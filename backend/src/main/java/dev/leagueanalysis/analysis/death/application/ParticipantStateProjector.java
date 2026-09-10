package dev.leagueanalysis.analysis.death.application;

import dev.leagueanalysis.analysis.death.domain.CoarseMapRegion;
import dev.leagueanalysis.analysis.death.domain.ParticipantStateAtDeath;
import dev.leagueanalysis.analysis.death.domain.PriorParticipantObservation;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.MatchSourceRevision;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ParticipantStateProjector {
    public static final String LATEST_PRIOR_RULE_VERSION =
            "latest-strictly-prior-participant-observation-v1";

    private final CoarseMapProjector coarseMapProjector;

    public ParticipantStateProjector(CoarseMapProjector coarseMapProjector) {
        this.coarseMapProjector = Objects.requireNonNull(coarseMapProjector, "coarseMapProjector");
    }

    public List<ParticipantStateAtDeath> project(
            MatchSourceRevision revision,
            List<Integer> participantIds,
            long deathRepresentedAtMs,
            long maxPriorObservationAgeMs,
            List<PriorParticipantObservation> observations) {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(participantIds, "participantIds");
        Objects.requireNonNull(observations, "observations");
        if (deathRepresentedAtMs < 0 || maxPriorObservationAgeMs < 0) {
            throw new IllegalArgumentException("NEGATIVE_OBSERVATION_BOUND");
        }

        var observationsByParticipant = observationsByParticipant(observations);
        return participantIds.stream()
                .map(participantId -> projectParticipant(
                        revision,
                        requireParticipantId(participantId),
                        deathRepresentedAtMs,
                        maxPriorObservationAgeMs,
                        observationsByParticipant.get(participantId)))
                .toList();
    }

    private Map<Integer, List<PriorParticipantObservation>> observationsByParticipant(
            List<PriorParticipantObservation> observations) {
        var byParticipant = new HashMap<Integer, List<PriorParticipantObservation>>();
        for (var observation : observations) {
            var nonNullObservation = Objects.requireNonNull(observation, "observation");
            byParticipant.merge(
                    nonNullObservation.participantId(),
                    List.of(nonNullObservation),
                    (existing, added) -> {
                        var combined = new java.util.ArrayList<>(existing);
                        combined.addAll(added);
                        return List.copyOf(combined);
                    });
        }
        return Map.copyOf(byParticipant);
    }

    private ParticipantStateAtDeath projectParticipant(
            MatchSourceRevision revision,
            int participantId,
            long deathRepresentedAtMs,
            long maxPriorObservationAgeMs,
            List<PriorParticipantObservation> suppliedObservations) {
        var candidates = suppliedObservations == null ? List.<PriorParticipantObservation>of() : suppliedObservations;
        var strictlyPriorObservations = candidates.stream()
                .filter(observation -> observation.evidence().representedAtMs() < deathRepresentedAtMs)
                .toList();
        if (!strictlyPriorObservations.isEmpty()) {
            var latestRepresentedAtMs = strictlyPriorObservations.stream()
                    .mapToLong(observation -> observation.evidence().representedAtMs())
                    .max()
                    .orElseThrow();
            var latestObservations = strictlyPriorObservations.stream()
                    .filter(observation -> observation.evidence().representedAtMs() == latestRepresentedAtMs)
                    .toList();
            var evidenceReferences = latestObservations.stream()
                    .map(PriorParticipantObservation::evidence)
                    .toList();
            var distinctContent = latestObservations.stream()
                    .map(ObservationContent::from)
                    .distinct()
                    .toList();
            if (distinctContent.size() > 1) {
                return gapState(participantId, "AMBIGUOUS_PRIOR_OBSERVATION", evidenceReferences);
            }
            var latestObservation = latestObservations.getFirst();
            if (!hasValidCounters(latestObservation)) {
                return gapState(participantId, "INVALID_OBSERVATION_COUNTER", evidenceReferences);
            }
            if (deathRepresentedAtMs - latestRepresentedAtMs > maxPriorObservationAgeMs) {
                return gapState(participantId, "OBSERVATION_TOO_OLD", evidenceReferences);
            }
            return observedState(
                    revision, latestObservation, deathRepresentedAtMs, evidenceReferences);
        }
        return gapState(participantId, candidates, deathRepresentedAtMs);
    }

    private boolean hasValidCounters(PriorParticipantObservation observation) {
        return observation.currentGold() >= 0
                && observation.totalGold() >= 0
                && observation.level() >= 0
                && observation.xp() >= 0
                && observation.minionsKilled() >= 0
                && observation.jungleMinionsKilled() >= 0;
    }

    private ParticipantStateAtDeath observedState(
            MatchSourceRevision revision,
            PriorParticipantObservation observation,
            long deathRepresentedAtMs,
            List<EvidenceReference> evidenceReferences) {
        var projection = coarseMapProjector.project(revision.mapId(), observation.x(), observation.y());
        var coverageStatus = projection.limitationCodes().isEmpty()
                ? CoverageStatus.OBSERVED
                : CoverageStatus.LIMITED;
        return new ParticipantStateAtDeath(
                observation.participantId(),
                observation.evidence().representedAtMs(),
                deathRepresentedAtMs - observation.evidence().representedAtMs(),
                observation.currentGold(),
                observation.totalGold(),
                observation.level(),
                observation.xp(),
                observation.minionsKilled(),
                observation.jungleMinionsKilled(),
                observation.x(),
                observation.y(),
                projection.region(),
                evidenceReferences,
                coverageStatus,
                projection.ruleVersion(),
                projection.limitationCodes());
    }

    private ParticipantStateAtDeath gapState(
            int participantId,
            List<PriorParticipantObservation> suppliedObservations,
            long deathRepresentedAtMs) {
        var limitationCode = suppliedObservations.stream()
                .anyMatch(observation -> observation.evidence().representedAtMs() >= deathRepresentedAtMs)
                ? "OBSERVATION_NOT_STRICTLY_PRIOR"
                : "NO_PRIOR_OBSERVATION";
        return gapState(
                participantId,
                limitationCode,
                suppliedObservations.stream().map(PriorParticipantObservation::evidence).toList());
    }

    private ParticipantStateAtDeath gapState(int participantId, String limitationCode) {
        return gapState(participantId, limitationCode, List.of());
    }

    private ParticipantStateAtDeath gapState(
            int participantId,
            String limitationCode,
            List<EvidenceReference> evidenceReferences) {
        return new ParticipantStateAtDeath(
                participantId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                CoarseMapRegion.UNKNOWN,
                evidenceReferences,
                CoverageStatus.LIMITED,
                CoarseMapProjector.RULE_VERSION,
                Set.of(limitationCode));
    }

    private int requireParticipantId(Integer participantId) {
        if (participantId == null || participantId < 1) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
        return participantId;
    }

    private record ObservationContent(
            int participantId,
            Integer x,
            Integer y,
            int currentGold,
            int totalGold,
            int level,
            int xp,
            int minionsKilled,
            int jungleMinionsKilled) {
        private static ObservationContent from(PriorParticipantObservation observation) {
            return new ObservationContent(
                    observation.participantId(),
                    observation.x(),
                    observation.y(),
                    observation.currentGold(),
                    observation.totalGold(),
                    observation.level(),
                    observation.xp(),
                    observation.minionsKilled(),
                    observation.jungleMinionsKilled());
        }
    }
}
