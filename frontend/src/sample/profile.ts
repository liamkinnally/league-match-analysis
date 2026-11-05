import type { PlayerProfile, RankObservation, SoloRank } from "../lib/player-lookup/profile";

const snapshots = [
  { day: "2026-09-08", lp: 1284, wins: 248, losses: 199 },
  { day: "2026-09-06", lp: 1238, wins: 244, losses: 197 },
  { day: "2026-09-04", lp: 1261, wins: 242, losses: 194 },
  { day: "2026-09-02", lp: 1189, wins: 237, losses: 192 },
  { day: "2026-08-30", lp: 1168, wins: 234, losses: 190 },
  { day: "2026-08-27", lp: 1212, wins: 232, losses: 186 },
];

const observations: RankObservation[] = snapshots.map(({ day, lp, wins, losses }, index) => ({
  id: `00000000-0000-4000-8000-${String(snapshots.length - index).padStart(12, "0")}`,
  observedAt: `${day}T23:00:00Z`,
  status: "ranked", tier: "CHALLENGER", division: "I", leaguePoints: lp,
  wins, losses, period: "sample-2026",
}));
const updatedAt = observations[0].observedAt;
const saved = { fetchedAt: updatedAt, refreshing: false, stale: false, retryNotBefore: null, error: null };

function ranked(tier: string, division: string, lp: number, wins: number, losses: number): SoloRank {
  return {
    ...saved, status: "ranked", tier, division, leaguePoints: lp, wins, losses,
    winRate: 100 * wins / (wins + losses), period: "unknown",
  };
}

const current = snapshots[0];
export const samplePlayerProfile: Omit<PlayerProfile, "recentSolo"> = {
  identity: { gameName: "Northline", tagLine: "DEMO", platform: "NA1" },
  summoner: { ...saved, status: "available", profileIconId: 29, summonerLevel: 417, revisionAt: updatedAt },
  soloRank: ranked("CHALLENGER", "I", current.lp, current.wins, current.losses),
  flexRank: ranked("DIAMOND", "II", 63, 42, 31),
  rankHistory: { trackingSince: observations[observations.length - 1].observedAt, observations, nextCursor: null },
};
