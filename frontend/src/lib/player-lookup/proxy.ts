import type { Platform } from "./regions";
import { parseLookup, retryDate } from "./types";
import { backendFetch } from "../backend-transport";
import { isSamplePreview } from "../preview-mode";

const headers = { "Cache-Control": "no-store" };
export function lookupError(status: number, retryNotBefore: string | null = null, retryAfter?: string | null) {
  const message = status === 400 ? "Enter a Riot game name and tag line."
    : status === 404 ? "Lookup not found. Search again."
    : status === 429 ? "Lookup is busy or cooling down. Try again after the indicated time."
    : "Live lookup is unavailable. Explore the sample match.";
  return Response.json({ message, retryNotBefore }, { status, headers: {
    ...headers, ...(retryAfter && /^\d{1,15}$/.test(retryAfter) ? { "Retry-After": retryAfter } : {}),
  } });
}
export async function proxyLookup(path: string, input?: { gameName: string; tagLine: string; queueId: number; platform: Platform }, method: "GET" | "POST" = input ? "POST" : "GET"): Promise<Response> {
  if (isSamplePreview()) return Response.json({ message: "Live player search is unavailable in this sample preview. Explore the synthetic match.",
    retryNotBefore: null }, { status: 503, headers });
  if (!process.env.BACKEND_URL) return lookupError(503);
  try {
    const response = await backendFetch(`/api/v1/player-matches${path}`, {
      method, cache: "no-store", signal: AbortSignal.timeout(8000),
      ...(input ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(input) } : {}),
    });
    if (!response.ok) {
      const body: unknown = await response.json().catch(() => null);
      const retry = body && typeof body === "object" && "retryNotBefore" in body ? retryDate(body.retryNotBefore) : null;
      return lookupError([400, 404, 429, 503].includes(response.status) ? response.status : 502, retry, response.headers.get("Retry-After"));
    }
    return Response.json(parseLookup(await response.json()), { status: response.status === 202 ? 202 : 200, headers });
  } catch { return lookupError(502); }
}
