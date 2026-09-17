import { matchIdPattern } from "../../../../../lib/player-lookup/regions";
import { backendFetch } from "../../../../../lib/backend-transport";
import { parseTimeline } from "../../../../../lib/development/timeline";
import { isSamplePreview } from "../../../../../lib/preview-mode";
import { retryDate } from "../../../../../lib/player-lookup/types";
const headers = { "Cache-Control": "no-store" };
export const dynamic = "force-dynamic";
async function proxy(request: Request, { params }: { params: Promise<{ matchId: string }> }) {
  const { matchId } = await params;
  if (!matchIdPattern.test(matchId)) return Response.json({ error: "INVALID_MATCH_ID" }, { status: 400, headers });
  if (request.method === "POST" && request.body !== null) {
    if (Number(request.headers.get("Content-Length")) > 64) return Response.json({ error: "INVALID_BODY" }, { status: 400, headers });
    const body = await request.text();
    if (body.trim() && body.trim() !== "{}") return Response.json({ error: "INVALID_BODY" }, { status: 400, headers });
  }
  if (isSamplePreview()) return Response.json({ error: "TIMELINE_UNAVAILABLE" }, { status: 503, headers });
  try {
    const response = await backendFetch(`/api/v1/matches/${matchId}/timeline`, {
      method: request.method, cache: "no-store", signal: AbortSignal.timeout(8000),
    });
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      return Response.json({ error: "TIMELINE_UNAVAILABLE", retryNotBefore: retryDate(body?.retryNotBefore) }, {
        status: [400, 404, 429, 503].includes(response.status) ? response.status : 502, headers,
      });
    }
    const result = parseTimeline(await response.json());
    if (result.matchId !== matchId) throw new Error("MISMATCHED_MATCH");
    return Response.json(result, { status: result.status === "RUNNING" ? 202 : 200, headers });
  } catch { return Response.json({ error: "TIMELINE_UNAVAILABLE" }, { status: 502, headers }); }
}
export const GET = proxy;
export const POST = proxy;
