import { afterEach, expect, it, vi } from 'vitest';
vi.mock('../providers/stripe-loader.js', () => ({ loadStripeClient: vi.fn() }));
import { GenericPaymentWidget, defineGenericPaymentWidget } from '../widget/generic-payment-widget.js';
import { armFormLoadDiagnostic } from './form-load-observer.js';
import { createGenericPaymentClient } from '../client.js';
import { CheckoutProviderView } from '../widget/checkout-provider-view.js';
import { mountStripePayment } from '../providers/stripe-driver.js';
import { createProviderMount } from '../widget/provider-mount.js';
import { stripeUiProvider, stripeWire, stripeReadWire, stripeCapability } from '../test/stripe-ui-fixtures.js';
import { json } from '../test/fixtures.js';

defineGenericPaymentWidget();
type Api = {
  armManualPayment(widget: HTMLElement, options: { deadline: number; authorize: () => Promise<true> }): { close(): void };
  bindManualPayment(widget: HTMLElement, host: HTMLElement, generation: number): unknown;
  manualPaymentBinding(host: HTMLElement): unknown;
  consumeManualPaymentConfirmation(binding: unknown): Promise<void>;
};
async function api(): Promise<Api> {
  const modules = import.meta.glob('./manual-payment-authorization.ts');
  expect(modules['./manual-payment-authorization.ts'], 'internal one-confirmation lease must exist').toBeTypeOf('function');
  return await modules['./manual-payment-authorization.ts']!() as Api;
}
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
const leases = new Set<{ close(): void }>();
afterEach(() => { for (const lease of leases) lease.close(); leases.clear(); });
const pay = (widget: GenericPaymentWidget) => widget.shadowRoot!.querySelector('button')!;
async function setup(authorize: () => Promise<true>, deadline = Date.now() + 60000) {
  const internal = await api(), p = stripeUiProvider(), requests: string[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(`${init.method} ${new URL(url).pathname}`); return json(url.endsWith('/client-session') ? stripeCapability() : init.method === 'GET' ? stripeReadWire('CREATED') : stripeWire()); });
  const widget = new GenericPaymentWidget(); widget.siteKey = 'test-site'; widget.priceKey = 'lifetime'; widget.apiBaseUrl = 'https://api.test';
  const lease = internal.armManualPayment(widget, { deadline, authorize }); leases.add(lease); document.body.append(widget);
  pay(widget).click(); await vi.waitFor(() => expect(widget.querySelector('iframe')).toBe(p.frame));
  p.emit('ready'); await vi.waitFor(() => expect(widget.state).toBe('ready'));
  return { internal, p, requests, widget, lease };
}

// Break: dispatch before grant, consumption at two layers, or resetting consumption after a declined result.
it('waits for one authorization before the real widget driver call and never confirms twice', async () => {
  const permission = deferred<true>(), authorize = vi.fn(() => permission.promise), f = await setup(authorize);
  f.p.stripe.confirmPayment.mockResolvedValue({ error: { type: 'validation_error' } });
  const host = f.p.frame.parentElement!;
  expect(host.getRootNode()).toBe(document); expect(f.widget.shadowRoot!.querySelector('iframe')).toBeNull();
  pay(f.widget).click();
  expect(f.p.stripe.confirmPayment).toHaveBeenCalledTimes(0);
  await vi.waitFor(() => expect(authorize).toHaveBeenCalledTimes(1));
  pay(f.widget).dispatchEvent(new MouseEvent('click'));
  permission.resolve(true); await vi.waitFor(() => expect(f.widget.state).toBe('ready'));
  expect(f.p.stripe.confirmPayment).toHaveBeenCalledTimes(1);
  pay(f.widget).click(); await vi.waitFor(() => expect(f.widget.state).toBe('checking'));
  expect(authorize).toHaveBeenCalledTimes(1); expect(f.p.stripe.confirmPayment).toHaveBeenCalledTimes(1);
  expect(f.requests).toHaveLength(3); expect(f.p.frame.parentElement).toBe(host);
});

// Break: rejection, malformed permission or lost authority accidentally falls through to Stripe.
it.each(['reject', 'false', 'close', 'disconnect', 'reconfigure', 'generation', 'host-reinsert', 'host-replace', 'expired'])('denies %s during authorization permanently after late resolution', async action => {
  const permission = deferred<true>(), authorize = vi.fn(() => permission.promise), f = await setup(authorize);
  pay(f.widget).click(); await vi.waitFor(() => expect(authorize).toHaveBeenCalledTimes(1));
  expect(f.p.stripe.confirmPayment).toHaveBeenCalledTimes(0);
  const host = f.p.frame.parentElement!, binding = f.internal.manualPaymentBinding(host), before = f.requests.length;
  if (action === 'close') f.lease.close();
  if (action === 'disconnect') { f.widget.remove(); document.body.append(f.widget); }
  if (action === 'reconfigure') f.widget.priceKey = 'other';
  if (action === 'generation') f.internal.bindManualPayment(f.widget, host, 999);
  if (action === 'host-reinsert') { host.remove(); f.widget.append(host); }
  if (action === 'host-replace') { host.replaceWith(document.createElement('div')); }
  if (action === 'expired') vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 60001);
  if (action === 'reject') permission.reject(new Error('private authorization sentinel'));
  else permission.resolve(action === 'false' ? false as unknown as true : true);
  await vi.waitFor(() => expect(pay(f.widget)).toBeEnabled());
  await expect(f.internal.consumeManualPaymentConfirmation(binding)).rejects.toMatchObject({ code: 'CONFIRMATION_BLOCKED' });
  expect(authorize).toHaveBeenCalledTimes(1); expect(f.p.stripe.confirmPayment).toHaveBeenCalledTimes(0);
  if (!['disconnect', 'reconfigure'].includes(action)) expect(f.requests).toHaveLength(before);
  expect(() => f.internal.armManualPayment(f.widget, { deadline: Date.now() + 60000, authorize })).toThrow();
});

// Break: accepting a node outside the constructor registry, a used node, bad time or simultaneous leases.
it('arms only one known unused detached Catalog widget and validates the original deadline', async () => {
  const internal = await api(), authorize = async () => true as const;
  for (const deadline of [NaN, Infinity, Date.now(), Date.now() - 1]) expect(() => internal.armManualPayment(new GenericPaymentWidget(), { deadline, authorize })).toThrow();
  expect(() => internal.armManualPayment(document.createElement('div'), { deadline: Date.now() + 60000, authorize })).toThrow();
  const used = new GenericPaymentWidget(); document.body.append(used); used.remove();
  expect(() => internal.armManualPayment(used, { deadline: Date.now() + 60000, authorize })).toThrow();
  const fresh = new GenericPaymentWidget(), lease = internal.armManualPayment(fresh, { deadline: Date.now() + 60000, authorize }); leases.add(lease);
  expect(() => internal.armManualPayment(new GenericPaymentWidget(), { deadline: Date.now() + 60000, authorize })).toThrow();
  lease.close(); expect(() => internal.armManualPayment(fresh, { deadline: Date.now() + 60000, authorize })).toThrow();
});

// Break: presence checks disappear at close, allowing a diagnostic widget to acquire a new authority.
it.each(['form-first', 'form-closed', 'manual-first', 'manual-closed'])('rejects mixed arming for %s', async order => {
  const internal = await api(), widget = new GenericPaymentWidget(), options = { deadline: Date.now() + 60000, authorize: async () => true as const };
  if (order.startsWith('form')) {
    const observation = armFormLoadDiagnostic(widget); leases.add(observation); if (order === 'form-closed') observation.close();
    expect(() => internal.armManualPayment(widget, options)).toThrow();
  } else {
    const lease = internal.armManualPayment(widget, options); leases.add(lease); if (order === 'manual-closed') lease.close();
    expect(() => armFormLoadDiagnostic(widget)).toThrow();
  }
});

// Break: another armed widget could accidentally revive an earlier denied provider view or handle.
it.each(['view', 'handle'])('retains old form-only %s denial after a different manual widget is armed', async target => {
  const internal = await api(), p = stripeUiProvider(), widget = new GenericPaymentWidget();
  const observation = armFormLoadDiagnostic(widget); leases.add(observation);
  const form = await import('./form-load-observer.js');
  document.body.append(widget); const anchor = widget.shadowRoot!.querySelector('button')!;
  const { host } = createProviderMount(widget, anchor, 'STRIPE'); form.bindFormLoadDiagnostic(widget, host, 1);
  vi.stubGlobal('fetch', async (url: string) => json(url.endsWith('/client-session') ? stripeCapability() : stripeWire()));
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }), session = await client.createCatalogCheckout({ siteKey: 'test-site', priceKey: 'lifetime' });
  const controller = new AbortController(), view = new CheckoutProviderView(client, host);
  const mounting = target === 'view' ? view.mount(session, controller.signal) : mountStripePayment({ container: host, signal: controller.signal, capability: { checkoutId: session.checkoutId, paymentId: session.state.paymentId, attemptId: '44444444-4444-4444-8444-444444444444', publishableKey: 'pk_test_fixture', clientSecret: 'pi_fixture_secret_fixture', expiresAt: session.expiresAt, returnUrl: `${location.origin}/payment-return` }, appearance: {}, refresh: () => client.get(session) });
  await vi.waitFor(() => expect(p.frame.isConnected).toBe(true)); p.emit('ready'); const mounted = await mounting;
  observation.close(); const authorize = vi.fn(async () => true as const);
  const lease = internal.armManualPayment(new GenericPaymentWidget(), { deadline: Date.now() + 60000, authorize }); leases.add(lease);
  const confirm = target === 'view' ? () => view.confirm() : () => mounted!.confirm();
  await expect(confirm()).rejects.toMatchObject({ code: 'CONFIRMATION_BLOCKED' });
  controller.abort(); await expect(confirm()).rejects.toMatchObject({ code: 'CONFIRMATION_BLOCKED' });
  expect(authorize).not.toHaveBeenCalled(); expect(p.stripe.confirmPayment).toHaveBeenCalledTimes(0);
});
