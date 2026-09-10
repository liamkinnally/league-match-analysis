package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.AnchorKind;
import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.MatchHeader;
import dev.leagueanalysis.analysis.match.domain.MatchParticipant;
import dev.leagueanalysis.analysis.match.domain.ParticipantObservation;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.ItemTransition;
import dev.leagueanalysis.evidence.domain.MatchSourceRevision;
import dev.leagueanalysis.evidence.domain.SourceCoverage;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MatchAnalysisTestFixture {
    public static final String MATCH_ID = "NA1_9000000001";
    static final UUID DETAIL_CAPTURE_ID = uuid(4);
    static final UUID TIMELINE_CAPTURE_ID = uuid(5);

    private MatchAnalysisTestFixture() {}

    public static MatchEvidenceSnapshot snapshot() {
        return snapshot(0, false, false, "sanitized-materializer-v1",
                "sanitized-observation-v1", "sanitized-event-v1");
    }

    static MatchEvidenceSnapshot withAlteredResultAndPostWindowEvidence() {
        return snapshot(0, true, false, "sanitized-materializer-v1",
                "sanitized-observation-v1", "sanitized-event-v1");
    }

    static MatchEvidenceSnapshot withAlteredResultOnly() {
        var base = snapshot();
        return new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), participants(true),
                base.observations(), base.anchors(), base.itemTransitions(),
                base.observedEndItems(), base.coverage());
    }

    static MatchEvidenceSnapshot withDifferentRowIds() {
        return snapshot(10_000, false, false, "sanitized-materializer-v1",
                "sanitized-observation-v1", "sanitized-event-v1");
    }

    static MatchEvidenceSnapshot reordered() {
        return snapshot(0, false, true, "sanitized-materializer-v1",
                "sanitized-observation-v1", "sanitized-event-v1");
    }

    static MatchEvidenceSnapshot withAnchorBeyondFourthCandidateBoundary() {
        var base = snapshot();
        var anchors = new ArrayList<>(base.anchors());
        anchors.add(anchor(398, 1_650_530, 1_650_000, 0,
                "ELITE_MONSTER_KILL", AnchorKind.BARON, 1, null, 100,
                List.of(), false, "BARON_NASHOR", "sanitized-event-v1"));
        return new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), base.participants(),
                base.observations(), anchors, base.itemTransitions(),
                base.observedEndItems(), base.coverage());
    }

    static MatchEvidenceSnapshot withTerminalLeadCandidate() {
        var base = snapshot();
        var observations = new ArrayList<>(base.observations());
        var terminalGold = new int[] {
            20_000, 20_000, 20_000, 20_000, 20_000,
            40_000, 40_000, 40_000, 40_000, 40_000
        };
        observations.addAll(observationsAt(
                2_076_000L, terminalGold, 900, false,
                "sanitized-observation-v1"));
        var anchors = new ArrayList<>(base.anchors());
        anchors.add(anchor(397, 2_000_000L, 2_000_000L, 0,
                "ELITE_MONSTER_KILL", AnchorKind.DRAGON, 7, null, 200,
                List.of(), false, "DRAGON", "sanitized-event-v1"));
        return new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), base.participants(),
                observations, anchors, base.itemTransitions(),
                base.observedEndItems(), base.coverage());
    }

    static MatchEvidenceSnapshot withBracketedFutureCandidate() {
        var base = withAnchorBeyondFourthCandidateBoundary();
        var observations = new ArrayList<>(base.observations());
        observations.addAll(observationsAt(1_740_000L, teamTotals(11_500, 11_200),
                920, false, "sanitized-observation-v1"));
        return new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), base.participants(),
                observations, base.anchors(), base.itemTransitions(),
                base.observedEndItems(), base.coverage());
    }

    static MatchEvidenceSnapshot withSparseOverlappingProjectedCandidates() {
        var base = snapshot();
        var sparseObservations = base.observations().stream()
                .filter(observation -> observation.representedAtMs() != 600_228L)
                .filter(observation -> observation.representedAtMs() != 780_275L)
                .toList();
        return new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), base.participants(),
                sparseObservations, base.anchors(), base.itemTransitions(),
                base.observedEndItems(), base.coverage());
    }

    static MatchEvidenceSnapshot reorderedSparseOverlappingProjectedCandidates() {
        var base = withSparseOverlappingProjectedCandidates();
        return new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), reversed(base.participants()),
                reversed(base.observations()), reversed(base.anchors()),
                reversed(base.itemTransitions()), base.observedEndItems(),
                reversed(base.coverage()));
    }

    static MatchEvidenceSnapshot withCrossBoundaryItemSequenceInSparseProjection() {
        var base = withSparseOverlappingProjectedCandidates();
        var itemAnchor = anchor(712, 550_000L, 550_000L, 0,
                "ITEM_PURCHASED", AnchorKind.ITEM, 6, null, null,
                List.of(), false, "ITEM:3006", "sanitized-event-v1");
        var anchors = new ArrayList<>(base.anchors());
        anchors.add(itemAnchor);
        var itemTransitions = new ArrayList<>(base.itemTransitions());
        itemTransitions.add(new ItemTransition(
                itemAnchor.key(), 6, 3_006, null, null,
                itemAnchor.evidenceReferences()));
        return new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), base.participants(),
                base.observations(), anchors, itemTransitions,
                base.observedEndItems(), base.coverage());
    }

    static MatchEvidenceSnapshot overCapDirectionAndPhaseCoverage() {
        var base = snapshot();
        var observations = new ArrayList<ParticipantObservation>();
        observations.addAll(observationsAt(100_000L, teamTotals(10_000, 10_000),
                500, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(200_000L, teamTotals(10_100, 10_300),
                510, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(250_000L, teamTotals(10_400, 10_600),
                520, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(350_000L, teamTotals(10_500, 10_900),
                530, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(400_000L, teamTotals(11_000, 11_400),
                540, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(500_000L, teamTotals(11_100, 11_700),
                550, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(550_000L, teamTotals(11_800, 12_400),
                560, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(670_000L, teamTotals(11_900, 12_700),
                570, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(820_000L, teamTotals(13_000, 13_800),
                580, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(940_000L, teamTotals(13_400, 14_000),
                590, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(1_480_000L, teamTotals(15_000, 15_600),
                600, false, "sanitized-observation-v1"));
        observations.addAll(observationsAt(1_600_000L, teamTotals(15_200, 15_800),
                610, false, "sanitized-observation-v1"));

        var anchors = List.of(
                anchor(701, 150_000L, 150_000L, 0,
                        "ELITE_MONSTER_KILL", AnchorKind.DRAGON, 7, null, 200,
                        List.of(), false, "DRAGON", "sanitized-event-v1"),
                anchor(702, 280_000L, 280_000L, 0,
                        "CHAMPION_KILL", AnchorKind.CHAMPION_KILL, 1, 6, null,
                        List.of(2), true, null, "sanitized-event-v1"),
                anchor(703, 320_000L, 320_000L, 0,
                        "ELITE_MONSTER_KILL", AnchorKind.HERALD, 2, null, 100,
                        List.of(), false, "RIFTHERALD", "sanitized-event-v1"),
                anchor(704, 430_000L, 430_000L, 0,
                        "ELITE_MONSTER_KILL", AnchorKind.DRAGON, 7, null, 200,
                        List.of(), false, "DRAGON", "sanitized-event-v1"),
                anchor(705, 470_000L, 470_000L, 0,
                        "BUILDING_KILL", AnchorKind.STRUCTURE, 1, null, 100,
                        List.of(), false, "TOWER_BUILDING:MID_LANE:OUTER_TURRET",
                        "sanitized-event-v1"),
                anchor(706, 620_000L, 620_000L, 0,
                        "BUILDING_KILL", AnchorKind.STRUCTURE, 6, null, 200,
                        List.of(), false, "TOWER_BUILDING:TOP_LANE:OUTER_TURRET",
                        "sanitized-event-v1"),
                anchor(707, 620_000L, 620_000L, 1,
                        "BUILDING_KILL", AnchorKind.STRUCTURE, 1, null, 100,
                        List.of(), false, "TOWER_BUILDING:BOTTOM_LANE:OUTER_TURRET",
                        "sanitized-event-v1"),
                anchor(708, 880_000L, 880_000L, 0,
                        "ELITE_MONSTER_KILL", AnchorKind.DRAGON, 2, null, 100,
                        List.of(), false, "DRAGON", "sanitized-event-v1"),
                anchor(709, 1_540_000L, 1_540_000L, 0,
                        "ELITE_MONSTER_KILL", AnchorKind.BARON, 7, null, 200,
                        List.of(), false, "BARON_NASHOR", "sanitized-event-v1"));
        return new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), base.participants(),
                observations, anchors, List.of(), base.observedEndItems(), base.coverage());
    }

    static MatchEvidenceSnapshot atSaturatedCapWithoutLateNovelKind() {
        return prefixStableSelection(false);
    }

    static MatchEvidenceSnapshot withLateNovelKindBeyondSaturatedCap() {
        return prefixStableSelection(true);
    }

    private static MatchEvidenceSnapshot prefixStableSelection(boolean includeLateNovelKind) {
        var base = overCapDirectionAndPhaseCoverage();
        var observations = new ArrayList<>(base.observations());
        var anchors = new ArrayList<>(base.anchors().stream()
                .filter(anchor -> anchor.key().representedAtMs() != 620_000L)
                .toList());
        if (includeLateNovelKind) {
            observations.addAll(observationsAt(
                    1_740_000L, teamTotals(16_000, 16_600),
                    620, false, "sanitized-observation-v1"));
            observations.addAll(observationsAt(
                    1_860_000L, teamTotals(16_200, 16_800),
                    630, false, "sanitized-observation-v1"));
            anchors.add(anchor(710, 1_800_000L, 1_800_000L, 0,
                    "BUILDING_KILL", AnchorKind.STRUCTURE, 6, null, 200,
                    List.of(), false, "TOWER_BUILDING:MID_LANE:INNER_TURRET",
                    "sanitized-event-v1"));
            anchors.add(anchor(711, 1_800_000L, 1_800_000L, 1,
                    "BUILDING_KILL", AnchorKind.STRUCTURE, 1, null, 100,
                    List.of(), false, "TOWER_BUILDING:TOP_LANE:INNER_TURRET",
                    "sanitized-event-v1"));
        }
        return new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), base.participants(),
                observations, anchors, List.of(), base.observedEndItems(), base.coverage());
    }

    static MatchEvidenceSnapshot withRevision(
            UUID detailCaptureId,
            UUID timelineCaptureId,
            String materializationVersion,
            String observationMethod,
            String eventMethod) {
        var base = snapshot(0, false, false, materializationVersion,
                observationMethod, eventMethod);
        return new MatchEvidenceSnapshot(
                base.header(),
                new MatchSourceRevision(
                        MATCH_ID, 11, detailCaptureId, timelineCaptureId,
                        materializationVersion),
                base.participants(),
                replaceObservationCapture(base.observations(), timelineCaptureId),
                replaceAnchorCapture(base.anchors(), timelineCaptureId),
                replaceItemCapture(base.itemTransitions(), timelineCaptureId),
                base.observedEndItems(),
                replaceCoverageCapture(base.coverage(), timelineCaptureId));
    }

    private static MatchEvidenceSnapshot snapshot(
            long rowIdOffset,
            boolean alterFuture,
            boolean reverseInputs,
            String materializationVersion,
            String observationMethod,
            String eventMethod) {
        var participants = participants(alterFuture);
        var observations = observations(rowIdOffset, alterFuture, observationMethod);
        var anchors = anchors(rowIdOffset, alterFuture, eventMethod);
        var itemTransitions = itemTransitions(rowIdOffset, alterFuture, eventMethod);
        var coverage = coverage(rowIdOffset);
        if (reverseInputs) {
            participants = reversed(participants);
            observations = reversed(observations);
            anchors = reversed(anchors);
            itemTransitions = reversed(itemTransitions);
            coverage = reversed(coverage);
        }
        return new MatchEvidenceSnapshot(
                new MatchHeader(
                        MATCH_ID, 420, 11, "CLASSIC", "MATCHED_GAME",
                        "16.17.810.4348", "2", 1_788_451_200_000L,
                        1_788_451_210_000L, 1_788_453_286_000L, 2_076_000L),
                new MatchSourceRevision(
                        MATCH_ID, 11, DETAIL_CAPTURE_ID, TIMELINE_CAPTURE_ID,
                        materializationVersion),
                participants,
                observations,
                anchors,
                itemTransitions,
                observedEndItems(),
                coverage);
    }

    private static List<MatchParticipant> participants(boolean alterFuture) {
        var result = new ArrayList<MatchParticipant>();
        var positions = List.of(
                "TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY",
                "TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY");
        for (int participantId = 1; participantId <= 10; participantId++) {
            result.add(new MatchParticipant(
                    participantId,
                    participantId <= 5 ? 100 : 200,
                    700 + participantId,
                    "Synthetic" + participantId,
                    positions.get(participantId - 1),
                    alterFuture ? participantId <= 5 : participantId > 5));
        }
        return List.copyOf(result);
    }

    private static List<ParticipantObservation> observations(
            long rowIdOffset, boolean alterFuture, String methodVersion) {
        long[] representedTimes = {
            480_210L, 600_228L, 780_275L, 900_291L,
            1_080_335L, 1_200_389L, 1_500_511L, 1_620_521L
        };
        int[][] totalGold = {
            {4700, 4720, 4740, 4700, 4711, 4600, 4620, 4580, 4600, 4600},
            {4830, 4840, 4860, 4810, 4809, 5000, 5010, 4970, 5010, 5010},
            {5700, 5680, 5720, 5660, 5690, 5750, 5730, 5710, 5690, 5670},
            {6200, 6180, 6220, 6160, 6190, 6550, 6300, 6260, 6240, 6220},
            {7350, 7310, 7280, 7260, 7240, 7480, 7390, 7340, 7300, 7280},
            {7900, 7840, 7790, 7750, 7720, 7750, 7680, 7640, 7600, 7560},
            {9800, 9700, 9620, 9560, 9500, 10_050, 9900, 9800, 9700, 9600},
            {10_500, 10_350, 10_250, 10_150, 10_050,
                    10_300, 10_250, 10_200, 10_150, 10_100}
        };
        var result = new ArrayList<ParticipantObservation>();
        long recordId = 100 + rowIdOffset;
        for (int sample = 0; sample < representedTimes.length; sample++) {
            var sampleObservations = observationsAt(
                    representedTimes[sample], totalGold[sample], recordId,
                    alterFuture && representedTimes[sample] > 1_200_389L,
                    methodVersion);
            result.addAll(sampleObservations);
            recordId += 10;
        }
        return List.copyOf(result);
    }

    private static List<ParticipantObservation> observationsAt(
            long representedAtMs,
            int[] totalGold,
            long recordId,
            boolean alterTeamOneGold,
            String methodVersion) {
        var result = new ArrayList<ParticipantObservation>();
        for (int participantId = 1; participantId <= 10; participantId++) {
            var total = totalGold[participantId - 1];
            if (alterTeamOneGold && participantId <= 5) {
                total += 75_000;
            }
            result.add(new ParticipantObservation(
                    participantId,
                    representedAtMs,
                    1_000 + participantId * 120,
                    2_000 + participantId * 90,
                    total % 1_000,
                    total,
                    8,
                    3_000 + participantId * 10,
                    60 + participantId,
                    participantId == 2 || participantId == 7 ? 30 : 0,
                    evidence(recordId++, representedAtMs, methodVersion,
                            TIMELINE_CAPTURE_ID)));
        }
        return List.copyOf(result);
    }

    private static int[] teamTotals(int opponentTeamGold, int focalTeamGold) {
        return new int[] {
            opponentTeamGold, opponentTeamGold, opponentTeamGold,
            opponentTeamGold, opponentTeamGold,
            focalTeamGold, focalTeamGold, focalTeamGold, focalTeamGold, focalTeamGold
        };
    }

    private static List<MatchAnchor> anchors(
            long rowIdOffset, boolean alterFuture, String methodVersion) {
        var result = new ArrayList<MatchAnchor>();
        result.add(anchor(301 + rowIdOffset, 540_215, 540_000, 1,
                "CHAMPION_KILL", AnchorKind.CHAMPION_KILL, 6, 1, null,
                List.of(7), true, null, methodVersion));
        result.add(anchor(302 + rowIdOffset, 570_220, 570_000, 2,
                "ELITE_MONSTER_KILL", AnchorKind.DRAGON, 2, null, 100,
                List.of(), false, "DRAGON", methodVersion));
        result.add(anchor(303 + rowIdOffset, 840_281, 840_000, 0,
                "ELITE_MONSTER_KILL", AnchorKind.DRAGON, 7, null, 200,
                List.of(), false, "DRAGON", methodVersion));
        result.add(anchor(304 + rowIdOffset, 870_286, 870_000, 1,
                "BUILDING_KILL", AnchorKind.STRUCTURE, 6, null, 200,
                List.of(), false, "TOWER_BUILDING:TOP_LANE:OUTER_TURRET", methodVersion));
        result.add(anchor(305 + rowIdOffset, 1_140_360, 1_140_000, 0,
                "CHAMPION_KILL", AnchorKind.CHAMPION_KILL, 1, 6, null,
                List.of(2), true, null, methodVersion));
        result.add(anchor(306 + rowIdOffset, 1_170_375, 1_170_000, 1,
                "ELITE_MONSTER_KILL", AnchorKind.HERALD, 2, null, 100,
                List.of(), false, "RIFTHERALD", methodVersion));
        if (!alterFuture) {
            result.add(anchor(307 + rowIdOffset, 1_530_513, 1_530_000, 0,
                    "ITEM_PURCHASED", AnchorKind.ITEM, 6, null, null,
                    List.of(), false, "ITEM:3006", methodVersion));
            result.add(anchor(308 + rowIdOffset, 1_560_516, 1_560_000, 1,
                    "CHAMPION_KILL", AnchorKind.CHAMPION_KILL, 6, 2, null,
                    List.of(7), true, null, methodVersion));
            result.add(anchor(309 + rowIdOffset, 1_560_516, 1_560_000, 2,
                    "TURRET_PLATE_DESTROYED", AnchorKind.TURRET_PLATE, 1, null, 100,
                    List.of(), false, "TOWER_BUILDING:TOP_LANE:OUTER_TURRET", methodVersion));
            result.add(anchor(310 + rowIdOffset, 1_590_518, 1_590_000, 0,
                    "ITEM_SOLD", AnchorKind.ITEM, 6, null, null,
                    List.of(), false, "ITEM:2006", methodVersion));
        } else {
            result.add(anchor(399 + rowIdOffset, 1_950_000, 1_950_000, 0,
                    "ELITE_MONSTER_KILL", AnchorKind.BARON, 1, null, 100,
                    List.of(), false, "BARON_NASHOR", methodVersion));
        }
        return List.copyOf(result);
    }

    private static List<ItemTransition> itemTransitions(
            long rowIdOffset, boolean alterFuture, String methodVersion) {
        if (alterFuture) {
            return List.of();
        }
        return List.of(
                new ItemTransition(
                        key(1_530_513, 1_530_000, 0, "ITEM_PURCHASED"),
                        6, 3_006, null, null,
                        evidence(307 + rowIdOffset, 1_530_513, methodVersion,
                                TIMELINE_CAPTURE_ID)),
                new ItemTransition(
                        key(1_590_518, 1_590_000, 0, "ITEM_SOLD"),
                        6, 2_006, null, null,
                        evidence(310 + rowIdOffset, 1_590_518, methodVersion,
                                TIMELINE_CAPTURE_ID)));
    }

    private static List<SourceCoverage> coverage(long rowIdOffset) {
        var signals = List.of(
                "participant_positions", "economy_snapshots", "item_events",
                "objective_events", "champion_kills");
        var result = new ArrayList<SourceCoverage>();
        for (int index = 0; index < signals.size(); index++) {
            result.add(new SourceCoverage(
                    signals.get(index), "MATCH_TIMELINE", CoverageStatus.OBSERVED,
                    0L, 2_076_000L, uuid(401 + rowIdOffset + index),
                    TIMELINE_CAPTURE_ID, "sanitized-coverage-v1"));
        }
        return List.copyOf(result);
    }

    private static Map<Integer, List<Integer>> observedEndItems() {
        var result = new LinkedHashMap<Integer, List<Integer>>();
        for (int participantId = 1; participantId <= 10; participantId++) {
            result.put(participantId, List.of(
                    1_000 + participantId, 2_000 + participantId,
                    0, 0, 0, 0, participantId <= 5 ? 3340 : 3364));
        }
        return result;
    }

    private static MatchAnchor anchor(
            long recordId,
            long representedAtMs,
            long frameAtMs,
            int eventIndex,
            String providerType,
            AnchorKind kind,
            Integer actorId,
            Integer targetId,
            Integer teamId,
            List<Integer> assisters,
            boolean assistersObserved,
            String descriptor,
            String methodVersion) {
        return new MatchAnchor(
                key(representedAtMs, frameAtMs, eventIndex, providerType),
                kind,
                actorId,
                targetId,
                teamId,
                assisters,
                assistersObserved,
                5_000 + eventIndex,
                6_000 + eventIndex,
                descriptor,
                List.of(evidence(recordId, representedAtMs, methodVersion,
                        TIMELINE_CAPTURE_ID)),
                Set.of());
    }

    private static TimelineEventKey key(
            long representedAtMs, long frameAtMs, int eventIndex, String providerType) {
        return new TimelineEventKey(
                MATCH_ID, representedAtMs, frameAtMs, eventIndex, providerType);
    }

    private static EvidenceReference evidence(
            long recordId, long representedAtMs, String methodVersion, UUID captureId) {
        return new EvidenceReference(
                captureId, uuid(recordId), representedAtMs, methodVersion);
    }

    private static List<ParticipantObservation> replaceObservationCapture(
            List<ParticipantObservation> observations, UUID captureId) {
        return observations.stream().map(observation -> new ParticipantObservation(
                observation.participantId(), observation.representedAtMs(),
                observation.x(), observation.y(), observation.currentGold(),
                observation.totalGold(), observation.level(), observation.xp(),
                observation.minionsKilled(), observation.jungleMinionsKilled(),
                new EvidenceReference(
                        captureId, observation.evidence().sourceRecordId(),
                        observation.evidence().representedAtMs(),
                        observation.evidence().methodVersion()))).toList();
    }

    private static List<MatchAnchor> replaceAnchorCapture(
            List<MatchAnchor> anchors, UUID captureId) {
        return anchors.stream().map(anchor -> new MatchAnchor(
                anchor.key(), anchor.kind(), anchor.actorParticipantId(),
                anchor.targetParticipantId(), anchor.teamId(),
                anchor.assisterParticipantIds(), anchor.assistersObserved(),
                anchor.positionX(), anchor.positionY(), anchor.descriptor(),
                anchor.evidenceReferences().stream().map(reference ->
                        new EvidenceReference(
                                captureId, reference.sourceRecordId(),
                                reference.representedAtMs(), reference.methodVersion()))
                        .toList(),
                anchor.limitationCodes())).toList();
    }

    private static List<ItemTransition> replaceItemCapture(
            List<ItemTransition> transitions, UUID captureId) {
        return transitions.stream().map(transition -> new ItemTransition(
                transition.key(), transition.actorParticipantId(), transition.itemId(),
                transition.beforeId(), transition.afterId(),
                transition.evidenceReferences().stream().map(reference ->
                        new EvidenceReference(
                                captureId, reference.sourceRecordId(),
                                reference.representedAtMs(), reference.methodVersion()))
                        .toList())).toList();
    }

    private static List<SourceCoverage> replaceCoverageCapture(
            List<SourceCoverage> coverage, UUID captureId) {
        return coverage.stream().map(item -> new SourceCoverage(
                item.signal(), item.sourceKind(), item.status(), item.representedStartMs(),
                item.representedEndMs(), item.sourceRecordId(), captureId,
                item.methodVersion())).toList();
    }

    private static <T> List<T> reversed(List<T> values) {
        var reversed = new ArrayList<>(values);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
