package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record MatchAnchor(
        TimelineEventKey key,
        AnchorKind kind,
        Integer actorParticipantId,
        Integer targetParticipantId,
        Integer teamId,
        List<Integer> assisterParticipantIds,
        boolean assistersObserved,
        Integer positionX,
        Integer positionY,
        String descriptor,
        List<EvidenceReference> evidenceReferences,
        Set<String> limitationCodes) {
    public static final Comparator<MatchAnchor> STABLE_ORDER = Comparator.comparing(MatchAnchor::key);

    public MatchAnchor {
        key = Objects.requireNonNull(key, "key");
        kind = Objects.requireNonNull(kind, "kind");
        validateParticipantId(actorParticipantId, true);
        validateParticipantId(targetParticipantId, false);
        if (teamId != null && teamId < 0) {
            throw new IllegalArgumentException("INVALID_TEAM_ID");
        }
        assisterParticipantIds = Objects.requireNonNull(
                        assisterParticipantIds, "assisterParticipantIds")
                .stream()
                .map(id -> {
                    validateParticipantId(id, false);
                    return id;
                })
                .distinct()
                .sorted()
                .toList();
        if (!assistersObserved && !assisterParticipantIds.isEmpty()) {
            throw new IllegalArgumentException("UNOBSERVED_ASSISTER_PARTICIPANT_IDS");
        }
        if (descriptor != null) {
            descriptor = TimelineEventKey.requireText(descriptor);
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
        var limitations = new TreeSet<>(Objects.requireNonNull(limitationCodes, "limitationCodes"));
        if (limitations.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("INVALID_ANCHOR_LIMITATION");
        }
        limitationCodes = Collections.unmodifiableSortedSet(limitations);
    }

    public static MatchAnchor reconcile(List<MatchAnchor> reports) {
        var requiredReports = Objects.requireNonNull(reports, "reports").stream()
                .map(report -> Objects.requireNonNull(report, "report"))
                .toList();
        if (requiredReports.isEmpty()) {
            throw new IllegalArgumentException("ANCHOR_REPORT_REQUIRED");
        }
        var first = requiredReports.getFirst();
        if (requiredReports.stream().anyMatch(report -> !report.key().equals(first.key()))) {
            throw new IllegalArgumentException("MISMATCHED_ANCHOR_KEY");
        }
        var evidence = requiredReports.stream()
                .flatMap(report -> report.evidenceReferences().stream())
                .toList();
        var limitations = new TreeSet<String>();
        requiredReports.forEach(report -> limitations.addAll(report.limitationCodes()));
        var content = AnchorContent.from(first);
        var sameKind = requiredReports.stream().allMatch(report -> report.kind() == first.kind());
        if (sameKind && requiredReports.stream().map(AnchorContent::from).allMatch(content::equals)) {
            return new MatchAnchor(
                    first.key(),
                    first.kind(),
                    content.actorParticipantId(),
                    content.targetParticipantId(),
                    content.teamId(),
                    content.assisterParticipantIds(),
                    content.assistersObserved(),
                    content.positionX(),
                    content.positionY(),
                    content.descriptor(),
                    evidence,
                    limitations);
        }
        limitations.add("AMBIGUOUS_ANCHOR_RELATION");
        return new MatchAnchor(
                first.key(),
                sameKind ? first.kind() : AnchorKind.OTHER,
                null,
                null,
                null,
                List.of(),
                false,
                null,
                null,
                null,
                evidence,
                limitations);
    }

    private static void validateParticipantId(Integer participantId, boolean providerZeroAllowed) {
        if (participantId != null
                && (participantId < (providerZeroAllowed ? 0 : 1) || participantId > 10)) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
    }

    private record AnchorContent(
            Integer actorParticipantId,
            Integer targetParticipantId,
            Integer teamId,
            List<Integer> assisterParticipantIds,
            boolean assistersObserved,
            Integer positionX,
            Integer positionY,
            String descriptor) {
        private static AnchorContent from(MatchAnchor anchor) {
            return new AnchorContent(
                    anchor.actorParticipantId(),
                    anchor.targetParticipantId(),
                    anchor.teamId(),
                    anchor.assisterParticipantIds(),
                    anchor.assistersObserved(),
                    anchor.positionX(),
                    anchor.positionY(),
                    anchor.descriptor());
        }
    }
}
