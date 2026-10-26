# Initial manual PROVISION checks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development task-by-task. User requests subagents/tests/independent review and continuous offline execution.

**Goal:** Correct reproduced initial-check redundancy/cancellation defects and identify any future initial failure without raw diagnostics.

**Architecture:** Reuse the existing manual bootstrap/provisioner/controller. Preserve full ownership proof and fixed deadlines; no new runner, dependency or financial surface.

**Tech Stack:** Existing Node ESM, node:test and in-memory filesystem/process/HTTP fixtures, virtual time.

**Spec:** `docs/superpowers/specs/2026-10-26-provision-initial-checks-design.md` plus existing manual-payment and manual3DS designs.

## Global Constraints

- Offline only: no Docker invocation, Sandbox/Stripe/network, original payment/refund/archive access, installation or Python. No real keys/private runtime contents or raw logs.
- Preserve source/account/API/ownership/signed journal/callback/capture gates, one permit/Pay and no retries. All existing limits remain:10s child/HTTP,45min authority,45s/30 financial probes,5min page, original+2min cleanup.
- Existing manual report version/shape, ordinary mode, form diagnostics and full acceptance remain compatible. Unknown stays PENDING, not proof of no payment. Historical artifacts/results are immutable.
- Preserve unrelated historical acceptance delta and untracked recovery files/plans. No Git mutations by implementers; root owns publication decisions. Do not commit/push in this offline deliverable.

## Review Focus

- Credential acquisition cannot bypass complete ownership proof after deduplication (Task1: real-component and snapshot substitution regressions).
- Child limits cannot replace/extend startup authority or lose original cancellation (Task1: original/child abort, expired/extended/invalid limits).
- Timeout must not expose credentials late or leave new work authorized (Task1: deferred verify/late callback/sticky-failure regressions).
- Unknown/hostile dependency errors must not leak via initial diagnostics (Task2: accessors/proxies/canary tests through real formatter and controller).
- Diagnostic write must not defer cleanup, change stdout schema or consume new time authority (Task2: emitted ordering, stall/reject/ceiling and ordinary/3DS controls).

### Task 1: Correct manual credential proof composition and child authority

**Files:** Modify `infrastructure/stripe-form-diagnostic-provision.mjs`, `infrastructure/stripe-sandbox-bootstrap.mjs` and their existing `.test.mjs` files. Follow current large-file organization; no new framework/helper production API except the bounded optional credential argument.

**Interfaces:** Preserve callback-only `withAdminCredentials(callback)` behavior. Manual-only bounded call `withAdminCredentials(callback, {deadline, signal?})` clips to the original startup authority and combines cancellation. Provisioner uses this argument only for manual payments, ordinary and selected3DS alike. Keep captured payment topology checks before acquisition and inside callback, actual bootstrap full proof before exposure and all nonmanual behavior unchanged.

- [x] Read investigation `.superpowers/provision-initial-investigation-2026-10-26.md`, spec and actual bootstrap/provisioner. Add real-component tests using existing guarded synthetic fixture, not permissive bootstrap stubs.
- [x] RED: virtual command latency230ms reaches the first provisioning HTTP call under the original10s credential child once redundant proof is eliminated. Keep a slower initial ownership timeout negative control with zero HTTP/credential exposure. Assert behavior/proof obligations, not magic implementation command counts.
- [x] RED: child abort/expiry cancels actual credential verification and denies late callback; original startup abort still dominates an unrelated active child; extended/malformed/expired limits cannot reveal credentials; no second/future credential work after failure. Exercise payment snapshot substitution before/at callback and callback-only/nonmanual controls.
- [x] Run meaningful failing tests before production edits and record command/output. Implement the smallest deduplication and bounded manual credential changes; no deadline increase or check removal.
- [x] GREEN: covering tests, complete bootstrap/provisioner files and related controller regression at original30s/concurrency1 limits. Self-review and write task-1-report.md with RED/GREEN, changed paths and remaining original-runtime uncertainty. No commits. Independent task review must pass before Task2 production changes.

### Task 2: Finite initial-boundary diagnostic and operator record

**Files:** Modify `infrastructure/stripe-form-diagnostic-provision.mjs`, `infrastructure/stripe-manual-payment.mjs`, their `.test.mjs` files and existing manual-payment declaration if needed; narrowly append `docs/local-development.md` and `docs/stage-5a-acceptance.md`. New pure module is unnecessary unless inert import safety requires it; keep existing organization.

**Interfaces:** Export pure `formatManualPaymentProvisionDiagnostic(error)` from the provisioner module. It emits exactly a fixed JSON line with tag `stripe_manual_payment_provision_diagnostic` and stage ADMISSION/OWNERSHIP/PROVIDER_BINDING/ADMIN_CREDENTIALS/UNKNOWN,≤256UTF8bytes. Brands stay private/module-owned; external errors are never inspected. Controller captures only constructor/prepare failure, emits after cleanup before final report via optional `writeProvisionDiagnostic`, wired to direct entry stderr. Final stdout report and auth diagnostics unchanged.

- [x] RED: actual factory/prepare boundary failures map to each named stage; unbranded primitive/object/accessor/proxy failures mapUNKNOWN without reads. Nonmanual compatibility and successful prepare produce no stage emission. Include secret canaries in errors, not expected output.
- [x] RED: controller/direct entry emits at most once, after independent gateway/provisioner/listener/runtime cleanup and before final report. Auth and later bind/financial failures emit no provision diagnostic. Reject/stall/expired output remains exit1/PENDING,≤1s clipped to original cleanup; no second write or financial retry. Ordinary and3DS report shape preserved.
- [x] Implement minimal WeakMap classification and bounded diagnostic emission. Inert imports remain inert; update exact synthetic loader allowlist only if genuine new static transitive source imports need it.
- [x] GREEN: full related manual/auth/bootstrap/provision/CLI regression and full Docker-free infrastructure test selection, original limits. Human docs explain synthetic reproduction vs unproven historical cause, fixed stages and unchanged authority. Preserve pre-existing acceptance text byte-for-byte before append.
- [x] Write task-2-report.md, independent task review; root produces combined diff package for broad final review, verifies current tree and test outputs. No Docker/backend/browser/Sandbox run follows and5A remainsNOT_ACCEPTED.
