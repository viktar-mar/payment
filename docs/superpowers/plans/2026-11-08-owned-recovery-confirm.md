# One-use original recovery confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Confirm only the original failed PI once with the completed original card, keeping independent settlement authority.

**Architecture:** A fresh original-session service transaction owns a dedicated provider receipt and append-only ACK. Ordinary recovery is excluded. An unregistered adapter gets independent preflight, sends one bounded official-SDK confirm and records evidence; the existing recovered observer alone publishes cash.

**Tech Stack:** Java25, existing Stripe Java34.0.0, JDBC/PostgreSQL in hosted CI, JUnit and inert SDK HttpClient.

**Spec:** docs/superpowers/specs/2026-11-08-owned-recovery-confirm-design.md

## Global Constraints

- No local Docker/SQL/Spring/browser/socket/provider/Stripe, Python/install, keys/archives, old operations/DISPATCHING or financial execution.
- Workers source read/edit/report only, no tests/compiler/Git/network/runtime/children. Root audited cached explicit pure JUnit and compilation, Git, GitHub CI only.
- New unregistered kind OWNED_RECOVERY_INTENT_CONFIRM, original RECOVER resource/key+:CONFIRM, RESERVED0 inserted and claimed1 atomically; only ACK2 or UNKNOWN2 follows, no reuse/retry. Ordinary paths default deny.
- Preserve immutable null-pay recovery, SETUP SUCCEEDED2/RECOVER RESERVED0 and independent observer authority. No route/bean/runner/dependency/browser change. No cash from reply.
- SDK pin2026-09-30.endive, zero retries, one logical POST,2-second call,30-second invocation,60-second claim, exclusive original raw session expiry; no forced synchronous storage cancellation guarantee.
- Preserve branch/workspace/reports, no cleanup/merge/PR. Full and dedicated exact-head GitHub CI required.

## Review Focus

- Scheduler selects an expired new-kind row before excluding it: task1 polling/by-ID and persisted-kind finish tests, plus SQL freeze.
- A current receipt exists RESERVED0 or a contender loses the first claim: task1 rejects adoption and rolls back every speculative mutation.
- Browser/session expiry occurs while waiting on provider/upstream locks: task1 witnesses the lock and asserts real rollback after expiry, not a timeout.
- Successful confirm changes the remote failed graph: task2 asserts no failed-graph GET after POST and ACK from local authority only; later observer verifies settlement.
- Budget/authority expires after SDK success but before ACK: task2 retains consumed own evidence, refuses ACK/secondPOST, no cash; task1 atomic rollback protects receipt.

### Task 1: Dedicated original-session receipt and SQL policy

**Files:**
- Create provider/application/model/ProviderRequestFingerprint.java (unchanged core encoding extracted from adapter facade), modify provider/adapter/out/stripe/StripeRequestFingerprint.java to delegate, and create provider/application/model/OwnedRecoveryConfirm.java; provider/application/port/out/OwnedRecoveryConfirmStore.java; provider/application/service/OwnedRecoveryConfirmService.java; provider/adapter/out/persistence/JdbcOwnedRecoveryConfirmStore.java under backend/src/main/java/dev/genericpayments/platform.
- Modify provider/application/model/ProviderOperation.java, provider/application/service/OwnedFailedRenewalMethodService.java, provider/application/port/out/OwnedFailedRenewalMethodStore.java, provider/adapter/out/persistence/JdbcOwnedFailedRenewalMethodStore.java, provider/adapter/out/persistence/JdbcProviderOperationStore.java, payments/adapter/out/persistence/PendingProviderRecoveryPersistenceAdapter.java, provider/adapter/out/stripe/StripeRecoveryProofAdapter.java (early new-kind refusal before credentials).
- Create backend/src/main/resources/db/migration/V37__stripe_billing_owned_recovery_confirm.sql; update backend/src/test/java/dev/genericpayments/platform/support/CurrentSchemaVersions.java.
- New pure OwnedRecoveryConfirmServiceTest and OwnedRecoveryConfirmIsolationTest in corresponding test packages; extend OwnedFailedRenewalIntegrationTest and test-only OwnedRecoveryInertFixture as needed.

**Interfaces:**
- OwnedRecoveryConfirm.ConfirmReply(String paymentIntent,String customer,String method,String currency,long amount,long received,long capturable,String charge,Instant observedAt), validating normalized succeeded-only data; ConfirmationAck(ProviderOperation operation,ConfirmReply reply,Instant acknowledgedAt), redacted.
- OwnedRecoveryConfirmStore default-denied caller-transaction methods: lockInTransaction(RecoveryPreparationContext)->Optional<ProviderOperation>; insertAndClaimInTransaction(context,String claimant,Instant now)->ProviderOperation; acknowledgeInTransaction(context,claim,ConfirmReply,Instant now)->ConfirmationAck; unknownInTransaction(context,claim,budget)->boolean (true only exact owned claim consumed). No nested transaction or provider work.
- OwnedRecoveryConfirmService(methods,store,SubscriptionTransactionPort,Clock): claim(expected,originalContext,String claimant)->ProviderOperation; inspectClaim(expected,claim,originalContext)->ProviderOperation; acknowledge(expected,claim,reply,originalContext)->ConfirmationAck; uncertain(expected,claim,budget)->void. Static canonical fingerprint(context,DigestPort) available to task2. claim/inspectClaim/acknowledge additionally receive the SAME OwnedRecoveryConfirm.InvocationBudget as their final argument; it is a final class constructed with (Clock,LongSupplier), exposing invocation(), enteredAt(), deadlineAt(), remainingNanos(ProviderOperation). Store insertAndClaim, requireClaim and acknowledge also receive it; requireClaimInTransaction(context,claim,budget,now) validates the immutable original commit-fence receipt. No caller-selected deadline or restarted budget. Check budget after final mutation/authority wait before transaction completion; timely committed ACK is returned even if commit/return is delayed. Unknown authenticates immutable relation, exact committed claim and the original budget invocation UUID/entered/deadline receipt, then stops only original admission; identity proof does not sample live time or require positive authority. A fresh budget/readback cannot acquire cleanup authority; no retry.
- Package-private typed transaction seam in OwnedFailedRenewalMethodService may receive separate lock and effect callbacks from same-package service; authenticate original ops before lock, original browser authority after lock, and again after effect. Public read semantics unchanged. A separate default-denied requireUnobservedRecoveryInTransaction(Reservation,Recovery) void port on the method store checks no prior RECOVERED read/publication only in the new financial transaction seam; actual JDBC and memory negative gate agree, never strengthen a fake-only existing enrollment path. A second default-denied method-store requireRecoveryConfirmationIdentityInTransaction(RecoveryPreparationContext) authenticates immutable stored reservation/Completion/Recovery/enrollment/owner locators for cleanup only, before canonical operation locks; it grants no live authority and compares no mutable current business state. Task1 must document exact seam in report before task2 consumes it.

- [x] Write new pure service and public isolation tests first using actual service/memory transaction harness and bare scripted JDBC boundary. Assert exact first claim/ACK and rollback/refusal/no effects; add genuine hosted cases for both original browser paths, concurrency/expiry, SQL transition/orphan/ACK guards, ordinary/scheduler negatives and golden fingerprint. Hosted tests are compiled only locally.
- [x] Root audits exact execution closure, cached compile + explicit new pure selectors: observe RED from absent new API or actual missing boundary; never run SQL locally.
- [x] Implement minimal model/port/service/seam/store/V37 and ordinary/scheduler isolation. Preserve lock ordering and V34 association explicitly, no new-kind omission bypass. Add immutable first-claim receipt with trigger-authenticated creating xid8, original invocation/UTC deadline and exact claimant/lease. Both terminals require a different transaction from its creation, even if constraints were forced early. Deferred initial checks require DISPATCHING1, never committed RESERVED0 or same-transaction ACK. ACK checks old owner/deadline; direct SQL uses fail-closed ordered NOWAIT, never inversion. CurrentSchemaVersions gains37.
- [x] Root runs explicit new+related pure regressions and allmain/hosted compile; independent task spec/quality review, resolve findings and commit.

### Task 2: Bounded original PaymentIntent confirm and recovered composition

**Files:**
- Create provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter.java; optional narrowly scoped StripeBillingOwnedRecoveryConfirmWrite.java only if adapter budget extraction improves clarity.
- Create corresponding StripeBillingOwnedFailedRenewalRecoveryConfirmAdapterTest.java; modify StripeBillingOwnedFailedRenewalRecoveryReader.java with a package-private inspect overload consuming the same InvocationBudget and clamping every GET wait/check to it, while preserving the public reader API; extend existing owned hosted integration test to use actual adapter with inert SDK response and independent recovered observer.
- Update docs/stage-5b-acceptance.md with preceding exact baseline receipt and honest current prepublication/native status.

**Interfaces:** public constructor(methods,remotePreflight,confirmService,credentials,clients,digest,Clock), package-private same plus LongSupplier; public ConfirmationAck confirm(Reservation,Completion,Recovery,SubscriptionCommandContext). Consumes task1 service, original public remote reader, fixed official PaymentIntentConfirmParams. Returned ACK is provider evidence only.

- [x] Write pure tests first using actual original service lineage plus dedicated inert store, official SDK memory transport and literal response/POST expectations. Cover original17GETs then one POST, exact path/params/options/key, completed-card/customer/amount/reference drift, malformed/error/SCA/processing response, mid-read and pre-POST authority drift, postwrite local drift, no adoption/secondPOST, cancellation/interruption,2sec actual timeout, immutable30sec actual/injected/UTC and raw session boundary, blocked-ACK rollback across30sec while session/lease remain live and delayed return of a timely committed ACK, safe redaction and ownUNKNOWN/stop only after first claim. Do not mock original authentication. Hosted positive goes through actual adapter before existing observeRecovery, with unchanged initial cash and original bill/PI only.
- [x] Root audits closure, runs explicit compile/new pure tests for expected missing-API RED.
- [x] Implement exact fixed request with one bounded logical call, budget starts before preflight. No postwrite failed graph reread; only fresh local exact claim/context then atomic ACK. Decline/action/processing/uncertainty never causes fallback or retry. Return committed ACK without a new postcommit rejection. Credential/client work remains caller-side under budget.
- [x] Root executes selected pure/related regression and compile-only hosted sources; independent task review, fix and commit. Update acceptance doc with actual source evidence/limitations.

### Task 3: Preserve complete backend coverage within the existing CI budget

**Files:** .github/workflows/ci.yml and infrastructure/ci-workflow.test.mjs only; controller owns this plan and report updates.

**Evidence:** Exact-head full CI42482030417 timed out at60min, no completed test failures: 4766 completed cases. OwnedFailedRenewalIntegrationTest alone took1734s,235 cases. Dedicated379 and frontend/infrastructure passed. Both runtime packaging gates were skipped by timeout and remain mandatory.

**Contract:**
- Keep backend job id, normal Maven verify lifecycle, default Surefire includes, nested-class default exclusion, all existing runtime packaging gates, and60min timeout.
- Split exactly dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java into new required job backend-owned-failed-renewal, timeout60min; same pinned checkout/node24/java25/Maven cache, Docker tooling checks and fresh local issuer preparation as backend. No job dependencies, conditions, skip/ignore/no-tests flags or secret/provider inputs.
- Backend invocation exactly: `./mvnw -B -ntp '-Dsurefire.excludes=**/*$*,dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java' verify`.
- Split invocation exactly: `./mvnw -B -ntp '-Dtest=dev.genericpayments.platform.provider.adapter.out.persistence.OwnedFailedRenewalIntegrationTest' test` in backend directory. Whole class, no method or tag selection.
- Official pinned Surefire3.5.6 source confirms surefire.excludes overrides its list and default nested pattern is **/*$*; retain that explicitly. No -Dtest in broad backend so future default-discovered tests remain covered. New job fails if explicit class absent using untouched default failIfNoSpecifiedTests.
- Extend existing bounded config policy tests to require both complementary exact invocation boundaries and existing job security/cache/tooling/order/bounds for all four jobs. Preserve existing Fake browser checks verbatim. Assert the selected source exists and both runtime packaging gates remain after backend verify. These are configuration boundary checks; actual plugin execution/coverage is hosted-only.
- No test source, POM, dependency, application, financial, browser or local operational changes. No coverage or timeout weakening.

- [x] Worker writes policy tests only; root executes explicit pure Node test and observes expected missing-partition RED before workflow change.
- [x] Worker implements minimal workflow partition; root verifies explicit policy tests, diff and source preservation. No local Maven/DB/Docker execution.
- [x] Independent task spec/quality review and scoped fixes if needed; final focused publication review covers CI delta only, existing financial reviews retained.
- [ ] Commit/push; wait for both exact-head workflows, including all four Offline CI jobs. Inspect both backend aggregates,235 owned hosted cases, both packaging markers and dedicated379 gate; never infer full success from earlier cancelled receipts.

### Publication

- [x] Fresh independent whole-change review on most capable model, one consolidated fix wave and scoped rereview as needed; no open important finding.
- [ ] Verify final source hashes and diff, commit/push, wait for exact-head full Offline CI and dedicated owned-renewal CI. Inspect completed numeric totals, new hosted cases and both runtime packaging markers without exposing raw logs.
- [ ] Record final receipt, remaining native acceptance and every ledger Ruling/cost. Preserve worktree/reports; no local operational run or cleanup.
