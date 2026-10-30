# First-payment Compose configuration correction

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute continuously within the user's offline scope.

**Goal:** Correct the first-payment configuration proof's rejection of inherited image defaults and prove explicit command overrides remain forbidden.
**Architecture:** Preserve existing first-payment bootstrap and image/container identity proof. Only the representation of absent command/entrypoint changes: undefined or null inherit the verified image; all explicit values remain invalid.
**Tech Stack:** Existing Node.js mjs and node:test, injected inert dependencies.
**Spec:** docs/superpowers/specs/2026-10-30-billing-first-payment-scenario.md plus the user's current offline bug-fix request.

## Global Constraints

No Docker commands, Sandbox, Stripe, network, Python, installation, local application/browser/DB/SQL or financial operations. No private env/issuer/CLI/archives or old payment access. Preserve native evidence. Audited explicit inert Node tests only. Existing source/image/runtime-env/ports/mounts/ownership checks and first-failure/no-retry policy remain intact. No generic diagnostics or new framework. Native acceptance remains NOT_ACCEPTED. One local commit after review; no push in this task.

## Evidence and limits

Native run 74daec39-1ae3-4247-b450-7afbb3520911 at 66d3dbc reached PREPARE after issuer setup, before CREATE_STOPPED. Its sanitized report does not distinguish configProof from subsequent sourceGate. The deterministic defect is that configProof rejects null command/entrypoint while the inert fixture omits these fields. Reproducing and fixing this defect is not evidence of successful native execution or proof it was the only cause.

## Review Focus

- Both null and absent fields must retain image defaults.
- Empty strings/arrays are explicit overrides, not inheritance.
- Nonempty strings/arrays, booleans, numbers and objects must still fail before CREATE_STOPPED.
- Exact source, Dockerfile/args, actual image launcher, environment and seventh loopback port guards must survive.
- Tests must run against the real bootstrap with inert filesystem/process/network dependencies and never consult private files.

### Task 1: Correct inherited command representation with regressions

**Files:** Modify infrastructure/stripe-sandbox-bootstrap.mjs and infrastructure/stripe-sandbox-bootstrap.test.mjs. Append a short evidence-qualified note to docs/local-development.md's prepared first-payment section. Do not restructure unrelated code.
**Interfaces:** Existing createOwnedBillingFirstPaymentBootstrap(options, dependencies); no API changes.

- [ ] Read the relevant bootstrap and fixture completely; audit the test's process/network/private-file traps before running it.
- [ ] Update the inert first-payment fixture to represent both command and entrypoint as null. Add tests for null, missing fields and combinations; assert prepare succeeds through stopped-container creation, without actual processes.
- [ ] Add rejection tests for each field's explicit empty/nonempty strings and arrays, false, zero and objects; assert no CREATE_STOPPED dispatch, source/image/runtime/ports checks unchanged. At least one narrow guarded source/build negative case must remain covered.
- [ ] Run the targeted first-payment tests before code changes; save RED output demonstrating PREPARE_PENDING from null defaults.
- [ ] Change only the two presence predicates to accept exactly undefined or null. No broad falsy check, truthiness defaulting, relaxed command allowlist or removed proof.
- [ ] Run targeted tests to GREEN, then the entire guarded bootstrap test file and related first-payment owner/evidence/ownership/owned admission tests after audit. Use explicit files, no discovery wildcard; do not invoke Playwright or Maven.
- [ ] Add documentation stating the corrected semantic boundary and that the prior native attempt stopped before Stripe; exact native cause and acceptance remain unconfirmed.
- [ ] Write the report with RED/GREEN commands/output, coverage, limitations and changed files. Do not commit until the controller completes independent review.
