# Payment admin

## Stage 4 subscriptions (local Fake provider)

Enable the Site subscription policy, publish a subscription Price, and configure explicit same-Product/currency transitions. Published Price versions and invoice policy snapshots are immutable. Policy changes affect future operations, not an existing invoice's fixed grace deadline. Enablement cannot be switched off while a non-terminal subscription or unresolved create/cancel still exists.

The Site-filtered subscription list links to lifecycle history, billing records and actual Payment/refund pages. A submitted command, action completion, or UNKNOWN result is not proof of payment or cancellation: refresh/reconcile the original operation. Do not issue a new financial command to resolve a timeout. Refunds never cancel the subscription. Method setup neither pays debt nor resumes a paused subscription.

If a subscription detail request or action reports an expired/revoked Admin session, its details, history and open intent are discarded. Sign in again using the displayed link; late responses cannot restore that view's authority or automatically retry a financial command.

Local Fake controls require Admin session, CSRF and idempotency, and operate only on the pinned FAKE account. ADVANCE moves Fake provider time, not production application or authentication time. The acceptance launcher supplies a separate test-only business clock and must not be deployed. Simulated Portal navigation is not a real provider portal. Stripe and real payment-method availability remain Stage 5 work.

Independent pnpm workspace; no backend source imports. Requires Node.js 22.12+ and pnpm 11.1.3.

From this directory, run `pnpm install`, then `pnpm --filter admin dev`. Set the server-only `BACKEND_INTERNAL_URL` to the backend origin (default `http://localhost:8080`). Production: `pnpm --filter admin build` then `pnpm --filter admin start`. The standalone build is also available under `admin/.next/standalone`; copy the generated static assets alongside it when packaging a container.

The browser talks only to `/api/backend/...`. Run the admin and backend with HTTPS and secure cookies in production. For local HTTP, configure the backend with `PAYMENT_SECURE_COOKIE=false`. Session cookies use `Path=/` so both Next navigation and BFF requests receive them; the backend remains responsible for session validation. Existing cookies issued with the old `/api/admin` path should be cleared when upgrading a local session.

An active payment account must be provisioned through the backend API before site creation. The admin supports site creation/listing, product creation, draft price creation, and confirmed price publication. Publish and archive state are backend owned; this stage exposes no archive mutation endpoint.

Checks: `pnpm --filter admin lint`, `pnpm --filter admin typecheck`, `pnpm --filter admin test --run`, `pnpm --filter admin build`. With the backend running on port 8080, regenerate its contract using `pnpm --filter admin generate:api`. Do not edit `schema.d.ts` manually.
