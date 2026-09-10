package dev.leagueanalysis.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.leagueanalysis.demo.DemoSeedCommand;
import org.junit.jupiter.api.Test;

class P3E2eFixtureConfigurationTest {
    @Test
    void explicitE2eStartupProvidesBothBrowserFixtures() throws Exception {
        var p3 = mock(P3SanitizedMatchFixture.class);
        var demo = mock(DemoSeedCommand.class);

        new P3E2eFixtureConfiguration().p3E2eFixture(p3, demo).run(null);

        verify(p3).replaceFixtureMatch();
        verify(p3).prepareBrowserEvidence();
        verify(demo).seed();
    }
}
