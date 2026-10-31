# Owned invoice settlement diagnostic repair

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. One coupled task, TDD, independent task review and final current-change review.

**Goal:** Make the existing owned settlement refusal identify its first rejecting predicate without changing any financial or ownership acceptance rule.
**Architecture:** Keep tentative per-invoice provenance separate from committed call-local OwnedActionFailure. Instrument existing booleans once, without throwing early; commit only at either existing failed settlement guard. Extend the existing exact closed reporting path, not the wire shape or authority.
**Tech Stack:** Java25, stripe-java34.0.0/API2026-09-30.endive; cached javac/Jupiter6; injected Node25 tests.
**Spec:** docs/superpowers/specs/2026-10-30-billing-first-payment-scenario.md; docs/superpowers/specs/2026-10-29-billing-clock-subscriptions.md; docs/superpowers/specs/2026-10-29-billing-clock-confirmation.md. This plan authorizes only diagnostic refinement of their existing strict guards.

## Evidence and scope decision

Run0cdd729d-2429-4f38-9ef4-61c2e46978f4 at66b610197bb739453a652f2118e607ac0ad93624 stopped with PREPARE_SERVER_ACTION_GRAPH_INVOICE_SETTLEMENT. Its client CREATE dispatch has no acknowledgement; no confirm marker. The safe report proves neither the failed field nor provider outcome. Read-only source/test reconnaissance and independent architecture gate establish diagnostic conflation. The six existing settlement regression variants deliberately share this label. No financial acceptance correction is evidence-backed: this task fixes diagnostic precision only and must not claim native cause/resolution.

## Global Constraints

- Existing isolated codex/stripe-billing-5b worktree only, base66b610197bb739453a652f2118e607ac0ad93624. No Docker/daemon, Stripe/Sandbox/network, DB/SQL/Spring/context/server/listener/browser, Python, installs, raw native/private/env/key/CLI/archive access. All executed data is artificial and every external test port is inert/injected.
- Never replay, reset, adopt or reconcile CREATE/DISPATCHING. No new financial operations. Preserve all historical/native evidence, resources and data. No push/CI/native run this task; root may make one local commit after reviews and checks.
- Acceptance/rejection, exceptions, original short-circuit/getter order, provider GET ordering/count/budget, CASH capture/reverse proof, all receipt/clock/customer/line/catalog/amount/authority/fence/root checks and ordinary behavior remain unchanged. Nullable money/lists stay strict; no null-to-zero/empty conversion.
- Diagnostic values are fixed enum/explicit allowlist members only; no values, identifiers, counts, presence booleans, raw text, exception inspection, unchecked prefix acceptance or new response field. No ThreadLocal, new framework, parser or recovery authority.
- Tentative provenance is per invoice and per action call. Commit only when an existing normalized OPEN-or-CASH or later action OPEN guard actually rejects. Earlier/later unrelated exceptions stay generic; first committed failure stays first. Do not annotate successful normalization, failed paid ZERO alternatives, capture exceptions or later ownership failures.
- Keep current strict status-400 code+stage envelope, exact parsing, first-failure/cleanup semantics, single terminal diagnostic and existing160-character line limit. Old coarse settlement label remains supported.
- Pure local tests only explicit audited Jupiter selectors with automatic registration disabled; cached javac -proc:none --release25, no test/package wildcard discovery or Maven test/verify. Exact injected Node test files only. Current source must be freshly compiled; stale target classes are not proof.

## Fixed reason vocabulary

New exact stages use prefix ACTION_GRAPH_INVOICE_SETTLEMENT_ with only these suffixes:

PRORATION_FLAG, PRORATION_KIND, COLLECTION_METHOD,
PAID_OFF_STRIPE, OVERPAID, STARTING_BALANCE, ENDING_BALANCE, PRE_CREDIT_NOTES, POST_CREDIT_NOTES,
INVOICE_DISCOUNTS, INVOICE_DISCOUNT_AMOUNTS, INVOICE_PRETAX_CREDITS, LINE_DISCOUNT_AMOUNTS, LINE_PRETAX_CREDITS,
TOTAL, SUBTOTAL, LINE_AMOUNT,
OPEN_DUE_POSITIVE, OPEN_DUE_CATALOG, OPEN_PAID, OPEN_REMAINING,
OPEN_ALLOCATION_STATUS, OPEN_ALLOCATION_PAYMENT, OPEN_ALLOCATION_TYPE, OPEN_ALLOCATION_INTENT,
SNAPSHOT_UNSUPPORTED, ACTION_OPEN_CASH, ACTION_OPEN.

One field category covers missing, null and nonzero/nonempty: do not expose which value/presence was seen. SNAPSHOT_UNSUPPORTED is intentionally coarse for unsupported paid/capture outcomes and normalized non-OPEN/non-CASH kinds. Do not instrument capture internals in this patch. Shared plain-predicate failures retain their exact common-field reason for OPEN and paid invoices; only paid CASH eligibility/capture-return-null retains the coarse snapshot fallback. ACTION_OPEN_CASH is committed only at the later action guard, after successful CASH normalization. Unknown/null statuses retain the existing generic exception outcome. Draft/uncollectible normalization remains unchanged; later native-status rejection is outside settlement diagnostics.

## Review Focus

- No weakened guard or extra IO hidden in diagnostic refactoring, including non-owned snapshots.
- Preserve evaluation of all six scalar getter arguments before the current zeros helper; list/amount/allocation predicates retain source order and null short circuits.
- No false provenance from a failed alternative, successful normalization, unrelated later failure, concurrent/reentrant call or repeated failure.
- Every new literal traverses adapter, scenario, Handler, Node client/formatter/parser and reporter; unknown/forged/escaped/extra/duplicate data stays refused.
- Existing valid OPEN action, ordinary strict reads, confirmed CASH and durable one-root/consumed authority retain observable output and request traces.

## Task 1: Refine existing settlement provenance end to end

**Files:**
- Modify backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingSnapshotMapper.java and StripeBillingClockActionAdapter.java.
- Modify backend/src/test/java/dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentScenario.java and OwnedBillingFirstPaymentRuntime.java exact diagnostic projections only.
- Tests in backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeBillingOwnedConfirmationSdkTest.java and StripeBillingClockSnapshotTest.java; optionally a focused pure test in same billing package if necessary to characterize an otherwise unreachable defensive guard. Existing OwnedBillingFirstPaymentRuntimeTest.java covers the private Handler in memory.
- Modify infrastructure/stripe-billing-first-payment-runtime.mjs and its existing infrastructure/stripe-billing-first-payment.test.mjs; extend examples/demo-site/tests/support/stripe-billing-first-payment.test.ts reporter coverage. Reporter production change only if genuinely required by existing closed parser.
- Clarify docs/local-development.md diagnostic meaning/evidence limits. No changes to public routes/protocol/options/config/dependencies or old reports.

- [ ] Read copied recon/design/tooling notes and audit complete selected test/helper closure. Save exact selector/compilation commands before running; do not infer safety from class names. Fresh output precedes cached classes, exclude auto-registration and native test discovery.
- [ ] Write meaningful assertions against real mapper/owned adapter with inert official SDK JSON; capture genuine assertion RED against unchanged production before implementation. Add fixed transport-label REDs if useful. Compiler/setup failure is not RED.
- [ ] For each six-field zero check: missing, null, nonzero (positive and representative negative) retain refusal and identify the exact fixed field. For five lists: missing/null/nonempty. Totals/subtotal/line mismatches retain exact preceding guards; use literal expectations.
- [ ] Cover proration/collection, positive/catalog due, paid/remaining, allocation status/payment presence/type/intent with exact request order/count and no root/POST; cover two-failure precedence and empty allocation behavior without inventing new attribution. Existing downstream allocation guard retains its current meaning.
- [ ] Preserve valid original OPEN binding/root/material/preconfirm and ordinary/owned confirmed CASH. Paid normalization or capture failure remains coarse snapshot fallback; capture exceptions remain generic. A successfully normalized CASH reaching action OPEN gets ACTION_OPEN_CASH, not a tentative OPEN-field reason. No failed alternative leaks a reason into a success or unrelated error.
- [ ] Implement a tiny nonthrowing trace of the actual existing expressions; no duplicate diagnostic re-evaluation, early throw, new provider read or global state. Use fixed exhaustive mapping and exact allowlists. Missing/native values never leave the transient existing SDK objects.
- [ ] Retain first failure through success/duplicate refusal and test sequential/concurrent/reentrant isolation with new settlement reasons. Preserve wrong-authority/no-IO/consumed-root/negative amount/currency/receipt/clock/line/PI/reverse/Charge attacks.
- [ ] Extend Java scenario/MemoryExchange Handler and injected Node owner/client/parser/reporter tests for every exact new stage, ordinary fallback, forged suffixes, newline/secret-looking/escaped/duplicate/extra fields, one terminal diagnostic and <=160 chars. No real handler listener or server/context launch.
- [ ] Capture focused GREEN, then run one audited related pure Java set including mapper/owned adapter/runtime Handler tests and the two exact Node test files. No Admin/full native regression for unchanged UI; document selected compilation/coverage limits accurately.
- [ ] Update local-development wording: labels identify the predicate, not native values or past cause. This is a diagnostic-only source fix; initial-payment/full5B remain NOT_ACCEPTED and CREATE remains non-retryable. Preserve old coarse compatibility label.
- [ ] Save RED/GREEN/runner/source hashes/self-review/full report in this plan's workspace; no subagents, commits or push by implementer. Root performs independent task/final reviews, handles scoped fixes and may create one local commit. Preserve all evidence.

## Completion

Task and final current-change reviews pass; root verifies frozen source hashes, all recorded outcomes, whitespace and staged scope. No native success, exact historical predicate, permanent fix for native invoice semantics, fresh backend full-build or hosted CI claim follows from this offline patch. Existing pending ledger remains untouched. The final report must make this limitation explicit.
