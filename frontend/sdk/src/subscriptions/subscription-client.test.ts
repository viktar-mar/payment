import { expect, it, vi } from 'vitest';
import { createGenericPaymentClient } from '../client.js';
import { base, customerToken, json } from '../test/customer-fixtures.js';
import { actionWire, billingWire, eventWire, mutationWire, offersWire, operationId, operationWire, portalWire, productId, quoteWire, returnUrl, subscriptionId, subscriptionPriceId, subscriptionSession, subscriptionWire, billingId } from '../test/subscription-fixtures.js';
import * as sdk from '../index.js';
it.each(['http://localhost:4173/manage?screen=subscriptions', 'http://127.0.0.1:4173/manage', 'http://[::1]:4173/manage', 'https://shop.test/manage'])(
  'round trips the canonical unmarked Portal return %s', async returnUrl => {
    const c = createGenericPaymentClient({ apiBaseUrl: base }).subscriptions;
    const navigationUrl = returnUrl + (returnUrl.includes('?') ? '&' : '?') + `subscriptionPortal=simulated&operationId=${operationId}`;
    const seen: Request[] = [];
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { seen.push(new Request(url, init)); return json({ ...portalWire(), navigation_url: navigationUrl }, 201); });
    await expect(c.portal({ session: subscriptionSession(), subscriptionId, idempotencyKey: operationId, returnUrl })).resolves.toMatchObject({ navigationUrl });
    expect(seen).toHaveLength(1); expect(await seen[0]!.json()).toEqual({ return_url: returnUrl });
  });
it.each(['http://external.test/manage', 'http://localhost.evil/manage', 'http://127.0.0.2/manage', 'http://127.1/manage', 'http://2130706433/manage', 'http://user@localhost/manage', 'http://localhost/manage#x', 'http://localhost:0/manage', 'https://shop.test/manage?subscriptionPortal=simulated', 'https://shop.test/manage?operationId='+operationId, 'http://localhost/manage%0a'])('rejects unsafe or already marked Portal return before dispatch: %s', async returnUrl => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  await expect(createGenericPaymentClient({ apiBaseUrl: base }).subscriptions.portal({ session: subscriptionSession(), subscriptionId, idempotencyKey: operationId, returnUrl })).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' });
  expect(fetch).not.toHaveBeenCalled();
});
it('exposes a headless subscription client with explicit-session methods', () => {
  expect(createGenericPaymentClient({ apiBaseUrl: 'https://api.test' })).toHaveProperty('subscriptions.list', expect.any(Function));
});
it.each(['get', 'list'] as const)('rejects a uniformly wrong response Site after dispatching %s with a valid Site-bound session', async method => {
  const client = createGenericPaymentClient({ apiBaseUrl: base }); const session = subscriptionSession();
  const wrong = { ...subscriptionWire(), site_id: operationId, access: { ...subscriptionWire().access, site_id: operationId } };
  const fetchMock = vi.fn(async () => json(method === 'get' ? wrong : { items: [wrong], next_cursor: null })); vi.stubGlobal('fetch', fetchMock);
  const result = await (method === 'get' ? client.subscriptions.get({ session, subscriptionId }) : client.subscriptions.list({ session })).catch(error => error);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(result).toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('polls only GETs without overlap and stops at a confirmed terminal operation', async () => {
  expect(sdk).toHaveProperty('watchSubscriptionOperation'); vi.useFakeTimers();
  const c = createGenericPaymentClient({ apiBaseUrl: base }).subscriptions; const session = subscriptionSession();
  const seen: { method?: string; at: number }[] = []; const started = Date.now();
  vi.stubGlobal('fetch', async (_: string, init: RequestInit) => { seen.push({ method: init.method, at: Date.now() - started }); return json({ ...operationWire(), status: seen.length === 6 ? 'SUCCEEDED' : 'PROCESSING' }); });
  const statuses: string[] = []; const pending = (async () => { for await (const op of sdk.watchSubscriptionOperation(c, { session, subscriptionId, operationId })) statuses.push(op.status); })();
  await vi.advanceTimersByTimeAsync(20000); await pending;
  expect(statuses).toEqual(['PROCESSING', 'PROCESSING', 'PROCESSING', 'PROCESSING', 'PROCESSING', 'SUCCEEDED']);
  expect(seen.every(s => s.method === 'GET')).toBe(true);
  for (let n = 1; n < seen.length; n++) expect(seen[n].at - seen[n - 1].at).toBeLessThanOrEqual(5000);
});
it('bounds polling to ten minutes and returns UNKNOWN without another financial command', async () => {
  expect(sdk).toHaveProperty('watchSubscriptionOperation'); vi.useFakeTimers();
  const c = createGenericPaymentClient({ apiBaseUrl: base }).subscriptions; const session = subscriptionSession();
  let calls = 0; vi.stubGlobal('fetch', async () => { calls++; return json(operationWire()); });
  const statuses: string[] = []; const pending = (async () => { for await (const op of sdk.watchSubscriptionOperation(c, { session, subscriptionId, operationId })) statuses.push(op.status); })();
  await vi.advanceTimersByTimeAsync(600001); await pending;
  expect(statuses.at(-1)).toBe('UNKNOWN'); const bounded = calls; await vi.advanceTimersByTimeAsync(20000); expect(calls).toBe(bounded); expect(calls).toBeLessThan(160);
});
it('aborts an in-flight polling read on the ten minute deadline', async () => {
  expect(sdk).toHaveProperty('watchSubscriptionOperation'); vi.useFakeTimers();
  const c = createGenericPaymentClient({ apiBaseUrl: base }).subscriptions; const session = subscriptionSession();
  let reads = 0; let aborted = false; vi.stubGlobal('fetch', (_: string, init: RequestInit) => {
    if (++reads === 1) return Promise.resolve(json(operationWire()));
    return new Promise<Response>((_resolve, reject) => init.signal?.addEventListener('abort', () => { aborted = true; reject(new DOMException('Aborted', 'AbortError')); }));
  });
  const statuses: string[] = []; const pending = (async () => { for await (const op of sdk.watchSubscriptionOperation(c, { session, subscriptionId, operationId })) statuses.push(op.status); })();
  await vi.advanceTimersByTimeAsync(600001); await pending; expect(aborted).toBe(true); expect(statuses).toEqual(['PROCESSING', 'UNKNOWN']);
});
it('sends exact Customer routes, bodies, statuses and caller intent keys for every method', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: base }); expect(client).toHaveProperty('subscriptions');
  const session = subscriptionSession(); const input = { session, subscriptionId }; const mutation = { ...input, idempotencyKey: 'intent-1' };
  const seen: Request[] = []; let reply: unknown; let status = 200;
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { seen.push(new Request(url, init)); return json(reply, status); });
  const c = client.subscriptions;
  const cases: [() => Promise<unknown>, string, string, unknown, unknown, number, boolean][] = [
    [() => c.list({ session, limit: 1, cursor: 'opaque+/=' }), '/subscriptions?limit=1&cursor=opaque%2B%2F%3D', 'GET', undefined, { items: [subscriptionWire()], next_cursor: null }, 200, false],
    [() => c.offers({ session, productId }), `/subscription-products/${productId}/offers`, 'GET', undefined, offersWire(), 200, false],
    [() => c.get(input), `/subscriptions/${subscriptionId}`, 'GET', undefined, subscriptionWire(), 200, false],
    [() => c.history(input), `/subscriptions/${subscriptionId}/events?limit=20`, 'GET', undefined, { items: [eventWire()], next_cursor: null }, 200, false],
    [() => c.billingRecords(input), `/subscriptions/${subscriptionId}/billing-records?limit=20`, 'GET', undefined, { items: [billingWire()], next_cursor: null }, 200, false],
    [() => c.create({ session, priceId: subscriptionPriceId, recurringConsent: true, idempotencyKey: 'intent-1' }), '/subscriptions', 'POST', { price_id: subscriptionPriceId, recurring_consent: true }, mutationWire(), 202, true],
    [() => c.preview({ ...input, targetPriceId: subscriptionPriceId, timing: 'NEXT_PERIOD' }), `/subscriptions/${subscriptionId}/change-preview`, 'POST', { target_price_id: subscriptionPriceId, timing: 'NEXT_PERIOD' }, quoteWire(), 200, false],
    [() => c.change({ ...mutation, quoteId: operationId }), `/subscriptions/${subscriptionId}/changes`, 'POST', { quote_id: operationId }, mutationWire(), 202, true],
    [() => c.clearChange(mutation), `/subscriptions/${subscriptionId}/changes/clear`, 'POST', {}, mutationWire(), 202, true],
    [() => c.cancel({ ...mutation, mode: 'IMMEDIATE', confirmation: subscriptionId }), `/subscriptions/${subscriptionId}/cancellation`, 'POST', { mode: 'IMMEDIATE', confirmation: subscriptionId }, mutationWire(), 202, true],
    [() => c.withdrawCancellation(mutation), `/subscriptions/${subscriptionId}/cancellation/withdraw`, 'POST', {}, mutationWire(), 202, true],
    [() => c.setupMethod({ ...mutation, recurringConsent: true }), `/subscriptions/${subscriptionId}/method-setups`, 'POST', { recurring_consent: true }, mutationWire(), 202, true],
    [() => c.recover({ ...mutation, billingRecordId: billingId }), `/subscriptions/${subscriptionId}/recoveries`, 'POST', { billing_record_id: billingId }, mutationWire(), 202, true],
    [() => c.resume({ ...mutation, recurringConsent: true }), `/subscriptions/${subscriptionId}/resumptions`, 'POST', { recurring_consent: true }, mutationWire(), 202, true],
    [() => c.operation({ ...input, operationId }), `/subscriptions/${subscriptionId}/operations/${operationId}`, 'GET', undefined, operationWire(), 200, false],
    [() => c.action({ ...input, operationId }), `/subscriptions/${subscriptionId}/operations/${operationId}/action`, 'GET', undefined, actionWire(), 200, false],
    [() => c.confirmAction({ ...mutation, operationId, actionToken: 'S'.repeat(43) }), `/subscriptions/${subscriptionId}/operations/${operationId}/action-confirmations`, 'POST', { action_token: 'S'.repeat(43) }, mutationWire(), 202, true],
    [() => c.portal({ ...mutation, returnUrl }), `/subscriptions/${subscriptionId}/portal-sessions`, 'POST', { return_url: returnUrl }, portalWire(), 201, true],
  ];
  for (const [call, path, method, body, response, expectedStatus, keyed] of cases) {
    reply = response; status = expectedStatus; expect(await call()).toBeDefined();
    const r = seen.at(-1)!; expect(r.url).toBe(base + '/api/v1/customer' + path); expect(r.method).toBe(method);
    expect(r.headers.get('authorization') === `Bearer ${customerToken}`).toBe(true);
    expect(r.headers.get('idempotency-key')).toBe(keyed ? 'intent-1' : null);
    expect(r.credentials).toBe('omit'); expect(r.cache).toBe('no-store'); expect(r.redirect).toBe('error');
    expect(body === undefined ? undefined : await r.json()).toEqual(body);
  }
});
it.each(['badId', 'key', 'consent', 'extra', 'limit', 'cursor', 'confirmation', 'returnUrl'])('rejects invalid input %s before dispatch', async kind => {
  const c = createGenericPaymentClient({ apiBaseUrl: base }).subscriptions; const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
  expect(c).toBeDefined(); const session = subscriptionSession(); const mutation = { session, subscriptionId, idempotencyKey: 'intent-1' };
  let work: Promise<unknown>;
  if (kind === 'badId') work = c.get({ session, subscriptionId: '../escape' });
  else if (kind === 'key') work = c.clearChange({ ...mutation, idempotencyKey: '' });
  else if (kind === 'consent') work = c.setupMethod({ ...mutation, recurringConsent: undefined as never });
  else if (kind === 'extra') work = c.get({ ...mutation } as never);
  else if (kind === 'limit') work = c.list({ session, limit: 101 });
  else if (kind === 'cursor') work = c.list({ session, cursor: ' ' });
  else if (kind === 'confirmation') work = c.cancel({ ...mutation, mode: 'IMMEDIATE', confirmation: operationId });
  else work = c.portal({ ...mutation, returnUrl: 'javascript:alert(1)' });
  await expect(work).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' }); expect(fetchMock).not.toHaveBeenCalled();
});
it.each(['wrongCustomer', 'wrongSubscription', 'wrongOperation', 'wrongProduct', 'pageOverflow', 'detailSize', 'pageSize', 'redirect', 'wrongStatus', 'error'])('rejects response boundary violation %s', async kind => {
  const c = createGenericPaymentClient({ apiBaseUrl: base }).subscriptions; expect(c).toBeDefined(); const session = subscriptionSession();
  let response = json(subscriptionWire()); let work: () => Promise<unknown> = () => c.get({ session, subscriptionId });
  if (kind === 'wrongCustomer') response = json({ ...subscriptionWire(), customer_id: operationId, access: { ...subscriptionWire().access, customer_id: operationId } });
  if (kind === 'wrongSubscription') response = json({ ...subscriptionWire(), subscription_id: operationId, access: { ...subscriptionWire().access, subscription_id: operationId } });
  if (kind === 'wrongOperation') { response = json({ ...operationWire(), operation_id: billingId }); work = () => c.operation({ session, subscriptionId, operationId }); }
  if (kind === 'wrongProduct') { response = json(offersWire()); work = () => c.offers({ session, productId: billingId }); }
  if (kind === 'pageOverflow') { response = json({ items: [subscriptionWire(), subscriptionWire()], next_cursor: null }); work = () => c.list({ session, limit: 1 }); }
  if (kind === 'detailSize') response = new Response(' '.repeat(32769));
  if (kind === 'pageSize') { response = new Response(' '.repeat(131073)); work = () => c.list({ session }); }
  if (kind === 'redirect') response = new Response(null, { status: 302 });
  if (kind === 'wrongStatus') response = json(subscriptionWire(), 201);
  if (kind === 'error') response = json({ code: 'private-error', detail: 'private payload' }, 500);
  vi.stubGlobal('fetch', async () => response); await expect(work()).rejects.toHaveProperty('code', kind === 'error' ? 'REQUEST_FAILED' : 'INVALID_RESPONSE');
});
