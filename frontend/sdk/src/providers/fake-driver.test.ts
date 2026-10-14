import { it, expect, vi } from "vitest";
import { fakeDriver } from "./fake-driver.js";
import { PaymentSdkError } from "../errors.js";
it("confirms only FAKE SERVER empty payload through transport once", async () => {
  const confirm = vi.fn(async () => ({ status: "PROCESSING" }));
  expect(await fakeDriver.confirm({ providerType: "FAKE", confirmationMode: "SERVER", providerClientPayload: {}, state: { status: "REQUIRES_PAYMENT_METHOD" } } as never, confirm as never)).toEqual({ status: "PROCESSING" }); expect(confirm).toHaveBeenCalledTimes(1);
});
it.each([{ providerType: "STRIPE" }, { confirmationMode: "CLIENT" }, { providerClientPayload: { secret: "synthetic" } }, { providerClientPayload: [] }, { state: { status: "PROCESSING" } }])("fails unsafe driver combination closed %#", async patch => {
  const confirm = vi.fn(); await expect(fakeDriver.confirm({ providerType: "FAKE", confirmationMode: "SERVER", providerClientPayload: {}, state: { status: "REQUIRES_PAYMENT_METHOD" }, ...patch } as never, confirm)).rejects.toBeInstanceOf(PaymentSdkError); expect(confirm).not.toHaveBeenCalled();
});
