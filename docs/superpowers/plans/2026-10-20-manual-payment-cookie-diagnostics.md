# Manual payment cookies and safe load diagnostics implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute and review each task before the next one; then obtain a combined final review.

**Goal:** Fix the offline-reproduced extra-cookie rejection and make future manual-payment page load failures distinguishable without exposing private data.

**Architecture:** Keep the existing one-shot gateway, page and SDK authority. Authenticate exactly one owned cookie while tolerating other well-formed cookie pairs; classify early page failures using a small closed set of locally assigned UI codes. Do not change controller reports or collect browser telemetry.

**Tech Stack:** Node24 native tests, TypeScript/Vite, existing real-SDK synthetic JSDOM fixtures. No new dependencies.

**Spec:** User-approved small-change description in chat on 2026-10-20: offline cookie handling and safe failure-stage diagnostics, with subagents, regression tests and independent review. The cookie defect is reproduced offline; the cause of the original Chrome failure remains unproven. Operational boundaries are documented in `docs/local-development.md`, section "One manual Stripe Sandbox payment".

## Global Constraints

- Offline only: no Sandbox entry, Stripe CLI, provider HTTP, real browser session, real keys/configuration, Docker stack startup, payment, refund, reconcile or replacement fixture.
- Reuse this recovery worktree and branch; do not touch main, private runtime evidence or previous plan workspaces. Local task commits are allowed; no push/merge/PR in this task.
- Keep exact Host/Origin enforcement, single Cookie header enforcement, HttpOnly/SameSite cookie, one-shot session and confirmation, immutable HTTP/lifetime deadlines, authority binding, cleanup and fail-closed behavior.
- Never log or display raw cookie/header/body/URL/session/key/token/error/stack/card data. No console, storage, network telemetry, screenshots, DevTools or payment-field inspection.
- Add only finite locally assigned failure codes to the existing terminal load-error UI. Do not alter financial permission, controller proof fields, report format or interpret a UI code as financial proof.
- Use RED then GREEN regression evidence and existing related suites. All new tests must exercise production behavior, not source-text snapshots or copied parser logic.

## Review Focus

- Other localhost cookies before/after the owned pair must not turn legitimate session/confirmation into403; Task1 covers both routes and order/whitespace.
- Duplicate owned cookies and duplicate Cookie header lines remain403, including identical/conflicting values and both orders; Task1 covers these plus wrong/missing/quoted values and malformed pairs.
- Cookie-like substrings in unrelated values, equals signs, quoted unrelated values, controls and excessive input cannot confuse ownership; Task1 covers parser boundaries.
- Exceptions and HTTP response bodies with sensitive canaries must not enter the UI, logs, storage or subsequent requests; Task2 exercises only allowlisted codes and no raw propagation.
- Expiry, late completion, repeated clicks and errors still close the page authority without a second exchange or confirmation; Task2 extends the real-SDK synthetic lifecycle coverage.

---

### Task 1: Authenticate the owned cookie within a bounded cookie list

**Files:**
- Modify: `infrastructure/stripe-manual-payment-gateway.mjs`
- Test: `infrastructure/stripe-manual-payment-gateway.test.mjs`

**Interfaces:**
- Consumes: existing internal `header(req,key)` duplicate-header guard; fixed cookie name `payment_manual_payment` and generated64-lowercase-hex value; unchanged gateway factory/options and injected fake I/O.
- Produces: same gateway API and status codes, accepting one exact owned pair alongside unrelated well-formed pairs on both authenticated POST routes.
- No public parser API; a focused internal helper is sufficient.

- [x] Write regression tests with hand-derived requests: unrelated pairs before/after owned cookie, multiple pairs and optional spaces/tabs immediately after semicolons, before the next name; expect session200 and sole authorized confirmation200 with exactly one authorizer invocation.
- [x] Assert own name is case-sensitive and only an exact unquoted owned value grants access; reject missing/empty/wrong/quoted owned cookie, duplicate owned names even with identical values, conflicts in either order, duplicate Cookie header lines, array/absent header, malformed segments, controls and input over8192ASCII bytes. Denied responses must not leak a session/cookie or call authorization.
- [x] Cover unrelated names/values containing the owned-name substring, values containing equals signs and valid quoted unrelated values. Accept valid RFC6265-style unrelated cookie pairs; do not URL-decode or apply first/last-wins to the owned name. Reject invalid/non-ASCII/control input rather than normalize it into authority. Optional SP/HTAB is allowed only immediately after a semicolon, not before it, at header ends, around '=' or inside a value. Do not trim pairs/values into an authority match.
- [x] Run `node --test infrastructure/stripe-manual-payment-gateway.test.mjs`; preserve expected RED403-vs200 evidence before production edits.
- [x] Implement bounded cookie-list authentication while retaining unchanged Host/Origin/header multiplicity checks and one-shot/deadline behavior.
- [x] Run the same focused file GREEN and related `node --test infrastructure/stripe-manual-payment.test.mjs infrastructure/stripe-manual-payment-schema.test.mjs infrastructure/stripe-manual-payment-options.test.mjs`; preserve commands/counts and self-review.
- [x] Commit only Task1 files with `fix: authenticate manual payment cookie within cookie lists`. Write report, then independent task review gates completion.

### Task 2: Show a finite failure-stage code without collecting browser data

**Files:**
- Modify: `examples/demo-site/src/manual-payment.ts`
- Test: `examples/demo-site/tests/support/manual-payment.test.ts`
- Test if needed for the built graph: `examples/demo-site/tests/support/manual-payment-build.test.ts`
- Modify: `docs/local-development.md` (manual-payment subsection only).

**Interfaces:**
- Consumes: Task1's unchanged gateway contract; existing `post`, `validateSession`, widget construction, `armManualPayment`, mount and close lifecycle in the page.
- Produces: unchanged `mountManualPaymentPage(root,dependencies): {close():void}` and finite extra text in the existing terminal status element. No new event, report, endpoint, observer, console output or exported SDK option.

- [x] Extend the existing real-page/real-SDK synthetic fixtures first. Pin separate failure branches: session HTTP403 -> `SESSION_HTTP_FORBIDDEN`,410 -> `SESSION_HTTP_GONE`, other non-ok -> `SESSION_HTTP_ERROR`, fetch/body rejection/timeout -> `SESSION_HTTP_UNAVAILABLE`; JSON parse -> `SESSION_JSON_INVALID`; validated record/session failure -> `SESSION_INVALID`; widget registration/construction/configuration -> `WIDGET_SETUP_FAILED`; lease arming -> `AUTHORIZATION_BINDING_FAILED`; observer/append/initial inspection -> `WIDGET_MOUNT_FAILED`.
- [x] Keep diagnostics private to the page: codes must derive from local phase and narrow numeric HTTP status checks, not from caught exception properties/messages or response bodies. Existing error wording and stop/retry prohibition remain, with the code appended using textContent. No detail after Pay is newly exposed; authorization errors and financial results retain their current semantics.
- [x] Inject sensitive canaries through errors and response bodies and assert only the finite code is visible. Preserve assertions for zero card/iframe reads, zero credential storage, one exchange/confirmation, closure after failure, no reopen/retry and late callbacks unable to overwrite terminal diagnostics. Use production behavior, not source-text assertions. Existing mixed-graph instrumentation scenario must report the binding code without making its graph valid.
- [x] Run `node --test --test-timeout=60000 examples/demo-site/tests/support/manual-payment.test.ts` and observe expected RED before production edits; record output.
- [x] Implement minimal stage tracking/classification without modifying the SDK, gateway financial contract or browser data collection. Fixed codes are diagnostic hints, not proof of a provider/network/root cause.
- [x] Document codes and their limits in the existing manual-payment operational guide: UI-only observation; previous Chrome cause unproven; no cookies/DevTools/raw output requested; no permission for another live attempt. Keep current deadlines/one-shot instructions intact.
- [x] Run focused page/build fixtures GREEN, demo typecheck (`pnpm --dir examples/demo-site exec tsc --noEmit`) and SDK tests/typecheck/build as related regression. Run all native support fixtures once before task commit. No live Playwright config or Sandbox test may run.
- [x] Commit only Task2 files with `fix: classify manual payment form load failures safely`. Write report, then independent task review gates completion.

## Final offline checkpoint

- [x] Root independently verifies both task reviews, the exact tree, complete offline infrastructure suite and native demo support fixtures; run demo typecheck/build and SDK tests/typecheck/build with existing dependencies. Do not run backend Maven: no backend changes; the previously authorized checkpoint policy uses relevant regression here.
- [x] Fresh combined reviewer evaluates both changes and unchanged caller boundaries using the task reports and combined diff. Any Critical/Important finding is fixed and scoped re-reviewed before completion.
- [x] Keep the worktree/branch and operational evidence. Report local commits, fresh test counts, independent review outcome, any remaining uncertainty and that Sandbox was not run. Do not claim the original Chrome failure is fixed or the payment accepted based on offline evidence.

Completed offline on 2026-10-20. Cookie repair `787d263`, diagnostics `c658448`; independent task reviews and combined review approved with no findings. Fresh root verification: infrastructure1033/1033, native support321/321, SDK772/772, demo/SDK types and builds passed. The initial restricted-permission and sanitized-PATH test setup failures were retained and resolved without product workarounds. No Sandbox/CLI/provider payment, push, merge or PR was performed. Earlier Chrome cause and Stage5A operational acceptance remain unproven.
