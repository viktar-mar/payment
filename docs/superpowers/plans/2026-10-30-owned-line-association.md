# Owned invoice-line association repair implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute the single coupled task with TDD and independent task/final review.

**Goal:** Remove an unnecessary duplicate flat-reference presence requirement from typed owned invoice-line verification while retaining the full receipt-bound nested association and financial proof.
**Architecture:** Change only shared `StripeBillingSnapshotMapper.Read.invoice` so all owned action, preconfirm and observation paths use the same narrow representation rule. Ordinary reads still require the exact flat reference; every supplied foreign flat reference and incomplete/conflicting nested graph still rejects. Existing closed diagnostic transport remains unchanged.
**Tech Stack:** Java25, stripe-java34.0.0/API2026-09-30.endive, cached javac/Jupiter, in-memory official SDK HttpClient.
**Spec:** docs/superpowers/specs/2026-10-29-billing-clock-subscriptions.md and docs/superpowers/specs/2026-10-29-billing-clock-confirmation.md. Their full line-proof requirement is clarified narrowly in this task; runtime authority is unchanged.

## Evidence and design decision

Single native run c4135c73-e5b7-4c70-bb34-f206e0e17b8d at75c1344 stopped at PREPARE_SERVER_ACTION_GRAPH_LINE_ASSOCIATION. Its safe report identifies a compound guard, not an individual predicate. Its client CREATE journal remains DISPATCHING without ACK; no confirm marker. All seven current containers are independently proved stopped. This task reads only that safe report and public source/cache, never underlying private native artifacts.

Read-only recon establishes a source defect: an otherwise exact receipt-bound nested graph is refused solely when duplicate flat line.subscription is absent/null. SDK getters are independent. Cached SDK has no authoritative prose proving provider-valid omission or deprecation; do not invent either claim. Architecture review line_assoc_design_gate (gpt-6-astra/high) approves the representation allowance as an explicit owned application contract correction, preserving the complete ownership invariant. Native actual failed predicate and acceptance remain unestablished.

## Global Constraints

- Work only in existing isolated codex/stripe-billing-5b worktree at base75c134497a0b47e3f899159f5a091c9c1168a582. No Docker/daemon, network/Stripe/Sandbox, DB/SQL/context/server/browser, Python, install, private config/env/key/CLI/archive/raw native log access. No financial operation.
- Never replay/reset/adopt/reconcile CREATE or any DISPATCHING, including c4135c73 and all historical records. Preserve all reports/containers/volumes/data and worktree. No push/CI this turn; root may make one local commit only after review.
- One production change: shared Read.invoice flat reference condition accepts exact reference OR (typed original receipt present AND flat reference null). Present foreign/empty/malformed values must reject. No caller-controlled bypass, new entry point, register, schema, protocol, diagnostic label, parser/framework, provider read or retry/budget expansion.
- Mandatory nested subscription/type/item, line invoice/object/mode/quantity/proration, exact invoice parent/Customer/clock/account/original receipt/created/item/period/price/product, currency/amount/balance/allocation/PI/reverse/Charge/CASH and durable authority/fences remain unchanged. Nullable money remains rejected; no null-to-zero.
- Apply the same rule to owned action, confirmation material/preconfirm and OPEN/CASH observation through shared Read. Ordinary no-clock/reference/window/fingerprint behavior stays unchanged.
- Local execution only audited explicit pure Jupiter selectors and inert official SDK transport via cached javac -proc:none --release25, no default/package/wildcard discovery or Maven test/verify. Compile errors/setup errors do not count as RED. No integration/HTTP-loopback fixtures run locally.

## Review Focus

- Missing duplicate field must not become missing authoritative nested ownership; reject each independent nested/item/invoice/receipt drift even with flat omitted/null.
- Deserialized malformed-present flat data must not become accepted absence; test exact cached SDK forms, no new raw parser without root ruling.
- Positive prepare must not merely defer refusal until owned confirmation/observation; require successful same binding/proof and paid CASH reads across paths.
- Supplied conflicting flat reference remains rejection; ordinary path is not widened, including ordinary no-clock snapshots.
- First-failure provenance, consumed authority/no dispatch on failed graph, all money/capture/reverse allocation proof and historical uncertainty remain intact.

## Task 1: Repair the complete owned line-association contract

**Files:**
- Modify backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingSnapshotMapper.java (one shared flat-reference predicate plus a precise comment).
- Tests in existing pure StripeBillingOwnedConfirmationSdkTest.java, StripeBillingClockSnapshotTest.java, StripeBillingClockSubscriptionSdkTest.java under backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/; optionally the existing inert owned fixture for small reusable value mutation only. No new test framework.
- Clarify docs/superpowers/specs/2026-10-29-billing-clock-subscriptions.md and docs/superpowers/specs/2026-10-29-billing-clock-confirmation.md; update docs/local-development.md guard meaning and current source-only repair limits. Do not rewrite historical reports.

**Interface:** Existing typed receipt is the only eligibility signal, never provider data or a new boolean. No public signature or closed diagnostic string changes. Produce same action binding/proof and snapshot values for exact/absent/null duplicate flat references when every authoritative proof matches.

- [ ] Audit exact selected test/fixture side effects before execution. Read complete relevant tests rather than infer safety from names. Do not run StripeBillingSnapshotMapperTest or StripeBillingConfirmationAdapterTest unless proved inert; they may use HTTP servers. Existing explicit pure selectors are preferred.
- [ ] Before production edits add true JSON regressions through inert official SDK. Owned exact/omitted/null flat references yield equivalent action binding/proof, successful action root, confirmation material/preconfirm and OPEN/CASH observation. Use deterministic existing synthetic IDs/values, never current native records.
- [ ] Add negative matrix with flat omitted/null: missing/null/foreign nested subscription and item; missing/null/wrong parent/type/details; missing/null/true proration; foreign invoice-parent/line-invoice/Customer/clock/receipt created/item/period/price/product, wrong quantity/mode/object; amount/currency/balance/allocation/default/requested/paid/PI/reverse/capture/Charge failures. Each must retain refusal/no root or no POST as appropriate. Existing tests may supply shared matrices if exercised under both omitted/null modes.
- [ ] Test foreign/empty flat string, expanded foreign, {}, id:null, numeric, boolean, array JSON against actual pinned deserializer. Malformed-present values must refuse. If SDK unexpectedly collapses malformed-present to null, report to root before changing design; do not add a raw parser speculatively.
- [ ] Ordinary null-clock snapshot with exact flat passes, missing/null/foreign flat rejects despite correct nested fields. Existing ordinary clock-owner refusal and v2 boundaries remain covered.
- [ ] Preserve guard provenance tests: replace missing/null flat rejection cases with explicit positive coverage; concurrent LINE_ASSOCIATION failure currently induced by missing flat must instead use foreign flat or missing nested, preserving its purpose. Never delete attacks to obtain green.
- [ ] Capture focused genuine assertion RED against unchanged75c1344 production, then implement the single predicate correction, then focused GREEN. Verify exact binding/snapshot/CASH outputs and bounded GETs, not merely no exception.
- [ ] Run one explicit related pure regression set after GREEN; add tests for stopped authority/consumed root/wrong durable attempt under allowed absence to prove no new dispatch/retry authority. Preserve test counts and exact selector audit.
- [ ] Clarify specs/docs: this is an intentional owned application representation rule, not a claim about SDK deprecation, provider-valid missing fields or exact native cause. Update prior absent/null line rejection description accurately; money-null checks unchanged; native initial/full5B remain NOT_ACCEPTED and all DISPATCHING protected.
- [ ] Save command/runner/RED/GREEN/hash/self-review evidence and report to this plan's ignored workspace, no commit/subagents. Root handles independent spec/quality review, any fixes, broad final review and one local commit. Preserve all evidence.

## Completion

Independent task review and most-capable final whole-current-change review must pass. Root verifies evidence/source hashes, whitespace and staged file scope before local commit. No push, CI or native operation. Report the proven source contract correction, actual offline tests and the unproved native cause/acceptance separately.
