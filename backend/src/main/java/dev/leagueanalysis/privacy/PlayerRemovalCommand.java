package dev.leagueanalysis.privacy;

import java.io.PrintStream;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Local operator entry point. No Spring application, recovery worker, or HTTP server is started. */
public final class PlayerRemovalCommand {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INIT = "INITIALIZE-PRIVATE-LEDGER";
    private PlayerRemovalCommand() {}

    public static int run(String[] arguments, Map<String,String> environment, PrintStream out, PrintStream err) {
        try {
            if (arguments.length == 0 || Arrays.asList(arguments).contains("--help")) {
                out.println("""
                        Private player removal (defaults to dry run):
                          initialize --ledger PATH [--execute --confirm INITIALIZE-PRIVATE-LEDGER]
                          remove --ledger PATH --puuid-file FILE --mode exclude|erase-only
                          remove --ledger PATH --match-id NA1_123 --participant 1 --mode exclude|erase-only
                          reconcile --ledger PATH
                          prepare-restore --ledger PATH
                          check --ledger PATH
                        Add --execute --confirm <dry-run confirmation> to remove/reconcile only after review.
                        Execution requires all backend/database clients stopped. Credentials: server-side
                        SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD.
                        Never publish the ledger. Affected whole matches disappear for ALL participants.
                        """);
                return 0;
            }
            var options = Options.parse(arguments, environment);
            String url = required(environment, "SPRING_DATASOURCE_URL");
            if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("POSTGRESQL_REQUIRED");
            try (var connection = DriverManager.getConnection(url, required(environment,"SPRING_DATASOURCE_USERNAME"),
                    required(environment,"SPRING_DATASOURCE_PASSWORD"))) {
                var source = new SingleConnectionDataSource(connection, true);
                var jdbc = new JdbcTemplate(source);
                if (options.execute) maintenance(connection);
                if (options.command.equals("initialize")) {
                    initialize(options, environment, jdbc, out);
                    return 0;
                }
                var ledger = RemovalLedger.read(options.ledger);
                if(options.command.equals("prepare-restore")) {
                    String confirmation=PrivacyHash.of("prepare-restore\n"+ledger.digest());
                    if(!options.execute) {
                        out.println(JSON.writeValueAsString(Map.of("status","DRY_RUN","confirmation",confirmation,
                                "action","Apply reviewed schema migrations only; keep ledger and serving blocked until reconciliation")));
                        return 0;
                    }
                    if(!confirmation.equals(options.confirm)) throw new IllegalArgumentException("CONFIRMATION_MISMATCH_RUN_DRY_RUN_AGAIN");
                    Flyway.configure().dataSource(url,required(environment,"SPRING_DATASOURCE_USERNAME"),
                            required(environment,"SPRING_DATASOURCE_PASSWORD")).load().migrate();
                    out.println(JSON.writeValueAsString(Map.of("status","SCHEMA_PREPARED_RECONCILIATION_REQUIRED")));
                    return 0;
                }
                if (options.command.equals("check")) {
                    PrivacyRuntimeGuard.verify(connection, options.ledger.toString());
                    out.println(JSON.writeValueAsString(Map.of("status", "LEDGER_SYNCHRONIZED", "operations", ledger.entries().size())));
                    return 0;
                }
                if (options.command.equals("remove")) PrivacyRuntimeGuard.verify(connection, options.ledger.toString());
                var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
                transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                transactions.setReadOnly(!options.execute);
                transactions.executeWithoutResult(status -> {
                    try {
                        if (options.execute) {
                            jdbc.execute("set local lock_timeout='5s'");
                            jdbc.execute("set local league_analysis.removal_operator='on'");
                            // Lock before publishing ledger intent: a late direct SQL writer cannot
                            // deadlock the operator after the durable intent has been written.
                            jdbc.execute("""
                                    lock table league_analysis.ingestion_run, league_analysis.source_payload,
                                    league_analysis.source_capture, league_analysis.ingestion_item,
                                    league_analysis.riot_identity, league_analysis.riot_match, league_analysis.riot_team,
                                    league_analysis.riot_participant, league_analysis.participant_state_observation,
                                    league_analysis.match_event, league_analysis.evidence_coverage,
                                    league_analysis.player_profile_current, league_analysis.rank_refresh_state,
                                    league_analysis.rank_observation in access exclusive mode
                                    """);
                        }
                        var planner = new RemovalPlanner(jdbc, JSON);
                        var plan = options.command.equals("remove") ? planner.plan(subject(options,jdbc)) : replayPlan(planner,ledger);
                        String confirmation = PrivacyHash.of(options.command + "\n" + options.mode + "\n" + ledger.digest() + "\n" + plan.fingerprint());
                        if (!options.execute) {
                            out.println(JSON.writeValueAsString(Map.of("status","DRY_RUN", "confirmation",confirmation,
                                    "command",options.command,"mode",options.mode,"matchesRemovedForAllParticipants",plan.matchIds(),
                                    "affectedRecords",plan.affectedRecords(),"ledgerOperations",ledger.entries().size())));
                            return;
                        }
                        if (!confirmation.equals(options.confirm)) throw new IllegalArgumentException("CONFIRMATION_MISMATCH_RUN_DRY_RUN_AGAIN");
                        var next = ledger;
                        RemovalLedger.Entry entry = null;
                        if (options.command.equals("remove")) {
                            entry = new RemovalLedger.Entry(UUID.randomUUID(), Instant.now().toString(), options.mode,
                                    plan.puuidHashes(),plan.aliasHashes(),plan.matchHashes());
                            next = ledger.append(entry);
                            next.write(options.ledger); // fsync intent BEFORE committing any deletion
                        }
                        planner.apply(plan);
                        for (var operation : next.entries()) {
                            if (operation.mode().equals("exclude")) {
                                exclude(jdbc,"puuid",operation.puuidHashes()); exclude(jdbc,"riot_id",operation.aliasHashes());
                                exclude(jdbc,"match",operation.matchHashes());
                            }
                            jdbc.update("""
                                    insert into league_analysis.privacy_completion(operation_id, completed_at, mode, affected_counts)
                                    values (?, now(), ?, cast(? as jsonb)) on conflict(operation_id) do nothing
                                    """,operation.operationId(),operation.mode(),JSON.writeValueAsString(
                                            entry != null && entry.operationId().equals(operation.operationId()) ? plan.affectedRecords() : Map.of("reconciled",1)));
                        }
                        jdbc.update("update league_analysis.privacy_control set ledger_sha256=? where singleton",next.digest());
                        // Verify zero surviving subject/match records in the same transaction.
                        var after=planner.planHashes(plan.puuidHashes(),plan.aliasHashes(),plan.matchHashes());
                        if (after.affectedRecords().values().stream().anyMatch(count -> count != 0))
                            throw new IllegalStateException("POST_REMOVAL_RECORDS_REMAIN");
                    } catch (RuntimeException error) { throw error; }
                    catch (Exception error) { throw new IllegalStateException("REMOVAL_INCOMPLETE_RECONCILE_BEFORE_RESTART",error); }
                });
                if (options.execute) {
                    PrivacyRuntimeGuard.verify(connection,options.ledger.toString());
                    out.println(JSON.writeValueAsString(Map.of("status","COMPLETE","ledgerOperations",RemovalLedger.read(options.ledger).entries().size())));
                }
                return 0;
            }
        } catch (Exception error) {
            String message=error.getMessage();
            err.println(message != null && message.matches("[A-Z0-9_]+") ? message : "REMOVAL_REFUSED_CHECK_CONFIGURATION_AND_LEDGER");
            return 2;
        }
    }

    private static void maintenance(Connection connection) throws SQLException {
        try(var statement=connection.createStatement()) {
            for(long key : List.of(PrivacyRuntimeGuard.APPLICATION_LOCK,PrivacyRuntimeGuard.WRITE_LOCK)) {
                try(var result=statement.executeQuery("select pg_try_advisory_lock("+key+")")) {
                    result.next(); if (!result.getBoolean(1)) throw new IllegalStateException("BACKEND_MUST_BE_STOPPED");
                }
            }
            try(var result=statement.executeQuery("""
                    select count(*) from pg_stat_activity where datname=current_database()
                    and pid<>pg_backend_pid() and backend_type='client backend'
                    """)) {
                result.next(); if(result.getLong(1)!=0) throw new IllegalStateException("OTHER_DATABASE_CLIENTS_ACTIVE_STOP_BACKEND_AND_BACKUP_JOB");
            }
        }
    }

    private static void initialize(Options options, Map<String,String> env, JdbcTemplate jdbc, PrintStream out) throws Exception {
        if (!options.execute) {
            out.println(JSON.writeValueAsString(Map.of("status","DRY_RUN","confirmation",INIT,"action","Initialize private ledger and schema; no player records removed")));
            return;
        }
        if (!INIT.equals(options.confirm)) throw new IllegalArgumentException("INITIALIZATION_CONFIRMATION_REQUIRED");
        // Only explicitly authorized initialization migrates. Normal dry runs never do.
        Flyway.configure().dataSource(required(env,"SPRING_DATASOURCE_URL"),required(env,"SPRING_DATASOURCE_USERNAME"),
                required(env,"SPRING_DATASOURCE_PASSWORD")).load().migrate();
        var digest=jdbc.queryForObject("select ledger_sha256 from league_analysis.privacy_control where singleton",String.class);
        if (digest != null) throw new IllegalStateException("LEDGER_ALREADY_INITIALIZED");
        var ledger=Files.exists(options.ledger) ? RemovalLedger.read(options.ledger) : RemovalLedger.empty();
        if (!ledger.entries().isEmpty()) throw new IllegalStateException("EXISTING_REMOVALS_REQUIRE_RECONCILIATION");
        ledger.write(options.ledger);
        jdbc.update("update league_analysis.privacy_control set ledger_sha256=? where singleton",ledger.digest());
        out.println(JSON.writeValueAsString(Map.of("status","INITIALIZED")));
    }

    private static RemovalPlan replayPlan(RemovalPlanner planner, RemovalLedger ledger) {
        var puuids=new TreeSet<String>(); var aliases=new TreeSet<String>(); var matches=new TreeSet<String>();
        for(var entry:ledger.entries()) {
            puuids.addAll(entry.puuidHashes()); aliases.addAll(entry.aliasHashes()); matches.addAll(entry.matchHashes());
        }
        return planner.planHashes(puuids,aliases,matches);
    }
    private static void exclude(JdbcTemplate jdbc,String kind,Set<String> hashes) {
        for(String hash:hashes) jdbc.update("insert into league_analysis.privacy_exclusion values (?,?) on conflict do nothing",kind,hash);
    }
    private static String subject(Options options,JdbcTemplate jdbc) throws Exception {
        if (options.puuidFile!=null) {
            if(!Files.isRegularFile(options.puuidFile,LinkOption.NOFOLLOW_LINKS) || Files.size(options.puuidFile)>512)
                throw new IllegalArgumentException("INVALID_PUUID_FILE");
            String puuid=Files.readString(options.puuidFile).strip();
            if(!puuid.matches("[A-Za-z0-9_-]{3,256}")) throw new IllegalArgumentException("UNSUPPORTED_PUUID");
            return puuid;
        }
        var values=jdbc.queryForList("select puuid from league_analysis.riot_participant where match_id=? and participant_id=?",String.class,options.matchId,options.participant);
        if(values.size()!=1) throw new IllegalArgumentException("UNVERIFIED_MATCH_PARTICIPANT");
        return values.getFirst();
    }
    private static String required(Map<String,String> environment,String key) {
        String value=environment.get(key); if(value==null || value.isBlank()) throw new IllegalArgumentException("MISSING_"+key);
        return value;
    }

    private record Options(String command,Path ledger,Path puuidFile,String matchId,int participant,String mode,boolean execute,String confirm) {
        static Options parse(String[] args,Map<String,String> env) {
            String command=args[0];
            if(!Set.of("initialize","remove","reconcile","check","prepare-restore").contains(command)) throw new IllegalArgumentException("UNSUPPORTED_COMMAND");
            var values=new HashMap<String,String>(); boolean execute=false;
            for(int index=1;index<args.length;index++) {
                String name=args[index];
                if(name.equals("--execute")) { if(execute) throw new IllegalArgumentException("DUPLICATE_OPTION"); execute=true; continue; }
                if(!Set.of("--ledger","--puuid-file","--match-id","--participant","--mode","--confirm").contains(name)
                        || index+1>=args.length || values.putIfAbsent(name,args[++index])!=null)
                    throw new IllegalArgumentException("UNSUPPORTED_OR_DUPLICATE_OPTION");
            }
            String path=values.getOrDefault("--ledger",env.get("PLAYER_REMOVAL_LEDGER_FILE"));
            if(path==null || !Path.of(path).isAbsolute()) throw new IllegalArgumentException("ABSOLUTE_PRIVATE_LEDGER_PATH_REQUIRED");
            String mode=values.getOrDefault("--mode",""); String match=values.get("--match-id"); String file=values.get("--puuid-file");
            int participant=values.containsKey("--participant") ? Integer.parseInt(values.get("--participant")) : 0;
            if(command.equals("remove")) {
                if(!Set.of("exclude","erase-only").contains(mode)) throw new IllegalArgumentException("EXPLICIT_REMOVAL_MODE_REQUIRED");
                if((file!=null)==(match!=null) || file!=null && participant!=0) throw new IllegalArgumentException("EXACTLY_ONE_VERIFIED_IDENTIFIER_REQUIRED");
                if(match!=null && (!match.matches("(NA1|EUW1|EUN1|KR)_[0-9]+") || participant<1 || participant>10))
                    throw new IllegalArgumentException("UNSUPPORTED_MATCH_PARTICIPANT");
            } else if(file!=null || match!=null || participant!=0 || !mode.isEmpty()) throw new IllegalArgumentException("UNSUPPORTED_OPTION_FOR_COMMAND");
            String confirm=values.get("--confirm");
            if(execute && (confirm==null || command.equals("check")) || !execute && confirm!=null)
                throw new IllegalArgumentException("EXECUTION_REQUIRES_EXPLICIT_CONFIRMATION");
            return new Options(command,Path.of(path),file==null?null:Path.of(file),match,participant,mode,execute,confirm);
        }
    }
}
