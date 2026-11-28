import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {setTimeout as delay} from 'node:timers/promises';
import {billingCancellationOrigin,validateBillingCancellationCallbackEvidence} from './stripe-billing-evidence.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const scheduledWitnesses=new WeakMap();
const scopeIds=['accountId','siteId','customerId','subscriptionId','operationId','attemptId','productId','priceId'];
const digest=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
function requireEvidence(value) { if(!value) throw billingError('EVIDENCE'); }

function ownedScope({configuration,fixture,phase,operations,baseline}) {
  const scope=fixture.resources(), graph=baseline?.graph;
  requireEvidence(configuration.selectedCase==='initial_cancel_withdraw'&&billingUuid(scope.runId)&&scope.runId===configuration.runId&&scope.runId===graph?.runId
    &&/^[a-f0-9]{40}$/.test(scope.expectedSource)&&scope.expectedSource===configuration.expectedSource
    &&scope.expectedSource===graph.expectedSource&&scope.siteKey==='s5a-'+scope.runId.replaceAll('-',''));
  requireEvidence(scopeIds.every(k=>billingUuid(scope[k]))&&digest(scope.createKeyHash)&&digest(scope.confirmKeyHash)
    &&['accountId','siteId','customerId','subscriptionId','operationId','priceId'].every(k=>scope[k]===graph[k])
    &&graph.priceVersion===1&&graph.priceReference===scope.priceReference
    &&Number.isSafeInteger(graph.periodStart)&&graph.periodStart>0&&Number.isSafeInteger(graph.periodEnd)&&graph.periodEnd>graph.periodStart
    &&['cashVerified','journalVerified','signedCallbacksVerified'].every(k=>baseline[k]===true));
  requireEvidence(['scheduled','withdrawn'].includes(phase)&&operations&&operations.schedule
    &&(phase==='scheduled'?operations.withdraw===undefined:operations.withdraw!=null));
  const commands=phase==='scheduled'?[operations.schedule]:[operations.schedule,operations.withdraw];
  requireEvidence(commands.every(o=>billingUuid(o.operationId)&&digest(o.keyHash))
    &&new Set([scope.operationId,scope.attemptId,...commands.map(o=>o.operationId)]).size===commands.length+2
    &&new Set([scope.createKeyHash,scope.confirmKeyHash,...commands.map(o=>o.keyHash)]).size===commands.length+2);
  return scope;
}

function validateTransition(receipt,command,mode,row,baseline) {
  const scheduled=mode==='PERIOD_END';
  requireEvidence(receipt?.operation_id===command.operationId&&receipt.key_hash===command.keyHash
    &&receipt.kind===(scheduled?'CANCEL':'WITHDRAW_CANCEL')&&receipt.mode===mode
    &&receipt.acknowledged===true&&receipt.boundary===baseline.periodEnd&&billingUuid(receipt.event_id)
    &&Number.isSafeInteger(receipt.expected_revision)&&receipt.expected_revision>=0&&receipt.revision===receipt.expected_revision+1);
  const callback=row.callbacks.find(e=>e.id===receipt.event_id)?.payload;
  requireEvidence(callback?.schema_version===4&&callback.event_type==='subscription.updated'
    &&callback.state===(scheduled?'CANCEL_AT_PERIOD_END':'ACTIVE')&&callback.revision===receipt.revision
    &&callback.payment_id===null&&callback.billing_record_id===null&&callback.access_allowed===true);
  const at=Date.parse(callback.occurred_at), until=Date.parse(callback.access_valid_until);
  requireEvidence(at>=1000*baseline.periodStart&&at<1000*baseline.periodEnd
    &&until===Math.min(at+300000,1000*baseline.periodEnd));
}

export function validateBillingCancellationEvidence(inputs,row,inspection,witness) {
  const scope=ownedScope(inputs), graph=inputs.baseline.graph, scheduled=inputs.phase==='scheduled';
  requireEvidence(row&&['cash','journal','original','closed_inventory'].every(k=>row[k]===true)
    &&billingUuid(row.payment_id)&&billingUuid(row.billing_record_id)
    &&Array.isArray(row.callbacks)&&Array.isArray(row.receipts)&&row.receipts.length===(scheduled?1:2));
  const original=witness?.original;
  requireEvidence(original&&row.payment_id===original.paymentId&&row.billing_record_id===original.billingRecordId
    &&row.original_paid_event_id===original.paidEventId
    &&row.callbacks.some(e=>e.id===original.cashEventId&&e.payload?.schema_version===3
      &&e.payload.payment_id===original.paymentId&&e.payload.billing_record_id===original.billingRecordId));
  const current=validateBillingCancellationCallbackEvidence(scope,row,inspection).graph;
  requireEvidence(Object.keys(current).every(k=>current[k]===graph[k]));
  const paid=row.callbacks.find(e=>e.id===row.original_paid_event_id)?.payload;
  requireEvidence(billingUuid(row.original_paid_event_id)&&paid?.schema_version===4&&paid.event_type==='subscription.updated'
    &&paid.state==='ACTIVE'&&paid.access_allowed===true&&paid.billing_record_id===row.billing_record_id&&paid.payment_id===null);
  const [schedule,withdraw]=row.receipts;
  const digests=new Map(inspection.events.map(event=>[event.event_id,event.sha256]));
  const baselineEvents=Object.entries(original.callbackDigests);
  requireEvidence(digests.size===baselineEvents.length+row.receipts.length
    &&baselineEvents.every(([id,sha])=>digests.get(id)===sha)
    &&row.receipts.every(r=>!Object.hasOwn(original.callbackDigests,r.event_id))
    &&schedule.expected_revision===original.revision);
  validateTransition(schedule,inputs.operations.schedule,'PERIOD_END',row,graph);
  requireEvidence(schedule.prior_operation===null&&schedule.prior_event===null&&schedule.prior_event_revision===null);
  if(withdraw) {
    requireEvidence(witness.scheduled?.operationId===schedule.operation_id&&witness.scheduled.eventId===schedule.event_id
      &&witness.scheduled.revision===schedule.revision&&witness.scheduled.callbackDigest===digests.get(schedule.event_id));
    validateTransition(withdraw,inputs.operations.withdraw,'WITHDRAW',row,graph);
    const direct=withdraw.prior_operation===schedule.operation_id&&withdraw.prior_event===null&&withdraw.prior_event_revision===null;
    const observed=withdraw.prior_operation===null&&withdraw.prior_event===schedule.event_id&&withdraw.prior_event_revision===schedule.revision;
    requireEvidence((direct||observed)&&withdraw.expected_revision===schedule.revision&&withdraw.event_id!==schedule.event_id);
  }
  requireEvidence(Number(row.revision)===(withdraw??schedule).revision
    &&(scheduled?row.cancellation_at!=null&&Number(row.cancellation_at)===graph.periodEnd:row.cancellation_at===null));
  return true;
}

export async function proveBillingCancellationEvidence(inputs,limits,dependencies={}) {
  billingCheck(limits);
  const scope=ownedScope(inputs), {operations,bootstrap,fixture,phase}=inputs;
  const witness={original:billingCancellationOrigin(inputs.baseline),scheduled:scheduledWitnesses.get(inputs.baseline)};
  requireEvidence(witness.original&&(phase==='scheduled'?!witness.scheduled:witness.scheduled));
  const query=await readFile(new URL('./stripe-billing-cancellation-proof.sql',import.meta.url),'utf8');
  const connect=dependencies.connect??createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');
  const args=[...scopeIds.map(k=>scope[k]),scope.createKeyHash,scope.confirmKeyHash,scope.endpoints.demo,scope.siteKey,
    operations.schedule.operationId,operations.schedule.keyHash,operations.withdraw?.operationId??null,operations.withdraw?.keyHash??null];
  let ready;
  try {
    await billingBounded(limits,async active=>{
      requireEvidence(await bootstrap.withDatabaseCredentials(async credentials=>{
        billingCheck(active);
        const sql=connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});
        let closing;
        const close=()=>closing??=sql.end({timeout:0});
        const abort=()=>{void close().catch(()=>{});};
        active.signal.addEventListener('abort',abort,{once:true});
        try {
          for(;;) {
            billingCheck(active);
            const rows=await sql.begin('read only',async tx=>{
              await tx.unsafe("set local statement_timeout='5000'");
              return tx.unsafe(query,args);
            });
            billingCheck(active);
            requireEvidence(rows.length===1);
            const row=rows[0],expected=phase==='scheduled'?1:2;
            requireEvidence(row.cash===true&&row.original===true&&row.journal===true&&row.closed_inventory===true
              &&Array.isArray(row.receipts)&&row.receipts.length<=expected&&Array.isArray(row.callbacks));
            if(row.receipts.length===expected&&row.receipts.every(r=>r.acknowledged===true)
                &&row.callbacks.every(c=>c.delivered===true)) {ready=row;return true;}
            await delay(250,undefined,{signal:active.signal});
          }
        } finally {
          active.signal.removeEventListener('abort',abort);
          await billingBounded({deadline:Date.now()+5000},close,5000);
        }
      },active)===true);
    },90000);
    const inspection=await billingBounded(limits,l=>fixture.inspectCallbacks(l),10000);
    billingCheck(limits);
    validateBillingCancellationEvidence(inputs,ready,inspection,witness);
    if(phase==='scheduled') {
      requireEvidence(!scheduledWitnesses.has(inputs.baseline));
      const schedule=ready.receipts[0];
      const callbackDigest=inspection.events.find(event=>event.event_id===schedule.event_id).sha256;
      scheduledWitnesses.set(inputs.baseline,Object.freeze({operationId:schedule.operation_id,eventId:schedule.event_id,
        revision:schedule.revision,callbackDigest}));
    }
    return true;
  } catch {
    billingCheck(limits);
    throw billingError('EVIDENCE');
  }
}
