import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {validateBillingCallbackEvidence,validateBillingRenewalCallbackEvidence} from './stripe-billing-evidence.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {renewalInstant,validateRenewalBoundary,validateRenewalFinalization} from './stripe-billing-first-renewal-response.mjs';
const need=v=>{if(!v)throw billingError('EVIDENCE');};
const flags=['cash','journal','original','owned','renewal','operations'];
const pairEvents=row=>[...(row.initial?.callbacks??[]),...(row.renewal?.callbacks??[])];
/** Final financial proof is independent of, and occurs after, the unchanged initial proof. */
export function validateBillingFirstRenewalEvidence(scope,row,inspection,configuration,boundary,settlement){
 need(row&&flags.every(k=>row.proof?.[k]===true));
 need(scope.runId===configuration.runId&&scope.expectedSource===configuration.expectedSource&&row.owner_run===scope.runId&&row.source_sha===scope.expectedSource&&/^clock_[A-Za-z0-9_]{1,200}$/.test(row.clock_reference));
 const original={run_id:scope.runId,...Object.fromEntries([['account_id','accountId'],['site_id','siteId'],['customer_id','customerId'],['price_id','priceId'],['subscription_id','subscriptionId'],['operation_id','operationId']].map(([wire,key])=>[wire,scope[key]]))};
 const initial=row.initial,renewal=row.renewal;need(initial&&renewal);
 need(billingUuid(renewal.payment_id)&&billingUuid(renewal.billing_record_id)&&/^in_[A-Za-z0-9_]{1,200}$/.test(renewal.invoice_reference)&&/^pi_[A-Za-z0-9_]{1,200}$/.test(renewal.intent_reference));
 validateRenewalBoundary(boundary,original,{paid_through:new Date(Number(initial.period_end)*1000).toISOString()});validateRenewalFinalization(settlement,original,boundary);
 need(Number(row.frozen_time)===configuration.frozenTime&&Number(row.receipt_period_start)===Number(initial.period_start)&&Number(row.receipt_period_end)===Number(initial.period_end));
 need(Number(initial.period_start)<=configuration.frozenTime&&configuration.frozenTime<Number(initial.period_end)&&Number(renewal.period_start)===Number(initial.period_end));
 need(Number(row.boundary_time)===Number(initial.period_end)&&Number(row.finalization_time)===Number(initial.period_end)+3600&&Number(renewal.period_start)===renewalInstant(boundary.period_start)&&Number(renewal.period_end)===renewalInstant(boundary.period_end));
 need(billingUuid(row.renewal_admission_id)&&row.renewal_admission_id===boundary.renewal_admission_id&&row.publication_id===row.renewal_admission_id&&row.publication_id===settlement.publication_id);
 for(const key of ['customer_reference','subscription_reference','product_reference','price_reference'])need(initial[key]===renewal[key]);
 for(const key of ['invoice_reference','intent_reference','payment_id','billing_record_id'])need(initial[key]!==renewal[key]);
 const all=pairEvents(row);need(initial.callbacks?.length===2&&renewal.callbacks?.length===2&&all.length===4&&new Set(all.map(e=>e.id)).size===4);
 need(inspection?.duplicates===0&&inspection.count===4&&inspection.events?.length===4&&new Set(inspection.events.map(e=>e.event_id)).size===4&&inspection.events.every(e=>all.some(c=>c.id===e.event_id)));
 for(const [pair,time,validator]of [[initial,configuration.frozenTime,validateBillingCallbackEvidence],[renewal,Number(row.finalization_time),validateBillingRenewalCallbackEvidence]]){
  const ids=new Set(pair.callbacks.map(e=>e.id)),subset={count:2,duplicates:0,events:inspection.events.filter(e=>ids.has(e.event_id))};
  validator(scope,pair,subset);
  const lifecycle=pair.callbacks.filter(e=>e.payload.schema_version===4);need(lifecycle.length===1);const p=lifecycle[0].payload;
  need(p.state==='ACTIVE'&&p.access_allowed===true&&p.billing_record_id===pair.billing_record_id&&renewalInstant(p.occurred_at)===time&&renewalInstant(p.access_valid_until)===time+300);
 }
 return Object.freeze({ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true});
}
/** Credentials stay inside this bounded read-only callback; receiver inspection follows connection close. */
export async function proveBillingFirstRenewalEvidence({configuration,bootstrap,fixture,boundary,settlement},limits,dependencies={}){
 const scope=fixture.resources();need(scope.runId===configuration.runId&&scope.expectedSource===configuration.expectedSource);
 for(const k of ['accountId','siteId','customerId','subscriptionId','operationId','attemptId','productId','priceId'])need(billingUuid(scope[k]));
 need(/^[a-f0-9]{64}$/.test(scope.createKeyHash)&&/^[a-f0-9]{64}$/.test(scope.confirmKeyHash)&&billingUuid(boundary?.renewal_admission_id));
 const query=await readFile(new URL('./stripe-billing-first-renewal-proof.sql',import.meta.url),'utf8');
 const connect=dependencies.connect??createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');let row;
 need(await bootstrap.withDatabaseCredentials(async credentials=>{billingCheck(limits);let sql;
  try{sql=connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});const abort=()=>{void sql.end({timeout:0}).catch(()=>{});};limits.signal?.addEventListener('abort',abort,{once:true});
   try{row=await billingBounded(limits,async l=>{for(;;){const rows=await sql.begin('read only',async tx=>{await tx.unsafe("set local statement_timeout='5000'");return tx.unsafe(query,[scope.accountId,scope.siteId,scope.customerId,scope.subscriptionId,scope.operationId,scope.attemptId,scope.productId,scope.priceId,scope.createKeyHash,scope.confirmKeyHash,scope.endpoints.demo,configuration.runId,configuration.expectedSource,configuration.frozenTime,boundary.renewal_admission_id]);});billingCheck(l);need(rows.length===1);const result=rows[0];need(flags.every(k=>result.proof?.[k]===true));need(result.initial?.callbacks?.length===2&&result.renewal?.callbacks?.length===2);if(pairEvents(result).every(c=>c.delivered===true))return result;await billingBounded(l,()=>new Promise(r=>setTimeout(r,250)),1000);}},90000);}finally{limits.signal?.removeEventListener('abort',abort);}billingCheck(limits);return true;
  }finally{if(sql)await billingBounded({deadline:Date.now()+5000},()=>sql.end({timeout:0}),5000);}
 },limits)===true);billingCheck(limits);need(row);const inspection=await fixture.inspectCallbacks(limits);billingCheck(limits);return validateBillingFirstRenewalEvidence(scope,row,inspection,configuration,boundary,settlement);
}
