package dev.leagueanalysis.privacy;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Stops restore resurrection before lookup recovery, ingestion or HTTP startup. */
@Component
@DependsOnDatabaseInitialization
public final class PrivacyRuntimeGuard {
    public static final long APPLICATION_LOCK = 734178220046L;
    public static final long WRITE_LOCK = 734178220047L;
    private static final Logger log = LoggerFactory.getLogger(PrivacyRuntimeGuard.class);
    private static final int PROBE_TIMEOUT_SECONDS = 5;
    private final Connection lease;
    private final ApplicationEventPublisher events;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("privacy-lease-monitor").factory());
    private final long acquiredAt = System.nanoTime();
    private long lastHealthyAt = acquiredAt;
    private int backendPid;
    private boolean failed;
    private boolean closed;

    public PrivacyRuntimeGuard(DataSource dataSource,
            @Value("${league-analysis.privacy.ledger-path:}") String ledgerPath,
            ApplicationEventPublisher events) throws Exception {
        this.events = events;
        lease = dataSource.getConnection();
        try {
            lease.setNetworkTimeout(Runnable::run, PROBE_TIMEOUT_SECONDS * 1000);
            try (var statement = lease.createStatement()) {
                statement.execute("set application_name='league-analysis-privacy-lease'");
                try (var result = statement.executeQuery("select pg_try_advisory_lock_shared(" + APPLICATION_LOCK + ")")) {
                    result.next();
                    if (!result.getBoolean(1)) throw new IllegalStateException("PLAYER_REMOVAL_MAINTENANCE_ACTIVE");
                }
            }
            verify(lease, ledgerPath);
            try (var statement = lease.createStatement();
                 var result = statement.executeQuery("select pg_backend_pid(), pg_postmaster_start_time()")) {
                result.next();
                backendPid = result.getInt(1);
                log.info("PRIVACY_RUNTIME_LEASE_READY backendPid={} databaseStartedAt={}", backendPid, result.getTimestamp(2).toInstant());
            }
            lastHealthyAt = System.nanoTime();
        } catch (Exception failure) { close(); throw failure; }
    }

    @PostConstruct void startMonitoring() {
        monitor.scheduleWithFixedDelay(() -> {
            try { requireHealthy(); }
            catch (IllegalStateException ignored) { /* The first failure already notified the process supervisor. */ }
        }, 10, 10, TimeUnit.SECONDS);
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
        if (!failed && !closed) {
            long startedAt = System.nanoTime();
            try (var statement = lease.createStatement()) {
                statement.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
                try (var result = statement.executeQuery("select 1")) {
                    if (!result.next() || result.getInt(1) != 1) throw new SQLException("Invalid privacy probe result", "08006");
                }
                lastHealthyAt = System.nanoTime();
                return;
            } catch (SQLException failure) {
                failed = true;
                long now = System.nanoTime();
                // Exception messages and stack traces may carry SQL, credentials or provider data.
                String state = failure.getSQLState();
                log.error("RUNTIME_LEASE_LOST_RESTART_REQUIRED backendPid={} sqlState={} vendorCode={} errorType={} causeType={} leaseAgeMs={} lastHealthyAgeMs={} probeDurationMs={}",
                        backendPid, state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN",
                        failure.getErrorCode(), failure.getClass().getSimpleName(),
                        failure.getCause() == null ? "NONE" : failure.getCause().getClass().getSimpleName(),
                        TimeUnit.NANOSECONDS.toMillis(now - acquiredAt), TimeUnit.NANOSECONDS.toMillis(now - lastHealthyAt),
                        TimeUnit.NANOSECONDS.toMillis(now - startedAt));
                // The executable's listener starts an exit thread; it must not close this context synchronously.
                events.publishEvent(new PrivacyLeaseLostEvent(this));
            }
        }
        throw new IllegalStateException("RUNTIME_LEASE_LOST_RESTART_REQUIRED");
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<PrivacyAvailabilityFilter> privacyAvailabilityFilter() {
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new PrivacyAvailabilityFilter(this));
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+20);
        return registration;
    }

    @PreDestroy public synchronized void close() {
        closed=true;
        failed=true;
        monitor.shutdownNow();
        try (var statement = lease.createStatement()) {
            statement.execute("select pg_advisory_unlock_shared(" + APPLICATION_LOCK + ")");
        } catch (java.sql.SQLException ignored) { /* A disconnected session has already released its lock. */ }
        finally { try { lease.close(); } catch (java.sql.SQLException ignored) {} }
    }
}
