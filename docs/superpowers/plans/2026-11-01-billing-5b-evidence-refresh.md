# Stage 5B current evidence refresh

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Complete the task without intermediate continuation questions.

**Goal:** Make the tracked acceptance and operator documents accurately describe the completed hosted gate and failed native first-payment attempt, without claiming Stage 5B acceptance; prepare the separately scoped next first-successful-renewal design and implementation plan.

**Architecture:** Documentation only. Preserve dated evidence and existing refusal/ownership boundaries; add an authoritative current checkpoint and distinguish an executed failed attempt from an unexecuted remaining scenario. No runtime or product behavior changes.

**Tech Stack:** Markdown, existing Git evidence, Node.js built-ins for local link and receipt checks.

**Spec:** `docs/superpowers/specs/2026-10-28-stripe-billing-5b-design.md` §7 and existing Stage 5B implementation plan Task 12. Source receipts are the recorded publication completion and native run reports named below.

## Global Constraints

- Сохранить `stripe-java 34.0.0` и API `2026-09-30.endive`. Default Fake и отключённый Stripe не требуют ключей.
- Старые EUR13 и DISPATCHING не являются fixtures 5B. Без новых recovery collectors, Python, распаковщиков или исследования старых архивов.
- No local Docker, application/SQL, Testcontainers, browser or Stripe execution; no dependency installation or native attempt. Reading saved sanitized metadata is sufficient.
- Preserve existing reports, historical failed gates, keys, archives and runtime ledgers. Do not rewrite historical ACK/DISPATCHING or treat cleanup as acceptance.
- Stage 5B remains NOT_ACCEPTED. Recorded CI success establishes only its exact source and hosted checks. Do not invent test counts absent from the receipt.

## Review Focus

1. A current unqualified NOT_RUN label must not hide an executed failed native initial attempt.
2. An old component checkpoint must not claim that the current code or native payment passed.
3. Historical failed gates remain identifiable and are not relabeled successful.
4. The latest run stopped before CREATE/CONFIRM, while older unresolved CREATE records remain protected; do not generalize the latest boundary to every old run.
5. Cleanup of four never-started objects from the latest run must not be confused with a new run, accepted payment or current presence of all seven containers.

## Task 1: Refresh acceptance and operator checkpoints

**Files:** Modify `docs/stage-5b-acceptance.md` and `docs/local-development.md` only. Do not edit original specs, application code or CI workflows.

**Interfaces:** Consume the following saved receipts as read-only evidence:

- `.superpowers/sdd/2026-11-01-first-payment-confirm-diagnostics/publication-3b9114c/completion.json`: source `3b9114c9599a59efe0e5290d2992c5f57b8c564c`, hosted CI `41302204254`, all three jobs successful.
- `.superpowers/native-billing-first-payment/150000ac-0789-4992-98c6-238dbf38b11d/report.json` and its `completion.json`, plus `publish-3b9114c/status.json`: failed initial attempt at `START_POSTGRES_HEALTH_NOT_RUNNING`, no CREATE/CONFIRM dispatch in this run, authorization consumed, no retry.
- `.superpowers/investigations/2026-11-01-postgres-start-150000ac/read-only-log-observation.json` and `storage-findings.md`: bounded read yielded STORAGE_NO_SPACE; no exact exhausted filesystem/inode/quota or fixed startup is established.
- `.superpowers/inventories/2026-11-01-old-containers-networks-150000ac/approved-six-cleanup/result.json`: exactly six approved never-started containers removed, including four of run150000ac and two of run15f2c8fd; all networks/volumes/images/cache and remaining objects preserved. Keep this operational note brief.

- [ ] Read the two documents and source receipts. Add a dated current checkpoint with exact source, CI URL, actual failed native boundary and remaining provider acceptance.
- [ ] Update current status statements and the prepared first-payment lane table. Clearly distinguish historical component/compiled-only checkpoints from the later exact-head hosted gate; retain their historical counts and failures.
- [ ] Explain that ordinary UTC, immutable original clock F and original initial-only guards stay in place. The next offline product prerequisite is a separately scoped first unchanged-terms renewal with durable advancement/later READY evidence; it is not enabled by this documentation task.
- [ ] Verify the exact SHA/run/job outcomes against the saved JSON, local Markdown link targets and anchors for changed links, `git diff --check`, and changed-file scope. Do not add tests that merely mirror documentation.
- [ ] Independently review spec compliance and documentation quality, fix findings, and commit the reviewed checkpoint. No new native execution follows from the commit.

## Task 2: Prepare the first unchanged-terms renewal design and plan

**Files:** Create `docs/superpowers/specs/2026-11-01-owned-first-renewal-design.md` and `docs/superpowers/plans/2026-11-01-owned-first-renewal-implementation.md`. Root may add pointers in the reviewed acceptance/operator checkpoint after the new documents pass review.

**Scope:** Design preparation only, not execution or capability activation. Bind the paid-admission, immutable advance/READY evidence, typed first-renewal mapping, scoped billing time and FactService publication to concrete existing consumers. Keep initial native acceptance outstanding. Do not add direct SDK-only tests before their actual production consumer exists.

- [ ] Ground the proposal in current classes/migrations and official Stripe advance/status/invoice-timing documentation. Identify newer public-documentation versus pinned API limits explicitly.
- [ ] Define exact paid initial admission; `OwnedInitialConfirmationProof` alone is preconfirmation association, not payment proof. Preserve existing UTC and lock order.
- [ ] Specify a finite first-renewal progression, immutable original evidence, per-step single-dispatch/no-replay, uncertain outcomes and safe intermediate invoice states. No implicit financial budget or old fixture adoption.
- [ ] Assign concrete implementation/test files and genuine RED scenarios to their owning consumers; local integration execution stays forbidden, hosted CI remains the full checkpoint.
- [ ] Independently review correctness, feasible task boundaries, source claims and scope. Verify links/whitespace; no tests that grep human prose. Do not start implementing this new plan in the design-preparation task.
