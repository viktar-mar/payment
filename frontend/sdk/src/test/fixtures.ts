export const token = "SYNTHETIC_TEST_TOKEN_" + "x".repeat(22);
// One checkout owns one immutable expiry across create/read/confirm responses.
const expiresAt = new Date(Date.now() + 86400000).toISOString();
export const wire = (status = "REQUIRES_PAYMENT_METHOD") => ({
  checkout_id: "11111111-1111-4111-8111-111111111111", checkout_token: token,
  payment_id: "22222222-2222-4222-8222-222222222222", amount_minor: 4999, currency: "EUR",
  price: { price_id: "33333333-3333-4333-8333-333333333333", price_key: "lifetime", version: 1, amount_minor: 4999, currency: "EUR", tax_mode: "NONE" },
  provider_type: "FAKE", confirmation_mode: "SERVER", provider_client_payload: {}, status,
  expires_at: expiresAt,
});
export const readWire = (status = "PROCESSING") => { const { checkout_token: ignored, ...value } = wire(status); return value; };
export const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
export const input = { siteKey: "test-site", priceKey: "lifetime" };
