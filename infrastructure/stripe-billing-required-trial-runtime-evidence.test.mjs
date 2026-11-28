import assert from 'node:assert/strict';
import {createHash,createHmac} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {createReceiver} from './callback-receiver.mjs';

const id=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const failure=/^Error: billing_REQUIRED_TRIAL_EVIDENCE$/;
const seedQuery=await readFile(new URL('./stripe-billing-required-trial-seed.sql',import.meta.url),'utf8');
const proofQuery=await readFile(new URL('./stripe-billing-required-trial-proof.sql',import.meta.url),'utf8');

function scenario() {
 const scope={runId:id(1),expectedSource:'a'.repeat(40),siteKey:'s5a-'+id(1).replaceAll('-',''),accountId:id(2),
  siteId:id(3),customerId:id(4),productId:id(5),priceId:id(6),subscriptionId:id(7),operationId:id(8),createKeyHash:'b'.repeat(64),
  priceReference:'price_fixture',trialPermitKeyHash:'d'.repeat(64),endpoints:{demo:'http://127.0.0.1:18081'}};
 const configuration={selectedCase:'initial_required_trial',runId:scope.runId,expectedSource:scope.expectedSource,
  ports:{demo:18081},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}};
 const started=Math.floor(Date.now()/1000)*1000-30000;
 const eventAt=new Date(started+1000).toISOString();
 const trialStart=new Date(started),trialEnd=new Date(started+7*86400000);
 const seed={customer_reference:'cus_fixture',setup_reference:'seti_fixture',method_reference:'pm_fixture',subscription_reference:'sub_fixture',
  item_reference:'si_fixture',invoice_reference:'in_fixture',provider_price_id:scope.priceReference,provider_product_id:'prod_fixture',
  trial_start:trialStart,trial_end:trialEnd};
 const seedArgs=[id(1),id(2),id(3),id(4),id(5),id(6),id(7),id(8),'b'.repeat(64)];
 const proofArgs=[...seedArgs,'cus_fixture','seti_fixture','pm_fixture','sub_fixture','si_fixture','in_fixture',trialStart.toISOString(),trialEnd.toISOString()];
 const payload={access_allowed:true,access_valid_until:new Date(started+301000).toISOString(),billing_record_id:null,customer_id:id(4),
  event_id:id(10),event_type:'subscription.updated',occurred_at:eventAt,payment_id:null,product_id:id(5),revision:2,schema_version:4,
  site_id:id(3),state:'TRIALING',subscription_id:id(7)};
 const callback={callback_id:id(10),callback_type:'subscription.updated',callback_state:'delivered',callback_bound:true,permit_bound:true,payload};
 const snapshot=()=>{
  const evaluated=new Date();
  return {run_id:id(1),billing_record_id:id(9),trial_started_event_id:id(10),revision:'2',trial_start:trialStart,trial_end:trialEnd,
   access_evaluated_at:evaluated,access_valid_until:new Date(evaluated.getTime()+300000)};
 };
 const connectionOptions={host:'127.0.0.1',port:15432,database:'payment',user:'payment',password:'owned-db-test'};
 const calls=[];
 let authorized=false,open=false;
 const state={scope,configuration,seedArgs,proofArgs,payload,callback,calls};
 const fixture={resources:()=>scope,async inspectCallbacks(){
  assert.ok(!authorized&&!open);
  calls.push('inspection');
  return state.inspect?state.inspect():{count:1,duplicates:0,events:[{event_id:id(10),event_type:'subscription.updated',count:1,
   sha256:createHash('sha256').update(JSON.stringify(payload)).digest('hex')}]};
 }};
 const bootstrap={async withDatabaseCredentials(run,limits){
  assert.ok(!authorized&&!open);
  assert.equal(limits.signal.aborted,false);
  authorized=true;
  try{return await run(connectionOptions);}
  finally{authorized=false;}
 }};
 const cli={async proveOwnedBillingRequiredTrial(owned,readOnly,limits){
  assert.equal(owned,fixture);
  assert.ok(!authorized&&!open);
  if(state.readRequests)await state.readRequests(readOnly,limits);
  const seedRows=await readOnly(state.replaceSeedRequest??{mode:'read only',query:seedQuery,args:seedArgs},limits);
  assert.deepEqual(seedRows,[seed]);
  assert.ok(!authorized&&!open);
  const [row]=await readOnly({mode:'read only',query:proofQuery,args:proofArgs},limits);
  if(state.afterRead)await state.afterRead(readOnly,limits);
  return {trialVerified:true,noCash:true,runId:row.run_id,billingRecordId:row.billing_record_id,
   trialStartedEventId:row.trial_started_event_id,revision:2,trialStart:row.trial_start.toISOString(),trialEnd:row.trial_end.toISOString(),
   accessEvaluatedAt:row.access_evaluated_at.toISOString(),accessValidUntil:row.access_valid_until.toISOString(),...state.patchProof};
 }};
 const connect=options=>{
  assert.ok(authorized&&!open);
  assert.deepEqual({...options,onnotice:options.onnotice.name},
   {...connectionOptions,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:'onnotice',debug:false});
  open=true;
  return {async begin(mode,read){
   assert.equal(mode,'read only');
   let timeout=false;
   return read({async unsafe(query,args){
    assert.ok(authorized&&open);
    if(query==="set local statement_timeout='5000'"){timeout=true;return;}
    assert.equal(timeout,true);
    if(query===seedQuery){calls.push('seed');assert.deepEqual(args,seedArgs);return [seed];}
    if(query===proofQuery){calls.push('proof');assert.deepEqual(args,proofArgs);return [snapshot()];}
    calls.push('outbox');
    assert.deepEqual(args,[...proofArgs,'d'.repeat(64),'http://127.0.0.1:18081']);
    assert.match(query,/g.confirmation_key_digest=\$18::text/);
    assert.match(query,/g.client_origin=\$19::text/);
    assert.match(query,/not exists\(select 1 from subscription_confirmation_chains/);
    const row={...snapshot(),...callback};
    return state.outbox?state.outbox(row):[row];
   }});
  },async end(options){assert.ok(open);assert.deepEqual(options,{timeout:0});open=false;calls.push('close');}};
 };
 const run=async(limits={deadline:Date.now()+2000})=>{
  const {proveBillingRequiredTrialRuntime}=await import('./stripe-billing-required-trial-runtime-evidence.mjs');
  return proveBillingRequiredTrialRuntime({configuration,bootstrap,cli,fixture},limits,{connect});
 };
 return {...state,run,state};
}

test('proves only the consumed trial with one matching signed receiver callback',async()=>{
 const f=scenario();let handler;
 createReceiver({}, {createServer:callback=>{handler=callback;return {on(){}};}});
 const request=async(url,body,headers={})=>{
  let status,payload;
  const req={url,method:body===undefined?'GET':'POST',headers,rawHeaders:Object.entries(headers).flat(),
   async *[Symbol.asyncIterator](){if(body!==undefined)yield Buffer.from(body);}};
  await handler(req,{writeHead(code){status=code;},end(value){payload=value&&JSON.parse(value);}});
  return {status,payload};
 };
 const secret=Buffer.alloc(32,23),media={'content-type':'application/json'};
 const config=await request('/configure',JSON.stringify({secret:secret.toString('base64url'),secret_version:1}),media);
 assert.equal(config.status,204);
 const body=JSON.stringify(f.payload),timestamp=String(Math.floor(Date.now()/1000));
 const signature=createHmac('sha256',secret).update(timestamp+'.').update(body).digest('hex');
 const response=await request('/callback',body,{...media,'payment-event-id':id(10),
  'payment-timestamp':timestamp,'payment-secret-version':'1','payment-signature':'v1='+signature});
 assert.equal(response.status,204);
 f.state.inspect=async()=>(await request('/inspection/digests')).payload;
 assert.equal(await f.run(),true);
 assert.deepEqual(f.calls,['seed','close','proof','close','outbox','close','inspection']);
});

for(const [name,change] of [
 ['wrong consumed permit',f=>{f.callback.permit_bound=false;}],
 ['missing permit hash',f=>{delete f.scope.trialPermitKeyHash;}],
 ['reused CREATE key for permit',f=>{f.scope.trialPermitKeyHash=f.scope.createKeyHash;}],
 ['wrong demo origin',f=>{f.scope.endpoints.demo='http://127.0.0.1:18082';}],
 ['wrong acceptance case',f=>{f.configuration.selectedCase='initial_success';}],
 ['foreign trial owner',f=>{f.scope.expectedSource='c'.repeat(40);}],
 ['unverified no-cash proof',f=>{f.state.patchProof={noCash:false};}],
 ['different journal event',f=>{f.state.patchProof={trialStartedEventId:id(11)};}],
 ['missing current proof',f=>{f.state.outbox=()=>[];}],
 ['wrong callback configuration',f=>{f.callback.callback_bound=false;}],
 ['extra outbox event',f=>{f.state.outbox=row=>[row,{...row,callback_id:id(11)}];}],
 ['failed callback delivery',f=>{f.callback.callback_state='invalid';}],
 ['incorrect payload shape',f=>{f.payload.extra=true;}],
 ['cash payment in a trial callback',f=>{f.payload.payment_id=id(11);}],
 ['wrong lifecycle state',f=>{f.payload.state='ACTIVE';}],
 ['incorrect callback expiry',f=>{f.payload.access_valid_until=new Date().toISOString();}],
 ['wrong receiver digest',f=>{f.state.inspect=()=>({count:1,duplicates:0,events:[{event_id:id(10),event_type:'subscription.updated',count:1,sha256:'0'.repeat(64)}]});}],
 ['duplicate signed callback',f=>{f.state.inspect=()=>({count:1,duplicates:1,events:[{event_id:id(10),event_type:'subscription.updated',count:2,sha256:createHash('sha256').update(JSON.stringify(f.payload)).digest('hex')}]});}],
 ['extra signed callback',f=>{f.state.inspect=()=>({count:2,duplicates:0,events:[{event_id:id(10),event_type:'subscription.updated',count:1,sha256:createHash('sha256').update(JSON.stringify(f.payload)).digest('hex')},{event_id:id(11),event_type:'payment.succeeded',count:1,sha256:'0'.repeat(64)}]});}],
 ['foreign database request',f=>{f.state.readRequests=(readOnly,limits)=>readOnly({mode:'read only',query:'select 1',args:f.seedArgs},limits);}],
 ['foreign proof arguments',f=>{f.seedArgs[2]=id(11);}],
])test(`rejects ${name}`,async()=>{const f=scenario();change(f);await assert.rejects(f.run(),failure);});

test('polls only a pending exact callback',async()=>{
 const f=scenario();let polls=0;
 f.state.outbox=row=>[{...row,callback_state:++polls===1?'pending':'delivered'}];
 assert.equal(await f.run(),true);
 assert.deepEqual(f.calls,['seed','close','proof','close','outbox','outbox','close','inspection']);
});

test('closes the credential connection on abort and never accepts the late result',async()=>{
 const f=scenario(),controller=new AbortController();let release;
 f.state.outbox=row=>new Promise(resolve=>{
  release=()=>resolve([row]);
  controller.abort();
 });
 await assert.rejects(f.run({deadline:Date.now()+2000,signal:controller.signal}),failure);
 release();
 assert.deepEqual(f.calls,['seed','close','proof','close','outbox','close']);
});

test('executes only the allowlisted SQL even when a request changes after validation',async()=>{
 const f=scenario();
 f.state.replaceSeedRequest={mode:'read only',args:f.seedArgs,
  get query(){delete this.query;this.query='select 1';return seedQuery;}};
 assert.equal(await f.run(),true);
 assert.deepEqual(f.calls,['seed','close','proof','close','outbox','close','inspection']);
});

test('closes the SQL capability after both owned statements',async()=>{
 const f=scenario();
 f.state.afterRead=(readOnly,limits)=>readOnly({mode:'read only',query:null,args:f.proofArgs},limits);
 await assert.rejects(f.run(),failure);
 assert.deepEqual(f.calls,['seed','close','proof','close']);
});
