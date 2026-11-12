import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "./support/game-assets";
import { profileFixture } from "../src/lib/player-lookup/profile.test-fixture";

test("a fresh lookup persists the match and exposes only regional public suggestions", async ({ page, request }) => {
  await installDeterministicGameAssets(page);
  const id = `7${Date.now()}`;
  const matchId = `NA1_${id}`;
  const before = await request.get(`http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? "8080"}/api/v1/matches/${matchId}/development?focus=6`);
  expect(before.status()).toBe(404);
  await page.goto("/");
  await page.getByRole("navigation", { name: "Product navigation" }).getByRole("link", { name: "Match history", exact: true }).click();
  await expect(page).toHaveURL(/\/search$/);
  const search = page.locator(".player-search");
  const emptySearch = await search.boundingBox();
  expect(emptySearch).not.toBeNull();
  expect(Math.abs(emptySearch!.x + emptySearch!.width / 2 - page.viewportSize()!.width / 2)).toBeLessThan(2);
  await expect(page.getByRole("complementary", { name: "About this site" })).toContainText("LoL Match Analysis");
  await expect(page.getByText("Prototype", { exact: true })).toHaveCount(0);
  await expect(page.getByText(/All supported queues/)).toHaveCount(0);
  await page.getByLabel("Riot ID").fill(`Lookup${id}#NA1`);
  await page.getByRole("button", { name: "Find matches" }).click();
  await expect(page).toHaveURL(new RegExp(`/summoners/na/Lookup${id}-NA1$`));
  const row = page.getByRole("link", { name: "Garen victory match development" });
  await expect(row).toBeVisible({ timeout: 20000 });
  const runId = new URL((await row.getAttribute("href"))!, page.url()).searchParams.get("historyRunId");
  const populatedSearch = await search.boundingBox();
  const profile = await page.locator(".profile-header").boundingBox();
  expect(populatedSearch).not.toBeNull();
  expect(profile).not.toBeNull();
  expect(Math.abs(populatedSearch!.x + populatedSearch!.width - profile!.x - profile!.width)).toBeLessThan(2);
  expect(populatedSearch!.x).toBeGreaterThan(emptySearch!.x);
  await expect(row).toHaveAttribute("href", `/matches/${matchId}/development?focus=6&historyRunId=${runId}`);
  const champion = row.getByRole("img", { name: "Garen" });
  await expect(champion).toBeVisible();
  await expect.poll(() => champion.evaluate(
    (image: HTMLImageElement) => image.complete && image.naturalWidth > 0,
  )).toBe(true);
  const response = await request.get(`/api/player-matches/${runId}`);
  expect(await response.text()).not.toMatch(/puuid|lookup-invented-participant|sourceCapture|payload_json/);
  const suggestionQuery = new URLSearchParams({ platform: "NA1", q: `Lookup${id}` });
  const suggestions = await request.get(`/api/player-suggestions?${suggestionQuery}`);
  expect(suggestions.ok()).toBe(true);
  expect(await suggestions.json()).toMatchObject({ suggestions: [{ gameName: `Lookup${id}`, tagLine: "NA1", platform: "NA1" }] });
  expect(await suggestions.text()).not.toMatch(/puuid|sourceCapture|payload_json/);
  suggestionQuery.set("platform", "EUW1");
  const otherRegion = await request.get(`/api/player-suggestions?${suggestionQuery}`);
  expect(await otherRegion.json()).toEqual({ suggestions: [] });
  await page.reload();
  await expect(row).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
  const persisted = await request.get(`http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? "8080"}/api/v1/matches/${matchId}/development?focus=6`);
  expect(persisted.ok()).toBe(true);
  expect((await persisted.json()).matchId).toBe(matchId);
});

test("failed live lookup keeps the independent sample working", async ({ page }) => {
  await installDeterministicGameAssets(page);
  await page.goto("/summoners/na/Unavailable-NA1");
  const failure = page.locator(".player-lookup__status");
  await expect(failure).toContainText("Live lookup is unavailable.");
  await expect(failure).not.toContainText(/explore.*sample/i);
  await page.getByRole("link", { name: "Explore sample match" }).click();
  await expect(page).toHaveURL(/NA1_7000000001\/development/);
  await expect(page.getByText("Sample match — synthetic data")).toBeVisible();
  await expect(page.getByText(/Garen extended his lead: CS \+4 to \+13/)).toBeVisible();
});

test("slow history keeps navigation and completed matches usable, then recovers from a failed refresh", async ({ page }) => {
  await installDeterministicGameAssets(page);
  const runId = "00000000-0000-0000-0000-000000000008";
  const match = { matchId: "NA1_7000000001", queueId: 420, participantId: 6, championName: "Garen", championId: 86,
    gameVersion: "16.17.1", endItemIds: [3071, 3047, 0, 0, 0, 0, 3340], position: "UTILITY", win: true,
    startedAtMs: 1788890400000, durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: true };
  let stage: "pending" | "running" | "failed" | "complete" = "pending";
  let release: (() => void) | undefined;
  await page.route(`**/api/player-matches/${runId}`, async route => {
    if (stage === "pending") await new Promise<void>(resolve => { release = resolve; });
    if (stage === "failed") return route.fulfill({ status: 503, contentType: "application/json", body: "{}" });
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ runId, gameName: "플레이어名前LongName", tagLine: "NA1",
      status: stage === "complete" ? "COMPLETE" : "RUNNING", message: null, retryNotBefore: null, queueId: 420, lastUpdated: null, nextRefreshAt: null, previousRunId: null, hasMore: true, matches: [match] }) });
  });
  await page.goto(`/search?runId=${runId}`);
  await expect(page.locator(".player-lookup__status").getByRole("status")).toContainText("Loading match history");
  await expect(page.getByRole("button", { name: "Find matches" })).toBeEnabled();
  stage = "running"; release?.();
  const row = page.getByRole("link", { name: "Garen victory match development" });
  await expect(row).toBeVisible();
  await expect(row.getByText("SUPPORT", { exact: true })).toBeVisible();
  await expect(page.locator(".player-lookup__status").getByRole("status")).toContainText("1 match is ready to open");
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 844 });
    const progress = page.locator(".player-lookup__status").getByRole("status");
    await expect(progress).toBeInViewport();
    const progressBounds = await progress.boundingBox();
    const performanceBounds = await page.getByRole("region", { name: "Recent performance" }).boundingBox();
    const sidebarBounds = await page.getByRole("complementary", { name: "Player ranks and champions" }).boundingBox();
    expect(progressBounds!.y + progressBounds!.height).toBeLessThanOrEqual(performanceBounds!.y);
    expect(progressBounds!.y + progressBounds!.height).toBeLessThanOrEqual(sidebarBounds!.y);
    await expect(row).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  }
  await page.getByLabel("Riot ID").fill("Another player");
  stage = "failed";
  await expect(page.getByRole("region", { name: "Player lookup" }).getByRole("alert")).toBeVisible();
  await expect(row).toBeVisible();
  stage = "complete";
  await page.getByRole("button", { name: "Retry loading" }).click();
  await expect(page.locator(".player-lookup__status").getByRole("status")).toHaveText("1 match");
  await expect(page.getByLabel("Riot ID")).toHaveValue("Another player");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
});

for (const width of [1440, 390, 320]) {
  const enlargedText = width === 320;
  const pageCount = enlargedText ? 2 : 12;
  const matchCount = pageCount * 20;
  test(`linked restoration keeps profile and history anchors stable at ${width}px${enlargedText ? " with 200% text" : ""}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installDeterministicGameAssets(page);
    const identity = { gameName: "Restored", tagLine: "NA1", platform: "NA1" };
    const runId = (number: number) => `10000000-0000-0000-0000-${String(number).padStart(12, "0")}`;
    const requestedRuns: number[] = [];
    let resume!: () => void;
    const remainingPages = new Promise<void>(resolve => { resume = resolve; });
    await page.route("**/api/player-matches/**", async route => {
      const pathname = new URL(route.request().url()).pathname;
      if (pathname.endsWith("/profile")) return route.fulfill({ json: { ...profileFixture, identity } });
      const number = Number(pathname.slice(-12));
      if (!Number.isInteger(number) || number < 1 || number > pageCount) return route.fulfill({ status: 404, json: {} });
      requestedRuns.push(number);
      if (number === pageCount - 1) await remainingPages;
      // Every page has the same day, champion and result ratios. Only the row count grows.
      const matches = Array.from({ length: 20 }, (_, index) => ({
        matchId: `NA1_${9000000000 + number * 20 + index}`, queueId: 420, participantId: 6,
        championName: "Garen", championId: 86, gameVersion: "16.17.1", endItemIds: [], position: "TOP",
        win: true, remake: false, startedAtMs: 1788890400000, durationSeconds: 1800,
        kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: true,
      }));
      await route.fulfill({ json: { ...identity, runId: runId(number), status: "COMPLETE", message: null,
        retryNotBefore: null, queueId: 420, lastUpdated: "2026-09-14T10:02:00Z", nextRefreshAt: null,
        previousRunId: number > 1 ? runId(number - 1) : null, hasMore: false, matches } });
    });
    await page.goto(`/search?runId=${runId(pageCount)}`);
    if (enlargedText) {
      await page.addStyleTag({ content: "html { font-size: 32px !important; }" });
      expect(await page.evaluate(() => getComputedStyle(document.documentElement).fontSize)).toBe("32px");
    }
    const history = page.getByRole("list", { name: "Recent match history" });
    await expect(history.getByRole("listitem")).toHaveCount(20);
    await expect(page.getByLabel("Level 123")).toBeVisible();
    await expect(page.getByText("Emerald II", { exact: true })).toBeVisible();
    await expect.poll(() => page.evaluate(() => [...document.images].every(image => image.complete))).toBe(true);
    await page.evaluate(() => document.fonts.ready);
    const status = page.locator(".player-lookup__status").getByRole("status");
    await expect(status).toContainText("20 matches are ready to open");
    await expect(status).toBeInViewport();
    const retainedHref = await history.getByRole("link").first().getAttribute("href");
    expect(retainedHref).toContain("/development?focus=6&historyRunId=");
    await history.getByRole("link").first().click({ trial: true });
    await page.evaluate(() => window.scrollTo(0, 0));

    const anchors = {
      results: page.locator(".player-lookup__results"),
      profile: page.locator(".profile-header"),
      performance: page.getByRole("region", { name: "Recent performance" }),
      historyHeading: page.getByRole("heading", { name: "Match history", exact: true }),
      history,
    };
    const geometry = async () => Object.fromEntries(await Promise.all(Object.entries(anchors).map(async ([name, locator]) => [name,
      await locator.evaluate(element => {
        const bounds = element.getBoundingClientRect();
        return { top: bounds.top + window.scrollY, height: bounds.height };
      }),
    ])));
    const before = await geometry();
    const form = await page.locator(".player-search").boundingBox();
    const progress = await status.boundingBox();
    expect(progress!.y).toBeGreaterThanOrEqual(form!.y + form!.height);
    expect(progress!.y - form!.y - form!.height).toBeLessThan(48);
    expect(progress!.y + progress!.height).toBeLessThanOrEqual(before.profile.top);
    const overflowBefore = await page.evaluate(() => Math.max(0, document.documentElement.scrollWidth - innerWidth));
    if (enlargedText) {
      expect(progress!.x).toBeGreaterThanOrEqual(0);
      expect(progress!.x + progress!.width).toBeLessThanOrEqual(width);
      expect(await status.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
    }

    resume();
    await expect(history.getByRole("listitem")).toHaveCount(matchCount);
    await expect(status).toHaveText(`${matchCount} matches`);
    await expect(page.getByLabel("Queue Type")).toBeEnabled();
    const after = await geometry();
    // Check actual content anchors as well as the outer wrapper: padding cannot mask an inner collapse.
    for (const name of Object.keys(anchors)) {
      expect.soft(Math.abs(after[name].top - before[name].top), `${name} moved after restoration at ${width}px`).toBeLessThanOrEqual(1);
    }
    expect.soft(after.profile.height, "profile enrichment must be settled before measurement").toBe(before.profile.height);
    expect.soft(after.performance.height, "added rows must not resize the summary fixture").toBe(before.performance.height);
    const settled = await status.boundingBox();
    expect.soft(settled!.y + settled!.height, "settled feedback stays beside the search form").toBeLessThanOrEqual(after.profile.top);
    await page.locator(`a[href="${retainedHref}"]`).click({ trial: true });
    expect(requestedRuns).toEqual(Array.from({ length: pageCount }, (_, index) => pageCount - index));
    if (enlargedText) {
      // Other profile sections already overflow with doubled root text; this transition must not add overflow.
      expect(await page.evaluate(() => Math.max(0, document.documentElement.scrollWidth - innerWidth))).toBeLessThanOrEqual(overflowBefore);
      expect(await status.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
    } else {
      expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
    }
  });
}

test("ARAM history filters, paginates, restores loaded pages, and upgrades a deferred timeline", async ({ page, request }) => {
  test.setTimeout(180000);
  await installDeterministicGameAssets(page);
  const seed = String(Date.now());
  const matchId = `NA1_${seed}45000`;
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto("/search");
  await page.getByLabel("Riot ID").fill(`History${seed}#NA1`);
  await page.getByRole("button", { name: "Find matches" }).click();
  const rows = page.getByRole("list", { name: "Recent match history" }).getByRole("listitem");
  await expect(page.getByLabel("Queue Type")).toHaveValue("0");
  await expect(rows).toHaveCount(14, { timeout: 40000 });
  await expect(page.getByLabel("Queue Type")).toBeEnabled({ timeout: 40000 });
  for (const label of ["Ranked Solo/Duo", "Ranked Flex", "Draft Pick", "Swiftplay", "ARAM"])
    await expect(rows.filter({ hasText: label }).first()).toBeVisible();
  // Every action shares the production admission policy; a suite crossing the minute boundary honors retry metadata.
  const historyAction = async (action: () => Promise<unknown>) => {
    const responsePromise = page.waitForResponse(r => r.url().includes("/api/player-matches") && r.request().method() === "POST");
    await action();
    let response = await responsePromise;
    if (response.status() === 429) {
      const body = await response.json();
      const retryAt = Date.parse(body.retryNotBefore);
      expect(Number.isFinite(retryAt)).toBe(true);
      await expect.poll(() => Date.now(), { timeout: 65000 }).toBeGreaterThanOrEqual(retryAt);
      const retryResponse = page.waitForResponse(r => r.url().includes("/api/player-matches") && r.request().method() === "POST");
      await page.getByRole("button", { name: "Retry loading" }).click();
      response = await retryResponse;
    }
    expect([200, 202]).toContain(response.status());
    const { runId } = await response.json();
    // Rows render before the asynchronous router navigation commits the restorable page URL.
    await expect(page).toHaveURL(new RegExp(`/summoners/na/History${seed}-NA1\\?runId=${runId}$`), { timeout: 40000 });
  };
  await historyAction(() => page.getByLabel("Queue Type").selectOption("450"));
  await expect(rows).toHaveCount(20, { timeout: 40000 });
  await expect(page.getByLabel("Queue Type")).toHaveValue("450");
  await expect(page.getByRole("button", { name: "Load older matches" })).toBeEnabled({ timeout: 40000 });
  await expect(page.getByRole("button", { name: "Update", exact: true })).toBeDisabled();
  await expect(page.getByText(/Update in \d+:\d\d/)).toBeVisible();
  await expect(rows.first()).toContainText("ARAM");
  await expect(rows.first().getByRole("link")).toHaveAttribute("title", "Timeline loads when opened");
  await expect(rows.first()).not.toContainText("TOP");
  await expect(rows.first()).not.toContainText("UNKNOWN");
  const detail = await request.get(`http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? "8080"}/api/v1/matches/${matchId}/development?focus=6`);
  expect(detail.status()).toBe(200);
  expect((await detail.json()).timelineAvailable).toBe(false);
  await historyAction(() => page.getByRole("button", { name: "Load older matches" }).click());
  await expect(rows).toHaveCount(40, { timeout: 40000 });
  const historyUrl = new URL(page.url());
  await page.reload();
  await expect(page).toHaveURL(historyUrl.href);
  await expect(rows).toHaveCount(40);
  await expect(page.getByLabel("Queue Type")).toHaveValue("450");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
  const timelineStart = page.waitForResponse(response => response.url().endsWith(`/api/matches/${matchId}/timeline`)
    && response.request().method() === "POST");
  await rows.first().getByRole("link").click();
  const timelineResponse = await timelineStart;
  expect([200, 202, 429]).toContain(timelineResponse.status());
  if (timelineResponse.status() === 429) {
    // The suite shares the real six-new-jobs/minute admission budget. Honor its retry deadline.
    const retry = page.getByRole("button", { name: "Retry timeline" });
    await expect(retry).toBeDisabled();
    await expect(retry).toBeEnabled({ timeout: 65000 });
    await retry.click();
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await expect(page.getByRole("heading", { name: "Scoreboard" })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "Role", exact: true })).toHaveCount(0);
  await expect(page.getByRole("columnheader", { name: "Dragons", exact: true })).toHaveCount(0);
  await expect(page.getByRole("columnheader", { name: "Towers", exact: true })).toBeVisible();
  await page.getByRole("combobox", { name: "Compare with opponent" }).selectOption("1");
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await page.getByRole("region", { name: "Suggested windows" }).getByRole("link", { name: /^8:00–10:00/ }).click();
  await expect(page.getByRole("table", { name: "Before and after differences" })).toContainText("CS+2+10Gold+100+510XP+20+220");
  await expect(page.getByText("Current avg. tier")).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("table", { name: "Final team results, compact", exact: true }).getByText("Towers", { exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  await page.getByRole("tab", { name: "Runes", exact: true }).click();
  const backToHistory = page.getByRole("link", { name: "Match history", exact: true });
  await expect(backToHistory).toHaveAttribute("href", `/search${historyUrl.search}`);
  await backToHistory.click();
  await expect(rows).toHaveCount(40);
  await expect(page.getByLabel("Queue Type")).toHaveValue("450");
  expect(errors).toEqual([]);
});
