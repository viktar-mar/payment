import {defineConfig,devices} from '@playwright/test';
import {billingContractBrowserArgs} from './tests/support/stripe-billing-contract-redirects';

process.env.PLAYWRIGHT_NO_COPY_PROMPT='1';
export default defineConfig({
  testDir:'./tests',testMatch:'stripe-billing-initial-3ds.spec.ts',
  workers:1,retries:0,fullyParallel:false,timeout:2880000,globalTimeout:3000000,
  outputDir:'test-results',preserveOutput:'never',
  reporter:[['./tests/support/stripe-billing-initial-3ds-reporter.ts']],
  projects:[{name:'owned-initial-3ds',use:{...devices['Desktop Chrome'],channel:'chrome',
    trace:'off',video:'off',screenshot:'off',launchOptions:{args:[...billingContractBrowserArgs]}}}],
});
