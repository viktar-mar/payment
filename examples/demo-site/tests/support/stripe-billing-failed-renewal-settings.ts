import {fileURLToPath} from 'node:url';
import type {TestInfo} from '@playwright/test';

/** Check the resolved worker project, including use.trace forced by the CLI. */
export function failedRenewalRunSettingsAllowed(info:TestInfo):boolean {
  const {config,project}=info;
  const {use}=project;
  const reporters=config.reporter;
  const dedicated=fileURLToPath(new URL('./stripe-billing-failed-renewal-reporter.ts',import.meta.url));
  return !process.env.PW_TEST_REPORTER && reporters.length===1 && reporters[0]?.[0]===dedicated && reporters[0][1]===undefined &&
    config.workers===1 && config.fullyParallel===false && config.preserveOutput==='never' &&
    project.retries===0 && project.repeatEach===1 && info.retry===0 && info.repeatEachIndex===0 &&
    use.trace==='off' && use.screenshot==='off' && use.video==='off' &&
    use.contextOptions?.recordHar===undefined && use.contextOptions?.recordVideo===undefined;
}
