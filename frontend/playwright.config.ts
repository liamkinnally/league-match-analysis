import { defineConfig, devices } from "@playwright/test";
import { localConfig } from "../scripts/lib/dev-config.cjs";

const ports = {
  DEV_FRONTEND_PORT: process.env.E2E_FRONTEND_PORT ?? "3100",
  DEV_BACKEND_PORT: process.env.E2E_BACKEND_PORT ?? "8180",
  DEV_POSTGRES_PORT: process.env.E2E_POSTGRES_PORT ?? "5549",
};
const runtime = localConfig("fixture", { ...process.env, ...ports });
// SQL fixture operations in the specs must target the same isolated Compose project.
Object.assign(process.env, runtime.databaseEnv, {
  E2E_FRONTEND_PORT: String(runtime.frontendPort),
  E2E_BACKEND_PORT: String(runtime.backendPort),
  E2E_POSTGRES_PORT: String(runtime.postgresPort),
  SPRING_DATASOURCE_URL: runtime.backendEnv.SPRING_DATASOURCE_URL,
  RIOT_API_KEY: "", RIOT_PUBLIC_LOOKUP_ENABLED: "false",
});

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: runtime.frontendOrigin,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "../scripts/dev fixture",
      env: { ...runtime.composeEnv, ...ports },
      url: `${runtime.frontendOrigin}/api/health`,
      reuseExistingServer: false,
      timeout: 180_000,
      gracefulShutdown: { signal: "SIGTERM", timeout: 15_000 },
    },
  ],
});
