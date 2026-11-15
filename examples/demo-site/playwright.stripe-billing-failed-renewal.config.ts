import {defineConfig,devices} from '@playwright/test';
process.env.PLAYWRIGHT_NO_COPY_PROMPT='1';
export default defineConfig({
  testDir:'./tests',testMatch:'stripe-billing-failed-renewal.spec.ts',
  workers:1,retries:0,fullyParallel:false,timeout:2880000,globalTimeout:3000000,
  outputDir:'test-results',preserveOutput:'never',
  reporter:[['./tests/support/stripe-billing-failed-renewal-reporter.ts']],
  use:{trace:'off',screenshot:'off',video:'off'},
  projects:[{name:'owned-failed-renewal-manual-chrome',use:{...devices['Desktop Chrome'],channel:'chrome',headless:false}}],
});
