# Authenticated owned method completion and same-debt recovery

Design checkpoint: source recon identifies an explicit backend consumer with native browser issuance remaining closed. Implementation follows the companion plan after a builder check.

## Intent

Move beyond descriptive graphs: authenticate and atomically complete a pending owned SETUP through the actual FactService, preserving the original open debt and advancing exactly one observation sequence. Then reserve and publish recovery of that same debt from verified cash on its original invoice/allocation/PaymentIntent.

Use existing Java/hexagonal architecture, PostgreSQL transaction/receipt patterns and the reviewed descriptive readers. Old V34 predicates remain unchanged. A separate append-only transition receipt authenticates the exact successor sequence; no range/latest/fallback proof may authorize it. Ordinary clock-owner rejection, ordinary SETUP and V29 server-payment confirmation remain closed to this new path.

## Execution constraints

No local Docker, Sandbox/provider account call, financial operation, Python, installation, local SQL/application context, old operation/DISPATCHING, key or operational archive access. Tests are explicit socket-free cached javac/JUnit locally; hosted fresh synthetic integration is an authorized publication gate. All source edits use apply_patch. Root owns Git, no worker spawning; sequential implementers, independent task reviews and final whole-change review. Preserve worktree and reports; no interim user approval stops for reversible implementation choices.

No runtime registration, public route, native launch command or acceptance promotion. Native browser capability issuance and actual native setup/confirmation remain separate unexecuted boundaries unless explicitly added to this written scope; no generic capability bypass. This slice remains at existing READY2 E+3600, and supplies no grace-expiry/G+1 time producer. Full Stage5B remains NOT_ACCEPTED.

## Durable transition

Reserve one fresh SETUP from current failed-renewal authority, revalidating management authority, recurring consent, binding, policy and owned READY2 identity. Authenticate the original debt with only that exact operation allowed. Persist the branch reservation permanently; even a terminal failed SETUP must not reopen legacy recovery. Serialize legacy recovery and method reservation on the same admission, with additive mutually exclusive triggers. V34 predicates and V29 remain unchanged.

Persist append-only reservation, METHOD/RECOVERED read claims, completion, successor recovery association and publication. Reuse the existing stop receipt. Capture exact operation versions, original failure revision/observation sequence, debt identity and immutable clock/policy lineage. Authenticate typed provider-step creation, claim and acknowledgement against these persisted receipts; ordinary reservation remains denied for clock owners. Only SETUP_INTENT_CREATE/:SETUP and SUBSCRIPTION_UPDATE/:METHOD may use the new seam, with fingerprints recomputed from saved authority and proof.

Actual FactService publication must consume the captured observation fence and exact expected billing sample. METHOD permits one PROCESSING SETUP, freshly authenticated setup/method ACKs and succeeded remote graph; inserts the APPLIED marker internally, completes SETUP once, preserves revision and all debt/bill/payment/entitlement facts, and consumes exactly one observation sequence. Completion receipt and effects commit atomically. Any failed postcondition rolls back and produces one separate permanent stop.

Successor RECOVER requires that exact completion and its sequence, the original invoice/allocation/PaymentIntent and a fresh reserved operation. It uses the existing recovery-proof persistence through an explicit successor association. Its proof hook is selected explicitly, never by trying legacy and falling back. Actual FactService consumes the next fence, increments revision once, completes RECOVER, clears the same debt and records exactly one renewal cash payment.

## Observation and evidence

Observers remain unregistered. One durable 30-second claim, two-second SDK calls, zero retries, 100 GETs inclusive of clock reads, exact READY2 identity before/after, caller-thread durable checks around every call and final contextual/time check. SDK workers never access persistence. Reuse the reviewed descriptive readers; descriptors themselves grant no authority. Preserve cancellation, interrupted callers, deadline checks and fixed safe diagnostics without raw messages/causes.

Hosted positive integration must reserve SETUP through the new service, create ACK receipts through actual provider-store claim/guard/ack with inert SDK responses, and invoke unchanged setup-proof persistence. SETUP stays PROCESSING until observer → real FactService completes it. Never seed SETUP.SUCCEEDED or direct-insert ACK receipts as positive proof. Then execute successor recovery through the actual fact path. Include rollback, stale fence/version, corruption, branch concurrency and exact-time drift negatives.
