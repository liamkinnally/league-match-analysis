package dev.leagueanalysis.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.InventorySnapshot;
import dev.leagueanalysis.evidence.domain.ItemTransition;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InventoryProjectorTest {
    private static final String MATCH_ID = "NA1_1";
    private static final TimelineEventKey DEATH_KEY = key(1_000, 1_000, 99, "CHAMPION_KILL");
    private final InventoryProjector reducer = new InventoryProjector();

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedTransitionCases")
    void appliesEachSupportedTransitionAsOneExactItem(String ignoredName, List<ItemTransition> transitions,
            Map<Integer, Integer> expectedQuantities) {
        var result = reducer.reduce(List.of(1), transitions, DEATH_KEY);

        var inventory = result.inventories().getFirst();
        assertThat(inventory.participantId()).isEqualTo(1);
        assertThat(inventory.itemQuantities()).containsExactlyEntriesOf(expectedQuantities);
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(inventory.ruleVersion()).isEqualTo("match-v5-item-reducer-v1");
        assertThat(inventory.supportingEvidence())
                .containsExactlyElementsOf(transitions.stream().map(ItemTransition::evidence).toList());
        assertThat(inventory.ambiguities()).isEmpty();
        assertThat(inventory.limitationCodes()).isEmpty();
        assertThat(result.ambiguities()).isEmpty();
    }

    static Stream<Arguments> supportedTransitionCases() {
        return Stream.of(
                Arguments.of("purchase adds the exact id",
                        List.of(transition(1, 1, "ITEM_PURCHASED", 1001, null, null)),
                        Map.of(1001, 1)),
                Arguments.of("repeated purchases retain multiset quantity",
                        List.of(
                                transition(1, 1, "ITEM_PURCHASED", 1001, null, null),
                                transition(2, 1, "ITEM_PURCHASED", 1001, null, null)),
                        Map.of(1001, 2)),
                Arguments.of("sale removes one exact id",
                        List.of(
                                transition(1, 1, "ITEM_PURCHASED", 1001, null, null),
                                transition(2, 1, "ITEM_SOLD", 1001, null, null)),
                        Map.of()),
                Arguments.of("destroy removes one exact component id",
                        List.of(
                                transition(1, 1, "ITEM_PURCHASED", 1001, null, null),
                                transition(2, 1, "ITEM_DESTROYED", 1001, null, null)),
                        Map.of()),
                Arguments.of("purchase undo removes beforeId",
                        List.of(
                                transition(1, 1, "ITEM_PURCHASED", 1001, null, null),
                                transition(2, 1, "ITEM_UNDO", null, 1001, 0)),
                        Map.of()),
                Arguments.of("sale undo adds afterId",
                        List.of(transition(1, 1, "ITEM_UNDO", null, 0, 1001)),
                        Map.of(1001, 1)));
    }

    @Test
    void sortsInputByLogicalKeyAndExcludesTheDeathKeyAndEveryLaterTransition() {
        var beforePurchase = transitionAt(1_000, 1_000, 1, 1, "ITEM_PURCHASED", 1001, null, null);
        var beforeSale = transitionAt(1_000, 1_000, 2, 1, "ITEM_SOLD", 1001, null, null);
        var sameOrderPosition = transitionAt(1_000, 1_000, 3, 1, "ITEM_PURCHASED", 2003, null, null);
        var later = transitionAt(1_001, 1_001, 0, 1, "ITEM_PURCHASED", 3006, null, null);
        var death = key(1_000, 1_000, 3, "CHAMPION_KILL");

        var result = reducer.reduce(
                List.of(1), List.of(later, sameOrderPosition, beforeSale, beforePurchase), death);

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).isEmpty();
        assertThat(inventory.supportingEvidence())
                .containsExactly(beforePurchase.evidence(), beforeSale.evidence());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ambiguousTransitionCases")
    void retainsAmbiguousKnownParticipantEventsWithoutChangingCurrentState(
            String ignoredName, ItemTransition ambiguous, String expectedCode) {
        var purchase = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);

        var result = reducer.reduce(List.of(1), List.of(ambiguous, purchase), DEATH_KEY);

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).containsExactlyEntriesOf(Map.of(1001, 1));
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.UNKNOWN);
        assertThat(inventory.supportingEvidence()).containsExactly(purchase.evidence());
        assertThat(inventory.limitationCodes()).containsExactly(expectedCode);
        assertThat(inventory.ambiguities()).hasSize(1);
        assertThat(inventory.ambiguities().getFirst().code()).isEqualTo(expectedCode);
        assertThat(inventory.ambiguities().getFirst().transition()).isEqualTo(ambiguous);
        assertThat(result.ambiguities()).containsExactlyElementsOf(inventory.ambiguities());
    }

    static Stream<Arguments> ambiguousTransitionCases() {
        return Stream.of(
                Arguments.of("missing purchase item id",
                        transition(2, 1, "ITEM_PURCHASED", null, null, null), "INVALID_ITEM_ID"),
                Arguments.of("zero sale item id",
                        transition(2, 1, "ITEM_SOLD", 0, null, null), "INVALID_ITEM_ID"),
                Arguments.of("purchase with contradictory undo ids",
                        transition(2, 1, "ITEM_PURCHASED", 2003, 2003, 0), "CONTRADICTORY_ITEM_IDS"),
                Arguments.of("undo with missing sides",
                        transition(2, 1, "ITEM_UNDO", null, null, null), "INVALID_UNDO_SHAPE"),
                Arguments.of("undo with both positive sides",
                        transition(2, 1, "ITEM_UNDO", null, 1001, 2003), "INVALID_UNDO_SHAPE"),
                Arguments.of("undo with an itemId",
                        transition(2, 1, "ITEM_UNDO", 1001, 1001, 0), "CONTRADICTORY_ITEM_IDS"),
                Arguments.of("sale of an absent id",
                        transition(2, 1, "ITEM_SOLD", 2003, null, null), "ITEM_NOT_PRESENT"),
                Arguments.of("destroy of an absent id",
                        transition(2, 1, "ITEM_DESTROYED", 2003, null, null), "ITEM_NOT_PRESENT"),
                Arguments.of("purchase undo of an absent id",
                        transition(2, 1, "ITEM_UNDO", null, 2003, 0), "ITEM_NOT_PRESENT"),
                Arguments.of("unsupported item transition",
                        transition(2, 1, "ITEM_TRANSFORMED", 2003, null, null), "UNSUPPORTED_ITEM_TRANSITION"));
    }

    @Test
    void recordsUnknownParticipantEvidenceWithoutCreatingAnInventoryForIt() {
        var unknown = transition(1, 11, "ITEM_PURCHASED", 1001, null, null);

        var result = reducer.reduce(List.of(1, 2), List.of(unknown), DEATH_KEY);

        assertThat(result.inventories()).extracting(InventorySnapshot::participantId).containsExactly(1, 2);
        assertThat(result.inventories()).allSatisfy(inventory -> {
            assertThat(inventory.itemQuantities()).isEmpty();
            assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
            assertThat(inventory.ambiguities()).isEmpty();
        });
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(result.ambiguities().getFirst().code()).isEqualTo("UNKNOWN_PARTICIPANT");
        assertThat(result.ambiguities().getFirst().transition()).isEqualTo(unknown);
    }

    @Test
    void preservesUnattributedZeroActorAsUnknownWithoutChangingRosterInventories() {
        var attributed = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);
        var unattributed = transition(2, 0, "ITEM_PURCHASED", 2003, null, null);

        var result = reducer.reduce(List.of(1, 2), List.of(unattributed, attributed), DEATH_KEY);

        assertThat(result.inventories()).extracting(InventorySnapshot::participantId).containsExactly(1, 2);
        assertThat(result.inventories().get(0).itemQuantities())
                .containsExactlyEntriesOf(Map.of(1001, 1));
        assertThat(result.inventories().get(0).coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(result.inventories().get(0).supportingEvidence()).containsExactly(attributed.evidence());
        assertThat(result.inventories().get(0).ambiguities()).isEmpty();
        assertThat(result.inventories().get(1).itemQuantities()).isEmpty();
        assertThat(result.inventories().get(1).coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(result.inventories().get(1).ambiguities()).isEmpty();
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(result.ambiguities().getFirst().code()).isEqualTo("UNKNOWN_PARTICIPANT");
        assertThat(result.ambiguities().getFirst().transition()).isEqualTo(unattributed);
        assertThat(result.ambiguities().getFirst().transition().evidence())
                .isEqualTo(unattributed.evidence());
    }

    @Test
    void preservesUnavailableAndObservedZeroActorsAsDistinctUnappliedEvidence() {
        var unavailable = transition(1, null, "ITEM_PURCHASED", 1001, null, null);
        var observedZero = transition(2, 0, "ITEM_PURCHASED", 2003, null, null);

        var result = reducer.reduce(List.of(1), List.of(observedZero, unavailable), DEATH_KEY);

        assertThat(result.inventories().getFirst().itemQuantities()).isEmpty();
        assertThat(result.inventories().getFirst().coverageStatus())
                .isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(result.ambiguities()).hasSize(2)
                .allSatisfy(ambiguity -> assertThat(ambiguity.code())
                        .isEqualTo("UNKNOWN_PARTICIPANT"));
        assertThat(result.ambiguities()).extracting(ambiguity -> ambiguity.transition().actorParticipantId())
                .containsExactly(null, 0);
        assertThat(result.ambiguities()).extracting(ambiguity -> ambiguity.transition().evidence())
                .containsExactly(unavailable.evidence(), observedZero.evidence());
    }

    @Test
    void conflictingDuplicateActorsZeroAndRosterApplyNeitherAndPreserveEveryReport() {
        var rosterReport = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);
        var unattributedReport = new ItemTransition(
                rosterReport.key(), 0, 1001, null, null, evidence(101, 101));

        var result = reducer.reduce(
                List.of(1), List.of(rosterReport, unattributedReport), DEATH_KEY);

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).isEmpty();
        assertThat(inventory.supportingEvidence()).isEmpty();
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.UNKNOWN);
        assertThat(inventory.limitationCodes()).containsExactly("CONFLICTING_LOGICAL_EVENT");
        assertThat(result.ambiguities()).hasSize(2)
                .extracting(ambiguity -> ambiguity.code())
                .containsOnly("CONFLICTING_LOGICAL_EVENT");
        assertThat(result.ambiguities()).extracting(ambiguity -> ambiguity.transition())
                .containsExactlyInAnyOrder(rosterReport, unattributedReport);
        assertThat(result.ambiguities()).extracting(ambiguity -> ambiguity.transition().evidence())
                .containsExactlyInAnyOrder(rosterReport.evidence(), unattributedReport.evidence());
    }

    @Test
    void appliesIdenticalLogicalReportsOnceAndRetainsEveryEvidenceInEitherInputOrder() {
        var first = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);
        var duplicate = new ItemTransition(first.key(), 1, 1001, null, null, evidence(101, 1));

        var firstOrder = reducer.reduce(List.of(1), List.of(duplicate, first), DEATH_KEY);
        var secondOrder = reducer.reduce(List.of(1), List.of(first, duplicate), DEATH_KEY);

        assertThat(firstOrder).isEqualTo(secondOrder);
        var inventory = firstOrder.inventories().getFirst();
        assertThat(inventory.itemQuantities()).containsExactlyEntriesOf(Map.of(1001, 1));
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(inventory.supportingEvidence()).containsExactly(duplicate.evidence(), first.evidence());
        assertThat(inventory.ambiguities()).isEmpty();
        assertThat(firstOrder.ambiguities()).isEmpty();
    }

    @Test
    void collapsesIdenticalUnattributedReportsIntoOneAmbiguityWithEveryEvidenceReference() {
        var first = transition(1, 0, "ITEM_PURCHASED", 1001, null, null);
        var duplicate = new ItemTransition(first.key(), 0, 1001, null, null, evidence(101, 1));

        var firstOrder = reducer.reduce(List.of(1), List.of(duplicate, first), DEATH_KEY);
        var secondOrder = reducer.reduce(List.of(1), List.of(first, duplicate), DEATH_KEY);

        assertThat(firstOrder).isEqualTo(secondOrder);
        assertThat(firstOrder.inventories().getFirst().itemQuantities()).isEmpty();
        assertThat(firstOrder.ambiguities()).singleElement().satisfies(ambiguity -> {
            assertThat(ambiguity.code()).isEqualTo("UNKNOWN_PARTICIPANT");
            assertThat(ambiguity.transition().evidenceReferences())
                    .containsExactly(duplicate.evidence(), first.evidence());
        });
    }

    @Test
    void collapsesIdenticalRemoveMissingReportsIntoOneAmbiguityWithEveryEvidenceReference() {
        var first = transition(1, 1, "ITEM_SOLD", 1001, null, null);
        var duplicate = new ItemTransition(first.key(), 1, 1001, null, null, evidence(101, 1));

        var firstOrder = reducer.reduce(List.of(1), List.of(duplicate, first), DEATH_KEY);
        var secondOrder = reducer.reduce(List.of(1), List.of(first, duplicate), DEATH_KEY);

        assertThat(firstOrder).isEqualTo(secondOrder);
        assertThat(firstOrder.inventories().getFirst().itemQuantities()).isEmpty();
        assertThat(firstOrder.ambiguities()).singleElement().satisfies(ambiguity -> {
            assertThat(ambiguity.code()).isEqualTo("ITEM_NOT_PRESENT");
            assertThat(ambiguity.transition().evidenceReferences())
                    .containsExactly(duplicate.evidence(), first.evidence());
        });
    }

    @Test
    void rejectsConflictingLogicalEvidenceBeforeApplyingAnyCandidate() {
        var first = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);
        var conflicting = new ItemTransition(first.key(), 1, 2003, null, null, evidence(102, 1));

        var result = reducer.reduce(List.of(1), List.of(first, conflicting), DEATH_KEY);

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).isEmpty();
        assertThat(inventory.limitationCodes()).containsExactly("CONFLICTING_LOGICAL_EVENT");
        assertThat(inventory.ambiguities()).hasSize(2)
                .extracting(ambiguity -> ambiguity.code())
                .containsOnly("CONFLICTING_LOGICAL_EVENT");
    }

    @Test
    void returnsRosterAndItemKeysInSortedImmutableCollections() {
        var transitions = List.of(
                transition(2, 2, "ITEM_PURCHASED", 3006, null, null),
                transition(1, 2, "ITEM_PURCHASED", 1001, null, null));

        var result = reducer.reduce(List.of(2, 1), transitions, DEATH_KEY);

        assertThat(result.inventories()).extracting(InventorySnapshot::participantId).containsExactly(1, 2);
        assertThat(result.inventories().get(1).itemQuantities().keySet()).containsExactly(1001, 3006);
        assertThatThrownBy(() -> result.inventories().add(result.inventories().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.inventories().get(1).itemQuantities().put(4000, 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.inventories().get(1).supportingEvidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.ambiguities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidRosterInputsRatherThanInventingParticipants() {
        assertThatThrownBy(() -> reducer.reduce(List.of(1, 1), List.of(), DEATH_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DUPLICATE_PARTICIPANT_ID");
        assertThatThrownBy(() -> reducer.reduce(List.of(0), List.of(), DEATH_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_PARTICIPANT_ID");
        assertThatThrownBy(() -> reducer.reduce(List.of(11), List.of(), DEATH_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_PARTICIPANT_ID");
    }

    @Test
    void exactFullMatchReconciliationIgnoresZeroSlotsWithoutChangingDeathQuantities() {
        var beforeDeath = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);
        var afterDeath = transitionAt(1_100, 1_100, 1, 1, "ITEM_PURCHASED", 2003, null, null);

        var result = reducer.reduce(
                List.of(1),
                List.of(afterDeath, beforeDeath),
                DEATH_KEY,
                Map.of(1, List.of(2003, 1001, 0, 0, 0, 0, 0)));

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).containsExactlyEntriesOf(Map.of(1001, 1));
        assertThat(inventory.supportingEvidence()).containsExactly(beforeDeath.evidence());
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(inventory.limitationCodes()).isEmpty();
    }

    @Test
    void reconciliationComparesItemQuantitiesRatherThanOnlyDistinctIds() {
        var first = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);
        var second = transition(2, 1, "ITEM_PURCHASED", 1001, null, null);

        var result = reducer.reduce(
                List.of(1),
                List.of(first, second),
                DEATH_KEY,
                Map.of(1, List.of(1001, 1001, 0, 0, 0, 0, 0)));

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).containsExactlyEntriesOf(Map.of(1001, 2));
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(inventory.limitationCodes()).isEmpty();
    }

    @Test
    void endInventoryMismatchPreservesReconstructedCoverageAndNeverRepairsTheDeathMultiset() {
        var beforeDeath = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);
        var afterDeath = transitionAt(1_100, 1_100, 1, 1, "ITEM_PURCHASED", 2003, null, null);

        var result = reducer.reduce(
                List.of(1),
                List.of(beforeDeath, afterDeath),
                DEATH_KEY,
                Map.of(1, List.of(3006, 0, 0, 0, 0, 0, 0)));

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).containsExactlyEntriesOf(Map.of(1001, 1));
        assertThat(inventory.supportingEvidence()).containsExactly(beforeDeath.evidence());
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(inventory.limitationCodes()).containsExactly("END_INVENTORY_MISMATCH");
    }

    @Test
    void absentOrPreV4EndInventoryPreservesReconstructedCoverageAndQuantities() {
        var purchase = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);

        var absent = reducer.reduce(List.of(1), List.of(purchase), DEATH_KEY, Map.of());
        var preV4 = reducer.reduce(List.of(1), List.of(purchase), DEATH_KEY, Map.of(1, List.of()));

        for (var result : List.of(absent, preV4)) {
            var inventory = result.inventories().getFirst();
            assertThat(inventory.itemQuantities()).containsExactlyEntriesOf(Map.of(1001, 1));
            assertThat(inventory.supportingEvidence()).containsExactly(purchase.evidence());
            assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
            assertThat(inventory.limitationCodes()).containsExactly("END_INVENTORY_UNAVAILABLE");
        }
    }

    @Test
    void rejectsNonSevenSlotEndEvidenceAsUnavailableRatherThanPartialReconciliation() {
        var purchase = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);

        var result = reducer.reduce(
                List.of(1), List.of(purchase), DEATH_KEY, Map.of(1, List.of(1001, 0, 0)));

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).containsExactlyEntriesOf(Map.of(1001, 1));
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(inventory.limitationCodes()).containsExactly("END_INVENTORY_UNAVAILABLE");
    }

    @Test
    void anAmbiguousPrefixRemainsUnknownEvenWhenTheObservedEndMultisetCoincidentallyMatches() {
        var invalidRemoval = transition(1, 1, "ITEM_SOLD", 1001, null, null);

        var result = reducer.reduce(
                List.of(1), List.of(invalidRemoval), DEATH_KEY, Map.of(1, List.of(0, 0, 0, 0, 0, 0, 0)));

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).isEmpty();
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.UNKNOWN);
        assertThat(inventory.limitationCodes()).containsExactly("ITEM_NOT_PRESENT");
        assertThat(inventory.ambiguities()).hasSize(1);
    }

    @Test
    void anAmbiguousPrefixWithUsableDifferingEndEvidenceAddsNoConclusiveReconciliationCode() {
        var invalidRemoval = transition(1, 1, "ITEM_SOLD", 1001, null, null);

        var result = reducer.reduce(
                List.of(1), List.of(invalidRemoval), DEATH_KEY, Map.of(1, List.of(3006, 0, 0, 0, 0, 0, 0)));

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).isEmpty();
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.UNKNOWN);
        assertThat(inventory.limitationCodes()).containsExactly("ITEM_NOT_PRESENT");
        assertThat(inventory.ambiguities()).hasSize(1);
    }

    @Test
    void anAmbiguousPrefixStillRecordsUnavailableEndEvidenceWithoutChangingUnknownCoverage() {
        var invalidRemoval = transition(1, 1, "ITEM_SOLD", 1001, null, null);

        var result = reducer.reduce(List.of(1), List.of(invalidRemoval), DEATH_KEY, Map.of());

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).isEmpty();
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.UNKNOWN);
        assertThat(inventory.limitationCodes())
                .containsExactly("END_INVENTORY_UNAVAILABLE", "ITEM_NOT_PRESENT");
        assertThat(inventory.ambiguities()).hasSize(1);
    }

    @Test
    void anAmbiguousPostDeathTransitionLeavesReconstructedPrefixCoverageAndReconciliationSilent() {
        var beforeDeath = transition(1, 1, "ITEM_PURCHASED", 1001, null, null);
        var invalidAfterDeath = transitionAt(1_100, 1_100, 1, 1, "ITEM_SOLD", 2003, null, null);

        var result = reducer.reduce(
                List.of(1),
                List.of(beforeDeath, invalidAfterDeath),
                DEATH_KEY,
                Map.of(1, List.of(3006, 0, 0, 0, 0, 0, 0)));

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).containsExactlyEntriesOf(Map.of(1001, 1));
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
        assertThat(inventory.limitationCodes()).isEmpty();
        assertThat(inventory.ambiguities()).isEmpty();
        assertThat(result.ambiguities()).hasSize(1);
        assertThat(result.ambiguities().getFirst().transition()).isEqualTo(invalidAfterDeath);
    }

    @Test
    void treatsAnEventFromAnotherMatchAsAmbiguousEvidenceRatherThanApplyingIt() {
        var foreign = new ItemTransition(
                new TimelineEventKey("NA1_0", 100, 200, 1, "ITEM_PURCHASED"),
                1,
                1001,
                null,
                null,
                evidence(1, 100));

        var result = reducer.reduce(List.of(1), List.of(foreign), DEATH_KEY);

        var inventory = result.inventories().getFirst();
        assertThat(inventory.itemQuantities()).isEmpty();
        assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.UNKNOWN);
        assertThat(inventory.limitationCodes()).containsExactly("EVENT_MATCH_MISMATCH");
        assertThat(result.ambiguities()).hasSize(1);
    }

    private static ItemTransition transition(
            int eventIndex, Integer participantId, String type, Integer itemId, Integer beforeId, Integer afterId) {
        return transitionAt(100 + eventIndex, 200 + eventIndex, eventIndex,
                participantId, type, itemId, beforeId, afterId);
    }

    private static ItemTransition transitionAt(
            long representedAtMs,
            long frameAtMs,
            int eventIndex,
            Integer participantId,
            String type,
            Integer itemId,
            Integer beforeId,
            Integer afterId) {
        return new ItemTransition(
                key(representedAtMs, frameAtMs, eventIndex, type),
                participantId,
                itemId,
                beforeId,
                afterId,
                evidence(eventIndex, representedAtMs));
    }

    private static TimelineEventKey key(long representedAtMs, long frameAtMs, int eventIndex, String type) {
        return new TimelineEventKey(MATCH_ID, representedAtMs, frameAtMs, eventIndex, type);
    }

    private static EvidenceReference evidence(int eventIndex, long representedAtMs) {
        return new EvidenceReference(
                uuid(1_000 + eventIndex), uuid(2_000 + eventIndex), representedAtMs, "match-v5-v1");
    }

    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
    }

}
