import { isPlatform, validRiotIdentity } from "../../../lib/player-lookup/regions";
import { lookupError, proxyLookup } from "../../../lib/player-lookup/proxy";
import { isHistoryFilter } from "../../../lib/player-lookup/types";
export const dynamic = "force-dynamic";
export async function POST(request: Request): Promise<Response> {
  try {
    if (Number(request.headers.get("Content-Length")) > 4096) return lookupError(400);
    const raw = await request.text();
    if (raw.length > 4096) return lookupError(400);
    const body: unknown = JSON.parse(raw);
    if (!body || typeof body !== "object" || !("gameName" in body) || !("tagLine" in body)
        || typeof body.gameName !== "string" || typeof body.tagLine !== "string"
        || Object.keys(body).some(key => !["gameName", "tagLine", "queueId", "platform"].includes(key))) return lookupError(400);
    const gameName = body.gameName.trim();
    const tagLine = body.tagLine.trim();
    if (!validRiotIdentity(gameName, tagLine)) return lookupError(400);
    const queueId = "queueId" in body ? body.queueId : 0;
    if (!isHistoryFilter(queueId)) return lookupError(400);
    const platform = "platform" in body ? body.platform : "NA1";
    if (!isPlatform(platform)) return lookupError(400);
    return proxyLookup("", { gameName, tagLine, queueId, platform });
  } catch { return lookupError(400); }
}
