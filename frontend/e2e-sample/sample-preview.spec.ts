import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "../e2e/support/game-assets";

test("sample preview shows the analytics notice without collecting visits", async ({ page }) => {
  const analyticsRequests: string[] = [];
  page.on("request", request => {
    if (/vercel-scripts\.com|\/_vercel\/insights\//.test(request.url())) analyticsRequests.push(request.url());
  });
  await page.goto("/privacy");
  await expect(page.getByRole("heading", { name: "Website analytics" })).toBeVisible();
  await expect(page.getByText("Updated September 22, 2026")).toBeVisible();
  await expect(page.getByText(/Local development and preview deployments do not collect these analytics/)).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole("heading", { name: "Website analytics" }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: "test-results/analytics-privacy-mobile.png" });
  await page.goto("/terms");
  await expect(page.getByText("Updated September 16, 2026")).toBeVisible();
  await expect(page.locator('script[data-sdkn^="@vercel/analytics"]')).toHaveCount(0);
  expect(analyticsRequests).toEqual([]);
});

test("tokenless sample supports match selection and URL restoration", async ({ page, request }) => {
  await installDeterministicGameAssets(page);
  const liveRequests: string[] = [];
  page.on("request", (request) => {
    if (/\/api\/(player-matches|matches\/.*\/ranks)/.test(request.url())) liveRequests.push(request.url());
  });
  const health = await request.get("/api/health");
  expect(await health.json()).toEqual({ status: "UP", dataSource: "sample", backend: "NOT_USED" });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Explore a sample match" })).toBeVisible();
  await expect(page.getByText(/Live player search is unavailable in this sample preview/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Find matches" })).toHaveCount(0);
  await page.getByRole("link", { name: "Explore sample match" }).click();
  await expect(page.getByText("Sample match — synthetic data")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await page.getByRole("link", { name: /^8:00–10:00 Garen's recorded comparison:/ }).click();
  await expect(page).toHaveURL(/focus=6&compare=1&from=480000&to=600000/);
  await page.getByRole("tab", { name: "XP" }).click();
  await expect(page).toHaveURL(/focus=6&compare=1&from=480000&to=600000&metric=xp/);
  await expect(page.getByLabel("Selected interval 8:00–10:00")).toContainText("+20 XP");
  await expect(page.getByLabel("Selected interval 8:00–10:00")).toContainText("+220 XP");

  await page.locator('[data-participant-id="7"] .refined-summoner-line').click();
  await expect(page.getByRole("heading", { name: "Vi vs Darius" })).toBeVisible();
  await page.getByRole("combobox", { name: "Compare with opponent" }).selectOption("2");
  await expect(page).toHaveURL(/focus=7&compare=2&from=480000&to=600000&metric=xp/);
  await expect(page.getByRole("heading", { name: "Vi vs Lee Sin" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("tab", { name: "XP" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: "Vi vs Lee Sin" })).toBeVisible();
  await expect(page.getByLabel("Selected interval 8:00–10:00")).toContainText("+94 XP");
  await expect(page.getByLabel("Selected interval 8:00–10:00")).toContainText("+116 XP");
  await page.goBack();
  await expect(page.getByRole("heading", { name: "Vi vs Darius" })).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByText("Sample match — synthetic data")).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.goto("/search?runId=previous-backend-run");
  await expect(page.getByText(/Live player search is unavailable in this sample preview/)).toBeVisible();
  await expect(page.getByRole("link", { name: "Explore sample match" })).toBeVisible();
  expect(liveRequests).toEqual([]);
});


test("rune portraits preserve the analysis selection and recover through browser history", async ({ page }) => {
  await installDeterministicGameAssets(page);
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto("/matches/NA1_7000000001/development?focus=6&compare=1&from=480000&to=600000&metric=xp");
  await page.getByRole("tab", { name: "Runes", exact: true }).click();
  const panel = page.getByRole("tabpanel", { name: "Runes", exact: true });
  const portraits = panel.getByRole("group", { name: "Inspect participant runes" });
  await expect(portraits.getByRole("button")).toHaveCount(10);
  await expect(panel.getByRole("heading", { name: /Invented Player 6.*Garen/ })).toBeVisible();
  await expect(panel.getByText("576", { exact: true })).toBeVisible();
  await expect(panel.getByText("454", { exact: true })).toBeVisible();
  await portraits.getByRole("button", { name: /participant 5$/ }).click();
  await expect(page).toHaveURL(/runeParticipant=5/);
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "XP", exact: true })).toHaveAttribute("aria-selected", "true");
  await expect(panel.getByRole("heading", { name: /Invented Player 5/ })).toBeVisible();
  await page.reload();
  await expect(portraits.getByRole("button", { name: /participant 5$/ })).toHaveAttribute("aria-pressed", "true");
  await page.goBack();
  await expect(panel.getByRole("heading", { name: /Invented Player 6.*Garen/ })).toBeVisible();
  const desktopRows = await portraits.locator(".rune-portrait-team").evaluateAll(teams => teams.map(team => Math.round(team.getBoundingClientRect().top)));
  expect(new Set(desktopRows).size).toBe(1);
  await expect(panel.locator(".rune-description").first()).toBeVisible();
  await expect(panel.locator(".rune-description summary")).toHaveCount(0);
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.locator(".development-final-state").evaluate(element => window.scrollTo(0, element.getBoundingClientRect().top + window.scrollY - (document.querySelector(".development-nav")?.getBoundingClientRect().height ?? 48) - 16));
  await page.screenshot({ path: "test-results/runes-desktop.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await portraits.getByRole("button", { name: /participant 10$/ }).click();
  await expect(panel.getByRole("heading", { name: /Invented Player 10/ })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const mobileRows = await portraits.locator(".rune-portrait-team").evaluateAll(teams => teams.map(team => ({ top: Math.round(team.getBoundingClientRect().top), center: team.getBoundingClientRect().left + team.getBoundingClientRect().width / 2 })));
  expect(new Set(mobileRows.map(row => row.top)).size).toBe(2);
  expect(Math.abs(mobileRows[0].center - mobileRows[1].center)).toBeLessThan(1);
  await page.locator(".development-final-state").evaluate(element => window.scrollTo(0, element.getBoundingClientRect().top + window.scrollY - (document.querySelector(".development-nav")?.getBoundingClientRect().height ?? 48) - 16));
  await page.screenshot({ path: "test-results/runes-mobile.png" });
  await page.getByRole("tab", { name: "Scoreboard", exact: true }).click();
  await expect(page.getByRole("table").first()).toBeVisible();
  expect(errors).toEqual([]);
});
