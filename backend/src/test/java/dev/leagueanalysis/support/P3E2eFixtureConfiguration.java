package dev.leagueanalysis.support;

import dev.leagueanalysis.demo.DemoSeedCommand;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import java.io.IOException;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;

/** Browser evidence is available only on the test classpath with explicit e2e opt-in. */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
public class P3E2eFixtureConfiguration {
    @Bean
    @Primary
    RiotHttpTransport offlineRiotHttpTransport() {
        return (request, timeout, maxResponseBytes) -> {
            throw new IOException("Riot HTTP is disabled in the e2e profile.");
        };
    }

    @Bean
    @Primary
    dev.leagueanalysis.ingestion.riot.application.RiotGateway publicLookupFixtureGateway(
            tools.jackson.databind.ObjectMapper json, java.time.Clock clock) {
        return new PublicLookupGatewayFixture(json, clock);
    }

    @Bean
    ApplicationRunner p3E2eFixture(P3SanitizedMatchFixture fixture, DemoSeedCommand demo, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return args -> {
            fixture.replaceFixtureMatch();
            fixture.prepareBrowserEvidence();
            demo.seed();
            jdbc.execute("delete from league_analysis.leaderboard_entry");
            jdbc.execute("delete from league_analysis.leaderboard_member");
            for(int i=0;i<300;i++) {
                var id="e2e-leaderboard-"+i;
                jdbc.update("insert into league_analysis.leaderboard_entry values(false,?,'CHALLENGER',?,?,?)",id,1500-i,150+i,50+i);
                jdbc.update("insert into league_analysis.leaderboard_member(puuid,game_name,tag_line,profile_icon_id) values(?,?,?,?)",
                        id,i==299?null:"History"+(100+i),i==299?null:"NA1",i==299?null:29+i);
            }
            jdbc.update("update league_analysis.leaderboard_state set fetched_at=now(),ladder_due_at=now()+interval '15 minutes',failed=false,next_tier=0,lease_until=null,lease_id=null");
            jdbc.update("update league_analysis.leaderboard_cutoff set fetched_at=now(),failed=false,challenger_boundary=1201,challenger_promotion=1202,grandmaster_boundary=800,grandmaster_promotion=801,cycle_at=null,challenger_histogram=null,grandmaster_histogram=null");
        };
    }
}
