# Billing lifecycle time and UTC receipt separation

Stage 5B offline prerequisite; native Billing remains **NOT_RUN**, full 5B **NOT_ACCEPTED**. This is not authorization to execute provider operations or TestClock advancement.

## Decision

Separate billing evaluation from operational timestamps without replacing the application Clock or extending the Stage4 Fake bean-injection list. The current callback adapter validates access, renders state and schedules delivery using one instant. Future billing evidence therefore postpones UTC delivery. Fact application likewise mixes period/grace decisions with lease and audit timestamps.

Implement a finite prerequisite: paid-period application, unpaid renewal and grace expiry with an exact synthetic owner whose billing time differs from UTC. The ordinary production adapter has no override and returns the supplied UTC instant. Only a test fixture supplies an isolated account/Site/Customer override. No production setting, endpoint, native registration, provider mutation, clock receipt or new database table is introduced.

## Contract

1. `SubscriptionCallbackPort.snapshot(subscription,event,access,enqueuedAt)` retains its existing signature. `access.evaluatedAt` and `event.occurredAt` are the explicit billing snapshot; they must agree. Validate the complete access decision at that time, render display state there and retain the event occurrence in JSON. Persist outbox eligibility/creation/update at `enqueuedAt`. Delivery signatures, claims, leases, retry and completion retain their own UTC clocks. Existing callers supplying equal times and Stage4 behavior remain unchanged.
2. Add a small `SubscriptionBillingTimePort` which samples by exact subscription ownership and receives the existing clock instant as fallback. Default bean returns that instant unchanged. Its mandatory unscoped-query guard permits ordinary operation; an isolated test implementation rejects unscoped list/global deadline selection. No ambient mutable global clock, arbitrary registry, environment switch or provider call.
3. FactService samples operational UTC once, validates its observation/sweep fence with UTC, then samples billing time for lifecycle and access decisions. Object/BillingRecord/operation/subscription audit and journal receipt timestamps remain UTC; journal occurrence is billing time and callback enqueue is UTC. Introduce dual-time domain overloads only where eligibility and updatedAt currently share one argument (`confirmPaid`, `renewalFailed`); old overloads delegate equal instants. Preserve original fingerprints and all ownership/cash proof checks.
4. Query exact get/access evaluates at the matching owner's billing time. Unscoped list and global expired-grace selection fail closed when the port declares isolated time. `DeadlineWorker.expire(exact Claim)` validates the locked owner/revision and unpaid bill, uses billing time for grace eligibility, UTC for receipt/enqueue. Unsupported mutation/native-clock paths remain disabled and are not claimed by this slice.
5. Prove synthetic paid → failed renewal → grace expiry using actual application/SQL/callback integration in hosted CI. A +35-day billing shift must not defer UTC delivery, expire/reclaim a UTC lease, enable an early retry or change security/session expiry. Ordinary/Stage4 regressions remain intact.

## Boundaries

No local Docker, DB/SQL, Spring/full application, Testcontainers, browser or Stripe/Sandbox. No new payments/refunds, private keys/archives/recovery reads, Python, installation/dependency upgrade or diagnostic framework. Audited exact pure-JUnit tests may run through cached offline Maven; integration tests execute only in existing hosted CI. Preserve historical EUR13/DISPATCHING records and existing untracked paths. Ordinary reviewed commits/push and exact-head hosted CI remain authorized.

Actual TestClock creation/association/readiness, clock-specific immutable request provenance, resource/event timestamp interpretation, mixed-owner list filtering and provider mutation/quote/cancellation paths are separate later work. The initial-only native runner remains `INITIAL_NON_CLOCK`. Public Stripe docs do not establish a universal wall-versus-simulated contract for every `created` field; no current proof window is weakened or guessed.

An isolated sample must identify its mode explicitly even if its instant equals UTC. Under that mode this slice admits only ordinary INITIAL/RENEWAL facts for unchanged terms, without trial, pending plan change, cancellation, resume or other command proof. Reject unsupported fact shapes before writes, and omit financial permitted actions from the exact read view. This keeps unfinished mutation time semantics unreachable through the new seam. Ordinary samples retain all existing behaviors. Global list/deadline selection must invoke the mandatory guard before querying, rather than detecting an override only after filtering rows.
