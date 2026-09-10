package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.support.*;
import java.time.*;
import java.util.*;
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
class PublicMatchLookupIntegrationTest {
    @Autowired JdbcRiotIngestionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final MutableClock clock = new MutableClock();
    final Queue<Runnable> work = new ArrayDeque<>();
    PublicLookupGatewayFixture gateway;
    PublicMatchLookupService service;

    @BeforeEach void setup() {
        jdbc.execute("truncate table league_analysis.ingestion_run, league_analysis.source_payload cascade");
        gateway = new PublicLookupGatewayFixture(json, clock);
        service = new PublicMatchLookupService(new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock),
                store, clock, true, work::add);
    }
    PublicMatchLookup run(String name) {
        UUID id = service.submit(name, "NA1", "peer").lookup().runId();
        work.remove().run();
        return service.get(id);
    }

    @Test void ingestsProviderMatchReturnsOnlyPublicSummaryCachesAndReusesCompleteStoredMatch() {
        var result = run("Invented");
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().matchId()).isEqualTo("NA1_7000000002");
        assertThat(result.matches().getFirst().participantId()).isEqualTo(6);
        assertThat(result.matches().getFirst().gameVersion()).isEqualTo("16.17.1");
        assertThat(result.matches().getFirst().endItemIds()).containsExactly(6631, 3047, 3071, 3051, 0, 0, 3340);
        assertThat(json.writeValueAsString(result)).doesNotContain("puuid", "capture", "lookup-invented-participant", "private");
        assertThat(store.findFresh(new RiotIngestionCommand("invented", "na1", 5), clock.instant().minusSeconds(900))).isPresent();
        assertThat(service.submit("Invented", "NA1", "peer").httpStatus()).isEqualTo(200);
        clock.now = clock.now.plusSeconds(901);
        gateway.calls.clear();
        assertThat(run("Invented").matches()).hasSize(1);
        assertThat(gateway.calls).containsExactly("account", "list");
    }

    @Test void completeRunWithNoEligibleStoredMatchesMapsToEmptyHistory() {
        var result = run("OtherQueue");
        jdbc.update("update league_analysis.riot_match set queue_id = 430 where match_id = ?", "NA1_7000000002");
        assertThat(service.get(result.runId()).status()).isEqualTo("EMPTY");
        assertThat(service.get(result.runId()).matches()).isEmpty();
    }

    @Test void successfulEmptyHistoryIsCachedWithoutChangingLocalEmptyFailureContract() {
        gateway.empty = true;
        var result = run("Nobody");
        assertThat(result.status()).isEqualTo("EMPTY");
        assertThat(service.submit("Nobody", "NA1", "peer").httpStatus()).isEqualTo(200);
        var local = new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock)
                .ingest(new RiotIngestionCommand("Nobody", "NA1", 5));
        assertThat(local.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(store.readPublicRun(local.runId())).isEmpty();
    }

    @Test void partialTimelineKeepsUsefulFinalStatsAndDoesNotCacheAsComplete() {
        gateway.timelineFailure = RiotFailureCode.NOT_FOUND;
        var result = run("Partial");
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().timelineAvailable()).isFalse();
        assertThat(store.findFresh(new RiotIngestionCommand("Partial", "NA1", 5), clock.instant().minusSeconds(900))).isEmpty();
    }

    @Test void expiredKeyFailsSafelyAndDoesNotExposeProviderMessage() {
        gateway.accountFailure = RiotFailureCode.AUTHENTICATION_FAILED;
        var result = run("Expired");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("unavailable").doesNotContain("private");
        assertThat(result.matches()).isEmpty();
    }

    @Test void cooldownSurvivesRestartStopsQueuedWorkAndExpiresOnlyAtFullProviderTime() {
        gateway.timelineFailure = RiotFailureCode.RATE_LIMITED;
        var first = service.submit("Limited", "NA1", "peer").lookup().runId();
        var queued = service.submit("Queued", "NA1", "peer").lookup().runId();
        work.remove().run();
        assertThat(service.get(first).retryNotBefore()).isEqualTo(clock.now.plusSeconds(120));
        assertThat(service.get(first).matches()).hasSize(1);
        gateway.calls.clear();
        work.remove().run();
        assertThat(gateway.calls).isEmpty();
        assertThat(service.get(queued).status()).isEqualTo("FAILED");
        assertThat(service.get(queued).retryNotBefore()).isEqualTo(clock.now.plusSeconds(120));
        service.interruptPreviousRuns();
        clock.now = clock.now.plusSeconds(119);
        assertThatThrownBy(() -> service.submit("Other", "NA1", "peer")).isInstanceOf(PublicLookupException.class);
        clock.now = clock.now.plusSeconds(1);
        assertThat(service.submit("Other", "NA1", "peer").httpStatus()).isEqualTo(202);
    }

    @Test void restartFailsOnlyInterruptedPublicWorkAndKeepsCompletedRows() {
        var complete = run("Ready");
        var interrupted = service.submit("Interrupted", "NA1", "peer").lookup().runId();
        var local = store.startRun(new RiotIngestionCommand("Local", "NA1", 5), clock.instant());
        service.interruptPreviousRuns();
        assertThat(service.get(interrupted).status()).isEqualTo("FAILED");
        assertThat(service.get(interrupted).message()).contains("interrupted");
        assertThat(service.get(complete.runId()).matches()).hasSize(1);
        assertThat(jdbc.queryForObject("select status from league_analysis.ingestion_run where id = ?", String.class, local)).isEqualTo("RUNNING");
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-09T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
