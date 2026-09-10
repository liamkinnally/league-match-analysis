package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.AnchorKind;
import dev.leagueanalysis.analysis.match.domain.AssertionMode;
import dev.leagueanalysis.analysis.match.domain.EvidenceClaim;
import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.MaterialityReason;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.analysis.match.domain.StateReceipt;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class TransitionNarrativePolicy {
    public Narrative describe(
            MatchEvidenceSnapshot snapshot,
            int focalParticipantId,
            List<MatchAnchor> anchors,
            StateReceipt receipt,
            Set<MaterialityReason> reasons) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(reasons, "reasons");
        var orderedAnchors = Objects.requireNonNull(anchors, "anchors").stream()
                .sorted(MatchAnchor.STABLE_ORDER)
                .toList();
        var focalTeamId = snapshot.participants().stream()
                .filter(participant -> participant.participantId() == focalParticipantId)
                .map(participant -> participant.teamId())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "FOCAL_PARTICIPANT_NOT_FOUND"));
        var questionKind = questionKind(
                snapshot, focalParticipantId, focalTeamId, orderedAnchors, receipt);
        var deathBeforeOpposingGain = hasFocalDeathBeforeOpposingDurableAnchor(
                focalParticipantId, focalTeamId, orderedAnchors);
        var claims = new ArrayList<EvidenceClaim>();
        for (int index = 0; index < orderedAnchors.size(); index++) {
            var anchor = orderedAnchors.get(index);
            claims.add(new EvidenceClaim(
                    "claim_anchor_" + anchor.key().representedAtMs() + "_"
                            + anchor.key().frameEventIndex(),
                    observedAnchorStatement(anchor),
                    AssertionMode.OBSERVED,
                    anchor.evidenceReferences(),
                    anchor.limitationCodes()));
        }
        var interpretation = new EvidenceClaim(
                "claim_transition_interpretation_" + receipt.interval().startMs(),
                interpretation(questionKind),
                AssertionMode.INTERPRETED,
                receipt.evidenceReferences(),
                Set.of());
        claims.add(interpretation);
        claims.add(new EvidenceClaim(
                "claim_transition_limits_" + receipt.interval().startMs(),
                missingEvidence(questionKind, deathBeforeOpposingGain),
                AssertionMode.UNKNOWN,
                List.of(),
                Set.of("RELATION_AND_CONTESTABILITY_UNKNOWN")));
        return new Narrative(
                questionKind,
                title(questionKind, deathBeforeOpposingGain),
                Optional.of(interpretation),
                claims);
    }

    private QuestionKind questionKind(
            MatchEvidenceSnapshot snapshot,
            int focalParticipantId,
            int focalTeamId,
            List<MatchAnchor> anchors,
            StateReceipt receipt) {
        if (hasParallelOpposingAnchors(snapshot, focalTeamId, anchors)) {
            return QuestionKind.OVERLAPPING_EXCHANGE;
        }
        if (hasFocalDeathBeforeOpposingDurableAnchor(
                focalParticipantId, focalTeamId, anchors)) {
            return QuestionKind.ADVERSE_CONSEQUENCE;
        }
        var representedTeams = anchors.stream()
                .map(anchor -> representedTeamId(snapshot, anchor))
                .flatMap(Optional::stream)
                .collect(java.util.stream.Collectors.toSet());
        if (representedTeams.contains(focalTeamId)
                && representedTeams.stream().anyMatch(teamId -> teamId != focalTeamId)) {
            return QuestionKind.MIXED_VALUE;
        }
        if (representedTeams.contains(focalTeamId)
                && anchors.stream().anyMatch(this::isDurableOrStructure)) {
            return QuestionKind.CONVERSION;
        }
        var delta = receipt.focalTeamLeadDelta();
        if (delta != null && delta < 0) {
            return QuestionKind.ADVERSE_CONSEQUENCE;
        }
        if (delta != null && delta > 0) {
            return QuestionKind.CONVERSION;
        }
        return QuestionKind.MIXED_VALUE;
    }

    private boolean hasParallelOpposingAnchors(
            MatchEvidenceSnapshot snapshot, int focalTeamId, List<MatchAnchor> anchors) {
        return anchors.stream()
                .filter(anchor -> anchor.kind() != AnchorKind.ITEM)
                .collect(java.util.stream.Collectors.groupingBy(
                        anchor -> anchor.key().representedAtMs()))
                .values()
                .stream()
                .anyMatch(parallel -> {
                    var teams = parallel.stream()
                            .map(anchor -> representedTeamId(snapshot, anchor))
                            .flatMap(Optional::stream)
                            .collect(java.util.stream.Collectors.toSet());
                    return teams.contains(focalTeamId)
                            && teams.stream().anyMatch(teamId -> teamId != focalTeamId);
                });
    }

    private boolean hasFocalDeathBeforeOpposingDurableAnchor(
            int focalParticipantId, int focalTeamId, List<MatchAnchor> anchors) {
        return anchors.stream()
                .filter(anchor -> anchor.kind() == AnchorKind.CHAMPION_KILL)
                .filter(anchor -> Objects.equals(anchor.targetParticipantId(), focalParticipantId))
                .anyMatch(death -> anchors.stream()
                        .filter(anchor -> anchor.key().compareTo(death.key()) > 0)
                        .filter(this::isDurableOrStructure)
                        .anyMatch(anchor -> anchor.teamId() != null
                                && anchor.teamId() != focalTeamId));
    }

    private Optional<Integer> representedTeamId(
            MatchEvidenceSnapshot snapshot, MatchAnchor anchor) {
        if (anchor.limitationCodes().contains("AMBIGUOUS_ANCHOR_RELATION")) {
            return Optional.empty();
        }
        if (anchor.teamId() != null) {
            return Optional.of(anchor.teamId());
        }
        if (anchor.actorParticipantId() == null || anchor.actorParticipantId() == 0) {
            return Optional.empty();
        }
        return snapshot.participants().stream()
                .filter(participant -> participant.participantId()
                        == anchor.actorParticipantId())
                .map(participant -> participant.teamId())
                .findFirst();
    }

    private boolean isDurableOrStructure(MatchAnchor anchor) {
        return switch (anchor.kind()) {
            case DRAGON, BARON, HERALD, STRUCTURE, TURRET_PLATE -> true;
            default -> false;
        };
    }

    private String observedAnchorStatement(MatchAnchor anchor) {
        return switch (anchor.kind()) {
            case CHAMPION_KILL -> "A champion kill was recorded at "
                    + anchor.key().representedAtMs() + " ms.";
            case DRAGON, BARON, HERALD -> "A recorded "
                    + anchor.kind().name().toLowerCase(java.util.Locale.ROOT)
                    + " objective occurred at " + anchor.key().representedAtMs() + " ms.";
            case STRUCTURE, TURRET_PLATE -> "A structure event was recorded at "
                    + anchor.key().representedAtMs() + " ms.";
            case ITEM -> "An item event was recorded at "
                    + anchor.key().representedAtMs() + " ms.";
            default -> "An event was recorded at "
                    + anchor.key().representedAtMs() + " ms.";
        };
    }

    private String title(QuestionKind kind, boolean deathBeforeOpposingGain) {
        return switch (kind) {
            case MIXED_VALUE -> "Value was recorded for both teams in this interval";
            case CONVERSION -> "Focal-team gains were recorded in this interval";
            case ADVERSE_CONSEQUENCE -> deathBeforeOpposingGain
                    ? "A focal death preceded an opposing gain"
                    : "The sampled focal-team lead decreased in this interval";
            case OVERLAPPING_EXCHANGE -> "Opposing gains overlapped in this interval";
        };
    }

    private String interpretation(QuestionKind kind) {
        return switch (kind) {
            case MIXED_VALUE ->
                    "The bounded evidence supports a mixed-value reading of the interval.";
            case CONVERSION ->
                    "The later sampled state can be compared with the recorded focal-team gains.";
            case ADVERSE_CONSEQUENCE ->
                    "The later sampled state records an adverse change after the bounded sequence.";
            case OVERLAPPING_EXCHANGE ->
                    "The parallel anchors support competing readings of the exchange.";
        };
    }

    private String missingEvidence(QuestionKind kind, boolean deathBeforeOpposingGain) {
        return switch (kind) {
            case ADVERSE_CONSEQUENCE -> deathBeforeOpposingGain
                    ? "The available evidence does not establish objective contestability or a link from the earlier death."
                    : "The sampled movement does not establish cause, objective contestability, or a preferred action.";
            default ->
                    "Intent, complete tradeoffs, and a preferred action are unknown.";
        };
    }

    public record Narrative(
            QuestionKind questionKind,
            String title,
            Optional<EvidenceClaim> interpretationClaim,
            List<EvidenceClaim> claims) {
        public Narrative {
            questionKind = Objects.requireNonNull(questionKind, "questionKind");
            title = dev.leagueanalysis.evidence.domain.TimelineEventKey.requireText(title);
            interpretationClaim = Objects.requireNonNull(
                    interpretationClaim, "interpretationClaim");
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        }
    }
}
