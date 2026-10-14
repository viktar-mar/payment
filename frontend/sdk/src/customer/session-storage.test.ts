import { expect, it, vi } from "vitest";
import * as sdk from "../index.js";
import { createGenericPaymentClient } from "../client.js";
import { customerWire, authInput, base, sessionKey } from "../test/customer-fixtures.js";
import { subscriptionSession } from '../test/subscription-fixtures.js';
const recover = (identity = { apiBaseUrl: base, siteKey: "test-site" }) => (sdk as any).recoverCustomerSession(identity);
it("recovers a normalized, Origin-bound sessionStorage capability without source credentials", async () => {
  vi.stubGlobal("fetch", async () => new Response(JSON.stringify(customerWire())));
  const session = await (createGenericPaymentClient({ apiBaseUrl: base }) as any).exchangeSiteToken(authInput);
  expect(session.customer).toEqual({ id: "11111111-1111-4111-8111-111111111111", status: "ACTIVE", maskedEmail: null });
  expect(recover()).toEqual(session);
  expect(sessionStorage.key(0)).toBe(sessionKey());
  expect(sessionStorage.getItem(sessionKey())).not.toContain(authInput.token);
  expect(recover({ apiBaseUrl: "https://other.test", siteKey: "test-site" })).toBeNull();
  expect(recover({ apiBaseUrl: base, siteKey: "other-site" })).toBeNull();
});
it('recovers old three-scope sessions without upgrading authority and preserves explicit new scopes', async () => {
  const client = createGenericPaymentClient({ apiBaseUrl: base });
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify(customerWire())));
  await client.exchangeSiteToken(authInput); expect(sdk.recoverCustomerSession({ apiBaseUrl: base, siteKey: 'test-site' })?.scopes).toEqual(['CUSTOMER_READ', 'CHECKOUT_CREATE', 'IDENTITY_LINK']);
  const next = subscriptionSession(); sessionStorage.setItem(sessionKey(), JSON.stringify({ version: 1, ...next }));
  expect(sdk.recoverCustomerSession({ apiBaseUrl: base, siteKey: 'test-site' })?.scopes).toEqual(next.scopes);
});
it.each(["extra", "version", "oversized", "context", "token", "expiry", "status", "scope", "missing"])("rejects and removes %s records", async kind => {
  vi.stubGlobal("fetch", async () => new Response(JSON.stringify(customerWire())));
  await (createGenericPaymentClient({ apiBaseUrl: base }) as any).exchangeSiteToken(authInput);
  const record = JSON.parse(sessionStorage.getItem(sessionKey())!);
  if (kind === "extra") record.email = "private@example.test";
  if (kind === "version") record.version = 2;
  if (kind === "context") record.origin = "https://evil.test";
  if (kind === "token") record.sessionToken = "bad";
  if (kind === "expiry") record.expiresAt = "2000-01-01T00:00:00Z";
  if (kind === "status") record.customer.status = "ANONYMIZED";
  if (kind === "scope") record.scopes = ["ADMIN"];
  if (kind === "missing") delete record.customer;
  sessionStorage.setItem(sessionKey(), kind === "oversized" ? "x".repeat(8193) : JSON.stringify(record));
  expect(recover()).toBeNull(); expect(sessionStorage.getItem(sessionKey())).toBeNull();
});
