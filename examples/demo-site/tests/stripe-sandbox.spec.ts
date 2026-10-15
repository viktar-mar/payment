import {test} from '@playwright/test';
import {openStripeSandboxFixture,runSandboxCard,type SandboxFixture} from './support/stripe-sandbox-fixtures';

test.describe.serial('explicitly owned Stripe Sandbox acceptance',()=>{
  let fixture:SandboxFixture;
  // Runs before browser/page setup: both flags, durable state, container ownership and platform binding checks.
  test.beforeAll(async()=>{fixture=await openStripeSandboxFixture();});
  test.afterAll(async()=>{await fixture?.close();});
  for(const name of ['success','decline','sca'] as const)
    test(`Sandbox ${name} server-confirmed card journey`,async({browser})=>{await runSandboxCard(browser,fixture,name);});
});
