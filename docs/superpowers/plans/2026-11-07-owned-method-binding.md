# Original Confirmed Method Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace handwritten browser-path confirmed SetupIntent/card reads and direct proof binding with authenticated original binding production.

**Architecture:** Fresh service/JDBC unbound inspection and caller-transaction atomic binding; a separate explicit SDK reader validates two original objects and hands evidence to that transaction. Existing METHOD and lifecycle/FactService remain independent.

**Tech Stack:** Existing Java25/Maven, cached JUnit/SDK inert transport, stripe-java34.0.0/API2026-09-30.endive, PostgreSQL hosted only in GitHub CI.

**Spec:** `docs/superpowers/specs/2026-11-07-owned-method-binding-design.md`

## Global Constraints

- No local Docker/Sandbox/Stripe/provider/financial/old DISPATCHING/Python/install/operational keys/archives or local SQL/Spring/browser/server/socket. Root-only audited cached explicit pure verification; genuine hosted sources compileOnly locally, execute in GitHub CI.
- Two sequential source writers, observed RED before main implementation, independent spec+quality review after each task, one whole-change review/consolidated fix wave, commit/push/exact-head full+dedicated CI. Root owns execution/Git/network. Workers cannot execute tests/compiler/runtime/Git/network or spawn agents.
- No new dependency, migration, route/bean/runtime command or changes to ordinary binding/write helpers. Preserve existing worktree/reports; no PR/merge/cleanup or routine user gate.
- Original unbound SETUP ACK/v2, canonical fingerprint, committed browser consumption, management key and exact live original enrollment are mandatory. New default-denied ports. Existing bound proof never adopted.
- Immutable30s UTC+real/injected monotonic invocation; two logical GET maximum, each≤2s with original session expiry at worker entry, zero application/SDK retry, pinned API, no idempotency key. Fixed cause-free binding error; no stop/write/provider step on read failure.

## Review Focus

- A stale descriptive unbound context cannot authorize binding after session expiry/revocation or proof drift: Task1 actual-service negatives and genuine after-lock hosted controls.
- A second valid session with the original management key cannot replace the enrolled session: Task1 service and genuine hosted controls.
- Ordinary proof-store REQUIRES_NEW must never escape the new transaction: Task1 genuine rollback after actual conditional binding retains unbound proof.
- Credentials/client resolution or SDK scheduling may consume the budget/session: Task2 caller reinspection and worker zero-entry cases, exact expiry and independent monotonic controls.
- Two concurrent readers may see the same unbound proof; only one binding succeeds and the loser cannot replace/stop it: Task1 conditional store race and Task2 final-handoff refusal without POST or stop.

---

### Task 1: Authenticated unbound inspection and atomic binding

**Files:** Modify provider application `model/OwnedFailedRenewalMethod.java`, `port/out/OwnedFailedRenewalMethodStore.java`, `service/OwnedFailedRenewalMethodService.java`, persistence `JdbcOwnedFailedRenewalMethodStore.java`; add pure `provider/application/service/OwnedMethodBindingPreparationTest.java`; extend necessary explicit Harness in `OwnedFailedRenewalMethodServiceTest.java` and `OwnedMethodSetupInertFixture.java`, existing inert `OwnedMethodBrowserStoreTransactionTest.java`, hosted `OwnedFailedRenewalIntegrationTest.java`.

**Interfaces produced:**
```java
// Nested record; all fields required and redacted toString.
record BindingPreparationContext(SetupPreparationContext original,
    SubscriptionBillingProofStore.Setup setupProof, ProviderOperation setupStep) {}
// Both defaults deny. All store work joins caller transaction.
BoundSetup requireBrowserUnboundSetupInTransaction(Reservation r,
    CustomerSessionContext originalSession, Instant now);
BoundSetup bindBrowserSetupInTransaction(BindingPreparationContext expected,
    CustomerSessionContext originalSession, String method, Instant now);
// Fresh public service boundaries; no provider/stop/lifecycle effects.
BindingPreparationContext inspectBrowserBindingPreparation(Reservation r,
    SubscriptionCommandContext originalContext);
SubscriptionBillingProofStore.Setup bindBrowserMethod(BindingPreparationContext expected,
    SubscriptionCommandContext originalContext, String method);
```
`BoundSetup` is an existing descriptive receipt wrapper; its proof may be unbound only for this new distinct port, with explicit service validation. The existing bound inspection's contract remains unchanged.

- [ ] Write tests first through actual service/authority/explicit unbound proof and ACK. Pin success before any METHOD, committed-consumption requirement, exact original identity/key/debt/sample/enrollment/chronology/canonical ACK, no state changes on inspect; binding updates only exact proof with no financial/lifecycle/provider changes. Pin missing/default store transaction refusal, malformed method, context drift, already bound, competing bind, failed/expired/revoked final session and second live session refusal, final rollback and toString/null coverage. Freeze for root closure audit and expected missing-API RED.
- [ ] Root rebuilds a fresh current-plan cached verification helper, explicit existing method/service/browser/model/store/provider guard/SETUP/reader/METHOD selectors plus new BindingPreparation test; hosted source compileOnly. Audit transitive pure fixture closure and execute RED before main source writing. Never copy or read sibling SDD workspace files.
- [ ] Implement descriptive model/default-denied ports and private shared binding inspection body using existing original setup management/debt/key checks. Require unbound original proof/ACK and committed permission; final reauth after store work. Bind in a separate fresh transaction by reinspection/equality, conditional caller store write and final current authority/exact enrollment; return actual proof without a postcommit failure.
- [ ] Implement JDBC unbound port with original pending/enrollment, canonical SETUP ACK/v2/time/root chronology and committed permit. Atomic bind matches all immutable proof columns and null method, requires exactly one changed row, final enrollment check. Preserve V35/V36 and ordinary proof store; do not call ordinary REQUIRES_NEW bindMethod. Use existing row/lock helpers without duplicating the prior bound-inspection logic where a narrow private validator suffices.
- [ ] Add genuine hosted success before METHOD, uncommitted-consumption savepoint refusal, actual binding rollback, final lock expiry and second live session controls. No SDK/browser runtime locally. Root compiles all main and hosted sources, executes audited pure regression, checks hashes/whitespace, commits scoped task and gets fresh independent spec+quality review.

### Task 2: Independent succeeded SetupIntent/card reader and genuine composition

**Files:** Add `provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalBindingAdapter.java` and matching pure test; extend only required explicit inert fixture helpers. Modify browser branch of hosted `OwnedFailedRenewalIntegrationTest.java` and current sections of `docs/stage-5b-acceptance.md`, `docs/testing/billing-failed-renewal-recovery.md`, `docs/local-development.md`.

**Consumes:** Task1 binding inspection/atomic bind/context; existing `StripeSubscriptionProviderAdapter.requireSetupRemote`, `StripeCredentialResolver`, `StripeClientFactory`, unchanged actual METHOD adapter, original browser fixture/context/token.
**Produces:**
```java
public StripeBillingOwnedFailedRenewalBindingAdapter(OwnedFailedRenewalMethodService methods,
    StripeCredentialResolver credentials, StripeClientFactory clients, Clock utc);
// Package-private overload appends LongSupplier, with actual System.nanoTime enforced too.
public SubscriptionBillingProofStore.Setup bind(Reservation r,
    SubscriptionCommandContext originalContext);
```

- [ ] Write official SDK inert tests first using actual service and Task1 unbound fixture. Assert exactly GET original seti then GET its pm, original customer/account/sandbox/card/metadata/time validation, options/version/retries/timeouts/no idempotency, no POST/financial/lifecycle/provider-row effects. Cover bad object/ref/customer/status/method/type/card/live/metadata/window, bound-before-entry zero GET, changed authority/context between reads and before bind, credential/client delay, worker scheduling exact expiry, UTC rollback, immutable real+injected budget, interrupted/timeouts/errors with fixed safe reason/cause absence. Freeze root audit then intended missing-adapter RED.
- [ ] Implement one explicit non-bean adapter/private bounded GET mechanics. Full actual context reinspection before first/second read and bind; worker has only SDK/clock/time capabilities. Two logical calls max, pinned zero-retry options, SDK runtime exceptions normalized safely, no guarded stop or new provider operation. Successful committed service binding is returned without postcommit rejection. Do not refactor existing readers/writers or invent native execution.
- [ ] Replace only browser branch of boundMethod hosted fixture with this adapter while retaining genuine SDK succeeded SetupIntent/card response bodies, consumed original browser receipt and original management context. Nonbrowser manual path unchanged. Verify composition through existing actual METHOD/lifecycle/observer/FactService and retained rollback controls; add malformed remote/refusal/concurrent final binding/expiry cases proving unchanged debt/cash/events/callbacks and original unbound/ACK state. Hosted code compileOnly locally.
- [ ] Update current acceptance/guide/local-development sections with baseline5297e40 receipt and new-source publication gate, precise two-GET semantics and remaining native/runtime/recovery/time/grace/tax/decline/callback gaps. Root executes same audited pure manifest plus adapter, compiles hosted, checks all hashes/whitespace and commits; independent task review.
- [ ] Fresh broad whole-change review from5297e40; one consolidated tests-first fix wave/scoped re-review if needed. Root pushes existing branch and verifies exact-head full CI and dedicated renewal. Record safe numeric receipts in current ignored report, preserve all reports/worktree and explicit nativeNOT_RUN/full5BNOT_ACCEPTED. No local Docker/Sandbox or native operation.
