import {execFile} from 'node:child_process';
import {lstat,realpath,open} from 'node:fs/promises';
import {constants} from 'node:fs';
import {createServer} from 'node:net';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateManualRefundOptions} from './stripe-manual-refund-options.mjs';
import {createFormDiagnosticSettlement} from './stripe-form-diagnostic-settlement.mjs';
import {proveOwnedContainer,sameOwnedProjection as same,CONTAINER_FORMAT,NETWORK_FORMAT,VOLUME_FORMAT} from './stripe-sandbox-ownership.mjs';
const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..'),DOCKER='/Applications/Docker.app/Contents/Resources/bin/docker';
const ORIGINAL='031b5ae7-6603-4339-b1b3-77231291ff34',SOURCE='f0620e40004162d2b3eededa60512f50a492f4cc';
const ORDER=['postgres','mailpit','callback-receiver','demo-site','backend'],ALL=[...ORDER,'issuer-installer','issuer-probe'];
const ENV=Object.freeze({PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C'}),fail=()=>{throw null;},fixed=()=>new Error('stripe_manual_refund_runtime_UNCONFIRMED');
function executeAwaited(file,args,settings){return new Promise((resolveCall,rejectCall)=>{let done=false,closed=false,error,result;const finish=()=>{if(done&&closed)error?rejectCall(null):resolveCall(result);};const child=execFile(file,args,settings,(e,stdout,stderr)=>{error=e;result={stdout,stderr};done=true;finish();});child.once('close',()=>{closed=true;finish();});});}
function exact(value,keys){if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))fail();const d=Object.getOwnPropertyDescriptors(value);if(Reflect.ownKeys(d).length!==keys.length||keys.some(k=>!d[k]?.enumerable||!Object.hasOwn(d[k],'value')))fail();return Object.fromEntries(keys.map(k=>[k,d[k].value]));}
/** Restores only the already retained topology. No Compose, creation or credential getters. */
export async function createOwnedManualRefundRuntime(input,dependencies={}){
  let options,state,target,root,io;
  try{
    ({options,state}=exact(input,['options','state']));
    options=validateManualRefundOptions(options);
    if(!options)fail();
    target=state.target;
    if(target.originalRunId!==ORIGINAL||target.originalSource!==SOURCE
      ||target.project!=='stripe-'+ORIGINAL
      ||Object.keys(target.containers).sort().join(',')!==[...ALL].sort().join(','))fail();
    root=dependencies.root??ROOT;
    if(typeof root!=='string'||resolve(root)!==root)fail();
    io={execute:dependencies.execute??executeAwaited,lstat:dependencies.lstat??lstat,
      realpath:dependencies.realpath??realpath,open:dependencies.open??open,
      createServer:dependencies.createServer??createServer,now:dependencies.now??Date.now,
      setTimeout:dependencies.setTimeout??setTimeout,clearTimeout:dependencies.clearTimeout??clearTimeout};
  }catch{throw fixed();}

  const settlement=createFormDiagnosticSettlement(io);
  const dispatched=new Set(),running=new Set(),pendingStarts=new Map();
  const stopping=new Map(),startedRecords=new Map(),outstanding=new Set(),bodies=new Set();
  const resources=new Map(),releases=new Map();
  let originalDeadline=null,busy=false,failed=false,closed=false,restored=false;
  let restoreUsed=false,stopUsed=false,socketIdentity=null,resourceFailure=false;

  function limits(value,cleanup=false){
    const d=Object.getOwnPropertyDescriptors(value??{});
    const selected=exact(value,Object.hasOwn(d,'signal')?['deadline','signal']:['deadline']);
    if(!Number.isSafeInteger(selected.deadline)||selected.deadline<=io.now()
      ||selected.signal!==undefined&&!(selected.signal instanceof AbortSignal)
      ||selected.signal?.aborted)fail();
    if(originalDeadline===null){
      if(cleanup||selected.deadline>io.now()+1800000)fail();
      originalDeadline=selected.deadline;
      settlement.bind(originalDeadline);
    }
    if(cleanup?selected.deadline>originalDeadline+120000:selected.deadline!==originalDeadline)fail();
    return Object.freeze(selected);
  }
  function check(context,cleanup=false){
    if(context.signal?.aborted||io.now()>=context.deadline||!cleanup&&(closed||failed))fail();
  }
  // A caller's canceled wait never removes the raw IO or disposal obligation.
  function track(body,set=outstanding){
    const work=Promise.resolve().then(body);
    set.add(work);
    work.then(()=>set.delete(work),()=>set.delete(work));
    work.catch(()=>{});
    return work;
  }
  function own(value,dispose){
    if(resources.has(value)||releases.has(value))fail();
    resources.set(value,dispose);
  }
  function releaseOwned(value){
    if(releases.has(value))return releases.get(value);
    const dispose=resources.get(value);
    if(!dispose)fail();
    const work=track(async()=>{
      if(originalDeadline===null||io.now()>=originalDeadline+120000)fail();
      await dispose(value);
      resources.delete(value);
      if(io.now()>=originalDeadline+120000)fail();
    });
    releases.set(value,work);
    work.catch(()=>{resourceFailure=true;});
    return work;
  }
  function call(context,body,dispose){
    check(context);
    return settlement.call(context,()=>track(async()=>{
      check(context);
      const value=await body();
      if(dispose)own(value,dispose);
      return value;
    }),dispose?releaseOwned:undefined);
  }
  function primitive(context,body,cleanup=false){
    check(context,cleanup);
    return cleanup?settlement.wait(context,()=>track(()=>{
      check(context,true);
      return body();
    })):call(context,body);
  }
  function release(value){return track(()=>settlement.release(value));}
  function settings(context){
    return {cwd:root,env:{...ENV,GIT_OPTIONAL_LOCKS:'0',GIT_CONFIG_GLOBAL:'/dev/null',
      GIT_CONFIG_SYSTEM:'/dev/null',DOCKER_CONFIG:'/private/var/empty'},shell:false,
      timeout:Math.min(10000,Math.max(1,context.deadline-io.now())),maxBuffer:65536,signal:context.signal};
  }
  async function command(file,args,context,cleanup=false){
    const result=await primitive(context,()=>io.execute(file,args,settings(context)),cleanup);
    check(context,cleanup);
    if(typeof result?.stdout!=='string'||typeof result.stderr!=='string'||result.stderr!==''
      ||Buffer.byteLength(result.stdout)+Buffer.byteLength(result.stderr)>65536)fail();
    return result.stdout;
  }
  const docker=(args,context,cleanup=false)=>command(DOCKER,['--host',options.daemon,...args],context,cleanup);
  const inspect=async(kind,id,format,context,cleanup=false)=>
    JSON.parse(await docker([kind,'inspect','--format',format,id],context,cleanup));

  async function directories(path,context,cleanup=false){
    for(let current=path;;current=dirname(current)){
      const s=await primitive(context,()=>io.lstat(current),cleanup);
      if(!s.isDirectory()||s.isSymbolicLink()||![0,process.getuid()].includes(s.uid)
        ||await primitive(context,()=>io.realpath(current),cleanup)!==current)fail();
      if(current==='/')break;
    }
  }
  async function socket(context,cleanup=false){
    const path=new URL(options.daemon).pathname;
    await directories(dirname(path),context,cleanup);
    const s=await primitive(context,()=>io.lstat(path),cleanup);
    if(!s.isSocket()||s.isSymbolicLink()||s.uid!==process.getuid()
      ||await primitive(context,()=>io.realpath(path),cleanup)!==path)fail();
    const identity={ino:s.ino,dev:s.dev};
    if(socketIdentity&&!same(socketIdentity,identity))fail();
    socketIdentity??=identity;
    check(context,cleanup);
  }
  async function receiverBlob(context){
    const path=root+'/infrastructure/callback-receiver.mjs';
    await directories(dirname(path),context);
    const valid=s=>s.isFile()&&!s.isSymbolicLink()&&[0,process.getuid()].includes(s.uid)
      &&s.nlink===1&&Number.isSafeInteger(s.size)&&s.size>=0&&s.size<=65536;
    const named=await call(context,()=>io.lstat(path));
    if(!valid(named)||await call(context,()=>io.realpath(path))!==path)fail();
    const file=await call(context,()=>io.open(path,constants.O_RDONLY|constants.O_NOFOLLOW),file=>file.close());
    try{
      const first=await call(context,()=>file.stat());
      if(!valid(first)||first.ino!==named.ino||first.dev!==named.dev)fail();
      const bytes=Buffer.alloc(first.size+1);
      const read=await call(context,()=>file.read(bytes,0,bytes.length,0));
      const last=await call(context,()=>file.stat());
      const again=await call(context,()=>io.lstat(path));
      if(read.bytesRead!==first.size
        ||!same([first.ino,first.dev,first.size,first.mtimeMs,first.ctimeMs],
          [last.ino,last.dev,last.size,last.mtimeMs,last.ctimeMs])
        ||!valid(again)||again.ino!==first.ino||again.dev!==first.dev)fail();
      const expected=await command('/usr/bin/git',['show',SOURCE+':infrastructure/callback-receiver.mjs'],context);
      if(!bytes.subarray(0,read.bytesRead).equals(Buffer.from(expected)))fail();
    }finally{await release(file);}
  }
  async function source(context){
    if((await command('/usr/bin/git',['rev-parse','HEAD'],context)).trim()!==options.expectedSource
      ||await command('/usr/bin/git',['-c','core.fsmonitor=false','status','--porcelain=v1','--untracked-files=no'],context)!=='')fail();
    await command('/usr/bin/git',['merge-base','--is-ancestor',SOURCE,options.expectedSource],context);
    await socket(context);
    await receiverBlob(context);
  }
  async function infrastructure(context,cleanup=false){
    if(!same(await inspect('network',target.network.Id,NETWORK_FORMAT,context,cleanup),target.network))fail();
    for(const v of Object.values(target.volumes)){
      if(!same(await inspect('volume',v.Name,VOLUME_FORMAT,context,cleanup),v))fail();
    }
  }
  async function container(service,context,{live=false,cleanup=false}={}){
    const retained=target.containers[service];
    const value=await inspect('container',retained.id,CONTAINER_FORMAT,context,cleanup);
    const helper=service.startsWith('issuer-');
    const proof=proveOwnedContainer(value,{service,project:target.project,runId:ORIGINAL,
      network:target.network,sourceImage:target.images[helper?'backend':service],
      recordedContainer:startedRecords.get(service)??retained,expectedMounts:retained.mounts,
      expectedPorts:retained.ports,liveRequired:live,cleanupOnly:true,backendStripeEnabled:true});
    if(proof.infrastructureRequired)await infrastructure(context,cleanup);
    if(helper&&(proof.state.Running||proof.state.Status!=='exited'||proof.state.ExitCode!==0
      ||proof.state.StartedAt!==retained.startedAt))fail();
    if(!live&&!cleanup&&(proof.state.Running||proof.state.Status!=='exited'
      ||proof.state.StartedAt!==retained.startedAt))fail();
    return proof;
  }
  async function topology(context){
    await source(context);
    await infrastructure(context);
    const ids=(await docker(['ps','--all','--no-trunc','--filter',
      'label=com.docker.compose.project='+target.project,'--format','{{.ID}}'],context)).trim().split('\n');
    if(!same(ids.sort(),Object.values(target.containers).map(c=>c.id).sort()))fail();
    for(const image of Object.values(target.images)){
      const actual=await inspect('image',image.id,'{"Id":{{json .ID}},"User":{{json .Config.User}}}',context);
      if(actual.Id!==image.id||actual.User!==image.user)fail();
    }
    for(const service of ALL)await container(service,context,{live:running.has(service)});
    check(context);
  }
  async function available(port,context){
    return call(context,()=>new Promise((resolveProbe,rejectProbe)=>{
      const server=io.createServer(socket=>socket.destroy());
      let done=false;
      const finish=error=>{
        if(done)return;
        done=true;
        if(io.now()>=originalDeadline+120000){rejectProbe(null);return;}
        try{
          server.close(closeError=>error||closeError?rejectProbe(null):resolveProbe(true));
        }catch{rejectProbe(null);}
      };
      server.once('error',finish);
      server.listen({port,host:'127.0.0.1',exclusive:true,signal:context.signal},()=>finish());
    }));
  }
  async function stopOne(service,context){
    if(stopping.has(service))return stopping.get(service);
    const work=(async()=>{
      check(context,true);
      await socket(context,true);
      await infrastructure(context,true);
      const proof=await container(service,context,{cleanup:true});
      if(proof.state.Running){
        startedRecords.set(service,proof.safe);
        await docker(['container','stop','--time','10',target.containers[service].id],context,true);
        const after=await container(service,context,{cleanup:true});
        if(after.state.Running||after.state.Status!=='exited')fail();
      }
      running.delete(service);
      return true;
    })();
    stopping.set(service,work);
    work.catch(()=>{});
    return work;
  }
  async function drain(context){
    for(;;){
      check(context,true);
      // Late opens are registered before raw acquisition settles. Once operation
      // bodies are fenced, release those resources without duplicate closes.
      if(!bodies.size)for(const value of resources.keys())releaseOwned(value).catch(()=>{});
      const waiting=[...outstanding,...bodies];
      if(!waiting.length){if(resources.size||resourceFailure)fail();return;}
      await settlement.wait(context,()=>Promise.allSettled(waiting));
    }
  }
  async function cleanup(context){
    let ok=true;
    // Each service has its own drain/stop path. A later unsettled start must not
    // consume the earlier services' independent cleanup opportunity.
    const closures=[...ORDER].reverse().filter(service=>dispatched.has(service)).map(async service=>{
      const start=pendingStarts.get(service);
      if(start)await settlement.wait(context,()=>start.catch(()=>{}));
      return stopOne(service,context);
    });
    const results=await settlement.wait(context,()=>Promise.allSettled(closures)).catch(()=>{ok=false;return [];});
    if(results.some(result=>result.status==='rejected'))ok=false;
    try{await drain(context);}catch{ok=false;}
    try{if(!settlement.close(context))ok=false;}catch{ok=false;}
    if(!ok||pendingStarts.size||outstanding.size||bodies.size||resources.size)fail();
    return true;
  }
  async function run(value,body){
    if(busy){failed=true;throw fixed();}
    busy=true;
    try{
      const context=limits(value);
      check(context);
      const work=track(()=>body(context),bodies);
      const result=await settlement.wait(context,()=>work);
      check(context);
      return result;
    }catch{failed=true;throw fixed();}
    finally{busy=false;}
  }
  async function start(service,context){
    check(context);
    const raw=track(()=>{
      check(context);
      return io.execute(DOCKER,['--host',options.daemon,'container','start',target.containers[service].id],settings(context));
    });
    pendingStarts.set(service,raw);
    raw.finally(()=>pendingStarts.delete(service)).catch(()=>{});
    const work=track(async()=>{
      try{
        const result=await raw;
        if(result?.stdout!==target.containers[service].id+'\n'||result.stderr!=='')fail();
      }finally{
        if(closed||failed||context.signal?.aborted||io.now()>=context.deadline){
          const cleanupLimits={deadline:originalDeadline+120000};
          if(io.now()<cleanupLimits.deadline)await stopOne(service,cleanupLimits);
        }
      }
    });
    await settlement.wait(context,()=>work);
    check(context);
  }
  async function restore(context){
    if(restoreUsed)fail();
    restoreUsed=true;
    await topology(context);
    for(const port of Object.values(target.ports))await available(port,context);
    for(const service of ORDER){
      await topology(context);
      if(await state.beginRestore(service,context)!==true)fail();
      await topology(context);
      check(context);
      dispatched.add(service);
      await start(service,context);
      const until=Math.min(context.deadline,io.now()+45000);
      let ready=false;
      for(let probe=0;probe<30&&io.now()<until;probe++){
        check(context);
        try{
          const proof=await container(service,context,{live:true});
          startedRecords.set(service,proof.safe);
          ready=true;
          break;
        }catch{
          if(probe===29||io.now()+1500>=until)fail();
          await call(context,()=>new Promise(resolveWait=>io.setTimeout(resolveWait,1500)));
        }
      }
      if(!ready)fail();
      running.add(service);
      await topology(context);
      if(await state.ackRestore(service,context)!==true)fail();
    }
    restored=true;
    return true;
  }
  return Object.freeze({
    resources:()=>target,
    restore:value=>run(value,restore),
    verify:value=>run(value,async context=>{
      if(!restored)fail();
      await topology(context);
      return true;
    }),
    withAdminCredentials:(callback,value)=>run(value,async context=>{
      if(!restored||typeof callback!=='function')fail();
      await topology(context);
      if(await state.withRetainedAdmin(async credentials=>{
        check(context);
        await topology(context);
        check(context);
        if(await callback(credentials)!==true)fail();
        check(context);
        return true;
      },context)!==true)fail();
      return true;
    }),
    verifyForwardTarget:value=>run(value,async context=>{
      if(!restored)fail();
      await topology(context);
      return true;
    }),
    verifyWebhookSecret:(candidate,value)=>run(value,async context=>{
      if(!restored)fail();
      await topology(context);
      if(await state.verifyRetainedWebhookSecret(candidate,context)!==true)fail();
      check(context);
      return true;
    }),
    stop:async value=>{
      closed=true;
      failed=true;
      try{
        const context=limits(value,true);
        if(stopUsed)fail();
        stopUsed=true;
        return await cleanup(context);
      }catch{throw fixed();}
    },
  });
}
