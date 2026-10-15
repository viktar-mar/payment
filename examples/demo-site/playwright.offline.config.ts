import {defineConfig,devices} from '@playwright/test';
process.env.PLAYWRIGHT_NO_COPY_PROMPT='1';
export default defineConfig({testDir:'./tests',testMatch:'stripe-return-offline.spec.ts',workers:1,retries:0,
  timeout:30000,expect:{timeout:5000},reporter:'line',outputDir:'test-results',preserveOutput:'never',
  use:{...devices['Desktop Chrome'],trace:'off',screenshot:'off',video:'off'}});
