package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.domain.CapturedDocument;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotAccount;
import dev.leagueanalysis.ingestion.riot.domain.RiotMatchMaterialization;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RiotIngestionStore {
    default boolean isExcludedAccount(RiotAccount account) { return false; }

    default boolean isExcludedMatch(String matchId) { return false; }

    default boolean isExcludedDocument(ProviderDocument document) { return false; }

    /** Discard only a newly created unresolved run, before any source is retained. */
    default void discardRun(UUID runId) { throw new UnsupportedOperationException("Discard unavailable"); }

    /** Discard this run's unmaterialized item and any captured detail for it. */
    default void discardExcludedMatch(UUID runId, String matchId) {
        throw new UnsupportedOperationException("Discard unavailable");
    }

    UUID startRun(RiotIngestionCommand command, Instant startedAt);

    boolean isCompleteMatch(String matchId);

    default boolean isCompleteMatch(String matchId, String puuid) {
        return isCompleteMatch(matchId);
    }

    void recordRetryNotBefore(UUID runId, Instant retryNotBefore);

    CapturedDocument saveCapture(UUID runId, ProviderDocument document);

    void recordResolvedAccount(UUID runId, RiotAccount account, CapturedDocument source);

    /** Only called after the Account response is exclusion-checked, captured and resolved. */
    default void recordVerifiedRequestedIdentity(UUID runId, RiotIngestionCommand command) {}

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
