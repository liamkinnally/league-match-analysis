package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.DecisionEpisode;
import dev.leagueanalysis.analysis.match.domain.DecisionEpisode.EpisodeKind;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReviewOrderingPolicy {
    public static final String VERSION = "review-order-p3-v1";
    private static final Map<EpisodeKind, Integer> LEARNING_PRIORITY = Map.of(
            EpisodeKind.CONVERSION, 0,
            EpisodeKind.MIXED_VALUE, 1,
            EpisodeKind.ADVERSE_CONSEQUENCE, 2,
            EpisodeKind.OVERLAPPING_EXCHANGE, 3);
    private static final Comparator<DecisionEpisode> CHRONOLOGICAL = Comparator
            .comparingLong((DecisionEpisode episode) -> episode.window().interval().startMs())
            .thenComparing(DecisionEpisode::id);

    public ReviewOrder order(List<DecisionEpisode> episodes) {
        var required = List.copyOf(Objects.requireNonNull(episodes, "episodes"));
        var learning = required.stream()
                .sorted(Comparator.comparingInt(
                                (DecisionEpisode episode) -> LEARNING_PRIORITY.get(episode.kind()))
                        .thenComparing(CHRONOLOGICAL))
                .toList();
        var chronological = required.stream().sorted(CHRONOLOGICAL).toList();
        var navigation = new LinkedHashMap<String, Navigation>();
        for (int index = 0; index < learning.size(); index++) {
            var current = learning.get(index);
            var temporalCue = index == 0
                    ? "START"
                    : temporalCue(learning.get(index - 1), current);
            navigation.put(current.id(), new Navigation(current.id(), temporalCue));
        }
        return new ReviewOrder(
                learning,
                learning.stream().map(DecisionEpisode::id).toList(),
                chronological.stream().map(DecisionEpisode::id).toList(),
                navigation,
                VERSION);
    }

    private String temporalCue(DecisionEpisode from, DecisionEpisode to) {
        var fromStart = from.window().interval().startMs();
        var toStart = to.window().interval().startMs();
        if (toStart < fromStart) {
            return "EARLIER";
        }
        if (toStart > fromStart) {
            return "LATER";
        }
        return "SAME_TIME";
    }

    public record ReviewOrder(
            List<DecisionEpisode> episodes,
            List<String> learningOrder,
            List<String> chronologicalOrder,
            Map<String, Navigation> navigation,
            String orderingRuleVersion) {
        public ReviewOrder {
            episodes = List.copyOf(Objects.requireNonNull(episodes, "episodes"));
            learningOrder = List.copyOf(
                    Objects.requireNonNull(learningOrder, "learningOrder"));
            chronologicalOrder = List.copyOf(
                    Objects.requireNonNull(chronologicalOrder, "chronologicalOrder"));
            navigation = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(navigation, "navigation")));
            orderingRuleVersion = Objects.requireNonNull(
                    orderingRuleVersion, "orderingRuleVersion");
        }

        public Navigation navigationTo(String episodeId) {
            var result = navigation.get(episodeId);
            if (result == null) {
                throw new IllegalArgumentException("EPISODE_NOT_FOUND");
            }
            return result;
        }
    }

    public record Navigation(String episodeId, String temporalCue) {
        public Navigation {
            episodeId = Objects.requireNonNull(episodeId, "episodeId");
            temporalCue = Objects.requireNonNull(temporalCue, "temporalCue");
        }
    }
}
