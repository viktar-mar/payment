# Manual Stripe Readiness Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing manual form-only diagnostic with finite evidence explaining the boundary between iframe creation and Stripe readiness, without treating that evidence as a fix or authorizing another Sandbox attempt.

**Architecture:** Keep the shared `FormDiagnosticSample` v1 and automated diagnostic protocol unchanged. Add an internal SDK loader-start snapshot and a manual-only v2 observation envelope. The ordinary-browser collector reports layout categories, iframe load observation and top-document CSP bitmasks; the gateway validates and preserves that evidence atomically with its sample.

**Tech Stack:** Existing TypeScript SDK, Vitest, Node test runner, Vite and jsdom; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-19-stripe-manual-form-design.md`, as extended by the user's approval of the following scope on 2026-10-19: offline implementation of container/iframe dimensions, iframe load, Stripe `loaderstart`, and CSP observations, with tests and independent review. This plan defines the exact new finite contract for approval before implementation.

## Global Constraints

- Work only in the existing `codex/stripe-sandbox-5a` recovery worktree, based on `6dacfe0aa518a627c5a296285703f17ae8e89b7d`.
- No Sandbox, Stripe, Docker, browser or external-network launch; no private environment/key/runtime reads. Synthetic offline tests and local builds only.
- Keep permanent confirmation denial, one manual activation/session exchange, at most one pending and one terminal observation, the existing 15-second checkpoint, original 60-second window, and owned cleanup unchanged.
- Do not weaken CSP, add retries, increase deadlines, synthesize readiness, or interpret iframe presence/load or `loaderstart` as `ready`.
- No raw URLs, paths, headers, keys, identifiers, timestamps, pixel coordinates, provider errors, console messages, resource lists, iframe document access or iframe contents in the new evidence. Do not inspect those fields to classify CSP.
- New observations and final manual reports use version `2`; nested `sample` remains version `1`. Reject old/manual mixed formats rather than silently infer missing evidence. Shared automated formats remain unchanged.
- Exact own enumerable scalar data only; reject unknown/symbol/accessor keys without executing getters. Observation and final report wire size remain at most 4096 UTF-8 bytes. Reject duplicate JSON keys, including escaped spellings, at every nesting level.
- Browser evidence remains `BROWSER_REPORTED`, not independent proof of browser rendering, Stripe success, or payment state. Missing observations remain unknown, never inferred success.
- Review each task independently; finish with a review of the complete incremental change, not the unrelated historical branch. Preserve existing user work and retained runtime artifacts. No push or merge in this increment.

## Review Focus

- Synchronous `loaderstart` during mount must be observed; throwing payload getters must never be read.
- Close, abort, deadline and lost/replaced widget generations must stop mutation/listener activity without removing confirmation denial.
- Multiple or replaced iframes must not inherit a previous frame's load evidence or be represented as one known loaded form.
- CSP report-only versus enforced violations must remain distinct; synthetic events and events outside the active top-document window are ignored.
- A historical pending report must retain its matching evidence at cutoff; malformed concurrent reports must not replace an accepted ready sample or promote success.

---

### Task 1: Internal Stripe loader-start observation

**Files:**
- Modify: `frontend/sdk/src/diagnostics/form-load-observer.ts`
- Modify: `frontend/sdk/src/providers/stripe-driver.ts`
- Test: `frontend/sdk/src/diagnostics/form-load-observer.test.ts`
- Test: `frontend/sdk/src/providers/stripe-driver.test.ts`

**Interfaces:**
- Preserve `FormLoadSnapshot`, `snapshot()` and all existing serialized keys exactly.
- Extend the handle returned by `armFormLoadDiagnostic(widget)` with `readinessSnapshot(): Readonly<{loaderstart_seen:boolean}>`.
- Add internal `markFormLoadLoaderStart(binding: FormLoadBinding | undefined): void`. Update only the current active bound generation, while its operation is pending; never retain an event payload.
- Attach the Stripe `loaderstart` listener before `mount`, with the same lifecycle discipline as readiness listeners. Detach on ready, failure, abort and destroy.

- [ ] Write failing observer tests: snapshot defaults false; a current binding records true; unbound/lost/closed/aborted generations do not mutate; legacy `snapshot()` exact keys are unchanged; confirmation remains denied after close.
- [ ] Write failing driver tests: synchronous and delayed `loaderstart` become visible, but mount still waits for actual `ready`; no-event remains false; a payload with throwing getters is not inspected; listener removal is verified on ready, loaderror and abort.
- [ ] Run the two affected Vitest files with the installed local runtime; confirm failures are missing behavior, not setup/import failures.
- [ ] Implement the minimal internal hook and listener lifecycle, without modifying normal checkout behavior or SDK public exports.
- [ ] Run the affected files plus `src/widget/checkout-provider-view.test.ts`; run SDK typecheck. Require zero failures and record red/green output.
- [ ] Self-review, create a scoped commit, then obtain independent spec-compliance and quality review before Task 2.

### Task 2: Manual-only finite evidence and end-to-end offline regression

**Files:**
- Create: `infrastructure/stripe-manual-form-evidence.mjs` and `.d.mts`, plus `infrastructure/stripe-manual-form-evidence.test.mjs`
- Create: `examples/demo-site/src/manual-form-readiness.ts`
- Modify: `examples/demo-site/src/form-load-manual.ts`
- Modify: `infrastructure/stripe-manual-form-gateway.mjs` and `.d.mts`
- Modify: `infrastructure/stripe-manual-form-schema.mjs` and `.d.mts`
- Modify: `infrastructure/stripe-manual-form.mjs` and `.d.mts` only as required to carry the new evidence, without changing orchestration authority.
- Modify tests: `infrastructure/stripe-manual-form-gateway.test.mjs`, `infrastructure/stripe-manual-form-schema.test.mjs`, `infrastructure/stripe-manual-form.test.mjs`, and any directly affected runtime fixture.
- Modify tests: `examples/demo-site/tests/support/form-load-manual.test.ts` and `examples/demo-site/tests/support/form-load-manual-build.test.ts`
- Modify: `examples/demo-site/Dockerfile` and `docs/local-development.md`

**Interfaces:**
- `ManualFormReadinessEvidence` has exactly these fields:
  - `loaderstart_seen: boolean`
  - `host_box` and `frame_box`: `ABSENT | ZERO_AREA | POSITIVE_AREA | MULTIPLE | UNAVAILABLE`; `MULTIPLE` is permitted only for `frame_box`.
  - `host_visibility` and `frame_visibility`: `ABSENT | VISIBLE | HIDDEN | UNAVAILABLE`; multiple frames produce `UNAVAILABLE` frame visibility.
  - `frame_load: NO_FRAME | NOT_SEEN | SEEN | UNAVAILABLE`; applies only to the currently identified single iframe. Unknown/missed load is not a failure assertion.
  - `csp_observation: OBSERVED | UNAVAILABLE`, `csp_enforced_mask` and `csp_report_only_mask`: integers `0..15`; unavailable implies zero masks.
- CSP masks match the existing diagnostic convention: script directives `1`, frame/child directives `2`, connect directive `4`, other directives `8`. Read only `isTrusted`, `effectiveDirective`, and `disposition`; no `blockedURI`, `sourceFile`, `sample` or error payload access.
- Export pure validators `validateManualFormEvidence(value)` and `validateManualFormObservation(value)`, plus `parseManualFormObservation(wire)` and `unavailableManualFormEvidence()`.
- Observation envelope: exactly `{version:2,sample:FormDiagnosticSample,evidence:ManualFormReadinessEvidence}`. No partial or legacy acceptance.
- Final manual report: bump existing version to `2`, preserve existing keys and add `evidence: ManualFormReadinessEvidence | null`; evidence is null exactly when sample is null. Readiness classification still uses only the unchanged shared sample classifier.
- Collector: `createManualFormReadinessObserver(options)` consumes `getWidget: () => HTMLElement | undefined`, `isActive: () => boolean`, and `readinessSnapshot: () => Readonly<{loaderstart_seen:boolean}> | undefined`; produces `snapshot(): Readonly<ManualFormReadinessEvidence>` and idempotent `close(): void`. Create it at the single Start before the async session handoff; getter closures allow the owned widget to arrive later. Attach frame capture to its shadow root before connecting/resuming it. Stop on ready/close/abort/deadline with owned page lifecycle cleanup.

- [ ] Write failing schema/gateway tests for exact v2 round-trip, old-version rejection, nested duplicate/accessor/unknown keys, oversize and invalid enum/mask combinations, null coherence, and pending/terminal evidence retention under cutoff and malformed-report races.
- [ ] Write failing collector/page tests using the actual manual page and real SDK with synthetic Stripe: positive and zero-area boxes; hidden ancestors across the shadow host; absent/multiple/replaced iframes; current-frame load capture; loaderstart without ready; enforced/report-only CSP; ignored untrusted/late events; throwing forbidden getters; observer cleanup on ready/close/abort/deadline.
- [ ] Verify those tests fail for the new missing contract/behavior before implementation. Keep assertions on serialized production output and lifecycle behavior, not on a duplicate fake implementation.
- [ ] Implement the pure finite contract and owned browser collector. Category conversion discards numeric dimensions immediately. `VISIBLE` means only the checked layout/style conditions, not that card fields rendered. Do not access frame `src`, `contentWindow`, `contentDocument`, resource timing, performance entries, or cross-origin frame internals.
- [ ] Wire the envelope through page, gateway and final report atomically. Preserve old readiness classification and existing one-shot/deadline/cleanup rules. Unavailable diagnostics cannot upgrade a result or trigger a new attempt.
- [ ] Add only the explicit new public `.mjs`/`.d.mts` files to the Docker COPY allowlist. Extend the existing isolated actual-COPY build fixture; never COPY the infrastructure directory or private inputs.
- [ ] Run the SDK full offline Vitest suite and SDK typecheck; run all `infrastructure/stripe-manual-form*.test.mjs` plus the new evidence test using the installed local Node test runner. Run the actual-source manual page test and Docker-packaging build regression serially. Require zero failures; report any warning/failure by name. No Docker or real-browser suite.
- [ ] Update the manual-mode guide with version, field meanings, unknown/cross-origin limitations and an explicit statement that this change does not fix or prove Stripe rendering.
- [ ] Self-review and commit the scoped change. Obtain independent task review and then whole-increment review against the base recorded above. Address blocking findings with covering tests and scoped re-review.

## Handoff

- [ ] Report exact tests/counts and review outcome, commit(s), and diagnostic limitations.
- [ ] Do not start a new manual/Sandbox attempt; that remains a separate user decision.
