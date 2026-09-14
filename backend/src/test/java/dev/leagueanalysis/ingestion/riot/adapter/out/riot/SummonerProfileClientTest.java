package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import static org.assertj.core.api.Assertions.*;
import dev.leagueanalysis.ingestion.riot.application.*;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SummonerProfileClientTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"),ZoneOffset.UTC);
    private SummonerProfileClient client(int status,String body,Map<String,List<String>> headers) {
        var properties=new RiotProperties("invented-key","AMERICAS","NA1",420,10000,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1));
        return new SummonerProfileClient(properties,(request,timeout,max)->{
            assertThat(request.uri().getPath()).isEqualTo("/lol/summoner/v4/summoners/by-puuid/invented");
            return new RiotHttpTransport.Response(status,HttpHeaders.of(headers,(a,b)->true),body.getBytes(StandardCharsets.UTF_8));
        },new ObjectMapper(),clock);
    }
    @Test void extractsOnlySupportedSummonerFieldsWithoutObsoleteIdentityDependency() {
        var result=client(200,"{\"profileIconId\":29,\"summonerLevel\":180,\"revisionDate\":1789000000000,\"private\":\"do-not-expose\"}",Map.of()).fetch("NA1","invented");
        assertThat(result.iconId()).isEqualTo(29);assertThat(result.level()).isEqualTo(180);assertThat(result.revisionAt()).isEqualTo(Instant.ofEpochMilli(1789000000000L));
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("do-not-expose");
        assertThat(client(200,"{\"profileIconId\":0}",Map.of()).fetch("NA1","invented").level()).isNull();
    }
    @Test void missingOrMalformedValuesNeverBecomeRealZeroAndCooldownKeepsRetryTime() {
        for(String body:List.of("{}","{\"profileIconId\":-1}","{\"profileIconId\":2,\"summonerLevel\":-1}"))
            assertThatThrownBy(()->client(200,body,Map.of()).fetch("NA1","invented")).isInstanceOfSatisfying(RiotGatewayException.class,e->assertThat(e.code()).isEqualTo(RiotFailureCode.INVALID_RESPONSE));
        assertThatThrownBy(()->client(429,"invented-key",Map.of("Retry-After",List.of("120"))).fetch("NA1","invented"))
            .isInstanceOfSatisfying(RiotGatewayException.class,e->{assertThat(e.retryNotBefore()).isEqualTo(clock.instant().plusSeconds(120));assertThat(e.toString()).doesNotContain("invented-key");});
    }
}
