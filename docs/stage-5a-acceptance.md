# Stripe Stage 5A acceptance

Status on 2026-10-25: **not accepted**. The implementation is present, but complete real Sandbox lifecycle evidence and final regression gates are still required. Recovery diagnostic development is frozen; Python provenance, unpacking and new SQL/container collectors are not prerequisites for closing 5A.

This review starts from `52e12550fa871aae1dd9c25e000bc38d27847dac`. The authority is the approved [Stage 5 design](superpowers/specs/2026-10-14-stripe-sandbox-design.md#12-проверки-и-критерии-приёмки-5a), with the existing [Task 16 acceptance plan](superpowers/plans/2026-10-14-stripe-sandbox-5a-implementation.md#task-16-cucumber-regression-и-opt-in-stripe-sandbox-acceptance). Open checkboxes in the original planning snapshot are not evidence that the corresponding code is absent.

## Acceptance matrix

The test links below establish that coverage exists in source, not that every suite has run on this revision. Actual executions are recorded separately.

| Requirement | Existing implementation and coverage | Remaining gate |
| --- | --- | --- |
| 1. Default Fake and explicit Sandbox configuration | [Configuration tests](../backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeSandboxConfigurationTest.java) cover disabled mode, profiles, key shapes and secret-safe failures; [account tests](../backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeAccountVerificationAdapterTest.java) cover identity and permission errors. Fresh full regression, isolated Fake startup and full owned Fake E2E passed below. | Exact-final-commit CI. |
| 2. Catalog, dynamic and anonymous/identified donations with Site ownership | [Cucumber scenarios](../backend/src/test/resources/features/stripe_sandbox.feature), [client-session tests](../backend/src/test/java/dev/genericpayments/platform/payments/adapter/in/web/StripeClientSessionIntegrationTest.java) and catalog publication tests exercise amount, source, account and authority boundaries. Fresh PostgreSQL/Cucumber regression passed. | Real Sandbox donation evidence remains unconfirmed. |
| 3. Card success, recoverable decline, SCA, processing and unknown | Payment Element and [driver tests](../frontend/sdk/src/providers/stripe-driver.test.ts) retain server authority. Cucumber covers same-intent retry and pending SCA. Historical notes describe one successful EUR20 payment. | Real Sandbox decline/retry on the same intent and 3DS; a complete correlated payment report. |
| 4. Durable preparation and idempotency | [Operation-store tests](../backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/ProviderOperationStoreIntegrationTest.java), preparation tests and operation guards cover concurrency, lost mapping commits and expired write windows. Fresh PostgreSQL integration regression passed. | No remaining synthetic integration gate; retain the real lifecycle evidence required by rows 3, 5–7. |
| 5. Signed webhook, inbox, journal and outbox | [Webhook admission tests](../backend/src/test/java/dev/genericpayments/platform/provider/adapter/in/web/StripeWebhookAdmissionTest.java) cover exact bytes, timestamp bounds, size, API version and account/mode. Inbox tests cover duplicates, ordering, races and rollback; fresh integration regression passed. | Correlated real webhook, journal, outbox and Site callback evidence. |
| 6. Missed webhook and unknown-operation recovery | [Recovery tests](../backend/src/test/java/dev/genericpayments/platform/payments/application/service/StripeRecoveryIntegrationTest.java) and Cucumber cover retrieve, original-operation proof, bounded replay and foreign-object refusal. Fresh integration regression passed. | Real Sandbox missed-webhook recovery. |
| 7. Refunds and refund limits | [Refund tests](../backend/src/test/java/dev/genericpayments/platform/payments/application/service/StripeRefundIntegrationTest.java) cover EUR7 plus EUR13, concurrent reservations, unknown/pending/failure and webhook-before-response. Fresh integration regression passed; historical notes describe a verified EUR7 refund. | Current EUR13 outcome is unknown; complete real partial/full-refund evidence. |
| 8. SDK contracts, lifecycle, return handling and privacy | SDK unit tests cover SSR/lazy loading, presentation, ownership, cancellation, expiry, stale responses and safe storage/events. SDK/Admin/demo unit/build, Docker-backed nginx/CSP and full owned Fake E2E passed below; real form readiness was historically observed in Chrome. | No remaining local synthetic gate; real lifecycle evidence remains required by rows 3, 5–7. |
| 9. Migrations and existing flows | [Migration tests](../backend/src/test/java/dev/genericpayments/platform/donations/adapter/out/persistence/DonationMigrationIntegrationTest.java) exercise historical data through V25. Fresh full Maven verify, full owned Fake E2E and stage 1–4 smoke passed. Existing payment/donation/subscription/callback suites and [CI](../.github/workflows/ci.yml) remain available. | CI on the exact final commit. |

## Historical payment and refund evidence

The retained [remainder design](superpowers/specs/2026-10-20-manual-refund-remainder-design.md#1-зачем-нужен-отдельный-режим) records a successful anonymous catalog/card payment of EUR20. It also records independent confirmation of Refund SUCCEEDED/700, a local REFUND_SUCCEEDED fact and callback HTTP204. The original refund controller stopped PENDING before authorizing its second leg; the notes say EUR13 was not sent in that observation.

These historical notes do not establish the current Stripe or database state. No current reconciliation has been performed in this closeout. Read-only Stripe access was subsequently authorized, but the known local credentials/worktree were absent and no exact original PaymentIntent binding was available. Only the preservation summary was read; original archives, database contents and private container environments were not opened. All seven original stack containers remained stopped. An absent local report, an old READY state or an incomplete PENDING result never proves that EUR13 is safe to submit again. Do not rerun an old financial entrypoint, recreate its authority files or invent a replacement report.

An empty, ignored, mode0600 `infrastructure/stripe-sandbox.env.local` template now exists in the primary checkout. A future provider-only read needs the test server key placed there locally and the independently identified original PaymentIntent ID, never its client secret. No global credential search, CLI authorization or Stripe request was performed. Supplying these inputs does not authorize writes and cannot by itself verify the old local journal/outbox.

If a bounded read-only check cannot establish the old state, keep that historical run unresolved as a separate operational item. Do not expand the frozen diagnostic framework. Any future fresh acceptance fixture needs its own authorization and does not resolve the historical EUR13 question.

## Fresh offline verification

The initial checkpoint was restricted to Docker-free checks. Dependencies were restored from the existing pnpm store with frozen lockfiles, `--offline` and `--ignore-scripts`; missing cache entries stopped preparation rather than triggering a download. The user then authorized Docker regression on artificial data and Stripe read-only observation of the old payment/refunds. That continuation is recorded separately below; new financial operations, original archive reads, Python and diagnostic-framework expansion remain excluded.

Environment: Node.js 25.2.1, pnpm 11.1.3 and Temurin JDK25. CI is pinned to Node24, so these local checks do not replace the GitHub-hosted gate.

| Executed gate | Result |
| --- | --- |
| Explicit Docker-free Maven selection: ArchitectureRulesTest, ArchitectureRulesRuleTest, StripeSandboxConfigurationTest, StripeClientFactoryTest, StripeAccountVerificationAdapterTest, StripePaymentSnapshotMapperTest, ProviderOperationGuardTest | 106 passed, no failures/errors/skips; production and test sources compiled; Maven offline BUILD SUCCESS. |
| Full SDK unit suite, two workers | 27 files, 772 tests passed. |
| SDK typecheck and build | Both exited 0. |
| Initial full Admin unit suite, two workers overlapping SDK and javac | 236 passed; two PaymentDetails tests exceeded their unchanged default 5000ms deadline. This run failed. |
| Unchanged PaymentDetails tests run alone, one worker | 9 passed; no timeout or assertion changes. |
| Full Admin unit suite rerun, one worker without the overlapping SDK/javac workloads | 35 files, 238 tests passed with the original deadlines and assertions. The initial two-worker timeout failures remain recorded above. |
| Admin typecheck and lint | Both exited 0. |
| Admin production build with NEXT_TELEMETRY_DISABLED=1 | Exited 0; webpack compilation, TypeScript and static-page generation completed. |
| Demo return handling and actual Vite return privacy tests | 2 files, 5 tests passed; owned loopback servers only. |
| Demo TypeScript and Vite production build | Exited 0. |
| Selected native Chromium DOM and synthetic card-driver fixtures | 20 passed, including nested snapshot cases; all browser HTTP/WebSocket traffic intercepted, no actual Stripe form/card/payment. |
| Selected native Node README/CI and manual-payment/refund admission/report-schema tests | 24 passed, no failures/skips. This is not the full infrastructure suite. |
| Source publishability, hexagonal outbound packages, ignored credentials/outputs and retained build inputs | 4 passed with an explicit name filter in gitignore.test.mjs; its three Docker-context tests were not selected. |

Those initial checks did not run full Maven/PostgreSQL/Cucumber, Compose, Docker-backed return/privacy, full infrastructure or owned Fake E2E. The authorized continuation below supersedes that execution boundary, not the historical results. Real Sandbox and current payment/refund state have still not been checked. Existing Java deprecation and unchecked-operation compiler warnings remain; no claim of warning-free compilation is made.

Git comparison with main shows only added V21–V25 migration files and no changes to V1–V20. This source-level check alone does not verify Flyway checksums; the later full Maven run exercised fresh/upgrade test databases through V25, not the old retained database.

The initial offline pnpm attempt selected an empty temporary-directory store and failed with `ERR_PNPM_NO_OFFLINE_TARBALL`. Reusing the existing project store restored frontend and demo dependencies with zero downloads and no lockfile changes. A sandbox filesystem restriction on that cache was resolved through scoped command approval, not a code change.

The previous [backend CI checkpoint](superpowers/plans/2026-10-20-backend-ci-stability.md#combined-checkpoint-and-publication-root-owned) records 2599 tests, no failures/errors and one existing opt-in generator skip, including Cucumber 79/79. That is historical evidence for its recorded source tree, not a fresh result for this revision.

The selected native Node command used `--test-timeout=30000 --test-concurrency=1` with these explicit files, not the Docker-dependent infrastructure glob: `readme.test.mjs`, `ci-workflow.test.mjs`, `stripe-manual-payment-options.test.mjs`, `stripe-manual-payment-schema.test.mjs`, `stripe-manual-refund-schema.test.mjs`, `stripe-manual-refund-remainder-options.test.mjs` and `stripe-manual-refund-remainder-schema.test.mjs`, all under `infrastructure/`. SDK used `pnpm --dir frontend/sdk test --run --maxWorkers=2`; the final Admin unit gate used `pnpm --dir frontend/admin test --run --maxWorkers=1`. No repository test settings were changed.

The separate native Chromium/fixture selection used `node --test --test-timeout=60000 --test-concurrency=1` with `provider-mount-browser.test.ts`, `form-load-manual-snapshot-browser.test.ts` and `stripe-sandbox-fixtures.test.ts` under `examples/demo-site/tests/support/`. It does not run the real Sandbox spec or the Docker-backed nginx/CSP suite. The actual Vite return privacy command was `pnpm --dir examples/demo-site exec vitest run src/payment-return.test.ts src/vite-return-privacy.test.ts --maxWorkers=1`.

## Authorized synthetic runtime continuation

Production source at `52be506d54eea8e88e9461d449c0f3bc4474ec39` is unchanged from the reviewed implementation baseline. The current changes correct test fixtures only. Regression uses generated credentials, new databases and cached images, not the original Stripe stack or user environment. No dependency installation or application-image build/pull was requested in this continuation; Maven used the existing offline cache. Infrastructure tests do execute network-disabled `FROM scratch` Buildx context exports using synthetic files. Fake Compose binds the packaged current JAR, existing frontend builds and the existing three-class test launcher; it does not run an older application JAR from a cached image.

| Executed gate | Result |
| --- | --- |
| Full offline Maven `verify`, fresh PostgreSQL/Testcontainers | BUILD SUCCESS; 2599 tests, 0 failures/errors, 1 skip; Cucumber 79/79. Completed 2026-10-25T14:58:29Z in 55:16. The sole skip is the existing opt-in `SubscriptionOpenApiGenerationIntegrationTest` (`subscription.openapi.generate` absent), not a disabled acceptance test. |
| Docker-backed nginx/CSP return/privacy Chromium suite, one worker, zero retries | 7/7 passed. Non-owned browser traffic intercepted; temporary nginx stopped. |
| Initial full infrastructure suite overlapping Maven | Failed: 1746 passed, 3 failed, 4 cancelled of 1753. Two unchanged 30s parent deadlines expired; a synthetic clock inconsistency caused the third failure. |
| Clock correction and complete sequential infrastructure repeat | Deterministic 60s host-clock-jump RED reproduced the refusal; GREEN shares the retained synthetic clock with simulated Docker start/runtime. Full related file 87/87; complete suite 1753/1753 passed in 47.9s, original deadlines and production admission unchanged. |
| Complete infrastructure after the CORS URL regression was added | 1755/1755 passed in 52.6s, no failures/cancellations/skips, original 30s limits. |
| Initial full native browser/support suite overlapping Maven | Failed: 311 passed, 6 failed, 4 cancelled of 321. Two unchanged 60s parent deadlines expired; child failures/cancellations reported parent completion, not mismatched payment assertions. |
| Complete unchanged native browser/support repeat without concurrent Maven | 321/321 passed in 53.5s, no failures/cancellations/skips and original deadlines. |
| Initial isolated Fake startup and full ordinary Playwright, zero retries | Startup passed; 60/61 browser tests passed. The CORS test's fixed `localhost:4173` URL refused connection on the random-port fixture before its final assertion. A pure URL regression reproduces the defect; corrected navigation retains the owned port and changes only loopback hostname. |
| Complete corrected-test Fake E2E and smoke, new project/database, zero browser retries | Startup, 61/61 browser tests and all stage 1, 2, 3A, 3B, 4 smoke checks passed. Controller exited 0 with `PASS` and `stopped:true`; all six exact owned containers were independently observed exited and no separate temporary nginx remained. |

The initial isolated runtime fixture failed before browser tests because a generated standalone Admin bind target was absent; the second fixture failed host readiness because Docker Desktop did not publish an internal bridge's ports. Both failed reports remain retained. Only generated build mountpoint/setup was adjusted. The third fixture uses ordinary Compose bridge networking, explicit Fake, Stripe disabled and no real provider credentials; physical network egress isolation is not claimed. Its six containers were independently observed stopped, and no separate temporary nginx remained. Smoke stages 1–4 did not execute after that browser failure.

Only the test clock and CORS navigation are corrected; no financial runtime, migration, CORS policy, authority check, timeout, retry or production setting is relaxed. Independent review approved both test-only corrections with no Critical/Important/Minor findings. The final Fake runtime used project `task17-5a-20261025-76996dab` with the current test-only working delta over the named source commit. Stage 4 emitted a Node25 `TimeoutNegativeWarning` but returned all smoke assertions passing; warning-free execution is not claimed. Containers, volumes, networks and synthetic evidence were retained, not deleted. The original Stripe project's seven services remained exited throughout the observed before/after metadata checks. Private synthetic logs and earlier failures remain under the existing ignored execution workspace; this document is not a replacement financial report.

Flyway logs record validation of all 25 migrations and successful application through V25 in synthetic databases. Latest source `V25__provider_recovery_idempotency.sql` SHA256 is `e2c14789a7a466c3f74da5795adf3bb8042af176a21a1c0794a12f56719a6bc7`, unchanged from the implementation baseline. This source digest is not a claim about the retained database's Flyway history/checksum.

## Minimal completion sequence

1. Preserve the completed local regression results above; rerun relevant gates if executable source changes. Exact-final-commit hosted CI remains pending. Keep separate results for Docker-free units, Maven/PostgreSQL/Cucumber, SDK/Admin/demo, nginx/CSP privacy, infrastructure and owned Fake E2E; no silent skips or weakened CI.
2. Obtain the missing test server key locally and the exact old PaymentIntent identity for the already-authorized provider-only read. Compare provider totals and individual refunds first. Old database/archive access has not been authorized by the synthetic Docker permission; local journal/outbox comparison remains a separate bounded step if available and authorized. No app scheduler, reconciliation mutation, callback resend, key rotation or new refund is part of read-only observation. Inconclusive evidence remains inconclusive.
3. Once separately authorized, use the existing Sandbox acceptance scenarios for missing card decline/retry, 3DS, donations, partial/full refunds and missed-webhook recovery. Preserve exact payment/account/Site bindings, stable operation keys and server-side evidence. Financial browser scenarios have one worker and zero automatic retries; a failed or unknown run does not authorize a new one.
4. Record actual results, unresolved defects and cleanup, update this status and README, and complete Task 16.8's spec/source-completeness review, staged secret audit with explicit synthetic-sentinel exclusions, exact source exclusions, latest migration-checksum check and independent review. Verify GitHub CI for the exact published commit before integrating 5A; a local definition or old green run is not sufficient. Synthetic migration validation does not validate the retained old database.

The cached `origin/codex/stripe-sandbox-5a` ref was `a2a8de5d47657302c7287ff7cc0965d79c9aca62`, 28 commits behind the reviewed baseline. This is a local ref observation, not a current GitHub query. No push, merge or remote CI status is claimed here.

## Excluded work

No new diagnostic platform, Python runtime, unpacker, container collector or generalized recovery framework belongs to this closeout. Existing diagnostic files and historical data are preserved. Stage 5B owns Stripe Billing/subscriptions and Customer Portal. Stage 5C owns additional payment methods, expanded operational readiness and cloud preparation. Wallet support and live-payment activation are not prerequisites for card-first 5A acceptance.
