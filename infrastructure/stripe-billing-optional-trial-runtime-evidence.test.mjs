import assert from 'node:assert/strict';
import test from 'node:test';
import {billingCallbackDigest} from './stripe-billing-evidence.mjs';
import {createOptionalTrialRuntimeEvidence} from './stripe-billing-optional-trial-runtime-evidence.mjs';

const id = n=>'81000000-1111-4222-8333-'+String(n).padStart(12,'0');
const external = 'acct_1UPiMpELswAd8tpR';
const configuration = {runId:id(1),expectedSource:'a'.repeat(40),approvedBinding:{externalAccountId:external}};
const binding = {runId:id(1),expectedSource:configuration.expectedSource,siteId:id(2),accountId:id(3),
  customerId:id(4),productId:id(5),priceId:id(6),siteKey:'s5a-'+id(1).replaceAll('-','')};
const start = 1800000000, end=start+604800, resumeEnd=end+30*86400;
const receipt = {runId:id(1),clockIntentId:id(7),subscriptionId:id(8),createOperationId:id(9),
  frozenTime:start,trialEnd:end,setupOperationId:id(10),resumeOperationId:id(11)};
const limit = ()=>({deadline:Date.now()+2000});
function fresh(fault) {
  let connects=0,closes=0,inspections=0;
  const reads = [], args = [binding.siteId,binding.siteKey,binding.customerId,binding.accountId,binding.productId,binding.priceId,
    receipt.subscriptionId,receipt.createOperationId,id(12),id(13),'sub_original','in_trial',new Date(start*1000).toISOString(),
    new Date(end*1000).toISOString()];
  const resumeArgs = [...args,id(14),receipt.setupOperationId,receipt.resumeOperationId,'in_resume',
    new Date(end*1000).toISOString(),new Date(resumeEnd*1000).toISOString(),id(15)];
  const events = [13,14,16,17].map(n=>{
    const type=n===16?'payment.succeeded':'subscription.updated';
    return {id:id(n),callback_type:type,callback_bound:n!==16||fault!=='foreign-cash-callback',delivered:true,payload:{event_id:id(n),event_type:type,
      site_id:binding.siteId,subscription_id:receipt.subscriptionId}};
  });
  const shared = {external_account_id:external,subscription_id:receipt.subscriptionId,item_reference:'si_original'};
  const settle = {trial_bill_id:id(12),resume_bill_id:id(15),...shared};
  const dependencies = {
    connect(config) {
      assert.equal(config.password,'ephemeral');
      connects++;
      return {end:async()=>{closes++;},begin:async (mode,inside)=>{
        assert.equal(mode,'read only');
        const settings=[];
        return inside({unsafe:async (query,values)=>{
          if(query.startsWith('set local ')){settings.push(query);return [];}
          assert.deepEqual(settings,["set local statement_timeout='10000'",'set local jit=off',
            'set local join_collapse_limit=1','set local from_collapse_limit=1']);
          if(query.includes('a.trial_start,a.trial_end')) {
            reads.push('original');
            assert.deepEqual(values,[receipt.runId,receipt.clockIntentId,receipt.subscriptionId,receipt.createOperationId,
              binding.accountId,binding.siteId,binding.customerId,binding.productId,binding.priceId]);
            return [{subscription_reference:'sub_original',invoice_reference:'in_trial',item_reference:'si_original',
              trial_start:start,trial_end:end,original_bill_id:id(12),trial_started_event_id:id(13)}];
          }
          if(query.startsWith('-- Read-only local evidence')) {
            reads.push('paused');
            assert.deepEqual(values,args);
            return [{...shared,billing_record_id:id(12),trial_started_event_id:id(13),paused_event_id:id(14),paused_at:new Date(end*1000)}];
          }
          if(query.includes('p.billing_record_id,r.invoice_reference')) {
            reads.push('resume');
            return [{billing_record_id:id(15),invoice_reference:'in_resume',period_end:resumeEnd,frozen_time:end,intent_reference:'pi_resume'}];
          }
          if(query.startsWith('-- Read-only evidence for the same paused trial')) {
            reads.push(values.at(-1));
            if (values.at(-1)==='PENDING') {
              assert.deepEqual(values,[...resumeArgs,null,'PENDING']);
              return [{...settle,payment_id:null,cash_event_id:null,period_event_id:null}];
            }
            assert.deepEqual(values,[...resumeArgs,'pi_resume','PAID']);
            return [{...settle,payment_id:id(18),cash_event_id:id(16),period_event_id:id(17)}];
          }
          if(query.includes('f.charge_reference is not null')) {
            reads.push('confirm');
            assert.deepEqual(values,[receipt.clockIntentId,receipt.resumeOperationId,id(15),'in_resume','pi_resume']);
            return fault==='missing-confirmation'?[]:[{checked:1}];
          }
          assert.ok(query.includes('from outbox_events e'));
          assert.match(query,/e\.source_kind='PAYMENT' and e\.subscription_id is null and e\.payment_id=\$4::uuid/);
          assert.deepEqual(values,[binding.siteId,binding.accountId,receipt.subscriptionId,id(18)]);
          reads.push('outbox');
          return events;
        }});
      }};
    },
  };
  const fixture = {resources:()=>binding,inspectCallbacks:async()=>{
    inspections++;
    return {count:4,duplicates:0,events:events.map((row,index)=>({event_id:row.id,event_type:row.callback_type,
      count:1,sha256:index===0&&fault==='invalid-digest'?'0'.repeat(64):billingCallbackDigest(row.payload)}))};
  }};
  const bootstrap = {withDatabaseCredentials:async callback=>callback({password:'ephemeral'})};
  return {create:()=>createOptionalTrialRuntimeEvidence({configuration,bootstrap,fixture},limit(),dependencies),
    stats:()=>({connects,closes,inspections,reads})};
}

test('proves the original local bills and four exact signed receipts using only fixed read-only queries',async()=>{
  const f=fresh(), verifier=await f.create();
  assert.equal(await verifier.pause({...receipt,stage:'PAUSED'},limit()),true);
  assert.equal(await verifier.pending({...receipt,stage:'OPEN'},limit()),true);
  assert.equal(await verifier.paid({...receipt,stage:'PAID'},limit()),true);
  assert.deepEqual(f.stats(),{connects:3,closes:3,inspections:1,reads:['original','paused','resume','PENDING','confirm','PAID','outbox']});
  await assert.rejects(verifier.paid({...receipt,stage:'PAID'},limit()),/OPTIONAL_TRIAL_EVIDENCE/);
});

for(const fault of ['missing-confirmation','invalid-digest','foreign-cash-callback']){
  test('cannot accept '+fault,async()=>{
    const f=fresh(fault), verifier=await f.create();
    await verifier.pause({...receipt,stage:'PAUSED'},limit());
    await verifier.pending({...receipt,stage:'OPEN'},limit());
    await assert.rejects(verifier.paid({...receipt,stage:'PAID'},limit()),/OPTIONAL_TRIAL_EVIDENCE/);
    assert.equal(f.stats().closes,3);
    assert.equal(f.stats().inspections,fault==='invalid-digest'?1:0);
  });
}
