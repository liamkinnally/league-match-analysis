package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.MatchHeader;
import dev.leagueanalysis.analysis.match.domain.MatchParticipant;
import dev.leagueanalysis.analysis.match.domain.ParticipantObservation;
import dev.leagueanalysis.analysis.match.domain.TimeInterval;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.MatchSourceRevision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StateReceiptProjectorTest {
    private static final long BEFORE_TIME = 480_210L;
    private static final long AFTER_TIME = 600_228L;
    private static final int[] BEFORE_GOLD = {
        4_700, 4_720, 4_740, 4_700, 4_711,
        4_600, 4_620, 4_580, 4_600, 4_600
    };
    private static final int[] AFTER_GOLD = {
        4_830, 4_840, 4_860, 4_810, 4_809,
        5_000, 5_010, 4_970, 5_010, 5_010
    };

    private final StateReceiptProjector projector = new StateReceiptProjector();

    @Test
    void usesLatestAtOrBeforeForBeforeAndEarliestAtOrAfterForAfter() {
        var receipt = projector.project(snapshotWithBothSamples(),
                new TimeInterval(BEFORE_TIME, AFTER_TIME), 6);

        assertThat(receipt.beforeRepresentedAtMs()).isEqualTo(480_210L);
        assertThat(receipt.afterRepresentedAtMs()).isEqualTo(600_228L);
        assertThat(receipt.focalTeamLeadBefore()).isEqualTo(-571);
        assertThat(receipt.focalTeamLeadAfter()).isEqualTo(851);
        assertThat(receipt.focalTeamLeadDelta()).isEqualTo(1_422);
    }

    @Test
    void never_uses_a_future_sample_as_before_evidence() {
        var snapshotWithoutPriorSample = snapshot(List.of(
                observationsAt(AFTER_TIME, AFTER_GOLD, 200)));

        var receipt = projector.project(snapshotWithoutPriorSample,
                new TimeInterval(BEFORE_TIME, AFTER_TIME), 6);

        assertThat(receipt.before()).isEmpty();
        assertThat(receipt.limitationCodes()).contains("BEFORE_STATE_UNAVAILABLE");
        assertThat(receipt.hasUsableBrackets()).isFalse();
    }

    @Test
    void preservesParticipantSamplesAndLabelsOnlyTheAfterSampleAsConsequenceEvidence() {
        var receipt = projector.project(snapshotWithBothSamples(),
                new TimeInterval(BEFORE_TIME, AFTER_TIME), 6);

        assertThat(receipt.before()).hasValueSatisfying(before -> {
            assertThat(before.consequenceEvidence()).isFalse();
            assertThat(before.focalTeamTotalGold()).isEqualTo(23_000);
            assertThat(before.opponentTeamTotalGold()).isEqualTo(23_571);
            assertThat(before.focalParticipant()).hasValueSatisfying(focal -> {
                assertThat(focal.participantId()).isEqualTo(6);
                assertThat(focal.totalGold()).isEqualTo(4_600);
                assertThat(focal.level()).isEqualTo(8);
                assertThat(focal.creepScore()).isEqualTo(42);
            });
            assertThat(before.laneOpponent()).hasValueSatisfying(opponent -> {
                assertThat(opponent.participantId()).isEqualTo(1);
                assertThat(opponent.totalGold()).isEqualTo(4_700);
                assertThat(opponent.level()).isEqualTo(8);
                assertThat(opponent.creepScore()).isEqualTo(37);
            });
        });
        assertThat(receipt.after()).hasValueSatisfying(after ->
                assertThat(after.consequenceEvidence()).isTrue());
        assertThat(receipt.evidenceReferences()).hasSize(20);
        assertThat(receipt.projectionRuleVersion()).isEqualTo("state-receipt-p3-v1");
    }

    @Test
    void conflicting_same_time_participant_samples_make_the_affected_fields_unusable() {
        var base = snapshotWithBothSamples();
        var observations = new ArrayList<>(base.observations());
        var focalBefore = observations.stream()
                .filter(observation -> observation.participantId() == 6)
                .filter(observation -> observation.representedAtMs() == BEFORE_TIME)
                .findFirst()
                .orElseThrow();
        observations.add(new ParticipantObservation(
                focalBefore.participantId(), focalBefore.representedAtMs(),
                focalBefore.x(), focalBefore.y(), focalBefore.currentGold(),
                focalBefore.totalGold() + 1, focalBefore.level(), focalBefore.xp(),
                focalBefore.minionsKilled(), focalBefore.jungleMinionsKilled(),
                new EvidenceReference(
                        uuid(2), uuid(999), BEFORE_TIME,
                        "sanitized-observation-v1")));
        var conflicted = new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), base.participants(), observations,
                base.anchors(), base.itemTransitions(), base.observedEndItems(),
                base.coverage());

        var receipt = projector.project(
                conflicted, new TimeInterval(BEFORE_TIME, AFTER_TIME), 6);

        assertThat(receipt.before()).hasValueSatisfying(before -> {
            assertThat(before.focalParticipant()).isEmpty();
            assertThat(before.focalTeamTotalGold()).isNull();
            assertThat(before.limitationCodes()).contains(
                    "AMBIGUOUS_PARTICIPANT_SAMPLE",
                    "FOCAL_PARTICIPANT_STATE_UNAVAILABLE",
                    "FOCAL_TEAM_TOTAL_UNAVAILABLE");
        });
        assertThat(receipt.hasUsableBrackets()).isFalse();
    }

    @Test void aramNeverInfersAnSrLaneOpponentEvenWhenProviderRolesLookLikeSr() {
        var sr = snapshotWithBothSamples();
        for (int map : List.of(12, 14)) {
            var h = sr.header();
            var aram = new MatchEvidenceSnapshot(new MatchHeader(h.matchId(), 450, map, "ARAM", h.gameType(),
                    h.gameVersion(), h.dataVersion(), h.gameCreationMs(), h.gameStartMs(), h.gameEndMs(), h.durationMs()),
                    new MatchSourceRevision(h.matchId(), map, sr.sourceRevision().detailCaptureId(),
                            sr.sourceRevision().timelineCaptureId(), sr.sourceRevision().materializationVersion()),
                    sr.participants(), sr.observations(), sr.anchors(), sr.itemTransitions(), sr.observedEndItems(), sr.coverage());
            var receipt = projector.project(aram, new TimeInterval(BEFORE_TIME, AFTER_TIME), 6);
            assertThat(receipt.before()).hasValueSatisfying(sample -> assertThat(sample.laneOpponent()).isEmpty());
            assertThat(receipt.after()).hasValueSatisfying(sample -> assertThat(sample.laneOpponent()).isEmpty());
        }
    }

    private MatchEvidenceSnapshot snapshotWithBothSamples() {
        return snapshot(List.of(
                observationsAt(360_180L, BEFORE_GOLD, 10),
                observationsAt(BEFORE_TIME, BEFORE_GOLD, 100),
                observationsAt(AFTER_TIME, AFTER_GOLD, 200),
                observationsAt(720_250L, AFTER_GOLD, 300)));
    }

    private MatchEvidenceSnapshot snapshot(List<List<ParticipantObservation>> samples) {
        return new MatchEvidenceSnapshot(
                new MatchHeader(
                        "NA1_9000000001", 420, 11, "CLASSIC", "MATCHED_GAME",
                        "16.17.810.4348", "2", 1_788_451_200_000L,
                        1_788_451_210_000L, 1_788_453_286_000L, 2_076_000L),
                new MatchSourceRevision(
                        "NA1_9000000001", 11, uuid(1), uuid(2),
                        "sanitized-materializer-v1"),
                participants(),
                samples.stream().flatMap(List::stream).toList(),
                List.of(),
                List.of(),
                Map.of(),
                List.of());
    }

    private List<MatchParticipant> participants() {
        var participants = new ArrayList<MatchParticipant>();
        var positions = List.of(
                "TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY",
                "TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY");
        for (int participantId = 1; participantId <= 10; participantId++) {
            participants.add(new MatchParticipant(
                    participantId,
                    participantId <= 5 ? 100 : 200,
                    700 + participantId,
                    "Synthetic" + participantId,
                    positions.get(participantId - 1),
                    participantId > 5));
        }
        return List.copyOf(participants);
    }

    private List<ParticipantObservation> observationsAt(
            long representedAtMs, int[] totals, long evidenceSeed) {
        var observations = new ArrayList<ParticipantObservation>();
        for (int participantId = 1; participantId <= 10; participantId++) {
            observations.add(new ParticipantObservation(
                    participantId,
                    representedAtMs,
                    1_000 + participantId,
                    2_000 + participantId,
                    totals[participantId - 1] % 1_000,
                    totals[participantId - 1],
                    8,
                    3_000 + participantId,
                    36 + participantId,
                    participantId == 2 || participantId == 7 ? 12 : 0,
                    new EvidenceReference(
                            uuid(2), uuid(evidenceSeed + participantId),
                            representedAtMs, "sanitized-observation-v1")));
        }
        return List.copyOf(observations);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
