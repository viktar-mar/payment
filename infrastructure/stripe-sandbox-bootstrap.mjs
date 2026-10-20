import {proveOwnedContainer,proveOwnedPorts as portProof,CONTAINER_FORMAT,NETWORK_FORMAT,VOLUME_FORMAT} from './stripe-sandbox-ownership.mjs';
import {execFile} from 'node:child_process';
import {constants} from 'node:fs';
import {lstat,realpath,readFile,readdir,mkdir,open,rename,unlink} from 'node:fs/promises';
import {randomBytes,generateKeyPairSync} from 'node:crypto';
import {createServer} from 'node:net';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {preflightOwnedSandbox,preflightOwnedFormDiagnostic,preflightOwnedManualPayment,formDiagnosticOptIn} from './stripe-sandbox-owned.mjs';
import {manualPaymentOptIn,validateManualPaymentOptions} from './stripe-manual-payment-options.mjs';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const DOCKER='/Applications/Docker.app/Contents/Resources/bin/docker';
const USERFILE=ROOT+'/infrastructure/stripe-sandbox.env.local';
const SERVICES=['postgres','mailpit','backend','demo-site','callback-receiver'];
const PORT_NAMES=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
const STRIPE_NAMES=['PAYMENT_STRIPE_SANDBOX_SECRET_KEY','PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY','PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET'];
const EXHAUSTED=new Set(['2f3f49b1-df07-41b1-b978-bc884b390813','6f684b69-32e6-429f-a34c-e4f69ba2ce18']);
const CHILD_ENV=Object.freeze({PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C'});
const ERRORS=new Set(['OPT_IN_REQUIRED','INPUTS_CONFIGURATION','OPERATION_BUSY',
  'BUILD_PENDING','PREPARE_PENDING','FORWARD_PENDING','PERSIST_PENDING','START_PENDING','VERIFY_PENDING','ADMIN_PENDING','STOP_PENDING']);
const fixed=code=>new Error('stripe_sandbox_bootstrap_'+(ERRORS.has(code)?code:'INPUTS_CONFIGURATION'));
const fail=()=>{throw null;};
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const fullId=value=>typeof value==='string'&&/^[0-9a-f]{64}$/.test(value);
const imageId=value=>typeof value==='string'&&/^sha256:[0-9a-f]{64}$/.test(value);
function canonical(value) {
  if(Array.isArray(value))return value.map(canonical);
  if(object(value))return Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])]));
  return value;
}
const same=(a,b)=>JSON.stringify(canonical(a))===JSON.stringify(canonical(b));
// Only internally created failures carry diagnostic metadata. Never traverse a dependency rejection.
const failures=new WeakMap();
function diagnosticFailure(category,exitCode) {
  const error=Object.create(null);failures.set(error,{category,...(Number.isSafeInteger(exitCode)?{exitCode}:{})});return error;
}
function childFailure(error,signal) {
  try {
    if(signal.aborted)return diagnosticFailure('ABORT');
    if(error.code==='ERR_CHILD_PROCESS_STDIO_MAXBUFFER')return diagnosticFailure('OUTPUT_LIMIT');
    if(error.code==='ABORT_ERR')return diagnosticFailure('ABORT');
    if(Number.isSafeInteger(error.code))return diagnosticFailure('NONZERO_EXIT',error.code);
    if(error.killed===true&&error.signal==='SIGTERM')return diagnosticFailure('TIMEOUT');
    if(['ENOENT','EACCES','ENOEXEC','EAGAIN'].includes(error.code))return diagnosticFailure('SPAWN_FAILURE');
  } catch {}
  return diagnosticFailure('UNKNOWN');
}
function frozen(value) {
  if(value&&typeof value==='object') {for(const child of Object.values(value))frozen(child);Object.freeze(value);}
  return value;
}

// execFile's abort callback can precede close. Never dispatch the next command then.
function executeAwaited(execute,file,args,settings) {
  return new Promise((resolveExecution,rejectExecution)=>{
    let callbackDone=false,closed=false,failure=null,result,closeCode,closeSignal;
    const finish=()=>{if(callbackDone&&closed) {
      if(!failure&&(closeCode!==0||closeSignal))failure=diagnosticFailure(Number.isSafeInteger(closeCode)&&closeCode!==0?'NONZERO_EXIT':'UNKNOWN',closeCode);
      if(failure)rejectExecution(failure);else resolveExecution(result);
    }};
    try {
      const child=execute(file,args,settings,(error,stdout,stderr)=>{
        failure=error?childFailure(error,settings.signal):null;result={stdout,stderr};callbackDone=true;finish();
      });
      child.once('close',(code,signal)=>{closeCode=code;closeSignal=signal;closed=true;finish();});
    } catch(error) {rejectExecution(childFailure(error,settings.signal));}
  });
}

async function available(port,host,context,io) {
  return new Promise(resolveProbe=>{
    const server=io.createServer(socket=>socket.destroy());let settled=false,closing=false,allowed=false,expired=false;
    const settle=ok=>{if(settled)return;settled=true;io.clearTimeout(timer);context.signal.removeEventListener('abort',abort);resolveProbe(ok);};
    const finish=ok=>{
      if(settled)return;if(!ok)allowed=false;if(closing)return;closing=true;allowed=ok;
      try {server.close(error=>settle(allowed&&!expired&&!error&&!context.signal.aborted));} catch {settle(false);}
    };
    const abort=()=>{expired=true;finish(false);settle(false);};
    const timer=io.setTimeout(abort,Math.min(3000,Math.max(1,context.deadline-io.now())));
    context.signal.addEventListener('abort',abort,{once:true});server.on('error',abort);
    try {server.listen({port,host,exclusive:true,signal:context.signal},()=>finish(true));} catch {finish(false);}
    if(context.signal.aborted)abort();
  });
}

async function publicHealth(url,context) {
  const controller=new AbortController(),abort=()=>controller.abort();
  const timer=setTimeout(abort,Math.min(10000,Math.max(1,context.deadline-Date.now())));
  context.signal.addEventListener('abort',abort,{once:true});
  try {
    if(context.signal.aborted)fail();
    const response=await fetch(url,{signal:controller.signal,redirect:'error'});
    if(response.status!==200)fail();
    const reader=response.body?.getReader();let bytes=0;
    if(reader)try {for(;;) {const part=await reader.read();if(part.done)break;bytes+=part.value.byteLength;if(bytes>65536)fail();}}
    finally {await reader.cancel();reader.releaseLock();}
    if(controller.signal.aborted)fail();return true;
  } finally {clearTimeout(timer);context.signal.removeEventListener('abort',abort);}
}

/** Inert constructor. Root alone owns login, listener, provisioning and financial work. */
export async function createOwnedSandboxBootstrap(options,dependencies={}) {
  return createBootstrap(options,dependencies,false);
}

export async function createOwnedFormDiagnosticBootstrap(options,dependencies={}) {
  return createBootstrap(options,dependencies,true);
}

export async function createOwnedManualPaymentBootstrap(options,dependencies={}) {
  let env;try{env=Object.getOwnPropertyDescriptor(options,'env')?.value;}catch{}
  if(!manualPaymentOptIn(env))throw fixed('OPT_IN_REQUIRED');
  const selected=validateManualPaymentOptions(options);if(!selected)throw fixed('INPUTS_CONFIGURATION');
  return createBootstrap(selected,dependencies,false,true);
}
async function createBootstrap(options,dependencies,diagnostic,manualPayment=false) {
  let enabled=false;
  try {const env=options?.env;enabled=manualPayment?manualPaymentOptIn(env):diagnostic?formDiagnosticOptIn(env):env?.PAYMENT_STRIPE_SANDBOX_ENABLED==='true'&&env?.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS==='true'&&env.PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT!=='true'
    &&env.PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC!=='true'&&env.PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY!=='true'&&env.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC!=='true';} catch {}
  if(!enabled)throw fixed('OPT_IN_REQUIRED');
  let runId,expectedSource,daemon,ports,env,io;
  try {
    const flags=options.env;
    if(![undefined,''].includes(flags.DEBUG)||![undefined,''].includes(flags.NODE_DEBUG)||![undefined,'','0'].includes(flags.PWDEBUG))fail();
    runId=options.runId;expectedSource=options.expectedSource;daemon=options.daemon;
    if(typeof runId!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(runId)||EXHAUSTED.has(runId)
      ||typeof expectedSource!=='string'||!/^[0-9a-f]{40}$/.test(expectedSource))fail();
    if(!object(options.ports)||Object.keys(options.ports).sort().join(',')!==[...PORT_NAMES].sort().join(','))fail();
    ports=Object.freeze(Object.fromEntries(PORT_NAMES.map(name=>[name,options.ports[name]])));
    if(Object.values(ports).some(port=>!Number.isInteger(port)||port<1024||port>65535)||new Set(Object.values(ports)).size!==6)fail();
    if(typeof daemon!=='string'||/[\s\x00-\x1f\x7f%]/.test(daemon))fail();
    const url=new URL(daemon);
    if(url.protocol!=='unix:'||url.host||url.username||url.password||url.search||url.hash||url.pathname.length<2||url.href!==daemon||daemon!=='unix://'+url.pathname)fail();
    const binding=diagnostic||manualPayment?{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}:options.approvedBinding;
    if(!object(binding)||Object.keys(binding).sort().join(',')!=='apiVersion,externalAccountId'
      ||binding.externalAccountId!=='acct_1UPiMpELswAd8tpR'||binding.apiVersion!=='2026-09-30.endive')fail();
    env=Object.freeze(manualPayment?options.env:diagnostic?{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}:
      {PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',DEBUG:flags.DEBUG,NODE_DEBUG:flags.NODE_DEBUG,PWDEBUG:flags.PWDEBUG});
    const raw=dependencies.execFile??execFile;
    io=Object.freeze({execute:dependencies.execute??((...args)=>executeAwaited(raw,...args)),preflight:dependencies.preflight??(manualPayment?preflightOwnedManualPayment:diagnostic?preflightOwnedFormDiagnostic:preflightOwnedSandbox),
      lstat:dependencies.lstat??lstat,realpath:dependencies.realpath??realpath,readFile:dependencies.readFile??readFile,readdir:dependencies.readdir??readdir,
      mkdir:dependencies.mkdir??mkdir,open:dependencies.open??open,rename:dependencies.rename??rename,unlink:dependencies.unlink??unlink,
      randomBytes:dependencies.randomBytes??randomBytes,generateKeyPairSync:dependencies.generateKeyPairSync??generateKeyPairSync,
      now:dependencies.now??Date.now,setTimeout:dependencies.setTimeout??setTimeout,clearTimeout:dependencies.clearTimeout??clearTimeout,
      createServer:dependencies.createServer??createServer,
      portAvailable:dependencies.portAvailable??((port,host,context)=>available(port,host,context,io)),health:dependencies.health??publicHealth});
  } catch {throw fixed('INPUTS_CONFIGURATION');}

  const project='stripe-'+runId,siteKey='s5a-'+runId.replaceAll('-',''),directory=ROOT+'/test-results/stripe-sandbox/'+runId;
  const networkName=project+'-network',volumeNames={postgres:project+'-postgres',issuer:project+'-issuer'};
  const tags={backend:project+'-backend:latest','demo-site':project+'-demo-site:latest',postgres:'postgres:18-alpine',mailpit:'axllent/mailpit:v1.27','callback-receiver':'node:24-alpine'};
  const endpoints=frozen({backend:'http://127.0.0.1:'+ports.backend,demo:'http://127.0.0.1:'+ports.demo,receiver:'http://127.0.0.1:'+ports.receiver,
    postgres:'postgresql://127.0.0.1:'+ports.postgres,mailpitHttp:'http://127.0.0.1:'+ports.mailpitHttp,mailpitSmtp:'smtp://127.0.0.1:'+ports.mailpitSmtp});
  let busy=false,pending=false,status='NEW',startup=null,lock=null,sequence=0,issuerProved=false,webhookReady=false,live=false,retired=false,manualDeadline=null;
  let inputs=null,admin=null,providerKeys=null,diagnosticConfigured=false;
  let terminalBuild=false,buildDiagnostic=null,buildxIdentity=null;
  const buildxDirectory=directory+'/buildx',dockerEnvironment=Object.freeze({...CHILD_ENV,DOCKER_CONFIG:'/private/var/empty',BUILDX_CONFIG:buildxDirectory});
  const recorded={images:{},network:null,volumes:{},containers:{},retiredBackend:null},intents=[],privateIdentities=new Map();
  const ownLabels=kind=>({'payment.sandbox.project':project,'payment.sandbox.run':runId,'payment.sandbox.kind':kind});
  const containerLabels=service=>({...ownLabels('container'),'com.docker.compose.project':project,'com.docker.compose.service':service});
  function check(context) {
    if(context.signal.aborted)throw diagnosticFailure(context.externalSignal?.aborted?'ABORT':'TIMEOUT');
    if(io.now()>=context.deadline)throw diagnosticFailure('TIMEOUT');
  }
  function resources() {
    return frozen(JSON.parse(JSON.stringify({runId,expectedSource,daemon,project,siteKey,ports,cliDirectory:directory+'/cli',endpoints,
      status:pending?'PENDING':status,webhookReady,...(manualPayment?{mode:'MANUAL_PAYMENT',manualPort:options.manualPort}:diagnostic?{mode:'FORM_DIAGNOSTIC'}:{}),...recorded,...(buildDiagnostic?{buildDiagnostic}:{})})));
  }
  async function operation(phase,limits,body,{cleanup=false,diagnostic=false}={}) {
    if(terminalBuild&&!diagnostic)throw fixed(phase+'_PENDING');
    if(busy)throw fixed('OPERATION_BUSY');busy=true;
    const controller=new AbortController();let timer,external,onAbort;
    try {
      if(pending&&!cleanup)fail();
      if(!Number.isSafeInteger(limits?.deadline)||limits.deadline<=io.now()||(limits.signal!==undefined&&!(limits.signal instanceof AbortSignal)))fail();
      if(manualPayment){
        if(manualDeadline===null){if(limits.deadline>io.now()+45*60000)fail();manualDeadline=limits.deadline;}
        if(cleanup?limits.deadline>manualDeadline+120000:phase==='VERIFY'?limits.deadline>manualDeadline:limits.deadline!==manualDeadline)fail();
      }
      external=limits.signal;onAbort=()=>controller.abort();external?.addEventListener('abort',onAbort,{once:true});if(external?.aborted)controller.abort();
      timer=io.setTimeout(onAbort,Math.min(limits.deadline-io.now(),2147483647));
      const context={deadline:limits.deadline,signal:controller.signal,externalSignal:external};check(context);
      const result=await body(context);
      if(!diagnostic||context.buildCompleted!==true)check(context);return result;
    } catch {pending=true;throw fixed(phase+'_PENDING');}
    finally {io.clearTimeout(timer);external?.removeEventListener('abort',onAbort);busy=false;}
  }
  async function execute(file,args,settings,context) {
    check(context);
    let result;
    try {result=await io.execute(file,args,{...settings,signal:context.signal,timeout:Math.min(settings.timeout,Math.max(1,context.deadline-io.now()))});}
    catch(error) {check(context);throw error;}
    check(context);
    if(typeof result?.stdout!=='string'||typeof result.stderr!=='string')fail();
    if(Buffer.byteLength(result.stdout)+Buffer.byteLength(result.stderr)>65536)throw diagnosticFailure('OUTPUT_LIMIT');
    return result;
  }
  async function docker(args,context,budget=30000) {
    await buildxProof(context);
    return execute(DOCKER,['--config','/private/var/empty','--host',daemon,...args],{cwd:ROOT,env:dockerEnvironment,shell:false,
      stdio:['ignore','pipe','pipe'],timeout:budget,maxBuffer:65536},context);
  }
  async function inspect(kind,selector,format,context) {
    const result=await docker([kind,'inspect','--format',format,selector],context,10000);
    const invalid=()=>{if(buildDiagnostic&&kind==='image')throw diagnosticFailure('INVALID_IMAGE_PROOF');fail();};
    if(result.stderr!=='')invalid();
    let value;try {value=JSON.parse(result.stdout);}catch {invalid();}
    if(!object(value))invalid();return value;
  }
  function composeArgs(envFile,action,{override=true,stripe=false}={}) {
    return ['compose','-p',project,'--project-directory',ROOT+'/infrastructure','--env-file',envFile,'-f',ROOT+'/infrastructure/compose.yaml',
      ...(override?['-f',directory+'/owned.yaml']:[]),...(stripe?['-f',ROOT+'/infrastructure/compose.stripe-sandbox.yaml']:[]),'--profile','smoke',...action];
  }
  const validFile=s=>s.isFile()&&!s.isSymbolicLink()&&s.uid===process.getuid()&&(s.mode&0o7777)===0o600&&s.nlink===1&&Number.isSafeInteger(s.size)&&s.size>=0&&s.size<=65536;
  async function directoryProof(path,context,privateMode=false) {
    check(context);const s=await io.lstat(path);
    if(!s.isDirectory()||s.isSymbolicLink()||(s.uid!==0&&s.uid!==process.getuid())||await io.realpath(path)!==path
      ||(privateMode&&(s.uid!==process.getuid()||(s.mode&0o7777)!==0o700)))fail();check(context);
    return s;
  }
  async function buildxProof(context) {
    const actual=await directoryProof(buildxDirectory,context,true);
    if(!buildxIdentity||actual.ino!==buildxIdentity.ino||actual.dev!==buildxIdentity.dev)fail();
  }
  async function syncDirectory(path,context,identity=null) {
    check(context);const file=await io.open(path,constants.O_RDONLY|constants.O_NOFOLLOW|constants.O_DIRECTORY);
    try {
      const s=await file.stat();if(!s.isDirectory()||s.isSymbolicLink()||(s.uid!==0&&s.uid!==process.getuid()))fail();
      if(identity&&(s.ino!==identity.ino||s.dev!==identity.dev||s.uid!==process.getuid()||(s.mode&0o7777)!==0o700))fail();
      await file.sync();
    }
    finally {await file.close();}check(context);
  }
  async function exclusiveFile(path,contents,context,hold=false) {
    await directoryProof(dirname(path),context,true);
    check(context);const file=await io.open(path,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);
    let retain=false;
    try {
      check(context);const initial=await file.stat();check(context);if(!validFile(initial))fail();
      await file.writeFile(contents);check(context);
      const actual=await file.stat(),named=await io.lstat(path);
      if(!validFile(actual)||!validFile(named)||actual.ino!==initial.ino||actual.dev!==initial.dev||named.ino!==initial.ino||named.dev!==initial.dev||await io.realpath(path)!==path)fail();
      check(context);await file.sync();check(context);await syncDirectory(dirname(path),context);privateIdentities.set(path,{ino:actual.ino,dev:actual.dev});
      check(context);retain=hold;return file;
    } finally {if(!retain)await file.close();}
  }
  async function atomicPrivate(path,contents,context,terminalDiagnostic=false) {
    const temporary=path+'.tmp-'+(++sequence);await exclusiveFile(temporary,contents,context);
    const expected=privateIdentities.get(path);
    if(expected) {const current=await io.lstat(path);if(!validFile(current)||current.ino!==expected.ino||current.dev!==expected.dev||await io.realpath(path)!==path)fail();}
    else if((await io.readdir(dirname(path))).includes(path.slice(dirname(path).length+1)))fail();
    check(context);await io.rename(temporary,path);privateIdentities.set(path,privateIdentities.get(temporary));privateIdentities.delete(temporary);
    await syncDirectory(dirname(path),context);check(context);
    // This checked, durable diagnostic commit is its one terminal decision boundary.
    if(terminalDiagnostic)context.buildCompleted=true;
  }
  async function ledger(context,terminalDiagnostic=false) {
    await atomicPrivate(directory+'/runtime.json',JSON.stringify({...resources(),intents})+'\n',context,terminalDiagnostic);
  }
  async function intent(action,name,context) {intents.push({action,name,status:'DISPATCHING'});await ledger(context);}
  async function acknowledgement(context) {intents.at(-1).status='ACKNOWLEDGED';await ledger(context);}
  async function sourceGate(context,portsCheck=true) {
    const result=await io.preflight({runId,expectedSource,daemon,ports,env,...(manualPayment?{manualPort:options.manualPort}:{})},{lstat:io.lstat,realpath:io.realpath,
      execute:(file,args,settings)=>execute(file,args,settings,context),portAvailable:(port,host)=>io.portAvailable(port,host,context)});
    check(context);if(!object(result)||Object.keys(result).length!==1||result.status!=='READY')fail();
    if(portsCheck)for(const port of [...Object.values(ports),...(manualPayment?[options.manualPort]:[])]) {check(context);if(await io.portAvailable(port,'127.0.0.1',context)!==true)fail();}
  }
  async function sourceIdentity(context) {
    const settings={cwd:ROOT,env:{...CHILD_ENV,GIT_OPTIONAL_LOCKS:'0',GIT_CONFIG_GLOBAL:'/dev/null',GIT_CONFIG_SYSTEM:'/dev/null'},shell:false,timeout:10000,maxBuffer:65536};
    const head=await execute('/usr/bin/git',['rev-parse','HEAD'],settings,context);
    if(![expectedSource,expectedSource+'\n'].includes(head.stdout)||head.stderr!=='')fail();
    const status=await execute('/usr/bin/git',['-c','core.fsmonitor=false','status','--porcelain=v1','--untracked-files=no'],settings,context);
    if(status.stdout!==''||status.stderr!=='')fail();
  }
  async function privateTree(context) {
    for(let path=ROOT;;path=dirname(path)) {await directoryProof(path,context);if(path==='/')break;}
    for(const file of [ROOT+'/.dockerignore',ROOT+'/backend/.dockerignore']) {
      const text=(await io.readFile(file)).toString('utf8'),lines=text.split(/\r?\n/).filter(line=>line&&!line.startsWith('#'));
      for(const required of ['**/test-results','**/.superpowers','**/*.env.local','**/fixtures/customer-issuer-*.jwks.json'])if(!lines.includes(required))fail();
      if(lines.some(line=>line.startsWith('!')&&!['!**/.env.example','!**/*.env.example'].includes(line)))fail();check(context);
    }
    for(const path of [ROOT+'/test-results',ROOT+'/test-results/stripe-sandbox']) {
      const names=await io.readdir(dirname(path));if(!names.includes(path.slice(dirname(path).length+1)))await io.mkdir(path,{mode:0o700});
      await directoryProof(path,context,path.endsWith('/stripe-sandbox'));await syncDirectory(dirname(path),context);
    }
    await io.mkdir(directory,{mode:0o700});await directoryProof(directory,context,true);await syncDirectory(dirname(directory),context);
    lock=await exclusiveFile(directory+'/owner.lock',runId+'\n',context,true);await ledger(context);
    await io.mkdir(buildxDirectory,{mode:0o700});
    const owned=await directoryProof(buildxDirectory,context,true);buildxIdentity={ino:owned.ino,dev:owned.dev};
    await syncDirectory(buildxDirectory,context,buildxIdentity);await syncDirectory(directory,context);await buildxProof(context);
  }
  function envText(values) {return Object.entries(values).map(([name,value])=>name+'='+value+'\n').join('');}
  function portInputs() {return {PAYMENT_BACKEND_HOST_PORT:ports.backend,PAYMENT_DEMO_HOST_PORT:ports.demo,PAYMENT_POSTGRES_HOST_PORT:ports.postgres,
    PAYMENT_MAILPIT_HOST_PORT:ports.mailpitHttp,PAYMENT_MAILPIT_SMTP_HOST_PORT:ports.mailpitSmtp};}
  async function imageProof(service,context) {
    const value=await inspect('image',tags[service],'{"Id":{{json .ID}},"User":{{json .Config.User}}}',context);
    if(!imageId(value.Id)||typeof value.User!=='string') {if(buildDiagnostic)throw diagnosticFailure('INVALID_IMAGE_PROOF');fail();}
    recorded.images[service]={tag:tags[service],id:value.Id,user:value.User};
  }
  async function build(context) {
    const synthetic={PAYMENT_DB_PASSWORD:'buildOnly',PAYMENT_ADMIN_BOOTSTRAP_USERNAME:'buildOnly',PAYMENT_ADMIN_BOOTSTRAP_PASSWORD:'buildOnly',
      PAYMENT_DATA_ENCRYPTION_KEY:'buildOnly',PAYMENT_DONATION_MESSAGE_FINGERPRINT_KEY:'buildOnly',PAYMENT_CUSTOMER_DIGEST_KEY_V1:'buildOnly',PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1:'buildOnly',...portInputs()};
    await exclusiveFile(directory+'/build.env',envText(synthetic),context);await intent('BUILD','backend+demo-site',context);
    if(buildDiagnostic) {buildDiagnostic.stage='BUILD_COMMAND';await ledger(context);}
    await docker(composeArgs(directory+'/build.env',['build','--quiet',...(manualPayment?['--build-arg','PAYMENT_DEMO_BUILD_MODE=manual-payment']:diagnostic?['--build-arg','PAYMENT_DEMO_BUILD_MODE=form-diagnostic']:[]),'backend','demo-site'],{override:false}),context,1200000);
    for(const service of ['backend','demo-site']) {
      if(buildDiagnostic) {buildDiagnostic.stage=service==='backend'?'BACKEND_IMAGE_PROOF':'DEMO_IMAGE_PROOF';await ledger(context);}
      await imageProof(service,context);
    }
    await acknowledgement(context);
  }
  async function pullImages(context) {
    for(const service of ['postgres','mailpit','callback-receiver']) {
      await intent('PULL',service,context);await docker(['pull','--quiet',tags[service]],context);await imageProof(service,context);await acknowledgement(context);
    }
  }
  async function generate(context) {
    const values=Array.from({length:6},()=>io.randomBytes(32).toString('base64'));
    if(values.some(value=>!/^([A-Za-z0-9+/]{43})=$/.test(value))||new Set(values).size!==6)fail();
    admin={username:'s5a-'+io.randomBytes(16).toString('hex'),password:values[1]};
    inputs={PAYMENT_DB_PASSWORD:values[0],PAYMENT_ADMIN_BOOTSTRAP_USERNAME:admin.username,PAYMENT_ADMIN_BOOTSTRAP_PASSWORD:admin.password,
      PAYMENT_DATA_ENCRYPTION_KEY:values[2],PAYMENT_DONATION_MESSAGE_FINGERPRINT_KEY:values[3],PAYMENT_CUSTOMER_DIGEST_KEY_V1:values[4],
      PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1:values[5],PAYMENT_FAKE_AUTO_SUCCESS:'false',PAYMENT_TELEMETRY_SITEKEYS:siteKey,...portInputs()};
    const rsa=io.generateKeyPairSync('rsa',{modulusLength:2048}),ec=io.generateKeyPairSync('ec',{namedCurve:'prime256v1'});
    const keys=[{...rsa.privateKey.export({format:'jwk'}),kid:'local-fixture-rs',alg:'RS256',use:'sig'},
      {...ec.privateKey.export({format:'jwk'}),kid:'local-fixture-es',alg:'ES256',use:'sig'}];
    await exclusiveFile(directory+'/app.env',envText(inputs),context);await exclusiveFile(directory+'/issuer.jwks.json',JSON.stringify({keys})+'\n',context);
    if(!diagnostic)for(const suffix of ['/cli','/cli/home','/cli/config','/cli/config/stripe']) {
      await io.mkdir(directory+suffix,{mode:0o700});await directoryProof(directory+suffix,context,true);await syncDirectory(dirname(directory+suffix),context);
    }
    if(!diagnostic)await exclusiveFile(directory+'/cli/config/stripe/config.toml','',context);
  }
  async function absent(args,context) {if((await docker(args,context)).stdout.trim()!=='')fail();}
  async function noPriorResources(context) {
    await absent(['ps','--all','--no-trunc','--filter','label=com.docker.compose.project='+project,'--format','{{.ID}}'],context);
    await absent(['network','ls','--no-trunc','--filter','name='+networkName,'--format','{{.ID}}'],context);
    for(const name of Object.values(volumeNames))await absent(['volume','ls','--filter','name='+name,'--format','{{.Name}}'],context);
    for(const service of ['backend','demo-site'])await absent(['image','ls','--no-trunc','--filter','reference='+tags[service],'--format','{{.ID}}'],context);
  }
  async function infrastructure(context) {
    await absent(['ps','--all','--no-trunc','--filter','label=com.docker.compose.project='+project,'--format','{{.ID}}'],context);
    await absent(['network','ls','--no-trunc','--filter','name='+networkName,'--format','{{.ID}}'],context);
    await intent('CREATE_NETWORK',networkName,context);
    const output=await docker(['network','create','--driver','bridge',...Object.entries(ownLabels('network')).flatMap(([key,value])=>['--label',key+'='+value]),networkName],context);
    const network=await inspect('network',networkName,NETWORK_FORMAT,context);
    if(!fullId(network.Id)||output.stdout.trim()!==network.Id||network.Name!==networkName||network.Driver!=='bridge'||network.Scope!=='local'||!same(network.Labels,ownLabels('network')))fail();
    recorded.network=network;await acknowledgement(context);
    for(const [kind,name] of Object.entries(volumeNames)) {
      await absent(['volume','ls','--filter','name='+name,'--format','{{.Name}}'],context);await intent('CREATE_VOLUME',name,context);
      const output=await docker(['volume','create','--driver','local',...Object.entries(ownLabels(kind)).flatMap(([key,value])=>['--label',key+'='+value]),name],context);
      const volume=await inspect('volume',name,VOLUME_FORMAT,context);
      if(volume.Name!==name||output.stdout.trim()!==name||volume.Driver!=='local'||volume.Scope!=='local'||typeof volume.CreatedAt!=='string'||!Number.isFinite(Date.parse(volume.CreatedAt))||!same(volume.Labels,ownLabels(kind)))fail();
      recorded.volumes[kind]=volume;await acknowledgement(context);
    }
  }
  function expectedPorts(service) {
    const pairs={postgres:[['5432/tcp',ports.postgres]],mailpit:[['8025/tcp',ports.mailpitHttp],['1025/tcp',ports.mailpitSmtp]],
      backend:[['8080/tcp',ports.backend]],'demo-site':[['8080/tcp',ports.demo]],'callback-receiver':[['8080/tcp',ports.receiver]]}[service]??[];
    return Object.fromEntries(pairs.map(([container,host])=>[container,[{HostIp:'127.0.0.1',HostPort:String(host)}]]));
  }
  function expectedMounts(service) {
    return {postgres:[{Type:'volume',Name:volumeNames.postgres,Destination:'/var/lib/postgresql',RW:true}],
      backend:[{Type:'volume',Name:volumeNames.issuer,Destination:'/app/local-fixtures',RW:false}],
      'callback-receiver':[{Type:'bind',Source:ROOT+'/infrastructure/callback-receiver.mjs',Destination:'/app/callback-receiver.mjs',RW:false}],
      'issuer-installer':[{Type:'bind',Source:directory+'/issuer.jwks.json',Destination:'/input/issuer.json',RW:false},{Type:'volume',Name:volumeNames.issuer,Destination:'/issuer',RW:true}],
      'issuer-probe':[{Type:'volume',Name:volumeNames.issuer,Destination:'/issuer',RW:false}]}[service]??[];
  }
  async function infrastructureProof(context) {
    if(!recorded.network||!same(await inspect('network',recorded.network.Id,NETWORK_FORMAT,context),recorded.network))fail();
    for(const volume of Object.values(recorded.volumes))if(!same(await inspect('volume',volume.Name,VOLUME_FORMAT,context),volume))fail();
  }
  async function containerProof(service,selector,context,{liveRequired=false,neverStarted=false,cleanupOnly=false}={}) {
    const value=await inspect('container',selector,CONTAINER_FORMAT,context),recordedContainer=recorded.containers[service];
    const proof=proveOwnedContainer(value,{service,project,runId,network:recorded.network,sourceImage:recorded.images[service.startsWith('issuer-')?'backend':service],
      recordedContainer,expectedMounts:expectedMounts(service),expectedPorts:expectedPorts(service),liveRequired,neverStarted,cleanupOnly,backendStripeEnabled:Boolean(retired||diagnosticConfigured)});
    if(proof.infrastructureRequired)await infrastructureProof(context);
    if(recordedContainer)recorded.containers[service]=proof.safe;
    return {safe:proof.safe,state:proof.state};
  }
  async function projectProof(context) {
    const output=await docker(['ps','--all','--no-trunc','--filter','label=com.docker.compose.project='+project,'--format','{{.ID}}'],context,10000);
    const ids=output.stdout.trim()===''?[]:output.stdout.trim().split('\n');
    if(ids.some(value=>!fullId(value))||new Set(ids).size!==ids.length||ids.sort().join(',')!==Object.values(recorded.containers).map(value=>value.id).sort().join(','))fail();
  }
  async function topology(context,liveRequired=false,neverStarted=false) {
    await infrastructureProof(context);await projectProof(context);
    const delayed=manualPayment&&!recorded.containers.backend&&!liveRequired;
    if(delayed)await plannedBackend(context);
    const services=delayed?SERVICES.filter(service=>service!=='backend'):SERVICES;
    if(services.some(service=>!recorded.containers[service]))fail();
    for(const service of services)await containerProof(service,recorded.containers[service].id,context,{liveRequired,neverStarted});
  }
  async function plannedBackend(context) {
    if(!manualPayment||recorded.containers.backend||live||retired)fail();
    await sourceIdentity(context);await configProof(context);
    const image=await inspect('image',tags.backend,'{"Id":{{json .ID}},"User":{{json .Config.User}}}',context);
    if(image.Id!==recorded.images.backend.id||image.User!==recorded.images.backend.user)fail();
    await absent(['ps','--all','--no-trunc','--filter','name=^/'+project+'-backend$','--format','{{.ID}}'],context);
    if(await io.portAvailable(ports.backend,'127.0.0.1',context)!==true)fail();
  }
  async function finalEnvironment(context){
    const path=directory+'/final.env',expected=privateIdentities.get(path);if(!expected)fail();
    await directoryProof(directory,context,true);const handle=await io.open(path,constants.O_RDONLY|constants.O_NOFOLLOW);
    try{const held=await handle.stat(),named=await io.lstat(path);
      if(!validFile(held)||!validFile(named)||held.ino!==expected.ino||held.dev!==expected.dev||named.ino!==expected.ino||named.dev!==expected.dev||await io.realpath(path)!==path)fail();check(context);
    }finally{await handle.close();}
  }
  async function helper(service,command,context) {
    const installer=service==='issuer-installer',name=project+'-'+service;await intent('CREATE_HELPER',name,context);
    const args=['container','create','--name',name,'--pull','never','--network','none','--read-only','--user',installer?'0:0':'10001:10001','--cap-drop','ALL',
      ...(installer?['--cap-add','CHOWN','--cap-add','DAC_OVERRIDE']:[]),...Object.entries(containerLabels(service)).flatMap(([key,value])=>['--label',key+'='+value]),
      ...(installer?['--mount','type=bind,src='+directory+'/issuer.jwks.json,dst=/input/issuer.json,readonly']:[]),
      '--mount','type=volume,src='+volumeNames.issuer+',dst=/issuer'+(installer?'':',readonly'),'--entrypoint','/bin/sh',recorded.images.backend.id,'-ec',command];
    const output=await docker(args,context),id=output.stdout.trim();if(!fullId(id))fail();
    const proof=await containerProof(service,id,context,{neverStarted:true});recorded.containers[service]=proof.safe;await acknowledgement(context);
    await infrastructureProof(context);await containerProof(service,id,context,{neverStarted:true});await intent('START_HELPER',id,context);
    await docker(['container','start',id],context);const wait=await docker(['container','wait',id],context);
    if(!/^0\n?$/.test(wait.stdout))fail();const done=await containerProof(service,id,context);
    if(done.state.Running||done.state.Status!=='exited'||done.state.ExitCode!==0)fail();await acknowledgement(context);
  }
  async function issuer(context) {
    await helper('issuer-installer','cp /input/issuer.json /issuer/customer-issuer-private.jwks.json; chmod 0700 /issuer; chmod 0600 /issuer/customer-issuer-private.jwks.json; chown 10001:10001 /issuer /issuer/customer-issuer-private.jwks.json',context);
    await helper('issuer-probe','test -r /issuer/customer-issuer-private.jwks.json; test -s /issuer/customer-issuer-private.jwks.json; test "$(stat -c %u:%g:%a /issuer)" = 10001:10001:700; test "$(stat -c %u:%g:%a /issuer/customer-issuer-private.jwks.json)" = 10001:10001:600',context);
    issuerProved=true;
  }
  function overrideText() {
    const lines=['services:'];
    for(const service of SERVICES) {
      lines.push('  '+service+':','    image: '+tags[service],'    container_name: '+project+'-'+service,'    labels:');
      for(const [key,value] of Object.entries(ownLabels('container')))lines.push('      '+key+': '+value);
      lines.push('    ports: !override');for(const [port,bindings] of Object.entries(expectedPorts(service)))for(const binding of bindings)lines.push('      - "127.0.0.1:'+binding.HostPort+':'+port.split('/')[0]+'"');
      if(service==='backend')lines.push('    environment:','      PAYMENT_STRIPE_SANDBOX_ENABLED: "false"','    volumes: !override','      - type: volume','        source: owned-issuer','        target: /app/local-fixtures','        read_only: true');
    }
    lines.push('networks:','  default:','    external: true','    name: '+networkName,'volumes:','  payment-postgres:','    external: true','    name: '+volumeNames.postgres,
      '  owned-issuer:','    external: true','    name: '+volumeNames.issuer);
    return lines.join('\n')+'\n';
  }
  async function configProof(context) {
    // Render only build-only interpolation; app/Stripe secrets never enter config output.
    const result=await docker(composeArgs(directory+'/build.env',['config','--format','json']),context,10000);
    if(result.stderr!=='')fail();const config=JSON.parse(result.stdout);
    if(!object(config.services)||config.networks?.default?.external!==true||config.networks.default.name!==networkName)fail();
    for(const [key,name] of [['payment-postgres',volumeNames.postgres],['owned-issuer',volumeNames.issuer]]) {
      if(config.volumes?.[key]?.external!==true||config.volumes[key].name!==name)fail();
    }
    for(const service of SERVICES) {
      const value=config.services[service];if(!object(value)||value.image!==tags[service]||value.container_name!==project+'-'+service||!same(value.labels,ownLabels('container'))
        ||!object(value.networks)||Object.keys(value.networks).join(',')!=='default')fail();
      if(!Array.isArray(value.ports)||value.ports.length!==Object.keys(expectedPorts(service)).length)fail();
      const ports={};
      for(const binding of value.ports) {
        if(binding.host_ip!=='127.0.0.1'||binding.protocol!=='tcp'||!Number.isInteger(binding.target)||Object.hasOwn(ports,binding.target+'/tcp'))fail();
        ports[binding.target+'/tcp']=[{HostIp:binding.host_ip,HostPort:String(binding.published)}];
      }
      portProof(ports,expectedPorts(service));
      const expected=expectedMounts(service),mounts=value.volumes??[];
      if(!Array.isArray(mounts)||mounts.length!==expected.length)fail();
      for(const mount of expected) {
        const actual=mounts.find(candidate=>candidate.target===mount.Destination),source=mount.Type==='bind'?mount.Source:service==='backend'?'owned-issuer':'payment-postgres';
        if(!actual||actual.type!==mount.Type||actual.source!==source||Boolean(actual.read_only)!==!mount.RW)fail();
      }
      if(STRIPE_NAMES.some(name=>Object.hasOwn(value.environment??{},name))||(service==='backend'&&value.environment?.PAYMENT_STRIPE_SANDBOX_ENABLED!=='false'))fail();
    }
  }
  async function prepare(context) {
    if(status!=='NEW')fail();startup={deadline:context.deadline,signal:context.externalSignal};
    await sourceGate(context);await privateTree(context);await noPriorResources(context);await build(context);await pullImages(context);await generate(context);await infrastructure(context);await issuer(context);
    await exclusiveFile(directory+'/owned.yaml',overrideText(),context);await configProof(context);await sourceGate(context);
    if(diagnostic)await configureDiagnostic(context);
    await intent('CREATE_STOPPED',project,context);
    const services=manualPayment?SERVICES.filter(service=>service!=='backend'):SERVICES;
    await docker(composeArgs(directory+(diagnostic?'/final.env':'/app.env'),['up','--no-start','--no-deps','--no-build','--pull','never',...services],{stripe:diagnostic}),context);
    for(const service of services) {const proof=await containerProof(service,project+'-'+service,context,{neverStarted:true});recorded.containers[service]=proof.safe;}
    await topology(context,false,true);await acknowledgement(context);status='PREPARED';await ledger(context);return true;
  }

  async function buildOnly(context) {
    let failure=null;
    try {
      await sourceGate(context);buildDiagnostic.stage='PRIVATE';await privateTree(context);await noPriorResources(context);await build(context);
    } catch(error) {failure=failures.get(error)??{category:'UNKNOWN'};}
    finally {
      if(lock) {try {await lock.close();} catch {failure={category:'UNKNOWN'};}finally {lock=null;}}
    }
    if(failure) {
      pending=true;buildDiagnostic={stage:buildDiagnostic.stage,result:failure.category==='UNKNOWN'?'UNCONFIRMED':'FAILED',...failure};
      if(privateIdentities.has(directory+'/runtime.json'))await ledger(context);
      fail();
    }
    buildDiagnostic={stage:'FINAL_RECORD',result:'SUCCEEDED',category:'NONE'};status='BUILD_ONLY_COMPLETE';
    try {await ledger(context,true);} catch {
      pending=true;buildDiagnostic={stage:'FINAL_RECORD',result:'UNCONFIRMED',category:'UNKNOWN'};
      // A rename can precede a failed directory fsync. Re-record uncertainty if the original budget permits.
      try {await ledger(context);}catch {}fail();
    }
    return true;
  }

  async function boundedRead(file,context) {
    check(context);const buffer=Buffer.alloc(65537),result=await file.read(buffer,0,buffer.length,0);
    if(!Number.isInteger(result.bytesRead)||result.bytesRead<0||result.bytesRead>65536)fail();check(context);
    return buffer.subarray(0,result.bytesRead);
  }
  async function userFile(context) {
    await directoryProof(ROOT+'/infrastructure',context);
    const before=await io.lstat(USERFILE);
    if(!validFile(before)||await io.realpath(USERFILE)!==USERFILE)fail();
    const file=await io.open(USERFILE,constants.O_RDONLY|constants.O_NOFOLLOW);
    try {
      const actual=await file.stat();
      if(!validFile(actual)||actual.ino!==before.ino||actual.dev!==before.dev)fail();
      const bytes=await boundedRead(file,context);
      if(bytes.length!==actual.size)fail();return {file,stat:actual,bytes};
    } catch {await file.close();fail();}
  }
  async function unchanged(original,context) {
    const current=await io.lstat(USERFILE),held=await original.file.stat();
    if(!validFile(current)||!validFile(held)||current.ino!==original.stat.ino||current.dev!==original.stat.dev
      ||held.ino!==original.stat.ino||held.dev!==original.stat.dev||await io.realpath(USERFILE)!==USERFILE
      ||!(await boundedRead(original.file,context)).equals(original.bytes))fail();
  }
  async function configureDiagnostic(context) {
    const original=await userFile(context);
    try {
      // Accept the existing verifier as opaque input; never publish it or rewrite user configuration.
      const text=original.bytes.toString('utf8'),values={};
      if(!Buffer.from(text).equals(original.bytes)||text.includes('\0'))fail();
      for(const line of text.split(/\r?\n/)) {
        if(/^\s*(?:#.*)?$/.test(line))continue;
        const m=/^([A-Z_]+)=(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s'"\r\n]*))$/.exec(line);
        if(!m||!STRIPE_NAMES.includes(m[1])||Object.hasOwn(values,m[1]))fail();values[m[1]]=m[2]??m[3]??m[4];
      }
      if(Object.keys(values).length!==3||! /^(?:sk|rk)_test_[A-Za-z0-9]+$/.test(values[STRIPE_NAMES[0]])
        ||!/^pk_test_[A-Za-z0-9]+$/.test(values[STRIPE_NAMES[1]])||Object.values(values).some(v=>/replace_me|your_key|synthetic|sentinel|example/i.test(v)))fail();
      await unchanged(original,context);providerKeys=Object.freeze({secretKey:values[STRIPE_NAMES[0]],publishableKey:values[STRIPE_NAMES[1]]});
      const verifier='whsec_'+io.randomBytes(32).toString('hex');if(!/^whsec_[a-f0-9]{64}$/.test(verifier))fail();
      await exclusiveFile(directory+'/final.env',envText({...inputs,...values,[STRIPE_NAMES[2]]:verifier}),context);diagnosticConfigured=true;
    }finally{await original.file.close();}
  }
  function parsedUser(bytes,secret) {
    const text=bytes.toString('utf8');if(!Buffer.from(text).equals(bytes)||text.includes('\0'))fail();
    const assignments={},lines=text.split(/(?<=\n)/);let webhookIndex=-1;
    for(let index=0;index<lines.length;index++) {
      const body=lines[index].replace(/\r?\n$/,'');
      if(/^\s*(?:#.*)?$/.test(body))continue;
      const match=/^([A-Z_]+)=(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s'"\r\n]*))$/.exec(body);
      if(!match||!STRIPE_NAMES.includes(match[1])||Object.hasOwn(assignments,match[1]))fail();
      assignments[match[1]]=match[2]??match[3]??match[4];
      if(match[1]===STRIPE_NAMES[2])webhookIndex=index;
    }
    if(Object.keys(assignments).length!==3||! /^(?:sk|rk)_test_[A-Za-z0-9]+$/.test(assignments[STRIPE_NAMES[0]])
      ||!/^pk_test_[A-Za-z0-9]+$/.test(assignments[STRIPE_NAMES[1]]))fail();
    if(Object.values(assignments).some(value=>/replace_me|your_key|synthetic|sentinel|example/i.test(value)))fail();
    const previous=assignments[STRIPE_NAMES[2]];if(previous!==''&&previous!==secret)fail();
    if(previous==='') {
      const line=lines[webhookIndex],offset=line.indexOf('=')+1,quote=["'",'"'].includes(line[offset])?line[offset]:'';
      lines[webhookIndex]=line.slice(0,offset)+quote+secret+quote+line.slice(offset+(quote?2:0));
    }
    return {assignments:{...assignments,[STRIPE_NAMES[2]]:secret},updated:Buffer.from(lines.join('')),changed:previous===''};
  }
  async function releaseWriter(path,file,context) {
    await directoryProof(dirname(path),context,true);
    const expected=privateIdentities.get(path),held=await file.stat(),named=await io.lstat(path);
    if(!expected||!validFile(held)||!validFile(named)||held.ino!==expected.ino||held.dev!==expected.dev
      ||named.ino!==expected.ino||named.dev!==expected.dev||await io.realpath(path)!==path)fail();
    check(context);await io.unlink(path);await syncDirectory(dirname(path),context);privateIdentities.delete(path);
  }
  async function persist(secret,context) {
    if(status!=='PREPARED'||!issuerProved||webhookReady||typeof secret!=='string'||!/^whsec_[A-Za-z0-9_]{16,256}$/.test(secret))fail();
    await topology(context,false,true);await sourceIdentity(context);await intent('PERSIST_WEBHOOK','backend-key-persistence',context);
    const writerPath=ROOT+'/test-results/stripe-sandbox/user-env.lock';
    const writer=await exclusiveFile(writerPath,runId+'\n',context,true);let complete=false,original;
    try {
      original=await userFile(context);const parsed=parsedUser(original.bytes,secret);
      if(parsed.changed) {
        const temporary=directory+'/stripe-user.env.tmp';await exclusiveFile(temporary,parsed.updated,context);
        await unchanged(original,context);check(context);await io.rename(temporary,USERFILE);await syncDirectory(ROOT+'/infrastructure',context);
      } else await unchanged(original,context);
      await exclusiveFile(directory+'/final.env',envText({...inputs,...parsed.assignments}),context);
      webhookReady=true;await acknowledgement(context);complete=true;return true;
    } finally {
      try {if(original)await original.file.close();if(complete)await releaseWriter(writerPath,writer,context);}
      finally {await writer.close();}
    }
  }
  async function sleepForHealth(context) {
    check(context);
    await new Promise((resolveWait,rejectWait)=>{
      let timer;const abort=()=>{io.clearTimeout(timer);context.signal.removeEventListener('abort',abort);rejectWait(null);};
      context.signal.addEventListener('abort',abort,{once:true});
      timer=io.setTimeout(()=>{context.signal.removeEventListener('abort',abort);resolveWait();},Math.min(1000,Math.max(1,context.deadline-io.now())));
      if(context.signal.aborted)abort();
    });check(context);
  }
  async function healthy(service,context) {
    for(;;) {
      await infrastructureProof(context);const proof=await containerProof(service,recorded.containers[service].id,context);
      if(!proof.state.Running||proof.state.Status!=='running'||!['starting','healthy'].includes(proof.state.Health))fail();
      if(proof.state.Health==='healthy') {await containerProof(service,proof.safe.id,context,{liveRequired:true});return;}
      await sleepForHealth(context);
    }
  }
  async function healthRequests(context) {
    for(const [service,path] of [['backend','/actuator/health/readiness'],['demo-site','/health'],['callback-receiver','/health']]) {
      await infrastructureProof(context);await containerProof(service,recorded.containers[service].id,context,{liveRequired:true});
      const endpoint=service==='demo-site'?endpoints.demo:service==='callback-receiver'?endpoints.receiver:endpoints.backend;
      if(await io.health(endpoint+path,{signal:context.signal,deadline:Math.min(context.deadline,io.now()+10000)})!==true)fail();check(context);
    }
  }
  async function start(context) {
    if(status!=='PREPARED'||!(diagnostic?diagnosticConfigured:webhookReady)||!issuerProved||retired||live)fail();
    await sourceIdentity(context);await topology(context,false,true);
    for(const port of Object.values(ports))if(await io.portAvailable(port,'127.0.0.1',context)!==true)fail();
    if(!diagnostic){if(!manualPayment){const old=recorded.containers.backend;await containerProof('backend',old.id,context,{neverStarted:true});
    await intent('RETIRE_PLACEHOLDER',old.id,context);
    const removed=await docker(['container','rm',old.id],context);if(removed.stdout.trim()!==old.id)fail();
    recorded.retiredBackend=old;delete recorded.containers.backend;retired=true;await projectProof(context);await acknowledgement(context);
    }else{await plannedBackend(context);await finalEnvironment(context);retired=true;}
    await intent('CREATE_FINAL_BACKEND',project+'-backend',context);
    await docker(composeArgs(directory+'/final.env',['up','--no-start','--no-deps','--no-build','--pull','never','backend'],{stripe:true}),context);
    const proof=await containerProof('backend',project+'-backend',context,{neverStarted:true});recorded.containers.backend=proof.safe;
    await topology(context,false,true);await acknowledgement(context);}
    for(const service of SERVICES) {
      await topology(context);await containerProof(service,recorded.containers[service].id,context,{neverStarted:true});
      await intent('START_SERVICE',recorded.containers[service].id,context);await docker(['container','start',recorded.containers[service].id],context);
      await healthy(service,context);await acknowledgement(context);
    }
    await topology(context,true);await healthRequests(context);live=true;status='LIVE';await ledger(context);return true;
  }
  async function verify(context) {
    if(!live||status!=='LIVE')fail();await sourceIdentity(context);await topology(context,true);await healthRequests(context);return true;
  }
  function controlled(promise,context) {
    check(context);
    return new Promise((resolveWait,rejectWait)=>{
      const abort=()=>rejectWait(null);context.signal.addEventListener('abort',abort,{once:true});
      Promise.resolve(promise).then(value=>{context.signal.removeEventListener('abort',abort);resolveWait(value);},()=>{context.signal.removeEventListener('abort',abort);rejectWait(null);});
      if(context.signal.aborted)abort();
    });
  }
  async function stop(context) {
    // Root must stop its R2 listener before calling this capability.
    await infrastructureProof(context);await projectProof(context);
    for(const [service,container] of Object.entries(recorded.containers))await containerProof(service,container.id,context,{cleanupOnly:true});
    for(const [service,container] of Object.entries(recorded.containers).reverse()) {
      await infrastructureProof(context);const proof=await containerProof(service,container.id,context,{cleanupOnly:true});
      if(proof.state.Running) {
        await docker(['container','stop','--time','10',container.id],context);
        const stopped=await containerProof(service,container.id,context,{cleanupOnly:true});if(stopped.state.Running||!['exited','created'].includes(stopped.state.Status))fail();
      }
    }
    await projectProof(context);if(intents.some(value=>value.status!=='ACKNOWLEDGED'))fail();
    live=false;status='STOPPED';await ledger(context);if(lock) {await lock.close();lock=null;}return true;
  }

  const capabilities={
    buildOnly:async limits=>{
      if(terminalBuild||busy||pending||status!=='NEW')throw fixed(busy?'OPERATION_BUSY':'BUILD_PENDING');
      terminalBuild=true;buildDiagnostic={stage:'ADMISSION',result:'RUNNING',category:'NONE'};
      try {return await operation('BUILD',limits,buildOnly,{diagnostic:true});}
      catch(error) {if(buildDiagnostic.result==='RUNNING')buildDiagnostic={stage:buildDiagnostic.stage,result:'UNCONFIRMED',category:'UNKNOWN'};throw error;}
    },
    prepare:limits=>operation('PREPARE',limits,prepare),
    verifyForwardTarget:()=>operation('FORWARD',startup,async context=>{if(status!=='PREPARED'&&!live)fail();await sourceIdentity(context);await topology(context,live,!live);
      if(!live) {await sourceGate(context);if(await io.portAvailable(ports.backend,'127.0.0.1',context)!==true)fail();}return true;}),
    persistWebhookSecret:secret=>operation('PERSIST',startup,context=>persist(secret,context)),
    start:limits=>operation('START',limits,start),verify:limits=>operation('VERIFY',limits,verify),resources,
    withAdminCredentials:callback=>operation('ADMIN',startup,async context=>{
      if(typeof callback!=='function'||!live||status!=='LIVE')fail();await verify(context);
      if(await controlled(Promise.resolve().then(()=>{check(context);return callback(Object.freeze({username:admin.username,password:admin.password}));}),context)!==true)fail();return true;
    }),
    stop:limits=>operation('STOP',limits,stop,{cleanup:true}),
  };
  if(diagnostic) return Object.freeze({prepare:capabilities.prepare,start:capabilities.start,verify:capabilities.verify,resources,
    withAdminCredentials:capabilities.withAdminCredentials,stop:capabilities.stop,
    withProviderCredentials:callback=>operation('ADMIN',startup,async context=>{
      if(typeof callback!=='function'||!diagnosticConfigured||!providerKeys)fail();
      await sourceIdentity(context);await topology(context,live,!live);
      if(await controlled(Promise.resolve().then(()=>{check(context);return callback(providerKeys);}),context)!==true)fail();return true;
    })});
  return Object.freeze(capabilities);
}
