import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "./support/game-assets";
import { parsePlayerProfile } from "../src/lib/player-lookup/profile";
import { profileFixture } from "../src/lib/player-lookup/profile.test-fixture";
import type { MatchSummary } from "../src/lib/player-lookup/types";

const runId = "00000000-0000-0000-0000-000000000099";
const identity = { gameName: "Invented Player", tagLine: "DEMO" };
const match: MatchSummary = {
  matchId: "NA1_7000000001", participantId: 6, championId: 86, championName: "Garen",
  gameVersion: "16.17.1", endItemIds: [3071, 3047, 0, 0, 0, 0, 3340], position: "TOP",
  win: true, remake: false, startedAtMs: 1788890400000, durationSeconds: 1800,
  kills: 8, deaths: 4, assists: 7, cs: 190, gold: 14200, timelineAvailable: true, queueId: 420,
};

test("cached player profile summarizes counted games and keeps all history rows usable on desktop and mobile", async ({ page }) => {
  await installDeterministicGameAssets(page);
  const errors: string[] = [], mutations: string[] = [];
  const refreshAvailableAt = new Date(Date.now() + 15 * 60000).toISOString();
  page.on("pageerror", error => errors.push(error.message));
  page.on("request", request => { if (request.method() === "POST") mutations.push(request.url()); });
  let matches: MatchSummary[] = [
    match,
    { ...match, matchId: "NA1_7000000002", championId: 103, championName: "Ahri", position: "MIDDLE", win: false },
    { ...match, matchId: "NA1_7000000003", championId: 122, championName: "Darius", win: false, remake: true, durationSeconds: 125 },
    { ...match, matchId: "NA1_7000000004", championId: 61, championName: "Orianna", position: "MIDDLE", remake: null },
  ];
  await page.route(`**/api/player-matches/${runId}`, route => route.fulfill({ json: {
    runId, ...identity, status: matches.length ? "COMPLETE" : "EMPTY", message: null, retryNotBefore: null, queueId: 0,
    lastUpdated: "2026-09-14T10:00:00Z", nextRefreshAt: refreshAvailableAt, previousRunId: null, hasMore: false, matches,
  } }));
  let profile = parsePlayerProfile(structuredClone(profileFixture));
  await page.route(`**/api/player-matches/${runId}/profile`, route => route.fulfill({ json: profile }));
  await page.goto(`/search?runId=${runId}`);

  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Invented Player#DEMO");
  await expect(page.getByRole("heading", { name: "Player search", exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Game name")).toBeVisible();
  await expect(page.getByLabel("Level 123")).toBeVisible();
  await expect(page.getByRole("button", { name: "Update", exact: true })).toBeDisabled();
  const solo = page.getByRole("region", { name: "Ranked Solo/Duo", exact: true });
  await expect(solo.getByRole("img", { name: "Emerald rank" })).toBeVisible();
  await expect(solo.getByText("Emerald II", { exact: true })).toBeVisible();
  await expect(solo.getByText("80W – 60L", { exact: true })).toBeVisible();
  await expect(solo.getByText("57.1%", { exact: false })).toBeVisible();
  await expect(solo.getByText("About this record", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Ranked Flex", exact: true }).locator("summary")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Champion performance", exact: true }).getByText("Recent history")).toBeVisible();
  await expect.poll(() => page.evaluate(() => {
    const badge = document.querySelector(".profile-header__level")!.getBoundingClientRect();
    const updated = document.querySelector(".profile-header__actions small")!.getBoundingClientRect();
    return Math.abs(badge.bottom - updated.bottom);
  })).toBeLessThan(1);
  await solo.getByText("View rank observations", { exact: false }).click();
  await expect(solo.getByRole("list", { name: "Observed Solo/Duo ranks" }).getByText("Emerald II · 42 LP")).toBeVisible();

  const performance = page.getByRole("region", { name: "Recent performance", exact: true });
  await expect(performance.getByText("2 games in this history")).toBeVisible();
  await expect(performance.getByText("50%", { exact: true })).toBeVisible();
  await expect(performance.getByText("1W", { exact: true })).toBeVisible();
  await expect(performance.getByText("1L", { exact: true })).toBeVisible();
  await expect(performance.getByText("1 remake excluded. 1 result unavailable.")).toBeVisible();
  await expect(page.getByText(/eligibility|reporting period|games.*loaded/i)).toHaveCount(0);
  const history = page.getByRole("list", { name: "Recent match history" });
  await expect(history.getByRole("listitem")).toHaveCount(4);
  for (const name of ["Garen victory", "Ahri defeat", "Darius remake", "Orianna victory"]) {
    await expect(history.getByRole("link", { name: `${name} match development` })).toBeVisible();
  }
  await expect(history.getByRole("link", { name: "Darius remake match development" })).toHaveAttribute("href", `/matches/NA1_7000000003/development?focus=6&historyRunId=${runId}`);
  await page.screenshot({ path: "test-results/profile-desktop.png", fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await expect(page.getByLabel("Queue Type")).toBeEnabled();
  await expect(history.getByRole("link", { name: "Garen victory match development" })).toBeVisible();
  await page.screenshot({ path: "test-results/profile-mobile.png", fullPage: true });

  const originalMatches = matches, originalProfile = profile;
  matches = [
    { ...match, kills: 2, deaths: 6, assists: 4 },
    { ...match, matchId: "NA1_7000000005", championId: 103, championName: "Ahri", win: false, kills: 2, deaths: 6, assists: 4 },
    { ...match, matchId: "NA1_7000000006", championId: 103, championName: "Ahri", win: false, kills: 2, deaths: 6, assists: 4 },
  ];
  profile = { ...profile, soloRank: { ...profile.soloRank, wins: 40, losses: 60, winRate: 40 } };
  await page.reload();
  await expect(performance.getByText("33%", { exact: true })).toBeVisible();
  await expect(performance.getByText("1.00:1", { exact: true })).toBeVisible();
  await expect(solo.locator(".profile-rank__rate")).toHaveCSS("color", "rgb(227, 151, 160)");
  await expect(performance.locator(".player-performance__ring-wins")).toHaveCSS("stroke", "rgb(227, 151, 160)");
  await expect(performance.getByText("33%", { exact: true })).toHaveCSS("color", "rgb(227, 151, 160)");
  await expect(performance.getByText("0%", { exact: true })).toHaveCSS("color", "rgb(227, 151, 160)");
  await expect(performance.getByText("100%", { exact: true })).toHaveCSS("color", "rgb(163, 191, 255)");
  await expect(performance.getByText("1.00:1", { exact: true })).toHaveCSS("color", "rgb(228, 228, 223)");
  await page.screenshot({ path: "test-results/profile-losing-mobile.png", fullPage: true });
  matches = originalMatches;
  profile = originalProfile;

  profile = { ...profile, summoner: { ...profile.summoner, stale: true, error: "RATE_LIMITED" }, soloRank: { ...profile.soloRank, stale: true, error: "RATE_LIMITED" } };
  await page.reload();
  await expect(page.getByText("Profile may be out of date", { exact: true })).toBeVisible();
  await expect(solo.getByText("Update unavailable · Showing saved rank", { exact: true })).toBeVisible();
  await expect(solo.getByText("80W – 60L", { exact: true })).toBeVisible();
  await expect(performance.getByText("2 games in this history")).toBeVisible();
  await page.screenshot({ path: "test-results/profile-stale-mobile.png", fullPage: true });

  matches = [];
  profile = { ...profile,
    summoner: { ...profile.summoner, status: "unavailable", profileIconId: null, summonerLevel: null, stale: false, error: null },
    soloRank: { ...profile.soloRank, status: "unavailable", tier: null, division: null, leaguePoints: null,
      wins: null, losses: null, winRate: null, stale: false, error: null, fetchedAt: null },
    rankHistory: { trackingSince: null, observations: [], nextCursor: null },
  };
  await page.reload();
  await expect(solo.getByText("Rank unavailable", { exact: true })).toBeVisible();
  await expect(solo.getByText("Unranked", { exact: true })).toHaveCount(0);
  await expect(performance.getByText("No completed results to summarize yet.")).toBeVisible();
  await expect(performance.getByText("0%", { exact: true })).toHaveCount(0);
  await expect(page.getByText("No recent matches", { exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: "test-results/profile-unavailable-mobile.png", fullPage: true });
  expect(mutations).toEqual([]);
  expect(errors).toEqual([]);
});
