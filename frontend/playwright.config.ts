import { defineConfig, devices } from "@playwright/test";

const frontendPort = process.env.E2E_FRONTEND_PORT ?? "3000";
const backendPort = process.env.E2E_BACKEND_PORT ?? "8080";
const backendOrigin = `http://127.0.0.1:${backendPort}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: `http://127.0.0.1:${frontendPort}`,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: `cd ../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,e2e -Dspring-boot.run.useTestClasspath=true -Dspring-boot.run.additional-classpath-elements=target/test-classes -Dspring-boot.run.arguments=--server.port=${backendPort}`,
      url: `${backendOrigin}/actuator/health`,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
    {
      command: `BACKEND_URL=${backendOrigin} npm run dev -- --port ${frontendPort}`,
      url: `http://127.0.0.1:${frontendPort}`,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
  ],
});
