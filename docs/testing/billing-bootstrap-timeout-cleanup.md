# Bootstrap timeout and bounded cleanup regression

The saved attempt failed before fixture creation with
`START_DEMO_SITE_HEALTH_PROOF_TIMEOUT cleanup=UNCONFIRMED`. Four owned containers
remained running until a separate verified finalizer stopped them. That later
stop does not upgrade the original automatic cleanup or alter its saved evidence.

The approved evidence does not identify the inspection that timed out, its
duration, the child-close timing, or the failed automatic-cleanup boundary.
It does not establish an application health failure. The historical native
startup and cleanup cause remains **NOT_DETERMINED**.

## Proved source defect and repair

The owner races START against cancellation. Cancellation could end that outer
race while the actual bootstrap invocation still owned a pending child.
Bootstrap correctly refused stop while busy, but the owner's memoized cleanup
then permanently completed unconfirmed even if START settled within its unused
cleanup allowance.

Both initial-payment and first-renewal owners now retain settlement of the
actual START invocation separately from the canceled race. After runtime close
and listener stop, cleanup waits for that settlement before attempting the
existing bootstrap stop. Resolution and rejection both establish settlement;
neither grants success, acknowledgement, or ownership authority. The original
startup failure and diagnostic remain unchanged.

The wait and stop share the one previously captured cleanup deadline:
`min(cleanup start + 120000, original run deadline + 120000)`. Listener/runtime/
fixture cleanup retains its 30000 ms per-step ceiling. Bootstrap stop retains
its 120000 ms maximum, clipped to that shared deadline. If settlement cannot be
established in time, cleanup remains unconfirmed and never schedules a late
stop. The bootstrap busy guard, callback-plus-close contract, exact ownership
proofs and original DISPATCHING evidence remain intact. There is no START replay,
financial retry, timeout increase, or new diagnostic vocabulary.

## Offline regression evidence

The root's audited RED run on unchanged product source found the first-payment
owner already CLOSED with one stop invocation before the held START settled.
The expanded RED selection ran 11 cases: nine lifecycle assertions failed and
the two native-wrapper timeout/ownership cases passed, with no cancellations or
skips. These are synthetic offline tests, not native scenario results.

New coverage includes both owner modes, late resolution and hostile rejection,
listener failure, single-use refusal, and the original cleanup ceiling after
a delayed cancellation. The real-bootstrap control interruption test preserves
the first diagnostic and DISPATCHING intent while releasing its synthetic
command inside the allowance.

The injected execFile/EventEmitter regression selects the network inspection
after demo-site start. At mock time 1000 with original run deadline 121000,
it asserts the unchanged 10000 ms inspection timeout and an unaborted signal.
The timeout callback alone cannot complete START: child close is separately
required. The result is exactly `START_DEMO_SITE_HEALTH_PROOF_TIMEOUT`; receiver,
runtime, fixture and financial work never start. Cleanup stops only the exact
recorded running IDs, and a synthetic ownership-label drift refuses all stops.
The original demo-site DISPATCHING intent is never acknowledged by cleanup.

Root's post-repair GREEN verification passed: `green-regressions.tap` contains
11/11 passing targeted cases, and `green-related.tap` contains 1155/1155 passing
owner, bootstrap, first-renewal, options and entrypoint tests. The related run
completed in 3561 ms with no failures, cancellations, skips or warnings. Root
also confirmed `git diff --check` was clean.

Root used `/opt/homebrew/bin/node --test --test-timeout=30000
--test-concurrency=1 --test-reporter=tap` with explicit inert test files and
built-in TypeScript stripping; no extra loader or installation was needed.
Independent task review approved specification compliance and code quality;
the independent final whole-fix review also approved, with no findings in either
review. Reviewers verified all six source-package hashes. Root rechecked the
original completion and runtime evidence hashes and confirmed they are unchanged.
The implementer executed no tests or native commands.

Native validation: **NOT_RUN**. 5B acceptance: **NOT_ACCEPTED**.
