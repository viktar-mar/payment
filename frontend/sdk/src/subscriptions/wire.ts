import { exact, instant, key, object, uuid } from '../contracts.js';
import { validCursor } from '../customer/contracts.js';
import { PaymentSdkError } from '../errors.js';
import type { CustomerSession } from '../customer/contracts.js';
import type { SubscriptionClientSession, SubscriptionMutationReceipt } from './contracts.js';
import { accessReasons, billingStatuses, eventTypes, operationKinds, operationStatuses, permittedActions, subscriptionStates } from './contracts.js';
import type { SubscriptionAccess, SubscriptionAction, SubscriptionBillingPage, SubscriptionBillingView, SubscriptionEvent, SubscriptionHistory, SubscriptionOffer, SubscriptionOperation, SubscriptionPage, SubscriptionPeriod, SubscriptionPortal, SubscriptionPrice, SubscriptionQuote, SubscriptionView } from './contracts.js';
function invalid(): never { throw new PaymentSdkError('INVALID_RESPONSE'); }
function fields(raw: unknown, names: string): Record<string, unknown> { if (!object(raw) || !exact(raw, names.split(' '))) invalid(); return raw; }
function id(raw: unknown): string { return uuid(raw) ? raw : invalid(); }
function time(raw: unknown): string { return instant(raw) ? raw : invalid(); }
function bool(raw: unknown): boolean { return typeof raw === 'boolean' ? raw : invalid(); }
function integer(raw: unknown, min = 0): number { return typeof raw === 'number' && Number.isSafeInteger(raw) && raw >= min ? raw : invalid(); }
function currency(raw: unknown): string { return typeof raw === 'string' && /^[A-Z]{3}$/.test(raw) ? raw : invalid(); }
function enumeration<T extends string>(raw: unknown, values: readonly T[]): T { return typeof raw === 'string' && values.includes(raw as T) ? raw as T : invalid(); }
function nullable<T>(raw: unknown, parse: (v: unknown) => T): T | null { return raw === null ? null : parse(raw); }
function array<T>(raw: unknown, parse: (v: unknown) => T, max = 100): T[] { if (!Array.isArray(raw) || raw.length > max) invalid(); return raw.map(parse); }
function enumSet<T extends string>(raw: unknown, values: readonly T[]): T[] { const result = array(raw, v => enumeration(v, values), values.length); if (new Set(result).size !== result.length) invalid(); return result; }
function ordered(start: string, end: string, equal = false): void { if (equal ? Date.parse(end) < Date.parse(start) : Date.parse(end) <= Date.parse(start)) invalid(); }
function fresh(raw: unknown): string { const result = time(raw); if (Date.parse(result) <= Date.now()) invalid(); return result; }
function price(raw: unknown): SubscriptionPrice {
  const p = fields(raw, 'price_id price_key version product_id amount_minor currency interval trial_days trial_payment_method_policy tax_mode');
  if (!key(p.price_key)) invalid();
  return { priceId: id(p.price_id), priceKey: p.price_key, version: integer(p.version, 1), productId: id(p.product_id), amountMinor: integer(p.amount_minor, 1), currency: currency(p.currency), interval: enumeration(p.interval, ['MONTH', 'YEAR']), trialDays: integer(p.trial_days), trialPaymentMethodPolicy: enumeration(p.trial_payment_method_policy, ['REQUIRED_BEFORE_TRIAL', 'OPTIONAL_DURING_TRIAL']), taxMode: enumeration(p.tax_mode, ['NONE', 'INCLUSIVE', 'EXCLUSIVE', 'STRIPE_TAX']) };
}
function period(raw: unknown): SubscriptionPeriod { const p = fields(raw, 'start end'); const result = { start: time(p.start), end: time(p.end) }; ordered(result.start, result.end); return result; }
function sameTerms(p: SubscriptionPrice, productId: string, unit: string): void { if (p.productId !== productId || p.currency !== unit) invalid(); }
function access(raw: unknown): SubscriptionAccess {
  const a = fields(raw, 'allowed reason site_id customer_id product_id subscription_id entitled_price_id entitled_price_version revision evaluated_at valid_until');
  const result: SubscriptionAccess = { allowed: bool(a.allowed), reason: enumeration(a.reason, accessReasons), siteId: id(a.site_id), customerId: id(a.customer_id), productId: id(a.product_id), subscriptionId: nullable(a.subscription_id, id), entitledPriceId: nullable(a.entitled_price_id, id), entitledPriceVersion: nullable(a.entitled_price_version, v => integer(v, 1)), revision: integer(a.revision), evaluatedAt: time(a.evaluated_at), validUntil: nullable(a.valid_until, time) };
  if (result.allowed !== ['PAID', 'TRIAL', 'GRACE'].includes(result.reason) || (result.entitledPriceId === null) !== (result.entitledPriceVersion === null) || (result.reason === 'NO_SUBSCRIPTION') !== (result.subscriptionId === null)) invalid();
  if (result.allowed) {
    if (result.entitledPriceId === null || result.validUntil === null) invalid();
    ordered(result.evaluatedAt, result.validUntil);
    if (Date.parse(result.validUntil) > Date.parse(result.evaluatedAt) + 300000) invalid();
  } else if (result.validUntil !== null) invalid();
  return result;
}
export function parseSubscription(raw: unknown): SubscriptionView {
  const s = fields(raw, 'subscription_id site_id customer_id product_id payment_account_id billing_price entitled_price state billing_status revision period paid_through trial cancellation pending_change pending_operation debt access permitted_actions next_amount created_at updated_at');
  const result: SubscriptionView = {
    subscriptionId: id(s.subscription_id), siteId: id(s.site_id), customerId: id(s.customer_id), productId: id(s.product_id), paymentAccountId: id(s.payment_account_id), billingPrice: price(s.billing_price), entitledPrice: nullable(s.entitled_price, price), state: enumeration(s.state, subscriptionStates), billingStatus: enumeration(s.billing_status, billingStatuses), revision: integer(s.revision), period: nullable(s.period, period), paidThrough: nullable(s.paid_through, time), trial: nullable(s.trial, period),
    cancellation: nullable(s.cancellation, v => { const c = fields(v, 'effective_at'); return { effectiveAt: time(c.effective_at) }; }),
    pendingChange: nullable(s.pending_change, v => { const c = fields(v, 'operation_id target_price effective_at state'); return { operationId: id(c.operation_id), targetPrice: price(c.target_price), effectiveAt: time(c.effective_at), state: enumeration(c.state, ['PAYMENT_PENDING', 'SCHEDULED', 'UNKNOWN']) }; }),
    pendingOperation: nullable(s.pending_operation, v => { const o = fields(v, 'operation_id kind status'); return { operationId: id(o.operation_id), kind: enumeration(o.kind, operationKinds), status: enumeration(o.status, operationStatuses) }; }),
    debt: nullable(s.debt, v => { const d = fields(v, 'billing_record_id period grace_deadline amount_due_minor currency'); return { billingRecordId: id(d.billing_record_id), period: period(d.period), graceDeadline: nullable(d.grace_deadline, time), amountDueMinor: integer(d.amount_due_minor), currency: currency(d.currency) }; }),
    access: access(s.access), permittedActions: enumSet(s.permitted_actions, permittedActions),
    nextAmount: nullable(s.next_amount, v => { const n = fields(v, 'amount_minor currency tax_final'); return { amountMinor: integer(n.amount_minor), currency: currency(n.currency), taxFinal: bool(n.tax_final) }; }),
    createdAt: time(s.created_at), updatedAt: time(s.updated_at),
  };
  ordered(result.createdAt, result.updatedAt, true);
  sameTerms(result.billingPrice, result.productId, result.billingPrice.currency);
  if (result.entitledPrice) sameTerms(result.entitledPrice, result.productId, result.billingPrice.currency);
  if (result.pendingChange) sameTerms(result.pendingChange.targetPrice, result.productId, result.billingPrice.currency);
  if ((result.debt && result.debt.currency !== result.billingPrice.currency) || (result.nextAmount && result.nextAmount.currency !== result.billingPrice.currency)) invalid();
  const a = result.access;
  if (a.siteId !== result.siteId || a.customerId !== result.customerId || a.productId !== result.productId || a.subscriptionId !== result.subscriptionId || a.revision !== result.revision || a.entitledPriceId !== (result.entitledPrice?.priceId ?? null) || a.entitledPriceVersion !== (result.entitledPrice?.version ?? null)) invalid();
  if (a.allowed) {
    const boundary = a.reason === 'PAID' ? result.paidThrough : a.reason === 'TRIAL' ? result.trial?.end : result.debt?.graceDeadline;
    if (!boundary || Date.parse(a.validUntil!) > Date.parse(boundary) || (result.cancellation && Date.parse(a.validUntil!) > Date.parse(result.cancellation.effectiveAt))) invalid();
    if (a.reason === 'TRIAL' && result.trial && Date.parse(a.evaluatedAt) < Date.parse(result.trial.start)) invalid();
  }
  return result;
}
export function parseSubscriptionAction(raw: unknown): SubscriptionAction {
  const a = fields(raw, 'kind secret expires_at');
  const kind = enumeration(a.kind, ['FAKE_CONFIRM_PAYMENT', 'FAKE_CONFIRM_SETUP', 'PROVIDER_CLIENT'] as const);
  if (!validSubscriptionActionToken(a.secret)) invalid();
  return { kind, secret: a.secret, expiresAt: fresh(a.expires_at) };
}
export function validSubscriptionActionToken(raw: unknown): raw is string { return typeof raw === 'string' && raw.length > 0 && raw.length <= 2048 && /^[A-Za-z0-9_.:/-]+$/.test(raw); }
function operation(raw: unknown, mutation: boolean): SubscriptionOperation {
  const permission = mutation && object(raw) && Object.hasOwn(raw, 'browser_confirm_allowed');
  const o = fields(raw, 'operation_id subscription_id status action_available' + (mutation ? ' next_action' : '') + (permission ? ' browser_confirm_allowed' : ''));
  const result: SubscriptionOperation = { operationId: id(o.operation_id), subscriptionId: id(o.subscription_id), status: enumeration(o.status, operationStatuses), actionAvailable: bool(o.action_available) };
  if (result.actionAvailable !== (result.status === 'REQUIRES_ACTION')) invalid();
  if (mutation && o.next_action !== null) { if (!result.actionAvailable) invalid(); result.nextAction = parseSubscriptionAction(o.next_action); }
  if (mutation) Object.defineProperty(result, 'browserConfirmAllowed', { value: permission ? bool(o.browser_confirm_allowed) : false, enumerable: false });
  return result;
}
export function parseSubscriptionOperation(raw: unknown): SubscriptionOperation { return operation(raw, false); }
export function parseSubscriptionMutation(raw: unknown): SubscriptionMutationReceipt { return operation(raw, true) as SubscriptionMutationReceipt; }
/** Flat six-string transport grammar also rejects duplicate/escaped duplicate keys. */
export function parseSubscriptionClientSessionJson(raw: string): unknown {
  const string = '"(?:[^"\\\\\\u0000-\\u001f]|\\\\(?:["\\\\/bfnrt]|u[0-9a-fA-F]{4}))*"';
  const pair = `${string}\\s*:\\s*${string}`;
  if (!new RegExp(`^\\s*\\{\\s*${pair}(?:\\s*,\\s*${pair})*\\s*\\}\\s*$`).test(raw) || [...raw.matchAll(new RegExp(pair, 'g'))].length !== 6) invalid();
  return JSON.parse(raw);
}
export function parseSubscriptionClientSession(raw: unknown, action: SubscriptionAction, session: CustomerSession): SubscriptionClientSession {
  const s = fields(raw, 'provider flow publishable_key client_secret action_token expires_at');
  if (s.provider !== 'stripe' || action.kind !== 'PROVIDER_CLIENT' || s.action_token !== action.secret || !validSubscriptionActionToken(s.action_token)
    || typeof s.publishable_key !== 'string' || s.publishable_key.length > 255 || !/^pk_(?:test|live)_[A-Za-z0-9_]+$/.test(s.publishable_key)) invalid();
  const flow = enumeration(s.flow, ['PAYMENT', 'SETUP'] as const);
  if (typeof s.client_secret !== 'string' || s.client_secret.length > 512 || !(flow === 'PAYMENT' ? /^pi_[A-Za-z0-9_]+_secret_[A-Za-z0-9_]+$/ : /^seti_[A-Za-z0-9_]+_secret_[A-Za-z0-9_]+$/).test(s.client_secret)) invalid();
  const expiresAt = fresh(s.expires_at);
  if (!instant(action.expiresAt) || !instant(session.expiresAt) || Date.parse(expiresAt) > Math.min(Date.parse(action.expiresAt), Date.parse(session.expiresAt))) invalid();
  const result = {} as SubscriptionClientSession;
  Object.defineProperties(result, {
    flow: { value: flow }, publishableKey: { value: s.publishable_key }, clientSecret: { value: s.client_secret }, actionToken: { value: s.action_token }, expiresAt: { value: expiresAt },
    toJSON: { value: () => '[SubscriptionClientSession redacted]' }, toString: { value: () => '[SubscriptionClientSession redacted]' },
  });
  return Object.freeze(result);
}
export function parseSubscriptionQuote(raw: unknown): SubscriptionQuote {
  const q = fields(raw, 'quote_id subscription_id from_price_id from_price_version target_price timing due_now_minor next_amount_minor currency effective_at expires_at tax_final subscription_revision policy_version');
  const result: SubscriptionQuote = { quoteId: id(q.quote_id), subscriptionId: id(q.subscription_id), fromPriceId: id(q.from_price_id), fromPriceVersion: integer(q.from_price_version, 1), targetPrice: price(q.target_price), timing: enumeration(q.timing, ['IMMEDIATE', 'NEXT_PERIOD']), dueNowMinor: integer(q.due_now_minor, Number.MIN_SAFE_INTEGER), nextAmountMinor: integer(q.next_amount_minor, 1), currency: currency(q.currency), effectiveAt: time(q.effective_at), expiresAt: fresh(q.expires_at), taxFinal: bool(q.tax_final), subscriptionRevision: integer(q.subscription_revision), policyVersion: integer(q.policy_version) };
  if (result.targetPrice.currency !== result.currency) invalid();
  // A scheduled effectiveAt may legitimately be later than quote expiry.
  return result;
}
export function validSubscriptionReturnUrl(raw: unknown): raw is string {
  if (!validPortalUrl(raw)) return false;
  const query = new URL(raw).searchParams;
  return !query.has('subscriptionPortal') && !query.has('operationId');
}
function validPortalUrl(raw: unknown): raw is string {
  try {
    if (typeof raw !== 'string' || raw.length > 2048 || /[\s\\]/.test(raw) || /[\u0000-\u001f\u007f]/.test(decodeURIComponent(raw))) return false;
    const url = new URL(raw);
    if (!url.hostname || url.username || url.password || raw.includes('#') || url.port === '0') return false;
    if (url.protocol === 'https:') return true;
    // Parsing a local URL grants no server permission: the backend still enforces
    // its explicit local profile and exact persisted allowlist. Match the raw
    // authority so URL normalization cannot turn alternate numeric hosts local.
    return url.protocol === 'http:' && /^http:\/\/(?:localhost|127\.0\.0\.1|\[::1\])(?::[1-9]\d*)?(?:[/?]|$)/i.test(raw);
  } catch { return false; }
}
export function parseSubscriptionPortal(raw: unknown): SubscriptionPortal {
  const p = fields(raw, 'navigation_url expires_at simulated');
  if (!validPortalUrl(p.navigation_url) || p.simulated !== true) invalid();
  const url = new URL(p.navigation_url);
  if (url.searchParams.getAll('subscriptionPortal').length !== 1 || url.searchParams.get('subscriptionPortal') !== 'simulated' || url.searchParams.getAll('operationId').length !== 1 || !uuid(url.searchParams.get('operationId'))) invalid();
  return { navigationUrl: p.navigation_url, expiresAt: fresh(p.expires_at), simulated: true };
}
function billing(raw: unknown): SubscriptionBillingView {
  const b = fields(raw, 'billing_record_id subscription_id kind price previous_price period amount_due_minor currency settlement payment_ids created_at updated_at');
  const result: SubscriptionBillingView = { billingRecordId: id(b.billing_record_id), subscriptionId: id(b.subscription_id), kind: enumeration(b.kind, ['INITIAL', 'RENEWAL', 'PRORATION']), price: price(b.price), previousPrice: nullable(b.previous_price, price), period: period(b.period), amountDueMinor: integer(b.amount_due_minor), currency: currency(b.currency), settlement: enumeration(b.settlement, ['OPEN', 'CONFIRMED_CASH', 'ZERO_AMOUNT', 'UNSUPPORTED', 'VOID']), paymentIds: array(b.payment_ids, id, 1000), createdAt: time(b.created_at), updatedAt: time(b.updated_at) };
  ordered(result.createdAt, result.updatedAt, true); if (result.currency !== result.price.currency || new Set(result.paymentIds).size !== result.paymentIds.length) invalid();
  if (result.previousPrice) sameTerms(result.previousPrice, result.price.productId, result.currency);
  if ((result.settlement === 'ZERO_AMOUNT' && (result.amountDueMinor !== 0 || result.paymentIds.length !== 0)) || (result.settlement === 'CONFIRMED_CASH' && result.amountDueMinor === 0)) invalid();
  return result;
}
function event(raw: unknown): SubscriptionEvent {
  const e = fields(raw, 'event_id subscription_id sequence revision type source occurred_at received_at operation_id billing_record_id payment_id');
  return { eventId: id(e.event_id), subscriptionId: id(e.subscription_id), sequence: integer(e.sequence), revision: integer(e.revision), type: enumeration(e.type, eventTypes), source: enumeration(e.source, ['API', 'PROVIDER_WEBHOOK', 'RECONCILIATION', 'DEADLINE']), occurredAt: time(e.occurred_at), receivedAt: time(e.received_at), operationId: nullable(e.operation_id, id), billingRecordId: nullable(e.billing_record_id, id), paymentId: nullable(e.payment_id, id) };
}
function page<T>(raw: unknown, parse: (v: unknown) => T, limit: number): { items: T[]; nextCursor: string | null } {
  const p = fields(raw, 'items next_cursor'); if (p.next_cursor !== null && !validCursor(p.next_cursor)) invalid();
  return { items: array(p.items, parse, limit), nextCursor: p.next_cursor as string | null };
}
export function parseSubscriptionPage(raw: unknown, limit = 100): SubscriptionPage { return page(raw, parseSubscription, limit); }
export function parseSubscriptionBillingPage(raw: unknown, limit = 100): SubscriptionBillingPage { return page(raw, billing, limit); }
export function parseSubscriptionHistory(raw: unknown, limit = 100): SubscriptionHistory { return page(raw, event, limit); }
export function parseSubscriptionOffers(raw: unknown): SubscriptionOffer[] {
  const o = fields(raw, 'items'); return array(o.items, v => { const a = fields(v, 'price direction allowed_timings trial_eligible'); return { price: price(a.price), direction: nullable(a.direction, v => enumeration(v, ['UPGRADE', 'DOWNGRADE', 'LATERAL'])), allowedTimings: enumSet(a.allowed_timings, ['IMMEDIATE', 'NEXT_PERIOD']), trialEligible: bool(a.trial_eligible) }; }, 1000);
}
