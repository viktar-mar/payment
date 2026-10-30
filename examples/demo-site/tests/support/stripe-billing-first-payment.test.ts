import test from 'node:test';
import assert from 'node:assert/strict';

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
