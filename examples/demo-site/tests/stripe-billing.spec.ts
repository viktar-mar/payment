import {test} from '@playwright/test';
import {createOwnedBillingAcceptance} from '../../../infrastructure/stripe-billing-acceptance.mjs';
import {snapshotBillingAcceptanceEnvironment} from '../../../infrastructure/stripe-billing-acceptance-options.mjs';
import {runInitialBillingBrowser} from './support/stripe-billing-acceptance';

let owner:Awaited<ReturnType<typeof createOwnedBillingAcceptance>>;
test.beforeAll(async()=>{
 const raw=process.env,env=snapshotBillingAcceptanceEnvironment(raw);
 if(!env)throw new Error('billing_OPT_IN_REQUIRED');
 const ports={backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
 for(const name of Object.keys(ports) as (keyof typeof ports)[]){const value=raw['PAYMENT_STRIPE_SANDBOX_PORT_'+name.toUpperCase()];ports[name]=typeof value==='string'&&/^\d{4,5}$/.test(value)?Number(value):0;}
 owner=await createOwnedBillingAcceptance({env,runId:raw.PAYMENT_STRIPE_SANDBOX_RUN_ID,expectedSource:raw.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE,
  daemon:raw.PAYMENT_STRIPE_SANDBOX_DAEMON,ports,approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_success'});
});
test('INITIAL_NON_CLOCK: fresh EUR20 MONTH original v2 cash and signed callbacks',async({browser})=>{
 const controller=new AbortController(),limits={deadline:Date.now()+2700000,signal:controller.signal};
 try{const result=await owner.run((port,scoped)=>runInitialBillingBrowser(browser,port,scoped),limits);if(result.scope!=='INITIAL_NON_CLOCK'||!result.initialAccepted||result.full5bAccepted)throw new Error('billing_NOT_ACCEPTED');}
 finally{controller.abort();await owner.close({deadline:Date.now()+120000});}
});
