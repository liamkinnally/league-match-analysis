package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import dev.leagueanalysis.ingestion.riot.application.RiotFailureCode;
import dev.leagueanalysis.ingestion.riot.application.RiotGatewayException;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiotApiClientTest {
    private static final String TEST_SECRET = "test-secret";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);

    @Test void sharedRegionalListsCanContainTransferredMatchesWithoutPermittingTheirDetailLookup() {
        var transport=new FakeTransport(ok("[\"EUW1_123\",\"TR1_456\",\"EUN1_789\"]"));
        var client=client(properties(TEST_SECRET),transport,ignored->{}).forPlatform("EUW1");
        assertThat(client.listMatchIds("invented",0,0,20,null).matchIds()).containsExactly("EUW1_123","TR1_456","EUN1_789");
        assertCode(()->client.fetchMatchDetail("TR1_456"),RiotFailureCode.INVALID_INPUT);
        assertCode(()->client.fetchMatchDetail("EUN1_789"),RiotFailureCode.INVALID_INPUT);
        assertThat(transport.requests).hasSize(1);
    }

    @Test void selectedPlatformRoutesAccountHistoryDetailAndTimelineToItsOfficialCluster() {
        for (var route : Map.of("NA1", "americas", "EUW1", "europe", "EUN1", "europe", "KR", "asia").entrySet()) {
            String id = route.getKey() + "_123";
            var transport = new FakeTransport(ok("{\"puuid\":\"invented\",\"gameName\":\"선수\",\"tagLine\":\"tag\"}"),
                    ok("[\"" + id + "\"]"), ok("{\"info\":{\"gameVersion\":\"16.18.1\"}}"), ok("{}"));
            var client = client(properties(TEST_SECRET), transport, ignored -> {}).forPlatform(route.getKey()).forPublicLookup();
            var account = client.resolveAccount(new RiotId("선수", "tag"));
            assertThat(account.source().platformRoute()).isEqualTo(route.getKey());
            assertThat(client.listMatchIds("invented", 0, 0, 20, null).matchIds()).containsExactly(id);
            client.fetchMatchDetail(id);
            client.fetchMatchTimeline(id);
            assertThat(transport.requests).allSatisfy(request ->
                    assertThat(request.uri().getHost()).isEqualTo(route.getValue() + ".api.riotgames.com"));
            assertCode(() -> client.fetchMatchDetail("ZZ1_123"), RiotFailureCode.INVALID_INPUT);
        }
        var wrongRegion = new FakeTransport(ok("[\"NA1_123\"]"));
        assertCode(() -> client(properties(TEST_SECRET), wrongRegion, ignored -> {}).forPlatform("KR")
                .listMatchIds("invented", 0, 0, 20, null), RiotFailureCode.INVALID_RESPONSE);
    }

    @Test
    void publicLookupStopsOnFirst429AndRetainsFullRetryAfterWithoutParsingErrorBody() {
        var transport = new FakeTransport(response(429, "not-json-private-body",
                Map.of("Retry-After", List.of("120"))));
        var delays = new ArrayList<Duration>();
        var client = client(properties(TEST_SECRET), transport, delays::add).forPublicLookup();
        assertThatThrownBy(() -> client.fetchMatchDetail("NA1_123"))
                .isInstanceOfSatisfying(RiotGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(RiotFailureCode.RATE_LIMITED);
                    assertThat(failure.retryNotBefore()).isEqualTo(CLOCK.instant().plusSeconds(120));
                    assertThat(failure.getMessage()).doesNotContain("private-body");
                });
        assertThat(transport.requests).hasSize(1);
        assertThat(delays).isEmpty();
    }

    @Test
    void usesFixedOfficialHostHeaderAuthenticationAndValidatedMatchPaths() {
        var transport = new FakeTransport(ok("{\"metadata\":{\"matchId\":\"NA1_123\"},\"info\":{\"gameVersion\":\"16.17.1\"}}"));
        var client = client(properties(TEST_SECRET), transport, ignored -> {});

        var result = client.fetchMatchDetail("NA1_123");

        var request = transport.requests.getFirst();
        assertThat(request.uri().getScheme()).isEqualTo("https");
        assertThat(request.uri().getHost()).isEqualTo("americas.api.riotgames.com");
        assertThat(request.uri().getPath()).isEqualTo("/lol/match/v5/matches/NA1_123");
        assertThat(request.uri().getQuery()).isNull();
        assertThat(request.headers().firstValue("X-Riot-Token")).contains(TEST_SECRET);
        assertThat(result.resourceKey()).isEqualTo("NA1_123");
        assertThat(result.providerGameVersion()).isEqualTo("16.17.1");
        assertThat(result.attempt()).isEqualTo(1);
    }

    @Test
    void percentEncodesAccountAndPuuidSegmentsAndOwnsRankedListQuery() {
        var transport = new FakeTransport(
                ok("{\"puuid\":\"invented puuid/one\",\"gameName\":\"Name With Space\",\"tagLine\":\"N/A\"}"),
                ok("[\"NA1_123\"]"),
                ok("{\"metadata\":{\"matchId\":\"NA1_123\"},\"info\":{\"frames\":[]}}"));
        var client = client(properties(TEST_SECRET), transport, ignored -> {});

        client.resolveAccount(new RiotId("Name With Space", "N/A"));
        client.listRankedMatchIds("invented puuid/one", 20);
        client.fetchMatchTimeline("NA1_123");

        assertThat(transport.requests.get(0).uri().getRawPath()).isEqualTo(
                "/riot/account/v1/accounts/by-riot-id/Name%20With%20Space/N%2FA");
        assertThat(transport.requests.get(1).uri().getRawPath()).isEqualTo(
                "/lol/match/v5/matches/by-puuid/invented%20puuid%2Fone/ids");
        assertThat(transport.requests.get(1).uri().getRawQuery())
                .isEqualTo("queue=420&type=ranked&start=0&count=20");
        assertThat(transport.requests.get(2).uri().getPath())
                .isEqualTo("/lol/match/v5/matches/NA1_123/timeline");
    }

    @Test
    void requestsOlderNormalHistoryWithFixedCutoffWithoutRankedTypeFilter() {
        var transport = new FakeTransport(ok("[\"NA1_123\"]"));
        client(properties(TEST_SECRET), transport, ignored -> {})
                .listMatchIds("invented", 400, 20, 20, 1780000000L);
        assertThat(transport.requests.getFirst().uri().getRawQuery())
                .isEqualTo("queue=400&start=20&count=20&endTime=1780000000");
        assertCode(() -> client(properties(TEST_SECRET), transport, ignored -> {})
                .listMatchIds("invented", 1700, 0, 20, null), RiotFailureCode.INVALID_INPUT);
    }

    @Test
    void allQueueLookupOmitsQueueAndKeepsRawOffsetCountAndCutoff() {
        var transport = new FakeTransport(ok("[\"NA1_123\"]"));
        client(properties(TEST_SECRET), transport, ignored -> {})
                .listMatchIds("invented", 0, 20, 20, 1780000000L);
        assertThat(transport.requests.getFirst().uri().getRawQuery())
                .isEqualTo("start=20&count=20&endTime=1780000000");
    }

    @Test
    void rejectsInvalidIdentifiersAndCountBeforeTransport() {
        var transport = new FakeTransport();
        var client = client(properties(TEST_SECRET), transport, ignored -> {});

        assertCode(() -> client.fetchMatchDetail("EUW1_123"), RiotFailureCode.INVALID_INPUT);
        assertCode(() -> client.fetchMatchTimeline("NA1_not-a-number"), RiotFailureCode.INVALID_INPUT);
        assertCode(() -> client.listRankedMatchIds(" ", 1), RiotFailureCode.INVALID_INPUT);
        assertCode(() -> client.listRankedMatchIds("invented", 0), RiotFailureCode.INVALID_INPUT);
        assertCode(() -> client.listRankedMatchIds("invented", 21), RiotFailureCode.INVALID_INPUT);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void rejectsMatchListsLargerThanTheRequestedOrAbsoluteLimit() {
        var aboveRequested = new FakeTransport(ok("[\"NA1_1\",\"NA1_2\"]"));
        var aboveAbsolute = new FakeTransport(ok(matchIdsJson(21)));

        assertCode(() -> client(properties(TEST_SECRET), aboveRequested, ignored -> {})
                .listRankedMatchIds("invented", 1), RiotFailureCode.INVALID_RESPONSE);
        assertCode(() -> client(properties(TEST_SECRET), aboveAbsolute, ignored -> {})
                .listRankedMatchIds("invented", 20), RiotFailureCode.INVALID_RESPONSE);
    }

    @Test
    void failsAtFirstCallWhenConfigurationKeyIsMissing() {
        var transport = new FakeTransport();
        var client = client(properties(""), transport, ignored -> {});

        assertCode(() -> client.fetchMatchDetail("NA1_123"), RiotFailureCode.CONFIGURATION_MISSING);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void retriesOneRateLimitAndOneRetryableServerFailure() {
        var delays = new ArrayList<Duration>();
        var rateLimited = new FakeTransport(
                response(429, "not retained", Map.of("Retry-After", List.of("2"))),
                ok("{\"metadata\":{\"matchId\":\"NA1_123\"},\"info\":{\"gameVersion\":\"16.17.1\"}}"));
        var unavailable = new FakeTransport(
                response(503, "not retained", Map.of()),
                ok("{\"metadata\":{\"matchId\":\"NA1_124\"},\"info\":{\"gameVersion\":\"16.17.1\"}}"));

        var retriedRateLimit = client(properties(TEST_SECRET), rateLimited, delays::add)
                .fetchMatchDetail("NA1_123");
        var retriedUnavailable = client(properties(TEST_SECRET), unavailable, delays::add)
                .fetchMatchDetail("NA1_124");

        assertThat(retriedRateLimit.attempt()).isEqualTo(2);
        assertThat(retriedUnavailable.attempt()).isEqualTo(2);
        assertThat(delays).containsExactly(Duration.ofSeconds(2), Duration.ofMillis(250));
    }

    @Test
    void mapsEveryNonRetryableStatusWithoutAnotherRequest() {
        var expected = Map.of(
                400, RiotFailureCode.UPSTREAM_REJECTED,
                401, RiotFailureCode.AUTHENTICATION_FAILED,
                403, RiotFailureCode.AUTHENTICATION_FAILED,
                404, RiotFailureCode.NOT_FOUND,
                418, RiotFailureCode.UPSTREAM_REJECTED);

        expected.forEach((status, code) -> {
            var transport = new FakeTransport(response(status, "private body", Map.of()));
            assertCode(() -> client(properties(TEST_SECRET), transport, ignored -> {})
                    .fetchMatchDetail("NA1_123"), code);
            assertThat(transport.requests).hasSize(1);
        });
    }

    @Test
    void mapsEveryExhaustedRetryableStatusAfterExactlyOneRetry() {
        for (var status : List.of(500, 502, 503, 504)) {
            var delays = new ArrayList<Duration>();
            var transport = new FakeTransport(
                    response(status, "first private body", Map.of()),
                    response(status, "second private body", Map.of()));

            assertCode(() -> client(properties(TEST_SECRET), transport, delays::add)
                    .fetchMatchDetail("NA1_123"), RiotFailureCode.UPSTREAM_UNAVAILABLE);
            assertThat(transport.requests).hasSize(2);
            assertThat(delays).containsExactly(Duration.ofMillis(250));
        }
    }

    @Test
    void mapsExhaustedRateLimitAndBoundsRetryAfter() {
        var boundedDelays = new ArrayList<Duration>();
        var exhausted = new FakeTransport(
                response(429, "first private body", Map.of("Retry-After", List.of("99"))),
                response(429, "second private body", Map.of()));

        assertCode(() -> client(properties(TEST_SECRET), exhausted, boundedDelays::add)
                .fetchMatchDetail("NA1_123"), RiotFailureCode.RATE_LIMITED);
        assertThat(exhausted.requests).hasSize(2);
        assertThat(boundedDelays).containsExactly(Duration.ofSeconds(5));

        for (var retryAfter : List.of(
                Map.<String, List<String>>of(),
                Map.of("Retry-After", List.of("not-a-number")),
                Map.of("Retry-After", List.of("-1")))) {
            var fallbackDelays = new ArrayList<Duration>();
            var transport = new FakeTransport(
                    response(429, "private body", retryAfter),
                    ok("{\"metadata\":{\"matchId\":\"NA1_123\"},\"info\":{\"gameVersion\":\"16.17.1\"}}"));
            client(properties(TEST_SECRET), transport, fallbackDelays::add)
                    .fetchMatchDetail("NA1_123");
            assertThat(fallbackDelays).containsExactly(Duration.ofMillis(250));
        }
    }

    @Test
    void retriesIoOnceAndMapsExhaustionToNetworkFailure() {
        var recovered = new FakeTransport(
                new IOException("private network detail"),
                ok("{\"metadata\":{\"matchId\":\"NA1_123\"},\"info\":{\"gameVersion\":\"16.17.1\"}}"));
        var exhausted = new FakeTransport(
                new IOException("first private network detail"),
                new IOException("second private network detail"));
        var recoveredDelays = new ArrayList<Duration>();
        var exhaustedDelays = new ArrayList<Duration>();

        var result = client(properties(TEST_SECRET), recovered, recoveredDelays::add)
                .fetchMatchDetail("NA1_123");
        assertThat(result.attempt()).isEqualTo(2);
        assertThat(recovered.requests).hasSize(2);
        assertThat(recoveredDelays).containsExactly(Duration.ofMillis(250));

        assertThatThrownBy(() -> client(properties(TEST_SECRET), exhausted, exhaustedDelays::add)
                        .fetchMatchDetail("NA1_123"))
                .isInstanceOfSatisfying(RiotGatewayException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(RiotFailureCode.NETWORK_FAILURE);
                    assertThat(exception.toString()).doesNotContain("private network detail", TEST_SECRET);
                });
        assertThat(exhausted.requests).hasSize(2);
        assertThat(exhaustedDelays).containsExactly(Duration.ofMillis(250));
    }

    @Test
    void hashesExactBytesAndRetainsOnlyWhitelistedResponseMetadata() throws Exception {
        var body = "{\"metadata\":{\"matchId\":\"NA1_123\"},\"info\":{\"gameVersion\":\"16.17.1\"}}\n";
        var headers = Map.of(
                "Content-Type", List.of("application/json"),
                "Date", List.of("Thu, 03 Sep 2026 16:00:00 GMT"),
                "Retry-After", List.of("2"),
                "X-App-Rate-Limit", List.of("20:1"),
                "X-App-Rate-Limit-Count", List.of("1:1"),
                "X-Method-Rate-Limit", List.of("10:1"),
                "X-Method-Rate-Limit-Count", List.of("1:1"),
                "Set-Cookie", List.of("private-cookie"),
                "Authorization", List.of("private-authorization"),
                "X-Unapproved", List.of("private-header"));
        var result = client(
                        properties(TEST_SECRET),
                        new FakeTransport(response(200, body, headers)),
                        ignored -> {})
                .fetchMatchDetail("NA1_123");

        var bytes = body.getBytes(StandardCharsets.UTF_8);
        assertThat(result.bodySizeBytes()).isEqualTo(bytes.length);
        assertThat(result.bodySha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(result.responseMetadata().size()).isEqualTo(7);
        assertThat(result.responseMetadata().has("content-type")).isTrue();
        assertThat(result.responseMetadata().has("date")).isTrue();
        assertThat(result.responseMetadata().has("retry-after")).isTrue();
        assertThat(result.responseMetadata().has("x-app-rate-limit")).isTrue();
        assertThat(result.responseMetadata().has("x-app-rate-limit-count")).isTrue();
        assertThat(result.responseMetadata().has("x-method-rate-limit")).isTrue();
        assertThat(result.responseMetadata().has("x-method-rate-limit-count")).isTrue();
        assertThat(result.responseMetadata().toString()).doesNotContain(
                "private-cookie", "private-authorization", "private-header");
    }

    @Test
    void neverRetriesAuthenticationAndSanitizesEveryExposedString() {
        var responseBody = "private upstream response body";
        var transport = new FakeTransport(response(403, responseBody, Map.of()));
        var properties = properties(TEST_SECRET);
        var client = client(properties, transport, ignored -> {});

        assertThatThrownBy(() -> client.fetchMatchDetail("NA1_123"))
                .isInstanceOfSatisfying(RiotGatewayException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(RiotFailureCode.AUTHENTICATION_FAILED);
                    assertThat(exception.getMessage()).doesNotContain(TEST_SECRET, responseBody, "https://");
                    assertThat(exception.toString()).doesNotContain(TEST_SECRET, responseBody, "https://");
                });
        assertThat(properties.toString()).doesNotContain(TEST_SECRET);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void mapsBodyLimitAndInvalidJsonWithoutLeakingTransportDetails() {
        var tooLarge = new FakeTransport(new ResponseBodyLimitException());
        var invalid = new FakeTransport(ok("upstream-private-not-json"));

        assertCode(() -> client(properties(TEST_SECRET), tooLarge, ignored -> {})
                .fetchMatchDetail("NA1_123"), RiotFailureCode.BODY_TOO_LARGE);
        assertThatThrownBy(() -> client(properties(TEST_SECRET), invalid, ignored -> {})
                .fetchMatchDetail("NA1_123"))
                .isInstanceOfSatisfying(RiotGatewayException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(RiotFailureCode.INVALID_RESPONSE);
                    assertThat(exception.toString()).doesNotContain("upstream-private-not-json", TEST_SECRET);
                });
    }

    private RiotApiClient client(RiotProperties properties, RiotHttpTransport transport, Sleeper sleeper) {
        return new RiotApiClient(properties, transport, new ObjectMapper(), CLOCK, sleeper);
    }

    private RiotProperties properties(String secret) {
        return new RiotProperties(
                secret,
                "AMERICAS",
                "NA1",
                420,
                16 * 1024 * 1024,
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5));
    }

    private void assertCode(Runnable call, RiotFailureCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(RiotGatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static RiotHttpTransport.Response ok(String body) {
        return response(200, body, Map.of("Content-Type", List.of("application/json")));
    }

    private static String matchIdsJson(int count) {
        var ids = new ArrayList<String>();
        for (var index = 1; index <= count; index++) {
            ids.add("\"NA1_" + index + "\"");
        }
        return "[" + String.join(",", ids) + "]";
    }

    private static RiotHttpTransport.Response response(
            int status,
            String body,
            Map<String, List<String>> headers) {
        return new RiotHttpTransport.Response(
                status,
                HttpHeaders.of(headers, (name, value) -> true),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class FakeTransport implements RiotHttpTransport {
        private final ArrayDeque<Object> outcomes = new ArrayDeque<>();
        private final List<HttpRequest> requests = new ArrayList<>();

        FakeTransport(Object... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public Response send(HttpRequest request, Duration timeout, int maxResponseBytes) throws IOException {
            requests.add(request);
            var outcome = outcomes.removeFirst();
            if (outcome instanceof IOException exception) {
                throw exception;
            }
            return (Response) outcome;
        }
    }
}
