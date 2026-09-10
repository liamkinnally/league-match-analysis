package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import java.util.Comparator;
import java.util.Objects;

public record ParticipantObservation(
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
        EvidenceReference evidence) {
    public static final Comparator<ParticipantObservation> STABLE_ORDER = Comparator
            .comparingLong(ParticipantObservation::representedAtMs)
            .thenComparingInt(ParticipantObservation::participantId)
            .thenComparing(observation -> observation.evidence().sourceCaptureId())
            .thenComparing(observation -> observation.evidence().sourceRecordId());

    public ParticipantObservation {
        if (participantId < 1 || participantId > 10) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
        if (representedAtMs < 0) {
            throw new IllegalArgumentException("NEGATIVE_REPRESENTED_TIME");
        }
        evidence = Objects.requireNonNull(evidence, "evidence");
    }
}
