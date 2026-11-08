# Owned G−1 preparation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce one durable exact-original failed-renewal preparation for G−1 without advancing time or granting provider dispatch authority.

**Architecture:** An explicit service acquires existing lifecycle locks and invokes a caller-transaction JDBC store. Additive V38 independently authenticates published failure lineage, immutable target and fingerprint. Hosted composition tests exercise real failure provenance; ordinary runtime and current READY2/financial guards stay closed.

**Tech Stack:** Existing Java25, Spring JDBC, PostgreSQL migrations and cached JUnit; no new dependencies.

**Spec:** docs/superpowers/specs/2026-11-08-owned-grace-preparation-design.md

## Global Constraints

- No local Docker, DB/SQL, Spring/application/browser/socket/provider/Stripe/Sandbox/native/financial execution; no old operations/DISPATCHING, archives or keys; no Python, installation, default Maven or broad local discovery.
- Workers read/edit/report source only: no execution/compiler/tests/Git/network/runtime/children. Root owns audited cached verification and Git/GitHub publication. Preserve worktree and reports; no cleanup, PR or merge.
- Existing explicit component only: no bean, route, scheduler, launcher, export, new/later sampled business time, provider operation or claim, cash, journal, callback, lifecycle mutation or stop. V30–V37, existing READY2 selector, existing service entry-point behavior and provider policy remain unchanged.
- One immutable G−1 preparation per failed admission, insert winner only; duplicate and competing calls refuse. Exact reload reauthenticates only and grants no dispatch continuation. Current failure, fixed captured G and full original lineage are required; no current-policy-derived time or manufactured positive proofs.
- Whole-second G/N; READY2 < G−1 < G < G+1 < N; target minus READY2 within 56 days MONTH or 730 days YEAR. Audit UTC is independent, microsecond normalized and not future in DB. Exact versioned Java/SQL fingerprint parity.
- Source-only hosted tests compile locally; actual JDBC/Flyway/transaction/concurrency evidence requires exact-head CI. Native NOT_RUN; full5B NOT_ACCEPTED. All4 full CI jobs, dedicated renewal and both packaging markers required before final completion claim.

## Review Focus

- A foreign or descriptive admission must not gain even preparation authority through a valid neighboring owner; reload actual full stored identity and failure before insert/reload.
- A stored preparation cannot be reused after method reservation, recovery, stop, account/source/credential/term/policy drift, nor turn an existing provider DISPATCHING into authority.
- Direct SQL cannot bypass original failure provenance, target/fingerprint/chronology, and immutable rows cannot be rewritten/deleted even if current authority disappears.
- Concurrent preparation and method/recovery transition must serialize on canonical operation/lifecycle/account/subscription/bill locks, without lock-order inversion or partial receipts.
- G comes from published debt; nonwhole epochs, too-short grace, G+1 at N, interval ceiling and overflow refuse without time advancement, financial side effects or fallback.

### Task 1: Durable preparation and real hosted composition

**Files:**
- Modify backend/src/main/java/dev/genericpayments/platform/provider/application/model/OwnedFailedRenewal.java.
- Modify backend/src/main/java/dev/genericpayments/platform/provider/application/port/out/OwnedFailedRenewalStore.java.
- Modify backend/src/main/java/dev/genericpayments/platform/provider/application/service/OwnedFailedRenewalService.java.
- Modify backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/persistence/JdbcOwnedFailedRenewalStore.java.
- Create backend/src/main/resources/db/migration/V38__stripe_billing_owned_failed_grace_preparation.sql.
- Extend existing OwnedFailedRenewalModelTest, OwnedFailedRenewalServiceTest and OwnedFailedRenewalStoreTransactionTest; no duplicated service fixture.
- Extend backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java, with narrowly shared fixture helper if necessary. Do not refactor unrelated historical tests.
- Update docs/stage-5b-acceptance.md and docs/testing/billing-failed-renewal-recovery.md with bounded scope and previous exact b6246f6 evidence.
- Update a migration inventory assertion only if an existing explicit inventory requires V38; do not broaden discovery or weaken it.

**Interfaces:**
- Add nested public record GraceLastSecondPreparation(UUID id, UUID admissionId, UUID failureClaimId, UUID billId, UUID predecessorReadyId, Instant graceDeadline, long target, String operationKey, String fingerprint, Instant preparedAt) to OwnedFailedRenewal. Constructor validates shape and exact G−1; persisted reauthentication mandatory. public static long graceLastSecondTarget(Admission admission, Failure failure) validates full descriptive original/debt relationships and derives exact G−1/window. public static String graceLastSecondFingerprint(UUID id, Admission admission, Failure failure, long target, String operationKey, Instant preparedAt) uses explicit canonical fields. Key exactly `billing-failed-grace-v1:<failed-admission UUID>:1`.
- Extend OwnedFailedRenewalStore with default-denied void lockGraceLastSecondInTransaction(Admission admission); GraceLastSecondPreparation prepareGraceLastSecondInTransaction(Admission admission, Failure failure, Instant utcAt); GraceLastSecondPreparation requireGraceLastSecondInTransaction(Admission admission, GraceLastSecondPreparation preparation). JDBC overrides require actual caller transaction and own DataSource holder, never provider work/nested transactions; constructor unchanged. Lock method authenticates full stored original identity before shared V38 NOWAIT canonical helper and separate fresh eligibility.
- Add public GraceLastSecondPreparation prepareGraceLastSecond(Admission admission) and requireGraceLastSecond(Admission admission, GraceLastSecondPreparation preparation) to OwnedFailedRenewalService. No ambient transaction; each uses one requiresNew, new nonblocking lock method FIRST (before existing blocking current), existing current/requireNoFinancial/requireDebt, exact original READY2 sample, failure reload and new store method. No guarded/terminal-stop wrapper on refusal. Constructors unchanged. Hosted regression must hold V35 fresh-operation FK KEY SHARE while grace enters, proving prompt grace refusal/no stopped winner; original blocking current first would invert locks.
- Table stripe_billing_failed_grace_preparations: id primary key, admission_id unique immutable FK, predecessor_ready FK, failure_claim_id FK, renewal_bill FK, grace_deadline timestamptz, target bigint, operation_key unique, fingerprint 64 lowercase hex, prepared_at timestamptz. Original full owner/debt/failure fields derive through immutable referenced rows and participate in fingerprint; no unnecessary copies. V38 exposes fixed SQL fingerprint/current predicate and INSERT/update/delete guards only. Trigger obtains nonblocking canonical locks and performs separate fresh VOLATILE eligibility check. Fingerprint exact field list is frozen in implementation report before root RED/implementation gate; Java/SQL use existing first_renewal_hash/first_renewal_instant canonical scalar parity (microsecond UTC, not session-dependent timestamp formatting).

- [ ] **Tests first:** Pure model cases exact G−1, fixed persisted G independent of policy inputs, interval ceilings, whole-second/period and chronology, foreign failure/debt/bill/claim shape, malformed key/fingerprint and tampering. Pure service boundary tests no ambient tx/no effects, dependency validation, sorted canonical locks and same requiresNew, valid prepare/exact reload, exception rollback propagation. Use inert fakes, no Mockito dynamic attach/context/actual SQL.
- [ ] **Hosted tests first:** Existing genuine setup+fail creates original admission, READY2 and failure. Enable persisted READY2 selector and verify prepare returns exact stored G−1 without changing selected billing time or finance/journal/callback/providerops. Inspect row/reference/hash parity, same caller connection/xact, inserted count exactly one, duplicate/refusal state unchanged. Direct SQL forged target/fingerprint/source-linked failure/UTC and immutable update/delete controls. Refuse before failure, after method reservation/recovery/publication/stop, foreign owner/credentials/disabled/policy drift and rollback. Concurrent fresh requests have exactly one durable winner; concurrent method preparation cannot create an unverified mixed state. Any genuine case not reachable without forbidden positive proof insert must use rollback-only negative corruption or remain explicitly unsupported, never fake authority.
- [ ] **Root RED:** Source-closure audit, cached -proc:none all-main + explicit pure + genuine hosted compile-only fails for missing new APIs. Root executes only audited pure tests; workers await explicit gate.
- [ ] **Implement:** Minimal model/service/store/additive V38, pure tests then actual hosted cases. Freeze exact fingerprint list and SQL/Java scalar encoding in report. Direct SQL guard independently authenticates original immutable/current relations, absence of method/recovery/publication/stop and DB chronology. No changes to old time/financial predicates or operation policy.
- [ ] **Root GREEN:** All-main plus explicit pure/genuine hosted compilation, new pure tests and prior226 inert tests zero skip/abort/fail, source hashes and whitespace. Update evidence docs honestly; hosted new execution pending. Independent task spec/quality review; scoped tests-first fixes then commit.

### Publication

- [ ] Fresh independent whole-change review of BASE b6246f6..final task head, complete frozen package; one consolidated fix wave and one scoped rereview if needed. No open important finding.
- [ ] Root final source-hash/diff verification, commit/push existing branch and await all4 full CI plus dedicated renewal at exact head. Inspect actual new hosted cases, disjoint backend totals and both packaging markers, no weakened old checks.
- [ ] Record actual receipt and Rulings/costs, preserve all artifacts. Preparation implemented is distinct from clock dispatch/ACK/READY/grace access/native acceptance. No operational launch or old operation retry.
