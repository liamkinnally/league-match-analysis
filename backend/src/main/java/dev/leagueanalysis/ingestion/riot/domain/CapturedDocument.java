package dev.leagueanalysis.ingestion.riot.domain;

import java.util.Objects;
import java.util.UUID;

public record CapturedDocument(UUID payloadId, UUID captureId, ProviderDocument document) {
    public CapturedDocument {
        payloadId = Objects.requireNonNull(payloadId, "payloadId");
        captureId = Objects.requireNonNull(captureId, "captureId");
        document = Objects.requireNonNull(document, "document");
    }
}
