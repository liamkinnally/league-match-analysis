package dev.leagueanalysis.ingestion.riot.domain;

import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

public record ProviderDocument(
        SourceKind kind,
        String resourceKey,
        Instant capturedAt,
        int httpStatus,
        String regionalRoute,
        String platformRoute,
        String providerGameVersion,
        String bodySha256,
        int bodySizeBytes,
        JsonNode payload,
        JsonNode responseMetadata,
        String parserVersion,
        int attempt) {
    public ProviderDocument {
        kind = Objects.requireNonNull(kind, "kind");
        resourceKey = DomainText.require(resourceKey);
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        regionalRoute = DomainText.require(regionalRoute);
        bodySha256 = DomainText.require(bodySha256);
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
        responseMetadata = Objects.requireNonNull(responseMetadata, "responseMetadata").deepCopy();
        parserVersion = DomainText.require(parserVersion);
        if (httpStatus < 100 || httpStatus > 599 || bodySizeBytes < 0 || attempt < 1) {
            throw new IllegalArgumentException("INVALID_PROVIDER_DOCUMENT");
        }
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }

    @Override
    public JsonNode responseMetadata() {
        return responseMetadata.deepCopy();
    }
}
