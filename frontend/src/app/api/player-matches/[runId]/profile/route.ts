import { profileError, proxyProfile } from "../../../../../lib/player-lookup/profile-proxy";
import { runIdPattern } from "../../../../../lib/player-lookup/types";
export const dynamic = "force-dynamic";
export async function GET(request: Request, context: { params: Promise<{ runId: string }> }): Promise<Response> {
  const { runId } = await context.params;
  const params = new URL(request.url).searchParams, cursor = params.get("cursor");
  if (!runIdPattern.test(runId) || [...params.keys()].some(key => key !== "cursor") || params.getAll("cursor").length > 1 || (cursor !== null && (!/^[A-Za-z0-9_-]+={0,2}$/.test(cursor) || cursor.length > 256))) return profileError(400);
  return proxyProfile(runId, "GET", cursor);
}
