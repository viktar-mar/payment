import {constants} from 'node:fs';
import {open,mkdir,lstat,realpath,rename,unlink} from 'node:fs/promises';
import {dirname,join,resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const KEY=/^[a-z0-9][a-z0-9-]{2,62}$/;
const CASES=['success','decline','sca'];
const PHASES=['READY','PREPARING','CONFIRMING','RETRYING','SERVER_CONFIRMED'];
const fail=code=>{throw new Error('stripe_sandbox_'+code);};
const check=(value,code='CONFIGURATION')=>{if(!value)fail(code);};
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).sort().join(',')===[...keys].sort().join(',');
const safeCredential=v=>typeof v==='string'&&v.length>0&&v.length<=512&&!/[\r\n\x00-\x1f\x7f]|(?:sk|rk|pk)_live_/.test(v);
function guard(env) {
  check(env.PAYMENT_STRIPE_SANDBOX_ENABLED==='true'&&env.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS==='true','OPT_IN_REQUIRED');
  check(!env.DEBUG&&!env.NODE_DEBUG&&(!env.PWDEBUG||env.PWDEBUG==='0'),'DEBUG_DISABLED_REQUIRED');
}
function endpoint(value) {
  try {const u=new URL(value);return u.protocol==='http:'&&u.hostname==='127.0.0.1'&&u.port&&u.origin===value&&!u.username&&!u.password&&!u.search&&!u.hash;}catch{return false;}
}
function validate(s,runId) {
  check(exact(s,['version','runId','owners','endpoints','account','site','price','cases','refunds'])&&s.version===1&&UUID.test(runId)&&s.runId===runId,'STATE');
  check(exact(s.owners,['project','backend','demo','receiver'])&&/^stripe-[a-z0-9-]{3,50}$/.test(s.owners.project)
    &&['backend','demo','receiver'].every(k=>/^[a-f0-9]{64}$/.test(s.owners[k])),'STATE');
  check(exact(s.endpoints,['backend','demo','receiver'])&&Object.values(s.endpoints).every(endpoint),'STATE');
  check(exact(s.account,['id','key','externalId'])&&UUID.test(s.account.id)&&KEY.test(s.account.key)&&/^acct_[A-Za-z0-9]+$/.test(s.account.externalId),'STATE');
  check(['site','price'].every(k=>exact(s[k],['id','key'])&&UUID.test(s[k].id)&&KEY.test(s[k].key)),'STATE');
  check(exact(s.cases,CASES)&&CASES.every(k=>exact(s.cases[k],['paymentId','checkoutId','phase'])&&UUID.test(s.cases[k].paymentId)
    &&UUID.test(s.cases[k].checkoutId)&&PHASES.includes(s.cases[k].phase)),'STATE');
  check(new Set(CASES.map(k=>s.cases[k].paymentId)).size===3&&new Set(CASES.map(k=>s.cases[k].checkoutId)).size===3,'STATE');
  check(Array.isArray(s.refunds)&&s.refunds.length===2&&s.refunds.every((r,i)=>exact(r,['amount','key','phase','id'])&&r.amount===[700,1300][i]
    &&UUID.test(r.key)&&['READY','DISPATCHING','ACKNOWLEDGED','CONFIRMED'].includes(r.phase)&&(r.id===null||UUID.test(r.id))
    &&(r.phase!=='READY'||r.id===null)&&(!['ACKNOWLEDGED','CONFIRMED'].includes(r.phase)||r.id!==null))&&s.refunds[0].key!==s.refunds[1].key,'STATE');
  return s;
}
async function directory(root,runId,create=false) {
  check(UUID.test(runId),'STATE');const base=await realpath(root);
  let current=base;
  for(const part of ['test-results','stripe-sandbox',runId]) {
    current=join(current,part);if(create)await mkdir(current,{mode:0o700}).catch(e=>{if(e.code!=='EEXIST')throw e;});
    const info=await lstat(current);check(info.isDirectory()&&!info.isSymbolicLink()&&info.uid===process.getuid(),'STATE');
    if(part===runId)check((info.mode&0o077)===0,'STATE');
  }
  return current;
}
async function readState(path,runId) {
  const handle=await open(path,constants.O_RDONLY|constants.O_NOFOLLOW);
  try {const info=await handle.stat();check(info.isFile()&&info.uid===process.getuid()&&(info.mode&0o777)===0o600&&info.size<=16384,'STATE');
    return validate(JSON.parse(await handle.readFile('utf8')),runId);
  }finally{await handle.close();}
}
async function durable(dir,state,initial=false) {
  validate(state,state.runId);const text=JSON.stringify(state);check(Buffer.byteLength(text)<=16384,'STATE');
  const path=join(dir,'state.json');
  if(!initial)await readState(path,state.runId);
  const temp=join(dir,'.state-'+randomUUID()+'.tmp'),handle=await open(temp,'wx',0o600);
  try {await handle.writeFile(text);await handle.sync();}finally{await handle.close();}
  const directoryHandle=await open(dir,'r');
  try {await directoryHandle.sync();await rename(temp,path);await directoryHandle.sync();}finally{await directoryHandle.close();}
}
/** Explicit local initialization only, after separately authorized known-success provisioning. Never creates platform resources. */
export async function initializeStripeSandboxState(metadata,options={}) {
  const env=options.env??process.env;guard(env);
  let lock,dir;
  try {
    check(exact(metadata,['runId','owners','endpoints','account','site','price','cases']),'STATE');
    const state={...structuredClone(metadata),version:1,cases:Object.fromEntries(CASES.map(k=>[k,{...metadata.cases[k],phase:'READY'}])),
      refunds:[700,1300].map(amount=>({amount,key:randomUUID(),phase:'READY',id:null}))};validate(state,state.runId);
    dir=await directory(options.rootDirectory??ROOT,state.runId,true);lock=await open(join(dir,'lock'),'wx',0o600);
    try{await lstat(join(dir,'state.json'));fail('STATE');}catch(e){if(e.code!=='ENOENT')throw e;}
    await durable(dir,state,true);
  }catch{fail('STATE');}finally{if(lock){await lock.close();await unlink(join(dir,'lock'));}}
}
export async function verifyStripeSandboxOwnership(state,env=process.env,execute=promisify(execFile)) {
  try {
  const childEnv=Object.fromEntries(['PATH','HOME','USER','LOGNAME','LANG','LC_ALL','TMPDIR'].filter(k=>env[k]).map(k=>[k,env[k]]));
  const options={timeout:10000,maxBuffer:16384,env:childEnv};
  let daemon=env.DOCKER_HOST;
  if(env.DOCKER_CONTEXT||!daemon) {
    const context=env.DOCKER_CONTEXT??(await execute('docker',['context','show'],options)).stdout.trim();
    check(typeof context==='string'&&/^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(context),'OWNERSHIP');
    daemon=JSON.parse((await execute('docker',['context','inspect',context,'--format','{{json .Endpoints.docker.Host}}'],options)).stdout.trim());
  }
  check(typeof daemon==='string'&&daemon.length<=1024&&!/[\s\x00-\x1f\x7f]/.test(daemon),'OWNERSHIP');
  const socket=new URL(daemon);
  check(socket.protocol==='unix:'&&!socket.host&&!socket.username&&!socket.password&&!socket.search&&!socket.hash
    &&socket.pathname.startsWith('/')&&socket.pathname.length>1&&daemon==='unix://'+socket.pathname,'OWNERSHIP');
  for(const [name,service,port] of [['backend','backend',8080],['demo','demo-site',8080],['receiver','callback-receiver',8080]]) {
    const format='{{json .Id}}|{{json .State.Running}}|{{json (index .Config.Labels "com.docker.compose.project")}}|{{json (index .Config.Labels "com.docker.compose.service")}}|{{json .NetworkSettings.Ports}}';
    const {stdout}=await execute('docker',['--host',daemon,'inspect','--format',format,state.owners[name]],options);
    // Inspect only ownership/ports. In particular, never retrieve container environment or Stripe keys.
    const [id,running,project,actualService,ports]=stdout.trim().split('|').map(v=>JSON.parse(v)),bindings=ports?.[port+'/tcp'];
    check(id===state.owners[name]&&running===true&&project===state.owners.project&&actualService===service
      &&Array.isArray(bindings)&&bindings.some(b=>b.HostIp==='127.0.0.1'&&b.HostPort===new URL(state.endpoints[name]).port),'OWNERSHIP');
  }
  return true;
  }catch{fail('OWNERSHIP');}
}
/** Shared exclusive command session. Unit tests inject only transport/ownership boundaries. */
export async function openStripeSandboxFixture(options={}) {
  const env=options.env??process.env;guard(env);let lock,dir;
  try {
    dir=await directory(options.rootDirectory??ROOT,env.PAYMENT_STRIPE_SANDBOX_RUN_ID);
    lock=await open(join(dir,'lock'),'wx',0o600);
    const s=await readState(join(dir,'state.json'),env.PAYMENT_STRIPE_SANDBOX_RUN_ID);
    check(await (options.verifyOwnership??(state=>verifyStripeSandboxOwnership(state,env)))(structuredClone(s))===true,'OWNERSHIP');
    check(safeCredential(env.PAYMENT_SANDBOX_ADMIN_USERNAME)&&safeCredential(env.PAYMENT_SANDBOX_ADMIN_PASSWORD),'CREDENTIALS');
    let cookie='',csrf='',closed=false;
    const attempts=options.pollAttempts??30,delay=options.pollDelayMs??250,requestTimeout=options.requestTimeoutMs??10000;
    check(Number.isInteger(attempts)&&attempts>=1&&attempts<=30&&Number.isInteger(delay)&&delay>=0&&delay<=250
      &&Number.isInteger(requestTimeout)&&requestTimeout>=50&&requestTimeout<=10000);
    const fetcher=options.fetch??fetch;
    async function request(path,{method='GET',body,key,target=s.endpoints.backend,headers={},expected=200,deadline=Date.now()+requestTimeout}={}) {
      try {
        check(deadline>Date.now(),'HTTP');
        const response=await fetcher(target+path,{method,redirect:'error',signal:AbortSignal.timeout(Math.max(1,Math.min(requestTimeout,deadline-Date.now()))),
          headers:{Accept:'application/json',...(cookie&&target===s.endpoints.backend&&(path.startsWith('/api/admin/')||path.startsWith('/api/v1/admin/'))?{Cookie:cookie}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{}),
            ...(key?{'Idempotency-Key':key,'X-XSRF-TOKEN':csrf}:{}),...headers},...(body!==undefined?{body:JSON.stringify(body)}:{})});
        if(response.status!==expected){await response.body?.cancel();fail('HTTP');}
        let size=0;const chunks=[];
        try {for await(const chunk of response.body??[]){size+=chunk.length;if(size>65536)fail('HTTP');chunks.push(chunk);}}
        catch{await response.body?.cancel().catch(()=>{});fail('HTTP');}
        const bytes=Buffer.concat(chunks);return {value:bytes.length?JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes)):null,response};
      }catch{fail('HTTP');}
    }
    const login=await request('/api/admin/v1/auth/sessions',{method:'POST',body:{username:env.PAYMENT_SANDBOX_ADMIN_USERNAME,password:env.PAYMENT_SANDBOX_ADMIN_PASSWORD},expected:204});
    const cookies=login.response.headers.getSetCookie(),session=cookies.find(c=>c.startsWith('PAYMENT_ADMIN_SESSION='))?.split(';')[0],xsrf=cookies.find(c=>c.startsWith('XSRF-TOKEN='))?.split(';')[0];
    check(session&&xsrf,'AUTHENTICATION');cookie=session+'; '+xsrf;csrf=xsrf.slice(11);
    const read=async(path,opts)=>(await request(path,opts)).value;
    const accounts=await read('/api/admin/v1/payment-accounts'),account=Array.isArray(accounts)&&accounts.find(a=>a.id===s.account.id);
    check(account&&account.key===s.account.key&&account.provider==='STRIPE'&&account.mode==='SANDBOX'&&account.status==='ACTIVE'&&account.external_account_id===s.account.externalId,'BINDING');
    const sites=await read('/api/admin/v1/sites'),site=Array.isArray(sites)&&sites.find(v=>v.id===s.site.id);
    check(site&&site.key===s.site.key&&site.status==='ACTIVE'&&site.payment_account_id===s.account.id&&site.allowed_origins?.includes(s.endpoints.demo),'BINDING');
    const catalog=await read(`/api/admin/v1/sites/${s.site.id}/catalog`),price=catalog?.prices?.find(p=>p.id===s.price.id);
    check(price&&price.price_key===s.price.key&&price.type==='ONE_TIME'&&price.status==='PUBLISHED'&&price.amount_minor===2000&&price.currency==='EUR','BINDING');
    async function detail(name,deadline) {
      check(CASES.includes(name));const d=await read('/api/v1/admin/payments/'+s.cases[name].paymentId,{deadline});const p=d?.payment;
      check(p&&p.payment_id===s.cases[name].paymentId&&p.site_id===s.site.id&&p.payment_account_id===s.account.id&&p.provider==='STRIPE'
        &&p.source==='CATALOG'&&p.price_id===s.price.id&&p.price_key===s.price.key&&p.amount_minor===2000&&p.currency==='EUR','BINDING');
      check(['events','refunds','outbox_events','deliveries','attempts'].every(k=>Array.isArray(d[k])&&d[k].length<=1000),'PROOF');return d;
    }
    async function eventProof(d,type,amount,reference,deadline) {
      const matches=d.events.filter(e=>e.event_type===type&&(reference===undefined||e.provider_object_id===reference));
      if(matches.length===0)return false;
      check(matches.length===1,'PROOF');const e=matches[0];check(UUID.test(e.id)&&e.amount_minor===amount&&e.currency==='EUR','PROOF');
      const callback=type==='PAYMENT_SUCCEEDED'?'payment.succeeded':'refund.succeeded',out=d.outbox_events.filter(o=>o.id===e.id&&o.callback_type===callback);
      if(out.length!==1||out[0].status!=='DELIVERED'||!d.deliveries.some(v=>v.outbox_event_id===e.id&&v.status==='SUCCEEDED'&&v.http_status>=200&&v.http_status<300))return false;
      const inspected=await read('/inspection',{target:s.endpoints.receiver,deadline});check(Array.isArray(inspected?.events),'PROOF');
      return inspected.events.filter(v=>v.event_id===e.id&&v.event_type===callback&&Number.isSafeInteger(v.count)&&v.count>=1).length===1;
    }
    async function poll(probe,code) {
      const deadline=Date.now()+45000;
      for(let n=0;n<attempts&&Date.now()<deadline;n++) {
        if(await probe(deadline))return true;
        if(n+1<attempts)await new Promise(resolve=>setTimeout(resolve,Math.min(delay,Math.max(0,deadline-Date.now()))));
      }
      fail(code);
    }
    async function paymentProof(name,deadline) {
      const d=await detail(name,deadline);
      return ['SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED'].includes(d.payment.status)&&await eventProof(d,'PAYMENT_SUCCEEDED',2000,undefined,deadline);
    }
    async function confirm(name) {
      await poll(budget=>paymentProof(name,budget),'PENDING_PAYMENT');s.cases[name].phase='SERVER_CONFIRMED';await durable(dir,s);return true;
    }
    async function beginBrowser(name) {
      check(CASES.includes(name));const c=s.cases[name];
      if(c.phase!=='READY') {if(await paymentProof(name)){await confirm(name);return false;}fail('PENDING_BROWSER');}
      const d=await detail(name);check(['CREATED','REQUIRES_PAYMENT_METHOD'].includes(d.payment.status)&&d.events.every(e=>e.event_type!=='PAYMENT_SUCCEEDED'),'PENDING_BROWSER');
      c.phase='PREPARING';await durable(dir,s);return true;
    }
    async function browserSession(name) {
      check(CASES.includes(name)&&s.cases[name].phase==='PREPARING','PENDING_BROWSER');
      const token=env['PAYMENT_SANDBOX_'+name.toUpperCase()+'_CHECKOUT_TOKEN'];check(typeof token==='string'&&/^[A-Za-z0-9_-]{43}$/.test(token),'CREDENTIALS');
      const c=s.cases[name],v=await read('/api/v1/public/checkouts/'+c.checkoutId,{headers:{Origin:s.endpoints.demo,Authorization:'Bearer '+token}});
      check(v.checkout_id===c.checkoutId&&v.payment_id===c.paymentId&&v.provider_type==='STRIPE'&&v.confirmation_mode==='CLIENT_PROVIDER'
        &&v.amount_minor===2000&&v.currency==='EUR'&&Number.isFinite(Date.parse(v.expires_at))&&Date.parse(v.expires_at)>Date.now(),'BINDING');
      const price=v.price;check(price&&price.price_id===s.price.id&&price.price_key===s.price.key&&price.amount_minor===2000&&price.currency==='EUR','BINDING');
      return {version:2,checkoutId:c.checkoutId,checkoutToken:token,expiresAt:v.expires_at,apiBaseUrl:s.endpoints.backend,siteKey:s.site.key,priceKey:s.price.key,
        providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId:c.checkoutId,paymentId:c.paymentId,amountMinor:2000,currency:'EUR',status:v.status,expiresAt:v.expires_at,
          price:{priceId:price.price_id,priceKey:price.price_key,version:price.version,amountMinor:2000,currency:'EUR',taxMode:price.tax_mode}}};
    }
    async function markPay(name,retry=false) {
      check(CASES.includes(name));const c=s.cases[name];check(c.phase===(retry?'CONFIRMING':'PREPARING'),'PENDING_BROWSER');
      if(retry)await poll(async budget=>{const d=await detail(name,budget);return d.payment.status==='REQUIRES_PAYMENT_METHOD'&&d.attempts.some(a=>a.status==='FAILED')
        &&new Set(d.attempts.map(a=>a.provider_intent_reference).filter(Boolean)).size===1;},'MANUAL_CHECK_DECLINE');
      c.phase=retry?'RETRYING':'CONFIRMING';await durable(dir,s);
    }
    async function refunds() {
      for(let i=0;i<2;i++) {
        const r=s.refunds[i];let d=await detail('success');
        const known=s.refunds.map(v=>v.id).filter(Boolean);check(d.refunds.every(v=>known.includes(v.id)),'PENDING_REFUND');
        if(r.phase==='READY') {
          check(d.payment.refunded_amount_minor===(i===0?0:700)&&d.refunds.length===i,'PROOF');
          r.phase='DISPATCHING';await durable(dir,s);
          const result=await read('/api/v1/admin/payments/'+s.cases.success.paymentId+'/refunds',{method:'POST',key:r.key,
            body:{amount_minor:r.amount,currency:'EUR',reason_code:'REQUESTED_BY_CUSTOMER'},expected:202});
          check(UUID.test(result?.refund_id),'PENDING_REFUND');r.id=result.refund_id;r.phase='ACKNOWLEDGED';await durable(dir,s);
        }
        if(!r.id)fail('PENDING_REFUND');
        await poll(async budget=>{
          d=await detail('success',budget);const ownedIds=s.refunds.map(v=>v.id).filter(Boolean);check(d.refunds.every(v=>ownedIds.includes(v.id)),'PROOF');
          const current=d.refunds.find(v=>v.id===r.id);if(!current)return false;
          check(current.amount_minor===r.amount&&current.currency==='EUR','PROOF');
          if(current.status!=='SUCCEEDED')return false;
          check(/^re_[A-Za-z0-9]+$/.test(current.provider_refund_reference),'PROOF');
          return await eventProof(d,'REFUND_SUCCEEDED',r.amount,current.provider_refund_reference,budget);
        },'PENDING_REFUND');
        r.phase='CONFIRMED';await durable(dir,s);
      }
      const d=await detail('success');check(d.payment.refunded_amount_minor===2000&&d.payment.status==='REFUNDED'
        &&d.refunds.length===2&&d.events.filter(e=>e.event_type==='REFUND_SUCCEEDED').length===2,'PROOF');return true;
    }
    return {identity:structuredClone(s),beginBrowser,browserSession,markPay,confirm,refunds,
      async close(){if(closed)return;closed=true;cookie='';csrf='';await lock.close();await unlink(join(dir,'lock'));}};
  }catch(error){if(lock){await lock.close();await unlink(join(dir,'lock'));}
    if(/^stripe_sandbox_[A-Z_]+$/.test(error?.message))throw new Error(error.message);fail('STATE');}
}
export async function runStripeSandboxSmoke(options={}) {
  const session=await openStripeSandboxFixture(options);
  try {for(const name of CASES)await session.confirm(name);await session.refunds();return {paymentConfirmed:true,refundConfirmed:true,callbackVerified:true};}
  catch(error){if(/^stripe_sandbox_[A-Z_]+$/.test(error?.message))throw new Error(error.message);fail('FAILED');}
  finally{await session.close();}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href) {
  try{console.log(JSON.stringify(await runStripeSandboxSmoke()));}
  catch(error){console.error(/^stripe_sandbox_[A-Z_]+$/.test(error?.message)?error.message:'stripe_sandbox_FAILED');process.exitCode=1;}
}
