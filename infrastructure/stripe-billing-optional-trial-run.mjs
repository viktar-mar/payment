import {billingBounded, billingCheck, billingError} from './stripe-billing-limits.mjs';
import {browserCleanupConfirmed, browserDiagnostic} from './stripe-billing-first-payment-browser-diagnostic.mjs';
import {validateBillingOptionalTrialOptions} from './stripe-billing-optional-trial-options.mjs';
import {billingUuid, createBillingOptionalTrialFixture} from './stripe-billing-provision.mjs';
import {parseRefundRetainedJSON as parseJsonWithoutDuplicateKeys} from './stripe-manual-refund-retained.mjs';
import {createOwnedBillingOptionalTrialBootstrap} from './stripe-sandbox-bootstrap.mjs';
import {createOwnedBillingOptionalTrialCliAdapter} from './stripe-sandbox-cli.mjs';
import {preflightOwnedBillingOptionalTrial} from './stripe-sandbox-owned.mjs';

const stages = Object.freeze({
  open:'TRIALING', advance:'PAUSED', 'setup-permit':'WAITING_FOR_SETUP',
  'setup-install':'SETUP_INSTALLED', resume:'OPEN', pay:'PAID',
});
const routes = Object.keys(stages);
const identityKeys = ['runId','clockIntentId','subscriptionId','createOperationId','frozenTime','trialEnd'];
const permitKeys = ['setupIntentId','publishableKey','clientSecret','expiresAt'];
const otpPath = '/api/v1/public/customer-auth/email-otp/challenges';
const need = condition => { if (!condition) throw billingError('OPTIONAL_TRIAL'); };
const fields = (value, keys) => Object.freeze(Object.fromEntries(keys.map(key => [key,value[key]])));
const same = (reference, value) => Object.entries(reference).every(([key,expected]) => value[key] === expected);
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value,key));

const resumeDiagnostics = new Set([
  'OPTIONAL_TRIAL_RESUME_PRECHECK',
  'OPTIONAL_TRIAL_RESUME_OBSERVE_CLOCK',
  'OPTIONAL_TRIAL_RESUME_OBSERVE_SUBSCRIPTION',
  'OPTIONAL_TRIAL_RESUME_OBSERVE_CASH',
  'OPTIONAL_TRIAL_RESUME_CLAIM',
  'OPTIONAL_TRIAL_RESUME_DISPATCH_CLOCK',
  'OPTIONAL_TRIAL_RESUME_STRIPE_INVALID_REQUEST',
  'OPTIONAL_TRIAL_RESUME_STRIPE_BILLING_CYCLE_ANCHOR',
  'OPTIONAL_TRIAL_RESUME_STRIPE_PAYMENT_BEHAVIOR',
  'OPTIONAL_TRIAL_RESUME_STRIPE_PRORATION_BEHAVIOR',
  'OPTIONAL_TRIAL_RESUME_DISPATCH_RESPONSE',
  'OPTIONAL_TRIAL_RESUME_RESPONSE_INVOICE_INVALID',
  'OPTIONAL_TRIAL_RESUME_RESPONSE_INVOICE_ORIGINAL',
  'OPTIONAL_TRIAL_RESUME_RESPONSE_STRUCTURE',
  'OPTIONAL_TRIAL_RESUME_RESPONSE_STATUS',
  'OPTIONAL_TRIAL_RESUME_RESPONSE_OWNER',
  'OPTIONAL_TRIAL_RESUME_RESPONSE_PENDING_UPDATE',
  'OPTIONAL_TRIAL_RESUME_RESPONSE_PERIOD',
  'OPTIONAL_TRIAL_RESUME_RESPONSE_STRIPE',
  'OPTIONAL_TRIAL_RESUME_READ_INVOICE',
  'OPTIONAL_TRIAL_RESUME_FINISH',
  'OPTIONAL_TRIAL_RESUME_RESULT',
  'OPTIONAL_TRIAL_RESUME_PROJECT',
  'OPTIONAL_TRIAL_RESUME_OPEN',
]);
export const isOptionalTrialResumeDiagnostic = code => resumeDiagnostics.has(code);

async function json(fetcher, url, body, headers, status, limits, onResumeStopped) {
  billingCheck(limits);
  const response = await fetcher(url, {method:'POST',redirect:'error',signal:limits.signal,
    headers:{Accept:'application/json','Content-Type':'application/json',...headers},body:JSON.stringify(body)});
  try {
    const stopped = response.status === 400 && onResumeStopped !== undefined;
    need(response.status === status || stopped);
    const maxBytes = stopped ? 256 : 8192;
    let size = 0;
    const chunks = [];
    for await (const chunk of response.body ?? []) {
      billingCheck(limits);
      size += chunk.length;
      need(size <= maxBytes);
      chunks.push(Buffer.from(chunk));
    }
    billingCheck(limits);
    const text = new TextDecoder('utf8',{fatal:true}).decode(Buffer.concat(chunks));
    if (!stopped) return JSON.parse(text);
    let diagnostic;
    try { diagnostic = parseJsonWithoutDuplicateKeys(text); }
    catch { throw billingError('OPTIONAL_TRIAL'); }
    if (exact(diagnostic,['stage','code']) && diagnostic.stage === 'OPTIONAL_TRIAL_STOPPED'
      && isOptionalTrialResumeDiagnostic(diagnostic.code)) onResumeStopped(diagnostic.code);
    throw billingError('OPTIONAL_TRIAL');
  } finally {
    await response.body?.cancel().catch(() => {});
  }
}

function setupPermit(response) {
  const permit = fields(response,permitKeys);
  const {setupIntentId,publishableKey,clientSecret,expiresAt} = permit;
  const expiry = typeof expiresAt === 'string' ? Date.parse(expiresAt) : NaN;
  need(typeof setupIntentId === 'string' && /^seti_[A-Za-z0-9]{1,250}$/.test(setupIntentId)
    && typeof publishableKey === 'string' && /^pk_test_[A-Za-z0-9]{1,250}$/.test(publishableKey)
    && typeof clientSecret === 'string' && clientSecret.length <= 512
    && clientSecret.startsWith(setupIntentId+'_secret_') && /^[A-Za-z0-9_]+$/.test(clientSecret)
    && expiry > Date.now() && expiry <= Date.now()+180000);
  return permit;
}

function runtimeClient(configuration, bootstrap, fetcher) {
  let next = 0, original, setupOperationId, resumeOperationId, resumeFailure, controlKey, terminate, abort;
  return Object.freeze({
    get resumeFailure() { return resumeFailure; },
    async step(route, body, limits) {
      const index = next;
      need(routes[index] === route);
      next = -1;
      let entered = false, result;
      need(await bootstrap.withOptionalTrialRuntime(async ({endpoint,capability}) => {
        need(!entered && endpoint === 'http://127.0.0.1:'+configuration.controlPort
          && typeof capability === 'string' && /^[A-Za-z0-9_-]{43}$/.test(capability));
        entered = true;
        controlKey ??= capability;
        need(controlKey === capability);
        const headers = {Authorization:'Bearer '+capability};
        terminate ??= limit => json(fetcher,endpoint+'/optional-trial/abort',{},headers,200,limit);
        // Bootstrap wraps callback errors. Keep the vetted code within this client.
        const onResumeStopped = route === 'resume' ? code => { resumeFailure = code; } : undefined;
        result = await json(fetcher,endpoint+'/optional-trial/'+route,body,headers,200,limits,onResumeStopped);
        return true;
      },limits) === true && entered);
      billingCheck(limits);
      const permit = route === 'setup-permit';
      const keys = ['stage',...identityKeys,...(index>=2?['setupOperationId']:[]),
        ...(index>=4?['resumeOperationId']:[]),...(permit?permitKeys:[])];
      need(exact(result,keys) && result.stage === stages[route] && result.runId === configuration.runId);
      if (!original) {
        need(['clockIntentId','subscriptionId','createOperationId'].every(key=>billingUuid(result[key]))
          && new Set(['runId','clockIntentId','subscriptionId','createOperationId'].map(key=>result[key])).size === 4
          && Number.isSafeInteger(result.frozenTime) && result.frozenTime>0 && result.trialEnd === result.frozenTime+604800);
        original = fields(result,identityKeys);
      }
      need(same(original,result));
      if (index>=2) {
        setupOperationId ??= result.setupOperationId;
        need(billingUuid(setupOperationId) && setupOperationId !== original.createOperationId
          && result.setupOperationId === setupOperationId);
      }
      if (index>=4) {
        resumeOperationId ??= result.resumeOperationId;
        need(billingUuid(resumeOperationId) && resumeOperationId !== setupOperationId
          && resumeOperationId !== original.createOperationId && result.resumeOperationId === resumeOperationId);
      }
      const output = permit ? setupPermit(result) : fields(result,keys);
      next = index+1;
      return output;
    },
    abort(limits) {
      next = -1;
      return abort ??= (async () => {
        if (!terminate) return true;
        const result = await terminate(limits);
        return exact(result,['stage']) && result.stage === 'QUIESCENT';
      })();
    },
  });
}

async function authenticate(configuration, bootstrap, fixture, fetcher, limits) {
  const binding = fixture.resources();
  const origin = 'http://127.0.0.1:'+configuration.ports.demo;
  const backend = 'http://127.0.0.1:'+configuration.ports.backend;
  need(binding.runId===configuration.runId && binding.expectedSource===configuration.expectedSource
    && binding.endpoints?.backend===backend && binding.endpoints?.demo===origin
    && binding.siteKey==='s5a-'+configuration.runId.replaceAll('-','')
    && ['accountId','siteId','priceId','productId'].every(key=>billingUuid(binding[key]))
    && typeof binding.email==='string' && /^[a-z0-9-]{1,64}@example\.test$/.test(binding.email));
  need(await bootstrap.verify(limits)===true);
  const headers = {Origin:origin};
  const {challenge_id:challenge} = await json(fetcher,backend+otpPath,
    {site_key:binding.siteKey,email:binding.email,intent:'AUTHENTICATE'},headers,202,limits);
  need(billingUuid(challenge));
  let entered = false, session;
  need(await fixture.withOtpCode(async code=>{
    need(!entered && typeof code==='string' && /^\d{6}$/.test(code));
    entered = true;
    need(await bootstrap.verify(limits)===true);
    session = await json(fetcher,backend+otpPath+'/'+challenge+'/verify',{site_key:binding.siteKey,code},headers,200,limits);
    return true;
  },limits)===true && entered && session?.site_id===binding.siteId && session.customer?.status==='ACTIVE'
    && billingUuid(session.customer.id) && Array.isArray(session.scopes)
    && session.scopes.includes('SUBSCRIPTION_READ') && session.scopes.includes('SUBSCRIPTION_MANAGE')
    && typeof session.session_token==='string' && /^[A-Za-z0-9_-]{43}$/.test(session.session_token)
    && Date.parse(session.expires_at)>Date.now());
  const identity = {customerId:session.customer.id,siteId:binding.siteId};
  need(await fixture.acknowledgeCustomer(identity,limits)===true);
  billingCheck(limits);
  return {...identity,accountId:binding.accountId,priceId:binding.priceId,origin,
    customerToken:session.session_token,recurringConsent:true};
}

/** Admission is inert. An explicit, single-use run owns all subsequent capabilities. */
export function createOwnedBillingOptionalTrial(options, dependencies={}) {
  const configuration = validateBillingOptionalTrialOptions(options);
  if (!configuration) throw billingError('OPT_IN_REQUIRED');
  const fetcher = dependencies.fetch ?? globalThis.fetch;
  const lifetime = new AbortController();
  let phase='NEW', failure, accepted=false, claimed=false, closed=false, cleanupConfirmed=false;
  let bootstrap, prepareStarted=false, cli, fixture, runtime, browserCompletion, finished;
  const resources = () => Object.freeze({runId:configuration.runId,expectedSource:configuration.expectedSource,
    scope:'OPTIONAL_TRIAL_PAUSE_RESUME',status:closed?'CLOSED':claimed?'RUNNING':'NEW',phase,code:failure??null,
    optionalTrialAccepted:accepted,initialAccepted:false,full5bAccepted:false,cleanupConfirmed});
  const finish = async () => {
    lifetime.abort();
    let ok = true;
    const limits = {deadline:Date.now()+120000};
    if (browserCompletion && !await billingBounded(limits,()=>browserCompletion,25000).catch(()=>false)) ok=false;
    for (const [owner,method] of [[runtime,'abort'],[fixture,'close'],[cli,'stopListener'],[prepareStarted?bootstrap:null,'stop']]) {
      if (owner && await billingBounded(limits,l=>owner[method](l),30000).catch(()=>false)!==true) ok=false;
    }
    cleanupConfirmed = ok;
    if(!ok)failure ??= 'CLEANUP';
  };
  return Object.freeze({
    resources,
    async run(browser, limits) {
      if (claimed) throw billingError('SINGLE_USE');
      claimed = true;
      const abort = () => lifetime.abort();
      let timer, resolveDone;
      finished = new Promise(resolve=>{resolveDone=resolve;});
      try {
        billingCheck(limits);
        need(typeof browser==='function' && limits.deadline-Date.now()<=2700000);
        limits.signal?.addEventListener('abort',abort,{once:true});
        timer = setTimeout(abort,limits.deadline-Date.now());
        const active = {deadline:limits.deadline,signal:lifetime.signal};
        const step = (name, action, timeout) => {
          phase = name;
          return billingBounded(active,action,timeout);
        };
        const requireStep = (name, action, timeout) => step(name,async child=>{ need(await action(child)===true); },timeout);
        const preflight = await step('PREFLIGHT',l=>dependencies.preflight
          ?dependencies.preflight(configuration,l):preflightOwnedBillingOptionalTrial(configuration,{},l),120000);
        need(preflight===true || preflight?.status==='READY');
        bootstrap = await step('BOOTSTRAP',()=>(dependencies.createBootstrap??createOwnedBillingOptionalTrialBootstrap)(configuration));
        cli = await step('CLI',()=>(dependencies.createCli??createOwnedBillingOptionalTrialCliAdapter)({
          configuration,cliDirectory:bootstrap.resources().cliDirectory,
          verifyForwardTarget:bootstrap.verifyForwardTarget,persistWebhookSecret:bootstrap.persistWebhookSecret,
        }));
        const profile = await step('CLI_PROFILE',()=>cli.checkSavedProfile(active),10000);
        if(profile==='EXPIRED'){
          failure='CLI_PROFILE_EXPIRED';
          throw billingError(failure);
        }
        need(profile==='READY');
        await requireStep('PREPARE',()=>{
          prepareStarted=true;
          return bootstrap.prepare(active);
        },2700000);
        await requireStep('BINDING',()=>cli.validateBinding(active),120000);
        await requireStep('LISTENER',()=>cli.startListener(active),120000);
        await requireStep('START',()=>bootstrap.start(active),2700000);
        fixture = await step('FIXTURE',()=>(dependencies.createFixture??createBillingOptionalTrialFixture)({configuration,bootstrap,cli}));
        await requireStep('PROVISION',l=>fixture.provision(l),300000);
        runtime = runtimeClient(configuration,bootstrap,fetcher);
        const opening = await step('CUSTOMER',l=>authenticate(configuration,bootstrap,fixture,fetcher,l),120000);
        const command = (name,route,body={})=>step(name,async child=>{
          need(await cli.assertBillingListener(child)===true);
          return runtime.step(route,body,child);
        },180000);
        try { await command('OPEN_TRIAL','open',opening); }
        finally { opening.customerToken = ''; }
        const paused = await command('ADVANCE','advance');
        const evidence = await step('EVIDENCE',async l=>{
          const create = dependencies.createEvidence
            ?? (await import('./stripe-billing-optional-trial-runtime-evidence.mjs')).createOptionalTrialRuntimeEvidence;
          return create({configuration,bootstrap,fixture},l);
        });
        await requireStep('PAUSE_PROOF',l=>evidence.pause(paused,l),120000);
        const permit = await command('SETUP_PERMIT','setup-permit');
        await requireStep('BROWSER',l=>{
          const task = Promise.resolve().then(()=>browser(permit,l));
          browserCompletion = task.then(()=>true,error=>browserCleanupConfirmed(error));
          return task;
        },180000);
        await command('SETUP_INSTALL','setup-install');
        const pending = await command('RESUME','resume');
        await requireStep('OPEN_PROOF',l=>evidence.pending(pending,l),120000);
        const paid = await command('PAY','pay');
        await requireStep('PAID_PROOF',l=>evidence.paid(paid,l),120000);
        billingCheck(active);
      } catch(error) {
        if(phase==='BROWSER')failure ??= browserDiagnostic(error,'ACCEPTANCE');
        if(phase==='RESUME')failure ??= runtime?.resumeFailure;
        failure ??= phase==='NEW'?'LIMITS':phase;
      } finally {
        clearTimeout(timer);
        limits?.signal?.removeEventListener('abort',abort);
        await finish();
        if(!failure){accepted=true;phase='COMPLETE';}
        closed = true;
        resolveDone();
      }
      if(failure)throw billingError(failure);
      return resources();
    },
    async close() {
      lifetime.abort();
      if (finished) await finished;
      else if (!claimed) {claimed=true;await finish();closed=true;}
      return resources();
    },
  });
}
