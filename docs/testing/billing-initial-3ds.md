# First subscription payment with a 3DS challenge

This isolated case creates one ordinary EUR 20 Sandbox subscription. The first Pay uses a test card that requires authentication. After the server confirms the original PaymentIntent, only the browser that received that confirmation can continue the challenge.

## Running the case

Use a fresh billing run UUID, the exact committed source, dedicated ports and Docker endpoint, and the approved Sandbox account. The case uses the [reviewed checkout-local static asset policy](billing-failed-renewal-recovery.md#static-assets-in-the-owned-payment-frame). Select `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` and `PAYMENT_STRIPE_BILLING_CASE=initial_3ds`. From `examples/demo-site`, run the dedicated project:

```sh
pnpm exec playwright test --config=playwright.stripe-billing-initial-3ds.config.ts
```

For local runs, `PAYMENT_STRIPE_BILLING_3DS_CLI_PROFILE_RUN_ID` selects an earlier owned profile. The runner copies it into the fresh private directory and verifies that it is bound to the single approved test account. `PAYMENT_STRIPE_BILLING_3DS_SUBNET` optionally selects an unused, aligned `10.0.0.0/8` subnet with a `/28` prefix. Check for overlap with Docker networks and host routes before launch. The runner verifies the created bridge against the selected subnet before starting services.

There is one subscription creation, one confirmation and one SDK challenge. When the issuer's test page is ready, open this run's private `operator.html` in the operator's Chrome profile and use the challenge button once. By default the file is under `examples/demo-site/test-results/<run-uuid>/`. A custom Playwright output directory changes the parent directory. An unexpected issuer address, mismatched transaction, lost acknowledgement or expired browser authority ends the attempt. An unfinished operation cannot be submitted again.

If a refused Stripe.com origin is the first failure after the challenge dispatch receipt, the runner attempts one write to `test-results/stripe-sandbox/<run-uuid>/refused-provider-origin.private.json` from the repository root. The owner-only file contains the canonical origin, run ID, source and fixed checkpoint. Keep the file in the private run directory. The terminal marker reports only the checkpoint, and the blocked request remains blocked if the file cannot be written.

The private `issuerRelation` field records whether the refused form navigation matches the URL and 3DS transaction fields of the original Stripe authentication response. The runner aborts the route before reading that response and stops the read after 750 ms. It reads only an uncompressed response with a measured body size up to 64 KiB. A match is diagnostic evidence only. The request path, query, form data and response body are discarded.

## What establishes a pass

The original confirmation attempt must retain its winning `REQUIRES_ACTION` receipt. The terminal server records must show one paid invoice and one EUR 20 payment for the bound subscription, with matching journal entries and signed site callbacks. A separate read of the original PaymentIntent and latest captured Charge must show an authenticated 3DS 2.x challenge. Frictionless authentication, a different Charge or a second charge fails the check.

Record the source, run ID, terminal marker and cleanup result. Ordinary CI uses synthetic fixtures. This case still needs a native Sandbox receipt.
