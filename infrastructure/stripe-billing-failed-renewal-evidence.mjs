import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {billingUuid} from './stripe-billing-provision.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {exactRecord,failedStages,instantNanos,validateFailedRenewalPublication} from './stripe-billing-failed-renewal-response.mjs';

const need=ok=>{if(!ok)throw billingError('FAILED_RENEWAL_EVIDENCE');};
const flags=['owned','original','lineage','cash','operations','journal'];
// The arm is a remote fixture opportunity and has no durable financial receipt.
const durable=failedStages.at(-1).retained.filter(k=>!k.startsWith('arm_'));
const common=['schema_version','event_id','event_type','occurred_at','site_id','customer_id','subscription_id','product_id','payment_id','billing_record_id'];
const paymentKeys=[...common,'external_order_id','payment_status','amount_minor','currency','billing_kind','price_id','price_version','period_start','period_end'];
const lifecycleKeys=[...common,'revision','state','access_allowed','access_valid_until'];
const ns=seconds=>BigInt(seconds)*1000000000n;

function five(values){
 need(Array.isArray(values)&&Object.getPrototypeOf(values)===Array.prototype);
 const descriptors=Object.getOwnPropertyDescriptors(values);
 need(Reflect.ownKeys(descriptors).length===6&&descriptors.length.value===5);
 return [0,1,2,3,4].map(i=>{need(descriptors[i]?.enumerable&&Object.hasOwn(descriptors[i],'value'));return descriptors[i].value;});
}
function checkRow(scope,row,c,settlement){
 const r=exactRecord(row,['owner_run','source_sha','frozen_time','lineage','callbacks','proof']);
 need(r.owner_run===scope.runId&&r.owner_run===c.runId&&r.source_sha===scope.expectedSource&&r.source_sha===c.expectedSource);
 // postgres.js returns a top-level int8 as a string. jsonb lineage integers are JSON numbers.
 need(r.frozen_time===c.frozenTime||r.frozen_time===String(c.frozenTime));
 const proof=exactRecord(r.proof,flags);need(flags.every(k=>proof[k]===true));
 const lineage=exactRecord(r.lineage,durable);
 for(const k of durable){
  if(k.endsWith('_at')||['period_start','period_end','boundary_time','grace_deadline'].includes(k))
   need(instantNanos(lineage[k])===instantNanos(settlement[k]));
  else need(lineage[k]===settlement[k]);
 }
 return five(r.callbacks).map(e=>exactRecord(e,['id','type','kind','payload','delivered']));
}
function callbackKind(scope,e,s,c){
 need(billingUuid(e.id));
 const payment=e.kind==='PAYMENT_SUCCEEDED',p=exactRecord(e.payload,payment?paymentKeys:lifecycleKeys);
 need(p.event_id===e.id&&p.event_type===e.type&&p.site_id===scope.siteId&&p.product_id===scope.productId&&p.subscription_id===scope.subscriptionId&&p.customer_id===scope.customerId);
 const initial=p.billing_record_id===s.initial_bill_id;
 need(initial||p.billing_record_id===s.failed_bill_id);
 const historical=ns(initial?c.frozenTime:s.finalization_time);
 if(payment){
  need(p.schema_version===3&&e.type==='payment.succeeded'&&p.payment_id===(initial?s.initial_payment_id:s.recovery_payment_id));
  need(p.external_order_id===null&&p.payment_status==='SUCCEEDED'&&p.amount_minor===2000&&p.currency==='EUR');
  need(p.billing_kind===(initial?'INITIAL':'RENEWAL')&&p.price_id===scope.priceId&&p.price_version===1);
  instantNanos(p.occurred_at); // Cash event occurrence is independently compared to payment_events in SQL.
  const start=instantNanos(p.period_start),end=instantNanos(p.period_end);
  need(initial?start<=historical&&end===instantNanos(s.period_start):start===instantNanos(s.period_start)&&end===instantNanos(s.period_end));
 }else{
  const failed=e.kind==='RENEWAL_FAILED';
  need(e.kind==='PERIOD_PAID'||failed&&!initial);
  need(p.schema_version===4&&e.type==='subscription.updated'&&p.payment_id===null&&p.state===(failed?'PAST_DUE':'ACTIVE')&&p.access_allowed===true);
  need(Number.isSafeInteger(p.revision)&&p.revision>=0&&(initial||p.revision===s.recovery_revision-(failed?1:0)));
  need(instantNanos(p.occurred_at)===historical&&instantNanos(p.access_valid_until)===historical+ns(300));
 }
 return (initial?'INITIAL':'RENEWAL')+'/'+e.kind;
}

/** Compare all five actual SQL outbox bodies to their independent signed-receiver digests. */
export function validateBillingFailedRenewalEvidence(scope,row,inspection,c,raw){
 const s=validateFailedRenewalPublication(raw,scope,c),events=checkRow(scope,row,c,s);
 const inspected=exactRecord(inspection,['count','duplicates','events']);need(inspected.count===5&&inspected.duplicates===0);
 const receipts=five(inspected.events).map(e=>exactRecord(e,['event_id','event_type','count','sha256']));
 const kinds=new Set(),ids=new Set();
 for(const e of events){
  need(e.delivered===true&&!ids.has(e.id));ids.add(e.id);kinds.add(callbackKind(scope,e,s,c));
  const match=receipts.filter(r=>r.event_id===e.id);need(match.length===1&&match[0].count===1&&match[0].event_type===e.type);
  const canonical=JSON.stringify(Object.fromEntries(Object.keys(e.payload).sort().map(k=>[k,e.payload[k]])));
  need(match[0].sha256===createHash('sha256').update(canonical).digest('hex'));
 }
 need(kinds.size===5);
 return Object.freeze({ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true});
}

export async function proveBillingFailedRenewalEvidence({configuration:c,bootstrap,fixture,settlement},limits,dependencies={}){
 const scope=fixture.resources();
 need(scope.runId===c.runId&&scope.expectedSource===c.expectedSource);
 const ids=['accountId','siteId','customerId','subscriptionId','operationId','attemptId','productId','priceId'].map(k=>scope[k]);need(ids.every(billingUuid));
 const hashes=[scope.createKeyHash,scope.confirmKeyHash];need(hashes.every(v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)));
 const s=validateFailedRenewalPublication(settlement,scope,c);
 const args=[...ids,...hashes,scope.endpoints.demo,c.runId,c.expectedSource,c.frozenTime,s.renewal_admission_id];
 const query=await readFile(new URL('./stripe-billing-failed-renewal-proof.sql',import.meta.url),'utf8');
 const connect=dependencies.connect??createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');
 let row;
 need(await bootstrap.withDatabaseCredentials(async credentials=>{
  billingCheck(limits);
  const sql=connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});
  let closePromise;
  const close=()=>closePromise??=sql.end({timeout:0});
  try{
   row=await billingBounded(limits,async l=>{
    const abort=()=>{void close().catch(()=>{});};
    l.signal.addEventListener('abort',abort,{once:true});
    try{
     for(;;){
      const rows=await sql.begin('read only',async tx=>{
       await tx.unsafe("set local statement_timeout='5000'");billingCheck(l);return tx.unsafe(query,args);
      });
      billingCheck(l);need(rows.length===1);
      const events=checkRow(scope,rows[0],c,s);
      const kinds=new Set(events.map(e=>callbackKind(scope,e,s,c)));
      need(kinds.size===5&&new Set(events.map(e=>e.id)).size===5&&events.every(e=>typeof e.delivered==='boolean'));
      if(events.every(e=>e.delivered))return rows[0];
      await billingBounded(l,()=>new Promise(r=>setTimeout(r,250)),1000);
     }
    }finally{l.signal.removeEventListener('abort',abort);}
   },90000);
   billingCheck(limits);return true;
  }finally{await billingBounded({deadline:Date.now()+5000},close,5000);}
 },limits)===true);
 billingCheck(limits);const inspection=await fixture.inspectCallbacks(limits);billingCheck(limits);
 return validateBillingFailedRenewalEvidence(scope,row,inspection,c,s);
}
