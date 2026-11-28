# Initial Billing acceptance preparation

This is a narrow implementation of Task 12 of the [5B plan](../plans/2026-10-28-stripe-billing-5b-implementation.md), following the [v2 amendment](2026-10-29-billing-confirmation-retry-v2.md). Prepare one genuine future initial-payment case; do not launch it during this offline checkpoint. Full 5B remains NOT_ACCEPTED and native Billing remains NOT_RUN.

## Scope and outcome

One fresh isolated run owns a new Sandbox Account/Site/Customer, one published EUR20 MONTH subscription Price with zero trial, one consented CREATE and one explicit Pay using SERVER_CONFIRM_V2. The genuine demo/Customer OTP/SDK/Elements path collects a ConfirmationToken and the backend confirms the original Billing PaymentIntent. Read-only observations establish settlement. There is no automatic retry or second financial attempt. Test clocks, decline/retry, manual 3DS, renewal, Portal, refunds, donations and old targets are excluded from this case.

Use the existing reviewed source/topology/bootstrap/CLI/ownership primitives with closed Billing factories. Keep ordinary Fake and 5A unchanged. No generic diagnostic framework, recovered state, Python or new dependencies. The Billing bootstrap generates a fresh distinct grant signing key and enables grant configuration only on its owned backend. The Billing listener uses a fixed supported invoice/subscription/PI event list; it does not expose arbitrary commands or event configuration. Exact provider reads are bound to the newly owned original graph, never searches or old identifiers.

## Execution boundary

The exported factory is inert. An explicit future Billing opt-in plus fresh UUID, exact source revision, approved Account/API binding, canonical daemon and six unique loopback ports is required before runtime dependencies are touched. Default test discovery never selects this case. Dedicated Playwright uses one worker, zero retries and no trace/video/screenshots. Runtime requires separate authorization; this specification is not authorization.

Reuse existing bounded source and process proofs and exact-ID shutdown, retaining volumes. Persist fresh operation identity before dispatch. Keep one total monotonic budget, bounded operations and a separate cleanup allowance; stop on first failure, UNKNOWN, lost dispatch acknowledgement or ownership drift. Cleanup failure cannot turn failure into acceptance. Do not publish a command with missing production adapters or substitute a synthetic fixture as a native result.

## Evidence

Acceptance needs independently obtained and mutually consistent observations: original local subscription/operation/v2 attempt; provider Customer → Subscription → initial Invoice → default InvoicePayment → original PaymentIntent → captured card Charge; local INITIAL confirmed cash/BillingRecord → Payment → journal/events/outbox; and canonical signed callback with real-UTC freshness and event uniqueness. Browser success, invoice paid or callback count alone cannot accept. Public DTOs may be used for their actual fields; scoped read-only SQL against the new owned database supplies the missing original graph and durable confirmation proof. No public API widening or invented PAYMENT_CONFIRM journal kind.

Raw keys, grants, sessions, client secrets, ConfirmationTokens, cookie bodies, provider responses and SQL rows must never enter reports. Keep them in the existing private capability/fixture lifecycle. Emit only fixed phase/code, scoped public local identities and proof booleans/counts. Authorization output contains only the official device URL and current code. No secret screenshots or raw browser console logs.

## Verification and constraints

Local verification is limited to source audits, cached Node unit/contract tests, typecheck and builds. No local Docker, PostgreSQL, SQL, full Java application, Testcontainers, Stripe/Sandbox, new payments/refunds, Python, installs, key/archive/recovery reads. Hosted synthetic GitHub CI remains authorized. Preserve the three pre-existing untracked paths and historical EUR13/DISPATCHING records. Commit/push the reviewed work to the current branch without merging or force pushing.

Docker-free injected tests must prove inert imports, configuration separation, strict ownership/budgets, fixed listener events, one CREATE/one confirm, no retry/clock path, failure-stop and cleanup, full receipt rejection, privacy and default discovery exclusion. Test fakes are explicitly synthetic. Independent task and final reviews precede publication; exact-head hosted CI verifies regression. Native outcomes remain NOT_RUN until a separately authorized run.
