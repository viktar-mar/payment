# Manual refund proof diagnostics — bounded offline correction

> **For agentic workers:** Use superpowers:subagent-driven-development for the single task below, followed by task and final review. This is the narrow correction approved in chat, not a new runtime release plan.

**Goal:** Reproduce weaknesses in the refund proof observer offline, correct only demonstrated observer defects, and expose a bounded safe explanation for a future unconfirmed result.

**Architecture:** Keep the financial controller and its original-target one-shot fence. Investigate the existing multi-read proof flow with synthetic responses and fake time. Add an allowlisted diagnostic projection without relaxing money, identity, webhook, journal, callback, deadline, or cleanup proofs.

**Tech Stack:** Existing Node.js 24 ESM infrastructure and node:test; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-20-manual-refund-design.md`, with the user's approved narrow follow-up: offline diagnosis, safe diagnostics, tests for delays/races, independent review; no Sandbox or repeated refund.

## Global Constraints

- Work only in the existing recovery worktree on `codex/stripe-sandbox-5a`; main is untouched.
- No Docker/Compose commands, services, Stripe CLI/OAuth/listener/provider calls, real Admin HTTP, private env/config/credentials, retained run/state/lock reads or mutations. Tests use only synthetic fixtures, process/transport doubles, and fake clocks.
- No refund/rotation retry, automatic resume, baseline bypass, lock deletion, historical report promotion, new run authority, or preparation of a remainder-only runtime.
- Preserve original amount EUR2000, refund legs 700/1300, exact ownership and identities, one rotation, and confirmation-before-remainder. False still means unverified, not absence of effects.
- Preserve active authority <=30min, original cleanup ceiling +2min, HTTP/provider reads <=10s, proof <=30 probes/45s, and existing raw-work settlement before runtime stop. Do not increase limits or relax integrity failures to obtain green tests.
- The finite public report remains <=4096 bytes, with no IDs, paths, keys, secrets, payloads, raw exception messages/stacks or arbitrary strings. Diagnostics must be static allowlisted values and bounded counters, validated as own data without running getters; old version-1 report inputs remain valid.
- No Java/API/DB/SDK changes, installations, commits, push, merge, or PR in the worker. ROOT decides a local commit after review; no push or merge is included.

## Review Focus

- Normal acknowledgement may precede webhook and callback processing: bounded observation, never repeated financial POST.
- Separate detail/list/operation reads can straddle an update: distinguish demonstrated benign convergence from foreign identity or contradictory financial facts.
- Terminal/foreign/reconciliation-only evidence must remain fail-closed; diagnostics cannot bless success.
- Abort/deadline/close can race the observer: no authority renewal, escaped body work, second POST, or lost cleanup evidence.
- Untrusted diagnostic objects and exception canaries must not execute accessors or leak into output.

### Task 1: Investigate and correct the proof observer with safe failure diagnostics

**Files:**
- Inspect/modify as demonstrated necessary: `infrastructure/stripe-manual-refund-session.mjs`, `infrastructure/stripe-manual-refund-session.d.mts`, `infrastructure/stripe-manual-refund-session.test.mjs`.
- Diagnostic integration if needed: `infrastructure/stripe-manual-refund.mjs`, `infrastructure/stripe-manual-refund.test.mjs`, `infrastructure/stripe-manual-refund-schema.mjs`, `infrastructure/stripe-manual-refund-schema.d.mts`, `infrastructure/stripe-manual-refund-schema.test.mjs`.
- Explain changed contracts and limits in `docs/local-development.md`; update the refund design only to describe this approved diagnostic correction accurately.
- A small new pure diagnostic module/test is allowed only if it avoids duplicating the shared allowlist contract. No unrelated refactor.

**Interfaces:**
- Consume existing `createManualRefundSession({runtime,cli,state}, dependencies)` and `runManualRefund(options, limits, deps)`; preserve their financial capabilities and the state methods.
- Produce a strictly validated optional finite diagnostic projection on new reports. The worker chooses the smallest allowlisted stage/reason/probe shape consistent with existing code and documents it. It must survive session close and cannot overwrite proof booleans or original failure.

- [ ] Read the session, controller, runtime verification boundary, report schema/types, and their synthetic tests; trace the request/acknowledgement/observation/confirmation/cleanup chain. Do not treat a guessed race as the proven cause of the historical attempt.
- [ ] Before product edits, send ROOT the concrete hypothesis, behavioral reproduction, and exact RED command/output. At minimum cover pending -> completed refund, delayed callback after webhook success, a detail/list read straddling a valid transition, hard contradiction, and exhaustion/abort. Use fake time and synthetic API snapshots, not sleeps or network services.
- [ ] Correct only observer defects actually reproduced. If a fix needs broader authority or relaxation of an integrity constraint, stop and explain. Otherwise add precise safe failure-stage/reason diagnostics with no sensitive values.
- [ ] Demonstrate RED -> GREEN for new diagnostic schema, output/canary/getter safety, and each behavioral correction; keep original strict negative cases. Assert no duplicate POST/rotation, no unconfirmed remainder, and no renewed budget.
- [ ] Run focused and related refund/controller/CLI regressions on final candidate; ROOT runs the full safe infrastructure checkpoint after task review. Preserve existing Docker-mutating test exclusions.
- [ ] Self-review and freeze the candidate. Save the implementation report in this plan's SDD workspace with changed-file hashes, RED/GREEN commands/counts, limitations, and distinction between reproduced code defects and unknown historical trigger. Do not commit or spawn other agents.
- [ ] Independent task review (spec + quality), fixing findings through the original implementer. Then fresh full safe infrastructure verification and a final patch review; no actual Sandbox test.

## ROOT preflight and decisions

This is one tightly coupled observer/diagnostic correction, not independent runtime or backend work. No new user-facing product choices are needed. Historical read-only evidence established refund EUR700 SUCCEEDED, PROVIDER_EVENT journal success, callback HTTP204 with secret version2, and no EUR1300 dispatch; it does not identify the exact branch that rejected the run. Neither worker nor tests may read the retained artifacts or turn that evidence into a confirmed historical harness report.
