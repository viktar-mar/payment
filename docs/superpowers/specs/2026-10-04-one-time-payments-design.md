# Этап 2 — разовые платежи

Дата: 2026-10-04

## 1. Цель

Добавить к готовому Foundation/Catalog вертикальный поток разовых платежей, который полностью работает локально без Stripe-аккаунта через `FakePaymentProvider`, но сохраняет границы, необходимые для будущего Stripe adapter.

Этап должен поддерживать:

- гостевую оплату опубликованной каталожной цены;
- динамический платёж через доверенный Partner API;
- полный и частичный возврат;
- асинхронное подтверждение provider events;
- append-only журнал денежных фактов;
- идемпотентные команды;
- provider inbox и transactional outbox;
- подписанный callback на backend подключённого сайта;
- просмотр и управление платежами в admin;
- framework-agnostic TypeScript SDK и статический demo-site.

Stripe account, Stripe SDK и реальные денежные операции для этого этапа не требуются.

## 2. Исходное состояние и ограничения

Этап продолжает реализацию из:

- общей спецификации `docs/superpowers/specs/2026-09-12-generic-payment-platform-design.md`;
- Stage 1, описанного в `docs/superpowers/plans/2026-09-12-foundation-catalog-implementation.md`.

Сохраняются следующие ограничения:

- backend — Java 25, Spring Boot 4.1.1 и Maven Wrapper;
- backend остаётся модульным монолитом с hexagonal architecture;
- domain-код не зависит от Spring, JPA, HTTP, Jackson и provider SDK;
- PostgreSQL является transactional store;
- admin остаётся отдельным Next.js приложением;
- SDK не зависит от React и Next.js;
- денежные суммы хранятся в integer minor units;
- currency хранится как uppercase ISO-4217 code;
- идентификаторы доменных сущностей — UUID;
- все создаваемые timestamps получают время через внедрённый `Clock`;
- секреты поступают через environment или заменяемые secret ports;
- проект остаётся локальным: Git не инициализируется, commits и push не выполняются;
- каждая production-функция реализуется через red-green-refactor;
- Compose stack не обязан быть запущен во время проектирования и поднимается только для runtime-проверок.

## 3. Scope

### 3.1 Входит в этап

- новый backend-модуль `payments`;
- новый backend-модуль `webhooks` для provider inbox и site outbox;
- provider-neutral payment port и расширение fake provider;
- публичный каталожный checkout;
- Partner API для динамической суммы;
- nullable `customer_id` в модели Payment без создания Customer-модуля;
- site API keys со scopes и безопасной rotation;
- один активный callback endpoint на Site;
- callback secret rotation;
- refunds;
- ручная reconciliation отдельного Payment;
- admin payments UI;
- `frontend/sdk` как ESM-пакет;
- Web Component для каталожной оплаты;
- статический `examples/demo-site`;
- backend unit/integration/Cucumber tests;
- frontend unit/component/Playwright tests;
- обновлённый Docker Compose и smoke workflow.

### 3.2 Не входит в этап

- Stripe account и Stripe API integration;
- ввод карты, CVC или собственная имитация Stripe Payment Element;
- Customers и объединение платежей по личности;
- OAuth, OIDC, site JWT и email OTP;
- DonationCampaign и donation widget;
- subscriptions, trials и recurring billing execution;
- автоматическая периодическая reconciliation всего массива операций;
- несколько callback endpoints для одного Site;
- несколько администраторов и RBAC;
- автоматическое архивирование финансовых фактов;
- production deployment к конкретному cloud provider.

## 4. Согласованные решения

1. Текущий `Payment` является проекцией, а `PaymentEvent` — append-only финансовым журналом.
2. Полный event sourcing не используется: проекция сохраняется явно и обновляется транзакционно вместе с новым фактом.
3. Fake provider работает асинхронно по умолчанию.
4. Опциональный fake `auto-success` предназначен для smoke и быстрых локальных сценариев.
5. На этапе 2 платежи могут быть гостевыми; `customer_id` остаётся nullable до этапа 3.
6. Динамический платёж доступен только Partner API и требует `external_order_id`.
7. Один Site имеет не более одного активного callback endpoint.
8. Синхронный ответ browser/provider не считается окончательным денежным фактом.
9. Успешный provider event является источником подтверждения результата.
10. Ошибка callback не откатывает Payment и не удаляет PaymentEvent.

## 5. Архитектура модулей

### 5.1 `payments`

Модуль владеет:

- `Payment`;
- `PaymentAttempt`;
- `Refund`;
- `PaymentEvent`;
- state transitions и refund invariants;
- application use cases;
- idempotency платежных команд;
- REST adapters Public, Partner и Admin API;
- PostgreSQL adapters своих агрегатов и журнала.

Модуль обращается к Site, PaymentAccount и опубликованной Price только через application ports. Он не использует JPA repositories других модулей напрямую.

### 5.2 `webhooks`

Модуль владеет:

- provider inbox;
- обработкой provider events;
- outbox events для подключённых сайтов;
- callback deliveries;
- retry/backoff и состоянием `DEAD`;
- HMAC signing;
- callback configuration и encrypted secret versions.

Provider event handler вызывает application port модуля `payments`. Обновление Payment, вставка PaymentEvent и создание OutboxEvent выполняются в одной PostgreSQL-транзакции.

### 5.3 `provider`

Provider-neutral ports расширяются операциями:

- создать provider payment intent;
- получить актуальное состояние provider payment;
- создать refund;
- получить актуальное состояние provider refund.

Domain и application слои не знают provider-specific DTO. Adapter переводит внешние состояния в платформенные результаты.

`FakePaymentProvider` является единственной реализацией на этапе 2. Будущий Stripe adapter обязан реализовать тот же набор портов.

### 5.4 Frontend

- `frontend/admin` управляет платежами, refunds, fake simulations и callback configuration;
- `frontend/sdk` содержит framework-agnostic client, provider-client abstraction и Web Component;
- `examples/demo-site` подключает собранный SDK как обычный внешний сайт.

## 6. Domain model

### 6.1 Payment

`Payment` содержит:

- `payment_id`;
- `site_id`;
- nullable `customer_id`;
- `payment_account_id`;
- source: `CATALOG` или `DYNAMIC`;
- nullable `price_id` и неизменяемый snapshot версии цены;
- nullable `external_order_id`;
- amount minor units;
- currency;
- tax mode и nullable `tax_amount_minor` snapshot;
- status;
- nullable provider payment reference;
- refundable и successfully refunded amounts;
- optimistic-lock version;
- `created_at` и `updated_at`.

Обязательные поля Price snapshot: `price_id`, `price_key`, version, amount, currency и tax mode. Изменение каталога после создания Payment не меняет историческую сумму.

На этапе 2 Fake provider поддерживает `NONE` и `INCLUSIVE`: сохранённый Price amount является итоговой списываемой суммой, а отдельный tax breakdown не вычисляется. `EXCLUSIVE` и `STRIPE_TAX` требуют tax-capable provider и отклоняются до создания Payment с `PAYMENT_CAPABILITY_UNAVAILABLE`. Это исключает молчаливое неправильное начисление налога.

Статусы Payment:

```text
CREATED
REQUIRES_PAYMENT_METHOD
REQUIRES_ACTION
PROCESSING
SUCCEEDED
FAILED
CANCELLED
PARTIALLY_REFUNDED
REFUNDED
```

Подтверждённое состояние нельзя откатить запоздавшим событием в более раннее. `SUCCEEDED` может перейти только в refund-related состояния. `REFUNDED` является terminal state для денежных операций данного Payment.

Recoverable decline завершает текущий PaymentAttempt как `FAILED`, но переводит Payment в `REQUIRES_PAYMENT_METHOD`. Покупатель может начать новую попытку с новым confirm `Idempotency-Key`, не создавая новый Payment. `FAILED` на уровне Payment используется только для окончательного provider/business failure, после которого новый attempt запрещён.

### 6.2 PaymentAttempt

Каждая попытка обращения к provider создаёт отдельный `PaymentAttempt`:

- `attempt_id`;
- `payment_id`;
- порядковый номер;
- provider operation idempotency key;
- nullable provider intent reference;
- payment method family, если она безопасно известна;
- status;
- безопасный result code;
- timestamps.

Статусы попытки:

```text
CREATED
REQUIRES_PAYMENT_METHOD
REQUIRES_ACTION
PROCESSING
SUCCEEDED
FAILED
UNKNOWN
CANCELLED
```

`UNKNOWN` означает, что сетевой результат provider call неизвестен. В этом состоянии повторное списание блокируется до reconciliation или безопасного повторения той же provider operation с тем же provider idempotency key.

### 6.3 Refund

`Refund` является отдельным агрегатом, связанным с Payment:

- `refund_id`;
- `payment_id`;
- amount и currency;
- status;
- provider refund reference;
- idempotency key reference;
- reason code и разрешённая техническая note;
- timestamps.

Статусы Refund:

```text
PENDING
PROCESSING
SUCCEEDED
FAILED
UNKNOWN
CANCELLED
```

Refund разрешён только для `SUCCEEDED` или `PARTIALLY_REFUNDED` Payment. Сумма подтверждённых и незавершённых refunds не может превышать успешно оплаченную сумму. Проверка выполняется под row lock Payment.

Payment становится `PARTIALLY_REFUNDED` или `REFUNDED` только после подтверждённого provider event или reconciliation result.

### 6.4 PaymentEvent

`PaymentEvent` является неизменяемым бизнес-фактом и содержит:

- event UUID;
- payment ID;
- последовательный номер внутри Payment;
- site ID;
- payment account ID;
- event type;
- source: `API`, `PROVIDER_EVENT`, `RECONCILIATION` или `ADMIN`;
- nullable amount/currency;
- nullable provider event ID и provider object ID;
- `occurred_at` и `received_at` в UTC;
- request ID;
- nullable idempotency key;
- payload hash;
- ограниченную техническую metadata без email, tokens и платёжных данных.

Минимальный набор event types:

```text
PAYMENT_CREATED
ATTEMPT_CREATED
PAYMENT_METHOD_REQUIRED
CUSTOMER_ACTION_REQUIRED
PAYMENT_PROCESSING
PAYMENT_SUCCEEDED
PAYMENT_FAILED
PAYMENT_CANCELLED
PROVIDER_RESULT_UNKNOWN
REFUND_REQUESTED
REFUND_PROCESSING
REFUND_SUCCEEDED
REFUND_FAILED
```

Business adapters имеют только insert port. Update/delete PaymentEvent запрещены application-кодом и PostgreSQL guard trigger. Operational restore/migration выполняются отдельным привилегированным процессом, не runtime user приложения.

## 7. Persistence model

Новая Flyway migration создаёт:

- `payments`;
- `payment_attempts`;
- `refunds`;
- `payment_events`;
- `payment_event_sequences` с `payment_id` как primary key и atomic `next_sequence`;
- `idempotency_records`;
- `checkout_sessions`;
- `provider_inbox`;
- `outbox_events`;
- `outbox_deliveries`;
- `site_api_keys`;
- `site_callback_configs`;
- `site_callback_secret_versions`.

Обязательные ограничения:

- unique `(site_id, external_order_id)` для non-null dynamic orders;
- unique `(payment_id, sequence_number)` для PaymentEvent;
- unique `(provider_type, payment_account_id, provider_event_id)` для inbox;
- unique `(scope, idempotency_key)` для idempotency record;
- unique active callback configuration per Site;
- positive amount checks;
- matching currency checks между Refund и Payment;
- optimistic version на mutable projections;
- foreign keys к Site, PaymentAccount, Price и Payment.

`customer_id` остаётся nullable UUID без foreign key до появления Customer-модуля. Этап 3 добавит foreign key после миграции существующих guest rows.

## 8. Идемпотентность

`Idempotency-Key` обязателен для:

- создания public checkout;
- подтверждения fake checkout;
- создания dynamic Payment;
- выпуска нового Partner handoff;
- создания Refund;
- ручной reconciliation;
- ручного callback retry.

Scope включает actor/site, API operation и key. Request fingerprint вычисляется из canonical representation бизнес-полей, а не из исходного порядка JSON properties.

Поведение:

- первый запрос создаёт idempotency record и бизнес-операцию;
- повтор с тем же key и fingerprint возвращает исходный response reference;
- повтор с тем же key и другим fingerprint возвращает `409 IDEMPOTENCY_CONFLICT`;
- конкурентные первые запросы сериализуются database constraint/locking;
- незавершённая исходная операция возвращает её текущее безопасное состояние, а не создаёт вторую.

Completed idempotency record получает `expires_at` через 30 дней после сохранения исходного response reference. Ежедневный cleanup удаляет только completed records с истёкшим сроком; in-progress records не удаляются. Dynamic `external_order_id` остаётся уникальным в пределах Site независимо от retention idempotency records.

Secret-bearing части response, необходимые для корректного replay — checkout token или handoff token — хранятся зашифрованными только до собственного expiry token. Пока token действителен, replay возвращает тот же response. После expiry replay не создаёт новую операцию и возвращает исходный Payment reference вместе с `CHECKOUT_EXPIRED`; выпуск нового handoff выполняется отдельной Partner-командой, а не повтором create.

Provider operation idempotency key детерминированно выводится из внутреннего command/attempt ID. Безопасное повторение provider call использует тот же ключ.

## 9. Checkout sessions

Public checkout использует пару:

- публичный `checkout_id`;
- случайный 256-bit checkout token, возвращаемый только при создании.

В PostgreSQL хранится SHA-256 hash token, scope разрешений, Payment ID и expiry. Token:

- действует 24 часа;
- разрешает только чтение и предусмотренное provider confirmation конкретного checkout;
- передаётся в `Authorization: Bearer`;
- не включается в URL;
- не пишется в logs;
- хранится SDK в памяти или `sessionStorage`, но не в persistent local storage.

Partner API возвращает одноразовый handoff token, который backend сайта передаёт браузеру для продолжения dynamic checkout через SDK. Token действует 10 минут, привязан к Payment, Site и разрешённому Origin и хранится только как SHA-256 hash. Browser обменивает его на обычный checkout token. Site API key браузеру не передаётся.

## 10. API contracts

Все endpoints входят в OpenAPI и возвращают Problem Details с `request_id`.

### 10.1 Public API

```text
POST /api/v1/public/sites/{siteKey}/checkouts
POST /api/v1/public/handoffs
POST /api/v1/public/checkouts/{checkoutId}/confirm
GET  /api/v1/public/checkouts/{checkoutId}
```

Создание принимает `price_key`, `Idempotency-Key` и browser Origin. Backend сам получает опубликованную Price, amount, currency и tax mode. Поля amount/currency отсутствуют в request schema; запрос с дополнительными полями отклоняется validation error.

Ответ содержит:

- checkout ID и одноразово показанный checkout token;
- Payment ID;
- amount/currency и price snapshot summary;
- provider type;
- confirmation mode;
- безопасный provider client payload;
- текущее состояние.

`handoffs` принимает одноразовый Partner handoff token и разрешённый Origin, инвалидирует handoff и возвращает checkout ID/token. `confirm` используется provider-client adapter SDK только для server-confirmation providers, включая fake. Будущий Stripe adapter возвращает client-provider confirmation mode и подтверждается через Stripe.js.

### 10.2 Partner API

```text
POST /api/v1/partner/payments
GET  /api/v1/partner/payments/{paymentId}
POST /api/v1/partner/payments/{paymentId}/handoffs
```

Site определяется из аутентифицированного site API key. Создание принимает:

- `external_order_id`;
- positive `amount_minor`;
- currency;
- optional return URL, разрешённый конфигурацией Site;
- ограниченную техническую metadata без секретов и PII;
- `Idempotency-Key`.

Ответ возвращает checkout handoff token для передачи browser SDK. Dynamic amount никогда не принимается Public API.

`handoffs` выпускает новый 10-minute handoff для существующего незавершённого Payment и требует scope `payments:create`, разрешённый Origin и собственный `Idempotency-Key`.

### 10.3 Admin API

```text
GET  /api/v1/admin/payments
GET  /api/v1/admin/payments/{paymentId}
POST /api/v1/admin/payments/{paymentId}/refunds
POST /api/v1/admin/payments/{paymentId}/reconcile
POST /api/v1/admin/payments/{paymentId}/fake-events
POST /api/v1/admin/outbox/{eventId}/retry
POST /api/v1/admin/sites/{siteId}/api-keys
POST /api/v1/admin/sites/{siteId}/api-keys/{keyId}/rotate
POST /api/v1/admin/sites/{siteId}/api-keys/{keyId}/revoke
PUT  /api/v1/admin/sites/{siteId}/callback
POST /api/v1/admin/sites/{siteId}/callback/rotate-secret
```

Admin mutation endpoints используют существующую session/CSRF protection и требуют `Idempotency-Key` для денежных или повторяемых действий.

Полные значения site API key и callback secret возвращаются только один раз при создании/rotation.

## 11. Site API keys

Формат ключа содержит безопасный prefix для поиска и случайную secret часть. В базе хранятся:

- key ID;
- Site ID;
- prefix;
- Argon2id hash;
- scopes;
- status;
- created/expires/last-used timestamps.

Stage 2 scopes:

```text
payments:create
payments:read
```

Rotation создаёт новый ключ и позволяет ограниченное перекрытие со старым. Старый ключ автоматически становится недействительным после 24 часов или немедленно по admin revoke.

Authentication выполняет dummy Argon2 verification для неизвестного prefix, чтобы уменьшить timing distinction. Last-used timestamp обновляется с write throttling, а не при каждом запросе.

## 12. Provider interaction

### 12.1 Создание операции

Provider network call не выполняется внутри открытой database transaction.

Создание checkout/Payment и подготовка provider payment object выполняются так:

1. транзакция сохраняет Payment, `PAYMENT_CREATED` и idempotency state;
2. application вызывает provider port с детерминированным provider idempotency key;
3. вторая транзакция сохраняет provider reference и состояние `REQUIRES_PAYMENT_METHOD`/`PROCESSING` вместе с соответствующим PaymentEvent;
4. окончательный успех/отказ применяется только через provider event или reconciliation.

Если процесс останавливается между шагами, повтор команды использует сохранённый Payment и тот же provider idempotency key. Он не создаёт новую денежную операцию.

Каждый вызов checkout `confirm` сначала транзакционно создаёт новый PaymentAttempt и `ATTEMPT_CREATED`, затем вызывает provider вне транзакции. Recoverable decline завершает только этот Attempt и оставляет возможность следующего confirm. Unknown result блокирует следующий Attempt до reconciliation.

### 12.2 Fake provider

Fake adapter:

- создаёт детерминированные provider references;
- хранит provider-side state отдельно от Payment projection;
- поддерживает success, decline, requires-action, processing, timeout и unknown-result сценарии;
- умеет выдавать duplicate и out-of-order events;
- умеет вернуть фактическое состояние при reconciliation;
- не выполняет внешние сетевые запросы.

Application default: `PAYMENT_FAKE_AUTO_SUCCESS=false`.

При `PAYMENT_FAKE_AUTO_SUCCESS=true` confirmation асинхронно помещает success event в inbox. Синхронный confirm response всё равно не переводит Payment напрямую в `SUCCEEDED`.

Fake controls доступны только Admin API и тестовым fixtures. Они не появляются в публичном SDK.

## 13. Provider inbox

Каждый provider event нормализуется в envelope:

- provider type;
- PaymentAccount ID;
- provider event ID;
- provider object ID;
- event type;
- occurred time;
- received time;
- payload hash;
- безопасный fake payload или encrypted raw payload reference для будущего provider.

Inbox endpoint/adapter выполняет insert-if-absent и быстро завершает при duplicate. Worker claim использует PostgreSQL locking, допускающий несколько workers без двойной обработки.

Обработка события:

1. загружает соответствующий Payment/Refund;
2. при необходимости получает актуальный provider object;
3. применяет monotonic domain transition;
4. добавляет PaymentEvent;
5. обновляет projection;
6. создаёт OutboxEvent;
7. помечает inbox row обработанной.

Шаги 3–7 выполняются в одной транзакции. Запоздавшее событие, которое не меняет подтверждённое состояние, отмечается обработанным без регрессивного PaymentEvent.

## 14. Reconciliation

Stage 2 поддерживает ручную reconciliation одного Payment через admin.

Reconciliation:

- блокирует конкурентное повторное списание;
- получает фактическое provider state;
- применяет ту же state machine, что provider inbox;
- добавляет PaymentEvent с source `RECONCILIATION`, если обнаружен новый бизнес-факт;
- создаёт OutboxEvent для подтверждённого изменения;
- является идемпотентной.

Периодический fleet-wide reconciliation scheduler остаётся для hardening этапа, но application port и единичный use case формируются сейчас.

## 15. Transactional outbox и callbacks

Один Site имеет ноль или один активный callback endpoint.

Outbox payload фиксируется при создании события и содержит:

- schema version;
- event ID;
- event type;
- Site ID;
- Payment ID;
- nullable external order ID;
- Payment status;
- nullable amount/currency;
- occurred time.

Payload не содержит email, checkout token, site API key, callback secret или provider client secret.

Stage 2 публикует callback event types:

```text
payment.requires_payment_method
payment.requires_action
payment.processing
payment.succeeded
payment.failed
payment.cancelled
refund.processing
refund.succeeded
refund.failed
```

OutboxEvent создаётся только при реальном domain transition. Duplicate или проигнорированное out-of-order provider event не создаёт повторный callback.

Callback headers:

```text
Payment-Event-Id: <uuid>
Payment-Timestamp: <unix-seconds>
Payment-Signature: v1=<hex-hmac-sha256>
Payment-Secret-Version: <version>
Content-Type: application/json
```

Подписывается строка `<timestamp>.<raw-body>`.

HTTP 2xx означает успешную доставку. Redirects не следуются. Остальные ответы, timeout и network errors создают failed delivery attempt.

Retry policy: немедленная первая попытка и пять retries через 1 минуту, 5 минут, 30 минут, 2 часа и 12 часов. После шести общих попыток событие переходит в `DEAD`.

Automatic retries используют endpoint и secret version, сохранённые в OutboxEvent при создании. Manual retry создаёт новую delivery attempt для того же event ID; администратор явно выбирает исходный snapshot или текущую Site callback configuration. Подключённый сайт обязан дедуплицировать callbacks по event ID.

## 16. Callback secret storage и rotation

Site API key можно хранить односторонним hash, но callback secret требуется для подписи. Он хранится через `EncryptedSecretPort`.

Локальная реализация:

- AES-256-GCM;
- случайный 96-bit nonce для каждой записи;
- key version в ciphertext metadata;
- 32-byte master key из `PAYMENT_DATA_ENCRYPTION_KEY` в environment;
- master key не хранится в PostgreSQL и не логируется.

Production adapter позднее можно заменить cloud secrets manager без изменения application/domain interfaces.

Rotation создаёт новую secret version. Предыдущая version остаётся доступна 24 часа и дольше, если на неё ещё ссылается незавершённая outbox delivery. Ежедневный cleanup уничтожает старую version только после завершения или окончательного истечения всех связанных deliveries.

`infrastructure/.env.example` получает безопасный local-only пример 32-byte Base64 key. Для любого non-local profile отсутствие `PAYMENT_DATA_ENCRYPTION_KEY` останавливает startup; приложение не генерирует молча новый key.

## 17. Security

### 17.1 Public checkout

- Site должен быть active;
- Origin обязателен и точно сопоставляется с нормализованным allowlist Site;
- CORS разрешает только этот Origin;
- public endpoints получают rate limits по Site, IP и operation;
- published Price загружается сервером;
- amount/currency от browser не доверяются;
- checkout bearer token сравнивается по hash;
- ответы используют `Cache-Control: no-store`.

### 17.2 Partner API

- Site выводится из authenticated API key, а не из request body;
- key должен быть active, unexpired и иметь нужный scope;
- request body и metadata имеют строгие size limits;
- rate limit применяется по credential и Site;
- secret никогда не отражается в response/logs.

### 17.3 Callback SSRF protection

В production callback URL:

- требует HTTPS;
- не содержит user-info или fragment;
- использует разрешённый port policy;
- не может резолвиться в loopback, link-local, private, multicast или metadata-service address;
- повторно проверяется при DNS resolution перед connect;
- не следует redirects.

Local profile может явно разрешить `localhost` для тестов. Production default этого не допускает.

### 17.4 Logging

Запрещено логировать:

- site API keys;
- callback secrets;
- checkout/handoff tokens;
- provider client secrets;
- card/payment data;
- полные provider payloads;
- raw authorization headers.

Logs содержат только request ID, безопасные object IDs, operation, status и sanitised error category.

## 18. Error model

Provider decline является бизнес-результатом и не возвращается как HTTP 500. Temporary infrastructure errors и неизвестный provider result различаются.

Минимальные stable error codes:

```text
SITE_NOT_AVAILABLE
ORIGIN_NOT_ALLOWED
PRICE_NOT_AVAILABLE
PAYMENT_CAPABILITY_UNAVAILABLE
CHECKOUT_TOKEN_INVALID
CHECKOUT_EXPIRED
SITE_API_KEY_INVALID
SITE_API_SCOPE_REQUIRED
EXTERNAL_ORDER_ALREADY_EXISTS
IDEMPOTENCY_CONFLICT
PAYMENT_STATE_CONFLICT
REFUND_EXCEEDS_AVAILABLE
PROVIDER_DECLINED
PROVIDER_TEMPORARILY_UNAVAILABLE
PROVIDER_RESULT_UNKNOWN
CALLBACK_CONFIG_INVALID
CALLBACK_DELIVERY_FAILED
```

Каждый Problem Details response содержит:

- HTTP status;
- stable code;
- безопасный title/detail;
- request ID;
- validation field errors, когда применимо.

Внутренние provider messages, stack traces и secret configuration не возвращаются клиенту.

## 19. Admin UI

### 19.1 Payments list

Admin navigation получает раздел `Payments` с фильтрами:

- Site;
- status;
- external order ID;
- Payment ID;
- provider reference;
- created date range.

### 19.2 Payment details

Страница показывает:

- amount/currency и source;
- Site, PaymentAccount и Price snapshot;
- current status;
- PaymentEvent timeline;
- PaymentAttempts;
- Refunds;
- provider references;
- OutboxEvent и delivery attempts.

Действия:

- полный или частичный Refund с подтверждением;
- Reconcile;
- Retry callback;
- Fake simulation при fake provider.

Опасные действия имеют явное confirmation UI и понятный результат. Повторная отправка browser form защищена idempotency key.

### 19.3 Site integration settings

Site screen позволяет:

- создать/rotate/revoke Partner API key;
- увидеть prefix, scopes и last-used time;
- настроить один callback URL;
- создать/rotate callback secret;
- увидеть состояние последних deliveries.

Полные secrets показываются только в одноразовом reveal state и не возвращаются при последующем чтении страницы.

## 20. TypeScript SDK

`frontend/sdk` собирается как ESM package и не зависит от React/Next.js.

Публичный API предоставляет:

- imperative checkout client;
- Web Component `<generic-payment-widget>`;
- configuration через `siteKey`, `priceKey`, API base URL, locale и theme tokens;
- безопасные lifecycle events для UI;
- восстановление незавершённого checkout из `sessionStorage`;
- polling с bounded exponential delay и cancellation.

Widget states:

```text
idle
loading
ready
processing
requires_action
succeeded
failed
unknown
expired
```

SDK имеет внутренний provider-client interface. Fake driver вызывает public `confirm` endpoint. Будущий Stripe driver использует Stripe.js/Payment Element и не меняет публичный API widget.

Browser event `succeeded` служит только UX-сигналом. Подключённый сайт не должен выдавать entitlement на основании browser event; доверенный источник — подписанный backend callback или Partner API read.

Styling использует CSS variables и изолированные component styles. Widget поддерживает keyboard navigation, screen-reader labels, visible focus и reduced motion.

## 21. Demo site

`examples/demo-site` является статическим примером интеграции и показывает:

- подключение собранного SDK;
- конфигурацию `site_key` и `price_key`;
- theme tokens;
- гостевой каталожный checkout;
- processing/success/failure/unknown states;
- восстановление checkout после reload.

Demo site не содержит Partner API key и не вызывает dynamic payment API напрямую.

Dynamic server-to-server flow демонстрируется smoke command и документацией с разделением trusted backend и browser handoff token.

## 22. Tests

### 22.1 Backend unit tests

Покрывают:

- Payment state machine;
- out-of-order transition rejection;
- PaymentAttempt unknown result;
- recoverable decline и новый Attempt в том же Payment;
- refund amount/currency invariants;
- PaymentEvent construction;
- idempotency fingerprint;
- HMAC signature;
- API key verification/scopes;
- callback retry schedule;
- provider result mapping.

### 22.2 Backend integration tests

PostgreSQL Testcontainers проверяет:

- Flyway migration;
- all unique/check/foreign-key constraints;
- append-only PaymentEvent guard;
- atomic Payment projection + PaymentEvent + OutboxEvent transaction;
- inbox duplicate handling;
- worker claiming;
- optimistic locking;
- concurrent refund protection;
- idempotency concurrency;
- encrypted callback secret persistence;
- site-isolated queries.

HTTP integration tests проверяют Public, Partner и Admin API, CORS, CSRF, scopes, rate-limit behavior и Problem Details.

### 22.3 Cucumber

Business scenarios:

- successful catalog payment;
- declined catalog payment;
- повторная попытка того же checkout после recoverable decline;
- dynamic Partner payment;
- same idempotency key/same request;
- same idempotency key/different request;
- duplicate provider event;
- out-of-order provider event;
- unknown provider result and reconciliation;
- full refund;
- partial refunds and over-refund rejection;
- callback success;
- callback retries and `DEAD`;
- PaymentAccount routing.

Сценарии используют настоящий Spring context, PostgreSQL Testcontainer и FakePaymentProvider.

### 22.4 Frontend

- SDK unit tests для client, polling, recovery и provider driver;
- Web Component tests для states, events, accessibility и theming;
- admin component tests для filters, timeline, refunds, secrets reveal и retry;
- Playwright для demo-site checkout, success/failure, processing и reload recovery.

## 23. Docker Compose и smoke

Compose добавляет `demo-site`. Stage 2 default services:

```text
postgres
backend
admin
demo-site
```

Smoke profile дополнительно запускает `callback-receiver` из `infrastructure/callback-receiver.mjs`: минимальный Node 24 HTTP service без package dependencies. Он проверяет HMAC, дедуплицирует event IDs в памяти и предоставляет только внутренний inspection endpoint для smoke script. Сервис не входит в production images и не публикует host port.

Application default сохраняет `PAYMENT_FAKE_AUTO_SUCCESS=false`. Локальный smoke profile или явная `.env` настройка включает auto-success.

Smoke workflow:

1. дожидается readiness;
2. входит как admin;
3. создаёт или использует fake PaymentAccount, Site и published Price;
4. настраивает Site callback на smoke-profile `callback-receiver`;
5. создаёт catalog checkout с idempotency key;
6. подтверждает checkout;
7. дожидается `SUCCEEDED`;
8. проверяет `PAYMENT_SUCCEEDED` в journal;
9. проверяет успешную outbox delivery;
10. повторяет create/confirm с теми же keys и убеждается, что второй Payment/Attempt не создан.

Callback receiver для smoke является тестовой локальной инфраструктурой и не входит в production runtime image.

## 24. Наблюдаемость

Metrics этапа:

- Payments created/succeeded/failed/processing by Site и provider;
- provider call duration/errors/unknown results;
- inbox received/duplicate/failed/lag;
- outbox pending/delivered/retried/dead/lag;
- refunds requested/succeeded/failed;
- idempotency replay/conflict counts.

Metrics не используют high-cardinality Payment IDs как labels. Logs получают request correlation и безопасные domain IDs. Health/readiness учитывает database connectivity, но временная недоступность callback сайта не делает backend unready.

## 25. Критерии приёмки

Этап 2 считается готовым, когда:

- гостевой checkout по опубликованной Price проходит через FakePaymentProvider;
- browser не может подменить amount/currency каталожной цены;
- dynamic amount доступен только scoped Partner API key;
- один `external_order_id` не создаёт две операции в пределах Site;
- idempotency replay возвращает исходную операцию;
- конфликт fingerprint возвращает `409`;
- синхронный provider response не подтверждает Payment;
- provider inbox безопасно обрабатывает duplicate и out-of-order events;
- каждый подтверждённый денежный факт появляется в append-only PaymentEvent;
- projection, PaymentEvent и OutboxEvent обновляются атомарно;
- callback подписан, повторяется и переходит в `DEAD` после исчерпания attempts;
- ручной callback retry доступен в admin;
- полные и частичные refunds соблюдают остаток;
- неизвестный provider result блокирует повторное списание до reconciliation;
- recoverable decline допускает новый Attempt без создания второго Payment;
- admin показывает Payment timeline, attempts, refunds и deliveries;
- demo-site использует SDK без React/Next.js зависимости;
- backend, frontend, Cucumber, Playwright, Compose и smoke checks проходят;
- secrets, tokens и полные provider payloads отсутствуют в logs;
- всё работает без Stripe account и без изменения Stage 1 contracts для существующих Site/Catalog flows.

## 26. Совместимость со следующими этапами

Этап 3 добавит Customer, authentication и donations, используя nullable `customer_id`, checkout session и Public API security boundaries этого этапа.

Этап 4 добавит recurring provider operations и Subscription projection, повторно используя inbox, PaymentEvent conventions, outbox delivery, callbacks и SDK provider-client abstraction.

Этап 5 добавит Stripe adapter, raw signed webhook endpoint, Stripe.js Payment Element, sandbox E2E, периодическую reconciliation, production secrets adapter и deployment hardening без изменения публичных payment concepts.
