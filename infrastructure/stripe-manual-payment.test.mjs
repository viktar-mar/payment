import {test} from 'node:test';
import assert from 'node:assert/strict';
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
  const script="import {syncBuiltinESMExports} from 'node:module';import cp from 'node:child_process';import fs from 'node:fs';import fsp from 'node:fs/promises';import http from 'node:http';import net from 'node:net';const trap=()=>{throw Error('private boundary touched');};cp.execFile=cp.spawn=cp.spawnSync=trap;fs.readFileSync=trap;fsp.readFile=trap;http.createServer=net.createServer=trap;globalThis.fetch=trap;syncBuiltinESMExports();await import('./infrastructure/stripe-manual-payment.mjs');await import('./infrastructure/stripe-manual-payment-auth.mjs');";
  const result=spawnSync(process.execPath,['--input-type=module','-e',script],{cwd:new URL('..',import.meta.url),env:{PATH:'/usr/bin:/bin',LANG:'C',LC_ALL:'C',NO_COLOR:'1'},encoding:'utf8'});assert.equal(result.status,0,result.stderr);assert.equal(result.stdout,'');
});
function cliInput(){return {args:[],env:{...options.env,PAYMENT_STRIPE_SANDBOX_RUN_ID:runId,PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:expectedSource,PAYMENT_STRIPE_SANDBOX_DAEMON:options.daemon,...Object.fromEntries(Object.entries(options.ports).map(([key,value])=>['PAYMENT_STRIPE_SANDBOX_PORT_'+key.toUpperCase(),String(value)])),PAYMENT_STRIPE_SANDBOX_PORT_MANUAL:String(options.manualPort)}};}
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
