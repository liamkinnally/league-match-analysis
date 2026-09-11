package dev.leagueanalysis.privacy;

import dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class RemovalPlannerIntegrationTest {
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17.11-bookworm");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static JdbcRiotIngestionStore store;
    static final ObjectMapper JSON = new ObjectMapper();
    static RemovalPlanner planner;
    static final String TARGET = "invented-puuid-1";
    static final String SHARED = "NA1_9999999999";
    static final String UNRELATED = "NA1_8888888888";

    @BeforeAll static void start() {
        DB.start();
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        jdbc = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        store = new JdbcRiotIngestionStore(jdbc, manager, JSON);
        planner = new RemovalPlanner(jdbc, JSON);
    }
    @AfterAll static void stop() { DB.stop(); }
    @BeforeEach void clean() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("set local league_analysis.removal_operator = 'on'");
            jdbc.execute("truncate league_analysis.ingestion_run, league_analysis.source_payload cascade");
        });
    }

    @Test void dryRunReportsSharedMatchesWithoutChangingAnyRows() throws Exception {
        match(UNRELATED, "other-puuid", "invented-puuid-2", 0);
        match(SHARED, TARGET, "invented-puuid-2", 1);
        var before = snapshot();
        var plan = planner.plan(TARGET);
        assertThat(plan.matchIds()).containsExactly(SHARED);
        assertThat(plan.affectedRecords()).containsEntry("riot_match", 1L).containsEntry("riot_participant", 2L);
        assertThat(plan.puuidHashes()).containsExactly(PrivacyHash.of(TARGET));
        assertThat(plan.aliasHashes()).contains(PrivacyHash.riotId("InventedPlayer", "NA1"));
        assertThat(planner.plan(TARGET).fingerprint()).isEqualTo(plan.fingerprint());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void deletesDuplicateRawCapturesAndInterruptedLookupButPreservesOtherMatchAndReanchorsIdentity() throws Exception {
        match(UNRELATED, "other-puuid", "invented-puuid-2", 0);
        match(SHARED, TARGET, "invented-puuid-2", 1);
        match(SHARED, TARGET, "invented-puuid-2", 2);
        UUID interrupted = accountRun("InventedPlayer", TARGET);
        // A pre-privacy interruption could retain the submitted Riot ID before resolution.
        jdbc.update("update league_analysis.ingestion_run set resolved_puuid=null, requested_game_name='InventedPlayer', requested_tag_line='NA1' where id=?", interrupted);
        var retained = jdbc.queryForMap("select * from league_analysis.riot_match where match_id=?", UNRELATED);
        var plan = planner.plan(TARGET);
        execute(plan);
        assertThat(jdbc.queryForMap("select * from league_analysis.riot_match where match_id=?", UNRELATED)).isEqualTo(retained);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_match where match_id=?", Long.class, SHARED)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_identity where puuid=?", Long.class, TARGET)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.ingestion_run where id=?", Long.class, interrupted)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_payload where payload_json::text like ?", Long.class, "%" + TARGET + "%")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_capture where resource_key=?", Long.class, SHARED)).isZero();
        assertThat(jdbc.queryForObject("select c.resource_key from league_analysis.riot_identity i join league_analysis.source_capture c on c.id=i.last_source_capture_id where i.puuid='invented-puuid-2'", String.class)).isEqualTo(UNRELATED);
        assertThat(planner.planHashes(plan.puuidHashes(), plan.aliasHashes(), plan.matchHashes()).affectedRecords().values()).allMatch(n -> n == 0L);
    }

    @Test void coversRawOnlyMatchAndDropsOtherPlayersListCaptureWithoutDroppingUnrelatedRecords() throws Exception {
        match(UNRELATED, "other-puuid", "invented-puuid-2", 0);
        UUID run = accountRun("OtherPlayer", "other-puuid");
        var body = JSON.readTree("{\"metadata\":{\"matchId\":\"NA1_7777777777\",\"participants\":[\"invented-puuid-1\"]},\"info\":{\"invalid\":true}}");
        store.saveCapture(run, document(SourceKind.MATCH_DETAIL, "NA1_7777777777", body, 1));
        store.saveCapture(run, document(SourceKind.MATCH_LIST, "other-puuid", JSON.readTree("[\"NA1_7777777777\",\"NA1_8888888888\"]"), 1));
        var plan = planner.plan(TARGET);
        assertThat(plan.matchIds()).containsExactly("NA1_7777777777");
        execute(plan);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_match", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_capture where source_kind='MATCH_LIST'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.ingestion_run where id=?", Long.class, run)).isEqualTo(1);
    }

    @Test void refusesUnknownIdentifierAndDriftAndExecutionOutsideOperatorTransaction() throws Exception {
        assertThatThrownBy(() -> planner.plan("not-recorded")).isInstanceOf(IllegalArgumentException.class);
        match(SHARED, TARGET, "invented-puuid-2", 0);
        var plan = planner.plan(TARGET);
        assertThatThrownBy(() -> planner.apply(plan)).isInstanceOf(IllegalStateException.class);
        accountRun("LatePlayer", "late-puuid");
        assertThatThrownBy(() -> execute(plan)).isInstanceOf(IllegalStateException.class).hasMessageContaining("PLAN_CHANGED");
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_match", Long.class)).isEqualTo(1);
    }

    @Test void refusesSubjectLookupOwningRetainedUnrelatedProvenance() throws Exception {
        UUID run = match(UNRELATED, "other-puuid", "invented-puuid-2", 0);
        jdbc.update("update league_analysis.ingestion_run set resolved_puuid=? where id=?", TARGET, run);
        assertThatThrownBy(() -> planner.plan(TARGET)).isInstanceOf(IllegalStateException.class).hasMessageContaining("RETAINED_PROVENANCE");
    }

    @Test void restorePlanRecognizesOrphanMalformedPayloadAndOrphanListFromLedgerHashes() {
        var malformed = JSON.valueToTree(Map.of("legacySubject", TARGET));
        var list = JSON.valueToTree(List.of(SHARED, UNRELATED));
        for (var body : List.of(malformed, list)) jdbc.update("""
                insert into league_analysis.source_payload(id,source_kind,body_sha256,body_size_bytes,payload_json)
                values(?,?,?,?,cast(? as jsonb))
                """, UUID.randomUUID(), body.isArray() ? "MATCH_LIST" : "MATCH_DETAIL", PrivacyHash.of(body.toString()),
                body.toString().getBytes(StandardCharsets.UTF_8).length, body.toString());
        // Free-form discovery remains unsupported; only prior verified ledger hashes allow this restore match.
        assertThatThrownBy(() -> planner.plan(TARGET)).isInstanceOf(IllegalArgumentException.class);
        var plan = planner.planHashes(Set.of(PrivacyHash.of(TARGET)), Set.of(), Set.of(PrivacyHash.of(SHARED)));
        assertThat(plan.affectedRecords()).containsEntry("source_payload", 2L);
        execute(plan);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_payload", Long.class)).isZero();
    }

    @Test void refusesUnresolvedLookupWhenVerifiedNameWasReusedByAnotherPlayer() {
        accountRun("ReusedName", TARGET);
        accountRun("ReusedName", "different-owner");
        UUID interrupted = store.startRun(new RiotIngestionCommand("ReusedName", "NA1", 2), Instant.now());
        // Seed the historical shape explicitly; new unresolved runs intentionally retain no names.
        jdbc.update("update league_analysis.ingestion_run set requested_game_name='ReusedName', requested_tag_line='NA1' where id=?", interrupted);
        var before = snapshot();
        assertThatThrownBy(() -> planner.plan(TARGET)).isInstanceOf(IllegalStateException.class).hasMessageContaining("AMBIGUOUS");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void resolvesTargetMatchListEvenWhenMatchWasNeverMaterialized() {
        UUID run = accountRun("InventedPlayer", TARGET);
        store.saveCapture(run, document(SourceKind.MATCH_LIST, TARGET, JSON.valueToTree(List.of(SHARED)), 0));
        var plan = planner.plan(TARGET);
        assertThat(plan.matchIds()).containsExactly(SHARED);
        assertThat(plan.affectedRecords()).containsEntry("riot_match", 0L).containsEntry("source_capture", 2L);
        execute(plan);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.ingestion_run", Long.class)).isZero();
    }

    @Test void refusesMatchListThatContradictsRetainedNormalizedParticipants() throws Exception {
        match(UNRELATED, "other-puuid", "invented-puuid-2", 0);
        UUID run = accountRun("InventedPlayer", TARGET);
        store.saveCapture(run, document(SourceKind.MATCH_LIST, TARGET, JSON.valueToTree(List.of(UNRELATED)), 0));
        var before = snapshot();
        assertThatThrownBy(() -> planner.plan(TARGET)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFLICTING_MATCH_MEMBERSHIP");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void removesAnonymousRunIdentifiedByItsAccountCaptureButPreservesSharedMatchLookupOwner() {
        UUID interrupted = store.startPublicRun(new RiotIngestionCommand("InventedPlayer", "NA1", 2), Instant.now());
        store.saveCapture(interrupted, document(SourceKind.ACCOUNT, "InventedPlayer#NA1",
                JSON.valueToTree(Map.of("puuid", TARGET, "gameName", "InventedPlayer", "tagLine", "NA1")), 0));
        UUID other = accountRun("OtherPlayer", "other-puuid");
        store.saveCapture(other, document(SourceKind.MATCH_DETAIL, SHARED,
                JSON.valueToTree(Map.of("metadata", Map.of("matchId", SHARED, "participants", List.of(TARGET, "other-puuid")))), 0));
        assertThat(jdbc.queryForMap("select requested_game_name,requested_tag_line,resolved_puuid from league_analysis.ingestion_run where id=?", interrupted))
                .containsEntry("requested_game_name", "").containsEntry("requested_tag_line", "").containsEntry("resolved_puuid", null);
        var plan = planner.plan(TARGET);
        assertThat(plan.affectedRecords()).containsEntry("ingestion_run", 1L);
        execute(plan);
        assertThat(store.readPublicRun(interrupted)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.ingestion_run where id=?", Long.class, other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_identity where puuid='other-puuid'", Long.class)).isEqualTo(1);
    }

    @Test void refusesConflictingAccountCaptureAndResolvedLookupOwnership() {
        UUID other = accountRun("OtherPlayer", "other-puuid");
        store.saveCapture(other, document(SourceKind.ACCOUNT, "InventedPlayer#NA1",
                JSON.valueToTree(Map.of("puuid", TARGET, "gameName", "InventedPlayer", "tagLine", "NA1")), 0));
        var before = snapshot();
        assertThatThrownBy(() -> planner.plan(TARGET)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFLICTING_ACCOUNT_RUN_OWNERSHIP");
        assertThat(snapshot()).isEqualTo(before);
    }

    static void execute(RemovalPlan plan) {
        tx.executeWithoutResult(status -> { jdbc.execute("set local league_analysis.removal_operator = 'on'"); planner.apply(plan); });
    }
    static UUID match(String id, String first, String second, int minute) throws Exception {
        var detail = JSON.readTree(RemovalPlannerIntegrationTest.class.getResourceAsStream("/fixtures/riot/match-detail-minimal.json"));
        String encoded = detail.toString().replace(SHARED, id).replace(TARGET, first).replace("invented-puuid-2", second);
        if (!first.equals(TARGET)) encoded = encoded.replace("InventedPlayer", "OtherPlayer");
        var timeline = JSON.readTree(RemovalPlannerIntegrationTest.class.getResourceAsStream("/fixtures/riot/timeline-minimal.json"));
        UUID run = store.startRun(new RiotIngestionCommand("Fixture", "NA1", 2), Instant.parse("2026-09-01T00:00:00Z"));
        store.addItems(run, List.of(id));
        var capturedDetail = store.saveCapture(run, document(SourceKind.MATCH_DETAIL, id, JSON.readTree(encoded), minute));
        var capturedTimeline = store.saveCapture(run, document(SourceKind.MATCH_TIMELINE, id, JSON.readTree(timeline.toString().replace(SHARED, id)), minute));
        store.materialize(run, id, new MatchV5Decoder().decode(capturedDetail, Optional.of(capturedTimeline)));
        return run;
    }
    static UUID accountRun(String name, String puuid) {
        UUID run = store.startRun(new RiotIngestionCommand(name, "NA1", 2), Instant.parse("2026-09-01T00:00:00Z"));
        var capture = store.saveCapture(run, document(SourceKind.ACCOUNT, name + "#NA1", JSON.valueToTree(Map.of("puuid",puuid,"gameName",name,"tagLine","NA1")), 0));
        store.recordResolvedAccount(run, new dev.leagueanalysis.ingestion.riot.domain.RiotAccount(puuid, name, "NA1"), capture);
        return run;
    }
    static ProviderDocument document(SourceKind kind, String resource, tools.jackson.databind.JsonNode body, int minute) {
        return new ProviderDocument(kind, resource, Instant.parse("2026-09-01T00:00:00Z").plusSeconds(minute * 60L), 200,
                "AMERICAS", "NA1", null, PrivacyHash.of(body.toString()), body.toString().getBytes(StandardCharsets.UTF_8).length,
                body, JSON.createObjectNode(), "test", 1);
    }
    static List<Map<String,Object>> snapshot() {
        return jdbc.queryForList("select 'run' kind, to_jsonb(t)::text data from league_analysis.ingestion_run t union all select 'identity',to_jsonb(t)::text from league_analysis.riot_identity t union all select 'capture',to_jsonb(t)::text from league_analysis.source_capture t order by kind,data");
    }
}
