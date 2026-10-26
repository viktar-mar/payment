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
      'stripe-manual-form-options.mjs','stripe-sandbox-acceptance.mjs',
      'stripe-sandbox-bootstrap.mjs','stripe-sandbox-owned.mjs',
      'stripe-sandbox-cli.mjs','stripe-sandbox-provision.mjs',
      'stripe-sandbox-ownership.mjs','stripe-manual-refund-options.mjs','stripe-manual-refund-remainder-options.mjs',
      'stripe-sandbox-extras.mjs','smoke-stage-5a.mjs',
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
      'infrastructure/stripe-manual-payment.mjs']){
      assert.throws(()=>fs.readFileSync(new URL(file,root)),/private boundary touched/);
      await assert.rejects(async()=>fsp.readFile(new URL(file,root)),/private boundary touched/);
    }
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
  const value=await task;assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(value.report.listener_closed,false);assert.equal(value.report.runtime_closed,mode!=='ceiling');assert.equal(writes.filter(line=>line.startsWith('{')).length,1);assert.ok(!f.calls.includes('binding'));assert.ok(!f.calls.includes('provision'));assert.equal(f.timers.size,0);
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
