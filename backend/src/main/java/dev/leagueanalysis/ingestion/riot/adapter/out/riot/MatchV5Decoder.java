package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import dev.leagueanalysis.evidence.domain.CanonicalEventKind;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.ingestion.riot.domain.CapturedDocument;
import dev.leagueanalysis.ingestion.riot.domain.EvidenceCoverage;
import dev.leagueanalysis.ingestion.riot.domain.MatchEvent;
import dev.leagueanalysis.ingestion.riot.domain.MatchFact;
import dev.leagueanalysis.ingestion.riot.domain.ParticipantFact;
import dev.leagueanalysis.ingestion.riot.domain.ParticipantStateObservation;
import dev.leagueanalysis.ingestion.riot.domain.RiotMatchMaterialization;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import dev.leagueanalysis.ingestion.riot.domain.TeamFact;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

public class MatchV5Decoder {
    private static final String METHOD_VERSION = "match-v5-v1";
    private static final List<String> TIMELINE_SIGNALS = List.of(
            "participant_positions",
            "economy_snapshots",
            "progression_events",
            "item_transitions",
            "match_events",
            "summoner_casts",
            "health_resources",
            "cooldowns",
            "wave_state",
            "ward_positions",
            "vision_state");

    public RiotMatchMaterialization decode(
            CapturedDocument detail,
            Optional<CapturedDocument> timeline) {
        requireKind(detail, SourceKind.MATCH_DETAIL);
        timeline.ifPresent(document -> requireKind(document, SourceKind.MATCH_TIMELINE));

        var detailPayload = detail.document().payload();
        var metadata = requiredObject(detailPayload, "metadata");
        var info = requiredObject(detailPayload, "info");
        var matchId = requiredText(metadata, "matchId");
        var dataVersion = requiredText(metadata, "dataVersion");

        var teams = decodeTeams(matchId, requiredArray(info, "teams"));
        var participants = decodeParticipants(matchId, requiredArray(info, "participants"));
        var observations = new ArrayList<ParticipantStateObservation>();
        var events = new ArrayList<MatchEvent>();
        var coverage = new ArrayList<EvidenceCoverage>();

        Long timelineStart = null;
        Long timelineEnd = null;
        UUID timelineCaptureId = null;
        var timelineFrameCount = 0;
        var participantSnapshotsComplete = false;
        var positionsComplete = false;
        if (timeline.isPresent()) {
            var timelineDocument = timeline.orElseThrow();
            timelineCaptureId = timelineDocument.captureId();
            var timelinePayload = timelineDocument.document().payload();
            var timelineMetadata = requiredObject(timelinePayload, "metadata");
            if (!matchId.equals(requiredText(timelineMetadata, "matchId"))) {
                throw invalidPayload();
            }
            var frames = requiredArray(requiredObject(timelinePayload, "info"), "frames");
            if (frames.isEmpty()) {
                throw invalidPayload();
            }
            timelineFrameCount = frames.size();
            participantSnapshotsComplete = true;
            positionsComplete = true;
            var expectedParticipantIds = new HashSet<Integer>();
            participants.forEach(participant -> expectedParticipantIds.add(participant.participantId()));
            for (var frame : frames) {
                var frameAtMs = requiredLong(frame, "timestamp");
                timelineStart = timelineStart == null ? frameAtMs : Math.min(timelineStart, frameAtMs);
                timelineEnd = timelineEnd == null ? frameAtMs : Math.max(timelineEnd, frameAtMs);
                var frameCoverage = decodeObservations(
                        matchId,
                        frame,
                        frameAtMs,
                        timelineCaptureId,
                        expectedParticipantIds,
                        observations);
                participantSnapshotsComplete &= frameCoverage.completeParticipants();
                positionsComplete &= frameCoverage.completePositions();
                decodeEvents(matchId, frame, frameAtMs, timelineCaptureId, events);
            }
        }

        coverage.add(detailCoverage(matchId, detail.captureId()));
        coverage.addAll(timelineCoverage(
                matchId,
                timelineCaptureId,
                timelineStart,
                timelineEnd,
                timelineFrameCount,
                observations.size(),
                participantSnapshotsComplete,
                positionsComplete));

        var match = new MatchFact(
                matchId,
                requiredLong(info, "gameId"),
                requiredInt(info, "queueId"),
                requiredInt(info, "mapId"),
                requiredText(info, "gameMode"),
                requiredText(info, "gameType"),
                requiredText(info, "gameVersion"),
                dataVersion,
                requiredLong(info, "gameCreation"),
                optionalLong(info, "gameStartTimestamp"),
                optionalLong(info, "gameEndTimestamp"),
                requiredLong(info, "gameDuration"),
                detail.captureId(),
                timelineCaptureId,
                METHOD_VERSION);

        return new RiotMatchMaterialization(match, teams, participants, observations, events, coverage);
    }

    CanonicalEventKind classify(String type) {
        return switch (type) {
            case "ITEM_PURCHASED", "ITEM_SOLD", "ITEM_DESTROYED", "ITEM_UNDO" ->
                    CanonicalEventKind.ITEM;
            case "CHAMPION_KILL" -> CanonicalEventKind.CHAMPION_KILL;
            case "ELITE_MONSTER_KILL", "BUILDING_KILL", "TURRET_PLATE_DESTROYED",
                    "DRAGON_SOUL_GIVEN", "OBJECTIVE_BOUNTY_PRESTART", "OBJECTIVE_BOUNTY_FINISH" ->
                    CanonicalEventKind.OBJECTIVE;
            case "WARD_PLACED", "WARD_KILL" -> CanonicalEventKind.WARD;
            case "SKILL_LEVEL_UP", "LEVEL_UP" -> CanonicalEventKind.PROGRESSION;
            default -> CanonicalEventKind.OTHER;
        };
    }

    private List<TeamFact> decodeTeams(String matchId, JsonNode nodes) {
        var teams = new ArrayList<TeamFact>();
        for (var node : nodes) {
            teams.add(new TeamFact(
                    matchId,
                    requiredInt(node, "teamId"),
                    requiredBoolean(node, "win"),
                    requiredObject(node, "objectives")));
        }
        return teams;
    }

    private List<ParticipantFact> decodeParticipants(String matchId, JsonNode nodes) {
        var participants = new ArrayList<ParticipantFact>();
        for (var node : nodes) {
            participants.add(new ParticipantFact(
                    matchId,
                    requiredInt(node, "participantId"),
                    requiredText(node, "puuid"),
                    optionalText(node, "riotIdGameName"),
                    optionalText(node, "riotIdTagline"),
                    requiredInt(node, "teamId"),
                    requiredInt(node, "championId"),
                    requiredText(node, "championName"),
                    teamPosition(node),
                    requiredInt(node, "kills"),
                    requiredInt(node, "deaths"),
                    requiredInt(node, "assists"),
                    requiredInt(node, "totalMinionsKilled"),
                    requiredInt(node, "neutralMinionsKilled"),
                    requiredInt(node, "goldEarned"),
                    requiredInt(node, "goldSpent"),
                    requiredInt(node, "visionScore"),
                    requiredInt(node, "summoner1Id"),
                    requiredInt(node, "summoner2Id"),
                    requiredBoolean(node, "win"),
                    List.of(
                            requiredInt(node, "item0"),
                            requiredInt(node, "item1"),
                            requiredInt(node, "item2"),
                            requiredInt(node, "item3"),
                            requiredInt(node, "item4"),
                            requiredInt(node, "item5"),
                            requiredInt(node, "item6")), ParticipantDetailsDecoder.decode(node)));
        }
        return participants;
    }

    private ParticipantFrameCoverage decodeObservations(
            String matchId,
            JsonNode frame,
            long frameAtMs,
            UUID captureId,
            Set<Integer> expectedParticipantIds,
            List<ParticipantStateObservation> observations) {
        var participantFrames = requiredObject(frame, "participantFrames");
        var actualParticipantIds = new HashSet<Integer>();
        var completePositions = !participantFrames.isEmpty();
        for (var participantFrame : participantFrames) {
            var position = participantFrame.path("position");
            var participantId = requiredInt(participantFrame, "participantId");
            actualParticipantIds.add(participantId);
            var x = optionalInt(position, "x");
            var y = optionalInt(position, "y");
            completePositions &= x != null && y != null;
            observations.add(new ParticipantStateObservation(
                    UUID.randomUUID(),
                    matchId,
                    participantId,
                    frameAtMs,
                    x,
                    y,
                    requiredInt(participantFrame, "currentGold"),
                    requiredInt(participantFrame, "totalGold"),
                    requiredInt(participantFrame, "level"),
                    requiredInt(participantFrame, "xp"),
                    requiredInt(participantFrame, "minionsKilled"),
                    requiredInt(participantFrame, "jungleMinionsKilled"),
                    captureId,
                    METHOD_VERSION));
        }
        var completeParticipants = actualParticipantIds.equals(expectedParticipantIds)
                && actualParticipantIds.size() == participantFrames.size();
        return new ParticipantFrameCoverage(
                completeParticipants, completeParticipants && completePositions);
    }

    private void decodeEvents(
            String matchId,
            JsonNode frame,
            long frameAtMs,
            UUID captureId,
            List<MatchEvent> events) {
        var eventNodes = requiredArray(frame, "events");
        var eventIndex = 0;
        for (var event : eventNodes) {
            var position = event.path("position");
            events.add(new MatchEvent(
                    UUID.randomUUID(),
                    matchId,
                    requiredLong(event, "timestamp"),
                    frameAtMs,
                    eventIndex++,
                    requiredText(event, "type"),
                    classify(requiredText(event, "type")),
                    firstPresentInt(event, "participantId", "killerId", "creatorId"),
                    optionalInt(event, "victimId"),
                    optionalInt(position, "x"),
                    optionalInt(position, "y"),
                    event,
                    captureId,
                    METHOD_VERSION));
        }
    }

    private EvidenceCoverage detailCoverage(String matchId, UUID captureId) {
        var details = JsonNodeFactory.instance.objectNode().put("basis", "match-v5-detail");
        return new EvidenceCoverage(
                UUID.randomUUID(),
                matchId,
                SourceKind.MATCH_DETAIL,
                "match_roster_result_patch",
                CoverageStatus.OBSERVED,
                null,
                null,
                details,
                captureId,
                METHOD_VERSION);
    }

    private List<EvidenceCoverage> timelineCoverage(
            String matchId,
            UUID captureId,
            Long start,
            Long end,
            int frameCount,
            int observationCount,
            boolean participantSnapshotsComplete,
            boolean positionsComplete) {
        var coverage = new ArrayList<EvidenceCoverage>();
        for (var signal : TIMELINE_SIGNALS) {
            var available = captureId != null;
            var status = timelineStatus(
                    signal, available, participantSnapshotsComplete, positionsComplete);
            var details = JsonNodeFactory.instance.objectNode()
                    .put("basis", available ? "match-v5-timeline" : "timeline-not-captured");
            if (available) {
                details.put("frameCount", frameCount)
                        .put("observationCount", observationCount)
                        .put("completeParticipantSnapshots", participantSnapshotsComplete)
                        .put("completePositionSnapshots", positionsComplete);
            }
            coverage.add(new EvidenceCoverage(
                    UUID.randomUUID(),
                    matchId,
                    SourceKind.MATCH_TIMELINE,
                    signal,
                    status,
                    available ? start : null,
                    available ? end : null,
                    details,
                    captureId,
                    METHOD_VERSION));
        }
        return coverage;
    }

    private CoverageStatus timelineStatus(
            String signal,
            boolean available,
            boolean participantSnapshotsComplete,
            boolean positionsComplete) {
        if (!available) {
            return CoverageStatus.UNAVAILABLE;
        }
        return switch (signal) {
            case "participant_positions" -> positionsComplete
                    ? CoverageStatus.OBSERVED
                    : CoverageStatus.LIMITED;
            case "economy_snapshots" -> participantSnapshotsComplete
                    ? CoverageStatus.OBSERVED
                    : CoverageStatus.LIMITED;
            case "progression_events", "item_transitions", "match_events" -> CoverageStatus.OBSERVED;
            case "ward_positions" -> CoverageStatus.LIMITED;
            case "summoner_casts", "health_resources", "cooldowns", "wave_state", "vision_state" ->
                    CoverageStatus.UNAVAILABLE;
            default -> CoverageStatus.UNKNOWN;
        };
    }

    private void requireKind(CapturedDocument document, SourceKind expected) {
        if (document == null || document.document().kind() != expected) {
            throw invalidPayload();
        }
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || !value.isObject()) {
            throw invalidPayload();
        }
        return value;
    }

    private JsonNode requiredArray(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || !value.isArray()) {
            throw invalidPayload();
        }
        return value;
    }

    private String requiredText(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw invalidPayload();
        }
        return value.stringValue();
    }

    private String teamPosition(JsonNode node) {
        var value = optionalText(node, "teamPosition");
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private String optionalText(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw invalidPayload();
        }
        return value.stringValue();
    }

    private int requiredInt(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidPayload();
        }
        return value.intValue();
    }

    private Integer optionalInt(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidPayload();
        }
        return value.intValue();
    }

    private Integer firstPresentInt(JsonNode parent, String... fields) {
        for (var field : fields) {
            var value = optionalInt(parent, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private long requiredLong(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalidPayload();
        }
        return value.longValue();
    }

    private Long optionalLong(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalidPayload();
        }
        return value.longValue();
    }

    private boolean requiredBoolean(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalidPayload();
        }
        return value.booleanValue();
    }

    private RiotPayloadException invalidPayload() {
        return new RiotPayloadException("INVALID_REQUIRED_FIELD");
    }

    private record ParticipantFrameCoverage(
            boolean completeParticipants,
            boolean completePositions) {}
}
