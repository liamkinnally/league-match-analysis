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
        drain();
        return service.get(id);
    }

    void drain() { while (!work.isEmpty()) work.remove().run(); }

    @Test void ingestsProviderMatchReturnsOnlyPublicSummaryCachesAndReusesCompleteStoredMatch() {
        var result = run("Invented");
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().matchId()).isEqualTo("NA1_7000000002");
        assertThat(result.matches().getFirst().participantId()).isEqualTo(6);
        assertThat(result.matches().getFirst().gameVersion()).isEqualTo("16.17.1");
        assertThat(result.matches().getFirst().endItemIds()).containsExactly(6631, 3047, 3071, 3051, 0, 0, 3340);
        assertThat(json.writeValueAsString(result)).doesNotContain("puuid", "capture", "lookup-invented-participant", "private");
        assertThat(store.findFresh(new RiotIngestionCommand("invented", "na1", 20, 0, 0, null, null), clock.instant().minusSeconds(900))).isPresent();
        assertThat(service.submit("Invented", "NA1", "peer").httpStatus()).isEqualTo(200);
        clock.now = clock.now.plusSeconds(901);
        gateway.calls.clear();
        assertThat(run("Invented").matches()).hasSize(1);
        assertThat(gateway.calls).isEmpty();
        var refreshed = service.refresh(result.runId(), "peer");
        drain();
        assertThat(service.get(refreshed.lookup().runId()).matches()).hasSize(1);
        assertThat(gateway.calls).containsExactly("account", "list");
    }

    @Test void completeStoredMatchWithConflictingMembershipCannotBecomeSuccessfulEmptyHistory() {
        var local = new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock)
                .ingest(new RiotIngestionCommand("Conflict", "NA1", 1));
        assertThat(local.status()).isEqualTo(IngestionRunStatus.COMPLETE);
        jdbc.update("""
                insert into league_analysis.riot_identity(puuid, first_observed_at, last_observed_at, last_source_capture_id)
                select 'old-member', first_observed_at, last_observed_at, last_source_capture_id
                from league_analysis.riot_identity where puuid = ?
                """, PublicLookupGatewayFixture.PUUID);
        jdbc.update("update league_analysis.riot_participant set puuid = 'old-member' where match_id = ? and participant_id = 6",
                PublicLookupGatewayFixture.MATCH_ID);
        var result = run("Conflict");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.matches()).isEmpty();
        assertThat(store.isCompleteMatch(PublicLookupGatewayFixture.MATCH_ID)).isTrue();
        assertThat(store.isCompleteMatch(PublicLookupGatewayFixture.MATCH_ID, PublicLookupGatewayFixture.PUUID)).isFalse();
    }

    @Test void completeRunWithNoEligibleStoredMatchesMapsToEmptyHistory() {
        var result = run("OtherQueue");
        jdbc.update("update league_analysis.riot_match set queue_id = 450 where match_id = ?", "NA1_7000000002");
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

    @Test void historyDefersTimelineUntilOpenedAndSharesTheUpgrade() {
        var result = run("Partial");
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.matches().getFirst().timelineAvailable()).isFalse();
        assertThat(gateway.calls).containsExactly("account", "list", "detail");
        var first = service.requestTimeline(result.matches().getFirst().matchId(), "peer");
        var shared = service.requestTimeline(result.matches().getFirst().matchId(), "peer");
        assertThat(shared.runId()).isEqualTo(first.runId());
        drain();
        assertThat(service.timeline(first.matchId()).status()).isEqualTo("AVAILABLE");
        assertThat(service.get(result.runId()).matches().getFirst().timelineAvailable()).isTrue();
        assertThat(gateway.calls).containsExactly("account", "list", "detail", "timeline");
    }

    @Test void unavailableTimelineKeepsFinalStatisticsAndDoesNotRepeatProviderRequest() {
        var result = run("UnavailableTimeline");
        String matchId = result.matches().getFirst().matchId();
        gateway.timelineFailure = RiotFailureCode.NOT_FOUND;
        service.requestTimeline(matchId, "peer"); drain();
        assertThat(service.timeline(matchId).status()).isEqualTo("UNAVAILABLE");
        var before = service.get(result.runId()).matches().getFirst();
        assertThat(before.timelineAvailable()).isFalse();
        assertThat(before.kills()).isGreaterThanOrEqualTo(0);
        gateway.calls.clear();
        service.requestTimeline(matchId, "peer"); drain();
        assertThat(gateway.calls).isEmpty();
        assertThat(service.get(result.runId()).matches().getFirst()).isEqualTo(before);
    }

    @Test void transientTimelineFailureCanBeRetriedWithoutRefetchingDetail() {
        var result = run("RetryTimeline");
        String matchId = result.matches().getFirst().matchId();
        gateway.timelineFailure = RiotFailureCode.UPSTREAM_UNAVAILABLE;
        service.requestTimeline(matchId, "peer"); drain();
        assertThat(service.timeline(matchId).status()).isEqualTo("FAILED");
        gateway.timelineFailure = null;
        gateway.calls.clear();
        service.requestTimeline(matchId, "peer"); drain();
        assertThat(service.timeline(matchId).status()).isEqualTo("AVAILABLE");
        assertThat(gateway.calls).containsExactly("timeline");
    }

    @Test void olderPagesHaveTwentyRowsAndReuseAStableSnapshotAcrossQueues() {
        gateway.totalMatches = 43;
        var first = service.submit("Pages", "NA1", 440, "peer");
        drain();
        var page = service.get(first.lookup().runId());
        assertThat(page.matches()).hasSize(20);
        assertThat(page.hasMore()).isTrue();
        clock.now = clock.now.plusSeconds(30);
        var older = service.older(page.runId(), "peer");
        drain();
        var second = service.get(older.lookup().runId());
        assertThat(second.matches()).hasSize(20);
        assertThat(second.previousRunId()).isEqualTo(page.runId());
        assertThat(second.matches()).extracting(PublicMatchLookup.MatchSummary::matchId)
                .doesNotContainAnyElementsOf(page.matches().stream().map(PublicMatchLookup.MatchSummary::matchId).toList());
        assertThat(gateway.pages.get(1)).isEqualTo(gateway.pages.getFirst().replace(":0:20:", ":20:20:"));
        int calls = gateway.calls.size();
        assertThat(service.older(page.runId(), "peer").lookup().runId()).isEqualTo(second.runId());
        assertThat(gateway.calls).hasSize(calls);
        var tail = service.older(second.runId(), "peer"); drain();
        assertThat(service.get(tail.lookup().runId()).matches()).hasSize(3);
        assertThat(service.get(tail.lookup().runId()).hasMore()).isFalse();
        assertThatThrownBy(() -> service.refresh(page.runId(), "peer")).isInstanceOf(PublicLookupException.class);
    }

    @Test void expiredKeyFailsSafelyAndDoesNotExposeProviderMessage() {
        gateway.accountFailure = RiotFailureCode.AUTHENTICATION_FAILED;
        var result = run("Expired");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("unavailable").doesNotContain("private");
        assertThat(result.matches()).isEmpty();
    }

    @Test void cooldownSurvivesRestartAndKeepsSummaryRows() {
        var complete = run("Ready");
        gateway.detailFailure = RiotFailureCode.RATE_LIMITED;
        gateway.totalMatches = 20;
        var limited = service.submit("Limited", "NA1", "peer").lookup().runId();
        // Account, list, first detail. Retry is scheduled, not executed synchronously.
        work.remove().run(); work.remove().run(); work.remove().run();
        assertThat(service.get(limited).retryNotBefore()).isEqualTo(clock.now.plusSeconds(120));
        assertThat(service.get(limited).status()).isEqualTo("RUNNING");
        service.close();
        service.interruptPreviousRuns();
        assertThat(service.get(limited).status()).isEqualTo("FAILED");
        assertThat(service.get(complete.runId()).matches()).hasSize(1);
        assertThat(service.submit("Ready", "NA1", "peer").httpStatus()).isEqualTo(200);
        assertThatThrownBy(() -> service.submit("Other", "NA1", "peer")).isInstanceOf(PublicLookupException.class);
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
