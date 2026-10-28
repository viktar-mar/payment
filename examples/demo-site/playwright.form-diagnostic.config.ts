import {defineConfig} from '@playwright/test';
process.env.PLAYWRIGHT_NO_COPY_PROMPT='1';
export default defineConfig({
  testDir:'./tests',testMatch:'form-load-diagnostic.spec.ts',workers:1,retries:0,fullyParallel:false,
  timeout:1020000,globalTimeout:1025000,outputDir:'test-results',preserveOutput:'never',
  reporter:[['./tests/support/form-load-diagnostic-reporter.ts']],
  use:{trace:'off',screenshot:'off',video:'off'},
});
