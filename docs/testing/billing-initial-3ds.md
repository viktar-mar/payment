# First subscription payment with a 3DS challenge

This isolated case creates one ordinary EUR 20 Sandbox subscription. The first Pay uses Stripe's web test card ending in 3220, which requires successful 3DS2 authentication. The browser that received the original confirmation continues it through Stripe.js. [Stripe test cards](https://docs.stripe.com/testing#regulatory-cards)

## Running the case

Use a fresh billing run UUID, the exact committed source, dedicated ports and Docker endpoint, and the approved Sandbox account. The case uses the [reviewed checkout-local static asset policy](billing-failed-renewal-recovery.md#static-assets-in-the-owned-payment-frame). Select `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` and `PAYMENT_STRIPE_BILLING_CASE=initial_3ds`. From `examples/demo-site`, run the dedicated project:

```sh
pnpm exec playwright test --config=playwright.stripe-billing-initial-3ds.config.ts
```

For local runs, `PAYMENT_STRIPE_BILLING_3DS_CLI_PROFILE_RUN_ID` selects an earlier owned profile. The runner copies it into the fresh private directory and verifies that it is bound to the single approved test account. `PAYMENT_STRIPE_BILLING_3DS_SUBNET` optionally selects an unused, aligned `10.0.0.0/8` subnet with a `/28` prefix. Check for overlap with Docker networks and host routes before launch. The runner verifies the created bridge against the selected subnet before starting services.

There is one subscription creation, one confirmation and one SDK challenge. When the issuer's test page is ready, open this run's private `operator.html` in the operator's Chrome profile and use the challenge button once. By default the file is under `examples/demo-site/test-results/<run-uuid>/`. A custom Playwright output directory changes the parent directory. A lost acknowledgement or expired browser authority ends the attempt. An unfinished operation cannot be submitted again.

## Browser isolation and the Stripe contract

The application uses Stripe.js `handleNextAction` after the original server confirmation returns `requires_action`. The runner persists the one-use challenge dispatch before allowing Stripe.js to proceed. It then uses a unique enabled **Complete** control in an admitted provider document. The runner checks that the same control and checkout still own the gesture immediately before clicking. Stripe.js handles the challenge protocol. [Stripe subscription integration](https://docs.stripe.com/payments/finalize-payments-on-the-server?platform=web&type=subscription#handle-any-next-actions-client-side)

The browser uses a fresh context with service workers, shared workers and downloads disabled. The original checkout owns local application requests. Popups, WebSockets, unexpected top-level navigations and foreign network destinations end the attempt. For challenge traffic, the runner admits approved HTTPS provider origins, rejects ambient credentials and keeps Stripe API access scoped to the original test PaymentIntent and authentication endpoints. The checkout-local reviewed issuer origin remains optional and is verified using the existing HMAC policy. An independent Chromium response guard blocks HTTP redirects before the next hop. [Stripe.js origin policy](https://docs.stripe.com/security/guide#content-security-policy)

The runner does not require an issuer request order, a form navigation, any ARes or CRes fields, or an iframe request and response match. It waits for the original subscription's paid state in the application before recording the browser acknowledgement. The independent server proof below determines whether the payment passed.

## Offline regression

Run from the repository root with a disposable headless Chrome profile. The contract fixtures use synthetic transactions and disable external network access. They exercise a challenge with auxiliary issuer traffic and no form-navigation receipt, provider isolation, the single-use gesture, and blocked redirects from nested Chromium targets.

```sh
PAYMENT_TEST_CHROME_CHANNEL=chrome node --test --test-concurrency=1 \
  examples/demo-site/tests/support/stripe-billing-contract-{api,challenge,redirects}.test.ts \
  examples/demo-site/tests/support/stripe-billing-acceptance.test.ts
```

Earlier issuer replay fixtures remain available for offline regression.

## What establishes a pass

The original confirmation attempt must retain its winning `REQUIRES_ACTION` receipt. The server must observe one paid invoice for the bound subscription and one EUR 20 payment, with signed site callbacks tied to the exact cash and paid-period journal entries. A separate Stripe read must show that the original PaymentIntent succeeded and that its single captured Charge records `authenticated`, `challenge` and 3DS version 2.x. Frictionless authentication, a different Charge or a second charge fails the check. [Stripe Charge 3DS result](https://docs.stripe.com/api/charges/object#charge_object-payment_method_details-card-three_d_secure) · [Stripe subscription webhooks](https://docs.stripe.com/billing/subscriptions/webhooks)

Record the source, run ID, terminal marker and cleanup result. Ordinary CI uses synthetic fixtures. This case still needs a native Sandbox receipt.
