package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Shared, process-local Riot budgets. Exhaustion never waits or contacts the delegate. */
public final class BudgetedRiotHttpTransport implements RiotHttpTransport {
    private final RiotHttpTransport delegate;
    private final Clock clock;
    private final Map<String, HostBudget> hosts = new HashMap<>();

    public BudgetedRiotHttpTransport(RiotHttpTransport delegate, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Response send(HttpRequest request, Duration timeout, int maxResponseBytes)
            throws IOException, InterruptedException {
        var route = Route.of(request);
        final HostBudget host;
        final Budget method;
        final Reservation reservation;
        synchronized (this) {
            var now = clock.instant();
            host = hosts.computeIfAbsent(request.uri().getHost().toLowerCase(Locale.ROOT), ignored -> new HostBudget());
            method = host.methods.computeIfAbsent(route.method(), ignored -> new Budget(Map.of()));
            var blocked = new Block(now, "application")
                    .later(host.app.blockedUntil, "application")
                    .later(host.services.getOrDefault(route.service(), Instant.MIN), "service")
                    .later(method.blockedUntil, "method")
                    .later(host.app.retryAt(now), "application")
                    .later(method.retryAt(now), "method");
            if (blocked.until().isAfter(now)) return localThrottle(now, blocked);
            reservation = new Reservation(now);
            host.app.reserve(reservation, now);
            method.reserve(reservation, now);
        }
        Response response = null;
        try {
            response = delegate.send(request, timeout, maxResponseBytes);
            return response;
        } finally {
            synchronized (this) {
                var now = clock.instant();
                // Keep a completed call for a full window after completion. The provider may
                // have counted it at any point while it was in flight, even after a reset.
                reservation.completed = now;
                long appOverlap = host.app.complete(reservation);
                long methodOverlap = method.complete(reservation);
                if (response != null) {
                    host.app.observe(response.headers(), "App", now, appOverlap);
                    method.observe(response.headers(), "Method", now, methodOverlap);
                    if (response.statusCode() == 429) {
                        var until = retryAfter(response.headers(), now);
                        switch (response.headers().firstValue("X-Rate-Limit-Type").orElse("").toLowerCase(Locale.ROOT)) {
                            case "method" -> method.blockedUntil = later(method.blockedUntil, until);
                            case "service" -> host.services.merge(route.service(), until, BudgetedRiotHttpTransport::later);
                            default -> host.app.blockedUntil = later(host.app.blockedUntil, until);
                        }
                    }
                }
            }
        }
    }

    private static Response localThrottle(Instant now, Block blocked) {
        var delay = Duration.between(now, blocked.until());
        long seconds = Math.max(1, delay.getSeconds() + (delay.getNano() == 0 ? 0 : 1));
        return new Response(429, HttpHeaders.of(Map.of(
                "Retry-After", List.of(Long.toString(seconds)),
                "X-Rate-Limit-Type", List.of(blocked.scope()),
                "X-League-Rate-Limit-Source", List.of("local")), (name, value) -> true), new byte[0]);
    }

    private static Instant retryAfter(HttpHeaders headers, Instant now) {
        var value = headers.firstValue("Retry-After").orElse("").trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds >= 0) return now.plusSeconds(Math.max(1, seconds));
        } catch (NumberFormatException | DateTimeException | ArithmeticException ignored) {
            // An HTTP-date is also legal Retry-After syntax.
        }
        try {
            var date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return date.isAfter(now) ? date : now.plusSeconds(1);
        } catch (DateTimeException ignored) {
            return now.plusSeconds(60);
        }
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private record Block(Instant until, String scope) {
        Block later(Instant candidate, String candidateScope) {
            return candidate.isAfter(until) ? new Block(candidate, candidateScope) : this;
        }
    }

    private static final class HostBudget {
        private final Budget app = new Budget(Map.of(1L, 20L, 120L, 100L));
        private final Map<String, Budget> methods = new HashMap<>();
        private final Map<String, Instant> services = new HashMap<>();
    }

    private static final class Reservation {
        private final Instant started;
        private Instant completed;
        Reservation(Instant started) { this.started = started; }
        Instant expires(long seconds) { return (completed == null ? started : completed).plusSeconds(seconds); }
    }

    private static final class Budget {
        private Map<Long, Window> windows = new HashMap<>();
        private final List<Reservation> reservations = new ArrayList<>();
        private final Map<Reservation, Long> overlapping = new HashMap<>();
        private Instant blockedUntil = Instant.MIN;

        Budget(Map<Long, Long> limits) {
            limits.forEach((seconds, limit) -> windows.put(seconds, new Window(seconds, limit)));
        }

        Instant retryAt(Instant now) {
            prune(now);
            Instant retry = now;
            for (var window : windows.values()) {
                window.observations.removeIf(observation -> !observation.until.isAfter(now));
                var expiries = reservations.stream()
                        .filter(reservation -> reservation.completed == null || reservation.expires(window.seconds).isAfter(now))
                        .map(reservation -> reservation.completed == null ? now.plusSeconds(1) : reservation.expires(window.seconds))
                        .sorted().toList();
                if (expiries.size() >= window.limit) {
                    // Enough reservations must expire to make space for one more call.
                    retry = later(retry, expiries.get((int) (expiries.size() - window.limit)));
                }
                for (var observation : window.observations) {
                    if (observation.count >= window.limit) retry = later(retry, observation.until);
                }
            }
            return retry;
        }

        void reserve(Reservation reservation, Instant now) {
            prune(now);
            // A delayed response can omit any overlapping request, including one that has
            // already completed without usable headers. Preserve overlap counts until that
            // response arrives; timestamps alone cannot order same-clock concurrent calls.
            long pending = overlapping.size();
            overlapping.replaceAll((active, count) -> count + 1);
            overlapping.put(reservation, pending);
            reservations.add(reservation);
            for (var window : windows.values()) {
                window.observations.removeIf(observation -> !observation.until.isAfter(now));
                window.observations.forEach(observation -> observation.count++);
            }
        }

        long complete(Reservation reservation) {
            return overlapping.remove(reservation);
        }

        void observe(HttpHeaders headers, String scope, Instant now, long overlapCount) {
            var limits = parseWindows(headers, "X-" + scope + "-Rate-Limit", false);
            var counts = parseWindows(headers, "X-" + scope + "-Rate-Limit-Count", true);
            // Do not relax known/default windows based on partial, conflicting or malformed data.
            if (limits.isEmpty() || !limits.keySet().equals(counts.keySet())) return;
            var updated = new HashMap<Long, Window>();
            limits.forEach((seconds, limit) -> {
                var window = windows.getOrDefault(seconds, new Window(seconds, limit));
                window.limit = limit;
                var observed = new Observation(counts.get(seconds) + overlapCount, now.plusSeconds(seconds));
                // A later observation with an equal/higher count dominates earlier snapshots.
                // Lower or out-of-order counts never release an existing reservation early.
                window.observations.removeIf(previous -> !previous.until.isAfter(now)
                        || previous.count <= observed.count);
                window.observations.add(observed);
                updated.put(seconds, window);
            });
            windows = updated;
            prune(now);
        }

        private void prune(Instant now) {
            long retention = Math.max(120, windows.keySet().stream().mapToLong(Long::longValue).max().orElse(120));
            reservations.removeIf(reservation -> reservation.completed != null && !reservation.expires(retention).isAfter(now));
        }
    }

    private static Map<Long, Long> parseWindows(HttpHeaders headers, String name, boolean counts) {
        var values = headers.allValues(name);
        if (values.isEmpty()) return Map.of();
        var parsed = new LinkedHashMap<Long, Long>();
        try {
            for (var entry : String.join(",", values).split(",", -1)) {
                var parts = entry.trim().split(":", -1);
                if (parts.length != 2) return Map.of();
                long value = Long.parseLong(parts[0].trim());
                long seconds = Long.parseLong(parts[1].trim());
                // Defensive bounds prevent overflow and unbounded retention from invalid headers.
                if (value < (counts ? 0 : 1) || value > Integer.MAX_VALUE || seconds < 1 || seconds > 86400
                        || parsed.putIfAbsent(seconds, value) != null) return Map.of();
            }
        } catch (NumberFormatException ignored) {
            return Map.of();
        }
        return parsed;
    }

    private static final class Window {
        private final long seconds;
        private long limit;
        private final List<Observation> observations = new ArrayList<>();
        Window(long seconds, long limit) { this.seconds = seconds; this.limit = limit; }
    }

    private static final class Observation {
        private long count;
        private final Instant until;
        Observation(long count, Instant until) { this.count = count; this.until = until; }
    }

    /** Stable endpoint identities deliberately exclude account IDs, match IDs and query values. */
    private record Route(String service, String method) {
        static Route of(HttpRequest request) {
            var path = request.uri().getRawPath();
            String service;
            String method;
            if (path.startsWith("/lol/match/v5/matches/by-puuid/")) {
                service = "match-v5";
                method = "match-list";
            } else if (path.startsWith("/lol/match/v5/matches/")) {
                service = "match-v5";
                method = path.endsWith("/timeline") ? "match-timeline" : "match-detail";
            } else if (path.startsWith("/lol/league/v4/entries/by-puuid/")) {
                service = "league-v4";
                method = "league-entries-by-puuid";
            } else if (path.startsWith("/lol/summoner/v4/summoners/by-puuid/")) {
                service = "summoner-v4";
                method = "summoner-by-puuid";
            } else if (path.startsWith("/riot/account/v1/accounts/by-riot-id/")) {
                service = "account-v1";
                method = "account-by-riot-id";
            } else {
                // New endpoints need an explicit route identity before getting independent budgets.
                service = "other";
                method = "other";
            }
            return new Route(service, request.method() + ":" + method);
        }
    }
}
