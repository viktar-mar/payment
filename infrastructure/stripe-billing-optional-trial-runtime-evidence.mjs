import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import {setTimeout as delay} from 'node:timers/promises';
import {billingCallbackDigest} from './stripe-billing-evidence.mjs';
import {billingBounded, billingCheck, billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const own = new URL('./stripe-billing-optional-trial-',import.meta.url);
const need = value=>{if(!value)throw billingError('OPTIONAL_TRIAL_EVIDENCE');};
const one = rows=>{need(Array.isArray(rows)&&rows.length===1);return rows[0];};
const providerRef = (value,prefix)=>typeof value==='string'&&value.startsWith(prefix+'_')
  && /^[A-Za-z0-9]{1,250}$/.test(value.slice(prefix.length+1));
const iso = seconds=>{need(Number.isSafeInteger(Number(seconds)));return new Date(Number(seconds)*1000).toISOString();};

const originalQuery = `
select a.subscription_reference,a.invoice_reference,a.item_reference,a.trial_start,a.trial_end,
       p.original_bill_id,p.trial_started_event_id
from stripe_billing_optional_trial_acknowledgements a
join stripe_billing_optional_trial_pauses p on p.clock_intent=a.clock_intent and p.subscription_id=a.subscription_id
 and p.create_operation=a.create_operation and p.frozen_time=a.trial_end
join stripe_billing_optional_trial_clock_advances advance on advance.clock_intent=a.clock_intent
 and advance.target=a.trial_end and advance.status='ACKNOWLEDGED' and advance.ready_at is not null
where (a.run_id,a.clock_intent,a.subscription_id,a.create_operation,a.account_id,a.site_id,a.customer_id,a.product_id,a.price_id)
   = ($1::uuid,$2::uuid,$3::uuid,$4::uuid,$5::uuid,$6::uuid,$7::uuid,$8::uuid,$9::uuid)
limit 2`;

const resumeQuery = `
select p.billing_record_id,r.invoice_reference,r.period_end,r.frozen_time,r.intent_reference
from stripe_billing_owned_optional_trial_resumes r
join stripe_billing_optional_trial_pending_resumptions p on p.clock_intent=r.clock_intent and p.resume_operation=r.resume_operation
where (r.clock_intent,r.subscription_id,r.setup_operation,r.resume_operation)=($1::uuid,$2::uuid,$3::uuid,$4::uuid)
 and r.status='ACKNOWLEDGED' limit 2`;

const paidClaimQuery = `
select 1 from stripe_billing_owned_optional_trial_payment_confirmations f
where (f.clock_intent,f.resume_operation,f.billing_record_id,f.invoice_reference,f.intent_reference)
 = ($1::uuid,$2::uuid,$3::uuid,$4::text,$5::text) and f.status='ACKNOWLEDGED' and f.charge_reference is not null limit 2`;

const callbacksQuery = `
select e.id, e.callback_type, e.payload,
       e.payment_account_id=$2::uuid and e.endpoint_url='http://callback-receiver:8080/callback'
         and ((e.source_kind='SUBSCRIPTION' and e.subscription_id=$3::uuid and e.payment_id is null)
              or (e.source_kind='PAYMENT' and e.subscription_id is null and e.payment_id=$4::uuid)) callback_bound,
       e.status='DELIVERED' and e.attempt_count=1 and e.lease_owner is null
          and (select count(*) from outbox_deliveries d where d.outbox_event_id=e.id)=1
          and exists(select 1 from outbox_deliveries d where d.outbox_event_id=e.id
               and d.attempt_number=1 and d.delivery_mode='AUTOMATIC' and d.status='SUCCEEDED' and d.http_status=204) delivered,
       e.status='PENDING' and e.attempt_count<=1 pending
from outbox_events e
where e.site_id=$1::uuid
limit 5`;

/** Open only a per-proof connection scoped to the freshly verified bootstrap. Fixed SQL is always read only. */
async function readOnly(bootstrap, connect, limits, proof) {
  let entered = false, result;
  need(await bootstrap.withDatabaseCredentials(async credentials=>{
    billingCheck(limits);
    need(!entered);
    entered = true;
    const sql = connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});
    let closing;
    const close = ()=>closing??=(async()=>sql.end({timeout:0}))();
    const abort = ()=>{void close().catch(()=>{});};
    limits.signal?.addEventListener('abort',abort,{once:true});
    try {
      const query = async (statement,args)=>{
        billingCheck(limits);
        return sql.begin('read only',async tx=>{
          await tx.unsafe("set local statement_timeout='10000'");
          await tx.unsafe('set local jit=off');
          await tx.unsafe('set local join_collapse_limit=1');
          await tx.unsafe('set local from_collapse_limit=1');
          billingCheck(limits);
          const rows = await tx.unsafe(statement,args);
          billingCheck(limits);
          return rows;
        });
      };
      result = await proof(query);
      return true;
    } finally {
      limits.signal?.removeEventListener('abort',abort);
      await billingBounded({deadline:Date.now()+5000},close,5000);
    }
  },limits)===true && entered);
  billingCheck(limits);
  return result;
}

/** Provider checks run in the JVM producer. These separately prove local facts and signed delivery. */
export async function createOptionalTrialRuntimeEvidence({configuration,bootstrap,fixture},limits,dependencies={}) {
  billingCheck(limits);
  const connect = dependencies.connect??createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');
  const [pauseSql,resumeSql] = await Promise.all(['pause-proof.sql','resume-proof.sql'].map(name=>readFile(new URL(own.href+name),'utf8')));
  const binding = fixture.resources(), keys = ['accountId','siteId','customerId','productId','priceId'];
  const scope = Object.freeze(Object.fromEntries(keys.map(key=>[key,binding[key]])));
  need(keys.every(key=>billingUuid(scope[key])) && binding.runId===configuration.runId
    && binding.expectedSource===configuration.expectedSource && binding.siteKey==='s5a-'+configuration.runId.replaceAll('-',''));

  let phase='PAUSED', original, pending, pausedEvent;
  function current(value, expected, child) {
    billingCheck(child);
    need(phase===expected && value.stage===expected && value.runId===configuration.runId);
    const latest = fixture.resources();
    need(keys.every(key=>latest[key]===scope[key])&&latest.runId===binding.runId&&latest.expectedSource===binding.expectedSource);
    phase = 'STOPPED';
  }
  const transaction = (child,proof)=>readOnly(bootstrap,connect,child,proof);
  const account = configuration.approvedBinding.externalAccountId;
  return Object.freeze({
    async pause(value,child) {
      current(value,'PAUSED',child);
      await transaction(child,async read=>{
        const row = one(await read(originalQuery,[value.runId,value.clockIntentId,value.subscriptionId,value.createOperationId,
          scope.accountId,scope.siteId,scope.customerId,scope.productId,scope.priceId]));
        need([row.original_bill_id,row.trial_started_event_id].every(billingUuid)
          && providerRef(row.subscription_reference,'sub') && providerRef(row.invoice_reference,'in')
          && providerRef(row.item_reference,'si') && Number(row.trial_start)===value.frozenTime && Number(row.trial_end)===value.trialEnd);
        const args=[scope.siteId,binding.siteKey,scope.customerId,scope.accountId,scope.productId,scope.priceId,
          value.subscriptionId,value.createOperationId,row.original_bill_id,row.trial_started_event_id,row.subscription_reference,
          row.invoice_reference,iso(row.trial_start),iso(row.trial_end)];
        const proof = one(await read(pauseSql,args));
        need(proof.external_account_id===account && proof.subscription_id===value.subscriptionId
          && proof.item_reference===row.item_reference && proof.billing_record_id===row.original_bill_id
          && proof.trial_started_event_id===row.trial_started_event_id && billingUuid(proof.paused_event_id)
          && proof.paused_at instanceof Date && proof.paused_at.getTime()===value.trialEnd*1000);
        original = Object.freeze({args,subscription:value.subscriptionId,clock:value.clockIntentId,
          bill:row.original_bill_id,started:row.trial_started_event_id,item:row.item_reference,trialEnd:value.trialEnd});
        pausedEvent=proof.paused_event_id;
      });
      phase='OPEN';
      return true;
    },
    async pending(value,child) {
      current(value,'OPEN',child);
      need(value.clockIntentId===original.clock && value.subscriptionId===original.subscription);
      await transaction(child,async read=>{
        const row = one(await read(resumeQuery,[original.clock,original.subscription,value.setupOperationId,value.resumeOperationId]));
        need(billingUuid(row.billing_record_id) && row.billing_record_id!==original.bill
          && providerRef(row.invoice_reference,'in') && row.invoice_reference!==original.args[11]
          && providerRef(row.intent_reference,'pi') && Number(row.frozen_time)===original.trialEnd
          && Number(row.period_end)>original.trialEnd);
        const args=[...original.args,pausedEvent,value.setupOperationId,value.resumeOperationId,row.invoice_reference,
          iso(row.frozen_time),iso(row.period_end),row.billing_record_id];
        const proof = one(await read(resumeSql,[...args,null,'PENDING']));
        need(proof.external_account_id===account && proof.item_reference===original.item && proof.subscription_id===original.subscription
          && proof.trial_bill_id===original.bill && proof.resume_bill_id===row.billing_record_id && proof.payment_id===null
          && proof.cash_event_id===null && proof.period_event_id===null);
        pending = Object.freeze({args,operation:value.resumeOperationId,setup:value.setupOperationId,
          invoice:row.invoice_reference,intent:row.intent_reference,bill:row.billing_record_id});
      });
      phase='PAID';
      return true;
    },
    async paid(value,child) {
      current(value,'PAID',child);
      need(value.subscriptionId===original.subscription && value.clockIntentId===original.clock
        && value.resumeOperationId===pending.operation && value.setupOperationId===pending.setup);
      const completed = await transaction(child,async read=>{
        one(await read(paidClaimQuery,[original.clock,pending.operation,pending.bill,pending.invoice,pending.intent]));
        const proof = one(await read(resumeSql,[...pending.args,pending.intent,'PAID']));
        need(proof.external_account_id===account && proof.item_reference===original.item && proof.subscription_id===original.subscription
          && proof.trial_bill_id===original.bill && proof.resume_bill_id===pending.bill
          && [proof.payment_id,proof.cash_event_id,proof.period_event_id].every(billingUuid));
        const expected = new Map([[original.started,'subscription.updated'],[pausedEvent,'subscription.updated'],
          [proof.cash_event_id,'payment.succeeded'],[proof.period_event_id,'subscription.updated']]);
        need(expected.size===4);
        for(;;) {
          const rows = await read(callbacksQuery,[scope.siteId,scope.accountId,original.subscription,proof.payment_id]);
          need(rows.length===expected.size && new Set(rows.map(row=>row.id)).size===expected.size
            && rows.every(row=>row.callback_bound===true && expected.get(row.id)===row.callback_type
              && (row.delivered===true || row.pending===true)
              && row.payload?.event_id===row.id && row.payload?.event_type===row.callback_type
              && row.payload?.site_id===scope.siteId && row.payload?.subscription_id===original.subscription));
          if(rows.every(row=>row.delivered===true))return rows;
          await delay(250,undefined,{signal:child.signal});
        }
      });
      const inspection = await fixture.inspectCallbacks(child);
      billingCheck(child);
      need(inspection?.duplicates===0 && inspection.count===4 && inspection.events?.length===4);
      need(completed.every(row=>inspection.events.filter(event=>event.event_id===row.id
        && event.event_type===row.callback_type && event.count===1 && event.sha256===billingCallbackDigest(row.payload)).length===1));
      return true;
    },
  });
}
