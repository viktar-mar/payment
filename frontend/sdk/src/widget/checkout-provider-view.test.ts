import type { Stripe } from '@stripe/stripe-js';
import { expect, it, vi } from 'vitest';
vi.mock('../providers/stripe-loader.js', () => ({ loadStripeClient: vi.fn() }));
import { loadStripeClient } from '../providers/stripe-loader.js';
import { CheckoutProviderView } from './checkout-provider-view.js';
import { createGenericPaymentClient } from '../client.js';
import { json, wire, readWire } from '../test/fixtures.js';

const stripeWire = (status = 'CREATED') => ({ ...wire(status), provider_type: 'STRIPE', confirmation_mode: 'CLIENT_PROVIDER' });
function capability() {
  return { checkout_id: wire().checkout_id, payment_id: wire().payment_id, attempt_id: '44444444-4444-4444-8444-444444444444', provider_type: 'STRIPE', confirmation_mode: 'CLIENT_PROVIDER', publishable_key: 'pk_test_fixture', client_secret: 'pi_fixture_secret_sensitive', expires_at: wire().expires_at, return_url: `${location.origin}/payment-return` };
}
function provider() {
  const listeners = new Map<string, () => void>(); const frame = document.createElement('iframe');
  const element = { on: (name: string, fn: () => void) => { listeners.set(name, fn); return element; }, off: (name: string) => { listeners.delete(name); return element; }, mount: (host: HTMLElement) => host.append(frame), destroy: vi.fn(() => frame.remove()), focus: vi.fn() };
  const elements = { create: vi.fn(() => element), update: vi.fn<(...args: unknown[]) => Promise<void>>(() => Promise.resolve()) };
  const stripe = { elements: vi.fn(() => elements), confirmPayment: vi.fn(() => Promise.resolve({})) };
  vi.mocked(loadStripeClient).mockResolvedValue(stripe as unknown as Stripe);
  return { stripe, elements, element, frame, emit: (name: string) => listeners.get(name)?.() };
}
async function setup(status = 'CREATED') {
  const requests: Request[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    requests.push(new Request(url, init));
    return json(url.endsWith('/client-session') ? capability() : init.method === 'GET' ? { ...stripeWire(status), checkout_token: undefined } : stripeWire());
  });
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' });
  const session = await client.createCatalogCheckout({ siteKey: 'test-site', priceKey: 'lifetime' });
  const host = document.createElement('div'); document.body.append(host);
  const view = new CheckoutProviderView(client, host); const controller = new AbortController();
  return { requests, client, session, host, view, controller };
}

it('prepares once and withholds readiness until the real Element is ready', async () => {
  const p = provider(), f = await setup(); let resolved = false;
  const mounting = f.view.mount(f.session, f.controller.signal).then(() => { resolved = true; }); void mounting.catch(() => {});
  await vi.waitFor(() => expect(f.host.querySelector('iframe')).toBe(p.frame)); expect(resolved).toBe(false);
  expect(f.requests.map(r => r.method)).toEqual(['POST', 'POST']); expect(f.requests[1].url).toMatch(/\/client-session$/);
  expect(f.host.innerHTML).not.toMatch(/pk_test_|pi_fixture|clientSecret/);
  p.emit('ready'); await mounting; expect(resolved).toBe(true);
  f.view.destroy(); expect(f.host.childElementCount).toBe(0); expect(p.element.destroy).toHaveBeenCalledOnce();
});
it('uses one backend refresh after browser error and returns backend success', async () => {
  const p = provider(), f = await setup('SUCCEEDED');
  p.stripe.confirmPayment.mockResolvedValue({ error: { type: 'card_error', message: 'raw sensitive detail' } } as never);
  const mounting = f.view.mount(f.session, f.controller.signal); void mounting.catch(() => {});
  await vi.waitFor(() => expect(f.host.querySelector('iframe')).toBeTruthy()); p.emit('ready'); await mounting;
  const result = await f.view.confirm(); expect(result.status).toBe('SUCCEEDED'); expect(f.session.state.status).toBe('SUCCEEDED');
  expect(f.requests.map(r => r.method)).toEqual(['POST', 'POST', 'GET']); expect(p.stripe.confirmPayment).toHaveBeenCalledOnce(); f.view.destroy();
});
it('keeps Fake confirmation on the original server path without loading Stripe', async () => {
  vi.mocked(loadStripeClient).mockClear(); const requests: string[] = [];
  vi.stubGlobal('fetch', async (url: string) => { requests.push(url); return json(url.endsWith('/confirm') ? readWire('SUCCEEDED') : wire()); });
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); const session = await client.createCatalogCheckout({ siteKey: 'test-site', priceKey: 'lifetime' });
  const host = document.createElement('div'), view = new CheckoutProviderView(client, host);
  await view.mount(session, new AbortController().signal); expect((await view.confirm()).status).toBe('SUCCEEDED');
  expect(requests).toHaveLength(2); expect(requests[1]).toMatch(/\/confirm$/); expect(loadStripeClient).not.toHaveBeenCalled(); expect(host.childElementCount).toBe(0); view.destroy();
});
it('aborts preparation and never mounts a late capability', async () => {
  const p = provider(), f = await setup(); let release!: (value: Response) => void;
  vi.stubGlobal('fetch', () => new Promise<Response>(resolve => { release = resolve; }));
  const mounting = f.view.mount(f.session, f.controller.signal); const outcome = mounting.catch(e => e);
  await vi.waitFor(() => expect(release).toBeTypeOf('function'));
  f.controller.abort(); release(json(capability())); expect(await outcome).toMatchObject({ name: 'AbortError' });
  expect(f.host.childElementCount).toBe(0); expect(p.stripe.elements).not.toHaveBeenCalled();
});
it('applies latest presentation during readiness without replacing the Element or preparing again', async () => {
  const p = provider(), f = await setup(); const mounting = f.view.mount(f.session, f.controller.signal); void mounting.catch(() => {});
  await vi.waitFor(() => expect(f.host.querySelector('iframe')).toBeTruthy());
  await f.view.updatePresentation({ appearance: { variables: { colorPrimary: '#123456' } }, locale: 'pt' });
  p.emit('ready'); await mounting;
  expect(p.elements.update).toHaveBeenLastCalledWith({ appearance: { variables: { colorPrimary: '#123456' } }, locale: 'pt' });
  expect(f.host.querySelector('iframe')).toBe(p.frame); expect(f.requests).toHaveLength(2); expect(p.elements.create).toHaveBeenCalledOnce(); f.view.destroy();
});
it('uses a fixed abort error before mount even with a malicious cancellation reason', async () => {
  const f = await setup(); f.controller.abort(new Error('pi_fixture_secret_sensitive')); const error = await f.view.mount(f.session, f.controller.signal).catch(e => e);
  expect(error).toMatchObject({ name: 'AbortError' }); expect(String(error)).not.toContain('pi_fixture'); expect(f.requests).toHaveLength(1);
});
it('rejects unsupported presentation fields before they reach Stripe', async () => {
  const f = await setup(); await expect(f.view.updatePresentation({ appearance: {}, locale: 'en', amount: 1 } as never)).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' }); expect(f.requests).toHaveLength(1);
});
it.each(['reject', 'throw'])('retains the ready Stripe handle when initial presentation catch-up fails: %s', async failure => {
  const p = provider(), f = await setup('SUCCEEDED');
  p.elements.update.mockImplementationOnce(() => { if (failure === 'throw') throw new Error('raw sensitive'); return Promise.reject(new Error('raw sensitive')); });
  const mounting = f.view.mount(f.session, f.controller.signal); const outcome = mounting.then(() => 'ready', e => e.code);
  await vi.waitFor(() => expect(f.host.querySelector('iframe')).toBe(p.frame)); const host = p.frame.parentElement;
  await f.view.updatePresentation({ appearance: { variables: { colorPrimary: '#123456' } }, locale: 'pt' }); p.emit('ready');
  expect(await outcome).toBe('ready'); expect(p.frame.parentElement).toBe(host); expect(p.element.destroy).not.toHaveBeenCalled(); expect(f.requests).toHaveLength(2);
  await f.view.updatePresentation({ appearance: {}, locale: 'en' }); expect(p.elements.update).toHaveBeenCalledTimes(2); expect(f.requests).toHaveLength(2);
  expect((await f.view.confirm()).status).toBe('SUCCEEDED'); expect(f.requests.map(r => r.method)).toEqual(['POST', 'POST', 'GET']); expect(p.elements.create).toHaveBeenCalledOnce(); f.view.destroy();
});
it.each(['abort', 'destroy'])('cancellation still wins while initial presentation catch-up is pending: %s', async cancellation => {
  const p = provider(), f = await setup(); let reject!: (reason: unknown) => void;
  p.elements.update.mockImplementationOnce(() => new Promise<void>((_, no) => { reject = no; }));
  const mounting = f.view.mount(f.session, f.controller.signal); const outcome = mounting.catch(e => e);
  await vi.waitFor(() => expect(f.host.querySelector('iframe')).toBe(p.frame)); await f.view.updatePresentation({ appearance: {}, locale: 'pt' }); p.emit('ready');
  await vi.waitFor(() => expect(reject).toBeTypeOf('function')); if (cancellation === 'abort') f.controller.abort(); else f.view.destroy();
  expect(await outcome).toMatchObject({ name: 'AbortError' }); reject(new Error('late sensitive')); await Promise.resolve();
  expect(f.host.childElementCount).toBe(0); expect(p.element.destroy).toHaveBeenCalledOnce(); expect(f.requests).toHaveLength(2); expect(p.stripe.confirmPayment).not.toHaveBeenCalled();
});
