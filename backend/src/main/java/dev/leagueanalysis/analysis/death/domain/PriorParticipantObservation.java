package dev.leagueanalysis.analysis.death.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import java.util.Objects;

public record PriorParticipantObservation(
        int participantId,
        Integer x,
        Integer y,
        int currentGold,
        int totalGold,
        int level,
        int xp,
        int minionsKilled,
        int jungleMinionsKilled,
        EvidenceReference evidence) {
    public PriorParticipantObservation {
        if (participantId < 1) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
        evidence = Objects.requireNonNull(evidence, "evidence");
    }
}
