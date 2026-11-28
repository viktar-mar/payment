import {createHash} from 'node:crypto';
import {scope,responses,id,F,E,T,N,iso} from './failed-renewal-descriptors.mjs';
export const flags=['owned','original','lineage','cash','operations','journal'];
export function data(){
 const settlement=responses().at(-1),s=scope();
 const common={site_id:s.siteId,customer_id:s.customerId,subscription_id:s.subscriptionId,product_id:s.productId};
 const event=(n,type,kind,fields)=>{const payload={...common,event_id:id(n),event_type:type,...fields};return {id:id(n),type,kind,delivered:true,payload};};
 const payment=(n,kind,p,b,start,end)=>event(n,'payment.succeeded','PAYMENT_SUCCEEDED',{schema_version:3,occurred_at:iso(start),payment_id:id(p),billing_record_id:id(b),external_order_id:null,payment_status:'SUCCEEDED',amount_minor:2000,currency:'EUR',billing_kind:kind,price_id:s.priceId,price_version:1,period_start:iso(start),period_end:iso(end)});
 const lifecycle=(n,kind,b,at,revision,state)=>event(n,'subscription.updated',kind,{schema_version:4,occurred_at:iso(at),revision,state,access_allowed:true,access_valid_until:iso(at+300),billing_record_id:id(b),payment_id:null});
 const callbacks=[payment(101,'INITIAL',12,11,F,E),lifecycle(102,'PERIOD_PAID',11,F,2,'ACTIVE'),lifecycle(103,'RENEWAL_FAILED',23,T,3,'PAST_DUE'),payment(104,'RENEWAL',42,23,E,N),lifecycle(105,'PERIOD_PAID',23,T,4,'ACTIVE')];
 const lineage=Object.fromEntries(Object.entries(settlement).filter(([k])=>!['phase','code','run_id','account_id','site_id','customer_id','price_id','operation_id','subscription_id','source_sha','original_frozen_time'].includes(k)&&!k.startsWith('access_')&&!k.startsWith('arm_')));
 return {settlement,row:{owner_run:s.runId,source_sha:s.expectedSource,frozen_time:F,lineage,callbacks,proof:Object.fromEntries(flags.map(k=>[k,true]))}};
}
export const inspect=cs=>({count:cs.length,duplicates:0,events:cs.map(({id:event_id,type:event_type,payload})=>({event_id,event_type,count:1,sha256:createHash('sha256').update(JSON.stringify(Object.fromEntries(Object.keys(payload).sort().map(k=>[k,payload[k]])))).digest('hex')}))});
