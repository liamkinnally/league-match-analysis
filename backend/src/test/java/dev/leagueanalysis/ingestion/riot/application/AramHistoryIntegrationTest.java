package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.analysis.death.application.CoarseMapProjector;
import dev.leagueanalysis.analysis.death.domain.CoarseMapRegion;
import dev.leagueanalysis.analysis.match.application.MatchDevelopmentService;
import dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import dev.leagueanalysis.support.PublicLookupGatewayFixture;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AramHistoryIntegrationTest {
    @Autowired JdbcRiotIngestionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MatchDevelopmentService development;
    final Clock clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);
    final Queue<Runnable> work = new ArrayDeque<>();
    PublicLookupGatewayFixture gateway;
    PublicMatchLookupService service;
    @BeforeEach void setup() {
        jdbc.execute("truncate table league_analysis.ingestion_run, league_analysis.source_payload cascade");
        gateway = new PublicLookupGatewayFixture(json, clock);
        service = new PublicMatchLookupService(new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock),
                store, clock, true, work::add);
    }
    @AfterEach void cleanup() { service.close(); }
    void drain() { while (!work.isEmpty()) work.remove().run(); }
    PublicMatchLookup submit(String name, int queue) {
        var request = service.submit(name, "NA1", queue, "peer"); drain();
        return service.get(request.lookup().runId());
    }

    @Test void bothAramMapsRetainSummaryAndTimelineWithoutSrGeographyLanesOrObjectives() {
        for (int map : List.of(12, 14)) {
            gateway.aramMapId = map;
            var page = submit("Lookup" + (7500000000L + map), 450);
            assertThat(page.status()).isEqualTo("COMPLETE");
            assertThat(page.matches()).hasSize(1);
            var match = page.matches().getFirst();
            assertThat(match.queueId()).isEqualTo(450);
            assertThat(match.position()).isEqualTo("UNKNOWN");
            assertThat(match.timelineAvailable()).isFalse();
            assertThat(store.hasSummary(match.matchId(), PublicLookupGatewayFixture.PUUID, 450)).isTrue();
            assertThat(store.hasSummary(match.matchId(), PublicLookupGatewayFixture.PUUID, 0)).isTrue();
            gateway.calls.clear();
            assertThat(service.submit(page.gameName(), "NA1", 450, "peer").lookup().runId()).isEqualTo(page.runId());
            assertThat(gateway.calls).isEmpty();
            var timeline = service.requestTimeline(match.matchId(), "peer"); drain();
            assertThat(service.timeline(match.matchId()).status()).isEqualTo("AVAILABLE");
            assertThat(jdbc.queryForObject("select queue_id from league_analysis.ingestion_run where id=?", Integer.class, timeline.runId())).isEqualTo(450);
            assertThat(gateway.calls).containsExactly("timeline");
            var view = development.load(match.matchId(), 6, 1).orElseThrow();
            assertThat(view.summary().mapId()).isEqualTo(map);
            assertThat(view.summary().gameMode()).isEqualTo("ARAM");
            assertThat(view.timelineAvailable()).isTrue();
            assertThat(view.samples()).isNotEmpty();
            assertThat(view.teams()).allSatisfy(team -> assertThat(team.objectives()).containsOnlyKeys("tower", "inhibitor"));
            assertThat(view.events()).noneMatch(event -> event.type().equals("ELITE_MONSTER_KILL"));
            var location = new CoarseMapProjector().project(map, 5000, 5000);
            assertThat(location.region()).isEqualTo(CoarseMapRegion.UNKNOWN);
            assertThat(location.limitationCodes()).contains("UNSUPPORTED_MAP");
        }
    }

    @Test void mixedCurrentQueuesIncludeAramAndPreserveRawPaginationAndHistoricalLegacyQueues() {
        var first = submit("History832", 0);
        assertThat(first.matches()).hasSize(14);
        assertThat(first.matches()).extracting(PublicMatchLookup.MatchSummary::queueId).contains(450).doesNotContain(430, 490);
        var next = service.older(first.runId(), "peer"); drain();
        assertThat(service.get(next.lookup().runId()).matches()).hasSize(11);
        assertThat(gateway.pages).containsExactly("0:0:20:" + clock.instant().getEpochSecond(), "0:20:20:" + clock.instant().getEpochSecond());
        for (int legacy : List.of(430, 490)) {
            var page = submit("Lookup" + (7600000000L + legacy), legacy);
            assertThat(page.matches()).hasSize(1);
            assertThat(page.matches().getFirst().queueId()).isEqualTo(legacy);
        }
    }

    @Test void queueMapCompatibilityRejectsAramOnSrAndSrOnAram() {
        gateway.mapOverride = 11;
        assertThat(submit("Lookup7700000001", 450).status()).isEqualTo("FAILED");
        gateway.mapOverride = 12;
        assertThat(submit("Lookup7700000002", 420).status()).isEqualTo("FAILED");
        gateway.mapOverride = 14;
        assertThat(submit("Lookup7700000003", 480).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_match", Integer.class)).isZero();
    }
}
