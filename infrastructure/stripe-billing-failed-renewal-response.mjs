import {billingUuid} from './stripe-billing-provision.mjs';
import {billingError} from './stripe-billing-limits.mjs';
const need=ok=>{if(!ok)throw billingError('FAILED_RENEWAL_ACK');};
const identities=['run_id','account_id','site_id','customer_id','price_id','subscription_id','operation_id'];
const base=[...identities,'source_sha','original_frozen_time','code','phase'];
const access=['access_reason','access_allowed','access_evaluated_at','access_valid_until'];
const steps=[
 ['boundary','boundary','RENEWAL_BOUNDARY','renewal_admission_id initial_bill_id initial_payment_id boundary_id boundary_ready_id boundary_advance_id boundary_operation_id pending_graph_invocation_id initial_invoice_id initial_allocation_id initial_payment_intent_id renewal_invoice_id boundary_time period_start period_end pending_graph_accepted_at'],
 ['armDecline','arm-decline','DECLINE_ARMED','arm_admission_id arm_boundary_id arm_run_id arm_payment_method_id arm_acknowledged_at'],
 ['failedRenewal','failed','FAILED_RENEWAL','failed_admission_id finalization_ready_id finalization_advance_id finalization_operation_id failure_claim_id failed_bill_id finalization_time failed_allocation_id failed_payment_intent_id grace_deadline failed_recorded_at'],
 ['graceLastSecond','grace-last-second','GRACE_LAST_SECOND','grace_preparation_id grace_ready_id grace_operation_id grace_invocation_id grace_target grace_frozen_time',access],
 ['graceAt','grace-at','GRACE_AT','grace_at_ready_id grace_at_operation_id grace_at_invocation_id grace_at_frozen_time',access],
 ['graceAfter','grace-after','GRACE_AFTER','grace_after_ready_id grace_after_operation_id grace_after_invocation_id grace_after_frozen_time',access],
 ['prepareMethod','method/prepare','METHOD_PREPARED','method_reservation_id method_operation_id',['action']],
 ['selectMethod','method/select','METHOD_SELECTED','',['session']],
 ['confirmMethod','method/confirm','METHOD_CONSUMED','',['browser_confirm_allowed','state']],
 ['applyMethod','method/apply','METHOD_READY','method_claim_id method_setup_step_id method_update_step_id recovery_operation_id recovery_bill_id method_completed_at setup_intent_id replacement_method_id recovery_allocation_id recovery_payment_intent_id'],
 ['confirmRecovery','recovery/confirm','RECOVERY_ACKNOWLEDGED','recovery_confirm_operation_id recovery_confirm_resource_id recovery_confirm_operation_version recovery_confirm_status recovery_acknowledged_at recovery_confirm_payment_intent_id recovery_charge_id'],
 ['observeRecovery','recovery/observe','RECOVERED_SETTLED_LOCAL','recovery_publication_claim_id recovery_publication_admission_id recovery_publication_operation_id recovery_publication_bill_id recovery_publication_reservation_id recovery_payment_id recovery_published_at recovery_revision recovery_observation_sequence',access]
];
let cumulative=[];
export const failedStages=Object.freeze(steps.map(([method,path,phase,fields,local=[]])=>{
 const added=Object.freeze(fields.split(' ').filter(Boolean));cumulative=[...cumulative,...added];
 return Object.freeze({method,path,phase,added,retained:Object.freeze(cumulative),local:Object.freeze(local)});
}));
export function exactRecord(value,keys){
 need(value&&typeof value==='object'&&[Object.prototype,null].includes(Object.getPrototypeOf(value)));
 const ds=Object.getOwnPropertyDescriptors(value);need(Reflect.ownKeys(ds).length===keys.length);
 return Object.fromEntries(keys.map(k=>{need(ds[k]?.enumerable&&Object.hasOwn(ds[k],'value'));return [k,ds[k].value];}));
}
// Java Instant.toString permits up to nine fractional digits. Preserve sub-millisecond provenance.
export function instantNanos(v){
 need(typeof v==='string');const m=/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(v);need(m);
 const s=Date.parse(m[1]+'Z');need(Number.isSafeInteger(s)&&s>0&&new Date(s).toISOString().slice(0,19)===m[1]);return BigInt(s)*1000000n+BigInt((m[2]??'').padEnd(9,'0'));
}
const seconds=v=>{const ns=instantNanos(v);need(ns%1000000000n===0n);return Number(ns/1000000000n);};
const opaque=v=>typeof v==='string'&&v.length>0&&v.length<=8192&&!/[\u0000-\u001f\u007f]/.test(v);
/** The failed owner additionally validates injected initial ports before retaining a session. */
export function checkedInitialResponse(raw,route){
 const extras={prepare:['action','session'],confirm:['protocol','attempt_id','state','retry_allowed','successor_attempt_id','next_action_allowed','expires_at']};
 const phase=Object.getOwnPropertyDescriptor(raw,'phase')?.value;
 const fields=route==='observe'?(phase==='SETTLED_LOCAL'?['period_start','paid_through','access_valid_until','access_evaluated_at']:[]):extras[route];
 need(fields);
 const v=exactRecord(raw,['phase','code',...identities,...fields]);need(v.code==='OK'&&identities.every(k=>billingUuid(v[k])));
 if(route==='prepare'){
  const a=exactRecord(v.action,['kind','secret','expires_at']);
  const s=exactRecord(v.session,['protocol','provider','flow','publishable_key','client_secret','action_token','attempt_id','capability','expires_at']);
  need(v.phase==='PREPARED'&&a.kind==='PROVIDER_CLIENT'&&opaque(a.secret)&&s.protocol==='SERVER_CONFIRM_V2'&&s.provider==='stripe'&&s.flow==='PAYMENT');
  need([s.publishable_key,s.client_secret,s.capability].every(opaque)&&s.action_token===null&&billingUuid(s.attempt_id));
  instantNanos(a.expires_at);instantNanos(s.expires_at);v.action=Object.freeze(a);v.session=Object.freeze(s);
 }else if(route==='confirm'){
  need(v.phase==='OBSERVE'&&v.protocol==='SERVER_CONFIRM_V2'&&billingUuid(v.attempt_id)&&v.state==='OBSERVE'&&v.retry_allowed===false&&v.successor_attempt_id===null&&v.next_action_allowed===false);
  instantNanos(v.expires_at);
 }else{
  need(v.phase==='SETTLED_LOCAL'||v.phase==='OBSERVE');
  for(const k of fields)seconds(v[k]);
 }
 return Object.freeze(v);
}
const refs={initial_invoice_id:'in',renewal_invoice_id:'in',initial_payment_intent_id:'pi',failed_payment_intent_id:'pi',recovery_payment_intent_id:'pi',recovery_confirm_payment_intent_id:'pi',initial_allocation_id:'inpay',failed_allocation_id:'inpay',recovery_allocation_id:'inpay',arm_payment_method_id:'pm',replacement_method_id:'pm',setup_intent_id:'seti',recovery_charge_id:'ch'};
function field(k,v){
 if(Object.hasOwn(refs,k))need(typeof v==='string'&&v.length<=255&&new RegExp('^'+refs[k]+'_[a-zA-Z0-9]+$').test(v));
 else if(k.endsWith('_id'))need(billingUuid(v));
 else if(k==='recovery_confirm_status')need(v==='ACKNOWLEDGED');
 else if(k.endsWith('_at')||['period_start','period_end','boundary_time','grace_deadline'].includes(k))instantNanos(v);
 else need(Number.isSafeInteger(v)&&v>=0);
}
function checkAccess(v,reason,at,until){
 need(v.access_reason===reason&&v.access_allowed===(until!==null)&&seconds(v.access_evaluated_at)===at);
 need(until===null?v.access_valid_until===null:seconds(v.access_valid_until)===until);
}
export function checkInitialSettlementTime(v,c){
 need(seconds(v.period_start)<=c.frozenTime&&seconds(v.paid_through)>c.frozenTime);
 need(seconds(v.access_evaluated_at)===c.frozenTime&&seconds(v.access_valid_until)===c.frozenTime+300);
}
export function validateFailedRenewalStage(method,raw,original,initial,c,previous){
 const index=failedStages.findIndex(s=>s.method===method);need(index>=0);const stage=failedStages[index],prior=failedStages[index-1];
 const v=exactRecord(raw,[...base,...stage.retained,...stage.local]);
 need(v.phase===stage.phase&&v.code==='OK'&&v.source_sha===c.expectedSource&&v.run_id===c.runId&&v.original_frozen_time===c.frozenTime);
 need(identities.every(k=>billingUuid(v[k])&&v[k]===original[k]));
 if(prior){need(previous?.phase===prior.phase&&prior.retained.every(k=>v[k]===previous[k]));}else need(previous===undefined);
 checkInitialSettlementTime(initial,c);
 return validateContents(v,index,stage.added,seconds(initial.paid_through),previous);
}
/** A final SQL reader does not possess browser secrets or intermediate response snapshots. */
export function validateFailedRenewalPublication(raw,scope,c){
 const index=failedStages.length-1,stage=failedStages[index];
 const v=exactRecord(raw,[...base,...stage.retained,...stage.local]);
 need(v.phase===stage.phase&&v.code==='OK'&&v.source_sha===c.expectedSource&&v.original_frozen_time===c.frozenTime);
 for(const [wire,name] of [['run_id','runId'],['account_id','accountId'],['site_id','siteId'],['customer_id','customerId'],['price_id','priceId'],['subscription_id','subscriptionId'],['operation_id','operationId']])
  need(billingUuid(v[wire])&&v[wire]===scope[name]);
 return validateContents(v,index,stage.retained,seconds(v.period_start));
}
function validateContents(v,index,fields,e,previous){
 const method=failedStages[index].method;
 for(const k of fields)field(k,v[k]);
 const n=seconds(v.period_end),g=e+259200;
 need(seconds(v.boundary_time)===e&&seconds(v.period_start)===e&&g+1<n&&v.initial_invoice_id!==v.renewal_invoice_id);
 if(index>=1)need(v.arm_admission_id===v.renewal_admission_id&&v.arm_boundary_id===v.boundary_id&&v.arm_run_id===v.run_id&&instantNanos(v.arm_acknowledged_at)>=instantNanos(v.pending_graph_accepted_at));
 if(index>=2)need(v.finalization_time===e+3600&&seconds(v.grace_deadline)===g&&v.initial_bill_id!==v.failed_bill_id&&v.initial_payment_intent_id!==v.failed_payment_intent_id&&v.initial_allocation_id!==v.failed_allocation_id&&instantNanos(v.failed_recorded_at)>=instantNanos(v.arm_acknowledged_at));
 if(index>=3)need(v.grace_target===g-1&&v.grace_frozen_time===g-1);
 if(index>=4)need(v.grace_at_frozen_time===g);
 if(index>=5)need(v.grace_after_frozen_time===g+1);
 if(index>=9)need(v.method_setup_step_id!==v.method_update_step_id&&v.replacement_method_id!==v.arm_payment_method_id&&v.recovery_bill_id===v.failed_bill_id&&v.recovery_allocation_id===v.failed_allocation_id&&v.recovery_payment_intent_id===v.failed_payment_intent_id&&instantNanos(v.method_completed_at)>=instantNanos(v.failed_recorded_at));
 if(index>=10)need(v.recovery_confirm_operation_version===2&&v.recovery_confirm_resource_id===v.recovery_operation_id&&v.recovery_confirm_payment_intent_id===v.failed_payment_intent_id&&instantNanos(v.recovery_acknowledged_at)>=instantNanos(v.method_completed_at));
 switch(method){
 case 'graceLastSecond':checkAccess(v,'GRACE',g-1,g);break;
 case 'graceAt':checkAccess(v,'EXPIRED',g,null);break;
 case 'graceAfter':checkAccess(v,'EXPIRED',g+1,null);break;
 case 'prepareMethod':{
  const a=exactRecord(v.action,['kind','secret','expires_at']);need(a.kind==='PROVIDER_CLIENT'&&opaque(a.secret));instantNanos(a.expires_at);v.action=Object.freeze(a);break;
 }
 case 'selectMethod':{
  const s=exactRecord(v.session,['protocol','provider','flow','publishable_key','client_secret','action_token','attempt_id','capability','expires_at']);
  need(s.protocol==='BROWSER_CONFIRM_V1'&&s.provider==='stripe'&&s.flow==='SETUP'&&opaque(s.publishable_key)&&opaque(s.client_secret)&&s.action_token===previous.action.secret&&s.attempt_id===null&&s.capability===null);
  need(instantNanos(s.expires_at)===instantNanos(previous.action.expires_at));v.session=Object.freeze(s);break;
 }
 case 'confirmMethod':need(v.browser_confirm_allowed===true&&v.state==='RESERVED');break;
 case 'observeRecovery':
  need(v.recovery_publication_admission_id===v.failed_admission_id&&v.recovery_publication_reservation_id===v.method_reservation_id&&v.recovery_publication_operation_id===v.recovery_operation_id&&v.recovery_publication_bill_id===v.failed_bill_id&&v.recovery_payment_id!==v.initial_payment_id&&instantNanos(v.recovery_published_at)>=instantNanos(v.recovery_acknowledged_at));
  checkAccess(v,'PAID',g+1,Math.min(g+301,n));break;
 }
 return Object.freeze(v);
}
// JSON.parse already validated the grammar. Scan complete string tokens (including escapes)
// and reject key aliases/duplicates at their original object depth, before using the object.
export function checkFailedWireKeys(text){
 const stack=[];
 for(const m of text.matchAll(/"(?:[^"\\]|\\.)*"|[{}]/g)){
  if(m[0]==='{')stack.push(new Set());else if(m[0]==='}')stack.pop();
  else if(/^\s*:/.test(text.slice(m.index+m[0].length))){
   need(/^"[a-z_]+"$/.test(m[0])&&!stack.at(-1).has(m[0]));stack.at(-1).add(m[0]);
  }
 }
}
