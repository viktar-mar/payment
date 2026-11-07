# Original confirmed method binding

## Intent

Continue the original owned failed-renewal browser path after committed first-only browser consumption and before the existing METHOD writer. Replace the hosted fixture's handwritten succeeded SetupIntent/card GET and direct proof binding with an explicitly constructed server adapter and authenticated atomic binding transaction. This is an offline source deliverable, not native acceptance or a new financial operation.

## Constraints and decisions

- Existing Java25/Maven, stripe-java34.0.0, pinned API2026-09-30.endive, hexagonal application/store/provider boundaries; no new dependency or migration.
- User's nonstop execution and subagents/tests/independent review/publication/CI instructions persist. Existing linked `codex/billing-wait-snapshot` is used; no PR, merge or cleanup. Preserve reports/worktree.
- No local Docker, SQL/Spring/application/browser/server/socket, Stripe/Sandbox/provider request, financial operation, old operation/DISPATCHING reuse, key/archive reading, Python or installation. Root may run audited cached explicit pure tests/compile and remote GitHub CI; workers write/read source only.
- Controller decision: bound this slice to independent confirmed-method reads and atomic binding. METHOD, lifecycle observation, recovery payment, runtime registration, route/bean/native/time/grace/tax/decline/callback work remains separate.
- Controller decision: 30-second immutable invocation budget across all caller work, real and injected monotonic limits plus UTC; at most two logical official SDK retrieves, each at most two seconds. Zero application/SDK retries. Existing transport may transparently recover idempotent GET inside the bound; no single-wire-attempt claim.
- Controller decision: a fresh call may inspect an unbound original again after a read-only failure; this does not mint browser confirmation permission or repeat a write. Already-bound proofs are refused before any GET and are never adopted/replaced. No durable new read claim/retry framework is added for these GETs.

## Application and persistence

`BindingPreparationContext(original,setupProof,setupStep)` is descriptive and redacted. `original` is the existing `SetupPreparationContext`; the proof must be the exact original unbound proof, the SETUP step its canonical ACK/v2. It grants no reusable authority.

`inspectBrowserBindingPreparation(reservation,originalContext)` enters a fresh short transaction, reuses private original SETUP management/key/debt/account/binding/sample inspection, requires the original unbound SETUP proof, correct canonical ACK/v2/window/chronology, committed original browser consumption, and exact original live enrollment after locks. It requires no METHOD row or ACK. Final management revalidation uses current UTC, preserving exact expiry semantics.

`bindBrowserMethod(expected,originalContext,method)` enters a separate fresh transaction, redoes the same inspection and exact context equality, validates the pm reference, conditionally binds the exact still-unbound proof through the caller-owned store transaction, then revalidates original management authority and exact enrollment after all blocking work. Any failure rolls the binding back. No independently committed ordinary `bindMethod` call, lifecycle save, provider call, stop or new provider row occurs here. Return the actual bound proof only after transaction success; no misleading post-commit timeout/authority check.

New store methods default-deny. JDBC locks the original operation/enrollment/proof through existing V35/V36 protections, checks the committed `owned_method_browser_permit`, and performs a conditional update matching all immutable proof identity columns with `method_reference is null`; exactly one row must change. The existing SQL guards remain authoritative. Existing ordinary proof binding/non-browser behavior is unchanged. Missing/partial/foreign/expired enrollment, uncommitted consumption, wrong key/debt, bound proof, malformed ACK and state drift refuse.

## Independent provider adapter

`StripeBillingOwnedFailedRenewalBindingAdapter.bind(reservation,originalContext)` obtains fresh binding preparation before credentials/client resolution; repeats full authenticated context comparisons before each SDK read and the binding handoff. No provider I/O runs in a transaction or a worker with store/service capabilities.

Read original SetupIntent only, using original account credentials and pinned API. Reuse the ordinary setup validator for object/customer/sandbox/usage/card types/original metadata/created window/exact reference; require succeeded and a bounded `pm_` method ID. Independently retrieve exactly that method and require matching ID, payment_method object, livemode false, original customer, type card, nonnull card details. Never trust browser method IDs, client secrets or a subscription snapshot as binding evidence.

Each worker checks original session expiry, interruption and immutable invocation/per-call deadlines at SDK entry, with connect/read timeouts at most two seconds, no idempotency key and max retries zero. Use isolated private read-budget mechanics; do not generalize or change the existing SETUP/METHOD write helper. Cancellation bounds local waiting, not remote execution. Reinspect and check remaining budget before the atomic service binding; it may commit after the final valid check and must return the genuine committed proof. Later METHOD independently authenticates again. All errors use fixed cause-free `OWNED_METHOD_BINDING_UNAVAILABLE`/409/`Owned method binding unavailable`; no raw provider data, secret, claim stop or successful lifecycle fact.

## Acceptance and evidence

Task/service pure tests use actual authority/service with explicit original unbound SETUP ACK and committed browser receipt semantics; no fabricated successful binding context. Adapter tests use official SDK with an inert in-memory transport. Hosted sources use genuine existing browser consumption, real service/JDBC binding, actual METHOD adapter, independent lifecycle save and observer/FactService. Positive paths may not seed bound proofs or successful lifecycle/ACK evidence. Non-browser fixture/manual path remains intact.

Cover malformed/foreign/uncommitted inputs, no METHOD requirement, refusal before reads, exact two-read options/order, drift between reads, wrong SetupIntent/card, expiry/revocation before worker and during the final transaction, second live session with original key, conditional-update competition and rollback, no financial changes, successful binding followed by independent METHOD and genuine lifecycle-save rollback retaining ACK. Compile hosted sources only locally; execute them only in exact-head GitHub full CI plus dedicated renewal workflow.

Published baseline `5297e40e10364b150d6f60971246073d7cab0725` full CI42405505802 is SUCCESS: 5506 backend tests, zero failures/errors, one existing skip, all127 failed-renewal composition cases; dedicated42405505688 SUCCESS379/379. This baseline covers the previous manual binding fixture, not the adapter in this design. New hosted proof requires its own published-source CI. Native binding/method/recovery stays NOT_RUN; full5B NOT_ACCEPTED.

Official API references for the existing retrieve shapes: https://docs.stripe.com/api/setup_intents/retrieve and https://docs.stripe.com/api/payment_methods/retrieve. Current documentation examples use a later API revision; this slice preserves the repository's pinned revision and validates cached SDK shapes locally, with native interoperability outstanding.
