import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import {syncBuiltinESMExports} from 'node:module';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import * as transport from './stripe-sandbox-cli.mjs';

mock.method(childProcess,'execFile',()=>{throw Error('synthetic_test_forbids_real_process');});
mock.method(childProcess,'spawn',()=>{throw Error('synthetic_test_forbids_real_process');});
syncBuiltinESMExports();
after(()=>{mock.restoreAll();syncBuiltinESMExports();});
const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const ORIGINAL='031b5ae7-6603-4339-b1b3-77231291ff34',PRIOR='34941d87-12d3-4c74-8c5a-b62b7c914390';
const PAYMENT='11111111-2222-4333-8444-555555555555',SITE='22222222-3333-4444-8555-666666666666';
const ACCOUNT='acct_1UPiMpELswAd8tpR',VERSION='2026-09-30.endive',SECRET='whsec_syntheticOriginal123';
const DIR=ROOT+'/test-results/stripe-sandbox/'+ORIGINAL+'/cli';
const READY=`Ready! You are using Stripe API Version [${VERSION}]. Your webhook signing secret is ${SECRET} (^C to quit)\n`;
const turn=()=>new Promise(r=>setImmediate(r));
const prior=()=>({refundId:'66666666-7777-4888-8999-000000000000',providerReference:'re_first',providerEventId:'evt_first'});
const remainder=()=>({refundId:'66666666-7777-4888-8999-000000000001',providerReference:'re_second',providerEventId:'evt_second',amountMinor:1300,totalMinor:2000});
const refund=(second=false)=>({id:second?'re_second':'re_first',object:'refund',status:'succeeded',amount:second?1300:700,currency:'eur',charge:'ch_original',payment_intent:'pi_original',metadata:{payment_id:PAYMENT,refund_id:(second?remainder():prior()).refundId}});
function target(){return {original:{originalRunId:ORIGINAL,originalSource:'f0620e40004162d2b3eededa60512f50a492f4cc',project:'stripe-'+ORIGINAL,containers:{},images:{},network:{},volumes:{},ports:{},endpoints:{},account:{id:'44444444-5555-4666-8777-888888888888',key:'stripe-sandbox-default',externalId:ACCOUNT},site:{id:SITE,key:'s5a-original'},price:{id:'55555555-6666-4777-8888-999999999999',key:'s5a-price-original',version:1,providerPriceId:'price_original'},paymentId:PAYMENT,operationId:'33333333-4444-4555-8666-777777777777',intentId:'pi_original',callbackVersion:1,amountMinor:2000,currency:'EUR',source:'CATALOG'},priorRunId:PRIOR,priorSource:'a'.repeat(40),priorRefundId:prior().refundId,remainderKey:'s5a-refund:'+PRIOR+':remainder',callbackVersionBefore:2,callbackVersionAfter:3};}
function intent(total){return {id:'pi_original',object:'payment_intent',livemode:false,status:'succeeded',amount:2000,amount_received:2000,currency:'eur',capture_method:'automatic',customer:null,payment_method_types:['card'],metadata:{payment_id:PAYMENT,site_id:SITE,source:'CATALOG'},latest_charge:{id:'ch_original',object:'charge',livemode:false,customer:null,payment_intent:'pi_original',status:'succeeded',paid:true,captured:true,amount:2000,amount_captured:2000,currency:'eur',payment_method_details:{type:'card'},amount_refunded:total,refunded:total===2000,refunds:{object:'list',has_more:false,data:total===2000?[refund(),refund(true)]:total===700?[refund()]:[]}}};}
const stat=(dir=false)=>({uid:process.getuid(),mode:dir?0o40700:0o100600,nlink:1,size:10,isDirectory:()=>dir,isFile:()=>!dir,isSymbolicLink:()=>false});
function fixture(){
  const f={total:700,calls:[],children:[],timers:new Map(),mutate:()=>{},mutateRefund:()=>{},mutateEvent:()=>{},limits:{deadline:Date.now()+60000}};
  f.options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'},originalRunId:ORIGINAL,priorRunId:PRIOR,cliDirectory:DIR,backendPort:18080,approvedBinding:{externalAccountId:ACCOUNT,apiVersion:VERSION},verifyForwardTarget:async()=>true,persistWebhookSecret:async(value,limits)=>{assert.equal(value,SECRET);assert.equal(limits.deadline,f.limits.deadline);return true;}};
  let timerId=0;
  f.deps={lstat:async path=>stat(!path.endsWith('.toml')&&!path.endsWith('.json')),realpath:async path=>path,readdir:async()=>['config.toml'],open:async()=>({stat:async()=>stat(),close:async()=>{}}),
    setTimeout:(callback,ms)=>{const id=++timerId;f.timers.set(id,{callback,ms});return id;},clearTimeout:id=>f.timers.delete(id),
    execute:async(file,args,settings)=>{
      f.calls.push({file,args,settings});const command=args.slice(11);let body;
      if(command[0]==='--version')return {stdout:'stripe version 1.52.0\n',stderr:''};
      if(command[0]==='whoami')body={account_id:ACCOUNT,mode:'test',authorized_accounts:[{id:ACCOUNT,modes:['test']}]};
      else if(command[1]==='/v1/balance')return {stdout:JSON.stringify({object:'balance',livemode:false}),stderr:`< HTTP 200\n< Stripe-Version: ${VERSION}\n`};
      else if(command[1]==='/v1/payment_intents/pi_original'){assert.deepEqual(command,['get','/v1/payment_intents/pi_original','--expand','latest_charge.refunds']);body=intent(f.total);f.mutate(body);}
      else if(['/v1/refunds/re_first','/v1/refunds/re_second'].includes(command[1])){assert.equal(command.length,2);body=refund(command[1].endsWith('re_second'));f.mutateRefund(body);}
      else if(['/v1/events/evt_first','/v1/events/evt_second'].includes(command[1])){assert.equal(command.length,2);const second=command[1].endsWith('evt_second');body={id:second?'evt_second':'evt_first',object:'event',type:'refund.updated',api_version:VERSION,livemode:false,data:{object:refund(second)}};f.mutateEvent(body);}
      else assert.fail('unexpected command '+command.join(' '));
      return {stdout:JSON.stringify(body),stderr:''};
    },spawn:(file,args,settings)=>{const child=new EventEmitter();child.pid=4242;child.stdout=new PassThrough();child.stderr=new PassThrough();child.signals=[];child.kill=signal=>{child.signals.push(signal);queueMicrotask(()=>{child.emit('exit',0);if(child.autoClose!==false)child.emit('close',0);});return true;};f.children.push(child);f.calls.push({file,args,settings,child});return child;},
  };return f;
}
async function create(options,deps){assert.equal(typeof transport.createManualRefundRemainderCliAdapter,'function','separate remainder read-only factory is required');const boundary=await import('./stripe-manual-refund-remainder-cli.mjs');assert.equal(boundary.createManualRefundRemainderCliAdapter,transport.createManualRefundRemainderCliAdapter);return boundary.createManualRefundRemainderCliAdapter(options,deps);}
async function setup({start=true,bind=true,configure=()=>{}}={}){const f=fixture();configure(f);f.cli=await create(f.options,f.deps);if(start){const pending=f.cli.startListener(f.limits);await turn();f.children.at(-1)?.stderr.write(READY);await pending;}if(bind)await f.cli.bindRemainderTarget(target(),f.limits);return f;}
const reject=promise=>assert.rejects(promise,/^Error: stripe_sandbox_cli_/);
const close=f=>f.cli.stopListener({deadline:f.limits.deadline+120000});

test('new factory denies bad opt-in before configuration/dependency getters and exposes no login/write authority',async()=>{
  for(const env of [{},{PAYMENT_STRIPE_SANDBOX_ENABLED:'true'}, {...fixture().options.env,PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND:'true'}]){let touched=0;const options={env,get priorRunId(){touched++;throw null;}},deps=new Proxy({},{get(){touched++;throw null;}});await reject(create(options,deps));assert.equal(touched,0);}
  const f=await setup();assert.deepEqual(Object.keys(f.cli).sort(),['assertRemainderListener','bindRemainderTarget','inspectRemainder','startListener','stopListener','validateBinding','verifyPriorPartial']);for(const key of ['login','completeLogin','loginInvocation','completeLoginInvocation','inspectRefund','bindCapturedPayment','execute','get','post'])assert.equal(key in f.cli,false);await close(f);
});
test('exact partial retrieve/Event/aggregate seeds only its own verified identity and final proof rereads both legs',async()=>{
  const f=await setup();assert.deepEqual(await f.cli.verifyPriorPartial(prior(),f.limits),{providerVerified:true,webhookVerified:true});assert.equal(f.cli.assertRemainderListener(f.limits),true);await f.cli.verifyPriorPartial(prior(),f.limits);
  f.total=2000;assert.deepEqual(await f.cli.inspectRemainder(remainder(),f.limits),{providerVerified:true,webhookVerified:true});await f.cli.inspectRemainder(remainder(),f.limits);
  assert.ok(f.calls.filter(c=>c.args.includes('/v1/events/evt_first')).length>=4);assert.ok(f.calls.every(c=>!c.args.some(a=>['post','login','--live','--latest','--print-json','--skip-verify'].includes(a))));
  for(const call of f.calls){assert.equal(call.file,'/usr/bin/sandbox-exec');assert.equal(call.settings.env.HOME,DIR+'/home');assert.equal(call.settings.env.PATH,'/usr/bin:/bin:/usr/sbin:/sbin');assert.equal(call.settings.env.STRIPE_API_KEY,undefined);if(!call.child)assert.ok(call.settings.timeout<=10000);}
  await close(f);
});
test('binding never seeds caller partial booleans and is permitted only once after listener',async()=>{
  const f=await setup();await reject(f.cli.inspectRemainder({...remainder(),providerVerified:true},f.limits));await reject(f.cli.inspectRemainder(remainder(),f.limits));await close(f);
  const g=await setup({bind:false});await g.cli.bindRemainderTarget(target(),g.limits);await reject(g.cli.bindRemainderTarget(target(),g.limits));await close(g);
  const h=await setup({start:false,bind:false});await reject(h.cli.bindRemainderTarget(target(),h.limits));assert.equal(h.calls.length,0);
});
for(const [name,change] of [
  ['wrong prior local id',v=>v.refundId=PAYMENT],['extra caller proof boolean',v=>v.providerVerified=true],['extra caller amount',v=>v.amountMinor=700],['null reference',v=>v.providerReference=null],['null Event',v=>v.providerEventId=null],
])test('prior identity rejects '+name,async()=>{const f=await setup(),v=prior();change(v);await reject(f.cli.verifyPriorPartial(v,f.limits));await reject(f.cli.inspectRemainder(remainder(),f.limits));await close(f);});
for(const [name,change] of [
  ['prior run',v=>v.priorRunId=PAYMENT],['prior source',v=>v.priorSource='bad'],['prior refund null',v=>v.priorRefundId=null],['financial key',v=>v.remainderKey='other'],['callback version',v=>v.callbackVersionAfter=2],['original source',v=>v.original.originalSource='a'.repeat(40)],['account',v=>v.original.account.externalId='acct_other'],['flat target',v=>Object.assign(v,{...v.original})],['getter',v=>Object.defineProperty(v,'priorRefundId',{enumerable:true,get(){assert.fail('must not evaluate');}})],
])test('remainder binding rejects '+name,async()=>{const f=await setup({bind:false}),v=target();change(v);await reject(f.cli.bindRemainderTarget(v,f.limits));await close(f);});
for(const [name,change] of [
  ['Intent live',f=>f.mutate=p=>p.livemode=true],['Intent metadata',f=>f.mutate=p=>p.metadata.site_id=PAYMENT],['Intent customer',f=>f.mutate=p=>p.customer='cus_other'],['capture method',f=>f.mutate=p=>p.capture_method='manual'],['card type',f=>f.mutate=p=>p.payment_method_types=['card','link']],['amount',f=>f.mutate=p=>p.amount_received=1999],['status',f=>f.mutate=p=>p.status='processing'],['null charge',f=>f.mutate=p=>p.latest_charge=null],['uncaptured',f=>f.mutate=p=>p.latest_charge.captured=false],['aggregate',f=>f.mutate=p=>p.latest_charge.amount_refunded=2000],['refunded',f=>f.mutate=p=>p.latest_charge.refunded=true],['has_more',f=>f.mutate=p=>p.latest_charge.refunds.has_more=true],['extra refund',f=>f.mutate=p=>p.latest_charge.refunds.data.push({...refund(),id:'re_other'})],['missing refunds',f=>f.mutate=p=>delete p.latest_charge.refunds],['refund metadata',f=>f.mutateRefund=r=>r.metadata.refund_id=PAYMENT],['refund status',f=>f.mutateRefund=r=>r.status='pending'],['refund live',f=>f.mutateRefund=r=>r.livemode=true],['refund charge',f=>f.mutateRefund=r=>r.charge='ch_other'],['refund reference',f=>f.mutateRefund=r=>r.id=null],['Event identity',f=>f.mutateEvent=e=>e.id='evt_other'],['Event type',f=>f.mutateEvent=e=>e.type='refund.failed'],['Event API',f=>f.mutateEvent=e=>e.api_version='other'],['Event account',f=>f.mutateEvent=e=>e.account='acct_other'],['Event live',f=>f.mutateEvent=e=>e.livemode=true],['Event data',f=>f.mutateEvent=e=>e.data.object.metadata.refund_id=PAYMENT],
])test('invalid partial '+name+' grants no seed even after a later repair',async()=>{const f=await setup();change(f);await reject(f.cli.verifyPriorPartial(prior(),f.limits));f.mutate=()=>{};f.mutateRefund=()=>{};f.mutateEvent=()=>{};f.total=2000;await reject(f.cli.inspectRemainder(remainder(),f.limits));await close(f);});
test('only the identical verified partial may be rechecked and it always requires aggregate700',async()=>{
  for(const change of [v=>v.providerReference='re_other',v=>v.providerEventId='evt_other',v=>v.refundId=PAYMENT]){const f=await setup();await f.cli.verifyPriorPartial(prior(),f.limits);const v=prior();change(v);const count=f.calls.length;await reject(f.cli.verifyPriorPartial(v,f.limits));assert.equal(f.calls.length,count);await close(f);}
  const f=await setup();await f.cli.verifyPriorPartial(prior(),f.limits);f.total=2000;await reject(f.cli.verifyPriorPartial(prior(),f.limits));await close(f);
});
for(const [name,change] of [
  ['same reference',f=>f.value.providerReference='re_first'],['same Event',f=>f.value.providerEventId='evt_first'],['same local id',f=>f.value.refundId=prior().refundId],['wrong amount',f=>f.value.amountMinor=700],['wrong total',f=>f.value.totalMinor=1300],['changed charge',f=>f.mutate=p=>{p.latest_charge.id='ch_changed';for(const r of p.latest_charge.refunds.data)r.charge='ch_changed';}],['changed prior Event',f=>f.mutateEvent=e=>{if(e.id==='evt_first')e.data.object.amount=1300;}],['changed prior Refund',f=>f.mutateRefund=r=>{if(r.id==='re_first')r.metadata.refund_id=PAYMENT;}],['truncated final list',f=>f.mutate=p=>p.latest_charge.refunds.has_more=true],['duplicate final reference',f=>f.mutate=p=>p.latest_charge.refunds.data[1]=refund()],['final false',f=>f.mutate=p=>p.latest_charge.refunded=false],
])test('final proof rejects '+name,async()=>{const f=await setup();await f.cli.verifyPriorPartial(prior(),f.limits);f.total=2000;f.value=remainder();change(f);await reject(f.cli.inspectRemainder(f.value,f.limits));await close(f);});
test('listener loss between reads is sticky and the synchronous fence performs no IO',async()=>{
  const f=await setup();await f.cli.verifyPriorPartial(prior(),f.limits);const count=f.calls.length,timers=f.timers.size;assert.equal(f.cli.assertRemainderListener(f.limits),true);assert.equal(f.calls.length,count);assert.equal(f.timers.size,timers);f.children[0].emit('exit',1);assert.throws(()=>f.cli.assertRemainderListener(f.limits));await reject(f.cli.startListener(f.limits));await close(f);
  const g=await setup();g.mutateEvent=()=>g.children[0].emit('error',Error('synthetic'));await reject(g.cli.verifyPriorPartial(prior(),g.limits));g.total=2000;await reject(g.cli.inspectRemainder(remainder(),g.limits));await close(g);
});
test('original admission cancellation remains effective with omitted or replacement later signal',async()=>{
  for(const replacement of [undefined,new AbortController().signal]){const abort=new AbortController();const f=await setup({configure:f=>f.limits.signal=abort.signal});await f.cli.verifyPriorPartial(prior(),f.limits);abort.abort();assert.throws(()=>f.cli.assertRemainderListener({deadline:f.limits.deadline,signal:replacement}));await reject(f.cli.verifyPriorPartial(prior(),{deadline:f.limits.deadline,signal:replacement}));await close(f);}
});
for(const kind of ['credentials','mixed account','live','API','CLI','private config','webhook verifier'])test('retained '+kind+' refusal exposes no OAuth fallback',async()=>{
  const f=fixture(),execute=f.deps.execute;if(kind==='webhook verifier')f.options.persistWebhookSecret=async()=>false;
  if(kind==='private config')f.deps.lstat=async()=>({...stat(),mode:0o100644});
  f.deps.execute=async(file,args,settings)=>{if(args.includes('whoami')){if(kind==='credentials')throw null;if(kind==='mixed account')return {stdout:JSON.stringify({account_id:ACCOUNT,mode:'test',authorized_accounts:[{id:ACCOUNT,modes:['test','live']}]}),stderr:''};if(kind==='live')return {stdout:JSON.stringify({account_id:ACCOUNT,mode:'live',authorized_accounts:[{id:ACCOUNT,modes:['live']}]}),stderr:''};}if(kind==='API'&&args.includes('/v1/balance'))return {stdout:JSON.stringify({object:'balance',livemode:false}),stderr:'< HTTP 200\n< Stripe-Version: other\n'};if(kind==='CLI'&&args.includes('--version'))return {stdout:'stripe version 1.53.0\n',stderr:''};return execute(file,args,settings);};
  f.cli=await create(f.options,f.deps);const refused=reject(f.cli.startListener(f.limits));await turn();f.children.at(-1)?.stderr.write(READY);await refused;assert.equal('loginInvocation' in f.cli,false);assert.equal(f.calls.some(c=>c.args.includes('login')),false);
});
test('cleanup awaits the owned child close and a changed PID never grants kill authority',async()=>{
  const f=await setup();f.children[0].autoClose=false;let done=false;const pending=close(f).then(v=>{done=true;return v;});await turn();assert.equal(done,false);f.children[0].emit('close',0);assert.equal(await pending,true);
  const g=await setup();g.children[0].pid++;assert.throws(()=>g.cli.assertRemainderListener(g.limits));await reject(close(g));assert.deepEqual(g.children[0].signals,[]);
});
test('late verifier/stdout/exit/error after abort cannot resurrect listener readiness',async()=>{
  let release;const f=fixture(),abort=new AbortController();f.limits.signal=abort.signal;f.options.persistWebhookSecret=()=>new Promise(r=>{release=r;});f.cli=await create(f.options,f.deps);const pending=f.cli.startListener(f.limits);await turn();f.children[0].stderr.write(READY);await turn();abort.abort();await reject(pending);release(true);f.children[0].stderr.write(READY);f.children[0].emit('exit',0);f.children[0].emit('error',Error('synthetic'));await turn();await reject(f.cli.bindRemainderTarget(target(),f.limits));await reject(f.cli.startListener(f.limits));
});
test('same prior Event identity cannot change its immutable Event type during a recheck',async()=>{
  const f=await setup();await f.cli.verifyPriorPartial(prior(),f.limits);
  f.mutateEvent=e=>{if(e.id==='evt_first')e.type='refund.created';};
  await reject(f.cli.verifyPriorPartial(prior(),f.limits));await close(f);
});
test('dispatch fence requires its own partial proof, never just a bound target',async()=>{
  const f=await setup();assert.throws(()=>f.cli.assertRemainderListener(f.limits));
  await reject(f.cli.verifyPriorPartial(prior(),f.limits));await close(f);
});
test('provider proof cannot spend more than45 seconds across individually bounded reads',async()=>{
  let now=Date.now(),slow=false;const clock=mock.method(Date,'now',()=>now);
  try{
    const f=await setup({configure:f=>{const execute=f.deps.execute;f.deps.execute=async(...args)=>{const value=await execute(...args);if(slow)now+=9000;return value;};}});
    slow=true;await reject(f.cli.verifyPriorPartial(prior(),f.limits));await close(f);
  }finally{clock.mock.restore();}
});
test('runtime verifier callbacks receive only the accepted public Limits fields',async()=>{
  const received=[];
  const f=await setup({configure:f=>{
    f.options.verifyForwardTarget=async limits=>{received.push(Object.keys(limits).sort());return true;};
    f.options.persistWebhookSecret=async(candidate,limits)=>{assert.equal(candidate,SECRET);received.push(Object.keys(limits).sort());return true;};
  }});
  assert.deepEqual(received,[['deadline','signal'],['deadline','signal']]);
  await f.cli.verifyPriorPartial(prior(),f.limits);await close(f);
});
test('original admission abort cancels an in-flight read even when later limits omit its signal',async()=>{
  const abort=new AbortController();let release,readSignal;
  const f=await setup({configure:f=>{
    f.limits.signal=abort.signal;const execute=f.deps.execute;
    f.deps.execute=async(file,args,settings)=>{const result=await execute(file,args,settings);if(args.includes('/v1/payment_intents/pi_original')){readSignal=settings.signal;await new Promise(r=>{release=r;});}return result;};
  }});
  const refused=reject(f.cli.verifyPriorPartial(prior(),{deadline:f.limits.deadline}));await turn();abort.abort();const canceled=readSignal.aborted;release();await refused;await close(f);assert.equal(canceled,true);
});
test('a child returned from spawn after admission abort remains owned and cannot become ready later',async()=>{
  const f=fixture(),abort=new AbortController();f.limits.signal=abort.signal;const spawn=f.deps.spawn;
  f.deps.spawn=(...args)=>{const child=spawn(...args);abort.abort();queueMicrotask(()=>child.stderr.write(READY));return child;};
  f.cli=await create(f.options,f.deps);await reject(f.cli.startListener(f.limits));assert.deepEqual(f.children[0].signals,['SIGINT']);
  f.children[0].stderr.write(READY);await reject(f.cli.bindRemainderTarget(target(),f.limits));await reject(f.cli.startListener({deadline:f.limits.deadline}));
});
