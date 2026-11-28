# Webhook Persistence and Terminal Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the failed-renewal-only webhook/config coupling and provide truthful bounded terminal diagnostics and stopped proof after persistence failure.
**Architecture:** Fresh failed-mode final.env uses the current listener verifier without changing shared config. The owner retains consumed/pending authority and the reporter publishes only allowlisted terminal fields after close.
**Tech Stack:** Existing Node ESM, Node test in-memory fixtures, TypeScript/Playwright types; no new dependencies.
**Spec:** docs/superpowers/specs/2026-11-15-webhook-persist-cleanup-design.md

## Global Constraints

- Only OWNED_FAILED_RENEWAL_GRACE_RECOVERY changes persistence policy; all other modes keep empty/equal-only shared-secret policy.
- No Docker commands/daemon/container, SQL, app/browser, Stripe, Python, installs, old operation replay or historical config/lock/evidence mutation.
- Source-only workers/reviewers; only Root explicitly audited inert tests and cached static typechecks may execute.
- Preserve keys, paths, data, raw bodies, pairing details and errors from every diagnostic output; use a finite checkpoint vocabulary.
- Preserve original intents, sticky pending authority, original finite budgets, exactly-once dispatch, no automatic retry, no adoption/financial side effect.
- Preserve the worktree and task evidence. No native acceptance claim or new attempt.

## Review Focus

- Shared file changes during snapshot: reject through unchanged identity/bytes; never write the shared input.
- Partial final-store or acknowledgement: pending stays consumed; cleanup cannot become acknowledgement/readiness.
- Pending unknown creation, extra/foreign/replaced/running resource: no new cleanup authority.
- Existing stale/malformed/foreign writer: no access, unlink, adoption or false release claim.
- Reporter handoff followed by canary/error/duplicate/foreign annotations: no sensitive output or false pass.
- Every failed persistence stays outside generic all-ACK cleanup. Exact seven-resource proof requires the retained owner descriptor, publishes no ledger and preserves exact durable bytes and memory projections through dispatch/ACK/close uncertainty; the descriptor grants one proof only.
- Terminal delivery requires exactly one clean pre-end onTestBegin baseline matching the exact target case/file/project/expected status, zero retry and the same TestCase/TestResult identities. Reject reserved static annotations even when setup failure copies them into both native end arrays; preserve the existing success predicate.

### Task 1: Failed-mode run-scoped persistence and exact stopped proof

**Files:** infrastructure/stripe-sandbox-bootstrap.mjs; infrastructure/stripe-sandbox-bootstrap.test.mjs.
**Interfaces:** Add only failed-mode readonly persistenceDiagnostic():string|null with finite codes PERSIST_ADMISSION, PERSIST_OWNERSHIP, PERSIST_INPUT, PERSIST_UNCHANGED, PERSIST_FINAL_STORE, PERSIST_ACK. Codes are last local checkpoint on refusal, not raw cause or success. Task2 consumes this exact capability. Leave legacy startupDiagnostic intact.

- [ ] Author memory regressions first: old nonempty webhook differs from current listener yet failed-mode final.env uses current verifier; empty/equal/different values, quotes/CRLF, opaque old writer unaffected; shared input exact bytes/inode and zero rename/writer calls. Keep malformed/unknown/duplicate/live-key/placeholder/private metadata checks.
- [ ] Test final-store sync, ACK, concurrent input, timeout/abort refusals preserve pending and no redispatch. Finite diagnostic contains no value/path/error and cannot turn success into failure or failure into readiness.
- [ ] Test exact prepared failed-mode persistence failure can fresh-prove seven nonrunning resources; all receipts/pending remain unchanged. Test missing/extra/foreign/replaced/running/nonzeroStartedAt services and pending CREATE/other action refuse; no stop/start/removal/global-writer dispatch. Other modes retain prior policy and cleanup tests.
- [ ] Root audits fixtures and executes explicit RED. Release implementation only after intended failures are established.
- [ ] Implement narrow failed-mode private persistence, sticky checkpoint and exact cleanup exception. Root targeted GREEN and related inert regression; independent task review/fixes, then commit.

### Task 2: Trusted terminal delivery and operator documentation

**Files:** infrastructure/stripe-billing-first-payment.mjs and .d.mts; infrastructure/stripe-billing-failed-renewal.test.mjs; examples/demo-site/tests/stripe-billing-failed-renewal.spec.ts; examples/demo-site/tests/support/stripe-billing-failed-renewal-reporter.ts; examples/demo-site/tests/support/stripe-billing-failed-renewal.test.ts; docs/testing/billing-failed-renewal-recovery.md.
**Interfaces:** Consume Task1 persistenceDiagnostic only for failed-mode LISTENER failure; add formatBillingFailedRenewalDiagnostic(result):string|null and parseBillingFailedRenewalDiagnosticLine(line:unknown):string|null with one canonical closed string. Include fixed stage/checkpoint and separate CONFIRMED/UNCONFIRMED cleanup and fixed cleanup witness. No raw resources/code serialization.

- [ ] Add owner regressions for listener/persist rejection: stop runs, original failure remains, confirmed/unconfirmed cleanup distinguished, no browser/financial work/replay. Test hostile diagnostics map to unknown/refuse.
- [ ] Add memory reporter tests using actual reporter: post-handoff valid trusted terminal annotation is emitted once only for exact target; stdout/stderr/canary/duplicate/foreign/malformed annotations suppressed; success predicate unchanged. Pin spec finally-close-to-annotation wiring with a static source check and typecheck actual cached Playwright API.
- [ ] Root audit and explicit RED, then release minimal integration. No broad reporter overrides, raw error output or new IO.
- [ ] Document run-scoped verifier, untouched historical writer and the distinction between stopped proof and consumed persistence. Preserve old historical failure evidence and current Stage5B NOT_ACCEPTED.
- [ ] Root targeted/related GREEN and strict cached TS checks, independent task review/fixes, commit. One final strongest-model source review of this bugfix range; no native run. Keep branch and evidence; choose publication under standing user authority after tests/reviews, never claim prior-head CI as current proof.

### Consolidated final review fixes

- [x] Author source-only regressions for initial intent write, ACK temporary write/file sync/post-rename directory sync and post-ACK input close. Each covers inactive proof, unexpected running service/helper, exact durable bytes and repeated-stop refusal; cover ambiguous owner close separately.
- [x] Author actual reporter regressions for copied reserved static annotations on setup failure, clean late native end delivery, missing/mismatched/repeated begin proof and reserved/retried baselines.
- [x] After Root's intended RED release, implement exclusive one-use proof-only failed-persistence cleanup without ledger publication and reporter clean-begin provenance; update design/runbook.
- [ ] Root runs audited affected/related regressions and strict cached Playwright 1.63.0 typecheck, then one scoped independent re-review. Worker makes no execution claim; native spec remains unexecuted. Historical persistence remains UNKNOWN and full Stage 5B remains NOT_ACCEPTED.
