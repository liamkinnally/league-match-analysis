package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.TimelineEventKey;

public record MatchHeader(
        String matchId,
        int queueId,
        int mapId,
        String gameMode,
        String gameType,
        String gameVersion,
        String dataVersion,
        long gameCreationMs,
        Long gameStartMs,
        Long gameEndMs,
        long durationMs) {
    public MatchHeader {
        matchId = TimelineEventKey.requireText(matchId);
        gameMode = TimelineEventKey.requireText(gameMode);
        gameType = TimelineEventKey.requireText(gameType);
        gameVersion = TimelineEventKey.requireText(gameVersion);
        dataVersion = TimelineEventKey.requireText(dataVersion);
        if (gameCreationMs < 0
                || gameStartMs != null && gameStartMs < 0
                || gameEndMs != null && gameEndMs < 0
                || gameStartMs != null && gameEndMs != null && gameEndMs < gameStartMs
                || durationMs < 0) {
            throw new IllegalArgumentException("INVALID_MATCH_TIME");
        }
    }
}
