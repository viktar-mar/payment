import type { CustomerScope, CustomerSession, ResponseRule } from '../customer/contracts.js';
export const subscriptionStates = ['INCOMPLETE', 'TRIALING', 'ACTIVE', 'PAST_DUE', 'PAUSED', 'UNPAID', 'CANCELLED', 'CANCEL_AT_PERIOD_END'] as const;
export const billingStatuses = ['INCOMPLETE', 'TRIALING', 'ACTIVE', 'PAST_DUE', 'PAUSED', 'UNPAID', 'CANCELLED'] as const;
export const operationStatuses = ['RESERVED', 'PROCESSING', 'REQUIRES_ACTION', 'UNKNOWN', 'SUCCEEDED', 'FAILED'] as const;
export const operationKinds = ['CREATE', 'CHANGE', 'CLEAR_CHANGE', 'CANCEL', 'WITHDRAW_CANCEL', 'SETUP', 'RECOVER', 'RESUME', 'PORTAL', 'RECONCILE'] as const;
export const permittedActions = ['CHANGE_PLAN', 'CLEAR_CHANGE', 'CANCEL_PERIOD_END', 'CANCEL_IMMEDIATE', 'WITHDRAW_CANCELLATION', 'SETUP_METHOD', 'RECOVER_INVOICE', 'RESUME', 'OPEN_PORTAL'] as const;
export const accessReasons = ['PAID', 'TRIAL', 'GRACE', 'INITIAL_PAYMENT_REQUIRED', 'PAUSED', 'UNPAID', 'CANCELLED', 'EXPIRED', 'NO_SUBSCRIPTION'] as const;
export const eventTypes = ['CREATED', 'CONFIRMED', 'TRIAL_STARTED', 'TRIAL_ENDED', 'PAUSED', 'PERIOD_PAID', 'RENEWAL_FAILED', 'GRACE_STARTED', 'ACCESS_SUSPENDED', 'RECOVERED', 'CHANGE_REQUESTED', 'CHANGE_SCHEDULED', 'CHANGE_APPLIED', 'CHANGE_CLEARED', 'CANCEL_SCHEDULED', 'CANCEL_WITHDRAWN', 'CANCELLED', 'METHOD_UPDATED', 'RECONCILIATION_REQUIRED'] as const;
export type SubscriptionTiming = 'IMMEDIATE' | 'NEXT_PERIOD';
export interface SubscriptionPrice { priceId: string; priceKey: string; version: number; productId: string; amountMinor: number; currency: string; interval: 'MONTH' | 'YEAR'; trialDays: number; trialPaymentMethodPolicy: 'REQUIRED_BEFORE_TRIAL' | 'OPTIONAL_DURING_TRIAL'; taxMode: 'NONE' | 'INCLUSIVE' | 'EXCLUSIVE' | 'STRIPE_TAX'; }
export interface SubscriptionPeriod { start: string; end: string; }
export interface SubscriptionAccess { allowed: boolean; reason: typeof accessReasons[number]; siteId: string; customerId: string; productId: string; subscriptionId: string | null; entitledPriceId: string | null; entitledPriceVersion: number | null; revision: number; evaluatedAt: string; validUntil: string | null; }
export interface SubscriptionView {
  subscriptionId: string; siteId: string; customerId: string; productId: string; paymentAccountId: string;
  billingPrice: SubscriptionPrice; entitledPrice: SubscriptionPrice | null; state: typeof subscriptionStates[number]; billingStatus: typeof billingStatuses[number]; revision: number;
  period: SubscriptionPeriod | null; paidThrough: string | null; trial: SubscriptionPeriod | null; cancellation: { effectiveAt: string } | null;
  pendingChange: { operationId: string; targetPrice: SubscriptionPrice; effectiveAt: string; state: 'PAYMENT_PENDING' | 'SCHEDULED' | 'UNKNOWN' } | null;
  pendingOperation: { operationId: string; kind: typeof operationKinds[number]; status: typeof operationStatuses[number] } | null;
  debt: { billingRecordId: string; period: SubscriptionPeriod; graceDeadline: string | null; amountDueMinor: number; currency: string } | null;
  access: SubscriptionAccess; permittedActions: typeof permittedActions[number][]; nextAmount: { amountMinor: number; currency: string; taxFinal: boolean } | null; createdAt: string; updatedAt: string;
}
export interface SubscriptionOffer { price: SubscriptionPrice; direction: 'UPGRADE' | 'DOWNGRADE' | 'LATERAL' | null; allowedTimings: SubscriptionTiming[]; trialEligible: boolean; }
export interface SubscriptionBillingView { billingRecordId: string; subscriptionId: string; kind: 'INITIAL' | 'RENEWAL' | 'PRORATION'; price: SubscriptionPrice; previousPrice: SubscriptionPrice | null; period: SubscriptionPeriod; amountDueMinor: number; currency: string; settlement: 'OPEN' | 'CONFIRMED_CASH' | 'ZERO_AMOUNT' | 'UNSUPPORTED' | 'VOID'; paymentIds: string[]; createdAt: string; updatedAt: string; }
export interface SubscriptionPage { items: SubscriptionView[]; nextCursor: string | null; }
export interface SubscriptionBillingPage { items: SubscriptionBillingView[]; nextCursor: string | null; }
export interface SubscriptionEvent { eventId: string; subscriptionId: string; sequence: number; revision: number; type: typeof eventTypes[number]; source: 'API' | 'PROVIDER_WEBHOOK' | 'RECONCILIATION' | 'DEADLINE'; occurredAt: string; receivedAt: string; operationId: string | null; billingRecordId: string | null; paymentId: string | null; }
export interface SubscriptionHistory { items: SubscriptionEvent[]; nextCursor: string | null; }
export interface SubscriptionQuote { quoteId: string; subscriptionId: string; fromPriceId: string; fromPriceVersion: number; targetPrice: SubscriptionPrice; timing: SubscriptionTiming; dueNowMinor: number; nextAmountMinor: number; currency: string; effectiveAt: string; expiresAt: string; taxFinal: boolean; subscriptionRevision: number; policyVersion: number; }
/** Secret-bearing memory-only capability. Never serialize, log, emit, or attach to the DOM. */
export interface SubscriptionAction { kind: 'FAKE_CONFIRM_PAYMENT' | 'FAKE_CONFIRM_SETUP' | 'PROVIDER_CLIENT'; secret: string; expiresAt: string; }
export interface SubscriptionOperation { operationId: string; subscriptionId: string; status: typeof operationStatuses[number]; actionAvailable: boolean; nextAction?: SubscriptionAction; }
/** Ephemeral mutation receipt. Never project permission into operation GETs or UI state. */
export interface SubscriptionMutationReceipt extends SubscriptionOperation { readonly browserConfirmAllowed: boolean; }
/** Memory-only provider context bound to the original action and Customer session. */
export interface SubscriptionClientSession { readonly flow: 'PAYMENT' | 'SETUP'; readonly publishableKey: string; readonly clientSecret: string; readonly actionToken: string; readonly expiresAt: string; }
/** Short-lived server navigation result; keep only in memory. */
export interface SubscriptionPortal { navigationUrl: string; expiresAt: string; simulated: boolean; }
export interface SubscriptionRequest { session: CustomerSession; signal?: AbortSignal; }
export interface SubscriptionIdRequest extends SubscriptionRequest { subscriptionId: string; }
export interface SubscriptionMutation extends SubscriptionIdRequest { idempotencyKey: string; }
export interface SubscriptionClient {
  list(input: SubscriptionRequest & { limit?: number; cursor?: string }): Promise<SubscriptionPage>;
  offers(input: SubscriptionRequest & { productId: string }): Promise<SubscriptionOffer[]>;
  get(input: SubscriptionIdRequest): Promise<SubscriptionView>;
  history(input: SubscriptionIdRequest & { limit?: number; cursor?: string }): Promise<SubscriptionHistory>;
  billingRecords(input: SubscriptionIdRequest & { limit?: number; cursor?: string }): Promise<SubscriptionBillingPage>;
  create(input: SubscriptionRequest & { priceId: string; recurringConsent: boolean; idempotencyKey: string }): Promise<SubscriptionOperation>;
  preview(input: SubscriptionIdRequest & { targetPriceId: string; timing: SubscriptionTiming }): Promise<SubscriptionQuote>;
  change(input: SubscriptionMutation & { quoteId: string }): Promise<SubscriptionOperation>;
  clearChange(input: SubscriptionMutation): Promise<SubscriptionOperation>;
  cancel(input: SubscriptionMutation & { mode: 'PERIOD_END' | 'IMMEDIATE'; confirmation?: string }): Promise<SubscriptionOperation>;
  withdrawCancellation(input: SubscriptionMutation): Promise<SubscriptionOperation>;
  setupMethod(input: SubscriptionMutation & { recurringConsent: boolean }): Promise<SubscriptionOperation>;
  recover(input: SubscriptionMutation & { billingRecordId: string }): Promise<SubscriptionOperation>;
  resume(input: SubscriptionMutation & { recurringConsent: boolean }): Promise<SubscriptionOperation>;
  operation(input: SubscriptionIdRequest & { operationId: string }): Promise<SubscriptionOperation>;
  action(input: SubscriptionIdRequest & { operationId: string }): Promise<SubscriptionAction>;
  clientSession(input: SubscriptionMutation & { operationId: string; action: SubscriptionAction }): Promise<SubscriptionClientSession>;
  confirmAction(input: SubscriptionMutation & { operationId: string; actionToken: string }): Promise<SubscriptionMutationReceipt>;
  portal(input: SubscriptionMutation & { returnUrl: string }): Promise<SubscriptionPortal>;
}
/** Internal bridge: Customer authority is checked before send and after completion. */
export type SubscriptionAuthorizedRequest = (session: CustomerSession, path: string, method: 'GET' | 'POST', body: object | undefined, signal: AbortSignal | undefined, expected: readonly number[], idempotency: string | undefined, scope: Extract<CustomerScope, 'SUBSCRIPTION_READ' | 'SUBSCRIPTION_MANAGE'>, rule?: ResponseRule, cap?: number, parseSuccess?: (raw: string) => unknown) => Promise<unknown>;
