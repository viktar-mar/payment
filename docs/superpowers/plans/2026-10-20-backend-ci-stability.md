# Backend CI Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. The user explicitly approved fixing both failures with subagents, deterministic regressions and independent reviews, then pushing the branch and repeating GitHub CI. Root commits only after independent review; workers never commit or spawn agents.

**Goal:** Correct the two backend failures from GitHub run39405220751 and verify the pushed result with the existing secret-free workflow.

**Architecture:** Keep Java hexagonal boundaries and real PostgreSQL integration tests. Reproduce the subscription precision defect at the operation persistence boundary and use the existing microsecond timestamp policy where the evidence requires it. Isolate checkout HTTP test clients from shared in-memory rate-limit state without weakening runtime throttling.

**Tech Stack:** Java25, Maven Wrapper3.9.11, Spring Boot, PostgreSQL18 Testcontainers, JUnit, existing GitHub Actions.

**Spec:** Approved chat request on2026-10-20 and observed GitHub failure https://github.com/viktar-mar/payment/actions/runs/39405220751 at commit970fd317b6f61f75ad96bd3125381fe4d7b662ab. No new product behavior/design beyond these bounded correctness fixes.

## Global Constraints

- Reuse linked worktree `/Users/viktar.marozau/.codex/worktrees/stripe-sandbox-5a-recovery/payment`, branch `codex/stripe-sandbox-5a`, base970fd317b6f61f75ad96bd3125381fe4d7b662ab. Preserve main and unrelated changes.
- No Stripe CLI/OAuth, Sandbox/live calls, card entry, confirmation, refunds or actual application Compose startup. No private env/issuer reads or changes; no machine-wide tool installation, Docker prune, data removal or evidence cleanup.
- Tests use disposable tracked-source archives with newly generated synthetic issuer material and explicit empty Maven settings; no user's ignored files. Docker/Testcontainers may own disposable PostgreSQL/Mailpit test resources only, as required by the existing backend suite. Preserve evidence and use explicit local Docker socket with isolated Docker config.
- Preserve domain invariants and runtime rate limiting. No sleeps, retries, disabled/skipped failing tests, increased limits or broad clock/domain validation weakening to make CI green. Preserve real-UTC signature validation and future business-clock semantics.
- Keep API/schema/dependency versions and workflow coverage unchanged. Do not expand into unrelated historical Stripe work or the nonblocking pnpm-action runtime warning.
- One implementer at a time, fresh independent spec/quality reviewer after each task; root owns plan, ledger, review packages, commits and push. No worker subagents or commits. Review before local commit; retain ignored reports.
- Run deterministic RED/GREEN and related backend regression selection per task, then one full local Maven verify checkpoint after both fixes. Push only reviewed committed code with normal fast-forward semantics; no force push, merge, PR or repository-settings changes. Await the exact new GitHub run's terminal result; no Sandbox opt-ins.

## Review Focus

1. Sub-microsecond instants rounding upward in PostgreSQL must not make an unchanged business clock appear to move backward.
2. A meaningful actual clock rollback must still be rejected; do not clamp times or remove monotonic invariants.
3. Test precision alignment must not mask a real service/persistence defect; exercise the round trip with deterministic non-aligned fractions.
4. Exhausted shared/default-IP quota must not turn an unrelated missing-idempotency validation test into429; the regression must not contaminate other test contexts.
5. Same-client throttling, spoofed forwarded headers and business-clock/real-UTC signature separation must retain their existing assertions.

---

### Task 1: Subscription operation timestamp precision

**Files:**
- Modify: `backend/src/main/java/dev/genericpayments/platform/subscriptions/application/service/SubscriptionCommandTransaction.java` (minimal timestamp-boundary correction supported by reproduction)
- Modify: `backend/src/test/java/dev/genericpayments/platform/subscriptions/application/service/SubscriptionRuntimeClockIntegrationTest.java`
- Optional new focused integration test in the same package if it keeps clock/precision cases clearer; root approval required before widening production files.

**Interfaces:**
- Consumes existing real `SubscriptionOperationPersistenceAdapter.claim/save`, `SubscriptionFactFixture.start/op`, fixed TestClock, existing payment-service microsecond normalization precedent.
- Produces successful create/read/update with PostgreSQL-microsecond-compatible operation times under a deterministically non-aligned clock, preserving all domain checks and callback UTC behavior.

- [x] **Step1: Confirm cause and RED.** Real PostgreSQL regression demonstrated both round-up and round-down raw nanosecond boundaries; original production produced the monotonic-time error and incorrect later update precision.
- [x] **Step2: Minimal fix.** Two timestamp origins normalize to MICROS. Separate raw-nanosecond create/read/update regressions and real rollback rejection remain; original future signature/callback fixture is microsecond aligned without weakening assertions.
- [x] **Step3: GREEN and related regression.** Complete changed class and related subscription/domain/transport selection passed136tests,0failures/errors/skips. Full checkpoint remains root-owned.
- [x] **Step4: Independent gate.** Fresh reviewer approved spec and quality; source unchanged from reviewed tree. Local commit a767bb45bdab4e43f274822268b5d6e8fcf9cb33, no push yet.

### Task 2: Checkout HTTP test client isolation

**Files:**
- Modify: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/in/web/PublicCheckoutApiIntegrationTest.java`
- Optional modify: `backend/src/test/java/dev/genericpayments/platform/support/AdminApiClient.java` only if needed for a reusable test-only default request-address capability preserving existing callers.
- Optional new focused regression class in the payments web-test package for isolated contaminated-quota reproduction.
- No production limiter/filter/property changes unless the reproduction proves a runtime defect and root approves the additional scope.

**Interfaces:**
- Consumes real `PublicRateLimitFilter`, `RateLimitPort` and singleton `InMemoryTokenBucketRateLimitAdapter`; shared fixed Site fixture; MockMvc default remote address versus existing per-test IP.
- Produces deterministic HTTP validation under independently scoped clients while existing same-client rate-limit tests continue to return429 and correct Retry-After/CORS/no-store.

- [x] **Step1: Confirm cause and RED.** Deliberately exhausted default-client quota reproduced the original missing-idempotency400→429 failure. Unique imported test configuration and AFTER_CLASS disposal contain poisoned state.
- [x] **Step2: Isolate test clients.** Optional test-only client address defaults cover all raw requests; existing constructor and explicit request overrides remain intact. Runtime limiter/filter/properties unchanged.
- [x] **Step3: GREEN and related regression.** All four required classes passed20tests,0failures/errors/skips, preserving same-client limits, forwarding-header protection, validation and headers.
- [x] **Step4: Independent gate.** Fresh task reviewer approved spec and quality at staged treef8f53f5215563f771e3a9722a4809367e0a2cb5d. Final combined review and root fullMaven/push/CI gates remain pending below.

## Combined checkpoint and publication (root-owned)

- [x] Run one full local `./mvnw -B -ntp verify` against a disposable tracked-source archive with fresh synthetic issuer. Source treef8f53f5215563f771e3a9722a4809367e0a2cb5d:2599tests,0failures/errors,1existing opt-in OpenAPI generator skip; Cucumber79/79. BUILD SUCCESS,15:53,2026-10-20. Unchanged frontend/infrastructure local suites were not duplicated.
- [x] Final independent review approved this two-fix delta from970fd31, not the historical branch;0Critical/Important findings. Reports, declined behaviors and retained follow-ups remain recorded.
- [ ] Verify reviewed source unchanged, worktree clean, remote matchesviktar-mar/payment; commit exact approved changes and push `codex/stripe-sandbox-5a` without force.
- [ ] Await the exact new SHA's `Offline CI` push run and inspect terminal jobs/failure summaries. Record final URL and results. No automatic real Sandbox execution.

This tracked checklist is the pre-publication verification snapshot. The exact pushed commit and subsequent remote CI outcome are recorded in the retained execution ledger and final handoff, avoiding another documentation-only push solely to rewrite this snapshot.

## Plan self-review

Each task has an independent rejectable result and review gate. Shared interfaces are existing clock/Testcontainers fixtures only; no overlapping production edits. Five Review Focus cases map to explicit regression requirements. User already chose and authorized execution, so no new execution-method or product-design decision is needed.

## Retained follow-ups

Final independent review approved the five-file two-fix delta with no Critical/Important findings. Existing framework/JDK/compiler warnings remain a separate compatibility-maintenance task. The Fake subscription simulator can round a frozen raw-nanosecond clock through PostgreSQL above the business clock when deriving a period; arbitrary nanosecond simulator period/recovery support is not established by these operation regressions. A future clock-contract fix should retain the domain guard against confirming a period before it starts. No Sandbox acceptance is claimed.
