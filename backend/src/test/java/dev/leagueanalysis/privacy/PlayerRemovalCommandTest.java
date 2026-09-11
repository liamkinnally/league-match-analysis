package dev.leagueanalysis.privacy;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class PlayerRemovalCommandTest {
    static PostgreSQLContainer postgres;
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    @TempDir Path directory;
    Path ledger;
    Path subject;
    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer("postgres:17.11-bookworm"); postgres.start();
        source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).load().migrate();
    }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }
    @BeforeEach void prepare() throws Exception {
        jdbc.execute("truncate league_analysis.ingestion_run, league_analysis.source_payload cascade");
        jdbc.execute("truncate league_analysis.privacy_completion, league_analysis.privacy_exclusion");
        jdbc.execute("update league_analysis.privacy_control set ledger_sha256=null");
        ledger=directory.resolve("private/ledger.json"); subject=directory.resolve("subject.txt");
        Files.writeString(subject,"synthetic-private-puuid");
        assertThat(run("initialize", "--execute", "--confirm", "INITIALIZE-PRIVATE-LEDGER").code).isZero();
        seed();
    }
    void seed() {
        var run=UUID.randomUUID(); var payload=UUID.randomUUID(); var capture=UUID.randomUUID();
        jdbc.update("""
                insert into league_analysis.ingestion_run(id,requested_game_name,requested_tag_line,platform_route,regional_route,
                queue_id,match_limit,status,started_at,resolved_puuid,public_request)
                values (?,'PrivateName','NA1','NA1','AMERICAS',420,5,'RUNNING',now(),'synthetic-private-puuid',true)
                """,run);
        String body="{\"puuid\":\"synthetic-private-puuid\",\"gameName\":\"PrivateName\",\"tagLine\":\"NA1\"}";
        jdbc.update("insert into league_analysis.source_payload values (?, 'ACCOUNT', ?, ?, cast(? as jsonb))",payload,PrivacyHash.of(body),body.length(),body);
        jdbc.update("""
                insert into league_analysis.source_capture values (?, ?, ?, 'ACCOUNT', 'PrivateName#NA1', 'AMERICAS', 'NA1', now(), 200, null, '{}', 'test', 1)
                """,capture,run,payload);
        jdbc.update("insert into league_analysis.riot_identity values ('synthetic-private-puuid','PrivateName','NA1','NA1',now(),now(),?)",capture);
    }
    @Test void dryRunUnknownAndStaleConfirmationNeverDelete() throws Exception {
        var planned=run("remove","--puuid-file",subject.toString(),"--mode","exclude");
        assertThat(planned.code).isZero(); assertThat(planned.out).contains("DRY_RUN").doesNotContain("synthetic-private-puuid","PrivateName");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from league_analysis.ingestion_run",String.class)).isEqualTo("RUNNING");
        assertThat(RemovalLedger.read(ledger).entries()).isEmpty();
        assertThat(run("remove","--puuid-file",subject.toString(),"--mode","exclude","--execute","--confirm","wrong").code).isNotZero();
        Files.writeString(subject,"unknown");
        assertThat(run("remove","--puuid-file",subject.toString(),"--mode","exclude").code).isNotZero();
        assertThat(count()).isEqualTo(1);
    }
    @Test void backendLeasePreventsExecutionAndRestartClearsCacheBoundary() throws Exception {
        var plan=run("remove","--puuid-file",subject.toString(),"--mode","exclude");
        try(var guard=new GuardClose(source,ledger.toString())) {
            var blocked=execute(plan);
            assertThat(blocked.code).isNotZero(); assertThat(blocked.err).contains("BACKEND_MUST_BE_STOPPED");
            assertThat(count()).isEqualTo(1);
        }
        assertThat(execute(plan).code).isZero(); assertThat(count()).isZero();
        try(var connection=source.getConnection()) { PrivacyRuntimeGuard.verify(connection,ledger.toString()); }
        assertThatThrownBy(this::seed).hasMessageContaining("PRIVACY_EXCLUDED");
    }
    @Test void oldBackupAndInterruptedIntentMustReconcileBeforeServing() throws Exception {
        var backup=postgres.execInContainer("pg_dump","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"-Fc","-f","/tmp/before.dump");
        assertThat(backup.getExitCode()).isZero();
        assertThat(execute(run("remove","--puuid-file",subject.toString(),"--mode","exclude")).code).isZero();
        var restore=postgres.execInContainer("pg_restore","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"--clean","--if-exists","--no-owner","--exit-on-error","/tmp/before.dump");
        assertThat(restore.getExitCode()).describedAs(restore.getStderr()).isZero();
        assertThat(count()).isEqualTo(1);
        try(var connection=source.getConnection()) {
            assertThatThrownBy(() -> PrivacyRuntimeGuard.verify(connection,ledger.toString())).hasMessageContaining("RECONCILIATION_REQUIRED");
        }
        var replay=run("reconcile");
        assertThat(replay.code).isZero(); assertThat(count()).isEqualTo(1);
        assertThat(run("reconcile","--execute","--confirm",fingerprint(replay)).code).isZero();
        assertThat(count()).isZero(); assertThat(run("check").code).isZero();
    }
    @Test void eraseOnlyAllowsFutureLookupButKeepsRestoreLedger() throws Exception {
        var plan=run("remove","--puuid-file",subject.toString(),"--mode","erase-only");
        assertThat(run("remove","--puuid-file",subject.toString(),"--mode","erase-only","--execute","--confirm",fingerprint(plan)).code).isZero();
        seed(); assertThat(count()).isEqualTo(1);
        assertThat(run("check").code).isZero();
    }
    @Test void durableIntentWithoutDatabaseCommitRequiresExplicitReplay() throws Exception {
        var before=RemovalLedger.read(ledger);
        before.append(new RemovalLedger.Entry(UUID.randomUUID(),"2026-09-11T00:00:00Z","exclude",
                Set.of(PrivacyHash.of("synthetic-private-puuid")),Set.of(PrivacyHash.riotId("PrivateName","NA1")),Set.of())).write(ledger);
        assertThat(run("check").code).isNotZero();
        assertThat(count()).isEqualTo(1);
        var replay=run("reconcile");
        assertThat(run("reconcile","--execute","--confirm",fingerprint(replay)).code).isZero();
        assertThat(count()).isZero();
        assertThat(run("check").code).isZero();
        jdbc.execute("delete from league_analysis.privacy_exclusion");
        assertThat(run("check").code).isNotZero();
    }
    @Test void unsupportedOrAmbiguousInputAndOtherClientsStopExecution() throws Exception {
        assertThat(run("remove","--riot-id","PrivateName#NA1","--mode","exclude").code).isNotZero();
        assertThat(run("remove","--match-id","EUW1_1","--participant","1","--mode","exclude").code).isNotZero();
        assertThat(run("remove","--puuid-file",subject.toString(),"--match-id","NA1_1","--participant","1","--mode","exclude").code).isNotZero();
        var plan=run("remove","--puuid-file",subject.toString(),"--mode","exclude");
        try(var other=source.getConnection()) {
            assertThat(execute(plan).err).contains("OTHER_DATABASE_CLIENTS_ACTIVE");
            assertThat(count()).isEqualTo(1);
        }
    }
    @Test void terminatedRuntimeLeaseBlocksRequestsUntilProcessRestart() throws Exception {
        var guard=new PrivacyRuntimeGuard(source,ledger.toString());
        try {
            jdbc.execute("select pg_terminate_backend(pid) from pg_stat_activity where application_name='league-analysis-privacy-lease'");
            assertThatThrownBy(guard::requireHealthy).hasMessageContaining("RUNTIME_LEASE_LOST");
            var response=new org.springframework.mock.web.MockHttpServletResponse();
            new PrivacyAvailabilityFilter(guard).doFilter(new org.springframework.mock.web.MockHttpServletRequest(),response,
                    (request,reply) -> { throw new AssertionError("Cached/private response must not be served"); });
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThatThrownBy(guard::requireHealthy).hasMessageContaining("RUNTIME_LEASE_LOST");
        } finally { guard.close(); }
    }
    @Test void prepareOlderRestoreMigratesWithoutResettingLedgerOrServing() throws Exception {
        String database="legacy_restore_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create database "+database);
        String url=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+database);
        try {
            Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).target("5").load().migrate();
            var old=new JdbcTemplate(new DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));
            old.update("""
                    insert into league_analysis.ingestion_run(id,requested_game_name,requested_tag_line,platform_route,regional_route,
                    queue_id,match_limit,status,started_at,resolved_puuid,public_request)
                    values (?,'PrivateName','NA1','NA1','AMERICAS',420,5,'RUNNING',now(),'synthetic-private-puuid',true)
                    """,UUID.randomUUID());
            RemovalLedger.read(ledger).append(new RemovalLedger.Entry(UUID.randomUUID(),"2026-09-11T00:00:00Z","exclude",
                    Set.of(PrivacyHash.of("synthetic-private-puuid")),Set.of(),Set.of())).write(ledger);
            String previous=Files.readString(ledger);
            var planned=runAt(url,"prepare-restore");
            assertThat(planned.code).isZero();
            assertThat(old.queryForObject("select to_regclass('league_analysis.privacy_control')::text",String.class)).isNull();
            assertThat(runAt(url,"prepare-restore","--execute","--confirm",fingerprint(planned)).code).isZero();
            assertThat(Files.readString(ledger)).isEqualTo(previous);
            assertThat(runAt(url,"check").code).isNotZero();
            assertThat(old.queryForObject("select count(*) from league_analysis.ingestion_run",Integer.class)).isEqualTo(1);
            var replay=runAt(url,"reconcile");
            assertThat(runAt(url,"reconcile","--execute","--confirm",fingerprint(replay)).code).isZero();
            assertThat(old.queryForObject("select count(*) from league_analysis.ingestion_run",Integer.class)).isZero();
            assertThat(runAt(url,"check").code).isZero();
        } finally { jdbc.execute("drop database "+database); }
    }
    int count() { return jdbc.queryForObject("select count(*) from league_analysis.riot_identity",Integer.class); }
    Result execute(Result plan) { return run("remove","--puuid-file",subject.toString(),"--mode","exclude","--execute","--confirm",fingerprint(plan)); }
    String fingerprint(Result plan) { return new ObjectMapper().readTree(plan.out).path("confirmation").asText(); }
    Result run(String...args) {
        return runAt(postgres.getJdbcUrl(),args);
    }
    Result runAt(String url,String...args) {
        var all=new ArrayList<>(List.of(args)); all.addAll(List.of("--ledger",ledger.toString()));
        var out=new ByteArrayOutputStream(); var err=new ByteArrayOutputStream();
        int result=PlayerRemovalCommand.run(all.toArray(String[]::new), Map.of("SPRING_DATASOURCE_URL",url,
                "SPRING_DATASOURCE_USERNAME",postgres.getUsername(),"SPRING_DATASOURCE_PASSWORD",postgres.getPassword()),new PrintStream(out),new PrintStream(err));
        return new Result(result,out.toString(),err.toString());
    }
    record Result(int code,String out,String err) {}
    static class GuardClose implements AutoCloseable {
        PrivacyRuntimeGuard guard;
        GuardClose(DriverManagerDataSource source,String path) throws Exception { guard=new PrivacyRuntimeGuard(source,path); }
        public void close() throws Exception { guard.close(); }
    }
}
