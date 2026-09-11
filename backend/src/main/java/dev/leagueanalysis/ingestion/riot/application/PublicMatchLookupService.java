package dev.leagueanalysis.ingestion.riot.application;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded work on one application instance. Restart requires an explicit retry. */
@Service
public class PublicMatchLookupService {
    private final RiotIngestionService ingestion;
    private final PublicMatchLookupStore store;
    private final Clock clock;
    private final boolean enabled;
    private final Executor worker;
    private final Map<RequestKey, UUID> active = new HashMap<>();
    // Unverified user input must not survive a crash/restart. It is used only to
    // label active responses until the Account lookup verifies ownership.
    private final Map<UUID, RiotIngestionCommand> pendingIdentities = new HashMap<>();
    private final Map<String, List<Instant>> submissions = new HashMap<>();

    @Autowired
    public PublicMatchLookupService(RiotIngestionService ingestion, PublicMatchLookupStore store, Clock clock,
            @Value("${league-analysis.riot.public-lookup-enabled:false}") boolean enabled) {
        this(ingestion, store, clock, enabled, new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), runnable -> {
                    var thread = new Thread(runnable, "public-match-lookup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()));
    }

    PublicMatchLookupService(RiotIngestionService ingestion, PublicMatchLookupStore store, Clock clock,
            boolean enabled, Executor worker) {
        this.ingestion = ingestion;
        this.store = store;
        this.clock = clock;
        this.enabled = enabled;
        this.worker = worker;
    }

    @PostConstruct
    public void interruptPreviousRuns() { store.failInterrupted(clock.instant()); }

    @PreDestroy
    public void close() {
        if (worker instanceof ExecutorService executor) executor.shutdownNow();
    }

    public synchronized Submission submit(String gameName, String tagLine, String socketPeer) {
        var command = new RiotIngestionCommand(gameName, tagLine, 5);
        if (command.gameName().codePoints().anyMatch(Character::isISOControl)
                || command.tagLine().codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_RIOT_ID");
        if (!enabled) throw new PublicLookupException(503, "Live lookup is unavailable. Explore the sample match.", null);
        var now = clock.instant();
        var cooldown = store.latestCooldown().filter(time -> time.isAfter(now));
        if (cooldown.isPresent()) throw new PublicLookupException(429, "Riot is cooling down. Try again after the indicated time.", cooldown.get());
        var key = new RequestKey(command.gameName().toLowerCase(Locale.ROOT), command.tagLine().toLowerCase(Locale.ROOT));
        if (active.containsKey(key)) return new Submission(202, get(active.get(key)));
        var fresh = store.findFresh(command, now.minusSeconds(900));
        if (fresh.isPresent()) return new Submission(200, fresh.get());
        if (active.size() >= 5) throw busy(now.plusSeconds(2));
        // Only the actual socket peer is used. All clients behind a proxy share its budget.
        submissions.values().forEach(times -> times.removeIf(time -> !time.isAfter(now.minusSeconds(60))));
        submissions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (!submissions.containsKey(socketPeer) && submissions.size() >= 4096) throw busy(now.plusSeconds(60));
        var times = submissions.computeIfAbsent(socketPeer, ignored -> new ArrayList<>());
        if (times.size() >= 6) throw new PublicLookupException(429, "Too many lookups from this connection. Try again shortly.", times.getFirst().plusSeconds(60));
        UUID runId = store.startPublicRun(command, now);
        active.put(key, runId);
        pendingIdentities.put(runId, command);
        try {
            worker.execute(() -> execute(key, runId, command));
        } catch (RejectedExecutionException exception) {
            active.remove(key);
            pendingIdentities.remove(runId);
            store.failPublicRun(runId, now);
            throw busy(now.plusSeconds(2));
        }
        times.add(now);
        return new Submission(202, get(runId));
    }

    private void execute(RequestKey key, UUID runId, RiotIngestionCommand command) {
        try {
            // Work already queued before a 429 must also respect the persisted cooldown.
            var cooldown = store.latestCooldown().filter(time -> time.isAfter(clock.instant()));
            if (cooldown.isPresent()) {
                store.stopForCooldown(runId, cooldown.get(), clock.instant());
            } else ingestion.executePublic(runId, command);
        } catch (RuntimeException exception) {
            store.failPublicRun(runId, clock.instant());
        } finally {
            synchronized (this) {
                active.remove(key);
                pendingIdentities.remove(runId);
            }
        }
    }

    public synchronized PublicMatchLookup get(UUID runId) {
        var lookup = store.readPublicRun(runId)
                .orElseThrow(() -> new PublicLookupException(404, "Lookup not found. Search again.", null));
        var pending = pendingIdentities.get(runId);
        if (pending != null && lookup.gameName().isEmpty() && lookup.tagLine().isEmpty()) {
            return new PublicMatchLookup(lookup.runId(), pending.gameName(), pending.tagLine(), lookup.status(),
                    lookup.message(), lookup.retryNotBefore(), lookup.matches());
        }
        return lookup;
    }

    private PublicLookupException busy(Instant retry) {
        return new PublicLookupException(429, "Lookup is busy. Try again shortly.", retry);
    }

    private record RequestKey(String gameName, String tagLine) {}

    public record Submission(int httpStatus, PublicMatchLookup lookup) {}
}
