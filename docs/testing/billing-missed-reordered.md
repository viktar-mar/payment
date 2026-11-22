# Recovering a subscription payment before its invoice notifications arrive

The `initial_missed_reordered` case checks whether an operator can reconcile the original EUR 20 subscription payment when the application has received neither invoice notification. It then checks whether processing both late notifications leaves the same payment, billing period, journal entries and signed callback deliveries in place.

## Running a fresh case

Use a new run ID, the exact committed source, the dedicated service ports, a verified Docker endpoint and the approved Stripe Sandbox account. The checkout needs the local policy for static assets in the payment frame. [Static asset setup](billing-failed-renewal-recovery.md#static-assets-in-the-owned-payment-frame)

Select `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` and `PAYMENT_STRIPE_BILLING_CASE=initial_missed_reordered`. Run the dedicated entry point from `examples/demo-site`.

```sh
pnpm exec playwright test --config=playwright.stripe-billing-invoice-webhooks.config.ts
```

The case creates one subscription and submits Pay once. Its local receiver holds the original signed `invoice.finalized` and `invoice.paid` deliveries in memory. Both must belong to that run's customer, subscription and invoice. The listener and application must both accept the original signatures within their validity window. A missing receipt, expired signature or uncertain operation ends the run.

## Evidence required for acceptance

1. Before Pay, the application identifies the original invoice and the unused confirmation attempt.
2. After Pay, Stripe shows one captured EUR 20 charge. The application still shows no successful payment, paid-period journal event or payment callback. Both signed notifications are still held.
3. One administrative reconciliation records the payment against the original invoice. The database, journal and callback receiver agree on the payment and period.
4. The receiver forwards the newer paid notification, waits for the application's processed receipt, then forwards the older finalized notification. The application must retain the same payment, bill, journal events and signed callback deliveries. A final independent Stripe read must still find the original charge.
5. The terminal report records acceptance and cleanup independently. Keep the source, run ID and terminal report with the stage matrix.

The PostgreSQL regressions use synthetic provider events. A native receipt requires a separate completed Stripe Sandbox run for this case.
