# Original recovery remote preflight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Isolate ordinary invoice payment from owned debt and independently validate the unchanged original debt/card graph.

**Architecture:** Common JDBC resource validation closes ordinary dispatch paths. A separate unregistered reader authenticates original management authority and loads exact immutable historical proof/steps before bounded independent official SDK GETs. Descriptive output grants no write authority.

**Tech Stack:** Java25, existing Stripe Java34.0.0, JUnit, JDBC/PostgreSQL in hosted CI only.

**Spec:** docs/superpowers/specs/2026-11-07-owned-recovery-remote-preflight-design.md

## Global Constraints

- No local Docker, SQL/Spring runtime, browser, Stripe/provider requests, Python, installation, keys or archives; no old operations/DISPATCHING.
- Workers source read/edit/report only, no tests/compiler/Git/network/runtime/children. Root executes audited cached explicit pure JUnit, compilation, Git and hosted CI.
- No migrations/dependencies/endpoints/registration/runner changes; no financial writes, claims, binding, lifecycle/journal/callback, stop or grant effects in reader.
- Reader pin2026-09-30.endive; 100 logical GETs maximum including clocks; 2-second GET and 30-second immutable invocation; zero retries/no idempotency; raw original session expiry exclusive; real+injected monotonic and UTC bounds.
- Preserve ordinary nonclock recovery and old METHOD PROCESSING-only behavior. Redact descriptions and errors. Retain workspace and reports.

## Review Focus

- Previously inserted owned RESERVED pay receipt must not dispatch even when ordinary reserve is bypassed: task1 first-claim test.
- Mutable authority text must not move an owned customer into ordinary recovery: task1 authority-drift refusal.
- Completed SETUP must not be faked as PROCESSING to reuse old mapper: task2 positive SUCCEEDED and old-reader rejection.
- Slow caller-side proof/credential/authentication work and virtual-thread scheduling must consume immutable budgets: task2 expiry/timeout tests.
- Failure or foreign record after an earlier good GET must return no evidence and have no cleanup/write effects: task2 mid-read drift and inert-port counters.

### Task 1: Close ordinary owned INVOICE_PAY admission

**Files:**
- Modify backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/persistence/JdbcProviderOperationStore.java
- Create backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedRecoveryProviderGuardTest.java
- Modify backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java

**Interfaces:** consumes existing ProviderOperationStore reserve/claim/acknowledge/bindRecovered and common requireResource; produces same signatures with durable clock-owned INVOICE_PAY refusal. No new public seam.

- [ ] Write pure tests exercising actual JdbcProviderOperationStore public methods with a bare scripted JdbcTemplate, no DataSource/context. Assert owned reservation/first claim/ACK/manual binding refuse before any provider receipt mutation; ordinary nonclock control proceeds to the existing mutation boundary. Name the specific production branch removal each catches. Add hosted tests using actual recoveryPrepared composition and unchanged snapshots, both browser variants; negative legacy seeded receipt remains test-only and no financial positive evidence.
- [ ] Root audit closure, compile all main+explicit tests, run only new pure class. Observe behavioral RED caused by missing owned INVOICE_PAY exclusion, not artificial exception matching.
- [ ] Extend only the existing ownership exclusion to INVOICE_PAY. Keep join scoping/lock modes and ordinary behavior unchanged.
- [ ] Root run new pure and related provider-store/method tests; compile hosted test only. Independent task spec/quality review, resolve findings, commit.

### Task 2: Add original recovery remote graph reader

**Files:**
- Create backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalRecoveryReader.java
- Modify backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingSnapshotMapper.java
- Modify backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalMethodObserver.java only if needed to share the unchanged package-private exact-clock validator instead of duplicating it.
- Create backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingOwnedRecoveryPreparationMappingTest.java
- Create backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalRecoveryReaderTest.java
- Optional test-only fixture beside those tests, if needed for genuine service+inert ports composition.
- Narrow existing test fixture correction allowed in OwnedFailedRenewalServiceTest.ready: its ready/ACK clock name must equal its admitted ClockOwner.name, rather than an unrelated hardcoded name. Preserve all production checks and include related pure regressions.

**Interfaces:**
- Consumes OwnedFailedRenewalMethodService.inspectBrowserRecoveryPreparation(Reservation,Completion,Recovery,SubscriptionCommandContext) and read-only SubscriptionBillingProofStore.setup(account,setupOperationId), ProviderOperationStore.findById(originalStepId).
- Produces public final unregistered StripeBillingOwnedFailedRenewalRecoveryReader with public constructor(methods,proofs,steps,credentials,clients,digest,Clock), package-private equivalent with LongSupplier, public PreparedRecovery inspect(Reservation,Completion,Recovery,SubscriptionCommandContext). Nested PreparedRecovery(RecoveryPreparationContext original,Snapshot snapshot) redacts toString.
- Produces mapper package-private static Snapshot retrieveOwnedRecoveryPreparation(StripeClient,Ownership,RecoveryPreparationContext,OwnedMethodCandidate,DigestPort,Instant,ReadExecutor). Existing METHOD entry remains PROCESSING-only and recovery cash reader unchanged.

- [ ] Write tests first for completed-original SETUP SUCCEEDED2 + RECOVER RESERVED0 + same OPEN debt/failed PI + independently confirmed original card; only prior initial invoice has cash, no authority/grants/result secrets. Hand-derived expected GET sequence is clock, complete debt graph, original SetupIntent, exact card, clock. Reuse existing inert graph fixtures, not synthetic operation-status substitution in production.
- [ ] Add ownership/operation/step/version/fingerprint/reference/chronology/association mismatch tests, remote graph/card drift and existing reader PROCESSING-only negative control; no SDK calls for invalid supplied records. Reader tests cover pre-auth refusal before credentials/store IDs, missing/foreign proof and steps, mid-read original authority/proof/step drift, session equality/exactexpiry, clock/readiness/API mismatch, 2-second/per-invocation UTC+monotonic+actual wallclock, interrupt/cancellation, maxread/page bounds and safe exceptions without stop/mutation. Prefer actual service with existing inert fixture; mocks only external boundary where unavoidable.
- [ ] Root audits closure and observes expected RED (new API absent), then authorizes minimal implementation.
- [ ] Implement mapper shared mechanics without broadening existing lane; implement reader with spec budgets and repeated actual original-service/immutable evidence comparisons. No new dispatch port or migration.
- [ ] Root run explicit pure tests plus related owned method/recovery mapper/adapter/service regressions, compile affected hosted tests, independent task review, fix findings and commit.

### Publication

- [ ] Fresh independent whole-change review, one consolidated fix wave if needed and scoped re-review. Root verify sources/hashes and git diff check.
- [ ] Commit/push this branch, verify exact-head full Offline CI and dedicated owned-renewal CI; inspect completed backend test totals and runtime packaging markers in memory only.
- [ ] Final report includes implementation/evidence/native limitations and every ledger Ruling with cost if wrong. Retain this plan workspace and all reports; no cleanup or operational run.
