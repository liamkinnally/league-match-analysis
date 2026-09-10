import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "./support/game-assets";

test("player lookup", async ({ page, request }) => {
  await installDeterministicGameAssets(page);
  const id = `7${Date.now()}`;
  const matchId = `NA1_${id}`;
  const before = await request.get(`http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? "8080"}/api/v1/matches/${matchId}/development?focus=6`);
  expect(before.status()).toBe(404);
  await page.goto("/search");
  await page.getByLabel("Game name").fill(`Lookup${id}`);
  await page.getByLabel("Tag line").fill("NA1");
  await page.getByRole("button", { name: "Find matches" }).click();
  await expect(page).toHaveURL(/search\?runId=/);
  const runId = new URL(page.url()).searchParams.get("runId");
  const row = page.getByRole("link", { name: "Garen victory match development" });
  await expect(row).toBeVisible({ timeout: 20000 });
  await expect(row).toHaveAttribute("href", `/matches/${matchId}/development?focus=6`);
  const champion = row.getByRole("img", { name: "Garen" });
  await expect(champion).toBeVisible();
  await expect.poll(() => champion.evaluate(
    (image: HTMLImageElement) => image.complete && image.naturalWidth > 0,
  )).toBe(true);
  await page.screenshot({ path: "test-results/player-history-wide.png", fullPage: true });
  const response = await request.get(`/api/player-matches/${runId}`);
  expect(await response.text()).not.toMatch(/puuid|lookup-invented-participant|sourceCapture|payload_json/);
  await page.reload();
  await expect(row).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
  await page.screenshot({ path: "test-results/player-history-narrow.png", fullPage: true });
  await row.click();
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await expect(page.getByText(/Garen extended his lead: CS \+4 to \+13/)).toBeVisible();
  await expect(page.getByRole("table", { name: "Before and after differences" })).toContainText("CS+4+13Gold+100+510XP+20+220");
  await page.goBack();
  await expect(page).toHaveURL(new RegExp(`runId=${runId}`));
  await expect(row).toBeVisible();
});

test("failed live lookup keeps the independent sample working", async ({ page }) => {
  await installDeterministicGameAssets(page);
  await page.goto("/search");
  await page.getByLabel("Game name").fill("Unavailable");
  await page.getByLabel("Tag line").fill("NA1");
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
  const match = { matchId: "NA1_7000000001", participantId: 6, championName: "Garen", championId: 86,
    gameVersion: "16.17.1", endItemIds: [3071, 3047, 0, 0, 0, 0, 3340], position: "UTILITY", win: true,
    startedAtMs: 1788890400000, durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: true };
  let stage: "pending" | "running" | "failed" | "complete" = "pending";
  let release: (() => void) | undefined;
  await page.route(`**/api/player-matches/${runId}`, async route => {
    if (stage === "pending") await new Promise<void>(resolve => { release = resolve; });
    if (stage === "failed") return route.fulfill({ status: 503, contentType: "application/json", body: "{}" });
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ runId, gameName: "플레이어名前LongName", tagLine: "NA1",
      status: stage === "complete" ? "COMPLETE" : "RUNNING", message: null, retryNotBefore: null, matches: [match] }) });
  });
  await page.goto(`/search?runId=${runId}`);
  await expect(page.getByRole("region", { name: "Player lookup" }).getByRole("status")).toContainText("Loading match history");
  await expect(page.getByRole("button", { name: "Find matches" })).toBeEnabled();
  stage = "running"; release?.();
  const row = page.getByRole("link", { name: "Garen victory match development" });
  await expect(row).toBeVisible();
  await expect(row.getByText("SUPPORT", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Player lookup" }).getByRole("status")).toContainText("1 match is ready to open");
  await page.getByLabel("Game name").fill("Another player");
  stage = "failed";
  await expect(page.getByRole("region", { name: "Player lookup" }).getByRole("alert")).toBeVisible();
  await expect(row).toBeVisible();
  stage = "complete";
  await page.getByRole("button", { name: "Retry loading" }).click();
  await expect(page.getByRole("region", { name: "Player lookup" }).getByRole("status")).toContainText("1 recent match — Ready to review");
  await expect(page.getByLabel("Game name")).toHaveValue("Another player");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
});
