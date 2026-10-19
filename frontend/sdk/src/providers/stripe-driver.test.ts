import type { Stripe } from '@stripe/stripe-js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('./stripe-loader.js', () => ({ loadStripeClient: vi.fn() }));
import { loadStripeClient } from './stripe-loader.js';
import { mountStripePayment } from './stripe-driver.js';
import type { CheckoutState, ClientPaymentSession } from '../contracts.js';
import { armFormLoadDiagnostic, bindFormLoadDiagnostic, registerFormLoadWidget } from '../diagnostics/form-load-observer.js';
import { createProviderMount } from '../widget/provider-mount.js';

function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
const secret = 'pi_fixture_secret_sensitive';
function capability(key = 'pk_test_A'): ClientPaymentSession {
  const value = { checkoutId: '11111111-1111-4111-8111-111111111111', paymentId: '22222222-2222-4222-8222-222222222222', attemptId: '33333333-3333-4333-8333-333333333333', publishableKey: key, clientSecret: secret, expiresAt: new Date(Date.now() + 60000).toISOString(), returnUrl: 'https://shop.example/payment-return' };
  Object.defineProperty(value, 'clientSecret', { enumerable: false }); return Object.freeze(value);
}
const state: CheckoutState = { checkoutId: '11111111-1111-4111-8111-111111111111', paymentId: '22222222-2222-4222-8222-222222222222', amountMinor: 1000, currency: 'EUR', price: null, status: 'PROCESSING', expiresAt: '2099-01-01T00:00:00Z' };
// The external Stripe boundary is a controlled event source. The driver is real.
function provider() {
  const listeners = new Map<string, Set<() => void>>(); let host: HTMLElement | undefined;
  const frame = document.createElement('iframe');
  const element = {
    on: vi.fn((event: string, callback: () => void) => { const set = listeners.get(event) ?? new Set(); set.add(callback); listeners.set(event, set); return element; }),
    off: vi.fn((event: string, callback: () => void) => { listeners.get(event)?.delete(callback); return element; }),
    mount: vi.fn((container: HTMLElement) => { host = container; host.append(frame); }),
    destroy: vi.fn(() => { frame.remove(); host = undefined; }),
    focus: vi.fn(),
  };
  const elements = { create: vi.fn(() => element), update: vi.fn<(...args: unknown[]) => void | Promise<void>>(() => Promise.resolve()) };
  const stripe = { elements: vi.fn(() => elements), confirmPayment: vi.fn<(...args: unknown[]) => Promise<unknown>>(() => Promise.resolve({ paymentIntent: { status: 'succeeded', client_secret: secret } })) };
  const emit = (event: string) => { for (const listener of [...(listeners.get(event) ?? [])]) listener(); };
  return { stripe, elements, element, emit, listeners };
}
function setup() {
  const external = provider(); vi.mocked(loadStripeClient).mockResolvedValue(external.stripe as unknown as Stripe);
  const controller = new AbortController(); const container = document.createElement('div');
  const refresh = vi.fn<() => Promise<CheckoutState>>().mockResolvedValue(state);
  const options = { container, capability: capability(), signal: controller.signal, appearance: {}, locale: 'en' as const, refresh };
  return { ...external, controller, container, refresh, options };
}
async function ready(f: ReturnType<typeof setup>) {
  const pending = mountStripePayment(f.options);
  void pending.catch(() => {});
  await vi.waitFor(() => expect(f.element.mount).toHaveBeenCalledOnce());
  f.emit('ready'); return pending;
}
beforeEach(() => { vi.mocked(loadStripeClient).mockReset(); });

describe('internal loaderstart observation', () => {
  const cleanups: (() => void)[] = [];
  afterEach(() => { for (const cleanup of cleanups.splice(0)) cleanup(); });
  function diagnosticSetup() {
    const f = setup(); const widget = document.createElement('div'); registerFormLoadWidget(widget);
    const observation = armFormLoadDiagnostic(widget);
    const anchor = document.createElement('button'); widget.attachShadow({ mode: 'open' }).append(anchor); document.body.append(widget);
    const lease = createProviderMount(widget, anchor, 'STRIPE'), container = lease.host;
    bindFormLoadDiagnostic(widget, container, 1);
    cleanups.push(() => { f.controller.abort(); observation.close(); lease.destroy(); widget.remove(); });
    return { ...f, container, options: { ...f.options, container }, observation };
  }
  // Break: registering after mount misses a synchronous event; resolving on it invents readiness.
  it.each(['synchronous', 'delayed'])('observes %s loaderstart without inspecting payload or resolving mount', async timing => {
    const f = diagnosticSetup(); let resolved = false;
    const getter = vi.fn(() => { throw new Error('provider payload read'); });
    const payload = Object.defineProperty({}, 'elementType', { get: getter });
    function loaderstart() {
      expect(f.listeners.get('loaderstart')?.size, 'loaderstart listener must precede mount').toBe(1);
      for (const listener of f.listeners.get('loaderstart')!) (listener as (value: unknown) => void)(payload);
    }
    if (timing === 'synchronous') f.element.mount.mockImplementationOnce(() => { loaderstart(); });
    const pending = mountStripePayment(f.options).then(handle => { resolved = true; return handle; });
    void pending.catch(() => {});
    await vi.waitFor(() => expect(f.element.mount).toHaveBeenCalledOnce());
    if (timing === 'delayed') loaderstart();
    // Synchronous provider exceptions are safely mapped by the driver, so verify registration here too.
    expect(f.element.on.mock.calls.some(([event]) => event === 'loaderstart')).toBe(true);
    expect(f.observation.readinessSnapshot()).toEqual({ loaderstart_seen: true });
    expect(getter).not.toHaveBeenCalled(); await Promise.resolve(); expect(resolved).toBe(false);
    expect(f.observation.snapshot()).toMatchObject({ ready_seen: false, operation: 'PENDING' });
    f.emit('ready'); const handle = await pending;
    expect(resolved).toBe(true); expect(f.listeners.get('loaderstart')?.size).toBe(0); handle.destroy();
  });
  // Break: inferring loaderstart from ready or leaving listeners attached falsifies later evidence.
  it.each(['ready', 'loaderror', 'abort', 'mount-throw'])('keeps absent loaderstart false and removes its listener after %s', async outcome => {
    const f = diagnosticSetup(); let late: (() => void) | undefined;
    if (outcome === 'mount-throw') f.element.mount.mockImplementationOnce(() => { late = [...(f.listeners.get('loaderstart') ?? [])][0]; throw new Error('provider mount failure'); });
    const pending = mountStripePayment(f.options); const result = pending.catch(error => error);
    await vi.waitFor(() => expect(f.element.mount).toHaveBeenCalledOnce());
    expect(f.element.on.mock.calls.some(([event]) => event === 'loaderstart'), 'loaderstart listener must be registered').toBe(true);
    late ??= [...(f.listeners.get('loaderstart') ?? [])][0];
    if (outcome === 'abort') f.controller.abort();
    if (outcome === 'ready' || outcome === 'loaderror') f.emit(outcome);
    const value = await result;
    if (outcome === 'ready') value.destroy();
    else expect(value).toMatchObject(outcome === 'abort' ? { name: 'AbortError' } : { code: 'PROVIDER_TEMPORARILY_UNAVAILABLE' });
    expect(f.listeners.get('loaderstart')?.size).toBe(0);
    late?.(); f.emit('loaderstart');
    expect(f.observation.readinessSnapshot()).toEqual({ loaderstart_seen: false });
    expect(f.stripe.confirmPayment).not.toHaveBeenCalled(); expect(f.refresh).not.toHaveBeenCalled();
  });
});

describe('Stripe mount readiness and cleanup', () => {
  it('resolves only on actual ready and disables every wallet', async () => {
    const f = setup(); let resolved = false;
    const pending = mountStripePayment(f.options).then(handle => { resolved = true; return handle; });
    void pending.catch(() => {});
    await vi.waitFor(() => expect(f.element.mount).toHaveBeenCalledOnce());
    expect(resolved).toBe(false); expect(f.refresh).not.toHaveBeenCalled();
    expect(f.stripe.elements).toHaveBeenCalledWith({ clientSecret: secret, appearance: {}, locale: 'en' });
    expect(f.elements.create).toHaveBeenCalledWith('payment', { wallets: { applePay: 'never', googlePay: 'never', link: 'never' } });
    f.emit('ready'); const handle = await pending;
    expect(resolved).toBe(true); handle.destroy(); handle.destroy();
    expect(f.element.destroy).toHaveBeenCalledOnce(); expect(f.container.childElementCount).toBe(0);
    expect([...f.listeners.values()].every(set => set.size === 0)).toBe(true);
  });
  it.each([null, new Error(`malicious ${secret}`)])('safely rejects unavailable loader: %s', async result => {
    const f = setup(); if (result) vi.mocked(loadStripeClient).mockRejectedValue(result); else vi.mocked(loadStripeClient).mockResolvedValue(null);
    const error = await mountStripePayment(f.options).catch(error => error);
    expect(error).toMatchObject({ code: 'PROVIDER_TEMPORARILY_UNAVAILABLE' }); expect(String(error)).not.toContain(secret); expect(error.cause).toBeUndefined();
    expect(f.container.textContent).not.toContain(secret); expect(f.stripe.elements).not.toHaveBeenCalled(); expect(f.refresh).not.toHaveBeenCalled();
  });
  it('aborts pending loader promptly and suppresses its late client', async () => {
    const f = setup(); const loading = deferred<Stripe | null>(); vi.mocked(loadStripeClient).mockReturnValue(loading.promise);
    const pending = mountStripePayment(f.options); const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    f.controller.abort(new Error(`malicious ${secret}`)); await rejected;
    loading.resolve(f.stripe as unknown as Stripe); await Promise.resolve(); await Promise.resolve();
    expect(f.stripe.elements).not.toHaveBeenCalled(); expect(f.refresh).not.toHaveBeenCalled();
  });
  it('does not load when already aborted', async () => {
    const f = setup(); f.controller.abort(); await expect(mountStripePayment(f.options)).rejects.toMatchObject({ name: 'AbortError' }); expect(loadStripeClient).not.toHaveBeenCalled();
  });
  it.each(['abort', 'loaderror'])('cleans an Element awaiting ready after %s and ignores late events', async event => {
    const f = setup(); const pending = mountStripePayment(f.options); const outcome = pending.catch(error => error);
    await vi.waitFor(() => expect(f.element.mount).toHaveBeenCalledOnce());
    if (event === 'abort') f.controller.abort(); else f.emit('loaderror');
    expect(await outcome).toMatchObject(event === 'abort' ? { name: 'AbortError' } : { code: 'PROVIDER_TEMPORARILY_UNAVAILABLE' }); f.emit('ready'); f.emit('loaderror');
    expect(f.element.destroy).toHaveBeenCalledOnce(); expect(f.container.childElementCount).toBe(0); expect(f.stripe.confirmPayment).not.toHaveBeenCalled();
  });
  it('keeps separate clients for distinct account capabilities', async () => {
    const a = setup(); const b = setup(); b.options.capability = capability('pk_test_B');
    vi.mocked(loadStripeClient).mockResolvedValueOnce(a.stripe as unknown as Stripe).mockResolvedValueOnce(b.stripe as unknown as Stripe);
    const first = await ready(a); const second = await ready(b);
    expect(loadStripeClient).toHaveBeenNthCalledWith(1, 'pk_test_A'); expect(loadStripeClient).toHaveBeenNthCalledWith(2, 'pk_test_B');
    first.destroy(); expect(b.container.childElementCount).toBe(1); second.destroy();
  });
  it('rejects a provider key/secret mismatch without retry or refresh', async () => {
    const f = setup(); f.stripe.elements.mockImplementation(() => { throw new Error(`mismatched ${secret}`); });
    await expect(mountStripePayment(f.options)).rejects.toMatchObject({ code: 'PROVIDER_TEMPORARILY_UNAVAILABLE' });
    expect(loadStripeClient).toHaveBeenCalledOnce(); expect(f.refresh).not.toHaveBeenCalled();
  });
  it('destroys an Element returned after a synchronous abort during creation', async () => {
    const f = setup(); f.elements.create.mockImplementation(() => { f.controller.abort(); return f.element; });
    await expect(mountStripePayment(f.options)).rejects.toMatchObject({ name: 'AbortError' });
    expect(f.element.destroy).toHaveBeenCalledOnce(); expect(f.element.mount).not.toHaveBeenCalled(); expect(f.refresh).not.toHaveBeenCalled();
  });
});

describe('Stripe confirmation authority', () => {
  it('returns backend PROCESSING even when Stripe reports succeeded, discarding raw result', async () => {
    const f = setup(); const handle = await ready(f); const result = await handle.confirm();
    expect(result).toBe(state); expect(JSON.stringify(result)).not.toContain(secret);
    expect(f.stripe.confirmPayment).toHaveBeenCalledWith({ elements: f.elements, confirmParams: { return_url: 'https://shop.example/payment-return' }, redirect: 'if_required' });
    expect(f.refresh).toHaveBeenCalledOnce(); handle.destroy();
  });
  it('rejects a double click without dispatching twice', async () => {
    const f = setup(); const handle = await ready(f); const result = deferred<unknown>(); f.stripe.confirmPayment.mockReturnValue(result.promise);
    const first = handle.confirm(); await expect(handle.confirm()).rejects.toMatchObject({ code: 'PAYMENT_STATE_CONFLICT' });
    expect(f.stripe.confirmPayment).toHaveBeenCalledOnce(); result.resolve({}); await expect(first).resolves.toBe(state); handle.destroy();
  });
  it.each(['abort', 'destroy'])('suppresses late confirmation and further calls after %s', async action => {
    const f = setup(); const handle = await ready(f); const result = deferred<unknown>(); f.stripe.confirmPayment.mockReturnValue(result.promise);
    const pending = handle.confirm(); const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    if (action === 'abort') f.controller.abort(); else handle.destroy(); await rejected;
    result.resolve({ paymentIntent: { status: 'succeeded' } }); await Promise.resolve(); await Promise.resolve();
    await expect(handle.confirm()).rejects.toMatchObject({ name: 'AbortError' }); expect(f.refresh).not.toHaveBeenCalled(); expect(f.element.destroy).toHaveBeenCalledOnce();
  });
  it('suppresses a refresh that completes after destruction', async () => {
    const f = setup(); const handle = await ready(f); const result = deferred<CheckoutState>(); f.refresh.mockReturnValue(result.promise);
    const pending = handle.confirm(); const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    await vi.waitFor(() => expect(f.refresh).toHaveBeenCalledOnce()); handle.destroy(); await rejected; result.resolve(state);
  });
  it.each(['card_error', 'validation_error', 'api_error', 'reject'])('returns authoritative backend state after settled %s confirmation', async type => {
    const f = setup(); const handle = await ready(f);
    if (type === 'reject') f.stripe.confirmPayment.mockRejectedValue(new Error(secret)); else f.stripe.confirmPayment.mockResolvedValue({ error: { type, message: secret } });
    const authoritative = { ...state, status: 'SUCCEEDED' as const }; f.refresh.mockResolvedValue(authoritative);
    await expect(handle.confirm()).resolves.toBe(authoritative); expect(f.refresh).toHaveBeenCalledOnce(); expect(f.stripe.confirmPayment).toHaveBeenCalledOnce(); handle.destroy();
  });
  it.each(['card_error', 'validation_error', 'api_error', 'reject'])('returns unknown safely whenever backend refresh fails after %s', async type => {
    const f = setup(); const handle = await ready(f);
    if (type === 'reject') f.stripe.confirmPayment.mockRejectedValue(new Error(secret)); else f.stripe.confirmPayment.mockResolvedValue({ error: { type, message: secret, payment_intent: { client_secret: secret } } });
    f.refresh.mockRejectedValue(new Error(`backend ${secret}`));
    const error = await handle.confirm().catch(e => e);
    expect(error.code).toBe('PROVIDER_RESULT_UNKNOWN');
    expect(String(error)).not.toContain(secret); expect(error.cause).toBeUndefined(); expect(f.refresh).toHaveBeenCalledOnce(); handle.destroy();
  });
  it.each(['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'] as const)('focuses the live input for card/validation errors only after actionable backend %s', async status => {
    const f = setup(); const handle = await ready(f); f.refresh.mockResolvedValue({ ...state, status });
    f.stripe.confirmPayment.mockResolvedValue({ error: { type: status === 'CREATED' ? 'validation_error' : 'card_error', message: secret } });
    await expect(handle.confirm()).rejects.toMatchObject({ code: 'PROVIDER_DECLINED' }); expect(f.element.focus).toHaveBeenCalledOnce(); expect(f.refresh).toHaveBeenCalledOnce(); handle.destroy();
  });
  it.each(['api_error', 'reject'])('keeps %s confirmation unknown when backend remains CREATED', async type => {
    const f = setup(); const handle = await ready(f); f.refresh.mockResolvedValue({ ...state, status: 'CREATED' });
    if (type === 'reject') f.stripe.confirmPayment.mockRejectedValue(new Error(secret)); else f.stripe.confirmPayment.mockResolvedValue({ error: { type, message: secret } });
    await expect(handle.confirm()).rejects.toMatchObject({ code: 'PROVIDER_RESULT_UNKNOWN' }); expect(f.element.focus).not.toHaveBeenCalled(); expect(f.refresh).toHaveBeenCalledOnce(); handle.destroy();
  });
  it.each(['PROCESSING', 'UNKNOWN', 'FAILED', 'CANCELLED', 'EXPIRED', 'PARTIALLY_REFUNDED', 'REFUNDED'] as const)('returns backend %s despite browser card error', async status => {
    const f = setup(); const handle = await ready(f); const authoritative = { ...state, status }; f.refresh.mockResolvedValue(authoritative); f.stripe.confirmPayment.mockResolvedValue({ error: { type: 'card_error' } });
    await expect(handle.confirm()).resolves.toBe(authoritative); expect(f.element.focus).not.toHaveBeenCalled(); handle.destroy();
  });
  it('returns backend CREATED after a no-error browser result without manufacturing success', async () => {
    const f = setup(); const handle = await ready(f); const authoritative = { ...state, status: 'CREATED' as const }; f.refresh.mockResolvedValue(authoritative);
    await expect(handle.confirm()).resolves.toBe(authoritative); handle.destroy();
  });
  it.each(['checkoutId', 'paymentId'] as const)('rejects a refreshed state for another %s', async field => {
    const f = setup(); const handle = await ready(f); f.refresh.mockResolvedValue({ ...state, [field]: '99999999-9999-4999-8999-999999999999' });
    await expect(handle.confirm()).rejects.toMatchObject({ code: 'PROVIDER_RESULT_UNKNOWN' }); expect(f.refresh).toHaveBeenCalledOnce(); handle.destroy();
  });
});

describe('presentation updates', () => {
  it('serializes an active update and coalesces pending calls to the latest validated presentation', async () => {
    const f = setup(); const handle = await ready(f); const first = deferred<void>(); const latest = deferred<void>();
    f.elements.update.mockReturnValueOnce(first.promise).mockReturnValueOnce(latest.promise);
    const active = handle.updatePresentation({ appearance: { theme: 'flat' }, locale: 'en' });
    const superseded = handle.updatePresentation({ appearance: { theme: 'stripe' }, locale: 'pt' });
    const rejected = expect(superseded).rejects.toMatchObject({ name: 'AbortError' });
    const pending = handle.updatePresentation({ appearance: { theme: 'night' }, locale: 'pt' });
    await rejected; expect(f.elements.update).toHaveBeenCalledTimes(1);
    first.resolve(); await active;
    await vi.waitFor(() => expect(f.elements.update).toHaveBeenCalledTimes(2));
    expect(f.elements.update).toHaveBeenNthCalledWith(2, { appearance: { theme: 'night' }, locale: 'pt' });
    latest.resolve(); await pending;
    expect(loadStripeClient).toHaveBeenCalledOnce(); expect(f.elements.create).toHaveBeenCalledOnce(); expect(f.refresh).not.toHaveBeenCalled(); expect(f.stripe.confirmPayment).not.toHaveBeenCalled(); handle.destroy();
  });
  it('starts only the explicit latest pending update after an earlier async failure', async () => {
    const f = setup(); const handle = await ready(f); const first = deferred<void>(); f.elements.update.mockReturnValueOnce(first.promise).mockResolvedValueOnce(undefined);
    const active = handle.updatePresentation({ appearance: { theme: 'flat' } }); const rejected = expect(active).rejects.toMatchObject({ code: 'PROVIDER_TEMPORARILY_UNAVAILABLE' });
    const pending = handle.updatePresentation({ appearance: { theme: 'night' }, locale: 'pt' });
    expect(f.elements.update).toHaveBeenCalledTimes(1); first.reject(new Error(secret)); await rejected; await pending;
    expect(f.elements.update).toHaveBeenCalledTimes(2); expect(f.elements.update).toHaveBeenNthCalledWith(2, { appearance: { theme: 'night' }, locale: 'pt' }); handle.destroy();
  });
  it.each(['abort', 'destroy'])('clears active/pending presentation calls on %s and never starts a late queue item', async action => {
    const f = setup(); const handle = await ready(f); const first = deferred<void>(); f.elements.update.mockReturnValueOnce(first.promise);
    const active = handle.updatePresentation({ appearance: { theme: 'flat' } }); const activeRejected = expect(active).rejects.toMatchObject({ name: 'AbortError' });
    const pending = handle.updatePresentation({ appearance: { theme: 'night' }, locale: 'pt' }); const pendingRejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    if (action === 'abort') f.controller.abort(); else handle.destroy(); await Promise.all([activeRejected, pendingRejected]);
    first.resolve(); await Promise.resolve(); await Promise.resolve(); expect(f.elements.update).toHaveBeenCalledTimes(1); expect(f.element.destroy).toHaveBeenCalledOnce();
  });
  it('does not replace a valid pending update with malformed presentation', async () => {
    const f = setup(); const handle = await ready(f); const first = deferred<void>(); f.elements.update.mockReturnValueOnce(first.promise).mockResolvedValueOnce(undefined);
    const active = handle.updatePresentation({ appearance: {} }); const pending = handle.updatePresentation({ appearance: { theme: 'night' }, locale: 'pt' });
    await expect(handle.updatePresentation({ appearance: {}, locale: 'fr' } as never)).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' });
    expect(f.elements.update).toHaveBeenCalledTimes(1); first.resolve(); await active; await pending; expect(f.elements.update).toHaveBeenCalledTimes(2); handle.destroy();
  });
  it('updates only validated appearance/locale on the existing Elements with no financial operation', async () => {
    const f = setup(); const handle = await ready(f);
    await handle.updatePresentation({ appearance: { theme: 'night', variables: { colorPrimary: '#abcdef' } }, locale: 'pt' });
    expect(f.elements.update).toHaveBeenCalledWith({ appearance: { theme: 'night', variables: { colorPrimary: '#abcdef' } }, locale: 'pt' });
    expect(loadStripeClient).toHaveBeenCalledOnce(); expect(f.stripe.elements).toHaveBeenCalledOnce(); expect(f.elements.create).toHaveBeenCalledOnce(); expect(f.element.mount).toHaveBeenCalledOnce();
    expect(f.stripe.confirmPayment).not.toHaveBeenCalled(); expect(f.refresh).not.toHaveBeenCalled(); handle.destroy();
  });
  it('normalizes a void Elements.update result', async () => { const f = setup(); f.elements.update.mockReturnValue(undefined); const handle = await ready(f); await expect(handle.updatePresentation({ appearance: {}, locale: 'en' })).resolves.toBeUndefined(); handle.destroy(); });
  it('validates and updates the same locale value even with a caller accessor', async () => {
    const f = setup(); const handle = await ready(f); let reads = 0;
    await handle.updatePresentation({ appearance: {}, get locale() { return (++reads <= 2 ? 'en' : 'unsafe-locale') as 'en'; } });
    expect(f.elements.update).toHaveBeenCalledWith({ appearance: {}, locale: 'en' }); handle.destroy();
  });
  it.each([{ appearance: { rules: {} } }, { appearance: {}, locale: 'fr' }, { appearance: {}, amount: 10 }, { appearance: {}, clientSecret: secret }])('rejects unapproved presentation input %j before update', async presentation => {
    const f = setup(); const handle = await ready(f); await expect(handle.updatePresentation(presentation as never)).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' }); expect(f.elements.update).not.toHaveBeenCalled(); handle.destroy();
  });
  it('rejects invalid initial presentation before loading a client', async () => { const f = setup(); await expect(mountStripePayment({ ...f.options, appearance: { rules: {} } })).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' }); expect(loadStripeClient).not.toHaveBeenCalled(); });
  it('maps asynchronous presentation failure without exposing provider text', async () => {
    const f = setup(); const handle = await ready(f); f.elements.update.mockRejectedValue(new Error(secret)); const error = await handle.updatePresentation({ appearance: {} }).catch(e => e);
    expect(error.code).toBe('PROVIDER_TEMPORARILY_UNAVAILABLE'); expect(String(error)).not.toContain(secret); handle.destroy();
  });
  it.each(['abort', 'destroy'])('rejects updates after %s and suppresses an in-flight update completion', async action => {
    const f = setup(); const handle = await ready(f); const update = deferred<void>(); f.elements.update.mockReturnValue(update.promise);
    const pending = handle.updatePresentation({ appearance: {}, locale: 'pt' }); const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    if (action === 'abort') f.controller.abort(); else handle.destroy(); await rejected; update.resolve();
    await expect(handle.updatePresentation({ appearance: {} })).rejects.toMatchObject({ name: 'AbortError' }); expect(f.elements.update).toHaveBeenCalledOnce();
  });
});
