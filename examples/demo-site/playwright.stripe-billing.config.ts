import {defineConfig,devices} from '@playwright/test';
process.env.PLAYWRIGHT_NO_COPY_PROMPT='1';
export default defineConfig({testDir:'./tests',testMatch:'stripe-billing.spec.ts',workers:1,retries:0,fullyParallel:false,
 // 45-minute owner budget + 120-second cleanup + 60-second setup/scheduling headroom.
 timeout:2880000,globalTimeout:3000000,expect:{timeout:15000},outputDir:'test-results',preserveOutput:'never',
 reporter:[['./tests/support/stripe-billing-reporter.ts']],
 use:{...devices['Desktop Chrome'],trace:'off',screenshot:'off',video:'off'}});
