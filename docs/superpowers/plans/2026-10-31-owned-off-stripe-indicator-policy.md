# Owned initial unavailable off-Stripe indicator policy

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. One coupled task, TDD, independent task/final reviews.

**Goal:** Accept unavailable off-Stripe indicator only in typed owned positive INITIAL actual OPEN or paid/full-CASH graphs, under the existing independent unpaid/full-card-cash proof; never invent a zero value.
**Architecture:** One internal scoped predicate in shared Read.invoice/plainZeros; all action/material/preconfirm and final owned snapshot paths agree. Ordinary/noAdjustments/zero/void/draft/uncollectible/mutation paths stay strict. Full capture and durable authority remain compulsory.
**Tech Stack:** Java25, stripe-java34.0.0/API2026-09-30.endive; cached javac/Jupiter6 with explicit inert selectors.
**Spec:** docs/superpowers/specs/2026-10-29-billing-clock-subscriptions.md and docs/superpowers/specs/2026-10-29-billing-clock-confirmation.md. This task explicitly revises only the off-Stripe indicator evidence requirement for the bounded owned initial scope below.

## Evidence and deliberate policy

Native d6dbc099 at87b55db stopped PAID_OFF_STRIPE before confirm. Safe report distinguishes neither missing/null/nonzero nor native provider outcome; CREATE remains DISPATCHING without client ACK. Cached selected SDK binary has @SerializedName("amount_paid_off_stripe") Long and matching API constant; no source/schema is available offline. Nullable Java representation proves neither API omission semantics nor null=zero. Do not claim a Stripe-contract defect, provider-valid omission or resolved native cause.

Independent architecture gate conditionally approves an explicit application policy: exact owned unpaid graph before action; exact fully captured gross card cash before paid/access projection. An unavailable side indicator remains unknown. This changes the evidence requirement and cannot prove the stronger proposition that off-Stripe funding was zero or rule out additional outside funding. Full card capture still proves actual gross card cash. This is not a global nullable-money normalization or an OPEN-only partial fix.

## Global Constraints

- Existing isolated codex/stripe-billing-5b worktree, base87b55db41b65cadfa49370f62604d7306da1ca7d. No Docker/daemon/Stripe/Sandbox/network, financial operations, DB/SQL/Spring/context/server/listener/browser, Python, installs, private/env/key/CLI/raw-native/project archive access. Static selected cached public SDK artifacts allowed.
- Never replay/reset/adopt/reconcile CREATE/DISPATCHING. Preserve old reports/containers/volumes/data; no cleanup. No push/CI/native run this task. Root may make one reviewed local commit.
- Eligible allowance requires Read.original != null, INITIAL kind, positive due, actual invoice status exactly open or paid. Typed receipt lineage/owner/customer/clock/original item/period/price/terms and all existing surrounding checks remain required. No new public boolean bypass, metadata authorization, diagnostic-dependent policy or protocol.
- Eligible indicator accepts exact Long zero or unavailable/null getter. Supplied positive/negative values refuse. No fabricated numeric zero, no raw-presence parser. Ordinary/non-original, noAdjustments, mutation/proration/recovery/schedule, ZERO/VOID/draft/uncollectible and unsupported states retain explicit-zero behavior.
- Other five zero-money fields, required empty adjustment lists, exact totals/subtotal/line, allocation/PI/Charge/reverse proof, sandbox/card/currency/customer/amount/paid timestamp, authority/fences/session/root/attempt/one-confirm budgets remain strict. Do not change capture or accept paid status alone. CASH PI identification remains the existing unique allocation proof, not a newly claimed equality to the confirmation-root PI.
- Shared policy applies to action selection, material read, preconfirm and owned OPEN/CASH observation. Never allow only selection or only OPEN and strand subsequent phases.
- Keep six original scalar getter arguments eager and ordered before helper short circuits. A pure cached invoice status for scope is allowed and should be reused by switch (no extra provider reads); document any deliberate getter scheduling change. Original first-failure provenance remains: known nonzero wins; accepted unavailable indicator allows the next real predicate reason. No new diagnostic vocabulary/transport needed.
- Only fresh selected javac -proc:none --release25 compilation and audited explicit pure Jupiter selectors with all auto-registration disabled. No Maven test/verify or package/wildcard discovery. Compile/test only synthetic values/inert official SDK transport; no native test fixtures. Existing cached target may be fallback for unchanged transitive classes, never proof of full fresh backend build.

## Review Focus

- Scope cannot admit ordinary or wrong kind/status/zero due, and cannot depend on actionFailure diagnostics.
- Unavailable indicator plus partial/foreign/manual/non-card/uncaptured/split/reverse-invalid money must never become CASH or issue a usable unauthorized action.
- Known nonzero must refuse even alongside complete capture; other nullable money/lists remain strict.
- Full lifecycle must work under omitted/null/zero and preserve existing flat-line representation/authority contracts; no new financial route or retry.
- Docs must explicitly supersede prior "nullable money unchanged" for this bounded indicator only and disclose unknown off-Stripe funding semantics/native value.

## Task 1: Implement and prove bounded owned indicator compatibility

**Files:**
- Modify backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingSnapshotMapper.java only for production; keep zeros and StripeBillingMutationAdapter.noAdjustments unchanged.
- Modify existing pure tests StripeBillingOwnedConfirmationSdkTest.java, StripeBillingClockSnapshotTest.java and optionally same-package StripeBillingSettlementEvaluationTest.java; fixture helper only if small and reused. No new test framework/class unless justified to root.
- Clarify both specs named above and docs/local-development.md. No JS/runtime/protocol/config/dependency/schema changes.

- [ ] Read copied recon/design notes and audit complete inert helper/test closures. Use cached JDK25 at /Users/viktar.marozau/.sdkman/candidates/java/25-tem/bin and explicit artifact list. Prior /private/tmp/payment-invoice-settlement-task1/run.sh may be read as command reference only, never executed/overwritten; create fresh current task runner/output. Do not read/write other plans' SDD workspaces.
- [ ] Add genuine behavioral RED before production changes. Five field states omitted/null/zero/positive/negative across typed owned actual OPEN and paid/full CASH, ordinary OPEN/CASH; only eligible owned omitted/null newly pass. Retain positive/negative rejection attacks, replacing old missing/null refusal rows with meaningful positive+protected-scope assertions rather than deleting coverage.
- [ ] Under omitted/null/zero and prior flat exact/omitted/null combinations, exercise real owned action -> same binding/root -> material -> durable preconfirm -> exactly one synthetic confirm -> independent paid CASH snapshot. Assert literal amount/currency/cash identity and GET/POST sequence/count, one lifetime root/no regeneration. No real provider dispatch; no claim that confirm alone projects money.
- [ ] CASH with omitted/null must reject missing/partial/uncaptured/foreign/non-card Charge, nonsucceeded/partial/manual PI, missing/split/foreign allocations, duplicate/foreign reverse allocation, missing paid_at and wrong currency/customer/mode/amount. Present nonzero rejects even with complete capture. Existing cash negative matrix should run under both omitted/null states; no weaker assertions.
- [ ] Unavailable indicator cannot mask other five money missing/null/nonzero, required-list absence/nonempty, total/subtotal/line mismatch, malformed allocation, nested/clock/item/price/period/receipt drift or stopped/expired/wrong durable authority. Failed action/material/preconfirm graphs yield no root/no confirmation POST as appropriate.
- [ ] Prove no new admission for draft/uncollectible/VOID/zero/trial/proration/nonoriginal. Test actual noAdjustments with otherwise complete synthetic Invoice: baseline zero passes and omitted/null/nonzero still refuses. Keep all other helper/caller behavior untouched.
- [ ] Verify known-nonzero+second error keeps PAID_OFF_STRIPE, eligible unavailable+bad ending balance reports ENDING_BALANCE, null/unknown status remains generic, six getter eager ordering and call-local first-failure/reentrant/concurrent isolation remain. Malformed present types use actual selected SDK; do not add parser or claim raw-type strictness beyond observed behavior. Escalate if malformed data collapses to an accepted unavailable value unexpectedly.
- [ ] Implement minimal internal policy; no extra GETs/provider calls, new authority or changed capture algorithm. Cached pure status scope and positive INITIAL eligibility must be easy to audit.
- [ ] Capture focused GREEN and one related full explicit pure Java set (three audited classes). Rebuild all changed/relevant billing production sources and selected current tests into fresh output before execution. Do not run unrelated Node/Admin/full-context tests for unchanged code. Preserve all failed/setup/RED/GREEN logs, exact runner/commands, hashes and coverage limits in this plan's workspace.
- [ ] Update specs/docs honestly: this is an application evidence-policy change, not proof of Stripe wire optionality/null=0/zero outside funding/native cause. Initial-payment/full5B remain NOT_ACCEPTED; current CREATE remains nonretryable. No new native permission.
- [ ] Self-review; save task-1-report.md with exact RED/GREEN and file hashes. No implementer commit/push/subagents. Root performs independent task/final reviews and staging proof before any local commit; preserve evidence/worktree.

## Completion

Both independent reviews pass with no unresolved Important/Critical findings; root verifies frozen bytes and recorded checks. Native cause/value, payment outcome and actual-provider acceptance remain unproved. The final user-facing result must disclose the chosen changed evidence requirement and its limit regarding unknown external funding. No native attempt or publication this task.
