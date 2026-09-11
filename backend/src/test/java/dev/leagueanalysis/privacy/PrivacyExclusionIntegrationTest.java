package dev.leagueanalysis.privacy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class PrivacyExclusionIntegrationTest {
    static PostgreSQLContainer postgres;
    static JdbcTemplate jdbc;

    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer("postgres:17.11-bookworm");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }
    @AfterEach void clean() {
        jdbc.execute("truncate league_analysis.ingestion_run, league_analysis.source_payload cascade");
        jdbc.execute("delete from league_analysis.privacy_exclusion");
    }
    @Test void excludedPlayerCannotPersistRawPayloadEvenBeforeNormalization() {
        jdbc.update("insert into league_analysis.privacy_exclusion values ('puuid', ?)", PrivacyHash.of("removed-player"));
        assertThatThrownBy(() -> payload("{\"metadata\":{\"participants\":[\"other-player\",\"removed-player\"]}}"))
                .hasMessageContaining("PRIVACY_EXCLUDED");
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_payload", Integer.class)).isZero();
        payload("{\"metadata\":{\"participants\":[\"unrelated-player\"]}}");
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_payload", Integer.class)).isEqualTo(1);
    }
    @Test void excludedMatchCannotPersistInRawMatchList() {
        jdbc.update("insert into league_analysis.privacy_exclusion values ('match', ?)", PrivacyHash.of("NA1_123"));
        assertThatThrownBy(() -> payload("[\"NA1_123\",\"NA1_456\"]")).hasMessageContaining("PRIVACY_EXCLUDED");
    }
    @Test void excludedAliasCannotCreateInterruptedLookupRecord() {
        jdbc.update("insert into league_analysis.privacy_exclusion values ('riot_id', ?)", PrivacyHash.riotId("Example", "NA1"));
        assertThatThrownBy(() -> jdbc.update("""
                insert into league_analysis.ingestion_run
                  (id, requested_game_name, requested_tag_line, platform_route, regional_route, queue_id, match_limit, status, started_at)
                values (?, 'EXAMPLE', 'na1', 'NA1', 'AMERICAS', 420, 5, 'RUNNING', now())
                """, UUID.randomUUID())).hasMessageContaining("PRIVACY_EXCLUDED");
    }
    @Test void writeAlreadyWaitingDuringRemovalRechecksNewExclusionBeforePersisting() throws Exception {
        var source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        try(var removal=source.getConnection(); var workers=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            removal.setAutoCommit(false);
            try(var statement=removal.createStatement()) {
                statement.execute("select pg_advisory_xact_lock("+PrivacyRuntimeGuard.WRITE_LOCK+")");
                statement.execute("insert into league_analysis.privacy_exclusion values ('puuid', league_analysis.privacy_hash('late-player'))");
            }
            var waiting=workers.submit(() -> {
                try { payload("{\"puuid\":\"late-player\"}"); return "unexpected persistence"; }
                catch(Exception error) { return error.getMessage(); }
            });
            long until=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
            while(System.nanoTime()<until && jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and not granted",Integer.class)==0)
                Thread.sleep(10);
            assertThat(waiting.isDone()).isFalse();
            removal.commit();
            assertThat(waiting.get(5,java.util.concurrent.TimeUnit.SECONDS)).contains("PRIVACY_EXCLUDED");
            assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_payload",Integer.class)).isZero();
        }
    }
    private void payload(String body) {
        jdbc.update("""
                insert into league_analysis.source_payload(id, source_kind, body_sha256, body_size_bytes, payload_json)
                values (?, 'MATCH_DETAIL', ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), PrivacyHash.of(body), body.length(), body);
    }
}
