import type { Page, Route } from "@playwright/test";

const imagePath = (kind: string, id: string) => `/__e2e-assets/${kind}-${id}.svg`;
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
  "3340": "Stealth Ward", "3364": "Oracle Lens", "6630": "Goredrinker", "6631": "Stridebreaker",
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

export async function installDeterministicGameAssets(page: Page): Promise<void> {
  await page.route("**/__e2e-assets/**", (route) => route.fulfill({ status: 200, contentType: "image/svg+xml", body: svg }));
  await page.route("**/api/game-assets?*", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(Object.fromEntries(versionsFrom(route).map((version) => [version, {
      assetVersion: "16.17.2", champions, items, spells,
    }]))),
  }));
}

export async function installUnavailableGameAssets(page: Page): Promise<void> {
  await page.route("**/api/game-assets?*", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(Object.fromEntries(versionsFrom(route).map((version) => [version, null]))),
  }));
}
