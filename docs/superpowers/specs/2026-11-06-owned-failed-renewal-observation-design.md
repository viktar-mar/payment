# Owned failed-renewal observation preparation

## Intent and scope

Continue Stage 5B with the smallest useful provider-facing slice: verify a raw failed first-renewal graph through the pinned Stripe SDK and produce a descriptive normalized observation for the existing owned-recovery backend. This is offline preparation, not a native attempt. The user has requested uninterrupted implementation, tests and independent review; Docker, Sandbox, old operations and DISPATCHING remain prohibited.

The durable backend from `2026-11-05-owned-recovery-backend-design.md` is already implemented and its ten PostgreSQL integration cases passed hosted CI on `f9e85685da95c6dd79deedc3623de067b1b89333`. Do not reimplement it or reinterpret hosted evidence as native acceptance.

## Boundary and interfaces

Add a package-private `StripeBillingSnapshotMapper.retrieveOwnedFailedRenewal(StripeClient, Ownership, OwnedFailedRenewal.Admission, Instant observedAt, ReadExecutor)` returning a package-private `FailedRenewalRead(Snapshot snapshot, String allocation, String paymentIntent)`. Require an executor, FINALIZATION readiness and the exact saved boundary. This record describes the graph only: it grants no persisted read claim, acceptance time, recovery authority, method ownership or publication rights. A later explicit adapter must authenticate persisted admission/read/fences before and after IO, check the actual clock, and set acceptance time only after those checks.

Keep the successful-renewal public entry closed to failure. Extract common graph verification inside the existing mapper using an internal closed success/failure lane, never a public permissive flag. The successful lane retains its ACTIVE/pending/paid behavior and all existing guards; the new lane starts only with the original ACTIVE, paid-through, pre-debt local projection and exact original revision.

Shared validation retains exact Sandbox account, credential reference, owner/customer/clock/source lineage, catalog terms/item/quantity, no trial/tax/credit/proration/change/cancellation, exact periods and exactly two invoices. Reuse strict invoice normalization and the complete original INITIAL CASH/allocation/PI/Charge/reverse allocation proof. No account/customer/subscription/terms drift or third invoice is admissible. Do not use the initial payment-action helper or read a client secret.

## Failed graph contract

The new lane requires remote `past_due`, the exact persisted-boundary renewal invoice in raw `open` status, normalized RENEWAL/OPEN with the immutable original positive amount/currency/period and no renewal cash. Require exactly one default open invoice-payment allocation with full amount requested and known zero paid, referring to a different PaymentIntent/allocation from the original payment. Re-read the reverse allocation list and require exact one-object equality.

Verify the PaymentIntent is the same customer/currency/full amount, Sandbox, card-only/automatic capture, `requires_payment_method`, known zero amount received. Require a real linked decline, not simply this unpaid status: `last_payment_error.type=card_error`, `code=card_declined`, a supported existing decline code (`generic_decline`, `insufficient_funds`, `do_not_honor`), and its charge equal to `latest_charge`. Retrieve that exact Charge; verify its object, Sandbox/customer/PI/currency/amount identity, failed status, false paid/captured, known zero captured/refunded, no refund, card details, `failure_code=card_declined`, and `outcome.type=issuer_declined` with matching reason. These conservative predicates reuse existing confirmation-adapter policy, not its dispatch/receipt authority. Missing/unknown/inconsistent values refuse. No provider error message, client secret or raw response enters the result or exception.

All IO goes through existing bounded `Read.get/pages/options` and the supplied executor. No writes, confirmation, pay, setup, new PI, retries, polling, clock changes, store/service calls, transaction changes, bean/endpoint/exporter/launcher changes or schema changes. The normalized snapshot is PAST_DUE with unchanged original CASH plus OPEN renewal, no recovery/operation markers, method readiness false; it does not authenticate method setup. Actual pinned provider behavior remains unverified until a separately authorized future native run.

## Verification

Use the existing handwritten in-memory official SDK transport: no sockets, provider network, Docker, Spring/SQL, installs, Python or operational archive/key reads. Tests first establish failure of the missing entry, then exercise successful descriptive decline mapping and negative owner/terms/tax/period/original-cash/decline/charge/reverse-join cases. Assert GET-only request sequence and old successful entry still refuses the same failed graph. Run the complete existing pure first-renewal mapping suite and related inert tests using cached dependencies and explicit selectors; no default Maven discovery.

Update the acceptance matrix and recovery guide with the already verified hosted CI and this narrower preparation. Full Stage 5B and native failed-renewal recovery remain NOT_ACCEPTED/NOT_RUN. Native time/readiness, persisted observation adapter, verified method/customer confirmation, recovered-cash producer and bounded runtime remain subsequent work.
