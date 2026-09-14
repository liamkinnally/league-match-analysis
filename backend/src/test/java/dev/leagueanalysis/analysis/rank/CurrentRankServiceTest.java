package dev.leagueanalysis.analysis.rank;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import tools.jackson.databind.ObjectMapper;

class CurrentRankServiceTest {
    final AtomicLong now = new AtomicLong(1_000_000);
    final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    final List<java.net.http.HttpRequest> requests = new ArrayList<>();
    int status = 200;
    String body = "[]";
    Map<String,List<String>> headers = Map.of();
    String key = "secret-test-key";
    RankRosterQuery query = id -> id.equals("missing") ? Optional.empty() : Optional.of(
            new RankRosterQuery.Roster(id, id.startsWith("OTHER") ? 450 : 420,
                    List.of(new RankRosterQuery.Player(1,"private-test-puuid"))));
    CurrentRankService service() {
        RiotHttpTransport transport = (request, timeout, bytes) -> {
            requests.add(request);
            return new RiotHttpTransport.Response(status, HttpHeaders.of(headers,(a,b)->true),body.getBytes(StandardCharsets.UTF_8));
        };
        return new CurrentRankService(query, new RiotProperties(key,"AMERICAS","NA1",420,10000,
                Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1)), transport,
                new ObjectMapper(),now::get,pending::add);
    }
    void drain() { while (!pending.isEmpty()) pending.remove().run(); }
    @Test void soloAndFlexShareOneProviderResponseAndRefresh() {
        query=id -> Optional.of(new RankRosterQuery.Roster(id,id.equals("NA1_flex") ? 440 : 420,
                List.of(new RankRosterQuery.Player(1,"private-test-puuid"))));
        body="[{\"queueType\":\"RANKED_SOLO_5x5\",\"tier\":\"GOLD\",\"rank\":\"II\",\"leaguePoints\":42,\"wins\":12,\"losses\":8},{\"queueType\":\"RANKED_FLEX_SR\",\"tier\":\"SILVER\",\"rank\":\"I\",\"leaguePoints\":19,\"wins\":3,\"losses\":2}]";
        var service=service();
        service.load("NA1_solo"); service.load("NA1_flex");
        assertThat(pending).hasSize(1); drain();
        assertThat(requests).hasSize(1);
        assertThat(service.load("NA1_solo").orElseThrow().players().getFirst().tier()).isEqualTo("GOLD");
        assertThat(service.load("NA1_flex").orElseThrow().players().getFirst().tier()).isEqualTo("SILVER");
    }
    @Test void queuedPlayersShareAuthenticationFailureAndItsExactBackoff() {
        var roster = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(id -> new RankRosterQuery.Player(id, "private-test-" + id)).toList();
        query = id -> Optional.of(new RankRosterQuery.Roster(id, 420,
                id.equals("NA1_other") ? List.of(new RankRosterQuery.Player(1, "private-new-player")) : roster));
        status = 403;
        var service = service();
        service.load("NA1_1");
        assertThat(pending).hasSize(10);
        drain();
        assertThat(requests).hasSize(1);
        assertThat(service.load("NA1_1").orElseThrow().players()).allSatisfy(player -> {
            assertThat(player.status()).isEqualTo("unavailable");
            assertThat(player.error()).isEqualTo("AUTH_UNAVAILABLE");
        });
        assertThat(service.load("NA1_other").orElseThrow().players().getFirst().error())
                .isEqualTo("AUTH_UNAVAILABLE");
        now.addAndGet(59_999);
        service.load("NA1_1");
        assertThat(pending).isEmpty();
        now.incrementAndGet();
        status = 200;
        service.load("NA1_1");
        assertThat(pending).hasSize(10);
        drain();
        assertThat(requests).hasSize(11);
        assertThat(service.load("NA1_1").orElseThrow().players())
                .allSatisfy(player -> assertThat(player.status()).isEqualTo("unranked"));
    }

    @Test void flexSelectsOnlyFlexAndExecutorSaturationReturnsUnavailable() {
        query=id -> Optional.of(new RankRosterQuery.Roster(id,440,List.of(new RankRosterQuery.Player(1,"private-test-puuid"))));
        body="[{\"queueType\":\"RANKED_FLEX_SR\",\"tier\":\"EMERALD\",\"rank\":\"III\",\"leaguePoints\":17}]";
        var service=service();service.load("NA1_1");drain();
        var result=service.load("NA1_1").orElseThrow();
        assertThat(result.queueType()).isEqualTo("RANKED_FLEX_SR");
        assertThat(result.players().getFirst().tier()).isEqualTo("EMERALD");
        var saturated=new CurrentRankService(query,new RiotProperties(key,"AMERICAS","NA1",420,10000,
                Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1)),
                (request,timeout,bytes) -> { throw new AssertionError("Must not send"); }, new ObjectMapper(),now::get,
                task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        assertThat(saturated.load("NA1_1").orElseThrow().players().getFirst().status()).isEqualTo("unavailable");
        assertThat(saturated.load("NA1_1").orElseThrow().refreshing()).isFalse();
    }
    @Test void simultaneousRequestsShareOneInFlightLookup() throws Exception {
        var service=service();
        try(var callers=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var calls=new ArrayList<java.util.concurrent.Callable<CurrentRankService.Result>>();
            for(int i=0;i<30;i++) calls.add(() -> service.load("NA1_1").orElseThrow());
            for(var result:callers.invokeAll(calls)) assertThat(result.get().refreshing()).isTrue();
        }
        assertThat(pending).hasSize(1);drain();assertThat(requests).hasSize(1);
    }
    @Test void limitsBurstRequestsAcrossDifferentPlayers() {
        query=id -> Optional.of(new RankRosterQuery.Roster(id,420,List.of(new RankRosterQuery.Player(1,id))));
        var service=service();
        for(int i=0;i<12;i++) service.load("NA1_"+i);
        drain();
        assertThat(requests).hasSize(10);
        assertThat(service.load("NA1_11").orElseThrow().players().getFirst().status()).isEqualTo("unavailable");
    }
    @Test void staleRankSurvivesFailureAndHttpDateRetryAfterIsRespected() {
        body="[{\"queueType\":\"RANKED_SOLO_5x5\",\"tier\":\"MASTER\",\"rank\":\"I\",\"leaguePoints\":42}]";
        var service=service();service.load("NA1_1");drain();now.addAndGet(360_000);
        status=429;headers=Map.of("Retry-After",List.of(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.Instant.ofEpochMilli(now.get()+120_000).atZone(java.time.ZoneOffset.UTC))));
        service.load("NA1_1");drain();
        var player=service.load("NA1_1").orElseThrow().players().getFirst();
        assertThat(player.status()).isEqualTo("ranked");assertThat(player.stale()).isTrue();
        assertThat(player.error()).isEqualTo("RATE_LIMITED");
        now.addAndGet(60_000);service.load("NA1_1");drain();assertThat(requests).hasSize(2);
    }
    @Test void rankedIsQueueSpecificAndCachedWithSingleFlightAndExpiry() {
        body="[{\"queueType\":\"RANKED_SOLO_5x5\",\"tier\":\"GOLD\",\"rank\":\"II\",\"leaguePoints\":42}]";
        var service=service();
        assertThat(service.load("NA1_1").orElseThrow().refreshing()).isTrue();
        service.load("NA1_1"); assertThat(pending).hasSize(1); drain();
        var player=service.load("NA1_1").orElseThrow().players().getFirst();
        assertThat(player.status()).isEqualTo("ranked"); assertThat(player.tier()).isEqualTo("GOLD");
        assertThat(player.cached()).isTrue(); assertThat(player.stale()).isFalse();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().uri().toString()).isEqualTo("https://na1.api.riotgames.com/lol/league/v4/entries/by-puuid/private-test-puuid");
        now.addAndGet(Duration.ofMinutes(6).toMillis());
        assertThat(service.load("NA1_1").orElseThrow().players().getFirst().stale()).isTrue();
        drain(); assertThat(requests).hasSize(2);
    }
    @Test void onlySuccessfulValidEmptyOrWrongQueueMeansUnranked() {
        for (var response : List.of("[]", "[{\"queueType\":\"RANKED_FLEX_SR\",\"tier\":\"GOLD\",\"rank\":\"II\",\"leaguePoints\":42}]")) {
            body=response; var service=service(); service.load("NA1_1");drain();
            assertThat(service.load("NA1_1").orElseThrow().players().getFirst().status()).isEqualTo("unranked");
        }
    }
    @Test void authMalformedAndMissingKeyAreUnavailableAndRedacted() {
        for(var response : List.of("{}", "broken", "[{}]", "[{\"queueType\":\"RANKED_FLEX_SR\"}]", "[{\"queueType\":\"RANKED_SOLO_5x5\",\"tier\":\"SECRET\"}]")) {
            body=response;var service=service();service.load("NA1_1");drain();
            assertThat(service.load("NA1_1").orElseThrow().players().getFirst().status()).isEqualTo("unavailable");
        }
        body="secret-test-key private-test-puuid";status=403;var service=service();service.load("NA1_1");drain();
        var result=service.load("NA1_1").orElseThrow();
        assertThat(result.players().getFirst().status()).isEqualTo("unavailable");
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain(key,"private-test-puuid");
        key="";var noKey=service();assertThat(noKey.load("NA1_1").orElseThrow().players().getFirst().status()).isEqualTo("unavailable");
    }
    @Test void retryAfterPreventsAdditionalCallsAndUnsupportedAndMissingDoNotCallRiot() {
        status=429; headers=Map.of("Retry-After",List.of("120")); var service=service();service.load("NA1_1");drain();
        now.addAndGet(60_000);service.load("NA1_1");drain();assertThat(requests).hasSize(1);
        now.addAndGet(61_000);service.load("NA1_1");drain();assertThat(requests).hasSize(2);
        assertThat(service.load("missing")).isEmpty();
        assertThat(service.load("OTHER_1").orElseThrow().queueType()).isNull();
        assertThat(service.load("EUW1_1").orElseThrow().players().getFirst().status()).isEqualTo("unavailable");
    }
}
