package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.ItemTransition;
import dev.leagueanalysis.evidence.domain.MatchSourceRevision;
import dev.leagueanalysis.evidence.domain.SourceCoverage;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record MatchEvidenceSnapshot(
        MatchHeader header,
        MatchSourceRevision sourceRevision,
        List<MatchParticipant> participants,
        List<ParticipantObservation> observations,
        List<MatchAnchor> anchors,
        List<ItemTransition> itemTransitions,
        Map<Integer, List<Integer>> observedEndItems,
        List<SourceCoverage> coverage) {
    public MatchEvidenceSnapshot {
        header = Objects.requireNonNull(header, "header");
        sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
        if (!header.matchId().equals(sourceRevision.matchId())) {
            throw new IllegalArgumentException("MISMATCHED_SOURCE_REVISION");
        }
        participants = sortedCopy(participants, MatchParticipant.STABLE_ORDER, "participants");
        observations = sortedCopy(observations, ParticipantObservation.STABLE_ORDER, "observations");
        anchors = sortedCopy(anchors, MatchAnchor.STABLE_ORDER, "anchors");
        itemTransitions = sortedCopy(
                itemTransitions,
                Comparator.comparing(ItemTransition::key)
                        .thenComparing(transition -> transition.evidence().sourceCaptureId())
                        .thenComparing(transition -> transition.evidence().sourceRecordId()),
                "itemTransitions");
        var orderedEndItems = new LinkedHashMap<Integer, List<Integer>>();
        new TreeMap<>(Objects.requireNonNull(observedEndItems, "observedEndItems"))
                .forEach((participantId, items) -> {
                    if (participantId == null || participantId < 1 || participantId > 10) {
                        throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
                    }
                    orderedEndItems.put(
                            participantId,
                            List.copyOf(Objects.requireNonNull(items, "endItems")));
                });
        observedEndItems = Collections.unmodifiableMap(orderedEndItems);
        coverage = sortedCopy(coverage, SourceCoverage.STABLE_ORDER, "coverage");
    }

    private static <T> List<T> sortedCopy(
            List<T> values, Comparator<? super T> order, String name) {
        return Objects.requireNonNull(values, name).stream()
                .map(value -> Objects.requireNonNull(value, name + " element"))
                .sorted(order)
                .toList();
    }
}
