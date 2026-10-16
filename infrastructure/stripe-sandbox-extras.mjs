import {constants} from 'node:fs';
import {lstat, realpath, open, rename, unlink} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PORTS = ['backend', 'demo', 'receiver', 'postgres', 'mailpitHttp', 'mailpitSmtp'];
const KINDS = ['anonymous', 'emailOtp'];
const ACCOUNT = 'acct_1UPiMpELswAd8tpR';
const ERRORS = new Set(['OPT_IN_REQUIRED', 'CONFIGURATION', 'BUSY', 'PENDING', 'UNSUPPORTED_OPERATION']);
const fixed = code => new Error('stripe_sandbox_extras_' + (ERRORS.has(code) ? code : 'PENDING'));
const fail = () => { throw null; };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const uuid = value => typeof value === 'string' && UUID.test(value);
const intent = value => typeof value === 'string' && /^pi_[A-Za-z0-9_]{1,200}$/.test(value);
const token = value => typeof value === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value)
  && Buffer.from(value, 'base64url').toString('base64url') === value;
const credential = value => typeof value === 'string' && value.length > 0 && value.length <= 1024 && !/[\x00-\x1f\x7f]/.test(value);
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const exact = (value, fields) => object(value) && Reflect.ownKeys(value).every(key => typeof key === 'string')
  && Object.getOwnPropertyNames(value).sort().join(',') === [...fields].sort().join(',');
function timestamp(value) {
  if (typeof value !== 'string' || value.length > 64) fail();
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|([+-])(\d{2}):(\d{2}))$/.exec(value);
  if (!match) fail();
  const [year,month,day,hour,minute,second] = match.slice(1,7).map(Number);
  const date = new Date(0);date.setUTCFullYear(year,month-1,day);
  if (month<1 || month>12 || day<1 || date.getUTCMonth()!==month-1 || date.getUTCDate()!==day
    || hour>23 || minute>59 || second>59 || Number(match[8]??0)>23 || Number(match[9]??0)>59) fail();
  const parsed = Date.parse(value);if(!Number.isFinite(parsed))fail();return parsed;
}
function freeze(value) {
  if (value && typeof value === 'object') { for (const child of Object.values(value)) freeze(child); Object.freeze(value); }
  return value;
}
const snapshot = value => freeze(JSON.parse(JSON.stringify(value)));

/** One-run acceptance evidence. Construction is inert; all authority stays in owned capabilities. */
export async function createSandboxAcceptanceExtras(options, dependencies = {}) {
  let enabled = false;
  try {
    const env = options?.env;
    enabled = env?.PAYMENT_STRIPE_SANDBOX_ENABLED === 'true' && env?.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS === 'true';
  } catch {}
  if (!enabled) throw fixed('OPT_IN_REQUIRED');
  let topology, binding, bootstrap, provisioner, cli, io;
  try {
    const env = options.env;
    if (![undefined, ''].includes(env.DEBUG) || ![undefined, ''].includes(env.NODE_DEBUG) || ![undefined, '', '0'].includes(env.PWDEBUG)) fail();
    const b = options.bootstrap, p = options.provisioner, c = options.cli;
    for (const name of ['resources', 'verify', 'withAdminCredentials']) if (typeof b?.[name] !== 'function') fail();
    for (const name of ['resources', 'withMissedCheckout']) if (typeof p?.[name] !== 'function') fail();
    for (const name of ['validateBinding', 'bindMissedIntent', 'stopListener', 'inspectIntent']) if (typeof c?.[name] !== 'function') fail();
    const r = b.resources();
    if (!uuid(r.runId) || ['2f3f49b1-df07-41b1-b978-bc884b390813', '6f684b69-32e6-429f-a34c-e4f69ba2ce18'].includes(r.runId)
      || typeof r.expectedSource !== 'string' || !/^[0-9a-f]{40}$/.test(r.expectedSource)
      || r.project !== 'stripe-' + r.runId || r.siteKey !== 's5a-' + r.runId.replaceAll('-', '')) fail();
    if (!exact(r.ports, PORTS)) fail();
    const ports = Object.fromEntries(PORTS.map(name => [name, r.ports[name]]));
    if (Object.values(ports).some(port => !Number.isInteger(port) || port < 1024 || port > 65535) || new Set(Object.values(ports)).size !== 6) fail();
    if (typeof r.daemon !== 'string' || /[\s\x00-\x1f\x7f%]/.test(r.daemon)) fail();
    const daemon = new URL(r.daemon);
    if (daemon.protocol !== 'unix:' || daemon.host || daemon.username || daemon.password || daemon.search || daemon.hash
      || daemon.pathname.length < 2 || daemon.href !== r.daemon || r.daemon !== 'unix://' + daemon.pathname) fail();
    const endpoints = Object.fromEntries(['backend', 'demo', 'receiver'].map(name => {
      const expected = 'http://127.0.0.1:' + ports[name];
      if (r.endpoints?.[name] !== expected) fail();
      return [name, expected];
    }));
    const owners = {project:r.project};
    for (const [name, service] of [['backend','backend'], ['demo','demo-site'], ['receiver','callback-receiver']]) {
      const id = r.containers?.[service]?.id;
      if (typeof id !== 'string' || !/^[0-9a-f]{64}$/.test(id)) fail();
      owners[name] = id;
    }
    if (new Set(Object.values(owners)).size !== 4) fail();
    topology = snapshot({runId:r.runId, expectedSource:r.expectedSource, daemon:r.daemon, project:r.project, siteKey:r.siteKey, ports, endpoints, owners});
    bootstrap = Object.freeze(Object.fromEntries(['resources','verify','withAdminCredentials'].map(name => [name, b[name].bind(b)])));
    provisioner = Object.freeze(Object.fromEntries(['resources','withMissedCheckout'].map(name => [name, p[name].bind(p)])));
    cli = Object.freeze(Object.fromEntries(['validateBinding','bindMissedIntent','stopListener','inspectIntent'].map(name => [name, c[name].bind(c)])));
    binding = provisionSnapshot(p.resources());
    liveSnapshot(r);
    io = Object.freeze({lstat:dependencies.lstat ?? lstat, realpath:dependencies.realpath ?? realpath, open:dependencies.open ?? open,
      rename:dependencies.rename ?? rename, unlink:dependencies.unlink ?? unlink, fetch:dependencies.fetch ?? globalThis.fetch,
      now:dependencies.now ?? Date.now, setTimeout:dependencies.setTimeout ?? setTimeout, clearTimeout:dependencies.clearTimeout ?? clearTimeout});
    if (Object.values(io).some(value => typeof value !== 'function')) fail();
  } catch { throw fixed('CONFIGURATION'); }

  const directory = ROOT + '/test-results/stripe-sandbox/' + topology.runId;
  const ledgerPath = directory + '/extras.json', lockPath = directory + '/extras.lock';
  const identities = new Map(), donations = {anonymous:{phase:'NEW',confirmed:false}, emailOtp:{phase:'NEW',confirmed:false}};
  const otp = {phase:'NEW'}, missed = {phase:'NEW',confirmed:false};
  let status = 'NEW', pending = false, busy = false, sequence = 0, lock = null, cookie = '', csrf = '';
  let missedToken = null, fixtureIssued = false, fixtureClosed = false;
  const recipient = topology.runId+'@example.test';
  const stable = name => 's5a:' + topology.runId + ':' + name;
  const resources = () => snapshot({runId:topology.runId,status:pending ? 'PENDING' : status,donations,otp,missed});

  function liveSnapshot(r) {
    if (!object(r) || r.status !== 'LIVE' || r.webhookReady !== true) fail();
    for (const name of ['runId','expectedSource','daemon','project','siteKey']) if (r[name] !== topology[name]) fail();
    for (const name of PORTS) if (r.ports?.[name] !== topology.ports[name]) fail();
    for (const name of ['backend','demo','receiver']) if (r.endpoints?.[name] !== topology.endpoints[name]) fail();
    for (const [name, service] of [['backend','backend'],['demo','demo-site'],['receiver','callback-receiver']])
      if (r.containers?.[service]?.id !== topology.owners[name]) fail();
  }
  function provisionSnapshot(r) {
    if (!object(r) || r.status !== 'READY') fail();
    for (const name of ['runId','expectedSource','daemon','project','siteKey']) if (r[name] !== topology[name]) fail();
    for (const name of PORTS) if (r.ports?.[name] !== topology.ports[name]) fail();
    for (const name of ['backend','demo','receiver']) if (r.endpoints?.[name] !== topology.endpoints[name]) fail();
    for (const name of ['project','backend','demo','receiver']) if (r.owners?.[name] !== topology.owners[name]) fail();
    const suffix = topology.runId.replaceAll('-', '');
    const result = {};
    for (const [name, key] of [['account','stripe-sandbox-default'],['site',topology.siteKey],['product','s5a-product-'+suffix],
      ['price','s5a-price-'+suffix],['identityProvider','s5a-email-'+suffix],['campaign','s5a-donate-'+suffix]]) {
      if (!uuid(r[name]?.id) || r[name].key !== key) fail();
      result[name] = {id:r[name].id,key};
    }
    if (r.account.externalId !== ACCOUNT || r.price.version !== 1 || typeof r.price.providerPriceId !== 'string'
      || !/^price_[A-Za-z0-9_]{1,249}$/.test(r.price.providerPriceId)
      || r.identityProvider.mode !== 'EMAIL_OTP' || r.identityProvider.status !== 'ACTIVE' || r.identityProvider.readiness !== 'READY'
      || r.identityProvider.version !== 0 || r.campaign.status !== 'ACTIVE' || r.campaign.version !== 1) fail();
    Object.assign(result.account, {externalId:ACCOUNT});
    Object.assign(result.price, {version:1,providerPriceId:r.price.providerPriceId});
    Object.assign(result.identityProvider, {mode:'EMAIL_OTP',status:'ACTIVE',readiness:'READY',version:0});
    Object.assign(result.campaign, {status:'ACTIVE',version:1});
    result.cases = {};
    const ids = Object.values(result).map(value => value.id).filter(Boolean);
    for (const name of ['success','decline','sca','missed']) {
      const c = r.cases?.[name];
      if (!uuid(c?.checkoutId) || !uuid(c?.paymentId)) fail();
      ids.push(c.checkoutId,c.paymentId);
      result.cases[name] = {checkoutId:c.checkoutId,paymentId:c.paymentId};
    }
    if (new Set(ids).size !== ids.length) fail();
    return snapshot(result);
  }
  function check(context) { if (context.signal.aborted || io.now() >= context.deadline) fail(); }
  async function controlled(context, body) {
    check(context);
    return new Promise((resolveWait, rejectWait) => {
      const abort = () => rejectWait(null);
      context.signal.addEventListener('abort', abort, {once:true});
      Promise.resolve().then(() => {check(context);return body();}).then(value => {
        context.signal.removeEventListener('abort', abort);
        try {check(context);resolveWait(value);} catch {rejectWait(null);}
      }, () => {context.signal.removeEventListener('abort', abort);rejectWait(null);});
      if (context.signal.aborted) abort();
    });
  }
  async function bounded(parent, body, budget = 10000) {
    check(parent);
    const controller = new AbortController(), abort = () => controller.abort();
    const context = {signal:controller.signal,deadline:Math.min(parent.deadline,io.now()+budget)};
    parent.signal.addEventListener('abort', abort, {once:true});
    if (parent.signal.aborted) abort();
    let timer;
    try {timer=io.setTimeout(abort,Math.max(1,context.deadline-io.now()));return await controlled(context,()=>body(context));}
    finally {try{io.clearTimeout(timer);parent.signal.removeEventListener('abort',abort);}catch{fail();}}
  }
  const call = (context, fn, ...args) => controlled(context, () => fn(...args));
  async function run(limits, body, opening = false) {
    if (busy) throw fixed('BUSY');
    busy = true;
    const controller = new AbortController();
    let timer, external, abort;
    try {
      if (pending || (opening ? status !== 'NEW' : status !== 'OPEN') || !Number.isSafeInteger(limits?.deadline)
        || limits.deadline <= io.now() || (limits.signal !== undefined && !(limits.signal instanceof AbortSignal))) fail();
      external = limits.signal; abort = () => controller.abort();
      external?.addEventListener('abort',abort,{once:true});if(external?.aborted)abort();
      const context = {signal:controller.signal,deadline:limits.deadline};
      timer = io.setTimeout(abort,Math.min(2147483647,limits.deadline-io.now()));
      return await controlled(context,()=>body(context));
    } catch {pending=true;throw fixed('PENDING');}
    finally {
      busy=false;
      try {io.clearTimeout(timer);external?.removeEventListener('abort',abort);}catch{pending=true;throw fixed('PENDING');}
    }
  }
  async function ownership(context) {
    if (await call(context,bootstrap.verify,{signal:context.signal,deadline:context.deadline}) !== true) fail();
    liveSnapshot(bootstrap.resources());
    if (!equal(provisionSnapshot(provisioner.resources()),binding)) fail();
  }
  async function closeHandle(handle, context) {
    const closing = Promise.resolve().then(()=>handle.close());closing.catch(()=>{});
    check(context);await bounded(context,()=>closing);
  }
  function privateFile(info) {
    if (!info?.isFile() || info.isSymbolicLink() || info.uid !== process.getuid() || (info.mode & 0o777) !== 0o600 || info.nlink !== 1) fail();
  }
  async function directoryProof(context) {
    for (const path of [ROOT,ROOT+'/test-results',ROOT+'/test-results/stripe-sandbox',directory]) {
      const info = await call(context,io.lstat,path);
      if (!info.isDirectory() || info.isSymbolicLink() || info.uid !== process.getuid() || (path !== ROOT && (info.mode & 0o777) !== 0o700)
        || await call(context,io.realpath,path) !== path) fail();
    }
  }
  async function fileProof(path, handle, context) {
    const held = await call(context,()=>handle.stat()), named = await call(context,io.lstat,path);
    privateFile(held);privateFile(named);
    const previous = identities.get(path);
    if (held.ino !== named.ino || held.dev !== named.dev || (previous && (previous.ino !== held.ino || previous.dev !== held.dev))
      || await call(context,io.realpath,path) !== path) fail();
    return {ino:held.ino,dev:held.dev};
  }
  async function absent(path, context) {
    const missing = await call(context,async()=>{try{await io.lstat(path);return false;}catch(error){return error?.code === 'ENOENT';}});
    if (!missing) fail();
  }
  async function syncDirectory(context) {
    const handle = await call(context,io.open,directory,constants.O_RDONLY|constants.O_NOFOLLOW);
    try {
      const info = await call(context,()=>handle.stat());
      if (!info.isDirectory() || info.uid !== process.getuid() || (info.mode & 0o777) !== 0o700) fail();
      await call(context,()=>handle.sync());
    } finally {await closeHandle(handle,context);}
  }
  async function exclusive(path, text, context, hold = false) {
    await directoryProof(context);
    const handle = await call(context,io.open,path,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);
    let handedOff = false;
    try {
      const identity = await fileProof(path,handle,context);
      await call(context,()=>handle.writeFile(text));await call(context,()=>handle.sync());
      await fileProof(path,handle,context);await syncDirectory(context);identities.set(path,identity);
      if (hold) {handedOff=true;return handle;}
    } finally {if(!handedOff)await closeHandle(handle,context);}
  }
  async function namedLedger(context) {
    if (!identities.has(ledgerPath)) return absent(ledgerPath,context);
    const handle = await call(context,io.open,ledgerPath,constants.O_RDONLY|constants.O_NOFOLLOW);
    try {await fileProof(ledgerPath,handle,context);}finally{await closeHandle(handle,context);}
  }
  async function save(context) {
    await directoryProof(context);if(!lock)fail();await fileProof(lockPath,lock,context);await namedLedger(context);
    const temporary = directory+'/.extras-'+(++sequence)+'.tmp', text = JSON.stringify({version:1,...resources()})+'\n';
    if (Buffer.byteLength(text) > 65536) fail();
    await exclusive(temporary,text,context);
    await directoryProof(context);await fileProof(lockPath,lock,context);await namedLedger(context);
    const handle = await call(context,io.open,temporary,constants.O_RDONLY|constants.O_NOFOLLOW);
    try {await fileProof(temporary,handle,context);}finally{await closeHandle(handle,context);}
    await call(context,io.rename,temporary,ledgerPath);identities.set(ledgerPath,identities.get(temporary));await syncDirectory(context);
  }
  function captureCookies(cookies) {
    if (!Array.isArray(cookies) || cookies.length !== 2) fail();
    const values = {};
    for (const entry of cookies) {
      if (typeof entry !== 'string' || entry.length > 2048 || /[\r\n\x00-\x1f\x7f]/.test(entry)) fail();
      const parts = entry.split(';').map(part=>part.trim());
      const match = /^(PAYMENT_ADMIN_SESSION|XSRF-TOKEN)=([A-Za-z0-9_-]{1,512})$/.exec(parts[0]);
      if (!match || values[match[1]] || parts.some(part=>/^domain=/i.test(part)) || !parts.some(part=>/^path=\/$/i.test(part))) fail();
      values[match[1]]=match[2];
    }
    if (!values.PAYMENT_ADMIN_SESSION || !values['XSRF-TOKEN']) fail();
    cookie='PAYMENT_ADMIN_SESSION='+values.PAYMENT_ADMIN_SESSION+'; XSRF-TOKEN='+values['XSRF-TOKEN'];csrf=values['XSRF-TOKEN'];
  }
  async function request(path, context, {target='backend',method='GET',body,key,login=false,checkoutToken}={}) {
    return bounded(context,async operation=>{
      await ownership(operation);
      const origin = target === 'mailpit' ? 'http://127.0.0.1:'+topology.ports.mailpitHttp : topology.endpoints[target];
      const admin = target === 'backend' && !login && path.startsWith('/api/v1/admin/');
      if (!origin || (admin && (!cookie || !csrf))) fail();
      const headers = {Accept:'application/json',...(body!==undefined?{'Content-Type':'application/json'}:{}),
        ...(key?{'Idempotency-Key':key}:{}),...(admin?{Cookie:cookie,...(method!=='GET'?{'X-XSRF-TOKEN':csrf}:{})}:{}),
        ...(checkoutToken?{Origin:topology.endpoints.demo,Authorization:'Bearer '+checkoutToken}:{})};
      if (method==='POST' && path==='/api/v1/admin/payments/'+binding.cases.missed.paymentId+'/reconcile')
        headers['X-Request-Id']=stable('missed:reconcile');
      const response = await call(operation,io.fetch,origin+path,{method,headers,redirect:'error',signal:operation.signal,...(body!==undefined?{body:JSON.stringify(body)}:{})});
      const reader = response.body?.getReader();
      const chunks = [];let size=0;
      try {
        if (response.status !== (login?204:200)) fail();
        if (reader) for (;;) {
          const part = await call(operation,()=>reader.read());if(part.done)break;
          if (!(part.value instanceof Uint8Array)) fail();
          size+=part.value.byteLength;if(size>65536)fail();chunks.push(Buffer.from(part.value));
        }
        check(operation);
        const bytes = Buffer.concat(chunks);
        if (login && bytes.length) fail();
        const value = bytes.length ? JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes)) : null;
        return {value,cookies:login?response.headers.getSetCookie():null};
      } finally {
        if (reader) {Promise.resolve(reader.cancel()).catch(()=>{});reader.releaseLock();}
        else if (response.body?.cancel) Promise.resolve(response.body.cancel()).catch(()=>{});
      }
    });
  }
  const read = async(path,context,settings) => (await request(path,context,settings)).value;
  function donation(kind) {if(!KINDS.includes(kind))fail();return donations[kind];}
  function safeDetails(d, c, source, customerId) {
    const p = d?.payment;
    if (!object(p) || !uuid(p.payment_id) || p.payment_id !== c.paymentId || p.site_id !== binding.site.id
      || p.payment_account_id !== binding.account.id || p.provider !== 'STRIPE' || p.source !== source
      || p.amount_minor !== (source==='DONATION'?500:2000) || p.currency !== 'EUR' || p.customer_id !== customerId
      || p.refunded_amount_minor !== 0 || p.tax_mode !== 'NONE' || p.subscription_id !== null || p.billing_record_id !== null) fail();
    if (source === 'DONATION') {
      if (p.price_id !== null || p.price_key !== null || p.price_version !== null || p.donation_id !== c.donationId
        || p.campaign_id !== binding.campaign.id || p.campaign_key !== binding.campaign.key || p.campaign_version !== binding.campaign.version) fail();
    } else if (p.price_id !== binding.price.id || p.price_key !== binding.price.key || p.price_version !== binding.price.version
      || p.donation_id !== null || p.campaign_id !== null || p.campaign_key !== null || p.campaign_version !== null) fail();
    for (const name of ['attempts','events','refunds','outbox_events','deliveries']) {
      if (!Array.isArray(d[name]) || d[name].length > 1000 || d[name].some(value=>!object(value)||!uuid(value.id))
        || new Set(d[name].map(value=>value.id)).size !== d[name].length) fail();
    }
    if (d.refunds.length) fail();
    return d;
  }
  async function detail(c, source, customerId, context) {
    return safeDetails(await read('/api/v1/admin/payments/'+c.paymentId,context),c,source,customerId);
  }
  function noSuccess(d) {
    if (!['CREATED','REQUIRES_PAYMENT_METHOD','REQUIRES_ACTION','PROCESSING'].includes(d.payment.status)
      || d.events.some(e=>e.event_type==='PAYMENT_SUCCEEDED') || d.outbox_events.some(e=>e.callback_type==='payment.succeeded')
      || d.deliveries.some(e=>d.outbox_events.some(o=>o.id===e.outbox_event_id&&o.callback_type==='payment.succeeded'))) fail();
  }
  async function preparation(c, d, context) {
    const operations = await read('/api/v1/admin/provider-operations?payment_id='+c.paymentId,context);
    if (!Array.isArray(operations) || operations.length > 100 || operations.some(o=>!object(o)||!uuid(o.operation_id))) fail();
    const matches = operations.filter(o=>o.kind==='PAYMENT_PREPARE');if(matches.length!==1)fail();
    const op = matches[0];
    if (op.payment_id !== c.paymentId || op.resource_id !== c.paymentId || op.site_id !== binding.site.id || op.account_id !== binding.account.id
      || op.account_mode !== 'SANDBOX' || op.status !== 'ACKNOWLEDGED' || op.manual_review_required !== false || !intent(op.reference)) fail();
    if (c.operationId && (c.operationId!==op.operation_id || c.intentId!==op.reference)) fail();
    if (d.attempts.length !== 1) fail();
    const attempt = d.attempts[0];
    if (attempt.attempt_number !== 1 || attempt.provider_intent_reference !== op.reference
      || (c.attemptId && c.attemptId!==attempt.id)) fail();
    return {operationId:op.operation_id,intentId:op.reference,attemptId:attempt.id};
  }
  async function inspection(context) {
    const value = await read('/inspection',context,{target:'receiver'});
    if (!object(value) || !Array.isArray(value.events) || value.events.length>1000 || !Number.isSafeInteger(value.count)
      || value.count!==value.events.length || !Number.isSafeInteger(value.duplicates) || value.duplicates<0
      || value.events.some(e=>!object(e)||!uuid(e.event_id)||typeof e.event_type!=='string'||!Number.isSafeInteger(e.count)||e.count<1)
      || new Set(value.events.map(e=>e.event_id)).size!==value.events.length) fail();
    return value.events;
  }
  async function successProof(c, d, context, reconciliation=false) {
    const matches = d.events.filter(e=>e.event_type==='PAYMENT_SUCCEEDED');
    if (matches.length>1) fail();
    if (!matches.length) return null;
    const e = matches[0];
    if (e.provider_object_id!==c.intentId || e.amount_minor!==d.payment.amount_minor || e.currency!=='EUR'
      || !['PROVIDER_EVENT','RECONCILIATION'].includes(e.source)
      || (reconciliation && (e.source!=='RECONCILIATION' || e.request_id!==stable('missed:reconcile')))) fail();
    if(d.outbox_events.filter(o=>o.callback_type==='payment.succeeded').some(o=>o.id!==e.id))fail();
    if (d.payment.status!=='SUCCEEDED' || d.attempts[0].status!=='SUCCEEDED') return null;
    const out = d.outbox_events.filter(o=>o.id===e.id && o.callback_type==='payment.succeeded');
    if (out.length!==1 || out[0].status!=='DELIVERED') return null;
    const deliveries = d.deliveries.filter(v=>v.outbox_event_id===e.id && v.status==='SUCCEEDED'
      && Number.isInteger(v.http_status) && v.http_status>=200 && v.http_status<300);
    if (!deliveries.length) return null;
    const received = await inspection(context);
    if (received.filter(v=>v.event_id===e.id && v.event_type==='payment.succeeded').length!==1) return null;
    return {eventId:e.id,outboxId:out[0].id,deliveryId:deliveries[0].id,receiverVerified:true};
  }
  async function delay(context, milliseconds) {
    let timer;
    try {await controlled(context,()=>new Promise(resolveWait=>{timer=io.setTimeout(resolveWait,Math.min(milliseconds,Math.max(1,context.deadline-io.now())));}));}
    finally {io.clearTimeout(timer);}
  }
  async function poll(context, probe, budget=45000, attempts=30) {
    return bounded(context,async operation=>{
      for(let n=0;n<attempts;n++) {
        const value=await probe(operation);if(value)return value;
        if(n+1<attempts)await delay(operation,Math.min(1000,Math.max(1,operation.deadline-io.now())));
      }
      fail();
    },budget);
  }
  async function openExtras(context) {
    await bounded(context,ownership);
    if (await bounded(context,operation=>call(operation,cli.validateBinding,{signal:operation.signal,deadline:operation.deadline}))!==true) fail();
    await directoryProof(context);await absent(ledgerPath,context);await absent(lockPath,context);
    lock=await exclusive(lockPath,JSON.stringify({runId:topology.runId})+'\n',context,true);status='OPENING';await save(context);
    let admin;
    await bounded(context,async operation=>{
      await ownership(operation);
      if (await call(operation,bootstrap.withAdminCredentials,value=>{
        check(operation);
        if(!credential(value?.username)||!credential(value?.password))fail();
        admin=Object.freeze({username:value.username,password:value.password});return true;
      })!==true || !admin) fail();
    });
    try {captureCookies((await request('/api/admin/v1/auth/sessions',context,{method:'POST',body:admin,login:true})).cookies);}
    finally {admin=null;}
    status='OPEN';await save(context);return true;
  }
  async function beforeDonationCreate(kind, facts, context) {
    const c=donation(kind);
    if(c.phase!=='NEW' || !exact(facts,['siteKey','campaignKey','amountMinor','hasCustomerAuthority']) || facts.siteKey!==binding.site.key
      || facts.campaignKey!==binding.campaign.key || facts.amountMinor!==500 || typeof facts.hasCustomerAuthority!=='boolean'
      || facts.hasCustomerAuthority!==(kind==='emailOtp') || (kind==='emailOtp' && otp.phase!=='VERIFIED')) fail();
    await bounded(context,ownership);
    Object.assign(c,{key:stable('donation:'+kind),phase:'DISPATCHING'});await save(context);return c.key;
  }
  async function ackDonationCreate(kind, value, context) {
    const c=donation(kind);
    if(c.phase!=='DISPATCHING' || !exact(value,['checkoutId','paymentId','donationId','campaignKey','campaignVersion','amountMinor','currency'])
      || ![value.checkoutId,value.paymentId,value.donationId].every(uuid) || new Set([value.checkoutId,value.paymentId,value.donationId]).size!==3
      || value.campaignKey!==binding.campaign.key || value.campaignVersion!==binding.campaign.version || value.amountMinor!==500 || value.currency!=='EUR') fail();
    const used=Object.values(binding.cases).flatMap(v=>[v.checkoutId,v.paymentId]);
    for(const other of Object.values(donations))for(const name of ['checkoutId','paymentId','donationId'])if(other[name])used.push(other[name]);
    if([value.checkoutId,value.paymentId,value.donationId].some(id=>used.includes(id)))fail();
    const acknowledged={checkoutId:value.checkoutId,paymentId:value.paymentId,donationId:value.donationId};
    const d=await detail(acknowledged,'DONATION',kind==='emailOtp'?otp.customerId:null,context);noSuccess(d);
    Object.assign(c,acknowledged,{phase:'ACKNOWLEDGED'});await save(context);return true;
  }
  async function beforeDonationPay(kind, context) {
    const c=donation(kind);if(c.phase!=='ACKNOWLEDGED')fail();
    const d=await detail(c,'DONATION',kind==='emailOtp'?otp.customerId:null,context);noSuccess(d);
    const original=await preparation(c,d,context);
    if(!['CREATED','REQUIRES_PAYMENT_METHOD'].includes(d.attempts[0].status))fail();
    Object.assign(c,original,{phase:'PAY_DISPATCHING'});await save(context);return true;
  }
  async function confirmDonation(kind, context) {
    const c=donation(kind);if(c.phase!=='PAY_DISPATCHING')fail();
    const proof=await poll(context,async operation=>{
      const d=await detail(c,'DONATION',kind==='emailOtp'?otp.customerId:null,operation);
      await preparation(c,d,operation);return successProof(c,d,operation);
    });
    Object.assign(c,proof,{phase:'CONFIRMED',confirmed:true});await save(context);return true;
  }
  function handoff(callback, value, context) {
    check(context);if(typeof callback!=='function')fail();
    const result=callback(value);
    if(result instanceof Promise)result.catch(()=>{});
    if(result!==true)fail();check(context);
  }
  async function recipientMessages(context) {
    const value=await read('/api/v1/messages',context,{target:'mailpit'});
    if (!object(value) || !Array.isArray(value.messages) || value.messages.length>1000 || value.start!==0
      || !Number.isSafeInteger(value.messages_count) || value.messages_count!==value.messages.length
      || value.total!==value.messages.length) fail();
    const ids=new Set();
    for(const message of value.messages) {
      if(!object(message) || typeof message.ID!=='string' || !/^[A-Za-z0-9]{1,128}$/.test(message.ID) || ids.has(message.ID)
        || !Array.isArray(message.To) || message.To.some(to=>!object(to)||typeof to.Address!=='string'))fail();
      ids.add(message.ID);
    }
    return value.messages.filter(message=>message.To.some(to=>to.Address===recipient));
  }
  function mailProof(message, dateField) {
    if (!object(message) || !Array.isArray(message.To) || message.To.length!==1 || message.To[0]?.Address!==recipient
      || message.From?.Address!=='no-reply@payment.local' || message.Subject!=='Your verification code') fail();
    const date=timestamp(message[dateField]);
    const lower=dateField==='Date'?Math.floor(otp.dispatchedAt/1000)*1000:otp.dispatchedAt;
    if(date<lower || date>otp.expiresAt)fail();
  }
  async function beginOtp(callback, context) {
    if(otp.phase!=='NEW' || typeof callback!=='function')fail();
    if((await recipientMessages(context)).length)fail();
    Object.assign(otp,{phase:'CHALLENGE_DISPATCHING',dispatchedAt:io.now()});await save(context);
    await bounded(context,ownership);
    handoff(callback,Object.freeze({email:recipient,siteKey:binding.site.key}),context);return true;
  }
  async function ackOtpChallenge(value, context) {
    if(otp.phase!=='CHALLENGE_DISPATCHING' || !exact(value,['challengeId','expiresAt']) || !uuid(value.challengeId))fail();
    const expiresAt=timestamp(value.expiresAt);
    if(expiresAt<=io.now() || expiresAt<=otp.dispatchedAt || expiresAt>otp.dispatchedAt+1800000)fail();
    Object.assign(otp,{phase:'CHALLENGE_ACKNOWLEDGED',challengeId:value.challengeId,expiresAt});await save(context);return true;
  }
  async function withOtpCode(callback, context) {
    if(otp.phase!=='CHALLENGE_ACKNOWLEDGED' || typeof callback!=='function' || io.now()>=otp.expiresAt)fail();
    await bounded({...context,deadline:Math.min(context.deadline,otp.expiresAt)},async operation=>{
      let code=null;
      try {
        code=await poll(operation,async current=>{
          const matches=await recipientMessages(current);
          if(matches.length>1)fail();if(!matches.length)return null;
          const summary=matches[0];mailProof(summary,'Created');
          const detail=await read('/api/v1/message/'+summary.ID,current,{target:'mailpit'});
          if(typeof detail?.ID!=='string' || detail.ID!==summary.ID)fail();mailProof(detail,'Date');
          if(typeof detail.Text!=='string' || detail.Text.length>64000)fail();
          const codes=[...detail.Text.matchAll(/(?<![0-9])[0-9]{6}(?![0-9])/g)];
          if(codes.length!==1)fail();
          // A second complete listing catches an already-arrived duplicate instead of selecting the first message.
          const again=await recipientMessages(current);
          if(again.length!==1 || again[0].ID!==summary.ID)fail();mailProof(again[0],'Created');
          return codes[0][0];
        });
        otp.phase='CODE_DISPATCHING';await save(operation);
        handoff(callback,code,operation);otp.phase='CODE_HANDED';await save(operation);return true;
      } finally {code=null;}
    },45000);
    return true;
  }
  async function beforeOtpVerify(context) {
    if(otp.phase!=='CODE_HANDED' || io.now()>=otp.expiresAt)fail();
    await bounded(context,ownership);otp.phase='VERIFY_DISPATCHING';await save(context);return true;
  }
  async function ackOtpVerification(value, context) {
    if(otp.phase!=='VERIFY_DISPATCHING' || io.now()>=otp.expiresAt || !exact(value,['challengeId','customerId'])
      || !uuid(value.challengeId) || value.challengeId!==otp.challengeId || !uuid(value.customerId))fail();
    otp.customerId=value.customerId;otp.phase='VERIFIED';await save(context);return true;
  }
  async function negativeBaseline(context) {
    const d=await detail(missed,'CATALOG',null,context);noSuccess(d);await preparation(missed,d,context);
    const received=await inspection(context);
    if(received.some(r=>r.event_type==='payment.succeeded' && d.events.some(e=>e.id===r.event_id)))fail();
    return {journalIds:d.events.map(e=>e.id),outboxIds:d.outbox_events.map(e=>e.id),deliveryIds:d.deliveries.map(e=>e.id),receiverIds:received.map(e=>e.event_id)};
  }
  function onlySuccess(name) {if(name!=='success' || fixtureClosed)fail();}
  async function missedFixture(limits) {
    let captured;
    try {captured=Object.freeze({signal:limits?.signal,deadline:limits?.deadline});}
    catch {pending=true;throw fixed('PENDING');}
    return run(captured,async context=>{
      if(fixtureIssued || missed.phase!=='NEW')fail();await bounded(context,ownership);
      let original;
      const result=provisioner.withMissedCheckout(value=>{
        check(context);
        if(!exact(value,['checkoutToken','checkoutId','paymentId']) || !token(value.checkoutToken)
          || value.checkoutId!==binding.cases.missed.checkoutId || value.paymentId!==binding.cases.missed.paymentId)fail();
        original={checkoutToken:value.checkoutToken,checkoutId:value.checkoutId,paymentId:value.paymentId};return true;
      });
      if(result instanceof Promise)result.catch(()=>{});
      if(result!==true || !original)fail();
      missedToken=original.checkoutToken;Object.assign(missed,{checkoutId:original.checkoutId,paymentId:original.paymentId,phase:'READY'});
      original=null;fixtureIssued=true;await save(context);
      return Object.freeze({identity:snapshot({endpoints:topology.endpoints,site:binding.site,price:{id:binding.price.id,key:binding.price.key}}),
        beginBrowser:name=>run(captured,async current=>{
          onlySuccess(name);if(missed.phase!=='READY')fail();
          const d=await detail(missed,'CATALOG',null,current);noSuccess(d);
          if(!['CREATED','REQUIRES_PAYMENT_METHOD'].includes(d.payment.status))fail();
          missed.phase='PREPARING';await save(current);return true;
        }),
        browserSession:name=>run(captured,async current=>{
          onlySuccess(name);if(missed.phase!=='PREPARING' || !missedToken)fail();
          const v=await read('/api/v1/public/checkouts/'+missed.checkoutId,current,{checkoutToken:missedToken});
          if(!object(v) || v.checkout_id!==missed.checkoutId || v.payment_id!==missed.paymentId || v.provider_type!=='STRIPE'
            || v.confirmation_mode!=='CLIENT_PROVIDER' || v.amount_minor!==2000 || v.currency!=='EUR'
            || !['CREATED','REQUIRES_PAYMENT_METHOD'].includes(v.status) || timestamp(v.expires_at)<=io.now()
            || v.price?.price_id!==binding.price.id || v.price.price_key!==binding.price.key || v.price.version!==binding.price.version
            || v.price.amount_minor!==2000 || v.price.currency!=='EUR' || v.price.tax_mode!=='NONE')fail();
          missed.phase='BROWSER_READY';await save(current);
          return {version:2,checkoutId:missed.checkoutId,checkoutToken:missedToken,expiresAt:v.expires_at,apiBaseUrl:topology.endpoints.backend,
            siteKey:binding.site.key,priceKey:binding.price.key,providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',
            state:{checkoutId:missed.checkoutId,paymentId:missed.paymentId,amountMinor:2000,currency:'EUR',status:v.status,expiresAt:v.expires_at,
              price:{priceId:binding.price.id,priceKey:binding.price.key,version:binding.price.version,amountMinor:2000,currency:'EUR',taxMode:'NONE'}}};
        }),
        markPay:(...args)=>run(captured,async current=>{
          if(args.length!==1)fail();onlySuccess(args[0]);if(missed.phase!=='BROWSER_READY')fail();
          const d=await detail(missed,'CATALOG',null,current);noSuccess(d);
          const original=await preparation(missed,d,current);
          if(!['CREATED','REQUIRES_PAYMENT_METHOD'].includes(d.attempts[0].status))fail();
          Object.assign(missed,original);
          if(await call(current,cli.bindMissedIntent,Object.freeze({paymentId:missed.paymentId,siteId:binding.site.id,operationId:missed.operationId,
            intentId:missed.intentId,amountMinor:2000,currency:'EUR',source:'CATALOG',externalAccountId:ACCOUNT}))!==true)fail();
          missed.negativeBaseline=await negativeBaseline(current);missed.phase='LISTENER_STOP_DISPATCHING';await save(current);
          if(await call(current,cli.stopListener,{signal:current.signal,deadline:current.deadline})!==true)fail();
          missed.listenerStopped=true;missed.phase='PAY_DISPATCHING';await save(current);
        }),
        confirm:name=>run(captured,async current=>{
          onlySuccess(name);if(missed.phase!=='PAY_DISPATCHING' || missed.listenerStopped!==true)fail();
          await poll(current,async operation=>{
            await bounded(operation,ownership);
            try {const proof=await call(operation,cli.inspectIntent,{signal:operation.signal,deadline:operation.deadline});return exact(proof,['captured'])&&proof.captured===true;}
            catch {check(operation);return false;}
          },20000,10);
          missed.providerCaptured=true;missed.negativeBaseline=await negativeBaseline(current);
          missed.key=stable('missed:reconcile');missed.phase='RECONCILE_DISPATCHING';await save(current);
          const response=safeDetails(await read('/api/v1/admin/payments/'+missed.paymentId+'/reconcile',current,{method:'POST',key:missed.key}),missed,'CATALOG',null);
          await preparation(missed,response,current);
          const success=response.events.filter(e=>e.event_type==='PAYMENT_SUCCEEDED');
          if(success.length!==1 || success[0].source!=='RECONCILIATION' || success[0].request_id!==missed.key
            || missed.negativeBaseline.journalIds.includes(success[0].id) || missed.negativeBaseline.outboxIds.includes(success[0].id)
            || missed.negativeBaseline.receiverIds.includes(success[0].id))fail();
          missed.phase='RECONCILE_ACKNOWLEDGED';await save(current);
          const proof=await poll(current,async operation=>{
            const d=await detail(missed,'CATALOG',null,operation);await preparation(missed,d,operation);
            const result=await successProof(missed,d,operation,true);
            if(result && (result.eventId!==success[0].id || missed.negativeBaseline.deliveryIds.includes(result.deliveryId)))fail();
            return result;
          });
          Object.assign(missed,proof,{phase:'CONFIRMED',confirmed:true});await save(current);return true;
        }),
        refunds:async()=>{throw fixed('UNSUPPORTED_OPERATION');},
        close:async()=>{if(busy)throw fixed('BUSY');fixtureClosed=true;missedToken=null;},
      });
    });
  }
  async function closeExtras(context) {
    if(Object.values(donations).some(c=>!['NEW','CONFIRMED'].includes(c.phase)) || !['NEW','VERIFIED'].includes(otp.phase)
      || !['NEW','CONFIRMED'].includes(missed.phase) || (fixtureIssued&&!fixtureClosed))fail();
    await bounded(context,ownership);status='CLOSED';await save(context);
    await directoryProof(context);await fileProof(lockPath,lock,context);
    await call(context,io.unlink,lockPath);await syncDirectory(context);await closeHandle(lock,context);lock=null;
    cookie='';csrf='';missedToken=null;return true;
  }
  return Object.freeze({resources,open:limits=>run(limits,openExtras,true),
    beforeDonationCreate:(kind,facts,limits)=>run(limits,context=>beforeDonationCreate(kind,facts,context)),
    ackDonationCreate:(kind,value,limits)=>run(limits,context=>ackDonationCreate(kind,value,context)),
    beforeDonationPay:(kind,limits)=>run(limits,context=>beforeDonationPay(kind,context)),
    confirmDonation:(kind,limits)=>run(limits,context=>confirmDonation(kind,context)),
    beginOtp:(callback,limits)=>run(limits,context=>beginOtp(callback,context)),
    ackOtpChallenge:(value,limits)=>run(limits,context=>ackOtpChallenge(value,context)),
    withOtpCode:(callback,limits)=>run(limits,context=>withOtpCode(callback,context)),
    beforeOtpVerify:limits=>run(limits,beforeOtpVerify),
    ackOtpVerification:(value,limits)=>run(limits,context=>ackOtpVerification(value,context)),
    missedFixture,close:limits=>run(limits,closeExtras)});
}
