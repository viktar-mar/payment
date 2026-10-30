import {billingFirstPaymentOptIn,validateBillingFirstPaymentOptions} from './stripe-billing-first-payment-options.mjs';
import {billingBounded} from './stripe-billing-limits.mjs';
import {manualRefundRemainderExcluded as remainderExcluded} from './stripe-manual-refund-remainder-options.mjs';
import {billingAcceptanceExcluded,billingAcceptanceOptIn,validateBillingAcceptanceOptions} from './stripe-billing-acceptance-options.mjs';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {lstat,realpath} from 'node:fs/promises';
import {createServer} from 'node:net';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {manualFormOptIn,validateManualFormOptions,validateFormDiagnosticTopology} from './stripe-manual-form-options.mjs';
import {manualPaymentOptIn,validateManualPaymentOptions} from './stripe-manual-payment-options.mjs';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const DOCKER='/Applications/Docker.app/Contents/Resources/bin/docker';
const CONFIG='/private/var/empty';
const PORTS=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
const executeFile=promisify(execFile);
const pending=(phase,code)=>Object.freeze({status:'PENDING',phase,code});
// Nothing is inherited. Git status cannot refresh the index or invoke a configured fsmonitor.
const CHILD_ENV=Object.freeze({PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',
  GIT_OPTIONAL_LOCKS:'0',GIT_CONFIG_GLOBAL:'/dev/null',GIT_CONFIG_SYSTEM:'/dev/null'});

/** Bound listen + verified close together. Expiry is cleanup-unconfirmed, never admission. */
function portAvailable(port,host,dependencies) {
  const tcpServer=dependencies.createServer??createServer,setTimer=dependencies.setTimeout??setTimeout,clearTimer=dependencies.clearTimeout??clearTimeout;
  return new Promise(resolveProbe=>{
    const server=tcpServer(socket=>socket.destroy()),controller=new AbortController();
    let closing=false,settled=false,canReady=false,expired=false;
    const settle=available=>{
      if(settled)return;
      settled=true;clearTimer(timer);resolveProbe(available);
    };
    const finish=available=>{
      if(settled)return;
      if(!available)canReady=false;
      if(closing)return;
      closing=true;canReady=available;
      if(!available)controller.abort();
      try {server.close(error=>settle(canReady&&!expired&&!error));}
      catch {controller.abort();settle(false);}
    };
    const timer=setTimer(()=>{
      expired=true;controller.abort();finish(false);settle(false);
    },3000);
    server.on('error',()=>finish(false));
    try {server.listen({port,host,exclusive:true,signal:controller.signal},()=>finish(true));}
    catch {finish(false);}
  });
}

/** Credential-free macOS/local-Docker admission only. READY is not a reservation or release.
 * No entrypoint, private reads, runtime creation, authentication, services or fixtures.
 * A later bootstrap must recheck ownership/availability immediately before its own actions.
 */
export async function preflightOwnedSandbox(options,dependencies={}) {
  return preflight(options,dependencies,false);
}
export async function preflightOwnedBillingFirstPayment(options,dependencies={},limits){return preflightOwnedBilling(options,dependencies,limits,true);}
export async function preflightOwnedBilling(options,dependencies={},limits,firstPayment=false) {
  const selected=(firstPayment?validateBillingFirstPaymentOptions:validateBillingAcceptanceOptions)(options);
  if(!selected)return pending('OPT_IN','OPT_IN_REQUIRED');
  if(limits!==undefined){
    const raw=dependencies;
    // Runtime-only authority is explicit; each boundary rechecks it before dispatch and after completion.
    dependencies={
      execute:(file,args,settings)=>billingBounded(limits,l=>(raw.execute??executeFile)(file,args,{...settings,signal:l.signal,timeout:Math.min(settings.timeout,l.deadline-Date.now())}),10000),
      lstat:path=>billingBounded(limits,()=>(raw.lstat??lstat)(path)),
      realpath:path=>billingBounded(limits,()=>(raw.realpath??realpath)(path)),
      portAvailable:(port,host)=>billingBounded(limits,()=>raw.portAvailable?raw.portAvailable(port,host):portAvailable(port,host,raw),4000),
    };
  }
  return preflight(selected,dependencies,firstPayment?'first-payment':'billing');
}

// This gate runs before consulting dependencies or any private configuration.
export function formDiagnosticOptIn(env) {
  try {return remainderExcluded(env)&&env?.PAYMENT_STRIPE_SANDBOX_ENABLED==='true'&&env.PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC==='true'
    &&env.PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY==='true'
    &&[undefined,'','false'].includes(env.PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT)
    &&[undefined,'','false'].includes(env.PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND)
    &&[undefined,'','false'].includes(env.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS)
    &&[undefined,'','false'].includes(env.PAYMENT_STRIPE_SANDBOX_FULL_RUN)
    &&[undefined,'','false'].includes(env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN)
    &&[undefined,'','false'].includes(env.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC)
    &&[undefined,''].includes(env.DEBUG)&&[undefined,''].includes(env.NODE_DEBUG)
    &&[undefined,'','0'].includes(env.PWDEBUG)&&[undefined,'','off'].includes(env.PW_TRACE);}
  catch {return false;}
}

export async function preflightOwnedFormDiagnostic(options,dependencies={}) {
  return preflight(options,dependencies,true);
}
export async function preflightOwnedManualFormDiagnostic(options,dependencies={}) {
  // Inspect the env data descriptor without invoking a hostile option accessor.
  let env;
  try {env=Object.getOwnPropertyDescriptor(options,'env')?.value;}catch{}
  if(!manualFormOptIn(env))return pending('OPT_IN','OPT_IN_REQUIRED');
  const selected=validateManualFormOptions(options);
  if(!selected)return pending('INPUTS','CONFIGURATION');
  return preflight(selected,dependencies,'manual');
}

export async function preflightOwnedManualPayment(options,dependencies={}) {
  let env;try{env=Object.getOwnPropertyDescriptor(options,'env')?.value;}catch{}
  if(!manualPaymentOptIn(env))return pending('OPT_IN','OPT_IN_REQUIRED');
  const selected=validateManualPaymentOptions(options);if(!selected)return pending('INPUTS','CONFIGURATION');
  return preflight(selected,dependencies,'payment');
}
async function preflight(options,dependencies,diagnostic) {
  let phase='OPT_IN',code='OPT_IN_REQUIRED';
  try {
    const env=Object.getOwnPropertyDescriptor(options??{},'env')?.value;
    if(diagnostic==='first-payment'?!billingFirstPaymentOptIn(env):diagnostic==='billing'?!billingAcceptanceOptIn(env):!billingAcceptanceExcluded(env))return pending(phase,code);
    if(!['billing','first-payment'].includes(diagnostic)&&!remainderExcluded(env))return pending(phase,code);
    if(env?.PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND==='true'||(['billing','first-payment'].includes(diagnostic)?false:diagnostic==='payment'?!manualPaymentOptIn(env):diagnostic==='manual'?!manualFormOptIn(env):diagnostic?!formDiagnosticOptIn(env):(env?.PAYMENT_STRIPE_SANDBOX_ENABLED!=='true'||env?.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS!=='true'||env.PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT==='true'
      ||env.PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC==='true'||env.PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY==='true'||env.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC==='true')))return pending(phase,code);
    phase='INPUTS';code='CONFIGURATION';
    if(![undefined,''].includes(env.DEBUG)||![undefined,''].includes(env.NODE_DEBUG)||![undefined,'','0'].includes(env.PWDEBUG))return pending(phase,code);
    const topology=validateFormDiagnosticTopology(options);if(!topology)return pending(phase,code);
    const {expectedSource,daemon,ports}=topology,url=new URL(daemon);
    const selectedPorts=PORTS.map(name=>ports[name]);if(diagnostic==='first-payment')selectedPorts.push(options.controlPort);if(diagnostic==='manual'||diagnostic==='payment')selectedPorts.push(options.manualPort);

    phase='SOURCE';code='SOURCE_MISMATCH';
    const execute=dependencies.execute??executeFile;
    const processOptions={cwd:ROOT,env:CHILD_ENV,shell:false,timeout:10000,maxBuffer:16384};
    const head=await execute('/usr/bin/git',['rev-parse','HEAD'],processOptions);
    if(head.stdout!==expectedSource+'\n'&&head.stdout!==expectedSource)return pending(phase,code);
    const status=await execute('/usr/bin/git',['-c','core.fsmonitor=false','status','--porcelain=v1','--untracked-files=no'],processOptions);
    if(status.stdout!=='')return pending(phase,code);

    phase='DAEMON';code='DAEMON_UNCONFIRMED';
    const inspect=dependencies.lstat??lstat,canonical=dependencies.realpath??realpath;
    const socket=await inspect(url.pathname);
    if(!socket.isSocket()||socket.isSymbolicLink()||socket.uid!==process.getuid()||await canonical(url.pathname)!==url.pathname)return pending(phase,code);
    const config=await inspect(CONFIG);
    if(!config.isDirectory()||config.isSymbolicLink()||config.uid!==0||!Number.isInteger(config.mode)||(config.mode&0o022)!==0||await canonical(CONFIG)!==CONFIG)return pending(phase,code);
    try {await inspect(CONFIG+'/config.json');return pending(phase,code);}
    catch(error) {if(error?.code!=='ENOENT')return pending(phase,code);}
    const docker=await execute(DOCKER,['--config',CONFIG,'--host',daemon,'info','--format','{{json .OSType}}'],processOptions);
    if(docker.stdout!=='"linux"\n'&&docker.stdout!=='"linux"')return pending(phase,code);

    phase='PORTS';code='PORT_UNAVAILABLE';
    const available=dependencies.portAvailable??((port,host)=>portAvailable(port,host,dependencies));
    for(const port of selectedPorts)if(await available(port,'127.0.0.1')!==true)return pending(phase,code);
    return Object.freeze({status:'READY'});
  }catch {return pending(phase,code);}
}
