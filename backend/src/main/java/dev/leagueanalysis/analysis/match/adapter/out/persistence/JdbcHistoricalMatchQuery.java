package dev.leagueanalysis.analysis.match.adapter.out.persistence;

import dev.leagueanalysis.analysis.match.application.HistoricalMatchQuery;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcHistoricalMatchQuery implements HistoricalMatchQuery {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate readSnapshots;

    public JdbcHistoricalMatchQuery(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.readSnapshots = new TransactionTemplate(transactionManager);
        this.readSnapshots.setReadOnly(true);
        this.readSnapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public <T> T inReadSnapshot(Supplier<T> work) {
        var requiredWork = Objects.requireNonNull(work, "work");
        return readSnapshots.execute(status -> requiredWork.get());
    }

    @Override
    public Optional<MatchEvidenceSnapshot> load(String matchId) {
        var normalizedMatchId = requireMatchId(matchId);
        var headerAndRevision = loadHeaderAndRevision(normalizedMatchId);
        if (headerAndRevision.isEmpty()) {
            return Optional.empty();
        }
        var match = headerAndRevision.orElseThrow();
        return Optional.of(new MatchEvidenceSnapshot(
                match.header(),
                match.revision(),
                loadParticipants(normalizedMatchId),
                loadObservations(normalizedMatchId),
                loadAnchors(normalizedMatchId),
                loadItemTransitions(normalizedMatchId),
                loadObservedEndItems(normalizedMatchId),
                loadCoverage(normalizedMatchId)));
    }

    private Optional<HeaderAndRevision> loadHeaderAndRevision(String matchId) {
        return jdbc.query("""
                select match_id, queue_id, map_id, game_mode, game_type, game_version,
                       data_version, game_creation_ms, game_start_ms, game_end_ms,
                       game_duration_seconds, detail_source_capture_id,
                       timeline_source_capture_id, materialization_version
                from league_analysis.riot_match
                where match_id = ?
                """, (resultSet, rowNumber) -> new HeaderAndRevision(
                        new MatchHeader(
                                resultSet.getString("match_id"),
                                resultSet.getInt("queue_id"),
                                resultSet.getInt("map_id"),
                                resultSet.getString("game_mode"),
                                resultSet.getString("game_type"),
                                resultSet.getString("game_version"),
                                resultSet.getString("data_version"),
                                resultSet.getLong("game_creation_ms"),
                                resultSet.getObject("game_start_ms", Long.class),
                                resultSet.getObject("game_end_ms", Long.class),
                                Math.multiplyExact(resultSet.getLong("game_duration_seconds"), 1_000)),
                        new MatchSourceRevision(
                                resultSet.getString("match_id"),
                                resultSet.getInt("map_id"),
                                resultSet.getObject("detail_source_capture_id", UUID.class),
                                resultSet.getObject("timeline_source_capture_id", UUID.class),
                                resultSet.getString("materialization_version"))), matchId)
                .stream()
                .findFirst();
    }

    private List<MatchParticipant> loadParticipants(String matchId) {
        return List.copyOf(jdbc.query("""
                select participant_id, team_id, champion_id, champion_name, team_position, win
                from league_analysis.riot_participant
                where match_id = ?
                order by participant_id
                """, (resultSet, rowNumber) -> new MatchParticipant(
                        resultSet.getInt("participant_id"),
                        resultSet.getInt("team_id"),
                        resultSet.getInt("champion_id"),
                        resultSet.getString("champion_name"),
                        resultSet.getString("team_position"),
                        resultSet.getBoolean("win")), matchId));
    }

    private List<ParticipantObservation> loadObservations(String matchId) {
        return List.copyOf(jdbc.query("""
                select id, participant_id, represented_at_ms, x, y, current_gold,
                       total_gold, level, xp, minions_killed, jungle_minions_killed,
                       source_capture_id, method_version
                from league_analysis.participant_state_observation
                where match_id = ?
                order by represented_at_ms, participant_id, source_capture_id, id
                """, (resultSet, rowNumber) -> new ParticipantObservation(
                        resultSet.getInt("participant_id"),
                        resultSet.getLong("represented_at_ms"),
                        nullableInteger(resultSet, "x"),
                        nullableInteger(resultSet, "y"),
                        resultSet.getInt("current_gold"),
                        resultSet.getInt("total_gold"),
                        resultSet.getInt("level"),
                        resultSet.getInt("xp"),
                        resultSet.getInt("minions_killed"),
                        resultSet.getInt("jungle_minions_killed"),
                        evidence(resultSet)), matchId));
    }

    private List<MatchAnchor> loadAnchors(String matchId) {
        var reports = jdbc.query("""
                select id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                       provider_event_type, canonical_event_kind, actor_participant_id,
                       target_participant_id, position_x, position_y, event_payload::text,
                       source_capture_id, method_version
                from league_analysis.match_event
                where match_id = ?
                order by represented_at_ms, frame_at_ms, frame_event_index,
                         provider_event_type, source_capture_id, id
                """, this::mapAnchor, matchId);
        var reportsByKey = new TreeMap<TimelineEventKey, List<MatchAnchor>>();
        for (var report : reports) {
            reportsByKey.computeIfAbsent(report.key(), ignored -> new ArrayList<>()).add(report);
        }
        return reportsByKey.values().stream().map(MatchAnchor::reconcile).toList();
    }

    private List<ItemTransition> loadItemTransitions(String matchId) {
        return List.copyOf(jdbc.query("""
                select id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                       provider_event_type, actor_participant_id, event_payload::text,
                       source_capture_id, method_version
                from league_analysis.match_event
                where match_id = ? and canonical_event_kind = 'ITEM'
                order by represented_at_ms, frame_at_ms, frame_event_index,
                         provider_event_type, source_capture_id, id
                """, this::mapItemTransition, matchId));
    }

    private Map<Integer, List<Integer>> loadObservedEndItems(String matchId) {
        var inventories = new LinkedHashMap<Integer, List<Integer>>();
        jdbc.query("""
                select participant_id, end_item_ids::text
                from league_analysis.riot_participant
                where match_id = ?
                order by participant_id
                """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getInt("participant_id"),
                        integerArray(resultSet.getString("end_item_ids"))), matchId)
                .forEach(entry -> inventories.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(inventories);
    }

    private List<SourceCoverage> loadCoverage(String matchId) {
        return List.copyOf(jdbc.query("""
                select id, source_kind, signal, status, represented_start_ms,
                       represented_end_ms, source_capture_id, method_version
                from league_analysis.evidence_coverage
                where match_id = ?
                order by signal, source_kind, method_version, status,
                         represented_start_ms nulls first, represented_end_ms nulls first,
                         source_capture_id nulls first, id
                """, (resultSet, rowNumber) -> new SourceCoverage(
                        resultSet.getString("signal"),
                        resultSet.getString("source_kind"),
                        CoverageStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("represented_start_ms", Long.class),
                        resultSet.getObject("represented_end_ms", Long.class),
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("source_capture_id", UUID.class),
                        resultSet.getString("method_version")), matchId));
    }

    private MatchAnchor mapAnchor(ResultSet resultSet, int rowNumber) throws SQLException {
        var payload = parsePayload(resultSet);
        var assisters = assistingParticipantIds(payload);
        var ownership = anchorOwnership(payload);
        var canonicalKind = resultSet.getString("canonical_event_kind");
        var providerType = resultSet.getString("provider_event_type");
        return new MatchAnchor(
                eventKey(resultSet),
                anchorKind(canonicalKind, providerType, payload),
                nullableInteger(resultSet, "actor_participant_id"),
                nullableInteger(resultSet, "target_participant_id"),
                ownership.teamId(),
                assisters.values(),
                assisters.observed(),
                nullableInteger(resultSet, "position_x"),
                nullableInteger(resultSet, "position_y"),
                descriptor(canonicalKind, payload),
                List.of(evidence(resultSet)),
                ownership.ambiguous()
                        ? Set.of("AMBIGUOUS_ANCHOR_RELATION")
                        : Set.of());
    }

    private ItemTransition mapItemTransition(ResultSet resultSet, int rowNumber) throws SQLException {
        var payload = parsePayload(resultSet);
        return new ItemTransition(
                eventKey(resultSet),
                nullableInteger(resultSet, "actor_participant_id"),
                nullableInteger(payload, "itemId"),
                nullableInteger(payload, "beforeId"),
                nullableInteger(payload, "afterId"),
                evidence(resultSet));
    }

    private AnchorKind anchorKind(String canonicalKind, String providerType, JsonNode payload) {
        if (providerType.equals("BUILDING_KILL")) {
            return AnchorKind.STRUCTURE;
        }
        if (providerType.equals("TURRET_PLATE_DESTROYED")) {
            return AnchorKind.TURRET_PLATE;
        }
        if (canonicalKind.equals("CHAMPION_KILL")) {
            return AnchorKind.CHAMPION_KILL;
        }
        if (canonicalKind.equals("ITEM")) {
            return AnchorKind.ITEM;
        }
        if (canonicalKind.equals("PROGRESSION")) {
            return AnchorKind.PROGRESSION;
        }
        var monsterType = text(payload, "monsterType");
        if ("DRAGON".equals(monsterType)) {
            return AnchorKind.DRAGON;
        }
        if ("BARON_NASHOR".equals(monsterType)) {
            return AnchorKind.BARON;
        }
        if ("RIFTHERALD".equals(monsterType)) {
            return AnchorKind.HERALD;
        }
        return AnchorKind.OTHER;
    }

    private String descriptor(String canonicalKind, JsonNode payload) {
        if (canonicalKind.equals("ITEM")) {
            var itemId = firstInteger(payload, "itemId", "beforeId", "afterId");
            return itemId == null ? null : "ITEM:" + itemId;
        }
        var monsterType = text(payload, "monsterType");
        if (monsterType != null) {
            return joinDescriptor(monsterType, text(payload, "monsterSubType"));
        }
        return joinDescriptor(
                text(payload, "buildingType"),
                text(payload, "laneType"),
                text(payload, "towerType"));
    }

    private TimelineEventKey eventKey(ResultSet resultSet) throws SQLException {
        return new TimelineEventKey(
                resultSet.getString("match_id"),
                resultSet.getLong("represented_at_ms"),
                resultSet.getLong("frame_at_ms"),
                resultSet.getInt("frame_event_index"),
                resultSet.getString("provider_event_type"));
    }

    private EvidenceReference evidence(ResultSet resultSet) throws SQLException {
        return new EvidenceReference(
                resultSet.getObject("source_capture_id", UUID.class),
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("represented_at_ms"),
                resultSet.getString("method_version"));
    }

    private JsonNode parsePayload(ResultSet resultSet) throws SQLException {
        return json.readTree(resultSet.getString("event_payload"));
    }

    private List<Integer> integerArray(String encodedJson) {
        var node = json.readTree(encodedJson);
        if (!node.isArray() || node.size() != 7) {
            return List.of();
        }
        var values = new ArrayList<Integer>();
        for (var value : node) {
            var integer = nullableInteger(value);
            if (integer == null) {
                return List.of();
            }
            values.add(integer);
        }
        return List.copyOf(values);
    }

    private AssistingParticipantIds assistingParticipantIds(JsonNode payload) {
        var node = payload.get("assistingParticipantIds");
        if (node == null || !node.isArray()) {
            return AssistingParticipantIds.unavailable();
        }
        var values = new ArrayList<Integer>();
        for (var value : node) {
            var integer = nullableInteger(value);
            if (integer == null || integer < 1 || integer > 10) {
                return AssistingParticipantIds.unavailable();
            }
            values.add(integer);
        }
        return new AssistingParticipantIds(List.copyOf(values), true);
    }

    private AnchorOwnership anchorOwnership(JsonNode payload) {
        var killerTeamId = nullableInteger(payload, "killerTeamId");
        var teamId = nullableInteger(payload, "teamId");
        if (killerTeamId != null && teamId != null && !killerTeamId.equals(teamId)) {
            return AnchorOwnership.ambiguousOwnership();
        }
        return new AnchorOwnership(killerTeamId != null ? killerTeamId : teamId, false);
    }

    private Integer firstInteger(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = nullableInteger(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Integer nullableInteger(JsonNode node, String field) {
        return nullableInteger(node.get(field));
    }

    private Integer nullableInteger(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue()
                : null;
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, Integer.class);
    }

    private String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
    }

    private String joinDescriptor(String... parts) {
        var present = java.util.Arrays.stream(parts).filter(Objects::nonNull).toList();
        return present.isEmpty() ? null : String.join(":", present);
    }

    private String requireMatchId(String matchId) {
        return TimelineEventKey.requireText(matchId);
    }

    private record HeaderAndRevision(MatchHeader header, MatchSourceRevision revision) {}

    private record AssistingParticipantIds(List<Integer> values, boolean observed) {
        private static AssistingParticipantIds unavailable() {
            return new AssistingParticipantIds(List.of(), false);
        }
    }

    private record AnchorOwnership(Integer teamId, boolean ambiguous) {
        private static AnchorOwnership ambiguousOwnership() {
            return new AnchorOwnership(null, true);
        }
    }
}
