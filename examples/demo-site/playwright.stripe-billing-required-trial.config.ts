import {defineConfig,devices} from '@playwright/test';

process.env.PLAYWRIGHT_NO_COPY_PROMPT='1';
export default defineConfig({
  testDir:'./tests',testMatch:'stripe-billing-required-trial.spec.ts',
  workers:1,retries:0,fullyParallel:false,timeout:2880000,globalTimeout:3000000,
  outputDir:'test-results',preserveOutput:'never',
  reporter:[['./tests/support/stripe-billing-required-trial-reporter.ts']],
  projects:[{name:'owned-required-trial',use:{...devices['Desktop Chrome'],channel:'chrome',trace:'off',video:'off',screenshot:'off'}}],
});
