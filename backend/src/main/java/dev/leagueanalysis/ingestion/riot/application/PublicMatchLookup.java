package dev.leagueanalysis.ingestion.riot.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public projection: no provider identifiers, captures or exception bodies. */
public record PublicMatchLookup(UUID runId, String gameName, String tagLine, String status,
        String message, Instant retryNotBefore, List<MatchSummary> matches) {
    public record MatchSummary(String matchId, int participantId, String championName, int championId,
            String gameVersion, List<Integer> endItemIds, String position, boolean win, long startedAtMs, long durationSeconds, int kills, int deaths,
            int assists, int cs, int gold, boolean timelineAvailable) {}
}
