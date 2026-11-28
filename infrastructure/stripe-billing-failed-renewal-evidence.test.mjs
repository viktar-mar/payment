import test,{before,after} from 'node:test';import assert from 'node:assert/strict';
import {opts,scope,id,T,G,iso} from './fixtures/failed-renewal-descriptors.mjs';
import {data,flags,inspect} from './fixtures/failed-renewal-evidence-rows.mjs';
import {validateBillingFailedRenewalEvidence as validate,proveBillingFailedRenewalEvidence as prove} from './stripe-billing-failed-renewal-evidence.mjs';
import {createHmac} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {Readable} from 'node:stream';
import {EventEmitter} from 'node:events';
import {createReceiver} from './callback-receiver.mjs';
const saved=globalThis.fetch;let escaped=0;before(()=>{globalThis.fetch=()=>{escaped++;throw Error('blocked');};});after(()=>{globalThis.fetch=saved;assert.equal(escaped,0);});
const result={ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true};
const verify=(f,i=inspect(f.row.callbacks))=>validate(scope(),f.row,i,opts(),f.settlement);
test('hosted evidence bridge exposes only four fixed failure codes and its exact success marker',async()=>{
 const source=await readFile(new URL('./fixtures/stripe-billing-failed-renewal-row-consumer.mjs',import.meta.url),'utf8');
 const fixture=await readFile(new URL('../backend/src/test/java/dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalScenarioIntegrationTest.java',import.meta.url),'utf8');
 assert.deepEqual([...source.matchAll(/\bfailureCode\s*=\s*(\d+)/g)].map(match=>Number(match[1])),[71,72,73,74]);
 assert.deepEqual([...source.matchAll(/process\.(?:stdout|stderr)\.write\(([^)]+)\)/g)].map(match=>match[1]),["'combined_actual_sql_and_signed_union_verified\\n'"]);
 assert.doesNotMatch(source,/\bconsole\.|node:(?:child_process|https?|net|fs)|\bfetch\s*\(/);
 assert.match(source,/process\.exitCode\s*=\s*failureCode\b/);
 for(const [code,stage] of [[71,'INPUT'],[72,'PUBLICATION'],[73,'POSITIVE'],[74,'QUEUED_RETRY']])
  assert.match(fixture,new RegExp('case '+code+'\\s*->\\s*"'+stage+'"'));
 assert.match(fixture,/default\s*->\s*"TRANSPORT"/);
});
test('shipped callback projection rejects any scoped retry and identifies the first automatic 204',async()=>{
 const sql=await readFile(new URL('./stripe-billing-failed-renewal-proof.sql',import.meta.url),'utf8');
 const delivery=sql.slice(sql.indexOf("'delivered',case")).replace(/\s+/g,' ');
 // The guard covers both pending and delivered branches and every retry status, including PENDING.
 assert.match(delivery,/when exists\s*\(\s*select 1 from outbox_retry_requests (\w+) where \1\.outbox_event_id\s*=\s*j\.id\s*\)\s*then null.*when j\.status='PENDING'/i);
 const terminal=delivery.slice(delivery.indexOf("when j.status='DELIVERED'"),delivery.indexOf('then true'));
 assert.match(terminal,/d\.attempt_number\s*=\s*1\b/);
 assert.match(terminal,/d\.delivery_mode\s*=\s*'AUTOMATIC'/);
 assert.match(terminal,/d\.status\s*=\s*'SUCCEEDED'/);
 assert.match(terminal,/d\.http_status\s*=\s*204\b/);
});
function proofWithDeliveryRows(f,markers){
 const calls=[];
 const connect=()=>({
  begin:async(mode,cb)=>{assert.equal(mode,'read only');return cb({unsafe:async q=>{
   if(q.startsWith('set local'))return [];
   calls.push('select');assert.ok(markers.length,'unexpected proof poll');
   const row=structuredClone(f.row);row.callbacks[2].delivered=markers.shift();return [row];
  }});},
  end:async()=>{calls.push('close');}
 });
 const bootstrap={withDatabaseCredentials:cb=>cb({})};
 const fixture={resources:scope,inspectCallbacks:async()=>{calls.push('receiver');return inspect(f.row.callbacks);}};
 return {calls,read:()=>prove({configuration:opts(),settlement:f.settlement,bootstrap,fixture},{deadline:Date.now()+5000},{connect})};
}
test('rejecting delivery projection for a queued retry terminates before polling or reading five matching digests',async()=>{
 const f=data(),p=proofWithDeliveryRows(f,[null]);
 await assert.rejects(p.read());
 assert.deepEqual(p.calls,['select','close']);
});
test('ordinary pending automatic first delivery may settle on a subsequent bounded read',async()=>{
 const f=data(),p=proofWithDeliveryRows(f,[false,true]);
 assert.deepEqual(await p.read(),result);
 assert.deepEqual(p.calls,['select','select','close','receiver']);
});
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
