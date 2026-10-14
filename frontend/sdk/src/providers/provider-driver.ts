import type { CheckoutSession, CheckoutState } from "../contracts.js";
export interface ProviderDriver { confirm(session: CheckoutSession, transport: () => Promise<CheckoutState>): Promise<CheckoutState>; }
