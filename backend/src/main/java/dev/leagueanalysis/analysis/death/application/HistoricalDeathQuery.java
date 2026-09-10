package dev.leagueanalysis.analysis.death.application;

import dev.leagueanalysis.analysis.death.domain.DeathEvent;
import dev.leagueanalysis.analysis.death.domain.ObjectiveEvent;
import dev.leagueanalysis.analysis.death.domain.PriorParticipantObservation;
import dev.leagueanalysis.evidence.domain.ItemTransition;
import dev.leagueanalysis.evidence.domain.MatchSourceRevision;
import dev.leagueanalysis.evidence.domain.SourceCoverage;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public interface HistoricalDeathQuery {
    default <T> T inReadSnapshot(Supplier<T> work) {
        return Objects.requireNonNull(work, "work").get();
    }

    Optional<MatchSourceRevision> findRevision(String matchId);

    List<Integer> findParticipantIds(String matchId);

    List<DeathEvent> findDeaths(String matchId);

    Optional<DeathEvent> findDeath(String matchId, long frameAtMs, int frameEventIndex);

    List<PriorParticipantObservation> findLatestPriorObservations(
            String matchId, long representedBeforeMs);

    List<ItemTransition> findItemTransitionsBefore(
            String matchId, TimelineEventKey exclusiveUpperBound);

    List<ItemTransition> findAllItemTransitions(String matchId);

    Map<Integer, List<Integer>> findObservedEndItems(String matchId);

    List<ObjectiveEvent> findObjectives(
            String matchId, long representedStartMs, long representedEndMs);

    List<SourceCoverage> findCoverage(String matchId, Set<String> signals);
}
