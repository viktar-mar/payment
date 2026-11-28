# First-payment wait snapshot — offline design

Date: 2026-11-03. Base: `b91982ca143381796ba7433b7688731f295cb883`.
Scope: design and implementation plan only. No implementation or new native authority.
Independent builder check: no important questions remain; this is design review,
not validation of an implementation or a native payment.

## Evidence and goal

Consumed run `a8668b92-5ad5-4e80-86a6-e669d48cc55b` ended with
`BROWSER_WAIT_OBSERVE`, CREATE ACK, no CONFIRM-dispatch journal, confirmed cleanup.
The local [offline analysis](../../../.superpowers/offline-observe-analysis-2026-11-03/analysis.json)
is not versioned. Its durable copy is under the main project's
`test-results/offline-observe-analysis-2026-11-03-a8668b92/analysis.json`.
Two actual-driver/page characterizations prove that deferred `elements.submit()`
and deferred `createConfirmationToken()` both expose CONFIRMING/null provenance.
An existing runner test proves its timeout never reads terminal provenance.
Neither test identifies the native provider/network cause or establishes cash.

Improve the next report with one frozen **last Node-observed pending stage**.
It is a historical diagnostic observation, not a statement of the live provider
state at timeout, a timeout cause, dispatch proof, or payment acceptance.

## Chosen handoff and alternatives

Send only primitive stage changes from the real driver through the dedicated
page to a context-local Playwright binding **before** failure. At the first failed
wait, Node freezes its existing local value and revokes continuation in the same
synchronous stack. It performs no diagnostic read after timeout.

This choice is the builder's conservative design decision. A delayed renderer
message can leave an older stage in Node; the explicit LAST_OBSERVED label is
essential. Missing or unusable observations retain the generic code.

Rejected: reading the closing page after timeout adds an I/O/cancellation race;
retirement-only retention never reaches Node on that path; adding a renderer or
SDK watchdog changes timeout policy. No provider timeout or payment fix is proposed.

## Producer: private handle metadata

Extend the existing private module
`frontend/sdk/src/subscriptions/stripe-payment-attempt-diagnostic.ts` with:

- `StripePaymentAttemptPending = 'SUBMIT' | 'TOKEN'`.
- `setStripePaymentAttemptPending(handle: unknown, value: unknown): void`:
  only SUBMIT, TOKEN and null are recognized; null clears. Invalid values are
  ignored without coercion or property inspection. Deduplicate unchanged values.
- `observeStripePaymentAttemptPending(handle: unknown, listener:
  (stage: StripePaymentAttemptPending | null) => void): () => void`:
  one private observer per suitable handle, no initial replay. Invalid registration
  or an existing observer returns a no-op disposer. A disposer removes only its
  own registration. Invoke listeners synchronously, never await their return;
  swallow synchronous observer exceptions without reading/logging them.

Use WeakMaps; keep failure provenance independent and first-write-only. No new
public handle fields, package/root exports, contracts, IDs, counters, timestamps,
error payloads, DOM metadata, persistent storage or transport API.

The driver announces SUBMIT immediately before its existing submit call and
clears it whenever that guarded await settles, then similarly announces TOKEN
around its existing token call. Clear synchronously on destroy/cancellation.
Preserve original guards before/after awaits, validation correction, one original
client dispatch, receipt/next-action rules and public result/error shapes.
No observer is needed by ordinary SDK consumers.

## Dedicated page: optional, nonblocking observation

The page imports the observer from the same source helper as its existing failure
reader. Keep the existing Vite source alias and Docker inputs unchanged.
Register after the returned handle is assigned and before enabling Pay. The
existing one-Pay coordinator still controls confirmation.

Forward the primitive value to the optional controller binding
`window.__firstPaymentPending`. No tokens, identities or other arguments.
Never await the binding: attach rejection handling to its returned Promise and
return immediately. Missing, throwing or rejected diagnostic transport disables
observation locally and does not change confirmation or expose rejected values.
Do not read accessor properties to locate the optional binding. Dispose the
observer in existing stop/retirement cleanup. The current terminal failure reader,
storage blockers, lifecycle stop and successful OBSERVE behavior remain intact.

## Node receiver: bounded untrusted observation

Install `__firstPaymentPending` beside the existing prepare binding, on the same
browser context. Its handler is synchronous and carries no financial capability.
Accept only after the original prepare and READY and once Node has marked its
original Pay click as started, while the journey remains open. Require the actual
main frame and exact bound demo origin. Ignore pre-Pay or post-close calls.

During the open observation, use this fixed state machine:

| State | Accepted value | Next state | Local pending hint |
| --- | --- | --- | --- |
| NEW | SUBMIT | SUBMIT | SUBMIT |
| SUBMIT | null | BETWEEN | null |
| BETWEEN | TOKEN | TOKEN | TOKEN |
| TOKEN | null | DONE | null |

Any other value, duplicate, order violation, wrong frame or wrong origin while
open permanently disables observation (UNAVAILABLE/null). Never inspect an object
payload or throw its data into the payment path. At most four accepted changes;
no growing history or counters are emitted. Disabled/missing observation has no
effect on the original transaction path.

## Failure cutoff: ordering and late dispatch

Create one journey-local AbortController, compose it with the original parent
signal, and preserve the original absolute deadline. Use these scoped limits at
every existing bounded browser/bridge/prepare/confirm handoff; never broaden a
deadline. The parent owner and public port interfaces remain unchanged.
For a handoff without a limits parameter (such as customer acknowledgement),
recheck the scoped limits before its next dispatch; do not change that public
signature or allow a late acknowledgement to start CREATE.

On the first failure, the latch executes one synchronous sequence:

1. Mark journey failure/abandonment and close observation acceptance.
2. Select and store the first error from closed local constants. Only rejection
   of the original OBSERVE wait may use the local pending hint, while the parent
   is live and before its absolute deadline. Terminal-page errors, navigation,
   bridge failures, aborts and other checkpoints retain their existing identity.
3. Abort the journey-local signal immediately, before yielding or reporting.
   Existing context close is requested through its one shared close Promise.

There is no await, provider work, page evaluation, new timer or output before
revocation. A previously latched error always wins. Closure/draining retains its
existing 15000ms bound; the OBSERVE wait retains its existing 45000ms cap.
Late stage notifications cannot change the frozen error. Late submit/token
completion cannot start a new CONFIRM: route failure guards and the composed
signal must reject before `port.confirm` dispatch. Already dispatched work is not
claimed to be undone; unresolved outcomes remain protected from replay.

Use the existing error/owner/parser/reporter channel. Add only these closed codes:

- `BROWSER_WAIT_OBSERVE_LAST_OBSERVED_SUBMIT`
- `BROWSER_WAIT_OBSERVE_LAST_OBSERVED_TOKEN`

The generic `BROWSER_WAIT_OBSERVE` and every existing code stay valid. Specialize
only when minting that first failed-wait error, never by rewriting an earlier
error. Preserve the one final line shape and 160-character limit for initial and
combined renewal reports; no new owner result fields or success/cleanup authority.

## Proofs and limits

Offline tests must establish both the useful observation and its limits:

- Real driver and real page: deferred SUBMIT/TOKEN, exact primitive sequence,
  no public/API changes, one Pay and one possible confirm, rejected/missing/hung
  observation transport cannot delay the payment path.
- Actual runner with injected transport: receive a stage, reject the wait,
  synchronously revoke, release the provider/bridge later; zero late confirms,
  immutable first code, original close/drain bounds, no post-timeout evaluate.
- Reject forged frame/origin, object/accessor/Proxy payloads, bad order, duplicate,
  pre-Pay/late events; missing or cleared stages give the original generic code.
- Delayed messages: report only the previously received stage, freeze it once;
  do not relabel it as live state. Parent abort/expiry and earlier failures win.
- Preserve all existing terminal diagnostic, validation retry, success,
  original/renewal formatter/parser/reporter and related regressions.

No Docker/daemon, Stripe/Sandbox/network, real browser, Vite server/build,
Java/Maven/SQL, install, Python, archives, keys, env.local or raw native/browser
logs during implementation. Use installed Node/Vitest/jsdom and inert transports.
No backend, payment policy, dependency/lock, callback or cash-evidence changes.
Historical native stage/cause and stage5B acceptance remain unknown/not accepted.

Implementation requires its reviewed plan; this design grants no new native run,
payment, refund, retry or recovery. Existing consumed runs and DISPATCHING stay
untouched. See the [implementation plan](../plans/2026-11-03-first-payment-timeout-snapshot.md).
