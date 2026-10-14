# Generic Payment Platform — дизайн

Дата: 2026-09-12

## 1. Цель

Создать центральную, повторно используемую платёжную платформу для нескольких принадлежащих одному владельцу сайтов. Платформа должна принимать разовые платежи, оформлять подписки и собирать разовые донаты через Stripe, при этом однозначно связывать каждую операцию с сайтом, клиентом и внешним заказом.

Платёжная форма и управление подпиской встраиваются в дизайн подключаемого сайта. Центральный backend хранит платёжное состояние, принимает Stripe webhooks, ведёт неизменяемый журнал денежных фактов и надёжно уведомляет backend подключённого сайта.

Первая эксплуатация предполагает одного индивидуального предпринимателя в Португалии и один Stripe-аккаунт. Архитектура допускает последующее назначение сайтам разных Stripe-аккаунтов или Stripe Connect без изменения публичного API платформы.

## 2. Согласованный объём MVP

MVP включает:

- несколько логически изолированных сайтов;
- разовые платежи по фиксированной цене каталога;
- разовые платежи с динамической суммой для доверенной server-to-server интеграции;
- подписки с фиксированной месячной или годовой ценой;
- бесплатный пробный период;
- смену тарифа;
- немедленную отмену подписки или отмену в конце оплаченного периода;
- обновление способа оплаты;
- полные и частичные возвраты;
- разовые донаты с предложенными суммами или пользовательской суммой в заданных пределах;
- карты, Apple Pay, Google Pay, PayPal и другие подходящие Stripe payment methods;
- включение способов оплаты отдельно для каждого сайта;
- собственный встраиваемый интерфейс оплаты и управления подпиской;
- опциональный Stripe Customer Portal как резервный сценарий;
- сайты с backend и статические сайты;
- аутентификацию через токен backend сайта, OIDC-токен или email OTP;
- административную web-панель для одного администратора;
- локальный запуск через Docker Compose;
- работу без Stripe-аккаунта через Fake Payment Provider;
- Cucumber-JVM acceptance tests и browser E2E tests.

В MVP не входят:

- физические товары, доставка и складской учёт;
- usage-based billing;
- тарификация по количеству пользователей;
- промокоды и рассрочка;
- регуляр Connect onboarding и управление connected accounts;
- несколько администраторов и роли;
- продвинутая финансовая аналитика;
- привязка к конкретному облачному провайдеру;
- автоматическая подача налоговой отчётности.

## 3. Структура репозитория

Проект хранится локально без инициализации Git, коммитов и публикации.

```text
payment/
├── backend/                    # Java, Spring Boot, Maven Wrapper
├── frontend/
│   ├── admin/                  # Next.js, React, TypeScript, Tailwind CSS
│   └── sdk/                    # framework-agnostic TypeScript SDK
├── examples/
│   └── demo-site/              # static и server-backed примеры интеграции
├── infrastructure/             # Docker Compose и локальная конфигурация
└── docs/                       # архитектура и инструкции
```

Backend и frontend собираются и разворачиваются независимо. Frontend workspace использует pnpm. Backend использует Maven Wrapper. SDK собирается как ESM-пакет и локально подключается к demo-site через workspace dependency.

## 4. Общая архитектура

Основной backend — модульный монолит. Он является одним deployable-приложением, но разделён на бизнес-модули с явными границами:

- `sites` — подключённые сайты, домены, ключи, authentication и payment settings;
- `catalog` — продукты, версии цен, налоговые настройки и донаты;
- `customers` — изолированные клиенты и их идентичности;
- `payments` — платежи, попытки, возвраты и денежный журнал;
- `subscriptions` — жизненный цикл подписок;
- `provider` — платёжные аккаунты и синхронизация с провайдером;
- `webhooks` — входящие provider events и исходящие события сайтам;
- `customeraccess` — OIDC, site tokens, email OTP и SDK sessions;
- `admin` — административная аутентификация, команды и аудит.

Каждый модуль следует hexagonal architecture:

```text
<module>/
├── domain/                     # чистые Java-модели, value objects и правила
├── application/
│   ├── port/in/                # use-case interfaces
│   ├── port/out/               # persistence/provider/messaging interfaces
│   └── service/                # orchestration use cases
└── adapter/
    ├── in/                     # REST, webhook, scheduled jobs
    └── out/                    # PostgreSQL, Stripe, email, HTTP callbacks
```

Domain-код не зависит от Spring, Stripe SDK, JPA и HTTP. Persistence entities, Stripe DTO и REST DTO не используются как domain objects. Взаимодействие между модулями идёт через application ports и доменные идентификаторы, а не через прямой доступ к чужим таблицам или repository.

## 5. Компоненты и взаимодействие

### 5.1 Spring Boot backend

Backend предоставляет пять групп API:

- Public API — конфигурация SDK, email OTP и допустимые публичные checkout-команды;
- Customer API — платежи и подписки аутентифицированного клиента;
- Partner API — доверенные server-to-server команды сайтов;
- Admin API — управление платформой;
- Provider Webhook API — приём подписанных событий Stripe.

Все API версионируются с первого релиза. OpenAPI-описание является частью сборки и источником типов клиента админки.

### 5.2 Admin frontend

`frontend/admin` использует Next.js App Router, React, TypeScript и Tailwind CSS. Next.js отвечает за страницы, middleware, административную сессию в браузере и безопасное проксирование запросов к Spring Boot. Платёжная бизнес-логика в Next.js отсутствует.

Backend создаёт непрозрачную административную сессию после проверки пароля. Next.js хранит идентификатор сессии в cookie с `HttpOnly`, `Secure` и `SameSite`. Изменяющие запросы защищены CSRF-токеном. Начальная учётная запись администратора создаётся bootstrap-командой из environment variables; пароль хранится только как Argon2id hash.

### 5.3 Frontend SDK

`frontend/sdk` не зависит от React или Next.js. Он предоставляет imperative API и Web Components:

- payment widget;
- donation widget;
- subscription manager;
- authentication/session helper.

SDK принимает дизайн-токены, locale и набор переводов. Stripe Payment Element оформляется через Stripe Appearance API; собственные части SDK используют CSS variables и изолированные стили. Данные карт и CVC передаются непосредственно Stripe.js и никогда не проходят через platform backend.

Публичный `site_key` идентифицирует сайт, но не является секретом. Backend всегда проверяет origin по allowlist сайта. Секретные site API keys используются только server-to-server.

### 5.4 Demo site

`examples/demo-site` демонстрирует:

- публичный checkout по цене каталога;
- публичный донат;
- email OTP;
- OIDC session injection;
- server-backed динамический платёж;
- оформление и управление подпиской;
- обработку результата и ошибок SDK.

## 6. Модель данных

### 6.1 Site и PaymentAccount

`Site` содержит стабильный внутренний ID, уникальный публичный key, display name, статус, allowed origins, return URLs, integration settings, разрешённые authentication modes и payment method policy.

`PaymentAccount` описывает провайдера, внешний account ID, рабочий режим и ссылку на credentials. Секреты провайдера не хранятся открытым текстом в обычных таблицах. Локально credentials приходят из environment/Docker secrets; облачный secrets adapter можно заменить без изменения domain-кода.

Каждый `Site` ссылается на `PaymentAccount`. В MVP все сайты используют один Stripe payment account. Provider mappings каталога хранятся отдельно, чтобы один внутренний Product или Price позднее можно было синхронизировать с несколькими Stripe-аккаунтами.

### 6.2 Product и Price

`Product` принадлежит одному сайту и описывает цифровой продукт или услугу. Клиент одного сайта не получает доступ к продуктам другого.

`Price` является неизменяемой опубликованной версией цены и содержит:

- стабильный `price_key` в пределах сайта;
- тип `ONE_TIME` или `SUBSCRIPTION`;
- сумму в minor units;
- currency;
- recurring interval для подписок;
- trial policy;
- `tax_mode`: `NONE`, `INCLUSIVE`, `EXCLUSIVE` или `STRIPE_TAX`;
- Stripe product tax code при необходимости;
- active period и provider mapping.

Изменение опубликованной цены создаёт новую версию. Существующие платежи и подписки продолжают ссылаться на исходную версию.

`DonationCampaign` принадлежит сайту и содержит currency, suggested amounts, минимальную и максимальную сумму, текст назначения и признак разрешённого анонимного доната. В MVP донаты только разовые.

### 6.3 Customer и идентичности

Customer всегда изолирован внутри сайта. Совпадающий email на двух сайтах не объединяет клиентов и не объединяет Stripe Customer objects.

Идентичность определяется комбинацией:

```text
(site_id, issuer, subject)
```

`issuer` различает backend сайта, конкретного OIDC provider и email OTP. Email хранится в Customer profile, нормализуется для поиска внутри сайта и не используется как глобальный ключ.

Поддерживаются:

- `SITE_TOKEN` — короткоживущий JWT, подписанный backend сайта;
- `OIDC_TOKEN` — токен доверенного issuer с проверкой подписи, audience, expiry и subject;
- `EMAIL_OTP` — одноразовый код и короткоживущая platform session.

Чистый OAuth provider без OIDC identity token требует site backend, который проверяет OAuth и выдаёт `SITE_TOKEN`.

### 6.4 Payment, попытки и возвраты

`Payment` хранит текущую проекцию операции: site, customer, external order, price или donation reference, provider account, amount, currency, tax, status и provider IDs.

`PaymentAttempt` создаётся для каждой попытки подтвердить оплату и фиксирует provider intent, выбранный payment method family и безопасный результат попытки.

`Refund` хранит отдельный полный или частичный возврат, его состояние и provider reference. Сумма всех успешных возвратов не может превышать успешную сумму платежа.

Основные состояния платежа:

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

Domain state machine запрещает нелегальные переходы и не позволяет запоздавшему событию откатить подтверждённый платёж в более раннее состояние.

### 6.5 Subscription

`Subscription` является локальной проекцией Stripe Subscription и содержит site, customer, текущую версию Price, provider reference, период, trial dates, cancellation policy и status.

Поддерживаются состояния `INCOMPLETE`, `TRIALING`, `ACTIVE`, `PAST_DUE`, `PAUSED`, `CANCEL_AT_PERIOD_END`, `CANCELLED` и `UNPAID`. Доступ к продукту определяется domain policy, а не только одним сырым Stripe status. Изменения подтверждаются provider events.

## 7. Неизменяемый журнал денежных фактов

Обычные application logs не являются финансовым источником истины. PostgreSQL хранит неизменяемый `PaymentEvent` для каждого бизнес-факта.

Запись содержит:

- event ID;
- payment ID;
- site ID;
- payment account ID;
- event type;
- source: API, Stripe webhook, reconciliation или admin;
- amount in minor units и currency;
- provider event ID и provider object ID;
- `occurred_at` и `received_at` в UTC;
- request ID;
- idempotency key;
- payload hash;
- разрешённую техническую metadata без email и card data.

Строки журнала не обновляются и не удаляются бизнес-операциями. Исправление представляется новым компенсирующим событием. `payments` и `subscriptions` являются пересчитываемыми текущими проекциями поверх фактов и provider state.

Оригинальное provider event body допускается хранить зашифрованным с ограниченным сроком retention для расследований и повторной обработки. Полный body никогда не пишется в application logs.

## 8. Платёжные потоки

### 8.1 Каталожная оплата

Сайт или SDK передаёт `site_key` и `price_key`. Backend извлекает сумму, currency и tax policy из опубликованной версии Price. Значения от браузера не принимаются как доверенные. Backend создаёт локальный Payment до обращения в Stripe, затем создаёт PaymentIntent и возвращает SDK только необходимые публичные данные и `client_secret`.

### 8.2 Динамическая оплата

Только Partner API может передать динамические amount/currency и обязательный `external_order_id`. Запрос аутентифицируется секретным ключом сайта и требует `Idempotency-Key`. Public API никогда не принимает динамическую цену обычного заказа.

### 8.3 Донат

Public API принимает выбранную пользователем сумму только для активной DonationCampaign. Backend проверяет currency, minimum и maximum. Донат может быть связан с аутентифицированным Customer или быть анонимным. Сообщение донора проходит ограничение длины и sanitization.

### 8.4 Подписка

Backend создаёт или переиспользует Stripe Customer внутри конкретного сайта и создаёт subscription по опубликованному Price. SDK обрабатывает initial payment и SCA через Payment Element. Trial, последующее списание, past-due и отмена обновляются по provider events.

Customer API позволяет получить состояние, сменить тариф в пределах разрешённого каталога, выбрать политику proration, отменить подписку и создать SetupIntent для обновления payment method. Stripe Customer Portal доступен как site-level fallback, но не является основным UI.

## 9. Подтверждение результата и webhooks

Ответ браузера или синхронный ответ Stripe не считается окончательным денежным фактом. Stripe webhook является источником подтверждения результата.

Provider Webhook API:

1. получает неизменённое raw body;
2. проверяет Stripe signature;
3. определяет PaymentAccount;
4. атомарно сохраняет уникальный `ProviderEvent` в inbox;
5. быстро возвращает успешный HTTP response;
6. фоновый worker обрабатывает inbox event;
7. в одной транзакции добавляет PaymentEvent, обновляет projections и создаёт OutboxEvent.

Уникальность `(provider, payment_account_id, provider_event_id)` защищает от повторов. Для потенциально запоздавших или неполных событий adapter получает актуальный provider object и применяет domain transition по фактическому состоянию.

Outbox worker отправляет backend сайта подписанное HMAC-событие. Payload содержит version, event ID, site ID, business object ID, event type и occurred time. Доставка использует exponential backoff, сохраняет каждый HTTP результат и после лимита попыток переводит событие в `DEAD`. Администратор может повторить доставку вручную.

Для статического сайта SDK получает состояние через polling endpoint по непрозрачному session ID. Browser redirect сам по себе не подтверждает успех.

## 10. Идемпотентность и согласованность

Все изменяющие Partner и Customer API команды требуют `Idempotency-Key`. Backend сохраняет scope, request fingerprint и response reference. Повтор с тем же ключом и тем же телом возвращает исходный результат; повтор с изменённым телом отклоняется.

Stripe adapter использует производный provider idempotency key. Если сетевой сбой оставляет результат неизвестным, Payment получает состояние, требующее reconciliation. До сверки SDK не предлагает повторное списание.

Конкурентные обновления защищаются optimistic locking и domain state machine. Возвраты дополнительно блокируют aggregate на время проверки доступной к возврату суммы.

Периодическая reconciliation job сверяет незавершённые, неизвестные и давно не обновлявшиеся операции с Stripe. Результат сверки записывается новым PaymentEvent с source `RECONCILIATION`.

## 11. Authentication и безопасность

### 11.1 Site API keys

Site API key показывается администратору только при создании или rotation. В базе хранится prefix для поиска и Argon2id hash для проверки. Ключ имеет scope, статус и дату последнего использования. Rotation допускает короткий период перекрытия старого и нового ключа.

### 11.2 SDK sessions

После успешной проверки `SITE_TOKEN`, `OIDC_TOKEN` или `EMAIL_OTP` backend выдаёт короткоживущую SDK session, ограниченную `site_id`, `customer_id`, audience и allowed actions. Токен другого сайта непригоден.

OIDC configuration задаётся отдельно для сайта: issuer, audience/client ID, JWKS source и разрешённые claims. Проверяются signature, issuer, audience, expiry и subject. JWKS кэшируется с безопасным refresh.

Email OTP:

- генерируется криптографически стойко;
- хранится только как hash;
- имеет короткий срок действия;
- ограничивает число попыток и повторных отправок;
- защищён rate limiting по site, IP и нормализованному email;
- использует одинаковые внешние ответы независимо от существования Customer;
- локально отправляется в Mailpit;
- в production использует заменяемый MailSender port.

### 11.3 Web security

- строгий per-site CORS allowlist;
- TLS обязателен в production;
- CSP и ограничение допустимых Stripe origins;
- административные cookie: HttpOnly, Secure, SameSite;
- CSRF protection для cookie-authenticated команд;
- security headers на backend, admin и demo configuration;
- rate limiting публичных и authentication endpoints;
- request size limits;
- запрет логирования secrets, tokens, client secrets, card data и полного webhook body;
- audit log для login, configuration changes, refunds, retries и key rotation.

Система не хранит PAN или CVC и не предоставляет собственные поля ввода карты. Все card inputs принадлежат Stripe Element.

## 12. Payment methods и налоги

На уровне Site задаётся policy:

- `AUTOMATIC` — Stripe динамически выбирает подходящие активированные методы;
- `ALLOWLIST` — сайт дополнительно ограничивает разрешённые методы.

Backend никогда не обещает метод, который недоступен для страны аккаунта, currency, устройства или типа платежа. SDK отображает фактически доступные методы Stripe.

Apple Pay, Google Pay и другие domain-bound методы требуют регистрации каждого top-level domain и subdomain. PayPal требует активации в Stripe Dashboard и подключения PayPal account. Эти требования отображаются в admin checklist, но не автоматизируются без Stripe credentials.

Default currency — EUR, но Price и DonationCampaign явно хранят currency. Конвертация не выполняется платформой автоматически.

Tax policy задаётся на Price. Stripe Tax является опциональным adapter capability. Платформа может рассчитывать и собирать VAT через Stripe, но не считается системой автоматической налоговой регистрации или подачи отчётности. Перед live запуском владелец подтверждает налоговую схему с бухгалтером.

## 13. Обработка ошибок

HTTP API использует Problem Details и стабильные machine-readable error codes. Каждый ответ содержит `request_id`.

SDK различает:

- configuration error;
- authentication required или expired session;
- validation error;
- payment method required;
- additional customer action/SCA;
- bank/provider decline;
- asynchronous processing;
- temporary provider/network failure;
- unknown result requiring reconciliation.

Пользовательские тексты локализуются на стороне SDK. Внутренние сообщения Stripe, stack traces и детали secret configuration не возвращаются клиенту.

Неподтверждённый или asynchronous платёж не открывает доступ к продукту. Сайт получает entitlement-related уведомление только после подтверждённого business event.

## 14. Admin UI

Admin MVP предоставляет:

- login/logout одного администратора;
- создание, редактирование и отключение Sites;
- управление allowed origins, return URLs и authentication modes;
- создание и rotation site API keys и webhook secrets;
- OIDC configuration;
- включение email OTP;
- выбор payment method policy;
- создание Products, Price versions и DonationCampaigns;
- просмотр Customers в контексте Site;
- поиск Payments по site, external order, provider ID и status;
- timeline неизменяемых PaymentEvents;
- просмотр PaymentAttempts, Refunds и provider references;
- полный и частичный refund с подтверждением;
- просмотр Subscriptions и их состояния;
- просмотр Outbox deliveries и manual retry;
- запуск reconciliation для отдельной операции;
- просмотр AuditLog;
- checklist будущей Stripe configuration.

## 15. Тестовая стратегия

### 15.1 Unit tests

JUnit проверяет domain value objects, state machines, pricing rules, refund invariants, access policy и event construction без Spring context. Application services тестируются с in-memory/fake ports.

### 15.2 Integration tests

Testcontainers поднимает PostgreSQL. Integration tests проверяют Flyway migrations, persistence adapters, unique constraints, optimistic locking, inbox/outbox transactions и repository queries. HTTP tests запускают Spring Boot и проверяют authentication, validation, Problem Details и security headers.

Stripe adapter contract tests используют контролируемый HTTP stub для success, decline, timeout, malformed response, retry и unknown-result сценариев. FakePaymentProvider обеспечивает детерминированную локальную разработку без Stripe account.

### 15.3 Cucumber acceptance tests

Cucumber-JVM запускается через JUnit Platform. Gherkin описывает только бизнес-сценарии, а не каждую низкоуровневую проверку. Acceptance environment использует настоящий Spring Boot application и PostgreSQL Testcontainer с FakePaymentProvider.

Обязательные feature groups:

- catalog payment;
- dynamic server payment;
- donation limits и anonymous donation;
- subscription creation, trial, plan change и cancellation;
- failed renewal;
- full и partial refunds;
- idempotent commands;
- duplicate и out-of-order provider events;
- unknown provider result и reconciliation;
- per-site customer isolation;
- OIDC, site token и email OTP;
- signed site callbacks, retries и dead delivery;
- payment account routing.

Сценарии реального Stripe помечаются `@stripe-sandbox` и не входят в default local test suite.

### 15.4 Frontend tests

Admin и SDK получают component/unit tests. Playwright проверяет критические browser journeys на demo-site и в admin. Stripe Payment Element не имитируется как собственная card form; sandbox E2E проверяет его отдельно после создания Stripe account.

## 16. Локальный запуск и наблюдаемость

Default Docker Compose поднимает:

```text
postgres
mailpit
backend
admin
demo-site
```

FakePaymentProvider включён в локальном профиле. Seed data создаёт два сайта, каталожную цену, месячную и годовую подписки и donation campaign. Это позволяет проверить изоляцию одинакового email между сайтами.

После появления Stripe account отдельный sandbox profile использует Stripe test keys и Stripe CLI для forwarding webhook events.

Backend предоставляет liveness и readiness endpoints, структурированные JSON logs, request correlation и базовые metrics. Код готов к OpenTelemetry exporter, но отдельные Prometheus, Grafana и tracing backend не входят в MVP.

## 17. Действия владельца, необходимые позднее

До начала разработки Stripe account не обязателен. Для sandbox E2E и live запуска владелец должен:

1. создать Stripe account для индивидуального предпринимателя в Португалии;
2. создать Stripe Sandbox;
3. локально вне исходного кода указать sandbox publishable key и secret key;
4. активировать необходимые payment methods;
5. при использовании PayPal создать или подключить PayPal account;
6. зарегистрировать каждый домен и subdomain для Apple Pay, Google Pay и других domain-bound methods;
7. настроить live webhook endpoint и его signing secret;
8. выбрать production SMTP/email provider;
9. подтвердить VAT, invoicing и reporting requirements с португальским бухгалтером;
10. выбрать cloud provider, настроить HTTPS, secrets storage, PostgreSQL backups и monitoring.

Секретные ключи не передаются в чат и не добавляются в репозиторий. Проект предоставляет `.env.example` только с именами переменных.

## 18. Критерии приёмки MVP

MVP считается готовым, когда:

- default Docker Compose запускает всю систему без Stripe account;
- администратор создаёт сайт, origins, authentication modes и каталог через UI;
- demo-site выполняет каталожный платёж, динамический server payment, донат и подписку;
- один email на двух сайтах создаёт независимых Customers;
- статический режим не позволяет изменить цену каталожного товара;
- динамический обычный платёж доступен только Partner API;
- повтор команды с тем же idempotency key не создаёт вторую операцию;
- duplicate и out-of-order provider events не повреждают состояние;
- каждый подтверждённый денежный факт появляется в append-only PaymentEvent journal;
- обновление projection и создание OutboxEvent атомарны;
- callback сайта подписан, повторяется при временной ошибке и виден в admin;
- Customer управляет подпиской через встроенный SDK UI;
- admin выполняет полный и частичный refund;
- unit, integration, Cucumber и Playwright default suites проходят локально;
- sandbox profile документирован и готов принять Stripe keys без изменения кода;
- в логах отсутствуют секреты, card data, OAuth tokens и полные provider payloads.

## 19. Принятые архитектурные решения

- Центральный сервис вместо копии payment backend на каждом сайте.
- Модульный монолит вместо преждевременных микросервисов.
- Hexagonal architecture внутри каждого бизнес-модуля.
- Java и Spring Boot backend с Maven Wrapper.
- PostgreSQL как transactional store.
- Next.js admin и отдельный framework-agnostic TypeScript SDK.
- Собственный встроенный subscription UI; Stripe Customer Portal — fallback.
- Append-only payment journal плюс пересчитываемые projections.
- Transactional inbox/outbox для provider и site events.
- Один Stripe account сейчас, явный PaymentAccount routing для будущего разделения.
- Изоляция Customer по Site.
- FakePaymentProvider до создания Stripe account.
- Cucumber-JVM как acceptance/BDD слой, а не замена unit tests.

## 20. Декомпозиция реализации

Полный MVP слишком велик для одного безопасного implementation plan. Реализация делится на законченные вертикальные этапы; каждый следующий этап начинается после прохождения тестов предыдущего.

1. **Foundation и каталог** — структура monorepo, hexagonal backend skeleton, PostgreSQL/Flyway, Docker Compose, Site/PaymentAccount/Product/Price, bootstrap admin authentication, базовая admin UI и FakePaymentProvider.
2. **Разовые платежи** — каталожный и динамический payment flows, PaymentAttempt, append-only PaymentEvent journal, idempotency, provider inbox, transactional outbox, callbacks, refunds, SDK payment widget и Cucumber acceptance suite.
3. **Customer access и донаты** — site tokens, OIDC validation, email OTP/Mailpit, SDK sessions, DonationCampaign, публичный donation widget и per-site customer isolation.
4. **Подписки** — recurring prices, trial, renewal events, plan changes, cancellation, SetupIntent, custom subscription manager и Customer Portal fallback.
5. **Stripe Sandbox и hardening** — реальный Stripe adapter E2E, Payment Element, wallet/domain checklist, PayPal readiness, reconciliation, Playwright journeys, security review, metrics и production deployment guide.

Первый implementation plan покрывает только этап 1. Последующие планы используют этот документ как общий контракт и детализируют соответствующий этап перед реализацией.
