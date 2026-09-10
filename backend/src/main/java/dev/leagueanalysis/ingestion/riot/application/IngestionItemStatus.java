package dev.leagueanalysis.ingestion.riot.application;

public enum IngestionItemStatus {
    PENDING,
    RUNNING,
    COMPLETE,
    PARTIAL,
    FAILED,
    SKIPPED
}
