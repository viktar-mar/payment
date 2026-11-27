# First subscription payment with a 3DS challenge

This isolated case creates one ordinary EUR 20 Sandbox subscription. The first Pay uses Stripe's web test card ending in 3220, which requires successful 3DS2 authentication. After the server confirms the original PaymentIntent, only the browser that received that confirmation can continue the challenge. Stripe does not document a fixed issuer resource set for this test card. [Stripe test cards](https://docs.stripe.com/payments/3d-secure/authentication-flow#test-the-3ds-flow)

## Running the case

Use a fresh billing run UUID, the exact committed source, dedicated ports and Docker endpoint, and the approved Sandbox account. The case uses the [reviewed checkout-local static asset policy](billing-failed-renewal-recovery.md#static-assets-in-the-owned-payment-frame). Select `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` and `PAYMENT_STRIPE_BILLING_CASE=initial_3ds`. From `examples/demo-site`, run the dedicated project:

```sh
pnpm exec playwright test --config=playwright.stripe-billing-initial-3ds.config.ts
```

For local runs, `PAYMENT_STRIPE_BILLING_3DS_CLI_PROFILE_RUN_ID` selects an earlier owned profile. The runner copies it into the fresh private directory and verifies that it is bound to the single approved test account. `PAYMENT_STRIPE_BILLING_3DS_SUBNET` optionally selects an unused, aligned `10.0.0.0/8` subnet with a `/28` prefix. Check for overlap with Docker networks and host routes before launch. The runner verifies the created bridge against the selected subnet before starting services.

There is one subscription creation, one confirmation and one SDK challenge. When the issuer's test page is ready, open this run's private `operator.html` in the operator's Chrome profile and use the challenge button once. By default the file is under `examples/demo-site/test-results/<run-uuid>/`. A custom Playwright output directory changes the parent directory. An unexpected issuer address, mismatched transaction, lost acknowledgement or expired browser authority ends the attempt. An unfinished operation cannot be submitted again.

If a refused Stripe.com origin is the first failure after the challenge dispatch receipt, the runner attempts one write to `test-results/stripe-sandbox/<run-uuid>/refused-provider-origin.private.json` from the repository root. The owner-only file contains the canonical origin, run ID, source and fixed checkpoint. Keep the file in the private run directory. The terminal marker reports only the checkpoint, and the blocked request remains blocked if the file cannot be written.

The private `issuerRelation` field records whether the refused form navigation matches the URL and 3DS transaction fields of the original Stripe authentication response. The runner aborts the route before reading that response and stops the read after 750 ms. It reads only an uncompressed response with a measured body size up to 64 KiB. A match is diagnostic evidence only. The request path, query, form data and response body are discarded.

The dedicated entry can resolve the reviewed issuer origin from the owner-only report and checkout-local policy key. The complete report must match its reviewed HMAC, original run and source, and verified transaction-and-frame category. The rule applies to the original POST into the direct Stripe.js challenge iframe and the one trusted form submission on that same issuer origin. Both requests still require their original frame and paired nonredirect responses. If the report or policy key is unavailable, the checkout uses its existing issuer policy.

## Browser isolation and the Stripe contract

The application uses Stripe.js `handleNextAction` after the server's original confirmation returns `requires_action`. The acceptance browser runs in a fresh context with service workers and downloads disabled. Its Stripe API requests are scoped to that original test PaymentIntent and the challenge source returned for it. The runner keeps the one-time operator gesture tied to the original frame and checks the first issuer form's action and body through the browser before the click. [Stripe subscription integration](https://docs.stripe.com/payments/finalize-payments-on-the-server?platform=web&type=subscription#handle-any-next-actions-client-side)

The Chromium guard pairs each admitted request with its original target, frame, and response. It allows the authentication navigation to the exact issuer URL returned by the original ARes and checks both 3DS transaction IDs. It allows one issuer submission from the verified frame after the trusted Complete click. Redirects, unrelated origins, replacement frames, replayed requests, and provider requests carrying cookies or foreign credentials are rejected.

The approved issuer frame has a separate resource budget for each of the two documents. The guard blocks at most six distinct same-origin raster, font or stylesheet GETs per document and requires the browser's corresponding failure events. Same-origin JavaScript requires one of six distinct canonical paths, a parser initiator on the verified document loader, and its paired HTTP 200 JavaScript response. Other resource types and script-initiated loads fail the check.

If Stripe.js receives CRes through a browser form navigation, the guard allows one original form POST from the committed issuer decision document. The destination must be a canonical non-root HTTPS URL on the exact `hooks.stripe.com` origin. Its path and query belong to Stripe. The guard checks that exact request URL in the Chromium receipt. It also checks the cookie-free POST, form encoding and size, a single canonical Base64URL CRes, both original transaction IDs, the protocol version and terminal status `Y` or `N`. An explicit completion indicator must be `Y`. An optional session value can appear once. The response must be the paired HTTP 200 HTML response. A paused result prevents SDK completion and the final intent read until the guard confirms that response.

Chromium renders the Stripe and issuer pages. The acceptance runner does not read their HTML or JavaScript bodies or derive a form from their markup. A valid CRes only correlates the browser traffic to the initial challenge. The application and independent server proof establish settlement. [EMVCo 3DS challenge flow](https://www.emvco.com/dynamic/emv-3-d-secure-whitepaper-v2/challenge-flow/technical-features/) · [Stripe 3DS flow](https://docs.stripe.com/payments/3d-secure/authentication-flow#control-the-3ds-flow)

## Offline regression

Run from the repository root with a disposable headless Chrome profile. The fixtures use synthetic transactions and block external DNS. They exercise the live request and response guard across Chromium targets, including dynamically generated issuer markup, foreign destinations, duplicate or malformed result fields, transaction mismatches, and invalid response pairing.

```sh
PAYMENT_TEST_CHROME_CHANNEL=chrome node --test --test-concurrency=1 examples/demo-site/tests/support/stripe-billing-initial-3ds-{challenge,redirect}-chrome.test.ts
```

Earlier issuer replay fixtures remain available for offline regression. The native acceptance entry no longer reads or captures issuer replay pairs.

## What establishes a pass

The original confirmation attempt must retain its winning `REQUIRES_ACTION` receipt. The server must observe one paid invoice for the bound subscription and one EUR 20 payment, with signed site callbacks tied to the exact cash and paid-period journal entries. A separate Stripe read must show that the original PaymentIntent succeeded and that its single captured Charge records `authenticated`, `challenge` and 3DS version 2.x. Frictionless authentication, a different Charge or a second charge fails the check. [Stripe Charge 3DS result](https://docs.stripe.com/api/charges/object#charge_object-payment_method_details-card-three_d_secure) · [Stripe subscription webhooks](https://docs.stripe.com/billing/subscriptions/webhooks)

Record the source, run ID, terminal marker and cleanup result. Ordinary CI uses synthetic fixtures. This case still needs a native Sandbox receipt.
