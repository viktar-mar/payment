# Конечные признаки окружения для диагностики Stripe-формы

Статус: **предложение на согласование**, подготовлен только план. Пользователь разрешил подготовку узкого плана без Sandbox; это не разрешение на реализацию, browser/runtime/API или новую попытку. База проекта: `17b4d08e44f7310068b1d3e390da8261ff216960`, существующая ветка `codex/stripe-sandbox-5a`.

## Цель и известная граница

Предыдущая ограниченная диагностика наблюдала завершённые capability/loader/create/mount, но не `ready`/`loaderror`; initial catch-up ещё не начинался. Это локализует ожидание, но не определяет причину. Последующий AT_LIMIT был кэшированным OPERATION_FAILED, не новым доказательством pending на 60-й секунде. Исправление cleanup в `17b4d08` не изменяет загрузку Stripe и не подтверждает cleanup исторической попытки.

Нужно дополнить следующую отдельно разрешённую диагностику конечными признаками двух классов: зарегистрированные CSP-нарушения собственного документа и наблюдавшиеся ошибки ресурсов единственной принадлежащей сценарию страницы. Результат должен отличать положительное наблюдение от отсутствия доступных сведений, не притворяясь точным диагнозом Stripe.

## Выбор подхода и границы

Это мой инженерный выбор для согласования: два изолированных сборщика без изменения SDK/backend/CSP и версия 2 публичного диагностического отчёта. Новые зависимости не нужны. Дополнительный `loaderstart` пока не нужен: ещё одна отметка этапа сама по себе не различает блокировку и ошибку загрузки. Сбор полных логов, URL, provider payload, trace/HAR и изменение timeout не рассматриваются.

Сборщики пассивны: не добавляют запросы, не перехватывают/исправляют ответы, не создают retry, DOM/iframe inspectors, payment capabilities или SDK events. Количество событий не накапливается: только OR конечных битов, постоянный размер памяти, без списков Request/Response/Event и их сохранения в замыканиях/отложенной работе.

### CSP собственного документа

Перед `goto` устанавливается самодостаточный init-script через существующий owned context. Он активен только в top-level документе точного owned loopback origin и получает исходный absolute active deadline, который проверяется до чтения события. Имеющиеся init-script для session и новый CSP-script независимы от порядка исполнения.

Единственный новый read-only глобальный объект — `__formDiagnosticCsp` с единственным методом `snapshot()`, non-writable/non-configurable, его значение и снимки заморожены. Нет публичного writer/reset/stop. `securitypolicyviolation` слушается на собственном document в capture-фазе. Разрешены только чтения `isTrusted`, `effectiveDirective`, `disposition`: synthetic/untrusted события игнорируются. Исключение/невалидный disposition делает источник UNAVAILABLE; исходное исключение не исследуется.

Группы CSP: `script-src`, `script-src-elem`, `script-src-attr` → SCRIPT=1; `frame-src`, `child-src` → FRAME=2; `connect-src` → CONNECT=4; остальные строковые effectiveDirective → OTHER=8. Раздельно накапливаются disposition `enforce` и `report`. Report-only не называется фактической блокировкой. Геттеры blockedURI/documentURI/sourceFile/referrer/originalPolicy/sample/statusCode и прочие поля не читаются.

Это не наблюдение CSP внутри cross-origin Stripe iframe и не проверка применённой политики целиком. Отсутствие события не доказывает отсутствие CSP-проблемы: событие могло относиться к другому документу или ещё не быть доставлено.

### Ресурсы принадлежащей страницы

Node-сторона подписывается только на `page.on('requestfailed')` и `page.on('response')` единственной owned page до `goto`. Разрешены только `Request.resourceType()`, `Response.status()` и `Response.request().resourceType()`. Вызовы синхронные, без await в обработчике. Любое исключение, нестроковый resourceType или нецелый HTTP status вне 100..599 делает источник UNAVAILABLE; текст исключения не читается.

Группы: script → SCRIPT=1; document → DOCUMENT=2; xhr/fetch → FETCH_XHR=4; остальные строковые типы → OTHER=8. `requestfailed` устанавливает transport-failure bit. `response` отдельно устанавливает HTTP 400..499 и HTTP 500..599 bits; 100..399 их не устанавливают. Один запрос может дать несколько наблюдений; это не счётчик уникальных запросов.

Нельзя читать url/frame/headers/body/postData/failure/errorText, response text/json, provider events, console/pageerror или содержимое iframe. Без URL/происхождения эти признаки относятся ко всей owned page, включая доступные Playwright события её подфреймов, а не к конкретному Stripe-запросу. HTTP 4xx не называется неверным ключом; requestfailed не называется DNS/TLS/CSP-причиной.

Сборщик имеет `snapshot()` и `stop()`. Обработчики проверяют исходный active/local deadline, abort и флаг stop ДО чтения event object. После stop они инертны, даже если unsubscribe не удался. Нет новой browser/page close операции. Поздний teardown-induced requestfailed не попадает в отчёт.

## Точный конечный контракт

Environment evidence — плоский frozen object ровно с восемью полями:

| Поле | Домен |
| --- | --- |
| `csp_observation` | OBSERVED / UNAVAILABLE |
| `csp_enforced_mask` | целое 0..15, биты CSP выше |
| `csp_report_only_mask` | целое 0..15, биты CSP выше |
| `resource_observation` | OBSERVED / UNAVAILABLE |
| `resource_transport_failure_mask` | целое 0..15, биты ресурсов выше |
| `resource_http_4xx_mask` | целое 0..15, биты ресурсов выше |
| `resource_http_5xx_mask` | целое 0..15, биты ресурсов выше |
| `environment_freshness` | SAMPLED / CACHED / UNAVAILABLE |

OBSERVED означает только, что сборщик установлен и этот снимок доступен; нулевые биты означают «не записано к моменту снимка», не «окружение исправно». Для UNAVAILABLE соответствующие masks равны 0. Canonical unavailable: оба источника UNAVAILABLE, masks=0, freshness=UNAVAILABLE. При хотя бы одном доступном источнике freshness=SAMPLED либо CACHED. Частичная доступность не превращается в полную.

Внутренний `FormDiagnosticSample` и SDK observer сохраняют version=1 и нынешние поля/классификацию. Новый внутренний observation: `{sample: FormDiagnosticSample|null, environment: FormEnvironmentEvidence}`. Browser result содержит `observations` вместо `samples`, остальные outcome/closure поля неизменны; родитель отвергает старую/mixed форму, лишние ключи, accessors, неконечные значения и более двух observations.

Публичный wire frame — version=2, плоские существующие stage/result/proof поля плюс восемь environment полей. В sampled frame SDK version=1 не копируется: сериализуется только envelope version=2. В opaque failure frame stage полей нет, но environment evidence есть: например, блокировка bootstrap script может оставить CSP-признак даже без widget facade. Старые/mixed version=1 wire frames текущий v2 reporter отвергает; история не переписывается и не перепарсивается. Лимиты сохраняются: максимум два frame, каждый не более 4096 UTF-8 bytes.

Обычный stage/ready результат требует SAMPLED и оба источника OBSERVED; иначе OBSERVING превращается в INSTRUMENTATION_UNAVAILABLE. Исходные FAILED/ABORTED/DEADLINE/UNEXPECTED_ACTIVITY и финансовая/cleanup неопределённость не переопределяются. Сами ненулевые masks не доказывают причину и не отменяют действительно подтверждённую readiness: вспомогательный ресурс мог дать ошибку без влияния на форму. Exit0 всё ещё требует реальную FORM_READY_* и все четыре независимых proof.

## Снимки, ошибки и исходное время

После goto один bounded page-evaluate читает текущие handshake и CSP evidence без обязательного widget snapshot. Два facade читаются независимо: отсутствующий/throwing SDK reader не скрывает доступный CSP snapshot и наоборот; исходные ошибки не исследуются. Если facade отсутствует, доступные конечные environment сведения сохраняются в opaque INSTRUMENTATION_UNAVAILABLE frame; дополнительных browser reads после этой ошибки нет. До Resume обязательны исправный handshake, оба источника OBSERVED и SAMPLED evidence; иначе нет Resume/client-session, и частичная instrumentation не создаёт дополнительный Attempt.

Обычный observation loop получает stage и CSP одним page-evaluate, затем выполняет существующий Pay visibility proof и берёт Node resource snapshot. Это составной снимок двух источников в пределах исходного окна, не атомарное межпроцессное причинное измерение. Не добавляется второй reader, меняющий readiness bucket или 60s ceiling. До Resume и при ошибках stage может быть null; это не выдуманный sample.

Node callbacks и page reads ограничены прежним deadline/abort/local window. Последний полностью валидный observation сохраняется замороженным. При FORM_LIMIT он копируется как AT_LIMIT, freshness=CACHED, с прежним FAILED provenance; новые page reads и дописывание поздних resource events запрещены. При других сбоях допустим только ранее сохранённый evidence с CACHED либо canonical unavailable, не новый post-failure сбор. Checkpoint не переписывается финальными масками. До teardown resource collector останавливается, возвращённые данные неизменны.

Уточнение после независимого builder-check: CSP realm не получает Node AbortSignal или установленный позднее local end. Мой выбор — не добавлять RPC/control channel ради остановки пассивного collector. Его приватные конечные биты могут меняться после Node abort/local end до document teardown либо исходного active deadline; они больше не читаются и не публикуются. Поэтому остановка Node-сборщика/наблюдения не выдаётся за мгновенную остановку CSP listener или внешних операций. Тест обязан показать неизменность сохранённого отчёта при таких поздних CSP событиях; запреты на raw data/запросы сохраняются.

Остаются исходные 15 минут active, 15s checkpoint, 60s observation и исходный active deadline+120000ms cleanup. Сохраняется context-first grace <=5000ms с резервом 10000ms и pre-dispatch isClosed guard из `17b4d08`. Никакие сведения не возобновляют время или отменяют uncertainty.

## Полномочия и проверки

Сейчас только документы. Последующая реализация потребует подтверждения этого дизайна/плана; тесты будут офлайн, внешние event/process/Stripe границы синтетические, реальные collector/driver/parent/schema/reporter выполняются. Нужны meaningful RED→GREEN, связанные regression/typechecks, проверка сериализации init-script без module closures и независимое review после каждой из двух задач. Новая instrumentation не является доказательством реального browser поведения до отдельной проверки.

В обоих будущих implementation tasks запрещены actual browser, Sandbox/API/CLI/OAuth, Docker/appstack, чтение private keys/env/JWK/mail/OTP/runtime logs, порты/run UUID/allowance, удаление/переиспользование ресурсов и платежи. Не меняются SDK/backend, nginx/CSP, selectors, Stripe ready timeout, provider binding, финансовые proof, default/full/Fake режимы. UI/SDK не получают новые события/exports.

Возможный будущий запуск — отдельное разрешение на одну прежнюю prepare-only попытку: без карты, Pay/confirm/retry/refund/OTP, без CLI login/listener. Она всё ещё требует свежего неподтверждённого EUR20 Intent и прежних ограниченных Product/Price/Checkout/Attempt; «не платить» не означает отсутствие provider writes. Утверждение документов/коммит не разрешают такую попытку. После неясного результата — отчёт/manual check, не автоматический повтор. При всех нулевых masks причина остаётся UNKNOWN, не делается вывод «проблема внутри Stripe» и не ослабляется CSP.

## Проверенные публичные контракты

Проверено 2026-10-19 только по публичной документации, без Stripe.js/provider вызовов:

- [W3C CSP violation DOM events](https://www.w3.org/TR/CSP3/#violation-events): отдельные effectiveDirective/disposition позволяют проектировать конечную категорию, не собирать URI/policy/sample; document scope и асинхронная доставка ограничивают выводы.
- [Playwright requestfailed](https://playwright.dev/docs/api/class-page#page-event-requestfailed) и [response](https://playwright.dev/docs/api/class-page#page-event-response): HTTP 4xx/5xx не равны transport failure; нужны раздельные признаки.
- [Playwright resourceType](https://playwright.dev/docs/api/class-request#request-resource-type) и [response.status](https://playwright.dev/docs/api/class-response#response-status): используются только тип ресурса и категория статуса.
- [Playwright addInitScript](https://playwright.dev/docs/api/class-page#page-add-init-script): установка до document scripts; порядок нескольких init scripts не гарантирован, поэтому они независимы.
- [Stripe ready](https://docs.stripe.com/js/element/events/on_ready): iframe presence и признаки окружения не заменяют ready/реальную доступность Pay.

Интерпретация категорий и выбор masks — проектные решения, не утверждение документации о причине исторического сбоя. Контекст и прежние safety границы: [ограниченный режим](2026-10-18-stripe-form-load-diagnostic-design.md), [план реализации](../plans/2026-10-19-stripe-form-environment-evidence-implementation.md).
