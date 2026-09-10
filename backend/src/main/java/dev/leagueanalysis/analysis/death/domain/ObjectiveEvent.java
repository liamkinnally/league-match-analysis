package dev.leagueanalysis.analysis.death.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.Objects;

public record ObjectiveEvent(
        TimelineEventKey key,
        Integer actorParticipantId,
        Integer teamId,
        String objectiveDescriptor,
        Integer positionX,
        Integer positionY,
        EvidenceReference evidence) {
    public ObjectiveEvent {
        key = Objects.requireNonNull(key, "key");
        if (actorParticipantId != null && actorParticipantId < 0) {
            throw new IllegalArgumentException("INVALID_ACTOR_PARTICIPANT_ID");
        }
        if (teamId != null && teamId < 0) {
            throw new IllegalArgumentException("INVALID_TEAM_ID");
        }
        evidence = Objects.requireNonNull(evidence, "evidence");
    }
}
