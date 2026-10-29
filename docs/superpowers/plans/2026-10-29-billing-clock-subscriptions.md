# Owned initial Subscription implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development task by task with independent reviews. Execute continuously through commits, push and exact-head hosted CI under existing authorization.

**Goal:** Create and observe one owned-clock initial Subscription and project its verified OPEN state through existing application services.

**Architecture:** Immutable owned intent plus atomic original provider ACK/response receipt, a nonregistered explicit adapter and shared exact snapshot normalization. Hosted-only orchestration supplies exact-owner billing time while ordinary production paths remain fenced.

**Tech Stack:** Existing Java25, Spring/JDBC/PostgreSQL, stripe-java34.0.0, Maven/JUnit; no new dependencies.

**Spec:** [Owned TestClock initial Subscription](../specs/2026-10-29-billing-clock-subscriptions.md).

## Global Constraints

- No local Docker, DB/SQL execution, application/Spring context, Testcontainers, browser, Stripe/Sandbox/financial request, Python, installations or private env/key/archive/recovery reads. Only audited exact pure tests and cached offline compilation locally; hosted existing synthetic CI is authorized.
- Preserve ordinary fingerprints/keys/replay/UTC guards, existing migrations and INITIAL_NON_CLOCK. No confirmation, advance, trial, recovery, mutation or public activation. Native NOT_RUN/full5B NOT_ACCEPTED.
- One source author at a time, fresh independent review after each task, final whole-slice review; root Git only. Preserve worktree/evidence and original3untracked paths. No merge/force push/PR/deletion.

## Review Focus

1. A forged/foreign/stopped READY, Customer or local CREATE gains authority: reload all immutable relations; Task1 SQL corruption and Task2 no-dispatch cases.
2. ACK commits without original response receipt or an expired claim dispatches again: Task1 transactional rollback/late ACK/first-dispatch tests; Task2 uncertain response and duplicate claimant cases.
3. UTC and simulated time collapse or a stale READY authorizes later-period facts: Task2 exact clock preflight and opaque-created tests, Task3 distinct-time and changed-period refusal.
4. New owned mapper bypasses ordinary safeguards or leaks action/secret authority: Task2 shared validator tests and Task3 complete resource joins/ordinary and public-fence regressions.
5. Nested receipt transactions deadlock lifecycle sampling or fixture SQL bypasses application invariants: Task1 lock audit/races and Task3 actual proxied reservation/publication/fence/fact composition.

### Task 1: Immutable owned Subscription intent and atomic ACK receipt

**Files:** new provider application models `OwnedClockSubscription` and `OwnedClockSubscriptionReceipt`; new `StripeBillingClockSubscriptionStore`/`JdbcStripeBillingClockSubscriptionStore`; modify ProviderOperationStore/JdbcProviderOperationStore and StripeBillingClockIdentity as needed; additive `V31__stripe_billing_owned_subscriptions.sql`; exact CurrentSchemaVersions; pure identity and hosted persistence tests.

**Interfaces:** Introduce typed immutable intent with exact READY, Customer binding/ACK, Subscription/CREATE, original operation key, new fingerprint, selected terms/mappings and admission UTC. Receipt contains intent, original provider step, native reference/positive opaque created/item/initial-period, acknowledgment UTC. Suggested store entry `admitFresh(PaymentAccount, ClockOwner, UUID subscription, UUID operation, Instant)` plus exact find/requireCurrent/receipt methods. ProviderOperationStore gains explicit `reserveOwnedBillingSubscription` and atomic `acknowledgeOwnedBillingSubscription` methods taking typed intent/claim/returned receipt fields. Finalize concrete signatures/record fields in the report for Task2; do not expose generic SQL or capability controls.

- [ ] Write/run audited pure identity/policy RED for original owned identity and one-use rules; preserve ordinary fixed vectors.
- [ ] Implement persisted admission from exact existing READY/Customer and pristine original paid CREATE. One intent per clock, immutable complete relations, active authority, existing catalog mapping/terms and no ordinary fallback.
- [ ] Implement typed reservation/first-claim/current-owner/UNKNOWN/late-ACK guards. Original ACK plus receipt is atomic; generic acknowledge/recovery cannot manufacture owned response evidence. Preserve lock order; do not call another REQUIRES_NEW adapter from inside the transaction. Add finite stop reasons only for this scope.
- [ ] Author hosted constraint/race/rollback tests: foreign IDs/ACKs, one-clock-two-subscriptions, mutated intent/receipt, duplicate claim, expired claim, stop/late ACK, receipt failure rolls back ACK, known receipt local replay, ordinary reservation/manual recovery denied; no local execution.
- [ ] Run exact audited pure tests and offline test compilation, save full evidence/hash/diff/report, independent review, root commit.

### Task 2: Nonregistered owned create and exact initial graph adapter

**Files:** new `StripeBillingClockSubscriptionAdapter` and focused owned remote proof helper if needed; modify shared StripeBillingOwnership/StripeBillingSnapshotMapper and ordinary request assembly only to reuse common structural contracts; new exact pure SDK/mapper/fence tests using StripeBillingInertSdkFixture. Consume Task1's final typed APIs.

**Interfaces:** `createOwned(SubscriptionProviderModels.Command, ClockOwner)` returns existing Reply using one original owned intent and atomic receipt; `retrieveOwned(SubscriptionProviderModels.Lookup, ClockOwner)` returns existing Snapshot from the persisted receipt. No Spring component/production registration. Exact ownership/context constructor dependencies are explicit, never a supplied clock ID/time or broad bypass flag.

- [ ] RED: ordinary direct mapper must reject unexpected Customer/Subscription/Invoice clocks; owned valid initial response with created distinct from F and UTC must be accepted only with original lineage. Use real SDK inert HttpClient, no network server.
- [ ] Implement original-claim-only READY preflight→single POST→atomic ACK/receipt. Exact card/classic/default_incomplete/on_subscription/quantity1 params, pinned API/zero retries, original key, no anchor/backdate/trial/test_clock create param. Reuse shared request/shape helpers without weakening ordinary checks.
- [ ] Validate original positive opaque created, item and period containing F; exact native customer/clock/price and excluded shapes. Unknown/malformed/drift/timeout stops winning attempt; duplicate loser cannot stop winner. Known receipt returns locally without HTTP. Missing ACK/receipt does not authorize recovery.
- [ ] Add typed mapper context for exact Customer/Subscription/Invoice clocks and original created/item/initial period; preserve full cash/allocation checks, bounds and UTC observedAt. Initial-only noadvance adapter rejects later periods, trial/mutations and never issues client/confirmation authority. Revalidate current intent/receipt after reads.
- [ ] Cover separate missing/foreign clock cases, swapped item/created/price/period, F boundary, provider response malformed/live/deleted/unknown, late/duplicate calls, stopped/account drift, unsupported operations, exact request counts/order, and ordinary unchanged timestamp/fingerprint behavior. Record meaningful RED→GREEN, compile all tests, review then root commit.

### Task 3: Real hosted application composition and acceptance checkpoint

**Files:** test-source owned Subscription fixture and hosted integration tests; optional transaction-participating exact-owner read port/helper only if required by fixture without exposing production activation; docs/stage-5b-acceptance.md and relevant local-development section.

**Interfaces:** Compose existing SubscriptionCommandTransaction.reserveCreate/recordReply, SubscriptionObservationPort and ApplySubscriptionSnapshotUseCase/FactService with Task1 stores/Task2 adapter. @Primary billing-time sampling is test-only and joins current transaction without new locks/REQUIRES_NEW. Existing production ordinary time adapter remains unchanged.

- [ ] Positive hosted path must use real command reservation/authority/catalog, owned clock→Customer→Subscription SDK, original object binding, exact incomplete/open invoice graph and real single-use observation fence→FactService. Do not use inserted lifecycle rows to claim this path works.
- [ ] Assert OPEN/INCOMPLETE/no entitlement/cash/journal success, exact native lineage and idempotent repeated observation, rejected stale revision/fence, stopped owner and wrong graph. Distinct F/created/realUTC prove billing evaluation/audit/leases/security separation. Add noambient-transaction SDK assertions and sampler no-new-transaction proof. Preserve ordinary public/action/inbox refusals.
- [ ] Update docs with completed b7776a7/CI40916243540 (3718backend,87Cucumber/676steps,all3jobsPASS) and accurately label this new component as authored until its gate. Confirmation/advances/trial remain outstanding; no native-ready/completion claim.
- [ ] Run audited related pure regression and offline complete test compilation; save evidence/hash/diff/report; task review and root commit.
- [ ] Final combined independent review, root push and exact-head hosted CI. Repair observed failures with focused tests/review under unchanged local restrictions. Record final result/counts in retained publication checkpoint.
