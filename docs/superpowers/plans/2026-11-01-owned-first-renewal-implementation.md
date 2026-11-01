# Owned First Unchanged-Terms Renewal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This document only prepares future work; execution is not authorized by its creation.

**Goal:** Один owned first unchanged-terms renewal: genuine paid initial → два single-dispatch advance → typed verified CASH → атомарный FactService/payment/events/outbox/access.

**Architecture:** Новый internal owned-only consumer переиспользует ProviderOperation claim/ACK/UNKNOWN, добавляет paid admission и immutable step/ACK/later-READY/boundary/publication evidence. Existing original guards и ordinary registry сохраняются; later-time sampler доступен только hosted composition.

**Tech Stack:** Java25/Spring, JDBC/PostgreSQL additive V33, stripe-java34.0.0, JUnit/AssertJ и существующий inert official SDK HTTP transport.

**Spec:** [Owned first renewal design](../specs/2026-11-01-owned-first-renewal-design.md). Прочитать целиком; исходная точка `3b9114c9599a59efe0e5290d2992c5f57b8c564c`.

## Global Constraints

- Сохранить `stripe-java 34.0.0` и API `2026-09-30.endive`. Default Fake и отключённый Stripe не требуют ключей.
- Только additive migration V33. V30–V32, исходный F/`ReadyClock`, receipts, fingerprints, original initial-only guards и старые consumed/UNKNOWN/DISPATCHING неизменны.
- Нет public/scheduled advance, обычной Spring-регистрации, нового launcher/framework, ordinary fallback, автоматического confirm/pay/finalize, третьего advance или принятия старых fixtures.
- Operational UTC для JWT/OTP/signature/leases/retry/callback delivery не сдвигается. Документ не даёт runtime/финансового бюджета.
- Текущая работа — только документация. Все checkbox/команды ниже относятся к **будущей реализации в отдельно допустимом hosted окружении**, не к запуску сейчас; local Docker/application/SQL/Testcontainers/Stripe/tests/install запрещены.
- Новые файлы ниже — proposed paths, не существующие ссылки. Source-linked существующие seams перечислены в spec §2. Любое изменение исходного HEAD требует rebase этой file map и свободного additive version.
- Во всех tasks минимальные compile-safe interfaces/schema/context setup допустимы до behavioral RED, без реализации admission/dispatch/acceptance. RED — assertion-level failure на требуемом поведении реального consumer; compilation, missing migration/schema или fixture/context/environment failure не принимаются. Затем минимально реализовать поведение и подтвердить GREEN теми же assertions.

- Уточнение исполнения: [spec §8](../specs/2026-11-01-owned-first-renewal-design.md#8-уточнение-исполнения-одноразовый-запуск-graph-read) добавляет immutable read-start claim per READY. Task2 готовит schema/model до V33 freeze, Task3 владеет INSERT-winner/I/O consumer, Task4 проверяет claim/fence и локальный receipt replay; никаких новых provider budget/retry.

## Review Focus

1. Preconfirmation hash/OBSERVE/active clock без настоящего paid INITIAL не допускает advance (Task1).
2. Crash/ACK-without-READY/expired lease и concurrent claimant никогда не получают второй POST или takeover (Task2).
3. Jan31→Feb28 anchor, draft без PI, extra invoice и optional pinned fields не превращаются в paid proof (Task3).
4. Step2 без READY, сломанный predecessor и owner revoke не откатывают время к F и не дают access (Task4).
5. Boundary/Pending/stale Paid не создают debt/grace/cash/callback, exact fresh replay не дублирует деньги (Task5).

---

## File map и общие типы

Использовать package roots: `P=backend/src/main/java/dev/genericpayments/platform/provider`, `S=backend/src/main/java/dev/genericpayments/platform/subscriptions`, `T=backend/src/test/java/dev/genericpayments/platform` (ниже это сокращения **точных префиксов**, не имена каталогов).

В новом `P/application/model/OwnedFirstRenewal.java` разместить связанные typed evidence records, не дублировать ProviderOperation.Status/claim machine:

- `Admission`: id, exact original `OwnedClockSubscriptionReceipt`, sourceSha, original root/bill/payment IDs+versions, invoice/allocation/PI, initial period/terms, subscriptionRevision/policyVersion, admittedAt; всё reloadable из spec §3.
- `Step {BOUNDARY, FINALIZATION}`; `Advance`: id (provider resourceId), admissionId, step/ordinal, target, exact predecessor (original READY для1/later READY+boundary для2), key/fingerprint.
- `Ack`: Advance, exact ACKed ProviderOperation, original live claimant/deadline и typed POST fields. `LaterReady`: Ack, exact GET clock fields/status/target/observedAt. Не использовать original ReadyClock для later time.
- `Boundary`: admissionId, READY1, item, renewalInvoice, provider-derived `Subscription.Period`; immutable captured boundary identity.
- sealed `Observation`: `Pending(Admission,LaterReady,Boundary)` или `Paid(Admission,LaterReady,Boundary,ApplySubscriptionSnapshotUseCase.Fence,SubscriptionProviderModels.Snapshot)`; fence в Paid обязателен. Внутренний mapper возвращает sealed `MappedObservation`: `MappedPending(Boundary)` или `MappedPaid(Boundary,Snapshot)`; adapter добавляет captured fence, не использует null-placeholder.
- `Publication`: admissionId, READY2, boundary, renewal bill/payment IDs и projected subscription revision. SHA/fingerprint — versioned explicit-field canonical encoding, не record.toString.

## Task 1: Genuine paid admission и owned evidence persistence

**Files:** Create `P/application/model/OwnedFirstRenewal.java`, `P/application/port/out/OwnedFirstRenewalStore.java`, `P/adapter/out/persistence/JdbcOwnedFirstRenewalStore.java`, `P/application/service/OwnedFirstRenewalAdmissionService.java`, `backend/src/main/resources/db/migration/V33__stripe_billing_owned_first_renewal.sql`. Modify `T/support/CurrentSchemaVersions.java`: добавить33 в `EXPECTED` list, сейчас заканчивающийся32; сохранить точные migration assertions и все прежние версии.
**Tests:** Create `T/provider/adapter/out/persistence/OwnedFirstRenewalAdmissionIntegrationTest.java`; reuse genuine `T/support/StripeBillingClockConfirmationCompositionFixture.java` без вставки successful rows.

**Interfaces:**
- `OwnedFirstRenewalAdmissionService.admit(OwnedClockSubscriptionReceipt receipt,String sourceSha): Admission` берёт `SubscriptionTransactionPort`, clock UTC и существующие lock ports.
- Store `admitPaid(receipt,sourceSha,Instant utcAt): Admission` и `requirePaidInTransaction(Admission): Admission` — MANDATORY, только чтение/insert под caller lifecycle locks.
- Store `prepare(Admission,Step,Boundary nullable,Instant utcAt): Advance` — короткая отдельная transaction; Step1 требует null boundary, Step2 exact persisted boundary. `stop(Admission,String reason,Instant utcAt): void` — monotonic terminal marker. Разрешённые reason: AUTHORITY_DRIFT, ADVANCE_UNKNOWN, READY_UNAVAILABLE, GRAPH_UNSUPPORTED, PAYMENT_NOT_OBSERVED.

- [ ] **RED:** `rejectsAssociationWithoutPaidInitial` проходит create/action/select/confirm, но до fresh CASH/apply admission fails и нет advance intent/provider POST; OBSERVE и REQUIRES_ACTION не дают shortcut.
- [ ] **RED:** `admitsOnlyExactPaidPredecessor` сначала выполняет real confirmation→paidObservation→apply→assertPaid; admission succeeds один раз; rerun возвращает ту же запись. Parameterized drift: CREATE non-SUCCEEDED, wrong context bill/invoice/PI/period/amount/account/customer, refunded Payment, trial/debt/pending/cancel, price/policy/revision/credentials mismatch → refusal без dispatch.
- [ ] После compile-safe setup запустить owning hosted test: `cd backend && ./mvnw -B -ntp -Dtest=OwnedFirstRenewalAdmissionIntegrationTest test`; требовать assertion-level RED, показывающий, что реальный consumer ещё не допускает точный paid predecessor либо ошибочно допускает неподходящий. Отсутствующий interface/schema, compilation или fixture/context failure — setup defect, не behavioral RED.
- [ ] Реализовать §3 spec: service lock order sorted operation(s), включая original CREATE →Site→Customer→Account→Subscription; store не вызывает independent clock/provider transactions. V33 создаёт шесть evidence tables из §4 с FK lineage, unique subscription/ordinal и immutable triggers; admission identity immutable, только stop null→reason допускается. Owning migration test требует точного schema `EXPECTED` до33 включительно; не ослаблять migration assertions. Boundary/publication writers появятся в owning tasks.
- [ ] `rejectsEvidenceTamper` проверяет SQL запреты original lineage/target/fingerprint/ordinal/receipt UPDATE/DELETE и duplicate admission; stop невозможно очистить. Hosted owning test PASS; task commit только если разрешён будущим исполнением.

## Task 2: Два advance с существующим ProviderOperation и bounded later READY

**Files:** Create `P/adapter/out/stripe/billing/StripeBillingFirstRenewalAdvanceAdapter.java`.
Modify `P/application/model/ProviderOperation.java`, `P/application/port/out/ProviderOperationStore.java`, `P/adapter/out/persistence/JdbcProviderOperationStore.java`, Task1 store/V33.
Task1–4 последовательно дополняют только ещё не выпущенную V33 в disposable hosted fixtures: Task2 завершает dispatch/read-claim schema, Task3 — INSERT guards чтения/boundary, Task4 — INSERT proof публикации. Task5 замораживает полную V33 до любого retained/shared DB/native применения или выпуска; после любого такого применения/выпуска дальнейшие изменения идут следующей additive migration с обновлённой file map, не переписыванием V33.
Inspect/update explicit refusal only: `P/adapter/out/stripe/StripeRecoveryProofAdapter.java`, `P/adapter/out/stripe/StripeInboxResolutionAdapter.java` (если default уже закрыт, сохранить и доказать тестом).
**Tests:** Create `T/provider/adapter/out/stripe/billing/StripeBillingFirstRenewalAdvanceIntegrationTest.java`; extend `T/provider/adapter/out/persistence/StripeBillingClockStorePolicyTest.java` where generic store policies live.

**Interfaces:**
- Adapter `advance(Admission admission,Step step): LaterReady`, outside transaction; consumes Task1 prepare/store and real ProviderOperationStore.
- New owned port methods `reserveOwnedFirstRenewalAdvance(PaymentAccount,Advance): ProviderOperation`, `acknowledgeOwnedFirstRenewalAdvance(Advance,ProviderOperation claim,TestClockAck observation,Instant utcAt): Ack`; TestClockAck nested immutable DTO in OwnedFirstRenewal, exact reference/object/test mode/name/frozen/status/created/deletes_after/optional advancing target.
- Store `recordReady(Ack,TestClockReady observation,Instant utcAt): LaterReady`, `requireCurrent(Admission): Admission`; TestClockReady same fields but mandatory target-ready. No SDK model crosses port. `recordReady` short independent tx atomically inserts immutable READY; special ACK copies original claim/deadline atomically with provider ACK.

- [ ] **RED:** `postsOncePerStepAndRequiresGetReady` asserts persisted DISPATCHING before POST, exact target B then T/key/API/retries0; ACK old frozen+absent status_details allowed, optional contradictory target refused. No READY from POST. GET advancing→ready yields exact LaterReady; original F/ReadyClock unchanged.
- [ ] **RED:** `twoAdvancesShareClockButNotDispatchIdentity` ACKs both operations with same clock ref; duplicate ordinal/key/resource or forged generic reservation refuses. `neverRecoversAdvance` tests lockRecovery/bindRecovered and both resolution adapters refuse before provider IO.
- [ ] Hosted test command: `cd backend && ./mvnw -B -ntp -Dtest=StripeBillingFirstRenewalAdvanceIntegrationTest,StripeBillingClockStorePolicyTest test`; require meaningful RED, then implement minimal consumer and guards.
- [ ] Add TEST_CLOCK_ADVANCE to Kind and V33 CHECK preserving all existing members. `resourceId=Advance.id`; exact ownership/target/key/fingerprint required in requireResource, supportedResource/reference validation and firstDispatchOnly. Generic reserve refuses this kind before Account lock; special reservation validates persisted exact Advance. Generic ACK refuses owned advance; only special ACK can write ACK receipt with original live lease. Add explicit recovery/bind refusal even after lease expiry.
- [ ] Before reserve/claim and in persisted prepare/revalidation/SQL guard, enforce strictly-forward conservative MONTH56×86400s / YEAR730×86400s from exact predecessor F/B. Test over-limit MONTH/YEAR with full otherwise-valid inert responses; this may refuse the provider-valid outer56–62d/730–731d window, never derives N or adds GETs. Hosted crash cases retain unstopped DISPATCHING before/after POST and bare ACK; expiry cases wait for the actual original60s UTC deadline without changing consumed history or DB clock.
- [ ] V33 recreate reference index with `provider_reference is not null and kind not in ('SUBSCRIPTION_UPDATE','TEST_CLOCK_ADVANCE')`; all other kinds retain uniqueness. New scoped FK/unique step proof protects same clock's two advances. Guard OLD identity, single DISPATCHING from pristine RESERVED, one ACK under live claim, no reset/deletion; no generic recovery.
- [ ] Dispatch phases use provider-step→Site→Customer→Account then nonlocking lifecycle reads; add owned claim hook **before** generic requirePinnedAccount. Never call legacy Account-first ClockStore helper, lock Subscription/CREATE underneath, nor call REQUIRES_NEW from guarded callers. Stop locks Account only. Add lock-order race with FactService holding Site then requesting Account; no deadlock. Revalidate paid proof+revision before/after IO and original owner stop; finalization requires persisted READY1+boundary, not old-period containment.
- [ ] Use one exact predecessor GET, one POST per stage, ≤12 status GETs/250ms spacing/30s monotonic/2s request/60s UTC claim; bound by remaining lease too. No provider retry; only original live invocation continues after ACK. Inert transport tests actual pinned getters/request params; no separate SDK-only suite.
- [ ] **GREEN/races:** `stopsWithoutReplay` parameters crash before/after POST, HTTP disconnect, lost ACK/READY write, lease expiration, internal_failure, wrong clock/target/name/created, stop during GET, 12 advancing responses; rerun issues zero POST/GET unless exact completed READY returned locally. Concurrent claimant produces one POST; hosted tests PASS.

## Task 3: Typed boundary/Pending/Paid renewal mapper

**Files:** Modify `P/adapter/out/stripe/billing/StripeBillingSnapshotMapper.java`; create `P/adapter/out/stripe/billing/StripeBillingFirstRenewalAdapter.java`; extend Task1 store boundary reader/writer.
**Tests:** Create `T/provider/adapter/out/stripe/billing/StripeBillingFirstRenewalMappingTest.java`; existing `T/provider/adapter/out/stripe/StripeBillingSnapshotMapperTest.java` retained as original regression.

**Interfaces:**
- `StripeBillingFirstRenewalAdapter.observe(Admission,LaterReady): Observation` captures existing revision/financial selection/observation fence before provider IO; revalidates exact current clock/admission afterward. At READY1 always Pending, even early paid.
- `StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(StripeClient,Ownership,Admission,LaterReady,Instant observedAt): MappedObservation` builds private typed graph result; adapter consumes MappedPaid and constructs Paid with nonnull captured fence. MappedPending becomes Pending; neither mapper result can call publication directly.
- Store `recordBoundary(Admission,LaterReady,Boundary): Boundary` requires READY1 and validated shape; exact repeated boundary returns original. `boundary(Admission): Boundary` fails closed when absent.

- [ ] **RED:** `boundaryDraftWithoutPaymentIsPrivatePending` verifies one original INITIAL CASH plus one subscription_cycle draft with no allocations/PI → Pending; financial state unchanged. `finalReadyIsNotPaid` leaves draft/open after READY2 and never yields CASH by time/status alone.
- [ ] **RED:** `firstRenewalUsesProviderPeriod` covers original Jan31→Feb28 and provider next end Mar31, with T inside `[Feb28,Mar31)`; rejects gap/overlap/original plusMonths assumption, T>=newEnd, third invoice, duplicate INITIAL/renewal, changed item/created/terms/clock, VOID/uncollectible/actions/zero/credits/split/reverse allocation mismatch.
- [ ] Hosted command `cd backend && ./mvnw -B -ntp -Dtest=StripeBillingFirstRenewalMappingTest,StripeBillingSnapshotMapperTest test`; require meaningful RED, then add typed branch preserving every ordinary/OwnedInitial entrypoint guard.
- [ ] Implement spec §5: original INITIAL exact persisted invoice/PI/CASH/period; new renewal exact nested+flat item/subscription ownership and provider-derived period, all full cash graph checks; old null allowances stay confined to original INITIAL. No broad owned boolean weakens renewal evidence.
- [ ] Adapter: boundary one graph attempt; paid ≤3 complete attempts; ≤100 requests including clock brackets per attempt, page10/max10, 30s monotonic/2s request/250ms spacing. Pending after budget → terminal PAYMENT_NOT_OBSERVED; unsupported → GRAPH_UNSUPPORTED; no FactService or invoice.pay/finalize.
- [ ] **GREEN:** `cashRequiresFullJoinedGraph` yields ACTIVE snapshot with exactly INITIAL CASH + RENEWAL CASH, providerRevision0, null operation markers, original period historical and renewal current; hosted owning/regression tests PASS. Provider-created/event times cannot substitute occurrence/ownership proof.

## Task 4: Monotonic scoped time и atomic first-renewal publication

**Files:** Create `T/support/OwnedFirstRenewalBillingTime.java`, `P/application/service/OwnedFirstRenewalPublicationService.java`; extend Task1 store publication writer/reader. Existing `S/application/service/SubscriptionFactService.java` and original time sampler remain unchanged unless a focused failing consumer test identifies a necessary seam; widening their old guards is excluded.
**Tests:** Create `T/subscriptions/application/service/OwnedFirstRenewalPublicationIntegrationTest.java` with real FactService/query/cash/outbox ports.

**Interfaces:**
- `OwnedFirstRenewalBillingTime implements SubscriptionBillingTimePort`: constructor `(JdbcTemplate jdbc,OwnedSubscriptionBillingTime original)`; same `sample(account,site,customer,utcAt): Sample` and `requireUnscopedAllowed(site,customer): void`.
- `OwnedFirstRenewalPublicationService.publish(Admission,LaterReady,Paid): Publication`; uses SubscriptionTransactionPort + existing lock/fence ports, invokes FactService in **same** transaction. Null/mismatched fence refuses.
- Store `recordPublicationInTransaction(Admission,LaterReady,Paid): Publication`, `publicationInTransaction(Admission): Optional<Publication>`; derives exact bill/payment/revision from DB after FactService, never from caller IDs. `requirePaidInTransaction` supports original state or exact committed publication state only.

- [ ] **RED:** `neverFallsBackAfterRenewalAdmission`: no admission delegates original F; admitted but missing READY refuses; READY1 samples B; prepared/DISPATCHING2 blocks READY1/F; READY2 samples T; broken predecessor/revoke/stop/foreign Account/policy drift refuses; unscoped lists/offers remain excluded. Assert caller connection/xact same, no new locks/store transactions.
- [ ] **RED:** `laterTimeDoesNotPay`: at B and T with original paidThrough E, query access denied; Pending creates no BillingRecord/debt/grace/events/outbox. UTC lease/signature/createdAt/receivedAt unchanged despite later billing time.
- [ ] Hosted command `cd backend && ./mvnw -B -ntp -Dtest=OwnedFirstRenewalPublicationIntegrationTest test`; meaningful RED then implement nonlocking selector starting from admitted owner/highest prepared ordinal (§6), never inner-join disappearance→ordinary fallback.
- [ ] Publish validates fresh existing fence/observation sequence, exact READY2/boundary/current revision/paid predecessor and Paid CASH shape under lifecycle locks; FactService then publication receipt insert commit atomically. No REQUIRES_NEW under those locks; selector serializes with revoke through existing Account lock.
- [ ] **GREEN:** `publishesOnlyFreshPaidOnce` produces second CONFIRMED_CASH bill and SUCCEEDED SUBSCRIPTION Payment with RENEWAL context, paidThrough provider N, currentPeriod `[E,N)`, one additional PERIOD_PAID and payment/subscription outbox pair. Existing original rows/CREATE SUCCEEDED unchanged; fresh exact replay idempotent, stale replay or mid-read drift rolls back all changes; hosted tests PASS.

## Task 5: Complete composition и exact-source hosted gate

**Files:** Create `T/support/StripeBillingFirstRenewalCompositionFixture.java`, `T/provider/adapter/out/stripe/billing/StripeBillingFirstRenewalCompositionIntegrationTest.java`. Modify `docs/stage-5b-acceptance.md` and `docs/local-development.md` only after actual implementation evidence exists; preserve historical native failure.
**Interfaces:** Fixture extends existing genuine confirmation fixture; explicitly constructs admission/advance/mapping/publication consumers and new sampler, no production bean/registry/launcher. Root process controls future Git/publication; no push implied by this plan.

- [ ] **RED:** `genuineInitialThroughTwoAdvancesToRenewalCash` invokes real create/action/select/durable confirm/fresh paid graph/apply/assertPaid before admission; then READY1 draft boundary, READY2 fresh paid graph, publication, authenticated query and outbox payload checks. Expected financial totals: 2 bills/2 payments/2 PERIOD_PAID/4 outbox entries, no synthetic successful seed. Hosted inherited fixture currently uses 1000 EUR minor units (EUR10) per invoice; do not mistake that inert fixture amount for native EUR20 budget.
- [ ] **RED:** `pendingAndDriftCannotPublish` and `concurrentPublicationIsOnce` cover Pending after second READY, source/owner/policy/revision/fence race and duplicate request. Every intermediate/failure preserves captured financial state; at most two advance POSTs, no pay/finalize/second confirm. Confirm original READY/fingerprints/receipts byte-for-byte unchanged.
- [ ] Future hosted targeted command `cd backend && ./mvnw -B -ntp -Dtest=StripeBillingFirstRenewalCompositionIntegrationTest,StripeBillingClockConfirmationCompositionIntegrationTest test`; fail meaningfully before wiring; wire only existing seams/new consumers; require PASS.
- [ ] Review all consumer tests, pin/API request headers/no retries, migration upgrade/tamper/races and packaging isolation. Run full existing hosted `.github/workflows/ci.yml` on exact committed SHA: backend `./mvnw -B -ntp verify`, frontend/default Fake/privacy, infrastructure; all three jobs success. Record exact source/run/job URLs/outcomes, never infer counts. Source change requires fresh gate; local compile alone is not acceptance.
- [ ] Record offline-only outcome and remaining native gate honestly. No native execution here: later explicit fresh scope must authorize initial EUR20 + **one EUR20 renewal**, both B/T advances and resources/reads; initial paid acceptance still outstanding. No previous native run/old EUR13/DISPATCHING fixture adopted, no capability switch/launcher added. Stage5B remains NOT_ACCEPTED.

## Self-review и handoff

Coverage: Task1→spec §3; Task2→§4; Task3→§5; Task4→§6; Task5→§7. Type/step IDs/targets/limits agree; no independent SDK mechanics task. Six evidence tables carry provenance, while one existing provider dispatch machine owns mutation. New wire shape/timing/method-persistence failures stop at the owning consumer gate and require a scoped design correction; never silently upgrade pin, add retries or broaden budget.

Подготовка этого плана не исполняет checkbox, не проводит тестов и не заявляет acceptance. Future implementation method/authority определяется отдельно; текущий документ можно review/publish в docs-only scope без новой runtime-команды.

## Execution addendum — read budget ownership

Шесть исходных tables не резервируют право внешнего graph read. Task2 добавляет append-only `stripe_billing_first_renewal_read_claims`, typed ReadClaim и MANDATORY beginReadInTransaction(admission,ready,fence,utcAt) surface до финальной V33 freeze. Task3 реализует lifecycle capture + успешный INSERT как единственный live continuation, не выдаёт существующий claim на conflict и не останавливает победителя; lost response/crash не разрешает новый бюджет. Boundary/Paid привязаны к exact claim и принятому в исходном30s бюджете результату. Исходный observation sequence не consume до FactService.

Task3 tests: repeated call/crash/conflict не добавляют GET, concurrent loser не revoke winner, missing/broken claim не даёт old-F fallback или publication. Task4 tests: otherwise-valid Paid с null/foreign/stale claim/fence отвергается; successful receipt replay получает fresh **local** fence/DB validation без graph/read claim/reapply/денег/events/outbox. Scope и цена уточнения зафиксированы в spec §8; прежние initial guards и native ограничения сохраняются.
