package dev.leagueanalysis.analysis.death.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.leagueanalysis.analysis.death.domain.DeathContext;
import dev.leagueanalysis.analysis.death.domain.DeathEvent;
import dev.leagueanalysis.analysis.death.domain.ObjectiveEvent;
import dev.leagueanalysis.analysis.death.domain.ParticipantStateAtDeath;
import dev.leagueanalysis.analysis.death.domain.PriorParticipantObservation;
import dev.leagueanalysis.analysis.death.domain.TemporalRelation;
import dev.leagueanalysis.evidence.application.InventoryProjector;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.InventorySnapshot;
import dev.leagueanalysis.evidence.domain.ItemTransition;
import dev.leagueanalysis.evidence.domain.MatchSourceRevision;
import dev.leagueanalysis.evidence.domain.SourceCoverage;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DeathContextServiceTest {
    private static final String MATCH_ID = "NA1_1";
    private static final TimelineEventKey DEATH_KEY = key(1_000, 1_100, 3, "CHAMPION_KILL");
    private static final MatchSourceRevision REVISION = new MatchSourceRevision(
            MATCH_ID, 11, uuid(1), uuid(2), "materializer-v2");

    @Test
    void composesPartialEvidenceWithoutChangingIdentityCoverageOrReconciliation() {
        var query = completeFake();
        var service = service(query);

        var result = service.analyze(new DeathContextRequest(MATCH_ID, 1_100, 3, 100, 200, 300))
                .orElseThrow();

        assertThat(result.death()).isSameAs(query.death.orElseThrow());
        assertThat(result.death().key()).isEqualTo(DEATH_KEY);
        assertThat(result.death().evidence()).isEqualTo(evidence(10, 1_000));
        assertThat(result.sourceRevision()).isSameAs(REVISION);

        assertThat(query.priorRepresentedBeforeMs).isEqualTo(1_000);
        assertThat(result.participantStates()).extracting(ParticipantStateAtDeath::participantId)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(result.participantStates().get(0).observationAgeMs()).isEqualTo(50);
        assertThat(result.participantStates().get(0).coarseMapRegion().name()).isEqualTo("SOUTH_WEST");
        assertThat(result.participantStates().get(1).observationAgeMs()).isEqualTo(100);
        assertThat(result.participantStates().subList(2, 10))
                .allSatisfy(state -> {
                    assertThat(state.coverageStatus()).isEqualTo(CoverageStatus.LIMITED);
                    assertThat(state.limitationCodes()).containsExactly("NO_PRIOR_OBSERVATION");
                });

        assertThat(result.inventories()).extracting(InventorySnapshot::participantId)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(query.exclusiveInventoryBound).isEqualTo(DEATH_KEY);
        assertThat(result.inventories().get(0).itemQuantities())
                .containsExactlyEntriesOf(Map.of(1001, 1));
        assertThat(result.inventories().get(0).supportingEvidence())
                .containsExactly(evidence(20, 900));
        assertThat(result.inventories().get(1).itemQuantities()).isEmpty();
        assertThat(result.inventories().get(1).limitationCodes())
                .containsExactly("END_INVENTORY_MISMATCH");
        assertThat(result.inventoryAmbiguities()).hasSize(1);
        assertThat(result.inventoryAmbiguities().getFirst().code()).isEqualTo("ITEM_NOT_PRESENT");

        assertThat(query.objectiveStartMs).isEqualTo(800);
        assertThat(query.objectiveEndMs).isEqualTo(1_300);
        assertThat(result.objectiveRelationships())
                .extracting(relationship -> relationship.objective().objectiveDescriptor())
                .containsExactly("DRAGON", "HERALD", "BARON");
        assertThat(result.objectiveRelationships())
                .extracting(relationship -> relationship.temporalRelation())
                .containsExactly(TemporalRelation.BEFORE, TemporalRelation.SAME_TIME, TemporalRelation.AFTER);
        assertThat(result.objectiveRelationships())
                .extracting(relationship -> relationship.signedDeltaMs())
                .containsExactly(-200L, 0L, 300L);

        assertThat(result.inputCoverage()).containsExactly(
                coverage("economy_snapshots", "MATCH_TIMELINE", CoverageStatus.OBSERVED, 40),
                coverage("item_transitions", "MATCH_TIMELINE", CoverageStatus.LIMITED, 41),
                coverage("match_events", "MATCH_TIMELINE", CoverageStatus.ESTIMATED, 42),
                coverage("participant_positions", "MATCH_TIMELINE", CoverageStatus.UNAVAILABLE, 43));
        assertThat(result.ruleVersions())
                .containsExactly(
                        "bounded-objective-relationship-v1",
                        "latest-strictly-prior-participant-observation-v1",
                        "match-v5-item-reducer-v1",
                        "summoners-rift-grid-v1");
        assertThat(result.limitationCodes()).contains(
                "END_INVENTORY_MISMATCH",
                "INPUT_COVERAGE_MISSING_MATCH_ROSTER_RESULT_PATCH",
                "ITEM_NOT_PRESENT",
                "NO_PRIOR_OBSERVATION");
        assertThat(result.evidenceReferences()).contains(
                evidence(10, 1_000),
                evidence(11, 950),
                evidence(12, 900),
                evidence(20, 900),
                evidence(21, 1_000),
                evidence(22, 1_200),
                evidence(23, 1_400),
                evidence(31, 800),
                evidence(32, 1_000),
                evidence(33, 1_300));

        assertThatThrownBy(() -> result.participantStates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.inputCoverage().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.evidenceReferences().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void returnsEmptyForAnAbsentMatchOrExactDeath() {
        var absentMatch = new FakeQuery();
        assertThat(service(absentMatch).analyze(request())).isEmpty();

        var absentDeath = new FakeQuery();
        absentDeath.revision = Optional.of(REVISION);
        assertThat(service(absentDeath).analyze(request())).isEmpty();
    }

    @Test
    void performsEveryAnalysisReadInsideOneProviderSnapshotBoundary() {
        var query = completeFake();

        assertThat(service(query).analyze(request())).isPresent();

        assertThat(query.snapshotCalls).isEqualTo(1);
        assertThat(query.readOccurredOutsideSnapshot).isFalse();
    }

    @Test
    void surfacesPartialDeathLimitationsAndEveryContributingReference() {
        var query = completeFake();
        var firstEvidence = evidence(60, DEATH_KEY.representedAtMs());
        var secondEvidence = evidence(61, DEATH_KEY.representedAtMs());
        query.death = Optional.of(new DeathEvent(
                DEATH_KEY,
                null,
                null,
                List.of(),
                false,
                null,
                null,
                List.of(secondEvidence, firstEvidence),
                Set.of("AMBIGUOUS_DEATH_EVENT", "VICTIM_PARTICIPANT_ID_UNAVAILABLE")));

        var result = service(query).analyze(request()).orElseThrow();

        assertThat(result.death().victimParticipantId()).isNull();
        assertThat(result.death().evidenceReferences()).containsExactly(firstEvidence, secondEvidence);
        assertThat(result.limitationCodes()).contains(
                "AMBIGUOUS_DEATH_EVENT",
                "VICTIM_PARTICIPANT_ID_UNAVAILABLE",
                "ASSISTING_PARTICIPANT_IDS_UNAVAILABLE");
        assertThat(result.evidenceReferences()).contains(firstEvidence, secondEvidence);
    }

    @Test
    void retainsConflictingCoverageRecordsWithoutStrengtheningAndDoesNotCallTheSignalMissing() {
        var query = completeFake();
        var observed = coverage("match_events", "MATCH_TIMELINE", CoverageStatus.OBSERVED, 70);
        var limited = coverage("match_events", "MATCH_DETAIL", CoverageStatus.LIMITED, 71);
        query.coverage = List.of(observed, limited);

        var result = service(query).analyze(request()).orElseThrow();

        assertThat(result.inputCoverage()).containsExactly(limited, observed);
        assertThat(result.limitationCodes()).contains("CONFLICTING_INPUT_COVERAGE_MATCH_EVENTS");
        assertThat(result.limitationCodes()).doesNotContain("INPUT_COVERAGE_MISSING_MATCH_EVENTS");
        assertThat(result.limitationCodes()).contains(
                "INPUT_COVERAGE_MISSING_ECONOMY_SNAPSHOTS",
                "INPUT_COVERAGE_MISSING_ITEM_TRANSITIONS",
                "INPUT_COVERAGE_MISSING_MATCH_ROSTER_RESULT_PATCH",
                "INPUT_COVERAGE_MISSING_PARTICIPANT_POSITIONS");
    }

    @Test
    void zeroWindowsSelectOnlySameTimeObjectivesAndRemainExplicit() {
        var query = completeFake();

        var result = service(query).analyze(new DeathContextRequest(MATCH_ID, 1_100, 3, 0, 0, 0))
                .orElseThrow();

        assertThat(query.objectiveStartMs).isEqualTo(1_000);
        assertThat(query.objectiveEndMs).isEqualTo(1_000);
        assertThat(result.objectiveRelationships()).hasSize(1);
        assertThat(result.objectiveRelationships().getFirst().temporalRelation())
                .isEqualTo(TemporalRelation.SAME_TIME);
    }

    @Test
    void collapsesDuplicateObjectiveCapturesByLogicalKeyInEitherInputOrder() {
        var logicalKey = key(900, 900, 7, "ELITE_MONSTER_KILL");
        var selected = objective(logicalKey, 1, 100, "DRAGON", 10, 20, 50);
        var duplicate = objective(logicalKey, 1, 100, "DRAGON", 10, 20, 51);

        var firstOrder = analyzeWithObjectives(List.of(duplicate, selected));
        var secondOrder = analyzeWithObjectives(List.of(selected, duplicate));

        assertThat(firstOrder.objectiveRelationships()).isEqualTo(secondOrder.objectiveRelationships());
        assertThat(firstOrder.objectiveRelationships()).hasSize(1);
        assertThat(firstOrder.objectiveRelationships().getFirst().objective()).isEqualTo(selected);
        assertThat(firstOrder.objectiveRelationships().getFirst().temporalRelation())
                .isEqualTo(TemporalRelation.BEFORE);
        assertThat(firstOrder.objectiveRelationships().getFirst().signedDeltaMs()).isEqualTo(-100);
        assertThat(firstOrder.limitationCodes()).doesNotContain("AMBIGUOUS_OBJECTIVE_EVENT");
        assertThat(firstOrder.evidenceReferences()).contains(selected.evidence(), duplicate.evidence());
        assertThat(firstOrder.evidenceReferences()).isEqualTo(secondOrder.evidenceReferences());
    }

    @Test
    void suppressesConflictingSameKeyObjectivesInEitherInputOrderAndRetainsAllEvidence() {
        var logicalKey = key(1_100, 1_100, 8, "BUILDING_KILL");
        var first = objective(logicalKey, 1, 100, "TOWER_BUILDING:MID_LANE", 10, 20, 52);
        var conflictingRows = List.of(
                objective(logicalKey, 2, 100, "TOWER_BUILDING:MID_LANE", 10, 20, 53),
                objective(logicalKey, 1, 200, "TOWER_BUILDING:MID_LANE", 10, 20, 54),
                objective(logicalKey, 1, 100, "TOWER_BUILDING:TOP_LANE", 10, 20, 55),
                objective(logicalKey, 1, 100, "TOWER_BUILDING:MID_LANE", 11, 20, 56),
                objective(logicalKey, 1, 100, "TOWER_BUILDING:MID_LANE", 10, 21, 57));

        for (var conflicting : conflictingRows) {
            var firstOrder = analyzeWithObjectives(List.of(conflicting, first));
            var secondOrder = analyzeWithObjectives(List.of(first, conflicting));

            assertThat(firstOrder.objectiveRelationships()).isEmpty();
            assertThat(firstOrder.objectiveRelationships()).isEqualTo(secondOrder.objectiveRelationships());
            assertThat(firstOrder.limitationCodes()).contains("AMBIGUOUS_OBJECTIVE_EVENT");
            assertThat(firstOrder.limitationCodes()).isEqualTo(secondOrder.limitationCodes());
            assertThat(firstOrder.evidenceReferences()).contains(first.evidence(), conflicting.evidence());
            assertThat(firstOrder.evidenceReferences()).isEqualTo(secondOrder.evidenceReferences());
        }
    }

    private static DeathContext analyzeWithObjectives(List<ObjectiveEvent> objectives) {
        var query = completeFake();
        query.objectives = objectives;
        return service(query).analyze(request()).orElseThrow();
    }

    private static DeathContextService service(HistoricalDeathQuery query) {
        return new DeathContextService(
                query,
                new ParticipantStateProjector(new CoarseMapProjector()),
                new InventoryProjector());
    }

    private static DeathContextRequest request() {
        return new DeathContextRequest(MATCH_ID, 1_100, 3, 100, 200, 300);
    }

    private static FakeQuery completeFake() {
        var query = new FakeQuery();
        query.revision = Optional.of(REVISION);
        query.death = Optional.of(new DeathEvent(
                DEATH_KEY, 3, 4, List.of(5), true, 5_000, 5_000, evidence(10, 1_000)));
        query.participantIds = List.of(10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
        query.observations = List.of(
                observation(2, 900, 12),
                observation(1, 950, 11));
        query.transitions = List.of(
                transition(1_400, 1_400, 1, 10, "ITEM_SOLD", 9000, 23),
                transition(1_200, 1_200, 1, 1, "ITEM_PURCHASED", 3006, 22),
                transition(1_000, 1_100, 3, 1, "ITEM_PURCHASED", 2003, 21),
                transition(900, 900, 1, 1, "ITEM_PURCHASED", 1001, 20));
        var endItems = new LinkedHashMap<Integer, List<Integer>>();
        for (int participantId = 1; participantId <= 10; participantId++) {
            endItems.put(participantId, List.of(0, 0, 0, 0, 0, 0, 0));
        }
        endItems.put(1, List.of(1001, 2003, 3006, 0, 0, 0, 0));
        endItems.put(2, List.of(3006, 0, 0, 0, 0, 0, 0));
        query.endItems = endItems;
        query.objectives = List.of(
                objective(1_301, 44, "OUTSIDE_AFTER"),
                objective(1_300, 33, "BARON"),
                objective(799, 30, "OUTSIDE_BEFORE"),
                objective(1_000, 32, "HERALD"),
                objective(800, 31, "DRAGON"));
        query.coverage = List.of(
                coverage("participant_positions", "MATCH_TIMELINE", CoverageStatus.UNAVAILABLE, 43),
                coverage("match_events", "MATCH_TIMELINE", CoverageStatus.ESTIMATED, 42),
                coverage("item_transitions", "MATCH_TIMELINE", CoverageStatus.LIMITED, 41),
                coverage("economy_snapshots", "MATCH_TIMELINE", CoverageStatus.OBSERVED, 40));
        return query;
    }

    private static SourceCoverage coverage(
            String signal, String sourceKind, CoverageStatus status, int evidenceId) {
        var captureId = status == CoverageStatus.UNKNOWN || status == CoverageStatus.UNAVAILABLE
                ? null
                : uuid(300 + evidenceId);
        return new SourceCoverage(
                signal,
                sourceKind,
                status,
                0L,
                5_000L,
                uuid(400 + evidenceId),
                captureId,
                "timeline-v1");
    }

    private static PriorParticipantObservation observation(int participantId, long atMs, int evidenceId) {
        return new PriorParticipantObservation(
                participantId, participantId, participantId, 100, 200, 3, 400, 5, 6,
                evidence(evidenceId, atMs));
    }

    private static ItemTransition transition(
            long representedAtMs,
            long frameAtMs,
            int eventIndex,
            int participantId,
            String type,
            Integer itemId,
            int evidenceId) {
        return new ItemTransition(
                key(representedAtMs, frameAtMs, eventIndex, type),
                participantId,
                itemId,
                null,
                null,
                evidence(evidenceId, representedAtMs));
    }

    private static ObjectiveEvent objective(long representedAtMs, int evidenceId, String descriptor) {
        return new ObjectiveEvent(
                key(representedAtMs, representedAtMs, evidenceId, "OBJECTIVE_" + descriptor),
                1,
                100,
                descriptor,
                null,
                null,
                evidence(evidenceId, representedAtMs));
    }

    private static ObjectiveEvent objective(
            TimelineEventKey key,
            Integer actorParticipantId,
            Integer teamId,
            String descriptor,
            Integer x,
            Integer y,
            int evidenceId) {
        return new ObjectiveEvent(
                key,
                actorParticipantId,
                teamId,
                descriptor,
                x,
                y,
                evidence(evidenceId, key.representedAtMs()));
    }

    private static TimelineEventKey key(
            long representedAtMs, long frameAtMs, int eventIndex, String providerType) {
        return new TimelineEventKey(MATCH_ID, representedAtMs, frameAtMs, eventIndex, providerType);
    }

    private static EvidenceReference evidence(int id, long representedAtMs) {
        return new EvidenceReference(uuid(100 + id), uuid(200 + id), representedAtMs, "timeline-v1");
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private static final class FakeQuery implements HistoricalDeathQuery {
        private Optional<MatchSourceRevision> revision = Optional.empty();
        private Optional<DeathEvent> death = Optional.empty();
        private List<Integer> participantIds = List.of();
        private List<PriorParticipantObservation> observations = List.of();
        private List<ItemTransition> transitions = List.of();
        private Map<Integer, List<Integer>> endItems = Map.of();
        private List<ObjectiveEvent> objectives = List.of();
        private List<SourceCoverage> coverage = List.of();
        private long priorRepresentedBeforeMs = -1;
        private long objectiveStartMs = -1;
        private long objectiveEndMs = -1;
        private TimelineEventKey exclusiveInventoryBound;
        private int snapshotCalls;
        private boolean snapshotActive;
        private boolean readOccurredOutsideSnapshot;

        @Override
        public <T> T inReadSnapshot(Supplier<T> work) {
            snapshotCalls++;
            snapshotActive = true;
            try {
                return work.get();
            } finally {
                snapshotActive = false;
            }
        }

        @Override
        public Optional<MatchSourceRevision> findRevision(String matchId) {
            recordRead();
            return revision;
        }

        @Override
        public List<Integer> findParticipantIds(String matchId) {
            recordRead();
            return participantIds;
        }

        @Override
        public List<DeathEvent> findDeaths(String matchId) {
            recordRead();
            return death.stream().toList();
        }

        @Override
        public Optional<DeathEvent> findDeath(String matchId, long frameAtMs, int frameEventIndex) {
            recordRead();
            return death;
        }

        @Override
        public List<PriorParticipantObservation> findLatestPriorObservations(
                String matchId, long representedBeforeMs) {
            recordRead();
            priorRepresentedBeforeMs = representedBeforeMs;
            return observations;
        }

        @Override
        public List<ItemTransition> findItemTransitionsBefore(
                String matchId, TimelineEventKey exclusiveUpperBound) {
            recordRead();
            exclusiveInventoryBound = exclusiveUpperBound;
            return transitions.stream()
                    .filter(transition -> transition.key().compareTo(exclusiveUpperBound) < 0)
                    .toList();
        }

        @Override
        public List<ItemTransition> findAllItemTransitions(String matchId) {
            recordRead();
            return transitions;
        }

        @Override
        public Map<Integer, List<Integer>> findObservedEndItems(String matchId) {
            recordRead();
            return endItems;
        }

        @Override
        public List<ObjectiveEvent> findObjectives(
                String matchId, long representedStartMs, long representedEndMs) {
            recordRead();
            objectiveStartMs = representedStartMs;
            objectiveEndMs = representedEndMs;
            return new ArrayList<>(objectives);
        }

        @Override
        public List<SourceCoverage> findCoverage(String matchId, Set<String> signals) {
            recordRead();
            return coverage;
        }

        private void recordRead() {
            if (!snapshotActive) {
                readOccurredOutsideSnapshot = true;
            }
        }
    }
}
