import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "./support/game-assets";

test("player lookup", async ({ page, request }) => {
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
  await page.screenshot({ path: "test-results/player-history-wide.png", fullPage: true });
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
  await page.screenshot({ path: "test-results/player-history-narrow.png", fullPage: true });
  await row.click();
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await expect(page.getByText(/Garen extended his lead: CS \+4 to \+13/)).toBeVisible();
  await expect(page.getByRole("table", { name: "Before and after differences" })).toContainText("CS+4+13Gold+100+510XP+20+220");
  await expect(page.getByRole("link", { name: "Match history", exact: true })).toHaveAttribute("href", `/search?runId=${runId}`);
  await page.getByRole("tab", { name: "Runes", exact: true }).click();
  await expect(page.getByRole("link", { name: "Match history", exact: true })).toHaveAttribute("href", `/search?runId=${runId}`);
  await page.getByRole("link", { name: "Match history", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`runId=${runId}`));
  await expect(row).toBeVisible();
});

test("failed live lookup keeps the independent sample working", async ({ page }) => {
  await installDeterministicGameAssets(page);
  await page.goto("/search");
  await page.getByLabel("Riot ID").fill("Unavailable#NA1");
  await page.getByRole("button", { name: "Find matches" }).click();
  await expect(page.getByText("Live lookup is unavailable. Explore the sample match.")).toBeVisible();
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
  const allHistoryUrl = page.url();
  await page.screenshot({ path: "test-results/all-queue-history-wide.png", fullPage: true });
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
  await expect(page.getByRole("button", { name: "Load older matches" })).toBeEnabled({ timeout: 40000 });
  await historyAction(() => page.getByRole("button", { name: "Load older matches" }).click());
  await expect(rows).toHaveCount(43, { timeout: 40000 });
  await expect(page.getByText("End of match history for this queue.")).toBeVisible();
  const historyUrl = page.url();
  await page.reload();
  await expect(rows).toHaveCount(43);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
  await page.screenshot({ path: "test-results/queue-history-narrow.png", fullPage: true });
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
  await page.screenshot({ path: "test-results/aram-development-wide.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("table", { name: "Final team results, compact", exact: true }).getByText("Towers", { exact: true })).toBeVisible();
  await page.screenshot({ path: "test-results/aram-development-narrow.png", fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  await page.goto(historyUrl);
  await expect(page).toHaveURL(historyUrl);
  await expect(rows).toHaveCount(43);
  await expect(page.getByLabel("Queue Type")).toHaveValue("450");
  await page.getByLabel("Queue Type").selectOption("0");
  await expect(page).toHaveURL(allHistoryUrl);
  await expect(rows).toHaveCount(14);
  await expect(page.getByLabel("Queue Type")).toHaveValue("0");
  await historyAction(() => page.getByRole("button", { name: "Load older matches" }).click());
  await expect(rows).toHaveCount(25, { timeout: 40000 });
  await page.reload();
  await expect(rows).toHaveCount(25);
  await expect(page.getByLabel("Queue Type")).toHaveValue("0");
  await page.screenshot({ path: "test-results/all-queue-history-narrow.png", fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
  expect(errors).toEqual([]);
});
