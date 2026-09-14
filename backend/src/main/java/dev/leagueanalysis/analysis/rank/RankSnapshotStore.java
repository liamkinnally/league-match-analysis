package dev.leagueanalysis.analysis.rank;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Private minimized cache; implementations gate every read/write against removal state. */
public interface RankSnapshotStore {
    record Value(String status, String tier, String division, Integer lp, Integer wins, Integer losses) {}
    record Snapshot(Map<String, RankSnapshotStore.Value> values, Instant fetchedAt, Instant retryAt, String error, Instant leaseUntil) {}
    Optional<Snapshot> read(String platform, String puuid);
    boolean claim(String platform, String puuid, Instant now, UUID refreshId);
    void success(String platform, String puuid, Map<String, RankSnapshotStore.Value> values, Instant now, UUID refreshId);
    void failure(String platform, String puuid, String error, Instant retryAt, UUID refreshId);
    void requireHealthy();
    boolean isAllowed(String puuid);
}
