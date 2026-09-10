package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.AssertionMode;
import dev.leagueanalysis.analysis.match.domain.AnchorKind;
import dev.leagueanalysis.analysis.match.domain.DecisionEpisode;
import dev.leagueanalysis.analysis.match.domain.DecisionEpisode.DecisionWindow;
import dev.leagueanalysis.analysis.match.domain.DecisionEpisode.EpisodeKind;
import dev.leagueanalysis.analysis.match.domain.EvidenceClaim;
import dev.leagueanalysis.analysis.match.domain.MaterialityReason;
import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.ReviewBeat;
import dev.leagueanalysis.analysis.match.domain.ReviewBeat.BeatKind;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket.FocalRelationship;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class DecisionEpisodeComposer {
    public static final String VERSION = "review-episode-p3-v1";

    public Optional<DecisionEpisode> compose(TransitionPacket packet) {
        var kind = episodeKind(packet);
        var episodeId = episodeId(packet.transitionId(), kind);
        var beats = switch (kind) {
            case CONVERSION -> conversionBeats(episodeId, packet);
            case MIXED_VALUE -> mixedValueBeats(episodeId, packet);
            case ADVERSE_CONSEQUENCE -> adverseBeats(episodeId, packet);
            case OVERLAPPING_EXCHANGE -> overlappingBeats(episodeId, packet);
        };
        if (beats.size() < 3) {
            return Optional.empty();
        }
        return Optional.of(new DecisionEpisode(
                episodeId,
                packet.transitionId(),
                kind,
                new DecisionWindow(
                        packet.interval(),
                        packet.anchors().stream().map(anchor -> anchor.key().toString()).toList()),
                packet.questionKind().questionId(),
                selectionRationale(kind),
                beats,
                VERSION));
    }

    private List<ReviewBeat> conversionBeats(String episodeId, TransitionPacket packet) {
        var beats = new ArrayList<ReviewBeat>();
        addCue(beats, episodeId, packet, "Exact conversion cue");
        addBefore(beats, episodeId, packet);
        addSequence(beats, episodeId, packet,
                "Ordered anchors and observed durable value appear in this interval.");
        addReceipt(beats, episodeId, packet);
        addInterpretation(beats, episodeId, packet);
        addRecognition(beats, episodeId, packet,
                "Look for exact follow-through before treating a local gain as conversion.");
        return List.copyOf(beats);
    }

    private List<ReviewBeat> mixedValueBeats(String episodeId, TransitionPacket packet) {
        var beats = new ArrayList<ReviewBeat>();
        addSequence(beats, episodeId, packet,
                "Observed anchors are ordered without assigning either anchor to a side.");
        addReceipt(beats, episodeId, packet);
        addInterpretation(beats, episodeId, packet);
        if (beats.size() >= 3) {
            beats.add(beat(episodeId, beats.size(), BeatKind.CONTRAST,
                    "Keep both sides visible",
                    "The interval contains value on both sides; chronology alone does not rank it.",
                    AssertionMode.OBSERVED, Optional.empty(), packet.evidenceReferences(),
                    packet.limitationCodes()));
        }
        return List.copyOf(beats);
    }

    private List<ReviewBeat> adverseBeats(String episodeId, TransitionPacket packet) {
        var beats = new ArrayList<ReviewBeat>();
        addBefore(beats, episodeId, packet);
        var focalDeath = focalDeath(packet);
        focalDeath
                .ifPresent(anchor -> addAnchorBeat(beats, episodeId, packet,
                        BeatKind.FOCAL_DEATH, "Observed focal death", anchor));
        focalDeath.flatMap(death -> packet.anchors().stream()
                        .filter(anchor -> anchor.key().compareTo(death.key()) > 0)
                        .filter(this::isDurableObjective)
                        .findFirst())
                .ifPresent(anchor -> addAnchorBeat(beats, episodeId, packet,
                        BeatKind.LATER_OBJECTIVE, "Later observed objective", anchor));
        addReceipt(beats, episodeId, packet);
        var unknown = firstClaim(packet, AssertionMode.UNKNOWN);
        beats.add(beat(episodeId, beats.size(), BeatKind.EVIDENCE_LIMIT,
                "Evidence boundary",
                unknown.map(EvidenceClaim::statement)
                        .orElse("The observed order does not establish a causal edge."),
                AssertionMode.UNKNOWN,
                unknown.map(claim -> Optional.of(claim.claimId())).orElse(Optional.empty()),
                unknown.map(EvidenceClaim::evidenceReferences).orElse(packet.evidenceReferences()),
                unknown.map(EvidenceClaim::limitationCodes).orElse(packet.limitationCodes())));
        addRecognition(beats, episodeId, packet,
                "Separate the focal cost from any later team-level consequence.");
        return List.copyOf(beats);
    }

    private List<ReviewBeat> overlappingBeats(String episodeId, TransitionPacket packet) {
        var beats = new ArrayList<ReviewBeat>();
        addSequence(beats, episodeId, packet,
                "Overlapping anchors are retained without assigning cost or gain roles.");
        if (packet.materialityReasons().contains(MaterialityReason.STRUCTURE_CHANGE)
                || packet.materialityReasons().contains(MaterialityReason.DURABLE_OBJECTIVE)) {
            beats.add(beat(episodeId, beats.size(), BeatKind.DURABLE_VALUE,
                    "Observed durable outcomes",
                    "Durable categories are recorded without assigning cause between them.",
                    AssertionMode.OBSERVED, Optional.empty(), packet.evidenceReferences(),
                    packet.limitationCodes()));
        }
        addReceipt(beats, episodeId, packet);
        addInterpretation(beats, episodeId, packet);
        if (beats.size() >= 3) {
            beats.add(beat(episodeId, beats.size(), BeatKind.COMPETING_READING,
                    "Competing readings remain open",
                    "The overlapping exchange supports more than one bounded reading.",
                    AssertionMode.UNKNOWN, Optional.empty(), packet.evidenceReferences(),
                    Set.of("CAUSAL_EDGE_NOT_ESTABLISHED")));
        }
        return List.copyOf(beats);
    }

    private void addCue(
            List<ReviewBeat> beats,
            String episodeId,
            TransitionPacket packet,
            String title) {
        if (!packet.anchors().isEmpty()) {
            addAnchorBeat(beats, episodeId, packet, BeatKind.CUE,
                    title, packet.anchors().getFirst());
        }
    }

    private void addBefore(
            List<ReviewBeat> beats, String episodeId, TransitionPacket packet) {
        packet.receipt().before().ifPresent(before -> beats.add(beat(
                episodeId, beats.size(), BeatKind.BEFORE_STATE,
                "Before state",
                "Timestamped state immediately before the bounded sequence.",
                AssertionMode.RECONSTRUCTED,
                Optional.empty(),
                before.evidenceReferences(), before.limitationCodes())));
    }

    private void addSequence(
            List<ReviewBeat> beats,
            String episodeId,
            TransitionPacket packet,
            String detail) {
        if (packet.anchors().size() > 1) {
            beats.add(beat(episodeId, beats.size(), BeatKind.SEQUENCE,
                    "Exact observed sequence", detail, AssertionMode.OBSERVED,
                    Optional.empty(),
                    packet.anchors().stream()
                            .flatMap(anchor -> anchor.evidenceReferences().stream())
                            .distinct()
                            .toList(),
                    packet.limitationCodes()));
        }
    }

    private void addReceipt(
            List<ReviewBeat> beats, String episodeId, TransitionPacket packet) {
        if (packet.receipt().hasUsableBrackets()) {
            beats.add(beat(episodeId, beats.size(), BeatKind.RECEIPT,
                    "State receipt",
                    "Timestamped before and after samples bound the observed change.",
                    AssertionMode.RECONSTRUCTED,
                    Optional.empty(),
                    packet.receipt().evidenceReferences(),
                    packet.receipt().limitationCodes()));
        }
    }

    private void addInterpretation(
            List<ReviewBeat> beats, String episodeId, TransitionPacket packet) {
        packet.interpretationClaim().ifPresent(claim -> beats.add(beat(
                episodeId, beats.size(), BeatKind.INTERPRETATION,
                "Bounded interpretation", claim.statement(), claim.assertionMode(),
                Optional.of(claim.claimId()), claim.evidenceReferences(),
                claim.limitationCodes())));
    }

    private void addRecognition(
            List<ReviewBeat> beats,
            String episodeId,
            TransitionPacket packet,
            String detail) {
        if (beats.size() >= 2) {
            beats.add(beat(episodeId, beats.size(), BeatKind.RECOGNITION_CUE,
                    "Recognition cue", detail, AssertionMode.INTERPRETED,
                    Optional.empty(), packet.evidenceReferences(), packet.limitationCodes()));
        }
    }

    private void addAnchorBeat(
            List<ReviewBeat> beats,
            String episodeId,
            TransitionPacket packet,
            BeatKind kind,
            String title,
            dev.leagueanalysis.analysis.match.domain.MatchAnchor anchor) {
        beats.add(beat(episodeId, beats.size(), kind, title,
                "Exact " + anchor.kind().name().toLowerCase(java.util.Locale.ROOT)
                        + " anchor at " + anchor.key().representedAtMs() + " ms.",
                AssertionMode.OBSERVED,
                Optional.empty(),
                anchor.evidenceReferences(), anchor.limitationCodes()));
    }

    private ReviewBeat beat(
            String episodeId,
            int index,
            BeatKind kind,
            String title,
            String detail,
            AssertionMode assertionMode,
            Optional<String> sourceClaimId,
            List<dev.leagueanalysis.evidence.domain.EvidenceReference> evidenceReferences,
            Set<String> limitationCodes) {
        return new ReviewBeat(
                episodeId + "-beat-" + (index + 1), kind, title, detail,
                assertionMode, sourceClaimId, evidenceReferences, limitationCodes);
    }

    private Optional<EvidenceClaim> firstClaim(
            TransitionPacket packet, AssertionMode mode) {
        return firstClaim(packet.claims(), mode);
    }

    private Optional<MatchAnchor> focalDeath(TransitionPacket packet) {
        if (!packet.focalRelationships().contains(FocalRelationship.TARGET)) {
            return Optional.empty();
        }
        var targetBearingKills = packet.anchors().stream()
                .filter(anchor -> anchor.kind() == AnchorKind.CHAMPION_KILL)
                .filter(anchor -> anchor.targetParticipantId() != null)
                .toList();
        var targetIds = targetBearingKills.stream()
                .map(MatchAnchor::targetParticipantId)
                .distinct()
                .toList();
        if (targetIds.size() != 1) {
            return Optional.empty();
        }
        return targetBearingKills.stream().findFirst();
    }

    private boolean isDurableObjective(MatchAnchor anchor) {
        return switch (anchor.kind()) {
            case DRAGON, BARON, HERALD, STRUCTURE, TURRET_PLATE -> true;
            default -> false;
        };
    }

    private Optional<EvidenceClaim> firstClaim(
            List<EvidenceClaim> claims, AssertionMode mode) {
        return claims.stream().filter(claim -> claim.assertionMode() == mode).findFirst();
    }

    private EpisodeKind episodeKind(TransitionPacket packet) {
        return switch (packet.questionKind()) {
            case CONVERSION -> EpisodeKind.CONVERSION;
            case MIXED_VALUE -> EpisodeKind.MIXED_VALUE;
            case ADVERSE_CONSEQUENCE -> EpisodeKind.ADVERSE_CONSEQUENCE;
            case OVERLAPPING_EXCHANGE -> EpisodeKind.OVERLAPPING_EXCHANGE;
        };
    }

    private String selectionRationale(EpisodeKind kind) {
        return switch (kind) {
            case CONVERSION -> "Selected for an observed conversion sequence and durable value.";
            case MIXED_VALUE -> "Selected because both sides record material value in one interval.";
            case ADVERSE_CONSEQUENCE -> "Selected to separate an observed focal cost from later consequence.";
            case OVERLAPPING_EXCHANGE -> "Selected because cost and gain bands overlap in time.";
        };
    }

    private String episodeId(String transitionId, EpisodeKind kind) {
        var canonical = transitionId + '\n' + kind.name() + '\n' + VERSION;
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "ep_" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
