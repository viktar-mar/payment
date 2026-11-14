# Owned grace boundary continuation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Reachable spec is binding; source-only worker restrictions override test/commit defaults.

**Goal:** Authenticate complete G−1→G→G+1 lineage and prove unpaid access EXPIRED at G/G+1.

**Architecture:** Explicit finite AT/AFTER phases, same V38 resource, two dedicated kinds, V40 receipts, shared narrow clock exchange and existing reader extended in place. No new preparation framework.

**Tech Stack:** Existing Java25, Spring/JPA/JDBC, PostgreSQL and official Stripe SDK fixture; no new dependencies.

**Spec:** docs/superpowers/specs/2026-11-14-owned-grace-boundary-continuation-design.md.

## Global Constraints

- No local Docker, DB/SQL, application, browser, socket, provider, Stripe/Sandbox/native/financial execution. No old operations or DISPATCHING, archives or keys; no Python, installation, default Maven or broad local discovery.
- Workers read/edit/report source only; no execution/compiler/tests/Git/network/runtime/children. Root owns audited cached inert execution, compile-only genuine-source checks, Git publication and exact-head GitHub CI.
- Preserve worktree, reports, existing data and all historical receipts. No cleanup, merge or PR.
- Standing user direction authorizes uninterrupted subagent implementation/review and branch publication after checks; it grants no local runtime or financial authority.

- Preserve V30–V39 bytes, existing uniqueness indexes, original G−1 API/READY2 current writer proof, ordinary behavior, runtime allowlists and verification strength. Native/full5B remain incomplete.
- Each implementer first writes failing behavior tests and reports TESTS_READY. Root audits and runs RED, explicitly releases implementation, then runs GREEN and commits before independent review. Genuine hosted sources compile locally only.

## Task 1: Closed model, service, SDK exchange and dedicated isolation

**Depends:** immutable baseline03f37570acaae053ae774ab476535daafe0d3f01. New persistence defaults deny until Task2.

**Files:** create OwnedFailedGraceBoundary model, OwnedFailedGraceBoundaryStore port, OwnedFailedGraceBoundaryService, StripeBillingOwnedFailedGraceBoundaryAdapter, package-private OwnedGraceClockExchange. Narrowly refactor original StripeBillingOwnedFailedGraceAdvanceAdapter. Add both spec kinds to ProviderOperation and all Java generic/recovery guards. Add model/service/inertfixture/SDK/isolation tests alongside existing equivalents. No V40 or reader edits yet.

**Implement:** exact spec phases, types/signatures, deterministic key/fingerprint vector, predecessor+1 and savedG/G+1 validation. Default-denied port with beforeFreshEntryInTransaction and requirePredecessorInTransaction and phase transitions; service independent committed current-bracketed inspect/claim/inspectClaim/ack/inspectAck/recordReady/uncertain, original failures component retained in inert fixture. Shared clock-only exchange preserves all original SDK bounds/order/88 regressions; parent hooks never worker DB; returnedACK/cleanup ownership/lateREADY semantics unchanged. Generic discovery AND locked rechecks/mutations/recovery refuse both kinds before credentials/client. No index/allowlist changes.

**Tests first:** literal targets/hash vector/root/phase/overflow/current mismatch; explicit duplicate phase never infernext; expiredhistorical predecessor remainsdescription; defaultdenied/ambient/transactionbracket/currentdrift; loser zeroSDK/cleanup, exactowner ACK/uncertainty/lateREADY. SDK correct preflight/key/target/onePOST/independentGET, all bounds/raw malformedduplicate/trailing/fixedversion/noretry, wrong AFTERpredecessor. Both newkinds genericisolation. Source-only TESTS_READY report before implementation; Root's audited RED gate then release.

**Verify/review:** Root compileallmain+genuine, auditedexplicitnewinert+originalservice/SDK/transactionregression; exacthash/countreceipts. Root commits `feat: add owned grace boundary clock continuation`, independently reviews spec+quality and fixes Important/Critical. Genuine persistence proof remains pendingTask2/3.

## Task 2: V40 immutable evidence, ownership, concurrency and stop

**Depends:** Task1 model/port/service/adapter exactinterfaces. No alternateprotocol.

**Files:** V40__stripe_billing_owned_grace_boundaries.sql, JdbcOwnedFailedGraceBoundaryStore, narrow callerhelpers onlyifneeded, CurrentSchemaVersions, exactgenericSQLrecorder OwnedSubscriptionLockOrderTest, inertstoretransactiontests and actual OwnedFailedRenewalIntegrationTest phaseproducer/negative/race helpers. Genuine sources compileonlylocally.

**Implement:** specfourimmutabletables/PK/unique/compositeFK/phase/clock/audit/actualxid guards, additiveoriginalREADYidentity+rootFK and same-root ATpredecessorforAFTER. Freshgate originalV38NOWAITrow→freshRCphasequery beforelifecycle; nakedRESERVEDrecognized, continuationcompatibleKEYSHARE and existinglifecycle/FKNOWAITorder. PhysicalJPAnonautocommitRCcaller and independentbeforeCommit plus immediate/deferreddatabaseproof. CurrentwriterstaysREADY2. Atomic pristineRESERVED0→DISPATCHING1+claim, independentlycommittedACK2andREADY, actualdistinctxids/strictchronology. Additiveallowedkind/rawmutationguards preserveindexes/V30–V39bytes. Narrow2scleanup+UNCERTAINstop, ACKpreservation/ambiguity/lateREADY rules; extendstoponlyexactV40alternative preservingupstreamNOWAITfirst and oldV34/V39semantics. Add boundedSTABLEoriginal/phasecommittedproofhelpers forTask3one-snapshotreader (snapshotUTC/xid), writerfreshchecksafterlocks. Append schema40/exactgenericSQLexclusions.

**Tests first:** inertrealJDBCstore withhandwrittenJPA/connectionholders, beforeCommit/foreignpool/isolationpreSQL, exactbindings/rowshape/noadoption/losercleanup. Hostedgenuine actualSDK/storeAT+AFTERpositive; root/phase/target/current/clock/chronology/xid/FK/atomicitynegative; nakedRESERVED/ACK/samexid/earlydrain; contendergatebeforeheldlifecycle/skiploserzeroSDK; ownercleanupafterexpiry/drift/ACKpreservation/borrowedrefusal/lateREADYterminal, boundedlock proof whileholderactive. Producerdeltahelpers retainunrelatedphases/sentinels. TESTS_READY→RootauditedRED/compileonlygenuine→release.

**Verify/review:** Rootcachedinertnewstore/caller/isolation+relatedregressions and allmain/genuinecompile. Commit `feat: persist owned grace boundary evidence`, independenttaskreview/fixes. RecordSQLproofhostedpending; selectorunchangeduntilTask3.

## Task 3: Single-snapshot exact access proof and acceptance scope

**Depends:** Task2STABLEhelpersandproducerAPI. Keep existing OwnedFailedGraceBillingTime as soleexactselector.

**Files:** existingtest-onlyselector/inerttests/exactservicetests, genuine OwnedFailedRenewalIntegrationTest, relevant5Bacceptance/spec/README scope. Runtimeallowlistsunchanged.

**Implement:** onesnapshotindependentphaseoperation/receipt discovery and fulloriginal→AT→AFTERproof. Noboundaryevidencepreservesoldbehavior; anyATrequirescompleteATREADYelserefuse; ATcomplete/noAFTERreturnsstoredG; anyAFTERrequirescompleteAT+AFTERREADYelserefuse, neverlowerfallback/latest/sequentialqueries. Preservephysicalcaller/isolation/fullmappedcurrent/ownerORactualID/trustedfallback; allproducerxids!=reader; ownuncommittedop/claimclosesfallbackandsamexidREADYrefusedafterearlydrain; historicalbudgetexpiryallowed. NoVOLATILEwriterfunctions/securityclockrewrite.

Zero failed candidates permanently exits that tier in this call. Read fallback goes directly to concrete trusted OwnedFirstRenewalBillingTime→OwnedSubscriptionBillingTime on authenticated jdbc, never rediscovers failed admission via OwnedFailedRenewalBillingTime.sample. Writer/unscoped delegate unchanged. Add admission-arrives-between-statements regression, preserving strict fallback assertions except unsafe failed-tier rediscovery expectations (binding spec clarification from builder check).

**Tests first:** inertrealselectorrows/onesnapshot/G/G1exacttime, partial/foreign/stopped/currentmismatch/secondaryconnection; actualget/accessEXPIRED/false/null/emptyactionsatliteralG/G1. GenuinefullrealoldSDK/storeG−1→newAT→AFTER, repeatedactualget/access/fullrowsnapshotsincludingfournewtables withnoreadexclusions, unchangeddebtOPEN/businesscashjournalcallbackaudit. Actorsbefore snapshots, expectedrefusalinsideouterREQUIRED. Bothphasecommittedreadssurviveactualbudget/leaseexpirywithoutrewriting. Addmissingnegative/racecoveragewithoutduplicatingTask2. TESTS_READY→RootRED→release. Documentnewhostedboundaryproof vsremainingrecovery/native; nevermarkfull5Bcomplete.

**Verify/review/publish:** Rootauditedexplicitinertnew+relatedregression/allmain+genuinecompile; commit `test: prove access at owned grace boundaries`. Independenttaskreview thenbroadwholebranchreview onmostcapablemodel againstbaseline/exactrange. Fixwithtests+scopedreviews, preserveworkspace/reports. Pushsameisolatedbranch, verifyexactheadfullworkflowall4jobs+dedicatedownedlane+bothpackaging. Hostedfailure→regressionfix/review/push/exactCIuntilgreen. No localruntime. Finalreportsprovenbehavior/CIcounts/links, remainingnative/recovery and actualRulingsinorderwithcosts.
