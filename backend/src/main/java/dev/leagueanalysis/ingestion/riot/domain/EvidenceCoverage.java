package dev.leagueanalysis.ingestion.riot.domain;

import dev.leagueanalysis.evidence.domain.CoverageStatus;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record EvidenceCoverage(
        UUID id,
        String matchId,
        SourceKind sourceKind,
        String signal,
        CoverageStatus status,
        Long representedStartMs,
        Long representedEndMs,
        JsonNode details,
        UUID sourceCaptureId,
        String methodVersion) {
    public EvidenceCoverage {
        id = Objects.requireNonNull(id, "id");
        matchId = DomainText.require(matchId);
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        signal = DomainText.require(signal);
        status = Objects.requireNonNull(status, "status");
        details = Objects.requireNonNull(details, "details").deepCopy();
        methodVersion = DomainText.require(methodVersion);
        if (status != CoverageStatus.UNKNOWN && status != CoverageStatus.UNAVAILABLE) {
            sourceCaptureId = Objects.requireNonNull(sourceCaptureId, "sourceCaptureId");
        }
    }

    @Override
    public JsonNode details() {
        return details.deepCopy();
    }
}
