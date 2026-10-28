# Stripe Billing 5B — проект решения

Дата: 2026-10-28. Статус: подготовлено к review и реализации; это расширение ранее согласованного [этапа 5](2026-10-14-stripe-sandbox-design.md#14-границы-следующих-частей), а не заявление о готовности Stripe-подписок. Основание: принятый [5A](../../stage-5a-acceptance.md), [Stage4](2026-10-11-subscriptions-design.md) и [аудит текущего кода](../../stripe-5b-audit.md).

## 1 Цель и неизменяемые границы

Существующие Site-owned подписки работают через настоящий Stripe Billing в Sandbox, сохраняя локальную модель доступа, журнал, callbacks и встроенный интерфейс. Stripe создаёт invoices и выполняет продления; scheduler платформы не делает параллельное списание по календарю.

- Java25/Spring Boot, Maven Wrapper и hexagonal backend; frontend отдельно, Admin Next.js/React/TypeScript/Tailwind, SDK framework-independent.
- Сохранить `stripe-java 34.0.0` и API `2026-09-30.endive`. Default Fake и отключённый Stripe не требуют ключей.
- Только карты, один item, quantity1, Price MONTH/YEAR, один исходный Account на подписку. Только test mode; без Connect, Managed Payments, Accounts v2 preview, trial offers, metered/seat billing, купонов, credits, tax expansion и live activation.
- Уникальность non-terminal подписки по Site/Customer/Product; разные сайты и accounts не объединяются по email.
- Денежные факты только после server retrieve и immutable ownership/money proof; браузер, return URL, synchronous ACK и invoice status сами по себе paid-доступ не дают.
- Только additive migrations после V25; исторические миграции неизменны. Client secrets, action grants, Portal URL не сохраняются в storage/events/logs/raw inbox.
- Старые EUR13 и DISPATCHING не являются fixtures 5B. Без новых recovery collectors, Python, распаковщиков или исследования старых архивов.

## 2 Изолированные provider bindings и операции

Stripe Customer связан с `(account_id, site_id, customer_id)`. Provider ref уникален внутри account; никакого поиска/слияния по email. Metadata помогает проверить соответствие, но не заменяет локально сохранённую mapping/creation proof. Смена Account сайта не переносит старую подписку.

`StripeSubscriptionProviderAdapter` реализует существующий `SubscriptionProviderPort`; новый registry выбирает Fake либо Stripe по Account. Capabilities объявляются только после реализации соответствующей безопасной команды. Неподдержанный provider/method/settlement даёт безопасный refusal, не Fake fallback.

Многошаговые CREATE/SETUP/CHANGE/RECOVER используют existing SubscriptionOperation плюс durable provider steps. Customer, subscription, setup, schedule, pay и Portal имеют отдельные детерминированные ключи/отпечатки под исходной операцией, сохранённые до HTTP. Provider HTTP — вне DB-транзакции; ownership/lease/CAS проверяются до dispatch и при фиксации. Сохраняются 23-часовая граница replay из 5A и original-account binding. UNKNOWN не создаёт следующий ресурс, новый ключ либо новое списание.

## 3 Первое списание и клиентское действие

Без trial создать Subscription с `billing_mode=classic`, `collection_method=charge_automatically`, card-only и `payment_behavior=default_incomplete`. Classic выбран для ограниченного переноса существующей Stage4 модели, без новой flexible/trial-offer семантики; pinned contract task проверяет именно эту комбинацию. Сохранить Subscription/Invoice/InvoicePayment/PaymentIntent связи; не создавать независимый one-time PaymentIntent в обход Billing. `save_default_payment_method=on_subscription` применяется к конкретной подписке после подтверждения consent/метода.

Существующий `NextAction.secret` для Stripe — локальный непрозрачный action grant, не client_secret. Новый customer-only `POST /api/v1/customer/subscriptions/{id}/operations/{operationId}/client-session` выдаёт memory-only `provider=stripe`, `flow=PAYMENT|SETUP`, publishable key, client secret, opaque action token и expiry. Вход: origin, CustomerSession, точные subscription/operation и исходный action token; никаких caller-supplied provider IDs/keys/return URLs. Ответ `Cache-Control: no-store`; секреты не попадают в безопасные subscription views.

SDK монтирует Payment Element либо Setup Element в заранее подключённый видимый host. При Pay сначала существующий `action-confirmations` потребляет локальный grant и durable фиксирует попытку. Stripe-адаптер этого endpoint не вызывает server-side confirm и не объявляет settlement; после ACK SDK ровно один раз вызывает соответствующий Stripe.js confirm. GET-only polling получает серверный результат. Потерянный ACK/abort/UNKNOWN не разрешает второй confirm; новый пользовательский retry требует проверки того же intent и отдельного допустимого attempt, а не создания подписки заново. Fake action contract остаётся совместимым.

## 4 Invoice, деньги и порядок наблюдений

Нормализованный snapshot проверяет account, subscription, Customer binding, Price/version/item/quantity, валюту, период и lifecycle. Cash берётся из полностью перечисленных InvoicePayment allocations и проверенных PaymentIntent/Charge; split/multi-invoice allocation, credits, paid_out_of_band, charge без capture и неподдержанные типы не объявляются CASH. Для 5B разрешён только однозначный полный cash join одного invoice; остальное UNSUPPORTED/reconciliation-required. Один успешный cash object создаёт один локальный Payment/source факт; callback и outbox — в одной транзакции с проекцией.

Нулевой invoice не создаёт положительный Payment. ZERO разрешает только явно доказанный бесплатный trial или разрешённый zero-proration transition; не произвольный бесплатный период.

Stripe не имеет Fake provider sequence. Его unversioned snapshots используют `providerRevision=0` как явно документированный sentinel, без timestamp/event-ID arithmetic. Актуальность обеспечивают fresh retrieve, существующий локальный revision/operation fence, operation-specific original-proof и terminal/paid-through guards. Fake revision semantics не меняются. Контрактные тесты обязаны доказать, что запоздавший ответ не откатывает новый тариф, отмену, оплаченный период или grace; если текущего fence недостаточно, задача добавляет конкретный read/dispatch fence перед подключением Stripe, а не ослабляет сравнения.

Webhook слой сохраняет exact-body signature, time bounds, version и account/mode checks из 5A. Allowlist: `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, `customer.subscription.paused`, `customer.subscription.resumed`, `customer.subscription.trial_will_end`; `invoice.created`, `invoice.updated`, `invoice.finalized`, `invoice.finalization_failed`, `invoice.paid`, `invoice.payment_failed`, `invoice.payment_action_required`, `invoice.voided`, `invoice.marked_uncollectible`; `setup_intent.succeeded`, `setup_intent.setup_failed`, `setup_intent.canceled`; `subscription_schedule.created`, `subscription_schedule.updated`, `subscription_schedule.released`, `subscription_schedule.completed`, `subscription_schedule.canceled`, `subscription_schedule.aborted`. Каждый тип нормализуется в ограниченный reference receipt и routed worker; событие никогда само не выдаёт доступ. Invoice-created быстро ACK после durable admission, без синхронного retrieve/finalize в webhook request; автоматический биллинг остаётся Stripe. Schedule receipt разрешается через проверенный parent Subscription и original-operation binding; metadata alone недостаточно. Unknown/unmapped references остаются inbox pending/dead с безопасным кодом. Разрешение webhook-before-mapping использует original creation-event/idempotency proof, а не metadata alone.

## 5 Trial, продление, recovery и изменение тарифа

- **Required method trial:** сначала confirmed SetupIntent и привязанный к этой подписке method; только затем создать trial. Reservation eligibility остаётся занятым при UNKNOWN. Trial не считается использованным по browser/ACK.
- **Optional trial:** точный server-owned trial_end сохраняется до первого dispatch; `missing_payment_method=pause`. Trial end без метода приводит к проверенному PAUSED без искусственного долга. Resume только отдельной явной командой и после подтверждённой оплаты нужного периода.
- **Продление:** Stripe invoice/attempt, затем verified cash → paid-through. Ошибка открывает существующий фиксированный grace (по умолчанию3дня), но не переносит его при каждом webhook. Setup метода не оплачивает долг автоматически.
- **Recovery:** если на исходном invoice уже есть незавершённый intent/action — использовать его. Повторный invoice.pay допустим только после authoritative RETRY_ALLOWED и original-key protection; scheduler самостоятельно его не вызывает.
- **Upgrade:** preview с точным proration timestamp/target/истечением; `pending_if_incomplete` + `always_invoice`, если это поддерживается pinned contract. Entitled Price меняется только после соответствующей оплаченной proration или разрешённого ZERO. Pending update нельзя подтверждать обычным subscription.updated.
- **Downgrade/month-year/deferred upgrade:** owned Subscription Schedule с сохранением текущей фазы до разрешённой границы и точного target после неё; release/clear изменяет только schedule исходной операции. Не менять чужие schedules и не создавать кредит/refund автоматически.
- **Cancel/withdraw:** exact current boundary по умолчанию; immediate только при policy и proof безопасного поведения Stripe без unintended invoice/proration. Refund и cancellation независимы.
- **Pause:** поддержать существующий PAUSED lifecycle и resume, прежде всего optional trial. `pause_collection` не считается PAUSED; произвольной новой команды пользовательской паузы Stage4 не вводить этим адаптером.

Используется subscription-wide бесплатный `trial_end`, несмотря на рекомендацию Stripe для новых проектов рассмотреть Trial Offers: он сохраняет согласованные Stage4 правила и не вводит paid/item-level trial либо portal/schedule preview. Это проектное решение, не утверждение, что пользователь отдельно выбирал конкретный Stripe-параметр.

## 6 Customer Portal

Portal выключен по умолчанию. Настройка привязана к исходному Account и Site policy version; Customer проверен через локальный binding. Разрешены только invoice history и узкий cancellation flow конкретной подписки в конце периода, если все действия конфигурации совместимы с policy. Если это нельзя доказать — Portal недоступен, встроенный manager остаётся рабочим.

`subscription_update`, coupons, quantity, arbitrary price transitions, customer-data editing и customer-wide `payment_method_update` выключены. Метод конкретной подписки обновляется через owned SetupIntent и явный `default_payment_method`, без изменения defaults других подписок. Нельзя выдавать общий customer Portal, если он обходит policies любой доступной в нём подписки. Exact return-url allowlist; browser URL и completed navigation не являются receipt/settlement. Возврат инициирует только безопасное чтение/сверку.

## 7 Приёмка 5B

Offline: default Fake; pinned SDK HTTP contract fixtures; PostgreSQL integration/Cucumber; SDK/Admin/demo/privacy; migration upgrade и существующие5A/Stage4 regression. Ни одного настоящего Stripe-запроса в default CI, browser mocks не считаются реальной приёмкой.

Будущий отдельно разрешённый Sandbox run: новая isolated fixture, fresh source/account/API/port admission, один worker, retries0, test-only Customer/Prices/Subscriptions и test clocks только своих fixtures. Для grace/expiry использовать существующий изолированный Stage4 business-clock contract, если требуется синхронизация с provider test clock; реальное UTC для JWT/OTP/signature и monotonic deadlines не сдвигать. Test-clock advancement может создавать новые тестовые списания и входит в явно согласованный бюджет, а не считается read-only действием. Сценарии: initial success/decline/3DS, required/optional trial, renewal success/failure/grace/recovery, per-subscription method isolation, pending/failed/success upgrade, scheduled change, cancel/withdraw, paused resume, Portal policy/return, webhook reorder/missed recovery. Доказательства: точные связи Subscription/Invoice/InvoicePayment/PaymentIntent/Charge, локальные BillingRecord/Payment/events/outbox и signed Site callback; zero invoices без фиктивного cash. Stop при первом неизвестном/ошибочном исходе, без automatic replay и без изменения старых5A объектов.

## 8 Источники и состояние решений

Официальные источники и их ограничения перечислены в [аудите](../../stripe-5b-audit.md#официальные-источники-проверенные-онлайн). Параметры здесь — проектные решения в рамках ранее согласованного scope; pinned-contract task проверяет их совместимость с конкретным SDK/API до production-адаптера. Новый функциональный код, миграции и Sandbox-запуск этим документом не выполнялись. Неподдержанный pinned contract останавливает соответствующую задачу и требует точного изменения дизайна; нельзя молча обновить SDK/API либо убрать acceptance scenario.
