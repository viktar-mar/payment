# Persisted failed-renewal observer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Atomically publish a newly verified declined-renewal graph through the existing owned debt service.

**Architecture:** The application service exposes authenticated immutable inspection/context-aware publication. The explicit Stripe observer performs bounded reads outside transactions and uses that service; an additive fact-command time constraint checks the actual authenticated sample without overriding it.

**Tech Stack:** Java25, stripe-java34.0.0/API2026-09-30.endive, cached JUnit/AssertJ, existing PostgreSQL migrations/hosted fixtures and in-memory SDK transport.

**Spec:** docs/superpowers/specs/2026-11-06-owned-failed-renewal-observer-design.md

## Global Constraints

- No local Docker, Stripe/Sandbox, financial operations, Python/install, local SQL/application contexts or original archive/key/old-operation/DISPATCHING access. No default Maven test discovery locally. Hosted fresh-fixture CI remains an authorized publication check.
- No registration, routes, scheduling, runtime exports/launch, new native time producer, admission, provider mutation, method confirmation or recovery/CASH producer. Preserve migrations and ordinary/success contracts.
- Preserve old constructors and fact Command six-/seven-argument constructors; new expected time is a constraint, never authority or clock override. New observer only requires exact isolated READY2 time.
- Root owns ledger/Git; agents do not commit or spawn. One implementation writer at a time, independent review of each task plus whole-change review. Preserve worktree/evidence; no user approval pauses for routine choices.

## Review Focus

- Final-inspection/publication drift and actual FactService resampling mismatch reject before effects (Task1).
- Claim/window/fence/context authentication cannot be replaced by caller records or ordinary fallback (Tasks1–2).
- Complete SDK calls, request count, UTC/monotonic budgets and cancellation remain bounded without DB work in workers (Task2).
- Stop follows unwind once; publication failure owns its stop, and committed success is not retroactively failed (Task2).
- Hosted fixtures, local memory tests and native acceptance are separate claims; no fake positive cash (both tasks/docs).

### Task 1: Authenticated read context and atomic fact-time constraint

**Files:** Modify `provider/application/service/OwnedFailedRenewalService.java`, `subscriptions/application/port/in/ApplySubscriptionSnapshotUseCase.java`, `subscriptions/application/service/SubscriptionFactService.java` under `backend/src/main/java/dev/genericpayments/platform`. Consolidate focused inspection cases in the existing `OwnedFailedRenewalServiceTest.java` harness or add a separate `OwnedFailedRenewalInspectionTest.java` if useful and `provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java` for hosted real composition. Add a focused pure fact-time guard test if existing observation harness is unsuitable.

**Interfaces produced:** Public nested `OwnedFailedRenewalService.FailedReadContext(PaymentAccount,Subscription,StripeBillingBindingPort.CustomerBinding,Instant billingAt)`; existing service constructor plus new overload appending bindings after Clock; `inspectFailedRead(Admission,ReadClaim)`; `publishFailure(Admission,ReadClaim,FailedEvidence,FailedReadContext)`; Command canonical8 with final `SubscriptionBillingTimePort.Sample expectedBillingSample`, existing6/7 constructors retained/null default. No store or schema API change.

- [x] Write behavioral tests first and observe RED: default old constructor cannot inspect; explicit port inspection authenticates exact claim/context without effects; stale/foreign/expired/future claims and owner/binding/time drift refuse. Assert lock/transaction order, no stop on inspection alone, no facts on rejection.
- [x] Implement context seam and context-aware publication reusing existing private current/lock helpers. Full context equality under publication locks; exact READY2 sample for new path only. Add optional Command constraint and FactService comparison immediately after authenticated sample, before writes. Test service-to-fact sample drift, expected mode drift, null expectation legacy path and zero effects/rollback.
- [x] Author/compile hosted JDBC/realFactService cases for genuine inspection and atomic publication-time mismatch, without local execution. Run audited cached pure service/fact regression with explicit selectors. Record APIs/commands/counts; independent Task1 spec/quality review and fixes, then root local commit.

### Task 2: Bounded provider observer and genuine composition coverage

**Files:** Create `provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalObserver.java`; create matching `StripeBillingOwnedFailedRenewalObserverTest.java`. A focused test-only fixture is permitted if needed; no general harness/framework. Extend `OwnedFailedRenewalIntegrationTest.java` for real observer publication and rollback coverage. Modify `StripeBillingFirstRenewalAdapter.java` only if extracting a narrowly shared exact-clock predicate, retaining its behavior; prefer small duplication if extraction expands scope. Update `docs/stage-5b-acceptance.md` and `docs/testing/billing-failed-renewal-recovery.md`.

**Interfaces consumed:** Task1 signatures plus package-private `StripeBillingSnapshotMapper.retrieveOwnedFailedRenewal(StripeClient,Ownership,OwnedFailedRenewal.Admission,Instant,ReadExecutor)` and `FailedRenewalRead`. **Produced:** explicit observer constructor `(OwnedFailedRenewalService,StripeCredentialResolver,StripeClientFactory,Clock)`; package-private overload with final LongSupplier; `Failure observe(Admission)`.

- [x] Write behavioral tests first for valid persisted FAILED receipt and no provider IO until the fresh claim/context exists. Use in-memory SDK transport and real service with handwritten ports; verify all15GET paths, original payment/no newcash and actual debt projection/receipt rather than descriptive success. Observe RED before implementation.
- [x] Implement one-shot context/clock/graph/publication flow and complete-SDK-call executor per spec. Test clock/context/fence/sample drift before/after reads and publication; missing/malformed/foreignreturnedclaims; expired/futureUTC, elapsed injected/actualmonotonic limits, locks consuming budget, count/page limits, zero retries/timeouts/API, interruption/cancel, SDK errors, one stop after unwind, stop failure safety, service-owned publication/begin failure stop and committed success after delayed commit.
- [x] Author/compile hosted full observer→realFactService→debt/journal/outbox composition on genuine fresh initial/READY/boundary data; include rejected drift rollback/no cash. Execute no hosted tests locally. Run all related explicit pure service/mapper/observer/first-renewal/clock regressions with current-source cached compile and record limitations.
- [x] Update evidence docs without native claims; independent Task2 review/fixes and whole-change review, root commits.

**Required publication gate (recorded separately):** Push the reviewed branch and verify both GitHub workflows on the exact final source. No local Docker or Sandbox attempt follows automatically. Publication completion and exact source/run results belong to the ignored ledger and publication receipt, so this pre-publication plan does not require a self-referential source-SHA edit.

## Completion

Record task commits, executed local/hosted checks, reviews, decisions and remaining native boundaries in this plan's ignored ledger. Keep old operational evidence intact. Stage5B remains NOT_ACCEPTED; no fresh financial authority is created by this plan.

The offline implementation checkpoint passed independent review of both tasks and a separate whole-branch review. Root freshly compiled current sources and passed **343/343** explicit in-memory tests with zero failures, skips or aborts. Hosted composition source compiled locally only. This is the pre-publication checkpoint; later hosted results are a separate exact-source gate.
