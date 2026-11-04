import test, {before, after} from 'node:test';
const originalFetch=globalThis.fetch;let transportCalls=0;
before(()=>{globalThis.fetch=()=>{transportCalls++;throw new Error('offline test transport blocked');};});
after(()=>{globalThis.fetch=originalFetch;assert.equal(transportCalls,0);});
import assert from 'node:assert/strict';
import {runInNewContext} from 'node:vm';

// Local audit: Node builtins, inert source imports and an injected Route/BrowserPort only.
// No listener, browser, build, process launch, private input or network dependency.
const moduleUrl = new URL('./stripe-billing-first-payment.ts', import.meta.url).href;
const id = (n: string) => n.repeat(8) + '-' + n.repeat(4) + '-4' + n.repeat(3) + '-8' + n.repeat(3) + '-' + n.repeat(12);
const token = 'T'.repeat(43);
const binding = {siteId: id('1'), customerId: id('2'), priceId: id('3'), endpoints: {backend: 'http://127.0.0.1:18080', demo: 'http://127.0.0.1:14173'}};
const prepared = () => ({phase: 'PREPARED', code: 'OK', run_id: id('4'), account_id: id('5'), site_id: binding.siteId, customer_id: binding.customerId, price_id: binding.priceId, subscription_id: id('6'), operation_id: id('7'), action: {kind: 'PROVIDER_CLIENT', secret: 'G'.repeat(43), expires_at: new Date(Date.now() + 60000).toISOString()}, session: {protocol: 'SERVER_CONFIRM_V2', provider: 'stripe', flow: 'PAYMENT', publishable_key: 'pk_test_fixture', client_secret: 'pi_fixture_secret_fixture', action_token: null, attempt_id: id('8'), capability: 'bca2_' + 'C'.repeat(43), expires_at: new Date(Date.now() + 50000).toISOString()}});
function route(url: string, method = 'GET', value?: unknown, headerPatch = {}) {
  let response: any;
  return {
    request: () => ({url: () => url, method: () => method, redirectedFrom: () => null, postData: () => value === undefined ? null : JSON.stringify(value), allHeaders: async () => ({authorization: 'Bearer ' + token, origin: binding.endpoints.demo, 'content-type': 'application/json', 'idempotency-key': 'sdk-original-key', ...headerPatch})}),
    fulfill: async (data: any) => {response = data.json;},
    response: () => response,
  };
}
async function fixture() {
  const module = await import(moduleUrl);
  const p = prepared();
  const original = `${binding.endpoints.backend}/api/v1/customer/subscriptions/${p.subscription_id}/operations/${p.operation_id}`;
  const calls: unknown[] = [];
  const confirmed = {...p, phase: 'OBSERVE', protocol: 'SERVER_CONFIRM_V2', attempt_id: p.session.attempt_id, state: 'OBSERVE', retry_allowed: false, successor_attempt_id: null, next_action_allowed: false, expires_at: p.session.expires_at};
  const port = {confirm: async (value: unknown) => {calls.push(value); return confirmed;}};
  const limits = {deadline: Date.now() + 60000};
  const bridge = module.createFirstPaymentBridge({binding, prepared: p, token, port, limits});
  return {bridge, p, original, calls, confirmed, limits};
}
async function select(f: Awaited<ReturnType<typeof fixture>>) {
  await f.bridge(route(f.original + '/action'));
  await f.bridge(route(f.original + '/confirmation-session', 'POST', {action_token: f.p.action.secret}));
}
test('bridge fulfills original action and selected session without second backend selection', async () => {
  const f = await fixture();
  const action = route(f.original + '/action');
  await f.bridge(action);
  assert.deepEqual(action.response(), f.p.action);
  const session = route(f.original + '/confirmation-session', 'POST', {action_token: f.p.action.secret});
  await f.bridge(session);
  assert.deepEqual(Object.keys(session.response()).sort(), ['attempt_id','capability','client_secret','expires_at','flow','protocol','provider','publishable_key']);
  assert.equal(session.response().capability, f.p.session.capability);
  assert.equal(f.calls.length, 0);
});
test('bridge forwards original SDK attempt, capability, token and key exactly once', async () => {
  const f = await fixture();
  await select(f);
  const request = () => route(f.original + '/confirmation-attempts/' + f.p.session.attempt_id + '/confirm', 'POST', {capability: f.p.session.capability, confirmation_token: 'ctoken_fixture'});
  const r = request();
  await f.bridge(r);
  assert.deepEqual(f.calls, [{attempt_id: f.p.session.attempt_id, capability: f.p.session.capability, confirmation_token: 'ctoken_fixture', idempotency_key: 'sdk-original-key'}]);
  assert.deepEqual(r.response(), {protocol:'SERVER_CONFIRM_V2',attempt_id:f.p.session.attempt_id,state:'OBSERVE',retry_allowed:false,successor_attempt_id:null,next_action_allowed:false,expires_at:f.p.session.expires_at});
  await assert.rejects(f.bridge(request()), /^Error: first_payment_BROWSER$/);
  assert.equal(f.calls.length, 1);
});
test('wrong identity, authorization, body, key or order consumes bridge authority', async () => {
  for (const bad of ['identity','authority','field','key','order']) {
    const f = await fixture();
    await select(f);
    const r = route(f.original + '/confirmation-attempts/' + (bad === 'identity' ? id('9') : f.p.session.attempt_id) + '/confirm', 'POST', {capability:f.p.session.capability,confirmation_token:'ctoken_fixture',...(bad === 'field' ? {extra:true} : {})}, bad === 'authority' ? {authorization:'Bearer foreign'} : bad === 'key' ? {'idempotency-key':''} : {});
    await assert.rejects(f.bridge(bad === 'order' ? route(f.original + '/action') : r), /^Error: first_payment_BROWSER$/);
    await assert.rejects(f.bridge(route(f.original + '/action')));
    assert.equal(f.calls.length, 0);
  }
});
test('3DS, altered attempt, expiry, abort and lost acknowledgement never grant next action or retry', async () => {
  for (const bad of ['3ds','attempt','expiry','abort','lost']) {
    const f = await fixture();
    await select(f);
    if (bad === '3ds') Object.assign(f.confirmed,{state:'REQUIRES_ACTION',next_action_allowed:true});
    if (bad === 'attempt') f.confirmed.attempt_id = id('9');
    if (bad === 'expiry') f.limits.deadline = Date.now() - 1;
    if (bad === 'abort') (f.limits as any).signal = AbortSignal.abort();
    if (bad === 'lost') Object.defineProperty(f.confirmed, 'state', {get() {throw Error('secret-sentinel');}});
    const r = route(f.original + '/confirmation-attempts/' + f.p.session.attempt_id + '/confirm','POST',{capability:f.p.session.capability,confirmation_token:'ctoken_fixture'});
    await assert.rejects(f.bridge(r), /^Error: first_payment_BROWSER$/);
    assert.equal(r.response(), undefined);
    await assert.rejects(f.bridge(r));
    assert.equal(f.calls.length <= 1, true);
  }
});

test('dedicated discovery and reporter suppress artifacts, raw errors and arbitrary output', async () => {
  const {readFile} = await import('node:fs/promises');
  const {runInNewContext} = await import('node:vm');
  async function configuration(file: string) {
    const source = await readFile(new URL('../../' + file, import.meta.url), 'utf8');
    return runInNewContext(source.replace(/^import .*?;\s*/m, '').replace('export default ', 'result = '), {defineConfig:(value:unknown)=>value,devices:{'Desktop Chrome':{}},process:{env:{}},result:null});
  }
  const dedicated = await configuration('playwright.stripe-billing-first-payment.config.ts');
  const normal = await configuration('playwright.config.ts');
  assert.equal(dedicated.testMatch,'stripe-billing-first-payment.spec.ts');
  assert.equal(dedicated.workers,1);
  assert.equal(dedicated.retries,0);
  assert.equal(dedicated.webServer,undefined);
  assert.equal(dedicated.preserveOutput,'never');
  assert.deepEqual(Object.values(dedicated.use),['off','off','off']);
  assert.ok(normal.testIgnore.includes(dedicated.testMatch));
  assert.ok(normal.testIgnore.includes('**/support/stripe-billing-first-payment*.test.ts'));
  const {default:Reporter} = await import(new URL('./stripe-billing-first-payment-reporter.ts',import.meta.url).href);
  const original = process.stdout.write;
  const lines: string[] = [];
  process.stdout.write = ((value: unknown) => {lines.push(String(value));return true;}) as typeof process.stdout.write;
  try {
    const reporter = new Reporter();
    reporter.onStdOut('secret-sentinel\n');
    reporter.onStdErr('secret-sentinel');
    reporter.onError({get message(){throw Error('must not read');}});
    reporter.onTestEnd({title:'secret-sentinel'}, {status:'failed',errors:['secret-sentinel'],attachments:['secret-sentinel']});
    reporter.onEnd({status:'failed'});
    assert.deepEqual(lines,['first_payment_setup_failed\n','owned_initial_payment_not_accepted\n','owned_initial_payment_result_not_accepted full5b=NOT_ACCEPTED\n']);
  } finally {process.stdout.write = original;}
});


test('browser runner sanitizes early page failure and closes the opened context', async () => {
  const module = await import(moduleUrl);
  let closes = 0;
  let portReads = 0;
  const context = {
    newPage: async () => {throw new Error('private-provider-sentinel');},
    close: async () => {closes++;},
  };
  const browser = {newContext: async () => context};
  const port = new Proxy({}, {get() {portReads++;throw new Error('unexpected port access');}});
  await assert.rejects(
    module.runFirstPaymentBrowser(browser, port, {deadline:Date.now() + 1000}),
    /^Error: first_payment_BROWSER$/,
  );
  assert.equal(closes, 1);
  assert.equal(portReads, 0);
});

test('browser page checkpoint survives sanitization and terminal formatting', async () => {
  const module = await import(moduleUrl);
  const {formatBillingFirstPaymentDiagnostic} = await import('../../../../infrastructure/stripe-billing-first-payment.mjs');
  let error: unknown;
  try {await module.runFirstPaymentBrowser({newContext:async()=>({newPage:async()=>{throw null;},close:async()=>{}})}, {}, {deadline:Date.now()+1000});} catch (value) {error=value;}
  // The final formatter must recognize the literal closed checkpoint.
  assert.equal(formatBillingFirstPaymentDiagnostic({runId:id('1'),expectedSource:'e'.repeat(40),scope:'OWNED_INITIAL_PAYMENT',full5bAccepted:false,phase:'BROWSER',status:'CLOSED',initialAccepted:false,code:'BROWSER',diagnostic:'BROWSER_PAGE',cleanupConfirmed:true}), 'owned_initial_payment_diagnostic code=BROWSER_PAGE cleanup=CONFIRMED');
  assert.match(String(error), /^Error: first_payment_BROWSER$/);
});

// Inert original-flow fixture: terminal evaluation runs only in a VM; no browser or transport.
async function journeyFixture(fault = '', options: {onPrepare?:()=>void;holdPrepare?:boolean;onObserveWait?:()=>void;onAcknowledge?:()=>void;holdAcknowledge?:boolean;pending?:unknown[];pendingSource?:'frame'|'origin';prePending?:unknown[];latePending?:unknown[];paySignal?:unknown;paySignalSource?:'frame'|'origin';latePaySignal?:unknown;heldConfirm?:boolean;closeReject?:boolean;stopReady?:boolean;stopObserve?:boolean;postalVisible?:boolean;lateOpen?:boolean;payRace?:'bridge-first'|'pay-first';snapshot?:unknown;reader?:'missing'|'throwing'|'accessor'|'writable'|'configurable'|'enumerable'|'nonfunction';snapshotOrigin?:string;wrongTop?:boolean;skipPrepare?:boolean;lateOrigin?:boolean;lateEvaluation?:boolean;bridgeDuringEvaluation?:boolean} = {}) {
  const module=await import(moduleUrl);
  const provenance=await import('../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs');
  const p=prepared(),calls:string[]=[],b={...binding,siteKey:'fixture-site',email:'fixture@example.test'};
  let releaseAcknowledgement:(()=>void)|undefined;let scopedSignal:AbortSignal|undefined,lateWork:Promise<void>|undefined; let pendingCallback:any,paySignalCallback:any; let pendingOrigin=false;let phase='',bindingCallback:any,routeCallback:any,navigation:any,closed=false,releaseOpen:any,releaseHeaders:undefined|(()=>void);
  const hit=(name:string)=>{calls.push(name);if(fault===name)throw new Proxy({}, {get(){throw Error('foreign value read');}});};
  const frame={url:()=>(pendingOrigin||options.lateOrigin&&phase==='OBSERVE')?'https://foreign.test/':b.endpoints.demo+'/billing-first-payment.html'};
  const requestRoute=(url:string,method='GET',body?:unknown,wrong=false)=>{
    const r=route(url,method,body);
    if(wrong){const request=r.request;r.request=()=>({...request(),allHeaders:async()=>{
      if(options.payRace==='pay-first')await new Promise<void>(resolve=>{releaseHeaders=resolve;});
      return {get authorization(){calls.push('bridge_validation_observed');return 'Bearer foreign';},origin:b.endpoints.demo,'content-type':'application/json','idempotency-key':'sdk-original-key'};
    }});}
    return {...r,fallback:async()=>{},abort:async()=>{calls.push('route-abort');}};
  };
  const original=`${b.endpoints.backend}/api/v1/customer/subscriptions/${p.subscription_id}/operations/${p.operation_id}`;
  const otp=b.endpoints.backend+'/api/v1/public/customer-auth/email-otp/challenges';
  const selectBridge=async()=>{await routeCallback(requestRoute(original+'/action'));await routeCallback(requestRoute(original+'/confirmation-session','POST',{action_token:p.action.secret}));};
  const page:any={mainFrame:()=>frame,on:(_event:string,cb:any)=>{navigation=cb;},
    goto:async()=>{hit('NAVIGATION');navigation(frame);},
    waitForFunction:async(_fn:any,expected:string)=>{if(expected==='READY'&&fault==='SECOND_NAVIGATION')navigation(frame);if(closed)throw null;if(expected)hit('WAIT_'+expected);else {if(phase==='OBSERVE')options.onObserveWait?.();hit(phase==='OBSERVE'?'WAIT_OBSERVE':'CONFIGURE_WAIT');}},
    evaluate:async(fn:any,value:any)=>{
      if(typeof value!=='string'){hit('CONFIGURE');return;}
      calls.push('terminal-evaluate');
      if(options.lateEvaluation)await new Promise<void>(resolve=>{releaseHeaders=resolve;});
      if(options.bridgeDuringEvaluation)await routeCallback(requestRoute(original+'/action'));
      const window:any={};window.top=options.wrongTop?{}:window;
      const reader=()=>{calls.push('reader');if(options.reader==='throwing')throw null;return options.snapshot;};
      if(options.reader!=='missing')Object.defineProperty(window,'__firstPaymentFailure',options.reader==='accessor'?{get(){calls.push('accessor');return reader;}}:{value:options.reader==='nonfunction'?'SUBMIT':reader,writable:options.reader==='writable',configurable:options.reader==='configurable',enumerable:options.reader==='enumerable'});
      return runInNewContext('('+fn.toString()+')(origin)',{window,location:{origin:options.snapshotOrigin??b.endpoints.demo},document:{body:{dataset:{phase:options.stopObserve?'STOPPED':phase}}},origin:value});
    },
    locator:(selector:string)=>selector==='body'?{getAttribute:async()=>(options.stopReady&&phase==='READY'||options.stopObserve&&phase==='OBSERVE')?'STOPPED':phase}:{frameLocator:()=>({locator:(s:string)=>({fill:async()=>hit(s.includes('number')?'CARD_NUMBER':s.includes('expiry')?'CARD_EXPIRY':s.includes('cvc')?'CARD_CVC':'POSTAL_FILL'),isVisible:async()=>{hit('POSTAL_CHECK');return options.postalVisible!==false;}})})},
    getByRole:(_role:string,{name}:any)=>({evaluate:async()=>true,check:async()=>{},isEnabled:async()=>{for(const value of options.prePending??[])pendingCallback?.({frame},value);return true;},click:async()=>{
      if(name==='Request code'){hit('OTP_REQUEST');await routeCallback(requestRoute(otp,'POST',{site_key:b.siteKey,email:b.email,intent:'AUTHENTICATE'}));phase='CODE';}
      if(name==='Verify code'){hit('OTP_VERIFY');await routeCallback(requestRoute(otp+'/'+id('9')+'/verify','POST',{}));phase='AUTHENTICATED';}
      if(name==='Start payment'&&options.skipPrepare){phase='READY';return;}
      if(name==='Start payment'){await bindingCallback({frame},{token,origin:b.endpoints.demo,customerId:b.customerId,siteId:b.siteId});await selectBridge();phase='READY';}
      if(name==='Pay'){
        hit('PAY_CLICK');
        if(options.paySignal !== undefined) paySignalCallback?.({frame:options.paySignalSource==='frame'?{url:()=>frame.url()}:options.paySignalSource==='origin'?{url:()=> 'https://foreign.test/'}:frame},options.paySignal);
        pendingOrigin=options.pendingSource==='origin';
        for(const value of options.pending??[])pendingCallback?.({frame:options.pendingSource==='frame'?{url:()=>frame.url()}:frame},value);
        pendingOrigin=false;
        const confirmation=()=>requestRoute(original+'/confirmation-attempts/'+p.session.attempt_id+'/confirm','POST',{capability:p.session.capability,confirmation_token:'ctoken_fixture'},!!options.payRace);
        if(options.payRace){void routeCallback(confirmation());throw null;}
        if(options.heldConfirm){const r=confirmation(),request=r.request;r.request=()=>({...request(),allHeaders:async()=>{await new Promise<void>(resolve=>{releaseHeaders=resolve;});return request().allHeaders();}});lateWork=routeCallback(r);await Promise.resolve();}
        if(fault!=='WAIT_OBSERVE'&&!options.stopObserve)await routeCallback(confirmation());phase='OBSERVE';
      }
    }}),
  };
  const context:any={newPage:async()=>{hit('PAGE');return page;},exposeBinding:async(name:string,cb:any)=>{hit('SETUP');if(name==='__firstPaymentPending'){pendingCallback=cb;return;}if(name==='__firstPaymentPaySignal'){paySignalCallback=cb;return;}assert.equal(name,'__firstPaymentPrepare');bindingCallback=cb;},route:async(_url:string,cb:any)=>{routeCallback=async(r:any)=>{if(fault==='ACTION_BRIDGE'&&r.request().url().endsWith('/action'))r.fulfill=async()=>{throw null;};if(fault==='SESSION_BRIDGE'&&r.request().url().endsWith('/confirmation-session'))r.fulfill=async()=>{throw null;};if(fault==='CONFIRM_BRIDGE'&&r.request().url().endsWith('/confirm'))r.fulfill=async()=>{throw null;};return cb(r);};},close:async()=>{calls.push('close');closed=true;releaseAcknowledgement?.();if(scopedSignal)calls.push(scopedSignal.aborted?'scoped-aborted':'scoped-live');for(const value of options.latePending??[])pendingCallback?.({frame},value);if(options.latePaySignal!==undefined)paySignalCallback?.({frame},options.latePaySignal);releaseHeaders?.();if(options.closeReject)throw null;}};
  const browser:any={newContext:()=>{hit('CONTEXT');return options.lateOpen?new Promise(r=>releaseOpen=r):Promise.resolve(context);}};
  const port:any={resources:()=>b,acknowledgeCustomer:async()=>{calls.push('acknowledge');options.onAcknowledge?.();if(options.holdAcknowledge)await new Promise<void>(resolve=>{releaseAcknowledgement=resolve;});return true;},prepare:async(_value:any,_key:any,limits:any)=>{scopedSignal=limits.signal;hit('PREPARE');options.onPrepare?.();if(options.holdPrepare)await new Promise<void>(resolve=>{releaseAcknowledgement=resolve;});return p;},withOtpCode:async(cb:any)=>cb('123456'),confirm:async()=>{calls.push('confirm');return {...p,phase:'OBSERVE',protocol:'SERVER_CONFIRM_V2',attempt_id:p.session.attempt_id,state:'OBSERVE',retry_allowed:false,successor_attempt_id:null,next_action_allowed:false,expires_at:p.session.expires_at};}};
  return {run:(limits:any={deadline:Date.now()+1000})=>module.runFirstPaymentBrowser(browser,port,limits),calls,provenance,releaseOpen:()=>releaseOpen(context),drain:()=>lateWork};
}
test('independent confirm bridge failure wins before concurrent Pay rejection propagates',async()=>{
  const f=await journeyFixture('',{payRace:'bridge-first'});let failure;try{await f.run();}catch(error){failure=error;}
  assert.equal(f.calls.filter(x=>x==='bridge_validation_observed').length,1);assert.equal(f.calls.filter(x=>x==='PREPARE').length,1);assert.equal(f.calls.filter(x=>x==='confirm').length,0);assert.equal(f.calls.filter(x=>x==='close').length,1);
  assert.equal(String(failure),'Error: first_payment_BROWSER');assert.equal(f.provenance.browserDiagnostic(failure),'BROWSER_CONFIRM_BRIDGE');
});
test('earlier Pay rejection survives an independently pending confirm bridge failure',async()=>{
  const f=await journeyFixture('',{payRace:'pay-first'});let failure;try{await f.run();}catch(error){failure=error;}
  assert.equal(f.calls.filter(x=>x==='bridge_validation_observed').length,1);assert.equal(f.calls.filter(x=>x==='PREPARE').length,1);assert.equal(f.calls.filter(x=>x==='confirm').length,0);assert.equal(f.calls.filter(x=>x==='close').length,1);
  assert.ok(f.calls.indexOf('close')<f.calls.indexOf('bridge_validation_observed'));assert.equal(String(failure),'Error: first_payment_BROWSER');assert.equal(f.provenance.browserDiagnostic(failure),'BROWSER_PAY_CLICK');
});
for(const [fault,code] of [['CONTEXT','BROWSER_CONTEXT'],['PAGE','BROWSER_PAGE'],['SETUP','BROWSER_SETUP'],['NAVIGATION','BROWSER_NAVIGATION'],['CONFIGURE','BROWSER_CONFIGURE'],['OTP_REQUEST','BROWSER_OTP_REQUEST'],['OTP_VERIFY','BROWSER_OTP_VERIFY'],['PREPARE','BROWSER_PREPARE'],['WAIT_READY','BROWSER_WAIT_READY'],['CARD_NUMBER','BROWSER_CARD_NUMBER'],['CARD_EXPIRY','BROWSER_CARD_EXPIRY'],['CARD_CVC','BROWSER_CARD_CVC'],['POSTAL_CHECK','BROWSER_POSTAL_CHECK'],['POSTAL_FILL','BROWSER_POSTAL_FILL'],['PAY_CLICK','BROWSER_PAY_CLICK'],['WAIT_OBSERVE','BROWSER_WAIT_OBSERVE_MARKER_MISSING'],['ACTION_BRIDGE','BROWSER_ACTION_BRIDGE'],['SESSION_BRIDGE','BROWSER_SESSION_BRIDGE'],['CONFIRM_BRIDGE','BROWSER_CONFIRM_BRIDGE'],['SECOND_NAVIGATION','BROWSER_NAVIGATION']])test('inert original journey retains literal '+code+' for '+fault,async()=>{
 const f=await journeyFixture(fault!);let e;try{await f.run();}catch(error){e=error;}
 assert.equal(String(e),'Error: first_payment_BROWSER');assert.equal(f.provenance.browserDiagnostic(e),code);
 assert.equal(f.calls.filter(x=>x==='close').length,fault==='CONTEXT'?0:1);
 if(['WAIT_READY','CARD_NUMBER','CARD_EXPIRY','CARD_CVC','POSTAL_CHECK','POSTAL_FILL','WAIT_OBSERVE'].includes(fault!)){assert.equal(f.calls.filter(x=>x==='PREPARE').length,1);assert.equal(f.calls.filter(x=>x==='confirm').length,0);}
});
test('STOPPED READY preserves checkpoint without confirm and successful original flow confirms once',async()=>{
 const f=await journeyFixture('',{stopReady:true});let e;try{await f.run();}catch(error){e=error;}assert.equal(f.provenance.browserDiagnostic(e),'BROWSER_WAIT_READY');assert.equal(f.calls.filter(x=>x==='confirm').length,0);assert.equal(f.calls.filter(x=>x==='PREPARE').length,1);
 const success=await journeyFixture();assert.equal(await success.run(),true);assert.equal(success.calls.filter(x=>x==='confirm').length,1);assert.equal(success.calls.filter(x=>x==='close').length,1);
});
test('synthetic validation STOPPED at OBSERVE cannot confirm or accept',async()=>{
 const f=await journeyFixture('',{stopObserve:true});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE');assert.equal(f.calls.filter(x=>x==='PREPARE').length,1);assert.equal(f.calls.filter(x=>x==='confirm').length,0);assert.equal(f.calls.filter(x=>x==='close').length,1);
});
test('invisible optional postal field leaves original successful route sequence intact',async()=>{
 const f=await journeyFixture('',{postalVisible:false});assert.equal(await f.run(),true);assert.equal(f.calls.includes('POSTAL_FILL'),false);assert.equal(f.calls.filter(x=>x==='confirm').length,1);assert.equal(f.calls.filter(x=>x==='close').length,1);
});
for(const fault of ['CARD_NUMBER',''])test('rejecting close preserves '+(fault||'successful body')+' primary and cleanup uncertainty',async()=>{
 const f=await journeyFixture(fault,{closeReject:true});let e;try{await f.run();}catch(error){e=error;}assert.equal(String(e),'Error: first_payment_BROWSER');assert.equal(f.provenance.browserDiagnostic(e),fault?'BROWSER_CARD_NUMBER':'BROWSER_CLOSE');assert.equal(f.provenance.browserCleanupConfirmed(e),false);assert.equal(f.calls.filter(x=>x==='close').length,1);
});
for(const code of ['BROWSER_CONTEXT','BROWSER_PAGE','BROWSER_SETUP','BROWSER_NAVIGATION','BROWSER_CONFIGURE','BROWSER_OTP_REQUEST','BROWSER_OTP_VERIFY','BROWSER_PREPARE','BROWSER_ACTION_BRIDGE','BROWSER_SESSION_BRIDGE','BROWSER_CONFIRM_BRIDGE','BROWSER_WAIT_READY','BROWSER_CARD_NUMBER','BROWSER_CARD_EXPIRY','BROWSER_CARD_CVC','BROWSER_POSTAL_CHECK','BROWSER_POSTAL_FILL','BROWSER_PAY_CLICK','BROWSER_WAIT_OBSERVE','BROWSER_WAIT_OBSERVE_PAY_SIGNAL_OBSERVED_MARKER_MISSING','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_SUBMIT','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_TOKEN','BROWSER_CLOSE','BROWSER_CONFIRM_GUARD','BROWSER_CONFIRM_STATE','BROWSER_CONFIRM_SUBMIT_VALIDATION','BROWSER_CONFIRM_SUBMIT','BROWSER_CONFIRM_TOKEN_VALIDATION','BROWSER_CONFIRM_TOKEN','BROWSER_CONFIRM_CLIENT','BROWSER_CONFIRM_NEXT_ACTION','BROWSER_CONFIRM_RETIRED','BROWSER_CONFIRM_RECEIPT'])test('reporter transports literal '+code+' exactly once',async()=>{
 const {default:Reporter}=await import(new URL('./stripe-billing-first-payment-reporter.ts',import.meta.url).href);
 const {formatBillingFirstPaymentDiagnostic,parseBillingFirstPaymentDiagnosticLine}=await import(new URL('../../../../infrastructure/stripe-billing-first-payment.mjs',import.meta.url).href);
 const line=formatBillingFirstPaymentDiagnostic({status:'CLOSED',initialAccepted:false,code:'BROWSER',diagnostic:code,cleanupConfirmed:true});assert.equal(line,'owned_initial_payment_diagnostic code='+code+' cleanup=CONFIRMED');assert.equal(parseBillingFirstPaymentDiagnosticLine(line),line);assert.ok(line.length<160);
 const original=process.stdout.write,lines:string[]=[];process.stdout.write=((value:unknown)=>{lines.push(String(value));return true;}) as typeof process.stdout.write;
 try{const reporter=new Reporter();for(const bad of [code+'_ALIAS','PREFIX_'+code,code+'1','BROWSER_UNKNOWN','sk_test_SECRET'])reporter.onStdOut('owned_initial_payment_diagnostic code='+bad+' cleanup=CONFIRMED\n');reporter.onStdOut(line+' extra\n');reporter.onStdOut('x'.repeat(4097)+line+'\n');reporter.onStdOut(line.slice(0,31));reporter.onStdOut(line.slice(31)+'\n');reporter.onStdOut(line+'\n');reporter.onError(new Proxy({},{get(){throw null;}}));assert.deepEqual(lines,[line+'\n','first_payment_setup_failed\n']);}finally{process.stdout.write=original;}
});
test('aborted context opening closes once after late resolution without restoring success',async()=>{
 const f=await journeyFixture('',{lateOpen:true}),controller=new AbortController();const running=f.run({deadline:Date.now()+1000,signal:controller.signal});const rejected=assert.rejects(running,/first_payment_BROWSER/);controller.abort();await rejected;f.releaseOpen();await Promise.resolve();await Promise.resolve();assert.equal(f.calls.filter(x=>x==='close').length,1);
});
test('runner close timeout retains the first checkpoint and cannot gain late cleanup proof',async t=>{
 t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});const module=await import(moduleUrl),meta=await import('../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs');let enter:any,release:any,closes=0;const entered=new Promise(r=>enter=r);
 const context={newPage:async()=>{throw null;},close:()=>{closes++;enter();return new Promise<void>(r=>release=r);}};
 const running=module.runFirstPaymentBrowser({newContext:async()=>context},{},{deadline:100000});let failure:unknown;const settled=running.catch((error:unknown)=>{failure=error;});await entered;t.mock.timers.tick(15000);await settled;
 assert.equal(meta.browserDiagnostic(failure),'BROWSER_PAGE');assert.equal(meta.browserCleanupConfirmed(failure),false);release();for(let i=0;i<10;i++)await Promise.resolve();assert.equal(meta.browserCleanupConfirmed(failure),false);assert.equal(closes,1);
});

test('reporter forwards one strict final diagnostic after CLI handoff and drops hostile suffixes', async () => {
  const {default:Reporter}=await import(new URL('./stripe-billing-first-payment-reporter.ts',import.meta.url).href);
  const original=process.stdout.write,lines:string[]=[];process.stdout.write=((v:unknown)=>{lines.push(String(v));return true;}) as typeof process.stdout.write;
  try {
    const reporter=new Reporter();const handoff='Authorize the isolated Sandbox CLI at https://access.stripe.com/fixture using code ABCD EFGH. Keep this terminal open.';
    reporter.onStdOut(handoff+'\n');reporter.onStdOut(handoff+'\n');
    for(const line of ['owned_initial_payment_diagnostic code=UNKNOWN cleanup=CONFIRMED','owned_initial_payment_diagnostic code=PREPARE_ACK cleanup=CONFIRMED run_id=private-sentinel','owned_initial_payment_diagnostic code=PREPARE_ACK cleanup=private-sentinel','x'.repeat(4097)+'owned_initial_payment_diagnostic code=PREPARE_ACK cleanup=CONFIRMED'])reporter.onStdOut(line+'\n');
    reporter.onStdOut('owned_initial_payment_diagnostic code=PREPARE_SERVER_SUBSCRIPTION_');reporter.onStdOut('CREATE cleanup=UNCONFIRMED\nprivate-sentinel\n');
    reporter.onStdOut('owned_initial_payment_diagnostic code=PREPARE_ACK cleanup=CONFIRMED\n');
    assert.deepEqual(lines,[handoff+'\n','owned_initial_payment_diagnostic code=PREPARE_SERVER_SUBSCRIPTION_CREATE cleanup=UNCONFIRMED\n']);
  } finally {process.stdout.write=original;}
});

test('bridge accepts an evolving fixture whose initial snapshot has no acknowledged customer', async () => {
  const module=await import(moduleUrl),f=await fixture();
  const initial={...binding,customerId:undefined};
  const current={...initial,customerId:f.p.customer_id};
  assert.equal(initial.customerId,undefined);
  assert.equal(current.customerId,f.p.customer_id);
  const bridge=module.createFirstPaymentBridge({binding:initial,prepared:f.p,token,port:{confirm:async(value:unknown)=>{f.calls.push(value);return f.confirmed;}},limits:f.limits});
  await bridge(route(f.original+'/action'));
  await bridge(route(f.original+'/confirmation-session','POST',{action_token:f.p.action.secret}));
  const confirmation=route(f.original+'/confirmation-attempts/'+f.p.session.attempt_id+'/confirm','POST',{capability:f.p.session.capability,confirmation_token:'ctoken_fixture'});
  await bridge(confirmation);
  assert.equal(confirmation.response().state,'OBSERVE');
  assert.equal(f.calls.length,1);
});

for(const stage of ["ACTION_SCOPE","ACTION_AUTHORITY","ACTION_VERIFICATION","ACTION_GRAPH","ACTION_GRAPH_LINE_ASSOCIATION","ACTION_GRAPH_INVOICE_SETTLEMENT","ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_FLAG","ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_KIND","ACTION_GRAPH_INVOICE_SETTLEMENT_COLLECTION_METHOD","ACTION_GRAPH_INVOICE_SETTLEMENT_PAID_OFF_STRIPE","ACTION_GRAPH_INVOICE_SETTLEMENT_OVERPAID","ACTION_GRAPH_INVOICE_SETTLEMENT_STARTING_BALANCE","ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE","ACTION_GRAPH_INVOICE_SETTLEMENT_PRE_CREDIT_NOTES","ACTION_GRAPH_INVOICE_SETTLEMENT_POST_CREDIT_NOTES","ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNTS","ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNT_AMOUNTS","ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_PRETAX_CREDITS","ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_DISCOUNT_AMOUNTS","ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_PRETAX_CREDITS","ACTION_GRAPH_INVOICE_SETTLEMENT_TOTAL","ACTION_GRAPH_INVOICE_SETTLEMENT_SUBTOTAL","ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_AMOUNT","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_POSITIVE","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_CATALOG","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_PAID","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_REMAINING","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_STATUS","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_PAYMENT","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_TYPE","ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_INTENT","ACTION_GRAPH_INVOICE_SETTLEMENT_SNAPSHOT_UNSUPPORTED","ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN_CASH","ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN","ACTION_GRAPH_PAYMENT_ALLOCATION","ACTION_GRAPH_PAYMENT_ALLOCATION_STATUS_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_STATUS_NOT_OPEN","ACTION_GRAPH_PAYMENT_ALLOCATION_DEFAULT_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_DEFAULT_NOT_TRUE","ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_REQUESTED_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_REQUESTED_MISMATCH","ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_PAID_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_PAID_NONZERO","ACTION_GRAPH_PAYMENT_ALLOCATION_PAYMENT_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_TYPE_UNAVAILABLE","ACTION_GRAPH_PAYMENT_ALLOCATION_TYPE_UNEXPECTED","ACTION_RESERVATION","ACTION_RESULT","ACTION_BINDING"])test('reporter validates '+stage+' once after handoff',async()=>{
 const {default:Reporter}=await import(new URL('./stripe-billing-first-payment-reporter.ts',import.meta.url).href);
 const {formatBillingFirstPaymentDiagnostic,parseBillingFirstPaymentDiagnosticLine}=await import(new URL('../../../../infrastructure/stripe-billing-first-payment.mjs',import.meta.url).href);
 const line=formatBillingFirstPaymentDiagnostic({status:'CLOSED',initialAccepted:false,code:'RUNTIME',diagnostic:'PREPARE_SERVER_'+stage,cleanupConfirmed:true});
 assert.equal(line,'owned_initial_payment_diagnostic code=PREPARE_SERVER_'+stage+' cleanup=CONFIRMED');
 assert.equal(parseBillingFirstPaymentDiagnosticLine(line),line);assert.ok(line!.length<=160);
 const original=process.stdout.write,lines:string[]=[];process.stdout.write=((v:unknown)=>{lines.push(String(v));return true;}) as typeof process.stdout.write;
 try {
  const reporter=new Reporter(),handoff='Authorize the isolated Sandbox CLI at https://access.stripe.com/fixture using code ABCD EFGH. Keep this terminal open.';
  reporter.onStdOut(handoff+'\n');reporter.onStdOut(handoff+'\n');
  reporter.onStdOut('owned_initial_payment_diagnostic code=PREPARE_SERVER_ACTION_FOREIGN cleanup=CONFIRMED\n');
  reporter.onStdOut(line.replace(stage,stage+'_FOREIGN')+'\n');
  reporter.onStdOut(line.replace(stage,'PREFIX_'+stage)+'\n');reporter.onStdOut(line.replace(stage,stage+'1')+'\n');
  reporter.onStdOut(line!.replace(stage,stage+'_sk_test_SECRET')+'\n');
  reporter.onStdOut(line+' private-sentinel\n');reporter.onStdOut(line.slice(0,31));reporter.onStdOut(line.slice(31)+'\n');reporter.onStdOut(line+'\n');
  reporter.onStdErr();
  assert.deepEqual(lines,[handoff+'\n',line+'\n']);
 }finally{process.stdout.write=original;}
});

// Task3: terminal acceptance is finite; private protocol acceptance stays separate.
for(const code of ['START_BACKEND_COMMAND_REJECTED','START_BACKEND_COMMAND_NONZERO_EXIT','START_BACKEND_COMMAND_OUTPUT_LIMIT','START_BACKEND_COMMAND_SPAWN_FAILURE','START_BACKEND_HEALTH_NOT_RUNNING','START_BACKEND_HEALTH_UNHEALTHY','START_BACKEND_HEALTH_PROOF_REJECTED','START_BACKEND_PUBLIC_HEALTH_REJECTED','START_BACKEND_COMMAND_ABORT','START_BACKEND_COMMAND_TIMEOUT','START_BACKEND_HEALTH_WAIT_ABORT','START_BACKEND_HEALTH_WAIT_TIMEOUT','START_BACKEND_HEALTH_REJECTED'])test('startup diagnostic reporter validates '+code+' only once',async()=>{
 const {default:Reporter}=await import(new URL('./stripe-billing-first-payment-reporter.ts',import.meta.url).href);
 const {formatBillingFirstPaymentDiagnostic,parseBillingFirstPaymentDiagnosticLine}=await import(new URL('../../../../infrastructure/stripe-billing-first-payment.mjs',import.meta.url).href);
 const {createOwnedBillingFirstPayment}=await import(new URL('../../../../infrastructure/stripe-billing-first-payment.mjs',import.meta.url).href);
 const owner=await createOwnedBillingFirstPayment({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_FIRST_PAYMENT:'true'},runId:id('1'),expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},controlPort:18083,frozenTime:1790000000,approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_success'},{preflight:async()=>true,createBootstrap:async()=>({prepare:async()=>true,resources:()=>({cliDirectory:'/synthetic'}),start:async()=>{throw null;},startupDiagnostic:()=>code,stop:async()=>{throw null;}}),createCli:async()=>({validateBinding:async()=>true,startListener:async()=>true,stopListener:async()=>true}),login:async()=>true});
 await assert.rejects(owner.run(async()=>{throw Error('unexpected journey');},{deadline:Date.now()+10000}));const result=await owner.close();assert.equal(result.initialAccepted,false);assert.equal(result.code,'START');assert.equal(result.diagnostic,code);
 const line=formatBillingFirstPaymentDiagnostic(result);assert.equal(line,'owned_initial_payment_diagnostic code='+code+' cleanup=UNCONFIRMED');assert.equal(parseBillingFirstPaymentDiagnosticLine(line),line);
 const original=process.stdout.write,lines:string[]=[];process.stdout.write=((v:unknown)=>{lines.push(String(v));return true;}) as typeof process.stdout.write;
 try{
  const reporter=new Reporter(),handoff='Authorize the isolated Sandbox CLI at https://access.stripe.com/fixture using code ABCD EFGH. Keep this terminal open.';
  reporter.onStdOut(handoff+'\n');reporter.onStdOut(handoff+'\n');
  for(const bad of ['START_PRIVATE_COMMAND_REJECTED','START_BACKEND_COMMAND_SECRET','START_BACKEND_HEALTH_PRIVATE'])reporter.onStdOut('owned_initial_payment_diagnostic code='+bad+' cleanup=CONFIRMED\n');
  reporter.onStdOut(line+' private-sentinel\n');reporter.onStdOut(line.slice(0,31));reporter.onStdOut(line.slice(31)+'\n');reporter.onStdOut(line+'\n');reporter.onStdErr();
  assert.deepEqual(lines,[handoff+'\n',line+'\n']);
 }finally{process.stdout.write=original;}
});

for(const category of ['GUARD','STATE','SUBMIT_VALIDATION','SUBMIT','TOKEN_VALIDATION','TOKEN','CLIENT','NEXT_ACTION','RETIRED','RECEIPT'])test('atomic terminal VM snapshot maps '+category+' only after original prepare and Pay',async()=>{
 const f=await journeyFixture('',{stopObserve:true,snapshot:category});let error;try{await f.run();}catch(e){error=e;}
 assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_CONFIRM_'+category);assert.equal(f.provenance.browserCleanupConfirmed(error),true);assert.equal(f.calls.filter(c=>c==='terminal-evaluate').length,1);assert.equal(f.calls.filter(c=>c==='reader').length,1);assert.equal(f.calls.filter(c=>c==='PREPARE').length,1);assert.equal(f.calls.filter(c=>c==='PAY_CLICK').length,1);assert.equal(f.calls.filter(c=>c==='confirm').length,0);
});
for(const reader of ['missing','throwing','accessor','writable','configurable','enumerable','nonfunction'] as const)test('malformed terminal '+reader+' reader preserves generic WAIT_OBSERVE',async()=>{
 const f=await journeyFixture('',{stopObserve:true,snapshot:'SUBMIT',reader});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE');assert.equal(f.calls.filter(c=>c==='confirm').length,0);assert.equal(f.calls.includes('accessor'),false);
});
for(const snapshot of [undefined,null,1,true,{},['SUBMIT'],'BROWSER_CONFIRM_SUBMIT','SUBMIT_EXTRA','PREFIX_SUBMIT','SUBMIT\n'])test('untrusted terminal label '+JSON.stringify(snapshot)+' stays generic',async()=>{
 const f=await journeyFixture('',{stopObserve:true,snapshot});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE');assert.equal(f.calls.filter(c=>c==='confirm').length,0);
});
test('unreadable terminal value is never inspected',async()=>{let reads=0;const snapshot=new Proxy({},{get(){reads++;throw null;},getPrototypeOf(){reads++;throw null;},ownKeys(){reads++;throw null;}});const f=await journeyFixture('',{stopObserve:true,snapshot});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE');assert.equal(reads,0);});
for(const options of [{snapshotOrigin:'https://foreign.test'},{wrongTop:true},{skipPrepare:true},{lateOrigin:true}])test('wrong terminal flow or origin '+JSON.stringify(options)+' supplies no category',async()=>{
 const f=await journeyFixture('',{stopObserve:true,snapshot:'SUBMIT',...options});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE');assert.equal(f.calls.includes('reader'),false);assert.equal(f.calls.includes('confirm'),false);
});
test('successful OBSERVE never consults failure reader',async()=>{const f=await journeyFixture('',{snapshot:'SUBMIT',reader:'throwing'});assert.equal(await f.run(),true);assert.equal(f.calls.includes('reader'),false);assert.equal(f.calls.filter(c=>c==='confirm').length,1);});
test('no terminal timeout does not sample or manufacture a confirmation category',async()=>{const f=await journeyFixture('WAIT_OBSERVE',{snapshot:'SUBMIT'});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_MARKER_MISSING');assert.equal(f.calls.includes('terminal-evaluate'),false);assert.equal(f.calls.includes('reader'),false);});
test('already latched Node bridge failure wins over later renderer category',async()=>{const f=await journeyFixture('',{stopObserve:true,snapshot:'SUBMIT',bridgeDuringEvaluation:true});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_CONFIRM_BRIDGE');assert.equal(f.calls.includes('confirm'),false);});
test('bounded late terminal evaluation cannot restore success or replace first generic failure',async t=>{
 t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});const f=await journeyFixture('',{stopObserve:true,snapshot:'SUBMIT',lateEvaluation:true});let error;const running=f.run({deadline:2000}).catch((e:unknown)=>{error=e;});for(let i=0;i<120&&!f.calls.includes('terminal-evaluate');i++)await Promise.resolve();assert.equal(f.calls.includes('terminal-evaluate'),true);t.mock.timers.tick(1000);await running;assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE');assert.equal(f.provenance.browserCleanupConfirmed(error),true);assert.equal(f.calls.filter(c=>c==='close').length,1);assert.equal(f.calls.includes('confirm'),false);await Promise.resolve();assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE');
});

for(const [pending,stage] of [[['SUBMIT'],'SUBMIT'],[['SUBMIT',null,'TOKEN'],'TOKEN']] as const)test('original wait freezes received '+stage,async()=>{const f=await journeyFixture('WAIT_OBSERVE',{pending:[...pending]});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_LAST_OBSERVED_'+stage);assert.equal(f.calls.includes('terminal-evaluate'),false);assert.equal(f.calls.includes('confirm'),false);});
for(const [pending,code] of [[[],'MARKER_MISSING'],[['SUBMIT',null],'SUBMIT_CLEARED'],[['SUBMIT',null,'TOKEN',null],'TOKEN_CLEARED'],[['TOKEN'],'MARKER_INVALID']] as const)test('original wait distinguishes finite pending state '+code,async()=>{const f=await journeyFixture('WAIT_OBSERVE',{pending:[...pending]});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_'+code);assert.equal(f.calls.includes('terminal-evaluate'),false);assert.equal(f.calls.includes('confirm'),false);});
test('received Pay signal is distinguished from missing provider pending signal',async()=>{const f=await journeyFixture('WAIT_OBSERVE',{paySignal:'ENTERED'});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_PAY_SIGNAL_OBSERVED_MARKER_MISSING');assert.equal(f.calls.includes('terminal-evaluate'),false);assert.equal(f.calls.includes('confirm'),false);});
for(const [value,source] of [['BAD',undefined],['ENTERED','frame'],['ENTERED','origin']] as const)test('invalid or foreign Pay signal '+String(value)+' / '+String(source)+' does not change diagnosis',async()=>{const f=await journeyFixture('WAIT_OBSERVE',{paySignal:value,paySignalSource:source});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_MARKER_MISSING');});
test('Pay signal delivered after timeout cannot rewrite frozen missing-marker diagnosis',async()=>{const f=await journeyFixture('WAIT_OBSERVE',{latePaySignal:'ENTERED'});let error;try{await f.run();}catch(e){error=e;}await Promise.resolve();assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_MARKER_MISSING');});

for(const [pending,code] of [[[],'MARKER_MISSING'],[['SUBMIT',null],'SUBMIT_CLEARED'],[['SUBMIT',null,'TOKEN',null],'TOKEN_CLEARED'],[['TOKEN'],'MARKER_INVALID'],[['SUBMIT','SUBMIT'],'MARKER_INVALID'],[['SUBMIT',null,null],'MARKER_INVALID'],[['SUBMIT',{}],'MARKER_INVALID'],[['SUBMIT',undefined],'MARKER_INVALID']] as const)test('finite pending outcome '+code+' for '+JSON.stringify(pending),async()=>{const f=await journeyFixture('WAIT_OBSERVE',{pending:[...pending]});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_'+code);});
for(const pendingSource of ['frame','origin'] as const)test('untrusted pending '+pendingSource+' disables hints',async()=>{const f=await journeyFixture('WAIT_OBSERVE',{pending:['SUBMIT'],pendingSource});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_MARKER_INVALID');});
test('pre Pay is ignored and late TOKEN cannot rewrite frozen SUBMIT; scoped cancellation blocks held bridge',async()=>{const f=await journeyFixture('WAIT_OBSERVE',{prePending:['TOKEN'],pending:['SUBMIT'],latePending:[null,'TOKEN'],heldConfirm:true});let error;try{await f.run();}catch(e){error=e;}await f.drain();assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_LAST_OBSERVED_SUBMIT');assert.equal(f.calls.includes('scoped-live'),false);assert.equal(f.calls.includes('scoped-aborted'),true);assert.equal(f.calls.includes('confirm'),false);assert.equal(f.calls.includes('terminal-evaluate'),false);});
test('hostile pending payload is never inspected',async()=>{let reads=0;const value=new Proxy({},{get(){reads++;throw null;},ownKeys(){reads++;throw null;},getPrototypeOf(){reads++;throw null;}});const f=await journeyFixture('WAIT_OBSERVE',{pending:['SUBMIT',value]});let error;try{await f.run();}catch(e){error=e;}assert.equal(reads,0);assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE_MARKER_INVALID');});

test('parent abort at original wait keeps generic identity despite received SUBMIT',async()=>{const controller=new AbortController();const f=await journeyFixture('WAIT_OBSERVE',{pending:['SUBMIT'],onObserveWait:()=>controller.abort()});let error;try{await f.run({deadline:Date.now()+1000,signal:controller.signal});}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE');assert.equal(f.calls.includes('scoped-aborted'),true);});
test('absolute parent expiry at original wait keeps generic identity despite TOKEN',async t=>{t.mock.timers.enable({apis:['Date'],now:1000});const f=await journeyFixture('WAIT_OBSERVE',{pending:['SUBMIT',null,'TOKEN'],onObserveWait:()=>t.mock.timers.setTime(2000)});let error;try{await f.run({deadline:2000});}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_WAIT_OBSERVE');});
test('closing during customer acknowledgement revokes CREATE before late acknowledgement resolves',async()=>{const controller=new AbortController();const f=await journeyFixture('',{holdAcknowledge:true,onAcknowledge:()=>controller.abort()});let error;try{await f.run({deadline:Date.now()+1000,signal:controller.signal});}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_PREPARE');assert.equal(f.calls.includes('PREPARE'),false);assert.equal(f.calls.includes('confirm'),false);assert.equal(f.calls.filter(c=>c==='close').length,1);});

test('closing while prepare is pending cannot publish bridge authority on late return',async()=>{const controller=new AbortController();const f=await journeyFixture('',{holdPrepare:true,onPrepare:()=>controller.abort()});let error;try{await f.run({deadline:Date.now()+1000,signal:controller.signal});}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_PREPARE');assert.equal(f.calls.includes('confirm'),false);assert.equal(f.calls.includes('scoped-aborted'),true);});
for(const payRace of ['bridge-first','pay-first'] as const)test('received hint cannot replace earlier '+payRace+' failure',async()=>{const f=await journeyFixture('',{pending:['SUBMIT'],payRace});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),payRace==='bridge-first'?'BROWSER_CONFIRM_BRIDGE':'BROWSER_PAY_CLICK');assert.equal(f.calls.includes('confirm'),false);});
test('pending hint never replaces terminal reader category',async()=>{const f=await journeyFixture('',{pending:['SUBMIT'],stopObserve:true,snapshot:'TOKEN'});let error;try{await f.run();}catch(e){error=e;}assert.equal(f.provenance.browserDiagnostic(error),'BROWSER_CONFIRM_TOKEN');});
