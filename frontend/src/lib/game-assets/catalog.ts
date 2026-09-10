import "server-only";

import type { GameAsset, GameAssetCatalog } from "./types";

const DATA_DRAGON = "https://ddragon.leagueoflegends.com";
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

function descriptionText(value: string): string {
  const entities: Record<string, string> = {
    amp: "&",
    lt: "<",
    gt: ">",
    quot: '"',
    apos: "'",
    nbsp: " ",
  };
  return value
    .replace(/<[^>]*>/g, "")
    .replace(/&([a-z]+);/g, (match, key: string) => entities[key] ?? match)
    .trim();
}
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
  const response = await fetch(url, {
    next: { revalidate: 86_400 },
    signal: AbortSignal.timeout(2_500),
  });
  if (!response.ok) throw new Error("ASSET_REQUEST_FAILED");
  return response.json();
}

function assets(
  body: unknown,
  version: string,
  kind: "champion" | "item" | "spell",
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
    const key = kind === "item" ? id : entry.key;
    const name = entry.name;
    const full = entry.image?.full;
    if (
      typeof key !== "string" ||
      typeof name !== "string" ||
      typeof full !== "string"
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

export async function resolveGameAssetCatalog(
  gameVersion: string,
): Promise<GameAssetCatalog> {
  const versions = await cachedJson(`${DATA_DRAGON}/api/versions.json`);
  const assetVersion = newestMatchingVersion(versions, gameVersion);
  const [champions, items, spells] = await Promise.all([
    cachedJson(`${DATA_DRAGON}/cdn/${assetVersion}/data/en_US/champion.json`),
    cachedJson(`${DATA_DRAGON}/cdn/${assetVersion}/data/en_US/item.json`),
    cachedJson(`${DATA_DRAGON}/cdn/${assetVersion}/data/en_US/summoner.json`),
  ]);
  return {
    assetVersion,
    champions: assets(champions, assetVersion, "champion"),
    items: assets(items, assetVersion, "item"),
    spells: assets(spells, assetVersion, "spell"),
  };
}
