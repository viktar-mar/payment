import {test} from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {fileURLToPath} from 'node:url';
const {authorizeManualPaymentCli=async()=>false,formatManualPaymentAuthDiagnostic=()=>''}=await import('./stripe-manual-payment-auth.mjs').catch(()=>({}));
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
const warning='Warning: the system keyring is unavailable. Your credentials have been stored unencrypted in '+ROOT+'/test-results/stripe-sandbox/'+runId+'/cli/config/stripe/credentials.json';
test('exact original-run storage warning permits completion only with callback and actual close zero',async()=>{
  const f=fixture({completion:'Done.\n'+warning+'\n'});
  assert.equal(await authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async line=>f.handoffs.push(line),f.deps),true);
  assert.equal(f.dispatches,2);assert.equal(f.handoffs.length,1);
});
const diagnostic=(stage,reason)=>({tag:'stripe_manual_payment_auth_diagnostic',stage,reason});
async function deniedDiagnostic(settings){const f=fixture(settings);let error;try{await authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async()=>{},f.deps);}catch(value){error=value;}assert.ok(error);const line=formatManualPaymentAuthDiagnostic(error);assert.ok(Buffer.byteLength(line)<=256);assert.ok(line.endsWith('\n'));return JSON.parse(line);}
test('unsafe actual completion emits only finite stage and reason',async()=>{assert.deepEqual(await deniedDiagnostic({completion:'access_token=synthetic-private'}),diagnostic('COMPLETE','OUTPUT_UNSAFE'));});
test('unbranded hostile errors never supply diagnostic properties',()=>{
  let reads=0;const hostile=new Proxy({},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;}});
  assert.deepEqual(JSON.parse(formatManualPaymentAuthDiagnostic(hostile)),diagnostic('UNKNOWN','INTERNAL'));assert.equal(reads,0);
});
test('unexpected auth dependency errors keep unknown classification without reading getters',async()=>{
  let reads=0;const f=fixture();f.deps.now=()=>{throw new Proxy({},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;}});};
  await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async()=>{},f.deps),error=>{assert.deepEqual(JSON.parse(formatManualPaymentAuthDiagnostic(error)),diagnostic('UNKNOWN','INTERNAL'));return true;});assert.equal(reads,0);assert.equal(f.dispatches,0);
});
test('handoff rejection is classified at its boundary without reading external errors',async()=>{
  let reads=0;const f=fixture();
  await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async()=>{throw new Proxy({},{get(){reads++;throw null;}});},f.deps),error=>{assert.deepEqual(JSON.parse(formatManualPaymentAuthDiagnostic(error)),diagnostic('HANDOFF','HANDOFF_FAILED'));return true;});assert.equal(reads,0);assert.equal(f.dispatches,1);
});
for(const [name,completion] of [
  ['foreign',warning.replace(runId,'00000000-0000-4000-8000-000000000000')],['sibling',warning.replace('/cli/config/','/cli-other/config/')],
  ['traversal',warning.replace('/cli/config/','/cli/../cli/config/')],['prefix','prefix '+warning],['suffix',warning+' suffix'],['duplicate',warning+'\n'+warning],
  ['additional marker',warning+'\npassword=synthetic-private'],['control',warning+'\n\x1b[0m'],['oversized',warning+'\n'+'x'.repeat(65536)]
])test('storage warning rejects '+name+' output without diagnostic leakage',async()=>{
  const value=await deniedDiagnostic({completion});assert.equal(value.stage,'COMPLETE');assert.ok(['OUTPUT_UNSAFE','OUTPUT_INVALID'].includes(value.reason));
});
test('known warning is never accepted in stderr or login JSON',async()=>{
  assert.deepEqual(await deniedDiagnostic({stderr:warning}),diagnostic('LOGIN','OUTPUT_INVALID'));
  assert.deepEqual(await deniedDiagnostic({raw:warning+'\n'+raw}),diagnostic('HANDOFF','HANDOFF_INVALID'));
  const f=fixture({completion:warning});let count=0;const execute=f.deps.execFile;f.deps.execFile=(file,args,options,cb)=>execute(file,args,options,(err,out,stderr)=>cb(err,out,++count===2?warning:stderr));
  await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async()=>{},f.deps),error=>JSON.parse(formatManualPaymentAuthDiagnostic(error)).reason==='OUTPUT_UNSAFE');
});
test('raw completion channels remain jointly bounded before warning removal',async()=>{
  const f=fixture({completion:warning+'\n'+'x'.repeat(32000)});let count=0;const execute=f.deps.execFile;f.deps.execFile=(file,args,options,cb)=>execute(file,args,options,(err,out,stderr)=>cb(err,out,++count===2?'y'.repeat(34000):stderr));
  await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async()=>{},f.deps),error=>JSON.parse(formatManualPaymentAuthDiagnostic(error)).reason==='OUTPUT_INVALID');
});
test('first callback failure survives missing close and late child success',async()=>{
  const {f,limits,tick}=clocked({error:new Proxy({},{get(){throw null;}}),noClose:true});
  const task=authorizeManualPaymentCli({cli:f.cli,runId},limits,async()=>{},f.deps);const denied=assert.rejects(task,error=>JSON.parse(formatManualPaymentAuthDiagnostic(error)).reason==='PROCESS_FAILED');
  await tick(1000);await tick(11000);await denied;assert.equal(f.dispatches,1);
});
for(const mode of ['abort','timeout','missing close'])test('finite diagnostic retains '+mode+' as the original cause',async()=>{
  const {f,controller,limits,tick}=clocked({noClose:true});
  if(mode!=='missing close')f.deps.execFile=()=>new EventEmitter();
  const task=authorizeManualPaymentCli({cli:f.cli,runId},limits,async()=>{},f.deps),expected=mode==='abort'?'ABORTED':mode==='timeout'?'TIMEOUT':'CHILD_CLOSE_UNCONFIRMED';
  const denied=assert.rejects(task,error=>{assert.deepEqual(JSON.parse(formatManualPaymentAuthDiagnostic(error)),diagnostic('LOGIN',expected));return true;});
  await tick(1000);if(mode==='abort')controller.abort();if(mode==='timeout')await tick(181000);await tick(mode==='timeout'?191000:11000);await denied;assert.equal(f.handoffs.length,0);
});
for(const [name,stderr] of [['marker','api_key=synthetic-private'],['control','\x00'],['oversized','x'.repeat(65536)]])test('known stdout warning cannot mask '+name+' in stderr',async()=>{
  const f=fixture({completion:warning});let count=0;const execute=f.deps.execFile;f.deps.execFile=(file,args,options,cb)=>execute(file,args,options,(err,out,value)=>cb(err,out,++count===2?stderr:value));
  await assert.rejects(authorizeManualPaymentCli({cli:f.cli,runId},{deadline:Date.now()+2000},async()=>{},f.deps),error=>{const value=JSON.parse(formatManualPaymentAuthDiagnostic(error));assert.deepEqual(value,diagnostic('COMPLETE',name==='oversized'?'OUTPUT_INVALID':'OUTPUT_UNSAFE'));return true;});
});
