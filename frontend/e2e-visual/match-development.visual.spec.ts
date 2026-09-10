import { expect, test, type Page } from "@playwright/test";

const solidImage = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='32' height='32'%3E%3Crect width='32' height='32' fill='%2357655b'/%3E%3C/svg%3E";
const asset = (name: string) => ({ name, imageUrl: solidImage });
const assetCatalog = {
  assetVersion: "16.17.1",
  champions: {
    "64": asset("Lee Sin"), "86": asset("Garen"), "122": asset("Darius"),
    "136": asset("Aurelion Sol"), "254": asset("Vi"),
  },
  items: Object.fromEntries([
    ["3006", "Berserker's Greaves"], ["3047", "Plated Steelcaps"], ["3053", "Sterak's Gage"],
    ["3071", "Black Cleaver"], ["3078", "Trinity Force"], ["3089", "Rabadon's Deathcap"],
    ["3111", "Mercury's Treads"], ["3118", "Malignance"], ["3340", "Stealth Ward"],
    ["3363", "Farsight Alteration"], ["3364", "Oracle Lens"], ["6630", "Goredrinker"],
    ["6655", "Luden's Companion — long item label"],
  ].map(([id, name]) => [id, asset(name)])),
  spells: { "4": asset("Flash"), "11": asset("Smite"), "12": asset("Teleport") },
};

async function openStableDevelopment(page: Page, route: string, waitForImage = true): Promise<string[]> {
  const errors: string[] = [];
  page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route("**/api/game-assets?*", (request) => request.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ "16.17.1": assetCatalog }),
  }));
  await page.goto(route);
  await page.waitForLoadState("networkidle");
  await page.evaluate(async () => { await document.fonts.ready; });
  if (waitForImage) await expect(page.getByRole("img").first()).toBeVisible();
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  const clippedLabels = await page.locator('.graph-boundary-label').evaluateAll(labels => labels.filter(label => {
    const box = label.getBoundingClientRect(), chart = label.closest('svg')!.getBoundingClientRect();
    return box.left < chart.left || box.right > chart.right;
  }).map(label => label.textContent));
  expect(clippedLabels).toEqual([]);
  return errors;
}

test("sample development wide", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const errors = await openStableDevelopment(page, "/matches/__lab_development/development?focus=6&compare=1");
  await expect(page.getByText("Show all 15 sampled values")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Scoreboard" })).toBeVisible();
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("sample-development-wide.png", { animations: "disabled", fullPage: true });
});

test("sample development narrow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const errors = await openStableDevelopment(page, "/matches/__lab_development/development?focus=6&compare=1");
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBe(0);
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("sample-development-narrow.png", { animations: "disabled", fullPage: true });
});

test("development sparse", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const errors = await openStableDevelopment(page, "/matches/__lab_development_sparse/development?focus=6&compare=1");
  await expect(page.getByText("No suggested windows met the gold-change threshold.")).toBeVisible();
  await expect(page.getByText("Show all 3 sampled values")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Compare with opponent" })).toContainText("Aurelion Sol — JUNGLE");
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("development-sparse.png", { animations: "disabled", fullPage: true });
});

test("development long labels", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const errors = await openStableDevelopment(page, "/matches/__lab_development_long_labels/development?focus=6&compare=1");
  await expect(page.getByRole("combobox", { name: "Compare with opponent" })).toContainText("Aurelion Sol — JUNGLE");
  await page.locator(".refined-events > summary").click();
  await expect(page.getByText("Purchased Luden's Companion — long item label", { exact: true })).toBeVisible();
  const points = page.locator(".recharts-area-dots circle");
  await expect(points).toHaveCount(2);
  const xs=await points.evaluateAll(elements=>elements.map(point=>Number(point.getAttribute("cx"))));
  expect(xs[0]).toBeGreaterThanOrEqual(0); expect(xs[1]).toBeGreaterThan(xs[0]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBe(0);
  expect(errors).toEqual([]);
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(page).toHaveScreenshot("development-long-labels-narrow.png", { animations: "disabled", fullPage: true });
});

test("player lookup", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const runId = "00000000-0000-0000-0000-000000000005";
  await page.route(`**/api/player-matches/${runId}`, (request) => request.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ runId, gameName: "AveryLongRecruiterLookupName", tagLine: "NA1", status: "COMPLETE", message: null, retryNotBefore: null, matches: [{ matchId: "NA1_7000000002", participantId: 6, championName: "Garen", championId: 86, gameVersion: "16.17.1", endItemIds: [6655, 3047, 3071, 3053, 0, 0, 3340], position: "TOP", win: true, startedAtMs: 1788890400000, durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: true }] }),
  }));
  const errors = await openStableDevelopment(page, `/search?runId=${runId}`);
  await expect(page.getByTitle("Luden's Companion — long item label")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBe(0);
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("player-lookup-narrow.png", { animations: "disabled", fullPage: true });
});

test("failed lookup", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const runId = "00000000-0000-0000-0000-000000000006";
  await page.route(`**/api/player-matches/${runId}`, (request) => request.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ runId, gameName: "Unavailable", tagLine: "NA1", status: "FAILED", message: "Lookup could not finish. Search again or explore the sample match.", retryNotBefore: null, matches: [] }),
  }));
  const errors = await openStableDevelopment(page, `/search?runId=${runId}`, false);
  await expect(page.getByText("Lookup could not finish. Search again or explore the sample match.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Find matches" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBe(0);
  const height = await page.evaluate(() => ({
    document: document.documentElement.scrollHeight,
    viewport: document.documentElement.clientHeight,
  }));
  expect(height.document).toBeLessThanOrEqual(height.viewport);
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("failed-lookup-narrow.png", { animations: "disabled" });
});

test("home narrow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const errors = await openStableDevelopment(page, "/", false);
  await expect(page.getByRole("heading", { name: "Find a player" })).toBeVisible();
  await expect(page.getByRole("link", { name: "match-analysis-v1" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("home-narrow.png", { animations: "disabled", fullPage: true });
});

test("history loading narrow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const runId = "00000000-0000-0000-0000-000000000007";
  await page.route(`**/api/player-matches/${runId}`, route => route.fulfill({
    contentType: "application/json", body: JSON.stringify({ runId, gameName: "Player", tagLine: "NA1", status: "RUNNING", message: null, retryNotBefore: null, matches: [] }),
  }));
  const errors = await openStableDevelopment(page, `/search?runId=${runId}`, false);
  await expect(page.getByRole("region", { name: "Player lookup" }).getByRole("status")).toContainText("Fetching recent matches");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("history-loading-narrow.png", { animations: "disabled", fullPage: true });
});
