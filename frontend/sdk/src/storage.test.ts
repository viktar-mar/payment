import { it, expect, vi } from "vitest";
import { createGenericPaymentClient } from "./client.js";
import { recoverCheckout, storageKey } from "./storage.js";
import { input, wire, json, token } from "./test/fixtures.js";
const identity = { ...input, apiBaseUrl: "https://api.test" };
it("stores only versioned allowlisted safe recovery fields and clears exact owned record", async () => {
  const local = vi.spyOn(Storage.prototype, "setItem"); vi.stubGlobal("fetch", async () => json(wire()));
  const client = createGenericPaymentClient(identity); const session = await client.createCatalogCheckout(input);
  const key = storageKey(identity); const record = JSON.parse(sessionStorage.getItem(key)!);
  expect(Object.keys(record).sort()).toEqual(["apiBaseUrl", "checkoutId", "checkoutToken", "confirmationMode", "expiresAt", "priceKey", "providerType", "siteKey", "state", "version"]);
  expect(record.version).toBe(2); expect(record.providerType).toBe("FAKE"); expect(record.confirmationMode).toBe("SERVER");
  expect(record.checkoutToken).toBe(token); expect(JSON.stringify(record.state)).not.toContain(token);
  expect(local.mock.instances.every(instance => instance === sessionStorage)).toBe(true);
  expect(recoverCheckout(identity)?.checkoutId).toBe(session.checkoutId);
  sessionStorage.setItem("foreign", "untouched"); client.clear(session); expect(sessionStorage.getItem(key)).toBeNull(); expect(sessionStorage.getItem("foreign")).toBe("untouched");
});
it.each(["malformed", "oversized", "version", "expiry", "identity", "token", "state", "extra"])("rejects and removes invalid recovery %s", async kind => {
  vi.stubGlobal("fetch", async () => json(wire())); await createGenericPaymentClient(identity).createCatalogCheckout(input);
  const key = storageKey(identity); const record = JSON.parse(sessionStorage.getItem(key)!);
  if (kind === "version") record.version = 3;
  if (kind === "expiry") record.expiresAt = "2000-01-01T00:00:00.000Z";
  if (kind === "identity") record.apiBaseUrl = "https://evil.test";
  if (kind === "token") record.checkoutToken = "invalid";
  if (kind === "state") record.state.status = "ALIEN";
  if (kind === "extra") record.providerClientPayload = { secret: "synthetic" };
  sessionStorage.setItem(key, kind === "malformed" ? "{" : kind === "oversized" ? "x".repeat(9000) : JSON.stringify(record));
  expect(recoverCheckout(identity)).toBeNull(); expect(sessionStorage.getItem(key)).toBeNull();
});
it("binds exact normalized API Site and Price and ignores other namespaces", async () => {
  vi.stubGlobal("fetch", async () => json(wire())); await createGenericPaymentClient(identity).createCatalogCheckout(input);
  expect(recoverCheckout({ ...identity, apiBaseUrl: "https://API.test:443/" })).not.toBeNull();
  for (const patch of [{ apiBaseUrl: "https://other.test" }, { siteKey: "other-site" }, { priceKey: "other-price" }]) expect(recoverCheckout({ ...identity, ...patch })).toBeNull();
  expect(sessionStorage.length).toBe(1);
});
it('accepts a legacy version 1 record as Fake only and upgrades it after authorized read', async () => {
  vi.stubGlobal('fetch', async () => json(wire())); const client = createGenericPaymentClient(identity); await client.createCatalogCheckout(input);
  const key = storageKey(identity); const record = JSON.parse(sessionStorage.getItem(key)!); delete record.providerType; delete record.confirmationMode; record.version = 1;
  sessionStorage.setItem(key, JSON.stringify(record)); const restored = recoverCheckout(identity)!;
  expect(restored.providerType).toBe('FAKE'); expect(restored.confirmationMode).toBe('SERVER');
  vi.stubGlobal('fetch', async () => { const { checkout_token: ignored, ...read } = wire(); return json(read); });
  await client.get(restored); expect(JSON.parse(sessionStorage.getItem(key)!).version).toBe(2);
});
it.each([{ providerType: 'STRIPE', confirmationMode: 'SERVER' }, { providerType: 'FAKE', confirmationMode: 'CLIENT_PROVIDER' }, { providerType: 'ALIEN', confirmationMode: 'SERVER' }])('removes invalid version 2 provider binding %j', async patch => {
  vi.stubGlobal('fetch', async () => json(wire())); await createGenericPaymentClient(identity).createCatalogCheckout(input);
  const key = storageKey(identity); const record = JSON.parse(sessionStorage.getItem(key)!); sessionStorage.setItem(key, JSON.stringify({ ...record, ...patch }));
  expect(recoverCheckout(identity)).toBeNull(); expect(sessionStorage.getItem(key)).toBeNull();
});
