package dev.leagueanalysis.analysis.leaderboard;

import java.time.Instant;
import java.util.List;

/** Public projection deliberately contains no provider identifiers. */
public record Leaderboard(String platform, String queueType, String status, Instant fetchedAt,
        Instant nextRefreshAt, boolean stale, List<Entry> entries, Cutoffs cutoffs) {
    public record Cutoff(int slots,int minimumLp,Integer boundaryLp,int promotionLp) {}
    public record Cutoffs(String status,Instant fetchedAt,boolean stale,Cutoff challenger,Cutoff grandmaster) {}
    public record Entry(int position, String gameName, String tagLine, Integer profileIconId,
            String tier, int leaguePoints, int wins, int losses) {}
}
