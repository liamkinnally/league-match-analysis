package dev.leagueanalysis.analysis.death.application;

import dev.leagueanalysis.analysis.death.domain.DeathContext;
import dev.leagueanalysis.analysis.death.domain.DeathEvent;
import dev.leagueanalysis.analysis.death.domain.ObjectiveEvent;
import dev.leagueanalysis.analysis.death.domain.ObjectiveRelationship;
import dev.leagueanalysis.analysis.death.domain.ParticipantStateAtDeath;
import dev.leagueanalysis.analysis.death.domain.PriorParticipantObservation;
import dev.leagueanalysis.analysis.death.domain.TemporalRelation;
import dev.leagueanalysis.evidence.application.InventoryProjector;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.InventoryAmbiguity;
import dev.leagueanalysis.evidence.domain.InventorySnapshot;
import dev.leagueanalysis.evidence.domain.ItemTransition;
import dev.leagueanalysis.evidence.domain.SourceCoverage;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class DeathContextService {
    public static final String OBJECTIVE_RELATIONSHIP_RULE_VERSION =
            "bounded-objective-relationship-v1";

    private static final Set<String> REQUIRED_COVERAGE_SIGNALS = Set.of(
            "match_roster_result_patch",
            "participant_positions",
            "economy_snapshots",
            "item_transitions",
            "match_events");

    private final HistoricalDeathQuery query;
    private final ParticipantStateProjector participantStateProjector;
    private final InventoryProjector inventoryProjector;

    public DeathContextService(
            HistoricalDeathQuery query,
            ParticipantStateProjector participantStateProjector,
            InventoryProjector inventoryProjector) {
        this.query = Objects.requireNonNull(query, "query");
        this.participantStateProjector = Objects.requireNonNull(
                participantStateProjector, "participantStateProjector");
        this.inventoryProjector = Objects.requireNonNull(inventoryProjector, "inventoryProjector");
    }

    public Optional<DeathContext> analyze(DeathContextRequest request) {
        var requiredRequest = Objects.requireNonNull(request, "request");
        return query.inReadSnapshot(() -> analyzeInReadSnapshot(requiredRequest));
    }

    private Optional<DeathContext> analyzeInReadSnapshot(DeathContextRequest requiredRequest) {
        var revision = query.findRevision(requiredRequest.matchId());
        if (revision.isEmpty()) {
            return Optional.empty();
        }
        var death = query.findDeath(
                requiredRequest.matchId(),
                requiredRequest.deathFrameAtMs(),
                requiredRequest.deathFrameEventIndex());
        if (death.isEmpty()) {
            return Optional.empty();
        }
        var selectedRevision = revision.orElseThrow();
        var selectedDeath = death.orElseThrow();
        if (!selectedRevision.matchId().equals(requiredRequest.matchId())
                || !selectedDeath.key().matchId().equals(requiredRequest.matchId())) {
            throw new IllegalArgumentException("MISMATCHED_DEATH_CONTEXT_MATCH");
        }

        var participantIds = query.findParticipantIds(requiredRequest.matchId());
        var observations = query.findLatestPriorObservations(
                requiredRequest.matchId(),
                selectedDeath.key().representedAtMs());
        var participantStates = participantStateProjector.project(
                selectedRevision,
                participantIds,
                selectedDeath.key().representedAtMs(),
                requiredRequest.maxPriorObservationAgeMs(),
                observations);

        var transitionsBeforeDeath = query.findItemTransitionsBefore(
                requiredRequest.matchId(), selectedDeath.key());
        var transitions = query.findAllItemTransitions(requiredRequest.matchId());
        var inventoryProjection = inventoryProjector.reduce(
                participantIds,
                transitionsBeforeDeath,
                transitions,
                selectedDeath.key(),
                query.findObservedEndItems(requiredRequest.matchId()));

        var objectiveStartMs = subtractFloorZero(
                selectedDeath.key().representedAtMs(), requiredRequest.objectiveLookbackMs());
        var objectiveEndMs = addCeilingLongMax(
                selectedDeath.key().representedAtMs(), requiredRequest.objectiveLookaheadMs());
        var objectives = query.findObjectives(
                        requiredRequest.matchId(), objectiveStartMs, objectiveEndMs).stream()
                .filter(objective -> objective.key().matchId().equals(requiredRequest.matchId()))
                .filter(objective -> objective.key().representedAtMs() >= objectiveStartMs
                        && objective.key().representedAtMs() <= objectiveEndMs)
                .toList();
        var objectiveGrouping = groupObjectives(selectedDeath.key().representedAtMs(), objectives);
        var inputCoverage = query.findCoverage(requiredRequest.matchId(), REQUIRED_COVERAGE_SIGNALS);
        var limitations = aggregateLimitations(
                participantIds.size(),
                selectedDeath.assistingParticipantIdsObserved(),
                participantStates,
                inventoryProjection.inventories(),
                inventoryProjection.ambiguities(),
                inputCoverage,
                objectiveGrouping.ambiguous());
        limitations.addAll(selectedDeath.limitationCodes());
        var ruleVersions = aggregateRuleVersions(participantStates, inventoryProjection.inventories());
        var evidenceReferences = aggregateEvidenceReferences(
                selectedDeath, observations, transitions, objectives);

        return Optional.of(new DeathContext(
                selectedDeath,
                selectedRevision,
                participantStates,
                inventoryProjection.inventories(),
                inventoryProjection.ambiguities(),
                objectiveGrouping.relationships(),
                inputCoverage,
                ruleVersions,
                limitations,
                evidenceReferences));
    }

    private ObjectiveRelationship relationship(long deathRepresentedAtMs, ObjectiveEvent objective) {
        var deltaMs = objective.key().representedAtMs() - deathRepresentedAtMs;
        var relation = deltaMs < 0
                ? TemporalRelation.BEFORE
                : deltaMs == 0 ? TemporalRelation.SAME_TIME : TemporalRelation.AFTER;
        return new ObjectiveRelationship(objective, relation, deltaMs);
    }

    private ObjectiveGrouping groupObjectives(
            long deathRepresentedAtMs, List<ObjectiveEvent> objectives) {
        var byKey = new TreeMap<TimelineEventKey, List<ObjectiveEvent>>();
        for (var objective : objectives) {
            byKey.computeIfAbsent(objective.key(), ignored -> new ArrayList<>()).add(objective);
        }
        var relationships = new ArrayList<ObjectiveRelationship>();
        var ambiguous = false;
        for (var group : byKey.values()) {
            var ordered = group.stream().sorted(objectiveEvidenceOrder()).toList();
            var representative = ordered.getFirst();
            if (ordered.stream().allMatch(objective -> sameObjectiveContent(representative, objective))) {
                relationships.add(relationship(deathRepresentedAtMs, representative));
            } else {
                ambiguous = true;
            }
        }
        return new ObjectiveGrouping(relationships, ambiguous);
    }

    private Comparator<ObjectiveEvent> objectiveEvidenceOrder() {
        return Comparator.comparingLong((ObjectiveEvent objective) -> objective.evidence().representedAtMs())
                .thenComparing(objective -> objective.evidence().sourceCaptureId())
                .thenComparing(objective -> objective.evidence().sourceRecordId())
                .thenComparing(objective -> objective.evidence().methodVersion());
    }

    private boolean sameObjectiveContent(ObjectiveEvent left, ObjectiveEvent right) {
        return Objects.equals(left.actorParticipantId(), right.actorParticipantId())
                && Objects.equals(left.teamId(), right.teamId())
                && Objects.equals(left.objectiveDescriptor(), right.objectiveDescriptor())
                && Objects.equals(left.positionX(), right.positionX())
                && Objects.equals(left.positionY(), right.positionY());
    }

    private Set<String> aggregateLimitations(
            int participantCount,
            boolean assistingParticipantIdsObserved,
            List<ParticipantStateAtDeath> participantStates,
            List<InventorySnapshot> inventories,
            List<InventoryAmbiguity> inventoryAmbiguities,
            List<SourceCoverage> coverageRecords,
            boolean ambiguousObjectiveEvent) {
        var limitations = new TreeSet<String>();
        participantStates.forEach(state -> limitations.addAll(state.limitationCodes()));
        inventories.forEach(inventory -> limitations.addAll(inventory.limitationCodes()));
        inventoryAmbiguities.forEach(ambiguity -> limitations.add(ambiguity.code()));
        if (participantCount != 10) {
            limitations.add("INCOMPLETE_ROSTER");
        }
        if (!assistingParticipantIdsObserved) {
            limitations.add("ASSISTING_PARTICIPANT_IDS_UNAVAILABLE");
        }
        if (ambiguousObjectiveEvent) {
            limitations.add("AMBIGUOUS_OBJECTIVE_EVENT");
        }
        var coverageStatusesBySignal = new TreeMap<String, Set<dev.leagueanalysis.evidence.domain.CoverageStatus>>();
        for (var coverage : coverageRecords) {
            coverageStatusesBySignal
                    .computeIfAbsent(coverage.signal(), ignored -> new TreeSet<>())
                    .add(coverage.status());
        }
        REQUIRED_COVERAGE_SIGNALS.stream()
                .filter(signal -> !coverageStatusesBySignal.containsKey(signal))
                .map(signal -> "INPUT_COVERAGE_MISSING_" + signal.toUpperCase(Locale.ROOT))
                .forEach(limitations::add);
        coverageStatusesBySignal.forEach((signal, statuses) -> {
            if (statuses.size() > 1) {
                limitations.add("CONFLICTING_INPUT_COVERAGE_" + signal.toUpperCase(Locale.ROOT));
            }
        });
        return limitations;
    }

    private Set<String> aggregateRuleVersions(
            List<ParticipantStateAtDeath> participantStates,
            List<InventorySnapshot> inventories) {
        var versions = new TreeSet<String>();
        versions.add(ParticipantStateProjector.LATEST_PRIOR_RULE_VERSION);
        versions.add(OBJECTIVE_RELATIONSHIP_RULE_VERSION);
        participantStates.forEach(state -> versions.add(state.ruleVersion()));
        inventories.forEach(inventory -> versions.add(inventory.ruleVersion()));
        return versions;
    }

    private List<EvidenceReference> aggregateEvidenceReferences(
            DeathEvent death,
            List<PriorParticipantObservation> observations,
            List<ItemTransition> transitions,
            List<ObjectiveEvent> objectives) {
        var references = new ArrayList<EvidenceReference>();
        references.addAll(death.evidenceReferences());
        observations.stream().map(PriorParticipantObservation::evidence).forEach(references::add);
        transitions.stream().flatMap(transition -> transition.evidenceReferences().stream()).forEach(references::add);
        objectives.stream().map(ObjectiveEvent::evidence).forEach(references::add);
        return references;
    }

    private record ObjectiveGrouping(
            List<ObjectiveRelationship> relationships, boolean ambiguous) {
        private ObjectiveGrouping {
            relationships = List.copyOf(relationships);
        }
    }

    private long subtractFloorZero(long value, long amount) {
        return amount >= value ? 0 : value - amount;
    }

    private long addCeilingLongMax(long value, long amount) {
        return amount > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + amount;
    }
}
