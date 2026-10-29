import {test} from 'node:test';
import assert from 'node:assert/strict';

test('CLI rejects changing throwing and inherited env containers without reading them',async t=>{
  const {runManualPaymentCli:accept}=await import('./stripe-manual-payment.mjs');
  const flags={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true',PAYMENT_STRIPE_SANDBOX_RUN_ID:'11111111-2222-4333-8444-555555555555',PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:'e'.repeat(40),PAYMENT_STRIPE_SANDBOX_DAEMON:'unix:///Users/synthetic/docker.sock',PAYMENT_STRIPE_SANDBOX_PORT_MANUAL:'18087'};
  for(const [index,name]of ['BACKEND','DEMO','RECEIVER','POSTGRES','MAILPITHTTP','MAILPITSMTP'].entries())flags['PAYMENT_STRIPE_SANDBOX_PORT_'+name]=String(18080+index);
  for(const shape of ['throwing','changing','inherited'])await t.test(shape,async()=>{
    let reads=0,factories=0;
    const getter=()=>{reads++;if(shape==='throwing')throw 'secret_canary';return reads===1?{}:flags;};
    const input=shape==='inherited'?Object.assign(Object.create({get env(){reads++;return flags;}}),{args:[]}):Object.defineProperty({args:[]},'env',{enumerable:true,get:getter});
    const denied=()=>{factories++;throw null;};
    const result=await accept(input,{write:async()=>{},subscribe:()=>()=>{},preflight:denied,createBootstrap:denied,openState:denied,createRuntime:denied});
    assert.equal(result.exitCode,1);assert.equal(reads,0,shape);assert.equal(factories,0,shape);
  });
  let captures=0,gets=0;
  const input=new Proxy({env:flags,args:[]},{get(target,key){if(key==='env'){gets++;throw null;}return Reflect.get(target,key);},getOwnPropertyDescriptor(target,key){if(key==='env')captures++;return Reflect.getOwnPropertyDescriptor(target,key);}});
  assert.equal((await accept(input,{write:async()=>{},subscribe:()=>()=>{}})).exitCode,1);
  assert.equal(captures,1);assert.equal(gets,0);
  let authority=0;
  const admitted=new Proxy({env:{...flags,PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:undefined},args:[]},{get(target,key){if(key==='env'){gets++;throw null;}return Reflect.get(target,key);},getOwnPropertyDescriptor(target,key){if(key==='env')captures++;return Reflect.getOwnPropertyDescriptor(target,key);}});
  captures=0;
  const denied=()=>{authority++;throw null;};
  assert.equal((await accept(admitted,{write:async()=>{},subscribe:()=>()=>{},preflight:denied,createBootstrap:denied,openState:denied,createRuntime:denied})).exitCode,1);
  assert.equal(captures,1);assert.equal(gets,0);assert.equal(authority,1);
});

// Break: new-mode exclusion after normalization or evaluating an accessor.
test('remainder exclusion precedes snapshots configuration and dependencies',async()=>{
  const {runManualPaymentCli:accept}=await import('./stripe-manual-payment.mjs'); const base={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'};
  let reads=0;const trap=new Proxy({},{get(){reads++;throw null;}});
  for(const shape of ['true','malformed','boolean','accessor','hidden','inherited']){
    const raw=shape==='inherited'?Object.assign(Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'}),base):{...base};
    if(shape!=='inherited')Object.defineProperty(raw,'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',shape==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:shape!=='hidden',value:shape==='true'||shape==='hidden'?'true':shape==='boolean'?false:{private:'canary'}});
    Object.assign(raw,{PAYMENT_STRIPE_SANDBOX_RUN_ID:'11111111-2222-4333-8444-555555555555',PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:'e'.repeat(40),PAYMENT_STRIPE_SANDBOX_DAEMON:'unix:///Users/synthetic/docker.sock',PAYMENT_STRIPE_SANDBOX_PORT_MANUAL:'18087'});
    for(const [index,name]of ['BACKEND','DEMO','RECEIVER','POSTGRES','MAILPITHTTP','MAILPITSMTP'].entries())raw['PAYMENT_STRIPE_SANDBOX_PORT_'+name]=String(18080+index);
    const denied=()=>{reads++;throw null;};
    const value=await accept({env:raw,args:[]},{write:async()=>{},subscribe:()=>()=>{},preflight:denied,createBootstrap:denied,openState:denied,createRuntime:denied});
    assert.equal(value.exitCode,1);
  }
  assert.equal(reads,0);
});
import {EventEmitter} from 'node:events';
import {authorizeManualPaymentCli} from './stripe-manual-payment-auth.mjs';
import {createManualPaymentProvisioner} from './stripe-form-diagnostic-provision.mjs';
import {preflightOwnedManualPayment} from './stripe-sandbox-owned.mjs';
const module=await import('./stripe-manual-payment.mjs').catch(()=>({}));
const {runManualPayment=async()=>({report:{result:'FAILED'},exitCode:1}),runManualPaymentCli=async()=>({exitCode:1})}=module;
const runId='7deaf3e1-1247-4d80-9cf5-67ebcd24429a',expectedSource='a'.repeat(40);
const options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true'},runId,expectedSource,daemon:'unix:///Users/viktar.marozau/.docker/run/docker.sock',ports:{backend:18101,demo:18102,receiver:18103,postgres:18104,mailpitHttp:18105,mailpitSmtp:18106},manualPort:18107};
function fixture(settings={}){
  const calls=[],argumentsSeen=[],timers=new Map(),limits={deadline:2701000},clock={value:1000},proof={paymentConfirmed:true,webhookVerified:true,journalVerified:true,callbackVerified:true,providerEventId:'evt_original'};
  let grant,financialRetries=0;
  const action=(name,value=true)=>async(...args)=>{if(['provision','durablePermit'].includes(name)&&calls.includes(name))financialRetries++;calls.push(name);argumentsSeen.push([name,args]);if(settings.fail===name)throw Error('sk_test_canary');if(settings.advance===name)clock.value=limits.deadline;return typeof value==='function'?value(...args):value;};
  const bootstrap={prepare:action('prepare'),resources:()=>({runId,status:'PREPARED',cliDirectory:new URL('../test-results/stripe-sandbox/'+runId+'/cli',import.meta.url).pathname,ports:options.ports}),persistWebhookSecret:()=>{},verifyForwardTarget:()=>{},start:action('start'),verify:action('verify'),stop:action('stop',settings.runtimeClosed??true)};
  const cli={validateBinding:action('binding'),startListener:action('listener'),stopListener:action('stopListener',settings.listenerClosed??true),bindManualPayment:action('bindPayment'),verifyManualPaymentBaseline:action('freshBaseline'),assertManualPaymentListener:l=>{calls.push('assertListener');argumentsSeen.push(['assertListener',[l]]);if(settings.listenerLost)throw Error('synthetic listener lost');return true;},inspectManualPayment:action('provider',{captured:true,eventVerified:true}),inspectUnusedManualPayment:action('unusedProvider')};
  const provisioner={prepare:action('provision',{session:{},binding:{}}),markConfirmation:action('durablePermit'),verifyPayment:action('server',()=>settings.proof??proof),verifyUnused:action('unusedServer'),close:action('closeProvisioner')};
  const gateway={open:action('open',{url:'http://127.0.0.1:18107/manual-payment.html'}),wait:action('wait',async()=>{if(settings.outcome==='NOT_STARTED')return {outcome:'NOT_STARTED'};if(settings.twice){await grant();await assert.rejects(grant());}else await grant();return {outcome:settings.outcome??'CONFIRMATION_GRANTED'};}),close:action('closeGateway',settings.gatewayClosed??true)};
  const deps={now:()=>clock.value,setTimeout:(fn,delay)=>{const id={};timers.set(id,{fn,at:clock.value+delay});return id;},clearTimeout:id=>timers.delete(id),preflight:action('preflight',{status:'READY'}),createBootstrap:action('bootstrap',bootstrap),createCli:action('cli',cli),authorizeCli:action('auth'),createProvisioner:action('provisioner',provisioner),createGateway:action('gateway',value=>{grant=value.authorizeConfirmation;return gateway;}),write:action('write')};
  const tick=async at=>{clock.value=at;for(const [id,t] of [...timers])if(t.at<=at){timers.delete(id);t.fn();}for(let i=0;i<200;i++)await Promise.resolve();};
  return {calls,argumentsSeen,timers,limits,clock,deps,bootstrap,cli,provisioner,gateway,tick,get grant(){return grant;},get financialRetries(){return financialRetries;}};
}
const defer=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
test('one controller follows baseline durable grant server provider and independent cleanup order',async()=>{
  const f=fixture();const value=await runManualPayment(options,f.limits,f.deps);
  assert.equal(value.report.result,'SUCCEEDED');assert.equal(value.exitCode,0);
  assert.deepEqual(f.calls,['preflight','bootstrap','prepare','cli','auth','binding','listener','start','verify','provisioner','provision','bindPayment','gateway','open','write','wait','freshBaseline','assertListener','durablePermit','assertListener','server','provider','closeGateway','closeProvisioner','stopListener','stop']);
  assert.equal(f.financialRetries,0);
});
test('one no-grant outcome requires both unused proofs and remains exit1',async()=>{
  const f=fixture({outcome:'NOT_STARTED'}),value=await runManualPayment(options,f.limits,f.deps);
  assert.equal(value.report.result,'NOT_STARTED');assert.equal(value.exitCode,1);assert.ok(f.calls.includes('unusedServer'));assert.ok(f.calls.includes('unusedProvider'));assert.ok(!f.calls.includes('durablePermit'));
});
for(const name of ['preflight','prepare','auth','binding','listener','start','verify','provision','bindPayment','freshBaseline','durablePermit','server','provider'])test('failure at '+name+' latches pending without a late unused or success upgrade',async()=>{
  const f=fixture({fail:name}),value=await runManualPayment(options,f.limits,f.deps);
  assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.ok(!f.calls.includes('unusedServer'));assert.ok(!f.calls.includes('unusedProvider'));assert.equal(f.financialRetries,0);
  if(name==='freshBaseline')assert.ok(!f.calls.includes('durablePermit'));
});
for(const key of ['gatewayClosed','listenerClosed','runtimeClosed'])test('unverified '+key+' fails but attempts remaining cleanup independently',async()=>{
  const f=fixture({[key]:false}),value=await runManualPayment(options,f.limits,f.deps);
  assert.equal(value.report.result,'PENDING');assert.equal(value.exitCode,1);assert.ok(f.calls.includes('stopListener'));assert.ok(f.calls.includes('stop'));
});
test('expired baseline cannot authorize durable permit or perform post-deadline financial reads',async()=>{
  const f=fixture({advance:'freshBaseline'}),value=await runManualPayment(options,f.limits,f.deps);
  assert.equal(value.report.result,'PENDING');assert.ok(!f.calls.includes('durablePermit'));assert.ok(!f.calls.includes('server'));assert.ok(!f.calls.includes('unusedProvider'));
});
test('concurrent/repeated grant never creates a second financial permission',async()=>{
  const f=fixture({twice:true}),value=await runManualPayment(options,f.limits,f.deps);
  assert.equal(f.calls.filter(x=>x==='durablePermit').length,1);assert.equal(value.exitCode,1);
});
test('malformed server proof or browser-only success cannot establish payment',async()=>{
  for(const settings of [{proof:{paymentConfirmed:true}},{outcome:'SUCCEEDED'}]){
    const f=fixture(settings),value=await runManualPayment(options,f.limits,f.deps);assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.ok(!f.calls.includes('provider'));
  }
});
test('invalid admission never touches dependencies or private state',async()=>{
  const deps=new Proxy({},{get(){throw Error('dependency touched');}});const value=await runManualPayment({...options,env:{}},{deadline:2701000},deps);assert.equal(value.report.phase,'ADMISSION');assert.equal(value.exitCode,1);
});
test('CLI rejects positional args and still owns exactly one safe report write',async()=>{
  const writes=[];let subscriptions=0,removals=0;
  const value=await runManualPaymentCli({env:options.env,args:['--retry']},{write:async wire=>{writes.push(wire);},subscribe:()=>{subscriptions++;return ()=>removals++;}});
  assert.equal(value.exitCode,1);assert.equal(writes.length,1);assert.equal(subscriptions,1);assert.equal(removals,1);assert.ok(!writes[0].includes('sk_test'));
});
test('every financial action retains one original deadline and normalized flags',async()=>{
  const f=fixture();await runManualPayment({...options,env:{...options.env,PAYMENT_STRIPE_SANDBOX_FULL_RUN:'false',PWDEBUG:'0'}},f.limits,f.deps);
  for(const name of ['prepare','binding','listener','start','verify','provision','freshBaseline','durablePermit','server'])assert.equal(f.argumentsSeen.find(x=>x[0]===name)[1][0].deadline,2701000,name);
  for(const name of ['stopListener','stop','closeProvisioner','closeGateway'])assert.equal(f.argumentsSeen.find(x=>x[0]===name)[1][0].deadline,2821000,name);
  assert.deepEqual(f.argumentsSeen.find(x=>x[0]==='bootstrap')[1][0].env,options.env);assert.equal(f.timers.size,0);
});
test('auth failure cannot claim separately owned authorization children closed',async()=>{
  const f=fixture({fail:'auth'}),value=await runManualPayment(options,f.limits,f.deps);assert.equal(value.report.listener_closed,false);assert.ok(!f.calls.includes('listener'));
});
test('successful financial proof may use the original cleanup ceiling after active expiry',async()=>{
  const f=fixture();f.cli.inspectManualPayment=async()=>{f.clock.value=2700999;return {captured:true,eventVerified:true};};f.gateway.close=async l=>{assert.equal(l.deadline,2821000);await f.tick(2701001);return true;};
  const value=await runManualPayment(options,f.limits,f.deps);assert.equal(value.report.result,'SUCCEEDED');assert.equal(value.exitCode,0);assert.equal(f.timers.size,0);
});
test('abort during human wait revokes captured permission and forbids final provider reads',async()=>{
  const f=fixture(),controller=new AbortController(),pending=defer();f.limits.signal=controller.signal;f.gateway.wait=()=>pending.promise;
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);controller.abort();await f.tick(1001);
  await assert.rejects(f.grant());pending.resolve({outcome:'CONFIRMATION_GRANTED'});await f.tick(1002);const value=await task;
  assert.equal(value.exitCode,1);assert.ok(!f.calls.includes('durablePermit'));assert.ok(!f.calls.includes('server'));assert.ok(!f.calls.includes('unusedProvider'));
});
test('one failed cleanup still attempts other closures and preserves each independent proof',async()=>{
  const f=fixture({fail:'closeProvisioner'}),value=await runManualPayment(options,f.limits,f.deps);
  assert.equal(value.exitCode,1);assert.equal(value.report.gateway_closed,true);assert.equal(value.report.listener_closed,true);assert.equal(value.report.runtime_closed,true);
});
test('late gateway acquisition is closed once without opening printing or granting',async()=>{
  const f=fixture(),pending=defer();f.deps.createGateway=()=>pending.promise;
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);await f.tick(2701000);pending.resolve(f.gateway);await f.tick(2701001);
  const value=await task;assert.equal(value.exitCode,1);assert.equal(f.calls.filter(x=>x==='closeGateway').length,1);assert.ok(!f.calls.includes('open'));assert.ok(!f.calls.includes('write'));assert.equal(value.report.gateway_closed,false);
});
test('late unsettled acquisition remains owned after original cleanup ceiling with no report upgrade',async()=>{
  const f=fixture(),pending=defer();f.deps.createGateway=()=>pending.promise;
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);await f.tick(2701000);await f.tick(2821000);const value=await task;
  assert.equal(value.report.gateway_closed,false);pending.resolve(f.gateway);await f.tick(2821001);
  assert.equal(f.calls.filter(x=>x==='closeGateway').length,1);assert.equal(value.report.gateway_closed,false);assert.equal(value.report.result,'PENDING');
});
test('stalled gateway cleanup cannot consume the independent cleanup attempts',async()=>{
  const f=fixture(),pending=defer();f.gateway.close=()=>pending.promise;
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);await f.tick(31000);
  assert.ok(f.calls.includes('stopListener'));assert.ok(f.calls.includes('stop'));await f.tick(2821000);const value=await task;
  assert.equal(value.exitCode,1);assert.equal(value.report.gateway_closed,false);pending.resolve(true);await f.tick(2821001);assert.equal(value.report.gateway_closed,false);
});
test('inert imports neither read private credentials nor launch any subprocess or listener',async()=>{
  const {spawnSync}=await import('node:child_process');
  const script=`
    import assert from 'node:assert/strict';
    import {registerHooks,syncBuiltinESMExports} from 'node:module';
    import {pathToFileURL} from 'node:url';
    import cp from 'node:child_process';import fs from 'node:fs';
    import fsp from 'node:fs/promises';import http from 'node:http';import net from 'node:net';
    const trap=()=>{throw Error('private boundary touched');};
    // Node 24's ESM loader uses public readFileSync. Supply only these actual
    // transitive module sources through a loader hook; evaluated code still
    // receives the unconditional filesystem trap, even for a source filename.
    const root=pathToFileURL(process.cwd()+'/');
    const sources=new Set([
      'stripe-manual-payment.mjs','stripe-manual-payment-options.mjs',
      'stripe-manual-payment-schema.mjs','stripe-manual-payment-auth.mjs',
      'stripe-form-diagnostic-provision.mjs','stripe-form-diagnostic-settlement.mjs',
      'stripe-manual-form-options.mjs','stripe-sandbox-acceptance.mjs','stripe-sandbox-remaining-options.mjs',
      'stripe-sandbox-bootstrap.mjs','stripe-sandbox-owned.mjs',
      'stripe-sandbox-cli.mjs','stripe-sandbox-provision.mjs',
      'stripe-sandbox-ownership.mjs','stripe-manual-refund-options.mjs','stripe-manual-refund-remainder-options.mjs',
      'stripe-sandbox-extras.mjs','smoke-stage-5a.mjs',
      'stripe-billing-acceptance-options.mjs','stripe-billing-limits.mjs',
    ].map(name=>new URL('infrastructure/'+name,root).href));
    const readSource=fs.readFileSync;
    registerHooks({load(url,context,nextLoad){
      if(url.startsWith('node:'))return nextLoad(url,context);
      if(!sources.has(url))return trap();
      return {format:'module',source:readSource(new URL(url),'utf8'),shortCircuit:true};
    }});
    cp.execFile=cp.spawn=cp.spawnSync=trap;fs.readFileSync=trap;fsp.readFile=trap;
    http.createServer=net.createServer=trap;globalThis.fetch=trap;syncBuiltinESMExports();
    await import('./infrastructure/stripe-manual-payment.mjs');
    await import('./infrastructure/stripe-manual-payment-auth.mjs');
    // Negative probes never read the filesystem, including an otherwise allowed
    // source path. No private path is exempted by suffix or repository prefix.
    for(const file of ['infrastructure/.env','infrastructure/fixtures/customer-issuer-private.jwks.json',
      'test-results/stripe-sandbox/synthetic/state.json','docs/local-development.md',
      'infrastructure/stripe-manual-payment.mjs','infrastructure/stripe-billing-acceptance-options.mjs',
      'infrastructure/stripe-billing-limits.mjs']){
      assert.throws(()=>fs.readFileSync(new URL(file,root)),/private boundary touched/);
      await assert.rejects(async()=>fsp.readFile(new URL(file,root)),/private boundary touched/);
    }
    await assert.rejects(import('./infrastructure/stripe-billing-acceptance-options.test.mjs'),/private boundary touched/);
  `;
  const result=spawnSync(process.execPath,['--input-type=module','-e',script],{cwd:new URL('..',import.meta.url),env:{PATH:'/usr/bin:/bin',LANG:'C',LC_ALL:'C',NO_COLOR:'1'},encoding:'utf8'});assert.equal(result.status,0,result.stderr);assert.equal(result.stdout,'');
});
function cliInput(){return {args:[],env:{...options.env,PAYMENT_STRIPE_SANDBOX_RUN_ID:runId,PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:expectedSource,PAYMENT_STRIPE_SANDBOX_DAEMON:options.daemon,...Object.fromEntries(Object.entries(options.ports).map(([key,value])=>['PAYMENT_STRIPE_SANDBOX_PORT_'+key.toUpperCase(),String(value)])),PAYMENT_STRIPE_SANDBOX_PORT_MANUAL:String(options.manualPort)}};}
// Break: promoting ordinary provider evidence, omitting old proof/cleanup gates or dropping selection at entry.
test('selected 3DS controller requires exact provider evidence plus every old server and cleanup proof',async()=>{
  const selected={...options,env:{...options.env,PAYMENT_STRIPE_SANDBOX_MANUAL_3DS:'true'}};
  for(const provider of [{captured:true,eventVerified:true},{captured:true,eventVerified:true,threeDSecureAuthenticated:false},{captured:false,eventVerified:true,threeDSecureAuthenticated:true},{captured:true,eventVerified:false,threeDSecureAuthenticated:true},{captured:true,eventVerified:true,threeDSecureAuthenticated:true,extra:true}]){
    const f=fixture();f.cli.inspectManualPayment=async()=>provider;const result=await runManualPayment(selected,f.limits,f.deps);assert.equal(result.report.mode,'MANUAL_3DS');assert.equal(result.report.result,'PENDING');assert.equal(result.report.three_ds_verified,false);
  }
  for(const failed of [undefined,'paymentConfirmed','webhookVerified','journalVerified','callbackVerified','gatewayClosed','listenerClosed','runtimeClosed']){
    const settings=failed?.endsWith('Closed')?{[failed]:false}:{},f=fixture(settings);f.cli.inspectManualPayment=async()=>({captured:true,eventVerified:true,threeDSecureAuthenticated:true});
    if(failed&&!failed.endsWith('Closed'))f.provisioner.verifyPayment=async()=>({paymentConfirmed:true,webhookVerified:true,journalVerified:true,callbackVerified:true,providerEventId:'evt_original',[failed]:false});
    const result=await runManualPayment(selected,f.limits,f.deps);assert.equal(result.report.mode,'MANUAL_3DS');assert.equal(result.report.result,failed?'PENDING':'SUCCEEDED');assert.equal(result.exitCode,failed?1:0);
    assert.equal(f.argumentsSeen.find(x=>x[0]==='gateway')[1][0].threeDSecure,true);assert.equal(f.argumentsSeen.find(x=>x[0]==='cli')[1][0].env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS,'true');assert.equal(f.timers.size,0);
  }
  const ordinary=fixture(),value=await runManualPayment(options,ordinary.limits,ordinary.deps);assert.equal(value.report.result,'SUCCEEDED');assert.equal(Object.hasOwn(value.report,'three_ds_verified'),false);assert.equal(Object.hasOwn(ordinary.argumentsSeen.find(x=>x[0]==='gateway')[1][0],'threeDSecure'),false);
});
test('CLI snapshots exact 3DS opt-in and rejects hostile selection before private dependencies',async()=>{
  const f=fixture();f.cli.inspectManualPayment=async()=>({captured:true,eventVerified:true,threeDSecureAuthenticated:true});f.deps.subscribe=()=>()=>{};
  const input=cliInput();input.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';const value=await runManualPaymentCli(input,f.deps);assert.equal(value.report.mode,'MANUAL_3DS');assert.equal(value.report.three_ds_verified,true);
  let reads=0;for(const selection of ['TRUE',true,null,'accessor']){
    const hostile=cliInput();Object.defineProperty(hostile.env,'PAYMENT_STRIPE_SANDBOX_MANUAL_3DS',selection==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:true,value:selection});
    const denied=()=>{reads++;throw null;};const result=await runManualPaymentCli(hostile,{write:async()=>{},subscribe:()=>()=>{},preflight:denied,createBootstrap:denied,createCli:denied});assert.equal(result.report.phase,'ADMISSION');
  }assert.equal(reads,0);
});
test('signal during stalled final stdout ends its sole output attempt without renewing lifecycle',async()=>{
  const f=fixture();let stop,writes=0,removed=0,settled=false;
  f.deps.subscribe=callback=>{stop=callback;return ()=>removed++;};
  f.deps.write=async wire=>{writes++;if(wire.startsWith('{'))return new Promise(()=>{});};
  const task=runManualPaymentCli(cliInput(),f.deps).then(value=>{settled=true;return value;});await f.tick(1000);assert.equal(writes,2);stop();await f.tick(1001);
  assert.equal(settled,true);const value=await task;assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(writes,2);assert.equal(removed,1);assert.equal(f.timers.size,0);
});
test('final report write failure remains one attempt and cannot reenter provider or permission',async()=>{
  const f=fixture();let writes=0;f.deps.subscribe=()=>()=>{};f.deps.write=async wire=>{writes++;if(wire.startsWith('{'))throw Error('sk_test_canary');};
  const value=await runManualPaymentCli(cliInput(),f.deps);assert.equal(value.exitCode,1);assert.equal(writes,2);assert.equal(f.calls.filter(x=>x==='provider').length,1);assert.equal(f.calls.filter(x=>x==='durablePermit').length,1);assert.equal(f.timers.size,0);
});
test('synchronous report writer failure disposes its timer and signal subscription',async()=>{
  const f=fixture();let writes=0,removed=0;f.deps.subscribe=()=>()=>removed++;f.deps.write=wire=>{writes++;if(wire.startsWith('{'))throw Error('sk_test_canary');};
  const value=await runManualPaymentCli(cliInput(),f.deps);assert.equal(value.exitCode,1);assert.equal(writes,2);assert.equal(removed,1);assert.equal(f.timers.size,0);
});
for(const stage of ['prepare','start'])test('unsettled runtime '+stage+' drains before stop and remains owned after timeout',async()=>{
  const f=fixture(),pending=defer();f.bootstrap[stage]=()=>{f.calls.push(stage);return pending.promise;};
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);await f.tick(2701000);
  assert.ok(!f.calls.includes('stop'),'runtime cannot stop while its creation/start remains in flight');
  pending.resolve(true);await f.tick(2701001);const value=await task;assert.equal(value.exitCode,1);assert.equal(f.calls.filter(x=>x==='stop').length,1);
});
test('a never-settled runtime mutation prevents unsafe stop until its eventual settlement',async()=>{
  const f=fixture(),pending=defer();f.bootstrap.prepare=()=>pending.promise;
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);await f.tick(2701000);await f.tick(2821000);const value=await task;
  assert.equal(value.report.runtime_closed,false);assert.ok(!f.calls.includes('stop'));pending.resolve(true);await f.tick(2821001);
  assert.equal(f.calls.filter(x=>x==='stop').length,1);assert.equal(value.report.runtime_closed,false);
});
test('failed listener stop preserves independent runtime stop proof and exact target order',async()=>{
  const f=fixture({fail:'stopListener'}),value=await runManualPayment(options,f.limits,f.deps);
  assert.equal(value.exitCode,1);assert.equal(value.report.listener_closed,false);assert.equal(value.report.runtime_closed,true);
  assert.ok(f.calls.indexOf('stopListener')<f.calls.indexOf('stop'));assert.equal(f.calls.filter(x=>x==='stop').length,1);
});
// Break: discarding the cached stop's direct true settlement after its first
// slice, accepting non-true/late evidence, or promoting the latched failure.
for(const threeDSecure of [false,true])for(const scenario of [
  {name:'true during final drain',at:32000,value:true,closed:true},
  {name:'true just before ceiling',at:2820999,value:true,closed:true},
  {name:'false',at:32000,value:false,closed:false},
  {name:'non-literal true',at:32000,value:{closed:true},closed:false},
  {name:'rejection',at:32000,reject:true,closed:false},
  {name:'true exactly at ceiling',at:2821000,value:true,closed:false},
  {name:'true after ceiling',at:2821001,value:true,closed:false},
  {name:'never settled',closed:false},
  {name:'true after report',postReport:true,closed:false},
])test('cached runtime stop '+scenario.name+' preserves pending '+(threeDSecure?'3DS':'ordinary')+' report',async()=>{
  const f=fixture({fail:'provision'}),pending=defer(),stops=[];
  f.bootstrap.stop=function(limits){stops.push({target:this,limits,at:f.clock.value});f.calls.push('stop');return pending.promise;};
  const selected=threeDSecure?{...options,env:{...options.env,PAYMENT_STRIPE_SANDBOX_MANUAL_3DS:'true'}}:options;
  let settled=false;
  const task=runManualPayment(selected,f.limits,f.deps).then(value=>{settled=true;return value;});
  await f.tick(1000);
  assert.equal(stops.length,1);assert.equal(stops[0].target,f.bootstrap);assert.equal(stops[0].at,1000);
  assert.deepEqual(stops[0].limits,{deadline:2821000});assert.equal(Object.isFrozen(stops[0].limits),true);
  assert.equal([...f.timers.values()].some(timer=>timer.at===31000),true);
  await f.tick(31000);assert.equal(settled,false);
  assert.equal([...f.timers.values()].some(timer=>timer.at===31000),false);
  if(scenario.at!==undefined){
    f.clock.value=scenario.at;
    if(scenario.reject)pending.reject(Error('synthetic stop failure'));else pending.resolve(scenario.value);
    await f.tick(scenario.at);
  }else await f.tick(2821000);
  const value=await task,wire=JSON.stringify(value.report);
  assert.equal(value.report.runtime_closed,scenario.closed);
  assert.equal(value.report.result,'PENDING');assert.equal(value.report.phase,'PROVISION');assert.equal(value.exitCode,1);
  assert.equal(value.report.mode,threeDSecure?'MANUAL_3DS':'MANUAL_PAYMENT');
  for(const key of ['confirmation_granted','payment_confirmed','provider_confirmed','webhook_verified','journal_verified','callback_verified'])assert.equal(value.report[key],false,key);
  assert.equal(value.report.gateway_closed,true);assert.equal(value.report.listener_closed,true);
  if(threeDSecure)assert.equal(value.report.three_ds_verified,false);
  assert.deepEqual(f.calls,['preflight','bootstrap','prepare','cli','auth','binding','listener','start','verify','provisioner','provision','closeProvisioner','stopListener','stop']);
  assert.equal(stops.length,1);assert.equal(f.financialRetries,0);assert.equal(f.limits.deadline,2701000);assert.equal(f.timers.size,0);
  assert.equal(Object.isFrozen(value),true);assert.equal(Object.isFrozen(value.report),true);
  if(scenario.postReport){pending.resolve(true);await f.tick(2821001);}
  assert.equal(JSON.stringify(value.report),wire);assert.equal(stops.length,1);
});
for(const threeDSecure of [false,true])test('successful financial proof cannot erase a stop slice timeout '+(threeDSecure?'3DS':'ordinary'),async()=>{
  const f=fixture(),pending=defer(),stops=[];
  if(threeDSecure)f.cli.inspectManualPayment=async()=>{f.calls.push('provider');return {captured:true,eventVerified:true,threeDSecureAuthenticated:true};};
  f.bootstrap.stop=function(limits){stops.push({target:this,limits,at:f.clock.value});f.calls.push('stop');return pending.promise;};
  const selected=threeDSecure?{...options,env:{...options.env,PAYMENT_STRIPE_SANDBOX_MANUAL_3DS:'true'}}:options;
  const task=runManualPayment(selected,f.limits,f.deps);await f.tick(1000);await f.tick(31000);
  pending.resolve(true);await f.tick(32000);const value=await task;
  assert.equal(value.report.runtime_closed,true);assert.equal(value.report.gateway_closed,true);assert.equal(value.report.listener_closed,true);
  assert.equal(value.report.result,'PENDING');assert.equal(value.report.phase,'VERIFY');assert.equal(value.exitCode,1);
  for(const key of ['confirmation_granted','payment_confirmed','provider_confirmed','webhook_verified','journal_verified','callback_verified'])assert.equal(value.report[key],true,key);
  if(threeDSecure)assert.equal(value.report.three_ds_verified,true);
  assert.equal(f.calls.filter(name=>name==='durablePermit').length,1);assert.equal(f.calls.filter(name=>name==='provider').length,1);
  assert.equal(stops.length,1);assert.equal(stops[0].target,f.bootstrap);assert.equal(stops[0].at,1000);assert.deepEqual(stops[0].limits,{deadline:2821000});
  assert.equal(f.financialRetries,0);assert.equal(f.timers.size,0);
});
test('aborted authorization child work receives original close grace before runtime stop',async()=>{
  const f=fixture(),pending=defer(),controller=new AbortController();f.limits.signal=controller.signal;f.deps.authorizeCli=()=>pending.promise;
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);controller.abort();await f.tick(1001);assert.ok(!f.calls.includes('stop'));
  pending.reject(Error('synthetic child closed'));await f.tick(1002);const value=await task;
  assert.equal(value.report.listener_closed,false);assert.equal(f.calls.filter(x=>x==='stop').length,1);assert.equal(value.exitCode,1);
});
test('listener lost during durable confirmation await yields zero browser permits and honest consumed report',async()=>{
  const settings={},f=fixture(settings),durable=defer();let entered=0,browserPermits=0;
  f.provisioner.markConfirmation=()=>{entered++;f.calls.push('durablePermit');return durable.promise;};
  f.gateway.wait=async()=>{try{await f.grant();browserPermits++;return {outcome:'CONFIRMATION_GRANTED'};}catch{return {outcome:'FAILED'};}};
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);assert.equal(entered,1);settings.listenerLost=true;durable.resolve(true);await f.tick(1001);const value=await task;
  assert.equal(browserPermits,0);assert.equal(value.report.confirmation_granted,true);assert.equal(value.report.result,'PENDING');assert.equal(value.exitCode,1);
  assert.ok(!f.calls.includes('server'));assert.ok(!f.calls.includes('provider'));assert.ok(!f.calls.includes('unusedServer'));await assert.rejects(f.grant());assert.equal(entered,1);
});
test('listener lost after fresh baseline denies durable dispatch and a Promise cannot impersonate synchronous assertion',async()=>{
  for(const assertion of [()=>{throw Error('synthetic listener lost');},()=>Promise.resolve(true)]){
    const f=fixture();f.cli.assertManualPaymentListener=assertion;const value=await runManualPayment(options,f.limits,f.deps);
    assert.equal(value.report.result,'PENDING');assert.equal(value.report.confirmation_granted,false);assert.ok(!f.calls.includes('durablePermit'));assert.ok(!f.calls.includes('unusedProvider'));
  }
});
function actualAuth(f,settings={}){
  const root=new URL('..',import.meta.url).pathname.replace(/\/$/,''),directory=root+'/test-results/stripe-sandbox/'+runId+'/cli';let dispatches=0;
  const descriptor=complete=>Object.freeze({file:'/usr/bin/sandbox-exec',args:Object.freeze(['-p','(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))','/opt/homebrew/bin/stripe','--config',directory+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error','login',...(complete?['--complete-device']:[])]),options:Object.freeze({cwd:root,env:Object.freeze({PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:directory+'/home',XDG_CONFIG_HOME:directory+'/config',NO_COLOR:'1'}),shell:false,stdio:Object.freeze(['pipe','pipe','pipe']),timeout:complete?600000:180000,maxBuffer:65536})});
  f.cli.loginInvocation=async()=>descriptor(false);f.cli.completeLoginInvocation=async()=>descriptor(true);
  f.deps.authorizeCli=(input,limits,handoff)=>authorizeManualPaymentCli(input,limits,handoff,{now:f.deps.now,setTimeout:f.deps.setTimeout,clearTimeout:f.deps.clearTimeout,execFile:(file,args,options,callback)=>{
    const child=new EventEmitter(),complete=++dispatches===2;queueMicrotask(()=>{if(complete&&settings.onCompletion){settings.onCompletion({child,callback});return;}callback(null,complete?(settings.completion??'Done.\n'):JSON.stringify({browser_url:'https://dashboard.stripe.com/stripecli/confirm_auth?t=synthetic',verification_code:'alpha-bravo',next_step:'stripe login --complete-device'}),'');child.emit('close',complete?(settings.closeCode??0):0,null);});return child;
  }});return ()=>dispatches;
}
test('real auth through controller and entry emits one safe stderr diagnostic after cleanup before final report',async()=>{
  const f=fixture(),dispatches=actualAuth(f,{completion:'password=synthetic-private'}),out=[],err=[],order=[];let code;
  const stream=(lines,name)=>Object.assign(new EventEmitter(),{write(text,done){lines.push(text);order.push(name);done();return true;}});
  f.deps.subscribe=()=>()=>{};const stop=f.bootstrap.stop;f.bootstrap.stop=async limits=>{const value=await stop(limits);order.push('stop');return value;};
  await module.runManualPaymentEntry(cliInput(),{stdout:stream(out,'stdout'),stderr:stream(err,'stderr'),exit:value=>{code=value;}},f.deps);
  assert.equal(code,1);assert.equal(dispatches(),2);assert.equal(err.length,1);assert.deepEqual(JSON.parse(err[0]),{tag:'stripe_manual_payment_auth_diagnostic',stage:'COMPLETE',reason:'OUTPUT_UNSAFE'});
  assert.ok(order.indexOf('stop')<order.indexOf('stderr'));assert.equal(order.at(-1),'stdout');assert.equal(out.filter(line=>line.startsWith('{')).length,1);assert.ok(!out.join('').includes('synthetic-private'));assert.ok(!f.calls.includes('binding'));assert.ok(!f.calls.includes('provision'));assert.equal(JSON.parse(out.at(-1)).listener_closed,false);
});
for(const settings of [{},{closeCode:1}])test('actual auth close determines binding and diagnostic independently '+JSON.stringify(settings),async()=>{
  const f=fixture(),err=[];actualAuth(f,settings);f.deps.writeAuthDiagnostic=async line=>err.push(line);const value=await runManualPayment(options,f.limits,f.deps);
  assert.equal(value.exitCode,settings.closeCode?1:0);assert.equal(err.length,settings.closeCode?1:0);assert.equal(f.calls.includes('binding'),!settings.closeCode);if(settings.closeCode)assert.equal(JSON.parse(err[0]).reason,'PROCESS_FAILED');
});
test('later binding failure emits no fabricated authorization diagnostic',async()=>{
  const f=fixture({fail:'binding'}),err=[];actualAuth(f);f.deps.writeAuthDiagnostic=async line=>err.push(line);const value=await runManualPayment(options,f.limits,f.deps);assert.equal(value.exitCode,1);assert.equal(err.length,0);
});
test('hostile authorization rejection emits finite fallback without property reads',async()=>{
  const f=fixture(),err=[];let reads=0;f.deps.authorizeCli=async()=>{throw new Proxy({},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;}});};f.deps.writeAuthDiagnostic=async line=>err.push(line);
  const value=await runManualPayment(options,f.limits,f.deps);assert.equal(value.exitCode,1);assert.equal(reads,0);assert.deepEqual(JSON.parse(err[0]),{tag:'stripe_manual_payment_auth_diagnostic',stage:'UNKNOWN',reason:'INTERNAL'});
});
for(const mode of ['reject','stall','ceiling'])test('diagnostic writer '+mode+' retains proof one final report and original bounds',async()=>{
  const f=fixture(),writes=[],diagnostics=[];actualAuth(f,{completion:'password=synthetic-private'});f.deps.subscribe=()=>()=>{};f.deps.write=async line=>writes.push(line);
  const stop=f.bootstrap.stop;f.bootstrap.stop=async limits=>{const value=await stop(limits);if(mode==='ceiling')f.clock.value=2820500;return value;};
  f.deps.writeAuthDiagnostic=line=>{diagnostics.push(line);if(mode==='reject')throw null;return new Promise(()=>{});};
  let settled=false;const task=runManualPaymentCli(cliInput(),f.deps).then(value=>{settled=true;return value;});await f.tick(1000);
  assert.equal(diagnostics.length,1);if(mode!=='reject'){assert.equal(settled,false);await f.tick(mode==='ceiling'?2821000:2000);}
  // Even the ceiling case directly observes stop=true at2820500, before the
  // original2821000 ceiling; the missed slice/diagnostic timeout stays failed.
  const value=await task;assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(value.report.listener_closed,false);assert.equal(value.report.runtime_closed,true);assert.equal(writes.filter(line=>line.startsWith('{')).length,1);assert.ok(!f.calls.includes('binding'));assert.ok(!f.calls.includes('provision'));assert.equal(f.timers.size,0);
});
for(const mode of ['reject','stall'])test('entry stderr '+mode+' cannot prevent sole final stdout attempt',async()=>{
  const f=fixture(),out=[];actualAuth(f,{completion:'password=synthetic-private'});f.deps.subscribe=()=>()=>{};let writes=0,code;
  const stdout=Object.assign(new EventEmitter(),{write(text,done){out.push(text);done();}}),stderr=Object.assign(new EventEmitter(),{write(text,done){writes++;if(mode==='reject')done(Error('synthetic write failure'));}});
  const task=module.runManualPaymentEntry(cliInput(),{stdout,stderr,exit:value=>{code=value;}},f.deps);await f.tick(1000);if(mode==='stall')await f.tick(2000);await task;
  assert.equal(code,1);assert.equal(writes,1);assert.equal(out.filter(line=>line.startsWith('{')).length,1);assert.equal(JSON.parse(out.at(-1)).payment_confirmed,false);assert.ok(!f.calls.includes('binding'));assert.equal(f.timers.size,0);
});
for(const window of ['one-second','cleanup'])test('diagnostic deferred dispatch rechecks the original '+window+' cutoff when the clock crosses after scheduling',async()=>{
  const f=fixture(),diagnostics=[],writes=[];actualAuth(f,{completion:'password=synthetic-private'});f.deps.subscribe=()=>()=>{};f.deps.write=async line=>writes.push(line);f.deps.writeAuthDiagnostic=async line=>diagnostics.push({at:f.clock.value,line});
  if(window==='cleanup'){const stop=f.bootstrap.stop;f.bootstrap.stop=async limits=>{const value=await stop(limits);f.clock.value=2820500;return value;};}
  const schedule=f.deps.setTimeout;let crossed=false;
  f.deps.setTimeout=(fn,delay)=>{if(!crossed&&delay===(window==='cleanup'?500:1000)&&f.calls.includes('stop')){crossed=true;f.clock.value=window==='cleanup'?2821000:2000;}return schedule(fn,delay);};
  const value=await runManualPaymentCli(cliInput(),f.deps);
  assert.equal(crossed,true);assert.equal(diagnostics.length,0);assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(writes.filter(line=>line.startsWith('{')).length,1);assert.ok(!f.calls.includes('binding'));assert.ok(!f.calls.includes('provision'));assert.equal(f.timers.size,0);
});
test('real authorization retains its first completion failure arriving during original close grace after controller timeout',async()=>{
  const f=fixture(),diagnostics=[];f.limits.deadline=401000;let completion;let reads=0;
  const dispatches=actualAuth(f,{onCompletion:value=>{completion=value;}});f.deps.writeAuthDiagnostic=async line=>diagnostics.push(line);
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);assert.equal(dispatches(),2);
  await f.tick(400999);completion.callback(new Proxy({},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;}}),'','');
  await f.tick(401000);assert.equal(diagnostics.length,0);assert.ok(!f.calls.includes('stop'));
  await f.tick(410999);const value=await task;
  assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(value.report.listener_closed,false);assert.equal(value.report.runtime_closed,true);assert.equal(reads,0);
  assert.equal(diagnostics.length,1);assert.deepEqual(JSON.parse(diagnostics[0]),{tag:'stripe_manual_payment_auth_diagnostic',stage:'COMPLETE',reason:'PROCESS_FAILED'});assert.ok(!f.calls.includes('binding'));assert.ok(!f.calls.includes('provision'));
  completion.callback(null,'Done.\n','');completion.child.emit('close',0,null);await f.tick(411000);assert.equal(diagnostics.length,1);assert.equal(value.report.result,'PENDING');assert.equal(dispatches(),2);assert.equal(f.timers.size,0);
});
test('real authorization late success cannot revive expired controller authority or emit after final report',async()=>{
  const f=fixture(),diagnostics=[];f.limits.deadline=401000;let completion;
  const dispatches=actualAuth(f,{onCompletion:value=>{completion=value;}});f.deps.writeAuthDiagnostic=async line=>diagnostics.push(line);
  const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);await f.tick(401000);assert.equal(diagnostics.length,0);
  completion.callback(null,'Done.\n','');completion.child.emit('close',0,null);await f.tick(401001);const value=await task;
  assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(value.report.listener_closed,false);assert.equal(diagnostics.length,1);assert.deepEqual(JSON.parse(diagnostics[0]),{tag:'stripe_manual_payment_auth_diagnostic',stage:'COMPLETE',reason:'ABORTED'});assert.ok(!f.calls.includes('binding'));assert.ok(!f.calls.includes('provision'));
  await f.tick(521000);assert.equal(diagnostics.length,1);assert.equal(dispatches(),2);assert.equal(f.timers.size,0);
});

// Break: discarding verification failures or confusing a cleanup-only failure with verification failure.
async function actualVerifyAdmissionFailure(f){
 const safe={...options,status:'LIVE',mode:'MANUAL_PAYMENT',webhookReady:true,project:'stripe-'+runId,siteKey:'s5a-'+runId.replaceAll('-',''),endpoints:Object.fromEntries(['backend','demo','receiver'].map(key=>[key,'http://127.0.0.1:'+options.ports[key]])),containers:Object.fromEntries(['backend','demo-site','callback-receiver'].map((key,i)=>[key,{id:String(i+1).repeat(64)}]))};
 const p=await createManualPaymentProvisioner({env:options.env,browserOrigin:'http://127.0.0.1:18107',bootstrap:{resources:()=>safe,verify:async()=>true,withAdminCredentials:async()=>{assert.fail('verification admission must not acquire credentials');}},verifyProviderBinding:async()=>true},{now:f.deps.now,setTimeout:f.deps.setTimeout,clearTimeout:f.deps.clearTimeout,fetch:()=>assert.fail('verification admission must not fetch')});
 try{await p.verifyPayment(f.limits);}catch(error){return error;}assert.fail('unprepared verification must reject');
}
for(const boundary of ['branded-server','server-reply','provider'])for(const timing of ['during-drain','after-finalization'])test(`verify diagnostic retains ${boundary} ${timing} without reviving financial work`,async()=>{
 const f=fixture(),pending=defer(),lines=[];f.limits.deadline=401000;const branded=await actualVerifyAdmissionFailure(f);
 if(boundary==='provider')f.cli.inspectManualPayment=()=>pending.promise;else f.provisioner.verifyPayment=()=>pending.promise;
 f.deps.writeVerifyDiagnostic=async line=>lines.push(line);const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);await f.tick(401000);assert.deepEqual(lines,[]);
 if(timing==='after-finalization')await f.tick(521000);
 const settle=()=>boundary==='server-reply'?pending.resolve({}):pending.reject(branded);
 if(timing==='during-drain'){settle();await f.tick(401001);}
 const value=await task,snapshot=JSON.stringify(value.report);
 if(timing==='during-drain')assert.deepEqual(lines,['{"tag":"stripe_manual_payment_verify_diagnostic","stage":"'+(boundary==='branded-server'?'ADMISSION':boundary==='server-reply'?'SERVER_REPLY':'PROVIDER')+'","reason":"UNCONFIRMED"}\n']);
 else {assert.deepEqual(lines,[]);settle();await f.tick(521001);assert.deepEqual(lines,[]);}
 assert.equal(JSON.stringify(value.report),snapshot);assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(value.report.phase,'VERIFY');assert.equal(value.report.provider_confirmed,false);assert.equal(f.calls.filter(x=>x==='durablePermit').length,1);assert.equal(f.financialRetries,0);assert.equal(f.timers.size,0);
});
for(const boundary of ['server','provider'])test('verify diagnostic remains UNKNOWN when timed-out '+boundary+' succeeds only during drain',async()=>{
 const f=fixture(),pending=defer(),lines=[];f.limits.deadline=401000;
 if(boundary==='server')f.provisioner.verifyPayment=()=>pending.promise;else f.cli.inspectManualPayment=()=>pending.promise;
 f.deps.writeVerifyDiagnostic=async line=>lines.push(line);const task=runManualPayment(options,f.limits,f.deps);await f.tick(1000);await f.tick(401000);assert.deepEqual(lines,[]);
 pending.resolve(boundary==='server'?{paymentConfirmed:true,webhookVerified:true,journalVerified:true,callbackVerified:true,providerEventId:'evt_original'}:{captured:true,eventVerified:true});await f.tick(401001);
 const value=await task;assert.deepEqual(lines,['{"tag":"stripe_manual_payment_verify_diagnostic","stage":"UNKNOWN","reason":"UNCONFIRMED"}\n']);assert.equal(value.report.result,'PENDING');assert.equal(value.report.payment_confirmed,boundary==='provider');assert.equal(value.report.provider_confirmed,false);assert.equal(f.calls.filter(x=>x==='durablePermit').length,1);assert.equal(f.financialRetries,0);assert.equal(f.timers.size,0);
});
for(const cleanupOnly of [false,true])test('selected 3DS accepted full verification stays silent even for cleanup-only failure '+cleanupOnly,async()=>{
 const f=fixture({runtimeClosed:!cleanupOnly}),lines=[];f.cli.inspectManualPayment=async()=>({captured:true,eventVerified:true,threeDSecureAuthenticated:true});f.deps.writeVerifyDiagnostic=async line=>lines.push(line);
 const value=await runManualPayment({...options,env:{...options.env,PAYMENT_STRIPE_SANDBOX_MANUAL_3DS:'true'}},f.limits,f.deps);
 assert.deepEqual(lines,[]);assert.equal(value.report.three_ds_verified,true);assert.equal(value.report.provider_confirmed,true);assert.equal(value.report.result,cleanupOnly?'PENDING':'SUCCEEDED');assert.equal(value.exitCode,cleanupOnly?1:0);assert.equal(f.timers.size,0);
});
for(const threeDSecure of [false,true])for(const boundary of ['server-reply','server-throw','provider-reply','provider-throw'])test(`verify diagnostic reports ${boundary} for ${threeDSecure?'3DS':'ordinary'} entry after cleanup before sole final report`,async()=>{
 const f=fixture(),out=[],err=[],order=[],input=cliInput();if(threeDSecure)input.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';let reads=0,code;
 const hostile=new Proxy({},{get(){reads++;throw Error('private');},getPrototypeOf(){reads++;throw null;},ownKeys(){reads++;throw null;}});
 if(boundary==='server-reply')f.provisioner.verifyPayment=async()=>({paymentConfirmed:false,webhookVerified:true,journalVerified:true,callbackVerified:true,providerEventId:'evt_private'});
 if(boundary==='server-throw')f.provisioner.verifyPayment=async()=>{throw hostile;};
 if(boundary==='provider-reply')f.cli.inspectManualPayment=async()=>({captured:true,eventVerified:true,...(threeDSecure?{threeDSecureAuthenticated:false}:{private:'canary'})});
 if(boundary==='provider-throw')f.cli.inspectManualPayment=async()=>{throw hostile;};
 f.deps.subscribe=()=>()=>{};const stop=f.bootstrap.stop;f.bootstrap.stop=async limits=>{const ok=await stop(limits);order.push('cleanup');return ok;};
 const stream=(lines,name)=>Object.assign(new EventEmitter(),{write(text,done){lines.push(text);order.push(name);done();}});
 await module.runManualPaymentEntry(input,{stdout:stream(out,'stdout'),stderr:stream(err,'stderr'),exit:value=>{code=value;}},f.deps);
 const stage=boundary==='server-reply'?'SERVER_REPLY':boundary==='server-throw'?'UNKNOWN':'PROVIDER';
 assert.deepEqual(err,['{"tag":"stripe_manual_payment_verify_diagnostic","stage":"'+stage+'","reason":"UNCONFIRMED"}\n']);assert.ok(Buffer.byteLength(err[0])<=256);assert.equal(reads,0);assert.equal(code,1);
 assert.ok(order.indexOf('cleanup')<order.indexOf('stderr'));assert.equal(order.at(-1),'stdout');assert.equal(out.filter(line=>line.startsWith('{')).length,1);
 const report=JSON.parse(out.at(-1));assert.equal(report.phase,'VERIFY');assert.equal(report.result,'PENDING');assert.equal(report.confirmation_granted,true);assert.equal(report.payment_confirmed,boundary.startsWith('provider'));assert.equal(report.provider_confirmed,false);assert.equal(f.calls.filter(x=>x==='durablePermit').length,1);assert.equal(f.financialRetries,0);assert.equal(f.timers.size,0);
});
for(const settings of [{},{outcome:'NOT_STARTED'},{fail:'auth'},{fail:'bindPayment'},{runtimeClosed:false},{listenerClosed:false},{gatewayClosed:false}])test('verify diagnostic stays silent for success unused earlier failure or cleanup-only '+JSON.stringify(settings),async()=>{
 const f=fixture(settings),lines=[];f.deps.writeVerifyDiagnostic=async line=>lines.push(line);await runManualPayment(options,f.limits,f.deps);assert.deepEqual(lines,[]);assert.equal(f.financialRetries,0);assert.equal(f.timers.size,0);
});
for(const mode of ['reject','stall','ceiling','expired'])test('verify diagnostic '+mode+' cannot suppress sole final stdout or extend cleanup',async()=>{
 const f=fixture({fail:'server'}),lines=[],out=[];f.deps.subscribe=()=>()=>{};f.deps.write=async line=>out.push(line);
 const stop=f.bootstrap.stop;f.bootstrap.stop=async limits=>{const ok=await stop(limits);if(mode==='ceiling')f.clock.value=2820500;if(mode==='expired')f.clock.value=2821000;return ok;};
 f.deps.writeVerifyDiagnostic=line=>{assert.ok(f.calls.includes('stop'));lines.push(line);if(mode==='reject')throw Error('private');return new Promise(()=>{});};
 let settled=false;const task=runManualPaymentCli(cliInput(),f.deps).then(value=>{settled=true;return value;});await f.tick(1000);assert.equal(lines.length,mode==='expired'?0:1);
 if(mode==='stall'||mode==='ceiling'){assert.equal(settled,false);await f.tick(mode==='ceiling'?2821000:2000);}
 const value=await task;assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(out.filter(line=>line.startsWith('{')).length,1);assert.equal(f.calls.filter(x=>x==='durablePermit').length,1);assert.equal(f.financialRetries,0);assert.equal(f.timers.size,0);
});
for(const window of ['one-second','cleanup'])test('verify diagnostic deferred dispatch rechecks '+window+' cutoff',async()=>{
 const f=fixture({fail:'server'}),lines=[];f.deps.writeVerifyDiagnostic=async line=>lines.push(line);
 if(window==='cleanup'){const stop=f.bootstrap.stop;f.bootstrap.stop=async limits=>{const ok=await stop(limits);f.clock.value=2820500;return ok;};}
 const schedule=f.deps.setTimeout;let crossed=false;f.deps.setTimeout=(fn,delay)=>{if(!crossed&&delay===(window==='cleanup'?500:1000)&&f.calls.includes('stop')){crossed=true;f.clock.value=window==='cleanup'?2821000:2000;}return schedule(fn,delay);};
 const value=await runManualPayment(options,f.limits,f.deps);assert.equal(crossed,true);assert.equal(lines.length,0);assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(f.timers.size,0);
});
for(const mode of ['reject','stall'])test('verify entry stderr '+mode+' still attempts sole final stdout',async()=>{
 const f=fixture({fail:'server'}),out=[];f.deps.subscribe=()=>()=>{};let count=0,code;
 const stdout=Object.assign(new EventEmitter(),{write(text,done){out.push(text);done();}}),stderr=Object.assign(new EventEmitter(),{write(text,done){count++;if(mode==='reject')done(Error('private'));}});
 const task=module.runManualPaymentEntry(cliInput(),{stdout,stderr,exit:value=>{code=value;}},f.deps);await f.tick(1000);if(mode==='stall')await f.tick(2000);await task;
 assert.equal(code,1);assert.equal(count,1);assert.equal(out.filter(line=>line.startsWith('{')).length,1);assert.equal(JSON.parse(out.at(-1)).result,'PENDING');assert.equal(f.timers.size,0);
});

// Break: classifying unrelated failures, emitting before cleanup, or changing the report schema.
for(const threeDSecure of [false,true])test('actual provision factory failure reaches entry stderr after cleanup before unchanged '+(threeDSecure?'3DS':'ordinary')+' report',async()=>{
 const f=fixture(),out=[],err=[],order=[];let code;const input=cliInput();if(threeDSecure)input.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';
 f.deps.subscribe=()=>()=>{};f.deps.createProvisioner=value=>createManualPaymentProvisioner({...value,browserOrigin:'https://password-synthetic-private.invalid'});
 const stop=f.bootstrap.stop;f.bootstrap.stop=async limits=>{const value=await stop(limits);order.push('stop');return value;};
 const stream=(lines,name)=>Object.assign(new EventEmitter(),{write(text,done){lines.push(text);order.push(name);done();return true;}});
 await module.runManualPaymentEntry(input,{stdout:stream(out,'stdout'),stderr:stream(err,'stderr'),exit:value=>{code=value;}},f.deps);
 assert.equal(code,1);assert.deepEqual(err,['{"tag":"stripe_manual_payment_provision_diagnostic","stage":"ADMISSION"}\n']);assert.ok(order.indexOf('stop')<order.indexOf('stderr'));assert.equal(order.at(-1),'stdout');
 assert.equal(out.length,1);const report=JSON.parse(out[0]);assert.equal(report.result,'PENDING');assert.equal(report.phase,'PROVISION');assert.equal(report.gateway_closed,true);assert.equal(report.listener_closed,true);assert.equal(report.runtime_closed,true);assert.equal(report.mode,threeDSecure?'MANUAL_3DS':'MANUAL_PAYMENT');
 assert.deepEqual(Object.keys(report).sort(),['version','mode','result','phase','confirmation_granted','payment_confirmed','provider_confirmed','webhook_verified','journal_verified','callback_verified','gateway_closed','listener_closed','runtime_closed','browser_closure','provenance',...(threeDSecure?['three_ds_verified']:[])].sort());assert.equal(f.financialRetries,0);assert.ok(!f.calls.includes('bindPayment'));
});
test('hostile prepare failure emits UNKNOWN only after every independently owned cleanup',async()=>{
 const f=fixture(),diagnostics=[];let reads=0;f.provisioner.prepare=async()=>{throw new Proxy({},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;}});};
 f.deps.writeProvisionDiagnostic=async line=>{assert.ok(f.calls.includes('closeProvisioner'));assert.ok(f.calls.includes('stopListener'));assert.ok(f.calls.includes('stop'));diagnostics.push(line);};
 const value=await runManualPayment(options,f.limits,f.deps);assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(reads,0);assert.deepEqual(diagnostics,['{"tag":"stripe_manual_payment_provision_diagnostic","stage":"UNKNOWN"}\n']);assert.equal(f.financialRetries,0);
});
for(const fail of [undefined,'auth','binding','bindPayment','server','provider'])test('no provision diagnostic outside failed construction or prepare '+String(fail),async()=>{
 const f=fixture({fail}),diagnostics=[];f.deps.writeProvisionDiagnostic=async line=>diagnostics.push(line);await runManualPayment(options,f.limits,f.deps);assert.equal(diagnostics.length,0);
});
for(const mode of ['reject','stall','ceiling','expired'])test('provision diagnostic '+mode+' keeps sole output within original cleanup authority',async()=>{
 const f=fixture({fail:'provision'}),writes=[],diagnostics=[];f.deps.subscribe=()=>()=>{};f.deps.write=async line=>writes.push(line);
 const stop=f.bootstrap.stop;f.bootstrap.stop=async limits=>{const value=await stop(limits);if(mode==='ceiling')f.clock.value=2820500;if(mode==='expired')f.clock.value=2821000;return value;};
 f.deps.writeProvisionDiagnostic=line=>{assert.ok(f.calls.includes('stop'));diagnostics.push(line);if(mode==='reject')throw Error('password=synthetic-private');return new Promise(()=>{});};
 let settled=false;const task=runManualPaymentCli(cliInput(),f.deps).then(value=>{settled=true;return value;});await f.tick(1000);
 assert.equal(diagnostics.length,mode==='expired'?0:1);if(mode==='stall'||mode==='ceiling'){assert.equal(settled,false);await f.tick(mode==='ceiling'?2821000:2000);}
 const value=await task;assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(writes.length,1);assert.ok(!f.calls.includes('bindPayment'));assert.equal(f.financialRetries,0);assert.equal(f.timers.size,0);
});
for(const window of ['one-second','cleanup'])test('provision diagnostic deferred dispatch rechecks '+window+' cutoff',async()=>{
 const f=fixture({fail:'provision'}),diagnostics=[];f.deps.writeProvisionDiagnostic=async line=>diagnostics.push(line);
 if(window==='cleanup'){const stop=f.bootstrap.stop;f.bootstrap.stop=async limits=>{const value=await stop(limits);f.clock.value=2820500;return value;};}
 const schedule=f.deps.setTimeout;let crossed=false;f.deps.setTimeout=(fn,delay)=>{if(!crossed&&delay===(window==='cleanup'?500:1000)&&f.calls.includes('stop')){crossed=true;f.clock.value=window==='cleanup'?2821000:2000;}return schedule(fn,delay);};
 const value=await runManualPayment(options,f.limits,f.deps);assert.equal(crossed,true);assert.equal(diagnostics.length,0);assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(f.timers.size,0);
});
for(const mode of ['reject','stall'])test('provision entry stderr '+mode+' cannot prevent sole final stdout',async()=>{
 const f=fixture({fail:'provision'}),out=[];f.deps.subscribe=()=>()=>{};let writes=0,code;
 const stdout=Object.assign(new EventEmitter(),{write(text,done){out.push(text);done();}}),stderr=Object.assign(new EventEmitter(),{write(text,done){writes++;if(mode==='reject')done(Error('synthetic write failure'));}});
 const task=module.runManualPaymentEntry(cliInput(),{stdout,stderr,exit:value=>{code=value;}},f.deps);await f.tick(1000);if(mode==='stall')await f.tick(2000);await task;
 assert.equal(code,1);assert.equal(writes,1);assert.equal(out.length,1);assert.equal(JSON.parse(out[0]).result,'PENDING');assert.equal(f.timers.size,0);
});

// Break: discarding the initial refusal, accepting a non-READY reply, or extending its authority.
function initialPendingReport(threeDSecure=false){return {version:1,mode:threeDSecure?'MANUAL_3DS':'MANUAL_PAYMENT',result:'PENDING',phase:'PREPARE',confirmation_granted:false,payment_confirmed:false,provider_confirmed:false,webhook_verified:false,journal_verified:false,callback_verified:false,gateway_closed:true,listener_closed:true,runtime_closed:true,browser_closure:'NOT_OWNED',provenance:'CONTROLLER_REPORTED',...(threeDSecure?{three_ds_verified:false}:{})};}
for(const [phase,code]of [['OPT_IN','OPT_IN_REQUIRED'],['INPUTS','CONFIGURATION'],['SOURCE','SOURCE_MISMATCH'],['DAEMON','DAEMON_UNCONFIRMED'],['PORTS','PORT_UNAVAILABLE']])
 for(const threeDSecure of [false,true])test('initial preflight refusal retains safe '+phase+' code for '+(threeDSecure?'3DS':'ordinary')+' without later capabilities',async()=>{
  const f=fixture(),diagnostics=[];f.deps.preflight=async()=>{f.calls.push('preflight');return {status:'PENDING',phase,code};};f.deps.writePreflightDiagnostic=async line=>diagnostics.push(line);
  const selected={...options,env:{...options.env,...(threeDSecure?{PAYMENT_STRIPE_SANDBOX_MANUAL_3DS:'true'}:{})}};
  const value=await runManualPayment(selected,f.limits,f.deps);
  assert.equal(value.exitCode,1);assert.deepEqual(value.report,initialPendingReport(threeDSecure));assert.deepEqual(f.calls,['preflight']);assert.equal(f.financialRetries,0);assert.equal(f.timers.size,0);
  assert.equal(diagnostics.length,1);assert.deepEqual(JSON.parse(diagnostics[0]),{tag:'stripe_manual_payment_preflight_diagnostic',phase,code});assert.ok(Buffer.byteLength(diagnostics[0])<=256);assert.ok(diagnostics[0].endsWith('\n'));
 });
test('initial preflight malformed replies emit only UNKNOWN without evaluating accessors or copying private fields',async()=>{
 let reads=0;const known={status:'PENDING',phase:'SOURCE',code:'SOURCE_MISMATCH'};
 const accessor=Object.defineProperty({status:'PENDING',code:'SOURCE_MISMATCH'},'phase',{enumerable:true,get(){reads++;throw Error('synthetic-private');}});
 const hidden=Object.defineProperty({...known},'code',{enumerable:false,value:'SOURCE_MISMATCH'});
 for(const reply of [null,undefined,'synthetic-private',{...known,status:'READY'},{...known,code:'PORT_UNAVAILABLE'},{...known,phase:'synthetic-private'},{...known,code:'synthetic-private'},{...known,private:'synthetic-private'},{...known,[Symbol('synthetic-private')]:true},Object.create(known),accessor,hidden,new Proxy({},{ownKeys(){throw Error('synthetic-private');}})]){
  const f=fixture(),diagnostics=[];f.deps.preflight=async()=>{f.calls.push('preflight');return reply;};f.deps.writePreflightDiagnostic=async line=>diagnostics.push(line);
  const value=await runManualPayment(options,f.limits,f.deps);assert.deepEqual(value.report,initialPendingReport());assert.equal(value.exitCode,1);assert.deepEqual(f.calls,['preflight']);assert.equal(f.timers.size,0);
  assert.deepEqual(diagnostics,['{"tag":"stripe_manual_payment_preflight_diagnostic","phase":"UNKNOWN","code":"UNKNOWN"}\n']);
 }
 assert.equal(reads,0);
});
test('initial preflight hostile rejection is not inspected and cannot suppress the safe diagnostic or final report',async()=>{
 const f=fixture(),diagnostics=[],writes=[];let reads=0;f.deps.subscribe=()=>()=>{};f.deps.write=async line=>writes.push(line);
 f.deps.preflight=async()=>{f.calls.push('preflight');throw new Proxy({},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;},ownKeys(){reads++;throw null;}});};f.deps.writePreflightDiagnostic=async line=>diagnostics.push(line);
 const value=await runManualPaymentCli(cliInput(),f.deps);assert.equal(reads,0);assert.equal(value.exitCode,1);assert.deepEqual(value.report,initialPendingReport());assert.deepEqual(f.calls,['preflight']);assert.equal(writes.length,1);assert.deepEqual(JSON.parse(writes[0]),initialPendingReport());assert.equal(f.timers.size,0);
 assert.deepEqual(diagnostics,['{"tag":"stripe_manual_payment_preflight_diagnostic","phase":"UNKNOWN","code":"UNKNOWN"}\n']);
});
for(const phase of ['SOURCE','DAEMON','PORTS'])for(const threeDSecure of [false,true])test('real initial admission '+phase+' refusal reaches entry stderr before sole unchanged '+(threeDSecure?'3DS':'ordinary')+' report',async()=>{
 const f=fixture(),out=[],err=[],order=[],input=cliInput();let code;if(threeDSecure)input.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';f.deps.subscribe=()=>()=>{};
 f.deps.preflight=async selected=>{f.calls.push('preflight');return preflightOwnedManualPayment(selected,{
  execute:async(file,args)=>({stdout:file.endsWith('/git')?(args.includes('rev-parse')?(phase==='SOURCE'?'b'.repeat(40):expectedSource)+'\n':''):phase==='DAEMON'?'"windows"\n':'"linux"\n',stderr:''}),
  lstat:async path=>{if(path==='/private/var/empty/config.json')throw Object.assign(Error('synthetic absent'),{code:'ENOENT'});return path==='/private/var/empty'?{isDirectory:()=>true,isSymbolicLink:()=>false,uid:0,mode:0o40755}:{isSocket:()=>true,isSymbolicLink:()=>false,uid:process.getuid()};},
  realpath:async path=>path,portAvailable:async()=>phase!=='PORTS'
 });};
 const stream=(lines,name)=>Object.assign(new EventEmitter(),{write(text,done){lines.push(text);order.push(name);done();return true;}});
 await module.runManualPaymentEntry(input,{stdout:stream(out,'stdout'),stderr:stream(err,'stderr'),exit:value=>{code=value;}},f.deps);
 assert.equal(code,1);assert.deepEqual(f.calls,['preflight']);assert.deepEqual(order,['stderr','stdout']);assert.equal(out.length,1);assert.deepEqual(JSON.parse(out[0]),initialPendingReport(threeDSecure));
 const expectedCode={SOURCE:'SOURCE_MISMATCH',DAEMON:'DAEMON_UNCONFIRMED',PORTS:'PORT_UNAVAILABLE'}[phase];assert.equal(err.length,1);assert.deepEqual(JSON.parse(err[0]),{tag:'stripe_manual_payment_preflight_diagnostic',phase,code:expectedCode});assert.equal(f.timers.size,0);
});
for(const fail of [undefined,'prepare','auth','binding','provision','server'])test('no initial preflight diagnostic after READY even for later '+String(fail),async()=>{
 const f=fixture({fail}),diagnostics=[];f.deps.writePreflightDiagnostic=async line=>diagnostics.push(line);await runManualPayment(options,f.limits,f.deps);assert.deepEqual(diagnostics,[]);assert.equal(f.timers.size,0);
});
for(const mode of ['reject','stall','ceiling','expired'])test('initial preflight diagnostic '+mode+' cannot suppress sole final stdout or renew original bounds',async()=>{
 const f=fixture(),writes=[],diagnostics=[];f.deps.subscribe=()=>()=>{};f.deps.write=async line=>writes.push(line);
 f.deps.preflight=async()=>{f.calls.push('preflight');if(mode==='ceiling')f.clock.value=2820500;if(mode==='expired')f.clock.value=2821000;return {status:'PENDING',phase:'PORTS',code:'PORT_UNAVAILABLE'};};
 f.deps.writePreflightDiagnostic=line=>{diagnostics.push(line);if(mode==='reject')throw Error('synthetic-private');return new Promise(()=>{});};
 let settled=false;const task=runManualPaymentCli(cliInput(),f.deps).then(value=>{settled=true;return value;});await f.tick(1000);
 assert.equal(diagnostics.length,mode==='expired'?0:1);if(mode==='stall'||mode==='ceiling'){assert.equal(settled,false);await f.tick(mode==='ceiling'?2821000:2000);}
 const value=await task;assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(writes.length,1);assert.deepEqual(f.calls,['preflight']);assert.equal(f.timers.size,0);assert.ok(!writes.join('').includes('synthetic-private'));
});
for(const window of ['one-second','cleanup'])test('initial preflight diagnostic deferred dispatch rechecks '+window+' cutoff',async()=>{
 const f=fixture(),diagnostics=[];f.deps.preflight=async()=>{f.calls.push('preflight');if(window==='cleanup')f.clock.value=2820500;return {status:'PENDING',phase:'PORTS',code:'PORT_UNAVAILABLE'};};f.deps.writePreflightDiagnostic=async line=>diagnostics.push(line);
 const schedule=f.deps.setTimeout;let crossed=false;f.deps.setTimeout=(fn,delay)=>{if(!crossed&&delay===(window==='cleanup'?500:1000)&&f.calls.includes('preflight')){crossed=true;f.clock.value=window==='cleanup'?2821000:2000;}return schedule(fn,delay);};
 const value=await runManualPayment(options,f.limits,f.deps);assert.equal(crossed,true);assert.deepEqual(diagnostics,[]);assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.deepEqual(f.calls,['preflight']);assert.equal(f.timers.size,0);
});
for(const mode of ['reject','stall'])test('initial preflight entry stderr '+mode+' still attempts one final stdout without retries',async()=>{
 const f=fixture(),out=[];let writes=0,code;f.deps.subscribe=()=>()=>{};f.deps.preflight=async()=>{f.calls.push('preflight');return {status:'PENDING',phase:'SOURCE',code:'SOURCE_MISMATCH'};};
 const stdout=Object.assign(new EventEmitter(),{write(text,done){out.push(text);done();}}),stderr=Object.assign(new EventEmitter(),{write(text,done){writes++;if(mode==='reject')done(Error('synthetic-private'));}});
 const task=module.runManualPaymentEntry(cliInput(),{stdout,stderr,exit:value=>{code=value;}},f.deps);await f.tick(1000);if(mode==='stall')await f.tick(2000);await task;
 assert.equal(code,1);assert.equal(writes,1);assert.equal(out.length,1);assert.deepEqual(JSON.parse(out[0]),initialPendingReport());assert.deepEqual(f.calls,['preflight']);assert.equal(f.timers.size,0);
});
