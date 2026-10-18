import { defineConfig, devices } from "@playwright/test";
// Suppress page snapshots. Playwright still generates matcher/source contexts on
// failure; payload-free assertions/fixture indirection plus preserveOutput avoid retention.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = "1";
const externalOrigin = process.env.PAYMENT_E2E_DEMO_ORIGIN;

export default defineConfig({
  testDir: "./tests",
  testIgnore: ["stripe-sandbox.spec.ts", "**/support/stripe-sandbox-acceptance.test.ts", "form-load-diagnostic.spec.ts", "**/support/form-load-diagnostic*.test.ts"],
  fullyParallel: false,
  workers: 1,
  retries: 1,
  // Cold real-backend provisioning measured 43s; keep the full journey bounded without relaxing condition waits.
  timeout: 90_000,
  expect: { timeout: 12_000 },
  outputDir: "test-results",
  preserveOutput: "never",
  reporter: [["line"]],
  use: {
    baseURL: externalOrigin ?? "http://127.0.0.1:4173",
    screenshot: "off",
    trace: "off",
    video: "off",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: externalOrigin ? undefined : {
    command: "pnpm exec vite --force --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: false,
    timeout: 30_000,
    stdout: "ignore",
    stderr: "pipe",
  },
});
