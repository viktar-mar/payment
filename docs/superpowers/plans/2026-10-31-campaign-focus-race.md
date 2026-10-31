# Campaign creation focus race implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. One coupled task, TDD, independent task review and final current-change review.

**Goal:** Restore focus reliably to the campaign creation trigger after a saved or conflict-refreshed form unmounts, without relaxing accessibility or intent-safety checks.
**Architecture:** The parent list owns its trigger and a one-shot focus-restoration intent; restore during the React commit effect after the creation form is removed. Both save and conflict refresh request that restoration. The existing form retains error announcement/focus and mutation idempotency behavior. No new abstraction or dependency.
**Tech Stack:** Existing React19.3 / TypeScript / Vitest4 / jsdom27 / Testing Library; cached installed dependencies only.
**Spec:** User's offline bugfix request; docs/superpowers/specs/2026-10-10-customer-access-donations-design.md sections19.3/accessibility verification; existing CampaignPages exact focus and fresh-intent assertions remain binding.

## Evidence

CI41196816490 at4dd2850 failed CampaignPages.test.tsx:59: expected Create campaign focus, got body. Read-only recon reproduced the actual race with the current source: a MutationObserver invokes Refresh when conflict DOM becomes available, before the form's pending passive error-focus effect. Trace: callback focuses trigger -> pending error effect focuses alert -> form deletion leaves body focused. Original six-test focused baseline passed locally, so passing that baseline alone does not exclude the race. The test also waits on GET dispatch, not completed refresh; regression must observe the real UI result.

## Global Constraints

- Work in existing isolated codex/stripe-billing-5b worktree at base4dd2850d9580589345a66e172b6696f65652c598. Preserve all historical reports, native records, containers, volumes, financial operations and DISPATCHING.
- Offline only: no network/GitHub/push/CI, Docker/daemon, app/server/browser, DB/SQL, Stripe/Sandbox, Python, private env/key/archive/native-log reads, install or financial operation. No repeat/reset/adoption/reconciliation of DISPATCHING.
- Source scope: CampaignList.tsx and CampaignPages.test.tsx only, plus this plan. No changes to CampaignForm error-focus, shared mutation hook, API, tests/config/timeouts/dependencies or unrelated UI.
- Keep exact focus/blocked-intent/refresh/new-empty-form assertions. No skip/todo, retry, sleep, timer-delay workaround, test-only product flag, mocked React effects, suppressed assertion/warning, or broader timeout. No focus on initial mount or unrelated list refresh; restoration is consumed once per requested form close.
- Existing cached primary-checkout dependencies may be read through a symlink from an isolated /private/tmp source mirror, since current admin/node_modules is absent. Copy current worktree Admin sources/configs; never run or modify primary source. Preserve source-hash mapping so evidence refers to this exact patch. Do not install.
- Root may make one local commit only after reviewed verification. No push/CI/native run in this task. Retain task evidence/workspace rather than delete it.

## Review Focus

- Immediate user action as conflict DOM appears must not let pending alert focus win after form removal.
- Save and conflict-refresh both restore the same real trigger; fresh initial mount/open does not steal focus.
- Later list load completion must not refocus away from a user's newly selected control.
- Refresh continues to close/reset blocked intent; a new empty creation form is enabled without retrying the failed request.
- Deterministic regression exercises real components/effects/DOM with only external fetch replaced, and releases observers/globals even on failure.

## Task 1: Commit-safe creation trigger restoration and regression

**Files:** Modify frontend/admin/src/components/donations/campaigns/CampaignList.tsx and CampaignPages.test.tsx. Existing hooks/forms/fixtures remain unchanged.
**Interface:** CampaignList({siteId:string}) and CampaignForm onSaved/onRefresh signatures and behavior remain unchanged. Use parent-owned one-shot request and a commit effect after creating becomes false; no initial-mount autofocus. Choose layout effect for synchronous completion after removal and before paint.

- [ ] Audit full focused test, transitive real UI/API/mutation hooks and suite setup for side effects. Reuse cached installed dependencies through a scratch mirror; capture commands/versions/source hashes.
- [ ] Add deterministic immediate-conflict regression first. Use DOM availability observation to exercise Refresh before pending passive error focus completes (no sleeps or React mocks). Assert form removed, exact trigger focused, conflict submission not repeated, fresh creation starts enabled and empty. Record expected assertion RED against unchanged product source.
- [ ] Add meaningful initial no-focus-steal / successful-unmount / focus-intent-consumed coverage using real DOM controls and controlled external responses. Keep all existing6tests and their exact focus/intent checks; any synchronization correction waits for actual UI conditions, never simply masks missing focus.
- [ ] Apply minimal parent-only one-shot post-unmount focus correction shared by save/refresh. Preserve pagination-reset behavior on save, list revision on refresh and existing hook ordering.
- [ ] Run focused CampaignPages GREEN and all Admin tests once on final source, plus Admin typecheck and lint. Full Admin suite is local jsdom with synthetic dependencies; audit before running, no servers/network/native. Meaningful failure reports include pre-existing failures/warnings; do not silently exclude tests.
- [ ] Save RED/GREEN/suite/type/lint logs and final source hashes, causal explanation, changed paths, cleanup and self-review in task report. No commit or subagents by implementer. Root performs task review and final broad current-task review then local commit.

## Completion

Require independent task spec/quality PASS and final most-capable current-change review. Verify evidence/source hashes, whitespace and exact staged paths before local commit. Native initial/full5B acceptance remain unchanged/unproved. Report source behavior and local regression separately from hosted CI/native acceptance.
