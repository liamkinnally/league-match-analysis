package dev.leagueanalysis.ingestion.riot.application;

import java.util.Objects;

public final class RiotGatewayException extends RuntimeException {
    private final RiotFailureCode code;
    private java.time.Instant retryNotBefore;

    public RiotGatewayException(RiotFailureCode code, String sanitizedMessage) {
        super(sanitizedMessage);
        this.code = Objects.requireNonNull(code, "code");
    }

    public RiotGatewayException(RiotFailureCode code, String sanitizedMessage, Throwable cause) {
        super(sanitizedMessage, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public RiotGatewayException(RiotFailureCode code, String sanitizedMessage, java.time.Instant retryNotBefore) {
        this(code, sanitizedMessage);
        this.retryNotBefore = retryNotBefore;
    }

    public java.time.Instant retryNotBefore() { return retryNotBefore; }

    public RiotFailureCode code() {
        return code;
    }

    @Override
    public String toString() {
        return getClass().getName() + ": " + code + ": " + getMessage();
    }
}
