package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import java.io.IOException;

public final class ResponseBodyLimitException extends IOException {
    public ResponseBodyLimitException() {
        super("Response body exceeded configured limit");
    }
}
