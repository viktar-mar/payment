# Owned unpaid allocation contract implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. One coupled task; genuine RED/GREEN; independent task and final current-change reviews.

**Goal:** Permit Stripe's documented unfinalized allocation paid amount in the proved owned-original first-payment graph without treating null as cash or numeric zero.
**Architecture:** Change only the shared paymentGraph paid-amount predicate for typed original, positive INITIAL invoices. Action, material and preconfirm continue to use the same complete ownership/invoice/PI/reverse proof. Ordinary action and CASH retain strict numeric evidence.
**Tech Stack:** Java25, stripe-java34.0.0/API2026-09-30.endive; cached explicit Jupiter6 launcher; existing Node tests, no new dependencies.
**Spec:** Latest user-authorized offline correction, the contract addendum below, docs/superpowers/specs/2026-10-29-billing-clock-confirmation.md and docs/superpowers/specs/2026-10-29-billing-clock-subscriptions.md. The addendum supersedes only their prior explicit-zero allocation requirement within the bounded owned-original graph.

## Contract addendum and evidence

The consumed run aabe30d7-d499-46bf-bb56-4f592ed50aa8 on947adfec3c1a9fa7a05ac204e792dddc9175ecda stopped with PREPARE_SERVER_ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_PAID_UNAVAILABLE. Safe report establishes a reached SDK getter returned null; it does not establish wire omission versus JSON null. CREATE dispatch exists, ACK and CONFIRM do not; runtime status remains PENDING; all7containers independently stopped. Its CREATE is uncertain, consumed and nonretryable.

Official pinned source: https://raw.githubusercontent.com/stripe/stripe-java/v34.0.0/src/main/java/com/stripe/model/InvoicePayment.java documents amount_paid as null until payment is paid; https://raw.githubusercontent.com/stripe/stripe-java/v34.0.0/src/main/java/com/stripe/ApiVersion.java pins2026-09-30.endive. Current official reference https://docs.stripe.com/api/invoice-payment/object corroborates nullability. These establish null semantics, not conforming wire omission. Typed SDK null covers both representations; it must never be converted to numeric zero or paid evidence.

Ruling: accept typed null or existing numeric zero only for r.original!=null, INITIAL invoice and positive due inside the actual open-invoice action graph. Keep nonzero refusal and all other proofs unchanged. Cost: this is an intentional narrowly scoped admission correction; malformed or omitted source fields that deserialize identically to documented null cannot be distinguished by this typed boundary, so complete independent unpaid/ownership proof remains mandatory. Do not claim native acceptance.

## Global Constraints

- Work only in existing isolated codex/stripe-billing-5b worktree; base947adfec3c1a9fa7a05ac204e792dddc9175ecda. No Docker/daemon/Stripe API/Sandbox, financial operations, DB/SQL/server/Spring context/browser/Playwright/Maven default suite, Python, installation, key/env/private payload/archive access. Official read-only contract research is complete; implementation/tests are offline.
- Preserve all prior/current native reports, keys, data and workspaces. Do not replay/reset/adopt/reconcile CREATE/DISPATCHING. Root may make one reviewed local commit; no push/CI/native run is part of this task.
- Do not change zero helper, global nullable-money handling, ordinary create/change/resume/recovery, OPEN descriptive normalization, CASH/capture proof, off-Stripe policy, SDK/API/dependencies, schemas, diagnostic enum/allowlists/format, durable authority or request/dispatch budgets.
- Required graph: actual invoice open, typed original INITIAL positive catalog due, invoice paid explicitly0/remaining==due; sole default open allocation requested==due; exact payment type/ref/owner/currency/mode/amount; PI received explicitly0, automatic capture+confirmation, requires_payment_method, no latest charge; exact sole reverse allocation; original clock/receipt/period/line and secret bounds. No root or confirm POST if any required proof fails.
- Preserve sequential getters, first-failure latch, actual second getPayment/type read, generic throwing-getter handling and existing closed public errors. Never base acceptance on diagnostic carrier presence.
- All Java execution uses fresh explicit javac --release25 -proc:none -implicit:none -sourcepath '' and exactly three audited inert classes: StripeBillingOwnedConfirmationSdkTest, StripeBillingClockSnapshotTest, billing.StripeBillingSettlementEvaluationTest. All five launcher auto-registration flags OFF, extension autodetection/parallel OFF. Do not discover packages or run HTTP-loopback fixtures/application/runtime tests. Cached unchanged transitive target support is disclosed, not full fresh backend verification.

## Review Focus

- Typed null can issue the identical bound action/root/material/one inert confirm only after the complete unpaid original graph, and never cash.
- Missing and JSON-null numeric source representations share SDK null but no claim of wire omission or actual previous payload follows.
- Supplied positive/negative values and absent independent invoice/PI money facts still refuse before root/POST.
- Ordinary/non-original, CASH, canceled/default/requested/payment/type/reverse/identity guards stay strict; malformed/throwing fields never gain permission.
- Diagnostic labels remain compatible, source evaluation order and first-cause isolation remain unchanged outside corrected null branch.

### Task 1: Correct the bounded owned unpaid allocation policy and its behavioral regressions

**Files:**
- Modify backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingSnapshotMapper.java, paymentGraph paid predicate and a concise contract comment only.
- Modify backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeBillingOwnedConfirmationSdkTest.java; change the shared inert fixture only if a focused behavioral test cannot otherwise express documented null (do not globally change defaults).
- Modify docs/superpowers/specs/2026-10-29-billing-clock-confirmation.md, docs/superpowers/specs/2026-10-29-billing-clock-subscriptions.md and docs/local-development.md only current allocation policy paragraphs. Prior implementation plans are history and stay untouched.

**Interfaces:** consumes existing typed OwnedInitial/read.original, invoice kind/due and exact full paymentGraph. Produces the existing PaymentAction and binding with unchanged schema, requests and authority. No new API, diagnostic or dependency.

- [ ] Audit source/dependency/test closure. Create a fresh task-only /private/tmp build/runner. The old /private/tmp/payment-invoice-settlement-task1/run.sh may be read as reference only; never execute/overwrite it. Copy final source lists, commands, RED/GREEN summaries and hashes into this plan's workspace. Do not run a class until its complete selector/extension/fixture closure is confirmed inert.
- [ ] Before production edits, add positive RED covering allocation amount_paid omitted/JSON-null/0 across owned action -> same binding/root -> material -> preconfirm/one inert confirm -> full independent CASH with explicit full paid value. Cross with existing flat/indicator representations where needed without duplicating the whole lifecycle test. Assert the complete real request sequence, root count/binding and one confirm POST. Baseline code must fail at AMOUNT_PAID_UNAVAILABLE for null cases, not compilation/setup/linkage.
- [ ] Preserve ordinary missing/null refusal and nonzero owned refusal. Amend outdated owned-null negative rows to independent real invalid data instead of deleting guard tests: +1/-1 allocation paid; missing/mismatched requested; canceled/nondefault; incorrect PI received/amount/customer/currency/status/capture/confirmation/latest charge; missing reverse association. Add null-allocation variants demonstrating later PI/reverse guards still stop root/material/preconfirm with no POST. Retain correct earlier normalization precedence and first-failure latch expectations.
- [ ] Prove paid allocation amount_paid omitted/null is not accepted as CASH; explicit full paid/CASH path remains green. Preserve existing ordinary getter-order/throwing-getter tests in StripeBillingSettlementEvaluationTest. Assert material and preconfirm use the same corrected rule, never diagnostic-only permission.
- [ ] Observe expected RED, then implement the minimal predicate scoped by typed original, INITIAL kind and positive due. Do not mutate/synthesize amount_paid, do not change zeros(), and preserve all surrounding comparisons/getter order. Comment with documented unfinalized meaning and numeric-cash boundary.
- [ ] Run focused GREEN then one complete approved three-class pure regression, fresh compile of changed mapper/billing package and all selected test/fixture sources. Use all five launcher registration OFF flags, no dynamic discovery. Run only existing named Node pure suites: /opt/homebrew/bin/node --test --test-timeout=60000 --test-concurrency=1 infrastructure/stripe-billing-first-payment.test.mjs examples/demo-site/tests/support/stripe-billing-first-payment.test.ts. Report exact counts/failures/skips and cached-transitive/full-Maven/native limitations.
- [ ] Update current docs/specs: nullable allocation rule only for owned original open INITIAL positive invoice, explicit zero compatibility, nonzero refused, all invoice/PI/reverse/full CASH proof retained; ordinary policy unchanged. Retain closed AMOUNT_PAID_UNAVAILABLE label compatibility without claiming it still rejects documented owned null. Historical run remains NOT_ACCEPTED and uncertain CREATE not retryable. Link official pinned source/current reference; distinguish getter-null from unknown wire representation.
- [ ] Self-review, git diff --check, exact artifact hashes and full task-1-report.md with test commands/results and limitations. No implementer commits, push or subagents. Root runs independent task and final current-change review gates and one local commit only after fresh verification. Preserve evidence.

## Completion

No open Important/Critical review findings. The correction is verified offline only, not native first-payment or full5B acceptance. No new Sandbox authority follows. User is informed of scoped admission decision, evidence limits, local commit and no Docker/Stripe operations.
