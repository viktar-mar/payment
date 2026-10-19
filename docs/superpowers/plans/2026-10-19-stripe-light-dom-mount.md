# Stripe Light DOM Mount Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Исправить несовместимый монтаж Stripe Element для Catalog и Donation, сохранив оформление и точную безопасную диагностическую привязку.

**Architecture:** Внутренний SDK owned-mount helper владеет Stripe Light DOM host и Shadow DOM wrapper/slot. Виджеты сохраняют прежний UI, а диагностика находит host только по внутренней принадлежности. Fake остаётся на прежнем пути; unsupported nested ShadowRoot явно отклоняется без provider-capability.

**Tech Stack:** TypeScript, Web Components/slots, существующие Stripe.js10.1.0, Vitest/jsdom, Node test/Vite, установленный Playwright Chromium. Без новых зависимостей.

**Spec:** `docs/superpowers/specs/2026-10-19-stripe-light-dom-mount-design.md`.

**Status:** Approved by user on 2026-10-19. Execution method: subagents, independent task reviews and final review. Baseline source740e91a1cce9afc9e268f01d7f464a4fec91fd2f.

## Global Constraints

- Sandbox пока не запускать.
- Работа остаётся в существующем worktree `codex/stripe-sandbox-5a`, основная папка/main не меняется.
- Stripe-контейнер обоих виджетов должен находиться в Light DOM того же документа и отображаться в прежнем месте виджета через именованный slot.
- У Fake сохраняется прежний путь монтажа.
- Контейнер принадлежит конкретному виджету и конкретному поколению; не использовать глобальный DOM-поиск или публичный подменяемый resolver.
- Обнаруженная утрата binding окончательна; immutable deny подтверждения сохраняется после close/loss.
- Сохранить конечные схемы, provenance BROWSER_REPORTED, original60s observation, original15min authority и original cleanup ceiling.
- Не читать секреты/env/приватные логи или iframe contents/URL, не выполнять Stripe API/CLI, новые backend операции, Docker-запуски, автоматические повторы и установку зависимостей.
- Не ослаблять CSP, не подделывать ready и не подтверждать оплату.
- Локальные task-коммиты допустимы; push/merge/main/worktree cleanup в этот план не входят. Сохранить проверочные артефакты до согласования следующего шага.

## Review Focus

1. Fake и несколько виджетов на странице: cleanup одного экземпляра не затрагивает другой или чужой DOM — Task1.
2. Slot/host/wrapper substitution и detach/reinsert в одном JavaScript turn: retired/утраченный mount не восстанавливает диагностическую привязку — Task1.
3. Widget внутри стороннего ShadowRoot: Stripe fail-closed до capability, Fake продолжает прежний путь — Task1.
4. Ready до/после 15s checkpoint и callback после close/loss/deadline: никакого ложного ready, лишнего отчёта или снятия deny — Task2.
5. Настоящий DOM и isolated COPY graph: тест не должен проходить лишь благодаря принимающей любой host заглушке Stripe или доступности полного checkout — Task1 и Task2.

## File Map

- New `frontend/sdk/src/widget/provider-mount.ts`: owned host/wrapper/slot lifecycle и внутренний resolver.
- New `frontend/sdk/src/widget/provider-mount.test.ts`: lifecycle и ownership контракт.
- `frontend/sdk/src/widget/generic-payment-widget.ts`, `frontend/sdk/src/donations/generic-donation.ts`: используют helper, сохраняют Fake/UI.
- `frontend/sdk/src/diagnostics/form-load-observer.ts`: binding проверяет identity текущего owned host, а не прежний ShadowRoot.
- Existing widget/donation/observer/driver test files and `frontend/sdk/src/test/stripe-ui-fixtures.ts`: реальные DOM-контракты и regression coverage.
- `examples/demo-site/src/form-load-manual.ts`, `form-load-diagnostic.ts`, `manual-form-readiness.ts`: owned host для snapshots, load и геометрии; Pay по-прежнему в Shadow DOM.
- Existing `examples/demo-site/tests/support/form-load-{manual,diagnostic}*.test.ts`: source/graph/COPY сценарии.
- New `examples/demo-site/tests/support/provider-mount-browser.test.ts` and optional test-only entry fixture alongside it: network-denied native DOM integration.
- `docs/local-development.md`: описать исправление/границы доказательства, не объявлять Sandbox acceptance.

### Task 1: Owned provider mount для Catalog/Donation и SDK binding

**Files:** SDK files from File Map only; CSS hashes/policy only if own stylesheet bytes necessarily change and both consumers are kept exact.

**Interfaces:**
- Consumes: actual widget HTMLElement, existing shadow anchor HTMLElement, `CheckoutSession['providerType']`.
- Produces: internal `OwnedProviderMount { readonly host: HTMLDivElement; destroy(): void }`, `createProviderMount(widget: HTMLElement, anchor: HTMLElement, providerType: CheckoutSession['providerType']): OwnedProviderMount`, `ownedProviderHost(widget: HTMLElement): HTMLDivElement | undefined` in `frontend/sdk/src/widget/provider-mount.ts`.
- No export from the SDK public entry point. Task2 imports this exact internal module alongside the existing internal diagnostic observer. Store identity in private module state, not public properties/attributes.

- [ ] **Step 1: Write RED tests against the real widget mount boundary.** For Catalog and Donation assert supplied Stripe host is connected, `host.ownerDocument.documentElement.contains(host) === true`, `host.getRootNode() === host.ownerDocument`; a shadow slot renders this same owned host. A detached fake or a slot-only claim is insufficient. Show these tests fail on the old mount structure before product edits.
- [ ] **Step 2: Add ownership/lifecycle RED tests.** Two instances stay independent; theme/locale updates retain host identity; cleanup removes only its own host/wrapper/slot; stale destroy cannot remove replacement; forged lookalike nodes cannot become owned. Host/slot/wrapper removal, replacement, same-turn detach/reinsert and slot reassignment revoke diagnostic ownership permanently. Real widget disconnect/reconnect does not revive old binding. Nested external ShadowRoot rejects Stripe before prepareClientPayment; Fake retains legacy mounting.
- [ ] **Step 3: Implement the helper and both consumers minimally.** Keep existing shadow wrapper styles if possible so CSP hashes stay unchanged. Stripe host is widget-owned Light DOM projected through a named slot; Fake retains previous mount placement. Validate ownership, document connection, slot association and current lease. Revocation must be sticky; queued DOM changes must be considered before returning a positive resolver result. Destroy idempotently removes only its exact nodes and retires that lease. No global portal, public resolver or additional Stripe capability.
- [ ] **Step 4: Update SDK diagnostic binding and existing fixtures.** Binding must be to the exact current owned host/generation. Keep the captured immutable deny even when observer or lease is closed/lost. Tests for generic manual binding use a valid owned lease rather than granting arbitrary shadow nodes a positive binding. Preserve actual ready-before/after-mount, loaderror, abort, presentation and Fake behaviors.
- [ ] **Step 5: Verify GREEN and regression.** From `frontend/sdk`, run installed Node against `node_modules/vitest/vitest.mjs run --maxWorkers=1` (full SDK suite), `node_modules/typescript/bin/tsc -p tsconfig.json --noEmit`, then `node_modules/typescript/bin/tsc -p tsconfig.json`. Use sanitized env and existing dependencies; record exact commands, RED assertions and full summaries. Demo remains Task2's consumer; do not claim all frontend integration is complete yet.
- [ ] **Step 6: Self-review, local commit and report.** Commit only task files, never ignored env/results. Independent reviewer checks this task's spec compliance and quality before Task2. Report all failures/limits; no Sandbox evidence claims.

### Task 2: Owned diagnostic readers, native DOM regression и packaging

**Files:** Demo diagnostic/readiness sources, support tests from File Map, docs/local-development.md; Task1 SDK only for independently reviewed fixes required by integration.

**Interfaces:**
- Consumes: `ownedProviderHost(widget: HTMLElement): HTMLDivElement | undefined` from Task1, existing finite FormDiagnosticSample/manual-v2 evidence schemas and observer deny.
- Produces: unchanged diagnostic wire contracts using the exact owned Light DOM host; a repeatable offline native-browser DOM-contract test, not a real Stripe smoke test.

- [ ] **Step 1: Write RED source and packaged-entry tests.** With real SDK and strict synthetic provider, require document-owned Stripe host, accept coherent ready, keep iframe-only pending, preserve one Start/one session/one first Attempt and two-report limits. Replace old assertions requiring host.getRootNode() to equal widget.shadowRoot. Ready and no-ready control cases must both be exercised; synthetic provider cannot simply emit ready for any container.
- [ ] **Step 2: Write RED evidence/ownership tests.** Manual/automatic readers resolve only the owned host, not a shadow wrapper or foreign matching class. Observe native load for the current exact iframe in its new tree, reset evidence on frame changes, preserve unavailable/multiple categories, visibility across the slot projection and ancestors, historical pending checkpoints and sticky loss. Test close, pagehide, abort, lost lease, same-turn mutations, late callbacks and ready after cutoff. No new field, report, authority window or provider data.
- [ ] **Step 3: Implement readers minimally.** Use internal ownedProviderHost for element geometry/frames. Keep Pay selectors local to the owned widget's shadowRoot. Update only observer listener roots/owned identity needed for Light DOM and slot-composed visibility. Never inspect cross-origin frame contents/URL and never turn outer geometry/load into ready. Preserve immutable deny after observer closure and binding loss.
- [ ] **Step 4: Native-browser offline characterization.** Add `provider-mount-browser.test.ts` using installed Chromium and real built/source-bundled SDK, synthetic data only. A fulfilled in-process document and intercepted synthetic API/provider boundaries exercise both Catalog and Donation; every other browser request and WebSocket is denied, service workers blocked. Assert real provider host is a document descendant and actually projected by the slot, lifecycle/multiple-instance behavior, Fake regression and no confirmation. No external JS fetch, Docker, user browser, screenshots/traces/console dumps or dependencies. Explicitly label result DOM compatibility, not Stripe ready.
- [ ] **Step 5: Verify source, graph, COPY and final regression.** From repo root with installed Node: `node --test --test-concurrency=1 examples/demo-site/tests/support/form-load-manual.test.ts examples/demo-site/tests/support/form-load-diagnostic-build.test.ts examples/demo-site/tests/support/form-load-manual-build.test.ts examples/demo-site/tests/support/provider-mount-browser.test.ts`. Run all related `infrastructure/stripe-manual-*.test.mjs`, schema/environment/controller regression and the demo's existing source diagnostic tests. Run full SDK suite again and SDK/demo TypeScript build checks; run production and diagnostic Vite modes with exact installed CLI. COPY filesystem tests must remain restricted to public sources and run without Docker. Record any relevant test command discovered in the repository rather than inventing package scripts.
- [ ] **Step 6: Document, self-review, local commit and task review.** Describe compatible mounting and limitations, unchanged authority/deny, no real Sandbox acceptance. Include exact TDD and regression evidence in report. Independent task reviewer checks diff/requirements before final review.

## Final Gate

- [ ] Independent whole-fix review covers Task1+Task2 from this fix's original base, including ownership, Fake, UI/CSP, diagnostic limits, build graph and test honesty. Surface all findings and fix covering tests before completion; do not hide deferred findings.
- [ ] Fresh final verification on reviewed HEAD, clean tracked status, no Sandbox/provider/Docker calls. Local commits only; no push/merge/main changes.
- [ ] Hand off results and state explicitly that real Stripe ready remains unverified until a separately authorized bounded Sandbox check.
