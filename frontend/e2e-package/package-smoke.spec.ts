import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "../e2e/support/game-assets";

test("packaged application serves the persisted sample through the browser", async ({ page }) => {
  await installDeterministicGameAssets(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  await expect(page.getByRole("link", { name: "match-analysis-v1" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Find matches" })).toBeEnabled();

  await page.getByRole("link", { name: "Explore sample match" }).click();
  await expect(page.getByText("Sample match — synthetic data")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await expect(page.getByText("Victory", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Gold earned").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "Gold difference over time" })).toBeVisible();

  await expect(page.getByText(
    "Garen extended his lead: CS +4 to +13, gold +100 to +510, and XP +20 to +220.",
  )).toBeVisible();
  await expect(page.getByRole("table", { name: "Before and after differences" }))
    .toContainText("CS+4+13Gold+100+510XP+20+220");
  await page.locator(".refined-events > summary").click();
  await expect(page.getByText("Purchased Black Cleaver", { exact: true })).toBeVisible();
  await expect(page.getByText("Killed Darius", { exact: true })).toBeVisible();
  await expect(page.getByText(/Assists:\s*Vi,\s*Orianna/)).toBeVisible();
  await expect(page.getByText("Secured Cloud Dragon", { exact: true })).toBeVisible();
  await page.screenshot({ path: "test-results/package-smoke-wide.png", fullPage: true });

  await page.getByText("Other recorded intervals").click();
  const otherInterval = page.getByRole("navigation", { name: /other recorded intervals, scrollable table/ })
    .getByRole("link").first();
  const intervalLabel = (await otherInterval.textContent())?.trim();
  expect(intervalLabel).toBeTruthy();
  await otherInterval.click();
  await expect(page).toHaveURL(/[?&]from=\d+&to=\d+/);
  const selectedFrom = new URL(page.url()).searchParams.get("from");
  const selectedTo = new URL(page.url()).searchParams.get("to");
  expect(selectedFrom).toBeTruthy(); expect(selectedTo).toBeTruthy();
  const selectedUrl = page.url();
  await page.reload();
  expect(page.url()).toBe(selectedUrl);
  await expect(page.getByRole("heading", { name: "Gold difference over time" })).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  await page.screenshot({ path: "test-results/package-smoke-narrow.png", fullPage: true });
});
