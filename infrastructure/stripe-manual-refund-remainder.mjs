import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {manualRefundRemainderOptIn,validateManualRefundRemainderOptions} from './stripe-manual-refund-remainder-options.mjs';
import {validateManualRefundDiagnostic} from './stripe-manual-refund-schema.mjs';
import {validateManualRefundRemainderReport,formatManualRefundRemainderReport} from './stripe-manual-refund-remainder-schema.mjs';

const ROOT=fileURLToPath(new URL('..',import.meta.url)).replace(/\/$/,'');
const ORIGINAL='031b5ae7-6603-4339-b1b3-77231291ff34',PRIOR='34941d87-12d3-4c74-8c5a-b62b7c914390';
const FLAGS=['ENABLED','ALLOW_TEST_OPERATIONS','MANUAL_REFUND_REMAINDER','MANUAL_REFUND','FULL_RUN','OWNED_RUN','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT'].map(key=>'PAYMENT_STRIPE_SANDBOX_'+key).concat(['DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE']);
const EMPTY=Object.freeze({version:1,mode:'MANUAL_REFUND_REMAINDER',result:'FAILED',phase:'ADMISSION',target_verified:false,prior_partial_verified:false,prior_callback_delivery_verified:false,callback_rotated:false,remainder_confirmed:false,provider_verified:false,webhook_verified:false,journal_verified:false,callback_signature_verified:false,final_total_verified:false,listener_closed:false,runtime_closed:false,provenance:'CONTROLLER_REPORTED'});
const rejected=()=>Object.freeze({report:EMPTY,exitCode:1});
const fail=()=>{throw null;};
function data(value,key){
  const descriptor=Object.getOwnPropertyDescriptor(value,key);
  if(descriptor&&(!descriptor.enumerable||!Object.hasOwn(descriptor,'value'))||!descriptor&&key in value)fail();
  return descriptor?.value;
}
function exact(value,keys){
  if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))fail();
  const descriptors=Object.getOwnPropertyDescriptors(value);
  if(Reflect.ownKeys(descriptors).length!==keys.length)fail();
  return Object.fromEntries(keys.map(key=>{const d=descriptors[key];if(!d?.enumerable||!Object.hasOwn(d,'value'))fail();return [key,d.value];}));
}
function writer(stream){
  return text=>new Promise((accept,reject)=>{
    let done=false;
    const finish=error=>{if(done)return;done=true;stream.removeListener('error',finish);error?reject():accept();};
    stream.once('error',finish);
    try{stream.write(text,error=>{if(error)setImmediate(()=>finish(true));else finish(false);});}catch{finish(true);}
  });
}

/** One authority, including raw late factories and independent cleanup obligations. */
export async function runManualRefundRemainder(options, originalLimits, deps = {}) {
  const selected = validateManualRefundRemainderOptions(options);
  let input;
  try {
    input = exact(originalLimits, Object.hasOwn(originalLimits ?? {}, 'signal') ? ['deadline', 'signal'] : ['deadline']);
    if (!selected || !Number.isSafeInteger(input.deadline) || input.deadline <= 0
      || input.signal !== undefined && !(input.signal instanceof AbortSignal)
      || input.signal?.aborted) return rejected();
  } catch {
    return rejected();
  }
  let now,
    setTimer,
    clearTimer;
  try {
    now = deps.now ?? Date.now;
    const start = now();
    if (!Number.isSafeInteger(start) || input.deadline <= start || input.deadline > start + 1800000) return rejected();
    setTimer = deps.setTimeout ?? setTimeout;
    clearTimer = deps.clearTimeout ?? clearTimeout;
  } catch {
    return rejected();
  }
  const controller = new AbortController(),
    abort = () => controller.abort();
  const limits = Object.freeze({deadline: input.deadline, signal: controller.signal});
  const cleanup = Object.freeze({deadline: input.deadline + 120000}),
    httpCutoff = input.deadline + 60000;
  input.signal?.addEventListener('abort', abort, {once: true});
  const timer = setTimer(abort, Math.max(1, limits.deadline - now()));
  const proof = {...EMPTY},
    resources = {},
    attempted = new Set(),
    active = new Set(),
    runtimeWork = new Set(),
    httpWork = new Set(),
    all = new Set(),
    closures = new Map(),
    settledClosures = new Set();
  let phase = 'ADMISSION',
    pending = false,
    denied = false,
    closing = false,
    runtimeReleased = false,
    stateReleased = false,
    diagnostic = null,
    baseline;

  function remember() {
    if (diagnostic || !resources.session) return;
    try {
      const inspect = data(resources.session, 'diagnostic');
      if (typeof inspect === 'function') diagnostic = validateManualRefundDiagnostic(inspect.call(resources.session));
    } catch {}
  }
  function uncertain() {
    pending = true;
    remember();
  }
  function check() {
    if (closing || pending || controller.signal.aborted || now() >= limits.deadline) fail();
  }
  function track(body, kind, operation = true) {
    const promise = Promise.resolve().then(body);
    all.add(promise);
    if (operation) {
      active.add(promise);
      if (kind === 'runtime') runtimeWork.add(promise);
      else httpWork.add(promise);
    }
    const settle = () => {
      all.delete(promise);
      active.delete(promise);
      runtimeWork.delete(promise);
      httpWork.delete(promise);
      if (closing && runtimeReleased && !runtimeWork.size) close('runtime');
    };
    promise.then(settle, settle);
    promise.catch(() => {});
    return promise;
  }
  function wait(promise, deadline, signal) {
    return new Promise((accept, reject) => {
      let done = false,
        timer;
      const finish = (ok, value) => {
        if (done) return;
        done = true;
        clearTimer(timer);
        signal?.removeEventListener('abort', cancel);
        ok ? accept(value) : reject();
      };
      const cancel = () => finish(false);
      Promise.resolve(promise).then(value => finish(now() < deadline && !signal?.aborted, value), cancel);
      if (now() >= deadline || signal?.aborted) {
        cancel();
        return;
      }
      signal?.addEventListener('abort', cancel, {once: true});
      timer = setTimer(cancel, Math.max(1, deadline - now()));
    });
  }
  function acquire(kind, value) {
    resources[kind] = value;
    if (closing
      && (kind === 'session' || kind === 'cli' || kind === 'runtime' && runtimeReleased && !runtimeWork.size || kind === 'state' && stateReleased)) close(kind);
  }
  function operation(body, kind, onAcquire) {
    check();
    const raw = track(async () => {
      check();
      const value = await body();
      onAcquire?.(value);
      return value;
    }, kind);
    return wait(raw, limits.deadline, limits.signal);
  }
  async function requireTrue(body, kind) {
    if (await operation(body, kind) !== true) fail();
    check();
  }
  async function factory(kind, body) {
    attempted.add(kind);
    await operation(body, kind, value => acquire(kind, value));
    check();
  }
  function close(kind) {
    if (kind === 'runtime' && runtimeWork.size) return;
    if (!resources[kind] || closures.has(kind) || now() >= cleanup.deadline) return closures.get(kind);
    const method = {state: 'close', runtime: 'stop', cli: 'stopListener', session: 'close'}[kind];
    const promise = track(async () => {
      if (now() >= cleanup.deadline) fail();
      if (await resources[kind][method](cleanup) !== true || now() >= cleanup.deadline) fail();
      return true;
    }, kind, false);
    closures.set(kind, promise);
    promise.then(() => settledClosures.add(kind), () => {
      settledClosures.add(kind);
      uncertain();
    });
    return promise;
  }
  async function drain(set, deadline) {
    try {
      while (set.size) await wait(Promise.allSettled([...set]), deadline);
    } catch {
      uncertain();
    }
  }
  async function certify(kind, field, deadline = cleanup.deadline) {
    if (!attempted.has(kind)) {
      if (field) proof[field] = true;
      return;
    }
    try {
      const promise = close(kind);
      if (!promise || await wait(promise, deadline) !== true) fail();
      if (field) proof[field] = true;
    } catch {
      uncertain();
    }
  }
  function acceptFinancial(value, prior) {
    const last = prior ? 'callbackDeliveryVerified' : 'callbackSignatureVerified';
    const p = exact(value, [
      'refundId',
      'providerReference',
      'providerEventId',
      'cashEventId',
      'amountMinor',
      'totalMinor',
      'providerVerified',
      'webhookVerified',
      'journalVerified',
      last
    ]);
    const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
    if (!uuid(p.refundId) || !uuid(p.cashEventId) || typeof p.providerReference !== 'string'
      || !/^re_[A-Za-z0-9_]{1,249}$/.test(p.providerReference)
      || typeof p.providerEventId !== 'string'
      || !/^evt_[A-Za-z0-9_]{1,249}$/.test(p.providerEventId)
      || p.amountMinor !== (prior ? 700 : 1300)
      || p.totalMinor !== (prior ? 700 : 2000)
      || ['providerVerified', 'webhookVerified', 'journalVerified', last].some(key => p[key] !== true)) fail();
    if (prior ? p.refundId !== resources.state.target().priorRefundId : p.refundId === baseline.refundId || p.providerReference === baseline.providerReference
      || p.providerEventId === baseline.providerEventId
      || p.cashEventId === baseline.cashEventId) fail();
    return Object.freeze(p);
  }
  try {
    await factory('state', async () => {
      const create = deps.createState ?? (await import('./stripe-manual-refund-remainder-state.mjs')).createManualRefundRemainderState;
      return create(selected);
    });
    const admission = await operation(() => resources.state.admit(limits), 'state');
    if (admission === 'DENIED') {
      denied = true;
    }
    else {
      if (admission !== 'READY') fail();
      check();
      phase = 'RESTORE';
      await factory('runtime', async () => {
        const create = deps.createRuntime ?? (await import('./stripe-manual-refund-remainder-runtime.mjs')).createOwnedManualRefundRemainderRuntime;
        return create(Object.freeze({options: selected, state: resources.state}));
      });
      await requireTrue(() => resources.runtime.restore(limits), 'runtime');
      phase = 'BINDING';
      await factory('cli', async () => {
        const create = deps.createCli ?? (await import('./stripe-manual-refund-remainder-cli.mjs')).createManualRefundRemainderCliAdapter;
        return create(Object.freeze({
          env: selected.env,
          originalRunId: ORIGINAL,
          priorRunId: PRIOR,
          cliDirectory: ROOT + '/test-results/stripe-sandbox/' + ORIGINAL + '/cli',
          backendPort: resources.state.target().original.ports.backend,
          verifyForwardTarget: resources.runtime.verifyForwardTarget,
          persistWebhookSecret: resources.runtime.verifyWebhookSecret,
          approvedBinding: Object.freeze({externalAccountId: 'acct_1UPiMpELswAd8tpR', apiVersion: '2026-09-30.endive'})
        }));
      });
      await requireTrue(() => resources.cli.validateBinding(limits), 'cli');
      phase = 'LISTENER';
      await requireTrue(() => resources.cli.startListener(limits), 'cli');
      phase = 'BASELINE';
      await requireTrue(() => resources.cli.bindRemainderTarget(resources.state.target(), limits), 'cli');
      await factory('session', async () => {
        const create = deps.createSession ?? (await import('./stripe-manual-refund-remainder-session.mjs')).createManualRefundRemainderSession;
        return create(Object.freeze({runtime: resources.runtime, cli: resources.cli, state: resources.state}));
      });
      baseline = acceptFinancial(await operation(() => resources.session.verifyBaseline(limits), 'session'), true);
      check();
      proof.target_verified = true;
      proof.prior_partial_verified = true;
      proof.prior_callback_delivery_verified = true;
      phase = 'ROTATION';
      await requireTrue(() => resources.session.rotateCallback(limits), 'session');
      proof.callback_rotated = true;
      phase = 'REMAINDER';
      acceptFinancial(await operation(() => resources.session.refundRemainder(limits), 'session'), false);
      check();
      proof.remainder_confirmed = true;
      proof.provider_verified = true;
      proof.webhook_verified = true;
      proof.journal_verified = true;
      proof.callback_signature_verified = true;
      phase = 'VERIFY';
      const final = exact(await operation(() => resources.session.verifyFinal(limits), 'session'), [
        'priorPartialVerified',
        'priorCallbackDeliveryVerified',
        'remainderConfirmed',
        'providerVerified',
        'webhookVerified',
        'journalVerified',
        'callbackSignatureVerified',
        'finalTotalVerified'
      ]);
      if (Object.values(final).some(value => value !== true)) fail();
      check();
      proof.final_total_verified = true;
    }
  } catch {
    uncertain();
  }
  finally {
    if (!pending && !denied) phase = 'CLEANUP';
    closing = true;
    controller.abort();
    // Cancellation starts immediately; raw bodies/acquisitions remain owned.
    close('session');
    close('cli');
    await drain(httpWork, httpCutoff);
    let httpUnsettled = httpWork.size > 0;
    if (httpUnsettled) uncertain();
    await certify('session', undefined, httpCutoff);
    if (closures.has('session') && !settledClosures.has('session')) httpUnsettled = true;
    await certify('cli', 'listener_closed', Math.min(cleanup.deadline, now() + 10000));
    // Restore starts must settle before stop. Reserve transport time only within
    // the original ceiling; a late settlement still retains its stop obligation.
    await drain(runtimeWork, cleanup.deadline - 20000);
    runtimeReleased = true;
    if (!runtimeWork.size) close('runtime');
    await drain(active, cleanup.deadline - 10000);
    if (runtimeWork.size || active.size) uncertain();
    if (!runtimeWork.size) close('runtime');
    stateReleased = true;
    close('state');
    // State/file closure owns an independent opportunity even if runtime stop
    // remains unsettled. Both raw closures are still awaited to the same ceiling.
    await Promise.all([certify('runtime', httpUnsettled ? undefined : 'runtime_closed'), certify('state')]);
    await drain(all, cleanup.deadline);
    for (const kind of attempted) if (!resources[kind]) uncertain();
    clearTimer(timer);
    input.signal?.removeEventListener('abort', abort);
  }
  if (input.signal?.aborted || now() >= cleanup.deadline || all.size) uncertain();
  remember();
  if (pending && diagnostic) proof.diagnostic = diagnostic;
  const result = pending ? 'PENDING' : denied ? 'FAILED' : 'SUCCEEDED';
  const report = validateManualRefundRemainderReport({...proof, result, phase}) ?? validateManualRefundRemainderReport({...EMPTY, result: 'PENDING', phase: 'CLEANUP'});
  return Object.freeze({report, exitCode: report.result === 'SUCCEEDED' ? 0 : 1});
}

/** Snapshot only public raw data, after every raw flag and zero-argument check. */
export async function runManualRefundRemainderCli(input,deps={}){
  let selected,started;
  try{
    const raw=exact(input,['env','args']);
    if(!Array.isArray(raw.args)||Object.getPrototypeOf(raw.args)!==Array.prototype||Reflect.ownKeys(Object.getOwnPropertyDescriptors(raw.args)).length!==1||Object.getOwnPropertyDescriptor(raw.args,'length').value!==0)fail();
    const env=Object.fromEntries(FLAGS.map(key=>[key,data(raw.env,key)]));
    if(!manualRefundRemainderOptIn(env))fail();
    selected=validateManualRefundRemainderOptions({env,runId:data(raw.env,'PAYMENT_STRIPE_SANDBOX_RUN_ID'),expectedSource:data(raw.env,'PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE'),daemon:data(raw.env,'PAYMENT_STRIPE_SANDBOX_DAEMON')});
    if(selected){started=(deps.now??Date.now)();if(!Number.isSafeInteger(started))selected=null;}
  }catch{selected=null;}
  const controller=new AbortController();let unsubscribe,value=rejected();
  try{
    if(selected){
      const subscribe=deps.subscribe??(stop=>{process.once('SIGINT',stop);process.once('SIGTERM',stop);return ()=>{process.removeListener('SIGINT',stop);process.removeListener('SIGTERM',stop);};});
      unsubscribe=subscribe(()=>controller.abort());
      value=await runManualRefundRemainder(selected,{deadline:started+1800000,signal:controller.signal},deps);
    }
  }catch{value=Object.freeze({report:validateManualRefundRemainderReport({...EMPTY,result:'PENDING'}),exitCode:1});}
  const now=deps.now??Date.now,setTimer=deps.setTimeout??setTimeout,clearTimer=deps.clearTimeout??clearTimeout;
  const deadline=selected?started+1920000:now()+1000;let wrote=false;
  try{
    const text=formatManualRefundRemainderReport(value.report);if(!text||Buffer.byteLength(text)>4096)fail();
    wrote=await new Promise(accept=>{
      let done=false,timer;
      const finish=ok=>{if(done)return;done=true;clearTimer(timer);controller.signal.removeEventListener('abort',cancel);accept(ok);};
      const cancel=()=>finish(false);
      controller.signal.addEventListener('abort',cancel,{once:true});timer=setTimer(cancel,Math.max(1,deadline-now()));
      try{Promise.resolve((deps.write??writer(process.stdout))(text)).then(()=>finish(now()<deadline&&!controller.signal.aborted),cancel);}catch{cancel();}
      if(now()>=deadline||controller.signal.aborted)cancel();
    });
  }catch{}
  finally{try{unsubscribe?.();}catch{wrote=false;}}
  if(wrote)return value;
  return Object.freeze({report:validateManualRefundRemainderReport({...value.report,result:value.report.result==='FAILED'?'FAILED':'PENDING',phase:value.report.result==='FAILED'?'ADMISSION':'CLEANUP'}),exitCode:1});
}
export async function runManualRefundRemainderEntry(input,capabilities,deps={}){
  let code=1;
  try{code=(await runManualRefundRemainderCli(input,Object.create(deps,{write:{value:writer(capabilities.stdout)}}))).exitCode;}catch{}
  capabilities.exit(code);
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))void runManualRefundRemainderEntry({env:process.env,args:process.argv.slice(2)},{stdout:process.stdout,exit:code=>process.exit(code)});
