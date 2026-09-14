import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "./support/game-assets";
import { parsePlayerProfile } from "../src/lib/player-lookup/profile";
import { profileFixture } from "../src/lib/player-lookup/profile.test-fixture";

test("cached profile shows current record and discrete observations without refreshing Riot", async ({ page }) => {
  await installDeterministicGameAssets(page);
  const runId = "00000000-0000-0000-0000-000000000099";
  const identity = { gameName: "Invented Player", tagLine: "DEMO" };
  const errors: string[] = [], mutations: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("request", request => { if (request.method() === "POST") mutations.push(request.url()); });
  await page.route(`**/api/player-matches/${runId}`, route => route.fulfill({ json: {
    runId, ...identity, status: "COMPLETE", message: null, retryNotBefore: null, queueId: 0,
    lastUpdated: "2026-09-14T10:00:00Z", nextRefreshAt: "2099-09-14T10:15:00Z", previousRunId: null, hasMore: false,
    matches: [{ matchId: "NA1_7000000001", participantId: 6, championId: 86, championName: "Garen", gameVersion: "16.17.1", endItemIds: [], position: "TOP", win: true, startedAtMs: 1788890400000, durationSeconds: 1800, kills: 8, deaths: 4, assists: 7, cs: 190, gold: 14200, timelineAvailable: true, queueId: 420 }],
  } }));
  let profile = parsePlayerProfile(structuredClone(profileFixture));
  await page.route(`**/api/player-matches/${runId}/profile`, route => route.fulfill({ json: profile }));
  await page.goto(`/search?runId=${runId}`);
  const panel = page.getByRole("region", { name: "Searched player profile" });
  await expect(panel.getByText("57.1% · 80W–60L", { exact: true })).toBeVisible();
  await expect(panel.getByText(/8 games with recorded outcomes loaded · 5W–3L/)).toBeVisible();
  await expect(panel.getByText("Level 123", { exact: true })).toBeVisible();
  await panel.getByText("View rank observations", { exact: false }).click();
  await expect(panel.getByText(/Tracking since/)).toBeVisible();
  await panel.screenshot({ path: "test-results/profile-desktop.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await panel.screenshot({ path: "test-results/profile-mobile.png" });
  profile = { ...profile, summoner: { ...profile.summoner, stale: true, error: "RATE_LIMITED" }, soloRank: { ...profile.soloRank, stale: true, error: "RATE_LIMITED" } };
  await page.reload();
  await expect(panel.getByText(/May be out of date/)).toHaveCount(2);
  await expect(panel.getByText("57.1% · 80W–60L", { exact: true })).toBeVisible();
  await panel.screenshot({ path: "test-results/profile-stale-mobile.png" });
  expect(mutations).toEqual([]);
  expect(errors).toEqual([]);
});
