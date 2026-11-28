# Bootstrap proof timeout and bounded cleanup

## Evidence and objective

The saved combined first-payment/renewal attempt failed before fixture creation with
`START_DEMO_SITE_HEALTH_PROOF_TIMEOUT cleanup=UNCONFIRMED`. Four owned containers
remained running and were subsequently stopped by a separate, verified finalizer.
That finalizer does not turn the original automatic cleanup into a success.

The startup code covers infrastructure/container ownership inspections, including
a per-inspection 10-second process timeout and cancellation at the original run
deadline. It does not prove that the demo application was unhealthy, nor identify
which inspection timed out. No raw startup or cleanup logs are available in the
approved evidence. The historical native cause remains undetermined.

Source inspection separately identifies a reproducible lifecycle defect: the
owner's canceled bounded START can return before the actual bootstrap invocation
settles. Cleanup then encounters the bootstrap's necessary busy guard, and its
single memoized cleanup permanently finishes unconfirmed even if the invocation
settles within the unused cleanup allowance.

Repair that lifecycle defect and add exact timeout coverage. Do not speculate
about an environmental fix, extend timeout budgets, or add another diagnostic
framework.

## Binding constraints

- Work offline in the existing `codex/stripe-billing-5b` worktree. No Docker
  commands, daemon/container access, Stripe/Sandbox, app/browser runtime, SQL,
  Python, installs, key files, or old archives. No push or hosted CI in this task.
- No new financial operations, retries, resets, or changes to old payments,
  refunds, DISPATCHING/UNKNOWN operations, native reports, or original ledgers.
- Tests must use injected in-memory processes, files, ownership projections and
  clocks only. Root audits/runs tests and owns Git writes. Workers do not spawn
  further agents.
- Retain the actual child callback-plus-close settlement requirement, bootstrap
  busy guard, exact ownership/topology proofs, finite diagnostic vocabulary,
  first-failure retention and single-use financial authority.
- Track the actual dispatched START settlement, not a synthetic timer or the
  outer canceled race. After listener shutdown, wait for it within the SAME
  captured cleanup deadline before the existing single stop attempt. Settlement
  is not success or ownership proof. No START replay, stop retry loop, renewed
  deadline, or late stop after expiration.
- Keep cleanup's total 120-second allowance, other cleanup step ceilings and
  original run-deadline clipping. If settlement cannot be confirmed in time,
  finish UNCONFIRMED and prevent late mutation. Cleanup failures retain the
  original startup diagnostic; no dependency error properties or raw logs leak.
- Both initial-payment and combined first-renewal owners share the repaired
  ordering. No later runtime/fixture/browser/CREATE/CONFIRM/advance is reached
  after a failed or canceled START.

## Acceptance

1. A deterministic failing regression on the old owner demonstrates cleanup
   finalizing before an already dispatched START settles. After the fix, release
   within the original allowance permits one exact owned stop sequence and
   confirmed cleanup, while the overall attempt remains failed.
2. A held START through the original cleanup deadline stays unconfirmed; later
   release cannot dispatch stop, acknowledge an intent, or change the result.
   Listener failure, ownership drift and failed/nonrunning proof remain failures.
3. An inert native-child-adapter regression reproduces the exact demo-site
   HEALTH_PROOF timeout with an unexpired run deadline. It asserts the unchanged
   10-second inspection cap, no next-service or financial dispatch, original
   DISPATCHING intent, trusted fixed diagnostic and actual child-close ordering.
4. Related owner/bootstrap/limits/ownership/renewal regressions pass without
   weakening assertions. Independent task and final reviews find no unresolved
   important issue. Record limitations honestly: no native rerun or 5B acceptance.
