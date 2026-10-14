import { object } from "../contracts.js";
import { PaymentSdkError } from "../errors.js";
import type { ProviderDriver } from "./provider-driver.js";
export const fakeDriver: ProviderDriver = {
  async confirm(session, transport) {
    if (session.providerType !== "FAKE" || session.confirmationMode !== "SERVER" || !object(session.providerClientPayload) || Object.keys(session.providerClientPayload).length) throw new PaymentSdkError("UNSUPPORTED_PROVIDER");
    if (!["CREATED", "REQUIRES_PAYMENT_METHOD"].includes(session.state.status)) throw new PaymentSdkError("PAYMENT_STATE_CONFLICT");
    return transport();
  },
};
