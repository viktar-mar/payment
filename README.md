# Generic Payments

A payment platform for multiple Sites, with a Java backend, an Admin application and a framework-independent browser SDK. Each Site keeps its own catalog, Customers, payments, subscriptions, callback configuration and scoped Partner access; provider accounts do not erase those boundaries. A trusted server must verify financial facts before fulfillment. Browser events and successful UI flows alone are not payment proof.

## Repository

| Folder | Purpose |
| --- | --- |
| `backend/` | Java / Spring Boot backend with hexagonal domain, application and adapter boundaries |
| [`frontend/admin/`](frontend/README.md) | Next.js Admin and same-origin backend proxy |
| [`frontend/sdk/`](frontend/sdk/README.md) | Framework-independent client and Web Components |
| [`examples/demo-site/`](examples/demo-site/) | SDK integration demo |
| `infrastructure/` | Local Compose, issuer initializer and smoke/test tooling |
| [`docs/`](docs/local-development.md) | Local development and acceptance guides |

## Quick start: local Fake provider

Use Docker Engine/Desktop with Compose v2 and Buildx, Node.js 24 and a POSIX shell on Linux or macOS. Run as your ordinary user without sudo; root and Windows are unsupported by the local launcher. Running source checks outside Docker also requires JDK 25 and pnpm 11.1.3; Maven is provided by `backend/mvnw`.

From the repository root, generate a local Customer issuer using the [initializer](infrastructure/init-local-customer-issuer.mjs), then copy the public [environment example](infrastructure/.env.example) only when no local configuration exists:

```bash
node infrastructure/init-local-customer-issuer.mjs
[ -e infrastructure/.env ] || [ -L infrastructure/.env ] || cp infrastructure/.env.example infrastructure/.env
```

The initializer preserves an existing regular issuer file and refuses symlinks. The copy guard preserves existing `.env` files and symlinks. Review `infrastructure/.env` in your editor before starting: public example passwords and keys are for local development only. Keep existing database credentials and encryption/signing keys with persisted data. Bootstrap credentials create the first administrator; editing them does not rotate an existing administrator's password.

If a port is occupied by an unrelated process or container, change the corresponding `PAYMENT_*_HOST_PORT` setting. Leave that process running. The [default Compose file](infrastructure/compose.yaml) binds published ports to loopback and uses Fake without Stripe credentials. The [local launcher](infrastructure/local-compose.mjs) adds a [local user overlay](infrastructure/compose.local-user.yaml) that runs only the backend with your POSIX UID/GID so it can read the host-owned issuer fixture. The host key stays mode0600. The launcher derives IDs from its process, ignoring inherited `PAYMENT_LOCAL_UID`/`PAYMENT_LOCAL_GID` values, and resolves the repository paths from its own location:

```bash
node infrastructure/local-compose.mjs up
```

Default addresses (adjust for any host-port changes): [Admin](http://localhost:3000), [API](http://localhost:8080), [demo](http://localhost:3001). Sign in with the configured bootstrap credentials on a fresh database. Starting the stack does not populate a Site or catalog; follow the [local development guide](docs/local-development.md) to configure a Fake account, Site and published prices before using the demo.

Stop this Compose project while retaining its database volume:

```bash
node infrastructure/local-compose.mjs stop
```

Keep the named PostgreSQL volume, local configuration and issuer fixture for subsequent runs. Do not remove volumes to resolve a port conflict.

## Scope and readiness

The Fake provider covers local one-time payments, donations and subscriptions, including recovery and callback flows. On 2026-10-28, Stage 5A card-first is **technically ACCEPTED** for executable source `9143e01937b9d0e06d2e2809eec88cf0c965ea3a`: offline/Fake regression, earlier card success and same-intent decline/retry, separate manual 3DS, and fresh payment/refund/donation/missed-webhook proofs. Independent documentation and risk-based branch reviews are complete with no actionable/material findings; their coverage limits are recorded in the matrix. Final staged audit, commit/push and external exact-documentation-commit CI verification remain the publication gate at this write-time. See the dated [Stage 5A acceptance matrix](docs/stage-5a-acceptance.md) for the decision, source bindings and limits. Sandbox evidence does not establish live-payment readiness.

The [remaining-only 5A runner](docs/local-development.md#remaining-only-5a-sandbox-mode) completed its authorized fresh run. Its narrow report retains `remainingAccepted=true`, `accepted=false` and `proof.cards=false`; the overall evidence decision combines separate runs rather than inventing one successful full runner.

The historical EUR13/local-state issue and an earlier anonymous DISPATCHING donation remain separate unresolved operational items, excluded from automatic replay. New successful fixtures do not resolve them or authorize another refund/donation attempt. Further recovery diagnostic development is frozen; retained old journal, outbox and Flyway state remain unobserved.

Earlier authorized Docker cleanup removed 39 stopped/created synthetic containers, 10 one-run images and 4 empty Fake networks. Controller metadata checks confirmed the seven original Stripe containers remained exited and all volumes and other Docker objects were preserved; generated configs/logs and test evidence remain. These observations were not reproduced during this documentation update.

Stage 5B Stripe Billing/subscriptions and Customer Portal, then Stage 5C expanded reconciliation, payment methods and cloud readiness remain future work. See the [Stage 5 roadmap](docs/superpowers/specs/2026-10-14-stripe-sandbox-design.md#1-последовательность-этапа) and the [acceptance boundaries](docs/local-development.md#stage-5a-offline-regression-and-explicit-sandbox-acceptance).

## Checks and guides

[Offline CI](.github/workflows/ci.yml) runs default Maven verification (including synthetic Stripe adapter/Cucumber fixtures), SDK and Admin tests/typechecks/builds, Admin lint, demo return-unit tests/build, native Node synthetic support tests, the separate offline Chromium return/privacy suite and all infrastructure Node tests. It installs dependencies with frozen lockfiles and requires no repository secrets or real Stripe opt-in.

The completed [hosted run 40207944787](https://github.com/viktar-mar/payment/actions/runs/40207944787) passed all three jobs for exact source commit `319af884c212401623841e8f768636e9a9b83e8a`. Earlier hosted failures remain recorded in the acceptance matrix. That success does not cover this subsequent documentation commit; its exact-commit hosted CI remains pending.

Full owned Fake end-to-end acceptance uses the separate Stage 4 clock/runtime harness and is outside this pipeline; its completed local result is recorded in the acceptance matrix. Real Sandbox acceptance is also outside CI. Local and hosted synthetic results do not establish financial acceptance or readiness to merge. For exact commands and ownership requirements, see [CI coverage](docs/local-development.md#secret-free-ci-coverage) and the workflow above.

Use the [Admin guide](frontend/README.md), [SDK guide](frontend/sdk/README.md), [demo source](examples/demo-site/) and [local development guide](docs/local-development.md) for integration and advanced testing.
