# Customer concurrency fixture ordering implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. One test-only task with independent task and final current-change reviews; root publishes after both gates.

**Goal:** Make the existing one-original-customer concurrency test deterministic without changing production behavior or weakening assertions.
**Architecture:** The strict FIFO HTTP fixture remains unchanged. The two account response callbacks rendezvous after response selection and before returning JSON, so both GET slots are consumed before either caller can POST.
**Tech Stack:** Java25, existing stripe-java34.0.0, cached JUnit6.0.3/Mockito5.23.0 and local synthetic HTTP fixture; no dependencies.
**Spec:** Existing behavioral contract in backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeBillingCustomerAdapterTest.java::concurrentSubscriptionsShareOneOriginalCustomerKey; strict fixture semantics in backend/src/test/java/dev/genericpayments/platform/support/StripeHttpFixture.java. Parent billing contracts remain unchanged.

## Global Constraints

- Existing isolated codex/stripe-billing-5b worktree; base69b3cf6516fe542ad48dd0d6972a7b3d67fa9ff4. No production, shared fixture, workflow, dependency or financial-policy edits. Only the named customer test and this plan may change.
- No local Docker/daemon, Stripe/Sandbox/non-loopback network, financial operations, DB/SQL/application/Spring context/browser/Python/install/private env/key/raw-native/project archive access. Ordinary GitHub push/CI status/log access is reserved to root after review. Isolated cached-JDK test processes and this exact test-owned127.0.0.1 port0 HTTP fixture are permitted; no other listeners.
- Never replay/reset/adopt/reconcile CREATE/DISPATCHING; preserve every old/current report, archive, data, volume and worktree. Do not clean/delete evidence. No implementer commit/push/subagents. Root owns one reviewed commit and publication.
- Preserve every existing concurrency assertion: at least one cus_owned result; each result cus_owned or PROVIDER_RESULT_UNKNOWN; exactly one customer POST; exact original billing_customer account/site/customer key; customer/site metadata without email/name; exactly one operation for the original customer. Do not swallow SDK/runtime errors or relax FIFO routing, provider proof, claims, timeouts or retry policy.
- Tests only synthetic. Fresh explicit javac --release25 -proc:none -implicit:none with empty sourcepath and fresh output first. Runtime classpath uses only audited dependencies, fresh classes and staged named cached helpers; no backend/target/test-classes resources. Cached production/helper fallback is disclosed, not full fresh-build proof.
- JUnit pinned LauncherConfig actual five switches are enableTestEngineAutoRegistration, enablePostDiscoveryFilterAutoRegistration, enableLauncherSessionListenerAutoRegistration, enableLauncherDiscoveryListenerAutoRegistration and enableTestExecutionListenerAutoRegistration, all false. Explicit Jupiter engine only, enableImplicitConfigurationParameters(false), extensions-autodetection and parallel execution false. No Maven/default/package discovery or Playwright locally.

## Evidence and rulings

CI41243940229 at base failed only this concurrency test:4561run,0failures,1error,1skip; frontend/infrastructure passed. Original script GET GET POST permits a legal GET POST GET interleaving. Strict FIFO then returns empty500; SDK handleError throws responseBody-null NPE at create. The exact historical CI request order was not recorded. Source proof and a controlled prefix reproduction establish the defect without claiming that historical trace was observed.

Ruling: fix only test ordering with existing response callback overload; no production catch broadening or global route matching — the artificial FIFO constraint is the demonstrated defect — cost is test-only synchronization and no durable-store/native correctness claim.
Ruling: use captured failing CI as the genuine unchanged-test RED baseline, plus one deterministic pre-fix forced-prefix probe — test code is the deliverable; a permanent meta-test of its barrier would mirror setup — cost is explicitly limited historical source/route provenance, covered by real post-fix behavioral assertions and fresh CI.
Ruling: preserve evidence and review only this current fix, not the entire unfinished5B branch — user requested retention and continuous scoped work — cost is local evidence storage; native first payment/full5B remain NOT_ACCEPTED.

## Review Focus

- Account callbacks must run after both FIFO GET responses have been selected, before either successful verification can return. An entry barrier alone is insufficient; recorded calls order is not itself poll order.
- Coordination failure must fail the test, restore interrupts and release/cancel workers before executor close; never convert it to an allowed business result.
- Both reserve/claim contenders remain concurrent; do not serialize the adapter or permit extra POSTs. All original assertions remain and added counts may strengthen them.
- Existing sequential identity() behavior and shared HTTP fixture remain unchanged; no timeout increase, new sleep or hidden retry.
- Verification must name safe selectors/dependencies and actual launcher API; no full local Maven/Docker run or overclaim from cached support. Recon's proposed interceptor-auto-registration method does not exist in pinned6.0.3; use the five verified methods above.

### Task 1: Synchronize the two account responses in the existing customer race

**Files:** Modify backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeBillingCustomerAdapterTest.java. This root-created plan is the only other source/document change.
**Interfaces:** Consume existing StripeHttpFixture.enqueue(method,path,status,Function<Call,String>), two current-account responses and real adapter/memory ports. Produce deterministic test arrangement for GET GET POST with unchanged production API and assertions.

- [ ] Read own copied case/source/test recon. Audit safe closure and cached prerequisites; create fresh task-specific /private/tmp build/evidence. Read existing test helpers and writing-good-tests skill. Do not execute older scripts or access another SDD workspace.
- [ ] Before editing, run one bounded controlled baseline probe using the original GET GET POST script and only callerA's legal GET/POST prefix while callerB is held before entry. Assert fixed method/path order, bodyless-fixture rejection and expected SDK failure category without raw requests/headers/forms/bodies/errors. Preserve this evidence and the captured CI failing test. Compilation/setup errors are not RED; no random retries to obtain RED.
- [ ] Replace only the concurrent test's two identity() response enqueues with existing function callbacks sharing a two-party response rendezvous, waiting at most the existing five seconds after each matched response is selected. Return the unchanged account JSON. Keep the worker-entry barrier and existing150ms POST-body delay. Use a small test-local helper/constant only if needed, and keep ordinary identity() calls unchanged in behavior.
- [ ] Preserve all original result/key/metadata/onePOST/operation/resource assertions. Add exactly two GET/account requests and exactly three total requests; ensure account_id metadata and acknowledged original binding if useful. Coordination errors fail loudly with a fixed assertion and preserved interrupt; reset/release gates and cancel outstanding futures before executor close on failure. No new sleeps/timeouts/retries, permissive fixture matching or production exception changes.
- [ ] Run focused fixed method, then full26-case customer class and the one related StripeBillingClockSdkTest#concurrentClockCallsAndCustomerCallsEachDispatchOnlyOneOriginalPost selector using the explicit safe launcher. Fresh compile the15audited sources plus launcher; statically audit/inspect the four staged helper classfiles, cached Mockito agent manifest and exact dependency paths. Match default inline Mockito semantics; cached startup agent in this owned test JVM is permitted. No broad suite locally.
- [ ] Self-review, git diff --check, record exact inputs/support/output/source hashes, command/selection/counts and every failure/warning. Write task-1-report.md and freeze source. No source commit by implementer. Root reviews frozen scope, independently reviews final fix, commits/pushes and waits for a new exact-head full GitHub Offline CI. Native outcome remains UNKNOWN/NOT_ACCEPTED.

## Completion

Both reviews have no unresolved Critical/Important issues; selected local regression passes; root verifies clean reviewed commit and exact-head GitHub CI. Report failed original run and successful replacement distinctly. No Sandbox attempt follows automatically.
