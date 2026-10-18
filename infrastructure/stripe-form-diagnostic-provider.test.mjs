import test from 'node:test';
import assert from 'node:assert/strict';
const module=await import('./stripe-form-diagnostic-provider.mjs').catch(()=>({}));
const keys={secretKey:'sk_test_A00112233445566778899',publishableKey:'pk_test_B00112233445566778899'};
const account={id:'acct_1UPiMpELswAd8tpR',object:'account'};
const intent={id:'pi_TestOnly',object:'payment_intent',livemode:false,status:'requires_payment_method',amount:2000,currency:'eur',amount_received:0,payment_method:null,latest_charge:null,customer:null,setup_future_usage:null,on_behalf_of:null,transfer_data:null};
function fixture(change=()=>{}) {
  assert.equal(typeof module.createFormDiagnosticProvider,'function');
  const calls=[];let now=1000;
  const deps={now:()=>now,fetch:async(url,settings)=>{calls.push({url,settings});const body=url.endsWith('/account')?structuredClone(account):structuredClone(intent);
    const output={body,version:'2026-09-30.endive',redirected:false,status:200};change(output);
    const response=new Response(JSON.stringify(output.body),{status:output.status,headers:{'Stripe-Version':output.version}});
    Object.defineProperty(response,'redirected',{value:output.redirected});return response;}};
  return {reader:module.createFormDiagnosticProvider(keys,deps),calls,limits:{deadline:10000},expire:()=>now=10001};
}
test('private provider proves default account/version without override or Connect headers',async()=>{
  const f=fixture();assert.equal(await f.reader.verifyBinding(f.limits),true);assert.equal(await f.reader.verifyIntent('pi_TestOnly',f.limits),true);
  assert.deepEqual(f.calls.map(c=>c.url),['https://api.stripe.com/v1/account','https://api.stripe.com/v1/payment_intents/pi_TestOnly']);
  for(const c of f.calls){assert.equal(c.settings.method,'GET');assert.equal(c.settings.redirect,'error');assert.deepEqual(Object.keys(c.settings.headers).sort(),['Accept','Authorization']);}
  assert.equal(JSON.stringify(f.reader).includes(keys.secretKey),false);
});
for(const [name,change] of [['account',r=>r.body.id='acct_foreign'],['version',r=>r.version='old'],['live',r=>r.body.livemode=true],['redirect',r=>r.redirected=true],['oversize',r=>r.body.padding='x'.repeat(65537)]])
test('private provider rejects '+name+' without replay',async()=>{const f=fixture(change);await assert.rejects(f.reader.verifyBinding(f.limits));assert.equal(f.calls.length,1);});
for(const [field,value] of [['amount',2001],['currency','usd'],['status','processing'],['amount_received',1],['payment_method','pm_x'],['latest_charge','ch_x'],['customer','cus_x'],['setup_future_usage','off_session'],['on_behalf_of','acct_other'],['transfer_data',{}]])
test('no-confirmation proof rejects '+field,async()=>{const f=fixture(r=>{if(r.body.object==='payment_intent')r.body[field]=value;});await f.reader.verifyBinding(f.limits);await assert.rejects(f.reader.verifyIntent('pi_TestOnly',f.limits));});
test('provider deadline never renews and late responses fail',async()=>{const f=fixture();await f.reader.verifyBinding(f.limits);await assert.rejects(f.reader.verifyIntent('pi_TestOnly',{deadline:10001}));assert.equal(f.calls.length,1);f.expire();await assert.rejects(f.reader.verifyBinding(f.limits));assert.equal(f.calls.length,1);});
test('late account response cannot unlock a provider Intent read',async()=>{
  let now=1000,calls=0;const reader=module.createFormDiagnosticProvider(keys,{now:()=>now,fetch:async()=>{calls++;now=10001;return new Response(JSON.stringify(account),{headers:{'Stripe-Version':'2026-09-30.endive'}});}});
  await assert.rejects(reader.verifyBinding({deadline:10000}));await assert.rejects(reader.verifyIntent('pi_TestOnly',{deadline:10000}));assert.equal(calls,1);
});
