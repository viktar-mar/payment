# Offline regression timeout repair

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute continuously; no intermediate human approval gates.

**Goal:** Explain and repair the previously failing native Node regression groups without weakening their assertions, isolation or limits.

**Architecture:** Investigate the existing synthetic tests before changing code. Preserve operational authorities and real filesystem durability. Remove only measured redundant test setup/work; if a timing budget is assigned at the wrong level, document the evidence before proposing a structural correction.

**Tech Stack:** Existing Node 24, node:test, TypeScript type erasure, Vite, JSDOM, Playwright discovery; installed dependencies only.

**Spec:** Human request of 2026-10-27: diagnose old timeouts with subagents and independent review, achieve green regression, do not weaken checks, do not run Docker or Sandbox. Earlier remaining-mode implementation is preserved.

## Global Constraints

- Work only in `/private/tmp/payment-5a-offline.Dm0qkj`, branch `codex/stripe-5a-offline-acceptance`; preserve every pre-existing change and untracked recovery object.
- Do not run or access Docker, Stripe/Sandbox, original archives, retained private runtime or old payments; use only newly created synthetic fixtures.
- Do not install dependencies or execute Python. No commit, push, merge or Git index change in this task.
- Do not raise existing 30000 ms infrastructure, 60000 ms support, 10000 ms worker or operational deadline limits. Do not skip cases, drop assertions, bypass guards, suppress stderr/failures, remove mutation checks, replace real durability with no-op sync, or reuse mutable fixture authority between independent cases.
- Serialize heavy tests/builds. Read-only investigation/review may overlap. Every implementation change requires focused failure evidence, covering tests and independent review.
- Final verification must retain original test selection/coverage for permitted offline suites. Explicitly inventory Docker-dependent tests not executed under the human restriction; never claim full repository CI green from a subset.
- Evidence stays in this plan's ignored SDD directory. Do not delete prior evidence or this task's uncommitted artifacts.

## Review Focus

- Missing/false financial proof must continue fencing both dispatch and remainder; all current variants remain exercised.
- Fsync/rename, descriptor cleanup and identity checks remain real and tested; optimizations must not cache mutable authority across operations.
- Browser scenarios preserve isolated DOM/storage/clock state and mutation detection, even if setup becomes cheaper.
- Worker crash, message/exit race and timeout must reject deterministically and dispose the worker.
- Filesystem-only Docker COPY fixture must still build/typecheck the actual staged tree and every mode; no actual Docker access.

### Task 1: Infrastructure diagnosis and minimal repair

**Files:** `infrastructure/stripe-manual-refund-state.test.mjs`, `infrastructure/stripe-manual-refund-remainder-state.test.mjs`, their named `infrastructure/fixtures/*` dependencies and state modules only if measured evidence identifies a production defect. No speculative rewrite.

**Interfaces:** Existing `fixture(t)`, `openManualRefundState(...)`, and remainder state authority are unchanged unless a concrete bug requires a reviewed compatibility-preserving repair.

- [ ] Diagnose saved failures and one serialized focused reproduction. Identify exact blocking/expensive calls and distinguish cumulative group budget from a single hung operation.
- Diagnosis decision: the three failed tables contain 9 omitted-proof, 7 false/mismatched-proof and 8 remainder cases. Register those 24 independent cases as individually named top-level tests, keeping each family prefix and every original body/assertion/fixture. Each case receives the unchanged native test timeout, not a shared cumulative parent timeout. Leave other tables and production code untouched.
- [ ] Record a falsifiable root-cause hypothesis and focused RED evidence. If no defect reproduces, report that truthfully and preserve the unchanged source.
- [ ] Implement the smallest measured repair with regression coverage, preserving every original case and authority invariant. If the above constraints leave no safe repair, return NEEDS_CONTEXT to the controller rather than guessing.
- [ ] Run both full related state suites with the original infrastructure budget; record duration/counts and any warnings.
- [ ] Self-review and report changed files, RED/GREEN evidence and concerns. Do not commit. Independent task reviewer follows.

### Task 2: Support worker/build diagnosis and minimal repair

**Files:** `examples/demo-site/tests/support/form-load-manual.test.ts`, `manual-payment.test.ts`, `form-load-manual-build.test.ts`; narrowly named fixture helpers only when necessary. Do not change browser/payment production behavior to repair test overhead.

**Interfaces:** Preserve `exercise(entry, scenario, automatic?, instrumented?)`, real SDK compilation, scenario names/assertions and downstream imports. A private worker/helper can be extracted only with complete per-case isolation and reviewed import/discovery/typecheck behavior.

- [ ] Diagnose old support failures, setup/import/worker/build lifecycle and existing working neighboring tests. Use one serialized reproduction/profile, not parallel full runs.
- Diagnosis decision: move each static Vite import in the two worker-owning test files into its main-thread `bundle()` function so workers never load unused bundler dependencies; keep a fresh worker/DOM per scenario. Run staged-demo noEmit once immediately after staged-SDK compilation; all mode-specific builds and assertions remain unchanged. Measure before/after under original limits.
- Evidence-driven amendment: the minimal import/build correction alone still exhausted the form parent at 60000 ms. The two aggregate scenario containers must use native `describe(..., {concurrency: 1}, ...)` with `before` for fresh directory/control bundle setup, one native `test` per existing scenario/mutation, and `after` for directory cleanup even after partial setup failure. Share only read-only compiled bundle URLs, never worker/DOM/authority. Preserve `exercise` exports and every original case body/assertion. Mutation graph construction stays inside its individual case. Native tests/hooks keep the CLI60000 ms bound and workers keep10000 ms. Do not add an explicit infinite/raised timeout or alter the overall CI job bound.
- [ ] Record exact expensive work and focused RED evidence before minimal change. Preserve original worker and test budgets.
- [ ] Implement the smallest correction and regression tests for any changed worker/helper behavior; retain every existing scenario and mutation assertion.
- [ ] Run affected full suites and their import/build consumers under the original support budget; include typecheck and Playwright discovery if graph paths change.
- [ ] Self-review and report changed files, RED/GREEN evidence and concerns. Do not commit. Independent task reviewer follows.

### Task 3: Integration verification and final independent review

**Files:** Evidence only unless Task 1/2 review finds a scoped integration bug; no new diagnostic framework.

**Interfaces:** The exact source from Tasks 1 and 2 plus all pre-existing remaining-mode edits; native Node and existing package commands.

- [ ] Inventory all top-level infrastructure tests and native support suites, explicitly classify actual Docker-dependent files separately from filesystem-only fixtures.
- [ ] Run permitted complete native regression serially at unchanged limits with a clean environment. Do not hide failed runs or count omitted Docker tests as passing.
- [ ] Run relevant SDK/demo checks/builds and default/offline/dedicated Playwright discovery or offline browser tests when installed tools permit without Docker/network.
- [ ] If a failure appears, diagnose it and route a bounded scoped fix/review before re-verifying; never increase limits to make the report green.
- [ ] Package this task's exact delta, coverage inventory and final evidence for a fresh independent whole-change review. Report achieved green scope, remaining restrictions and no runtime activity.
