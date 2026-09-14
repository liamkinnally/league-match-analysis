package dev.leagueanalysis.analysis.match.adapter.out.persistence;

import dev.leagueanalysis.analysis.match.application.MatchOverviewQuery;
import dev.leagueanalysis.analysis.match.domain.MatchDevelopment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcMatchOverviewQuery implements MatchOverviewQuery {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcMatchOverviewQuery(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Overview> load(String matchId) {
        var matches = jdbc.query("""
                select match_id, queue_id, map_id, game_mode, game_version,
                       game_creation_ms, game_duration_seconds
                from league_analysis.riot_match
                where match_id = ?
                """, (resultSet, rowNumber) -> new MatchRow(
                        resultSet.getString("match_id"),
                        resultSet.getInt("queue_id"),
                        resultSet.getInt("map_id"),
                        resultSet.getString("game_mode"),
                        resultSet.getString("game_version"),
                        resultSet.getLong("game_creation_ms"),
                        Math.multiplyExact(resultSet.getLong("game_duration_seconds"), 1_000)),
                matchId);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        var match = matches.getFirst();
        var names = jdbc.queryForObject("""
                select p.payload_json::text from league_analysis.riot_match m
                join league_analysis.source_capture c on c.id=m.detail_source_capture_id
                join league_analysis.source_payload p on p.id=c.source_payload_id where m.match_id=?
                """, String.class, matchId);
        var recordedNames = json.readTree(names).path("info").path("participants");
        var participants = jdbc.query("""
                select participant_id, team_id, champion_id, champion_name, team_position,
                       win, kills, deaths, assists, total_minions_killed,
                       neutral_minions_killed, gold_earned, gold_spent, vision_score,
                       summoner_spell_one_id, summoner_spell_two_id, end_item_ids::text,
                       rune_snapshot::text, participant_totals::text
                from league_analysis.riot_participant
                where match_id = ?
                order by team_id, participant_id
                """, (resultSet, rowNumber) -> new Participant(
                        resultSet.getInt("participant_id"),
                        resultSet.getInt("team_id"),
                        resultSet.getInt("champion_id"),
                        resultSet.getString("champion_name"),
                        resultSet.getString("team_position"),
                        resultSet.getBoolean("win"),
                        resultSet.getInt("kills"),
                        resultSet.getInt("deaths"),
                        resultSet.getInt("assists"),
                        resultSet.getInt("total_minions_killed"),
                        resultSet.getInt("neutral_minions_killed"),
                        resultSet.getInt("gold_earned"),
                        resultSet.getInt("gold_spent"),
                        resultSet.getInt("vision_score"),
                        resultSet.getInt("summoner_spell_one_id"),
                        resultSet.getInt("summoner_spell_two_id"),
                        itemIds(resultSet.getString("end_item_ids")),
                        name(recordedNames, resultSet.getInt("participant_id"), "riotIdGameName"),
                        name(recordedNames, resultSet.getInt("participant_id"), "riotIdTagline"),
                        name(recordedNames, resultSet.getInt("participant_id"), "summonerName"),
                        optionalJson(resultSet.getString("rune_snapshot"), dev.leagueanalysis.ingestion.riot.domain.ParticipantDetails.Runes.class),
                        optionalJson(resultSet.getString("participant_totals"), dev.leagueanalysis.ingestion.riot.domain.ParticipantDetails.Totals.class)),
                matchId);
        return Optional.of(new Overview(
                match.matchId(), match.queueId(), match.mapId(), match.gameMode(), match.gameVersion(),
                match.gameCreationMs(), match.durationMs(), participants, teams(match, participants), events(matchId, participants)));
    }

    private <T> T optionalJson(String value, Class<T> type) {
        return value == null ? null : json.readValue(value, type);
    }

    private String name(JsonNode players, int id, String field) {
        for (var player : players) {
            if (player.path("participantId").intValue() == id) {
                var value = player.path(field);
                return value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
            }
        }
        return null;
    }

    private List<MatchDevelopment.TeamResult> teams(MatchRow match, List<Participant> participants) {
        return jdbc.query("select team_id, win, objectives::text from league_analysis.riot_team where match_id=? order by team_id",
                (rs, index) -> {
                    int teamId = rs.getInt("team_id");
                    var roster = participants.stream().filter(p -> p.teamId() == teamId).toList();
                    boolean complete = roster.size() == 5;
                    var objectives = new LinkedHashMap<String, Integer>();
                    var raw = json.readTree(rs.getString("objectives"));
                    if (match.mapId() == 11) {
                        for (var objective : List.of("tower", "inhibitor", "dragon", "baron", "riftHerald")) {
                            objectives.put(objective, objectiveCount(raw, objective));
                        }
                        int season = season(match.gameVersion());
                        if (season >= 14) objectives.put("horde", objectiveCount(raw, "horde"));
                        if (season == 15) objectives.put("atakhan", objectiveCount(raw, "atakhan"));
                    } else if (match.mapId() == 12 || match.mapId() == 14) {
                        objectives.put("tower", objectiveCount(raw, "tower"));
                        objectives.put("inhibitor", objectiveCount(raw, "inhibitor"));
                    }
                    return new MatchDevelopment.TeamResult(teamId, rs.getBoolean("win"),
                            complete ? roster.stream().mapToInt(Participant::kills).sum() : null,
                            complete ? roster.stream().mapToInt(Participant::deaths).sum() : null,
                            complete ? roster.stream().mapToInt(Participant::assists).sum() : null,
                            complete ? roster.stream().mapToInt(Participant::goldEarned).sum() : null,
                            objectives);
                }, match.matchId());
    }

    private int season(String version) {
        try { return Integer.parseInt(version.split("\\.")[0]); }
        catch (RuntimeException ignored) { return -1; }
    }

    private Integer objectiveCount(JsonNode raw, String key) {
        var value = raw.path(key).path("kills");
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 ? value.intValue() : null;
    }

    private List<MatchDevelopment.Event> events(String matchId, List<Participant> roster) {
        return jdbc.query("""
                select e.* from league_analysis.match_event e join league_analysis.riot_match m
                  on m.match_id=e.match_id and m.timeline_source_capture_id=e.source_capture_id
                where m.match_id=? order by e.represented_at_ms, e.frame_at_ms, e.frame_event_index
                """, (rs, index) -> {
                    var raw = json.readTree(rs.getString("event_payload"));
                    var type = rs.getString("provider_event_type");
                    Integer actor = rs.getObject("actor_participant_id", Integer.class);
                    Integer target = rs.getObject("target_participant_id", Integer.class);
                    var assisters = new ArrayList<Integer>();
                    var recordedAssisters = raw.path("assistingParticipantIds");
                    boolean assistersObserved = recordedAssisters.isArray();
                    for (var assister : recordedAssisters) {
                        if (!assister.isIntegralNumber() || !assister.canConvertToInt()
                                || assister.intValue() < 1 || assister.intValue() > 10) {
                            assistersObserved = false;
                            break;
                        }
                        assisters.add(assister.intValue());
                    }
                    if (!assistersObserved) assisters.clear();
                    var ids = new LinkedHashSet<Integer>();
                    if (actor != null && actor > 0) ids.add(actor);
                    if (target != null && target > 0) ids.add(target);
                    ids.addAll(assisters);
                    Integer item = raw.path("itemId").isIntegralNumber() ? raw.path("itemId").intValue() : null;
                    return new MatchDevelopment.Event(rs.getLong("represented_at_ms"), eventLabel(type, raw, item),
                            ids.stream().sorted().toList(), item, actor, target, assisters,
                            assistersObserved, type, rs.getLong("frame_at_ms"),
                            rs.getInt("frame_event_index"), sanitizedFields(raw),
                            rs.getObject("position_x", Integer.class), rs.getObject("position_y", Integer.class),
                            dev.leagueanalysis.analysis.match.application.EventPresentationMapper.project(type, actor, raw, roster));
                }, matchId);
    }

    private String eventLabel(String type, JsonNode raw, Integer item) {
        return switch (type) {
            case "ITEM_PURCHASED" -> "Purchased item " + item;
            case "ITEM_SOLD" -> "Sold item " + item;
            case "ITEM_DESTROYED" -> "Removed item " + item;
            case "ITEM_UNDO" -> "Undid item change";
            case "CHAMPION_KILL" -> "Champion kill";
            case "ELITE_MONSTER_KILL" -> switch (raw.path("monsterType").asText("")) {
                case "DRAGON" -> "Dragon secured";
                case "BARON_NASHOR" -> "Baron secured";
                case "RIFTHERALD" -> "Rift Herald secured";
                default -> "Epic monster secured";
            };
            case "WARD_PLACED" -> "Ward placed";
            case "WARD_KILL" -> "Ward destroyed";
            case "BUILDING_KILL" -> "Structure destroyed";
            case "TURRET_PLATE_DESTROYED" -> "Turret plate destroyed";
            case "LEVEL_UP" -> "Level gained";
            case "SKILL_LEVEL_UP" -> "Skill level gained";
            case "CHAMPION_SPECIAL_KILL" -> "Special kill marker";
            case "GAME_END" -> "Game ended";
            default -> type.replace('_', ' ');
        };
    }

    // Only recorded gameplay fields are public. Identity, credentials and unknown metadata never cross this boundary.
    private Map<String, Object> sanitizedFields(JsonNode raw) {
        var result = new LinkedHashMap<String, Object>();
        for (var key : List.of("type", "timestamp", "realTimestamp", "participantId", "creatorId", "killerId",
                "victimId", "teamId", "killerTeamId", "assistingParticipantIds", "position", "itemId", "beforeId",
                "afterId", "goldGain", "wardType", "killType", "multiKillLength", "monsterType", "monsterSubType",
                "buildingType", "towerType", "laneType", "level", "skillSlot", "levelUpType", "bounty",
                "shutdownBounty", "winningTeam", "actualStartTime", "name", "x", "y", "killStreakLength",
                "victimDamageDealt", "victimDamageReceived", "basic", "magicDamage", "physicalDamage", "trueDamage",
                "spellName", "spellSlot", "championName")) {
            var value = raw.get(key);
            if (value != null) result.put(key, sanitize(value));
        }
        return result;
    }

    private Object sanitize(JsonNode value) {
        if (value.isObject()) return sanitizedFields(value);
        if (value.isArray()) {
            var values = new ArrayList<Object>();
            for (var child : value) values.add(sanitize(child));
            return values;
        }
        if (value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isNumber()) return value.doubleValue();
        return value.asText();
    }

    private List<Integer> itemIds(String encoded) {
        var values = new ArrayList<Integer>();
        for (var node : json.readTree(encoded)) {
            values.add(node.intValue());
        }
        return List.copyOf(values);
    }

    private record MatchRow(
            String matchId,
            int queueId,
            int mapId,
            String gameMode,
            String gameVersion,
            long gameCreationMs,
            long durationMs) {}
}
