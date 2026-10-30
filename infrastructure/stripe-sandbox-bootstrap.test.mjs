import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';

test('factory reuses one own-data env capture despite a substituting Proxy get trap',async()=>{
  const {createOwnedSandboxBootstrap:create}=await import('./stripe-sandbox-bootstrap.mjs');
  for(const exotic of [false,true]){
    let gets=0,captures=0,configuration=0,dependencies=0;
    const env=Object.assign(exotic?Object.create({unrelated:'ignored'}):{},{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'});
    const input=new Proxy({env,get runId(){configuration++;throw null;}},{get(target,key){if(key==='env'){gets++;return {};}return Reflect.get(target,key);},getOwnPropertyDescriptor(target,key){if(key==='env')captures++;return Reflect.getOwnPropertyDescriptor(target,key);}});
    const trap=new Proxy({},{get(){dependencies++;throw null;}});
    await assert.rejects(()=>create(input,trap,trap));
    assert.equal(gets,0);assert.equal(captures,1);assert.equal(configuration,1);assert.equal(dependencies,0);
  }
});

// Break: new-mode exclusion after normalization or evaluating an accessor.
test('remainder exclusion precedes snapshots configuration and dependencies',async()=>{
  const {createOwnedSandboxBootstrap:accept}=await import('./stripe-sandbox-bootstrap.mjs'); const base={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'};
  let reads=0;const trap=new Proxy({},{get(){reads++;throw null;}});
  for(const shape of ['true','malformed','boolean','accessor','hidden','inherited']){
    const raw=shape==='inherited'?Object.assign(Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'}),base):{...base};
    if(shape!=='inherited')Object.defineProperty(raw,'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',shape==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:shape!=='hidden',value:shape==='true'||shape==='hidden'?'true':shape==='boolean'?false:{private:'canary'}});
    await assert.rejects(accept({env:raw,get bootstrap(){reads++;throw null;},get runId(){reads++;throw null;}},trap,trap),/OPT_IN_REQUIRED/);
  }
  assert.equal(reads,0);
});
import childProcess from 'node:child_process';
import net from 'node:net';
import filesystem from 'node:fs/promises';
import {syncBuiltinESMExports} from 'node:module';
import {constants} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {EventEmitter} from 'node:events';
import {createOwnedSandboxBootstrap} from './stripe-sandbox-bootstrap.mjs';
import * as bootstrapModule from './stripe-sandbox-bootstrap.mjs';
import {createFormDiagnosticRuntime} from './stripe-form-diagnostic-runtime.mjs';
import * as formRuntime from './stripe-form-diagnostic-runtime.mjs';
import {createManualPaymentProvisioner} from './stripe-form-diagnostic-provision.mjs';

// Guard the builtin defaults as well as each fixture's raw process boundary.
const forbidden=()=>{throw new Error('synthetic_test_forbids_real_process');};
mock.method(childProcess,'execFile',forbidden);
mock.method(childProcess,'spawn',forbidden);
mock.method(net,'createServer',forbidden);
mock.method(globalThis,'fetch',forbidden);
const actualOpen=filesystem.open,actualReadFile=filesystem.readFile;
mock.method(filesystem,'open',(path,...args)=>{if(String(path).endsWith('/infrastructure/stripe-sandbox.env.local'))return forbidden();return actualOpen(path,...args);});
mock.method(filesystem,'readFile',(path,...args)=>{if(String(path).endsWith('/infrastructure/stripe-sandbox.env.local'))return forbidden();return actualReadFile(path,...args);});
syncBuiltinESMExports();
const actualEnvironment=process.env;
const privateEnvironment=name=>/^(?:HOME|DOCKER_.*|BUILDX_.*|STRIPE_.*|PAYMENT_STRIPE_SANDBOX_(?:SECRET_KEY|PUBLISHABLE_KEY|WEBHOOK_SECRET)|HTTPS?_PROXY|ALL_PROXY)$/i.test(String(name));
process.env=new Proxy(actualEnvironment,{get(target,name){if(privateEnvironment(name))return forbidden();return Reflect.get(target,name);}});
after(()=>{process.env=actualEnvironment;mock.restoreAll();syncBuiltinESMExports();});

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const RUN='11111111-2222-4333-8444-555555555555',PROJECT='stripe-'+RUN;
const DIR=ROOT+'/test-results/stripe-sandbox/'+RUN,USERFILE=ROOT+'/infrastructure/stripe-sandbox.env.local';
const PORTS={backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025};
const SERVICES=['postgres','mailpit','backend','demo-site','callback-receiver'];
const SECRET='whsec_A00112233445566778899';
const USERBYTES='# private synthetic test input\r\nPAYMENT_STRIPE_SANDBOX_SECRET_KEY=sk_test_A00112233445566778899\r\nPAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY="pk_test_B00112233445566778899"\r\nPAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET=\r\n';
const LIMIT=()=>({deadline:Date.now()+120000});
test('manual payment named factory builds separate assets and still requires verified listener',async()=>{
  assert.equal(typeof bootstrapModule.createOwnedManualPaymentBootstrap,'function');const f=fixture();
  f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT='true';f.options.manualPort=18083;delete f.options.approvedBinding;
  const b=await bootstrapModule.createOwnedManualPaymentBootstrap(f.options,f.dependencies),limits=LIMIT();
  assert.equal(b.withProviderCredentials,undefined);await b.prepare(limits);assert.equal(b.resources().mode,'MANUAL_PAYMENT');assert.equal(b.resources().webhookReady,false);
  const build=f.events.find(e=>e.type==='process'&&e.args.includes('build')&&e.args.includes('--quiet'));assert.ok(build.args.includes('PAYMENT_DEMO_BUILD_MODE=manual-payment'));
  await assert.rejects(b.start(limits));assert.equal(b.resources().webhookReady,false);
});
const turn=()=>new Promise(resolveTurn=>setImmediate(resolveTurn));
async function manualPaymentPrepared(){const f=fixture();f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT='true';f.options.manualPort=18083;delete f.options.approvedBinding;
  const execute=f.dependencies.execute;f.dependencies.execute=async(file,args,settings)=>{const result=await execute(file,args,settings);if(f.unknownBackendCreate&&args.includes('up')&&args.at(-1)==='backend')throw null;if(f.foreignBackendName&&args.some(a=>a.startsWith('name=^/')))return {stdout:id(999),stderr:''};return result;};
  f.adapter=await bootstrapModule.createOwnedManualPaymentBootstrap(f.options,f.dependencies);f.limits=LIMIT();await f.adapter.prepare(f.limits);return f;}
test('manual backend is absent until durable listener configuration then created once with no removal',async()=>{
  const f=await manualPaymentPrepared();assert.equal(f.adapter.resources().containers.backend,undefined);assert.equal(await f.adapter.verifyForwardTarget(),true);
  await f.adapter.persistWebhookSecret(SECRET);assert.equal(f.adapter.resources().containers.backend,undefined);assert.equal(await f.adapter.start(f.limits),true);
  const before=f.adapter.resources();assert.equal(before.status,'LIVE');assert.equal(before.webhookReady,true);assert.ok(before.containers.backend);
  await f.adapter.stop({deadline:f.limits.deadline+120000});assert.equal(f.containers.has(before.containers.backend.id),true);
  assert.equal(f.events.filter(stoppedCompose).filter(e=>e.args.slice(e.args.indexOf('up')).includes('backend')).length,1);
  assert.equal(f.events.some(e=>e.type==='process'&&e.args.includes('rm')),false);
});
test('manual backend final private store substitution refuses creation or retry',async()=>{
  const f=await manualPaymentPrepared();await f.adapter.persistWebhookSecret(SECRET);
  f.files.get(DIR+'/final.env').mode=0o100644;await assert.rejects(f.adapter.start(f.limits));const after=f.events.length;await assert.rejects(f.adapter.start(f.limits));assert.equal(f.events.length,after);assert.equal(f.adapter.resources().containers.backend,undefined);
});
test('manual backend unknown creation retains original dispatch and refuses retry or adoption',async()=>{
  const f=await manualPaymentPrepared();await f.adapter.persistWebhookSecret(SECRET);f.unknownBackendCreate=true;
  await assert.rejects(f.adapter.start(f.limits));assert.equal(f.adapter.resources().containers.backend,undefined);const n=f.events.length;await assert.rejects(f.adapter.start(f.limits));assert.equal(f.events.length,n);
  const ledger=JSON.parse(f.files.get(DIR+'/runtime.json').contents);assert.equal(ledger.intents.at(-1).action,'CREATE_FINAL_BACKEND');assert.equal(ledger.intents.at(-1).status,'DISPATCHING');
  await assert.rejects(f.adapter.stop({deadline:f.limits.deadline+120000}));assert.ok([...f.containers.values()].some(c=>c.Name==='/'+PROJECT+'-backend'));assert.equal(f.events.some(e=>e.type==='process'&&e.args.includes('rm')),false);
});
test('manual forwarding refuses a foreign planned backend name before listener authority',async()=>{const f=await manualPaymentPrepared();f.foreignBackendName=true;await assert.rejects(f.adapter.verifyForwardTarget());assert.equal(f.adapter.resources().containers.backend,undefined);
});
test('manual bootstrap cannot renew active authority after preparation',async()=>{
  const f=await manualPaymentPrepared();await f.adapter.persistWebhookSecret(SECRET);await assert.rejects(f.adapter.start({deadline:f.limits.deadline+1}));assert.equal(f.adapter.resources().containers.backend,undefined);
});
test('manual ownership verification accepts a clipped request budget inside original authority',async()=>{const f=await manualPaymentPrepared();await f.adapter.persistWebhookSecret(SECRET);await f.adapter.start(f.limits);assert.equal(await f.adapter.verify({deadline:Date.now()+10000}),true);await f.adapter.stop({deadline:f.limits.deadline+120000});});
const rejects=(promise,code)=>assert.rejects(promise,{message:'stripe_sandbox_bootstrap_'+code});
const id=n=>n.toString(16).padStart(64,'0');
const image=n=>'sha256:'+id(n);
const labels=kind=>({'payment.sandbox.project':PROJECT,'payment.sandbox.run':RUN,'payment.sandbox.kind':kind});
const cLabels=service=>({...labels('container'),'com.docker.compose.project':PROJECT,'com.docker.compose.service':service});
const mapping=service=>Object.fromEntries(({
  postgres:[['5432/tcp',PORTS.postgres]],mailpit:[['8025/tcp',PORTS.mailpitHttp],['1025/tcp',PORTS.mailpitSmtp]],
  backend:[['8080/tcp',PORTS.backend]],'demo-site':[['8080/tcp',PORTS.demo]],'callback-receiver':[['8080/tcp',PORTS.receiver]],
}[service]??[]).map(([port,value])=>[port,[{HostIp:'127.0.0.1',HostPort:String(value)}]]));
const mounts=service=>({postgres:[{Type:'volume',Name:PROJECT+'-postgres',Source:'/var/lib/docker/volumes/owned/_data',Destination:'/var/lib/postgresql',RW:true}],
  backend:[{Type:'volume',Name:PROJECT+'-issuer',Source:'/var/lib/docker/volumes/issuer/_data',Destination:'/app/local-fixtures',RW:false}],
  'callback-receiver':[{Type:'bind',Source:ROOT+'/infrastructure/callback-receiver.mjs',Destination:'/app/callback-receiver.mjs',RW:false}],
  'issuer-installer':[{Type:'bind',Source:DIR+'/issuer.jwks.json',Destination:'/input/issuer.json',RW:false},{Type:'volume',Name:PROJECT+'-issuer',Source:'/var/lib/docker/volumes/issuer/_data',Destination:'/issuer',RW:true}],
  'issuer-probe':[{Type:'volume',Name:PROJECT+'-issuer',Source:'/var/lib/docker/volumes/issuer/_data',Destination:'/issuer',RW:false}],
}[service]??[]);
const stoppedCompose=event=>event.type==='process'&&event.args[4]==='compose'&&(event.args.includes('up')||event.args.includes('create'));

function fixture(overrides={}) {
  const events=[],files=new Map(),containers=new Map(),volumes=new Map(),images=new Map(),timers=new Map();
  let serial=10,timerSerial=0,randomSerial=0,network=null;
  const add=(path,directory,contents='')=>files.set(path,{directory,contents:Buffer.from(contents),mode:directory?0o40700:0o100600,uid:process.getuid(),nlink:1,ino:++serial,dev:1});
  for(let path=ROOT;path!=='/';path=dirname(path))add(path,true);
  add('/',true);add(ROOT+'/infrastructure',true);add(ROOT+'/backend',true);
  const ignore='**/test-results\n**/.superpowers\n**/*.env.local\n**/fixtures/customer-issuer-*.jwks.json\n';
  add(ROOT+'/.dockerignore',false,ignore);add(ROOT+'/backend/.dockerignore',false,ignore);add(USERFILE,false,USERBYTES);
  const stat=entry=>({...entry,size:entry.contents.length,isDirectory:()=>entry.directory,isFile:()=>!entry.directory,isSymbolicLink:()=>Boolean(entry.symlink)});
  const need=path=>{const entry=files.get(path);if(!entry)throw null;return entry;};
  const env=new Proxy({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'},
    {get(target,name){if(privateEnvironment(name))return forbidden();return Reflect.get(target,name);}});
  const options={runId:RUN,expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',ports:{...PORTS},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},env,...overrides};
  function container(service) {
    const helper=service.startsWith('issuer-'),name=PROJECT+'-'+service;
    const value={Id:id(++serial),Name:'/'+name,Image:images.get(helper?'backend':service),Labels:cLabels(service),
      State:{Status:'created',Running:false,ExitCode:0,StartedAt:'0001-01-01T00:00:00Z',Health:''},Mounts:mounts(service),
      Networks:helper?{none:{NetworkID:''}}:{[PROJECT+'-network']:{NetworkID:network.Id}},PortBindings:mapping(service),Ports:{},
      User:helper?(service==='issuer-installer'?'0:0':'10001:10001'):(service==='backend'?'payment':service==='demo-site'?'101:101':service==='callback-receiver'?'node':''),
      ReadonlyRootfs:helper,NetworkMode:helper?'none':PROJECT+'-network',CapAdd:service==='issuer-installer'?['CHOWN','DAC_OVERRIDE']:[],CapDrop:helper?['ALL']:[],
      StripeKeyPresent:service==='backend'&&events.some(e=>e.type==='open'&&e.path===DIR+'/final.env'),
      SandboxDisabled:!events.some(e=>e.type==='open'&&e.path===DIR+'/final.env')};
    containers.set(value.Id,value);return value;
  }
  const dependencies={
    execFile:forbidden,now:Date.now,setTimeout:(callback,ms)=>{const key=++timerSerial;timers.set(key,{callback,ms});return key;},clearTimeout:key=>timers.delete(key),
    preflight:async input=>{events.push({type:'preflight',input});return {status:'READY'};},portAvailable:async(port,host,context)=>{events.push({type:'port',port,host,context});return true;},
    lstat:async path=>{events.push({type:'lstat',path});return stat(need(path));},realpath:async path=>{need(path);return path;},
    readFile:async(path)=>{events.push({type:'readFile',path});return Buffer.from(need(path).contents);},
    readdir:async path=>[...files.keys()].filter(p=>dirname(p)===path).map(p=>p.slice(path.length+1)),
    mkdir:async(path,settings)=>{events.push({type:'mkdir',path,settings});if(files.has(path))throw null;need(dirname(path));add(path,true);},
    rename:async(from,to)=>{events.push({type:'rename',from,to});need(from);files.set(to,files.get(from));files.delete(from);},
    unlink:async path=>{events.push({type:'unlink',path});need(path);files.delete(path);},
    open:async(path,flags,mode)=>{
      events.push({type:'open',path,flags,mode});
      if(flags&constants.O_CREAT) {if(files.has(path)&&(flags&constants.O_EXCL))throw null;need(dirname(path));if(!files.has(path))add(path,false);}
      const entry=need(path);
      if(entry.symlink&&(flags&constants.O_NOFOLLOW))throw null;
      if(flags&constants.O_TRUNC)entry.contents=Buffer.alloc(0);
      return {stat:async()=>stat(entry),writeFile:async contents=>{events.push({type:'write',path});entry.contents=Buffer.from(contents);},
        readFile:async()=>{events.push({type:'read',path});return Buffer.from(entry.contents);},
        read:async(buffer,offset,length,position)=>{events.push({type:'read',path});const bytesRead=Math.min(length,entry.contents.length-position);entry.contents.copy(buffer,offset,position,position+bytesRead);return {bytesRead,buffer};},
        sync:async()=>{events.push({type:'sync',path});},close:async()=>{events.push({type:'close',path});}};
    },
    randomBytes:size=>{events.push({type:'random',size});return Buffer.alloc(size,++randomSerial);},generateKeyPairSync:(kind,options)=>{events.push({type:'crypto',kind,options});return {privateKey:{export:()=>kind==='rsa'?{kty:'RSA',n:'n',e:'AQAB',d:'d'}:{kty:'EC',crv:'P-256',x:'x',y:'y',d:'d'}}};},
    health:async(url,context)=>{events.push({type:'health',url,context});return true;},
    execute:async(file,args,settings)=>{
      events.push({type:'process',file,args,settings});
      if(file==='/usr/bin/git')return {stdout:args.includes('rev-parse')?'e'.repeat(40)+'\n':'',stderr:''};
      const a=args.slice(4),[verb,sub]=a;
      let stdout='';
      if(verb==='compose') {
        const action=a.findIndex(value=>['create','up'].includes(value));
        if(action!==-1) {
          const envFile=a[a.indexOf('--env-file')+1];
          assert.ok([DIR+'/app.env',DIR+'/final.env'].includes(envFile),'stopped Compose environment must be selected explicitly');
          const services=envFile===DIR+'/app.env'?SERVICES.filter(s=>options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT!=='true'||s!=='backend'):options.env.PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC==='true'?SERVICES:['backend'];
          assert.deepEqual(a.slice(action),['up','--no-start','--no-deps','--no-build','--pull','never',...services],
            'stopped Compose contract requires supported up, no start, no dependencies, no build, no pull and exact services');
          for(const service of services)container(service);
        }
        if(a.includes('build')) {images.set('backend',image(1));images.set('demo-site',image(2));}
        if(a.includes('config'))stdout=JSON.stringify({services:Object.fromEntries(SERVICES.map(service=>[service,{
          image:service==='backend'?PROJECT+'-backend:latest':service==='demo-site'?PROJECT+'-demo-site:latest':service==='postgres'?'postgres:18-alpine':service==='mailpit'?'axllent/mailpit:v1.27':'node:24-alpine',
          container_name:PROJECT+'-'+service,labels:labels('container'),networks:{default:null},environment:service==='backend'?{PAYMENT_STRIPE_SANDBOX_ENABLED:'false',...(options.env.PAYMENT_STRIPE_BILLING_ACCEPTANCE==='true'?{PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACTIONS_KEY_VERSIONS:'v1',PAYMENT_STRIPE_BILLING_ACTIONS_ACTIVE_KEY_VERSION:'v1',PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1:'buildOnly'}:{})}:{},
          ports:Object.entries(mapping(service)).map(([port,binding])=>({target:Number(port.split('/')[0]),published:binding[0].HostPort,host_ip:binding[0].HostIp,protocol:'tcp',mode:'ingress'})),
          volumes:mounts(service).map(m=>({type:m.Type,source:m.Type==='bind'?m.Source:service==='backend'?'owned-issuer':'payment-postgres',target:m.Destination,read_only:!m.RW})),
        }])),networks:{default:{external:true,name:PROJECT+'-network'}},volumes:{'payment-postgres':{external:true,name:PROJECT+'-postgres'},'owned-issuer':{external:true,name:PROJECT+'-issuer'}}});
      } else if(verb==='pull') {
        const tag=a.at(-1);images.set(tag==='postgres:18-alpine'?'postgres':tag==='axllent/mailpit:v1.27'?'mailpit':'callback-receiver',image(images.size+3));
      } else if(verb==='image'&&sub==='inspect') {
        const tag=a.at(-1),service=tag.includes('demo-site')?'demo-site':tag.includes('backend')?'backend':tag==='postgres:18-alpine'?'postgres':tag==='axllent/mailpit:v1.27'?'mailpit':'callback-receiver';
        stdout=JSON.stringify({Id:images.get(service),User:service==='backend'?'payment':service==='demo-site'?'101:101':''});
      } else if(verb==='network'&&sub==='create') {network={Id:id(++serial),Name:PROJECT+'-network',Labels:labels('network'),Driver:'bridge',Scope:'local'};stdout=network.Id;
      } else if(verb==='network'&&sub==='inspect')stdout=JSON.stringify(network);
      else if(verb==='volume'&&sub==='create') {const name=a.at(-1),kind=name.endsWith('-issuer')?'issuer':'postgres';volumes.set(name,{Name:name,CreatedAt:'2026-10-16T12:00:00Z',Driver:'local',Scope:'local',Labels:labels(kind)});stdout=name;
      } else if(verb==='volume'&&sub==='inspect')stdout=JSON.stringify(volumes.get(a.at(-1)));
      else if(verb==='container'&&sub==='create') {const name=a[a.indexOf('--name')+1],service=name.endsWith('-issuer-installer')?'issuer-installer':'issuer-probe';stdout=container(service).Id;
      } else if(verb==='container'&&sub==='inspect') {const selector=a.at(-1),value=containers.get(selector)??[...containers.values()].find(c=>c.Name==='/'+selector);if(!value)throw null;stdout=JSON.stringify(value);
      } else if(verb==='container'&&sub==='start') {const value=containers.get(a.at(-1));assert.ok(value);value.State={Status:value.Name.includes('issuer-')?'exited':'running',Running:!value.Name.includes('issuer-'),ExitCode:0,StartedAt:'2026-10-16T12:01:00Z',Health:value.Name.includes('issuer-')?'':'healthy'};
        value.Ports=structuredClone(value.PortBindings);if(value.Name.endsWith('-mailpit'))value.Ports['1110/tcp']=null;
        if(value.NetworkMode==='none')value.Networks={none:{NetworkID:id(900)}};
      } else if(verb==='container'&&sub==='wait')stdout='0';
      else if(verb==='container'&&sub==='stop') {const value=containers.get(a.at(-1));assert.ok(value);value.State.Running=false;value.State.Status='exited';}
      else if(verb==='container'&&sub==='rm') {assert.equal(a.length,3);assert.ok(containers.delete(a.at(-1)));stdout=a.at(-1);}
      else if(verb==='ps'&&a.includes('--filter')){const filter=a[a.indexOf('--filter')+1];stdout=[...containers.values()].filter(c=>!filter.startsWith('name=')||c.Name==='/'+PROJECT+'-backend').map(c=>c.Id).join('\n');}
      return {stdout,stderr:''};
    },
  };
  return {options,dependencies,events,files,containers,volumes,images,timers,get network(){return network;},stat};
}
async function setup(overrides) {const f=fixture(overrides);f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);return f;}
async function prepared() {const f=await setup();assert.equal(await f.adapter.prepare(LIMIT()),true);return f;}

test('prepare-only bootstrap has a separate inert factory and rejects full-mode flags before dependency access',async()=>{
  assert.equal(typeof bootstrapModule.createOwnedFormDiagnosticBootstrap,'function');
  let touched=0;const deps=new Proxy({},{get(){touched++;throw null;}});
  await assert.rejects(bootstrapModule.createOwnedFormDiagnosticBootstrap({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'}},deps));
  assert.equal(touched,0);
});

test('prepare-only bootstrap creates the final backend once without listener, user-env mutation or removal',async()=>{
  assert.equal(typeof bootstrapModule.createOwnedFormDiagnosticBootstrap,'function');
  const f=fixture({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}});
  const original=Buffer.from(f.files.get(USERFILE).contents),b=await bootstrapModule.createOwnedFormDiagnosticBootstrap(f.options,f.dependencies),limits=LIMIT();
  assert.deepEqual(Object.keys(b).sort(),['prepare','resources','start','stop','verify','withAdminCredentials','withProviderCredentials'].sort());
  await b.prepare(limits);await b.start(limits);assert.equal(await b.verify(limits),true);
  assert.equal(b.resources().webhookReady,false);assert.equal(b.resources().mode,'FORM_DIAGNOSTIC');
  assert.deepEqual(f.files.get(USERFILE).contents,original);
  assert.equal(f.events.some(e=>e.type==='process'&&e.args.includes('rm')),false);
  assert.equal(f.events.some(e=>e.type==='mkdir'&&e.path.includes('/cli')),false);
  assert.ok(f.events.some(e=>e.type==='process'&&e.args.includes('PAYMENT_DEMO_BUILD_MODE=form-diagnostic')));
  let keys;await b.withProviderCredentials(value=>{keys=value;return true;});
  assert.deepEqual(Object.keys(keys).sort(),['publishableKey','secretKey']);
  await b.stop({...limits,deadline:limits.deadline+120000});
});

function formFixture(browserOrigin='http://127.0.0.1:18081') {
  const env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
  const f=fixture({env});delete f.options.approvedBinding;
  const suffix=RUN.replaceAll('-',''),uid=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
  const account={id:uid(1),key:'stripe-sandbox-default',provider:'STRIPE',mode:'SANDBOX',status:'ACTIVE',external_account_id:'acct_1UPiMpELswAd8tpR'};
  const site={id:uid(2),key:'s5a-'+suffix,status:'ACTIVE',payment_account_id:uid(1),allowed_origins:[browserOrigin],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'ALLOWLIST',methods:['CARD']}};
  const provider={id:uid(3),provider_key:'s5a-email-'+suffix,mode:'EMAIL_OTP',status:'ACTIVE',readiness:'READY',version:0,sender_ref:'local-default',template_key:'otp-default',issuer:null,audiences:[],allowed_algorithms:[],jwks_source_type:null,keys:[]};
  const product={id:uid(4),site_id:site.id,key:'s5a-product-'+suffix,name:'Stage5A one-time acceptance',description:null,status:'ACTIVE'};
  const price={id:uid(5),site_id:site.id,product_id:product.id,price_key:'s5a-price-'+suffix,version:1,type:'ONE_TIME',amount_minor:2000,currency:'EUR',billing_interval:null,trial_days:0,trial_payment_method_policy:'OPTIONAL_DURING_TRIAL',tax_mode:'NONE',tax_code:null,status:'DRAFT',provider_price_id:null};
  const checkout={checkout_id:uid(6),payment_id:uid(7),checkout_token:Buffer.alloc(32,23).toString('base64url'),amount_minor:2000,currency:'EUR',provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'REQUIRES_PAYMENT_METHOD',expires_at:new Date(Date.now()+3600000).toISOString(),price:{price_id:price.id,price_key:price.price_key,version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE'}};
  const operation={operation_id:uid(8),payment_id:checkout.payment_id,resource_id:checkout.payment_id,account_id:account.id,account_mode:'SANDBOX',site_id:site.id,kind:'PAYMENT_PREPARE',status:'ACKNOWLEDGED',manual_review_required:false,reference:'pi_TestOnly'};
  const intent={id:'pi_TestOnly',object:'payment_intent',livemode:false,status:'requires_payment_method',amount:2000,currency:'eur',amount_received:0,payment_method:null,latest_charge:null,customer:null,setup_future_usage:null,on_behalf_of:null,transfer_data:null};
  const detail={payment:{payment_id:checkout.payment_id,site_id:site.id,payment_account_id:account.id,provider:'STRIPE',source:'CATALOG',price_id:price.id,price_key:price.price_key,price_version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE',refunded_amount_minor:0,refundable_amount_minor:0,status:'REQUIRES_PAYMENT_METHOD',customer_id:null,donation_id:null,subscription_id:null},attempts:[],events:[{id:uid(9),event_type:'PAYMENT_CREATED'},{id:uid(10),event_type:'PAYMENT_METHOD_REQUIRED'}],refunds:[],outbox_events:[],deliveries:[]};
  const payments=[detail.payment];let accountCreated=false,siteCreated=false,policy=[];
  const http=[];
  const response=(value,status=200,headers={})=>{const h=new Headers();for(const [name,values] of Object.entries(headers))for(const value of Array.isArray(values)?values:[values])h.append(name,value);return new Response(status===204?null:JSON.stringify(value),{status,headers:h});};
  const fetcher=async(url,settings)=>{
    const path=new URL(url).pathname,method=settings.method,body=settings.body?JSON.parse(settings.body):undefined;http.push({url,path,method,body,settings});
    if(url.startsWith('https://api.stripe.com/'))return response(path==='/v1/account'?{id:'acct_1UPiMpELswAd8tpR',object:'account'}:intent,200,{'Stripe-Version':'2026-09-30.endive'});
    if(method!=='GET') {const ledger=JSON.parse(f.files.get(DIR+'/form-provisioning.json').contents);assert.equal(ledger.intents.at(-1).status,'DISPATCHING');}
    if(path.endsWith('/auth/sessions'))return response(null,204,{'Set-Cookie':['PAYMENT_ADMIN_SESSION=session; Path=/; HttpOnly','XSRF-TOKEN=csrf; Path=/']});
    if(path.endsWith('/payment-accounts')){if(method==='GET')return response(accountCreated?[account]:[]);accountCreated=true;return response(account,201);}
    if(path==='/api/admin/v1/sites'){if(method==='GET')return response(siteCreated?[site]:[]);siteCreated=true;return response(site,201);}
    if(path.endsWith('/identity-providers'))return response([provider]);if(path.endsWith('/identity-providers/'+provider.id))return response(provider);
    if(path.endsWith('/checkout-return-urls')){if(method==='GET')return response(policy);policy=[{origin:site.allowed_origins[0],return_url:site.allowed_origins[0]+'/payment-return.html',version:0}];return response(null,204);}
    if(path.endsWith('/products'))return response(product,201);if(path.endsWith('/prices'))return response(price,201);
    if(path.endsWith('/publish')){price.status='PUBLISHED';price.provider_price_id='price_TestOnly';return response(price);}
    if(path.endsWith('/catalog'))return response({products:[product],prices:[price]});
    if(path.endsWith('/checkouts'))return response(checkout,201);
    if(path==='/api/v1/admin/payments'){
      assert.equal(new URL(url).search,'?site_id='+site.id+'&page=0&size=2&sort=CREATED_DESC');
      return response({items:payments,total:payments.length,page:0,size:2,sort:'CREATED_DESC'});
    }
    if(path.endsWith('/payments/'+checkout.payment_id))return response(detail);
    if(path.endsWith('/provider-operations'))return response([operation]);throw new Error('Unexpected synthetic route');
  };
  const lstat=f.dependencies.lstat;f.dependencies.lstat=async path=>{if(!f.files.has(path))throw Object.assign(new Error(),{code:'ENOENT'});return lstat(path);};
  const deps={bootstrap:f.dependencies,provider:{fetch:fetcher},provision:{...f.dependencies,fetch:fetcher}};
  return {...f,deps,http,detail,intent,operation,payments,resume(){detail.attempts=[{id:uid(11),attempt_number:1,status:'REQUIRES_PAYMENT_METHOD',provider_intent_reference:'pi_TestOnly',payment_method_family:'CARD',result_code:null}];detail.events.push({id:uid(12),event_type:'ATTEMPT_CREATED'});}};
}

// Keep the real credential capability and full bootstrap proofs; only the guarded
// filesystem/process/HTTP boundaries below are artificial.
async function manualCredentialLive(configure=()=>{}) {
  const f=fixture();f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT='true';f.options.manualPort=18083;delete f.options.approvedBinding;
  f.clock={now:1000};f.dependencies.now=()=>f.clock.now;f.limits={deadline:121000};
  configure(f);f.adapter=await bootstrapModule.createOwnedManualPaymentBootstrap(f.options,f.dependencies);
  await f.adapter.prepare(f.limits);await f.adapter.persistWebhookSecret(SECRET);await f.adapter.start(f.limits);return f;
}
for(const threeDSecure of [false,true])for(const latency of [0,230,500])test(`manual credential composition ${threeDSecure?'selected 3DS':'ordinary'} at ${latency}ms preserves original child budgets`,async()=>{
  const f=formFixture('http://127.0.0.1:18083');f.options.env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true',...(threeDSecure?{PAYMENT_STRIPE_SANDBOX_MANUAL_3DS:'true'}:{})};
  f.options.manualPort=18083;let now=1000,simulate=false;const execute=f.dependencies.execute;
  f.dependencies.now=()=>now;f.dependencies.execute=async(...args)=>{const result=await execute(...args);if(simulate)now+=latency;return result;};
  const b=await bootstrapModule.createOwnedManualPaymentBootstrap(f.options,f.dependencies),limits={deadline:121000};
  await b.prepare(limits);await b.persistWebhookSecret(SECRET);await b.start(limits);
  const fetcher=f.deps.provision.fetch;let firstHttp=null;
  const dependencies={...f.dependencies,fetch:async(url,settings)=>{
    if(firstHttp===null){firstHttp={path:new URL(url).pathname,now};simulate=false;}
    const path=new URL(url).pathname;
    if(path==='/inspection')return new Response(JSON.stringify({count:0,duplicates:0,events:[]}));
    if(path.endsWith('/callback'))return new Response(JSON.stringify({site_id:'00000000-0000-4000-8000-000000000002',endpoint_url:'http://callback-receiver:8080/callback',enabled:true,secret_version:1,secret:Buffer.alloc(32,17).toString('base64url')}));
    if(path==='/configure')return new Response(null,{status:204});return fetcher(url,settings);
  }};
  let binding=false;const p=await createManualPaymentProvisioner({env:f.options.env,bootstrap:b,browserOrigin:'http://127.0.0.1:18083',verifyProviderBinding:async()=>{binding=true;return true;}},dependencies);
  simulate=true;
  if(latency===500){
    await assert.rejects(p.prepare(limits),/PROVISION_PENDING/);assert.equal(firstHttp,null);assert.equal(binding,false);assert.equal(f.files.has(DIR+'/form-provisioning.json'),false);assert.equal(b.resources().status,'PENDING');
    const before=f.events.length;await assert.rejects(p.prepare(limits));assert.equal(f.events.length,before);
  }else{
    const handoff=await p.prepare(limits);assert.equal(handoff.binding.intentId,'pi_TestOnly');assert.equal(firstHttp.path,'/api/admin/v1/auth/sessions');
    assert.equal(binding,true);assert.equal(b.resources().status,'LIVE');assert.ok(f.files.has(DIR+'/form-provisioning.json'));
    assert.ok(f.events.filter(e=>e.type==='health').every(e=>e.context.deadline<=limits.deadline));
    assert.ok(f.events.filter(e=>e.type==='process').every(e=>e.settings.timeout<=120000));
  }
});

for(const trigger of ['child abort','child expiry','startup abort'])test(`manual credential child ${trigger} cancels held proof and denies late exposure`,async()=>{
  const startup=new AbortController(),child=new AbortController();let release,entered,hold=false;
  const started=new Promise(resolveStarted=>{entered=resolveStarted;});
  const f=await manualCredentialLive(f=>{
    f.limits.signal=startup.signal;const execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{if(hold){hold=false;entered(args[2]);await new Promise(resolveRelease=>{release=resolveRelease;});}return execute(...args);};
  });
  let exposed=false,finished=false;hold=true;
  const result=f.adapter.withAdminCredentials(()=>{exposed=true;return true;},{deadline:11000,signal:child.signal}).then(()=>true,()=>false).finally(()=>{finished=true;});
  const settings=await started;
  const timer=[...f.timers.values()][0];
  if(trigger==='child abort')child.abort();else if(trigger==='startup abort')startup.abort();else {f.clock.now=11000;timer.callback();}
  await turn();const aborted=settings.signal.aborted,settledEarly=finished;release();const succeeded=await result;
  assert.equal(timer.ms,10000);assert.equal(settings.timeout,10000);assert.equal(aborted,true);assert.equal(settledEarly,false,'a held process remains owned until settlement');assert.equal(succeeded,false);assert.equal(exposed,false);assert.equal(f.adapter.resources().status,'PENDING');
  const before=f.events.length;await rejects(f.adapter.withAdminCredentials(()=>{exposed=true;return true;}),'ADMIN_PENDING');await rejects(f.adapter.verify({deadline:121000}),'VERIFY_PENDING');assert.equal(f.events.length,before);assert.equal(exposed,false);
});
for(const [name,limits] of [
  ['extended',()=>({deadline:121001})],['expired',()=>({deadline:1000})],['nonfinite',()=>({deadline:NaN})],['fractional',()=>({deadline:1000.5})],['missing',()=>({})],['null',()=>null],
  ['aborted',()=>({deadline:11000,signal:AbortSignal.abort()})],['invalid signal',()=>({deadline:11000,signal:{}})],
  ['inherited',()=>Object.create({deadline:11000})],['inherited signal',()=>Object.assign(Object.create({signal:AbortSignal.abort()}),{deadline:11000})],
  ['extra field',()=>({deadline:11000,renew:true})],['symbol field',()=>({deadline:11000,[Symbol('authority')]:true})],['array',()=>Object.assign([],{deadline:11000})],
  ['accessor',()=>Object.defineProperty({},'deadline',{get(){throw new Error('untrusted limit getter');}})],
])test(`manual credential child rejects ${name} authority before proofs and permanently denies exposure`,async()=>{
  const f=await manualCredentialLive();let exposed=false;const before=f.events.length;
  await rejects(f.adapter.withAdminCredentials(()=>{exposed=true;return true;},limits()),'ADMIN_PENDING');assert.equal(exposed,false);assert.equal(f.events.length,before);
  await rejects(f.adapter.withAdminCredentials(()=>{exposed=true;return true;}),'ADMIN_PENDING');assert.equal(f.events.length,before);assert.equal(exposed,false);
});
test('manual callback-only credentials retain original authority and arity',async()=>{
  const f=await manualCredentialLive();assert.equal(f.adapter.withAdminCredentials.length,1);let exposed=false;
  assert.equal(await f.adapter.withAdminCredentials(value=>{exposed=Object.keys(value).sort().join(',')==='password,username';return true;}),true);assert.equal(exposed,true);assert.equal(f.adapter.resources().status,'LIVE');
});
test('nonmanual callback-only credential contract ignores an extra child argument',async()=>{
  const f=await prepared();await f.adapter.persistWebhookSecret(SECRET);await f.adapter.start(LIMIT());let exposed=false;
  assert.equal(f.adapter.withAdminCredentials.length,1);
  assert.equal(await f.adapter.withAdminCredentials(()=>{exposed=true;return true;},{deadline:0,signal:AbortSignal.abort()}),true);assert.equal(exposed,true);
});
test('manual credential child without a signal denies callback when proof reaches its deadline',async()=>{
  let expire=false;const f=await manualCredentialLive(f=>{const health=f.dependencies.health;f.dependencies.health=async(...args)=>{const result=await health(...args);if(expire&&args[0]==='http://127.0.0.1:18082/health')f.clock.now=11000;return result;};});
  expire=true;let exposed=false;await rejects(f.adapter.withAdminCredentials(()=>{exposed=true;return true;},{deadline:11000}),'ADMIN_PENDING');assert.equal(exposed,false);assert.equal(f.adapter.resources().status,'PENDING');
});
test('manual credential limits capture rejects accessors without evaluating them',async()=>{
  const f=await manualCredentialLive();let reads=0,exposed=false;
  await rejects(f.adapter.withAdminCredentials(()=>{exposed=true;return true;},Object.defineProperty({},'deadline',{get(){reads++;return 11000;}})),'ADMIN_PENDING');assert.equal(reads,0);assert.equal(exposed,false);
});
test('manual provision child expiry aborts real verification and retains unsettled cleanup ownership',async()=>{
  let entered,release,hold=false;const started=new Promise(resolveStarted=>{entered=resolveStarted;});
  const f=await manualCredentialLive(f=>{const execute=f.dependencies.execute;f.dependencies.execute=async(...args)=>{if(hold){hold=false;entered(args[2]);await new Promise(resolveRelease=>{release=resolveRelease;});}return execute(...args);};});
  let exposed=false;const b=f.adapter,bootstrap={...b,withAdminCredentials:(callback,...args)=>{hold=true;return b.withAdminCredentials(value=>{exposed=true;return callback(value);},...args);}};
  const p=await createManualPaymentProvisioner({env:f.options.env,bootstrap,browserOrigin:'http://127.0.0.1:18083',verifyProviderBinding:async()=>true},{...f.dependencies,fetch:forbidden});
  const result=p.prepare(f.limits).then(()=>true,()=>false);const settings=await started;
  [...f.timers.values()].find(timer=>timer.ms===10000).callback();assert.equal(await result,false);await turn();const aborted=settings.signal.aborted;
  await assert.rejects(p.close({deadline:f.limits.deadline+120000}));await rejects(b.stop({deadline:f.limits.deadline+120000}),'OPERATION_BUSY');
  release();await turn();assert.equal(aborted,true);assert.equal(exposed,false);assert.equal(f.files.has(DIR+'/form-provisioning.json'),false);assert.equal(b.resources().status,'PENDING');
  const before=f.events.length;await assert.rejects(p.prepare(f.limits));await rejects(b.withAdminCredentials(()=>{exposed=true;return true;}),'ADMIN_PENDING');assert.equal(f.events.length,before);assert.equal(exposed,false);
});

for(const [field,mutate] of [
  ['source',f=>{f.changedSource=true;}],['image',f=>{f.containers.get(f.adapter.resources().containers.backend.id).Image=image(999);}],['network',f=>{f.containers.get(f.adapter.resources().containers.backend.id).Networks[PROJECT+'-network'].NetworkID=id(999);}],
  ['volume',f=>{f.volumes.get(PROJECT+'-issuer').CreatedAt='2026-10-17T12:00:00Z';}],['mount',f=>{f.containers.get(f.adapter.resources().containers.backend.id).Mounts[0].RW=true;}],
  ['port',f=>{f.containers.get(f.adapter.resources().containers.backend.id).Ports['8080/tcp'][0].HostPort='19000';}],['health',f=>{f.changedHealth=true;}],
])test(`manual credential composition retains complete ${field} proof after provider binding`,async()=>{
  const f=await manualCredentialLive(f=>{
    const execute=f.dependencies.execute,health=f.dependencies.health;
    f.dependencies.execute=async(...args)=>{const result=await execute(...args);return f.changedSource&&args[0]==='/usr/bin/git'&&args[1].includes('rev-parse')?{stdout:'f'.repeat(40)+'\n',stderr:''}:result;};
    f.dependencies.health=async(...args)=>await health(...args)&&!f.changedHealth;
  });
  let exposed=false;const b=f.adapter,bootstrap={...b,withAdminCredentials:(callback,...args)=>b.withAdminCredentials(value=>{exposed=true;return callback(value);},...args)};
  const p=await createManualPaymentProvisioner({env:f.options.env,bootstrap,browserOrigin:'http://127.0.0.1:18083',verifyProviderBinding:async()=>{mutate(f);return true;}},{...f.dependencies,fetch:forbidden});
  await assert.rejects(p.prepare(f.limits),/PROVISION_PENDING/);assert.equal(exposed,false);assert.equal(f.files.has(DIR+'/form-provisioning.json'),false);assert.equal(b.resources().status,'PENDING');
});

for(const boundary of ['before capability','inside callback'])for(const [field,mutate] of [
  ['mode',s=>s.mode='FORM_DIAGNOSTIC'],['listener',s=>s.webhookReady=false],['status',s=>s.status='PENDING'],['run',s=>s.runId='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'],
  ['source',s=>s.expectedSource='f'.repeat(40)],['daemon',s=>s.daemon='unix:///foreign.sock'],['project',s=>s.project='foreign'],['site',s=>s.siteKey='foreign'],['ports',s=>s.ports.backend++],
  ...['backend','demo','receiver'].map(name=>[name+' endpoint',s=>s.endpoints[name]='http://127.0.0.1:19000']),
  ...['backend','demo-site','callback-receiver'].map(name=>[name+' owner',s=>s.containers[name].id=id(999)]),
])test(`manual credential snapshot refuses changed ${field} ${boundary}`,async()=>{
  let afterBinding=false,substitute=false;
  const f=await manualCredentialLive(f=>{const health=f.dependencies.health;f.dependencies.health=async(...args)=>{const result=await health(...args);if(afterBinding&&args[0]==='http://127.0.0.1:18082/health')substitute=true;return result;};});
  const b=f.adapter,bootstrap={...b,resources:()=>{const safe=structuredClone(b.resources());if(substitute)mutate(safe);return safe;}};
  const eventStart=f.events.length;
  const p=await createManualPaymentProvisioner({env:f.options.env,bootstrap,browserOrigin:'http://127.0.0.1:18083',verifyProviderBinding:async()=>{afterBinding=true;if(boundary==='before capability')substitute=true;return true;}},{...f.dependencies,fetch:forbidden});
  await assert.rejects(p.prepare(f.limits),/PROVISION_PENDING/);assert.equal(f.files.has(DIR+'/form-provisioning.json'),false);
  const before=f.events.length;await assert.rejects(p.prepare(f.limits));assert.equal(f.events.length,before);
  const health=f.events.slice(eventStart).filter(e=>e.type==='health'&&e.context.deadline===11000);
  assert.equal(health.length,boundary==='before capability'?3:6,'initial proof stays; callback proof completes exactly once before snapshot rejection');
});

test('real prepare-only runtime completes one synthetic bootstrap/provision/provider lifecycle and closes only owned resources',async()=>{
  const f=formFixture(),r=await createFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT();
  const h=await r.prepare(limits);assert.deepEqual(Object.keys(h).sort(),['endpoint','expectedSource','runId','session']);
  assert.equal(h.session.version,2);assert.equal(h.session.state.price.taxMode,'NONE');assert.ok(Object.isFrozen(h.session.state.price));
  f.resume();assert.equal(await r.verifyNoConfirmation(limits),true);assert.equal(await r.close({...limits,deadline:limits.deadline+120000}),true);
  assert.equal(f.http.filter(v=>v.path.endsWith('/checkouts')).length,1);
  assert.equal(f.http.filter(v=>v.path.startsWith('/v1/payment_intents/')).length,2);
  assert.equal(f.http.some(v=>/confirm|refund|customer|campaign|callback|inspection|client-session/.test(v.path)),false);
  assert.equal(f.events.some(v=>v.type==='process'&&(v.args.includes('rm')||v.args.includes('stripe'))),false);
});

const manualEnv={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
function manualFixture(){const f=formFixture('http://127.0.0.1:18083');return {...f,options:{...f.options,env:{...manualEnv},manualPort:18083}};}
for(const attempts of [0,1])test('manual runtime preserves Docker ownership, gateway provisioning and final '+attempts+' Attempt proof',async()=>{
  assert.equal(typeof formRuntime.createManualFormDiagnosticRuntime,'function');const f=manualFixture();
  const r=await formRuntime.createManualFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT(),h=await r.prepare(limits);
  assert.deepEqual(Object.keys(h).sort(),['assetEndpoint','endpoint','expectedSource','runId','session']);
  assert.equal(h.endpoint,'http://127.0.0.1:18083');assert.equal(h.assetEndpoint,'http://127.0.0.1:18081');
  assert.equal(h.session.state.amountMinor,2000);assert.equal(h.session.state.currency,'EUR');assert.equal(h.session.state.price.taxMode,'NONE');
  assert.equal(f.http.find(v=>v.path==='/api/admin/v1/sites'&&v.method==='POST').body.allowed_origins[0],'http://127.0.0.1:18083');
  assert.deepEqual(f.http.find(v=>v.path.endsWith('/checkout-return-urls')&&v.method==='PUT').body,
    {origin:'http://127.0.0.1:18083',return_url:'http://127.0.0.1:18083/payment-return.html',expected_version:-1});
  assert.equal(f.http.find(v=>v.path.endsWith('/checkouts')).settings.headers.Origin,'http://127.0.0.1:18083');
  const ledger=JSON.parse(f.files.get(DIR+'/runtime.json').contents);assert.equal(ledger.endpoints.demo,'http://127.0.0.1:18081');assert.deepEqual(ledger.ports,PORTS);
  assert.equal(f.events.find(v=>v.type==='preflight').input.env.PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC,'true');
  assert.equal(f.events.find(v=>v.type==='preflight').input.env.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC,undefined);
  if(attempts)f.resume();assert.equal(await r.verifyNoConfirmation(limits),true);
  const reads=f.http.length;await assert.rejects(r.verifyNoConfirmation(limits));await assert.rejects(r.prepare(limits));assert.equal(f.http.length,reads);
  assert.equal(await r.close({...limits,deadline:limits.deadline+120000}),true);await assert.rejects(r.close({...limits,deadline:limits.deadline+120000}));
  assert.equal(f.http.filter(v=>v.path.endsWith('/checkouts')).length,1);
  assert.equal(f.http.some(v=>/confirm|refund|customer|campaign|callback|inspection|client-session/.test(v.path)),false);
});
for(const [name,mutate] of [['second Attempt',f=>{f.resume();f.detail.attempts.push({...f.detail.attempts[0],id:'00000000-0000-4000-8000-000000000099',attempt_number:2});}],
  ['unknown provider state',f=>f.intent.status='processing'],['charge',f=>f.intent.latest_charge='ch_unexpected'],['binding changed',f=>f.operation.reference='pi_Foreign']])
test('manual runtime rejects '+name+' and consumes final proof',async()=>{
  assert.equal(typeof formRuntime.createManualFormDiagnosticRuntime,'function');const f=manualFixture(),r=await formRuntime.createManualFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT();
  await r.prepare(limits);mutate(f);await assert.rejects(r.verifyNoConfirmation(limits));const reads=f.http.length;
  await assert.rejects(r.verifyNoConfirmation(limits));assert.equal(f.http.length,reads);
});
test('manual runtime refuses renewed active and cleanup deadlines without further private operations',async()=>{
  assert.equal(typeof formRuntime.createManualFormDiagnosticRuntime,'function');const f=manualFixture(),r=await formRuntime.createManualFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT();
  await r.prepare(limits);const reads=f.http.length;await assert.rejects(r.verifyNoConfirmation({...limits,deadline:limits.deadline+1}));
  await assert.rejects(r.verifyNoConfirmation(limits));assert.equal(f.http.length,reads);
  await assert.rejects(r.close({...limits,deadline:limits.deadline+120001}));
});

test('full and automatic bootstrap reject manual flag before configuration or dependencies',async()=>{
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  for(const [factory,env] of [[createOwnedSandboxBootstrap,{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'}],
    [bootstrapModule.createOwnedFormDiagnosticBootstrap,{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}]])
    await assert.rejects(factory({env:{...env,PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true'},get ports(){touched++;throw null;}},trap),{message:'stripe_sandbox_bootstrap_OPT_IN_REQUIRED'});
  assert.equal(touched,0);
});

test('runtime consumes a rejected renewed final proof and cannot retry it with the original deadline',async()=>{
  const f=formFixture(),r=await createFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT();await r.prepare(limits);f.resume();
  const reads=f.http.length;await assert.rejects(r.verifyNoConfirmation({...limits,deadline:limits.deadline+1}));
  await assert.rejects(r.verifyNoConfirmation(limits));assert.equal(f.http.length,reads);
});
test('runtime never certifies a final zero-Attempt state as completed form preparation',async()=>{
  const f=formFixture(),r=await createFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT();await r.prepare(limits);
  await assert.rejects(r.verifyNoConfirmation(limits));await assert.rejects(r.verifyNoConfirmation(limits));
});
for(const phase of ['baseline','final'])
test('I2 runtime rejects an extra owned-Site payment at '+phase+' despite valid selected detail',async()=>{
  const f=formFixture(),r=await createFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT();
  if(phase==='final'){await r.prepare(limits);f.resume();}
  f.payments.push({...f.detail.payment,payment_id:'00000000-0000-4000-8000-000000000099'});
  await assert.rejects(phase==='baseline'?r.prepare(limits):r.verifyNoConfirmation(limits));
});
test('runtime abort cannot hang on an unsettled private boundary and cleanup stays unconfirmed',async()=>{
  const f=formFixture();f.dependencies.preflight=async()=>new Promise(()=>{});const controller=new AbortController();
  const r=await createFormDiagnosticRuntime(f.options,f.deps),limits={...LIMIT(),signal:controller.signal};
  const preparing=r.prepare(limits);await turn();controller.abort();
  const result=await Promise.race([preparing.then(()=>false,()=>true),new Promise(resolve=>setTimeout(()=>resolve(false),50))]);
  assert.equal(result,true);await assert.rejects(r.close({deadline:limits.deadline+120000}));
});
test('I1 abort during late provisioning lock open cannot certify cleanup and the late handle closes',async()=>{
  const f=formFixture(),original=f.deps.provision.open,controller=new AbortController();let release,entered;
  const gate=new Promise(resolve=>release=resolve),reached=new Promise(resolve=>entered=resolve);
  f.deps.provision.open=async(path,...args)=>{if(path===DIR+'/form-provisioning.lock'){entered();await gate;}return original(path,...args);};
  const r=await createFormDiagnosticRuntime(f.options,f.deps),limits={...LIMIT(),signal:controller.signal},preparing=r.prepare(limits);
  await reached;controller.abort();await assert.rejects(preparing);await assert.rejects(r.close({deadline:limits.deadline+120000}));
  release();await turn();await turn();
  assert.equal(f.events.filter(e=>e.type==='close'&&e.path===DIR+'/form-provisioning.lock').length,1);
  assert.equal(f.http.some(e=>e.path.endsWith('/auth/sessions')),false);
});
test('I1 aborted backend body read permanently prevents cleanup certification',async()=>{
  const f=formFixture(),fetcher=f.deps.provision.fetch,controller=new AbortController();let final=false,entered,cancelled=0;
  const reached=new Promise(resolve=>entered=resolve);
  f.deps.provision.fetch=async(url,settings)=>{if(final&&url.includes('/api/v1/admin/payments/'))return new Response(new ReadableStream({pull(){entered();},cancel(){cancelled++;}}));return fetcher(url,settings);};
  const r=await createFormDiagnosticRuntime(f.options,f.deps),limits={...LIMIT(),signal:controller.signal};await r.prepare(limits);f.resume();final=true;
  const proof=r.verifyNoConfirmation(limits);await reached;controller.abort();await assert.rejects(proof);
  await assert.rejects(r.close({deadline:limits.deadline+120000}));await turn();assert.equal(cancelled,1);
  assert.equal(f.events.filter(e=>e.type==='close'&&e.path===DIR+'/form-provisioning.lock').length,1);
});
test('I1 late provider fetch remains uncertain and disposes its eventual response body',async()=>{
  const f=formFixture(),fetcher=f.deps.provider.fetch,controller=new AbortController();let final=false,release,entered,cancelled=0;
  const gate=new Promise(resolve=>release=resolve),reached=new Promise(resolve=>entered=resolve);
  f.deps.provider.fetch=async(url,settings)=>{if(final){entered();await gate;return new Response(new ReadableStream({cancel(){cancelled++;}}));}return fetcher(url,settings);};
  const r=await createFormDiagnosticRuntime(f.options,f.deps),limits={...LIMIT(),signal:controller.signal};await r.prepare(limits);f.resume();final=true;
  const proof=r.verifyNoConfirmation(limits);await reached;controller.abort();await assert.rejects(proof);await turn();
  await assert.rejects(r.close({deadline:limits.deadline+120000}));release();await turn();await turn();assert.equal(cancelled,1);
});
test('I1 late lock acquisition after the original cleanup ceiling causes no late disposal dispatch',async()=>{
  const f=formFixture(),original=f.deps.provision.open,controller=new AbortController();let release,entered,time=Date.now();
  const gate=new Promise(resolve=>release=resolve),reached=new Promise(resolve=>entered=resolve);
  f.deps.now=f.dependencies.now=f.deps.provision.now=f.deps.provider.now=()=>time;
  f.deps.provision.open=async(path,...args)=>{if(path===DIR+'/form-provisioning.lock'){entered();await gate;}return original(path,...args);};
  const r=await createFormDiagnosticRuntime(f.options,f.deps),limits={deadline:time+120000,signal:controller.signal},preparing=r.prepare(limits);
  await reached;controller.abort();await assert.rejects(preparing);await assert.rejects(r.close({deadline:limits.deadline+120000}));
  time=limits.deadline+120001;release();await turn();await turn();
  assert.equal(f.events.some(e=>e.type==='close'&&e.path===DIR+'/form-provisioning.lock'),false);
  await assert.rejects(r.close({deadline:time+120000}));
});
test('I1 unsettled response disposal remains uncertain after every business intent is acknowledged',async()=>{
  const f=formFixture(),fetcher=f.deps.provision.fetch,controller=new AbortController();let final=false,entered,release;
  const gate=new Promise(resolve=>release=resolve),reached=new Promise(resolve=>entered=resolve);
  f.deps.provision.fetch=async(url,settings)=>{if(final&&url.includes('/api/v1/admin/payments/'))return new Response(new ReadableStream({pull(){entered();},cancel(){return gate;}}));return fetcher(url,settings);};
  const r=await createFormDiagnosticRuntime(f.options,f.deps),limits={...LIMIT(),signal:controller.signal};await r.prepare(limits);f.resume();final=true;
  const proof=r.verifyNoConfirmation(limits);await reached;controller.abort();await assert.rejects(proof);await turn();
  await assert.rejects(r.close({deadline:limits.deadline+120000}));release();await turn();await turn();
  await assert.rejects(r.close({deadline:limits.deadline+120000}));
});
test('runtime rejects redirected backend proof even with an otherwise valid body',async()=>{
  const f=formFixture(),fetcher=f.deps.provision.fetch;f.deps.provision.fetch=async(url,settings)=>{const response=await fetcher(url,settings);
    if(url.includes('/api/v1/admin/payments/'))Object.defineProperty(response,'redirected',{value:true});return response;};
  const r=await createFormDiagnosticRuntime(f.options,f.deps);await assert.rejects(r.prepare(LIMIT()));
});
test('runtime refuses an unacknowledged checkout response without replay and cannot confirm cleanup',async()=>{
  const f=formFixture(),fetcher=f.deps.provision.fetch;f.deps.provision.fetch=async(url,settings)=>{const response=await fetcher(url,settings);if(url.endsWith('/checkouts'))throw new Error('opaque synthetic uncertain response');return response;};
  const r=await createFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT();await assert.rejects(r.prepare(limits));await assert.rejects(r.prepare(limits));
  assert.equal(f.http.filter(v=>v.path.endsWith('/checkouts')).length,1);
  await assert.rejects(r.close({...limits,deadline:limits.deadline+120000}));
  const ledger=JSON.parse(f.files.get(DIR+'/form-provisioning.json').contents);assert.equal(ledger.intents.at(-1).status,'DISPATCHING');
});
test('runtime rejects foreign owned resources before final HTTP and never stops that foreign container',async()=>{
  const f=formFixture(),r=await createFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT();await r.prepare(limits);f.resume();
  const backend=[...f.containers.values()].find(v=>v.Name.endsWith('-backend'));backend.Labels['payment.sandbox.run']='foreign';const count=f.http.length;
  await assert.rejects(r.verifyNoConfirmation(limits));assert.equal(f.http.length,count);await assert.rejects(r.close({...limits,deadline:limits.deadline+120000}));assert.equal(backend.State.Running,true);
});
for(const suffix of ['/payment-accounts','/sites','/products','/prices','/publish'])
test('runtime latches unknown '+suffix+' mutation outcome with its original durable intent',async()=>{
  const f=formFixture(),fetcher=f.deps.provision.fetch;let dispatched=0;
  f.deps.provision.fetch=async(url,settings)=>{const response=await fetcher(url,settings);if(settings.method==='POST'&&url.endsWith(suffix)){dispatched++;throw new Error('unknown synthetic response');}return response;};
  const r=await createFormDiagnosticRuntime(f.options,f.deps),limits=LIMIT();await assert.rejects(r.prepare(limits));await assert.rejects(r.prepare(limits));assert.equal(dispatched,1);
  const ledger=JSON.parse(f.files.get(DIR+'/form-provisioning.json').contents);assert.equal(ledger.intents.at(-1).status,'DISPATCHING');
  assert.equal(f.http.some(v=>v.path.endsWith('/checkouts')),false);await assert.rejects(r.close({...limits,deadline:limits.deadline+120000}));
});

function typedInspectFixture(kind,{backendUser='payment'}={}) {
  const f=fixture(),execute=f.dependencies.execute,proofs=[];
  f.dependencies.execute=async(file,args,settings)=>{
    const result=await execute(file,args,settings);
    if(args[4]!==kind||args[5]!=='inspect')return result;
    const format=args[7],value=JSON.parse(result.stdout),typed={ID:value.Id};
    assert.equal(args[6],'--format');
    const proof={selector:args.at(-1),format};proofs.push(proof);
    if(kind==='image') {
      const user=args.at(-1)===PROJECT+'-backend:latest'?backendUser:value.User;
      // Only these two image contracts are characterized, not a Go template interpreter.
      if(format==='{"Id":{{json .Id}},"User":{{json .Config.User}}}') {
        const raw={Id:typed.ID,Config:user===''?{}:{User:user}};
        proof.fallback=true;
        if(!Object.hasOwn(raw.Config,'User')) {proof.missingRawUser=true;throw new Error('synthetic_strict_raw_image_user_missing');}
        result.stdout=JSON.stringify({Id:raw.Id,User:raw.Config.User});
      } else {
        assert.equal(format,'{"Id":{{json .ID}},"User":{{json .Config.User}}}');
        result.stdout=JSON.stringify({Id:typed.ID,User:user});
      }
    } else {
      // Literal command-boundary contracts protect typed projection, not source spelling.
      assert.ok(format.includes('"Id":{{json .ID}}'),'typed '+kind+' identity selector required');
      if(kind==='network')assert.equal(format,'{"Id":{{json .ID}},"Name":{{json .Name}},"Labels":{{json .Labels}},"Driver":{{json .Driver}},"Scope":{{json .Scope}}}');
      else {
        assert.ok(format.includes('"Health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}""{{end}}'));
        const state={...value.State};if(state.Health==='')delete state.Health;
        proof.absentHealth=!Object.hasOwn(state,'Health');
        value.State={...state,Health:state.Health??''};
      }
      result.stdout=JSON.stringify({...value,Id:typed.ID});
    }
    return result;
  };
  f.proofs=proofs;return f;
}

const diagnostic=f=>JSON.parse(f.files.get(DIR+'/runtime.json').contents).buildDiagnostic;
async function terminal(f) {
  const count=f.events.length;
  for(const [invoke,code] of [[()=>f.adapter.buildOnly(LIMIT()),'BUILD_PENDING'],[()=>f.adapter.prepare(LIMIT()),'PREPARE_PENDING'],
    [()=>f.adapter.start(LIMIT()),'START_PENDING'],[()=>f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING'],
    [()=>f.adapter.verifyForwardTarget(),'FORWARD_PENDING'],[()=>f.adapter.withAdminCredentials(forbidden),'ADMIN_PENDING'],
    [()=>f.adapter.verify(LIMIT()),'VERIFY_PENDING'],[()=>f.adapter.stop(LIMIT()),'STOP_PENDING']])await rejects(invoke(),code);
  assert.equal(f.events.length,count);
}

test('buildOnly is inert until invoked and ends after one synthetic build and two image proofs',async()=>{
  const f=await setup();assert.equal(f.events.length,0);assert.equal(await f.adapter.buildOnly(LIMIT()),true);
  const commands=f.events.filter(e=>e.type==='process'),builds=commands.filter(e=>e.args.includes('build'));
  assert.equal(builds.length,1);assert.deepEqual(builds[0].args.slice(-4),['build','--quiet','backend','demo-site']);
  assert.deepEqual(commands.filter(e=>e.args[4]==='image'&&e.args[5]==='inspect').map(e=>e.args.at(-1)),[PROJECT+'-backend:latest',PROJECT+'-demo-site:latest']);
  assert.equal(commands.length,9);assert.equal(commands.some(e=>['pull','create','start','stop','rm'].includes(e.args[4])||e.args.includes('create')||stoppedCompose(e)),false);
  assert.equal(f.events.some(e=>e.type==='crypto'||e.type==='random'||e.type==='health'||e.path===USERFILE||e.type==='unlink'),false);
  assert.equal(f.files.has(DIR+'/issuer.jwks.json'),false);assert.equal(f.files.has(DIR+'/app.env'),false);assert.equal(f.files.has(DIR+'/cli'),false);
  assert.equal(f.adapter.resources().status,'BUILD_ONLY_COMPLETE');assert.equal(f.adapter.resources().webhookReady,false);
  assert.deepEqual(diagnostic(f),{stage:'FINAL_RECORD',result:'SUCCEEDED',category:'NONE'});
  assert.equal(f.events.filter(e=>e.type==='close'&&e.path===DIR+'/owner.lock').length,1);assert.ok(f.files.has(DIR+'/owner.lock'));
  assert.equal(f.files.get(DIR+'/buildx').mode&0o7777,0o700);
  for(const event of commands) {
    assert.deepEqual(event.settings.env,{PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',DOCKER_CONFIG:'/private/var/empty',BUILDX_CONFIG:DIR+'/buildx'});
    assert.ok(f.events.slice(0,f.events.indexOf(event)).some(e=>e.type==='sync'&&e.path===DIR+'/buildx'));
  }
  await terminal(f);
});

// Catches imageProof .ID -> .Id: strict raw fallback cannot read omitted optional User.
test('typed Docker image identity accepts empty and nonempty User without raw fallback',async()=>{
  for(const user of ['','payment']) {
    const f=typedInspectFixture('image',{backendUser:user});
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
    try {assert.equal(await f.adapter.buildOnly(LIMIT()),true);}
    catch(error) {assert.fail(error.message+'; proof='+JSON.stringify(diagnostic(f))+'; boundary='+JSON.stringify(f.proofs));}
    assert.equal(f.adapter.resources().images.backend.user,user);
    assert.equal(f.adapter.resources().images.backend.id,'sha256:0000000000000000000000000000000000000000000000000000000000000001');
    assert.equal(f.adapter.resources().images['demo-site'].user,'101:101');
    assert.equal(f.proofs.length,2);assert.equal(f.proofs.some(proof=>proof.fallback),false);
    assert.equal(f.events.some(event=>event.path===USERFILE),false);
    await terminal(f);
  }
});

// Catches NETWORK_FORMAT .ID -> .Id while exercising recorded ownership proofs.
test('typed Docker network identity projects Id through preparation and repeated ownership proof',async()=>{
  const f=typedInspectFixture('network');f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
  assert.equal(await f.adapter.prepare(LIMIT()),true);
  assert.equal(f.adapter.resources().network.Id,f.network.Id);
  assert.equal(await f.adapter.verifyForwardTarget(),true);
  assert.ok(f.proofs.length>1);
  assert.ok(f.proofs.some(proof=>proof.selector===PROJECT+'-network'));
  assert.ok(f.proofs.some(proof=>proof.selector===f.network.Id));
  assert.equal(f.events.some(event=>event.path===USERFILE),false);
});

// Catches CONTAINER_FORMAT .ID -> .Id; optional absent Health must still project "".
test('typed Docker container identity projects Id with legitimately absent optional health',async()=>{
  const f=typedInspectFixture('container');f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
  assert.equal(await f.adapter.prepare(LIMIT()),true);
  const metadata=f.adapter.resources();
  for(const service of [...SERVICES,'issuer-installer','issuer-probe']) {
    const value=f.containers.get(metadata.containers[service].id);
    assert.ok(value);assert.equal(metadata.containers[service].id,value.Id);
  }
  assert.ok(f.proofs.some(proof=>proof.absentHealth));
  assert.equal(await f.adapter.verifyForwardTarget(),true);
  assert.equal(f.events.some(event=>event.path===USERFILE),false);
});

// Catches removal of JSON whitespace before the first Go action (the invalid {{{ opening).
test('container format separates the literal JSON opening from its first Go action through owned forwarding',async()=>{
  const f=typedInspectFixture('container'),execute=f.dependencies.execute;let inspections=0,malformedOpening=false;
  f.dependencies.execute=async(file,args,settings)=>{
    if(args[4]==='container'&&args[5]==='inspect') {
      const format=args[7];assert.equal(args[6],'--format');inspections++;
      // Characterize only the observed installed-parser rejection, not a Go interpreter.
      if(format.startsWith('{{{')) {malformedOpening=true;throw Object.assign(new Error('synthetic_invalid_go_action_opening'),{code:64});}
      assert.match(format,/^\{[ \t\r\n]+\{\{/);
    }
    return execute(file,args,settings);
  };
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
  try {assert.equal(await f.adapter.prepare(LIMIT()),true);}
  catch(error) {assert.fail(error.message+'; malformedOpening='+malformedOpening);}
  const metadata=f.adapter.resources();assert.equal(metadata.status,'PREPARED');
  for(const service of [...SERVICES,'issuer-installer','issuer-probe'])assert.equal(metadata.containers[service].id,[...f.containers.values()].find(value=>value.Name==='/'+PROJECT+'-'+service).Id);
  const before=inspections;assert.equal(await f.adapter.verifyForwardTarget(),true);assert.ok(inspections>before);
  assert.equal(metadata.webhookReady,false);assert.equal(f.events.some(event=>event.type==='health'||event.path===USERFILE),false);
  let admin=false;await rejects(f.adapter.withAdminCredentials(()=>{admin=true;}),'ADMIN_PENDING');assert.equal(admin,false);
});

// Catches rejecting Docker's two exact CAP_ aliases or broadening/reordering the allowlist.
test('helper CapAdd admits bare canonical and mixed aliases with no probe additions',async t=>{
  for(const [name,capAdd] of [['bare',['CHOWN','DAC_OVERRIDE']],['canonical',['CAP_CHOWN','CAP_DAC_OVERRIDE']],
    ['mixed CHOWN',['CHOWN','CAP_DAC_OVERRIDE']],['mixed DAC_OVERRIDE',['CAP_CHOWN','DAC_OVERRIDE']]])await t.test(name,async()=>{
    const f=fixture(),execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{
      const result=await execute(...args);
      if(args[1][4]==='container'&&args[1][5]==='inspect') {
        const value=JSON.parse(result.stdout);if(value.Name.endsWith('-issuer-installer')){value.CapAdd=capAdd;result.stdout=JSON.stringify(value);}
      }
      return result;
    };
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);assert.equal(await f.adapter.prepare(LIMIT()),true);
    assert.equal(f.adapter.resources().containers['issuer-probe'].status,'exited');assert.equal(await f.adapter.verifyForwardTarget(),true);
    assert.equal(f.adapter.resources().webhookReady,false);assert.equal(f.events.some(event=>event.type==='health'||event.path===USERFILE),false);
  });
});

test('helper CapAdd aliases retain exact capability and CapDrop gates before helper start or authority',async()=>{
  const installer='issuer-installer',probe='issuer-probe';
  const cases=[
    ['missing',installer,{CapAdd:['CAP_CHOWN']}],['extra',installer,{CapAdd:['CAP_CHOWN','CAP_DAC_OVERRIDE','CAP_SYS_ADMIN']}],
    ['duplicate',installer,{CapAdd:['CAP_CHOWN','CAP_CHOWN']}],['duplicate alias',installer,{CapAdd:['CHOWN','CAP_CHOWN','DAC_OVERRIDE']}],
    ['wrong order',installer,{CapAdd:['CAP_DAC_OVERRIDE','CAP_CHOWN']}],['privileged',installer,{CapAdd:['CHOWN','SYS_ADMIN']}],
    ['unknown prefixed privilege',installer,{CapAdd:['CAP_CHOWN','CAP_SYS_ADMIN']}],['unknown case',installer,{CapAdd:['cap_chown','DAC_OVERRIDE']}],
    ['unknown prefix',installer,{CapAdd:['CAP_CAP_CHOWN','DAC_OVERRIDE']}],
    ...[null,0,true,{}].map(value=>['nonstring '+JSON.stringify(value),installer,{CapAdd:[value,'DAC_OVERRIDE']}]),
    ['nonarray',installer,{CapAdd:'CHOWN,DAC_OVERRIDE'}],
    ...[['CAP_ALL'],['all'],[],['ALL','CHOWN']].map(value=>['CapDrop '+JSON.stringify(value),installer,{CapDrop:value}]),
    ['probe bare addition',probe,{CapAdd:['CHOWN']}],['probe canonical addition',probe,{CapAdd:['CAP_CHOWN']}],
  ];
  for(const [name,service,fields] of cases) {
    const f=fixture(),execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{
      const result=await execute(...args);
      if(args[1][4]==='container'&&args[1][5]==='inspect') {
        const value=JSON.parse(result.stdout);if(value.Name.endsWith('-'+service))result.stdout=JSON.stringify({...value,...fields});
      }
      return result;
    };
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');
    const helper=[...f.containers.values()].find(value=>value.Name.endsWith('-'+service));assert.ok(helper,name);
    assert.equal(f.events.some(event=>event.type==='process'&&event.args[4]==='container'&&event.args[5]==='start'&&event.args.at(-1)===helper.Id),false,name);
    assert.equal(f.events.some(stoppedCompose),false,name);
    assert.equal(f.events.some(event=>event.type==='health'||event.path===USERFILE),false,name);assert.equal(f.adapter.resources().webhookReady,false,name);
    const before=f.events.length;let admin=false;
    await rejects(f.adapter.start(LIMIT()),'START_PENDING');await rejects(f.adapter.verifyForwardTarget(),'FORWARD_PENDING');
    await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');await rejects(f.adapter.verify(LIMIT()),'VERIFY_PENDING');
    await rejects(f.adapter.withAdminCredentials(()=>{admin=true;}),'ADMIN_PENDING');assert.equal(admin,false,name);assert.equal(f.events.length,before,name);
  }
});

test('image proof still rejects missing or malformed User and identity fields',async()=>{
  for(const proof of [{Id:image(1)},{Id:image(1),User:null},{Id:image(1),User:0},{User:''},
    {Id:null,User:''},{Id:'sha256:1234',User:''},{Id:id(1),User:''}]) {
    const f=fixture(),execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{const result=await execute(...args);if(args[1][4]==='image'&&args[1][5]==='inspect')result.stdout=JSON.stringify(proof);return result;};
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
    await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
    assert.deepEqual(diagnostic(f),{stage:'BACKEND_IMAGE_PROOF',result:'FAILED',category:'INVALID_IMAGE_PROOF'});
    assert.equal(f.adapter.resources().images.backend,undefined);await terminal(f);
  }
});

test('Buildx directory rejects mode, symlink, UID and canonical substitution before any Docker dispatch',async()=>{
  for(const mutate of [f=>{f.files.get(DIR+'/buildx').mode=0o40750;},f=>{f.files.get(DIR+'/buildx').symlink=true;},
    f=>{f.files.get(DIR+'/buildx').uid++;},f=>{f.files.get(DIR+'/buildx').canonicalForeign=true;}]) {
    const f=fixture(),mkdir=f.dependencies.mkdir;f.dependencies.mkdir=async(...args)=>{await mkdir(...args);if(args[0]===DIR+'/buildx')mutate(f);};
    const realpath=f.dependencies.realpath;f.dependencies.realpath=async path=>f.files.has(DIR+'/buildx')&&f.files.get(DIR+'/buildx').canonicalForeign?DIR+'/foreign':realpath(path);
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
    assert.equal(f.events.filter(e=>e.type==='process').length,0);await terminal(f);
  }
});

test('Buildx identity is proved again at the build boundary after resource checks',async()=>{
  const f=fixture(),execute=f.dependencies.execute;
  f.dependencies.execute=async(...args)=>{const value=await execute(...args);if(args[1][4]==='image'&&args[1][5]==='ls'&&args[1].includes('reference='+PROJECT+'-demo-site:latest'))f.files.get(DIR+'/buildx').ino++;return value;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
  assert.equal(f.events.some(e=>e.type==='process'&&e.args.includes('build')),false);await terminal(f);
});

test('Buildx fsync refuses a substituted open directory descriptor before Docker',async()=>{
  const f=fixture(),open=f.dependencies.open;
  f.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0]===DIR+'/buildx')file.stat=async()=>({...f.stat(f.files.get(DIR+'/buildx')),ino:999999});return file;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
  assert.equal(f.events.some(e=>e.type==='process'),false);await terminal(f);
});

test('untrusted build rejection is UNKNOWN without reading its properties or exposing any raw data',async()=>{
  const f=fixture(),execute=f.dependencies.execute;let getters=0;
  f.dependencies.execute=async(...args)=>{const value=await execute(...args);if(args[1].includes('build'))throw new Proxy({},{get(){getters++;throw null;}});return value;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
  assert.equal(getters,0);assert.deepEqual(diagnostic(f),{stage:'BUILD_COMMAND',result:'UNCONFIRMED',category:'UNKNOWN'});
  assert.equal(f.events.filter(e=>e.type==='process'&&e.args.includes('build')).length,1);await terminal(f);
});

test('arbitrary image dependency rejections remain UNKNOWN even when null or SyntaxError shaped',async()=>{
  for(const rejection of [null,new SyntaxError('synthetic-private'),new Proxy({},{getPrototypeOf:forbidden,get:forbidden})]) {
    const f=fixture(),execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{if(args[1][4]==='image'&&args[1][5]==='inspect')throw rejection;return execute(...args);};
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
    assert.deepEqual(diagnostic(f),{stage:'BACKEND_IMAGE_PROOF',result:'UNCONFIRMED',category:'UNKNOWN'});await terminal(f);
  }
});

test('synchronous spawn failure is terminal and retains only a normalized category',async()=>{
  const f=fixture(),execute=f.dependencies.execute;delete f.dependencies.execute;
  f.dependencies.execFile=(file,args,settings,done)=>{
    if(args.includes('build'))throw Object.assign(new Error('synthetic-private-spawn'),{code:'ENOENT'});
    const child=new EventEmitter();queueMicrotask(async()=>{const value=await execute(file,args,settings);done(null,value.stdout,value.stderr);child.emit('close',0,null);});return child;
  };
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
  assert.deepEqual(diagnostic(f),{stage:'BUILD_COMMAND',result:'FAILED',category:'SPAWN_FAILURE'});await terminal(f);
});

test('buildOnly deadline expiry cannot settle while actual child close remains unknown',async()=>{
  const f=fixture(),execute=f.dependencies.execute,child=new EventEmitter();let callback,entered,settings,settled=false;
  const started=new Promise(resolveStarted=>{entered=resolveStarted;});delete f.dependencies.execute;
  f.dependencies.execFile=(file,args,options,done)=>{
    if(args.includes('build')) {callback=done;settings=options;entered();return child;}
    const immediate=new EventEmitter();queueMicrotask(async()=>{const value=await execute(file,args,options);done(null,value.stdout,value.stderr);immediate.emit('close',0,null);});return immediate;
  };
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);const operation=f.adapter.buildOnly(LIMIT()).finally(()=>{settled=true;}),rejected=rejects(operation,'BUILD_PENDING');
  await started;[...f.timers.values()][0].callback();callback(Object.assign(new Error('synthetic-private-timeout'),{code:'ABORT_ERR'}),'','');await turn();
  assert.equal(settings.signal.aborted,true);assert.equal(settled,false);assert.equal(diagnostic(f).result,'RUNNING');
  child.emit('close',null,'SIGTERM');await rejected;assert.equal(f.adapter.resources().buildDiagnostic.category,'TIMEOUT');await terminal(f);
});

test('buildOnly differentiates invalid image proofs and output limits after the build',async()=>{
  for(const [service,stdout,category] of [['backend','{"Id":"invalid","User":"payment"}','INVALID_IMAGE_PROOF'],
    ['demo-site','not JSON','INVALID_IMAGE_PROOF'],['backend','x'.repeat(65537),'OUTPUT_LIMIT']]) {
    const f=fixture(),execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{const value=await execute(...args);if(args[1][4]==='image'&&args[1][5]==='inspect'&&args[1].at(-1)===PROJECT+'-'+service+':latest')value.stdout=stdout;return value;};
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
    assert.deepEqual(diagnostic(f),{stage:service==='backend'?'BACKEND_IMAGE_PROOF':'DEMO_IMAGE_PROOF',result:'FAILED',category});await terminal(f);
  }
});

test('native child failures normalize only safe metadata and wait for actual close before recording',async()=>{
  for(const [metadata,category,exitCode] of [[{code:17},'NONZERO_EXIT',17],[{code:'ERR_CHILD_PROCESS_STDIO_MAXBUFFER'},'OUTPUT_LIMIT'],
    [{killed:true,signal:'SIGTERM'},'TIMEOUT'],[{code:'ABORT_ERR'},'ABORT'],[{code:'ENOENT'},'SPAWN_FAILURE'],[{},'UNKNOWN']]) {
    const f=fixture(),execute=f.dependencies.execute,child=new EventEmitter();let callback,entered,settled=false;
    const started=new Promise(resolveStarted=>{entered=resolveStarted;});delete f.dependencies.execute;
    f.dependencies.execFile=(file,args,settings,done)=>{
      if(args.includes('build')) {f.events.push({type:'process',file,args,settings});callback=done;entered();return child;}
      const immediate=new EventEmitter();queueMicrotask(async()=>{const value=await execute(file,args,settings);done(null,value.stdout,value.stderr);immediate.emit('close',0,null);});return immediate;
    };
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
    const operation=f.adapter.buildOnly(LIMIT()).finally(()=>{settled=true;}),rejected=rejects(operation,'BUILD_PENDING');await started;
    callback(Object.assign(new Error('synthetic-private-error'),metadata),'synthetic-private-output','synthetic-private-stderr');await turn();
    assert.equal(settled,false);assert.equal(f.events.some(e=>e.type==='process'&&e.args[5]==='inspect'),false);
    child.emit('close',exitCode??null,metadata.signal??null);await rejected;
    assert.deepEqual(diagnostic(f),{stage:'BUILD_COMMAND',result:category==='UNKNOWN'?'UNCONFIRMED':'FAILED',category,...(exitCode===undefined?{}:{exitCode})});
    assert.equal(JSON.stringify(f.adapter.resources()).includes('synthetic-private'),false);await terminal(f);
  }
});

test('successful callback cannot start image proofs or settle buildOnly until close',async()=>{
  const f=fixture(),execute=f.dependencies.execute,child=new EventEmitter();let callback,entered,settled=false;
  const started=new Promise(resolveStarted=>{entered=resolveStarted;});delete f.dependencies.execute;
  f.dependencies.execFile=(file,args,settings,done)=>{
    if(args.includes('build')) {callback=done;execute(file,args,settings).then(()=>entered());return child;}
    const immediate=new EventEmitter();queueMicrotask(async()=>{const value=await execute(file,args,settings);done(null,value.stdout,value.stderr);immediate.emit('close',0,null);});return immediate;
  };
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);const operation=f.adapter.buildOnly(LIMIT()).finally(()=>{settled=true;});await started;
  callback(null,'','');await turn();assert.equal(settled,false);assert.equal(f.events.some(e=>e.type==='process'&&e.args[5]==='inspect'),false);
  child.emit('close',0,null);assert.equal(await operation,true);await terminal(f);
});

test('aborted buildOnly waits for dispatched work then refuses proofs and all promotion',async()=>{
  const f=fixture(),execute=f.dependencies.execute,controller=new AbortController();let release,entered;
  const started=new Promise(resolveStarted=>{entered=resolveStarted;});
  f.dependencies.execute=async(...args)=>{if(args[1].includes('build')) {entered();await new Promise(resolveRelease=>{release=resolveRelease;});}return execute(...args);};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);let settled=false;
  const operation=f.adapter.buildOnly({...LIMIT(),signal:controller.signal}).finally(()=>{settled=true;}),rejected=rejects(operation,'BUILD_PENDING');await started;
  controller.abort();await turn();assert.equal(settled,false);release();await rejected;
  assert.equal(f.adapter.resources().buildDiagnostic.category,'ABORT');assert.equal(f.adapter.resources().status,'PENDING');
  assert.equal(f.events.some(e=>e.type==='process'&&e.args[5]==='inspect'),false);await terminal(f);
});

test('failed admission or invalid deadline consumes buildOnly without private IO and cannot be promoted',async()=>{
  for(const limits of [{deadline:NaN},{deadline:Date.now()-1},LIMIT()]) {
    const f=fixture();f.dependencies.preflight=async()=>({status:'PENDING'});f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
    await rejects(f.adapter.buildOnly(limits),'BUILD_PENDING');assert.equal(f.events.length,0);await terminal(f);
  }
});

test('failed final record never reports build success and retains the lock file and both images',async()=>{
  const f=fixture(),open=f.dependencies.open;
  f.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0].includes('/runtime.json.tmp-')) {
    const write=file.writeFile;file.writeFile=async bytes=>{if(JSON.parse(bytes).buildDiagnostic?.result==='SUCCEEDED')throw null;return write(bytes);};
  }return file;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
  assert.equal(f.adapter.resources().status,'PENDING');assert.equal(f.adapter.resources().buildDiagnostic.result,'UNCONFIRMED');
  assert.notEqual(diagnostic(f).result,'SUCCEEDED');assert.ok(f.files.has(DIR+'/owner.lock'));assert.equal(f.images.size,2);await terminal(f);
});

test('checked durable build completion remains authoritative when the deadline ticks before outer return',async()=>{
  const f=fixture(),open=f.dependencies.open,deadline=5000;let now=1000,finalSynced=false,checks=0,boundaryEvents;
  f.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0]===DIR) {
    const sync=file.sync;file.sync=async()=>{await sync();if(f.files.has(DIR+'/runtime.json')&&diagnostic(f)?.result==='SUCCEEDED')finalSynced=true;};
  }return file;};
  f.dependencies.now=()=>{
    if(finalSynced&&++checks===2) {boundaryEvents=f.events.length;now=deadline;return deadline-1;}
    return now;
  };
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
  assert.equal(await f.adapter.buildOnly({deadline}),true);
  assert.equal(now,deadline);assert.equal(checks,2);assert.equal(f.events.length,boundaryEvents);
  const retained=JSON.parse(f.files.get(DIR+'/runtime.json').contents);
  assert.equal(retained.status,'BUILD_ONLY_COMPLETE');assert.equal(f.adapter.resources().status,'BUILD_ONLY_COMPLETE');
  assert.deepEqual(retained.buildDiagnostic,{stage:'FINAL_RECORD',result:'SUCCEEDED',category:'NONE'});
  assert.deepEqual(f.adapter.resources().buildDiagnostic,retained.buildDiagnostic);await terminal(f);
});

test('expiry before final record rename rejects without late IO and retains unconfirmed evidence',async()=>{
  const f=fixture(),open=f.dependencies.open,deadline=5000;let now=1000,boundaryEvents;
  f.dependencies.now=()=>now;
  f.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0].includes('/runtime.json.tmp-')) {
    const write=file.writeFile;file.writeFile=async bytes=>{await write(bytes);if(JSON.parse(bytes).buildDiagnostic?.result==='SUCCEEDED') {now=deadline;boundaryEvents=f.events.length;}};
  }return file;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly({deadline}),'BUILD_PENDING');
  // Closing the already-open temporary file is the only filesystem action after expiry.
  assert.deepEqual(f.events.slice(boundaryEvents).map(e=>e.type),['close']);
  assert.equal(f.adapter.resources().status,'PENDING');assert.equal(f.adapter.resources().buildDiagnostic.result,'UNCONFIRMED');
  assert.notEqual(diagnostic(f).result,'SUCCEEDED');await terminal(f);
});

test('failed final directory sync records UNCONFIRMED within the original remaining budget',async()=>{
  const f=fixture(),open=f.dependencies.open;let failed=false;
  f.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0]===DIR) {
    const sync=file.sync;file.sync=async()=>{
      if(!failed&&f.files.has(DIR+'/runtime.json')&&diagnostic(f)?.result==='SUCCEEDED'){failed=true;throw null;}await sync();
    };
  }return file;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
  assert.equal(failed,true);assert.deepEqual(diagnostic(f),{stage:'FINAL_RECORD',result:'UNCONFIRMED',category:'UNKNOWN'});await terminal(f);
});

test('owner lock close failure records an unconfirmed terminal result and never removes the file',async()=>{
  const f=fixture(),open=f.dependencies.open;
  f.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0]===DIR+'/owner.lock')file.close=async()=>{throw null;};return file;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.buildOnly(LIMIT()),'BUILD_PENDING');
  assert.equal(diagnostic(f).result,'UNCONFIRMED');assert.equal(f.files.has(DIR+'/owner.lock'),true);await terminal(f);
});

test('constructor rejects invalid input before accessing dependencies and snapshots approved values',async()=>{
  for(const update of [{runId:'2f3f49b1-df07-41b1-b978-bc884b390813'},{expectedSource:'bad'},{ports:{...PORTS,backend:PORTS.demo}},{daemon:'tcp://localhost:2375'},{approvedBinding:{externalAccountId:'acct_other',apiVersion:'2026-09-30.endive'}},{env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',DEBUG:'1'}}]) {
    const f=fixture(update);let getters=0;
    await rejects(createOwnedSandboxBootstrap(f.options,new Proxy({},{get(){getters++;throw null;}})),'INPUTS_CONFIGURATION');assert.equal(getters,0);
  }
  const f=await setup();f.options.ports.backend=65530;f.options.approvedBinding.externalAccountId='acct_mutated';f.options.runId='mutated';
  const resources=f.adapter.resources();assert.equal(resources.runId,RUN);assert.equal(resources.ports.backend,18080);assert.ok(Object.isFrozen(resources.ports));assert.ok(Object.isFrozen(f.adapter));assert.equal(f.events.length,0);
});

test('R1 failure and invalid deadlines stop before all private access, writes, crypto and processes',async()=>{
  const f=await setup();f.dependencies.preflight=async()=>({status:'READY'});
  await rejects(f.adapter.prepare({deadline:NaN}),'PREPARE_PENDING');assert.equal(f.events.length,0);
  const g=fixture();g.dependencies.preflight=async()=>({status:'PENDING'});g.adapter=await createOwnedSandboxBootstrap(g.options,g.dependencies);
  await rejects(g.adapter.prepare(LIMIT()),'PREPARE_PENDING');assert.equal(g.events.length,0);
  await rejects(g.adapter.prepare(LIMIT()),'PREPARE_PENDING');assert.equal(g.events.length,0);
});

test('prepare builds without credentials and records UID-safe issuer proof before creating five stopped services',async()=>{
  const f=await prepared(),metadata=f.adapter.resources();
  assert.equal(metadata.status,'PREPARED');assert.equal(metadata.cliDirectory,DIR+'/cli');assert.equal(metadata.siteKey,'s5a-11111111222243338444555555555555');
  assert.deepEqual(Object.keys(metadata.containers).sort(),[...SERVICES,'issuer-installer','issuer-probe'].sort());
  assert.equal(f.events.filter(e=>e.type==='read'&&e.path===USERFILE).length,0);
  const build=f.events.find(e=>e.type==='process'&&e.args.includes('build'));
  assert.ok(build);assert.ok(build.args.includes('--quiet'));assert.ok(!build.args.includes(ROOT+'/infrastructure/compose.stripe-sandbox.yaml'));
  assert.ok(f.events.indexOf(build)<f.events.findIndex(e=>e.type==='open'&&e.path===DIR+'/issuer.jwks.json'));
  for(const event of f.events.filter(e=>e.type==='process')) {
    assert.equal(event.file,'/Applications/Docker.app/Contents/Resources/bin/docker');assert.deepEqual(event.args.slice(0,4),['--config','/private/var/empty','--host','unix:///Users/test/docker.sock']);
    assert.equal(event.settings.cwd,ROOT);assert.equal(event.settings.shell,false);assert.equal(event.settings.maxBuffer,65536);assert.ok(event.settings.signal instanceof AbortSignal);
    assert.deepEqual(Object.keys(event.settings.env).sort(),['BUILDX_CONFIG','DOCKER_CONFIG','LANG','LC_ALL','PATH']);
    assert.equal(event.settings.env.DOCKER_CONFIG,'/private/var/empty');assert.equal(event.settings.env.BUILDX_CONFIG,DIR+'/buildx');assert.ok(event.settings.timeout<=1200000);
  }
  const createCalls=f.events.filter(stoppedCompose);
  assert.equal(createCalls.length,1);assert.deepEqual(createCalls[0].args.slice(createCalls[0].args.indexOf('up')),
    ['up','--no-start','--no-deps','--no-build','--pull','never','postgres','mailpit','backend','demo-site','callback-receiver']);
  for(const service of SERVICES) {
    const proof=metadata.containers[service],actual=f.containers.get(proof.id);
    assert.equal(proof.status,'created');assert.equal(actual.State.Running,false);assert.equal(actual.State.StartedAt,'0001-01-01T00:00:00Z');
    assert.deepEqual(actual.Networks,{[PROJECT+'-network']:{NetworkID:metadata.network.Id}});assert.deepEqual(actual.Mounts,mounts(service));
    assert.deepEqual(actual.PortBindings,mapping(service));assert.deepEqual(actual.Labels,cLabels(service));
  }
  assert.equal(f.events.filter(e=>e.type==='process'&&e.args[4]==='container'&&e.args[5]==='start').length,2);
  assert.equal(f.events.some(e=>e.type==='health'||e.path===USERFILE),false);
  const installer=f.events.find(e=>e.type==='process'&&e.args.includes('create')&&e.args.includes(PROJECT+'-issuer-installer'));
  assert.ok(installer.args.includes('--read-only'));assert.ok(installer.args.includes('CHOWN'));assert.ok(installer.args.includes('DAC_OVERRIDE'));
  assert.ok(installer.args.at(-1).includes('chown 10001:10001'));assert.ok(installer.args.at(-1).includes('chmod 0600'));
  const probe=f.events.find(e=>e.type==='process'&&e.args.includes('create')&&e.args.includes(PROJECT+'-issuer-probe'));
  assert.ok(probe.args.includes('10001:10001'));assert.ok(probe.args.at(-1).includes('stat'));
  assert.equal(f.files.get(DIR+'/issuer.jwks.json').mode&0o777,0o600);
  for(const path of [DIR,DIR+'/cli',DIR+'/cli/home',DIR+'/cli/config',DIR+'/cli/config/stripe'])assert.equal(f.files.get(path).mode&0o777,0o700);
  const ledger=JSON.parse(f.files.get(DIR+'/runtime.json').contents);assert.equal(ledger.webhookReady,false);assert.ok(!JSON.stringify(ledger).includes('sk_test_'));
  const creation=f.events.indexOf(createCalls[0]);
  assert.ok(f.events.slice(0,creation).some(e=>e.type==='process'&&e.args[5]==='wait'&&e.args.at(-1)===metadata.containers['issuer-probe'].id));
  assert.equal(ledger.intents.find(i=>i.action==='CREATE_STOPPED').status,'ACKNOWLEDGED');
  let invoked=false;await rejects(f.adapter.withAdminCredentials(()=>{invoked=true;return true;}),'ADMIN_PENDING');assert.equal(invoked,false);
  await rejects(f.adapter.verify(LIMIT()),'VERIFY_PENDING');assert.equal(f.events.some(e=>e.type==='health'||e.path===USERFILE),false);
});

test('stopped forwarding admission proves planned ownership and a free port without granting admin or readiness',async()=>{
  const f=await prepared();assert.equal(await f.adapter.verifyForwardTarget(),true);
  assert.equal(f.adapter.resources().status,'PREPARED');assert.equal(f.events.filter(e=>e.type==='health').length,0);
  let invoked=false;await rejects(f.adapter.withAdminCredentials(()=>{invoked=true;}),'ADMIN_PENDING');assert.equal(invoked,false);
  const g=await prepared();const backend=g.containers.get(g.adapter.resources().containers.backend.id);backend.PortBindings['8080/tcp'][0].HostIp='0.0.0.0';
  await rejects(g.adapter.verifyForwardTarget(),'FORWARD_PENDING');
});

test('secret callback preserves user bytes and durably saves both stores before acknowledging',async()=>{
  const f=await prepared();assert.equal(await f.adapter.persistWebhookSecret(SECRET),true);
  assert.equal(f.files.get(USERFILE).contents.toString(),USERBYTES.replace('PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET=\r\n','PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET='+SECRET+'\r\n'));
  const final=f.files.get(DIR+'/final.env').contents.toString();assert.ok(final.includes('PAYMENT_STRIPE_SANDBOX_SECRET_KEY=sk_test_A00112233445566778899\n'));assert.ok(final.includes(SECRET));
  assert.equal(f.adapter.resources().webhookReady,true);
  const userRename=f.events.findIndex(e=>e.type==='rename'&&e.to===USERFILE),finalWrite=f.events.findIndex(e=>e.type==='write'&&e.path===DIR+'/final.env');
  assert.ok(userRename>=0&&finalWrite>userRename);assert.ok(f.events.slice(userRename+1).some(e=>e.type==='sync'&&e.path===ROOT+'/infrastructure'));
  assert.ok(f.events.slice(finalWrite+1).some(e=>e.type==='sync'&&e.path===DIR+'/final.env'));
  const safe=JSON.stringify(f.adapter.resources());for(const value of [SECRET,'sk_test_','pk_test_','privateKey','password','PAYMENT_DB_PASSWORD'])assert.ok(!safe.includes(value));
  const ledger=JSON.parse(f.files.get(DIR+'/runtime.json').contents);assert.equal(ledger.webhookReady,true);
});

test('secret validation rejects unequal, duplicate, unknown, live and synthetic assignments without changing user bytes',async()=>{
  for(const bytes of [USERBYTES.replace('WEBHOOK_SECRET=','WEBHOOK_SECRET=whsec_A99999999999999999999'),USERBYTES+'PAYMENT_STRIPE_SANDBOX_SECRET_KEY=sk_test_duplicate\n',
    USERBYTES+'OTHER=value\n',USERBYTES.replace('sk_test_','sk_live_'),USERBYTES.replace('sk_test_A00112233445566778899','sk_test_synthetic')]) {
    const f=await prepared();f.files.get(USERFILE).contents=Buffer.from(bytes);
    await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');assert.equal(f.files.get(USERFILE).contents.toString(),bytes);assert.equal(f.adapter.resources().webhookReady,false);
  }
  const g=await prepared();await rejects(g.adapter.persistWebhookSecret('sk_test_notWebhook'),'PERSIST_PENDING');assert.equal(g.events.filter(e=>e.type==='read'&&e.path===USERFILE).length,0);
});

test('equal webhook secret leaves user inode and bytes unchanged while saving final private environment',async()=>{
  const f=await prepared(),entry=f.files.get(USERFILE);entry.contents=Buffer.from(USERBYTES.replace('WEBHOOK_SECRET=','WEBHOOK_SECRET='+SECRET));const before=Buffer.from(entry.contents);
  assert.equal(await f.adapter.persistWebhookSecret(SECRET),true);assert.equal(f.files.get(USERFILE),entry);assert.deepEqual(entry.contents,before);
  assert.equal(f.events.filter(e=>e.type==='rename'&&e.to===USERFILE).length,0);assert.ok(f.files.has(DIR+'/final.env'));
});

test('start retires only the never-started backend, records its replacement, then starts exact IDs in dependency order',async()=>{
  const f=await prepared(),before=f.adapter.resources(),old=before.containers.backend.id;
  assert.equal(await f.adapter.persistWebhookSecret(SECRET),true);assert.equal(await f.adapter.start(LIMIT()),true);
  const metadata=f.adapter.resources(),fresh=metadata.containers.backend.id;assert.notEqual(fresh,old);assert.equal(metadata.retiredBackend.id,old);assert.ok(!f.containers.has(old));
  assert.deepEqual(f.events.filter(e=>e.type==='process'&&e.args[4]==='container'&&e.args[5]==='start').slice(-5).map(e=>e.args.at(-1)),SERVICES.map(s=>metadata.containers[s].id));
  const creates=f.events.filter(stoppedCompose);assert.equal(creates.length,2);
  assert.deepEqual(creates[1].args.slice(creates[1].args.indexOf('up')),['up','--no-start','--no-deps','--no-build','--pull','never','backend']);
  assert.equal(creates[1].args.at(creates[1].args.indexOf('-f',creates[1].args.indexOf('-f')+2)+1),DIR+'/owned.yaml');
  assert.ok(creates[1].args.includes(ROOT+'/infrastructure/compose.stripe-sandbox.yaml'));assert.ok(creates[1].args.includes(DIR+'/final.env'));
  for(const [service,container] of Object.entries(before.containers))if(service!=='backend')assert.equal(metadata.containers[service].id,container.id);
  assert.equal(f.containers.size,7);assert.deepEqual([...f.containers.keys()].sort(),Object.values(metadata.containers).map(c=>c.id).sort());
  assert.deepEqual(metadata.images,before.images);assert.deepEqual(metadata.network,before.network);assert.deepEqual(metadata.volumes,before.volumes);
  assert.equal(await f.adapter.verify(LIMIT()),true);assert.equal(await f.adapter.verifyForwardTarget(),true);
  let credentials;assert.equal(await f.adapter.withAdminCredentials(value=>{credentials=value;return true;}),true);
  assert.deepEqual(Object.keys(credentials).sort(),['password','username']);assert.ok(Object.isFrozen(credentials));
  assert.equal(f.events.filter(e=>e.type==='process'&&e.args.includes('container')&&e.args.includes('rm')).length,1);
  await rejects(f.adapter.start(LIMIT()),'START_PENDING');
});

test('cleanup verifies exact identities, stops only recorded IDs and retains all resource and private evidence',async()=>{
  const f=await prepared();await f.adapter.persistWebhookSecret(SECRET);await f.adapter.start(LIMIT());const before=f.adapter.resources();
  assert.equal(await f.adapter.stop(LIMIT()),true);assert.equal(f.adapter.resources().status,'STOPPED');
  assert.equal(f.containers.size,7);assert.equal(f.volumes.size,2);assert.equal(f.network.Id,before.network.Id);assert.ok(f.files.has(DIR+'/issuer.jwks.json'));assert.ok(f.files.has(DIR+'/runtime.json'));
  const calls=f.events.filter(e=>e.type==='process'&&e.args[4]==='container'&&e.args[5]==='stop');assert.deepEqual(calls.map(e=>e.args.at(-1)).sort(),SERVICES.map(s=>before.containers[s].id).sort());
  assert.ok(f.events.some(e=>e.type==='close'&&e.path===DIR+'/owner.lock'));
  const g=await prepared(),backend=g.containers.get(g.adapter.resources().containers.backend.id);backend.Labels['payment.sandbox.run']='foreign';
  await rejects(g.adapter.stop(LIMIT()),'STOP_PENDING');assert.equal(g.events.filter(e=>e.type==='process'&&e.args[5]==='stop').length,0);
});

// Break: clearing the failure latch during cleanup or treating persisted PENDING as failed stop proof.
test('successful stop after an operation failure preserves PENDING resources and durable ledger and refuses later work',async()=>{
  const f=await manualCredentialLive();
  await rejects(f.adapter.withAdminCredentials(async()=>{throw new Error('synthetic operation failure');}),'ADMIN_PENDING');
  assert.equal(f.adapter.resources().status,'PENDING');
  assert.equal(await f.adapter.stop({deadline:f.limits.deadline+120000}),true);
  assert.equal(f.adapter.resources().status,'PENDING');
  const ledger=JSON.parse(f.files.get(DIR+'/runtime.json').contents);
  assert.equal(ledger.status,'PENDING');
  assert.ok(ledger.intents.every(intent=>intent.status==='ACKNOWLEDGED'));
  assert.equal([...f.containers.values()].every(container=>container.State.Running===false),true);
  assert.ok(f.events.some(event=>event.type==='close'&&event.path===DIR+'/owner.lock'));
  const before=f.events.length;
  await rejects(f.adapter.start(f.limits),'START_PENDING');
  await rejects(f.adapter.verify(f.limits),'VERIFY_PENDING');
  assert.equal(f.events.length,before);
  assert.equal(JSON.parse(f.files.get(DIR+'/runtime.json').contents).status,'PENDING');
});

test('issuer chmod precedes chown so its minimal capabilities suffice, and Docker label key order is immaterial',async()=>{
  const f=fixture(),execute=f.dependencies.execute;
  f.dependencies.execute=async(...args)=>{
    const result=await execute(...args);
    if(args[1][5]==='inspect'&&['network','volume'].includes(args[1][4])) {
      const value=JSON.parse(result.stdout);value.Labels=Object.fromEntries(Object.entries(value.Labels).sort());result.stdout=JSON.stringify(value);
    }
    return result;
  };
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);assert.equal(await f.adapter.prepare(LIMIT()),true);
  const command=f.events.find(e=>e.type==='process'&&e.args.includes(PROJECT+'-issuer-installer')&&e.args.includes('create')).args.at(-1);
  assert.ok(command.indexOf('chmod 0600')<command.indexOf('chown 10001:10001'));
});

test('every private creation checks exclusive nofollow flags, inode ownership, file sync and directory sync before acknowledgement',async()=>{
  const f=await prepared();await f.adapter.persistWebhookSecret(SECRET);
  for(const event of f.events.filter(e=>e.type==='open'&&(e.flags&constants.O_CREAT))) {
    assert.equal(event.flags&(constants.O_EXCL|constants.O_NOFOLLOW),constants.O_EXCL|constants.O_NOFOLLOW);assert.equal(event.mode,0o600);
    const index=f.events.indexOf(event),next=f.events.slice(index+1);assert.ok(next.some(e=>e.type==='sync'&&e.path===event.path));assert.ok(next.some(e=>e.type==='sync'&&e.path===dirname(event.path)));
  }
  for(const event of f.events.filter(e=>e.type==='rename')) {
    const before=f.events.slice(0,f.events.indexOf(event));assert.ok(before.some(e=>e.type==='sync'&&e.path===event.from));assert.ok(before.some(e=>e.type==='close'&&e.path===event.from));
  }
  const g=fixture(),open=g.dependencies.open;
  g.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0]===DIR+'/owner.lock')file.stat=async()=>({...g.stat(g.files.get(args[0])),uid:process.getuid()+1});return file;};
  g.adapter=await createOwnedSandboxBootstrap(g.options,g.dependencies);await rejects(g.adapter.prepare(LIMIT()),'PREPARE_PENDING');
  assert.equal(g.events.filter(e=>e.type==='process').length,0);assert.ok(g.events.some(e=>e.type==='close'&&e.path===DIR+'/owner.lock'));
});

test('existing run, symlinked run parent and missing build ignore coverage refuse all Docker writes',async()=>{
  for(const mutate of [f=>f.files.set(DIR,{...f.files.get(ROOT),contents:Buffer.alloc(0)}),
    f=>{f.files.get(ROOT+'/backend/.dockerignore').contents=Buffer.from('**/*.env.local\n');},
    f=>{f.files.get(ROOT).symlink=true;}]) {
    const f=fixture();mutate(f);f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
    await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');assert.equal(f.events.filter(e=>e.type==='process').length,0);
  }
});

test('unknown create outcome preserves DISPATCHING evidence, blocks later work and never adopts or retries',async()=>{
  const f=fixture(),execute=f.dependencies.execute;let failed=false;
  f.dependencies.execute=async(...args)=>{const value=await execute(...args);if(args[1][4]==='network'&&args[1][5]==='create'){failed=true;throw new Proxy({},{get(){throw new Error('must_not_traverse');}});}return value;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');assert.equal(failed,true);
  const ledger=JSON.parse(f.files.get(DIR+'/runtime.json').contents);assert.equal(ledger.intents.at(-1).status,'DISPATCHING');assert.equal(f.adapter.resources().status,'PENDING');
  const count=f.events.length;await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');assert.equal(f.events.length,count);await rejects(f.adapter.stop(LIMIT()),'STOP_PENDING');
  assert.ok(f.files.has(DIR+'/owner.lock'));assert.equal(f.containers.size,0);
});

test('issuer probe failure prevents service creation and every user Stripe file read',async()=>{
  const f=fixture(),execute=f.dependencies.execute;
  f.dependencies.execute=async(...args)=>{const result=await execute(...args);if(args[1][5]==='wait'&&[...f.containers.values()].find(c=>c.Id===args[1].at(-1))?.Name.endsWith('issuer-probe'))result.stdout='1\n';return result;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');
  assert.equal(f.events.filter(stoppedCompose).length,0);
  await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');assert.equal(f.events.filter(e=>e.path===USERFILE).length,0);
});

test('foreign image, extra network, issuer mount and either Mailpit binding invalidate stopped ownership',async()=>{
  for(const mutate of [f=>{f.containers.get(f.adapter.resources().containers.backend.id).Image=image(999);},
    f=>{f.containers.get(f.adapter.resources().containers.backend.id).Networks.foreign={NetworkID:id(999)};},
    f=>{f.containers.get(f.adapter.resources().containers.backend.id).Mounts[0].Name='foreign';},
    f=>{delete f.containers.get(f.adapter.resources().containers.mailpit.id).PortBindings['1025/tcp'];},
    f=>{f.containers.get(f.adapter.resources().containers.mailpit.id).PortBindings['8025/tcp'].push({HostIp:'::1',HostPort:'12345'});},
    f=>{f.containers.get(f.adapter.resources().containers.backend.id).State.StartedAt='2026-10-16T12:00:00Z';},
    f=>{f.volumes.get(PROJECT+'-issuer').CreatedAt='2026-10-17T12:00:00Z';},
    f=>{f.containers.set(id(999),{Id:id(999)});},
    f=>{f.containers.get(f.adapter.resources().containers['demo-site'].id).StripeKeyPresent=true;}]) {
    const f=await prepared();mutate(f);await rejects(f.adapter.verifyForwardTarget(),'FORWARD_PENDING');assert.equal(f.events.filter(e=>e.type==='health').length,0);
  }
});

test('unsafe user file metadata, duplicate writer lock and concurrent bytes fail before replacement',async()=>{
  for(const mutate of [f=>{f.files.get(USERFILE).mode=0o100640;},f=>{f.files.get(USERFILE).nlink=2;},f=>{f.files.get(USERFILE).uid++;},f=>{f.files.get(USERFILE).symlink=true;},
    f=>{f.files.set(ROOT+'/test-results/stripe-sandbox/user-env.lock',{...f.files.get(USERFILE),contents:Buffer.alloc(0)});}]) {
    const f=await prepared();mutate(f);await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');assert.equal(f.events.filter(e=>e.type==='rename'&&e.to===USERFILE).length,0);
  }
  const f=fixture(),open=f.dependencies.open;
  f.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0]===DIR+'/stripe-user.env.tmp'){const sync=file.sync;file.sync=async()=>{await sync();f.files.get(USERFILE).contents=Buffer.from(USERBYTES+'# concurrent\n');};}return file;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await f.adapter.prepare(LIMIT());await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');
  assert.equal(f.files.get(USERFILE).contents.toString(),USERBYTES+'# concurrent\n');assert.equal(f.files.has(DIR+'/final.env'),false);assert.equal(f.adapter.resources().webhookReady,false);
});

test('failure syncing final private store never acknowledges the webhook or allows replacement',async()=>{
  const f=fixture(),open=f.dependencies.open;
  f.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0]===DIR+'/final.env')file.sync=async()=>{throw null;};return file;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await f.adapter.prepare(LIMIT());await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');
  assert.equal(f.adapter.resources().webhookReady,false);await rejects(f.adapter.start(LIMIT()),'START_PENDING');assert.equal(f.events.filter(e=>e.type==='process'&&e.args[5]==='rm').length,0);
});

test('callbacks retain original prepare deadline and cancellation after prepare returns',async()=>{
  const f=fixture();let now=1000;f.dependencies.now=()=>now;f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
  await f.adapter.prepare({deadline:5000});now=5000;await rejects(f.adapter.verifyForwardTarget(),'FORWARD_PENDING');
  const g=await setup(),controller=new AbortController();await g.adapter.prepare({...LIMIT(),signal:controller.signal});controller.abort();
  const before=g.events.length;await rejects(g.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');assert.equal(g.events.length,before);
});

test('abort during a command awaits its completion and prevents every subsequent action',async()=>{
  const f=fixture(),controller=new AbortController(),execute=f.dependencies.execute;let release,entered;
  const started=new Promise(resolveStarted=>{entered=resolveStarted;});
  f.dependencies.execute=async(...args)=>{if(args[1].includes('build')) {entered(args[2]);await new Promise(resolveRelease=>{release=resolveRelease;});}return execute(...args);};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);let finished=false;
  const prepare=f.adapter.prepare({...LIMIT(),signal:controller.signal}).finally(()=>{finished=true;});const settings=await started;controller.abort();await turn();
  assert.equal(settings.signal.aborted,true);assert.equal(finished,false);await rejects(f.adapter.verifyForwardTarget(),'OPERATION_BUSY');release();await rejects(prepare,'PREPARE_PENDING');
  assert.ok(f.events.filter(e=>e.type==='process').at(-1).args.includes('build'));assert.equal(f.files.has(DIR+'/issuer.jwks.json'),false);
});

test('builtin raw exec wrapper waits for close after abort callback and never falls through to a real process',async()=>{
  const f=fixture(),controller=new AbortController(),child=new EventEmitter();let callback,settings,entered;
  const started=new Promise(resolveStarted=>{entered=resolveStarted;});delete f.dependencies.execute;
  f.dependencies.execFile=(file,args,options,done)=>{assert.equal(file,'/Applications/Docker.app/Contents/Resources/bin/docker');settings=options;callback=done;entered();return child;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);let finished=false;
  const prepare=f.adapter.prepare({...LIMIT(),signal:controller.signal}).finally(()=>{finished=true;});await started;controller.abort();callback(new Error('raw private error'),'secret','secret');await turn();
  assert.equal(settings.signal.aborted,true);assert.equal(finished,false);child.emit('close',null,'SIGTERM');await rejects(prepare,'PREPARE_PENDING');assert.equal(f.events.filter(e=>e.type==='read'&&e.path===USERFILE).length,0);
});

test('oversized process output and nested errors are redacted and permanently stop the instance',async()=>{
  for(const broken of [()=>({stdout:'x'.repeat(65537),stderr:''}),()=>{throw new Proxy({},{get(){throw new Error('private');}});}]) {
    const f=fixture();f.dependencies.execute=async()=>broken();f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
    await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');assert.equal(f.adapter.resources().status,'PENDING');assert.equal(f.files.has(DIR+'/issuer.jwks.json'),false);
  }
});

test('preexisting project resources and owned image tags refuse the build before any Docker mutation',async()=>{
  for(const kind of ['network','image','ps']) {
    const f=fixture(),execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{const result=await execute(...args);if(args[1][4]===kind&&(args[1].includes('ls')||kind==='ps'))result.stdout=id(999)+'\n';return result;};
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');
    assert.equal(f.events.filter(e=>e.type==='process'&&e.args.includes('build')).length,0);
  }
});

test('synthetic merged Compose proof verifies exact override mounts and all six ports before service creation',async()=>{
  const f=await prepared(),config=f.events.find(e=>e.type==='process'&&e.args.includes('config')&&e.args[4]==='compose');
  assert.ok(config);assert.ok(config.args.includes(DIR+'/build.env'));assert.ok(!config.args.includes(DIR+'/app.env'));
  const g=fixture(),execute=g.dependencies.execute;
  g.dependencies.execute=async(...args)=>{const result=await execute(...args);if(args[1][4]==='compose'&&args[1].includes('config')){const body=JSON.parse(result.stdout);body.services.backend.volumes.push({type:'bind',source:'/foreign',target:'/app/local-fixtures/customer-issuer-private.jwks.json',read_only:true});result.stdout=JSON.stringify(body);}return result;};
  g.adapter=await createOwnedSandboxBootstrap(g.options,g.dependencies);await rejects(g.adapter.prepare(LIMIT()),'PREPARE_PENDING');assert.equal(g.events.filter(stoppedCompose).length,0);
});

test('changed private directory or hardlinked ledger is refused before private replacement',async()=>{
  for(const mutate of [f=>{f.files.get(DIR).symlink=true;},f=>{f.files.get(DIR+'/runtime.json').nlink=2;}]) {
    const f=await prepared();mutate(f);await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');assert.equal(f.adapter.resources().status,'PENDING');
  }
});

test('admin callback deadline cannot leave an unbounded capability or restore readiness from late completion',async()=>{
  const f=await prepared();await f.adapter.persistWebhookSecret(SECRET);await f.adapter.start(LIMIT());let release,entered,finished=false;
  const started=new Promise(resolveStarted=>{entered=resolveStarted;});
  const operation=f.adapter.withAdminCredentials(()=>{entered();return new Promise(resolveRelease=>{release=resolveRelease;});}).finally(()=>{finished=true;});
  const rejected=rejects(operation,'ADMIN_PENDING');await started;const timer=[...f.timers.values()][0];assert.ok(timer);timer.callback();await turn();const bounded=finished;
  release(true);await rejected;assert.equal(bounded,true);await rejects(f.adapter.verify(LIMIT()),'VERIFY_PENDING');
});

test('network-none helpers accept Docker builtin none topology while refusing any connected helper network',async()=>{
  const f=fixture(),execute=f.dependencies.execute;
  f.dependencies.execute=async(...args)=>{const result=await execute(...args);if(args[1][4]==='container'&&args[1][5]==='inspect') {
    const body=JSON.parse(result.stdout);if(body.NetworkMode==='none'){body.Networks={none:{NetworkID:id(900)}};result.stdout=JSON.stringify(body);}
  }return result;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);assert.equal(await f.adapter.prepare(LIMIT()),true);
  const g=fixture(),other=g.dependencies.execute;
  g.dependencies.execute=async(...args)=>{const result=await other(...args);if(args[1][4]==='container'&&args[1][5]==='inspect') {
    const body=JSON.parse(result.stdout);if(body.NetworkMode==='none'){body.Networks={bridge:{NetworkID:id(901)}};result.stdout=JSON.stringify(body);}
  }return result;};
  g.adapter=await createOwnedSandboxBootstrap(g.options,g.dependencies);await rejects(g.adapter.prepare(LIMIT()),'PREPARE_PENDING');
});

test('generated local credentials are distinct Base64 inputs and issuer algorithms match the backend contract',async()=>{
  const f=await prepared(),values=Object.fromEntries(f.files.get(DIR+'/app.env').contents.toString().trim().split('\n').map(line=>{const offset=line.indexOf('=');return [line.slice(0,offset),line.slice(offset+1)];}));
  const secrets=['PAYMENT_DB_PASSWORD','PAYMENT_ADMIN_BOOTSTRAP_PASSWORD','PAYMENT_DATA_ENCRYPTION_KEY','PAYMENT_DONATION_MESSAGE_FINGERPRINT_KEY','PAYMENT_CUSTOMER_DIGEST_KEY_V1','PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1'].map(name=>values[name]);
  assert.equal(new Set(secrets).size,6);for(const value of secrets){assert.equal(Buffer.from(value,'base64').byteLength,32);assert.equal(Buffer.from(value,'base64').toString('base64'),value);}
  assert.equal(values.PAYMENT_FAKE_AUTO_SUCCESS,'false');assert.equal(values.PAYMENT_TELEMETRY_SITEKEYS,'s5a-11111111222243338444555555555555');
  const keys=JSON.parse(f.files.get(DIR+'/issuer.jwks.json').contents).keys;assert.deepEqual(keys.map(k=>[k.kid,k.alg,k.use]),[['local-fixture-rs','RS256','sig'],['local-fixture-es','ES256','sig']]);
  assert.deepEqual(f.events.filter(e=>e.type==='crypto').map(e=>[e.kind,e.options]),[['rsa',{modulusLength:2048}],['ec',{namedCurve:'prime256v1'}]]);
});

test('replacement proof and durable identity precede the first exact-ID application start',async()=>{
  const f=fixture(),execute=f.dependencies.execute;let checked=false;
  f.dependencies.execute=async(...args)=>{
    if(args[1][4]==='container'&&args[1][5]==='start') {
      const ledger=JSON.parse(f.files.get(DIR+'/runtime.json').contents),backend=ledger.containers.backend;
      if(ledger.retiredBackend&&!checked) {
        assert.equal(args[1].at(-1),ledger.containers.postgres.id);assert.notEqual(backend.id,ledger.retiredBackend.id);
        assert.ok(ledger.intents.some(i=>i.action==='CREATE_FINAL_BACKEND'&&i.status==='ACKNOWLEDGED'));
        const replacement=f.events.findLastIndex(stoppedCompose),after=f.events.slice(replacement+1);
        for(const service of ['postgres','mailpit','backend','demo-site','callback-receiver']) {
          assert.ok(after.some(e=>e.type==='process'&&e.args[4]==='container'&&e.args[5]==='inspect'&&e.args.at(-1)===ledger.containers[service].id));
          assert.equal(f.containers.get(ledger.containers[service].id).State.Running,false);
        }
        const rename=after.findLastIndex(e=>e.type==='rename'&&e.to===DIR+'/runtime.json');assert.ok(rename>=0);
        assert.ok(after.slice(rename+1).some(e=>e.type==='sync'&&e.path===DIR));checked=true;
      }
    }
    return execute(...args);
  };
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await f.adapter.prepare(LIMIT());await f.adapter.persistWebhookSecret(SECRET);await f.adapter.start(LIMIT());assert.equal(checked,true);
});

for(const phase of ['initial','replacement'])for(const dispatched of [false,true])test(phase+' stopped Compose '+(dispatched?'uncertain':'failed')+' result retains dispatch evidence without adoption, retry, start or authority',async()=>{
  const f=fixture(),execute=f.dependencies.execute;let dispatch;
  f.dependencies.execute=async(file,args,settings)=>{
    const event={type:'process',file,args,settings};
    if(stoppedCompose(event)&&args.includes(DIR+(phase==='initial'?'/app.env':'/final.env'))) {
      dispatch=event;
      if(dispatched)await execute(file,args,settings);else f.events.push(event);
      throw dispatched?new Proxy({},{get(){throw new Error('must_not_traverse_uncertain_result');}}):new Error('synthetic_compose_failure');
    }
    return execute(file,args,settings);
  };
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);
  if(phase==='replacement') {await f.adapter.prepare(LIMIT());await f.adapter.persistWebhookSecret(SECRET);await rejects(f.adapter.start(LIMIT()),'START_PENDING');}
  else await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');
  assert.ok(dispatch);assert.equal(f.adapter.resources().status,'PENDING');
  const ledger=JSON.parse(f.files.get(DIR+'/runtime.json').contents),intent=ledger.intents.at(-1);
  assert.deepEqual(intent,{action:phase==='initial'?'CREATE_STOPPED':'CREATE_FINAL_BACKEND',name:phase==='initial'?PROJECT:PROJECT+'-backend',status:'DISPATCHING'});
  assert.equal(Object.hasOwn(ledger.containers,'backend'),false);assert.equal(Object.hasOwn(f.adapter.resources().containers,'backend'),false);
  if(phase==='replacement')assert.ok(ledger.retiredBackend);else assert.equal(f.events.some(e=>e.path===USERFILE),false);
  const commands=f.events.filter(e=>e.type==='process');assert.deepEqual(commands.at(-1).args,dispatch.args);
  assert.equal(f.containers.size,(phase==='initial'?2:6)+(dispatched?(phase==='initial'?5:1):0));
  assert.equal(commands.filter(e=>e.args[4]==='container'&&e.args[5]==='start').length,2);assert.equal(f.events.some(e=>e.type==='health'),false);
  const count=f.events.length;let invoked=false;
  await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');await rejects(f.adapter.start(LIMIT()),'START_PENDING');
  await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');await rejects(f.adapter.verifyForwardTarget(),'FORWARD_PENDING');
  await rejects(f.adapter.verify(LIMIT()),'VERIFY_PENDING');await rejects(f.adapter.withAdminCredentials(()=>{invoked=true;return true;}),'ADMIN_PENDING');
  assert.equal(invoked,false);assert.equal(f.events.length,count);await rejects(f.adapter.stop(LIMIT()),'STOP_PENDING');
  assert.equal(f.events.filter(stoppedCompose).length,phase==='initial'?1:2);
  assert.equal(f.events.filter(e=>e.type==='process'&&e.args[4]==='container'&&e.args[5]==='start').length,2);
  assert.equal(f.events.filter(e=>e.type==='process'&&e.args[4]==='container'&&e.args[5]==='rm').length,phase==='initial'?0:1);
  assert.ok(f.files.has(DIR+'/owner.lock'));assert.deepEqual(JSON.parse(f.files.get(DIR+'/runtime.json').contents).intents.at(-1),intent);
});

test('live verification rejects extra actual bindings before health or admin calls',async()=>{
  const f=await prepared();await f.adapter.persistWebhookSecret(SECRET);await f.adapter.start(LIMIT());
  const backend=f.containers.get(f.adapter.resources().containers.backend.id);backend.Ports={...backend.Ports,'9090/tcp':[{HostIp:'127.0.0.1',HostPort:'19090'}]};
  const health=f.events.filter(e=>e.type==='health').length;await rejects(f.adapter.verify(LIMIT()),'VERIFY_PENDING');assert.equal(f.events.filter(e=>e.type==='health').length,health);
  let invoked=false;await rejects(f.adapter.withAdminCredentials(()=>{invoked=true;return true;}),'ADMIN_PENDING');assert.equal(invoked,false);
});

test('startup waits for each current service health without requiring later services to be running',async()=>{
  const f=fixture(),execute=f.dependencies.execute;let entered;
  const starting=new Promise(resolveStarted=>{entered=resolveStarted;});
  f.dependencies.execute=async(...args)=>{const value=await execute(...args);if(args[1][5]==='start') {
    const container=f.containers.get(args[1].at(-1));if(container.Name===('/'+PROJECT+'-postgres')){container.State.Health='starting';entered(container);}
  }return value;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await f.adapter.prepare(LIMIT());await f.adapter.persistWebhookSecret(SECRET);const start=f.adapter.start(LIMIT());
  const postgres=await starting;await turn();const timer=[...f.timers.values()].find(t=>t.ms===1000);assert.ok(timer);
  assert.equal(f.containers.get(f.adapter.resources().containers.mailpit.id).State.Running,false);postgres.State.Health='healthy';timer.callback();assert.equal(await start,true);
});

test('default HTTP health enforces redirects, bounded body and an abort signal without any network',async()=>{
  const f=fixture();delete f.dependencies.health;const calls=[];
  const guard=globalThis.fetch;globalThis.fetch=async(url,options)=>{calls.push({url,options});return {status:200,body:new ReadableStream({start(controller){controller.enqueue(new Uint8Array([1]));controller.close();}})};};
  try {
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await f.adapter.prepare(LIMIT());await f.adapter.persistWebhookSecret(SECRET);assert.equal(await f.adapter.start(LIMIT()),true);
    assert.equal(calls.length,3);for(const call of calls){assert.equal(call.options.redirect,'error');assert.ok(call.options.signal instanceof AbortSignal);assert.ok(call.url.startsWith('http://127.0.0.1:'));}
    globalThis.fetch=async()=>({status:200,body:new ReadableStream({start(controller){controller.enqueue(new Uint8Array(65537));controller.close();}})});
    await rejects(f.adapter.verify(LIMIT()),'VERIFY_PENDING');
  } finally {globalThis.fetch=guard;}
});

test('default free-port check retains its timer until verified close and fails closed on missing close',async()=>{
  const f=fixture();delete f.dependencies.portAvailable;let entered,close;
  const waiting=new Promise(resolveStarted=>{entered=resolveStarted;});
  f.dependencies.createServer=()=>({on(){return this;},listen(options,callback){queueMicrotask(callback);},close(callback){close=callback;entered();}});
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);const prepare=f.adapter.prepare(LIMIT());const rejected=rejects(prepare,'PREPARE_PENDING');
  await waiting;const timer=[...f.timers.values()].find(t=>t.ms===3000);assert.ok(timer);timer.callback();await rejected;close();
  assert.equal(f.events.filter(e=>e.type==='mkdir').length,0);
});

test('never-started services admit empty configured NetworkID with exact owned mode, map and fresh network proof',async()=>{
  const f=fixture(),execute=f.dependencies.execute;
  f.dependencies.execute=async(...args)=>{const value=await execute(...args);if(args[1][4]==='container'&&args[1][5]==='inspect') {
    const body=JSON.parse(value.stdout);if(SERVICES.includes(body.Labels['com.docker.compose.service'])&&body.State.Status==='created') {
      body.Networks[PROJECT+'-network'].NetworkID='';body.NetworkMode=f.network.Id;value.stdout=JSON.stringify(body);
    }
  }return value;};
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);assert.equal(await f.adapter.prepare(LIMIT()),true);assert.equal(await f.adapter.verifyForwardTarget(),true);
  await f.adapter.persistWebhookSecret(SECRET);assert.equal(await f.adapter.start(LIMIT()),true);assert.equal(await f.adapter.verify(LIMIT()),true);
});

test('wrong nonempty initial network ID, foreign mode and absent configured map always refuse preparation',async()=>{
  for(const mutate of [body=>{body.Networks[PROJECT+'-network'].NetworkID=id(999);},body=>{body.NetworkMode='bridge';},body=>{body.Networks={};}]) {
    const f=fixture(),execute=f.dependencies.execute;
    f.dependencies.execute=async(...args)=>{const value=await execute(...args);if(args[1][4]==='container'&&args[1][5]==='inspect') {
      const body=JSON.parse(value.stdout);if(body.Labels['com.docker.compose.service']==='backend'){mutate(body);value.stdout=JSON.stringify(body);}
    }return value;};
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await rejects(f.adapter.prepare(LIMIT()),'PREPARE_PENDING');
  }
});

test('empty live NetworkID fails before health or admin and cleanup-only cleared IDs never grant live or restart authority',async()=>{
  const f=await prepared();await f.adapter.persistWebhookSecret(SECRET);await f.adapter.start(LIMIT());
  f.containers.get(f.adapter.resources().containers.backend.id).Networks[PROJECT+'-network'].NetworkID='';
  const health=f.events.filter(e=>e.type==='health').length;await rejects(f.adapter.verify(LIMIT()),'VERIFY_PENDING');assert.equal(f.events.filter(e=>e.type==='health').length,health);
  let invoked=false;await rejects(f.adapter.withAdminCredentials(()=>{invoked=true;return true;}),'ADMIN_PENDING');assert.equal(invoked,false);
  const g=fixture(),execute=g.dependencies.execute;
  g.dependencies.execute=async(...args)=>{const value=await execute(...args);if(args[1][4]==='container'&&args[1][5]==='stop') {
    const container=g.containers.get(args[1].at(-1));container.Networks[PROJECT+'-network'].NetworkID='';
  }return value;};
  g.adapter=await createOwnedSandboxBootstrap(g.options,g.dependencies);await g.adapter.prepare(LIMIT());await g.adapter.persistWebhookSecret(SECRET);await g.adapter.start(LIMIT());
  assert.equal(await g.adapter.stop(LIMIT()),true);const starts=g.events.filter(e=>e.type==='process'&&e.args[5]==='start').length;
  await rejects(g.adapter.start(LIMIT()),'START_PENDING');await rejects(g.adapter.verify(LIMIT()),'VERIFY_PENDING');assert.equal(g.events.filter(e=>e.type==='process'&&e.args[5]==='start').length,starts);
});

test('I1 created helpers accept empty builtin none ID, and cleared exited helper IDs grant cleanup only',async()=>{
  const f=await prepared(),metadata=f.adapter.resources();assert.equal(metadata.containers['issuer-probe'].status,'exited');
  for(const helper of ['issuer-installer','issuer-probe'])f.containers.get(metadata.containers[helper].id).Networks.none.NetworkID='';
  assert.equal(await f.adapter.stop(LIMIT()),true);const count=f.events.length;
  await rejects(f.adapter.start(LIMIT()),'START_PENDING');assert.equal(f.events.length,count);
  for(const mutate of [body=>{body.Networks={bridge:{NetworkID:id(900)}};},body=>{body.Networks.none.NetworkID='partial';},body=>{body.State.Status='running';body.State.Running=true;}]) {
    const g=fixture(),execute=g.dependencies.execute;
    g.dependencies.execute=async(...args)=>{const result=await execute(...args);if(args[1][4]==='container'&&args[1][5]==='inspect') {
      const body=JSON.parse(result.stdout);if(body.NetworkMode==='none'){mutate(body);result.stdout=JSON.stringify(body);}
    }return result;};
    g.adapter=await createOwnedSandboxBootstrap(g.options,g.dependencies);await rejects(g.adapter.prepare(LIMIT()),'PREPARE_PENDING');
    assert.equal(g.events.filter(e=>e.type==='process'&&e.args[5]==='start').length,0);
  }
});

test('I2 official Node image default is empty while the receiver must explicitly run as node',async()=>{
  const f=await prepared();assert.equal(f.adapter.resources().images['callback-receiver'].user,'');
  assert.equal(f.containers.get(f.adapter.resources().containers['callback-receiver'].id).User,'node');
  const g=fixture(),execute=g.dependencies.execute;
  g.dependencies.execute=async(...args)=>{const result=await execute(...args);if(args[1][4]==='container'&&args[1][5]==='inspect') {
    const body=JSON.parse(result.stdout);if(body.Labels['com.docker.compose.service']==='callback-receiver'){body.User='';result.stdout=JSON.stringify(body);}
  }return result;};
  g.adapter=await createOwnedSandboxBootstrap(g.options,g.dependencies);await rejects(g.adapter.prepare(LIMIT()),'PREPARE_PENDING');
});

test('I3 only known unpublished Mailpit 1110 null is allowed alongside the exact six live host bindings',async()=>{
  const f=await prepared();await f.adapter.persistWebhookSecret(SECRET);assert.equal(await f.adapter.start(LIMIT()),true);
  const mailpit=f.containers.get(f.adapter.resources().containers.mailpit.id);assert.equal(mailpit.Ports['1110/tcp'],null);assert.equal(await f.adapter.verify(LIMIT()),true);
  for(const mutate of [c=>{c.Ports['1110/tcp']=[{HostIp:'127.0.0.1',HostPort:'11110'}];},
    c=>{c.Ports['8025/tcp'][0].HostIp='::1';},c=>{c.Ports['1025/tcp'][0].HostIp='0.0.0.0';},
    c=>{c.Ports['8025/tcp'].push({HostIp:'127.0.0.1',HostPort:'18026'});},c=>{delete c.Ports['1025/tcp'];},
    c=>{c.Ports['9090/tcp']=null;}]) {
    const g=await prepared();await g.adapter.persistWebhookSecret(SECRET);await g.adapter.start(LIMIT());mutate(g.containers.get(g.adapter.resources().containers.mailpit.id));
    const before=g.events.filter(e=>e.type==='health').length;await rejects(g.adapter.verify(LIMIT()),'VERIFY_PENDING');assert.equal(g.events.filter(e=>e.type==='health').length,before);
  }
  const g=await prepared();g.containers.get(g.adapter.resources().containers.mailpit.id).PortBindings['1110/tcp']=null;
  await rejects(g.adapter.verifyForwardTarget(),'FORWARD_PENDING');
  const h=await prepared();await h.adapter.persistWebhookSecret(SECRET);await h.adapter.start(LIMIT());h.containers.get(h.adapter.resources().containers.backend.id).Ports['1110/tcp']=null;
  await rejects(h.adapter.verify(LIMIT()),'VERIFY_PENDING');
});

test('I4 writer FD stays held through checked unlink and durable parent sync',async()=>{
  const f=await prepared();assert.equal(await f.adapter.persistWebhookSecret(SECRET),true);
  const path=ROOT+'/test-results/stripe-sandbox/user-env.lock';
  const removed=f.events.findIndex(e=>e.type==='unlink'&&e.path===path),closed=f.events.findIndex(e=>e.type==='close'&&e.path===path);
  const directorySync=f.events.findIndex((e,index)=>index>removed&&e.type==='sync'&&e.path===dirname(path));
  assert.ok(removed>=0&&directorySync>removed&&closed>directorySync);
});

test('I4 replaced, missing or unsafe writer paths refuse release without unlinking the foreign lock',async()=>{
  const path=ROOT+'/test-results/stripe-sandbox/user-env.lock';
  for(const mutate of [f=>{const prior=f.files.get(path);f.files.set(path,{...prior,ino:prior.ino+1000,contents:Buffer.from('foreign writer\n')});},
    f=>{f.files.delete(path);},f=>{f.files.get(path).mode=0o100640;},f=>{f.files.get(path).uid++;},f=>{f.files.get(path).nlink=2;},
    f=>{f.files.get(dirname(path)).symlink=true;}]) {
    const f=fixture(),open=f.dependencies.open;let changed=false,expected;
    f.dependencies.open=async(...args)=>{const file=await open(...args);if(args[0]===DIR+'/final.env'){const sync=file.sync;file.sync=async()=>{await sync();mutate(f);expected=f.files.get(path);changed=true;};}return file;};
    f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await f.adapter.prepare(LIMIT());await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');
    assert.equal(changed,true);assert.equal(f.events.filter(e=>e.type==='unlink'&&e.path===path).length,0);assert.equal(f.adapter.resources().status,'PENDING');
    assert.ok(f.files.has(DIR+'/runtime.json'));assert.equal(f.files.get(path),expected);
  }
});

test('I4 held writer metadata must still match its recorded inode and current private ownership',async()=>{
  const f=fixture(),path=ROOT+'/test-results/stripe-sandbox/user-env.lock',open=f.dependencies.open;let releasing=false;
  f.dependencies.open=async(...args)=>{const file=await open(...args);
    if(args[0]===path){const stat=file.stat;file.stat=async()=>{const value=await stat();return releasing?{...value,dev:value.dev+1}:value;};}
    if(args[0]===DIR+'/final.env'){const sync=file.sync;file.sync=async()=>{await sync();releasing=true;};}return file;
  };
  f.adapter=await createOwnedSandboxBootstrap(f.options,f.dependencies);await f.adapter.prepare(LIMIT());await rejects(f.adapter.persistWebhookSecret(SECRET),'PERSIST_PENDING');
  assert.ok(f.files.has(path));assert.equal(f.events.filter(e=>e.type==='unlink'&&e.path===path).length,0);
});

test('both opt-ins precede every configuration and dependency getter',async()=>{
  for(const env of [{},{PAYMENT_STRIPE_SANDBOX_ENABLED:'true'},{PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'}]) {
    let touched=0;
    const options={env};Object.defineProperty(options,'runId',{get(){touched++;throw null;}});
    const dependencies=new Proxy({execFile:forbidden},{get(){touched++;throw null;}});
    await assert.rejects(createOwnedSandboxBootstrap(options,dependencies),{message:'stripe_sandbox_bootstrap_OPT_IN_REQUIRED'});
    assert.equal(touched,0);
  }
});

test('Billing bootstrap is inert and keeps a seventh distinct grant key in owned backend only',async()=>{
  assert.equal(typeof bootstrapModule.createOwnedBillingBootstrap,'function');const f=fixture();
  f.options.env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'};f.options.selectedCase='initial_success';
  const b=await bootstrapModule.createOwnedBillingBootstrap(f.options,f.dependencies),limits=LIMIT();assert.equal(f.events.length,0);
  await b.prepare(limits);await b.persistWebhookSecret(SECRET);await b.start(limits);
  const data=Object.fromEntries(f.files.get(DIR+'/app.env').contents.toString().trim().split('\n').map(l=>l.split(/=(.*)/s).slice(0,2)));
  assert.match(data.PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1,/^[A-Za-z0-9+/]{43}=$/);
  assert.equal(Object.values(data).filter(v=>v===data.PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1).length,1);
  assert.equal(JSON.stringify(b.resources()).includes(data.PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1),false);
  const yaml=f.files.get(DIR+'/owned.yaml').contents.toString();
  assert.equal((yaml.match(/PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED/g)||[]).length,1);
  assert.ok(yaml.indexOf('PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED')>yaml.indexOf('  backend:'));
  assert.ok(yaml.indexOf('PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1')<yaml.indexOf('  demo-site:'));
  let credentials;assert.equal(await b.withDatabaseCredentials(async value=>{credentials=value;return true;},limits),true);
  assert.deepEqual(credentials,{host:'127.0.0.1',port:PORTS.postgres,database:'payment',user:'payment',password:data.PAYMENT_DB_PASSWORD});
  await b.stop(LIMIT());
});

test('Billing grant entropy collision fails before owned service creation',async()=>{
  const f=fixture();f.options.env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'};f.options.selectedCase='initial_success';
  let n=0;f.dependencies.randomBytes=size=>Buffer.alloc(size,++n===7?1:n);
  const b=await bootstrapModule.createOwnedBillingBootstrap(f.options,f.dependencies);await assert.rejects(b.prepare(LIMIT()));assert.equal(f.containers.size,0);
});
test('Billing bootstrap denies renewal of original deadline and restricts DB callback to live proof',async()=>{
  const f=fixture();f.options.env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'};f.options.selectedCase='initial_success';
  const b=await bootstrapModule.createOwnedBillingBootstrap(f.options,f.dependencies),limits=LIMIT();await b.prepare(limits);
  await assert.rejects(b.persistWebhookSecret(SECRET).then(()=>b.start({deadline:limits.deadline+1})));assert.equal(b.resources().status,'PENDING');
});
test('Billing generated Compose proof rejects grant configuration escaping the backend',async()=>{
  const f=fixture();f.options.env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'};f.options.selectedCase='initial_success';
  const execute=f.dependencies.execute;f.dependencies.execute=async(file,args,settings)=>{const r=await execute(file,args,settings);if(args.includes('config')&&args.includes('--format')){const c=JSON.parse(r.stdout);c.services['demo-site'].environment.PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1='buildOnly';return {...r,stdout:JSON.stringify(c)};}return r;};
  const b=await bootstrapModule.createOwnedBillingBootstrap(f.options,f.dependencies);await assert.rejects(b.prepare(LIMIT()),/PREPARE_PENDING/);
});

for(const interruption of ['before-callback','root-abort','child-deadline'])test('Billing DB credential callback rejects '+interruption+' and cannot accept a late result',async()=>{
  const f=fixture();f.options.env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'};f.options.selectedCase='initial_success';
  const root=new AbortController(),limits={...LIMIT(),signal:root.signal},b=await bootstrapModule.createOwnedBillingBootstrap(f.options,f.dependencies);
  await b.prepare(limits);await b.persistWebhookSecret(SECRET);await b.start(limits);
  let entered,release,calls=0;const waiting=new Promise(resolveEntered=>{entered=resolveEntered;});
  if(interruption==='before-callback')root.abort();
  const result=b.withDatabaseCredentials(()=>{calls++;entered();return new Promise(resolveRelease=>{release=resolveRelease;});},{deadline:limits.deadline-1000});
  const rejected=assert.rejects(result,/ADMIN_PENDING/);
  if(interruption!=='before-callback'){await waiting;if(interruption==='root-abort')root.abort();else{assert.equal(f.timers.size,1);[...f.timers.values()][0].callback();}}
  await rejected;assert.equal(calls,interruption==='before-callback'?0:1);assert.equal(b.resources().status,'PENDING');
  if(release){release(true);await turn();assert.equal(b.resources().status,'PENDING');}
  const prior=calls;await assert.rejects(b.withDatabaseCredentials(()=>{calls++;return true;},{deadline:limits.deadline-1000}),/ADMIN_PENDING/);assert.equal(calls,prior);
  assert.equal(await b.stop({deadline:limits.deadline+1000}),true);
});
function firstPaymentFixture(backendConfig={}){const f=fixture({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_FIRST_PAYMENT:'true'},selectedCase:'initial_success',controlPort:18083,frozenTime:1790000000});const execute=f.dependencies.execute;
f.dependencies.execute=async(file,args,settings)=>{const r=await execute(file,args,settings);if(args.includes('inspect')){const v=JSON.parse(r.stdout);if(args[4]==='image'&&args.at(-1)===PROJECT+'-backend:latest'){v.RuntimeSource=true;v.Entrypoint=['java','-Dloader.path=/app/initial-runtime','-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFirstPaymentRuntime','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher'];v.Cmd=null;}if(args[4]==='container'&&v.Name==='/'+PROJECT+'-backend'){v.RuntimeIdentity=true;v.Entrypoint=['java','-Dloader.path=/app/initial-runtime','-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFirstPaymentRuntime','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher'];v.Cmd=null;v.PortBindings['18083/tcp']=[{HostIp:'127.0.0.1',HostPort:'18083'}];if(v.State.Running)v.Ports['18083/tcp']=v.PortBindings['18083/tcp'];}r.stdout=JSON.stringify(v);}if(args.includes('config')&&args.includes('--format')){const v=JSON.parse(r.stdout),b=v.services.backend;b.build={context:ROOT+'/backend',dockerfile:'Dockerfile.billing-first-payment',args:{PAYMENT_RUNTIME_SOURCE_SHA:'e'.repeat(40)}};Object.assign(b,{entrypoint:null,command:null},backendConfig);Object.assign(b.environment,{PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACTIONS_KEY_VERSIONS:'v1',PAYMENT_STRIPE_BILLING_ACTIONS_ACTIVE_KEY_VERSION:'v1',PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1:'buildOnly',PAYMENT_STRIPE_BILLING_FIRST_PAYMENT:'true',PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_RUN_ID:RUN,PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_SOURCE_SHA:'e'.repeat(40),PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_FROZEN_TIME:'1790000000',PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_CONTROL_PORT:'18083',PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_CONTROL_BIND:'0.0.0.0',PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_CONTROL_KEY:'buildOnly'});b.ports.push({target:18083,published:'18083',host_ip:'127.0.0.1',protocol:'tcp'});r.stdout=JSON.stringify(v);}return r;};return f;}
test('first-payment bootstrap privately binds dedicated source image entrypoint env and seventh port',async()=>{assert.equal(typeof bootstrapModule.createOwnedBillingFirstPaymentBootstrap,'function');const f=firstPaymentFixture(),b=await bootstrapModule.createOwnedBillingFirstPaymentBootstrap(f.options,f.dependencies),l=LIMIT();assert.equal(f.events.length,0);await b.prepare(l);await b.persistWebhookSecret(SECRET);await b.start(l);let privateRuntime;await b.withFirstPaymentRuntime(v=>{privateRuntime=v;return true;},l);assert.match(privateRuntime.capability,/^[A-Za-z0-9_-]{43}$/);assert.equal(privateRuntime.endpoint,'http://127.0.0.1:18083');assert.equal(JSON.stringify(b.resources()).includes(privateRuntime.capability),false);assert.ok(f.events.some(e=>e.type==='port'&&e.port===18083));const yaml=f.files.get(DIR+'/runtime-build.yaml').contents.toString();assert.match(yaml,/Dockerfile.billing-first-payment/);assert.match(yaml,/PAYMENT_RUNTIME_SOURCE_SHA/);assert.ok(f.events.find(e=>e.type==='process'&&e.args.includes('build')&&e.args.includes('--quiet')).args.includes('PAYMENT_DEMO_BUILD_MODE=billing-first-payment'));assert.ok(f.events.filter(e=>e.type==='process'&&e.args.includes('build')).every(e=>e.args.includes(DIR+'/runtime-build.yaml')));await b.stop(LIMIT());});
for(const drift of ['source','entrypoint','environment','port'])test('first-payment bootstrap rejects '+drift+' drift before start',async()=>{assert.equal(typeof bootstrapModule.createOwnedBillingFirstPaymentBootstrap,'function');const f=firstPaymentFixture(),execute=f.dependencies.execute;f.dependencies.execute=async(file,args,settings)=>{const r=await execute(file,args,settings);if(args[4]==='image'&&args[5]==='inspect'&&args.at(-1)===PROJECT+'-backend:latest'){const v=JSON.parse(r.stdout);if(drift==='source')v.RuntimeSource=false;if(drift==='entrypoint')v.Entrypoint=['java','-jar','app.jar'];r.stdout=JSON.stringify(v);}if(args[4]==='container'&&args[5]==='inspect'){const v=JSON.parse(r.stdout);if(v.Name==='/'+PROJECT+'-backend'){if(drift==='environment')v.RuntimeIdentity=false;if(drift==='port')v.PortBindings['18083/tcp'][0].HostIp='0.0.0.0';}r.stdout=JSON.stringify(v);}return r;};const b=await bootstrapModule.createOwnedBillingFirstPaymentBootstrap(f.options,f.dependencies);await assert.rejects(b.prepare(LIMIT()));assert.equal(f.events.some(e=>e.type==='process'&&e.args[4]==='container'&&e.args[5]==='start'&&e.args.at(-1)===b.resources().containers.backend?.id),false);});

// Break: inherited null or absent command fields are mistaken for explicit overrides.
for(const [entrypointName,entrypoint] of [['null',null],['absent',undefined]])for(const [commandName,command] of [['null',null],['absent',undefined]])
test('first-payment bootstrap prepares inherited '+entrypointName+' entrypoint and '+commandName+' command',async()=>{
  const f=firstPaymentFixture({entrypoint,command}),b=await bootstrapModule.createOwnedBillingFirstPaymentBootstrap(f.options,f.dependencies);
  assert.equal(await b.prepare(LIMIT()),true);assert.equal(b.resources().status,'PREPARED');
  assert.equal(f.events.filter(stoppedCompose).length,1);
  assert.ok(SERVICES.every(service=>b.resources().containers[service]?.status==='created'));
  const ledger=JSON.parse(f.files.get(DIR+'/runtime.json').contents);
  assert.equal(ledger.intents.find(intent=>intent.action==='CREATE_STOPPED')?.status,'ACKNOWLEDGED');
});

// Break: a falsy check or command allowlist admits an explicit override before creation.
for(const field of ['entrypoint','command'])for(const [name,value] of [
  ['empty string',''],['nonempty string','java'],['empty array',[]],['nonempty array',['java']],['false',false],['zero',0],['object',{}],
])test('first-payment bootstrap rejects explicit '+field+' '+name+' before stopped creation',async()=>{
  const f=firstPaymentFixture({[field]:value}),b=await bootstrapModule.createOwnedBillingFirstPaymentBootstrap(f.options,f.dependencies);
  await rejects(b.prepare(LIMIT()),'PREPARE_PENDING');assert.equal(b.resources().status,'PENDING');
  assert.equal(f.events.filter(stoppedCompose).length,0);
  assert.equal(JSON.parse(f.files.get(DIR+'/runtime.json').contents).intents.some(intent=>intent.action==='CREATE_STOPPED'),false);
});

// Break: allowing inherited commands accidentally weakens the exact source/build proof.
for(const [name,mutate] of [
  ['context',build=>{build.context=ROOT;}],['Dockerfile',build=>{build.dockerfile='Dockerfile';}],
  ['source argument',build=>{build.args.PAYMENT_RUNTIME_SOURCE_SHA='f'.repeat(40);}],['extra argument',build=>{build.args.EXTRA='unexpected';}],
])test('first-payment bootstrap rejects build '+name+' drift before stopped creation',async()=>{
  const f=firstPaymentFixture(),execute=f.dependencies.execute;
  f.dependencies.execute=async(...args)=>{const result=await execute(...args);if(args[1].includes('config')&&args[1].includes('--format')){
    const config=JSON.parse(result.stdout);mutate(config.services.backend.build);result.stdout=JSON.stringify(config);
  }return result;};
  const b=await bootstrapModule.createOwnedBillingFirstPaymentBootstrap(f.options,f.dependencies);
  await rejects(b.prepare(LIMIT()),'PREPARE_PENDING');assert.equal(f.events.filter(stoppedCompose).length,0);
  assert.equal(JSON.parse(f.files.get(DIR+'/runtime.json').contents).intents.some(intent=>intent.action==='CREATE_STOPPED'),false);
});
