package dev.leagueanalysis.analysis.leaderboard;

import static org.assertj.core.api.Assertions.*;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import dev.leagueanalysis.privacy.RemovalPlanner;
import dev.leagueanalysis.privacy.PrivacyHash;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LeaderboardIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired LeaderboardStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    static final Instant NOW=Instant.parse("2026-11-06T12:00:00Z");
    @BeforeEach void reset(){
        jdbc.update("update league_analysis.leaderboard_cutoff set fetched_at=null,failed=false,cycle_at=null,challenger_histogram=null,grandmaster_histogram=null,challenger_boundary=null,challenger_promotion=null,grandmaster_boundary=null,grandmaster_promotion=null");
        jdbc.execute("delete from league_analysis.leaderboard_entry");jdbc.execute("delete from league_analysis.leaderboard_member");
        jdbc.execute("delete from league_analysis.privacy_exclusion");
        jdbc.update("update league_analysis.leaderboard_state set initial_fill_started_at=null,initial_fill_deadline=null,initial_fill_completed_at=?,na_request_due_at=?,americas_request_due_at=?",java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(Instant.EPOCH),java.sql.Timestamp.from(Instant.EPOCH));
        jdbc.update("update league_analysis.leaderboard_state set claimed_work_tier=null,initial_fill_ready=false,fetched_at=null,ladder_due_at=?,request_due_at=?,lease_id=null,lease_until=null,next_tier=0,failed=false",java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW));
    }
    @AfterEach void clean(){reset();}
    List<LeaderboardClient.Standing> hundred(){return java.util.stream.IntStream.range(0,100).mapToObj(i->new LeaderboardClient.Standing("leaderboard-only-"+i,"CHALLENGER",1000-i,100,50)).toList();}
    LeaderboardStore.Work claim(Instant at){return store.claim(at,Duration.ofSeconds(120)).orElseThrow();}
    /** Enrichment scenarios start from an already completed bulk cycle. */
    void publishForEnrichment(LeaderboardStore.Work work,List<LeaderboardClient.Standing> rows,Instant at) {
        store.ladder(work,rows,at);
        jdbc.update("update league_analysis.leaderboard_state set next_tier=0,ladder_due_at=?",java.sql.Timestamp.from(at.plusSeconds(900)));
        jdbc.update("update league_analysis.leaderboard_cutoff set cycle_at=null,challenger_histogram=null,grandmaster_histogram=null");
    }
    @Test void fullChallengerPublishesBeforeOptionalTiersAndGlobalCutoffsIncludeExcludedPlayers() {
        var challenger=java.util.stream.IntStream.range(0,300).mapToObj(i->new LeaderboardClient.Standing("challenger-"+i,"CHALLENGER",2000-i,100,50)).toList();
        jdbc.update("insert into league_analysis.privacy_exclusion values ('puuid',?)",PrivacyHash.of("challenger-298"));
        store.ladder(claim(NOW),challenger,NOW);
        assertThat(store.read(NOW,true).entries()).hasSize(299);
        assertThat(json.valueToTree(store.read(NOW,true)).path("cutoffs").path("status").asText()).isEqualTo("pending");
        var gm=java.util.stream.IntStream.range(0,700).mapToObj(i->new LeaderboardClient.Standing("gm-"+i,"GRANDMASTER",3000-i,100,50)).toList();
        var restarted=new LeaderboardStore(jdbc,manager,context.getBean(dev.leagueanalysis.privacy.PrivacyRuntimeGuard.class));
        restarted.ladder(claim(NOW.plusSeconds(15)),gm,NOW.plusSeconds(15));
        assertThat(json.valueToTree(store.read(NOW,true)).path("cutoffs").path("status").asText()).isEqualTo("pending");
        store.ladder(claim(NOW.plusSeconds(30)),List.of(new LeaderboardClient.Standing("master-high","MASTER",5000,100,50)),NOW.plusSeconds(30));
        var cutoffs=json.valueToTree(store.read(NOW,true)).path("cutoffs");
        assertThat(cutoffs.path("status").asText()).isEqualTo("ready");
        assertThat(cutoffs.path("fetchedAt").asText()).isEqualTo(NOW.toString());
        assertThat(cutoffs.path("challenger").path("boundaryLp").intValue()).isEqualTo(2702);
        assertThat(cutoffs.path("challenger").path("promotionLp").intValue()).isEqualTo(2703);
        assertThat(cutoffs.path("grandmaster").path("boundaryLp").intValue()).isEqualTo(1702);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.leaderboard_member",Integer.class)).isEqualTo(299);
        assertThat(store.read(NOW,true).entries()).allMatch(e->e.tier().equals("CHALLENGER"));
        // Optional failure in the next cycle retains both the new Challenger publication and old estimates.
        store.ladder(claim(NOW.plusSeconds(900)),challenger,NOW.plusSeconds(900));
        var optional=claim(NOW.plusSeconds(915));assertThat(optional.tier()).isEqualTo(1);
        store.failure(optional,NOW.plusSeconds(975),false,NOW.plusSeconds(915));
        var retained=store.read(NOW.plusSeconds(916),true);
        assertThat(retained.fetchedAt()).isEqualTo(NOW.plusSeconds(900));assertThat(retained.stale()).isFalse();
        assertThat(retained.cutoffs().fetchedAt()).isEqualTo(NOW);assertThat(retained.cutoffs().stale()).isTrue();
        assertThat(retained.nextRefreshAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(claim(NOW.plusSeconds(930)).tier()).isEqualTo(-1);

    }
    @Test void expansionWaitsForExpandedPublicationAndLegacyLowerTiersNeverLeakIntoChallenger() {
        publishForEnrichment(claim(NOW),hundred(),NOW);
        jdbc.update("update league_analysis.leaderboard_state set initial_fill_ready=false,initial_fill_completed_at=null");
        jdbc.update("update league_analysis.leaderboard_member set game_name='Cached',tag_line='NA1',profile_icon_id=29,name_due_at=?,icon_due_at=?",java.sql.Timestamp.from(NOW.plusSeconds(86400)),java.sql.Timestamp.from(NOW.plusSeconds(86400)));
        jdbc.update("insert into league_analysis.leaderboard_entry values(false,'old-gm','GRANDMASTER',999,10,10)");
        assertThat(store.read(NOW,false).entries()).hasSize(100).allMatch(e->e.tier().equals("CHALLENGER"));
        assertThat(store.claim(NOW.plusSeconds(15),Duration.ofSeconds(120))).isEmpty();
        assertThat(jdbc.queryForObject("select initial_fill_started_at from league_analysis.leaderboard_state",java.sql.Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("select initial_fill_completed_at from league_analysis.leaderboard_state",java.sql.Timestamp.class)).isNull();
        var expanded=new ArrayList<>(hundred());expanded.add(new LeaderboardClient.Standing("new-member","CHALLENGER",900,9,1));
        store.ladder(claim(NOW.plusSeconds(900)),expanded,NOW.plusSeconds(900));
        var gm=claim(NOW.plusSeconds(915));
        assertThat(jdbc.queryForObject("select initial_fill_deadline from league_analysis.leaderboard_state",java.sql.Timestamp.class).toInstant()).isEqualTo(NOW.plusSeconds(1815));
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.leaderboard_entry where tier<>'CHALLENGER'",Integer.class)).isZero();
        store.failure(gm,NOW.plusSeconds(975),false,NOW.plusSeconds(915));
    }
    @Test void persistedSlotsFenceExpiredBulkWorkWithoutRepeatingItsCycleAndRetainSnapshotsOnFailure(){
        var first=claim(NOW);
        assertThat(store.claim(NOW.plusSeconds(15),Duration.ofSeconds(120))).isEmpty();
        assertThat(store.claim(NOW.plusSeconds(121),Duration.ofSeconds(120))).isEmpty();
        store.ladder(first,hundred(),NOW.plusSeconds(122));
        assertThat(store.read(NOW,true).entries()).isEmpty();
        assertThat(store.read(NOW.plusSeconds(122),true).nextRefreshAt()).isEqualTo(NOW.plusSeconds(900));
        var restarted=claim(NOW.plusSeconds(900));
        publishForEnrichment(restarted,hundred(),NOW.plusSeconds(901));
        assertThat(store.read(NOW.plusSeconds(902),true).entries()).hasSize(100);
        assertThat(store.claim(NOW.plusSeconds(910),Duration.ofSeconds(120))).isEmpty();
        var enrich=claim(NOW.plusSeconds(916));
        store.failure(enrich,NOW.plusSeconds(1217),true,NOW.plusSeconds(917));
        assertThat(store.claim(NOW.plusSeconds(1216),Duration.ofSeconds(120))).isEmpty();
        assertThat(store.read(NOW.plusSeconds(1217),false).entries()).hasSize(100);
        assertThat(store.read(NOW,false).nextRefreshAt()).isNull();
        var ladder=claim(NOW.plusSeconds(1802));
        store.failure(ladder,NOW.plusSeconds(1900),false,NOW.plusSeconds(1803));
        var stale=store.read(NOW.plusSeconds(1804),true);
        assertThat(stale.entries()).hasSize(100);assertThat(stale.stale()).isTrue();assertThat(stale.fetchedAt()).isEqualTo(NOW.plusSeconds(901));
    }
    @Test void initialFillUsesIndependentPersistedHostFloorsAndHonorsGlobalRetry() {
        jdbc.update("update league_analysis.leaderboard_state set initial_fill_completed_at=null");
        publishForEnrichment(claim(NOW),hundred(),NOW);
        var name=claim(NOW.plusSeconds(15));assertThat(name.icon()).isFalse();
        store.member(name,new LeaderboardClient.Name("First","NA1"),null,NOW.plusSeconds(15));
        var restarted=new LeaderboardStore(jdbc,manager,context.getBean(dev.leagueanalysis.privacy.PrivacyRuntimeGuard.class));
        var eligible=restarted.claim(NOW.plusSeconds(16),Duration.ofSeconds(120));
        assertThat(eligible).isPresent();
        var icon=eligible.orElseThrow();
        assertThat(icon.icon()).isTrue();assertThat(icon.puuid()).isEqualTo(name.puuid());
        restarted.member(icon,null,29,NOW.plusSeconds(16));
        // Neither host is eligible again before its own completion-based two-second floor.
        assertThat(restarted.claim(NOW.plusMillis(16500),Duration.ofSeconds(120))).isEmpty();
        var nextName=restarted.claim(NOW.plusSeconds(17),Duration.ofSeconds(120)).orElseThrow();assertThat(nextName.icon()).isFalse();
        restarted.failure(nextName,NOW.plusSeconds(137),true,NOW.plusSeconds(17));
        assertThat(store.claim(NOW.plusSeconds(136),Duration.ofSeconds(120))).isEmpty();
        assertThat(store.claim(NOW.plusSeconds(137),Duration.ofSeconds(120))).isPresent();
    }
    @Test void initialFillCapsOneHostAndExpiresPermanentlyEvenDuringARequest() {
        jdbc.update("update league_analysis.leaderboard_state set initial_fill_completed_at=null");
        publishForEnrichment(claim(NOW),hundred(),NOW);
        jdbc.update("update league_analysis.leaderboard_member set game_name='Cached',tag_line='NA1',name_due_at=?",java.sql.Timestamp.from(NOW.plusSeconds(86400)));
        var first=claim(NOW.plusSeconds(15));assertThat(first.icon()).isTrue();
        store.member(first,null,29,NOW.plusSeconds(15));
        assertThat(store.claim(NOW.plusSeconds(16),Duration.ofSeconds(120))).isEmpty();
        var second=claim(NOW.plusSeconds(17));assertThat(second.icon()).isTrue();
        // An unavailable icon yields to other candidates without resetting the bounded phase.
        store.failure(second,NOW.plusSeconds(77),false,NOW.plusSeconds(17));
        jdbc.update("update league_analysis.leaderboard_state set ladder_due_at=?",java.sql.Timestamp.from(NOW.plusSeconds(1800)));
        var pending=claim(NOW.plusSeconds(914));
        store.failure(pending,NOW.plusSeconds(976),false,NOW.plusSeconds(916));
        assertThat(jdbc.queryForObject("select initial_fill_deadline from league_analysis.leaderboard_state",java.sql.Timestamp.class).toInstant()).isEqualTo(NOW.plusSeconds(915));
        assertThat(jdbc.queryForObject("select initial_fill_completed_at from league_analysis.leaderboard_state",java.sql.Timestamp.class).toInstant()).isEqualTo(NOW.plusSeconds(916));
        var restarted=new LeaderboardStore(jdbc,manager,context.getBean(dev.leagueanalysis.privacy.PrivacyRuntimeGuard.class));
        assertThat(restarted.claim(NOW.plusSeconds(930),Duration.ofSeconds(120))).isEmpty();
        var steady=restarted.claim(NOW.plusSeconds(931),Duration.ofSeconds(120)).orElseThrow();
        restarted.member(steady,null,30,NOW.plusSeconds(931));
        assertThat(restarted.claim(NOW.plusSeconds(933),Duration.ofSeconds(120))).isEmpty();
    }
    @Test void fullyCachedInitialFillCompletesWithoutRequestsAndDoesNotRestartForMissingMembers() {
        jdbc.update("update league_analysis.leaderboard_state set initial_fill_completed_at=null");
        publishForEnrichment(claim(NOW),hundred(),NOW);
        jdbc.update("update league_analysis.leaderboard_member set game_name='Cached',tag_line='NA1',profile_icon_id=29,name_due_at=?,icon_due_at=?",java.sql.Timestamp.from(NOW.plusSeconds(86400)),java.sql.Timestamp.from(NOW.plusSeconds(86400)));
        assertThat(store.claim(NOW.plusSeconds(15),Duration.ofSeconds(120))).isEmpty();
        assertThat(jdbc.queryForObject("select initial_fill_completed_at from league_analysis.leaderboard_state",java.sql.Timestamp.class).toInstant()).isEqualTo(NOW.plusSeconds(15));
        jdbc.update("update league_analysis.leaderboard_member set profile_icon_id=null,icon_due_at=? where puuid='leaderboard-only-0'",java.sql.Timestamp.from(NOW));
        // Warm idle scans reserve the normal cadence instead of rescanning caches every scheduler tick.
        assertThat(store.claim(NOW.plusSeconds(16),Duration.ofSeconds(120))).isEmpty();
        var later=claim(NOW.plusSeconds(30));
        store.member(later,null,31,NOW.plusSeconds(30));
        assertThat(store.claim(NOW.plusSeconds(32),Duration.ofSeconds(120))).isEmpty();
        assertThat(jdbc.queryForObject("select initial_fill_completed_at from league_analysis.leaderboard_state",java.sql.Timestamp.class).toInstant()).isEqualTo(NOW.plusSeconds(15));
    }
    @Test void equallyDueEnrichmentFollowsStandingsButOlderWorkKeepsPriority() {
        var rows=new ArrayList<>(hundred());
        rows.set(0,new LeaderboardClient.Standing("z-first","CHALLENGER",2000,20,10));
        rows.set(1,new LeaderboardClient.Standing("a-second","CHALLENGER",1999,20,10));
        rows.set(99,new LeaderboardClient.Standing("0-grandmaster","CHALLENGER",500,20,10));
        publishForEnrichment(claim(NOW),rows,NOW);
        var name=claim(NOW.plusSeconds(15));
        assertThat(name.puuid()).isEqualTo("z-first");assertThat(name.icon()).isFalse();
        store.member(name,new LeaderboardClient.Name("First","NA1"),null,NOW.plusSeconds(15));
        assertThat(store.claim(NOW.plusSeconds(29),Duration.ofSeconds(120))).isEmpty();
        var icon=claim(NOW.plusSeconds(30));
        assertThat(icon.puuid()).isEqualTo("z-first");assertThat(icon.icon()).isTrue();
        store.member(icon,null,29,NOW.plusSeconds(30));
        jdbc.update("update league_analysis.leaderboard_member set name_due_at=? where puuid='0-grandmaster'",java.sql.Timestamp.from(NOW.minusSeconds(1)));
        var older=claim(NOW.plusSeconds(45));
        assertThat(older.puuid()).isEqualTo("0-grandmaster");assertThat(older.icon()).isFalse();
        // Enrichment priority changes do not change the published rank ordering.
        assertThat(store.read(NOW.plusSeconds(45),true).entries().getFirst().gameName()).isEqualTo("First");
    }
    @Test void expiredFallbackCycleStartsAgainInsteadOfPublishingOldStandingsAsFresh() {
        store.ladder(claim(NOW),List.of(new LeaderboardClient.Standing("old","CHALLENGER",100,2,1)),NOW);
        var resumed=claim(NOW.plusSeconds(901));
        assertThat(resumed.tier()).isZero();
        assertThat(jdbc.queryForObject("select challenger_histogram from league_analysis.leaderboard_cutoff",String.class)).isNull();
        assertThat(store.read(NOW.plusSeconds(901),true).cutoffs().status()).isEqualTo("unavailable");
    }
    @Test void privacyCutoffKeepsNextEligibleChallengerInsteadOfFallingBackToGrandmaster() {
        jdbc.update("insert into league_analysis.privacy_exclusion values ('puuid',?)",PrivacyHash.of("leaderboard-only-0"));
        var rows=new ArrayList<>(hundred());rows.add(new LeaderboardClient.Standing("challenger-101","CHALLENGER",1,1,1));
        publishForEnrichment(claim(NOW),rows,NOW);
        assertThat(store.read(NOW,true).entries()).hasSize(100);
        assertThat(store.read(NOW,true).entries().getLast().leaguePoints()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_tier from league_analysis.leaderboard_state",Integer.class)).isZero();
    }
    @Test void freshForegroundAccountAndIconObservationsAvoidBackgroundRequests() {
        var ingestion=context.getBean(dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore.class);
        var fixture=new dev.leagueanalysis.support.PublicLookupGatewayFixture(json,Clock.fixed(NOW,ZoneOffset.UTC));
        var command=new dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand("Invented","NA1",20,420,0,NOW.getEpochSecond(),null);
        var run=ingestion.startPublicRun(command,NOW);
        var account=fixture.resolveAccount(new dev.leagueanalysis.ingestion.riot.domain.RiotId("Invented","NA1"));
        ingestion.recordResolvedAccount(run,account.account(),ingestion.saveCapture(run,account.source()));
        jdbc.update("update league_analysis.source_payload set payload_json=payload_json || cast(? as jsonb) where source_kind='ACCOUNT'", "{\"gameName\":\"Invented\",\"tagLine\":\"NA1\"}");
        ingestion.recordVerifiedProfile(run,new dev.leagueanalysis.ingestion.riot.domain.PlatformAccountProfile(29,100L,NOW),NOW);
        var rows=new ArrayList<>(hundred());rows.set(0,new LeaderboardClient.Standing(account.account().puuid(),"CHALLENGER",2000,9,1));
        publishForEnrichment(claim(NOW),rows,NOW);
        var first=store.read(NOW,true).entries().getFirst();
        assertThat(first.gameName()).isEqualTo("Invented");assertThat(first.profileIconId()).isEqualTo(29);
        jdbc.update("update league_analysis.leaderboard_member set name_due_at=?,icon_due_at=? where puuid<>?",java.sql.Timestamp.from(NOW.plusSeconds(86400)),java.sql.Timestamp.from(NOW.plusSeconds(86400)),account.account().puuid());
        assertThat(store.claim(NOW.plusSeconds(15),Duration.ofSeconds(120))).isEmpty();
        // A newer but invalid Account name must neither replace usable identity nor postpone enrichment.
        jdbc.update("update league_analysis.source_capture set captured_at=? where source_kind='ACCOUNT'",java.sql.Timestamp.from(NOW.plusSeconds(10)));
        jdbc.update("update league_analysis.source_payload set payload_json=payload_json || cast(? as jsonb) where source_kind='ACCOUNT'", "{\"gameName\":\"Invalid/Name\",\"tagLine\":\"NA1\"}");
        assertThat(store.claim(NOW.plusSeconds(30),Duration.ofSeconds(120))).isEmpty();
        assertThat(store.read(NOW.plusSeconds(30),true).entries().getFirst().gameName()).isEqualTo("Invented");
        assertThat(jdbc.queryForObject("select name_due_at from league_analysis.leaderboard_member where puuid=?",java.sql.Timestamp.class,account.account().puuid()).toInstant()).isEqualTo(NOW.plusSeconds(86400));
        // A recent match observation must not extend an old Account capture's freshness.
        jdbc.update("update league_analysis.source_capture set captured_at=? where source_kind='ACCOUNT'",java.sql.Timestamp.from(NOW.minusSeconds(172800)));
        jdbc.update("update league_analysis.riot_identity set last_observed_at=? where puuid=?",java.sql.Timestamp.from(NOW.plusSeconds(20)),account.account().puuid());
        jdbc.update("update league_analysis.leaderboard_member set name_fetched_at=null,name_due_at=? where puuid=?",java.sql.Timestamp.from(NOW),account.account().puuid());
        var work=claim(NOW.plusSeconds(45));assertThat(work.puuid()).isEqualTo(account.account().puuid());assertThat(work.icon()).isFalse();
        jdbc.execute("truncate league_analysis.ingestion_run,league_analysis.source_payload cascade");
    }
    @Test void sparseFallbackPublishesAtomicallyAndLeaderboardOnlyPrivacyRemovalErasesBothCycles(){
        store.ladder(claim(NOW),List.of(new LeaderboardClient.Standing("only-apex","CHALLENGER",1,2,3)),NOW);
        assertThat(store.read(NOW,true).entries()).hasSize(1);
        assertThat(store.claim(NOW.plusSeconds(14),Duration.ofSeconds(120))).isEmpty();
        store.ladder(claim(NOW.plusSeconds(15)),List.of(),NOW.plusSeconds(15));
        store.ladder(claim(NOW.plusSeconds(30)),List.of(new LeaderboardClient.Standing("master","MASTER",900,5,6)),NOW.plusSeconds(30));
        assertThat(store.read(NOW,true).entries()).extracting(Leaderboard.Entry::tier).containsExactly("CHALLENGER");
        jdbc.update("update league_analysis.leaderboard_member set game_name='Known',tag_line='NA1' where puuid='only-apex'");
        jdbc.update("insert into league_analysis.leaderboard_entry values(true,'only-apex','CHALLENGER',2,3,4)");
        var planner=new RemovalPlanner(jdbc,json);var plan=planner.plan("only-apex");
        assertThat(plan.affectedRecords().get("leaderboard_entry")).isEqualTo(2L);
        assertThat(plan.aliasHashes()).contains(PrivacyHash.riotId("Known","NA1"));
        new TransactionTemplate(manager).executeWithoutResult(status->{jdbc.execute("set local league_analysis.removal_operator='on'");planner.apply(plan);});
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.leaderboard_entry where puuid='only-apex'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.leaderboard_member where puuid='only-apex'",Integer.class)).isZero();
        jdbc.update("insert into league_analysis.privacy_exclusion values ('puuid',?)",PrivacyHash.of("master"));
        assertThat(store.read(NOW,true).entries()).isEmpty();
        assertThatThrownBy(()->jdbc.update("insert into league_analysis.leaderboard_entry values(true,'master','MASTER',2,3,4)")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void emptyCacheIsReadableWithoutCredentialsOrProviderWork() throws Exception {
        var response=MockMvcBuilders.webAppContextSetup(context).build().perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/leaderboard/na")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"status\":\"unavailable\"", "\"entries\":[]").doesNotContain("puuid");
    }
}
