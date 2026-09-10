import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e-package",
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: "list",
  outputDir: "test-results/package-smoke",
  use: {
    baseURL: process.env.PACKAGE_SMOKE_BASE_URL ?? "http://127.0.0.1:3416",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
