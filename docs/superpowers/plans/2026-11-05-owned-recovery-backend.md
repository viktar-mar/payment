# Owned failed-renewal recovery backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement durable backend debt/recovery orchestration and its narrow fact-publication gate offline.

**Architecture:** Explicit-constructor internal service plus JDBC/append-only V34 lineage, using existing fact/cash/event/outbox projection. Existing constructors and ordinary/successful lanes remain closed to isolated recovery.

**Tech Stack:** Java25, Maven/current cached dependencies, PostgreSQL migration, JUnit. No installs.

**Spec:** docs/superpowers/specs/2026-11-05-owned-recovery-backend-design.md

## Global Constraints

- No Docker/daemon/container, Stripe/Sandbox/network/Python/install, application context, SQL/database or browser execution; no private env, archives or historical operational data reads.
- Old operations/DISPATCHING are not adopted/replayed/reset. Historical migrations V30–V33 byte-identical. No invoice.pay/confirm/new PI or native activation.
- Existing ordinary/Spring constructors deny isolated recovery. No registry/endpoint/bean/scheduler/export changes. Raw provider observations, native billing-time advances and browser handoff remain unsupported.
- Same immutable original published price's positive amount/currency/recurring interval, trial0; no hardcoded EUR20 production constant (native future scenario stays EUR20). Same original debt/invoice/allocation/PI; UTC independent of billing time; fixed30-second single-use reads, no renewal/retry/takeover.
- Actual FactService/cash/event/outbox code must perform failed/recovered publication; no fabricated success flags or custom Payment insertion shortcut.
- Root owns Git writes and final review; authors do not spawn agents. Preserve worktree/artifacts. User requests no intermediate approval stops; routine decisions are recorded and execution continues.

## Review Focus

- Failed branch cannot race/coexist with successful V33 publication/read claim (Task1 SQL, Task3 hosted race).
- Missing/current-owner drift after debt must refuse, not fall back to original/success/ordinary authority (Tasks1–2).
- Exact RECOVER/bill/PI/fence/read acceptance and default denied FactService; unknown/old/unbound inputs cause no cash/access (Task2).
- Atomically rollback facts, association and receipt; terminal stop survives the unwind without nested lifecycle-lock transactions (Tasks2–3).
- Authored SQL/tests and normalized input trust are never reported as executed/native financial proof (Task3 docs).

### Task 1: Durable model/store and additive migration

**Files:** Create provider/application/model/OwnedFailedRenewal.java, provider/application/port/out/OwnedFailedRenewalStore.java, provider/adapter/out/persistence/JdbcOwnedFailedRenewalStore.java under backend/src/main/java/dev/genericpayments/platform; create backend/src/main/resources/db/migration/V34__stripe_billing_owned_failed_renewal.sql; pure model tests under backend/src/test/java/.../provider/application/service/OwnedFailedRenewalModelTest.java.

Modify `JdbcOwnedFirstRenewalStore.java` only if needed for a narrow package-private immutable predecessor reload helper, without changing any existing consumer behavior. Such a helper is descriptive reload, never live failure/recovery authority.

**Interfaces:** Model records/phases and store methods from spec; author chooses focused exact record field signatures and documents them before Task2. Methods admitInTransaction/current/claimReadInTransaction/requireReadInTransaction/recordFailureInTransaction/requireFailureInTransaction/associateInTransaction/requireRecoveryInTransaction/recordPublicationInTransaction/publicationInTransaction/stopInTransaction/requireRecoveryFactInTransaction. All concrete, no unsupported placeholder bodies. Fact proof consumes ApplySubscriptionSnapshotUseCase.Command, locked Subscription/operations, billingAt and utcAt.

- [x] Step1: Test model invariants and fixed30-second/ownership/source/identity/failure constraints first, meaningful RED then GREEN with audited pure tests.
- [x] Step2: Implement actual V34/JDBC lineage, exclusion, claims/current/debt/association/publication/stops, composite scope constraints and immutable receipts. Use existing paid/ready provenance for admission only; independent exact post-debt authority after failure. Parameterized SQL and caller-transaction checks.
- [x] Step3: Compile cached model/JDBC sources without Spring/SQL execution; retain SQL execution as unverified until Task3 hosted tests. Independent task spec/quality review; fix via author, root local commit.

### Task 2: Real orchestration and deny-by-default fact seam

**Files:** Create provider/application/service/OwnedFailedRenewalService.java; subscriptions/application/port/out/SubscriptionIsolatedRecoveryProofPort.java; provider/adapter/out/persistence/OwnedFailedRenewalFactProof.java; modify SubscriptionFactService.java with explicit proof-constructor overload only; create focused pure service/fact-guard tests.

**Interfaces:** Consume Task1 records/store. Service methods admit/beginFailedRead/publishFailure/reserveExistingRecovery/beginRecoveredRead/publishRecovery/replayPublication/stop. Port void requireAuthorized(Command,Subscription,List<SubscriptionOperation>,Instant billingAt,Instant utcAt), static denied() default; adapter delegates to store's current proof under caller transaction. Existing constructors preserve denied(). Keep all common isolated guards and reject every unrelated operation/marker even with a proof port.

- [ ] Step1: Write meaningful failing pure tests for real service calls and default denial, read expiry/stale/foreign/fence/PI/unknown/duplicates, same-debt recovery and rollback/stop orchestration. Audit closure before execution, no application contexts.
- [ ] Step2: Implement lock ordering, concrete claim/reservation/association/publication through existing operations and FactService. No provider traffic or custom cash insertion. Require exact isolated billing sample; never set global Clock.
- [ ] Step3: Implement narrow proof gate before effects; common shape checks stay binding. Test existing constructors and forged proof/foreign marker/unsupported shapes. Run focused/related pure regression and cached compilation. Independent review then root commit.

### Task 3: Hosted composition source and honest operator documentation

**Files:** Create backend/src/test/java/.../provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java and focused support fixture only if needed. Reuse StripeBillingFirstRenewalCompositionFixture genuine initial/READY producers; no fake inserted successful payments. Modify docs/testing/billing-failed-renewal-recovery.md, docs/stage-5b-acceptance.md and new design/plan progress as needed.

**Interfaces:** Actual Task1–2 constructors/methods; test-only exact-owner isolated time sample. Runtime/provider/browser factories remain unchanged.

- [ ] Step1: Author genuine JDBC+real FactService failed OPEN debt→grace-1/G checks→explicit same-PI RECOVER association→CASH publication; assert original cash, actual bill/debt/operation/event/outbox and no INVOICE_PAY. Include concurrent branch exclusion, expired/stale/foreign claims, association rollback, post-fact rollback, stopped/no-resume and idempotent read-only replay cases.
- [ ] Step2: Compile hosted tests but do not execute; run all audited related pure regression. No default Maven test discovery; use cached test-compile or explicit javac closure. Report exact executed vs unexecuted checks and missing runtime/raw-provider/confirmation/time evidence.
- [ ] Step3: Update guide/matrix precisely; independent task review and final whole-change review, root local commits. No push/CI/native run required by this offline request.

## Self-review

Task1 produces immutable model/store APIs consumed by Task2 and genuine DB tests in Task3. Task2 owns the only shared production seam, backward compatible/denied by default; Task3 changes only tests/docs. Model/SQL current predicates must agree on before/after failure/publication state. Review the SQL exclusion and lock order together before accepting either branch. No task grants runtime permission.
