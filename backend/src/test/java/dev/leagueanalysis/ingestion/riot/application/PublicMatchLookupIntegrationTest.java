package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.SummonerProfileClient;
import dev.leagueanalysis.analysis.profile.JdbcPlayerProfileStore;
import dev.leagueanalysis.analysis.profile.PlayerProfileService;
import dev.leagueanalysis.analysis.rank.CurrentRankProvider;
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
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PublicMatchLookupIntegrationTest {
    @Autowired JdbcRiotIngestionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JdbcPlayerProfileStore profileStore;
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

    PlayerProfileService attachProfiles(SummonerProfileClient summoner) {
        var ranks = mock(CurrentRankProvider.class);
        var unavailable = new CurrentRankProvider.State(null, null, false, false, null, null);
        when(ranks.peek(anyString(), anyString(), anyString())).thenReturn(unavailable);
        when(ranks.refresh(anyString(), anyString(), anyString())).thenReturn(unavailable);
        var profiles = new PlayerProfileService(profileStore, ranks, summoner, clock);
        service.profileService(profiles);
        return profiles;
    }

    @Test void profileRemainsPendingAcrossQueuedStagesAndUsesVerifiedLookupAlias() {
        var summoner = mock(SummonerProfileClient.class);
        when(summoner.fetch(anyString(), anyString())).thenReturn(new SummonerProfileClient.Profile(29, 180L, null));
        var profiles = attachProfiles(summoner);
        var id = service.submit("CurrentAlias", "NA1", 440, "peer").lookup().runId();
        work.remove().run(); // Account verified; list/details are still queued.
        assertThat(profiles.load(id, null).summoner().refreshing()).isTrue();
        assertThat(profiles.load(id, null).soloRank().refreshing()).isTrue();
        for (int i = 0; i < 3; i++) work.remove().run(); // List, detail, history completion.
        assertThat(service.get(id).status()).isEqualTo("COMPLETE");
        var beforeSummoner = profiles.load(id, null);
        assertThat(beforeSummoner.identity().gameName()).isEqualTo("CurrentAlias");
        assertThat(beforeSummoner.summoner().refreshing()).isTrue();
        assertThat(beforeSummoner.soloRank().refreshing()).isTrue();
        work.remove().run(); // Summoner succeeded; rank stage still waits for its turn.
        var beforeRank = profiles.load(id, null);
        assertThat(beforeRank.summoner().status()).isEqualTo("available");
        assertThat(beforeRank.soloRank().refreshing()).isTrue();
        work.remove().run();
        assertThat(work).isEmpty();
        assertThat(profiles.load(id, null).summoner().refreshing()).isFalse();
        assertThat(profiles.load(id, null).soloRank().refreshing()).isFalse();
    }

    @Test void completedHistorySurvivesProfileCooldownPastItsDeadline() {
        var delayed = new ArrayList<Runnable>();
        service = new PublicMatchLookupService(new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock),
                store, clock, true, work::add, (task, delay) -> delayed.add(task));
        var summoner = mock(SummonerProfileClient.class);
        when(summoner.fetch(anyString(), anyString())).thenThrow(new RiotGatewayException(
                RiotFailureCode.RATE_LIMITED, "sanitized", clock.instant().plusSeconds(1000)));
        var profiles = attachProfiles(summoner);
        var id = service.submit("CurrentAlias", "NA1", 440, "peer").lookup().runId();
        for (int i = 0; i < 4; i++) work.remove().run();
        assertThat(service.get(id).status()).isEqualTo("COMPLETE");
        work.remove().run();
        assertThat(delayed).hasSize(1);
        clock.now = clock.now.plusSeconds(901);
        delayed.removeFirst().run();
        assertThat(service.get(id).status()).isEqualTo("COMPLETE");
        assertThat(service.submit("CurrentAlias", "NA1", 440, "peer").httpStatus()).isEqualTo(200);
        assertThat(profiles.load(id, null).summoner().refreshing()).isFalse();
        assertThat(profiles.load(id, null).soloRank().refreshing()).isFalse();
    }

    @Test void publicProfileIdentityIsNotReplacedByHistoricalParticipantName() {
        var profiles = attachProfiles(mock(SummonerProfileClient.class));
        var id = service.submit("CurrentAlias", "NA1", 440, "peer").lookup().runId();
        for (int i = 0; i < 4; i++) work.remove().run();
        assertThat(jdbc.queryForObject("select game_name from league_analysis.riot_identity where puuid=?",
                String.class, PublicLookupGatewayFixture.PUUID)).isNotEqualTo("CurrentAlias");
        assertThat(profiles.load(id, null).identity().gameName()).isEqualTo("CurrentAlias");
        assertThat(profiles.load(id, null).identity().tagLine()).isEqualTo("NA1");
    }

    @Test void explicitRecentRequestsInterleaveTwoUsersAndNeverExceedTwentyDetailCallsEach() {
        gateway.rawQueues=Collections.nCopies(60,420);
        var first=service.submit("History101","NA1",440,"first-peer");
        var second=service.submit("History202","NA1",440,"second-peer");drain();
        clock.now=clock.now.plusSeconds(901);gateway.calls.clear();gateway.pages.clear();
        var a=service.recentRecord(first.lookup().runId(),"first-peer");
        var b=service.recentRecord(second.lookup().runId(),"second-peer");
        assertThat(service.recentRecord(first.lookup().runId(),"first-peer").lookup().runId()).isEqualTo(a.lookup().runId());
        for(int i=0;i<4;i++)work.remove().run();
        assertThat(gateway.calls).containsExactly("account","account","list","list");
        work.remove().run();work.remove().run();
        assertThat(service.get(a.lookup().runId()).matches()).hasSize(1);
        assertThat(service.get(b.lookup().runId()).matches()).hasSize(1);
        drain();
        assertThat(service.get(a.lookup().runId()).matches()).hasSize(20);
        assertThat(service.get(b.lookup().runId()).matches()).hasSize(20);
        assertThat(gateway.calls.stream().filter("detail"::equals).count()).isEqualTo(40);
        assertThat(gateway.calls.stream().filter("list"::equals).count()).isEqualTo(2);
        assertThat(gateway.calls).doesNotContain("timeline");
        assertThat(gateway.pages).containsExactly("420:0:20:"+clock.instant().getEpochSecond(),"420:0:20:"+clock.instant().getEpochSecond());
        int calls=gateway.calls.size();
        for(int i=0;i<20;i++){service.get(a.lookup().runId());service.get(b.lookup().runId());}
        assertThat(gateway.calls).hasSize(calls);
        assertThatThrownBy(()->service.recentRecord(first.lookup().runId(),"first-peer")).isInstanceOfSatisfying(PublicLookupException.class,e->assertThat(e.status()).isEqualTo(429));
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
