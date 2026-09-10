package dev.leagueanalysis.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.leagueanalysis.analysis.death.domain.DeathEvent;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TimelineEventKeyTest {
    @Test
    void itemTransitionDistinguishesUnavailableActorFromExplicitlyObservedZero() {
        var key = new TimelineEventKey("NA1_1", 10_000, 20_000, 3, "ITEM_PURCHASED");
        var evidence = new EvidenceReference(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                10_000,
                "timeline-v1");

        var unavailable = new ItemTransition(key, null, 1001, null, null, evidence);
        var unattributed = new ItemTransition(key, 0, 1001, null, null, evidence);

        assertThat(unavailable.actorParticipantId()).isNull();
        assertThat(unattributed.actorParticipantId()).isZero();
        assertThat(unavailable).isNotEqualTo(unattributed);
        assertThat(unavailable.key()).isEqualTo(key);
        assertThat(unavailable.itemId()).isEqualTo(1001);
        assertThat(unavailable.beforeId()).isNull();
        assertThat(unavailable.afterId()).isNull();
        assertThat(unavailable.evidenceReferences()).containsExactly(evidence);
        assertThat(unattributed.evidenceReferences()).containsExactly(evidence);
    }

    @Test
    void itemTransitionStillRejectsNegativeActorIds() {
        var key = new TimelineEventKey("NA1_1", 10_000, 20_000, 3, "ITEM_PURCHASED");
        var evidence = new EvidenceReference(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                10_000,
                "timeline-v1");

        assertThatThrownBy(() -> new ItemTransition(key, -1, 1001, null, null, evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_PARTICIPANT_ID");
    }

    @Test
    void ordersDifferentRepresentedTimestampsBeforeFrameMetadata() {
        var earlier = new TimelineEventKey("NA1_1", 10_000, 20_000, 9, "CHAMPION_KILL");
        var later = new TimelineEventKey("NA1_1", 10_001, 10_001, 0, "CHAMPION_KILL");

        assertThat(earlier).isLessThan(later);
    }

    @Test
    void ordersEqualRepresentedTimestampsByContainingFrame() {
        var earlierFrame = new TimelineEventKey("NA1_1", 10_000, 10_000, 9, "CHAMPION_KILL");
        var laterFrame = new TimelineEventKey("NA1_1", 10_000, 20_000, 0, "CHAMPION_KILL");

        assertThat(earlierFrame).isLessThan(laterFrame);
    }

    @Test
    void ordersEqualFrameTimesByFrameEventIndexBeforeProviderType() {
        var lowerIndex = new TimelineEventKey("NA1_1", 10_000, 20_000, 2, "ZZZ_TYPE");
        var higherIndex = new TimelineEventKey("NA1_1", 10_000, 20_000, 3, "AAA_TYPE");

        assertThat(lowerIndex).isLessThan(higherIndex);
    }

    @Test
    void usesProviderTypeOnlyAsTheFinalDeterministicTieBreaker() {
        var alphabeticalFirst = new TimelineEventKey("NA1_1", 10_000, 20_000, 3, "AAA_TYPE");
        var alphabeticalSecond = new TimelineEventKey("NA1_1", 10_000, 20_000, 3, "ZZZ_TYPE");

        assertThat(alphabeticalFirst).isLessThan(alphabeticalSecond);
    }

    @Test
    void logicalIdentityDoesNotDependOnCaptureOrNormalizedRowIds() {
        var key = new TimelineEventKey("NA1_1", 10_000, 20_000, 3, "CHAMPION_KILL");
        var first = new DeathEvent(
                key,
                8,
                0,
                List.of(1, 2),
                120,
                240,
                new EvidenceReference(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        10_000,
                        "timeline-v1"));
        var second = new DeathEvent(
                key,
                8,
                0,
                List.of(1, 2),
                120,
                240,
                new EvidenceReference(
                        UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        10_000,
                        "timeline-v1"));

        assertThat(first.key()).isEqualTo(second.key());
        assertThat(first.evidence()).isNotEqualTo(second.evidence());
    }

    @Test
    void reconcilesIdenticalDeathReportsInEitherOrderAndRetainsEveryReference() {
        var key = new TimelineEventKey("NA1_1", 10_000, 20_000, 3, "CHAMPION_KILL");
        var first = deathReport(key, 8, 0, List.of(1, 2), true, 120, 240, 1, 2);
        var second = deathReport(key, 8, 0, List.of(1, 2), true, 120, 240, 3, 4);

        var firstOrder = DeathEvent.reconcile(List.of(first, second));
        var secondOrder = DeathEvent.reconcile(List.of(second, first));

        assertThat(firstOrder).isEqualTo(secondOrder);
        assertThat(firstOrder.victimParticipantId()).isEqualTo(8);
        assertThat(firstOrder.evidenceReferences()).containsExactly(first.evidence(), second.evidence());
        assertThat(firstOrder.limitationCodes()).isEmpty();
    }

    @Test
    void reconcilesConflictingDeathReportsAsAProviderNeutralPartialInEitherOrder() {
        var key = new TimelineEventKey("NA1_1", 10_000, 20_000, 3, "CHAMPION_KILL");
        var first = deathReport(key, 8, 0, List.of(1, 2), true, 120, 240, 1, 2);
        var conflicting = deathReport(key, 9, 0, List.of(1, 2), true, 120, 240, 3, 4);

        var firstOrder = DeathEvent.reconcile(List.of(first, conflicting));
        var secondOrder = DeathEvent.reconcile(List.of(conflicting, first));

        assertThat(firstOrder).isEqualTo(secondOrder);
        assertThat(firstOrder.key()).isEqualTo(key);
        assertThat(firstOrder.victimParticipantId()).isNull();
        assertThat(firstOrder.killerParticipantId()).isNull();
        assertThat(firstOrder.assistingParticipantIds()).isEmpty();
        assertThat(firstOrder.assistingParticipantIdsObserved()).isFalse();
        assertThat(firstOrder.positionX()).isNull();
        assertThat(firstOrder.positionY()).isNull();
        assertThat(firstOrder.evidenceReferences()).containsExactly(first.evidence(), conflicting.evidence());
        assertThat(firstOrder.limitationCodes()).containsExactlyInAnyOrder(
                "AMBIGUOUS_DEATH_EVENT", "VICTIM_PARTICIPANT_ID_UNAVAILABLE");
    }

    @Test
    void defensivelyCopiesAssistingParticipantIds() {
        var mutableAssisters = new java.util.ArrayList<>(List.of(1, 2));
        var death = new DeathEvent(
                new TimelineEventKey("NA1_1", 10_000, 20_000, 3, "CHAMPION_KILL"),
                8,
                0,
                mutableAssisters,
                null,
                null,
                new EvidenceReference(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        10_000,
                        "timeline-v1"));

        mutableAssisters.add(3);

        assertThat(death.assistingParticipantIds()).containsExactly(1, 2);
        assertThatThrownBy(() -> death.assistingParticipantIds().add(3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankLogicalIdentityPartsAndNegativeOrderingValues() {
        assertThatThrownBy(() -> new TimelineEventKey(" ", 0, 0, 0, "TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimelineEventKey("NA1_1", 0, 0, 0, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimelineEventKey("NA1_1", -1, 0, 0, "TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimelineEventKey("NA1_1", 0, -1, 0, "TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimelineEventKey("NA1_1", 0, 0, -1, "TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DeathEvent deathReport(
            TimelineEventKey key,
            Integer victimParticipantId,
            Integer killerParticipantId,
            List<Integer> assistingParticipantIds,
            boolean assistingParticipantIdsObserved,
            Integer positionX,
            Integer positionY,
            long captureId,
            long recordId) {
        return new DeathEvent(
                key,
                victimParticipantId,
                killerParticipantId,
                assistingParticipantIds,
                assistingParticipantIdsObserved,
                positionX,
                positionY,
                List.of(new EvidenceReference(
                        new UUID(0, captureId),
                        new UUID(0, recordId),
                        key.representedAtMs(),
                        "timeline-v1")),
                Set.of());
    }
}
