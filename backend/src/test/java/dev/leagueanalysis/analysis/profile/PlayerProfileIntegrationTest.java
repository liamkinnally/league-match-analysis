package dev.leagueanalysis.analysis.profile;

import static org.assertj.core.api.Assertions.*;
import dev.leagueanalysis.analysis.rank.RankSnapshotStore;
import dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.application.*;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;
import dev.leagueanalysis.support.*;
import dev.leagueanalysis.privacy.RemovalPlanner;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PlayerProfileIntegrationTest {
    static final Instant NOW=Instant.parse("2026-09-14T12:00:00Z");
    static final String PUUID=PublicLookupGatewayFixture.PUUID;
    @Autowired JdbcPlayerProfileStore profiles;
    @Autowired PlayerProfileService service;
    @Autowired JdbcRiotIngestionStore ingestion;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    UUID run;
    UUID refresh=UUID.randomUUID();
    @BeforeEach void setup(){
        clear();var fixture=new PublicLookupGatewayFixture(json,Clock.fixed(NOW,ZoneOffset.UTC));
        var command=new RiotIngestionCommand("Invented","NA1",20,420,0,NOW.getEpochSecond(),null);
        run=ingestion.startPublicRun(command,NOW);
        var account=fixture.resolveAccount(new RiotId(command.gameName(),command.tagLine()));
        ingestion.recordResolvedAccount(run,account.account(),ingestion.saveCapture(run,account.source()));
        ingestion.recordVerifiedRequestedIdentity(run,command);
    }
    @AfterEach void clear(){jdbc.execute("truncate table league_analysis.ingestion_run,league_analysis.source_payload cascade");jdbc.execute("delete from league_analysis.privacy_exclusion");}
    Map<String,RankSnapshotStore.Value> gold(){return Map.of("RANKED_SOLO_5x5",new RankSnapshotStore.Value("ranked","GOLD","I",80,12,8));}
    @Test void persistsOnlySuccessfulSampledObservationsAndRetainsLastGoodValuesOnFailure(){
        assertThat(profiles.claim("NA1",PUUID,NOW,refresh)).isTrue();
        assertThat(profiles.claim("NA1",PUUID,NOW,refresh)).isFalse();
        profiles.success("NA1",PUUID,gold(),NOW,refresh);profiles.success("NA1",PUUID,gold(),NOW,refresh);
        var subject=profiles.subject(run).orElseThrow();
        assertThat(profiles.history(subject,null).observations()).hasSize(1);
        assertThat(profiles.claim("NA1",PUUID,NOW.plusSeconds(301),refresh=UUID.randomUUID())).isTrue();
        profiles.success("NA1",PUUID,gold(),NOW.plusSeconds(301),refresh);
        assertThat(profiles.history(subject,null).observations()).hasSize(1);
        profiles.failure("NA1",PUUID,"RATE_LIMITED",NOW.plusSeconds(800),refresh);
        assertThat(profiles.read("NA1",PUUID).orElseThrow().values().get("RANKED_SOLO_5x5").wins()).isEqualTo(12);
        assertThat(profiles.claim("NA1",PUUID,NOW.plusSeconds(700),UUID.randomUUID())).isFalse();
        assertThat(profiles.claim("NA1",PUUID,NOW.plusSeconds(901),refresh=UUID.randomUUID())).isTrue();
        profiles.success("NA1",PUUID,Map.of(),NOW.plusSeconds(901),refresh);
        var history=profiles.history(subject,null);
        assertThat(history.observations()).hasSize(2);
        assertThat(history.observations().getFirst().status()).isEqualTo("unranked");
        assertThat(history.observations().getFirst().period()).isNull();
        assertThat(history.trackingSince()).isEqualTo(NOW);
        assertThat(json.writeValueAsString(history)).doesNotContain(PUUID,"refreshId","source_capture");
    }
    @Test void publicProjectionSeparatesCurrentRankRecordFromUnverifiedRecentAndZeroGames() {
        assertThat(profiles.claim("NA1",PUUID,NOW,refresh)).isTrue();profiles.success("NA1",PUUID,gold(),NOW,refresh);
        var profile=service.load(run,null);
        assertThat(profile.soloRank().wins()).isEqualTo(12);assertThat(profile.soloRank().losses()).isEqualTo(8);assertThat(profile.soloRank().winRate()).isEqualTo(60.0);assertThat(profile.soloRank().period()).isEqualTo("unknown");
        assertThat(profile.flexRank().status()).isEqualTo("unranked");assertThat(profile.flexRank().wins()).isNull();assertThat(profile.flexRank().losses()).isNull();assertThat(profile.flexRank().winRate()).isNull();assertThat(profile.flexRank().period()).isEqualTo("unknown");
        assertThat(profile.flexRank().fetchedAt()).isEqualTo(profile.soloRank().fetchedAt());
        assertThat(json.writeValueAsString(profile)).doesNotContain(PUUID,"refreshId","puuid","source_capture");
        refresh=UUID.randomUUID();assertThat(profiles.claim("NA1",PUUID,NOW.plusSeconds(301),refresh)).isTrue();
        profiles.success("NA1",PUUID,Map.of("RANKED_SOLO_5x5",new RankSnapshotStore.Value("ranked","GOLD","I",0,0,0)),NOW.plusSeconds(301),refresh);
        assertThat(service.load(run,null).soloRank().winRate()).isNull();
        assertThat(service.load(run,null).soloRank().wins()).isZero();
    }
    @Test void expiredWorkerCannotOverwriteNewerClaimOrAppendAnObservation() {
        UUID old=UUID.randomUUID(),fresh=UUID.randomUUID();
        assertThat(profiles.claim("NA1",PUUID,NOW,old)).isTrue();
        assertThat(profiles.claim("NA1",PUUID,NOW.plusSeconds(121),fresh)).isTrue();
        profiles.success("NA1",PUUID,gold(),NOW.plusSeconds(122),fresh);
        profiles.success("NA1",PUUID,Map.of(),NOW.plusSeconds(123),old);
        profiles.failure("NA1",PUUID,"RATE_LIMITED",NOW.plusSeconds(1000),old);
        var result=profiles.read("NA1",PUUID).orElseThrow();
        assertThat(result.values().get("RANKED_SOLO_5x5").tier()).isEqualTo("GOLD");assertThat(result.retryAt()).isNull();
        assertThat(profiles.history(profiles.subject(run).orElseThrow(),null).observations()).hasSize(1);
    }
    @Test void persistedPendingMarkerFencesOlderCompletionAndExpiresAtItsBoundedDeadline() {
        var subject=profiles.subject(run).orElseThrow();
        ingestion.finishRun(run,IngestionRunStatus.COMPLETE,null,null,NOW);
        profiles.markProfilePending(subject,run,NOW.plusSeconds(900));
        var newer=ingestion.startPublicRun(new RiotIngestionCommand("Invented","NA1",20,440,0,NOW.getEpochSecond(),null),NOW.plusSeconds(1));
        profiles.markProfilePending(subject,newer,NOW.plusSeconds(901));
        profiles.finishProfileWork(run);
        profiles.markProfilePending(subject,run,NOW.plusSeconds(900));
        assertThat(jdbc.queryForObject("select profile_pending_run_id from league_analysis.player_profile_current where puuid=?",UUID.class,PUUID)).isEqualTo(newer);
        assertThat(profiles.profilePending(subject,run,NOW.plusSeconds(2))).isTrue();
        assertThat(profiles.profilePending(subject,run,NOW.plusSeconds(901))).isFalse();
        profiles.finishProfileWork(newer);
        assertThat(profiles.profilePending(subject,run,NOW.plusSeconds(2))).isFalse();
    }
    @Test void boundedCursorAndRemovalPreviewIncludeEveryPersonalTableAndDetectNewObservationDrift(){
        profiles.claimSummoner("NA1",PUUID,NOW,refresh);var subject=profiles.subject(run).orElseThrow();
        profiles.summonerSuccess(subject,29,180L,NOW.minusSeconds(20),NOW,refresh);
        for(int i=0;i<52;i++){var at=NOW.plusSeconds(i*901L);assertThat(profiles.claim("NA1",PUUID,at,refresh=UUID.randomUUID())).isTrue();profiles.success("NA1",PUUID,gold(),at,refresh);}
        var page=profiles.history(subject,null);assertThat(page.observations()).hasSize(50);assertThat(page.nextCursor()).isNotNull();
        var older=profiles.history(subject,page.nextCursor());assertThat(older.observations()).hasSize(2);assertThat(older.nextCursor()).isNull();
        assertThat(older.observations()).doesNotContainAnyElementsOf(page.observations());
        var planner=new RemovalPlanner(jdbc,json);var before=planner.plan(PUUID);
        assertThat(before.affectedRecords()).containsEntry("player_profile_current",1L).containsEntry("rank_refresh_state",1L).containsEntry("rank_observation",104L);
        profiles.claim("NA1",PUUID,NOW.plusSeconds(52*901L),refresh=UUID.randomUUID());profiles.success("NA1",PUUID,gold(),NOW.plusSeconds(52*901L),refresh);
        assertThat(planner.plan(PUUID).fingerprint()).isNotEqualTo(before.fingerprint());
        var transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(()->transactions.executeWithoutResult(status->{jdbc.execute("set local league_analysis.removal_operator='on'");planner.apply(before);})).hasMessageContaining("REMOVAL_PLAN_CHANGED");
        transactions.executeWithoutResult(status->{jdbc.execute("set local league_analysis.removal_operator='on'");planner.apply(planner.plan(PUUID));});
        assertThat(profiles.subject(run)).isEmpty();
        for(String table:List.of("player_profile_current","rank_refresh_state","rank_observation"))assertThat(jdbc.queryForObject("select count(*) from league_analysis."+table,Long.class)).isZero();
    }
    @Test void recentRequestBudgetSurvivesFailureAndDoesNotDependOnHistoryPageFilter(){
        var subject=profiles.subject(run).orElseThrow();
        profiles.claimRecent(subject,NOW);
        ingestion.finishRun(run,IngestionRunStatus.FAILED,"TEST","Unavailable",NOW);
        assertThatThrownBy(()->profiles.claimRecent(subject,NOW.plusSeconds(899))).isInstanceOfSatisfying(PublicLookupException.class,e->assertThat(e.retryNotBefore()).isEqualTo(NOW.plusSeconds(900)));
        assertThat(profiles.recent(subject,run,NOW.plusSeconds(899)).canLoad()).isFalse();
        profiles.claimRecent(subject,NOW.plusSeconds(900));
        assertThat(profiles.recent(subject,run,NOW.plusSeconds(900)).retryNotBefore()).isEqualTo(NOW.plusSeconds(1800));
    }
    @Test void exclusionsGateReadsClaimsAndDirectWritesAndRecentCoverageRemainsUnverified(){
        var subject=profiles.subject(run).orElseThrow();
        var recent=profiles.recent(subject,run,NOW);assertThat(recent.completeness()).isEqualTo("loading");assertThat(recent.canLoad()).isFalse();assertThat(recent.retryNotBefore()).isEqualTo(NOW.plusSeconds(900));
        ingestion.finishRun(run,IngestionRunStatus.COMPLETE,null,null,NOW);
        assertThat(profiles.recent(subject,run,NOW).completeness()).isEqualTo("unverified");
        jdbc.update("insert into league_analysis.privacy_exclusion(kind,subject_hash) values ('puuid',league_analysis.privacy_hash(?))",PUUID);
        assertThat(profiles.subject(run)).isEmpty();assertThat(profiles.claim("NA1",PUUID,NOW,refresh)).isFalse();
        assertThatThrownBy(()->service.load(run,null)).isInstanceOf(PublicLookupException.class);
        assertThatThrownBy(()->jdbc.update("insert into league_analysis.player_profile_current(puuid,platform) values (?,'NA1')",PUUID)).hasMessageContaining("PRIVACY_EXCLUDED");
    }
}
