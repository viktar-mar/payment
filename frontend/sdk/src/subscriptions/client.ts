import { bindSelectedSetup, bindPaymentSession, consumePaymentSession, parseAttempt, parseConfirmationJson, parseConfirmationSession } from './confirmation-v2.js';
import type { SubscriptionConfirmReceipt, SubscriptionPaymentSession } from './contracts.js';
import { object, uuid } from '../contracts.js';
import { validCursor } from '../customer/contracts.js';
import { PaymentSdkError } from '../errors.js';
import type { CustomerSession } from '../customer/contracts.js';
import type { SubscriptionAuthorizedRequest, SubscriptionClient, SubscriptionIdRequest, SubscriptionMutation, SubscriptionMutationReceipt, SubscriptionOperation, SubscriptionRequest, SubscriptionView } from './contracts.js';
import { parseSubscription, parseSubscriptionAction, parseSubscriptionBillingPage, parseSubscriptionHistory, parseSubscriptionMutation, parseSubscriptionOffers, parseSubscriptionOperation, parseSubscriptionPage, parseSubscriptionPortal, parseSubscriptionQuote, validSubscriptionActionToken, validSubscriptionReturnUrl } from './wire.js';
import { bindSubscriptionAction, assertSubscriptionAction } from './action-driver.js';
import { parseSubscriptionClientSession, parseSubscriptionClientSessionJson } from './wire.js';
import { registerSubscriptionAuthority, type SubscriptionAuthorityCapture } from './authority.js';
const root = '/api/v1/customer/subscriptions';
function invalid(): never { throw new PaymentSdkError('INVALID_CONFIGURATION'); }
function input(raw: unknown, required: string[], optional: string[] = []): asserts raw is SubscriptionRequest {
  if (!object(raw) || !required.every(k => Object.hasOwn(raw, k)) || Object.keys(raw).some(k => !['session', 'signal', ...required, ...optional].includes(k)) || (raw.signal !== undefined && !(raw.signal instanceof AbortSignal))) invalid();
  if (!object(raw.session)) throw new PaymentSdkError('INVALID_SESSION');
  for (const name of ['subscriptionId', 'productId', 'priceId', 'targetPriceId', 'quoteId', 'operationId', 'billingRecordId', 'attemptId']) if (name in raw && !uuid(raw[name])) invalid();
  if ('idempotencyKey' in raw && (typeof raw.idempotencyKey !== 'string' || !/^[A-Za-z0-9_.:/-]{1,255}$/.test(raw.idempotencyKey))) invalid();
  if ('recurringConsent' in raw && typeof raw.recurringConsent !== 'boolean') invalid();
}
function ownership(view: SubscriptionView, session: CustomerSession, expectedId?: string): SubscriptionView { if (view.siteId !== session.siteId || view.customerId !== session.customer.id || (expectedId && view.subscriptionId !== expectedId)) throw new PaymentSdkError('INVALID_RESPONSE'); return view; }
function operationOwnership<T extends SubscriptionOperation>(result: T, input: SubscriptionRequest & { subscriptionId?: string; operationId?: string }): T { if ((input.subscriptionId && result.subscriptionId !== input.subscriptionId) || (input.operationId && result.operationId !== input.operationId)) throw new PaymentSdkError('INVALID_RESPONSE'); return result; }
function query(i: { limit?: number; cursor?: string }): { limit: number; search: string } {
  const limit = i.limit ?? 20; if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100 || (i.cursor !== undefined && !validCursor(i.cursor))) invalid();
  const search = new URLSearchParams({ limit: String(limit) }); if (i.cursor !== undefined) search.set('cursor', i.cursor); return { limit, search: search.toString() };
}
export function createSubscriptionClient(request: SubscriptionAuthorizedRequest, captureAuthority?: SubscriptionAuthorityCapture): SubscriptionClient {
  const read = (i: SubscriptionRequest, path: string, cap = 32768, manage = false) => request(i.session, path, 'GET', undefined, i.signal, [200], undefined, manage ? 'SUBSCRIPTION_MANAGE' : 'SUBSCRIPTION_READ', undefined, cap);
  const post = (i: SubscriptionRequest & { idempotencyKey?: string }, path: string, body: object, status = 202, parseSuccess?: (raw: string) => unknown) => request(i.session, path, 'POST', body, i.signal, [status], i.idempotencyKey, 'SUBSCRIPTION_MANAGE', undefined, 32768, parseSuccess);
  const path = (i: SubscriptionIdRequest, suffix = '') => `${root}/${i.subscriptionId}${suffix}`;
  function bound<T extends SubscriptionOperation>(result: T, i: SubscriptionRequest): T { if (result.nextAction) bindSubscriptionAction(client, i.session, result.operationId, result.subscriptionId, result.nextAction); return result; }
  async function mutate(i: SubscriptionMutation, suffix: string, body: object): Promise<SubscriptionMutationReceipt> { return bound(operationOwnership(parseSubscriptionMutation(await post(i, path(i, suffix), body)), i), i); }
  const client: SubscriptionClient = {
    list: async i => { input(i, ['session'], ['limit', 'cursor']); const q = query(i); const page = parseSubscriptionPage(await read(i, `${root}?${q.search}`, 131072), q.limit); for (const v of page.items) ownership(v, i.session); if (new Set(page.items.map(v => v.siteId)).size > 1) throw new PaymentSdkError('INVALID_RESPONSE'); return page; },
    offers: async i => { input(i, ['session', 'productId']); const offers = parseSubscriptionOffers(await read(i, `/api/v1/customer/subscription-products/${i.productId}/offers`, 131072)); if (offers.some(o => o.price.productId !== i.productId)) throw new PaymentSdkError('INVALID_RESPONSE'); return offers; },
    get: async i => { input(i, ['session', 'subscriptionId']); return ownership(parseSubscription(await read(i, path(i))), i.session, i.subscriptionId); },
    history: async i => { input(i, ['session', 'subscriptionId'], ['limit', 'cursor']); const q = query(i); const page = parseSubscriptionHistory(await read(i, path(i, `/events?${q.search}`), 131072), q.limit); if (page.items.some(e => e.subscriptionId !== i.subscriptionId)) throw new PaymentSdkError('INVALID_RESPONSE'); return page; },
    billingRecords: async i => { input(i, ['session', 'subscriptionId'], ['limit', 'cursor']); const q = query(i); const page = parseSubscriptionBillingPage(await read(i, path(i, `/billing-records?${q.search}`), 131072), q.limit); if (page.items.some(b => b.subscriptionId !== i.subscriptionId)) throw new PaymentSdkError('INVALID_RESPONSE'); return page; },
    create: async i => { input(i, ['session', 'priceId', 'recurringConsent', 'idempotencyKey']); return bound(parseSubscriptionMutation(await post(i, root, { price_id: i.priceId, recurring_consent: i.recurringConsent })), i); },
    preview: async i => { input(i, ['session', 'subscriptionId', 'targetPriceId', 'timing']); if (!['IMMEDIATE', 'NEXT_PERIOD'].includes(i.timing)) invalid(); const quote = parseSubscriptionQuote(await post(i, path(i, '/change-preview'), { target_price_id: i.targetPriceId, timing: i.timing }, 200)); if (quote.subscriptionId !== i.subscriptionId || quote.targetPrice.priceId !== i.targetPriceId || quote.timing !== i.timing) throw new PaymentSdkError('INVALID_RESPONSE'); return quote; },
    change: async i => { input(i, ['session', 'subscriptionId', 'idempotencyKey', 'quoteId']); return mutate(i, '/changes', { quote_id: i.quoteId }); },
    clearChange: async i => { input(i, ['session', 'subscriptionId', 'idempotencyKey']); return mutate(i, '/changes/clear', {}); },
    cancel: async i => { input(i, ['session', 'subscriptionId', 'idempotencyKey', 'mode'], ['confirmation']); if (!['PERIOD_END', 'IMMEDIATE'].includes(i.mode) || (i.mode === 'IMMEDIATE' && i.confirmation !== i.subscriptionId) || (i.confirmation !== undefined && !uuid(i.confirmation))) invalid(); return mutate(i, '/cancellation', { mode: i.mode, ...(i.confirmation === undefined ? {} : { confirmation: i.confirmation }) }); },
    withdrawCancellation: async i => { input(i, ['session', 'subscriptionId', 'idempotencyKey']); return mutate(i, '/cancellation/withdraw', {}); },
    setupMethod: async i => { input(i, ['session', 'subscriptionId', 'idempotencyKey', 'recurringConsent']); return mutate(i, '/method-setups', { recurring_consent: i.recurringConsent }); },
    recover: async i => { input(i, ['session', 'subscriptionId', 'idempotencyKey', 'billingRecordId']); return mutate(i, '/recoveries', { billing_record_id: i.billingRecordId }); },
    resume: async i => { input(i, ['session', 'subscriptionId', 'idempotencyKey', 'recurringConsent']); return mutate(i, '/resumptions', { recurring_consent: i.recurringConsent }); },
    operation: async i => { input(i, ['session', 'subscriptionId', 'operationId']); return operationOwnership(parseSubscriptionOperation(await read(i, path(i, `/operations/${i.operationId}`))), i); },
    action: async i => { input(i, ['session', 'subscriptionId', 'operationId']); return bindSubscriptionAction(client, i.session, i.operationId, i.subscriptionId, parseSubscriptionAction(await read(i, path(i, `/operations/${i.operationId}/action`), 32768, true))); },
    clientSession: async i => { input(i, ['session', 'subscriptionId', 'operationId', 'idempotencyKey', 'action']); assertSubscriptionAction(client, i, i.action); const raw = await post(i, path(i, `/operations/${i.operationId}/client-session`), { action_token: i.action.secret }, 200, parseSubscriptionClientSessionJson); assertSubscriptionAction(client, i, i.action); return parseSubscriptionClientSession(raw, i.action, i.session); },
    confirmAction: async i => { input(i, ['session', 'subscriptionId', 'operationId', 'idempotencyKey', 'actionToken']); if (!validSubscriptionActionToken(i.actionToken)) invalid(); return mutate(i, `/operations/${i.operationId}/action-confirmations`, { action_token: i.actionToken }); },
    confirmationSession: async i => {
      input(i,['session','subscriptionId','operationId','idempotencyKey','action']); assertSubscriptionAction(client,i,i.action);
      const raw=await post(i,path(i,`/operations/${i.operationId}/confirmation-session`),{action_token:i.action.secret},200,parseConfirmationJson);
      assertSubscriptionAction(client,i,i.action);
      const context=parseConfirmationSession(raw,i.session,i.action.expiresAt,i.action);
      return context.protocol==='SERVER_CONFIRM_V2'?bindPaymentSession(client,i,context):bindSelectedSetup(client,i,i.action,context);
    },
    confirmationAttempt: async i => { input(i,['session','subscriptionId','operationId','attemptId']); return parseAttempt(await request(i.session,path(i,`/operations/${i.operationId}/confirmation-attempts/${i.attemptId}`),'GET',undefined,i.signal,[200],undefined,'SUBSCRIPTION_MANAGE',undefined,32768,parseConfirmationJson),i.attemptId); },
    currentConfirmationAttempt: async i => { input(i,['session','subscriptionId','operationId']); const raw=await request(i.session,path(i,`/operations/${i.operationId}/confirmation-attempts/current`),'GET',undefined,i.signal,[200],undefined,'SUBSCRIPTION_MANAGE',undefined,32768,parseConfirmationJson); return raw===null?null:parseAttempt(raw); },
    confirmPaymentAttempt: async i => { input(i,['session','subscriptionId','operationId','context','confirmationToken','idempotencyKey']); if(typeof i.confirmationToken!=='string'||!/^ctoken_[A-Za-z0-9]{1,200}$/.test(i.confirmationToken))invalid(); consumePaymentSession(client,i,i.context); return parseAttempt(await post(i,path(i,`/operations/${i.operationId}/confirmation-attempts/${i.context.attemptId}/confirm`),{capability:i.context.capability,confirmation_token:i.confirmationToken},200,parseConfirmationJson),i.context.attemptId,true) as SubscriptionConfirmReceipt; },
    retryPaymentAttempt: async i => { input(i,['session','subscriptionId','operationId','attemptId','idempotencyKey']); return parseAttempt(await post(i,path(i,`/operations/${i.operationId}/confirmation-attempts/${i.attemptId}/retry`),{},200,parseConfirmationJson),i.attemptId); },
    paymentAttemptSession: async i => { input(i,['session','subscriptionId','operationId','attemptId','idempotencyKey','expiresAt']); return bindPaymentSession(client,i,parseConfirmationSession(await post(i,path(i,`/operations/${i.operationId}/confirmation-attempts/${i.attemptId}/client-session`),{},200,parseConfirmationJson),i.session,i.expiresAt,undefined,i.attemptId) as SubscriptionPaymentSession); },
    portal: async i => {
      input(i, ['session', 'subscriptionId', 'idempotencyKey', 'returnUrl']); if (!validSubscriptionReturnUrl(i.returnUrl)) invalid();
      const result = parseSubscriptionPortal(await post(i, path(i, '/portal-sessions'), { return_url: i.returnUrl }, 201));
      if (result.simulated) {
        const actual = new URL(result.navigationUrl); const expected = new URL(i.returnUrl); const query = new URLSearchParams(actual.search);
        query.delete('subscriptionPortal'); query.delete('operationId');
        if (actual.origin !== expected.origin || actual.pathname !== expected.pathname || query.toString() !== expected.searchParams.toString()) throw new PaymentSdkError('INVALID_RESPONSE');
      }
      return result;
    },
  };
  if (captureAuthority) registerSubscriptionAuthority(client, captureAuthority);
  return client;
}
