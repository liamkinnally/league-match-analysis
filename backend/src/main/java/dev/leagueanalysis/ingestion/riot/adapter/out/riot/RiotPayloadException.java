package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

public final class RiotPayloadException extends RuntimeException {
    public RiotPayloadException(String code) {
        super(code);
    }
}
