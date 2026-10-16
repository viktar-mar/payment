import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import {syncBuiltinESMExports} from 'node:module';
import {constants} from 'node:fs';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createSandboxCliAdapter} from './stripe-sandbox-cli.mjs';

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
async function start(f,line=READY) {const ready=f.adapter.startListener(LIMIT());await turn();f.children.at(-1)?.stderr.write(line);return ready;}
async function stop(f) {return f.adapter.stopListener(LIMIT());}
const rejects=(promise,code)=>assert.rejects(promise,{message:'stripe_sandbox_cli_'+code});

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
