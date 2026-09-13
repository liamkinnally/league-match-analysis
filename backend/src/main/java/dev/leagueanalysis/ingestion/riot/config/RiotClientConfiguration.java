package dev.leagueanalysis.ingestion.riot.config;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.BudgetedRiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.JdkRiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotApiClient;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.application.RiotGateway;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RiotProperties.class)
public class RiotClientConfiguration {
    @Bean
    MatchV5Decoder matchV5Decoder() {
        return new MatchV5Decoder();
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    RiotHttpTransport riotHttpTransport(RiotProperties properties, Clock clock) {
        return new BudgetedRiotHttpTransport(new JdkRiotHttpTransport(properties.connectTimeout()), clock);
    }

    @Bean
    RiotGateway riotGateway(
            RiotProperties properties,
            RiotHttpTransport transport,
            ObjectMapper json,
            Clock clock) {
        return new RiotApiClient(properties, transport, json, clock);
    }
}
