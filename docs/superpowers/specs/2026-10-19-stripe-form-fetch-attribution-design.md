# Безопасные категории FETCH/XHR и причина окончания наблюдения

Статус: пользователь подтвердил офлайн-расширение диагностики с тестами и независимым review. Независимый builder-check: «nothing important left»; root проверил покрытие и интерфейсы плана. Этот addendum фиксирует инженерные решения для плана; перед реализацией ожидается review/подтверждение плана пользователем. Реализация и новый Sandbox-запуск ещё не выполнены. Baseline: `59c795fbed441240db0c1e566c8decc797c97e09`, существующая linked worktree и ветка `codex/stripe-sandbox-5a`. Primary `main` не меняется.

## Зачем и что не является диагнозом

Последняя отдельно разрешённая prepare-only попытка записала SAMPLED checkpoint: capability/loader/create/mount завершены, iframe присутствует, ready/loaderror не наблюдались, `catchup=NOT_STARTED`; transport mask=4, HTTP4xx/5xx и CSP masks=0. Итог — CACHED `OPERATION_FAILED` с четырьмя положительными proof. Это не доказывает ни ошибку Stripe, ни точную причину завершения наблюдения. Существующий driver одинаково классифицирует истечение local window и непрозрачный browser-operation failure.

Офлайн-проба показала, что отменённый локальный fetch, синтетический DNS failure Stripe API и общий сбой другого адресата дают одинаковую mask4. Нужны две связанные категории одного наблюдавшегося FETCH/XHR failure и отдельная причина завершения наблюдения. Никаких исправлений оплаты по предположению.

Это дополнение к [ограниченному режиму](2026-10-18-stripe-form-load-diagnostic-design.md) и [environment evidence v2](2026-10-19-stripe-form-environment-evidence-design.md). Только следующие точечные положения прежнего дизайна заменяются: разрешённые metadata reads для активного `requestfailed` типа fetch/xhr; два дополнительных resource scalar; обязательная причина окончания наблюдения; wire v3. Все остальные ограничения сохраняются.

## Границы полномочий

- Только офлайн-код, синтетические тесты и review; новые зависимости не нужны.
- Нельзя запускать actual browser, Sandbox/API/CLI/OAuth, Docker/appstack, выдавать run UUID/порты/allowance, читать private env/keys/JWK/mail/OTP/runtime logs, удалять/adopt/replay ресурсы. Ранее сохранённый конечный отчёт не переписывается.
- Не меняются SDK/backend, nginx/CSP, selectors, интервалы/timeout, provider binding, financial/no-confirmation proof, context/browser/runtime cleanup, default/full/Fake режимы. Нет новых SDK exports/events.
- Не записывать raw URL, query, path, fragment, origin/hostname, errorText, headers/body/postData, provider payload, console/pageerror, DOM/iframe contents, trace/HAR/screenshot/video. Нет сетевых запросов, retries, response interception или новых browser capabilities.
- Исходные границы: 15 минут active, checkpoint15s, observation60s, cleanup до original active deadline+120000ms, context grace<=5000ms и reserve10000ms. Конечные категории не возобновляют время.
- Максимум два frame, каждый<=4096 UTF-8 bytes. Exit0 по-прежнему требует реальный FORM_READY_* и все четыре независимых proof; новый metadata не заменяет их.

## Связанные категории одного FETCH/XHR failure

Существующий Node resource observer остаётся единственным сборщиком. Только внутри активного `requestfailed`, после успешного строкового `resourceType()` равного `fetch` или `xhr`, разрешены однократные синхронные `request.url()` и `request.failure()`. Оба вызова изолированы: отказ одного не скрывает категорию другого. Никаких frame/request headers/body reads. HTTP response обработчик и script/document/other requestfailed не читают новые metadata.

Полный URL и failure object допустимы только как транзитные входы синхронных классификаторов; они не сохраняются в состоянии, списках, callbacks, исключениях/логах или возвращаемых данных. В `failure()` разрешено прочитать только own enumerable data property `errorText` у plain/null-prototype record ровно с этим ключом. Accessors, лишние/hidden/symbol keys, иные prototypes и любые ошибки дают UNKNOWN без обхода thrown value. Строка errorText ограничена128 символами и сравнивается целиком; нет trim/substring/regex extraction или копирования неизвестного текста в результат.

Сохраняется **последняя пара** категорий одного failed FETCH/XHR event до данного снимка; вычислить обе категории локально, затем заменить пару атомарно. Это не две независимые OR-маски, не список/счётчик и не утверждение о единственном запросе или причине отсутствия ready. Поздний failure может заменить пару в следующем снимке, но не изменить уже frozen checkpoint. Событие может относиться к загрузке страницы до Resume или к её подфрейму; принадлежность iframe и причинная связь не устанавливаются.

### `fetch_failure_destination`

Observed домен: `OWN_APP | STRIPE_API | STRIPE_JS | OTHER | UNKNOWN`. Sentinel значения: `NONE | UNAVAILABLE`.

- `OWN_APP`: URL origin точно равен одному из двух проверенных owned origins — diagnostic demo endpoint и backend `handoff.session.apiBaseUrl`. Оба должны быть canonical `http://127.0.0.1:<port>`, без credentials/path/query/fragment; порт1024..65535. Driver проверяет их до launch, затем передаёт observer frozen список. Не использовать общий wildcard localhost/loopback.
- `STRIPE_API`: HTTPS, hostname ровно `api.stripe.com`, default443.
- `STRIPE_JS`: HTTPS, hostname ровно `js.stripe.com` либо с непустым subdomain на границе `.js.stripe.com`, default443.
- `OTHER`: иной валидный HTTP(S) URL без credentials. Это не утверждение, что адрес не принадлежит Stripe; просто он вне конкретного списка.
- `UNKNOWN`: нестроковый/пустой/длиннее8192 URL, строка с ASCII control/space U+0000..U+0020 или U+007F, malformed/non-HTTP(S), credentials, отказ чтения/классификации либо невалидные owned origins. Никаких нормализаций вручную, удаления точек, декодирования IDN/URL или DNS lookup; использовать стандартный URL parser и точные сравнения.

Классификатор не читает query/path/fragment: эти части не используются ни для выбора класса, ни для вывода. `api.stripe.com.evil`, `eviljs.stripe.com` и нестандартный порт не должны попасть в Stripe-класс. Имена классов — фиксированные литералы, не возвращённая часть URL.

### `fetch_failure_code`

Observed домен: `ABORTED | NAME_NOT_RESOLVED | CONNECTION_REFUSED | CONNECTION_RESET | TIMED_OUT | CERTIFICATE | BLOCKED_BY_CLIENT | GENERIC_FAILED | UNKNOWN`. Sentinels: `NONE | UNAVAILABLE`.

Exact allowlist (входные строки начинаются с `net::ERR_`):

| Суффикс входной строки | Выходной литерал |
| --- | --- |
| ABORTED | ABORTED |
| NAME_NOT_RESOLVED | NAME_NOT_RESOLVED |
| CONNECTION_REFUSED | CONNECTION_REFUSED |
| CONNECTION_RESET | CONNECTION_RESET |
| TIMED_OUT, CONNECTION_TIMED_OUT | TIMED_OUT |
| CERT_AUTHORITY_INVALID, CERT_COMMON_NAME_INVALID, CERT_DATE_INVALID | CERTIFICATE |
| BLOCKED_BY_CLIENT | BLOCKED_BY_CLIENT |
| FAILED | GENERIC_FAILED |

Всё остальное, включая null, неверный record, другие browser dialects, текст с URL/секретом или добавленным prefix/suffix, — UNKNOWN. GENERIC_FAILED не называется CORS/CSP/неверным ключом. Коды — наблюдение Chromium, не установленная корневая причина и не проверка publishable-key/account pairing.

### Доступность и masks

Resource evidence расширяется с4 до6 scalar полей, полное environment — с8 до10. Старые поля/masks/freshness сохраняют значение:

- OBSERVED и transport mask без FETCH_XHR bit4: пара строго `NONE/NONE`.
- OBSERVED и transport mask с bit4: оба значения из observed доменов выше, независимо допустим UNKNOWN; NONE/UNAVAILABLE запрещены.
- UNAVAILABLE: masks=0 и пара строго `UNAVAILABLE/UNAVAILABLE`.

Ошибка нового metadata read даёт UNKNOWN только соответствующей категории, но не обнуляет уже наблюдавшийся transport bit и не делает старый source недоступным. Старые ошибки `resourceType/status` и регистрации по-прежнему защёлкивают весь source в UNAVAILABLE, сбрасывая masks и пару. Нули не доказывают исправность сети. Stop/abort/deadline/local-end guard выполняется до **любого** чтения event metadata; после него не вызываются даже url/failure.

## `observation_end` независимо от результата и cleanup

Wire v3 добавляет обязательный scalar с доменом:

| Литерал | Что именно известно |
| --- | --- |
| OBSERVING | Исторический pending checkpoint; наблюдение тогда не закончено |
| FORM_READY | Наблюдение завершилось реальным ready+visible/enabled Pay proof |
| SDK_TERMINAL | Loop завершён по наблюдавшемуся terminal SDK/stage состоянию |
| LOCAL_LIMIT | Истекло исходное60s local window, включая settlement точно на границе |
| ABORTED | AbortSignal остановил browser observation |
| ACTIVE_DEADLINE | Истёк original active deadline |
| INSTRUMENTATION_UNAVAILABLE | Недоступны/некорректны необходимые instrumentation/result данные |
| BROWSER_OPERATION_FAILED | Непрозрачный сбой browser acquisition/action/read; текст не исследуется |
| BROWSER_NOT_STARTED | Parent не вызывал browser observation (например prepare failure) |

`FormLoadBrowserResult` и dependency result получают обязательное поле `observationEnd` (camelCase); `observations/outcome/browserClosed/contextClosed` остаются. Это результат завершения наблюдения, а не самостоятельный operational error и не доказательство остановки внешних запросов. Изначально parent ставит BROWSER_NOT_STARTED; перед вызовом browser переходит в BROWSER_OPERATION_FAILED как fail-closed fallback; malformed return даёт INSTRUMENTATION_UNAVAILABLE. Valid browser return должен содержать конечный литерал, не OBSERVING/BROWSER_NOT_STARTED.

Driver ставит FORM_READY при успешном terminal ready, INSTRUMENTATION_UNAVAILABLE при таком terminal classifier, SDK_TERMINAL при остальных terminal sample состояниях. В catch FORM_LIMIT/ABORTED/DEADLINE/UNAVAILABLE/остальное отображаются в LOCAL_LIMIT/ABORTED/ACTIVE_DEADLINE/INSTRUMENTATION_UNAVAILABLE/BROWSER_OPERATION_FAILED. Cleanup никогда не меняет причину уже завершённого observation: возможен FORM_READY с общим OPERATION_FAILED и отрицательным closure proof. Так же financial/deadline failure parent не переписывает историческую причину observation.

Если frame два, первый имеет OBSERVING, последний — конечную причину; pending checkpoint нельзя задним числом пометить LOCAL_LIMIT. Ready wire требует FORM_READY, обычный pending stage — OBSERVING. Failure frames допускают любой конечный литерал: их result/proof по-прежнему определяют провал. Final browser result с LOCAL_LIMIT должен иметь outcome FAILED, с ABORTED — ABORTED, с ACTIVE_DEADLINE — DEADLINE_REACHED, с BROWSER_OPERATION_FAILED — FAILED; несогласованные пары отвергаются. FORM_READY/SDK_TERMINAL допускают OBSERVING либо FAILED (cleanup uncertainty); INSTRUMENTATION_UNAVAILABLE допускает OBSERVING/INSTRUMENTATION_UNAVAILABLE/FAILED.

## Атомарный protocol upgrade

SDK sample остаётся version1, private page facade и CSP init/read не меняются. Новый wire frame — version3, flat existing stage/result/proof +10 environment fields +observation_end. Parser/reporter отвергают v1/v2/mixed, пропущенные/лишние поля, getters/hidden/symbol keys, duplicates и превышение4096bytes. Исторические артефакты v2 не мигрируют и не прогоняются через новый parser.

`buildFormDiagnosticFrame(sample,outcome,proofs,environment,observationEnd)` получает пятый обязательный аргумент; невалидная причина не может создать ready/stage: producer переводит её в INSTRUMENTATION_UNAVAILABLE, сохраняя dominant failure result, если он уже был. Форматтер/парсер отвергают невалидное или несовместимое поле. Parent проверяет exact five-key browser result и остальные прежние guards; максимум два observations сохраняется.

Новые категории входят в тот же последовательно полученный environment snapshot. Нет дополнительного page.evaluate, visibility read, измерения времени/нового reader. CACHED содержит только предыдущие конечные значения; поздние callback/read/close не делают их SAMPLED. Независимые CSP/resource источники, partial unavailable и все финансовые/cleanup gates сохраняются.

## Проверки и пределы вывода

Нужны meaningful RED/GREEN классификаторов, event collector и настоящего driver→parent→schema→reporter с синтетическими внешними границами. Обязательны ложные hostname/ports, query с sensitive sentinel, exact error whitelist и hostile descriptors, failure одного metadata read без потери другого, согласованность mask/sentinels, замена пары целиком, frozen checkpoint, LOCAL_LIMIT против browser read rejection, original active/abort boundary и независимость observation_end от cleanup/financial result.

Существующие stop/late-acquisition/preclosed/context grace/reserve/все четыре proof и normal-discovery/build-isolation тесты остаются обязательными. Offline tests не являются runtime acceptance; причина последней реальной загрузки остаётся UNKNOWN. Следующая actual prepare-only попытка, даже без оплаты, потребует отдельного разрешения на provider writes и не входит в реализацию этого addendum.

Публичные контракты проверены2026-10-19: [Playwright request.failure/url](https://playwright.dev/docs/api/class-request#request-failure) предоставляет строки, но пример raw logging из документации здесь **не используется**; [Chromium error list](https://chromium.googlesource.com/chromium/src/+/main/net/base/net_error_list.h) подтверждает имена whitelist. Группировка, allowlist и выбор последней пары — наши инженерные решения, не диагноз из документации.
