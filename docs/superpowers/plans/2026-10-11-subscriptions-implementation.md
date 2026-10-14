# Subscriptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать согласованный этап 4 — подписки, их оплату и управление доступом через локальный Fake Provider, с Customer/Partner/Admin API и встраиваемым интерфейсом.

**Architecture:** Hexagonal modular monolith: новый модуль `subscriptions` управляет договором и entitlement; `payments` продолжает владеть денежными фактами. Provider ведёт invoice lifecycle; локальные inbox, projections, journals и outbox обеспечивают обработку и сверку. Backend сайта проверяет доступ серверно, не по browser events.

**Tech Stack:** Существующие Java 25, Spring Boot 4.1.1, Maven Wrapper, PostgreSQL 18, Flyway, JUnit, Testcontainers, Cucumber 7.34.8; TypeScript, Web Components, Next.js 16.3.3, React 19.3.0, Tailwind CSS, Vitest, Playwright, Docker Compose. Не обновлять версии ради этого этапа.

**Spec:** [Подтверждённая спецификация](/Users/viktar.marozau/Projects/my/generic-solution/payment/docs/superpowers/specs/2026-10-11-subscriptions-design.md). Письменная спецификация, включая anonymization guard, подтверждена пользователем 2026-10-11. План подтверждён; выбран вариант 1 — subagent-driven execution с отдельным исполнителем и review каждой задачи, локально и без Git.

## Global Constraints

- Проект остаётся локальным. Git, commits, push и worktrees не используются.
- `backend/` и `frontend/` остаются раздельными. Admin использует Next.js, React, TypeScript и Tailwind CSS; SDK не зависит от React и Next.js.
- Domain не зависит от Spring, JPA, HTTP, Jackson и provider SDK. Модули взаимодействуют через application ports, а не чужие repositories.
- PostgreSQL хранит транзакционные данные, журналы, inbox и outbox. Полный event sourcing не вводится.
- Денежные суммы используют integer minor units, идентификаторы доменных сущностей — UUID, время — внедрённый `Clock` и UTC.
- Этап 4 полностью проверяется локально через Fake Provider. Настоящий Stripe adapter, Stripe Sandbox, Payment Element, реальные Customer Portal sessions и проверка доступности карт, PayPal, Apple Pay и Google Pay относятся к этапу 5.
- Для `(site_id, customer_id, product_id)` допускается не более одной non-terminal подписки. Только подтверждённое завершение освобождает слот.
- В первой версии допускается один product item с quantity 1. Plan changes выполняются только внутри Product и currency.
- Grace duration задаётся неотрицательным целым количеством дней; 0 отключает льготу. По умолчанию deadline равен окончанию предыдущего оплаченного периода плюс 3 дня, где день — 24 часа в UTC.
- В первой версии `valid_until` не позже минимума из entitlement boundary и `evaluated_at + 5 минут`.
- Refund и cancellation независимы. Free trial и zero-amount invoice не создают фиктивный положительный Payment.
- Все изменяющие Subscription команды Customer, Partner и Admin требуют `Idempotency-Key`.
- Старые persisted sessions автоматически не получают новые полномочия; требуется новая аутентификация. Старые Partner keys также не расширяются автоматически.
- Новая copy поддерживает английский и португальский, locale-only update не запускает финансовую операцию.

Уточнение безопасности на review этого плана: выключение site-level функции подписок запрещается, пока остаются non-terminal подписки или UNKNOWN create/cancel. Это сохраняет согласованное правило выдачи новых scopes только enabled-Site и не лишает клиента управления после повторного входа. Изменение grace/разрешённых переходов остаётся доступным для будущих операций. Реализация этого guard входит в Task13.

## Review Focus

1. Конец месяца, високосный год и DST: domain использует подтверждённые provider boundaries, а Fake сохраняет календарный anchor; месяц не равен 30 дням. Тесты задач 2 и 4.
2. После preview опубликована новая Price либо изменена policy: apply не подменяет точную версию и не списывает пересчитанную сумму без нового согласия. Тесты задачи 9.
3. Customer сменился или вышел, пока запрос/setup ещё выполняется: старый результат не появляется в новой session, не сохраняет capability и не запускает второй POST. Тесты задач 14 и 15.
4. Отмена пересеклась с renewal/recovery: реальные деньги учитываются, но terminal Subscription и новая подписка того же Product не воскрешаются/не изменяются. Тесты задач 7 и 10.
5. Одинаковые provider IDs в разных accounts, повторные invoice/payment views одного settlement: account-scoped mappings изолированы, денежный факт ровно один. Тесты задач 3, 5 и 7.

---

## 1 Порядок выполнения и локальные gates

Работать последовательно по зависимостям. Здесь один план, поскольку денежные факты, состояние подписки и доступ имеют общие транзакционные инварианты; отдельно их принимать небезопасно. SDK и Admin остаются отдельными deliverables после стабилизации API.

| Блок | Задачи | Проверяемый результат |
| --- | --- | --- |
| Основание | 1–5 | Policies, pure domain, PostgreSQL constraints, Fake contracts, subscription Payments |
| Backend flows | 6–12 | Create, trial, provider facts, renewal/recovery, changes, cancellation, setup/Portal, reconciliation |
| Контракты и UI | 13–16 | Защищённые API, SDK, Web Component, Admin |
| Приёмка | 17 | Cucumber, browser E2E, Compose smoke и regression |

После каждого deliverable сохранить краткий локальный checkpoint: изменённые файлы, фактически выполненные команды, результат, незавершённые замечания. Это заменяет commit steps; никаких Git-команд. Не объявлять весь этап готовым по unit tests одного блока.

Команды ниже выполняются **при реализации**, не при составлении плана. Рабочий каталог для команд указан явно. Не устанавливать новые зависимости и не запускать сервисы сейчас. При реализации использовать имеющиеся runtimes; если нужного инструмента нет — сообщить, не менять окружение скрытно.

Для Maven-команд каталог — `backend/`; для `pnpm` — каталог соответствующего package. Фокусные Maven-команды используют имена тестов, создаваемых в данной задаче. Integration tests требуют доступного Docker/Testcontainers. RED означает содержательное падение нового поведения, не сетевую ошибку и не недоступность Docker.

## 2 Карта файлов и границ

Корень всех путей — `/Users/viktar.marozau/Projects/my/generic-solution/payment`.

Обозначения в задачах только для экономии повторений: `B` = `backend/src/main/java/dev/genericpayments/platform`, `T` = `backend/src/test/java/dev/genericpayments/platform`, `M` = `backend/src/main/resources/db/migration`. Например, `B/subscriptions/domain/Subscription.java` означает единственный точный путь, не wildcard. Каждый public Java type ниже хранится в одноимённом файле, если явно не назван nested type.

| Файлы | Ответственность |
| --- | --- |
| `B/catalog/domain/Price.java`, `TrialPaymentMethodPolicy.java`; `B/subscriptions/domain/SubscriptionPolicy.java`, `PlanTransition.java` | Версионированные условия, site policy, явное направление перехода |
| `B/subscriptions/domain/Subscription.java`, `SubscriptionPriceTerms.java`, `SubscriptionAccessPolicy.java`, `AccessDecision.java` | Договор, отдельный entitled тариф, pure access calculation |
| `B/subscriptions/domain/BillingRecord.java`, `SubscriptionOperation.java`, `SubscriptionEvent.java`, `TrialEligibility.java` | Invoice ledger, durable mutation, append-only lifecycle, trial reservation |
| `B/subscriptions/application/port/in/` | Узкие входящие use cases и commands, перечисленные в задачах |
| `B/subscriptions/application/port/out/` | Store, transactions, provider, authority, payment bridge, callback, telemetry |
| `B/subscriptions/application/service/` | Координаторы без HTTP/provider SDK; короткие transaction phases отдельно от provider calls |
| `B/subscriptions/adapter/out/persistence/` | JDBC repositories и PostgreSQL transaction/lease adapters |
| `B/subscriptions/adapter/out/integration/` | Вызовы application ports Catalog, Customers, Payments, Partners, Admin |
| `B/subscriptions/adapter/in/web/`, `adapter/in/configuration/` | Transport DTO, controllers, guards, wiring, schedulers |
| `B/provider/adapter/out/fake/` | Независимое, restart-safe состояние Fake billing и разрешённые Admin controls |
| `B/payments/domain/SubscriptionBillingContext.java` и Payments adapters | Явный source/context, денежные objects, существующие refunds/history |
| `B/webhooks/` и существующий `provider_inbox` | Маршрутизация событий, общая доставка callbacks, сохранение v1/v2 |
| `frontend/sdk/src/subscriptions/` | Headless client, validators, Fake action driver, встраиваемый manager |
| `frontend/admin/src/components/subscriptions/`, `src/app/subscriptions/` | Admin list/details/actions; policy page принадлежит Site |
| `examples/demo-site/`, `infrastructure/smoke-stage-4.mjs` | Реальные локальные integration/E2E flows, без заявления о защите статического контента |

Миграции только additive: V13 policies/Price, V14 subscriptions ledger, V15 Fake billing, V16 Payment context, V17 scopes, V18 inbox/outbox. Старые V1–V12 не редактировать. Изменения CHECK/functions допустимы новой миграцией с сохранением прежних смыслов; существующие payloads и финансовые строки не переписывать.

## 3 Общий словарь интерфейсов

Существующие типы `SiteId`, `CustomerId`, `ProductId`, `PriceId`, `PaymentAccountId`, `PaymentId`, `Money`, `BillingInterval`, `TaxMode`, `CustomerSessionContext`, application `AuthenticatedSite` и `AuthenticatedAdmin` не переименовывать. UUID использовать для новых Subscription, Operation, Quote, BillingRecord и Event IDs.

Новые типы вводятся в указанных задачах. Nullable поля здесь означают отсутствие факта, а не неизвестный успех. В wire DTO это явный `null`; secret-bearing DTO не входят в safe projections.

```java
// Task 1: subscriptions/domain/SubscriptionPolicy.java
public record SubscriptionPolicy(SiteId siteId, long version, boolean enabled,
        int graceDays, boolean allowImmediateCancellation, boolean portalEnabled,
        boolean deferUpgrades, Set<String> portalReturnUrls) {}

// Task 1: subscriptions/domain/PlanTransition.java
public record PlanTransition(SiteId siteId, ProductId productId,
        PriceId fromPriceId, PriceId toPriceId, Direction direction,
        boolean immediateAllowed, long policyVersion) {
    public enum Direction { UPGRADE, DOWNGRADE, LATERAL }
}

// Task 2: subscriptions/domain/SubscriptionPriceTerms.java
public record SubscriptionPriceTerms(PriceId priceId, String priceKey, int version,
        ProductId productId, Money regularAmount, BillingInterval interval,
        int trialDays, TrialPaymentMethodPolicy trialPolicy, TaxMode taxMode) {}

// Task 2: subscriptions/domain/Subscription.java
public record Subscription(UUID id, SiteId siteId, CustomerId customerId,
        ProductId productId, PaymentAccountId paymentAccountId,
        SubscriptionPriceTerms billingPrice, SubscriptionPriceTerms entitledPrice,
        Status billingStatus, String providerReference, Period currentPeriod,
        Instant paidThrough, Period trial, Cancellation cancellation,
        Change pendingChange, Debt debt, String terminalReason, long revision,
        Instant createdAt, Instant updatedAt, Instant lastReconciledAt) {
    public enum Status { INCOMPLETE, TRIALING, ACTIVE, PAST_DUE, PAUSED, UNPAID, CANCELLED }
    public record Period(Instant start, Instant end) {}
    public record Cancellation(Instant effectiveAt) {}
    public record Change(UUID operationId, SubscriptionPriceTerms target,
            Instant effectiveAt, ChangeState state) {}
    public enum ChangeState { PAYMENT_PENDING, SCHEDULED, UNKNOWN }
    public record Debt(UUID billingRecordId, Period unpaidPeriod, Instant graceDeadline) {}
}

// Task 2: subscriptions/domain/AccessDecision.java
public record AccessDecision(boolean allowed, Reason reason, SiteId siteId,
        CustomerId customerId, ProductId productId, UUID subscriptionId,
        PriceId entitledPriceId, Integer entitledPriceVersion, long revision,
        Instant evaluatedAt, Instant validUntil) {
    public enum Reason { PAID, TRIAL, GRACE, INITIAL_PAYMENT_REQUIRED,
        PAUSED, UNPAID, CANCELLED, EXPIRED, NO_SUBSCRIPTION }
}

// Task 3: subscriptions/domain/SubscriptionOperation.java
public record SubscriptionOperation(UUID id, SiteId siteId, CustomerId customerId,
        UUID subscriptionId, Kind kind, Status status, String authorityScope,
        String keyDigest, String fingerprint, String providerOperationKey,
        long expectedRevision, long policyVersion, String providerReference,
        String safeFailureCode, Instant createdAt, Instant updatedAt, long version) {
    public enum Kind { CREATE, CHANGE, CLEAR_CHANGE, CANCEL, WITHDRAW_CANCEL,
        SETUP, RECOVER, RESUME, PORTAL, RECONCILE }
    public enum Status { RESERVED, PROCESSING, REQUIRES_ACTION, UNKNOWN, SUCCEEDED, FAILED }
}

// Task 3: subscriptions/domain/TrialEligibility.java
public record TrialEligibility(SiteId siteId, CustomerId customerId,
        ProductId productId, UUID reservedByOperation, Instant consumedAt, long version) {}

// Task 3: subscriptions/domain/BillingRecord.java
public record BillingRecord(UUID id, SiteId siteId, UUID subscriptionId,
        PaymentAccountId accountId, String providerInvoiceReference, Kind kind,
        SubscriptionPriceTerms billedPrice, SubscriptionPriceTerms previousPrice,
        Subscription.Period period, long amountDueMinor, String currency,
        Settlement settlement, long version, Instant createdAt, Instant updatedAt) {
    public enum Kind { INITIAL, RENEWAL, PRORATION }
    public enum Settlement { OPEN, CONFIRMED_CASH, ZERO_AMOUNT, UNSUPPORTED, VOID }
}

// Task 3: subscriptions/domain/SubscriptionEvent.java
public record SubscriptionEvent(UUID id, SiteId siteId, UUID subscriptionId,
        PaymentAccountId accountId, long sequence, long revision, Type type,
        Source source, Instant occurredAt, Instant receivedAt, UUID operationId,
        UUID billingRecordId, PaymentId paymentId, String providerReference,
        String factKey, String requestId) {
    public enum Source { API, PROVIDER_WEBHOOK, RECONCILIATION, DEADLINE }
    public enum Type { CREATED, CONFIRMED, TRIAL_STARTED, TRIAL_ENDED, PAUSED,
        PERIOD_PAID, RENEWAL_FAILED, GRACE_STARTED, ACCESS_SUSPENDED,
        RECOVERED, CHANGE_REQUESTED, CHANGE_SCHEDULED, CHANGE_APPLIED,
        CHANGE_CLEARED, CANCEL_SCHEDULED, CANCEL_WITHDRAWN, CANCELLED,
        METHOD_UPDATED, RECONCILIATION_REQUIRED }
}
```

Все records получают constructor validation в своей задаче: обязательные IDs, времена, nonnegative counters/amountDue, positive regular Money, currency/ownership, bounded safe references. `Money(0, ...)` не создавать: zero invoice моделируется `BillingRecord.amountDueMinor = 0`. Signed proration preview использует `long`, не `Money`.

## Task 1: Price trial policy и настройки подписок

**Files:**

- Create: `B/catalog/domain/TrialPaymentMethodPolicy.java`.
- Modify: `B/catalog/domain/Price.java`, `B/catalog/application/port/in/CreatePriceCommand.java`, `B/catalog/application/service/CreatePriceService.java`, `B/catalog/adapter/in/web/CreatePriceRequest.java`, `PriceResponse.java`, `B/catalog/adapter/out/persistence/PriceJpaEntity.java`, `PricePersistenceAdapter.java`.
- Create: `B/subscriptions/domain/SubscriptionPolicy.java`, `PlanTransition.java`; `B/subscriptions/application/port/out/SubscriptionPolicyPort.java`; `B/subscriptions/adapter/out/persistence/SubscriptionPolicyPersistenceAdapter.java`.
- Create: `M/V13__subscription_catalog_policies.sql`.
- Test: `T/catalog/domain/PriceTest.java`, `T/subscriptions/domain/SubscriptionPolicyTest.java`, `T/subscriptions/adapter/out/persistence/SubscriptionPolicyIntegrationTest.java`.

**Interfaces:**

- Consumes: existing `Price.draft(...)`, `Money.of(long,String)`, `SiteId`, `ProductId`, `PriceId`.
- Produces: `TrialPaymentMethodPolicy { REQUIRED_BEFORE_TRIAL, OPTIONAL_DURING_TRIAL }`; `Price.trialPaymentMethodPolicy()`; `Price.withTrialPaymentMethodPolicy(TrialPaymentMethodPolicy)` только для draft.
- Produces: `SubscriptionPolicyPort.load(SiteId): SubscriptionPolicy`, `save(SubscriptionPolicy,long expectedVersion): SubscriptionPolicy`, `transition(SiteId,ProductId,PriceId,PriceId): Optional<PlanTransition>`, `transitions(SiteId,ProductId): List<PlanTransition>`, `saveTransition(PlanTransition): void`.

- [ ] **1. RED: зафиксировать immutability trial policy и defaults.** Добавить в existing `PriceTest`, используя его draft fixture `Price.draft(...)`; новый self-contained policy test:

```java
@Test void defaultsDoNotEnableSubscriptionsOrPortal() {
    var p = SubscriptionPolicy.defaults(new SiteId(UUID.randomUUID()));
    assertThat(p.enabled()).isFalse();
    assertThat(p.graceDays()).isEqualTo(3);
    assertThat(p.portalEnabled()).isFalse();
    assertThat(p.allowImmediateCancellation()).isFalse();
}
@Test void negativeGraceIsRejected() {
    assertThatThrownBy(() -> new SubscriptionPolicy(new SiteId(UUID.randomUUID()),
        1, true, -1, false, false, false, Set.of()))
        .isInstanceOf(DomainValidationException.class);
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=PriceTest,SubscriptionPolicyTest test`. Expected: новые методы/types отсутствуют либо immutability/default assertion fails.
- [ ] **3. Ввести policy types и сохранить старые Price call sites.** Existing draft overloads/constructor overload и old `CreatePriceCommand` delegate к optional default; новые signatures добавляют `TrialPaymentMethodPolicy trialPaymentMethodPolicy`. Published copy сохраняет поле, `withTrialPaymentMethodPolicy` вызывает `requireDraft()`. `ONE_TIME` не получает trial даже при optional default.

```java
public enum TrialPaymentMethodPolicy { REQUIRED_BEFORE_TRIAL, OPTIONAL_DURING_TRIAL }

public static SubscriptionPolicy defaults(SiteId siteId) {
    return new SubscriptionPolicy(siteId, 0, false, 3, false, false, false, Set.of());
}
public SubscriptionPolicy {
    Objects.requireNonNull(siteId);
    if (version < 0 || graceDays < 0) throw new DomainValidationException("Invalid subscription policy");
    portalReturnUrls = Set.copyOf(portalReturnUrls);
}
```

- [ ] **4. Написать V13 и persistence tests.** SQL adds `prices.trial_payment_method_policy` with optional default и CHECK enum; заменить существующую published-price immutability function новой версией, включающей поле, не меняя прежних сравнений. Создать versioned `site_subscription_policies` и `subscription_plan_transitions` с Site/Product/Price composite FK; `(site_id,from_price_id,to_price_id)` unique, from != to. Policy changes increment version, existing snapshots не обновляют. Return URLs: exact configured HTTPS URL либо local-profile loopback URL, без credentials/fragment; arbitrary browser URL не принимать.

```sql
alter table prices add column trial_payment_method_policy varchar(32) not null
 default 'OPTIONAL_DURING_TRIAL'
 check (trial_payment_method_policy in ('REQUIRED_BEFORE_TRIAL','OPTIONAL_DURING_TRIAL'));
```

- [ ] **5. Run GREEN.** `./mvnw -Dtest=PriceTest,SubscriptionPolicyTest,SubscriptionPolicyIntegrationTest,PublishedPriceConstraintTest,CatalogTransactionalServiceIntegrationTest test`. Assert legacy published amount/version/interval/trialDays unchanged; required policy cannot be edited after publish; transitions reject foreign Product/Site/currency at application boundary. Record local checkpoint.

## Task 2: Pure Subscription и access decision

**Files:**

- Create: `B/subscriptions/domain/SubscriptionPriceTerms.java`, `Subscription.java`, `SubscriptionAccessPolicy.java`, `AccessDecision.java`, `ChangeTiming.java`, `SubscriptionRuleViolation.java`.
- Test: `T/subscriptions/domain/SubscriptionTest.java`, `SubscriptionAccessPolicyTest.java`, `SubscriptionFixtures.java`.
- Modify: `T/ArchitectureRulesTest.java` только для добавления subscription-specific application/adapter boundary rule.

**Interfaces:**

- Consumes: Task 1 policy/terms; existing ID/Money types.
- Produces: common records; `SubscriptionAccessPolicy.evaluate(Subscription,Instant): AccessDecision`; `SubscriptionAccessPolicy.validUntil(Instant,Instant): Instant`.
- Produces domain `ChangeTiming { IMMEDIATE, NEXT_PERIOD }`; `SubscriptionRuleViolation(Reason,String message)` extends RuntimeException, exposes `reason()`, nested `Reason { STATE_CONFLICT, QUOTE_STALE }`. Pure rules throw it without HTTP status/application exception; Task6 translates it at the application boundary.

```java
public final class SubscriptionRuleViolation extends RuntimeException {
    public enum Reason { STATE_CONFLICT, QUOTE_STALE }
    private final Reason reason;
    public SubscriptionRuleViolation(Reason reason,String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }
    public Reason reason() { return reason; }
}
```

- Produces pure transitions: `Subscription.confirmTrial(Period,Instant)`, `confirmPaid(SubscriptionPriceTerms,Period,Instant)`, `renewalFailed(UUID,Period,int,Instant)`, `pause(Instant)`, `scheduleCancellation(Instant,Instant)`, `withdrawCancellation(Instant)`, `cancel(String,Instant)` all return a new `Subscription`. `displayState(Instant): String`; `nonTerminal(): boolean`.
- Produces `SubscriptionFixtures.active(Instant start,Instant end): Subscription` для unit tests: random same-Site IDs, Basic monthly EUR1000 terms, ACTIVE, matching billing/entitled Price, paidThrough=end, no trial/change/debt/cancel, revision1. Это test-only factory, не production builder.

- [ ] **1. RED: проверить access cache cap и точную boundary.**

```java
@Test void paidAccessExpiresWithoutAJob() {
    Instant start = Instant.parse("2028-02-29T12:00:00Z");
    Instant end = Instant.parse("2029-02-28T12:00:00Z");
    var s = SubscriptionFixtures.active(start, end);
    var policy = new SubscriptionAccessPolicy();
    assertThat(policy.evaluate(s, end.minusSeconds(1)).validUntil()).isEqualTo(end);
    assertThat(policy.evaluate(s, end).allowed()).isFalse();
    assertThat(policy.evaluate(s, start).validUntil()).isEqualTo(start.plusSeconds(300));
}
@Test void duplicateRenewalFailureDoesNotExtendGrace() {
    Instant end = Instant.parse("2026-10-31T10:00:00Z");
    var s = SubscriptionFixtures.active(end.minusSeconds(86400), end);
    UUID invoice = UUID.randomUUID();
    var period = new Subscription.Period(end, Instant.parse("2026-11-30T10:00:00Z"));
    var failed = s.renewalFailed(invoice, period, 3, end.plusSeconds(1));
    var duplicate = failed.renewalFailed(invoice, period, 3, end.plusSeconds(80000));
    assertThat(duplicate.debt().graceDeadline()).isEqualTo(end.plusSeconds(259200));
    assertThat(duplicate.revision()).isEqualTo(failed.revision());
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=SubscriptionTest,SubscriptionAccessPolicyTest test`. Expected: missing domain implementation.
- [ ] **3. Реализовать temporal access, без длительностей MONTH/YEAR в domain.** `Period` requires start < end. Allowed paid/trial/grace requires nonterminal and exact entitled terms. Cancellation effectiveAt caps boundary; terminal always denies. Grace requires previous confirmed paidThrough and entitledPrice, not initial unpaid/trial. `UNPAID`/`PAUSED` deny. At boundary use `now.isBefore(boundary)`, not `<=`. `NO_SUBSCRIPTION` — результат query layer с nullable subscription/Price, не synthetic Subscription.

```java
public static Instant validUntil(Instant now, Instant boundary) {
    Instant cap = now.plus(Duration.ofMinutes(5));
    return boundary.isBefore(cap) ? boundary : cap;
}
public boolean nonTerminal() { return billingStatus != Status.CANCELLED; }
public String displayState(Instant now) {
    boolean live = billingStatus == Status.ACTIVE && paidThrough != null && now.isBefore(paidThrough)
        || billingStatus == Status.TRIALING && trial != null && now.isBefore(trial.end());
    return cancellation != null && live ? "CANCEL_AT_PERIOD_END" : billingStatus.name();
}
```

- [ ] **4. Добавить parameterized cases и GREEN.** Cases: before/at/after paid/trial/grace boundary; graceDays0; late first failure anchored to paidThrough; optional trial pause; unpaid; cancel flag + pastDue displays PAST_DUE; immediate cancellation denies even prior paidThrough; terminal ignores late confirmPaid; provider billing target differs from entitledPrice during unpaid scheduled change. `./mvnw -Dtest=SubscriptionTest,SubscriptionAccessPolicyTest,ArchitectureRulesTest test`. Record checkpoint.

## Task 3: PostgreSQL ledger, uniqueness и anonymization guard

**Files:**

- Create: `B/subscriptions/domain/BillingRecord.java`, `SubscriptionOperation.java`, `SubscriptionEvent.java`, `TrialEligibility.java`.
- Create: `B/subscriptions/application/port/out/SubscriptionStore.java`, `BillingRecordStore.java`, `SubscriptionOperationStore.java`, `SubscriptionEventStore.java`, `TrialEligibilityStore.java`, `SubscriptionTransactionPort.java`, `SubscriptionLifecycleLockPort.java`.
- Create: `B/subscriptions/adapter/out/persistence/SubscriptionPersistenceAdapter.java`, `BillingRecordPersistenceAdapter.java`, `SubscriptionOperationPersistenceAdapter.java`, `SubscriptionEventPersistenceAdapter.java`, `TrialEligibilityPersistenceAdapter.java`, `SpringSubscriptionTransactionAdapter.java`.
- Create: `B/customers/application/port/out/CustomerBillingAnonymizationGuardPort.java`; `B/subscriptions/adapter/out/integration/CustomerBillingAnonymizationGuardAdapter.java`, `SubscriptionLifecycleLockAdapter.java`; `B/paymentaccounts/application/port/out/LockPaymentAccountPort.java`.
- Modify: `B/paymentaccounts/adapter/out/persistence/PaymentAccountPersistenceAdapter.java` to implement the account lock port.
- Modify: `B/customers/application/service/CustomerAnonymizationService.java`, `B/customers/adapter/in/configuration/CustomerManagementConfiguration.java`, `T/support/PostgresIntegrationTest.java`.
- Create: `M/V14__subscription_ledger.sql`.
- Test: `T/subscriptions/adapter/out/persistence/SubscriptionConstraintsIntegrationTest.java`, `SubscriptionJournalIntegrationTest.java`, `SubscriptionAnonymizationIntegrationTest.java`, `SubscriptionMigrationIntegrationTest.java`.

**Interfaces:**

- Consumes: Task 2 Subscription; existing `LockSitePort.findByIdForUpdate`, `CustomerStore.findByIdForUpdate`.
- Produces: `SubscriptionStore.find(SiteId,UUID): Optional<Subscription>`, `lock(SiteId,UUID): Subscription`, `save(Subscription,long expectedRevision): Subscription`, `hasBlockingCustomerSubscriptions(SiteId,CustomerId): boolean`, `hasBlockingSiteSubscriptions(SiteId): boolean`, `hasNonTerminalAccountSubscriptions(PaymentAccountId): boolean`.
- Produces: `SubscriptionLifecycleLockPort.lockActive(SiteId,CustomerId): void` (ambient transaction mandatory); `SubscriptionTransactionPort.required(Supplier<T>): T`, `requiresNew(Supplier<T>): T`, `active(): boolean`.
- Produces: `LockPaymentAccountPort.findByIdForUpdate(PaymentAccountId): Optional<PaymentAccount>`; create pins an ACTIVE account under this lock. Account status writes in Task12 use the same row lock.
- Produces: `BillingRecordStore.upsert(BillingRecord): BillingRecord`, `lock(SiteId,UUID): BillingRecord`; `TrialEligibilityStore.isEligible(SiteId,CustomerId,ProductId): boolean` (read only), `reserve(SiteId,CustomerId,ProductId,UUID): boolean`, `consume(UUID,Instant): void`, `releaseConfirmedFailure(UUID): void`.
- Produces: `SubscriptionOperationStore.claim(SubscriptionOperation): Claim`, nested `Claim(SubscriptionOperation operation,boolean owner)`; `lock(UUID): SubscriptionOperation`, `save(SubscriptionOperation,long expectedVersion): SubscriptionOperation`, `hasUnresolved(SiteId,UUID): boolean`.
- Produces: `SubscriptionEventStore.append(SubscriptionEvent): boolean` (factKey dedup + sequence allocated transactionally), `list(SiteId,UUID,int limit,long afterSequence): List<SubscriptionEvent>`.
- Produces: `CustomerBillingAnonymizationGuardPort.requireAnonymizable(SiteId,CustomerId): void`; exception `PaymentApplicationException("CUSTOMER_BILLING_ACTIVE",409,"Finish subscriptions before anonymization")`.

- [ ] **1. RED: guard до удаления PII.** В новом test создать mock guard и проверить его invocation в existing anonymization transaction до access revocation; PostgreSQL test использует новый ledger и существующий Admin fixture.

```java
@Test void guardRejectsAnonymizationWithoutCancellingAnything() {
    var store = mock(SubscriptionStore.class);
    var guard = new CustomerBillingAnonymizationGuardAdapter(store);
    var site = new SiteId(UUID.randomUUID());
    var customer = new CustomerId(UUID.randomUUID());
    when(store.hasBlockingCustomerSubscriptions(site, customer)).thenReturn(true);
    assertThatThrownBy(() -> guard.requireAnonymizable(site, customer))
        .isInstanceOf(PaymentApplicationException.class)
        .hasMessage("Finish subscriptions before anonymization");
    verify(store).hasBlockingCustomerSubscriptions(site, customer);
    verifyNoMoreInteractions(store);
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=SubscriptionConstraintsIntegrationTest,SubscriptionAnonymizationIntegrationTest test`.
- [ ] **3. Написать V14 и JDBC stores.** Tables: `subscriptions`, `subscription_operations`, `subscription_trial_eligibility`, `subscription_billing_records`, `subscription_provider_objects`, `subscription_events`, `subscription_event_sequences`, `subscription_quotes`, `subscription_action_capabilities`, `subscription_portal_sessions`. V14 сразу содержит quote fields Task9, digest-only capability fields Task11, owner/expiry/public operation ID для Fake Portal, а также `next_reconcile_at`, `reconcile_lease_owner`, `reconcile_lease_token`, `reconcile_lease_until`, `reconcile_attempts` для Task12. Применённая миграция больше не редактируется; задачи9/11/12 используют подготовленные tables/columns. Composite FKs: Site/Customer; Site/Product; exact Site/Product/Price; Subscription/Site/Account; invoice/Subscription/Account. Required periods use start < end, version/revision >=0, no partial timestamp pairs. Unresolved operation types serialized per Subscription. Persist operation payload as strict safe fields, no token/setup secret/email/raw provider JSON.

```sql
create unique index uq_subscription_live_product
 on subscriptions(site_id,customer_id,product_id) where billing_status <> 'CANCELLED';
create unique index uq_subscription_provider_object
 on subscription_provider_objects(provider_type,payment_account_id,object_type,provider_reference);
create unique index uq_subscription_fact
 on subscription_events(subscription_id,fact_key);
create unique index uq_subscription_event_sequence
 on subscription_events(subscription_id,sequence_number);
```

Pinned account FK references the historical PaymentAccount identity, not `(sites.id, sites.payment_account_id)`: changing Site routing must not invalidate existing subscriptions. Ownership constraints relate the stored Subscription/Site/Account triple and its invoices, not mutable current routing.

- [ ] **4. Внедрить lock/guard и append-only protection.** Global order: operation/idempotency claim → Site row → Customer row → PaymentAccount row → Subscription → billing record → Payment → journal sequence → callback config/secret. Шаги без нужды в конкретном lock его пропускают, но не меняют порядок; account-only status mutation не захватывает Site/Customer после account lock. Create и anonymize используют одни Site/Customer locks; operations не удаляются, UNKNOWN create/cancel также блокирует guard. Для anonymization вставить guard непосредственно после Customer lock и до revoke/redaction; constructor wiring/tests update explicitly, не оставлять permissive production fallback. Journal UPDATE/DELETE trigger rejects. Добавить новые tables в test cleanup.

```java
public void requireAnonymizable(SiteId site, CustomerId customer) {
    if (subscriptions.hasBlockingCustomerSubscriptions(site, customer))
        throw new PaymentApplicationException("CUSTOMER_BILLING_ACTIVE", 409,
            "Finish subscriptions before anonymization");
}
```

- [ ] **5. Run GREEN.** `./mvnw -Dtest=SubscriptionConstraintsIntegrationTest,SubscriptionJournalIntegrationTest,SubscriptionAnonymizationIntegrationTest,SubscriptionMigrationIntegrationTest,CustomerAnonymizationServiceIntegrationTest test`. Concurrent inserts same Product: exactly one winner; different Product accepted; scheduled cancellation still blocks; terminal frees slot. Two-thread create/anonymize latch test asserts either create wins and anonymization rejects with PII intact, or anonymization wins and create rejects; never active billing on anonymized Customer. Migration test Flyway target12 seed → latest checks untouched old amounts/versions/session scopes and new defaults. Record checkpoint.

## Task 4: Provider-neutral billing contract и persistent Fake

**Files:**

- Create: `B/subscriptions/application/model/SubscriptionProviderModels.java`; `B/subscriptions/application/port/out/SubscriptionProviderPort.java`, `SubscriptionProviderRegistry.java`, `FakeSubscriptionControlPort.java`.
- Create: `B/provider/adapter/out/fake/FakeSubscriptionProviderAdapter.java`, `FakeSubscriptionStateAdapter.java`, `FakeBillingCalendar.java`; modify `B/provider/adapter/out/fake/FakeProviderConfiguration.java`.
- Create: `M/V15__fake_subscription_billing.sql`.
- Test: `T/subscriptions/application/service/SubscriptionProviderContractTest.java`, `T/provider/adapter/out/fake/FakeSubscriptionProviderIntegrationTest.java`, `FakeBillingCalendarTest.java`.

**Interfaces:**

- Consumes: persisted operation key, pinned PaymentAccount, terms, Period; no platform-generated autonomous renewal charge.
- Produces: registry `forAccount(PaymentAccount): SubscriptionProviderPort`.
- `SubscriptionProviderModels` contains only named nested application records/enums: `Capabilities(boolean conditionalUpdate,boolean safeImmediateCancel,boolean setup,boolean portal)`; `Command(PaymentAccount account,SubscriptionOperation operation,SubscriptionPriceTerms current,SubscriptionPriceTerms target,String providerSubscriptionReference,String providerInvoiceReference,String providerQuoteReference,boolean recurringConsent)`; `Reply(Outcome outcome,String reference,NextAction action)`; `Outcome { ACCEPTED, REQUIRES_ACTION, DECLINED, UNKNOWN }`; `NextAction(ActionKind kind,String secret,Instant expiresAt)` with redacted `toString`; `ActionKind { FAKE_CONFIRM_PAYMENT, FAKE_CONFIRM_SETUP, PROVIDER_CLIENT }`.
- Additional models: `Lookup(PaymentAccount account,UUID subscriptionId,String reference,String providerOperationKey)`; `Snapshot(String subscriptionReference,Subscription.Status status,SubscriptionPriceTerms billingPrice,Subscription.Period period,Subscription.Period trial,Instant cancellationAt,List<Invoice> invoices,String operationKey,boolean paymentMethodReady,long providerRevision,Instant observedAt)`; `Invoice(String reference,BillingRecord.Kind kind,SubscriptionPriceTerms price,SubscriptionPriceTerms previousPrice,Subscription.Period period,long amountDueMinor,String currency,SettlementKind settlement,List<CashObject> cashObjects)`; `SettlementKind { OPEN, CASH, ZERO, UNSUPPORTED, VOID }`; `CashObject(String reference,long amountMinor,String currency,PaymentStatus status,Instant occurredAt)`.
- Quote models: `QuoteRequest(Command command,SubscriptionPriceTerms target,ChangeTiming timing)` uses Task2 domain enum; `ProviderQuote(String reference,long dueNowMinor,long nextAmountMinor,String currency,Instant effectiveAt,Instant expiresAt,boolean taxFinal)`.
- Port methods: `capabilities(PaymentAccount): Capabilities`, `create(Command): Reply`, `preview(QuoteRequest): ProviderQuote`, `change(Command): Reply`, `clearChange(Command): Reply`, `cancel(Command,boolean immediate): Reply`, `withdrawCancel(Command): Reply`, `setup(Command): Reply`, `recover(Command): Reply`, `resume(Command): Reply`, `retrieve(Lookup): Snapshot`, `nextAction(Lookup): NextAction`, `confirmAction(Command,String actionToken): Reply`, `portal(Command,String allowedReturnUrl): PortalNavigation`.
- `PortalNavigation(String navigationUrl,Instant expiresAt,boolean simulated)` redacts URL in toString. Provider reference/operation receipt immutable; lists defensively copied. Provider DTOs/API secrets never enter domain.
- Fake controls: `advance(PaymentAccountId,Instant): void`, `scenario(PaymentAccountId,UUID,Scenario): void`, `completeAction(PaymentAccountId,UUID): void`; `Scenario { SUCCESS, PROCESSING, REQUIRES_ACTION, DECLINE, UNKNOWN, ZERO_PRORATION, UNSUPPORTED_SETTLEMENT }`.
- Calendar: `FakeBillingCalendar.periodEnd(Instant anchor,int anchorDay,int periods,BillingInterval): Instant`.

- [ ] **1. RED: сохранить календарный anchor.**

```java
@Test void monthlyAnchorReturnsToTheThirtyFirstAfterFebruary() {
    Instant anchor = Instant.parse("2028-01-31T10:00:00Z");
    assertThat(FakeBillingCalendar.periodEnd(anchor, 31, 1, BillingInterval.MONTH))
        .isEqualTo(Instant.parse("2028-02-29T10:00:00Z"));
    assertThat(FakeBillingCalendar.periodEnd(anchor, 31, 2, BillingInterval.MONTH))
        .isEqualTo(Instant.parse("2028-03-31T10:00:00Z"));
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=FakeBillingCalendarTest,SubscriptionProviderContractTest test`.
- [ ] **3. Ввести interface и календарь.** Fake хранит original anchor month/day/time отдельно от latest period end; YEAR leap-day truncation не теряет original day. Signed preview may be negative, but immediate negative не применяется. `CashObject` represents an actual positive-valued provider payment object/attempt, including FAILED/REQUIRES_ACTION, not proof of successful movement. Snapshot не сообщает `CASH` без matching SUCCEEDED objects/amount/currency. ZERO invoices contain no payment objects. `accepted` и browser completion не открывают entitlement.

```java
public static Instant periodEnd(Instant anchor, int anchorDay, int periods, BillingInterval interval) {
    if (anchorDay < 1 || anchorDay > 31 || periods < 1) throw new IllegalArgumentException("Invalid billing anchor");
    var base = anchor.atZone(ZoneOffset.UTC);
    var next = interval == BillingInterval.MONTH ? base.plusMonths(periods) : base.plusYears(periods);
    return next.withDayOfMonth(Math.min(anchorDay, next.toLocalDate().lengthOfMonth())).toInstant();
}
```

- [ ] **4. Создать independent Fake persistence.** Tables `fake_subscription_objects`, `fake_subscription_operations`, `fake_subscription_invoices`, `fake_subscription_cash_objects`, `fake_subscription_event_queue`; account-scoped refs, immutable key+fingerprint receipts; deterministic IDs derived from operation/invoice. Fake не читает local Subscription/Payment projection как provider truth. V15 cash objects linked to owning subscription/invoice, но positive payments будут импортированы задачей5. Unknown receipt survives restart; provider simulator time stored per account, не изменяет global production Clock.

```sql
create unique index uq_fake_subscription_operation
 on fake_subscription_operations(payment_account_id,operation_key);
create unique index uq_fake_subscription_invoice
 on fake_subscription_invoices(payment_account_id,provider_reference);
```

- [ ] **5. Run GREEN.** `./mvnw -Dtest=FakeBillingCalendarTest,SubscriptionProviderContractTest,FakeSubscriptionProviderIntegrationTest test`. Calendar cases include January31→February→March31, February29 YEAR→non-leap→leap, and Europe/Lisbon DST transition instants: provider/Fake UTC boundaries stay exact regardless of JVM default timezone. Also exercise trial end with/without method, scheduled target, success/decline/action/unknown, zero/unsupported settlement, identical key replay, mismatched fingerprint reject, retrieve after new adapter instance, account isolation. Queue delivery wired in Task7; Fake advance only creates provider facts, no direct local grant. Record checkpoint.

## Task 5: PaymentSource SUBSCRIPTION и денежный bridge

**Files:**

- Create: `B/payments/domain/SubscriptionBillingContext.java`; modify `Payment.java`, `PaymentSource.java`.
- Create: `B/payments/application/port/in/RecordSubscriptionCashUseCase.java`; `B/payments/application/service/SubscriptionCashService.java`.
- Modify: `B/payments/application/service/PaymentCreationTransaction.java`, `PaymentTransitionService.java`, `B/payments/adapter/out/persistence/PaymentJpaEntity.java`, `PaymentPersistenceMapper.java`, `PaymentPersistenceAdapter.java`.
- Create: `B/subscriptions/application/port/out/SubscriptionCashPort.java`; `B/subscriptions/adapter/out/integration/SubscriptionCashAdapter.java`.
- Create: `B/provider/adapter/out/fake/FakeSubscriptionCashLookupAdapter.java`; modify `FakePaymentProviderAdapter.java`, `FakeProviderStatePersistenceAdapter.java` in the same directory for lookup/refund ownership of subscription cash.
- Modify: `B/payments/adapter/out/persistence/PaymentOutboxPersistenceAdapter.java`, `B/webhooks/adapter/out/persistence/CanonicalCallbackPayloadAdapter.java`, `infrastructure/callback-receiver.mjs`, `callback-receiver.test.mjs` for v3 payment context before enabling cash import.
- Create: `M/V16__subscription_payment_context.sql`.
- Test: `T/payments/domain/SubscriptionBillingContextTest.java`, `T/payments/adapter/out/persistence/SubscriptionPaymentIntegrationTest.java`, `T/payments/application/service/SubscriptionCashIntegrationTest.java`; `T/webhooks/application/service/SubscriptionPaymentCallbackIntegrationTest.java`.

**Interfaces:**

- Consumes: positive `CashObject`, BillingRecord, pinned account; same transaction as lifecycle projection.
- Produces context `SubscriptionBillingContext(UUID subscriptionId,UUID billingRecordId,Kind kind,ProductId productId,PriceId priceId,int priceVersion,PriceId previousPriceId,Integer previousPriceVersion,Instant periodStart,Instant periodEnd,String providerInvoiceReference)`, nested `Kind { INITIAL, RENEWAL, PRORATION }`.
- Produces `RecordSubscriptionCashUseCase.record(Command): Result`; nested `Command(SiteId siteId,UUID customerId,PaymentAccountId accountId,PaymentProviderType provider,SubscriptionBillingContext context,String providerPaymentReference,Money amount,TaxMode taxMode,PaymentStatus status,Instant occurredAt,String factKey)`, `Result(PaymentId paymentId,boolean newFact)`.
- Produces `SubscriptionCashPort.record(RecordSubscriptionCashUseCase.Command): RecordSubscriptionCashUseCase.Result` through integration adapter. Port is `MANDATORY`, provider calls forbidden. Adapter invokes Payments application port, not Payments repository.
- New `Payment.subscription(PaymentId,SiteId,UUID customerId,PaymentAccountId,SubscriptionBillingContext,Money,TaxMode,Map<String,String> technicalMetadata,Instant now): Payment` factory initializes dedicated source at CREATED with immutable context. Source checks explicit exhaustive switch. Existing factories/overloads retain old signatures and null subscription context.
- Produces payment callback v3: exact v1 fields plus `subscription_id`, `billing_record_id`, `customer_id`, `product_id`, `billing_kind`, `price_id`, `price_version`, `period_start`, `period_end`, with `schema_version=3`, `external_order_id=null`. Refund amounts retain existing PaymentEvent semantics. Existing outbox PaymentEvent FK remains valid; Task7 later adds pinned account column/lifecycle rows. No Subscription cash event is emitted using the context-less v1 payload.

- [ ] **1. RED: prorated amount не равна regular Price и остаётся refundable cash.** Add source-specific Payment test and positive/zero constraint test:

```java
@Test void subscriptionSourceIsExplicit() {
    assertThat(PaymentSource.valueOf("SUBSCRIPTION")).isEqualTo(PaymentSource.SUBSCRIPTION);
}
@Test void cashMoneyStillRejectsZero() {
    assertThatThrownBy(() -> Money.of(0, "EUR")).isInstanceOf(DomainValidationException.class);
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=SubscriptionBillingContextTest,SubscriptionPaymentIntegrationTest test`.
- [ ] **3. Add V16/context mapping and cash import.** Source SUBSCRIPTION requires customer, immutable context, no catalog snapshot/external order/donation fields; other source checks unchanged. Context copy included in every Payment mutation/factory/mapper and `createLocal` equality check. Provider mapping unique `(provider,account,providerPaymentReference)`; immutable reference cannot be replaced. On another actual provider object create another linked Payment, never reset terminal Payment. Cash import routes through common fact application used by webhook/reconcile, event dedup based actual object+fact, not ingress path.

```java
switch (source) {
    case CATALOG -> validateCatalog();
    case DYNAMIC -> validateDynamic();
    case DONATION -> validateDonation();
    case SUBSCRIPTION -> validateSubscription();
}
```

The four private validation methods hold the respective current constructor invariants; new `validateSubscription()` requires context/customer and excludes fields of the other three sources. Do not call prepare/confirm of a separate one-time Payment when importing a provider-owned invoice settlement.

- [ ] **4. Cover real provider lookup/refunds without Fake FK conflict.** Fake PaymentProviderAdapter retrieve/refund must recognize account-scoped `fake_subscription_cash_objects` as independent provider truth through a new `FakeSubscriptionCashLookupAdapter` in `B/provider/adapter/out/fake/`. Existing Fake refunds remain provider objects in existing store, with ownership checks. No synthetic foreign Payment object created to satisfy old Fake storage shape.

Before GREEN, add explicit source switch in Payment outbox snapshot: CATALOG/DYNAMIC→v1, DONATION→v2, SUBSCRIPTION→v3 with the immutable context above. Canonical payload and receiver validators accept exactly the v3 allowlist and positive money rules; preserve literal v1/v2 fixtures. Test cash import plus callback rollback atomically and linked refund v3 context. This belongs here, not after the cash service is usable.

```sql
create unique index uq_subscription_payment_object
 on payments(payment_account_id,provider_payment_reference)
 where source='SUBSCRIPTION' and provider_payment_reference is not null;
```

- [ ] **5. Run GREEN.** `./mvnw -Dtest=SubscriptionBillingContextTest,SubscriptionPaymentIntegrationTest,SubscriptionCashIntegrationTest,SubscriptionPaymentCallbackIntegrationTest,PaymentTest,PaymentConstraintsIntegrationTest,PaymentDonationPersistenceIntegrationTest,RefundOwnershipBoundaryTest test`; root `node --test infrastructure/callback-receiver.test.mjs`. Assertions: partial/full refund independent of Subscription; account A/B same object ref isolated; invoice+payment+reconcile same success creates one PaymentEvent; new object after terminal decline yields second Payment linked to same bill; trial/zero never invokes cash port. Record checkpoint.

## Task 6: Create operation, authorization и trial reservation

**Files:**

- Create: `B/subscriptions/application/model/SubscriptionActor.java`, `SubscriptionCommandContext.java`, `SubscriptionOperationResult.java`.
- Create: `B/subscriptions/application/port/in/CreateSubscriptionUseCase.java`; `B/subscriptions/application/port/out/SubscriptionAuthorityPort.java`, `SubscriptionCatalogPort.java`.
- Create: `B/subscriptions/application/service/CreateSubscriptionService.java`, `SubscriptionCommandTransaction.java`, `SubscriptionRuleErrors.java`; `B/subscriptions/adapter/out/integration/SubscriptionAuthorityAdapter.java`, `SubscriptionCatalogAdapter.java`; `B/subscriptions/adapter/in/configuration/SubscriptionApplicationConfiguration.java`.
- Modify: `B/customers/domain/CustomerSessionScope.java`, `B/customers/application/service/CustomerSessionService.java`, `B/partners/domain/PartnerScope.java`.
- Create: `B/customers/application/port/out/CustomerFeatureScopesPort.java`; `B/subscriptions/adapter/out/integration/CustomerSubscriptionScopesAdapter.java`.
- Create: `M/V17__subscription_scopes.sql`.
- Test: `T/subscriptions/application/service/CreateSubscriptionServiceTest.java`, `CreateSubscriptionIntegrationTest.java`, `SubscriptionAuthorityIntegrationTest.java`; modify existing CustomerSession scope tests.

**Interfaces:**

- Consumes: Tasks1–5; revalidated existing Customer/Partner/Admin authority; provider contract.
- Produces sealed `SubscriptionActor` nested records `Customer(CustomerSessionContext session)`, `Partner(AuthenticatedSite site)`, `Admin(AuthenticatedAdmin admin)`, using **application** AuthenticatedSite, not adapter principal.
- Produces `SubscriptionCommandContext(SubscriptionActor actor,SiteId siteId,CustomerId customerId,String origin,String idempotencyKey,String requestId)`; actor/raw customer IDs not browser request fields for Customer route.
- Produces `SubscriptionOperationResult(UUID operationId,UUID subscriptionId,SubscriptionOperation.Status status,SubscriptionProviderModels.NextAction nextAction)` with redacted secret-bearing representation.
- `CreateSubscriptionUseCase.create(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,PriceId priceId,boolean recurringConsent)`.
- `SubscriptionAuthorityPort.revalidate(SubscriptionCommandContext,boolean manage): void` ambient transaction mandatory; verifies current Site/Customer ACTIVE, original scopes/current key/session, exact Origin for Customer, no owner substitution.
- `SubscriptionCatalogPort.published(SiteId,PriceId): SubscriptionPriceTerms`, `publishedForProduct(SiteId,ProductId): List<SubscriptionPriceTerms>`; implementation calls Catalog application load ports and filters ownership/published status, not foreign repositories. `CustomerFeatureScopesPort.additionalScopes(SiteId): Set<CustomerSessionScope>` returns new scopes only when policy enabled.
- Transaction phase contract: `SubscriptionCommandTransaction.reserveCreate(CreateSubscriptionUseCase.Command): ReservedCreate`, nested `ReservedCreate(SubscriptionOperation operation,Subscription subscription,PaymentAccount account,boolean owner)`; `recordReply(UUID,Reply): SubscriptionOperationResult` does not grant access.
- `SubscriptionRuleErrors.translate(SubscriptionRuleViolation): PaymentApplicationException` maps STATE_CONFLICT to `SUBSCRIPTION_STATE_CONFLICT`409 and QUOTE_STALE to `SUBSCRIPTION_QUOTE_STALE`409 with fixed safe messages. Application services in Tasks8–10 catch/translate; domain never imports application/provider models or web exceptions.

```java
public static PaymentApplicationException translate(SubscriptionRuleViolation failure) {
    return switch (failure.reason()) {
        case STATE_CONFLICT -> new PaymentApplicationException("SUBSCRIPTION_STATE_CONFLICT",409,
            "Subscription action unavailable");
        case QUOTE_STALE -> new PaymentApplicationException("SUBSCRIPTION_QUOTE_STALE",409,
            "Preview again before changing plan");
    };
}
```

- [ ] **1. RED: stored old scopes не расширяются.**

```java
@Test void legacyScopeSetDoesNotGrantSubscriptionManagement() {
    var legacy = Set.of(CustomerSessionScope.CUSTOMER_READ,
        CustomerSessionScope.CHECKOUT_CREATE, CustomerSessionScope.IDENTITY_LINK);
    assertThat(legacy).doesNotContain(CustomerSessionScope.SUBSCRIPTION_MANAGE);
    assertThat(PartnerScope.parse("subscriptions:read")).isEqualTo(PartnerScope.SUBSCRIPTIONS_READ);
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=CreateSubscriptionServiceTest,SubscriptionAuthorityIntegrationTest test`.
- [ ] **3. Add enum/scopes migration and issuance policy.** New Customer scopes `SUBSCRIPTION_READ`, `SUBSCRIPTION_MANAGE`; Partner values `subscriptions:read`, `subscriptions:manage`. Widen CHECK allowlists via V17, never UPDATE stored scopes. New issuance unions existing defaults with enabled-Site additional scopes. Site без enabled policy сохраняет прежние scopes. Task13 запрещает выключение функции при non-terminal/UNKNOWN subscriptions: иначе после re-login Customer потеряет право даже на cancellation. Это не меняет уже выданные scopes и не подменяет per-action policy guards. Customer module depends only on its feature port.

```java
var issuedScopes = new HashSet<>(DEFAULT_SCOPES);
issuedScopes.addAll(features.additionalScopes(site.id()));
CustomerSession saved = sessions.save(CustomerSession.create(
    new CustomerSessionId(UUID.randomUUID()),site.id(),customer.id(),normalizedOrigin,
    digest.value(),digest.keyVersion(),Set.copyOf(issuedScopes),now,now.plus(ABSOLUTE_TTL)));
```

- [ ] **4. Implement durable reserve/call/observe phases.** Claim key scoped by Site+actor principal+operation, fingerprint canonical exact payload/Price/revision/policy; reserve Customer/Product slot and trial atomically. Operation saved before external create; deterministic provider key `subscription:<account UUID>:<operation UUID>:CREATE`. Provider call with no active DB transaction. Result records ACCEPTED/REQUIRES_ACTION/UNKNOWN/confirmed rejection; never grants paid/trial access from reply. Required trial first awaits setup: `FAKE_CONFIRM_SETUP` is a continuation of the original CREATE operation through its action endpoint, not a competing SETUP operation blocked by the unresolved CREATE. Optional trial consumes eligibility only when provider facts show actual start. Confirmed failure before start releases reservation; unknown retains it.

```java
public SubscriptionOperationResult create(CreateSubscriptionUseCase.Command command) {
    var reserved = transactions.reserveCreate(command);
    if (!reserved.owner()) return transactions.readResult(reserved.operation().id());
    var provider = providers.forAccount(reserved.account());
    var request = new SubscriptionProviderModels.Command(reserved.account(), reserved.operation(),
        reserved.subscription().billingPrice(), null, null, null, null, command.recurringConsent());
    SubscriptionProviderModels.Reply reply;
    try { reply = provider.create(request); }
    catch (RuntimeException failure) { reply = new SubscriptionProviderModels.Reply(
        SubscriptionProviderModels.Outcome.UNKNOWN, null, null); }
    return transactions.recordReply(reserved.operation().id(), reply);
}
```

Add `SubscriptionCommandTransaction.readResult(UUID): SubscriptionOperationResult`; it returns the stored safe operation state, while action capability is reissued only by the dedicated authorized provider-action read of Task13, never stored plaintext in operation.

- [ ] **5. Run GREEN.** `./mvnw -Dtest=CreateSubscriptionServiceTest,CreateSubscriptionIntegrationTest,SubscriptionAuthorityIntegrationTest,CustomerSessionServiceIntegrationTest,CatalogCheckoutServiceTest test`. Check no anonymous create, crossSite/customer hidden, recurring Price rejected by existing one-time checkout, two concurrent keys one live Subscription, two products okay, replay same ID, changed fingerprint409, provider call sees no ambient transaction, unknown survives replay/new key cannot bypass, required consent rejected if false, required/optional trial reservations. Record checkpoint.

## Task 7: Provider inbox, atomic settlement projection и callbacks

**Files:**

- Create: `B/webhooks/application/model/SubscriptionProviderEnvelope.java`; `B/webhooks/application/port/out/SubscriptionInboxPort.java`; `B/webhooks/application/service/SubscriptionInboxService.java`, `SubscriptionInboxWorker.java`; `B/webhooks/adapter/out/persistence/SubscriptionInboxPersistenceAdapter.java`.
- Create: `B/subscriptions/application/port/in/ApplySubscriptionSnapshotUseCase.java`; `B/subscriptions/application/service/SubscriptionFactService.java`; `B/subscriptions/application/port/out/SubscriptionCallbackPort.java`; `B/webhooks/adapter/out/persistence/SubscriptionCallbackPersistenceAdapter.java`.
- Modify: `B/payments/adapter/out/persistence/ProviderInboxPersistenceAdapter.java` (claims payment family only), `PaymentOutboxPersistenceAdapter.java`; `B/webhooks/adapter/out/persistence/CanonicalCallbackPayloadAdapter.java`, `OutboxPersistenceAdapter.java`; `B/payments/adapter/in/configuration/ProviderInboxConfiguration.java`, `B/subscriptions/adapter/in/configuration/SubscriptionApplicationConfiguration.java` for inbox worker wiring.
- Create: `M/V18__subscription_event_transport.sql`.
- Modify: `infrastructure/callback-receiver.mjs`, `infrastructure/callback-receiver.test.mjs`.
- Test: `T/subscriptions/application/service/SubscriptionFactsIntegrationTest.java`, `SubscriptionInboxIntegrationTest.java`; `T/webhooks/application/service/SubscriptionCallbackIntegrationTest.java`.

**Interfaces:**

- Consumes: provider Snapshot, account-scoped mappings, cash bridge, current Subscription and pending operation.
- Produces `ApplySubscriptionSnapshotUseCase.apply(Command): void`, nested `Command(PaymentAccount account,UUID subscriptionId,SubscriptionProviderModels.Snapshot snapshot,SubscriptionEvent.Source source,String factKey,String requestId)`; ambient transaction mandatory, no provider call inside.
- `SubscriptionProviderEnvelope(PaymentProviderType provider,PaymentAccountId accountId,String eventId,String objectReference,String eventType,Instant occurredAt,Instant receivedAt)` only hints, not raw payload. Allowed types `SUBSCRIPTION_CHANGED`, `SUBSCRIPTION_INVOICE_CHANGED`, `SUBSCRIPTION_SETUP_CHANGED`; handler retrieves authoritative snapshot outside transaction.
- `SubscriptionInboxPort` methods `insert(SubscriptionProviderEnvelope): boolean`, `claim(String owner,int batch,Instant now,Duration lease): List<Claim>`, `lock(Claim,Instant): SubscriptionProviderEnvelope`, `complete(Claim): void`, `fail(Claim,String category,Instant): void`; nested `Claim(UUID id,String owner)`.
- `SubscriptionInboxService.receive(SubscriptionProviderEnvelope): boolean`; `SubscriptionInboxWorker.runOnce(): void`.
- `SubscriptionCallbackPort.snapshot(Subscription,SubscriptionEvent,AccessDecision,Instant): void` ambient transaction mandatory.

- [ ] **1. RED: callback validator accepts v4 while preserving Task5 v3 and old v1/v2.** Receiver unit tests use literal fixtures; Java integration verifies transactional source rows.

```javascript
// Add to infrastructure/callback-receiver.test.mjs using its existing signed-request helper.
const lifecycle = {
  schema_version: 4, event_id: '11111111-1111-4111-8111-111111111111',
  event_type: 'subscription.updated', site_id: '22222222-2222-4222-8222-222222222222',
  subscription_id: '33333333-3333-4333-8333-333333333333',
  customer_id: '44444444-4444-4444-8444-444444444444',
  product_id: '55555555-5555-4555-8555-555555555555', revision: 2,
  state: 'TRIALING', access_allowed: true,
  access_valid_until: '2026-10-11T12:05:00Z', occurred_at: '2026-10-11T12:00:00Z',
  billing_record_id: null, payment_id: null
};
```

Assert valid signed v4 yields204; old v1/v2 fixtures remain204; extra secret field400; bad signature401. Test request helper remains existing test's helper, not a new SDK API.

- [ ] **2. Run RED.** Root: `node --test infrastructure/callback-receiver.test.mjs`; backend: `./mvnw -Dtest=SubscriptionInboxIntegrationTest,SubscriptionFactsIntegrationTest,SubscriptionCallbackIntegrationTest test`.
- [ ] **3. Add transport migration without fake Payment references.** Reuse existing `provider_inbox` unique triple; add `event_family` default PAYMENT, family-aware CHECK and separate workers' claim filters. Subscription adapter reads safe hint envelope; old `ProviderEventEnvelope` and money enum stay narrow. Existing payment claims must exclude SUBSCRIPTION before any envelope decode. Fake queue enters the same inbox, not a browser ingress. Add `outbox_events.source_kind` default PAYMENT, nullable `payment_id`, nullable `subscription_id`, `subscription_event_id`, `payment_account_id`. Backfill account from historical Payment, not current Site account; old payload unchanged. Payment source FK remains for PAYMENT; lifecycle FK and CHECK enforce SUBSCRIPTION source; mutation trigger includes all new source fields.

```sql
alter table outbox_events alter column payment_id drop not null;
-- Enforced after new source columns/FKs are installed:
-- PAYMENT: payment_id not null, subscription_event_id null.
-- SUBSCRIPTION: subscription_id/subscription_event_id not null, payment_id null.
```

- [ ] **4. Implement atomic application and exact callback schemas.** Snapshot validated for pinned account/Subscription/mapping. Lock and revalidate current operation before modifying state; stale/out-of-order hints retrieve actual provider state. Record billing record, cash objects, entitlement, trial consumption, lifecycle journal and callback in one transaction. Re-observation without new fact appends nothing. Terminal cancel still imports late cash, never grants entitlement. Unknown settlement records `RECONCILIATION_REQUIRED`, no payment success. Crash before commit rolls all projections/journals/outbox back; after commit retry dedups.

Confirmed ZERO settlement is allowed for free-trial invoices and rounded-zero conditional proration only; it does not silently mark a positive first-paid/renewal regular period as funded. Such unexpected zero settlement requires reconciliation and no paid grant. A valid zero proration applies the confirmed target without a Payment. Update existing `PaymentOutboxPersistenceAdapter` INSERTs to set the immutable pinned `payment_account_id` for every source, not just subscriptions.

```java
// SubscriptionFactService: one transaction; Cash records are imported before any access grant.
for (var invoice : command.snapshot().invoices()) {
    BillingRecord bill = billing.upsert(mapInvoice(command, invoice));
    for (var object : invoice.cashObjects()) cash.record(cashCommand(command, bill, object));
}
// mapInvoice(Command,Invoice): BillingRecord and cashCommand(Command,BillingRecord,CashObject):
// RecordSubscriptionCashUseCase.Command are private pure mapping methods in this service.
```

Actual payment attempts are imported even while invoice settlement is OPEN, preserving decline/action/processing history. Import never fabricates success from invoice status; access transitions additionally require supported confirmed settlement and current provider state. Verify failed object → new successful object yields two linked Payments but one success for the confirmed object; zero invoice yields none.

**v3 payment callback:** existing v1 fields plus `subscription_id`, `billing_record_id`, `customer_id`, `product_id`, `billing_kind`, `price_id`, `price_version`, `period_start`, `period_end`; schema_version3 and `external_order_id=null`. `amount_minor/currency` retain existing event semantics, including refunds. Context is immutable from Payment, not current Subscription. No provider invoice string required in external callback.

**v4 lifecycle callback:** exact fields shown in RED fixture. `event_type` is `subscription.updated` or `subscription.access_changed`; state is safe display state; optional IDs null if irrelevant. `revision` monotone local projection revision. `access_valid_until` obeys5min cap evaluated when snapshot emitted; delivery never extends expiry. Backend consumer refreshes Partner state rather than treating delayed callback as fresh grant.

Outbox provider telemetry joins pinned `outbox_events.payment_account_id`, not `sites.payment_account_id`. Keep existing signer/secret rotation/retries/DEAD/manual replay, no duplicate transport engine. `CanonicalCallbackPayloadAdapter` selects exact allowlist by version, rejects unknown nested fields and oversized16KiB body. SubscriptionCash import and ordinary payment event both use one dedup path; they cannot emit two success callbacks.

- [ ] **5. Run GREEN.** Backend focused tests plus `DonationCallbackIntegrationTest`, `CallbackDeliveryServiceIntegrationTest`, `PendingOutboxSingleConnectionIntegrationTest`, `ProviderInboxServiceIntegrationTest`; root receiver tests. Account IDs reused across accounts stay isolated. Test timeout+reconcile, duplicate invoice/payment views, swapped ordering, rollback failpoint, lease loss, same fact after restart, callback rotation/dead/replay, v1/v2 snapshots unchanged, Site account change does not change routing/account provenance. Record checkpoint.

## Task 8: Trial завершение, renewal, grace и recovery

**Files:**

- Create: `B/subscriptions/domain/RecoveryRules.java`; `B/subscriptions/application/port/in/RecoverSubscriptionUseCase.java`, `ResumeSubscriptionUseCase.java`; `B/subscriptions/application/service/SubscriptionRecoveryService.java`, `SubscriptionRecoveryTransaction.java`.
- Modify: `B/subscriptions/application/service/SubscriptionFactService.java`, `B/provider/adapter/out/fake/FakeSubscriptionStateAdapter.java`.
- Test: `T/subscriptions/domain/RecoveryRulesTest.java`, `T/subscriptions/application/service/SubscriptionRenewalIntegrationTest.java`, `SubscriptionTrialIntegrationTest.java`, `SubscriptionRecoveryIntegrationTest.java`.

**Interfaces:**

- Consumes: confirmed provider Snapshot/invoice/cash objects, existing debt and fixed policy snapshot; `SubscriptionAuthorityPort.revalidate`.
- Produces: `RecoverSubscriptionUseCase.recover(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId,UUID billingRecordId)`; `ResumeSubscriptionUseCase.resume(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId,boolean recurringConsent)`.
- Produces rules `RecoveryRules.requireRecoverable(Subscription,BillingRecord,boolean liveAttempt): void`, `requireResumable(Subscription,boolean methodReady): void`.
- `SubscriptionRecoveryTransaction.reserveRecover(RecoverSubscriptionUseCase.Command): SubscriptionOperation`, `reserveResume(ResumeSubscriptionUseCase.Command): SubscriptionOperation`, `recordReply(UUID,Reply): SubscriptionOperationResult`.

- [ ] **1. RED: optional trial без метода не становится долгом.** Добавить Cucumber fixture-independent unit case через Task2 factory:

```java
@Test void cancelledSubscriptionCannotResume() {
    Instant end = Instant.parse("2026-10-18T12:00:00Z");
    var active = SubscriptionFixtures.active(end.minusSeconds(86400), end);
    var cancelled = active.cancel("CUSTOMER_REQUEST", end.minusSeconds(1));
    assertThatThrownBy(() -> RecoveryRules.requireResumable(cancelled, true))
        .isInstanceOf(SubscriptionRuleViolation.class);
    assertThat(new SubscriptionAccessPolicy().evaluate(cancelled, end).allowed()).isFalse();
}
```

Integration test arranges optional trial/provider method absent, advances Fake to trial end, processes inbox; asserts PAUSED, zero new billing debt, zero new Payments, access denied. Adding method leaves PAUSED until explicit resume.

- [ ] **2. Run RED.** `./mvnw -Dtest=RecoveryRulesTest,SubscriptionTrialIntegrationTest,SubscriptionRenewalIntegrationTest,SubscriptionRecoveryIntegrationTest test`.
- [ ] **3. Implement fact transitions and recovery phases.** Previous paid period end is sole grace anchor. Same unpaid billing record keeps its captured policyVersion/graceDeadline, regardless of duplicate/late event or later Site setting. During failed scheduled change set billingPrice target but retain entitledPrice old; paid confirmation sets target entitlement only for its actual current period. No grace for initial or first invoice after trial without prior paid period. Required trial waits confirmed method+consent; actual trial start consumes reservation. Trial ends without method -> PAUSED, not PAID/PAST_DUE. No new trial on resume.

```java
public static void requireResumable(Subscription s, boolean methodReady) {
    if (s.billingStatus() != Subscription.Status.PAUSED || !methodReady || s.pendingChange() != null)
        throw new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.STATE_CONFLICT,"Resume unavailable");
}
public static void requireRecoverable(Subscription s, BillingRecord bill, boolean liveAttempt) {
    if (!s.nonTerminal() || s.debt() == null || !s.debt().billingRecordId().equals(bill.id())
            || bill.settlement() != BillingRecord.Settlement.OPEN || liveAttempt)
        throw new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.STATE_CONFLICT,"Recovery unavailable");
}
```

- [ ] **4. Use retrieve before recovery and recheck after retrieve.** Read provider existing invoice/attempt outside transaction, then reserve under current row locks/revision; reject unresolved prior attempt/new key. Provider recovery targets existing reference, not one-time preparePayment. Resume starts new provider-defined period with no pause backbilling. Payment success for old historical period is recorded but cannot grant outside its end; terminal remains terminal. Decline/action/unknown do not extend paidThrough. Provider snapshots must establish both settlement and current status; setup success alone insufficient.

```gherkin
Scenario: Grace expires while renewal retries continue
  Given a paid subscription ends at "2026-10-31T10:00:00Z"
  And its captured renewal grace is 3 UTC days
  When renewal fails twice for the same invoice
  Then the grace deadline is "2026-11-03T10:00:00Z"
  When platform time is "2026-11-03T10:00:00Z" without running deadline jobs
  Then product access is denied
  And the customer can still read history and request invoice recovery
```

- [ ] **5. Run GREEN.** Focused tests from RED plus `SubscriptionCashIntegrationTest`, `SubscriptionAccessPolicyTest`. Exercise monthly/yearly separate invoices, successful first payment/renewal, zero grace, late failure, posttrial failure no grace, retry same object/new object, unknown resume/recover, recovered current/expired periods, unpaid terminal finish eligibility. Record checkpoint.

## Task 9: Preview и безопасная смена тарифа

**Files:**

- Create: `B/subscriptions/domain/PlanChangeRules.java`, `SubscriptionQuote.java`; `B/subscriptions/application/port/out/SubscriptionQuoteStore.java`; `B/subscriptions/adapter/out/persistence/SubscriptionQuotePersistenceAdapter.java`.
- Create: `B/subscriptions/application/port/in/PreviewSubscriptionChangeUseCase.java`, `ChangeSubscriptionPlanUseCase.java`, `ClearSubscriptionChangeUseCase.java`; `B/subscriptions/application/service/SubscriptionPlanService.java`, `SubscriptionPlanTransaction.java`.
- Reuse: `subscription_quotes` created by Task3 V14; do not edit applied migrations.
- Test: `T/subscriptions/domain/PlanChangeRulesTest.java`, `T/subscriptions/application/service/SubscriptionPlanIntegrationTest.java`, `SubscriptionQuoteIntegrationTest.java`.

**Interfaces:**

- Consumes: explicit PlanTransition, provider quote/capabilities, current paid Subscription, policy.
- Produces domain `SubscriptionQuote(UUID id,SiteId siteId,UUID subscriptionId,String authorityScope,PriceId fromPriceId,int fromPriceVersion,SubscriptionPriceTerms target,ChangeTiming timing,long dueNowMinor,long nextAmountMinor,String currency,Instant effectiveAt,Instant expiresAt,boolean taxFinal,long subscriptionRevision,long policyVersion,String providerQuoteReference)`; `authorityScope` is a safe digest, never serialized in Customer DTO. `ChangeTiming` is the Task2 domain enum, imported consistently, not a second enum.
- `SubscriptionQuoteStore.save(SubscriptionQuote): void`, `lock(SiteId,UUID): SubscriptionQuote`.
- `PreviewSubscriptionChangeUseCase.preview(Query): SubscriptionQuote`, nested `Query(SubscriptionCommandContext context,UUID subscriptionId,PriceId targetPriceId,ChangeTiming timing)`; read-like preview does not charge or mutate Subscription. It validates management authority but needs no financial Idempotency-Key; stored quote ID is not a billing operation.
- `ChangeSubscriptionPlanUseCase.change(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId,UUID quoteId)`; `ClearSubscriptionChangeUseCase.clear(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId)`.
- Rules `PlanChangeRules.requireQuoteCurrent(SubscriptionQuote,long revision,long policyVersion,Instant now): void`, `requireChangeable(Subscription,boolean unresolved): void`, `allowedTiming(PlanTransition,SubscriptionPriceTerms current,SubscriptionPriceTerms target,SubscriptionPolicy,boolean conditionalUpdate): Set<ChangeTiming>`; application extracts the capability boolean, domain does not import provider models.

- [ ] **1. RED: устаревший quote нельзя применить молча.**

```java
@Test void expiredQuoteAndChangedPolicyAreRejected() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    var s = SubscriptionFixtures.active(now, now.plusSeconds(86400));
    var q = new SubscriptionQuote(UUID.randomUUID(), s.siteId(), s.id(), "test-authority-digest",
        s.billingPrice().priceId(), s.billingPrice().version(), s.billingPrice(),
        ChangeTiming.IMMEDIATE, 100, 2000, "EUR", now, now.plusSeconds(60),
        false, s.revision(), 4, "fake_quote_1");
    assertThatThrownBy(() -> PlanChangeRules.requireQuoteCurrent(q,s.revision(),5,now))
        .isInstanceOf(SubscriptionRuleViolation.class);
    assertThatThrownBy(() -> PlanChangeRules.requireQuoteCurrent(q,s.revision(),4,now.plusSeconds(60)))
        .isInstanceOf(SubscriptionRuleViolation.class);
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=PlanChangeRulesTest,SubscriptionQuoteIntegrationTest test`.
- [ ] **3. Implement quote validation and durable apply.** Persist exact current/target IDs+versions, provider quote ref, captured revision/policy, dueNow/next/currency/taxFinal. Quote expiry max5min, no secrets; apply checks target still published/allowed, policy/revision/expiry unchanged, same Site/Product/currency. Re-read provider quote and compare amount/effective conditions before sending mutation; changed total requires fresh preview, never silently larger charge. Publishing another version does not substitute target ID; archiving/disallowing quoted target rejects. Quote scope also binds actor/Customer, stored as safe authority digest, not bearer.

```java
public static void requireQuoteCurrent(SubscriptionQuote q,long revision,long policyVersion,Instant now) {
    if (q.subscriptionRevision() != revision || q.policyVersion() != policyVersion
            || !now.isBefore(q.expiresAt()))
        throw new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.QUOTE_STALE,
            "Preview again before changing plan");
}
```

- [ ] **4. Implement contingent/scheduled transitions.** Immediate only explicitly configured upgrade, same interval, provider conditionalUpdate, positive/zero dueNow. Negative dueNow returns NEXT_PERIOD alternative, no credit/refund. Pending immediate keeps billing/entitled old until confirmed applied+cash or confirmed zero change; merely invoice paid unsupported settlement does not apply. Scheduled stores exact target and operation; next unpaid period keeps old grace entitlement. Only one pending change; new key never overwrites UNKNOWN. Clear requires provider confirmation; unresolved operation cannot be replaced by clear pretending rollback.

```gherkin
Scenario: Scheduled downgrade is not prepaid
  Given a paid Pro subscription with a scheduled Basic price change
  When the provider starts the Basic period but its invoice fails
  Then billing price is Basic and entitled price remains Pro during fixed grace
  When grace expires without payment
  Then access is denied
  When that current invoice is confirmed paid
  Then entitlement becomes the exact scheduled Basic price version
```

- [ ] **5. Run GREEN.** `./mvnw -Dtest=PlanChangeRulesTest,SubscriptionPlanIntegrationTest,SubscriptionQuoteIntegrationTest,SubscriptionFactsIntegrationTest test`. Cases: same-interval upgrade success/decline/action/unknown, rounded zero with no Payment, negative forbidden immediate, month/year always deferred, pending replacement rejected, explicit clear, trial/pastDue/paused/unpaid/cancelled/scheduledCancel denied, changed policy/revision/amount/expired quote rejected, newer published Price never silently selected. Record checkpoint.

## Task 10: Scheduled и immediate cancellation без финансовых побочных эффектов

**Files:**

- Create: `B/subscriptions/domain/SubscriptionCancellationRules.java`; `B/subscriptions/application/port/in/CancelSubscriptionUseCase.java`, `WithdrawSubscriptionCancellationUseCase.java`; `B/subscriptions/application/service/SubscriptionCancellationService.java`, `SubscriptionCancellationTransaction.java`.
- Modify: `B/subscriptions/application/service/SubscriptionFactService.java`, `B/provider/adapter/out/fake/FakeSubscriptionStateAdapter.java`.
- Test: `T/subscriptions/domain/SubscriptionCancellationRulesTest.java`, `T/subscriptions/application/service/SubscriptionCancellationIntegrationTest.java`, `SubscriptionCancellationRaceIntegrationTest.java`.

**Interfaces:**

- Consumes: live paid/trial/access boundary, Site policy, safeImmediateCancel capability, one pending mutation constraint.
- Produces `CancelSubscriptionUseCase.cancel(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId,Mode mode,UUID confirmation)`, `Mode { PERIOD_END, IMMEDIATE }`.
- `WithdrawSubscriptionCancellationUseCase.withdraw(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId)`.
- `SubscriptionCancellationRules.allowImmediate(Subscription,SubscriptionPolicy,Instant): boolean`, `requireWithdrawable(Subscription,Instant): void`.

- [ ] **1. RED: terminal не оживает от поздней оплаты.**

```java
@Test void confirmedCancellationCannotBeResurrectedByPaidPeriod() {
    Instant now = Instant.parse("2026-10-11T12:00:00Z");
    var s = SubscriptionFixtures.active(now, now.plusSeconds(86400));
    var terminal = s.cancel("CUSTOMER_REQUEST", now);
    var late = terminal.confirmPaid(s.billingPrice(), s.currentPeriod(), now.plusSeconds(10));
    assertThat(late.billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
    assertThat(new SubscriptionAccessPolicy().evaluate(late, now.plusSeconds(10)).allowed()).isFalse();
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=SubscriptionCancellationRulesTest,SubscriptionCancellationRaceIntegrationTest test`.
- [ ] **3. Implement separate permission for finishing inactive states.** Paid/trial immediate requires Site allow+exact Subscription UUID confirmation+provider safe capability. PAUSED/UNPAID/INCOMPLETE without live paid/trial can explicitly finish even Site flag false. PAST_DUE is not silently included in this exception; normal policy and provider safe semantics apply. Do not infer cancellation from command accepted/timeout. Pending plan change must first be explicitly cleared/settled, not hidden by cancel.

```java
public static boolean allowImmediate(Subscription s,SubscriptionPolicy p,Instant now) {
    boolean livePaid = s.paidThrough() != null && now.isBefore(s.paidThrough());
    boolean liveTrial = s.trial() != null && now.isBefore(s.trial().end());
    boolean inactiveFinish = Set.of(Subscription.Status.PAUSED,Subscription.Status.UNPAID,
        Subscription.Status.INCOMPLETE).contains(s.billingStatus()) && !livePaid && !liveTrial;
    return inactiveFinish || p.allowImmediateCancellation();
}
```

- [ ] **4. Implement period end/withdraw/race handling.** PERIOD_END effective date comes from confirmed current paid/trial boundary. Withdrawal only before effectiveAt and provider terminal confirmation. Immediate request preserves access until confirmed terminal fact, then closes access and releases live Product slot. Cancel does not call refund/cash creation; retains overdue invoice and money history. Late cash records real PaymentEvent but not active status; a new subscription after old cancellation is unaffected.

```gherkin
Scenario: Renewal success arrives after cancellation and resubscribe
  Given the old subscription was confirmed cancelled
  And the customer created a new subscription for the same product
  When the old renewal invoice is reported paid
  Then the old payment is recorded once under the old subscription
  And the old subscription remains cancelled
  And the new subscription revision and entitlement do not change
  And no automatic refund or extra final charge is created
```

- [ ] **5. Run GREEN.** `./mvnw -Dtest=SubscriptionCancellationRulesTest,SubscriptionCancellationIntegrationTest,SubscriptionCancellationRaceIntegrationTest,SubscriptionAnonymizationIntegrationTest test`. Include setup/change/recovery competing keys, unknown cancel retry, scheduledcancel still blocks anonymization, paused finish frees slot only after confirmation, partial/full refund keeps subscription active. Record checkpoint.

## Task 11: Per-subscription method setup и optional Fake Portal

**Files:**

- Create: `B/subscriptions/application/port/in/SetupSubscriptionMethodUseCase.java`, `GetSubscriptionActionUseCase.java`, `ConfirmSubscriptionActionUseCase.java`, `CreateSubscriptionPortalUseCase.java`; `B/subscriptions/application/service/SubscriptionMethodService.java`, `SubscriptionActionService.java`, `SubscriptionPortalService.java`.
- Create: `B/subscriptions/application/port/out/SubscriptionActionStore.java`, `SubscriptionPortalStore.java`; `B/subscriptions/adapter/out/persistence/SubscriptionActionPersistenceAdapter.java`, `SubscriptionPortalPersistenceAdapter.java`.
- Modify: `B/provider/adapter/out/fake/FakeSubscriptionProviderAdapter.java`, `FakeSubscriptionStateAdapter.java`, `B/subscriptions/application/port/out/SubscriptionProviderPort.java`.
- Reuse: Task3 V14 `subscription_action_capabilities` and `subscription_portal_sessions`; no migration edits here.
- Test: `T/subscriptions/application/service/SubscriptionMethodIntegrationTest.java`, `SubscriptionActionIntegrationTest.java`, `SubscriptionPortalIntegrationTest.java`.

**Interfaces:**

- Consumes: Task4 setup/action/Portal contracts, scoped authority and explicit recurring consent.
- `SetupSubscriptionMethodUseCase.setup(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId,boolean recurringConsent)`.
- `GetSubscriptionActionUseCase.get(Query): SubscriptionProviderModels.NextAction`, nested `Query(SubscriptionCommandContext context,UUID subscriptionId,UUID operationId)`.
- `ConfirmSubscriptionActionUseCase.confirm(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId,UUID operationId,String actionToken)` redacted toString. Local Fake confirm only; no PAN/CVC or arbitrary provider event parameters.
- Implements Task4 provider methods `nextAction(Lookup): NextAction`, `confirmAction(Command,String actionToken): Reply`; no action secret stored in domain/operation/events.
- `SubscriptionActionStore.issue(SiteId,CustomerId,PaymentAccountId,UUID subscriptionId,UUID operationId,String purpose,String tokenDigest,Instant expiresAt): void`, `consume(SiteId,CustomerId,PaymentAccountId,UUID subscriptionId,UUID operationId,String purpose,String tokenDigest,Instant now): boolean` with atomic single-use check; issued TTL max10min, purpose+operation+account binding, retry resolves original provider receipt. Raw token only response/memory.
- `SubscriptionPortalStore.save(SiteId,CustomerId,UUID subscriptionId,UUID publicOperationId,Instant expiresAt): void`, `valid(SiteId,CustomerId,UUID subscriptionId,UUID publicOperationId,Instant now): boolean`; owner+expiry checked server-side, operation ID is not a capability.
- `CreateSubscriptionPortalUseCase.create(Command): PortalNavigation`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId,String returnUrl)`.
- `SubscriptionPortalService.requireAllowedReturn(SubscriptionPolicy,String): String` exact allowlist match, not prefix/substring.

- [ ] **1. RED: Portal allowlist — exact match.**

```java
@Test void portalReturnUrlCannotEscapeAllowlist() {
    var p = new SubscriptionPolicy(new SiteId(UUID.randomUUID()),1,true,3,false,true,
        false,Set.of("https://shop.example/account"));
    assertThat(SubscriptionPortalService.requireAllowedReturn(p,"https://shop.example/account"))
        .isEqualTo("https://shop.example/account");
    assertThatThrownBy(() -> SubscriptionPortalService.requireAllowedReturn(p,
        "https://shop.example/account.evil")) .isInstanceOf(PaymentApplicationException.class);
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=SubscriptionMethodIntegrationTest,SubscriptionActionIntegrationTest,SubscriptionPortalIntegrationTest test`.
- [ ] **3. Implement setup capability and consent.** Persist only digest/purpose/scope/expiry; response tokens and navigation URLs use redacted toString. Customer action GET revalidates ownership and reissues short-lived authorized action rather than replaying plaintext secret from idempotency storage. Consuming Fake action calls same provider boundary and inbox processing; browser action accepted is not success. Setup changes only target subscription default method; no other product's default is touched. Consent captured as purpose+boolean+policy/version+timestamp, no legal text claim.

Action confirmation is a continuation of its original CREATE/SETUP/CHANGE/RECOVER/RESUME operation, not a competing mutation. Store the confirmation's authority-scoped idempotency key digest and safe fingerprint with its capability record; exact retry resolves the original operation/provider receipt, changed fingerprint rejects409. Persist claim/consume before the provider call, call outside the transaction, revalidate before recording reply. A crash/timeout preserves a resolvable original operation; it never enables a fresh financial POST. GET action issuance uses no-store and never creates a new charge operation.

```java
public static String requireAllowedReturn(SubscriptionPolicy policy,String url) {
    if (!policy.portalEnabled() || url == null || !policy.portalReturnUrls().contains(url))
        throw new PaymentApplicationException("SUBSCRIPTION_PORTAL_UNAVAILABLE",409,"Portal unavailable");
    return url;
}
```

- [ ] **4. Implement Fake Portal as clearly labelled same-use-case UI.** Navigation returns to the exactly allowlisted site/demo manager URL with a public operation UUID, not a bearer/action token in URL; Customer authentication and ownership are required after navigation. Portal session stores owner, public operation ID and expiry; URL alone grants no authority. Route is `frontend/admin`-independent customer/demo navigation, never arbitrary HTML provider payload. Fake Portal can navigate to manager actions (setup/cancel/return), but invokes their authorized APIs, no privileged Fake controls. Return triggers fresh read; provider reconciliation remains the backend worker's job, not a grant from browser return. Portal disabled default; operation/session expiry does not block future mutations indefinitely. Unknown setup remains resolvable; adding method does not pay debt/resume PAUSED.

```gherkin
Scenario: Method update is not invoice recovery
  Given product A is past due and product B has another active subscription
  When the customer completes method setup for product A
  Then A still requires an explicit invoice recovery command
  And B payment method does not change
  And no new PaymentEvent reports a charge
```

- [ ] **5. Run GREEN.** Focused tests; crossSite/customer action token denied, wrong purpose/operation/account denied, expired/single-use token, duplicate confirmation same provider receipt, consent false denied, logs/events/storage secret-free, Portal allowlist disabled/open redirect/return-only, setup does not restore grace or consume repeat trial. Record checkpoint.

## Task 12: Bounded reconciliation, deadlines, account guard и telemetry

**Files:**

- Create: `B/subscriptions/application/port/in/ReconcileSubscriptionUseCase.java`; `B/subscriptions/application/port/out/SubscriptionSweepPort.java`, `SubscriptionTelemetryPort.java`; `B/subscriptions/application/service/SubscriptionReconciliationService.java`, `SubscriptionSweepWorker.java`, `SubscriptionDeadlineWorker.java`.
- Create: `B/subscriptions/adapter/out/persistence/SubscriptionSweepPersistenceAdapter.java`; `B/subscriptions/adapter/out/observability/MicrometerSubscriptionTelemetryAdapter.java`; `B/subscriptions/adapter/in/configuration/SubscriptionScheduler.java`, `SubscriptionProperties.java`.
- Create: `B/paymentaccounts/application/port/out/PaymentAccountBillingGuardPort.java`; `B/subscriptions/adapter/out/integration/PaymentAccountSubscriptionGuardAdapter.java`.
- Modify: `B/paymentaccounts/adapter/out/persistence/PaymentAccountPersistenceAdapter.java`; existing account save is the available status mutation boundary. Enforce guard on ACTIVE→inactive in a transaction under Task3 account row lock, without dependency on subscription repositories. Do not introduce a destructive endpoint.
- Test: `T/subscriptions/application/service/SubscriptionReconciliationIntegrationTest.java`, `SubscriptionDeadlineIntegrationTest.java`, `SubscriptionSweepIntegrationTest.java`; `T/subscriptions/adapter/out/observability/SubscriptionObservabilityIntegrationTest.java`; `T/paymentaccounts/application/service/SubscriptionAccountGuardTest.java`.

**Interfaces:**

- `ReconcileSubscriptionUseCase.reconcile(Command): SubscriptionOperationResult`, nested `Command(SubscriptionCommandContext context,UUID subscriptionId)`.
- `SubscriptionSweepPort.claim(String owner,int batch,Instant now,Duration lease): List<Claim>`, nested `Claim(SiteId siteId,UUID subscriptionId,String owner,UUID leaseToken,long revision)`; `finish(Claim,Instant nextCheck,boolean succeeded): void`; `expiredGrace(int batch,Instant now): List<Claim>`. Each claim receives a fresh leaseToken, including reclaim by the same worker; stale owners cannot finish a replacement lease.
- `SubscriptionReconciliationService.reconcileClaim(Claim): void` retrieves outside transaction and applies Task7 fact port under lease/revision check. `SubscriptionSweepWorker.runOnce()`; `SubscriptionDeadlineWorker.runOnce()`.
- `PaymentAccountBillingGuardPort.requireMutable(PaymentAccountId): void` blocks disabling/removing account with live subscriptions/unresolved create/cancel; pinned old account still available after Site switches.
- `SubscriptionTelemetryPort.record(Operation,Outcome): void`, `observeUnknownAge(Duration): void`, `observeInboxLag(Duration): void`; bounded enums `Operation { CREATE, CHANGE, CANCEL, SETUP, RECOVER, RESUME, RECONCILE, DEADLINE, CALLBACK }`, `Outcome { SUCCESS, DECLINED, UNKNOWN, REJECTED, NO_CHANGE }`.

- [ ] **1. RED: deadline writes only one access suspension.** Clock-only access test already exists; new integration test runs worker twice and asserts one event/outbox and no provider cancel/payment call.

```java
@Test void unknownAgeMetricsHaveNoObjectIdentifierLabels() {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    var telemetry = new MicrometerSubscriptionTelemetryAdapter(registry);
    telemetry.record(SubscriptionTelemetryPort.Operation.RECONCILE,
        SubscriptionTelemetryPort.Outcome.UNKNOWN);
    assertThat(registry.getMeters()).allSatisfy(meter ->
        assertThat(meter.getId().getTags()).allSatisfy(tag ->
            assertThat(Set.of("operation","outcome")).contains(tag.getKey())));
}
```

- [ ] **2. Run RED.** `./mvnw -Dtest=SubscriptionReconciliationIntegrationTest,SubscriptionDeadlineIntegrationTest,SubscriptionObservabilityIntegrationTest,SubscriptionAccountGuardTest test`.
- [ ] **3. Implement bounded workers.** Defaults batch20/max100, lease30s/range1–600s, fixed sweep delay30s, capped backoff60s→300s→1800s→7200s; unknown/stale priority, live lease owner+token and revision checked before apply/finish. Repeated no-change read no lifecycle event. Use pinned account from Subscription, not current Site config. Manual reconciliation requires idempotency/admin or entitled authority, not browser mark-paid. No second platform billing timer; deadlines only access facts.

```sql
select id from subscriptions
 where next_reconcile_at <= :now
   and (reconcile_lease_until is null or reconcile_lease_until <= :now)
 order by next_reconcile_at,id
 limit :batch for update skip locked;
```

- [ ] **4. Protect expired grace once and account lifecycle.** Deadline fact key `grace-expired:<billingRecord UUID>`; re-evaluate under lock, don't suspend a subscription already recovered, cancelled or moved to another debt. Persist safe lease/backoff fields reserved in V14. Account update guard shares account row serialization with create; create validates ACTIVE account while its lock is held. Existing reads/refunds/recovery retain pinned account capability. Do not solve account switch by migrating subscriptions or rewriting Payment references.

```gherkin
Scenario: Old account remains responsible after Site routing changes
  Given a subscription was created using account A
  When its Site selects account B for new purchases
  Then renewal recovery and reconciliation still use account A
  And disabling account A is rejected while this subscription is non-terminal
```

- [ ] **5. Run GREEN.** Focused tests plus `SubscriptionSweepIntegrationTest`; two workers/lease expiry/backoff, late callback plus reconcile, unknown create/setup/change/cancel/recovery after restart, no-op no events, deadline race with payment, default/config bounds, negative log assertions for token/email/URL/provider body, metrics labels bounded, old account guard atomic with create. Record checkpoint.

## Task 13: Customer, Partner и Admin transport и OpenAPI

**Files:**

- Create: `B/subscriptions/application/port/in/QuerySubscriptionsUseCase.java`, `ManageSubscriptionPoliciesUseCase.java`; `B/subscriptions/application/model/SubscriptionView.java`, `SubscriptionHistory.java`, `SubscriptionOffer.java`, `SubscriptionBillingView.java`; `B/subscriptions/application/service/SubscriptionQueryService.java`, `SubscriptionPolicyService.java`.
- Create: `B/subscriptions/application/port/out/SubscriptionReadPort.java`; `B/subscriptions/adapter/out/persistence/SubscriptionReadPersistenceAdapter.java` for bounded cursor pages and safe linked-payment reads.
- Create: `B/subscriptions/adapter/in/web/CustomerSubscriptionController.java`, `PartnerSubscriptionController.java`, `AdminSubscriptionController.java`, `SubscriptionPolicyController.java`, `SubscriptionTransport.java`, `SubscriptionRequestPolicyFilter.java`; `B/subscriptions/adapter/in/configuration/SubscriptionOpenApiConfiguration.java`.
- Modify: `B/admin/adapter/in/web/SecurityConfiguration.java`, `B/customers/adapter/in/web/CustomerSessionAuthenticationFilter.java`, `B/payments/adapter/in/web/DynamicSiteCorsConfigurationSource.java`, `PublicRateLimitFilter.java`, `B/partners/adapter/in/web/PartnerAuthenticationFilter.java` only for explicit new routes/limits.
- Modify: `B/payments/application/service/CustomerPaymentHistoryService.java`, `B/payments/application/model/CustomerPaymentHistory.java` to explicitly accept SUBSCRIPTION with `catalog=null`; no change to existing history field set.
- Test: `T/subscriptions/adapter/in/web/CustomerSubscriptionApiIntegrationTest.java`, `PartnerSubscriptionApiIntegrationTest.java`, `AdminSubscriptionApiIntegrationTest.java`, `SubscriptionOpenApiIntegrationTest.java`, `SubscriptionRequestPolicyIntegrationTest.java`.
- Modify: `frontend/admin/src/lib/api/schema.d.ts` by generator, not manual editing.

**Interfaces:**

- `QuerySubscriptionsUseCase.list(Query): Page`, nested `Query(SubscriptionActor actor,SiteId siteId,CustomerId customerId,ProductId productId,String state,int limit,String cursor)`, `Page(List<SubscriptionView> items,String nextCursor)`, `BillingPage(List<SubscriptionBillingView> items,String nextCursor)`; `get(SubscriptionActor,SiteId,UUID): SubscriptionView`; `history(SubscriptionActor,SiteId,UUID,int limit,String cursor): SubscriptionHistory`; `billingRecords(SubscriptionActor,SiteId,UUID,int limit,String cursor): BillingPage`; `offers(SubscriptionActor,SiteId,CustomerId,ProductId): List<SubscriptionOffer>`; `access(SubscriptionActor,SiteId,CustomerId,ProductId): AccessDecision`; `operation(SubscriptionActor,SiteId,UUID): SubscriptionOperationResult` without nextAction secret.
- `SubscriptionHistory(List<SubscriptionEvent> items,String nextCursor)`; linked cash history is explicit separate `billing_records` query, never overload lifecycle events as money events.
- `SubscriptionOffer(SubscriptionPriceTerms price,PlanTransition.Direction direction,Set<ChangeTiming> allowedTimings,boolean trialEligible)`; direction is null and timings empty for a new-subscription offer. Existing subscription offers use explicit transitions from the pinned current Price; UI never infers upgrade from amount.
- `SubscriptionBillingView(BillingRecord bill,List<PaymentId> paymentIds)`; transport maps an allowlisted invoice view and linked cash IDs, never serializes domain/provider references by default.
- Linked Payment IDs are read from owned `subscription_provider_objects` mappings populated by the Task5 bridge. Customer Payment details/history remain behind Payments application ports, not direct subscription SQL against foreign Payments repositories.
- `SubscriptionReadPort.list(SiteId,CustomerId,ProductId,String state,int limit,String cursor): RowPage`, nested `RowPage(List<Subscription> items,String nextCursor)`; `current(SiteId,CustomerId,ProductId): Optional<Subscription>` returns non-terminal only; `events(SiteId,UUID,int limit,String cursor): SubscriptionHistory`; `billingRecords(SiteId,UUID,int limit,String cursor): QuerySubscriptionsUseCase.BillingPage`; `unresolvedOperation(SiteId,UUID): Optional<SubscriptionOperation>`. Cursor parsing in the persistence adapter is bounded and bound to Site/Customer/filter/collection; foreign or modified cursor rejects, never changes authority. Query service revalidates authority before each call and derives fresh access/actions separately from stored rows.
- `ManageSubscriptionPoliciesUseCase` declares nested `PolicyContext(AuthenticatedAdmin admin,SiteId siteId,String idempotencyKey,String requestId)`; site-wide policy commands do not require or invent a CustomerId. Methods `load(AuthenticatedAdmin,SiteId): SubscriptionPolicy`, `transitions(AuthenticatedAdmin,SiteId,ProductId): List<PlanTransition>`, `save(PolicyContext,SubscriptionPolicy,long expectedVersion): SubscriptionPolicy`, `transition(PolicyContext,PlanTransition): PlanTransition`; Admin only. These mutations use scoped idempotency and audit.
- Policy disable guard: under Site row lock, `enabled:true→false` requires `!SubscriptionStore.hasBlockingSiteSubscriptions(siteId)`; otherwise `SUBSCRIPTION_STATE_CONFLICT`409 without changing policy. Concurrent create shares the Site lock. Test disable versus create and re-login cancellation availability; changing grace/transition settings remains allowed and affects future operations only.
- `SubscriptionTransport.view(SubscriptionView): SubscriptionResponse`; DTO exact wire shape below; view wraps the domain Subscription, fresh AccessDecision, permitted action enum set, next amount/tax estimate, safe debt total. Query service computes actions, UI does not invent them.

```java
public record SubscriptionView(Subscription subscription, AccessDecision access,
        Set<PermittedAction> permittedActions, NextAmount nextAmount,
        DebtView debt, PendingOperation pendingOperation) {
    public enum PermittedAction { CHANGE_PLAN, CLEAR_CHANGE, CANCEL_PERIOD_END,
        CANCEL_IMMEDIATE, WITHDRAW_CANCELLATION, SETUP_METHOD, RECOVER_INVOICE,
        RESUME, OPEN_PORTAL }
    public record NextAmount(long amountMinor,String currency,boolean taxFinal) {}
    public record DebtView(UUID billingRecordId,Subscription.Period period,
        Instant graceDeadline,long amountDueMinor,String currency) {}
    public record PendingOperation(UUID operationId,SubscriptionOperation.Kind kind,
        SubscriptionOperation.Status status) {}
}
```

**Endpoint matrix:** Customer prefix `/api/v1/customer/subscriptions`; Partner `/api/v1/partner/subscriptions`; Admin `/api/v1/admin/subscriptions`. All use the same application use cases. Customer Site/Customer come from session, Partner uses verified Site + explicit Customer belonging to it, Admin Site required.

| Method/suffix | Body/query | Success |
| --- | --- | --- |
| GET collection | limit default20 max100, cursor, product_id/state; Admin also site_id/customer_id | 200 page |
| POST collection | price_id, recurring_consent; Partner/Admin customer_id and Admin site_id | 202 operation |
| GET `/{id}` | none | 200 safe view |
| GET `/{id}/events`, `/{id}/billing-records` | limit/cursor | 200 page |
| POST `/{id}/change-preview` | target_price_id, timing | 200 quote, no charge |
| POST `/{id}/changes` | quote_id | 202 operation |
| POST `/{id}/changes/clear` | empty object | 202 operation |
| POST `/{id}/cancellation` | mode, confirmation UUID for immediate | 202 operation |
| POST `/{id}/cancellation/withdraw` | empty object | 202 operation |
| POST `/{id}/method-setups` | recurring_consent | 202 operation |
| GET `/{id}/operations/{operationId}` | none | 200 safe operation |
| GET `/{id}/operations/{operationId}/action` | none | 200 memory-only action capability |
| POST `/{id}/operations/{operationId}/action-confirmations` | action_token, Fake supported only | 202 operation |
| POST `/{id}/recoveries` | billing_record_id | 202 operation |
| POST `/{id}/resumptions` | recurring_consent | 202 operation |
| POST `/{id}/portal-sessions` | return_url | 201 short-lived navigation |
| POST `/{id}/reconcile` | empty object; Admin/Partner manage only | 202 operation |

Partner access endpoint `GET /api/v1/partner/customers/{customerId}/products/{productId}/access` requires `subscriptions:read`; no public paid-content access endpoint. Admin policy endpoints under `/api/v1/admin/sites/{siteId}/subscription-policy` and `/products/{productId}/subscription-transitions` use GET/POST, version check, idempotency, CSRF, audit. Admin Fake controls `/api/v1/admin/subscriptions/{id}/fake-controls` accept bounded scenario/action/clock values only for FAKE account and local profile, no arbitrary provider event JSON. Customer action confirmation is not this Admin control route.

Offer endpoints: Customer `GET /api/v1/customer/subscription-products/{productId}/offers`; Partner `GET /api/v1/partner/customers/{customerId}/products/{productId}/subscription-offers`; Admin `GET /api/v1/admin/sites/{siteId}/customers/{customerId}/products/{productId}/subscription-offers`. All require subscription read authority, exact ownership and published subscription Prices. Reply `{items:[{price,direction,allowed_timings,trial_eligible}]}` is bounded by the list response cap. Financial apply still revalidates authority and exact quote conditions.

**Safe SubscriptionResponse exact top-level fields:** `subscription_id`, `site_id`, `customer_id`, `product_id`, `payment_account_id`, `billing_price`, `entitled_price`, `state`, `billing_status`, `revision`, `period`, `paid_through`, `trial`, `cancellation`, `pending_change`, `pending_operation`, `debt`, `access`, `permitted_actions`, `next_amount`, `created_at`, `updated_at`.

Nested Price fields: `price_id`, `price_key`, `version`, `product_id`, `amount_minor`, `currency`, `interval`, `trial_days`, `trial_payment_method_policy`, `tax_mode`. `period`/`trial`: `start`, `end`. Cancellation: `effective_at`. Pending change: `operation_id`, `target_price`, `effective_at`, `state`. Debt: `billing_record_id`, `period`, `grace_deadline`, `amount_due_minor`, `currency`. Next amount: `amount_minor`, `currency`, `tax_final`. Access uses Task2 fields with snake_case names. Missing state facts represented null; entitled Price null on unpaid initial. No provider secrets/references in ordinary Customer safe view.

Pending operation: `operation_id`, `kind`, `status`, or null. This safe pointer permits reopening the manager after timeout/logout without storing secrets or issuing a fresh financial POST. Billing view fields: `billing_record_id`, `subscription_id`, `kind`, `price`, `previous_price`, `period`, `amount_due_minor`, `currency`, `settlement`, `payment_ids`, `created_at`, `updated_at`; no provider invoice string in Customer view. Quote response fields: `quote_id`, `subscription_id`, `from_price_id`, `from_price_version`, `target_price`, `timing`, `due_now_minor`, `next_amount_minor`, `currency`, `effective_at`, `expires_at`, `tax_final`, `subscription_revision`, `policy_version`; never provider quote reference or authority digest.

Permitted actions enum is `SubscriptionView.PermittedAction`, derived from live policy/state/capabilities/unknown guard. Safe `SubscriptionOperationResponse` fields: `operation_id`, `subscription_id`, `status`, `action_available`; action fetched separately. Customer mutation response is a distinct `SubscriptionMutationResponse` with those safe fields and nullable `next_action` capability DTO; safe polling/events never include it. `SubscriptionResponse`, these response types and other transport records are nested public types of `SubscriptionTransport` with explicit OpenAPI schema names. Action response: `kind`, `secret`, `expires_at`; Portal response: `navigation_url`, `expires_at`, `simulated`.

- [ ] **1. RED: no cross-authority/cross-origin mutation.** Add MockMvc cases before route wiring. Request limits fixed: mutation body8KiB, single IDs32–255 bounds per type, response detail32KiB/list+history128KiB, cursor4096char max; unknown query keys/duplicate key/header values rejected.

```gherkin
Scenario Outline: A foreign subscription is not an existence oracle
  Given customer A owns a subscription
  When customer B requests its <operation> endpoint with a valid B session
  Then the response is the same safe 404 as for an unknown subscription
  And no provider command or audit success is emitted
  Examples:
    | operation |
    | read      |
    | change    |
    | setup     |
    | cancel    |
```

- [ ] **2. Run RED.** `./mvnw -Dtest=CustomerSubscriptionApiIntegrationTest,PartnerSubscriptionApiIntegrationTest,AdminSubscriptionApiIntegrationTest,SubscriptionRequestPolicyIntegrationTest,SubscriptionOpenApiIntegrationTest test`.
- [ ] **3. Wire controllers/filters with safe mapping.** Require subscription read/manage scopes, current authority and owner recheck in transaction. Admin session+CSRF retained, Partner secret absent browser, Customer bearer exact Origin with no cookies mixed. API no-store, credential requests redirect forbidden, bounded safe Problem codes: `SUBSCRIPTION_NOT_FOUND`404, `SUBSCRIPTION_STATE_CONFLICT`409, `SUBSCRIPTION_QUOTE_STALE`409, `IDEMPOTENCY_CONFLICT`409, `OPERATION_IN_PROGRESS`409, `CUSTOMER_BILLING_ACTIVE`409; no provider message passthrough. Financial/control mutations idempotent, preview exemption explicit, customer read no need manage except fetching financial action capability.

```java
// Controller boundary: ownership is selected from the authenticated session, never body IDs.
var context = new SubscriptionCommandContext(new SubscriptionActor.Customer(session),
    session.siteId(),session.customerId(),origin,idempotencyKey,requestId);
var result = create.create(new CreateSubscriptionUseCase.Command(context,body.priceId(),body.recurringConsent()));
return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(transport.operation(result));
```

`SubscriptionTransport.operation(SubscriptionOperationResult)` maps safe operation fields and optional separately declared nextAction response; never uses generic domain Jackson serialization. Add writeOnly action token/consent schema metadata and redacted request toString.

- [ ] **4. Generate Admin types and run GREEN.** First focused backend tests+old Customer/Partner/Admin/Payment OpenAPI tests. With a test-owned backend only, run Admin `pnpm generate:api` against its `/v3/api-docs`, then `pnpm typecheck`. Generated enums explicitly include scopes and SUBSCRIPTION source. No manual additions to generated types. Stop owned backend afterward or leave it only for immediately following accepted validation and report ownership. Record checkpoint.

## Task 14: Headless SDK и strict subscription contracts

**Files:**

- Create: `frontend/sdk/src/subscriptions/contracts.ts`, `wire.ts`, `client.ts`, `action-driver.ts`, `subscription-client.test.ts`, `subscription-contracts.test.ts`, `subscription-authority.test.ts`.
- Modify: `frontend/sdk/src/client.ts`, `contracts.ts`, `index.ts`, `customer/contracts.ts`, `customer/client.ts`, `customer/session-storage.test.ts`, `test/customer-fixtures.ts`.
- Create: `frontend/sdk/src/test/subscription-fixtures.ts`.
- Modify: `frontend/sdk/README.md` headless methods/scopes/history rollout section.

**Interfaces:**

- Consumes: Task13 exact HTTP contracts; existing `CustomerSession`, CustomerRequest with `authorized` binding and generation checks; never checkout bearer for subscription routes.
- Produces `GenericPaymentClient.subscriptions: SubscriptionClient`. Inputs explicitly contain `session: CustomerSession`; no implicit anonymous fallback. Methods:

```typescript
interface SubscriptionRequest { session: CustomerSession; signal?: AbortSignal }
interface SubscriptionIdRequest extends SubscriptionRequest { subscriptionId: string }
interface SubscriptionMutation extends SubscriptionIdRequest { idempotencyKey: string }
interface SubscriptionClient {
  list(input: SubscriptionRequest & { limit?: number; cursor?: string }): Promise<SubscriptionPage>;
  offers(input: SubscriptionRequest & { productId: string }): Promise<SubscriptionOffer[]>;
  get(input: SubscriptionIdRequest): Promise<SubscriptionView>;
  history(input: SubscriptionIdRequest & { limit?: number; cursor?: string }): Promise<SubscriptionHistory>;
  billingRecords(input: SubscriptionIdRequest & { limit?: number; cursor?: string }): Promise<SubscriptionBillingPage>;
  create(input: SubscriptionRequest & { priceId: string; recurringConsent: boolean; idempotencyKey: string }): Promise<SubscriptionOperation>;
  preview(input: SubscriptionIdRequest & { targetPriceId: string; timing: 'IMMEDIATE' | 'NEXT_PERIOD' }): Promise<SubscriptionQuote>;
  change(input: SubscriptionMutation & { quoteId: string }): Promise<SubscriptionOperation>;
  clearChange(input: SubscriptionMutation): Promise<SubscriptionOperation>;
  cancel(input: SubscriptionMutation & { mode: 'PERIOD_END' | 'IMMEDIATE'; confirmation?: string }): Promise<SubscriptionOperation>;
  withdrawCancellation(input: SubscriptionMutation): Promise<SubscriptionOperation>;
  setupMethod(input: SubscriptionMutation & { recurringConsent: boolean }): Promise<SubscriptionOperation>;
  recover(input: SubscriptionMutation & { billingRecordId: string }): Promise<SubscriptionOperation>;
  resume(input: SubscriptionMutation & { recurringConsent: boolean }): Promise<SubscriptionOperation>;
  operation(input: SubscriptionIdRequest & { operationId: string }): Promise<SubscriptionOperation>;
  action(input: SubscriptionIdRequest & { operationId: string }): Promise<SubscriptionAction>;
  confirmAction(input: SubscriptionMutation & { operationId: string; actionToken: string }): Promise<SubscriptionOperation>;
  portal(input: SubscriptionMutation & { returnUrl: string }): Promise<SubscriptionPortal>;
}
```

`SubscriptionView`, `SubscriptionOffer`, `SubscriptionBillingView`, `SubscriptionBillingPage`, `SubscriptionQuote`, `SubscriptionHistory`, `SubscriptionPage`, `SubscriptionOperation`, `SubscriptionAction`, `SubscriptionPortal` mirror Task13 DTOs in camelCase; quote includes Task13 safe quote fields only. BillingPage is `{items:SubscriptionBillingView[],nextCursor:string|null}`. `SubscriptionOperation` safe fields only plus optional memory-only `nextAction` accepted from the distinct mutation response; polling parser forbids nextAction. `SubscriptionAction` kind/secret/expiresAt; `SubscriptionPortal` navigationUrl/expiresAt/simulated. Wire parsing lives in `wire.ts`, exported `parseSubscription(raw: unknown): SubscriptionView`, `parseSubscriptionQuote`, `parseSubscriptionOperation`, `parseSubscriptionAction`, `parseSubscriptionPortal`, `parseSubscriptionOffers`, `parseSubscriptionBillingPage`. Browser state never uses provider payload as arbitrary object.

- [ ] **1. RED: new explicit scopes/source, unknown enum rejected.**

```typescript
it('accepts the new scopes explicitly without accepting unknown authority', () => {
  expect(validScopes(['CUSTOMER_READ', 'SUBSCRIPTION_READ', 'SUBSCRIPTION_MANAGE'])).toBe(true);
  expect(validScopes(['CUSTOMER_READ', 'SUBSCRIPTION_ADMIN'])).toBe(false);
  expect(validScopes(['SUBSCRIPTION_READ', 'SUBSCRIPTION_READ'])).toBe(false);
});
```

`validScopes` imported from existing customer/contracts.ts. Fixture file defines literal safe DTOs matching Task13, not permissive `as any`.

- [ ] **2. Run RED.** SDK: `pnpm test --run src/subscriptions/subscription-contracts.test.ts src/subscriptions/subscription-client.test.ts src/subscriptions/subscription-authority.test.ts`.
- [ ] **3. Implement strict parsers and HTTP methods.** Reject extra fields, non-UUIDs, unsafe ints, zero/negative regular amount, fractional versions, invalid datetime ordering, allowed access without entitled version/validUntil, mismatched Site/Customer/Subscription, invalid enums/cursor. Validate access expiry <= evaluatedAt+300s and boundary where exposed; no browser authorization promise. History source adds SUBSCRIPTION explicitly with catalog=null; scope validator max5 with duplicate check, never wildcard acceptance. Old persisted3-scope sessions remain syntactically valid but fail manage locally until reauth.

```typescript
const scopes = ['CUSTOMER_READ', 'CHECKOUT_CREATE', 'IDENTITY_LINK',
  'SUBSCRIPTION_READ', 'SUBSCRIPTION_MANAGE'] as const;
export function validScopes(raw: unknown): raw is CustomerScope[] {
  return Array.isArray(raw) && raw.length > 0 && raw.length <= scopes.length
    && new Set(raw).size === raw.length
    && raw.every(s => scopes.includes(s as typeof scopes[number]));
}
```

- [ ] **4. Preserve authority generation and financial intent.** All requests credentials omit/cache no-store/redirect error, response32KiB or pages128KiB, GET polling bounded5sec/10min using existing bounds. Every mutation requires caller-generated intent key; timeout means read operation/list, never new POST automatically. Action tokens only memory, no sessionStorage/DOM/events/URLs. Customer client provides a narrow subscription-authorized request wrapper which verifies original session fingerprint before send and after await; replacing session with same customer but different bearer also invalidates old work. Abort stops browser work, not server execution. Portal URL validated as approved server navigation scheme/shape and not persisted/logged.

```typescript
// Add to subscription-authority.test.ts with controlled fetch promises.
it('does not retry an accepted mutation after an unreadable response', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response('{', {
    status: 202, headers: { 'content-type': 'application/json' }
  }));
  vi.stubGlobal('fetch', fetchMock);
  const client = createGenericPaymentClient({ apiBaseUrl: 'http://localhost:8080' });
  await expect(client.subscriptions.create({ session: subscriptionSession(),
    priceId: subscriptionPriceId, recurringConsent: true,
    idempotencyKey: 'subscription-intent-1' })).rejects.toBeInstanceOf(PaymentSdkError);
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
```

`subscriptionSession(): CustomerSession` and `subscriptionPriceId: string` are exported from the new fixture file with exact existing valid43char test token, current test origin, valid future expiry, new scopes and random fixed UUIDs. Test cleanup uses existing setup to restore globals.

- [ ] **5. Run GREEN.** `pnpm typecheck`, `pnpm test --run`, `pnpm build`. Focus stale completion after logout/switchCustomer/switchSite/baseURL/token renewal; setup capability not in storage/events; malformed/oversized/error redirects; no auto POST; SSR import safe; old payment/donation and OTP/OIDC/link flows pass. Record checkpoint.

## Task 15: Themeable subscription manager Web Component и demo

**Files:**

- Create: `frontend/sdk/src/subscriptions/generic-subscription-manager.ts`, `manager-state.ts`, `styles.ts`, `copy.ts`, `generic-subscription-manager.test.ts`, `manager-state.test.ts`, `action-driver.test.ts`.
- Modify: `frontend/sdk/src/index.ts`, `frontend/sdk/README.md`, `examples/demo-site/src/main.ts`, `styles.css`.
- Create: `examples/demo-site/src/subscriptions.ts`.

**Interfaces:**

- Consumes: headless SubscriptionClient, explicit CustomerSession, safe projections and permittedActions, memory-only action driver.
- Produces `registerGenericSubscriptionManager(tagName = 'generic-subscription-manager'): void`, SSR-safe no implicit registration.
- Component properties `client: GenericPaymentClient | undefined`, `customerSession: CustomerSession | undefined`, `productId: string | undefined`, `priceId: string | undefined`, `locale: 'en'|'pt'`.
- Exported pure `manager-state.ts`: `ManagerState { authorityRevision:number; phase:'idle'|'loading'|'ready'|'processing'|'requires_action'|'unknown'|'expired'|'error'; subscription:SubscriptionView|null; operation:SubscriptionOperation|null }`; `acceptManagerResult(state:ManagerState,authorityRevision:number,next:ManagerState): ManagerState` rejects stale revision. Component aborts requests/increments revision when authority/site/API/disconnect changes.
- Events `generic-subscription-state`, `generic-subscription-error` have frozen safe detail `{subscriptionId:string|null,operationId:string|null,state:string,revision:number}` or `{code:string}`; never tokens, URLs, price/provider payloads. Export `SubscriptionFakeActionDriver.complete(action:SubscriptionAction,input:SubscriptionMutation & {operationId:string}): Promise<SubscriptionOperation>` via client confirmAction, no Admin controls.
- CSS tokens `--gp-subscription-font`, `--gp-subscription-text`, `--gp-subscription-background`, `--gp-subscription-accent`, `--gp-subscription-focus`, `--gp-subscription-border`, `--gp-subscription-radius`; Shadow DOM with native labelled controls, no external CSS dependency.

- [ ] **1. RED: logout/switch defeats stale completion.**

```typescript
it('ignores completion from the previous authority', () => {
  const current: ManagerState = { authorityRevision: 2, phase: 'idle',
    subscription: null, operation: null };
  const stale: ManagerState = { ...current, authorityRevision: 1, phase: 'ready' };
  expect(acceptManagerResult(current, 1, stale)).toBe(current);
});
```

- [ ] **2. Run RED.** SDK: `pnpm test --run src/subscriptions/manager-state.test.ts src/subscriptions/generic-subscription-manager.test.ts`.
- [ ] **3. Implement manager read/confirm flows and safe generation check.** Create requires authenticated session + consent; otherwise display login requirement and do not POST. Fetch exact published offers for Product and billing records separately from lifecycle history. Show current billing/entitled tier, period/trial/grace, cancellation, pending change, next amount with tax estimate, unresolved operation and history. On reopen follow `pendingOperation.operationId` to safe status/action reads; do not infer success or submit another command. Only render permitted actions. Planchange preview separated from Apply confirmation; cancellation explains effective date/no automatic refund; setup copy never says “pay debt”. Unknown state offers refresh/status, not fresh financial retry. Locale rerender only, preserves safe state and does not invoke action.

```typescript
export function acceptManagerResult(state: ManagerState, authorityRevision: number,
  next: ManagerState): ManagerState {
  return authorityRevision === state.authorityRevision
    ? { ...next, authorityRevision: state.authorityRevision } : state;
}
```

- [ ] **4. Add en/pt copy, accessibility and demo integration.** Copy dictionaries have identical typed keys. Include text:

```typescript
const copy = {
  en: { simulated: 'Local Fake simulation — not a real payment',
    setup: 'Update payment method', recover: 'Pay the existing invoice',
    unknown: 'The result is not confirmed. Refresh the operation status.',
    cancelNote: 'Cancellation does not automatically refund earlier payments.' },
  pt: { simulated: 'Simulação Fake local — não é um pagamento real',
    setup: 'Atualizar método de pagamento', recover: 'Pagar a fatura existente',
    unknown: 'O resultado não está confirmado. Atualize o estado da operação.',
    cancelNote: 'O cancelamento não reembolsa automaticamente os pagamentos anteriores.' }
} satisfies Record<'en'|'pt', Record<'simulated'|'setup'|'recover'|'unknown'|'cancelNote', string>>;
```

Add remaining manager labels in the same typed dictionary as UI controls are implemented, with both translations in the same edit. Native fieldset/legend for options, focus first invalid field/error summary, aria-live polite status, visible focus/reduced motion. Demo integrates existing OTP login, two products and theme; Fake Portal navigation returns to manager and reloads server state. No public content gate based on JS; display explicit note that protected content needs backend/serverless verification.

- [ ] **5. Run GREEN.** SDK `pnpm typecheck`, `pnpm test --run`, `pnpm build`; demo `pnpm build`. Cases sessionexpiry/disconnect/reconnect/site/API/customer switch; locale-only no POST; frozen event detail no secrets; action clearing; keyboard preview/cancel/setup, aria focus, theme. Record checkpoint.

## Task 16: Admin subscriptions, policies и existing refund integration

**Files:**

- Create: `frontend/admin/src/components/subscriptions/SubscriptionList.tsx`, `SubscriptionDetails.tsx`, `SubscriptionFilters.tsx`, `SubscriptionTimeline.tsx`, `SubscriptionActions.tsx`, `SubscriptionPolicyForm.tsx`, `PlanTransitionForm.tsx`, `FakeSubscriptionControls.tsx`, `test-fixtures.ts`.
- Create tests: `SubscriptionList.test.tsx`, `SubscriptionDetails.test.tsx`, `SubscriptionPolicyForm.test.tsx`, `SubscriptionActions.test.tsx` in the same directory.
- Create: `frontend/admin/src/app/subscriptions/page.tsx`, `src/app/subscriptions/[subscriptionId]/page.tsx`, `src/app/sites/[siteId]/subscriptions/page.tsx`.
- Modify: `frontend/admin/src/components/AppShell.tsx`, `CreatePriceForm.tsx`, `CatalogView.tsx`, `customers/AnonymizeCustomerDialog.tsx`, `payments/PaymentDetails.tsx`, `integration/SiteApiKeys.tsx`; `frontend/admin/src/lib/api/forward.ts`, `forward.test.ts`.
- Reuse: generated `schema.d.ts`, existing `api`, `ActionDialog`, `useIntentMutation`, `RefundDialog`, Site integration key form.

**Interfaces:**

- Consumes: Task13 generated `components['schemas']` and backend routes; no handwritten second Admin DTO model.
- `SubscriptionList(): React.JSX.Element`; `SubscriptionDetails({subscriptionId:string})`; `SubscriptionPolicyForm({siteId:string})`; `SubscriptionActions({subscription:SubscriptionResponse,operation?:SubscriptionOperationResponse,onChanged:()=>void})`; response aliases come from the explicitly named generated schemas in Task13.
- BFF allowlist extends explicit subscription/policy/action/reconcile/Fake routes, retains existing cookie/CSRF and no external credentials forwarding.
- `CreatePriceForm` adds required/optional trial method choice for subscription Price with trialDays>0; no mutation of published Price. Partner key editor offers explicit read/manage scopes without auto-edit of old keys.

- [ ] **1. RED: unresolved result never shown as completed cancellation.** Add component test with operation UNKNOWN fixture and current ACTIVE projection; assert visible “Result not confirmed”, no “Cancelled” success label, refresh available, conflicting commands disabled. Fixtures contain only safe wire DTOs and no tokens.

```tsx
it('shows ambiguous result without claiming cancellation', () => {
  render(<SubscriptionActions subscription={unknownCancellationSubscription()}
    operation={unknownCancellationOperation()}
    onChanged={vi.fn()} />);
  expect(screen.getByText('Result not confirmed')).toBeInTheDocument();
  expect(screen.queryByText('Subscription cancelled')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Refresh status' })).toBeEnabled();
});
```

`unknownCancellationSubscription(): SubscriptionResponse` and `unknownCancellationOperation(): SubscriptionOperationResponse` are exported by the new Admin fixture file. The first has ACTIVE state, empty conflicting permitted actions and pending_operation pointing to the second's operation_id; the second has UNKNOWN status and action_available=false. Details follows the safe pending_operation pointer on reopen. Both fixtures use exact Task13 DTOs, not casts to permissive types.

- [ ] **2. Run RED.** Admin: `pnpm test --run src/components/subscriptions/SubscriptionActions.test.tsx src/components/subscriptions/SubscriptionList.test.tsx src/components/subscriptions/SubscriptionPolicyForm.test.tsx`.
- [ ] **3. Implement lists/details/actions with existing intent hook.** Filter Site/Customer/Product/state, cursor pagination, safe UTC display, billing vs entitled Price, trial/grace/cancel deadline, billing records and linked Payments, lifecycle sequence, unknown/reconcile state. Mutation body/key stays bound to visible intent; changing target clears quote/requires new confirmation. 409 stale triggers refresh, not auto-reapply. Show no mark-paid/grant toggle. PaymentDetails shows immutable subscription/bill/kind/period context and opens existing refund dialog; refund result does not set cancelled state.

```tsx
<a href={`/payments/${paymentId}`}>Open payment and refunds</a>
<p>Refunds do not cancel this subscription.</p>
```

- [ ] **4. Add policy forms and guard feedback.** Site enabled/grace integer>=0/immediate/Portal/allowlistedURLs/deferredupgrade; transitions select exact Price IDs within Product and explicit direction, no inference from amounts. Policy version conflict displayed without overwrite; disabling while subscriptions remain reports the Task13 guard, not a partial disabled UI. Anonymization409 message links to Customer's subscriptions and requires separate authorized cancellation/confirmation, never automatically cancels. Fake controls labelled simulation and local-only. Recheck BFF route/body/response limits and suppress action/Portal secrets in diagnostics.

```tsx
<p>Finish non-terminal subscriptions and confirm the provider result before anonymizing this customer.</p>
```

- [ ] **5. Run GREEN.** Admin `pnpm lint`, `pnpm typecheck`, `pnpm test --run`, `pnpm build`; SDK regression if shared contract generator changed. Tests keyboard dialogs/focus, late fetch after filter/route change, unknown/retry same intent key, server-driven permittedActions, policy concurrentversion409, immediateconfirmation, context+refund link, key scopes remain opt-in. Record checkpoint.

## Task 17: Сценарная приёмка, browser, Compose и handoff

**Files:**

- Create: `backend/src/test/resources/features/subscriptions.feature`, `T/acceptance/SubscriptionSteps.java`, `SubscriptionAcceptanceWorld.java`; modify `AcceptanceWorld.java`, `CucumberSpringConfiguration.java` only for explicit fixture cleanup/configuration.
- Create: `examples/demo-site/tests/subscriptions.spec.ts`, `subscription-session-lifecycle.spec.ts`, `admin-subscriptions.spec.ts`, `tests/support/subscription-fixtures.ts`.
- Create: `infrastructure/smoke-stage-4.mjs`, `smoke-stage-4.test.mjs`; modify `callback-receiver.test.mjs`, `compose.yaml` only if extra public simulation config needed, not for runtime secrets.
- Modify: `docs/local-development.md`, `frontend/sdk/README.md`, `frontend/README.md` (existing Admin guide).

**Interfaces:**

- Consumes: all accepted Tasks1–16; existing acceptance runner/Postgres/Mailpit, demo Playwright owned fixtures, smoke-env ownership helpers.
- Produces `SubscriptionAcceptanceWorld` helpers using real application/API ports and TestClock: `createPaid(String product,String interval)`, `startTrial(String methodPolicy,boolean hasMethod)`, `advance(Instant)`, `failRenewal()`, `recover()`, `cancelNow()`, `drainInbox()`, `access(): AccessDecision`, `paymentCount(): long`, `eventCount(SubscriptionEvent.Type): long`. No mocked provider truth in acceptance steps.
- Smoke exports `runSmoke(env:NodeJS.ProcessEnv): Promise<Stage4SmokeReport>` where report contains only result, named boolean checks and numeric counts; no body/token/email/URL dumps. `Stage4SmokeReport` is documented JSDoc in `.mjs`, not TypeScript runtime syntax.

- [ ] **1. RED: add executable business scenarios, not sleeps.** Tag feature `@subscriptions`; deterministic Cucumber steps exercise actual PostgreSQL/Fake/inbox/outbox. Representative complete scenario:

```gherkin
@subscriptions
Feature: Site-owned subscriptions and confirmed product access
  Scenario: Optional trial ends without debt and resumes only explicitly
    Given an authenticated customer of a subscription-enabled site
    And a monthly subscription price with a 7 day optional-method trial
    When the customer creates the subscription with recurring consent
    And provider facts are processed
    Then access is allowed because of trial
    And no cash payment exists for the trial
    When time reaches trial end without a payment method
    And provider facts are processed
    Then the subscription is paused and access is denied
    And no renewal debt exists
    When a method setup is confirmed
    Then the subscription remains paused
    When the customer explicitly resumes and the first invoice is paid
    And provider facts are processed
    Then access is allowed for the new paid period without another trial
```

Additional executable scenarios in this same feature map every spec acceptance group: required setup/trial, initial decline/action/unknown/success, live-product uniqueness and two products, monthly/yearly invoices, fixed grace/cacheexpiry, scheduled unpaid target, conditional positive/zero upgrade, stale quote, cancel/withdraw/terminal late cash, debt recovery/no doublecharge, setup isolation/Portal, refund independence, duplicates/out-of-order/crash/restart, unsupported settlement, signed v3/v4 replay, crossSite/oldscopes, anonymization race and migration regression. Individual domain/integration tasks already pin low-level interleavings; do not duplicate full race matrices in browser.

- [ ] **2. Run RED focused.** Backend `./mvnw -Dtest=RunCucumberTest -Dcucumber.filter.tags=@subscriptions test`; demo `pnpm test:e2e -- subscriptions.spec.ts subscription-session-lifecycle.spec.ts admin-subscriptions.spec.ts --retries=0`; root `node --test infrastructure/smoke-stage-4.test.mjs`. Infrastructure/unit RED is an unmet check, not a deliberately unavailable port.
- [ ] **3. Implement acceptance glue and finite smoke.** Real flow Fake provider → inbox → cash/lifecycle projections → journals → signed outbox → local receiver. Receiver verifies signatures and exact v3/v4 fields, dedups event ID, applies revision-monotone cache only after Partner refresh; no late callback can extend `valid_until`. Smoke tests bound every poll/read, ENV-only secret configuration, labels/ID checks for owned services; no reading `.env` inside test helper and no payload diagnostics. Test unknown/restart once with provider durable receipt, not repeated destructive resets.

```javascript
/** @typedef {{result:'PASS', initial:boolean, trial:boolean, renewal:boolean,
 * grace:boolean, recovery:boolean, upgrade:boolean, cancellation:boolean,
 * signedCallbacks:boolean, noDuplicateCash:boolean, isolation:boolean}} Stage4SmokeReport */
export function accessCacheFresh(decision, now) {
  return decision?.allowed === true && typeof decision.valid_until === 'string'
    && Number.isFinite(Date.parse(decision.valid_until))
    && now < Date.parse(decision.valid_until);
}
```

Add node test proving at exact valid_until returns false; consumer first validates Site/Customer/Product and signature/revision, so this helper is only cache freshness, not authorization by itself.

- [ ] **4. Browser journeys against real owned backend.** Cover OTP auth→trial/create→method setup→invoice payment; upgrade preview/confirm; failedrenewal grace/recovery; cancel/withdraw; en/pt/theme/keyboard; Admin policy/history/reconcile/refund navigation. Session lifecycle test starts delayed action, logs out/switches Customer/disconnects, resolves old response; asserts no stale success/event/storage or secondPOST. Keep existing screenshots/trace/video off for secret-bearing flows, retries0 for acceptance evidence. Use controlled test Clock/Fake controls, not actual timeouts for billing periods.

```typescript
test('theme and locale do not create a financial operation', async ({ page }) => {
  let mutations = 0;
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().includes('/customer/subscriptions')) mutations++;
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Português' }).click();
  await page.getByRole('button', { name: 'Subscription theme' }).click();
  expect(mutations).toBe(0);
});
```

Demo buttons are introduced in Task15 with these accessible names. Assert ready UI conditions before counting action effects; no arbitrary sleep.

- [ ] **5. Run final verification with fresh evidence.** Backend `./mvnw verify` (all stages/Cucumber); SDK `pnpm typecheck`, `pnpm test --run`, `pnpm build`; Admin `pnpm lint`, `pnpm typecheck`, `pnpm test --run`, `pnpm build`; demo `pnpm build`, `pnpm test:e2e -- --retries=0`; root `node --test infrastructure/callback-receiver.test.mjs infrastructure/smoke-stage-2.test.mjs infrastructure/smoke-stage-3a.test.mjs infrastructure/smoke-stage-3b.test.mjs infrastructure/smoke-stage-4.test.mjs`. Then execute Stage4 Compose smoke with explicitly provided test ENV and test-owned stack. Existing Stage1–3 runtime regression checks use their documented entrypoints and owned fixture IDs, not blind reuse of other processes/data.
- [ ] **6. Update local-development/rollout guide and stop owned services.** Document enabled Site + published subscription Price + new Customer login/new Partner scopes; current strict SDK upgrade required; examples for Customer manager and Partner server-side access check; callbacks v1/v2/v3/v4; Fake controls/limitations; prices/policies immutability; unknown/reconcile workflow; trial/grace/cancel/refund/setup differences; Stripe stage5 prerequisite list. Preserve existing Compose volumes and unrelated processes; no `down -v`, broad process kill or database wipe. Report actual suite counts/commands/results and outstanding limitations only after running them. No real Stripe readiness or production/legal claim.

## 4 Миграционный rollout

1. Сервисы остановлены → применить additive migrations к сохранённым данным в test copy; backend migration acceptance проверяет baselineV12→latest отдельно от live local data.
2. Развернуть backend вместе с widened validators/new DTOs. Subscriptions default disabled; existing one-time flows продолжают работать.
3. Обновить Admin и SDK/demo совместно. Existing SDK strict enums не объявлять совместимыми с SUBSCRIPTION/history/newscopes без обновления.
4. Явно включить нужный Site, настроить transitions/Price, выдать новые Partner scopes и пройти новую Customer authentication. Старые sessions/keys сохраняют прежние права.
5. Проверить Fake бизнес-сценарии/операции/обработку callback и серверную проверку доступа. Не заменять серверный gate компонентом.
6. Подготовить Stage5 отдельным согласованным scope: Stripe account/test credentials/webhooks/domains/consent, pinned API version, sandbox validation conditional updates/free trial/offsession/payment methods/Portal. Stage4 не включает эти внешние действия.

## 5 Self-review и handoff

| Раздел спецификации | Владеющие задачи |
| --- | --- |
| 1–4 scope/hexagonal/Fake boundary | Все задачи, особенно2/4/5/7 |
| 5 Product/Price/policy | 1/3/6/9/16 |
| 6 state/access/cache/account | 2/3/7/8/10/12/13 |
| 7 trial/initial | 3/4/6/7/8/11 |
| 8 renewal/grace/recovery | 4/5/7/8/12 |
| 9 preview/plan change | 1/4/9/14/15/16 |
| 10 cancel/refunds | 5/10/16 |
| 11 setup/Portal | 4/11/13/14/15 |
| 12 cash/context/two journals | 3/5/7 |
| 13 idempotency/inbox/reconcile/deadline | 3/4/6/7/9/10/11/12 |
| 14 API/callbacks/static server gate | 6/7/13/14/15/17 |
| 15 security/anonymization | 3/6/11/13/14/15/16 |
| 16 SDK/Admin/demo | 14/15/16/17 |
| 17 migration/compatibility | 1/3/5/6/7/13/14/17 |
| 18 observability | 7/12/16/17 |
| 19 acceptance/regression/cleanup | Каждая задача и финальная17 |
| 20 Stripe boundaries | Global Constraints, rollout6, Task17 guide |

Before execution: main agent reads both spec and this plan; checks current files for user changes without Git; confirms runtime/service ownership and chosen method. During each task follow RED→GREEN, inspect test output, keep local checkpoint. If actual code differs from assumed interface, amend plan/contracts before dependent task; do not silently add scope.

Plan author self-review (2026-10-11): all20 spec sections mapped above; 17 tasks have RED and GREEN/final-verification gates; five Review Focus cases assigned to owning tests. Static placeholder scan found none; code fences balanced. Corrected applied-migration reuse, pure-domain quote/rule types, exact Admin props, v3 cash callback dependency, invoice-attempt history, pending-operation discovery, Fake Portal navigation and account/lease serialization. These are document checks only: product tests were not run, implementation/services/Git were not touched. Execution begins only after user reviews this plan and chooses Native or subagent-driven.
