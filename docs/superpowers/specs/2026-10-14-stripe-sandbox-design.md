# Этап 5 Stripe Sandbox и реальные provider adapters

Дата: 2026-10-14. Статус: подтверждена пользователем 2026-10-14.

Цель этапа — подключить существующую платёжную платформу к Stripe Sandbox, сохранив её собственные Site, Customer, каталог, финансовый журнал и правила подписок. Один backend обслуживает разные сайты; принадлежность каждого платежа и подписки остаётся явной. Frontend и backend сохраняют отдельные папки, Java backend — hexagonal структуру.

Пользователь подтвердил последовательность 5A → 5B → 5C и отдельную локальную конфигурацию ключей. Ни один шаг этого этапа не включает live payments, Stripe Connect, Managed Payments или активацию бизнеса для приёма настоящих денег.

## 1 Последовательность этапа

| Часть | Результат | Граница приёмки |
| --- | --- | --- |
| 5A | Stripe Sandbox, Payment Element, разовые платежи и донаты, signed webhooks, возвраты | Тестовая карта проходит полный путь до подтверждённого финансового факта и callback сайта; Fake не сломан |
| 5B | Stripe Billing, подписки и Customer Portal | Подтверждены initial и renewal payments, trial, recovery, смена тарифа, отмена и изоляция клиентов |
| 5C | Расширенная сверка, методы оплаты и готовность к облаку | Проверена матрица доступности методов, восстановление после сбоев, secrets/rotation и deployment checklist |

Ниже подробно определена только 5A. Для 5B и 5C нужны отдельные спецификации и планы после результатов предшествующей части. Обязательная безопасная сверка неизвестных операций входит уже в 5A; её нельзя отложить до 5C.

Существующие спецификации остаются основой: [платформа](2026-09-12-generic-payment-platform-design.md), [разовые оплаты](2026-10-04-one-time-payments-design.md), [Customer access и донаты](2026-10-10-customer-access-donations-design.md), [подписки](2026-10-11-subscriptions-design.md). Этот этап добавляет Stripe, а не заменяет существующие domain rules.

## 2 Границы части 5A

В 5A входят catalog и dynamic one-time payments, анонимные и идентифицированные one-time donations, Payment Element в SDK/demo, проверенные события Stripe, полные и частичные возвраты, Admin inspection и восстановление операций с неизвестным результатом.

Первый обязательный метод — карта, включая 3D Secure. Apple Pay, Google Pay и PayPal пока не объявляются готовыми: их включение зависит от аккаунта, валюты, устройства, доменов и конкретного flow. Fake capabilities не служат доказательством возможностей Stripe. До 5C adapter ограничивает методы проверенным card flow, wallet UI отключён; политика Site пересекается с этим ограничением, а несовместимый allowlist отклоняется без создания денежной операции.

EUR — обязательная Sandbox-проверка. Другие валюты допускаются только после проверки поддерживаемой суммы и единиц валюты; conversion и Adaptive Pricing не входят в 5A. `NONE` и `INCLUSIVE` сохраняют утверждённую сумму; `EXCLUSIVE` и автоматический Stripe Tax отклоняются до отдельной реализации. Налог не пересчитывается после согласия клиента.

Subscriptions для Stripe недоступны до 5B. Существующие Fake subscriptions продолжают работать. Платные trial, split payouts, marketplace onboarding, disputes workflow и бухгалтерская интеграция вне 5A.

## 3 Выбор Stripe API и UI

5A использует один PaymentIntent на локальный Payment и Payment Element для ввода платёжных данных. Центральная платформа уже управляет checkout, immutable amount, авторизацией, донатами и журналом; PaymentIntents позволяют сохранить эти границы. Альтернатива — Checkout Sessions с Elements — берёт больше checkout-логики на сторону Stripe и требует отдельного сопоставления с существующим checkout. Stripe рекомендует её для большинства новых интеграций; здесь выбираем более низкоуровневый API осознанно. [Stripe intent-first integration](https://docs.stripe.com/payments/accept-a-payment-intent-first).

Backend использует официальный `com.stripe:stripe-java:34.0.0` с API `2026-09-30.endive`. Snapshot webhook version должна совпадать с этим контрактом. Версии не выбираются автоматически по `latest`; обновление SDK/API требует отдельной проверки mapping и regression tests. [Stripe Java API version](https://raw.githubusercontent.com/stripe/stripe-java/v34.0.0/src/main/java/com/stripe/ApiVersion.java).

Stripe.js загружается официальным loader с сервера Stripe, только для Stripe checkout и только в браузере. Его версия фиксируется в lockfile на этапе реализации. Headless API и Web Component остаются доступными сайтам без React/Next.js; Admin остаётся в `frontend/admin`, SDK — в `frontend/sdk`.

## 4 Hexagonal границы и маршрутизация

Domain и application не получают Stripe SDK classes. Stripe adapter преобразует типизированные application requests/results и provider references; HTTP errors и Stripe JSON не пересекают эту границу.

Повторно используются `PaymentProviderPort`, `CatalogProviderPort`, `PaymentProviderRegistry`, существующие preparation/refund coordinators, provider inbox, reconciliation и transactional outbox. Добавляются узкие ports для получения client-payment capability и разрешения credential reference. Новый client-provider flow не маскируется под Fake server confirmation.

Registry выбирает adapter по сохранённому `PaymentAccount.provider`, а credentials — по `PaymentAccount.credentialsRef`. Каталог также получает account-specific dispatch вместо единственного глобального catalog adapter. В одном процессе могут сосуществовать Fake и Stripe; включение Stripe не переназначает старые Sites, Products, Prices, Payments или Subscriptions.

Для первого Sandbox используется alias `stripe-sandbox-default`. Alias разрешается только через конфигурацию backend, не трактуется как путь к файлу, имя произвольной environment variable или URL. PaymentAccount хранит reference и ожидаемый `acct_…`, но не ключ. Смена credential reference не может незаметно переключить уже начатую операцию на другой Stripe account.

Создание/подключение Stripe PaymentAccount включает read-only проверку identity через server key и сопоставление с `externalAccountId`. Права restricted key должны включать эту проверку. Publishable key задаётся в той же credential binding; совпадение префиксов не доказывает, что ключи из одного Sandbox. Ошибочная пара должна давать безопасную ошибку, без поиска другого аккаунта и без нового PaymentIntent.

Provider Product/Price mappings имеют ключи Account + локальный объект + версия. Опубликованные цены не переписываются; повторная публикация использует сохранённый mapping и deterministic operation key. Recurring Price не открывает Stripe subscription flow до 5B.

## 5 Локальная конфигурация ключей

Default `infrastructure/compose.yaml` остаётся работоспособным без Stripe keys и сохраняет Fake. Stripe включается отдельным opt-in Compose overlay `infrastructure/compose.stripe-sandbox.yaml`, который не меняет текущий `.env` и не передаёт Stripe secrets контейнерам Admin/demo.

После review плана подготавливаются:

- `infrastructure/stripe-sandbox.env.example` — tracked template с пустыми значениями и пояснениями;
- `infrastructure/stripe-sandbox.env.local` — Git-ignored файл с правами `0600`, создаваемый только при отсутствии; существующее содержимое не перезаписывается;
- Compose overlay и краткая инструкция для явного запуска Sandbox и возвращения к default Fake.

Контракт конфигурации:

| Переменная | Значение и использование |
| --- | --- |
| `PAYMENT_STRIPE_SANDBOX_ENABLED` | Только явное `true` в opt-in overlay; отсутствует/false в default |
| `PAYMENT_STRIPE_SANDBOX_SECRET_KEY` | `sk_test_…` либо `rk_test_…`, только backend |
| `PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY` | `pk_test_…` из того же Sandbox, возвращается для соответствующего checkout |
| `PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET` | `whsec_…` от локального Stripe CLI listener |

`sk_live_`, `rk_live_`, `pk_live_`, organization keys, пустые и демонстрационные значения не принимаются при включённом Sandbox. Stripe Sandbox configuration разрешена только в local/test профилях. При выключенном Stripe отсутствие ключей не мешает Fake startup. Ключи не попадают в `NEXT_PUBLIC_*`, `VITE_*`, image layers, build arguments, Git, URL или команды с literal secret.

Имеющийся у пользователя `sk_test_…` допустим для первого локального подключения. Для дальнейшей эксплуатации предпочтителен restricted key с документированной матрицей разрешений; недостаточные permissions дают явную configuration error. Webhook signing secret — отдельный секрет, не API key. [Stripe API keys](https://docs.stripe.com/keys).

Нельзя выводить развёрнутый `docker compose config` с заполненными ключами в отчёт. Проверки конфигурации сообщают только наличие, режим и результат, не значения.

## 6 Создание PaymentIntent и неизвестный результат

Application сначала транзакционно фиксирует локальный Payment, immutable money, Account, исходные business facts и durable operation identity. Затем вне DB transaction adapter создаёт PaymentIntent с deterministic idempotency key; следующая транзакция сохраняет provider reference. Подготовка не подтверждает списание.

Один Payment переиспользует тот же PaymentIntent при перезагрузке, recoverable decline и повторном вводе карты. Новый local PaymentAttempt не означает новый PaymentIntent. Сумма, валюта и account берутся из сохранённого Payment, а не из browser payload. Это соответствует модели повторного использования intent в Stripe. [Payment Intents lifecycle](https://docs.stripe.com/payments/payment-intents).

Request fingerprint и operation key фиксируются до сетевого вызова. Timeout, crash после ответа Stripe, `5xx` и потерянный commit не разрешают создание нового intent с новым key. Восстановление использует исходные operation facts, reference и account; при отсутствии доказательства исход остаётся `UNKNOWN` с видимой reconciliation task.

Повтор provider write с тем же key допускается только в проверенном окне его хранения. После неопределённого/истёкшего окна автоматическое повторное создание запрещено: operator сверяет исходную операцию, а не нажимает скрытое «создать ещё раз». Stripe idempotency cache не заменяет постоянную business idempotency платформы. [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests).

Для 5A one-time платежи не сохраняют метод для будущих списаний и не создают Stripe Customer без необходимости. В 5B mapping Customer будет включать Site + локальный Customer + Account; общий email не объединяет клиентов разных сайтов.

## 7 Client-provider confirmation и PaymentAttempts

Текущая Fake ветка остаётся `provider_type=FAKE`, `confirmation_mode=SERVER`, с пустым provider payload. Stripe ветка использует `STRIPE` + `CLIENT_PROVIDER`. SDK contracts и bounded parsers становятся discriminated unions; комбинации неизвестного provider/mode и лишние поля отклоняются.

Обычные checkout create/read responses не содержат PaymentIntent client secret. Отдельный авторизованный `POST /api/v1/public/checkouts/{id}/client-session` подготавливает Stripe UI: проверяет checkout token, exact Origin, scopes, срок, Site/Account, Customer binding там, где он требуется, и idempotency key. Donation/dynamic checkout сначала готовит тот же локальный PaymentIntent, если это ещё не сделано.

До выдачи capability application фиксирует client attempt. Ответ содержит только checkout/payment/attempt identifiers, matching publishable key, PaymentIntent client secret и срок локальной session, с `Cache-Control: no-store`. Capability получается после DB commit и provider lookup вне transaction; секрет не сохраняется в таблицах attempts, idempotency responses или journals. Повтор запроса повторно проверяет authority и получает capability того же intent, не копирует сохранённый raw response.

Payment Element монтируется для этого intent. При отправке формы Stripe.js выполняет confirmation и SCA; server `/confirm` сохраняется только для Fake и отклоняет Stripe. SDK не собирает PAN/CVC и не передаёт их платформе. Browser success/error — сигнал для UX и безопасного refresh, не основание для финансового transition.

Client attempt фиксирует авторизованное открытие клиентского flow, а не гарантированное списание. Клиентский POST не доказывает, что пользователь нажал Pay. Повторное открытие unresolved flow переиспользует attempt; новый attempt разрешается после серверно подтверждённого recoverable failure. Stripe Charge receipts, связанные с intent, дают provider facts о реальных попытках; нельзя утверждать взаимно-однозначное соответствие local UI attempts и Stripe Charges. Amount и факт success всегда относятся к Payment/intent.

Unknown/processing/requires action блокируют автоматический новый финансовый flow. После abort/disconnect или lost response SDK читает состояние и не повторяет финансовый POST вслепую. Истечение checkout запрещает новую выдачу capability, но не отзывает уже выданный Stripe client secret. Поздний подтверждённый платёж всё равно журналируется; срок локального UI не стирает денежный факт и не запускает автоматический refund.

## 8 SDK и возврат из Stripe

Provider driver лениво загружает Stripe.js, монтирует/размонтирует Payment Element и переводит существующие theme tokens в Appearance configuration. Account-specific Stripe instance не переиспользуется между несовместимыми bindings. Смена Site, checkout, logout, expiry и disconnect отменяют локальную работу, уничтожают Element и подавляют stale completions.

Client secret и capability остаются в памяти: не в storage, DOM attributes, browser events, telemetry или `toString`. Разрешённые browser events содержат только безопасные IDs и state. Backend DTOs, exception mapping и SDK errors имеют redacted diagnostics; текущие record/map representations также должны быть защищены.

Для карты используется `redirect: if_required`. Return URL выбирается из server-side разрешённых точных URLs Site, а не принимается произвольно от клиента. Return page не является доказательством оплаты и читает локальное подтверждённое состояние с прежней authority; при её отсутствии предлагает безопасное восстановление, не принимает authority из query.

Stripe может добавить `payment_intent_client_secret` в redirect query. Return page удаляет Stripe query до загрузки analytics/сторонних scripts, использует `Referrer-Policy: no-referrer`; demo/proxy не логирует query. Приложение само не помещает secrets в URL. Cleanup проверяется отдельным тестом; нельзя обещать отсутствие secret в исходном Stripe redirect URL. [Stripe return page parameters](https://docs.stripe.com/payments/accept-a-payment-intent-first#submit-the-payment-to-stripe-client-side).

## 9 Проверенные webhooks и provider inbox

Endpoint `POST /api/v1/provider-webhooks/stripe/{accountKey}` выбирает заранее настроенный активный Sandbox Account и его signing secret. Caller не выбирает credentials header-ом. Connect events и live objects отклоняются; переданные Site/Payment metadata не являются полномочиями.

До разбора JSON проверяются exact raw bytes, ограничение body 256 KiB, header 4 KiB, `Stripe-Signature` и timestamp tolerance 300 секунд по реальному UTC clock, не Fake business clock. После проверки adapter принимает поддержанную snapshot API version и allowlisted event types. Ошибки не включают body, signature или secret. Требование raw body и возможность duplicate/out-of-order deliveries описаны Stripe. [Stripe webhooks](https://docs.stripe.com/webhooks).

Endpoint сохраняет минимальный verified receipt в существующий provider inbox: Account, event ID, object ID, type и time. Успешный `2xx` возвращается после durable insert; duplicate получает безопасный `2xx`, invalid request — bounded `4xx`, невозможность сохранить receipt — retryable `5xx`. Stripe raw JSON и client secrets не сохраняются. Текущий safe envelope расширяется типизированным reference receipt, а не произвольной JSON map; второй inbox engine не создаётся.

Worker вне transaction получает актуальный PaymentIntent/Refund/связанный Charge с credentials именно этого Account. Затем сопоставляет provider reference с сохранённой локальной операцией, проверяет mode, amount/currency и нормализует результат. При crash до сохранения mapping допускается только восстановление по durable operation + проверенным фактам Stripe; metadata служит подсказкой, но не заменяет проверку.

Первичный allowlist: `payment_intent.processing`, `payment_intent.requires_action`, `payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.canceled`, `refund.created`, `refund.updated`, `refund.failed`. [Stripe event types](https://docs.stripe.com/api/events/types).

Получение события запускает сверку, но не подменяет её названием event. `requires_payment_method` сохраняет Payment готовым к повтору; подтверждённый отказ завершает соответствующий attempt, не весь Payment. `requires_action` и `processing` сохраняют pending flow; только проверенный `succeeded` создаёт success, `canceled` — cancellation. Unsupported intent states не интерпретируются как успех. Manual capture не входит в 5A.

Неизвестный объект не создаёт Payment из webhook. Receipt получает bounded retry при потенциальной гонке mapping, затем явный unmatched/dead outcome для Admin inspection. Неподдержанный event type безопасно игнорируется. Event для другого Account/Site не меняет projection или journal.

Deduplication включает provider + Account + event ID; отдельные event IDs об одном денежном факте не создают повторный success/refund/callback. Event `created` не используется как monotonic revision. Re-fetch, object references и существующие transition guards предотвращают регресс confirmed state при поздних событиях.

## 10 Денежные факты и возвраты

`PaymentEvent` остаётся append-only журналом, Payment/Refund — projections, Audit — журналом действий оператора. Синхронный provider acknowledgement и browser result не создают `PAYMENT_SUCCEEDED`. Подтверждение проходит через один application transition path из verified inbox или reconciliation и атомарно создаёт outbox event для соответствующего сайта.

Refund использует существующую авторизованную Admin/Partner command и локальное резервирование доступной суммы. Adapter адресует конкретный подтверждённый PaymentIntent/Charge, Account и Refund operation key; учитывает partial/full refund и currency. Network call выполняется вне DB transaction. Unknown refund блокирует повторное использование зарезервированной суммы до сверки; новый key не используется для обхода timeout.

Provider refund ID и фактический результат подтверждаются webhook/retrieve. Pending/failed refund не записывается как успешный возврат. Existing confirmed amounts и история не переписываются. Duplicate, parallel partial refunds и webhook-before-response проверяются транзакционными тестами. [Stripe refund creation](https://docs.stripe.com/api/refunds/create).

Stripe metadata содержит allowlisted технические references Site, Payment, source и operation. Email, donation message, tokens и credentials туда не передаются. В Admin и callbacks сохраняется различимость Site, source, order, Donation и PaymentAccount; provider metadata не заменяет локальную ownership модель.

## 11 Сверка и наблюдаемость в 5A

Обязательны retrieve для застрявших Payment/Refund, восстановление после пропущенного webhook, bounded retries/backoff и безопасный manual retry. Сверка использует те же нормализованные transitions, journals и outbox, что inbox. Повтор worker/restart не порождает новый денежный факт.

Admin показывает provider reference, account/mode, unresolved operation и причину безопасного отказа. Logs используют request/operation/object correlation без Stripe bodies, customer email и capabilities. Метрики имеют ограниченные labels, например Site/provider/outcome; Payment/Customer IDs не становятся labels. HTTP wire logging и body tracing для Stripe отключены.

## 12 Проверки и критерии приёмки 5A

Default automated suite не требует Stripe account: official SDK обращается к контролируемому HTTP fixture, PostgreSQL integration tests проверяют constraints/transactions, Cucumber — бизнес-сценарии. Fixture проверяет реальные adapter requests, version header, account routing и operation keys, а не подменяет весь adapter готовым success.

Обязательные сценарии:

1. Fake-only startup без Stripe keys; opt-in validation отклоняет live keys, недостающий secret, wrong profile/account и unsupported permissions без раскрытия значений.
2. Catalog/dynamic payment и anonymous/identified donation получают правильные Site, source, amount и Account. Cross-Site/Customer/token/origin попытки не выдают capability.
3. Card success, recoverable decline с повтором того же intent, SCA, processing и unknown. Client-side success не выдаёт paid result до серверного подтверждения.
4. Crash до/после provider response, lost DB commit, duplicate/concurrent preparation и истёкшее provider idempotency window не создают второй intent вслепую.
5. Exact-body signature, stale/future timestamp, modified/oversized body, live event, неподдержанная version и wrong Account отклоняются. Duplicate/out-of-order и webhook-before-mapping не дублируют денежный факт или callback.
6. Пропущенный webhook восстанавливается retrieve; поздний decline не отменяет подтверждённый success. Foreign/unmatched objects не создают локальные Payments.
7. Partial/full/parallel refunds, timeout, pending/failure и webhook-before-response сохраняют лимиты и подтверждённую историю.
8. SDK discriminated contracts, lazy/SSR-safe import, theme, accessibility, logout/disconnect/expiry, stale responses, return-query cleanup и отсутствие secrets в storage/events/logs.
9. Additive migrations проходят на пустой БД и поверх предыдущих этапов. Fake payment/donation/subscription scenarios и callback contracts не регрессируют.

Отдельный opt-in Sandbox smoke подтверждает настоящий Stripe PaymentIntent, card success/3DS, webhook, локальный journal/outbox/callback и refund. Playwright использует один worker, ноль автоматических retries финансовых сценариев; traces, screenshots, video и raw payload dumps с credentials отключены. Mocked browser test не выдаётся за Sandbox E2E.

Отчёт фиксирует фактически выполненные suites, версии и outcome без ключей. Если нет credentials/network/device, соответствующая проверка явно остаётся невыполненной. 5A принимается после card Sandbox evidence и отсутствия новых regression failures; готовность всех wallet методов для этого не требуется.

## 13 Действия пользователя для Sandbox

После подготовки конфигурационных файлов пользователь локально заполняет server и publishable keys из одного Sandbox. Полные значения не отправляются в чат. Бизнес-сайт, VPS и выбор cloud provider для локального теста не нужны.

Для webhook пользователь авторизует Stripe CLI в том же Sandbox, запускает listener с фильтром нужных payment/refund events и forwarding на loopback endpoint своего Account, затем сохраняет выданный `whsec_…` локально. API version listener/Sandbox сверяется с pin; флаг `latest` не заменяет такую проверку. CLI listener не требует публичного Dashboard webhook URL. Не используются `--live`, `--print-json` и отключение TLS verification. [Stripe CLI listen](https://docs.stripe.com/cli/listen).

CLI не устанавливается и Stripe account не изменяется автоматически в рамках подготовки спецификации. На этапе практического подключения установка и интерактивный login выполняются явно. Listener и app services запускаются только для согласованной проверки; после неё останавливаются только собственные процессы без удаления volumes.

## 14 Границы следующих частей

5B повторно использует Stage 4 Subscription semantics, но проверяет Stripe-specific Subscription/Invoice/Payment mapping, first payment, off-session renewal, required/optional trial method, setup, per-subscription default method, pending plan change, pause/resume и cancellation. Customer Portal разрешает только действия, совместимые с локальными policy; несовместимые действия отключаются. Возврат из Portal не подтверждает изменение, email не объединяет чужие Customers.

5C расширяет reconciliation и operational recovery, проверяет доступность Apple Pay/Google Pay/PayPal и других выбранных методов отдельно для one-time/recurring/off-session flows. Добавляются domain registration/HTTPS checklist, rotation, production secrets adapter, monitoring и облачный deployment guide. Налоговая модель, требования к бизнесу и live activation согласуются отдельно до настоящих платежей; Sandbox не означает production readiness.

## 15 Следующий шаг

Пользователь проверяет письменную спецификацию, прежде всего выбор PaymentIntent, границы card-first 5A и безопасную выдачу client capability. После её подтверждения составляется детальный план 5A: сначала локальные templates/overlay и проверки, затем adapter/contracts, webhooks/refunds, UI и opt-in Sandbox acceptance. План проходит review с выбором способа выполнения перед изменением runtime/configuration или установкой dependencies.
