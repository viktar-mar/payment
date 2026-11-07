# Original Owned Method Application Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce one authenticated original METHOD provider ACK from the independently bound original SETUP proof, replacing the hosted browser-path manual update.

**Architecture:** An enriched fresh service/JDBC inspection authenticates current original authority plus committed browser permission and bound proof. An explicit adapter uses the existing typed original write lane; shared package-private mechanics preserve SETUP and METHOD deadline/cancellation/cleanup behavior. Lifecycle transition and observation stay independent.

**Tech Stack:** Existing Java25/Maven, Spring/JDBC/PostgreSQL, stripe-java34.0.0/API2026-09-30.endive, cached JUnit/official inert SDK, GitHub CI.

**Spec:** `docs/superpowers/specs/2026-11-07-owned-method-apply-design.md`

## Global Constraints

- No local Docker/Sandbox/Stripe/provider/financial execution, old operation/DISPATCHING reuse, Python/install, operational keys/archives, local SQL/Spring/browser/server/socket.
- Local execution only root-audited explicit cached pure JUnit; hosted integration source compileOnly. No default local Maven/test discovery. No schema/migration/runtime/ordinary provider/SDK/public-route/time/tax/recovery changes.
- Two sequential source writers, tests first with root closure audit and observed RED before behavior implementation. Root owns all Git/publication and exact-head full/dedicated CI. Preserve reports/worktree; no merge/PR/cleanup or routine user gate.
- New adapter consumes bound proof; independent setup/card verification and binding remain the genuine hosted predecessor. It returns ACK evidence only, never invokes recordMethodUpdateReply or publishes a fact.

## Review Focus

- Own METHOD ACK must not be a precondition for its first command; missing/uncommitted browser consumption still refuses — Task1 JDBC positive/negative controls.
- Known enrollment expiry may be earlier than all write budgets — Task2 worker UTC exact-expiry−500ms/exact-expiry zero-entry regression.
- Successful ACK followed by failed/expired lifecycle transition must remain ACK with no repeat — Task2 real hosted rollback/dead-session controls.
- Shared helper refactoring must retain SETUP worker checks, independent monotonic limit and final late-proof semantics — Task2 unchanged SETUP regression selection.
- Partial proof/context drift and insert/claim competition must not grant replacement or stop the winner — Task1 context drift and Task2 deterministic interleaving/holder controls.

---

### Task 1: Original bound-setup inspection before METHOD

**Files:** Modify under `backend/src/main/java/dev/genericpayments/platform/provider/`: `application/model/OwnedFailedRenewalMethod.java`, `application/service/OwnedFailedRenewalMethodService.java`, `application/port/out/OwnedFailedRenewalMethodStore.java`, `adapter/out/persistence/JdbcOwnedFailedRenewalMethodStore.java`. Add `backend/src/test/java/dev/genericpayments/platform/provider/application/service/OwnedMethodApplicationPreparationTest.java`. Modify necessary explicit Harness cases in existing `OwnedFailedRenewalMethodServiceTest.java`, existing `OwnedMethodBrowserStoreTransactionTest.java`, and hosted `adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java` under the test provider root.

**Interfaces produced:**
```java
// Nested descriptive, all fields required, redacted toString.
record BoundSetup(OwnedMethodBrowser.Enrollment enrollment,
    SubscriptionBillingProofStore.Setup setupProof, ProviderOperation setupStep) {}
record MethodPreparationContext(SetupPreparationContext original,
    SubscriptionBillingProofStore.Setup setupProof, ProviderOperation setupStep) {}
// Default denied with existing fixed browserDenied error.
BoundSetup requireBrowserBoundSetupInTransaction(Reservation reservation,
    CustomerSessionContext originalSession, Instant now);
// Public existing method service, fresh transaction, no writes/stop.
MethodPreparationContext inspectBrowserMethodPreparation(Reservation reservation,
    SubscriptionCommandContext originalContext);
```

- [ ] Write actual-service pure tests first for original bound SETUP/ACK success before METHOD, success with an existing own METHOD row, no state change; all original authority/key/debt/time drift refusals and missing/unbound/foreign/version/lease/fingerprint proof/ACK controls; model null/redaction. Extend explicit inert store cases with no permissive fallback. Add typed default-denial/caller-transaction control to existing persistence selector. Stop for root audit.
- [ ] Root recreates this plan's cached-only verification helper and explicit `task-1-pure.json`: new `OwnedMethodApplicationPreparationTest`, existing `OwnedMethodSetupPreparationTest`, `OwnedFailedRenewalMethodServiceTest`, `OwnedFailedRenewalServiceTest`, `OwnedMethodBrowserServiceTest`, `OwnedMethodBrowserModelTest`, `OwnedMethodBrowserStoreTransactionTest`, `OwnedFailedRenewalMethodStoreTransactionTest`, `OwnedFailedRenewalMethodProviderGuardTest`; compileOnly `OwnedFailedRenewalIntegrationTest`. Run `node .superpowers/sdd/2026-11-07-owned-method-apply/verify-pure.mjs task-1-red .superpowers/sdd/2026-11-07-owned-method-apply/task-1-pure.json`; expected only missing new API/model compile errors. Repair unrelated test errors before counting RED.
- [ ] Implement models/port and fresh service inspection. Extract the existing SETUP inspection transaction body to a private helper reused within the same caller transaction; do not nest public fresh transactions or change prior behavior. New inspection compares enrollment/proof/SETUP ACK identity, then revalidates current original authority/session after store work. Invalid clients never call guarded stop.
- [ ] Implement JDBC new method using existing `requireStepResource` SUBSCRIPTION_UPDATE/canonical SQL fingerprint/committed permit, genuine proof and original SETUP ACK readback with exact v2/identity/window/cleared lease, then existing exact enrollment/database UTC check after blocking work. Do not require METHOD ACK or absence; no SQL/migration changes. Keep new method default-denied elsewhere.
- [ ] Extract a private hosted bound-method fixture before manual METHOD reserve, preserving actual independent GET succeeded SETUP/card validations and bindMethod plus original context/token handoff. Add genuine JDBC pre-METHOD positives, valid own claim, missing/uncommitted consumption, bound-proof/ACK corruption and current-session exactness/after-lock expiry controls. Hosted source compiles only locally.
- [ ] Freeze source; root runs same manifest `task-1-green`, inspects zero failures/current hashes/whitespace, commits scoped change and obtains independent spec+quality review before Task2 source writing.

### Task 2: One original METHOD update and genuine composition

**Files:** Add `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalMethodAdapter.java` and package-private `StripeBillingOwnedMethodWrite.java`. Refactor existing `StripeBillingOwnedFailedRenewalSetupAdapter.java` only to share mechanical budget/call/termination. Add matching new SDK test `backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalMethodAdapterTest.java`. Modify only necessary existing pure fixture/Harness helpers, hosted `OwnedFailedRenewalIntegrationTest.java`, and current sections of `docs/stage-5b-acceptance.md`, `docs/testing/billing-failed-renewal-recovery.md`, `docs/local-development.md`.

**Consumes:** Task1 `MethodPreparationContext`/inspection; typed `reserveOwnedFailedRenewalMethodStep`/`requireOwnedFailedRenewalMethodDispatch`, existing find/claim/acknowledge/unknown and ProviderOperationGuard; existing StripeRequestFingerprint; original bound fixture.

**Interfaces produced:**
```java
public StripeBillingOwnedFailedRenewalMethodAdapter(OwnedFailedRenewalMethodService methods,
    ProviderOperationStore steps, StripeCredentialResolver credentials,
    StripeClientFactory clients, DigestPort digest, Clock utc);
// Package-private overload appends LongSupplier; real System.nanoTime also enforced.
public ProviderOperation apply(Reservation reservation, SubscriptionCommandContext originalContext);
// Internal shared mechanics, no registration/public API/retry framework.
final class StripeBillingOwnedMethodWrite {
    interface Call<T> { T run(RequestOptions options) throws StripeException; }
    static final class Budget {
        Budget(Clock utc, LongSupplier nanos);
        long remaining(ProviderOperation claim);
        <T>T call(ProviderOperation claim, Instant originalSessionExpiry,
            String dispatchKey, Call<T> work);
    }
    static void terminate(OwnedFailedRenewalMethodService methods, ProviderOperationStore steps,
        Reservation reservation, ProviderOperation claim);
}
```

- [ ] Write official SDK memory tests first through actual method-service authority and independently populated original proof/ACK/consumption inert state, not fabricated inspection context. Verify one POST original subscription with only default_payment_method, exact original key/fingerprint/options/ACK and unchanged business state/SETUP receipt. Add complete old-step, concurrency/claim loser, malformed response/identity, authority/proof drift, uncertainty/cleanup/ACK-kept/no-repeat and immutable time/interruption/worker exact-session-expiry controls. Stop for root closure audit before execution or main source.
- [ ] Root `task-2-pure.json` adds new SDK test, existing `StripeBillingOwnedFailedRenewalSetupAdapterTest` and `StripeOwnedMethodBrowserReaderTest` to Task1 audited selectors; hosted remains compileOnly. Run helper under `task-2-red`; require expected missing METHOD adapter errors only before granting implementation.
- [ ] Extract shared mechanical write helper from SETUP without altering its contract or tests: immutable UTC30s/independent real+injected monotonic30s, original lease, SDK call/connect/read≤2s, pinned API2026-09-30.endive, zero retries, worker entry interruption/deadline/exact session expiry, cancellation and caller flag, matching-claim UNKNOWN plus caller-only stop. Do not generalize request/authority/response handling. Run SETUP regressions as part of approved manifest, no special native test.
- [ ] Implement METHOD adapter using Task1 fresh context and only SUBSCRIPTION_UPDATE typed lane; refuse any preexisting original :METHOD step, verify exact SUBSCRIPTION_METHOD_V1 fingerprint/first RESERVEDv0/first DISPATCHINGv1 claimant with60s lease+23h window. First valid claimant wins independent of inserter; before ownership no cleanup. Reinspect full context at caller boundaries and typed dispatch before one SDK update containing only default_payment_method. Validate exact original response ID/object/sandbox/customer/method before genuine ACK/v2; return matching ACK without late post-commit recheck. Holder failure fixed cause-free OWNED_METHOD_APPLY_UNAVAILABLE/409/Owned method application unavailable, no ACK downgrade/repeat.
- [ ] Browser-only hosted bound fixture uses actual adapter instead of manual METHOD dispatch; preserve independent GET+binding, nonbrowser manual path, external actual recordMethodUpdateReply and observer→FactService composition. Add real no-consumption refusal, exact request/options, uncertainty/no-repeat, retained ACK with genuine lifecycle-save rollback, and final ACK crossing revoke/expiry followed by refused lifecycle transition. No seeded successful lifecycle/ACK evidence or native command.
- [ ] Update current matrix/guide with baseline dc3c470's exact hosted scope and a successful-published-commit gate for these new cases; retain native binding/runtime/time/tax/decline/grace/recovery gaps/full5B NOT_ACCEPTED. Freeze; root runs `task-2-green` approved manifest, checks current source hashes/whitespace, commits and independently reviews this task.
- [ ] Root obtains one fresh whole-change review from dc3c470, resolves one consolidated final fix wave if needed, pushes existing codex/billing-wait-snapshot and verifies exact-head full CI plus dedicated renewal. Preserve all reports/worktree; no merge/PR/cleanup/local runtime.
