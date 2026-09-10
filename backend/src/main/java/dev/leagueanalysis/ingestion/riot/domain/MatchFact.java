package dev.leagueanalysis.ingestion.riot.domain;

import java.util.Objects;
import java.util.UUID;

public record MatchFact(
        String matchId,
        long gameId,
        int queueId,
        int mapId,
        String gameMode,
        String gameType,
        String gameVersion,
        String dataVersion,
        long gameCreationMs,
        Long gameStartMs,
        Long gameEndMs,
        long gameDurationSeconds,
        UUID detailSourceCaptureId,
        UUID timelineSourceCaptureId,
        String materializationVersion) {
    public MatchFact {
        matchId = DomainText.require(matchId);
        gameMode = DomainText.require(gameMode);
        gameType = DomainText.require(gameType);
        gameVersion = DomainText.require(gameVersion);
        dataVersion = DomainText.require(dataVersion);
        detailSourceCaptureId = Objects.requireNonNull(detailSourceCaptureId, "detailSourceCaptureId");
        materializationVersion = DomainText.require(materializationVersion);
    }
}
