package dev.leagueanalysis.analysis.death.adapter.out.persistence;

import dev.leagueanalysis.analysis.death.application.HistoricalDeathQuery;
import dev.leagueanalysis.analysis.death.domain.DeathEvent;
import dev.leagueanalysis.analysis.death.domain.ObjectiveEvent;
import dev.leagueanalysis.analysis.death.domain.PriorParticipantObservation;
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
public class JdbcHistoricalDeathQuery implements HistoricalDeathQuery {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate readSnapshots;

    public JdbcHistoricalDeathQuery(
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
    public Optional<MatchSourceRevision> findRevision(String matchId) {
        var rows = jdbc.query("""
                select match_id, map_id, detail_source_capture_id,
                       timeline_source_capture_id, materialization_version
                from league_analysis.riot_match
                where match_id = ?
                """, (resultSet, rowNumber) -> new MatchSourceRevision(
                        resultSet.getString("match_id"),
                        resultSet.getInt("map_id"),
                        resultSet.getObject("detail_source_capture_id", UUID.class),
                        resultSet.getObject("timeline_source_capture_id", UUID.class),
                        resultSet.getString("materialization_version")),
                requireMatchId(matchId));
        return rows.stream().findFirst();
    }

    @Override
    public List<Integer> findParticipantIds(String matchId) {
        return List.copyOf(jdbc.queryForList("""
                select participant_id
                from league_analysis.riot_participant
                where match_id = ?
                order by participant_id
                """, Integer.class, requireMatchId(matchId)));
    }

    @Override
    public List<DeathEvent> findDeaths(String matchId) {
        var reports = jdbc.query("""
                select id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                       provider_event_type, actor_participant_id, target_participant_id,
                       position_x, position_y, event_payload::text, source_capture_id,
                       method_version
                from league_analysis.match_event
                where match_id = ? and canonical_event_kind = 'CHAMPION_KILL'
                order by represented_at_ms, frame_at_ms, frame_event_index, provider_event_type,
                         source_capture_id, id
                """, this::mapDeath, requireMatchId(matchId));
        return reconcileDeaths(reports);
    }

    @Override
    public Optional<DeathEvent> findDeath(String matchId, long frameAtMs, int frameEventIndex) {
        if (frameAtMs < 0 || frameEventIndex < 0) {
            throw new IllegalArgumentException("NEGATIVE_EVENT_ORDER_VALUE");
        }
        var reports = jdbc.query("""
                select id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                       provider_event_type, actor_participant_id, target_participant_id,
                       position_x, position_y, event_payload::text, source_capture_id,
                       method_version
                from league_analysis.match_event
                where match_id = ? and frame_at_ms = ? and frame_event_index = ?
                  and canonical_event_kind = 'CHAMPION_KILL'
                order by provider_event_type, represented_at_ms, source_capture_id, id
                """, this::mapDeath, requireMatchId(matchId), frameAtMs, frameEventIndex);
        return reconcileDeaths(reports).stream().findFirst();
    }

    @Override
    public List<PriorParticipantObservation> findLatestPriorObservations(
            String matchId, long representedBeforeMs) {
        if (representedBeforeMs < 0) {
            throw new IllegalArgumentException("NEGATIVE_OBSERVATION_BOUND");
        }
        return List.copyOf(jdbc.query("""
                select id, participant_id, represented_at_ms, x, y, current_gold,
                       total_gold, level, xp, minions_killed, jungle_minions_killed,
                       source_capture_id, method_version
                from (
                    select observation.*,
                           dense_rank() over (
                               partition by participant_id
                               order by represented_at_ms desc
                           ) as recency_rank
                    from league_analysis.participant_state_observation observation
                    where match_id = ? and represented_at_ms < ?
                ) ranked
                where recency_rank = 1
                order by participant_id, represented_at_ms desc, id
                """, this::mapObservation,
                requireMatchId(matchId), representedBeforeMs));
    }

    @Override
    public List<ItemTransition> findItemTransitionsBefore(
            String matchId, TimelineEventKey exclusiveUpperBound) {
        var normalizedMatchId = requireMatchId(matchId);
        var bound = Objects.requireNonNull(exclusiveUpperBound, "exclusiveUpperBound");
        if (!normalizedMatchId.equals(bound.matchId())) {
            throw new IllegalArgumentException("MISMATCHED_EVENT_BOUND_MATCH");
        }
        return List.copyOf(jdbc.query("""
                select id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                       provider_event_type, actor_participant_id, event_payload::text,
                       source_capture_id, method_version
                from league_analysis.match_event
                where match_id = ? and canonical_event_kind = 'ITEM'
                  and (represented_at_ms, frame_at_ms, frame_event_index, provider_event_type)
                      < (?, ?, ?, ?)
                order by represented_at_ms, frame_at_ms, frame_event_index, provider_event_type
                """, this::mapItemTransition,
                normalizedMatchId,
                bound.representedAtMs(),
                bound.frameAtMs(),
                bound.frameEventIndex(),
                bound.providerEventType()));
    }

    @Override
    public List<ItemTransition> findAllItemTransitions(String matchId) {
        return List.copyOf(jdbc.query("""
                select id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                       provider_event_type, actor_participant_id, event_payload::text,
                       source_capture_id, method_version
                from league_analysis.match_event
                where match_id = ? and canonical_event_kind = 'ITEM'
                order by represented_at_ms, frame_at_ms, frame_event_index, provider_event_type
                """, this::mapItemTransition, requireMatchId(matchId)));
    }

    @Override
    public Map<Integer, List<Integer>> findObservedEndItems(String matchId) {
        var inventories = new LinkedHashMap<Integer, List<Integer>>();
        var rows = jdbc.query("""
                select participant_id, end_item_ids::text
                from league_analysis.riot_participant
                where match_id = ?
                order by participant_id
                """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getInt("participant_id"),
                        integerArray(resultSet.getString("end_item_ids"))),
                requireMatchId(matchId));
        rows.forEach(entry -> inventories.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(inventories);
    }

    @Override
    public List<ObjectiveEvent> findObjectives(
            String matchId, long representedStartMs, long representedEndMs) {
        if (representedStartMs < 0 || representedEndMs < representedStartMs) {
            throw new IllegalArgumentException("INVALID_OBJECTIVE_BOUNDS");
        }
        return List.copyOf(jdbc.query("""
                select id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                       provider_event_type, actor_participant_id, position_x, position_y,
                       event_payload::text, source_capture_id, method_version
                from league_analysis.match_event
                where match_id = ? and canonical_event_kind = 'OBJECTIVE'
                  and represented_at_ms between ? and ?
                order by represented_at_ms, frame_at_ms, frame_event_index, provider_event_type
                """, this::mapObjective,
                requireMatchId(matchId), representedStartMs, representedEndMs));
    }

    @Override
    public List<SourceCoverage> findCoverage(String matchId, Set<String> signals) {
        requireMatchId(matchId);
        Objects.requireNonNull(signals, "signals");
        if (signals.isEmpty()) {
            return List.of();
        }
        if (signals.stream().anyMatch(signal -> signal == null || signal.isBlank())) {
            throw new IllegalArgumentException("BLANK_COVERAGE_SIGNAL");
        }
        var placeholders = String.join(", ", Collections.nCopies(signals.size(), "?"));
        var arguments = new ArrayList<Object>(signals.size() + 1);
        arguments.add(matchId.strip());
        arguments.addAll(signals);
        return List.copyOf(jdbc.query("""
                select id, source_kind, signal, status, represented_start_ms,
                       represented_end_ms, source_capture_id, method_version
                from league_analysis.evidence_coverage
                where match_id = ? and signal in (%s)
                order by signal, source_kind, method_version, status,
                         represented_start_ms nulls first, represented_end_ms nulls first,
                         source_capture_id nulls first, id
                """.formatted(placeholders), this::mapCoverage, arguments.toArray()));
    }

    private DeathEvent mapDeath(ResultSet resultSet, int rowNumber) throws SQLException {
        var payload = parsePayload(resultSet);
        var assisterIds = assistingParticipantIds(payload);
        return new DeathEvent(
                eventKey(resultSet),
                nullableInteger(resultSet, "target_participant_id"),
                nullableInteger(resultSet, "actor_participant_id"),
                assisterIds.values(),
                assisterIds.observed(),
                nullableInteger(resultSet, "position_x"),
                nullableInteger(resultSet, "position_y"),
                evidence(resultSet));
    }

    private SourceCoverage mapCoverage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SourceCoverage(
                resultSet.getString("signal"),
                resultSet.getString("source_kind"),
                dev.leagueanalysis.evidence.domain.CoverageStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("represented_start_ms", Long.class),
                resultSet.getObject("represented_end_ms", Long.class),
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("source_capture_id", UUID.class),
                resultSet.getString("method_version"));
    }

    private List<DeathEvent> reconcileDeaths(List<DeathEvent> reports) {
        var reportsByKey = new TreeMap<TimelineEventKey, List<DeathEvent>>();
        for (var report : reports) {
            reportsByKey.computeIfAbsent(report.key(), ignored -> new ArrayList<>()).add(report);
        }
        return reportsByKey.values().stream()
                .map(DeathEvent::reconcile)
                .toList();
    }

    private PriorParticipantObservation mapObservation(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new PriorParticipantObservation(
                resultSet.getInt("participant_id"),
                nullableInteger(resultSet, "x"),
                nullableInteger(resultSet, "y"),
                resultSet.getInt("current_gold"),
                resultSet.getInt("total_gold"),
                resultSet.getInt("level"),
                resultSet.getInt("xp"),
                resultSet.getInt("minions_killed"),
                resultSet.getInt("jungle_minions_killed"),
                evidence(resultSet));
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

    private ObjectiveEvent mapObjective(ResultSet resultSet, int rowNumber) throws SQLException {
        var payload = parsePayload(resultSet);
        return new ObjectiveEvent(
                eventKey(resultSet),
                nullableInteger(resultSet, "actor_participant_id"),
                firstInteger(payload, "killerTeamId", "teamId"),
                objectiveDescriptor(payload),
                nullableInteger(resultSet, "position_x"),
                nullableInteger(resultSet, "position_y"),
                evidence(resultSet));
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
        return integerArray(json.readTree(encodedJson));
    }

    private List<Integer> integerArray(JsonNode node) {
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
            if (integer == null || integer < 1) {
                return AssistingParticipantIds.unavailable();
            }
            values.add(integer);
        }
        return new AssistingParticipantIds(List.copyOf(values), true);
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

    private String objectiveDescriptor(JsonNode payload) {
        var monsterType = text(payload, "monsterType");
        if (monsterType != null) {
            return joinDescriptor(monsterType, text(payload, "monsterSubType"));
        }
        return joinDescriptor(
                text(payload, "buildingType"),
                text(payload, "laneType"),
                text(payload, "towerType"));
    }

    private String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
    }

    private String joinDescriptor(String... parts) {
        var present = java.util.Arrays.stream(parts)
                .filter(Objects::nonNull)
                .toList();
        return present.isEmpty() ? null : String.join(":", present);
    }

    private String requireMatchId(String matchId) {
        var normalized = Objects.requireNonNull(matchId, "matchId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("BLANK_IDENTIFIER");
        }
        return normalized;
    }

    private record AssistingParticipantIds(List<Integer> values, boolean observed) {
        private static AssistingParticipantIds unavailable() {
            return new AssistingParticipantIds(List.of(), false);
        }
    }
}
