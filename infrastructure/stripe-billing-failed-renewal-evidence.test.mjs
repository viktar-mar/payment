import test,{before,after} from 'node:test';import assert from 'node:assert/strict';import {createHash} from 'node:crypto';
import {opts,scope,responses,id,F,E,T,N,G,iso} from './fixtures/failed-renewal-descriptors.mjs';
import {validateBillingFailedRenewalEvidence as validate,proveBillingFailedRenewalEvidence as prove} from './stripe-billing-failed-renewal-evidence.mjs';
import {createHmac} from 'node:crypto';
import {Readable} from 'node:stream';
import {EventEmitter} from 'node:events';
import {createReceiver} from './callback-receiver.mjs';
const saved=globalThis.fetch;let escaped=0;before(()=>{globalThis.fetch=()=>{escaped++;throw Error('blocked');};});after(()=>{globalThis.fetch=saved;assert.equal(escaped,0);});
const flags=['owned','original','lineage','cash','operations','journal'],result={ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true};
function data(){
 const settlement=responses().at(-1),s=scope();
 const common={site_id:s.siteId,customer_id:s.customerId,subscription_id:s.subscriptionId,product_id:s.productId};
 const event=(n,type,kind,fields)=>{const payload={...common,event_id:id(n),event_type:type,...fields};return {id:id(n),type,kind,delivered:true,payload};};
 const payment=(n,kind,p,b,start,end)=>event(n,'payment.succeeded','PAYMENT_SUCCEEDED',{schema_version:3,occurred_at:iso(start),payment_id:id(p),billing_record_id:id(b),external_order_id:null,payment_status:'SUCCEEDED',amount_minor:2000,currency:'EUR',billing_kind:kind,price_id:s.priceId,price_version:1,period_start:iso(start),period_end:iso(end)});
 const lifecycle=(n,kind,b,at,revision,state)=>event(n,'subscription.updated',kind,{schema_version:4,occurred_at:iso(at),revision,state,access_allowed:true,access_valid_until:iso(at+300),billing_record_id:id(b),payment_id:null});
 const callbacks=[payment(101,'INITIAL',12,11,F,E),lifecycle(102,'PERIOD_PAID',11,F,2,'ACTIVE'),lifecycle(103,'RENEWAL_FAILED',23,T,3,'PAST_DUE'),payment(104,'RENEWAL',42,23,E,N),lifecycle(105,'PERIOD_PAID',23,T,4,'ACTIVE')];
 const lineage=Object.fromEntries(Object.entries(settlement).filter(([k])=>!['phase','code','run_id','account_id','site_id','customer_id','price_id','operation_id','subscription_id','source_sha','original_frozen_time'].includes(k)&&!k.startsWith('access_')&&!k.startsWith('arm_')));
 return {settlement,row:{owner_run:s.runId,source_sha:s.expectedSource,frozen_time:F,lineage,callbacks,proof:Object.fromEntries(flags.map(k=>[k,true]))}};
}
const inspect=cs=>({count:cs.length,duplicates:0,events:cs.map(({id:event_id,type:event_type,payload})=>({event_id,event_type,count:1,sha256:createHash('sha256').update(JSON.stringify(Object.fromEntries(Object.keys(payload).sort().map(k=>[k,payload[k]])))).digest('hex')}))});
const verify=(f,i=inspect(f.row.callbacks))=>validate(scope(),f.row,i,opts(),f.settlement);
test('exact five signed digests and actual persisted graph, distinct historical and final clocks',()=>{const f=data();assert.deepEqual(verify(f),result);});
for(const [name,change] of [
 ...flags.map(k=>[k,f=>f.row.proof[k]=false]),['source',f=>f.row.source_sha='0'.repeat(40)],['run',f=>f.row.owner_run=id(99)],['debt',f=>f.row.lineage.failed_bill_id=id(99)],['other ACK',f=>f.row.lineage.recovery_confirm_operation_id=id(99)],['other initial',f=>f.row.lineage.initial_payment_id=id(99)],['no AFTER',f=>delete f.row.lineage.grace_after_ready_id],['wrong final access',f=>f.settlement.access_evaluated_at=iso(T)],['wrong failed time',f=>f.row.callbacks[2].payload.occurred_at=iso(G+1)],['wrong recovered callback',f=>f.row.callbacks[4].payload.access_valid_until=iso(G+301)],['replacement debt',f=>f.row.callbacks[3].payload.billing_record_id=id(92)],['extra method event',f=>f.row.callbacks.push({...f.row.callbacks[2],kind:'METHOD_UPDATED'})],['missing event',f=>f.row.callbacks.splice(2,1)],['undelivered',f=>f.row.callbacks[2].delivered=false],['foreign payload',f=>f.row.callbacks[0].payload.unknown='hidden'],['duplicate paid',f=>f.row.callbacks[4]=f.row.callbacks[1]]
])test('reject '+name+' even with matching new digests',()=>{const f=data();change(f);assert.throws(()=>verify(f));});
test('receiver alone cannot add, drop or duplicate an event or change a digest',()=>{const f=data();for(const change of [i=>i.duplicates++,i=>i.events.pop(),i=>i.events[2].sha256='0'.repeat(64),i=>i.events[2].count++,i=>i.events[1]=i.events[0]]){const i=inspect(f.row.callbacks);change(i);assert.throws(()=>verify(f,i));}});
test('the receiver accepts each signed wire body once and rejects the same body with a foreign signature',async()=>{
 const f=data(),key=Buffer.alloc(32,7);let handle;
 createReceiver({},{createServer:fn=>{handle=fn;return new EventEmitter();}});
 async function request(url,body,headers={}){
  const req=Readable.from(body?[Buffer.from(body)]:[]);let status,value;
  Object.assign(req,{url,method:body?'POST':'GET',headers:{'content-type':'application/json',...headers},rawHeaders:[]});
  await handle(req,{headersSent:false,writeHead:n=>{status=n;},end:out=>{if(out)value=JSON.parse(out);}});
  return {status,value};
 }
 assert.equal((await request('/configure',JSON.stringify({secret:key.toString('base64url'),secret_version:1}))).status,204);
 for(const {id,payload} of f.row.callbacks){
  const body=JSON.stringify(Object.fromEntries(Object.keys(payload).sort().map(k=>[k,payload[k]]))),ts=String(Math.floor(Date.now()/1000));
  const headers={'payment-timestamp':ts,'payment-event-id':id,'payment-secret-version':'1','payment-signature':'v1='+'0'.repeat(64)};
  assert.equal((await request('/callback',body,headers)).status,401);
  headers['payment-signature']='v1='+createHmac('sha256',key).update(ts+'.'+body).digest('hex');
  assert.equal((await request('/callback',body,headers)).status,204);
 }
 const inspection=await request('/inspection/digests');assert.equal(inspection.status,200);assert.deepEqual(verify(f,inspection.value),result);
});
test('bounded fixed read-only SQL and exact original scope; close sql before signed-receiver inspection',async()=>{
 const f=data(),calls=[],s=scope();let live=false;
 const bootstrap={withDatabaseCredentials:async cb=>{live=true;try{return await cb({password:'inert'});}finally{live=false;}}},fixture={resources:scope,inspectCallbacks:async()=>{assert.equal(live,false);calls.push('receiver');return inspect(f.row.callbacks);}};
 const connect=()=>({begin:async(mode,cb)=>{assert.equal(mode,'read only');return cb({unsafe:async(q,p)=>{if(q.startsWith('set local')){assert.match(q,/5000/);return [];}assert.match(q,/owned_grace_successor_projected/);assert.ok(p.includes(s.runId)&&p.includes(s.expectedSource)&&p.includes(s.operationId)&&p.includes(s.attemptId));return [f.row];}});},end:async()=>calls.push('end')});
 assert.deepEqual(await prove({configuration:opts(),fixture,bootstrap,settlement:f.settlement},{deadline:Date.now()+5000},{connect}),result);assert.deepEqual(calls,['end','receiver']);
});
