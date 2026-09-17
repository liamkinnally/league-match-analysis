import { backendFetch } from "../../../lib/backend-transport";
import { isSamplePreview } from "../../../lib/preview-mode";
import { isPlatform } from "../../../lib/player-lookup/regions";
import { parseSuggestions } from "../../../lib/player-lookup/suggestions";
export const dynamic = "force-dynamic";
const headers = { "Cache-Control": "no-store" };
export async function GET(request: Request): Promise<Response> {
  const search = new URL(request.url).searchParams;
  const platform = search.get("platform") ?? "NA1", q = search.get("q")?.trim() ?? "";
  if (!isPlatform(platform) || q.length > 81 || /[\u0000-\u001f\u007f]/u.test(q)
      || [...search.keys()].some(key => !["platform", "q"].includes(key))
      || search.getAll("platform").length > 1 || search.getAll("q").length > 1) return Response.json({ suggestions: [] }, { status: 400, headers });
  if (!q) return Response.json({ suggestions: [] }, { headers });
  if (isSamplePreview() || !process.env.BACKEND_URL) return Response.json({ suggestions: [] }, { status: 503, headers });
  try {
    const response = await backendFetch(`/api/v1/player-suggestions?${new URLSearchParams({ platform, q })}`, { cache: "no-store", signal: AbortSignal.timeout(4000) });
    if (!response.ok) throw new Error("SUGGESTIONS_UNAVAILABLE");
    const suggestions = parseSuggestions(await response.json());
    if (suggestions.some(suggestion => suggestion.platform !== platform)) throw new Error("REGION_MISMATCH");
    return Response.json({ suggestions }, { headers });
  } catch { return Response.json({ suggestions: [] }, { status: 503, headers }); }
}
