# Bootstrap timeout and cleanup implementation plan

> Use subagent-driven development. The user authorized continuous offline work,
> tests and independent review. Do not pause for plan approval.

**Spec:** `docs/superpowers/specs/2026-11-01-bootstrap-proof-timeout-cleanup.md`

**Goal:** Repair abandoned cleanup after a settling canceled START and cover the
exact reported startup timeout without claiming an unproved native root cause.

## Global Constraints

All Binding constraints and Acceptance requirements in the spec apply verbatim.
Only source and approved saved sanitized evidence may be read. Workers may edit
the task files and their own report; root audits/runs all inert tests and owns
Git writes. No Docker/Stripe/runtime/Python/install/keys/archives or financial
actions, no push/hosted CI. Preserve original native evidence and ledgers.

### Task 1: Bounded settlement before the existing stop and exact timeout regression

**Files:**
- Modify `infrastructure/stripe-billing-first-payment.mjs` (shared owner).
- Modify `infrastructure/stripe-billing-first-payment.test.mjs`.
- Modify `infrastructure/stripe-sandbox-bootstrap.test.mjs`.
- Add `docs/testing/billing-bootstrap-timeout-cleanup.md` with evidence limits,
  repaired ordering, regression results and explicit native NOT_RUN status.

Read the spec and the two reconnaissance reports under
`.superpowers/investigations/2026-11-01-demo-health-proof-timeout/`.

1. TESTS ONLY first. Reproduce cancellation before actual START settles in both
   owner modes using gates and mocked time. Prove no premature owner completion
   or stop, release within the original cleanup ceiling, expect one stop and
   confirmed cleanup. Include original-ceiling expiration with no late stop,
   unchanged listener failure result, hostile late rejection, and single-use
   nonacceptance. Adapt the existing `external-command` control interruption
   test to exercise settlement within the allowance, preserving its immutable
   diagnostic/DISPATCHING assertions. Do not let a fake held command hang tests
   for a real cleanup allowance.
2. Add an injected execFile/EventEmitter test in the bootstrap suite reproducing
   `START_DEMO_SITE_HEALTH_PROOF_TIMEOUT` at an inspection after demo-site start,
   with unchanged timeout 10000 and run deadline still valid. Assert that the
   real wrapper waits for child close, startup halts before receiver/fixture,
   exact IDs are the only possible stops, and DISPATCHING remains unacknowledged.
   No real process or connection is permitted. Cover refusal of ownership drift.
3. Report TESTS_READY and wait for root's audited RED run before product edits.
4. After root's RED confirmation, track the actual START promise settlement
   separately from the outer `billingBounded` race. Drain it after listener stop
   and before bootstrap.stop within the ONE already captured cleanup deadline.
   Promise resolution/rejection both establish settlement; never inspect error
   objects, swallow a startup failure as success, or grant new runtime/financial
   authority. If drain times out, do not invoke a late stop. Preserve cleanup
   ordering and ceilings for the other resources and keep the busy guard intact.
5. Self-review and provide GREEN-ready source. Root runs focused and related
   regressions, sends issues to the same implementer, then freezes a review
   package containing all new/tracked task files. An independent reviewer checks
   spec compliance AND quality, followed by an independent whole-slice review.
6. Document proved source defect separately from the undetermined original
   inspection/cleanup cause. Native validation is NOT_RUN, 5B NOT_ACCEPTED.
   Root records test results and creates a local commit after clean review.

**Verification:** Root audits test imports/injected closures, then runs only
explicit inert Node test files (with the existing TypeScript loader if needed).
Start with the new failing case, run the covering owner/bootstrap tests, and
broaden only to related limits/ownership/first-renewal/entrypoint tests. No package
script, Maven, browser, Docker or native scenario is to run implicitly.
