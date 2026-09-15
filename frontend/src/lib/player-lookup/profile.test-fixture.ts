export const profileFixture = {
  identity: { gameName: "Invented Player", tagLine: "DEMO" },
  summoner: { status: "available", profileIconId: 29, summonerLevel: 123, revisionAt: "2026-09-14T10:00:00Z", fetchedAt: "2026-09-14T10:00:00Z", refreshing: false, stale: false, retryNotBefore: null, error: null },
  soloRank: { status: "ranked", tier: "EMERALD", division: "II", leaguePoints: 42, wins: 80, losses: 60, winRate: 57.1, period: "unknown", fetchedAt: "2026-09-14T10:01:00Z", refreshing: false, stale: false, retryNotBefore: null, error: null },
  flexRank: { status: "unranked", tier: null, division: null, leaguePoints: null, wins: null, losses: null, winRate: null, period: "unknown", fetchedAt: "2026-09-14T10:01:00Z", refreshing: false, stale: false, retryNotBefore: null, error: null },
  recentSolo: { queueId: 420, target: 20, sampleSize: 8, wins: 5, losses: 3, snapshotAt: "2026-09-14T10:02:00Z", oldestIncludedAt: "2026-09-12T10:02:00Z", policyVersion: "solo-eligibility-unverified-v1", checkedCandidates: 10, unknownCount: 10, excludedCount: 0, completeness: "unverified", canLoad: true, retryNotBefore: null },
  rankHistory: { trackingSince: "2026-09-13T10:00:00Z", observations: [{ id: "00000000-0000-0000-0000-000000000010", observedAt: "2026-09-14T10:01:00Z", status: "ranked", tier: "EMERALD", division: "II", leaguePoints: 42, wins: 80, losses: 60, period: null }], nextCursor: null },
};
