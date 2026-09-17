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
        return attachProfiles(summoner, ranks);
    }

    PlayerProfileService attachProfiles(SummonerProfileClient summoner, CurrentRankProvider ranks) {
        var profiles = new PlayerProfileService(profileStore, ranks, summoner, clock);
        service.profileService(profiles);
        return profiles;
    }

    PublicMatchLookup emptyProfile(String name, String tag, String platform, String puuid) {
        var command = new RiotIngestionCommand(name, tag, 20, 0, 0, clock.instant().getEpochSecond(), null, platform);
        var id = store.startPublicRun(command, clock.instant());
        var body = json.createObjectNode().put("gameName", name).put("tagLine", tag).put("puuid", puuid);
        var bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest;
        try { digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
        var doc = new dev.leagueanalysis.ingestion.riot.domain.ProviderDocument(
                dev.leagueanalysis.ingestion.riot.domain.SourceKind.ACCOUNT, name+"#"+tag, clock.instant(), 200,
                dev.leagueanalysis.ingestion.riot.domain.RiotPlatform.parse(platform).regionalRoute(), platform, null,
                digest, bytes.length, body, json.createObjectNode(), "fixture-v1", 1);
        var capture = store.saveCapture(id, doc);
        store.recordResolvedAccount(id, new dev.leagueanalysis.ingestion.riot.domain.RiotAccount(puuid,name,tag), capture);
        store.recordVerifiedRequestedIdentity(id, command);
        store.finishRun(id, IngestionRunStatus.COMPLETE, null, null, clock.instant());
        return store.readPublicRun(id).orElseThrow();
    }

    @Test void cachedZeroMatchProfilesAndRefreshAdmissionStaySeparateAcrossPlatforms() {
        var na = emptyProfile("SharedName", "tag", "NA1", "regional-na");
        var euw = emptyProfile("SharedName", "tag", "EUW1", "regional-euw");
        var eune = emptyProfile("SharedName", "tag", "EUN1", "regional-eune");
        var kr = emptyProfile("SharedName", "tag", "KR", "regional-kr");
        for (var page : List.of(na, euw, eune, kr)) {
            assertThat(page.status()).isEqualTo("EMPTY");
            assertThat(service.submit("sharedname", "TAG", 0, page.platform(), "peer").lookup().runId()).isEqualTo(page.runId());
            assertThat(store.readPageCommand(page.runId()).orElseThrow().platform()).isEqualTo(page.platform());
            assertThat(profileStore.subject(page.runId()).orElseThrow().platform()).isEqualTo(page.platform());
            assertThatThrownBy(() -> service.refresh(page.runId(), "peer")).isInstanceOf(PublicLookupException.class);
        }
        store.recordRetryNotBefore(euw.runId(), clock.instant().plusSeconds(120));
        assertThat(store.latestCooldown("EUW1")).contains(clock.instant().plusSeconds(120));
        assertThat(store.latestCooldown("EUN1")).contains(clock.instant().plusSeconds(120));
        assertThat(store.latestCooldown("NA1")).isEmpty();
        assertThat(store.latestCooldown("KR")).isEmpty();
        assertThat(gateway.calls).isEmpty();
    }

    @Test void autocompleteIsBoundedLiteralPrivateAndPlatformScopedIncludingEmptyAccounts() {
        for (int i=0; i<7; i++) emptyProfile("Alpha"+i, "tag", "NA1", "suggest-na-"+i);
        emptyProfile("AlphaEU", "tag", "EUW1", "suggest-euw");
        emptyProfile("Alpha_Exact", "tag", "NA1", "suggest-literal");
        emptyProfile("선수이름", "KR1", "KR", "suggest-kr");
        var hidden = emptyProfile("AlphaHidden", "tag", "NA1", "suggest-hidden");
        jdbc.update("update league_analysis.ingestion_run set status='RUNNING' where id=?",hidden.runId());
        jdbc.update("insert into league_analysis.player_profile_current(puuid,platform,profile_icon_id,summoner_level) values ('suggest-na-0','NA1',29,180)");
        assertThat(store.suggestions("NA1","a")).hasSize(5).extracting(s -> s.platform()).containsOnly("NA1");
        var exact = store.suggestions("NA1","ALPHA0#ta");
        assertThat(exact).hasSize(1);
        assertThat(exact.getFirst().profileIconId()).isEqualTo(29);
        assertThat(exact.getFirst().summonerLevel()).isEqualTo(180);
        assertThat(store.suggestions("EUW1","Alpha")).extracting(s -> s.gameName()).containsExactly("AlphaEU");
        assertThat(store.suggestions("NA1","Alpha_")).extracting(s -> s.gameName()).containsExactly("Alpha_Exact");
        assertThat(store.suggestions("NA1","%" )).isEmpty();
        assertThat(store.suggestions("KR","선수")).extracting(s -> s.gameName()).containsExactly("선수이름");
        assertThat(store.suggestions("NA1","AlphaHidden")).isEmpty();
        jdbc.update("insert into league_analysis.privacy_exclusion(kind,subject_hash) values ('puuid',?)",dev.leagueanalysis.privacy.PrivacyHash.of("suggest-na-0"));
        try { assertThat(store.suggestions("NA1","Alpha0")).isEmpty(); }
        finally { jdbc.update("delete from league_analysis.privacy_exclusion where subject_hash=?",dev.leagueanalysis.privacy.PrivacyHash.of("suggest-na-0")); }
        assertThat(gateway.calls).isEmpty();
    }

    @Test void autocompleteKeepsVerifiedLookupNameAfterHistoricalParticipantNamesAreImported() {
        var result=run("CurrentAlias");
        String historicalName=jdbc.queryForObject("select game_name from league_analysis.riot_identity where puuid=?",String.class,PublicLookupGatewayFixture.PUUID);
        assertThat(historicalName).isNotEqualTo("CurrentAlias");
        assertThat(store.suggestions("NA1","CurrentAlias")).extracting(s->s.gameName()).containsExactly("CurrentAlias");
        assertThat(store.suggestions("NA1",historicalName)).extracting(s->s.gameName()).doesNotContain(historicalName);
        String participantName=jdbc.queryForObject("select game_name from league_analysis.riot_identity where puuid='lookup-invented-participant-1'",String.class);
        assertThat(store.suggestions("NA1",participantName)).extracting(s->s.gameName()).contains(participantName);
        assertThat(result.status()).isEqualTo("COMPLETE");
    }

    @Test void autocompleteUsesNewestCanonicalAccountNameInsteadOfOldNamesOrRequestSpelling() {
        var original=emptyProfile("BeforeRename","tag","NA1","renamed-suggestion");
        clock.now=clock.now.plusSeconds(1);
        var renamed=emptyProfile("AfterRename","newtag","NA1","renamed-suggestion");
        jdbc.update("update league_analysis.ingestion_run set requested_game_name='REQUESTSPELLING',requested_tag_line='NEWTAG' where id=?",renamed.runId());
        assertThat(store.suggestions("NA1","AfterRename#new")).extracting(s->s.gameName()+"#"+s.tagLine()).containsExactly("AfterRename#newtag");
        assertThat(store.suggestions("NA1","BeforeRename")).isEmpty();
        assertThat(store.suggestions("NA1","REQUESTSPELLING")).isEmpty();
        assertThat(store.readPublicRun(original.runId())).isPresent();
    }

    @Test void regionalHistoryOlderPagesAndTimelineKeepTheirPlatform() {
        gateway.totalMatches=21;
        for(String platform:List.of("EUW1","EUN1","KR")) {
            var first=service.submit("Regional"+platform,"tag",0,platform,"peer"+platform);
            drain();
            var page=service.get(first.lookup().runId());
            assertThat(page.status()).isEqualTo("COMPLETE");
            assertThat(page.matches()).hasSize(20).allSatisfy(match->assertThat(match.matchId()).startsWith(platform+"_"));
            var older=service.older(page.runId(),"peer"+platform); drain();
            assertThat(service.get(older.lookup().runId()).matches()).hasSize(1);
            assertThat(service.get(older.lookup().runId()).platform()).isEqualTo(platform);
            String match=page.matches().getFirst().matchId();
            service.requestTimeline(match,"peer"+platform); drain();
            assertThat(service.timeline(match).status()).isEqualTo("AVAILABLE");
        }
    }

    @Test void pendingSameRiotIdInDifferentPlatformsNeverSharesTheRun() {
        var na = service.submit("Pending", "tag", 0, "NA1", "peer");
        var euw = service.submit("Pending", "tag", 0, "EUW1", "peer");
        assertThat(euw.lookup().runId()).isNotEqualTo(na.lookup().runId());
        assertThat(euw.lookup().platform()).isEqualTo("EUW1");
        assertThat(service.submit("Pending", "tag", 0, "EUW1", "peer").lookup().runId()).isEqualTo(euw.lookup().runId());
        work.clear();
    }

    @Test void firstLookupFetchesProfileAndRankWhileRecentHistoryIsStillLoading() {
        gateway.totalMatches = 20;
        var summoner = mock(SummonerProfileClient.class);
        when(summoner.fetch(anyString(), anyString())).thenReturn(new SummonerProfileClient.Profile(29, 180L, null));
        var ranks = mock(CurrentRankProvider.class);
        when(ranks.peek(anyString(), anyString(), anyString())).thenReturn(new CurrentRankProvider.State(null, null, false, false, null, null));
        var profiles = attachProfiles(summoner, ranks);
        var id = service.submit("CurrentAlias", "NA1", 440, "peer").lookup().runId();
        work.remove().run(); // Account resolution.
        work.remove().run(); // Platform verified; list/details are still queued.
        assertThat(profiles.load(id, null).summoner().refreshing()).isTrue();
        assertThat(profiles.load(id, null).soloRank().refreshing()).isTrue();
        verifyNoInteractions(summoner);
        work.remove().run(); // Summoner gets its turn before the match batch.
        var beforeRank = profiles.load(id, null);
        assertThat(beforeRank.identity().gameName()).isEqualTo("CurrentAlias");
        assertThat(beforeRank.summoner().status()).isEqualTo("available");
        assertThat(beforeRank.summoner().profileIconId()).isEqualTo(29);
        assertThat(beforeRank.summoner().summonerLevel()).isEqualTo(180L);
        assertThat(beforeRank.soloRank().refreshing()).isTrue();
        assertThat(service.get(id).status()).isEqualTo("RUNNING");
        work.remove().run(); // History also advances between profile stages.
        assertThat(gateway.calls).containsExactly("account", "list");
        work.remove().run(); // Rank is admitted before match details finish.
        verify(ranks).refresh("NA1", PublicLookupGatewayFixture.PUUID, "RANKED_SOLO_5x5");
        assertThat(service.get(id).status()).isEqualTo("RUNNING");
        assertThat(service.submit("CurrentAlias", "NA1", 440, "other-peer").lookup().runId()).isEqualTo(id);
        drain();
        assertThat(service.get(id).matches()).hasSize(20);
        assertThat(service.get(id).status()).isEqualTo("COMPLETE");
        assertThat(work).isEmpty();
        assertThat(profiles.load(id, null).summoner().refreshing()).isFalse();
        assertThat(profiles.load(id, null).soloRank().refreshing()).isFalse();
        assertThat(service.submit("CurrentAlias", "NA1", 440, "peer").httpStatus()).isEqualTo(200);
        verify(summoner, times(1)).fetch("NA1", PublicLookupGatewayFixture.PUUID);
        verify(ranks, times(1)).refresh(anyString(), anyString(), anyString());
        assertThat(work).isEmpty();
    }

    @Test void profileRateLimitRetriesTheSameStageAndFinishesHistoryAfterCooldown() {
        var delayed = new ArrayList<Runnable>();
        service = new PublicMatchLookupService(new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock),
                store, clock, true, work::add, (task, delay) -> delayed.add(task));
        var summoner = mock(SummonerProfileClient.class);
        when(summoner.fetch(anyString(), anyString())).thenThrow(new RiotGatewayException(
                RiotFailureCode.RATE_LIMITED, "sanitized", clock.instant().plusSeconds(120)))
                .thenReturn(new SummonerProfileClient.Profile(29, 180L, null));
        var profiles = attachProfiles(summoner);
        var id = service.submit("CurrentAlias", "NA1", 440, "peer").lookup().runId();
        work.remove().run();
        work.remove().run();
        work.remove().run();
        assertThat(delayed).hasSize(1);
        assertThat(work).isEmpty();
        assertThat(service.get(id).status()).isEqualTo("RUNNING");
        clock.now = clock.now.plusSeconds(120);
        delayed.removeFirst().run();
        drain();
        assertThat(service.get(id).status()).isEqualTo("COMPLETE");
        assertThat(profiles.load(id, null).summoner().status()).isEqualTo("available");
        verify(summoner, times(2)).fetch("NA1", PublicLookupGatewayFixture.PUUID);
    }

    @Test void profileCooldownPastDeadlineStopsUnfinishedLookupAndClearsPendingState() {
        var delayed = new ArrayList<Runnable>();
        service = new PublicMatchLookupService(new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock),
                store, clock, true, work::add, (task, delay) -> delayed.add(task));
        var summoner = mock(SummonerProfileClient.class);
        when(summoner.fetch(anyString(), anyString())).thenThrow(new RiotGatewayException(
                RiotFailureCode.RATE_LIMITED, "sanitized", clock.instant().plusSeconds(1000)));
        var profiles = attachProfiles(summoner);
        var id = service.submit("CurrentAlias", "NA1", 440, "peer").lookup().runId();
        work.remove().run();
        work.remove().run();
        work.remove().run();
        assertThat(service.get(id).status()).isEqualTo("RUNNING");
        assertThat(delayed).hasSize(1);
        clock.now = clock.now.plusSeconds(901);
        delayed.removeFirst().run();
        assertThat(service.get(id).status()).isEqualTo("FAILED");
        assertThat(profiles.load(id, null).summoner().refreshing()).isFalse();
        assertThat(profiles.load(id, null).soloRank().refreshing()).isFalse();
        assertThat(work).isEmpty();
        assertThat(delayed).isEmpty();
    }

    @Test void unavailableProfileDoesNotPreventRecentHistoryFromFinishing() {
        var summoner = mock(SummonerProfileClient.class);
        when(summoner.fetch(anyString(), anyString())).thenThrow(new RiotGatewayException(
                RiotFailureCode.UPSTREAM_UNAVAILABLE, "sanitized"));
        var profiles = attachProfiles(summoner);
        var id = service.submit("CurrentAlias", "NA1", "peer").lookup().runId();
        for (int i = 0; i < 5; i++) work.remove().run(); // Account, platform verification, failed Summoner, list, rank.
        assertThat(service.get(id).status()).isEqualTo("RUNNING");
        var resolvedProfile = profiles.load(id, null);
        assertThat(resolvedProfile.summoner().status()).isEqualTo("unavailable");
        assertThat(resolvedProfile.summoner().refreshing()).isFalse();
        assertThat(resolvedProfile.soloRank().status()).isEqualTo("unavailable");
        assertThat(resolvedProfile.soloRank().refreshing()).isFalse();
        drain();
        var result = service.get(id);
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.matches()).hasSize(1);
        assertThat(profiles.load(result.runId(), null).summoner().status()).isEqualTo("unavailable");
        assertThat(profiles.load(result.runId(), null).summoner().refreshing()).isFalse();
        assertThat(profiles.load(result.runId(), null).summoner().retryNotBefore()).isAfter(clock.instant());
    }

    @Test void failedAccountVerificationNeverStartsProfileRequests() {
        gateway.accountFailure = RiotFailureCode.NOT_FOUND;
        var summoner = mock(SummonerProfileClient.class);
        var ranks = mock(CurrentRankProvider.class);
        attachProfiles(summoner, ranks);
        var result = run("CurrentAlias");
        assertThat(result.status()).isEqualTo("FAILED");
        verifyNoInteractions(summoner, ranks);
    }

    @Test void publicProfileIdentityIsNotReplacedByHistoricalParticipantName() {
        var profiles = attachProfiles(mock(SummonerProfileClient.class));
        var id = service.submit("CurrentAlias", "NA1", 440, "peer").lookup().runId();
        drain();
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
        for(int i=0;i<6;i++)work.remove().run();
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
        // Account, platform verification, list, first detail. Retry is scheduled, not executed synchronously.
        work.remove().run(); work.remove().run(); work.remove().run(); work.remove().run();
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
