import {execFile} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {formatSandboxAuthorization} from './stripe-sandbox-acceptance.mjs';
const ROOT=fileURLToPath(new URL('..',import.meta.url)).replace(/\/$/,'');
const POLICY='(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))';
const diagnostics=new WeakMap();
function authError(stage,reason){const error=new Error('stripe_manual_payment_AUTHORIZATION_PENDING');diagnostics.set(error,{stage,reason});return error;}
const fail=(reason='DESCRIPTOR_INVALID')=>{throw authError('UNKNOWN',reason);};
/** Only this module's finite classifications can supply diagnostic data. */
export function formatManualPaymentAuthDiagnostic(error){
  const {stage,reason}=diagnostics.get(error)??{stage:'UNKNOWN',reason:'INTERNAL'};
  return JSON.stringify({tag:'stripe_manual_payment_auth_diagnostic',stage,reason})+'\n';
}
function data(value,keys){
  if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))fail();
  const d=Object.getOwnPropertyDescriptors(value);if(Reflect.ownKeys(d).length!==keys.length)fail();
  const r={};for(const key of keys){if(!d[key]?.enumerable||!Object.hasOwn(d[key],'value'))fail();r[key]=d[key].value;}return r;
}
function fixedArray(value,expected){
  if(!Array.isArray(value)||Object.getPrototypeOf(value)!==Array.prototype||!Object.isFrozen(value))return false;
  const d=Object.getOwnPropertyDescriptors(value);return Reflect.ownKeys(d).length===expected.length+1&&d.length?.value===expected.length
    &&expected.every((item,index)=>d[index]?.enumerable&&Object.hasOwn(d[index],'value')&&d[index].value===item);
}
function descriptor(value,runId,complete){
  const v=data(value,['file','args','options']),o=data(v.options,['cwd','env','shell','stdio','timeout','maxBuffer']);
  const directory=ROOT+'/test-results/stripe-sandbox/'+runId+'/cli',env={PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:directory+'/home',XDG_CONFIG_HOME:directory+'/config',NO_COLOR:'1'};
  const e=data(o.env,Object.keys(env)),args=['-p',POLICY,'/opt/homebrew/bin/stripe','--config',directory+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error','login',...(complete?['--complete-device']:[])];
  if(![value,v.options,o.env].every(Object.isFrozen)||v.file!=='/usr/bin/sandbox-exec'||!fixedArray(v.args,args)
    ||o.cwd!==ROOT||o.shell!==false||!fixedArray(o.stdio,['pipe','pipe','pipe'])||o.timeout!==(complete?600000:180000)||o.maxBuffer!==65536||Object.keys(env).some(key=>e[key]!==env[key]))fail();
  return {file:v.file,args,options:{...o,env:e,stdio:['pipe','pipe','pipe']}};
}
function authorization(raw){
  const fail=()=>{throw authError('HANDOFF','HANDOFF_INVALID');};
  if(typeof raw!=='string'||Buffer.byteLength(raw)>65536)fail();
  const quoted=/"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/y,value=Object.create(null);let cursor=0;
  const ws=()=>{while(/[ \t\r\n]/.test(raw[cursor]??'!'))cursor++;};
  const punctuation=char=>{ws();if(raw[cursor++]!==char)fail();};
  const string=()=>{ws();quoted.lastIndex=cursor;const match=quoted.exec(raw);if(!match)fail();cursor=quoted.lastIndex;return JSON.parse(match[0]);};
  punctuation('{');for(let i=0;i<3;i++){if(i)punctuation(',');const key=string();if(!['browser_url','verification_code','next_step'].includes(key)||Object.hasOwn(value,key))fail();punctuation(':');value[key]=string();}punctuation('}');ws();
  if(cursor!==raw.length||value.next_step!=='stripe login --complete-device')fail();
  const line=formatSandboxAuthorization({tag:'stripe_sandbox_authorization',browser_url:value.browser_url,verification_code:value.verification_code});if(!line)fail();return line;
}
/** Executes only the two fixed isolated descriptors. Callback and actual close are independent proofs. */
export async function authorizeManualPaymentCli({cli,runId},limits,onAuthorization,deps={}){
  let stage='LOGIN';
  try{
  limits=Object.freeze(data(limits,Object.hasOwn(limits??{},'signal')?['deadline','signal']:['deadline']));
  if(typeof runId!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(runId)||!Number.isSafeInteger(limits.deadline)
    ||limits.signal!==undefined&&!(limits.signal instanceof AbortSignal)||typeof onAuthorization!=='function')fail();
  const now=deps.now??Date.now,setTimer=deps.setTimeout??setTimeout,clearTimer=deps.clearTimeout??clearTimeout,execute=deps.execFile??execFile;
  const check=()=>{if(limits.signal?.aborted)throw authError(stage,'ABORTED');if(now()>=limits.deadline)throw authError(stage,'TIMEOUT');};
  async function bounded(body){check();let timer,abort;try{return await Promise.race([Promise.resolve().then(()=>{check();return body();}).then(value=>{check();return value;}),new Promise((_,reject)=>{abort=()=>reject(authError(stage,limits.signal?.aborted?'ABORTED':'TIMEOUT'));limits.signal?.addEventListener('abort',abort,{once:true});timer=setTimer(abort,Math.max(1,limits.deadline-now()));})]);}finally{clearTimer(timer);limits.signal?.removeEventListener('abort',abort);}}
  async function run(v){
    check();return new Promise((accept,reject)=>{
      let child,cap,grace,callback=false,closed=false,failed=false,done=false,stdout,stderr,firstReason;
      const mark=reason=>{failed=true;firstReason??=reason;};
      const controller=new AbortController();
      const dispose=()=>{child?.removeListener('close',close);child?.removeListener('error',error);};
      const finish=()=>{if(done||!callback||!closed)return;settle(failed);};
      const settle=bad=>{if(done)return;done=true;clearTimer(cap);clearTimer(grace);limits.signal?.removeEventListener('abort',abort);if(closed)dispose();if(bad)reject(authError(stage,firstReason??'PROCESS_FAILED'));else accept({stdout,stderr});};
      const waitClose=()=>{if(grace!==undefined||done)return;grace=setTimer(()=>{mark('CHILD_CLOSE_UNCONFIRMED');settle(true);controller.abort();},Math.min(10000,Math.max(1,limits.deadline+10000-now())));};
      const abort=()=>{if(done)return;mark(limits.signal?.aborted?'ABORTED':'TIMEOUT');waitClose();controller.abort();};
      const error=()=>{if(done)return;mark('PROCESS_FAILED');waitClose();};
      const close=(code,signal)=>{closed=true;if(done){dispose();return;}if(code!==0||signal)mark('PROCESS_FAILED');clearTimer(cap);finish();if(!callback)waitClose();};
      limits.signal?.addEventListener('abort',abort,{once:true});
      try{
        cap=setTimer(abort,Math.max(1,Math.min(v.options.timeout,limits.deadline-now())));
        child=execute(v.file,v.args,{...v.options,timeout:0,signal:controller.signal},(error,out,err)=>{if(done)return;callback=true;if(error)mark('PROCESS_FAILED');stdout=out;stderr=err;finish();if(!closed)waitClose();});
        child.once('close',close);child.on('error',error);if(limits.signal?.aborted)abort();
      }catch{mark('PROCESS_FAILED');settle(true);}
    });
  }
    const initial=descriptor(await bounded(()=>cli.loginInvocation(limits)),runId,false),result=await run(initial);check();
    if(result.stderr!==''||typeof result.stdout!=='string')fail('OUTPUT_INVALID');
    stage='HANDOFF';
    await bounded(async()=>{const line=authorization(result.stdout);try{return await onAuthorization(line);}catch{throw authError('HANDOFF','HANDOFF_FAILED');}});
    stage='COMPLETE';
    const complete=descriptor(await bounded(()=>cli.completeLoginInvocation(limits)),runId,true),completed=await run(complete);check();
    // Successful completion banners are not a stable CLI protocol. Discard
    // ordinary bounded text; neither it nor process exit proves account access.
    for(const text of [completed.stdout,completed.stderr])if(typeof text!=='string'||Buffer.byteLength(text)>65536)fail('OUTPUT_INVALID');
    if(Buffer.byteLength(completed.stdout)+Buffer.byteLength(completed.stderr)>65536)fail('OUTPUT_INVALID');
    for(const text of [completed.stdout,completed.stderr])if(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]/.test(text))fail('OUTPUT_UNSAFE');
    const warning='Warning: the system keyring is unavailable. Your credentials have been stored unencrypted in '+ROOT+'/test-results/stripe-sandbox/'+runId+'/cli/config/stripe/credentials.json';
    let removed=false;
    const scanned=completed.stdout.split('\n').filter(line=>{if(line!==warning)return true;if(removed)fail('OUTPUT_UNSAFE');removed=true;return false;}).join('\n');
    for(const text of [scanned,completed.stderr])if(/(?:[spr]k_(?:test|live)_|whsec_|client[_ -]?secret|(?:access|refresh|id|auth(?:entication|orization)?)[_ -]?token|\btoken\b|authorization\s*[:=]|bearer\s|(?:set-)?cookie\s*[:=]|api[_ -]?key|password|credential)/i.test(text))fail('OUTPUT_UNSAFE');
    return true;
  }catch(error){const owned=diagnostics.get(error);throw owned?authError(owned.stage==='UNKNOWN'?stage:owned.stage,owned.reason):authError('UNKNOWN','INTERNAL');}
}
