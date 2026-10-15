import type { Stripe } from '@stripe/stripe-js';
export async function loadStripeClient(publishableKey: string): Promise<Stripe | null> {
  const { loadStripe } = await import('@stripe/stripe-js/pure/index.js');
  return loadStripe(publishableKey);
}
