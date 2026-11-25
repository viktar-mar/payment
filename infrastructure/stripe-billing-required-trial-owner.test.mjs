import assert from 'node:assert/strict';
import test from 'node:test';
import * as acceptance from './stripe-billing-acceptance.mjs';
import {validateBillingAcceptanceOptions} from './stripe-billing-acceptance-options.mjs';
import {createBillingFixture} from './stripe-billing-provision.mjs';
import {provisionHarness} from './fixtures/failed-renewal-provision-harness.mjs';
import {id} from './fixtures/failed-renewal-descriptors.mjs';

const selectedCase='initial_required_trial';
const profile='PAYMENT_STRIPE_BILLING_REQUIRED_TRIAL_CLI_PROFILE_RUN_ID';
const subnet='PAYMENT_STRIPE_BILLING_REQUIRED_TRIAL_SUBNET';
const configuration={
 env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:selectedCase,[profile]:id(50),[subnet]:'10.254.81.0/28'},
 selectedCase,runId:id(1),expectedSource:'a'.repeat(40),daemon:'unix:///Users/test/docker.sock',
 ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
 approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},
};
const scope={subscriptionId:id(7),operationId:id(8)};
const limits=()=>({deadline:Date.now()+3000});
const yes=async()=>true;

test('required trial is admitted with only a trial CREATE and one SetupIntent permit and confirmation',async()=>{
 const admitted=validateBillingAcceptanceOptions(configuration);
 assert.equal(admitted?.protocol,'BROWSER_CONFIRM_V1');
 assert.deepEqual(admitted?.budget,{create:1,setupPermit:1,setupConfirm:1});
 assert.deepEqual(validateBillingAcceptanceOptions(admitted),admitted);
 const withoutProfile={...configuration.env};
 delete withoutProfile[profile];
 assert.equal(validateBillingAcceptanceOptions({...configuration,env:withoutProfile}),null);
 assert.equal(validateBillingAcceptanceOptions({...configuration,protocol:'SERVER_CONFIRM_V2'}),null);
 assert.equal(validateBillingAcceptanceOptions({...configuration,budget:{create:1,confirm:1}}),null);
 for(const key of [profile,subnet]){
  const env={...configuration.env,PAYMENT_STRIPE_BILLING_CASE:'initial_3ds'};
  delete env[key===profile?subnet:profile];
  assert.equal(validateBillingAcceptanceOptions({...configuration,selectedCase:'initial_3ds',env}),null);
 }
 assert.equal(validateBillingAcceptanceOptions({...configuration,env:{...configuration.env,[profile]:configuration.runId}}),null);
 await assert.rejects(acceptance.createOwnedBillingAcceptance(configuration),/OPT_IN_REQUIRED/);
});

for(const fault of ['none','wrong price','missing setup','foreign setup','duplicate permit','duplicate setup','duplicate ack','same key','missing permit','payment confirmation','early create ack','uncertain permit','proof','cleanup','uncertain customer']){
 test('required trial owner and real fixture with '+fault,async()=>{
  const f=await provisionHarness({configuration,factory:createBillingFixture,
   onFetch(path,settings,response){
    if(!path.endsWith('/publish'))return response;
    return response.json().then(value=>Response.json({...value,trial_days:7,trial_payment_method_policy:fault==='wrong price'?'OPTIONAL_DURING_TRIAL':'REQUIRED_BEFORE_TRIAL'}));
   },
   onWrite(name){
    if(fault==='uncertain customer'&&name.endsWith('-customer.json')||fault==='uncertain permit'&&name.endsWith('-trial-permit-dispatch.json'))throw Error('private');
   }});
  const cli={validateBinding:yes,startListener:yes,assertBillingListener:yes,stopListener:yes};
  const bootstrap={prepare:yes,start:yes,resources:()=>({cliDirectory:'inert'}),stop:async()=>fault!=='cleanup'};
  let proofs=0;
  const owner=await acceptance.createOwnedBillingRequiredTrial(configuration,{
   preflight:yes,createBootstrap:()=>bootstrap,createCli:()=>cli,login:()=>assert.fail('must reuse prior login'),
   createFixture:()=>f.fixture,proveRequiredTrial:async inputs=>{
    assert.equal(inputs.fixture,f.fixture);
    assert.equal(inputs.cli,cli);
    assert.equal(inputs.bootstrap,bootstrap);
    proofs++;return fault!=='proof';
   }});
  const outcome=owner.run(async port=>{
   assert.deepEqual(Object.keys(port).sort(),['acknowledge','acknowledgeCustomer','acknowledgeTrialSetup','beforeDispatch','beforeTrialSetup','resources','withOtpCode']);
   try{await port.acknowledgeCustomer({customerId:id(4),siteId:id(3)});}catch(error){if(fault!=='uncertain customer')throw error;}
   if(fault==='early create ack'){
    const sending=port.beforeDispatch('create',id(11));
    await Promise.all([sending,port.acknowledge('create',scope)]);
    return true;
   }
   await port.beforeDispatch('create',id(11));
   await port.acknowledge('create',scope);
   if(fault==='payment confirmation')await port.beforeDispatch('confirm',id(12));
   const permit=()=>port.beforeTrialSetup('permit',scope,fault==='same key'?id(11):id(12));
   if(fault!=='missing permit')await permit();
   if(fault==='duplicate permit')await permit();
   if(fault==='missing setup')return true;
   const confirm=()=>port.beforeTrialSetup('setup',fault==='foreign setup'?{...scope,operationId:id(99)}:scope);
   await confirm();
   if(fault==='duplicate setup')await confirm();
   await port.acknowledgeTrialSetup(scope);
   if(fault==='duplicate ack')await port.acknowledgeTrialSetup(scope);
   return true;
  },limits());
  const request=f.requests.find(row=>row.path.endsWith('/prices')&&row.method==='POST');
  if(fault==='none'){
   const result=await outcome;
   assert.equal(result.scope,'INITIAL_REQUIRED_TRIAL');
   assert.equal(result.requiredTrialAccepted,true);
   assert.equal(result.initialAccepted,false);
   assert.equal(result.full5bAccepted,false);
   assert.equal(result.cleanupConfirmed,true);
   assert.deepEqual(f.writes.slice(-5).map(row=>row.name.slice(12)),['create-dispatch.json','create-ack.json','trial-permit-dispatch.json','trial-setup-dispatch.json','trial-setup-ack.json']);
   assert.ok(!JSON.stringify(f.writes.slice(-3)).includes(id(12)));
   await assert.rejects(owner.run(async()=>true,limits()),/SINGLE_USE/);
  }else{
   await assert.rejects(outcome,/^Error: billing_[A-Z_]+$/);
   assert.equal(owner.resources().requiredTrialAccepted,false);
  }
  const price=request??f.requests.find(row=>row.path.endsWith('/prices')&&row.method==='POST');
  assert.equal(price.body.trial_days,7);
  assert.equal(price.body.trial_payment_method_policy,'REQUIRED_BEFORE_TRIAL');
  assert.equal(proofs,['none','proof','cleanup'].includes(fault)?1:0);
 });
}
