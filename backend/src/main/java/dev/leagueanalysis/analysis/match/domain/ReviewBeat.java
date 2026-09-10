package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record ReviewBeat(
        String id,
        BeatKind kind,
        String title,
        String detail,
        AssertionMode assertionMode,
        Optional<String> sourceClaimId,
        List<EvidenceReference> evidenceReferences,
        Set<String> limitationCodes) {
    public ReviewBeat {
        id = TimelineEventKey.requireText(id);
        kind = Objects.requireNonNull(kind, "kind");
        title = TimelineEventKey.requireText(title);
        detail = TimelineEventKey.requireText(detail);
        assertionMode = Objects.requireNonNull(assertionMode, "assertionMode");
        sourceClaimId = Objects.requireNonNull(sourceClaimId, "sourceClaimId")
                .map(TimelineEventKey::requireText);
        evidenceReferences = List.copyOf(
                Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
        var limitations = new TreeSet<>(
                Objects.requireNonNull(limitationCodes, "limitationCodes"));
        if (limitations.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("INVALID_REVIEW_BEAT_LIMITATION");
        }
        limitationCodes = Collections.unmodifiableSortedSet(limitations);
    }

    public enum BeatKind {
        CUE,
        BEFORE_STATE,
        SEQUENCE,
        DURABLE_VALUE,
        LOCAL_GAIN,
        OPPOSING_DURABLE_VALUE,
        FOCAL_DEATH,
        LATER_OBJECTIVE,
        COST_BAND,
        GAIN_BAND,
        RECEIPT,
        INTERPRETATION,
        EVIDENCE_LIMIT,
        CONTRAST,
        COMPETING_READING,
        RECOGNITION_CUE
    }
}
