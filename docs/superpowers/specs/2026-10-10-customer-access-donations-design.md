# Этап 3 — Customer Access и донаты

Дата: 2026-10-10

## 1. Цель

Добавить к готовому потоку разовых платежей изолированных по Site клиентов, безопасный доступ из browser SDK и разовые донаты. Этап полностью работает локально без Stripe-аккаунта через существующий `FakePaymentProvider`.

Stage 3 должен поддерживать:

- Customer, принадлежащего ровно одному Site;
- вход через `SITE_TOKEN`, generic OIDC или email OTP;
- короткоживущую browser SDK session;
- явное linking нескольких identities без автоматического объединения по email;
- удаление персональных данных через необратимую anonymization;
- optional Customer linkage для существующих catalog и Partner dynamic payments;
- чтение Customer собственной истории платежей и донатов через headless API;
- несколько DonationCampaign на Site;
- анонимные и авторизованные разовые донаты;
- существующий асинхронный payment/provider/event/outbox/callback flow;
- Admin UI, framework-agnostic SDK и встраиваемые Web Components;
- два последовательных milestone с отдельной приёмкой: Customer Access, затем Donations.

Stage 3 не добавляет центральный кабинет Customer, подписки или реальный Stripe adapter.

## 2. Исходное состояние и ограничения

Этап продолжает реализацию из:

- общей спецификации `docs/superpowers/specs/2026-09-12-generic-payment-platform-design.md`;
- Stage 1 Foundation/Catalog;
- спецификации Stage 2 `docs/superpowers/specs/2026-10-04-one-time-payments-design.md`;
- полностью принятой реализации Stage 2.

Сохраняются ограничения:

- backend — Java 25, Spring Boot 4.1.1 и Maven Wrapper;
- backend — модульный монолит с hexagonal architecture;
- domain-код не зависит от Spring, JPA, HTTP, Jackson, SMTP, JWT libraries и provider SDK;
- PostgreSQL является transactional store;
- admin остаётся отдельным Next.js приложением;
- SDK не зависит от React и Next.js;
- browser не передаёт доверенные currency, tax или provider значения;
- денежные суммы хранятся в integer minor units;
- идентификаторы доменных сущностей — UUID;
- время создаётся через внедрённый `Clock`;
- secrets поступают через environment или заменяемые secret ports;
- проект остаётся локальным: Git-команды, commits и push не выполняются;
- production-функции реализуются через red-green-refactor;
- существующие Stage 1–2 контракты сохраняются, кроме явно описанных additive extensions.

## 3. Scope

### 3.1 Входит в Stage 3A — Customer Access

- новый backend-модуль `customers`;
- site-scoped Customer и CustomerIdentity;
- generic configuration для нескольких OIDC issuers на Site;
- `SITE_TOKEN` verification через site-specific asymmetric keys;
- email OTP, generic SMTP adapter и Mailpit;
- transactional mail outbox;
- короткоживущие opaque SDK sessions;
- headless SDK API и необязательный email OTP Web Component;
- явное identity linking;
- Customer anonymization;
- optional authenticated catalog checkout и Customer payment history;
- Admin и Partner API для разрешённых Customer operations;
- audit, metrics, Cucumber, Compose и browser checks для Customer Access.

### 3.2 Входит в Stage 3B — Donations

- новый backend-модуль `donations`;
- несколько DonationCampaign на Site;
- suggested и custom amounts;
- анонимные и авторизованные разовые донаты;
- зашифрованное необязательное сообщение донора;
- новый `PaymentSource.DONATION`;
- donation snapshot внутри Payment;
- расширение Admin payments/donations UI;
- headless SDK и donation Web Component;
- безопасные callback extensions;
- Customer donation history extension;
- Cucumber, Playwright и полный Compose smoke.

### 3.3 Не входит в этап

- центральный Customer cabinet;
- login redirects или social login buttons, которыми владеет платёжная платформа;
- автоматическое объединение identities по email;
- объединение двух уже существующих Customer;
- refresh tokens и browser-local persistent login;
- подписки, trials и recurring donations;
- Stripe account, Stripe adapter и Stripe Payment Element;
- реальные Apple Pay, Google Pay, PayPal или card operations;
- публичная fundraising goal и collected amount counter;
- публичное отображение donor messages;
- автоматические квитанции, инвойсы и налоговые документы;
- выбор конкретного cloud или email provider;
- production deployment;
- фиксирование юридического срока хранения данных внутри кода.

## 4. Согласованные решения

1. Customer всегда принадлежит одному Site; одинаковый email на разных сайтах не связывает данные.
2. Платформа поддерживает `SITE_TOKEN`, direct OIDC token verification и email OTP.
3. Login UI и OAuth redirects остаются на подключённом сайте.
4. Чистый OAuth без OIDC проходит через backend сайта, который выдаёт `SITE_TOKEN`.
5. Production `SITE_TOKEN` использует только asymmetric signatures; HMAC разрешён лишь в local/test fixtures.
6. SDK session является короткоживущим opaque token и хранится только в `sessionStorage`.
7. Identity не объединяются автоматически по совпавшему email.
8. Linking требует действующую Customer session и свежее доказательство новой identity либо доверенную Partner-команду.
9. Anonymization удаляет PII, identities, sessions, OTP и связанные donor messages, но сохраняет обезличенные финансовые факты.
10. Центральный Customer cabinet не создаётся; сайт использует headless SDK или встраиваемые компоненты.
11. Site может иметь несколько DonationCampaign с уникальными `campaign_key`.
12. Stage 3 поддерживает только разовые донаты.
13. Анонимный донат не собирает имя или email; он может содержать только сумму и необязательное сообщение.
14. Публичные goal/progress counters не входят в этап.
15. Донат использует существующий asynchronous payment flow; browser или synchronous provider response не подтверждает денежный факт.
16. Donor message не попадает в append-only PaymentEvent или immutable callback payload.
17. SMTP adapter остаётся provider-neutral; локально используется Mailpit.
18. Stage 3A полностью принимается до начала Stage 3B.
19. Catalog checkout с действующей SDK session связывает Payment с Customer; отсутствие session сохраняет guest-семантику, а явно переданная invalid session завершает request ошибкой.
20. Partner dynamic payment может передать optional `customer_id`; принимается только `ACTIVE` Customer того же Site, а отсутствие поля сохраняет guest-семантику.
21. Customer читает только собственную paginated историю Payments/Donations внутри текущего Site через headless API; центральный кабинет по-прежнему не создаётся.

## 5. Архитектура модулей

### 5.1 `customers`

Модуль владеет:

- `Customer`;
- `CustomerIdentity`;
- `IdentityProviderConfiguration`;
- `CustomerSession`;
- `EmailOtpChallenge`;
- `OidcAuthenticationAttempt` и consumed SITE_TOKEN identifiers;
- identity linking и anonymization rules;
- authentication application services;
- Public, Customer, Partner и Admin adapters;
- PostgreSQL persistence своих агрегатов;
- mail outbox и его worker.

Модуль использует порты для:

- загрузки Site и его authentication policy;
- asymmetric token verification;
- JWKS fetch/cache;
- keyed digest и encryption;
- random token/code generation;
- отправки email;
- audit и bounded telemetry.

Domain и application layers не зависят от Nimbus/Jose, Spring Security, JavaMail или конкретного SMTP provider.

### 5.2 `donations`

Модуль владеет:

- `DonationCampaign`;
- `Donation`;
- campaign lifecycle;
- validation suggested/custom amount;
- donor message lifecycle;
- donation checkout orchestration;
- Admin, Public и Partner adapters;
- PostgreSQL persistence своих агрегатов.

Модуль создаёт Payment только через payment application port. Он не обращается напрямую к payment JPA repository или таблицам.

### 5.3 `payments`

Модуль получает additive extension:

- `PaymentSource.DONATION`;
- nullable `donation_id`;
- immutable donation campaign snapshot;
- создание donation Payment с optional `customer_id`;
- optional Customer linkage в существующих catalog и Partner dynamic creation flows;
- safe paginated query собственной Customer payment/donation history;
- admin filters и details для Campaign/Customer;
- safe callback context для donation.

При отсутствии Customer context existing catalog и dynamic flows сохраняют guest-семантику. Явно переданный invalid, expired или cross-Site Customer context не игнорируется и завершает operation ошибкой. Customer-facing history использует application/query port модуля `payments`; Customer adapters не обращаются напрямую к payment repository или таблицам.

Stage 3 migration добавляет composite foreign key от nullable `(site_id, customer_id)` Payment к Customer и сохраняет все существующие `NULL` guest rows. Migration имеет fail-fast precondition для любых legacy non-null orphan values и не создаёт фиктивных Customer автоматически.

### 5.4 `sites`, `admin`, SDK и demo

`sites` продолжает владеть списком разрешённых authentication modes и Origin allowlist. Customer-модуль владеет конкретными issuer/key configurations.

Admin добавляет Site authentication settings, Customers и DonationCampaign/Donation screens. SDK предоставляет headless methods и Web Components. Demo-site демонстрирует email OTP и donation journeys, но не имитирует Google или Apple UI.

## 6. Customer domain

### 6.1 Customer

`Customer` содержит:

- `CustomerId id`;
- `SiteId siteId`;
- `CustomerStatus status`: `ACTIVE` или `ANONYMIZED`;
- nullable encrypted normalized email;
- nullable site-scoped email lookup digest;
- `createdAt`, `updatedAt`, `anonymizedAt`;
- optimistic `version`.

Инварианты:

- Customer не может менять Site;
- persistence гарантирует unique `(site_id, id)` для composite ownership references;
- email нормализуется одним versioned algorithm перед digest/encryption;
- raw и normalized email не хранятся в открытом виде;
- `ANONYMIZED` является необратимым состоянием;
- anonymized Customer не получает новые sessions и identities;
- повторная аутентификация удалённой identity создаёт нового Customer и не восстанавливает старую финансовую историю.

Email является атрибутом профиля и поисковым hint внутри Site, но не глобальным identity key и не основанием для автоматического merge.

### 6.2 CustomerIdentity

Identity определяется комбинацией:

```text
(site_id, issuer_key, subject_digest)
```

`CustomerIdentity` содержит:

- identity ID;
- Customer и Site ID;
- authentication mode;
- стабильный `issuer_key`;
- keyed digest от canonical subject;
- verified timestamp;
- created/last-used timestamps.

Raw `subject`, raw tokens, authorization codes и provider refresh tokens не сохраняются. Для email OTP canonical subject является нормализованным email; для OIDC и SITE_TOKEN — валидированный `sub`.

Одна identity принадлежит не более чем одному Customer. Identity, уже принадлежащая другому Customer, не может быть linked; merge двух Customer не входит в Stage 3.

### 6.3 Identity provider configuration

Site может иметь несколько configurations:

- `OIDC_TOKEN`: provider key, issuer, accepted audiences, JWKS source, allowed algorithms и optional verified-email claim mapping;
- `SITE_TOKEN`: issuer, accepted audiences, JWKS source или сохранённый public JWK set и allowed algorithms;
- `EMAIL_OTP`: site-level enablement и sender/template settings без SMTP password в БД.

Provider key уникален внутри Site. Configuration имеет `ACTIVE`/`DISABLED` status и optimistic version. Уже созданные identities используют стабильный issuer key даже после disable configuration.

## 7. Token verification и SDK sessions

### 7.1 OIDC и SITE_TOKEN validation

Production allowlist algorithms: `ES256` и `RS256`. Проверяются:

- signature;
- exact issuer;
- configured audience;
- `azp`, когда OIDC token содержит несколько audiences;
- non-blank subject;
- `exp`, `nbf` и допустимый clock skew;
- one-time OIDC nonce либо one-time SITE_TOKEN `jti`;
- active Site, authentication mode и provider configuration;
- `kid` и принадлежность ключа настроенному JWKS.

Algorithm confusion, `none`, symmetric signatures и key data из token headers отклоняются. Token не может задавать JWKS URL.

Unknown `kid` допускает один bounded forced refresh JWKS cache. Ошибка issuer/JWKS закрывает только новую authentication operation и не нарушает существующий payment flow.

Direct OIDC начинается с короткоживущего `OidcAuthenticationAttempt`, привязанного к Site, provider key и Origin. Backend возвращает random attempt ID и nonce; основной Site передаёт nonce выбранному OIDC provider. При exchange backend проверяет token `nonce` и атомарно потребляет attempt. Redirect, state и login UI остаются ответственностью Site.

SITE_TOKEN обязан иметь короткий lifetime и уникальный `jti`. Backend хранит digest потреблённого `jti` до expiry и разрешает ровно один exchange. Это не даёт повторно превратить перехваченный source token в новые SDK sessions.

Email из OIDC используется только при configured claim mapping и подтверждённом `email_verified`. SITE_TOKEN email считается подтверждённым лишь при явном trusted claim contract. Отсутствие email не мешает identity создать Customer.

### 7.2 JWKS source

Поддерживаются:

- static public JWK set, сохранённый в Site configuration;
- remote HTTPS JWKS URL.

Remote fetch:

- запрещает user-info, fragments и redirects;
- запрещает loopback, private, link-local, multicast, metadata endpoints и IPv4-mapped обходы;
- повторно проверяет resolved address при каждом connect;
- использует bounded connect/read timeout и response-size limit;
- принимает только корректный JWK set с ограниченным количеством keys;
- кэширует успешный результат с bounded TTL;
- не использует stale keys после configurable maximum stale window.

Local/test profile может разрешить явно перечисленные local issuer hosts. Production игнорирует local allowlist.

### 7.3 SDK session

После успешной аутентификации backend создаёт cryptographically random opaque token. Browser получает token только один раз; PostgreSQL хранит только keyed digest.

Session содержит:

- Site и Customer ID;
- exact Origin;
- scopes;
- issued/last-used/expires timestamps;
- revoked timestamp/reason;
- token digest version.

Default absolute TTL — 30 минут. Sliding extension и refresh token отсутствуют. Session хранится SDK только в `sessionStorage`, не в `localStorage`, cookies или URL. Закрытие browser tab требует нового входа.

Каждый authenticated request проверяет active Site/Customer/session, Origin, scope и expiry. Anonymization или explicit logout немедленно revokes sessions.

## 8. Email OTP

### 8.1 Challenge

Public request принимает `site_key` и email при разрешённом `EMAIL_OTP`. Backend всегда возвращает одинаковый `202 Accepted` shape для допустимого запроса независимо от существования Customer.

Challenge содержит:

- random public challenge ID;
- Site ID;
- email lookup digest;
- encrypted normalized email;
- keyed OTP digest;
- attempt count;
- expiry, consumed и invalidated timestamps;
- Origin;
- resend lineage.

Default policy:

- numeric cryptographically random code;
- TTL 10 минут;
- максимум 5 verification attempts;
- resend cooldown 60 секунд;
- предыдущий active challenge для того же Site/email/Origin инвалидируется при успешном resend.

OTP digest использует server-side secret key, поэтому low-entropy code нельзя перебрать offline только по содержимому challenge. Raw code существует в памяти только во время генерации/rendering и SMTP send. Для асинхронной доставки rendered message и recipient сохраняются в mail outbox только как versioned encrypted ciphertext и никогда не логируются.

### 8.2 Rate limiting

Security-critical limits хранятся в PostgreSQL и действуют между replicas. Ключи лимитов:

- Site;
- Site + keyed email digest;
- Site + keyed source-IP digest;
- challenge verification attempts.

Raw IP и email не сохраняются в rate-limit storage. Entries имеют TTL и cleanup job. Forwarded headers принимаются только от явно настроенных trusted proxies.

Rate limiting может вернуть `429`, но не раскрывает существование Customer или email. Значения лимитов конфигурируются в безопасных bounded пределах; defaults фиксируются implementation plan.

### 8.3 Verification

Успешная verification в одной транзакции:

1. блокирует challenge;
2. проверяет expiry, consumed state и attempt count;
3. сравнивает keyed digest constant-time;
4. загружает или создаёт email identity внутри Site;
5. создаёт либо находит связанный ACTIVE Customer;
6. помечает challenge consumed;
7. выдаёт SDK session.

Concurrent verification одного challenge создаёт не более одной session result и не создаёт duplicate Customer/identity.

## 9. Mail delivery

Создание OTP challenge и mail-outbox job выполняется в одной PostgreSQL transaction. SMTP вызов никогда не выполняется внутри этой transaction.

Mail worker:

- claims bounded batches с lease;
- повторяет только transient errors с bounded exponential backoff;
- завершает permanent failures без бесконечных retries;
- восстанавливает abandoned leases;
- не логирует recipient, subject data или OTP;
- уничтожает encrypted recipient/message payload после успешной доставки или terminal retention deadline, оставляя только safe delivery metadata;
- публикует bounded metrics.

Local Compose использует Mailpit. Production adapter использует SMTP с TLS/STARTTLS и credentials из environment/secret reference. Если активный Site разрешает EMAIL_OTP, а production mail capability не настроена, OTP capability fail-closed и readiness сообщает configuration failure; fake delivery не включается.

## 10. Identity linking

Identity linking доступен двумя способами:

1. Customer request с действующей SDK session и свежим proof новой identity;
2. Partner API с Site API key, соответствующим scope, `Idempotency-Key` и подтверждёнными issuer/subject claims доверенного site backend.

Правила:

- target Customer обязан быть ACTIVE и принадлежать тому же Site;
- новая identity проходит полную validation своего authentication mode;
- proof имеет короткий freshness window и одноразовое использование;
- identity без владельца присоединяется атомарно;
- identity того же Customer возвращает idempotent success;
- identity другого Customer возвращает conflict и не выполняет merge;
- email совпадение не изменяет результат;
- действие фиксируется в audit без subject/token/email.

Headless SDK предоставляет linking operation. Готовый account-management UI не входит в Stage 3.

## 11. Anonymization

Anonymization вызывается:

- Partner API от backend Site;
- Admin API с confirmation и `Idempotency-Key`.

Операция в одной transaction:

- блокирует Customer;
- переводит его в `ANONYMIZED`;
- revokes все CustomerSession;
- invalidates active OTP challenges, связанные с email digest;
- удаляет email ciphertext/digest;
- удаляет CustomerIdentity rows;
- удаляет donor messages для Payments этого Customer;
- создаёт безопасную audit entry.

Payment, PaymentEvent, Refund и provider facts не удаляются. Их `customer_id` остаётся ссылкой на anonymized Customer без PII. Immutable events и callback snapshots могут сохранять Customer UUID, но не email, subject или donor message.

Anonymization повторяется идемпотентно. Восстановление Customer и merge нового Customer со старой историей не поддерживаются.

## 12. DonationCampaign

Campaign содержит:

- `DonationCampaignId id`;
- Site ID;
- `campaignKey`, уникальный внутри Site;
- display name и purpose description;
- ISO-4217 currency;
- ordered unique suggested amounts;
- minimum и maximum amount;
- `customAmountAllowed`;
- `anonymousAllowed`;
- `CampaignStatus`: `DRAFT`, `ACTIVE`, `PAUSED`, `ARCHIVED`;
- timestamps и optimistic version.

Инварианты:

- key использует тот же safe-key формат, что Site/Product keys;
- currency фиксируется до первой activation и затем неизменяема;
- minimum и maximum положительны, minimum не больше maximum;
- suggested amounts уникальны и находятся внутри диапазона;
- если custom amount запрещён, допустимы только suggested amounts;
- только `ACTIVE` Campaign создаёт checkout;
- lifecycle: `DRAFT -> ACTIVE`, `ACTIVE <-> PAUSED`, любое неархивное состояние `-> ARCHIVED`;
- `ARCHIVED` является конечным;
- изменение config увеличивает version, но не меняет snapshot существующих Payments.

Stage 3 не хранит goal, public total или recurring policy.

## 13. Donation и donor message

`Donation` создаётся до Payment и содержит:

- Donation ID;
- Site и Campaign ID;
- nullable encrypted donor message;
- message state: `PRESENT`, `ABSENT` или `REDACTED`;
- timestamps.

Customer принадлежность является частью связанного Payment и не дублируется в Donation row.

Message:

- optional;
- plain text, максимум 500 Unicode code points;
- нормализуется в Unicode NFC;
- запрещает control characters, HTML interpretation и hidden markup semantics;
- шифруется existing versioned data-encryption port;
- отображается только escaped text в Admin;
- может быть redacted отдельно Admin-командой;
- удаляется при anonymization связанного Customer;
- не копируется в PaymentEvent, outbox callback, metrics или logs.

Для anonymous Donation message не связан с identity. Его redaction выполняется Admin-командой или retention policy, но не через Customer anonymization.

## 14. Donation Payment

`PaymentSource` расширяется значением `DONATION`. Donation Payment содержит:

- обязательный `donation_id`;
- optional Customer ID;
- immutable campaign snapshot: Campaign ID, key, version и currency;
- amount из validated public request;
- `tax_mode=NONE`;
- отсутствие Price и external order ID.

Database shape constraints делают `CATALOG`, `DYNAMIC` и `DONATION` взаимоисключающими. Composite foreign keys гарантируют одинаковый Site у Customer, Donation, Campaign и Payment.

Donation orchestration в одной local transaction:

1. проверяет Site, Origin и active Campaign;
2. проверяет optional SDK session;
3. проверяет amount и anonymous policy;
4. начинает idempotency operation;
5. создаёт Donation;
6. создаёт Payment projection и initial PaymentEvent через payment application port;
7. сохраняет campaign snapshot;
8. создаёт checkout session;
9. завершает idempotency record.

Provider preparation и confirmation сохраняют transaction-split Stage 2. Donation service не вызывает provider внутри transaction создания локальных агрегатов.

Если request содержит invalid/expired SDK token, он завершается authentication error и никогда не деградирует до anonymous Donation.

Donation idempotency fingerprint содержит resolved Customer ID либо явный anonymous marker, но не raw SDK token. Повтор того же `Idempotency-Key` с другим identity context возвращает idempotency conflict.

## 15. API contracts

Точные DTO names и OpenAPI operation IDs фиксируются implementation plan, но semantic endpoints Stage 3 следующие.

### 15.1 Public authentication API

- exchange SITE_TOKEN;
- начать одноразовый OIDC authentication attempt и получить nonce;
- exchange OIDC ID token для configured provider key;
- request email OTP challenge;
- verify email OTP challenge;
- exchange свежего identity proof для linking.

Credentials передаются в request body или `Authorization` header согласно endpoint contract, помечаются sensitive для logging filters и никогда не помещаются в URL.

Session response содержит только:

- one-time raw SDK session token;
- expiry;
- scopes;
- safe Customer view: ID, status и masked email при наличии.

### 15.2 Customer API

Customer API требует SDK Bearer session и exact allowed Origin:

- получить текущий safe Customer view;
- получить собственную cursor-paginated историю Payments;
- получить собственную cursor-paginated историю Donations;
- завершить текущую session;
- link новую identity.

History всегда ограничена Customer и Site из проверенной session. Она возвращает только safe product-facing поля: public IDs, source, amount, currency, public status, timestamps и допустимый catalog/donation context. Provider payloads, internal events, identity metadata, donor message, Admin actions и данные других Customer не возвращаются. Стабильная сортировка — `created_at DESC, id DESC`; page size имеет server-side maximum. Cursor является opaque, integrity-protected и привязан к Site, Customer и query shape. Центральный account UI в Stage 3 не предоставляется.

### 15.3 Existing checkout extensions

Catalog checkout принимает optional SDK Bearer session. Действующая session того же Site и Origin связывает создаваемый Payment с Customer; отсутствие session создаёт guest Payment. Явно переданная invalid, expired, anonymized или cross-Site session возвращает authentication error и не деградирует до guest.

Partner dynamic payment принимает optional `customer_id`. При наличии backend проверяет, что Customer существует, имеет статус `ACTIVE` и принадлежит Site текущего API key. Unknown, anonymized или cross-Site Customer отклоняется единообразно. При отсутствии `customer_id` existing guest behavior и contract сохраняются.

Resolved Customer ID либо явный guest marker входит в idempotency fingerprint catalog/dynamic operation. Raw SDK token в fingerprint и persistence не попадает; повтор того же `Idempotency-Key` с другим Customer context возвращает idempotency conflict.

### 15.4 Public donation API

Создание donation checkout принимает:

- `site_key`;
- `campaign_key`;
- `amount_minor`;
- optional donor message;
- обязательный `Idempotency-Key`;
- optional SDK Bearer session.

Currency, tax, Customer ID, Site ID и provider fields из browser body не принимаются. Response переиспользует существующий checkout contract с additive donation context.

### 15.5 Partner API

Partner API использует Site API key scopes и Site isolation:

- получить Customer safe view по ID;
- запросить anonymization;
- link доверенную identity к Customer;
- получить existing Payment state по Payment ID с nullable `customer_id` для собственного Site;
- получить Donation/Payment state по Donation ID.

Mutation требует `Idempotency-Key`. API key одного Site не читает и не изменяет Customer/Donation другого Site.

### 15.6 Admin API

Admin API добавляет:

- CRUD/lifecycle identity provider configurations;
- Customer list/details и anonymization;
- DonationCampaign create/update/lifecycle/list;
- Donation list/details/message redaction;
- Payment filters по Customer/Campaign/Donation;
- audit entries для всех security-sensitive mutations.

Secrets и one-time credentials не возвращаются list/details endpoints.

## 16. Callback и финансовый журнал

Donation использует существующие PaymentEvent types и provider inbox. Success, failure, refunds и reconciliation следуют Stage 2 rules.

Donation callback использует `schema_version=2` и получает обязательный donation context:

- `donation_id`;
- `campaign_key`;
- nullable `customer_id`.

Catalog/dynamic callbacks продолжают использовать неизменённый Stage 2 `schema_version=1`. Existing v1 receivers не получают дополнительные поля. Canonical serialization и signature verification являются version-aware; Stage 3 demo receiver принимает обе версии. Callback никогда не содержит:

- email;
- raw/normalized subject;
- identity provider token;
- SDK session;
- OTP;
- donor message.

Существующий at-least-once delivery и deduplication по event ID сохраняются. Версия callback не меняет источник финансовой истины.

## 17. Security

### 17.1 Site и Origin isolation

- Site определяется server-side из configured site key/token context;
- Customer/identity/session/campaign/donation/payment ownership проверяется composite keys и application rules;
- Customer history, catalog session linkage и Partner dynamic `customer_id` авторизуются server-side по Site и Customer context, а не по browser-supplied ownership fields;
- exact Origin обязан входить в Site allowlist;
- session привязана к Origin обмена;
- CORS response не отражает произвольный Origin;
- `null` Origin не разрешён browser flows.

### 17.2 Secrets и PII

- tokens, OTP, email, raw subject, SMTP password и donor message не логируются;
- encrypted fields используют versioned encryption metadata;
- lookup digests используют отдельный versioned keyed-digest secret;
- logs и audit содержат только safe IDs, modes, outcomes и correlation ID;
- request/response body tracing отключён для authentication endpoints;
- error responses не раскрывают существование Customer или identity.

### 17.3 Abuse protection

- request-size limits применяются до parsing;
- OTP имеет distributed rate limits;
- token exchange, identity linking и donation creation имеют bounded rate limits;
- invalid credential errors используют uniform public shape;
- timing-sensitive digest comparisons constant-time;
- JWKS и SMTP имеют bounded timeout/retry;
- Admin anonymization требует confirmation UX и disabled duplicate submission.

### 17.4 Browser storage

SDK хранит только session token и safe session metadata в `sessionStorage`. OIDC/SITE source tokens не сохраняются библиотекой после exchange. OTP code не сохраняется. `localStorage` запрещён.

## 18. Error model

Stage 3 использует existing safe error envelope и добавляет bounded public codes:

- `AUTHENTICATION_FAILED`;
- `AUTHENTICATION_MODE_UNAVAILABLE`;
- `IDENTITY_LINK_CONFLICT`;
- `OTP_INVALID_OR_EXPIRED`;
- `OTP_RATE_LIMITED`;
- `SESSION_EXPIRED`;
- `CUSTOMER_ANONYMIZED`;
- `CUSTOMER_NOT_AVAILABLE`;
- `CAMPAIGN_NOT_AVAILABLE`;
- `AMOUNT_NOT_ALLOWED`;
- `ANONYMOUS_DONATION_NOT_ALLOWED`;
- `MAIL_CAPABILITY_UNAVAILABLE` для operational/admin diagnostics, но не для email enumeration.

Public message не содержит provider/JWKS/SMTP exception, token claim, email или existence hint. Correlation ID связывает safe response с internal diagnostic event.

## 19. Admin UI

### 19.1 Site authentication settings

Admin показывает:

- enabled authentication modes;
- OIDC provider list и status;
- issuer, audiences, JWKS source type и safe key metadata;
- SITE_TOKEN configuration;
- EMAIL_OTP capability/readiness;
- last successful JWKS refresh и safe failure state.

Raw keys, tokens и SMTP credentials не отображаются.

### 19.2 Customers

Customer list фильтруется по Site, status, Customer ID и masked email lookup. Details показывают safe identity metadata: mode, issuer key и timestamps без subject.

Anonymization требует confirmation dialog, pending state, duplicate-submit protection и focus restoration. UI явно сообщает, что финансовые факты останутся, а PII и сообщения будут удалены необратимо.

### 19.3 Campaigns и Donations

Campaign screens поддерживают create/edit/lifecycle и client-side preview allowed amounts. Server остаётся источником validation.

Donation list/details показывают Site, Campaign, Customer presence, amount, Payment status, timestamps и escaped message. Фильтры включают Site, Campaign, Customer, Donation ID, Payment ID/status и date range.

Message redaction требует confirmation. Refund/reconciliation используют существующие Payment actions.

## 20. SDK и Web Components

Framework-agnostic TypeScript SDK добавляет:

- `exchangeSiteToken`;
- `exchangeOidcToken`;
- `requestEmailOtp`;
- `verifyEmailOtp`;
- `getCurrentCustomer`;
- `listCustomerPayments`;
- `listCustomerDonations`;
- `linkIdentity`;
- `logoutCustomerSession`;
- `createDonationCheckout`;
- abortable state watchers.

SDK:

- сохраняет SDK session только в `sessionStorage`;
- не сохраняет source identity token;
- автоматически прикладывает действующую session к catalog и donation checkout requests того же API base, Site и Origin;
- очищает session при expiry/logout/anonymization;
- не отправляет session на другой API base, Site или Origin;
- не считает synchronous response окончательным payment result.

Web Components:

- optional email OTP sign-in component;
- donation component с suggested/custom amount, message и payment state;
- design tokens, locale и translations;
- isolated styles и accessible labels/focus/error announcements;
- возможность заменить UI полностью через headless SDK.

OAuth buttons и redirect callbacks принадлежат основному Site.

## 21. Observability и audit

Metrics имеют bounded labels:

- authentication mode;
- safe outcome enum;
- mail outcome enum;
- session operation;
- donation/payment/provider outcome.

Запрещены metric labels: email, Customer ID, Campaign key/ID, issuer URL, IP, subject, token ID и Origin.

Добавляются:

- auth exchange counters/latency;
- OTP requested/verified/rejected/rate-limited counters;
- active/pending mail-outbox gauges и delivery counters;
- session issued/revoked/expired counters;
- identity link outcomes;
- anonymization outcomes;
- donation checkout outcomes.

Audit фиксирует actor, Site, resource IDs, action, safe outcome, request/correlation ID и timestamp. Audit не содержит удаляемые PII или credentials.

## 22. Test strategy

### 22.1 Stage 3A unit tests

- Customer и identity invariants;
- normalization, digest versioning и safe masking;
- token claims/algorithm rules;
- OTP lifecycle, attempt limits и expiry;
- OIDC nonce и SITE_TOKEN `jti` replay protection;
- session scopes, Origin binding и expiry;
- identity linking conflicts/idempotency;
- anonymization and repeat behavior.

### 22.2 Stage 3A integration/contract tests

- PostgreSQL site isolation и composite constraints;
- Payment Customer-FK migration сохраняет guest rows и fail-fast отклоняет legacy orphan values;
- concurrent identity creation и OTP verification;
- concurrent linking/anonymization/session use;
- catalog checkout linkage для valid session и fail-closed поведение invalid/expired/cross-Site session;
- Partner dynamic payment с active same-Site Customer и отказ для unknown/anonymized/cross-Site Customer;
- Customer history isolation, cursor pagination и stable ordering;
- OIDC/SITE_TOKEN signature, issuer, audience, time claims и key rotation;
- OIDC attempt binding/nonce consumption и SITE_TOKEN `jti` consumption;
- unknown `kid` bounded refresh;
- JWKS SSRF, redirects, DNS/address changes, timeout и size limits;
- distributed OTP rate limits;
- mail outbox claim/lease/retry/restart behavior;
- SMTP temporary/permanent failures;
- log/audit scan на tokens, OTP, email и subject.

### 22.3 Stage 3A Cucumber и browser checks

Business scenarios используют real Spring HTTP boundaries и PostgreSQL:

- SITE_TOKEN exchange;
- OIDC exchange;
- email OTP through Mailpit;
- одинаковый email на двух Site создаёт независимых Customer;
- cross-Site session rejection;
- explicit identity linking;
- linking conflict без merge;
- authenticated catalog checkout связывает Payment с Customer;
- guest catalog checkout сохраняет прежнюю семантику;
- invalid supplied catalog session завершается ошибкой, а не guest fallback;
- Customer видит только собственную историю текущего Site;
- anonymization удаляет PII и сохраняет payment facts.

Milestone 3A принимается только после полного Stage 1–3A regression run и independent review.

### 22.4 Stage 3B tests

- Campaign domain/lifecycle/amount invariants;
- persistence ownership и concurrent updates;
- anonymous allowed/forbidden;
- suggested/custom amount validation;
- authenticated Customer binding;
- invalid session cannot fall back to anonymous;
- donation idempotency fingerprint различает resolved Customer и guest context;
- idempotent replay и fingerprint conflict;
- donation/payment atomic creation;
- provider success/failure/retry/refund/reconciliation;
- message encryption/redaction/anonymization;
- callback v1 preservation, v2 schema/signature/deduplication и absence of PII/message;
- admin/SDK component tests.

### 22.5 Playwright и Compose smoke

Playwright проверяет:

- anonymous donation;
- email OTP login через Mailpit и authenticated donation;
- authenticated catalog payment появляется в собственной Customer history;
- cross-Site session rejection;
- sessionStorage restore within tab и removal on logout/expiry;
- Admin Campaign lifecycle;
- Customer anonymization;
- donation refund через существующий Admin flow;
- accessibility-critical keyboard/focus/error behavior.

Compose smoke запускает PostgreSQL, backend, Admin, demo-site, Mailpit и callback receiver. OIDC contract tests используют local signed issuer fixture; реальные Google/Apple endpoints не нужны.

Все Stage 1–2 tests остаются обязательной regression suite.

## 23. Configuration и runtime

Configuration groups:

- SDK session TTL и digest key/version;
- identity/email digest key/version;
- token clock skew, JWKS timeout/cache/size limits;
- OIDC attempt и SITE_TOKEN maximum lifetime/replay retention;
- OTP TTL, length, attempts, resend cooldown и distributed limits;
- mail outbox batch/lease/backoff;
- SMTP host, port, TLS mode, username, secret reference и sender;
- donor message encryption through existing data-encryption configuration;
- public request-size/rate limits;
- cleanup retention для expired sessions, OTP, rate-limit buckets и processed mail jobs.

`.env.example` содержит только local-development values и явные предупреждения. Production credentials не имеют defaults. Compose ports не конфликтуют с existing Stage 2 stack и очищаются после smoke; PostgreSQL volume сохраняется, если пользователь не запросил destructive reset.

## 24. Milestone gates

### 24.1 Gate Stage 3A

До начала donation implementation должны быть завершены:

- Customer/identity persistence;
- три authentication modes;
- SDK sessions;
- SMTP/Mailpit и mail outbox;
- identity linking;
- anonymization;
- optional Customer linkage для catalog/Partner dynamic payments;
- Customer payment history для catalog/dynamic;
- Admin/SDK integration;
- Cucumber, security checks, Compose verification;
- independent review без открытых Critical/Important findings.

### 24.2 Gate Stage 3B

Stage 3 завершён после:

- Campaign/Donation backend;
- DONATION Payment integration;
- Customer donation history extension;
- Admin/SDK/Web Components;
- callback and observability extensions;
- Cucumber/Playwright/Compose smoke;
- full regression and independent review;
- остановки принадлежащих проверке runtime resources без изменения чужих containers/volumes.

## 25. Критерии приёмки

- два Site с одинаковым email имеют независимых Customer и sessions;
- token одного Site не используется на другом Site или Origin;
- SITE_TOKEN и OIDC проверяются только по configured issuer/audience/keys;
- email OTP не раскрывает существование Customer и выдерживает concurrent/rate-limit scenarios;
- browser persistent storage не содержит source tokens, OTP или long-lived session;
- identity linking не выполняет email-based merge;
- anonymization необратимо удаляет PII/identities/messages и сохраняет обезличенные финансовые факты;
- catalog Payment с valid SDK session получает Customer, отсутствие session остаётся guest, а явно invalid session возвращает ошибку;
- Partner dynamic Payment связывается только с `ACTIVE` Customer того же Site;
- Customer history содержит только Payments/Donations текущего Customer и Site;
- Site поддерживает несколько независимо управляемых Campaign;
- Campaign принимает только разрешённые amounts и anonymous policy;
- invalid SDK session не превращает донат в anonymous;
- donation создаёт ровно один Donation и один Payment при idempotent replay;
- окончательное состояние определяется provider event;
- callback подписан, дедуплицируем и не содержит donor message/PII;
- Admin, SDK, Cucumber, Playwright, Compose и полная regression suite проходят;
- всё локально работает без Stripe, Google/Apple accounts и выбранного cloud provider;
- Git не используется.

## 26. Совместимость со следующими этапами

Stage 4 использует Customer, CustomerSession и Payment customer linkage для subscriptions, trials, plan changes, cancellation и payment-method management. Центральный subscription manager будет отдельным embedded UI; Stripe Customer Portal останется fallback.

Stage 5 реализует Stripe adapter, Payment Element, real provider webhooks, wallet/domain checklist, PayPal readiness, sandbox E2E, periodic reconciliation и production deployment hardening. Stage 3 authentication и donation contracts не зависят от Stripe DTO.

Public fundraising progress, receipts/tax documents, recurring donations, Customer merges и persistent refresh-token sessions требуют отдельных design decisions и не добавляются неявно в последующие этапы.
