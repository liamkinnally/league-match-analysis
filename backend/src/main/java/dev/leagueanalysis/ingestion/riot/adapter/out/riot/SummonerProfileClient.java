package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import dev.leagueanalysis.ingestion.riot.application.*;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class SummonerProfileClient {
    private final RiotProperties properties;
    private final RiotHttpTransport transport;
    private final ObjectMapper json;
    private final Clock clock;
    public SummonerProfileClient(RiotProperties properties,RiotHttpTransport transport,ObjectMapper json,Clock clock) {
        this.properties=properties;this.transport=transport;this.json=json;this.clock=clock;
    }
    public record Profile(int iconId,Long level,Instant revisionAt) {}
    public Profile fetch(String platform,String puuid) {
        if(properties.apiKey().isBlank())throw new RiotGatewayException(RiotFailureCode.CONFIGURATION_MISSING,"Profile credentials unavailable");
        if(!dev.leagueanalysis.ingestion.riot.domain.RiotPlatform.supported(platform))throw new IllegalArgumentException("UNSUPPORTED_PLATFORM");
        try {
            var request=HttpRequest.newBuilder(URI.create("https://"+platform.toLowerCase(Locale.ROOT)+".api.riotgames.com/lol/summoner/v4/summoners/by-puuid/"+URLEncoder.encode(puuid,StandardCharsets.UTF_8)))
                .header("X-Riot-Token",properties.apiKey()).timeout(properties.requestTimeout()).GET().build();
            var response=transport.send(request,properties.requestTimeout(),Math.min(properties.maxResponseBytes(),65536));
            if(response.statusCode()==429)throw new RiotGatewayException(RiotFailureCode.RATE_LIMITED,"Profile cooling down",retry(response));
            if(response.statusCode()==404)throw new RiotGatewayException(RiotFailureCode.NOT_FOUND,"Account not found on selected platform");
            if(response.statusCode()!=200)throw new RiotGatewayException(RiotFailureCode.UPSTREAM_UNAVAILABLE,"Profile unavailable");
            var body=json.readTree(response.body());var icon=body.path("profileIconId");
            if(!body.isObject()||!icon.isIntegralNumber()||!icon.canConvertToInt()||icon.intValue()<0)throw new IllegalArgumentException();
            Long level=count(body,"summonerLevel"),revision=count(body,"revisionDate");
            return new Profile(icon.intValue(),level,revision==null?null:Instant.ofEpochMilli(revision));
        } catch(RiotGatewayException failure){throw failure;}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new RiotGatewayException(RiotFailureCode.UPSTREAM_UNAVAILABLE,"Profile unavailable");}
        catch(Exception failure){throw new RiotGatewayException(RiotFailureCode.INVALID_RESPONSE,"Profile response unavailable");}
    }
    private Long count(tools.jackson.databind.JsonNode body,String key) {
        var node=body.get(key);if(node==null||node.isNull())return null;
        if(!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<0)throw new IllegalArgumentException();return node.longValue();
    }
    private Instant retry(RiotHttpTransport.Response response) {
        var raw=response.headers().firstValue("Retry-After").orElse("30");
        try{return clock.instant().plusSeconds(Math.max(1,Long.parseLong(raw)));}
        catch(RuntimeException failure){try{return ZonedDateTime.parse(raw,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(RuntimeException invalid){return clock.instant().plusSeconds(30);}}
    }
}
