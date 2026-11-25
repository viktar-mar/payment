import {readFile} from 'node:fs/promises';
import {billingUuid,buildBillingPortalPolicyRequest} from '../../../../infrastructure/stripe-billing-provision.mjs';
import {billingBounded,billingCheck,billingError} from '../../../../infrastructure/stripe-billing-limits.mjs';

const proofFile=new URL('../../../../infrastructure/stripe-billing-portal-proof.sql',import.meta.url);
const graphIds=['accountId','siteId','customerId','subscriptionId','operationId','priceId'];
const utc=/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z$/;
const hex=value=>typeof value==='string'&&/^[0-9a-f]{64}$/.test(value);
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const need=value=>{if(!value)throw billingError('PORTAL_EVIDENCE');};

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
    periodStart:graph.periodStart,periodEnd:graph.periodEnd});
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

/** Test composition for the Portal boundary with injected initial, browser, and read-only SQL evidence. */
export function createBillingPortalFlow({configuration,scope,policyReceipts,policyAck},dependencies,limits) {
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
      const initial=await bounded(async active=>initialWitness(owned,await dependencies.verifyInitial(owned,active)));

      phase='PORTAL_POLICY';
      const policy=verifyPolicy(configuration,owned,policyReceipts,policyAck);
      const query=await bounded(()=>readFile(proofFile,'utf8'));
      need(query.length>0);
      let proof;
      async function checkSql(active) {
        const rows=await dependencies.readOnlyPortalSql(proof,active);
        billingCheck(active);
        need(Array.isArray(rows)&&rows.length===1&&rows[0]?.portal_verified===true);
      }

      phase='PORTAL_OPEN';
      let openingStage='READY',portalKeyDigest;
      const opening=Object.freeze({});
      const hosted=await bounded(async active=>{
        function checkOpening(expected) {
          billingCheck(active);
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
              need(await dependencies.recordOpeningIntent(Object.freeze({portalKeyDigest:keyDigest,returnUrl:policy.portalReturnUrl}),active)===true);
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
              need(object(receipt)&&Object.keys(receipt).length===1);
              const {expiresAt}=receipt;
              need(typeof expiresAt==='string'&&utc.test(expiresAt)&&Date.parse(expiresAt)>Date.now());
              openingStage='VERIFYING';
              proof=sqlRequest(query,owned,initial,policy,portalKeyDigest,expiresAt);
              checkpoint='PORTAL_PROOF_BEFORE_OPEN';
              await checkSql(active);
              checkOpening('VERIFYING');
              need(Date.parse(expiresAt)>Date.now());
              openingStage='PROVEN';
              return opening;
            } catch { return refuse(checkpoint); }
          },
        });
        const opened=await dependencies.openPortal(port,active);
        checkOpening('PROVEN');
        need(opened?.opening===opening&&object(opened.tab)&&object(opened.document)
          &&opened.origin==='https://billing.stripe.com'&&opened.providerMutationCount===0);
        return Object.freeze({tab:opened.tab,document:opened.document});
      },60000);
      portalOpened=true;

      phase='PORTAL_RETURN';
      const returned=await bounded(async active=>{
        const observed=await dependencies.observeMerchantReturn(hosted,active);
        billingCheck(active);
        need(!failure&&observed?.tab===hosted.tab&&observed.sourceDocument===hosted.document&&object(observed.document)
          &&observed.document!==hosted.document&&observed.initiator==='PROVIDER_DOCUMENT'&&observed.method==='GET'
          &&observed.url===policy.portalReturnUrl&&observed.providerMutationCount===0);
        return Object.freeze({tab:observed.tab,document:observed.document});
      },60000);
      returnObserved=true;

      phase='PORTAL_RETURN_READ';
      await bounded(async active=>{
        const read=await dependencies.readReturnedSubscription(returned,active);
        billingCheck(active);
        need(!failure&&read?.tab===returned.tab&&read.document===returned.document&&read.method==='GET'&&read.status===200
          &&read.siteId===owned.siteId&&read.customerId===owned.customerId&&read.subscriptionId===owned.subscriptionId
          &&read.billingStatus==='ACTIVE'&&read.access?.allowed===true&&read.access.reason==='PAID'
          &&read.revision===initial.revision&&Date.parse(read.paidThrough)===1000*initial.periodEnd);
      });

      phase='PORTAL_PROOF_AFTER_RETURN';
      await bounded(checkSql);
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
