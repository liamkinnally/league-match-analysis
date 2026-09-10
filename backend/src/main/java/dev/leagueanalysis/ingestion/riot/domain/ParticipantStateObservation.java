package dev.leagueanalysis.ingestion.riot.domain;

import java.util.Objects;
import java.util.UUID;

public record ParticipantStateObservation(
        UUID id,
        String matchId,
        int participantId,
        long representedAtMs,
        Integer x,
        Integer y,
        int currentGold,
        int totalGold,
        int level,
        int xp,
        int minionsKilled,
        int jungleMinionsKilled,
        UUID sourceCaptureId,
        String methodVersion) {
    public ParticipantStateObservation {
        id = Objects.requireNonNull(id, "id");
        matchId = DomainText.require(matchId);
        sourceCaptureId = Objects.requireNonNull(sourceCaptureId, "sourceCaptureId");
        methodVersion = DomainText.require(methodVersion);
    }
}
