export const siteId = "11111111-1111-4111-8111-111111111111";
export const otherSiteId = "22222222-2222-4222-8222-222222222222";
export const customerId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
export const providerId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
export const site = { id: siteId, display_name: "Shop", authentication_modes: ["OIDC_TOKEN", "SITE_TOKEN", "EMAIL_OTP"], status: "ACTIVE" };
export const customer = { customer_id: customerId, site_id: siteId, status: "ACTIVE", masked_email: "a***@e***.test", created_at: "2026-10-10T10:00:00Z", updated_at: "2026-10-10T11:00:00Z", anonymized_at: null, identities: [{ mode: "OIDC_TOKEN", issuer_key: "safe-issuer", created_at: "2026-10-10T10:00:00Z", verified_at: null, last_used_at: null, subject: "DO-NOT-SHOW-SUBJECT" }], email: "DO-NOT-SHOW-EMAIL", token: "DO-NOT-SHOW-TOKEN" };
export const provider = { id: providerId, provider_key: "oidc-main", mode: "OIDC_TOKEN", status: "ACTIVE", version: 3, issuer: "https://issuer.test", audiences: ["app"], allowed_algorithms: ["RS256"], jwks_source_type: "REMOTE", readiness: "CONFIGURED_UNVERIFIED", keys: [{ kid: "public-id", kty: "RSA", algorithm: "RS256", n: "DO-NOT-SHOW-KEY" }], last_successful_refresh_at: null, jwks_refresh_state: "REFRESH_FAILED", public_jwk_set: "DO-NOT-SHOW-RAW", jwks_uri: "DO-NOT-SHOW-URI", sender_ref: null, template_key: null };
export function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
