# Manual Pay Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user click one prepared test Pay button in visible Chrome while the existing owned first-payment and first-renewal scenario retains all server checks.

**Architecture:** Add an optional manual handoff to the existing browser runner and select it only from a dedicated Playwright config. The same owner, backend bridge, final proof and reporter remain in use.

**Tech Stack:** TypeScript, Playwright, Node test runner, Chrome.

**Spec:** [manual Pay handoff](../specs/2026-11-04-first-renewal-manual-pay.md)

## Global Constraints

- No Docker, Stripe, new payments, refunds or old DISPATCHING operations during implementation.
- The ordinary unattended scenario, one-shot limits, timeout bounds and cleanup remain intact.
- No new dependency or raw browser output; default discovery excludes the dedicated config.

## Review Focus

- No operator click must end with a safe diagnostic and no CONFIRM dispatch.
- A manual click must run the original confirm bridge exactly once.
- A quick terminal state after click must still be observed.
- Abort/navigation must not grant a second click or revive the attempt.
- The dedicated config must never enter default CI discovery.

### Task 1: Bounded manual Pay handoff

**Files:** `examples/demo-site/tests/support/stripe-billing-first-payment.ts`, its inert and hosted tests, browser diagnostic allowlist.

- [ ] Add failing inert tests for manual click success, no-click timeout and no auto click.
- [ ] Run the focused tests and confirm the expected failure.
- [ ] Add a `manualPay?: true` runner option. Keep the existing path untouched; manual mode waits at most three minutes for CONFIRMING/OBSERVE/STOPPED, then uses the unchanged OBSERVE reader.
- [ ] Run focused tests, hosted synthetic browser test and typecheck.
- [ ] Obtain independent review of this task.

### Task 2: Dedicated visible Chrome entry

**Files:** `examples/demo-site/playwright.stripe-billing-first-renewal-manual.config.ts`, `examples/demo-site/tests/stripe-billing-first-renewal.spec.ts`, config tests, default Playwright config, `docs/local-development.md`.

- [ ] Add a failing config/discovery test for visible installed Chrome, one worker, zero retries and no recording.
- [ ] Run the test and confirm the expected failure.
- [ ] Add the dedicated config and select manual mode only from its exact project name; keep the existing spec and owner.
- [ ] Verify default discovery exclusion, focused tests, typecheck and full related offline regression.
- [ ] Obtain independent review; commit, push and check CI. Do not run the manual Sandbox mode.
