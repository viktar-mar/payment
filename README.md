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

The Fake provider covers local one-time payments, donations and subscriptions, including recovery and callback flows. Stage 5A Stripe form readiness has been observed, but complete real Sandbox payment, refund and callback acceptance remains **pending**. Offline fixtures and browser UX do not establish financial fulfillment or live-payment readiness.

Historical notes also describe a successful EUR20 test payment and a verified EUR7 refund; the current EUR13 remainder outcome is unresolved. Do not repeat a refund based on missing local evidence. The [Stage 5A acceptance matrix](docs/stage-5a-acceptance.md) separates implementation, historical observations, fresh test results and remaining gates. Further recovery diagnostic development is frozen.

Stage 5B Stripe Billing/subscriptions and Customer Portal, then Stage 5C expanded reconciliation, payment methods and cloud readiness remain future work. See the [Stage 5 roadmap](docs/superpowers/specs/2026-10-14-stripe-sandbox-design.md#1-последовательность-этапа) and the [acceptance boundaries](docs/local-development.md#stage-5a-offline-regression-and-explicit-sandbox-acceptance).

## Checks and guides

[Offline CI](.github/workflows/ci.yml) runs default Maven verification (including synthetic Stripe adapter/Cucumber fixtures), SDK and Admin tests/typechecks/builds, Admin lint, demo return-unit tests/build, native Node synthetic support tests, the separate offline Chromium return/privacy suite and all infrastructure Node tests. It installs dependencies with frozen lockfiles and requires no repository secrets or real Stripe opt-in.

Full owned Fake end-to-end acceptance needs the separate Stage 4 clock/runtime harness and is outside this first pipeline. Real Sandbox acceptance is also outside CI. Local verification results do not establish a successful GitHub-hosted workflow run. For exact commands and ownership requirements, see [CI coverage](docs/local-development.md#secret-free-ci-coverage) and the workflow above.

Use the [Admin guide](frontend/README.md), [SDK guide](frontend/sdk/README.md), [demo source](examples/demo-site/) and [local development guide](docs/local-development.md) for integration and advanced testing.
