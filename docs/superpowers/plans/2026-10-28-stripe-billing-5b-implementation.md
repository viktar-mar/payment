# Stripe Billing 5B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подключить существующие Site-owned подписки к Stripe Billing/Elements/Portal в Sandbox без второго billing engine и без регрессии 5A/Fake.

**Architecture:** Реализовать существующий SubscriptionProviderPort через Stripe, сохранив application/domain и отдельные adapter/persistence границы. HTTP к провайдеру выполняется вне транзакций; исходная durable операция, account binding, immutable invoice/cash joins и локальные fences предшествуют изменению доступа. SDK получает только memory-only client context; Portal является ограниченным fallback.

**Tech Stack:** Java25, Spring Boot, Maven Wrapper, PostgreSQL/Flyway, stripe-java34.0.0/API2026-09-30.endive; существующие TypeScript SDK/Web Components, Next.js Admin, Cucumber/Node/Playwright.

**Spec:** [Проект 5B](../specs/2026-10-28-stripe-billing-5b-design.md), [предварительный аудит](../../stripe-5b-audit.md), ранее согласованный Stage5/Stage4. Статус: план подготовлен; ни одна задача ниже ещё не реализована.

## Global Constraints

- Default Fake и отключённый Stripe не требуют ключей. Не менять SDK/API pin, не включать preview, live, Connect, Managed Payments или новые методы оплаты.
- Сохранить уникальность non-terminal Site/Customer/Product и исходный Account. Не объединять Customers по email. Один item/quantity1, MONTH/YEAR, точная published Price/version.
- Browser/ACK/return не являются settlement. ZERO не создаёт положительный Payment. UNKNOWN не разрешает новую финансовую попытку или новый ключ.
- 23-часовая граница guarded replay сохраняется. Любой внешний write имеет сохранённые stable key/fingerprint и fenced ownership; HTTP вне DB transaction, никаких скрытых SDK retries.
- Client secret, action grant, Portal URL — memory-only; no-store, без raw payload logs/storage/events/trace/video/screenshots. Публичные ошибки — фиксированные безопасные коды.
- Только additive миграции после V25; не менять исторические миграции и отчёты 5A. Старые EUR13/DISPATCHING, их архивы, данные и ключи не использовать.
- Подготовка и offline implementation не разрешают Docker/Sandbox. Тесты, требующие PostgreSQL/Docker, перечисляются как отложенные до отдельного runtime-разрешения либо выполняются в согласованном hosted CI; не считать NOT_RUN прошедшим тестом.
- Каждая задача: RED → GREEN по указанному поведению, целевые и связанные regression-тесты, независимое review; полный Maven verify на backend checkpoint и финале при разрешённом окружении. Нет промежуточных запросов «продолжать?», нет расширения диагностической платформы.

## Review Focus

1. Два Customer одного email и два Site/Account: никакого чужого Customer/PaymentMethod/Portal; тесты Task2/10.
2. Webhook опередил mapping, lost ACK или истекло replay-окно: не создавать вторую Subscription/Invoice/charge; Task2/4/9.
3. Invoice paid через credits/out-of-band либо частичный allocation: не выдавать CASH и новый paid-through; Task3/6.
4. Ответы retrieve пришли в обратном порядке при одинаковом event timestamp: не откатывать оплату, отмену, тариф и grace; Task3/6.
5. Logout/expiry/return/reload в процессе 3DS/setup: ни повторного confirm, ни секрета в sinks, ни stale widget ownership; Task5/11.

## File structure and stable interfaces

Ниже `B` означает `backend/src/main/java/dev/genericpayments/platform/`, `T` — `backend/src/test/java/dev/genericpayments/platform/`, `R` — `backend/src/main/resources/db/migration/`, `S` — `frontend/sdk/src/`. Это однозначные префиксы путей, не новые каталоги.

Новые Stripe implementation units находятся в `B/provider/adapter/out/stripe/billing/`: `StripeSubscriptionProviderAdapter`, `StripeBillingCustomerAdapter`, `StripeBillingSnapshotMapper`, `StripeBillingActionAdapter`, `StripeBillingMutationAdapter`, `StripeBillingPortalAdapter`. Внешний SDK и его DTO не выходят из adapter слоя. JDBC bindings — отдельный `B/provider/adapter/out/persistence/StripeBillingBindingAdapter` через provider-neutral ports в `B/provider/application/port/out/`.

`SubscriptionProviderPort` и `SubscriptionProviderModels` остаются контрактом CREATE/SETUP/retrieve/preview/change/cancel/recover/resume/portal. Не копировать эти модели в новый параллельный модуль. Extension client-session описан в Task5. Для Stripe `providerRevision=0` — unversioned sentinel с локальными fences; Fake sequence неизменен.

### Task 1: Pinned Billing contract fixtures

**Files:** Create `T/provider/adapter/out/stripe/StripeBillingSdkContractTest.java`, `T/provider/adapter/out/stripe/StripeBillingHttpFixture.java`; использовать существующие `StripeClientFactory`/`StripeSdkHttpClient` и dependency34.0.0 из `backend/pom.xml` без изменений версии.

**Produces:** воспроизводимый HTTP fixture для customers/subscriptions/invoices/invoice-payments/setup/schedules/Portal на loopback, с контролируемыми ответами и подсчётом requests; не готовый mocked SubscriptionProviderPort.

- [ ] RED: `pinsVersionAndDisablesRetries`, `decodesInvoicePaymentAllocation`, `decodesPaymentAndSetupActions`, `retainsSubscriptionItemPeriods`, `supportsClassicTrialAndPendingUpdateParameters`. Проверить header2026-09-30.endive, один HTTP dispatch при transport error, реальные generated SDK params, обязательные null/unknown shapes.
- [ ] Run `./mvnw -Dtest=StripeBillingSdkContractTest test` в `backend`; сначала зафиксировать точную причину RED, затем сделать минимальные fixture/decoder additions и получить GREEN.
- [ ] Зафиксировать в тестах выбранный `billing_mode=classic`, `default_incomplete`, card-only, `on_subscription`, legacy subscription-wide trial_end и pending-update/schedule поля. При несовместимости pinned SDK/API сначала точно исправить проект решения; не обновлять pin и не удалять сценарий.
- [ ] Review, затем commit `test: pin Stripe Billing SDK contracts`.

### Task 2: Durable Customer/resource bindings and step ownership

**Files:** Create `B/provider/application/port/out/StripeBillingBindingPort.java`, `B/provider/adapter/out/persistence/StripeBillingBindingAdapter.java`, `B/provider/adapter/out/stripe/billing/StripeBillingCustomerAdapter.java`, `R/V26__stripe_billing_bindings.sql`; Modify `B/provider/application/model/ProviderOperation.java` и соответствующий JDBC operation mapping/check constraints; Test `T/provider/adapter/out/persistence/StripeBillingBindingIntegrationTest.java`, `T/provider/adapter/out/stripe/StripeBillingCustomerAdapterTest.java`.

**Interfaces:** `Optional<CustomerBinding> findCustomer(PaymentAccountId accountId, SiteId siteId, CustomerId customerId)` и `CustomerBinding bindCustomer(PaymentAccountId accountId, SiteId siteId, CustomerId customerId, String providerCustomerReference, UUID originalProviderOperationId)` в `StripeBillingBindingPort`. `CustomerBinding` — immutable record `(UUID id, PaymentAccountId accountId, SiteId siteId, CustomerId customerId, String providerReference, UUID creationOperationId, long version)`, без secrets. Subscription/Invoice/Setup resource references используют существующий `SubscriptionObjectPort`; durable dispatch proof остаётся в ProviderOperationStore, не в raw body.

- [ ] RED: same-email foreign Site/Account, concurrent Customer creation, ACK lost before binding commit, stale lease holder и expiry23h. Требовать один dispatched original key и UNKNOWN без нового create.
- [ ] Добавить operation kinds CUSTOMER_CREATE, SUBSCRIPTION_CREATE, SUBSCRIPTION_UPDATE, SUBSCRIPTION_CANCEL, SETUP_INTENT_CREATE, INVOICE_PAY, SCHEDULE_CREATE, SCHEDULE_UPDATE, SCHEDULE_RELEASE, PORTAL_CONFIGURATION_CREATE, PORTAL_SESSION_CREATE. Каждому шагу — resource UUID, stable key/fingerprint исходной команды; существующие4 вида 5A не меняют поведение. Добавить internal object kind SCHEDULE в существующий SubscriptionObjectPort и соответствующий additive DB constraint; другие kinds сохраняются.
- [ ] GREEN: constraints на account/site/customer и account/provider-reference; guard/creation-event proof для неизвестного результата, без email search. Metadata сама по себе binding не создаёт.
- [ ] Run `./mvnw -Dtest=StripeBillingBindingIntegrationTest,StripeBillingCustomerAdapterTest,ProviderOperationStoreIntegrationTest,ProviderOperationGuardTest test` при разрешённой PostgreSQL среде; Docker-free части отдельно. Review и commit `feat: persist owned Stripe Billing bindings`.

### Task 3: Verified subscription/invoice snapshot mapping

**Files:** Create `B/provider/adapter/out/stripe/billing/StripeBillingSnapshotMapper.java`, `T/provider/adapter/out/stripe/StripeBillingSnapshotMapperTest.java`, `T/subscriptions/application/service/StripeBillingObservationIntegrationTest.java`; Modify `B/subscriptions/application/model/SubscriptionProviderModels.java`, `SubscriptionFactService.java` только для явного unversioned контракта/fence, если это требуется тестами.

**Produces:** normalized existing Snapshot/Invoice/CashObject из SDK objects; проверенная связь Account→Customer→Subscription→item/Price→Invoice→InvoicePayment→PaymentIntent→captured Charge. InvoicePayment allocation не заменяет проверку полного capture.

- [ ] RED: чужой customer/price/item/account, quantity2, currency mismatch, truncated pagination, split allocation, paid_out_of_band, credits, uncaptured intent, zero invoice и duplicate cash reference. Нельзя получить CASH/paid-through по одному invoice.status.
- [ ] RED: delayed response после нового paid period/cancel/plan update, одинаковые event timestamps, local revision/operation fence mismatch. Проверить прежние Fake revisions отдельно.
- [ ] GREEN: полная bounded pagination или явный refusal при превышении лимита; Stripe providerRevision0, fresh retrieve + local fence, никакого event-time sequencing. ZERO только для доказанного trial/разрешённой zero-proration; UNSUPPORTED не выдаёт доступ.
- [ ] Run focused mapper tests; PostgreSQL observation/fact regression при разрешённой среде. Review и commit `feat: verify Stripe subscription billing snapshots`.

### Task 4: Stripe create adapter and provider registry

**Files:** Create `B/provider/adapter/out/stripe/billing/StripeSubscriptionProviderAdapter.java`, `B/provider/adapter/in/configuration/SubscriptionProviderRegistryConfiguration.java`, `T/provider/adapter/out/stripe/StripeSubscriptionCreateTest.java`; Modify `B/provider/adapter/out/fake/FakeProviderConfiguration.java` (убрать только Fake-only subscription registry bean), Stripe configuration wiring; оставить Fake adapter.

**Consumes:** Tasks1–3, существующие Command/Reply/Outcome и immutable recurring catalog mappings.

- [ ] RED: Fake-only без Stripe keys, mixed Fake/Stripe account routing, non-trial incomplete create, unknown creation и webhook-before-ACK. Не создавать независимый PAYMENT_PREPARE intent для Subscription.
- [ ] GREEN: Customer binding, one-item classic subscription, default_incomplete/card-only/on_subscription, exact originating operation binding и narrow capabilities. ACK сохраняет reference, но не ACTIVE/paid entitlement. Все ещё не реализованные provider methods fail closed до своих задач.
- [ ] Run `./mvnw -Dtest=StripeSubscriptionCreateTest,StripeSandboxConfigurationTest,ArchitectureRulesTest test`; related Fake create regression в разрешённой среде. Review и commit `feat: route owned subscriptions to Stripe Billing`.

### Task 5: Scoped Billing client-session and confirmation grant

**Files:** Create `B/subscriptions/application/port/in/CreateSubscriptionClientSessionUseCase.java`, `B/subscriptions/application/service/SubscriptionClientSessionService.java`, `B/subscriptions/application/model/SubscriptionClientSession.java`, `B/subscriptions/adapter/in/web/SubscriptionClientSessionController.java`, `B/provider/adapter/out/stripe/billing/StripeBillingActionAdapter.java`, `R/V27__stripe_subscription_client_grants.sql`; Test `T/subscriptions/adapter/in/web/StripeSubscriptionClientSessionIntegrationTest.java`.

**Interfaces:** `SubscriptionClientSession issue(Command command)` в `CreateSubscriptionClientSessionUseCase`; `Command(SubscriptionCommandContext context, UUID subscriptionId, UUID operationId, String actionToken)`. Result record `(String provider, Flow flow, String publishableKey, String clientSecret, String actionToken, Instant expiresAt)`, provider строго `stripe`, Flow PAYMENT|SETUP. POST customer-only `/{id}/operations/{operationId}/client-session` под `/api/v1/customer/subscriptions`. Existing `action-confirmations` фиксирует/потребляет action attempt перед браузерным confirm; Stripe adapter этого endpoint возвращает receipt, не вызывает server-side Stripe confirm.

- [ ] RED: foreign actor/Site/Customer/origin/subscription/operation, missing recurring consent, expired/rotated token, lost ACK, parallel Pay и secret sink canaries. Partner/Admin endpoints не выдают raw provider context клиенту вместо CustomerSession.
- [ ] GREEN: непрозрачный локальный grant, хранится только hash и binding; no-store response, client_secret только из проверенного исходного invoice/setup. Существующий action-confirmations использует constant-time grant match и durable attempt; повтор receipt не разрешает новый browser confirm. Mutation/action views остаются redacted.
- [ ] Run scoped web/application tests и existing client-authority/redaction regression. Review и commit `feat: issue scoped Stripe subscription client sessions`.

### Task 6: Billing webhooks, invoice cash, renewals and reconciliation

**Files:** Modify `B/provider/adapter/out/stripe/StripeWebhookVerifier.java`, `B/provider/adapter/in/web/StripeWebhookController.java`, `B/webhooks/application/service/SubscriptionInboxWorker.java`, `B/webhooks/application/model/SubscriptionProviderEnvelope.java`; Create `B/provider/adapter/out/stripe/StripeVerifiedReceipt.java`, `B/provider/adapter/out/stripe/billing/StripeBillingInboxAdapter.java`, `T/provider/adapter/in/web/StripeBillingWebhookTest.java`, `T/subscriptions/application/service/StripeBillingLifecycleIntegrationTest.java`.

**Interfaces:** `Optional<StripeVerifiedReceipt> verifyAny(byte[] raw, String signature, PaymentAccount account, Instant receivedAt)`; sealed receipt содержит либо existing `ProviderReferenceReceipt`, либо existing `SubscriptionProviderEnvelope`. Старый `verify` остаётся payment/refund-only wrapper для совместимости. Internal SUBSCRIPTION_SCHEDULE_CHANGED добавляется явно; `ReceiveSubscriptionEventUseCase`/`SubscriptionInboxPort` остаются application входом Billing hints. Ни raw JSON, ни Stripe SDK object не пересекают application boundary.

- [ ] RED: каждый разрешённый design event type, exact-body signature/version/account/livemode failure; duplicate/out-of-order, arrival-before-mapping, signed event без verified capture, поздний decline после paid и повтор failed renewal, не продлевающий grace.
- [ ] GREEN: общий exact-body authenticate/decode до классификации на payment/refund либо subscription/invoice/setup/schedule; не считать пустой результат старого payment verifier доказательством аутентификации Billing. Типизированный Billing receipt идёт в существующий subscription inbox, не в one-time reference worker. Fresh retrieve и Task3 mapping используют existing facts/cash bridge/journal/outbox/callback. Invoice PaymentIntent разрешается через Billing mapping без второго financial source; one-time/refund5A routing сохраняется. SCHEDULE receipt использует internal SUBSCRIPTION_SCHEDULE_CHANGED и verified parent subscription, не принимается как произвольный Subscription ref. Invoice-created ACK происходит после durable inbox insert, без provider I/O в request. Полные invoice lists bounded и parent joins проверены.
- [ ] Run webhook/mapper tests, затем lifecycle/journal/outbox/callback/Fake/5A integration и Cucumber в разрешённой среде. Review и commit `feat: settle Stripe subscription invoices through verified facts`.

### Task 7: Trial, method setup and paused resume

**Files:** Extend `StripeSubscriptionProviderAdapter`, `StripeBillingActionAdapter`; Create `T/provider/adapter/out/stripe/StripeSubscriptionTrialTest.java`, `T/subscriptions/application/service/StripeSubscriptionMethodIsolationTest.java`; existing `SubscriptionMethodService`, `SubscriptionRecoveryService` менять только при доказанном adapter integration gap.

- [ ] RED: REQUIRED_BEFORE_TRIAL не создаёт trial до confirmed owned SetupIntent; OPTIONAL trial с точным сохранённым end; no-method end→PAUSED без cash/debt; повтор UNKNOWN не освобождает eligibility. Setup подпискиA не меняет default method подпискиB того же Customer.
- [ ] GREEN: server-owned stable trial_end, missing_payment_method=pause, per-subscription method binding; resume только явной операцией с проверкой исходного invoice/period/payment. Setup не оплачивает renewal debt. Не использовать pause_collection как PAUSED.
- [ ] Run targeted provider tests и существующие required/optional trial/resume/anonymization integration при разрешении. Review и commit `feat: support owned Stripe trials and payment method setup`.

### Task 8: Quotes, paid upgrades and deferred schedules

**Files:** Create `B/provider/adapter/out/stripe/billing/StripeBillingMutationAdapter.java`, `T/provider/adapter/out/stripe/StripeSubscriptionPlanChangeTest.java`; Extend `StripeBillingBindingAdapter` и registry capabilities, существующие `SubscriptionPlanService`/transaction только для bounded contract integration.

- [ ] RED: preview expiry, stale current terms, target outside product/currency, unpaid/failed proration, pending-update expiry, zero allowed proration; month↔year/deferred downgrade на точной границе; existing foreign schedule и lost schedule ACK.
- [ ] GREEN: сохранить proration timestamp/quote terms; paid upgrade через pinned pending_if_incomplete + always_invoice, entitled price только после соответствующего cash/ZERO proof. Schedules сохраняют текущую фазу, точный target и ownership; clear освобождает только свой schedule. Ни credit/refund, ни price switch на основании ACK.
- [ ] Run plan-change fixtures и existing quote/change/clear/scheduled entitlement tests. Review и commit `feat: apply Stripe plan changes with payment and schedule proofs`.

### Task 9: Cancellation, withdrawal and safe invoice recovery

**Files:** Extend `StripeBillingMutationAdapter`, `StripeSubscriptionProviderAdapter`; Create `T/provider/adapter/out/stripe/StripeSubscriptionRecoveryTest.java`, `T/provider/adapter/out/stripe/StripeSubscriptionCancellationTest.java`.

- [ ] RED: cancel/withdraw границы trial/paid period, immediate без policy, stale operation, debt grace boundary; existing active intent вместо нового invoice.pay; UNKNOWN/истёкшее23h окно, invoice paid concurrently и чужой invoice.
- [ ] GREEN: exact original-operation proofs; ordinary period-end cancel, safe immediate без нежелательного invoice/proration, withdrawal только до captured boundary. RETRY_ALLOWED после authoritative read; original-key invoice.pay отдельно от ассоциации существующего action. Scheduler никогда самостоятельно не делает финансовый recovery write.
- [ ] Run focused provider tests; existing cancellation/recovery/idempotency/payment-refund independence regression. Review и commit `feat: guard Stripe cancellation and invoice recovery`.

### Task 10: Policy-compatible real Customer Portal

**Files:** Create `B/provider/adapter/out/stripe/billing/StripeBillingPortalAdapter.java`, `B/provider/application/port/out/StripePortalConfigurationPort.java`, `R/V28__stripe_portal_policy_bindings.sql`, `T/provider/adapter/out/stripe/StripeBillingPortalTest.java`; Extend provider JDBC binding and `SubscriptionPortalService` только для explicit compatibility proof.

- [ ] RED: disabled Site policy, wrong Customer/Account, foreign return URL, config drift, another subscription с несовместимой policy, Portal navigation without signed server lifecycle fact. Нельзя открыть generic customer portal как обход ограничений выбранной подписки.
- [ ] GREEN: account/Site/policy-version config с разрешёнными invoice history и period-end cancellation flow; отдельная owned PORTAL_CONFIGURATION_CREATE с durable key/fingerprint для ensure-config и PORTAL_SESSION_CREATE для navigation. Lost config ACK или policy/config drift не создаёт новую конфигурацию вслепую. Customer-wide method update, subscription_update, coupons, quantity и прочее выключены. При невозможности доказать совместимость — unavailable, встроенные команды остаются доступными. URL memory-only и replay не создаёт бесконтрольно новые sessions.
- [ ] Run focused Portal tests и existing return/allowlist/lifecycle/cross-Customer regression. Review и commit `feat: constrain Stripe Customer Portal to Site policy`.

### Task 11: SDK Elements, subscription manager and Admin/demo

**Files:** Create `S/subscriptions/stripe-subscription-driver.ts`, `S/subscriptions/stripe-subscription-driver.test.ts`; Modify `S/subscriptions/action-driver.ts`, `client.ts`, `contracts.ts`, `wire.ts`, `generic-subscription-manager.ts`, `S/providers/stripe-loader.ts` only if shared loading needs extension; update existing Admin subscription views/demo without adding provider IDs/secrets to generic views.

- [ ] RED: authenticated issue-session/mount→Pay preflight→exactly one confirmPayment|confirmSetup→GET-only server polling; ready/3DS/processing/failed/UNKNOWN. Host connected and visible before Element mount; no false ready from iframe alone.
- [ ] RED: session replacement, logout, expiry, disconnect, delayed success, page return cleanup и refresh после spent grant. No duplicate POST/confirm or secrets in URL/storage/events/console/DOM diagnostics; Fake action APIs remain compatible.
- [ ] GREEN: lazy shared Stripe loader, memory-only bound action session and real Elements; all settlement/access comes from server. Manager shows pending/error/action states and retires spent form; Admin distinguishes provider ACK, billing status, local entitlement и reconciliation-required.
- [ ] Run SDK focused + full test/typecheck/build; Admin relevant tests/typecheck/lint/build, demo return/privacy build. Native browser fixtures intercept all third-party traffic. Review и commit `feat: add Stripe subscription Elements and management UI`.

### Task 12: Regression, operator guide and separate Billing acceptance

**Files:** Create `backend/src/test/resources/features/stripe_billing.feature`, `T/acceptance/StripeBillingSteps.java`, `infrastructure/stripe-billing-acceptance.mjs`, its Docker-free contract tests, `examples/demo-site/playwright.stripe-billing.config.ts`, `examples/demo-site/tests/stripe-billing.spec.ts`, `docs/stage-5b-acceptance.md`; update `docs/local-development.md`, SDK/Admin guides and default Playwright exclusion.

- [ ] RED/GREEN Cucumber: initial, trial, renewal/grace/recovery, change/cancel/Portal and mixed Fake/Stripe, using real application/PostgreSQL and official SDK HTTP fixture. Default Maven/CI needs no real Stripe credentials.
- [ ] Offline runner contract: explicit Billing opt-in, fresh UUID/source/Account/API/fixtures and exact ownership; no old-object target, no data/key/collector import. Worker1/retries0, stable persisted operation keys, finite total/step/cleanup limits, stop on first failure/unknown. Build on existing reviewed5A owner primitives; do not copy entire diagnostic framework or issue every possible scenario automatically without a bounded authorized budget.
- [ ] Full checkpoint when environment permitted: `./mvnw verify`; SDK/Admin/demo gates; exact safe infrastructure selections; default Fake E2E plus existing5A regression. Record actual NOT_RUN/skip reasons. Final independent whole-branch review and staged secret/source/migration audit, commit/push/exact-source hosted CI.
- [ ] Only after separate runtime permission: isolated Sandbox Billing cases from design§7, with owned test clocks for renewals, genuine human3DS where needed, provider/journal/outbox/signed callback proof and no fake ZERO cash. Reuse the Stage4 isolated business-clock boundary for grace; JWT/OTP/signatures retain realUTC, deadlines monotonic. Clock advances capable of generating test charges count against the explicit financial budget. No blanket retries; preserve every failed outcome and stop only owned services without deleting volumes. Mark5B accepted only after evidence, never after fixture/unit-only success.

## Dependency order and checkpoints

Tasks1→2→3→4→5→6 form the first backend checkpoint: a simple authenticated subscription can reach server-confirmed initial settlement offline. Task7 adds trial/setup; Task8–9 commands; Task10 Portal; Task11 complete UI; Task12 broad regression and separately authorized actual-provider acceptance. Tasks may be reviewed independently but provider capabilities remain false until their owning task passes.

Backend source, SDK transport and UI stay in separate files/projects. Each commit includes only its exact task files. Do not start5C or change deployment/provider credentials as a shortcut. Implementation method can preserve the existing subagent-and-independent-review workflow; this preparation does not start implementation or a Sandbox run.

## Self-review

Spec§1–2→Tasks1–4; §3→Tasks5/11; §4→Tasks3/6; §5→Tasks7–9; §6→Task10; §7→Task12. Five Review Focus risks have explicit owning tests. Interfaces use existing provider models except the explicit new client-session boundary and binding/config ports. No unknown provider outcome is promoted to declined/succeeded; no dependency pin change, financial retry or runtime authority is implicit. API-field incompatibility is handled by Task1 contract failure and a precise design correction before adapter implementation, not by an unspecified permissive fallback.
