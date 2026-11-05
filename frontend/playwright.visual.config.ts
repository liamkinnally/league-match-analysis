import { executablePath } from "./test-browser.cjs";
import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  globalSetup: "./playwright-browser-check.ts",
  testDir: "./e2e-visual",
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: [["list"]],
  snapshotPathTemplate: "{testDir}/snapshots/{arg}{ext}",
  use: {
    launchOptions: { executablePath, chromiumSandbox: process.env.LEAGUE_VISUAL_CONTAINER !== "1" },
    baseURL: "http://127.0.0.1:3000",
    colorScheme: "light",
    locale: "en-US",
    reducedMotion: "reduce",
    screenshot: "only-on-failure",
    timezoneId: "UTC",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium-linux",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: "node .next/standalone/server.js",
    url: "http://127.0.0.1:3000",
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      HOSTNAME: "127.0.0.1",
      UI_LAB_ENABLED: "1",
      LEAGUE_ANALYSIS_RUNTIME: "verification",
    },
  },
});
