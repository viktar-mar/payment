# CI test construction and V34 contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute without intermediate approval stops.

**Goal:** Repair the two confirmed regression causes in GitHub CI, then publish and verify the exact fixed source.

**Architecture:** Test-only deterministic selection of the existing ordinary SubscriptionFactService constructor; preserve all production constructors, deny-by-default recovery proof and Spring wiring. Explicit schema version contract includes the shipped V34 without deriving the expected list from the database.

**Tech Stack:** Existing Java25/JUnit/cached dependencies, GitHub Actions. No installs.

**Spec:** Approved user request: fix constructor selection in tests and V34 expected migrations, with subagents/tests/independent review, push and CI. Baseline db34cd50317103fb5a6f358523116e6758f39811; CI42114822704 failed4913 tests (9 failures,57 errors,1skip), while new recovery hosted10/10 and dedicated renewal379/379 passed.

## Global Constraints

- No local Docker/daemon/container, Spring/database/SQL, Sandbox/Stripe calls, Python, installation, keys/archive or historical operation reads. Old operations/DISPATCHING untouched.
- Existing inert tests may use their bounded loopback-only HTTP fixture after source closure audit; never a real provider. Hosted Spring/SQL tests compile only locally; full execution belongs to GitHub Actions.
- No production/migration source edits, proof-port bean registration, ordinary-time fallback changes, financial assertion weakening, skip/timeout/filter changes or automatic replacement of expected schema versions with observed database values.
- Reuse current isolated branch/worktree, root owns Git writes. Preserve worktree/evidence. Push/CI explicitly authorized.

## Review Focus

- Constructor selection must not depend on reflection order, index, or largest arity; new privileged proof overload must not enter old fixtures.
- Ordinary billing time must be concrete/default, never an unstubbed mock returning null. The default recovery denial remains binding.
- All three confirmed consumers must be fixed, including hosted context reconstruction without registering the new proof port.
- V34 is added to the exact ordered expected schema contract; migration preservation assertions remain unchanged.
- Tests must exercise behavior/regression, and local evidence must not imply hosted SQL passed before actual CI.

### Task 1: Repair test composition and explicit schema version contract

**Files:** Modify backend/src/test/java/dev/genericpayments/platform/subscriptions/application/service/StripeBillingObservationTest.java; backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeSubscriptionCancellationTest.java; backend/src/test/java/dev/genericpayments/platform/subscriptions/application/service/SubscriptionFactsIntegrationTest.java; backend/src/test/java/dev/genericpayments/platform/support/CurrentSchemaVersions.java. Add focused test-only constructor helper/regression tests in existing support packages as justified; no production source changes.

**Interfaces:** Choose the existing21-argument ordinary constructor by its exact parameter signature (test-only shared helper allowed); it already supplies concrete ordinary billing time and denied recovery proof. Preserve existing dependent mocks and assertions. Schema EXPECTED remains an explicit literal ordered list through34. A pure test may compare this independent contract with shipped migration resource filenames, never executeSQL or derive EXPECTED dynamically.

- [x] Step1: Audit selected test closure; reproduce constructor failure using existing pure observation tests and write meaningful regression for schema contract, observe RED before fix.
- [x] Step2: Implement deterministic test construction for all three consumers and append34 to explicit expected schema list, preserving all prior assertions. Add regression proving ordinary construction/default denial if needed, no implementation-mirroring tests.
- [x] Step3: Run targeted audited pure/loopback tests, compile hosted changed test only with cached dependencies, git diff check; independent task review then root commit.

### Publication and final verification

- [x] Broad final source review for the combined small task (may share the single task review when it covers the entire change); root verifies evidence and commits.
- [ ] Push current branch, wait for both workflows on exact new HEAD, inspect all four job conclusions and new recovery SQL test summary. Investigate any new failure without weakening checks or running local Docker/Sandbox.

## Scope check

Single test-maintenance batch: constructor fixes share one exact ordinary-construction boundary; schema contract is independent but included in the same small CI regression patch. Production remains read-only. Same independent reviewer checks each cause and whole diff, avoiding duplicate reviews of identical scope.
