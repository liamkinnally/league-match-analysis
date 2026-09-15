package dev.leagueanalysis.analysis.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Minimized public projection. Observation values deliberately carry no inferred LP deltas. */
public record PlayerProfile(Identity identity, Summoner summoner, Rank soloRank, Rank flexRank,
        RecentRecord recentSolo, RankHistory rankHistory) {
    public record Identity(String gameName, String tagLine) {}
    public record Summoner(String status, Integer profileIconId, Long summonerLevel, Instant revisionAt,
            Instant fetchedAt, boolean refreshing, boolean stale, Instant retryNotBefore, String error) {}
    public record Rank(String status, String tier, String division, Integer leaguePoints, Integer wins,
            Integer losses, Double winRate, String period, Instant fetchedAt, boolean refreshing,
            boolean stale, Instant retryNotBefore, String error) {}
    public record RecentRecord(int queueId, int target, int sampleSize, int wins, int losses,
            Instant snapshotAt, Instant oldestIncludedAt, String policyVersion, int checkedCandidates,
            int unknownCount, int excludedCount, String completeness, boolean canLoad, Instant retryNotBefore) {}
    public record Observation(UUID id, Instant observedAt, String status, String tier, String division,
            Integer leaguePoints, Integer wins, Integer losses, String period) {}
    public record RankHistory(Instant trackingSince, List<Observation> observations, String nextCursor) {}
}
