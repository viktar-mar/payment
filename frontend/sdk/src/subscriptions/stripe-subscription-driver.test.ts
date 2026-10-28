import { beforeEach, expect, it, vi } from 'vitest';
import * as driver from './action-driver.js';
import { createGenericPaymentClient } from '../client.js';
import { saveCustomerSession } from '../customer/session-storage.js';
import { base, json } from '../test/customer-fixtures.js';
import { actionWire, mutationWire, operationId, subscriptionId, subscriptionSession } from '../test/subscription-fixtures.js';
const provider = vi.hoisted(() => ({ load: vi.fn() }));
vi.mock('../providers/stripe-loader.js', () => ({ loadStripeClient: provider.load }));
const token = 'G'.repeat(43);
const tick = async () => { for (let n = 0; n < 20; n++) await Promise.resolve(); };
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason?: unknown) => void; const promise = new Promise<T>((a,b) => { resolve=a; reject=b; }); return { promise, resolve, reject }; }
function fixture(flow = 'PAYMENT', permission: unknown = true) {
  const events = new Map<string, () => void>(), destroy = vi.fn(), mount = vi.fn();
  const element = { on: vi.fn((name: string, handler: () => void) => { events.set(name,handler); }), off: vi.fn(), mount, destroy };
  const payment = vi.fn(async () => ({})), setup = vi.fn(async () => ({}));
  const stripe = { elements: vi.fn(() => ({ create: vi.fn(() => element) })), confirmPayment: payment, confirmSetup: setup };
  provider.load.mockResolvedValue(stripe);
  const calls: Request[] = [];
  const receipt = deferred<Response>(); let deferReceipt = false;
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    const request = new Request(url, init); calls.push(request);
    if (request.method === 'DELETE' && request.url.endsWith('/customer/session')) return new Response(null, { status: 204 });
    if (request.url.endsWith('/action')) return json({ ...actionWire(), kind: 'PROVIDER_CLIENT', secret: token });
    if (request.url.endsWith('/client-session')) return json({ provider: 'stripe', flow, publishable_key: 'pk_test_fixture', client_secret: flow === 'PAYMENT' ? 'pi_fixture_secret_fixture' : 'seti_fixture_secret_fixture', action_token: token, expires_at: new Date(Date.now()+30000).toISOString() });
    if (request.url.endsWith('/action-confirmations')) return deferReceipt ? receipt.promise : json({ ...mutationWire(), ...(permission === 'missing' ? {} : { browser_confirm_allowed: permission }) }, 202);
    throw new Error('Unexpected synthetic request');
  });
  const container = document.createElement('div'); document.body.append(container);
  vi.spyOn(container, 'getClientRects').mockReturnValue([{ width: 300, height: 100 }] as unknown as DOMRectList);
  const controller = new AbortController(), paymentClient = createGenericPaymentClient({ apiBaseUrl: base }), client = paymentClient.subscriptions, session = subscriptionSession();
  const input = { session, subscriptionId, operationId, signal: controller.signal };
  let current = true;
  async function prepare() {
    expect(driver).toHaveProperty('mountStripeSubscription', expect.any(Function));
    const action = await client.action(input);
    const pending = driver.mountStripeSubscription({ client, input, action, container, isCurrent: () => current && container.isConnected });
    void pending.catch(() => {});
    await tick(); return { pending };
  }
  return { prepare, calls, events, mount, destroy, payment, setup, stripe, receipt, controller, paymentClient, session, container, client, input, defer: () => { deferReceipt = true; }, revoke: () => { current = false; } };
}
beforeEach(() => { provider.load.mockReset(); });
it.each(['PAYMENT','SETUP'])('waits for ready and spends the grant before exactly one %s confirmation', async flow => {
  const f = fixture(flow); const { pending } = await f.prepare(); let ready = false; void pending.then(() => { ready=true; });
  await tick(); expect(f.mount).toHaveBeenCalledTimes(1); expect(ready).toBe(false); expect(f.payment).not.toHaveBeenCalled();
  f.events.get('ready')!(); const handle = await pending;
  f.defer(); const confirmation = handle.confirm(); const second = handle.confirm().catch(e=>e); await tick();
  expect((await second).code).toBe('PAYMENT_STATE_CONFLICT');
  expect(f.calls.filter(c=>c.url.endsWith('/action-confirmations'))).toHaveLength(1);
  f.receipt.resolve(json({ ...mutationWire(), browser_confirm_allowed: true },202)); await confirmation;
  expect(flow === 'PAYMENT' ? f.payment : f.setup).toHaveBeenCalledTimes(1);
  expect(flow === 'PAYMENT' ? f.setup : f.payment).not.toHaveBeenCalled();
  expect(f.destroy).toHaveBeenCalledTimes(1);
  await expect(handle.confirm()).rejects.toBeDefined();
  expect(f.calls.filter(c=>c.method==='POST')).toHaveLength(2);
});
it.each([false, 'missing', null, 'true'])('never confirms for false, missing or invalid receipt permission (%s)', async permission => {
  const f = fixture('PAYMENT',permission); const { pending } = await f.prepare(); f.events.get('ready')!(); const handle = await pending;
  await handle.confirm().catch(()=>{});
  expect(f.payment).not.toHaveBeenCalled(); expect(f.setup).not.toHaveBeenCalled(); expect(f.destroy).toHaveBeenCalledTimes(1);
});
it('burns a lost acknowledgement without automatic POST replay or provider confirmation', async () => {
  const f = fixture(); const { pending } = await f.prepare(); f.events.get('ready')!(); const handle = await pending; f.defer();
  const confirmation = handle.confirm().catch(e=>e); await tick(); f.receipt.reject(new Error('synthetic transport loss')); await confirmation;
  await handle.confirm().catch(()=>{}); expect(f.payment).not.toHaveBeenCalled();
  expect(f.calls.filter(c=>c.url.endsWith('/action-confirmations'))).toHaveLength(1); expect(f.destroy).toHaveBeenCalledTimes(1);
});
it.each(['abort','replace','disconnect','expiry'])('denies delayed preflight after %s and destroys old form', async reason => {
  const f = fixture(); const { pending } = await f.prepare(); f.events.get('ready')!(); const handle = await pending; f.defer();
  const confirmation = handle.confirm().catch(e=>e); await tick();
  if (reason==='abort') f.controller.abort(); if (reason==='replace') f.revoke(); if (reason==='disconnect') f.container.remove(); if (reason==='expiry') f.session.expiresAt='2000-01-01T00:00:00Z';
  f.receipt.resolve(json({ ...mutationWire(), browser_confirm_allowed: true },202)); await confirmation;
  expect(f.payment).not.toHaveBeenCalled(); expect(f.destroy).toHaveBeenCalledTimes(1);
});
it('retires the form after the sole asynchronous challenge returns and preserves fixed errors', async () => {
  const f = fixture(); const challenge = deferred<unknown>(); f.payment.mockImplementation(()=>challenge.promise as never);
  const { pending } = await f.prepare(); f.events.get('ready')!(); const handle = await pending;
  const confirmation = handle.confirm().catch(e=>e); await tick(); expect(f.payment).toHaveBeenCalledTimes(1); expect(f.destroy).not.toHaveBeenCalled();
  challenge.reject(new Error('private provider material')); const result = await confirmation;
  expect(result.code).toBe('PROVIDER_RESULT_UNKNOWN'); expect(String(result)).not.toContain('private'); expect(f.destroy).toHaveBeenCalledTimes(1);
});
it('does not mount into a hidden host or treat iframe insertion as ready', async () => {
  const f = fixture(); f.container.hidden=true; const { pending } = await f.prepare(); await expect(pending).rejects.toBeDefined(); expect(f.mount).not.toHaveBeenCalled();
});
it.each(['logout','replacement'])('retires immediately on accepted Customer %s while a challenge is pending', async reason => {
  const f=fixture();const challenge=deferred<unknown>();f.payment.mockImplementation(()=>challenge.promise as never);
  const {pending}=await f.prepare();f.events.get('ready')!();const handle=await pending;const confirmation=handle.confirm().catch(e=>e);await tick();
  expect(f.payment).toHaveBeenCalledTimes(1);
  if(reason==='logout')await f.paymentClient.logoutCustomerSession(f.session);
  else saveCustomerSession({...f.session,sessionToken:'R'.repeat(43)});
  expect(f.destroy).toHaveBeenCalledTimes(1);
  challenge.resolve({});await confirmation;expect(f.payment).toHaveBeenCalledTimes(1);
});
it('cannot mount after a persisted Customer authority record is removed during the loader await',async()=>{
  const f=fixture();saveCustomerSession(f.session);const loaded=deferred<typeof f.stripe>();provider.load.mockReturnValue(loaded.promise);
  const {pending}=await f.prepare();sessionStorage.clear();loaded.resolve(f.stripe);
  try { await tick();expect(f.mount).not.toHaveBeenCalled();await expect(pending).rejects.toBeDefined(); }
  finally { f.controller.abort(); }
});
it('denies UNKNOWN even if a malformed winning receipt claims browser permission',async()=>{
  const f=fixture();const {pending}=await f.prepare();f.events.get('ready')!();const handle=await pending;f.defer();
  const confirmation=handle.confirm().catch(e=>e);await tick();f.receipt.resolve(json({...mutationWire(),status:'UNKNOWN',browser_confirm_allowed:true},202));await confirmation;
  expect(f.payment).not.toHaveBeenCalled();expect(f.destroy).toHaveBeenCalledTimes(1);
});
it('cannot reopen a locally spent grant by retrieving another action object',async()=>{
  const f=fixture('PAYMENT',false);const {pending}=await f.prepare();f.events.get('ready')!();const handle=await pending;await handle.confirm().catch(()=>{});
  const retry=await f.prepare();await expect(retry.pending).rejects.toMatchObject({code:'INVALID_SESSION'});
  expect(f.calls.filter(r=>r.url.endsWith('/client-session'))).toHaveLength(1);expect(f.payment).not.toHaveBeenCalled();
});
it('retires an invalidated in-place Customer authority before any Pay preflight',async()=>{
  const f=fixture();const {pending}=await f.prepare();f.events.get('ready')!();const handle=await pending;
  f.session.scopes=['SUBSCRIPTION_READ'];await expect(handle.confirm()).rejects.toBeDefined();
  expect(f.destroy).toHaveBeenCalledTimes(1);expect(f.calls.filter(r=>r.url.endsWith('/action-confirmations'))).toHaveLength(0);expect(f.payment).not.toHaveBeenCalled();
});
it('cleans a loaderror and suppresses provider details',async()=>{
  const f=fixture();const {pending}=await f.prepare();f.events.get('loaderror')!();
  await expect(pending).rejects.toMatchObject({code:'PROVIDER_TEMPORARILY_UNAVAILABLE'});expect(f.destroy).toHaveBeenCalledTimes(1);expect(f.payment).not.toHaveBeenCalled();
});
it('never mounts delayed loader work after abort or page return',async()=>{
  const f=fixture();const loaded=deferred<typeof f.stripe>();provider.load.mockReturnValue(loaded.promise);const {pending}=await f.prepare();
  window.dispatchEvent(new Event('pagehide'));loaded.resolve(f.stripe);await expect(pending).rejects.toBeDefined();expect(f.mount).not.toHaveBeenCalled();expect(f.payment).not.toHaveBeenCalled();
});
it('bounds the ready wait to the session expiry without mounting again',async()=>{
  vi.useFakeTimers();const f=fixture();const {pending}=await f.prepare();expect(f.mount).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(30001);await expect(pending).rejects.toBeDefined();expect(f.destroy).toHaveBeenCalledTimes(1);expect(f.payment).not.toHaveBeenCalled();
});
