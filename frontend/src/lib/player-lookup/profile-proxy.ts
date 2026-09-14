import "server-only";
import { backendFetch } from "../backend-transport";
import { isSamplePreview } from "../preview-mode";
import { parsePlayerProfile } from "./profile";
import { retryDate } from "./types";

export function profileError(status: number, retryNotBefore: string | null = null, retryAfter: string | null = null): Response {
  const message = status === 404 ? "Profile is not available for this lookup yet." : status === 429 ? "Recent Solo/Duo collection is cooling down. Try again after the indicated time." : status === 400 ? "The profile request is invalid." : "Profile details are unavailable. Loaded matches remain available.";
  return Response.json({ message, retryNotBefore }, { status, headers: { "Cache-Control": "no-store", ...(retryAfter && /^\d{1,15}$/.test(retryAfter) ? { "Retry-After": retryAfter } : {}) } });
}
export async function proxyProfile(runId: string, method: "GET" | "POST", cursor?: string | null): Promise<Response> {
  if (isSamplePreview() || !process.env.BACKEND_URL) return profileError(503);
  const path = `/api/v1/player-matches/${runId}/${method === "POST" ? "recent-record" : "profile"}${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`;
  try {
    const response = await backendFetch(path, { method, cache: "no-store", signal: AbortSignal.timeout(8000) });
    if (!response.ok) {
      const body: unknown = await response.json().catch(() => null);
      const retry = body && typeof body === "object" && "retryNotBefore" in body ? retryDate(body.retryNotBefore) : null;
      return profileError([400, 404, 429, 503].includes(response.status) ? response.status : 502, retry, response.headers.get("Retry-After"));
    }
    return Response.json(parsePlayerProfile(await response.json()), { status: response.status === 202 ? 202 : 200, headers: { "Cache-Control": "no-store" } });
  } catch { return profileError(502); }
}
