package dev.leagueanalysis;

import dev.leagueanalysis.support.PostgresTestConfiguration;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class FoundationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Value("${local.server.port}") int port;

    @Test
    void connectsToPostgresAndStartsJpa() {
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
        assertThat(entityManagerFactory.isOpen()).isTrue();
    }

    @Test
    void appliesTheInitialMigration() {
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.schemata where schema_name = 'league_analysis'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where version = '1' and success",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void doesNotReapplyAnAppliedMigration() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void servesHealthyStatusWithoutInternalDetails() throws Exception {
        var request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"status\":\"UP\"")
                    .doesNotContain("components", "details");
        }
    }
}
