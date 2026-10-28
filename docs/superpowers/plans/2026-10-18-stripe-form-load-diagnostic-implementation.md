# Stripe Form Load Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Реализовать отдельно включаемую диагностику одной загрузки Stripe-формы без подтверждения оплаты, проверить её только офлайн.

**Architecture:** Internal SDK WeakMap tracks one exact widget/host/generation and latches deny-confirmation. A separate prepare-only runtime owns one fresh anonymous checkout/Intent/first Attempt, public finite schema, driver and reporter; no full acceptance/CLI/listener entry is invoked. Existing full/Fake behavior remains unchanged; no actual runtime action is authorized by this plan.

**Tech Stack:** Existing TypeScript/Stripe.js/Vitest/Node ESM/Playwright/Vite/Docker bootstrap modules; no new dependency or backend schema.

**Spec:** [Approved form diagnostic design](../specs/2026-10-18-stripe-form-load-diagnostic-design.md), human implementation approval after commit73d832d.

## Global Constraints

- «Пока без запуска Sandbox»: only synthetic unit/contract tests, source builds and typechecks. No actual browser, Stripe API/CLI/OAuth, Docker/appstack, private keys/log reads, deletion/adoption/replay/rekey/new actual UUID/ports/budget/allowance.
- Runtime design: EUR20/ONE_TIME/NONE/anonymous, at most1Product/1Price/1Intent/1Checkout/initialAttempt1. No confirm/payment-method/customer/future-usage/Connect parameters, no card input/Pay/markPay/retry/refund/extras.
- Three distinct optins ENABLED+FORM_DIAGNOSTIC+ALLOW_PREPARE_ONLY; full-run/ALLOW_TEST_OPERATIONS flags/debug reject before private/dependency access. Default/full guards never accept diagnostic flags.
- One original15min active +2min cleanup; form15s checkpoint/60s max clipped to original remaining time. No renewed deadlines, automatic repeat or old state adoption.
- Separate mode creates fresh owned final backend directly (no full-mode placeholder deletion), uses private unregistered webhook verification value if required by backend configuration, does not mutate user's webhook secret or claim webhookReady. No CLI/listener is created.
- One exact weak registration; missing/duplicate instrumentation fails. View+handle deny latch survives disarm/destroy. Normal unarmed SDK path unchanged.
- Strict finite frozen scalar snapshots, max2/4KiB, no raw error/DOM/frame/URL/provider payload/input/body/header/secret. Cleanup and no-charge proof independent from UI; exit0 only FORM_READY_* with both confirmed.
- All source edits in existing linked codex/stripe-sandbox-5a worktree. Preserve all old evidence/resources/main. Final feature commit/push after review under standing permission; no mainmerge/PR/archive.

## Review Focus

1. A duplicate ESM observer instance or remounted widget must fail before falsely attributing readiness; Task1 registry handshake and Task3 built graph tests.
2. Disarm/cleanup cannot turn an already diagnostic view/handle into a payable handle; Task1 direct confirm-after-close tests.
3. Full-mode bootstrap must still require true listener persistence, while diagnostic mode never launders full optins or deletes placeholder resources; Task2 mode-crossing tests.
4. Client-session creates the first local Attempt: baseline0/final1 on success, incomplete0or1 stays failed, second never allowed; Task2 before/after real response contract tests.
5. A late browser acquisition/close, missing proof or malformed reporter input cannot become success from a ready Pay flag; Task3 late-resource/privacy/deadline/report framing tests.

## Files and shared contracts

Task1 owns `frontend/sdk/src/diagnostics/form-load-observer.ts`, its test, SDK widget/provider-view/driver instrumentation and focused new integration tests. No SDK public index/package exports.

Task2 owns `infrastructure/stripe-form-diagnostic-runtime.mjs` (+`.d.mts`,tests), private provider/provision helpers (+tests) as necessary, and additive separate factories/tests in existing `stripe-sandbox-bootstrap.mjs`/`stripe-sandbox-owned.mjs`. It does not modify existing full provisioner/acceptance flows or page/schema files.

Task3 ROOT owns `infrastructure/stripe-form-diagnostic-schema.mjs` (+`.d.mts`,tests), `infrastructure/stripe-form-diagnostic.mjs` (+`.d.mts`,tests), `examples/demo-site/form-load-diagnostic.html`, `src/form-load-diagnostic.ts`, `tests/support/form-load-diagnostic.ts`(+tests), `tests/form-load-diagnostic.spec.ts`, separate `playwright.form-diagnostic.config.ts` and safe reporter(+tests). It owns Vite/Dockerfile/default-test exclusion/local guide wiring. Shared build mode is exactly `form-diagnostic`; default demo entry is unchanged.

SDK consumer API from internal module:

```ts
armFormLoadDiagnostic(widget: HTMLElement): {
  snapshot(): Readonly<FormLoadSnapshot>;
  close(): void;
};
```

`FormLoadSnapshot` exact keys: `version:1`, `binding:'ARMED'|'BOUND'|'LOST'|'CLOSED'`, booleans `capability_started,capability_ready,loader_started,loader_ready,element_created,mount_called,mount_returned,ready_seen,loaderror_seen,view_completed,widget_completed,confirmation_blocked`, `catchup:'NOT_STARTED'|'PENDING'|'RESOLVED'|'REJECTED'|'ABORTED'`, `operation:'PENDING'|'COMPLETED'|'FAILED'|'ABORTED'`. Internal writer functions are Task1's choice, no payload/callback inputs. Register known widget instrumentation in its constructor so foreign/duplicate registry arm cannot succeed. Bind host/generation once before capability work; later configure/disconnect/repeated mount invalidates binding. Initial setup of an armed not-yet-connected widget must be supported.

Private runtime API (independent of browser/schema):

```ts
createFormDiagnosticRuntime(options, dependencies?): Promise<{
  prepare(limits): Promise<FormDiagnosticHandoff>;
  verifyNoConfirmation(limits): Promise<true>;
  close(cleanupLimits): Promise<true>;
}>;
```

Inert factory validates optins before reading dependencies. Handoff exact `{runId,expectedSource,endpoint,session}`: endpoint=owned demo origin; session is the ordinary version2 recovery record with apiBaseUrl/siteKey/priceKey/checkoutId/checkoutToken/expiresAt/providerType/confirmationMode/state, transient private data for addInitScript only. No action/refund/confirm capabilities. Prepare one time only, finalproof one time only; repeated/unknown outcomes latch failure. Core options exact `{env,runId,expectedSource,daemon,ports}` six existing named loopback ports. Parent passes original `{deadline,signal}` throughout; runtime captures first deadline and refuses renewal. Runtime close never deletes resources; private journals/locks/proofs retain original keys/unknown states. Startup and no-charge fields are genuine bounded allowlisted backend/provider reads, not callback `true` claims. Private binding pins project's existing approved external account and2026-09-30.endive, no overridden Stripe-Version/Connect header; pk/account pairing remains unproved. Separate factory may share core validation helpers but cannot invoke full acceptance or fake full flags/listener acknowledgement.

Browser API:

```ts
runFormLoadBrowser(browserType, handoff, limits): Promise<{
  samples: readonly FormDiagnosticSample[];
  browserClosed: boolean;
  contextClosed: boolean;
}>;
```

BrowserType is launch-only Playwright capability, no pre-created fixture; late acquired owned browser/context closes within independent cleanup ceiling. Browser owns exactly one page/context/browser, installs transient recovery session only for exact owned origin, navigates diagnostic page, checks handshake, performs one Resume, samples at15s/final<=60s, freezes before teardown. It never receives runtime mutation capabilities.

Sample extends exact SDK snapshot with `widget_state`, `element_state`, `pay_state` from spec finite domains plus `pay_visible:boolean`, `bucket:'BEFORE_15S'|'AFTER_15S'|'AT_LIMIT'`; all keys descriptor-validated. Root schema classifier returns the exact spec result enums, failure/abort/deadline dominate stage observations. Final runner output `{version:1,result,bucket, ...sample fields, no_confirmation:boolean, browser_closed:boolean,context_closed:boolean,runtime_closed:boolean}` contains no IDs/URLs/secret/private metadata and no accepted/paymentConfirmed. Runtime failure/missing sample yields a fixed safe failed frame, never fabricated stage detail. Up to2 bounded frames; reporter rejects duplicate JSON keys/extra/overflow/truncated/unknown fields without exposing input.

### Task 1: SDK passive observation and persistent confirmation deny

**Consumes:** existing widget Resume/view/driver behavior. **Produces:** internal arm/snapshot/close API above.

- [ ] Write behavioral tests first: foreign/uninstrumented arm fails, fresh armed widget binds once; pending capability/loader/ready and synchronous ready produce literal flags; catch-up pending/resolve/reject/abort distinct; removed/reconfigured/repeated host latches LOST; snapshots frozen/no event payload getters. Direct view and handle confirms after arm, after close and during destroy race reject before Stripe/Fake confirm/backend refresh.
- [ ] Run focused Vitest tests. Expected meaningful FAIL for missing registration/flags/deny before production edits, not syntax/dependency failure. Record output.
- [ ] Implement minimal WeakMap/WeakSet module and instrumentation at existing awaits/handlers. Deny is captured immutably by each view/handle and cannot be cleared. No new async waits or public exports.
- [ ] Run focused tests, full SDK suite and SDK tsc. Expected exit0/no failures; preserve normal existing Fake/Stripe tests. Report exact files/hash/RED/GREEN; ROOT retains commit ownership.

### Task 2: Fresh owned prepare-only runtime and no-confirmation proofs

**Consumes:** existing safe owned bootstrap mechanics and current backend contracts, no SDK dependency. **Produces:** createFormDiagnosticRuntime API above. Demo image build uses build arg `PAYMENT_DEMO_BUILD_MODE=form-diagnostic` (Task3 Dockerfile supplies default ordinary build).

- [ ] Write Node tests first around real new factory with synthetic FS/process/fetch boundaries: missing/mixed flags access0; direct account default-version/mode/redirect/size/deadline mismatch; exactly one Product/Price/Checkout preparation and no campaigns/callbacks/fullstate; initial Attempts0 then1 via sole client-session;2/wrong money/binding/charge/Customer fail; unknown create/late response no replay; owner/deadline/cleanup uncertainty and all full-mode regressions.
- [ ] Run `node --test` exact new/helper/bootstrap/owned files, confirm meaningful RED for absent prepare-only entry/contract. Do not execute installed Docker/Stripe/user env.
- [ ] Implement additive dedicated bootstrap/preflight factories with shared mechanical proof code; retain full-mode gates. Diagnostic reads testkeys privately without writing userenv; direct final backend creation with locally generated private unregistered webhook verifier where backend requires it, no listener proof. No placeholder rm in diagnostic mode.
- [ ] Implement minimal durable provisioner (existing admin cookie+CSRF/owner/pinned paths, account/site+ready provider/returnurl/catalog publish/one anonymous Checkout), original operation ledger before every write, no retries/adoption. Fresh bound private provider read and server before/after proof; one initial client Attempt permitted, no confirm dispatch. Capture immutable source/run/ports/keys; one-use handoff/no-financial-capabilities.
- [ ] Run full affected Node suites and report RED/GREEN/commands/hashes. Expected all pass; no full-mode behavior weakening. ROOT reviews and integrates, no worker git commit.

### Task 3: Dedicated page, one-load driver, strict output and integration

**Consumes:** Tasks1/2 exact APIs. **Produces:** separate runnable opt-in entry; offline tests only in this authorized execution.

- [ ] Write schema tests with literal valid sample: mount_returned/ready_seen/view_completed/widget_completed=true +visible Pay => FORM_READY_BEFORE_15S; pendingready vs catchupPENDING distinguish; opaque error beats snapshot; malformed/proxy/accessor/duplicate/oversize/truncation never leaks. Write browser driver tests asserting one Resume/no input/Pay, early/late/60s samples and late-close ownership. Write parent lifecycle tests against real runtime boundary plus synthetic transport, ensure no use of full acceptance/extras and no fake proof success.
- [ ] Run Node/Vitest focused tests, observe meaningful RED before code; build config tests verify default discovery excludes diagnostic spec and new config has1worker/retries0/trace+screenshots+videooff/preserveOutputnever. Tests must execute/import configuration rather than grep source text.
- [ ] Implement pure finite schema/classifier, controlled parent entry and opaque failure provenance; private runtime hands transient session only to driver. Diagnostic page imports SDK src/index and internal observer in one Vite graph ONLY in `form-diagnostic` build, arms fresh widget before connection/Resume, exposes readonly snapshot/handshake only. No other UI or writer/disarm facade.
- [ ] Implement exact-owned manual browser launch/context close, original15scheckpoint/60s/original deadline, callback settlement before runtimecleanup, uncertainty permanently latched, at most2 frozen safe samples. Separate reporter suppresses raw outputs/errors/attachments and validates framed finite payload. Separate spec/config never enters full tests or normal discovery.
- [ ] Add Vite/Dockerfile build mode wiring (new html copied, buildarg default ordinary), strict no-referrer/unchanged CSP; diagnostic scripts external only. Update approved design status and local guide with optins, safety/interpretation and no execution authority. Do not expose private values/actual run IDs.
- [ ] ROOT verification: full SDK suite; SDK+demo tsc; all `infrastructure/*.test.mjs` synthetic tests plus demo support tests and schema/driver tests; default+diagnostic Vite builds into unique temp dirs, no actual browser/Docker. The three unchanged `gitignore.test.mjs` context-filter tests invoke real Docker buildx builds: explicitly exclude and report these, not as passing synthetic checks. Read-only Compose config uses synthetic fixtures only. Existing ephemeral listen/close unit probes do not reserve actual run ports. No Maven for unchanged backend. Investigate every unexpected failure, do not skip/suppress it.
- [ ] Freeze exact reviewed diff/hash/package, dispatch fresh independent read-only requirements+quality review of this entire diagnostic amendment. Critical/Important fixes require RED->GREEN + related/full green suite; scope rulings recorded. Do not redo historical Tasks1–15/broad branch review.
- [ ] ROOT stage exact public files after secret/diff/hash audit, commit cohesive verified phases as appropriate, feature push/readback under standing permission; main/evidence untouched. Final handoff explicitly states offline-only/no Sandbox/root cause still unproved, separate future run permission needed.

## Plan self-review and execution decision

All spec sections map to Tasks1–3; schema/API names above are shared frozen contracts. Overlap is only explicit module consumption, not file ownership; SDK and runtime may run concurrently under dispatching-parallel-agents after this plan freezes. Task3 integrates both. Preflight rows belong in this plan's own SDD ledger.

Human explicitly confirmed the full design and requested implementation now with tests/review. Preserve that execution request: no redundant design/plan approval question, no inferred runtime authority. ROOT coordinates the same cohesive Stage5A Task16 amendment with two disjoint implementation specialists and one fresh final independent reviewer; exact scopes are enforced. No source publication can authorize an actual Sandbox invocation.
