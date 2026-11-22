// Execute the deployed entry, SDK and loader in Node with in-memory HTTP
// responses and a synthetic provider. The inserted script never leaves the fixture.
import assert from 'node:assert/strict';
import {mkdtemp, readFile, readdir, rm, stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import test from 'node:test';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {build} from 'vite';

async function checkCardForms(entry: string) {
  const sessionExpiry = new Date(Date.now() + 300_000).toISOString();
  const paymentExpiry = new Date(Date.now() + 60_000).toISOString();
  const methodExpiry = new Date(Date.now() + 180_000).toISOString();
  const backend = 'http://127.0.0.1:3001';
  const challengeId = crypto.randomUUID(), subscriptionId = crypto.randomUUID(), operationId = crypto.randomUUID();
  const challenges = backend + '/api/v1/public/customer-auth/email-otp/challenges';
  const base = backend + '/api/v1/customer/subscriptions/' + subscriptionId + '/operations/';
  const attemptId = crypto.randomUUID(), replacementId = crypto.randomUUID();
  const operation = base + operationId, replacement = base + replacementId;
  const actionToken = 'bga1_' + 'M'.repeat(43);
  const replies = new Map<string, Response>([
    [challenges, Response.json({challenge_id: challengeId, expires_at: paymentExpiry}, {status: 202})],
    [challenges + '/' + challengeId + '/verify', Response.json({
      session_token: 'T'.repeat(43), expires_at: sessionExpiry, site_id: crypto.randomUUID(),
      scopes: ['SUBSCRIPTION_READ', 'SUBSCRIPTION_MANAGE'],
      customer: {id: crypto.randomUUID(), status: 'ACTIVE', masked_email: null},
    })],
    [operation + '/action', Response.json({kind: 'PROVIDER_CLIENT', secret: 'bga1_' + 'A'.repeat(43), expires_at: paymentExpiry})],
    [operation + '/confirmation-session', Response.json({
      protocol: 'SERVER_CONFIRM_V2', provider: 'stripe', flow: 'PAYMENT', publishable_key: 'pk_test_fixture',
      client_secret: 'pi_fixture_secret_fixture', attempt_id: attemptId, capability: 'bca2_' + 'B'.repeat(43), expires_at: paymentExpiry,
    })],
    [operation + '/confirmation-attempts/' + attemptId + '/confirm', Response.json({
      protocol: 'SERVER_CONFIRM_V2', attempt_id: attemptId, state: 'OBSERVE', retry_allowed: false,
      successor_attempt_id: null, next_action_allowed: false, expires_at: paymentExpiry,
    })],
    [replacement + '/action', Response.json({kind: 'PROVIDER_CLIENT', secret: actionToken, expires_at: methodExpiry})],
    [replacement + '/confirmation-session', Response.json({
      protocol: 'BROWSER_CONFIRM_V1', flow: 'SETUP', provider: 'stripe', publishable_key: 'pk_test_fixture',
      client_secret: 'seti_fixture_secret_fixture', action_token: actionToken, expires_at: methodExpiry,
    })],
    [replacement + '/action-confirmations', Response.json({
      subscription_id: subscriptionId, operation_id: replacementId, status: 'RESERVED', action_available: false,
      next_action: null, browser_confirm_allowed: true,
    }, {status: 202})],
  ]);
  const body = {dataset: {phase: 'NEW'}};
  const scripts: Array<EventTarget & {src: string}> = [];
  const firstScript = Promise.withResolvers<void>();
  const requests: string[] = [];
  const progress: unknown[][] = [];
  const controls = new Map(['request', 'verify', 'code', 'consent', 'start', 'pay', 'confirm-method', 'status']
    .map(id => [id, {disabled: true, checked: false, value: '', onclick: undefined as ((event?: {isTrusted: boolean}) => Promise<void>) | undefined}]));
  const host = {isConnected: true, get ownerDocument(): Document {return document as unknown as Document;},
    getClientRects: () => [{width: 300, height: 100}], getRootNode: () => document};
  const document = Object.assign(new EventTarget(), {
    body, visibilityState: 'visible',
    querySelector: (selector: string) => selector === '#provider' ? host : controls.get(selector.slice(1)) ?? null,
    querySelectorAll: (selector: string) => selector.startsWith('script[') ? scripts : selector === 'button,input' ? [...controls.values()] : [],
    createElement: (tag: string) => {
      if (tag === 'link') return {relList: {supports: () => true}};
      assert.equal(tag, 'script');
      return new EventTarget();
    },
    head: {appendChild(script: EventTarget & {src: string}) {scripts.push(script); firstScript.resolve();}},
  });
  class PageWindow extends EventTarget {
    readonly top = this;
    declare __failedRenewalConfigure: (config: object) => void;
    declare __failedRenewalMethod: (operation: object) => Promise<void>;
    declare __failedRenewalStop?: () => void;
    __failedRenewalPrepare() {return {subscriptionId, operationId};}
    __failedRenewalChallenge() {}
    __failedRenewalTerminal() {}
    __failedRenewalPayProgress = (...args: unknown[]) => {progress.push(args);};
  }
  const window = new PageWindow();
  Object.defineProperty(document, 'defaultView', {value: window});
  const globals = {
    window, document, location: {origin: 'http://localhost:3000'}, ShadowRoot: class {},
    getComputedStyle: () => ({display: 'block', visibility: 'visible', opacity: '1'}),
    MutationObserver: class {observe() {} disconnect() {} takeRecords() {return [];}},
    fetch: async (url: string) => {
      requests.push(url);
      const reply = replies.get(url);
      assert.ok(reply, 'only local fixture responses are available');
      return reply;
    },
  };
  const originals = new Map(Object.keys(globals).map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  let start: Promise<void> | undefined;
  try {
    for (const [name, value] of Object.entries(globals)) Object.defineProperty(globalThis, name, {configurable: true, value});
    await import(pathToFileURL(entry).href);
    assert.equal(scripts.length, 0, 'opening the page must not load Stripe.js');
    window.__failedRenewalConfigure({backend, siteKey: 'test-site', email: 'fixture@example.test', deadline: Date.parse(sessionExpiry)});
    await controls.get('request')!.onclick!();
    controls.get('code')!.value = '123456';
    await controls.get('verify')!.onclick!();
    assert.equal(body.dataset.phase, 'AUTHENTICATED');
    assert.equal(scripts.length, 0, 'authentication must not load Stripe.js');
    controls.get('consent')!.checked = true;
    start = controls.get('start')!.onclick!();
    await Promise.race([firstScript.promise, start]);
    assert.equal(body.dataset.phase, 'PREPARING');
    assert.deepEqual(requests, [...replies.keys()].slice(0, 4));
    assert.equal(scripts.length, 1, 'the real payment mount inserts one script');
    const url = new URL(scripts[0]!.src);
    assert.equal(url.origin, 'https://js.stripe.com');
    assert.deepEqual([...url.searchParams], [['advancedFraudSignals', 'false']]);
    // Deliver the inserted script's load event locally. Neither form contacts a provider.
    const calls: string[] = [];
    const stripe = () => ({
      elements: ({clientSecret}: {clientSecret: string}) => ({
        submit: async () => ({}),
        create: () => {
          let ready: () => void;
          const form = clientSecret.startsWith('pi_') ? 'payment' : 'setup';
          return {
            on(name: string, fn: () => void) {if (name === 'ready') ready = fn;},
            mount() {calls.push(form); ready();},
            off() {}, destroy() {},
          };
        },
      }),
      createConfirmationToken: async () => ({confirmationToken: {id: 'ctoken_fixture'}}),
      confirmSetup: async () => {calls.push('confirmSetup'); return {};},
    });
    Object.assign(window, {Stripe: Object.assign(stripe, {version: 'endive'})});
    scripts[0]!.dispatchEvent(new Event('load'));
    await start;
    assert.equal(body.dataset.phase, 'READY');
    assert.deepEqual(progress, []);
    controls.get('pay')!.disabled = false;
    await controls.get('pay')!.onclick!({isTrusted: true});
    assert.equal(body.dataset.phase, 'OBSERVE');
    await window.__failedRenewalMethod({subscriptionId, operationId: replacementId});
    assert.equal(body.dataset.phase, 'METHOD_READY');
    assert.equal(controls.get('confirm-method')!.disabled, true);
    assert.deepEqual(calls, ['payment', 'setup']);
    assert.deepEqual(requests, [...replies.keys()].slice(0, -1));
    controls.get('confirm-method')!.disabled = false;
    await controls.get('confirm-method')!.onclick!({isTrusted: true});
    assert.equal(body.dataset.phase, 'METHOD_CONFIRMED');
    assert.deepEqual(progress, [['ENTERED'], ['SUBMIT'], [null], ['TOKEN'], [null]]);
    assert.deepEqual(calls, ['payment', 'setup', 'confirmSetup']);
    assert.deepEqual(requests, [...replies.keys()]);
    assert.equal(scripts.length, 1);
  } finally {
    try {
      window.__failedRenewalStop?.();
      await start;
    } finally {
      for (const [name, descriptor] of originals) {
        if (descriptor) Object.defineProperty(globalThis, name, descriptor);
        else Reflect.deleteProperty(globalThis, name);
      }
    }
  }
}

test('failed-renewal delivery loads one configured script and requires separate clicks for payment and SETUP', async () => {
  const root = fileURLToPath(new URL('../..', import.meta.url));
  const sdk = resolve(root, '../../frontend/sdk/src');
  const privateDir = await mkdtemp(join(tmpdir(), 'failed-renewal-build-'));
  const outDir = join(privateDir, 'dist');
  const modules = new Set<string>();
  try {
    await build({
      root, configFile: join(root, 'vite.config.ts'), configLoader: 'runner', envFile: false,
      mode: 'billing-failed-renewal', logLevel: 'silent', cacheDir: join(privateDir, 'cache'),
      build: {outDir},
      plugins: [{
        name: 'inspect-failed-renewal-delivery',
        generateBundle(_options, bundle) {
          const entry = this.getModuleInfo(join(root, 'src/billing-failed-renewal.ts'));
          assert.ok(entry?.importedIds.includes(join(sdk, 'index.ts')), 'entry uses the current public SDK source');
          for (const output of Object.values(bundle)) {
            if (output.type === 'chunk') for (const id of output.moduleIds) modules.add(id);
          }
        },
      }],
    });
    assert.deepEqual((await readdir(outDir)).sort(), ['assets', 'billing-failed-renewal.html', 'health']);
    assert.equal(await readFile(join(outDir, 'health'), 'utf8'), 'ok\n');
    const html = await readFile(join(outDir, 'billing-failed-renewal.html'), 'utf8');
    const entry = /<script\b[^>]*\bsrc="\/(assets\/[\w.-]+\.js)"/.exec(html)?.[1];
    assert.ok(entry, 'delivered HTML points to its emitted JavaScript');
    const file = join(outDir, entry);
    const size = (await stat(file)).size;
    assert.ok(size > 0 && size < 5 * 1024 * 1024);
    for (const name of ['client.ts', 'subscriptions/stripe-payment-attempt-driver.ts', 'subscriptions/stripe-subscription-method.ts']) {
      assert.ok(modules.has(join(sdk, name)), `deployed chunks include ${name}`);
    }
    assert.ok(modules.has(join(root, 'src/billing-failed-renewal-page.ts')));
    assert.equal([...modules].filter(id => id.includes('/@stripe/stripe-js/') && id.endsWith('/pure/index.js')).length, 1,
      'the deployed entry includes a single installed pure loader');
    await checkCardForms(file);
  } finally {
    await rm(privateDir, {recursive: true, force: true});
  }
});
