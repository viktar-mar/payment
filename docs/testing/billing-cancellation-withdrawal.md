# Schedule and withdraw cancellation at period end

This case starts with one fresh ordinary subscription and one initial EUR 20 test payment. A customer requests cancellation at the end of the paid period and then withdraws that request before the period ends. Access should remain active through the same paid-through date at both checkpoints.

Current verification covers synthetic provider responses and PostgreSQL. The ordinary first-payment path requires an explicit zero for the unpaid invoice allocation. A previous test-clock run observed a missing value before payment. If the ordinary subscription returns the same shape, its existing validation will stop before Pay. A native scheduling or withdrawal receipt is still outstanding.

## Running the case

The dedicated Playwright configuration is `examples/demo-site/playwright.stripe-billing-cancellation.config.ts`. It selects one test in one worker, with zero retries. The ordinary browser suite excludes this case.

Use the billing runner's fresh run UUID, exact committed source, dedicated Docker daemon endpoint, six unique ports and approved test-account binding. Select `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` and `PAYMENT_STRIPE_BILLING_CASE=initial_cancel_withdraw`. In a prepared and authorized environment, run this command from `examples/demo-site`.

```sh
npx --no-install playwright test --config=playwright.stripe-billing-cancellation.config.ts
```

Before scheduling cancellation, the runner verifies the original payment, journal, signed callbacks and Stripe objects. It then submits the schedule request once through the customer’s subscription manager. Withdrawal becomes available only after the runner verifies the scheduled state locally and in Stripe. It submits the withdrawal once and verifies the same subscription, original charge, access and paid-through date again.

The financial budget is one subscription creation and one initial confirmation. Scheduling and withdrawal each have their own operation and receipt. An uncertain dispatch or failed check ends the run. Historical operations stay closed.

The passing marker is `owned_period_end_cancellation_passed full5b=NOT_ACCEPTED`. It requires both checkpoints and confirmed cleanup. Capture the source revision and bounded run report before marking this row in the Stage 5B matrix.
