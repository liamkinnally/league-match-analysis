import "server-only";
import { createHash } from "node:crypto";
import type { GameAssetCatalog, RuneAsset, RuneTreeAsset, GameAsset, RunePerformanceState } from "./types";
import { readAssetSource, AssetSourceUnavailable, type AssetSource } from "./source";
import { runeCatalog } from "./runes";
import { parseRunePerformance } from "./rune-performance";
import { assetManifest } from "./manifest";
import { DATA_DRAGON, COMMUNITY_DRAGON, safeAssetPath, communityDragonGameDataUrl, descriptionText, runeDescription } from "./urls";

type RecordValue = Record<string, unknown>;
const object = (value: unknown): value is RecordValue => !!value && typeof value === "object" && !Array.isArray(value);
const positiveId = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value > 0;
const validPerks = (value: unknown) => Array.isArray(value) && value.length > 0 && value.length <= 5000 && value.some(row => object(row) && positiveId(row.id) && typeof row.name === "string" && Array.isArray(row.endOfGameStatDescs));
const validTrees = (value: unknown) => {
  if (!Array.isArray(value) || !value.length || value.length > 20) return false;
  const ids = new Set<number>();
  return value.every(tree => object(tree) && positiveId(tree.id) && typeof tree.name === "string" && tree.name.length <= 128 && typeof tree.icon === "string" && safeAssetPath(tree.icon) && Array.isArray(tree.slots) && tree.slots.length > 0 && tree.slots.length <= 12 && tree.slots.every(row => object(row) && Array.isArray(row.runes) && row.runes.length > 0 && row.runes.length <= 20 && row.runes.every(rune => {
    if (!object(rune) || !positiveId(rune.id) || ids.has(rune.id) || typeof rune.name !== "string" || rune.name.length > 128 || typeof rune.icon !== "string" || !safeAssetPath(rune.icon)) return false;
    ids.add(rune.id); return true;
  })));
};
const validStyles = (value: unknown) => object(value) && Array.isArray(value.styles) && value.styles.length > 0 && value.styles.length <= 20 && value.styles.every(row => object(row) && positiveId(row.id) && Array.isArray(row.slots) && row.slots.length <= 12);
const sourceEntry = (source: AssetSource) => ({ sourceUrl: source.sourceUrl, sha256: source.sha256, retrievedAt: source.retrievedAt });

function parseLayout(dd: unknown, stylesBody: unknown, perksBody: unknown, patch: string) {
  const runeTrees: Record<string, RuneTreeAsset> = {}, runes: Record<string, RuneAsset> = {}, statShards: Record<string, GameAsset & { id: number }> = {};
  if (Array.isArray(perksBody)) for (const perk of perksBody) {
    if (!object(perk) || !positiveId(perk.id) || typeof perk.name !== "string" || perk.name.length > 128 || typeof perk.iconPath !== "string") continue;
    const imageUrl = communityDragonGameDataUrl(patch, perk.iconPath);
    if (imageUrl) runes[perk.id] = { id: perk.id, name: descriptionText(perk.name), imageUrl, treeId: null, slot: null, description: runeDescription(perk.longDesc) };
  }
  if (Array.isArray(dd)) for (const tree of dd) {
    if (!object(tree) || !positiveId(tree.id) || typeof tree.name !== "string" || typeof tree.icon !== "string" || !safeAssetPath(tree.icon) || !Array.isArray(tree.slots)) continue;
    const slots: number[][] = [];
    for (const [slot, row] of tree.slots.entries()) {
      if (!object(row) || !Array.isArray(row.runes) || row.runes.length > 20) continue;
      const ids: number[] = [];
      for (const rune of row.runes) {
        if (!object(rune) || !positiveId(rune.id) || typeof rune.name !== "string" || typeof rune.icon !== "string" || !safeAssetPath(rune.icon)) continue;
        ids.push(rune.id); runes[rune.id] = { id: rune.id, name: descriptionText(rune.name), imageUrl: `${DATA_DRAGON}/cdn/img/${rune.icon}`, treeId: tree.id, slot, description: runeDescription(rune.longDesc) ?? runes[rune.id]?.description };
      }
      slots.push(ids);
    }
    runeTrees[tree.id] = { id: tree.id, name: descriptionText(tree.name), imageUrl: `${DATA_DRAGON}/cdn/img/${tree.icon}`, slots };
  }
  let statShardSlots: number[][] | undefined;
  if (object(stylesBody) && Array.isArray(stylesBody.styles)) for (const style of stylesBody.styles) {
    if (!object(style) || !positiveId(style.id) || !Array.isArray(style.slots)) continue;
    const rows = style.slots.filter(object);
    const shardRows = rows.filter(row => row.type === "kStatMod").map(row => Array.isArray(row.perks) ? row.perks.filter(positiveId) : []);
    if (shardRows.length && shardRows.every(row => row.length > 0 && row.length <= 20)) {
      if (statShardSlots && JSON.stringify(statShardSlots) !== JSON.stringify(shardRows)) { statShardSlots = undefined; break; }
      statShardSlots = shardRows;
    }
    // CommunityDragon supplies rows only when Data Dragon did not establish this tree.
    if (!runeTrees[style.id] && typeof style.name === "string" && typeof style.iconPath === "string") {
      const imageUrl = communityDragonGameDataUrl(patch, style.iconPath);
      if (imageUrl) {
        const slots = rows.filter(row => row.type !== "kStatMod").map(row => Array.isArray(row.perks) ? row.perks.filter(positiveId) : []);
        runeTrees[style.id] = { id: style.id, name: descriptionText(style.name), imageUrl, slots };
        slots.forEach((ids, slot) => ids.forEach(id => { if (runes[id]) runes[id] = { ...runes[id], treeId: style.id as number, slot }; }));
      }
    }
  }
  for (const id of statShardSlots?.flat() ?? []) {
    const entry = runes[id];
    if (entry) { statShards[id] = { id, name: entry.name, imageUrl: entry.imageUrl, description: entry.description }; delete runes[id]; }
  }
  return { runeTrees, runes, statShards, statShardSlots };
}

export async function resolveRuneAssets(gameVersion: string, assetVersion: string): Promise<Partial<GameAssetCatalog>> {
  if (!/^\d+\.\d+(?:\.\d+)*$/.test(gameVersion)) return {};
  const patch = gameVersion.split(".").slice(0, 2).join(".");
  const root = `${COMMUNITY_DRAGON}/${patch}/plugins/rcp-be-lol-game-data/global/default/v1`;
  const [ddResult, stylesResult, perksResult] = await Promise.allSettled([
    assetVersion ? readAssetSource(`${DATA_DRAGON}/cdn/${assetVersion}/data/en_US/runesReforged.json`, validTrees) : Promise.reject(new Error("MATCHING_ASSET_VERSION_NOT_FOUND")),
    readAssetSource(`${root}/perkstyles.json`, validStyles),
    readAssetSource(`${root}/perks.json`, validPerks),
  ]);
  const dd = ddResult.status === "fulfilled" ? ddResult.value : null, styles = stylesResult.status === "fulfilled" ? stylesResult.value : null, perks = perksResult.status === "fulfilled" ? perksResult.value : null;
  const fallback = runeCatalog(gameVersion), dynamic = parseLayout(dd?.body, styles?.body, perks?.body, patch);
  const treeFallbackUsed = !Object.keys(dynamic.runeTrees).length && Boolean(fallback.runeTrees);
  const runes = treeFallbackUsed ? { ...fallback.runes } : {};
  for (const [id, entry] of Object.entries(dynamic.runes)) {
    const pinnedRune = treeFallbackUsed ? fallback.runes?.[id] : undefined;
    runes[id] = { ...entry, treeId: entry.treeId ?? pinnedRune?.treeId ?? null, slot: entry.slot ?? pinnedRune?.slot ?? null };
  }
  const statShardSlots = dynamic.statShardSlots ?? (!styles ? fallback.statShardSlots : undefined);
  const statShards = { ...dynamic.statShards };
  let shardFallbackUsed = !dynamic.statShardSlots && Boolean(statShardSlots);
  for (const id of statShardSlots?.flat() ?? []) {
    if (!statShards[id] && (!styles || !perks) && fallback.statShards?.[id]) { statShards[id] = fallback.statShards[id]; shardFallbackUsed = true; }
  }
  const layout = { runeTrees: Object.keys(dynamic.runeTrees).length ? dynamic.runeTrees : fallback.runeTrees, runes, statShards, statShardSlots };
  const sourceUrl = `${root}/perks.json`;
  const state: RunePerformanceState = perks ? { status: perks.status, patch, sourceUrl, sha256: perks.sha256, retrievedAt: perks.retrievedAt, ...(perks.retryAt ? { retryAt: perks.retryAt, reason: "Using the last successful metadata for this patch while the source is unavailable." } : {}) } : { status: "unavailable", patch, sourceUrl, ...(perksResult.status === "rejected" && perksResult.reason instanceof AssetSourceUnavailable ? { retryAt: perksResult.reason.retryAt } : {}), reason: "Performance metadata for this match patch is unavailable; it will be retried." };
  const sources = [dd, styles, perks].filter((value): value is AssetSource => value !== null);
  const pinned = assetManifest(gameVersion, assetVersion, true);
  const fallbackUsed = treeFallbackUsed || shardFallbackUsed;
  const semanticDatasets = [...(fallbackUsed ? pinned.semanticDatasets : []), ...sources.map(sourceEntry)];
  const signature = createHash("sha256").update(semanticDatasets.map(source => source.sha256).join(":")).digest("hex").slice(0, 12);
  return { ...layout, runePerformance: perks ? parseRunePerformance(perks.body) : {}, runePerformanceState: state,
    manifest: { ...pinned, id: `rune-layout-${patch}-v1`, communityDragonVersion: patch, resolverVersion: `2-${signature}`, semanticDatasets,
      fallbackReason: [fallbackUsed ? "Using the shipped metadata snapshot for this same patch where layout metadata is unavailable." : null, sources.some(source => source.status === "stale") ? "Some metadata uses the last successful snapshot for this same patch." : null, !assetVersion ? "Matching Data Dragon artwork is unavailable." : null, state.status !== "available" ? state.reason : null].filter(Boolean).join(" ") || null },
  };
}
