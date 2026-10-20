# Ограниченный возврат уже оплаченных EUR20 в Sandbox

Статус: объём подтверждён в чате 2026-10-20, включая одну ротацию callback-ключа только исходного тестового Site. Подробное описание и связанный план подготовлены для проверки до реализации. Текущая работа — только офлайн; ни этот документ, ни его подтверждение не разрешают запуск Sandbox.

## 1. Результат и неизменяемая цель

Отдельный режим возвращает EUR7, затем оставшиеся EUR13 одного уже подтверждённого анонимного CATALOG/CARD Payment на EUR20. Второй возврат разрешён только после независимого подтверждения первого в Stripe, локальном журнале и подписанном callback. Итог — две подтверждённые операции и Payment REFUNDED с refunded_amount_minor=2000. Новое списание, Checkout или PaymentIntent не создаются. Ввод карты и браузерная страница не нужны.

Первый вариант намеренно привязан только к исходному успешному manual-payment run `031b5ae7-6603-4339-b1b3-77231291ff34`, source `f0620e40004162d2b3eededa60512f50a492f4cc`. Его сохранённые публичные identities связываются с заново прочитанным защищённым Admin состоянием и Stripe, а не с похожим объектом по сумме/metadata. Произвольные Site/Payment/provider IDs, суммы, endpoint URLs и credential paths не принимаются от оператора. Другие платежи потребуют отдельного расширения режима.

Привязка остаётся `acct_1UPiMpELswAd8tpR`, API `2026-09-30.endive`, Stripe CLI `1.52.0`, test mode. Используется прежняя recovery worktree. Для новой операции — отдельный свежий lowercase UUID, отдельный owner-only каталог, новый reviewed clean controller SHA и исходный canonical owned Unix Docker socket. Старый UUID не становится новым бюджетом первоначальной оплаты.

## 2. Что не входит

Нет новых оплат, подтверждения карт, decline/retry/3DS, Donation/Customer/OTP, подписок, автоматической reconciliation, денежных операций через Stripe CLI/Dashboard, cancellation, смены Stripe API-ключей/webhook verifier, изменения внешнего аккаунта/API version, общего HTTP proxy, удаления или пересоздания контейнеров/сетей/томов/образов/истории. Нет обхода неизвестного результата новым refund key или новым run UUID. Старые form-only/manual-payment/full acceptance режимы сохраняют прежние ограничения.

Это одна будущая ограниченная проверка, не полная приёмка 5A и не live readiness. Реальный запуск требует отдельного явного разрешения после тестов и review. Сейчас запрещены Docker/Compose runtime, Stripe CLI/OAuth/listener, provider/Admin/финансовые запросы и чтение настоящих private env/config/credentials. Офлайн-тесты работают только с синтетическими файлами, transport/process doubles и loopback fixtures с внешней сетью, запрещённой тестом.

## 3. Разделение восстановления и новой authority

Текущий bootstrap умеет только NEW → PREPARED → LIVE → STOPPED. Его prepare/start нельзя вызывать для возобновления сохранённой попытки. Нужна отдельная restoration capability, не ослабление требований never-started у старого bootstrap.

При будущем запуске она bounded/no-follow читает только заранее известные файлы исходного каталога: runtime.json, form-provisioning.json, app.env и final.env; metadata раскрывается только безопасными projections. CLI получает только свой прежний изолированный каталог через существующую private boundary; контроллер не читает содержимое CLI credentials. Предыдущие operational Markdown notes и прежний stdout SUCCEEDED — справочная история, не текущая финансовая authority.

Проверяются canonical path каждого родителя, owner UID, тип, single-link regular file, 0700 каталог/0600 файлы, размер ≤65536 bytes, отсутствие symlink, совпадение lstat/fstat/inode/dev, размер прочитанного и стабильность файла. JSON принимает только определённую схему, уникальные имена членов и известные значения. app.env/final.env разбираются purpose-specific parser без shell/source/eval; только исходный фиксированный allowlist и безопасные форматы, без дублей/подстановок/неизвестных присваиваний. Два файла должны иметь одинаковые исходные DB/Admin/шифровальные ключи и ports. Stripe secrets только test, webhook verifier непустой; значения не возвращаются из public resources/report/error.

Исходный manifest должен быть MANUAL_PAYMENT/STOPPED/webhookReady=true с полностью ACKNOWLEDGED startup intents, ровно известными семью container records, пятью image records, одной сетью и двумя volumes. form-provisioning должен однозначно описывать завершённое создание единственного Catalog payment и исходного PAYMENT_PREPARE. Из него нельзя выдумывать success/decline/SCA state общего smoke runner.

Одна exclusive lock в исходном каталоге исключает параллельное использование разными refund runs; новая run directory и durable state создаются исключительно. Существующие locks/state не снимаются автоматически. Исходные runtime/provisioning/private files никогда не переписываются. Новые lock/state принадлежат только новой операции; при неясном закрытии/dispatch lock и доказательства сохраняются для ручного разбора.

## 4. Восстановление контейнеров без пересоздания

До любого start Docker projection заново доказывает полный набор точных container IDs, project/service/own labels, immutable image IDs, mounts, network/volume identities, loopback host ports, user/capability/read-only ограничения. В проекте нет лишних контейнеров, все семь остановлены; обе одноразовые issuer helpers ранее успешно завершились и никогда не запускаются снова. Не выводятся Config.Env, raw inspect или конфигурация Compose. Только backend имеет Stripe credentials; их фактическое значение не извлекается Docker projection.

Порты остаются исходными шестью; чужой listener или занятый порт означает stop, без выбора другого порта. По ID запускаются только пять обычных сервисов в порядке postgres, mailpit, callback-receiver, demo-site, backend. Каждый start предваряется fsynced DISPATCHING записью; известное подтверждение фиксируется отдельно. Успех команды не заменяет свежую проверку exact ID/running/health/ports/topology. Никаких build/pull/up/rm/volume/network create или новых issuer keys.

Новый чистый controller source и исходный runtime source — разные факты: новый expectedSource должен совпадать с текущим HEAD; исходный source должен существовать и быть предком нового. Сохранённые image IDs не перепривязываются к новому SHA. Bind-mounted callback-receiver.mjs должен побайтно совпадать с исходным Git blob; иначе stop. Это явно ограниченная реставрация указанного run, не принятие произвольной старой topology.

Admin credentials передаются из private loader только в ограниченный callback после свежей проверки runtime; успешный Admin login и последующие защищённые reads проверяют исходный Site/Account/Payment. Не создаётся новый администратор и не меняются DB/шифровальные ключи.

## 5. Listener и provider proof

Существующий isolated CLI transport переиспользуется через отдельный узкий refund factory. Он не открывает login/complete-login, произвольную command capability, write API или старую unpaid manual-payment binding. Используются сохранённые isolated CLI credentials. Если они отсутствуют/истекли или account/test/API/version проверки не проходят — stop до callback rotation и refund; автоматического OAuth/login нет. Будущая отдельная авторизация потребует отдельного согласования.

Listener использует прежние account-key endpoint и event allowlist. Его выданный webhook secret должен точно совпасть с opaque retained final.env verifier; сравнение в памяти, не в argv/логах. Несовпадение запрещает продолжение, очистку/перезапись secret или пересоздание backend. Forward target заново проверяется restoration capability; callback persistSecret здесь означает проверку равенства, а не запись. Реальное закрытие child и самостоятельная остановка runtime доказываются отдельно.

Новые read-only методы привязываются один раз к исходным Payment/Site/Account/operation/Intent/Price и исходной новой deadline. Перед ротацией и обоими refund POST проверяются живой listener и provider binding. До первого возврата Intent/Charge: succeeded/paid/captured, automatic CARD, test mode, EUR2000, amount_received/amount_captured=2000, customer=null, нулевые refunds. После каждого возврата проверяются только исходный Intent/Charge и точные Refund IDs из подтверждённого Admin ответа, их status=succeeded, amount 700/1300, eur и совпадение charge/intent; aggregate amount_refunded 700/2000, refunded false/true. Неполный список/has_more/другой refund отклоняется. Не требовать несуществующий обязательный Refund.livemode: test mode доказывают binding и связанный Intent/Charge, а также Event если он используется.

## 6. Исходный серверный baseline

Свежие Admin reads должны показать ровно исходный Payment на Site, provider STRIPE/mode SANDBOX, source CATALOG, EUR2000/NONE, customer/donation/subscription=null, исходный Price/версию и единственный acknowledged PAYMENT_PREPARE с тем же Intent. Ровно один исходный успешный CARD attempt, один PAYMENT_SUCCEEDED source PROVIDER_EVENT, тот же provider object/event и ранее DELIVERED outbox/delivery. Payment SUCCEEDED, refunded_amount_minor=0, Refunds отсутствуют; pending/unmatched provider operation блокирует возврат.

Старое receiver inspection после рестарта не восстановимо и не выдаётся за повторную проверку callback оплаты. Сохранённый факт прошлой успешной проверки остаётся историческим; новые подписанные refund callbacks доказываются независимо новым receiver inspection. Из journal/outbox не создаётся фиктивная запись receiver.

## 7. Одна ротация только исходного Site

После всех baseline/ownership/provider checks durable state фиксирует ROTATION_DISPATCHING и исходный Site ID. Выполняется ровно один существующий защищённый POST /api/v1/admin/sites/{siteId}/callback/rotate-secret. Запрос не меняет endpoint/enabled или остальные Sites. Ответ должен вернуть тот же Site, прежний receiver callback URL, enabled=true и следующую ожидаемую secret version. Новый plaintext secret передаётся только в памяти через POST /configure ровно исходному owned receiver; в state хранятся лишь version/phase.

Endpoint нельзя угадать или принять из произвольного server response: исходный callback URL строго `http://callback-receiver:8080/callback`; `/configure` вызывается через отдельно verified loopback receiver origin. В текущем API нет GET callback config/secret: до записи проверяется исходный ACK callback/receiver record version1 и прежние delivered payment facts, но это не доказательство текущей конфигурации. Поэтому единственная разрешённая rotation проверяет результат ПОСЛЕ своей записи: тот же Site, exact internal callback URL, enabled=true, secret_version=2. Если конфигурация кем-то изменена или версия не2 — PENDING, ноль refund POST; разрешённая ротация могла уже произойти, откат/повтор/PUT configure запрещены. Новый backend endpoint не добавляется.

Потерянный/невалидный ответ rotation, неуспешный durable commit или неизвестный /configure outcome означает PENDING и ноль refund POST. Нельзя повторять rotation, конфигурировать новый Site, сохранять plaintext для повторного запуска или снимать lock. Изменение callback key отмечается в стандартном audit backend. Старая история ключей не удаляется.

## 8. Две операции и денежные доказательства

Два разных стабильных idempotency keys и суммы 700/1300 записываются в новую owner-only state до первого финансового действия. Каждый POST адресует только исходный Payment, currency EUR, reason_code REQUESTED_BY_CUSTOMER; ни note, ни PII нет. API только существующий защищённый /api/v1/admin/payments/{paymentId}/refunds. Исходный authenticated Admin ID должен оставаться тем же: его identity входит в server idempotency scope.

Непосредственно перед POST fsync сохраняет DISPATCHING. 202 — только acknowledgement; только валидный original Refund UUID сохраняется как ACKNOWLEDGED. Потерянный ответ, отмена, 5xx/invalid reply/timeout или потерянный локальный commit после dispatch останавливают режим PENDING. Никакого второго POST с тем же или новым key и никакой новой ротации, даже если позднее виден похожий Refund. Для известного UUID допустимы лишь bounded reads.

После EUR7: единственный owned Refund SUCCEEDED/700/EUR/provider reference; Payment PARTIALLY_REFUNDED/refunded_amount_minor=700. Один REFUND_SUCCEEDED именно этого provider refund, неизменённый исходный success, совпадение cash journal ID → delivered outbox ID → успешная delivery → signature-verified receiver event. Provider read отдельно подтверждает тот же refund и aggregate700. Только после fsynced CONFIRMED и всех checks разрешён EUR13.

После EUR13: ровно два owned Refund SUCCEEDED на 700 и1300; Payment REFUNDED/refunded_amount_minor=2000; ровно два REFUND_SUCCEEDED, каждый с собственным provider reference и подписанным callback. Provider aggregate2000/refunded=true. Transport duplicates допустимы, новые денежные факты/чужие refunds — нет. Для обоих success events требуется source=PROVIDER_EVENT и непустой provider_event_id; отдельный read-only CLI retrieve проверяет соответствующий refund.created/refund.updated Event, API version, livemode=false и связанный исходный Refund. Если scheduler записал success через RECONCILIATION раньше webhook, деньги могут быть возвращены, но этот эксперимент остаётся PENDING без второго возврата/исправления истории: webhook proof нельзя выдумывать.

Режим не возобновляет прерванный refund run автоматически. Повторный запуск с существующей state/lock запрещён до любых mutations; fresh UUID не обходит original-target lock и исходный zero-refund baseline. Read-only разбор PENDING — отдельное решение оператора, не автоматическое завершение второй операции.

## 9. Время, отчёт и завершение

Решение проектирования: одна новая active authority ≤30min от начала refund entry; cleanup только до исходной новой deadline+2min, не до новой deadline после ошибки. Каждое HTTP/provider read ≤10s, proof phase ≤30 probes/45s внутри active boundary. Сроки из первоначальной оплаты не продлеваются и не используются для новых writes. Cleanup не разрешает финансы/новые reads. Все in-flight команды и acquired resources остаются под единственной исходной cleanup ceiling.

Вывод — один finite JSON ≤4096 bytes, mode MANUAL_REFUND/version1, без IDs/PII/secrets/raw errors. Фиксированные phase ADMISSION/RESTORE/BINDING/LISTENER/BASELINE/ROTATION/PARTIAL/REMAINDER/VERIFY/CLEANUP; result FAILED/PENDING/SUCCEEDED; booleans target_verified, callback_rotated, partial_confirmed, remainder_confirmed, provider_verified, webhook_verified, journal_verified, callback_verified, listener_closed, runtime_closed; provenance CONTROLLER_REPORTED. Только SUCCEEDED + все booleans true + реальный exit0 подтверждает этот эксперимент. False означает unverified, не «возврата не было». Ошибка после любого неизвестного действия навсегда защёлкивает PENDING, даже если поздний cleanup/read успешен.

В finally сначала прекращаются/дожидаются работы HTTP и listener/children, затем отдельно останавливаются только exact freshly-verified ordinary containers, запущенные этим refund controller. Отказ listener close не отменяет независимую попытку runtime stop. Helpers не запускаются/не останавливаются повторно. Исходные и новые volumes/network/images/containers/provider objects/credentials/state сохраняются; нет down -v/prune/rm. Невозможность доказать закрытие отражается в report, не маскируется.

## 10. Офлайн gates и передача

Каждая задача: meaningful RED→GREEN, целевые и связанные regressions, отдельное независимое review требований и качества, устранение финансовых/security findings. Root проверяет итоговый diff и свежие финальные тесты; прежние broad Stage5A review не повторяются. Полный infrastructure Node suite на финальном checkpoint; SDK/demo/native/typecheck/build только если их файлы изменятся. Maven не требуется при неизменных Java/API/DB; их изменение требует пересмотра границ и backend gate. Реальные ключи и run files не читаются даже для «диагностики» тестов.

Новая инструкция описывает отдельный opt-in и отсутствие браузерных шагов, fixed target, expired CLI stop, unknown/rotation failure и сохранение данных. Команда до runtime release — только template, не готовая ссылка/выполненная проверка. После offline gates пользователь отдельно разрешает одну попытку, включая указанные restore, one rotation и два возврата.

## Источники решений

Текущие tracked bootstrap/CLI/manual-payment/provision/smoke/receiver и CallbackAdminController/CallbackPersistenceAdapter — источник repository contracts. Подтверждённый исходный finite report хранится в отдельной локальной operational note; это не новая authority.

Stripe: [создание возвратов](https://docs.stripe.com/api/refunds/create), [поля и состояния Refund](https://docs.stripe.com/api/refunds/object), [aggregate/refunded Charge](https://docs.stripe.com/api/charges/object). Эти документы не заменяют pinned runtime/API verification.
