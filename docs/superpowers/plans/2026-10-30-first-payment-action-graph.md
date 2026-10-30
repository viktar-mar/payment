# Initial subscription ACTION_GRAPH offline correction

Spec: docs/superpowers/specs/2026-10-30-billing-first-payment-scenario.md and docs/superpowers/specs/2026-10-29-billing-clock-confirmation.md.

## Evidence and decision

Run 6f723740-cd90-448f-9b7d-91df37ff12f1 at e4e707c stopped at PREPARE_SERVER_ACTION_GRAPH, cleanup confirmed. The public fixture journal ends at CREATE DISPATCHING without ACK/confirm. This proves a harness boundary only, not a provider or backend operation outcome. Read-only source reconnaissance confirms that nullable allocation amount_paid, invoice ending_balance and missing legacy line.subscription all fail current checks; handcrafted fixtures do not cover these variants. Cached stripe-java34/API2026-09-30.endive represents them but supplies no authoritative valid-state semantics. None is established as the native cause.

The proven defect to correct now is loss of specific guard failure provenance. Preserve financial validation and add only three closed reason boundaries to the existing prepare diagnostic. Do not guess a null-to-zero policy or remove ownership checks. Native initial payment and full5B remain NOT_ACCEPTED.

## Global Constraints

- Existing isolated codex/stripe-billing-5b worktree only. No installation, Python, Docker/daemon, database/SQL, application context/server/browser, network, Stripe or Sandbox. No private configuration, keys, CLI state, archives or raw native logs. No payment/refund or native retry.
- Preserve all reports/data and every uncertain CREATE/DISPATCHING; no replay/reset/adoption/reconciliation. Only safe current-run report and ledger-summary are evidence.
- Keep all amount/cash/clock/account/customer/subscription/item/price/period/reverse-allocation/fence/authority checks, request/read/time budgets, DTO shape, production registration and default Fake unchanged.
- Diagnostic is call-local, closed, in memory, and read only on failure. No exception/property/message/stack/body/value inspection and no extra provider read. No arbitrary caller string becomes a diagnostic.
- Preserve existing public mapper entry and generic ACTION_GRAPH fallback. Add only ACTION_GRAPH_LINE_ASSOCIATION, ACTION_GRAPH_INVOICE_SETTLEMENT, ACTION_GRAPH_PAYMENT_ALLOCATION. These identify failed guards, never actual field values or native cause.
- Local tests only audited explicit pure JUnit/Jupiter selectors and injected Node tests via cached runtimes. No wildcard discovery/Maven test/verify. Optional cached offline compile only. No pushes or CI this turn. Root alone commits after reviews; preserve ignored evidence.

## Review Focus

No financial rule weakening; diagnostics tied to actual failed guards, not stale markers or untrusted exceptions. Reentrancy/concurrency and first failure remain correct. End-to-end exact allowlists, bounded envelope, parser/reporter and secret suppression. Tests reproduce null/omitted variants through official SDK JSON parsing. Native uncertainty remains explicit.

## Task 1: Narrow owned graph guard provenance and regression

Implementer reads this entire plan. Modify StripeBillingSnapshotMapper, StripeBillingClockConfirmationSupport and StripeBillingClockActionAdapter, plus existing pure StripeBillingOwnedConfirmationSdkTest. Add a closed typed per-call signal for the owned action read only, with exactly three failures: LINE_ASSOCIATION (existing compound top-level/parent subscription line guard), INVOICE_SETTLEMENT (owned initial OPEN requirement after snapshot normalization), PAYMENT_ALLOCATION (existing open/default/requested/zero-paid/type guard before PI read). Keep regular mapper and observation paths unchanged. Existing owned API delegates without diagnostics. Keep typed recorder internal to action call; no global/thread-local state or exception-derived authority. Adapter may refine ACTION_GRAPH only from this trusted call-local signal on that failure. Failures elsewhere preserve generic ACTION_GRAPH. Add no extra runtime classes to the test-source export closure.

TDD: first write pure inert SDK JSON tests expecting the three codes and unchanged rejection/no root/no POST. At least explicit null and omission of allocation amount_paid, null and omitted invoice ending_balance, omitted/null/foreign legacy or nested line subscription, and generic client/ready/read failures. Preserve successful zero/both-link graph, known amount/ownership attacks, sticky first failure, duplicate refusal and concurrent calls. Demonstrate genuine assertion RED, then GREEN; no dependency/compile error counts as RED. Run exact related pure selectors once after focused GREEN. Record commands, counts and source hashes in task report. No raw failure bodies printed. Self-review, no commit/subagents.

## Task 2: Existing diagnostic transport and operator documentation

Fresh implementer after Task1 review. Update OwnedBillingFirstPaymentScenario.actionFailureStage, Runtime prepare-stage allowlist, Java RuntimeTest, Node private client allowlist and exact Node/reporter tests so all three labels pass only the existing code-plus-stage envelope and once-only final formatter/reporter. Unknown/forged stages and extra fields remain rejected. DTO has no new fields. Add regressions before production edits and preserve exact positive/negative contracts. Audit each selected test import/runner as inert before execution.

Update docs/local-development.md with the three labels, what each failed guard means, the source-only reproductions and the remaining uncertainty. Do not claim native fix/acceptance or suggest retry. Local verification uses explicit audited Jupiter selectors and injected Node test files; TypeScript only if relevant. Preserve compile/export compatibility and no new dependencies. Record evidence and self-review; no commit/subagents.

## Completion

Independent spec+quality review after each task, independent whole-current-change review at the end. Resolve substantive findings and re-review them. Root verifies final diff/evidence, checks whitespace, and makes one local commit. Do not push, start CI or run native work. Report proven correction, test counts, native uncertainty and protected DISPATCHING.
