import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import net from 'node:net';
import filesystem from 'node:fs/promises';
import {syncBuiltinESMExports} from 'node:module';
import {EventEmitter,getEventListeners} from 'node:events';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {openOwnedSandboxAcceptance,formatSandboxAuthorization,formatSandboxDiagnostic} from './stripe-sandbox-acceptance.mjs';

const forbidden=()=>{throw new Error('synthetic_test_forbids_external_boundary');};
for(const name of ['execFile','exec','spawn','fork','execSync','spawnSync','execFileSync'])mock.method(childProcess,name,forbidden);
for(const name of ['createServer','connect','createConnection'])mock.method(net,name,forbidden);
mock.method(globalThis,'fetch',forbidden);
for(const name of ['open','readFile','lstat','realpath','readdir','mkdir','rename','unlink'])mock.method(filesystem,name,forbidden);
syncBuiltinESMExports();after(()=>{mock.restoreAll();syncBuiltinESMExports();});
const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const RUN='11111111-2222-4333-8444-555555555555';
const ENV={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'};
for(const flag of ['PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT','PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY','PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC'])
test('full acceptance rejects mixed '+flag+' before dependency or limits access',async()=>{
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  await assert.rejects(openOwnedSandboxAcceptance({env:{...ENV,[flag]:'true'}},trap,trap),{message:'stripe_sandbox_acceptance_OPT_IN_REQUIRED'});
  assert.equal(touched,0);
});
const PORTS={backend:18080,demo:14173,receiver:18090,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025};
const options=()=>({env:{...ENV},runId:RUN,expectedSource:'a'.repeat(40),daemon:'unix:///synthetic/docker.sock',ports:{...PORTS}});
const limits=()=>({deadline:Date.now()+120000,signal:new AbortController().signal});
const authorization={tag:'stripe_sandbox_authorization',browser_url:'https://access.stripe.com/device/verify?device=public',verification_code:'ABCD-EFGH'};
const json=()=>JSON.stringify({browser_url:authorization.browser_url,verification_code:authorization.verification_code,next_step:'stripe login --complete-device'});
const frozen=value=>{if(value&&typeof value==='object'){for(const item of Object.values(value))frozen(item);Object.freeze(value);}return value;};
function captureOutput(lines=[]) {
  const original=process.stdout.write.bind(process.stdout);
  return mock.method(process.stdout,'write',(...args)=>{
    // Node's native test runner uses binary protocol writes; never suppress its evidence.
    if(typeof args[0]!=='string')return original(...args);
    lines.push(args[0]);return true;
  });
}
function fixture() {
  const events=[],commands=[],children=[],output=[];let topology={status:'NEW',runId:RUN,cliDirectory:ROOT+'/test-results/stripe-sandbox/'+RUN+'/cli',ports:PORTS,
    endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:14173',receiver:'http://127.0.0.1:18090'}};
  const catalog={...topology,status:'READY',site:{id:'00000000-0000-4000-8000-000000000002',key:'s5a-'+RUN.replaceAll('-','')},
    price:{id:'00000000-0000-4000-8000-000000000004',key:'s5a-price-'+RUN.replaceAll('-','')},campaign:{id:'00000000-0000-4000-8000-000000000006',key:'s5a-donate-'+RUN.replaceAll('-',''),version:1}};
  const record=(name,value=true)=>async context=>{events.push(name);assert.ok(context.signal instanceof AbortSignal);assert.ok(context.deadline<=setup.deadline);return value;};
  const setup=limits();
  const bootstrap={prepare:async context=>{assert.equal(context.deadline,setup.deadline);events.push('prepare');topology={...topology,status:'PREPARED'};return true;},
    resources:()=>topology,persistWebhookSecret:async secret=>{assert.equal(secret,'whsec_synthetic');events.push('durable-secret');return true;},
    verifyForwardTarget:async()=>true,start:async context=>{events.push('start');topology={...topology,status:'LIVE'};return true;},verify:record('verify'),
    withAdminCredentials:async cb=>cb(Object.freeze({username:'synthetic',password:'synthetic'})),
    stop:async context=>{events.push('stop-services');return true;}};
  function descriptor(complete=false) {
    return frozen({file:'/usr/bin/sandbox-exec',args:['-p','(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))','/opt/homebrew/bin/stripe',
      '--config',topology.cliDirectory+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error','login',...(complete?['--complete-device']:[])],
      options:{cwd:ROOT,env:{PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:topology.cliDirectory+'/home',XDG_CONFIG_HOME:topology.cliDirectory+'/config',NO_COLOR:'1'},shell:false,stdio:['pipe','pipe','pipe'],timeout:complete?600000:180000,maxBuffer:65536}});
  }
  let capturedCliOptions;
  const cli={loginInvocation:async context=>{events.push('login-descriptor');return descriptor();},completeLoginInvocation:async context=>{events.push('complete-descriptor');return descriptor(true);},
    validateBinding:record('binding'),startListener:async context=>{events.push('listen');assert.equal(await capturedCliOptions.persistWebhookSecret('whsec_synthetic'),true);return true;},
    stopListener:async context=>{events.push('stop-listener');return true;}};
  const provisioner={provision:record('provision'),resources:()=>catalog,withCatalogEnvironment:cb=>cb(frozen({...ENV,PAYMENT_STRIPE_SANDBOX_RUN_ID:RUN,DOCKER_HOST:'unix:///synthetic/docker.sock',
    PAYMENT_SANDBOX_ADMIN_USERNAME:'synthetic',PAYMENT_SANDBOX_ADMIN_PASSWORD:'synthetic',PAYMENT_SANDBOX_SUCCESS_CHECKOUT_TOKEN:'synthetic-token',PAYMENT_SANDBOX_DECLINE_CHECKOUT_TOKEN:'synthetic-token',PAYMENT_SANDBOX_SCA_CHECKOUT_TOKEN:'synthetic-token'}))};
  let extraState={status:'OPEN',donations:{anonymous:{confirmed:false},emailOtp:{confirmed:false}},otp:{phase:'NEW'},missed:{confirmed:false}};
  const fourth={close:async()=>{events.push('close-missed');}};
  const extras={open:record('open-extras'),resources:()=>extraState,missedFixture:async context=>{events.push('missed-fixture');return fourth;},close:async context=>{events.push('close-extras');assert.ok(context.signal instanceof AbortSignal);return true;}};
  const deps={now:Date.now,setTimeout,clearTimeout,fetch:forbidden,
    createOwnedSandboxBootstrap:async opts=>{events.push('bootstrap-factory');assert.ok(Object.isFrozen(opts));assert.ok(Object.isFrozen(opts.ports));assert.equal(opts.env.STRIPE_SECRET_KEY,undefined);assert.equal(opts.approvedBinding.externalAccountId,'acct_1UPiMpELswAd8tpR');return bootstrap;},
    createSandboxCliAdapter:async opts=>{capturedCliOptions=opts;events.push('cli-factory');assert.equal(opts.cliDirectory,topology.cliDirectory);assert.equal(opts.backendPort,18080);return cli;},
    createOwnedSandboxProvisioner:async opts=>{events.push('provision-factory');assert.equal(opts.bootstrap,bootstrap);assert.equal(await opts.verifyProviderBinding(setup),true);return provisioner;},
    openStripeSandboxFixture:async opts=>{events.push('open-cards');assert.equal(opts.env.PAYMENT_SANDBOX_RUN_ID,undefined);assert.equal(opts.env.PAYMENT_STRIPE_SANDBOX_RUN_ID,RUN);assert.equal(await opts.verifyOwnership({runId:RUN}),true);return {close:async()=>events.push('close-cards')};},
    runStripeSandboxSmoke:async opts=>{events.push('refunds');return {paymentConfirmed:true,refundConfirmed:true,callbackVerified:true};},
    createSandboxAcceptanceExtras:async opts=>{events.push('extras-factory');assert.equal(opts.provisioner,provisioner);return extras;},
    execFile:(file,args,opts,callback)=>{commands.push({file,args,opts});const child=new EventEmitter();children.push({child,callback});queueMicrotask(()=>{callback(null,args.at(-1)==='--complete-device'?'':json(),'');child.emit('close',0);});return child;}};
  const journeys={cards:async f=>{events.push('cards');return true;},donations:async e=>{events.push('donations');extraState={...extraState,donations:{anonymous:{confirmed:true},emailOtp:{confirmed:true}},otp:{phase:'VERIFIED'}};return true;},
    missed:async f=>{events.push('missed');extraState={...extraState,missed:{confirmed:true}};return true;}};
  return {events,commands,children,output,setup,deps,bootstrap,cli,provisioner,extras,journeys,descriptor};
}
async function quietOpen(f,opts=options()) {
  const write=captureOutput(f.output);let owned;
  try{owned=await openOwnedSandboxAcceptance(opts,f.setup,f.deps);}finally{write.mock.restore();}
  const quiet=method=>async(...args)=>{const output=captureOutput(f.output);try{return await owned[method](...args);}finally{output.mock.restore();}};
  return Object.freeze({...owned,run:quiet('run'),close:quiet('close')});
}
const diagnostics=wire=>wire.map(line=>JSON.parse(line)).filter(value=>value.tag==='stripe_sandbox_diagnostic');
const expectedDiagnostic=(phase,code='OPERATION_FAILED',exitCode=null,cleanupConfirmed=true)=>({tag:'stripe_sandbox_diagnostic',phase,code,exitCode,cleanupConfirmed});
// External work is replaced at the raw process/time boundaries, keeping the real owner.
function timedFixture(budget=1200000) {
  const f=fixture(),alarms=new Map(),processes=[];let now=Date.now(),id=0,setupSignal;
  f.setup.deadline=now+budget;
  const prepare=f.bootstrap.prepare;
  f.bootstrap.prepare=async ctx=>{setupSignal=ctx.signal;return prepare(ctx);};
  f.deps.now=()=>now;
  f.deps.setTimeout=(callback,delay)=>{const key=++id;alarms.set(key,{at:now+delay,callback});return key;};
  f.deps.clearTimeout=key=>alarms.delete(key);
  f.deps.execFile=(file,args,opts,callback)=>{
    const child=new EventEmitter(),record={child,opts,callback};processes.push(record);
    if(processes.length===1)queueMicrotask(()=>{callback(null,json(),'');child.emit('close',0);});
    return child;
  };
  return {...f,processes,alarms,get setupSignal(){return setupSignal;},
    advance(ms){const end=now+ms;for(;;){const due=[...alarms].filter(([,a])=>a.at<=end).sort((a,b)=>a[1].at-b[1].at||a[0]-b[0])[0];if(!due)break;
      alarms.delete(due[0]);now=due[1].at;due[1].callback();}now=end;},
    jump(ms){now+=ms;},now:()=>now};
}
const turn=()=>new Promise(resolve=>setImmediate(resolve));
// Break: the old three-minute completion cap refuses a still-pending authorized CLI.
test('completion can succeed after three minutes within its ten-minute cap',async()=>{
  const f=timedFixture(),opening=quietOpen(f);opening.catch(()=>{});await turn();
  assert.equal(f.processes.length,2);assert.equal(f.processes[0].opts.timeout,0);assert.equal(f.processes[1].opts.timeout,0);
  f.advance(180001);await turn();assert.equal(f.processes[1].opts.signal.aborted,false);
  f.processes[1].callback(null,'','');f.processes[1].child.emit('close',0);
  const owned=await opening;assert.equal(await owned.close({deadline:f.now()+120000}),true);
  assert.equal(f.alarms.size,0);assert.equal(getEventListeners(f.setupSignal,'abort').length,0);
  for(const p of f.processes){assert.equal(p.child.listenerCount('close'),0);assert.equal(p.child.listenerCount('error'),0);}
});
test('only exact phase-specific login descriptor caps are admitted',async()=>{
  for(const [complete,cap] of [[false,600000],[false,179999],[true,180000],[true,600001]]){
    const f=fixture(),bad=structuredClone(f.descriptor(complete));bad.options.timeout=cap;
    f.cli[complete?'completeLoginInvocation':'loginInvocation']=async()=>frozen(bad);
    await assert.rejects(quietOpen(f),{message:`stripe_sandbox_acceptance_${complete?'LOGIN_COMPLETE':'LOGIN'}_PENDING`});
    assert.equal(f.commands.length,complete?1:0);assert.equal(f.events.includes('listen'),false);
  }
});
// Break: native timeout/error shape or elapsed time cannot establish executor provenance.
for(const phase of ['LOGIN','LOGIN_COMPLETE'])test(`executor-owned ${phase} cap proves timeout only after callback and close`,async()=>{
  const f=timedFixture(),controller=new AbortController();f.setup.signal=controller.signal;
  if(phase==='LOGIN')f.deps.execFile=(file,args,opts,callback)=>{const child=new EventEmitter();f.processes.push({child,opts,callback});return child;};
  let settled=false;const opening=quietOpen(f).finally(()=>{settled=true;});opening.catch(()=>{});await turn();
  const p=f.processes.at(-1);f.advance(phase==='LOGIN'?180000:600000);await turn();
  assert.equal(p.opts.signal.aborted,true);assert.equal(controller.signal.aborted,false);assert.equal(settled,false);
  p.callback({code:'ETIMEDOUT',killed:true},'private-stdout','private-stderr');await turn();
  assert.equal(settled,false);assert.equal(f.events.includes('stop-services'),false);
  p.child.emit('close',null,'SIGTERM');await assert.rejects(opening,{message:`stripe_sandbox_acceptance_${phase}_PENDING`});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic(phase,'PROCESS_TIMEOUT')]);
  assert.equal(f.alarms.size,0);assert.equal(getEventListeners(controller.signal,'abort').length,0);assert.equal(getEventListeners(f.setupSignal,'abort').length,0);
  assert.equal(p.child.listenerCount('close'),0);assert.equal(p.child.listenerCount('error'),0);
  const wire=f.output.join('');p.callback(null,'private-late','private-late');p.child.emit('close',0);f.advance(2000000);await turn();assert.equal(f.output.join(''),wire);
  const reported=await reportFrames(f.output.flatMap(frame=>[frame.slice(0,19),Buffer.from(frame.slice(19))]),true,true);
  assert.ok(reported.lines.includes(`sandbox_diagnostic phase=${phase} code=PROCESS_TIMEOUT exit=none cleanup=confirmed\n`));
  assert.equal(reported.lines.join('').includes('private-'),false);
});
for(const budget of [90000,600000])test(`completion local wait clips to original remaining ${budget}ms deadline`,async()=>{
  const f=timedFixture(budget),opening=quietOpen(f);opening.catch(()=>{});await turn();
  const p=f.processes.at(-1);assert.equal(f.processes.length,2);
  f.advance(budget);assert.equal(p.opts.signal.aborted,true);
  p.callback(null,'','');p.child.emit('close',null,'SIGTERM');await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_COMPLETE_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN_COMPLETE','DEADLINE_REACHED')]);assert.equal(f.alarms.size,0);
});
test('original abort wins a coincident local cap and acknowledgement never starts later phases',async()=>{
  const f=timedFixture(),controller=new AbortController();f.setup.signal=controller.signal;
  const opening=quietOpen(f);opening.catch(()=>{});await turn();const p=f.processes.at(-1);
  f.jump(600000);controller.abort();f.advance(0);p.child.emit('close',null,'SIGTERM');p.callback({timedOut:true},'private-out','private-err');
  await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_COMPLETE_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN_COMPLETE','ABORTED')]);assert.equal(f.events.includes('listen'),false);assert.equal(f.alarms.size,0);
});
for(const boundary of ['deadline','abort'])test(`original ${boundary} during local-timeout acknowledgement grace retains precedence`,async()=>{
  const f=timedFixture(boundary==='deadline'?605000:1200000),controller=new AbortController();f.setup.signal=controller.signal;
  const opening=quietOpen(f);opening.catch(()=>{});await turn();const p=f.processes.at(-1);
  f.advance(600000);assert.equal(p.opts.signal.aborted,true);
  if(boundary==='deadline')f.advance(5000);else controller.abort();
  p.callback(null,'','');p.child.emit('close',13);await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_COMPLETE_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN_COMPLETE',boundary==='deadline'?'DEADLINE_REACHED':'ABORTED')]);assert.equal(f.alarms.size,0);
});
test('time consumed by initial login reduces the original remaining completion wait',async()=>{
  const f=timedFixture(90000),execute=f.deps.execFile;
  f.deps.execFile=(file,args,opts,callback)=>{const child=execute(file,args,opts,callback);if(args.at(-1)==='login')f.jump(30000);return child;};
  const opening=quietOpen(f);opening.catch(()=>{});await turn();const p=f.processes.at(-1);
  f.advance(59999);assert.equal(p.opts.signal.aborted,false);f.advance(1);assert.equal(p.opts.signal.aborted,true);
  p.callback(null,'','');p.child.emit('close',null,'SIGTERM');await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_COMPLETE_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN_COMPLETE','DEADLINE_REACHED')]);assert.equal(f.alarms.size,0);
});
test('synchronous opaque spawn failure disposes cap timers and original abort listeners',async()=>{
  const f=timedFixture(),controller=new AbortController();f.setup.signal=controller.signal;let reads=0;
  f.deps.execFile=()=>{throw new Proxy({},{get(){reads++;throw null;}});};
  await assert.rejects(quietOpen(f),{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN','PROCESS_FAILED')]);assert.equal(reads,0);assert.equal(f.alarms.size,0);
  assert.equal(getEventListeners(controller.signal,'abort').length,0);assert.equal(getEventListeners(f.setupSignal,'abort').length,0);
});
test('foreign timeout-shaped callback and elapsed-clock guesses stay opaque',async()=>{
  for(const late of [false,true]){
    const f=timedFixture(),opening=quietOpen(f);opening.catch(()=>{});await turn();const p=f.processes.at(-1);let reads=0;
    const foreign=new Proxy({code:'ETIMEDOUT',timedOut:true,killed:true},{get(){reads++;throw null;}});
    if(late)f.jump(600001);
    p.callback(foreign,'private-out','private-err');p.child.emit('close',0);
    await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_COMPLETE_PENDING'});
    assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN_COMPLETE','PROCESS_FAILED')]);assert.equal(reads,0);assert.equal(f.alarms.size,0);
  }
});
for(const proof of ['callback','close','error','timeout'])test(`missing process acknowledgement after ${proof} is bounded and cleanup remains unconfirmed`,async()=>{
  const f=timedFixture(),opening=quietOpen(f);opening.catch(()=>{});await turn();const p=f.processes.at(-1);
  let abortError;
  p.opts.signal.addEventListener('abort',()=>{try{p.child.emit('error',new Error('private-abort-event'));}catch(error){abortError=error;}},{once:true});
  if(proof==='callback')p.callback(null,'','');
  if(proof==='close')p.child.emit('close',0);
  if(proof==='error')p.child.emit('error',new Proxy({},{get(){throw null;}}));
  if(proof==='timeout')f.advance(600000);
  f.advance(10000);await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_COMPLETE_PENDING_CLEANUP_UNCONFIRMED'});
  assert.equal(p.opts.signal.aborted,true);assert.equal(f.events.includes('listen'),false);
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN_COMPLETE',proof==='timeout'?'PROCESS_TIMEOUT':'PROCESS_FAILED',null,false)]);
  assert.equal(f.alarms.size,0);assert.equal(getEventListeners(f.setupSignal,'abort').length,0);
  assert.equal(abortError,undefined);
  if(proof!=='close')assert.doesNotThrow(()=>p.child.emit('error',new Error('private-late-error')));
  const wire=f.output.join('');p.callback(null,'','');p.child.emit('close',0);f.advance(2000000);await turn();assert.equal(f.output.join(''),wire);
  assert.equal(p.child.listenerCount('close'),0);assert.equal(p.child.listenerCount('error'),0);
});
test('close before callback retains ownership and clears its acknowledgement grace on success',async()=>{
  const f=timedFixture(),opening=quietOpen(f);opening.catch(()=>{});await turn();const p=f.processes.at(-1);
  p.child.emit('close',0);await turn();assert.equal(f.events.includes('listen'),false);
  f.advance(9999);p.callback(null,'','');const owned=await opening;assert.equal(await owned.close({deadline:f.now()+120000}),true);assert.equal(f.alarms.size,0);
});
test('actual close before the local cap prevents timeout relabeling while callback is missing',async()=>{
  const f=timedFixture(),opening=quietOpen(f);opening.catch(()=>{});await turn();const p=f.processes.at(-1);
  f.advance(599999);p.child.emit('close',null,'SIGTERM');f.advance(10000);
  await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_COMPLETE_PENDING_CLEANUP_UNCONFIRMED'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN_COMPLETE','PROCESS_SIGNALLED',null,false)]);assert.equal(f.alarms.size,0);
});
test('timeout formatter permits only the flat null-exit envelope and reporter refuses forgeries',async()=>{
  const base=expectedDiagnostic('LOGIN_COMPLETE','PROCESS_TIMEOUT');
  assert.equal(formatSandboxDiagnostic(base),'sandbox_diagnostic phase=LOGIN_COMPLETE code=PROCESS_TIMEOUT exit=none cleanup=confirmed\n');
  let reads=0;const getter={...base};Object.defineProperty(getter,'exitCode',{get(){reads++;throw null;}});
  for(const bad of [{...base,exitCode:1},{...base,browserCase:'success',browserStep:'NAVIGATE'},{...base,stdout:'private-out'},{...base,error:'private-error'},getter])assert.equal(formatSandboxDiagnostic(bad),null);
  assert.equal(reads,0);
  for(const bad of [{...base,exitCode:1},{...base,browserCase:'success',browserStep:'NAVIGATE'},{...base,stderr:'private-err'}]){
    const result=await reportFrames([JSON.stringify(bad)+'\n'],true,true);
    assert.deepEqual(result.result,{status:'failed'});assert.ok(result.lines.includes('sandbox_diagnostic_failed_manual_check\n'));assert.equal(result.lines.join('').includes('private-'),false);
  }
});
// Break: erasing a real browser failure in either catch layer loses the only safe substep.
test('real card navigation failure survives driver owner parent and split reporter transport',async()=>{
  const {runSandboxCard}=await import('../examples/demo-site/tests/support/stripe-sandbox-fixtures.ts');
  const {withOwnedCardContexts}=await import('../examples/demo-site/tests/support/stripe-sandbox-acceptance.ts');
  const f=fixture(),events=[],contexts=[];
  const context={addInitScript:async()=>events.push('install'),newPage:async()=>({goto:async()=>{events.push('navigate');throw new Error('private-navigation');}}),
    close:async()=>{events.push('close-context');contexts.splice(0);}};
  const browser={contexts:()=>contexts,newContext:async()=>{contexts.push(context);return context;}};
  const cardFixture={beginBrowser:async()=>true,browserSession:async()=>({}),identity:{endpoints:{demo:'http://127.0.0.1:14173',backend:'http://127.0.0.1:18080'},site:{key:'synthetic'},price:{key:'synthetic'}},
    markPay:forbidden,confirm:forbidden,close:async()=>{events.push('close-fixture');}};
  f.deps.openStripeSandboxFixture=async()=>cardFixture;
  f.journeys.cards=async actual=>withOwnedCardContexts(browser,limits(),()=>runSandboxCard(browser,actual,'success'));
  const owned=await quietOpen(f);
  await assert.rejects(owned.run(f.journeys,limits()),{message:'stripe_sandbox_acceptance_CARDS_PENDING_CLEANUP_UNCONFIRMED'});
  const result=await reportFrames(f.output.flatMap(frame=>[frame.slice(0,17),Buffer.from(frame.slice(17))]));
  assert.ok(result.lines.includes('sandbox_diagnostic phase=CARDS code=OPERATION_FAILED exit=none cleanup=unconfirmed browser_case=success browser_step=NAVIGATE\n'));
  assert.deepEqual(events,['install','navigate','close-context','close-fixture']);
  assert.equal(f.events.includes('refunds'),false);assert.equal(result.lines.join('').includes('private-'),false);
});
async function reportFrames(frames,owned=true,exercisePrivateChannels=false){
  const {default:Reporter}=await import('../examples/demo-site/tests/support/stripe-sandbox-reporter.ts');
  const old=process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;
  if(owned)process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN='true';else delete process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;
  const reporter=new Reporter(),lines=[],write=captureOutput(lines);
  try {
    for(const frame of frames)reporter.onStdOut(frame);
    if(exercisePrivateChannels){
      const hostile=new Proxy({},{get(){throw new Error('private-getter');}});
      reporter.onStdErr('private-stderr');reporter.onError(hostile);
      reporter.onTestEnd(hostile,{status:'failed',error:hostile,errors:[hostile],attachments:[hostile],stdout:['private-out'],stderr:['private-err']});
    }
    return {lines,result:await reporter.onEnd({status:'passed'})};
  }finally{write.mock.restore();if(old===undefined)delete process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;else process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN=old;}
}

const browserSteps=['BEGIN_BROWSER','NEW_CONTEXT','CHECKOUT_SESSION','INSTALL_SESSION','NEW_PAGE','NAVIGATE','RESUME','PAYMENT_FORM_READY','CARD_FRAME','CARD_NUMBER_READY','CARD_NUMBER','CARD_EXPIRY','CARD_CVC','POSTAL_CHECK','POSTAL_FILL','MARK_PAY','PAY','MARK_RETRY','RETRY_PAYMENT_FORM_READY','RETRY_CARD_FRAME','RETRY_CARD_NUMBER_READY','RETRY_CARD_NUMBER','RETRY_CARD_EXPIRY','RETRY_CARD_CVC','RETRY_POSTAL_CHECK','RETRY_POSTAL_FILL','RETRY_PAY','SCA_COMPLETE','SERVER_CONFIRM','CLOSE_CONTEXT'];
function syntheticCard(failAt,closeFails=false,formObservation){
  const events=[],contexts=[];let retry=false;
  const operation=async(step,args)=>{events.push({step,args});if(step===failAt||step==='CLOSE_CONTEXT'&&closeFails)throw new Proxy({},{get(){throw new Error('private-must-not-read');}});};
  const frame={locator:selector=>({waitFor:async(...args)=>operation((retry?'RETRY_':'')+'CARD_NUMBER_READY',args),fill:async(...args)=>operation((retry?'RETRY_':'')+({'input[name="number"]':'CARD_NUMBER','input[name="expiry"]':'CARD_EXPIRY','input[name="cvc"]':'CARD_CVC','input[name="postalCode"]':'POSTAL_FILL'})[selector],args),
    isVisible:async()=>{await operation((retry?'RETRY_':'')+'POSTAL_CHECK',[]);return true;}})};
  const widget={first(){return this;},evaluate:async(body,arg,options)=>{assert.equal(typeof body,'function');assert.equal(arg,undefined);assert.deepEqual(options,{timeout:1000});if(formObservation===undefined)throw null;return formObservation;},locator:selector=>({waitFor:async(...args)=>operation((retry?'RETRY_':'')+(selector.startsWith('button')?'PAYMENT_FORM_READY':'CARD_FRAME'),args)}),frameLocator:()=>frame,getByRole:(_role,options)=>({click:async(...args)=>operation(options.name==='Pay'?(retry?'RETRY_PAY':'PAY'):'RESUME',args)})};
  const challenge={frameLocator(){return this;},getByRole:()=>({click:async(...args)=>operation('SCA_COMPLETE',args)})};
  const page={goto:async(...args)=>operation('NAVIGATE',args),locator:()=>widget,frameLocator:()=>challenge};
  const context={addInitScript:async(...args)=>operation('INSTALL_SESSION',args),newPage:async()=>{await operation('NEW_PAGE',[]);return page;},close:async()=>{try{await operation('CLOSE_CONTEXT',[]);}finally{contexts.splice(0);}}};
  const browser={contexts:()=>contexts,newContext:async(...args)=>{await operation('NEW_CONTEXT',args);contexts.push(context);return context;}};
  const fixture={identity:{endpoints:{demo:'http://127.0.0.1:14173',backend:'http://127.0.0.1:18080'},site:{key:'synthetic-site'},price:{key:'synthetic-price'}},
    beginBrowser:async(...args)=>{await operation('BEGIN_BROWSER',args);return true;},browserSession:async(...args)=>{await operation('CHECKOUT_SESSION',args);return {synthetic:'session'};},
    markPay:async(...args)=>{if(args[1])retry=true;await operation(retry?'MARK_RETRY':'MARK_PAY',args);},confirm:async(...args)=>operation('SERVER_CONFIRM',args),close:async()=>events.push({step:'FIXTURE_CLOSE',args:[]})};
  return {browser,fixture,events};
}
// Break: a missing/mislabelled boundary, reordered write or overwritten primary failure changes emitted evidence.
test('failed form observation survives the real driver owner and split reporter with no later authority',async()=>{
  const {runSandboxCard}=await import('../examples/demo-site/tests/support/stripe-sandbox-fixtures.ts');
  const {withOwnedCardContexts}=await import('../examples/demo-site/tests/support/stripe-sandbox-acceptance.ts');
  const observation={widgetState:'checking',elementState:'EMPTY',payState:'ABSENT'};
  for(const [phase,name,step] of [['CARDS','success','PAYMENT_FORM_READY'],['CARDS','decline','RETRY_PAYMENT_FORM_READY'],['MISSED','success','PAYMENT_FORM_READY']]){
    const f=fixture(),card=syntheticCard(step,true,observation);card.fixture.close=async()=>{throw null;};
    if(phase==='CARDS')f.deps.openStripeSandboxFixture=async()=>card.fixture;else f.extras.missedFixture=async()=>card.fixture;
    f.journeys[phase==='CARDS'?'cards':'missed']=async actual=>withOwnedCardContexts(card.browser,limits(),()=>runSandboxCard(card.browser,actual,name));
    const owned=await quietOpen(f);await assert.rejects(owned.run(f.journeys,limits()));
    assert.deepEqual(diagnostics(f.output),[{...expectedDiagnostic(phase,'OPERATION_FAILED',null,false),browserCase:name,browserStep:step,...observation}]);
    assert.equal(owned.resources().accepted,false);assert.equal(card.events.some(e=>e.step==='SERVER_CONFIRM'),false);
    if(phase==='CARDS')assert.equal(f.events.includes('refunds'),false);
    const result=await reportFrames(f.output.map(line=>[line.slice(0,21),Buffer.from(line.slice(21))]).flat());
    assert.deepEqual(result.result,{status:'failed'});
    assert.ok(result.lines.includes(`sandbox_diagnostic phase=${phase} code=OPERATION_FAILED exit=none cleanup=unconfirmed browser_case=${name} browser_step=${step} widget_state=checking element_state=EMPTY pay_state=ABSENT\n`));
  }
});
// Break: accepting arbitrary DOM/error strings or partial triples can disclose capabilities through the safe reporter.
test('form formatter and reporter strictly allowlist the complete readiness observation',async()=>{
  const base={...expectedDiagnostic('CARDS','OPERATION_FAILED',null,false),browserCase:'success',browserStep:'PAYMENT_FORM_READY',widgetState:'loading',elementState:'FRAME',payState:'ABSENT'};
  assert.equal(formatSandboxDiagnostic(base),'sandbox_diagnostic phase=CARDS code=OPERATION_FAILED exit=none cleanup=unconfirmed browser_case=success browser_step=PAYMENT_FORM_READY widget_state=loading element_state=FRAME pay_state=ABSENT\n');
  let reads=0;
  const invalid=[{...base,widgetState:'private-sentinel'},{...base,elementState:'private-sentinel'},{...base,payState:'private-sentinel'},
    {...base,widgetState:new String('loading')},{...base,widgetState:[]},{...base,payState:true},{...base,private:'private-sentinel'},
    {...base,phase:'DONATIONS'},{...base,browserStep:'CARD_NUMBER'},{...base,phase:'MISSED',browserCase:'decline'},
    {...base,code:'ABORTED'},{...base,code:'PROCESS_EXIT_NONZERO',exitCode:2},...['widgetState','elementState','payState'].map(key=>{const v={...base};delete v[key];return v;}),
    {...base,[Symbol('private')]:true}];
  for(const key of ['widgetState','elementState','payState'])invalid.push(Object.defineProperty({...base},key,{get(){reads++;throw null;}}));
  for(const value of invalid)assert.equal(formatSandboxDiagnostic(value),null);
  assert.equal(reads,0);
  // JSON normalizes boxed strings into valid primitives; direct formatter rejection above is the applicable boundary for that case.
  for(const value of invalid.filter(v=>!Reflect.ownKeys(v).some(k=>typeof k==='symbol'||!Object.hasOwn(Object.getOwnPropertyDescriptor(v,k),'value'))&&![v.widgetState,v.elementState,v.payState].some(field=>field instanceof String))){
    const result=await reportFrames([JSON.stringify(value)+'\n']);assert.deepEqual(result.result,{status:'failed'});
    assert.ok(result.lines.includes('sandbox_diagnostic_failed_manual_check\n'));assert.equal(result.lines.join('').includes('private-'),false);
  }
  const wire=JSON.stringify(base);
  for(const [key,escaped] of [['widgetState','widget\\u0053tate'],['elementState','element\\u0053tate'],['payState','pay\\u0053tate']]){
    for(const duplicate of [key,escaped]){const result=await reportFrames([wire.slice(0,-1)+`,"${duplicate}":"${base[key]}"}\n`]);
      assert.ok(result.lines.includes('sandbox_diagnostic_failed_manual_check\n'));assert.equal(result.lines.some(v=>v.startsWith('sandbox_diagnostic phase=')),false);}
  }
  for(const malformed of [wire,JSON.stringify({...base,widgetState:'private-'+'x'.repeat(4096)})+'\n']){
    const result=await reportFrames([malformed.slice(0,19),Buffer.from(malformed.slice(19))]);
    assert.ok(result.lines.includes('sandbox_diagnostic_failed_manual_check\n'));assert.equal(result.lines.join('').includes('private-'),false);
  }
});
// Break: optional diagnostic failure, invalid data or a late result must never replace the trusted original failure.
test('unavailable malformed or hostile form observations retain the primary pair without reading error or data getters',async()=>{
  const {runSandboxBrowserStep}=await import('./stripe-sandbox-acceptance.mjs');let reads=0;
  const hostile=new Proxy({},{get(){reads++;throw null;}}),good={widgetState:'loading',elementState:'EMPTY',payState:'ABSENT'};
  const bad=[null,[],{...good,widgetState:'private-sentinel'},{...good,extra:'private-sentinel'},Object.defineProperty({...good},'payState',{get(){reads++;throw null;}})];
  const observers=[...bad.map(value=>async()=>value),async()=>{throw hostile;}];
  for(const observer of observers){
    const f=fixture();f.journeys.cards=async()=>runSandboxBrowserStep('success','PAYMENT_FORM_READY',async()=>{throw hostile;},observer);
    const owned=await quietOpen(f);await assert.rejects(owned.run(f.journeys,limits()));
    assert.deepEqual(diagnostics(f.output),[{...expectedDiagnostic('CARDS','OPERATION_FAILED',null,false),browserCase:'success',browserStep:'PAYMENT_FORM_READY'}]);
    assert.equal(f.events.includes('refunds'),false);assert.ok(f.events.includes('stop-services'));
  }
  assert.equal(reads,0);
});
test('form observation timeout is bounded and a late result cannot revise the original failed report',async()=>{
  const {runSandboxBrowserStep}=await import('./stripe-sandbox-acceptance.mjs');
  const nativeSet=globalThis.setTimeout,nativeClear=globalThis.clearTimeout,alarms=[];
  const timer=mock.method(globalThis,'setTimeout',(body,delay,...args)=>{
    const handle=nativeSet(()=>{},1000000);if(delay!==1000){nativeClear(handle);return nativeSet(body,delay,...args);}
    alarms.push({fire:()=>body(...args),handle});return handle;
  });
  let release,reads=0;const hostile=new Proxy({},{get(){reads++;throw null;}}),pending=new Promise(resolve=>{release=resolve;});
  const f=fixture();f.journeys.cards=async()=>runSandboxBrowserStep('success','PAYMENT_FORM_READY',async()=>{throw hostile;},()=>pending);
  try{
    const owned=await quietOpen(f),running=owned.run(f.journeys,limits());running.catch(()=>{});
    await new Promise(resolve=>setImmediate(resolve));assert.equal(alarms.length,1);assert.equal(diagnostics(f.output).length,0);
    alarms[0].fire();await assert.rejects(running);const before=[...f.output];
    release({widgetState:'ready',elementState:'FRAME',payState:'ENABLED'});await new Promise(resolve=>setImmediate(resolve));
    assert.deepEqual(f.output,before);assert.deepEqual(diagnostics(f.output),[{...expectedDiagnostic('CARDS','OPERATION_FAILED',null,false),browserCase:'success',browserStep:'PAYMENT_FORM_READY'}]);
    assert.equal(f.events.filter(x=>x==='stop-services').length,1);assert.equal(reads,0);assert.equal(f.events.includes('refunds'),false);
  }finally{timer.mock.restore();for(const alarm of alarms)nativeClear(alarm.handle);release(null);}
});
test('observers run only on a new failed readiness boundary and nested primary evidence cannot be replaced',async()=>{
  const {runSandboxBrowserStep}=await import('./stripe-sandbox-acceptance.mjs');let calls=0,token;
  const observe=async()=>{calls++;return {widgetState:'ready',elementState:'FRAME',payState:'ENABLED'};};
  assert.equal(await runSandboxBrowserStep('success','PAYMENT_FORM_READY',async()=>17,observe),17);
  try{await runSandboxBrowserStep('success','CARD_NUMBER',async()=>{throw null;},observe);}catch(error){token=error;}
  const f=fixture();f.journeys.cards=async()=>runSandboxBrowserStep('decline','RETRY_PAYMENT_FORM_READY',async()=>{throw token;},observe);
  const owned=await quietOpen(f);await assert.rejects(owned.run(f.journeys,limits()));
  assert.equal(calls,0);assert.deepEqual(diagnostics(f.output),[{...expectedDiagnostic('CARDS','OPERATION_FAILED',null,false),browserCase:'success',browserStep:'CARD_NUMBER'}]);
});
for(const boundary of ['deadline','abort'])test(`original ${boundary} during form observation retains precedence over UI evidence`,async()=>{
  const {runSandboxBrowserStep}=await import('./stripe-sandbox-acceptance.mjs');
  const f=fixture(),controller=new AbortController();let now=Date.now();f.deps.now=()=>now;
  f.journeys.cards=async()=>runSandboxBrowserStep('success','PAYMENT_FORM_READY',async()=>{throw null;},async()=>{
    if(boundary==='deadline')now=f.setup.deadline;else controller.abort();
    return {widgetState:'loading',elementState:'FRAME',payState:'ABSENT'};
  });
  const owned=await quietOpen(f);await assert.rejects(owned.run(f.journeys,{deadline:f.setup.deadline,signal:controller.signal}));
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('CARDS',boundary==='deadline'?'DEADLINE_REACHED':'ABORTED',null,false)]);
  assert.equal(f.events.includes('refunds'),false);
});
test('every real browser boundary emits its exact case step and stops later authority',async()=>{
  const {runSandboxCard}=await import('../examples/demo-site/tests/support/stripe-sandbox-fixtures.ts');
  const {withOwnedCardContexts}=await import('../examples/demo-site/tests/support/stripe-sandbox-acceptance.ts');
  for(const step of browserSteps){
    const name=step.includes('RETRY')?'decline':step==='SCA_COMPLETE'?'sca':'success';
    const f=fixture(),card=syntheticCard(step);f.deps.openStripeSandboxFixture=async()=>card.fixture;
    f.journeys.cards=async actual=>withOwnedCardContexts(card.browser,limits(),()=>runSandboxCard(card.browser,actual,name));
    const owned=await quietOpen(f);
    await assert.rejects(owned.run(f.journeys,limits()),{message:'stripe_sandbox_acceptance_CARDS_PENDING_CLEANUP_UNCONFIRMED'});
    assert.deepEqual(diagnostics(f.output),[{...expectedDiagnostic('CARDS','OPERATION_FAILED',null,false),browserCase:name,browserStep:step}],step);
    const steps=card.events.map(event=>event.step),failure=steps.indexOf(step);
    assert.ok(failure>=0);assert.ok(steps.slice(failure+1).every(value=>['CLOSE_CONTEXT','FIXTURE_CLOSE'].includes(value)),step);
    if(!['BEGIN_BROWSER','NEW_CONTEXT'].includes(step))assert.equal(steps.filter(value=>value==='CLOSE_CONTEXT').length,1);
    assert.equal(steps.at(-1),'FIXTURE_CLOSE');assert.equal(f.events.includes('refunds'),false);
    const result=await reportFrames(f.output);assert.deepEqual(result.result,{status:'failed'});
    assert.ok(result.lines.includes(`sandbox_diagnostic phase=CARDS code=OPERATION_FAILED exit=none cleanup=unconfirmed browser_case=${name} browser_step=${step}\n`));
  }
});
test('real primary browser failure survives rejecting context cleanup and fixture cleanup',async()=>{
  const {runSandboxCard}=await import('../examples/demo-site/tests/support/stripe-sandbox-fixtures.ts');
  const {withOwnedCardContexts}=await import('../examples/demo-site/tests/support/stripe-sandbox-acceptance.ts');
  const f=fixture(),card=syntheticCard('CARD_NUMBER',true);card.fixture.close=async()=>{throw null;};
  f.deps.openStripeSandboxFixture=async()=>card.fixture;f.journeys.cards=async actual=>withOwnedCardContexts(card.browser,limits(),()=>runSandboxCard(card.browser,actual,'success'));
  const owned=await quietOpen(f);await assert.rejects(owned.run(f.journeys,limits()));
  assert.deepEqual(diagnostics(f.output),[{...expectedDiagnostic('CARDS','OPERATION_FAILED',null,false),browserCase:'success',browserStep:'CARD_NUMBER'}]);
  assert.ok(f.events.includes('stop-services'));assert.equal(owned.resources().accepted,false);
});
test('shared browser provenance rejects hostile or invalid input without property reads and keeps nested first token',async()=>{
  const {runSandboxBrowserStep,rethrowSandboxBrowserFailure}=await import('./stripe-sandbox-acceptance.mjs');
  let reads=0,calls=0;const hostile=new Proxy({},{get(){reads++;throw null;},ownKeys(){reads++;throw null;}});
  for(const value of [hostile,null,undefined,1,'private-sentinel',{browserCase:'success',browserStep:'NAVIGATE'},new Error('private-sentinel')])
    assert.throws(()=>rethrowSandboxBrowserFailure(value),{message:'stripe_sandbox_MANUAL_CHECK_BROWSER'});
  for(const [name,step] of [['other','NAVIGATE'],['success','other'],[hostile,'NAVIGATE'],['success',hostile]])
    await assert.rejects(runSandboxBrowserStep(name,step,async()=>{calls++;}));
  assert.equal(calls,0);assert.equal(reads,0);
  assert.equal(await runSandboxBrowserStep('success','NAVIGATE',async()=>{calls++;return 17;}),17);assert.equal(calls,1);
  let token;try{await runSandboxBrowserStep('success','CARD_NUMBER',async()=>{throw hostile;});}catch(error){token=error;}
  assert.throws(()=>rethrowSandboxBrowserFailure(token),error=>error===token);
  await assert.rejects(runSandboxBrowserStep('decline','PAY',async()=>{throw token;}),error=>error===token);assert.equal(reads,0);
});
test('MISSED only preserves success browser evidence and deadlines or aborts retain precedence',async()=>{
  const {runSandboxCard}=await import('../examples/demo-site/tests/support/stripe-sandbox-fixtures.ts');
  const {withOwnedCardContexts}=await import('../examples/demo-site/tests/support/stripe-sandbox-acceptance.ts');
  for(const name of ['success','decline','sca']){
    const f=fixture(),card=syntheticCard('RESUME');f.extras.missedFixture=async()=>card.fixture;
    f.journeys.missed=async actual=>withOwnedCardContexts(card.browser,limits(),()=>runSandboxCard(card.browser,actual,name));
    const owned=await quietOpen(f);await assert.rejects(owned.run(f.journeys,limits()));
    assert.deepEqual(diagnostics(f.output),[{...expectedDiagnostic('MISSED','OPERATION_FAILED',null,false),...(name==='success'?{browserCase:'success',browserStep:'RESUME'}:{})}]);
    assert.equal(owned.resources().accepted,false);
    const result=await reportFrames(f.output);assert.deepEqual(result.result,{status:'failed'});
  }
  for(const boundary of ['deadline','abort']){
    const f=fixture(),card=syntheticCard('NAVIGATE'),controller=new AbortController();let now=Date.now();f.deps.now=()=>now;
    const original=card.fixture.browserSession;
    card.fixture.browserSession=async(...args)=>{if(boundary==='deadline')now=f.setup.deadline;else controller.abort();return original(...args);};
    f.deps.openStripeSandboxFixture=async()=>card.fixture;
    f.journeys.cards=async actual=>withOwnedCardContexts(card.browser,limits(),()=>runSandboxCard(card.browser,actual,'success'));
    const owned=await quietOpen(f);await assert.rejects(owned.run(f.journeys,{deadline:f.setup.deadline,signal:controller.signal}));
    assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('CARDS',boundary==='deadline'?'DEADLINE_REACHED':'ABORTED',null,false)]);
    assert.ok(card.events.some(event=>event.step==='CLOSE_CONTEXT'));assert.equal(f.events.includes('refunds'),false);
  }
});
test('browser evidence cannot label unrelated parent phases',async()=>{
  const {runSandboxBrowserStep}=await import('./stripe-sandbox-acceptance.mjs');
  const f=fixture();f.journeys.donations=async()=>runSandboxBrowserStep('success','NAVIGATE',async()=>{throw null;});
  const owned=await quietOpen(f);await assert.rejects(owned.run(f.journeys,limits()));
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('DONATIONS','OPERATION_FAILED',null,false)]);
});
test('extended formatter and reporter accept only the bounded paired browser contract',async()=>{
  const base={...expectedDiagnostic('CARDS','OPERATION_FAILED',null,false),browserCase:'success',browserStep:'NAVIGATE'};
  for(const name of ['success','decline','sca'])for(const step of browserSteps){
    assert.equal(formatSandboxDiagnostic({...base,browserCase:name,browserStep:step}),`sandbox_diagnostic phase=CARDS code=OPERATION_FAILED exit=none cleanup=unconfirmed browser_case=${name} browser_step=${step}\n`);
  }
  let reads=0;
  const invalid=[
    {...expectedDiagnostic('CARDS'),browserCase:'success'},{...expectedDiagnostic('CARDS'),browserStep:'NAVIGATE'},
    {...base,browserCase:'unknown'},{...base,browserStep:'unknown'},{...base,browserCase:'success\nprivate-sentinel'},
    {...base,browserStep:'NAVIGATE\u0000private-sentinel'},{...base,browserCase:1},{...base,browserStep:true},
    {...base,phase:'LOGIN'},{...base,phase:'MISSED',browserCase:'decline'},{...base,phase:'MISSED',browserCase:'sca'},
    ...['ABORTED','DEADLINE_REACHED','PROCESS_FAILED','PROCESS_SIGNALLED','PROCESS_EXIT_NONZERO'].map(code=>({...base,code,exitCode:code==='PROCESS_EXIT_NONZERO'?1:null})),
    {...base,exitCode:1},{...base,private:'private-sentinel'},
  ];
  for(const value of invalid){
    assert.equal(formatSandboxDiagnostic(value),null);
    const result=await reportFrames([JSON.stringify(value)+'\n']);
    assert.deepEqual(result.result,{status:'failed'});assert.ok(result.lines.includes('sandbox_diagnostic_failed_manual_check\n'));
    assert.equal(result.lines.join('').includes('private-'),false);
  }
  for(const key of ['browserCase','browserStep'])assert.equal(formatSandboxDiagnostic(Object.defineProperty({...base},key,{get(){reads++;throw null;}})),null);
  assert.equal(formatSandboxDiagnostic({...base,browserCase:new String('success')}),null);
  assert.equal(formatSandboxDiagnostic({...base,browserStep:new String('NAVIGATE')}),null);
  assert.equal(formatSandboxDiagnostic({...base,[Symbol('private')]:true}),null);assert.equal(reads,0);
  const wire=JSON.stringify(base),auth=JSON.stringify(authorization)+'\n';
  for(const [plain,escaped,value] of [['browserCase','browser\\u0043ase','success'],['browserStep','browser\\u0053tep','NAVIGATE'],['tag','t\\u0061g','stripe_sandbox_diagnostic'],['phase','ph\\u0061se','CARDS']]){
    for(const key of [plain,escaped]){
      const result=await reportFrames([auth,wire.slice(0,-1)+`,"${key}":"${value}"}\n`]);
      assert.deepEqual(result.result,{status:'failed'});assert.ok(result.lines.includes('sandbox_diagnostic_failed_manual_check\n'));
      assert.equal(result.lines.some(line=>line.startsWith('sandbox_diagnostic phase=')),false);
      assert.ok(result.lines.includes(formatSandboxAuthorization(authorization)));
    }
  }
  for(const malformed of [wire,wire.slice(0,-1)+',"private":"private-sentinel'+'x'.repeat(4096)+'"}\n']){
    const result=await reportFrames([malformed.slice(0,13),Buffer.from(malformed.slice(13))]);
    assert.deepEqual(result.result,{status:'failed'});assert.ok(result.lines.includes('sandbox_diagnostic_failed_manual_check\n'));assert.equal(result.lines.join('').includes('private-'),false);
  }
  for(const frames of [[wire+'\n',auth],[auth,wire.slice(0,23),Buffer.from(wire.slice(23)+'\n')]]){
    const result=await reportFrames(frames);assert.deepEqual(result.result,{status:'failed'});
    assert.ok(result.lines.includes(formatSandboxAuthorization(authorization)));
    assert.equal(result.lines.filter(line=>line.startsWith('sandbox_diagnostic phase=')).length,1);
  }
  const suppressed=await reportFrames([wire+'\n',auth],false);assert.deepEqual(suppressed.lines,['sandbox_result_passed cases=0 failures=0\n']);
});

// Break: trusting callback data instead of actual close/state invents unsafe process diagnoses.
test('owned process diagnostics classify close evidence and opaque callback or spawn failures',async()=>{
  let reads=0;
  const hostile=new Proxy({},{get(){reads++;throw new Error('private-getter');}});
  for(const sample of [
    {kind:'close',error:hostile,exit:7,signal:null,code:'PROCESS_EXIT_NONZERO',wantExit:7},
    {kind:'close',error:null,exit:255,signal:null,code:'PROCESS_EXIT_NONZERO',wantExit:255},
    {kind:'close',error:hostile,exit:0,signal:null,code:'PROCESS_FAILED',wantExit:null},
    {kind:'close',error:'private-error',exit:null,signal:null,code:'PROCESS_FAILED',wantExit:null},
    {kind:'close',error:false,exit:0,signal:null,code:'PROCESS_FAILED',wantExit:null},
    {kind:'close',error:null,exit:null,signal:'SIGTERM',code:'PROCESS_SIGNALLED',wantExit:null},
    {kind:'close',error:hostile,exit:256,signal:null,code:'PROCESS_FAILED',wantExit:null},
    {kind:'spawn',error:hostile,code:'PROCESS_FAILED',wantExit:null},
  ]){
    const f=fixture();
    f.deps.execFile=(file,args,opts,callback)=>{if(sample.kind==='spawn')throw sample.error;
      const child=new EventEmitter();queueMicrotask(()=>{callback(sample.error,'private-stdout','private-stderr');child.emit('close',sample.exit,sample.signal);});return child;};
    await assert.rejects(quietOpen(f),{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});
    assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN',sample.code,sample.wantExit)]);
    assert.equal(f.output.join('').includes('private-'),false);assert.equal(f.events.includes('complete-descriptor'),false);
    assert.equal(f.events.includes('start'),false);
  }
  assert.equal(reads,0);
});
test('failed process callback waits for close before diagnostic or cleanup and retains actual exit',async()=>{
  const f=fixture();let child,callbackDone=false,settled=false;
  f.deps.execFile=(file,args,opts,callback)=>{child=new EventEmitter();queueMicrotask(()=>{callbackDone=true;callback(new Error('private-error'),'private-out','private-err');});return child;};
  const opening=quietOpen(f).finally(()=>{settled=true;});opening.catch(()=>{});
  await new Promise(resolve=>setImmediate(resolve));assert.equal(callbackDone,true);assert.equal(settled,false);
  assert.deepEqual(f.output,[]);assert.equal(f.events.includes('stop-services'),false);assert.equal(f.events.includes('complete-descriptor'),false);
  child.emit('close',9);await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN','PROCESS_EXIT_NONZERO',9)]);
});
test('child error event alone cannot release ownership or become raw diagnostic data',async()=>{
  const f=fixture();let child,callback,reads=0;
  const hostile=new Proxy({},{get(){reads++;throw new Error('private-event-error');}});
  f.deps.execFile=(file,args,opts,done)=>{child=new EventEmitter();callback=done;return child;};
  const opening=quietOpen(f);opening.catch(()=>{});await new Promise(resolve=>setImmediate(resolve));
  child.emit('error',hostile);callback(null,json(),'');await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(f.output,[]);assert.equal(f.events.includes('stop-services'),false);assert.equal(f.events.includes('complete-descriptor'),false);
  child.emit('close',0);await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN','PROCESS_FAILED')]);assert.equal(reads,0);
});
test('owned abort before close waits for acknowledgement and records ABORTED without process text',async()=>{
  const f=fixture(),controller=new AbortController();f.setup.signal=controller.signal;let child,settled=false;
  f.deps.execFile=(file,args,opts,callback)=>{child=new EventEmitter();opts.signal.addEventListener('abort',()=>callback('private-abort','private-out','private-err'),{once:true});return child;};
  const opening=quietOpen(f).finally(()=>{settled=true;});opening.catch(()=>{});
  await new Promise(resolve=>setImmediate(resolve));controller.abort();await new Promise(resolve=>setImmediate(resolve));
  assert.equal(settled,false);assert.deepEqual(f.output,[]);assert.equal(f.events.includes('stop-services'),false);
  child.emit('close',null,'SIGTERM');await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN','ABORTED')]);assert.equal(f.output.join('').includes('SIGTERM'),false);
});
test('proven bounded nonzero close survives callback failure even when the owned context aborts',async()=>{
  const f=fixture(),controller=new AbortController();f.setup.signal=controller.signal;
  f.deps.execFile=(file,args,opts,callback)=>{const child=new EventEmitter();queueMicrotask(()=>{
    controller.abort();callback(new Error('private-abort'),'private-out','private-err');child.emit('close',5);
  });return child;};
  await assert.rejects(quietOpen(f),{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN','PROCESS_EXIT_NONZERO',5)]);
});
test('owned clock reaching the original deadline produces DEADLINE_REACHED',async()=>{
  const f=fixture();let now=Date.now();f.deps.now=()=>now;
  f.deps.execFile=(file,args,opts,callback)=>{const child=new EventEmitter();queueMicrotask(()=>{now=f.setup.deadline;callback(null,json(),'');child.emit('close',0);});return child;};
  await assert.rejects(quietOpen(f),{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});
  assert.deepEqual(diagnostics(f.output),[expectedDiagnostic('LOGIN','DEADLINE_REACHED')]);assert.equal(f.events.includes('complete-descriptor'),false);
});

// Break: traversing a dependency rejection lets it forge a diagnostic or execute private getters.
test('unknown dependencies remain opaque and first setup stage survives cleanup refusal',async()=>{
  let reads=0;const hostile=new Proxy({},{get(){reads++;throw new Error('private-getter');}});
  for(const phase of ['PREPARE','BINDING','LISTENER','START','PROVISION']){
    for(const error of [hostile,'private-rejection',{tag:'stripe_sandbox_diagnostic',phase:'LOGIN',code:'PROCESS_EXIT_NONZERO',exitCode:13,cleanupConfirmed:true}]){
      const f=fixture();
      const [target,method]=({PREPARE:[f.bootstrap,'prepare'],BINDING:[f.cli,'validateBinding'],LISTENER:[f.cli,'startListener'],START:[f.bootstrap,'start'],PROVISION:[f.provisioner,'provision']})[phase];
      target[method]=async()=>{throw error;};f.cli.stopListener=async()=>false;f.bootstrap.stop=async()=>{f.events.push('stop-services');return false;};
      await assert.rejects(quietOpen(f),{message:`stripe_sandbox_acceptance_${phase}_PENDING_CLEANUP_UNCONFIRMED`});
      assert.deepEqual(diagnostics(f.output),[expectedDiagnostic(phase,'OPERATION_FAILED',null,false)]);
      assert.equal(f.output.join('').includes('private-'),false);assert.ok(f.events.includes('stop-services'));
      assert.equal(f.events.includes('open-cards'),false);
    }
  }
  assert.equal(reads,0);
});
test('run failure and cleanup-only failure emit once and preserve original error API',async()=>{
  for(const kind of ['cards','cleanup','close']){
    const f=fixture(),owned=await quietOpen(f);f.cli.stopListener=async()=>false;
    if(kind==='cards')f.journeys.cards=async()=>{throw {code:'PROCESS_EXIT_NONZERO',exitCode:22,message:'private-error'};};
    const operation=()=>kind==='close'?owned.close(limits()):owned.run(f.journeys,limits());
    const message=kind==='cards'?'stripe_sandbox_acceptance_CARDS_PENDING_CLEANUP_UNCONFIRMED':'stripe_sandbox_acceptance_CLEANUP_UNCONFIRMED';
    await assert.rejects(operation(),{message});
    assert.deepEqual(diagnostics(f.output),[expectedDiagnostic(kind==='cards'?'CARDS':'CLEANUP','OPERATION_FAILED',null,false)]);
    if(kind==='cards')await assert.rejects(operation(),{message});else await assert.rejects(owned.close(limits()),{message});
    assert.equal(diagnostics(f.output).length,1);assert.equal(f.output.join('').includes('private-'),false);
    assert.equal(owned.resources().accepted,false);assert.equal(owned.resources().cleanup.services,true);
  }
});
test('diagnostic sink failure cannot replace setup failure or skip owned cleanup',async()=>{
  const f=fixture();f.bootstrap.prepare=async()=>false;let attempts=0;
  const sink=mock.method(process.stdout,'write',()=>{attempts++;throw new Error('private-sink');});
  try{await assert.rejects(openOwnedSandboxAcceptance(options(),f.setup,f.deps),{message:'stripe_sandbox_acceptance_PREPARE_PENDING'});}
  finally{sink.mock.restore();}
  assert.equal(attempts,1);assert.ok(f.events.includes('stop-services'));
});

// Break: coercion/accessors/extra fields could turn private payload into terminal output.
test('diagnostic formatter exactly allowlists primitives without invoking getters',()=>{
  assert.equal(formatSandboxDiagnostic(expectedDiagnostic('LOGIN_COMPLETE','PROCESS_EXIT_NONZERO',1)),
    'sandbox_diagnostic phase=LOGIN_COMPLETE code=PROCESS_EXIT_NONZERO exit=1 cleanup=confirmed\n');
  assert.equal(formatSandboxDiagnostic(expectedDiagnostic('CLEANUP','OPERATION_FAILED',null,false)),
    'sandbox_diagnostic phase=CLEANUP code=OPERATION_FAILED exit=none cleanup=unconfirmed\n');
  for(const phase of ['PREPARE','LOGIN','LOGIN_COMPLETE','BINDING','LISTENER','START','PROVISION','READY','CARDS','REFUNDS','DONATIONS','MISSED','CONFIRMED','CLEANUP'])
    assert.equal(formatSandboxDiagnostic(expectedDiagnostic(phase)),`sandbox_diagnostic phase=${phase} code=OPERATION_FAILED exit=none cleanup=confirmed\n`);
  for(const code of ['OPERATION_FAILED','PROCESS_SIGNALLED','PROCESS_FAILED','ABORTED','DEADLINE_REACHED'])
    assert.equal(formatSandboxDiagnostic(expectedDiagnostic('LOGIN',code)),`sandbox_diagnostic phase=LOGIN code=${code} exit=none cleanup=confirmed\n`);
  const base=expectedDiagnostic('LOGIN');let reads=0;
  for(const value of [null,[],true,'private',{},Object.create(base),{...base,private:'private-marker'},
    {...base,[Symbol('private')]:true},Object.defineProperty({...base},'hidden',{value:'private-marker'}),
    {...base,phase:'LOGIN\nprivate-marker'},{...base,phase:new String('LOGIN')},{...base,code:'UNKNOWN'},
    {...base,cleanupConfirmed:1},{...base,exitCode:0},{...base,exitCode:1},
    ...[0,256,-1,1.5,'1',null,NaN,Infinity].map(exitCode=>({...base,code:'PROCESS_EXIT_NONZERO',exitCode})),
    Object.defineProperty({...base},'code',{get(){reads++;throw new Error('private-getter');}}),
    new Proxy({},{ownKeys(){throw new Error('private-proxy');}})])assert.equal(formatSandboxDiagnostic(value),null);
  assert.equal(reads,0);
});

// Break: JSON key duplication or escaped transport tags can bypass terminal failure/refusal.
test('reporter refuses duplicate diagnostic fields',async()=>{
  const frame=JSON.stringify(expectedDiagnostic('LOGIN'));
  const duplicated=frame.slice(0,-1)+',"phase":"LOGIN_COMPLETE"}\n';
  const refused=await reportFrames([duplicated]);
  assert.ok(refused.lines.includes('sandbox_diagnostic_failed_manual_check\n'));
  assert.deepEqual(refused.result,{status:'failed'});
  assert.equal(refused.lines.some(line=>line.startsWith('sandbox_diagnostic phase=')),false);
});
test('reporter accepts a decoded exact diagnostic tag',async()=>{
  const frame=JSON.stringify(expectedDiagnostic('LOGIN'));
  const escaped=frame.replace('stripe_sandbox_diagnostic','stripe_sandbox_diag\\u006eostic')+'\n';
  const accepted=await reportFrames([escaped]);
  assert.ok(accepted.lines.includes('sandbox_diagnostic phase=LOGIN code=OPERATION_FAILED exit=none cleanup=confirmed\n'));
  assert.deepEqual(accepted.result,{status:'failed'});
});
test('reporter refuses diagnostic-shaped unknown tags and keeps refusal distinct from authorization',async()=>{
  for(const frame of [JSON.stringify({...expectedDiagnostic('LOGIN'),tag:'unknown'})+'\n',
    JSON.stringify({...expectedDiagnostic('LOGIN'),tag:undefined})+'\n',
    '{"tag":"stripe_sandbox_diagnostic","private":"stripe_sandbox_authorization private-marker"',
    '{"tag":"stripe_sandbox_diagnostic","private":"stripe_sandbox_authorization private-marker'+'x'.repeat(4096)+'"}\n']){
    const {lines,result}=await reportFrames([frame]);
    assert.deepEqual(result,{status:'failed'});assert.ok(lines.includes('sandbox_diagnostic_failed_manual_check\n'));
    assert.equal(lines.join('').includes('private-'),false);assert.equal(lines.includes('sandbox_authorization_failed_manual_check\n'),false);
  }
});
// Break: an erroneous authorization tag must not turn malformed diagnostics into a second handoff.
for(const order of ['before','after'])test(`reporter refuses diagnostic shape claiming authorization ${order} a valid handoff`,async()=>{
  const auth=JSON.stringify(authorization)+'\n';
  const malformed={...expectedDiagnostic('LOGIN','OPERATION_FAILED',null,false),tag:'stripe_sandbox_authorization'};
  for(const value of [malformed,{...malformed,private:'private-marker'}]){
    const diagnostic=JSON.stringify(value)+'\n';
    const {lines,result}=await reportFrames(order==='before'?[diagnostic,auth]:[auth,diagnostic]);
    assert.ok(lines.includes('sandbox_diagnostic_failed_manual_check\n'));
    assert.equal(lines.includes('sandbox_authorization_failed_manual_check\n'),false);
    assert.deepEqual(result,{status:'failed'});assert.ok(lines.includes('sandbox_result_failed cases=0 failures=1\n'));
    assert.equal(lines.includes(formatSandboxAuthorization(authorization)),order==='after');
    assert.equal(lines.some(line=>line.startsWith('sandbox_diagnostic phase=')),false);
    assert.equal(lines.join('').includes('private-marker'),false);
  }
});
test('reporter refuses malformed private diagnostic frames with only fixed output and final failure',async()=>{
  const base=expectedDiagnostic('LOGIN');
  const invalid=[
    {tag:'stripe_sandbox_diagnostic',private:'private-marker'},
    {...base,private:'private-marker'},{...base,tag:'stripe_sandbox_diagnostic_unknown'},
    {...base,phase:'unknown'},{...base,phase:'LOGIN\nprivate-marker'},
    {...base,code:'private-marker'},{...base,exitCode:'1'},
    {...base,code:'PROCESS_EXIT_NONZERO',exitCode:0},
    {...base,code:'PROCESS_EXIT_NONZERO',exitCode:256},
    {...base,code:'PROCESS_EXIT_NONZERO',exitCode:null},
    {...base,code:'PROCESS_SIGNALLED',exitCode:1},
    {...base,cleanupConfirmed:'true'},
  ].map(value=>JSON.stringify(value)+'\n');
  invalid.push('{"tag":"stripe_sandbox_diagnostic",private-marker}\n',
    '{"tag":"stripe_sandbox_diagnostic","private":"private-marker'+ 'x'.repeat(4096)+'"}\n',
    '{"private":"'+ 'x'.repeat(4096)+'","tag":"stripe_sandbox_diagnostic"}\n',
    '{"tag":"stripe_sandbox_diagnostic","private":"private-marker"');
  for(const frame of invalid){
    const {lines,result}=await reportFrames([frame.slice(0,11),Buffer.from(frame.slice(11))]);
    assert.deepEqual(result,{status:'failed'});assert.ok(lines.includes('sandbox_diagnostic_failed_manual_check\n'));
    assert.equal(lines.some(line=>line.startsWith('sandbox_diagnostic phase=')),false);
    assert.equal(lines.join('').includes('private-'),false);
    assert.ok(lines.includes('sandbox_result_failed cases=0 failures=1\n'));
  }
});
test('reporter keeps authorization immediate on either side of a diagnostic and rejects repeat diagnostics',async()=>{
  const auth=JSON.stringify(authorization)+'\n',diag=JSON.stringify(expectedDiagnostic('BINDING'))+'\n';
  for(const frames of [[diag,auth],[auth,diag]]){
    const {lines,result}=await reportFrames(frames);
    assert.deepEqual(result,{status:'failed'});
    assert.ok(lines.includes(formatSandboxAuthorization(authorization)));
    assert.equal(lines.includes('sandbox_authorization_failed_manual_check\n'),false);
    assert.equal(lines.filter(line=>line.startsWith('sandbox_diagnostic phase=')).length,1);
  }
  const duplicate=await reportFrames([diag,auth,diag]);
  assert.equal(duplicate.lines.filter(line=>line.startsWith('sandbox_diagnostic phase=')).length,1);
  assert.ok(duplicate.lines.includes('sandbox_diagnostic_failed_manual_check\n'));
  assert.deepEqual(duplicate.result,{status:'failed'});
  const privateChannels=await reportFrames([diag],true,true);
  assert.deepEqual(privateChannels.result,{status:'failed'});assert.equal(privateChannels.lines.join('').includes('private-'),false);
});
test('non-owned reporter suppresses all diagnostic and authorization input',async()=>{
  const {lines,result}=await reportFrames([JSON.stringify(expectedDiagnostic('LOGIN'))+'\n',
    JSON.stringify(authorization)+'\n','stripe_sandbox_diagnostic private-marker'+'x'.repeat(4096)],false);
  assert.equal(result,undefined);assert.deepEqual(lines,['sandbox_result_passed cases=0 failures=0\n']);
});

// Break: suppressing a proven completion failure loses its stage at the real reporter boundary.
test('failed login completion forwards one proven safe diagnostic from parent through reporter',async()=>{
  const f=fixture(),wire=[],original=f.deps.execFile;
  f.deps.execFile=(file,args,opts,callback)=>args.at(-1)!=='--complete-device'?original(file,args,opts,callback):
    (()=>{const child=new EventEmitter();queueMicrotask(()=>{callback(new Error('private-error'),'private-stdout','private-stderr');child.emit('close',1);});return child;})();
  const producer=captureOutput(wire);
  try{await assert.rejects(openOwnedSandboxAcceptance(options(),f.setup,f.deps),{message:'stripe_sandbox_acceptance_LOGIN_COMPLETE_PENDING'});}
  finally{producer.mock.restore();}
  assert.equal(f.events.includes('binding'),false);assert.equal(f.events.includes('start'),false);
  assert.ok(f.events.includes('stop-services'));
  const {default:Reporter}=await import('../examples/demo-site/tests/support/stripe-sandbox-reporter.ts');
  const old=process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN='true';
  const reporter=new Reporter(),lines=[],consumer=captureOutput(lines);
  try {
    for(const frame of wire){reporter.onStdOut(frame.slice(0,17));reporter.onStdOut(Buffer.from(frame.slice(17)));}
    assert.ok(lines.includes('sandbox_diagnostic phase=LOGIN_COMPLETE code=PROCESS_EXIT_NONZERO exit=1 cleanup=confirmed\n'));
    assert.deepEqual(await reporter.onEnd({status:'passed'}),{status:'failed'});
    assert.equal(lines.join('').includes('private-'),false);
    for(const privateValue of [ROOT,'sandbox-exec','--complete-device','--config','whsec_synthetic'])assert.equal(lines.join('').includes(privateValue),false);
  }finally{consumer.mock.restore();if(old===undefined)delete process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;else process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN=old;}
});

// Break: moving any capability getter before opt-in opens private setup without authority.
test('missing owned opt-in rejects before every other getter',async()=>{
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  const lines=[],write=captureOutput(lines);
  try{await assert.rejects(openOwnedSandboxAcceptance(new Proxy({env:{}},{get(t,k){if(k==='env')return t.env;touched++;throw null;}}),trap,trap),{message:'stripe_sandbox_acceptance_OPT_IN_REQUIRED'});}
  finally{write.mock.restore();}
  assert.equal(touched,0);assert.deepEqual(lines,[]);
});
// Break: accepting arbitrary redirect/next-step allows an unreviewed authorization destination.
test('human handoff accepts pinned hosts without guessing their path and rejects capability material',()=>{
  assert.equal(formatSandboxAuthorization(authorization),'Authorize the isolated Sandbox CLI at https://access.stripe.com/device/verify?device=public using code ABCD-EFGH. Keep this terminal open.\n');
  for(const browser_url of ['http://access.stripe.com/device','https://evil.test/device','https://access.stripe.com:444/device','https://u@access.stripe.com/device','https://access.stripe.com/device#secret','https://access.stripe.com/%0a','https://access.stripe.com/?key=sk_test_marker','https://access.stripe.com/?client_secret=abc'])
    assert.equal(formatSandboxAuthorization({...authorization,browser_url}),null);
  for(const verification_code of [' bad','ABCD\nEFGH','sk_test_marker','https://example.test','A'])assert.equal(formatSandboxAuthorization({...authorization,verification_code}),null);
  assert.equal(formatSandboxAuthorization({...authorization,extra:true}),null);
});
test('human handoff permits the default HTTPS port',()=>{
  assert.notEqual(formatSandboxAuthorization({...authorization,browser_url:'https://dashboard.stripe.com:443'}),null);
});
test('human handoff rejects encoded controls after decoding',()=>{
  assert.equal(formatSandboxAuthorization({...authorization,browser_url:'https://access.stripe.com/%C2%85'}),null);
});
// Break: premature refunds/extras writes or cleanup without owned listener exit violates lifecycle authority.
test('setup and one-shot journeys preserve original card-close-refund-extras-missed cleanup order',async()=>{
  const f=fixture(),lines=[];const write=captureOutput(lines);
  try {
    const owned=await openOwnedSandboxAcceptance(options(),f.setup,f.deps);assert.ok(Object.isFrozen(owned));
    assert.equal(await owned.run(f.journeys,f.setup),true);assert.equal(await owned.close(limits()),true);
    const steps=f.events.filter(v=>!['verify','binding'].includes(v));
    assert.deepEqual(steps,['bootstrap-factory','prepare','cli-factory','login-descriptor','complete-descriptor','listen','durable-secret','start','provision-factory','provision','open-cards','cards','close-cards','refunds','extras-factory','open-extras','donations','missed-fixture','missed','close-missed','close-extras','stop-listener','stop-services']);
    assert.equal(owned.resources().cleanupConfirmed,true);assert.equal(owned.resources().accepted,true);
    assert.deepEqual(owned.resources().proof,{cards:true,refunds:true,anonymous:true,emailOtp:true,otp:true,missed:true});
    assert.equal(lines.length,1);assert.deepEqual(JSON.parse(lines[0]),authorization);
    assert.equal(f.commands.length,2);assert.deepEqual(f.commands[0].args,f.descriptor().args);assert.deepEqual(f.commands[1].args,f.descriptor(true).args);
    for(const c of f.commands){assert.equal(c.opts.shell,false);assert.equal(c.opts.maxBuffer,65536);assert.ok(c.opts.timeout<=120000);assert.ok(c.opts.signal instanceof AbortSignal);assert.deepEqual(c.opts.env,f.descriptor().options.env);}
  }finally{write.mock.restore();}
});

// Break: suppressing the owned envelope prevents human authorization, or exposing arbitrary stdout leaks secrets.
test('reporter buffers one bounded owned authorization envelope and suppresses all other payloads',async()=>{
  const {default:Reporter}=await import('../examples/demo-site/tests/support/stripe-sandbox-reporter.ts');
  const old=process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN='true';
  const reporter=new Reporter(),lines=[];const write=captureOutput(lines);
  try {
    const wire=JSON.stringify(authorization)+'\n';reporter.onStdOut(wire.slice(0,9));reporter.onStdOut(Buffer.from(wire.slice(9)));
    reporter.onStdOut('private-marker\n');reporter.onStdErr('private-marker');
    assert.deepEqual(lines,[formatSandboxAuthorization(authorization)]);
    reporter.onStdOut(wire);assert.equal(lines.at(-1),'sandbox_authorization_failed_manual_check\n');assert.equal(lines.join('').includes('private-marker'),false);
  }finally{write.mock.restore();if(old===undefined)delete process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;else process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN=old;}
});

test('debug guards and invalid supplied identity reject before all capability factories',async()=>{
  const lines=[],write=captureOutput(lines);
  try{
    for(const mutate of [o=>o.env.DEBUG='*',o=>o.env.NODE_DEBUG='http',o=>o.env.PWDEBUG='1',o=>o.runId='not-a-run',o=>o.expectedSource='HEAD',o=>o.daemon='http://localhost',o=>o.ports.demo=o.ports.backend,o=>o.ports.extra=18000]){
      const opts=options();mutate(opts);let touched=0;const deps=new Proxy({},{get(){touched++;throw null;}});
      await assert.rejects(openOwnedSandboxAcceptance(opts,limits(),deps),{message:'stripe_sandbox_acceptance_CONFIGURATION'});assert.equal(touched,0);
    }
  }finally{write.mock.restore();}
  assert.deepEqual(lines,[]);
});
test('unsafe or ambiguous initiation never obtains completion or starts services',async()=>{
  for(const value of ['{}',json()+'\n'+json(),JSON.stringify({browser_url:authorization.browser_url,verification_code:'ABCD-EFGH',next_step:'stripe login --interactive'}),
    JSON.stringify({browser_url:'https://evil.test/device',verification_code:'ABCD-EFGH',next_step:'stripe login --complete-device'}),json().slice(0,-1)+',"extra":true}',json()]){
    const f=fixture();f.deps.execFile=(file,args,opts,callback)=>{const child=new EventEmitter();queueMicrotask(()=>{callback(null,value,value===json()?'private-stderr':'');child.emit('close',0);});return child;};
    await assert.rejects(quietOpen(f),{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});
    assert.equal(f.events.includes('complete-descriptor'),false);assert.equal(f.events.includes('listen'),false);assert.equal(f.events.includes('start'),false);assert.ok(f.events.includes('stop-services'));
  }
});
test('OAuth descriptor mutation cannot select a command or inherit an environment',async()=>{
  for(const mutate of [d=>d.args.push('--api-key','sk_test_synthetic'),d=>d.options.env.STRIPE_API_BASE='https://evil.test',d=>d.options.shell=true,d=>d.file='/bin/sh']){
    const f=fixture(),bad=structuredClone(f.descriptor());mutate(bad);f.cli.loginInvocation=async()=>frozen(bad);
    await assert.rejects(quietOpen(f),{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});assert.equal(f.commands.length,0);
  }
});
test('login callback success waits for actual process close before completion',async()=>{
  const f=fixture();let first,callbackDone=false;
  f.deps.execFile=(file,args,opts,callback)=>{const child=new EventEmitter();if(!first){first=child;queueMicrotask(()=>{callbackDone=true;callback(null,json(),'');});}
    else queueMicrotask(()=>{callback(null,'','');child.emit('close',0);});return child;};
  const opening=quietOpen(f);await new Promise(resolve=>setImmediate(resolve));assert.equal(callbackDone,true);assert.equal(f.events.includes('complete-descriptor'),false);
  first.emit('close',0);const owned=await opening;assert.equal(await owned.close(limits()),true);
});
test('abort callback does not release process ownership until the actual close event',async()=>{
  const f=fixture(),controller=new AbortController();f.setup.signal=controller.signal;let child;
  f.deps.execFile=(file,args,opts,callback)=>{child=new EventEmitter();opts.signal.addEventListener('abort',()=>callback(new Error('private-error'),'private-output','private-error'),{once:true});return child;};
  let settled=false;const opening=quietOpen(f).finally(()=>{settled=true;});opening.catch(()=>{});
  await new Promise(resolve=>setImmediate(resolve));controller.abort();await new Promise(resolve=>setImmediate(resolve));assert.equal(settled,false);
  child.emit('close',null);await assert.rejects(opening,{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});assert.equal(f.events.includes('complete-descriptor'),false);
});
test('binding failure after process zero never grants listener authority',async()=>{
  const f=fixture();f.cli.validateBinding=async()=>false;
  await assert.rejects(quietOpen(f),{message:'stripe_sandbox_acceptance_BINDING_PENDING'});assert.equal(f.events.includes('listen'),false);assert.equal(f.events.includes('start'),false);
});
test('unknown cards stop refunds and all later writes with first phase and separate cleanup evidence',async()=>{
  const f=fixture(),owned=await quietOpen(f);let calls=0;f.journeys.cards=async()=>{calls++;throw new Error('private-capability');};
  await assert.rejects(owned.run(f.journeys,limits()),{message:'stripe_sandbox_acceptance_CARDS_PENDING_CLEANUP_UNCONFIRMED'});
  assert.equal(calls,1);assert.equal(f.events.includes('refunds'),false);assert.equal(f.events.includes('donations'),false);
  assert.equal(owned.resources().failure,'CARDS');assert.equal(owned.resources().cleanupConfirmed,false);assert.equal(owned.resources().accepted,false);
  await assert.rejects(owned.run(f.journeys,limits()),{message:'stripe_sandbox_acceptance_CARDS_PENDING_CLEANUP_UNCONFIRMED'});assert.equal(calls,1);
});
test('browser true alone without protected proof stops the fourth payment',async()=>{
  const f=fixture(),owned=await quietOpen(f);f.journeys.donations=async()=>true;
  await assert.rejects(owned.run(f.journeys,limits()),{message:'stripe_sandbox_acceptance_DONATIONS_PENDING'});assert.equal(f.events.includes('missed-fixture'),false);
});
test('listener cleanup refusal prevents successful acceptance while services still receive exact stop',async()=>{
  const f=fixture(),owned=await quietOpen(f);f.cli.stopListener=async()=>{f.events.push('stop-listener');return false;};
  await assert.rejects(owned.run(f.journeys,limits()),{message:'stripe_sandbox_acceptance_CLEANUP_UNCONFIRMED'});
  assert.equal(owned.resources().accepted,false);assert.equal(owned.resources().cleanup.listener,false);assert.equal(owned.resources().cleanup.services,true);
});
test('later run limits cannot renew the original preparation deadline',async()=>{
  const f=fixture();const owned=await quietOpen(f);let captured;
  f.extras.missedFixture=async context=>{captured=context;return {close:async()=>{}};};
  assert.equal(await owned.run(f.journeys,{deadline:f.setup.deadline+600000}),true);assert.equal(captured.deadline,f.setup.deadline);
});
test('gated old transport proves ownership before credentials and preserves genuine 204 cookies',async()=>{
  const f=fixture();let called=0;f.deps.fetch=async(url,settings)=>{
    called++;assert.equal(f.events.at(-1),'verify');assert.equal(settings.redirect,'error');assert.ok(settings.signal instanceof AbortSignal);
    const headers=new Headers();headers.append('Set-Cookie','PAYMENT_ADMIN_SESSION=real-cookie; HttpOnly');headers.append('Set-Cookie','XSRF-TOKEN=real-csrf');
    return new Response(null,{status:204,headers});
  };
  const openCards=f.deps.openStripeSandboxFixture;
  f.deps.openStripeSandboxFixture=async opts=>{
    const response=await opts.fetch('http://127.0.0.1:18080/api/admin/v1/auth/sessions',{method:'POST',redirect:'error',body:'synthetic-admin',headers:{'Content-Type':'application/json'}});
    assert.equal(response.status,204);assert.equal(response.body,null);assert.deepEqual(response.headers.getSetCookie(),['PAYMENT_ADMIN_SESSION=real-cookie; HttpOnly','XSRF-TOKEN=real-csrf']);
    return openCards(opts);
  };
  const owned=await quietOpen(f);assert.equal(await owned.run(f.journeys,limits()),true);assert.equal(called,1);
});
test('gated old transport rejects foreign endpoints redirects and oversized full bodies before more writes',async()=>{
  for(const variant of ['foreign','redirect','overflow']) {
    const f=fixture();let calls=0;f.deps.fetch=async()=>{calls++;return variant==='redirect'?new Response(null,{status:302,headers:{Location:'https://evil.test'}}):new Response(Buffer.alloc(65537));};
    f.deps.openStripeSandboxFixture=async opts=>{await opts.fetch((variant==='foreign'?'http://127.0.0.1:1':'http://127.0.0.1:18080')+'/api/admin/v1/payment-accounts',{redirect:'error'});return {close:async()=>{}};};
    const owned=await quietOpen(f);await assert.rejects(owned.run(f.journeys,limits()),{message:'stripe_sandbox_acceptance_CARDS_PENDING'});
    assert.equal(calls,variant==='foreign'?0:1);assert.equal(f.events.includes('refunds'),false);
  }
});
test('reporter refuses malformed overflow and unfinished envelopes without printing their payload',async()=>{
  const {default:Reporter}=await import('../examples/demo-site/tests/support/stripe-sandbox-reporter.ts');
  const old=process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN='true';
  try {
    for(const wire of ['{"tag":"stripe_sandbox_authorization","private":"private-marker"}\n','stripe_sandbox_authorization'+'x'.repeat(4096),'{"tag":"stripe_sandbox_authorization"']) {
      const lines=[],reporter=new Reporter(),write=captureOutput(lines);
      try{reporter.onStdOut(wire);assert.deepEqual(await reporter.onEnd({status:'passed'}),{status:'failed'});assert.equal(lines.join('').includes('private-marker'),false);assert.ok(lines.includes('sandbox_authorization_failed_manual_check\n'));}
      finally{write.mock.restore();}
    }
  }finally{if(old===undefined)delete process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;else process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN=old;}
});

test('cleanup waits for the owning browser callback to finish closing its contexts after abort',async()=>{
  const f=fixture(),owned=await quietOpen(f),controller=new AbortController();let release;
  f.journeys.cards=async()=>{await new Promise(resolve=>{release=resolve;});f.events.push('browser-contexts-closed');return true;};
  const running=owned.run(f.journeys,{deadline:f.setup.deadline,signal:controller.signal});running.catch(()=>{});
  await new Promise(resolve=>setImmediate(resolve));controller.abort();await new Promise(resolve=>setImmediate(resolve));
  const premature=f.events.includes('stop-services');release();
  await assert.rejects(running,{message:'stripe_sandbox_acceptance_CARDS_PENDING_CLEANUP_UNCONFIRMED'});assert.equal(premature,false);
  assert.ok(f.events.indexOf('browser-contexts-closed')<f.events.indexOf('stop-listener'));
});
test('duplicate OAuth JSON fields are ambiguous and never reach completion',async()=>{
  const f=fixture();f.deps.execFile=(file,args,opts,callback)=>{const child=new EventEmitter();queueMicrotask(()=>{callback(null,json().slice(0,-1)+',"verification_code":"ABCD-EFGH"}','');child.emit('close',0);});return child;};
  await assert.rejects(quietOpen(f).then(async cap=>{await cap.close(limits());return cap;}),{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});assert.equal(f.events.includes('complete-descriptor'),false);
});
test('run and close reject overlap without disrupting the one original browser callback',async()=>{
  const f=fixture(),owned=await quietOpen(f);let release;
  const cards=f.journeys.cards;f.journeys.cards=async fixture=>{await new Promise(resolve=>{release=resolve;});return cards(fixture);};
  const running=owned.run(f.journeys,limits());await new Promise(resolve=>setImmediate(resolve));
  await assert.rejects(owned.run(f.journeys,limits()),{message:'stripe_sandbox_acceptance_BUSY'});
  await assert.rejects(owned.close(limits()),{message:'stripe_sandbox_acceptance_BUSY'});release();assert.equal(await running,true);
  assert.equal(f.events.filter(value=>value==='cards').length,1);
  assert.deepEqual(diagnostics(f.output),[]);
});
test('old transport cancellation aborts the full original response body and stops later writes',async()=>{
  const f=fixture(),controller=new AbortController();let requestSignal,reads=0;
  f.deps.fetch=async(url,settings)=>{
    requestSignal=settings.signal;
    return new Response(new ReadableStream({start(stream){settings.signal.addEventListener('abort',()=>stream.error(new Error('synthetic-stream-abort')),{once:true});},pull(){reads++;}}));
  };
  f.deps.openStripeSandboxFixture=async opts=>{await opts.fetch('http://127.0.0.1:18080/api/admin/v1/payment-accounts',{redirect:'error'});return {close:async()=>{}};};
  const owned=await quietOpen(f),running=owned.run(f.journeys,{deadline:f.setup.deadline,signal:controller.signal});running.catch(()=>{});
  await new Promise(resolve=>setImmediate(resolve));controller.abort();
  await assert.rejects(running,{message:'stripe_sandbox_acceptance_CARDS_PENDING'});assert.equal(requestSignal.aborted,true);assert.ok(reads>0);assert.equal(f.events.includes('refunds'),false);
});
test('never-settling rejected response cancellation cannot hold the old transport open',async()=>{
  const f=fixture(),controller=new AbortController();let settled=false;
  f.deps.fetch=async()=>({status:302,redirected:true,body:{cancel:()=>new Promise(()=>{})}});
  f.deps.openStripeSandboxFixture=async opts=>{await opts.fetch('http://127.0.0.1:18080/api/admin/v1/payment-accounts',{redirect:'error'});return {close:async()=>{}};};
  const owned=await quietOpen(f),running=owned.run(f.journeys,{deadline:f.setup.deadline,signal:controller.signal}).finally(()=>{settled=true;});running.catch(()=>{});
  await new Promise(resolve=>setImmediate(resolve));const completedBeforeAbort=settled;controller.abort();
  await assert.rejects(running,{message:'stripe_sandbox_acceptance_CARDS_PENDING'});assert.equal(completedBeforeAbort,true);
});
// I1: a rejected owner callback is not a receipt for closing its contexts.
for(const closeKind of ['reject','pending'])test(`owned card close ${closeKind} keeps browser cleanup unconfirmed while Node cleanup runs`,async()=>{
  const {withOwnedCardContexts}=await import('../examples/demo-site/tests/support/stripe-sandbox-acceptance.ts');
    const f=fixture(),owned=await quietOpen(f);let current=[],closeCalls=0;
    const browser={contexts:()=>current};
    const originalTimer=globalThis.setTimeout;
    const timer=mock.method(globalThis,'setTimeout',(fn,delay,...args)=>originalTimer(fn,delay===15000?1:delay,...args));
    f.journeys.cards=async()=>withOwnedCardContexts(browser,f.setup,async()=>{
      current=[{close:async()=>{closeCalls++;if(closeKind==='reject')throw new Error('synthetic-private-close');await new Promise(()=>{});}}];
    });
    try {
      await assert.rejects(owned.run(f.journeys,f.setup),{message:'stripe_sandbox_acceptance_CARDS_PENDING_CLEANUP_UNCONFIRMED'});
      assert.equal(closeCalls,1);assert.equal(owned.resources().failure,'CARDS');assert.equal(owned.resources().cleanupConfirmed,false);
      assert.equal(owned.resources().cleanup.listener,true);assert.equal(owned.resources().cleanup.services,true);assert.equal(owned.resources().accepted,false);
    }finally{timer.mock.restore();}
});
test('escaped duplicate OAuth name cannot overwrite a conflicting next step',async()=>{
  const f=fixture(),wire=JSON.stringify({browser_url:authorization.browser_url,verification_code:authorization.verification_code,next_step:'stripe login --interactive'}).slice(0,-1)
    +',"\\u006eext_step":"stripe login --complete-device"}';
  f.deps.execFile=(file,args,opts,callback)=>{const child=new EventEmitter();queueMicrotask(()=>{callback(null,wire,'');child.emit('close',0);});return child;};
  await assert.rejects(quietOpen(f).then(async owned=>{await owned.close(limits());return owned;}),{message:'stripe_sandbox_acceptance_LOGIN_PENDING'});
  assert.equal(f.events.includes('complete-descriptor'),false);
});
test('genuine OAuth escaped value preserves the public URL ampersand',async()=>{
  const f=fixture(),original=f.deps.execFile;
  f.deps.execFile=(file,args,opts,callback)=>original(file,args,opts,(error,out,err)=>callback(error,args.at(-1)==='--complete-device'?out:
    JSON.stringify({browser_url:'https://access.stripe.com/device?one=1&two=2',verification_code:'ABCD-EFGH',next_step:'stripe login --complete-device'}).replace('&','\\u0026'),err));
  const owned=await quietOpen(f);assert.equal(await owned.close(limits()),true);assert.equal(f.events.includes('complete-descriptor'),true);
});
