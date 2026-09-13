import { lookupError, proxyLookup } from "../../../../../lib/player-lookup/proxy";
import { runIdPattern } from "../../../../../lib/player-lookup/types";
export const dynamic = "force-dynamic";
export async function POST(request: Request, context: { params: Promise<{ runId: string }> }): Promise<Response> {
  const { runId } = await context.params;
  if (!runIdPattern.test(runId)) return lookupError(400);
  // These operations accept only the server-owned page handle, never pagination or provider options.
  if (request.body !== null) {
    if (Number(request.headers.get("Content-Length")) > 64) return lookupError(400);
    const body = await request.text();
    if (body.trim() !== "" && body.trim() !== "{}") return lookupError(400);
  }
  return proxyLookup(`/${runId}/refresh`, undefined, "POST");
}
