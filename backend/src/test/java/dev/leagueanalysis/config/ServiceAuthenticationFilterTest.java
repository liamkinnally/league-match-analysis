package dev.leagueanalysis.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceAuthenticationFilterTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void requiredAuthenticationRejectsMissingAndWeakConfiguration() {
        assertThatThrownBy(() -> new ServiceAuthenticationFilter(true, ""))
                .hasMessageContaining("BACKEND_SERVICE_TOKEN");
        assertThatThrownBy(() -> new ServiceAuthenticationFilter(true, "too-short"))
                .hasMessageContaining("BACKEND_SERVICE_TOKEN");
        assertThatThrownBy(() -> new ServiceAuthenticationFilter(false, "0123456789abcdef0123456789abcde\n"))
                .hasMessageContaining("BACKEND_SERVICE_TOKEN");
    }

    @Test
    void unauthenticatedLocalConfigurationPassesRequests() throws Exception {
        var chain = invoke(new ServiceAuthenticationFilter(false, ""), "GET", "/api/v1/demo", null);
        assertThat(chain.chain().getRequest()).isNotNull();
        assertThat(chain.response().getStatus()).isEqualTo(200);
    }

    @Test
    void missingOrWrongCredentialsAreRejectedBeforeEveryBackendRouteFamily() throws Exception {
        var filter = new ServiceAuthenticationFilter(true, TOKEN);
        for (String path : new String[]{"/api/v1/demo", "/api/matches/example/analysis", "/api/local/riot/ingestions"}) {
            var missing = invoke(filter, "GET", path, null);
            assertUnauthorized(missing);
            var wrong = invoke(filter, "POST", path, "Bearer 0123456789abcdef0123456789abcdeg");
            assertUnauthorized(wrong);
        }
    }

    @Test
    void validBearerCredentialPassesThroughExactlyOnce() throws Exception {
        var result = invoke(new ServiceAuthenticationFilter(true, TOKEN), "POST", "/api/v1/player-matches", "Bearer " + TOKEN);
        assertThat(result.chain().getRequest()).isNotNull();
        assertThat(result.response().getContentAsString()).isEmpty();
    }

    @Test
    void onlyStatusGetAndHeadHealthRequestsBypassAuthentication() throws Exception {
        var filter = new ServiceAuthenticationFilter(true, TOKEN);
        assertThat(invoke(filter, "GET", "/actuator/health", null).chain().getRequest()).isNotNull();
        assertThat(invoke(filter, "HEAD", "/actuator/health", null).chain().getRequest()).isNotNull();
        for (var request : new String[][]{{"POST", "/actuator/health"}, {"GET", "/actuator/health/"},
                {"GET", "/actuator/health/readiness"}, {"GET", "//actuator/health"}, {"GET", "/actuator%2Fhealth"}}) {
            assertUnauthorized(invoke(filter, request[0], request[1], null));
        }
    }

    @Test
    void applicationContextRegistersTheFilter() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServiceAuthenticationFilter.class)
                .withPropertyValues("league-analysis.service-auth.required=true",
                        "league-analysis.service-auth.token=" + TOKEN)
                .run(context -> assertThat(context).hasSingleBean(ServiceAuthenticationFilter.class));
        new ApplicationContextRunner()
                .withUserConfiguration(ServiceAuthenticationFilter.class)
                .withPropertyValues("league-analysis.service-auth.required=true")
                .run(context -> assertThat(context).hasFailed());
    }

    private static Result invoke(ServiceAuthenticationFilter filter, String method, String path, String authorization)
            throws IOException, ServletException {
        var request = new MockHttpServletRequest(method, path);
        if (authorization != null) request.addHeader("Authorization", authorization);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain);
    }

    private static void assertUnauthorized(Result result) throws Exception {
        assertThat(result.chain().getRequest()).isNull();
        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.response().getContentType()).startsWith("application/json");
        assertThat(result.response().getContentAsString()).isEqualTo("{\"error\":\"UNAUTHORIZED\"}");
    }

    private record Result(MockHttpServletResponse response, MockFilterChain chain) {}
}
