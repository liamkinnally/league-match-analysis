package dev.leagueanalysis.ingestion.riot.application;

import java.time.Instant;

public final class PublicLookupException extends RuntimeException {
    private final int status;
    private final Instant retryNotBefore;
    public PublicLookupException(int status, String message, Instant retryNotBefore) {
        super(message);
        this.status = status;
        this.retryNotBefore = retryNotBefore;
    }
    public int status() { return status; }
    public Instant retryNotBefore() { return retryNotBefore; }
}
