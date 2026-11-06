import { backendFetch } from "../../../lib/backend-transport";
import { isSamplePreview } from "../../../lib/preview-mode";
import { parseLeaderboard } from "../../../lib/leaderboard/types";
const headers = { "Cache-Control": "no-store" };
const error = (status: number) => Response.json({ message: "Leaderboard is temporarily unavailable." }, { status, headers });
export async function GET(request: Request): Promise<Response> {
  if (new URL(request.url).search) return error(400);
  if (isSamplePreview() || !process.env.BACKEND_URL) return error(503);
  try {
    const response = await backendFetch("/api/v1/leaderboard/na", { cache: "no-store", signal: AbortSignal.timeout(8000) });
    if (!response.ok) return error(response.status === 503 ? 503 : 502);
    return Response.json(parseLeaderboard(await response.json()), { headers });
  } catch { return error(502); }
}
