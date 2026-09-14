package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetedRiotHttpTransportTest {
    private static final String HOST = "americas.api.riotgames.com";
    private static final String DETAIL = "/lol/match/v5/matches/NA1_1";
    private static final String RANK = "/lol/league/v4/entries/by-puuid/private-player";
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger calls = new AtomicInteger();
    private RiotHttpTransport.Response next = response(200, Map.of());
    private final BudgetedRiotHttpTransport transport = new BudgetedRiotHttpTransport((request, timeout, max) -> {
        calls.incrementAndGet();
        return next;
    }, clock);

    @Test void summonerHasExplicitMethodBudgetSharedAcrossIdentitiesButSeparateFromRanks() throws Exception {
        next=response(200,Map.of("X-Method-Rate-Limit","1:10","X-Method-Rate-Limit-Count","1:10"));
        assertThat(send("/lol/summoner/v4/summoners/by-puuid/invented-one").statusCode()).isEqualTo(200);
        next=response(200,Map.of());
        assertThat(send("/lol/summoner/v4/summoners/by-puuid/invented-two").statusCode()).isEqualTo(429);
        assertThat(send(RANK).statusCode()).isEqualTo(200);
        assertThat(send("/unknown-endpoint").statusCode()).isEqualTo(200);
    }

    @Test
    void mixedCallsShareConservativeHostLimitsAndLocalResponsesAreSafe() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(send(i % 2 == 0 ? DETAIL : RANK).statusCode()).isEqualTo(200);
        }
        var denied = send(DETAIL);
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.headers().firstValue("Retry-After")).contains("1");
        assertThat(denied.headers().firstValue("X-League-Rate-Limit-Source")).contains("local");
        assertThat(denied.headers().firstValue("X-Rate-Limit-Type")).contains("application");
        assertThat(denied.body()).isEmpty();
        assertThat(denied.headers().map().toString()).doesNotContain("secret", "private-player", "NA1_1");
        assertThat(calls).hasValue(20);
        assertThat(send("europe.api.riotgames.com", DETAIL).statusCode()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        assertThat(send(DETAIL).statusCode()).isEqualTo(200);
    }

    @Test
    void longDefaultWindowResetsAtTwoMinutes() throws Exception {
        for (int batch = 0; batch < 5; batch++) {
            for (int i = 0; i < 20; i++) assertThat(send(DETAIL).statusCode()).isEqualTo(200);
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(send(RANK).headers().firstValue("Retry-After")).contains("115");
        assertThat(calls).hasValue(100);
        clock.advance(Duration.ofSeconds(115));
        assertThat(send(RANK).statusCode()).isEqualTo(200);
    }

    @Test
    void learnsAppAndMethodWindowsWithoutSeparatingResourceIdsOrQueryParameters() throws Exception {
        next = response(200, Map.of("X-App-Rate-Limit", "30:1,200:120", "X-App-Rate-Limit-Count", "1:1,1:120",
                "X-Method-Rate-Limit", "2:10", "X-Method-Rate-Limit-Count", "1:10"));
        send(DETAIL);
        next = response(200, Map.of());
        assertThat(send("/lol/match/v5/matches/NA1_2?secret=query").statusCode()).isEqualTo(200);
        var denied = send(DETAIL);
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.headers().firstValue("Retry-After")).contains("10");
        assertThat(denied.headers().firstValue("X-Rate-Limit-Type")).contains("method");
        for (int i = 0; i < 28; i++) assertThat(send(RANK).statusCode()).isEqualTo(200);
        assertThat(send(RANK).statusCode()).isEqualTo(429);
        clock.advance(Duration.ofSeconds(10));
        assertThat(send(DETAIL).statusCode()).isEqualTo(200);
    }

    @Test
    void providerCountsIncludeCallsOutsideThisProcess() throws Exception {
        next = response(200, Map.of("X-App-Rate-Limit", "20:1,100:120", "X-App-Rate-Limit-Count", "19:1,99:120"));
        send(DETAIL);
        next = response(200, Map.of());
        assertThat(send(RANK).statusCode()).isEqualTo(200);
        assertThat(send(DETAIL).statusCode()).isEqualTo(429);
        assertThat(calls).hasValue(2);
    }

    @Test
    void malformedOrIncompleteHeadersDoNotRelaxConservativeLimits() throws Exception {
        for (var headers : List.of(
                Map.of("X-App-Rate-Limit", "999:1,999:120"),
                Map.of("X-App-Rate-Limit", "999:1,bad", "X-App-Rate-Limit-Count", "1:1"),
                Map.of("X-App-Rate-Limit", "999:1,999:120", "X-App-Rate-Limit-Count", "1:1"),
                Map.of("X-App-Rate-Limit", "0:1,-1:120", "X-App-Rate-Limit-Count", "0:1,0:120"),
                Map.of("X-App-Rate-Limit", "999:1,999:1", "X-App-Rate-Limit-Count", "1:1"))) {
            var isolated = new BudgetedRiotHttpTransport((request, timeout, bytes) -> response(200, headers), clock);
            for (int i = 0; i < 20; i++) assertThat(send(isolated, HOST, DETAIL).statusCode()).isEqualTo(200);
            assertThat(send(isolated, HOST, RANK).statusCode()).isEqualTo(429);
        }
    }

    @Test
    void methodRetryAfterOnlyBlocksThatEndpointOnThatHost() throws Exception {
        next = response(429, Map.of("X-Rate-Limit-Type", "method", "Retry-After", "7"));
        send(DETAIL);
        next = response(200, Map.of());
        assertThat(send("/lol/match/v5/matches/NA1_2").statusCode()).isEqualTo(429);
        assertThat(send(DETAIL + "/timeline").statusCode()).isEqualTo(200);
        assertThat(send("europe.api.riotgames.com", DETAIL).statusCode()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(7));
        assertThat(send(DETAIL).statusCode()).isEqualTo(200);
    }

    @Test
    void serviceRetryAfterBlocksSiblingMethodsButNotOtherServices() throws Exception {
        next = response(429, Map.of("X-Rate-Limit-Type", "service", "Retry-After", "7"));
        send(DETAIL);
        next = response(200, Map.of());
        assertThat(send(DETAIL + "/timeline").statusCode()).isEqualTo(429);
        assertThat(send("/lol/match/v5/matches/by-puuid/player/ids").statusCode()).isEqualTo(429);
        assertThat(send(RANK).statusCode()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(7));
        assertThat(send(DETAIL).statusCode()).isEqualTo(200);
    }

    @Test
    void applicationOrUnknownRetryScopeBlocksHostAndSupportsHttpDate() throws Exception {
        for (var type : List.of("application", "unknown")) {
            next = response(429, Map.of("X-Rate-Limit-Type", type, "Retry-After", "Sat, 12 Sep 2026 12:00:09 GMT"));
            var isolated = new BudgetedRiotHttpTransport((request, timeout, bytes) -> next, clock);
            send(isolated, HOST, DETAIL);
            next = response(200, Map.of());
            assertThat(send(isolated, HOST, RANK).headers().firstValue("Retry-After")).contains("9");
            assertThat(send(isolated, "na1.api.riotgames.com", RANK).statusCode()).isEqualTo(200);
        }
        clock.advance(Duration.ofSeconds(9));
    }

    @Test
    void missingOrInvalidRetryAfterUsesConservativeMinute() throws Exception {
        for (var headers : List.of(Map.<String, String>of(), Map.of("Retry-After", "-1"), Map.of("Retry-After", "garbage"))) {
            var isolated = new BudgetedRiotHttpTransport((request, timeout, bytes) -> response(429, headers), clock);
            send(isolated, HOST, DETAIL);
            assertThat(send(isolated, HOST, RANK).headers().firstValue("Retry-After")).contains("60");
        }
    }

    @Test
    void concurrentCallsCannotOversubscribeAndInflightReservationsSurviveReset() throws Exception {
        var entered = new CountDownLatch(20);
        var release = new CountDownLatch(1);
        var delegates = new AtomicInteger();
        var concurrent = new BudgetedRiotHttpTransport((request, timeout, bytes) -> {
            delegates.incrementAndGet();
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
            return response(200, Map.of());
        }, clock);
        try (var executor = Executors.newFixedThreadPool(30)) {
            var futures = new ArrayList<java.util.concurrent.Future<RiotHttpTransport.Response>>();
            try {
                for (int i = 0; i < 30; i++) futures.add(executor.submit(() -> send(concurrent, HOST, DETAIL)));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                clock.advance(Duration.ofSeconds(2));
                assertThat(send(concurrent, HOST, RANK).statusCode()).isEqualTo(429);
                assertThat(delegates).hasValue(20);
            } finally {
                release.countDown();
            }
            var accepted = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS).statusCode() == 200) accepted++;
            assertThat(accepted).isEqualTo(20);
        }
    }

    @Test
    void learningCountsPreservesOutstandingReservationsAndIgnoresStaleLowerCounts() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var order = new AtomicInteger();
        var learned = new BudgetedRiotHttpTransport((request, timeout, bytes) -> {
            if (order.incrementAndGet() == 1) {
                firstEntered.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
                return response(200, Map.of("X-App-Rate-Limit", "20:60", "X-App-Rate-Limit-Count", "1:60"));
            }
            return response(200, Map.of("X-App-Rate-Limit", "20:60", "X-App-Rate-Limit-Count", "19:60"));
        }, clock);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> send(learned, HOST, DETAIL));
            try {
                assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(send(learned, HOST, RANK).statusCode()).isEqualTo(200);
                assertThat(send(learned, HOST, DETAIL).statusCode()).isEqualTo(429);
                assertThat(order).hasValue(2);
            } finally {
                releaseFirst.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(send(learned, HOST, RANK).headers().firstValue("Retry-After")).contains("60");
        }
        clock.advance(Duration.ofSeconds(60));
        assertThat(send(learned, HOST, DETAIL).statusCode()).isEqualTo(200);
    }

    @Test
    void delayedProviderCountsIncludeCompletedOverlapsWithoutUsableHeaders() throws Exception {
        for (var scope : List.of("App", "Method")) {
            for (var completion : List.of("missing", "malformed", "failed")) {
                var firstEntered = new CountDownLatch(1);
                var releaseFirst = new CountDownLatch(1);
                var delegated = new AtomicInteger();
                var delayed = new BudgetedRiotHttpTransport((request, timeout, bytes) -> {
                    if (delegated.incrementAndGet() == 1) {
                        firstEntered.countDown();
                        if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
                        return response(200, Map.of("X-" + scope + "-Rate-Limit", "20:60",
                                "X-" + scope + "-Rate-Limit-Count", "19:60"));
                    }
                    if (completion.equals("failed")) throw new IOException("provider processed before read failed");
                    return response(200, completion.equals("malformed")
                            ? Map.of("X-" + scope + "-Rate-Limit-Count", "unusable") : Map.of());
                }, clock);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    var first = executor.submit(() -> send(delayed, HOST, DETAIL));
                    try {
                        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
                        if (completion.equals("failed")) {
                            assertThatThrownBy(() -> send(delayed, HOST, DETAIL)).isInstanceOf(IOException.class);
                        } else {
                            assertThat(send(delayed, HOST, DETAIL).statusCode()).isEqualTo(200);
                        }
                    } finally {
                        releaseFirst.countDown();
                    }
                    assertThat(first.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                }
                // The fixed clock is deliberate: ordering cannot rely on distinct timestamps.
                assertThat(send(delayed, HOST, DETAIL).statusCode()).as(scope + " / " + completion).isEqualTo(429);
                assertThat(delegated).hasValue(2);
            }
        }
    }

    @Test
    void retryAfterRoundsUpAndLaterResponsesCannotShortenBackoff() throws Exception {
        var pendingEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var order = new AtomicInteger();
        var backedOff = new BudgetedRiotHttpTransport((request, timeout, bytes) -> {
            if (order.incrementAndGet() == 1) {
                pendingEntered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
                return response(429, Map.of("Retry-After", "1"));
            }
            return response(429, Map.of("Retry-After", "10"));
        }, clock);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> send(backedOff, HOST, DETAIL));
            try {
                assertThat(pendingEntered.await(5, TimeUnit.SECONDS)).isTrue();
                send(backedOff, HOST, RANK);
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        clock.advance(Duration.ofMillis(500));
        assertThat(send(backedOff, HOST, DETAIL).headers().firstValue("Retry-After")).contains("10");
        clock.advance(Duration.ofMillis(9500));
        // A fresh provider response proves the local block expired.
        send(backedOff, HOST, DETAIL);
        assertThat(order).hasValue(3);
    }

    @Test
    void failedSendsStillConsumeReservedBudgetAndPreserveTransportArguments() throws Exception {
        var failed = new BudgetedRiotHttpTransport((request, timeout, bytes) -> {
            assertThat(timeout).isEqualTo(Duration.ofSeconds(3));
            assertThat(bytes).isEqualTo(1234);
            throw new IOException("upstream unavailable");
        }, clock);
        for (int i = 0; i < 20; i++) assertThatThrownBy(() -> send(failed, HOST, DETAIL)).isInstanceOf(IOException.class);
        assertThat(send(failed, HOST, DETAIL).statusCode()).isEqualTo(429);
    }

    private RiotHttpTransport.Response send(String path) throws Exception { return send(HOST, path); }
    private RiotHttpTransport.Response send(String host, String path) throws Exception { return send(transport, host, path); }
    private static RiotHttpTransport.Response send(RiotHttpTransport target, String host, String path) throws Exception {
        return target.send(HttpRequest.newBuilder(URI.create("https://" + host + path))
                .header("X-Riot-Token", "secret").GET().build(), Duration.ofSeconds(3), 1234);
    }
    private static RiotHttpTransport.Response response(int status, Map<String, String> headers) {
        var values = new java.util.HashMap<String, List<String>>();
        headers.forEach((name, value) -> values.put(name, List.of(value)));
        return new RiotHttpTransport.Response(status, HttpHeaders.of(values, (a, b) -> true), new byte[0]);
    }
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-12T12:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
