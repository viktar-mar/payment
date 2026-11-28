# Billing time-domain implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Continue through implementation, independent reviews, publication and exact-head hosted CI without intermediate user approvals.

**Goal:** Prepare a proven UTC delivery/audit boundary for future owned Billing clocks, without enabling or executing them.

**Architecture:** Preserve Clock and Fake behavior; add an exact-owner billing-time port with a default identity adapter. Separate lifecycle decisions and callback occurrence from operational timestamps. Activate only in synthetic tests.

**Tech Stack:** Existing Java25/Spring/JUnit/Maven/PostgreSQL tests; no new dependencies.

**Spec:** [Billing time domains](../specs/2026-10-29-billing-time-domains.md).

## Global Constraints

- No local Docker, SQL/DB, Testcontainers, Spring/full application, browser, provider/Sandbox, financial operations, Python, installations or private key/archive/recovery reads.
- Pure exact-class JUnit only after auditing class/parents/static setup; cached offline Maven may compile all tests but must never discover broad suites. Hosted CI performs application/SQL/Fake/browser verification.
- Default production/Stage4 unchanged; no new native clock activation or public configuration. Preserve fingerprints, migration history, proof guards and old operational records. Initial native runner stays INITIAL_NON_CLOCK; native NOT_RUN/full5B NOT_ACCEPTED.
- One source author at a time; independent review per task plus final broad review. Root handles Git; preserve three pre-existing untracked paths and evidence. Ordinary commits/push/hosted CI authorized; no merge or force push.

## Review Focus

- Future billing time leaks into outbox eligibility, leases, audit or security; offset-time SQL/signature/retry assertions.
- Computed access disagrees with event/payload time; full access equality, exact occurrence and malicious mismatch tests.
- Mixed clocks silently reach global list/sweep queries; mandatory explicit guard, exact-owner positive and foreign-owner negative tests.
- Existing Fake clock semantics or immutable provider proof changes; untouched launcher/fingerprints and full hosted regressions.
- Pure tests accidentally launch local services or authored tests falsely counted as executed; audit test selection, hosted integration receipts and honest acceptance docs.

### Task 1: UTC callback enqueue with explicit billing snapshot

Modify SubscriptionCallbackPort documentation and SubscriptionCallbackPersistenceAdapter. Add a pure isolated adapter unit test for future billing access/state/occurrence versus UTC enqueue; wrong access/occurrence and ownership reject before SQL. Audit the exact class before running cached `./mvnw -o -Dtest=<exact-new-unit-class> test`. Establish meaningful RED with existing implementation, then GREEN. Add hosted integration coverage to SubscriptionCallbackIntegrationTest or a focused sibling proving immediate UTC eligibility, retry/lease and UTC signing. Do not run its Spring/Testcontainers fixture locally. Existing four-argument callers and Stage4 behavior stay compatible. Save report, test outputs, source hashes and diff; independent task review then root commit.

### Task 2: Scoped lifecycle billing time and synthetic paid/renewal/grace proof

Add SubscriptionBillingTimePort and ordinary identity adapter, explicit constructor wiring with compatibility only where required by existing tests. Mandatory port guard rejects unscoped list/global deadline selection under a synthetic override. Change only FactService, exact QueryService get/access and DeadlineWorker exact expiry; preserve UTC fence/audit/receipt/enqueue. Add confirmPaid/renewalFailed dual-time overloads; ordinary signatures delegate equal times. New test-only scope is exact account/Site/Customer, no runtime registration.

Write pure domain/service tests first, observe meaningful RED using audited exact classes, implement and run focused pure regressions plus offline test compilation. Hosted integration exercises real application/SQL paid period → failed renewal → grace expiry, matching and foreign scope, UTC callback claim/retry/signature, unchanged security lifetime and unscoped refusal. Keep default Stage4 tests unchanged. No native provider semantics or caller-controlled clock context.

Update acceptance/local development docs: record completed 4b604b8 / CI40877692992, preserve prior failed history, distinguish newly prepared time separation from unimplemented native provenance and native NOT_RUN. Save author report/hash/diff; independent task and final review. Root commits/pushes, verifies exact-head hosted CI and repairs only observed failures within the same runtime constraints.

Task2 clarification: the scoped port returns a typed sample with billing instant and explicit isolated-vs-ordinary mode. Isolated mode is read/projection/expiry only for ordinary unchanged-term INITIAL/RENEWAL facts; reject trial/change/cancellation/resume/unsupported observation shapes before writes and suppress financial actions in the view. Do not infer mode by comparing instants. Required unscoped guard executes before list/expiredGrace SQL. This is a bounded prerequisite, not silent partial support for other mutations.
