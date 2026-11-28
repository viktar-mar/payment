# One-use original recovery scenario composition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Integrate actual original confirmation and independent recovered observation into one single-use test-support scenario object, without opening a native launcher.

**Architecture:** Fixed original tuple and concrete existing adapters; atomic phase entry rejects duplicate/concurrent calls before dependencies. Actual independently committed receipts survive phase failures, and existing services retain all durable and financial authority.

**Tech Stack:** Existing Java25/JUnit/Stripe34.0.0, memory HttpClient locally; genuine JDBC/FactService only in hosted CI.

**Spec:** docs/superpowers/specs/2026-11-08-owned-recovery-composition-design.md

## Global Constraints

- No local Docker/DB/SQL/Spring/browser/socket/provider/Stripe/Python/install, keys/archives or old operations/DISPATCHING.
- Workers source read/edit/report only, no tests/compiler/Git/network/runtime/children. Root explicit audited cached javac/pure JUnit+hosted compile-only, Git/GitHub only.
- Test-support only: no route/bean/main/HTTP/environment/native command, production service/schema/dependency/packaging export or historical runtime change. No fake authority or public callback drivers.
- Concrete actual confirmer and recovered observer with original Reservation/Completion/Recovery/SubscriptionCommandContext; ACK is provider evidence, independent observer/FactService alone publishes settlement.
- Atomic one-use phases, no reset/resume/adopt/retry. Keep existing30sec/2sec component budgets, original session and durable guards; no outer postcommit timeout/rejection/cleanup.
- Preserve worktree/reports; no cleanup/PR/merge. Full4-job Offline CI and dedicated renewal CI at exact published head required; native NOT_RUN, full5B NOT_ACCEPTED.

## Review Focus

- Concurrent/rejected phase entry must not poison or duplicate the active owner: pure real-transport latch test and state/receipt checks.
- Confirmation commits but observer later fails: genuine hosted failure preserves exact ACK, original cash/debt and no duplicate publication; phase terminal FAILED.
- Fresh object with old inputs grants no replay authority: actual adapter/durable guards refuse without another POST or adoption; original usable object not poisoned.
- Error from real memory transport cannot become a normal success/refusal: pure Error identity propagates, phase FAILED and no invented receipt.
- Commit/return delay does not turn real success into failure: no outer deadline or revalidation; preserve exact returned receipt, existing adapter/observer delayed-commit coverage retained.

### Task 1: One-use recovery phase component and genuine composition

**Files:**
- Create backend/src/test/java/dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalRecovery.java.
- Create backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/OwnedBillingFailedRenewalRecoveryTest.java; optionally extend narrowly scoped existing inert test fixtures under test source when reuse avoids fake authorities. Never subclass an existing test class to inherit its tests.
- Modify backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java for genuine composition and negative controls; minimal test-only transport fixture extension only if required for response-boundary fault injection, no callback in public component.
- Update docs/stage-5b-acceptance.md with actual preceding f8c35aa receipts and accurate current prepublication/native limits. Root owns design/plan/ledger/publication.

**Interfaces:** public final OwnedBillingFailedRenewalRecovery in acceptance.runtime; constructor(Reservation,Completion,Recovery,SubscriptionCommandContext,StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter,StripeBillingOwnedFailedRenewalMethodObserver). Public enum State {NEW,CONFIRMING,CONFIRMED,OBSERVING,COMPLETE,FAILED}; ConfirmationAck confirm(); Publication observe(); State state(); Optional<ConfirmationAck> confirmation(); Optional<Publication> publication(); fixed redacted toString(). No additional operational entry, configuration, time source or public driver. Publication refers to OwnedFailedRenewalMethod.Publication.

- [x] **Tests first:** Write pure real-component tests using actual original memory service/receipt fixture and official SDK memory HttpClient. No constructor side effects; observe-before-confirm refuses without calls; actual17GET+1originalPOST reaches CONFIRMED with unchanged finances; repeated confirm before calls; concurrent confirm cannot poison first owner; failed confirm freezes FAILED/no invented receipt; actual real boundary Error propagates unchanged; redacted cause-free RuntimeException refusal. Require observer phase prerequisites; do not mock the concrete observer or claim cash from counters.
- [x] **Hosted tests first:** Adapt both genuine browser variants to construct/use component.confirm then component.observe, preserving all existing exact request/options/receipt/unchanged-cash-before-observe and original settlement assertions. Add observation-failure-after-ACK retaining durable ACK and immutable first cash/no publication, repeated/out-of-order/concurrent phase refusal and fresh-instance replay before settlement. Keep no duplicate POST/publication and no direct positive receipt seeding. Use current recoveredGraph setup between explicit phases rather than introducing a controller callback. Tests compile only locally.
- [x] **Root RED:** Audit explicit pure execution closure. Cached all-main/explicit-tests/hosted compile with -proc:none and selected pure JUnit only; new missing API expected compile RED, no unrelated failures. Worker production mutation gate remains closed until root confirms RED.
- [x] **Implement:** Minimal test-support class with final original inputs/concrete dependencies, atomic entry and safely published exact returned receipts. RuntimeException maps only to fixed PaymentApplicationException(OWNED_RECOVERY_SCENARIO_UNAVAILABLE,409,Owned recovery scenario unavailable), no causes/suppressed private payload. Error propagates after FAILED. Rejecting a losing/out-of-order call leaves active/completed state untouched. Actual entered-phase failure consumes object; delegate cleanup only. No invented financial success, additional SDK/GET/POST/poll or postcommit budget check.
- [x] **Root GREEN:** Explicit new pure tests and related confirmer/service/isolation/reader/envelope regression after closure audit; pre-existing Mockito-based method-observer SDK regression is hosted-only because dynamic JVM attach may cross the local no-socket/no-extra-runtime boundary, while the new actual-observer memory cases run locally; all-main and genuine hosted compile-only; hash/diff preservation. Independent task spec+quality review with frozen diff and root evidence. Resolve findings through scoped tests-first fix loop, then commit.

### Publication

- [x] Fresh independent whole-change review, one consolidated fix wave/scoped rereview if needed. No unresolved important finding.
- [ ] Verify source hashes/diff, commit/push and await full Offline CI(all4jobs) plus dedicated renewal CI on exact head. Inspect both backend totals, genuine new composition cases,205 prior targeted cases where relevant and both runtime packaging markers, no raw log publication.
- [ ] Record final receipt and every Ruling/cost, preserve all reports/worktree. No native/local operational run, cleanup, PR or merge. Full5B remains NOT_ACCEPTED.
