import { expect, it } from 'vitest';
import { parseHistory, validScopes } from '../customer/contracts.js';
import { createGenericPaymentClient } from '../client.js';
import { actionWire, billingWire, mutationWire, offersWire, operationWire, portalWire, quoteWire, subscriptionWire, operationId } from '../test/subscription-fixtures.js';
async function parsers() { expect(createGenericPaymentClient({ apiBaseUrl: 'https://api.test' })).toHaveProperty('subscriptions'); return import('./wire.js'); }
it('accepts the new scopes explicitly without accepting unknown authority', () => {
  expect(validScopes(['CUSTOMER_READ', 'SUBSCRIPTION_READ', 'SUBSCRIPTION_MANAGE'])).toBe(true);
  expect(validScopes(['CUSTOMER_READ', 'SUBSCRIPTION_ADMIN'])).toBe(false);
  expect(validScopes(['SUBSCRIPTION_READ', 'SUBSCRIPTION_READ'])).toBe(false);
});
it('accepts initial offers without a transition direction while rejecting unknown directions', async () => {
  const p = await parsers();
  const initial = { ...offersWire().items[0], direction: null, allowed_timings: [] };
  expect(p.parseSubscriptionOffers({ items: [initial] })[0]).toMatchObject({ direction: null, allowedTimings: [] });
  expect(() => p.parseSubscriptionOffers({ items: [{ ...initial, direction: 'UPSELL' }] })).toThrow();
});
it('parses every exact Task13 safe DTO into camelCase without provider objects', async () => {
  const p = await parsers();
  expect(p.parseSubscription(subscriptionWire())).toMatchObject({ state: 'ACTIVE', billingStatus: 'ACTIVE', billingPrice: { amountMinor: 1000 }, access: { entitledPriceVersion: 1 } });
  expect(p.parseSubscriptionQuote(quoteWire())).toMatchObject({ dueNowMinor: 0, fromPriceVersion: 1, policyVersion: 1 });
  expect(p.parseSubscriptionOperation(operationWire())).toMatchObject({ actionAvailable: false });
  expect(p.parseSubscriptionMutation(mutationWire())).not.toHaveProperty('nextAction');
  expect(p.parseSubscriptionAction(actionWire()).kind).toBe('FAKE_CONFIRM_SETUP');
  // Fake producer uses two UUIDs, not the 43-character Customer session format.
  expect(p.parseSubscriptionAction({ ...actionWire(), secret: operationId + operationId }).secret).toHaveLength(72);
  expect(p.parseSubscriptionPortal(portalWire()).simulated).toBe(true);
  expect(p.parseSubscriptionOffers(offersWire())[0].allowedTimings).toEqual(['IMMEDIATE', 'NEXT_PERIOD']);
  expect(p.parseSubscriptionBillingPage({ items: [billingWire()], next_cursor: null }).items[0].paymentIds).toHaveLength(1);
});
it.each(['extra', 'uuid', 'amount0', 'amountNegative', 'amountUnsafe', 'versionFraction', 'revisionFraction', 'interval', 'tax', 'state', 'billingStatus', 'actions', 'duplicateActions', 'period', 'updated', 'site', 'customer', 'subscription', 'product', 'accessPrice', 'accessVersion', 'accessRevision', 'accessMissingVersion', 'accessMissingTime', 'accessAfter5min', 'accessAfterBoundary', 'accessBeforeEvaluation', 'deniedWithTime', 'badReason', 'currency', 'pending', 'debt'])('rejects malformed subscription %s', async kind => {
  const p = await parsers(); const raw: Record<string, unknown> = subscriptionWire();
  const price = { ...subscriptionWire().billing_price }; const access: Record<string, unknown> = { ...subscriptionWire().access };
  if (kind === 'extra') raw.provider_payload = {};
  if (kind === 'uuid') raw.subscription_id = 'x';
  if (kind === 'amount0') price.amount_minor = 0;
  if (kind === 'amountNegative') price.amount_minor = -1;
  if (kind === 'amountUnsafe') price.amount_minor = Number.MAX_SAFE_INTEGER + 1;
  if (kind === 'versionFraction') price.version = 1.5;
  if (kind === 'revisionFraction') raw.revision = 1.5;
  if (kind === 'interval') price.interval = 'WEEK';
  if (kind === 'tax') price.tax_mode = 'ALIEN';
  if (kind === 'state') raw.state = 'UNKNOWN';
  if (kind === 'billingStatus') raw.billing_status = 'CANCEL_AT_PERIOD_END';
  if (kind === 'actions') raw.permitted_actions = ['ADMIN'];
  if (kind === 'duplicateActions') raw.permitted_actions = ['RESUME', 'RESUME'];
  if (kind === 'period') raw.period = { start: '2026-02-01T00:00:00Z', end: '2026-01-01T00:00:00Z' };
  if (kind === 'updated') raw.updated_at = '2025-01-01T00:00:00Z';
  if (kind === 'site') access.site_id = operationId;
  if (kind === 'customer') access.customer_id = operationId;
  if (kind === 'subscription') access.subscription_id = operationId;
  if (kind === 'product') price.product_id = operationId;
  if (kind === 'accessPrice') access.entitled_price_id = operationId;
  if (kind === 'accessVersion') access.entitled_price_version = 2;
  if (kind === 'accessRevision') access.revision = 2;
  if (kind === 'accessMissingVersion') access.entitled_price_version = null;
  if (kind === 'accessMissingTime') access.valid_until = null;
  if (kind === 'accessAfter5min') access.valid_until = '2026-01-10T00:05:01Z';
  if (kind === 'accessAfterBoundary') { raw.paid_through = '2026-01-10T00:01:00Z'; }
  if (kind === 'accessBeforeEvaluation') access.valid_until = access.evaluated_at;
  if (kind === 'deniedWithTime') { access.allowed = false; access.reason = 'UNPAID'; }
  if (kind === 'badReason') access.reason = 'ALLOWED';
  if (kind === 'currency') raw.next_amount = { amount_minor: 100, currency: 'USD', tax_final: true };
  if (kind === 'pending') raw.pending_operation = { operation_id: operationId, kind: 'ALIEN', status: 'PROCESSING' };
  if (kind === 'debt') raw.debt = { billing_record_id: operationId, period: raw.period, amount_due_minor: 100, currency: 'EUR' };
  raw.billing_price = price; raw.access = access;
  expect(() => p.parseSubscription(raw)).toThrow();
});
it('accepts displayed cancellation and nullable unpaid grace deadline without widening enums', async () => {
  const p = await parsers();
  expect(p.parseSubscription({ ...subscriptionWire(), state: 'CANCEL_AT_PERIOD_END', cancellation: { effective_at: '2026-02-01T00:00:00Z' } }).state).toBe('CANCEL_AT_PERIOD_END');
  const raw = subscriptionWire(); expect(p.parseSubscription({ ...raw, billing_status: 'PAST_DUE', state: 'PAST_DUE', entitled_price: null, paid_through: null, access: { ...raw.access, allowed: false, reason: 'INITIAL_PAYMENT_REQUIRED', entitled_price_id: null, entitled_price_version: null, valid_until: null }, debt: { billing_record_id: operationId, period: raw.period, grace_deadline: null, amount_due_minor: 1000, currency: 'EUR' } }).debt?.graceDeadline).toBeNull();
});
it.each(['http://localhost:4173/manage', 'http://127.0.0.1:4173/manage', 'http://[::1]:4173/manage'])('accepts a fresh single-marker loopback Portal response %s', async origin => {
  const p = await parsers(); const navigation_url = `${origin}?subscriptionPortal=simulated&operationId=${operationId}`;
  expect(p.parseSubscriptionPortal({ ...portalWire(), navigation_url }).navigationUrl).toBe(navigation_url);
  for (const suffix of ['&subscriptionPortal=simulated', `&operationId=${operationId}`]) expect(() => p.parseSubscriptionPortal({ ...portalWire(), navigation_url: navigation_url+suffix })).toThrow();
  expect(() => p.parseSubscriptionPortal({ ...portalWire(), navigation_url, expires_at: '2000-01-01T00:00:00Z' })).toThrow();
});
it('rejects mutation secrets in polling, expired actions and unapproved navigation shapes', async () => {
  const p = await parsers();
  expect(() => p.parseSubscriptionOperation(mutationWire())).toThrow();
  expect(() => p.parseSubscriptionMutation({ ...mutationWire(), next_action: actionWire() })).toThrow();
  expect(() => p.parseSubscriptionAction({ ...actionWire(), expires_at: '2000-01-01T00:00:00Z' })).toThrow();
  for (const navigation_url of ['javascript:alert(1)', 'https://user:pass@shop.test/', 'https://shop.test/#secret', 'https://shop.test/?subscriptionPortal=simulated', 'https://shop.test/?subscriptionPortal=simulated&operationId=bad']) expect(() => p.parseSubscriptionPortal({ ...portalWire(), navigation_url })).toThrow();
  expect(() => p.parseSubscriptionPortal({ ...portalWire(), simulated: false })).toThrow();
});
it('rejects invalid quote, billing and offer nested fields and page cursors', async () => {
  const p = await parsers();
  for (const raw of [{ ...quoteWire(), from_price_version: 1.5 }, { ...quoteWire(), due_now_minor: Number.MAX_SAFE_INTEGER + 1 }, { ...quoteWire(), provider_quote: 'private' }, { ...quoteWire(), currency: 'USD' }, { ...quoteWire(), timing: 'LATER' }]) expect(() => p.parseSubscriptionQuote(raw)).toThrow();
  for (const item of [{ ...billingWire(), payment_ids: ['bad'] }, { ...billingWire(), settlement: 'ZERO_AMOUNT' }, { ...billingWire(), previous_price: { ...billingWire().price, product_id: operationId } }]) expect(() => p.parseSubscriptionBillingPage({ items: [item], next_cursor: null })).toThrow();
  expect(() => p.parseSubscriptionBillingPage({ items: [], next_cursor: ' ' })).toThrow();
  expect(() => p.parseSubscriptionOffers({ items: [{ ...offersWire().items[0], allowed_timings: ['NOW'] }] })).toThrow();
});
it('accepts SUBSCRIPTION cash history only with a null catalog snapshot', () => {
  const item = { payment_id: '11111111-1111-4111-8111-111111111111', source: 'SUBSCRIPTION', amount_minor: 100, currency: 'EUR', status: 'SUCCEEDED', created_at: '2026-01-01T00:00:00Z', updated_at: '2026-01-01T00:00:00Z', catalog: null };
  expect(parseHistory({ items: [item], next_cursor: null }, 20).items[0].source).toBe('SUBSCRIPTION');
  expect(() => parseHistory({ items: [{ ...item, catalog: { price_id: item.payment_id, price_key: 'monthly', version: 1 } }], next_cursor: null }, 20)).toThrow();
});
