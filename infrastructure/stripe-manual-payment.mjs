import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {manualPaymentOptIn,validateManualPaymentOptions} from './stripe-manual-payment-options.mjs';
import {validateManualPaymentReport,formatManualPaymentReport} from './stripe-manual-payment-schema.mjs';
import {formatManualPaymentAuthDiagnostic} from './stripe-manual-payment-auth.mjs';
const ROOT=fileURLToPath(new URL('..',import.meta.url)).replace(/\/$/,'');
const EMPTY=Object.freeze({version:1,mode:'MANUAL_PAYMENT',result:'FAILED',phase:'ADMISSION',confirmation_granted:false,payment_confirmed:false,provider_confirmed:false,webhook_verified:false,journal_verified:false,callback_verified:false,gateway_closed:false,listener_closed:false,runtime_closed:false,browser_closure:'NOT_OWNED',provenance:'CONTROLLER_REPORTED'});
const rejected=()=>Object.freeze({report:EMPTY,exitCode:1});
const FLAGS=['PAYMENT_STRIPE_SANDBOX_ENABLED','PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS','PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT','PAYMENT_STRIPE_SANDBOX_FULL_RUN','PAYMENT_STRIPE_SANDBOX_OWNED_RUN','PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY','DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'];
const PORTS=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
function exact(value,keys){
  try{if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;
    const d=Object.getOwnPropertyDescriptors(value);if(Reflect.ownKeys(d).length!==keys.length)return null;
    const copy={};for(const key of keys){if(!d[key]?.enumerable||!Object.hasOwn(d[key],'value'))return null;copy[key]=d[key].value;}return copy;
  }catch{return null;}
}
function stdoutWriter(stream){return text=>new Promise((accept,reject)=>{const error=()=>reject();stream.once('error',error);try{stream.write(text,failed=>{if(failed){reject();return;}stream.removeListener('error',error);accept();});}catch{stream.removeListener('error',error);reject();}});}
const stdout=text=>stdoutWriter(process.stdout)(text);

/** One original authority owns one fresh fixture, listener, grant and final proof. */
export async function runManualPayment(options,originalLimits,deps={}){
  const selected=validateManualPaymentOptions(options),input=exact(originalLimits,['deadline','signal'])??exact(originalLimits,['deadline']);
  if(!selected||!input||!Number.isSafeInteger(input.deadline)||(input.signal!==undefined&&!(input.signal instanceof AbortSignal)))return rejected();
  let now,setTimer,clearTimer;
  try{now=deps.now??Date.now;const t=now();if(!Number.isSafeInteger(t)||input.deadline>t+2700000||input.deadline<=t+390000||input.signal?.aborted)return rejected();setTimer=deps.setTimeout??setTimeout;clearTimer=deps.clearTimeout??clearTimeout;}catch{return rejected();}
  const lifetime=new AbortController(),abort=()=>lifetime.abort();input.signal?.addEventListener('abort',abort,{once:true});
  const timer=setTimer(abort,Math.max(1,input.deadline-now())),limits=Object.freeze({deadline:input.deadline,signal:lifetime.signal}),cleanup=Object.freeze({deadline:input.deadline+120000});
  const origin='http://127.0.0.1:'+selected.manualPort,canonical=origin+'/manual-payment.html';
  let phase='PREPARE',failed=false,closing=false,grantUsed=false,granted=false,unused=false,authStarted=false,authCompleted=false,bootstrap,cli,provisioner,gateway;
  let gatewayAttempted=false,cliAttempted=false,bootstrapAttempted=false;
  let authDiagnostic,authDiagnosticFinalized=false;
  const proof={...EMPTY},work=new Set(),runtimeWork=new Set(),childWork=new Set(),closeTasks=new Map();let runtimeStopReleased=false;
  function active(){return !lifetime.signal.aborted&&!input.signal?.aborted&&now()<limits.deadline;}
  function check(){if(failed||closing||!active())throw null;}
  function bounded(promise,deadline,signal){return new Promise((accept,reject)=>{let done=false,timer;const finish=(ok,value)=>{if(done)return;done=true;clearTimer(timer);signal?.removeEventListener('abort',abort);ok?accept(value):reject();};const abort=()=>finish(false);
    Promise.resolve(promise).then(value=>finish(now()<deadline&&!signal?.aborted,value),()=>finish(false));if(now()>=deadline||signal?.aborted){finish(false);return;}signal?.addEventListener('abort',abort,{once:true});timer=setTimer(abort,Math.max(1,deadline-now()));});}
  function start(body,onValue,kind){const p=Promise.resolve().then(body).then(value=>{onValue?.(value);return value;});work.add(p);if(kind==='runtime')runtimeWork.add(p);if(kind==='children')childWork.add(p);
    const settled=()=>{work.delete(p);runtimeWork.delete(p);childWork.delete(p);if(runtimeStopReleased&&runtimeWork.size===0&&bootstrap)close('bootstrap',bootstrap,'stop')?.catch(()=>{});};p.then(settled,settled);return p;}
  function operation(body,onValue,kind){check();return bounded(start(()=>{check();return body();},onValue,kind),limits.deadline,limits.signal);}
  async function requireTrue(body,kind){if(await operation(body,undefined,kind)!==true)throw null;}
  function close(kind,value,method){
    if(!value)return null;if(closeTasks.has(kind))return closeTasks.get(kind);
    // Late acquisitions remain owned under the original cleanup ceiling. They
    // cannot improve the already frozen report or renew financial authority.
    const p=start(()=>value[method](cleanup));closeTasks.set(kind,p);p.catch(()=>{failed=true;});return p;
  }
  function acquired(kind,method,set){return value=>{set(value);if(closing&&(kind!=='bootstrap'||runtimeStopReleased&&runtimeWork.size===0))close(kind,value,method)?.catch(()=>{});};}
  async function cleanupOne(kind,value,method,field){
    if(!value)return;
    try{const ok=await bounded(close(kind,value,method),Math.min(cleanup.deadline,now()+30000));if(field)proof[field]=ok===true&&now()<cleanup.deadline;if(ok!==true)failed=true;}catch{failed=true;}
  }
  try{
    const preflight=deps.preflight??(await import('./stripe-sandbox-owned.mjs')).preflightOwnedManualPayment;
    if(exact(await operation(()=>preflight(selected)),['status'])?.status!=='READY')throw null;
    bootstrapAttempted=true;
    const createBootstrap=deps.createBootstrap??(await import('./stripe-sandbox-bootstrap.mjs')).createOwnedManualPaymentBootstrap;
    await operation(()=>createBootstrap(selected),acquired('bootstrap','stop',value=>{bootstrap=value;}),'runtime');
    await requireTrue(()=>bootstrap.prepare(limits),'runtime');
    const safe=bootstrap.resources();
    if(safe.runId!==selected.runId||safe.status!=='PREPARED'||safe.cliDirectory!==ROOT+'/test-results/stripe-sandbox/'+selected.runId+'/cli'||safe.ports?.backend!==selected.ports.backend)throw null;
    cliAttempted=true;
    const createCli=deps.createCli??(await import('./stripe-sandbox-cli.mjs')).createSandboxCliAdapter;
    await operation(()=>createCli(Object.freeze({env:selected.env,runId:selected.runId,cliDirectory:safe.cliDirectory,backendPort:selected.ports.backend,accountKey:'stripe-sandbox-default',approvedBinding:Object.freeze({externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}),persistWebhookSecret:bootstrap.persistWebhookSecret,verifyForwardTarget:bootstrap.verifyForwardTarget})),acquired('cli','stopListener',value=>{cli=value;}));
    phase='AUTHORIZATION';authStarted=true;
    const authorize=deps.authorizeCli??(await import('./stripe-manual-payment-auth.mjs')).authorizeManualPaymentCli;
    await requireTrue(async()=>{try{return await authorize({cli,runId:selected.runId},limits,line=>operation(()=>(deps.write??stdout)(line)));}catch(error){if(!authDiagnosticFinalized)authDiagnostic??=formatManualPaymentAuthDiagnostic(error);throw null;}},'children');
    authCompleted=true;
    phase='BINDING';await requireTrue(()=>cli.validateBinding(limits),'children');
    phase='LISTENER';await requireTrue(()=>cli.startListener(limits),'children');
    phase='START';await requireTrue(()=>bootstrap.start(limits),'runtime');await requireTrue(()=>bootstrap.verify(limits));
    phase='PROVISION';
    const createProvisioner=deps.createProvisioner??(await import('./stripe-form-diagnostic-provision.mjs')).createManualPaymentProvisioner;
    await operation(()=>createProvisioner(Object.freeze({env:selected.env,bootstrap,browserOrigin:origin,verifyProviderBinding:cli.validateBinding})),acquired('provisioner','close',value=>{provisioner=value;}));
    const prepared=exact(await operation(()=>provisioner.prepare(limits)),['session','binding']);if(!prepared)throw null;
    await requireTrue(()=>cli.bindManualPayment(prepared.binding,limits),'children');
    phase='HANDOFF';gatewayAttempted=true;
    const createGateway=deps.createGateway??(await import('./stripe-manual-payment-gateway.mjs')).createManualPaymentGateway;
    await operation(()=>createGateway(Object.freeze({origin,assetEndpoint:'http://127.0.0.1:'+selected.ports.demo,session:prepared.session,authorizeConfirmation:async()=>{
      try{
        check();if(grantUsed)throw null;grantUsed=true;
        await requireTrue(()=>cli.verifyManualPaymentBaseline(limits),'children');check();
        await requireTrue(()=>{if(cli.assertManualPaymentListener(limits)!==true)throw null;return provisioner.markConfirmation(limits);});
        // Durable consumption is evidence even when listener loss denies reply.
        // The final synchronous assertion has no await before literal true.
        proof.confirmation_granted=true;check();if(cli.assertManualPaymentListener(limits)!==true)throw null;
        granted=true;return true;
      }catch{failed=true;throw Error('stripe_manual_payment_PERMISSION_PENDING');}
    }}),limits),acquired('gateway','close',value=>{gateway=value;}));
    if(exact(await operation(()=>gateway.open()),['url'])?.url!==canonical)throw null;
    await operation(()=>(deps.write??stdout)(canonical+'\n'));
    phase='AWAITING_PAY';const outcome=exact(await operation(()=>gateway.wait()),['outcome']);
    if(!outcome||!['CONFIRMATION_GRANTED','NOT_STARTED','FAILED'].includes(outcome.outcome)||outcome.outcome==='FAILED'||(outcome.outcome==='CONFIRMATION_GRANTED')!==granted)throw null;
    phase='VERIFY';
    if(granted){
      const server=exact(await operation(()=>provisioner.verifyPayment(limits)),['paymentConfirmed','webhookVerified','journalVerified','callbackVerified','providerEventId']);
      if(!server||['paymentConfirmed','webhookVerified','journalVerified','callbackVerified'].some(key=>server[key]!==true)||typeof server.providerEventId!=='string'||!/^evt_[A-Za-z0-9_]{1,200}$/.test(server.providerEventId))throw null;
      proof.payment_confirmed=true;proof.webhook_verified=true;proof.journal_verified=true;proof.callback_verified=true;
      const provider=exact(await operation(()=>cli.inspectManualPayment(server.providerEventId,limits),undefined,'children'),['captured','eventVerified']);
      if(provider?.captured!==true||provider.eventVerified!==true)throw null;proof.provider_confirmed=true;
    }else{
      if(grantUsed)throw null;await requireTrue(()=>provisioner.verifyUnused(limits));await requireTrue(()=>cli.inspectUnusedManualPayment(limits),'children');unused=true;
    }
  }catch{failed=true;}
  finally{
    closing=true;
    await cleanupOne('gateway',gateway,'close','gateway_closed');if(!gatewayAttempted)proof.gateway_closed=true;
    await cleanupOne('provisioner',provisioner,'close');
    await cleanupOne('cli',cli,'stopListener','listener_closed');if(!cliAttempted)proof.listener_closed=true;
    if(authStarted&&!authCompleted)proof.listener_closed=false;
    // The existing child owners get their close grace before runtime stop.
    // An unclosed child remains uncertain after that finite attempt; it does
    // not prevent the independent owned runtime stop required by R11.
    const childDrainDeadline=Math.min(cleanup.deadline,now()+10000);
    try{while(childWork.size)await bounded(Promise.allSettled([...childWork]),childDrainDeadline);}catch{failed=true;proof.listener_closed=false;}
    // Manual-only R11: an unsuccessful completed listener-stop attempt still
    // permits independent owned runtime stop, with honest PENDING evidence.
    // In-flight creation/start is different: it must settle before any stop.
    try{while(runtimeWork.size)await bounded(Promise.allSettled([...runtimeWork]),cleanup.deadline);}catch{failed=true;}
    runtimeStopReleased=true;
    if(runtimeWork.size===0)await cleanupOne('bootstrap',bootstrap,'stop','runtime_closed');
    if(!bootstrapAttempted)proof.runtime_closed=true;
    // All late work is observed. Positive closure requires it to have settled;
    // timeout still leaves each acquired capability's one close attempt owned.
    try{while(work.size)await bounded(Promise.allSettled([...work]),cleanup.deadline);}catch{failed=true;}
    // Keep timeout fallback provisional until the existing child/work drain can
    // deliver its first classified failure; later settlement cannot change it.
    if(authStarted&&!authCompleted)authDiagnostic??=formatManualPaymentAuthDiagnostic(undefined);
    authDiagnosticFinalized=true;
    if(authDiagnostic&&deps.writeAuthDiagnostic){
      const diagnosticDeadline=Math.min(cleanup.deadline,now()+1000);
      try{if(now()>=diagnosticDeadline)throw null;await bounded(Promise.resolve().then(()=>{if(now()>=diagnosticDeadline)throw null;return deps.writeAuthDiagnostic(authDiagnostic);}),diagnosticDeadline);}catch{failed=true;}
    }
    clearTimer(timer);input.signal?.removeEventListener('abort',abort);
  }
  if(input.signal?.aborted)failed=true;
  const closed=proof.gateway_closed&&proof.listener_closed&&proof.runtime_closed;
  const result=!failed&&closed?(granted?'SUCCEEDED':unused?'NOT_STARTED':'PENDING'):'PENDING';
  const report=validateManualPaymentReport({...proof,result,phase:result==='SUCCEEDED'?'SUCCEEDED':failed?phase:'VERIFY'})??EMPTY;
  return Object.freeze({report,exitCode:report.result==='SUCCEEDED'?0:1});
}
function rawData(env,key){const d=Object.getOwnPropertyDescriptor(env,key);if(d&&(!d.enumerable||!Object.hasOwn(d,'value')))throw null;return d?.value;}
/** Explicit entry snapshots only allowlisted environment data; imports are inert. */
export async function runManualPaymentCli(input,deps={}){
  let selected,started;
  try{
    const env=Object.fromEntries(FLAGS.map(key=>[key,rawData(input.env,key)]));
    if(!manualPaymentOptIn(env)||!Array.isArray(input.args)||input.args.length)throw null;
    started=(deps.now??Date.now)();
    const number=key=>{const raw=rawData(input.env,key);if(typeof raw!=='string'||!/^[1-9][0-9]{3,4}$/.test(raw))throw null;return Number(raw);};
    selected=validateManualPaymentOptions({env,runId:rawData(input.env,'PAYMENT_STRIPE_SANDBOX_RUN_ID'),expectedSource:rawData(input.env,'PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE'),daemon:rawData(input.env,'PAYMENT_STRIPE_SANDBOX_DAEMON'),ports:Object.fromEntries(PORTS.map(key=>[key,number('PAYMENT_STRIPE_SANDBOX_PORT_'+key.toUpperCase())])),manualPort:number('PAYMENT_STRIPE_SANDBOX_PORT_MANUAL')});
  }catch{selected=null;}
  const controller=new AbortController(),stop=()=>controller.abort();
  const subscribe=deps.subscribe??(callback=>{process.once('SIGINT',callback);process.once('SIGTERM',callback);return ()=>{process.removeListener('SIGINT',callback);process.removeListener('SIGTERM',callback);};});
  let unsubscribe,value=rejected();
  try{
    try{unsubscribe=subscribe(stop);if(selected)value=await runManualPayment(selected,{deadline:started+2700000,signal:controller.signal},deps);}catch{}
    let wrote=false,timer;const now=deps.now??Date.now,deadline=selected?started+2820000:now()+1000;
    try{wrote=await new Promise(accept=>{let done=false;const abort=()=>finish(false),finish=ok=>{if(done)return;done=true;(deps.clearTimeout??clearTimeout)(timer);controller.signal.removeEventListener('abort',abort);accept(ok);};
      controller.signal.addEventListener('abort',abort,{once:true});timer=(deps.setTimeout??setTimeout)(abort,Math.max(1,deadline-now()));
      // An already aborted invocation still owns its one bounded report attempt.
      try{Promise.resolve((deps.write??stdout)(formatManualPaymentReport(value.report))).then(()=>finish(now()<deadline&&!controller.signal.aborted),abort);}catch{abort();}
      if(controller.signal.aborted||now()>=deadline)abort();});}catch{}
    if(wrote)return value;
    const report=validateManualPaymentReport({...value.report,result:value.report.result==='FAILED'?'FAILED':'PENDING',phase:value.report.result==='FAILED'?'ADMISSION':'CLEANUP'});
    return Object.freeze({report:report??EMPTY,exitCode:1});
  }finally{unsubscribe?.();}
}
export async function runManualPaymentEntry(input,capabilities,deps={}){
  let code=1;try{const value=await runManualPaymentCli(input,Object.create(deps,{write:{value:stdoutWriter(capabilities.stdout)},...(capabilities.stderr?{writeAuthDiagnostic:{value:stdoutWriter(capabilities.stderr)}}:{})}));code=value.exitCode;}catch{}
  capabilities.exit(code);
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))void runManualPaymentEntry({env:process.env,args:process.argv.slice(2)},{stdout:process.stdout,stderr:process.stderr,exit:code=>process.exit(code)});
