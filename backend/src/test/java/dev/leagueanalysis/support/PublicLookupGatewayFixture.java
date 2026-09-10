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
    private String currentMatchId = MATCH_ID;
    private final ObjectMapper json;
    private final Clock clock;
    public PublicLookupGatewayFixture(ObjectMapper json, Clock clock) { this.json = json; this.clock = clock; }

    public RiotAccountLookup resolveAccount(RiotId id) {
        calls.add("account");
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
    public ProviderDocument fetchMatchDetail(String matchId) {
        calls.add("detail");
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
                    .replace("demo-match-v1-participant-", "lookup-invented-participant-");
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
