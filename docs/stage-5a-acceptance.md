# Stripe Stage 5A acceptance

Status on 2026-10-25: **not accepted**. The implementation is present, but complete real Sandbox lifecycle evidence and final regression gates are still required. Recovery diagnostic development is frozen; Python provenance, unpacking and new SQL/container collectors are not prerequisites for closing 5A.

This review starts from `52e12550fa871aae1dd9c25e000bc38d27847dac`. The authority is the approved [Stage 5 design](superpowers/specs/2026-10-14-stripe-sandbox-design.md#12-проверки-и-критерии-приёмки-5a), with the existing [Task 16 acceptance plan](superpowers/plans/2026-10-14-stripe-sandbox-5a-implementation.md#task-16-cucumber-regression-и-opt-in-stripe-sandbox-acceptance). Open checkboxes in the original planning snapshot are not evidence that the corresponding code is absent.

## Acceptance matrix

The test links below establish that coverage exists in source, not that every suite has run on this revision. Actual executions are recorded separately.

| Requirement | Existing implementation and coverage | Remaining gate |
| --- | --- | --- |
| 1. Default Fake and explicit Sandbox configuration | [Configuration tests](../backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeSandboxConfigurationTest.java) cover disabled mode, profiles, key shapes and secret-safe failures; [account tests](../backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/stripe/StripeAccountVerificationAdapterTest.java) cover identity and permission errors. | Final full regression and actual Fake startup. |
| 2. Catalog, dynamic and anonymous/identified donations with Site ownership | [Cucumber scenarios](../backend/src/test/resources/features/stripe_sandbox.feature), [client-session tests](../backend/src/test/java/dev/genericpayments/platform/payments/adapter/in/web/StripeClientSessionIntegrationTest.java) and catalog publication tests exercise amount, source, account and authority boundaries. | Final PostgreSQL/Cucumber regression; real Sandbox donation evidence remains unconfirmed. |
| 3. Card success, recoverable decline, SCA, processing and unknown | Payment Element and [driver tests](../frontend/sdk/src/providers/stripe-driver.test.ts) retain server authority. Cucumber covers same-intent retry and pending SCA. Historical notes describe one successful EUR20 payment. | Real Sandbox decline/retry on the same intent and 3DS; a complete correlated payment report. |
| 4. Durable preparation and idempotency | [Operation-store tests](../backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/ProviderOperationStoreIntegrationTest.java), preparation tests and operation guards cover concurrency, lost mapping commits and expired write windows. | Fresh PostgreSQL integration regression. |
| 5. Signed webhook, inbox, journal and outbox | [Webhook admission tests](../backend/src/test/java/dev/genericpayments/platform/provider/adapter/in/web/StripeWebhookAdmissionTest.java) cover exact bytes, timestamp bounds, size, API version and account/mode. Inbox tests cover duplicates, ordering, races and rollback. | Final integration regression and correlated real webhook, journal, outbox and Site callback evidence. |
| 6. Missed webhook and unknown-operation recovery | [Recovery tests](../backend/src/test/java/dev/genericpayments/platform/payments/application/service/StripeRecoveryIntegrationTest.java) and Cucumber cover retrieve, original-operation proof, bounded replay and foreign-object refusal. | Real Sandbox missed-webhook recovery and fresh integration regression. |
| 7. Refunds and refund limits | [Refund tests](../backend/src/test/java/dev/genericpayments/platform/payments/application/service/StripeRefundIntegrationTest.java) cover EUR7 plus EUR13, concurrent reservations, unknown/pending/failure and webhook-before-response. Historical notes describe a verified EUR7 refund. | Current EUR13 outcome is unknown; complete real partial/full-refund evidence and fresh integration regression. |
| 8. SDK contracts, lifecycle, return handling and privacy | SDK unit tests cover SSR/lazy loading, presentation, ownership, cancellation, expiry, stale responses and safe storage/events. SDK/Admin/demo unit and build checks passed below; real form readiness was historically observed in Chrome. | Docker-backed nginx/CSP return/privacy browser suite and full owned Fake E2E. |
| 9. Migrations and existing flows | [Migration tests](../backend/src/test/java/dev/genericpayments/platform/donations/adapter/out/persistence/DonationMigrationIntegrationTest.java) exercise historical data through V25. Existing payment/donation/subscription/callback suites and [CI](../.github/workflows/ci.yml) remain available. | Full Maven verify, full owned Fake E2E and CI on the exact final commit. |

## Historical payment and refund evidence

The retained [remainder design](superpowers/specs/2026-10-20-manual-refund-remainder-design.md#1-зачем-нужен-отдельный-режим) records a successful anonymous catalog/card payment of EUR20. It also records independent confirmation of Refund SUCCEEDED/700, a local REFUND_SUCCEEDED fact and callback HTTP204. The original refund controller stopped PENDING before authorizing its second leg; the notes say EUR13 was not sent in that observation.

These historical notes do not establish the current Stripe or database state. No current reconciliation has been performed in this closeout. An absent local report, an old READY state or an incomplete PENDING result never proves that EUR13 is safe to submit again. Do not rerun an old financial entrypoint, recreate its authority files or invent a replacement report.

If a bounded read-only check cannot establish the old state, keep that historical run unresolved as a separate operational item. Do not expand the frozen diagnostic framework. Any future fresh acceptance fixture needs its own authorization and does not resolve the historical EUR13 question.

## Fresh offline verification

The current execution is restricted to Docker-free checks. No Docker invocation, Stripe request, original archive read, Python execution or financial operation is permitted. Dependencies may be restored from the existing pnpm store with frozen lockfiles, `--offline` and `--ignore-scripts`; missing cache entries must stop preparation rather than trigger a download.

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

Full Maven/PostgreSQL/Cucumber, Compose validation, Docker-backed return/privacy, full infrastructure and owned Fake E2E have not run in this closeout. Real Sandbox and current payment/refund state have not been checked. Existing Java deprecation and unchecked-operation compiler warnings remain; no claim of warning-free compilation is made.

Git comparison with main shows only added V21–V25 migration files and no changes to V1–V20. This source-level check does not verify Flyway checksums or migration execution in a database.

The initial offline pnpm attempt selected an empty temporary-directory store and failed with `ERR_PNPM_NO_OFFLINE_TARBALL`. Reusing the existing project store restored frontend and demo dependencies with zero downloads and no lockfile changes. A sandbox filesystem restriction on that cache was resolved through scoped command approval, not a code change.

The previous [backend CI checkpoint](superpowers/plans/2026-10-20-backend-ci-stability.md#combined-checkpoint-and-publication-root-owned) records 2599 tests, no failures/errors and one existing opt-in generator skip, including Cucumber 79/79. That is historical evidence for its recorded source tree, not a fresh result for this revision.

The selected native Node command used `--test-timeout=30000 --test-concurrency=1` with these explicit files, not the Docker-dependent infrastructure glob: `readme.test.mjs`, `ci-workflow.test.mjs`, `stripe-manual-payment-options.test.mjs`, `stripe-manual-payment-schema.test.mjs`, `stripe-manual-refund-schema.test.mjs`, `stripe-manual-refund-remainder-options.test.mjs` and `stripe-manual-refund-remainder-schema.test.mjs`, all under `infrastructure/`. SDK used `pnpm --dir frontend/sdk test --run --maxWorkers=2`; the final Admin unit gate used `pnpm --dir frontend/admin test --run --maxWorkers=1`. No repository test settings were changed.

The separate native Chromium/fixture selection used `node --test --test-timeout=60000 --test-concurrency=1` with `provider-mount-browser.test.ts`, `form-load-manual-snapshot-browser.test.ts` and `stripe-sandbox-fixtures.test.ts` under `examples/demo-site/tests/support/`. It does not run the real Sandbox spec or the Docker-backed nginx/CSP suite. The actual Vite return privacy command was `pnpm --dir examples/demo-site exec vitest run src/payment-return.test.ts src/vite-return-privacy.test.ts --maxWorkers=1`.

## Minimal completion sequence

1. Finish the existing code's regression gates. Keep a separate result for Docker-free unit checks, full Maven/PostgreSQL/Cucumber, SDK/Admin/demo, Docker-backed return/privacy, infrastructure and full owned Fake E2E. Do not weaken CI or silently skip tests to fit the current execution restriction.
2. Once read-only runtime/provider access is authorized, establish the exact old payment/refund identities and compare provider totals, individual refunds, local journal and outbox/delivery state. No app scheduler, reconciliation mutation, callback resend, key rotation or new refund is part of that read-only step. Inconclusive evidence remains inconclusive.
3. Once separately authorized, use the existing Sandbox acceptance scenarios for missing card decline/retry, 3DS, donations, partial/full refunds and missed-webhook recovery. Preserve exact payment/account/Site bindings, stable operation keys and server-side evidence. Financial browser scenarios have one worker and zero automatic retries; a failed or unknown run does not authorize a new one.
4. Record the actual results, unresolved defects and cleanup outcome, update this status and README, and verify GitHub CI for the exact published commit before integrating 5A. An offline workflow definition or old green run is not sufficient.

The cached `origin/codex/stripe-sandbox-5a` ref was `a2a8de5d47657302c7287ff7cc0965d79c9aca62`, 28 commits behind the reviewed baseline. This is a local ref observation, not a current GitHub query. No push, merge or remote CI status is claimed here.

## Excluded work

No new diagnostic platform, Python runtime, unpacker, container collector or generalized recovery framework belongs to this closeout. Existing diagnostic files and historical data are preserved. Stage 5B owns Stripe Billing/subscriptions and Customer Portal. Stage 5C owns additional payment methods, expanded operational readiness and cloud preparation. Wallet support and live-payment activation are not prerequisites for card-first 5A acceptance.
