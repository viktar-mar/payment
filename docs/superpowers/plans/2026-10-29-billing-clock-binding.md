# Owned clock and Customer binding implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Execute through independent reviews, commits, push and exact-head hosted CI without intermediate user approvals.

**Goal:** Prepare a durable, one-use clock creation and fresh Customer binding component, leaving native clock Subscriptions disabled.

**Architecture:** Original provider-operation receipts plus immutable owner intent/READY evidence; a narrow owned-Customer reservation; private test-only orchestration and explicit downstream fences.

**Tech Stack:** Existing Java25, Spring, JDBC/PostgreSQL, stripe-java34.0.0, JUnit and Maven. No new dependencies.

**Spec:** [Owned Billing clock binding](../specs/2026-10-29-billing-clock-binding.md).

## Global Constraints

- No local Docker, SQL/DB, Testcontainers, Spring/full application, browser, provider/Sandbox, financial operations, Python, installations or private key/archive/recovery reads.
- Pure exact-class JUnit only after auditing class/parents/static setup; cached offline Maven may compile all tests but must never discover broad suites. Hosted CI performs application/SQL/Fake/browser verification.
- Preserve ordinary keys/fingerprints/replay, migration history, original immutable bindings and historical records. No public clock activation or native runner changes; INITIAL_NON_CLOCK stays unchanged; native NOT_RUN/full5B NOT_ACCEPTED.
- One source author at a time; independent review per task plus final broad review. Root handles Git. Preserve original three untracked paths and all evidence. Ordinary commits/push/hosted CI authorized; no merge or force push.

## Review Focus

1. Admission races ordinary Customer reservation or deadlocks through nested REQUIRES_NEW; exact owner lock order, real hosted two-connection races and proxied transaction proof.
2. Lost ACK or expired lease authorizes a second POST; first-dispatch fence in locked store, persisted stopped state and exact original-operation tests.
3. Caller clock ID or foreign ACK becomes authority; composite owner/ACK constraints, immutable intent/READY and typed internal methods.
4. Clock Customer leaks into ordinary Subscription or old timestamp proof; explicit common ownership/store fences, no native clock activation and unchanged UTC guards.
5. Offline evidence overstates runtime/acceptance or tests start forbidden local services; exact pure-class audit, inert SDK transport and hosted integration receipts.

### Task 1: Durable clock ownership and one-use operation contracts

Read the spec and `.superpowers/recon/2026-10-29-billing-clock-binding/backend.md`. Implement ProviderOperation.Kind TEST_CLOCK_CREATE, typed clock owner/READY models and StripeBillingClockStore with JdbcStripeBillingClockStore; additive V30__stripe_billing_owned_test_clocks.sql and exact CurrentSchemaVersions update. A narrowly one-way stopped state is allowed; identity and READY evidence remain immutable. Use finite safe reason enum, no raw provider messages. Finalize concrete typed interfaces and record them in the report for Task 2.

Extend ProviderOperationStore with reserveOwnedBillingCustomer(Account, ReadyClock), default fail-closed only where necessary for existing pure doubles. JdbcProviderOperationStore implements atomic admission/reservation provenance contracts and first-dispatch-only claim for TEST_CLOCK_CREATE/owned CUSTOMER_CREATE; preserve ordinary behavior. Add explicit owned recovery rejection, exact ACK/reference checks, duplicate-reference index membership and downstream Subscription/Setup resource fence. Update StripeBillingBindingAdapter if required to validate owned provenance without changing its original immutable relation. No clock SDK dispatch or production activation in this task.

Author meaningful RED→GREEN pure policy/store-double tests where they can verify the behavior without services; record exact audited class command and outcomes. Author/compile hosted StripeBillingClockStoreIntegrationTest proving constraints, immutability, original-owner checks, ordinary admission races in both directions, one-use claims, known-ACK binding recovery, UNKNOWN/stopped refusal and Spring transaction boundaries. Do not execute integration locally. Inspect every literal migration inventory. Save report/tests/hash/diff; independent task review then root commit.

### Task 2: Official SDK clock and owned-Customer flow with explicit fences

Read the spec, Task 1 interface report and `.superpowers/recon/2026-10-29-billing-clock-binding/runtime.md` (its ephemeral-only alternative is superseded by the durable spec). Implement non-auto-registered StripeBillingTestClockAdapter and explicit owned-Customer method on StripeBillingCustomerAdapter using Task 1 persisted provenance and typed reservation. Ordinary method and event recovery refuse clock owners. Preserve original ordinary fingerprints; add fixed fingerprint vectors for new tags, exact test_clock response proof and one original-key POST. One exact READY read, no polling/advance/delete/list. Persist terminal stop on failure; successful ACK replay may repair local binding only. Inspect shared ownership/inbox paths and add the missing downstream persisted-owner fence before any native Subscription/Setup action.

Add inert official SDK HttpClient fixture tests proving real serialization/parser behavior, pinned API, original keys, zero retries, exact URLs/params and malformed/live/deleted/wrong-clock/advancing/unknown/error outcomes. No sockets or local server. Audit each pure class before running exact cached offline Maven test selectors. Establish meaningful regression RED then GREEN, including ordinary response now rejecting unexpected clock and owned unknown never redispatching. Keep production constructor wiring correct and existing direct test construction compatible without weakening production fences. Compile complete test sources. Save author report/tests/hash/diff; independent task review then root commit.

### Task 3: Hosted composed proof and truthful acceptance checkpoint

Add a dedicated test-source-only orchestration fixture (no application main or exposed control channel) and hosted StripeBillingClockCustomerBindingIntegrationTest using actual proxied stores/adapters, exact owner fixtures and inert official SDK transport. Prove clock→READY→Customer→binding, no ordinary fallback during intent/READY gaps, exact same-run ACK replay without POST, malformed/unknown terminal failure and downstream Subscription/Setup no-dispatch. Assertions must cover actual SQL lineage, UTC audit/lease versus distinct provider frozen/created fields and preserved ordinary behavior. No changes to INITIAL_NON_CLOCK or Fake launcher/packaging.

Update stage-5b-acceptance.md and relevant local-development text: record completed 287bcb7 / CI40897288993 (3587 backend tests, 87 Cucumber scenarios, all jobs green); distinguish this new authored/prepared component from native NOT_RUN. Native timestamp contracts, Subscription/trial/renewal/advance and future private launcher remain outstanding. Run audited focused pure regressions and offline full test compilation; hosted proof remains explicitly authored until CI. Save report/tests/hash/diff; independent task review, root commit, final broad review, then authorized push and exact-head hosted CI. Repair observed CI failures without changing local runtime restrictions. Record final exact-head outcome and counts in the preserved publication checkpoint.
