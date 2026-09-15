package dev.leagueanalysis.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.leagueanalysis.demo.DemoSeedCommand;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.application.RiotGateway;
import dev.leagueanalysis.ingestion.riot.config.RiotClientConfiguration;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class P3E2eFixtureConfigurationTest {
    private final ApplicationContextRunner e2eContext = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=e2e", "RIOT_API_KEY=inherited-dummy-key",
                    "RIOT_PUBLIC_LOOKUP_ENABLED=false")
            .withUserConfiguration(RiotClientConfiguration.class, P3E2eFixtureConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(P3SanitizedMatchFixture.class, () -> mock(P3SanitizedMatchFixture.class))
            .withBean(DemoSeedCommand.class, () -> mock(DemoSeedCommand.class));

    @Test
    void explicitE2eStartupProvidesBothBrowserFixtures() throws Exception {
        var p3 = mock(P3SanitizedMatchFixture.class);
        var demo = mock(DemoSeedCommand.class);

        new P3E2eFixtureConfiguration().p3E2eFixture(p3, demo).run(null);

        verify(p3).replaceFixtureMatch();
        verify(p3).prepareBrowserEvidence();
        verify(demo).seed();
    }

    @Test
    void e2eProfileDiscardsInheritedCredentialsWhileKeepingFixtureLookupEnabled() {
        e2eContext.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(RiotProperties.class);
            assertThat(properties.apiKey()).isEmpty();
            assertThat(context.getEnvironment().getProperty("league-analysis.riot.public-lookup-enabled", Boolean.class))
                    .isTrue();
            assertThat(context.getBean(RiotGateway.class)).isInstanceOf(PublicLookupGatewayFixture.class);
        });
    }

    @Test
    void e2eTransportBlocksHttpEvenWhenTheBoundKeyIsExplicitlyOverridden() {
        e2eContext.withPropertyValues("league-analysis.riot.api-key=explicit-test-key").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RiotProperties.class).apiKey()).isEqualTo("explicit-test-key");
            var transport = context.getBean(RiotHttpTransport.class);
            assertThat(transport).isNotSameAs(context.getBean("riotHttpTransport"));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/offline-transport-check"))
                    .GET().build();
            assertThatThrownBy(() -> transport.send(request, Duration.ofMillis(10), 1024))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Riot HTTP is disabled in the e2e profile.");
        });
    }
}
