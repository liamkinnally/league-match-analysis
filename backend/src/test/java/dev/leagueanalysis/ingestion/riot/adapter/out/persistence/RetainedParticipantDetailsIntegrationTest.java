package dev.leagueanalysis.ingestion.riot.adapter.out.persistence;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.application.*;
import dev.leagueanalysis.ingestion.riot.domain.*;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Invented provider fixtures; no network or retained personal captures. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class RetainedParticipantDetailsIntegrationTest {
    private static final String MATCH = "NA1_9999999999";
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    @Autowired JdbcRiotIngestionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach @AfterEach void clear() {
        jdbc.execute("truncate table league_analysis.ingestion_run, league_analysis.source_payload cascade");
        jdbc.execute("truncate table league_analysis.privacy_exclusion");
    }

    @Test void cachedHistoryEnrichesLegacyDetailsWithoutReplacingTimelineOrIdentity() throws Exception {
        var run = legacyMatch();
        var before = preservedRows();

        assertThat(store.readPublicRun(run).orElseThrow().matches()).hasSize(1);

        assertThat(jdbc.queryForObject("select participant_totals->>'totalHeal' from league_analysis.riot_participant where match_id=? and participant_id=1",
                String.class, MATCH)).isEqualTo("123");
        assertThat(jdbc.queryForObject("select rune_snapshot#>>'{styles,0,selections,0,var1}' from league_analysis.riot_participant where match_id=? and participant_id=1",
                String.class, MATCH)).isEqualTo("0");
        assertThat(jdbc.queryForObject("select rune_snapshot#>>'{styles,0,selections,0,var2}' from league_analysis.riot_participant where match_id=? and participant_id=1",
                String.class, MATCH)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_participant where detail_extension_version=?",
                Integer.class, ParticipantDetails.VERSION)).isEqualTo(2);
        assertThat(preservedRows()).isEqualTo(before);
        var enriched = jdbc.queryForList("select xmin::text, to_jsonb(p)::text from league_analysis.riot_participant p order by participant_id");
        store.readPublicRun(run);
        assertThat(jdbc.queryForList("select xmin::text, to_jsonb(p)::text from league_analysis.riot_participant p order by participant_id"))
                .isEqualTo(enriched);
    }

    @Test void onlyCurrentMatchCaptureIsUsedEvenWhenRunHasANewerCapture() throws Exception {
        var run = legacyMatch();
        ObjectNode replacement = (ObjectNode) store.storedDetail(MATCH).orElseThrow().payload();
        ((ObjectNode) replacement.path("info").path("participants").get(0)).put("totalHeal", 999);
        var unused = store.saveCapture(run, document(SourceKind.MATCH_DETAIL, MATCH, replacement, 2));
        var before = preservedRows();

        assertThat(store.enrichParticipantDetails(MATCH)).isTrue();

        assertThat(jdbc.queryForObject("select participant_totals->>'totalHeal' from league_analysis.riot_participant where match_id=? and participant_id=1",
                String.class, MATCH)).isEqualTo("123");
        assertThat(jdbc.queryForObject("select detail_source_capture_id from league_analysis.riot_match where match_id=?",
                UUID.class, MATCH)).isNotEqualTo(unused.captureId());
        assertThat(preservedRows()).isEqualTo(before);
    }

    @Test void versionAndMembershipConflictsStayMissingRatherThanRepairingIdentity() throws Exception {
        legacyMatch();
        jdbc.update("update league_analysis.riot_match set game_version='16.18.1' where match_id=?", MATCH);
        assertThat(store.enrichParticipantDetails(MATCH)).isFalse();
        jdbc.update("update league_analysis.riot_match set game_version='16.17.810.4348' where match_id=?", MATCH);
        jdbc.update("update league_analysis.riot_participant set champion_id=999 where match_id=? and participant_id=2", MATCH);
        var before = preservedRows();
        assertThat(store.enrichParticipantDetails(MATCH)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_participant where detail_extension_version is not null",
                Integer.class)).isZero();
        assertThat(preservedRows()).isEqualTo(before);
        jdbc.update("update league_analysis.riot_participant set champion_id=2,detail_extension_version='future-version' where match_id=? and participant_id=2", MATCH);
        assertThat(store.enrichParticipantDetails(MATCH)).isFalse();
        assertThat(jdbc.queryForObject("select participant_totals from league_analysis.riot_participant where participant_id=1",
                String.class)).isNull();
    }

    @Test void excludedOrRemovedSubjectsCannotBeReintroducedByEnrichment() throws Exception {
        legacyMatch();
        jdbc.update("insert into league_analysis.privacy_exclusion(kind,subject_hash) values ('puuid', league_analysis.privacy_hash(?))",
                "invented-puuid-2");
        assertThat(store.enrichParticipantDetails(MATCH)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_participant where detail_extension_version is not null",
                Integer.class)).isZero();
        jdbc.execute("delete from league_analysis.riot_match");
        var before = preservedRows();
        assertThat(store.enrichParticipantDetails(MATCH)).isFalse();
        assertThat(preservedRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_participant", Integer.class)).isZero();
    }

    @Test void concurrentCacheReadersPerformOnlyOneEnrichment() throws Exception {
        legacyMatch();
        var before = preservedRows();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return store.enrichParticipantDetails(MATCH); });
            var second = executor.submit(() -> { start.await(); return store.enrichParticipantDetails(MATCH); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(preservedRows()).isEqualTo(before);
    }

    @Test void malformedRetainedRequiredFieldsLeaveTheExistingSummaryAvailable() throws Exception {
        var run = legacyMatch();
        jdbc.update("""
                update league_analysis.source_payload set payload_json=payload_json #- '{info,gameVersion}'
                where id=(select c.source_payload_id from league_analysis.riot_match m
                    join league_analysis.source_capture c on c.id=m.detail_source_capture_id where m.match_id=?)
                """, MATCH);
        var before = preservedRows();
        assertThat(store.readPublicRun(run).orElseThrow().matches()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_participant where detail_extension_version is not null",
                Integer.class)).isZero();
        assertThat(preservedRows()).isEqualTo(before);
    }

    private UUID legacyMatch() throws Exception {
        var command = new RiotIngestionCommand("InventedPlayer", "NA1", 20);
        var run = store.startPublicRun(command, NOW);
        var account = document(SourceKind.ACCOUNT, "InventedPlayer#NA1", json.createObjectNode()
                .put("puuid", "invented-puuid-1").put("gameName", "InventedPlayer").put("tagLine", "NA1"));
        var accountCapture = store.saveCapture(run, account);
        store.recordResolvedAccount(run, new RiotAccount("invented-puuid-1", "InventedPlayer", "NA1"), accountCapture);
        store.recordVerifiedRequestedIdentity(run, command);
        store.addItems(run, List.of(MATCH));
        ObjectNode detail = fixture("match-detail-minimal.json");
        var first = (ObjectNode) detail.path("info").path("participants").get(0);
        first.put("totalHeal", 123).put("totalHealsOnTeammates", 0).put("gameEndedInEarlySurrender", false);
        first.set("perks", json.readTree("""
                {"styles":[{"style":8000,"description":"primaryStyle","selections":[{"perk":8010,"var1":0}]}],
                 "statPerks":{"offense":5005,"flex":5008,"defense":5001}}
                """));
        var captured = store.saveCapture(run, document(SourceKind.MATCH_DETAIL, MATCH, detail));
        var timeline = store.saveCapture(run, document(SourceKind.MATCH_TIMELINE, MATCH, fixture("timeline-minimal.json")));
        store.materialize(run, MATCH, new MatchV5Decoder().decode(captured, Optional.of(timeline)));
        store.markItemTerminal(run, MATCH, IngestionItemStatus.COMPLETE, null, null, NOW);
        store.finishRun(run, IngestionRunStatus.COMPLETE, null, null, NOW);
        jdbc.update("""
                update league_analysis.riot_participant set rune_snapshot=null, participant_totals=null,
                    game_ended_in_early_surrender=null, game_ended_in_surrender=null,
                    team_early_surrendered=null, detail_extension_version=null where match_id=?
                """, MATCH);
        return run;
    }

    private ObjectNode fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/riot/" + name)) {
            return (ObjectNode) json.readTree(in);
        }
    }

    private ProviderDocument document(SourceKind kind, String key, ObjectNode body) throws Exception {
        return document(kind, key, body, 1);
    }

    private ProviderDocument document(SourceKind kind, String key, ObjectNode body, int attempt) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        return new ProviderDocument(kind, key, NOW, 200, "AMERICAS", "NA1", "16.17.810.4348",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length,
                body, json.createObjectNode(), "invented-enrichment-test-v1", attempt);
    }

    private List<Object> preservedRows() {
        return List.of("riot_match", "riot_identity", "riot_team", "participant_state_observation", "match_event",
                "evidence_coverage", "ingestion_run", "ingestion_item", "source_capture", "source_payload").stream()
                .<Object>map(table -> jdbc.queryForList("select to_jsonb(r)::text from league_analysis." + table + " r order by to_jsonb(r)::text"))
                .toList();
    }
}
