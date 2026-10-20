import {test} from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {fileURLToPath} from 'node:url';
const {authorizeManualPaymentCli=async()=>false}=await import('./stripe-manual-payment-auth.mjs').catch(()=>({}));
const ROOT=fileURLToPath(new URL('..',import.meta.url)).replace(/\/$/,''),runId='7deaf3e1-1247-4d80-9cf5-67ebcd24429a';
const policy='(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))';
function descriptor(complete=false){const directory=ROOT+'/test-results/stripe-sandbox/'+runId+'/cli';return Object.freeze({file:'/usr/bin/sandbox-exec',args:Object.freeze(['-p',policy,'/opt/homebrew/bin/stripe','--config',directory+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error','login',...(complete?['--complete-device']:[])]),options:Object.freeze({cwd:ROOT,env:Object.freeze({PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:directory+'/home',XDG_CONFIG_HOME:directory+'/config',NO_COLOR:'1'}),shell:false,stdio:Object.freeze(['pipe','pipe','pipe']),timeout:complete?600000:180000,maxBuffer:65536})});}
const raw=JSON.stringify({browser_url:'https://dashboard.stripe.com/stripecli/confirm_auth?t=synthetic',verification_code:'alpha-bravo',next_step:'stripe login --complete-device'});
function fixture(settings={}){let dispatches=0,handoffs=[];const cli={loginInvocation:async()=>settings.descriptor??descriptor(),completeLoginInvocation:async()=>descriptor(true)};return {cli,handoffs,get dispatches(){return dispatches;},deps:{execFile:(file,args,options,callback)=>{dispatches++;const child=new EventEmitter();queueMicrotask(()=>{callback(settings.error??null,dispatches===1?(settings.raw??raw):(settings.completion??''),settings.stderr??'');if(!settings.noClose)child.emit('close',settings.code??0,settings.signal??null);});return child;}}};}
test('auth emits only genuine allowed handoff then completes exact isolated login',async()=>{const f=fixture();assert.equal(await authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async line=>f.handoffs.push(line),f.deps),true);assert.equal(f.dispatches,2);assert.equal(f.handoffs.length,1);assert.ok(f.handoffs[0].startsWith('Authorize the isolated Sandbox CLI at https://dashboard.stripe.com/'));});
for(const rawValue of [raw.replace('https://dashboard.stripe.com','https://evil.invalid'),raw.replace('alpha-bravo','whsec_canary'),raw.replace('stripe login --complete-device','curl evil.invalid'),raw.replace('"browser_url":','"browser_url":"https://evil.invalid","browser_url":')])test('rejects untrusted auth output without completion dispatch '+rawValue.slice(-25),async()=>{const f=fixture({raw:rawValue});await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async line=>f.handoffs.push(line),f.deps));assert.equal(f.dispatches,1);assert.equal(f.handoffs.length,0);});
for(const settings of [{code:1},{signal:'SIGTERM'},{error:Error('canary')},{stderr:'sk_test_canary'}])test('callback nonzero signal or unsafe stderr denies completion '+JSON.stringify(settings),async()=>{const f=fixture(settings);await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async line=>f.handoffs.push(line),f.deps));assert.equal(f.dispatches,1);assert.equal(f.handoffs.length,0);});
test('changed descriptor or inherited environment is denied before dispatch',async()=>{const d=descriptor();const f=fixture({descriptor:Object.freeze({...d,options:Object.freeze({...d.options,env:Object.freeze({...d.options.env,STRIPE_API_KEY:'sk_test_canary'})})})});await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async()=>{},f.deps));assert.equal(f.dispatches,0);});
test('benign completion text is discarded after callback and actual child close',async()=>{const f=fixture({completion:'Authentication completed successfully.\n'});assert.equal(await authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async line=>f.handoffs.push(line),f.deps),true);assert.equal(f.handoffs.length,1);assert.ok(!f.handoffs.join('').includes('completed successfully'));});
for(const completion of ['sk_test_canary','Authorization: Bearer canary','Set-Cookie: canary','access_token=canary','token=canary','authentication_token=canary','\x1b[32mDone\x1b[0m','Done\x00'])test('completion credential marker or control cannot establish authorization '+JSON.stringify(completion),async()=>{const f=fixture({completion});await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async()=>{},f.deps));});
test('stdio accessors are rejected before reading or dispatching them',async()=>{
  let reads=0;const d=descriptor(),stdio=['pipe','pipe','pipe'];Object.defineProperty(stdio,0,{enumerable:true,get(){reads++;return 'pipe';}});Object.freeze(stdio);
  const f=fixture({descriptor:Object.freeze({...d,options:Object.freeze({...d.options,stdio})})});await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async()=>{},f.deps));assert.equal(reads,0);assert.equal(f.dispatches,0);
});
function clocked(settings={}){
  let now=1000;const timers=new Map(),f=fixture(settings),controller=new AbortController(),limits={deadline:901000,signal:controller.signal};
  Object.assign(f.deps,{now:()=>now,setTimeout:(fn,delay)=>{const id={};timers.set(id,{fn,at:now+delay});return id;},clearTimeout:id=>timers.delete(id)});
  const tick=async at=>{now=at;for(const [id,t] of [...timers])if(t.at<=at){timers.delete(id);t.fn();}for(let i=0;i<100;i++)await Promise.resolve();};
  return {f,controller,limits,timers,tick};
}
test('callback without actual child close never emits handoff or starts completion',async()=>{
  const {f,limits,tick,timers}=clocked({noClose:true});const task=authorizeManualPaymentCli({cli:f.cli,runId},limits,async line=>f.handoffs.push(line),f.deps);const rejected=assert.rejects(task);
  await tick(1000);assert.equal(f.dispatches,1);assert.equal(f.handoffs.length,0);await tick(11000);await rejected;assert.equal(f.dispatches,1);assert.equal(f.handoffs.length,0);assert.equal(timers.size,0);
});
test('abort and timeout drain only original child without late completion or renewed budget',async()=>{
  for(const mode of ['abort','timeout']){
    const {f,controller,limits,tick,timers}=clocked();let child,callback,childSignal;
    f.deps.execFile=(file,args,options,cb)=>{callback=cb;childSignal=options.signal;child=new EventEmitter();return child;};
    const task=authorizeManualPaymentCli({cli:f.cli,runId},limits,async line=>f.handoffs.push(line),f.deps);const rejected=assert.rejects(task);
    await tick(1000);if(mode==='abort')controller.abort();else await tick(181000);assert.equal(childSignal.aborted,true);
    await tick(mode==='abort'?11000:191000);await rejected;
    callback(null,raw,'');child.emit('close',0,null);await tick(200000);assert.equal(f.handoffs.length,0);assert.equal(timers.size,0);
  }
});
test('stalled handoff output expires under the original authority without completion',async()=>{
  const {f,limits,tick,timers}=clocked();let finish;const task=authorizeManualPaymentCli({cli:f.cli,runId},limits,()=>new Promise(resolve=>{finish=resolve;}),f.deps);const rejected=assert.rejects(task);
  await tick(1000);assert.equal(f.dispatches,1);await tick(901000);await rejected;finish();await tick(901001);assert.equal(f.dispatches,1);assert.equal(timers.size,0);
});
test('caller mutation cannot renew authorization deadline between the two children',async()=>{
  const {f,limits,tick}=clocked();f.cli.completeLoginInvocation=async()=>{await tick(901000);return descriptor(true);};
  const task=authorizeManualPaymentCli({cli:f.cli,runId},limits,async()=>{limits.deadline=1801000;},f.deps);
  await assert.rejects(task);assert.equal(f.dispatches,1);
});
