# Initial subscription CREATE failure evidence correction

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute both tasks continuously with independent review, within the user's offline scope.

**Goal:** Preserve a safe actionable failure boundary from the original prepare/CREATE flow instead of losing it as a generic refusal, without repeating or reclassifying uncertain financial work.
**Architecture:** Fixed prepare checkpoints in the existing dedicated Java scenario/Handler; bounded closed-enum error decoding in the existing Node client/owner; one sanitized final diagnostic line through the dedicated reporter. No recovery, extra requests, generic runner or diagnostic framework.
**Tech Stack:** Existing Java25/JUnit, Node mjs/node:test, existing TypeScript pure support tests.
**Spec:** docs/superpowers/specs/2026-10-30-billing-first-payment-scenario.md and current user request: offline diagnosis/fix, no Docker/Sandbox or retry/new financial operations.

## Global Constraints

No Docker CLI, Stripe/network/Sandbox/provider calls, application/DB/SQL/browser/context startup, Python/install. No private env/issuer/CLI, raw operational logs, archives or old financial state access. Read-only sanitized report/ledger and metadata for run 1fdcf26f-0d55-4e45-b0a6-c7d603144637 only; do not change any of its artifacts. Existing DISPATCHING is uncertain and permanently non-retryable. No new financial calls/reads, retries, endpoint, polling, observations, budget or deadline changes. No guard weakening. Reports contain fixed codes only: no exception messages/types/causes, HTTP bodies/statuses, identifiers, secrets, tokens or keys. No new dependency or exported runtime class; keep exact exporter inventory. Use explicit audited inert Node and standalone JUnit tests; cached offline Maven test-compile only, never Maven test/verify or broad class discovery. Preserve reports. Local reviewed commits are allowed; no push/CI in this task.

## Evidence and limits

At published e4f3863 with green CI41068509981 the native run reached LIVE, fresh fixture and OTP/customer ACK. billing-017-create-dispatch has no CREATE ACK and no confirm dispatch. Its timestamp precedes process close by20.2seconds including cleanup. Source/runtime private client schema, source/account config, token propagation and clipped deadline checks showed no deterministic mismatch. Backend prepare exceptions, private Handler errors, Node client and owner all discard the internal failure boundary. The actual native cause and whether a provider subscription was created cannot be inferred; only the loss of diagnostics is reproduced and corrected here. No native acceptance claim.

## Review Focus

- Permanent first failure and failure stage survive abort, duplicates and cleanup; no downstream effect after failure.
- Only authenticated exact prepare error responses may carry a fixed checkpoint; confirm/observe/auth errors retain their existing protocol.
- Provider checkpoint names describe the boundary only; UNKNOWN/lost replies are never presented as proof that a POST occurred or money moved.
- Node trusts only its internally created error metadata and exact bounded allowlisted DTOs; hostile messages/accessors/extra fields cannot leak or grant retry.
- The final dedicated reporter emits one fixed diagnostic after close, including cleanup confirmation, and never arbitrary stdout or raw error bodies.

### Task 1: Retain closed prepare failure checkpoints in the dedicated Java runtime

**Files:** OwnedBillingFirstPaymentScenario.java, OwnedBillingFirstPaymentRuntime.java and existing OwnedBillingFirstPaymentRuntimeTest.java under backend/src/test/java/dev/genericpayments/acceptance/runtime/.
**Interfaces:** Add default String prepareFailureStage() to Operations (fallback UNCLASSIFIED); Scenario overrides with its first latched failure stage. Handler's authenticated prepare failure DTO is exactly {code:INITIAL_PAYMENT_STOPPED,stage:<allowlisted stage>}; successful DTOs, other route errors and public production APIs unchanged.
**Stage values:** REQUEST, PREFLIGHT, CLOCK_ADMISSION, CLOCK_CREATE, CUSTOMER_CREATE, LOCAL_RESERVE, SUBSCRIPTION_CREATE, LOCAL_RECORD, RECEIPT, ACTION_SELECT, SESSION_SELECT, RESPONSE_BUILD, UNCLASSIFIED. Put the closed set in the existing Runtime class; no enum/new nested/exported class. Checkpoints immediately precede corresponding existing code. Latch the first failed checkpoint before abort; default/unknown/throwing stage providers sanitize to UNCLASSIFIED. No dependency exception inspection.

- [ ] Read relevant full code and audit pure test class. Write failing inert JUnit tests against actual scenario/Handler with in-memory HttpExchange and failing/fake bean ports: request/preflight failure, latched stage across duplicate prepare/abort, no downstream effects, malicious exceptions/stage fallback, unauthorized body not read and old confirm/observe errors unchanged. Use existing libraries only; do not initialize Spring/application classes.
- [ ] Compile using cached offline Maven test-compile with skipTests. Run only exact standalone JUnit class selector and retain RED evidence before implementation. Locate existing cached launcher/classpath; no install/network.
- [ ] Implement fixed checkpoints and exact safe prepare error envelope; retain old STOPPED/no-retry logic and exact runtime inventory.
- [ ] Run covering pure JUnit class to GREEN and compile/packaging inventory static checks. No shell exporter execution is required here. Write task report with commands/output/limits; no commit before review.

### Task 2: Carry the safe checkpoint into the Node owner and final sanitized report

**Files:** infrastructure/stripe-billing-first-payment-runtime.mjs, stripe-billing-first-payment.mjs/.d.mts, stripe-billing-first-payment.test.mjs; examples/demo-site/tests/stripe-billing-first-payment.spec.ts and tests/support/stripe-billing-first-payment-reporter.ts plus existing stripe-billing-first-payment.test.ts; docs/local-development.md. Avoid new modules/frameworks; update existing inert-module import inventories only if actually required.
**Interfaces:** Runtime exports an own-error metadata lookup; external rejection objects are never inspected. FirstPaymentResult adds diagnostic:string|null containing a closed fixed code. Existing code/phase and acceptance fields remain authoritative; diagnostic cannot change financial state. Formatter/parser share the fixed Node allowlist where practical. After owner.close(), spec emits one fixed diagnostic line for a rejected run; dedicated reporter validates every field and forwards at most one such line. Emit only code and cleanup enum, no run/source/local/provider IDs.
**Diagnostic codes:** PREPARE_AUTHORITY (bootstrap/callback admission), PREPARE_REQUEST (transport or request cancellation/uncertainty), PREPARE_HTTP (non-success without a validated server DTO), PREPARE_RESPONSE (bounded decoding/schema/response rejection), PREPARE_SERVER_<Task1 stage> for exact status400 fixed server error DTO, PREPARE_ACK for owner identity/ACK rejection. No raw transport cause or numeric HTTP status is reported. Preserve the first fixed code; unknown/dependency failures retain generic failure. Non-prepare paths retain existing semantics.

- [ ] Audit exact inert test bodies. First add failing tests for each boundary and Java-compatible valid/invalid prepare failure DTO, oversized/malformed/extra-field/secret-bearing bodies, abort/lost reply, admission denial, original key and one-dispatch only, owner retaining diagnostic even when browser catches, evolving fixture resources from no customer to acknowledged customer, no retry/late ACK, and sanitized reporter rejecting injected text/identifiers while emitting once. Preserve existing regressions.
- [ ] Implement decoding within the existing16KiB/body/deadline bounds; no additional fetch/read/retry or financial call. Keep successful schema strict; add no diagnostic route. Mark state consumed before dispatch as before. Old DISPATCHING artifacts must not be read/edited.
- [ ] Implement safe final report handoff only after owner close. Do not expose private metadata to the browser or its DOM. Update result types and add qualified documentation (root native cause unknown; creation outcome uncertain; original run must not retry).
- [ ] Run focused RED/GREEN tests, audited related explicit inert suites and existing cached demo typecheck only. Do not run Playwright/native discovery/browser or broad project tests. Write task report with commands/output, no commit before review.
