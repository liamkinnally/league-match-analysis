package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import java.util.Objects;

public record ParticipantReceipt(
        int participantId,
        int currentGold,
        int totalGold,
        int level,
        int creepScore,
        EvidenceReference evidence) {
    public ParticipantReceipt {
        if (participantId < 1 || participantId > 10) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
        evidence = Objects.requireNonNull(evidence, "evidence");
    }
}
