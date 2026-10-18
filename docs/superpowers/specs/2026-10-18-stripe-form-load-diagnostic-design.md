# Ограниченная диагностика загрузки Stripe Payment Element

Статус: дизайн подтверждён пользователем; разрешена офлайн-реализация с тестами и независимым ревью по [плану](../plans/2026-10-18-stripe-form-load-diagnostic-implementation.md). Сам документ и реализация НЕ разрешают Sandbox-запуск. Новые зависимости не требуются.

## Зачем

R37 остановился до ввода карты и Pay: `PAYMENT_FORM_READY`, `loading/FRAME/ABSENT`. R38 проверил исходники и добавил characterization-тесты: такой снимок совместим как с ожиданием Stripe `ready`, так и с ожиданием presentation catch-up после `ready`. Второй путь возможен в SDK, но его наличие в R37 не доказано. `FRAME` означает любой внешний iframe под собственным контейнером, а не готовые поля карты. Уникальная причина R37 остаётся неизвестной.

Цель нового режима — установить, на каком именно ожидании останавливается ОДНА загрузка формы, не вводя платёжные данные и не вызывая подтверждение оплаты. Это диагностика границы инициализации, не гарантия нахождения сетевой/CSP-причины и не внешняя приёмка этапа 5A.

Решения ниже подтверждены пользователем для реализации; полномочия на фактические runtime-действия остаются отдельными.

## Разрешения и явные запреты

Сейчас разрешены только офлайн-реализация и её проверка. Следующие разрешения независимы:

1. Подтверждение дизайна разрешает отдельный план и офлайн-реализацию с тестами/ревью, но НЕ реальный запуск.
2. Будущий запуск требует отдельного явного разрешения на ОДНУ ограниченную Sandbox-попытку с оговорёнными объектами подготовки.
3. Полный Sandbox-прогон/оплата требуют другого разрешения; ограниченный режим никогда сам в них не переходит.

В реальном режиме допускаются только test credentials/Sandbox. Создание формы требует нового неподтверждённого PaymentIntent: «без подтверждения оплаты» не означает «вообще без provider writes». При холодной подготовке максимум один новый Stripe Product, один Price и один PaymentIntent; локально — один Account/Site/Product/Price, один anonymous catalog Checkout и один первый PaymentAttempt при подготовке client-session. Если для готовности Site нужна конфигурация identity provider, она создаётся атомарно с Site по существующему контракту, без challenge/verify/отправки email. Регистрируется один фиксированный return URL текущего loopback demo origin; перехода на него нет. Campaign, Donation, customer login/session, OTP, Subscription и Portal не создаются.

Точная сумма свежего Checkout/PaymentIntent — EUR20, tax NONE, ONE_TIME Price. Эти параметры нужны для воспроизводимого свежего checkout, не для списания. Customer отсутствует; сохранённые способы оплаты не подключаются. Wallet/Link UI остаётся выключенным существующим driver. Повторный Checkout/client-session/PaymentIntent, любые refunds и публикация ещё одной версии Price запрещены.

Создание Intent не использует `confirm=true`, payment_method, confirmation_token, customer, setup_future_usage, on_behalf_of или transfer_data. Fresh provider proofs требуют `livemode=false` и отсутствие платёжного метода/charge, без раскрытия raw response. Первый локальный Attempt не равен отправленному Stripe confirmation: ожидается sequence=1/status=REQUIRES_PAYMENT_METHOD и один ATTEMPT_CREATED от единственного client-session вызова; второй Attempt/retry запрещён.

Нельзя вводить карту/CVC/expiry/postal, нажимать Pay/Enter для оплаты, вызывать `confirmPayment`, Fake/server confirm, `markPay`, retry, refunds, donation/OTP/missed-webhook/reconcile journeys. Нельзя принимать browser state за финансовое доказательство. Нельзя использовать Live/Connect/Managed Payments, менять CSP/селекторы/production readiness timeout, выполнять автоматические повторы, переиспользовать старые runtime/checkout/operation keys или удалять старые ресурсы.

## Отдельный вход, не сокращённый полный прогон

Новые отдельные runner и Playwright config/spec не импортируют полный `stripe-sandbox.spec.ts` и не вызывают `openOwnedSandboxAcceptance().run()`. Включение ограниченного режима не меняет поведение существующих full/Fake сценариев. Его результат не имеет полей `accepted`, `paymentConfirmed` или общей успешной приёмки 5A.

Новые явные opt-in: `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC=true`, `PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY=true`. Их отсутствие, debug/trace-режим, full-run/allow-test-operations flags или неоднозначная конфигурация отклоняются ДО доступа к приватным файлам и runtime dependencies. Exact имена файлов/команд определит план после подтверждения, команды реального запуска пока не публикуются.

Новый runtime получает свежие run UUID, source SHA, свободные loopback ports и собственные журнал/блокировку. Нельзя выбирать существующие R37 или другие retained fixtures. Readiness bootstrap и точечные owner-проверки существующего кода переиспользуются, но минимальный provisioner создаёт ровно один checkout; существующий полный provisioner с четырьмя checkout/campaign и его валидаторы НЕ ослабляются. Для совместимого локального bootstrap допустимы его служебные контейнеры, но не дополнительные business fixtures.

Stripe CLI, device OAuth и webhook listener в этом режиме не нужны: подтверждений/возвратов/доставки событий нет. Будущий private runtime boundary проверяет test-префиксы обоих ключей, default-account binding SECRET key и закреплённую default API version через bounded read-only `/v1/account` непосредственно. Запрос не содержит Stripe-Account/Connect context и явного Stripe-Version override, который скрыл бы несовпадение default version; redirect/retry запрещены. Ожидается текущая закреплённая в проекте 2026-09-30.endive. Секреты читаются только внутри этой границы из существующего игнорируемого `stripe-sandbox.env.local`; controller и отчёты содержимого файла не получают. Реализация binding получает отдельные офлайн-тесты; CLI-безопасность полного прогона не ослабляется.

`/v1/account` НЕ доказывает принадлежность publishable key этому account. У public key здесь проверяются только test-форма и неизменность передачи в текущую capability; до успешной реальной загрузки его совместимость остаётся непроверенной, не выдаётся флаг «пара ключей верифицирована». Нельзя выводить ни Live/Sandbox mode объекта, ни public-key/account pairing только из account response. Любая неизвестность secret binding/API version или полученный livemode=true блокируют запуск формы; неизвестное public-key pairing не заменяется предположением об успешном binding.

## Пассивные отметки в реальной цепочке SDK

Используется реальная цепочка `GenericPaymentWidget -> CheckoutProviderView -> mountStripePayment`, реальные API-ответы и Stripe-hosted Element. Monkey patching Stripe.js, подмена `ready`, route-fulfill ответов или искусственное разрешение Promise в реальном диагностическом запуске запрещены.

Предлагается внутренний модуль с WeakMap-регистрацией только ОДНОГО явно armed widget и его одного текущего provider host/generation. Он не экспортируется из публичного SDK entry, не создаёт событий, не вызывает пользовательских callback, не пишет в console/storage/network и не принимает provider objects/ошибки/данные. Default/unarmed путь ничего не накапливает. Отметки синхронные, без новых await/таймеров; их отказ не меняет обычный SDK flow, но делает диагностический результат непригодным.

Отдельная diagnostic-only страница, не обычный demo main, до Resume arm-ит один widget. Она импортирует reader и widget из одного module graph; handshake подтверждает работоспособность singleton registry. Чужая/дублированная module instance, повторный mount, смена generation/host, повторный Resume, исчезновение widget или отсутствие инструментации дают фиксированный failed/manual-check результат, а не «Stripe не готов».

Reader отдаёт только замороженные копии конечных значений для конкретной регистрации. Никакой внешней функции записи отметок или пользовательского отключения защиты подтверждения страница не предоставляет. На странице нет Login/Donation/Subscription UI и нет других экземпляров checkout widget.

Отметки в существующих точках, без чтения аргументов Stripe events:

| Отметка | Где фиксируется |
| --- | --- |
| capability_started / capability_ready | до и после единственного `prepareClientPayment` |
| loader_started / loader_ready | до и после существующего `loadStripeClient` |
| element_created | после `elements.create('payment', ...)` |
| mount_called / mount_returned | непосредственно до/после `element.mount(host)` |
| ready_seen / loaderror_seen | в существующих активных ready/loaderror handlers, до resolve/reject; payload не читается |
| catchup | NOT_STARTED / PENDING / RESOLVED / REJECTED / ABORTED, только для первоначального post-ready presentation catch-up |
| view_completed | после успешного завершения `CheckoutProviderView.mount` |
| widget_completed | после проверки текущей generation и установки providerReady |

Состояние provider `ready` НЕ выводится из iframe presence и НЕ подменяет завершение view/widget. Синхронный ready во время mount не теряется: таблица — независимые флаги, не линейный «последний этап». `ready_seen` без `mount_returned` не считается завершённым mount. Поздние события после abort/смены binding не улучшают итог.

## Подтверждение технически запрещено

Не ограничиваемся обещанием теста «не нажимать кнопку». Диагностический режим latch-ит deny-confirmation в конкретных создаваемых view и Stripe handle ДО начала mount. `CheckoutProviderView.confirm()` и `StripePaymentHandle.confirm()` проверяют этот latch и прекращаются ДО Fake confirm/Stripe confirm/backend refresh; попытка даёт фиксированный `CONFIRMATION_BLOCKED` и проваливает диагностику. Денежные capabilities (`markPay`, refund, retry, extras) runner вообще не выдаёт.

Deny latch сохраняется до уничтожения этих объектов; disarm/очистка WeakMap/закрытие наблюдения не превращают старый handle в разрешённый для оплаты. Обычный не armed SDK путь не меняется. Это только дополнительный запрет: наблюдение не может выдавать или расширять финансовую authority.

Browser driver делает единственный явный Resume и только read-only ожидания/снимки. Pay/клавиатурные действия/ввод в iframe отсутствуют. Если штатная кнопка Pay стала доступна, это конец наблюдения и начало закрытия, не приглашение продолжать сценарий. Перехватывать/подменять Stripe responses для этого нельзя.

## Время и результаты

Будущий единичный запуск имеет один изначальный active deadline 15 минут от entry, включая подготовку. Максимум наблюдения формы — 60 секунд от непосредственно предшествующей Resume отметки, обрезанный оставшимся исходным временем. Исходный cleanup ceiling — active deadline +2 минуты, никогда не продлевается. Сейчас никакие бюджеты/UUID/allowance не резервируются.

Отдельная контрольная точка на 15-й секунде соответствует текущему acceptance wait. Максимум два публичных снимка: контрольный, если форма ещё не готова, и итоговый. При готовности раньше 15 секунд — один итоговый снимок сразу. Поздняя готовность до 60 секунд отдельно помечается и не меняет production wait 15 секунд. Нет повторного Resume, reload/remount или повторного client-session. Только bounded local observation, никакого provider/API polling.

Строгий вывод содержит только версию схемы, enum результата, bucket BEFORE_15S / AFTER_15S / AT_LIMIT, перечисленные boolean/enum отметки, существующие finite widget_state/element_state/pay_state, подтверждение binding и отдельные cleanup booleans. Pay readiness дополнительно устанавливается настоящим enabled+visible locator, не выводится из `pay_state=ENABLED`. Не больше 4KiB на envelope, максимум два envelope; неизвестные/дублированные/лишние поля, accessor/nonprimitive/oversized/truncated данные отклоняются. Report builder не принимает произвольный text/error/message.

Классификация:

- FORM_READY_BEFORE_15S / FORM_READY_AFTER_15S — реальный Pay locator готов, согласованы mount_returned/ready_seen/view_completed/widget_completed, без binding/deny нарушений.
- WAITING_CLIENT_SESSION / WAITING_STRIPE_LOADER / ELEMENT_SETUP_INCOMPLETE — по отсутствующим соответствующим completion flags; исключения не превращаются в факт ожидания.
- WAITING_ELEMENT_READY — Element создан/mount вернулся, ready не наблюдался, loaderror не наблюдался, операция действительно ещё pending на пределе.
- WAITING_PRESENTATION_CATCHUP — ready наблюдался, catchup=PENDING, wrapper действительно не завершился.
- WIDGET_HANDOFF_INCOMPLETE — provider/view уже завершились, но реальные widget/Pay readiness ещё нет.
- ELEMENT_LOADERROR — наблюдался исходный loaderror, без provider payload или утверждения сетевой причины.
- OPERATION_FAILED / ABORTED / DEADLINE_REACHED / INSTRUMENTATION_UNAVAILABLE / BINDING_LOST / CONFIRMATION_BLOCKED / UNEXPECTED_ACTIVITY — отдельные failed/manual-check результаты; исходная ошибка/abort/deadline всегда приоритетнее интерпретации снимка.

При неоднозначности классификатор отказывается от более конкретного диагноза. Финансовая/cleanup неопределённость также проваливает весь сценарий. Успешная диагностика загрузки — НЕ успешная оплата/приёмка; exit0 разрешён только для FORM_READY_* с полной проверкой отсутствия подтверждения и подтверждённым cleanup. Всё остальное exit1/manual check. 15-секундный checkpoint сам по себе не создаёт ошибку и не заменяет исходный operation outcome.

## Приватность, проверка отсутствия оплаты и cleanup

Trace, screenshots, video, HAR, console/error dumps, HTML/DOM/iframe contents, URL/src/query, input values, provider event/error payload, network bodies/headers и произвольные stack traces не собираются и не сохраняются. Не читаются старые CLI/OAuth/JWK/mail/OTP/runtime logs. Browser-local session/capability остаётся только в пределах текущего владения; публичный JSON его не содержит.

До Resume сохраняется safe server baseline: один checkout/PaymentIntent, неизменные money/binding, ноль Attempts и ноль ATTEMPT_CREATED. Это соответствует текущим CatalogCheckoutService/PrepareClientPaymentService, а не искусственному pre-create client-session. Единственный нормальный client-session во время mount может создать ровно один первый Attempt; после успешного capability ответа final proof требует этот же Attempt sequence=1/status=REQUIRES_PAYMENT_METHOD, один ATTEMPT_CREATED и отсутствие дополнительных Attempts/Checkout/PaymentIntent. При незавершённом/отклонённом client-session допустимо только 0 или 1 такой начальный Attempt: его наличие не скрывает неопределённый outcome и не разрешает повторять запрос. Нельзя снимать baseline после второго искусственного prepare-вызова.

Финальный bounded read того же Intent проверяет `livemode=false`, `requires_payment_method`, `payment_method=null`, `latest_charge=null`, amount_received=0 и отсутствие связанного Customer; backend journal не содержит PAYMENT_SUCCEEDED, refunds отсутствуют. Эти чтения разрешаются только исходным binding/operation ownership и оставшимся исходным deadline, не новым ключом/повтором. Несовпадение или неизвестный исход -> UNEXPECTED_ACTIVITY/manual check, без confirm/cancel/reconcile. Счётчик запрещённых SDK-вызовов должен быть нулевым, но один только счётчик не является финансовым доказательством.

При первом сбое запрещаются дальнейшие writes/запуски. Если ownership и исходный deadline сохранились, допустим ровно один финальный bounded read отсутствия оплаты; иначе финансовый proof остаётся UNCONFIRMED, не восстанавливается новым deadline. Итоговый снимок фиксируется ДО teardown, чтобы уничтоженный iframe не переписал наблюдавшееся состояние.

Cleanup раздельно подтверждает закрытие единственного browser context/browser ownership, остановку только текущих freshly-verified owned containers и завершение controller/fixture. Нельзя выводить общий cleanup из «все контейнеры остановлены». Контейнеры, network, volumes, provider objects, данные и аудиторские записи сохраняются; нет prune/down -v/provider delete/cancel/автоудаления. Нехватка bridge capacity/диска/port, чужой ресурс или незакрытый процесс -> stop/manual check, не автоматическая очистка или новый запуск. В этом режиме нет listener/CLI, поэтому их несуществование не выдаётся за остановку.

## Офлайн-проверки реализации и границы вывода

До будущего запуска обязательны meaningful RED->GREEN для новой инструментации/deny/boundary/formatter, полный SDK unit suite, связанные runner/reporter tests, SDK/demo typecheck и независимое узкое review. Старые characterization-тесты R38 не считаются RED для новой реализации.

Минимальная матрица: pending capability/loader/ready; синхронный ready; loaderror и opaque throws; ready+pending/resolved/rejected catch-up; widget handoff delay; late ready после abort; смена generation/двойной mount/повторный Resume; несколько/чужие widget; duplicate module registry; readonly snapshots/privacy/getters; early/late readiness и пределы deadline; deny view+handle до любых confirm/refresh, включая disarm/destroy гонку; несовпадающий account/Live/Connect/version; неопределённый create/read/cleanup; попытка вызвать полный acceptance/refund/extras. Внешние fetch/Stripe/process границы в офлайн-тестах синтетические; реальная цепочка SDK/runner/formatter остаётся настоящей.

План после подтверждения разложит это на четыре последовательных шага: внутренний passive observer+deny; отдельный prepare-only runner/proofs; diagnostic-only page/driver/config+строгий reporter; интеграционная офлайн-проверка и независимое review. Точные имена вспомогательных файлов и helper extraction внутри этих границ оставлены исполнителю; новые продуктовые возможности или ослабление существующих guards не оставлены на его усмотрение.

Ни формальная готовность формы, ни WAITING_ELEMENT_READY не устанавливают точную причину проблем Stripe/network/CSP/Shadow DOM. Отсутствие события не равно доказанному timeout/provider outage. Предыдущие R37 исход/неподтверждённый общий cleanup не переписываются. Реализация/коммит/публикация дизайна не дают права на Sandbox-попытку.

Официальный контракт ready: [Stripe Element ready event](https://docs.stripe.com/js/element/events/on_ready). Безопасность/CSP: [Stripe integration security guide](https://docs.stripe.com/security/guide). Эти ссылки — справочные контракты, не проверка текущего браузера.

Создание отдельно от подтверждения: [Stripe Create PaymentIntent](https://docs.stripe.com/api/payment_intents/create). Подготовка обычного checkout в проекте уже не передаёт confirm/payment_method/customer; диагностический guard проверяет эту границу тестами, не меняет её на фиктивную оплату.
