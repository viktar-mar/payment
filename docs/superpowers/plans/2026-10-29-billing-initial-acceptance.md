# Initial Billing Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute continuously with task-scoped and final independent review.

**Goal:** Prepare an executable, explicitly opt-in initial Billing payment case without running Docker or Sandbox locally.

**Architecture:** Reuse the existing owned bootstrap/CLI internals through closed Billing factories. A dedicated browser entry provisions fresh recurring fixtures and uses the actual Customer/SDK v2 flow; scoped server/provider/callback evidence determines the result.

**Tech Stack:** Existing Node.js ESM/TypeScript, Playwright, PostgreSQL client and Docker Compose configuration; no added dependencies.

**Spec:** [Initial Billing acceptance](../specs/2026-10-29-billing-initial-acceptance.md).

## Global Constraints

- One fresh EUR20 MONTH no-trial initial success case, one CREATE/one explicit Pay, SERVER_CONFIRM_V2; no retry/clock/refund/donation/old-object path.
- No local Docker, PostgreSQL/SQL, Testcontainers/full Java application, Stripe/Sandbox, Python, installs or private key/archive/recovery reads. Only audited cached Node unit/contract tests, typecheck/build; hosted synthetic CI authorized.
- Preserve ordinary Fake/5A, historical records and the three existing untracked paths. No new dependencies, diagnostic framework, public provider receipt API or generic command/plugin surface.
- Inert imports/factories, explicit Billing-only runtime opt-in, exact owned source/objects, finite deadlines, first-failure stop and exact-ID cleanup retaining volumes.
- Native Billing NOT_RUN; full5B NOT_ACCEPTED. Reports never contain secrets/raw responses. Commit/push only after independent review; no merge/force push.

## Review Focus

- Billing enablement accidentally leaks into default 5A: Task 1 reciprocal opt-in and generated Compose tests.
- CLI forwards incomplete events or returns unrelated graph facts: Task 1 fixed-event and exact graph/cross-account rejection tests.
- Native factory import or default Playwright discovery dispatches I/O: Task 2 inert dependency sentinels and discovery tests.
- Lost ACK or browser success creates a second financial attempt or false acceptance: Task 2 budget/state and incomplete-receipt tests.
- Cleanup/timeout/raw exception hides the first failure or leaks credentials: Task 2 bounded lifecycle and secret-sentinel tests.

### Task 1: Closed Billing bootstrap and provider observation capabilities

**Files:** Modify `infrastructure/stripe-sandbox-bootstrap.mjs`, `stripe-sandbox-cli.mjs`, `stripe-sandbox-owned.mjs` and focused existing tests as needed. Create `infrastructure/stripe-billing-acceptance-options.mjs` and `.test.mjs`; a Billing Compose overlay if necessary. Do not modify backend behavior.

**Interfaces:** Produce pure `validateBillingAcceptanceOptions(options)` returning an immutable initial-success configuration, and inert `createOwnedBillingBootstrap(options, dependencies)` / `createBillingCliAdapter(options, dependencies)`. Their lifecycle methods reuse the existing bootstrap/CLI contracts. Billing CLI additionally offers exact owned graph observation, not arbitrary commands; document its input/output for Task 2 in the report. The options snapshot includes fresh run/source, approved Account/API, six unique loopback ports, fixed initial-success case and fixed one-create/one-confirm budget. Public flag `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` is exclusive of existing 5A modes.

- [ ] Write Docker-free failing tests for absent/mixed/accessor/inherited opt-in before dependency access; immutable valid configuration and exact fixed budget; fresh distinct grant key in owned backend only; fixed supported Billing listener events and bounded original graph validation.
- [ ] Run exact new/focused Node tests to establish meaningful RED. No real child process/network/filesystem fixture execution.
- [ ] Add closed factories inside existing implementations, preserving default behavior and exact ownership/cleanup. Do not duplicate bootstrap/CLI internals or permit arbitrary event lists/commands. Provider reader validates exact owned Customer/Subscription/Price/Invoice/allocation/PI/Charge facts with bounded pagination, no writes/search.
- [ ] Run affected Node regression tests; save command/output, source hashes, diff and author report. Independent task review; repair findings then root commits.

### Task 2: Initial Billing owner, fixture, browser and evidence

**Files:** Create `infrastructure/stripe-billing-acceptance.mjs`, `.test.mjs`, optional `.d.mts`; `examples/demo-site/playwright.stripe-billing.config.ts`, `tests/stripe-billing.spec.ts`, `tests/support/stripe-billing-acceptance.ts` and `.test.ts`. Add focused helper files only when they separate provisioning/proof from lifecycle. Modify default Playwright exclusions and a small optional-second-manager demo change if needed. Update `docs/stage-5b-acceptance.md`, `docs/local-development.md`.

**Interfaces:** Consume Task 1 factories. Produce inert `createOwnedBillingAcceptance(options, dependencies)` with explicit single-use run/close lifecycle and sanitized result `scope: INITIAL_NON_CLOCK`, `initialAccepted`; it must not imply full5B acceptance. Concrete browser support wires all native adapters; no throwing placeholder or operator plugin. Runtime setup uses existing protected OAuth/device handoff, bootstrap and signed receiver. Exact APIs and graph rows follow the two reconnaissance reports under `.superpowers/recon/2026-10-29-billing-initial-acceptance/`.

- [ ] Write failing injected tests for lifecycle ordering, no calls at import/construction, one dispatch per action, exact scope, unsupported/mixed modes, lost ACK/UNKNOWN, abort/deadline/concurrent invocation, finite cleanup, required cash/journal/signed evidence and secret-safe results.
- [ ] Run exact safe Node tests for RED. Implement fresh Account/Site/policy/OTP/recurring Product+Price+callback fixture via existing APIs; real demo uses Customer bearer session and v2. Never reuse the full Fake/5A fixture. If needed make the second demo Product optional rather than duplicate one Product across managers.
- [ ] Implement exact owned read-only SQL evidence and closed provider reads using fresh local IDs; verify real canonical signed callbacks. Do not run them locally. Wire genuine browser readiness, explicit test-card Pay, GET-only settlement and first-failure stop; no full-matrix/clock/retry expansion.
- [ ] Add dedicated worker1/retries0/artifacts-off config and explicit default exclusions; test discovery/source contracts. Native case is opt-in and NOT_RUN.
- [ ] Run cached focused Node/unit tests, typecheck/build and related regressions. Update acceptance with completed `152ea79`/CI `40855668800` v2 evidence and the honestly prepared initial case, retaining historical failed gates and remaining native matrix.
- [ ] Save author report/hash/diff; independent task and final review. Root commits, pushes and checks exact-head hosted CI. Repair only observed failures, keep local runtime restrictions; do not initiate native acceptance.
