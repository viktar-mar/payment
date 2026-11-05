# Failed renewal, fixed grace and explicit recovery — offline component

## Purpose and boundary

Prepare the next Stage 5B scenario without running Docker, Stripe, databases or a browser. The user requested uninterrupted implementation with subagents, tests and independent review. This is an **offline test-support component**, not a runnable native acceptance lane. Full Stage 5B remains NOT_ACCEPTED.

The successful owned initial EUR20 + first renewal EUR20 run `e7080356-9c8c-4dd8-9ed4-b9ee94e57039` used source `a59dad7fd979cea774537210e76ed98f95f334a8`, exited zero, proved both cash/journal/callback gates and confirmed cleanup. Its result does not cover renewal failure or recovery. Preserve its receipt and every historical run.

## Why a separate component

The existing first-renewal lane requires Pending draft then Paid, two advances and exactly two paid invoices. Its admission, publication, SQL and isolated billing-time contracts intentionally exclude debt/recovery. Ordinary Stripe ownership rejects owned TestClock customers; isolated query projection hides financial commands, and isolated facts reject recovery observations. Do not loosen these guards or label an ordinary recovery command as a supported clock recovery path.

The next useful deliverable is a small pure Java test-support scenario checking observations with the existing domain classes. It has no runtime endpoint, adapter, launcher, Spring registration, SQL, provider SDK call or production classpath export. It validates supplied synthetic domain observations; it cannot authenticate provider facts or establish financial acceptance. No generic diagnostic framework or new dependency is needed.

## Fixed scenario

Use one fresh synthetic owner/source/Account/Site/Customer/Product/Price/Subscription, EUR2000 MONTH, quantity1, trial0 and unchanged terms. Original paid period is [F,E), failed renewal is [E,N), fixed grace G=E+259200 seconds. Pick N>G+1. Operational UTC U is independent of business observation time and stays unchanged throughout the fixture.

Stages are single-use and ordered:

1. INITIAL_PAID: ACTIVE, original paidThrough E, exactly one positive local payment EUR2000, initial bill CASH.
2. FAILED_RENEWAL: PAST_DUE, original paidThrough E and entitlement unchanged, the same owned renewal bill OPEN, no new positive cash, debt for [E,N), captured grace G, GRACE access at E+3600.
3. REPEATED_FAILURE: same debt/bill/invoice/period/paidThrough/G despite another observation or future policy change; no new cash.
4. GRACE_LAST_SECOND: at G-1 access is GRACE and validUntil is G.
5. GRACE_EXPIRED: at exactly G access is denied, with no new cash or paid-through extension.
6. METHOD_READY: setup alone leaves the same open debt, paidThrough and denied access unchanged. A method-ready observation is not payment permission or proof of setup ownership.
7. RECOVERY_REQUESTED: one fresh explicit RECOVER operation for the same open bill/invoice, with no cash change or regained access. For this narrow scenario associate the same existing renewal allocation/PaymentIntent; no new invoice, subscription, third positive payment or generic invoice.pay fallback. Reject live/UNKNOWN/DISPATCHING authority.
8. RECOVERED: the same renewal bill becomes CASH, one new successful EUR2000 payment is associated with that bill/invoice/PI, original initial payment unchanged, debt clears, ACTIVE paidThrough N. At G+1 access is PAID. A separate negative test must show a capture after N cannot restore current access.

Use real `Subscription.renewalFailed`, `Subscription.confirmPaid`, `SubscriptionAccessPolicy` and `RecoveryRules` in fixture/test construction and checks rather than a parallel entitlement calculator. Every observation must preserve ownership, immutable price terms and original payment identity. Check business time independently from UTC created/updated audit times; never advance a security Clock to simulate grace.

The component returns only an offline-contract result. It must never return provider/cash/journal/signed-callback verified flags, native acceptance or full-5B acceptance. Missing journal/receiver/SDK proof remains a documented native blocker, not a boolean supplied by a fake driver. A failed transition consumes the component; later valid observations cannot resume it. Concurrent/repeated/out-of-order calls refuse.

## Tests and verification

Tests are pure JUnit, with real domain objects and no integration-test superclass, application context, provider, network or filesystem data dependency. Exercise complete valid transcript; each invalid ownership/terms/bill/period/payment/amount/currency/authority transition; moved grace; cash on failure/setup; exact expiry; same-invoice recovery; duplicate/late calls; and recovery after the debt period expires. Use cached compilation and an explicitly selected pure JUnit launcher only. Existing access-policy and recovery-rule regressions are related tests. No default Maven test invocation because it can start Testcontainers.

## Remaining native work (not implemented by this preparation)

Before any future Sandbox invocation, implement and review separate durable owned failure/debt admission and publication, a scoped later-time selector and explicit recovery authority that leave the original success path closed, verified decline/setup transitions, source-bound runtime packaging/preflight, bounded clock/payment budgets, and actual SQL/provider/journal/signed-callback evidence. Require fresh explicit authorization for its one-use financial budget. Do not import or retry historical payments, refunds, CREATE or DISPATCHING operations.
