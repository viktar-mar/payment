import {readFile} from 'node:fs/promises';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const proofFile=new URL('./stripe-billing-required-trial-proof.sql',import.meta.url);
const seedFile=new URL('./stripe-billing-required-trial-seed.sql',import.meta.url);
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

const seedReferences={
 customer_reference:'cus',setup_reference:'seti',method_reference:'pm',
 subscription_reference:'sub',item_reference:'si',invoice_reference:'in',
 provider_price_id:'price',provider_product_id:'prod',
};
const zeroInvoiceFields=[
 'amount_due','amount_paid','amount_remaining','total','subtotal','amount_paid_off_stripe','amount_overpaid',
 'starting_balance','ending_balance','pre_payment_credit_notes_amount','post_payment_credit_notes_amount',
];
const empty=value=>Array.isArray(value)&&value.length===0;
const metadata=(actual,expected)=>actual&&Object.entries(expected).every(([key,value])=>actual[key]===value);

function stripeObject(value,id,type) {
 need(value?.id===id&&value.object===type&&value.livemode===false&&(value.account===undefined||value.account===account));
 return value;
}

function completeList(value,length) {
 need(value?.object==='list'&&value.has_more===false&&Array.isArray(value.data)&&value.data.length===length);
 return value.data;
}

function seedIdentity(rows) {
 need(Array.isArray(rows)&&rows.length===1);
 const row=rows[0],pairs=Object.entries(seedReferences);
 need(row&&Object.keys(row).length===pairs.length+2);
 const ids=pairs.map(([key,prefix])=>{need(ref(row[key],prefix));return row[key];});
 const start=milliseconds(row.trial_start)/1000,end=milliseconds(row.trial_end)/1000;
 need(Number.isSafeInteger(start)&&Number.isSafeInteger(end)&&start>0&&start<=Date.now()/1000&&Date.now()/1000<end);
 return [...ids,start,end];
}

async function observeOwnedTrial(inputs,scope,limits,{readOnly,readProvider}) {
 const current=()=>{
  requireCurrent(scope,inputs.configuration,inputs.fixture);
  billingCheck(limits);
 };
 need(typeof readOnly==='function'&&typeof readProvider==='function');
 const query=await readFile(seedFile,'utf8');
 const args=Object.freeze([scope.runId,...graphIds.map(key=>scope[key]),scope.createKeyHash]);
 current();
 const seed=await readOnly(Object.freeze({mode:'read only',query,args}),limits);
 current();
 const [customer,setup,method,subscription,item,invoice,priceReference,product,start,end]=seedIdentity(seed);
 need(priceReference===scope.priceReference);
 const read=async(path,parameters={})=>{
  current();
  const request=Object.freeze({method:'GET',path,parameters:Object.freeze(parameters),runId:scope.runId,expectedSource:scope.expectedSource});
  const result=await readProvider(request,limits);
  current();
  need(result?.externalAccountId===account&&result.apiVersion===apiVersion&&result.livemode===false);
  return result.body;
 };
 const owner={account_id:scope.accountId,site_id:scope.siteId,customer_id:scope.customerId};
 const operation={...owner,subscription_id:scope.subscriptionId,operation_id:scope.operationId};
 const c=stripeObject(await read('/v1/customers/'+customer),customer,'customer');
 need(c.deleted!==true&&c.test_clock===null&&metadata(c.metadata,owner));
 const s=stripeObject(await read('/v1/setup_intents/'+setup),setup,'setup_intent');
 need(s.customer===customer&&s.payment_method===method&&s.status==='succeeded'&&s.usage==='off_session'
  &&Array.isArray(s.payment_method_types)&&s.payment_method_types.length===1&&s.payment_method_types[0]==='card'
  &&metadata(s.metadata,operation));
 const pm=stripeObject(await read('/v1/payment_methods/'+method),method,'payment_method');
 need(pm.customer===customer&&pm.type==='card'&&pm.card&&typeof pm.card==='object'&&!Array.isArray(pm.card));
 const sub=stripeObject(await read('/v1/subscriptions/'+subscription),subscription,'subscription');
 need(sub.customer===customer&&sub.status==='trialing'&&sub.currency==='eur'&&sub.test_clock===null
  &&sub.latest_invoice===invoice&&sub.default_payment_method===method&&sub.trial_start===start&&sub.trial_end===end
  &&sub.billing_mode?.type==='classic'&&sub.collection_method==='charge_automatically'
  &&sub.pause_collection===null&&sub.cancel_at===null&&sub.cancel_at_period_end===false
  &&sub.schedule===null&&sub.pending_update===null&&sub.ended_at===null
  &&sub.trial_settings?.end_behavior?.missing_payment_method==='pause'&&metadata(sub.metadata,operation));
 const [si]=completeList(sub.items,1);
 need(si?.id===item&&si.object==='subscription_item'&&si.deleted!==true&&si.subscription===subscription&&si.quantity===1
  &&si.current_period_start===start&&si.current_period_end===end);
 const price=stripeObject(si.price,priceReference,'price');
 need(price.product===product&&price.active===true&&price.unit_amount===2000&&price.currency==='eur'
  &&price.type==='recurring'&&price.tax_behavior==='inclusive'&&price.recurring?.interval==='month'
  &&price.recurring.interval_count===1&&price.recurring.usage_type==='licensed'
  &&metadata(price.metadata,{local_price_id:scope.priceId,local_price_version:'1'}));
 const inv=stripeObject(await read('/v1/invoices/'+invoice,{'expand[]':'amount_paid_off_stripe'}),invoice,'invoice');
 need(inv.status==='paid'&&inv.customer===customer&&inv.test_clock===null&&inv.billing_reason==='subscription_create'
  &&inv.collection_method==='charge_automatically'&&inv.currency==='eur'&&inv.parent?.type==='subscription_details'
  &&inv.parent.subscription_details?.subscription===subscription&&zeroInvoiceFields.every(key=>inv[key]===0)
  &&['discounts','total_discount_amounts','total_pretax_credit_amounts'].every(key=>empty(inv[key])));
 const [line]=completeList(inv.lines,1);
 need(ref(line?.id,'il'));
 stripeObject(line,line.id,'line_item');
 const detail=line.parent?.subscription_item_details;
 need(line.invoice===invoice&&line.amount===0&&line.quantity===1&&line.currency==='eur'
  &&(line.subscription==null||line.subscription===subscription)
  &&line.parent?.type==='subscription_item_details'&&detail?.subscription===subscription&&detail.subscription_item===item
  &&detail.proration===false&&line.pricing?.type==='price_details'&&line.pricing.price_details?.price===price.id
  &&line.pricing.price_details.product===price.product&&line.period?.start===start&&line.period.end===end
  &&empty(line.discount_amounts)&&empty(line.pretax_credit_amounts));
 const [originalInvoice]=completeList(await read('/v1/invoices',{subscription,limit:2}),1);
 stripeObject(originalInvoice,invoice,'invoice');
 completeList(await read('/v1/invoice_payments',{invoice,limit:1}),0);
 completeList(await read('/v1/payment_intents',{customer,limit:1}),0);
 completeList(await read('/v1/charges',{customer,limit:1}),0);
 return Object.freeze({...scope,mode:'test',externalAccountId:account,apiVersion,testClock:sub.test_clock,observedAt:Date.now(),
  customerReference:c.id,setupReference:s.id,methodReference:pm.id,subscriptionReference:sub.id,itemReference:si.id,invoiceReference:inv.id,
  setupType:s.object,setupUsage:s.usage,setupStatus:s.status,setupMethod:s.payment_method,setupCustomerReference:s.customer,
  setupSubscriptionId:s.metadata.subscription_id,setupOperationId:s.metadata.operation_id,
  methodObject:pm.object,methodType:pm.type,methodCustomerReference:pm.customer,defaultPaymentMethod:sub.default_payment_method,
  subscriptionType:sub.object,subscriptionStatus:sub.status,subscriptionCustomerReference:sub.customer,
  itemType:si.object,itemCount:sub.items.data.length,itemSubscriptionReference:si.subscription,itemPriceReference:price.id,
  invoiceType:inv.object,invoiceStatus:inv.status,invoiceReason:inv.billing_reason,invoiceCustomerReference:inv.customer,
  invoiceSubscriptionReference:inv.parent.subscription_details.subscription,latestInvoiceReference:sub.latest_invoice,invoiceCount:1,
  amountDue:inv.amount_due,amountPaid:inv.amount_paid,amountRemaining:inv.amount_remaining,
  allocationCount:0,paymentIntentCount:0,chargeCount:0,trialStart:sub.trial_start,trialEnd:sub.trial_end});
}

/** Read-only composition. The caller supplies a run-bound sandbox transport and PostgreSQL executor. */
export async function proveOwnedBillingRequiredTrialEvidence(inputs,limits,dependencies) {
 try {
  const {configuration,fixture}=inputs,{readOnly,readProvider}=dependencies;
  const owner=Object.freeze({configuration,fixture});
  const transports=Object.freeze({readOnly,readProvider});
  return await proveBillingRequiredTrialEvidence(owner,limits,{
   observeProvider:(scope,active)=>observeOwnedTrial(owner,scope,active,transports),
   readOnly,
  });
 }catch{throw billingError('REQUIRED_TRIAL_EVIDENCE');}
}
