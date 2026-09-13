package dev.leagueanalysis.support;

import dev.leagueanalysis.ingestion.riot.application.*;
import dev.leagueanalysis.ingestion.riot.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Invented provider responses, available only on the test classpath. */
public class PublicLookupGatewayFixture implements RiotGateway {
    public static final String MATCH_ID = "NA1_7000000002";
    public static final String PUUID = "lookup-invented-participant-6";
    public final List<String> calls = new ArrayList<>();
    public RiotFailureCode accountFailure;
    public RiotFailureCode timelineFailure;
    public boolean empty;
    public int totalMatches = 1;
    public List<Integer> rawQueues = List.of();
    public boolean sparseUnsupportedDetail;
    public int aramMapId = 12;
    public Integer mapOverride;
    public RiotFailureCode detailFailure;
    public final List<String> pages = new ArrayList<>();
    private final Map<String, Integer> queues = new HashMap<>();
    private final Map<String, String> historySeeds = new HashMap<>();
    private final Map<String, String> participantPrefixes = new HashMap<>();
    private String currentMatchId = MATCH_ID;
    private final ObjectMapper json;
    private final Clock clock;
    public PublicLookupGatewayFixture(ObjectMapper json, Clock clock) { this.json = json; this.clock = clock; }

    public RiotAccountLookup resolveAccount(RiotId id) {
        calls.add("account");
        if (id.gameName().matches("History[0-9]{1,13}")) {
            String seed = id.gameName().substring(7);
            String puuid = "history-" + seed + "-participant-6";
            historySeeds.put(puuid, seed);
            return new RiotAccountLookup(new RiotAccount(puuid, id.gameName(), id.tagLine()),
                    document(SourceKind.ACCOUNT, "invented", "{\"puuid\":\"" + puuid + "\"}"));
        }
        currentMatchId = id.gameName().matches("Lookup[0-9]{1,16}")
                ? "NA1_" + id.gameName().substring(6) : MATCH_ID;
        if (id.gameName().equalsIgnoreCase("Unavailable") || accountFailure != null)
            throw failure(accountFailure == null ? RiotFailureCode.AUTHENTICATION_FAILED : accountFailure);
        return new RiotAccountLookup(new RiotAccount(PUUID, id.gameName(), id.tagLine()),
                document(SourceKind.ACCOUNT, "invented", "{\"puuid\":\"" + PUUID + "\"}"));
    }
    public RiotMatchList listRankedMatchIds(String puuid, int count) {
        calls.add("list");
        return new RiotMatchList(empty ? List.of() : List.of(currentMatchId),
                document(SourceKind.MATCH_LIST, PUUID, empty ? "[]" : "[\"" + currentMatchId + "\"]"));
    }
    public RiotMatchList listMatchIds(String puuid, int queueId, int start, int count, Long endTime) {
        pages.add(queueId + ":" + start + ":" + count + ":" + endTime);
        if (historySeeds.containsKey(puuid) || !rawQueues.isEmpty()) {
            calls.add("list");
            String seed = historySeeds.get(puuid);
            List<Integer> actualQueues = rawQueues.isEmpty()
                    ? java.util.stream.IntStream.range(0, 43 * 8).mapToObj(i -> new int[] {420, 440, 400, 480, 450, 1700, 1700, 1700}[i % 8]).toList()
                    : rawQueues;
            var eligible = java.util.stream.IntStream.range(0, actualQueues.size())
                    .filter(i -> queueId == 0 || actualQueues.get(i) == queueId).boxed().toList();
            var ids = new ArrayList<String>();
            for (int offset = start; !empty && offset < Math.min(eligible.size(), start + count); offset++) {
                int i = eligible.get(offset);
                int actualQueue = actualQueues.get(i);
                String id = "NA1_" + (seed == null ? "7200000" : seed) + actualQueue + String.format(Locale.ROOT, "%02d", seed != null && rawQueues.isEmpty() && actualQueue != 1700 ? i / 8 : i);
                ids.add(id);
                queues.put(id, actualQueue);
                if (seed != null) participantPrefixes.put(id, "history-" + seed + "-participant-");
            }
            return new RiotMatchList(ids, document(SourceKind.MATCH_LIST, puuid, json.writeValueAsString(ids)));
        }
        if (totalMatches == 1) {
            var result = listRankedMatchIds(puuid, count);
            result.matchIds().forEach(id -> queues.put(id, queueId == 0 ? 420 : queueId));
            return result;
        }
        calls.add("list");
        var ids = new ArrayList<String>();
        for (int i = start; !empty && i < Math.min(totalMatches, start + count); i++) ids.add("NA1_" + (7100000000L + i));
        ids.forEach(id -> queues.put(id, queueId == 0 ? 420 : queueId));
        return new RiotMatchList(ids, document(SourceKind.MATCH_LIST, PUUID, json.writeValueAsString(ids)));
    }
    public ProviderDocument fetchMatchDetail(String matchId) {
        calls.add("detail");
        if (detailFailure != null) throw failure(detailFailure);
        return fixture(SourceKind.MATCH_DETAIL, "match.json", matchId);
    }
    public ProviderDocument fetchMatchTimeline(String matchId) {
        calls.add("timeline");
        if (timelineFailure != null) throw failure(timelineFailure);
        return fixture(SourceKind.MATCH_TIMELINE, "timeline.json", matchId);
    }
    private RiotGatewayException failure(RiotFailureCode code) {
        return new RiotGatewayException(code, "private fixture exception body", clock.instant().plusSeconds(120));
    }
    private ProviderDocument fixture(SourceKind kind, String file, String matchId) {
        try (var input = getClass().getResourceAsStream("/demo/" + file)) {
            String body = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)
                    .replace("NA1_7000000001", matchId).replace("7000000001", matchId.substring(4))
                    .replace("demo-match-v1-participant-", participantPrefixes.getOrDefault(matchId, "lookup-invented-participant-"));
            var payload = json.readTree(body);
            var info = (tools.jackson.databind.node.ObjectNode) payload.get("info");
            int queue = queues.getOrDefault(matchId, 420);
            if (kind == SourceKind.MATCH_DETAIL) {
                info.put("queueId", queue);
                info.put("mapId", mapOverride == null ? queue == 450 ? aramMapId : 11 : mapOverride);
                if (queue == 450) {
                    info.put("gameMode", "ARAM");
                    for (var player : info.get("participants")) {
                        var participant = (tools.jackson.databind.node.ObjectNode) player;
                        participant.put("teamPosition", "").put("individualPosition", "").put("lane", "").put("role", "");
                        participant.put("neutralMinionsKilled", 0).put("visionScore", 0).put("item6", 0);
                        participant.put("summoner1Id", 4).put("summoner2Id", 32);
                    }
                    for (var team : info.get("teams")) {
                        var objectives = (tools.jackson.databind.node.ObjectNode) team.get("objectives");
                        for (String key : List.of("dragon", "baron", "riftHerald", "horde", "atakhan")) objectives.remove(key);
                        ((tools.jackson.databind.node.ObjectNode) objectives.get("tower")).put("kills", team.get("win").booleanValue() ? 4 : 1);
                        objectives.set("inhibitor", json.createObjectNode().put("kills", team.get("win").booleanValue() ? 1 : 0));
                    }
                }
                if (sparseUnsupportedDetail && !RiotIngestionCommand.SUPPORTED_QUEUES.contains(queue)) {
                    info.remove("participants");
                    info.remove("teams");
                }
            } else if (queue == 450) {
                for (var frame : info.get("frames")) {
                    for (var player : frame.get("participantFrames")) {
                        var participant = (tools.jackson.databind.node.ObjectNode) player;
                        participant.remove("position");
                        participant.put("jungleMinionsKilled", 0);
                    }
                    var retained = json.createArrayNode();
                    for (var event : frame.get("events")) {
                        if (List.of("ELITE_MONSTER_KILL", "WARD_PLACED", "WARD_KILL", "TURRET_PLATE_DESTROYED")
                                .contains(event.get("type").stringValue())) continue;
                        var recorded = (tools.jackson.databind.node.ObjectNode) event;
                        recorded.remove("position");
                        recorded.remove("laneType");
                        retained.add(recorded);
                    }
                    ((tools.jackson.databind.node.ObjectNode) frame).set("events", retained);
                }
            }
            body = payload.toString();
            return document(kind, matchId, body);
        } catch (Exception e) { throw new IllegalStateException("Fixture unavailable", e); }
    }
    private ProviderDocument document(SourceKind kind, String key, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            return new ProviderDocument(kind, key, clock.instant(), 200, "AMERICAS", "NA1", "16.17.1",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length,
                    json.readTree(body), json.createObjectNode(), "match-v5-v1", 1);
        } catch (Exception e) { throw new IllegalStateException("Fixture unavailable", e); }
    }
}
