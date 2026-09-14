import runePerformanceFixture from "./rune-performance.json";
import { parseRunePerformance } from "../../src/lib/game-assets/rune-performance";
import type { Page, Route } from "@playwright/test";
import path from "node:path";
import artManifest from "./art/manifest.json";
import { runeCatalog } from "../../src/lib/game-assets/runes";
import { assetManifest, CURRENT_PROFILE_ART_PATCH } from "../../src/lib/game-assets/manifest";
import { eventAssetCatalog } from "../../src/lib/game-assets/event-assets";
import type { GameAsset, GameAssetCatalog } from "../../src/lib/game-assets/types";

const fixedArt: Record<string, { file: string; name: string; sourceUrl: string; alternateSources?: { sourceUrl: string; sha256: string; retrievedAt: string }[] }> = artManifest.assets;
const artDirectory = path.resolve("e2e/support/art");

const imagePath = (kind: string, id: string) => `/__e2e-assets/${fixedArt[`${kind}-${id}`]?.file ?? `${kind}-${id}.svg`}`;
const asset = (kind: string, id: string, name: string) => ({ name, imageUrl: imagePath(kind, id) });

const champions = Object.fromEntries([
  ["61", "Orianna"], ["64", "Lee Sin"], ["86", "Garen"], ["103", "Ahri"], ["111", "Nautilus"],
  ["122", "Darius"], ["145", "Kai'Sa"], ["222", "Jinx"], ["254", "Vi"], ["412", "Thresh"],
].map(([id, name]) => [id, asset("champion", id, name)]));

const itemNames: Record<string, string> = {
  "1028": "Ruby Crystal", "1037": "Pickaxe", "1042": "Dagger", "1058": "Needlessly Large Rod",
  "2055": "Control Ward", "3006": "Berserker's Greaves", "3020": "Sorcerer's Shoes", "3031": "Infinity Edge",
  "3047": "Plated Steelcaps", "3051": "Hearthbound Axe", "3065": "Spirit Visage", "3071": "Black Cleaver",
  "3089": "Rabadon's Deathcap", "3109": "Knight's Vow", "3190": "Locket of the Iron Solari",
  "3363": "Farsight Alteration", "3340": "Stealth Ward", "3364": "Oracle Lens", "6630": "Goredrinker", "6631": "Stridebreaker",
  "6655": "Luden's Companion", "6657": "Rod of Ages", "6672": "Kraken Slayer",
};
const items = Object.fromEntries(Object.entries(itemNames).map(([id, name]) => [id, asset("item", id, name)]));
const spells = Object.fromEntries([
  ["4", "Flash"], ["7", "Heal"], ["11", "Smite"], ["12", "Teleport"], ["14", "Ignite"],
].map(([id, name]) => [id, asset("spell", id, name)]));

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32"><rect width="32" height="32" fill="#59645b"/><circle cx="16" cy="16" r="9" fill="#d8ddd6"/></svg>`;

function versionsFrom(route: Route): string[] {
  return (new URL(route.request().url()).searchParams.get("versions") ?? "")
    .split(",")
    .map((version) => version.trim())
    .filter(Boolean);
}

export function deterministicGameAssetCatalog(version: string): GameAssetCatalog {
  const runeAssets = runeCatalog(version);
  const assetVersion = /^16\.18(?:\.\d+)*$/.test(version) ? "16.18.1" : "16.17.2";
  for (const [kind, entries] of [["tree", runeAssets.runeTrees], ["rune", runeAssets.runes], ["shard", runeAssets.statShards]] as const) {
    for (const [id, entry] of Object.entries(entries ?? {})) entry.imageUrl = imagePath(kind, id);
  }
  const events = eventAssetCatalog(version, items);
  for (const [key, entry] of Object.entries(events)) {
    if (fixedArt[`event-${key}`]) entry.imageUrl = imagePath("event", key);
    else if (key === "TURRET_PLATE") entry.imageUrl = imagePath("event", "TOWER_BUILDING");
  }
  const abilities = { "86": Object.fromEntries(["Q", "W", "E", "R"].map((slot) => [slot, { name: fixedArt[`ability-86-${slot}`].name, imageUrl: imagePath("ability", `86-${slot}`) }])) };
  return { assetVersion, champions, items, spells, manifest: assetManifest(version, assetVersion, true), ...runeAssets, events, abilities, runePerformance: parseRunePerformance(runePerformanceFixture.entries), runePerformanceState: { status: "available", patch: version.split(".").slice(0, 2).join("."), sourceUrl: runePerformanceFixture.sources.find(source => source.patch === version.split(".").slice(0, 2).join("."))?.url ?? "", retrievedAt: "2026-09-14T18:38:00Z" } };
}

export async function installDeterministicGameAssets(page: Page): Promise<void> {
  const providerArt = (route: Route) => {
    const entry = Object.values(fixedArt).find((art) => (art.sourceUrl === route.request().url() || art.alternateSources?.some(source => source.sourceUrl === route.request().url())));
    return entry ? route.fulfill({ status: 200, path: path.join(artDirectory, entry.file), contentType: entry.file.endsWith(".svg") ? "image/svg+xml" : "image/png" }) : route.fulfill({ status: 404, body: "Artwork unavailable in this fixture" });
  };
  await page.route("https://ddragon.leagueoflegends.com/**", providerArt);
  await page.route("https://raw.communitydragon.org/**", providerArt);
  await page.route("**/__e2e-assets/**", (route) => {
    const file = new URL(route.request().url()).pathname.split("/").pop();
    if (file && Object.values(fixedArt).some((entry) => entry.file === file)) return route.fulfill({ status: 200, path: path.join(artDirectory, file), contentType: file.endsWith(".svg") ? "image/svg+xml" : "image/png" });
    return route.fulfill({ status: 200, contentType: "image/svg+xml", body: svg });
  });
  await page.route("**/api/game-assets?*", (route) => {
    if (new URL(route.request().url()).searchParams.get("scope") === "current-profile") {
      const profileIcons: Record<string, GameAsset> = { "29": { name: "Profile icon 29", imageUrl: imagePath("profile", "29") } };
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ assetVersion: "16.18.1", champions: {}, items: {}, spells: {}, profileIcons, ranks: { GOLD: { name: "Gold rank", imageUrl: imagePath("rank", "GOLD-16.18") }, EMERALD: { name: "Emerald rank", imageUrl: imagePath("rank", "EMERALD") } }, manifest: { id: `profile-16.18.1-rank-${CURRENT_PROFILE_ART_PATCH}-v1`, scope: "current-profile", gameVersion: null, dataDragonVersion: "16.18.1", communityDragonVersion: CURRENT_PROFILE_ART_PATCH, resolverVersion: "1", semanticDatasets: [], fallbackReason: null } }) });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(Object.fromEntries(versionsFrom(route).map((version) => [version, deterministicGameAssetCatalog(version)]))) });
  });
}

export async function installUnavailableGameAssets(page: Page): Promise<void> {
  await page.route("**/api/game-assets?*", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(Object.fromEntries(versionsFrom(route).map((version) => [version, null]))),
  }));
}
