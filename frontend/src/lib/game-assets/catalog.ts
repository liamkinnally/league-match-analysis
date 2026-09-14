import "server-only";

import type { GameAsset, GameAssetCatalog, GameAssetOptions } from "./types";
import { DATA_DRAGON, descriptionText, safeAssetPath } from "./urls";
import { assetManifest, CURRENT_PROFILE_ART_PATCH } from "./manifest";
import { resolveRuneAssets } from "./dynamic-runes";
import { readAssetSource } from "./source";
import { championAbilities } from "./abilities";
import { eventAssetCatalog, rankAsset } from "./event-assets";

const SOURCE_GAME_VERSION = /^\d+\.\d+(?:\.\d+)*$/;
const DATA_DRAGON_VERSION = /^\d+\.\d+\.\d+$/;

type DataDragonEntry = {
  key?: unknown;
  name?: unknown;
  image?: { full?: unknown };
  description?: unknown;
  cooldown?: unknown;
  gold?: {
    base?: unknown;
    total?: unknown;
    sell?: unknown;
    purchasable?: unknown;
  };
};

const price = (value: unknown): number | null =>
  typeof value === "number" && Number.isFinite(value) && value >= 0
    ? value
    : null;

function patchPrefix(version: string): string {
  if (!SOURCE_GAME_VERSION.test(version))
    throw new Error("INVALID_GAME_VERSION");
  return version.split(".").slice(0, 2).join(".");
}

function numericVersion(version: string): number[] {
  return version.split(".").map(Number);
}

function newestMatchingVersion(versions: unknown, gameVersion: string): string {
  if (
    !Array.isArray(versions) ||
    !versions.every((value) => typeof value === "string")
  ) {
    throw new Error("INVALID_ASSET_VERSIONS");
  }
  const prefix = `${patchPrefix(gameVersion)}.`;
  const candidates = versions.filter(
    (version) =>
      DATA_DRAGON_VERSION.test(version) && version.startsWith(prefix),
  );
  candidates.sort((left, right) => {
    const a = numericVersion(left);
    const b = numericVersion(right);
    return b[0] - a[0] || b[1] - a[1] || b[2] - a[2];
  });
  if (!candidates[0]) throw new Error("MATCHING_ASSET_VERSION_NOT_FOUND");
  return candidates[0];
}

async function cachedJson(url: string): Promise<unknown> {
  return (await readAssetSource(url)).body;
}

function assets(
  body: unknown,
  version: string,
  kind: "champion" | "item" | "spell" | "profileicon",
): Record<string, GameAsset> {
  if (
    !body ||
    typeof body !== "object" ||
    !("data" in body) ||
    !body.data ||
    typeof body.data !== "object"
  ) {
    throw new Error("INVALID_ASSET_CATALOG");
  }
  const result: Record<string, GameAsset> = {};
  for (const [id, raw] of Object.entries(body.data)) {
    const entry = raw as DataDragonEntry;
    const key = kind === "item" || kind === "profileicon" ? id : entry?.key;
    const name = kind === "profileicon" ? `Profile icon ${id}` : entry?.name;
    const full = entry?.image?.full;
    if (
      typeof key !== "string" ||
      typeof name !== "string" ||
      typeof full !== "string" || !safeAssetPath(full) || full.includes("/") || !/^\d+$/.test(key)
    )
      continue;
    result[key] = {
      name,
      imageUrl: `${DATA_DRAGON}/cdn/${encodeURIComponent(version)}/img/${kind}/${encodeURIComponent(full)}`,
    };
    if (kind === "spell") {
      if (typeof entry.description === "string")
        result[key].description = descriptionText(entry.description);
      if (
        Array.isArray(entry.cooldown) &&
        entry.cooldown.every((value) => price(value) !== null)
      )
        result[key].cooldown = entry.cooldown;
    }
    if (kind === "item" && entry.gold && typeof entry.gold === "object")
      result[key].gold = {
        base: price(entry.gold.base),
        total: price(entry.gold.total),
        sell: price(entry.gold.sell),
        purchasable:
          typeof entry.gold.purchasable === "boolean"
            ? entry.gold.purchasable
            : null,
      };
  }
  return result;
}

export async function resolveGameAssetCatalog(gameVersion: string, options: GameAssetOptions = {}): Promise<GameAssetCatalog> {
  patchPrefix(gameVersion);
  let assetVersion = "";
  try { assetVersion = newestMatchingVersion(await cachedJson(`${DATA_DRAGON}/api/versions.json`), gameVersion); }
  catch (error) { if (!options.includeRunes) throw error; }
  const runeAssets = options.includeRunes ? resolveRuneAssets(gameVersion, assetVersion) : null;
  const [championBody, itemBody, spellBody] = assetVersion ? await Promise.all(["champion", "item", "summoner"].map((kind) => cachedJson(`${DATA_DRAGON}/cdn/${assetVersion}/data/en_US/${kind}.json`).catch(() => null))) : [null, null, null];
  const partial = (body: unknown, kind: "champion" | "item" | "spell" | "profileicon") => { try { return assets(body, assetVersion, kind); } catch { return {}; } };
  const champions = partial(championBody, "champion"), items = partial(itemBody, "item"), spells = partial(spellBody, "spell");
  const result: GameAssetCatalog = { assetVersion, champions, items, spells, manifest: assetManifest(gameVersion, assetVersion, options.includeRunes), events: eventAssetCatalog(gameVersion, items) };
  if (runeAssets) Object.assign(result, await runeAssets);
  if (options.championIds?.length && championBody && typeof championBody === "object" && "data" in championBody && championBody.data && typeof championBody.data === "object") {
    const entries = Object.entries(championBody.data);
    const ids = [...new Set(options.championIds)].filter((id) => Number.isSafeInteger(id) && id > 0).slice(0, 10);
    result.abilities = Object.fromEntries(await Promise.all(ids.map(async (id) => {
      const entry = entries.find(([, value]) => value && typeof value === "object" && "key" in value && value.key === String(id));
      if (!entry || !/^[a-zA-Z0-9]+$/.test(entry[0])) return [String(id), {}];
      try { return [String(id), championAbilities(await cachedJson(`${DATA_DRAGON}/cdn/${assetVersion}/data/en_US/champion/${entry[0]}.json`), id, assetVersion)]; } catch { return [String(id), {}]; }
    })));
  }
  return result;
}

export async function resolveCurrentProfileAssetCatalog(): Promise<GameAssetCatalog> {
  const versions = await cachedJson(`${DATA_DRAGON}/api/versions.json`);
  if (!Array.isArray(versions)) throw new Error("INVALID_ASSET_VERSIONS");
  const valid = versions.filter((v): v is string => typeof v === "string" && DATA_DRAGON_VERSION.test(v)).sort((a,b) => { const x=numericVersion(a),y=numericVersion(b); return y[0]-x[0] || y[1]-x[1] || y[2]-x[2]; });
  if (!valid[0]) throw new Error("INVALID_ASSET_VERSIONS");
  const version = valid[0];
  let profileIcons: Record<string, GameAsset> = {};
  try { profileIcons = assets(await cachedJson(`${DATA_DRAGON}/cdn/${version}/data/en_US/profileicon.json`), version, "profileicon"); } catch { /* Preserve rank and manifest when profile art is unavailable. */ }
  const ranks = Object.fromEntries(["IRON", "BRONZE", "SILVER", "GOLD", "PLATINUM", "EMERALD", "DIAMOND", "MASTER", "GRANDMASTER", "CHALLENGER"].map((tier) => [tier, rankAsset(tier, CURRENT_PROFILE_ART_PATCH)!]));
  return { assetVersion: version, champions: {}, items: {}, spells: {}, profileIcons, ranks, manifest: { id: `profile-${version}-rank-${CURRENT_PROFILE_ART_PATCH}-v1`, scope: "current-profile", gameVersion: null, dataDragonVersion: version, communityDragonVersion: CURRENT_PROFILE_ART_PATCH, resolverVersion: "1", semanticDatasets: [], fallbackReason: null } };
}
