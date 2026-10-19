import type { Stripe } from '@stripe/stripe-js';
import { afterEach, expect, it, vi } from 'vitest';
vi.mock('../providers/stripe-loader.js', () => ({ loadStripeClient: vi.fn() }));
import { loadStripeClient } from '../providers/stripe-loader.js';
import { GenericPaymentWidget, defineGenericPaymentWidget } from '../widget/generic-payment-widget.js';
import { CheckoutProviderView } from '../widget/checkout-provider-view.js';
import { mountStripePayment } from '../providers/stripe-driver.js';
import { createGenericPaymentClient } from '../client.js';
import { stripeUiProvider, stripeWire, stripeReadWire, stripeCapability } from '../test/stripe-ui-fixtures.js';
import { json } from '../test/fixtures.js';

defineGenericPaymentWidget();
const observations = new Set<{ close(): void }>();
afterEach(() => { for (const observation of observations) observation.close(); observations.clear(); });
// A missing implementation fails as an explicit contract assertion, not a module-loading error.
async function observer() {
  const modules = import.meta.glob('./form-load-observer.ts');
  expect(modules['./form-load-observer.ts'], 'internal diagnostic observer must exist').toBeTypeOf('function');
  return await modules['./form-load-observer.ts']!() as typeof import('./form-load-observer.js');
}
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function setup() {
  const api = await observer(); const p = stripeUiProvider(); const requests: string[] = [];
  const callbacks = new Map<string, () => void>(); const on = p.element.on;
  vi.spyOn(p.element, 'on').mockImplementation((name, callback) => { callbacks.set(name, callback); return on(name, callback); });
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(`${init.method} ${new URL(url).pathname}`); return json(url.endsWith('/client-session') ? stripeCapability() : init.method === 'GET' ? stripeReadWire('CREATED') : stripeWire()); });
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' });
  const session = await client.createCatalogCheckout({ siteKey: 'test-site', priceKey: 'lifetime' });
  const widget = new GenericPaymentWidget(); widget.siteKey = 'test-site'; widget.priceKey = 'lifetime'; widget.apiBaseUrl = 'https://api.test';
  const observation = api.armFormLoadDiagnostic(widget); observations.add(observation); document.body.append(widget);
  await vi.waitFor(() => expect(widget.state).toBe('checking'));
  const resume = widget.shadowRoot!.querySelectorAll('button')[1]!;
  await vi.waitFor(() => expect(resume).toBeEnabled());
  return { api, p, callbacks, requests, client, session, widget, observation, resume };
}
async function atFrame(f: Awaited<ReturnType<typeof setup>>) { f.resume.click(); await vi.waitFor(() => expect(f.widget.shadowRoot!.querySelector('iframe')).toBe(f.p.frame)); }
async function cleanup(f: Awaited<ReturnType<typeof setup>>) { f.observation.close(); f.widget.remove(); await Promise.resolve(); }

async function readinessFixture() {
  const api = await observer(); const widget = document.createElement('div');
  api.registerFormLoadWidget(widget);
  const observation = api.armFormLoadDiagnostic(widget); observations.add(observation);
  const host = document.createElement('div'); widget.attachShadow({ mode: 'open' }).append(host);
  return { api, widget, host, observation, bind() { document.body.append(widget); return api.bindFormLoadDiagnostic(widget, host, 1); } };
}

// Break: folding loaderstart into the legacy snapshot changes its serialized wire contract.
it('defaults the separate immutable readiness snapshot to false without changing legacy keys', async () => {
  const f = await readinessFixture();
  expect(f.observation.readinessSnapshot, 'separate readiness snapshot must exist').toBeTypeOf('function');
  const snapshot = f.observation.readinessSnapshot();
  expect(snapshot).toEqual({ loaderstart_seen: false }); expect(Object.isFrozen(snapshot)).toBe(true);
  expect(() => Object.assign(snapshot, { loaderstart_seen: true })).toThrow();
  expect(Object.keys(f.observation.snapshot())).toEqual(['version', 'binding', 'capability_started', 'capability_ready', 'loader_started', 'loader_ready', 'element_created', 'mount_called', 'mount_returned', 'ready_seen', 'loaderror_seen', 'catchup', 'view_completed', 'widget_completed', 'confirmation_blocked', 'operation']);
});
// Break: dropping the loaderstart mark or treating it as ready loses or overstates evidence.
it('records loaderstart only in the current bound pending readiness snapshot', async () => {
  const f = await readinessFixture(); const binding = f.bind();
  expect(f.api.markFormLoadLoaderStart, 'internal loaderstart hook must exist').toBeTypeOf('function');
  const before = f.observation.readinessSnapshot(); const legacy = f.observation.snapshot();
  f.api.markFormLoadLoaderStart(binding);
  expect(f.observation.readinessSnapshot()).toEqual({ loaderstart_seen: true });
  expect(before).toEqual({ loaderstart_seen: false }); expect(f.observation.snapshot()).toEqual(legacy);
});
// Break: accepting stale, unbound or terminal marks allows late events to improve evidence.
it.each(['unbound', 'lost', 'closed', 'aborted', 'failed', 'completed', 'repeat', 'host-removed'])('ignores loaderstart for a %s observation', async status => {
  const f = await readinessFixture();
  expect(f.api.markFormLoadLoaderStart, 'internal loaderstart hook must exist').toBeTypeOf('function');
  const binding = status === 'unbound' ? undefined : f.bind();
  if (status === 'lost') f.api.disconnectFormLoadWidget(f.widget);
  if (status === 'closed') f.observation.close();
  if (status === 'aborted' || status === 'failed') f.api.failFormLoad(binding, status === 'aborted');
  if (status === 'completed') f.api.markFormLoad(binding, 'widget_completed');
  if (status === 'repeat') f.api.bindFormLoadDiagnostic(f.widget, f.host, 2);
  if (status === 'host-removed') f.host.remove();
  f.api.markFormLoadLoaderStart(binding);
  expect(f.observation.readinessSnapshot()).toEqual({ loaderstart_seen: false });
  if (status === 'closed') expect(() => f.api.denyFormLoadConfirmation(binding)).toThrow('CONFIRMATION_BLOCKED');
});

// Break: accepting arbitrary or already-connected nodes defeats the singleton/fresh-widget handshake.
it('rejects foreign, duplicate-registry, previously connected and second armed widgets', async () => {
  const api = await observer();
  expect(() => api.armFormLoadDiagnostic(document.createElement('div'))).toThrow('INSTRUMENTATION_UNAVAILABLE');
  const duplicateModules = import.meta.glob('./form-load-observer.ts', { query: '?duplicate-registry' });
  const duplicate = await duplicateModules['./form-load-observer.ts']!() as Awaited<ReturnType<typeof observer>>;
  const fresh = new GenericPaymentWidget(); expect(() => duplicate.armFormLoadDiagnostic(fresh)).toThrow('INSTRUMENTATION_UNAVAILABLE');
  const used = new GenericPaymentWidget(); document.body.append(used); used.remove();
  expect(() => api.armFormLoadDiagnostic(used)).toThrow('INSTRUMENTATION_UNAVAILABLE');
  const first = api.armFormLoadDiagnostic(fresh);
  expect(first.snapshot()).toMatchObject({ binding: 'ARMED', operation: 'PENDING' });
  expect(() => api.armFormLoadDiagnostic(new GenericPaymentWidget())).toThrow('INSTRUMENTATION_UNAVAILABLE');
  expect(() => api.armFormLoadDiagnostic(fresh)).toThrow('INSTRUMENTATION_UNAVAILABLE'); first.close();
});
// Break: conflating stage flags hides which actual existing await is pending.
it.each(['capability', 'loader', 'ready'])('observes the literal pending %s boundary', async stage => {
  const f = await setup(); const pending = deferred<Response>(); const loader = deferred<Stripe | null>();
  if (stage === 'capability') { const original = fetch; vi.stubGlobal('fetch', (url: string, init: RequestInit) => url.endsWith('/client-session') ? pending.promise : original(url, init)); }
  if (stage === 'loader') vi.mocked(loadStripeClient).mockReturnValueOnce(loader.promise);
  f.resume.click(); await vi.waitFor(() => expect(f.observation.snapshot().capability_started).toBe(true));
  if (stage !== 'capability') await vi.waitFor(() => expect(f.observation.snapshot().loader_started).toBe(true));
  if (stage === 'ready') await vi.waitFor(() => expect(f.p.frame.isConnected).toBe(true));
  expect(f.observation.snapshot()).toEqual({ version: 1, binding: 'BOUND', capability_started: true, capability_ready: stage !== 'capability', loader_started: stage !== 'capability', loader_ready: stage === 'ready', element_created: stage === 'ready', mount_called: stage === 'ready', mount_returned: stage === 'ready', ready_seen: false, loaderror_seen: false, catchup: 'NOT_STARTED', view_completed: false, widget_completed: false, confirmation_blocked: false, operation: 'PENDING' });
  await cleanup(f); pending.resolve(json(stripeCapability())); loader.resolve(null);
});
// Break: listener instrumentation after mount loses synchronous ready; reading event payload leaks provider values.
it('keeps synchronous ready and mount-returned independent and never reads event payload', async () => {
  const f = await setup(); const payload = new Proxy({}, { get() { throw new Error('provider payload read'); } });
  let during: unknown;
  vi.spyOn(f.p.element, 'mount').mockImplementationOnce(host => { host.append(f.p.frame); (f.callbacks.get('ready') as unknown as (value: unknown) => void)(payload); during = f.observation.snapshot(); });
  f.resume.click(); await vi.waitFor(() => expect(f.widget.state).toBe('ready'));
  expect(during).toMatchObject({ ready_seen: true, mount_called: true, mount_returned: false, widget_completed: false });
  expect(f.observation.snapshot()).toMatchObject({ ready_seen: true, mount_returned: true, view_completed: true, widget_completed: true, operation: 'COMPLETED' });
  const snapshot = f.observation.snapshot(); expect(Object.isFrozen(snapshot)).toBe(true); expect(() => Object.assign(snapshot, { ready_seen: false })).toThrow();
  expect(JSON.stringify(snapshot)).not.toMatch(/pk_test|secret|https|checkoutId/); await cleanup(f);
});
// Break: post-ready presentation pending/rejection/abort must not be attributed to Stripe ready.
it.each(['resolve', 'reject', 'abort'])('distinguishes initial catch-up %s from ready', async outcome => {
  const f = await setup(); const update = deferred<void>(); f.p.elements.update.mockReturnValueOnce(update.promise);
  await atFrame(f); f.widget.locale = 'pt'; f.p.emit('ready');
  await vi.waitFor(() => expect(f.observation.snapshot().catchup).toBe('PENDING'));
  expect(f.observation.snapshot()).toMatchObject({ ready_seen: true, view_completed: false, widget_completed: false, operation: 'PENDING' });
  if (outcome === 'abort') f.widget.remove(); else if (outcome === 'reject') update.reject(new Error('private provider sentinel')); else update.resolve();
  await vi.waitFor(() => expect(f.observation.snapshot().catchup).toBe(outcome === 'abort' ? 'ABORTED' : outcome === 'reject' ? 'REJECTED' : 'RESOLVED'));
  expect(f.observation.snapshot()).toMatchObject(outcome === 'abort' ? { binding: 'LOST', operation: 'ABORTED', view_completed: false } : { binding: 'BOUND', operation: 'COMPLETED', view_completed: true, widget_completed: true });
  update.resolve(); await cleanup(f);
});
it.each(['loaderror', 'loader-reject', 'capability-reject', 'mount-throw'])('records %s as failure, never as a pending wait', async failure => {
  const f = await setup();
  if (failure === 'loader-reject') vi.mocked(loadStripeClient).mockRejectedValueOnce(new Error('private'));
  if (failure === 'capability-reject') vi.stubGlobal('fetch', async () => json({ code: 'REQUEST_FAILED' }, 503));
  if (failure === 'mount-throw') vi.spyOn(f.p.element, 'mount').mockImplementationOnce(() => { throw new Error('private'); });
  if (failure === 'loaderror') { await atFrame(f); f.p.emit('loaderror'); } else f.resume.click();
  await vi.waitFor(() => expect(f.observation.snapshot().operation).toBe('FAILED'));
  expect(f.observation.snapshot().loaderror_seen).toBe(failure === 'loaderror');
  expect(f.observation.snapshot().widget_completed).toBe(false); await cleanup(f);
});
// Break: stale host/generation and late events must never improve a lost observation.
it.each(['remove', 'reconfigure', 'repeat', 'resume', 'host-remove'])('permanently loses binding on %s and ignores late ready', async action => {
  const f = await setup(); await atFrame(f); const late = f.callbacks.get('ready');
  if (action === 'remove') f.widget.remove();
  if (action === 'reconfigure') f.widget.priceKey = 'other';
  if (action === 'repeat') f.api.bindFormLoadDiagnostic(f.widget, f.p.frame.parentElement!, 999);
  if (action === 'resume') f.resume.dispatchEvent(new MouseEvent('click'));
  if (action === 'host-remove') f.p.frame.parentElement!.remove();
  expect(f.observation.snapshot().binding).toBe('LOST'); late?.(); await Promise.resolve(); await Promise.resolve();
  expect(f.observation.snapshot()).toMatchObject({ binding: 'LOST', ready_seen: false, widget_completed: false }); await cleanup(f);
});
// Break: view/handle confirm after close or destruction must reject before every monetary or refresh boundary.
it.each(['view-stripe', 'view-fake', 'handle'])('retains immutable %s deny after close and destruction', async target => {
  const f = await setup(); const host = document.createElement('div'); f.widget.shadowRoot!.append(host);
  f.api.bindFormLoadDiagnostic(f.widget, host, 1);
  const controller = new AbortController(); const view = new CheckoutProviderView(f.client, host);
  let confirm: () => Promise<unknown>; let destroy: () => void;
  if (target === 'handle') {
    const capability = { checkoutId: f.session.checkoutId, paymentId: f.session.state.paymentId, attemptId: '44444444-4444-4444-8444-444444444444', publishableKey: 'pk_test_fixture', clientSecret: 'pi_fixture_secret_fixture', expiresAt: f.session.expiresAt, returnUrl: `${location.origin}/payment-return` };
    const pending = mountStripePayment({ container: host, signal: controller.signal, capability, appearance: {}, refresh: () => f.client.get(f.session) });
    await vi.waitFor(() => expect(f.p.frame.isConnected).toBe(true)); f.p.emit('ready'); const handle = await pending;
    confirm = () => handle.confirm(); destroy = () => handle.destroy();
  } else {
    const session = target === 'view-fake' ? { ...f.session, providerType: 'FAKE' as const, confirmationMode: 'SERVER' as const } : f.session;
    const pending = view.mount(session, controller.signal);
    if (target === 'view-stripe') { await vi.waitFor(() => expect(f.p.frame.isConnected).toBe(true)); f.p.emit('ready'); }
    await pending; confirm = () => view.confirm(); destroy = () => view.destroy();
  }
  const before = f.requests.length;
  await expect(confirm()).rejects.toMatchObject({ code: 'CONFIRMATION_BLOCKED' });
  f.observation.close(); await expect(confirm()).rejects.toMatchObject({ code: 'CONFIRMATION_BLOCKED' });
  let reentrant: Promise<unknown> | undefined;
  if (target !== 'view-fake') vi.spyOn(f.p.element, 'destroy').mockImplementationOnce(() => { reentrant = confirm().catch(error => error); f.p.frame.remove(); });
  destroy(); controller.abort(); await expect(confirm()).rejects.toMatchObject({ code: 'CONFIRMATION_BLOCKED' });
  if (reentrant) expect(await reentrant).toMatchObject({ code: 'CONFIRMATION_BLOCKED' });
  expect(f.requests).toHaveLength(before); expect(f.p.stripe.confirmPayment).not.toHaveBeenCalled();
  expect(f.observation.snapshot()).toMatchObject({ binding: 'CLOSED', confirmation_blocked: true, operation: 'FAILED' }); await cleanup(f);
});
it('records a first confirmation attempt after observation close', async () => {
  const f = await setup(); await atFrame(f); f.p.emit('ready'); await vi.waitFor(() => expect(f.widget.state).toBe('ready'));
  const pay = f.widget.shadowRoot!.querySelector('button')!; const before = f.requests.length;
  f.observation.close(); expect(f.observation.snapshot().confirmation_blocked).toBe(false);
  pay.click(); await vi.waitFor(() => expect(f.observation.snapshot().confirmation_blocked).toBe(true));
  expect(f.observation.snapshot().operation).toBe('FAILED'); expect(f.requests).toHaveLength(before); expect(f.p.stripe.confirmPayment).not.toHaveBeenCalled();
  await cleanup(f);
});
it('records loaderror without inspecting even accessor event payloads', async () => {
  const f = await setup(); await atFrame(f); const getter = vi.fn(() => { throw new Error('private payload read'); });
  const payload = Object.defineProperty({}, 'error', { get: getter });
  (f.callbacks.get('loaderror') as unknown as (value: unknown) => void)(payload);
  await vi.waitFor(() => expect(f.observation.snapshot().operation).toBe('FAILED'));
  expect(getter).not.toHaveBeenCalled(); expect(f.observation.snapshot()).toMatchObject({ loaderror_seen: true, ready_seen: false }); await cleanup(f);
});
it('denies view confirmation while capability is still pending', async () => {
  const f = await setup(); const host = document.createElement('div'); f.widget.shadowRoot!.append(host); f.api.bindFormLoadDiagnostic(f.widget, host, 1);
  const pending = deferred<Response>(); vi.stubGlobal('fetch', () => pending.promise);
  const view = new CheckoutProviderView(f.client, host), controller = new AbortController();
  const mounting = view.mount(f.session, controller.signal); const outcome = mounting.catch(error => error);
  await expect(view.confirm()).rejects.toMatchObject({ code: 'CONFIRMATION_BLOCKED' });
  controller.abort(); pending.resolve(json(stripeCapability())); expect(await outcome).toMatchObject({ name: 'AbortError' });
  expect(f.p.stripe.confirmPayment).not.toHaveBeenCalled(); await cleanup(f);
});
