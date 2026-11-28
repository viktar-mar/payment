export const OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID: 'PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID';
export const OPTIONAL_TRIAL_SUBNET: 'PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_SUBNET';
export function snapshotBillingOptionalTrialEnvironment(raw: unknown): Readonly<Record<string,string>> | null;
export function validateBillingOptionalTrialOptions(value: unknown): unknown;
export function billingOptionalTrialOptIn(value: unknown): boolean;
export function billingOptionalTrialExcluded(value: unknown): boolean;
