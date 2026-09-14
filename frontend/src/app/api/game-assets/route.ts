import { resolveGameAssetCatalog, resolveCurrentProfileAssetCatalog } from "../../../lib/game-assets/catalog";

const SOURCE_GAME_VERSION = /^\d+\.\d+(?:\.\d+)*$/;

export async function GET(request: Request): Promise<Response> {
  const params = new URL(request.url).searchParams;
  if ([...params.keys()].some((key) => !["versions", "runes", "champions", "scope"].includes(key))) return Response.json({ error: "INVALID_ASSET_OPTIONS" }, { status: 400 });
  if (params.get("scope") === "current-profile") {
    if (params.has("versions") || params.has("champions") || params.has("runes")) return Response.json({ error: "INVALID_ASSET_OPTIONS" }, { status: 400 });
    try { return Response.json(await resolveCurrentProfileAssetCatalog(), { headers: { "Cache-Control": "public, max-age=3600, stale-while-revalidate=86400" } }); }
    catch { return Response.json(null); }
  }
  if (params.has("scope") || (params.has("runes") && params.get("runes") !== "1")) return Response.json({ error: "INVALID_ASSET_OPTIONS" }, { status: 400 });
  const champions = params.get("champions")?.split(",") ?? [];
  if (champions.length > 10 || champions.some((id) => !/^[1-9]\d{0,5}$/.test(id))) return Response.json({ error: "INVALID_CHAMPION_IDS" }, { status: 400 });
  const options = { includeRunes: params.get("runes") === "1", championIds: [...new Set(champions.map(Number))] };
  const enriched = options.includeRunes || options.championIds.length > 0;
  const requested = params.get("versions")?.split(",") ?? [];
  const versions = [...new Set(requested.map((version) => version.trim()).filter(Boolean))];
  if (!versions.length || versions.length > 5 || versions.some((version) => !SOURCE_GAME_VERSION.test(version))) {
    return Response.json({ error: "INVALID_GAME_VERSIONS" }, { status: 400 });
  }
  const entries = await Promise.all(versions.map(async (version) => {
    try {
      return [version, (enriched ? await resolveGameAssetCatalog(version, options) : await resolveGameAssetCatalog(version))] as const;
    } catch {
      return [version, null] as const;
    }
  }));
  return Response.json(Object.fromEntries(entries), {
    headers: { "Cache-Control": options.includeRunes ? "no-store" : "public, max-age=3600, stale-while-revalidate=86400" },
  });
}
