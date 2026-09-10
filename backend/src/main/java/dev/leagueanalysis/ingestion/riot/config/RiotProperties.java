package dev.leagueanalysis.ingestion.riot.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("league-analysis.riot")
public record RiotProperties(
        String apiKey,
        @DefaultValue("AMERICAS") String regionalRoute,
        @DefaultValue("NA1") String platformRoute,
        @DefaultValue("420") int queueId,
        @DefaultValue("16777216") int maxResponseBytes,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("5s") Duration maxRetryDelay) {
    public static final String REQUIRED_REGIONAL_ROUTE = "AMERICAS";
    public static final String REQUIRED_PLATFORM_ROUTE = "NA1";
    public static final int REQUIRED_QUEUE_ID = 420;

    public RiotProperties {
        apiKey = apiKey == null ? "" : apiKey;
        if (!REQUIRED_REGIONAL_ROUTE.equals(regionalRoute)) {
            throw new IllegalArgumentException("INVALID_REGIONAL_ROUTE");
        }
        if (!REQUIRED_PLATFORM_ROUTE.equals(platformRoute)) {
            throw new IllegalArgumentException("INVALID_PLATFORM_ROUTE");
        }
        if (queueId != REQUIRED_QUEUE_ID) {
            throw new IllegalArgumentException("INVALID_QUEUE");
        }
        connectTimeout = positive(connectTimeout, "connectTimeout");
        requestTimeout = positive(requestTimeout, "requestTimeout");
        maxRetryDelay = positive(maxRetryDelay, "maxRetryDelay");
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("INVALID_MAX_RESPONSE_BYTES");
        }
    }

    @Override
    public String toString() {
        return "RiotProperties[apiKey=<redacted>, regionalRoute=" + regionalRoute
                + ", platformRoute=" + platformRoute
                + ", queueId=" + queueId
                + ", maxResponseBytes=" + maxResponseBytes
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout
                + ", maxRetryDelay=" + maxRetryDelay + "]";
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("INVALID_" + field.toUpperCase());
        }
        return value;
    }
}
