# Owned method and recovery reads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare strict descriptive reads of a saved method and later full payment of the same failed renewal debt.

**Architecture:** Extend the existing mapper with separate actual-debt lanes and reuse complete invoice/cash normalization. Supplied receipt consistency is checked without granting authority; durable SETUP/fence integration remains closed.

**Tech Stack:** Java25, cached stripe-java34.0.0/API2026-09-30.endive, inert SDK fixtures, JUnit/AssertJ.

**Spec:** docs/superpowers/specs/2026-11-06-owned-method-recovery-reads-design.md

## Global Constraints

- No local Docker, Sandbox/provider calls, financial operations, Python/install, local SQL/application contexts, old operations/DISPATCHING, keys or operational archives. No default Maven test discovery. Cached dependency-classpath resolution and explicit pure javac/JUnit only; hosted fresh-fixture CI is a separate authorized publication gate.
- All edits use apply_patch. Existing linked worktree/branch; root owns Git, reports are preserved. No agent spawning by workers, no parallel implementation writers.
- Descriptive package-private reads only: no registration, routes, new dependencies/schema, grants, reservation, fact publication, provider mutation, clock producer or runtime activation. No fabricated ACTIVE local owner, SETUP.SUCCEEDED or loosened V34/isolated predicates.
- No secrets/card details/operation markers in new results. New SDK calls require existing ReadExecutor/options, max100 requests, max10 pages/page10, sharing the same Read instance across invoice/setup/method calls; full timeout and clock/fence authentication remain caller responsibilities, not a new authority claim.
- Preserve all existing mapper/service behavior. Native method/recovery NOT_RUN; full5B NOT_ACCEPTED.

## Review Focus

- A consistent caller-created record must never be represented as authenticated persistence or permit fact publication (both tasks).
- Real PAST_DUE prestate and unchanged initial CASH/debt/grace cannot be bypassed using a copied ACTIVE projection (both tasks).
- A succeeded SetupIntent for another customer/operation/method or a merely bound method cannot prove this method candidate (Task1).
- A later paid invoice with a new allocation/PI, refunds, split/noncash funding or wrong period cannot discharge this exact debt (Task2).
- Shared normalization/refactoring must preserve old success/failure semantics, executor options and request/page limits (both tasks).

### Task 1: Saved method graph on unchanged failed debt

**Files:** Modify `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingSnapshotMapper.java`; create matching `StripeBillingOwnedMethodMappingTest.java` under the same test package. A focused test fixture/helper in that package is allowed if it avoids duplicating large graphs. Reuse existing first/failed-renewal fixtures.

**Interfaces:** In mapper produce package-private `record OwnedMethodCandidate(SubscriptionOperation operation, SubscriptionBillingProofStore.Setup setup, ProviderOperation setupStep, ProviderOperation methodStep)`; `record OwnedMethodRead(Snapshot snapshot,String setupIntent,String method)`; static `retrieveOwnedMethodReady(StripeClient client,Ownership owner,OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Failure failure,OwnedMethodCandidate candidate,DigestPort digest,Instant observedAt,ReadExecutor executor)`. Task2 reuses the actual-debt owner/graph normalization created here; author report pins exact internal helper names and invariants.

- [x] Write positive tests of the coherent pending-SETUP/ACK-step/card/default-method graph over actual local PAST_DUE state and unchanged failed renewal, plus local owner/failure/candidate mismatches refusing before SDK IO. Require observation>=failure.recordedAt and observation>=SETUP updatedAt>=SETUP creation>=failure.recordedAt; exact supplied admission/failure invoice/period and available step dispatch/retry/order chronology. ProviderOperation lacks step-created/ack timestamps: do not claim proof of those absent fields. Observe RED with the missing entry point/behavior.
- [x] Implement strict descriptive method read, including exact setup/method fingerprint joins and remote succeeded SetupIntent/customer/card/default ownership checks. Reuse existing complete invoice/failed PI/Charge normalization; no ACTIVE prestate fabrication or operation marker. Test step UNKNOWN/DISPATCHING, wrong credentials/kind/reference/key/resource, Setup/method drift, remote status/type/live/usage/metadata/window errors, no secrets, GET-only paths/options, no new cash, and shared page-cap refusal.
- [x] Freshly compile current affected production/test closure and run explicit focused plus related pure regression. Record exact commands/results/source hashes; independent Task1 spec and quality review, fixes, root commit. No hosted fixture execution locally.

### Task 2: Recovered cash for the exact saved renewal

**Files:** Extend same mapper; create `StripeBillingOwnedRecoveryMappingTest.java`; update `docs/stage-5b-acceptance.md` and `docs/testing/billing-failed-renewal-recovery.md` with precise preparation/blockers and publication-checkpoint scope.

**Interfaces:** Consume Task1 actual-debt normalization and existing `OwnedFailedRenewal.Recovery`, `SubscriptionOperation`. Produce package-private `record OwnedRecoveredRead(Snapshot snapshot,String allocation,String paymentIntent)`; static `retrieveOwnedRecoveredRenewal(StripeClient client,Ownership owner,OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Failure failure,OwnedFailedRenewal.Recovery recovery,SubscriptionOperation operation,Instant observedAt,ReadExecutor executor)`.

- [x] Write positive actual-debt→descriptive ACTIVE/two-CASH graph using exact saved renewal allocation/PI and a separate consistent RESERVED RECOVER candidate. Require observation>=recovery.associatedAt>=failure.recordedAt and exact admission/failure/recovery IDs/invoice/period before IO. Observe RED. Assert original initial cash unchanged, no operationKey/APPLIED marker/authority, complete GET-only graph.
- [x] Implement local Failure/Recovery/operation consistency and exact recovered remote graph, reusing Task1/shared normalization. Check existing owned authority/fingerprint formula, version/revision/policy, exact original and renewal identity, full amounts/currency/status, default/single allocation, reverse graph, refunded/unknown/zero/noncash/foreign/third invoice/period/tax/adjustment drift and SDK errors. Verify method success cannot be supplied as recovery evidence and legacy lanes remain strict.
- [x] Run fresh focused and all related explicit pure regression including legacy first/failed-renewal mapping/observer, ordinary recovery/method mapping where pure, service/fact guards. Update evidence docs with synthetic vs native distinction, retained durable SETUP/fence barriers and future contextual recovery publication requirement. Independent Task2 review/fixes, root commit.

## Final gate

Whole-change independent review from base8eed64c, current-source hash/diff checks, push and both exact-source GitHub workflows remain mandatory final gates. Their results are recorded in the ignored publication receipt/ledger without a self-referential bookkeeping commit. No Docker/Sandbox follows automatically.

Preserve ignored workspace and all prior evidence. Document significant root decisions and their consequences. Completion means this offline read preparation and its verification only; genuine durable method completion and native recovery remain open.

At this offline prepublication checkpoint both tasks passed independent spec/quality review. Current-source pure regression passed549/549 with zero failures/skips/aborts; root checked all26recorded sourcehashes. Existing unchecked compiler notes and JVM CDS warning remain documented. Hosted and native evidence are separate boundaries; the exact-source publication receipt carries the later hosted result.
