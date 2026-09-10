package dev.leagueanalysis.evidence.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ItemTransition(
        TimelineEventKey key,
        Integer actorParticipantId,
        Integer itemId,
        Integer beforeId,
        Integer afterId,
        List<EvidenceReference> evidenceReferences) {
    public ItemTransition {
        key = Objects.requireNonNull(key, "key");
        if (actorParticipantId != null && actorParticipantId < 0) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
        if ((itemId != null && itemId < 0)
                || (beforeId != null && beforeId < 0)
                || (afterId != null && afterId < 0)) {
            throw new IllegalArgumentException("INVALID_ITEM_ID");
        }
        evidenceReferences = Objects.requireNonNull(evidenceReferences, "evidenceReferences").stream()
                .map(reference -> Objects.requireNonNull(reference, "evidenceReference"))
                .distinct()
                .sorted(Comparator.comparingLong(EvidenceReference::representedAtMs)
                        .thenComparing(EvidenceReference::sourceCaptureId)
                        .thenComparing(EvidenceReference::sourceRecordId)
                        .thenComparing(EvidenceReference::methodVersion))
                .toList();
        if (evidenceReferences.isEmpty()) {
            throw new IllegalArgumentException("MISSING_EVIDENCE_REFERENCE");
        }
    }

    public ItemTransition(
            TimelineEventKey key,
            Integer actorParticipantId,
            Integer itemId,
            Integer beforeId,
            Integer afterId,
            EvidenceReference evidence) {
        this(key, actorParticipantId, itemId, beforeId, afterId, List.of(evidence));
    }

    public EvidenceReference evidence() {
        return evidenceReferences.getFirst();
    }
}
