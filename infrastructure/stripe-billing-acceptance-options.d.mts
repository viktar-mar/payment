export const RETRY_CLI_PROFILE_RUN_ID:'PAYMENT_STRIPE_BILLING_RETRY_CLI_PROFILE_RUN_ID';
export const RETRY_NETWORK_SUBNET:'PAYMENT_STRIPE_BILLING_RETRY_SUBNET';
export function billingAcceptanceExcluded(env:unknown):boolean;
export function billingAcceptanceOptIn(env:unknown):boolean;
export function validateBillingAcceptanceOptions(options:unknown):Readonly<Record<string,unknown>>|null;
export function snapshotBillingAcceptanceEnvironment(env:unknown):Readonly<Record<string,string|undefined>>|null;
