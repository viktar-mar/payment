# Stripe CLI Authorization Warning Fix

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. One atomic offline follow-up; the user explicitly requested implementation, tests and independent review on 2026-10-20. Do not ask again whether to implement this same scope.

**Goal:** Accept the pinned CLI's one known nonsecret storage warning without weakening secret rejection, and explain authorization failures using finite safe diagnostics.

**Architecture:** Keep the existing isolated two-child authorization lifecycle and independent account binding. Validate raw output first, recognize one exact stdout warning for the current run, then apply the existing credential-marker rejection to everything else. Brand internal failures with finite stage/reason values; the real entry can emit one bounded diagnostic on stderr while retaining the exact payment report and stdout protocol.

**Tech Stack:** Existing Node ESM, node:test, synthetic child-process boundaries; no new dependencies.

**Spec:** User's explicit offline correction request; unchanged financial/ownership/privacy constraints from `docs/superpowers/specs/2026-10-19-stripe-manual-payment-design.md`. This approved follow-up permits only the exact warning exception and one finite stderr diagnostic described below; it does not authorize a new Sandbox attempt.

## Global Constraints

- Work only in the existing isolated `codex/stripe-sandbox-5a` worktree. Main, merges, pushes and historical evidence are out of scope.
- No actual Stripe CLI invocation, OAuth, keys/private config/audit reads, Sandbox/provider/browser operation, runtime stack start, cleanup or deletion. All new tests use synthetic child callbacks/close events and synthetic values.
- Preserve the pinned CLI `1.52.0`, exact descriptors/environment, no Keychain access, original login 180000ms/complete 600000ms/actual-close grace 10000ms, and original active/cleanup authority. No retry or reauthentication.
- Callback success and actual close exit0 remain jointly necessary; warning acceptance is not account or payment proof. Account/API checks and all later financial permission/proofs remain unchanged.
- No raw stdout/stderr/error/message/stack/path/account/credential/device-code emission in diagnostics. No new fields in the final version1 MANUAL_PAYMENT report; its existing booleans/provenance remain honest.
- Keep independent review before local commit. Implementer stages exact tested changes, does not commit; root packages the staged tree. Preserve worktree/evidence; no generic skill cleanup.
- Final infrastructure verification may run only the existing synthetic loopback fixtures and static Docker tests (synthetic FROM scratch, network=none/local output, synthetic Compose config), with newly isolated temporary Docker/Buildx configs and pinned Unix daemon. No real application images/services/registry/Stripe. No dependency install, SDK/demo/Java changes or unrelated gates.

## Review Focus

1. A warning-like string with a foreign/sibling/traversal path or prefix/suffix must not bypass credential checks.
2. A valid known warning plus any secret/control/oversized output in either channel must fail without leaking a canary.
3. Timeout, abort, failed callback, missing close, late callback/close and mutated deadline must still stop before binding/provisioning.
4. Errors/objects with hostile message/stack/code getters must not become a diagnostic data source; only module-owned finite classification is emitted.
5. A rejected/stalled diagnostic writer must not alter proof, trigger another auth/financial operation, renew deadlines or duplicate final output.

### Task 1: Narrow warning handling and authorization diagnostics

**Files:**
- Modify: `infrastructure/stripe-manual-payment-auth.mjs`
- Modify: `infrastructure/stripe-manual-payment-auth.test.mjs`
- Modify: `infrastructure/stripe-manual-payment.mjs`
- Modify: `infrastructure/stripe-manual-payment.test.mjs`
- Modify: `docs/local-development.md` (manual-payment section only)
- Track this plan; no unrelated files, helper modules or public SDK changes.

**Interfaces and warning contract:**
- Preserve `authorizeManualPaymentCli({cli,runId}, limits, onAuthorization, deps={}) -> Promise<true>`; rejection remains fail-closed.
- Add internal module export `formatManualPaymentAuthDiagnostic(error) -> string`, returning one JSON line no more than256 UTF-8 bytes, exactly `{tag:'stripe_manual_payment_auth_diagnostic',stage,reason}`. `stage` is LOGIN/HANDOFF/COMPLETE/UNKNOWN. `reason` is DESCRIPTOR_INVALID/PROCESS_FAILED/CHILD_CLOSE_UNCONFIRMED/TIMEOUT/ABORTED/OUTPUT_INVALID/OUTPUT_UNSAFE/HANDOFF_INVALID/HANDOFF_FAILED/INTERNAL. Unknown/unbranded errors map to UNKNOWN/INTERNAL without reading their properties. No Error.message parsing or external getters. A module-owned WeakMap is an appropriate narrow branding mechanism.
- Classify the first primary failure at the existing boundary; late child work must not overwrite it or become success. Do not infer account identity from text. Diagnostics are observational, never authority.
- The only exception is the complete stdout line:
  `Warning: the system keyring is unavailable. Your credentials have been stored unencrypted in <ROOT>/test-results/stripe-sandbox/<original runId>/cli/config/stripe/credentials.json`
  ROOT is the module's existing fixed ROOT. At most one such exact line; no other path, prefix, suffix, ANSI/control normalization, arbitrary warning allowance or marker-regex relaxation. No exception on stderr or login-init JSON. Apply original raw type/size/control validation before removing this one line for the existing marker scan; combined raw stdout+stderr still <=65536bytes. Empty/ordinary safe completion text remains supported.
- The exact upstream nonsecret warning is from pinned `https://raw.githubusercontent.com/stripe/stripe-cli/v1.52.0/pkg/login/login.go`, `warnIfInsecureStorage`; its content is given here, no live/source fetch is needed by the implementer. Do not inspect the actual stored credential file.
- Controller records the safe diagnostic only when authorization fails; successful authorization emits no extra diagnostic. Real entry supplies a stderr writer through internal injected capability; synthetic/library callers need not write global stderr. Emit at most once, before the one final report, bounded to min(now+1000ms, original cleanup deadline), after independent cleanup attempts. Failed/stalled diagnostic emission remains PENDING/exit1 and cannot change financial/cleanup proof or retry. Existing stdout auth/URL/final-report format remains unchanged.

- [x] **Step 1: Meaningful RED.** Extend actual auth tests so the exact original-run stdout warning with callback+close0 must accept but currently fails. Add behavioral safe-diagnostic REDs for unsafe completion and real auth-module→controller→entry integration. Tests must exercise real module/controller, mocking only child/spawn/stream boundaries. Do not print synthetic credential strings in test names or diagnostics.
- [x] **Step 2: Implement the smallest auth correction.** Recognize the one exact warning only after raw validation, maintain all existing checks and bounds. Introduce finite module-owned error classification and formatter; preserve old descriptor/lifecycle contracts.
- [x] **Step 3: Integrate finite stderr diagnostic.** Preserve final report fields and no-follow-on-operation behavior. Cover writer failure/stall, hostile errors, auth rejection, rejected actual close, normal success without diagnostic and later binding failure without fabricated auth diagnostic.
- [x] **Step 4: Run focused GREEN.** `env -i PATH=/opt/homebrew/bin:/usr/bin:/bin LANG=C LC_ALL=C NO_COLOR=1 /opt/homebrew/bin/node --test --test-concurrency=1 infrastructure/stripe-manual-payment-auth.test.mjs infrastructure/stripe-manual-payment.test.mjs infrastructure/stripe-manual-payment-schema.test.mjs` must exit0. Include each Review Focus case and real auth seam, not source-text assertions.
- [x] **Step 5: Final regression and documentation.** Full `infrastructure/*.test.mjs` once after final source changes under the static-test-only exception above; use explicit pinned Docker PATH/daemon and fresh isolated temporary configs, retain paths. Report every failure/deviation honestly. No Maven/SDK/demo rerun because unchanged. Update only manual auth documentation with warning exception, diagnostic enum/privacy and no live acceptance claim.
- [x] **Step 6: Self-review/stage/report.** Read own diff; verify no unstaged unrelated changes, clean diff --check. Stage exact tested files including plan, no commit. Full report with RED/GREEN commands/output, full-suite result, exact changed files, synthetic-only assurance and concerns at the controller-specified report path. Return <15lines.
- [ ] **Step 7: Root independent gate/local finish.** Root packages exact staged tree; fresh reviewer checks spec+quality and the complete current follow-up delta, not historical branch work. Fix/re-review any findings. Root verifies reviewed tree before local commit; no push/merge/cleanup/new attempt. Report offline result and any remaining live uncertainty to human.
