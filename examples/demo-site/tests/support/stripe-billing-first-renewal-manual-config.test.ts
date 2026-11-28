import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {matchesGlob} from 'node:path';
import {fileURLToPath} from 'node:url';
// @ts-expect-error Native Node source type erasure.
import ordinary from '../../playwright.config.ts';

const configPath=fileURLToPath(new URL('../../playwright.stripe-billing-first-renewal-manual.config.ts',import.meta.url));
const modePath=fileURLToPath(new URL('./stripe-billing-first-renewal-browser-mode.ts',import.meta.url));

test('manual renewal config is an explicit visible Chrome lane excluded from ordinary discovery',async()=>{
  assert.equal(existsSync(configPath),true);
  const {default:manual}=await import(configPath);
  assert.equal(manual.testMatch,'stripe-billing-first-renewal.spec.ts');
  assert.equal(manual.workers,1);
  assert.equal(manual.retries,0);
  assert.equal(manual.fullyParallel,false);
  assert.equal(manual.preserveOutput,'never');
  assert.equal(manual.use?.trace,'off');
  assert.equal(manual.use?.screenshot,'off');
  assert.equal(manual.use?.video,'off');
  assert.deepEqual(manual.projects?.map((project:any)=>project.name),['owned-manual-chrome']);
  assert.equal(manual.projects?.[0]?.use?.channel,'chrome');
  assert.equal(manual.projects?.[0]?.use?.headless,false);
  assert.equal((ordinary.testIgnore as string[]).some(pattern=>matchesGlob('stripe-billing-first-renewal.spec.ts',pattern)),true);
});

test('only the exact manual project selects the manual Pay handoff',async()=>{
  assert.equal(existsSync(modePath),true);
  const {firstRenewalBrowserMode}=await import(modePath);
  assert.deepEqual(firstRenewalBrowserMode('owned-manual-chrome'),{manualPay:true});
  for(const name of ['', 'chromium', 'owned-manual-chrome-extra', 'OWNED-MANUAL-CHROME'])assert.equal(firstRenewalBrowserMode(name),undefined);
});
