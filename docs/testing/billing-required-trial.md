# Required payment method before a Stripe Billing trial

This case creates one fresh seven-day trial on a €20 monthly price that requires a payment method. Before confirmation, the original subscription must have no trial access. The browser confirms one SetupIntent and waits for the same subscription to gain trial access. The case checks that the trial began with one €0 invoice and no payment.

## Running the case

Use a fresh run UUID, the exact committed source, six dedicated ports and the approved Docker endpoint. The runner is bound to the approved Stripe Sandbox account. It uses the [checkout-local static-asset policy](billing-failed-renewal-recovery.md#static-assets-in-the-owned-payment-frame).

Select `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` and `PAYMENT_STRIPE_BILLING_CASE=initial_required_trial`. Set `PAYMENT_STRIPE_BILLING_REQUIRED_TRIAL_CLI_PROFILE_RUN_ID` to a prior owned test-only CLI profile. The runner verifies the saved credentials and uses a new private directory. Supply an unused aligned private /28 through `PAYMENT_STRIPE_BILLING_REQUIRED_TRIAL_SUBNET` if selecting a subnet explicitly. Run the dedicated entry from `examples/demo-site`.

```sh
pnpm exec playwright test --config=playwright.stripe-billing-required-trial.config.ts
```

The isolated backend allows the ordinary subscription sweep to advance the original CREATE and keeps callback delivery enabled. Provider webhooks can enter its inbox, whose worker is paused for this case. After setup, the browser waits for the original operation to settle before a read-only refresh.

The scenario stops at the first refusal or uncertain result. It does not advance the provider clock. The Sandbox subscription retains the selected card and has its ordinary renewal scheduled after the seven-day trial.

## Evidence required for acceptance

1. A single owned CREATE and one browser permission bind the original SetupIntent. The original subscription denies access before the confirmation and shows trial access afterward.
2. Independent Stripe reads and PostgreSQL verify the original SetupIntent, scoped card, Subscription, trial period and €0 invoice. The invoice has no allocations, PaymentIntents or charges. The application records the trial event and no cash.
3. The callback receiver verifies the signature of the one trial-start delivery. The callback ID and digest match the delivered outbox payload.
4. Both the runner and its cleanup succeed before the dedicated reporter prints `owned_initial_required_trial_passed full5b=NOT_ACCEPTED`. Record the source and local terminal result with the [Stage 5B matrix](../stage-5b-acceptance.md).
