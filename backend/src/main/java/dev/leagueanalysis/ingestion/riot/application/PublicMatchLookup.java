package dev.leagueanalysis.ingestion.riot.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public projection: no provider identifiers, captures or exception bodies. */
public record PublicMatchLookup(UUID runId, String gameName, String tagLine, String status,
        String message, Instant retryNotBefore, List<MatchSummary> matches, int queueId,
        Instant lastUpdated, Instant nextRefreshAt, UUID previousRunId, boolean hasMore) {
    public PublicMatchLookup(UUID runId, String gameName, String tagLine, String status,
            String message, Instant retryNotBefore, List<MatchSummary> matches) {
        this(runId, gameName, tagLine, status, message, retryNotBefore, matches, 0, null, null, null, false);
    }
    public PublicMatchLookup withIdentity(String name, String tag) {
        return new PublicMatchLookup(runId, name, tag, status, message, retryNotBefore, matches,
                queueId, lastUpdated, nextRefreshAt, previousRunId, hasMore);
    }
    /** Remake is unknown unless ten explicit early-surrender flags agree; win retains the provider result. */
    public record MatchSummary(String matchId, int participantId, String championName, int championId,
            String gameVersion, List<Integer> endItemIds, String position, boolean win, long startedAtMs, long durationSeconds, int kills, int deaths,
            int assists, int cs, int gold, boolean timelineAvailable, int queueId, Boolean remake) {}
}
