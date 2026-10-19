import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {manualFormOptIn,validateManualFormOptions} from './stripe-manual-form-options.mjs';
import {validateManualFormReport,formatManualFormReport} from './stripe-manual-form-schema.mjs';
const EMPTY=Object.freeze({version:1,mode:'MANUAL_FORM_DIAGNOSTIC',observation:'NOT_STARTED',end:'FAILED',sample:null,
  no_confirmation:false,gateway_closed:false,runtime_closed:false,browser_closure:'NOT_OWNED',provenance:'BROWSER_REPORTED'});
const failed=()=>Object.freeze({report:EMPTY,exitCode:1});
const FLAGS=['PAYMENT_STRIPE_SANDBOX_ENABLED','PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY',
  'PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_FULL_RUN','PAYMENT_STRIPE_SANDBOX_OWNED_RUN','PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS',
  'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'];
const PORTS=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
function exact(value,keys){
  try{
    if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;
    const descriptors=Object.getOwnPropertyDescriptors(value),names=Reflect.ownKeys(descriptors);
    if(names.length!==keys.length||names.some(key=>typeof key!=='string'||!keys.includes(key)))return null;
    const copy={};for(const key of keys){const d=descriptors[key];if(!d||!d.enumerable||!Object.hasOwn(d,'value'))return null;copy[key]=d.value;}return copy;
  }catch{return null;}
}
function limitSnapshot(value){
  const l=exact(value,['deadline','signal'])??exact(value,['deadline']);
  if(!l||!Number.isSafeInteger(l.deadline)||(l.signal!==undefined&&!(l.signal instanceof AbortSignal)))return null;
  return Object.freeze(l);
}
function gatewayObservation(value){
  const o=exact(value,['observation','end','sample']);if(!o)return null;
  const r=validateManualFormReport({...EMPTY,...o,no_confirmation:true,gateway_closed:true,runtime_closed:true});
  return r?{observation:r.observation,end:r.end,sample:r.sample}:null;
}
const stdout=text=>new Promise((accept,reject)=>{
  const error=()=>reject();process.stdout.once('error',error);
  process.stdout.write(text,failed=>{if(failed){reject();return;}process.stdout.removeListener('error',error);accept();});
});

/** One invocation owns one original budget, one fixture, one gateway and one final financial read. */
export async function runManualFormDiagnostic(options,originalLimits,dependencies={}){
  // Strict admission precedes dependencies, clocks, imports and private configuration.
  const selected=validateManualFormOptions(options),limits=limitSnapshot(originalLimits);
  if(!selected||!limits)return failed();
  let now,setTimer,clearTimer;
  try{
    now=dependencies.now??Date.now;
    if(!Number.isSafeInteger(now())||limits.deadline>now()+900000||limits.deadline<=now()+120000||limits.signal?.aborted)return failed();
    setTimer=dependencies.setTimeout??setTimeout;clearTimer=dependencies.clearTimeout??clearTimeout;
  }catch{return failed();}
  const cleanup=Object.freeze({deadline:limits.deadline+120000}),latestStart=limits.deadline-120000;
  const canonical='http://127.0.0.1:'+selected.manualPort+'/form-load-manual.html';
  let runtime,gateway,runtimeCloseUsed=false,gatewayCloseTask,gatewayCreationStarted=false,cleanupStarted=false,finalized=false,hasFailed=false;
  let noConfirmation=false,gatewayClosed=false,runtimeClosed=false,observation={observation:'NOT_STARTED',end:'FAILED',sample:null};
  const owned=new Set(),runtimeWork=new Set();
  function authority(){return !limits.signal?.aborted&&now()<limits.deadline;}
  function dominant(){return limits.signal?.aborted?'ABORTED':now()>=limits.deadline?'ACTIVE_DEADLINE':null;}
  function callBefore(fn,receiver,args,deadline=latestStart){
    // Loading a factory/capability can yield; recheck immediately before action.
    if(limits.signal?.aborted||now()>=deadline)throw null;
    return fn.apply(receiver,args);
  }
  function bounded(promise,deadline,signal){
    return new Promise((accept,reject)=>{
      let timer,done=false;
      const finish=(ok,value)=>{if(done)return;done=true;clearTimer(timer);signal?.removeEventListener('abort',abort);ok?accept(value):reject();};
      const abort=()=>finish(false);
      Promise.resolve(promise).then(value=>{finish(now()<deadline&&!signal?.aborted,value);},()=>finish(false));
      if(now()>=deadline||signal?.aborted){finish(false);return;}
      signal?.addEventListener('abort',abort,{once:true});timer=setTimer(abort,Math.max(1,deadline-now()));
    });
  }
  function start(work,kind,onValue){
    const promise=Promise.resolve().then(work).then(value=>{onValue?.(value);return value;});
    if(kind){owned.add(promise);if(kind==='runtime')runtimeWork.add(promise);}
    const settled=()=>{owned.delete(promise);runtimeWork.delete(promise);if(finalized)lateRuntimeClose();};
    promise.then(settled,settled);return promise;
  }
  function closeRuntime(){
    if(!runtime||runtimeCloseUsed)return null;runtimeCloseUsed=true;
    return start(()=>runtime.close(cleanup),'close',value=>{runtimeClosed=value===true&&now()<cleanup.deadline;});
  }
  function closeGateway(){
    if(!gateway||gatewayCloseTask)return gatewayCloseTask;
    gatewayCloseTask=start(()=>gateway.close(cleanup),'gateway',value=>{gatewayClosed=value===true&&now()<cleanup.deadline;});
    gatewayCloseTask.catch(()=>{hasFailed=true;});return gatewayCloseTask;
  }
  function lateRuntimeClose(){
    // A late acquisition never becomes unowned. Original expired cleanup limits
    // are retained; this best-effort call cannot change an already returned proof.
    if(runtime&&runtimeWork.size===0&&!runtimeCloseUsed){const p=closeRuntime();p?.catch(()=>{});}
  }
  async function settleRuntime(deadline){
    if(runtimeWork.size)await bounded(Promise.allSettled([...runtimeWork]),deadline);
  }
  try{
    const admission=await bounded(start(async()=>{
      const preflight=dependencies.preflight??(await import('./stripe-sandbox-owned.mjs')).preflightOwnedManualFormDiagnostic;
      return callBefore(preflight,null,[selected]);
    }),latestStart,limits.signal);
    if(exact(admission,['status'])?.status!=='READY')throw null;
    await bounded(start(async()=>{
      const create=dependencies.createRuntime??(await import('./stripe-form-diagnostic-runtime.mjs')).createManualFormDiagnosticRuntime;
      return callBefore(create,null,[selected]);
    },'runtime',value=>{runtime=value;}),latestStart,limits.signal);
    const handoff=await bounded(start(()=>callBefore(runtime.prepare,runtime,[limits]),'runtime'),latestStart,limits.signal);
    const h=exact(handoff,['runId','expectedSource','endpoint','assetEndpoint','session']);
    if(!h||h.runId!==selected.runId||h.expectedSource!==selected.expectedSource||h.endpoint!=='http://127.0.0.1:'+selected.manualPort
      ||h.assetEndpoint!=='http://127.0.0.1:'+selected.ports.demo||now()>=latestStart)throw null;
    gatewayCreationStarted=true;
    await bounded(start(async()=>{
      const create=dependencies.createGateway??(await import('./stripe-manual-form-gateway.mjs')).createManualFormGateway;
      return callBefore(create,null,[{origin:h.endpoint,assetEndpoint:h.assetEndpoint,session:h.session},limits]);
    },'gateway',value=>{gateway=value;if(cleanupStarted)closeGateway();}),latestStart,limits.signal);
    const opened=await bounded(start(()=>callBefore(gateway.open,gateway,[]),'gateway'),latestStart,limits.signal);
    if(exact(opened,['url'])?.url!==canonical)throw null;
    await bounded(start(()=>callBefore(dependencies.write??stdout,null,[canonical+'\n'],limits.deadline-60000)),limits.deadline-60000,limits.signal);
    observation={observation:'NO_REPORT',end:'FAILED',sample:null};
    const observed=gatewayObservation(await bounded(start(()=>callBefore(gateway.wait,gateway,[],limits.deadline-60000)),limits.deadline-60000,limits.signal));
    if(!observed){observation={observation:'INVALID_REPORT',end:'FAILED',sample:null};throw null;}
    observation=observed;
  }catch{hasFailed=true;}
  finally{
    cleanupStarted=true;
    if(gateway){
      const closing=closeGateway();
      // Leave a financial-read window within original active authority. Closing
      // work remains owned and must settle before a positive final closure claim.
      const closeWait=Math.min(cleanup.deadline,now()+30000,authority()?Math.max(now()+1,limits.deadline-30000):cleanup.deadline);
      try{if(await bounded(closing,closeWait)!==true)hasFailed=true;}catch{hasFailed=true;}
    }else if(runtime&&!gatewayCreationStarted)gatewayClosed=true;
    try{await settleRuntime(limits.deadline);}catch{hasFailed=true;}
    if(runtime&&runtimeWork.size===0&&authority()){
      try{
        noConfirmation=await bounded(start(()=>callBefore(runtime.verifyNoConfirmation,runtime,[limits],limits.deadline),'runtime'),limits.deadline,limits.signal)===true&&authority();
      }catch{hasFailed=true;}
    }
    try{await settleRuntime(cleanup.deadline);}catch{hasFailed=true;}
    if(runtime&&runtimeWork.size===0){
      try{if(await bounded(closeRuntime(),cleanup.deadline)!==true)hasFailed=true;}catch{hasFailed=true;}
    }
    try{while(owned.size)await bounded(Promise.allSettled([...owned]),cleanup.deadline);}catch{hasFailed=true;}
    finalized=true;lateRuntimeClose();
  }
  let end=dominant()??observation.end;
  if(!noConfirmation||!gatewayClosed||!runtimeClosed||hasFailed)end=dominant()??'FAILED';
  // An invalid report has no accepted history, even if abort/deadline dominates.
  if(observation.observation==='INVALID_REPORT'||observation.observation==='TERMINAL_REPORTED')end='FAILED';
  const report=validateManualFormReport({...EMPTY,...observation,end,no_confirmation:noConfirmation,gateway_closed:gatewayClosed,runtime_closed:runtimeClosed})??EMPTY;
  return Object.freeze({report,exitCode:report.end==='READY_REPORTED'?0:1});
}

function rawData(env,key){
  const d=Object.getOwnPropertyDescriptor(env,key);
  if(d&&(!Object.hasOwn(d,'value')||!d.enumerable))throw null;
  return d?.value;
}
/** process.env is exotic: snapshot allowlisted raw data into a plain record first. */
export async function runManualFormCli(input,dependencies={}){
  let selected,started;
  try{
    const env=Object.fromEntries(FLAGS.map(key=>[key,rawData(input.env,key)]));
    if(!manualFormOptIn(env)||!Array.isArray(input.args)||input.args.length!==0)return await emit(failed());
    started=(dependencies.now??Date.now)();
    const number=key=>{const value=rawData(input.env,key);if(typeof value!=='string'||! /^[1-9][0-9]{3,4}$/.test(value))throw null;return Number(value);};
    selected=validateManualFormOptions({env,runId:rawData(input.env,'PAYMENT_STRIPE_SANDBOX_RUN_ID'),expectedSource:rawData(input.env,'PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE'),
      daemon:rawData(input.env,'PAYMENT_STRIPE_SANDBOX_DAEMON'),ports:Object.fromEntries(PORTS.map(key=>[key,number('PAYMENT_STRIPE_SANDBOX_PORT_'+key.toUpperCase())])),manualPort:number('PAYMENT_STRIPE_SANDBOX_PORT_MANUAL')});
    if(!selected)return await emit(failed());
  }catch{return emit(failed());}
  const controller=new AbortController();
  const stop=()=>controller.abort();
  // Signal ownership exists only during an explicit direct invocation.
  const subscribe=dependencies.subscribe??(callback=>{process.once('SIGINT',callback);process.once('SIGTERM',callback);return ()=>{process.removeListener('SIGINT',callback);process.removeListener('SIGTERM',callback);};});
  let unsubscribe;
  try{
    unsubscribe=subscribe(stop);
    return await emit(await runManualFormDiagnostic(selected,{deadline:started+900000,signal:controller.signal},dependencies));
  }catch{return emit(failed());}
  finally{unsubscribe?.();}
  async function emit(value){
    try{await (dependencies.write??stdout)(formatManualFormReport(value.report));return value;}
    catch{
      const end=['ABORTED','ACTIVE_DEADLINE'].includes(value.report.end)?value.report.end:'FAILED';
      return Object.freeze({report:validateManualFormReport({...value.report,end})??EMPTY,exitCode:1});
    }
  }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  // No arbitrary flags, inherited child environments, private values or raw errors.
  runManualFormCli({env:process.env,args:process.argv.slice(2)}).then(value=>{process.exitCode=value.exitCode;},()=>{process.exitCode=1;});
}
