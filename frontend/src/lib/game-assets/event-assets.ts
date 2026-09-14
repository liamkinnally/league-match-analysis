import type { AbilitySlot, GameAsset, GameAssetCatalog } from "./types";
import { COMMUNITY_DRAGON } from "./urls";
import { reviewedAssetPatch } from "./manifest";

// Reviewed against the 16.17 and 16.18 provider minimap listing; names never construct paths.
const OBJECTS: Record<string, [string, string]> = {
  TOWER_BUILDING: ["Turret", "icon_ui_tower_minimap.png"],
  INHIBITOR_BUILDING: ["Inhibitor", "icon_ui_inhibitor_minimap_v2.png"],
  NEXUS_BUILDING: ["Nexus", "icon_ui_nexus_minimap_v2.png"],
  TURRET_PLATE: ["Turret plate", "icon_ui_tower_minimap.png"],
  BARON_NASHOR: ["Baron Nashor", "baron.png"], RIFTHERALD: ["Rift Herald", "riftherald.png"], HORDE: ["Void Grub", "grub.png"],
  DRAGON: ["Dragon", "dragon.png"],
  AIR_DRAGON: ["Cloud Dragon", "dragon_cloud.png"], FIRE_DRAGON: ["Infernal Dragon", "dragon_infernal.png"],
  EARTH_DRAGON: ["Mountain Dragon", "dragon_mountain.png"], WATER_DRAGON: ["Ocean Dragon", "dragon_ocean.png"],
  HEXTECH_DRAGON: ["Hextech Dragon", "dragon_hextech.png"], CHEMTECH_DRAGON: ["Chemtech Dragon", "dragon_chemtech.png"], ELDER_DRAGON: ["Elder Dragon", "dragon_elder.png"],
};
export function eventAssetCatalog(gameVersion: string, items: Record<string, GameAsset> = {}): Record<string, GameAsset> {
  const patch = reviewedAssetPatch(gameVersion);
  if (!patch) return {};
  const result = Object.fromEntries(Object.entries(OBJECTS).map(([key, [name, file]]) => [key, { name, imageUrl: `${COMMUNITY_DRAGON}/${patch}/game/assets/ux/minimap/icons/${file}` }]));
  result.WARD_EYE = { name: "Ward", imageUrl: `${COMMUNITY_DRAGON}/${patch}/plugins/rcp-fe-lol-postgame/global/default/scoreboard-stat-switcher-eye.svg` };
  for (const [key, id] of Object.entries({ WARD_YELLOW: "3340", WARD_SIGHT: "3340", WARD_BLUE: "3363", WARD_CONTROL: "2055" })) {
    if (items[id]) result[key] = items[id];
  }
  return result;
}
export function resolveEventAsset(catalog: GameAssetCatalog | null | undefined, key: string): GameAsset | undefined { return catalog?.events?.[key]; }
export function resolveAbilityAsset(catalog: GameAssetCatalog | null | undefined, championId: number, slot: AbilitySlot | number): GameAsset | undefined {
  const letter = typeof slot === "number" ? (["Q", "W", "E", "R"] as const)[slot - 1] : slot;
  return letter ? catalog?.abilities?.[String(championId)]?.[letter] : undefined;
}
export function rankAsset(tier: string, version: string): GameAsset | undefined {
  if (!/^\d+\.\d+(?:\.\d+)?$/.test(version) || !["IRON", "BRONZE", "SILVER", "GOLD", "PLATINUM", "EMERALD", "DIAMOND", "MASTER", "GRANDMASTER", "CHALLENGER"].includes(tier.toUpperCase())) return undefined;
  const patch = version.split(".").slice(0, 2).join(".");
  // Only the inspected asset version is approved; current profile manifests record it independently of matches.
  if (!reviewedAssetPatch(patch)) return undefined;
  return { name: `${tier} rank`, imageUrl: `${COMMUNITY_DRAGON}/${patch}/plugins/rcp-fe-lol-shared-components/global/default/images/${tier.toLowerCase()}.png` };
}
