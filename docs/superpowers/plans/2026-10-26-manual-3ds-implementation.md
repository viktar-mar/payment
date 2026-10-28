# Manual 3DS Offline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development task-by-task. The human explicitly requests implementation, subagents/tests/review and no intermediate continuation questions.

**Goal:** Prepare genuine human-operated3DS with correlated server proof, without running Docker or Sandbox.

**Architecture:** Extend the current manual-payment controller, finite public scenario header and existing page; reuse its one-shot SDK lease, CLI/bootstrap/provisioning and all evidence checks. No duplicated runtime or guessed provider DOM.

**Tech Stack:** Existing Node ESM, TypeScript, native node:test, intercepted Playwright Chromium, Vite; no dependencies installed.

**Spec:** `docs/superpowers/specs/2026-10-26-manual-3ds-design.md`, plus unchanged `docs/superpowers/specs/2026-10-19-stripe-manual-payment-design.md`.

## Global Constraints

- Offline implementation only: no Docker, Sandbox, payment/account API, keys/private runtime/archive reads, financial action, installation, Python or new diagnostic framework.
- Preserve one fresh fixture/onePay, no automatic retry, existing account/API/source/ownership, signed webhook/journal/outbox/callback and capture proof; only genuine server evidence grants success.
- Optional `PAYMENT_STRIPE_SANDBOX_MANUAL_3DS=true` requires all existing manual-payment opt-ins. Absent/empty/false is ordinary mode; other/accessor values fail closed. Ordinary report/API behavior stays compatible.
- Same page/routes/body/manifest/cookie/CSP/Host/Origin; only3DS protected session response has `X-Payment-Scenario: 3DS`, chosen by controller not browser. No provider iframe introspection or automatic3DS click.
- Same captured charge requires authenticated/challenge/2.x.x3DS evidence; modeMANUAL_3DS adds only three_ds_verified. No loosening of old checks or timeouts; verify≤45s/30probes, existing5minpage/45minauthority/+2mincleanup.
- Preserve existing worktree and pre-existing docs/stage-5a-acceptance.md outcome delta. No commit/push by implementers; root owns final review/publication decisions. No merge or readiness claim.

## Review Focus

- Wrong/ordinary card must never satisfy selected3DS proof (Task1: missing/result/version/flow negative vectors).
- A malicious config getter or mixed mode must not reach private/runtime dependencies (Task1: descriptor/entry admission tests).
- Scenario header must appear only on the protected session response, with no capability/body-schema leak (Task1: gateway behavior tests).
- Intermediate SDK states must not cancel a human challenge, but repeat confirmation remains denied (Task2: state sequence and one-shot lease tests).
- Missing/invalid header, expiry, page close and terminal failure must fail closed without raw error/secret output (Task2: UI tests/build/typecheck/discovery).

### Task 1: Infrastructure mode admission, evidence, finite handoff and report

**Files:** Modify `infrastructure/stripe-manual-payment-options.mjs` and `.d.mts`, `stripe-manual-payment.mjs` and `.d.mts`, `stripe-manual-payment-schema.mjs` and `.d.mts`, `stripe-manual-payment-gateway.mjs` and `.d.mts`, `stripe-sandbox-cli.mjs` and its `.d.mts` where needed; corresponding existing tests including `stripe-sandbox-cli.test.mjs`. Do not change browser sources or ordinary mode semantics.

**Interfaces:** Consumes current manual-payment controller/bootstrap/CLI/provisioner. Produces canonicalenv optionalMANUAL_3DStrue, gatewayoptional `threeDSecure:true`, session header `X-Payment-Scenario: 3DS`, conditional final inspector proof `{captured:true,eventVerified:true,threeDSecureAuthenticated:true}` and strict MANUAL_3DSreport with three_ds_verified. Keep old ordinary result byte-shape unchanged. All exact signature/value details are in the spec.

- [x] RED: exercise real opt-in/options/entry with malformed/missing required flags, invalid newflag/accessors, ordinary controls; selected env must retaintrue only when selected.
- [x] RED: exercise actual CLI final inspector with captured bound Charge/Event, require3DS authenticated/challenge/2.x.x; independently authored missing/null/failed/frictionless/invalidversion vectors fail, ordinarycaptured result unchanged. No additional financial/provider operation.
- [x] RED: actual controller selected mode cannotSUCCEED without3DSproof and every old server/cleanup proof; ordinary mode stillSUCCEEDS withoutnewfield. Strict new report parser rejects extra/duplicate/accessor/inconsistenttruefalse. Real gateway protected session header-onlyselection, ordinarynoheader and existing auth/onegrant behavior.
- [x] Run focused named existing tests before implementation; expected assertion failures due absentnewmode/evidence/header, not dependency/compiler errors. Save RED output in plan workspace.
- [x] Minimal implementation using spec; no fixturehelper in production, no runtime/private reads. Existing45sserver verification unchanged. Update type declarations.
- [x] GREEN related Node tests with original30000ms limits, whitespace and no-private-path review. Save command/count/output; independent task review must approve before Task2 production changes.
- [x] Write task-1-report.md with RED/GREEN evidence, exact changed files, concerns and integration notes; no commit/push. Root captures worktree diff package against0d9dfc5 (excluding pre-existing acceptance delta).

### Task 2: Human-operated3DS page, lifecycle regressions and operator instructions

**Files:** Modify `examples/demo-site/src/manual-payment.ts`, corresponding `tests/support/manual-payment.test.ts`, `manual-payment-build.test.ts`/config/native tests only if behavior coverage requires; update `docs/local-development.md` and acceptance doc narrowly. Reuse current HTML/entry/manifest; no new dependencies or production SDK change.

**Interfaces:** Consumes trusted protectedsession header from Task1. Produces3DSinstruction card4000002500003155, no DOM automation, connectedvisiblewidget during checking/processing/requires_action; terminal/expiryclose removesit; ordinary lifecycle unchanged. Uses existing armManualPayment one-shot lease, no public API change.

- [x] RED: absentheaderordinary behavior; exact3DSheader choosesinstructions only after exchange; unknownheader fails before widget/lease/sessionmount. Before loading use neutral instructions, not4242 forfuture3DS.
- [x] RED: selected3DSonePay, intermediatechecking/processing/requires_action keep widget visible/connected and leasealive; repeatedclick/Enter/programmatic confirm never reaches a second permit/Stripecall. Terminal succeeded/failed/unknown/expired/pagehide/abort/timeout retires once; failed/latepermit never producesfinancialsuccess.
- [x] Run focused native tests before implementation; expectedbehavior assertionfailures. Implement only page/header lifecycle logic, preserving record validation/HTTP/cookie privacy and fixedcodes.
- [x] GREEN page/support tests; demoTypeScript/Vitebuild and SDKmanualauthorization regression. Complete native support with original60000ms pertest and all artificial browser traffic intercepted; defaultPlaywright `--list` only must excludeSandbox/native support tests. No actualE2E, Docker orSandbox.
- [x] Operator docs: show future opt-ins and3DScard/humanchallenge/45sverifywindow/onePay/no retry, resultMANUAL_3DS/three_ds_verified, preserved server/journal/callback gates and browserNOT_OWNED. No fresh URL/authorizationcode/payment is created. Keep5ANOT_ACCEPTED and prior outcomes intact.
- [x] Write task-2-report.md and scopeddiff; independenttaskreview, then final combined feature review. Root verifies observed test results and records excluded Docker/backend/Sandbox gates honestly; no financial run follows.

### Task 3: Final-review correction — valid action-required event and UI sequences

**Origin:** Final independent review found two Important integration gaps in unchanged consumers, source-verified by the controller. This is one bounded final fix dispatch, not a new diagnostic framework or runtime permission.

**Files:** `infrastructure/stripe-form-diagnostic-provision.mjs` and its existing test; `examples/demo-site/src/manual-payment.ts`, existing source/emitted-page support tests. No backend/SDK public API, timers, dependencies, routes, provider operations or private inputs change.

- [x] RED: real provisioner selected 3DS accepts bound `CUSTOMER_ACTION_REQUIRED`/`payment.requires_action` journal/outbox/delivery/receiver history followed by unique valid success. Independently authored negative vectors retain event identity/source/provider-object/amount/currency correlation, callback joins, final success callback proof, ordinary-mode rejection and pre-permit rejection. No additional writes/requests beyond the existing verification flow.
- [x] Minimal selected-only intermediate-history allowance and matching callback mapping; original successful/unpaid proofs, original identities and finite limits unchanged. Snapshot the validated selected flag, not a browser choice.
- [x] RED: real SDK/page and emitted page reproduce `REQUIRES_ACTION → REQUIRES_ACTION` and `PROCESSING → REQUIRES_ACTION`; provider remains visible/connected with one spent permit/one provider dispatch until explicit close/expiry. No new watch, Pay/Resume or iframe interaction. Preserve ordinary post-attempt ready retirement and selected validation/terminal retirement.
- [x] GREEN affected tests at original30s/60s limits, related nine-file infrastructure regression, demo typecheck/build and discovery. No duplicate unchanged full SDK/native suite, Docker, backend or Sandbox. Save exact RED/GREEN evidence in task-3-report.md.
- [x] Scoped independent review resolves each final finding and checks the correction for new breakage. Root records rulings on every declined review scope, final delta/whitespace/privacy checks and offline-only completion. Preserve all historical outcomes/evidence; no automatic runtime or Git integration.
