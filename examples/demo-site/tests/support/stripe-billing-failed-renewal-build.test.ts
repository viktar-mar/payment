// Exercise the deployed entry entirely in Node. Only the SDK's local wire
// responses are supplied. Inserting a script records its URL without loading it.
import assert from 'node:assert/strict';
import {mkdtemp, readFile, readdir, rm, stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import test from 'node:test';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {build} from 'vite';

async function checkFirstCardLoad(entry: string) {
  const expiresAt = new Date(Date.now() + 60_000).toISOString();
  const backend = 'http://127.0.0.1:3001';
  const challengeId = crypto.randomUUID(), subscriptionId = crypto.randomUUID(), operationId = crypto.randomUUID();
  const challenges = backend + '/api/v1/public/customer-auth/email-otp/challenges';
  const operation = backend + '/api/v1/customer/subscriptions/' + subscriptionId + '/operations/' + operationId;
  const replies = new Map<string, Response>([
    [challenges, Response.json({challenge_id: challengeId, expires_at: expiresAt}, {status: 202})],
    [challenges + '/' + challengeId + '/verify', Response.json({
      session_token: 'T'.repeat(43), expires_at: expiresAt, site_id: crypto.randomUUID(),
      scopes: ['SUBSCRIPTION_READ', 'SUBSCRIPTION_MANAGE'],
      customer: {id: crypto.randomUUID(), status: 'ACTIVE', masked_email: null},
    })],
    [operation + '/action', Response.json({kind: 'PROVIDER_CLIENT', secret: 'bga1_' + 'A'.repeat(43), expires_at: expiresAt})],
    [operation + '/confirmation-session', Response.json({
      protocol: 'SERVER_CONFIRM_V2', provider: 'stripe', flow: 'PAYMENT', publishable_key: 'pk_test_fixture',
      client_secret: 'pi_fixture_secret_fixture', attempt_id: crypto.randomUUID(), capability: 'bca2_' + 'B'.repeat(43), expires_at: expiresAt,
    })],
  ]);
  const body = {dataset: {phase: 'NEW'}};
  const scripts: Array<EventTarget & {src: string}> = [];
  const firstScript = Promise.withResolvers<void>();
  const requests: string[] = [];
  const controls = new Map(['request', 'verify', 'code', 'consent', 'start', 'pay', 'confirm-method', 'status']
    .map(id => [id, {disabled: true, checked: false, value: '', onclick: undefined as (() => Promise<void>) | undefined}]));
  const host = {isConnected: true, getClientRects: () => [{width: 300, height: 100}], getRootNode: () => document};
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
    declare __failedRenewalStop?: () => void;
    __failedRenewalPrepare() {return {subscriptionId, operationId};}
    __failedRenewalChallenge() {}
    __failedRenewalTerminal() {}
  }
  const window = new PageWindow();
  const globals = {
    window, document, location: {origin: 'http://localhost:3000'}, ShadowRoot: class {},
    getComputedStyle: () => ({display: 'block', visibility: 'visible', opacity: '1'}),
    MutationObserver: class {observe() {} disconnect() {}},
    fetch: async (url: string) => {
      requests.push(url);
      const reply = replies.get(url);
      assert.ok(reply, 'only the four local fixture responses are available');
      return reply;
    },
  };
  const originals = new Map(Object.keys(globals).map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  let start: Promise<void> | undefined;
  try {
    for (const [name, value] of Object.entries(globals)) Object.defineProperty(globalThis, name, {configurable: true, value});
    await import(pathToFileURL(entry).href);
    assert.equal(scripts.length, 0, 'opening the page must not load Stripe.js');
    window.__failedRenewalConfigure({backend, siteKey: 'test-site', email: 'fixture@example.test', deadline: Date.parse(expiresAt)});
    await controls.get('request')!.onclick!();
    controls.get('code')!.value = '123456';
    await controls.get('verify')!.onclick!();
    assert.equal(body.dataset.phase, 'AUTHENTICATED');
    assert.equal(scripts.length, 0, 'authentication must not load Stripe.js');
    controls.get('consent')!.checked = true;
    start = controls.get('start')!.onclick!();
    await Promise.race([firstScript.promise, start]);
    assert.equal(body.dataset.phase, 'PREPARING');
    assert.deepEqual(requests, [...replies.keys()]);
    assert.equal(scripts.length, 1, 'the real payment mount inserts one script');
    const url = new URL(scripts[0]!.src);
    assert.equal(url.origin, 'https://js.stripe.com');
    assert.deepEqual([...url.searchParams], [['advancedFraudSignals', 'false']]);
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

test('failed-renewal delivery loads one configured Stripe.js script on the first SDK card mount', async () => {
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
    await checkFirstCardLoad(file);
  } finally {
    await rm(privateDir, {recursive: true, force: true});
  }
});
