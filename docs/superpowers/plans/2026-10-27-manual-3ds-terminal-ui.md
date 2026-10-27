# Manual 3DS terminal UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire the one-shot manual payment form when the SDK returns to ready after its consumed confirmation, without presenting an unverified payment success.

**Architecture:** Keep the existing manual page, SDK, permit and controller. A post-attempt ready event ends the page with its existing neutral server-report message; pending human challenge/checking/processing/requires_action keep the same provider connected. No extra polling, backend route or SDK/public behavior change.

**Tech Stack:** TypeScript, existing SDK and native Node/JSDOM/Vite synthetic support tests; no new dependencies.

**Spec:** Current human request (offline diagnosis/fix with subagents, tests, independent review), amending `docs/superpowers/specs/2026-10-26-manual-3ds-design.md` only for post-attempt ready retirement. All other design constraints remain binding.

## Global Constraints

- Offline only: no Docker or Sandbox invocation, external provider/browser resources, new payments/refunds, archived/private runtime data reads, installation or new dependencies.
- One Load/permit/Pay/provider confirmation; no rearm, replacement session, new polling or deadline extension; no card iframe/error/URL inspection or credential persistence.
- Preserve the actual pending 3DS challenge and intermediate checking/processing/requires_action generation; retire on post-attempt ready or existing terminal/expiry/close paths. Ready alone must never claim payment success; the native server report remains authoritative.
- Preserve ordinary manual behavior, SDK and backend behavior, existing server/provider/journal/callback/3DS/closure verification and report formats. Keep Stage5A not accepted.
- Work only in `/private/tmp/payment-5a-offline.Dm0qkj`; preserve unrelated untracked recovery/plans and all prior evidence. No push, merge, runtime or cleanup in this task.

## Review Focus

- Ready emitted by initial resume must not retire the not-yet-attempted form.
- A still-pending provider confirmation/challenge must remain visible/connected.
- Repeated-action and processing-then-action reads after provider completion retire neutrally, not as success.
- Late provider/read/event completion after retirement cannot resurrect the form or dispatch another permit/confirmation/read.
- The actual emitted Vite page must exhibit the same behavior as the source graph, without altering ordinary build entries.

## Task 1: Retire post-attempt ready and pin the lifecycle

**Files:**
- Modify: `examples/demo-site/src/manual-payment.ts`
- Test: `examples/demo-site/tests/support/manual-payment.test.ts`
- Test: `examples/demo-site/tests/support/manual-payment-build.test.ts`
- Update: `docs/superpowers/specs/2026-10-26-manual-3ds-design.md`, `docs/local-development.md`, `docs/stage-5a-acceptance.md`

**Interfaces:** Keep `mountManualPaymentPage(root, dependencies): {close():void}`, existing events, SDK one-shot lease and native reports unchanged. Consume existing post-attempt ready; produce only existing neutral finish/close behavior, not new success evidence.

- [ ] Write regression expectations for real SDK sequences: completed fake challenge -> repeated REQUIRES_ACTION -> SDK ready, and PROCESSING -> repeated REQUIRES_ACTION -> ready. Expect form removed, exactly one provider destruction, existing neutral server-report message, no success claim or repeat capability. Keep challenge-pending/initial-ready assertions and late-callback, one-Pay/permit, zero-secret-read/storage invariants.
- [ ] Run the source support test first and retain expected RED output; do not modify implementation until it fails for the stale-form behavior. Existing baseline is green.
- [ ] Apply the smallest manual-page lifecycle fix. Do not modify SDK, provider result processing, transport, native verification or introduce status polling.
- [ ] Adapt obsolete assertions preserving spent-ready forms and exercise both corrected sequences in the actual emitted page build test. Retain ordinary-mode and pending/intermediate challenge coverage. Update the old design/docs language so it no longer mandates keeping ready forever. Record the successful historical manual3DS result separately from this untried offline UI patch, never full5A acceptance.
- [ ] Run complete native demo support suite once, SDK unit suite once and SDK/demo typechecks + demo build using cached tools only. Main controller separately runs the allowed Docker-free infrastructure selection; ordinary Playwright discovery is list-only, not backend journeys.
- [ ] Self-review and report RED/GREEN commands/results, changed files and concerns. Do not commit/push or invoke hooks; main controller will create a scoped review package from the working diff, obtain task and final independent review, and retain the finished local patch.
