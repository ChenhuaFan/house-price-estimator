import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: [["line"], ["html", { open: "never" }]],
  expect: { timeout: 10_000 },
  timeout: 45_000,
  webServer: process.env.E2E_MOCKS === "1" ? [
    { command: "node tests/mock-backends.mjs", port: 18099, reuseExistingServer: false, timeout: 20_000 },
    { command: "pnpm dev --hostname 127.0.0.1 --port 13000", port: 13000, reuseExistingServer: false, timeout: 60_000, env: { ESTIMATOR_SERVICE_URL: "http://127.0.0.1:18081", MARKET_SERVICE_URL: "http://127.0.0.1:18080", PORTAL_PUBLIC_ORIGIN: "http://127.0.0.1:13000" } },
  ] : undefined,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? (process.env.E2E_MOCKS === "1" ? "http://127.0.0.1:13000" : "http://127.0.0.1:3000"),
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "firefox", use: { ...devices["Desktop Firefox"] } },
    { name: "webkit", use: { ...devices["Desktop Safari"] } },
  ],
});
