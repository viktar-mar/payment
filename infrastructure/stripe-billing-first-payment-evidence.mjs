import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {validateBillingCallbackEvidence} from './stripe-billing-evidence.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
const need=v=>{if(!v)throw billingError('EVIDENCE');};
export function validateBillingFirstPaymentEvidence(scope,row,inspection,configuration,settlement){
 need(row&&['cash','journal','original','owned'].every(k=>row.proof?.[k]===true));
 need(scope.runId===configuration.runId&&scope.expectedSource===configuration.expectedSource&&row.owner_run===scope.runId&&/^clock_[A-Za-z0-9_]{1,200}$/.test(row.clock_reference));
 need(Number(row.frozen_time)===configuration.frozenTime&&Number(row.receipt_period_start)===Number(row.period_start)&&Number(row.receipt_period_end)===Number(row.period_end));
 need(Date.parse(settlement.period_start)/1000===Number(row.period_start)&&Date.parse(settlement.paid_through)/1000===Number(row.period_end)&&Date.parse(settlement.access_evaluated_at)/1000===configuration.frozenTime&&Date.parse(settlement.access_valid_until)/1000===configuration.frozenTime+300);
 need(Number(row.period_start)<=configuration.frozenTime&&configuration.frozenTime<Number(row.period_end)&&row.callbacks?.length===2);
 const proof=validateBillingCallbackEvidence(scope,row,inspection);
 const lifecycle=row.callbacks.filter(e=>e.payload.schema_version===4);need(lifecycle.length===1);const p=lifecycle[0].payload;
 need(p.state==='ACTIVE'&&p.access_allowed===true&&p.billing_record_id===row.billing_record_id&&Date.parse(p.occurred_at)/1000===configuration.frozenTime&&Date.parse(p.access_valid_until)/1000===configuration.frozenTime+300);
 return Object.freeze({ownedVerified:true,cashVerified:proof.cashVerified,journalVerified:proof.journalVerified,signedCallbacksVerified:proof.signedCallbacksVerified});
}
/** Credentials remain in one bounded read-only callback, ending before receiver inspection. */
export async function proveBillingFirstPaymentEvidence({configuration,bootstrap,fixture,settlement},limits,dependencies={}){
 const scope=fixture.resources();need(scope.runId===configuration.runId&&scope.expectedSource===configuration.expectedSource);
 for(const k of ['accountId','siteId','customerId','subscriptionId','operationId','attemptId','productId','priceId'])need(billingUuid(scope[k]));
 need(/^[a-f0-9]{64}$/.test(scope.createKeyHash)&&/^[a-f0-9]{64}$/.test(scope.confirmKeyHash));
 const query=await readFile(new URL('./stripe-billing-first-payment-proof.sql',import.meta.url),'utf8');
 const connect=dependencies.connect??createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');let row;
 need(await bootstrap.withDatabaseCredentials(async credentials=>{billingCheck(limits);let sql;
  try{sql=connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});const abort=()=>{void sql.end({timeout:0}).catch(()=>{});};limits.signal?.addEventListener('abort',abort,{once:true});
   try{row=await billingBounded(limits,async l=>{for(;;){const rows=await sql.begin('read only',async tx=>{await tx.unsafe("set local statement_timeout='5000'");return tx.unsafe(query,[scope.accountId,scope.siteId,scope.customerId,scope.subscriptionId,scope.operationId,scope.attemptId,scope.productId,scope.priceId,scope.createKeyHash,scope.confirmKeyHash,scope.endpoints.demo,configuration.runId,configuration.frozenTime]);});billingCheck(l);need(rows.length===1);const result=rows[0];need(['cash','journal','original','owned'].every(k=>result.proof?.[k]===true));need(Array.isArray(result.callbacks)&&result.callbacks.length===2);if(result.callbacks.every(c=>c.delivered===true))return result;await billingBounded(l,()=>new Promise(r=>setTimeout(r,250)),1000);}},90000);}finally{limits.signal?.removeEventListener('abort',abort);}billingCheck(limits);return true;
  }finally{if(sql)await billingBounded({deadline:Date.now()+5000},()=>sql.end({timeout:0}),5000);}
 },limits)===true);billingCheck(limits);need(row);const inspection=await fixture.inspectCallbacks(limits);billingCheck(limits);return validateBillingFirstPaymentEvidence(scope,row,inspection,configuration,settlement);
}
