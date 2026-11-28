# Owned failed-renewal observation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Prepare a strict descriptive failed-renewal graph mapper without enabling any runtime or financial action.

**Architecture:** A separate package-private entry shares strict normalization with the current first-renewal mapper through closed internal lanes; successful-renewal and ordinary/default guards remain binding. Existing owned recovery service/store are unchanged.

**Tech Stack:** Java 25, pinned stripe-java 34.0.0 / API 2026-09-30.endive, cached JUnit/AssertJ and handwritten in-memory SDK transport.

**Spec:** docs/superpowers/specs/2026-11-06-owned-failed-renewal-observation-design.md

## Global Constraints

- No Docker, Sandbox/Stripe network, financial operations, Python, installation, local Spring/SQL, old operations/DISPATCHING or operational archive/key reads.
- No runtime/bean/route/registry/export/launcher/schema/time/confirmation changes. No bypass of ordinary or paid-renewal guards. No new dependencies.
- Descriptive raw graph output only; no persisted claim, acceptedAt or method/recovery authority inferred from input records. Native failed-renewal remains NOT_RUN.
- Root owns Git and ledger; authors do not commit or spawn agents. Preserve worktree and evidence. Continue without user approval pauses.

## Review Focus

- OPEN invoice alone is not a declined payment: require matching failed PI/error/Charge and exact reverse allocation.
- Sharing normalization must preserve every successful/ordinary guard and prohibit a public permissive mode.
- Wrong customer/clock/catalog/boundary/initial cash and extra invoices must refuse before producing evidence.
- Unknown amounts or non-cash settlement must never create a successful cash fact or method-readiness claim.
- Pure in-memory tests and hosted SQL are separate from native provider/callback acceptance.

### Task 1: Strict failed-renewal mapper, regression and current evidence

**Files:** Modify `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingSnapshotMapper.java`; create `backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingFailedRenewalMappingTest.java`; reuse or narrowly extend package-private helpers in `StripeBillingFirstRenewalMappingTest.java`. Update `docs/stage-5b-acceptance.md` and `docs/testing/billing-failed-renewal-recovery.md`.

**Interfaces:** `static FailedRenewalRead retrieveOwnedFailedRenewal(StripeClient, Ownership, OwnedFailedRenewal.Admission, Instant, ReadExecutor)` and package-private record `FailedRenewalRead(Snapshot snapshot,String allocation,String paymentIntent)` in the existing mapper. Shared private graph representation is an implementer decision; existing paid entry signatures/results unchanged.

- [x] Write failing inert SDK tests for valid decline normalization and success-lane refusal. Observe meaningful RED for absent entry, then implement the smallest closed-lane extraction and decline verifier from the spec.
- [x] Add negative raw graph tests covering owner/catalog/boundary, initial cash, unattempted/processing/action/succeeded PI, missing/inconsistent decline error, wrong/paid/captured Charge, amount/refund/outcome drift, split/canceled/foreign/reverse allocation, third invoice, taxes/credit/period drift. Assert no provider writes, original cash unchanged and no method/recovery authority.
- [x] Audit selected test closure, freshly compile current changed production/test sources against cached dependencies, run new and related first-renewal/owned-clock mapping regression with explicit selectors. No Maven default tests. Record exact commands/results and native limitations.
- [x] Correct stale hosted-execution claims using CI42122504000 and42122504028 at f9e8568, then describe new mapper scope/missing next integration in matrix/guide. Independent combined task and whole-change spec/quality review; fix/retest findings. Root commit only after verification; no native launch.

## Completion

The plan's ignored ledger records executed checks, reviews, commits and decisions. This slice is complete when the mapper and tests pass independent review; it does not close Stage 5B or prepare an executable Sandbox command.

## Offline completion checkpoint

Independent task/whole-change review passed after adding coherent paid/draft, local owner/revision and unknown-amount refusal controls. Root freshly compiled the current model/mapper/tests and passed **213/213** explicitly selected in-memory tests, with zero failures/skips/aborts. The author separately passed112/112 mapper cases. Initial RED proved the missing API rather than the desired graph behavior; a later missing-card-details control was observed failing before its guard, then passing. This testing-order limitation is preserved in the reports, not recast as a behavioral RED for the whole mapper. No local Docker, Stripe/Sandbox, SQL/context, Python, installation or historical operational-data access occurred. Publication and any hosted CI results are recorded separately in the ignored ledger; native remains NOT_RUN and Stage5B NOT_ACCEPTED.
