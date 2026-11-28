# Stripe Billing payment-method isolation

Run the dedicated case from the repository root with a configured private Sandbox environment.

```sh
node infrastructure/stripe-billing-native-cases.mjs method_isolation
```

The launcher builds a snapshot of the selected sources, opens a fresh local database and verifies the pinned Stripe account and API version. The application publishes two €20 monthly prices with seven-day trials for different products. A single fresh customer starts both subscriptions. Three SetupIntents confirm Stripe test cards through the SDK. This exercises the server-side method assignment independently of the payment form. [Stripe testing guide](https://docs.stripe.com/testing) · [Confirm a SetupIntent](https://docs.stripe.com/api/setup_intents/confirm)

Before replacement, each subscription has its own card and the customer invoice default points to the target's original card. The application assigns the replacement to the target. Independent reads then check both subscriptions, the customer default, both original €0 invoices and their periods, and the absence of allocations, PaymentIntents and charges. PostgreSQL ties the SetupIntent and update receipts to the selected local operation. The receiver verifies the signed site callbacks against the outbox. [Update a subscription](https://docs.stripe.com/api/subscriptions/update) · [Invoice line item](https://docs.stripe.com/api/invoice-line-item/object)

The case cancels its verified trial subscriptions with invoicing and proration disabled. A passing run also checks for zero charges after cancellation. The launcher writes the case result, source hash and JUnit counts to the run's private completion receipt. [Cancel a subscription](https://docs.stripe.com/api/subscriptions/cancel)
