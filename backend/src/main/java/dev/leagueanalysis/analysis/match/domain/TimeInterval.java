package dev.leagueanalysis.analysis.match.domain;

public record TimeInterval(long startMs, long endMs) {
    public TimeInterval {
        if (startMs < 0 || endMs < startMs) {
            throw new IllegalArgumentException("INVALID_TIME_INTERVAL");
        }
    }
}
