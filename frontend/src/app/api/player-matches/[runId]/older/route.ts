import { acceptsEmptyCommand } from "../../../../../lib/request-body";
import { lookupError, proxyLookup } from "../../../../../lib/player-lookup/proxy";
import { runIdPattern } from "../../../../../lib/player-lookup/types";
export const dynamic = "force-dynamic";
export async function POST(request: Request, context: { params: Promise<{ runId: string }> }): Promise<Response> {
  const { runId } = await context.params;
  if (!runIdPattern.test(runId)) return lookupError(400);
  // These operations accept only the server-owned page handle, never pagination or provider options.
  if (!await acceptsEmptyCommand(request)) return lookupError(400);
  return proxyLookup(`/${runId}/older`, undefined, "POST");
}
