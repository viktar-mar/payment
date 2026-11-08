# Persisted failed-renewal billing time Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the test-selected READY2 time seam from the genuine failed-renewal/recovery composition using persisted original readiness, without opening a native launcher.

**Architecture:** A test-support exact-owner selector starts from every failed-admission candidate, authenticates unchanged V34 authority and exact stored READY2, and delegates the earlier concrete selector only on absence. Genuine hosted lifecycle cases consume it; ordinary/security time and financial authority remain unchanged.

**Tech Stack:** Existing Java25/JDBC/JUnit/Stripe34.0.0 and API2026-09-30.endive; no new dependencies.

**Spec:** docs/superpowers/specs/2026-11-08-owned-failed-renewal-time-design.md

## Global Constraints

- No local Docker/DB/SQL/Spring/app/browser/socket/provider/Stripe/Sandbox/native/financial execution, Python/install/default Maven/broad local test discovery, keys/archives or oldops/DISPATCHING.
- Workers source read/edit/report only, no execution/compiler/tests/Git/network/runtime/children. Root audited cached javac with -proc:none, explicit inert pure JUnit and hosted compile-only; Git/GitHub under standing publication authority.
- Test-support only: no production service/schema/dependency/route/bean/launcher/packaging export or historical runtime change. No fake financial or readiness authority, no direct positive proof/ACK/lifecycle inserts.
- One nonlocking SELECT in caller actual transaction; exact Site/Customer candidates, foreign Account refuses. Reuse unchanged owned_failed_renewal_authority plus full predecessor tuple/source and exact original READY2; never current mutable chain/phase predicates or filtered-invalid fallback.
- No new time advance or grace inference: original persisted READY2 E+3600 only; security/audit UTC stays real. No extra locks/transactions/store/provider calls. Existing services/FactService retain phase/financial authority.
- Preserve all old exact-time drift/grace/refusal/financial tests. New genuine persisted positives must traverse original failure/SETUP/browser/binding/METHOD/confirm/independent recovered observation and keep exact journal/callback assertions.
- Preserve worktree/reports; no cleanup/PR/merge. All4 full Offline CI jobs plus dedicated renewal CI at exact published head and both runtime packaging markers required. Native NOT_RUN; full5B NOT_ACCEPTED.

## Review Focus

- Broken owned evidence must remain a visible failed candidate: genuine stopped/changed credentials/READY corruption controls refuse without ordinary fallback, preserve all state, and restoration resamples correctly.
- Sampling before failed-admission insert must fall back through the existing authenticated READY2 lane; genuine setup() with persisted mode enabled before admission must succeed, then use the failed lane.
- Pending SETUP/METHOD and transient fact-before-receipt/recovered transitions must retain original READY2: both genuine browser variants use persisted mode across every actual service/FactService phase.
- No ambient transaction or unrelated owner may gain isolated time: pure no-transaction boundary + genuine exact-account/site/customer fallback and caller connection/xact identity.
- Any unscoped list/deadline query containing failed rows, including stopped rows, must refuse; matching global/site/customer cases and unrelated scopes exercise delegation.

### Task 1: Persisted failed-renewal selector and genuine lifecycle integration

**Files:**
- Create backend/src/test/java/dev/genericpayments/platform/support/OwnedFailedRenewalBillingTime.java.
- Create backend/src/test/java/dev/genericpayments/platform/support/OwnedFailedRenewalBillingTimeTest.java (narrow inert boundary tests only, no authority claim from JDBC doubles).
- Modify backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java (opt-in selector wiring, genuine positives and denial controls).
- Update docs/stage-5b-acceptance.md and docs/testing/billing-failed-renewal-recovery.md with actual baseline4dbf53b receipts, selector scope and honest prepublication/remaining limits.

**Interfaces:** public final OwnedFailedRenewalBillingTime implements SubscriptionBillingTimePort; constructor(JdbcTemplate jdbc, OwnedFirstRenewalBillingTime original), both nonnull; Sample sample(PaymentAccountId,SiteId,CustomerId,Instant); void requireUnscopedAllowed(SiteId,CustomerId). Existing support diagnostics public Object sampledConnectionHolder and public String sampledTransaction. No extra control/configuration/driver.

- [ ] **Tests first:** Add memory-JDBC result-boundary tests for rejection before SQL without actual transaction; one returned valid row maps exact frozen epoch rather than supplied UTC; invalid candidate never delegates; >1 candidates refuse; zero failed rows preserves concrete upstream/ordinary fallback. These do not establish SQL authority. Audit real dependencies before root execution; thread-local transaction indication is an inert test precondition only, restored after each case.
- [ ] **Genuine tests first:** Add opt-in persisted delegate to existing ExactTime fixture, preserving all default selected/ordinary/drift controls. Add a new parameterized persisted-time variant of the existing two-browser singleUseRecoveryComposition... positive, sharing a narrowly factored assertion body so none of its original checks is dropped. Enable persisted mode before setup(); all actual failed/method services/FactService/proofs consume it. Assert original READY2 E+3600 despite a distinct operational UTC, caller transaction/connection, no time-induced writes/SDK calls, failure sample and later reservation/read/recovered sample. Preserve original persisted ACK/no cash before observation and exact original cash/journal/two callback enqueue after it.
- [ ] **Genuine refusals:** No ambient transaction; foreign Account for enrolled owner; changed credentials/disabled account and durable stop; invalid pinned READY proof (rollback-only corruption/restoration if constraints require it); matching global/site/customer/exact unscoped refusal including stopped lane; unrelated ordinary owner fallback through real upstream selectors. Compare financial and authority rows/call counts before/after sample-only controls; no stop/claim/write from selector. Existing genuine chain supplies every positive admission/receipt. Row-cardinality ambiguity may be inert boundary-only if schema forbids natural duplicates.
- [ ] **Root RED:** After source closure audit, cached all-main/new-unit/genuine-hosted compile with processors disabled must fail only for missing new selector APIs. No hosted JVM execution. Implementation gate remains closed until clean RED. New pure selector behavior can then run only under the audited inert JDBC boundary.
- [ ] **Implement:** Minimal new support selector as spec: actual transaction guard, candidate-preserving LEFT JOIN predecessor/full tuple/source/chronology + exact READY2, supplied Account and reused unchanged V34 authority, fixed denial/ambiguity, isolated frozen time only; absence delegates original. Scope count blocks any matching failed row before upstream delegation. Follow existing caller connection/xact diagnostics; no source-side native or phase state.
- [ ] **Root GREEN:** Fresh explicit inert tests + prior214 selectors, no skips/aborts/failures; cached all-main/genuine hosted compilation and source hashes. New SQL/lifecycle execution remains hosted-only. Update evidence prose then independent task spec+quality review with frozen diff and root verification. Fix findings in scoped tests-first loop and commit.

### Publication

- [ ] Fresh independent whole-change review of this complete plan range, one consolidated fix wave and scoped rereview if needed. No unresolved important finding.
- [ ] Verify source hashes/diff, commit/push and await all4 full CI plus dedicated renewal on exact head. Inspect actual totals/new selector cases, genuine two persisted variants, prior214 and old SDK observer25, disjoint backend partition and both packaging markers.
- [ ] Record final receipt and every Ruling/cost, preserve all reports/worktree. Native NOT_RUN/full5B NOT_ACCEPTED; no cleanup/PR/merge or operational launch.
