package dev.leagueanalysis.analysis.match.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.application.HistoricalMatchQuery;
import dev.leagueanalysis.analysis.match.application.TransitionPolicy;
import dev.leagueanalysis.analysis.match.application.TransitionSelector;
import dev.leagueanalysis.analysis.match.domain.AnchorKind;
import dev.leagueanalysis.analysis.match.domain.ParticipantObservation;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.support.P3SanitizedMatchFixture;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class JdbcHistoricalMatchQueryIntegrationTest {
    private static final String SENTINEL_MATCH_ID = "NA1_9000000099";
    private static final UUID SENTINEL_RUN_ID = P3SanitizedMatchFixture.uuid(901);
    private static final UUID SENTINEL_PAYLOAD_ID = P3SanitizedMatchFixture.uuid(902);
    private static final UUID SENTINEL_CAPTURE_ID = P3SanitizedMatchFixture.uuid(903);

    @Autowired
    HistoricalMatchQuery query;

    @Autowired
    P3SanitizedMatchFixture fixture;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        fixture.replaceFixtureMatch();
    }

    @AfterEach
    void removeSentinel() {
        jdbc.update("delete from league_analysis.riot_match where match_id = ?", SENTINEL_MATCH_ID);
        jdbc.update("delete from league_analysis.source_capture where id = ?", SENTINEL_CAPTURE_ID);
        jdbc.update("delete from league_analysis.ingestion_run where id = ?", SENTINEL_RUN_ID);
        jdbc.update("delete from league_analysis.source_payload where id = ?", SENTINEL_PAYLOAD_ID);
    }

    @Test
    void loadsOneOrderedSourceNeutralSnapshotWithoutRawPayloads() {
        var snapshot = query.inReadSnapshot(() ->
                query.load(P3SanitizedMatchFixture.MATCH_ID)).orElseThrow();

        assertThat(snapshot.header().matchId()).isEqualTo("NA1_9000000001");
        assertThat(snapshot.participants()).hasSize(10);
        assertThat(snapshot.observations())
                .extracting(ParticipantObservation::representedAtMs)
                .contains(480_210L, 600_228L, 780_275L, 900_291L,
                        1_080_335L, 1_200_389L, 1_500_511L, 1_620_521L);
        assertThat(snapshot.anchors())
                .extracting(anchor -> anchor.key().representedAtMs())
                .isSorted();
        assertThat(snapshot.anchors()).allSatisfy(anchor ->
                assertThat(anchor.evidenceReferences()).isNotEmpty());
    }

    @Test
    void reconcilesConflictingAnchorRelationsWithoutSelectingAConvenientReport() {
        jdbc.update("""
                insert into league_analysis.match_event
                    (id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                     provider_event_type, canonical_event_kind, actor_participant_id,
                     target_participant_id, position_x, position_y, event_payload,
                     source_capture_id, method_version)
                values (?, ?, 540215, 540000, 1, 'CHAMPION_KILL', 'CHAMPION_KILL',
                        1, 6, 6200, 5100,
                        jsonb_build_object(
                            'teamId', 100,
                            'assistingParticipantIds', to_jsonb(array[3]::integer[])),
                        ?, 'sanitized-event-v1')
                """,
                P3SanitizedMatchFixture.uuid(501),
                P3SanitizedMatchFixture.MATCH_ID,
                P3SanitizedMatchFixture.ALTERNATE_TIMELINE_CAPTURE_ID);

        var snapshot = query.inReadSnapshot(() ->
                query.load(P3SanitizedMatchFixture.MATCH_ID)).orElseThrow();
        var conflictingAnchor = snapshot.anchors().stream()
                .filter(anchor -> anchor.key().representedAtMs() == 540_215)
                .findFirst()
                .orElseThrow();

        assertThat(conflictingAnchor.actorParticipantId()).isNull();
        assertThat(conflictingAnchor.teamId()).isNull();
        assertThat(conflictingAnchor.assisterParticipantIds()).isEmpty();
        assertThat(conflictingAnchor.assistersObserved()).isFalse();
        assertThat(conflictingAnchor.positionX()).isNull();
        assertThat(conflictingAnchor.positionY()).isNull();
        assertThat(conflictingAnchor.evidenceReferences()).hasSize(2);
        assertThat(conflictingAnchor.limitationCodes()).contains("AMBIGUOUS_ANCHOR_RELATION");
    }

    @Test
    void makesOwnershipUnavailableWhenOneReportContainsConflictingTeamFields() {
        jdbc.update("""
                insert into league_analysis.match_event
                    (id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                     provider_event_type, canonical_event_kind, actor_participant_id,
                     position_x, position_y, event_payload, source_capture_id, method_version)
                values (?, ?, 600225, 600000, 8, 'ELITE_MONSTER_KILL', 'OBJECTIVE',
                        2, 5000, 6000,
                        jsonb_build_object(
                            'killerTeamId', 100,
                            'teamId', 200,
                            'monsterType', 'DRAGON'),
                        ?, 'sanitized-event-v1')
                """,
                P3SanitizedMatchFixture.uuid(502),
                P3SanitizedMatchFixture.MATCH_ID,
                P3SanitizedMatchFixture.TIMELINE_CAPTURE_ID);

        var snapshot = query.inReadSnapshot(() ->
                query.load(P3SanitizedMatchFixture.MATCH_ID)).orElseThrow();
        var conflictingOwnership = snapshot.anchors().stream()
                .filter(anchor -> anchor.key().representedAtMs() == 600_225)
                .findFirst()
                .orElseThrow();

        assertThat(conflictingOwnership.teamId()).isNull();
        assertThat(conflictingOwnership.limitationCodes())
                .contains("AMBIGUOUS_ANCHOR_RELATION");
    }

    @Test
    void narrative_preserves_suppressed_ownership_from_the_historical_query() {
        jdbc.update("""
                update league_analysis.match_event
                set actor_participant_id = 7,
                    event_payload = jsonb_build_object(
                        'killerTeamId', 200, 'teamId', 100, 'monsterType', 'RIFTHERALD')
                where match_id = ? and represented_at_ms = 1170375
                """, P3SanitizedMatchFixture.MATCH_ID);
        var snapshot = query.inReadSnapshot(() ->
                query.load(P3SanitizedMatchFixture.MATCH_ID)).orElseThrow();
        var ambiguous = snapshot.anchors().stream()
                .filter(anchor -> anchor.key().representedAtMs() == 1_170_375L)
                .findFirst().orElseThrow();
        assertThat(ambiguous.actorParticipantId()).isEqualTo(7);
        assertThat(ambiguous.teamId()).isNull();
        assertThat(ambiguous.limitationCodes()).contains("AMBIGUOUS_ANCHOR_RELATION");

        var packet = new TransitionSelector().select(snapshot, 6, TransitionPolicy.p3())
                .stream().filter(candidate -> candidate.interval().startMs() == 1_080_335L)
                .findFirst().orElseThrow();

        assertThat(packet.questionKind()).isEqualTo(QuestionKind.ADVERSE_CONSEQUENCE);
        assertThat(packet.title()).containsIgnoringCase("sampled").doesNotContain("death");
        assertThat(packet.limitationCodes()).contains("AMBIGUOUS_ANCHOR_RELATION");
        assertThat(packet.evidenceReferences()).containsAll(ambiguous.evidenceReferences());
        assertThat(packet.claims()).anySatisfy(claim -> {
            assertThat(claim.limitationCodes()).contains("AMBIGUOUS_ANCHOR_RELATION");
            assertThat(claim.evidenceReferences()).containsAll(ambiguous.evidenceReferences());
        });
    }

    @Test
    void reconcilesConflictingDerivedKindsAsAnUnavailableOtherAnchor() {
        jdbc.update("""
                insert into league_analysis.match_event
                    (id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                     provider_event_type, canonical_event_kind, actor_participant_id,
                     position_x, position_y, event_payload, source_capture_id, method_version)
                values (?, ?, 660240, 660000, 9, 'ELITE_MONSTER_KILL', 'OBJECTIVE',
                        2, 5000, 6000,
                        jsonb_build_object('killerTeamId', 100, 'monsterType', 'DRAGON'),
                        ?, 'sanitized-event-v1'),
                       (?, ?, 660240, 660000, 9, 'ELITE_MONSTER_KILL', 'OBJECTIVE',
                        2, 5000, 6000,
                        jsonb_build_object('killerTeamId', 100, 'monsterType', 'BARON_NASHOR'),
                        ?, 'sanitized-event-v1')
                """,
                P3SanitizedMatchFixture.uuid(503),
                P3SanitizedMatchFixture.MATCH_ID,
                P3SanitizedMatchFixture.TIMELINE_CAPTURE_ID,
                P3SanitizedMatchFixture.uuid(504),
                P3SanitizedMatchFixture.MATCH_ID,
                P3SanitizedMatchFixture.ALTERNATE_TIMELINE_CAPTURE_ID);

        var snapshot = query.inReadSnapshot(() ->
                query.load(P3SanitizedMatchFixture.MATCH_ID)).orElseThrow();
        var conflictingKind = snapshot.anchors().stream()
                .filter(anchor -> anchor.key().representedAtMs() == 660_240)
                .findFirst()
                .orElseThrow();

        assertThat(conflictingKind.kind()).isEqualTo(AnchorKind.OTHER);
        assertThat(conflictingKind.actorParticipantId()).isNull();
        assertThat(conflictingKind.targetParticipantId()).isNull();
        assertThat(conflictingKind.teamId()).isNull();
        assertThat(conflictingKind.assisterParticipantIds()).isEmpty();
        assertThat(conflictingKind.assistersObserved()).isFalse();
        assertThat(conflictingKind.positionX()).isNull();
        assertThat(conflictingKind.positionY()).isNull();
        assertThat(conflictingKind.descriptor()).isNull();
        assertThat(conflictingKind.evidenceReferences()).hasSize(2);
        assertThat(conflictingKind.limitationCodes())
                .contains("AMBIGUOUS_ANCHOR_RELATION");
    }

    @Test
    void replacingTheFixturePreservesAnUnrelatedMatchAndSourcePayload() {
        var observedAt = OffsetDateTime.parse("2026-09-03T12:00:00Z");
        jdbc.update("""
                insert into league_analysis.ingestion_run
                    (id, requested_game_name, requested_tag_line, platform_route, regional_route,
                     queue_id, match_limit, status, started_at, completed_at)
                values (?, 'Unrelated Sentinel', 'TEST', 'NA1', 'AMERICAS',
                        420, 1, 'COMPLETE', ?, ?)
                """, SENTINEL_RUN_ID, observedAt, observedAt);
        jdbc.update("""
                insert into league_analysis.source_payload
                    (id, source_kind, body_sha256, body_size_bytes, payload_json)
                values (?, 'MATCH_DETAIL', ?, 0, jsonb_build_object())
                """, SENTINEL_PAYLOAD_ID, "c".repeat(64));
        jdbc.update("""
                insert into league_analysis.source_capture
                    (id, ingestion_run_id, source_payload_id, source_kind, resource_key,
                     regional_route, platform_route, captured_at, http_status,
                     response_metadata, parser_version, attempt)
                values (?, ?, ?, 'MATCH_DETAIL', ?, 'AMERICAS', 'NA1', ?, 200,
                        jsonb_build_object(), 'sentinel-parser-v1', 1)
                """, SENTINEL_CAPTURE_ID, SENTINEL_RUN_ID, SENTINEL_PAYLOAD_ID,
                SENTINEL_MATCH_ID, observedAt);
        jdbc.update("""
                insert into league_analysis.riot_match
                    (match_id, game_id, queue_id, map_id, game_mode, game_type, game_version,
                     data_version, game_creation_ms, game_duration_seconds,
                     detail_source_capture_id, materialization_version, materialized_at)
                values (?, 9000000099, 420, 11, 'CLASSIC', 'MATCHED_GAME', '16.17.810.4348',
                        '2', 1788451200000, 2076, ?, 'sentinel-materializer-v1', ?)
                """, SENTINEL_MATCH_ID, SENTINEL_CAPTURE_ID, observedAt);

        fixture.replaceFixtureMatch();

        assertThat(jdbc.queryForObject(
                "select count(*) from league_analysis.riot_match where match_id = ?",
                Integer.class,
                SENTINEL_MATCH_ID)).isOne();
        fixture.prepareBrowserEvidence();
        assertThat(jdbc.queryForObject(
                "select count(*) from league_analysis.riot_match where match_id = ?",
                Integer.class,
                SENTINEL_MATCH_ID)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from league_analysis.source_payload where id = ?",
                Integer.class,
                SENTINEL_PAYLOAD_ID)).isOne();
    }

    @Test
    void returnsEmptyForAMissingMatch() {
        assertThat(query.load("NA1_missing")).isEmpty();
    }

    @Test
    void loadUsesOneRepeatableReadSnapshotAcrossACommittedRematerialization() {
        var rematerialized = new AtomicBoolean();
        var interleavingJdbc = new JdbcTemplate(dataSource) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
                var rows = super.query(sql, rowMapper, arguments);
                if (sql.contains("from league_analysis.riot_match")
                        && rematerialized.compareAndSet(false, true)) {
                    assertThat(queryForObject("show transaction_isolation", String.class))
                            .isEqualTo("repeatable read");
                    assertThat(queryForObject("show transaction_read_only", String.class))
                            .isEqualTo("on");
                    CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                                    .executeWithoutResult(status -> {
                                        jdbc.update("""
                                                update league_analysis.riot_match
                                                set timeline_source_capture_id = ?,
                                                    materialization_version =
                                                        'sanitized-materializer-v2'
                                                where match_id = ?
                                                """,
                                                P3SanitizedMatchFixture.ALTERNATE_TIMELINE_CAPTURE_ID,
                                                P3SanitizedMatchFixture.MATCH_ID);
                                        jdbc.update("""
                                                update league_analysis.participant_state_observation
                                                set total_gold = total_gold + 100000,
                                                    source_capture_id = ?
                                                where match_id = ?
                                                """,
                                                P3SanitizedMatchFixture.ALTERNATE_TIMELINE_CAPTURE_ID,
                                                P3SanitizedMatchFixture.MATCH_ID);
                                        jdbc.update("""
                                                update league_analysis.match_event
                                                set source_capture_id = ?
                                                where match_id = ?
                                                """,
                                                P3SanitizedMatchFixture.ALTERNATE_TIMELINE_CAPTURE_ID,
                                                P3SanitizedMatchFixture.MATCH_ID);
                                        jdbc.update("""
                                                update league_analysis.evidence_coverage
                                                set source_capture_id = ?
                                                where match_id = ?
                                                """,
                                                P3SanitizedMatchFixture.ALTERNATE_TIMELINE_CAPTURE_ID,
                                                P3SanitizedMatchFixture.MATCH_ID);
                                    }))
                            .join();
                }
                return rows;
            }
        };
        var interleavingQuery = new JdbcHistoricalMatchQuery(
                interleavingJdbc, json, transactionManager);

        var loaded = interleavingQuery.inReadSnapshot(() ->
                interleavingQuery.load(P3SanitizedMatchFixture.MATCH_ID)).orElseThrow();

        assertThat(rematerialized).isTrue();
        assertThat(loaded.sourceRevision().materializationVersion())
                .isEqualTo("sanitized-materializer-v1");
        assertThat(loaded.sourceRevision().timelineCaptureId())
                .isEqualTo(P3SanitizedMatchFixture.TIMELINE_CAPTURE_ID);
        assertThat(loaded.observations()).allSatisfy(observation -> {
            assertThat(observation.totalGold()).isLessThan(100_000);
            assertThat(observation.evidence().sourceCaptureId())
                    .isEqualTo(P3SanitizedMatchFixture.TIMELINE_CAPTURE_ID);
        });
        assertThat(loaded.anchors()).allSatisfy(anchor ->
                assertThat(anchor.evidenceReferences()).allSatisfy(reference ->
                        assertThat(reference.sourceCaptureId())
                                .isEqualTo(P3SanitizedMatchFixture.TIMELINE_CAPTURE_ID)));

        var current = query.inReadSnapshot(() ->
                query.load(P3SanitizedMatchFixture.MATCH_ID)).orElseThrow();
        assertThat(current.sourceRevision().materializationVersion())
                .isEqualTo("sanitized-materializer-v2");
        assertThat(current.sourceRevision().timelineCaptureId())
                .isEqualTo(P3SanitizedMatchFixture.ALTERNATE_TIMELINE_CAPTURE_ID);
        assertThat(current.observations()).allSatisfy(observation -> {
            assertThat(observation.totalGold()).isGreaterThan(100_000);
            assertThat(observation.evidence().sourceCaptureId())
                    .isEqualTo(P3SanitizedMatchFixture.ALTERNATE_TIMELINE_CAPTURE_ID);
        });
    }
}
