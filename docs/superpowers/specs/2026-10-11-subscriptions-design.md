# Подписки на этапе 4 платёжной платформы

Дата: 2026-10-11

Статус: согласованные разделы дизайна оформлены в спецификацию для review пользователя. Реализация этапа ещё не начата.

## 1 Цель и границы этапа

Добавить повторно используемые подписки для разных сайтов поверх готовых Catalog, Payments, Customer Access и Donations. Провайдер ведёт биллинг; платформа хранит локальную проекцию подписок, отдельную историю их жизненного цикла и правила доступа к продуктам.

Этап 4 полностью проверяется локально через Fake Provider. Настоящий Stripe adapter, Stripe Sandbox, Payment Element, реальные Customer Portal sessions и проверка доступности карт, PayPal, Apple Pay и Google Pay относятся к этапу 5. Успешный Fake-сценарий не подтверждает работоспособность соответствующего Stripe-сценария.

Основной клиентский интерфейс встраивается в сайт. Центральный кабинет клиента не создаётся. Разные сайты могут иметь разные продукты, тарифы, trial и правила подписок, даже используя один платёжный аккаунт.

Основание:

- [Общий дизайн платформы](/Users/viktar.marozau/Projects/my/generic-solution/payment/docs/superpowers/specs/2026-09-12-generic-payment-platform-design.md).
- [Разовые платежи](/Users/viktar.marozau/Projects/my/generic-solution/payment/docs/superpowers/specs/2026-10-04-one-time-payments-design.md).
- [Customer Access и донаты](/Users/viktar.marozau/Projects/my/generic-solution/payment/docs/superpowers/specs/2026-10-10-customer-access-donations-design.md).

Эта спецификация уточняет общий дизайн для этапа 4. В частности, browser не выбирает произвольную proration policy: backend применяет разрешённые настройки сайта и возможности провайдера.

## 2 Сохраняемые ограничения

- Backend остаётся Java и Spring Boot приложением с Maven Wrapper, модульным монолитом и hexagonal architecture.
- `backend/` и `frontend/` остаются раздельными. Admin использует Next.js, React, TypeScript и Tailwind CSS; SDK не зависит от React и Next.js.
- Domain не зависит от Spring, JPA, HTTP, Jackson и provider SDK. Модули взаимодействуют через application ports, а не чужие repositories.
- PostgreSQL хранит транзакционные данные, журналы, inbox и outbox. Полный event sourcing не вводится.
- Денежные суммы используют integer minor units, идентификаторы доменных сущностей — UUID, время — внедрённый `Clock` и UTC.
- Проект остаётся локальным. Git, commits, push и worktrees не используются.
- Docker Compose сохраняется для локальных runtime-проверок. Проектирование не требует запуска сервисов.
- Реальные платежи, создание Stripe-аккаунта и deployment не являются частью этого этапа.

## 3 Функциональный scope

### 3.1 Входит в этап

- Подписки по опубликованной версии `Price` типа `SUBSCRIPTION` с интервалом `MONTH` или `YEAR`.
- Создание только для аутентифицированного `ACTIVE` Customer своего Site.
- Free trial с обязательным либо необязательным способом оплаты.
- Первый платёж, продления, дополнительные платежи при upgrade и их связь с существующими Payments и Refunds.
- Управление доступом, фиксированный grace period, восстановление после подтверждённой оплаты.
- Предпросмотр и разрешённая смена тарифа, включая отложенный переход.
- Отмена в конце периода, отзыв запланированной отмены и опциональная немедленная отмена.
- Обновление способа оплаты и отдельное погашение неоплаченного invoice.
- Provider inbox, дедупликация, transactional outbox, подписанные уведомления сайтам и reconciliation подписок.
- Customer и Partner API, Admin UI, headless SDK, subscription manager Web Component и demo.
- Опциональный site-level Portal contract с явно обозначенной Fake-симуляцией.
- Unit, PostgreSQL integration, Cucumber, frontend и browser tests с управляемым временем.

### 3.2 Не входит в этап

- Реальный Stripe, собственный ввод реквизитов карты или имитация сертифицированного Payment Element.
- Recurring donations, seat-based и metered billing, quantity больше одного, несколько billing items в одной подписке.
- Скидки, купоны, paid trials, произвольный billing anchor и произвольная дата отмены.
- Автоматические credit notes, переносы баланса, возвраты при downgrade и ручная отметка «оплачено».
- Немедленная смена интервала month/year, cross-product и cross-currency migration.
- Автоматическая массовая миграция действующих подписок на новую Price.
- Customer merging, полноценная защита trial от создания новых аккаунтов и persistent refresh tokens.
- Налоговые документы, юридические гарантии, выбор cloud provider и production hosting.

## 4 Выбранный архитектурный подход

Рассмотрены три варианта: только внешний Portal, собственный billing engine и provider billing с локальной проекцией. Выбран третий вариант. Он сохраняет собственный встроенный интерфейс и site-specific правила, не возлагая на платформу независимое планирование реальных списаний.

Provider отвечает за subscription и invoice lifecycle, расчёт применимого перерасчёта, попытки списания и внешние payment objects. Платформа отвечает за разрешение команд, локальные факты и проекции, доступ к продукту, историю, безопасность и уведомления сайтов.

Fake Provider воспроизводит эту границу. Его управляемое время и симуляции создают provider-side события. Платформенный scheduler не выполняет второе самостоятельное списание на дату продления.

### 4.1 Новый модуль subscriptions

Модуль владеет `Subscription`, её billing records, командами изменения, trial eligibility, access policy и append-only `SubscriptionEvent`.

Структура соответствует существующим модулям:

```text
subscriptions/
  domain/
  application/
    port/in/
    port/out/
    service/
  adapter/
    in/
    out/
```

Входящие application ports покрывают создание, чтение, preview, смену тарифа, отмену и её отзыв, payment method setup, recovery, применение provider facts и reconciliation.

Исходящие ports покрывают хранение, транзакции, загрузку разрешённого каталога и Customer, обращения к провайдеру, создание и чтение Payments, outbox, audit и telemetry. Provider DTO остаются внутри adapters.

### 4.2 Границы существующих модулей

| Модуль | Ответственность на этапе 4 |
| --- | --- |
| `catalog` | Неизменяемые Price, trial settings и разрешённые переходы между тарифами |
| `customers` | Аутентификация, CustomerSession, ownership, отзыв доступа и guard анонимизации |
| `payments` | Реальные денежные операции, попытки, refunds и PaymentEvent |
| `subscriptions` | Жизненный цикл подписки, billing context и доступ к продукту |
| `provider` | Provider-neutral adapters и Fake billing state |
| `webhooks` | Inbox, маршрутизация событий, outbox, подпись и доставка callback |
| `sites` и `admin` | Site-level настройки, административные полномочия и audit |

Обновление финансового факта, связанных проекций и outbox выполняется одной PostgreSQL-транзакцией через application ports. Вызов внешнего провайдера не включается в долгую транзакцию БД.

## 5 Продукты цены и политики

### 5.1 Идентичность подписки

Для `(site_id, customer_id, product_id)` допускается не более одной non-terminal подписки. `INCOMPLETE`, `TRIALING`, `ACTIVE`, `PAST_DUE`, `PAUSED` и `UNPAID` занимают этот слот; запланированная отмена не освобождает его. Только подтверждённое завершение освобождает слот.

Basic/Pro и month/year являются альтернативами внутри одного Product. Разные продукты одного сайта могут иметь независимые подписки. Customer и подписки разных сайтов никогда не объединяются по email.

### 5.2 Price и разрешённые переходы

Subscription ссылается на точную опубликованную версию Price. Новая публикация не меняет действующий договор. Scheduled change сохраняет точный target Price, а не плавающий `price_key`.

Направление upgrade/downgrade задаётся явно в конфигурации переходов продукта. Сравнение абсолютной цены месяца и года не определяет уровень тарифа. Backend проверяет Site, Product, currency, статус и разрешённость target Price; browser передаёт только идентификатор выбора.

Для первой версии допускается один product item с quantity 1. Plan changes выполняются только внутри Product и currency. Trial duration и payment method requirement принадлежат версии Price и не меняются задним числом.

### 5.3 Политики по умолчанию

| Настройка | Значение по умолчанию |
| --- | --- |
| Upgrade в том же интервале | Сразу после подтверждённой успешной доплаты |
| Downgrade | Со следующего периода |
| Month/year change | Со следующего периода без немедленного перерасчёта |
| Grace при неуспешном продлении оплаченной подписки | 3 дня |
| Payment method при включённом trial | Необязателен до окончания trial |
| Обычная отмена | В конце оплаченного периода или trial |
| Немедленная отмена | Только если Site явно разрешил |
| Portal fallback | Выключен, включается отдельно для Site |

Правила изолированы по Site. Настройки могут запрещать переходы и выбирать отложенный upgrade вместо немедленного. Настройка не позволяет обойти подтверждение платежа или включить не реализованный провайдером сценарий.

Grace duration задаётся неотрицательным целым количеством дней; 0 отключает льготу. Required/optional payment method выбирается для конкретной версии Price, а не глобально для всех сайтов.

Версия применённой политики сохраняется с соответствующей операцией. Изменение настроек не переписывает уже начатый trial, согласованный переход или открытый grace deadline. Новый deadline применяется к следующему неуспешному продлению.

## 6 Модель подписки и состояния

### 6.1 Subscription

Subscription содержит:

- ID, Site, Customer, Product, PaymentAccount и точную текущую Price со snapshot условий;
- nullable provider subscription reference до завершения создания;
- подтверждённые начало и конец текущего периода и `paid_through`;
- Price snapshot последнего подтверждённого права доступа отдельно от provider billing Price и pending target;
- trial start/end и факт использования trial;
- billing state, cancellation flag, cancellation effective time и terminal reason;
- pending plan change с target Price, operation reference и effective time;
- ссылку на неоплаченное продление и фиксированный grace deadline;
- optimistic-lock version, created/updated timestamps и время последней provider сверки.

PaymentAccount фиксируется при создании. Смена аккаунта в настройках Site не переносит старую подписку к новому провайдеру. Действующие операции продолжают использовать её исходный account; account с non-terminal подписками нельзя удалить или отключить способом, лишающим платформу возможности обслуживать их.

### 6.2 Представление состояний

| Состояние | Смысл |
| --- | --- |
| `INCOMPLETE` | Создание или первая обязательная оплата ещё не подтверждены |
| `TRIALING` | Подтверждённый trial ещё действует |
| `ACTIVE` | Подтверждён действующий оплаченный период |
| `PAST_DUE` | Продление не оплачено, доступ вычисляется по grace policy |
| `PAUSED` | Подписка приостановлена, в том числе после trial без payment method |
| `CANCEL_AT_PERIOD_END` | Запланировано завершение действующего периода или trial |
| `CANCELLED` | Подписка подтверждённо завершена |
| `UNPAID` | Провайдер прекратил обычные попытки взыскания, платный доступ закрыт |

Запланированная отмена хранится отдельными полями, а не заменяет billing facts. `CANCEL_AT_PERIOD_END` — display state для действующего оплаченного периода или trial; при неудачном продлении клиент видит `PAST_DUE` и одновременно признак scheduled cancellation.

Истечение неподтверждённого initial flow завершается как `CANCELLED` с отдельной причиной после provider подтверждения, а не добавлением вымышленного статуса Stripe. `CANCELLED` не возобновляется: новая покупка создаёт новую Subscription. Запоздавшая оплата старой подписки фиксируется как денежный факт, но не воскрешает её и не отменяет новую подписку.

### 6.3 Access decision

Доступ рассчитывается отдельно от provider status и display state. Результат содержит `allowed`, `reason`, Site, Customer, Product, Subscription, точную entitled Price/version, revision, `evaluated_at` и `valid_until` для разрешённого доступа.

Причины различают оплаченный период, trial, grace, отсутствие первой оплаты, pause, unpaid, cancellation и истечение срока. Разрешение ограничено соответствующей подтверждённой границей времени. Если граница уже наступила, scheduler не обязан успеть изменить строку Subscription, чтобы доступ закрылся.

В первой версии `valid_until` не позже минимума из entitlement boundary и `evaluated_at + 5 минут`. Backend сайта проверяет время самостоятельно и после истечения получает новое решение. Подписанный callback инвалидирует кэш раньше; при потере callback окно устаревшего разрешения ограничено пятью минутами, а не длиной billing period. Для операций, требующих немедленной актуальности, сайт запрашивает свежее Partner API решение без кэша.

Отмена в конце периода не отнимает ранее оплаченный доступ. Подтверждённая немедленная отмена его прекращает. Отзыв CustomerSession не является отменой подписки; он закрывает browser authority.

## 7 Trial и первый платёж

### 7.1 Eligibility

Один trial разрешён для `(Site, Customer, Product)`. Смена Price, отмена и повторная подписка не дают новый trial. Eligibility резервируется атомарно при создании и считается использованной при подтверждённом начале trial.

Неизвестный результат создания удерживает reservation до reconciliation. Подтверждённая неудача до фактического начала trial освобождает reservation. Создание другого аккаунта не предотвращается этим правилом; полноценный anti-abuse не входит в этап.

### 7.2 Два режима payment method

В `REQUIRED_BEFORE_TRIAL` trial начинается только после подтверждённого setup пригодного способа оплаты и согласия клиента на будущие регулярные списания. Сам setup не является оплатой.

В `OPTIONAL_DURING_TRIAL` trial может начаться без payment method. Если к окончанию метода нет, подписка переходит в `PAUSED`, доступ заканчивается, долг и платёж за следующий период не создаются. Добавление метода после pause само по себе не запускает списание: клиент отдельно подтверждает возобновление. Новый платный период начинается по подтверждённым условиям провайдера без заднего начисления за паузу и без нового trial.

Если метод есть, provider ведёт первый платный invoice. Без подтверждённой первой оплаты платный период не открывается. Grace для ранее оплаченного продления не превращается в дополнительный trial.

Free trial не создаёт фиктивный положительный Payment. Его начало, окончание и pause записываются в SubscriptionEvent.

### 7.3 Создание без trial

Backend создаёт локальную Subscription и operation record до provider command. До подтверждения она имеет `INCOMPLETE` и не даёт платный доступ. SDK обслуживает initial payment action через provider-client abstraction; на этапе 4 используется только Fake driver.

Подтверждённая оплата и актуальный provider subscription state открывают соответствующий период. Browser redirect, завершение setup и синхронный accepted response не считаются финансовым успехом.

## 8 Продление grace и восстановление

Каждое продление имеет отдельный provider invoice и локальный billing record. Успешное списание создаёт собственный Payment и PaymentEvent, сохраняя историю прошлых периодов.

При неудачном продлении ранее оплаченной подписки сохраняется доступ на grace period. По умолчанию deadline равен окончанию предыдущего оплаченного периода плюс 3 дня, где день — 24 часа в UTC. Повторная ошибка, повторная доставка события и запоздавшее получение первого события не сдвигают эту границу.

Grace привязан к конкретному неоплаченному продлению. После deadline закрывается только платный доступ; вход, история, данные клиента и recovery остаются доступны. Provider retry schedule отделён от доступа и не переопределяется локальным grace scheduler.

Recovery работает с существующим неоплаченным invoice. Backend сначала проверяет его актуальное состояние и наличие незавершённой попытки. Он не создаёт новый самостоятельный одноразовый платёж на ту же сумму.

После подтверждённой оплаты доступ восстанавливается на подтверждённый действующий период. Оплата исторического invoice не предоставляет бессрочный доступ и не возобновляет terminal Subscription. `UNPAID` не занимает новый договор автоматически: клиент сначала завершает либо явно восстанавливает старый в рамках поддерживаемого provider flow.

## 9 Смена тарифа

### 9.1 Preview

Preview возвращает текущую и target Price, разрешённую стратегию, сумму к оплате сейчас, следующую регулярную сумму, currency, effective time и признак предварительного либо окончательного налогового расчёта.

Preview ограничен сроком действия, revision подписки и точными версиями цен. Apply повторно проверяет эти условия. Изменение подписки, истечение quote или изменение расчёта требует нового preview и подтверждения; нельзя молча списать большую сумму.

### 9.2 Немедленный upgrade

В первой версии немедленный upgrade разрешён только внутри того же billing interval. Провайдер рассчитывает доплату за оставшуюся часть периода. Пока требуемая доплата не подтверждена, текущая Price и доступ остаются прежними.

При отказе, необходимости действия или неизвестном результате upgrade остаётся pending либо завершается неуспешно; платформа не выдаёт upgraded entitlement заранее. При успешном подтверждении платежа и применения provider change текущая Price меняется атомарно с lifecycle event и outbox.

Provider adapter обязан поддерживать изменение, условное на оплату. Если такой возможности для выбранного метода нет, backend предлагает поддерживаемый отложенный переход или отклоняет немедленный вариант, а не применяет upgrade без оплаты.

Если положительный перерасчёт округлён до нуля, нужен подтверждённый provider change без фиктивного Payment. Отрицательный результат, требующий credit balance или возврата, не поддерживается как немедленный переход и предлагается только со следующего периода.

### 9.3 Отложенные изменения

Downgrade, month/year change и выбранный отложенный upgrade вступают в силу со следующего периода. До этой даты действует старый тариф. Сумма и доступ нового периода подтверждаются его billing facts; одно наступление даты не считается успешной оплатой.

Если provider уже изменил billing Price, но invoice нового периода не оплачен, entitlement во время grace остаётся на прежней оплаченной Price. После подтверждения оплаты предоставляется новый тариф; после grace без оплаты доступ закрывается. Provider billing target и реально разрешённый тариф не смешиваются.

В одной Subscription допускается одна незавершённая plan change. Сначала клиент явно снимает scheduled change либо дожидается разрешения pending provider operation; новая команда не перезаписывает неизвестный результат.

Plan changes разрешены для оплаченной действующей подписки без неоплаченного invoice, scheduled cancellation или другой unresolved mutation. Во время trial, pause, past due и unpaid сначала используется соответствующий recovery/cancellation flow. Это ограничение первой версии предотвращает неописанные credits и trial reset.

## 10 Отмена и refunds

По умолчанию клиент отменяет в конце текущего оплаченного периода либо trial. Backend показывает effective time и сохраняет доступ до него. До наступления этой даты и подтверждённого завершения клиент может отозвать scheduled cancellation.

Немедленная отмена доступна только при включённой настройке Site и требует отдельного явного подтверждения. Она не создаёт автоматический refund или дополнительный final charge. До отправки команды adapter проверяет возможность отмены без неожиданного перерасчёта; неподдерживаемый вариант отклоняется.

Для `PAUSED`, `UNPAID` и `INCOMPLETE` без действующего оплаченного периода или trial разрешено отдельное явное завершение сейчас, даже если Site запрещает досрочно обрывать оплаченный доступ. Оно не стирает invoice и не считается подтверждённым до ответа через provider fact/reconciliation. Так приостановленная подписка не занимает Product slot навсегда.

Незавершённый plan change сначала разрешается или явно снимается у провайдера. Scheduled cancellation не должна оставлять скрытый переход на другой тариф после закрытия подписки.

Refund и cancellation независимы. Refund адресуется конкретному успешному Payment, использует существующие ограничения полной/частичной суммы и сам по себе не отменяет подписку. Cancellation не помечает Payment как refunded. Решение администратора о возврате не заменяет provider подтверждение.

Просроченный invoice не удаляется отменой. Платформа показывает его отдельно и не инициирует погашение без разрешённого recovery flow. Реальные provider настройки окончательного invoice и повторных попыток должны быть проверены в Sandbox на этапе 5.

## 11 Способ оплаты и Portal

Payment method setup является отдельной операцией, связанной с Site, Customer, PaymentAccount и целевой Subscription. В первой версии обновляется метод именно этой подписки, без неявной смены способа оплаты других продуктов клиента.

Успешный setup не создаёт PaymentEvent о списании, не погашает долг, не восстанавливает entitlement и не начинает новый период сам по себе. Recovery либо resume подтверждаются отдельной командой. Согласие на будущие списания фиксируется с назначением setup; секреты и полные данные метода не попадают в журнал.

Provider клиент может требовать дополнительное действие и при setup, и при оплате. UI обязан поддерживать эти состояния, не обещая отсутствие дальнейшей аутентификации.

Portal включается отдельно на Site. Backend создаёт короткоживущую provider-neutral navigation session только после owner-проверок; return URL выбирается из настроенного allowlist, а не произвольного URL browser. Portal URL не попадает в logs, events или постоянное browser storage.

Fake Portal — обозначенный локальный simulation flow, использующий те же application use cases. Он не изображает настоящий Stripe Portal. Возврат из Portal запускает чтение состояния, но не подтверждает оплату или отмену. На этапе 5 проверяется, что его действия не обходят правила каталога, trial и plan changes; иначе соответствующие Portal actions остаются выключенными.

## 12 Денежные факты и billing records

### 12.1 Связь с Payments

Для подписок добавляется явный `PaymentSource.SUBSCRIPTION` и immutable subscription billing context. Нельзя использовать `CATALOG` для prorated суммы: существующая каталожная модель требует точного совпадения Payment с полной суммой Price.

Context включает Subscription, локальный billing record, тип `INITIAL`, `RENEWAL` или `PRORATION`, применимые Price versions, оплачиваемый период и provider invoice reference. Payment остаётся собственностью модуля `payments` и содержит фактически подтверждаемую сумму операции, а не изменяемую текущую цену подписки.

Billing record отражает invoice и связывает его с Payments и попытками. Один invoice не обязан равняться одной попытке. Один provider payment object не может породить два Payment. Reference уже связанного Payment не заменяется другим object ID; если провайдер действительно создаёт новый денежный объект, создаётся отдельный связанный Payment, а не сбрасывается terminal Payment.

Уникальности учитывают provider, PaymentAccount и внешний object ID. Денежный факт хранится один раз независимо от того, пришёл он через payment event, invoice event или reconciliation.

### 12.2 Нулевые суммы и settlement

Zero-amount invoice, в том числе trial или округлённый до нуля upgrade, является lifecycle/billing фактом, но не денежным списанием и не Refundable Payment. Статус invoice `paid` сам по себе не доказывает новое движение денег: adapter должен различать подтверждённую оплату и отсутствие суммы к списанию.

Credit balance, ручная внешняя отметка paid и иные не поддержанные этим этапом settlement flows не превращаются в вымышленный `payment.succeeded`. Неизвестный способ settlement сохраняется как требующий сверки. Платформа не объявляет поддержку credits только потому, что умеет читать invoice status.

### 12.3 Два журнала

`PaymentEvent` продолжает хранить денежные факты с обязательным Payment reference. `SubscriptionEvent` хранит создание и подтверждение подписки, trial, изменения тарифа, pause, cancellation, grace/access transitions и reconciliation lifecycle facts.

SubscriptionEvent содержит ID, sequence внутри Subscription, Site, Subscription, PaymentAccount, тип, source, времена occurred/received, request/idempotency correlation и разрешённые references на invoice/Payment/provider object. В нём нет bearer tokens, email, card data, произвольного provider payload или donor message.

API command audit отделяется от подтверждённого provider fact: запрос на отмену не равен отменённой подписке. Журналы append-only; исправления записываются новым событием. Текущие projections можно восстановить по сохранённым фактам и актуальному provider state, без обещания полной event-sourced модели.

## 13 Надёжность событий и команд

### 13.1 Idempotency и concurrency

Все изменяющие Subscription команды Customer, Partner и Admin требуют `Idempotency-Key`. Scope включает principal/authority, Site и operation; fingerprint включает объект, payload и выбранные версии. Повтор с иным fingerprint отклоняется. Повтор принятой команды возвращает ту же operation/resource reference и не запускает новую операцию.

Provider key детерминированно выводится из локальной операции. Таймаут переводит operation result в `UNKNOWN`, а не в подтверждённый отказ. Разрешение неизвестного результата требует чтения либо reconciliation исходной операции; новый ключ не обходит запрет конфликтующей mutation.

БД защищает non-terminal uniqueness, trial reservation, provider object mapping и event deduplication. Optimistic locking и согласованный порядок блокировок сериализуют конкурирующие create/change/cancel/recovery/anonymization, включая interleavings между разными API.

### 13.2 Inbox и применение фактов

Provider event принимается через существующую проверку подписи и account scope и сохраняется в inbox с уникальностью `(provider, payment_account_id, provider_event_id)`. Fake event ingress остаётся административным инструментом, а не public browser API.

Worker проверяет Site, Account, Subscription и invoice/payment mappings. Нельзя связывать внешние объекты только по Customer email или browser metadata. Неполные, конфликтующие и переставленные события требуют актуального provider read.

Один timestamp события не задаёт универсальный порядок: подписка может проходить past due и recovery многократно. Handler учитывает текущий provider state, billing period, operation correlation и terminal invariants. Повтор одного факта не увеличивает оплаченный срок и не создаёт второй журналируемый денежный успех.

Факт, Subscription/Payment projections, journal sequence и outbox фиксируются одной транзакцией. Ошибка доставки callback не откатывает финансовый результат. Ошибка worker до commit не оставляет половину обновлений.

### 13.3 Reconciliation и deadlines

Ручная и периодическая reconciliation охватывают неизвестные mutations, incomplete creation, pending upgrade/setup/cancel, неоплаченные invoice и stale Subscription projections. Повторная сверка без нового факта не создаёт искусственные события.

Stage 4 добавляет ограниченный subscription sweep с batch/lease/backoff и метриками, но не переписывает несвязанный payment reconciliation engine. Fake provider state сохраняется в PostgreSQL, чтобы рестарт не превращал неизвестную операцию в новую.

Отдельная локальная проверка deadline записывает единственный access suspension fact при окончании grace. Она не выдумывает provider cancellation и не запускает списание. Access decision проверяет время даже до выполнения этого job.

## 14 API и интеграция сайтов

### 14.1 Customer API

Customer API предоставляет следующие owner-scoped операции:

- список собственных подписок с pagination, подробности и lifecycle/payment history;
- создание по опубликованной Price;
- preview и применение разрешённого plan change;
- снятие scheduled plan change;
- отмену и отзыв scheduled cancellation;
- создание и чтение payment method setup;
- явную recovery/resume command;
- создание Portal session при разрешённой настройке.

Site и Customer берутся из проверенной CustomerSession, а не доверяются полям browser. Все mutation guards повторно проверяются перед commit. Чужой Subscription ID не раскрывает факт существования объекта.

Для новых функций добавляются scopes `SUBSCRIPTION_READ` и `SUBSCRIPTION_MANAGE`. Старые persisted sessions автоматически не получают новые полномочия; требуется новая аутентификация. Новые scopes выдаются только для Site с включёнными подписками, обновлённые SDK validators принимают их явно. Site без этой функции сохраняет прежний набор scopes.

Subscription UI требует обновлённый SDK. Расширение enums в Customer payment history и authentication response входит в контрактный rollout этапа; совместимость не означает, что старый strict SDK умеет новые источники платежей без обновления.

### 14.2 Partner и Admin API

Partner API предоставляет те же разрешённые server-to-server сценарии внутри authenticated Site и чтение access decision. Для ключей вводятся явные read/manage Subscription scopes, которые не добавляются старым ключам молча. Любой переданный Customer/Product/Price проверяется на принадлежность Site. Secret API key не передаётся в SDK.

Admin API позволяет фильтровать подписки, читать историю, менять будущие site/product policies, выполнять явно подтверждённые допустимые команды и запускать reconciliation. Существующие Admin session, CSRF и audit сохраняются. Кнопка ручной выдачи финансового успеха не добавляется.

Transport responses используют существующий snake_case стиль; SDK types — camelCase. Safe DTO и secret-bearing capabilities разделены. OpenAPI является источником Admin types; bounded parsing, fixed safe errors и request limits сохраняются для новых endpoints.

Operation response содержит reference, status и доступное next action, а не ложный terminal Subscription success. `UNKNOWN` — состояние операции/наблюдения, а не новый денежный Payment status.

### 14.3 Уведомления backend сайта

Subscription lifecycle/access callbacks используют новый явно версионированный payload. Существующие payment callback v1 и donation callback v2 не меняются. Subscription-related payment callbacks также получают отдельный versioned context, чтобы сайт мог отличить initial, renewal и proration.

Payload содержит event ID/type, Site, Subscription, Customer, Product, revision, occurred time, safe state и access expiry; Payment/invoice reference добавляется только для соответствующего факта. Provider secrets и PII отсутствуют.

Подпись, rotation, retries, `DEAD` и manual replay используют существующий outbox. Callback consumers должны дедуплицировать event ID и не откатывать revision; при пропуске или конфликте событий backend сайта читает актуальное состояние через Partner API.

Сайт не сохраняет разрешённый доступ дольше `valid_until`. Локальный callback endpoint и cache не являются глобальным источником финансовой истины.

### 14.4 Статические сайты

Email OTP и subscription manager работают без собственного site backend, обращаясь к центральной платформе. Однако JavaScript и скрытые DOM-блоки не защищают платные данные. Их выдаёт backend, serverless-функция либо защищённый шлюз после server-side проверки полномочий и access decision.

Встраиваемый UI не заменяет эту защиту. CORS также не является механизмом проверки права на продукт.

## 15 Безопасность и анонимизация

Сохраняются exact Origin binding, server-side ownership, no-store responses, запрет redirect при credential-bearing API requests и ограничения хранения browser tokens. Subscription ID не является capability.

SDK не логирует, не помещает в URL/DOM/events и не сохраняет в постоянное хранилище Customer tokens, setup/client secrets, Portal URLs и provider payloads. Payment method данные передаются только через provider-client abstraction; этап 4 не собирает PAN/CVC.

В рамках review этой спецификации вводится защитное правило для существующей anonymization command: Customer с non-terminal Subscription или неизвестным результатом её создания/отмены сначала должен явно завершить эти подписки и подтвердить результат у провайдера. Иначе anonymization отклоняется без удаления данных и без скрытой cancellation command.

Этот guard проверяется атомарно с Customer lifecycle и сериализуется с созданием подписки. Scheduled cancellation ещё не является terminal; если требуется немедленная anonymization, допустимую немедленную отмену выполняют отдельным авторизованным действием.

После подтверждённого завершения сохраняется существующее удаление PII, identities, sessions и сообщений. Обезличенные финансовые и subscription facts остаются. Guard — техническая защита биллинга, а не утверждение о юридических основаниях или сроках обработки запроса на удаление.

## 16 Клиентский интерфейс и Admin

Headless SDK позволяет сайту полностью контролировать разметку. Готовый subscription manager Web Component использует изолированные стили и CSS design tokens для цвета, шрифта, фокуса, границ и скругления. Next.js или React на подключаемом сайте не требуются.

Компонент показывает текущий тариф, подтверждённый период, trial/grace, scheduled change/cancel, следующую регулярную сумму с признаком предварительного налога, permitted actions и связанную историю. Plan change имеет preview и отдельное подтверждение. Cancellation объясняет дату потери доступа, а setup не подписывается как погашение долга.

UI различает loading, ready, processing, requires action, unknown, expired session и подтверждённый результат. Нет автоматического replay финансовых POST. Logout, expiry, смена Site/API и disconnect отменяют browser work, освобождают capabilities и подавляют stale completions. После неизвестного результата или возврата из Portal выполняется безопасное чтение.

Abort останавливает browser work, но не доказывает отмену уже принятой server/provider операции. Повторное открытие менеджера читает серверные operation/subscription records, а не создаёт новый checkout вслепую.

Browser events содержат только frozen safe identifiers, state и revision. Они предназначены для UX, не для выдачи платного контента. Customer authority хранится по существующим правилам; новые provider/setup capabilities остаются в памяти, если для них не определён безопасный recovery contract.

Accessibility следует существующим компонентам: labelled native controls, keyboard actions, visible focus, polite status, focused validation errors и reduced motion. Новая copy поддерживает английский и португальский, locale-only update не запускает финансовую операцию.

Admin остаётся в `frontend/admin`. Добавляются список и карточка подписки, фильтры Site/Customer/Product/state, lifecycle/payment history, настройка правил и разрешённых переходов, понятные unresolved/reconciliation states. Refund использует существующий payment flow. Fake simulation controls явно отделены от пользовательского SDK.

Demo показывает оформление и управление подпиской, email-вход, trial, recovery и тему под сайт. Он не заявляет защиту приватного контента в чисто статическом JavaScript.

## 17 Изменения хранения и совместимость

Additive migrations создают Subscription, policy/transition configuration, trial eligibility/reservation, billing records, operation records, SubscriptionEvent и provider mappings. Composite Site/Customer/Product/Account constraints исключают cross-site linkage; non-terminal uniqueness обеспечивается БД, а не только Java-проверкой.

Payments получает явный subscription source/context с отдельными CHECK constraints. `CATALOG`, `DYNAMIC` и `DONATION` сохраняют прежние правила. Нельзя расширять существующий fallback `else` так, чтобы Subscription случайно проходила donation validation.

Существующий one-time catalog checkout по-прежнему принимает только `ONE_TIME`. Recurring Price используется через отдельный Subscription flow и не превращается в разовую покупку обходным путём.

Price получает trial payment method policy без изменения amount, interval, version и смысла существующих опубликованных условий. Для legacy Price с `trialDays > 0` additive default — `OPTIONAL_DURING_TRIAL`; для `trialDays = 0` trial отсутствует. Изменение опубликованной trial policy после миграции требует новой Price version.

CustomerSession scope enum, safe payment history, Admin generated types, SDK discriminated unions и callback validators обновляются согласованно. Existing payment/donation callback payloads и уже сохранённые события не переписываются.

Миграции не создают фиктивные Customer, не переназначают Payments и не изменяют суммы ранее подтверждённых операций. Проверки миграции выполняются как на пустой БД, так и поверх данных этапов 1–3.

## 18 Наблюдаемость

Структурированные logs используют request, operation и object correlation без secrets, email и provider bodies. Audit фиксирует actor, Site, command, outcome и безопасные identifiers отдельно от финансового подтверждения.

Метрики покрывают создание и изменение подписок, pending/unknown age, inbox lag, reconciliation outcomes, grace expiration, recovery, callback delivery и rejection conflicts. Cardinality ограничена: Customer/Subscription ID не используются как labels.

Неизвестные операции, неподдержанный settlement, stuck pending changes и dead callbacks видны в Admin. Повторный запуск job или рестарт не создают новый денежный факт сами по себе.

## 19 Проверки и критерии приёмки

Backend использует Maven, unit tests для чистого domain, PostgreSQL integration tests для constraints/transactions и Cucumber для бизнес-сценариев. Время управляется через Clock и Fake billing controls, без ожидания реальных дней.

Обязательные группы сценариев:

1. Создание только после authentication; cross-Site и cross-Customer ID не дают доступ. Разные продукты могут сосуществовать, повторная активная подписка того же продукта запрещена и при конкуренции.
2. Trial с required и optional методом; начало после setup, окончание без метода без долга, явный resume, отсутствие повторного trial после смены тарифа или resubscribe.
3. Initial payment processing, failure, requires action, unknown и success. До подтверждения нет платного доступа и ложного succeeded event.
4. Отдельные monthly/yearly renewal payments; фиксированный grace, повторные failures без продления deadline, закрытие доступа на границе времени без работающего scheduler и подтверждённое recovery.
5. Preview expiration и revision conflict; немедленный same-interval upgrade только после доплаты либо подтверждённого zero-amount change; decline/unknown сохраняют старый тариф; downgrade и month/year вступают со следующего подтверждённого периода. Неоплаченный новый период сохраняет прежний тариф только на grace.
6. Scheduled change отменяется явно; конкурирующие change/cancel/recovery не оставляют два активных намерения. Scheduled cancellation сохраняет доступ, её отзыв допустим до effective time; terminal Subscription не возобновляется поздним событием.
7. Payment method setup не создаёт списание, не погашает invoice и не меняет другую подписку клиента. Fake Portal не обходит ownership и policies; возврат из него не подтверждает успех.
8. Полные и частичные refunds адресуются конкретному Payment и не отменяют Subscription; cancellation не создаёт refund или дополнительное списание.
9. Duplicate и out-of-order events, пропущенный webhook, crash до/после commit, unknown provider timeout и restart reconciliation. Нет двойных Payments, повторных журналируемых фактов или callback side effects.
10. Zero-amount trial/upgrade invoice не создаёт Payment. Invoice paid без подтверждённого денежного объекта не превращается в payment success.
11. Callback signature, payload version, retries и replay; consumer revision и истечение access cache не позже пяти минут либо более ранней entitlement boundary. Событие другого сайта не может изменить доступ.
12. Anonymization guard и гонка create/anonymize; после terminal cancellation PII удаляется по существующему flow, факты сохраняются без secrets.
13. Миграция существующих Price, Payments и Customer sessions без изменения прежней финансовой истории; regression catalog/dynamic payments, OTP/OIDC, identity linking и one-time donations.

Frontend acceptance включает TypeScript contract checks, SDK headless/component tests, Admin tests и browser E2E для клиентского и административного flows. Проверяются keyboard/aria behavior, theme, en/pt copy, session expiry, logout, disconnect, stale responses, bounded parsing и отсутствие secrets в events/storage/logs.

Compose smoke подтверждает полный локальный путь Fake Provider → inbox → projections/journals → outbox → backend сайта, а также browser управление и recovery. После проверок останавливаются только запущенные для них сервисы; существующие данные и unrelated процессы сохраняются.

Этап принят, когда эти сценарии проходят, договорённые контракты и local-development guide обновлены, новые regression failures отсутствуют, а ограничения Fake/Stripe явно указаны. Отчёт о тестах должен опираться на фактический запуск; наличие этой спецификации не является таким отчётом.

## 20 Переход к Stripe на этапе 5

Stage 5 реализует provider ports поверх выбранной и зафиксированной Stripe API version. Проверяются subscription/invoice/payment mappings, first payment и off-session recovery, условные на оплату plan updates, free trial и pause, cancellation semantics и per-subscription default payment method.

Stage 4 не навязывает платные Trial Offers или конкретный Stripe API primitive. В Sandbox выбирается совместимый free-trial механизм и проверяется соответствие нашим правилам, без расширения scope до paid trial.

Отдельно проверяются доступные payment methods для португальского аккаунта, валют, recurring/off-session сценариев и выбранного UI. Наличие метода у Stripe вообще не обещает его применимость к каждой операции подписки. До Sandbox-проверки методы не объявляются готовыми.

Перед real-provider интеграцией от пользователя потребуются Stripe-аккаунт и test credentials через secret configuration, а затем настройки webhook, разрешённые домены/return URLs и необходимые consent тексты. Секреты не передаются через документацию или browser SDK. Эти действия не нужны для локального этапа 4.

Provider ориентиры:

- Условные на оплату обновления имеют ограничения совместимости; adapter проверяет их, а не предполагает универсальную поддержку. [Stripe pending updates](https://docs.stripe.com/billing/subscriptions/pending-updates).
- Trial mechanisms отличаются по API и возможностям; free trial выбирается отдельно от расширения до paid introductory pricing. [Stripe trial comparison](https://docs.stripe.com/billing/subscriptions/trials/compare).
- SetupIntent подготавливает метод для будущего использования без списания. [Stripe Setup Intents](https://docs.stripe.com/payments/setup-intents).
- Subscription billing асинхронен; значимые факты приходят через события и сверку provider state. [Stripe subscription webhooks](https://docs.stripe.com/billing/subscriptions/webhooks).
- Отмена и её последствия для invoice требуют явных provider параметров. [Stripe cancellation](https://docs.stripe.com/billing/subscriptions/cancel).
- Проверка полномочий для платного контента выполняется на доверенной серверной стороне. [OWASP authorization checks](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html#verify-that-authorization-checks-are-performed-in-the-right-location).

## 21 Следующий шаг

Пользователь проверяет и подтверждает эту письменную спецификацию, включая guard анонимизации при незавершённой подписке. После подтверждения составляется отдельный план реализации с зависимостями, TDD-задачами и проверками. Реализация начинается после review плана и выбора способа выполнения; это согласование не заменяет последующие gates.
