# Owned grace: exact boundary continuation

2026-11-14. Offline implementation specification.

## Scope

Authenticate the existing committed G−1 proof at the original grace deadline G and G+1. Actual get/access must deny unpaid access at both instants. Keep the original V38 preparation, V39 G−1 operation/receipts, current writer READY2 sample, ordinary subscriptions, all money/debt/journal/callback effects unchanged. This finite two-phase continuation is not another preparation framework, recovery implementation or complete 5B acceptance.

No local Docker, DB/SQL, application/browser/socket/provider/Stripe/Sandbox/native/financial execution, Python/install, key/archive access or replay of historical/DISPATCHING operations. Workers source-read/edit/report only, with no execution/compiler/tests/Git/network/runtime/children. Root may compile all sources and execute explicit source-audited inert Jupiter selections using cached jars; genuine DB proof runs only in GitHub CI. Preserve worktree/reports and all existing data; no cleanup/merge/PR.

## Closed lineage

| Phase | Explicit number | Operation kind | Immediate predecessor | Target | Suffix |
| --- | --- | --- | --- | --- | --- |
| AT | 2 | OWNED_GRACE_AT_CLOCK_ADVANCE | committed original V39 READY G−1 | G | at |
| AFTER | 3 | OWNED_GRACE_AFTER_CLOCK_ADVANCE | committed AT READY G | G+1 | after |

Both use original V38 preparation UUID as resource and the same clock reference. Separate kinds preserve all existing account/kind/resource and account/kind/provider-reference uniqueness indexes. Caller explicitly nominates phase; duplicate AT never infers AFTER. Original operation is never reopened. Missing/partial/foreign/same-xid/stopped predecessors refuse. Original graceLastSecondTarget rules/exact math remain: READY2<G−1<G<G+1<N, whole-second boundaries, original MONTH56day/YEAR730day ceiling. Target is predecessor frozen+1 AND equal to saved G/G+1. Fresh entry cannot precede predecessor observedAt. Historical production budget expiry does not invalidate committed evidence.

New `OwnedFailedGraceBoundary` descriptions: `Phase`; `ReadyOrigin(readyId,operationId,invocation,operationFingerprint,TestClockReady observation,observedAt)`; `BoundaryContext(PreparationContext original,Phase phase,originalGraceReadyId,ReadyOrigin predecessor,long target,String operationKey,String fingerprint)`; `BoundaryAck(Phase phase,preparationId,ProviderOperation operation,invocation,claimant,deadline,TestClockAck observation,observedAt,acknowledgedAt)`; `BoundaryReady(id,admissionId,BoundaryAck ack,TestClockReady observation,observedAt)`. No description is a capability: authenticate full persisted/current lineage on every use. Redact toString/errors.

Key `billing-failed-grace-boundary-v1:<admission>:at|after`. Fingerprint uses existing canonical UTF8 length scalars (null=-1:), microsecond UTC Instants, SHA256, exact ordered vector:

1. domain `billing-failed-grace-boundary-v1`, admissionID, preparationID, preparationFingerprint, originalV39READYID;
2. explicit phaseNumber, kind.name;
3. immediate predecessor readyID, operationID, invocationID, operationFingerprint;
4. predecessor reference, object, livemode, name, created, deletesAfter, frozenTime, status, nullable advancingTarget, observedAt;
5. saved graceDeadline, derived target, operationKey.

No fresh invocation identity/time in the hash: pristine naked RESERVED must authenticate before claim exists. Validate actual fields as well as fingerprint.

## Service and SDK

Adapter `advance(Admission,GraceLastSecondPreparation,Phase):BoundaryReady`. New service inspect/claim/inspectClaim/acknowledge/inspectAck/recordReady/uncertain follows existing independently committed transitions, ambient refusal and original failures.withGraceLastSecondInTransaction before/after current brackets. New default-denied store includes beforeFreshEntryInTransaction(a,p,phase,b), requirePredecessorInTransaction(original,phase,b), and phase equivalents of original transition methods. Only new phase fresh entry takes its exclusive gate; old fresh-entry path stays refusing.

Extract only clock wire mechanics to package-private `OwnedGraceClockExchange`, shared by original and new adapters. Parent-thread hooks: inspectClaim, acknowledge(observation,observedAt), inspectAcknowledged, recordReady(observation,observedAt). Keep credentials/ownership/store/current/cleanup in adapters/service, never SDK worker/helper. Retain returned ACK only after committed hook returns. Preserve original public APIs and all original 88 SDK regressions. Bounds:30s UTC+monotonic invocation,60s lease, original retryBefore, zero retries, pinned API,2s connect/read/whole call, max12GET polls/250ms spacing with nextcall room; one POST own key/ref/target. Strict duplicate/trailing/malformed JSON and clock tuple checks; predecessor-ready preflight GET, independent ready GET even readyPOST. No postcommit expiry on returned READY. Loser/borrowed claim never SDK/cleanup.

All Java generic discovery AND locked recheck/reserve/claim/ACK/UNKNOWN/bind/recovery paths exclude both kinds before credentials/client. Preserve indexes, existing kinds and runtime allowlists.

## V40 persistence/concurrency

Preserve V30–V39 bytes. Four immutable tables `stripe_billing_failed_grace_boundary_{claims,acks,ready,cleanup}`. Claims unique(preparation,phase), unique(admission,phase), unique operation/invocation and exact composite identity/claimant/deadline FK targets. ACK/cleanup reference exact original claim including owner/deadline. READY unique(preparation,phase)/(admission,phase)/operation/invocation and exact ACK FK. All retain existing clock/audit/actualdatabase-xid shapes. Add unique originalV39READY(id,preparation,admission) and exact FK from each claim. AT predecessor_phase/ready=null; AFTER exact same-root AT READY composite FK, add cycle FK after table creation. Strict phase/kind/target/reference/chronology/fingerprint/independentproducer-xid proof, immediate/deferred constraints plus independent JPA beforeCommit.

Fresh entry/raw INSERT validates immutable supplied admission/preparation first; authenticated V38 row FOR NO KEY UPDATE NOWAIT then fresh RC phase read BEFORE lifecycle locks. New evidence—including naked RESERVED—refuses duplicate/skip/conflict. Continuation KEY SHARE compatible as needed. Existing lock order and immediate FK NOWAIT preacquisition preserved. Every operation authenticates known physically bound nonautocommit JPA RC connection via OwnedGraceCallerConnection; no alternate pool/acquisition. Recheck current/phase after locks. Writer current sample stays READY2. Claim+RESERVED0→DISPATCHING1 atomically independently commit; ACK2 then READY independently commit with distinct actual xids.

Cleanup narrow original-owner only with existing2s lock/SQL limits even after expiry/drift: atomically DISPATCHING→UNKNOWN2+cleanup+UNCERTAIN stop, OR retain exact committed ACK2 and cleanup/stop without ownREADY. Ambiguous ACK reload exact original only; successful READY terminal. Extend stop verifier only with exact V40 cleanup alternative: preserve upstream FOR UPDATE NOWAIT first then fresh claims; exact prep/admission/phase/op/invocation/claimant/deadline/timestamp/currentcleanupxid, independent producer xids and no ownREADY. Preserve V34/V39 rules, no broad completedREADY exemption. AT success may precede AFTER uncertainty, never AT cleanup.

Expose bounded V40 STABLE original/phase committed-evidence helpers with snapshot UTC/xid to share reader/writer proof. No VOLATILE writer-lock function in reader. Writer takes fresh statements after locks; reader one snapshot.

## Exact read and acceptance

Extend existing test-only OwnedFailedGraceBillingTime in place. Preserve known physical caller/isolation, full mapped current Subscription equality, owner-or-actual-ID candidate discovery, trusted internal fallback and one nonlocking RC snapshot. Independently discover both phase operations and all receipts: no later evidence keeps old READY2/G−1/ordinary behavior; any AT evidence requires complete original+ATREADY or refuse; completeAT/noAFTER gives storedG; anyAFTER evidence requires complete original+AT+AFTERREADY or refuse. No lower fallback/latest-row/sequential snapshots. All prior xids!=actualreader; own uncommitted op/claim closes fallback and same-xidREADY never authorizes (including early deferred drain). Deadlines constrain production, not historical validity.

The single-snapshot guarantee covers the failed-admission tier, including original/AT/AFTER classification and mapped current comparison. A zero candidate result under both discovery arms permanently exits that tier for the call: delegate directly to the trusted concrete first-renewal→owned-subscription/ordinary chain on the same authenticated template. Do not invoke OwnedFailedRenewalBillingTime.sample for read fallback, since that would rediscover a failed admission in a later snapshot and return READY2 without original/boundary proof. Preserve the historical writer/unscoped chain unchanged. An explicit admission-arrives-between-statements regression must show the failed tier is not revisited; original fallback assertions change only where they encoded this unsafe rediscovery.

At G/G+1 unchanged debtOPEN and original unpaid bill: actualget/access EXPIRED,allowed=false,validUntil=null,emptyactions,exact evaluatedAt. Full current account/site/customer/credentials/policy/source/debt/G/bill/paidThrough/entitlement/revision/audit/oldreceipts preserved; no cash/journal/event/outbox/callback effects. No recovered PAID proof in this contract.

Tests first, Root RED/GREEN. Inert fixtures keep actual services and handwritten external persistence/SDK boundaries; expectations literal/independent, no source-text tests. Genuine CI builds real originalSDK/store G−1 then newSDK/store AT/AFTER, independently committed observations, repeated actual get/access/full complete row snapshots including four newtables, no read deltaexclusions. Committed reads remain valid after actual invocation/lease expiry without rewriting evidence/securityclock. Negativebothphases: nakedRESERVED/partialACK/samexidREADY/earlydeferreddrain, current/credential/clock/owner/root/predecessor mismatch, competingphase/skip/borrowedcleanup, beforeCommit/atomicrollback, lockbudgets and foreignphysicalcaller. Create actors before read snapshots; catch expected refusal inside outer REQUIRED where needed. Producer deltahelpers retain unrelated phase/sentinelrows. Append CurrentSchemaVersions40 and exact lock-order SQL exclusions without weakening assertions. Full CI all4jobs+dedicatedlane+bothpackaging. Native failed/grace NOT_RUN; full5B NOT_ACCEPTED until remaining separately authorized work.
