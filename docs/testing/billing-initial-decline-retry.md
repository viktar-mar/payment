# Initial subscription decline and explicit retry

This case exercises a new ordinary subscription for EUR 20 in Stripe Sandbox. It tests whether the customer can recover from a declined first payment and settle the original invoice once. The first confirmation uses a declining test card. The customer then selects Retry in the subscription manager and confirms with a successful test card.

## Running the case

The browser uses the checkout's private key to recognize the previously verified Stripe static origin. Before starting a native attempt in a fresh checkout, follow the [static asset setup](billing-failed-renewal-recovery.md#static-assets-in-the-owned-payment-frame). An unrecognized origin stops the attempt.

Use the ordinary billing runner's fresh run UUID, exact committed source, isolated Docker endpoint and ports, and approved test-account binding. Set `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` and `PAYMENT_STRIPE_BILLING_CASE=initial_decline_retry`. From `examples/demo-site`, select the dedicated configuration.

```sh
pnpm exec playwright test --config=playwright.stripe-billing-decline-retry.config.ts
```

The runner permits one subscription creation, two distinct confirmations and one explicit Retry. Before Retry, it checks that the original confirmation declined and collected no money. The successor must belong to the same subscription, invoice and PaymentIntent. The final checks reconcile the server's records, Stripe objects, journal and signed callbacks. An incomplete receipt or failed check ends the attempt.

The ordinary subscription mapper requires an explicit zero for the unpaid allocation's `amount_paid`. An earlier test-clock invoice omitted that value. An ordinary invoice with the same omission would stop the case before Pay. Acceptance still requires an actual ordinary Sandbox invoice, the original failed charge and the successor's successful charge.

Record the run ID, exact source, terminal result and cleanup result in the acceptance matrix. The passing marker is `owned_initial_decline_retry_passed full5b=NOT_ACCEPTED`. The initial 3DS challenge has a separate acceptance requirement.
