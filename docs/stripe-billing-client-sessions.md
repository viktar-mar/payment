# Stripe Billing client sessions (Stage 5B Task 5)

This backend slice supports only the original **paid CREATE** operation and its verified original Billing invoice, default InvoicePayment allocation, and PaymentIntent. SETUP, trial payment-method collection, changes, recovery and Portal actions remain unavailable. Task 7 must add its distinct SETUP proof (including SetupIntent before a remote Subscription exists) with V27_2; V27 is PAYMENT-only. Task 6 owns V27_1. No server-side Stripe confirmation is performed.

## Customer API handoff

All action/client-session/confirmation responses use `Cache-Control: no-store`. The browser keeps Stripe client material in memory only; never put it in URLs, logs, telemetry, local/session storage or persistent state.

1. Keep the original subscription and CREATE operation IDs. Its accepted initial response may be `PROCESSING` with `action_available: false`. **`action_available` is not the sole trigger**: the Task 11 SDK must request the exact original `GET /api/v1/customer/subscriptions/{id}/operations/{operationId}/action` once for this payment handoff. A verified available action has `kind: PROVIDER_CLIENT`, an opaque `secret` grant, and `expires_at`. This is not a Stripe client secret. Ordinary operation GET stays secret-free.
2. POST `/api/v1/customer/subscriptions/{id}/operations/{operationId}/client-session` with only `{"action_token":"<opaque grant>"}`, the current bearer CustomerSession, its exact allowed `Origin`, and `Idempotency-Key`. The 200 response contains `provider: stripe`, `flow: PAYMENT`, `publishable_key`, `client_secret`, `action_token`, `expires_at`. Partner/Admin authentication cannot obtain this response. Unknown fields, ownership IDs, redirect URLs and provider intent IDs in the body are rejected. Cookie credentials cannot replace the CustomerSession.
3. After explicit Pay intent, POST the same opaque grant to the existing `.../action-confirmations` route with a stable confirmation idempotency key. This atomically consumes the durable grant and returns a 202 mutation receipt. Call Stripe.js **once only if that particular response contains `browser_confirm_allowed: true`**. The field defaults to false on all other mutation responses. The backend never calls Stripe confirm.
4. Every replay, parallel losing request, unknown local receipt and lost ACK is non-authorizing. If the winning HTTP response is lost, the browser must not guess that it may confirm. Repeating the receipt or polling an UNKNOWN operation never reopens the capability. Once consumed, GET/action cannot rotate it. Continue polling authoritative subscription state; browser success/confirmation receipts never grant entitlement or create Payment records.

The backend revalidates locked Site, Customer, CustomerSession, account, policy and operation ownership around provider reads and immediately before the response. A grant binds to its immutable original operation/provider-step/intent proof, then to the opaque session ID and exact Origin. A second session cannot take an already bound grant. Logout, revocation, disabled authority, expiry or ownership drift refuses release. Client expiry is bounded by both grant and CustomerSession expiry. Repeated same-session client retrieval may reproduce the same unconsumed secret but never extends its expiry. Browser/SDK acceptance remains Task 11 work.

## Disabled-safe dedicated key configuration

Billing actions are disabled by default, independently of Stripe Sandbox enablement. Fake and disabled Stripe startup do not require these settings. Enabling actions with invalid configuration fails startup with a fixed message that contains no key values. No installer, runtime key generation or private configuration is included here.

The application reads the following exact property names, with the corresponding environment variable fallback. Explicit properties take precedence.

| Property | Environment variable | Value |
| --- | --- | --- |
| `payment.stripe.billing-actions.enabled` | `PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED` | exact `true` or `false`, default `false` |
| `payment.stripe.billing-actions.active-key-version` | `PAYMENT_STRIPE_BILLING_ACTIONS_ACTIVE_KEY_VERSION` | one listed version, such as `v1` |
| `payment.stripe.billing-actions.key-versions` | `PAYMENT_STRIPE_BILLING_ACTIONS_KEY_VERSIONS` | 1–20 comma-separated versions, no spaces or duplicates |
| `payment.stripe.billing-actions.keys.v1` | `PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1` | Base64 encoding of exactly 32 bytes of dedicated secret key material |

Versions match `v[1-9][0-9]{0,8}`. Each configured version has distinct key bytes. Provision a cryptographically random dedicated key through the deployment's secret-management process; never reuse Stripe, Customer identity, encryption or other application credentials, and never check real keys into an example. The commented entries in `infrastructure/stripe-sandbox.env.example` are placeholders only. A deployment must explicitly pass these variables to the backend; existing bootstrap scripts and Compose wiring are unchanged by this slice.

All instances serving the account must share the same versioned key ring. A domain-separated HMAC-SHA256 over a length-framed immutable descriptor deterministically reproduces the opaque grant across restart/nodes. PostgreSQL stores only its SHA256 digest and typed immutable ownership descriptor, never the raw grant or Stripe client secret. The descriptor includes Site, Customer, account, subscription, original operation and provider step/key, exact invoice/allocation/intent proof, key version and issue/expiry timestamps. Mutable operation versions and session binding are excluded: committed reply version changes do not invalidate a receipt or make a replay authorizing.

To rotate, deploy the old and new versions to every node before switching the active version. Existing unexpired grants retain their original key version and reproduce exactly. Retain an old key until all its unconsumed grants have expired (at most ten minutes after the last issuance by an old node, plus operational clock-skew/rollout allowance). Removing it early fails closed for those grants. Only an expired, unconsumed exact-original action may rotate; a null provider-operation key never rotates, and a consumed grant is a durable tombstone regardless of key retirement. Keep unique version names forever; do not replace bytes under an existing version. Disabling issuance does not erase receipts or authorize retries.

Grant expiry, client-session response expiry and final browser permission use real UTC (`providerReferenceClock`), independent of business observation/test clocks and Stripe test clocks. Moving a business clock cannot extend or reopen a capability. Business timestamps remain on normal operation observations.

## Persistence and validation boundary

V27 keeps the PAYMENT proof strict and preserves existing Fake capability rows. Session identity is a nullable snapshot, without a restrictive CustomerSession foreign key: session cleanup/logout and terminal Customer anonymization can proceed while immutable consumed financial receipts remain. After consumption, the session snapshot may be redacted to all-null without clearing the consumption or confirmation receipt. Immutable proof, consent, expiry and one-way consumption are database-enforced. No historical migration is weakened.

The offline tests verify graph proof, token/key lifecycle, HTTP admission, real-clock authority, final permission and stored Task 4 CREATE fingerprint compatibility. PostgreSQL migration, concurrent claims and privacy behavior have authored hosted integration tests; compilation alone is not a database pass. Task 4's retained-term mapping and ACK lock-contention follow-ups remain Task 8 and Task 12 respectively.
