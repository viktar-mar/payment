# PROVISION phase and stop evidence implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax for tracking.

**Goal:** Improve offline-reproducible preparation diagnostics and direct runtime-stop evidence without another runtime attempt.

**Architecture:** Keep existing module-owned diagnostic branding and the controller's cached stop promise. Add only five preparation markers and reconcile only directly observed runtime-stop fulfillment before freezing the unchanged report.

**Tech Stack:** Existing Node.js ESM and node:test, injected synthetic IO, no dependencies.

**Spec:** docs/superpowers/specs/2026-10-26-provision-phase-and-stop-evidence-design.md

## Global Constraints

Offline only; no Docker commands, Sandbox/Stripe/external network, installation, Python, keys/config, old payment/refund/archive or raw runtime-log reads. Use injected artificial fixtures; the existing offline suite may use its own ephemeral synthetic loopback HTTP fixtures, never existing application services.
Preserve all existing security/financial predicates, report version/shape, once-only operations, 45-minute active authority, original plus two-minute cleanup ceiling, 30-second per-close slice, 10-second child, 45-second/30-probe financial proof and five-minute page.
Diagnostic remains exactly {tag,stage}, at most 256 UTF-8 bytes, module-owned finite labels only; no dependency-error inspection or raw content.
Historical reports stay immutable; UNKNOWN does not establish a historical root cause, and Stage5A remains NOT_ACCEPTED. No push or runtime launch.

## Review Focus

- Errors at each newly marked boundary fail closed without subsequent writes: Task 1.
- Hostile/unbranded errors never leak properties/content or acquire a named stage: Task 1.
- Ordinary and selected 3DS use the same finite markers, unchanged single diagnostic after cleanup: Task 1.
- Stop false/rejection, unresolved or exact/after-ceiling completion never becomes positive proof: Task 2.
- Successful stop after the local slice cannot upgrade failed payment result or mutate a returned report: Task 2.

### Task 1: Label known preparation boundaries

**Files:** infrastructure/stripe-form-diagnostic-provision.mjs and .test.mjs; infrastructure/stripe-manual-payment.test.mjs if needed for actual controller composition; docs/local-development.md; docs/stage-5a-acceptance.md.

**Interfaces:** Existing formatManualPaymentProvisionDiagnostic(error) remains exactly one {tag,stage} line. Existing prepare(limits), session/binding and controller report shapes stay unchanged. Add stages PRIVATE_STORAGE, ADMIN_SESSION, FIXTURE_SETUP, CHECKOUT, FINAL_BASELINE as described in the spec; all original labels/fallback remain.

- [ ] Add table-driven behavioral tests injecting failures into the real provisioner at each of the five boundaries, for ordinary and selected 3DS. Expect the literal corresponding stage, exit/failure and no later provisioning mutation; repeat prepare must not resume or create another checkout. Include a post-credential hostile throw and unchanged unbranded UNKNOWN/redaction coverage.
- [ ] Run the new tests against unchanged production; record expected UNKNOWN-versus-named-stage failures (RED).
- [ ] Set markers immediately before directory proof, admin-session dispatch, initial empty baseline, checkout mutation and final baseline respectively. No helper/framework or provisioning behavior changes.
- [ ] Run provisioner and manual controller tests; record GREEN and self-review. Update runbook/acceptance docs with exact labels, fallback limits and historical uncertainty; no acceptance claim.
- [ ] Commit only Task 1's exact source/test/doc paths after verification. Independent task reviewer must approve spec and quality before Task 2.

### Task 2: Reconcile directly observed stop fulfillment

**Files:** infrastructure/stripe-manual-payment.mjs and .test.mjs; docs/local-development.md; docs/stage-5a-acceptance.md.

**Interfaces:** Existing cached bootstrap.stop(cleanup) returns true only on its own verified closure. Record its literal-true settlement with observation time and reconcile runtime_closed before final report construction iff strictly before original cleanup ceiling. No new calls, waits, status reads, report fields or extensions. Task 1's marker formatter/report contracts are unchanged.

- [ ] Add virtual-time deferred-stop regression: preparation fails; stop starts once at t=1000; its first slice expires at t=31000; literal true settles at t=32000 before ceiling 2821000. Expect runtime_closed=true but result=PENDING/phase=PROVISION/exit1, no gateway/financial continuation or retry. Test ordinary and selected 3DS.
- [ ] Add negative cases false/rejection, exact-ceiling, after-ceiling, never-settled and post-report late fulfillment; runtime_closed remains false and returned report immutable. Check original stop deadline and once-only stop; preserve existing late-gateway semantics.
- [ ] Run new tests before implementation and record RED; implement minimal cached-stop settlement evidence and pre-report reconciliation only.
- [ ] Run manual controller/provisioner/bootstrap tests, record GREEN/self-review and update docs explaining conservative evidence versus persisted STOPPED. No historical report rewriting or live-cause claim.
- [ ] Commit Task 2's exact paths after verification and obtain independent spec/quality review.

## Final gates

- [ ] Run node --test --test-concurrency=1 --test-timeout=30000 --test-skip-pattern='Docker context excludes|rendered local backend identity|opt-in Compose' infrastructure/*.test.mjs; require exit0 and report actual counters. No recursive recovery tests or real Docker checks.
- [ ] Fresh independent final review of this request's complete diff from bb78702335e6bc04e02bec543adca1edfdebc25c, requirements and evidence; resolve findings and verify amended tests.
- [ ] Verify exact changed paths/whitespace, preserve unrelated files and private evidence, report proven corrections versus unproven live cause. No push or Sandbox launch.
