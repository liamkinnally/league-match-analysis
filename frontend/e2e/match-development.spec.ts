import { expect, test } from "@playwright/test";
import { installDeterministicGameAssets, installUnavailableGameAssets } from "./support/game-assets";

const sampleRoute = "/matches/NA1_7000000001/development?focus=6&compare=1";

test("sample development wide", async ({ page, request }) => {
  await installDeterministicGameAssets(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  const endpointResponse = await request.get(`http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? "8080"}/api/v1${sampleRoute}`);
  expect(endpointResponse.ok()).toBe(true);
  const endpoint = await endpointResponse.json();
  await page.goto("/");
  await page.getByRole("link", { name: "Explore sample match" }).click();
  await expect(page.getByText("Sample match — synthetic data")).toBeVisible();
  await expect(page.getByRole("tab", { name: "Gold" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Suggested windows" })).toBeVisible();
  await expect(page.getByText(endpoint.suggestedWindows[0].summary)).toBeVisible();
  await expect(page.getByLabel("Selected interval 8:00–10:00")).toContainText(`+${endpoint.suggestedWindows[0].before.goldDifference} g`);
  await expect(page.getByLabel("Selected interval 8:00–10:00")).toContainText(`+${endpoint.suggestedWindows[0].after.goldDifference} g`);
  await expect(page.getByRole("heading", { name: "Scoreboard" })).toBeVisible();
  const garen = page.getByRole("img", { name: "Garen" }).first();
  await expect(garen).toBeVisible();
  await expect.poll(() => garen.evaluate(
    (image: HTMLImageElement) => image.complete && image.naturalWidth > 0,
  )).toBe(true);
});

test("sample development narrow", async ({ page }) => {
  await installDeterministicGameAssets(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(sampleRoute);
  await expect(page.getByText("Sample match — synthetic data")).toBeVisible();
  await page.getByRole("tab", { name: "XP" }).click();
  await expect(page.getByRole("tab", { name: "XP" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: "XP difference over time" })).toBeVisible();
  const overflow = await page.evaluate(() => [...document.querySelectorAll("body *")]
    .filter((element) => element.getBoundingClientRect().right > window.innerWidth + 1)
    .map((element) => ({ tag: element.tagName, className: element.className })));
  expect(overflow).toEqual([]);
});

test("development selection restores", async ({ page }) => {
  await installDeterministicGameAssets(page);
  await page.goto(sampleRoute);
  await expect(page.getByLabel("Selected interval 8:00–10:00")).toBeVisible();
  await page.getByRole("link", { name: /8:00–10:00 Garen extended his lead/ }).click();
  await expect(page).toHaveURL(/focus=6&compare=1&from=480000&to=600000/);
  await page.getByRole("combobox", { name: "Compare with opponent" }).selectOption("2");
  await expect(page).toHaveURL(/focus=6&compare=2&from=480000&to=600000/);
  await page.goBack();
  await expect(page).toHaveURL(/focus=6&compare=1&from=480000&to=600000/);
  await page.reload();
  expect(page.url()).toContain("focus=6&compare=1&from=480000&to=600000");
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await expect(page.getByLabel("Selected interval 8:00–10:00")).toContainText("+100 g");
  await expect(page.getByLabel("Selected interval 8:00–10:00")).toContainText("+510 g");
  await expect(page.getByRole("table", { name: "Before and after differences" }))
    .toContainText("CS+4+13Gold+100+510XP+20+220");
  await page.locator(".refined-events > summary").click();
  await expect(page.getByText("Purchased Black Cleaver", { exact: true })).toBeVisible();
  await expect(page.getByText("Killed Darius", { exact: true })).toBeVisible();
  await expect(page.locator('[data-event-type="ELITE_MONSTER_KILL"]').filter({ hasText: "Vi" })).toBeVisible();

  await page.locator('[data-participant-id="7"] .refined-summoner-line').click();
  await expect(page).toHaveURL(/focus=7&compare=1&from=480000&to=600000/);
  await page.reload();
  expect(page.url()).toContain("focus=7&compare=1&from=480000&to=600000");
  await expect(page.getByRole("heading", { name: "Vi vs Darius" })).toBeVisible();
  await page.goBack();
  await expect(page).toHaveURL(/focus=6&compare=1&from=480000&to=600000/);

  await page.goto(`${sampleRoute}&from=481000&to=600000`);
  await expect(page).toHaveURL(/focus=6&compare=1$/);

  await page.goto("/matches/NA1_7000000001/development?focus=6");
  await expect(page).toHaveURL(/focus=6&compare=1$/);
});

test("development discloses every persisted sample", async ({ page, request }) => {
  await installDeterministicGameAssets(page);
  await page.setViewportSize({ width: 390, height: 844 });
  const endpointResponse = await request.get(`http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? "8080"}/api/v1${sampleRoute}`);
  expect(endpointResponse.ok()).toBe(true);
  const endpoint = await endpointResponse.json();
  await page.goto(sampleRoute);
  await page.getByText("Show all 15 sampled values").click();
  await expect(page.getByRole("table", { name: "All sampled gold values" }).getByRole("row")).toHaveCount(endpoint.samples.length + 1);
  await expect(page.getByRole("heading", { name: "Gold difference over time" })).toBeVisible();
});

test("development retains useful event IDs when the optional asset catalog is unavailable", async ({ page }) => {
  await installUnavailableGameAssets(page);
  await page.goto(sampleRoute);

  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await page.locator(".refined-events > summary").click();
  const purchase = page.getByText("Purchased Unresolved item", { exact: true }).first().locator("..");
  await purchase.getByText("Record details").click();
  await expect(purchase.locator("pre")).toContainText('"itemId": 3071');
  await expect(page.getByText("Killed Darius", { exact: true })).toBeVisible();
  await expect(page.locator('[data-event-type="ELITE_MONSTER_KILL"]').filter({ hasText: "Vi" })).toBeVisible();
});

test("stored rune counters reach the page and unavailable labels leave the match usable", async ({ page, request }) => {
  await installDeterministicGameAssets(page);
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => { if (message.type() === "error") errors.push(message.text()); });
  const response = await request.get(`http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? "8080"}/api/v1${sampleRoute}`);
  const stored = await response.json();
  expect(stored.roster.find((player: { participantId: number }) => player.participantId === 6).runes.styles[0].selections[0].counters).toEqual({ var1: 576, var2: 454, var3: 0 });
  await page.goto(`${sampleRoute}&finalView=runes&runeParticipant=6`);
  const panel = page.getByRole("tabpanel", { name: "Runes", exact: true });
  await expect(panel.getByText("576", { exact: true })).toBeVisible();
  await expect(panel.getByText("454", { exact: true })).toBeVisible();
  await expect(panel.getByRole("group", { name: "Inspect participant runes" }).getByRole("button")).toHaveCount(10);
  await expect(panel.locator(".rune-description").first()).toBeVisible();
  await installUnavailableGameAssets(page);
  await page.reload();
  await expect(panel.getByText("Patch-matched rune descriptions unavailable. Performance values cannot be labeled yet.")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  await page.getByRole("tab", { name: "Scoreboard", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Scoreboard" })).toBeVisible();
  expect(errors).toEqual([]);
});
