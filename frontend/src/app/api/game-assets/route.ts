import { resolveGameAssetCatalog } from "../../../lib/game-assets/catalog";

const SOURCE_GAME_VERSION = /^\d+\.\d+(?:\.\d+)*$/;

export async function GET(request: Request): Promise<Response> {
  const requested = new URL(request.url).searchParams.get("versions")?.split(",") ?? [];
  const versions = [...new Set(requested.map((version) => version.trim()).filter(Boolean))];
  if (!versions.length || versions.length > 5 || versions.some((version) => !SOURCE_GAME_VERSION.test(version))) {
    return Response.json({ error: "INVALID_GAME_VERSIONS" }, { status: 400 });
  }
  const entries = await Promise.all(versions.map(async (version) => {
    try {
      return [version, await resolveGameAssetCatalog(version)] as const;
    } catch {
      return [version, null] as const;
    }
  }));
  return Response.json(Object.fromEntries(entries), {
    headers: { "Cache-Control": "public, max-age=3600, stale-while-revalidate=86400" },
  });
}
