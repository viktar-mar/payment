# Original debt recovery: ordinary write isolation and independent remote preflight

## Intent

Continue Stage 5B after original management-authorized recovery preparation (8d01100). Before implementing a new financial producer, close the ordinary INVOICE_PAY admission gap and add the independent remote evidence check that producer will need. This delivers executable internal components, not a native acceptance claim or a financial dispatch permit. Full 5B remains NOT_ACCEPTED.

The controller chooses this boundary: ordinary recovery only pays a never-attempted invoice without an allocation; the owned lane already has the original failed allocation/PaymentIntent and an immutable null-pay recovery proof. V34 excludes dispatched INVOICE_PAY and V27_4 forbids modifying the proof. Neither guard may be silently bypassed. Public official API references describe [invoice.pay](https://docs.stripe.com/api/invoices/pay) and [PaymentIntent.confirm](https://docs.stripe.com/api/payment_intents/confirm), but do not establish our pinned same-allocation runtime contract. This is a limitation inferred from the documented contracts, not a claim that Stripe cannot preserve that identity. No mutation is selected or implemented here.

## Fixed constraints

- Existing Java 25, Spring, official Stripe Java 34.0.0, pinned API 2026-09-30.endive. No dependencies, migration, endpoint, registration, runner or deployment changes.
- No local Docker, SQL/Spring runtime, browser, Stripe/provider requests, Python, installations, keys or historical archives. Old financial operations and DISPATCHING remain untouched.
- Workers read/edit source and report only; root alone runs cached explicit pure JUnit and compilation, Git and GitHub. Real DB behavior is verified by hosted CI only.
- No claims, provider writes, proof binding, lifecycle/cash/access/journal/outbox/callback changes, stops, new browser grants, adoption or retries in the new reader.
- Existing ordinary recovery and existing METHOD observer remain behaviorally separate. Historical successful METHOD evidence may be consumed; a PROCESSING setup cannot stand in for completed original setup.

## A. Deny ordinary invoice payment for clock-owned subscription customers

Extend the existing resource ownership exclusion in JdbcProviderOperationStore to include INVOICE_PAY, using the same durable Account/Site/Customer clock-intent join, independent of mutable operation authority text or status. The existing common requireResource boundary must refuse ordinary reservation, first claim, acknowledgment and manual recovery binding for an owned RECOVER resource. This also covers previously inserted RESERVED receipts, without altering or consuming them. Ordinary nonclock RECOVER remains supported. No new lock order or stronger locks are introduced.

Hosted tests use genuine recoveryPrepared composition for both browser variants for reservation refusal. Negative legacy receipt fixtures may insert only provider receipts in a rollback-contained test to prove claim/ACK/binding refusal; they are not positive financial evidence. Assert receipt/financial/authority snapshots and SDK call count unchanged. Add a genuine ordinary nonclock positive control using an existing ordinary recovery fixture where practical.

## B. Independently read unchanged debt and completed original card

Add explicit unregistered StripeBillingOwnedFailedRenewalRecoveryReader. Public inspect(Reservation, Completion, Recovery, SubscriptionCommandContext) returns a redacted PreparedRecovery description containing the exact original RecoveryPreparationContext and mapped Snapshot only. It is not a durable receipt or reusable dispatch capability.

Authenticate with inspectBrowserRecoveryPreparation before credentials or caller-derived provider/store locators. Load the exact original setup proof and the two acknowledged provider steps via existing read-only ports using IDs from the authenticated Completion. Validate all joins, original step kinds/IDs/versions (2), fingerprints, owner account/credentials, references and chronology. Re-inspection includes unchanged original preparation and these immutable proof/step records, before/after each logical provider read and before return. The original management session expiry is exclusive and sampled with raw UTC, including after blocking authentication and in the worker before any SDK call.

New package-private mapper retrieveOwnedRecoveryPreparation consumes Ownership, RecoveryPreparationContext, OwnedMethodCandidate, DigestPort, observedAt and ReadExecutor. It validates the original RESERVED RECOVER association using existing exact recovery checks, SETUP SUCCEEDED at completion.setupFinalVersion=2, exact completion IDs/versions/setup/method and owner relationships. Reuse the complete failed-debt graph normalization, initial cash preservation, original default allocation/PI and declined charge/reverse joins, then independently retrieve original succeeded SetupIntent and attached card PaymentMethod. Require subscription default_payment_method equals that exact completed method. The old retrieveOwnedMethodReady remains PROCESSING-only; do not fabricate a PROCESSING operation from the completed one. Extract only shared validation/normalization mechanics if needed.

Read the same admitted ready TestClock before and after the graph, verifying identity/frozen time/readiness and all existing owned-clock fields. All actual calls are GET only, with pinned API, zero retries, no idempotency key, max 100 total logical GETs (including clock GETs), existing graph page limits, 2 seconds per GET including scheduling, and immutable 30-second invocation budget covering caller authentication/store/credential work. Enforce real and injected monotonic time and UTC rollback/deadline, exact original session expiry, interruption and cancellation. Fail closed with cause-free OWNED_RECOVERY_PREFLIGHT_UNAVAILABLE / 409 / "Owned recovery preflight unavailable"; expose no SDK payload, client secret, credential, card detail or arbitrary exception in result/toString/error.

No new generic transport framework or shared write-helper changes. A small read-only helper local to this reader is acceptable. Repeated preflight is permitted only while original authority/debt remains unchanged; it never authorizes repeat financial operations.

## Evidence and completion

Pure tests exercise official SDK serialization with inert queued in-memory HttpClient, authentic service with in-memory ports where feasible, exact read requests/budgets, failure safety and unchanged local state. Root audits the transitive pure execution closure. Real SQL composition tests run only in GitHub CI; locally compile them without discovery. Every task gets independent spec/quality review, then a whole-change review. Commit and push, verify exact-head full Offline CI and owned renewal workflow.

Remaining after this component: deliberate original-debt financial mutation and durable receipt contract compatible with immutable proofs and V34/V35, native failed renewal/grace/card confirmation/recovered cash plus journal and delivered callback acceptance, and remaining 5B acceptance matrix items. This component does not close any native financial criterion.
