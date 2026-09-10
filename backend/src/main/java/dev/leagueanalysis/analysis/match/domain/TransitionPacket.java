package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record TransitionPacket(
        AnalyticalObjectId analyticalObjectId,
        TimeInterval interval,
        List<MatchAnchor> anchors,
        StateReceipt receipt,
        Set<MaterialityReason> materialityReasons,
        Set<FocalRelationship> focalRelationships,
        QuestionKind questionKind,
        String title,
        Optional<EvidenceClaim> interpretationClaim,
        List<EvidenceClaim> claims,
        List<EvidenceReference> evidenceReferences,
        String selectionRuleVersion,
        Set<String> limitationCodes) {
    public TransitionPacket {
        analyticalObjectId = Objects.requireNonNull(analyticalObjectId, "analyticalObjectId");
        interval = Objects.requireNonNull(interval, "interval");
        anchors = Objects.requireNonNull(anchors, "anchors").stream()
                .map(anchor -> Objects.requireNonNull(anchor, "anchor"))
                .sorted(MatchAnchor.STABLE_ORDER)
                .toList();
        if (anchors.isEmpty()) {
            throw new IllegalArgumentException("ANCHOR_REQUIRED");
        }
        receipt = Objects.requireNonNull(receipt, "receipt");
        materialityReasons = Collections.unmodifiableSet(
                Objects.requireNonNull(materialityReasons, "materialityReasons").isEmpty()
                        ? Set.of()
                        : java.util.EnumSet.copyOf(materialityReasons));
        if (materialityReasons.isEmpty()) {
            throw new IllegalArgumentException("MATERIALITY_REASON_REQUIRED");
        }
        focalRelationships = Collections.unmodifiableSet(
                Objects.requireNonNull(focalRelationships, "focalRelationships").isEmpty()
                        ? Set.of()
                        : java.util.EnumSet.copyOf(focalRelationships));
        questionKind = Objects.requireNonNull(questionKind, "questionKind");
        title = TimelineEventKey.requireText(title);
        interpretationClaim = Objects.requireNonNull(
                interpretationClaim, "interpretationClaim");
        if (interpretationClaim.isPresent()
                && interpretationClaim.orElseThrow().assertionMode() != AssertionMode.INTERPRETED) {
            throw new IllegalArgumentException("INTERPRETATION_MODE_REQUIRED");
        }
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        evidenceReferences = Objects.requireNonNull(evidenceReferences, "evidenceReferences")
                .stream()
                .map(reference -> Objects.requireNonNull(reference, "evidenceReference"))
                .distinct()
                .sorted(Comparator.comparingLong(EvidenceReference::representedAtMs)
                        .thenComparing(EvidenceReference::sourceCaptureId)
                        .thenComparing(EvidenceReference::sourceRecordId)
                        .thenComparing(EvidenceReference::methodVersion))
                .toList();
        selectionRuleVersion = TimelineEventKey.requireText(selectionRuleVersion);
        var limitations = new TreeSet<>(
                Objects.requireNonNull(limitationCodes, "limitationCodes"));
        if (limitations.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("INVALID_TRANSITION_LIMITATION");
        }
        limitationCodes = Collections.unmodifiableSortedSet(limitations);
    }

    public String transitionId() {
        return analyticalObjectId.value();
    }

    public Optional<String> interpretation() {
        return interpretationClaim.map(EvidenceClaim::statement);
    }

    public enum FocalRelationship {
        ACTOR,
        TARGET,
        ASSISTER,
        RECORDED_TEAM_GAIN
    }
}
