import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {lstat,realpath} from 'node:fs/promises';
import {createServer} from 'node:net';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const DOCKER='/Applications/Docker.app/Contents/Resources/bin/docker';
const CONFIG='/private/var/empty';
const PORTS=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
const EXHAUSTED=new Set(['2f3f49b1-df07-41b1-b978-bc884b390813','6f684b69-32e6-429f-a34c-e4f69ba2ce18']);
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

// This gate runs before consulting dependencies or any private configuration.
export function formDiagnosticOptIn(env) {
  try {return env?.PAYMENT_STRIPE_SANDBOX_ENABLED==='true'&&env.PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC==='true'
    &&env.PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY==='true'
    &&[undefined,'','false'].includes(env.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS)
    &&[undefined,'','false'].includes(env.PAYMENT_STRIPE_SANDBOX_FULL_RUN)
    &&[undefined,'','false'].includes(env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN)
    &&[undefined,''].includes(env.DEBUG)&&[undefined,''].includes(env.NODE_DEBUG)
    &&[undefined,'','0'].includes(env.PWDEBUG)&&[undefined,'','off'].includes(env.PW_TRACE);}
  catch {return false;}
}

export async function preflightOwnedFormDiagnostic(options,dependencies={}) {
  return preflight(options,dependencies,true);
}

async function preflight(options,dependencies,diagnostic) {
  let phase='OPT_IN',code='OPT_IN_REQUIRED';
  try {
    const env=options?.env;
    if(diagnostic?!formDiagnosticOptIn(env):(env?.PAYMENT_STRIPE_SANDBOX_ENABLED!=='true'||env?.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS!=='true'
      ||env.PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC==='true'||env.PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY==='true'))return pending(phase,code);
    phase='INPUTS';code='CONFIGURATION';
    if(![undefined,''].includes(env.DEBUG)||![undefined,''].includes(env.NODE_DEBUG)||![undefined,'','0'].includes(env.PWDEBUG))return pending(phase,code);
    const {runId,expectedSource,daemon,ports}=options;
    if(typeof runId!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(runId)||EXHAUSTED.has(runId)
      ||typeof expectedSource!=='string'||!/^[0-9a-f]{40}$/.test(expectedSource))return pending(phase,code);
    if(!ports||typeof ports!=='object'||Array.isArray(ports)||Object.keys(ports).sort().join(',')!==[...PORTS].sort().join(','))return pending(phase,code);
    const selectedPorts=PORTS.map(name=>ports[name]);
    if(selectedPorts.some(port=>!Number.isInteger(port)||port<1024||port>65535)||new Set(selectedPorts).size!==6)return pending(phase,code);
    if(typeof daemon!=='string'||/[\s\x00-\x1f\x7f%]/.test(daemon))return pending(phase,code);
    const url=new URL(daemon);
    if(url.protocol!=='unix:'||url.host||url.username||url.password||url.search||url.hash||!url.pathname.startsWith('/')||url.pathname.length<2||url.href!==daemon||daemon!=='unix://'+url.pathname)return pending(phase,code);

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
