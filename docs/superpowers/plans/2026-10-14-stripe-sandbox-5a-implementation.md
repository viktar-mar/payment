# Stripe Sandbox 5A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подключить разовые платежи и донаты к Stripe Sandbox с Payment Element, проверенными денежными фактами, возвратами и восстановлением после сбоев, сохранив Fake flows.

**Architecture:** Java application управляет authority, immutable money, operations и journals; Stripe SDK изолирован в outbound adapters. Клиент подтверждает один PaymentIntent через Stripe.js, backend подтверждает результат через signed inbox и retrieve, используя общий transition/outbox path. Default Compose не требует Stripe; opt-in overlay передаёт ключи только backend.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Maven Wrapper, PostgreSQL 18, Flyway, stripe-java 34.0.0 / API 2026-09-30.endive, TypeScript, Stripe.js loader 10.1.0, Vitest, Cucumber, Playwright, Docker Compose.

**Spec:** [Подтверждённая спецификация этапа 5 и части 5A](../specs/2026-10-14-stripe-sandbox-design.md), подтверждение пользователя 2026-10-14.

## Global Constraints

- «Ни один шаг этого этапа не включает live payments, Stripe Connect, Managed Payments или активацию бизнеса для приёма настоящих денег».
- «5A использует один PaymentIntent на локальный Payment и Payment Element для ввода платёжных данных».
- «Backend использует официальный `com.stripe:stripe-java:34.0.0` с API `2026-09-30.endive`».
- «Subscriptions для Stripe недоступны до 5B. Существующие Fake subscriptions продолжают работать».
- «До 5C adapter ограничивает методы проверенным card flow, wallet UI отключён».
- «`NONE` и `INCLUSIVE` сохраняют утверждённую сумму; `EXCLUSIVE` и автоматический Stripe Tax отклоняются до отдельной реализации».
- «Stripe raw JSON и client secrets не сохраняются» — это относится к platform persistence/logs; Stripe SDK получает raw response только в памяти adapter.
- «Синхронный provider acknowledgement и browser result не создают `PAYMENT_SUCCEEDED`».
- «Default `infrastructure/compose.yaml` остаётся работоспособным без Stripe keys и сохраняет Fake».
- Не читать и не перезаписывать существующий `infrastructure/.env` при подготовке конфигурации. Private keys и `.env.local` не коммитить; public repository требует secret review перед каждым push.
- Новые migrations только additive; V1–V20 не редактировать. Financial history и callback v1/v2/v3/v4 не переписывать.
- Все network calls выполняются вне DB transaction. Domain/application не импортируют `com.stripe`, HTTP DTO или adapter classes.
- Запуск Stripe smoke только с явным opt-in, test credentials и согласованными fixtures. Не создавать новый финансовый operation key при неизвестном исходе.

## Review Focus

1. Один operation key с изменёнными Account/amount/currency/Price version обязан дать conflict до provider call; тест Task 3.
2. Старый card decline после нового attempt не должен закрыть новый flow или отменить success; failure watermark и тесты Tasks 6 и 9.
3. Checkout token с чужим Origin, подменённой сохранённой связью Payment/Customer или изменившимся credential binding не должен раскрыть client secret; тест Task 7.
4. Подписанный body с invalid UTF-8, несколькими signature headers или future timestamp обязан отклоняться до inbox insert; тест Task 8.
5. Позднее разрешение Stripe loader после disconnect и return URL с secret до analytics не должны оставить Element/capability или раскрыть query; тесты Tasks 13–15.

## Порядок и правила выполнения

Этот документ — план, не отчёт о реализации. Все checkbox пока открыты. Перед execution прочитать spec целиком, проверить `git status`, сохранить чужие изменения и использовать `superpowers:using-git-worktrees`, если требуется изоляция. Новый worktree создаётся только при execution, не при написании плана.

Все задачи выполняются последовательно. Review одной задачи может отклонить её независимо от соседней. Для каждой задачи обязательны содержательный RED, минимальный GREEN, regression check и отдельный commit. Compiler failure допустим только для ещё не введённого интерфейса; затем нужен behavioral RED. Отсутствие Docker/network/dependency — blocker проверки, не доказательство RED.

Корень команд — `/Users/viktar.marozau/Projects/my/generic-solution/payment`. `B` означает `backend/src/main/java/dev/genericpayments/platform`, `T` — `backend/src/test/java/dev/genericpayments/platform`, `M` — `backend/src/main/resources/db/migration`; каждый путь с этим префиксом однозначно раскрывается в полный путь. Public Java types хранятся в одноимённых файлах. Test snippets используют JUnit 5/AssertJ либо Vitest/Node test imports; interfaces ниже — контракты, не готовая реализация.

Фокусная backend-команда: из `backend` выполнить `./mvnw -Dtest=<точные имена тестов задачи> test`. Frontend-команды выполняются из указанного package. Документировать exit code, passed/failed/skipped и причину непройденных проверок. Не выдавать старые результаты за текущие.

### Карта файлов

| Группа | Ответственность | Задачи |
| --- | --- | --- |
| `infrastructure/stripe-sandbox-*`, Compose overlay, local guide | Private local configuration, safe preflight и opt-in runner | 1, 16 |
| `B/provider/adapter/out/stripe/` | Credential binding, official SDK, catalog/payment/refund mapping | 2, 4, 5, 6, 10 |
| `B/provider/application/`, `B/provider/adapter/out/persistence/` | Durable dispatch identity/lease/reference, без второго financial ledger | 3 |
| `B/catalog/application/service/`, registry | Account-specific publication вне transaction | 4 |
| `B/payments/application/`, public checkout adapters | Client attempt/capability и единый verified transition path | 6, 7, 9, 11 |
| `B/provider/adapter/in/web/`, existing provider inbox | Exact-byte signature admission, durable reference receipt | 8, 9 |
| `frontend/sdk/src/providers/`, contracts/storage/components | Stripe headless driver и Payment Element lifecycle | 12–14 |
| `frontend/admin/`, `examples/demo-site/` | Safe inspection, recovery/return UX, Sandbox demo | 15 |
| Tests, Cucumber, Sandbox smoke | Offline regression и отдельное реальное Stripe evidence | Все, итог 16 |

## Контракты между задачами

Новые application-типы не содержат Stripe SDK models и raw JSON. Safe result не содержит исключений Stripe. Credential/client-capability objects обязаны иметь redacted `toString`; secrets не являются record components с автоматически раскрывающим `toString`.

```java
// Task 2: paymentaccounts/application/port/out/PaymentAccountVerificationPort.java
public interface PaymentAccountVerificationPort {
    void verify(PaymentAccount account);
}

// Task 3: provider/application/model/ProviderOperation.java
public record ProviderOperation(UUID id, PaymentAccountId accountId,
        String externalAccountId, String credentialsRef, Kind kind,
        UUID resourceId, String operationKey, String fingerprint,
        Status status, String providerReference, Instant firstDispatchedAt,
        Instant retryBefore, String leaseOwner, Instant leaseExpiresAt, long version) {
    public enum Kind { PRODUCT_CREATE, PRICE_CREATE, PAYMENT_PREPARE, REFUND_CREATE }
    public enum Status { RESERVED, DISPATCHING, ACKNOWLEDGED, UNKNOWN, FAILED }
}

// Task 3: provider/application/port/out/ProviderOperationStore.java
public interface ProviderOperationStore {
    ProviderOperation reserve(PaymentAccount account, ProviderOperation.Kind kind,
        UUID resourceId, String key, String fingerprint);
    Optional<ProviderOperation> find(PaymentAccountId account, String key);
    Optional<ProviderOperation> claim(UUID id, String owner, Instant now, Duration lease);
    ProviderOperation acknowledge(UUID id, String owner, String reference);
    ProviderOperation unknown(UUID id, String owner);
    ProviderOperation bindRecovered(UUID id, String reference, String fingerprint);
}

// Task 6: payments/application/model/ProviderEvidence.java
public record ProviderEvidence(PaymentProviderType provider, PaymentAccountId accountId,
        PaymentAccountMode mode, String externalAccountId, Money money,
        String paymentReference, String chargeReference,
        String failedChargeReference, boolean captured) {}

// Task 6: preserve the two-argument constructor for existing Fake callers.
public record ProviderState(String reference, Status status, ProviderEvidence evidence) {
    public ProviderState(String reference, Status status) { this(reference, status, null); }
    public enum Status { CREATED, PENDING, REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION,
        PROCESSING, SUCCEEDED, FAILED, UNKNOWN, CANCELLED }
}

// Task 7: payments/application/port/in/PrepareClientPaymentUseCase.java
public interface PrepareClientPaymentUseCase {
    ClientPaymentSession prepare(PrepareClientPaymentCommand command);
}
// Task 7: payments/application/model/PrepareClientPaymentCommand.java
public record PrepareClientPaymentCommand(UUID checkoutId, URI origin,
        String checkoutToken, String idempotencyKey, String requestId) {}
// Override toString with a constant redacted summary; never log this command.

// Task 7: payments/application/port/out/ClientPaymentCapabilityPort.java
public interface ClientPaymentCapabilityPort {
    ClientPaymentCapability retrieve(PaymentAccount account, Payment payment);
}
// Task 7: payments/application/model/ClientPaymentCapability.java
// Final class: constructor(String publishableKey, String clientSecret), explicit
// accessors publishableKey()/clientSecret(), constant redacted toString().
// Task 7: payments/application/model/ClientPaymentSession.java
// Final class: constructor(UUID checkoutId, UUID paymentId, UUID attemptId,
// ClientPaymentCapability capability, Instant expiresAt, URI returnUrl), accessors
// with those names, constant redacted toString(). Not a persistent DTO.

// Task 8: payments/application/model/ProviderReferenceReceipt.java
public record ProviderReferenceReceipt(PaymentProviderType provider,
        PaymentAccountId accountId, String eventId, String objectId,
        ObjectType objectType, String providerEventType, String apiVersion,
        Instant occurredAt, Instant receivedAt) {
    public enum ObjectType { PAYMENT, REFUND }
}
```

`ProviderInboxPort.Claim` остаётся прежним. Task 8 добавляет `boolean insertReference(ProviderReferenceReceipt receipt)` и `Optional<ProviderReferenceReceipt> reference(Claim claim)`: receipt не выдаётся без действующего claim. Task 9 добавляет `ProviderInboxResolutionPort.resolve(ProviderReferenceReceipt): ResolvedProviderReceipt`, где новый immutable `ResolvedProviderReceipt` содержит receipt, проверенные local PaymentId/nullable RefundId, `ProviderState` и request fingerprint. Ни local IDs из браузера, ни metadata сами по себе не образуют этот результат.

## Task 1 Локальный Sandbox template и opt-in Compose

**Files:** Create `infrastructure/stripe-sandbox.env.example`, `infrastructure/init-stripe-sandbox-env.mjs`, `infrastructure/stripe-sandbox-config.test.mjs`, `infrastructure/compose.stripe-sandbox.yaml`; Modify `infrastructure/gitignore.test.mjs`, `.dockerignore`, `backend/.dockerignore`, `frontend/.dockerignore`, `docs/local-development.md`. Private `stripe-sandbox.env.local` не является Git artifact.

**Interfaces:** Produces `initializeSandboxEnv(path: string): 'created' | 'preserved'` из Node module. Путь CLI фиксирован относительно module, test вызывает функцию только в своём `mkdtemp`. Overlay добавляет `PAYMENT_STRIPE_SANDBOX_ENABLED: "true"`, `SPRING_PROFILES_ACTIVE: local,stripe-sandbox` и явные три `${PAYMENT_STRIPE_SANDBOX_*:?set Sandbox key}` только сервису backend. Не заменяет `PAYMENT_PROVIDER: fake`.

- [ ] **1 RED:** добавить следующий тест и проверки Git/Docker contexts. Проверить существующий файл и symlink: первый сохраняется побайтно, второй отклоняется, ничего не читается по ссылке.

```js
test('private env is exclusive, mode 0600 and never overwritten', () => {
  const directory = mkdtempSync(join(tmpdir(), 'payment-stripe-config-'));
  const path = join(directory, 'stripe-sandbox.env.local');
  try {
    assert.equal(initializeSandboxEnv(path), 'created');
    assert.equal(statSync(path).mode & 0o777, 0o600);
    const original = readFileSync(path);
    assert.equal(initializeSandboxEnv(path), 'preserved');
    assert.deepEqual(readFileSync(path), original);
  } finally { rmSync(directory, { recursive: true }); }
});
```

- [ ] **2 Проверить RED:** `node --test infrastructure/stripe-sandbox-config.test.mjs infrastructure/gitignore.test.mjs`; ожидается падение новой функции/overlay, старые source-retention checks остаются зелёными.

- [ ] **3 GREEN initializer:** использовать `lstatSync` для существующего regular file, `writeFileSync(path, template, { flag: 'wx', mode: 0o600 })` для отсутствующего; при `EEXIST` повторно проверить тип. Не делать chmod/read/overwrite существующего файла. Если его permissions шире 0600, безопасно предупредить до загрузки ключей, не менять их скрытно. CLI выводит только `created`/`preserved`, не template/key values. Template содержит ровно три пустых key assignments и комментарий «заполнить локально; не передавать в чат».

- [ ] **4 GREEN configuration:** добавить overlay; исключить `**/*.env.local` и private fixtures из Docker contexts, сохранив examples, source, Maven wrapper и lockfiles. Тест render Compose использует только синтетические значения через `spawnSync`, не читает настоящий `.env`; assert backend-only bindings, default Fake и отсутствие secret в admin/demo config.

- [ ] **5 Проверить GREEN:** `node --test --test-timeout=30000 infrastructure/stripe-sandbox-config.test.mjs infrastructure/gitignore.test.mjs`; отдельно `docker compose --env-file <owned synthetic env> -f infrastructure/compose.yaml -f infrastructure/compose.stripe-sandbox.yaml config --quiet`. Не печатать expanded config.

- [ ] **6 Commit:** stage только перечисленные tracked files, `git commit -m "feat: prepare isolated Stripe Sandbox configuration"`. После execution и только при отсутствии private env создать его initializer; keys пользователь вводит самостоятельно. До Task 2 overlay не объявлять рабочим Stripe backend.

## Task 2 Credential binding и официальный SDK client

**Files:** Modify `backend/pom.xml`, `B/paymentaccounts/application/service/CreatePaymentAccountService.java`, `B/shared/adapter/in/web/AdminCatalogConfiguration.java`; Create `B/provider/adapter/in/configuration/StripeSandboxConfiguration.java`, `B/provider/adapter/out/stripe/StripeSandboxProperties.java`, `B/provider/adapter/out/stripe/StripeCredentials.java`, `B/provider/adapter/out/stripe/StripeCredentialResolver.java`, `B/provider/adapter/out/stripe/StripeClientFactory.java`, `B/provider/adapter/out/stripe/StripeAccountVerificationAdapter.java`, `B/paymentaccounts/application/port/out/PaymentAccountVerificationPort.java`; Test `T/provider/adapter/out/stripe/StripeSandboxConfigurationTest.java`, `T/provider/adapter/out/stripe/StripeClientFactoryTest.java`, `T/provider/adapter/out/stripe/StripeAccountVerificationAdapterTest.java`, `T/support/StripeHttpFixture.java`.

**Interfaces:** Consumes `PaymentAccount`; produces `StripeCredentialResolver.resolve(PaymentAccount): StripeCredentials`, public `StripeClientFactory.create(StripeCredentials): StripeClient` и verification port. Factory/credential classes public для adapter configuration и tests, но не экспортируются через application ports. `StripeCredentials` final class содержит server/publishable/webhook keys с явными accessors, постоянный redacted `toString`. Unknown alias всегда error. Fake verification — pure no-op без network; Stripe verification выполняется вне transaction до сохранения Account.

`CreatePaymentAccountService` получает новый constructor `(LoadPaymentAccountPort, SavePaymentAccountPort, PaymentAccountVerificationPort)`. Existing overloads сохраняются для Fake-only callers и fail-closed для STRIPE, если verifier не передан; отдельный regression закрепляет отсутствие bypass через legacy constructor.

- [ ] **1 RED:** `ApplicationContextRunner` проверяет disabled/no keys, enabled/missing key, каждый live prefix, mixed `local,prod`, `test` и wrong alias. Одна строка live/dummy values создаётся программно в тесте, не копируется из пользовательского ключа. Добавить тест redaction:

```java
@Test void credentialsCannotLeakThroughDiagnostics() {
    var credentials = new StripeCredentials("sk_test_" + "A".repeat(32),
        "pk_test_" + "B".repeat(32), "whsec_" + "C".repeat(32));
    assertThat(credentials.toString()).isEqualTo("StripeCredentials[redacted]");
}
```

- [ ] **2 Проверить RED:** `./mvnw -Dtest=StripeSandboxConfigurationTest,StripeClientFactoryTest,StripeAccountVerificationAdapterTest test` из backend.

- [ ] **3 GREEN properties:** добавить dependency с точной версией 34.0.0. Bind property `payment.stripe.sandbox.enabled` и три key fields из env; constructor validators не выводят rejected value. Profile guard требует local либо test, разрешает дополнительный marker stripe-sandbox и отвергает остальные profiles, включая любое наличие prod/production. Неподходящий prefix, whitespace, пустое значение и literal `REPLACE_ME`/`your_key`/ellipsis examples отклоняются до SDK creation; validation не имитирует проверку действительности ключа.

```xml
<dependency>
  <groupId>com.stripe</groupId>
  <artifactId>stripe-java</artifactId>
  <version>34.0.0</version>
</dependency>
```

- [ ] **4 GREEN SDK transport:** instance client, без глобального `Stripe.apiKey/apiBase`. Minimal setup:

```java
StripeClient client = StripeClient.builder().setApiKey(credentials.secretKey())
    .setConnectTimeout(5_000).setReadTimeout(20_000).setMaxNetworkRetries(0).build();
```

Pin берётся из SDK default; HTTP fixture assert `Stripe-Version=2026-09-30.endive`. Не использовать unsafe version override. Только test factory принимает loopback `setApiBase`; runtime endpoint фиксирован `https://api.stripe.com`, redirect policy не должен пересылать Authorization. В Task 2 тестом закрепить refusal unexpected 3xx без следующего request.

- [ ] **5 GREEN fixture и identity:** `StripeHttpFixture` использует JDK `HttpServer` на `127.0.0.1:0`, `enqueue(method,path,status,json)`, `disconnectNext(method,path)`, `calls(): List<Call>`, `baseUri(): URI`, `close()`. `Call` содержит method/path/headers/form в памяти и redacted `toString`; fixture никогда не логирует requests. Проверить `/v1/account` через `client.v1().accounts().retrieveCurrent()`, сравнить `acct_…`; 401/403/timeout дают safe configuration error, ноль financial POST. Проверка не претендует доказать matching publishable key: это закрепит Sandbox/UI check.

- [ ] **6 GREEN и commit:** те же фокусные tests плюс `ArchitectureRulesTest`; document permissions: Account read, Products/Prices read+write, PaymentIntents read+write, Charges read, Refunds read+write, Events read для manual recovery. Не выдавать restricted key готовым до реальной проверки этих операций. Commit `feat: add account-bound Stripe Sandbox client`.

## Task 3 Durable provider operation reservation и replay guard

**Files:** Create `M/V21__provider_operation_dispatch.sql`, `B/provider/application/model/ProviderOperation.java`, `B/provider/application/port/out/ProviderOperationStore.java`, `B/provider/application/service/ProviderOperationGuard.java`, `B/provider/adapter/out/persistence/JdbcProviderOperationStore.java`; Modify `T/support/PostgresIntegrationTest.java` truncate list; Test `T/provider/adapter/out/persistence/ProviderOperationStoreIntegrationTest.java`, `T/provider/application/service/ProviderOperationGuardTest.java`.

**Interfaces:** Контракты operation/store определены выше; `ProviderOperationGuard.requireDispatch(ProviderOperation operation, PaymentAccount account, String fingerprint, Instant now): String` либо возвращает тот же operation key, либо бросает safe `PROVIDER_OPERATION_CONFLICT`/`PROVIDER_RESULT_UNKNOWN`. `ProviderOperationGuard.withinWriteWindow(Instant firstDispatchedAt, Instant now): boolean` проверяет `!now.isBefore(firstDispatchedAt) && now.isBefore(firstDispatchedAt.plus(Duration.ofHours(23)))`. Lease 60 seconds. `firstDispatchedAt` берётся из real UTC clock, не Fake TestClock.

- [ ] **1 RED:** DB tests: concurrent same reservation, changed fingerprint/account identity, crash after claim, stale owner acknowledge, recovered reference collision. Pure boundary test:

```java
@Test void expiredWriteWindowNeverPermitsBlindReplay() {
    var dispatched = Instant.parse("2026-10-14T10:00:00Z");
    assertThat(ProviderOperationGuard.withinWriteWindow(dispatched,
        dispatched.plus(Duration.ofHours(23)))).isFalse();
    assertThat(ProviderOperationGuard.withinWriteWindow(dispatched,
        dispatched.plus(Duration.ofHours(23)).minusNanos(1))).isTrue();
}
```

- [ ] **2 Проверить RED:** `./mvnw -Dtest=ProviderOperationGuardTest,ProviderOperationStoreIntegrationTest test`.

- [ ] **3 GREEN schema:** table `provider_operations` с FK Account, kind/resource/key/fingerprint, pinned external identity/credential alias, status/reference, first dispatch/retry deadline, lease owner/expiry и version; сразу добавить `recovery_attempts`, `next_attempt_at`, `safe_error_code`, `manual_review_required` для Task 11. Unique `(payment_account_id, operation_key)` и `(payment_account_id, kind, resource_id)`; partial unique provider reference по account+kind. CHECK не допускает dispatch без timestamp/deadline, credentials values/raw JSON отсутствуют. Все update — compare owner/version. Resource ownership проверяется adapter-ом по kind и существующим локальным объектам; unknown kind не dispatch-ится.

- [ ] **4 GREEN guard:** reserve/claim/acknowledge — короткие independent transactions. Fingerprint включает source, immutable amount/currency/tax, resource/version, original account identity и credential alias. Hash secret key не включать: rotation внутри того же verified account допустима. Лишь owner с live lease может write; acknowledged replay возвращает reference; unknown replay допускается только с прежним key и до retry deadline. После окна только retrieve/manual verified recovery. Никогда не «обнулять» unknown для генерации нового key.

- [ ] **5 Проверить GREEN и atomicity:** barrier-based two-thread tests без sleep, rollback injected after provider response, DB constraints на разные objects с одним key. `bindRecovered` сравнивает operation facts и verified mapping, не принимает browser/metadata-only proof. Эта таблица — dispatch receipt, не второй Payment ledger.

- [ ] **6 Commit:** `git commit -m "feat: persist provider dispatch identities and replay bounds"` после scoped staging и проверки source completeness.

## Task 4 Account-specific каталог без network внутри transaction

**Files:** Modify `B/catalog/application/service/PublishPriceService.java`, `B/catalog/adapter/in/configuration/CatalogApplicationConfiguration.java`, `B/provider/adapter/out/fake/FakeProviderConfiguration.java`, `B/catalog/adapter/out/persistence/ProviderCatalogMappingPersistenceAdapter.java`; Create `B/catalog/application/service/PricePublicationTransaction.java`, `B/catalog/application/port/out/CatalogProviderRegistry.java`, `B/provider/adapter/out/stripe/StripeCatalogProviderAdapter.java`; Test `T/catalog/application/service/PricePublicationIntegrationTest.java`, `T/provider/adapter/out/stripe/StripeCatalogProviderAdapterTest.java`.

**Interfaces:** `CatalogProviderRegistry.select(PaymentAccount): CatalogProviderPort`; прежние `ensureProduct(account, product)` и `createPrice(account, product, price)` сохраняются. `PricePublicationTransaction.prepare(PriceId): Publication` и `complete(Publication, ProviderProductRef, ProviderPriceRef): Price`; новый nested `Publication` содержит account/product/draft и ожидаемые версии. Task 3 reserve обеспечивает serialization внешней операции.

- [ ] **1 RED:** сохранить текущие Fake publication tests; новый test provider callback assert `TransactionSynchronizationManager.isActualTransactionActive()==false`. Stripe fixture checks same Product/Price replay and changed version conflict, tax rejection before POST, no cross-account mapping.

```java
// Test class extends existing CheckoutTestFixture.
@Autowired PublishPriceUseCase publication;
@MockitoSpyBean FakeCatalogProviderAdapter fakeCatalog;
@Test void publicationCallsProviderOutsideDatabaseTransaction() {
    doAnswer(call -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return call.callRealMethod();
    }).when(fakeCatalog).ensureProduct(any(), any());
    var draft = catalogPrice("publish-probe", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
    assertThat(publication.publish(draft.id()).status()).isEqualTo(PriceStatus.PUBLISHED);
}
```

- [ ] **2 Проверить RED:** `./mvnw -Dtest=PricePublicationIntegrationTest,StripeCatalogProviderAdapterTest test`; существующая `@Transactional publish` должна содержательно провалить no-ambient test.

- [ ] **3 GREEN split:** убрать transaction с coordinator; prepare валидирует Site/Product/Price/Account, снимает immutable snapshot; provider calls выполняются с Task 3 receipt вне transaction; complete повторно проверяет versions/account/status и атомарно сохраняет mappings+published Price. При конкурирующем complete с теми же mappings вернуть уже опубликованную ту же версию, с другими — conflict. Не увеличивать Price version «для retry».

- [ ] **4 GREEN Stripe mapping:** `/v1/products` и `/v1/prices`, amount minor/currency/tax behavior из immutable draft; `NONE/INCLUSIVE` fixed totals, `EXCLUSIVE` reject. Product key привязан к account+ProductId; Price key к account+PriceId+version. Разрешённые recurring catalog terms публикуются, но Stripe subscription commands всё ещё недоступны. Пример входящих registry правил:

```java
return switch (account.provider()) {
    case FAKE -> fakeCatalog;
    case STRIPE -> stripeCatalog.orElseThrow(
        () -> new IllegalStateException("PAYMENT_CAPABILITY_UNAVAILABLE"));
};
```

- [ ] **5 Проверить GREEN:** mixed Fake/Stripe registry, concurrent publish, archived Product, disabled Site/Account, crash before mapping commit; old published mappings и versions неизменны. Не обновлять старый Stripe Price amount.

- [ ] **6 Commit:** `feat: route catalog publication by account outside transactions`.

## Task 5 Один PaymentIntent на Payment и безопасная подготовка

**Files:** Create `B/provider/adapter/out/stripe/StripePaymentProviderAdapter.java`, `B/provider/adapter/out/stripe/StripePaymentRequests.java`, `B/provider/adapter/out/stripe/StripePaymentErrorMapper.java`, `T/provider/adapter/out/stripe/StripePaymentProviderAdapterTest.java`, `T/payments/application/service/StripePreparationIntegrationTest.java`; Modify `B/provider/adapter/out/fake/FakePaymentConfiguration.java`, `B/provider/adapter/in/configuration/StripeSandboxConfiguration.java`, `B/payments/application/service/PaymentCreationTransaction.java` только для verified recovered binding/late acknowledgement.

**Interfaces:** implements existing `PaymentProviderPort`; `capabilities` returns `{NONE,INCLUSIVE}`, `{CARD}`, `CLIENT_PROVIDER`. `preparePayment(ProviderPaymentRequest): ProviderResult` returns acknowledgement/reference, never synchronous success. Uses Task 3 operation reservation. `confirmPayment` always refuses Stripe server-confirm; retrieve/refund remain fail-closed until Tasks 6/10, not fabricated outcomes.

`StripePaymentRequests.prepare(Payment payment): PaymentIntentCreateParams` is adapter-local, owns the exact parameter construction below; `StripePaymentErrorMapper.map(Exception): ProviderResult` returns redacted safe ambiguity, never SDK exception text.

- [ ] **1 RED:** fixture asserts one `/v1/payment_intents` for repeated preparation, deterministic `Idempotency-Key`, no ambient transaction, original amount/account. Tests timeout-before/after creation, lost DB commit, changed money, unsupported tax/source and card allowlist rejection before write. Test no `Stripe-Account` header and no Customer creation.

```java
@Test void requestUsesImmutablePaymentMoneyWithoutFutureCharges() {
    var payment = Payment.dynamic(new PaymentId(UUID.randomUUID()),
        new SiteId(UUID.randomUUID()), new PaymentAccountId(UUID.randomUUID()),
        "owned-order", Money.of(2000, "EUR"), TaxMode.NONE, Map.of(), Instant.now());
    var params = StripePaymentRequests.prepare(payment);
    assertThat(params.getAmount()).isEqualTo(2000L);
    assertThat(params.getCurrency()).isEqualTo("eur");
    assertThat(params.getSetupFutureUsage()).isNull();
    assertThat(params.getPaymentMethodTypes()).containsExactly("card");
}
```

- [ ] **2 Проверить RED:** `./mvnw -Dtest=StripePaymentProviderAdapterTest,StripePreparationIntegrationTest test`.

- [ ] **3 GREEN request builder:** official typed parameters:

```java
PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
    .setAmount(payment.money().minorUnits())
    .setCurrency(payment.money().currency().toLowerCase(Locale.ROOT))
    .addPaymentMethodType("card")
    .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
    .putMetadata("payment_id", payment.id().value().toString())
    .putMetadata("site_id", payment.siteId().value().toString())
    .putMetadata("source", payment.source().name()).build();
var options = RequestOptions.builder().setIdempotencyKey(operation.operationKey()).build();
var intent = client.v1().paymentIntents().create(params, options);
```

Не задавать `setup_future_usage`, receipt email или произвольный metadata map. Суммы валидировать по выбранной валюте до write; для первого deliverable allowlist валют содержит EUR. Расширение валют отдельно требует тестов exponent/min/max.

- [ ] **4 GREEN reservation/ack:** claim → network → acknowledge; prepared reference привязать к original Account+Payment. Если local mapping commit потерян, acknowledged dispatch receipt восстанавливает тот же reference. Exception mapper не переносит Stripe message/body; network/5xx ambiguity → `Unknown`, не recoverable decline и не новый intent. SDK retries=0; наш replay bounded Task 3.

- [ ] **5 GREEN routing/regression:** registry содержит Fake и optional Stripe; Fake subscriptions/catalog wiring не исчезают при включённом Stripe. Site `AUTOMATIC` означает пересечение с `{CARD}`, а не автоматическое включение всех методов Dashboard. `ALLOWLIST` без CARD отклоняется до подготовки. `ProviderResult.Action` не используется для переноса client secret.

- [ ] **6 Commit:** `feat: prepare account-bound Stripe PaymentIntents idempotently`.

## Task 6 Verified snapshots и факты card attempts

**Files:** Create `B/payments/application/model/ProviderEvidence.java`, `B/provider/adapter/out/stripe/StripePaymentSnapshotMapper.java`, `M/V22__provider_charge_receipts.sql`; Modify `B/payments/application/model/ProviderState.java`, `B/provider/adapter/out/stripe/StripePaymentProviderAdapter.java`, `B/payments/application/port/out/LoadPaymentAttemptPort.java`, `B/payments/adapter/out/persistence/PaymentPersistenceAdapter.java`, `T/support/PostgresIntegrationTest.java`; Create `B/payments/application/port/out/ProviderChargeReceiptPort.java`, `B/payments/adapter/out/persistence/ProviderChargeReceiptPersistenceAdapter.java`; Test `T/provider/adapter/out/stripe/StripePaymentSnapshotMapperTest.java`, `T/payments/adapter/out/persistence/ProviderChargeReceiptIntegrationTest.java`.

**Interfaces:** `StripePaymentSnapshotMapper.payment(PaymentAccount, Payment, PaymentIntent, Charge): ProviderState`, `normalizedStatus(String): ProviderState.Status`. `ProviderChargeReceiptPort.record(PaymentAccountId, PaymentId, String chargeId, boolean paid, String safeOutcome): boolean` returns first insertion; `contains(PaymentAccountId account, String chargeId): boolean`. Attempt baseline хранится отдельной `payment_client_attempts` row (attempt FK, Account+Payment FK, nullable immutable Customer UUID из Payment, original credential alias/external identity, initial failed Charge reference); не расширять meaning старого Fake attempt.

- [ ] **1 RED:** parameterized state mapping и exact amount/currency tests. Создать Stripe models через official SDK deserialization fixture JSON в памяти; client secret в fixture — generated sentinel, не real key. Основной test:

```java
@Test void recoverableCardFailureDoesNotBecomeTerminalPaymentFailure() {
    assertThat(StripePaymentSnapshotMapper.normalizedStatus("requires_payment_method"))
        .isEqualTo(ProviderState.Status.REQUIRES_PAYMENT_METHOD);
    assertThat(StripePaymentSnapshotMapper.normalizedStatus("requires_capture"))
        .isEqualTo(ProviderState.Status.UNKNOWN);
}
```

- [ ] **2 Проверить RED:** `./mvnw -Dtest=StripePaymentSnapshotMapperTest,ProviderChargeReceiptIntegrationTest test`.

- [ ] **3 GREEN mapping:** `requires_confirmation/requires_payment_method` → method required; `requires_action`, `processing`, `succeeded`, `canceled` → соответствующее состояние. Success требует exact `amount_received`, currency, captured linked Charge и тот же PaymentIntent; чужой reference/mode/amount → safe mismatch, не success. `requires_capture` и неизвестный status → UNKNOWN и capability refusal. Last failed Charge (если есть) проверяется отдельным retrieve по тому же account/intent.

- [ ] **4 GREEN receipts:** UNIQUE `(payment_account_id, charge_reference)`, FK to existing Payment and immutable safe fields; no PAN/CVC/email/raw JSON. Watermark отделяет старый decline от новой клиентской сессии. Failure без нового Charge (например, invalid card input) остаётся UI retry того же attempt, не новый денежный факт. Provider receipts не утверждают 1:1 mapping UI attempt/Charge.

- [ ] **5 Проверить GREEN:** duplicate Charge evidence, foreign Payment/Account FK, старый failed Charge после нового attempt, evidence-less Stripe success rejection; прежний two-argument `ProviderState` продолжает работать только для Fake. Обновить test truncate list, не production data.

- [ ] **6 Commit:** `feat: normalize verified Stripe payment and charge evidence`.

## Task 7 Авторизованная client-payment capability

**Files:** Create `M/V23__checkout_return_urls.sql`, `B/payments/application/model/PrepareClientPaymentCommand.java`, `B/payments/application/model/ClientPaymentCapability.java`, `B/payments/application/model/ClientPaymentSession.java`, `B/payments/application/port/in/PrepareClientPaymentUseCase.java`, `B/payments/application/port/out/ClientPaymentCapabilityPort.java`, `B/payments/application/port/out/CheckoutReturnUrlPort.java`, `B/payments/application/service/PrepareClientPaymentService.java`, `B/payments/application/service/ClientPaymentAttemptTransaction.java`, `B/payments/adapter/out/persistence/CheckoutReturnUrlPersistenceAdapter.java`, `B/provider/adapter/out/stripe/StripeClientPaymentCapabilityAdapter.java`, `B/payments/adapter/in/web/PublicClientPaymentController.java`; Modify `B/payments/adapter/in/configuration/CheckoutConfiguration.java`, `B/payments/adapter/in/web/PublicCheckoutRoutePolicy.java`, `B/payments/adapter/in/web/DynamicSiteCorsConfigurationSource.java`, `B/payments/adapter/in/web/PublicCheckoutDtos.java`, `B/payments/application/model/IdempotencyRecord.java`, `T/support/PostgresIntegrationTest.java`; Test `T/payments/adapter/in/web/StripeClientSessionIntegrationTest.java`, `T/payments/application/service/ClientPaymentAttemptIntegrationTest.java`.

**Interfaces:** Contracts above. `CheckoutReturnUrlPort.find(SiteId, URI origin): Optional<URI>` resolves one exact configured return URL per Site+Origin, with HTTPS outside local loopback. No browser-supplied return URL. `ClientPaymentAttemptTransaction.begin(CheckoutAuthorizationService.Authorized, PaymentAccount, String key, String requestId, String previousFailedCharge): PaymentAttempt` is atomic; reuses unresolved client attempt for same checkout/account, even across a new request key. Confirm scope required. Customer identity берётся только из сохранённого Payment, привязанного к checkout capability, и повторно сравнивается с immutable client-attempt pin; anonymous Donation имеет явный null branch. Новый Customer bearer в том же Authorization header не вводится, клиент не передаёт Customer ID в body.

- [ ] **1 RED:** MockMvc tests keep checkout authority in headers/capabilities, never in body. Body is empty. Test missing/duplicate auth/idempotency/Origin headers, expired session, wrong Site/payment token, malformed stored Customer pin, disabled Account and changed binding; all return no secret/no provider financial POST. Add concurrent two-key request and completed-payment refusal tests.

```java
@Test void clientCapabilityNeverExposesItsSecretThroughToString() {
    String sentinel = "pi_owned_secret_" + UUID.randomUUID();
    var capability = new ClientPaymentCapability("pk_test_" + "B".repeat(32), sentinel);
    assertThat(capability.toString()).isEqualTo("ClientPaymentCapability[redacted]");
    assertThat(capability.clientSecret()).isEqualTo(sentinel);
}
```

- [ ] **2 Проверить RED:** `./mvnw -Dtest=StripeClientSessionIntegrationTest,ClientPaymentAttemptIntegrationTest test`.

- [ ] **3 GREEN transport:** `POST /api/v1/public/checkouts/{id}/client-session` returns `200`, `Cache-Control: no-store`; добавить canonical route и CORS POST с Authorization/Idempotency-Key в existing dynamic policy, использовать existing CONFIRM rate-limit bucket. Encoded/slash/matrix aliases по-прежнему отклоняются. Exact response:

```json
{"checkout_id":"uuid","payment_id":"uuid","attempt_id":"uuid","provider_type":"STRIPE","confirmation_mode":"CLIENT_PROVIDER","publishable_key":"pk_test_…","client_secret":"pi_…_secret_…","expires_at":"ISO8601","return_url":"https://shop.example/payment-return"}
```

Schema literals выше — документационные prefixes, не accepted credentials. Validation errors redact payload. Ordinary checkout DTOs возвращают provider/mode и `{}`; исправить их default `toString` без переноса secret туда. Для client-session добавить отдельный idempotency operation scope и resource type ATTEMPT; DB CHECK расширить новой migration V23, не старой migration.

- [ ] **4 GREEN orchestration:** authorize → prepare missing original intent (включая Donation/dynamic handoff) → retrieve verified state outside transaction → transaction reserve/reuse attempt+watermark+safe idempotency receipt → retrieve capability same account/intent → final authority recheck → return. Secret никогда не сохраняется в idempotency receipt. При authority expiry во время network response не выдавать capability. UNKNOWN/processing не инициируют новую оплату; requires_action может восстановить тот же pending flow.

- [ ] **5 GREEN exact URL policy:** migration создаёт `checkout_return_urls(site_id,origin,return_url,version)`, FK на allowed origin, уникальная row Site+Origin; reject userinfo, fragment, encoded control characters и чужой origin. Admin provisioning endpoint будет в Task 15; tests seed policy через adapter. Не строить URL из Host/X-Forwarded-Host. При отсутствии URL отказ до выдачи secret.

- [ ] **6 GREEN/commit:** verify serialized success, exceptions, record `toString`, journals/idempotency rows never contain sentinel. `/confirm` Stripe rejects with capability error; Fake flow unchanged. Commit `feat: issue scoped ephemeral Stripe client sessions`.

## Task 8 Exact-byte Stripe webhook admission в существующий inbox

**Files:** Create `M/V24__provider_reference_receipts.sql`, `B/payments/application/model/ProviderReferenceReceipt.java`, `B/provider/adapter/out/stripe/StripeWebhookVerifier.java`, `B/provider/adapter/in/web/StripeWebhookController.java`, `B/provider/adapter/in/web/StripeWebhookAdmissionFilter.java`; Modify `B/payments/application/port/out/ProviderInboxPort.java`, `B/payments/adapter/out/persistence/ProviderInboxPersistenceAdapter.java`, `B/admin/adapter/in/web/SecurityConfiguration.java`; Test `T/provider/adapter/in/web/StripeWebhookAdmissionTest.java`, `T/payments/adapter/out/persistence/ProviderReferenceReceiptIntegrationTest.java`.

**Interfaces:** `StripeWebhookVerifier.verify(byte[] raw, String signature, PaymentAccount account, Instant receivedAt): Optional<ProviderReferenceReceipt>`, empty only for signed unsupported event type; static `timestampAllowed(Instant timestamp, Instant now): boolean` compares absolute difference <=300 seconds. `insertReference/reference` contracts above. Signature clock — dedicated qualified real UTC clock; tests inject dedicated fixed real clock without changing business clock.

- [ ] **1 RED:** use HMAC-SHA256 generated signing key over timestamp + `.` + exact bytes. Test altered whitespace/body, malformed UTF-8, oversize streamed body без Content-Length, >4KiB signature, duplicate headers, timestamp ±301s, wrong whsec, live/connect event, wrong API version, wrong Account и arbitrary URL path.

```java
@Test void futureTimestampIsRejectedEvenWithValidMac() {
    var now = Instant.parse("2026-10-14T12:00:00Z");
    assertThat(StripeWebhookVerifier.timestampAllowed(now.plusSeconds(301), now)).isFalse();
    assertThat(StripeWebhookVerifier.timestampAllowed(now.plusSeconds(300), now)).isTrue();
}
```

- [ ] **2 Проверить RED:** `./mvnw -Dtest=StripeWebhookAdmissionTest,ProviderReferenceReceiptIntegrationTest test`.

- [ ] **3 GREEN admission:** exact route `/api/v1/provider-webhooks/stripe/{accountKey}`, POST only, JSON media only, bounded stream before MVC deserialization. Permit/CSRF exemption только для этого exact POST matcher, не `/provider-webhooks/**`. Unknown/inactive account — bounded rejection; account key chooses configured signing secret, never request header. Signature over exact bytes; verify UTF-8 with REPORT, timestamp future and stale bound separately (не полагаться на SDK stale-only behavior).

- [ ] **4 GREEN receipt persistence:** добавить `event_family='PROVIDER_REFERENCE'`, object type/provider event/API version и safe typed receipt fields в существующий `provider_inbox`; сразу расширить CHECK для DEAD outcome этой family после 12 attempts, сохранив прежние PAYMENT/SUBSCRIPTION constraints, payload shape и workers. UNIQUE provider+Account+event ID уже используется. `2xx` только после durable insert, duplicate safe `2xx`; store failure `5xx`. No payment transition and no provider network request в HTTP handler. Unsupported signed type → `204`, без insert.

- [ ] **5 GREEN admission errors:** safe `400/404/413/415/405` без body/signature в Problem detail; duplicate headers не склеиваются. При insert использовать real event/received timestamps; business clock не определяет signature validity. Allowlist ровно из spec §9; snapshot API mismatch fail-closed.

- [ ] **6 Commit:** `feat: admit signed Stripe reference receipts durably`.

## Task 9 Inbox resolution и единственный финансовый transition path

**Files:** Create `B/payments/application/model/ResolvedProviderReceipt.java`, `B/payments/application/port/out/ProviderInboxResolutionPort.java`, `B/provider/adapter/out/stripe/StripeInboxResolutionAdapter.java`, `B/payments/application/service/ProviderReferenceInboxWorker.java`, `B/payments/application/service/ClientAttemptPolicy.java`; Modify `B/payments/adapter/out/persistence/ProviderInboxPersistenceAdapter.java`, `B/payments/application/service/PaymentTransitionService.java`, `B/payments/application/service/RefundTransitionService.java`, `B/payments/adapter/in/configuration/ProviderInboxConfiguration.java`, `B/payments/adapter/in/configuration/ProviderInboxScheduler.java`; Test `T/payments/application/service/StripeInboxIntegrationTest.java`, `T/payments/application/service/StripeAttemptRecoveryIntegrationTest.java`.

**Interfaces:** Resolution contract above; `PaymentTransitionService.processVerified(Claim, ResolvedProviderReceipt): Result` applies a previously retrieved proof in a short transaction, verifying live claim + current Account/reference/Payment binding again. Existing Fake `process(Claim)` and subscription cash entry remain. New worker uses same inbox storage, not a separate queue/engine.

- [ ] **1 RED:** genuine Postgres cases: duplicate event, different events same success, out-of-order processing/decline after success, event before mapping commit, foreign amount/account, crash after journal insert before complete, lost claim. Explicit old failure guard:

```java
@Test void consumedFailureCannotCloseNewClientAttempt() {
    assertThat(ClientAttemptPolicy.isNewFailure("ch_old", "ch_old")).isFalse();
    assertThat(ClientAttemptPolicy.isNewFailure("ch_new", "ch_old")).isTrue();
    assertThat(ClientAttemptPolicy.isNewFailure(null, "ch_old")).isFalse();
}
```

`ClientAttemptPolicy.isNewFailure(String observed, String watermark): boolean` возвращает `observed != null && !observed.equals(watermark)`; application дополнительно проверяет ownership и отсутствие consumed Charge receipt. Predicate не является самостоятельным доказательством денежного факта.

- [ ] **2 Проверить RED:** `./mvnw -Dtest=StripeInboxIntegrationTest,StripeAttemptRecoveryIntegrationTest test`.

- [ ] **3 GREEN worker phases:** claim/read bounded safe receipt → resolve provider current object outside transaction → verify exact local mapping/operation facts → short transaction recheck claim/current references → apply transition/Charge receipt → append journal/outbox → complete claim. Current snapshot `succeeded` wins even if triggering event was old failure. Never fetch provider while holding payment/refund row lock.

- [ ] **4 GREEN state policy:** выделить `ClientAttemptPolicy` и явный Stripe verified path внутри общего transition service. Убрать предположение, что rank запрещает переход requires_action/processing → method required после проверенного нового decline. Такое восстановление закрывает только eligible client attempt, не terminal Payment. Fake rank semantics сохраняются отдельной веткой. Terminal success/refund facts immutable; old failure watermark/consumed receipt no-op. Новый attempt не создаётся самим worker.

- [ ] **5 GREEN unmatched/retry:** чужой object не создаёт Payment. Потенциальный orphan связывается с existing original operation только если acknowledged dispatch receipt уже содержит этот reference либо recovery проверил original creation event с тем же idempotency key. Mode/amount/metadata fingerprint дополнительно сравниваются, но не заменяют proof исходного request. При отсутствии такого proof Task 11 восстанавливает original mapping через bounded same-key replay, worker пока оставляет UNRESOLVED_OBJECT. Backoff 5/10/20/40/80/160/300s, максимум 12 attempts и DEAD outcome для PROVIDER_REFERENCE. Schema support уже добавляется в V24 в Task 8; после commit V24 не редактировать. State/hash canonical без raw body. Expired claim не может complete/apply; другой worker продолжает после lease.

- [ ] **6 Commit:** `feat: reconcile Stripe inbox receipts through verified transitions`.

## Task 10 Stripe refunds и сохранение резерва при неизвестном результате

**Files:** Create `B/provider/adapter/out/stripe/StripeRefundRequests.java`, `B/provider/adapter/out/stripe/StripeRefundSnapshotMapper.java`; Modify `B/provider/adapter/out/stripe/StripePaymentProviderAdapter.java`, `B/payments/application/service/RefundCoordinator.java`, `B/payments/application/service/RefundTransitionService.java` only for verified evidence; Test `T/provider/adapter/out/stripe/StripeRefundProviderAdapterTest.java`, `T/payments/application/service/StripeRefundIntegrationTest.java`.

**Interfaces:** Existing `ProviderRefundRequest/Lookup` and `ProviderResult/State`; Task 3 kind REFUND_CREATE. Snapshot evidence money — refund amount, paymentReference — original intent, chargeReference — linked captured charge. `requestRefund` returns ACCEPTED/PROCESSING/UNKNOWN, not synchronous SUCCEEDED. `StripeRefundSnapshotMapper.normalizedStatus(String): ProviderState.Status` maps unknown provider states to UNKNOWN.

- [ ] **1 RED:** test partial/full, amount mismatch, two parallel partial reservations, webhook-before-response, timeout после создания refund, duplicate same key, wrong charge/account и different amount with same key. При unknown reserved amount остаётся недоступным.

```java
@Test void pendingOrUnrecognizedRefundCannotBecomeSuccessful() {
    assertThat(StripeRefundSnapshotMapper.normalizedStatus("pending"))
        .isEqualTo(ProviderState.Status.PROCESSING);
    assertThat(StripeRefundSnapshotMapper.normalizedStatus("requires_action"))
        .isEqualTo(ProviderState.Status.PROCESSING);
    assertThat(StripeRefundSnapshotMapper.normalizedStatus("future_status"))
        .isEqualTo(ProviderState.Status.UNKNOWN);
}
```

- [ ] **2 Проверить RED:** `./mvnw -Dtest=StripeRefundProviderAdapterTest,StripeRefundIntegrationTest test`.

- [ ] **3 GREEN create:** refund parent lock/reservation уже существует; adapter reserve+claim Task 3, verified captured intent/charge, request amount minor из Refund, deterministic refund operation key. Local reason `requested_by_customer`/duplicate/fraudulent mapping только через явный allowlist; arbitrary technical note не отправлять Stripe. Не оперировать aggregate charge refunded total вместо individual Refund.

- [ ] **4 GREEN mapping:** `pending/requires_action` сохраняют pending reservation; `succeeded` становится success только через verified inbox/retrieve; `failed/canceled` освобождают резерв только после подтверждения конкретного Refund. Network/5xx и ambiguous errors → UNKNOWN, не TEMPORARILY_UNAVAILABLE, которое существующий acknowledge ошибочно мог бы превратить в FAILED. Идентификатор из acknowledgement сохраняется, journal success не создаётся.

```java
var params = RefundCreateParams.builder()
    .setPaymentIntent(payment.providerPaymentReference())
    .setAmount(refund.money().minorUnits())
    .putMetadata("refund_id", refund.id().value().toString()).build();
var result = client.v1().refunds().create(params,
    RequestOptions.builder().setIdempotencyKey(operation.operationKey()).build());
```

- [ ] **5 GREEN regressions:** successful-refund sums derive individual immutable Refunds, partial/full Payment statuses remain consistent; duplicate event/reconcile no second callback. Failed/pending refund не меняет successful sum. Existing Fake refunds и subscription-linked Fake refunds остаются прежними.

- [ ] **6 Commit:** `feat: add idempotent Stripe refunds with verified settlement`.

## Task 11 Recovery scheduler и manual reconciliation

**Files:** Create `B/payments/application/port/out/PendingProviderRecoveryPort.java`, `B/payments/adapter/out/persistence/PendingProviderRecoveryPersistenceAdapter.java`, `B/payments/application/service/StripeRecoveryService.java`, `B/provider/adapter/in/configuration/StripeRecoveryScheduler.java`, `B/provider/application/port/in/RecoverProviderOperationUseCase.java`, `B/provider/application/model/RecoverProviderOperationCommand.java`, `B/provider/application/port/out/ProviderRecoveryProofPort.java`, `B/provider/application/model/RecoveredProviderProof.java`, `B/provider/adapter/out/stripe/StripeRecoveryProofAdapter.java`; Modify `B/payments/application/service/ReconcilePaymentService.java`, `B/payments/application/service/AdminReconciliationService.java`, `B/provider/adapter/out/persistence/JdbcProviderOperationStore.java` for lease release/backoff; Test `T/payments/application/service/StripeRecoveryIntegrationTest.java`, `T/provider/adapter/in/configuration/StripeRecoverySchedulerTest.java`.

**Interfaces:** `PendingProviderRecoveryPort.claim(String owner, Instant now, int limit, Duration lease): List<UUID>` returns original provider operation IDs for missing mapping/UNKNOWN; service `runOnce(): void`, `recover(UUID operationId): void`. `StripeRecoveryService.mayReplayMissingReference(ProviderOperation.Status status, String reference, Instant firstDispatchedAt, Instant now): boolean` requires UNKNOWN/DISPATCHING, null reference, non-null first dispatch and Task 3 write-window predicate. Existing reconcile use case остаётся для known payment references. Scheduler enabled only with Stripe Sandbox flag, limit 20 per run, fixed delay 30s, bounded retries 12; retry deadlines используют real UTC clock. Task 3 migration сразу включает `recovery_attempts`, `next_attempt_at`, `safe_error_code`, `manual_review_required` и owner lease columns, чтобы committed V21 не дописывалась в этой задаче.

Manual interface: `RecoverProviderOperationUseCase.recover(RecoverProviderOperationCommand): void`; command содержит UUID operationId/actorId, String providerReference/creationEventId/idempotencyKey/requestId. `ProviderRecoveryProofPort.verify(ProviderOperation, String providerReference, String creationEventId): RecoveredProviderProof` делает только provider GET. Proof — record(UUID operationId, PaymentAccountId accountId, String providerReference, String fingerprint, String creationEventId). `StripeRecoveryService` реализует use case отдельным overload, сохраняя safe Audit/idempotency receipt; UI wiring добавляется в Task 15.

- [ ] **1 RED:** restart, lost webhook, unknown create/refund before reference persisted, frozen Fake business time, retry window boundary, concurrent workers и exception before final transaction. Test stale lease cannot apply new state and failed retrieve releases or expires owned lease safely.

```java
@Test void missingReferenceBeyondWindowRequiresManualReview() {
    Instant first = Instant.parse("2026-10-14T10:00:00Z");
    assertThat(StripeRecoveryService.mayReplayMissingReference(
        ProviderOperation.Status.UNKNOWN, null, first, first.plus(Duration.ofDays(1))))
        .isFalse();
    assertThat(StripeRecoveryService.mayReplayMissingReference(
        ProviderOperation.Status.ACKNOWLEDGED, "pi_owned", first, first.plusSeconds(60)))
        .isFalse();
}
```

- [ ] **2 Проверить RED:** `./mvnw -Dtest=StripeRecoveryIntegrationTest,StripeRecoverySchedulerTest test`.

- [ ] **3 GREEN known references:** use original Account binding + retrieve evidence Task 6/10, apply same verified path as inbox, never fake event success. `finally` release only own lease where valid; do not release another worker's lease. Unknown financial POST does not count as safe read retry.

- [ ] **4 GREEN missing references:** acknowledge receipt → verified retrieve/rebind; unknown within 23h → same request key/fingerprint only; outside window → no financial POST, mark manual-review-required. Manual recover проверяет event через Stripe Events GET: Account/mode, expected creation event type/object ID и `event.request.idempotency_key == original operationKey`, затем retrieves current object и сравнивает original immutable facts/fingerprint. Creation event доказывает binding, не success; денежный факт проходит обычную сверку. Отсутствующий/expired event или metadata-only совпадение оставляют UNKNOWN, не разрешают новое списание. No scanning/reparenting чужих объектов «по похожей сумме». Integration tests проверяют wrong key/object/account, duplicate manual command и отсутствие financial POST после окна.

- [ ] **5 GREEN observability:** safe error categories, attempts/age/next retry, journal/outbox no-op on duplicate; no IDs/secrets as metric labels. Manual reconcile endpoint resumes original operation, не создаёт новый key. После 12 failed retries Admin видит unresolved/dead; автоматический циклический restart счётчик не обнуляет.

- [ ] **6 Commit:** `feat: recover Stripe operations without duplicate writes`.

## Task 12 SDK contracts и безопасное восстановление checkout

**Files:** Modify `frontend/sdk/src/contracts.ts`, `frontend/sdk/src/client.ts`, `frontend/sdk/src/storage.ts`, `frontend/sdk/src/providers/provider-driver.ts`, `frontend/sdk/src/providers/fake-driver.ts`, `frontend/sdk/src/index.ts`, `frontend/sdk/src/errors.ts`, `frontend/sdk/src/client.test.ts`, `frontend/sdk/src/storage.test.ts`, `frontend/sdk/src/test/ssr-import.test.ts`; Create `frontend/sdk/src/providers/client-session.ts`, `frontend/sdk/src/providers/client-session.test.ts`.

**Interfaces:** `CheckoutSession` becomes discriminated Fake/Stripe union with same identity/state and empty provider payload; no client secret field. Add `client.prepareClientPayment(session: StripeCheckoutSession, options?: {idempotencyKey?: string; signal?: AbortSignal}): Promise<ClientPaymentSession>`. Final frontend capability interface:

```ts
export interface ClientPaymentSession {
  checkoutId: string; paymentId: string; attemptId: string;
  publishableKey: string; clientSecret: string;
  expiresAt: string; returnUrl: string;
}
```

- [ ] **1 RED:** exact wire parser tests missing/extra fields, wrong provider/mode, wrong IDs/expiry/return URL, oversized invalid UTF-8 response, duplicate identity и stale response. `prepareClientPayment` uses existing bounded request helper, `redirect:'error'`, no-store, checkout bearer and idempotency key, empty POST body. Wrong Customer token family never substitutes checkout token.

- [ ] **2 Проверить RED:** из `frontend/sdk`: `pnpm test --run src/providers/client-session.test.ts src/client.test.ts src/storage.test.ts src/test/ssr-import.test.ts`.

- [ ] **3 GREEN contracts:** parse `FAKE/SERVER/{}` and `STRIPE/CLIENT_PROVIDER/{}` exactly; bind client-session response to locally known checkout/payment before accepting capability. `client.confirm` remains Fake-only; Stripe uses new driver, so not forced into server confirm signature. Export new safe types without importing Stripe runtime into server bundle.

- [ ] **4 GREEN storage:** new recovery version 2 stores provider/mode plus existing approved checkout identity/token/state only; never key/client secret/return capability. Legacy version 1 is accepted as Fake only. Recovery performs authorized read and rejects changed provider/account/context before action. Donations remain non-persistent, consistent with existing privacy behavior.

```ts
it('rejects provider substitution during recovery', () => {
  expect(() => assertProviderBinding('STRIPE', 'CLIENT_PROVIDER', 'FAKE', 'SERVER'))
    .toThrow(PaymentSdkError);
});
```

`assertProviderBinding(expectedProvider, expectedMode, actualProvider, actualMode): void` is a new exported-internal function in `contracts.ts`; use it in recovery/read, not only tests.

- [ ] **5 GREEN:** same focused suite plus `pnpm typecheck`, ensure `JSON.stringify` of stored record has no generated sentinel client secret. Locale/theme update never POSTs; user restart does not auto-create a fresh Payment.

- [ ] **6 Commit:** `feat: add typed ephemeral Stripe client-session contracts`.

## Task 13 Lazy Stripe driver и Payment Element lifecycle

**Files:** Modify `frontend/sdk/package.json`, `frontend/pnpm-lock.yaml`, `frontend/sdk/src/test/ssr-import.test.ts`; Create `frontend/sdk/src/providers/stripe-loader.ts`, `frontend/sdk/src/providers/stripe-driver.ts`, `frontend/sdk/src/providers/stripe-driver.test.ts`, `frontend/sdk/src/providers/stripe-loader.test.ts`, `frontend/sdk/src/providers/stripe-appearance.ts`, `frontend/sdk/src/providers/stripe-appearance.test.ts`.

**Interfaces:** `loadStripeClient(publishableKey: string): Promise<Stripe|null>` uses `@stripe/stripe-js/pure` via dynamic import. `mountStripePayment(options: MountStripePaymentOptions): Promise<StripePaymentHandle>`; types ниже определяются в `stripe-driver.ts` и используют type-only imports Stripe SDK и Task 12 contracts.

```ts
export interface MountStripePaymentOptions {
  container: HTMLElement; capability: ClientPaymentSession; signal: AbortSignal;
  appearance: StripeElementsOptions['appearance'];
  refresh: () => Promise<CheckoutState>;
}
export interface StripePaymentHandle {
  confirm(): Promise<CheckoutState>;
  destroy(): void;
}
```

Handle never returns Stripe raw result.

- [ ] **1 RED:** Vitest dependency mock на loader boundary, не whole payment flow. Проверить null/reject, double click, two accounts, abort during pending loader, malicious loader error text и browser success while backend remains PROCESSING. Main new regression:

```ts
it('destroys a late Element after abort without publishing success', async () => {
  const {promise, resolve} = Promise.withResolvers<Stripe|null>();
  vi.mocked(loadStripeClient).mockReturnValue(promise);
  const refresh = vi.fn<() => Promise<CheckoutState>>();
  const capability: ClientPaymentSession = {
    checkoutId: '11111111-1111-4111-8111-111111111111',
    paymentId: '22222222-2222-4222-8222-222222222222',
    attemptId: '33333333-3333-4333-8333-333333333333',
    publishableKey: 'pk_test_' + 'B'.repeat(32), clientSecret: 'pi_owned_secret_sentinel',
    expiresAt: new Date(Date.now() + 60000).toISOString(),
    returnUrl: 'https://shop.example/payment-return'
  };
  const controller = new AbortController();
  const container = document.createElement('div');
  const loading = mountStripePayment({container, capability, signal: controller.signal,
    appearance: {}, refresh});
  controller.abort();
  const elements = vi.fn();
  resolve({elements} as unknown as Stripe);
  await expect(loading).rejects.toMatchObject({name: 'AbortError'});
  expect(elements).not.toHaveBeenCalled();
  expect(container.childElementCount).toBe(0);
  expect(refresh).not.toHaveBeenCalled();
});
```

В начале test module применить `vi.mock('./stripe-loader.js', () => ({loadStripeClient: vi.fn()}))`; реальный driver импортируется без подмены. Существующий abort contract — `DOMException` с `name='AbortError'`, не выдуманный PaymentSdkError code. Отдельный test abort после mount проверяет element.destroy и подавление позднего confirm.

- [ ] **2 Проверить RED:** `pnpm test --run src/providers/stripe-driver.test.ts src/providers/stripe-loader.test.ts src/providers/stripe-appearance.test.ts src/test/ssr-import.test.ts`.

- [ ] **3 GREEN dependency:** `pnpm add --save-exact @stripe/stripe-js@10.1.0` из SDK при execution; lockfile обновлять package manager-ом. Версия подтверждена [официальным release](https://github.com/stripe/stripe-js/releases/tag/v10.1.0). Не устанавливать React Stripe.js для framework-neutral SDK. Dynamic pure loader не добавляет script при импорте index/SSR/Fake path.

- [ ] **4 GREEN driver:** `stripe.elements({clientSecret, appearance})`, `elements.create('payment', {wallets:{applePay:'never',googlePay:'never'}})`; mount только после abort/generation checks. Confirm uses `stripe.confirmPayment({elements, confirmParams:{return_url: capability.returnUrl}, redirect:'if_required'})`; raw result discarded after mapping safe user-action error. Даже при Stripe succeeded возвращается `await refresh()` локального backend, не fabricated SUCCEEDED. Не передавать AbortSignal туда, где Stripe API его не поддерживает: abort отменяет local work и suppresses completion, не обещает отменить списание.

- [ ] **5 GREEN cleanup:** destroy idempotent, clear capability references, no logger/DOM attributes/storage/custom events with secrets. Appearance принимает явный allowlist design tokens, не произвольный CSS/URL. Метод family ограничен картой и wallets disabled. Test key mismatch creates controlled error and never a second client-session/PaymentIntent.

- [ ] **6 Commit:** `feat: mount Stripe Payment Element with safe client lifecycle`.

## Task 14 Встраивание Stripe в payment и donation компоненты

**Files:** Modify `frontend/sdk/src/widget/generic-payment-widget.ts`, `frontend/sdk/src/widget/generic-payment-widget.test.ts`, `frontend/sdk/src/widget/styles.ts`, `frontend/sdk/src/donations/generic-donation.ts`, `frontend/sdk/src/donations/generic-donation.test.ts`, `frontend/sdk/src/donations/styles.ts`, `frontend/sdk/README.md`; Create `frontend/sdk/src/widget/checkout-provider-view.ts`, `frontend/sdk/src/widget/checkout-provider-view.test.ts`.

**Interfaces:** `CheckoutProviderView` class: `constructor(client: GenericPaymentClient, host: HTMLElement)`, `mount(session: CheckoutSession, signal: AbortSignal): Promise<void>`, `confirm(): Promise<CheckoutState>`, `destroy(): void`. It is a small component-owned wrapper around Fake button/Stripe handle. Only this wrapper holds ephemeral capability; parent component retains existing safe session. Both widget and donation UI use this wrapper, not two copies of Stripe logic. Parent adds `#providerView?: CheckoutProviderView` and destroys it on existing generation cleanup.

- [ ] **1 RED:** render card form for Stripe, original Fake button flow, anonymous/identified Donation, changing campaign/Site/API while loading, Customer logout, disconnect, reconnect, stale result, expired capability, keyboard focus and disabled double submit. No automatic second financial POST after transport unknown.

```ts
it('does not render provider values before a checkout is authorized', () => {
  defineGenericPaymentWidget();
  const widget = document.createElement('generic-payment-widget');
  widget.setAttribute('api-base-url', 'https://api.test');
  widget.setAttribute('site-key', 'test-site');
  widget.setAttribute('price-key', 'lifetime');
  const fetchSpy = vi.fn(); vi.stubGlobal('fetch', fetchSpy);
  document.body.append(widget);
  expect(widget.shadowRoot!.innerHTML).not.toMatch(/clientSecret|pk_test_|pi_.*_secret/);
  expect(fetchSpy).not.toHaveBeenCalled();
  widget.remove();
});
```

Этот initial-state regression дополняет RED на новый Stripe render с controlled client-session response: Pay button не появляется до mount-ready, stable Element host сохраняется между status updates.

- [ ] **2 Проверить RED:** `pnpm test --run src/widget/generic-payment-widget.test.ts src/widget/checkout-provider-view.test.ts src/donations/generic-donation.test.ts`.

- [ ] **3 GREEN layout:** создать stable dedicated Element container один раз на generation. Не заменять его через `innerHTML`/re-render после mount. At session creation choose Fake/Stripe by validated discriminator; Stripe calls prepare once then lazy mount; Pay calls driver's confirm. Processing/unknown uses existing watch/get; disable payment button while unresolved. Destroy before removing DOM container.

- [ ] **4 GREEN UX:** status `aria-live`, labeled submit, keyboard focus on validation failure, theme tokens consistent with existing site theme, en/pt Stripe locale derived without initiating operations. Browser events stay frozen safe identifiers/state only. UI copy говорит «проверяем оплату», пока backend не подтвердил.

```ts
const safe = await this.#providerView!.confirm();
if (!this.#current(generation)) return;
this.#apply(safe, true);
```

Имена локальных parent methods сохранить существующими; snippet показывает обязательный порядок generation check перед UI/event update. Не копировать client secret в parent session или exception.

- [ ] **5 GREEN:** focused tests плюс полный `pnpm test --run` и `pnpm typecheck` в SDK. Donation message всё ещё передаётся только существующему central API, не Stripe metadata/driver.

- [ ] **6 Commit:** `feat: embed Stripe checkout in reusable payment and donation UI`.

## Task 15 Admin inspection и безопасная demo return page

**Files:** Create `B/payments/adapter/in/web/CheckoutReturnUrlAdminController.java`, `B/payments/application/service/CheckoutReturnUrlAdminService.java`, `B/provider/adapter/in/web/StripeOperationAdminController.java`, `B/provider/application/port/in/InspectProviderOperationsUseCase.java`, `B/provider/application/model/ProviderOperationView.java`, `B/provider/application/service/InspectProviderOperationsService.java`, `T/provider/adapter/in/web/StripeOperationAdminIntegrationTest.java`, `T/payments/adapter/in/web/CheckoutReturnUrlAdminIntegrationTest.java`, `frontend/admin/src/components/payments/ProviderOperationPanel.tsx`, `frontend/admin/src/components/payments/ProviderOperationPanel.test.tsx`, `examples/demo-site/src/payment-return.ts`, `examples/demo-site/src/payment-return.test.ts`, `examples/demo-site/payment-return.html`; Modify `B/payments/application/model/AdminPaymentViews.java`, `B/payments/adapter/in/configuration/AdminPaymentConfiguration.java`, `B/payments/adapter/out/persistence/CheckoutReturnUrlPersistenceAdapter.java`, `frontend/admin/src/components/payments/PaymentDetails.tsx`, `frontend/admin/src/lib/api/schema.d.ts`, `examples/demo-site/src/main.ts`, `examples/demo-site/vite.config.ts`, `examples/demo-site/nginx.conf`, `examples/demo-site/package.json`, `examples/demo-site/pnpm-lock.yaml`.

**Interfaces:** `GET /api/v1/admin/provider-operations?payment_id=<id>` requires ADMIN and returns safe original operation status/reference/age/next_retry/safe_error only; no credentials_ref/key/client secret. `InspectProviderOperationsUseCase.inspect(PaymentId): List<ProviderOperationView>`, `CheckoutReturnUrlAdminService.update(SiteId, URI origin, URI returnUrl, long expectedVersion, UUID actorId, String requestId): void`. `PUT /api/v1/admin/sites/{siteId}/checkout-return-urls` accepts bounded exact origin+return_url+expected_version, ADMIN+CSRF+audit. Query DTO fields: UUID operationId/paymentId/accountId, kind/status/reference, Instant firstDispatchedAt/retryBefore/nextAttemptAt, String safeErrorCode. `sanitizeStripeReturnLocation(location: URL): URL` clears query/hash entirely, preserves exact origin/path and never extracts authority.

`POST /api/v1/admin/provider-operations/{id}/recover` принимает только `provider_reference` и `creation_event_id`, требует ADMIN, CSRF и Idempotency-Key; controller вызывает Task 11 use case с server-derived actor/request ID. Это verified rebind исходной операции, не повторное создание intent/refund. OperationPanel содержит отдельное manual recovery dialog и объясняет, что новый платёж не создаётся; matching proof обязателен.

- [ ] **1 RED:** Admin role/CSRF/ownership/URL validation and secret sentinel tests; page mounts safe OperationPanel for unresolved state. Return cleanup test:

```ts
it('removes Stripe capability before application boot', () => {
  const url = new URL('https://shop.example/payment-return?payment_intent=pi_demo'
    + '&payment_intent_client_secret=generated-sentinel&redirect_status=succeeded');
  const safe = sanitizeStripeReturnLocation(url);
  expect(safe.search).toBe('');
  expect(safe.hash).toBe('');
});
```

- [ ] **2 Проверить RED:** backend `./mvnw -Dtest=StripeOperationAdminIntegrationTest,CheckoutReturnUrlAdminIntegrationTest test`, admin `pnpm test --run src/components/payments/ProviderOperationPanel.test.tsx`, demo unit `pnpm exec vitest run src/payment-return.test.ts` после `pnpm add --save-dev --save-exact vitest@4.1.11` из demo package. Package lock обновляет package manager, не ручная правка.

- [ ] **3 GREEN Admin:** application read use case извлекает безопасную projection, без прямого SDK вызова; target existing Payment immutable Account, не текущий Site Account в обход original binding. Existing reconcile/refund buttons сохраняют idempotency и CSRF. Return URL updates используют optimistic version и audit без secrets. Regenerate OpenAPI types против собственного ephemeral backend, не чужого процесса; не добавлять client-session schema в админский публичный вывод с secret example.

- [ ] **4 GREEN return entry:** отдельный HTML entry, сначала meta `name="referrer" content="no-referrer"`, затем inline `history.replaceState(null,'',location.pathname)`, затем module import приложения; policy header также `Referrer-Policy: no-referrer`, analytics отсутствуют. Nginx return location logging disabled/query-free; Vite dev access logs no raw URL. После очистки page использует ранее сохранённую checkout authority + read; при отсутствии безопасное сообщение об отсутствии session и login/recovery action. Query `redirect_status=succeeded` никогда не создаёт paid UI/access.

- [ ] **5 GREEN demo:** явный Sandbox badge, backend-configured Stripe Site/catalog/donation fixtures, no static `VITE_*` key, separate return page build entry. Unit and browser tests подтверждают cleanup before application boot, no network Referer query, no auto-create Payment при lost authority. Anonymous Donation после redirect без memory authority показывает безопасный fallback, не принимает secret из URL как login.

- [ ] **6 Commit:** `feat: expose safe Stripe operations and return recovery UI`.

## Task 16 Cucumber regression и opt-in Stripe Sandbox acceptance

**Files:** Create `backend/src/test/resources/features/stripe_sandbox.feature`, `T/acceptance/StripeSandboxSteps.java`, `T/acceptance/StripeAcceptanceConfiguration.java`, `infrastructure/smoke-stage-5a.mjs`, `infrastructure/smoke-stage-5a.test.mjs`, `examples/demo-site/playwright.stripe-sandbox.config.ts`, `examples/demo-site/tests/stripe-sandbox.spec.ts`, `examples/demo-site/tests/support/stripe-sandbox-fixtures.ts`; Modify `T/acceptance/CucumberSpringConfiguration.java`, `examples/demo-site/playwright.config.ts`, `frontend/sdk/README.md`, `frontend/README.md`, `docs/local-development.md`. Не использовать ignored `.superpowers` helpers как required input свежего clone.

**Interfaces:** `runStripeSandboxSmoke(options): Promise<{paymentConfirmed:boolean; refundConfirmed:boolean; callbackVerified:boolean}>` safe booleans only; CLI requires `PAYMENT_STRIPE_SANDBOX_ENABLED=true` plus `PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true`. `options` содержит approved loopback endpoints и owned fixture identity, не literal keys в argv. Unit mode injects HTTP fixture; real mode uses platform credentials из explicit local process environment. Stripe server key остаётся только в backend: runner не загружает private Stripe env и не вызывает Stripe API напрямую.

- [ ] **1 RED offline acceptance:** Cucumber использует real Postgres/application и Task 2 HTTP fixture, default suite без пользовательских ключей. `StripeAcceptanceConfiguration` импортируется только в Cucumber test context: включает Stripe с generated synthetic keys и loopback fixture factory, сохраняет Fake registry. Никакого real Stripe network в default Maven. Scenario example:

```gherkin
@stripe-sandbox
Feature: Stripe one-time settlement preserves platform authority
  Scenario: Browser success is not a confirmed payment
    Given an authorized Stripe Sandbox checkout for 2000 EUR minor units
    When its browser confirmation reports success
    And Stripe has not yet confirmed the intent on the server
    Then the platform payment is not succeeded
    When a signed Stripe success receipt is reconciled with the matching captured charge
    Then exactly one payment succeeded fact and one Site callback exist
```

Добавить реальные step definitions через application/MockMvc/DB и SDK HTTP fixture, не mutable in-memory «paymentSucceeded» boolean. Cases cover catalog/dynamic, anonymous/identified Donation, decline/retry, SCA, duplicate/out-of-order, refund/unknown/window и mixed Fake/Stripe. Feature пометить `@stripe-sandbox`: это offline adapter scenarios. External Stripe acceptance находится только в opt-in Node/Playwright config и не входит в default Maven.

- [ ] **2 Проверить RED:** backend `./mvnw -Dtest=RunCucumberTest -Dcucumber.filter.tags=@stripe-sandbox test`; Node `node --test infrastructure/smoke-stage-5a.test.mjs`. Missing explicit opt-in must fail before any network/file key read.

- [ ] **3 GREEN safe runner:** unit tests reject redirects/nonloopback platform endpoints, missing ownership, provider/account mismatch и live prefixes, never print payloads. All platform credential-bearing requests use `redirect:error`, bounded response/timeouts. Return only safe booleans/phase codes; errors redact response/body/headers. Assertions используют safe booleans/counts, не raw capabilities/response objects: matcher failure также не должен раскрывать их. New fixture operations get stable IDs/keys persisted locally before first write, повтор после unknown не генерирует новые автоматически.

- [ ] **4 GREEN browser config:** separate config `workers:1`, `retries:0`, `fullyParallel:false`, screenshots/trace/video off, preserveOutput never, line-safe reporter. Default Playwright ignores Sandbox spec, preserving existing Fake config. Реальная browser test использует настоящий Stripe iframe, не route.fulfill для payment success. BeforeAll требует те же два opt-in flags и owned fixture metadata, отсутствие их прерывает run до browser/payment setup. Third-party 3DS selector changes дают явный failure/manual-check, не подмену mock proof.

- [ ] **5 Полная offline проверка:** выполнить команды ниже на собственном окружении; сначала генерировать private local issuer fixture по уже существующей инструкции, если clone свежий. Не читать/печатать private file или существующий `.env` в отчёт.

```bash
# cwd: backend
./mvnw verify
# cwd: frontend/sdk
pnpm test --run
pnpm typecheck
pnpm build
# cwd: frontend/admin
pnpm test --run
pnpm typecheck
pnpm lint
pnpm build
# cwd: examples/demo-site
pnpm exec vitest run src/payment-return.test.ts
pnpm build
pnpm test:e2e -- --workers=1 --retries=0
# cwd: repository root
node --test --test-timeout=30000 --test-concurrency=1 infrastructure/*.test.mjs
```

External browser suite требует собственного running platform fixture с approved test seed. Не подключаться к неизвестным чужим localhost services. При sandbox `listen EPERM` запросить scoped escalation для loopback tests, не считать это application defect. Не оставлять hanging test servers.

- [ ] **6 Пользовательские credentials и listener:** после offline GREEN пользователь заполняет `.env.local` локально. `stripe login` интерактивен и выбирает тот же Sandbox; никаких ключей в чат/argv. Listener snapshot version проверить равной `2026-09-30.endive` до запуска. Не менять production account/default version ради test.

```bash
stripe listen --events payment_intent.processing,payment_intent.requires_action,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,refund.created,refund.updated,refund.failed --forward-to http://127.0.0.1:8080/api/v1/provider-webhooks/stripe/stripe-sandbox-default
docker compose --env-file infrastructure/.env --env-file infrastructure/stripe-sandbox.env.local -f infrastructure/compose.yaml -f infrastructure/compose.stripe-sandbox.yaml up -d --build
```

`stripe-sandbox-default` в endpoint — настроенный Account key; credential alias задаётся тем же именем для простоты, но это разные поля. Если Account key иной, взять его из verified provisioning result, не угадывать. `whsec_…` listener сохранить локально до backend start. Не использовать `--live`, `--latest` вместо version check, `--print-json` или `--skip-verify`. Команды не выполняются при написании/чтении этого плана.

- [ ] **7 Реальный Sandbox:** создать один owned Stripe Account/Site через Admin с server verification, настроить return URL, опубликовать один EUR one-time Price и Donation campaign; stable keys/IDs записать в Git-ignored smoke state без secrets/PII. Пройти card success, decline/retry same intent, 3DS, matching key pair, amount/site/source в Dashboard, signed webhook → journal → callback, partial/full refund и recovery missed webhook. Команды:

```bash
# cwd: repository root; opt-in flags set explicitly in local process environment
PAYMENT_STRIPE_SANDBOX_ENABLED=true PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true node infrastructure/smoke-stage-5a.mjs
# cwd: examples/demo-site
PAYMENT_STRIPE_SANDBOX_ENABLED=true PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true pnpm exec playwright test --config playwright.stripe-sandbox.config.ts
```

Use only [официальные Stripe test payment methods](https://docs.stripe.com/testing), не настоящую карту. Не очищать все Sandbox objects автоматически; отметить созданные fixtures и остановить только свои services/listener после проверки, volumes не удалять. Нет credentials/network/interactive SCA → эта приёмка остаётся pending, 5A не объявлять завершённым.

- [ ] **8 Review и commit:** проверить spec coverage §1–15, git diff/source completeness, staged secret audit с явно проверенными test-only sentinels, exact source exclusions, latest migration checksum и фактические test reports. Independent review по выбранному execution workflow; unresolved financial/security defects блокируют завершение. Commit `test: verify Stripe Sandbox payment lifecycle end to end`, push после review. Никакого live enablement в финальном отчёте.

## Итоговая приёмка и handoff

| Требование spec | Где реализуется и проверяется |
| --- | --- |
| §1–3 card-first, API pin, границы 5A | Tasks 2, 5, 13, 16 |
| §4 hexagonal/account-specific routing/catalog | Tasks 2–5, ArchitectureRulesTest |
| §5 private config/default Fake | Task 1, config tests и Task 16 fresh clone |
| §6 один intent/durable operation/unknown | Tasks 3, 5, 11 |
| §7 authority/client attempts/ephemeral capability | Tasks 6, 7, 12 |
| §8 Element/return/lifecycle/secret handling | Tasks 12–15 |
| §9 signature/inbox/out-of-order/mapping race | Tasks 8, 9 |
| §10 cash journal/refunds/Site attribution | Tasks 6, 9, 10, 16 |
| §11 bounded recovery/Admin/telemetry | Tasks 9, 11, 15 |
| §12 regression/Cucumber/Sandbox evidence | Все фокусные tests и Task 16 |
| §13 user login/keys/listener/cleanup | Tasks 1, 16 |
| §14–15 дальнейшие этапы и review gates | 5B/5C не реализуются этим планом |

План требует review пользователя и выбора execution method. Рекомендация — subagent-driven: взаимозависимых задач много, а ошибка idempotency/ownership может затронуть денежные факты; отдельный reviewer после каждой задачи оправдывает дополнительный расход контекста. Альтернатива — native execution основным агентом с независимым review всей ветки в конце; быстрее и дешевле, но замечания выявляются позже. До этого выбора implementation/config changes не выполняются.
