import { matchIdPattern } from "../player-lookup/regions";
import { retryDate, runIdPattern } from "../player-lookup/types";
export type TimelineLookup = {
  matchId: string; runId: string | null;
  status: "NOT_REQUESTED" | "RUNNING" | "AVAILABLE" | "UNAVAILABLE" | "FAILED";
  message: string | null; retryNotBefore: string | null;
};
export function parseTimeline(value: unknown): TimelineLookup {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("INVALID_TIMELINE");
  const r = value as Record<string, unknown>;
  if (typeof r.matchId !== "string" || !matchIdPattern.test(r.matchId)
    || !(r.runId === null || (typeof r.runId === "string" && runIdPattern.test(r.runId)))
    || !["NOT_REQUESTED", "RUNNING", "AVAILABLE", "UNAVAILABLE", "FAILED"].includes(String(r.status))) throw new Error("INVALID_TIMELINE");
  return { matchId: r.matchId, runId: r.runId as string | null, status: r.status as TimelineLookup["status"],
    // Provider messages are deliberately replaced by local, public status copy.
    message: null, retryNotBefore: retryDate(r.retryNotBefore) };
}
