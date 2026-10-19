# Stripe Form Fetch Attribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Различить безопасные категории последнего failed FETCH/XHR и причины окончания form observation, не меняя оплату и не запуская Sandbox.

**Architecture:** Сначала независимые синхронные finite classifiers; затем одним producer/consumer изменением подключить две связанные metadata категории и observation_end через строгий wire v3. SDK sample1/CSP/readiness/cleanup остаются прежними. Последняя пара относится к одному событию, а не к независимым накопительным masks.

**Tech Stack:** Установленные Node25.2.1/native TS erasure, TypeScript5.9.3, Playwright1.63.0, Vite8.3.0 и jsdom. Без новых dependencies/lockfile/tsconfig изменений.

**Spec:** [Design addendum](../specs/2026-10-19-stripe-form-fetch-attribution-design.md); дополняет environment evidence v2 и исходный prepare-only режим, не переписывая историю.

**Status:** Пользователь подтвердил scope офлайн-расширения с тестами/review. Baseline `59c795fbed441240db0c1e566c8decc797c97e09`, linked worktree `codex/stripe-sandbox-5a`; main не меняется. Независимый builder-check дизайна завершён: «nothing important left»; root self-review плана завершён. До реализации ожидается review/подтверждение плана пользователем. Метод исполнения уже выбран: субагент на задачу, отдельный независимый review каждой задачи и финальный scoped review; повторно этот выбор не спрашивать.

## Global Constraints

- «Только офлайн-код, синтетические тесты и review; новые зависимости не нужны». Нет actual browser/Sandbox/API/CLI/OAuth/Docker/appstack, private env/keys/JWK/mail/OTP/runtime logs, run UUID/ports/allowance, удаления/adoption/replay ресурсов.
- «Не меняются SDK/backend, nginx/CSP, selectors, интервалы/timeout, provider binding, financial/no-confirmation proof, context/browser/runtime cleanup, default/full/Fake режимы». Нет новых SDK exports/events.
- Новые reads только у активного failed fetch/xhr: однократные синхронные request.url()/failure(); failure — exact plain/null record с own enumerable data errorText, max128; URL max8192. Никаких retained raw strings/objects, frame/headers/body/console/trace/HAR/input или анализа thrown value.
- Constant space: существующие OR masks и последняя связанная пара конечных категорий. Metadata read failure даёт UNKNOWN соответствующей категории; старый source failure — прежний UNAVAILABLE плюс UNAVAILABLE/UNAVAILABLE.
- Wire version3; SDK sample version1 неизменен. «Максимум два frame, каждый<=4096 UTF-8 bytes». v1/v2/mixed/partial/extra/getter/duplicate reject; история не мигрирует.
- «Исходные границы: 15 минут active, checkpoint15s, observation60s, cleanup до original active deadline+120000ms, context grace<=5000ms и reserve10000ms». Категории не меняют result/proof и не возобновляют время.
- Нули/NONE/UNKNOWN не доказывают здоровую сеть; hostname class не проверяет ключи/iframe/причину ready. GENERIC_FAILED не превращать в CORS или ошибку Stripe. CACHED не становится fresh evidence.

## Review Focus

1. Hostname/credential/port/oversize tricks не должны получить Stripe/OWN_APP класс или вывести query: Task1 точные adversarial URL tests; Task2 end-to-end sentinel output assertion.
2. Новое metadata исключение не должно стереть старые masks или вторую категорию: Task2 independent url/failure throwing/getter tests, не обходящие original stop guards.
3. Пара из разных событий и переписанный checkpoint дают ложную атрибуцию: Task2 atomic latest-pair, independent frozen snapshots и late callback tests.
4. LOCAL_LIMIT не должен притворяться browser/SDK failure, а FORM_READY reason — финансовым/cleanup успехом: Task2 fake-clock/outcome/proof matrix.
5. Неполный v3 или старый/mixed report не должен пройти parser/reporter; actual Playwright discovery не должен включить synthetic tests: Task2 exact shapes/bytes/privacy и существующий config gate.

## File Map и интерфейсы

### Task1 — самостоятельные классификаторы, без wiring

Create:

- `infrastructure/stripe-form-diagnostic-network.mjs` — два синхронных классификатора без I/O, логов/retention.
- `infrastructure/stripe-form-diagnostic-network.d.mts` — literal types и declarations.
- `infrastructure/stripe-form-diagnostic-network.test.mjs` — literal/adversarial/descriptor/privacy tests.

Produces (точные домены/allowlist — spec):

```ts
type ObservedFormFetchDestination = 'OWN_APP'|'STRIPE_API'|'STRIPE_JS'|'OTHER'|'UNKNOWN';
type ObservedFormFetchFailureCode = 'ABORTED'|'NAME_NOT_RESOLVED'|'CONNECTION_REFUSED'|'CONNECTION_RESET'|'TIMED_OUT'|'CERTIFICATE'|'BLOCKED_BY_CLIENT'|'GENERIC_FAILED'|'UNKNOWN';
type FormFetchDestination = ObservedFormFetchDestination|'NONE'|'UNAVAILABLE';
type FormFetchFailureCode = ObservedFormFetchFailureCode|'NONE'|'UNAVAILABLE';
classifyFormFetchDestination(rawUrl:unknown, ownedOrigins:readonly string[]):ObservedFormFetchDestination;
classifyFormFetchFailure(rawFailure:unknown):ObservedFormFetchFailureCode;
```

`ownedOrigins` — frozen exact two canonical loopback HTTP origins; classifier сам защищается от malformed input и возвращает UNKNOWN. Browser API calls сюда не передаются; никаких функций/методов от unknown input не вызывать.

### Task2 — целостный upgrade collector/driver/parent/schema/reporter

Modify + corresponding tests:

- `infrastructure/stripe-form-diagnostic-environment.{mjs,d.mts,test.mjs}` — десятипольный environment, два resource scalar и exact availability/mask invariants.
- `infrastructure/stripe-form-diagnostic-schema.{mjs,d.mts,test.mjs}` — `FormObservationEnd`, builder fifth argument, wire3 и consistency rules.
- `infrastructure/stripe-form-diagnostic.{mjs,d.mts,test.mjs}` — exact five-key browser result, parent fallback и checkpoint/final reason.
- `examples/demo-site/tests/support/form-load-diagnostic-environment.ts` и `.test.ts` — только Node resource observer получает metadata classifiers и frozen ownedOrigins.
- `examples/demo-site/tests/support/form-load-diagnostic.ts` и `.test.ts` — validate origins, передать observer, добавить observationEnd; не переписывать cleanup.
- `examples/demo-site/tests/support/form-load-diagnostic-reporter.test.ts` — wire3 fixtures/privacy/old-version rejection.
- `docs/local-development.md` — три новых scalar, latest-pair/UNKNOWN/причины/protocol3 и отдельная launch authority.

Read/verify, не менять: production reporter (общий parser уже задаёт wire version), diagnostic page/facade/SDK, spec entry/config/Vite, provider/runtime/provision/bootstrap. Существующие config/build/provider/runtime tests — regression gate, не расширение runtime.

Interfaces Task2:

```ts
// FormResourceEvidence дополнительно:
readonly fetch_failure_destination:FormFetchDestination;
readonly fetch_failure_code:FormFetchFailureCode;
// Node collector получает проверенный список origins третьим аргументом:
createFormResourceObserver(page:Pick<Page,'on'|'off'>, isActive:()=>boolean,
  ownedOrigins:readonly string[]):{snapshot():Readonly<FormResourceEvidence>;stop():void};
type FormObservationEnd = 'OBSERVING'|'FORM_READY'|'SDK_TERMINAL'|'LOCAL_LIMIT'|'ABORTED'|'ACTIVE_DEADLINE'|'INSTRUMENTATION_UNAVAILABLE'|'BROWSER_OPERATION_FAILED'|'BROWSER_NOT_STARTED';
// Browser result и dependency result: прежние four keys плюс:
readonly observationEnd:Exclude<FormObservationEnd,'OBSERVING'|'BROWSER_NOT_STARTED'>;
buildFormDiagnosticFrame(sample:FormDiagnosticSample|null, outcome:FormDiagnosticOutcome,
  proofs:FormDiagnosticProofs, environment:FormEnvironmentEvidence,
  observationEnd:FormObservationEnd):FormDiagnosticFrame;
// Wire: version3 + существующий stage/result/proofs/environment + observation_end.
```

Sample1 и `FormDiagnosticObservation={sample,environment}` не меняются; новое поле конца относится к browser result, а не ко всем историческим observations. Parent даёт первому из двух frames OBSERVING, последнему final observationEnd; если frame один — final reason. Ready requires FORM_READY; pending stage requires OBSERVING. Primary failures доминируют независимо от reason. Exact final reason/outcome combinations перечислены в spec; malformed combination → instrumentation failure, не успех.

## Task 1: finite network classifiers

- [ ] **1. Написать meaningful behavioral RED.** Если import отсутствует, допустимы только inert shells с UNKNOWN; import failure не считать RED. Assert destination для demo/backend OWN_APP, `https://api.stripe.com/path?client_secret=SENSITIVE_SENTINEL`→STRIPE_API, `https://a.js.stripe.com/x`→STRIPE_JS; OTHER для lookalikes/remote HTTP/nondefault port, UNKNOWN для credentials/malformed/nonstring/>8192/ASCII control-space/invalid owned origins. Assert exact code whitelist и UNKNOWN для suffix/prefix/oversized/sensitive values. Getter/hidden/symbol/extra/prototype failure records не исполняют accessors; не читаются message/stack/toString/toJSON. Все выходы — фиксированные литералы без sentinel.
- [ ] **2. Запустить RED.** Из root `node --test infrastructure/stripe-form-diagnostic-network.test.mjs`; ожидать конкретные mismatched literals, не syntax/import error.
- [ ] **3. Реализовать ровно Task1 interfaces.** URL parser + exact validated origin/protocol/host/port; failure record проверять descriptors до чтения data value, затем whole-string map по spec. Ни методов unknown object, ни ручного URL decoding, ни raw errors в state/output. Unknown inputs → UNKNOWN.
- [ ] **4. GREEN и regression.** Команда шага2; затем `node --test infrastructure/stripe-form-diagnostic-environment.test.mjs examples/demo-site/tests/support/form-load-diagnostic-environment.test.ts`; все pass без skip/cancel. Task1 не подключён к текущему wire2 и не меняет его.
- [ ] **5. Независимое review и commit.** Frozen manifest ровно три новых файла. Critical/Important → targeted RED/GREEN и re-review. Root проверяет diff/privacy/чистоту unrelated files и коммитит только Task1 manifest; никакой runtime проверки.

## Task 2: collector, end reason и wire3

- [ ] **1. Добавить integration/schema/reporter RED.** Literal latest pairs: requestfailed fetch(OWN_APP,ABORTED), затем xhr(STRIPE_API,NAME_NOT_RESOLVED); transport mask остаётся4, пары не смешиваются, первый snapshot frozen. URL read throw с valid code сохраняет UNKNOWN/code; failure read throw сохраняет destination/UNKNOWN. Script/document/response не вызывают url/failure. Invalid source resourceType/status по-прежнему даёт masks0+UNAVAILABLE pair. NONE iff bit4 absent, observed tuple iff bit4 present, unavailable pair iff source unavailable.
- [ ] **2. Добавить stop/приватность/clock RED.** Captured callback после abort/local end/original deadline/stop/teardown читает0 properties включая url/failure. Реальный driver/parent с fake Page/runtime: local60s→LOCAL_LIMIT+FAILED, page read rejection→BROWSER_OPERATION_FAILED+FAILED, abort→ABORTED, original deadline→ACTIVE_DEADLINE, bootstrap absent→INSTRUMENTATION_UNAVAILABLE, prepare failure→BROWSER_NOT_STARTED. Checkpoint reason OBSERVING не переписывается final reason; caches/masks/pair frozen. FORM_READY с closure/financial failure никогда не exit0.
- [ ] **3. Запустить meaningful RED.** Из root `node --test infrastructure/stripe-form-diagnostic-environment.test.mjs infrastructure/stripe-form-diagnostic-schema.test.mjs infrastructure/stripe-form-diagnostic.test.mjs examples/demo-site/tests/support/form-load-diagnostic-environment.test.ts examples/demo-site/tests/support/form-load-diagnostic.test.ts examples/demo-site/tests/support/form-load-diagnostic-reporter.test.ts`; ожидать literal missing metadata/reason/version failures, не механический import/type mismatch.
- [ ] **4. Подключить collector и environment schema.** Расширить типы/unavailable/exact validator; validate/freeze два owned origins до launch; передать третьим аргументом observer. Active callback сначала прежний guard+resourceType, затем для fetch/xhr локально вычислить обе категории с независимыми try/catch и одним присваиванием пары. Старые invalidation/stop/unsubscribe/privacy и CSP serialized functions не менять.
- [ ] **5. Атомарно обновить driver→parent→schema→fixtures на v3.** Добавить observationEnd по spec без новых reads/timers/browser operations. Сохранить FORM_LIMIT как отличную причину, generic rejection — не анализируя error. Parent exact result/outcome/reason guards и независимые final proof/close остаются; builder fifth argument и all consumers обновить в одном commit. Старый/mixed v1/v2, missing/extra/accessor/duplicate/oversize/reason mismatch отвергать. Production reporter менять только если доказана необходимость и scope объяснён до изменения.
- [ ] **6. GREEN, guide и свежий applicable gate.** Все source gate commands ниже проходят; tests no fail/cancel/skip, tsc exit0. Guide объясняетlatest same-event pair/UNKNOWN, metadata collection before Resume/subframes, no cause/pair-key judgment, observation_end vs result, v3 breaking diagnostic-only change и отсутствие нового launch authority. Не менять старые completed status docs или historical evidence.

  ```sh
  # cwd repository root; только synthetic boundaries
  env -i PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin LANG=C LC_ALL=C NO_COLOR=1 node --test \
    infrastructure/stripe-form-diagnostic-network.test.mjs \
    infrastructure/stripe-form-diagnostic-environment.test.mjs \
    infrastructure/stripe-form-diagnostic.test.mjs \
    infrastructure/stripe-form-diagnostic-provider.test.mjs \
    infrastructure/stripe-form-diagnostic-runtime.test.mjs \
    infrastructure/stripe-form-diagnostic-schema.test.mjs \
    examples/demo-site/tests/support/form-load-diagnostic-environment.test.ts \
    examples/demo-site/tests/support/form-load-diagnostic.test.ts \
    examples/demo-site/tests/support/form-load-diagnostic-reporter.test.ts \
    examples/demo-site/tests/support/form-load-diagnostic-config.test.ts \
    examples/demo-site/tests/support/form-load-diagnostic-build.test.ts
  # cwd examples/demo-site
  env -i PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin LANG=C LC_ALL=C NO_COLOR=1 node node_modules/typescript/bin/tsc --noEmit
  ```

- [ ] **7. Независимое Task2 review, commit, финальное scoped review.** Frozen changed-file hashes, exact spec/manifest, no hidden source changes. Закрыть Critical/Important meaningful RED/GREEN и targeted re-review; свежий diffcheck и applicable gate перед final status/commit. Root может публиковать только feature branch по standing authority с remote hash readback; без main/PR/merge/archive и без удаления/подъёма старых ресурсов. Ни Maven, ни реальную оплату не запускать и не заявлять как проверенные.

## Self-review и handoff

Spec coverage: exact destination/error mapping/privacy → Task1; event allowed reads/availability/latest pair/gates → Task2. Outcome/reason/checkpoint/cache/wire3/reporter/guide/proof independence → Task2. Пять Review Focus привязаны к конкретным assert cases. Подключение producer/consumer и всех fixtures — атомарное, самостоятельный Task1 сохраняет работоспособный wire2 baseline. Типы/названия между задачами согласованы; команды используют уже установленные runtime/dependencies.

Независимый builder-check не оставил consequential questions. Инженерные решения автора: latest same-event pair (не causal/history trace), узкий exact allowlist с UNKNOWN, wire3 и отдельный observation_end; пользователь подтвердил назначение/границы расширения, не каждую техническую константу. Root self-review подтвердил покрытие spec, checkable steps, одинаковые type/signature names и тесты для пяти Review Focus. Дополнительных пользовательских выборов, помимо review плана, не требуется.

План передаётся пользователю для review перед реализацией по writing-plans. Подтверждение плана не означает Sandbox approval: после реализации/тестов/review остановиться и доложить результат, не запускать новую попытку автоматически.
