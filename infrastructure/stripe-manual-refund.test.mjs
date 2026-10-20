import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {Writable} from 'node:stream';
import {spawnSync} from 'node:child_process';
import {runManualRefund,runManualRefundCli,runManualRefundEntry} from './stripe-manual-refund.mjs';
import {manualPaymentOptIn} from './stripe-manual-payment-options.mjs';
import {manualFormOptIn} from './stripe-manual-form-options.mjs';
import {formDiagnosticOptIn} from './stripe-sandbox-owned.mjs';
import {runManualPaymentCli} from './stripe-manual-payment.mjs';
import {runManualFormCli} from './stripe-manual-form.mjs';
import {openOwnedSandboxAcceptance} from './stripe-sandbox-acceptance.mjs';
import {createOwnedSandboxBootstrap} from './stripe-sandbox-bootstrap.mjs';
import {createOwnedSandboxProvisioner} from './stripe-sandbox-provision.mjs';
import {runStripeSandboxSmoke} from './smoke-stage-5a.mjs';
const options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND:'true'},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'a'.repeat(40),daemon:'unix:///Users/synthetic/docker.sock'};
const defer=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
function fixture(){
  let time=1000;const calls=[],seen=[],timers=new Map(),abort=new AbortController();
  const limits={deadline:1801000,signal:abort.signal};
  const action=(name,value=true)=>async(...args)=>{calls.push(name);seen.push([name,args]);return value;};
  const target={originalRunId:'031b5ae7-6603-4339-b1b3-77231291ff34',ports:{backend:18080}};
  const state={target,close:action('state-close'),withRetainedAdmin:action('admin'),verifyRetainedWebhookSecret:action('secret')};
  const runtime={restore:action('restore'),verify:action('verify'),withAdminCredentials:action('admin-owned'),verifyForwardTarget:action('forward-owned'),verifyWebhookSecret:action('secret-owned'),stop:action('runtime-stop')};
  const cli={validateBinding:action('binding'),startListener:action('listener'),bindCapturedPayment:action('capture'),stopListener:action('listener-stop')};
  const proof=i=>({refundId:'66666666-7777-4888-8999-00000000000'+i,providerReference:i?'re_second':'re_first',providerEventId:i?'evt_second':'evt_first',amountMinor:i?1300:700,totalMinor:i?2000:700,providerVerified:true,webhookVerified:true,journalVerified:true,callbackVerified:true});
  const session={verifyBaseline:action('baseline'),rotateCallback:action('rotation'),refundPartial:action('partial',proof(0)),refundRemainder:action('remainder',proof(1)),verifyFinal:action('final',{partialConfirmed:true,remainderConfirmed:true,providerVerified:true,webhookVerified:true,journalVerified:true,callbackVerified:true}),close:action('session-close')};
  const deps={now:()=>time,setTimeout:(fn,delay)=>{const id={};timers.set(id,{fn,at:time+delay});return id;},clearTimeout:id=>timers.delete(id),openState:action('state',state),createRuntime:action('runtime',runtime),createCli:action('cli',cli),createSession:action('session',session)};
  for(const name of ['createBrowser','runBrowser','prepare','confirm','login','authorizeCli','runStripeSandboxSmoke','reconcile','cancel','delete','providerWrite'])Object.defineProperty(deps,name,{get(){assert.fail('forbidden controller capability: '+name);}});
  const turn=async()=>{for(let i=0;i<300;i++)await Promise.resolve();};
  const tick=async at=>{time=at;for(const [id,t] of [...timers])if(t.at<=time){timers.delete(id);t.fn();}await turn();};
  return {calls,seen,timers,abort,limits,state,runtime,cli,session,deps,turn,tick,proof};
}
// Break: reordering, extra capabilities, wrong original CLI directory or renewed authority.
test('controller composes accepted capabilities once and emits only complete proof',async()=>{
  const f=fixture(),value=await runManualRefund(options,f.limits,f.deps);
  assert.equal(value.exitCode,0);assert.equal(value.report.result,'SUCCEEDED');assert.equal(value.report.phase,'CLEANUP');
  assert.deepEqual(f.calls,['state','runtime','restore','cli','binding','listener','capture','session','baseline','rotation','partial','remainder','final','session-close','listener-stop','runtime-stop','state-close']);
  assert.ok(Object.entries(value.report).filter(([k])=>k.endsWith('_verified')||k.endsWith('_confirmed')||k.endsWith('_closed')||k==='callback_rotated').every(([,v])=>v===true));
  assert.deepEqual(f.seen.find(([n])=>n==='runtime')[1],[{options,state:f.state}]);
  const cliInput=f.seen.find(([n])=>n==='cli')[1][0];
  assert.deepEqual(Object.keys(cliInput).sort(),['approvedBinding','backendPort','cliDirectory','env','originalRunId','persistWebhookSecret','verifyForwardTarget']);
  assert.equal(cliInput.cliDirectory,new URL('../test-results/stripe-sandbox/031b5ae7-6603-4339-b1b3-77231291ff34/cli',import.meta.url).pathname);
  assert.equal(cliInput.persistWebhookSecret,f.runtime.verifyWebhookSecret);assert.equal(cliInput.verifyForwardTarget,f.runtime.verifyForwardTarget);
  assert.deepEqual(f.seen.find(([n])=>n==='capture')[1],[f.state.target,f.seen.find(([n])=>n==='restore')[1][0]]);
  assert.deepEqual(f.seen.find(([n])=>n==='session')[1],[{runtime:f.runtime,cli:f.cli,state:f.state}]);
  for(const name of ['restore','binding','listener','baseline','rotation','partial','remainder','final'])assert.equal(f.seen.find(([n])=>n===name)[1][0].deadline,1801000);
  for(const name of ['session-close','listener-stop','runtime-stop'])assert.equal(f.seen.find(([n])=>n===name)[1][0].deadline,1921000);
  assert.deepEqual(f.seen.find(([n])=>n==='state-close')[1],[{retain:false},{deadline:1921000}]);assert.equal(f.timers.size,0);
});
for(const [name,owner,method,phase] of [['state','deps','openState','RESTORE'],['runtime','deps','createRuntime','RESTORE'],['restore','runtime','restore','RESTORE'],['cli','deps','createCli','BINDING'],['binding','cli','validateBinding','BINDING'],['listener','cli','startListener','LISTENER'],['capture','cli','bindCapturedPayment','BASELINE'],['session','deps','createSession','BASELINE'],['baseline','session','verifyBaseline','BASELINE'],['rotation','session','rotateCallback','ROTATION'],['partial','session','refundPartial','PARTIAL'],['remainder','session','refundRemainder','REMAINDER'],['final','session','verifyFinal','VERIFY']]){
  test(name+' failure latches PENDING with no next phase or retry',async()=>{const f=fixture();f[owner][method]=async()=>{f.calls.push(name);throw new Proxy({},{get(){assert.fail('raw failure inspected');}});};const value=await runManualRefund(options,f.limits,f.deps);assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(value.report.phase,phase);assert.equal(f.calls.filter(x=>x===name).length,1);if(!['remainder','final'].includes(name))assert.equal(f.calls.includes('remainder'),false);});
}
test('malformed or partial proof never permits the second refund',async()=>{for(const field of ['providerVerified','webhookVerified','journalVerified','callbackVerified']){const f=fixture();f.session.refundPartial=async()=>({...f.proof(0),[field]:false});const v=await runManualRefund(options,f.limits,f.deps);assert.equal(v.exitCode,1);assert.equal(f.calls.includes('remainder'),false);assert.equal(v.report.partial_confirmed,false);}});
test('denied admission touches neither dependencies nor private environment',async()=>{let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});const v=await runManualRefund({...options,env:{}},null,trap);assert.equal(v.report.result,'FAILED');assert.equal(v.report.phase,'ADMISSION');assert.equal(touched,0);});
test('failed session and listener close independently allow runtime and private close',async()=>{const f=fixture();f.session.close=async()=>{f.calls.push('session-close');throw null;};f.cli.stopListener=async()=>{f.calls.push('listener-stop');throw null;};const v=await runManualRefund(options,f.limits,f.deps);assert.equal(v.report.result,'PENDING');assert.equal(v.report.listener_closed,false);assert.equal(v.report.runtime_closed,true);assert.ok(f.calls.includes('state-close'));assert.deepEqual(f.seen.find(([n])=>n==='state-close')[1][0],{retain:true});});
for(const [owner,method,name] of [['deps','openState','state'],['deps','createRuntime','runtime'],['deps','createCli','cli'],['deps','createSession','session']])test('abort still owns and closes late '+name+' acquisition',async()=>{const f=fixture(),late=defer(),resource={state:f.state,runtime:f.runtime,cli:f.cli,session:f.session}[name];f[owner][method]=()=>{f.calls.push(name);return late.promise;};const running=runManualRefund(options,f.limits,f.deps);await f.turn();f.abort.abort();await f.turn();late.resolve(resource);const v=await running;assert.equal(v.report.result,'PENDING');assert.equal(v.exitCode,1);assert.ok(f.calls.includes(({state:'state-close',runtime:'runtime-stop',cli:'listener-stop',session:'session-close'})[name]));});
test('timeout cancels nested work without renewing deadline and drains before runtime stop',async()=>{const f=fixture(),late=defer();let signal;f.runtime.restore=async l=>{signal=l.signal;f.calls.push('restore');return late.promise;};const running=runManualRefund(options,f.limits,f.deps);await f.turn();await f.tick(1801000);assert.equal(signal.aborted,true);assert.equal(f.calls.includes('runtime-stop'),false);late.resolve(true);const v=await running;assert.equal(v.report.result,'PENDING');assert.equal(v.report.phase,'RESTORE');assert.ok(f.calls.includes('runtime-stop'));});
const cliEnv=()=>({...options.env,PAYMENT_STRIPE_SANDBOX_RUN_ID:options.runId,PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:options.expectedSource,PAYMENT_STRIPE_SANDBOX_DAEMON:options.daemon});
test('CLI snapshots only flags and new authority, writes one finite JSON, and removes signals',async()=>{const f=fixture(),env=cliEnv(),lines=[];Object.defineProperty(env,'STRIPE_SECRET_KEY',{get(){assert.fail('private env read');}});let removed=false;const v=await runManualRefundCli({env,args:[]},{...f.deps,write:async text=>lines.push(text),subscribe:()=>()=>{removed=true;}});assert.equal(v.exitCode,0);assert.equal(lines.length,1);assert.equal(Buffer.byteLength(lines[0])<=4096,true);assert.equal(JSON.parse(lines[0]).result,'SUCCEEDED');assert.equal(removed,true);});
test('entry exits nonzero if its single stdout attempt fails',async()=>{const f=fixture(),stream=new EventEmitter(),lines=[],codes=[];stream.write=(text,cb)=>{lines.push(text);cb(Error('synthetic'));};await runManualRefundEntry({env:cliEnv(),args:[]},{stdout:stream,exit:c=>codes.push(c)},{...f.deps,subscribe:()=>()=>{}});assert.deepEqual(codes,[1]);assert.equal(lines.length,1);assert.equal(stream.listenerCount('error'),0);});
test('hung stdout has one bounded attempt and exit1',async()=>{const f=fixture(),late=defer();let writes=0;const running=runManualRefundCli({env:{},args:[]},{...f.deps,write:()=>{writes++;return late.promise;},subscribe:()=>()=>{}});await f.turn();await f.tick(2000);const v=await running;assert.equal(v.exitCode,1);assert.equal(writes,1);late.resolve();});
test('old modes reject MANUAL_REFUND before normalized snapshots can strip it',async()=>{const refund={PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND:'true'},base={PAYMENT_STRIPE_SANDBOX_ENABLED:'true'};assert.equal(manualPaymentOptIn({...base,PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true',...refund}),false);assert.equal(manualFormOptIn({...base,PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true',...refund}),false);assert.equal(formDiagnosticOptIn({...base,PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',...refund}),false);
  for(const [run,flags] of [[runManualPaymentCli,{PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'}],[runManualFormCli,{PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true'}]]){let touched=0;const env={...base,...flags,...refund};Object.defineProperty(env,'PAYMENT_STRIPE_SANDBOX_RUN_ID',{get(){touched++;throw null;}});const v=await run({env,args:[]},{write:async()=>{},subscribe:()=>()=>{}});assert.equal(v.exitCode,1);assert.equal(touched,0);}
});
test('all old full entry capabilities refuse refund mode before dependencies',async()=>{
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}}),env={...options.env,PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'};
  for(const create of [openOwnedSandboxAcceptance,createOwnedSandboxBootstrap,createOwnedSandboxProvisioner])await assert.rejects(()=>create({env},trap,trap),/OPT_IN_REQUIRED/);
  await assert.rejects(()=>runStripeSandboxSmoke({env,fetch:()=>{touched++;throw null;}}),/OPT_IN_REQUIRED/);
  assert.equal(touched,0);
});
for(const [owner,method,phase] of [['runtime','restore','RESTORE'],['cli','validateBinding','BINDING'],['cli','startListener','LISTENER'],['cli','bindCapturedPayment','BASELINE'],['session','verifyBaseline','BASELINE'],['session','rotateCallback','ROTATION'],['session','refundPartial','PARTIAL'],['session','refundRemainder','REMAINDER'],['session','verifyFinal','VERIFY']]){
  for(const reason of ['abort','timeout'])test(reason+' during '+method+' forbids subsequent writes and retains evidence',async()=>{
    const f=fixture(),late=defer();let signal;f[owner][method]=l=>{signal=l.signal;return late.promise;};
    // Captured binding receives target before its Limits.
    if(method==='bindCapturedPayment')f.cli.bindCapturedPayment=(_target,l)=>{signal=l.signal;return late.promise;};
    const running=runManualRefund(options,f.limits,f.deps);await f.turn();
    if(reason==='abort')f.abort.abort();else await f.tick(1801000);await f.turn();
    assert.equal(signal.aborted,true);late.resolve(true);const v=await running;
    assert.equal(v.report.result,'PENDING');assert.equal(v.report.phase,phase);assert.equal(v.exitCode,1);
    if(!['refundRemainder','verifyFinal'].includes(method))assert.equal(f.calls.includes('remainder'),false);
    assert.deepEqual(f.seen.find(([n])=>n==='state-close')[1][0],{retain:true});
  });
}
test('hung cleanup attempts do not consume independent runtime and state closure opportunities',async()=>{
  const f=fixture(),sessionClose=defer(),listenerClose=defer();f.session.close=()=>sessionClose.promise;f.cli.stopListener=()=>listenerClose.promise;
  const running=runManualRefund(options,f.limits,f.deps);await f.turn();await f.tick(11000);await f.tick(21000);
  assert.ok(f.calls.includes('runtime-stop'));await f.tick(31000);assert.ok(f.calls.includes('state-close'));
  sessionClose.resolve(true);listenerClose.resolve(true);const v=await running;assert.equal(v.report.result,'PENDING');assert.equal(v.report.runtime_closed,true);assert.equal(v.report.listener_closed,false);
});
test('signal during final stdout forces exit1 while preserving one report attempt',async()=>{
  const f=fixture(),output=defer();let stop,removed=false,writes=0;
  const running=runManualRefundCli({env:cliEnv(),args:[]},{...f.deps,subscribe:callback=>{stop=callback;return ()=>{removed=true;};},write:()=>{writes++;return output.promise;}});
  await f.turn();assert.equal(writes,1);assert.equal(removed,false);stop();await f.turn();output.resolve();
  const value=await running;assert.equal(value.exitCode,1);assert.equal(value.report.result,'PENDING');assert.equal(writes,1);assert.equal(removed,true);
});
test('real failing stdout consumes its error event and exits only once',async()=>{
  const f=fixture(),codes=[];let writes=0;
  const stream=new Writable({write(_chunk,_encoding,callback){writes++;callback(Error('synthetic stdout failure'));}});
  await runManualRefundEntry({env:cliEnv(),args:[]},{stdout:stream,exit:code=>codes.push(code)},{...f.deps,subscribe:()=>()=>{}});
  await new Promise(accept=>setImmediate(accept));assert.deepEqual(codes,[1]);assert.equal(writes,1);
});
test('cleanup is the reported phase when an otherwise complete proof loses closure',async()=>{
  const f=fixture();f.runtime.stop=async()=>false;
  const value=await runManualRefund(options,f.limits,f.deps);
  assert.equal(value.report.result,'PENDING');assert.equal(value.report.phase,'CLEANUP');assert.equal(value.report.runtime_closed,false);
});
test('cleanup drains the actual canceled session body before stopping the runtime',async()=>{
  const f=fixture(),body=defer();f.session.rotateCallback=()=>body.promise;
  const running=runManualRefund(options,f.limits,f.deps);await f.turn();f.abort.abort();await f.turn();
  try{assert.equal(f.calls.includes('runtime-stop'),false);}
  finally{body.resolve(true);await running;}
});
test('synthetic successful controller with broken stdout has actual process exit1 and no raw errors',()=>{
  // Import the inert named function in a child with synthetic capabilities;
  // never invoke the direct Sandbox entry or acquire its default boundaries.
  const source=`import assert from 'node:assert/strict';
    import {Writable} from 'node:stream';
    import {runManualRefundEntry} from ${JSON.stringify(new URL('./stripe-manual-refund.mjs',import.meta.url).href)};
    const options=${JSON.stringify(options)};
    const fixture=${fixture.toString()};const f=fixture();
    const stream=new Writable({write(_chunk,_encoding,callback){callback(Error('synthetic stdout failure'));}});
    await runManualRefundEntry({env:${JSON.stringify(cliEnv())},args:[]},{stdout:stream,exit:code=>process.exit(code)},{...f.deps,subscribe:()=>()=>{}});`;
  const child=spawnSync(process.execPath,['--input-type=module','-e',source],{env:{PATH:'/usr/bin:/bin',LANG:'C',LC_ALL:'C'},encoding:'utf8',timeout:5000,maxBuffer:4096});
  assert.equal(child.status,1);assert.equal(child.signal,null);assert.equal(child.stdout,'');assert.equal(child.stderr,'');assert.equal(child.error,undefined);
});
