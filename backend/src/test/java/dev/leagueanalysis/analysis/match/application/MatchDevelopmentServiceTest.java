package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.domain.AnchorKind;
import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.MatchDevelopment;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.MatchHeader;
import dev.leagueanalysis.analysis.match.domain.MatchParticipant;
import dev.leagueanalysis.analysis.match.domain.ParticipantObservation;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.MatchSourceRevision;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchDevelopmentServiceTest {
    private static final String MATCH_ID = "NA1_7000000001";
    private static final UUID DETAIL_CAPTURE = uuid(1);
    private static final UUID TIMELINE_CAPTURE = uuid(2);

    @Test
    void returnsActualTimeDifferencesAndFocalValuesWithoutInferringAtEventTimes() {
        var service = service(snapshot(
                observation(6, 480_000, 3_100, 8, 2_420, 53, 2),
                observation(1, 480_000, 3_000, 8, 2_400, 51, 0),
                observation(6, 600_000, 4_710, 9, 3_720, 75, 3),
                observation(1, 600_000, 4_200, 9, 3_500, 65, 0)));

        var result = service.load(MATCH_ID, 6, 1).orElseThrow();

        assertThat(result.timelineAvailable()).isTrue();
        assertThat(result.samples()).containsExactly(
                new MatchDevelopment.Sample(480_000, 100, 4, 20, 8, 8, 3_100, 55, 2_420),
                new MatchDevelopment.Sample(600_000, 510, 13, 220, 9, 9, 4_710, 78, 3_720));
        assertThat(result.events()).containsExactly(
                new MatchDevelopment.Event(
                        515_345, "Champion kill", List.of(1, 6, 7, 8), null, 6, 1,
                        List.of(7, 8), true));
    }

    @Test
    void distinguishesExplicitlyEmptyAssistersFromUnavailableAssisterRoles() {
        var result = service(snapshotWithAssisters(
                List.of(), false,
                observation(6, 480_000, 3_100, 8, 2_420, 53, 2),
                observation(1, 480_000, 3_000, 8, 2_400, 51, 0)))
                .load(MATCH_ID, 6, 1).orElseThrow();

        assertThat(result.events().getFirst().assisterParticipantIds()).isEmpty();
        assertThat(result.events().getFirst().assistersObserved()).isFalse();
    }

    @Test
    void keepsFocalSamplesAndUsesGapsWhenTheComparisonObservationIsMissingOrConflicting() {
        var service = service(snapshot(
                observation(6, 480_000, 3_100, 8, 2_420, 53, 2),
                observation(1, 480_000, 3_000, 8, 2_400, 51, 0),
                observation(1, 480_000, 3_001, 8, 2_400, 51, 0),
                observation(6, 600_000, 4_710, 9, 3_720, 75, 3)));

        var result = service.load(MATCH_ID, 6, 1).orElseThrow();

        assertThat(result.samples()).containsExactly(
                new MatchDevelopment.Sample(480_000, null, null, null, 8, null, 3_100, 55, 2_420),
                new MatchDevelopment.Sample(600_000, null, null, null, 9, null, 4_710, 78, 3_720));
    }

    @Test
    void keepsAComparisonTimestampAsANullFocalGapBetweenObservedFocalSamples() {
        var service = service(snapshot(
                observation(6, 480_000, 3_100, 8, 2_420, 53, 2),
                observation(1, 480_000, 3_000, 8, 2_400, 51, 0),
                observation(1, 600_000, 4_200, 9, 3_500, 65, 0),
                observation(6, 720_000, 5_500, 10, 4_500, 87, 3),
                observation(1, 720_000, 5_000, 10, 4_300, 84, 0)));

        var result = service.load(MATCH_ID, 6, 1).orElseThrow();

        assertThat(result.samples()).containsExactly(
                new MatchDevelopment.Sample(480_000, 100, 4, 20, 8, 8, 3_100, 55, 2_420),
                new MatchDevelopment.Sample(600_000, null, null, null, null, 9, null, null, null),
                new MatchDevelopment.Sample(720_000, 500, 6, 200, 10, 10, 5_500, 90, 4_500));
    }

    @Test
    void keepsAnotherParticipantsTimestampAsANullFocalGapWithoutAComparison() {
        var service = service(snapshot(
                observation(6, 480_000, 3_100, 8, 2_420, 53, 2),
                observation(1, 600_000, 4_200, 9, 3_500, 65, 0),
                observation(6, 720_000, 5_500, 10, 4_500, 87, 3)));

        var result = service.load(MATCH_ID, 6, null).orElseThrow();

        assertThat(result.samples()).containsExactly(
                new MatchDevelopment.Sample(480_000, null, null, null, 8, null, 3_100, 55, 2_420),
                new MatchDevelopment.Sample(600_000, null, null, null, null, null, null, null, null),
                new MatchDevelopment.Sample(720_000, null, null, null, 10, null, 5_500, 90, 4_500));
    }

    @Test
    void returnsSummaryAndScoreboardWhenTheTimelineWasNotCaptured() {
        var noTimeline = new MatchEvidenceSnapshot(
                header(),
                new MatchSourceRevision(MATCH_ID, 11, DETAIL_CAPTURE, null, "match-v5-v1"),
                participants(), List.of(), List.of(), List.of(), Map.of(), List.of());
        var service = service(noTimeline);

        var result = service.load(MATCH_ID, 6, null).orElseThrow();

        assertThat(result.timelineAvailable()).isFalse();
        assertThat(result.samples()).isEmpty();
        assertThat(result.events()).isEmpty();
        assertThat(result.summary())
                .extracting(
                        MatchDevelopment.Summary::win,
                        MatchDevelopment.Summary::kills,
                        MatchDevelopment.Summary::deaths,
                        MatchDevelopment.Summary::assists,
                        MatchDevelopment.Summary::totalCs,
                        MatchDevelopment.Summary::goldEarned)
                .containsExactly(true, 7, 2, 9, 211, 12_440);
        assertThat(result.roster()).hasSize(2).allSatisfy(participant -> {
            assertThat(participant.endItemIds()).hasSize(7);
            assertThat(participant.totalCs())
                    .isEqualTo(participant.laneCs() + participant.jungleCs());
        });
    }

    private MatchDevelopmentService service(MatchEvidenceSnapshot snapshot) {
        HistoricalMatchQuery historical = new HistoricalMatchQuery() {
            @Override
            public Optional<MatchEvidenceSnapshot> load(String matchId) {
                return Optional.of(snapshot);
            }
        };
        MatchOverviewQuery overview = matchId -> Optional.of(new MatchOverviewQuery.Overview(
                MATCH_ID,
                420,
                11,
                "CLASSIC",
                "16.17.1",
                1_788_000_000_000L,
                1_800_000,
                List.of(
                        new MatchOverviewQuery.Participant(
                                1, 100, 266, "Aatrox", "TOP", false,
                                3, 6, 4, 190, 8, 10_900, 10_300, 18, 4, 12,
                                List.of(3071, 3047, 0, 0, 0, 0, 3340)),
                        new MatchOverviewQuery.Participant(
                                6, 200, 24, "Jax", "TOP", true,
                                7, 2, 9, 201, 10, 12_440, 11_900, 23, 4, 12,
                                List.of(3078, 3047, 3051, 0, 0, 0, 3340)))));
        return new MatchDevelopmentService(historical, overview);
    }

    private MatchEvidenceSnapshot snapshot(ParticipantObservation... observations) {
        return snapshotWithAssisters(List.of(7, 8), true, observations);
    }

    private MatchEvidenceSnapshot snapshotWithAssisters(
            List<Integer> assisterParticipantIds,
            boolean assistersObserved,
            ParticipantObservation... observations) {
        var event = new MatchAnchor(
                new TimelineEventKey(MATCH_ID, 515_345, 540_000, 0, "CHAMPION_KILL"),
                AnchorKind.CHAMPION_KILL,
                6,
                1,
                200,
                assisterParticipantIds,
                assistersObserved,
                6_400,
                5_100,
                null,
                List.of(evidence(515_345, 99)),
                java.util.Set.of());
        return new MatchEvidenceSnapshot(
                header(),
                new MatchSourceRevision(MATCH_ID, 11, DETAIL_CAPTURE, TIMELINE_CAPTURE, "match-v5-v1"),
                participants(), List.of(observations), List.of(event), List.of(), Map.of(), List.of());
    }

    private MatchHeader header() {
        return new MatchHeader(
                MATCH_ID, 420, 11, "CLASSIC", "MATCHED_GAME", "16.17.1", "2",
                1_788_000_000_000L, 1_788_000_000_000L, 1_788_001_800_000L, 1_800_000);
    }

    private List<MatchParticipant> participants() {
        return List.of(
                new MatchParticipant(1, 100, 266, "Aatrox", "TOP", false),
                new MatchParticipant(6, 200, 24, "Jax", "TOP", true));
    }

    private ParticipantObservation observation(
            int participantId,
            long timestamp,
            int totalGold,
            int level,
            int xp,
            int laneCs,
            int jungleCs) {
        return new ParticipantObservation(
                participantId, timestamp, 1_000, 2_000, 500, totalGold, level, xp,
                laneCs, jungleCs, evidence(timestamp, participantId * 10 + (int) (timestamp / 120_000)));
    }

    private EvidenceReference evidence(long timestamp, int ordinal) {
        return new EvidenceReference(TIMELINE_CAPTURE, uuid(ordinal), timestamp, "match-v5-v1");
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("70000000-0000-0000-0000-" + "%012d".formatted(suffix));
    }
}
