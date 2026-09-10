package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.domain.DecisionEpisode;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewOrderingPolicyTest {
    private final DecisionEpisodeComposer composer = new DecisionEpisodeComposer();
    private final ReviewOrderingPolicy ordering = new ReviewOrderingPolicy();
    private DecisionEpisode e1;
    private DecisionEpisode e2;
    private DecisionEpisode e3;
    private DecisionEpisode e4;

    @BeforeEach
    void composeFourSanitizedEpisodes() {
        var packets = new TransitionSelector().select(
                MatchAnalysisTestFixture.snapshot(), 6, TransitionPolicy.p3());
        e1 = episodeAt(packets, 480_210L);
        e2 = episodeAt(packets, 780_275L);
        e3 = episodeAt(packets, 1_080_335L);
        e4 = episodeAt(packets, 1_500_511L);
    }

    @Test
    void uses_learning_order_while_preserving_chronological_order() {
        var review = ordering.order(List.of(e1, e2, e3, e4));

        assertThat(review.learningOrder()).containsExactly(
                e2.id(), e1.id(), e3.id(), e4.id());
        assertThat(review.chronologicalOrder()).containsExactly(
                e1.id(), e2.id(), e3.id(), e4.id());
        assertThat(review.navigationTo(e1.id()).temporalCue()).isEqualTo("EARLIER");
        assertThat(review.navigationTo(e3.id()).temporalCue()).isEqualTo("LATER");
        assertThat(review.orderingRuleVersion()).isEqualTo("review-order-p3-v1");
    }

    @Test
    void applies_kind_priority_then_chronology_without_rewriting_episode_times() {
        var review = ordering.order(List.of(e4, e3, e1, e2));

        assertThat(review.episodes()).containsExactly(e2, e1, e3, e4);
        assertThat(review.chronologicalOrder()).containsExactly(
                e1.id(), e2.id(), e3.id(), e4.id());
    }

    private DecisionEpisode episodeAt(List<TransitionPacket> packets, long startMs) {
        return packets.stream()
                .filter(packet -> packet.interval().startMs() == startMs)
                .findFirst()
                .flatMap(composer::compose)
                .orElseThrow();
    }
}
