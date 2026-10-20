# Stripe Manual Single Payment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare exactly one human-confirmed EUR20 Sandbox payment in ordinary Chrome, with authoritative provider/webhook/journal/callback evidence and verified owned cleanup.

**Architecture:** Keep form-only denial and full acceptance unchanged. Introduce a dedicated admitted runtime, a minimal single-checkout provisioner variant, a durable one-confirmation permit and an internal SDK lease, then serve a one-shot manual page through an owned loopback gateway. Reuse fixed isolated CLI descriptors, ownership checks and protected Admin projections; no Java/schema/API changes or new dependencies.

**Tech Stack:** Existing Node ESM/node:test, TypeScript, SDK/Vitest, Vite, synthetic Chromium, Docker Compose and pinned Stripe CLI.

**Spec:** `docs/superpowers/specs/2026-10-19-stripe-manual-payment-design.md` (scope approved in chat; implementation plan awaiting user review).

## Global Constraints

- Exact opt-ins: `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true`, `PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT=true`. All other run modes/debug flags incompatible; reject before dependencies/private input.
- One anonymous CATALOG Checkout/Payment/PaymentIntent, EUR2000, tax NONE, ONE_TIME, CARD. No Customer, Donation, Subscription, email, refund, retry, cancel, reconcile or additional checkout/price version.
- Bind `acct_1UPiMpELswAd8tpR`, API `2026-09-30.endive`, CLI `1.52.0`, test-only keys/identity and `livemode=false`. No live flag, API-version change, global config or Keychain.
- Fresh run UUID, clean reviewed SHA, pinned canonical local Unix daemon, seven exclusive loopback ports in1024–65535. Reuse existing isolated recovery worktree, never main/old fixtures.
- One original45min active deadline, original+2min cleanup ceiling, browser allowance5min only if at least5min+90s remains; clip to Checkout expiry. Proof polling≤30 probes/45s, HTTP≤10s, all clipped to active authority. Original login3min/complete10min/child-close grace10s are not extensions.
- Durable consumed permit before Stripe confirmation. Unknown delivery/outcome never resets authority or permits retry. Closure/host loss/expiry/reconfiguration keeps existing handles denied.
- Tokens/keys/cookies/PII/raw errors stay out of argv, URL, logs, report and Git. Private audit tree0700/files0600, original identities, O_EXCL/O_NOFOLLOW, fsync/atomic writes.
- Browser is human-owned: no actual Chrome automation, iframe/card reads, screenshots, console/Network/HAR/trace or requested secret screenshots. Synthetic offline browser only with all external HTTP/WebSockets denied.
- Stop only freshly verified owned resources. Preserve containers, networks, volumes, provider objects and evidence; no cleanup deletion, automatic rerun or secret-conflict reset.
- Each task: meaningful RED→GREEN, related regression, independent review before its local commit and before next task. Sandbox starts only after final gates and a clean committed source; never during tests/review.

## Review Focus

1. Lost permit response or crash between durable `CONFIRMING` and browser invocation must not yield another permit or fresh financial operation (Tasks1,3,4).
2. Stale/remounted widget or a lease closed during asynchronous permission must not confirm; old form-only denial wins even if a manual lease is armed (Task2).
3. Matching amount/metadata, browser succeeded, or RECONCILIATION-origin success must not be accepted as the original Intent's genuine webhook (Task1).
4. Host/Origin/cookie confusion, duplicate JSON keys, a second tab/session, and private return queries must not leak a session or consume a second capability (Task3).
5. Late child/HTTP/stdout settlement, abort, full disk or lost cleanup ownership cannot report success, renew a budget or stop unrelated resources (Tasks1,3,4).

## Existing seams and decisions

The inspected baseline is clean `12d767cf704373065f5c05069ae710dbd2716b91` on `codex/stripe-sandbox-5a`. Existing `createOwnedSandboxBootstrap` owns Compose/credentials and requires a live webhook listener; `createSandboxCliAdapter` owns isolated read-only CLI/listener operations. `createManualFormDiagnosticProvisioner` already implements one-checkout provisioning, but deliberately rejects live-webhook topology and demands unpaid final state. Do not pass it fake diagnostic flags/topology to bypass those restrictions.

Use a named `createOwnedManualPaymentBootstrap(options, dependencies)` factory selecting the fixed `manual-payment` build mode and resources.mode `MANUAL_PAYMENT`, with the same full-runtime listener requirement. Internally normalize trusted flags only after the new outer admission. Add a dedicated `createManualPaymentProvisioner` export to the existing single-checkout module; mode-specific admission/read proofs/ledger path are explicit. Do not loosen any current factory's predicates or call the four-checkout full provisioner.

Common contracts used below:

- `ManualPaymentOptions = {env, runId, expectedSource, daemon, ports:{backend,demo,receiver,postgres,mailpitHttp,mailpitSmtp}, manualPort}`; exact immutable plain data. `Limits={deadline:number,signal?:AbortSignal}`.
- `PaymentBinding = {paymentId,siteId,paymentAccountId,operationId,intentId,priceId,priceKey,amountMinor:2000,currency:'EUR',externalAccountId:'acct_1UPiMpELswAd8tpR'}`; UUID/reference shapes and equality to original server facts required.
- `PrivateSession` retains the existing version2 Checkout session shape. `PaymentServerProof = {paymentConfirmed:true,webhookVerified:true,journalVerified:true,callbackVerified:true,providerEventId:string}`; safe provider_event_id from the unique validated server journal only.
- Public report exact version1/mode `MANUAL_PAYMENT`, `result:'SUCCEEDED'|'NOT_STARTED'|'FAILED'|'PENDING'`, `phase:'ADMISSION'|'PREPARE'|'AUTHORIZATION'|'BINDING'|'LISTENER'|'START'|'PROVISION'|'HANDOFF'|'AWAITING_PAY'|'VERIFY'|'SUCCEEDED'|'CLEANUP'`; boolean fields `confirmation_granted,payment_confirmed,provider_confirmed,webhook_verified,journal_verified,callback_verified,gateway_closed,listener_closed,runtime_closed`; `browser_closure:'NOT_OWNED'`, `provenance:'CONTROLLER_REPORTED'`. False proof means unverified, never proof of absence. Exact finite JSON≤4096bytes, no extra fields.

### Task 1: Admitted single-checkout runtime inputs and server/provider proofs

**Files:**
- Create: `infrastructure/stripe-manual-payment-options.mjs`, `.d.mts`, `.test.mjs`.
- Modify/test: `infrastructure/stripe-sandbox-owned.mjs`, `stripe-sandbox-bootstrap.mjs`, `stripe-sandbox-cli.mjs`, `stripe-form-diagnostic-provision.mjs` and corresponding `.test.mjs` files.
- Modify/test old public entry guards: `infrastructure/stripe-sandbox-acceptance.mjs`, `smoke-stage-5a.mjs`, `stripe-manual-form-options.mjs`, `stripe-form-diagnostic.mjs`, `stripe-manual-form.mjs` plus corresponding guard/config tests.

**Interfaces:**
- `manualPaymentOptIn(env):boolean`, `validateManualPaymentOptions(value):Readonly<ManualPaymentOptions>|null`, `preflightOwnedManualPayment(options,deps?):Promise<{status:'READY'}|{status:'PENDING',phase,code}>`; inert before explicit invocation and no private access during preflight.
- `createOwnedManualPaymentBootstrap(options,deps?)` returns the existing full bootstrap capability shape; never exposes provider keys. It selects `PAYMENT_DEMO_BUILD_MODE=manual-payment` internally, not a caller command/arg.
- `createManualPaymentProvisioner({env,bootstrap,browserOrigin,verifyProviderBinding},deps?)` produces `prepare(limits):Promise<{session:PrivateSession,binding:PaymentBinding}>`, `markConfirmation(limits):Promise<true>`, `verifyPayment(limits):Promise<PaymentServerProof>`, `verifyUnused(limits):Promise<true>`, `close(limits):Promise<boolean>`. Each capability is one-use; lock/unknown state refuses replacement.
- CLI adapter adds `bindManualPayment(binding,limits):Promise<true>` (one-time, verify unpaid baseline) and `inspectManualPayment(providerEventId,limits):Promise<{captured:true,eventVerified:true}>` (only the latched Intent and exact journal event, active verified listener). Existing missed-Intent APIs unchanged.

- [ ] **1 RED admission tests:** absent/mixed/case-changed flags, accessors, debug, extra fields, old run IDs, dirty/wrong SHA, daemon mismatch and seventh-port collision fail before private/dependency getters; historical entrypoints reject the new flag. Old six/seven-port modes unchanged.
- [ ] **2 RED bootstrap/provision tests:** assert only the named factory selects new build mode/resources tag while requiring webhookReady=true. A live owned manual-payment topology produces exactly one known checkout/Intent and one Product/Price/version; Site origin/return URL equal gateway while ownership endpoints retain Docker origins. Callback configuration secret travels in memory only.
- [ ] **3 RED durable permit/proof tests:** before permit require the same ACKNOWLEDGED prepare operation, unpaid Intent baseline, one Site payment, attempt№1/CARD/no-success/no-refunds. Persist `CONFIRMING` before true; concurrent/second grant, DISPATCHING/failed fsync/rename/unknown response, wrong owner/host/Intent, second payment/attempt and changed binding fail without another write.
- [ ] **4 RED authoritative result tests:** exact SUCCEEDED/provenance/provider_event_id/original Intent/journal/outbox/delivery/receiver join succeeds. Reject amount-only matches, wrong account/mode/price/event/Intent/Charge, `livemode=true`, duplicate financial fact, Customer/refund, browser-only and `source=RECONCILIATION`. Genuine transport duplicates remain accepted. CLI read arguments contain no confirm/refund/cancel/delete/scan and reject the old missed-inspector lifecycle. `verifyUnused` accepts only no permit and a final unpaid original Intent with no success/refund; unexpected activity is PENDING.

  Behavioral anchors for the synthetic provisioner/CLI harness (all IO doubled): `assert.equal(checkoutWrites.length,1)`, `assert.equal(intentIds.size,1)`, `assert.ok(actions.indexOf('DURABLE_CONFIRMING') >= 0 && actions.indexOf('DURABLE_CONFIRMING') < actions.indexOf('PERMIT_REPLY'))`, `await assert.rejects(secondGrant)`, and `await assert.rejects(verifyReconciliationOnlySuccess())`. Assert zero subsequent financial writes after each rejection.
- [ ] **5 Run RED:** sanitized `node --test --test-concurrency=1 infrastructure/stripe-manual-payment-options.test.mjs infrastructure/stripe-sandbox-owned.test.mjs infrastructure/stripe-sandbox-bootstrap.test.mjs infrastructure/stripe-sandbox-cli.test.mjs infrastructure/stripe-form-diagnostic-provision.test.mjs`; record named behavioral failures, not missing-import-only evidence.
- [ ] **6 Implement the named factories/capabilities with existing private request/settlement/ledger patterns.** Preserve the old diagnostic predicates and final unpaid proof. Use existing protected Admin HTTP, no new backend route or SQL. Payment proof polling≤30/45s, per-request10s and original deadline; final provider verification is read-only.
- [ ] **7 GREEN and regression:** same tests plus acceptance/smoke/current manual/auto guard tests; all pass with zero live CLI/Docker/network/private input. Independent task review, resolve findings and rerun affected tests. Local commit `feat: add single-payment Sandbox preparation and proofs`.

### Task 2: Internal SDK one-confirmation lease

**Files:**
- Create: `frontend/sdk/src/diagnostics/manual-payment-authorization.ts`, `.test.ts`.
- Modify/test: `frontend/sdk/src/widget/generic-payment-widget.ts`, `frontend/sdk/src/providers/stripe-driver.ts` and their unit tests; related `form-load-observer.test.ts`/provider-view denial tests only for regression.

**Interfaces:**
- `armManualPayment(widget:HTMLElement,{deadline:number,authorize:()=>Promise<true>}):{close():void}`; register/bind/invalidate hooks for one known, unused Catalog widget before connection. No SDK entrypoint export.
- `bindManualPayment(widget,host,generation)` captures the current owned provider host; `manualPaymentBinding(host)` returns the opaque lease captured by the driver. `consumeManualPaymentConfirmation(binding):Promise<void>` is inert for an unarmed ordinary handle; armed handles set consumed before await and validate identity/time/closure again after authorization.
- Stripe driver invokes old `denyFormLoadConfirmation` first, then consumes the optional new lease immediately before the sole `stripe.confirmPayment`. New capability never changes Payment Element contents or owns PAN/CVC.

- [ ] **1 RED tests:** real widget+driver with synthetic Stripe sees zero confirmation without a valid grant, one after valid grant, zero for concurrent/second confirmation or failed/lost authorization. Lease close/disconnect/reconfigure/generation/host removal-reinsertion during await stays denied after late resolution and does not rearm.
- [ ] **2 RED regression:** old form-only observer still denies at both provider view and handle after close, mixed arming cannot override it; ordinary unarmed Catalog/Donation/Fake behavior and Light DOM mounting remain unchanged. Match one asynchronous authorization to one provider call, not one per layer.
- [ ] **3 Run RED:** from `frontend/sdk`, `pnpm exec vitest run src/diagnostics/manual-payment-authorization.test.ts src/providers/stripe-driver.test.ts src/widget/generic-payment-widget.test.ts src/diagnostics/form-load-observer.test.ts src/widget/checkout-provider-view.test.ts`. Use real production SDK code; only external Stripe/time/HTTP boundaries doubled. Anchor: `expect(confirmPayment).toHaveBeenCalledTimes(0)` before permission and after stale/failed permission; `expect(confirmPayment).toHaveBeenCalledTimes(1)` after one valid permit and after a concurrent/second attempt.
- [ ] **4 Implement the internal lease and narrow hooks.** No public API export, new dependency, Card access, retry path, portal or Shadow DOM change. Keep old denied handles denied even after a later new widget is armed.
- [ ] **5 GREEN:** focused tests, full SDK `pnpm test --run`, `pnpm typecheck`, `pnpm build`; all pass offline. Independent task review and affected rerun. Local commit `feat: gate manual Sandbox confirmation with a one-use SDK lease`.

### Task 3: One-shot payment gateway, manual page and isolated packaging

**Files:**
- Create: `infrastructure/stripe-manual-payment-gateway.mjs`, `.d.mts`, `.test.mjs`.
- Create: `examples/demo-site/manual-payment.html`, `src/manual-payment.ts`, `tests/support/manual-payment.test.ts`, `manual-payment-build.test.ts`, `manual-payment-config.test.ts`.
- Modify/test: `examples/demo-site/vite.config.ts`, `Dockerfile`, `playwright.config.ts`; existing staged-COPY/build/discovery/privacy tests.

**Interfaces:**
- `createManualPaymentGateway({origin,assetEndpoint,session,authorizeConfirmation:()=>Promise<true>},limits,deps?)` returns `open():Promise<{url:string}>`, `wait():Promise<{outcome:'CONFIRMATION_GRANTED'|'NOT_STARTED'|'FAILED'}>`, `close(limits):Promise<boolean>`. `wait` completes after the sole grant or cutoff; it is never payment-success proof. Permit commitment precedes response; ambiguous delivery stays consumed.
- Fixed routes: one GET `/manual-payment.html`, exact manifest-owned `/assets/...`, private-query-sanitized `/payment-return.html`, one same-origin POST `/manual-payment-session` `{}` and one POST `/manual-payment-confirmation` `{}`. No other route or general proxy. Cookie, exact Host/Origin, JSON32byte maximum, no duplicate keys/extra fields/retries. Return route must not grant a new session/confirmation.
- `mountManualPaymentPage(root,deps?):{close():void}` imports real widget+new lease from the same source module graph, consumes the one private session and calls `recoverCheckout`/one Resume. Captured lease authorize function calls the confirmation endpoint once; no HTTP retry. Missing module instrumentation fails closed before Pay.

- [ ] **1 RED gateway tests:** prove one document/session/permit, durable authorization before response, rejected Host/Origin/cookie/method/body, second tab, aborted response, concurrent requests and post-cutoff activity cannot expose/regrant a session. Hanging request/fetch/bind/close/late success must stay bounded, cancel/drain owned work and never silently change ports.
- [ ] **2 RED real-page tests:** new page uses the real SDK with synthetic provider; one Start/Resume and one permit/confirm; invalid or expired/mixed session, second click/Enter/programmatic call, unload, late callback, closed gateway and rejected grant never retry. User card values are never read/logged. UI states browser success separately from final server proof, and cannot offer retry/recovery after any attempted confirmation.
- [ ] **3 RED build/discovery tests:** actual `manual-payment` Vite graph includes only the new page plus safe return handling and one SDK graph; production/form-diagnostic builds exclude the new entry and preserve old behavior. Reproduce Docker COPY inputs in a fresh staged tree and run demo typecheck/build there without Docker/private infrastructure. Prove support tests excluded by actual config plus Playwright `--list`, with normal checkout/donation/subscription specs retained. Canary return queries never appear in logs.
- [ ] **4 Run RED:** sanitized `node --test --test-concurrency=1 infrastructure/stripe-manual-payment-gateway.test.mjs examples/demo-site/tests/support/manual-payment.test.ts examples/demo-site/tests/support/manual-payment-build.test.ts examples/demo-site/tests/support/manual-payment-config.test.ts`; record behavioral mismatches. Anchors: `assert.equal(permitReplies,1)`, `assert.equal(secondSession.statusCode,410)`, `assert.equal(fakeStripe.confirmCalls,1)`, `assert.equal(capturedLogs.includes(privateQueryCanary),false)`. No actual server/Docker/Sandbox or public network.
- [ ] **5 Implement gateway/page/build wiring.** Keep only explicit pure browser-module COPYs if necessary, never `COPY infrastructure/`. Serve actual fixed stdout URL only after successful owned bind; do not consume the user's one-shot page from tests/operator during a real run.
- [ ] **6 GREEN:** targeted tests and all demo support tests, SDK/demo typecheck, new/default/form-diagnostic real builds, ordinary Playwright full `--list` (listing only; no tests/server/browser). Independent task review, rerun affected checks, local commit `feat: add isolated manual Sandbox payment page and gateway`.

### Task 4: Bounded controller, authentic CLI handoff, report and operator guide

**Files:**
- Create: `infrastructure/stripe-manual-payment-auth.mjs`, `.test.mjs`; `stripe-manual-payment.mjs`, `.d.mts`, `.test.mjs`; `stripe-manual-payment-schema.mjs`, `.d.mts`, `.test.mjs`.
- Modify: `docs/local-development.md`; mark this plan's gates only from verified evidence.

**Interfaces:**
- `authorizeManualPaymentCli({cli,runId},limits,onAuthorization,deps?):Promise<true>` accepts only the existing fixed isolated login/complete descriptors, executes with callback+actual child-close ownership and original bounded timers, emits only a validated genuine auth handoff via `formatSandboxAuthorization`. Never evaluate `next_step`, infer a device path, reuse global credentials or run arbitrary commands.
- `runManualPayment(options:ManualPaymentOptions,limits:Limits,deps?):Promise<{report:ManualPaymentReport,exitCode:0|1}>`; admitted single invocation only. Order: preflight→named bootstrap prepare→CLI authorization/binding/listener→bootstrap start/verify→single provisioner prepare→CLI baseline bind→gateway open/actual URL→single grant→server proof→CLI Intent/Event proof→cleanup→single final report. Grant callback is `provisioner.markConfirmation` under the original authority.
- Internal CLI `assertManualPaymentListener(limits):true` synchronously checks the original manual binding/listener/deadline without I/O, provider reads, rebind or renewal. Check immediately before awaited durable `markConfirmation` and again afterwards immediately before literal true, with no intervening await. A committed permit remains consumed/reportable if subsequent listener loss denies the browser reply; the result remains PENDING with no retry.
- For no grant, `verifyUnused` and original provider read establish unused state; NOT_STARTED is exit1, not accepted payment. For any unknown/failure, no new financial write. A read/provisioning uncertainty prevents later Pay authority and latches PENDING.
- `validateManualPaymentReport`, `formatManualPaymentReport`, `parseManualPaymentReport` enforce the exact common contract, duplicates/length/domain matrix and success proofs. CLI snapshots allowlisted raw env only, takes zero positional args, owns signal subscriptions and one bounded stdout attempt. Imports inert; direct entry exits only its own process.

- [x] **1 RED auth tests:** malformed/untrusted URL/code/descriptor/env and secret-like output fail without following links or additional command dispatch; exact login+complete succeeds. Child callback without close, abort/nonzero/signal/timeout, late success and stalled stdout never renew budgets, start listener after failure or claim confirmed cleanup.
- [x] **2 RED controller tests:** assert exact dependency call order/cardinality and normalized flags. One prepare/check-out/gateway/session/grant; Pay only after original baseline. Before/after-grant failure, browser-only success, expired code, source/port conflict, listener loss, original deadline and malformed proof stop later operations; no refunds/extra cases/full runner/private fetch by imports.
- [x] **3 RED cleanup/report tests:** gateway closes/drains, provisioner closes, listener/children stop before bootstrap stop; each proof independent. If one fails, others still get their bounded cleanup attempt, only positive verified proofs are true, overall exit1, containers/data retained. Abort/late acquisitions remain owned, no post-authority provider read or late upgrade from PENDING. Strict wire≤4096bytes rejects extra/duplicate/accessor/canary data and invalid success matrices.
- [x] **4 Run RED:** sanitized `node --test --test-concurrency=1 infrastructure/stripe-manual-payment-auth.test.mjs infrastructure/stripe-manual-payment.test.mjs infrastructure/stripe-manual-payment-schema.test.mjs`; require named assertion failures. Anchors: `assert.equal(validateManualPaymentReport(allTrueSuccess)?.result,'SUCCEEDED')`, `assert.equal(validateManualPaymentReport({...allTrueSuccess,webhook_verified:false}),null)`, `assert.equal(validateManualPaymentReport({...allTrueSuccess,runtime_closed:false}),null)`, `assert.equal(financialRetries,0)`. No real CLI, Docker, Stripe, HTTP listener or private credentials.
- [x] **5 Implement controller/default private factories/report and guide.** Guide documents flags, original windows, source/ownership preflight, one fresh real run only after gates, ordinary Chrome copy-paste URL, one Start/Pay, official test card4242/future date/three-digit CVC, no real card/reload/retry, close tab/report observation, pending handling and preserved resources. Local private keys never appear in invocation examples.
- [ ] **6 GREEN final offline gate:** repository root `node --test --test-timeout=30000 --test-concurrency=1 infrastructure/*.test.mjs`; from `frontend/sdk`, `pnpm test --run`, `pnpm typecheck`, `pnpm build`; root `node --test --test-concurrency=1 examples/demo-site/tests/support/*.test.ts`; from demo, `node node_modules/typescript/bin/tsc --noEmit`, `pnpm build`, `pnpm build --mode manual-payment`, `node node_modules/@playwright/test/cli.js test --config=playwright.config.ts --list --reporter=list`. Each command must finish exit0; suites have zero failures, discovery contains ordinary browser specs and no support regressions. No Maven if Java/API/DB untouched. Fresh whole-delta independent review checks all five Review Focus classes; resolve findings and rerun affected/full gate as appropriate. Local commit `feat: orchestrate one manual Sandbox payment with verified cleanup`.
- [ ] **7 Source/permission handoff:** confirm clean reviewed committed source and all task reviews. User authorization permits one fresh manual-payment invocation only; recheck disk/networks/ports and previous owned closures read-only. Do not delete resources to make room or renew an expired run. Start the new entry exactly once; deliver actual fresh CLI authorization URL/code if required and actual gateway URL only once printed. Do not pre-open/probe it.
- [ ] **8 Final report:** resume the same process for its sole report and actual exit code; correlate provider/webhook/journal/callback/cleanup booleans, record finite safe audit evidence and human observation separately. Report only this single payment's result, never full Stage5A/live readiness. No automatic new attempt after a failed/PENDING result.

## Plan self-review and current evidence

- All design constraints map to a task: admission/private/runtime/provider boundaries1; immutable one-confirmation authority2; user page/session/privacy/build3; human auth, orchestration, reporting and final lifecycle4.
- Interfaces/field names and finite report domains are specified above; later tasks consume those names, not new implicit API or unspecified helpers. Existing SDK/public backend signatures stay unchanged.
- Five Review Focus classes have explicit tests in their owning tasks. Missing provider/webhook/callback evidence is PENDING, not a mocked success or permission to reconcile.
- Current worktree/source verified read-only; targeted untouched baseline passed101/101 (`stripe-manual-form-options`, `stripe-manual-form-schema`, `stripe-sandbox-owned`). This is baseline evidence, not RED→GREEN for any new feature.
- Design builder check completed independently: nothing important left. Implementation, new regression tests, task reviews and the new Sandbox invocation are all NOT_STARTED. No runtime/code changes are included in these documents.

## Execution handoff

Recommended: subagent-driven execution of these four sequential tasks, a fresh independent reviewer for each and a whole-delta review at the end. Confirm this plan captures the intended scope before implementation. The approved single real attempt remains after all gates, never parallel with offline development.
