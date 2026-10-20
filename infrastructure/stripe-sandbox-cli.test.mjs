import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';

test('factory reuses one own-data env capture despite a substituting Proxy get trap',async()=>{
  const {createSandboxCliAdapter:create}=await import('./stripe-sandbox-cli.mjs');
  for(const exotic of [false,true]){
    let gets=0,captures=0,configuration=0,dependencies=0;
    const env=Object.assign(exotic?Object.create({unrelated:'ignored'}):{},{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'});
    const input=new Proxy({env,get runId(){configuration++;throw null;}},{get(target,key){if(key==='env'){gets++;return {};}return Reflect.get(target,key);},getOwnPropertyDescriptor(target,key){if(key==='env')captures++;return Reflect.getOwnPropertyDescriptor(target,key);}});
    const trap=new Proxy({},{get(){dependencies++;throw null;}});
    await assert.rejects(()=>create(input,trap,trap));
    assert.equal(gets,0);assert.equal(captures,1);assert.equal(configuration,1);assert.equal(dependencies,0);
  }
});

// Break: new-mode exclusion after normalization or evaluating an accessor.
test('remainder exclusion precedes snapshots configuration and dependencies',async()=>{
  const {createSandboxCliAdapter:accept}=await import('./stripe-sandbox-cli.mjs'); const base={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'};
  let reads=0;const trap=new Proxy({},{get(){reads++;throw null;}});
  for(const shape of ['true','malformed','boolean','accessor','hidden','inherited']){
    const raw=shape==='inherited'?Object.assign(Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'}),base):{...base};
    if(shape!=='inherited')Object.defineProperty(raw,'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',shape==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:shape!=='hidden',value:shape==='true'||shape==='hidden'?'true':shape==='boolean'?false:{private:'canary'}});
    await assert.rejects(accept({env:raw,get bootstrap(){reads++;throw null;},get runId(){reads++;throw null;}},trap,trap),/OPT_IN_REQUIRED/);
  }
  assert.equal(reads,0);
});
import childProcess from 'node:child_process';
import {syncBuiltinESMExports} from 'node:module';
import {constants} from 'node:fs';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createSandboxCliAdapter,createManualRefundCliAdapter} from './stripe-sandbox-cli.mjs';
import {runManualPayment} from './stripe-manual-payment.mjs';
test('full CLI refuses manual flag before configuration/dependency access',async()=>{
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  await assert.rejects(createSandboxCliAdapter({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true'},get runId(){touched++;throw null;}},trap),{message:'stripe_sandbox_cli_OPT_IN_REQUIRED'});
  assert.equal(touched,0);
});

// A broken injection seam must never fall through to the pinned real CLI in RED.
mock.method(childProcess,'execFile',()=>{throw new Error('synthetic_test_forbids_real_process');});
mock.method(childProcess,'spawn',()=>{throw new Error('synthetic_test_forbids_real_process');});
syncBuiltinESMExports();
after(()=>{mock.restoreAll();syncBuiltinESMExports();});

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const RUN='11111111-2222-4333-8444-555555555555';
const DIR=ROOT+'/test-results/stripe-sandbox/'+RUN+'/cli';
const ACCOUNT='acct_1UPiMpELswAd8tpR',VERSION='2026-09-30.endive';
const SECRET='whsec_syntheticOriginal123';
const READY=`Ready! You are using Stripe API Version [${VERSION}]. Your webhook signing secret is ${SECRET} (^C to quit)\n`;
const LIMIT=()=>({deadline:Date.now()+60000});
const turn=()=>new Promise(resolveTurn=>setImmediate(resolveTurn));
const proof=()=>({paymentId:RUN,siteId:'22222222-3333-4444-8555-666666666666',operationId:'33333333-4444-4555-8666-777777777777',intentId:'pi_syntheticOriginal',amountMinor:2000,currency:'EUR',source:'CATALOG',externalAccountId:ACCOUNT});
const charge=()=>({id:'ch_reference',object:'charge',livemode:false,payment_intent:'pi_syntheticOriginal',status:'succeeded',paid:true,captured:true,amount:2000,amount_captured:2000,currency:'eur',payment_method_details:{type:'card'}});
const intent=()=>({id:'pi_syntheticOriginal',object:'payment_intent',livemode:false,status:'succeeded',amount:2000,amount_received:2000,currency:'eur',capture_method:'automatic',payment_method_types:['card'],metadata:{payment_id:RUN,site_id:'22222222-3333-4444-8555-666666666666',source:'CATALOG'},latest_charge:charge(),client_secret:'synthetic-not-returned'});
const oauth=()=>({account_id:ACCOUNT,mode:'test',authorized_accounts:[{id:ACCOUNT,modes:['test']}],email:'private@example.invalid',display_name:'Private',role:'owner',expires_at:1});
const balance=()=>({stdout:JSON.stringify({object:'balance',livemode:false,available:[],pending:[]}),stderr:`< HTTP 200\n< Stripe-Version: ${VERSION}\n`});
const stat=(directory=false)=>({uid:process.getuid(),mode:directory?0o40700:0o100600,nlink:1,size:10,isDirectory:()=>directory,isFile:()=>!directory,isSymbolicLink:()=>false});
function fixture(overrides={}) {
  const calls=[],children=[],timers=new Map();let timerId=0;
  const options={runId:RUN,cliDirectory:DIR,backendPort:18080,accountKey:'stripe-sandbox-default',approvedBinding:{externalAccountId:ACCOUNT,apiVersion:VERSION},env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',STRIPE_API_KEY:'never-inherit',NODE_OPTIONS:'never-inherit'},persistWebhookSecret:async()=>true,verifyForwardTarget:async()=>true,...overrides};
  const dependencies={
    lstat:async path=>stat(!path.endsWith('.toml')&&!path.endsWith('.json')),
    realpath:async path=>path,
    readdir:async()=>['config.toml'],
    open:async()=>({stat:async()=>stat(),close:async()=>{}}),
    execute:async(file,args,settings)=>{
      calls.push({file,args,settings});
      const command=args[args.indexOf('--log-level')+2];
      if(command==='--version')return {stdout:'stripe version 1.52.0\n',stderr:''};
      if(command==='whoami')return {stdout:JSON.stringify(oauth()),stderr:''};
      if(args.includes('/v1/balance'))return balance();
      return {stdout:JSON.stringify(intent()),stderr:''};
    },
    spawn:(file,args,settings)=>{
      const child=new EventEmitter();child.pid=4242+children.length;child.stdout=new PassThrough();child.stderr=new PassThrough();child.signals=[];
      child.kill=signal=>{child.signals.push(signal);if(child.autoExit!==false)queueMicrotask(()=>child.emit('exit',0,signal));return true;};
      children.push(child);calls.push({file,args,settings,child});return child;
    },
    setTimeout:(callback,ms)=>{const id=++timerId;timers.set(id,{callback,ms});return id;},
    clearTimeout:id=>timers.delete(id),
    execFile:()=>{throw new Error('synthetic_test_forbids_real_process');},
  };
  return {options,dependencies,calls,children,timers,fire(ms){const entry=[...timers].find(([,t])=>t.ms===ms);assert.ok(entry,`timer ${ms} exists`);timers.delete(entry[0]);entry[1].callback();}};
}
async function setup(overrides) {const f=fixture(overrides);f.adapter=await createSandboxCliAdapter(f.options,f.dependencies);return f;}
async function start(f,line=READY) {const ready=f.adapter.startListener(f.limits??LIMIT());await turn();f.children.at(-1)?.stderr.write(line);return ready;}
async function stop(f) {return f.adapter.stopListener(LIMIT());}
const rejects=(promise,code)=>assert.rejects(promise,{message:'stripe_sandbox_cli_'+code});
const paymentProof=()=>{const {source,...p}=proof();return {...p,paymentAccountId:'44444444-5555-4666-8777-888888888888',priceId:'55555555-6666-4777-8888-999999999999',priceKey:'s5a-price-test'};};
const ORIGINAL='031b5ae7-6603-4339-b1b3-77231291ff34';
const refundLeg=(second=false)=>({refundId:second?'66666666-7777-4888-8999-000000000001':'66666666-7777-4888-8999-000000000000',providerReference:second?'re_second':'re_first',providerEventId:second?'evt_second':'evt_first',amountMinor:second?1300:700,totalMinor:second?2000:700});
const refund=(second=false)=>({id:second?'re_second':'re_first',object:'refund',amount:second?1300:700,currency:'eur',charge:'ch_reference',payment_intent:'pi_syntheticOriginal',status:'succeeded',metadata:{payment_id:RUN,refund_id:second?'66666666-7777-4888-8999-000000000001':'66666666-7777-4888-8999-000000000000'}});
const refundTarget=()=>{const p=paymentProof();return {originalRunId:ORIGINAL,originalSource:'f0620e40004162d2b3eededa60512f50a492f4cc',project:'stripe-'+ORIGINAL,containers:{},images:{},network:{},volumes:{},ports:{},endpoints:{},account:{id:p.paymentAccountId,key:'stripe-sandbox-default',externalId:ACCOUNT},site:{id:p.siteId,key:'s5a-original'},price:{id:p.priceId,key:p.priceKey,version:1,providerPriceId:'price_original'},paymentId:p.paymentId,operationId:p.operationId,intentId:p.intentId,callbackVersion:1,amountMinor:2000,currency:'EUR',source:'CATALOG'};};
async function refundSetup({autoStart=true}={}){
  const f=fixture({originalRunId:ORIGINAL,cliDirectory:ROOT+'/test-results/stripe-sandbox/'+ORIGINAL+'/cli',env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND:'true'}});
  delete f.options.runId;delete f.options.accountKey;f.total=0;f.mutate=()=>{};f.mutateRefund=()=>{};f.mutateEvent=()=>{};
  const spawn=f.dependencies.spawn;f.dependencies.spawn=(...args)=>{const child=spawn(...args);child.on('exit',()=>{if(child.autoClose!==false)queueMicrotask(()=>child.emit('close',0));});return child;};
  const execute=f.dependencies.execute;f.dependencies.execute=async(file,args,settings)=>{
    if(args.some(a=>a.startsWith('/v1/payment_intents/'))){f.calls.push({file,args,settings});const p=intent();p.customer=null;p.latest_charge.customer=null;p.latest_charge.amount_refunded=f.total;p.latest_charge.refunded=f.total===2000;p.latest_charge.refunds={object:'list',data:f.total===2000?[refund(),refund(true)]:f.total?[refund()]:[],has_more:false};f.mutate(p);return {stdout:JSON.stringify(p),stderr:''};}
    if(args.some(a=>a.startsWith('/v1/refunds/'))){f.calls.push({file,args,settings});const r=refund(args.includes('/v1/refunds/re_second'));f.mutateRefund(r);return {stdout:JSON.stringify(r),stderr:''};}
    if(args.some(a=>a.startsWith('/v1/events/'))){f.calls.push({file,args,settings});const second=args.includes('/v1/events/evt_second'),e={id:second?'evt_second':'evt_first',object:'event',type:'refund.updated',api_version:VERSION,livemode:false,data:{object:refund(second)}};f.mutateEvent(e);return {stdout:JSON.stringify(e),stderr:''};}
    return execute(file,args,settings);
  };
  f.adapter=await createManualRefundCliAdapter(f.options,f.dependencies);f.limits=LIMIT();if(autoStart)await start(f);return f;
}
test('refund CLI binds captured payment once and proves partial false then full true without Refund.livemode',async()=>{
  const f=await refundSetup();assert.equal(await f.adapter.bindCapturedPayment(refundTarget(),f.limits),true);
  assert.deepEqual(Object.keys(f.adapter).sort(),['assertRefundListener','bindCapturedPayment','inspectRefund','startListener','stopListener','validateBinding']);
  assert.equal(f.adapter.assertRefundListener(f.limits),true);f.total=700;
  assert.deepEqual(await f.adapter.inspectRefund(refundLeg(),f.limits),{providerVerified:true,webhookVerified:true});
  f.total=2000;assert.deepEqual(await f.adapter.inspectRefund(refundLeg(true),f.limits),{providerVerified:true,webhookVerified:true});
  assert.ok(f.calls.filter(c=>!c.child).every(c=>c.settings.timeout<=10000));await stop(f);
});
test('old bindCapturedPayment still refuses an already retained partial700',async()=>{
  const f=await refundSetup();f.total=700;
  await rejects(f.adapter.bindCapturedPayment(refundTarget(),f.limits),'REFUND_BINDING');
  await rejects(f.adapter.inspectRefund(refundLeg(true),f.limits),'REFUND');
  await stop(f);
});
for(const [name,change] of [['live',p=>p.livemode=true],['wrong metadata',p=>p.metadata.site_id=RUN],['not succeeded',p=>p.status='processing'],['not captured',p=>p.latest_charge.captured=false],['wrong amount',p=>p.latest_charge.amount_captured=1999],['customer',p=>p.customer='cus_other'],['existing refund',p=>{p.latest_charge.amount_refunded=700;p.latest_charge.refunds.data=[refund()];}],['wrong charge',p=>p.latest_charge.payment_intent='pi_other'],['has_more',p=>p.latest_charge.refunds.has_more=true]])test('refund captured baseline rejects '+name+' and never rebinds',async()=>{const f=await refundSetup();f.mutate=change;await rejects(f.adapter.bindCapturedPayment(refundTarget(),f.limits),'REFUND_BINDING');const count=f.calls.length;await rejects(f.adapter.bindCapturedPayment(refundTarget(),f.limits),'REFUND_BINDING');assert.equal(f.calls.length,count);await stop(f);});
for(const [name,change] of [['wrong refund',f=>f.mutateRefund=r=>r.id='re_other'],['amount',f=>f.mutateRefund=r=>r.amount=1300],['charge',f=>f.mutateRefund=r=>r.charge='ch_other'],['intent',f=>f.mutateRefund=r=>r.payment_intent='pi_other'],['live refund',f=>f.mutateRefund=r=>r.livemode=true],['has_more',f=>f.mutate=p=>p.latest_charge.refunds.has_more=true],['partial refunded=true',f=>f.mutate=p=>p.latest_charge.refunded=true],['extra refund',f=>f.mutate=p=>p.latest_charge.refunds.data.push({...refund(),id:'re_foreign'})],['wrong event',f=>f.mutateEvent=e=>e.id='evt_other'],['event type',f=>f.mutateEvent=e=>e.type='charge.refunded'],['event version',f=>f.mutateEvent=e=>e.api_version='other'],['event live',f=>f.mutateEvent=e=>e.livemode=true],['event account',f=>f.mutateEvent=e=>e.account='acct_other'],['event refund',f=>f.mutateEvent=e=>e.data.object.id='re_other']])test('refund exact leg proof rejects '+name,async()=>{const f=await refundSetup();await f.adapter.bindCapturedPayment(refundTarget(),f.limits);f.total=700;change(f);await rejects(f.adapter.inspectRefund(refundLeg(),f.limits),'REFUND');assert.throws(()=>f.adapter.assertRefundListener(f.limits),{message:'stripe_sandbox_cli_REFUND_LISTENER'});await stop(f);});
test('refund listener loss during durable dispatch is synchronously latched without IO',async()=>{const f=await refundSetup();await f.adapter.bindCapturedPayment(refundTarget(),f.limits);const count=f.calls.length,timers=f.timers.size;assert.equal(f.adapter.assertRefundListener(f.limits),true);await Promise.resolve().then(()=>f.children.at(-1).emit('exit',1));assert.throws(()=>f.adapter.assertRefundListener(f.limits),{message:'stripe_sandbox_cli_REFUND_LISTENER'});assert.equal(f.calls.length,count);assert.equal(f.timers.size,timers);await rejects(f.adapter.startListener(f.limits),'LISTENER');});
test('refund listener cleanup awaits captured child close after exit',async()=>{const f=await refundSetup();await f.adapter.bindCapturedPayment(refundTarget(),f.limits);const child=f.children.at(-1);child.autoClose=false;child.kill=()=>{queueMicrotask(()=>child.emit('exit',0));return true;};let settled=false;const closing=f.adapter.stopListener({deadline:f.limits.deadline+120000}).then(v=>{settled=true;return v;});await turn();assert.equal(settled,false);child.emit('close',0);assert.equal(await closing,true);});
test('refund binding rejects operator flat identity and accessors without evaluating them',async()=>{for(const kind of ['flat','accessor','old source','account']){const f=await refundSetup(),target=kind==='flat'?paymentProof():refundTarget();let touched=0;if(kind==='accessor')Object.defineProperty(target.account,'externalId',{enumerable:true,get(){touched++;return ACCOUNT;}});if(kind==='old source')target.originalSource='a'.repeat(40);if(kind==='account')target.account.externalId='acct_other';await rejects(f.adapter.bindCapturedPayment(target,f.limits),'REFUND_BINDING');assert.equal(touched,0);await stop(f);}});
test('refund proof binds provider metadata to exact local Refund and Payment UUIDs',async()=>{for(const kind of ['refund','payment','Event']){const f=await refundSetup();await f.adapter.bindCapturedPayment(refundTarget(),f.limits);f.total=700;if(kind==='refund')f.mutateRefund=r=>r.metadata.refund_id=RUN;if(kind==='payment')f.mutateRefund=r=>r.metadata.payment_id=ORIGINAL;if(kind==='Event')f.mutateEvent=e=>e.data.object.metadata.refund_id=RUN;await rejects(f.adapter.inspectRefund(refundLeg(),f.limits),'REFUND');await stop(f);}});
for(const kind of ['PID','deadline','signal'])test('refund synchronous dispatch assertion refuses changed '+kind,async()=>{const f=await refundSetup();await f.adapter.bindCapturedPayment(refundTarget(),f.limits);let l=f.limits;if(kind==='PID')f.children.at(-1).pid++;if(kind==='deadline')l={deadline:f.limits.deadline+1};if(kind==='signal'){const controller=new AbortController();controller.abort();l={...f.limits,signal:controller.signal};}assert.throws(()=>f.adapter.assertRefundListener(l),{message:'stripe_sandbox_cli_REFUND_LISTENER'});assert.throws(()=>f.adapter.assertRefundListener(f.limits));});
for(const kind of ['missing credentials','expired credentials','wrong account','live context','API','CLI'])test('refund CLI refuses '+kind+' without login or write capability',async()=>{const f=await refundSetup({autoStart:false}),execute=f.dependencies.execute;f.dependencies.execute=async(file,args,settings)=>{if(args.includes('whoami')){if(kind==='missing credentials'||kind==='expired credentials')throw null;const v=oauth();if(kind==='wrong account')v.account_id='acct_other';if(kind==='live context')v.mode='live';return {stdout:JSON.stringify(v),stderr:''};}if(kind==='API'&&args.includes('/v1/balance'))return {stdout:JSON.stringify({object:'balance',livemode:false}),stderr:'< HTTP 200\n< Stripe-Version: other\n'};if(kind==='CLI'&&args.includes('--version'))return {stdout:'stripe version 1.53.0\n',stderr:''};return execute(file,args,settings);};f.adapter=await createManualRefundCliAdapter(f.options,f.dependencies);await rejects(f.adapter.validateBinding(f.limits),'BINDING');assert.equal(f.adapter.loginInvocation,undefined);assert.equal(f.adapter.completeLoginInvocation,undefined);assert.equal(f.adapter.bindManualPayment,undefined);assert.ok(f.calls.every(c=>!c.args.includes('login')));});
async function paymentSetup({autoStart=true,autoReady=false}={}){const f=await setup({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true'}});f.paid=false;f.mutate=()=>{};const execute=f.dependencies.execute;
  // Recreate adapter after installing transport responses; no real process is ever used.
  f.dependencies.execute=async(file,args,settings)=>{
    if(args.some(a=>a.startsWith('/v1/payment_intents/'))){f.calls.push({file,args,settings});const p=intent();p.customer=null;p.latest_charge.customer=null;p.latest_charge.refunded=false;p.latest_charge.amount_refunded=0;
      if(args.some((arg,index)=>arg==='--expand'&&args[index+1]==='latest_charge.refunds'))p.latest_charge.refunds={object:'list',data:[],has_more:false};
      if(!f.paid){p.status='requires_payment_method';p.amount_received=0;p.payment_method=null;p.latest_charge=null;}
      f.mutate(p);return {stdout:JSON.stringify(p),stderr:''};}
    if(args.some(a=>a.startsWith('/v1/events/'))){f.calls.push({file,args,settings});const e={id:'evt_original',object:'event',type:'payment_intent.succeeded',api_version:VERSION,livemode:false,data:{object:{id:'pi_syntheticOriginal',object:'payment_intent',livemode:false,status:'succeeded',amount:2000,amount_received:2000,currency:'eur',latest_charge:'ch_reference',customer:null,capture_method:'automatic',payment_method_types:['card'],metadata:{payment_id:RUN,site_id:'22222222-3333-4444-8555-666666666666',source:'CATALOG'}}}};f.mutateEvent?.(e);return {stdout:JSON.stringify(e),stderr:''};}
    return execute(file,args,settings);};
  if(autoReady){const spawn=f.dependencies.spawn;f.dependencies.spawn=(...args)=>{const child=spawn(...args);queueMicrotask(()=>child.stderr.write(READY));return child;};}
  f.adapter=await createSandboxCliAdapter(f.options,f.dependencies);f.limits=LIMIT();if(autoStart)await start(f);return f;}
test('manual CLI binds unpaid original then verifies exact event with active original listener',async()=>{
  const f=await paymentSetup();assert.equal(typeof f.adapter.bindManualPayment,'function');assert.equal(await f.adapter.bindManualPayment(paymentProof(),f.limits),true);await f.adapter.verifyManualPaymentBaseline(f.limits);f.paid=true;
  assert.deepEqual(await f.adapter.inspectManualPayment('evt_original',f.limits),{captured:true,eventVerified:true});
  assert.ok(f.calls.some(c=>c.args.includes('/v1/events/evt_original')));assert.ok(f.calls.every(c=>!c.args.some(a=>['confirm','refund','cancel','delete','list'].includes(a))));await rejects(f.adapter.inspectManualPayment('evt_original',f.limits),'PAYMENT');await stop(f);
});
for(const [name,mutate] of [['live',p=>p.livemode=true],['amount-only identity',p=>p.metadata.payment_id='other'],['uncaptured',p=>p.latest_charge.captured=false],['wrong amount',p=>p.amount_received=1999],['Customer',p=>p.customer='cus_other'],['refund',p=>p.latest_charge.amount_refunded=1],['missing refunds',p=>delete p.latest_charge.refunds],['truncated refunds',p=>p.latest_charge.refunds.has_more=true],['nonempty refunds',p=>p.latest_charge.refunds.data.push({id:'re_other'})],['wrong Charge',p=>p.latest_charge.payment_intent='pi_other']])
test('manual CLI rejects '+name+' and never uses missed listener lifecycle',async()=>{const f=await paymentSetup();assert.equal(typeof f.adapter.bindManualPayment,'function');await f.adapter.bindManualPayment(paymentProof(),f.limits);f.paid=true;f.mutate=mutate;await rejects(f.adapter.inspectManualPayment('evt_original',f.limits),'PAYMENT');await stop(f);});
test('manual CLI rejects wrong journal Event Intent, consumes final proof, and refuses missed binding',async()=>{const f=await paymentSetup();assert.equal(typeof f.adapter.bindManualPayment,'function');await f.adapter.bindManualPayment(paymentProof(),f.limits);await rejects(f.adapter.bindMissedIntent(proof()),'PROOF');f.paid=true;f.mutateEvent=e=>e.data.object.id='pi_other';await rejects(f.adapter.inspectManualPayment('evt_original',f.limits),'PAYMENT');await rejects(f.adapter.inspectUnusedManualPayment(f.limits),'UNUSED');await stop(f);});
test('manual CLI unused-final read is bound one-use unpaid original and requires active listener',async()=>{const f=await paymentSetup();assert.equal(typeof f.adapter.bindManualPayment,'function');await f.adapter.bindManualPayment(paymentProof(),f.limits);assert.equal(await f.adapter.inspectUnusedManualPayment(f.limits),true);await rejects(f.adapter.inspectManualPayment('evt_original',f.limits),'PAYMENT');await stop(f);});
for(const [name,mutate] of [['spent',f=>f.paid=true],['wrong original',f=>f.mutate=p=>p.id='pi_other'],['stale authority',f=>f.limits={deadline:f.limits.deadline+1}],['listener lost',f=>f.children.at(-1).emit('exit',1)]])
test('manual CLI pre-permit baseline refuses '+name+' and cannot retry',async()=>{const f=await paymentSetup();await f.adapter.bindManualPayment(paymentProof(),f.limits);mutate(f);await rejects(f.adapter.verifyManualPaymentBaseline(f.limits),'PAYMENT_BASELINE');const n=f.calls.length;await rejects(f.adapter.verifyManualPaymentBaseline(f.limits),'PAYMENT_BASELINE');assert.equal(f.calls.length,n);});
test('manual CLI permit baseline is once-only and final event must contain original Charge',async()=>{const f=await paymentSetup();await f.adapter.bindManualPayment(paymentProof(),f.limits);await f.adapter.verifyManualPaymentBaseline(f.limits);f.paid=true;f.mutateEvent=e=>e.data.object.latest_charge='ch_other';await rejects(f.adapter.inspectManualPayment('evt_original',f.limits),'PAYMENT');await stop(f);});
for(const [name,mutate] of [['wrong account',e=>e.account='acct_other'],['wrong API',e=>e.api_version='other'],['live Event',e=>e.livemode=true],['wrong Event',e=>e.id='evt_other']])
test('manual event proof refuses '+name,async()=>{const f=await paymentSetup();await f.adapter.bindManualPayment(paymentProof(),f.limits);await f.adapter.verifyManualPaymentBaseline(f.limits);f.paid=true;f.mutateEvent=mutate;await rejects(f.adapter.inspectManualPayment('evt_original',f.limits),'PAYMENT');await stop(f);});
test('manual command reads are clipped to ten seconds and original authority',async()=>{const f=await paymentSetup();await f.adapter.bindManualPayment(paymentProof(),f.limits);await f.adapter.verifyManualPaymentBaseline(f.limits);assert.ok(f.calls.filter(c=>!c.child).slice(-8).every(c=>c.settings.timeout<=10000));await stop(f);});
test('pre-permit baseline leaves independent unused-final inspection available',async()=>{const f=await paymentSetup();await f.adapter.bindManualPayment(paymentProof(),f.limits);await f.adapter.verifyManualPaymentBaseline(f.limits);assert.equal(await f.adapter.inspectUnusedManualPayment(f.limits),true);await rejects(f.adapter.inspectManualPayment('evt_original',f.limits),'PAYMENT');await stop(f);});
test('manual listener assertion is synchronous and performs no IO timer or baseline renewal',async()=>{
  const f=await paymentSetup();await f.adapter.bindManualPayment(paymentProof(),f.limits);await f.adapter.verifyManualPaymentBaseline(f.limits);
  assert.equal(typeof f.adapter.assertManualPaymentListener,'function');const calls=f.calls.length,timers=f.timers.size;
  assert.equal(f.adapter.assertManualPaymentListener(f.limits),true);assert.equal(f.adapter.assertManualPaymentListener(f.limits),true);assert.equal(f.calls.length,calls);assert.equal(f.timers.size,timers);
  f.paid=true;assert.deepEqual(await f.adapter.inspectManualPayment('evt_original',f.limits),{captured:true,eventVerified:true});await stop(f);
});
for(const [name,change] of [
  ['lost',async f=>f.children.at(-1).emit('exit',1)],
  ['invalidated',async f=>f.children.at(-1).emit('error',Error('synthetic'))],
  ['closed',async f=>stop(f)],
  ['replacement owner',async f=>{await stop(f);await start(f);} ],
  ['replacement PID',async f=>{f.children.at(-1).pid++;}],
  ['manual failed',async f=>rejects(f.adapter.verifyManualPaymentBaseline(f.limits),'PAYMENT_BASELINE')],
  ['final inspection consumed',async f=>f.adapter.inspectUnusedManualPayment(f.limits)],
  ['renewed deadline',async f=>{f.limits={deadline:f.limits.deadline+1};}],
  ['expired deadline',async f=>{f.limits={deadline:Date.now()-1};}],
  ['aborted signal',async f=>{const c=new AbortController();c.abort();f.limits={deadline:f.limits.deadline,signal:c.signal};}],
  ['invalid signal',async f=>{f.limits={deadline:f.limits.deadline,signal:{aborted:false}};}],
])test('manual listener assertion refuses '+name+' without any command or authority renewal',async()=>{
  const f=await paymentSetup();await f.adapter.bindManualPayment(paymentProof(),f.limits);await f.adapter.verifyManualPaymentBaseline(f.limits);await change(f);
  assert.equal(typeof f.adapter.assertManualPaymentListener,'function');const calls=f.calls.length,timers=f.timers.size;
  assert.throws(()=>f.adapter.assertManualPaymentListener(f.limits),{message:'stripe_sandbox_cli_PAYMENT_LISTENER'});assert.equal(f.calls.length,calls);assert.equal(f.timers.size,timers);
  assert.throws(()=>f.adapter.assertManualPaymentListener(LIMIT()),{message:'stripe_sandbox_cli_PAYMENT_LISTENER'});assert.equal(f.calls.length,calls);
});
test('manual listener assertion refuses unbound unfresh and ordinary-mode adapters',async()=>{
  const ordinary=await setup();assert.equal(typeof ordinary.adapter.assertManualPaymentListener,'function');assert.throws(()=>ordinary.adapter.assertManualPaymentListener(LIMIT()),{message:'stripe_sandbox_cli_PAYMENT_LISTENER'});assert.equal(ordinary.calls.length,0);
  for(const bound of [false,true]){const f=await paymentSetup();if(bound)await f.adapter.bindManualPayment(paymentProof(),f.limits);const calls=f.calls.length;
    assert.throws(()=>f.adapter.assertManualPaymentListener(f.limits),{message:'stripe_sandbox_cli_PAYMENT_LISTENER'});assert.equal(f.calls.length,calls);await stop(f);}
});
test('real original CLI listener loss during controller durable await denies every browser permit',async()=>{
  const f=await paymentSetup({autoStart:false,autoReady:true}),limits={deadline:Date.now()+2700000};let markEntered,finishMark,authorize,browserPermits=0;
  const entered=new Promise(resolveWait=>{markEntered=resolveWait;}),durable=new Promise(resolveWait=>{finishMark=resolveWait;});
  const ports={backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025};
  const configuration={env:f.options.env,runId:RUN,expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',ports,manualPort:18083};
  const bootstrap={prepare:async()=>true,resources:()=>({runId:RUN,status:'PREPARED',cliDirectory:DIR,ports}),start:async()=>true,verify:async()=>true,stop:async()=>true,persistWebhookSecret:async()=>true,verifyForwardTarget:async()=>true};
  const provisioner={prepare:async()=>({binding:paymentProof(),session:{}}),markConfirmation:()=>{markEntered();return durable;},verifyPayment:async()=>{assert.fail('server proof after lost original listener');},close:async()=>true};
  const gateway={open:async()=>({url:'http://127.0.0.1:18083/manual-payment.html'}),wait:async()=>{try{await authorize();browserPermits++;return {outcome:'CONFIRMATION_GRANTED'};}catch{return {outcome:'FAILED'};}},close:async()=>true};
  const task=runManualPayment(configuration,limits,{preflight:async()=>({status:'READY'}),createBootstrap:async()=>bootstrap,createCli:async()=>f.adapter,authorizeCli:async()=>true,createProvisioner:async()=>provisioner,createGateway:async options=>{authorize=options.authorizeConfirmation;return gateway;},write:async()=>{}});
  assert.equal(await Promise.race([entered.then(()=>true),task.then(()=>false)]),true);assert.equal(f.children.length,1);
  f.children[0].emit('exit',1);finishMark(true);const value=await task;
  assert.equal(browserPermits,0);assert.equal(value.report.confirmation_granted,true);assert.equal(value.report.result,'PENDING');assert.equal(value.exitCode,1);
  assert.equal(f.calls.filter(call=>call.args.some(arg=>arg.startsWith('/v1/payment_intents/'))).length,2,'one original bind read plus one fresh permit baseline only');
  assert.ok(f.calls.every(call=>!call.args.some(arg=>arg.startsWith('/v1/events/'))));await assert.rejects(authorize());assert.equal(browserPermits,0);
});

// A removed opt-in gate must fail before any dependency/private getter runs.
test('missing opt-ins reject before accessing configuration or dependencies', async () => {
  let touched=0;
  const options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true'}};
  Object.defineProperty(options,'runId',{get(){touched++;return 'private';}});
  const dependencies=new Proxy({}, {get(){touched++;return undefined;}});
  await assert.rejects(createSandboxCliAdapter(options,dependencies),{message:'stripe_sandbox_cli_OPT_IN_REQUIRED'});
  assert.equal(touched,0);
});

// Removing constructor validation/snapshotting would permit a changed account/path.
test('constructor rejects debug, exhausted runs, wrong binding, and nonowned paths', async()=>{
  for(const update of [{env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',DEBUG:'1'}},{runId:'2f3f49b1-df07-41b1-b978-bc884b390813'},{backendPort:80},{accountKey:'other'},{cliDirectory:'/tmp/cli'},{approvedBinding:{externalAccountId:'acct_other',apiVersion:VERSION}}]) {
    const f=fixture(update);await rejects(createSandboxCliAdapter(f.options,f.dependencies),'CONFIGURATION');assert.equal(f.calls.length,0);
  }
});
test('binding proof uses isolated pinned wrapper and snapshots options',async()=>{
  const f=await setup();f.options.approvedBinding.externalAccountId='acct_mutated';f.options.cliDirectory='/mutated';
  assert.equal(await f.adapter.validateBinding(LIMIT()),true);assert.ok(Object.isFrozen(f.adapter));
  assert.equal(f.calls.length,3);
  for(const call of f.calls) {
    assert.equal(call.file,'/usr/bin/sandbox-exec');
    assert.deepEqual(call.args.slice(0,11),['-p','(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))','/opt/homebrew/bin/stripe','--config',DIR+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error']);
    assert.deepEqual(call.settings.env,{PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:DIR+'/home',XDG_CONFIG_HOME:DIR+'/config',NO_COLOR:'1'});
    assert.equal(call.settings.shell,false);assert.equal(call.settings.cwd,ROOT);assert.deepEqual(call.settings.stdio,['ignore','pipe','pipe']);
    assert.ok(call.settings.timeout<=20000);assert.equal(call.settings.maxBuffer,65536);assert.ok(call.settings.signal instanceof AbortSignal);
  }
  assert.deepEqual(f.calls.map(c=>c.args.slice(11)),[['--version'],['whoami','--format','json'],['get','/v1/balance','--show-headers']]);
});
// Rejecting a permitted no-newline version blocks all subsequent binding proof.
test('binding accepts pinned version text with optional final newline only',async()=>{
  for(const stdout of ['stripe version 1.52.0','stripe version 1.52.0\n']) {
    const f=fixture(),execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{const result=await execute(...args);return f.calls.length===1?{stdout,stderr:''}:result;};
    const a=await createSandboxCliAdapter(f.options,f.dependencies);assert.equal(await a.validateBinding(LIMIT()),true);assert.equal(f.calls.length,3);
  }
});
test('binding rejects extra pinned version text, newlines, or stderr',async()=>{
  for(const response of [{stdout:'stripe version 1.52.0\n\n',stderr:''},{stdout:'stripe version 1.52.0 extra',stderr:''},{stdout:'stripe version 1.52.0',stderr:'extra'}]) {
    const f=fixture(),execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{const result=await execute(...args);return f.calls.length===1?response:result;};
    const a=await createSandboxCliAdapter(f.options,f.dependencies);await rejects(a.validateBinding(LIMIT()),'BINDING');assert.equal(f.calls.length,1);
  }
});
// Metadata failures must prevent all CLI invocation, with no permission repair.
test('each command rechecks canonical private directories and nofollow regular files',async()=>{
  for(const bad of ['symlink','uid','mode','links','size','canonical']) {
    const f=fixture();let opens=0;
    f.dependencies.lstat=async path=>{const s=stat(!path.endsWith('.toml'));if(path.endsWith('.toml')){if(bad==='symlink')s.isSymbolicLink=()=>true;if(bad==='uid')s.uid++;if(bad==='mode')s.mode=0o100644;if(bad==='links')s.nlink=2;if(bad==='size')s.size=65537;}return s;};
    if(bad==='canonical')f.dependencies.realpath=async path=>path+'/different';
    f.dependencies.open=async(path,flags)=>{opens++;assert.equal(flags,constants.O_RDONLY|constants.O_NOFOLLOW);return {stat:async()=>stat(),close:async()=>{}};};
    const a=await createSandboxCliAdapter(f.options,f.dependencies);await rejects(a.validateBinding(LIMIT()),'BINDING');assert.equal(f.calls.length,0);
  }
  const f=await setup();let reads=0;f.dependencies.lstat=async()=>{reads++;return stat(true);}; // snapshots dependency, too
  assert.equal(await f.adapter.validateBinding(LIMIT()),true);assert.equal(reads,0);
});
test('rejects OAuth ambiguity, live context, legacy keys, and nondefault response version',async()=>{
  const identities=[{}, {account_id:'acct_wrong',mode:'test',authorized_accounts:[]}, {...oauth(),mode:'live'}, {...oauth(),authorized_accounts:[...oauth().authorized_accounts,...oauth().authorized_accounts]}, {...oauth(),authorized_accounts:[{id:ACCOUNT,modes:['test','live']}]}, {account_id:ACCOUNT,api_key:'redacted',mode:'test'}];
  const replies=[{stdout:'stripe version 1.51.0',stderr:''},...identities.map(x=>({stdout:JSON.stringify(x),stderr:''})),...['> Stripe-Version: '+VERSION,'< HTTP 200\n< Stripe-Version: wrong','< HTTP 200\n< Stripe-Version: '+VERSION+'\n< Stripe-Version: '+VERSION,'< HTTP 200\n< HTTP 200\n< Stripe-Version: '+VERSION,'< HTTP 500\n< Stripe-Version: '+VERSION].map(stderr=>({...balance(),stderr}))];
  for(let i=0;i<replies.length;i++) {
    const f=fixture(),execute=f.dependencies.execute;f.dependencies.execute=async(...args)=>{const normal=await execute(...args);const n=f.calls.length;return (i===0&&n===1)||(i>0&&i<=identities.length&&n===2)||(i>identities.length&&n===3)?replies[i]:normal;};
    const a=await createSandboxCliAdapter(f.options,f.dependencies);await rejects(a.validateBinding(LIMIT()),'BINDING');
  }
});
test('accepts actual HTTP framing and case-insensitive response headers',async()=>{
  const f=fixture(),execute=f.dependencies.execute;f.dependencies.execute=async(...args)=>{const result=await execute(...args);return f.calls.length===3?{...result,stderr:'> Stripe-Version: wrong\r\n< HTTP/2 200 OK\r\n< sTrIpE-vErSiOn: '+VERSION+'\r\n'}:result;};
  const a=await createSandboxCliAdapter(f.options,f.dependencies);assert.equal(await a.validateBinding(LIMIT()),true);
});
test('bounds output and never traverses a thrown nested proxy',async()=>{
  for(const response of [{stdout:'x'.repeat(65537),stderr:''},{stdout:'x'.repeat(40000),stderr:'y'.repeat(40000)}]) {
    const f=fixture();f.dependencies.execute=async()=>response;const a=await createSandboxCliAdapter(f.options,f.dependencies);await rejects(a.validateBinding(LIMIT()),'BINDING');
  }
  let touched=0;const f=fixture();f.dependencies.execute=async()=>{throw new Proxy({}, {get(){touched++;throw 'private';}});};
  const a=await createSandboxCliAdapter(f.options,f.dependencies);await rejects(a.validateBinding(LIMIT()),'BINDING');assert.equal(touched,0);
});
// Readiness must depend on durable persistence, never on child output alone.
test('chunked ANSI/CRLF Ready waits for durable persistence and exact listener isolation',async()=>{
  let persistResolve,received;const f=await setup({persistWebhookSecret:secret=>{received=secret;return new Promise(resolvePersist=>{persistResolve=resolvePersist;});}});
  let settled=false;const pending=f.adapter.startListener(LIMIT()).then(value=>{settled=true;return value;});await turn();
  const child=f.children[0];child.stdout.write('\u001b[32m'+READY.slice(0,35));child.stdout.write(READY.slice(35).replace('\n','\u001b[0m\r\n'));await turn();
  assert.equal(received,SECRET);assert.equal(settled,false);persistResolve(true);assert.equal(await pending,true);
  assert.deepEqual(f.calls.at(-1).args.slice(11),['listen','--events','payment_intent.processing,payment_intent.requires_action,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,refund.created,refund.updated,refund.failed','--events-from','@self','--forward-to','http://127.0.0.1:18080/api/v1/provider-webhooks/stripe/stripe-sandbox-default']);
  await rejects(f.adapter.startListener(LIMIT()),'LISTENER');assert.equal(f.children.length,1);assert.equal(await stop(f),true);assert.deepEqual(child.signals,['SIGINT']);assert.equal(await stop(f),true);assert.deepEqual(child.signals,['SIGINT']);
});
test('rejects wrong forward target before spawning or querying CLI',async()=>{
  const f=await setup({verifyForwardTarget:async()=>false});await rejects(f.adapter.startListener(LIMIT()),'LISTENER');assert.equal(f.calls.length,0);assert.equal(f.children.length,0);
});
test('invalid Ready, output overflow, failed persistence, and premature exit cancel owned child',async()=>{
  for(const [line,persist,exit] of [[READY.replace(VERSION,'wrong'),true,false],[READY.replace('['+VERSION+']',''),true,false],['x'.repeat(8193),true,false],[READY,false,false],[READY,true,true]]) {
    const f=await setup({persistWebhookSecret:async()=>persist});const pending=f.adapter.startListener(LIMIT());await turn();const child=f.children[0];
    if(exit)child.emit('exit',1,null);else child.stderr.write(line);
    await rejects(pending,'LISTENER');if(!exit)assert.deepEqual(child.signals,['SIGINT']);await rejects(f.adapter.inspectIntent(LIMIT()),'INTENT');
  }
});
test('reconnect mismatches invalidate ownership and never persist a second secret',async()=>{
  let writes=0;const f=await setup({persistWebhookSecret:async()=>{writes++;return true;}});await start(f);
  f.children[0].stderr.write(READY);await turn();assert.equal(writes,1);
  f.children[0].stderr.write(READY.replace(SECRET,'whsec_other'));await turn();assert.equal(writes,1);await rejects(f.adapter.bindMissedIntent(proof()),'PROOF');assert.equal(await stop(f),true);
});
test('restart requires the same secret and does not replace the original listener proof',async()=>{
  const f=await setup();await start(f);assert.equal(await stop(f),true);const next=f.adapter.startListener(LIMIT());await turn();f.children[1].stderr.write(READY.replace(SECRET,'whsec_other'));await rejects(next,'LISTENER');assert.equal(f.children.length,2);
});
test('caller abort propagates to one-shot execution and active listener cleanup is awaited',async()=>{
  const f=fixture();let executionSignal,release;
  f.dependencies.execute=async(file,args,settings)=>{executionSignal=settings.signal;await new Promise(resolveExec=>{release=resolveExec;});return {stdout:'stripe version 1.52.0',stderr:''};};
  const a=await createSandboxCliAdapter(f.options,f.dependencies),controller=new AbortController();const validation=a.validateBinding({...LIMIT(),signal:controller.signal});await turn();controller.abort();await turn();assert.equal(executionSignal.aborted,true);release();await rejects(validation,'BINDING');
  const g=await setup(),abort=new AbortController();const starting=g.adapter.startListener({...LIMIT(),signal:abort.signal});await turn();g.children[0].autoExit=false;abort.abort();await turn();let finished=false;starting.catch(()=>{finished=true;});await turn();assert.equal(finished,false);g.children[0].emit('exit',0,'SIGINT');await rejects(starting,'LISTENER');
});
test('stop uncertainty sends only owned SIGINT and SIGTERM then permanently blocks restart and inspection',async()=>{
  const f=await setup();await start(f);await f.adapter.bindMissedIntent(proof());const child=f.children[0];child.autoExit=false;
  const stopping=stop(f);await turn();assert.deepEqual(child.signals,['SIGINT']);f.fire(5000);await turn();assert.deepEqual(child.signals,['SIGINT','SIGTERM']);f.fire(5000);await rejects(stopping,'STOP');
  child.emit('exit',0,'SIGTERM');await rejects(f.adapter.startListener(LIMIT()),'LISTENER');await rejects(f.adapter.inspectIntent(LIMIT()),'INTENT');assert.equal(f.children.length,1);
});
test('serializes operations and rejects absent or expired deadlines without side effects',async()=>{
  const f=await setup();await rejects(f.adapter.validateBinding(),'BINDING');await rejects(f.adapter.validateBinding({deadline:Date.now()-1}),'BINDING');assert.equal(f.calls.length,0);
  const pending=f.adapter.startListener(LIMIT());await turn();await rejects(f.adapter.validateBinding(LIMIT()),'BUSY');await rejects(f.adapter.stopListener(LIMIT()),'BUSY');f.children[0].stderr.write(READY);assert.equal(await pending,true);await stop(f);
});
// Replacing an original preparation fact or accepting an unbound charge must fail.
test('latches original preparation once before stop and retrieves only its intent after proven stop',async()=>{
  const f=await setup();await rejects(f.adapter.bindMissedIntent(proof()),'PROOF');await start(f);const original=proof();assert.equal(await f.adapter.bindMissedIntent(original),true);original.intentId='pi_replacement';
  await rejects(f.adapter.bindMissedIntent(proof()),'PROOF');await rejects(f.adapter.inspectIntent(LIMIT()),'INTENT');assert.equal(await stop(f),true);
  const captured=await f.adapter.inspectIntent(LIMIT());assert.deepEqual(captured,{captured:true});assert.ok(Object.isFrozen(captured));
  assert.deepEqual(f.calls.at(-1).args.slice(11),['get','/v1/payment_intents/pi_syntheticOriginal','--expand','latest_charge']);assert.equal(f.calls.filter(c=>c.args.includes('whoami')).length,2);
});
test('strict preparation and captured charge predicates reject financial mismatches',async()=>{
  for(const update of [{amountMinor:1999},{currency:'eur'},{source:'OTHER'},{externalAccountId:'acct_other'},{paymentId:'INVALID'},{extra:true}]) {
    const f=await setup();await start(f);await rejects(f.adapter.bindMissedIntent({...proof(),...update}),'PROOF');await stop(f);
  }
  const changes=[p=>p.id='pi_other',p=>p.livemode=true,p=>p.amount_received=1999,p=>p.capture_method='manual',p=>p.payment_method_types=['card','link'],p=>p.metadata.payment_id='wrong',p=>p.latest_charge='ch_reference',p=>p.latest_charge.payment_intent='pi_other',p=>p.latest_charge.captured=false,p=>p.latest_charge.amount_captured=1999,p=>p.latest_charge.payment_method_details.type='link'];
  for(const change of changes) {
    const f=fixture(),execute=f.dependencies.execute;f.dependencies.execute=async(...args)=>{const result=await execute(...args);if(args[1].some(x=>x.startsWith('/v1/payment_intents/'))){const p=intent();change(p);return {stdout:JSON.stringify(p),stderr:''};}return result;};
    f.adapter=await createSandboxCliAdapter(f.options,f.dependencies);await start(f);await f.adapter.bindMissedIntent(proof());await stop(f);await rejects(f.adapter.inspectIntent(LIMIT()),'INTENT');
  }
});

// A spontaneous exit/error or reconnect conflict must never become a stop proof.
test('unexpected listener exit cannot be relabeled as an explicit stop for inspection',async()=>{
  const f=await setup();await start(f);await f.adapter.bindMissedIntent(proof());f.children[0].emit('exit',1,null);assert.equal(await stop(f),true);await rejects(f.adapter.inspectIntent(LIMIT()),'INTENT');
});
test('post-readiness process errors and secret conflicts block later proof and callback restart',async()=>{
  for(const event of ['error','secret']) {
    let writes=0;const f=await setup({persistWebhookSecret:async()=>{writes++;return true;}});await start(f);await f.adapter.bindMissedIntent(proof());
    if(event==='error')f.children[0].emit('error',new Proxy({}, {get(){throw 'private';}}));else f.children[0].stderr.write(READY.replace(SECRET,'whsec_changed'));
    await stop(f);await rejects(f.adapter.inspectIntent(LIMIT()),'INTENT');await rejects(f.adapter.startListener(LIMIT()),'LISTENER');assert.equal(writes,1);
  }
  const f=await setup();await start(f);f.children[0].stderr.write(READY.replace(SECRET,'whsec_changed'));await stop(f);await rejects(f.adapter.startListener(LIMIT()),'LISTENER');
});
test('late persistence completion after abort never restores readiness or permits restart',async()=>{
  let release;const f=await setup({persistWebhookSecret:()=>new Promise(resolvePersist=>{release=resolvePersist;})});const abort=new AbortController();
  const pending=f.adapter.startListener({...LIMIT(),signal:abort.signal});await turn();f.children[0].stderr.write(READY);await turn();abort.abort();await rejects(pending,'LISTENER');release(true);await turn();await rejects(f.adapter.startListener(LIMIT()),'LISTENER');await rejects(f.adapter.bindMissedIntent(proof()),'PROOF');
});
test('bounded response headers in stdout still isolate the exact JSON balance body',async()=>{
  const f=fixture(),execute=f.dependencies.execute;f.dependencies.execute=async(...args)=>{const result=await execute(...args);return f.calls.length===3?{stdout:`< HTTP/1.1 200 OK\n< Stripe-Version: ${VERSION}\n\n`+result.stdout,stderr:''}:result;};
  const a=await createSandboxCliAdapter(f.options,f.dependencies);assert.equal(await a.validateBinding(LIMIT()),true);
});
test('private filesystem is revalidated between commands and optional credentials cannot be unsafe',async()=>{
  const f=fixture();let checks=0;f.dependencies.lstat=async path=>{const s=stat(!path.endsWith('.toml'));if(path===DIR&&++checks===2)s.mode=0o40755;return s;};
  const a=await createSandboxCliAdapter(f.options,f.dependencies);await rejects(a.validateBinding(LIMIT()),'BINDING');assert.equal(f.calls.length,1);
  const g=fixture();g.dependencies.readdir=async()=>['config.toml','credentials.json'];g.dependencies.lstat=async path=>{const s=stat(!path.endsWith('.toml')&&!path.endsWith('.json'));if(path.endsWith('.json'))s.nlink=2;return s;};
  const b=await createSandboxCliAdapter(g.options,g.dependencies);await rejects(b.validateBinding(LIMIT()),'BINDING');assert.equal(g.calls.length,0);
});
// An abort error callback alone is not evidence the one-shot child has exited.
test('default one-shot boundary awaits actual close after an abort callback',async()=>{
  const f=fixture();delete f.dependencies.execute;let callback,child,settings,finished=false;
  f.dependencies.execFile=(file,args,options,done)=>{callback=done;settings=options;child=new EventEmitter();return child;};
  const a=await createSandboxCliAdapter(f.options,f.dependencies),abort=new AbortController();
  const pending=a.validateBinding({...LIMIT(),signal:abort.signal});pending.catch(()=>{finished=true;});await turn();
  abort.abort();await turn();assert.equal(settings.signal.aborted,true);callback(new Proxy({}, {get(){throw 'private';}}),'private','private');await turn();assert.equal(finished,false);
  child.emit('close',null,'SIGTERM');await rejects(pending,'BINDING');assert.equal(finished,true);
});
test('login invocation is a frozen isolated descriptor with no process execution',async()=>{
  const f=await setup();const login=await f.adapter.loginInvocation(LIMIT());assert.equal(login.file,'/usr/bin/sandbox-exec');assert.ok(Object.isFrozen(login));assert.ok(Object.isFrozen(login.args));assert.ok(Object.isFrozen(login.options));assert.ok(Object.isFrozen(login.options.env));
  assert.equal(login.file,'/usr/bin/sandbox-exec');assert.deepEqual(login.args,['-p','(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))','/opt/homebrew/bin/stripe','--config',DIR+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error','login']);
  assert.deepEqual(login.options,{cwd:ROOT,env:{PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:DIR+'/home',XDG_CONFIG_HOME:DIR+'/config',NO_COLOR:'1'},shell:false,stdio:['pipe','pipe','pipe'],timeout:180000,maxBuffer:65536});assert.equal(f.calls.length,0);
  const g=fixture();g.dependencies.lstat=async()=>stat(false);const a=await createSandboxCliAdapter(g.options,g.dependencies);await rejects(a.loginInvocation(LIMIT()),'LOGIN');assert.equal(g.calls.length,0);
});
// Returning initiation argv or mutable caller state must never dispatch completion.
test('login completion returns only the frozen isolated device descriptor and metadata access',async()=>{
  for(const size of [0,65536]) {
    const f=fixture(),paths=[],opened=[],closed=[];
    f.dependencies.lstat=async path=>{paths.push(path);const s=stat(!path.endsWith('.toml')&&!path.endsWith('.json'));if(path.endsWith('/oauth_pending.json'))s.size=size;return s;};
    f.dependencies.open=async(path,flags)=>{opened.push(path);assert.equal(flags,constants.O_RDONLY|constants.O_NOFOLLOW);return {stat:async()=>({...stat(),size}),close:async()=>{closed.push(path);},get readFile(){assert.fail('completion must not read contents');}};};
    const a=await createSandboxCliAdapter(f.options,f.dependencies);
    f.options.cliDirectory='/mutated';f.options.runId='mutated';f.options.env.HOME='/mutated';f.options.approvedBinding.externalAccountId='acct_mutated';
    f.dependencies.lstat=async()=>assert.fail('dependencies must be snapshotted');
    const complete=await a.completeLoginInvocation({...LIMIT(),command:'arbitrary',cliDirectory:'/arbitrary'});
    assert.deepEqual(complete,{file:'/usr/bin/sandbox-exec',args:['-p','(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))','/opt/homebrew/bin/stripe','--config',DIR+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error','login','--complete-device'],options:{cwd:ROOT,env:{PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:DIR+'/home',XDG_CONFIG_HOME:DIR+'/config',NO_COLOR:'1'},shell:false,stdio:['pipe','pipe','pipe'],timeout:600000,maxBuffer:65536}});
    for(const value of [a,complete,complete.args,complete.options,complete.options.env,complete.options.stdio])assert.ok(Object.isFrozen(value));
    assert.throws(()=>complete.args.push('--arbitrary'),TypeError);assert.throws(()=>complete.options.env.HOME='/mutated',TypeError);
    assert.deepEqual(paths,[DIR,DIR+'/home',DIR+'/config',DIR+'/config/stripe',DIR+'/config/stripe/config.toml',DIR+'/config/stripe/oauth_pending.json']);
    assert.deepEqual(opened,[DIR+'/config/stripe/config.toml',DIR+'/config/stripe/oauth_pending.json']);assert.deepEqual(closed,opened);
    assert.equal(f.calls.length,0);assert.equal(f.children.length,0);assert.equal(f.timers.size,0);
  }
});
// Omitting any pending-file metadata guard would admit an unsafe OAuth session.
test('login completion refuses missing or unsafe pending metadata before any process',async()=>{
  const pending=DIR+'/config/stripe/oauth_pending.json';
  for(const stage of ['lstat','fstat'])for(const bad of ['missing','directory','symlink','hardlink','uid','mode','special-mode','oversize','negative-size','fractional-size','canonical']) {
    if(stage==='fstat'&&bad==='canonical')continue;
    const f=fixture();let pendingOpens=0,pendingCloses=0;
    const metadata=()=>{if(bad==='missing')throw new Error('synthetic missing');const s=stat();if(bad==='directory')s.isFile=()=>false;if(bad==='symlink')s.isSymbolicLink=()=>true;if(bad==='hardlink')s.nlink=2;if(bad==='uid')s.uid++;if(bad==='mode')s.mode=0o100644;if(bad==='special-mode')s.mode=0o104600;if(bad==='oversize')s.size=65537;if(bad==='negative-size')s.size=-1;if(bad==='fractional-size')s.size=0.5;return s;};
    const lstat=f.dependencies.lstat;f.dependencies.lstat=async path=>path===pending&&stage==='lstat'?metadata():lstat(path);
    f.dependencies.realpath=async path=>path===pending&&bad==='canonical'?path+'/different':path;
    f.dependencies.open=async(path,flags)=>{assert.equal(flags,constants.O_RDONLY|constants.O_NOFOLLOW);if(path===pending)pendingOpens++;return {stat:async()=>path===pending&&stage==='fstat'?metadata():stat(),close:async()=>{if(path===pending)pendingCloses++;}};};
    const a=await createSandboxCliAdapter(f.options,f.dependencies);await rejects(a.completeLoginInvocation(LIMIT()),'LOGIN');
    assert.equal(pendingOpens,stage==='fstat'?1:0);assert.equal(pendingCloses,pendingOpens);assert.equal(f.calls.length,0);assert.equal(f.children.length,0);assert.equal(f.timers.size,0);
  }
});
test('login completion rechecks private tree and optional credential metadata',async()=>{
  for(const bad of ['tree','config','credentials']) {
    const f=fixture(),lstat=f.dependencies.lstat;let pendingAccess=0;
    f.dependencies.readdir=async()=>['config.toml','credentials.json'];
    f.dependencies.lstat=async path=>{if(path.endsWith('/oauth_pending.json'))pendingAccess++;const s=await lstat(path);if(path===(bad==='tree'?DIR:DIR+'/config/stripe/'+(bad==='config'?'config.toml':'credentials.json')))s.mode=0o755;return s;};
    const a=await createSandboxCliAdapter(f.options,f.dependencies);await rejects(a.completeLoginInvocation(LIMIT()),'LOGIN');assert.equal(pendingAccess,0);assert.equal(f.calls.length,0);assert.equal(f.children.length,0);
  }
});
// A completion call must not bypass operation admission or retain cancellation.
test('login completion refuses invalid limits without filesystem or process access',async()=>{
  const aborted=new AbortController();aborted.abort();
  for(const limits of [undefined,{}, {deadline:Date.now()-1},{deadline:Infinity},{deadline:NaN},{...LIMIT(),signal:{}},{...LIMIT(),signal:aborted.signal}]) {
    const f=fixture();let accesses=0;f.dependencies.lstat=async()=>{accesses++;return stat(true);};
    const a=await createSandboxCliAdapter(f.options,f.dependencies);await rejects(a.completeLoginInvocation(limits),'LOGIN');assert.equal(accesses,0);assert.equal(f.calls.length,0);assert.equal(f.children.length,0);assert.equal(f.timers.size,0);
  }
});
test('login completion serializes and rechecks cancellation after pending metadata',async()=>{
  for(const cancel of ['signal','deadline']) {
    const f=fixture(),abort=new AbortController();let release,closed=0;
    f.dependencies.open=async path=>({stat:async()=>path.endsWith('/oauth_pending.json')&&!release?new Promise(resolveStat=>{release=()=>resolveStat(stat());}):stat(),close:async()=>{if(path.endsWith('/oauth_pending.json'))closed++;}});
    const a=await createSandboxCliAdapter(f.options,f.dependencies),completing=a.completeLoginInvocation({...LIMIT(),signal:abort.signal});await turn();
    await rejects(a.loginInvocation(LIMIT()),'BUSY');await rejects(a.completeLoginInvocation(LIMIT()),'BUSY');
    if(cancel==='signal')abort.abort();else [...f.timers.values()][0].callback();
    release();await rejects(completing,'LOGIN');assert.equal(closed,1);assert.equal(f.calls.length,0);assert.equal(f.children.length,0);assert.equal(f.timers.size,0);
    assert.deepEqual((await a.completeLoginInvocation(LIMIT())).args.slice(11),['login','--complete-device']);
  }
});
test('login completion admission flags precede every private or dependency getter',async()=>{
  for(const env of [{},{PAYMENT_STRIPE_SANDBOX_ENABLED:'true'},{PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'}]) {
    let touched=0;const options={env};
    for(const key of ['runId','cliDirectory','approvedBinding','persistWebhookSecret','completeLoginInvocation'])Object.defineProperty(options,key,{get(){touched++;throw new Error('private');}});
    const dependencies=new Proxy({}, {get(){touched++;throw new Error('private');}});
    await assert.rejects(createSandboxCliAdapter(options,dependencies),{message:'stripe_sandbox_cli_OPT_IN_REQUIRED'});assert.equal(touched,0);
  }
});
test('cumulative pre-Ready output is bounded while running output is drained without history',async()=>{
  const f=await setup(),pending=f.adapter.startListener(LIMIT());await turn();f.children[0].stdout.write(('x'.repeat(1000)+'\n').repeat(66));await rejects(pending,'LISTENER');assert.deepEqual(f.children[0].signals,['SIGINT']);
  const g=await setup();await start(g);g.children[0].stdout.write(('x'.repeat(1000)+'\n').repeat(100));assert.equal(await g.adapter.bindMissedIntent(proof()),true);await stop(g);
});
test('deadline expiry cancels persistence and cleanup awaits the same child',async()=>{
  let release;const f=await setup({persistWebhookSecret:()=>new Promise(resolvePersist=>{release=resolvePersist;})});const pending=f.adapter.startListener(LIMIT());await turn();f.children[0].stderr.write(READY);await turn();
  const operationTimer=[...f.timers.values()].find(timer=>timer.ms>10000);assert.ok(operationTimer);operationTimer.callback();await rejects(pending,'LISTENER');release(true);await turn();assert.deepEqual(f.children[0].signals,['SIGINT']);await rejects(f.adapter.startListener(LIMIT()),'LISTENER');
});
test('changed or nonpositive child PID never grants kill authority',async()=>{
  const f=await setup();await start(f);f.children[0].pid=9999;await rejects(stop(f),'STOP');assert.deepEqual(f.children[0].signals,[]);await rejects(f.adapter.startListener(LIMIT()),'LISTENER');
  const g=fixture(),spawnFake=g.dependencies.spawn;g.dependencies.spawn=(...args)=>{const child=spawnFake(...args);child.pid=0;return child;};g.adapter=await createSandboxCliAdapter(g.options,g.dependencies);await rejects(g.adapter.startListener(LIMIT()),'LISTENER');assert.deepEqual(g.children[0].signals,[]);
});
