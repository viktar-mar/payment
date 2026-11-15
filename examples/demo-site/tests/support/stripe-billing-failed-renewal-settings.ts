import type {TestInfo} from '@playwright/test';

/** Check the resolved worker project, including use.trace forced by the CLI. */
export function failedRenewalRunSettingsAllowed(info:TestInfo):boolean {
  const {config,project}=info;
  const {use}=project;
  return config.workers===1 && config.fullyParallel===false && config.preserveOutput==='never' &&
    project.retries===0 && project.repeatEach===1 && info.retry===0 && info.repeatEachIndex===0 &&
    use.trace==='off' && use.screenshot==='off' && use.video==='off' &&
    use.contextOptions?.recordHar===undefined && use.contextOptions?.recordVideo===undefined;
}
