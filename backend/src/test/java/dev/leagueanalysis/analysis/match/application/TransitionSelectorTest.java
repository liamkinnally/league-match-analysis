package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.domain.AssertionMode;
import dev.leagueanalysis.analysis.match.domain.MaterialityReason;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.analysis.match.domain.TimeInterval;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TransitionSelectorTest {
    private final TransitionSelector selector = new TransitionSelector();

    @Test
    void selects_sparse_diverse_packets_from_named_materiality_gates() {
        var selected = selector.select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());

        assertThat(selected).hasSizeBetween(3, 5);
        assertThat(selected).extracting(TransitionPacket::questionKind)
                .contains(
                        QuestionKind.MIXED_VALUE,
                        QuestionKind.CONVERSION,
                        QuestionKind.ADVERSE_CONSEQUENCE,
                        QuestionKind.OVERLAPPING_EXCHANGE);
        assertThat(selected).allSatisfy(packet -> {
            assertThat(packet.anchors()).isNotEmpty();
            assertThat(packet.receipt().hasUsableBrackets()).isTrue();
            assertThat(packet.materialityReasons()).isNotEmpty();
            assertThat(packet.selectionRuleVersion())
                    .isEqualTo("material-transition-p3-v1");
        });
    }

    @Test
    void preserves_sample_brackets_and_parallel_anchors_in_the_selected_packets() {
        var selected = selector.select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());

        assertThat(selected).extracting(TransitionPacket::interval)
                .containsExactly(
                        new TimeInterval(480_210L, 600_228L),
                        new TimeInterval(780_275L, 900_291L),
                        new TimeInterval(1_080_335L, 1_200_389L),
                        new TimeInterval(1_500_511L, 1_620_521L));
        var overlapping = selected.stream()
                .filter(packet -> packet.questionKind() == QuestionKind.OVERLAPPING_EXCHANGE)
                .findFirst()
                .orElseThrow();
        assertThat(overlapping.anchors().stream()
                .filter(anchor -> anchor.key().representedAtMs() == 1_560_516L))
                .hasSize(2);
        assertThat(overlapping.materialityReasons()).contains(
                MaterialityReason.LEAD_SWING,
                MaterialityReason.STRUCTURE_CHANGE,
                MaterialityReason.ITEM_BREAKPOINT_WITH_FOLLOWING_PARTICIPATION);
    }

    @Test
    void result_and_future_events_cannot_change_an_earlier_selection() {
        var baseline = selector.select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());
        var altered = selector.select(
                MatchAnalysisTestFixture.withAlteredResultAndPostWindowEvidence(),
                6,
                TransitionPolicy.p3());

        assertThat(idsBefore(altered, 1_200_389L))
                .isEqualTo(idsBefore(baseline, 1_200_389L));
    }

    @Test
    void terminal_result_cannot_change_selection() {
        var baseline = selector.select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());
        var alteredResult = selector.select(
                MatchAnalysisTestFixture.withAlteredResultOnly(),
                6,
                TransitionPolicy.p3());

        assertThat(ids(alteredResult)).containsExactlyElementsOf(ids(baseline));
    }

    @Test
    void caps_only_after_material_candidates_are_eligible() {
        var capped = selector.select(
                MatchAnalysisTestFixture.snapshot(),
                6,
                new TransitionPolicy("material-transition-test-cap-v1", 120_000, 800, 3));

        assertThat(capped).hasSize(3).allSatisfy(packet -> {
            assertThat(packet.receipt().hasUsableBrackets()).isTrue();
            assertThat(packet.materialityReasons()).isNotEmpty();
        });
    }

    @Test
    void an_anchor_beyond_the_candidate_boundary_cannot_extend_the_existing_window() {
        var baseline = selector.select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());
        var altered = selector.select(
                MatchAnalysisTestFixture.withAnchorBeyondFourthCandidateBoundary(),
                6,
                TransitionPolicy.p3());

        var baselineFourthId = baseline.stream()
                .filter(packet -> packet.interval().startMs() == 1_500_511L)
                .map(TransitionPacket::transitionId)
                .findFirst()
                .orElseThrow();
        assertThat(altered).filteredOn(packet ->
                        packet.interval().startMs() == 1_500_511L)
                .extracting(TransitionPacket::transitionId)
                .containsExactly(baselineFourthId);
    }

    @Test
    void a_future_candidate_sharing_only_a_bracket_boundary_preserves_the_prior_packet() {
        var baseline = selector.select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());
        var expanded = MatchAnalysisTestFixture.withBracketedFutureCandidate();
        var eligible = selector.eligible(expanded, 6, TransitionPolicy.p3());

        assertThat(eligible).extracting(TransitionPacket::interval).contains(
                new TimeInterval(1_500_511L, 1_620_521L),
                new TimeInterval(1_620_521L, 1_740_000L));
        assertThat(eligible).filteredOn(packet -> packet.interval().endMs() == 1_620_521L)
                .containsExactly(baseline.getLast());
        assertThat(idsBefore(selector.select(expanded, 6, TransitionPolicy.p3()), 1_620_521L))
                .containsExactlyElementsOf(ids(baseline));
    }

    @Test
    void overlapping_projected_intervals_merge_without_losing_exact_anchors() {
        var selected = selector.select(
                MatchAnalysisTestFixture.withSparseOverlappingProjectedCandidates(),
                6,
                TransitionPolicy.p3());
        var reordered = selector.select(
                MatchAnalysisTestFixture.reorderedSparseOverlappingProjectedCandidates(),
                6,
                TransitionPolicy.p3());

        assertThat(selected).hasSize(3);
        var merged = selected.stream()
                .filter(packet -> packet.interval().equals(
                        new TimeInterval(480_210L, 900_291L)))
                .findFirst()
                .orElseThrow();
        assertThat(merged.anchors())
                .extracting(anchor -> anchor.key().representedAtMs())
                .containsExactly(540_215L, 570_220L, 840_281L, 870_286L);
        assertThat(reordered).extracting(TransitionPacket::transitionId)
                .containsExactlyElementsOf(ids(selected));
    }

    @Test
    void projected_merge_cannot_create_a_cross_boundary_item_gate() {
        var selected = selector.select(
                MatchAnalysisTestFixture.withCrossBoundaryItemSequenceInSparseProjection(),
                6,
                TransitionPolicy.p3());

        var merged = selected.stream()
                .filter(packet -> packet.interval().equals(
                        new TimeInterval(480_210L, 900_291L)))
                .findFirst()
                .orElseThrow();
        assertThat(merged.materialityReasons()).doesNotContain(
                MaterialityReason.ITEM_BREAKPOINT_WITH_FOLLOWING_PARTICIPATION);
    }

    @Test
    void over_cap_selection_preserves_available_direction_and_phase_coverage() {
        var selected = selector.select(
                MatchAnalysisTestFixture.overCapDirectionAndPhaseCoverage(),
                6,
                TransitionPolicy.p3());

        assertThat(selected).hasSize(5);
        assertThat(selected).extracting(packet -> packet.interval().startMs())
                .contains(100_000L, 820_000L, 1_480_000L);
        assertThat(selected).extracting(packet -> packet.receipt().focalTeamLeadDelta())
                .contains(1_000, -1_000, 0);
    }

    @Test
    void late_novel_kind_cannot_displace_an_earlier_saturated_selection() {
        var baseline = selector.select(
                MatchAnalysisTestFixture.atSaturatedCapWithoutLateNovelKind(),
                6,
                TransitionPolicy.p3());
        var withLateNovelKind = selector.select(
                MatchAnalysisTestFixture.withLateNovelKindBeyondSaturatedCap(),
                6,
                TransitionPolicy.p3());

        assertThat(baseline).hasSize(5);
        assertThat(withLateNovelKind).extracting(TransitionPacket::transitionId)
                .containsExactlyElementsOf(ids(baseline));
    }

    @Test
    void terminal_lead_cannot_admit_a_near_terminal_candidate() {
        var baseline = selector.select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());
        var withTerminalCandidate = selector.select(
                MatchAnalysisTestFixture.withTerminalLeadCandidate(),
                6,
                TransitionPolicy.p3());

        assertThat(withTerminalCandidate).extracting(TransitionPacket::transitionId)
                .containsExactlyElementsOf(ids(baseline));
    }

    @Test
    void logical_transition_ids_ignore_row_uuids_and_input_order() {
        var baseline = selector.select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());
        var differentRows = selector.select(
                MatchAnalysisTestFixture.withDifferentRowIds(), 6, TransitionPolicy.p3());
        var reordered = selector.select(
                MatchAnalysisTestFixture.reordered(), 6, TransitionPolicy.p3());

        assertThat(differentRows).extracting(TransitionPacket::transitionId)
                .containsExactlyElementsOf(ids(baseline));
        assertThat(reordered).extracting(TransitionPacket::transitionId)
                .containsExactlyElementsOf(ids(baseline));
        assertThat(baseline.getFirst().transitionId())
                .isEqualTo("trn_bad429943abdeee4e6351b5e");
    }

    @Test
    void narratives_keep_interpretation_and_unknowns_separate_from_observations() {
        var selected = selector.select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());

        assertThat(selected).allSatisfy(packet -> {
            assertThat(packet.title()).isNotBlank();
            assertThat(packet.claims()).extracting(claim -> claim.assertionMode())
                    .contains(AssertionMode.OBSERVED, AssertionMode.UNKNOWN);
            assertThat((packet.title() + " " + packet.interpretation().orElse(""))
                    .toLowerCase(Locale.ROOT))
                    .doesNotContain("blame", "best", "mistake", "throw", "caused");
        });
    }

    private List<String> idsBefore(List<TransitionPacket> packets, long inclusiveEndMs) {
        return packets.stream()
                .filter(packet -> packet.interval().endMs() <= inclusiveEndMs)
                .map(TransitionPacket::transitionId)
                .toList();
    }

    private List<String> ids(List<TransitionPacket> packets) {
        return packets.stream().map(TransitionPacket::transitionId).toList();
    }
}
