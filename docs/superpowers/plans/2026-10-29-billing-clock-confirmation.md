# Owned initial Subscription confirmation implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development task by task with independent reviews. Execute continuously through ordinary commits/push and exact-head hosted CI; user explicitly opted out of intermediate approval pauses.

**Goal:** Confirm one original owned initial Subscription payment and prove its authoritative CASH projection without native execution.

**Architecture:** Domain-separated persisted action root and caller-transaction owner guards feed the existing v2 chain/attempt service. Explicit nonregistered owned action/confirmation adapters use the original receipt and shared bounded graph mapper; fresh observation settles through existing FactService/cash/journal/callback services.

**Tech Stack:** Existing Java25/Spring/JDBC/PostgreSQL, stripe-java34.0.0/API2026-09-30.endive, Maven/JUnit; existing SDK v2. No new dependencies.

**Spec:** [Owned initial confirmation](../specs/2026-10-29-billing-clock-confirmation.md).

## Global Constraints

- No local Docker, DB/SQL execution, Spring/application context, ApplicationContextRunner, Testcontainers, browser, Stripe/provider/financial calls, Python, installation or private environment/key/archive/recovery reads. Only cached offline compilation and exact audited standalone pure tests locally; full PostgreSQL/application/Fake/browser regression runs in existing hosted synthetic CI.
- Preserve historical EUR13/DISPATCHING records, original three untracked paths, previous evidence/worktree, ordinary descriptor/fingerprint bytes and migration history. Root owns Git; no merge/PR/forcepush/cleanup.
- One source writer at a time; independent task reviews and final combined review required. Ordinary public/v1 paths refuse admitted owners/new owned schema. No production registration, automatic lane selection, public route, new SDK protocol, retry/successor, advance/trial/renewal/mutation or native launcher. Native Billing NOT_RUN; full5B NOT_ACCEPTED.
- Server outcome is only OBSERVE/REQUIRES_ACTION/UNKNOWN; only fenced full CASH observation settles. Fixed owner F never replaces UTC for expiry/audit/dispatch/security. Provider I/O has no active transaction and zero retries.

## Review Focus

1. Owned root crosses into public/v1/default v2 or ordinary root claims a clock receipt: Task1 DB/schema/service negative tests; Task3 default service regressions.
2. Suspended outer transaction blocks its own owner checks or reverses provider/lifecycle lock order: Task1 MANDATORY/runtime-active/transaction-identity tests; Task3 finite stop/FactService contention.
3. Replayed or ambiguous Pay dispatches again or creates successor: Task1 existing state-machine/owned retry guards; Task2 exact SDK requests; Task3 duplicate/concurrent/lost-response tests.
4. Successful command or browser 3DS bypasses cash proof: Task2 strict response/state tests; Task3 no-cash-before-observation and exact paid graph/journal/outbox tests.
5. F/opaque timestamps or foreign PI/receipt substitution escape scope: Task2 graph/clock/drift matrix; Task3 UTC expiry and stale observation/state controls.

### Task 1: Persisted owned root and transaction-safe confirmation scope

**Files:** modify SubscriptionClientGrant/SubscriptionActionStore/SubscriptionActionPersistenceAdapter, StripeBillingClockSubscriptionStore/JdbcStripeBillingClockSubscriptionStore, SubscriptionInteractionTransaction/SubscriptionConfirmationService; additive `V32__stripe_billing_owned_initial_confirmation.sql`; CurrentSchemaVersions; new pure `OwnedInitialConfirmationContractTest` and hosted `StripeBillingOwnedConfirmationStoreIntegrationTest`. Add narrow helper/model only where needed to avoid duplicated logic; document concrete interfaces for Task2.

**Interfaces:**
- Binding adds nullable `UUID ownedIntent` while preserving existing constructors/ordinary descriptor bytes; schema `owned-initial-v1` maps to SUBSCRIPTION_CREATE/PAYMENT. Owned descriptor/proof includes its distinct domain and original receipt.
- `StripeBillingClockSubscriptionStore.requireOrdinaryConfirmation(PaymentAccountId,SiteId,CustomerId):void` and `requireConfirmationReceipt(OwnedClockSubscriptionReceipt,PaymentAccount,Subscription,SubscriptionOperation):OwnedClockSubscriptionReceipt` are caller-transaction MANDATORY with explicit runtime-active checks.
- `SubscriptionInteractionTransaction.confirmationLocked(context,subscription,operation,expectedReceipt,Function<State,T>)` is public typed owned overload; existing ordinary overload always runs ordinary exclusion. Both enforce actual authority before work. Function is work, never an allow predicate.
- `SubscriptionConfirmationService` retains ordinary @Autowired construction; explicit non-autowired construction with same dependencies plus nonnull original receipt binds one owned flow. Shared locked/load checks enforce lane/receipt; owned retry/predecessor fail. No provider replacement in production configuration.
- `SubscriptionActionStore.reserveOwnedInitialBilling(SubscriptionClientGrant,OwnedClockSubscriptionReceipt):void` participates in caller transaction and validates exact joins. Generic reserve and v1 bind/claim refuse owned lane/admitted owner. All variants use existing tables/state machine.

- [ ] RED on pure real descriptor/service-policy behavior: ordinary descriptor fixed vectors unchanged; wrong/missing owned linkage denied; default/owned scope cannot swap; owned retry/predecessor denied before provider; UTC-expired session fails with F far away. Audit exact test classes before execution, no context/sockets.
- [ ] Implement additive V32 exact receipt composite FK/uniqueness, schema↔linkage constraints, immutable linkage and DB insertion/transition guards. Owned CREATE cannot masquerade payment-v1; foreign/nonowned CREATE cannot claim owned schema. No historical migration edits.
- [ ] Implement mandatory guards and dedicated reservation, preserving operation→Site→Customer→Account→Subscription order and no provider-step write locks beneath lifecycle. No REQUIRES_NEW from locked phases; no actual provider calls. Reload full persisted proof rather than trust supplied records.
- [ ] Wire default and explicit owned v2 scope across select/session/status/current/confirm/replay/retry before chain mutation/disclosure. Preserve ordinary v2 retry and v1 descriptor/consumption behavior; adjust directly constructed test fixtures only as necessary for explicit authority dependencies.
- [ ] Author hosted FK/tamper/lane/mandatory/stop/current-owner/duplicate-root tests with real stores. They are compiled-only locally. Run exact audited pure tests and `mvn -o -f backend/pom.xml -DskipTests test-compile`; retain RED/GREEN/full logs, source manifest/SHA256/diff/report, self-review. Root independent review then commit.

### Task 2: Explicit owned action graph and one-shot SDK confirmation

**Files:** new `StripeBillingClockActionAdapter` and `StripeBillingClockConfirmationAdapter`; typed owned proof/helper in stripe/billing if needed; modify StripeBillingSnapshotMapper and narrow shared grant/capability helper only where reuse warrants; new `StripeBillingOwnedConfirmationSdkTest` plus inert fixture support. Existing StripeBillingActionAdapter/StripeBillingConfirmationAdapter semantics remain unchanged except shared helpers with regression proof.

**Interfaces:**
- Owned action adapter is explicitly constructed with expected original receipt, Task1 guarded phases, stores/tokens/credentials/client/verification/UTC dependencies; `NextAction nextAction(SubscriptionConfirmationUseCase.Scope)` performs locked snapshot→unlocked graph→locked reservation.
- Owned confirmation adapter is explicitly bound to expected receipt and implements existing SubscriptionConfirmationProvider `capability/read/confirm`, with no component/registry activation. Task1 receipt-bound service supplies it via existing provider dependency.
- Add typed owned payment-action mapper entry consuming OwnedInitial and exact original proof; return existing ephemeral PaymentAction. Reuse shared full graph logic, no boolean bypass.

- [ ] RED: ordinary mapper refuses clock/opaque-created data while explicit owned action accepts exact original receipt, one initial OPEN allocation/PI and distinct F/UTC/created. Use official SDK inert HttpClient only.
- [ ] Implement owned action issuance with original receipt reload/READY preflight, exact full graph, actual authorized locked state comparison and dedicated root insert. Repeated reserved/consumed/uncertain roots cannot mint permission. Root/chain/attempt expiry uses UTC; 600-second root ceiling and SDK secret ceiling512. No raw secrets in descriptors/loggable outputs.
- [ ] Implement owned provider: refuse ambient transaction before collaborators, root/receipt/chain/predecessor/state/token-digest mismatch, revalidate durable consumed attempt and authority before POST, original ready clock and state around I/O. One original PI confirm uses attempt.providerKey, pinned API, zero retries, confirmation_token/use_stripe_sdk only. Reuse safe capability encoding; no ordinary ownership fallback.
- [ ] Accept strict exact `succeeded` with full received/null action → OBSERVE, or `requires_action` with zero received/type use_stripe_sdk/nonempty opaque map → REQUIRES_ACTION. Both require correct PI object/id/customer/mode/amount/currency/automatic capture+confirmation/card. Everything uncertain/malformed/declined/unsupported → UNKNOWN/no receipt/no successor; no money or access mutation.
- [ ] Cover wrong account/owner/receipt/F/created/item/period/graph/allocation/reverse/PI/secret, pre/post-I/O drift, every response-field/status mutation, direct ambient-transaction denial, no token/secret/error leakage, no extra provider methods. Preserve ordinary guard/key/vector regressions and existing SDK v2 contract without new browser runtime.
- [ ] Run exact audited pure SDK/related tests and complete offline test compilation. Retain meaningful RED/GREEN logs and frozen manifest/hash/diff/report; independent review then root commit.

### Task 3: Real paid composition and final acceptance checkpoint

**Files:** new hosted `StripeBillingClockConfirmationCompositionIntegrationTest`, `StripeBillingClockConfirmationRaceIntegrationTest` and narrow test fixture deriving existing StripeBillingClockSubscriptionCompositionFixture; existing test helpers only as needed; docs/stage-5b-acceptance.md and local-development.md. No production financial changes unless a concrete failing test proves the seam needs one and root records the decision.

**Interfaces:** construct Task2 adapters and Task1 receipt-bound service explicitly with real Spring phases/stores, using the original create/recordReply and test-only owned billing-time sampler. Use existing `SubscriptionConfirmationUseCase.Scope`, session/confirm/status and real observation fence/FactService.

- [ ] Author positive real create→action→v2 select→claim→one SDK confirm→fresh owned CASH observation. Seed only catalog/owner/session/callback prerequisites, never action/attempt/lifecycle success/payment rows. Before observation OBSERVE and REQUIRES_ACTION retain INCOMPLETE/no entitlement/no cash/journal/callback success.
- [ ] After exact paid graph assert original CREATE SUCCEEDED, one confirmed-cash bill/payment, real payment journal and PERIOD_PAID/access plus actual payment/subscription callback outbox snapshots. Repeat fresh observation to prove deduplication; stale fence and graph corruption leave prior state unchanged. Assert original receipt/period unchanged and operational audit/expiry UTC distinct from F.
- [ ] Add actual duplicate/concurrent confirms with onePOST, wrong key/token, UNKNOWN/lost-response/requires-action no-retry, stopped owner/session/policy/revision drift, default public/v1 denial and ordinary retry preservation. Finite hosted contention tests prove no nested-transaction wait or lock inversion against stop/FactService. All SQL/application runtime remains hosted-only.
- [ ] Add focused pure/SDK regressions only if new behavior warrants; audit exact allowlist and compile complete test sources. Existing browser v2 regression runs in full hosted CI; do not start local browser/stack.
- [ ] Update docs with proven prior04e036e/CI40940823768 and mark new component authored/compiled pending gate, nativeNOT_RUN/full5BNOT_ACCEPTED. Preserve historical failed runs. Save evidence/hash/diff/report, independent task review/root commit, final combined review, root push and exact-head full hostedCI. Fix failures with focused regression/review under unchanged restrictions. Record final SHA/run/counts in retained publication checkpoint; preserve worktree/evidence.
