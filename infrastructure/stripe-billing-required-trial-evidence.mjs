import {readFile} from 'node:fs/promises';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const proofFile=new URL('./stripe-billing-required-trial-proof.sql',import.meta.url);
const caseName='initial_required_trial';
const account='acct_1UPiMpELswAd8tpR',apiVersion='2026-09-30.endive';
const graphIds=['accountId','siteId','customerId','productId','priceId','subscriptionId','operationId'];
const scopeKeys=['runId','expectedSource','siteKey',...graphIds,'createKeyHash','priceReference'];
const references={
 customerReference:'cus',setupReference:'seti',methodReference:'pm',subscriptionReference:'sub',
 itemReference:'si',invoiceReference:'in',
};
const need=value=>{if(!value)throw billingError('REQUIRED_TRIAL_EVIDENCE');};
const ref=(value,prefix)=>typeof value==='string'&&value.length<=255&&new RegExp('^'+prefix+'_[A-Za-z0-9]+$').test(value);
const instant=milliseconds=>new Date(milliseconds).toISOString();

function ownedScope(configuration,fixture) {
 const current=fixture.resources();
 const scope=Object.freeze(Object.fromEntries(scopeKeys.map(key=>[key,current?.[key]])));
 need(configuration?.selectedCase===caseName&&billingUuid(scope.runId)&&scope.runId===configuration.runId
  &&typeof scope.expectedSource==='string'&&/^[a-f0-9]{40}$/.test(scope.expectedSource)&&scope.expectedSource===configuration.expectedSource
  &&scope.siteKey==='s5a-'+scope.runId.replaceAll('-','')&&graphIds.every(key=>billingUuid(scope[key]))
  &&typeof scope.createKeyHash==='string'&&/^[a-f0-9]{64}$/.test(scope.createKeyHash)&&ref(scope.priceReference,'price')
  &&configuration.approvedBinding?.externalAccountId===account&&configuration.approvedBinding?.apiVersion===apiVersion);
 return scope;
}

function requireCurrent(scope,configuration,fixture) {
 const current=ownedScope(configuration,fixture);
 need(scopeKeys.every(key=>current[key]===scope[key]));
}

function providerBindings(scope,observed,startedAt,finishedAt) {
 need(observed&&scopeKeys.every(key=>observed[key]===scope[key])&&observed.mode==='test'
  &&observed.externalAccountId===account&&observed.apiVersion===apiVersion&&observed.testClock===null
  &&Number.isSafeInteger(observed.observedAt)&&startedAt<=observed.observedAt&&observed.observedAt<=finishedAt);
 const ids=Object.entries(references).map(([key,prefix])=>{
  const reference=observed[key];
  need(ref(reference,prefix));
  return reference;
 });
 need(observed.setupType==='setup_intent'&&observed.setupUsage==='off_session'&&observed.setupStatus==='succeeded'
  &&observed.setupMethod===observed.methodReference&&observed.setupCustomerReference===observed.customerReference
  &&observed.setupSubscriptionId===scope.subscriptionId&&observed.setupOperationId===scope.operationId);
 need(observed.methodObject==='payment_method'&&observed.methodType==='card'
  &&observed.methodCustomerReference===observed.customerReference&&observed.defaultPaymentMethod===observed.methodReference
  &&observed.subscriptionType==='subscription'&&observed.subscriptionStatus==='trialing'
  &&observed.subscriptionCustomerReference===observed.customerReference);
 need(observed.itemType==='subscription_item'&&observed.itemCount===1&&observed.itemSubscriptionReference===observed.subscriptionReference
  &&observed.itemPriceReference===scope.priceReference);
 need(observed.invoiceType==='invoice'&&observed.invoiceStatus==='paid'&&observed.invoiceReason==='subscription_create'
  &&observed.invoiceCustomerReference===observed.customerReference&&observed.invoiceSubscriptionReference===observed.subscriptionReference
  &&observed.latestInvoiceReference===observed.invoiceReference&&observed.invoiceCount===1
  &&['amountDue','amountPaid','amountRemaining','allocationCount','paymentIntentCount','chargeCount'].every(key=>observed[key]===0));
 const {trialStart,trialEnd}=observed,now=Math.floor(Date.now()/1000);
 need(Number.isSafeInteger(trialStart)&&trialStart>0&&trialStart<=now&&Number.isSafeInteger(trialEnd)&&now<trialEnd);
 return Object.freeze([scope.runId,...graphIds.map(key=>scope[key]),scope.createKeyHash,...ids,instant(1000*trialStart),instant(1000*trialEnd)]);
}

function milliseconds(value) {
 need(value instanceof Date);
 const measured=Date.prototype.getTime.call(value);
 need(Number.isFinite(measured));
 return measured;
}

function verifiedRow(rows,scope,args,startedAt,finishedAt) {
 need(Array.isArray(rows)&&rows.length===1);
 const row=rows[0];
 need(row?.run_id===scope.runId&&billingUuid(row.billing_record_id)&&billingUuid(row.trial_started_event_id));
 const revision=typeof row.revision==='string'&&/^[1-9][0-9]*$/.test(row.revision)?Number(row.revision):row.revision;
 need(Number.isSafeInteger(revision)&&revision>0);
 const start=milliseconds(row.trial_start),end=milliseconds(row.trial_end);
 const evaluated=milliseconds(row.access_evaluated_at),validUntil=milliseconds(row.access_valid_until);
 need(instant(start)===args[15]&&instant(end)===args[16]&&start<=evaluated&&evaluated<end
  &&startedAt<=evaluated&&evaluated<=finishedAt&&validUntil===Math.min(end,evaluated+300000)&&validUntil>Date.now());
 return Object.freeze({trialVerified:true,noCash:true,runId:scope.runId,billingRecordId:row.billing_record_id,
  trialStartedEventId:row.trial_started_event_id,revision,trialStart:args[15],trialEnd:args[16],
  accessEvaluatedAt:instant(evaluated),accessValidUntil:instant(validUntil)});
}

/** Bind a fresh provider observer and the exact read-only query. The caller owns both transports. */
export async function proveBillingRequiredTrialEvidence({configuration,fixture},limits,{observeProvider,readOnly}) {
 try {
  const callerLimits=Object.freeze({deadline:limits?.deadline,signal:limits?.signal});
  return await billingBounded(callerLimits,async stage=>{
   const active=Object.freeze({...stage});
   const scope=ownedScope(configuration,fixture);
   need(typeof observeProvider==='function'&&typeof readOnly==='function');
   const query=await readFile(proofFile,'utf8');
   requireCurrent(scope,configuration,fixture);
   billingCheck(active);
   const observationStartedAt=Date.now();
   const observed=await observeProvider(scope,active);
   const observationFinishedAt=Date.now();
   billingCheck(active);
   const args=providerBindings(scope,observed,observationStartedAt,observationFinishedAt);
   requireCurrent(scope,configuration,fixture);
   billingCheck(active);
   const readStartedAt=Date.now();
   const rows=await readOnly(Object.freeze({mode:'read only',query,args}),active);
   const readFinishedAt=Date.now();
   billingCheck(active);
   requireCurrent(scope,configuration,fixture);
   return verifiedRow(rows,scope,args,readStartedAt,readFinishedAt);
  },120000);
 }catch{throw billingError('REQUIRED_TRIAL_EVIDENCE');}
}
