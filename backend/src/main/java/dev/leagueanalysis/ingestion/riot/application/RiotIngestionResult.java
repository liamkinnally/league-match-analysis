package dev.leagueanalysis.ingestion.riot.application;

import java.util.UUID;

public record RiotIngestionResult(
        UUID runId,
        IngestionRunStatus status,
        int requested,
        int complete,
        int partial,
        int failed) {}
