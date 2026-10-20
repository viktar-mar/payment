import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {manualRefundOptIn,validateManualRefundOptions} from './stripe-manual-refund-options.mjs';
import {validateManualRefundReport,formatManualRefundReport,validateManualRefundDiagnostic} from './stripe-manual-refund-schema.mjs';

const ROOT=fileURLToPath(new URL('..',import.meta.url)).replace(/\/$/,'');
const ORIGINAL='031b5ae7-6603-4339-b1b3-77231291ff34';
const FLAGS=['MANUAL_REFUND_REMAINDER','ENABLED','ALLOW_TEST_OPERATIONS','MANUAL_REFUND','FULL_RUN','OWNED_RUN','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT'].map(k=>'PAYMENT_STRIPE_SANDBOX_'+k).concat(['DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE']);
const EMPTY=Object.freeze({version:1,mode:'MANUAL_REFUND',result:'FAILED',phase:'ADMISSION',target_verified:false,callback_rotated:false,partial_confirmed:false,remainder_confirmed:false,provider_verified:false,webhook_verified:false,journal_verified:false,callback_verified:false,listener_closed:false,runtime_closed:false,provenance:'CONTROLLER_REPORTED'});
const rejected=()=>Object.freeze({report:EMPTY,exitCode:1});
function data(value,key){const d=Object.getOwnPropertyDescriptor(value,key);if(d&&(!d.enumerable||!Object.hasOwn(d,'value'))||!d&&key in value)throw null;return d?.value;}
function exact(value,keys){
  if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw null;
  const d=Object.getOwnPropertyDescriptors(value);if(Reflect.ownKeys(d).length!==keys.length)throw null;
  return Object.fromEntries(keys.map(k=>{if(!d[k]?.enumerable||!Object.hasOwn(d[k],'value'))throw null;return [k,d[k].value];}));
}
function writer(stream){return text=>new Promise((accept,reject)=>{
  let done=false;
  const finish=error=>{if(done)return;done=true;stream.removeListener('error',finish);error?reject():accept();};
  stream.once('error',finish);
  // Writable may invoke its failed callback before emitting 'error'. Keep the
  // error owner through that emission; never let raw stream errors escape.
  try{stream.write(text,error=>{if(error)setImmediate(()=>finish(true));else finish(false);});}catch{finish(true);}
});}

/** One active authority; canceled waits never abandon acquired capabilities. */
export async function runManualRefund(options,originalLimits,deps={}){
  const selected=validateManualRefundOptions(options);let input;
  try{const keys=Object.hasOwn(originalLimits??{},'signal')?['deadline','signal']:['deadline'];input=exact(originalLimits,keys);
    if(!selected||!Number.isSafeInteger(input.deadline)||input.signal!==undefined&&!(input.signal instanceof AbortSignal)||input.signal?.aborted)return rejected();
  }catch{return rejected();}
  let now,setTimer,clearTimer;
  try{now=deps.now??Date.now;const start=now();if(!Number.isSafeInteger(start)||input.deadline<=start||input.deadline>start+1800000)return rejected();setTimer=deps.setTimeout??setTimeout;clearTimer=deps.clearTimeout??clearTimeout;}catch{return rejected();}
  const controller=new AbortController(),abort=()=>controller.abort();
  input.signal?.addEventListener('abort',abort,{once:true});
  const timer=setTimer(abort,Math.max(1,input.deadline-now()));
  const limits=Object.freeze({deadline:input.deadline,signal:controller.signal}),cleanup=Object.freeze({deadline:input.deadline+120000});
  const httpSettlementCutoff=cleanup.deadline-60000;
  const proof={...EMPTY},work=new Set(),bodies=new Set(),runtimeWork=new Set(),sessionWork=new Set(),closures=new Map(),cleanupAttempts=new Set();
  let phase='RESTORE',pending=false,closing=false,runtimeReleased=false,state,runtime,cli,session;
  let stateAttempted=false,runtimeAttempted=false,cliAttempted=false;
  function check(){if(closing||pending||controller.signal.aborted||now()>=limits.deadline)throw null;}
  function track(body,kind){
    const p=Promise.resolve().then(body);
    work.add(p);
    if(kind==='runtime')runtimeWork.add(p);
    if(kind==='session')sessionWork.add(p);
    const settled=()=>{
      work.delete(p);
      runtimeWork.delete(p);
      sessionWork.delete(p);
      if(closing&&runtimeReleased&&runtimeWork.size===0&&runtime)close('runtime',runtime,'stop');
    };
    p.then(settled,settled);
    p.catch(()=>{});
    return p;
  }
  function wait(promise,deadline,signal){return new Promise((accept,reject)=>{
    let done=false,t;
    const finish=(ok,value)=>{if(done)return;done=true;clearTimer(t);signal?.removeEventListener('abort',cancel);ok?accept(value):reject();};
    const cancel=()=>finish(false);
    Promise.resolve(promise).then(v=>finish(now()<deadline&&!signal?.aborted,v),cancel);
    if(now()>=deadline||signal?.aborted){cancel();return;}
    signal?.addEventListener('abort',cancel,{once:true});t=setTimer(cancel,Math.max(1,deadline-now()));
  });}
  function operation(body,onAcquire,kind){
    check();
    const p=track(async()=>{
      check();
      const value=await body();
      onAcquire?.(value);
      return value;
    },kind);
    if(kind!=='session'&&kind!=='runtime')bodies.add(p);
    p.then(()=>bodies.delete(p),()=>bodies.delete(p));
    return wait(p,limits.deadline,limits.signal);
  }
  async function requireTrue(body,kind){if(await operation(body,undefined,kind)!==true)throw null;}
  function close(kind,value,method){
    if(closures.has(kind))return closures.get(kind);
    const p=track(()=>{
      if(now()>=cleanup.deadline)throw null;
      return kind==='state'
        ?value.close(Object.freeze({retain:pending||work.size>1}),cleanup)
        :value[method](cleanup);
    },kind==='session'?'session':undefined);
    closures.set(kind,p);
    p.catch(()=>{pending=true;});
    return p;
  }
  function acquired(kind,method,set){return value=>{
    set(value);
    if(closing&&(kind!=='runtime'||runtimeReleased&&runtimeWork.size===0))close(kind,value,method);
  };}
  async function cleanupOne(kind,value,method,field){
    if(!value||cleanupAttempts.has(kind))return;
    cleanupAttempts.add(kind);
    try{
      const result=await wait(close(kind,value,method),Math.min(cleanup.deadline,now()+10000));
      if(result!==true)throw null;
      if(field)proof[field]=true;
    }catch{pending=true;}
  }
  async function drain(set,deadline){try{while(set.size)await wait(Promise.allSettled([...set]),deadline);}catch{pending=true;}}
  function acceptProof(value,amount,total){
    const p=exact(value,['refundId','providerReference','providerEventId','amountMinor','totalMinor','providerVerified','webhookVerified','journalVerified','callbackVerified']);
    if(typeof p.refundId!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(p.refundId)||!/^re_[A-Za-z0-9_]{1,200}$/.test(p.providerReference)||!/^evt_[A-Za-z0-9_]{1,200}$/.test(p.providerEventId)||p.amountMinor!==amount||p.totalMinor!==total||['providerVerified','webhookVerified','journalVerified','callbackVerified'].some(k=>p[k]!==true))throw null;
    proof.provider_verified=true;proof.webhook_verified=true;proof.journal_verified=true;proof.callback_verified=true;
  }
  try{
    stateAttempted=true;
    await operation(async()=>{
      const open=deps.openState??(await import('./stripe-manual-refund-state.mjs')).openManualRefundState;
      return open(selected,limits);
    },acquired('state','close',v=>{state=v;}));
    runtimeAttempted=true;
    await operation(async()=>{
      const create=deps.createRuntime??(await import('./stripe-manual-refund-runtime.mjs')).createOwnedManualRefundRuntime;
      return create(Object.freeze({options:selected,state}));
    },acquired('runtime','stop',v=>{runtime=v;}),'runtime');
    await requireTrue(()=>runtime.restore(limits),'runtime');

    phase='BINDING';
    cliAttempted=true;
    await operation(async()=>{
      const create=deps.createCli??(await import('./stripe-sandbox-cli.mjs')).createManualRefundCliAdapter;
      return create(Object.freeze({
        env:selected.env,originalRunId:ORIGINAL,
        cliDirectory:ROOT+'/test-results/stripe-sandbox/'+ORIGINAL+'/cli',
        backendPort:state.target.ports.backend,
        verifyForwardTarget:runtime.verifyForwardTarget,
        persistWebhookSecret:runtime.verifyWebhookSecret,
        approvedBinding:Object.freeze({externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'})
      }));
    },acquired('cli','stopListener',v=>{cli=v;}));
    await requireTrue(()=>cli.validateBinding(limits));

    phase='LISTENER';
    await requireTrue(()=>cli.startListener(limits));
    phase='BASELINE';
    await requireTrue(()=>cli.bindCapturedPayment(state.target,limits));
    await operation(async()=>{
      const create=deps.createSession??(await import('./stripe-manual-refund-session.mjs')).createManualRefundSession;
      return create(Object.freeze({runtime,cli,state}));
    },acquired('session','close',v=>{session=v;}),'session');
    await requireTrue(()=>session.verifyBaseline(limits),'session');
    proof.target_verified=true;

    phase='ROTATION';
    await requireTrue(()=>session.rotateCallback(limits),'session');
    proof.callback_rotated=true;
    phase='PARTIAL';
    acceptProof(await operation(()=>session.refundPartial(limits),undefined,'session'),700,700);
    proof.partial_confirmed=true;
    phase='REMAINDER';
    acceptProof(await operation(()=>session.refundRemainder(limits),undefined,'session'),1300,2000);
    proof.remainder_confirmed=true;
    phase='VERIFY';
    const final=exact(await operation(()=>session.verifyFinal(limits),undefined,'session'),[
      'partialConfirmed','remainderConfirmed','providerVerified','webhookVerified','journalVerified','callbackVerified'
    ]);
    if(Object.values(final).some(v=>v!==true))throw null;
    check();
  }catch{pending=true;}
  finally{
    if(!pending)phase='CLEANUP';
    closing=true;
    controller.abort();
    // Start HTTP cancellation immediately. Its actual close/body/factory
    // settlement is tracked separately from listener failure or timeout.
    if(session)close('session',session,'close');
    await cleanupOne('cli',cli,'stopListener','listener_closed');
    if(!cliAttempted)proof.listener_closed=true;
    // CLI bodies and late non-session factories retain their own bounded
    // cleanup opportunity; they cannot shorten the HTTP settlement window.
    await drain(bodies,Math.min(cleanup.deadline,now()+10000));
    await cleanupOne('cli',cli,'stopListener','listener_closed');
    await drain(sessionWork,httpSettlementCutoff);
    const httpDrainUnverified=sessionWork.size>0;
    if(httpDrainUnverified)pending=true;
    else await cleanupOne('session',session,'close');
    // A failed listener close cannot suppress the separate exact-runtime stop.
    // Only the fixed original cutoff permits an unresponsive HTTP fallback.
    // Even then, restore/start work must settle before stop can be released.
    await drain(runtimeWork,Math.min(cleanup.deadline,now()+10000));
    runtimeReleased=true;
    if(runtimeWork.size===0)await cleanupOne('runtime',runtime,'stop',httpDrainUnverified?undefined:'runtime_closed');
    if(!runtimeAttempted)proof.runtime_closed=true;
    await drain(work,Math.min(cleanup.deadline,now()+10000));
    await cleanupOne('state',state,'close');
    await drain(work,cleanup.deadline);
    if(stateAttempted&&!state||runtimeAttempted&&!runtime||cliAttempted&&!cli)pending=true;
    clearTimer(timer);
    input.signal?.removeEventListener('abort',abort);
  }
  if(input.signal?.aborted||now()>=cleanup.deadline)pending=true;
  // The session retains its first failure across close. Ignore untrusted or
  // absent diagnostic capabilities without changing the financial outcome.
  if(pending&&session)try{
    const inspect=data(session,'diagnostic');
    if(typeof inspect==='function'){
      const diagnostic=validateManualRefundDiagnostic(inspect());
      if(diagnostic)proof.diagnostic=diagnostic;
    }
  }catch{}
  const report=validateManualRefundReport({...proof,result:pending?'PENDING':'SUCCEEDED',phase:pending?phase:'CLEANUP'})??validateManualRefundReport({...proof,result:'PENDING',phase:'CLEANUP'});
  return Object.freeze({report,exitCode:report?.result==='SUCCEEDED'?0:1});
}

/** Zero arguments, flags and new authority only. No private environment snapshot. */
export async function runManualRefundCli(input,deps={}){
  let selected,started;
  try{const env=Object.fromEntries(FLAGS.map(k=>[k,data(input.env,k)]));
    if(!manualRefundOptIn(env)||!Array.isArray(input.args)||input.args.length)throw null;
    selected=validateManualRefundOptions({env,runId:data(input.env,'PAYMENT_STRIPE_SANDBOX_RUN_ID'),expectedSource:data(input.env,'PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE'),daemon:data(input.env,'PAYMENT_STRIPE_SANDBOX_DAEMON')});
    if(selected)started=(deps.now??Date.now)();
  }catch{selected=null;}
  const controller=new AbortController();let unsubscribe,value=rejected();
  try{
    if(selected){const subscribe=deps.subscribe??(stop=>{process.once('SIGINT',stop);process.once('SIGTERM',stop);return ()=>{process.removeListener('SIGINT',stop);process.removeListener('SIGTERM',stop);};});unsubscribe=subscribe(()=>controller.abort());value=await runManualRefund(selected,{deadline:started+1800000,signal:controller.signal},deps);}
  }catch{value=rejected();}
  const now=deps.now??Date.now,setTimer=deps.setTimeout??setTimeout,clearTimer=deps.clearTimeout??clearTimeout;
  const deadline=selected?started+1920000:now()+1000;let wrote=false;
  try{wrote=await new Promise(accept=>{
    let done=false,t;const abort=()=>finish(false),finish=ok=>{if(done)return;done=true;clearTimer(t);controller.signal.removeEventListener('abort',abort);accept(ok);};
    controller.signal.addEventListener('abort',abort,{once:true});
    t=setTimer(()=>finish(false),Math.max(1,deadline-now()));
    try{Promise.resolve((deps.write??writer(process.stdout))(formatManualRefundReport(value.report))).then(()=>finish(now()<deadline&&!controller.signal.aborted),()=>finish(false));}catch{finish(false);}
    if(controller.signal.aborted||now()>=deadline)finish(false);
  });}catch{}
  finally{unsubscribe?.();}
  if(wrote)return value;
  return Object.freeze({report:validateManualRefundReport({...value.report,result:value.report.result==='FAILED'?'FAILED':'PENDING',phase:value.report.result==='FAILED'?'ADMISSION':'CLEANUP'}),exitCode:1});
}
export async function runManualRefundEntry(input,capabilities,deps={}){
  let code=1;
  try{code=(await runManualRefundCli(input,Object.create(deps,{write:{value:writer(capabilities.stdout)}}))).exitCode;}catch{}
  capabilities.exit(code);
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))void runManualRefundEntry({env:process.env,args:process.argv.slice(2)},{stdout:process.stdout,exit:code=>process.exit(code)});
