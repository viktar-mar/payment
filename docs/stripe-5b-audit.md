# Аудит перед Stripe 5B

Дата: 2026-10-28. Проверенный исходный код: `caaeb98089e7f730b2ba6707348552a336b03917`, технически принятый 5A. Это статический аудит и подготовка следующего этапа, не результат исполнения Stripe Billing.

## Что уже существует

| Область | Проверенные исходники | Решение для 5B |
| --- | --- | --- |
| Модель и правила | `subscriptions/domain/Subscription.java`, `SubscriptionPolicy.java`, `SubscriptionPriceTerms.java` | Сохранить Site/Customer/Product/Account, billed/entitled Price, paid-through, trial reservation и фиксированный grace. |
| Application ports | `subscriptions/application/port/out/SubscriptionProviderPort.java`, `SubscriptionProviderRegistry.java`, `application/model/SubscriptionProviderModels.java` | Подключить Stripe к существующему контракту, не создавать второй billing engine. |
| Fake wiring | `provider/adapter/out/fake/FakeProviderConfiguration.java`, `FakeSubscriptionProviderAdapter.java` | Registry сейчас вызывает `requireFake`; нужен выбор по исходному Account с сохранением Fake по умолчанию. |
| Каталог Stripe | `provider/adapter/out/stripe/StripeCatalogProviderAdapter.java` | Recurring Price MONTH/YEAR уже публикуются; повторный каталог не нужен. |
| Деньги подписок | `subscriptions/application/service/SubscriptionFactService.java`, `adapter/out/integration/SubscriptionCashAdapter.java`, `payments/application/port/in/RecordSubscriptionCashUseCase.java` | Использовать проверенные invoice/cash observations и существующий финансовый журнал. Обычный `StripePaymentRequests` специально отклоняет источник SUBSCRIPTION. |
| Inbox и сверка | `webhooks/application/service/SubscriptionInboxWorker.java`, `subscriptions/application/service/SubscriptionReconciliationService.java` | Сохранить retrieve перед применением факта; добавить admission и разрешение Stripe subscription/invoice/setup references. |
| Ревизии | `SubscriptionReadPreparation.java`, `SubscriptionObservationPersistenceAdapter.java`, fence в `SubscriptionFactService.apply` | Fake имеет provider revision. Stripe нельзя приписывать глобальный sequence из event timestamp; нужен отдельный явно проверенный режим unversioned observation и существующие локальные fences. |
| API и права | `subscriptions/adapter/in/web/CustomerSubscriptionController.java`, `AbstractSubscriptionController.java`, `SubscriptionActionService.java` | Сохранить actor/Site/Customer/origin и idempotency. Добавить узкий customer-only client-session для Elements. |
| SDK | `frontend/sdk/src/subscriptions/action-driver.ts`, `contracts.ts`, `wire.ts`, `generic-subscription-manager.ts` | `PROVIDER_CLIENT` распознаётся, но выполнение сейчас возвращает UNSUPPORTED_PROVIDER. Нужны Payment/Setup Element driver и серверный grant перед Pay. |
| Portal | `SubscriptionPortalService.java`, `SubscriptionInteractionTransaction.java` | Есть policy/return-url/replay contract и Fake navigation; настоящей Stripe PortalSession нет. |
| Тесты | `backend/src/test/resources/features/subscriptions.feature`, subscription integration tests, SDK subscription tests | Повторно использовать бизнес-сценарии; добавить official-SDK HTTP fixtures и отдельную opt-in Billing acceptance. |

Backend-пути в таблице относительны к `backend/src/main/java/dev/genericpayments/platform/`.

## Разрывы, которые закрывает план

1. Нет Stripe Customer binding, subscription adapter и воспроизводимой цепочки Customer → Subscription → Invoice/PaymentIntent → локальные BillingRecord/Payment.
2. Общий durable provider store 5A знает только PRODUCT_CREATE, PRICE_CREATE, PAYMENT_PREPARE, REFUND_CREATE. Для многошаговых Billing-команд нужны отдельные стабильные шаги исходной операции, fenced ownership и запрет blind retry после неизвестного результата (`UNKNOWN`)/истечения окна.
3. Stripe webhook allowlist 5A содержит только payment_intent и refund события. Subscription/invoice/setup события сейчас не являются поддержанными Billing-входами.
4. Состояние invoice `paid` само по себе не доказывает движение денег: проверять InvoicePayment allocation и захваченный PaymentIntent/Charge; нулевой trial не создаёт положительный Payment. Неподдержанное settlement остаётся reconciliation-required.
5. Клиентский секрет Stripe не является локальным подтверждением оплаты или универсальным action token. Сохранить непрозрачный локальный action grant и отдельный memory-only provider context.
6. Portal имеет customer-wide функции. Общая смена default method может затронуть другие подписки; несогласованные смены тарифов могут обойти локальные quotes и entitlement. В 5B такие функции должны быть выключены по умолчанию.
7. `PAUSED` и pause_collection — разные состояния. 5B прежде всего переносит существующий сценарий optional trial → confirmed pause → explicit resume; не добавляет несуществующую в Stage4 API общую команду паузы оплаченной подписки.

## Что не меняется

5A остаётся принят по собственной матрице. Статическая подготовка 5B не изменяет его отчёты. Старые EUR13/local-state и анонимный DISPATCHING остаются отдельными незакрытыми случаями; их данные, ключи, архивы и операции в аудите не открывались и не повторялись. Нет установки, Docker/Sandbox, запросов к Stripe API, финансовых операций или нового диагностического инструментария.

Историческая спецификация Stage4 содержит прежнее ограничение «без Git» и первоначальный статус «реализация не начата»: это датированный документ, не текущая политика. Позднейшие явные разрешения пользователя на Git и принятый 5A имеют приоритет; бизнес-инварианты Stage4 сохраняются.

## Официальные источники, проверенные онлайн

- [Subscriptions API create](https://docs.stripe.com/api/subscriptions/create) — customer/subscription и первый invoice.
- [Invoice Payment](https://docs.stripe.com/api/invoice-payment/object) — отдельная связь invoice с фактическим платежом и allocation.
- [Pending updates](https://docs.stripe.com/billing/subscriptions/pending-updates) — изменения, зависящие от оплаты; обычный update нельзя считать безопасной заменой этому режиму.
- [Trial offers и free trial periods](https://docs.stripe.com/billing/subscriptions/trials/compare), [существующий free trial API](https://docs.stripe.com/billing/subscriptions/trials/free-trials) — trial_end остаётся отдельным legacy-путём, совместимым с выбранным ограниченным scope; новые trial offers сюда автоматически не добавляются.
- [Portal flows](https://docs.stripe.com/customer-management/portal-deep-links) — payment_method_update меняет customer default; это не доказательство изменения метода только выбранной подписки.
- [Pause collection](https://docs.stripe.com/billing/subscriptions/pause-payment) — не подменять им paused lifecycle.
- [Schedules](https://docs.stripe.com/billing/subscriptions/subscription-schedules) и [Billing testing](https://docs.stripe.com/billing/testing) — отложенные переходы и будущие изолированные проверки.
- [Subscription webhooks](https://docs.stripe.com/billing/subscriptions/webhooks) — точные имена lifecycle/invoice/schedule событий и быстрый durable ACK перед асинхронной обработкой.

Документация Stripe меняется. Контрактные fixtures в первой задаче проверяют точные поля именно существующих `stripe-java 34.0.0` и `2026-09-30.endive`; этот аудит не разрешает обновить pin или включить preview API. Две конкретные variant/version URL не открылись web-инструментом; вместо них использованы доступные официальные API-страницы. Отсутствие доступа к странице не считается проверкой её содержимого.
