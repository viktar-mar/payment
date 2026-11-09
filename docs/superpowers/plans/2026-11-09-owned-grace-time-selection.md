# Owned grace time selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute sequentially with Root-owned RED/GREEN gates and independent review per task.

**Goal:** Authenticate the original committed G−1 READY as isolated billing time and exercise real grace access without granting another clock advance or financial mutation.

**Architecture:** Existing explicit Java/JDBC test-support components; preserve operational UTC and fail closed on incomplete, stopped, ambiguous or foreign evidence.

**Tech Stack:** Existing Java25, Spring/JPA/JDBC and PostgreSQL; no dependencies.

**Spec:** docs/superpowers/specs/2026-11-09-owned-grace-time-selection-design.md

## Global Constraints

- No local Docker, DB/SQL, application, browser, socket, provider, Stripe/Sandbox/native/financial execution; no old operations or DISPATCHING, archives or keys. No Python, installation, default Maven or broad local discovery.
- Workers read/edit/report source only; Root owns audited cached inert verification, compile-only hosted source checks, Git publication and exact-head GitHub CI. Preserve worktree and reports; no cleanup, merge or PR.
- Standing user direction supplies uninterrupted subagent implementation/review and publication; no intermediate approval menu.

## Review Focus

- A sibling product/subscription or stale Java record must not borrow another subscription's READY; Task2 compares full actual mapped record, not merely owner IDs.
- A partial/consumed row must remain visible; Task2 discovers dedicated operation before claim and every receipt independently, then refuses without fallback.
- Same-transaction READY is not committed evidence even after deferred checks were drained; Task2 observes genuine precommit READY locally and from an independent transaction.
- A provisional preparation must not break old method/recovery compatibility; Task2 historical branch authenticates immutable preparation plus old V34 authority, never positive grace-current eligibility.
- Read-time selection must not enter writer/deadline paths or revive producer expiry; Task1 checks routing/default behavior, Task2 preserves old READY2 tests and reads after the actual original deadline.

### Task 1: Subscription-scoped exact read boundary

**Files:**
- Modify `backend/src/main/java/dev/genericpayments/platform/subscriptions/application/port/out/SubscriptionBillingTimePort.java`.
- Modify `backend/src/main/java/dev/genericpayments/platform/subscriptions/application/service/SubscriptionQueryService.java`.
- Extend `backend/src/test/java/dev/genericpayments/platform/subscriptions/application/service/SubscriptionBillingTimeServiceTest.java` using its existing handwritten port/transaction fixtures.

**Interfaces:**
- Produce `default Sample sampleForRead(Subscription subscription, Instant operationalAt)` delegating unchanged `sample(subscription.paymentAccountId(),subscription.siteId(),subscription.customerId(),operationalAt)`.
- Only exact `access` and private `view(Subscription)` use this new method. No change to `view(Subscription,Instant)` for guarded lists, authority sequence, constructors, other consumers or production adapter registration.

- [ ] Write meaningful tests before implementation: overridden read hook sees the exact object selected by get/access and original operational UTC, while historical `sample` throws if mistakenly used; actual query result is GRACE/allowed at literal G−1 with validUntil literal G, empty permitted actions, unchanged debt and original stored audit times. Refused authority reaches neither lookup nor read selection. No-subscription access returns NO_SUBSCRIPTION at UTC without any selection. Existing ordinary/default, unscoped and writer/deadline regressions remain unchanged.
- [ ] Freeze source; Root audits all runtime closures and executes explicit cached `SubscriptionBillingTimeServiceTest` RED. Missing method diagnostics are the expected API RED; no default Maven/application/JUnit discovery.
- [ ] Implement only the default seam and two exact consumers; Root executes the same selected tests plus related pure `SubscriptionBillingTimeTest`, `SubscriptionAccessPolicyTest`, `RecoveryRulesTest`. All main sources compile; genuine failed-renewal source compile-only.
- [ ] Root records hashes/receipts, commits Task1 and dispatches independent spec/quality review over complete Task1 range. Fix findings through the original worker and scoped rereview. Do not start Task2 until source review is clean.

### Task 2: Original committed grace read selection and genuine query proof

**Files:**
- Create `backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedGraceBillingTime.java` and matching `OwnedFailedGraceBillingTimeTest.java`.
- Create tiny test-only `backend/src/test/java/dev/genericpayments/platform/subscriptions/adapter/out/persistence/OwnedGraceSubscriptionRows.java` to delegate the existing package-private production mapper, with `OwnedGraceSubscriptionRows(JdbcTemplate)` and `Subscription map(ResultSet,int) throws SQLException`.
- Reuse/narrowly extend `OwnedFailedRenewalStoreTransactionTest.JpaBoundary` for inert metadata/isolation only; preserve existing assertions and default behavior.
- Extend `backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java` with new cases/helpers; preserve all old producer READY2 assertions.
- Update `docs/stage-5b-acceptance.md` and `docs/testing/billing-failed-renewal-recovery.md` with bounded source scope, measured local verification and pending hosted status until Root's exact-head publication receipt.

**Interfaces:**
- Consume Task1 `sampleForRead(Subscription,Instant)` and existing concrete `OwnedFailedRenewalBillingTime` fallback unchanged.
- Produce explicit public final test-support `OwnedFailedGraceBillingTime(JdbcTemplate,EntityManagerFactory,OwnedFailedRenewalBillingTime)` implementing port; historical `sample`/unscoped guard delegate unchanged. Only `sampleForRead` supplies new later-time authority, through the single-statement classification and exact evidence predicates in the spec.
- Reuse unchanged package-private `OwnedGraceCallerConnection.capture/requireSame`; require READ_COMMITTED through its already-bound physical Connection metadata before proof SQL. Never open another connection/transaction or flush/write in this reader. Capture/reset the usual `sampledConnectionHolder`/`sampledTransaction` diagnostics per call; ordinary fallback transaction diagnostic is null.
- Discover every failed parent with `(d.site_id=? and d.customer_id=?) or d.subscription_id=?`, never an account/subscription-validity filter. The ID arm explicitly prevents a genuine enrolled subscription with changed site/customer from reaching zero-candidate ordinary fallback. Zero parents under both arms alone may delegate the concrete old chain; same-owner siblings and known IDs with foreign owners refuse.
- `OwnedGraceSubscriptionRows` contains no alternative mapping or SQL; new selector LEFT JOINs subscriptions `s`, selects `s.*` plus unique `owned_*` aliases, maps only a present subscription and compares the entire supplied record. Missing or nonmatching record denies for both historical and G−1 branches.

- [ ] Write inert result-boundary/provenance tests first: missing/foreign/closed/rebound/autocommit caller and wrong isolation before SQL/fallback; one valid exact row; full-record mismatch including sibling product/debt/audit terms; multiple/null/false returned candidates; incomplete evidence refusal without fallback; valid no-failed-parent concrete fallback; ordinary diagnostic reset; historical writer/unscoped delegation. Result rows are not SQL authority; no source-text/SQL-string tests substitute for genuine proof.
- [ ] Author genuine hosted cases before product implementation, reusing `gracePrepared()`/`GraceSdk.queue()/advance()`; no positive proof insertion. Root compiles them only. Exact query service uses real injected authority/store/reads/policies/catalog/trials/accounts/providers and this explicit selector. Add full `graceReadRows` snapshots including four V39 tables/policy/full provider/stop history, with no delta exclusions.
- [ ] Hosted positive: genuine GET/POST/GET READY, real get+access and repeat produce G−1/GRACE/validUntil G/empty actions, exact unchanged record/debt/cash/journal/outbox/audit/receipts, zero more SDK calls; historical writer still READY2. Wait boundedly for the actual original producer budget to expire and prove committed read still works.
- [ ] Hosted negatives: preparation/no-claim remains READY2; DISPATCH and committed ACK-only refuse new reads but preserve writer READY2; same-transaction real READY and a concurrent independent reader both refuse before commit, rollback leaves original ACK/cleanup semantics. Exercise early deferred-check drain without treating it as commit. Extend actual secondary-pool test for both auto-commit modes and zero candidate queries. Supplied negative record copies change id/account/site/customer/product/revision/debt/price/time; no positive sibling is seeded.
- [ ] Hosted current/receipt damage after genuine READY: rollback-only credentials/disabled account/stop/source/debt/G/sequence/bill drift plus missing/invalid original claim/ACK/READY binding or clock/transaction fields, precisely disabling/restoring the relevant immutable trigger only for negative corruption. Snapshot drifted state before refusal and demand exact no-effect preservation. Do not fabricate another positive chain for ambiguity.
- [ ] Freeze tests; Root audits new inert transitive source closure, runs explicit cached API/behavior RED, then opens implementation gate. Implement selector/mapper bridge only, without migration, stored function, authority widening or runtime wiring. Root GREEN includes Task1 tests, new selector tests and related original failed-time/grace boundary/service/adapter/isolation tests; genuine source remains compile-only locally.
- [ ] Update documentation/report with actual receipts, commit Task2, independent task review, fix/scoped rereview if required; whole-change review across the full plan base→head on the strongest model, one consolidated final fix wave if necessary. Preserve all rulings and costs.
- [ ] Root publishes to current branch and verifies exact-head full CI all4 jobs, disjoint backend counts, overlapping dedicated379 renewal gate and both packaging markers. Save normalized receipt in this plan workspace, retain failed attempts as history. No local runtime or new Sandbox invocation. Full5B NOT_ACCEPTED/native NOT_RUN remain explicit.

## Plan self-review and execution

Two tasks have separate independently reviewable deliverables: the backwards-compatible production read seam, then the explicit test-support original-receipt consumer and genuine query proof. Shared interface is exactly `sampleForRead(Subscription,Instant)`. No SQL migration/production mapper/clock writer is changed. All five Review Focus items have owning tests above. Ordinary fallback applies only to zero failed candidates under both owner and actual-ID discovery arms, with old branch selected within the same statement when failed lineage exists. Source-only worker and Root-only verification rules override commands ordinarily delegated by the skills. Standing nonstop direction supplies execution handoff without another question; a fresh builder check precedes implementation.
