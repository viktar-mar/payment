# Persisted failed-renewal observation

## Goal and scope

Connect the descriptive declined-renewal mapper at `0fe5c2a` to the existing owned debt service: one fresh persisted FAILED read, verified provider graph and clocks, then actual atomic failure publication. Continue offline with subagents, tests and independent review. No local Docker, Stripe/Sandbox, financial operations, Python/install, local SQL/application contexts, original archives/keys or old operations/DISPATCHING. Hosted CI with fresh fixtures is a separate authorized publication check. Stage 5B remains NOT_ACCEPTED.

No bean/registry/route/scheduler/exporter/launcher, new admission, time producer/advance, method setup/customer confirmation, recovery reservation or recovered-CASH producer is enabled. Existing successful-renewal and ordinary/default guards remain closed. Preserve migrations and historical evidence.

## Authenticated application context

`OwnedFailedRenewalService` already owns correct lifecycle locking, caller transaction refusal, current admission/read authentication and terminal stops. Reuse it; do not duplicate locks inside the Stripe adapter or use the success-only upstream `requirePaid` after failed-branch admission.

Add public nested immutable `FailedReadContext(PaymentAccount account, Subscription subscription, StripeBillingBindingPort.CustomerBinding binding, Instant billingAt)` and `inspectFailedRead(Admission, ReadClaim)`. An explicit constructor overload appends `StripeBillingBindingPort bindings` after the existing Clock; keep the old constructor and every existing method's behavior. Without an explicit binding port, the new inspection/context-aware publication fails closed.

Inspection requires no ambient transaction, owns one `requiresNew`, and writes no facts, receipt or stop. Inside it reuse `current(admission)`, `requireNoFinancial`, and existing lock order: sorted operations, Site/Customer, Account, Subscription, sorted bills. Require exact persisted FAILED claim/admission/fence, original pre-debt revision and active projection, operational UTC in `[startedAt, deadlineAt)`, immutable owner/terms/receipt identity and isolated time. Load and validate binding account/site/customer/id/creation-operation/provider reference; return its full value including version. JDBC V26 currently makes bindings immutable at version0; full equality also protects explicit ports.

Capture the authenticated billing sample in private Locked state. For this new inspection/observer path require `ISOLATED` and billingAt equal to admission READY2 target (`Instant.ofEpochSecond(admission.ready().ack().advance().target())`). Do not tighten legacy failure/recovery entry points that intentionally accept other isolated grace times.

Add `publishFailure(Admission, ReadClaim, FailedEvidence, FailedReadContext expected)`. It uses the same guarded publication transaction as the original method, reauthenticates the read, reconstructs the entire context and compares exact equality before applying facts. A caller record supplies no authority without all live/persisted checks. Keep the original three-argument method unchanged through a private common core. Publication failure/receipt failure rolls back before the service's own separate terminal-stop transaction.

## Actual fact time constraint

The fact service independently samples billing time, so an earlier context comparison alone does not pin the time actually used to create debt. Append optional `SubscriptionBillingTimePort.Sample expectedBillingSample` to `ApplySubscriptionSnapshotUseCase.Command`; preserve existing seven- and six-argument constructors with null default. Immediately after FactService obtains its authenticated sample, reject a non-null unequal expectation with fixed `BILLING_TIME_CHANGED` before fence consumption or effects. The expectation only restricts a fresh authenticated sample; it never overrides or selects time or confers isolated authority. Old commands retain their prior behavior.

Only the new context-aware failure publication supplies the expected ISOLATED sample. This closes both the final inspection-to-publication gap and the fact service's resampling gap without changing ordinary/recovery semantics or introducing a mutable global clock.

## Explicit observer

Create `StripeBillingOwnedFailedRenewalObserver` in the existing stripe/billing adapter package. Public constructor takes `OwnedFailedRenewalService`, `StripeCredentialResolver`, `StripeClientFactory`, operational `Clock`; package-private overload adds `LongSupplier nanos`. `observe(OwnedFailedRenewal.Admission)` returns the persisted `OwnedFailedRenewal.Failure`, never a descriptive mapper result or assumed success.

Flow:

1. Sample entry UTC (microsecond normalization) and monotonic time, generate invocation UUID, call `beginFailedRead` exactly once. Authenticate returned phase/admission/invocation/fixed30-second deadline and startedAt between normalized entry and current UTC. No adoption or successor read.
2. Call inspection for the immutable context, resolve credentials/build client only afterward. Before and after every SDK request, inspect again and require exact context equality; all inspections and locks remain on the caller thread, outside provider IO.
3. Retrieve and verify the exact owned READY2 clock, run the failed mapper exactly once with its non-null executor, retrieve and verify that clock again. Check ID/object/Sandbox/not deleted/name/created/deletes-after/status/frozen-time and any advancing target exactly as the existing paid observer does. The mapper receives the original single published price and current authenticated ownership.
4. Reinspect, check budget, sample acceptance time, validate read acceptance of observedAt, construct FailedEvidence. Call the new context-aware publication outside the observation-failure catch; return its persisted receipt directly without a post-commit deadline failure.

One shared executor counts at most100 complete SDK calls, including clock reads; retain mapper page limits. Total evidence acceptance is bounded by both persisted UTC deadline and 30 seconds of elapsed monotonic time from entry. Every SDK call has at most2 seconds or remaining allowance, explicit API `2026-09-30.endive`, connect/read timeouts and zero retries. Follow the existing complete-SDK-call FutureTask/virtual-thread pattern; enforce actual System.nanoTime as well as injected monotonic time and cancel on completion/failure. No polling/sleep/retry or financial request. A slow database lock can delay return; it cannot extend evidence acceptance or move database work into provider workers.

`beginFailedRead` and publication keep their existing service-owned guarded stop behavior. Catch only intermediate observation work: after inspection transactions unwind, stop once with AUTHORITY_DRIFT for local context/read disagreement or UNCERTAIN for unsupported/transport/timeout provider evidence. Preserve interrupt status. Public failure diagnostics contain only fixed source-owned code/message, never provider/credential response content; if stop also fails, attach only a fixed safe suppressed description. No raw logs or new diagnostic collection. A failed stop does not authorize replacement authority. Existing duplicate-begin terminal behavior remains unchanged and must not receive a second observer stop.

## Verification and evidence

TDD with explicit tests of behavior, not reflection-only API presence. Pure tests use real service behavior and handwritten transaction/store/SDK-memory ports, no sockets or application context. Verify actual failure publication, no cash/new financial operation, original payment preserved; claim/fence/binding/account/subscription/time drift, fixed UTC and monotonic limits, clock changes, SDK malformed/transport/error, request budget, timeout/interruption, no IO in transaction, stop after unwind, no double stop and no false post-commit failure.

Author real JDBC/FactService hosted composition for inspection and the new context/time guard, and a full observer-to-debt case using fresh genuine initial/READY/boundary provenance and controlled SDK responses. Compile these locally only; hosted CI may execute them. Include the actual FactService resampling mismatch (even if a service sample matched), proving rollback/no writes/failure receipt. Preserve old service/mapper/success/ordinary regression tests and execute cached explicit pure selectors only locally. Update current guide/matrix with precise local versus hosted versus native evidence. No native acceptance or delivered Stripe callback is established by synthetic fixtures.
