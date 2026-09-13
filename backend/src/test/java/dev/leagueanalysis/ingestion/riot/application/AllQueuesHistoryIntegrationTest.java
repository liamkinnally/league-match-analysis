package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import dev.leagueanalysis.support.PublicLookupGatewayFixture;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AllQueuesHistoryIntegrationTest {
    @Autowired JdbcRiotIngestionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
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
    PublicMatchLookup submit(int queue) {
        var request = service.submit("Mixed", "NA1", queue, "peer");
        drain();
        return service.get(request.lookup().runId());
    }

    @Test void defaultSearchCollectsMixedQueuesAndProjectsTheirActualTypes() {
        gateway.rawQueues = List.of(420, 440, 400, 450, 430, 1700, 480, 490);
        gateway.sparseUnsupportedDetail = true;
        var submitted = service.submit("Mixed", "NA1", "peer");
        drain();
        var page = service.get(submitted.lookup().runId());
        assertThat(page.queueId()).isZero();
        assertThat(page.status()).isEqualTo("COMPLETE");
        assertThat(page.matches()).hasSize(7);
        assertThat(json.valueToTree(page).get("matches")).extracting(node -> node.get("queueId").intValue())
                .containsExactly(420, 440, 400, 450, 430, 480, 490);
        assertThat(page.hasMore()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_match where queue_id in (0,1700)", Integer.class)).isZero();
        assertThat(gateway.calls).doesNotContain("timeline");
        assertThat(gateway.pages).containsExactly("0:0:20:" + clock.instant().getEpochSecond());
    }

    @Test void filteringUsesSeparateServerHistoryAndReusesStoredConcreteSummaries() {
        gateway.rawQueues = List.of(420, 440, 420, 450, 440);
        var all = submit(0);
        gateway.calls.clear();
        var solo = submit(420);
        assertThat(solo.queueId()).isEqualTo(420);
        assertThat(solo.matches()).hasSize(2);
        assertThat(gateway.calls).containsExactly("account", "list");
        assertThat(solo.matches()).allMatch(match -> all.matches().contains(match));
        gateway.calls.clear();
        assertThat(service.submit("Mixed", "NA1", 0, "peer").lookup().runId()).isEqualTo(all.runId());
        assertThat(service.submit("Mixed", "NA1", 420, "peer").lookup().runId()).isEqualTo(solo.runId());
        assertThat(gateway.calls).isEmpty();
        assertThat(store.readPageCommand(all.runId()).orElseThrow().queueId()).isZero();
        assertThatThrownBy(() -> service.refresh(all.runId(), "peer")).isInstanceOf(PublicLookupException.class);
    }

    @Test void timelineRequestsKeepConcreteQueueAndCannotUseLookupSentinel() {
        var all = submit(0);
        var timeline = service.requestTimeline(all.matches().getFirst().matchId(), "peer");
        assertThat(jdbc.queryForObject("select queue_id from league_analysis.ingestion_run where id=?", Integer.class, timeline.runId()))
                .isEqualTo(420);
        assertThatThrownBy(() -> jdbc.update("update league_analysis.ingestion_run set queue_id=0 where id=?", timeline.runId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        drain();
        assertThat(service.timeline(timeline.matchId()).status()).isEqualTo("AVAILABLE");
        assertThat(service.get(all.runId()).queueId()).isZero();
    }

    @Test void unsupportedRawPageRetainsContinuationOffsetWithoutInventingFailureOrSupportedRows() {
        var queues = new ArrayList<Integer>(Collections.nCopies(20, 1700));
        queues.addAll(List.of(420, 440, 1700));
        gateway.rawQueues = queues;
        var first = submit(0);
        assertThat(first.status()).isEqualTo("EMPTY");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.matches()).isEmpty();
        var older = service.older(first.runId(), "peer");
        drain();
        var second = service.get(older.lookup().runId());
        assertThat(second.queueId()).isZero();
        assertThat(second.previousRunId()).isEqualTo(first.runId());
        assertThat(second.matches()).hasSize(2);
        assertThat(second.hasMore()).isFalse();
        assertThat(gateway.pages).containsExactly("0:0:20:" + clock.instant().getEpochSecond(), "0:20:20:" + clock.instant().getEpochSecond());
        assertThat(store.readPageCommand(second.runId()).orElseThrow().start()).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.ingestion_item where status='SKIPPED'", Integer.class)).isEqualTo(21);
    }
}
