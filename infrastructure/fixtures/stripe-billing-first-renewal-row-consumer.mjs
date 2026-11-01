/** Hosted composition bridge: stdin is the actual read-only SQL row and signature-verified deliveries. */
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {validateBillingFirstRenewalEvidence} from '../stripe-billing-first-renewal-evidence.mjs';
try {
const chunks=[];let bytes=0;
for await(const chunk of process.stdin){bytes+=chunk.length;if(bytes>262144)throw new Error('combined_evidence_input_limit');chunks.push(chunk);}
const input=Buffer.concat(chunks).toString('utf8');
const {scope,row,configuration,boundary,settlement,delivered}=JSON.parse(input);
const canonical=p=>JSON.stringify(Object.fromEntries(Object.keys(p).sort().map(k=>[k,p[k]])));
const inspection={count:delivered.length,duplicates:delivered.length-new Set(delivered.map(p=>p.event_id)).size,events:delivered.map(p=>({event_id:p.event_id,event_type:p.event_type,count:1,sha256:createHash('sha256').update(canonical(p)).digest('hex')}))};
const validate=i=>validateBillingFirstRenewalEvidence(scope,row,i,configuration,boundary,settlement);
assert.deepEqual(validate(inspection),{ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true});
for(const mutate of [i=>{i.events.pop();i.count--;},i=>i.duplicates++,i=>i.events[0].event_id='99999999-2222-4333-8444-555555555555',i=>i.events[0].sha256='0'.repeat(64)]){const bad=structuredClone(inspection);mutate(bad);assert.throws(()=>validate(bad));}
process.stdout.write('combined_actual_sql_and_signed_union_verified\n');
}catch {process.stderr.write('combined_actual_evidence_rejected\n');process.exitCode=1;}
