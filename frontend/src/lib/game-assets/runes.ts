import runeData17 from "./metadata/runes-16.17.1.json";
import styles17 from "./metadata/perkstyles-16.17.json";
import perks17 from "./metadata/perks-16.17.json";
import runeData18 from "./metadata/runes-16.18.1.json";
import styles18 from "./metadata/perkstyles-16.18.json";
import perks18 from "./metadata/perks-16.18.json";
import { reviewedAssetPatch } from "./manifest";
import type { GameAssetCatalog, RuneAsset, RuneTreeAsset, GameAsset } from "./types";
import { communityDragonGameDataUrl, DATA_DRAGON, descriptionText, runeDescription, safeAssetPath } from "./urls";

const metadataByPatch = { "16.17": { runeData: runeData17, styles: styles17, perks: perks17 }, "16.18": { runeData: runeData18, styles: styles18, perks: perks18 } };

export function runeCatalog(gameVersion: string): Pick<GameAssetCatalog, "runeTrees" | "runes" | "statShards" | "statShardSlots"> {
  const patch = reviewedAssetPatch(gameVersion);
  if (!patch) return {};
  const { runeData, styles, perks } = metadataByPatch[patch];
  const runeTrees: Record<string, RuneTreeAsset> = {};
  const runes: Record<string, RuneAsset> = {};
  const statShards: Record<string, GameAsset & { id: number }> = {};
  for (const tree of runeData) {
    if (!safeAssetPath(tree.icon)) continue;
    runeTrees[tree.id] = { id: tree.id, name: tree.name, imageUrl: `${DATA_DRAGON}/cdn/img/${tree.icon}`, slots: tree.slots.map((slot) => slot.runes.map((rune) => rune.id)) };
    tree.slots.forEach((row, slot) => row.runes.forEach((rune) => {
      if (safeAssetPath(rune.icon)) runes[rune.id] = { id: rune.id, treeId: tree.id, slot, name: rune.name, description: runeDescription(rune.longDesc), imageUrl: `${DATA_DRAGON}/cdn/img/${rune.icon}` };
    }));
  }
  const statShardSlots = styles.styles[0].slots.filter((slot) => slot.type === "kStatMod").map((slot) => slot.perks);
  const shardIds = new Set(statShardSlots.flat());
  for (const perk of perks) {
    if (!shardIds.has(perk.id)) continue;
    const imageUrl = communityDragonGameDataUrl(patch, perk.iconPath);
    if (imageUrl) statShards[perk.id] = { id: perk.id, name: perk.name, description: descriptionText(perk.longDesc), imageUrl };
  }
  return { runeTrees, runes, statShards, statShardSlots };
}
