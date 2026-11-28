# First-payment wait snapshot implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development task-by-task, with independent task reviews and a final review. Steps use checkbox syntax.

**Goal:** Report one last Node-observed SUBMIT/TOKEN stage at a failed OBSERVE wait while immediately revoking continuation.
**Architecture:** A private WeakMap observer publishes finite stage changes through the dedicated page's optional nonblocking binding. The runner validates a four-transition sequence, freezes its local hint on first failed wait, and aborts a journey-local signal. Existing closed diagnostic codes carry the observation.
**Tech Stack:** Existing TypeScript, Node tests, installed Vitest/jsdom, inert transports.
**Spec:** [Reviewed design](../specs/2026-11-03-first-payment-timeout-snapshot.md); base `b91982ca143381796ba7433b7688731f295cb883`. Planning only until implementation starts under its own ledger.

## Global Constraints

- LAST_OBSERVED is historical Node-received information, never live provider state, cause, dispatch, cash or acceptance proof.
- Preserve original absolute deadline, OBSERVE wait cap45000ms and close/drain cap15000ms. No new timers, post-timeout reads or provider awaits for diagnostics.
- Preserve first failure, one original dispatch, current guards, validation correction, next-action rules, existing public SDK/API/owner result shapes and the160-character final diagnostic envelope.
- No Docker/daemon, Stripe/Sandbox/network, real browser, Vite server/build, Java/Maven/SQL, install, Python, archives, keys, env.local or raw native/browser logs during implementation.
- Use installed Node/Vitest/jsdom and inert transports. No backend, payment policy, dependency/lock, callback or cash-evidence changes. Consumed runs and DISPATCHING stay untouched.
- Existing isolated worktree only; verify clean source and create/use a codex/ implementation branch before product edits. Preserve all diagnostic artifacts and reports. No push/native run as part of this plan.

## Review Focus

- A token resolves immediately after wait rejection: local abort must precede any new confirm dispatch (Task2).
- A stage message is delayed/reordered: historical wording, bounded fallback and first-error freezing must remain honest (Tasks1/2).
- A diagnostic binding is missing, throws, rejects or never resolves: the real SDK's confirmation/cancellation cannot depend on it (Task1).
- A parent abort or navigation overlaps wait/close: preserve primary error, exact deadline and cleanup confidence (Task2).
- Internal helper identity differs between SDK/page bundles: retain the existing shared source import/alias, no dist/package copy (Task1).

### Task 1: Emit private pre-CONFIRM stage observations without controlling payment

**Files:**
- Modify `frontend/sdk/src/subscriptions/stripe-payment-attempt-diagnostic.ts`.
- Modify `frontend/sdk/src/subscriptions/stripe-payment-attempt-driver.ts`.
- Modify `examples/demo-site/src/billing-first-payment.ts`.
- Test `frontend/sdk/src/subscriptions/stripe-payment-attempt-diagnostic.test.ts` and `frontend/sdk/src/subscriptions/owned-first-payment-page.test.ts`.

**Interfaces:**
- Consumes the unchanged public handle, failure helper and actual-driver/page fixture.
- Produces private `StripePaymentAttemptPending`, `setStripePaymentAttemptPending(handle:unknown,value:unknown):void`, and `observeStripePaymentAttemptPending(handle:unknown,listener:(stage:StripePaymentAttemptPending|null)=>void):()=>void` with the exact spec behavior.
- Produces optional page-to-controller calls `__firstPaymentPending(stage)` carrying only SUBMIT, TOKEN or null; registration has no initial replay and occurs before enabling Pay.

- [ ] **Step 1: Write assertion RED before product edits.** Add an actual-driver test whose deferred submit produces literal `['SUBMIT']` for an observer installed on the returned handle, with zero token/client calls. A missing proposed function may be detected safely and represented by an empty event list so RED is the expected event assertion, not an import/setup error. Save source hashes and full RED output.
- [ ] **Step 2: Verify RED.** Run `pnpm --dir frontend/sdk test --run src/subscriptions/stripe-payment-attempt-diagnostic.test.ts`. Expected: the new literal stage assertion fails on the unchanged implementation.
- [ ] **Step 3: Implement the private observer and driver updates.** Use separate WeakMaps, one observer/no replay, primitive validation, null clearing, change deduplication, synchronous callbacks/disposal. Announce/clear only around existing guarded SUBMIT/TOKEN awaits and destroy. Never alter failure provenance or await an observer.
- [ ] **Step 4: Connect the actual dedicated page.** Import the same source helper, subscribe after handle assignment before Pay, forward through the optional own-data-property binding without awaiting it, handle rejection without logging, dispose on stop. No new public API, DOM state or provider call.
- [ ] **Step 5: Cover real behavior and failure modes.** Assert `['SUBMIT',null,'TOKEN',null]` across two deferred awaits and one original confirm; pending cancellation/late completion causes zero late confirms. Assert no replay, second observer no-op, correct disposer, hostile key/value no inspection, missing/throwing/rejected/never-settling transport, real-page one Pay, terminal categories, successful OBSERVE and shared helper identity. Assert failure reader/public handle remain unchanged.
- [ ] **Step 6: Verify.** Run the two named SDK files and `pnpm --dir frontend/sdk typecheck`. Expected: all pass, no warnings or live transport. Record commands/output/counts/hashes; perform independent task review.
- [ ] **Step 7: Commit only the task's explicit files.** Use message `fix: observe private pre-confirm stages without blocking payment` after the task checks/review pass. No push.

### Task 2: Freeze a local wait hint and revoke browser continuation before yielding

**Files:**
- Modify `examples/demo-site/tests/support/stripe-billing-first-payment.ts`.
- Modify `infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs`.
- Modify `docs/local-development.md` with LAST_OBSERVED semantics and historical limits.
- Test `examples/demo-site/tests/support/stripe-billing-first-payment.test.ts`, `examples/demo-site/tests/support/stripe-billing-first-renewal.test.ts`, `infrastructure/stripe-billing-first-payment-browser-diagnostic.test.mjs`, `infrastructure/stripe-billing-first-payment.test.mjs`, `infrastructure/stripe-billing-first-renewal.test.mjs` as needed for both scopes.

**Interfaces:**
- Consumes Task1's exact optional binding and primitive sequence.
- Produces exactly `BROWSER_WAIT_OBSERVE_LAST_OBSERVED_SUBMIT` and `BROWSER_WAIT_OBSERVE_LAST_OBSERVED_TOKEN` in the existing closed code set. `browserFailure(code)` and owner/result/envelope signatures stay unchanged.
- Runner keeps a private finite receiver and one composed journey-local signal under the original absolute deadline; no new public helper or framework.

- [ ] **Step 1: Write RED for actual runner behavior.** Through the captured real pending binding, provide valid prepare/READY/Pay and SUBMIT (then a second case SUBMIT,null,TOKEN), reject the original wait, and expect the exact LAST_OBSERVED code. Hold/release the original provider/bridge after rejection and assert its scoped signal was already aborted and no new confirm occurred. Expected old-code failure: generic code and/or absent local cancellation.
- [ ] **Step 2: Verify RED.** Run `node --test examples/demo-site/tests/support/stripe-billing-first-payment.test.ts`. Expected: genuine assertion failure for the new observation/cancellation behavior, not a changed fixture refusing the newly named binding. Preserve exact existing prepare-binding guards in the fixture.
- [ ] **Step 3: Implement scoped cancellation and finite receiver.** Add one AbortController composed with parent signal; pass scoped limits at all existing browser/bridge/port handoffs. Install the additional synchronous binding; enforce original main-frame/origin/prepare/READY/Pay and the spec's four transitions. Invalid open observation permanently becomes unavailable; pre-Pay/late events are ignored and never affect financial authority.
- [ ] **Step 4: Implement the first-failure cutoff.** In one synchronous latch mark failure/abandonment, freeze the local enum, mint/store the first error, then abort before any await/output. Specialize only rejection of the original OBSERVE wait while parent is live; terminal/other errors keep their old codes. Earlier failures always win. Reuse the one context-close Promise and unchanged cleanup bound. Add no post-timeout page evaluate or provider read.
- [ ] **Step 5: Wire the exact two codes and documentation.** Existing owner/parser/reporter uses the shared closed code set; do not widen regex/schema or add result fields. Document last-received/stale/missing semantics and no retrospective cause/acceptance claim. Verify each final line stays under160characters for both original and combined scopes.
- [ ] **Step 6: Pin boundary cases.** Test no marker, cleared state, pre-Pay/late/reordered/duplicate/wrong-frame/wrong-origin/hostile value, observer absence, delayed messages, parent abort/expiry, earlier bridge/Pay failure, closing while route/prepare is pending, late newContext resolution and close/drain timeout confidence. Confirm no late CREATE/CONFIRM and no added post-timeout evaluate. Preserve existing success, terminal reader and generic timeout cases.
- [ ] **Step 7: Run related offline regressions.** Run the support first-payment/first-renewal Node tests and the three named infrastructure Node files. Run `pnpm --dir frontend/sdk test --run`, `pnpm --dir frontend/sdk typecheck`, and `pnpm --dir examples/demo-site exec tsc --noEmit`. Audit test/import closure before execution; do not discover opt-in spec/browser tests through a broad glob. Expected: all chosen offline suites green; report any existing failure by name, do not weaken assertions or increase deadlines.
- [ ] **Step 8: Independent task and final change review.** Review exact base-to-final diff, frozen first-failure/cancellation ordering, finite trust boundary, event timing and source-helper identity. Preserve evidence and all prior run artifacts.
- [ ] **Step 9: Commit the task's explicit files.** Use message `fix: freeze last-observed wait stage before journey cancellation` after checks and reviews pass. No push or native run.

## Planning verification and future limits

Self-review the spec/interface/value coverage and relative links, check whitespace
and closed-label length, then perform a fresh builder check before handing over
this plan. These checks do not validate an implementation. No new Sandbox attempt
is included; first-payment/renewal/native acceptance requires separate evidence
and the applicable explicit runtime authorization.
