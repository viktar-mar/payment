# Offline CI and Root README Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Implement task-by-task with independent review before each local commit. The user approved this concrete plan and subagent execution on 2026-10-20; Sandbox remains forbidden.

**Goal:** Give a fresh checkout a secret-free GitHub Actions regression pipeline and a concise, accurate entry point for local Fake development.

**Architecture:** Three independent Linux jobs reuse the existing Maven, pnpm and native Node test commands. A small shared local-issuer initializer replaces the long inline generation snippet so CI and the quick start use the same safe preparation. The root README links to existing detailed guides instead of duplicating them. No application/payment behavior changes.

**Tech Stack:** GitHub Actions on `ubuntu-24.04`, Temurin JDK 25, Maven Wrapper 3.9.11, Node 24, pnpm 11.1.3, native `node:test`, existing Vitest/Playwright, Docker Engine with Compose v2 and Buildx.

**Spec:** User-approved chat scope on 2026-10-20: CI without Stripe keys (backend/Maven/Cucumber, frontend and offline infrastructure tests) plus root README/quick start. Existing contracts: `docs/local-development.md`, `frontend/README.md`, `frontend/sdk/README.md`, package manifests, `backend/pom.xml`, and the sequential roadmap in `docs/superpowers/specs/2026-10-14-stripe-sandbox-design.md`.

## Global Constraints

- Reuse the clean isolated `codex/stripe-sandbox-5a` worktree at starting commit `d4067d35de6df73cd520083403ac970da40b6306`. Do not change main, push, merge, publish packages/images, deploy or change repository settings in this task.
- No real Stripe CLI/OAuth, Sandbox/live calls, card entry, payment confirmation, refunds or new financial fixtures. Do not read private local env files, credentials, previous run reports or issuer key contents.
- Ordinary CI must not require repository secrets or enable any real Sandbox opt-in. Synthetic Stripe adapter/Cucumber fixtures are not real Stripe acceptance.
- Backend tests may own disposable Testcontainers PostgreSQL/Mailpit resources. Infrastructure tests may render synthetic Compose configuration and build synthetic static Docker contexts. The separately configured offline browser suite may own its nginx container; provider browser tests intercept external requests. None of this starts the real application Compose/Sandbox stack.
- No full real-backend Fake Playwright acceptance in this first CI increment: its owned Stage 4 clock/runtime harness remains separately documented. Explicitly disclose that coverage boundary.
- Never overwrite an existing `.env` or issuer fixture, rotate user credentials, prune Docker, delete historical evidence, or stop unrelated resources.
- Workflow permissions are `contents: read`; checkout disables persisted credentials. No `pull_request_target`, secret bindings, environment dump, deploy/publish step, private artifact upload or new root dependency installation mechanism.
- Use immutable full commit SHAs for third-party actions, with upstream release comments. Verify SHA/tag correspondence from official upstream sources at implementation; do not guess SHAs. Use existing lockfiles with `--frozen-lockfile`.
- Keep traces, screenshots and video off. Do not upload raw test/auth/payment output as artifacts. Cache dependencies only, not local configuration, keys, build results or test reports.
- Preserve the earlier execution method: subagent implementer and fresh independent reviewer per task; targeted tests per change and full relevant gates at the CI checkpoint. Review before local commit; no automatic cleanup.

## Review Focus

1. A fresh clone has no ignored issuer fixture: generate one locally without committing or printing private material; existing bytes must remain untouched.
2. A pull request has no secrets or write token: the same test selection must run without live opt-ins, repository settings or a trusted branch condition.
3. Demo installation consumes the local SDK: build the SDK before demo checks and distinguish native Node support tests from ordinary Playwright discovery.
4. Docker-dependent checks must fail when unavailable, not silently skip; synthetic Compose rendering must not become application startup.
5. A green offline pipeline must not be represented as successful full Fake acceptance, completed Stage 5A, or live-payment readiness.

---

### Task 1: Secret-free CI and reusable local issuer preparation

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `infrastructure/init-local-customer-issuer.mjs`
- Create: `infrastructure/init-local-customer-issuer.test.mjs`
- Create: `infrastructure/ci-workflow.test.mjs`
- Modify: `docs/local-development.md` (issuer generation snippet and a short CI coverage section only)
- Modify: `infrastructure/stripe-manual-payment.test.mjs` (inert-import test only: Node 24 module-loader compatibility, private-read/process/network traps retained; execution ruling 2026-10-20)

**Interfaces:**
- Consumes: existing package manifests/lockfiles, `backend/mvnw`, default backend tests, infrastructure Node tests and `examples/demo-site/playwright.offline.config.ts`.
- Produces: `node infrastructure/init-local-customer-issuer.mjs`, runnable from any working directory, with the fixed repository-local ignored fixture destination. Export `initializeLocalCustomerIssuer(file: string) -> 'created' | 'preserved'` for tests against temporary directories; importing the module performs no writes. CLI prints only a fixed created/preserved status; errors exit nonzero without dumping private data.
- Creates the same RS256/RSA-2048 and ES256/P-256 private JWK fixture shape and key IDs as the existing guide, using exclusive creation and mode `0600`. Preserve an existing regular fixture byte-for-byte; reject symlinks/nonregular targets. Do not read existing private content. A race must not overwrite the winner.
- Workflow triggers: `push`, `pull_request`, `workflow_dispatch`; no path filtering in this first version. Cancel superseded runs using workflow/ref concurrency.
- Jobs: `backend` (45-minute limit), `frontend` (30-minute limit), `infrastructure` (30-minute limit). All run on `ubuntu-24.04`; no job condition restricts tests to trusted branches.

- [x] **Step 1: Meaningful RED for preparation and workflow contracts.** Add native Node tests first. For initializer: import is inert; missing temporary fixture creates both expected usable private key types with owner-only permissions; repeat preserves bytes; symlink/directory fail without overwrite; CLI resolves the repository independently of cwd and never prints key material (exercise CLI in a disposable copied fixture tree, never the user's existing key). For workflow: check all three jobs/required gates, permissions, trigger/concurrency/bounds, immutable action refs, frozen installs, explicit working directories, SDK-before-demo ordering and absence of secret/live/publish/deployment/artifact steps. Static contract tests complement, not replace, a real YAML/actionlint check.
- [x] **Step 2: Run RED.** `node --test infrastructure/init-local-customer-issuer.test.mjs infrastructure/ci-workflow.test.mjs` must fail on missing implementation/workflow, not an unrelated environment error. Retain the result in the task report.
- [x] **Step 3: Implement initializer and CI.** Write the smallest helper meeting the interface. Reuse the existing commands below, not new application test profiles or skipped tests. Resolve official action release SHAs; install pnpm before enabling its dependency cache. Use Maven/pnpm dependency caches keyed to the actual manifests/lockfiles. Check Docker/Compose/Buildx availability in the jobs that require them.

Backend job, after JDK/Node setup and initializer from the root:

```bash
./mvnw -B -ntp verify
# working-directory: backend
```

Frontend job, after pinned Node/pnpm setup:

```bash
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend/sdk test --run
pnpm --dir frontend/sdk typecheck
pnpm --dir frontend/sdk build
pnpm --dir frontend/admin test --run
pnpm --dir frontend/admin typecheck
pnpm --dir frontend/admin lint
pnpm --dir frontend/admin build
pnpm --dir examples/demo-site install --frozen-lockfile
pnpm --dir examples/demo-site exec vitest run src/payment-return.test.ts src/vite-return-privacy.test.ts
pnpm --dir examples/demo-site build
pnpm --dir examples/demo-site exec playwright install --with-deps chromium
node --test --test-timeout=60000 --test-concurrency=1 examples/demo-site/tests/support/*.test.ts
pnpm --dir examples/demo-site exec playwright test --config playwright.offline.config.ts --workers=1 --retries=0
```

Infrastructure job, after pinned Node/pnpm setup and Docker checks:

```bash
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend/sdk build
pnpm --dir examples/demo-site install --frozen-lockfile
node --test --test-timeout=30000 --test-concurrency=1 infrastructure/*.test.mjs
```

Use `NEXT_TELEMETRY_DISABLED=1` for the Admin build. Before adding all demo support files, inspect their actual runner/import requirements: they must remain native Node synthetic/browser-fixture tests, never ordinary Playwright real Sandbox discovery. If an existing support test needs a different offline command, document that specific command rather than omit it or broaden runtime permission.

- [x] **Step 4: GREEN and syntax validation.** Run both new test files. Validate the actual workflow using a checksum-verified official `actionlint` release; check shell/YAML diagnostics and fix real issues. Resolve official action inputs against their pinned manifests. Do not add a test that merely accepts whatever workflow was written.
- [x] **Step 5: Relevant checkpoint.** In the existing worktree, run initializer tests only against temporary fixtures; preserve the user's actual key. Run the backend verify, frontend gates and full infrastructure suite above, plus the offline Chromium suite, with owned local test Docker configuration. No Sandbox, actual application Compose startup or private env loading. Report any unavailable Node 24 runtime/tool separately; a Node 25 local pass does not prove the Node 24 GitHub job. No GitHub execution claim until the workflow has actually run remotely.
- [x] **Step 6: Documentation and independent gate.** Replace only the long issuer-generation snippet in `docs/local-development.md` with the shared command and add precise CI coverage/exclusions. Self-review, `git diff --check`, stage exact changed files, report RED/GREEN and remaining remote uncertainty. A fresh reviewer checks the tested delta before the root commits it locally; fix and re-review findings first.

### Task 2: Root README and trustworthy quick start

**Files:**
- Create: `README.md`
- Create: `infrastructure/readme.test.mjs`

**Interfaces:**
- Consumes: Task 1's initializer CLI/workflow, current `infrastructure/.env.example` and default `infrastructure/compose.yaml`, existing frontend/SDK/local-development guides.
- Produces: a concise repository entry document in English, matching current repository documentation. No duplicate specification or new startup wrapper.

- [ ] **Step 1: RED for the entry-point contract.** Add focused native Node tests for required relative links resolving inside the repository, referenced initializer/Compose/env-example paths existing, quick start using default Compose only, and explicit coverage/status distinctions. Assert real executable/script names against the manifests, not invented commands. Missing README must produce the initial RED; do not overconstrain prose or formatting.
- [ ] **Step 2: Write README.** Include purpose/multi-Site separation, a small folder map (Java hexagonal backend, Next.js Admin, framework-independent SDK, demo, infrastructure, docs), prerequisites and these fresh-clone steps: generate local issuer, copy `.env.example` only if no `.env` exists, review local settings, default Compose `up --build -d --wait`, then Admin/API/demo addresses. Explain that public example credentials are local-only, existing database/admin credentials persist, occupied unrelated ports should be changed rather than processes killed, and stopping must retain data. Use a conditional copy command, never an unconditional overwrite.
- [ ] **Step 3: State honest scope.** Fake covers local payment/donation/subscription functionality. Stage 5A form readiness has been observed, but the complete real Sandbox payment/refund/callback acceptance is still pending; Stage 5B Stripe Billing/Portal and Stage 5C remain future work. Browser UX is not financial fulfillment proof. Document precisely which checks CI executes and that full owned Fake E2E and real Sandbox are not part of this first pipeline.
- [ ] **Step 4: Link rather than duplicate.** Link `docs/local-development.md`, `frontend/README.md`, `frontend/sdk/README.md`, the demo directory, CI workflow and roadmap. Give canonical check commands or a compact link to the CI coverage section; keep advanced Stripe launch/secret/device-code instructions out of the quick start.
- [ ] **Step 5: Verify and independently review.** Run `node --test infrastructure/readme.test.mjs infrastructure/init-local-customer-issuer.test.mjs infrastructure/ci-workflow.test.mjs`, validate links/commands against the actual files and run `git diff --check`. Inspect rendered Markdown structure if a preview is available; never start the stack merely to test prose. A fresh reviewer checks accuracy, safety, scope and the tested delta before local commit. Then review the complete two-task delta once, fix relevant findings, and hand off with explicit local-versus-GitHub verification status. No push or Sandbox launch.

## Plan Self-Review / Handoff

The two tasks cover the approved deliverables; each has a separate rejectable result and independent review gate. Preparation belongs to CI/quick-start correctness, not a separate feature. The five Review Focus cases map to Task 1 fixture/workflow/ordering tests and Task 2 status/link checks. No production payment APIs, Java architecture, package versions or cloud deployment decisions change. Human approval was received on 2026-10-20; use the requested subagent-driven execution method.

Official reference entry points for action selection/validation (verify immutable release SHAs during implementation):
- https://github.com/actions/checkout
- https://github.com/actions/setup-java
- https://github.com/actions/setup-node
- https://github.com/pnpm/action-setup
- https://github.com/rhysd/actionlint

## Execution checkpoint (2026-10-20)

Task 1 is implemented, independently approved and locally verified. Full Maven:2589 total, zero failures/errors, one existing deliberately opt-in API-generation runner skipped. Frontend checks, native support302, offline Chromium7 and final infrastructure971 all passed. GitHub-hosted execution remains unperformed; no push, merge or Sandbox launch.

Task 2's draft and six behavioral tests passed targeted validation and task review, but final broad review found ImportantF1: ordinary native Linux host UID1000 creates a mode0600 issuer that container UID10001 cannot read through the existing direct bind mount. The README quick start is not accepted as complete until a deliberate secure access bridge is designed/tested. Do not weaken the fixture to0644 or run backend as root. A Docker-packaging/security-policy expansion requires separate human approval; no such change has been made. Task 2 and final acceptance remain open.
