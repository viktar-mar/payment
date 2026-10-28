import { expect, it, vi } from 'vitest';
import * as wire from './wire.js';
import { createGenericPaymentClient } from '../client.js';
import { base, json } from '../test/customer-fixtures.js';
import { actionWire, mutationWire, operationId, subscriptionId, subscriptionSession } from '../test/subscription-fixtures.js';

const token = 'G'.repeat(43);
const grant = () => ({ kind: 'PROVIDER_CLIENT' as const, secret: token, expiresAt: new Date(Date.now() + 120000).toISOString() });
const sessionWire = (flow = 'PAYMENT') => ({ provider: 'stripe', flow, publishable_key: 'pk_test_fixture', client_secret: flow === 'PAYMENT' ? 'pi_fixture_secret_fixture' : 'seti_fixture_secret_fixture', action_token: token, expires_at: new Date(Date.now() + 60000).toISOString() });

it('accepts explicit false and legacy missing receipt permission without authorizing confirmation', () => {
  for (const raw of [mutationWire(), { ...mutationWire(), browser_confirm_allowed: false }]) {
    expect(wire.parseSubscriptionMutation(raw)).toMatchObject({ browserConfirmAllowed: false });
  }
});
it('keeps permission only on mutation receipts and rejects malformed permission and unknown fields', () => {
  expect(wire.parseSubscriptionMutation({ ...mutationWire(), browser_confirm_allowed: true })).toMatchObject({ browserConfirmAllowed: true });
  for (const value of [null, 1, 'true', {}, []]) expect(() => wire.parseSubscriptionMutation({ ...mutationWire(), browser_confirm_allowed: value })).toThrow();
  expect(() => wire.parseSubscriptionMutation({ ...mutationWire(), browser_confirm_allowed: true, extra: false })).toThrow();
  expect(() => wire.parseSubscriptionOperation({ operation_id: operationId, subscription_id: subscriptionId, status: 'PROCESSING', action_available: false, browser_confirm_allowed: true })).toThrow();
});
it.each(['PAYMENT', 'SETUP'])('parses an exact bound memory-only %s client session', flow => {
  expect(wire).toHaveProperty('parseSubscriptionClientSession', expect.any(Function));
  const raw = sessionWire(flow), action = grant(), customer = subscriptionSession();
  const parsed = wire.parseSubscriptionClientSession(raw, action, customer);
  expect(parsed.flow).toBe(flow);
  expect(parsed.clientSecret === raw.client_secret).toBe(true);
  for (const serialized of [JSON.stringify(parsed), JSON.stringify({ ...parsed }), String(parsed)]) {
    expect([raw.client_secret, raw.publishable_key, token].some(value => serialized.includes(value))).toBe(false);
  }
  expect(Object.isFrozen(parsed)).toBe(true);
});
it('rejects session extras, wrong flow/secret pair, token, expiry, provider, key and non-string fields', () => {
  expect(wire).toHaveProperty('parseSubscriptionClientSession', expect.any(Function));
  const raw = sessionWire(), action = grant(), customer = subscriptionSession();
  const invalid = [{ ...raw, extra: false }, { ...raw, provider: 'STRIPE' }, { ...raw, flow: 'UNKNOWN' }, { ...raw, client_secret: 'seti_fixture_secret_fixture' }, { ...raw, action_token: 'other' }, { ...raw, expires_at: new Date(Date.now() + 180000).toISOString() }, { ...raw, expires_at: '2000-01-01T00:00:00Z' }, { ...raw, publishable_key: 'sk_test_private' }, { ...raw, client_secret: null }];
  for (const value of invalid) expect(() => wire.parseSubscriptionClientSession(value, action, customer)).toThrow();
  expect(() => wire.parseSubscriptionClientSession(raw, action, { ...customer, expiresAt: new Date(Date.now() + 1000).toISOString() })).toThrow();
});
it('issues client session only for its bound customer action with exact authenticated no-store transport', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: base }).subscriptions, customer = subscriptionSession();
  expect(client).toHaveProperty('clientSession', expect.any(Function));
  const calls: Request[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { calls.push(new Request(url, init)); return json(calls.length === 1 ? { ...actionWire(), kind: 'PROVIDER_CLIENT', secret: token } : sessionWire()); });
  const action = await client.action({ session: customer, subscriptionId, operationId });
  const result = await client.clientSession({ session: customer, subscriptionId, operationId, action, idempotencyKey: 'session-issue' });
  expect(result.clientSecret === sessionWire().client_secret).toBe(true);
  const request = calls[1]!;
  expect(request.url).toBe(`${base}/api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}/client-session`);
  expect(request.method).toBe('POST'); expect(request.cache).toBe('no-store'); expect(request.credentials).toBe('omit'); expect(request.redirect).toBe('error');
  expect(request.headers.get('authorization') === `Bearer ${customer.sessionToken}`).toBe(true);
  expect(request.headers.get('idempotency-key')).toBe('session-issue');
  expect(await request.json()).toEqual({ action_token: token });
  await expect(client.clientSession({ session: customer, subscriptionId, operationId, action: grant(), idempotencyKey: 'different' })).rejects.toMatchObject({ code: 'INVALID_SESSION' });
  expect(calls).toHaveLength(2);
});
it('rejects duplicate or escaped duplicate client-session fields before JSON can discard them',async()=>{
  const client=createGenericPaymentClient({apiBaseUrl:base}).subscriptions,customer=subscriptionSession();let raw='';
  vi.stubGlobal('fetch',async(url:string)=>url.endsWith('/action')?json({...actionWire(),kind:'PROVIDER_CLIENT',secret:token}):new Response(raw,{status:200,headers:{'Content-Type':'application/json'}}));
  const action=await client.action({session:customer,subscriptionId,operationId});
  for(const name of ['client_secret','client_\\u0073ecret']){
    raw=JSON.stringify(sessionWire()).replace(/}$/,`,"${name}":"pi_fixture_secret_other"}`);
    await expect(client.clientSession({session:customer,subscriptionId,operationId,action,idempotencyKey:'issue-duplicate'})).rejects.toMatchObject({code:'INVALID_RESPONSE'});
  }
});
