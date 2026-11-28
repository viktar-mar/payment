import {readFile} from 'node:fs/promises';
import {isProxy} from 'node:util/types';
import {billingPortalFixturePolicy,billingUuid,buildBillingPortalPolicyRequest} from './stripe-billing-provision.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingPortalOrigin} from './stripe-billing-evidence.mjs';
import {readOnlyBillingPortalSql} from './stripe-billing-portal-evidence.mjs';
import {exactRecord} from './stripe-billing-failed-renewal-response.mjs';

const proofFile=new URL('./stripe-billing-portal-proof.sql',import.meta.url);
const graphIds=['accountId','siteId','customerId','subscriptionId','operationId','priceId'];
const utc=/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z$/;
const hex=value=>typeof value==='string'&&/^[0-9a-f]{64}$/.test(value);
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const need=value=>{if(!value)throw billingError('PORTAL_EVIDENCE');};

function snapshotReceipt(value,keys,allowGeneration=false) {
  need(!isProxy(value)&&object(value));
  // The synthetic Chromium fixture also carries its document generation.
  if(allowGeneration&&Object.hasOwn(value,'generation')) {
    const receipt=exactRecord(value,[...keys,'generation']);
    need(Number.isSafeInteger(receipt.generation)&&receipt.generation>=0);
    return receipt;
  }
  return exactRecord(value,keys);
}

function initialWitness(scope,{baseline,origin,provider}={}) {
  const graph=baseline?.graph;
  need(graph&&graphIds.every(k=>billingUuid(scope[k])&&scope[k]===graph[k]));
  need(graph.runId===scope.runId&&graph.expectedSource===scope.expectedSource&&graph.priceVersion===1
    &&Number.isSafeInteger(graph.periodStart)&&Number.isSafeInteger(graph.periodEnd)
    &&graph.periodStart>0&&1000*graph.periodStart<=Date.now()&&Date.now()<1000*graph.periodEnd);
  need(baseline.cashVerified===true&&baseline.journalVerified===true&&baseline.signedCallbacksVerified===true);
  need(provider?.providerVerified===true&&provider.initialCashVerified===true&&provider.lineCount===1
    &&provider.allocationCount===1&&provider.chargeCount===1);
  const ids=[origin?.billingRecordId,origin?.paymentId,origin?.cashEventId,origin?.paidEventId];
  need(ids.every(billingUuid)&&new Set(ids).size===ids.length&&Number.isSafeInteger(origin.revision)&&origin.revision>=0);
  need(object(origin.callbackDigests)&&hex(origin.callbackDigests[origin.cashEventId])&&hex(origin.callbackDigests[origin.paidEventId]));
  return Object.freeze({billingRecordId:origin.billingRecordId,paymentId:origin.paymentId,revision:origin.revision,
    periodStart:graph.periodStart,periodEnd:graph.periodEnd,callbackDigests:origin.callbackDigests});
}

function checkCallbacks(initial,inspection) {
  const expected=new Map(Object.entries(initial.callbackDigests));
  need(inspection?.duplicates===0&&inspection.count===expected.size&&Array.isArray(inspection.events)
    &&inspection.events.length===expected.size);
  for(const event of inspection.events) {
    need(event?.count===1&&expected.has(event.event_id)&&expected.get(event.event_id)===event.sha256);
    expected.delete(event.event_id);
  }
}

function verifyPolicy(configuration,scope,receipts,policyAck) {
  need(receipts?.account?.id===scope.accountId&&receipts.site?.id===scope.siteId&&receipts.product?.id===scope.productId
    &&receipts.price?.id===scope.priceId&&receipts.site.key===scope.siteKey);
  const request=buildBillingPortalPolicyRequest(configuration,receipts);
  const policy=request.validateResponse(policyAck);
  const target=new URL(policy.portalReturnUrl);
  need(target.origin===scope.endpoints.demo&&target.searchParams.get('api_base_url')===scope.endpoints.backend);
  return policy;
}

function sqlRequest(query,scope,initial,policy,keyDigest,expiresAt) {
  const timestamp=seconds=>new Date(1000*seconds).toISOString();
  const args=Object.freeze([scope.accountId,scope.siteId,scope.customerId,scope.subscriptionId,scope.operationId,
    initial.billingRecordId,initial.paymentId,scope.priceId,policy.portalPolicyVersion,keyDigest,policy.portalReturnUrl,
    expiresAt,initial.revision,timestamp(initial.periodStart),timestamp(initial.periodEnd)]);
  return Object.freeze({mode:'read only',query,args});
}

/** Join owned initial payment evidence, one Portal opening, and the original subscription after return. */
export function createBillingPortalFlow({configuration,scope,policyReceipts,policyAck,fixture,bootstrap},dependencies,limits) {
  const runLimits=Object.freeze({deadline:limits?.deadline,signal:limits?.signal});
  const bounded=(work,maximum)=>billingBounded(runLimits,active=>work(Object.freeze(active)),maximum);
  let status='NOT_STARTED',code=null,portalOpened=false,returnObserved=false;
  const resources=()=>Object.freeze({status,code,portalOpened,returnObserved,portalAccepted:status==='PASSED'});

  async function run() {
    if(status!=='NOT_STARTED')throw billingError('PORTAL_ALREADY_RUN');
    status='RUNNING';
    let phase='PORTAL_INITIAL',failure=null;
    try {
      billingCheck(runLimits);
      need(billingUuid(scope?.runId)&&scope.runId===configuration?.runId&&scope.expectedSource===configuration.expectedSource
        &&hex(scope.createKeyHash)&&hex(scope.confirmKeyHash)&&scope.createKeyHash!==scope.confirmKeyHash);
      const owned=Object.freeze({...scope,endpoints:Object.freeze({...scope.endpoints})});
      const native=configuration.selectedCase==='initial_portal';
      const initial=await bounded(async active=>{
        const evidence=await dependencies.verifyInitial(owned,active);
        return initialWitness(owned,native?{...evidence,origin:billingPortalOrigin(evidence?.baseline)}:evidence);
      },native?120000:undefined);

      phase='PORTAL_POLICY';
      const policy=native?billingPortalFixturePolicy(fixture,owned):verifyPolicy(configuration,owned,policyReceipts,policyAck);
      need(policy);
      const checkPolicy=()=>{if(native)need(billingPortalFixturePolicy(fixture,owned)===policy);};
      const query=await bounded(()=>readFile(proofFile,'utf8'));
      need(query.length>0);
      let proof;
      async function checkSql(active) {
        billingCheck(active);
        checkPolicy();
        const rows=native
          ?await readOnlyBillingPortalSql({bootstrap,request:proof},active,{connect:dependencies.connect})
          :await dependencies.readOnlyPortalSql(proof,active);
        billingCheck(active);
        checkPolicy();
        need(Array.isArray(rows)&&rows.length===1&&rows[0]?.portal_verified===true);
      }

      phase='PORTAL_OPEN';
      let openingStage='READY',portalKeyDigest;
      const opening=Object.freeze({});
      const hosted=await bounded(async active=>{
        function checkOpening(expected) {
          billingCheck(active);
          checkPolicy();
          need(status==='RUNNING'&&phase==='PORTAL_OPEN'&&!failure&&openingStage===expected);
        }
        function refuse(checkpoint) {
          if(status==='RUNNING')failure??=checkpoint;
          throw billingError(checkpoint);
        }
        const port=Object.freeze({
          returnUrl:policy.portalReturnUrl,
          async beforeDispatch(keyDigest) {
            try {
              checkOpening('READY');
              need(hex(keyDigest)&&keyDigest!==owned.createKeyHash&&keyDigest!==owned.confirmKeyHash);
              openingStage='CLAIMED';
              if(native) {
                need(await dependencies.assertListener(active)===true);
                checkOpening('CLAIMED');
              }
              const recorded=native?await fixture.beforePortalOpening(owned,keyDigest,active)
                :await dependencies.recordOpeningIntent(Object.freeze({portalKeyDigest:keyDigest,returnUrl:policy.portalReturnUrl}),active);
              need(recorded===true);
              checkOpening('CLAIMED');
              portalKeyDigest=keyDigest;
              openingStage='DISPATCH_READY';
              return true;
            } catch { return refuse('PORTAL_DISPATCH'); }
          },
          async observe201(receipt) {
            let checkpoint='PORTAL_201';
            try {
              checkOpening('DISPATCH_READY');
              const {expiresAt}=snapshotReceipt(receipt,['expiresAt']);
              need(typeof expiresAt==='string'&&utc.test(expiresAt)&&Date.parse(expiresAt)>Date.now());
              openingStage='VERIFYING';
              proof=sqlRequest(query,owned,initial,policy,portalKeyDigest,expiresAt);
              checkpoint='PORTAL_PROOF_BEFORE_OPEN';
              await checkSql(active);
              checkOpening('VERIFYING');
              need(Date.parse(expiresAt)>Date.now());
              if(native) {
                checkpoint='PORTAL_ACK';
                need(await fixture.acknowledgePortalOpening(owned,portalKeyDigest,active)===true);
                checkOpening('VERIFYING');
                need(Date.parse(expiresAt)>Date.now());
              }
              openingStage='PROVEN';
              return opening;
            } catch { return refuse(checkpoint); }
          },
        });
        const raw=await dependencies.openPortal(port,active);
        checkOpening('PROVEN');
        const opened=snapshotReceipt(raw,['opening','tab','document','origin','providerMutationCount'],!native);
        need(opened.opening===opening&&object(opened.tab)&&object(opened.document)
          &&opened.origin==='https://billing.stripe.com'&&opened.providerMutationCount===0);
        return Object.freeze({tab:opened.tab,document:opened.document});
      },60000);
      portalOpened=true;

      phase='PORTAL_RETURN';
      const returned=await bounded(async active=>{
        const raw=await dependencies.observeMerchantReturn(hosted,active);
        billingCheck(active);
        const observed=snapshotReceipt(raw,['tab','document','sourceDocument','initiator','method','url','providerMutationCount'],!native);
        need(!failure&&observed.tab===hosted.tab&&observed.sourceDocument===hosted.document&&object(observed.document)
          &&observed.document!==hosted.document&&observed.initiator==='PROVIDER_DOCUMENT'&&observed.method==='GET'
          &&observed.url===policy.portalReturnUrl&&observed.providerMutationCount===0);
        return Object.freeze({tab:observed.tab,document:observed.document});
      },60000);
      returnObserved=true;

      phase='PORTAL_RETURN_READ';
      await bounded(async active=>{
        const raw=await dependencies.readReturnedSubscription(returned,active);
        billingCheck(active);
        const read=snapshotReceipt(raw,['tab','document','siteId','customerId','subscriptionId','method','status',
          'billingStatus','access','revision','paidThrough']);
        const access=snapshotReceipt(read.access,['allowed','reason']);
        need(!failure&&read.tab===returned.tab&&read.document===returned.document&&read.method==='GET'&&read.status===200
          &&read.siteId===owned.siteId&&read.customerId===owned.customerId&&read.subscriptionId===owned.subscriptionId
          &&read.billingStatus==='ACTIVE'&&access.allowed===true&&access.reason==='PAID'
          &&read.revision===initial.revision&&Date.parse(read.paidThrough)===1000*initial.periodEnd);
      });

      phase='PORTAL_PROOF_AFTER_RETURN';
      await bounded(async active=>{
        await checkSql(active);
        if(native) {
          checkCallbacks(initial,await fixture.inspectCallbacks(active));
          billingCheck(active);
          checkPolicy();
        }
      },native?60000:undefined);
      billingCheck(runLimits);
      need(!failure);
      status='PASSED';
      code='OK';
    } catch {
      status='STOPPED';
      code=failure??phase;
    }
    return resources();
  }
  return Object.freeze({run,resources});
}
