import { lookupError, proxyLookup } from "../../../../lib/player-lookup/proxy";
import { runIdPattern } from "../../../../lib/player-lookup/types";
export const dynamic = "force-dynamic";
export async function GET(_request: Request, context: { params: Promise<{ runId: string }> }): Promise<Response> {
  const { runId } = await context.params;
  if (!runIdPattern.test(runId)) return lookupError(400);
  return proxyLookup(`/${runId}`);
}
