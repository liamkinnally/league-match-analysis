import { defineConfig, devices } from "@playwright/test";

const port = process.env.E2E_SAMPLE_PORT ?? "3543";
const origin = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: "./e2e-sample",
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: origin,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium-sample", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `npm run dev -- --port ${port}`,
    url: `${origin}/api/health`,
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      VERCEL: "",
      VERCEL_ENV: "",
      LEAGUE_ANALYSIS_RUNTIME: "verification",
      PREVIEW_DATA_SOURCE: "sample",
      UI_LAB_ENABLED: "",
      BACKEND_URL: "",
      BACKEND_SERVICE_TOKEN: "",
    },
  },
});
