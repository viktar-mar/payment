import { exact, instant, key, object, statuses, uuid } from '../contracts.js';
import type { CheckoutSession, PaymentStatus } from '../contracts.js';
import { validCursor } from '../customer/contracts.js';
import { PaymentSdkError } from '../errors.js';
export interface DonationCampaignInput { siteKey: string; campaignKey: string; signal?: AbortSignal; }
export interface DonationCheckoutInput extends DonationCampaignInput { amountMinor: number; message?: string; customerSession?: 'auto' | 'omit'; idempotencyKey?: string; }
export interface DonationCampaign { campaignKey: string; displayName: string; purposeDescription: string | null; currency: string; suggestedAmounts: number[]; minimumAmount: number; maximumAmount: number; customAmountAllowed: boolean; anonymousAllowed: boolean; }
export interface DonationContext { donationId: string; campaignKey: string; campaignVersion: number; }
export interface DonationCheckoutSession extends CheckoutSession { donation: DonationContext; }
export interface CustomerDonation extends DonationContext { paymentId: string; campaignId: string; amountMinor: number; currency: string; status: Exclude<PaymentStatus, 'UNKNOWN' | 'EXPIRED'>; messageState: 'PRESENT' | 'ABSENT' | 'REDACTED'; createdAt: string; updatedAt: string; }
export interface CustomerDonationHistory { items: CustomerDonation[]; nextCursor: string | null; }
const positive = (n: unknown): n is number => Number.isSafeInteger(n) && (n as number) > 0;
const currency = (s: unknown): s is string => typeof s === 'string' && /^[A-Z]{3}$/.test(s);
export function donationInput(raw: unknown, create = false): asserts raw is DonationCheckoutInput {
  const allowed = ['siteKey', 'campaignKey', 'signal', ...(create ? ['amountMinor', 'message', 'customerSession', 'idempotencyKey'] : [])];
  if (!object(raw) || !key(raw.siteKey) || !key(raw.campaignKey) || Object.keys(raw).some(k => !allowed.includes(k)) || (raw.signal !== undefined && !(raw.signal instanceof AbortSignal)) || (create && (!positive(raw.amountMinor) || (raw.message !== undefined && (typeof raw.message !== 'string' || [...raw.message.normalize('NFC')].length > 500)) || (raw.customerSession !== undefined && !['auto', 'omit'].includes(raw.customerSession as string)) || (raw.idempotencyKey !== undefined && (typeof raw.idempotencyKey !== 'string' || !raw.idempotencyKey.trim() || raw.idempotencyKey.length > 255 || /[\u0000-\u001f\u007f-\u009f]/.test(raw.idempotencyKey)))))) throw new PaymentSdkError('INVALID_CONFIGURATION');
}
export function parseCampaign(raw: unknown, campaignKey: string): DonationCampaign {
  if (!object(raw) || !exact(raw, ['key', 'display_name', 'purpose_description', 'currency', 'suggested_amounts', 'minimum_amount', 'maximum_amount', 'custom_amount_allowed', 'anonymous_allowed']) || raw.key !== campaignKey || typeof raw.display_name !== 'string' || !raw.display_name.trim() || (raw.purpose_description !== null && typeof raw.purpose_description !== 'string') || !currency(raw.currency) || !positive(raw.minimum_amount) || !positive(raw.maximum_amount) || raw.minimum_amount > raw.maximum_amount || typeof raw.custom_amount_allowed !== 'boolean' || typeof raw.anonymous_allowed !== 'boolean' || !Array.isArray(raw.suggested_amounts) || new Set(raw.suggested_amounts).size !== raw.suggested_amounts.length || raw.suggested_amounts.some(n => !positive(n) || n < (raw.minimum_amount as number) || n > (raw.maximum_amount as number)) || (!raw.custom_amount_allowed && !raw.suggested_amounts.length)) throw new PaymentSdkError('INVALID_RESPONSE');
  return { campaignKey, displayName: raw.display_name, purposeDescription: raw.purpose_description, currency: raw.currency, suggestedAmounts: [...raw.suggested_amounts] as number[], minimumAmount: raw.minimum_amount, maximumAmount: raw.maximum_amount, customAmountAllowed: raw.custom_amount_allowed, anonymousAllowed: raw.anonymous_allowed };
}
export function parseDonationContext(raw: unknown): DonationContext {
  if (!object(raw) || !exact(raw, ['donation_id', 'campaign_key', 'campaign_version']) || !uuid(raw.donation_id) || !key(raw.campaign_key) || !positive(raw.campaign_version)) throw new PaymentSdkError('INVALID_RESPONSE');
  return { donationId: raw.donation_id, campaignKey: raw.campaign_key, campaignVersion: raw.campaign_version };
}
export function parseDonationHistory(raw: unknown, limit: number): CustomerDonationHistory {
  if (!object(raw) || !exact(raw, ['items', 'next_cursor']) || !Array.isArray(raw.items) || raw.items.length > limit || (raw.next_cursor !== null && !validCursor(raw.next_cursor))) throw new PaymentSdkError('INVALID_RESPONSE');
  const items = raw.items.map(item => {
    if (!object(item) || !exact(item, ['donation_id', 'payment_id', 'campaign_id', 'campaign_key', 'campaign_version', 'amount_minor', 'currency', 'status', 'message_state', 'created_at', 'updated_at']) || !uuid(item.payment_id) || !uuid(item.campaign_id) || !positive(item.amount_minor) || !currency(item.currency) || !statuses.includes(item.status as string) || ['UNKNOWN', 'EXPIRED'].includes(item.status as string) || !['PRESENT', 'ABSENT', 'REDACTED'].includes(item.message_state as string) || !instant(item.created_at) || !instant(item.updated_at) || Date.parse(item.updated_at) < Date.parse(item.created_at)) throw new PaymentSdkError('INVALID_RESPONSE');
    const context = parseDonationContext({ donation_id: item.donation_id, campaign_key: item.campaign_key, campaign_version: item.campaign_version });
    return { ...context, paymentId: item.payment_id, campaignId: item.campaign_id, amountMinor: item.amount_minor, currency: item.currency, status: item.status as CustomerDonation['status'], messageState: item.message_state as CustomerDonation['messageState'], createdAt: item.created_at, updatedAt: item.updated_at };
  });
  return { items, nextCursor: raw.next_cursor as string | null };
}
