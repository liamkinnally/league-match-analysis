import { parseCurrentRanks } from "../../../../../lib/development/response-guards";
import { backendFetch } from "../../../../../lib/backend-transport";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ matchId: string }> },
) {
  const { matchId } = await params;
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(matchId))
    return Response.json({ error: "INVALID_MATCH_ID" }, { status: 400 });
  try {
    const response = await backendFetch(
      `/api/v1/matches/${encodeURIComponent(matchId)}/ranks`,
      { cache: "no-store", signal: AbortSignal.timeout(8000) },
    );
    if (!response.ok)
      return Response.json(
        { error: "RANKS_UNAVAILABLE" },
        {
          status: response.status === 404 ? 404 : 503,
          headers: { "Cache-Control": "no-store" },
        },
      );
    const ranks = parseCurrentRanks(await response.json());
    if (ranks.matchId !== matchId) throw new Error("MISMATCHED_MATCH");
    return Response.json(ranks, { headers: { "Cache-Control": "no-store" } });
  } catch {
    return Response.json(
      { error: "RANKS_UNAVAILABLE" },
      { status: 503, headers: { "Cache-Control": "no-store" } },
    );
  }
}
