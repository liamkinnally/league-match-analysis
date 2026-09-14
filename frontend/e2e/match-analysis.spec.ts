import { expect, test, type Page, type Locator } from "@playwright/test";
import { execFileSync } from "node:child_process";

const matchRoute = "/matches/NA1_9000000001?focus=6";

async function follow(page: Page, link: Locator) {
  const href = await link.getAttribute("href");
  const destination = new URL(href!, page.url()).toString();
  await link.click();
  await expect(page).toHaveURL(destination);
}

async function inspect(page: Page, index: number) {
  await page.goto(matchRoute);
  await follow(page, page.getByTestId("transition-marker").nth(index - 1).getByRole("link"));
}

function evidenceContext(url: string) {
  const query = new URL(url).searchParams;
  return Object.fromEntries(["object", "question", "start", "end", "evidence"].map((key) => [key, query.get(key)]));
}

test("keeps one evidence-backed context across Explore, Review, and Investigation", async ({ page }) => {
  await page.goto("/matches/NA1_9000000001?focus=6");
  await expect(page.getByRole("heading", { name: /how this match changed/i })).toBeVisible();

  await page.getByRole("link", { name: /inspect e2: focal-team gains/i }).click();
  await expect(page.getByText("Team lead +819 → +3,142")).toBeVisible();
  await expect(page.getByText(/sampled at/i)).toBeVisible();

  await page.getByRole("link", { name: /review · 4/i }).click();
  await expect(page.getByText("Case 1 of 4")).toBeVisible();
  await expect(page.getByRole("link", { name: /earlier · 08:20/i })).toBeVisible();

  await follow(page, page.getByRole("link", { name: /investigate/i }));
  const investigationUrl = page.url();
  await page.getByRole("link", { name: /evidence/i }).click();
  await expect(page.getByText(/represented time/i).first()).toBeVisible();
  await page.getByRole("link", { name: /close evidence/i }).click();
  await expect(page).toHaveURL(investigationUrl);
  expect(page.url()).toBe(investigationUrl);
});

test("dismisses the marker explanation and shows only timestamped Map points", async ({ page }) => {
  await page.goto(matchRoute);
  await expect(page.getByLabel("Match Arc marker explanation")).toBeVisible();
  await page.getByRole("button", { name: "Got it" }).click();
  await expect(page.getByLabel("Match Arc marker explanation")).toHaveCount(0);
  await page.getByTestId("transition-marker").first().getByRole("link").click();
  await expect(page.getByTestId("analysis-stage")).toHaveAttribute("data-lens", "MAP");
  const map = page.getByRole("region", { name: "Observed positions" });
  await expect(map.getByText("9:00", { exact: true })).toBeVisible();
  await expect(map.getByText("9:30", { exact: true })).toBeVisible();
  await expect(map.getByText(/does not provide a path between them/)).toBeVisible();
  await expect(map.locator("path, polyline, canvas")).toHaveCount(0);
  await page.reload();
  await expect(page.getByLabel("Match Arc marker explanation")).toHaveCount(0);
});

test("keeps learning order separate from chronology and returns to the same Review beat", async ({ page }) => {
  await page.goto(matchRoute);
  await page.getByRole("link", { name: /review · 4/i }).click();
  await expect(page.getByRole("heading", { name: "Case 1 of 4" })).toBeVisible();
  const rail = page.getByRole("navigation", { name: "Chronological case rail" });
  await expect(rail.getByRole("link")).toHaveText(["E108:20", "E213:00", "E318:00", "E425:00"]);
  await expect(rail.getByRole("link").nth(1)).toHaveAttribute("aria-current", "step");
  const reviewContext = evidenceContext(page.url());
  const investigate = page.getByRole("link", { name: "Investigate", exact: true });
  const href = await investigate.getAttribute("href");
  const beat = new URL(href!, "http://127.0.0.1:3000").searchParams.get("returnBeat");
  await follow(page, investigate);
  await follow(page, page.getByRole("link", { name: "Back to Review", exact: true }));
  expect(evidenceContext(page.url())).toEqual(reviewContext);
  await expect(page.getByRole("heading", { name: "Case 1 of 4" })).toBeVisible();
  expect(beat).toBeTruthy();
  expect(new URL(page.url()).searchParams.get("returnBeat")).toBe(beat);
  await expect(page.locator(`[id="${beat}"]`)).toBeInViewport({ ratio: 0.9 });
  expect(new URL(page.url()).hash).toBe("");
  await page.getByRole("link", { name: /earlier · 08:20/i }).click();
  await expect(page.getByRole("heading", { name: "Case 2 of 4" })).toBeVisible();
  await expect(rail.getByRole("link").first()).toHaveAttribute("aria-current", "step");
});

test("separates observed ownership, maintained capability, and unknown active use", async ({ page }) => {
  await inspect(page, 2);
  await page.getByRole("link", { name: "Champion timing", exact: true }).click();
  const champion = page.getByRole("region", { name: "Champion timing", exact: true });
  await expect(champion.locator('[data-assertion-mode="OBSERVED"]').filter({ hasText: "An item event was recorded" })).toBeVisible();
  await expect(champion.locator('[data-assertion-mode="RECONSTRUCTED"]').filter({ hasText: "Stridebreaker became owned at 13:34.821" })).toBeVisible();
  await expect(champion.locator('[data-assertion-mode="EXPERT_MAINTAINED"]')).toContainText("Its active can slow nearby enemies and grant decaying movement speed per champion hit.");
  await expect(champion.locator('[data-assertion-mode="UNKNOWN"]').filter({ hasText: "Active use, readiness, and causal impact are unknown." })).toBeVisible();
  await expect(champion.getByText(/Patch 16.17/)).toBeVisible();
  await expect(champion.getByText("Active use required", { exact: true })).toBeVisible();
});

test("keeps an adverse receipt conservative and falls back from an unavailable lens", async ({ page }) => {
  await inspect(page, 3);
  await expect(page.getByTestId("analysis-stage")).toHaveAttribute("data-lens", "STATE");
  const origin = page.url();
  await expect(page.getByRole("link", { name: "Champion timing", exact: true })).toHaveCount(0);
  const requested = new URL(origin);
  requested.searchParams.set("lens", "CHAMPION_TIMING");
  await page.goto(requested.toString());
  await expect(page.getByTestId("analysis-stage")).toHaveAttribute("data-lens", "RECEIPT");
  await expect(page.getByRole("status")).toContainText("Showing the timestamped receipt instead");
  expect(evidenceContext(page.url())).toEqual(evidenceContext(origin));
  await page.getByRole("link", { name: "Ask", exact: true }).click();
  await page.getByRole("button", { name: "What remains unsupported by this evidence?" }).click();
  await expect(page.getByRole("dialog", { name: "Ask" })).toContainText("does not establish objective contestability or a link from the earlier death");
});

test("requires an explicit fresh analysis after a stale evidence revision", async ({ page }) => {
  await inspect(page, 2);
  const stale = new URL(page.url());
  stale.searchParams.set("evidence", `ev_${"0".repeat(64)}`);
  stale.searchParams.set("panel", "evidence");
  await page.goto(stale.toString());

  await expect(page.getByRole("heading", { name: "Match evidence changed" })).toBeVisible();
  await expect(page.getByTestId("analysis-stage")).toHaveCount(0);
  const refresh = page.getByRole("link", { name: "Refresh analysis", exact: true });
  await expect(refresh).toHaveAttribute("href", matchRoute);
  await follow(page, refresh);

  await expect(page.getByRole("heading", { name: "How this match changed" })).toBeVisible();
  await expect(page.getByTestId("transition-marker")).toHaveCount(4);
  await expect(page.getByTestId("analysis-stage")).toHaveCount(0);
  expect(new URL(page.url()).search).toBe("?focus=6");
});

test("preserves overlap in parallel Sequence bands and context across lens changes", async ({ page }) => {
  await inspect(page, 4);
  const context = evidenceContext(page.url());
  const parallel = page.getByTestId("sequence-band").filter({ hasText: "Parallel observations" });
  await expect(parallel).toHaveCount(1);
  await expect(parallel).toHaveAttribute("data-parallel", "true");
  await expect(parallel.locator("ul > li")).toHaveCount(2);
  await expect(parallel.locator("time")).toHaveText("26:00");
  await follow(page, page.getByRole("link", { name: "State", exact: true }));
  expect(evidenceContext(page.url())).toEqual(context);
  await expect(page.getByTestId("analysis-stage")).toHaveAttribute("data-lens", "STATE");
});

test("closes Evidence and structured Ask to byte-identical originating URLs", async ({ page }) => {
  await inspect(page, 2);
  const origin = page.url();
  await page.getByRole("link", { name: "Evidence", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "Evidence" })).not.toHaveAttribute("aria-modal", "true");
  await page.getByRole("link", { name: "Close Evidence", exact: true }).click();
  await expect(page).toHaveURL(origin);
  expect(page.url()).toBe(origin);
  await page.getByRole("link", { name: "Ask", exact: true }).click();
  const ask = page.getByRole("dialog", { name: "Ask" });
  await expect(ask.getByRole("textbox")).toHaveCount(0);
  await expect(ask.getByRole("button").first()).toBeVisible();
  await ask.getByRole("button").first().click();
  await expect(ask.locator('[aria-live="polite"]')).toBeVisible();
  await page.getByRole("link", { name: "Close Ask", exact: true }).click();
  await expect(page).toHaveURL(origin);
  expect(page.url()).toBe(origin);
});

test("shows a missing match honestly", async ({ page }) => {
  await page.goto("/matches/NA1_missing?focus=6");
  await expect(page.getByRole("heading", { name: "Match not found" })).toBeVisible();
  await expect(page.getByTestId("transition-marker")).toHaveCount(0);
});

test("supports the full flow with keyboard focus at a narrow viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(matchRoute);
  const inspectLink = page.getByTestId("transition-marker").nth(1).getByRole("link");
  await inspectLink.focus();
  await expect(inspectLink).toBeFocused();
  await page.keyboard.press("Enter");
  await page.getByRole("link", { name: /review · 4/i }).focus();
  await page.keyboard.press("Enter");
  await page.getByRole("link", { name: "Investigate", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/mode=investigate/);
  const origin = page.url();
  await page.getByRole("link", { name: "Ask", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Ask", exact: true })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(origin);
  const overflow = await page.evaluate(() => [...document.querySelectorAll("main *")]
    .filter((element) => element.getBoundingClientRect().right > window.innerWidth)
    .map((element) => ({ tag: element.tagName, className: element.className })));
  expect(overflow).toEqual([]);
});

test("shows the truthful empty state when stored evidence has no eligible transitions", async ({ page }) => {
  // One worker is required: other cases must never observe this temporary fixture state.
  const jdbcUrl = process.env.SPRING_DATASOURCE_URL;
  const database = jdbcUrl ? new URL(jdbcUrl.replace(/^jdbc:/, "")).pathname.slice(1) : null;
  if (database && !/^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(database)) throw new Error("Invalid isolated test database name");
  const sql = (input: string) => execFileSync("docker", [
    "compose", "exec", "-T", ...(database ? ["-e", `POSTGRES_DB=${database}`] : []), "postgres", "sh", "-c",
    'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At',
  ], { cwd: "..", input, encoding: "utf8" }).trim();
  const snapshot = "select json_agg(row_to_json(events) order by id) from (select id, represented_at_ms from league_analysis.match_event where match_id = 'NA1_9000000001') events;";
  const before = sql(snapshot);
  expect(JSON.parse(before)).toHaveLength(12);
  try {
    // Bounded, idempotent, exact-match-only update. No event or provenance is deleted.
    sql("update league_analysis.match_event set represented_at_ms = represented_at_ms + 10000000 where match_id = 'NA1_9000000001' and represented_at_ms between 0 and 2076000;");
    await page.goto(matchRoute);
    await expect(page.getByRole("heading", { name: "No supported match transitions" })).toBeVisible();
    await expect(page.getByTestId("transition-marker")).toHaveCount(0);
  } finally {
    sql("update league_analysis.match_event set represented_at_ms = represented_at_ms - 10000000 where match_id = 'NA1_9000000001' and represented_at_ms between 10000000 and 12076000;");
    expect(sql(snapshot)).toBe(before);
  }
  await page.goto(matchRoute);
  await expect(page.getByTestId("transition-marker")).toHaveCount(4);
});
