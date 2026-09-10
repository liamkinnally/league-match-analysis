package dev.leagueanalysis.analysis.death.domain;

import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ParticipantStateAtDeath(
        int participantId,
        Long observationRepresentedAtMs,
        Long observationAgeMs,
        Integer currentGold,
        Integer totalGold,
        Integer level,
        Integer xp,
        Integer minionsKilled,
        Integer jungleMinionsKilled,
        Integer x,
        Integer y,
        CoarseMapRegion coarseMapRegion,
        List<EvidenceReference> evidenceReferences,
        CoverageStatus coverageStatus,
        String ruleVersion,
        Set<String> limitationCodes) {
    public ParticipantStateAtDeath {
        if (participantId < 1) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
        if ((observationRepresentedAtMs == null) != (observationAgeMs == null)) {
            throw new IllegalArgumentException("INCOMPLETE_OBSERVATION_TIME");
        }
        if (observationRepresentedAtMs != null && (observationRepresentedAtMs < 0 || observationAgeMs < 0)) {
            throw new IllegalArgumentException("INVALID_OBSERVATION_TIME");
        }
        coarseMapRegion = Objects.requireNonNull(coarseMapRegion, "coarseMapRegion");
        evidenceReferences = Objects.requireNonNull(evidenceReferences, "evidenceReferences").stream()
                .map(reference -> Objects.requireNonNull(reference, "evidenceReference"))
                .distinct()
                .sorted(Comparator.comparingLong(EvidenceReference::representedAtMs)
                        .thenComparing(EvidenceReference::sourceCaptureId)
                        .thenComparing(EvidenceReference::sourceRecordId)
                        .thenComparing(EvidenceReference::methodVersion))
                .toList();
        coverageStatus = Objects.requireNonNull(coverageStatus, "coverageStatus");
        ruleVersion = TimelineEventKey.requireText(ruleVersion);
        limitationCodes = Set.copyOf(Objects.requireNonNull(limitationCodes, "limitationCodes"));
    }

    public ParticipantStateAtDeath(
            int participantId,
            Long observationRepresentedAtMs,
            Long observationAgeMs,
            Integer currentGold,
            Integer totalGold,
            Integer level,
            Integer xp,
            Integer minionsKilled,
            Integer jungleMinionsKilled,
            Integer x,
            Integer y,
            CoarseMapRegion coarseMapRegion,
            EvidenceReference evidence,
            CoverageStatus coverageStatus,
            String ruleVersion,
            Set<String> limitationCodes) {
        this(
                participantId,
                observationRepresentedAtMs,
                observationAgeMs,
                currentGold,
                totalGold,
                level,
                xp,
                minionsKilled,
                jungleMinionsKilled,
                x,
                y,
                coarseMapRegion,
                evidence == null ? List.of() : List.of(evidence),
                coverageStatus,
                ruleVersion,
                limitationCodes);
    }

    public EvidenceReference evidence() {
        return evidenceReferences.isEmpty() ? null : evidenceReferences.getFirst();
    }
}
