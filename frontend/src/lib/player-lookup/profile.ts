import { runIdPattern } from "./types";

export type ProfileIdentity = { gameName: string; tagLine: string };
type Freshness = { fetchedAt: string | null; refreshing: boolean; stale: boolean; retryNotBefore: string | null; error: string | null };
export type SummonerProfile = Freshness & { status: "available" | "loading" | "unavailable"; profileIconId: number | null; summonerLevel: number | null; revisionAt: string | null };
export type SoloRank = Freshness & { status: "ranked" | "unranked" | "loading" | "unavailable"; tier: string | null; division: string | null; leaguePoints: number | null; wins: number | null; losses: number | null; winRate: number | null; period: "unknown" };
export type RecentSoloRecord = { queueId: 420; target: 20; sampleSize: number; wins: number; losses: number; snapshotAt: string; oldestIncludedAt: string | null; policyVersion: string; checkedCandidates: number; unknownCount: number; excludedCount: number; completeness: "complete_target" | "complete_checked_range" | "incomplete_budget" | "incomplete_missing" | "loading" | "unverified"; canLoad: boolean; retryNotBefore: string | null };
export type RankObservation = { id: string; observedAt: string; status: "ranked" | "unranked"; tier: string | null; division: string | null; leaguePoints: number | null; wins: number | null; losses: number | null; period: string | null };
export type PlayerProfile = { identity: ProfileIdentity; summoner: SummonerProfile; soloRank: SoloRank; recentSolo: RecentSoloRecord; rankHistory: { trackingSince: string | null; observations: RankObservation[]; nextCursor: string | null } };

const fail = (): never => { throw new Error("INVALID_PLAYER_PROFILE"); };
const record = (v: unknown): Record<string, unknown> => v && typeof v === "object" && !Array.isArray(v) ? v as Record<string, unknown> : fail();
const text = (v: unknown, max = 128): string => typeof v === "string" && v.trim().length > 0 && v.length <= max ? v : fail();
const bool = (v: unknown): boolean => typeof v === "boolean" ? v : fail();
const integer = (v: unknown): number => typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? v : fail();
const nullableInteger = (v: unknown) => v === null ? null : integer(v);
const date = (v: unknown): string => typeof v === "string" && /^\d{4}-\d\d-\d\dT/.test(v) && Number.isFinite(Date.parse(v)) ? v : fail();
const nullableDate = (v: unknown) => v === null ? null : date(v);
const choice = <T extends string>(v: unknown, values: readonly T[]): T => typeof v === "string" && values.includes(v as T) ? v as T : fail();
const nullableChoice = <T extends string>(v: unknown, values: readonly T[]): T | null => v === null ? null : choice(v, values);
const tiers = ["IRON", "BRONZE", "SILVER", "GOLD", "PLATINUM", "EMERALD", "DIAMOND", "MASTER", "GRANDMASTER", "CHALLENGER"] as const;
const divisions = ["I", "II", "III", "IV"] as const;
function freshness(value: Record<string, unknown>): Freshness {
  return { fetchedAt: nullableDate(value.fetchedAt), refreshing: bool(value.refreshing), stale: bool(value.stale), retryNotBefore: nullableDate(value.retryNotBefore), error: value.error === null ? null : (text(value.error), "UNAVAILABLE") };
}
function rankValues(value: Record<string, unknown>) {
  return { tier: nullableChoice(value.tier, tiers), division: nullableChoice(value.division, divisions), leaguePoints: nullableInteger(value.leaguePoints), wins: nullableInteger(value.wins), losses: nullableInteger(value.losses) };
}
export const sameProfileIdentity = (a: ProfileIdentity, b: ProfileIdentity) => a.gameName.toLocaleLowerCase() === b.gameName.toLocaleLowerCase() && a.tagLine.toLocaleLowerCase() === b.tagLine.toLocaleLowerCase();
export const profileNeedsPolling = (profile: PlayerProfile) => profile.summoner.refreshing || profile.summoner.status === "loading" || profile.soloRank.refreshing || profile.soloRank.status === "loading" || profile.recentSolo.completeness === "loading";
export function parsePlayerProfile(value: unknown): PlayerProfile {
  const root = record(value), identity = record(root.identity), summoner = record(root.summoner), solo = record(root.soloRank), recent = record(root.recentSolo), history = record(root.rankHistory);
  if (!Array.isArray(history.observations) || history.observations.length > 50) fail();
  const observations = (history.observations as unknown[]).map((v): RankObservation => {
    const row = record(v), id = text(row.id), status = choice(row.status, ["ranked", "unranked"] as const), values = rankValues(row);
    if (!runIdPattern.test(id) || (status === "ranked" && (values.tier === null || values.division === null || values.leaguePoints === null))) fail();
    return { id, observedAt: date(row.observedAt), status, ...values, period: row.period === null ? null : text(row.period, 64) };
  });
  if (new Set(observations.map(row => row.id)).size !== observations.length) fail();
  const nextCursor = history.nextCursor === null ? null : text(history.nextCursor, 256);
  if (nextCursor && !/^[A-Za-z0-9_-]+={0,2}$/.test(nextCursor)) fail();
  const wins = integer(recent.wins), losses = integer(recent.losses), sampleSize = integer(recent.sampleSize);
  if (recent.queueId !== 420 || recent.target !== 20 || sampleSize > 20 || wins + losses !== sampleSize) fail();
  const completeness = choice(recent.completeness, ["complete_target", "complete_checked_range", "incomplete_budget", "incomplete_missing", "loading", "unverified"] as const);
  if (completeness === "complete_target" && sampleSize !== 20) fail();
  const soloValues = rankValues(solo), status = choice(solo.status, ["ranked", "unranked", "loading", "unavailable"] as const);
  if (status === "ranked" && (soloValues.tier === null || soloValues.division === null || soloValues.leaguePoints === null)) fail();
  if (solo.winRate !== null && (typeof solo.winRate !== "number" || !Number.isFinite(solo.winRate) || solo.winRate < 0 || solo.winRate > 100)) fail();
  return {
    identity: { gameName: text(identity.gameName, 64), tagLine: text(identity.tagLine, 16) },
    summoner: { ...freshness(summoner), status: choice(summoner.status, ["available", "loading", "unavailable"] as const), profileIconId: nullableInteger(summoner.profileIconId), summonerLevel: nullableInteger(summoner.summonerLevel), revisionAt: nullableDate(summoner.revisionAt) },
    soloRank: { ...freshness(solo), ...soloValues, status, winRate: solo.winRate as number | null, period: choice(solo.period, ["unknown"] as const) },
    recentSolo: { queueId: 420, target: 20, sampleSize, wins, losses, snapshotAt: date(recent.snapshotAt), oldestIncludedAt: nullableDate(recent.oldestIncludedAt), policyVersion: text(recent.policyVersion, 64), checkedCandidates: integer(recent.checkedCandidates), unknownCount: integer(recent.unknownCount), excludedCount: integer(recent.excludedCount), completeness, canLoad: bool(recent.canLoad), retryNotBefore: nullableDate(recent.retryNotBefore) },
    rankHistory: { trackingSince: nullableDate(history.trackingSince), observations, nextCursor },
  };
}
