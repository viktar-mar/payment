import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import {billingUuid} from './stripe-billing-provision.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
const need=v=>{if(!v)throw billingError('EVIDENCE');};
// Only an accepted initial payment from this invocation can anchor a cancellation proof.
const cancellationOrigins=new WeakMap();
export function billingCancellationOrigin(baseline) { return cancellationOrigins.get(baseline); }

function captureCancellationOrigin(result,row,inspection) {
 const [cashEventId]=row.cash_journal_ids??[],[paidEventId]=row.paid_journal_ids??[];
 const identity={paymentId:row.payment_id,billingRecordId:row.billing_record_id,cashEventId,paidEventId};
 need(Object.values(identity).every(billingUuid)&&new Set(Object.values(identity)).size===4
   &&row.cash_journal_ids.length===1&&row.paid_journal_ids.length===1);
 const cash=row.callbacks.find(e=>e.id===cashEventId)?.payload,paid=row.callbacks.find(e=>e.id===paidEventId)?.payload;
 need(cash?.schema_version===3&&cash.payment_id===identity.paymentId&&cash.billing_record_id===identity.billingRecordId
   &&paid?.schema_version===4&&paid.event_type==='subscription.updated'&&paid.state==='ACTIVE'&&paid.access_allowed===true
   &&paid.billing_record_id===identity.billingRecordId&&paid.payment_id===null);
 const revision=Number(row.subscription_revision);
 need(row.initial_unscheduled===true&&/^[0-9]+$/.test(String(row.subscription_revision))
   &&Number.isSafeInteger(revision)&&Number.isSafeInteger(paid.revision)&&paid.revision<=revision);
 const callbackDigests=Object.freeze(Object.fromEntries(inspection.events.map(event=>[event.event_id,event.sha256])));
 cancellationOrigins.set(result,Object.freeze({...identity,revision,callbackDigests}));
}

const canonical=p=>JSON.stringify(Object.fromEntries(Object.keys(p).sort().map(k=>[k,p[k]])));
/** Only sanitized proof flags and exact owned provider binding escape the in-memory SQL/callback comparison. */
export function validateBillingEvidence(scope,row,inspection) {
 need(row&&['cash','journal','original','webhook'].every(k=>row.proof?.[k]===true));
 return validateBillingCallbackEvidence(scope,row,inspection);
}
/** Shared strict payload/digest validation; each caller separately establishes its own financial proof. */
export function validateBillingCallbackEvidence(scope,row,inspection) {validateCallbackPayloads(scope,row,inspection,'INITIAL');return callbackGraph(scope,row);}
/** Separate fixed entry point; received RENEWAL payloads are never rewritten. */
export function validateBillingRenewalCallbackEvidence(scope,row,inspection) {need(row?.callbacks?.length===2);validateCallbackPayloads(scope,row,inspection,'RENEWAL');return Object.freeze({cashVerified:true,journalVerified:true,signedCallbacksVerified:true});}
export function validateBillingCancellationCallbackEvidence(scope,row,inspection) {
 validateCallbackPayloads(scope,row,inspection,'INITIAL',102);
 return callbackGraph(scope,row);
}
function validateCallbackPayloads(scope,row,inspection,kind,maximumCallbacks=100) {
 need(Array.isArray(row.callbacks)&&row.callbacks.length>=2&&row.callbacks.length<=maximumCallbacks&&inspection?.duplicates===0&&inspection.count===row.callbacks.length&&inspection.events?.length===row.callbacks.length);
 const ids=new Set();let payments=0,lifecycle=0;
 for(const e of row.callbacks){const p=e.payload;need(e.delivered===true&&billingUuid(e.id)&&!ids.has(e.id));ids.add(e.id);
  need(p?.event_id===e.id&&p.event_type===e.type&&p.site_id===scope.siteId&&p.customer_id===scope.customerId&&p.subscription_id===scope.subscriptionId&&p.product_id===scope.productId);
  const receipt=inspection.events.filter(v=>v.event_id===e.id);need(receipt.length===1&&receipt[0].event_type===e.type&&receipt[0].count===1&&receipt[0].sha256===createHash('sha256').update(canonical(p)).digest('hex'));
  if(p.schema_version===3){payments++;need(p.event_type==='payment.succeeded'&&p.payment_status==='SUCCEEDED'&&p.payment_id===row.payment_id&&p.billing_record_id===row.billing_record_id&&p.amount_minor===2000&&p.currency==='EUR'&&p.billing_kind===kind&&p.price_id===scope.priceId&&p.price_version===1&&Date.parse(p.period_start)/1000===Number(row.period_start)&&Date.parse(p.period_end)/1000===Number(row.period_end));}
  else {need(p.schema_version===4&&['subscription.updated','subscription.access_changed'].includes(p.event_type));if(p.state==='ACTIVE'&&p.access_allowed===true){need(p.payment_id===null||p.payment_id===row.payment_id);if(p.billing_record_id===row.billing_record_id)lifecycle++;}}
 }
 need(payments===1&&lifecycle>=1);
}
function callbackGraph(scope,row){
 const graph={runId:scope.runId,expectedSource:scope.expectedSource,...Object.fromEntries(['accountId','siteId','customerId','subscriptionId','operationId','priceId'].map(k=>{need(billingUuid(scope[k]));return [k,scope[k]];})),priceVersion:1};
 for(const [key,prefix] of [['customer','cus'],['subscription','sub'],['product','prod'],['price','price'],['invoice','in'],['allocation','inpay'],['intent','pi']]){const v=row[key+'_reference'];need(typeof v==='string'&&new RegExp('^'+prefix+'_[A-Za-z0-9_]{1,200}$').test(v));graph[key+'Reference']=v;}
 graph.periodStart=Number(row.period_start);graph.periodEnd=Number(row.period_end);need(Number.isSafeInteger(graph.periodStart)&&graph.periodStart>0&&Number.isSafeInteger(graph.periodEnd)&&graph.periodEnd>graph.periodStart);
 return Object.freeze({graph:Object.freeze(graph),cashVerified:true,journalVerified:true,signedCallbacksVerified:true});
}
export async function proveBillingEvidence({configuration,bootstrap,fixture},limits,dependencies={}) {
 const scope=fixture.resources();need(scope.runId===configuration.runId&&scope.expectedSource===configuration.expectedSource);
 for(const k of ['accountId','siteId','customerId','subscriptionId','operationId','attemptId','productId','priceId'])need(billingUuid(scope[k]));
 need(/^[0-9a-f]{64}$/.test(scope.createKeyHash)&&/^[0-9a-f]{64}$/.test(scope.confirmKeyHash));
 const query=await readFile(new URL('./stripe-billing-proof.sql',import.meta.url),'utf8');
 const connect=dependencies.connect??createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');
 let result;
 need(await bootstrap.withDatabaseCredentials(async credentials=>{
  billingCheck(limits);let sql;
  try {
   // Credentials never leave this callback. A forced connection end cancels in-flight or late queries.
   sql=connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});
   const abort=()=>{void sql.end({timeout:0}).catch(()=>{});};limits.signal?.addEventListener('abort',abort,{once:true});
   try{result=await billingBounded(limits,async l=>{
    for(;;){
     const rows=await sql.begin('read only',async tx=>{await tx.unsafe("set local statement_timeout='5000'");return tx.unsafe(query,[scope.accountId,scope.siteId,scope.customerId,scope.subscriptionId,scope.operationId,scope.attemptId,scope.productId,scope.priceId,scope.createKeyHash,scope.confirmKeyHash,scope.endpoints.demo]);});
     billingCheck(l);need(rows.length<=1);
     if(rows.length===1){const row=rows[0];
      // Cash/journal mismatches are terminal; pending transport is allowed only GET/SQL observation.
      need(['cash','journal','original'].every(k=>row.proof?.[k]===true));
      if(row.proof.webhook===true&&row.callbacks.length>=2&&row.callbacks.every(c=>c.delivered===true))return row;
     }
     await billingBounded(l,()=>new Promise(r=>setTimeout(r,250)),1000);
    }
   },90000);}finally{limits.signal?.removeEventListener('abort',abort);}
   billingCheck(limits);return true;
  }finally{if(sql)await billingBounded({deadline:Date.now()+5000},()=>sql.end({timeout:0}),5000);}
 },limits)===true);billingCheck(limits);need(result);
 const inspection=await fixture.inspectCallbacks(limits);billingCheck(limits);const baseline=validateBillingEvidence(scope,result,inspection);
 if(configuration.selectedCase==='initial_cancel_withdraw')captureCancellationOrigin(baseline,result,inspection);
 return baseline;
}
