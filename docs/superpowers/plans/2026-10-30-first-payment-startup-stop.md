# Owned first-payment startup and stop repair implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. User requested continuous offline implementation with subagents/tests/independent review. No interim approval pause.

**Goal:** Repair source-proved startup lifecycle and stop defects, and retain bounded startup evidence without guessing the native cause.
**Architecture:** Keep existing owned bootstrap and first-payment owner. Stop consumes only the remaining shared cleanup budget and proves actual owned stopped state independently of historical START uncertainty. The dedicated Java runtime releases every resource created before a startup failure. Trusted finite startup metadata reaches the existing final diagnostic line.
**Tech Stack:** Java25, cached offline Maven compile + exact pure Jupiter; Node/TypeScript injected tests; no new dependency.
**Spec:** docs/superpowers/specs/2026-10-30-billing-first-payment-scenario.md; current user request for offline backend startup/incomplete stop fix.

## Global Constraints

- No Docker/daemon/CLI, Sandbox/provider/network, DB/realSQL, Spring/application/context/server/browser startup, Python, installation, private env/key/CLI/archive/recovery/raw-native-log reads or original operation replay. Use only sanitized historical report/metadata.
- Never repeat/adopt/reset any DISPATCHING. Preserve all old/current evidence, native data/volumes, original stack and other projects. No deletions except fresh synthetic test temporary artifacts.
- Local execution only audited injected Node tests and cached offline Maven -DskipTests test-compile plus explicit standalone Jupiter selectors with automatic engines/listeners/extensions disabled. No ordinary Maven test/verify or Playwright discovery. No hosted CI/push in this offline task.
- Preserve authority, identity/source/image/container/volume/network proofs, admission before resource construction, provider/read/dispatch budgets, financial validation, success DTOs, first-failure latch and no retry. Root owns Git; no merge/forcepush/PR.
- Cleanup remains stop-only, <=120000ms shared total, original listener-before-container order. Never clear a historical pending flag or acknowledge an uncertain intent to claim a successful stop.
- For this repair, permitting stopped-state success with unacknowledged intent is first-payment-only and only START_SERVICE entries naming exact previously recorded owned full container IDs. Unknown creates/replacements/foreign or extra resources retain refusal before any stop.
- No exception messages/types/causes, raw output/logs, identifiers, secrets, arbitrary strings/counters in published diagnostics. Only trusted fixed enums and existing one-line code/cleanup format after close.
- Native startup cause remains unknown: post-cleanup137/OOMKilled=false proves neither original failure nor OOM. No health/ownership gate or startup timeout relaxation by guess. Native/full5B NOT_ACCEPTED.

## Investigation

Current source784e28b; native run15f2c8fd-dd39-4986-a553-54c5902ea8e5 stopped at backend START_SERVICE before any fixture/financial dispatch. Source confirms: owner clips every cleanup method including bootstrap.stop to30000ms despite shared120000ms; bootstrap refuses stop acknowledgement after stopped proofs if a START_SERVICE intent stays DISPATCHING; runtime leaves application/control resources live when setup after app.run throws; startup diagnostics vanish through generic catches/prepare-only formatter. These are source defects, not established historical causal attribution. Packaging/launcher looked consistent, so do not change it speculatively.

## Review Focus

- Multi-resource stop surviving30sec still must end within original shared120sec and never reset budget after another cleanup step.
- Actual nonrunning state can be proved with original owned START uncertainty; pending evidence/no-retry must remain durable.
- Unknown creation/identity/state drift or deadline retains fail-closed behavior and never stops unowned target.
- Every created Java startup resource closes once when later setup fails; ordinary context shutdown and failure unwind cannot double-close or run provider work.
- Only fixed startup categories from trusted state/owned errors enter terminal reporter; foreign/hostile errors never get inspected or gain diagnostic authority.

### Task1: repair first-payment stop budgets and stopped-state proof

Files: infrastructure/stripe-billing-first-payment.mjs, stripe-billing-first-payment.test.mjs, stripe-sandbox-bootstrap.mjs, stripe-sandbox-bootstrap.test.mjs. Docs only if needed in task report; final documentation belongs Task3.
- [x] RED actual owner with fake time: failed startup then stop completing after45sec succeeds within existing total120sec, stop once; expired shared budget/late resolution cannot pass.
- [x] RED real injected firstPayment bootstrap: backend health fails after one START dispatch, exact stop completes despite that historical START_SERVICE uncertainty; metadata/ledger remains PENDING and original intent unchanged; later start/verify denied without effects.
- [x] Give bootstrap.stop remaining shared cleanup budget capped120000ms; retain other cleanup method caps/order/latches.
- [x] Narrow first-payment-only pending START_SERVICE allowance after complete ownership and final actual nonrunning proof. Keep unknown create/foreign/replacement/extra-resource/no-ACK-start denial unchanged elsewhere. Do not parse textual stop ACK as state or relax existing proofs. Capture GREEN related injected tests and independent spec/quality review.

### Task2: exception-safe dedicated Java startup lifecycle

Files: OwnedBillingFirstPaymentRuntime.java and RuntimeTest.java; only if needed exactly one named nested StartupResources guard plus explicit exporter inventory/test updates in backend/export-billing-first-payment-runtime.sh and infrastructure/billing-first-payment-runtime.test.mjs.
- [x] RED pure fake lifecycle: application construction/bind/executor/wiring/control-start failure closes all previously created resources exactly once, no later setup/scenario/provider effects; successful startup keeps resources alive until normal context close, repeated close is harmless.
- [x] Wrap main post-admission resource acquisition/ownership transfer. Close context, bound control server, executor and scenario on later startup failure even if another close throws. Preserve application args/env admission before any real construction and existing launch/protocol.
- [x] Prefer methods on existing Runtime; if one nested guard is materially simpler, explicitly add only that class to the exact inventory, expected count and synthetic exporter tests. No broad test/support export.
- [x] Cached offline compile and exact pure Runtime/related Java selectors; synthetic export filesystem tests only, no real application/server. GREEN and independent task review.

### Task3: bounded startup diagnostics through existing reporter

Files: infrastructure/stripe-sandbox-bootstrap.mjs + test, stripe-billing-first-payment.mjs + test and .d.mts only if necessary, examples/demo-site/tests/support/stripe-billing-first-payment.test.ts, docs/local-development.md.
- [x] RED real injected bootstrap startup command/state/proof/timeout failures, owner propagation/first-failure retention and real final formatter/parser/reporter, including failure during cleanup. Unknown/hostile data stays opaque.
- [x] Capture only fixed checkpoint/service/category from already owned/validated state or existing internal WeakMap-created failures, without any new Docker/provider read or raw dependency property access. Expose a minimal first-payment-only read-only diagnostic accessor or equivalent trusted weak metadata; keep other modes' resource/protocol shape unchanged.
- [x] Owner retains the first valid startup code before cleanup. Existing PREPARE diagnostic validation/HTTP protocol stays unchanged; terminal formatter/parser admits only finite startup codes too, same one line and cleanup flag. No new financial permission/read/retry.
- [x] Documentation distinguishes verified defects from unproved native cause and stop-state truth from historical PENDING. Run final combined audited Java/Node/TS/static packaging checks and independent task review. No push/CI/native run.

## Execution record

Tasks1–3 completed with independent specification and quality approval. Review found and corrected the missed Spring close-event window (closed=true while active=true) and the owner/bootstrapped abort diagnostic ordering race. Final scoped proof is512 injected Node tests,330 unchanged-hash pure Java tests, cached TypeScript noEmit and bounded export checks (included in the512). Current Java correction compiled with targeted cached javac; the earlier whole offline Maven compile is historical, not a fresh final-source Maven claim. Native cause and full5B acceptance remain unproved/NOT_ACCEPTED.

Final integration gate: independent whole-current-change review, followed by one local reviewed commit. That gate is recorded in the ignored plan ledger; no push, hosted CI, Docker or Sandbox is part of this offline task.
