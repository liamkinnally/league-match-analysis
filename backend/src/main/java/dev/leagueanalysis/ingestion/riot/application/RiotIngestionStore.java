package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.domain.CapturedDocument;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotAccount;
import dev.leagueanalysis.ingestion.riot.domain.RiotMatchMaterialization;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RiotIngestionStore {
    UUID startRun(RiotIngestionCommand command, Instant startedAt);

    boolean isCompleteMatch(String matchId);

    void recordRetryNotBefore(UUID runId, Instant retryNotBefore);

    CapturedDocument saveCapture(UUID runId, ProviderDocument document);

    void recordResolvedAccount(UUID runId, RiotAccount account, CapturedDocument source);

    void addItems(UUID runId, List<String> matchIds);

    void markItemRunning(UUID runId, String matchId, Instant startedAt);

    void materialize(UUID runId, String matchId, RiotMatchMaterialization materialization);

    void markItemTerminal(
            UUID runId,
            String matchId,
            IngestionItemStatus status,
            String failureCode,
            String failureMessage,
            Instant completedAt);

    void finishRun(
            UUID runId,
            IngestionRunStatus status,
            String failureCode,
            String failureMessage,
            Instant completedAt);
}
