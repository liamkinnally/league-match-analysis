import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets } from "../e2e/support/game-assets";

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
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.goto("/search?runId=previous-backend-run");
  await expect(page.getByText(/Live player search is unavailable in this sample preview/)).toBeVisible();
  await expect(page.getByRole("link", { name: "Explore sample match" })).toBeVisible();
  expect(liveRequests).toEqual([]);
});
