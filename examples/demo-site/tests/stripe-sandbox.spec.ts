import {test} from '@playwright/test';
import {openStripeSandboxFixture,runSandboxCard,type SandboxFixture} from './support/stripe-sandbox-fixtures';
import {openOwnedSandboxAcceptance,type OwnedSandboxAcceptance,type OwnedSandboxPublicBinding} from '../../../infrastructure/stripe-sandbox-acceptance.mjs';
import {runOwnedSandboxDonations,withOwnedCardContexts} from './support/stripe-sandbox-acceptance';

const ownedMode=process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN==='true';
if(ownedMode)test.describe.serial('owned Stripe Sandbox lifecycle acceptance',()=>{
  const deadline=Date.now()+2700000,controller=new AbortController();
  let owned:OwnedSandboxAcceptance;
  test.beforeAll(async()=>{
    const env=process.env;
    const ports={backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
    for(const name of Object.keys(ports) as (keyof typeof ports)[]){
      const value=env['PAYMENT_STRIPE_SANDBOX_PORT_'+name.toUpperCase()];ports[name]=typeof value==='string'&&/^\d{4,5}$/.test(value)?Number(value):0;
    }
    owned=await openOwnedSandboxAcceptance({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:env.PAYMENT_STRIPE_SANDBOX_ENABLED,
      PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:env.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS,PAYMENT_STRIPE_SANDBOX_OWNED_RUN:env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN,
      DEBUG:env.DEBUG,NODE_DEBUG:env.NODE_DEBUG,PWDEBUG:env.PWDEBUG},
      runId:env.PAYMENT_STRIPE_SANDBOX_RUN_ID??'',expectedSource:env.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE??'',
      daemon:env.PAYMENT_STRIPE_SANDBOX_DAEMON??'',ports},{deadline,signal:controller.signal});
  });
  test.afterAll(async()=>{controller.abort();await owned?.close({deadline:Date.now()+120000});});
  test('server-confirmed cards refunds donations OTP and original missed webhook',async({browser})=>{
    const limits={deadline,signal:controller.signal};
    await owned.run({
      cards:async fixture=>withOwnedCardContexts(browser,limits,async()=>{for(const name of ['success','decline','sca'] as const)await runSandboxCard(browser,fixture,name);}),
      donations:async extras=>{
        const safe=owned.resources();
        if(!safe.endpoints||!safe.site||!safe.price||!safe.campaign)throw new Error('stripe_sandbox_BROWSER_BINDING_PENDING');
        const binding:OwnedSandboxPublicBinding={endpoints:safe.endpoints,site:safe.site,price:safe.price,campaign:safe.campaign};
        return runOwnedSandboxDonations(browser,extras,binding,limits);
      },
      missed:async fixture=>withOwnedCardContexts(browser,limits,async()=>{await runSandboxCard(browser,fixture,'success');}),
    },limits);
  });
});
else test.describe.serial('explicitly owned Stripe Sandbox acceptance',()=>{
  let fixture:SandboxFixture;
  // Runs before browser/page setup: both flags, durable state, container ownership and platform binding checks.
  test.beforeAll(async()=>{fixture=await openStripeSandboxFixture();});
  test.afterAll(async()=>{await fixture?.close();});
  for(const name of ['success','decline','sca'] as const)
    test(`Sandbox ${name} server-confirmed card journey`,async({browser})=>{await runSandboxCard(browser,fixture,name);});
});
