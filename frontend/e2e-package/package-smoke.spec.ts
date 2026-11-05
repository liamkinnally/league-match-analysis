import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "../e2e/support/game-assets";

test("packaged application serves the persisted sample through the browser", async ({ page }) => {
  await installDeterministicGameAssets(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  await expect(page.getByRole("link", { name: "LoL Match Analysis" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Find matches" })).toBeEnabled();
  await expect(page.locator('meta[name="twitter:card"]')).toHaveAttribute("content", "summary_large_image");
  await expect(page.locator('meta[property="og:image"]')).toHaveAttribute("content", "https://lolmatchanalysis.app/social-card.png");
  for (const path of ["/favicon.ico", "/favicon.png", "/apple-touch-icon.png", "/brand/logo-mark.svg", "/social-card.png"]) {
    const asset = await page.request.get(path);
    expect(asset.ok(), `${path} is included in the packaged application`).toBe(true);
    expect(asset.headers()["content-type"]).toMatch(/^image\//);
  }

  await page.getByRole("link", { name: "Explore sample profile" }).click();
  await expect(page.getByRole("heading", { name: /Northline.*#DEMO/ })).toBeVisible();
  // Check the persisted backend sample independently of the built-in sample profile.
  await page.goto("/matches/NA1_7000000001/development?focus=6&compare=1");
  await expect(page.getByText("Sample match — synthetic data")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await page.getByRole("tab", { name: "XP" }).click();
  await expect(page.getByRole("heading", { name: "XP difference over time" })).toBeVisible();
  await page.screenshot({ path: "test-results/package-smoke-wide.png", fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  await page.screenshot({ path: "test-results/package-smoke-narrow.png", fullPage: true });
});
