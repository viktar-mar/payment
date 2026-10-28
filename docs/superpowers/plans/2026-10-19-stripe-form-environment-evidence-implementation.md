# Stripe Form Environment Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить ограниченной диагностике конечные признаки CSP и ошибок загрузки, не вводя оплату, raw network logging или утверждение о найденной причине.

**Architecture:** Изолированный in-page CSP collector и Node page-resource collector выдают только fixed scalar evidence. Существующий SDK sample v1 остаётся неизменным; отдельный observation связывает его с evidence, а строгий wire/report boundary переходит на v2. Collector сначала проверяется самостоятельно, затем одним целостным шагом подключается к driver/parent/schema/reporter, чтобы не оставить несовместимые producer/consumer commits.

**Tech Stack:** Установленные TypeScript 5.9.3, Node ESM/native TS erasure, Playwright 1.63.0, Vite 8.3.0 и имеющийся jsdom; новых зависимостей нет.

**Spec:** [Согласованный design addendum](../specs/2026-10-19-stripe-form-environment-evidence-design.md), дополняющий [исходный ограниченный режим](../specs/2026-10-18-stripe-form-load-diagnostic-design.md).

**Status/authority:** Офлайн-реализация завершена по подтверждённому пользователем автоматическому продолжению. Tasks1–2 отдельно одобрены; единственное Important замечание финального review о проверке снимков до сериализации закрыто RED/GREEN и scoped re-review, новых замечаний нет. Свежий общий gate198/198 и demo tsc exit0. Feature-only публикация исходного кода подтверждена remote hash readback. Actual runtime не разрешён и не запускался; причина прежней загрузки UNKNOWN. Source baseline — `17b4d08e44f7310068b1d3e390da8261ff216960`, implementation baseline — `2bd8b7aee8b69a6edc95bedbdcd30d02e42d3d59`; работа только в существующей linked worktree/ветке `codex/stripe-sandbox-5a`, primary main не меняется.

## Global Constraints

- Подтверждено только офлайн-продолжение реализации/тестов; actual browser, Sandbox/API/CLI/OAuth, Docker/appstack, private env/keys/JWK/mail/OTP/runtime logs, actual run UUID/ports/allowance, удаление/adoption/replay запрещены.
- «Не меняются SDK/backend, nginx/CSP, selectors, Stripe ready timeout, provider binding, финансовые proof, default/full/Fake режимы». Нет dependency/lockfile/tsconfig изменений или новых SDK exports/events.
- CSP reads: только isTrusted/effectiveDirective/disposition; resource reads: только resourceType/status/request().resourceType. Нельзя читать или сохранять URL/frame/headers/body/postData/failure/errorText/provider payload/DOM/input/console/trace/HAR/screenshot/video.
- «Количество событий не накапливается: только OR конечных битов, постоянный размер памяти». Не хранить Event/Request/Response, строки/списки событий и отложенные callbacks с payload.
- «Максимум два frame, каждый не более 4096 UTF-8 bytes». Wire version=2; SDK sample version=1 неизменен; old/mixed wire и частичные поля отвергаются.
- «OBSERVED означает только, что сборщик установлен и этот снимок доступен». Ноль не доказывает отсутствие проблемы; masks не устанавливают принадлежность Stripe, ключевую ошибку или причину readiness.
- «Остаются исходные 15 минут active, 15s checkpoint, 60s observation и исходный active deadline+120000ms cleanup». Context grace <=5000ms / reserve10000ms / actual-dispatch isClosed guard сохраняются.
- Node callbacks/reads под исходными window/abort; CSP private bits ограничены исходным active deadline, но могут меняться после Node abort/local end до teardown. После конца наблюдения их не читают/публикуют. Это не cancellation proof.
- Все financial/no-confirmation и context/browser/runtime proof независимы; готовность окружения не заменяет ready+enabled/visible Pay. Неясность — manual check, не repeat.

## Review Focus

1. Report-only CSP не должен превращаться в enforce, а CSP собственного документа — в заявление о cross-origin iframe: Task1 literal mask/scope tests и Task2 интерпретация.
2. HTTP404/503 — не requestfailed; отсутствие события — не доказательство исправной сети: Task1 отдельные status/transport tests, Task2 нули/UNAVAILABLE.
3. Отсутствующий widget facade не должен скрыть уже доступный CSP-признак; частичная instrumentation не разрешает ready exit0: Task2 opaque evidence/partial source tests.
4. Deadline/abort/teardown/late acquisition не должны обновить frozen evidence или лишить cleanup прежней границы: Task1 stop-gate tests и Task2 clock/late-event regressions.
5. Getter/unknown/mixed-version/oversized input не должен попасть в вывод, а cached snapshot — стать fresh диагнозом: Task1 hostile event access tests и Task2 descriptor/wire/cache tests.

## Файлы и точные интерфейсы

### Task1: самостоятельный collector, без подключения к запуску

Create:

- `infrastructure/stripe-form-diagnostic-environment.mjs` — чистый finite validation/canonical unavailable.
- `infrastructure/stripe-form-diagnostic-environment.d.mts` — типы evidence и declarations.
- `infrastructure/stripe-form-diagnostic-environment.test.mjs` — домены/копии/accessors.
- `examples/demo-site/tests/support/form-load-diagnostic-environment.ts` — CSP init/read и Node resource observer.
- `examples/demo-site/tests/support/form-load-diagnostic-environment.test.ts` — реальные функции с синтетическими event/page boundaries.

Modify/test: `examples/demo-site/tests/support/form-load-diagnostic-config.test.ts` — новый Node-only test совпадает с уже существующим normal-discovery ignore glob; сам config не менять.

### Task2: единый producer/consumer переход

Modify:

- `infrastructure/stripe-form-diagnostic-schema.mjs`, `infrastructure/stripe-form-diagnostic-schema.d.mts`, `infrastructure/stripe-form-diagnostic-schema.test.mjs` — observation boundary и v2 wire.
- `infrastructure/stripe-form-diagnostic.mjs`, `infrastructure/stripe-form-diagnostic.d.mts`, `infrastructure/stripe-form-diagnostic.test.mjs` — строгое принятие observations и сохранение доказательств.
- `examples/demo-site/tests/support/form-load-diagnostic.ts`, `examples/demo-site/tests/support/form-load-diagnostic.test.ts` — установка/снимки/stop/cache, без переписывания cleanup.
- `examples/demo-site/tests/support/form-load-diagnostic-reporter.test.ts` — v2 fixtures/rejection/privacy; production reporter уже потребляет общий parser, его поведение не расширять.
- `docs/local-development.md` — только описание новых конечных полей, ограничений и отдельного future runtime approval.

Read/verify, не менять: diagnostic page/facade, SDK source, `tests/form-load-diagnostic.spec.ts`, existing reporter/config/Vite/normal entry, provider/runtime/provision guards. При необходимости правки за этим manifest — остановиться и объяснить её до изменения scope.

### Общие типы и значения

`FormCspEvidence`: readonly `csp_observation:'OBSERVED'|'UNAVAILABLE'`, `csp_enforced_mask:number`, `csp_report_only_mask:number`.

`FormResourceEvidence`: readonly `resource_observation:'OBSERVED'|'UNAVAILABLE'`, `resource_transport_failure_mask:number`, `resource_http_4xx_mask:number`, `resource_http_5xx_mask:number`.

`FormEnvironmentEvidence` — их пересечение плюс readonly `environment_freshness:'SAMPLED'|'CACHED'|'UNAVAILABLE'`. Все masks — исключительно integers0..15. UNAVAILABLE source требует нулевые собственные masks; freshness=UNAVAILABLE iff оба source UNAVAILABLE. CSP bits1/2/4/8=SCRIPT/FRAME/CONNECT/OTHER; resource bits1/2/4/8=SCRIPT/DOCUMENT/FETCH_XHR/OTHER. Точные mapping перечислены в spec, неизвестная строковая категория попадает в OTHER.

Task1 exports из infrastructure module:

```ts
validateFormEnvironmentEvidence(value: unknown): Readonly<FormEnvironmentEvidence> | null;
unavailableFormEnvironmentEvidence(): Readonly<FormEnvironmentEvidence>;
```

Task1 exports из browser support module:

```ts
installFormCspObserver(args: {ownedOrigin: string; activeDeadline: number}): void;
readDiagnosticPageEvidence(includeForm: boolean): unknown;
createFormResourceObserver(page: Pick<Page, 'on'|'off'>, isActive: () => boolean): {
  snapshot(): Readonly<FormResourceEvidence>;
  stop(): void;
};
```

Первый/второй exports передаются в addInitScript/evaluate: самодостаточные функции без ссылок на imported/module closure values. Init публикует только readonly `__formDiagnosticCsp.snapshot()`, возвращающий FormCspEvidence. `readDiagnosticPageEvidence` читает только эти два известных readonly facade и возвращает ровно `{handshake:boolean, sample:unknown|null, csp:unknown|null}`; при includeForm=false не вызывает SDK snapshot. Facade читаются в независимых finite guards: отсутствующий/throwing SDK reader → false/null, но не скрывает доступный CSP и наоборот. Getter для свойств facade не исполняется: использовать data descriptors; методы вызываются только из проверенных data properties. Imported types стираются; для native Node .ts import использовать существующий локальный `@ts-expect-error Native Node source type erasure` pattern, не ослаблять tsconfig.

Task2 exports/types из schema:

```ts
interface FormDiagnosticObservation {
  readonly sample: Readonly<FormDiagnosticSample> | null; // unchanged v1
  readonly environment: Readonly<FormEnvironmentEvidence>;
}
validateFormDiagnosticObservation(value: unknown): Readonly<FormDiagnosticObservation> | null;
buildFormDiagnosticFrame(sample: FormDiagnosticSample|null, outcome: FormDiagnosticOutcome,
  proofs: FormDiagnosticProofs, environment: FormEnvironmentEvidence): FormDiagnosticFrame;
```

`FormLoadBrowserResult` и parent dependency result: ровно `{observations:readonly FormDiagnosticObservation[], outcome, browserClosed, contextClosed}`. Старого `samples` нет; maximum2. `FormDiagnosticFrame` — плоский version2 + result/proofs/environment, с необязательным набором ВСЕХ stage полей кроме sample.version; partial stage не допускается. Sample validator/classifier сохраняют v1 контракт. Frame formatter/parser не принимают v1 wire. Для восстановления stage classification из полного v2 frame явно создать внутренний sample с version1; не смешивать версии.

## Task 1: конечные сборщики и приватность

**Consumes:** spec masks/allowed reads, existing Page events and init-script mechanism. **Produces:** Task1 types/functions выше; текущий diagnostic producer/consumer ещё v1 и не импортирует collector.

- [x] **1. Написать поведенческие тесты**. Использовать настоящие новые validator/collector functions и синтетические EventTarget/Page/Request/Response, не source-text assertions. Если отсутствующий module мешает behavioral RED, разрешены только inert export shells/canonical unavailable — import/type failure не считать доказательством. Literal examples:

  ```ts
  // trusted own-document enforce script-src-elem, then report connect-src
  assert.equal(csp.csp_enforced_mask, 1);
  assert.equal(csp.csp_report_only_mask, 4);
  // response404(script), response503(fetch), requestfailed(document)
  assert.equal(resources.resource_http_4xx_mask, 1);
  assert.equal(resources.resource_http_5xx_mask, 4);
  assert.equal(resources.resource_transport_failure_mask, 2);
  ```

  Pin every mapping, combined masks15, repeat idempotence, 200/302 no error bits, unknown string→OTHER, report-only vs enforce, untrusted ignored, wrong origin/non-top no collector. Invalid disposition/status/type or allowed getter throwing → source UNAVAILABLE, no raw error inspection. Synthetic forbidden property/method traps (`blockedURI`, URL, failure(), headers/body/frame etc.) must have read count0. Missing source ≠ observed-zero; extra/accessor/NaN/fraction/16/negative masks reject, frozen scalar copies independent of mutated input.
- [x] **2. Запустить meaningful RED**: из root `node --test infrastructure/stripe-form-diagnostic-environment.test.mjs examples/demo-site/tests/support/form-load-diagnostic-environment.test.ts`. Зафиксировать literal mask/domain failures, не import/syntax error; пока implementation отсутствует, GREEN не заявлять.
- [x] **3. Реализовать минимальный collector**. CSP только own-origin top document; один capture listener, fixed fields, absolute activeDeadline check до event reads. Независимость от session init ordering. Resource observer подписывается на два own-page events, OR битов, isActive gate ДО чтений, без async/retained payload. stop сначала закрывает gate, затем снимает только свои listeners; unsubscribe failure не открывает gate/не блокирует дальнейший cleanup и не считается proof удаления listener. Невалидный источник защёлкивает UNAVAILABLE и больше не читает события.
- [x] **4. Проверить serialization/lifecycle**. Исполнить `installFormCspObserver.toString()` и `readDiagnosticPageEvidence.toString()` в изолированном synthetic realm без module variables/сети; выполнить actual callback через зарегистрированный listener с platform-like trusted record. Изменение event input после callback не меняет frozen snapshot. Проверить original active deadline, partial registration failure, повторный stop, captured callbacks после stop/abort и нулевые forbidden reads. Нельзя выдавать synthetic DOM за настоящую доставку CSP browser events.
- [x] **5. Прогнать GREEN и typecheck**. Команда шага2 плюс `examples/demo-site/tests/support/form-load-diagnostic-config.test.ts`; отдельно из root `node --test infrastructure/stripe-form-diagnostic-schema.test.mjs examples/demo-site/tests/support/form-load-diagnostic.test.ts`. Из `examples/demo-site`: `node node_modules/typescript/bin/tsc --noEmit`. Добавить в config test проверку `node:path.matchesGlob` для нового support test против фактического `testIgnore` (текущий Node25 поддерживает API). Expected exit0, все tests passed, no skipped/cancelled. Старые schema/driver тесты ещё проходят без новой wiring.
- [x] **6. Независимое review и commit**. Проверить allowed reads, отсутствие payload retention, serialization, stop scope и доступность интерфейсов Task2. Critical/Important → RED→GREEN и targeted re-review; сохранить отчёт/хэши. ROOT коммитит только Task1 manifest после source/diff/secret audit; default/runtime не запускать.

## Task 2: подключение к строгому отчёту без смены смысла результата

**Consumes:** Task1 exact exports/types. **Produces:** observations boundary, v2 finite wire и полностью проверенный офлайн diagnostic path.

- [x] **1. Написать integration/schema/reporter RED**. Выполнять реальный driver→parent→schema→formatter/reporter с синтетическим внешним Page/runtime boundary; pin:

  - facade missing + CSP enforced script bit1 → opaque version2 INSTRUMENTATION_UNAVAILABLE с bit1, no fabricated stage/Resume;
  - throwing/accessor SDK facade не скрывает CSP; CSP failure не скрывает resource evidence; начальная частичная instrumentation даёт no Resume/client-session;
  - наблюдавшиеся HTTP404(script)/503(fetch) и transport(document) проходят как отдельные scalar masks без определения Stripe-origin/причины;
  - literal ready v1 sample + SAMPLED/оба OBSERVED + четыре proofs → v2 FORM_READY_*, exit0; источники UNAVAILABLE → INSTRUMENTATION_UNAVAILABLE/exit1; ненулевой вспомогательный mask не отменяет настоящую readiness;
  - FAILED/ABORTED/DEADLINE/financial/cleanup uncertainty всё ещё сильнее ready/masks; old `samples` browser result, extra/mixed/version1 wire, getters/duplicate keys/oversize/truncation reject без raw output;
  - 15s checkpoint retains собственный environment; cached final cannot upgrade to fresh stage/ready. При clock4999/5000/5001 и reserve999/1000/1001 прежние независимые closure assertions сохраняются.
- [x] **2. Запустить RED на текущем v1 wiring**. `node --test infrastructure/stripe-form-diagnostic-schema.test.mjs infrastructure/stripe-form-diagnostic.test.mjs examples/demo-site/tests/support/form-load-diagnostic.test.ts examples/demo-site/tests/support/form-load-diagnostic-reporter.test.ts`. Подтвердить literal absent evidence/version/false-success failures до producer changes. Простая замена всех fixture version1 на2 без проверки внутреннего sample1 запрещена.
- [x] **3. Подключить driver**. Установить CSP init и resource observer до goto. Gate учитывает original limits/abort/closing и local end после его определения. После goto один bounded read includeForm=false сохраняет доступный CSP+resource evidence до проверки handshake. До Resume требовать исправный handshake, оба источника OBSERVED и SAMPLED; иначе сохранить конечную частичную информацию и завершить без client-session. В loop один read includeForm=true заменяет прежний snapshot evaluate; outer record и stage/CSP проверить data descriptors, затем прежний Pay proof и Node snapshot. Observer records frozen; SDK/page facade и sampling intervals не менять.
- [x] **4. Сохранить failure/cache/stop**. На failure никаких новых page reads: только последний полностью валидный composite observation (freshness=CACHED) либо canonical unavailable. Если после последнего вывода checkpoint ещё не сохранён новый final observation, окончательный failure использует cached copy, не переписывая checkpoint. При FORM_LIMIT только прежний last sample с AT_LIMIT/CACHED; ни late Pay, ни CSP/resource bits не становятся fresh. Если до failure был только post-goto environment baseline, final sample=null и evidence=CACHED. В finally первым делом stop Node collector, freeze output; прежний context/browser/acquisition cleanup и его единственный original ceiling не рефакторить.
- [x] **5. Атомарно обновить parent/schema/types/fixtures**. Parent строго принимает только observations, каждому проверяет sample/env data shape, при отсутствии browser результата формирует canonical unavailable. Builder четвёртым обязательным аргументом получает environment каждого observation; parent не размазывает final evidence по checkpoint. CACHED/UNAVAILABLE и частичная instrumentation не допускают обычный stage/ready result; dominant opaque failures остаются. Parser/formatter v2 exact keys/UTF8≤4096/max2; reporter логика/вывод только существующих validated frames и fixed failure marker.
- [x] **6. Добавить поздние/приватные regressions и guide**. Actual retained CSP callback после Node abort/local end может менять только private bounded bits; frozen report не меняется и новых page reads нет. После resource stop/deadline callbacks не читают payload. Getter exception/partial instrumentation/late resource acquisition не меняют final outcome/proof/deadline. Существующие 37 driver cases, включая preclosed no-op, остаются meaningful. Guide объясняет masks/нулевые значения/OBSERVED/CACHED и отсутствие causal attribution, v2 breaking diagnostic-only format, отдельный runtime approval; запускных команд/actual IDs/ключей нет.
- [x] **7. Свежий применимый regression gate**. Выполнить команды ниже в sanitized offline environment (без PAYMENT/DEBUG/NODE_OPTIONS/private credentials), сохранить полный вывод, exit codes/counts/хэши. Expected all pass, no fail/cancel/skip, demo tsc exit0. Existing graph test строит diagnostic ESM и исполняет singleton handshake в synthetic jsdom с network forbidden; это не actual browser. Backend/SDK source не меняются: Maven/Docker/full financial journeys не запускать и не представлять как пройденные.

  ```sh
  # cwd: repository root; synthetic tests only
  env -i PATH="$PATH" LANG=C LC_ALL=C NO_COLOR=1 node --test \
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
  ```

  ```sh
  # cwd: examples/demo-site
  env -i PATH="$PATH" LANG=C LC_ALL=C NO_COLOR=1 node node_modules/typescript/bin/tsc --noEmit
  ```

- [x] **8. Независимое scoped review и публикация**. Review всего addendum Tasks1–2 на frozen hashes, без повторного исторического Stage5A review/тех же suites; полученные findings проверить и закрыть. ROOT проверяет spec coverage, descriptors/privacy/version/deadline/proof, exact changed-file manifest, diffcheck/secret candidates; коммитит и пушит только feature branch по standing authority с remote hash readback. Main/evidence/resources сохраняются, PR/merge/archive не делать. Финал явно говорит «только офлайн; причина UNKNOWN до новых сведений; Sandbox не запускался».

## Что сознательно не входит

Нет исправления Stripe/CSP/Shadow DOM по предположению, увеличения ожиданий, pair-key validation по `/v1/account`, чтения network URL/errors, raw report/endpoint или внесения provider error payload в enum. Не планируется полный Sandbox, card entry, confirmation/refund/webhook acceptance, подписки/Portal или 5B/5C. Команды настоящего запуска сюда не входят.

После реализации и её approval можно отдельно предложить одну прежнюю prepare-only Sandbox-попытку с новыми evidence, явно оговорив provider writes. Если новых положительных сведений нет — зафиксировать UNKNOWN/пределы наблюдения; автоматический повтор, ослабление CSP и «значит виноват Stripe» недопустимы.

## Self-review и handoff

Spec coverage: CSP/resource domains, allowed reads, states/freshness и независимость init → Task1; version2, opaque baseline/missing facade, composite snapshots/cache, unchanged guards/cleanup и guide → Task2. Все пять Review Focus имеют собственные тесты выше. Типы/имена между задачами совпадают; unused collector Task1 не меняет v1 runtime, Task2 меняет producer/consumer целиком. Implementation method уже выбран пользователем ранее: субагент-исполнитель и независимое review каждой задачи; не запрашивать этот выбор повторно.

Независимый builder-check нашёл один существенный вопрос: остановка CSP realm при Node abort/local end. Это не пользовательский product choice, а разрешённое инженерное уточнение: private fixed bits могут жить до active deadline/document teardown, но Node reads/publication прекращаются и frozen evidence не меняется. Решение включено в spec/Tasks1–2; дополнительный RPC/control API не нужен. Иных consequential questions не предъявлено. Новые design choices (masks, wire2, частичная instrumentation, две задачи) представлены пользователю для approval, не выдаются за ранее согласованные.

Офлайн-реализация, финальное review с закрытием замечания и feature-only публикация завершены. Эти результаты не разрешают actual runtime; для будущей попытки требуется отдельное согласование.
