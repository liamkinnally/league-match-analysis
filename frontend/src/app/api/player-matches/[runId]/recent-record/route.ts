import { profileError, proxyProfile } from "../../../../../lib/player-lookup/profile-proxy";
import { runIdPattern } from "../../../../../lib/player-lookup/types";
export const dynamic = "force-dynamic";
export async function POST(request: Request, context: { params: Promise<{ runId: string }> }): Promise<Response> {
  const { runId } = await context.params;
  if (!runIdPattern.test(runId) || new URL(request.url).search || Number(request.headers.get("Content-Length")) > 0) return profileError(400);
  if (request.body) {
    const reader = request.body.getReader();
    const first = await reader.read();
    await reader.cancel();
    if (!first.done) return profileError(400);
  }
  return proxyProfile(runId, "POST");
}
