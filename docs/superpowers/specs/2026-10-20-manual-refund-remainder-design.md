# Одноразовое завершение возврата EUR13 в Stripe Sandbox

Статус: полное описание подтверждено пользователем в чате 2026-10-20 после независимой проверки. Согласованы отдельный режим для оставшихся EUR13 и одна новая ротация callback-ключа 2 → 3 только исходного тестового Site. Следующий шаг — согласование implementation plan перед продуктовым кодом. Текущая работа строго офлайн; подтверждение описания не разрешает настоящий запуск Sandbox.

## 1. Зачем нужен отдельный режим

Исходная анонимная CATALOG/CARD оплата EUR20 прошла успешно. В последующей ограниченной проверке возврат EUR7 был ACKNOWLEDGED, но контроллер закончил с PENDING до разрешения второй операции. Независимые read-only проверки позже подтвердили Stripe Refund SUCCEEDED/700, локальный PROVIDER_EVENT/REFUND_SUCCEEDED и доставку callback HTTP204 с версией2. EUR13 не был отправлен в том наблюдении. Это исторические сведения, не текущая authority и не основание переписать прежний отчёт.

Новый режим заново доказывает именно эту исходную оплату и существующий возврат EUR7, затем может один раз отправить только оставшиеся EUR13. Полный результат: исходный Payment REFUNDED, aggregate refunded_amount_minor=2000, ровно два точных Refund на700 и1300. Никакого нового списания, Checkout, PaymentIntent, ввода карты или браузерного шага.

Callback receiver хранит ключ только в памяти; остановка уничтожает возможность заново проверить подпись прежнего callback. Configure существующего Site не раскрывает старый secret, GET reveal отсутствует. Поэтому согласована одна новая попытка ротации того же Site с ожидаемым результатом version3. Не добавляем API раскрытия, не извлекаем/дешифруем secret из БД, не сохраняем его в файл. Новая подпись доказывается для возврата EUR13; старое receiver inspection не воссоздаётся.

## 2. Жёстко заданные цели и запреты

- Исходный payment run: `031b5ae7-6603-4339-b1b3-77231291ff34`, исходный runtime source: `f0620e40004162d2b3eededa60512f50a492f4cc`.
- Единственный предшествующий refund run: `34941d87-12d3-4c74-8c5a-b62b7c914390`. Другие abandoned runs не принимаются.
- Account `acct_1UPiMpELswAd8tpR`, account key `stripe-sandbox-default`, API `2026-09-30.endive`, CLI `1.52.0`, только test mode, EUR2000, source CATALOG, одна исходная CARD attempt.
- Site/Payment/Price/Intent/старый Refund UUID берутся только из проверенной continuity сохранённых артефактов и свежих protected reads; оператор не передаёт IDs, суммы, ключи, URL или пути credentials.
- Сумма единственного нового refund POST —1300 minor EUR, reason_code REQUESTED_BY_CUSTOMER, без note/PII. Возврат700 невозможен через новую capability.
- Нельзя перезапускать старый refund entry, снимать/переписывать его fence, переводить старую ACKNOWLEDGED leg в CONFIRMED, переписывать старый PENDING, сбрасывать состояние или начинать прежние две операции с новым UUID.
- Нет общего resume/retry framework, новых платежей/донатов/3DS/подписок, reconciliation/cancel, provider writes из CLI/Dashboard, retry callbacks, смены endpoint/enabled/Stripe keys/API version и изменений других Sites.
- Нет удаления/пересоздания Docker ресурсов, build/pull/up/prune/down, старта issuer helpers, новых Admin/issuer keys, извлечения Docker Config.Env или broad private-file/DB reads.

Сейчас запрещены реальные Docker/Compose, Stripe CLI/OAuth/listener, provider/Admin/финансовые запросы и чтение настоящих run/env/credentials. Разработка и проверки используют только synthetic fixtures и изолированные loopback doubles; внешняя сеть в этих тестах запрещена.

## 3. Новая authority без обхода старой блокировки

Решение проектирования: отдельный direct entry и mode `MANUAL_REFUND_REMAINDER`; не новый параметр старого режима. Требуются ровно три opt-in флага ENABLED, ALLOW_TEST_OPERATIONS и MANUAL_REFUND_REMAINDER=true с префиксом PAYMENT_STRIPE_SANDBOX_. Все старые mode/debug flags запрещены; все старые entries должны отклонять новый flag до нормализации, private/dependency access. Imports инертны, direct entry имеет ноль positional args.

Оператор задаёт только новый lowercase UUID, reviewed clean controller HEAD и прежний canonical owned Unix Docker socket. Новый UUID не совпадает ни с payment run, ни с предыдущим refund run. Новый owner-only каталог содержит собственную state и report; expectedSource должен совпасть с clean HEAD. Оба сохранённых source commits должны существовать и быть предками нового controller, а source прежнего refund state должен совпасть с source его lock. Source runtime images не заменяется новым SHA.

Старая `manual-refund.lock` в исходном payment каталоге остаётся существующей и неизменной. Её содержимое обязано указывать ровно на предшествующий refund run и согласованный с его state source. Старый active deadline+120000 уже истёк: новая операция не продлевает старую authority. Ни старый lock, ни прошлый state не предоставляют capability нового финансового действия.

Дополнительно эксклюзивно создаётся `manual-refund-remainder.lock` в исходном payment каталоге. Она связывает новый run/source с ровно этими двумя историческими runs и исключает другой continuation UUID. После создания она сохраняется даже после успешного завершения или частичной ошибки admission; автоматического удаления/reset/resume нет. До её создания проверки read-only; два конкурента не должны дойти до restore/mutations. При частичном fsync/create или неизвестном исходе admission — fail closed с сохранёнными доказательствами.

Исходные payment runtime/provisioning/app.env/final.env, prior refund lock/state остаются побайтно неизменными. Новая capability заново сверяет их file identity/stability перед каждым разрешённым действием. Сохранённый report, operational Markdown и bool pending=false не доказывают, что операции не было, и никогда не превращают старый результат в SUCCEEDED.

## 4. Ограниченное чтение и durable state

Будущий loader читает только фиксированные runtime.json, form-provisioning.json, app.env, final.env исходного payment каталога; его manual-refund.lock; manual-refund.json ровно предыдущего refund каталога. CLI config остаётся внутри прежней изолированной CLI boundary, контроллер его не читает. Другие локальные секреты и произвольные пути недоступны.

Сохраняются existing safeguards: canonical parents, owner UID, 0700 run directories/0600 private files, regular single-link file, O_NOFOLLOW, lstat/fstat/inode/dev equality, stable size/mtime/ctime, bounded UTF-8 ≤65536 bytes, strict JSON без duplicate members/unknown shape, purpose-specific env parser без shell/eval/substitution. Private env continuity между app/final проверяется в памяти; никакие key values/maps/hashes в stdout, state, error или snapshot tests не попадают.

Prior state допускается только mode MANUAL_REFUND/version1 с указанными originalRunId/originalSource/Site/Payment, завершёнными ACKNOWLEDGED restores и rotation/configure version2. Leg700 строго ACKNOWLEDGED с валидным исходным Refund UUID; leg1300 строго READY, refundId=null, providerReference=null. Оба ключа должны быть точными детерминированными ключами prior run. Pending boolean валиден по схеме, но не предоставляет права повторить неизвестную операцию. Любая DISPATCHING/ACKNOWLEDGED/CONFIRMED leg1300, иные суммы/ключи/UUID/дополнительные proof поля — stop до restore.

Единственный refund key новой операции — уже сохранённый и ещё не использованный `s5a-refund:34941d87-12d3-4c74-8c5a-b62b7c914390:remainder`. Новый UUID не порождает новый financial key. Исходный700 key никогда не используется для записи. READY в сохранённом state — необходимое, но недостаточное условие: его подтверждают текущие server/provider проверки ниже.

Новая state имеет собственные ordered transitions RESTORE → BASELINE_VERIFIED → ROTATION_DISPATCHING → ROTATION_ACKNOWLEDGED(version3) → CONFIGURE_DISPATCHING → CONFIGURE_ACKNOWLEDGED(version3) → REMAINDER_DISPATCHING → REMAINDER_ACKNOWLEDGED → REMAINDER_CONFIRMED → FINAL_VERIFIED. Каждая mutation предваряется fsynced intent; transitions нельзя пропустить или вызвать параллельно/повторно. Исторические700 факты записываются только как новая проверенная baseline projection, не как исправление prior state. До POST ключ фиксирован; после202 сохраняется только валидный новый Refund UUID. Неизвестный dispatch, lost response или lost durable commit защёлкивает PENDING навсегда.

## 5. Восстановление только сохранённой topology

Сохраняются ровно семь исходных container IDs, пять image IDs, network и два volumes, fixed project/service/own labels, mounts, users/capabilities/read-only configuration и прежние loopback ports. Свежая Docker projection не содержит credentials. Лишний контейнер, чужой/занятый порт, missing/replaced identity, изменённый receiver Git blob или неправильный socket — stop, не создание замены.

Решение проектирования: ordinary services могли уже рестартовать в предыдущей проверке, поэтому нельзя требовать их mutable StartedAt равным давнему payment manifest. До первого start все пять обязаны быть exited; новые stopped projections с полными immutable identities фиксируются в новой state. StartedAt должен быть валидным, не раньше исходного, и не меняться между проверками до нашего start. Для issuer-installer/probe сохраняются exact original StartedAt, exited/exit0, ни одного нового старта. Это ограниченное правило нового режима, не ослабление исходного bootstrap/manual-refund.

По exact ID стартуют только postgres → mailpit → callback-receiver → demo-site → backend, с durable intent и fresh ownership/health checks. Наши subsequent reads/stop привязаны к уже наблюдённым новым StartedAt. Immutable topology и byte-for-byte receiver code должны остаться исходными. Admin login использует только retained original credentials через узкую callback capability; текущий authenticated Admin ID фиксируется и остаётся тем же до финансового POST.

В finally прекращаются HTTP/session работы, закрывается listener, независимо останавливаются только exact owned ordinary services, старт которых отправил этот controller. Late starts/response bodies/children наблюдаются до завершения cleanup; несертифицированный stop остаётся false/PENDING. Решение проектирования для нового режима: Docker stop grace10s получает transport ceiling до20s внутри общего cleanup срока, а не timeout, равный самому grace. Это не утверждение об исторической причине runtime_closed=false и не разрешение broad cleanup.

## 6. Listener, baseline и честное разделение доказательств

Отдельная узкая CLI factory нового режима предоставляет только fixed binding, listener и scoped read-only proof. Она не открывает login/complete-login, arbitrary command или writes и не расширяет старую bindCapturedPayment, которая обязана по-прежнему требовать zero refunds. Сохранённые CLI credentials/Account/API/version и webhook verifier проверяются заново; missing/expired/mismatch означает stop до ротации/возврата. Секрет listener только сравнивается с retained final.env в памяти, не перезаписывается. Необходимость будущего OAuth согласуется отдельно.

Перед любой ротацией свежие protected Admin reads доказывают:

- единственные исходные Account/Site/Price/Payment; ACTIVE STRIPE/SANDBOX; CATALOG/EUR2000/NONE; customer/donation/subscription/external_order=null; один успешный исходный CARD attempt и тот же acknowledged PAYMENT_PREPARE/Intent;
- Payment PARTIALLY_REFUNDED, refunded_amount_minor=700, refundable_amount_minor=1300; ровно один Refund700 SUCCEEDED, reason REQUESTED_BY_CUSTOMER, UUID из prior ACK, валидный provider reference;
- один исходный PAYMENT_SUCCEEDED и один REFUND_REQUESTED/700, один REFUND_SUCCEEDED/700 source PROVIDER_EVENT с непустым provider_event_id и exact provider refund reference; неизменённая исходная история, отсутствие чужих/дублирующих денежных фактов;
- ровно исходные ACKNOWLEDGED PAYMENT_PREPARE и REFUND_CREATE/700, совпадающие identities/references, manual_review_required=false; нет неизвестной второй операции/резерва/Refund1300;
- исходный payment outbox и refund700 outbox DELIVERED, соответствующие успешные HTTP204 deliveries; при наличии версии в текущем защищённом view проверяется ожидаемая версия, недоступное поле не выдумывается.

Независимая CLI проверка только исходного Intent/Charge/Refund/Event подтверждает succeeded/paid/captured EUR2000, automatic/card/customer=null/test mode; amount_refunded=700, refunded=false, полный refunds list без has_more, ровно точный700 Refund SUCCEEDED, matching payment_id/refund_id metadata, Charge/Intent и тот же refund.created/refund.updated Event нужной API version/livemode=false. Одни metadata, сумма, старый stdout или UI не достаточны.

Новая baseline допускается только при полном согласии этих доказательств. Provider seed исторического partial reference возникает только после его собственного retrieve+Event+aggregate, не из caller boolean. До/после ротации и прямо перед refund POST baseline проверяется заново; изменившийся набор/сумма/резерв/actor немедленно блокирует POST. Нет общей доверительной функции adopt partial.

Историческая callback delivery700 — server-observed факт. Новое receiver inspection после рестарта пусто и не должно содержать поддельную запись700; PENDING предыдущего контроллера остаётся PENDING. Не повторяем700 callback и не выдаём прошлую HTTP204 за заново проверенную HMAC подпись. Новые webhook/journal/provider/signature booleans относятся к EUR13; исторический partial и его delivery выделены отдельно.

## 7. Одна согласованная ротация ожидаемой версии3

После повторного baseline, live-listener и ownership checks новая state fsync фиксирует ROTATION_DISPATCHING. Один защищённый POST /api/v1/admin/sites/{исходныйSite}/callback/rotate-secret должен вернуть тот же Site, endpoint `http://callback-receiver:8080/callback`, enabled=true, secret_version=3 и валидный ephemeral plaintext secret.

Ограничение текущего API: до записи нет GET текущей callback configuration/version. Историческая version2 и delivery не доказывают, что конфигурация не менялась после наблюдения. Поэтому version3/endpoint/enabled проверяются по ответу ПОСЛЕ единственной разрешённой mutation. Неожиданный ответ означает PENDING и ноль refund POST; ротация уже могла произойти. Нельзя автоматически «исправить» конфигурацию, повторить/откатить ротацию или перейти к1300. Это та же явно ограниченная модель проверки, что у прежнего режима; новый backend endpoint не создаётся.

После durable ACK3 secret передаётся только в памяти в один POST /configure ровно исходному freshly owned receiver. Его204 и durable configure ACK обязательны. Secret не хранится для повторного запуска, не выводится, не попадает в ошибки; после передачи ссылка очищается. При invalid response/timeout/abort/fsync error — PENDING без финансового POST. Остальные Sites, endpoint/enabled и история ключей не меняются.

## 8. Единственный POST EUR13 и окончательная проверка

Перед POST повторно требуются coherent partial baseline700/available1300, те же actor/target/source bindings, configured version3 receiver и живой owned listener. Никакой await не разделяет последний listener fence и отправку fetch. Новая state fsync фиксирует REMAINDER_DISPATCHING с прежним unused remainder key, затем ровно один существующий защищённый POST /api/v1/admin/payments/{исходныйPayment}/refunds: amount_minor1300/currencyEUR/reason_codeREQUESTED_BY_CUSTOMER.

202 — acknowledgement, не settlement. Новый Refund UUID обязан отличаться от700, иметь тот же Payment/amount/currency и допустимое начальное состояние; только после durable ACK разрешаются scoped reads его UUID. Timeout, потеря ответа/ACK, invalid body, late callback после abort или любой неизвестный исход запрещают второй POST с тем же или другим key. Новый mode тоже одноразовый и не возобновляется новым UUID.

Подтверждение требует coherent detail/list: Payment REFUNDED/refunded2000/available0; ровно Refund700+1300 SUCCEEDED с точными UUID/references; два REFUND_REQUESTED и два REFUND_SUCCEEDED без дополнительных cash facts; исходная история700 и оплаты неизменна, reservation1300 после исходного700 success. Refund1300 success — именно PROVIDER_EVENT с непустым provider_event_id. RECONCILIATION-only success не заменяет webhook proof и оставляет PENDING.

Допустим лишь reservation-aware переход `(PARTIALLY_REFUNDED,700,0)` → `(REFUNDED,2000,0)` между раздельными reads при exact immutable facts и nondecreasing updated_at. Такой несовпадающий probe неполон и никогда не выдаёт success; нужен свежий coherent probe. Terminal/foreign/wrong-money/reconciliation-only факты проверяются до неполноты и не скрываются последующим корректным чтением.

Новый1300 cash event связывается по exact ID с DELIVERED outbox, успешной delivery и signature-verified receiver inspection `refund.succeeded`. Получатель после настройки3 не обязан и не может подтверждать старую подпись2. Только разрешённые duplicates уже известных events допустимы, не дополнительные денежные факты.

Отдельный CLI retrieve подтверждает точные Refund1300/Event, исходные700 Refund и Intent/Charge: aggregate2000, refunded=true, полный список ровно двух references, суммы700/1300, correct metadata/identities/test mode/API version. Только затем fsync REMAINDER_CONFIRMED. Final verify заново сверяет обе history projections, aggregate/provider binding и новый callback; после этого FINAL_VERIFIED. Browser/ACK/local state отдельно не достаточны.

## 9. Сроки, отчёт и остановка

Решение проектирования: одна новая active authority ≤30min с начала нового entry, cleanup только до этой deadline+2min. Старый expired deadline не возобновляется и не меняется. HTTP/provider reads ≤10s, отдельная proof phase ≤30 probes/45s внутри active deadline; ноль automatic financial retries. Cleanup не разрешает новую ротацию, запись денег или business verification reads.

Один конечный JSON version1/mode MANUAL_REFUND_REMAINDER ≤4096 bytes, без IDs/keys/paths/PII/raw exceptions. Phases: ADMISSION, RESTORE, BINDING, LISTENER, BASELINE, ROTATION, REMAINDER, VERIFY, CLEANUP. Results: FAILED/PENDING/SUCCEEDED; provenance CONTROLLER_REPORTED. Boolean fields: target_verified, prior_partial_verified, prior_callback_delivery_verified, callback_rotated, remainder_confirmed, provider_verified, webhook_verified, journal_verified, callback_signature_verified, final_total_verified, listener_closed, runtime_closed. All true + SUCCEEDED + actual exit0 обязательны для успеха ЭТОГО эксперимента.

prior_partial_verified означает новую server/provider проверку существующего700; prior_callback_delivery_verified означает только сохранённую server delivery700, не восстановленную HMAC проверку. Остальные финансовые/signature booleans относятся к1300 и final_total_verified —aggregate2000. Никаких partial_confirmed для старого harness или объявления всей5A завершённой. False значит unverified, не «денег не вернулось».

На unconfirmed report допускается existing strict copied/frozen diagnostic `{stage,reason,probes}` с finite vocabulary прежнего validator, без getter evaluation и raw failures. Первый diagnostic не стирается успешным поздним close. Success невозможен после любой неопределённости, ошибки proof/cleanup или stdout settlement; ошибки после admission сохраняют PENDING/exit1. Admission denial до side effects — FAILED/exit1. Закрытие session, listener и runtime проверяется независимо; прежние и новые ресурсы/доказательства не удаляются.

## 10. Границы реализации и обязательные офлайн проверки

Новые focused `infrastructure/stripe-manual-refund-remainder*` modules, типы и tests отделяют admission/state, restoration, session/proofs и controller/report. Narrow pure retained parsing/ownership/HTTP/CLI helpers можно переиспользовать/выделить с compatibility regressions. Старые режимы не получают remainder capability, более слабый baseline, вторую ротацию или разрешение обходить прежний fence. Java/API/DB/frontend/dependencies не меняются; иначе работа останавливается для пересмотра scope.

Проверки на synthetic files/process/Docker/HTTP/provider doubles должны доказать:

1. Missing/mixed opt-ins, getters, args и source/target mismatch отклоняются до private access; все старые entries отвергают новый mode.
2. Принимается лишь точный prior700ACK/1300READY/rotation2/configure2; ошибочные/неясные/заменённые/hardlinked/symlinked files и concurrent new UUIDs не дают ни restore, ни rotation/refund. Старый state/lock/report не меняются; новая sticky fence не снимается.
3. Первоначальный StartedAt ordinary service может корректно измениться после исторического restarta, но immutable identity mismatch, live/foreign service или повторный старт helper всегда запрещены. Late starts и stop transport/child settlement имеют реальный bounded cleanup, не только boolean.
4. Existing700 провайдерский факт и его Event обязательны; forged caller seed/one amount/metadata/DB success не дают1300. Нет повторного700 POST/старого callback/reconcile. Baseline changed до/после rotation блокирует1300.
5. Ровно одна rotation ожидаемой3 и один receiver configure; unexpected version/endpoint/enabled/unknown response/late abort дают ноль refund POST. Secret/cookie/credentials canaries не выходят ни через report, errors, getters, debug, snapshots или child output.
6. Только один1300 POST со старым unused key после durable dispatch и listener fence. ACK loss/fsync failure/concurrent call/другой UUID/restart не разрешают retry. Старый admin/actor scope фиксирован на протяжении новой сессии.
7. Успех требует двух exact refunds, immutable700 history, source PROVIDER_EVENT, exact journal/outbox/delivery/provider Event и новой HMAC receipt; partial/full/straddled projections не маскируют contradiction. Подделанные sums/IDs/callbacks, extra refund/has_more, terminal/reconciliation-only и late reads остаются PENDING.
8. Первый diagnostic, expired/aborted limits, late acquisitions/writes и cleanup failure не допускают ложного успеха/renewed budget. Report schema finite и inert; actual stdout/exit tests выполняются без private/runtime access.

Каждая implementation задача получает meaningful RED→GREEN, целевые и связанные regressions и независимое review до следующей задачи; затем whole-correction review и fresh root verification. Финальный безопасный infrastructure suite на Node24 включает все подходящие tests; реальные Docker build/config tests явно исключены в офлайн gate. Types/diff/secret/source-completeness checks обязательны. Maven/frontend gates не нужны при неизменных Java/frontend, но никакая прежняя проверка не выдаётся за новую.

## 11. Передача и оставленные builder решения

Это описание готовит один строго ограниченный будущий эксперимент, не запуск. После его approval нужен отдельный detailed implementation plan по выбранному пользователем subagent-driven workflow с review каждой задачи. После офлайн реализации потребуется ещё одно явное разрешение реального запуска, перечисляющее restore/listener, одну ожидаемую rotation3 и единственный refund1300; при необходимости отдельно согласуется OAuth. Ссылка Chrome не создаётся: браузерных шагов нет.

Builder выбирает разумные внутренние имена функций и точное выделение pure helpers, не меняя fixed amounts/targets/guards/proof semantics. Public interfaces и test commands фиксирует следующий plan. Миграции private state и универсальный recovery, обход fence, извлечение секретов, повторные операции или broadened runtime authority не оставлены на усмотрение builder.

Repository contracts: прежнее [описание режима700 →1300](2026-10-20-manual-refund-design.md), текущие stripe-manual-refund state/runtime/session/controller, stripe-sandbox-cli и callback-receiver, CallbackAdminController/CallbackPersistenceAdapter и их tests. Реальные сохранённые артефакты в рамках подготовки не читались; фактическая будущая проверка может fail closed, даже если историческое наблюдение было успешным.
