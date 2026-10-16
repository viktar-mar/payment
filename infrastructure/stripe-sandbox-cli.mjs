import {execFile,spawn} from 'node:child_process';
import {lstat,realpath,readdir,open} from 'node:fs/promises';
import {constants} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const ACCOUNT='acct_1UPiMpELswAd8tpR',VERSION='2026-09-30.endive';
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const EXHAUSTED=new Set(['2f3f49b1-df07-41b1-b978-bc884b390813','6f684b69-32e6-429f-a34c-e4f69ba2ce18']);
const PREFIX=Object.freeze(['-p','(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))','/opt/homebrew/bin/stripe']);
const EVENTS='payment_intent.processing,payment_intent.requires_action,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,refund.created,refund.updated,refund.failed';
function executeAwaited(execute,file,args,options) {
  return new Promise((resolveExecution,rejectExecution)=>{
    let callbackDone=false,closed=false,failed=false,result;
    const finish=()=>{if(callbackDone&&closed) {if(failed)rejectExecution(null);else resolveExecution(result);}};
    const child=execute(file,args,options,(error,stdout,stderr)=>{
      failed=Boolean(error);result={stdout,stderr};callbackDone=true;finish();
    });
    // execFile's AbortError callback can precede process termination. Await close too.
    child.once('close',()=>{closed=true;finish();});
  });
}
const fail=()=>{throw null;};
const fixed=code=>new Error('stripe_sandbox_cli_'+code);
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);

/** No entrypoint or implicit I/O: only fixed, isolated, read-only CLI capabilities. */
export async function createSandboxCliAdapter(options,dependencies={}) {
  let enabled=false;
  try {const optIn=options?.env;enabled=optIn?.PAYMENT_STRIPE_SANDBOX_ENABLED==='true'&&optIn?.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS==='true';} catch {}
  if(!enabled)throw new Error('stripe_sandbox_cli_OPT_IN_REQUIRED');
  let runId,cliDirectory,backendPort,persistSecret,verifyTarget,io;
  try {
    const env=options.env;
    if(![undefined,''].includes(env.DEBUG)||![undefined,''].includes(env.NODE_DEBUG)||![undefined,'','0'].includes(env.PWDEBUG))fail();
    runId=options.runId;cliDirectory=options.cliDirectory;backendPort=options.backendPort;
    const binding=options.approvedBinding;
    if(typeof runId!=='string'||!UUID.test(runId)||EXHAUSTED.has(runId)||cliDirectory!==ROOT+'/test-results/stripe-sandbox/'+runId+'/cli'
      ||!Number.isInteger(backendPort)||backendPort<1024||backendPort>65535||options.accountKey!=='stripe-sandbox-default'
      ||binding?.externalAccountId!==ACCOUNT||binding?.apiVersion!==VERSION)fail();
    persistSecret=options.persistWebhookSecret;verifyTarget=options.verifyForwardTarget;
    if(typeof persistSecret!=='function'||typeof verifyTarget!=='function')fail();
    const rawExecute=dependencies.execFile??execFile;
    io=Object.freeze({execute:dependencies.execute??((...invocation)=>executeAwaited(rawExecute,...invocation)),spawn:dependencies.spawn??spawn,lstat:dependencies.lstat??lstat,
      realpath:dependencies.realpath??realpath,readdir:dependencies.readdir??readdir,open:dependencies.open??open,
      setTimeout:dependencies.setTimeout??setTimeout,clearTimeout:dependencies.clearTimeout??clearTimeout});
  } catch {throw fixed('CONFIGURATION');}
  const env=Object.freeze({PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:cliDirectory+'/home',XDG_CONFIG_HOME:cliDirectory+'/config',NO_COLOR:'1'});
  const args=Object.freeze([...PREFIX,'--config',cliDirectory+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error']);
  let busy=false,owner=null,uncertain=false,originalSecret=null,latched=null,latchedOwner=null;

  function check(context) {if(context.signal.aborted||Date.now()>=context.deadline)fail();}
  async function operation(code,limits,body) {
    if(busy)throw fixed('BUSY');
    busy=true;let timer,external,onAbort;
    const controller=new AbortController();
    try {
      const deadline=limits?.deadline;external=limits?.signal;
      if(!Number.isFinite(deadline)||deadline<=Date.now()||(external!==undefined&&!(external instanceof AbortSignal)))fail();
      onAbort=()=>controller.abort();external?.addEventListener('abort',onAbort,{once:true});
      if(external?.aborted)controller.abort();
      timer=io.setTimeout(onAbort,Math.min(deadline-Date.now(),2147483647));
      const context={deadline,signal:controller.signal};check(context);
      return await body(context);
    } catch {throw fixed(code);}
    finally {try {io.clearTimeout(timer);external?.removeEventListener('abort',onAbort);} catch {} busy=false;}
  }
  async function privateFile(path) {
    const valid=s=>s.isFile()&&!s.isSymbolicLink()&&s.uid===process.getuid()&&(s.mode&0o7777)===0o600&&s.nlink===1&&Number.isInteger(s.size)&&s.size>=0&&s.size<=65536;
    if(!valid(await io.lstat(path))||await io.realpath(path)!==path)fail();
    const file=await io.open(path,constants.O_RDONLY|constants.O_NOFOLLOW);
    try {if(!valid(await file.stat()))fail();} finally {await file.close();}
  }
  async function privateTree(context) {
    for(const path of [cliDirectory,cliDirectory+'/home',cliDirectory+'/config',cliDirectory+'/config/stripe']) {
      check(context);const s=await io.lstat(path);
      if(!s.isDirectory()||s.isSymbolicLink()||s.uid!==process.getuid()||(s.mode&0o7777)!==0o700||await io.realpath(path)!==path)fail();
    }
    await privateFile(cliDirectory+'/config/stripe/config.toml');
    // Directory enumeration distinguishes absent credentials without inspecting thrown errors.
    const names=await io.readdir(cliDirectory+'/config/stripe');
    if(names.includes('credentials.json'))await privateFile(cliDirectory+'/config/stripe/credentials.json');
    check(context);
  }
  async function command(commandArgs,context) {
    await privateTree(context);check(context);
    const result=await io.execute('/usr/bin/sandbox-exec',[...args,...commandArgs],{cwd:ROOT,env,shell:false,stdio:['ignore','pipe','pipe'],
      timeout:Math.min(20000,Math.max(1,context.deadline-Date.now())),maxBuffer:65536,signal:context.signal});
    check(context);
    if(typeof result.stdout!=='string'||typeof result.stderr!=='string'||Buffer.byteLength(result.stdout)+Buffer.byteLength(result.stderr)>65536)fail();
    return result;
  }
  function parseBody(result,withHeaders=false) {
    const json=withHeaders?result.stdout.split(/\r?\n/).filter(line=>!line.startsWith('< ')&&!line.startsWith('> ')).join('\n'):result.stdout;
    const body=JSON.parse(json);if(!object(body))fail();return body;
  }
  async function binding(context) {
    const version=await command(['--version'],context);
    if(!['stripe version 1.52.0','stripe version 1.52.0\n'].includes(version.stdout)||version.stderr!=='')fail();
    const identity=parseBody(await command(['whoami','--format','json'],context));
    if(identity.account_id!==ACCOUNT||identity.mode!=='test'||!Array.isArray(identity.authorized_accounts)||identity.authorized_accounts.length!==1)fail();
    const account=identity.authorized_accounts[0];
    if(!object(account)||account.id!==ACCOUNT||!Array.isArray(account.modes)||!account.modes.includes('test')||account.modes.includes('live'))fail();
    const result=await command(['get','/v1/balance','--show-headers'],context),body=parseBody(result,true);
    const lines=(result.stdout+'\n'+result.stderr).split(/\r?\n/),statuses=[],versions=[];
    for(const line of lines) {
      if(/^<\s*HTTP/i.test(line)) {const match=/^< HTTP(?:\/1\.1|\/2)? 200(?: [A-Za-z ]+)?$/.exec(line);if(!match)fail();statuses.push(line);}
      if(/^<\s*stripe-version\b/i.test(line)) {const match=/^< stripe-version: ([^\s]+)$/i.exec(line);if(!match)fail();versions.push(match[1]);}
    }
    if(statuses.length!==1||versions.length!==1||versions[0]!==VERSION||body.object!=='balance'||body.livemode!==false)fail();
    return true;
  }
  function controlled(promise,context) {
    check(context);
    return new Promise((resolveWait,rejectWait)=>{
      const abort=()=>rejectWait(null);
      context.signal.addEventListener('abort',abort,{once:true});
      Promise.resolve(promise).then(value=>{context.signal.removeEventListener('abort',abort);resolveWait(value);},()=>{context.signal.removeEventListener('abort',abort);rejectWait(null);});
      if(context.signal.aborted)abort();
    });
  }
  async function waitExit(record,ms) {
    if(record.exited)return true;
    let timer;
    try {return await Promise.race([record.termination.then(()=>true),new Promise(resolveWait=>{timer=io.setTimeout(()=>resolveWait(false),ms);})]);}
    finally {io.clearTimeout(timer);}
  }
  async function terminate(record,budget=10000) {
    if(record.exited)return true;
    // The process object and positive PID are captured once; no PID-based/global kill.
    if(owner!==record||record.child.pid!==record.pid) {uncertain=true;return false;}
    try {record.child.kill('SIGINT');} catch {}
    const first=Math.max(1,Math.floor(budget/2));
    if(await waitExit(record,first))return true;
    if(owner!==record||record.child.pid!==record.pid) {uncertain=true;return false;}
    try {record.child.kill('SIGTERM');} catch {}
    if(await waitExit(record,Math.max(1,budget-first)))return true;
    uncertain=true;return false;
  }
  function listen(context) {
    const child=io.spawn('/usr/bin/sandbox-exec',[...args,'listen','--events',EVENTS,'--events-from','@self','--forward-to',
      'http://127.0.0.1:'+backendPort+'/api/v1/provider-webhooks/stripe/stripe-sandbox-default'],{cwd:ROOT,env,shell:false,stdio:['ignore','pipe','pipe']});
    const pid=child.pid;
    if(!Number.isInteger(pid)||pid<=0) {uncertain=true;child.on('error',()=>{});fail();}
    let resolveReady,rejectReady,resolveExit;
    const record={child,pid,exited:false,ready:false,valid:true,invalidated:false,stopping:false,explicitStop:false,persisting:false,persisted:false,bytes:0,buffers:['','']};
    record.readiness=new Promise((resolveWait,rejectWait)=>{resolveReady=resolveWait;rejectReady=rejectWait;});
    record.readiness.catch(()=>{});
    record.termination=new Promise(resolveWait=>{resolveExit=resolveWait;});
    owner=record;
    const invalidate=()=>{record.valid=false;record.invalidated=true;rejectReady(null);};
    child.on('exit',()=>{record.exited=true;record.valid=false;if(!record.stopping)record.invalidated=true;rejectReady(null);resolveExit();});
    child.on('error',invalidate);
    function line(raw) {
      const text=raw.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'').replace(/\r$/,'');
      if(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(text)) {invalidate();return;}
      if(!/Ready!|webhook signing secret|whsec_/.test(text))return;
      const match=/^Ready! You are using Stripe API Version \[2026-09-30\.endive\]\. Your webhook signing secret is (whsec_[A-Za-z0-9_]{1,200}) \(\^C to quit\)$/.exec(text);
      if(!match||!record.valid||record.exited||(originalSecret!==null&&match[1]!==originalSecret)) {invalidate();return;}
      if(record.persisting||record.persisted)return;
      originalSecret=match[1];record.persisting=true;
      // Await durable storage before releasing readiness. No reconnect callback retry.
      Promise.resolve().then(()=>{if(!record.valid||record.exited)fail();return persistSecret(originalSecret);}).then(result=>{
        record.persisting=false;
        if(result!==true||!record.valid||record.exited||context.signal.aborted) {uncertain=true;invalidate();return;}
        record.persisted=true;record.ready=true;resolveReady(true);
      },()=>{record.persisting=false;uncertain=true;invalidate();});
    }
    function output(index,chunk) {
      try {
        if(!record.ready) {record.bytes+=Buffer.byteLength(chunk);if(record.bytes>65536) {invalidate();return;}}
        // Decode strings in streams, preserving split UTF-8 sequences as well as lines.
        record.buffers[index]+=chunk;
        let end;
        while((end=record.buffers[index].indexOf('\n'))!==-1) {
          const raw=record.buffers[index].slice(0,end);record.buffers[index]=record.buffers[index].slice(end+1);
          if(Buffer.byteLength(raw)>8192) {record.buffers[index]='';invalidate();return;}
          line(raw);
        }
        if(Buffer.byteLength(record.buffers[index])>8192) {record.buffers[index]='';invalidate();}
      } catch {invalidate();}
    }
    child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');
    child.stdout.on('error',invalidate);child.stderr.on('error',invalidate);
    child.stdout.on('data',chunk=>output(0,chunk));child.stderr.on('data',chunk=>output(1,chunk));
    return record;
  }
  async function startListener(context) {
    if(uncertain||latched!==null||(owner!==null&&(!owner.exited||owner.invalidated)))fail();
    if(await controlled(Promise.resolve().then(()=>verifyTarget()),context)!==true)fail();
    await binding(context);await privateTree(context);check(context);
    let record;
    try {
      record=listen(context);
      await controlled(record.readiness,context);check(context);
      if(!record.valid||record.exited||!record.ready)fail();
      return true;
    } catch {
      // Setup can fail after spawn; cleanup still belongs to the recorded child.
      record??=owner;
      if(record===null)fail();
      record.valid=false;
      if(record.persisting)uncertain=true;
      await terminate(record);fail();
    }
  }
  async function stopListener(context) {
    if(uncertain)fail();
    if(owner===null)return true;
    if(owner.explicitStop&&owner.exited)return true;
    check(context);
    const record=owner;
    record.stopping=true;
    if(!await terminate(record,Math.min(10000,Math.max(2,context.deadline-Date.now()))))fail();
    check(context);record.explicitStop=true;
    return true;
  }
  async function bindMissedIntent(original) {
    if(busy)throw fixed('BUSY');
    busy=true;
    try {
      if(uncertain||latched!==null||owner===null||!owner.ready||!owner.valid||owner.exited)fail();
      const fields=['paymentId','siteId','operationId','intentId','amountMinor','currency','source','externalAccountId'];
      if(!object(original)||Reflect.ownKeys(original).some(key=>typeof key!=='string')||Object.getOwnPropertyNames(original).sort().join(',')!==[...fields].sort().join(','))fail();
      const snapshot=Object.fromEntries(fields.map(key=>[key,original[key]]));
      if(![snapshot.paymentId,snapshot.siteId,snapshot.operationId].every(value=>typeof value==='string'&&UUID.test(value))
        ||typeof snapshot.intentId!=='string'||!/^pi_[A-Za-z0-9_]{1,200}$/.test(snapshot.intentId)||snapshot.amountMinor!==2000
        ||snapshot.currency!=='EUR'||snapshot.source!=='CATALOG'||snapshot.externalAccountId!==ACCOUNT)fail();
      latched=Object.freeze(snapshot);latchedOwner=owner;return true;
    } catch {throw fixed('PROOF');} finally {busy=false;}
  }
  async function inspectIntent(context) {
    if(uncertain||latched===null||owner!==latchedOwner||!owner.ready||owner.invalidated||!owner.explicitStop||!owner.exited)fail();
    await binding(context);
    const p=parseBody(await command(['get','/v1/payment_intents/'+latched.intentId,'--expand','latest_charge'],context));
    if(p.id!==latched.intentId||p.object!=='payment_intent'||p.livemode!==false||p.status!=='succeeded'||p.amount!==2000||p.amount_received!==2000
      ||p.currency!=='eur'||p.capture_method!=='automatic'||!Array.isArray(p.payment_method_types)||p.payment_method_types.length!==1||p.payment_method_types[0]!=='card'
      ||!object(p.metadata)||p.metadata.payment_id!==latched.paymentId||p.metadata.site_id!==latched.siteId||p.metadata.source!==latched.source)fail();
    const c=p.latest_charge;
    if(!object(c)||typeof c.id!=='string'||!/^ch_[A-Za-z0-9_]{1,200}$/.test(c.id)||c.object!=='charge'||c.livemode!==false||c.payment_intent!==latched.intentId
      ||c.status!=='succeeded'||c.paid!==true||c.captured!==true||c.amount!==2000||c.amount_captured!==2000||c.currency!=='eur'||c.payment_method_details?.type!=='card')fail();
    return Object.freeze({captured:true});
  }
  return Object.freeze({
    validateBinding:limits=>operation('BINDING',limits,binding),
    startListener:limits=>operation('LISTENER',limits,startListener),
    stopListener:limits=>operation('STOP',limits,stopListener),
    bindMissedIntent,
    inspectIntent:limits=>operation('INTENT',limits,inspectIntent),
    loginInvocation:limits=>operation('LOGIN',limits,async context=>{
      await privateTree(context);
      return Object.freeze({file:'/usr/bin/sandbox-exec',args:Object.freeze([...args,'login']),options:Object.freeze({cwd:ROOT,env,shell:false,
        stdio:Object.freeze(['pipe','pipe','pipe']),timeout:180000,maxBuffer:65536})});
    }),
  });
}
