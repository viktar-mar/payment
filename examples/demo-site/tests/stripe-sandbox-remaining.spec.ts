import {expect,test} from '@playwright/test';
import {runSandboxCard} from './support/stripe-sandbox-fixtures';
import {openOwnedSandboxRemainingAcceptance,type OwnedSandboxRemainingAcceptance,type OwnedSandboxPublicBinding} from '../../../infrastructure/stripe-sandbox-acceptance.mjs';
import {remainingOnlyOptIn} from '../../../infrastructure/stripe-sandbox-remaining-options.mjs';
import {runOwnedSandboxDonations,withOwnedCardContexts} from './support/stripe-sandbox-acceptance';

test.describe.serial('remaining-only Stripe Sandbox lifecycle acceptance',()=>{
  const deadline=Date.now()+2700000,controller=new AbortController();
  let owned:OwnedSandboxRemainingAcceptance|undefined;
  test.beforeAll(async()=>{
    const env=process.env;
    // Validate raw descriptors and all competing flags before setup or browser authority.
    if(!remainingOnlyOptIn(env))throw new Error('stripe_sandbox_OPT_IN_REQUIRED');
    const ports={backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
    for(const name of Object.keys(ports) as (keyof typeof ports)[]){
      const value=env['PAYMENT_STRIPE_SANDBOX_PORT_'+name.toUpperCase()];ports[name]=typeof value==='string'&&/^\d{4,5}$/.test(value)?Number(value):0;
    }
    // Keep the raw selector visible to the owner's independent admission check.
    owned=await openOwnedSandboxRemainingAcceptance({env,
      runId:env.PAYMENT_STRIPE_SANDBOX_RUN_ID??'',expectedSource:env.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE??'',
      daemon:env.PAYMENT_STRIPE_SANDBOX_DAEMON??'',ports},{deadline,signal:controller.signal});
  });
  test.afterAll(async()=>{controller.abort();await owned?.close({deadline:Date.now()+120000});});
  test('server-confirmed new payment refunds donations OTP and missed webhook only',async({browser})=>{
    if(!owned)throw new Error('stripe_sandbox_OPT_IN_REQUIRED');
    const owner=owned,limits={deadline,signal:controller.signal};
    await owner.run({
      payment:async fixture=>withOwnedCardContexts(browser,limits,async()=>{await runSandboxCard(browser,fixture,'success');}),
      donations:async extras=>{
        const safe=owner.resources();
        if(!safe.endpoints||!safe.site||!safe.price||!safe.campaign)throw new Error('stripe_sandbox_BROWSER_BINDING_PENDING');
        const binding:OwnedSandboxPublicBinding={endpoints:safe.endpoints,site:safe.site,price:safe.price,campaign:safe.campaign};
        return runOwnedSandboxDonations(browser,extras,binding,limits);
      },
      missed:async fixture=>withOwnedCardContexts(browser,limits,async()=>{await runSandboxCard(browser,fixture,'success');}),
    },limits);
    // The owner settles server proofs and owned cleanup before narrow acceptance.
    const safe=owner.resources();
    expect(safe.mode).toBe('REMAINING_5A');expect(safe.remainingAccepted).toBe(true);
    expect(safe.accepted).toBe(false);expect(safe.cleanupConfirmed).toBe(true);
    expect(safe.proof).toEqual({cards:false,payment:true,refunds:true,anonymous:true,emailOtp:true,otp:true,missed:true});
  });
});
