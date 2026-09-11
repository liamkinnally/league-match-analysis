export type MatchSummary = {
  matchId: string; participantId: number; championName: string; championId: number;
  gameVersion: string; endItemIds: number[]; position: string; win: boolean; startedAtMs: number; durationSeconds: number;
  kills: number; deaths: number; assists: number; cs: number; gold: number; timelineAvailable: boolean;
};
export type PlayerLookup = {
  runId: string; gameName: string; tagLine: string;
  status: "RUNNING" | "COMPLETE" | "EMPTY" | "PARTIAL" | "FAILED";
  message: string | null; retryNotBefore: string | null; matches: MatchSummary[];
};
export const runIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const messages = new Set([
  "Live lookup is unavailable. Explore the sample match.",
  "Riot ID was not found. Check the name and tag.",
  "Riot is cooling down. Try again after the indicated time.",
  "Lookup was interrupted. Search again to retry.",
  "Some match data is unavailable. Completed matches are ready to open.",
  "Lookup could not finish. Search again or explore the sample match.",
]);
const record = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("INVALID_LOOKUP");
  return value as Record<string, unknown>;
};
const text = (value: unknown, max = 64): string => {
  if (typeof value !== "string" || !value.length || value.length > max) throw new Error("INVALID_LOOKUP");
  return value;
};
const number = (value: unknown, max = Number.MAX_SAFE_INTEGER): number => {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0 || value > max) throw new Error("INVALID_LOOKUP");
  return value;
};
const boolean = (value: unknown): boolean => {
  if (typeof value !== "boolean") throw new Error("INVALID_LOOKUP");
  return value;
};
export function retryDate(value: unknown): string | null {
  return typeof value === "string" && value.length <= 40 && Number.isFinite(Date.parse(value)) ? value : null;
}
export function parseLookup(value: unknown): PlayerLookup {
  const r = record(value);
  const runId = text(r.runId);
  if (!runIdPattern.test(runId) || !["RUNNING", "COMPLETE", "EMPTY", "PARTIAL", "FAILED"].includes(String(r.status))
      || !Array.isArray(r.matches) || r.matches.length > 5) throw new Error("INVALID_LOOKUP");
  const unresolved = (r.status === "RUNNING" || r.status === "FAILED")
    && r.gameName === "" && r.tagLine === "";
  return {
    runId, gameName: unresolved ? "" : text(r.gameName), tagLine: unresolved ? "" : text(r.tagLine, 16), status: r.status as PlayerLookup["status"],
    message: typeof r.message === "string" && messages.has(r.message) ? r.message : null,
    retryNotBefore: retryDate(r.retryNotBefore),
    matches: r.matches.map((value) => {
      const m = record(value);
      const matchId = text(m.matchId);
      const participantId = number(m.participantId, 10);
      if (!/^NA1_\d+$/.test(matchId) || participantId < 1) throw new Error("INVALID_LOOKUP");
      if (!Array.isArray(m.endItemIds) || m.endItemIds.length > 7) throw new Error("INVALID_LOOKUP");
      return { matchId, participantId, championName: text(m.championName), championId: number(m.championId),
        gameVersion: text(m.gameVersion), endItemIds: m.endItemIds.map((id) => number(id, 100000)),
        position: text(m.position || "UNKNOWN"), win: boolean(m.win), startedAtMs: number(m.startedAtMs),
        durationSeconds: number(m.durationSeconds), kills: number(m.kills), deaths: number(m.deaths),
        assists: number(m.assists), cs: number(m.cs), gold: number(m.gold), timelineAvailable: boolean(m.timelineAvailable) };
    }),
  };
}
