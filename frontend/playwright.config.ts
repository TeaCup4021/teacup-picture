import { defineConfig, devices } from "@playwright/test";

const frontendPort = Number(process.env.E2E_FRONTEND_PORT ?? 3000);
const frontendUrl = `http://127.0.0.1:${frontendPort}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: "html",
  use: {
    baseURL: frontendUrl,
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: `pnpm dev -p ${frontendPort}`,
    url: frontendUrl,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      ...process.env,
      NEXT_PUBLIC_API_BASE_URL: process.env.E2E_API_BASE_URL ?? "http://127.0.0.1:8123/api/v1",
    },
  },
});
