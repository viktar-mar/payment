# Failed renewal / grace / recovery offline component Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare and verify the next subscription lifecycle contract offline without introducing unsupported native recovery authority.

**Architecture:** A pure test-support Java scenario validates synthetic observations using existing subscription domain rules. Documentation separates its offline scope, the successful native initial/renewal predecessor and the still-missing native recovery integration.

**Tech Stack:** Java25, cached JUnit/AssertJ and current Maven project; no new dependencies.

**Spec:** docs/superpowers/specs/2026-11-05-failed-renewal-recovery-offline-design.md

## Global Constraints

- No Docker/daemon/container, Stripe/Sandbox, network, Python, installs, application context, SQL/database or browser execution; no secrets, archives or historical operational-data reads.
- Preserve production source, migrations, existing successful first-renewal guards, runtime packaging, default Fake and all old operations/DISPATCHING. No native activation or launcher.
- EUR2000 initial + EUR2000 recovered renewal, unchanged MONTH terms; G=E+259200; business time independent of operational UTC.
- Pure test-support observations are not authenticated provider/cash/journal/callback proof. Native NOT_RUN for this new scope; full5B NOT_ACCEPTED.
- Root owns Git writes. Implementers never spawn subagents; each task has independent spec/quality review and final combined review. Preserve this worktree, reports and all evidence.
- User requested no intermediate approval stops; make routine reversible implementation decisions and record material choices.

## Review Focus

- Mutation of a prior observation or wrong owner/terms cannot bypass lineage (Task1).
- Repeated failure/policy changes cannot move grace; exact expiry denies access (Task1).
- Setup, RECOVER acknowledgement, UNKNOWN/DISPATCHING cannot create paid access or cash (Task1).
- Same renewal invoice/bill/PI and unchanged original cash remain required after recovery (Task1).
- Offline validation and historical native evidence cannot be reported as a runnable/accepted recovery lane (Task2).

### Task 1: Pure ordered domain scenario and regression

**Files:** Create `backend/src/test/java/dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalScenario.java` and `OwnedBillingFailedRenewalScenarioTest.java`. Optional private fixture helper in the same test package only.

**Interfaces:** Produce a small synchronous single-use scenario with immutable lineage and explicit observation stages INITIAL_PAID, FAILED_RENEWAL, REPEATED_FAILURE, GRACE_LAST_SECOND, GRACE_EXPIRED, METHOD_READY, RECOVERY_REQUESTED, RECOVERED. The author chooses focused Java record signatures using real Subscription/BillingRecord/SubscriptionOperation domain data; document them in the report. There is no dependency-injected executable driver or runtime adapter. Return only offline-contract validation with no native/provider acceptance claims.

- [x] Step1: Write meaningful failing pure tests first; record RED. Use real domain reducers/access/recovery rules. Audit selected test closure before execution, never default Maven/Testcontainers.
- [x] Step2: Implement minimal scenario and strict immutable lineage/debt/cash/explicit-operation checks for the fixed stages from the spec; first failure consumes the scenario. Reject repeated/out-of-order calls and wrong/missing evidence.
- [x] Step3: Prove all specified rejection classes including cross-owner, changed terms/bill/period, moved deadline, new cash on failure/setup, unknown/live authority, changed original payment or recovery PI, and late recovery without current access. No fabricated verified flags.
- [x] Step4: Compile cached source/test closure and run new pure tests plus SubscriptionAccessPolicyTest and RecoveryRulesTest. Record command/counts, self-review and any limits in the task report; root independent review then local commit.

### Task 2: Acceptance and operator preparation documentation

**Files:** Modify `docs/stage-5b-acceptance.md`, `docs/local-development.md`; create `docs/testing/billing-failed-renewal-recovery.md`. Spec/plan are part of this task's reviewed documentation scope.

**Interfaces:** Consume Task1 exact implemented stage API/test evidence. The operator guide names fixed future business-time boundaries and required native adapters/evidence, but supplies no runnable unsupported command or financial authorization.

- [x] Step1: Record successful native predecessor e7080356 at a59dad7 with exact receipt/run/source and confirmed cleanup. Explicitly supersede stale current-status claims without erasing historical failures.
- [x] Step2: Document the new offline component, pure tests actually run, and precise native blockers (durable admission/publication, isolated time/recovery permission, method transition, runtime/SQL/callback proof). State no Docker/Stripe execution and no native recovery acceptance.
- [x] Step3: Check local links, exact source bindings and no misleading runnable/accepted claim. Root independent spec/quality review; commit, then final combined independent review. No push/CI or native run is required by this offline request.

## Self-review

Task1 owns all new executable code; Task2 consumes its API/results only. Shared interface is the fixed stage contract above. No production/shared-runtime file is shared between tasks. Requirements and test assertions agree on fixed grace, original cash preservation and synthetic-only proof limits. Existing linked worktree is reused; no new checkout or workspace deletion.
