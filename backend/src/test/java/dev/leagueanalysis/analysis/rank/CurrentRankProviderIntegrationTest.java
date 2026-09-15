package dev.leagueanalysis.analysis.rank;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.profile.JdbcPlayerProfileStore;
import dev.leagueanalysis.analysis.profile.PlayerProfileService;
import dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.application.IngestionRunStatus;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import dev.leagueanalysis.support.PublicLookupGatewayFixture;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "league-analysis.riot.api-key=",
        "league-analysis.riot.public-lookup-enabled=false"
})
@Import(PostgresTestConfiguration.class)
class CurrentRankProviderIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private static final String PUUID = PublicLookupGatewayFixture.PUUID;
    @Autowired JdbcPlayerProfileStore ranks;
    @Autowired JdbcRiotIngestionStore ingestion;
    @Autowired PlayerProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.execute("truncate table league_analysis.ingestion_run,league_analysis.source_payload cascade");
        jdbc.execute("delete from league_analysis.privacy_exclusion");
    }

    @Test void mixedQueueResponseRecoversPersistedFailureAndRehydratesWithoutAnotherRequest() {
        var fixture = new PublicLookupGatewayFixture(json, Clock.fixed(NOW, ZoneOffset.UTC));
        var command = new RiotIngestionCommand("Invented", "NA1", 20, 420, 0, NOW.getEpochSecond(), null);
        var run = ingestion.startPublicRun(command, NOW);
        var account = fixture.resolveAccount(new RiotId(command.gameName(), command.tagLine()));
        ingestion.recordResolvedAccount(run, account.account(), ingestion.saveCapture(run, account.source()));
        ingestion.recordVerifiedRequestedIdentity(run, command);
        ingestion.finishRun(run, IngestionRunStatus.COMPLETE, null, null, NOW);

        var failedRefresh = UUID.randomUUID();
        assertThat(ranks.claim("NA1", PUUID, NOW, failedRefresh)).isTrue();
        ranks.failure("NA1", PUUID, "UPSTREAM_UNAVAILABLE", NOW.plusSeconds(30), failedRefresh);
        var failed = ranks.read("NA1", PUUID).orElseThrow();
        assertThat(failed.values()).isNull();
        assertThat(failed.error()).isEqualTo("UPSTREAM_UNAVAILABLE");

        var now = new AtomicReference<>(NOW);
        var calls = new AtomicInteger();
        var work = new ArrayDeque<Runnable>();
        var properties = new RiotProperties("invented-key", "AMERICAS", "NA1", 420, 10000,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1));
        RiotHttpTransport transport = (request, timeout, maxBytes) -> {
            calls.incrementAndGet();
            return new RiotHttpTransport.Response(200, HttpHeaders.of(Map.of(), (name, value) -> true), """
                    [{"queueType":"RANKED_SOLO_5x5","tier":"GOLD","rank":"II","leaguePoints":37,"wins":6,"losses":4},
                     {"queueType":"JADE_RANKED_SOLO_5x5","tier":"SALT","rank":"III","leaguePoints":7,"wins":1,"losses":2},
                     {"queueType":"RANKED_FLEX_SR","tier":"SILVER","rank":"I","leaguePoints":23,"wins":5,"losses":9}]
                    """.getBytes(StandardCharsets.UTF_8));
        };
        var provider = new CurrentRankProvider(ranks, properties, transport, json,
                () -> now.get().toEpochMilli(), work::add);
        assertThat(provider.refresh("NA1", PUUID, "RANKED_SOLO_5x5").retryNotBefore()).isEqualTo(NOW.plusSeconds(30));
        assertThat(work).isEmpty();
        assertThat(calls).hasValue(0);

        now.set(NOW.plusSeconds(31));
        assertThat(provider.refresh("NA1", PUUID, "RANKED_SOLO_5x5").refreshing()).isTrue();
        assertThat(work).hasSize(1);
        assertThat(ranks.read("NA1", PUUID).orElseThrow().leaseUntil()).isAfter(now.get());
        work.remove().run();

        var saved = ranks.read("NA1", PUUID).orElseThrow();
        var solo = new RankSnapshotStore.Value("ranked", "GOLD", "II", 37, 6, 4);
        var flex = new RankSnapshotStore.Value("ranked", "SILVER", "I", 23, 5, 9);
        assertThat(saved.values()).containsOnlyKeys("RANKED_SOLO_5x5", "RANKED_FLEX_SR")
                .containsEntry("RANKED_SOLO_5x5", solo).containsEntry("RANKED_FLEX_SR", flex);
        assertThat(saved.fetchedAt()).isEqualTo(now.get());
        assertThat(saved.error()).isNull();
        assertThat(saved.retryAt()).isNull();
        assertThat(saved.leaseUntil()).isNull();
        assertThat(jdbc.queryForList("select queue_type from league_analysis.rank_observation order by queue_type", String.class))
                .containsExactly("RANKED_FLEX_SR", "RANKED_SOLO_5x5");

        var publicProfile = profiles.load(run, null);
        assertThat(publicProfile.identity().gameName()).isEqualTo("Invented");
        assertThat(publicProfile.soloRank().status()).isEqualTo("ranked");
        assertThat(publicProfile.soloRank().tier()).isEqualTo("GOLD");
        assertThat(publicProfile.soloRank().division()).isEqualTo("II");
        assertThat(publicProfile.soloRank().leaguePoints()).isEqualTo(37);
        assertThat(publicProfile.soloRank().wins()).isEqualTo(6);
        assertThat(publicProfile.soloRank().losses()).isEqualTo(4);
        assertThat(publicProfile.soloRank().winRate()).isEqualTo(60.0);
        assertThat(publicProfile.rankHistory().observations()).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("ranked");
            assertThat(observation.leaguePoints()).isEqualTo(37);
            assertThat(observation.observedAt()).isEqualTo(now.get());
        });
        assertThat(json.writeValueAsString(publicProfile)).doesNotContain(PUUID, "JADE", "SALT", "refreshId");

        var restarted = new CurrentRankProvider(ranks, properties, transport, json,
                () -> now.get().toEpochMilli(), work::add);
        var rehydrated = restarted.peek("NA1", PUUID, "RANKED_SOLO_5x5");
        assertThat(rehydrated.value()).isEqualTo(solo);
        assertThat(rehydrated.error()).isNull();
        assertThat(rehydrated.retryNotBefore()).isNull();
        assertThat(rehydrated.refreshing()).isFalse();
        assertThat(rehydrated.fetchedAt()).isEqualTo(now.get());
        assertThat(restarted.peek("NA1", PUUID, "RANKED_FLEX_SR").value()).isEqualTo(flex);
        assertThat(work).isEmpty();
        assertThat(calls).hasValue(1);
    }
}
