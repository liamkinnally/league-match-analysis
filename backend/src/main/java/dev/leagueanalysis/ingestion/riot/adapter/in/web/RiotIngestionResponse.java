package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import dev.leagueanalysis.ingestion.riot.application.IngestionRunStatus;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionResult;
import java.util.UUID;

public record RiotIngestionResponse(
        UUID runId,
        IngestionRunStatus status,
        int requested,
        int complete,
        int partial,
        int failed) {
    static RiotIngestionResponse from(RiotIngestionResult result) {
        return new RiotIngestionResponse(
                result.runId(),
                result.status(),
                result.requested(),
                result.complete(),
                result.partial(),
                result.failed());
    }
}
