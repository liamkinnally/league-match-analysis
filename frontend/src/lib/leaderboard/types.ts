import { validRiotIdentity } from "../player-lookup/regions";
export type LeaderboardEntry = { position: number; gameName: string | null; tagLine: string | null; profileIconId: number | null; tier: string; leaguePoints: number; wins: number; losses: number };
export type PromotionCutoff = { slots: number; minimumLp: number; boundaryLp: number | null; promotionLp: number };
export type LeaderboardCutoffs = { status: "ready" | "pending" | "unavailable"; fetchedAt: string | null; stale: boolean; challenger: PromotionCutoff | null; grandmaster: PromotionCutoff | null };
export type LeaderboardSnapshot = { platform: "NA1"; queueType: "RANKED_SOLO_5x5"; status: "ready" | "pending" | "unavailable"; fetchedAt: string | null; nextRefreshAt: string | null; stale: boolean; entries: LeaderboardEntry[]; cutoffs: LeaderboardCutoffs };
function invalid(): never { throw new Error("INVALID_LEADERBOARD"); }
function record(value: unknown): Record<string, unknown> { return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : invalid(); }
function integer(value: unknown, max = 2_147_483_647): number { return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= max ? value : invalid(); }
function date(value: unknown): string | null { return value === null ? null : typeof value === "string" && value.length <= 40 && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value)) ? value : invalid(); }
export function parseLeaderboard(value: unknown): LeaderboardSnapshot {
  const root = record(value);
  if (root.platform !== "NA1" || root.queueType !== "RANKED_SOLO_5x5" || !["ready", "pending", "unavailable"].includes(String(root.status)) || typeof root.stale !== "boolean" || !Array.isArray(root.entries) || root.entries.length > 300) return invalid();
  const positions = new Set<number>();
  const entries = root.entries.map(value => {
    const row = record(value), position = integer(row.position, 300);
    if (position < 1 || positions.has(position)) return invalid();
    positions.add(position);
    const named = typeof row.gameName === "string" && typeof row.tagLine === "string" && validRiotIdentity(row.gameName, row.tagLine);
    if (!named && (row.gameName !== null || row.tagLine !== null)) return invalid();
    if (row.tier !== "CHALLENGER") return invalid();
    return { position, gameName: row.gameName as string | null, tagLine: row.tagLine as string | null, profileIconId: row.profileIconId === null ? null : integer(row.profileIconId), tier: row.tier as string, leaguePoints: integer(row.leaguePoints), wins: integer(row.wins), losses: integer(row.losses) };
  });
  const fetchedAt = date(root.fetchedAt);
  if ((entries.length > 0 || root.status === "ready") && fetchedAt === null) return invalid();
  return { platform: "NA1", queueType: "RANKED_SOLO_5x5", status: root.status as LeaderboardSnapshot["status"], fetchedAt, nextRefreshAt: date(root.nextRefreshAt), stale: root.stale, entries, cutoffs: parseCutoffs(root.cutoffs) };
}

function parseCutoff(value: unknown, slots: number, minimumLp: number): PromotionCutoff {
  const row = record(value);
  const boundaryLp = row.boundaryLp === null ? null : integer(row.boundaryLp);
  const promotionLp = integer(row.promotionLp);
  if (row.slots !== slots || row.minimumLp !== minimumLp || (boundaryLp !== null && boundaryLp < minimumLp) || promotionLp !== Math.max(minimumLp, boundaryLp === null ? minimumLp : boundaryLp + 1)) return invalid();
  return { slots, minimumLp, boundaryLp, promotionLp };
}
function parseCutoffs(value: unknown): LeaderboardCutoffs {
  const row = record(value);
  if (!["ready", "pending", "unavailable"].includes(String(row.status)) || typeof row.stale !== "boolean") return invalid();
  const fetchedAt = date(row.fetchedAt);
  if (row.status === "ready") {
    if (!fetchedAt) return invalid();
    return { status: "ready", fetchedAt, stale: row.stale, challenger: parseCutoff(row.challenger, 300, 800), grandmaster: parseCutoff(row.grandmaster, 700, 400) };
  }
  if (row.challenger !== null || row.grandmaster !== null) return invalid();
  return { status: row.status as "pending" | "unavailable", fetchedAt, stale: row.stale, challenger: null, grandmaster: null };
}
