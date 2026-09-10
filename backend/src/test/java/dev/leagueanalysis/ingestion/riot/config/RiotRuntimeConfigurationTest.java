package dev.leagueanalysis.ingestion.riot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;

class RiotRuntimeConfigurationTest {
    @Test
    void mapsTheRuntimeRiotKeyWithoutTheLocalProfile() throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("runtime", Map.of("RIOT_API_KEY", "runtime-secret")));
        environment.getPropertySources().addLast(
                new ResourcePropertySource("application", "classpath:application.properties"));

        var properties = Binder.get(environment)
                .bind("league-analysis.riot", Bindable.of(RiotProperties.class))
                .orElseThrow(() -> new AssertionError("Riot runtime properties were not bound"));

        assertThat(properties.apiKey()).isEqualTo("runtime-secret");
    }
}
