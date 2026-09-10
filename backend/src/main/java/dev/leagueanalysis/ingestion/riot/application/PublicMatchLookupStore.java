package dev.leagueanalysis.ingestion.riot.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PublicMatchLookupStore {
    UUID startPublicRun(RiotIngestionCommand command, Instant now);
    Optional<PublicMatchLookup> readPublicRun(UUID runId);
    Optional<PublicMatchLookup> findFresh(RiotIngestionCommand command, Instant since);
    Optional<Instant> latestCooldown();
    void failInterrupted(Instant now);
    void stopForCooldown(UUID runId, Instant retryNotBefore, Instant now);
    void failPublicRun(UUID runId, Instant now);
}
