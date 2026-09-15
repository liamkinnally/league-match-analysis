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
    ApplicationRunner p3E2eFixture(P3SanitizedMatchFixture fixture, DemoSeedCommand demo) {
        return args -> {
            fixture.replaceFixtureMatch();
            fixture.prepareBrowserEvidence();
            demo.seed();
        };
    }
}
