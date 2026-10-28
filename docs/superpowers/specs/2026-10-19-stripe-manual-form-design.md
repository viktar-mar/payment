# Ручная загрузка Stripe-формы в обычном браузере

## Статус и подтверждённая граница

Пользователь подтвердил подготовку отдельного ручного режима с тестами и независимым ревью. Сейчас разрешена только офлайн-реализация: без запуска Sandbox, Docker, Stripe API/CLI, браузера, чтения приватных ключей/env и прежних runtime-файлов. Фактическая ручная попытка потребует отдельного разрешения после реализации. Ни документ, ни тесты, ни коммит не разрешают запуск.

Согласовано в чате: одноразовая локальная страница, без токенов в ссылке/чате; одна загрузка формы, без ввода карты и подтверждения; технический deny-confirmation; наблюдение до60 секунд, затем финансовая проверка и остановка owned стека. Пользователь открывает ссылку в обычном браузере и один раз нажимает «Загрузить форму». Повтор/reload/вторая вкладка не создают новую попытку.

Ниже — технические решения исполнителя для этой границы, а не дополнительные пожелания пользователя. Основная причина прежнего missing-ready остаётся UNKNOWN; ручной режим не считается её исправлением или приёмкой5A.

## Архитектура и происхождение сессии

Существующий diagnostic demo требует identity+recovered Checkout из Playwright addInitScript. В ручном режиме Playwright/CDP/расширение/DevTools/clipboard не используются. Отдельный Node gateway слушает только `127.0.0.1` на седьмом свежем порту, отличном от всех шести owned Docker ports. Это browser origin ручного Site. Его asset upstream — только текущий freshly-owned Docker demo, не произвольный URL.

Gateway отдаёт отдельный manual HTML и его собранные JS/CSS assets из текущего diagnostic demo. Никакой общий reverse proxy, filesystem/static-root endpoint, admin API или forwarding Authorization/Cookie не добавляется. Политики CSP/Referrer-Policy/X-Frame-Options не ослабляются; HTML сохраняет текущую demo security policy, API loopback разрешение уже существует. Ручной entry собирается рядом с автоматическим только в `form-diagnostic`; обычная production-сборка не содержит ни manual bootstrap, ни его маршрутов.

Новый manual runtime переиспользует текущие bootstrap/provider/provisioning проверки. Сам bootstrap продолжает владеть исходными Docker demo/backend origins. Только отдельный manual provisioner регистрирует gateway origin как Site allowed origin и origin фиксированного return URL; public provisioning requests используют тот же origin. Подмена demo endpoint в ownership records запрещена. Автоматический provisioner/runtime сохраняют прежние контракты.

Свежая fixture: прежний anonymous EUR20 ONE_TIME/NONE Checkout, один неоплаченный Intent, максимум один Product/Price и первый client-session Attempt, без Customer/Donation/Subscription/OTP/Portal. Никакого переиспользования предыдущих ресурсов. Ключи остаются в существующей private runtime boundary; gateway получает только текущий Checkout session, не Stripe secret/admin credentials.

## Допуск и одноразовый HTTP-протокол

Только точные `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC=true`, `PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY=true`. Автоматический FORM_DIAGNOSTIC/full/owned-run/allow-test-operations, DEBUG/NODE_DEBUG/PWDEBUG/PW_TRACE несовместимы с ручным входом и отклоняются до private/dependency access. Старые публичные входы также отвергают manual flag. Внутренний reuse прежнего bootstrap получает только его собственные уже проверенные flags.

До будущих writes проверяются source SHA/чистота, local daemon, наличие седьмого свободного loopback port; bind всегда exclusive. Ошибка/гонка bind не разрешает заменять порт или продолжать. URL страницы постоянный `/form-load-manual.html`, без query/hash/capability. Gateway сохраняет session только в памяти и не логирует запросы/ответы/ошибки.

- Первый точный GET документа выдаёт один случайный256-bit `HttpOnly; SameSite=Strict; Path=/` cookie и одноразовую страницу. Второй GET документа, reload/другая вкладка —410; не переиздаёт session/cookie и не создаёт ресурсы. Favicon возвращает204 без изменения состояния.
- Статические GET разрешены только для `/assets/<basename>.js` и `.css`, без path traversal/encoding/query. Upstream только фиксированный current demo origin, GET, без редиректов/входных credentials; response не более2MiB и до10 секунд в пределах original deadline. Никакие другие upstream paths не доступны. Для всех responses `Cache-Control: no-store`.
- По нажатию кнопки ровно один POST `/manual-session` с JSON `{}` требует exact Host, exact Origin, cookie и ограниченный body32bytes. Cookie/Origin не заменяются CORS wildcard. DNS rebinding/foreign Host, cross-origin/malformed requests отклоняются без выдачи сессии. Cookie не отражается в body/отчёте. Consumption latch ставится до любого async response; неоднозначная доставка не даёт retry.
- Browser получает session в JSON body, пишет только собственный recovery storage key и создаёт настоящий widget. Токены не попадают в URL, DOM text, console, отчёты, commands или localStorage. Response явно no-store; session JSON выдаётся единожды, server reference стирается после consumption/close.
- POST `/manual-observation` принимает максимум два validated finite sample: pending checkpoint после15с и terminal/limit report. Exact Host/Origin/cookie обязательны, body до4096bytes, неизвестные/лишние поля/late reports отвергаются; сырые ошибки/URL/iframe contents не принимаются. Это сообщения страницы, а не независимые browser proofs.

## Настоящая форма и время

Manual entry импортирует реальный SDK+observer из того же diagnostic Vite graph. Сначала latch единственный Start, затем session exchange, recovery, arm fresh widget до подключения, обычный recovery read и один настоящий Resume через widget UI. Не вызываются приватные SDK mount/confirm методы и не подменяются Stripe.js/events/responses. До единственного Resume host уже подключён и не скрыт ожиданием ready. Автоматического повторного Resume, client-session, reload/remount нет.

Надпись: «Ручная проверка — без оплаты». Кнопка: «Загрузить форму». Предупреждение: «Не вводите карту и не нажимайте Pay. Не обновляйте страницу». После согласованной готовности SDK показывается «Форма сообщила о готовности. Диагностический снимок отражает этот момент и больше не обновляется. Проверьте, видны ли поля сейчас; ничего не вводите». Снимок относится к первому согласованному ready-отчёту после provider event и завершения SDK view/widget handoff, а не обязательно к точному моменту исходного Stripe event. После него наблюдатели закрыты: поздняя видимость не переписывает сохранённую категорию и не создаёт новое наблюдение. Надпись не утверждает, что POST принят сервером. Реальные view/handle сохраняют deny-confirmation, даже если Pay появился или observer закрыт. Это не обещание отключить ввод внутри чужого iframe: пользователь ничего не вводит.

Один original active deadline15 минут от будущего CLI entry; cleanup ceiling тот же deadline+2 минуты. Старт ручной загрузки запрещён позднее active deadline−120с: сохраняется время на максимум60с наблюдения и финансовый read. Gateway перестаёт принимать новые действия на min(grant time+60с, active deadline−60с), независимо от browser timers/вкладки. Пока человек не начал, истечение admission cutoff завершает ожидание без session handoff.

Клиент отсчитывает60с до первого POST, а не после отложенного ответа. Поздний bootstrap response не может начать Resume. Наблюдение/cleanup client side best-effort: throttled/background browser не подчиняется серверному timer; отчёт не обещает принудительное закрытие iframe или обычного браузера. После60с клиент удаляет owned widget, очищает только свой storage key, отключает handlers/timers и не принимает late events. При раннем SDK ready виджет остаётся подключённым до исходного60с предела для человеческой проверки; это не обещание видимости полей. Повторных операций нет. После проверки человек закрывает только эту вкладку.

## Финансовые проверки и отчёт

Сервер закрывает gateway для новых действий до единственного final provider/backend proof и owned cleanup. Read выполняется только под исходным binding/deadline; новый deadline, retry/reconcile запрещены. Manual final proof допускает0 или1 первый REQUIRES_PAYMENT_METHOD Attempt:0 возможно, если человек не начал/страница упала до client-session. Второй Attempt, charge/payment method/customer, success/refund/extra Checkout или unknown provider state проваливают proof. Старый автоматический final proof по-прежнему требует1 Attempt. Baseline по-прежнему0.

Отдельный exact JSON report version1, `mode=MANUAL_FORM_DIAGNOSTIC`, не старый wire-v3. Поля: `observation` из `NOT_STARTED|READY_REPORTED|PENDING_REPORTED|TERMINAL_REPORTED|NO_REPORT|INVALID_REPORT`; `end` из `READY_REPORTED|LOCAL_LIMIT|NOT_STARTED|ABORTED|ACTIVE_DEADLINE|FAILED`; `sample` — validated FormDiagnosticSample либо null; `no_confirmation`, `gateway_closed`, `runtime_closed` — booleans; `browser_closure=NOT_OWNED`; `provenance=BROWSER_REPORTED`. Envelope до4096bytes. Readiness никогда не выводится из одного iframe. Ready report требует согласованного actual SDK sample и visible+enabled Pay, но остаётся browser-reported, не финансовой/независимой проверкой. Поздний/неполный sample не повышает итог.

Нет browser_closed/context_closed=true, accepted/paymentConfirmed, имитации automated environment/network evidence или автоматической приёмки5A. Human statement «поля видны/не видны» остаётся отдельным сообщением пользователя. Console/DevTools/Network/HAR/screenshot/trace/iframe payload не собираются. Финансовая/cleanup неопределённость всегда exit1; exit0 допускается только для valid READY_REPORTED с тремя положительными owned proofs и означает только завершённый ограниченный эксперимент.

Gateway owned sockets/responses/таймеры завершаются в original cleanup ceiling. Поздний listener acquisition остаётся owned и закрывается; abort/close не означает settlement. Останавливаются только текущие verified owned контейнеры; сеть/тома/данные/provider objects сохраняются. Внешним браузером процесс не владеет и не закрывает его целиком. При ошибке нет нового запуска/автоочистки.

## Офлайн-доказательства и дальнейший запуск

Нужны meaningful RED/GREEN, adversarial HTTP tests с синтетическими listen/fetch/timer boundaries, actual built-ESM jsdom tests настоящей SDK цепочки, mode/default-discovery/build isolation, SDK/demo types, полная SDK suite, связанные provisioning/diagnostic regressions и независимое review каждой задачи + итогового изменения. Новых зависимостей/Java/API/payment behavior не требуется. Реальный listener/browser/Stripe/Docker в офлайн-тестах не запускается.

Только после реализации, отчёта и нового явного разрешения можно запускать одну fresh manual fixture и выдавать человеку loopback URL с ограниченным временем ожидания. При текущем запрете Sandbox остаётся выключенным.
