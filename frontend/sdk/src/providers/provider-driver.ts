import type { CheckoutSession, CheckoutState } from "../contracts.js";
export interface ProviderDriver<Session extends CheckoutSession = CheckoutSession> { confirm(session: Session, transport: () => Promise<CheckoutState>): Promise<CheckoutState>; }
