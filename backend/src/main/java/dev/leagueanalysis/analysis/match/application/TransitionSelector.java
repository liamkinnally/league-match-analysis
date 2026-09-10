package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.AnalyticalObjectId;
import dev.leagueanalysis.analysis.match.domain.AnchorKind;
import dev.leagueanalysis.analysis.match.domain.EvidenceClaim;
import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.MaterialityReason;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.analysis.match.domain.StateReceipt;
import dev.leagueanalysis.analysis.match.domain.TimeInterval;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket.FocalRelationship;
import dev.leagueanalysis.evidence.domain.ItemTransition;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class TransitionSelector {
    private final StateReceiptProjector receiptProjector;
    private final TransitionNarrativePolicy narrativePolicy;

    public TransitionSelector() {
        this(new StateReceiptProjector(), new TransitionNarrativePolicy());
    }

    public TransitionSelector(
            StateReceiptProjector receiptProjector,
            TransitionNarrativePolicy narrativePolicy) {
        this.receiptProjector = Objects.requireNonNull(receiptProjector, "receiptProjector");
        this.narrativePolicy = Objects.requireNonNull(narrativePolicy, "narrativePolicy");
    }

    public List<TransitionPacket> select(
            MatchEvidenceSnapshot snapshot,
            int focalParticipantId,
            TransitionPolicy policy) {
        var eligible = eligible(snapshot, focalParticipantId, policy);
        return selectDiverse(
                eligible, snapshot.header().durationMs(), policy.maximumSelected());
    }

    List<TransitionPacket> eligible(
            MatchEvidenceSnapshot snapshot,
            int focalParticipantId,
            TransitionPolicy policy) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(policy, "policy");
        var focalTeamId = snapshot.participants().stream()
                .filter(participant -> participant.participantId() == focalParticipantId)
                .map(participant -> participant.teamId())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "FOCAL_PARTICIPANT_NOT_FOUND"));
        var boundedCandidates = clusterAnchors(
                        snapshot.anchors(), policy.candidateWindowMs()).stream()
                .map(anchors -> projectedCandidate(
                        snapshot, focalParticipantId, anchors, policy))
                .flatMap(Optional::stream)
                .toList();
        return mergeOverlapping(
                        snapshot, focalParticipantId, boundedCandidates).stream()
                .map(candidate -> eligiblePacket(
                        snapshot, focalParticipantId, focalTeamId, candidate, policy))
                .flatMap(Optional::stream)
                .sorted(java.util.Comparator.comparingLong(
                        packet -> packet.interval().startMs()))
                .toList();
    }

    private Optional<ProjectedCandidate> projectedCandidate(
            MatchEvidenceSnapshot snapshot,
            int focalParticipantId,
            List<MatchAnchor> anchors,
            TransitionPolicy policy) {
        var anchorInterval = new TimeInterval(
                anchors.getFirst().key().representedAtMs(),
                anchors.getLast().key().representedAtMs());
        var initialReceipt = receiptProjector.project(
                snapshot, anchorInterval, focalParticipantId);
        if (!initialReceipt.hasUsableBrackets()
                || initialReceipt.afterRepresentedAtMs() >= snapshot.header().durationMs()) {
            return Optional.empty();
        }
        var interval = new TimeInterval(
                initialReceipt.beforeRepresentedAtMs(),
                initialReceipt.afterRepresentedAtMs());
        var receipt = receiptProjector.project(snapshot, interval, focalParticipantId);
        var reasons = materialityReasons(
                snapshot.itemTransitions(), focalParticipantId, anchors, receipt, policy);
        return Optional.of(new ProjectedCandidate(interval, anchors, receipt, reasons));
    }

    private Optional<TransitionPacket> eligiblePacket(
            MatchEvidenceSnapshot snapshot,
            int focalParticipantId,
            int focalTeamId,
            ProjectedCandidate candidate,
            TransitionPolicy policy) {
        var anchors = candidate.anchors();
        var interval = candidate.interval();
        var receipt = candidate.receipt();
        var reasons = candidate.materialityReasons();
        if (reasons.isEmpty()) {
            return Optional.empty();
        }
        var narrative = narrativePolicy.describe(
                snapshot, focalParticipantId, anchors, receipt, reasons);
        var claims = new ArrayList<EvidenceClaim>(receipt.claims());
        claims.addAll(narrative.claims());
        var evidence = new ArrayList<dev.leagueanalysis.evidence.domain.EvidenceReference>(
                receipt.evidenceReferences());
        anchors.forEach(anchor -> evidence.addAll(anchor.evidenceReferences()));
        var limitations = new TreeSet<>(receipt.limitationCodes());
        anchors.forEach(anchor -> limitations.addAll(anchor.limitationCodes()));
        return Optional.of(new TransitionPacket(
                AnalyticalObjectId.transition(
                        snapshot.header().matchId(),
                        focalParticipantId,
                        interval,
                        anchors.stream().map(MatchAnchor::key).toList(),
                        policy.version()),
                interval,
                anchors,
                receipt,
                reasons,
                focalRelationships(anchors, focalParticipantId, focalTeamId),
                narrative.questionKind(),
                narrative.title(),
                narrative.interpretationClaim(),
                claims,
                evidence,
                policy.version(),
                limitations));
    }

    private List<ProjectedCandidate> mergeOverlapping(
            MatchEvidenceSnapshot snapshot,
            int focalParticipantId,
            List<ProjectedCandidate> candidates) {
        var ordered = candidates.stream()
                .sorted(java.util.Comparator
                        .comparingLong((ProjectedCandidate candidate) ->
                                candidate.interval().startMs())
                        .thenComparingLong(candidate -> candidate.interval().endMs()))
                .toList();
        var merged = new ArrayList<ProjectedCandidate>();
        for (var candidate : ordered) {
            if (merged.isEmpty()
                    || candidate.interval().startMs()
                            >= merged.getLast().interval().endMs()) {
                merged.add(candidate);
                continue;
            }
            var previous = merged.removeLast();
            var anchors = new ArrayList<MatchAnchor>(previous.anchors());
            anchors.addAll(candidate.anchors());
            var reasons = EnumSet.noneOf(MaterialityReason.class);
            reasons.addAll(previous.materialityReasons());
            reasons.addAll(candidate.materialityReasons());
            var interval = new TimeInterval(
                    previous.interval().startMs(),
                    Math.max(previous.interval().endMs(), candidate.interval().endMs()));
            merged.add(new ProjectedCandidate(
                    interval,
                    anchors.stream().sorted(MatchAnchor.STABLE_ORDER).toList(),
                    receiptProjector.project(snapshot, interval, focalParticipantId),
                    reasons));
        }
        return List.copyOf(merged);
    }

    private Set<MaterialityReason> materialityReasons(
            List<ItemTransition> itemTransitions,
            int focalParticipantId,
            List<MatchAnchor> anchors,
            StateReceipt receipt,
            TransitionPolicy policy) {
        var reasons = EnumSet.noneOf(MaterialityReason.class);
        if (anchors.stream().anyMatch(anchor -> switch (anchor.kind()) {
            case DRAGON, BARON, HERALD -> true;
            default -> false;
        })) {
            reasons.add(MaterialityReason.DURABLE_OBJECTIVE);
        }
        if (anchors.stream().anyMatch(anchor ->
                anchor.kind() == AnchorKind.STRUCTURE
                        || anchor.kind() == AnchorKind.TURRET_PLATE)) {
            reasons.add(MaterialityReason.STRUCTURE_CHANGE);
        }
        var beforeLead = receipt.focalTeamLeadBefore();
        var afterLead = receipt.focalTeamLeadAfter();
        var delta = receipt.focalTeamLeadDelta();
        if (beforeLead != null && afterLead != null && delta != null
                && Math.abs(delta) >= policy.minimumAbsoluteLeadDelta()) {
            if (crossesZero(beforeLead, afterLead)) {
                reasons.add(MaterialityReason.LEAD_SWING);
            } else if (Math.abs(afterLead) > Math.abs(beforeLead)) {
                reasons.add(MaterialityReason.LEAD_EXPANSION);
            }
        }
        if (hasItemBreakpointWithFollowingParticipation(
                itemTransitions, anchors, focalParticipantId)) {
            reasons.add(MaterialityReason.ITEM_BREAKPOINT_WITH_FOLLOWING_PARTICIPATION);
        }
        return reasons;
    }

    private boolean crossesZero(int beforeLead, int afterLead) {
        return beforeLead < 0 && afterLead > 0 || beforeLead > 0 && afterLead < 0;
    }

    private boolean hasItemBreakpointWithFollowingParticipation(
            List<ItemTransition> itemTransitions,
            List<MatchAnchor> anchors,
            int focalParticipantId) {
        return itemTransitions.stream()
                .filter(transition -> Objects.equals(
                        transition.actorParticipantId(), focalParticipantId))
                .filter(this::isAcquisition)
                .filter(transition -> anchors.stream()
                        .anyMatch(anchor -> anchor.key().equals(transition.key())))
                .anyMatch(transition -> anchors.stream()
                        .filter(anchor -> anchor.key().compareTo(transition.key()) > 0)
                        .anyMatch(anchor -> participates(anchor, focalParticipantId)));
    }

    private boolean isAcquisition(ItemTransition transition) {
        var providerType = transition.key().providerEventType();
        return !providerType.equals("ITEM_SOLD")
                && !providerType.equals("ITEM_DESTROYED")
                && !providerType.equals("ITEM_UNDO")
                && (transition.itemId() != null || transition.afterId() != null);
    }

    private boolean participates(MatchAnchor anchor, int focalParticipantId) {
        return Objects.equals(anchor.actorParticipantId(), focalParticipantId)
                || Objects.equals(anchor.targetParticipantId(), focalParticipantId)
                || anchor.assisterParticipantIds().contains(focalParticipantId);
    }

    private Set<FocalRelationship> focalRelationships(
            List<MatchAnchor> anchors, int focalParticipantId, int focalTeamId) {
        var relationships = EnumSet.noneOf(FocalRelationship.class);
        anchors.forEach(anchor -> {
            if (Objects.equals(anchor.actorParticipantId(), focalParticipantId)) {
                relationships.add(FocalRelationship.ACTOR);
            }
            if (Objects.equals(anchor.targetParticipantId(), focalParticipantId)) {
                relationships.add(FocalRelationship.TARGET);
            }
            if (anchor.assisterParticipantIds().contains(focalParticipantId)) {
                relationships.add(FocalRelationship.ASSISTER);
            }
            if (Objects.equals(anchor.teamId(), focalTeamId)) {
                relationships.add(FocalRelationship.RECORDED_TEAM_GAIN);
            }
        });
        return relationships;
    }

    private List<List<MatchAnchor>> clusterAnchors(
            List<MatchAnchor> anchors, long candidateWindowMs) {
        var clusters = new ArrayList<List<MatchAnchor>>();
        var current = new ArrayList<MatchAnchor>();
        for (var anchor : anchors.stream().sorted(MatchAnchor.STABLE_ORDER).toList()) {
            if (!current.isEmpty()
                    && anchor.key().representedAtMs()
                            - current.getFirst().key().representedAtMs() > candidateWindowMs) {
                clusters.add(List.copyOf(current));
                current.clear();
            }
            current.add(anchor);
        }
        if (!current.isEmpty()) {
            clusters.add(List.copyOf(current));
        }
        return List.copyOf(clusters);
    }

    private List<TransitionPacket> selectDiverse(
            List<TransitionPacket> eligible, long durationMs, int maximumSelected) {
        var selected = new ArrayList<TransitionPacket>();
        var representedKinds = EnumSet.noneOf(QuestionKind.class);
        var representedDirections = EnumSet.noneOf(DirectionShape.class);
        var representedPhases = new HashSet<Integer>();
        for (var packet : eligible) {
            if (selected.size() == maximumSelected) {
                break;
            }
            var direction = direction(packet);
            var phase = phase(packet, durationMs);
            var addsDirection = !representedDirections.contains(direction);
            var addsPhase = !representedPhases.contains(phase);
            var addsKind = !representedKinds.contains(packet.questionKind());
            var missingDirections = DirectionShape.values().length
                    - representedDirections.size();
            var possiblePhases = durationMs <= 0 ? 1 : 3;
            var missingPhases = possiblePhases - representedPhases.size();
            var reservedCoverageSlots = Math.max(missingDirections, missingPhases);
            var canSpendKindSlot = selected.size()
                    < maximumSelected - reservedCoverageSlots;
            if (!addsDirection && !addsPhase && !(addsKind && canSpendKindSlot)) {
                continue;
            }
            selected.add(packet);
            representedDirections.add(direction);
            representedPhases.add(phase);
            representedKinds.add(packet.questionKind());
        }
        return chronological(selected);
    }

    private DirectionShape direction(TransitionPacket packet) {
        var delta = packet.receipt().focalTeamLeadDelta();
        if (delta == null || delta == 0) {
            return DirectionShape.MIXED;
        }
        return delta > 0 ? DirectionShape.POSITIVE : DirectionShape.ADVERSE;
    }

    private int phase(TransitionPacket packet, long durationMs) {
        if (durationMs <= 0) {
            return 0;
        }
        var midpoint = packet.interval().startMs()
                + (packet.interval().endMs() - packet.interval().startMs()) / 2;
        return (int) Math.min(2, midpoint * 3 / durationMs);
    }

    private List<TransitionPacket> chronological(List<TransitionPacket> packets) {
        return packets.stream()
                .sorted(java.util.Comparator.comparingLong(
                        packet -> packet.interval().startMs()))
                .toList();
    }

    private record ProjectedCandidate(
            TimeInterval interval,
            List<MatchAnchor> anchors,
            StateReceipt receipt,
            Set<MaterialityReason> materialityReasons) {
        private ProjectedCandidate {
            anchors = List.copyOf(anchors);
            materialityReasons = materialityReasons.isEmpty()
                    ? Set.of()
                    : Set.copyOf(materialityReasons);
        }
    }

    private enum DirectionShape {
        POSITIVE,
        ADVERSE,
        MIXED
    }
}
