import { expect, it, vi } from 'vitest';
import { createGenericPaymentClient } from '../client.js';
import { PaymentSdkError } from '../errors.js';
import { authInput, base, customerWire, future, json, sessionKey } from '../test/customer-fixtures.js';
import { actionWire, mutationWire, operationId, subscriptionId, subscriptionPriceId, subscriptionSession, subscriptionWire } from '../test/subscription-fixtures.js';
import * as sdk from '../index.js';
it('requires explicit subscription authority before fetching', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' });
  expect(client).toHaveProperty('subscriptions');
  const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
  await expect(client.subscriptions.list({ session: undefined as never })).rejects.toMatchObject({ code: 'INVALID_SESSION' });
  expect(fetchMock).not.toHaveBeenCalled();
});
for (const flow of ['site-token', 'oidc', 'otp'] as const) it.each(['failed', 'aborted', 'malformed'])('preserves current subscription reads and commands after '+flow+' authentication is %s', async outcome => {
  const client = createGenericPaymentClient({ apiBaseUrl: base });
  const wire = { ...customerWire(), scopes: subscriptionSession().scopes, site_id: subscriptionSession().siteId };
  vi.stubGlobal('fetch', async () => json(wire)); const session = await client.exchangeSiteToken(authInput);
  vi.stubGlobal('fetch', async () => json(subscriptionWire())); await client.subscriptions.get({ session, subscriptionId });
  const prior = sessionStorage.getItem(sessionKey()); const abort = new AbortController();
  const authenticationFetch = vi.fn(async () => {
    if (outcome === 'aborted') abort.abort();
    return outcome === 'failed' ? json({}, 401) : json(outcome === 'malformed' ? { ...wire, scopes: ['SUBSCRIPTION_ADMIN'] } : wire);
  }); vi.stubGlobal('fetch', authenticationFetch);
  const pending = flow === 'site-token' ? client.exchangeSiteToken({ ...authInput, signal: abort.signal })
    : flow === 'oidc' ? client.exchangeOidcToken({ ...authInput, signal: abort.signal, attemptId: operationId, nonce: 'N'.repeat(43), expiresAt: future() })
    : client.verifyEmailOtp({ siteKey: authInput.siteKey, challengeId: operationId, code: '123456', intent: 'AUTHENTICATE', signal: abort.signal });
  expect(await pending.catch(error => error)).toBeInstanceOf(Error); expect(authenticationFetch).toHaveBeenCalledTimes(1);
  expect(sessionStorage.getItem(sessionKey()) === prior).toBe(true);
  const fetchMock = vi.fn(async (_url: string, init: RequestInit) => json(init.method === 'GET' ? subscriptionWire() : mutationWire(), init.method === 'GET' ? 200 : 202)); vi.stubGlobal('fetch', fetchMock);
  await expect(client.subscriptions.get({ session, subscriptionId })).resolves.toHaveProperty('subscriptionId', subscriptionId);
  await expect(client.subscriptions.clearChange({ session, subscriptionId, idempotencyKey: 'after-failed-auth' })).resolves.toHaveProperty('operationId', operationId);
  expect(fetchMock).toHaveBeenCalledTimes(2);
});
it('keeps stale successful login fenced after a newer failed attempt', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: base }); let finish!: (response: Response) => void;
  vi.stubGlobal('fetch', () => new Promise<Response>(resolve => { finish = resolve; }));
  const stale = client.exchangeSiteToken(authInput).catch(error => error);
  vi.stubGlobal('fetch', async () => json({}, 401)); await client.exchangeSiteToken(authInput).catch(() => {});
  finish(json({ ...customerWire(), scopes: subscriptionSession().scopes, site_id: subscriptionSession().siteId }));
  expect(await stale).toMatchObject({ name: 'AbortError' }); expect(sessionStorage.getItem(sessionKey())).toBeNull();
});
it('rejects future old-session work after a successful same-Customer bearer renewal', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: base });
  const wire = { ...customerWire(), scopes: subscriptionSession().scopes, site_id: subscriptionSession().siteId };
  vi.stubGlobal('fetch', async () => json(wire)); const session = await client.exchangeSiteToken(authInput);
  vi.stubGlobal('fetch', async () => json(subscriptionWire())); await client.subscriptions.get({ session, subscriptionId });
  vi.stubGlobal('fetch', async () => json({ ...wire, session_token: 'R'.repeat(43) })); const renewed = await client.exchangeSiteToken(authInput);
  const fetchMock = vi.fn(async () => json(subscriptionWire())); vi.stubGlobal('fetch', fetchMock);
  await expect(client.subscriptions.get({ session, subscriptionId })).rejects.toMatchObject({ code: 'INVALID_SESSION' }); expect(fetchMock).not.toHaveBeenCalled();
  await expect(client.subscriptions.get({ session: renewed, subscriptionId })).resolves.toHaveProperty('subscriptionId', subscriptionId); expect(fetchMock).toHaveBeenCalledTimes(1);
});
it('remembers removal of a previously bound persisted authority across clients', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: base }); const other = createGenericPaymentClient({ apiBaseUrl: base });
  vi.stubGlobal('fetch', async () => json({ ...customerWire(), scopes: subscriptionSession().scopes, site_id: subscriptionSession().siteId }));
  const session = await client.exchangeSiteToken(authInput);
  vi.stubGlobal('fetch', async () => json(subscriptionWire())); await client.subscriptions.get({ session, subscriptionId });
  vi.stubGlobal('fetch', async () => new Response(null, { status: 204 })); await other.logoutCustomerSession(session);
  const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
  await expect(client.subscriptions.get({ session, subscriptionId })).rejects.toMatchObject({ code: 'INVALID_SESSION' }); expect(fetchMock).not.toHaveBeenCalled();
});
it('binds action helper consumption to its original client, operation, authority and expiry', async () => {
  expect(sdk).toHaveProperty('confirmSubscriptionAction');
  const client = createGenericPaymentClient({ apiBaseUrl: base }); const session = subscriptionSession();
  vi.stubGlobal('fetch', async () => json(actionWire())); const action = await client.subscriptions.action({ session, subscriptionId, operationId });
  const input = { session, subscriptionId, operationId, idempotencyKey: 'confirm-1' };
  const fetchMock = vi.fn(async () => json(mutationWire(), 202)); vi.stubGlobal('fetch', fetchMock);
  await expect(sdk.confirmSubscriptionAction(client.subscriptions, { ...input, session: { ...session, sessionToken: 'R'.repeat(43) } }, action)).rejects.toMatchObject({ code: 'INVALID_SESSION' });
  await expect(sdk.confirmSubscriptionAction(client.subscriptions, { ...input, session: { ...session, siteId: operationId } }, action)).rejects.toMatchObject({ code: 'INVALID_SESSION' });
  await expect(sdk.confirmSubscriptionAction(client.subscriptions, { ...input, operationId: subscriptionId }, action)).rejects.toMatchObject({ code: 'INVALID_SESSION' });
  await expect(sdk.confirmSubscriptionAction(createGenericPaymentClient({ apiBaseUrl: base }).subscriptions, input, action)).rejects.toMatchObject({ code: 'INVALID_SESSION' });
  expect(fetchMock).not.toHaveBeenCalled();
  expect((await sdk.confirmSubscriptionAction(client.subscriptions, input, action)).operationId).toBe(operationId); expect(fetchMock).toHaveBeenCalledTimes(1);
  const before = fetchMock.mock.calls.length;
  action.expiresAt = '2000-01-01T00:00:00Z';
  await expect(sdk.confirmSubscriptionAction(client.subscriptions, input, action)).rejects.toMatchObject({ code: 'INVALID_SESSION' }); expect(fetchMock).toHaveBeenCalledTimes(before);
});
it('does not retry an accepted mutation after an unreadable response', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response('{', { status: 202, headers: { 'content-type': 'application/json' } })); vi.stubGlobal('fetch', fetchMock);
  const client = createGenericPaymentClient({ apiBaseUrl: base }); expect(client).toHaveProperty('subscriptions');
  await expect(client.subscriptions.create({ session: subscriptionSession(), priceId: subscriptionPriceId, recurringConsent: true, idempotencyKey: 'subscription-intent-1' })).rejects.toBeInstanceOf(PaymentSdkError);
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
it.each(['logout', 'customer', 'site', 'siteId', 'renewal', 'storage', 'mutable', 'expiry', 'abort'])('rejects late completion after %s authority changes', async kind => {
  const client = createGenericPaymentClient({ apiBaseUrl: base }); expect(client).toHaveProperty('subscriptions');
  const session = subscriptionSession(); const before = JSON.stringify(session);
  let finish!: (r: Response) => void; vi.stubGlobal('fetch', () => new Promise<Response>(r => { finish = r; }));
  const abort = new AbortController(); const pending = client.subscriptions.get({ session, subscriptionId, signal: abort.signal }).catch(e => e);
  vi.stubGlobal('fetch', async () => json({ items: [], next_cursor: null }));
  if (kind === 'logout') { vi.stubGlobal('fetch', async () => new Response(null, { status: 204 })); await client.logoutCustomerSession(session); }
  if (kind === 'customer' || kind === 'renewal') { vi.stubGlobal('fetch', async () => json({ ...customerWire(), scopes: session.scopes, site_id: session.siteId, session_token: 'R'.repeat(43), customer: { ...customerWire().customer, id: kind === 'customer' ? operationId : session.customer.id } })); await client.exchangeSiteToken(authInput); }
  if (kind === 'site') await client.subscriptions.list({ session: { ...session, siteKey: 'other-site' } });
  if (kind === 'storage') sessionStorage.setItem(sessionKey(), JSON.stringify({ version: 1, ...session, sessionToken: 'R'.repeat(43) }));
  if (kind === 'mutable') session.sessionToken = 'R'.repeat(43);
  if (kind === 'siteId') session.siteId = operationId;
  if (kind === 'expiry') session.expiresAt = '2000-01-01T00:00:00Z';
  if (kind === 'abort') abort.abort();
  finish(json(subscriptionWire())); const result = await pending;
  expect(result).toBeInstanceOf(Error); expect(result).not.toHaveProperty('subscriptionId');
  if (!['mutable', 'siteId', 'expiry'].includes(kind)) expect(JSON.stringify(session) === before).toBe(true);
});
it('rejects old persisted scopes locally and foreign bases before any financial request', async () => {
  const c = createGenericPaymentClient({ apiBaseUrl: base }).subscriptions; expect(c).toBeDefined(); const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
  for (const session of [{ ...subscriptionSession(), scopes: ['CUSTOMER_READ', 'CHECKOUT_CREATE', 'IDENTITY_LINK'] as const }, { ...subscriptionSession(), apiBaseUrl: 'https://other.test' }]) {
    await expect(c.setupMethod({ session, subscriptionId, recurringConsent: true, idempotencyKey: 'intent' })).rejects.toMatchObject({ code: 'INVALID_SESSION' });
  }
  expect(fetchMock).not.toHaveBeenCalled();
});
it('keeps setup action capabilities out of persistence and DOM events', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: base }); expect(client).toHaveProperty('subscriptions'); const session = subscriptionSession();
  const dispatch = vi.spyOn(document, 'dispatchEvent'); const save = vi.spyOn(Storage.prototype, 'setItem');
  vi.stubGlobal('fetch', async () => json({ ...mutationWire(), status: 'REQUIRES_ACTION', action_available: true, next_action: actionWire() }, 202));
  const result = await client.subscriptions.setupMethod({ session, subscriptionId, recurringConsent: true, idempotencyKey: 'setup-1' });
  expect(result.nextAction?.kind).toBe('FAKE_CONFIRM_SETUP'); expect(save).not.toHaveBeenCalled(); expect(dispatch).not.toHaveBeenCalled(); expect(document.body.textContent).toBe('');
  await client.logoutCustomerSession(session).catch(() => {});
  const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
  await expect(client.subscriptions.confirmAction({ session, subscriptionId, operationId, actionToken: result.nextAction!.secret, idempotencyKey: 'confirm-1' })).rejects.toMatchObject({ code: 'INVALID_SESSION' });
  expect(fetchMock).not.toHaveBeenCalled();
});
