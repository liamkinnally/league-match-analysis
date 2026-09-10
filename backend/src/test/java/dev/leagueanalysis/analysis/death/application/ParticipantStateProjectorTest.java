package dev.leagueanalysis.analysis.death.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.death.domain.CoarseMapRegion;
import dev.leagueanalysis.analysis.death.domain.ParticipantStateAtDeath;
import dev.leagueanalysis.analysis.death.domain.PriorParticipantObservation;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.MatchSourceRevision;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParticipantStateProjectorTest {
    private static final UUID DETAIL_CAPTURE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TIMELINE_CAPTURE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OBSERVATION_RECORD_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final MatchSourceRevision SUMMONERS_RIFT = new MatchSourceRevision(
            "NA1_1", 11, DETAIL_CAPTURE_ID, TIMELINE_CAPTURE_ID, "materializer-v2");

    private final ParticipantStateProjector projector = new ParticipantStateProjector(new CoarseMapProjector());

    @Test
    void preservesObservedEconomyProgressionTimestampAgeCoordinatesAndProvenance() {
        var evidence = new EvidenceReference(
                TIMELINE_CAPTURE_ID, OBSERVATION_RECORD_ID, 950, "timeline-frame-v1");
        var observation = new PriorParticipantObservation(
                1, 5_000, 10_000, 825, 3_400, 9, 7_777, 123, 16, evidence);

        var state = projector.project(SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(observation)).getFirst();

        assertThat(state.participantId()).isEqualTo(1);
        assertThat(state.observationRepresentedAtMs()).isEqualTo(950);
        assertThat(state.observationAgeMs()).isEqualTo(50);
        assertThat(state.currentGold()).isEqualTo(825);
        assertThat(state.totalGold()).isEqualTo(3_400);
        assertThat(state.level()).isEqualTo(9);
        assertThat(state.xp()).isEqualTo(7_777);
        assertThat(state.minionsKilled()).isEqualTo(123);
        assertThat(state.jungleMinionsKilled()).isEqualTo(16);
        assertThat(state.x()).isEqualTo(5_000);
        assertThat(state.y()).isEqualTo(10_000);
        assertThat(state.coarseMapRegion()).isEqualTo(CoarseMapRegion.NORTH_CENTER);
        assertThat(state.evidence()).isEqualTo(evidence);
        assertThat(state.coverageStatus()).isEqualTo(CoverageStatus.OBSERVED);
        assertThat(state.ruleVersion()).isEqualTo("summoners-rift-grid-v1");
        assertThat(state.limitationCodes()).isEmpty();
    }

    @Test
    void returnsRosterGapRecordsRatherThanSynthesizingAbsentParticipantSnapshots() {
        var observation = observation(1, 900, 10, 20);

        var states = projector.project(
                SUMMONERS_RIFT, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 1_000, 100, List.of(observation));

        assertThat(states).hasSize(10);
        assertThat(states).extracting(ParticipantStateAtDeath::participantId)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        var gap = states.get(1);
        assertThat(gap.coverageStatus()).isEqualTo(CoverageStatus.LIMITED);
        assertThat(gap.observationRepresentedAtMs()).isNull();
        assertThat(gap.observationAgeMs()).isNull();
        assertThat(gap.currentGold()).isNull();
        assertThat(gap.totalGold()).isNull();
        assertThat(gap.x()).isNull();
        assertThat(gap.y()).isNull();
        assertThat(gap.coarseMapRegion()).isEqualTo(CoarseMapRegion.UNKNOWN);
        assertThat(gap.evidence()).isNull();
        assertThat(gap.limitationCodes()).containsExactly("NO_PRIOR_OBSERVATION");
    }

    @Test
    void gapsStaleOrNonAntecedentObservationsWithoutUsingThemAsPriorEvidence() {
        var stale = observation(1, 899, 100, 200);
        var equalTimestamp = observation(2, 1_000, 300, 400);
        var laterTimestamp = observation(3, 1_001, 500, 600);

        var states = projector.project(
                SUMMONERS_RIFT, List.of(1, 2, 3), 1_000, 100, List.of(stale, equalTimestamp, laterTimestamp));

        assertThat(states).allSatisfy(state -> {
            assertThat(state.coverageStatus()).isEqualTo(CoverageStatus.LIMITED);
            assertThat(state.currentGold()).isNull();
            assertThat(state.totalGold()).isNull();
        });
        assertThat(states.get(0).limitationCodes()).containsExactly("OBSERVATION_TOO_OLD");
        assertThat(states.get(0).evidenceReferences()).containsExactly(stale.evidence());
        assertThat(states.get(1).limitationCodes()).containsExactly("OBSERVATION_NOT_STRICTLY_PRIOR");
        assertThat(states.get(1).evidenceReferences()).containsExactly(equalTimestamp.evidence());
        assertThat(states.get(2).limitationCodes()).containsExactly("OBSERVATION_NOT_STRICTLY_PRIOR");
        assertThat(states.get(2).evidenceReferences()).containsExactly(laterTimestamp.evidence());
    }

    @Test
    void preservesMissingCoordinatesAsMissingWithAnExplicitPositionLimitation() {
        var observation = new PriorParticipantObservation(
                1, null, 5_000, 100, 200, 3, 300, 4, 5,
                new EvidenceReference(TIMELINE_CAPTURE_ID, OBSERVATION_RECORD_ID, 900, "timeline-frame-v1"));

        var state = projector.project(SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(observation)).getFirst();

        assertThat(state.x()).isNull();
        assertThat(state.y()).isEqualTo(5_000);
        assertThat(state.coarseMapRegion()).isEqualTo(CoarseMapRegion.UNKNOWN);
        assertThat(state.coverageStatus()).isEqualTo(CoverageStatus.LIMITED);
        assertThat(state.limitationCodes()).containsExactly("POSITION_MISSING");
    }

    @Test
    void rejectsConflictingLatestPriorObservationsRegardlessOfInputOrder() {
        var older = observation(1, 940, 10, 20);
        var firstLatest = observation(1, 950, 100, 200);
        var conflictingLatest = new PriorParticipantObservation(
                1,
                1_000,
                2_000,
                300,
                400,
                5,
                500,
                6,
                7,
                new EvidenceReference(
                        UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        UUID.fromString("00000000-0000-0000-0000-000000000005"),
                        950,
                        "timeline-frame-v1"));

        var firstOrder = projector.project(
                SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(older, firstLatest, conflictingLatest)).getFirst();
        var reversedOrder = projector.project(
                SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(conflictingLatest, older, firstLatest)).getFirst();

        assertGapFor(firstOrder, "AMBIGUOUS_PRIOR_OBSERVATION");
        assertThat(firstOrder.evidenceReferences()).containsExactly(firstLatest.evidence(), conflictingLatest.evidence());
        assertThat(reversedOrder).isEqualTo(firstOrder);
    }

    @Test
    void deduplicatesIdenticalLatestContentAcrossProvenanceAndRetainsEveryReference() {
        var first = observation(1, 950, 100, 200);
        var second = new PriorParticipantObservation(
                1,
                first.x(),
                first.y(),
                first.currentGold(),
                first.totalGold(),
                first.level(),
                first.xp(),
                first.minionsKilled(),
                first.jungleMinionsKilled(),
                new EvidenceReference(
                        UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        UUID.fromString("00000000-0000-0000-0000-000000000005"),
                        950,
                        "timeline-frame-v1"));

        var firstOrder = projector.project(
                SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(second, first)).getFirst();
        var secondOrder = projector.project(
                SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(first, second)).getFirst();

        assertThat(firstOrder).isEqualTo(secondOrder);
        assertThat(firstOrder.coverageStatus()).isEqualTo(CoverageStatus.OBSERVED);
        assertThat(firstOrder.currentGold()).isEqualTo(100);
        assertThat(firstOrder.evidenceReferences()).containsExactly(first.evidence(), second.evidence());
    }

    @Test
    void gapsNegativeObservedCountersWithoutClampingOrUsingPartialState() {
        var invalidObservations = List.of(
                observationWithCounters(-1, 0, 0, 0, 0, 0),
                observationWithCounters(0, -1, 0, 0, 0, 0),
                observationWithCounters(0, 0, -1, 0, 0, 0),
                observationWithCounters(0, 0, 0, -1, 0, 0),
                observationWithCounters(0, 0, 0, 0, -1, 0),
                observationWithCounters(0, 0, 0, 0, 0, -1));

        for (var invalidObservation : invalidObservations) {
            var state = projector.project(
                    SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(invalidObservation)).getFirst();

            assertGapFor(state, "INVALID_OBSERVATION_COUNTER");
            assertThat(state.evidenceReferences()).containsExactly(invalidObservation.evidence());
        }
    }

    @Test
    void rejectsMixedValidAndInvalidLatestObservationsRegardlessOfInputOrder() {
        var olderValid = observation(1, 940, 10, 20);
        var latestValid = observation(1, 950, 100, 200);
        var latestInvalid = observationWithCountersAt(950, -1, 0, 0, 0, 0, 0);

        var firstOrder = projector.project(
                SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(olderValid, latestValid, latestInvalid)).getFirst();
        var reversedOrder = projector.project(
                SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(latestInvalid, olderValid, latestValid)).getFirst();

        assertGapFor(firstOrder, "AMBIGUOUS_PRIOR_OBSERVATION");
        assertThat(reversedOrder).isEqualTo(firstOrder);
    }

    @Test
    void rejectsDistinctInvalidLatestObservationsInsteadOfSubstitutingOlderEvidence() {
        var olderValid = observation(1, 940, 10, 20);
        var negativeCurrentGold = observationWithCountersAt(950, -1, 0, 0, 0, 0, 0);
        var negativeTotalGold = observationWithCountersAt(950, 0, -1, 0, 0, 0, 0);

        var state = projector.project(
                SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(olderValid, negativeCurrentGold, negativeTotalGold)).getFirst();

        assertGapFor(state, "AMBIGUOUS_PRIOR_OBSERVATION");
    }

    @Test
    void retainsInvalidLimitationForIdenticallyDuplicatedInvalidLatestObservation() {
        var olderValid = observation(1, 940, 10, 20);
        var latestInvalid = observationWithCountersAt(950, -1, 0, 0, 0, 0, 0);

        var state = projector.project(
                SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(olderValid, latestInvalid, latestInvalid)).getFirst();

        assertGapFor(state, "INVALID_OBSERVATION_COUNTER");
    }

    @Test
    void acceptsZeroValuedObservedCounters() {
        var observation = observationWithCounters(0, 0, 0, 0, 0, 0);

        var state = projector.project(SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(observation)).getFirst();

        assertThat(state.coverageStatus()).isEqualTo(CoverageStatus.OBSERVED);
        assertThat(state.currentGold()).isZero();
        assertThat(state.totalGold()).isZero();
        assertThat(state.level()).isZero();
        assertThat(state.xp()).isZero();
        assertThat(state.minionsKilled()).isZero();
        assertThat(state.jungleMinionsKilled()).isZero();
    }

    @Test
    void acceptsObservationAtTheExactMaximumAge() {
        var observation = observation(1, 900, 100, 200);

        var state = projector.project(SUMMONERS_RIFT, List.of(1), 1_000, 100, List.of(observation)).getFirst();

        assertThat(state.coverageStatus()).isEqualTo(CoverageStatus.OBSERVED);
        assertThat(state.observationAgeMs()).isEqualTo(100);
    }

    @Test
    void usesNoImplicitAgeToleranceAndStillRejectsAnEqualTimestamp() {
        var immediatelyPrior = observation(1, 999, 100, 200);
        var equalTimestamp = observation(2, 1_000, 300, 400);

        var states = projector.project(
                SUMMONERS_RIFT, List.of(1, 2), 1_000, 0, List.of(immediatelyPrior, equalTimestamp));

        assertGapFor(states.get(0), "OBSERVATION_TOO_OLD");
        assertGapFor(states.get(1), "OBSERVATION_NOT_STRICTLY_PRIOR");
    }

    private void assertGapFor(ParticipantStateAtDeath state, String limitationCode) {
        assertThat(state.coverageStatus()).isEqualTo(CoverageStatus.LIMITED);
        assertThat(state.observationRepresentedAtMs()).isNull();
        assertThat(state.observationAgeMs()).isNull();
        assertThat(state.currentGold()).isNull();
        assertThat(state.totalGold()).isNull();
        assertThat(state.limitationCodes()).containsExactly(limitationCode);
    }

    private PriorParticipantObservation observationWithCounters(
            int currentGold,
            int totalGold,
            int level,
            int xp,
            int minionsKilled,
            int jungleMinionsKilled) {
        return observationWithCountersAt(
                900, currentGold, totalGold, level, xp, minionsKilled, jungleMinionsKilled);
    }

    private PriorParticipantObservation observationWithCountersAt(
            long representedAtMs,
            int currentGold,
            int totalGold,
            int level,
            int xp,
            int minionsKilled,
            int jungleMinionsKilled) {
        return new PriorParticipantObservation(
                1, 1_000, 2_000, currentGold, totalGold, level, xp, minionsKilled, jungleMinionsKilled,
                new EvidenceReference(
                        TIMELINE_CAPTURE_ID, OBSERVATION_RECORD_ID, representedAtMs, "timeline-frame-v1"));
    }

    private PriorParticipantObservation observation(int participantId, long representedAtMs, int currentGold, int totalGold) {
        return new PriorParticipantObservation(
                participantId, 1_000, 2_000, currentGold, totalGold, 5, 500, 6, 7,
                new EvidenceReference(
                        TIMELINE_CAPTURE_ID, OBSERVATION_RECORD_ID, representedAtMs, "timeline-frame-v1"));
    }
}
