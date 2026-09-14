import type { AssetManifest } from "./types";

export const RUNE_LAYOUT_MANIFEST_ID = "rune-layout-16.17-v1";
export const RUNE_METADATA_SOURCES = [
  { sourceUrl: "https://ddragon.leagueoflegends.com/cdn/16.17.1/data/en_US/runesReforged.json", sha256: "74d1e211982f4c976f2c76c1229f5c09b6eabdf2bf76b8f1bbd6d7248a60fc44", projectionSha256: "a0bc770a35a10aa063761e27d343de2e7e45127bc1ec321995a634fa37c78003", retrievedAt: "2026-09-14T18:38:00Z" },
  { sourceUrl: "https://raw.communitydragon.org/16.17/plugins/rcp-be-lol-game-data/global/default/v1/perkstyles.json", sha256: "aa6bebc26efebd49587530d2284e84202686ea4c67febcc2f8d8b29b7d91a81b", projectionSha256: "bfb9e5353f8ee8f7d6600c0b4ba996409472a17ec47b56c5c6f20a2553879b8e", retrievedAt: "2026-09-14T18:38:00Z" },
  { sourceUrl: "https://raw.communitydragon.org/16.17/plugins/rcp-be-lol-game-data/global/default/v1/perks.json", sha256: "dd0948288ac0293caf71675cabbd138a7ad512b7a7cbb15c084fa9662233c13a", projectionSha256: "001eb96fd1e0b14fb9830ef8c0c6798cc37daf69cdf372f728c13b7ff4053399", retrievedAt: "2026-09-14T18:38:00Z" },
];
export const RUNE_METADATA_16_18_SOURCES = [
  { sourceUrl: "https://ddragon.leagueoflegends.com/cdn/16.18.1/data/en_US/runesReforged.json", sha256: "74d1e211982f4c976f2c76c1229f5c09b6eabdf2bf76b8f1bbd6d7248a60fc44", projectionSha256: "a0bc770a35a10aa063761e27d343de2e7e45127bc1ec321995a634fa37c78003", retrievedAt: "2026-09-14T19:31:00Z" },
  { sourceUrl: "https://raw.communitydragon.org/16.18/plugins/rcp-be-lol-game-data/global/default/v1/perkstyles.json", sha256: "aa6bebc26efebd49587530d2284e84202686ea4c67febcc2f8d8b29b7d91a81b", projectionSha256: "bfb9e5353f8ee8f7d6600c0b4ba996409472a17ec47b56c5c6f20a2553879b8e", retrievedAt: "2026-09-14T19:31:00Z" },
  { sourceUrl: "https://raw.communitydragon.org/16.18/plugins/rcp-be-lol-game-data/global/default/v1/perks.json", sha256: "dd0948288ac0293caf71675cabbd138a7ad512b7a7cbb15c084fa9662233c13a", projectionSha256: "001eb96fd1e0b14fb9830ef8c0c6798cc37daf69cdf372f728c13b7ff4053399", retrievedAt: "2026-09-14T19:31:00Z" },
];
// 16.18 sources were retrieved independently and their exact bytes matched 16.17.
// The source/version identity remains separate even when both hashes are identical.
export const RUNE_METADATA_BY_PATCH = { "16.17": RUNE_METADATA_SOURCES, "16.18": RUNE_METADATA_16_18_SOURCES };
export const RUNE_LAYOUT_MANIFEST_IDS = { "16.17": RUNE_LAYOUT_MANIFEST_ID, "16.18": "rune-layout-16.18-v1" };
export const CURRENT_PROFILE_ART_PATCH = "16.18";
export function reviewedAssetPatch(gameVersion: string): "16.17" | "16.18" | null {
  if (!/^16\.(17|18)(?:\.\d+)*$/.test(gameVersion)) return null;
  return gameVersion.split(".").slice(0, 2).join(".") as "16.17" | "16.18";
}
export function assetManifest(gameVersion: string, assetVersion: string, includeRunes = false): AssetManifest {
  const patch = reviewedAssetPatch(gameVersion);
  return {
    id: includeRunes && patch ? RUNE_LAYOUT_MANIFEST_IDS[patch] : `artwork-${assetVersion}-v1`,
    scope: "match-patch", gameVersion, dataDragonVersion: assetVersion,
    communityDragonVersion: patch, resolverVersion: "1",
    semanticDatasets: includeRunes && patch ? RUNE_METADATA_BY_PATCH[patch] : [],
    fallbackReason: includeRunes && !patch ? "Rune layout metadata has not been reviewed for this match patch." : null,
  };
}
