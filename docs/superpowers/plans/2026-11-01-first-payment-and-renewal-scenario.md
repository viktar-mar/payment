# Owned €20 initial and €20 first renewal scenario Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare a separate bounded future scenario from original €20 server-confirm-v2 cash through exactly one €20 renewal, without running it locally.

**Architecture:** A test-runtime Java wrapper composes the existing initial scenario and existing renewal consumers. A closed Node owner variant shares initial lifecycle and proves both periods before cleanup; explicit native adapters remain opt-in and use a separate runtime export.

**Tech Stack:** Java 25, Maven, stripe-java 34.0.0, PostgreSQL, existing Node.js/TypeScript/Playwright dependencies.

**Spec:** docs/superpowers/specs/2026-11-01-first-payment-and-renewal-scenario-design.md

## Global Constraints

- Pin stripe-java 34.0.0 and API 2026-09-30.endive; EUR2000 initial + EUR2000 first renewal, MONTH/quantity1/trial0/card/automatic/classic, unchanged terms.
- No local Docker/daemon/container, Stripe, Python, installs, application/SQL/Spring/Testcontainers/browser launch, archive or secret reading; cached compilation and root-audited pure/inert tests only. Hosted fresh synthetic CI is allowed.
- No old payments, retained €13/donations or consumed/UNKNOWN/DISPATCHING reuse/retry/reset; fail on the first failure.
- V30–V33, SubscriptionFactService and original OwnedSubscriptionBillingTime byte-identical. Operational UTC unchanged; original initial-only and default Fake behavior preserved.
- Original SERVER_CONFIRM_V2 request/key provenance and original initial evidence remain unchanged; no injectable post-payment hook, generic clock API, third advance or observation retry wrapper.
- Independent task compliance/quality review then broad final review. Root owns Git writes, runtime audit and final source-bound push/CI; implementers do not spawn subagents or run unapproved tests.
- User requested uninterrupted execution and authorized branch push; root resolves routine choices without additional questions. Preserve worktree/evidence; no PR/main merge.

## Review Focus

- A duplicate or concurrent boundary/finalization call must burn authority before any second effect (Task 1 and Task 2 tests).
- Initial proof failure must prevent all renewal work, and renewal failure must retain cleanup without acceptance (Task 2 tests).
- A mode/entrypoint/environment mismatch must fail before bootstrap/CLI/native effects; ordinary Playwright must not discover the new entry (Task 3 tests).
- Four final callbacks must be exactly both proved pairs, with no foreign/duplicate/missing delivery (Task 2 validator and Task 3 shipped-SQL hosted tests).
- The clock sampler must deny after admitted-but-not-READY and retain original F before admission; real initial €20 plus renewal €20 must pass through the new wrapper (Task 1 hosted tests).

---

### Task 1: Java combined scenario, fixed private runtime and export

**Files:**
- Create: backend/src/test/java/dev/genericpayments/acceptance/runtime/OwnedBillingFirstRenewalScenario.java
- Create: backend/src/test/java/dev/genericpayments/acceptance/runtime/OwnedBillingFirstRenewalRuntime.java
- Create: corresponding OwnedBillingFirstRenewalRuntimeTest.java and OwnedBillingFirstRenewalScenarioIntegrationTest.java
- Create: backend/export-billing-first-renewal-runtime.sh and backend/Dockerfile.billing-first-renewal
- Test: infrastructure/billing-first-renewal-runtime.test.mjs
- Modify only if needed for reuse: original runtime/scenario and narrow shared synthetic fixtures, without expanding old authority; .github/workflows/ci.yml packaging check.

**Interfaces:**
- Consumes: existing OwnedBillingFirstPaymentScenario Operations; original StartupResources; existing admission, advance, observation and publication APIs from the spec.
- Produces: OwnedBillingFirstRenewalScenario delegates original initial Operations and adds JsonObject boundary(), JsonObject finalization(), with wire fields/phases exactly as spec. OwnedBillingFirstRenewalRuntime admits the new env and dispatches only the two fixed empty-body routes plus original initial routes. Separate named export and Docker entrypoint: dev.genericpayments.acceptance.runtime.OwnedBillingFirstRenewalRuntime.

- [ ] Step 1: Write admission/handler/single-use tests and static named-export tests; record meaningful RED for absent/rejected new behavior using only audited pure test closure. Write hosted integration tests before implementation; do not run them locally.
- [ ] Step 2: Implement wrapper: delegate original initial; require SETTLED_LOCAL before admission; BOUNDARY→Pending; FINALIZATION→Paid→publication; assert authenticated access at E+3600, unchanged terms and €20. No external/caller-selected effects. Abort once on first failure.
- [ ] Step 3: Implement strict new runtime admission/auth/request gates and explicit new sampler config; reuse original startup ownership/unwind. Add named exporter/Dockerfile and hosted jdeps/production-jar isolation check without launching.
- [ ] Step 4: Test the new wrapper in hosted synthetic composition with real original confirmation and two EUR2000 facts; exact B/T order, two advance POSTs, no intermediate financial change, original lineage, signed callback delivery, and failure/no-third-advance cases. Reuse/refactor narrow fixtures rather than copying the provider router wholesale.
- [ ] Step 5: Run root-audited pure Node export tests and cached Maven test-compile; save evidence. Root freezes source diff/hashes including new files, dispatches independent task review and addresses blocking findings. Root commits the tested/reviewed task.

### Task 2: Closed Node owner, client and final financial proof

**Files:**
- Create: infrastructure/stripe-billing-first-renewal-options.mjs and .d.mts, matching test
- Modify: infrastructure/stripe-billing-first-payment.mjs, -runtime.mjs and .d.mts to share private closed mode implementation; original public shape unchanged
- Create: infrastructure/stripe-billing-first-renewal-evidence.mjs, -proof.sql and matching tests
- Modify: infrastructure/stripe-billing-evidence.mjs to share private callback validation with a fixed RENEWAL entry; existing exported INITIAL entry retains its signature and strictness
- Test: new infrastructure/stripe-billing-first-renewal.test.mjs plus existing initial-only tests
- Modify: original option exclusion gates only to reject new simultaneous mode.

**Interfaces:**
- Consumes: Task 1 fixed routes/responses and original browser/fixture/evidence APIs.
- Produces: validateBillingFirstRenewalOptions, billingFirstRenewalOptIn, snapshotBillingFirstRenewalEnvironment; createOwnedBillingFirstRenewal; createBillingFirstRenewalRuntime with ready/prepare/confirm/observe plus boundary(limits)/finalization(limits)/close; proveBillingFirstRenewalEvidence and validateBillingFirstRenewalEvidence. New options/result/phase/budget exactly as spec. Task 3 supplies createOwnedBillingFirstRenewalBootstrap, preflightOwnedBillingFirstRenewal, createBillingFirstRenewalCliAdapter and bootstrap.withFirstRenewalRuntime.

- [ ] Step 1: Write pure injected-owner/client/validator tests for new strict options, original/new mutual exclusion, success order through initial proof, boundary and finalization, no repeated stage, mismatched identity/time/amount, failure cleanup/uncertainty, and forged diagnostics. Run audited tests RED without native dependencies.
- [ ] Step 2: Implement strict options and closed shared owner/client modes. New mode selects only the named Task 3 adapters; no caller hook/target. Preserve old initial result/protocol and safe diagnostics; combined acceptance only after both proofs and confirmed cleanup.
- [ ] Step 3: Add read-only final SQL and validator proving exactly EUR2000+EUR2000, persisted original/renewal lineage and B/T/read/publication, local events/current access and exactly four signed delivered callbacks. Validate pairs with existing machinery and exact union. Keep credentials scoped and output closed.
- [ ] Step 4: Execute audited new and related Node regressions; no real database/network/runtime. Root reviews frozen diff and source/tests, independent task reviewer checks spec/quality, fixes via original author; root commits.

### Task 3: Explicit future native transport, browser entry and end-to-end hosted gates

**Files:**
- Modify: infrastructure/stripe-sandbox-bootstrap.mjs, -owned.mjs, -cli.mjs, -ownership.mjs and their focused synthetic tests
- Create: examples/demo-site/tests/stripe-billing-first-renewal.spec.ts and playwright.stripe-billing-first-renewal.config.ts (or equivalent established filenames), matching synthetic entry tests/declarations
- Modify: hosted combined Java integration test for execution of shipped SQL and final proof; CI packaging/tests selection as needed
- Modify: docs/stage-5b-acceptance.md and docs/local-development.md

**Interfaces:**
- Consumes: Tasks 1–2 exact env, entrypoint, options, fixed routes, read-only proof and owner/browser contract.
- Produces: separate preflight/bootstrap/CLI factories named in Task 2, private withFirstRenewalRuntime accessor; source-bound fresh resources and exact runtime image/container proof; dedicated opt-in browser entry reusing runFirstPaymentBrowser. No generic graph/advance CLI capability.

- [ ] Step 1: Write pure injected/bootstrap/CLI/ownership/Playwright-discovery static tests demonstrating incorrect mode/env/entrypoint rejection before side effects; record RED. New entry is opt-in, no import effects and excluded from normal Playwright.
- [ ] Step 2: Add closed variants sharing existing lifecycle and startup/stop checks; choose separate Dockerfile, env prefix, exact entrypoint and private capability. All existing initial-only/defaultFake tests stay binding. Reuse existing fresh EUR20 fixture and browser component.
- [ ] Step 3: Execute shipped SQL against the new hosted genuine combined €20+€20 fixture and validate exact signed callback union. Verify current and original exported runtime closures with production libs only, without launching them. Ensure focused hosted selection includes new combined tests.
- [ ] Step 4: Document OFFLINE_PREPARED, remaining native acceptance/storage prerequisites, exact future explicit invocation, no financial authority now. Update matrix without claiming full5B acceptance.
- [ ] Step 5: Root runs audited local Node regressions/typecheck/cached compile, freezes/reviews task with independent reviewer, and commits after fixes. Broad final review covers complete base→final diff, preserved invariants and deferred minor findings. One final combined fix wave and scoped rereview if needed. Root pushes branch and waits for all Offline CI jobs (and focused job when triggered) on exact final source; repair/review real failures without weakening checks. Preserve external final receipt and stop only when requested work is complete.
