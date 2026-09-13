package dev.leagueanalysis.ingestion.riot.application;

/** One bounded turn. A rate-limit exception leaves this turn retryable. */
@FunctionalInterface
public interface PublicIngestionWork {
    boolean step();
}
