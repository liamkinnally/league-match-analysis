import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "./support/game-assets";
import { profileFixture } from "../src/lib/player-lookup/profile.test-fixture";
const runId = "00000000-0000-0000-0000-000000000019";

test("cached autocomplete stays compact on desktop and mobile and opens a keyboard-selected regional profile", async ({ page }) => {
  await installDeterministicGameAssets(page);
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const queries: string[] = [];
  await page.route("**/api/player-suggestions?**", async route => {
    const url = new URL(route.request().url()); queries.push(url.searchParams.get("q")!);
    const platform = url.searchParams.get("platform");
    await route.fulfill({ json: { suggestions: Array.from({ length: 5 }, (_, index) => ({ gameName: index === 0 ? "Hide on bush" : `Hide player ${index}`, tagLine: "KR1", platform, profileIconId: 29, summonerLevel: 200 + index })) } });
  });
  await page.route("**/api/player-matches", async route => {
    const identity = route.request().postDataJSON();
    await route.fulfill({ json: { ...identity, runId, status: "EMPTY", message: null, retryNotBefore: null, lastUpdated: null, nextRefreshAt: null, previousRunId: null, hasMore: false, matches: [] } });
  });
  await page.route(`**/api/player-matches/${runId}/profile`, route => route.fulfill({ json: { ...profileFixture, identity: { gameName: "Hide on bush", tagLine: "KR1", platform: "KR" } } }));
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");
    const region = page.getByRole("combobox", { name: "Region" });
    await expect(region).toHaveValue("NA1");
    await region.selectOption("KR");
    const input = page.getByRole("combobox", { name: "Riot ID" });
    await input.fill("h");
    await expect(page.getByRole("listbox").getByRole("option")).toHaveCount(5);
    await input.fill("hi");
    await expect.poll(() => queries.at(-1)).toBe("hi");
    const dropdown = page.locator(".player-search__suggestions");
    await expect(dropdown).toBeVisible();
    const bounds = await dropdown.boundingBox();
    expect(bounds!.height).toBeLessThan(380);
    expect(bounds!.x).toBeGreaterThanOrEqual(0);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width);
    expect(await dropdown.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(false);
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
    await page.screenshot({ path: `test-results/player-suggestions-${width}.png`, fullPage: true });
    await input.press("Escape");
    await expect(page.getByRole("listbox")).toHaveCount(0);
    await input.fill("hid");
    await expect(page.getByRole("listbox").getByRole("option")).toHaveCount(5);
    await input.press("ArrowDown");
    await input.press("Enter");
    await expect(page).toHaveURL(/\/summoners\/kr\/Hide%20on%20bush-KR1$/);
    await expect(page.getByRole("heading", { name: "Hide on bush #KR1" })).toBeVisible();
    await expect(page.locator(".profile-header__region")).toHaveText("KR");
    await expect(region).toHaveValue("KR");
  }
  expect(errors).toEqual([]);
});

test("readable profile links reload in all supported regions and reject unsupported paths", async ({ page }) => {
  await installDeterministicGameAssets(page);
  const requests: Array<{ gameName: string; tagLine: string; platform: string }> = [];
  await page.route("**/api/player-matches", async route => {
    const identity = route.request().postDataJSON(); requests.push(identity);
    await route.fulfill({ json: { ...identity, runId, status: "EMPTY", message: null, retryNotBefore: null, lastUpdated: null, nextRefreshAt: null, previousRunId: null, hasMore: false, matches: [] } });
  });
  await page.route(`**/api/player-matches/${runId}/profile`, route => route.fulfill({ json: { ...profileFixture, identity: requests.at(-1) } }));
  for (const [slug, platform, name, tag] of [["na", "NA1", "kitinginmylane", "000"], ["euw", "EUW1", "Name-with-hyphen", "EUW"], ["eune", "EUN1", "Player name", "EUNE"], ["kr", "KR", "다른 이름", "KR1"], ["na", "NA1", "Literal%20name", "000"]]) {
    await page.goto(`/summoners/${slug}/${encodeURIComponent(name)}-${tag}`);
    await expect(page.getByRole("heading", { name: `${name} #${tag}` })).toBeVisible();
    await expect(page.getByRole("combobox", { name: "Region" })).toHaveValue(platform);
    await page.reload();
    await expect(page.getByRole("heading", { name: `${name} #${tag}` })).toBeVisible();
    expect(requests.at(-1)).toMatchObject({ platform, gameName: name, tagLine: tag });
    await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", `https://lolmatchanalysis.app/summoners/${slug}/${encodeURIComponent(name)}-${tag}`);
  }
  await page.goto("/summoners/unknown/Player-000");
  await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
});
