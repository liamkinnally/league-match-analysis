package dev.leagueanalysis.privacy;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

/** Stops restore resurrection before lookup recovery, ingestion or HTTP startup. */
@Component
@DependsOnDatabaseInitialization
public final class PrivacyRuntimeGuard {
    public static final long APPLICATION_LOCK = 734178220046L;
    public static final long WRITE_LOCK = 734178220047L;
    private final Connection lease;
    private boolean failed;

    public PrivacyRuntimeGuard(DataSource dataSource,
            @Value("${league-analysis.privacy.ledger-path:}") String ledgerPath) throws Exception {
        lease = dataSource.getConnection();
        try {
            try (var statement = lease.createStatement()) {
                statement.execute("set application_name='league-analysis-privacy-lease'");
                try (var result = statement.executeQuery("select pg_try_advisory_lock_shared(" + APPLICATION_LOCK + ")")) {
                    result.next();
                    if (!result.getBoolean(1)) throw new IllegalStateException("PLAYER_REMOVAL_MAINTENANCE_ACTIVE");
                }
            }
            verify(lease, ledgerPath);
        } catch (Exception failure) { close(); throw failure; }
    }

    public static void verify(Connection connection, String ledgerPath) throws Exception {
        String checkpoint;
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("select ledger_sha256 from league_analysis.privacy_control where singleton")) {
            if (!result.next()) throw new IllegalStateException("MISSING_REMOVAL_CHECKPOINT");
            checkpoint = result.getString(1);
        }
        if (ledgerPath == null || ledgerPath.isBlank()) {
            if (checkpoint != null) throw new IllegalStateException("PRIVATE_LEDGER_REQUIRED");
            return; // Uninitialized local database only. Railway always supplies a path.
        }
        var ledger = RemovalLedger.read(Path.of(ledgerPath));
        if (!ledger.digest().equals(checkpoint)) throw new IllegalStateException("REMOVAL_LEDGER_RECONCILIATION_REQUIRED");
        for (var entry : ledger.entries()) {
            try (var statement = connection.prepareStatement("select count(*) from league_analysis.privacy_completion where operation_id=? and mode=?")) {
                statement.setObject(1,entry.operationId()); statement.setString(2,entry.mode());
                try(var result=statement.executeQuery()) {
                    result.next(); if(result.getInt(1)!=1) throw new IllegalStateException("REMOVAL_RECEIPT_MISSING");
                }
            }
            if(entry.mode().equals("exclude")) {
                for(var item : java.util.Map.of("puuid",entry.puuidHashes(),"riot_id",entry.aliasHashes(),"match",entry.matchHashes()).entrySet()) {
                    for(String hash:item.getValue()) {
                        try(var statement=connection.prepareStatement("select count(*) from league_analysis.privacy_exclusion where kind=? and subject_hash=?")) {
                            statement.setString(1,item.getKey()); statement.setString(2,hash);
                            try(var result=statement.executeQuery()) {
                                result.next(); if(result.getInt(1)!=1) throw new IllegalStateException("REMOVAL_EXCLUSION_MISSING");
                            }
                        }
                    }
                }
            }
        }
    }

    /** A lost session is never silently replaced: old workers/caches require a process restart. */
    public synchronized void requireHealthy() {
        try {
            if (!failed && lease.isValid(1)) return;
        } catch (java.sql.SQLException ignored) { /* The response below deliberately contains no connection details. */ }
        failed=true;
        throw new IllegalStateException("RUNTIME_LEASE_LOST_RESTART_REQUIRED");
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<PrivacyAvailabilityFilter> privacyAvailabilityFilter() {
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new PrivacyAvailabilityFilter(this));
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+20);
        return registration;
    }

    @PreDestroy public synchronized void close() {
        failed=true;
        try (var statement = lease.createStatement()) {
            statement.execute("select pg_advisory_unlock_shared(" + APPLICATION_LOCK + ")");
        } catch (java.sql.SQLException ignored) { /* A disconnected session has already released its lock. */ }
        finally { try { lease.close(); } catch (java.sql.SQLException ignored) {} }
    }
}
