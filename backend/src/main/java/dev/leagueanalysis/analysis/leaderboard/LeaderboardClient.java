package dev.leagueanalysis.analysis.leaderboard;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public final class LeaderboardClient {
    static final List<String> TIERS=List.of("CHALLENGER","GRANDMASTER","MASTER");
    static final Comparator<Standing> ORDER=Comparator.comparingInt((Standing s)->TIERS.indexOf(s.tier()))
            .thenComparing(Comparator.comparingInt(Standing::lp).reversed()).thenComparing(Standing::puuid);
    private final RiotHttpTransport transport;
    private final RiotProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    public LeaderboardClient(RiotHttpTransport transport,RiotProperties properties,ObjectMapper json,Clock clock) {
        this.transport=transport;this.properties=properties;this.json=json;this.clock=clock;
    }
    public List<Standing> ladder(int tier) {
        var body=get("na1", "/lol/league/v4/"+TIERS.get(tier).toLowerCase(Locale.ROOT)+"leagues/by-queue/RANKED_SOLO_5x5");
        if(!body.isObject() || !body.path("queue").asText().equals("RANKED_SOLO_5x5")
                || !body.path("tier").asText().equals(TIERS.get(tier)) || !body.path("entries").isArray())throw invalid();
        var rows=new ArrayList<Standing>();var seen=new HashSet<String>();
        for(var row:body.path("entries")) {
            var id=text(row,"puuid",256);
            if(!seen.add(id))throw invalid();
            rows.add(new Standing(id,TIERS.get(tier),count(row,"leaguePoints"),count(row,"wins"),count(row,"losses")));
        }
        if(tier==0&&rows.size()>300||tier==1&&rows.size()>700)throw invalid();
        return rows.stream().sorted(ORDER).toList();
    }
    public Name name(String puuid) {
        var body=get("americas","/riot/account/v1/accounts/by-puuid/"+encode(puuid));
        if(!puuid.equals(text(body,"puuid",256)))throw invalid();
        var name=new Name(text(body,"gameName",64),text(body,"tagLine",16));
        if(!validName(name.gameName(),name.tagLine()))throw invalid();
        return name;
    }
    public int icon(String puuid) {
        var body=get("na1","/lol/summoner/v4/summoners/by-puuid/"+encode(puuid));
        if(!puuid.equals(text(body,"puuid",256)))throw invalid();
        return count(body,"profileIconId");
    }
    private JsonNode get(String host,String path) {
        try {
            var request=HttpRequest.newBuilder(URI.create("https://"+host+".api.riotgames.com"+path))
                    .header("X-Riot-Token",properties.apiKey()).timeout(properties.requestTimeout()).GET().build();
            var response=transport.send(request,properties.requestTimeout(),Math.min(properties.maxResponseBytes(),4*1024*1024));
            if(response.statusCode()!=200) {
                var retry=clock.instant().plusSeconds(response.statusCode()==401||response.statusCode()==403?3600:60);
                if(response.statusCode()==429) {
                    var value=response.headers().firstValue("Retry-After").orElse("60");
                    try {retry=clock.instant().plusSeconds(Math.max(1,Long.parseLong(value)));}
                    catch(RuntimeException ignored) {try {retry=ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(RuntimeException invalid){/* use default */}}
                }
                throw new Failure(retry,response.statusCode()==429||response.statusCode()==401||response.statusCode()==403);
            }
            return json.readTree(response.body());
        } catch(Failure failure){throw failure;}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw invalid();}
        catch(Exception failure){throw invalid();}
    }
    private Failure invalid(){return new Failure(clock.instant().plusSeconds(60),false);}
    private String text(JsonNode row,String field,int max) {
        var node=row.get(field);
        if(node==null||!node.isString()||node.stringValue().isBlank()||node.stringValue().length()>max
                ||node.stringValue().codePoints().anyMatch(Character::isISOControl))throw invalid();
        return node.stringValue();
    }
    private int count(JsonNode row,String field) {
        var node=row.get(field);if(node==null)return 0;
        if(!node.isIntegralNumber()||!node.canConvertToInt()||node.intValue()<0)throw invalid();
        return node.intValue();
    }
    static boolean validName(String name,String tag) {
        return name!=null&&tag!=null&&!name.isBlank()&&!tag.isBlank()&&name.length()<=64&&tag.length()<=16
                &&name.codePoints().noneMatch(c->c<32||c==127||c=='#'||c=='/'||c=='\\')
                &&tag.codePoints().noneMatch(c->c<32||c==127||c=='#'||c=='/'||c=='\\'||c=='-');
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    record Standing(String puuid,String tier,int lp,int wins,int losses) {}
    record Name(String gameName,String tagLine) {}
    static final class Failure extends RuntimeException {
        final Instant retry;final boolean global;
        Failure(Instant retry,boolean global){super("Leaderboard provider unavailable");this.retry=retry;this.global=global;}
    }
}
