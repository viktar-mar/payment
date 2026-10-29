# Billing confirmation retry v2 implementation

> Execute through fresh implementers and independent task reviews; do not pause for user checkpoints. Existing authorization includes commit, push and hosted CI, but excludes local Docker and Sandbox.

**Goal:** Support one explicit, proof-backed successor after a fresh Billing PAYMENT v2 synchronous decline, retaining all legacy no-replay safeguards.

**Architecture:** Add an immutable server-confirm attempt chain beside existing browser grants. Select the protocol before secret release; bind one server SDK invocation to its durable attempt and exact failure proof. The browser collects a ConfirmationToken and handles authentication; existing cash reconciliation remains authoritative.

**Tech stack:** Existing Java/Spring hexagonal application, Maven, PostgreSQL migrations, pinned Stripe Java SDK, TypeScript web-component SDK, Vitest, hosted GitHub CI.

**Spec:** `docs/superpowers/specs/2026-10-29-billing-confirmation-retry-v2.md`

## Global constraints

- Work only in `/private/tmp/payment-5a-offline.Dm0qkj`, branch `codex/stripe-billing-5b`. Preserve unrelated untracked files and all saved evidence.
- No local Docker/Testcontainers/database/full application/Cucumber/SQL, Stripe/Sandbox API, financial operations, private environment/keys/archive reads, Python, installation or dependency upgrades.
- Local compile and audited exact unit/MockMvc/owned loopback HTTP fixtures are allowed. Root audits local command scope. Hosted full synthetic CI remains authorized.
- No historical migration edits, weakened assertions, reset legacy latches, raw provider diagnostics, automatic retries, new invoices/PI or clocks/tools detours.
- Implementers do not spawn agents or commit; root owns Git and verification. One implementation writer at a time. Independent review includes both spec compliance and quality.

## Task 1: Durable backend PAYMENT v2 protocol and proof-backed successor

Read the spec as the authority. Implement an end-to-end backend vertical slice, with the smallest coherent interfaces. Main ownership: `backend/src/main/java/dev/genericpayments/platform/subscriptions/{application,adapter}`, `provider/adapter/out/stripe/billing`, a new next-number migration, relevant backend tests, OpenAPI contract artifacts if required. Do not edit SDK files.

Use existing ownership/grant/interaction/reconciliation architecture. Add domain/application models, in/out ports and transaction phases for immutable protocol selection, attempt issue/claim/outcome/read/retry; a persistence adapter; and a Stripe adapter that confirms once and proves only an attributable synchronous decline. Avoid duplicating provider graph validation: extend/reuse existing ownership and snapshot validators with exact bound references. Keep provider I/O outside locks and recheck authority/version on write. Protect v1 session and confirm paths against v2 reservations. Preserve all default/Fake/v1 behavior.

Expose strict CustomerSession-only no-store HTTP routes for fresh v2 session, attempt status, confirm and explicit retry. Use existing route policies/idempotency/error handling. Choose exact route and JSON contracts early, record them in the task report for the SDK task. Server capability is random/unforgeable, stored only as digest or derived from an immutable HMAC descriptor. Session response may contain only the required client secret/capability; durable/status/confirm outputs must not leak secrets. A successor must be usable without reopening the consumed root grant. Operation state is never payment evidence.

Write regressions first, including a positive provider-shaped original HTTP402 decline and same-PI successor; unknown/unrelated failed objects must remain closed. Add SQL integration coverage of root/protocol and predecessor uniqueness/claim/idempotency to existing hosted testing conventions. Preserve exact v1 tests. Audit new local classes for Docker/framework side effects before execution; compile-only is allowed. Report exact commands/results, any native assumptions and wire contract.

Review focus: causal proof, races, persisted parameters, authority expiry, provider operation ownership and current graph, lost ACK, duplicate claims, inability to migrate legacy grants, no automatic financial replay, no sensitive logging.

## Task 2: SDK v2 confirmation, authentication and explicit retry

Consume Task 1's exact wire contract. Main ownership: `frontend/sdk/src/subscriptions`, exports and SDK docs, and small relevant demo integration only if needed for usable flows. Retain Fake and SETUP/v1 compatibility; do not edit backend except a separately ruled interface defect.

Extend strict wire/client types and a distinct v2 payment driver path: fresh session opt-in, Elements ConfirmationToken creation, one server confirm, optional one `handleNextAction`, read-only result retrieval, and explicit proof-backed retry. Preserve old v1 one-shot latches. Integrate manager UI so a verified failure exposes Retry and issues/remounts one successor only on that explicit action; no polling/GET creation. Show truthful pending/unknown versus proven failure, and clear old Elements/secret references after attempt completion or authority loss. Do not derive payment success/access from browser results.

Use focused Vitest regressions first then full SDK tests/typecheck/build; backend integration types must remain strict. Test double click, transport lost ACK with durable decline, unknown outcome, malformed/foreign/expired successor, logout/identity change/visibility, one next-action handling, v1 spent behavior and nonpayment flows. Do not launch development servers or browsers against Stripe.

Review focus: no client authority escalation, one server dispatch, no v2 browser confirm, genuine usable positive retry, SDK/server agreement, cleanup and honest UI.

## Task 3: Final regression, acceptance record and hosted CI

Root coordinates a fresh independent whole-change review. Resolve findings with tests and scoped re-review. Update 5B acceptance/spec/README truthfully with the bounded v2 scope and native NOT_RUN; do not mark 5B accepted. Run audited backend targets and frontend regression, inspect diff/secrets/unchanged historical migrations. Commit/push the branch, inspect CI for that exact head, fix and re-review any related failure, and wait for complete hosted CI. No local Docker or Sandbox, no financial actions.

Review focus: complete vertical path, API/docs compatibility, independent test evidence, preserved default behavior and user boundaries. Final response reports verified functionality, commit/CI link and remaining native limitations without asking the user to continue.
