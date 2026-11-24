# Initial subscription decline and explicit retry

This case exercises a new ordinary subscription for EUR 20 in Stripe Sandbox. It tests whether the customer can recover from a declined first payment and settle the original invoice once. The first confirmation uses a declining test card. The customer then selects Retry in the subscription manager and confirms with a successful test card.

## Running the case

The browser uses the checkout's private key to recognize the previously verified Stripe static origin. Before starting a native attempt in a fresh checkout, follow the [static asset setup](billing-failed-renewal-recovery.md#static-assets-in-the-owned-payment-frame). An unrecognized origin stops the attempt.

Use the ordinary billing runner's fresh run UUID, exact committed source, isolated Docker endpoint and ports, and approved test-account binding. Set `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` and `PAYMENT_STRIPE_BILLING_CASE=initial_decline_retry`. From `examples/demo-site`, select the dedicated configuration.

```sh
pnpm exec playwright test --config=playwright.stripe-billing-decline-retry.config.ts
```

To reuse a confirmed Sandbox CLI login from this worktree, set `PAYMENT_STRIPE_BILLING_RETRY_CLI_PROFILE_RUN_ID` to the approved prior run UUID in the dedicated worker environment. This case copies only the two private authentication files into its fresh isolated CLI profile. Before starting the listener, it verifies the single expected test-only account and pinned API version against Stripe. An expired or mismatched profile stops the run before any subscription dispatch. Leave the variable unset for a fresh device code.

If Docker's default subnet pool is full, this case can use `PAYMENT_STRIPE_BILLING_RETRY_SUBNET` with an unused, network-aligned IPv4 /28 in `10.0.0.0/8`. Check current Docker networks and host routes for overlap before selecting the subnet. The runner creates one new bridge and verifies the reported subnet before recording its creation or preparing any containers. It stops if creation or verification fails.

The runner permits one subscription creation, two distinct confirmations and one explicit Retry. Before Retry, it checks that the original confirmation declined and collected no money. The successor must belong to the same subscription, invoice and PaymentIntent. The final checks reconcile the server's records, Stripe objects, journal and signed callbacks. An incomplete receipt or failed check ends the attempt.

Stripe now links an invoice line to its subscription through `parent.subscription_item_details`. The ordinary first-invoice mapper requires that nested subscription and item to match and rejects any conflicting legacy `subscription` value. [Stripe changelog](https://docs.stripe.com/changelog/basil/2025-03-31/adds-new-parent-field-to-invoicing-objects)

Stripe reports a nullable `InvoicePayment.amount_paid` until settlement. For an open original first invoice, the server and decline check allow an unavailable allocation amount while requiring the invoice to remain unpaid, the exact PaymentIntent to show zero received, and the default allocation to match in both directions. Retry still requires the authenticated failed charge. The final paid check requires the exact €20 allocation, succeeded PaymentIntent and captured charge. [Stripe Invoice Payment reference](https://docs.stripe.com/api/invoice-payment/object)

Record the run ID, exact source, terminal result and cleanup result in the acceptance matrix. The passing marker is `owned_initial_decline_retry_passed full5b=NOT_ACCEPTED`. The initial 3DS challenge has a separate acceptance requirement.

## Failure after creation

Run `a35eb316-f842-42b7-a1b0-157f942a8e99` recorded creation acknowledgement and stopped before the first confirmation dispatch. The terminal label `RETRY_ACK` also covers creation acknowledgement in this case. Its old browser code leaves the exact failed check unknown.

An offline regression reproduced a request for a nested Stripe document while the runner was still validating the original confirmation-session response. The runner now waits inside the existing request budget for the paired, validated session. It rechecks the original frame chain and retains the existing local document-block confirmation. Early images, stylesheets and scripts still refuse. A mismatched or failed session cannot release the staged document.

The terminal report now distinguishes waiting for the session, HTTP refusal, session validation, trusted presentation, form readiness and confirmation. It keeps the first fixed code and requires separate completion of browser close and request drain before reporting confirmed cleanup. These diagnostics contain no request contents.

Run `5d72b0cc-89cb-4701-989e-0465119d92d1` also stopped after creation acknowledgement. Its `BROWSER_INITIAL_SESSION` code leaves the last HTTP exchange undetermined. For subsequent decline/retry runs, the report distinguishes these boundaries using only requests for the acknowledged subscription and operation:

- `BROWSER_OPERATION_*`, `BROWSER_ACTION_*` and `BROWSER_CURRENT_*` identify the first prerequisite read with no matched response or a status other than 200. `BROWSER_ACTION_CONFLICT` means `/action` returned 409. The response body is outside the diagnostic. Ambiguous request observations retain `BROWSER_INITIAL_SESSION`.
- `BROWSER_SESSION_NOT_SENT` means the runner saw no session POST after the expected reads returned 200 or when the creation response did not call for those reads. HTTP status alone does not certify that the SDK accepted the response body.
- `BROWSER_SESSION_REQUEST_REJECTED`, `BROWSER_SESSION_REQUEST_PENDING`, `BROWSER_SESSION_NO_RESPONSE` and `BROWSER_SESSION_UNPAIRED` distinguish a rejected POST, an intercepted request awaiting continuation, a continuation that returned without an observed response and a response whose pairing the runner did not verify. These are browser observations. A matched response still passes the existing HTTP and session-wire checks before Pay.

Run `8f405cac-0a19-428f-9abe-7e7f04f0813b` reached `/action` and returned 409. Local tests independently reproduced `PROVIDER_EVIDENCE_MISMATCH` for the documented nested-only line and unfinalized allocation. The `/action` diagnostic recorded the HTTP status alone. The exact failing condition in that run remains unknown, and native acceptance still requires a fresh complete receipt.
