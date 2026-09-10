package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.domain.AnchorKind;
import dev.leagueanalysis.analysis.match.domain.DecisionEpisode.EpisodeKind;
import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.ReviewBeat;
import dev.leagueanalysis.analysis.match.domain.ReviewBeat.BeatKind;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.analysis.match.domain.StateReceipt;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DecisionEpisodeComposerTest {
    private final DecisionEpisodeComposer composer = new DecisionEpisodeComposer();
    private TransitionPacket conversionPacket;
    private TransitionPacket mixedValuePacket;
    private TransitionPacket adversePacket;
    private TransitionPacket overlappingPacket;

    @BeforeEach
    void selectSanitizedPackets() {
        var packets = new TransitionSelector()
                .select(MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());
        conversionPacket = packets.stream()
                .filter(packet -> packet.interval().startMs() == 780_275L)
                .findFirst()
                .orElseThrow();
        mixedValuePacket = packets.stream()
                .filter(packet -> packet.interval().startMs() == 480_210L)
                .findFirst()
                .orElseThrow();
        adversePacket = packets.stream()
                .filter(packet -> packet.interval().startMs() == 1_080_335L)
                .findFirst()
                .orElseThrow();
        overlappingPacket = packets.stream()
                .filter(packet -> packet.interval().startMs() == 1_500_511L)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void composes_sparse_supported_beats_without_mental_state_fields() {
        var episode = composer.compose(conversionPacket).orElseThrow();

        assertThat(episode.kind()).isEqualTo(EpisodeKind.CONVERSION);
        assertThat(episode.transitionId()).isEqualTo(conversionPacket.transitionId());
        assertThat(episode.beats()).hasSizeBetween(4, 6);
        assertThat(episode.selectionRationale()).isNotBlank();
        assertThat(episode.beats()).extracting(ReviewBeat::kind)
                .contains(BeatKind.CUE, BeatKind.RECEIPT,
                        BeatKind.INTERPRETATION, BeatKind.RECOGNITION_CUE);
        assertThat(episode.beats()).extracting(ReviewBeat::sourceClaimId)
                .filteredOn(Optional::isPresent)
                .map(Optional::orElseThrow)
                .allMatch(claimId -> conversionPacket.claims().stream()
                        .anyMatch(claim -> claim.claimId().equals(claimId)));
        assertThat(episode.beats().stream()
                        .map(ReviewBeat::sourceClaimId)
                        .flatMap(Optional::stream)
                        .toList())
                .doesNotHaveDuplicates();
        assertThat(episode.toString()).doesNotContain(
                "belief", "intent", "best action", "counterfactual");
    }

    @Test
    void returns_empty_instead_of_padding_when_fewer_than_three_beats_are_supported() {
        var thinPacket = thinPacket(conversionPacket);

        assertThat(composer.compose(thinPacket)).isEmpty();
    }

    @Test
    void does_not_invent_a_focal_death_when_adverse_classification_comes_only_from_delta() {
        var receipt = mixedValuePacket.receipt();
        var adverseReceipt = new StateReceipt(
                receipt.interval(), receipt.before(), receipt.after(), -250,
                receipt.projectionRuleVersion(), receipt.evidenceReferences(),
                receipt.claims(), receipt.limitationCodes());
        var deltaOnlyPacket = copyPacket(
                mixedValuePacket, mixedValuePacket.anchors(), adverseReceipt,
                QuestionKind.ADVERSE_CONSEQUENCE);

        var episode = composer.compose(deltaOnlyPacket).orElseThrow();

        assertThat(episode.beats()).extracting(ReviewBeat::kind)
                .doesNotContain(BeatKind.FOCAL_DEATH);
    }

    @Test
    void does_not_choose_an_unrelated_kill_when_packet_wide_target_evidence_is_ambiguous() {
        var possibleFocalDeath = adversePacket.anchors().stream()
                .filter(anchor -> anchor.kind() == AnchorKind.CHAMPION_KILL)
                .findFirst()
                .orElseThrow();
        var unrelatedEarlierKill = anchorAt(
                possibleFocalDeath, 1_120_000L, 1, possibleFocalDeath.kind());
        var ambiguousPacket = copyPacket(
                adversePacket,
                List.of(unrelatedEarlierKill, possibleFocalDeath,
                        adversePacket.anchors().getLast()),
                adversePacket.receipt(), adversePacket.questionKind());

        var episode = composer.compose(ambiguousPacket).orElseThrow();

        assertThat(episode.beats()).extracting(ReviewBeat::kind)
                .doesNotContain(BeatKind.FOCAL_DEATH);
    }

    @Test
    void does_not_label_a_non_objective_anchor_as_a_later_objective() {
        var focalDeath = adversePacket.anchors().stream()
                .filter(anchor -> anchor.kind() == AnchorKind.CHAMPION_KILL)
                .findFirst()
                .orElseThrow();
        var nonObjective = anchorAt(
                adversePacket.anchors().getLast(), 1_170_375L, null, AnchorKind.ITEM);
        var packet = copyPacket(
                adversePacket, List.of(focalDeath, nonObjective),
                adversePacket.receipt(), adversePacket.questionKind());

        var episode = composer.compose(packet).orElseThrow();

        assertThat(episode.beats()).extracting(ReviewBeat::kind)
                .doesNotContain(BeatKind.LATER_OBJECTIVE);
    }

    @Test
    void does_not_assign_mixed_value_sides_from_reordered_anchor_positions() {
        var originalEarlier = mixedValuePacket.anchors().getFirst();
        var originalLater = mixedValuePacket.anchors().getLast();
        var reorderedObjective = anchorAt(
                originalLater, originalEarlier.key().representedAtMs(),
                originalLater.targetParticipantId(), originalLater.kind());
        var reorderedKill = anchorAt(
                originalEarlier, originalLater.key().representedAtMs(),
                originalEarlier.targetParticipantId(), originalEarlier.kind());
        var reorderedPacket = copyPacket(
                mixedValuePacket, List.of(reorderedObjective, reorderedKill),
                mixedValuePacket.receipt(), mixedValuePacket.questionKind());

        var episode = composer.compose(reorderedPacket).orElseThrow();

        assertThat(episode.beats()).extracting(ReviewBeat::kind)
                .doesNotContain(BeatKind.LOCAL_GAIN, BeatKind.OPPOSING_DURABLE_VALUE)
                .contains(BeatKind.SEQUENCE);
    }

    @Test
    void does_not_assign_cost_and_gain_bands_from_overlapping_anchor_positions() {
        var episode = composer.compose(overlappingPacket).orElseThrow();

        assertThat(episode.beats()).extracting(ReviewBeat::kind)
                .doesNotContain(BeatKind.COST_BAND, BeatKind.GAIN_BAND)
                .contains(BeatKind.SEQUENCE);
    }

    private TransitionPacket thinPacket(TransitionPacket source) {
        var thinReceipt = new StateReceipt(
                source.interval(), Optional.empty(), Optional.empty(), null,
                source.receipt().projectionRuleVersion(), List.of(), List.of(),
                Set.of("BEFORE_STATE_UNAVAILABLE", "AFTER_STATE_UNAVAILABLE"));
        return new TransitionPacket(
                source.analyticalObjectId(), source.interval(),
                List.of(source.anchors().getFirst()), thinReceipt,
                source.materialityReasons(), source.focalRelationships(),
                source.questionKind(), source.title(), Optional.empty(), List.of(),
                source.anchors().getFirst().evidenceReferences(),
                source.selectionRuleVersion(), source.limitationCodes());
    }

    private TransitionPacket copyPacket(
            TransitionPacket source,
            List<MatchAnchor> anchors,
            StateReceipt receipt,
            QuestionKind questionKind) {
        return new TransitionPacket(
                source.analyticalObjectId(), source.interval(), anchors, receipt,
                source.materialityReasons(), source.focalRelationships(), questionKind,
                source.title(), source.interpretationClaim(), source.claims(),
                source.evidenceReferences(), source.selectionRuleVersion(),
                source.limitationCodes());
    }

    private MatchAnchor anchorAt(
            MatchAnchor source,
            long representedAtMs,
            Integer targetParticipantId,
            AnchorKind kind) {
        var key = new TimelineEventKey(
                source.key().matchId(), representedAtMs, representedAtMs, 0,
                kind.name());
        var evidence = source.evidenceReferences().stream()
                .map(reference -> new EvidenceReference(
                        reference.sourceCaptureId(), reference.sourceRecordId(),
                        representedAtMs, reference.methodVersion()))
                .toList();
        return new MatchAnchor(
                key, kind, source.actorParticipantId(), targetParticipantId,
                source.teamId(), source.assisterParticipantIds(),
                source.assistersObserved(), source.positionX(), source.positionY(),
                source.descriptor(), evidence, source.limitationCodes());
    }
}
