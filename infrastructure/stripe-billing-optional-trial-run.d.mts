import type {BillingLimits} from './stripe-billing-acceptance.mjs';

export interface OptionalTrialBrowserPermit {
  readonly setupIntentId: string;
  readonly publishableKey: string;
  readonly clientSecret: string;
  readonly expiresAt: string;
}

export function isOptionalTrialResumeDiagnostic(code: unknown): boolean;

export function createOwnedBillingOptionalTrial(options: unknown): {
  run(journey: (permit: OptionalTrialBrowserPermit, limits: BillingLimits) => Promise<true>, limits: BillingLimits): Promise<unknown>;
  close(): Promise<unknown>;
  resources(): unknown;
};
