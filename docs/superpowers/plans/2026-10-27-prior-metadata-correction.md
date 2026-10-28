# PRIOR_METADATA Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the auxiliary metadata/status conflation without weakening live admission or changing historical evidence.

**Architecture:** A pure inventory validator is used by a corrected local reference of the failed one-off checker. Metadata identifies resources, not stopped state. Existing live state gates remain mandatory and unchanged.

**Tech Stack:** Node.js built-ins and node:test; no new dependencies.

**Spec:** .superpowers/prior-metadata-design-2026-10-27.md and the current human request (offline fix with subagents, tests, independent review).

## Global Constraints

- No Docker/client, Sandbox/Stripe, network, process inspection, SQL, keys, financial operations, archive reads, installs, Python, deletions, push or runtime retry.
- Do not read or change historical runtime files, payments, refunds or archives. Preserve the original helper reference and old reports.
- Preserve bootstrap's sticky PENDING behavior and all native financial/report schemas.
- Expected PENDING is pinned only to the exact latest prior run/source in the corrected local reference; other three rows still require STOPPED. No global allowPending.
- Inventory never proves closure or grants READY. Preserve private-file checks, process absence, all40 exact-ID state/PID/ownership checks, capacity and native preflight.
- Work only in the existing isolated worktree. Preserve unrelated untracked recovery files/plans.

## Review Focus

- An unrelated PENDING row must still fail when STOPPED is expected.
- Unknown metadata states must fail even if supplied as the expected state.
- Wrong run/source/daemon/project or role/ID substitutions must fail without raw input in errors.
- PENDING inventory must not bypass the later all40 stopped-state checks or process-absence gate.
- A successful stop after a failure must retain PENDING and reject subsequent runtime start/verify.

### Task 1: Correct the auxiliary inventory boundary and pin its semantics

**Files:**
- Create: infrastructure/stripe-prior-runtime-inventory.mjs and .test.mjs
- Modify: infrastructure/stripe-sandbox-bootstrap.test.mjs (or its existing relevant split test file), docs/local-development.md, docs/stage-5a-acceptance.md
- Create local reference only: .superpowers/prior-metadata-corrected-2026-10-27.txt

**Interfaces:**
- Produces `priorRuntimeInventory(metadata, expected)`; expected = `{runId, expectedSource, daemon, expectedMetadataStatus, backend}`. Return frozen array of frozen `{id, run, role}` records; failures throw fixed `PRIOR_METADATA_UNCONFIRMED` without raw input. No IO or authorization.
- Consumes parsed JSON and trusted expectations from the one-off checker. Status must equal the exact expectation AND be one of PENDING/STOPPED. Role set is the existing six roles plus backend only when backend=true; full lowercase64hex IDs only. Identity must exactly match run/source/daemon/stripe-run project.

- [ ] Add regression tests first. Observe the old STOPPED-only predicate reject the expected latest PENDING. Test STOPPED, unrelated PENDING, identity/roles/full-ID rejection, unknown states and immutable unverified output. Use synthetic values only.
- [ ] Add a synthetic real-bootstrap regression: injected operation failure, stop returns true, resources and fake durable ledger remain PENDING, further start/verify fail without new effects. Reuse existing fixture boundaries; no real IO.
- [ ] Implement the minimal pure validator. Do not add live transport or a new diagnostic framework.
- [ ] Prepare corrected helper reference from the original. Replace metadata validation/collection with the validator and exact per-row expected statuses. Retain every other gate byte-for-byte where possible; do not execute/import this reference.
- [ ] Add concise documentation of inventory vs live-state evidence and the offline-only result. Do not mark current runtime admitted or stage5A accepted.
- [ ] Run focused and related offline tests. Main controller owns full permitted infrastructure suite (six actual Docker tests excluded as before); no backend changes mean no Maven run.
- [ ] Write report with RED/GREEN commands/results, changed files, exact reference differences, self-review, remaining unverified runtime checks. No commit/push in this task.
- [ ] Independent task review, then final scoped integration review; address blocking findings with the implementer and targeted tests.
