# CREATE_ACK → CONFIRM Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Complete both tasks continuously; source-only workers, controller-run inert tests.

**Goal:** Preserve and safely publish the first browser failure between CREATE_ACK and CONFIRM.
**Architecture:** Existing WeakMap provenance, first-failure latch in the held browser, dedicated closed terminal protocol.
**Tech Stack:** TypeScript, Node built-in tests, cached TypeScript compiler.
**Spec:** docs/superpowers/specs/2026-11-15-create-confirm-diagnostic-design.md

## Global Constraints

Offline source work and inert memory tests only. No Docker, native browser, Stripe, SQL execution, installs, Python, archives, keys or old operations. No retries, longer budgets, weaker guards, synthetic Pay or dispatch-authority changes. First trusted controller failure wins; no raw error properties or payloads. Preserve LISTENER diagnostics, reporter provenance, cleanup and immutable receipts. Root alone executes audited tests; workers edit/read source only, no subagents or Git mutations.

## Review Focus

- A route failure before manual Pay must preserve its bridge checkpoint despite synchronous abort.
- A readiness or card-fill failure must never authorize CONFIRM.
- A page STOPPED or crash must record controller context, never page-supplied text.
- Cleanup failures must not replace the initiating failure or claim cleanup success.
- Terminal serialization must reject forged/inherited/accessor fields and stage/checkpoint mismatches.

### Task 1: Preserve held-browser first failure

**Files:** examples/demo-site/tests/support/stripe-billing-failed-renewal.ts and its .test.ts.
**Interfaces:** Existing runFailedRenewalBrowser(browser,runAbort) exact initial/method/close shape. Reuse browserFailure/browserDiagnostic/finishBrowserCleanup from infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs, existing fixed codes only. No new page API or externally chosen diagnostic.

- [ ] Write inert regression tests first for action/session delivery rejection, pre-ready/mount refusal, number/expiry/CVC/postal failure, manual arm refusal, manual wait timeout and first failure surviving stop/close. Assert fixed WeakMap checkpoint, no secret canary, no CONFIRM, no replay and exactly one close. Keep successful held-page and method journey tests.
- [ ] Root audits test closure and runs explicit new tests RED before production edits; worker waits for release.
- [ ] Implement first-failure latching before abort and preserve bridge provenance with latchFailure callback. Wrap exact controller operations using existing BROWSER_* checkpoints. STOPPED/page-loss paths record current controller checkpoint only. Ensure close can attach cleanup evidence without changing successful close authority; no raw error reads.
- [ ] Root runs memory browser tests GREEN plus related tests/types. Independent task review, fix findings and re-review. Root commits tested task.

### Task 2: Publish dedicated terminal browser diagnostic

**Files:** infrastructure/stripe-billing-first-payment.mjs, infrastructure/stripe-billing-failed-renewal.test.mjs, examples/demo-site/tests/support/stripe-billing-failed-renewal.test.ts; documentation above if needed.
**Interfaces:** Task1 throws existing browserFailure provenance. Owner's BROWSER step already reads browserDiagnostic(error), potentially code ABORTED due first-failure cancellation. Dedicated formatBillingFailedRenewalDiagnostic(result) and parseBillingFailedRenewalDiagnosticLine(line) retain signatures and exact fixed text fields.

- [ ] Add owner integration and formatter/parser tests first: trusted initial browser failure after prepare but before confirm yields phase BROWSER, code BROWSER or ABORTED, original fixed diagnostic, initial/failed/full5b false, correct cleanup witness and no confirm/decline/retry. Confirm unknown error properties never gain provenance.
- [ ] Add reporter BROWSER annotation delivery tests using real class and native begin/end lifecycle, covering malformed/foreign/duplicate/static-forged values and suppressed raw channels. Root runs new tests RED.
- [ ] Preserve process-local browser provenance when immediate runAbort wins the outer bounded race: capture only WeakMap browserDiagnostic from this original held initial promise, and after its existing bounded cleanup drain fill a missing BROWSER/ABORTED diagnostic only. Never overwrite an earlier owner failure, await an unbounded task or change revocation. Test synchronous abort followed by late trusted rejection, foreign failure and cleanup refusal.
- [ ] Extend only failed-mode formatter/parser: stage LISTENER accepts persistence codes; stage BROWSER accepts existing browser codes; exact stage/code relation (stage or ABORTED), terminal own data, all acceptance false, consistent cleanup. Do not broaden legacy protocols or reporter trust gates.
- [ ] Root runs full relevant inert test files, related legacy regression tests and cached strict TypeScript. Independent task review; Root commits.
- [ ] Strong independent whole-change review of both tasks. One consolidated fix wave if needed, scoped re-review; preserve local artifacts and report historical-cause limitation. No push/native CI or runtime launch under this task's offline boundary.
