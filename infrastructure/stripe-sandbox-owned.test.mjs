import test from 'node:test';
import assert from 'node:assert/strict';

// Break: new-mode exclusion after normalization or evaluating an accessor.
test('remainder exclusion precedes snapshots configuration and dependencies',async()=>{
  const {preflightOwnedSandbox:accept,formDiagnosticOptIn}=await import('./stripe-sandbox-owned.mjs'); const base={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'};
  let reads=0;const trap=new Proxy({},{get(){reads++;throw null;}});
  for(const shape of ['true','malformed','boolean','accessor','hidden','inherited']){
    const raw=shape==='inherited'?Object.assign(Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'}),base):{...base};
    if(shape!=='inherited')Object.defineProperty(raw,'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',shape==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:shape!=='hidden',value:shape==='true'||shape==='hidden'?'true':shape==='boolean'?false:{private:'canary'}});
    assert.deepEqual(await accept({env:raw,get runId(){reads++;throw null;}},trap),{status:'PENDING',phase:'OPT_IN',code:'OPT_IN_REQUIRED'});
    const diagnostic=Object.create(Object.getPrototypeOf(raw),Object.getOwnPropertyDescriptors(raw));
    Object.assign(diagnostic,{PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:undefined});
    assert.equal(formDiagnosticOptIn(diagnostic),false);
  }
  assert.equal(reads,0);
});
import {EventEmitter} from 'node:events';
import {fileURLToPath} from 'node:url';
import {preflightOwnedSandbox,preflightOwnedFormDiagnostic} from './stripe-sandbox-owned.mjs';
import * as owned from './stripe-sandbox-owned.mjs';

const root=fileURLToPath(new URL('../',import.meta.url)).replace(/\/$/,'');
const source='a'.repeat(40),daemon='unix:///tmp/owned-docker.sock';
const flags={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'};
const ports={backend:56121,demo:56122,receiver:56123,postgres:56124,mailpitHttp:56125,mailpitSmtp:56126};
const manualFlags={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
const manualOptions=()=>options({env:{...manualFlags},manualPort:56127});
test('manual payment preflight requires original source, daemon and all seven available ports',async()=>{
  assert.equal(typeof owned.preflightOwnedManualPayment,'function');const input=options({env:{...flags,PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true'},manualPort:56127});
  const good=fixture();safe(await owned.preflightOwnedManualPayment(input,good.dependencies),{status:'READY'});assert.equal(good.calls.filter(c=>c.type==='port').length,7);
  for(const config of [{status:' M tracked\n'},{head:'b'.repeat(40)},{canonical:'/other.sock'}]){const f=fixture(config);assert.equal((await owned.preflightOwnedManualPayment(input,f.dependencies)).status,'PENDING');}
  const f=fixture();f.dependencies.portAvailable=async port=>port!==56127;safe(await owned.preflightOwnedManualPayment(input,f.dependencies),pending('PORTS','PORT_UNAVAILABLE'));
});
function options(patch={}) {return {runId:'11111111-1111-4111-8111-111111111111',expectedSource:source,daemon,env:{...flags},ports:{...ports},...patch};}
function fixture({head=source+'\n',status='',docker='"linux"\n',metadata={},canonical='/tmp/owned-docker.sock',executeError,portResult=true}={}) {
  const calls=[];
  return {calls,dependencies:{
    async execute(file,args,opts) {
      calls.push({type:'execute',file,args,opts});
      if(executeError)throw executeError;
      return {stdout:file.endsWith('/git')?(args.includes('rev-parse')?head:status):docker,stderr:''};
    },
    async lstat(path) {
      calls.push({type:'lstat',path});
      if(path==='/private/var/empty/config.json')throw Object.assign(new Error('synthetic absent config'),{code:'ENOENT'});
      if(path==='/private/var/empty')return {isDirectory:()=>true,isSymbolicLink:()=>false,uid:0,mode:0o40755};
      return {isSocket:()=>true,isSymbolicLink:()=>false,uid:process.getuid(),...metadata};
    },
    async realpath(path) {calls.push({type:'realpath',path});return path==='/private/var/empty'?path:canonical;},
    async portAvailable(port,host) {calls.push({type:'port',port,host});return portResult;}
  }};
}
function pending(phase,code) {return {status:'PENDING',phase,code};}
function safe(result,want) {assert.deepEqual(result,want);assert.equal(Object.isFrozen(result),true);assert.equal(JSON.stringify(result).includes('PRIVATE_CANARY'),false);}

test('diagnostic preflight uses dedicated flags while full preflight refuses mixed modes',async()=>{
  const env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'},f=fixture();
  safe(await preflightOwnedFormDiagnostic(options({env}),f.dependencies),{status:'READY'});
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  safe(await preflightOwnedSandbox(options({env:{...flags,...env}}),trap),pending('OPT_IN','OPT_IN_REQUIRED'));
  safe(await preflightOwnedFormDiagnostic(options({env:{...flags,...env}}),trap),pending('OPT_IN','OPT_IN_REQUIRED'));assert.equal(touched,0);
});
test('manual preflight admits exactly seven distinct ports and full/auto retain six',async()=>{
  assert.equal(typeof owned.preflightOwnedManualFormDiagnostic,'function');const f=fixture();
  safe(await owned.preflightOwnedManualFormDiagnostic(manualOptions(),f.dependencies),{status:'READY'});
  assert.deepEqual(f.calls.filter(c=>c.type==='port').map(c=>c.port),[56121,56122,56123,56124,56125,56126,56127]);
  const auto=fixture();await preflightOwnedFormDiagnostic(options({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}}),auto.dependencies);
  assert.equal(auto.calls.filter(c=>c.type==='port').length,6);
});
test('manual port collision fails without selecting a replacement or reading configuration on invalid admission',async()=>{
  assert.equal(typeof owned.preflightOwnedManualFormDiagnostic,'function');const f=fixture();f.dependencies.portAvailable=async port=>{f.calls.push({type:'port',port});return port!==56127;};
  safe(await owned.preflightOwnedManualFormDiagnostic(manualOptions(),f.dependencies),pending('PORTS','PORT_UNAVAILABLE'));
  assert.deepEqual(f.calls.filter(c=>c.type==='port').map(c=>c.port),[56121,56122,56123,56124,56125,56126,56127]);
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  safe(await owned.preflightOwnedManualFormDiagnostic({...manualOptions(),manualPort:56121},trap),pending('INPUTS','CONFIGURATION'));
  safe(await owned.preflightOwnedManualFormDiagnostic({env:{},get ports(){touched++;throw null;}},trap),pending('OPT_IN','OPT_IN_REQUIRED'));assert.equal(touched,0);
});
test('full and auto preflight reject manual flag before source/dependency getters',async()=>{
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  for(const [entry,env] of [[preflightOwnedSandbox,flags],[preflightOwnedFormDiagnostic,{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}]])
    safe(await entry({env:{...env,PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true'},get ports(){touched++;throw null;}},trap),pending('OPT_IN','OPT_IN_REQUIRED'));
  assert.equal(touched,0);
});

for(const absent of ['both',...Object.keys(flags)])
  test(`missing opt-ins (${absent}) never touch dependency, non-admission or private getters`,async()=>{
    const accesses=[],env=absent==='both'?{}:{...flags};delete env[absent];
    const input={env},dependencies={};
    for(const [target,names] of [
      [env,['DEBUG','NODE_DEBUG','PWDEBUG','PAYMENT_STRIPE_SANDBOX_SECRET_KEY','PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY']],
      [input,['runId','expectedSource','daemon','ports']],
      [dependencies,['execute','lstat','realpath','portAvailable','createServer','setTimeout','clearTimeout']]
    ])for(const name of names)Object.defineProperty(target,name,{get(){accesses.push(name);throw new Error('PRIVATE_CANARY');}});
    safe(await preflightOwnedSandbox(input,dependencies),pending('OPT_IN','OPT_IN_REQUIRED'));
    assert.deepEqual(accesses,[],'returning the right failure must not hide forbidden getter access');
  });

for(const [name,patch] of [
  ['missing UUID',{runId:undefined}],['uppercase UUID',{runId:'AAAAAAAA-1111-4111-8111-111111111111'}],
  ['old original UUID',{runId:'2f3f49b1-df07-41b1-b978-bc884b390813'}],['old second UUID',{runId:'6f684b69-32e6-429f-a34c-e4f69ba2ce18'}],
  ['short source',{expectedSource:'aaaa'}],['uppercase source',{expectedSource:'A'.repeat(40)}],
  ['duplicate port',{ports:{...ports,demo:ports.backend}}],['privileged port',{ports:{...ports,backend:80}}],
  ['fractional port',{ports:{...ports,backend:56121.5}}],['too-large port',{ports:{...ports,backend:65536}}],
  ['missing port',{ports:{backend:56121}}],['extra port',{ports:{...ports,extra:56127}}],
  ['remote daemon',{daemon:'tcp://127.0.0.1:2375'}],['daemon host',{daemon:'unix://remote/tmp/docker.sock'}],
  ['daemon query',{daemon:daemon+'?private=PRIVATE_CANARY'}],['daemon fragment',{daemon:daemon+'#x'}],
  ['daemon dot segment',{daemon:'unix:///tmp/../tmp/docker.sock'}],['daemon encoded path',{daemon:'unix:///tmp/%64ocker.sock'}],
  ['daemon whitespace',{daemon:'unix:///tmp/docker sock'}],['daemon empty path',{daemon:'unix:///'}],
  ['debug',{env:{...flags,DEBUG:'*'}}],['node debug',{env:{...flags,NODE_DEBUG:'http'}}],['Playwright debug',{env:{...flags,PWDEBUG:'1'}}]
])test(`invalid ${name} cannot reach source, socket, Docker or ports`,async()=>{
  const f=fixture();safe(await preflightOwnedSandbox(options(patch),f.dependencies),pending('INPUTS','CONFIGURATION'));assert.deepEqual(f.calls,[]);
});

test('successful admission preserves exact order, pinned read-only commands and sanitized children',async()=>{
  const f=fixture(),accesses=[],env={...flags,PWDEBUG:'0',GIT_DIR:'PRIVATE_CANARY',GIT_CONFIG_GLOBAL:'PRIVATE_CANARY',DOCKER_HOST:'PRIVATE_CANARY',DOCKER_CONFIG:'PRIVATE_CANARY',COMPOSE_FILE:'PRIVATE_CANARY',PATH:'PRIVATE_CANARY',HOME:'PRIVATE_CANARY'};
  for(const name of ['PAYMENT_STRIPE_SANDBOX_SECRET_KEY','PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY'])
    Object.defineProperty(env,name,{get(){accesses.push(name);throw new Error('PRIVATE_CANARY');}});
  safe(await preflightOwnedSandbox(options({env}),f.dependencies),{status:'READY'});
  assert.deepEqual(accesses,[]);
  const commands=f.calls.filter(c=>c.type==='execute');assert.equal(commands.length,3);
  assert.equal(commands[0].file,'/usr/bin/git');assert.deepEqual(commands[0].args,['rev-parse','HEAD']);
  assert.deepEqual(commands[1].args,['-c','core.fsmonitor=false','status','--porcelain=v1','--untracked-files=no']);
  assert.equal(commands[2].file,'/Applications/Docker.app/Contents/Resources/bin/docker');
  assert.deepEqual(commands[2].args,['--config','/private/var/empty','--host',daemon,'info','--format','{{json .OSType}}']);
  for(const c of commands) {
    assert.equal(c.opts.cwd,root);assert.equal(c.opts.timeout,10000);assert.equal(c.opts.maxBuffer,16384);
    assert.equal(c.opts.shell,false);assert.equal(JSON.stringify(c.opts.env).includes('PRIVATE_CANARY'),false);
    assert.equal(c.opts.env.GIT_OPTIONAL_LOCKS,'0');assert.equal(c.opts.env.GIT_CONFIG_GLOBAL,'/dev/null');
    for(const k of Object.keys(c.opts.env))assert.equal(/^(COMPOSE_|DOCKER_|PAYMENT_|DEBUG$|NODE_DEBUG$|PWDEBUG$)/.test(k),false);
  }
  assert.deepEqual(f.calls.slice(-6).map(c=>[c.type,c.port,c.host]),Object.values(ports).map(p=>['port',p,'127.0.0.1']));
  assert.equal(f.calls.findIndex(c=>c.type==='lstat'),2);
});

for(const [name,settings] of [['wrong source',{head:'b'.repeat(40)+'\n'}],['dirty source',{status:' M tracked.mjs\n'}],['process failure',{executeError:new Error('PRIVATE_CANARY')}],['malformed response',{head:null}]])
  test(`${name} stops before socket, Docker and port boundaries`,async()=>{
    const f=fixture(settings);safe(await preflightOwnedSandbox(options(),f.dependencies),pending('SOURCE','SOURCE_MISMATCH'));
    assert.equal(f.calls.every(c=>c.type==='execute'&&c.file==='/usr/bin/git'),true);
  });

for(const [name,settings] of [
  ['symlink socket',{metadata:{isSymbolicLink:()=>true}}],['non-socket',{metadata:{isSocket:()=>false}}],
  ['foreign uid',{metadata:{uid:process.getuid()+1}}],['noncanonical socket',{canonical:'/private/tmp/owned-docker.sock'}],
  ['non-linux Docker',{docker:'"windows"\n'}],['raw Docker output',{docker:'PRIVATE_CANARY'}]
])test(`${name} cannot reach port probes`,async()=>{
  const f=fixture(settings);safe(await preflightOwnedSandbox(options(),f.dependencies),pending('DAEMON','DAEMON_UNCONFIRMED'));assert.equal(f.calls.some(c=>c.type==='port'),false);
});

test('malicious nested/proxy process errors are never traversed or returned',async()=>{
  const malicious=new Proxy({},{get(){throw new Error('PRIVATE_CANARY');},ownKeys(){throw new Error('PRIVATE_CANARY');}});
  const f=fixture();f.dependencies.execute=async(file,args)=>{if(file.endsWith('/git'))return {stdout:args.includes('rev-parse')?source+'\n':'',stderr:''};throw malicious;};
  safe(await preflightOwnedSandbox(options(),f.dependencies),pending('DAEMON','DAEMON_UNCONFIRMED'));
});

for(const name of ['symlink','not directory','foreign owner','writable','missing mode','noncanonical','config present','config unreadable'])
  test(`unsafe Docker config isolation (${name}) stops before Docker info`,async()=>{
    const f=fixture(),lstat=f.dependencies.lstat,realpath=f.dependencies.realpath;
    f.dependencies.lstat=async path=>{
      if(path==='/private/var/empty/config.json') {
        if(name==='config present')return {isFile:()=>true};
        if(name==='config unreadable')throw Object.assign(new Error('PRIVATE_CANARY'),{code:'EACCES'});
      }
      const info=await lstat(path);
      if(path==='/private/var/empty')return {...info,...(name==='symlink'?{isSymbolicLink:()=>true}:name==='not directory'?{isDirectory:()=>false}:name==='foreign owner'?{uid:process.getuid()}:name==='writable'?{mode:0o40777}:name==='missing mode'?{mode:undefined}:{})};
      return info;
    };
    f.dependencies.realpath=async path=>path==='/private/var/empty'&&name==='noncanonical'?'/tmp/PRIVATE_CANARY':realpath(path);
    safe(await preflightOwnedSandbox(options(),f.dependencies),pending('DAEMON','DAEMON_UNCONFIRMED'));
    assert.equal(f.calls.some(c=>c.type==='execute'&&c.file.endsWith('/docker')),false);assert.equal(f.calls.some(c=>c.type==='port'),false);
  });

test('port refusal stops immediately without probing replacement ports',async()=>{
  const f=fixture({portResult:false});safe(await preflightOwnedSandbox(options(),f.dependencies),pending('PORTS','PORT_UNAVAILABLE'));
  assert.deepEqual(f.calls.filter(c=>c.type==='port').map(c=>c.port),[56121]);
});
test('port errors become only a fixed safe failure',async()=>{
  const f=fixture();f.dependencies.portAvailable=async()=>{throw new Error('PRIVATE_CANARY');};
  safe(await preflightOwnedSandbox(options(),f.dependencies),pending('PORTS','PORT_UNAVAILABLE'));
});

// Exercise the real default probe state machine; only TCP/timer primitives are controlled.
function controlledProbe({listenError=false,closeError=false,immediate=false,manual=false,holdPort}={}) {
  const f=fixture(),active=new Set(),servers=[];let entered;
  const closing=new Promise(resolve=>{entered=resolve;});
  delete f.dependencies.portAvailable;
  f.dependencies.setTimeout=(callback,ms)=>{assert.equal(ms,3000);const timer={callback};active.add(timer);return timer;};
  f.dependencies.clearTimeout=timer=>active.delete(timer);
  f.dependencies.createServer=handler=>{
    assert.equal(typeof handler,'function');const server=new EventEmitter();servers.push(server);
    server.listen=(opts,callback)=>{
      assert.equal(opts.host,'127.0.0.1');assert.equal(opts.exclusive,true);assert.equal(opts.port,[...Object.values(ports),...(manual?[56127]:[])][servers.length-1]);
      server.signal=opts.signal;server.port=opts.port;
      queueMicrotask(()=>listenError?server.emit('error',Object.assign(new Error('PRIVATE_CANARY'),{code:'EADDRINUSE'})):callback());
      return server;
    };
    server.close=callback=>{
      server.closeCalls=(server.closeCalls??0)+1;server.closed=callback;entered();
      if((immediate||servers.length>1)&&server.port!==holdPort)callback(closeError?new Error('PRIVATE_CANARY'):undefined);
      return server;
    };
    return server;
  };
  return {...f,active,servers,closing,expire(){for(const timer of [...active])timer.callback();}};
}

test('default probe retains its deadline and cannot advance until verified close',async()=>{
  const f=controlledProbe(),run=preflightOwnedSandbox(options(),f.dependencies);let settled=false;
  run.then(()=>{settled=true;});
  await f.closing;
  try {
    assert.equal(settled,false);assert.equal(f.servers.length,1);
    assert.equal(f.active.size,1,'the complete-probe timer must remain active during close');
    f.servers[0].closed();safe(await run,{status:'READY'});
    assert.equal(f.active.size,0);assert.equal(f.servers.length,6);
  }finally {f.servers[0].closed();await run;}
});

test('unconfirmed close expires to PENDING with no next probe or late READY',async()=>{
  const f=controlledProbe(),run=preflightOwnedSandbox(options(),f.dependencies);
  await f.closing;
  try {
    assert.equal(f.active.size,1,'pending close must not cancel its only deadline');
    f.expire();safe(await run,pending('PORTS','PORT_UNAVAILABLE'));
    assert.equal(f.servers.length,1);assert.equal(f.servers[0].closeCalls,1);assert.equal(f.servers[0].signal.aborted,true);
    f.servers[0].closed();f.servers[0].emit('error',new Error('PRIVATE_CANARY'));f.servers[0].closed();
    safe(await run,pending('PORTS','PORT_UNAVAILABLE'));assert.equal(f.servers.length,1);assert.equal(f.active.size,0);
  }finally {f.servers[0].closed();await run;}
});

test('close callback error cannot admit a port or start the next probe',async()=>{
  const f=controlledProbe({immediate:true,closeError:true});
  safe(await preflightOwnedSandbox(options(),f.dependencies),pending('PORTS','PORT_UNAVAILABLE'));
  assert.equal(f.servers.length,1);assert.equal(f.active.size,0);
});
test('immediate verified close admits all six and clears every complete-probe deadline',async()=>{
  const f=controlledProbe({immediate:true});safe(await preflightOwnedSandbox(options(),f.dependencies),{status:'READY'});
  assert.equal(f.servers.length,6);assert.equal(f.active.size,0);assert.equal(f.servers.every(s=>s.closeCalls===1),true);
});
test('listen refusal keeps cleanup bounded and can never become READY',async()=>{
  const f=controlledProbe({listenError:true}),run=preflightOwnedSandbox(options(),f.dependencies);
  await f.closing;
  try {
    assert.equal(f.active.size,1);f.expire();safe(await run,pending('PORTS','PORT_UNAVAILABLE'));
    f.servers[0].closed();assert.equal(f.servers.length,1);assert.equal(f.active.size,0);
  }finally {f.servers[0].closed();await run;}
});

// Synthetic OS socket boundary: exercise the production exclusive bind/close state machine.
function reservationProbe(occupied=[]) {
  const f=fixture(),reservations=new Map(occupied.map(port=>[port,'foreign'])),servers=[];delete f.dependencies.portAvailable;
  f.dependencies.createServer=()=>{
    const server=new EventEmitter();servers.push(server);
    server.listen=(settings,callback)=>{assert.equal(settings.host,'127.0.0.1');assert.equal(settings.exclusive,true);server.port=settings.port;
      queueMicrotask(()=>{if(reservations.has(server.port))server.emit('error',new Error('occupied'));else{reservations.set(server.port,server);callback();}});return server;};
    server.close=callback=>{server.closeCalls=(server.closeCalls??0)+1;if(reservations.get(server.port)===server)reservations.delete(server.port);queueMicrotask(callback);return server;};
    return server;
  };return {...f,reservations,servers};
}
test('synthetic occupied loopback port remains untouched and earlier owned probe closes',async()=>{
  const f=reservationProbe([56122]);safe(await preflightOwnedSandbox(options(),f.dependencies),pending('PORTS','PORT_UNAVAILABLE'));
  assert.deepEqual([...f.reservations],[[56122,'foreign']]);assert.equal(f.servers.length,2);assert.ok(f.servers.every(s=>s.closeCalls===1));
  assert.equal(f.reservations.has(56121),false,'earlier own probe released its reservation');
});
test('all six synthetic available ports admit READY and every owned probe has closed',async()=>{
  const f=reservationProbe();safe(await preflightOwnedSandbox(options(),f.dependencies),{status:'READY'});
  assert.equal(f.reservations.size,0);assert.equal(f.servers.length,6);assert.ok(f.servers.every(s=>s.closeCalls===1));
});
test('manual seventh port cannot admit before verified close and unconfirmed close fails',async()=>{
  assert.equal(typeof owned.preflightOwnedManualFormDiagnostic,'function');const f=controlledProbe({manual:true,immediate:true,holdPort:56127});
  let settled=false;const result=owned.preflightOwnedManualFormDiagnostic(manualOptions(),f.dependencies).then(value=>{settled=true;return value;});
  for(let n=0;n<40&&f.servers.length<7;n++)await new Promise(resolve=>setImmediate(resolve));
  assert.equal(f.servers.length,7);assert.equal(settled,false);assert.equal(f.active.size,1);
  f.servers[6].closed();safe(await result,{status:'READY'});assert.equal(f.active.size,0);
  const expired=controlledProbe({manual:true,immediate:true,holdPort:56127}),pendingResult=owned.preflightOwnedManualFormDiagnostic(manualOptions(),expired.dependencies);
  for(let n=0;n<40&&expired.servers.length<7;n++)await new Promise(resolve=>setImmediate(resolve));
  expired.expire();safe(await pendingResult,pending('PORTS','PORT_UNAVAILABLE'));expired.servers[6].closed();assert.equal(expired.servers.length,7);
});

test('Billing preflight reuses exact source daemon and six-port proof without legacy financial flags',async()=>{
  const input=options({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_success'}),f=fixture();
  safe(await owned.preflightOwnedBilling(input,f.dependencies),{status:'READY'});assert.equal(f.calls.filter(c=>c.type==='port').length,6);
  const wrong=fixture({head:'b'.repeat(40)});safe(await owned.preflightOwnedBilling(input,wrong.dependencies),pending('SOURCE','SOURCE_MISMATCH'));assert.equal(wrong.calls.filter(c=>c.type==='port').length,0);
});
test('Billing runtime preflight cancellation reaches command and prevents late source-to-daemon continuation',async()=>{
 const controller=new AbortController(),f=fixture();let release,signal;
 f.dependencies.execute=async(_file,_args,settings)=>{signal=settings.signal;return new Promise(r=>release=r);};
 const input=options({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_success'});
 const pending=owned.preflightOwnedBilling(input,f.dependencies,{deadline:Date.now()+1000,signal:controller.signal});await new Promise(r=>setTimeout(r,5));controller.abort();
 assert.equal((await pending).status,'PENDING');assert.equal(signal.aborted,true);release({stdout:source+'\n',stderr:''});await new Promise(r=>setTimeout(r,5));assert.deepEqual(f.calls,[]);
});
