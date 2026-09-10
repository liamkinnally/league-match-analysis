import { expect, test, type Page } from "@playwright/test";

const evidenceRevision = `ev_${"1".repeat(64)}`;
const investigationUrl =
  "/matches/__lab_investigation" +
  "?focus=6" +
  "&mode=investigate" +
  "&object=trn_000000000000000000000002" +
  "&start=780275" +
  "&end=900291" +
  "&question=advantage-conversion" +
  `&evidence=${evidenceRevision}` +
  "&lens=SEQUENCE";

async function openStablePage(page: Page, url: string): Promise<string[]> {
  const errors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(url);
  await page.addStyleTag({
    content: `
      *, *::before, *::after {
        animation: none !important;
        caret-color: transparent !important;
        transition: none !important;
      }
    `,
  });
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
  return errors;
}

test("normal Explore wide", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const errors = await openStablePage(
    page,
    "/matches/__lab_normal?focus=6",
  );

  await expect(
    page.getByRole("heading", { name: /how this match changed/i }),
  ).toBeVisible();
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("normal-explore.png", {
    animations: "disabled",
    fullPage: true,
  });
});

test("Investigation wide", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const errors = await openStablePage(page, investigationUrl);

  await expect(page.getByTestId("analysis-stage")).toBeVisible();
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("investigation-wide.png", {
    animations: "disabled",
    fullPage: true,
  });
});

test("Investigation narrow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const errors = await openStablePage(page, investigationUrl);

  await expect(page.getByTestId("analysis-stage")).toBeVisible();
  expect(errors).toEqual([]);
  await expect(page).toHaveScreenshot("investigation-narrow.png", {
    animations: "disabled",
    fullPage: true,
  });
});
