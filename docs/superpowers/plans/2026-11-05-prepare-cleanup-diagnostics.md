# Prepare request and cleanup diagnostics implementation plan

> For agentic workers: use superpowers:subagent-driven-development, task-by-task with independent review.

**Goal:** Distinguish the last local PREPARE boundary and the first missing cleanup proof without changing payment authority or outcomes.
**Architecture:** A module-private WeakMap exposes only finite source-owned runtime progress. The existing owner latches a prepare hint on its first generic rejection. An independent closed cleanup witness explains UNCONFIRMED through a separate bounded diagnostic line, preserving the primary failure and legacy format.
**Tech Stack:** Existing Node.js/TypeScript, built-in node:test, injected inert fixtures; no dependencies.
**Spec:** `docs/superpowers/specs/2026-10-30-billing-first-payment-scenario.md` and combined owner requirements in `docs/superpowers/specs/2026-11-01-first-payment-and-renewal-scenario-design.md`.

## Global Constraints

Offline only: no Docker, Stripe/network, installs, archives, real application/database/browser, financial actions or old operation replay. Preserve all historical reports, volumes, keys and artifacts. No push/native execution; coordinator may save a reviewed local commit.
Keep the original single-use CREATE/CONFIRM/advance authority, first-failure latch (including null diagnostic), original deadlines, <=120s dispatch/request bound, <=45min owner lifetime and separate <=120s cleanup allowance. No timeout increase, extra request, retry, late ACK/success, new authority or broader stop permission.
Preserve exact ownership/identity/response validation, unknown/consumed operation rules, cleanup order/counts and conservative browser knowledge snapshot. Do not await late browser settlement, upgrade UNCONFIRMED from later Exited, rewrite pending history or infer financial state from missing ACK.
Only fixed source-owned literals cross diagnostic boundaries; no error property reads, provider body/status/code text, credentials, IDs, timings or counters. Retain legacy formats, 160-character diagnostic envelope, 4096-character reporter buffer, one CLI handoff and one primary diagnostic. Task2 may emit at most one additional finite cleanup line.
No Java, schema, registry, SDK, financial predicate, graph, clock scheduling, bootstrap ownership/stop logic or ignored launcher edits. Execute only explicitly named audited inert Node tests, never default/wildcard discovery.

## Review Focus

- Outer owner cancellation wins before runtime rejection: Task1 snapshots source progress synchronously.
- Foreign objects/proxies: Task1 lookup has zero property reads and retains generic fallback/null failures.
- Late response/server envelope: Task1 terminal snapshot never changes or grants ACK/CONFIRM.
- Stopped containers with missing browser/listener proof: Task2 preserves UNCONFIRMED and explains only first missing component.
- Diagnostic floods/forgery: Task2 reporters reject unknown/wrong scope/oversized lines and emit each type once.

## Task 1: Source-owned prepare progress at first rejection

**Files:** modify `infrastructure/stripe-billing-first-payment-runtime.mjs`, its adjacent `.d.mts` declaration, `infrastructure/stripe-billing-first-payment.mjs`; tests `infrastructure/stripe-billing-first-payment.test.mjs`, `infrastructure/stripe-billing-first-renewal.test.mjs`.
**Interface:** export pure `billingFirstPaymentPrepareCheckpoint(runtime)` returning only `PREPARE_REQUEST_AUTHORITY`, `PREPARE_REQUEST_TRANSPORT`, `PREPARE_REQUEST_RESPONSE` or null for genuine module-created runtimes. Store provenance in a private WeakMap keyed by the frozen runtime object; never inspect runtime/error properties. Task2 consumes the unchanged owner result plus these accepted primary codes.

- [ ] Write inert tests first: canceled admission before callback/no prepare fetch => AUTHORITY; outer dispatch bound wins before client's catch while fetch pending => TRANSPORT; abort during synthetic response body => RESPONSE. Use mock timers/deferred promises, no real120s waits. Legacy generic PREPARE_REQUEST remains for foreign runtimes; hostile runtime/error/progress properties get zero reads; existing source-owned detailed error wins; earlier null failure stays null; late success/error cannot replace terminal code or produce ACK/CONFIRM/retry. Cover combined scope and formatter/parser strict finite recognition.
- [ ] Run behavioral red with real owner+runtime and injected boundaries, not only missing export compile/import red. Preserve original lookup fallback before implementing stage updates if needed.
- [ ] Set prepare progress synchronously before bootstrap admission, immediately before actual fetch, immediately upon fetch resolution before response access/body processing. Progress is a source location, not completed work or authenticated provider proof. Update only prepare route; no new awaits/reads/requests. In owner `fail`, enrich only first generic PREPARE_REQUEST before lifetime abort; never replace null or a more specific source-owned error. Direct runtime failure semantics may retain legacy generic abort code.
- [ ] Run `node --test infrastructure/stripe-billing-first-payment.test.mjs infrastructure/stripe-billing-first-renewal.test.mjs`; report exact red/green counts and limitations. No commit/push by worker; no own subagents/reviewers. Write task-1-report.md beside extracted brief. Independent review before Task2.

## Task 2: Finite missing-cleanup witness and safe terminal transport

**Files:** owner module/tests above and its adjacent `.d.mts` result/formatter declarations; both `examples/demo-site/tests/stripe-billing-first-{payment,renewal}.spec.ts`; both `examples/demo-site/tests/support/stripe-billing-first-{payment,renewal}-reporter.ts`; inert support tests `examples/demo-site/tests/support/stripe-billing-first-{payment,renewal}.test.ts`; docs `docs/local-development.md`, `docs/stage-5b-acceptance.md`.
**Interfaces:** owner result adds `cleanupFailure` (null or one literal below). Add `formatBillingFirstPaymentCleanupDiagnostic`, `parseBillingFirstPaymentCleanupDiagnosticLine` and combined `formatBillingFirstRenewalCleanupDiagnostic`, `parseBillingFirstRenewalCleanupDiagnosticLine`. Legacy primary formatter/parser and original payment diagnostic remain unchanged by this task.

- [ ] Add behavioral red: unresolved browser when owner aborts => BROWSER_UNSETTLED even if all stops succeed; settled browser with unconfirmed close => BROWSER_CLOSE; individual runtime/listener/start-settlement/bootstrap/fixture cleanup failure yields its matching witness when all prior proof known. Every callback success => null + confirmed; payment still rejected if earlier failed. Late completion cannot upgrade status or change witness. Hostile rejections are never inspected; original cleanup budgets/order/counts and no ACK/replay remain.
- [ ] Capture first missing proof in existing cleanup flow only. Closed names: BROWSER_UNSETTLED, BROWSER_CLOSE, RUNTIME_CLOSE, LISTENER_STOP, START_SETTLEMENT, BOOTSTRAP_STOP, FIXTURE_CLOSE. Keep current initial browser snapshot and cleanup operations exactly as before; no additional wait, probe, retry, timeout, bootstrap subdiagnostic or catch-up. Freeze witness via existing once-only cleanup result. PENDING remains independent from stopped state.
- [ ] Separate output grammar: `owned_initial_payment_cleanup_diagnostic code=<LITERAL>` or `owned_initial_and_first_renewal_cleanup_diagnostic code=<LITERAL>`. Only rejected CLOSED results with cleanupConfirmed=false and finite cleanupFailure format; confirmed/missing/invalid witness => null. Exact parser grammar, <=160 chars. No IDs or arbitrary text.
- [ ] Native specs print at most one new validated cleanup line after the unchanged primary diagnostic in finally. Reporter maintains independent one-shot primary and cleanup filters; combined reporter accepts only combined scope. Buffer4096, stderr/raw-error suppression, one CLI handoff unchanged. Test chunking, duplicate flood, wrong scopes, unknown names, extra fields, overflow and sentinels. Only inert imports/fake reporter/context fixtures; inspect side effects before execution, never run Playwright specs/browser.
- [ ] Update docs for consumed run483a0469: PREPARE_REQUEST exact cause unknown, local create-dispatch does not prove provider outcome, cleanupUNCONFIRMED is not contradicted by later7Exited, original PENDING retained, added locations are not root cause or financial/stop evidence, full5B NOT_ACCEPTED. Preserve earlier history.
- [ ] Run the two Task1 Node files plus `node --test --test-concurrency=1 examples/demo-site/tests/support/stripe-billing-first-payment.test.ts examples/demo-site/tests/support/stripe-billing-first-renewal.test.ts` after auditing their inert execution. Run cached `pnpm --dir examples/demo-site exec tsc --noEmit` (compiler only, no build/install/app) to verify changed reporter/spec declaration contracts. Run relevant finite closure/export checks only if exports change their declared contract; never wildcard. Write task-2-report.md; no commits/push/subagents by worker. Task review and final whole-patch review follow.
