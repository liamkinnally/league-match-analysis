package dev.leagueanalysis.ingestion.riot.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PublicMatchLookupStore {
    UUID startPublicRun(RiotIngestionCommand command, Instant now);
    Optional<PublicMatchLookup> readPublicRun(UUID runId);
    Optional<PublicMatchLookup> findFresh(RiotIngestionCommand command, Instant since);
    Optional<Instant> latestCooldown();
    default boolean matchesPageIdentity(UUID previousRunId, String puuid) { return false; }
    default Optional<RiotIngestionCommand> readPageCommand(UUID runId) { return Optional.empty(); }
    default Optional<PublicMatchLookup> findOlder(UUID previousRunId) { return Optional.empty(); }
    default Optional<Instant> latestRefresh(RiotIngestionCommand command) { return Optional.empty(); }
    default void recordPageSize(UUID runId, int providerCount) {}
    default boolean hasSummary(String matchId, String puuid, int queueId) { return false; }
    default Optional<dev.leagueanalysis.ingestion.riot.domain.ProviderDocument> storedDetail(String matchId) { return Optional.empty(); }
    default UUID startTimelineRun(String matchId, Instant now) { throw new UnsupportedOperationException(); }
    default Optional<TimelineLookup> readTimeline(String matchId) { return Optional.empty(); }
    void failInterrupted(Instant now);
    void stopForCooldown(UUID runId, Instant retryNotBefore, Instant now);
    void failPublicRun(UUID runId, Instant now);
}
