# Original owned recovery preparation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Authenticate the original still-unpaid recovery after genuine method completion without dispatching payment.

**Architecture:** A fresh service inspection joins the existing lock/fence/debt chain and a separately scoped JDBC original enrollment validator. Contexts describe current evidence only. Existing observers and financial publishers remain independent.

**Tech Stack:** Java 25, cached JUnit/Jupiter, Spring JDBC/PostgreSQL only in hosted CI; no dependencies.

**Spec:** `docs/superpowers/specs/2026-11-07-owned-recovery-preparation-design.md`

## Global Constraints

- No local Docker, SQL, Spring, application, browser/server/socket, Stripe/provider/financial operations, installation, Python, keys, archives or old operation reuse.
- Root alone executes cached explicit pure Java selectors and compiles hosted sources locally; hosted SQL/Spring execution occurs only in exact-head GitHub full CI plus dedicated renewal CI.
- Workers read/edit source and reports only; no execution, compiler, tests, Git, network or child agents. Root owns RED/GREEN and commits/push/CI.
- No financial adapter, native launcher, route, bean, migration, dependency, browser permission or operation claim. Full 5B remains NOT_ACCEPTED and native failed-renewal/recovery remains NOT_RUN.
- Preserve reports/worktree; no merge, PR or cleanup. Old SETUP/METHOD/non-browser behavior stays strict and unchanged.

## Review Focus

- Completed SETUP is not a still-RESERVED SETUP: prove strict delta-1 original completion/association rather than relaxing earlier gates (Tasks 1,2).
- New live session with original key cannot replace original enrollment; origin/scope/nanosecond expiry require exact identity (Tasks 1,2).
- Blocking locks cannot extend authority: final raw UTC/service and fresh DB UTC checks (Tasks 1,2).
- A descriptive context cannot reserve, stop, consume, write cash or publish facts; repeated valid inspection unchanged (Tasks 1,2).
- Former consumed browser-root expiry is not current management-session expiry; do not reissue permission (Task 2).

---

### Task 1: Fresh recovery preparation authority

**Files:**
- Modify `backend/src/main/java/dev/genericpayments/platform/provider/application/model/OwnedFailedRenewalMethod.java`.
- Modify `backend/src/main/java/dev/genericpayments/platform/provider/application/port/out/OwnedFailedRenewalMethodStore.java`.
- Modify `backend/src/main/java/dev/genericpayments/platform/provider/application/service/OwnedFailedRenewalMethodService.java`.
- Create `backend/src/test/java/dev/genericpayments/platform/provider/application/service/OwnedRecoveryPreparationTest.java`.
- Modify test-only `OwnedFailedRenewalMethodServiceTest.java` inert Harness, opt-in recovery enrollment controls only.

**Interfaces:** Produces `RecoveryPreparationContext(Reservation reservation, Enrollment enrollment, PaymentAccount account, Subscription subscription, SubscriptionOperation setupOperation, SubscriptionOperation recoveryOperation, CustomerBinding binding, Completion completion, Recovery recovery, Sample billingSample)`, required fields, isolated sample, redacted toString. Produces service `RecoveryPreparationContext inspectBrowserRecoveryPreparation(Reservation reservation, Completion completion, Recovery recovery, SubscriptionCommandContext originalContext)` and default-denied store `Enrollment requireBrowserRecoveryEnrollmentInTransaction(Reservation reservation, CustomerSessionContext originalSession, Instant now)`.

- [ ] Write actual-service tests first: complete original browser method flow using actual lifecycle/observer publication in existing inert Harness, reserve actual recovery, then inspect expected exact context, two repeated inspections leave every business/provider/receipt/fact state unchanged. Mutations: missing/forged receipts; wrong SETUP/recovery status/version/key/fingerprint/policy/reference; bill/debt/sequence/sample/binding drift; non-browser/admin/replacement key/session/origin/scopes/inexact expiry; ambient tx; final store/authority wait expiry/revocation; all refusals no stops/side effects. Test redaction/default-denied port behavior.
- [ ] Freeze test sources and notify root; root audits source closure and runs cached explicit compile/selector, confirms missing interface RED. No production edits until root opens gate.
- [ ] Implement exact interfaces with one fresh transaction, existing current/requireDebt(delta1)/selected RECOVER guards, independent durable receipts, original SETUP/context authentication and final exact original enrollment/authority checks. No provider/observer/read claim.
- [ ] Freeze, root verifies all relevant explicit pure selectors and hosted compile-only. Append root evidence to task report; task-scoped fresh spec+quality review and corrections precede root commit.

### Task 2: JDBC original recovery enrollment and genuine composition

**Files:**
- Modify `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/persistence/JdbcOwnedFailedRenewalMethodStore.java`.
- Modify `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/persistence/JdbcOwnedMethodBrowserStore.java`.
- Modify `backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalMethodStoreTransactionTest.java`.
- Modify `backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java`.
- Update `docs/stage-5b-acceptance.md`, `docs/testing/billing-failed-renewal-recovery.md`, `docs/local-development.md`.

**Interfaces:** Consumes Task1 signatures verbatim. Produces JDBC override `Enrollment requireBrowserRecoveryEnrollmentInTransaction(Reservation reservation, CustomerSessionContext originalSession, Instant now)` and package companion `Enrollment recoveryEnrollment(Reservation reservation, CustomerSessionContext originalSession, Instant now)`; preserve existing setup/reply/pending validators and old call sites.

- [ ] Write pure no-transaction guard regression plus hosted genuine browser composition cases before production edits. Use actual SETUP/binding/METHOD writer/lifecycle/observer completion/reserveRecovery; no direct positive proof/ACK/completion inserts. Assert repeated inspection changes no SQL business/provider/receipt state and makes zero SDK calls; original consumed root is preserved and no fresh browser action is minted; wrong/partial/missing enrollment, new live session, revoked original, changed completion/association/operation/sequence/debt fail. Lock-race tests witness actual blocking and release only after revocation or DB UTC expiry; do not rely on call timeout as refusal proof. Review the retained V36 historical permit to establish that no current-root-expiry predicate is introduced; do not wait ten minutes or rewrite original immutable roots to manufacture an expired-root positive.
- [ ] Root runs pure guard RED for absent override behavior and compiles hosted sources; local hosted execution forbidden. If entry guard already rejects through default port, use minimal public production-API presence RED then hosted behavior remains remote-only; record limitation accurately.
- [ ] Implement caller transaction/lock ordering, existing completed/recovery SQL predicates plus `owned_method_browser_permit(r.setup_operation)` (missing/uncommitted receipts refuse), and exact session checks with fresh post-lock DB UTC, exact Java expiry identity, no root expiry reuse or ordinary fallback. Factor only repeated exact enrollment/session SQL mechanics if needed; do not duplicate a full authorization block.
- [ ] Freeze, root reruns related pure selectors and compile-only hosted cases; documentation accurately records scope and pending new exact-head hosted proof. Fresh task spec+quality review then root commit.
- [ ] Root obtains broad fresh whole-change review (one consolidated final fix wave and scoped re-review if necessary), commits fixes, pushes existing branch and waits for both full and dedicated renewal CI at exact head. Record receipts in this plan's ignored workspace. Preserve reports; full5B/native acceptance stays unclaimed.
