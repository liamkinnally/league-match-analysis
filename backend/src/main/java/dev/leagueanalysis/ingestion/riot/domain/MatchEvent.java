package dev.leagueanalysis.ingestion.riot.domain;

import dev.leagueanalysis.evidence.domain.CanonicalEventKind;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record MatchEvent(
        UUID id,
        String matchId,
        long representedAtMs,
        long frameAtMs,
        int frameEventIndex,
        String providerEventType,
        CanonicalEventKind canonicalEventKind,
        Integer actorParticipantId,
        Integer targetParticipantId,
        Integer positionX,
        Integer positionY,
        JsonNode eventPayload,
        UUID sourceCaptureId,
        String methodVersion) {
    public MatchEvent {
        id = Objects.requireNonNull(id, "id");
        matchId = DomainText.require(matchId);
        providerEventType = DomainText.require(providerEventType);
        canonicalEventKind = Objects.requireNonNull(canonicalEventKind, "canonicalEventKind");
        eventPayload = Objects.requireNonNull(eventPayload, "eventPayload").deepCopy();
        sourceCaptureId = Objects.requireNonNull(sourceCaptureId, "sourceCaptureId");
        methodVersion = DomainText.require(methodVersion);
    }

    @Override
    public JsonNode eventPayload() {
        return eventPayload.deepCopy();
    }
}
