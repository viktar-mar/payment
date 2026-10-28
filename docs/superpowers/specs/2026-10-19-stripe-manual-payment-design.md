# Одна ручная тестовая оплата в Stripe Sandbox

Статус: пользователь подтвердил описанный в чате объём — отдельный режим, одна оплата EUR20 тестовой картой в обычном Chrome, тесты и независимое ревью, затем одна Sandbox-попытка. Этот документ уточняет реализацию для проверки вместе с планом. До согласования плана и прохождения его проверок запуск не выполняется.

## Цель и границы

Пользователь открывает выданный локальный URL в отдельном Chrome, загружает один свежий Checkout, вводит только официальную тестовую карту Stripe и один раз нажимает Pay. Итог устанавливают серверные чтения, а не браузерное сообщение. Проверяются один успешный PaymentIntent в Sandbox, соответствующий входящий webhook, единственный денежный факт в журнале и его подписанный callback.

Это не полный Stage5A acceptance. Не выполняются decline/retry/3DS-сценарии, донаты, Customer/OTP-вход, подписки, возвраты, missed-webhook/reconcile, дополнительные checkout/Intent, production activation или live операции. Нет автоматического повторного подтверждения даже после ошибки валидации карты, потери ответа, закрытия страницы или падения процесса. Служебная EMAIL_OTP-настройка Site допускается только как прежний readiness scaffold; письма и Customer не создаются.

Существующие form-only маршруты и их неизменяемый запрет подтверждения остаются без ослабления. Обычные Fake и production-сборка не получают новый экран или включённый режим. Полный owned acceptance остаётся отдельным сценарием с прежними проверками.

## Подход

Новый публичный локальный entrypoint `infrastructure/stripe-manual-payment.mjs` и отдельная страница `/manual-payment.html`. Новая runtime capability переиспользует ownership/bootstrap и изолированный Stripe CLI, но не вызывает `openOwnedSandboxAcceptance`, полный provisioner или refund runner. Отдельный named bootstrap factory выбирает только build mode `manual-payment`, оставляя обязательную настройку живого listener перед запуском backend.

Минимальное single-checkout provisioning — узкий третий вариант существующего form provisioner, а не ослабление `verifyNoConfirmation`. Он создаёт только прежние Account/Site/Product/Price плюс один анонимный CATALOG Checkout, настраивает один callback receiver и фиксирует исходный PAYMENT_PREPARE/Intent. Отдельные методы подтверждают baseline, отмечают разрешение и читают результат. Никакой general-purpose команды Stripe, HTTP proxy или открытого Admin API в шлюзе нет.

## Допуск и секреты

Нужны точные `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true`, `PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT=true`. FULL_RUN/OWNED_RUN/FORM_DIAGNOSTIC/MANUAL_FORM_DIAGNOSTIC/ALLOW_PREPARE_ONLY несовместимы. DEBUG/NODE_DEBUG/PWDEBUG/PW_TRACE отключены. Отказ происходит до private/dependency access. Старые публичные входы отклоняют новый флаг, а внутренние вызовы получают только нормализованные доверенные флаги после допуска.

Используются существующая recovery worktree, чистый reviewed SHA, свежий lowercase UUID, прежний canonical local Unix Docker daemon и семь различных свободных loopback ports (1024–65535). Чужие/сохранённые fixtures не переиспользуются. Preflight не читает ключей; bootstrap повторно проверяет source/ownership/ports непосредственно перед действиями. Нехватка диска/сети/портов останавливает попытку без автоматического удаления или смены порта.

Привязка остаётся `acct_1UPiMpELswAd8tpR`, API `2026-09-30.endive`, CLI `1.52.0`. CLI whoami должен подтверждать только test-доступ; backend при создании Account проверяет тот же внешний аккаунт. Каждый Intent/Charge/Event должен иметь `livemode=false`; mixed keys, чужой аккаунт или другая API version — stop. Секретные значения никогда не входят в argv, URL, отчёт или Git. Игнорируемая локальная конфигурация читается прежней private boundary. Webhook secret сохраняется существующим защищённым callback bootstrap; конфликт не разрешает его очистку/ротацию.

Авторизация CLI выполняется через существующие строго проверенные isolated descriptors, без Keychain, глобального HOME/Docker config или наследования окружения. Человек получает только подлинные разрешённые HTTPS URL и device code. Если они истекли, новая авторизация/попытка автоматически не начинается. Listener использует прежний фиксированный account-key endpoint и event filter, без `--live`, `--latest`, смены API version или Dashboard-настройки endpoint.

## Одна выдача и одно подтверждение

Loopback gateway отдаёт только свой одноразовый HTML, manifest-allowlisted собранные assets, фиксированный return document и собственные узкие POST. Сохраняются CSP, no-referrer, no-store и прежние cookie/Host/Origin-защиты. Токен Checkout выдаётся один раз через защищённый same-origin POST `{}` и остаётся только в памяти страницы. Нет query/hash/token storage, общего static root, CORS wildcard, forwarding Authorization/Cookie или доступа к private файлам.

Отдельный внутренний SDK lease arm-ится для одного ещё не подключённого Catalog widget. Он связан с текущими widget/providerHost/generation и не меняет публичный SDK API. Непосредственно перед единственным `stripe.confirmPayment` SDK сначала сохраняет неизменяемый локальный consumed latch, затем ожидает один same-origin запрос разрешения. Шлюз до ответа сохраняет durable `CONFIRMING` для исходных payment/operation/Intent и возвращает разрешение только после проверенного baseline. Только после успешного ответа и повторной проверки lease/срока SDK может вызвать Stripe.

Второй click/Enter/programmatic confirm, concurrent вызов, повторная выдача, потерянный/невалидный ответ, remount, host replacement, expiry или teardown не допускают второго вызова. Даже rejected/aborted validation расходует единственное разрешение. Закрытие lease не убирает deny с ранее выданного handle. Старый form-only deny проверяется прежде новой возможности и всегда имеет приоритет; mixed arming отклоняется. Обычный невооружённый widget сохраняет прежнее поведение. Это guard диагностического режима, а не защита от произвольного кода того же OS-пользователя.

Durable ledger использует owner-only 0700/0600, O_EXCL/O_NOFOLLOW, исходные inode/owner/identity и fsync/atomic rename по существующему образцу. DISPATCHING перед каждым provisioning write; `CONFIRMING` до выдачи permit. Неизвестный исход навсегда оставляет PENDING/неразрешённый повтор, а не новое имя/ключ/Intent. При отказе записи permit не выдаётся. Ручное восстановление старой попытки вне объёма.

## Время и страница

Решение исполнителя: одна исходная активная граница45min, как у существующего owned bootstrap (build и CLI human authorization входят в неё); cleanup только до исходной границы+2min. Прежние per-child login3min/complete10min и проверка callback+actual close с grace10s сохраняются, но не продлевают исходную границу. Новая страница даёт до5min от выдачи сессии, обрезанных Checkout expiry и исходной границей−90s. Новую сессию нельзя выдать, если не остаётся полных5min плюс90s для проверки. Серверное ожидание результата — максимум45s/30 probes, каждый HTTP≤10s, всё внутри исходного бюджета. Интервалы чтения допустимы; это не финансовые повторы.

Страница явно говорит: «Только Stripe Sandbox. Один тестовый платёж €20. Реальную карту не вводите». Человек один раз нажимает «Загрузить форму», вводит `4242 4242 4242 4242`, будущую дату, любой трёхзначный CVC и нажимает Pay один раз. Никакой браузерной автоматизации, чтения PAN/CVC/iframe contents, автозаполнения, screenshots/HAR/trace/console или сбора PII. Ошибка означает stop и сообщение оператору, не исправление карты с повторным Pay.

На терминальном исходе/expiry/page close экран не даёт повторного Resume/Pay. Успех SDK подписывается как браузерное сообщение; окончательный результат подтверждает оператор по серверному отчёту. Gateway не считает закрытие внешнего Chrome своей доказанной операцией. Фиксированный return document обрабатывается с существующим подавлением private query до любого request logging; не логирует и не переправляет client secret.

## Серверные доказательства

До разрешения — exact Account/Site/Price/source/EUR2000/NONE, ровно один Payment на Site, прежний ACKNOWLEDGED PAYMENT_PREPARE и тот же Intent, одна попытка №1 с CARD, нет success/refund/Customer/Donation/Subscription. Baseline Intent test-mode, requires_payment_method, amount_received0, payment_method/latest_charge/customer отсутствуют. Подтверждение произвольного похожего Intent по metadata запрещено.

После Pay — защищённый Admin payment detail/list и provider operations должны показывать тот же объект, `SUCCEEDED`, EUR2000, нулевые refunds, ровно один исходный attempt/Intent. Единственный PAYMENT_SUCCEEDED имеет `source=PROVIDER_EVENT`, непустой Stripe provider_event_id и тот же provider_object_id; RECONCILIATION или одно browser succeeded не доказывают входящий webhook.

Отдельная read-only CLI capability, привязанная к baseline до permit, читает только этот Intent и этот Stripe Event. Проверяются succeeded, amount/amount_received2000, eur, automatic capture, CARD, соответствующий captured/paid succeeded Charge, отсутствие Customer/возврата, `livemode=false`, account/API binding, event type payment_intent.succeeded и event.data.object.id исходного Intent. Не вызывается старый missed-webhook inspector, требующий остановленного listener. Совпадение journal UUID с outbox UUID, DELIVERED/successful delivery и signature-verified receiver inspection подтверждает callback. Дубликаты транспорта допустимы, дубликаты денежного факта — нет.

Все эти проверки используют уже существующие HTTP-проекции, не SQL и не новые backend routes. Если reconciliation успела записать единственный success раньше webhook, результат PENDING: не выдавать это за доказанную webhook-доставку и не исправлять журнал. Нет fake events/reconcile/refund/cancel/delete для получения зелёного результата.

## Отчёт и завершение

Вывод содержит только проверенную auth-handoff строку при необходимости, фактический фиксированный loopback URL и один finite JSON≤4096bytes. Схема version1/mode MANUAL_PAYMENT: `result` (SUCCEEDED/NOT_STARTED/FAILED/PENDING), `phase` (фиксированная стадия), `confirmation_granted`, `payment_confirmed`, `provider_confirmed`, `webhook_verified`, `journal_verified`, `callback_verified`, `gateway_closed`, `listener_closed`, `runtime_closed`, `browser_closure=NOT_OWNED`, `provenance=CONTROLLER_REPORTED`. Только SUCCEEDED со всеми confirmation/proof/cleanup booleans true и exit0 означает успешный ограниченный эксперимент. False в proof означает «не подтверждено», а не «доказанно не произошло».

В finally независимо закрываются gateway и его работы, provisioner, CLI listener/children, затем только freshly-verified owned runtime containers. Финансовые чтения заканчиваются внутри исходной active authority; cleanup не даёт права на дополнительные reads/writes/confirm. На ошибке/неопределённости exit1 и сохранённые исходные данные. Нет prune/down -v/provider deletion, удаления контейнеров/сетей/томов/истории или автоматического повторного запуска.

## Проверка до запуска

На каждой задаче нужны meaningful RED→GREEN, связанные regression-тесты и независимое ревью. Финальная офлайн-проверка включает инфраструктурный suite, SDK suite/typecheck/build, demo support/typecheck/build и настоящее обычное Playwright discovery `--list` без выполнения. Synthetic Chromium допускается только с полным запретом внешней сети. Сборка из staged Docker COPY должна доказать отсутствие private infrastructure и наличие всех нужных browser modules. Maven не нужен, если Java/API/DB не меняются; иначе отдельный backend gate.

До реализации план проверяется пользователем. После всех gates пользовательское подтверждение из чата разрешает одну свежую попытку без автоматических повторов; при изменении указанного объёма требуется новое согласование. Никакой текущий документ не является выполненной оплатой или готовой ссылкой.

## Первичные источники

- Stripe [testing](https://docs.stripe.com/testing): интерактивные тестовые карты требуют test API keys; реальные реквизиты не используются.
- Stripe [webhooks](https://docs.stripe.com/webhooks): подпись события проверяется на исходном body; client state не заменяет серверный факт.
- Stripe [CLI listen](https://docs.stripe.com/cli/listen): локальное перенаправление и signing secret, default API version и явно отдельный live flag. Источник не заменяет runtime-проверку закреплённой версии.
