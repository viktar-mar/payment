import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import {syncBuiltinESMExports} from 'node:module';
import {EventEmitter} from 'node:events';
import {CONTAINER_FORMAT,NETWORK_FORMAT,VOLUME_FORMAT} from './stripe-sandbox-ownership.mjs';
import {createOwnedManualRefundRemainderRuntime} from './stripe-manual-refund-remainder-runtime.mjs';
import * as fs from 'node:fs/promises';
import {fixture as retainedFixture} from './fixtures/stripe-manual-refund-remainder-state-fixture.mjs';

const forbidden=()=>{throw Error('synthetic_test_forbids_external_IO');};
mock.method(childProcess,'execFile',forbidden);mock.method(childProcess,'spawn',forbidden);mock.method(globalThis,'fetch',forbidden);syncBuiltinESMExports();
after(()=>{mock.restoreAll();syncBuiltinESMExports();});
const RUN='031b5ae7-6603-4339-b1b3-77231291ff34',SOURCE='f0620e40004162d2b3eededa60512f50a492f4cc',HEAD='e'.repeat(40),PROJECT='stripe-'+RUN;
const ROOT='/private/tmp/synthetic-refund-runtime',ORDER=['postgres','mailpit','callback-receiver','demo-site','backend'],ALL=[...ORDER,'issuer-installer','issuer-probe'];
const id=n=>n.toString(16).padStart(64,'0');
const labels=kind=>({'payment.sandbox.project':PROJECT,'payment.sandbox.run':RUN,'payment.sandbox.kind':kind});
// The synthetic process boundary accepts only the exact retained operations.
function allowedCommand(file,args,settings,target,options){
 assert.equal(settings.shell,false);assert.equal(settings.maxBuffer,65536);assert.ok(settings.timeout>0);
 assert.equal(settings.env.DOCKER_CONFIG,'/private/var/empty');
 if(file==='/usr/bin/git'){
  assert.ok([
   ['rev-parse','HEAD'],['-c','core.fsmonitor=false','status','--porcelain=v1','--untracked-files=no'],
   ['merge-base','--is-ancestor',SOURCE,options.expectedSource],['show',SOURCE+':infrastructure/callback-receiver.mjs'],
  ].some(expected=>JSON.stringify(expected)===JSON.stringify(args)));assert.ok(settings.timeout<=10000);return;
 }
 assert.equal(file,'/Applications/Docker.app/Contents/Resources/bin/docker');assert.deepEqual(args.slice(0,2),['--host',options.daemon]);
 const a=args.slice(2);
 if(a[0]==='ps'){assert.deepEqual(a,['ps','--all','--no-trunc','--filter','label=com.docker.compose.project='+target.project,'--format','{{.ID}}']);}
 else if(a[1]==='inspect'){
  const expected=a[0]==='container'?CONTAINER_FORMAT:a[0]==='network'?NETWORK_FORMAT:a[0]==='volume'?VOLUME_FORMAT:'{"Id":{{json .ID}},"User":{{json .Config.User}}}';
  assert.deepEqual(a.slice(1,4),['inspect','--format',expected]);assert.equal(a.length,5);
  assert.ok((a[0]==='container'?Object.values(target.containers).map(c=>c.id):a[0]==='image'?Object.values(target.images).map(i=>i.id):a[0]==='network'?[target.network.Id]:a[0]==='volume'?Object.values(target.volumes).map(v=>v.Name):[]).includes(a[4]));
 }else{
  assert.equal(a[0],'container');assert.ok(ORDER.some(service=>target.containers[service].id===a.at(-1)));
  assert.deepEqual(a,a[1]==='start'?['container','start',a.at(-1)]:['container','stop','--time','10',a.at(-1)]);
 }
 assert.ok(settings.timeout<=(a[0]==='container'&&a[1]==='stop'?20000:10000));
}
function fixture(){
  const ports={backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025};
  const maps=service=>Object.fromEntries(({postgres:[['5432/tcp',15432]],mailpit:[['8025/tcp',18025],['1025/tcp',11025]],backend:[['8080/tcp',18080]],'demo-site':[['8080/tcp',18081]],'callback-receiver':[['8080/tcp',18082]]}[service]??[]).map(([key,port])=>[key,[{HostIp:'127.0.0.1',HostPort:String(port)}]]));
  const mounts=service=>({postgres:[{Type:'volume',Name:PROJECT+'-postgres',Destination:'/var/lib/postgresql',RW:true}],backend:[{Type:'volume',Name:PROJECT+'-issuer',Destination:'/app/local-fixtures',RW:false}],'callback-receiver':[{Type:'bind',Source:ROOT+'/infrastructure/callback-receiver.mjs',Destination:'/app/callback-receiver.mjs',RW:false}],'issuer-installer':[{Type:'bind',Source:ROOT+'/test-results/stripe-sandbox/'+RUN+'/issuer.jwks.json',Destination:'/input/issuer.json',RW:false},{Type:'volume',Name:PROJECT+'-issuer',Destination:'/issuer',RW:true}],'issuer-probe':[{Type:'volume',Name:PROJECT+'-issuer',Destination:'/issuer',RW:false}]}[service]??[]);
  const images=Object.fromEntries(ORDER.map((service,i)=>[service,{tag:service,id:'sha256:'+id(i+10),user:service==='callback-receiver'?'':'10001:10001'}]));
  const target={originalRunId:RUN,originalSource:SOURCE,project:PROJECT,images,network:{Id:id(50),Name:PROJECT+'-network',Labels:labels('network'),Driver:'bridge',Scope:'local'},volumes:Object.fromEntries(['postgres','issuer'].map(kind=>[kind,{Name:PROJECT+'-'+kind,CreatedAt:'2026-10-19T10:00:00Z',Driver:'local',Scope:'local',Labels:labels(kind)}])),ports,endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:18081',receiver:'http://127.0.0.1:18082'}};
  target.containers=Object.fromEntries(ALL.map((service,i)=>[service,{id:id(100+i),name:PROJECT+'-'+service,image:images[service.startsWith('issuer-')?'backend':service].id,service,status:'exited',startedAt:'2026-10-19T10:00:00Z',networkId:service.startsWith('issuer-')?null:id(50),mounts:mounts(service),ports:maps(service)}]));
  const projected=Object.fromEntries(ALL.map(service=>{const c=target.containers[service],helper=service.startsWith('issuer-');return [service,{Id:c.id,Name:'/'+c.name,Image:c.image,Labels:{...labels('container'),'com.docker.compose.project':PROJECT,'com.docker.compose.service':service},State:{Status:'exited',Running:false,ExitCode:0,StartedAt:c.startedAt,Health:''},Mounts:structuredClone(c.mounts),Networks:helper?{none:{NetworkID:''}}:{[PROJECT+'-network']:{NetworkID:''}},PortBindings:structuredClone(c.ports),Ports:{},StripeKeyPresent:service==='backend',SandboxDisabled:false,User:helper?(service==='issuer-installer'?'0:0':'10001:10001'):service==='callback-receiver'?'node':images[service].user,ReadonlyRootfs:helper,NetworkMode:helper?'none':PROJECT+'-network',CapAdd:service==='issuer-installer'?['CHOWN','DAC_OVERRIDE']:null,CapDrop:helper?['ALL']:null}];}));
  const events=[],calls=[],started=[],stopped=[],limits={deadline:Date.now()+60000},options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'},runId:'11111111-2222-4333-8444-555555555555',expectedSource:HEAD,daemon:'unix://'+ROOT+'/docker.sock'};
  const resourceTarget={original:target,priorRunId:'34941d87-12d3-4c74-8c5a-b62b7c914390',priorSource:'d'.repeat(40),priorRefundId:'aaaaaaaa-bbbb-4ccc-8ddd-000000000020',remainderKey:'SYNTHETIC_KEY',callbackVersionBefore:2,callbackVersionAfter:3};
  const snapshots=[];const state={target:()=>resourceTarget,recordStopped:async(value,l)=>{assert.equal(l.deadline,limits.deadline);snapshots.push(structuredClone(value));events.push('stopped');return true;},beginRestore:async(service,l)=>{assert.equal(l.deadline,limits.deadline);events.push('dispatch:'+service);return true;},ackRestore:async(service,startedAt,l)=>{assert.equal(startedAt,projected[service].State.StartedAt);assert.equal(l.deadline,limits.deadline);events.push('ack:'+service);return true;},withRetainedAdmin:async callback=>callback({username:'s5a-synthetic',password:'SYNTHETIC_PRIVATE'}),verifyRetainedWebhookSecret:async candidate=>{if(candidate!=='whsec_SYNTHETIC')throw null;return true;}};
  const deps={root:ROOT,lstat:async path=>({uid:process.getuid(),ino:1,dev:1,mode:0o40700,isDirectory:()=>!path.endsWith('.sock'),isSymbolicLink:()=>false,isSocket:()=>path.endsWith('.sock')}),realpath:async path=>path,readFile:async()=>Buffer.from('synthetic receiver\n'),createServer:()=>{const server=new EventEmitter();server.listen=(_opts,cb)=>queueMicrotask(cb);server.close=cb=>queueMicrotask(cb);return server;},execute:async(file,args,settings)=>{allowedCommand(file,args,settings,target,options);calls.push({file,args,settings});if(file==='/usr/bin/git'){if(args[0]==='rev-parse')return {stdout:HEAD+'\n',stderr:''};if(args[0]==='show')return {stdout:'synthetic receiver\n',stderr:''};return {stdout:'',stderr:''};}assert.equal(file,'/Applications/Docker.app/Contents/Resources/bin/docker');const a=args.slice(2);if(a[0]==='ps')return {stdout:ALL.map(s=>projected[s].Id).join('\n'),stderr:''};if(a[1]==='inspect'){const value=a[0]==='network'?target.network:a[0]==='volume'?Object.values(target.volumes).find(v=>v.Name===a.at(-1)):a[0]==='image'?Object.values(images).find(i=>i.id===a.at(-1)):Object.values(projected).find(c=>c.Id===a.at(-1));return {stdout:JSON.stringify(a[0]==='image'?{Id:value.id,User:value.user}:value),stderr:''};}const service=ALL.find(s=>projected[s].Id===a.at(-1));if(a[0]==='container'&&a[1]==='start'){events.push('start:'+service);started.push(service);Object.assign(projected[service].State,{Status:'running',Running:true,Health:'healthy',StartedAt:new Date(Date.now()-1000).toISOString()});projected[service].Networks={[PROJECT+'-network']:{NetworkID:id(50)}};projected[service].Ports=structuredClone(projected[service].PortBindings);return {stdout:projected[service].Id+'\n',stderr:''};}if(a[0]==='container'&&a[1]==='stop'){stopped.push(service);Object.assign(projected[service].State,{Status:'exited',Running:false,Health:''});return {stdout:projected[service].Id+'\n',stderr:''};}throw Error('unexpected_synthetic_command');}};
  const directoryStat=deps.lstat;deps.lstat=async path=>path.endsWith('callback-receiver.mjs')?{uid:process.getuid(),ino:2,dev:1,nlink:1,size:19,mtimeMs:1,ctimeMs:1,isFile:()=>true,isSymbolicLink:()=>false}:directoryStat(path);
  deps.open=async()=>({stat:()=>deps.lstat(ROOT+'/infrastructure/callback-receiver.mjs'),read:async buffer=>{Buffer.from('synthetic receiver\n').copy(buffer);return {bytesRead:19};},close:async()=>{}});
  return {target,state,snapshots,options,limits,deps,projected,events,calls,started,stopped,open:()=>createOwnedManualRefundRemainderRuntime({options,state},deps)};
}
test('restoration starts only the five exact retained IDs with durable dispatch and acknowledgement',async()=>{
  const f=fixture(),runtime=await f.open();assert.equal(await runtime.restore(f.limits),true);
  assert.deepEqual(f.started,['postgres','mailpit','callback-receiver','demo-site','backend']);
  assert.deepEqual(f.events,['stopped',...ORDER.flatMap(s=>['dispatch:'+s,'start:'+s,'ack:'+s])]);
  assert.equal(await runtime.verify(f.limits),true);assert.equal(await runtime.stop({deadline:f.limits.deadline+120000}),true);assert.deepEqual(f.stopped,[...ORDER].reverse());
});
// Break caught: any broadened identity or policy comparison would start an unowned topology.
for(const [name,mutate] of [
  ['full ID',f=>f.projected.postgres.Id=id(900)],['image',f=>f.projected.backend.Image='sha256:'+id(999)],['label',f=>f.projected.backend.Labels['payment.sandbox.run']='other'],
  ['mount',f=>f.projected.backend.Mounts[0].RW=true],['host port',f=>f.projected.mailpit.PortBindings['8025/tcp'][0].HostIp='0.0.0.0'],['network',f=>f.projected.postgres.Networks[PROJECT+'-network'].NetworkID=id(999)],
  ['helper exit',f=>f.projected['issuer-probe'].State.ExitCode=1],['helper capabilities',f=>f.projected['issuer-installer'].CapDrop=[]],['helper user',f=>f.projected['issuer-probe'].User='0:0'],
  ['extra secret presence',f=>f.projected['demo-site'].StripeKeyPresent=true],['missing backend secret',f=>f.projected.backend.StripeKeyPresent=false],['backend disabled',f=>f.projected.backend.SandboxDisabled=true],
  ['previously running service',f=>{f.projected.postgres.State.Status='running';f.projected.postgres.State.Running=true;}],['helper read-write root',f=>f.projected['issuer-probe'].ReadonlyRootfs=false],
])test('restoration refuses changed '+name+' before any start',async()=>{const f=fixture();mutate(f);const runtime=await f.open();await assert.rejects(runtime.restore(f.limits),{message:'stripe_manual_refund_remainder_runtime_PENDING'});assert.deepEqual(f.started,[]);});
for(const kind of ['head','dirty','ancestor','blob','extra container','network','volume','occupied port','socket'])test('restoration refuses '+kind+' before dispatch',async()=>{
  const f=fixture(),execute=f.deps.execute;f.deps.execute=async(file,args,...rest)=>{if(file==='/usr/bin/git'){if(kind==='ancestor'&&args[0]==='merge-base')throw null;if(kind==='head'&&args[0]==='rev-parse')return {stdout:SOURCE+'\n',stderr:''};if(kind==='dirty'&&args.includes('status'))return {stdout:' M file\n',stderr:''};if(kind==='blob'&&args[0]==='show')return {stdout:'changed\n',stderr:''};}const result=await execute(file,args,...rest);if(file.endsWith('/docker')){if(kind==='extra container'&&args[2]==='ps')result.stdout+='\n'+id(999);if(kind==='network'&&args[2]==='network'&&args[3]==='inspect'){const v=JSON.parse(result.stdout);v.Labels['payment.sandbox.run']='other';result.stdout=JSON.stringify(v);}if(kind==='volume'&&args[2]==='volume'&&args[3]==='inspect'){const v=JSON.parse(result.stdout);v.CreatedAt='2026-10-20T00:00:00Z';result.stdout=JSON.stringify(v);}}return result;};
  if(kind==='occupied port')f.deps.createServer=()=>{const server=new EventEmitter();server.listen=()=>queueMicrotask(()=>server.emit('error',Error('EADDRINUSE')));server.close=cb=>queueMicrotask(cb);return server;};if(kind==='socket'){const stat=f.deps.lstat;f.deps.lstat=async p=>{const s=await stat(p);return p.endsWith('.sock')?{...s,isSocket:()=>false}:s;};}
  const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,[]);assert.deepEqual(f.events,kind==='occupied port'?['stopped']:[]);
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
  const f=fixture(),execute=f.deps.execute,abort=new AbortController();let unblock,entered;const ready=new Promise(r=>{entered=r;}),waiting=new Promise(r=>{unblock=r;});f.deps.execute=async(file,args,...rest)=>{if(args[2]==='container'&&args[3]==='start'){entered();await waiting;}return execute(file,args,...rest);};f.limits.signal=abort.signal;const runtime=await f.open(),restoring=runtime.restore(f.limits);await ready;abort.abort();await assert.rejects(restoring);await new Promise(r=>setImmediate(r));unblock();await new Promise(r=>setTimeout(r,30));assert.deepEqual(f.started,['postgres']);assert.deepEqual(f.stopped,['postgres']);assert.deepEqual(f.events,['stopped','dispatch:postgres','start:postgres']);
});
test('cleanup proves the restarted container current StartedAt after network disconnection',async()=>{
  const f=fixture(),execute=f.deps.execute;f.deps.execute=async(file,args,...rest)=>{const result=await execute(file,args,...rest),service=ALL.find(s=>f.target.containers[s].id===args.at(-1));if(args[2]==='container'&&args[3]==='start')f.projected[service].State.StartedAt=new Date(Date.now()-500).toISOString();if(args[2]==='container'&&args[3]==='stop')f.projected[service].Networks={[PROJECT+'-network']:{NetworkID:''}};return result;};const runtime=await f.open();await runtime.restore(f.limits);assert.equal(await runtime.stop({deadline:f.limits.deadline+120000}),true);assert.deepEqual(f.stopped,[...ORDER].reverse());
});
test('ownership drift while restore DISPATCHING fsync is pending denies the first exact-ID start',async()=>{const f=fixture(),begin=f.state.beginRestore;f.state.beginRestore=async(...args)=>{await begin(...args);f.projected.backend.Labels['payment.sandbox.run']='other';return true;};const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,[]);assert.deepEqual(f.events,['stopped','dispatch:postgres']);});
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
    blocked.resolve();await assert.rejects(closing,{message:'stripe_manual_refund_remainder_runtime_PENDING'});assert.equal(readSettled,true);
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

// Break caught: the original-runtime exact manifest StartedAt rule rejects a legitimate historical restart.
test('ordinary stopped StartedAt may advance but helper StartedAt may not',async()=>{
 const f=fixture();for(const service of ORDER)f.projected[service].State.StartedAt='2026-10-20T10:00:00Z';
 const runtime=await f.open();assert.equal(await runtime.restore(f.limits),true);
 assert.deepEqual(f.started,['postgres','mailpit','callback-receiver','demo-site','backend']);
 assert.equal(f.started.some(name=>name.startsWith('issuer-')),false);
 await runtime.stop({deadline:f.limits.deadline+120000});
});
test('stop grace10 has bounded20 transport under the original cleanup ceiling',async()=>{
 const f=fixture(),execute=f.deps.execute,stops=[];f.deps.execute=async(file,args,options)=>{if(args[2]==='container'&&args[3]==='stop')stops.push({args,options});return execute(file,args,options);};
 const runtime=await f.open();await runtime.restore(f.limits);await runtime.stop({deadline:f.limits.deadline+120000});
 assert.equal(stops.length,5);assert.equal(stops[0].options.timeout,20000);assert.equal(stops[0].args.includes('10'),true);
});


// Break caught: capturing before the full proof, or skipping reproof around durable dispatch, starts changed topology.
for(const stage of ['capture','dispatch'])test('all immutable topology is re-proved before '+stage+' and every start',async()=>{
 const f=fixture(),method=stage==='capture'?'recordStopped':'beginRestore',original=f.state[method];
 f.state[method]=async(...args)=>{if(stage==='capture'){assert.equal(f.calls.filter(c=>c.args[2]==='container'&&c.args[3]==='inspect').length,7);assert.equal(f.calls.some(c=>c.args[0]==='show'),true);}await original(...args);f.projected.backend.User='0:0';return true;};
 const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,[]);
});
for(const mutate of ['helper restart','future','regressive','malformed','unknown stopped status'])test('stopped capture rejects '+mutate,async()=>{
 const f=fixture();if(mutate==='helper restart')f.projected['issuer-probe'].State.StartedAt='2026-10-20T10:00:00Z';
 else if(mutate==='future')f.projected.backend.State.StartedAt=new Date(Date.now()+60000).toISOString();
 else if(mutate==='regressive')f.projected.backend.State.StartedAt='2026-10-18T00:00:00Z';
 else if(mutate==='malformed')f.projected.backend.State.StartedAt='no';else f.projected.backend.State.Status='dead';
 const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,[]);assert.deepEqual(f.snapshots,[]);
});
test('captured ordinary StartedAt cannot flap during durable record or before another start',async()=>{
 const f=fixture(),record=f.state.recordStopped;f.state.recordStopped=async(...args)=>{await record(...args);f.projected.mailpit.State.StartedAt='2026-10-20T10:00:00Z';return true;};
 const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,[]);assert.equal(f.snapshots.length,1);
 assert.deepEqual(Object.keys(f.snapshots[0]).sort(),['backend','callback-receiver','demo-site','mailpit','postgres']);
});
for(const state of ['future live','regressive live','flapping live'])test(state+' StartedAt cannot authorize acknowledgement or a later start',async()=>{
 const f=fixture(),execute=f.deps.execute;let inspect=0;f.deps.execute=async(file,args,...rest)=>{const r=await execute(file,args,...rest);if(args[2]==='container'&&args[3]==='start'){if(state==='future live')f.projected.postgres.State.StartedAt=new Date(Date.now()+60000).toISOString();if(state==='regressive live')f.projected.postgres.State.StartedAt=f.target.containers.postgres.startedAt;}if(state==='flapping live'&&f.started.length&&args[2]==='container'&&args[3]==='inspect'&&args.at(-1)===f.target.containers.postgres.id&&++inspect===2){const v=JSON.parse(r.stdout);v.State.StartedAt=new Date(Date.now()-500).toISOString();r.stdout=JSON.stringify(v);}return r;};
 // Readiness delays use the synthetic timer; ownership failures must not retry.
 const timer=f.deps.setTimeout;f.deps.setTimeout=(fn,ms)=>ms===1500?setTimeout(fn,0):(timer??setTimeout)(fn,ms);
 const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,['postgres']);assert.equal(f.events.includes('ack:postgres'),false);
});
test('failed first stop does not suppress independent reverse-order stops',async()=>{
 const f=fixture(),execute=f.deps.execute;f.deps.execute=async(file,args,...rest)=>{if(args[2]==='container'&&args[3]==='stop'&&args.at(-1)===f.target.containers.backend.id)throw Error('SYNTHETIC_PRIVATE_CANARY');return execute(file,args,...rest);};
 const runtime=await f.open();await runtime.restore(f.limits);await assert.rejects(runtime.stop({deadline:f.limits.deadline+120000}),{message:'stripe_manual_refund_remainder_runtime_PENDING'});assert.deepEqual(f.stopped,['demo-site','callback-receiver','mailpit','postgres']);
});
for(const status of ['dead','created','paused','unknown'])test('false Running with '+status+' status does not certify runtime closed',async()=>{
 const f=fixture(),runtime=await f.open();await runtime.restore(f.limits);Object.assign(f.projected.backend.State,{Running:false,Status:status});
 await assert.rejects(runtime.stop({deadline:f.limits.deadline+120000}));assert.deepEqual(f.stopped,['demo-site','callback-receiver','mailpit','postgres']);
});
for(const replacement of [undefined,new AbortController().signal])test('original runtime cancellation fences later omitted or replacement signals '+String(replacement),async()=>{
 const f=fixture(),abort=new AbortController(),runtime=await f.open();f.limits.signal=abort.signal;await runtime.restore(f.limits);abort.abort();let calls=0;
 await assert.rejects(runtime.withAdminCredentials(()=>{calls++;return true;},{deadline:f.limits.deadline,...(replacement?{signal:replacement}:{})}));assert.equal(calls,0);
 await runtime.stop({deadline:f.limits.deadline+120000});
});

// Break caught: pretending the execFile callback is child close lets cleanup certify a live child.
for(const failure of [false,true])test('raw child '+(failure?'timeout callback':'successful callback')+' waits for close within original cleanup',async()=>{
 const f=fixture(),execute=f.deps.execute,entered=deferred(),blocked=deferred(),abort=new AbortController();let childClosed=false,lateChild;
 const stub=mock.method(childProcess,'execFile',(file,args,settings,callback)=>{
  const child=new EventEmitter();Promise.resolve().then(()=>execute(file,args,settings)).then(async result=>{
   const delayed=args[2]==='container'&&args[3]==='start';callback(delayed&&failure?Error('SYNTHETIC_PRIVATE_TIMEOUT'):null,result.stdout,result.stderr);
   if(delayed){lateChild=child;entered.resolve();await blocked.promise;childClosed=true;}child.emit('close');
  },()=>{callback(Error('synthetic_io_failure'),'','');child.emit('close');});return child;
 });syncBuiltinESMExports();const deps={...f.deps};delete deps.execute;
 const runtime=await createOwnedManualRefundRemainderRuntime({options:f.options,state:f.state},deps);f.limits.signal=abort.signal;
 const restoring=runtime.restore(f.limits);restoring.catch(()=>{});
 try{await entered.promise;abort.abort();await assert.rejects(restoring);let closed=false;
  const closing=runtime.stop({deadline:f.limits.deadline+120000}).finally(()=>{closed=true;});closing.catch(()=>{});
  await turn();assert.equal(closed,false);assert.equal(childClosed,false);
  blocked.resolve();await assert.rejects(closing);assert.equal(childClosed,true);assert.deepEqual(f.stopped,['postgres']);
 }finally{blocked.resolve();await Promise.allSettled([restoring]);stub.mock.restore();syncBuiltinESMExports();}
});
// Break caught: an acquired port server or its delayed close is lost when the caller's wait aborts.
for(const phase of ['acquire','listen','close'])test('port '+phase+' settles and server disposal completes before cleanup result',async()=>{
 const f=fixture(),entered=deferred(),blocked=deferred(),abort=new AbortController();let acquisitions=0,closes=0,disposed=false;
 f.deps.createServer=async()=>{const server=new EventEmitter();acquisitions++;
  server.listen=(_opts,callback)=>{if(phase==='listen'){entered.resolve();blocked.promise.then(()=>callback());}else queueMicrotask(callback);};
  server.close=callback=>{closes++;const close=()=>{disposed=true;server.emit('close');callback();};if(phase==='close'){entered.resolve();blocked.promise.then(close);}else queueMicrotask(close);};
  if(phase==='acquire'){entered.resolve();await blocked.promise;}return server;
 };
 const runtime=await f.open();f.limits.signal=abort.signal;const restoring=runtime.restore(f.limits);restoring.catch(()=>{});
 try{await entered.promise;abort.abort();await assert.rejects(restoring);let complete=false;
  const closing=runtime.stop({deadline:f.limits.deadline+120000}).finally(()=>{complete=true;});closing.catch(()=>{});
  await turn();if(phase!=='listen'){assert.equal(complete,false);assert.equal(disposed,false);}blocked.resolve();await assert.rejects(closing);
  assert.equal(disposed,true);assert.equal(acquisitions,1);assert.equal(closes,1);assert.deepEqual(f.started,[]);
 }finally{blocked.resolve();await Promise.allSettled([restoring]);}
});
test('stop command transport is shortened to the remaining original cleanup budget',async()=>{
 const {f,advance}=clockFixture(),runtime=await f.open();await runtime.restore(f.limits);advance(f.limits.deadline+115000);
 await runtime.stop({deadline:f.limits.deadline+120000});const stops=f.calls.filter(c=>c.args[2]==='container'&&c.args[3]==='stop');
 assert.equal(stops.length,5);assert.deepEqual(stops.map(c=>c.settings.timeout),[5000,5000,5000,5000,5000]);
 assert.equal(f.calls.filter(c=>!(c.args[2]==='container'&&c.args[3]==='stop')).every(c=>c.settings.timeout<=10000),true);
});
test('actual remainder state persists historical stopped snapshots and actual new StartedAt without changing retained history',async t=>{
 const retained=await retainedFixture(t);await fs.writeFile(retained.root+'/infrastructure/callback-receiver.mjs','synthetic receiver\n',{mode:0o600});
 const snapshots=await Promise.all(retained.priorPaths.map(p=>fs.readFile(p)));const state=retained.create();assert.equal(await state.admit(retained.limits),'READY');
 const original=state.target().original,f=fixture();Object.assign(f.target,original);Object.assign(f.options,retained.options);
 for(const service of ALL){const c=original.containers[service];Object.assign(f.projected[service],{Id:c.id,Name:'/'+c.name,Image:c.image,Mounts:structuredClone(c.mounts),PortBindings:structuredClone(c.ports),User:service.startsWith('issuer-')?(service==='issuer-installer'?'0:0':'10001:10001'):service==='callback-receiver'?'node':original.images[service].user});
  if(ORDER.includes(service))f.projected[service].State.StartedAt=new Date(retained.deps.now()-5000).toISOString();
 }
 const execute=f.deps.execute;f.deps.execute=async(file,args,...rest)=>{if(file.endsWith('/docker')&&args[2]==='image'&&args[3]==='inspect'){const image=Object.values(original.images).find(i=>i.id===args.at(-1));return {stdout:JSON.stringify({Id:image.id,User:image.user}),stderr:''};}return execute(file,args,...rest);};
 Object.assign(f.deps,{root:retained.root,lstat:fs.lstat,realpath:fs.realpath,open:fs.open});
 const runtime=await createOwnedManualRefundRemainderRuntime({options:retained.options,state},f.deps);assert.equal(await runtime.restore(retained.limits),true);assert.deepEqual(f.started,ORDER);
 const durable=JSON.parse(await fs.readFile(retained.statePath,'utf8'));assert.deepEqual(durable.restore.map(v=>v.phase),['ACKNOWLEDGED','ACKNOWLEDGED','ACKNOWLEDGED','ACKNOWLEDGED','ACKNOWLEDGED']);
 assert.deepEqual(Object.keys(durable.stopped).sort(),['backend','callback-receiver','demo-site','mailpit','postgres']);
 for(const service of ORDER){assert.equal(durable.stopped[service].startedAt,new Date(retained.deps.now()-5000).toISOString());assert.equal(durable.restore.find(v=>v.service===service).startedAt,f.projected[service].State.StartedAt);}
 assert.equal(runtime.resources(),state.target());let credentials=0;
 await runtime.withAdminCredentials(value=>{assert.equal(value.username,retained.data.app.PAYMENT_ADMIN_BOOTSTRAP_USERNAME);credentials++;return true;},retained.limits);assert.equal(credentials,1);
 await runtime.verifyWebhookSecret(retained.data.final.PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET,retained.limits);await runtime.verifyForwardTarget(retained.limits);
 assert.equal(await runtime.stop({deadline:retained.limits.deadline+120000}),true);assert.equal(await state.close({deadline:retained.limits.deadline+120000}),true);
 assert.deepEqual(await Promise.all(retained.priorPaths.map(p=>fs.readFile(p))),snapshots);
});
for(const value of ['2026-02-30T10:00:00Z','2026-10-20T24:00:00Z'])test('calendar-normalized StartedAt is rejected: '+value,async()=>{
 const f=fixture();for(const service of ALL)f.target.containers[service].startedAt='2026-01-01T00:00:00Z';for(const service of ALL)f.projected[service].State.StartedAt='2026-01-01T00:00:00Z';
 f.projected.postgres.State.StartedAt=value;const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.started,[]);assert.deepEqual(f.snapshots,[]);
});
test('no start runs before the captured stopped state durable write settles',async()=>{
 const f=fixture(),entered=deferred(),blocked=deferred(),record=f.state.recordStopped;
 f.state.recordStopped=async(...args)=>{entered.resolve();await blocked.promise;return record(...args);};const runtime=await f.open();const restoring=runtime.restore(f.limits);
 await entered.promise;await turn();assert.deepEqual(f.started,[]);blocked.resolve();await restoring;assert.equal(f.snapshots.length,1);await runtime.stop({deadline:f.limits.deadline+120000});
});
test('failed captured stopped state fsync denies every start and acknowledgement',async()=>{
 const f=fixture();f.state.recordStopped=async()=>{throw Error('SYNTHETIC_PRIVATE_FSYNC');};const runtime=await f.open();
 await assert.rejects(runtime.restore(f.limits),{message:'stripe_manual_refund_remainder_runtime_PENDING'});assert.deepEqual(f.started,[]);assert.deepEqual(f.events,[]);
});
test('a live proof that settles beyond 45 seconds cannot authorize acknowledgement',async()=>{
 const {f,advance}=clockFixture(),execute=f.deps.execute,entered=deferred(),blocked=deferred();let intercepted=false;
 f.deps.execute=async(file,args,...rest)=>{const value=await execute(file,args,...rest);if(!intercepted&&f.started.length&&args[2]==='container'&&args[3]==='inspect'){intercepted=true;entered.resolve();await blocked.promise;}return value;};
 const runtime=await f.open(),restoring=runtime.restore(f.limits);restoring.catch(()=>{});await entered.promise;
 advance(f.limits.deadline-14999);blocked.resolve();await assert.rejects(restoring);assert.equal(f.events.includes('ack:postgres'),false);assert.deepEqual(f.started,['postgres']);
 await assert.rejects(runtime.stop({deadline:f.limits.deadline+120000}));
});
test('construction is inert and the capability cannot restore twice or renew cleanup',async()=>{
 const f=fixture(),runtime=await f.open();assert.deepEqual(f.calls,[]);assert.deepEqual(f.events,[]);assert.ok(Object.isFrozen(runtime));
 await runtime.restore(f.limits);const count=f.calls.length;await assert.rejects(runtime.restore(f.limits));assert.equal(f.calls.length,count);
 await assert.rejects(runtime.stop({deadline:f.limits.deadline+120001}));assert.equal(f.calls.length,count);
 await runtime.stop({deadline:f.limits.deadline+120000});
});
// Break caught: a second clean projection cannot erase observed ownership or transport failure.
for(const kind of ['ID','image','label','mount','network','timestamp','transport','starting port'])test('readiness permanently rejects transient '+kind+' failure',async()=>{
 const f=fixture(),execute=f.deps.execute;let observations=0;
 f.deps.execute=async(file,args,...rest)=>{const result=await execute(file,args,...rest);
  if(f.started.length===1&&args[2]==='container'&&args[3]==='inspect'&&args.at(-1)===f.target.containers.postgres.id&&++observations===1){
   if(kind==='transport')throw Error('SYNTHETIC_PRIVATE_TRANSPORT');const value=JSON.parse(result.stdout);
   if(kind==='ID')value.Id=id(999);if(kind==='image')value.Image='sha256:'+id(999);if(kind==='label')value.Labels['payment.sandbox.run']='other';
   if(kind==='mount')value.Mounts[0].RW=false;if(kind==='network')value.Networks[PROJECT+'-network'].NetworkID=id(999);if(kind==='timestamp')value.State.StartedAt=f.target.containers.postgres.startedAt;
   if(kind==='starting port'){value.State.Health='starting';value.Ports['5432/tcp'][0].HostPort='19999';}result.stdout=JSON.stringify(value);
  }return result;
 };
 f.deps.setTimeout=(callback,ms)=>setTimeout(callback,ms===1500?0:ms);
 const runtime=await f.open();await assert.rejects(runtime.restore(f.limits),{message:'stripe_manual_refund_remainder_runtime_PENDING'});
 assert.equal(observations,1);assert.equal(f.events.includes('ack:postgres'),false);assert.deepEqual(f.started,['postgres']);
 await runtime.stop({deadline:f.limits.deadline+120000});
});
test('owned running service with starting health can become healthy on the next readiness observation',async()=>{
 const f=fixture(),execute=f.deps.execute;let observations=0;
 f.deps.execute=async(file,args,...rest)=>{const result=await execute(file,args,...rest);if(f.started.length===1&&args[2]==='container'&&args[3]==='inspect'&&args.at(-1)===f.target.containers.postgres.id&&++observations===1){const value=JSON.parse(result.stdout);value.State.Health='starting';result.stdout=JSON.stringify(value);}return result;};
 f.deps.setTimeout=(callback,ms)=>setTimeout(callback,ms===1500?0:ms);const runtime=await f.open();await runtime.restore(f.limits);assert.ok(observations>=2);assert.deepEqual(f.started,ORDER);assert.equal(f.events.includes('ack:postgres'),true);await runtime.stop({deadline:f.limits.deadline+120000});
});
test('starting health pins the new timestamp before the subsequent healthy observation',async()=>{
 const f=fixture(),execute=f.deps.execute;let observations=0;
 f.deps.execute=async(file,args,...rest)=>{const result=await execute(file,args,...rest);if(f.started.length===1&&args[2]==='container'&&args[3]==='inspect'&&args.at(-1)===f.target.containers.postgres.id){const value=JSON.parse(result.stdout);observations++;if(observations===1)value.State.Health='starting';else value.State.StartedAt=new Date(Date.parse(value.State.StartedAt)-1).toISOString();result.stdout=JSON.stringify(value);}return result;};
 f.deps.setTimeout=(callback,ms)=>setTimeout(callback,ms===1500?0:ms);const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.equal(observations,2);assert.equal(f.events.includes('ack:postgres'),false);assert.deepEqual(f.started,['postgres']);
});
test('Docker nanosecond stopped regression within one millisecond denies capture',async()=>{
 const f=fixture();f.target.containers.postgres.startedAt='2026-10-19T10:00:00.123900000Z';f.projected.postgres.State.StartedAt='2026-10-19T10:00:00.123100000Z';
 const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.snapshots,[]);assert.deepEqual(f.started,[]);
});
test('Docker nanosecond later start within one millisecond is acknowledged and stopped',async()=>{
 const f=fixture(),execute=f.deps.execute;f.target.containers.postgres.startedAt='2026-10-19T10:00:00.123100000Z';f.projected.postgres.State.StartedAt='2026-10-19T10:00:00.123100000Z';
 f.deps.execute=async(file,args,...rest)=>{const result=await execute(file,args,...rest);if(args[2]==='container'&&args[3]==='start'&&args.at(-1)===f.target.containers.postgres.id)f.projected.postgres.State.StartedAt='2026-10-19T10:00:00.123900000Z';return result;};
 const runtime=await f.open();assert.equal(await runtime.restore(f.limits),true);assert.deepEqual(f.started,ORDER);assert.equal(f.events.includes('ack:postgres'),true);assert.equal(await runtime.stop({deadline:f.limits.deadline+120000}),true);
});
test('Docker nanosecond future within the current millisecond denies capture',async()=>{
 const f=fixture(),now=Date.parse('2026-10-20T12:00:00.123Z');f.deps.now=()=>now;f.limits.deadline=now+60000;f.projected.postgres.State.StartedAt='2026-10-20T12:00:00.123000001Z';
 const runtime=await f.open();await assert.rejects(runtime.restore(f.limits));assert.deepEqual(f.snapshots,[]);assert.deepEqual(f.started,[]);
});
