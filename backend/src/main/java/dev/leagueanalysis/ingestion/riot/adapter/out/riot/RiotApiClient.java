package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import dev.leagueanalysis.ingestion.riot.application.RiotAccountLookup;
import dev.leagueanalysis.ingestion.riot.application.RiotFailureCode;
import dev.leagueanalysis.ingestion.riot.application.RiotGateway;
import dev.leagueanalysis.ingestion.riot.application.RiotGatewayException;
import dev.leagueanalysis.ingestion.riot.application.RiotMatchList;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotAccount;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class RiotApiClient implements RiotGateway {
    private dev.leagueanalysis.ingestion.riot.domain.RiotPlatform platform = dev.leagueanalysis.ingestion.riot.domain.RiotPlatform.NA1;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(500, 502, 503, 504);
    private static final Set<String> RETAINED_HEADERS = Set.of(
            "content-type",
            "date",
            "retry-after",
            "x-app-rate-limit",
            "x-app-rate-limit-count",
            "x-method-rate-limit",
            "x-method-rate-limit-count");
    private static final Duration RETRY_DELAY = Duration.ofMillis(250);
    private static final String PARSER_VERSION = "riot-api-v1";

    private final RiotProperties properties;
    private final RiotHttpTransport transport;
    private final ObjectMapper json;
    private final Clock clock;
    private final Sleeper sleeper;
    private boolean stopOnRateLimit;

    public RiotApiClient(
            RiotProperties properties,
            RiotHttpTransport transport,
            ObjectMapper json,
            Clock clock) {
        this(properties, transport, json, clock, duration -> Thread.sleep(duration));
    }

    RiotApiClient(
            RiotProperties properties,
            RiotHttpTransport transport,
            ObjectMapper json,
            Clock clock,
            Sleeper sleeper) {
        this.properties = properties;
        this.transport = transport;
        this.json = json;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public RiotGateway forPlatform(String platform) {
        var client = new RiotApiClient(properties, transport, json, clock, sleeper);
        client.platform = dev.leagueanalysis.ingestion.riot.domain.RiotPlatform.parse(platform);
        client.stopOnRateLimit = stopOnRateLimit;
        return client;
    }

    @Override
    public RiotGateway forPublicLookup() {
        var client = new RiotApiClient(properties, transport, json, clock, sleeper);
        client.stopOnRateLimit = true;
        client.platform = platform;
        return client;
    }

    @Override
    public RiotAccountLookup resolveAccount(RiotId riotId) {
        if (riotId == null) {
            throw failure(RiotFailureCode.INVALID_INPUT, "Riot ID is invalid");
        }
        var path = "/riot/account/v1/accounts/by-riot-id/"
                + encodeSegment(riotId.gameName()) + "/" + encodeSegment(riotId.tagLine());
        var source = get(SourceKind.ACCOUNT, riotId.gameName() + "#" + riotId.tagLine(), path, null);
        var payload = source.payload();
        var account = new RiotAccount(
                requiredText(payload, "puuid"),
                requiredText(payload, "gameName"),
                requiredText(payload, "tagLine"));
        return new RiotAccountLookup(account, source);
    }

    @Override
    public RiotMatchList listRankedMatchIds(String puuid, int count) {
        return list(puuid, properties.queueId(), 0, count, null, true);
    }

    @Override
    public RiotMatchList listMatchIds(String puuid, int queueId, int start, int count, Long endTime) {
        return list(puuid, queueId, start, count, endTime, false);
    }

    private RiotMatchList list(String puuid, int queueId, int start, int count, Long endTime, boolean rankedType) {
        if (puuid == null || puuid.isBlank() || count < 1 || count > 20 || start < 0
                || (endTime != null && endTime < 0)
                || (queueId != 0 && !dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand.SUPPORTED_QUEUES.contains(queueId))) {
            throw failure(RiotFailureCode.INVALID_INPUT, "Match-list input is invalid");
        }
        var path = "/lol/match/v5/matches/by-puuid/" + encodeSegment(puuid) + "/ids";
        var query = (queueId == 0 ? "" : "queue=" + queueId + "&") + (rankedType ? "type=ranked&" : "") + "start=" + start + "&count=" + count
                + (endTime == null ? "" : "&endTime=" + endTime);
        var source = get(SourceKind.MATCH_LIST, puuid, path, query);
        var payload = source.payload();
        if (!payload.isArray()) {
            throw failure(RiotFailureCode.INVALID_RESPONSE, "Riot response is invalid");
        }
        if (payload.size() > count || payload.size() > 20) {
            throw failure(RiotFailureCode.INVALID_RESPONSE, "Riot response is invalid");
        }
        var ids = new ArrayList<String>();
        for (var node : payload) {
            if (!node.isString() || !validHistoryId(node.stringValue())) {
                throw failure(RiotFailureCode.INVALID_RESPONSE, "Riot response is invalid");
            }
            ids.add(node.stringValue());
        }
        return new RiotMatchList(ids, source);
    }

    @Override
    public ProviderDocument fetchMatchDetail(String matchId) {
        validateMatchId(matchId);
        return get(SourceKind.MATCH_DETAIL, matchId, "/lol/match/v5/matches/" + matchId, null);
    }

    @Override
    public ProviderDocument fetchMatchTimeline(String matchId) {
        validateMatchId(matchId);
        return get(
                SourceKind.MATCH_TIMELINE,
                matchId,
                "/lol/match/v5/matches/" + matchId + "/timeline",
                null);
    }

    private ProviderDocument get(SourceKind kind, String resourceKey, String path, String query) {
        if (properties.apiKey().isBlank()) {
            throw failure(RiotFailureCode.CONFIGURATION_MISSING, "Riot API key is not configured");
        }
        var uri = URI.create("https://" + platform.regionalRoute().toLowerCase(Locale.ROOT) + ".api.riotgames.com" + path + (query == null ? "" : "?" + query));
        var request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .header("X-Riot-Token", properties.apiKey())
                .GET()
                .build();

        for (var attempt = 1; attempt <= 2; attempt++) {
            final RiotHttpTransport.Response response;
            try {
                response = transport.send(request, properties.requestTimeout(), properties.maxResponseBytes());
            } catch (ResponseBodyLimitException exception) {
                throw failure(RiotFailureCode.BODY_TOO_LARGE, "Riot response exceeded the configured limit");
            } catch (IOException exception) {
                if (attempt == 1) {
                    pause(RETRY_DELAY);
                    continue;
                }
                throw failure(RiotFailureCode.NETWORK_FAILURE, "Riot request failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw failure(RiotFailureCode.NETWORK_FAILURE, "Riot request was interrupted", exception);
            }

            if (response.statusCode() == 200) {
                return document(kind, resourceKey, response, attempt);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw failure(RiotFailureCode.AUTHENTICATION_FAILED, "Riot authentication failed");
            }
            if (response.statusCode() == 404) {
                throw failure(RiotFailureCode.NOT_FOUND, "Riot resource was not found");
            }
            if (response.statusCode() == 429) {
                if (stopOnRateLimit) {
                    throw new RiotGatewayException(RiotFailureCode.RATE_LIMITED,
                            "Riot rate limit was exceeded", clock.instant().plus(fullRetryAfter(response)));
                }
                if (attempt == 1) {
                    pause(retryAfter(response));
                    continue;
                }
                throw failure(RiotFailureCode.RATE_LIMITED, "Riot rate limit was exceeded");
            }
            if (RETRYABLE_STATUS.contains(response.statusCode())) {
                if (attempt == 1) {
                    pause(RETRY_DELAY);
                    continue;
                }
                throw failure(RiotFailureCode.UPSTREAM_UNAVAILABLE, "Riot service is unavailable");
            }
            throw failure(RiotFailureCode.UPSTREAM_REJECTED, "Riot rejected the request");
        }
        throw failure(RiotFailureCode.NETWORK_FAILURE, "Riot request failed");
    }

    private ProviderDocument document(
            SourceKind kind,
            String resourceKey,
            RiotHttpTransport.Response response,
            int attempt) {
        var body = response.body();
        final JsonNode payload;
        try {
            payload = json.readTree(body);
        } catch (JacksonException exception) {
            throw failure(RiotFailureCode.INVALID_RESPONSE, "Riot response is invalid", exception);
        }
        if (payload == null) {
            throw failure(RiotFailureCode.INVALID_RESPONSE, "Riot response is invalid");
        }
        var metadata = json.createObjectNode();
        response.headers().map().forEach((name, values) -> {
            var normalized = name.toLowerCase(Locale.ROOT);
            if (RETAINED_HEADERS.contains(normalized)) {
                metadata.put(normalized, String.join(",", values));
            }
        });
        var providerGameVersion = kind == SourceKind.MATCH_DETAIL
                ? optionalText(payload.path("info"), "gameVersion")
                : null;
        return new ProviderDocument(
                kind,
                resourceKey,
                clock.instant(),
                response.statusCode(),
                platform.regionalRoute(),
                platform.name(),
                providerGameVersion,
                sha256(body),
                body.length,
                payload,
                metadata,
                PARSER_VERSION,
                attempt);
    }

    private Duration fullRetryAfter(RiotHttpTransport.Response response) {
        try {
            long seconds = Long.parseLong(response.headers().firstValue("Retry-After").orElse("60"));
            // Missing or malformed provider guidance uses a conservative one-minute cooldown.
            if (seconds <= 0) return Duration.ofSeconds(60);
            clock.instant().plusSeconds(seconds);
            return Duration.ofSeconds(seconds);
        } catch (RuntimeException exception) {
            return Duration.ofSeconds(60);
        }
    }

    private Duration retryAfter(RiotHttpTransport.Response response) {
        var value = response.headers().firstValue("Retry-After");
        if (value.isEmpty()) {
            return RETRY_DELAY;
        }
        try {
            var duration = Duration.ofSeconds(Long.parseLong(value.orElseThrow()));
            if (duration.isNegative()) {
                return RETRY_DELAY;
            }
            return duration.compareTo(properties.maxRetryDelay()) > 0
                    ? properties.maxRetryDelay()
                    : duration;
        } catch (NumberFormatException exception) {
            return RETRY_DELAY;
        }
    }

    private void pause(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(RiotFailureCode.NETWORK_FAILURE, "Riot retry was interrupted", exception);
        }
    }

    @Override
    public dev.leagueanalysis.ingestion.riot.domain.PlatformAccountProfile verifyPlatformAccount(String puuid) {
        var profile = new SummonerProfileClient(properties, transport, json, clock).fetch(platform.name(), puuid);
        return new dev.leagueanalysis.ingestion.riot.domain.PlatformAccountProfile(profile.iconId(), profile.level(), profile.revisionAt());
    }

    private boolean validHistoryId(String id) {
        if (id == null || !id.matches("[A-Z0-9]{2,4}_[0-9]{1,20}")) return false;
        String prefix = id.substring(0, id.indexOf('_'));
        return switch (platform.regionalRoute()) {
            case "AMERICAS" -> Set.of("NA1", "BR1", "LA1", "LA2").contains(prefix);
            case "EUROPE" -> Set.of("EUW1", "EUN1", "TR1", "RU", "ME1").contains(prefix);
            case "ASIA" -> Set.of("KR", "JP1").contains(prefix);
            default -> false;
        };
    }

    private boolean validMatchId(String id) {
        try { return dev.leagueanalysis.ingestion.riot.domain.RiotPlatform.fromMatchId(id) == platform; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private void validateMatchId(String matchId) {
        if (!validMatchId(matchId)) {
            throw failure(RiotFailureCode.INVALID_INPUT, "Match ID is invalid");
        }
    }

    private String requiredText(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw failure(RiotFailureCode.INVALID_RESPONSE, "Riot response is invalid");
        }
        return node.stringValue();
    }

    private String optionalText(JsonNode parent, String field) {
        var node = parent.get(field);
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private String encodeSegment(String value) {
        var encoded = new StringBuilder();
        for (var current : value.getBytes(StandardCharsets.UTF_8)) {
            var unsigned = current & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '.' || unsigned == '_' || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) unsigned));
            }
        }
        return encoded.toString();
    }

    private String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private RiotGatewayException failure(RiotFailureCode code, String message) {
        return new RiotGatewayException(code, message);
    }

    private RiotGatewayException failure(RiotFailureCode code, String message, Throwable cause) {
        return new RiotGatewayException(code, message, cause);
    }
}

@FunctionalInterface
interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
}
