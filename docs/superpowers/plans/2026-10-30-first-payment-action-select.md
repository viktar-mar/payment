# Owned first-payment action-selection diagnosis implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute continuously; user already requested implementation with subagents, tests and independent review.

**Goal:** Isolate the stopped ACTION_SELECT boundary safely, without guessing at a financial-evidence compatibility change or redispatching a consumed invocation.

**Architecture:** Preserve the existing receipt-bound adapter and exact financial proof. Retain a closed first-failure checkpoint in the adapter; the private scenario maps it into its existing exact prepare-failure envelope. Node and reporter retain only the same finite checkpoint, with unchanged failure/cleanup semantics.

**Tech Stack:** Java25/stripe-java34.0.0, cached offline Maven compile and exact pure Jupiter; Node/TypeScript inert tests, no new dependencies.

**Spec:** docs/superpowers/specs/2026-10-30-billing-first-payment-scenario.md and docs/superpowers/specs/2026-10-29-billing-clock-confirmation.md. User request: offline ACTION_SELECT investigation/fix, subagents/tests/review, no Docker/Sandbox/new financial operations or DISPATCHING retry.

## Global Constraints

- No local Docker, DB/SQL, Spring/application context, Testcontainers, browser, network, Stripe/provider/financial calls, Python, installation or private environment/key/archive/recovery reads.
- No old or current DISPATCHING retry/adoption/reset. Preserve every native artifact, volume and outcome uncertainty.
- Only cached offline compilation and exact audited standalone pure tests locally. Disable engine/listener/extension auto-registration; no package/classpath discovery.
- Preserve current operation order, calls, locks, authority, financial/ownership/amount/clock/CASH checks, deadlines, read/dispatch budgets, DTO fields, permanent stop, zero retries and runtime export inventory. No mapper/nullable-money/legacy-field compatibility change without authoritative evidence.
- No exception properties/messages/types/causes, SDK payloads, secrets, identifiers, raw operational logs or extra provider reads in diagnostics.
- Existing Java prepare envelope is exactly code plus stage, exact authenticated status400 only; Node whitelist and reporter remain finite, one safe line after close.
- Root owns Git. No push/hosted CI, merge, cleanup or new native attempt in this offline task.

## Evidence and limitation

Run d148a8b6-c770-42fb-a986-8744c2f43995 at b1858c9 stopped at PREPARE_SERVER_ACTION_SELECT, cleanup confirmed. That block includes scope resolution, two locked checks, verification, READY/graph/READY, root issuance and binding lookup. Native root cause and provider outcome are unknown. Independent source recon found no proven clock/receipt defect; null amount_paid and omitted legacy line.subscription are hypotheses only, not grounds to weaken proof. An inert failing shape would prove rejection, not validity of the provider shape or the native cause.

## Review Focus

- Actual adapter exceptions identify only their own fixed boundary and retain first failure through reuse/concurrency.
- Added observability never changes success/null results, exception identity or effect order.
- Scenario failure mapping cannot read exception details and always aborts; unavailable/unknown phase keeps safe fallback.
- New Java stages reach Node final reporter once; malformed/foreign diagnostic values still fail closed.
- Wire success/confirm/observe, exact export inventory and original graph/CASH guards stay unchanged.

### Task 1: retain finite adapter action-selection checkpoints

Files: modify StripeBillingClockActionAdapter.java and StripeBillingOwnedConfirmationSdkTest.java only.
Interfaces: preserve existing constructor and nextAction(Scope); add a read-only selectionFailureStage() accessor returning null before failure or one of ACTION_AUTHORITY, ACTION_VERIFICATION, ACTION_GRAPH, ACTION_RESERVATION.
- [x] Write inert behavioral tests for actual adapter failures at each boundary, sticky first failure, unchanged exception identity/no exception inspection, success/no-root and duplicate reserved-root behavior with no new I/O.
- [x] Compile with cached offline Maven -DskipTests test-compile; execute exact pure SDK test selector with explicit Jupiter engine. Capture RED before adapter edits.
- [x] Add minimal in-memory first-failure retention and fixed checkpoint boundaries around existing work. No observers/plugins/new runtime types or financial changes.
- [x] Run exact SDK class plus audited related pure clock/snapshot/contract classes; capture GREEN and self-review.
- [x] Independent task spec/quality review on frozen diff before Task2.

### Task 2: preserve finite detail through existing private prepare diagnostics

Files: OwnedBillingFirstPaymentScenario.java, OwnedBillingFirstPaymentRuntime.java, OwnedBillingFirstPaymentRuntimeTest.java, stripe-billing-first-payment-runtime.mjs, stripe-billing-first-payment.test.mjs, support/stripe-billing-first-payment.test.ts, docs/local-development.md; add small pure helper tests only if needed for actual mapping without invoking prepare financial steps.
Interfaces: existing exact status400 {code:INITIAL_PAYMENT_STOPPED,stage:<allowlisted>} and fixed PREPARE_SERVER_<stage> reporter; no new fields or routes. Add Task1 four stages plus ACTION_SCOPE, ACTION_RESULT, ACTION_BINDING. Keep ACTION_SELECT as fallback.
- [x] Write actual closed-stage mapping/Handler and injected Node/reporter RED tests for every new stage, fallback, first-error retention, hostile exception accessors, no extra calls and duplicate denial.
- [x] Set scenario scope/result/binding checkpoints around existing steps; retain adapter detail only for its ACTION_SELECT failure using an explicit allowlist; do not inspect thrown errors.
- [x] Extend Java/Node finite allowlists together; preserve body limits, exact shape/duplicate-key validation, deadlines, first-failure latch and cleanup gating. Update documentation honestly: root cause and native acceptance remain unproved.
- [x] Run exact pure Java selectors, focused and related inert Node/support tests and cached TypeScript check as appropriate; no ordinary Maven verify or native Playwright discovery.
- [x] Independent task review, then whole-change review; fix findings via implementer and verify before one local commit. No push or native run.
