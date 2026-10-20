# Manual Sandbox Refund Remainder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подготовить один офлайн-проверенный режим завершения возврата EUR13 исходного EUR20 после заново доказанного возврата EUR7, с одной будущей ротацией callback-ключа ожидаемой версии3.

**Architecture:** Новый inert entry MANUAL_REFUND_REMAINDER имеет собственные admission, sticky fence/state, restoration, scoped read-only CLI и ordered session. Старый MANUAL_REFUND и его PENDING/lock/state неизменны; новая операция не вызывает старый entry и не ослабляет zero-refund baseline. Переиспользуются существующие ownership/settlement и строго выделенные pure retained decoders; финансовый API и продуктовые backend/frontend не меняются.

**Tech Stack:** Node.js24, dependency-free ESM, node:test, existing TypeScript .d.mts, synthetic POSIX filesystem/loopback/process/HTTP/Stripe doubles; existing Java/API только как read-only contract reference.

**Spec:** [Подтверждённое описание завершения возврата EUR13](../specs/2026-10-20-manual-refund-remainder-design.md). Execution method уже выбран пользователем: subagents, meaningful RED→GREEN и независимое review каждой задачи. Этот план требует отдельного подтверждения до реализации; Sandbox не разрешён.

## Global Constraints

- «Исходный payment run: `031b5ae7-6603-4339-b1b3-77231291ff34`, исходный runtime source: `f0620e40004162d2b3eededa60512f50a492f4cc`».
- «Единственный предшествующий refund run: `34941d87-12d3-4c74-8c5a-b62b7c914390`. Другие abandoned runs не принимаются».
- «Account `acct_1UPiMpELswAd8tpR`, account key `stripe-sandbox-default`, API `2026-09-30.endive`, CLI `1.52.0`, только test mode, EUR2000, source CATALOG, одна исходная CARD attempt».
- «Сумма единственного нового refund POST —1300 minor EUR, reason_code REQUESTED_BY_CUSTOMER, без note/PII. Возврат700 невозможен через новую capability».
- Единственный financial key: `s5a-refund:34941d87-12d3-4c74-8c5a-b62b7c914390:remainder`; он не выводится и не заменяется новым run UUID.
- «Старая `manual-refund.lock` в исходном payment каталоге остаётся существующей и неизменной». Новая `manual-refund-remainder.lock` создаётся exclusive и никогда автоматически не удаляется, включая success/failed admission.
- «Сейчас запрещены реальные Docker/Compose, Stripe CLI/OAuth/listener, provider/Admin/финансовые запросы и чтение настоящих run/env/credentials».
- Никаких Java/API/DB/frontend/dependencies, общего resume, повторного700, callback retry, provider writes/secret retrieval, удаления ресурсов или изменения других Sites. Необходимость такого расширения останавливает работу для нового согласования.
- «Одна новая active authority ≤30min с начала нового entry, cleanup только до этой deadline+2min». HTTP/provider read≤10s; proof≤30 probes/45s; stop grace10s/transport≤20s внутри cleanup ceiling.
- После каждого task: focused/related regressions → независимый reviewer требований/качества → исправление findings → root verification → scoped local commit. После всех: whole-correction review и fresh safe infrastructure checkpoint. Push/merge/PR и фактический Sandbox не входят в этот план.

## Review Focus

1. При совпадающих суммах неверный prior run/lock, изменённая1300 leg или новый UUID не должны дать вторую authority; tests Task1 и cross-entry Task5.
2. Mutable ordinary StartedAt после законного прежнего restarta не равен исходному; изменение immutable identity или любого helper StartedAt должно блокировать восстановление; tests Task2.
3. Caller seed, metadata-only700 или потеря listener между proof и fetch не дают financial permission; tests Task3–4 с exact provider/Event checks и synchronous fence.
4. Неполный detail/list probe не должен скрывать уже наблюдённый terminal/foreign/reconciliation-only факт; tests Task4 до valid subsequent response.
5. Неожиданная rotation version, lost ACK, late resource acquisition, cleanup failure или stdout timeout не становятся success после позднего исправления; tests Task4–5 сохраняют первую ошибку и старую историю.

---

## Карта файлов и общие типы

Каждый новый module имеет соседние .d.mts и .test.mjs, кроме fixture. Все новые imports инертны.

| Files | Responsibility | Task |
| --- | --- | --- |
| `infrastructure/stripe-manual-refund-retained.*` | Pure parsing исходных payment manifests/env, без IO/mode policy | 1 |
| `infrastructure/stripe-manual-refund-remainder-options.*`, `-state.*` | Strict opt-in, pinned continuity, new sticky fence и durable transitions | 1 |
| `infrastructure/fixtures/stripe-manual-refund-remainder-state-fixture.mjs` | Только synthetic retained files, socket и Git double | 1 |
| `infrastructure/stripe-manual-refund-remainder-runtime.*` | Exact retained topology restoration/cleanup | 2 |
| `infrastructure/stripe-manual-refund-remainder-cli.*` и narrow changes `stripe-sandbox-cli.mjs/.test.mjs` | Separate read-only provider/listener capability, no old baseline weakening | 3 |
| `infrastructure/stripe-manual-refund-remainder-session.*` | Protected baseline, one rotation3, one1300 POST, proof chain | 4 |
| `infrastructure/stripe-manual-refund-remainder-schema.*`, `stripe-manual-refund-remainder.mjs/.d.mts/.test.mjs` | Finite report, sole entry/controller, compatibility guards | 5 |
| Old mode guards/snapshots и `docs/local-development.md` | New mode excluded everywhere else; operator handoff | 5 |

Точные типы ниже определяются Task1: `Limits` и `RemainderOptions` — в `stripe-manual-refund-remainder-options.d.mts` с re-export из state declaration, остальные — в `stripe-manual-refund-remainder-state.d.mts`. Дальнейшие задачи импортируют их, не вводят альтернативы:

- `Limits`: прежний readonly `{deadline:number,signal?:AbortSignal}`.
- `RemainderOptions`: readonly `{env:Readonly<Record<string,string|undefined>>,runId:string,expectedSource:string,daemon:string}`.
- `RemainderTarget`: readonly `{original:OriginalRefundTarget,priorRunId:'34941d87-12d3-4c74-8c5a-b62b7c914390',priorSource:string,priorRefundId:string,remainderKey:string,callbackVersionBefore:2,callbackVersionAfter:3}`. `original.callbackVersion=1` описывает исходный payment manifest, не активную callback configuration.
- `PriorPartialIdentity`: readonly `{refundId:string,providerReference:string,providerEventId:string}`; `PriorPartialProof` добавляет `cashEventId:string,amountMinor:700,totalMinor:700,providerVerified:true,webhookVerified:true,journalVerified:true,callbackDeliveryVerified:true`.
- `RemainderIdentity`: readonly `{refundId:string,providerReference:string,providerEventId:string,amountMinor:1300,totalMinor:2000}`; `RemainderProof` добавляет `cashEventId:string,providerVerified:true,webhookVerified:true,journalVerified:true,callbackSignatureVerified:true`.
- `FinalRemainderProof`: readonly `{priorPartialVerified:true,priorCallbackDeliveryVerified:true,remainderConfirmed:true,providerVerified:true,webhookVerified:true,journalVerified:true,callbackSignatureVerified:true,finalTotalVerified:true}`.
- `StoppedContainers`: readonly record пяти `RestoreService` к `RetainedContainer`; сохранённые image/ID/mounts/ports исходные, только stopped ordinary StartedAt может законно стать новее. Docker labels независимо проверяет Task2, они не добавляются в существующий RetainedContainer shape. Existing original types переносятся в shared retained declaration с прежним re-export из old state, не меняя старые consumer imports.

### Task 1: Strict retained continuity and one-shot remainder state

**Files:** Create `infrastructure/stripe-manual-refund-remainder-options.mjs/.d.mts/.test.mjs`, `infrastructure/stripe-manual-refund-remainder-state.mjs/.d.mts/.test.mjs`, `infrastructure/stripe-manual-refund-retained.mjs/.d.mts/.test.mjs`, `infrastructure/fixtures/stripe-manual-refund-remainder-state-fixture.mjs`. Modify только pure-decoder wiring в `infrastructure/stripe-manual-refund-state.mjs/.d.mts` и связанные old state tests при необходимости; старый constructor/mutation policy не меняется.

**Interfaces:**

- `manualRefundRemainderOptIn(env:unknown):boolean`; `validateManualRefundRemainderOptions(value:unknown):Readonly<RemainderOptions>|null` из options.
- Pure retained exports: `parseRefundRetainedJSON(text:string):unknown`, `decodeOriginalRefundTarget(value:Readonly<{runtime:unknown,provision:unknown}>,context:Readonly<{root:string,originalDirectory:string,daemon:string}>):Readonly<OriginalRefundTarget>`, `decodeOriginalRefundEnv(text:string,final:boolean,ports:OriginalRefundTarget['ports']):Readonly<Record<string,string>>`. Это внутренний parser caller-provided текста, без чтения пути/credentials capability; maps не выходят из state.
- `createManualRefundRemainderState(options:RemainderOptions,deps?:Record<string,unknown>):Readonly<RemainderState>` — inert sync construction. `admit(limits):Promise<'READY'|'DENIED'|'PENDING'>`; `target():Readonly<RemainderTarget>` только после READY; `recordStopped(value:StoppedContainers,limits):Promise<true>`; `beginRestore(service,limits):Promise<true>`; `ackRestore(service,startedAt:string,limits):Promise<true>`.
- Остальные state methods: `confirmBaseline(proof:PriorPartialProof,limits)`, `beginRotation(limits)`, `ackRotation(version:3,limits)`, `beginConfigure(version:3,limits)`, `ackConfigure(version:3,limits)`, `ackRemainder(value:Readonly<{refundId:string}>,limits)`, `confirmRemainder(proof:RemainderProof,limits)`, `confirmFinal(proof:FinalRemainderProof,limits)` — все `Promise<true>`.
- `beginRemainder(limits):Promise<Readonly<{amountMinor:1300,key:string,phase:'DISPATCHING',refundId:null,providerReference:null}>>`; нет index или amount input.
- `withRetainedAdmin(callback:(v:Readonly<{username:string,password:string}>)=>true|Promise<true>,limits):Promise<true>`, `verifyRetainedWebhookSecret(candidate:string,limits):Promise<true>`, `close(cleanupLimits):Promise<true>`. Close терминален даже при invalid context, zeroes private references, drains late file work; sticky files не удаляются.
- Fixture export `fixture(t)` следует существующему synthetic state fixture, добавляет точный prior state/lock и expired prior deadline. Возвращает `{root,original,prior,data,options,limits,deps,statePath,lockPath,priorPaths,create()}`; `priorPaths` фиксированно перечисляет synthetic исторические файлы для byte-comparison. Все clocks/processes внедрены; real network/process defaults заблокированы tests.

- [ ] **1 RED:** `opts require the sole remainder mode without evaluating getters`; `admission reads only pinned prior700ACK/1300READY after clean source and canonical socket`; `expired prior budget and matching old lock are mandatory`; `new UUID cannot bypass a sticky continuation fence`; `close never changes prior bytes or removes the new fence`. Основной assertion после admissible fixture: `await state.admit(f.limits)==='READY'`, `state.target().priorRefundId===prior.legs[0].refundId`, beginRemainder до baseline/configure отвергается; повторный create/admit с другим UUID не READY. Capture bytes/inodes всех priorPaths до/после; ни один старый файл не открывается для записи.

```js
const state=f.create();
assert.equal(await state.admit(f.limits),'READY');
assert.equal(state.target().remainderKey,'s5a-refund:34941d87-12d3-4c74-8c5a-b62b7c914390:remainder');
assert.equal(await state.close({deadline:f.limits.deadline+120000}),true);
assert.deepEqual(await Promise.all(f.priorPaths.map(path=>fs.readFile(path))),priorBytes);
assert.equal((await fs.stat(f.lockPath)).mode&0o777,0o600);
```

- [ ] **2 Verify RED:** `node --test --test-timeout=30000 --test-concurrency=1 infrastructure/stripe-manual-refund-remainder-options.test.mjs infrastructure/stripe-manual-refund-remainder-state.test.mjs`; ожидается meaningful missing implementation/new assertions failure, не случайный syntax/env failure. Записать RED evidence.
- [ ] **3 GREEN:** Extract только прежние strictJSON/runtime/provision/env pure decisions в retained unit; old state передаёт им уже bounded-read строки и получает тот же target/validation. New state допускает фиксированные prior lock/state со strict duplicate/shape/owner/inode/mode checks из spec§3–4. HEAD/original-source ancestry/socket проверяются до retained reads; prior source сверяется с lock/существующим ancestor commit сразу после чтения pinned prior state и до credential env. Требуются expired prior deadline+120000;700ACK/proof absent и1300READY/null refs. Сравниваются оба deterministic prior keys и original identities. До sticky creation всё read-only; после любого неопределённого exclusive create/fsync результат PENDING, не DENIED. DENIED допустим только при доказанном отсутствии acquired side effects; созданная state остаётся закрываемой в любом исходе.
- [ ] **4 GREEN durability:** На каждом state method проверять single-use phase, original Limits, retained file stability и owned state/fence. RecordStopped принимает только immutable-matching original ordinary projections со stopped StartedAt≥original и≤now; helpers не включены и не изменяются. Baseline/full proof требуют exact own data fields и все true. Только наш state получает новые transitions; no old ACK promotion. Atomic owner0600 temp→fsync→rename→directory fsync; no secret persistence. Fence не удаляется после ошибки или успеха.
- [ ] **5 Verify GREEN:** Добавить cases symlink/hardlink/owner/mode/size/duplicate JSON/env/inode replacement/partial read; changed old lock/source/amount/key/restore/rotation/proof, prior budget not expired,1300 DISPATCHING/ACK/CONFIRMED; concurrent admits, partial fsync, late open after abort, skip/repeat/renewed limit, copied/frozen outputs/no accessors/no private canaries. Run new tests плюс `stripe-manual-refund-state.test.mjs`, `stripe-manual-refund-options.test.mjs`, `stripe-manual-refund-schema.test.mjs`, `stripe-form-diagnostic-settlement` coverage через related lifecycle tests. Existing fixtures с исходным700→1300 и zero-refund остаются unchanged/green.
- [ ] **6 Independent review and commit:** отдельный reviewer по spec§2–4, own-data/private-file boundary и compatibility; root сверяет diff и fresh focused result. Scoped commit `feat: fence one-shot Sandbox remainder state`; никакого direct entry/runtime call.

### Task 2: Owned restoration after the previous runtime restart

**Files:** Create `infrastructure/stripe-manual-refund-remainder-runtime.mjs/.d.mts/.test.mjs`. Reuse `stripe-sandbox-ownership.mjs` и `stripe-form-diagnostic-settlement.mjs` без расширения их public policies. Old runtime не получает новое StartedAt правило/stop timeout.

**Interfaces:** `createOwnedManualRefundRemainderRuntime(input:Readonly<{options:RemainderOptions,state:RemainderState}>,deps?:Record<string,unknown>):Promise<Readonly<RemainderRuntime>>`, inert constructor; `resources():Readonly<RemainderTarget>`, `restore/verify/verifyForwardTarget(limits):Promise<true>`, `withAdminCredentials(callback,limits):Promise<true>`, `verifyWebhookSecret(candidate,limits):Promise<true>`, `stop(cleanupLimits):Promise<true>`. Credentials callback shape Task1; no raw Docker/env projection.

- [ ] **1 RED:** `ordinary stopped StartedAt may advance but helper StartedAt may not`; `all immutable topology is re-proved before captured stopped state and every start`; `late start after abort is independently drained and stopped`; `stop grace10 has bounded20 transport under the original cleanup ceiling`. Synthetic process args assertion permits only exact `container start/stop` IDs plus narrow inspect/ps/Git; no real Docker.

```js
assert.deepEqual(startedServices,['postgres','mailpit','callback-receiver','demo-site','backend']);
assert.equal(startedServices.some(name=>name.startsWith('issuer-')),false);
assert.equal(stopInvocation.options.timeout,20000); // controlled clock, >20s cleanup remains
assert.equal(stopInvocation.args.includes('10'),true); // Docker stop grace
```

- [ ] **2 Verify RED:** `node --test --test-timeout=30000 --test-concurrency=1 infrastructure/stripe-manual-refund-remainder-runtime.test.mjs`; новая normal restoration должна содержательно отличаться от старого exact-initial-StartedAt policy.
- [ ] **3 GREEN:** По source/socket/blob/labels/images/mounts/ports/network/volumes повторить существующий strict ownership contract. До старта five ordinary exited, helpers exact exited0/original StartedAt; recordStopped один раз после полной свежей projection. Subsequent pre-start observations должны совпадать с captured mutable StartedAt. Порядок start фиксирован postgres/mailpit/callback-receiver/demo-site/backend, durable beginRestore перед syscall, fresh live proof и ackRestore(actualStartedAt) после. No extra resources, no fallback port, no adoption already-live service. Только new mode допускает подтверждённые исторические ordinary restarts.
- [ ] **4 GREEN cleanup:** Track raw calls, child close, late file/socket/server acquisitions и pending starts вне canceled waiter; original deadline единственна. При stop fresh ownership отдельно для каждого dispatched service, reverse-order independent attempts. Command timeout для stop≤min(20000,remainingCleanup), Docker grace10; остальные commands≤10s. Ни timeout callback без child close, ни status false/unknown не доказывают runtime_closed. Все raw IO/resources drained или report остаётся PENDING.
- [ ] **5 Verify GREEN:** Wrong ID/image/user/mount/label/full project set, changed network/volume/receiver bytes/socket/source, future/regressive/flapping StartedAt, occupied port, live helper, starts before fsync/late starts/late child close, failed first stop не подавляет другие. Run new runtime+state tests и existing `stripe-manual-refund-runtime.test.mjs`, `stripe-sandbox-ownership.test.mjs`, `stripe-sandbox-bootstrap.test.mjs`, `stripe-sandbox-owned.test.mjs`, `stripe-manual-form-runtime.test.mjs`, `stripe-form-diagnostic-runtime.test.mjs`, только injected/offline cases.
- [ ] **6 Independent review and commit:** review spec§5/9 и output safety; root targeted recheck, commit `feat: restore retained runtime for one-shot remainder`; старый runtime source/policy unchanged.

### Task 3: Separate read-only Stripe proof for an already partial refund

**Files:** Create `infrastructure/stripe-manual-refund-remainder-cli.mjs/.d.mts/.test.mjs`; modify `infrastructure/stripe-sandbox-cli.mjs/.test.mjs` только для private transport reuse и named new factory. Existing manual/full/zero-refund factory contracts не меняются. New unit — purpose-specific re-export/type boundary над новым named factory, не второй скопированный OAuth/listener implementation.

**Interfaces:** `createManualRefundRemainderCliAdapter(input:RemainderCliOptions,deps?:Record<string,unknown>):Promise<Readonly<RemainderCli>>`, exported из new CLI module; `RemainderCliOptions` exact readonly `{env:RemainderOptions['env'],originalRunId:'031b5ae7-6603-4339-b1b3-77231291ff34',priorRunId:'34941d87-12d3-4c74-8c5a-b62b7c914390',cliDirectory:string,backendPort:number,verifyForwardTarget:RemainderRuntime['verifyForwardTarget'],persistWebhookSecret:RemainderRuntime['verifyWebhookSecret'],approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}}`.

`RemainderCli` methods: `validateBinding/startListener(limits):Promise<true>`, `bindRemainderTarget(target:RemainderTarget,limits):Promise<true>`, synchronous `assertRemainderListener(limits):true`, `verifyPriorPartial(value:PriorPartialIdentity,limits):Promise<Readonly<{providerVerified:true,webhookVerified:true}>>`, `inspectRemainder(value:RemainderIdentity,limits):Promise<Readonly<{providerVerified:true,webhookVerified:true}>>`, `stopListener(cleanupLimits):Promise<true>`. Нет login, arbitrary get, writes или caller proof boolean. `bindRemainderTarget` разрешён один раз после listener, проверяет exact target/identities и не считает partial proof подтверждённым. `verifyPriorPartial` принимает только identity с refundId=target.priorRefundId и впервые seeds reference только после своих retrieve+Event+aggregate; повтор разрешён только для той же identity и заново проверяет aggregate700. `inspectRemainder` требует собственный verified partial seed, amount1300/total2000 и новый distinct Refund reference.

- [ ] **1 RED:** `old bindCapturedPayment still refuses partial700`; `new factory has no login/write capability and refuses bad opt-in before deps`; `caller partial boolean/reference cannot seed without exact Refund Event and aggregate`; `only exact same verified partial may be rechecked before the remainder`. Cases included в new+old CLI tests.

```js
assert.equal('login' in cli,false);
assert.equal('completeLogin' in cli,false);
// Already bound target/listener, but the synthetic prior Event is invalid.
await assert.rejects(cli.verifyPriorPartial(priorIdentity,limits));
await assert.rejects(cli.inspectRemainder(remainderIdentity,limits));
assert.equal(commands.some(args=>args.includes('post')),false);
```

- [ ] **2 Verify RED:** `node --test --test-timeout=30000 --test-concurrency=1 infrastructure/stripe-manual-refund-remainder-cli.test.mjs infrastructure/stripe-sandbox-cli.test.mjs` на injected process/credential-stat projections, no actual CLI.
- [ ] **3 GREEN:** Narrow transport shares existing isolated config/path/sandbox-exec/no-global-keychain, child-close ownership, fixedAccount/API/CLI/version, private verifier equality и event allowlist. Factory selection — internal closed purpose, не операторский boolean/mode, который можно передать old factory. Expired credentials дают refusal без OAuth. Fixed get allowlist только исходный Intent with expanded charge/refunds, bound exact Refund IDs и bound Event IDs; no --live/latest/print-json/skip-verify или mutable Config.Env.
- [ ] **4 GREEN proof:** До seed exact captured2000/test/card/automatic/null customer, metadata/site/payment, complete refs list ровно700 refund, amount_refunded700/refundedfalse, exact700 Refund SUCCEEDED/refund_id metadata и matching pinned Event; отсутствие необязательного Refund.livemode допустимо только при подтверждённом test Intent/Charge/Event. Final1300 independently checks exact700+1300, aggregate2000/refundedtrue, original charge/event continuity и оба bound references. No amount-only adoption, pagination truncation, wrong version/account/type/refund metadata, terminal/reconciliation-only shortcut. Listener owner/pid/readiness invalidated permanently on loss; sync fence не делает async IO.
- [ ] **5 Verify GREEN:** Seed never granted after false/missing proof, inconsistent aggregate/has_more/extra refunds, changed original Event/ref, null charge/refund ID, one wrong field, expired/mixed keychain config, verifier mismatch, listener loss between reads и financial call, late spawn/exit/error/stdout after abort. Recheck exact700 before POST allowed; после1300 method verifyPriorPartial не принимает2000 вместо ожидаемых700. Related tests: old CLI, new runtime/state, `stripe-manual-payment.test.mjs`, `stripe-sandbox-owned.test.mjs`.
- [ ] **6 Independent review and commit:** Review spec§6, no credential/write authority escape и unchanged old zero-refund guard; root fresh targeted verification. Commit `feat: verify retained partial refund through scoped Stripe reads`.

### Task 4: One version3 rotation and one EUR13 settlement session

**Files:** Create `infrastructure/stripe-manual-refund-remainder-session.mjs/.d.mts/.test.mjs`. No changes to old session financial policy. Existing immutable validators/settlement may be imported, but не вызывается старый refund session/entry и не копируется старый two-leg controller.

**Interfaces:** `createManualRefundRemainderSession(input:Readonly<{runtime:RemainderRuntime,cli:RemainderCli,state:RemainderState}>,deps?:Record<string,unknown>):Promise<Readonly<RemainderSession>>`, inert. `verifyBaseline(limits):Promise<Readonly<PriorPartialProof>>` only first full admission; `rotateCallback(limits):Promise<true>`; `refundRemainder(limits):Promise<Readonly<RemainderProof>>`; `verifyFinal(limits):Promise<Readonly<FinalRemainderProof>>`; `diagnostic():Readonly<ManualRefundDiagnostic>|null`; `close(cleanupLimits):Promise<true>`. Internal repeated baseline refresh не вызывает state.confirmBaseline повторно, but compares pinned initial identities and re-verifies CLI700 until dispatch.

- [ ] **1 RED:** `success uses one rotation version3 and only the original1300 key`; `old700 callback inspection is neither required nor fabricated`; `missing or changed prior proof cannot reach the1300 fetch`; `straddled detail/list is incomplete, terminal facts still fail before a later valid response`. Synthetic fetch records exact routes/keys/bodies, asserts no700/refund/new payment/reconcile/callback retry paths. Stubbed state must validate phase order; synthetic signed receiver integration отдельно доказывает callback1300, без real Admin/Stripe.

```js
const refunds=requests.filter(r=>r.method==='POST'&&r.path.endsWith('/refunds'));
assert.equal(refunds.length,1);
assert.deepEqual(refunds[0].body,{amount_minor:1300,currency:'EUR',reason_code:'REQUESTED_BY_CUSTOMER'});
assert.equal(refunds[0].key,'s5a-refund:34941d87-12d3-4c74-8c5a-b62b7c914390:remainder');
assert.equal(requests.filter(r=>r.path.endsWith('/rotate-secret')).length,1);
```

- [ ] **2 Verify RED:** `node --test --test-timeout=30000 --test-concurrency=1 infrastructure/stripe-manual-refund-remainder-session.test.mjs`; assertions fail on absent/new implementation, no mocked success silently replacing real proof graph.
- [ ] **3 GREEN baseline and rotation:** Allowlist только прежние protected auth/session/accounts/sites/catalog/payment detail/list/provider-operations reads, exact one Site rotate and exact payment refunds POST, plus owned receiver configure/inspection. Credentials только в login body, Cookie/XSRF parsing из tested cookie-list contract; redirect:error, bound body≤64KiB/request≤10s, no raw errors. Verify frozen current Admin ID at each binding. Protected baseline spec§6 и CLI verifyPriorPartial required before `state.confirmBaseline`; current AdminPaymentViews Outbox/Delivery НЕ содержит secret version, поэтому не выдумывать proof version2 по отсутствующему полю. Проверить exact historical payment/refund204 delivery and cash chain, not old receiver HMAC. Refresh700 baseline/CLI до и после rotation; one fsynced rotation POST ожидает Site/endpoint/enabled/version3, one configure204/ACK3, secret only transient memory. Любой unexpected/unknown response — ноль refund POST, no repair/second rotation.
- [ ] **4 GREEN dispatch and proof:** После нового refresh/coherent700/available1300, exact actor, version3 configure, durable beginRemainder → synchronous listener fence непосредственно перед fetch → one1300 POST с сохранённым unused key. 202 только ACK valid new UUID; ack durable failure stops. Poll≤30/45s; exact full2000/two-refund/local requested/success events/order/provider ops/journal/outbox/delivery и freshly signed1300 receiver receipt. Допустим только forward reservation tuple из spec§8 с nondecreasing timestamp/unchanged rest; mismatch does not certify completion. Already-returned terminal/foreign/reconciliation-only/history changes проверяются before incomplete return. CLI inspectRemainder перед state.confirmRemainder и снова при final; final rechecks700 immutable baseline and new1300 facts before state.confirmFinal.
- [ ] **5 Verify GREEN:** Wrong prior ID/reference/Event/source/reservation/order/actor, dup cash/outbox, wrong total/availability, has_more, missing Event, terminal response followed by valid, wrong callback event ID, foreign receiver events; rotation wrong version/endpoint/enabled/unknown response, configure failure, baseline changes during rotation; lost202/fsync, listener failure precisely before fetch, concurrent/repeat/abort/renewed limits. Exact counts success: one rotation, one configure, one refund POST1300, zero700/new intents/retries. Session diagnostics use prior finite vocabulary, first failure survives close; secret/cookie canaries absent in report/errors and no getters evaluated. Cancel/drain late HTTP/body disposal before cleanup success. Run Tasks1–4 plus old `stripe-manual-refund-session.test.mjs`, `stripe-manual-refund.test.mjs`, `callback-receiver.test.mjs`, `smoke-stage-5a.test.mjs` and `stripe-form-diagnostic-provision.test.mjs` offline.
- [ ] **6 Independent review and commit:** Review spec§6–9, cash/signature proof scope, no unconfirmed continuation и Review Focus3–5; root related regressions. Commit `feat: verify one-shot Sandbox remainder settlement`.

### Task 5: Sole opt-in entry, finite report and offline release gate

**Files:** Create `infrastructure/stripe-manual-refund-remainder-schema.mjs/.d.mts/.test.mjs` и `stripe-manual-refund-remainder.mjs/.d.mts/.test.mjs`; modify `docs/local-development.md`. Old guards/snapshots: `stripe-manual-refund-options.mjs`, `stripe-manual-refund.mjs`, `stripe-manual-payment-options.mjs`, `stripe-manual-payment.mjs`, `stripe-manual-form-options.mjs`, `stripe-manual-form.mjs`, `stripe-sandbox-owned.mjs`, `stripe-sandbox-bootstrap.mjs`, `stripe-sandbox-cli.mjs`, `stripe-sandbox-acceptance.mjs`, `stripe-sandbox-provision.mjs`, and corresponding existing tests. `stripe-form-diagnostic.mjs` inherits its guard from owned; test its exported entry too. No broadened SDK/frontend spec discovery.

**Interfaces:** `validateManualRefundRemainderReport(value:unknown):Readonly<ManualRefundRemainderReport>|null`; `formatManualRefundRemainderReport(value:unknown):string|null`. Exact fields/values spec§9; reuse `ManualRefundDiagnostic` validator without changing its old vocabulary. `ManualRefundRemainderResult={readonly report:ManualRefundRemainderReport,readonly exitCode:0|1}`.

`ManualRefundRemainderReport`: version1, mode MANUAL_REFUND_REMAINDER, result FAILED/PENDING/SUCCEEDED, phase ADMISSION/RESTORE/BINDING/LISTENER/BASELINE/ROTATION/REMAINDER/VERIFY/CLEANUP, provenance CONTROLLER_REPORTED, optional finite diagnostic только на unconfirmed result. Точные boolean keys: `target_verified`, `prior_partial_verified`, `prior_callback_delivery_verified`, `callback_rotated`, `remainder_confirmed`, `provider_verified`, `webhook_verified`, `journal_verified`, `callback_signature_verified`, `final_total_verified`, `listener_closed`, `runtime_closed`. Unknown fields запрещены.

`runManualRefundRemainder(options:RemainderOptions,limits:Limits,deps?:ManualRefundRemainderDependencies):Promise<Readonly<ManualRefundRemainderResult>>`; `runManualRefundRemainderCli(input:Readonly<{env:Record<string,string|undefined>,args:readonly string[]}>,deps?):Promise<Readonly<ManualRefundRemainderResult>>`; `runManualRefundRemainderEntry(input,capabilities:Readonly<{stdout:NodeJS.WritableStream,exit:(code:0|1)=>unknown}>,deps?):Promise<void>`.

Dependencies: `now/setTimeout/clearTimeout`, `createState` Task1, `createRuntime` Task2, `createCli` Task3, `createSession` Task4, `write:(text:string)=>Promise<unknown>`, `subscribe:(stop:()=>void)=>()=>void`. Each factory signature exactly matches its earlier Interfaces; no new generic HTTP/provider/file capability. Controller stores inert/acquired resources before waiting on admit/restore to retain late cleanup ownership.

- [ ] **1 RED:** `report differentiates freshly verified prior delivery from new signature proof`; `admission denial is FAILED only with no acquired side effects`; `any acquired/unknown fence or financial operation stays PENDING`; `all old modes reject remainder flag before snapshots strip it`; `final stdout/exit cannot report success if write stalls or child/resources remain`. Child entry tests run with synthetic injected capabilities and empty opt-ins, never actual runtime.

```js
assert.equal(validateManualRefundRemainderReport({...validSucceeded,prior_partial_verified:false}),null);
assert.equal(validateManualRefundRemainderReport({...validSucceeded,callback_signature_verified:false}),null);
assert.equal(validateManualRefundRemainderReport({...validSucceeded,partial_confirmed:true}),null);
assert.equal(validateManualRefundRemainderReport({...validSucceeded,runtime_closed:false}),null);
```

- [ ] **2 Verify RED:** `node --test --test-timeout=30000 --test-concurrency=1 infrastructure/stripe-manual-refund-remainder-schema.test.mjs infrastructure/stripe-manual-refund-remainder.test.mjs`.
- [ ] **3 GREEN controller:** Validate raw own-data flags/args first, bind original≤30min active+2min cleanup once; create state → admit → runtime restore → CLI binding/listener/bindRemainderTarget → session baseline → rotation3 → remainder1300 → final. Reflect strict proofs only, no boolean spread from unvalidated dependency result. In finally independently session close, CLI stop, runtime stop, state close, awaiting raw acquisitions/settlement; close failure latches PENDING and cannot remove sticky fence. First failure diagnostic remains. State admit DENIED before effects returns FAILED after proved cleanup; PENDING/unknown may never be demoted to FAILED. No hook opens old entry/browser/OAuth.
- [ ] **4 GREEN report and compatibility:** version1/mode MANUAL_REFUND_REMAINDER with twelve exact boolean fields spec§9. Callback rotation requires target+prior partial+prior delivery; remainder confirmed also requires rotated and all new provider/webhook/journal/signature proofs; final_total_verified requires remainder; success requires all twelve true and VERIFY/CLEANUP phase/exit0. Diagnostic forbidden on success, copied data only, format≤4096bytes. Snapshot new flag everywhere before old normalization; malformed/accessor flag also refuses without running getters or dependencies. New mode only accepted by dedicated factory/entry. False never means no external effect. End stdout is one bounded JSON; errored/stalled/late write has real nonzero process exit and cannot emit a second success result.
- [ ] **5 GREEN guide:** Add future template only: `env -i` explicit restricted PATH and three required opt-ins plus RUN_ID/EXPECTED_SOURCE/DAEMON; no keys, target IDs, literal UUID/new SHA guessed at runtime, or executable command as a development prerequisite. Explain old key reuse/new sticky lock/old PENDING, prior delivery vs new HMAC, rotation post-write version validation and possible side effect on mismatch, expired CLI stop/separate OAuth approval, no Chrome/cards, no auto repeat/cleanup deletion. Mark actual remainder Sandbox acceptance pending. This guide and this plan are not runtime permission.
- [ ] **6 Verify GREEN and related guards:** Tasks1–5 tests plus all modified old guards/tests and signed synthetic receiver; each failure phase, missing proof, concurrent/late factory result, settled callback before child close, failed first cleanup, elapsed original ceiling, output overflow, prototype/accessor/canary shapes. Assert only one fixed1300 POST and no retry/rotation beyond one even when successful final response arrives after failure.
- [ ] **7 Final root checkpoint:** На Node24 выполнить fresh full safe suite и declaration check ниже; inspect all new tests for opt-in/default actual boundary before running. Root spec-to-task audit, staged secret audit (only generated synthetic sentinels), diff/source completeness; all five task reviews resolved, whole-correction independent reviewer approval. Unknown real state не открывать ради этих проверок. Если verification runner missing pnpm/uses wrong Node, исправить только runner environment/offline path, записать failed+passing evidence, не маскировать exclude product regressions.

```bash
# repository root, Node24; no real Sandbox flags/credentials, Docker absent from test PATH
node --test --test-reporter=tap --test-timeout=30000 --test-concurrency=1 --test-skip-pattern='Docker context excludes|rendered local backend identity|opt-in Compose binds|opt-in Compose fails' infrastructure/*.test.mjs
node examples/demo-site/node_modules/typescript/bin/tsc --noEmit --strict --module nodenext --moduleResolution nodenext --target es2022 --types node --typeRoots examples/demo-site/node_modules/@types infrastructure/stripe-manual-refund-retained.d.mts infrastructure/stripe-manual-refund-state.d.mts infrastructure/stripe-manual-refund-remainder-options.d.mts infrastructure/stripe-manual-refund-remainder-state.d.mts infrastructure/stripe-manual-refund-remainder-runtime.d.mts infrastructure/stripe-manual-refund-remainder-cli.d.mts infrastructure/stripe-manual-refund-remainder-session.d.mts infrastructure/stripe-manual-refund-remainder-schema.d.mts infrastructure/stripe-manual-refund-remainder.d.mts
git diff --check
```

Эта explicit skip-pattern исключает существующие три Docker-context builds, один actual rendered UID container check и два opt-in Compose config cases. Не заявлять их пройденными. Остальные tests, включая signed local receiver и guarded Playwright discovery, обязательны. Появление новых actual Docker tests требует их отдельной классификации ДО выполнения, а не общего skip runtime failure. Maven/SDK/Admin/demo build не перезапускаются при неизменных product files.

- [ ] **8 Independent review and commit:** Root проверяет actual report/counts/exit/version и reviewed source hashes; scoped commit `feat: expose guarded manual Sandbox remainder mode`. Никакого push/merge/PR/Sandbox. Отчёт перечисляет точные выполненные и excluded проверки; финансовое состояние только историческое, реальная новая acceptance pending.

## Self-review и handoff

Spec§1–2 → все задачи; §3–4 → Task1+5; §5 → Task2; §6 → Task3–4; §7–8 → Task4; §9 → Tasks1–5; §10 → каждая task gate/Task5; §11 → guide и текущий handoff. Review Focus1 → Task1/5,2 →Task2,3 →Task3/4,4 →Task4,5 →Task4/5.

План требует review пользователя. После подтверждения сохраняется уже выбранный subagent-driven workflow; execution method заново не запрашивается. Каждая зависимая задача начинается только после review предыдущей, не параллельно на общей state/CLI/session surface. В конце — local commit и safe handoff; одна реальная попытка с restore/listener/rotation3/refund1300 требует следующего отдельного разрешения пользователя.
