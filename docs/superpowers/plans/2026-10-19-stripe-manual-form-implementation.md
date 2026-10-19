# Stripe Manual Form Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подготовить отдельно включаемую одноразовую загрузку настоящей Stripe-формы человеком в обычном браузере, без подтверждения оплаты и без фактического Sandbox-запуска при реализации.

**Architecture:** Existing owned bootstrap/provider/provisioner дают fresh private Checkout. Новый manual runtime регистрирует отдельный loopback gateway browser origin, сохраняя исходные Docker ownership endpoints. Gateway выдаёт session body один раз, обслуживает только manual page+assets; страница arm-ит реальный widget и делает один Resume. Manual result не подделывает browser closure или автоматическую приёмку.

**Tech Stack:** Existing Node ESM, TypeScript, Vite, SDK custom element, Node test/Vitest/jsdom; без новых зависимостей и Java изменений.

**Spec:** [Подтверждённая в чате граница и технические решения](../specs/2026-10-19-stripe-manual-form-design.md).

## Global Constraints

- Только офлайн-реализация: никакого Sandbox/Docker/Stripe API/CLI/browser/private env/runtime inspection. Runtime boundaries синтетические; не поднимать реальные listeners в тестах.
- Отдельные exact opt-ins ENABLED+MANUAL_FORM_DIAGNOSTIC+ALLOW_PREPARE_ONLY=true; auto FORM_DIAGNOSTIC/full/owned/allow-test-operations/debug режимы несовместимы. Старые входы reject manual flag до private/dependency access.
- Fresh anonymous EUR20 ONE_TIME/NONE fixture; максимум один Checkout/Intent/первый Attempt; no card/Pay/confirm/retry/reload/refund/OTP/Customer/Subscription/Portal.
- Original active15 минут, cleanup+2 минуты; latest start deadline−120с; server observation cutoff min(grant+60с, deadline−60с); client clock начинается до session POST.
- Loopback-only gateway на отдельном седьмом порту; все шесть прежних Docker ports/ownership records неизменны. Source/ownership перепроверяются перед реальными действиями будущего запуска.
- Session только в JSON body→собственный sessionStorage key; не URL/DOM/console/files/report/commands. Stripe/admin secrets не выдаются gateway/page. No-store, exact Host/Origin, HttpOnly SameSite=Strict256-bit cookie, one-shot consumption до await.
- HTTP body32bytes для `{}` exchange и4096bytes для finite observations/report; static assets<=2MiB, timeout<=10с; redirects/path traversal/query/general proxy запрещены. Security policy не ослаблять.
- Ручной final proof допускает0|1 первый Attempt; прежний auto final остаётся строго1. Ноль Attempts не доказывает готовность формы. Late/unknown activity не улучшают результат.
- Manual result version1, provenance BROWSER_REPORTED, browser_closure NOT_OWNED; no automated closure/environment/acceptance claims. Все ресурсы/данные сохраняются.
- После каждой задачи targeted+related regression и независимое review; итоговый review всего manual delta от27e08b4. Полные SDK/types/regression на финале; Maven не нужен без Java изменений.

## Review Focus

- Second tab, reload, double click and response loss must never issue a second session/Resume/Attempt: Task2/3 tests.
- Origin divergence between gateway and owned Docker demo must preserve exact Site policy and return URL without falsifying ownership: Task1 tests.
- An unstarted/crashed page with0 Attempts must allow independent no-charge proof, not readiness; a second Attempt must fail: Task1/4 tests.
- Delayed session response, background timers or late network/listen settlement must not extend server authority or manufacture closure: Task2/3/4 tests.
- Browser reports may be forged/malformed/late; they must not become independent readiness, payment success or external browser closure proofs: Task2/4 tests.

## Task 1: Manual admission and private runtime/provisioning boundary

**Files:** create `infrastructure/stripe-manual-form-options.mjs`, `.d.mts`, `.test.mjs`; add `infrastructure/stripe-manual-form-runtime.test.mjs`; modify `infrastructure/stripe-sandbox-owned.mjs`, `stripe-form-diagnostic-provision.mjs`, `stripe-form-diagnostic-runtime.mjs`, `stripe-form-diagnostic-runtime.d.mts`. Add manual-mode rejection only to existing full guards in `stripe-sandbox-bootstrap.mjs`, `stripe-sandbox-acceptance.mjs`, `stripe-sandbox-cli.mjs`, `stripe-sandbox-provision.mjs`, `stripe-sandbox-extras.mjs`, `smoke-stage-5a.mjs` and their corresponding `.test.mjs` files. Forward the raw manual flag in `examples/demo-site/tests/form-load-diagnostic.spec.ts` and `stripe-sandbox.spec.ts` before guard evaluation; pin forwarding in the existing support config tests. No full workflow body is otherwise changed.

**Interfaces:**
- `manualFormOptIn(env): boolean`; `validateManualFormOptions(value): Readonly<ManualFormOptions>|null`, with exact existing options plus `manualPort:number`, canonical derived `http://127.0.0.1:<manualPort>` distinct from the six ports. Reuse existing base source/daemon shape rules; validation stays inert and before dependency access.
- Export `preflightOwnedManualFormDiagnostic(options:ManualFormOptions, dependencies?):Promise<Readonly<{status:'READY'}|{status:'PENDING',phase:string,code:string}>>` from `stripe-sandbox-owned.mjs`, reusing its existing source/daemon checks and bounded bind+verified-close probe for the seventh port. Full/auto admission still probes exactly its original six ports. Task4 calls this before runtime preparation; gateway's later exclusive bind rechecks ownership, and collision fails rather than selecting another port.
- Export `createManualFormDiagnosticRuntime(options:ManualFormOptions, dependencies?): Promise<ManualFormRuntime>` beside unchanged existing factory, sharing lifecycle implementation rather than duplicating it. Its capabilities remain one-shot `prepare(limits)`, `verifyNoConfirmation(limits)`, `close(limits)`; prepare returns existing private session/runId/source plus `endpoint`=gateway origin and `assetEndpoint`=actual owned Docker demo origin. Add these types to existing runtime declarations.
- Export `createManualFormDiagnosticProvisioner({env,bootstrap,browserOrigin},dependencies?)` from existing provisioner, sharing its private implementation. Manual browser origin is independent of immutable bootstrap endpoints; auto factory accepts no origin override. Manual no-confirmation proof allows0|1 first Attempt, auto remains1, baseline remains0.

- [ ] **1. RED admission tests:** omit/mix each flag/debug; invalid, duplicate/seventh port; hostile accessors/dependencies; assert null/rejection and0 private/dependency touches. Manual preflight probes the extra port exactly once with verified close; full/auto still probe six; collision stops. Existing full/auto entry tests must reject manual flag even with their own valid flags.
- [ ] **2. RED runtime/provision tests:** literal Site allowed origin/return URL/public Origin equal gateway; ownership verify still sees original Docker demo. Match exact EUR20 fixture. Manual final0 and1 succeed only with unchanged binding/no-charge state;2/unknown/charge fail; auto0 still fails. Assert one-shot prepare/proof/close/original-deadline rules and only intended capability keys.
- [ ] **3. Run RED:** `node --test infrastructure/stripe-manual-form-options.test.mjs infrastructure/stripe-manual-form-runtime.test.mjs infrastructure/stripe-sandbox-owned.test.mjs infrastructure/stripe-form-diagnostic-runtime.test.mjs infrastructure/stripe-sandbox-provision.test.mjs`; require named assertion failures, not missing-import-only evidence.
- [ ] **4. Implement the interfaces and the narrow manual origin/final-proof variant.** Preserve old defaults and trusted internal bootstrap flags; add manual-flag rejection to full/automatic exported entry guards, including their CLI env forwarding where needed. No private reads at import/construction.
- [ ] **5. GREEN + existing bootstrap/provider/provision regression:** command above plus `infrastructure/stripe-sandbox-bootstrap.test.mjs infrastructure/stripe-form-diagnostic-provider.test.mjs`; all pass, no skips/cancel. Review the exact changed guard branches, not only new helper tests.
- [ ] **6. Independent task review; resolve findings and rerun affected tests; commit only Task1 files.**

## Task 2: Owned one-shot manual HTTP gateway

**Files:** create `infrastructure/stripe-manual-form-gateway.mjs`, `.d.mts`, `.test.mjs`; use Task1 option/runtime types.

**Interfaces:** `createManualFormGateway({origin,assetEndpoint,session}, limits, dependencies?): ManualFormGateway`, inert constructor. `open():Promise<{url:string}>` binds exclusive loopback once; `wait():Promise<ManualGatewayObservation>` settles at existing cutoff/abort/failed boundary; `close(cleanupLimits):Promise<boolean>` proves owned listener/sockets/response settlement, not external browser closure. Observation fields: `observation`, `end`, `sample` with domains from spec; exported types shared with Task3/4. Clock/listener/random/fetch dependencies are injectable, bounded and not consulted before validation.

- [ ] **1. RED HTTP state-machine tests:** first document+cookie+one exchange succeed; second document/POST, wrong/missing Host/Origin/cookie, GET exchange, >32bytes/nonempty JSON, encoded/query/traversal paths, unexpected methods/routes, second tab/reload, partial response and failed delivery consume no additional authority. Private sentinels never reach URL/header reflection/log/error/report; no request credentials forwarded upstream.
- [ ] **2. RED ownership/time tests:** reject foreign/noncanonical upstream and shared ports; fixed assets only, exact security headers/no-store, >2MiB/redirect/10s failure; original deadline/start cutoff/60s/abort stop new reads/writes; max2 exact samples <=4096bytes; late/malformed/forged ready cannot manufacture proof; pending listener acquisition/active responses/sockets must settle before close=true.
- [ ] **3. Run RED:** `node --test infrastructure/stripe-manual-form-gateway.test.mjs`; test real handler/state machine against fake listener/request/fetch/timers, no real TCP/Stripe/Docker. Fail on behavior assertions.
- [ ] **4. Implement gateway with the spec's exact route/lease/consumption limits.** No disk persistence, global static root, general upstream proxy or raw diagnostics. Invalid requests do not reissue cookie/session; resource-safety failures latch FAILED.
- [ ] **5. GREEN:** same command;0 failed/skipped/cancelled. Self-check in-flight close/late fulfillment independently from abort dispatch.
- [ ] **6. Independent task review; resolve findings and rerun affected tests; commit only Task2 files.**

## Task 3: Ordinary-browser page and actual built SDK flow

**Files:** create `examples/demo-site/form-load-manual.html`, `src/form-load-manual.ts`, `tests/support/form-load-manual.test.ts`, `tests/support/form-load-manual-build.test.ts`; modify `vite.config.ts`, `Dockerfile`, `playwright.config.ts` and corresponding diagnostic config/build tests as necessary.

**Interfaces:** `mountManualFormPage(root:HTMLElement, dependencies?): {close():void}` from manual TS entry, called by a thin guarded DOM entry. Same-origin `/manual-session` JSON contract is Task1 private session; `/manual-observation` uses Task2 finite observation contract. Only external HTTP/Stripe/time boundaries doubled; real widget/observer/loader behavior remains production source. No manual entry in ordinary production build or Playwright discovery.

- [ ] **1. RED source behavior tests:** exact Russian button/warning/status copy from spec; a double click causes exactly1 exchange, recovery GET and client-session POST/Resume; no click means no handoff/provider action. Assert arm-before-connect, connected host before mount, deny at actual view/handle, no confirm/input APIs, zero confirmations, no raw payload/URL/DOM/error leakage. Missing/malformed/late session fails closed without mounting/retrying.
- [ ] **2. RED lifecycle tests:** synchronous/delayed ready and iframe-without-ready use actual SDK; Pay visibility alone does not fake ready. Report at most pending15s+one terminal; early-ready form remains visible only until original60s for human viewing. Deadline/abort/page teardown remove owned widget, clear only its storage key, stop timers/handlers; late response/events cannot remount. Browser closure is never asserted.
- [ ] **3. RED real-build tests:** actual Vite `form-diagnostic` includes both automatic/manual entries sharing SDK graph, session via controlled same-origin response and real lazy loader window.Stripe boundary; default production build excludes both. Fresh jsdom workers, no resource loader/network/browser. Existing automatic registry+ready build cases remain unchanged. All new support tests excluded from ordinary Playwright discovery.
- [ ] **4. Run RED:** `node --test examples/demo-site/tests/support/form-load-manual.test.ts examples/demo-site/tests/support/form-load-manual-build.test.ts examples/demo-site/tests/support/form-load-diagnostic-config.test.ts`; meaningful behavior failure required before implementation.
- [ ] **5. Implement page and add only the diagnostic build entry/Docker COPY/discovery changes.** User clicks normal page button; application triggers one real widget Resume, never direct private SDK methods or Playwright/CDP. Do not hide provider host until ready or relax CSP.
- [ ] **6. GREEN and types:** same tests plus existing `form-load-diagnostic-build.test.ts`; SDK `node node_modules/typescript/bin/tsc -p tsconfig.json --noEmit` and demo `node node_modules/typescript/bin/tsc --noEmit` from their directories. All pass; no real browser.
- [ ] **7. Independent task review; resolve findings and rerun affected tests; commit only Task3 files.**

## Task 4: Inert CLI/controller, finite report and final evidence

**Files:** create `infrastructure/stripe-manual-form.mjs`, `.d.mts`, `.test.mjs`, `stripe-manual-form-schema.mjs`, `.d.mts`, `.test.mjs`; modify `docs/local-development.md`; update this plan checkboxes only from verified evidence.

**Interfaces:** `runManualFormDiagnostic(options:ManualFormOptions, limits, dependencies?):Promise<{report:ManualFormReport,exitCode:0|1}>`. Dependencies expose Task1 preflight/runtime and Task2 gateway factories; one preflight→prepare→open→wait→close-gateway→single final proof→close-runtime path, no second run. Import is inert; direct CLI has the exact manual flags/source/daemon/seven ports contract. Emit only ready loopback URL without capability and final strict report, not raw exceptions/private values. `validateManualFormReport`, `formatManualFormReport`, `parseManualFormReport` enforce exact spec version1/mode/domain/provenance/no-closure claims and4096byte ceiling.

- [ ] **1. RED schema/controller tests:** getters/symbols/extra/oversized/mixed-version/malformed payload rejected; literal report domains and precedence. Valid READY_REPORTED cannot survive negative no-confirmation/gateway/runtime proof. Browser-only report cannot assert independent readiness/browser closure/payment success; null/late sample stays non-ready.
- [ ] **2. RED one-shot/cleanup tests:** no opt-in rejects before dependencies/private access; import inert; failed prepare/open/wait/write/close and abandoned page0 Attempts all go through bounded financial proof when authority remains, no repeat/new deadline. Reserve latest-start/financial-read windows; late acquisition/in-flight close remains unconfirmed; unknown ownership never stops unrelated resources. CLI env/stdio sanitized; no keys in commands/logs. Report no test launch as real readiness.
- [ ] **3. Run RED:** `node --test infrastructure/stripe-manual-form-schema.test.mjs infrastructure/stripe-manual-form.test.mjs` with fake runtime/gateway/clock/stdio and no process/browser/Docker/Stripe.
- [ ] **4. Implement controller/CLI/schema and user guide.** Guide states offline-only current status, future separate permission, one fresh EUR20 unconfirmed fixture, opening URL in an ordinary browser (no extension/DevTools), one «Загрузить форму», no card/Pay/reload, report fields visibility, close only this tab, no automatic repeat. Give exact future command with variable names/placeholders only, never invoke it now.
- [ ] **5. GREEN full manual suite, all related auto/provision/guard/default-discovery tests, SDK full unit suite and SDK/demo typechecks.** Use bounded sanitized children; preserve only safe result evidence under ignored `.superpowers`. No Maven/full Playwright, dev server, TCP listener or external calls for these unchanged Java/runtime boundaries.
- [ ] **6. Independent task review and final review of complete manual delta from27e08b4.** Verify no unsafe guard/default behavior drift, cookie/session/report isolation, owned cleanup vs non-owned browser distinction. Resolve findings; rerun all affected checks. Mutation/RED evidence must detect a disabled one-shot guard, expired start or missing deny without mutating committed production source.
- [ ] **7. Local commit and handoff:** source/status clean, main untouched, no push/PR/merge/resource cleanup. Link guide and verification/review evidence; explicitly say implementation-only, no Sandbox attempted and original missing-ready cause still UNKNOWN. Actual future launch requires fresh user approval.

## Self-review and execution gate

- Spec coverage: approved user interaction/safety, private handoff, exact origin separation, one-shot HTTP, ordinary build isolation, original deadlines, proof provenance and no Sandbox each map to Tasks1–4 above.
- Interface review: private session is existing FormDiagnosticHandoff.session; manual runtime adds only distinct endpoint/assetEndpoint. ManualGatewayObservation and ManualFormReport share domains without impersonating old automated wire-v3. Old automatic signatures/results remain unchanged.
- Review Focus's five risks each have owning RED tests above. New manual behavior never requires guessing external Stripe readiness or provider errors.
- This document is an implementation plan, not implemented behavior or launch approval. Per writing-plans, pause for user review of this plan before implementation. Preserve the previously requested subagent implementation/review workflow when execution is approved.
