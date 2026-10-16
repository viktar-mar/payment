import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import net from 'node:net';
import filesystem from 'node:fs/promises';
import {syncBuiltinESMExports} from 'node:module';
import {EventEmitter} from 'node:events';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {openOwnedSandboxAcceptance,formatSandboxAuthorization} from './stripe-sandbox-acceptance.mjs';

const forbidden=()=>{throw new Error('synthetic_test_forbids_external_boundary');};
for(const name of ['execFile','exec','spawn','fork','execSync','spawnSync','execFileSync'])mock.method(childProcess,name,forbidden);
for(const name of ['createServer','connect','createConnection'])mock.method(net,name,forbidden);
mock.method(globalThis,'fetch',forbidden);
for(const name of ['open','readFile','lstat','realpath','readdir','mkdir','rename','unlink'])mock.method(filesystem,name,forbidden);
syncBuiltinESMExports();after(()=>{mock.restoreAll();syncBuiltinESMExports();});
const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const RUN='11111111-2222-4333-8444-555555555555';
const ENV={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'};
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
      options:{cwd:ROOT,env:{PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:topology.cliDirectory+'/home',XDG_CONFIG_HOME:topology.cliDirectory+'/config',NO_COLOR:'1'},shell:false,stdio:['pipe','pipe','pipe'],timeout:180000,maxBuffer:65536}});
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
  const write=captureOutput();
  try{return await openOwnedSandboxAcceptance(opts,f.setup,f.deps);}finally{write.mock.restore();}
}

// Break: moving any capability getter before opt-in opens private setup without authority.
test('missing owned opt-in rejects before every other getter',async()=>{
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  await assert.rejects(openOwnedSandboxAcceptance(new Proxy({env:{}},{get(t,k){if(k==='env')return t.env;touched++;throw null;}}),trap,trap),{message:'stripe_sandbox_acceptance_OPT_IN_REQUIRED'});
  assert.equal(touched,0);
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
  for(const mutate of [o=>o.env.DEBUG='*',o=>o.env.NODE_DEBUG='http',o=>o.env.PWDEBUG='1',o=>o.runId='not-a-run',o=>o.expectedSource='HEAD',o=>o.daemon='http://localhost',o=>o.ports.demo=o.ports.backend,o=>o.ports.extra=18000]){
    const opts=options();mutate(opts);let touched=0;const deps=new Proxy({},{get(){touched++;throw null;}});
    await assert.rejects(openOwnedSandboxAcceptance(opts,limits(),deps),{message:'stripe_sandbox_acceptance_CONFIGURATION'});assert.equal(touched,0);
  }
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
