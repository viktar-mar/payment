import {billingUuid} from './stripe-billing-provision.mjs';
import {billingError} from './stripe-billing-limits.mjs';
const need=v=>{if(!v)throw billingError('RENEWAL_ACK');};
const identity=['run_id','account_id','site_id','customer_id','price_id','subscription_id','operation_id'];
function shape(v,extras){
 need(v&&typeof v==='object'&&[Object.prototype,null].includes(Object.getPrototypeOf(v)));
 const d=Object.getOwnPropertyDescriptors(v),keys=[...identity,'phase','code',...extras];
 need(Reflect.ownKeys(d).length===keys.length&&keys.every(k=>d[k]?.enumerable&&Object.hasOwn(d[k],'value')));
 need(v.code==='OK'&&identity.every(k=>billingUuid(v[k])));
}
export function renewalInstant(value){
 need(typeof value==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value));
 const n=Date.parse(value)/1000;need(Number.isSafeInteger(n)&&n>0);return n;
}
function sameIdentity(value,original){need(original&&identity.every(k=>value[k]===original[k]));}
/** Fixed wire contracts shared by the owner and its private client. No targets are accepted. */
export function validateRenewalBoundary(value,original,initial){
 shape(value,['renewal_admission_id','boundary_time','period_start','period_end']);sameIdentity(value,original);
 const end=renewalInstant(initial.paid_through),start=renewalInstant(value.period_start),next=renewalInstant(value.period_end);
 need(value.phase==='RENEWAL_BOUNDARY'&&billingUuid(value.renewal_admission_id)&&start===end&&renewalInstant(value.boundary_time)===end&&end+3600<next);return Object.freeze({...value});
}
export function validateRenewalFinalization(value,original,boundary){
 shape(value,['renewal_admission_id','publication_id','period_start','paid_through','access_evaluated_at','access_valid_until']);sameIdentity(value,original);
 const start=renewalInstant(boundary.period_start),evaluated=start+3600;
 need(value.phase==='RENEWAL_SETTLED_LOCAL'&&billingUuid(value.renewal_admission_id)&&value.renewal_admission_id===boundary.renewal_admission_id&&value.publication_id===value.renewal_admission_id
  &&renewalInstant(value.period_start)===start&&renewalInstant(value.paid_through)===renewalInstant(boundary.period_end)&&renewalInstant(value.access_evaluated_at)===evaluated&&renewalInstant(value.access_valid_until)===evaluated+300);return Object.freeze({...value});
}
