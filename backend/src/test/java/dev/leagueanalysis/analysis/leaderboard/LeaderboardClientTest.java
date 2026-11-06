package dev.leagueanalysis.analysis.leaderboard;
import static org.assertj.core.api.Assertions.*;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
class LeaderboardClientTest {
    static final Instant NOW=Instant.parse("2026-11-06T12:00:00Z");
    static RiotProperties properties(){return new RiotProperties("test-only","AMERICAS","NA1",420,16777216,Duration.ofSeconds(3),Duration.ofSeconds(10),Duration.ofSeconds(5));}
    LeaderboardClient client(String body,int status,Map<String,List<String>> headers){return new LeaderboardClient((r,t,m)->new RiotHttpTransport.Response(status,HttpHeaders.of(headers,(a,b)->true),body.getBytes(StandardCharsets.UTF_8)),properties(),new ObjectMapper(),Clock.fixed(NOW,ZoneOffset.UTC));}
    @Test void validatesAndOrdersLadderWithoutRequiringOmittedZeroFields(){
        var rows=client("""
            {"queue":"RANKED_SOLO_5x5","tier":"CHALLENGER","entries":[
            {"puuid":"b","leaguePoints":50,"wins":10},{"puuid":"z","leaguePoints":70},
            {"puuid":"a","leaguePoints":50,"losses":2}]}
            """,200,Map.of()).ladder(0);
        assertThat(rows).extracting(LeaderboardClient.Standing::puuid).containsExactly("z","a","b");
        assertThat(rows.getFirst().wins()).isZero();assertThat(rows.getFirst().losses()).isZero();
        for(var entries:List.of("[{\"puuid\":\"a\",\"wins\":-1}]","[{\"puuid\":\"a\",\"wins\":1.5}]","[{\"puuid\":\"a\"},{\"puuid\":\"a\"}]")) {
            assertThatThrownBy(()->client("{\"queue\":\"RANKED_SOLO_5x5\",\"tier\":\"CHALLENGER\",\"entries\":"+entries+"}",200,Map.of()).ladder(0)).isInstanceOf(LeaderboardClient.Failure.class);
        }
    }
    @Test void rejectsOversizedApexMembershipRatherThanPublishingContradictoryCounts() {
        var entries=java.util.stream.IntStream.range(0,301).mapToObj(i->"{\"puuid\":\"player-"+i+"\"}").collect(java.util.stream.Collectors.joining(","));
        assertThatThrownBy(()->client("{\"queue\":\"RANKED_SOLO_5x5\",\"tier\":\"CHALLENGER\",\"entries\":["+entries+"]}",200,Map.of()).ladder(0)).isInstanceOf(LeaderboardClient.Failure.class);
    }
    @Test void memberIdentityMustMatchAnd429CarriesProviderCooldown(){
        assertThatThrownBy(()->client("{\"puuid\":\"other\",\"profileIconId\":12}",200,Map.of()).icon("requested")).isInstanceOf(LeaderboardClient.Failure.class);
        var failure=catchThrowableOfType(LeaderboardClient.Failure.class,()->client("secret",429,Map.of("Retry-After",List.of("120"))).ladder(0));
        assertThat(failure.retry).isEqualTo(NOW.plusSeconds(120));assertThat(failure.global).isTrue();assertThat(failure.getMessage()).doesNotContain("secret");
    }
}
