package dev.leagueanalysis.ingestion.riot.adapter.out.persistence;

import dev.leagueanalysis.support.PostgresTestConfiguration;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class RiotIngestionSchemaIntegrationTest {
    private static final List<String> P1_TABLES = List.of(
            "evidence_coverage",
            "ingestion_item",
            "ingestion_run",
            "match_event",
            "participant_state_observation",
            "privacy_completion",
            "privacy_control",
            "privacy_exclusion",
            "riot_identity",
            "riot_match",
            "riot_participant",
            "riot_team",
            "source_capture",
            "source_payload");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void appliesMigrationTwoAndCreatesTheP1Tables() {
        assertThat(jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where version = '2' and success",
                Integer.class)).isEqualTo(1);

        assertThat(jdbc.queryForList("""
                        select table_name
                        from information_schema.tables
                        where table_schema = 'league_analysis'
                        order by table_name
                        """, String.class))
                .containsExactlyElementsOf(P1_TABLES);
    }

    @Test
    void upgradesAnAlreadyAppliedV2DatabaseThroughV3AndV4WithDeathContextQuerySupport() throws Exception {
        var database = "p1_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var adminUrl = jdbcUrl(postgres.getDatabaseName());
        try (var connection = DriverManager.getConnection(
                        adminUrl, postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }

        try {
            var upgradeUrl = jdbcUrl(database);
            var original = Flyway.configure()
                    .dataSource(upgradeUrl, postgres.getUsername(), postgres.getPassword())
                    .target(MigrationVersion.fromVersion("2"))
                    .load();
            original.migrate();
            assertThat(original.info().current().getVersion().getVersion()).isEqualTo("2");

            var v3 = Flyway.configure()
                    .dataSource(upgradeUrl, postgres.getUsername(), postgres.getPassword())
                    .target(MigrationVersion.fromVersion("3"))
                    .load();
            v3.migrate();
            assertThat(v3.info().current().getVersion().getVersion()).isEqualTo("3");

            var upgraded = Flyway.configure()
                    .dataSource(upgradeUrl, postgres.getUsername(), postgres.getPassword())
                    .target(MigrationVersion.fromVersion("4"))
                    .load();
            upgraded.migrate();

            assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("4");
            try (var connection = DriverManager.getConnection(
                            upgradeUrl, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement();
                    var column = statement.executeQuery("""
                            select data_type
                            from information_schema.columns
                            where table_schema = 'league_analysis'
                              and table_name = 'riot_participant'
                              and column_name = 'end_item_ids'
                            """)) {
                assertThat(column.next()).isTrue();
                assertThat(column.getString("data_type")).isEqualTo("jsonb");

                var indexes = new ArrayList<String>();
                try (var result = statement.executeQuery("""
                        select indexname
                        from pg_indexes
                        where schemaname = 'league_analysis'
                          and indexname in (
                              'participant_state_subject_time_idx',
                              'match_event_kind_order_idx')
                        order by indexname
                        """)) {
                    while (result.next()) {
                        indexes.add(result.getString("indexname"));
                    }
                }
                assertThat(indexes).containsExactly(
                        "match_event_kind_order_idx", "participant_state_subject_time_idx");
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                            adminUrl, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute("drop database " + database + " with (force)");
            }
        }
    }

    @Test
    void rejectsMissingRequiredRunFields() {
        assertThatThrownBy(() -> jdbc.update("""
                        insert into league_analysis.ingestion_run
                            (id, requested_game_name, requested_tag_line, platform_route,
                             regional_route, queue_id, match_limit, status, started_at)
                        values (?, null, 'NA1', 'NA1', 'AMERICAS', 420, 5, 'RUNNING', ?)
                        """, UUID.randomUUID(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidPlatformRoute() {
        assertInvalidRun("EUW1", "AMERICAS", 420, "RUNNING");
    }

    @Test
    void rejectsInvalidRegionalRoute() {
        assertInvalidRun("NA1", "EUROPE", 420, "RUNNING");
    }

    @Test
    void rejectsInvalidQueue() {
        assertInvalidRun("NA1", "AMERICAS", 1700, "RUNNING");
    }

    @Test
    void rejectsInvalidRunStatus() {
        assertInvalidRun("NA1", "AMERICAS", 420, "DONE");
    }

    @Test
    void deduplicatesPayloadsByKindAndExactBodyHash() {
        var hash = "a".repeat(64);
        insertPayload(UUID.randomUUID(), "MATCH_DETAIL", hash);

        assertThatThrownBy(() -> insertPayload(UUID.randomUUID(), "MATCH_DETAIL", hash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateCaptureAttemptForTheSameRunKindAndResource() {
        var runId = UUID.randomUUID();
        var payloadId = UUID.randomUUID();
        insertRun(runId, "NA1", "AMERICAS", 420, "RUNNING");
        insertPayload(payloadId, "MATCH_LIST", "b".repeat(64));
        insertCapture(UUID.randomUUID(), runId, payloadId, "MATCH_LIST", "recent-ranked", 1);

        assertThatThrownBy(() -> insertCapture(
                UUID.randomUUID(), runId, payloadId, "MATCH_LIST", "recent-ranked", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEvidenceBearingCoverageWithoutSourceCapture() {
        var runId = UUID.randomUUID();
        var payloadId = UUID.randomUUID();
        var captureId = UUID.randomUUID();
        insertRun(runId, "NA1", "AMERICAS", 420, "RUNNING");
        insertPayload(payloadId, "MATCH_DETAIL", "c".repeat(64));
        insertCapture(captureId, runId, payloadId, "MATCH_DETAIL", "NA1_123", 1);
        insertMatch("NA1_123", captureId);

        assertThatThrownBy(() -> jdbc.update("""
                        insert into league_analysis.evidence_coverage
                            (id, match_id, source_kind, signal, status, details,
                             source_capture_id, method_version)
                        values (?, 'NA1_123', 'MATCH_TIMELINE', 'participant_positions',
                                'OBSERVED', '{}'::jsonb, null, 'test-v1')
                        """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertInvalidRun(String platform, String region, int queue, String status) {
        assertThatThrownBy(() -> insertRun(UUID.randomUUID(), platform, region, queue, status))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRun(UUID id, String platform, String region, int queue, String status) {
        jdbc.update("""
                insert into league_analysis.ingestion_run
                    (id, requested_game_name, requested_tag_line, platform_route,
                     regional_route, queue_id, match_limit, status, started_at)
                values (?, 'ExamplePlayer', 'NA1', ?, ?, ?, 5, ?, ?)
                """, id, platform, region, queue, status, OffsetDateTime.now());
    }

    private void insertPayload(UUID id, String kind, String hash) {
        jdbc.update("""
                insert into league_analysis.source_payload
                    (id, source_kind, body_sha256, body_size_bytes, payload_json)
                values (?, ?, ?, 2, '{}'::jsonb)
                """, id, kind, hash);
    }

    private void insertCapture(
            UUID id,
            UUID runId,
            UUID payloadId,
            String kind,
            String resourceKey,
            int attempt) {
        jdbc.update("""
                insert into league_analysis.source_capture
                    (id, ingestion_run_id, source_payload_id, source_kind, resource_key,
                     regional_route, platform_route, captured_at, http_status,
                     response_metadata, parser_version, attempt)
                values (?, ?, ?, ?, ?, 'AMERICAS', 'NA1', ?, 200, '{}'::jsonb, 'test-v1', ?)
                """, id, runId, payloadId, kind, resourceKey, OffsetDateTime.now(), attempt);
    }

    private void insertMatch(String matchId, UUID detailCaptureId) {
        jdbc.update("""
                insert into league_analysis.riot_match
                    (match_id, game_id, queue_id, map_id, game_mode, game_type,
                     game_version, data_version, game_creation_ms, game_duration_seconds,
                     detail_source_capture_id, materialization_version, materialized_at)
                values (?, 123, 420, 11, 'CLASSIC', 'MATCHED_GAME',
                        '16.17.1', '2', 1, 1200, ?, 'test-v1', ?)
                """, matchId, detailCaptureId, OffsetDateTime.now());
    }

    private String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/" + database;
    }
}
