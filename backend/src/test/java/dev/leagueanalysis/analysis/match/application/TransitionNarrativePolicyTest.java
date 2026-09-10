package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.domain.AssertionMode;
import dev.leagueanalysis.analysis.match.domain.MaterialityReason;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.analysis.match.domain.TimeInterval;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransitionNarrativePolicyTest {
    private final TransitionNarrativePolicy policy = new TransitionNarrativePolicy();

    @Test
    void negative_receipt_without_focal_death_describes_only_sampled_movement() {
        var snapshot = MatchAnalysisTestFixture.snapshot();
        var receipt = new StateReceiptProjector().project(
                snapshot, new TimeInterval(1_080_335L, 1_200_389L), 6);
        var opposingObjective = snapshot.anchors().stream()
                .filter(anchor -> anchor.key().representedAtMs() == 1_170_375L)
                .findFirst().orElseThrow();

        var narrative = policy.describe(snapshot, 6, List.of(opposingObjective), receipt,
                Set.of(MaterialityReason.DURABLE_OBJECTIVE));

        assertThat(receipt.focalTeamLeadDelta()).isNegative();
        assertThat(narrative.questionKind()).isEqualTo(QuestionKind.ADVERSE_CONSEQUENCE);
        assertThat(narrative.title()).containsIgnoringCase("sampled").doesNotContain("death");
        assertThat(narrative.claims()).allSatisfy(claim ->
                assertThat(claim.statement()).doesNotContain("death"));
        assertThat(narrative.claims()).filteredOn(claim ->
                        claim.assertionMode() == AssertionMode.UNKNOWN)
                .singleElement().satisfies(claim -> assertThat(claim.statement())
                        .contains("does not establish", "cause", "contestability"));
    }
}
