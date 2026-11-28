# Authenticated owned browser SETUP preparation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the browser fixture's manual SetupIntent producer with a genuine authenticated one-shot server adapter, staying offline locally.

**Architecture:** A fresh application-service inspection authenticates the original enrolled reservation before SETUP exists. An explicitly constructed Stripe adapter uses existing typed provider steps, validation and proof persistence; the existing browser issuer and independent method completion consume its actual proof.

**Tech Stack:** Java25, existing Maven/JUnit/official Stripe SDK/PostgreSQL dependencies; cached pure compilation locally and hosted synthetic integration only.

**Spec:** `docs/superpowers/specs/2026-11-07-owned-method-setup-design.md`

## Global Constraints

- No local Docker, Sandbox/provider requests, financial execution, Python/install, local SQL/Spring/browser/server/socket, operational key/archive access or old operations/DISPATCHING adoption.
- No runtime/route/bean/export/Node/SDK registration, METHOD/recovery payment/time/grace/tax changes or native acceptance; prior migrations unchanged.
- Sequential source implementers, pure closure audit before execution, independent task and whole-change review; root owns Git/publication. Preserve reports/worktree and continue without routine user questions.
- One logical original SDK create, zero application/SDK retries, API2026-09-30.endive, complete-call/connect/read <=2s, invocation UTC+30s plus real/injected monotonic30s and claim60s. No physical-wire guarantee or transport change.
- Before ownership, duplicate/foreign/losing callers must not stop another holder. The successful first DISPATCHING/v1 claimant owns the invocation, independently of which insert won.
- ACK/proof are independent existing short transactions. A valid unbound proof may finish after the last successful inspection even if the session is then revoked/expired; later browser authority still refuses. Never claim atomic enrollment validation across those transactions or fail merely because the successful final proof commit completed late.

## Review Focus

- Inspecting before proof exists must authenticate current session/debt, not an immutable caller description — Task1.
- Session expiry while waiting on locks must refuse using fresh database UTC — Task1.
- Concurrent claimant loss must not stop the actual winner or repeat a provider command — Task2.
- Provider success followed by ACK/proof failure remains terminal without ACK downgrade or retry — Task2.
- Ordinary/non-browser flow and all previously verified browser/completion gates remain unchanged — both tasks and final CI.

### Task 1: Authenticated pre-SETUP inspection

**Files:** Modify `backend/src/main/java/dev/genericpayments/platform/provider/application/model/OwnedFailedRenewalMethod.java`, `provider/application/service/OwnedFailedRenewalMethodService.java`, `provider/application/port/out/OwnedFailedRenewalMethodStore.java`, `provider/adapter/out/persistence/JdbcOwnedFailedRenewalMethodStore.java`, `provider/adapter/out/persistence/JdbcOwnedMethodBrowserStore.java` under that main Java root. Add test `backend/src/test/java/dev/genericpayments/platform/provider/application/service/OwnedMethodSetupPreparationTest.java`. Modify only the necessary inert Harness in `OwnedFailedRenewalMethodServiceTest.java`, the existing `OwnedMethodBrowserStoreTransactionTest.java`, and hosted `provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java` under the test Java root.

**Interfaces produced:**

```java
// Nested in OwnedFailedRenewalMethod; validate non-null, redact toString.
record SetupPreparationContext(Reservation reservation,
    OwnedMethodBrowser.Enrollment enrollment, PaymentAccount account,
    Subscription subscription, SubscriptionOperation operation,
    CustomerBinding binding, SubscriptionBillingTimePort.Sample billingSample) {}

// New public inspection on existing method service; no ambient transaction.
SetupPreparationContext inspectBrowserSetupPreparation(
    Reservation reservation, SubscriptionCommandContext originalContext);

// New default-denied caller-transaction method-store port.
OwnedMethodBrowser.Enrollment requireBrowserSetupEnrollmentInTransaction(
    Reservation reservation, CustomerSessionContext originalSession, Instant now);
```

- [ ] Write pure tests first in `OwnedMethodSetupPreparationTest`: genuine browser reservation succeeds before any ACK/proof; no fields/receipt/operation/debt/fence/stop changed. Refuse wrong/replaced/non-customer/expired session, changed original key, origin/owner, missing enrollment, ambient transaction, stopped authority, operation version/status, debt/binding/sequence/sample drift. Add the new exact case to the existing inert Harness with no default permissive behavior; old pending still requires ACK. Add the store transaction/default-denial control, not a reflection-only existence test.
- [ ] Root audits the new test closure before execution. Root creates `task-1-pure.json` in this plan workspace with only the new class plus existing `OwnedFailedRenewalMethodServiceTest`, `OwnedMethodBrowserServiceTest`, `OwnedMethodBrowserModelTest`, `OwnedMethodBrowserStoreTransactionTest`, `OwnedFailedRenewalMethodStoreTransactionTest`, `OwnedFailedRenewalMethodProviderGuardTest`; compileOnly contains `OwnedFailedRenewalIntegrationTest`. Run `node .superpowers/sdd/2026-11-07-owned-method-setup/verify-pure.mjs task1-red .superpowers/sdd/2026-11-07-owned-method-setup/task-1-pure.json`; expected failure at missing inspection/context/store API. Compile errors unrelated to intended API are repaired before counting behavioral coverage.
- [ ] Implement the record and fresh inspection reusing `current(r,c,null)`, original `requireDebt(...,0)`, selected SETUP RESERVED/v0, method authority/request fingerprint and digest of the original reservation idempotency key. Authenticate original management scope twice as appropriate around existing locks. No `guarded` stop for an invalid client; no provider step, setup/root, fact or native call.
- [ ] Implement the JDBC enrollment check independently of ACK/proof/pending. Reuse unchanged V36 snapshot/scopes/session/lifecycle/database-time semantics; after existing locks require supplied UTC <= DB UTC, both times before original expiry, current persisted original session/site/customer/scopes, and non-null exact enrollment. Keep old pending/root/selection functions unchanged or refactor only shared identical validation. No migration or FK change.
- [ ] Extract a private hosted `ReservedMethod` fixture from the first half of existing pendingMethod: authentic setup/failure observer, original management authentication and reservation, no provider producer. Preserve its redacted original raw-token handoff for existing genuine HTTP tests. Hosted positives must show pre-proof context through actual JDBC; negative controls include a valid second session, default non-browser row, revoked/expired live session and expiry after blocked locks. Keep prior non-browser and browser pending positives/negatives. Compile only locally.
- [ ] Run the same approved manifest under fresh `task1-green` label, verify zero failures and current hashes; preserve every earlier failure. Do not run default Maven or any hosted test locally. Root commits the scoped verified change and generates the review package; independent task review must approve before Task2 source writing.

### Task 2: Original SETUP provider adapter and genuine composition

**Files:** Add `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalSetupAdapter.java` and matching test `backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalSetupAdapterTest.java`. Modify hosted `OwnedFailedRenewalIntegrationTest.java`. Modify `docs/stage-5b-acceptance.md`, `docs/testing/billing-failed-renewal-recovery.md` and relevant current section of `docs/local-development.md`. Do not modify ordinary provider behavior, SDK, runtime, exporter or earlier migrations.

**Consumes:** Task1 `SetupPreparationContext` and `inspectBrowserSetupPreparation`, existing `ProviderOperationStore.reserveOwnedFailedRenewalMethodStep` / `requireOwnedFailedRenewalMethodDispatch` / find/claim/acknowledge/unknown, `ProviderOperationGuard`, `SubscriptionBillingProofStore.authorizeSetup/recordSetup`, `StripeRequestFingerprint`, same-package `StripeSubscriptionProviderAdapter.requireSetupRemote`, and Task1 reserved fixture.

**Produces:**

```java
public StripeBillingOwnedFailedRenewalSetupAdapter(
    OwnedFailedRenewalMethodService methods, ProviderOperationStore steps,
    SubscriptionBillingProofStore proofs, StripeCredentialResolver credentials,
    StripeClientFactory clients, DigestPort digest, Clock utc);
public SubscriptionBillingProofStore.Setup prepare(
    OwnedFailedRenewalMethod.Reservation reservation,
    SubscriptionCommandContext originalContext);
// Package-private constructor appends LongSupplier nanos for inert tests only.
```

- [ ] Author inert SDK tests first using actual service/typed values and official in-memory SDK transport. No fake returned context supplies authority. Reuse existing pure fixtures or add a test-only same-package bridge where necessary; root audits the full transitive closure before execution. Verify request body/key/metadata, first-claim winner, no transaction/worker store I/O, absence of existing-step adoption and safe unbound proof. Add failure/terminal/budget tests from the spec. The returned Setup contains no secret; test that the producer never consumes or returns the provider client secret.
- [ ] Root writes `task-2-pure.json` by adding the new SDK test and existing `StripeOwnedMethodBrowserReaderTest` to Task1 manifest. Run the owned helper under `task2-red`; expected missing adapter failure. No new test execution without root audit; no external HTTP client, server or loopback socket. SDK responses are synthetic memory transport only.
- [ ] Implement one explicitly constructed adapter. Authenticate Task1 context at entry, refuse existing proof or any step state, use the exact typed original reserve and independently compare fingerprint to existing SETUP_INTENT_CREATE_V1 field encoding. Claim once (60s), require RESERVED/v0 before claim and first DISPATCHING/v1 after, and enforce exact original account/resource/key/fingerprint/claimant. Both concurrent inserters may compete for that one first claim; a loser does not stop the winner.
- [ ] Reinspect/compare full context on the caller around reserve/claim/dispatch/acceptance, call authorizeSetup and typed dispatch plus ProviderOperationGuard before the one SDK call. Build pinned RequestOptions with original :SETUP key, API2026-09-30.endive, zero retries and <=2s connect/read/complete-call. Enforce immutable entry UTC+30s and real/injected monotonic30s plus original lease before dispatch/acceptance after locks. The worker only performs SDK create and may be canceled; caller interrupt is preserved. No retries, account GET, probe, confirmation or other write.
- [ ] Send original customer, OFF_SESSION/CARD and exact five account/site/customer/subscription/operation metadata values. Reuse requireSetupRemote and additionally require unbound requires_payment_method and exact full metadata. Revalidate, acknowledge original SDK ID, revalidate and record the actual proof. Require returned proof matches all original identities and method null. Return durable success without a later clock/session recheck after the valid final commit.
- [ ] After acquired ownership, failure stops once outside active transactions. Mark UNKNOWN only for the original still-DISPATCHING claim; preserve any ACK, including an ACK whose later proof failed. Do not leak cause/provider/error/secret text and do not let a failed cleanup create replacement authority. Tests include every old state/duplicate/claimant loser; malformed/live/foreign/terminal/prebound responses; authority/time drift; transport/timeout/interrupt; ACK/proof failures; and a final successful proof commit crossing expiry/revocation. Before-last-inspection revocation refuses, after-last-inspection durable unbound proof may complete and later browser issuance must refuse.
- [ ] In hosted `pendingMethod`, browser=true invokes the adapter using actual original reservation/context and queues only its inert SDK response. Preserve the previous manual non-browser producer and the complete genuine browser→independent SETUP/card→METHOD ACK→observer/FactService chain. Assert one exact request/key/options/full metadata; SETUP remains RESERVED/v0, proof unbound, no browser root/METHOD/lifecycle fact, unchanged cash/debt/entitlement/revision/sequence. Add genuine uncertainty/no-repeat/ACK-kept and post-inspection revoke/proof receipt controls. No seeded ACK/SUCCEEDED row or native command.
- [ ] Run the approved related manifest under `task2-green`, compile all main plus genuine hosted closure, verify hashes and whitespace, and record results in this plan only. Update docs: attach the verified87effaa baseline to its actual hosted scope, describe this producer's local source versus exact-published-checks boundary, and retain missing runtime/time/tax/decline/recovery/native acceptance explicitly. Do not publish broken links to ignored local reports.
- [ ] Root commits and independently reviews Task2, then performs one fresh whole-change review from87effaa and handles findings through the skill workflow. Publish to existing branch and check exact-head GitHub full CI plus dedicated renewal gate. Preserve all reports/worktree; no merge, PR, cleanup, local Docker or Sandbox run.
