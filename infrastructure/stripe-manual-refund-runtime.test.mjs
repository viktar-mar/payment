import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import {syncBuiltinESMExports} from 'node:module';
import {EventEmitter} from 'node:events';
import {createOwnedManualRefundRuntime} from './stripe-manual-refund-runtime.mjs';
import * as fs from 'node:fs/promises';
import {fixture as retainedFixture} from './fixtures/stripe-manual-refund-state-fixture.mjs';

const forbidden=()=>{throw Error('synthetic_test_forbids_external_IO');};
mock.method(childProcess,'execFile',forbidden);mock.method(childProcess,'spawn',forbidden);mock.method(globalThis,'fetch',forbidden);syncBuiltinESMExports();
after(()=>{mock.restoreAll();syncBuiltinESMExports();});
const RUN='031b5ae7-6603-4339-b1b3-77231291ff34',SOURCE='f0620e40004162d2b3eededa60512f50a492f4cc',HEAD='e'.repeat(40),PROJECT='stripe-'+RUN;
const ROOT='/private/tmp/synthetic-refund-runtime',ORDER=['postgres','mailpit','callback-receiver','demo-site','backend'],ALL=[...ORDER,'issuer-installer','issuer-probe'];
const id=n=>n.toString(16).padStart(64,'0');
const labels=kind=>({'payment.sandbox.project':PROJECT,'payment.sandbox.run':RUN,'payment.sandbox.kind':kind});
function fixture(){
  const ports={backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025};
  const maps=service=>Object.fromEntries(({postgres:[['5432/tcp',15432]],mailpit:[['8025/tcp',18025],['1025/tcp',11025]],backend:[['8080/tcp',18080]],'demo-site':[['8080/tcp',18081]],'callback-receiver':[['8080/tcp',18082]]}[service]??[]).map(([key,port])=>[key,[{HostIp:'127.0.0.1',HostPort:String(port)}]]));
  const mounts=service=>({postgres:[{Type:'volume',Name:PROJECT+'-postgres',Destination:'/var/lib/postgresql',RW:true}],backend:[{Type:'volume',Name:PROJECT+'-issuer',Destination:'/app/local-fixtures',RW:false}],'callback-receiver':[{Type:'bind',Source:ROOT+'/infrastructure/callback-receiver.mjs',Destination:'/app/callback-receiver.mjs',RW:false}],'issuer-installer':[{Type:'bind',Source:ROOT+'/test-results/stripe-sandbox/'+RUN+'/issuer.jwks.json',Destination:'/input/issuer.json',RW:false},{Type:'volume',Name:PROJECT+'-issuer',Destination:'/issuer',RW:true}],'issuer-probe':[{Type:'volume',Name:PROJECT+'-issuer',Destination:'/issuer',RW:false}]}[service]??[]);
  const images=Object.fromEntries(ORDER.map((service,i)=>[service,{tag:service,id:'sha256:'+id(i+10),user:service==='callback-receiver'?'':'10001:10001'}]));
  const target={originalRunId:RUN,originalSource:SOURCE,project:PROJECT,images,network:{Id:id(50),Name:PROJECT+'-network',Labels:labels('network'),Driver:'bridge',Scope:'local'},volumes:Object.fromEntries(['postgres','issuer'].map(kind=>[kind,{Name:PROJECT+'-'+kind,CreatedAt:'2026-10-19T10:00:00Z',Driver:'local',Scope:'local',Labels:labels(kind)}])),ports,endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:18081',receiver:'http://127.0.0.1:18082'}};
  target.containers=Object.fromEntries(ALL.map((service,i)=>[service,{id:id(100+i),name:PROJECT+'-'+service,image:images[service.startsWith('issuer-')?'backend':service].id,service,status:'exited',startedAt:'2026-10-19T10:00:00Z',networkId:service.startsWith('issuer-')?null:id(50),mounts:mounts(service),ports:maps(service)}]));
  const projected=Object.fromEntries(ALL.map(service=>{const c=target.containers[service],helper=service.startsWith('issuer-');return [service,{Id:c.id,Name:'/'+c.name,Image:c.image,Labels:{...labels('container'),'com.docker.compose.project':PROJECT,'com.docker.compose.service':service},State:{Status:'exited',Running:false,ExitCode:0,StartedAt:c.startedAt,Health:''},Mounts:structuredClone(c.mounts),Networks:helper?{none:{NetworkID:''}}:{[PROJECT+'-network']:{NetworkID:''}},PortBindings:structuredClone(c.ports),Ports:{},StripeKeyPresent:service==='backend',SandboxDisabled:false,User:helper?(service==='issuer-installer'?'0:0':'10001:10001'):service==='callback-receiver'?'node':images[service].user,ReadonlyRootfs:helper,NetworkMode:helper?'none':PROJECT+'-network',CapAdd:service==='issuer-installer'?['CHOWN','DAC_OVERRIDE']:null,CapDrop:helper?['ALL']:null}];}));
  const events=[],calls=[],started=[],stopped=[],limits={deadline:Date.now()+60000},options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND:'true'},runId:'11111111-2222-4333-8444-555555555555',expectedSource:HEAD,daemon:'unix://'+ROOT+'/docker.sock'};
  const state={target,beginRestore:async(service,l)=>{assert.equal(l.deadline,limits.deadline);events.push('dispatch:'+service);return true;},ackRestore:async(service)=>{events.push('ack:'+service);return true;},withRetainedAdmin:async callback=>callback({username:'s5a-synthetic',password:'SYNTHETIC_PRIVATE'}),verifyRetainedWebhookSecret:async candidate=>{if(candidate!=='whsec_SYNTHETIC')throw null;return true;}};
  const deps={root:ROOT,lstat:async path=>({uid:process.getuid(),ino:1,dev:1,mode:0o40700,isDirectory:()=>!path.endsWith('.sock'),isSymbolicLink:()=>false,isSocket:()=>path.endsWith('.sock')}),realpath:async path=>path,readFile:async()=>Buffer.from('synthetic receiver\n'),createServer:()=>{const server=new EventEmitter();server.listen=(_opts,cb)=>queueMicrotask(cb);server.close=cb=>queueMicrotask(cb);return server;},execute:async(file,args)=>{calls.push({file,args});if(file==='/usr/bin/git'){if(args[0]==='rev-parse')return {stdout:HEAD+'\n',stderr:''};if(args[0]==='show')return {stdout:'synthetic receiver\n',stderr:''};return {stdout:'',stderr:''};}assert.equal(file,'/Applications/Docker.app/Contents/Resources/bin/docker');const a=args.slice(2);if(a[0]==='ps')return {stdout:ALL.map(s=>projected[s].Id).join('\n'),stderr:''};if(a[1]==='inspect'){const value=a[0]==='network'?target.network:a[0]==='volume'?Object.values(target.volumes).find(v=>v.Name===a.at(-1)):a[0]==='image'?Object.values(images).find(i=>i.id===a.at(-1)):Object.values(projected).find(c=>c.Id===a.at(-1));return {stdout:JSON.stringify(a[0]==='image'?{Id:value.id,User:value.user}:value),stderr:''};}const service=ALL.find(s=>projected[s].Id===a.at(-1));if(a[0]==='container'&&a[1]==='start'){events.push('start:'+service);started.push(service);Object.assign(projected[service].State,{Status:'running',Running:true,Health:'healthy'});projected[service].Networks={[PROJECT+'-network']:{NetworkID:id(50)}};projected[service].Ports=structuredClone(projected[service].PortBindings);return {stdout:projected[service].Id+'\n',stderr:''};}if(a[0]==='container'&&a[1]==='stop'){stopped.push(service);Object.assign(projected[service].State,{Status:'exited',Running:false,Health:''});return {stdout:projected[service].Id+'\n',stderr:''};}throw Error('unexpected_synthetic_command');}};
  const directoryStat=deps.lstat;deps.lstat=async path=>path.endsWith('callback-receiver.mjs')?{uid:process.getuid(),ino:2,dev:1,nlink:1,size:19,mtimeMs:1,ctimeMs:1,isFile:()=>true,isSymbolicLink:()=>false}:directoryStat(path);
  deps.open=async()=>({stat:()=>deps.lstat(ROOT+'/infrastructure/callback-receiver.mjs'),read:async buffer=>{Buffer.from('synthetic receiver\n').copy(buffer);return {bytesRead:19};},close:async()=>{}});
  return {target,state,options,limits,deps,projected,events,calls,started,stopped,open:()=>createOwnedManualRefundRuntime({options,state},deps)};
}
test('restoration starts only the five exact retained IDs with durable dispatch and acknowledgement',async()=>{
  const f=fixture(),runtime=await f.open();assert.equal(await runtime.restore(f.limits),true);
  assert.deepEqual(f.started,['postgres','mailpit','callback-receiver','demo-site','backend']);
  assert.deepEqual(f.events,ORDER.flatMap(s=>['dispatch:'+s,'start:'+s,'ack:'+s]));
  assert.equal(await runtime.verify(f.limits),true);assert.equal(await runtime.stop({deadline:f.limits.deadline+120000}),true);assert.deepEqual(f.stopped,[...ORDER].reverse());
});
// Break caught: any broadened identity or policy comparison would start an unowned topology.
for(const [name,mutate] of [
  ['full ID',f=>f.projected.postgres.Id=id(900)],['image',f=>f.projected.backend.Image='sha256:'+id(999)],['label',f=>f.projected.backend.Labels['payment.sandbox.run']='other'],
  ['mount',f=>f.projected.backend.Mounts[0].RW=true],['host port',f=>f.projected.mailpit.PortBindings['8025/tcp'][0].HostIp='0.0.0.0'],['network',f=>f.projected.postgres.Networks[PROJECT+'-network'].NetworkID=id(999)],
  ['helper exit',f=>f.projected['issuer-probe'].State.ExitCode=1],['helper capabilities',f=>f.projected['issuer-installer'].CapDrop=[]],['helper user',f=>f.projected['issuer-probe'].User='0:0'],
  ['extra secret presence',f=>f.projected['demo-site'].StripeKeyPresent=true],['missing backend secret',f=>f.projected.backend.StripeKeyPresent=false],['backend disabled',f=>f.projected.backend.SandboxDisabled=true],
  ['previously running service',f=>{f.projected.postgres.State.Status='running';f.projected.postgres.State.Running=true;}],['helper read-write root',f=>f.projected['issuer-probe'].ReadonlyRootfs=false],
])test('restoration refuses changed '+name+' before any start',async()=>{const f=fixture();mutate(f);const runtime=await f.open();await assert.rejects(runtime.restore(f.limits),{message:'stripe_manual_refund_runtime_UNCONFIRMED'});assert.deepEqual(f.started,[]);});
for(const kind of ['head','dirty','ancestor','blob','extra container','network','volume','occupied port','socket'])test('restoration refuses '+kind+' before dispatch',async()=>{
  const f=fixture(),execute=f.deps.execute;f.deps.execute=async(file,args,...rest)=>{if(file==='/usr/bin/git'){if(kind==='ancestor'&&args[0]==='merge-base')throw null;if(kind==='head'&&args[0]==='rev-parse')return {stdout:SOURCE+'\n',stderr:''};if(kind==='dirty'&&args.includes('status'))return {stdout:' M file\n',stderr:''};if(kind==='blob'&&args[0]==='show')return {stdout:'changed\n',stderr:''};}const result=await execute(file,args,...rest);if(file.endsWith('/docker')){if(kind==='extra container'&&args[2]==='ps')result.stdout+='\n'+id(999);if(kind==='network'&&args[2]==='network'&&args[3]==='inspect'){const v=JSON.parse(result.stdout);v.Labels['payment.sandbox.run']='other';result.stdout=JSON.stringify(v);}if(kind==='volume'&&args[2]==='volume'&&args[3]==='inspect'){const v=JSON.parse(result.stdout);v.CreatedAt='2026-10-20T00:00:00Z';result.stdout=JSON.stringify(v);}}return result;};
  if(kind==='occupied port')f.deps.createServer=()=>{const server=new EventEmitter();server.listen=()=>queueMicrotask(()=>server.emit('error',Error('EADDRINUSE')));server.close=cb=>queueMicrotask(cb);return server;};if(kind==='socket'){const stat=f.deps.lstat;f.deps.lstat=async p=>{const s=await stat(p);return p.endsWith('.sock')?{...s,isSocket:()=>false}:s;};}
  const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,[]);assert.deepEqual(f.events,[]);
});
test('fresh live topology gates every private callback and retained secret equality',async()=>{
  const f=fixture(),runtime=await f.open();await runtime.restore(f.limits);let seen;
  assert.equal(await runtime.withAdminCredentials(v=>{seen=v;return true;},f.limits),true);assert.equal(seen.username,'s5a-synthetic');
  assert.equal(await runtime.verifyWebhookSecret('whsec_SYNTHETIC',f.limits),true);assert.equal(await runtime.verifyForwardTarget(f.limits),true);
  f.projected.backend.Labels['payment.sandbox.run']='changed';let calls=0;await assert.rejects(runtime.withAdminCredentials(()=>{calls++;return true;},f.limits));assert.equal(calls,0);
});
for(const kind of ['secret','callback','deadline'])test('runtime permanently fences wrong '+kind,async()=>{const f=fixture(),runtime=await f.open();await runtime.restore(f.limits);if(kind==='secret')await assert.rejects(runtime.verifyWebhookSecret('whsec_WRONG',f.limits));if(kind==='callback')await assert.rejects(runtime.withAdminCredentials(()=>false,f.limits));if(kind==='deadline')await assert.rejects(runtime.verify({deadline:f.limits.deadline+1}));await assert.rejects(runtime.verify(f.limits));await runtime.stop({deadline:f.limits.deadline+120000});});
test('partial restore cleanup stops only IDs dispatched by this controller',async()=>{const f=fixture(),execute=f.deps.execute;f.deps.execute=async(file,args,...rest)=>{if(args[2]==='container'&&args[3]==='start'&&args.at(-1)===f.target.containers['demo-site'].id)throw null;return execute(file,args,...rest);};const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,['postgres','mailpit','callback-receiver']);await runtime.stop({deadline:f.limits.deadline+120000});assert.deepEqual(f.stopped,['callback-receiver','mailpit','postgres']);});
test('aborted late start remains owned until settlement and is stopped independently',async()=>{
  const f=fixture(),execute=f.deps.execute,abort=new AbortController();let unblock,entered;const ready=new Promise(r=>{entered=r;}),waiting=new Promise(r=>{unblock=r;});f.deps.execute=async(file,args,...rest)=>{if(args[2]==='container'&&args[3]==='start'){entered();await waiting;}return execute(file,args,...rest);};f.limits.signal=abort.signal;const runtime=await f.open(),restoring=runtime.restore(f.limits);await ready;abort.abort();await assert.rejects(restoring);await new Promise(r=>setImmediate(r));unblock();await new Promise(r=>setTimeout(r,30));assert.deepEqual(f.started,['postgres']);assert.deepEqual(f.stopped,['postgres']);assert.deepEqual(f.events,['dispatch:postgres','start:postgres']);
});
test('cleanup proves the restarted container current StartedAt after network disconnection',async()=>{
  const f=fixture(),execute=f.deps.execute;f.deps.execute=async(file,args,...rest)=>{const result=await execute(file,args,...rest),service=ALL.find(s=>f.target.containers[s].id===args.at(-1));if(args[2]==='container'&&args[3]==='start')f.projected[service].State.StartedAt='2026-10-20T12:00:00Z';if(args[2]==='container'&&args[3]==='stop')f.projected[service].Networks={[PROJECT+'-network']:{NetworkID:''}};return result;};const runtime=await f.open();await runtime.restore(f.limits);assert.equal(await runtime.stop({deadline:f.limits.deadline+120000}),true);assert.deepEqual(f.stopped,[...ORDER].reverse());
});
test('ownership drift while restore DISPATCHING fsync is pending denies the first exact-ID start',async()=>{const f=fixture(),begin=f.state.beginRestore;f.state.beginRestore=async(...args)=>{await begin(...args);f.projected.backend.Labels['payment.sandbox.run']='other';return true;};const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,[]);assert.deepEqual(f.events,['dispatch:postgres']);});
test('actual retained private loader and durable state integrate with exact runtime restore and equality-only secret',async t=>{
  const retained=await retainedFixture(t);await fs.writeFile(retained.root+'/infrastructure/callback-receiver.mjs','synthetic receiver\n',{mode:0o600});const state=await retained.open(),f=fixture();Object.assign(f.target,state.target);
  for(const service of ALL){const c=state.target.containers[service];Object.assign(f.projected[service],{Id:c.id,Name:'/'+c.name,Image:c.image,Mounts:structuredClone(c.mounts),PortBindings:structuredClone(c.ports),User:service.startsWith('issuer-')?(service==='issuer-installer'?'0:0':'10001:10001'):service==='callback-receiver'?'node':state.target.images[service].user});}
  const execute=f.deps.execute;f.deps.execute=async(file,args,...rest)=>{if(file.endsWith('/docker')&&args[2]==='image'&&args[3]==='inspect'){const image=Object.values(state.target.images).find(v=>v.id===args.at(-1));return {stdout:JSON.stringify({Id:image.id,User:image.user}),stderr:''};}return execute(file,args,...rest);};Object.assign(f.deps,{root:retained.root,lstat:fs.lstat,realpath:fs.realpath,open:fs.open});
  const runtime=await createOwnedManualRefundRuntime({options:retained.options,state},f.deps);await runtime.restore(retained.limits);assert.deepEqual(f.started,ORDER);const durable=JSON.parse(await fs.readFile(retained.statePath,'utf8'));assert.deepEqual(durable.restore.map(v=>v.phase),['ACKNOWLEDGED','ACKNOWLEDGED','ACKNOWLEDGED','ACKNOWLEDGED','ACKNOWLEDGED']);
  let credentials=0;assert.equal(await runtime.withAdminCredentials(value=>{assert.equal(value.username,retained.data.app.PAYMENT_ADMIN_BOOTSTRAP_USERNAME);credentials++;return true;},retained.limits),true);assert.equal(credentials,1);assert.equal(await runtime.verifyWebhookSecret(retained.data.final.PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET,retained.limits),true);await assert.rejects(runtime.verifyWebhookSecret('whsec_DIFFERENT_0011223344556677',retained.limits));assert.equal(runtime.webhookSecret,undefined);
  await runtime.stop({deadline:retained.limits.deadline+120000});await state.close({retain:true},{deadline:retained.limits.deadline+120000});for(const [name,value] of [['runtime.json',retained.data.runtime],['form-provisioning.json',retained.data.provision]])assert.deepEqual(JSON.parse(await fs.readFile(retained.original+'/'+name,'utf8')),value);assert.equal(await fs.readFile(retained.original+'/final.env','utf8'),Object.entries(retained.data.final).map(([k,v])=>k+'='+v+'\n').join(''));
});

const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};};
const turn=()=>new Promise(resolve=>setImmediate(resolve));
test('stop during second raw start drains it and independently stops every earlier dispatched service',async()=>{
  const f=fixture(),execute=f.deps.execute,entered=deferred(),blocked=deferred();
  f.deps.execute=async(file,args,...rest)=>{
    if(args[2]==='container'&&args[3]==='start'&&args.at(-1)===f.target.containers.mailpit.id){entered.resolve();await blocked.promise;}
    return execute(file,args,...rest);
  };
  const runtime=await f.open(),restoring=runtime.restore(f.limits);restoring.catch(()=>{});
  await entered.promise;let stopSettled=false;
  const closing=runtime.stop({deadline:f.limits.deadline+120000}).then(value=>{stopSettled=true;return value;},error=>{stopSettled=true;throw error;});closing.catch(()=>{});
  try{
    await turn();assert.equal(stopSettled,false,'stop must await the actual second start');
    blocked.resolve();await assert.rejects(restoring);assert.equal(await closing,true);
    assert.deepEqual(f.started,['postgres','mailpit']);assert.deepEqual([...f.stopped].sort(),['mailpit','postgres']);
    assert.deepEqual(ALL.filter(service=>f.projected[service].State.Running),[]);
  }finally{blocked.resolve();await Promise.allSettled([restoring,closing]);}
});
test('aborted verification raw Git must actually settle before stop can finish cleanup',async()=>{
  const f=fixture(),execute=f.deps.execute,entered=deferred(),blocked=deferred();let armed=false,readSettled=false;
  f.deps.execute=async(file,args,...rest)=>{if(armed&&file==='/usr/bin/git'&&args[0]==='rev-parse'){entered.resolve();await blocked.promise;readSettled=true;}return execute(file,args,...rest);};
  const runtime=await f.open();await runtime.restore(f.limits);armed=true;const abort=new AbortController();
  const verifying=runtime.verify({...f.limits,signal:abort.signal});await entered.promise;abort.abort();await assert.rejects(verifying);
  let stopSettled=false;const closing=runtime.stop({deadline:f.limits.deadline+120000}).then(value=>{stopSettled=true;return value;},error=>{stopSettled=true;throw error;});closing.catch(()=>{});
  try{
    await turn();assert.equal(readSettled,false);assert.equal(stopSettled,false,'aborted wait is not raw Git settlement');
    blocked.resolve();await assert.rejects(closing,{message:'stripe_manual_refund_runtime_UNCONFIRMED'});assert.equal(readSettled,true);
    assert.deepEqual([...f.stopped].sort(),[...ORDER].sort());assert.deepEqual(ALL.filter(service=>f.projected[service].State.Running),[]);
  }finally{blocked.resolve();await Promise.allSettled([verifying,closing]);}
});

for(const kind of ['lstat','realpath'])test('overlapping stop drains raw '+kind+' before certifying cleanup',async()=>{
  const f=fixture(),primitive=f.deps[kind],entered=deferred(),blocked=deferred();let armed=false,settled=false;
  f.deps[kind]=async(...args)=>{if(armed){armed=false;entered.resolve();await blocked.promise;settled=true;}return primitive(...args);};
  const runtime=await f.open();await runtime.restore(f.limits);armed=true;
  const verifying=runtime.verify(f.limits);verifying.catch(()=>{});await entered.promise;
  let stopSettled=false;const closing=runtime.stop({deadline:f.limits.deadline+120000}).finally(()=>{stopSettled=true;});closing.catch(()=>{});
  try{await turn();assert.equal(stopSettled,false);assert.equal(settled,false);blocked.resolve();await assert.rejects(verifying);assert.equal(await closing,true);assert.equal(settled,true);assert.deepEqual([...f.stopped].sort(),[...ORDER].sort());}
  finally{blocked.resolve();await Promise.allSettled([verifying,closing]);}
});

for(const kind of ['late open','close'])test('stop drains '+kind+' and disposes an owned receiver descriptor exactly once',async()=>{
  const f=fixture(),open=f.deps.open,entered=deferred(),blocked=deferred();let armed=false,closes=0,disposed=false;
  f.deps.open=async(...args)=>{
    const file=await open(...args);if(!armed)return file;armed=false;
    const close=file.close;file.close=async()=>{closes++;if(kind==='close'){entered.resolve();await blocked.promise;}await close();disposed=true;};
    if(kind==='late open'){entered.resolve();await blocked.promise;}
    return file;
  };
  const runtime=await f.open();await runtime.restore(f.limits);armed=true;const abort=new AbortController();
  const verifying=runtime.verify({...f.limits,signal:abort.signal});await entered.promise;abort.abort();await assert.rejects(verifying);
  let stopSettled=false;const closing=runtime.stop({deadline:f.limits.deadline+120000}).finally(()=>{stopSettled=true;});closing.catch(()=>{});
  try{await turn();assert.equal(stopSettled,false);assert.equal(disposed,false);blocked.resolve();await assert.rejects(closing);assert.equal(disposed,true);assert.equal(closes,1);assert.deepEqual([...f.stopped].sort(),[...ORDER].sort());}
  finally{blocked.resolve();await Promise.allSettled([verifying,closing]);}
});

function clockFixture(){
  const f=fixture(),timers=new Map();let now=Date.now(),sequence=0;
  f.limits.deadline=now+60000;f.deps.now=()=>now;
  f.deps.setTimeout=(callback,ms)=>{const id=++sequence;timers.set(id,{callback,at:now+ms});return id;};
  f.deps.clearTimeout=id=>timers.delete(id);
  return {f,advance(value){now=value;for(const [id,timer] of [...timers])if(timer.at<=now){timers.delete(id);timer.callback();}}};
}
test('an unsettled second start cannot consume earlier cleanup or renew any late stop after the original ceiling',async()=>{
  const {f,advance}=clockFixture(),execute=f.deps.execute,entered=deferred(),blocked=deferred();
  f.deps.execute=async(file,args,...rest)=>{if(args[2]==='container'&&args[3]==='start'&&args.at(-1)===f.target.containers.mailpit.id){entered.resolve();await blocked.promise;}return execute(file,args,...rest);};
  const runtime=await f.open(),restoring=runtime.restore(f.limits);restoring.catch(()=>{});await entered.promise;
  const ceiling=f.limits.deadline+120000,closing=runtime.stop({deadline:ceiling});closing.catch(()=>{});
  try{
    await turn();assert.deepEqual(f.stopped,['postgres']);advance(ceiling+1);await assert.rejects(closing);await assert.rejects(restoring);
    const calls=f.calls.length;blocked.resolve();await turn();await turn();assert.deepEqual(f.started,['postgres','mailpit']);assert.deepEqual(f.stopped,['postgres']);assert.equal(f.calls.length,calls+1,'only the already dispatched raw start may settle late');
    await assert.rejects(runtime.stop({deadline:ceiling+60000}));assert.equal(f.calls.length,calls+1);
  }finally{blocked.resolve();await Promise.allSettled([restoring,closing]);}
});
test('abandoned raw read uncertainty and cleanup disposal cannot become success after the original ceiling',async()=>{
  const {f,advance}=clockFixture(),execute=f.deps.execute,entered=deferred(),blocked=deferred();let armed=false;
  f.deps.execute=async(file,args,...rest)=>{if(armed&&file==='/usr/bin/git'&&args[0]==='rev-parse'){entered.resolve();await blocked.promise;}return execute(file,args,...rest);};
  const runtime=await f.open();await runtime.restore(f.limits);armed=true;const abort=new AbortController();
  const verifying=runtime.verify({...f.limits,signal:abort.signal});await entered.promise;abort.abort();await assert.rejects(verifying);
  const ceiling=f.limits.deadline+120000,closing=runtime.stop({deadline:ceiling});closing.catch(()=>{});
  try{await turn();assert.deepEqual([...f.stopped].sort(),[...ORDER].sort());advance(ceiling+1);await assert.rejects(closing);const calls=f.calls.length;blocked.resolve();await turn();await turn();assert.equal(f.calls.length,calls+1);await assert.rejects(runtime.verify(f.limits));await assert.rejects(runtime.stop({deadline:ceiling+60000}));assert.equal(f.calls.length,calls+1);}
  finally{blocked.resolve();await Promise.allSettled([verifying,closing]);}
});
test('cleanup rechecks the original ceiling inside deferred raw primitive dispatch',async()=>{
  const {f,advance}=clockFixture(),timer=f.deps.setTimeout,lstat=f.deps.lstat;let armed=false,triggered=false,lateIO=0;
  const ceiling=f.limits.deadline+120000;
  f.deps.setTimeout=(callback,ms)=>{const id=timer(callback,ms);if(armed&&!triggered){triggered=true;queueMicrotask(()=>queueMicrotask(()=>advance(ceiling+1)));}return id;};
  f.deps.lstat=async(...args)=>{if(f.deps.now()>=ceiling)lateIO++;return lstat(...args);};
  const runtime=await f.open();await runtime.restore(f.limits);armed=true;
  await assert.rejects(runtime.stop({deadline:ceiling}));assert.equal(triggered,true);assert.equal(lateIO,0);
});
