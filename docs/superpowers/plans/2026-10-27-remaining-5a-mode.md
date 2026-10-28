# Remaining-only 5A Sandbox mode — implementation plan

> Execute with `superpowers:subagent-driven-development`: one implementer at a time, RED → GREEN, independent specification and quality review after each task, then whole-delta review.

**Goal:** implement an explicitly selected offline-prepared runner for only the unproven 5A journeys. Do not run it against Docker or Stripe in this task.

**Architecture:** extend the existing owned acceptance orchestrator through a private shared opener; keep its full-mode contract unchanged. A separate named remaining-only opener and separate Playwright config/spec reuse the real server-proof/refund, donation/OTP, missed-webhook and owned-cleanup capabilities. No second controller, new dependencies, Python, archive/recovery tooling or backend changes.

**Tech stack:** native Node ESM/node:test, TypeScript, existing Playwright/browser support, existing local cached dependencies.

**Approved specification:** human request of 2026-10-27 plus the binding scope below. The request explicitly authorizes implementation, subagents, tests and independent review, but not Docker, Sandbox, financial operations or touching the old payment. No further design approval checkpoint is needed.

## Binding scope and safety constraints

- Operate only on the future fresh owned run/site/catalog/campaign and its new durable state. Do not read private runtime files, keys, prior payment state, recovery sources or archives in this implementation task. Preserve all pre-existing unrelated files.
- In the future run, make one new successful catalog payment of €20. Browser completion alone is insufficient: retain existing account/site/provider-operation/payment/signed-webhook/journal/outbox/delivered-signed-callback verification.
- On that payment only, dispatch €7 once with its existing durable stable idempotency key. Require the existing exact refund success/provider-reference/event/journal/callback/700-refunded proof before dispatching €13 once with its separate durable key. Final proof is REFUNDED/2000/exactly two expected refunds and facts. Unknown, pending, lost acknowledgement or mismatched state stops; no automatic repost, replacement key or resume.
- Then one anonymous donation of €5 and one EMAIL_OTP donation of €5 with the existing real browser, campaign/customer/source, Mailpit OTP and server proofs.
- Finally another new catalog payment of €20 for missed-webhook recovery. Reuse the existing exact new intent/operation binding, negative baseline, own listener stop, provider capture proof, still-negative local baseline, single stable-key admin reconcile, new unique RECONCILIATION fact and signed delivered callback. A raced webhook fails closed.
- Total cap is four successful new payments (€20 + €5 + €5 + €20), two refunds (€7 + €13) on the first only. Shared provisioning may create unused decline/SCA checkouts; do not pay or confirm them to satisfy full-mode predicates.
- First failure stops subsequent stages. Keep current ownership, deadline (45 minutes total), process acknowledgement, abort, cleanup (2 minutes) and privacy rules. No retries, automatic resume/rotation, deletion or evidence rewriting.
- Distinct report: `mode: 'REMAINING_5A'`, `remainingAccepted` only after all narrow server proofs and confirmed cleanup; `accepted: false` and `proof.cards: false` always. A separate `proof.payment` records this success-only payment. Never import historical 3DS/decline proof, never claim full 5A acceptance.
- Explicit selection uses `PAYMENT_STRIPE_SANDBOX_REMAINING_ONLY=true` plus existing ENABLED/ALLOW_TEST_OPERATIONS/OWNED_RUN flags. Own enumerable data descriptors only for the selector: reject inherited/accessor/hidden/malformed or mixed authority before dependencies or private access. Full opener rejects selected remaining mode. Pass only existing canonical flags to reviewed underlying capabilities.
- Implement here only. No actual Docker calls, Stripe/CLI/network service calls, browser financial journeys, installs, Python, original stack, old payment/refunds/archives. Artificial ephemeral loopback fixtures and list-only Playwright discovery are allowed. Do not auto-push this patch; a later exact-source CI/run requires separate action.

## Global review focus

Scope separation and truthful reports; real proof composition instead of mocked browser success; €7-before-€13 and pending acknowledgement handling; revoked success-only capability; no skipped-card assertions; default discovery isolation; first-failure/ownership/cleanup/deadline/privacy regressions. Do not broaden review into unrelated historical recovery tools.

## Task 1: implement remaining-only owned orchestration and proof composition

**Files:** modify `infrastructure/stripe-sandbox-acceptance.mjs`, its `.d.mts`, `infrastructure/stripe-sandbox-acceptance.test.mjs`, `infrastructure/smoke-stage-5a.test.mjs`; optionally add a small `infrastructure/stripe-sandbox-remaining-options.mjs` plus its native tests if that avoids duplicating descriptor validation. Do not change production refund/extras proof rules or broaden their gates.

**Contract:** export `openOwnedSandboxRemainingAcceptance(options, limits, dependencies?)`, sharing the private setup/cleanup engine with the existing opener. Return `run({payment, donations, missed}, limits)`, `close(limits)` and `resources()`. Types must expose a distinct remaining owner/resources and a browser-facing payment fixture without refund/close authority. Runtime restricts its methods to `success`, no retry, one begin/markPay, finite original context; revoke it after the payment journey. Parent independently requires `cards.confirm('success') === true` before directly requiring the same real fixture's `refunds() === true`. Do not call `runStripeSandboxSmoke` (it requires all three cards). Keep ordinary full-mode output and behavior unchanged. Reuse existing donations and missed capabilities and existing safe diagnostic codes/phases.

**Steps:**

1. Add meaningful failing tests first. Cover absent/malformed/mixed selector and zero getter/dependency/private access, exact stage order and bounds, skipped decline/SCA, success-only facade/no retry/no late authority, browser lie/false server proof, refund refusal/pending, each later-stage refusal, first failure/cleanup uncertainty, repeated calls/deadline/abort and distinct immutable truthful results.
2. Add a synthetic HTTP composition test using the existing smoke test fixture: success confirmation and €7 → €13 complete without confirming decline/SCA; assert refund paths/amounts/keys/durable order and existing server joins. Include failure-before-second-refund proof regression. Do not duplicate a large fixture or substitute provider proof with a boolean.
3. Implement the smallest shared orchestration change and declaration contract. Retain existing full tests as regressions.
4. Run the complete allowed relevant suites once, record exact commands/counts and RED/GREEN; all execution boundaries in orchestration tests are synthetic. Controller will independently verify before review.

**Verification:** cached Node 24 `--test --test-timeout=30000 --test-concurrency=1 infrastructure/stripe-sandbox-acceptance.test.mjs infrastructure/smoke-stage-5a.test.mjs infrastructure/stripe-sandbox-extras.test.mjs infrastructure/stripe-sandbox-owned.test.mjs` plus any new native options tests. Use absolute runtime `/Users/viktar.marozau/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node` and put its directory first in a clean PATH. Never run broad Docker-backed test files. Baseline Homebrew Node 25 native SIGABRT is not a RED feature assertion.

**Report:** write your assigned report with contract/files, precise RED/GREEN, remaining limitations and no runtime claims. Do not commit, push or spawn agents. Controller reviews a frozen working-diff package.

## Task 2: dedicated browser entry, discovery isolation and operator documentation

**Depends on:** reviewed Task 1 contract. Read it, do not redesign the engine or widen financial authority.

**Files:** create `examples/demo-site/playwright.stripe-sandbox-remaining.config.ts` and `examples/demo-site/tests/stripe-sandbox-remaining.spec.ts`; modify default `examples/demo-site/playwright.config.ts` to exclude the new Sandbox spec. Add the minimal raw remaining-selector exclusion to existing `tests/stripe-sandbox.spec.ts` before either full-owned or legacy three-card setup (its current env projection would otherwise hide the selector from library admission). Reuse `tests/support/stripe-sandbox-fixtures.ts` and `stripe-sandbox-acceptance.ts`; minimally type the card driver against its actually used fixture methods so the narrow facade can drive only success. Add discovery/entry regression tests in the existing `infrastructure/smoke-stage-5a.test.mjs` or a separate native no-Docker test file and existing support native tests as appropriate. Update the existing README/5A operator docs, not a new diagnostic framework. No package/lockfile/dependency changes.

**Contract:** dedicated one-test serial run, workers1/retries0, original 45min/47min overall caps, 15s assertions, existing redacting Sandbox reporter, trace/screenshots/video off, preserveOutput never, no webServer/automatic install/default CI execution. Before browser authority, require exact remaining selector and owned setup. Journeys call real `runSandboxCard(...,'success')` only for the new refund-payment and missed-webhook payment, existing owned-context wrapper and real donation/OTP driver. Never loop success/decline/SCA. Abort and owned cleanup retained; final assertions use `remainingAccepted`, never full `accepted`.

**Steps:**

1. RED native contract/discovery tests first: default Playwright list excludes all Sandbox specs and node:test files; dedicated config lists only this one narrow test; absent/mixed opt-in fails before browser/provisioning, no fallthrough to ordinary/full runner. Both old config branches must refuse the remaining selector rather than stripping it and running three cards. Test actual config/spec behavior with synthetic boundaries rather than only source regex.
2. Implement dedicated config/spec and minimal compatible driver typing. Reuse helpers; don't duplicate payment/donation proof logic.
3. Document future command, all four flags, fresh admission/source/run/ports prerequisites, exact four-payment/two-refund budget and order, no old-payment target, no retries/resume, stop-on-first-failure, safe report distinction and remaining full5A acceptance status. Explicitly say this task did not run Sandbox and publication/exact-source greenCI/fresh runtime authorization are still prerequisites.
4. Run relevant native support suites, TypeScript `tsc --noEmit`, demo build, default and both dedicated configs `playwright test --list` only, plus Task 1 native regressions. No browser financial execution.

**Report:** record exact RED/GREEN and modified paths, no service/runtime claims. Do not commit, push or spawn agents. Independent task review and final whole-delta review follow.

## Final verification and handoff

Controller runs relevant full native infrastructure suites with Docker-backed files excluded, full native support suite with synthetic process/HTTP/browser boundaries, demo typecheck/build and discovery-only listings. Do not run Maven (backend unchanged; may start Testcontainers), Docker, actual browser Sandbox specs or external services. Freeze exact diff, obtain final independent spec/security/quality review, resolve all findings through implementers, then hand off local patch with evidence. Full acceptance remains open until separately authorized exact-source hosted CI and real remaining Sandbox checks.
