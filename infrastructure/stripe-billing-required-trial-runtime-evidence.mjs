import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {setTimeout as delay} from 'node:timers/promises';
import {billingCallbackDigest} from './stripe-billing-evidence.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const graphIds=['runId','accountId','siteId','customerId','productId','priceId','subscriptionId','operationId'];
const createScopeKeys=[...graphIds,'createKeyHash'];
const scopeKeys=[...createScopeKeys,'expectedSource','siteKey','priceReference','trialPermitKeyHash','demoOrigin'];
const nativeKeys=['customer_reference','setup_reference','method_reference','subscription_reference','item_reference','invoice_reference'];
const eventType='subscription.updated';
const need=value=>{if(!value)throw billingError('REQUIRED_TRIAL_EVIDENCE');};

function ownedScope(configuration,fixture) {
 const current=fixture.resources();
 const scope=Object.freeze({...Object.fromEntries(scopeKeys.map(key=>[key,current?.[key]])),
  demoOrigin:current?.endpoints?.demo});
 const port=configuration.ports?.demo;
 need(configuration.selectedCase==='initial_required_trial'&&graphIds.every(key=>billingUuid(scope[key]))
   &&scope.runId===configuration.runId&&scope.expectedSource===configuration.expectedSource
   &&typeof scope.expectedSource==='string'&&/^[a-f0-9]{40}$/.test(scope.expectedSource)
   &&scope.siteKey==='s5a-'+scope.runId.replaceAll('-','')
   &&typeof scope.createKeyHash==='string'&&/^[a-f0-9]{64}$/.test(scope.createKeyHash)
   &&typeof scope.trialPermitKeyHash==='string'&&/^[a-f0-9]{64}$/.test(scope.trialPermitKeyHash)
   &&scope.trialPermitKeyHash!==scope.createKeyHash&&Number.isSafeInteger(port)&&port>=1024&&port<=65535
   &&scope.demoOrigin==='http://127.0.0.1:'+port
   &&typeof scope.priceReference==='string'&&/^price_[A-Za-z0-9]{1,249}$/.test(scope.priceReference));
 return scope;
}

// Reprove the journal, the consumed reservation, and no cash in the same snapshot as the outbox.
function withOutbox(proofQuery) {
 return `with required_trial as materialized (
${proofQuery}
)
select trial.*, (select count(*)=1 and bool_and(
       g.purpose='PROVIDER_CLIENT' and g.billing_schema='setup-v1' and g.consent_purpose='CREATE' and g.recurring_consent
       and g.customer_id=$4::uuid and g.payment_account_id=$2::uuid
       and g.billing_step_id=setup.step_id and g.billing_step_kind='SETUP_INTENT_CREATE'
       and g.billing_setup_reference=$11::text and g.billing_step_reference=$11::text
       and g.billing_customer_reference=$10::text and g.billing_owned_intent is null
       and g.confirmation_authority_scope='customer:'||$4::text and g.confirmation_key_digest=$18::text
       and g.client_session_id is not null and g.client_origin=$19::text and g.superseded_at is null
       and g.issued_at<=g.consumed_at and g.consumed_at<least(g.expires_at,g.client_expires_at)
       and g.consumed_at<=j.received_at)
      from subscription_action_capabilities g
      join stripe_subscription_setup_proofs setup on setup.operation_id=g.operation_id and setup.setup_reference=$11::text
      where g.site_id=$3::uuid and g.subscription_id=$7::uuid and g.operation_id=$8::uuid and g.consumed_at is not null)
      and not exists(select 1 from subscription_confirmation_chains c where c.operation_id=$8::uuid) permit_bound,
       e.id callback_id, e.callback_type, e.payload,
       (e.id=trial.trial_started_event_id and e.source_kind='SUBSCRIPTION' and e.subscription_event_id=j.id
        and e.payment_account_id=$2::uuid and e.subscription_id=$7::uuid and e.payment_id is null
        and e.callback_type='subscription.updated' and e.created_at=j.received_at
        and (e.payload->>'occurred_at')::timestamptz=j.occurred_at
        and (e.payload->>'access_valid_until')::timestamptz=least(trial.trial_end,j.occurred_at+interval '5 minutes')
        and e.endpoint_url='http://callback-receiver:8080/callback'
        and exists(select 1 from site_callback_configs c join site_callback_secret_versions v
                   on v.id=c.active_secret_version_id and v.site_id=c.site_id
                   where c.site_id=e.site_id and c.enabled and c.endpoint_url=e.endpoint_url
                     and v.id=e.secret_version_id and v.secret_version=1 and v.destroyed_at is null)
        and not exists(select 1 from outbox_retry_requests r where r.outbox_event_id=e.id)) callback_bound,
       case when e.status='PENDING' and e.attempt_count<=1
                 and not exists(select 1 from outbox_deliveries d where d.outbox_event_id=e.id) then 'pending'
            when e.status='DELIVERED' and e.attempt_count=1 and e.lease_owner is null
                 and (select count(*)=1 from outbox_deliveries d where d.outbox_event_id=e.id)
                 and exists(select 1 from outbox_deliveries d where d.outbox_event_id=e.id
                            and d.attempt_number=1 and d.delivery_mode='AUTOMATIC' and d.status='SUCCEEDED'
                            and d.http_status=204 and d.error_category is null and d.started_at>=e.created_at
                            and d.finished_at=e.updated_at and d.finished_at<=trial.access_evaluated_at)
                 then 'delivered' end callback_state
from required_trial trial
join subscription_events j on j.id=trial.trial_started_event_id
join outbox_events e on e.site_id=$3::uuid
limit 2`;
}

async function withReadOnlyDatabase(bootstrap,connect,limits,body) {
 let result,called=false;
 need(await bootstrap.withDatabaseCredentials(async credentials=>{
  billingCheck(limits);
  need(!called);called=true;
  const sql=connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});
  let closing;
  const close=()=>closing??=(async()=>sql.end({timeout:0}))();
  const abort=()=>{void close().catch(()=>{});};
  limits.signal.addEventListener('abort',abort,{once:true});
  try {
   const read=async(query,args)=>{
    billingCheck(limits);
    const rows=await sql.begin('read only',async tx=>{
     await tx.unsafe("set local statement_timeout='5000'");
     billingCheck(limits);
     return tx.unsafe(query,args);
    });
    billingCheck(limits);
    return rows;
   };
   result=await body(read);
   billingCheck(limits);
   return true;
  } finally {
   limits.signal.removeEventListener('abort',abort);
   await billingBounded({deadline:Date.now()+5000},close,5000);
  }
 },limits)===true&&called);
 billingCheck(limits);
 return result;
}

function requireProofRow(row,proof) {
 need(row?.run_id===proof.runId&&row.billing_record_id===proof.billingRecordId
   &&row.trial_started_event_id===proof.trialStartedEventId&&String(row.revision)===String(proof.revision)
   &&row.trial_start.toISOString()===proof.trialStart&&row.trial_end.toISOString()===proof.trialEnd);
}

function callbackDigest(scope,proof,row) {
 requireProofRow(row,proof);
 const payload=row.payload;
 need(row.permit_bound===true&&row.callback_bound===true&&row.callback_id===proof.trialStartedEventId&&row.callback_type===eventType);
 const expected={schema_version:4,event_id:proof.trialStartedEventId,event_type:eventType,site_id:scope.siteId,
  customer_id:scope.customerId,product_id:scope.productId,subscription_id:scope.subscriptionId,revision:proof.revision,
  state:'TRIALING',access_allowed:true,billing_record_id:null,payment_id:null};
 need(payload&&Object.keys(payload).length===Object.keys(expected).length+2
   &&Object.entries(expected).every(([key,value])=>Object.hasOwn(payload,key)&&payload[key]===value)
   &&typeof payload.occurred_at==='string'&&typeof payload.access_valid_until==='string');
 const at=Date.parse(payload.occurred_at),until=Date.parse(payload.access_valid_until),end=Date.parse(proof.trialEnd);
 need(at>=Date.parse(proof.trialStart)&&at<=Date.now()&&at<end&&until===Math.min(end,at+300000));
 return billingCallbackDigest(payload);
}

/** Read only through the verified bootstrap, then compare with the owned receiver beyond the credential scope. */
export async function proveBillingRequiredTrialRuntime({configuration,bootstrap,cli,fixture},limits,dependencies={}) {
 try {
  return await billingBounded(limits,async active=>{
   const scope=ownedScope(configuration,fixture);
   const current=()=>{
    billingCheck(active);
    const observed=ownedScope(configuration,fixture);
    need(scopeKeys.every(key=>observed[key]===scope[key]));
   };
   const [seedQuery,proofQuery]=await Promise.all(['seed','proof'].map(file=>
    readFile(new URL(`./stripe-billing-required-trial-${file}.sql`,import.meta.url),'utf8')));
   const connect=dependencies.connect??createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');
   const ownerArgs=createScopeKeys.map(k=>scope[k]);
   let expected=ownerArgs,proofArgs,proofRow,next=seedQuery;
   current();
   const proof=await cli.proveOwnedBillingRequiredTrial(fixture,async(request,readLimits)=>{
    current();billingCheck(readLimits);
    const query=next;
    next=null;
    need(query!==null&&readLimits.deadline<=active.deadline&&request?.mode==='read only'&&request.query===query);
    const requestedArgs=request.args;
    need(Array.isArray(requestedArgs));
    const args=Object.freeze([...requestedArgs]);
    need(args.length===expected.length&&expected.every((value,index)=>args[index]===value));
    const rows=await billingBounded(readLimits,stage=>withReadOnlyDatabase(bootstrap,connect,stage,read=>read(query,args)));
    current();need(Array.isArray(rows)&&rows.length===1);
    const [row]=rows;
    if(query===seedQuery){
     expected=[...ownerArgs,...nativeKeys.map(key=>row[key]),row.trial_start.toISOString(),row.trial_end.toISOString()];
     next=proofQuery;
    }else{proofRow=structuredClone(row);proofArgs=args;}
    return rows;
   },active);
   current();
   need(proofArgs&&proof?.trialVerified===true&&proof.noCash===true&&proof.runId===scope.runId
     &&billingUuid(proof.billingRecordId)&&billingUuid(proof.trialStartedEventId)
     &&Number.isSafeInteger(proof.revision)&&proof.revision>0);
   requireProofRow(proofRow,proof);
   need(proofRow.access_evaluated_at.toISOString()===proof.accessEvaluatedAt
     &&proofRow.access_valid_until.toISOString()===proof.accessValidUntil
     &&Date.parse(proof.accessValidUntil)>Date.now());
   const callbackQuery=withOutbox(proofQuery);
   const expectedDigest=await billingBounded(active,stage=>withReadOnlyDatabase(bootstrap,connect,stage,async read=>{
    for(;;){
     current();
     const rows=await read(callbackQuery,[...proofArgs,scope.trialPermitKeyHash,scope.demoOrigin]);
     current();need(Array.isArray(rows)&&rows.length===1);
     const row=rows[0],digest=callbackDigest(scope,proof,row);
     if(row.callback_state==='delivered')return digest;
     need(row.callback_state==='pending');
     await delay(250,undefined,{signal:stage.signal});
    }
   }),60000);
   const inspection=await billingBounded(active,stage=>fixture.inspectCallbacks(stage),10000);
   current();
   need(Date.parse(proof.accessValidUntil)>Date.now()&&inspection?.count===1&&inspection.duplicates===0
     &&Array.isArray(inspection.events)&&inspection.events.length===1);
   const [event]=inspection.events;
   need(event?.event_id===proof.trialStartedEventId&&event.event_type===eventType&&event.count===1&&event.sha256===expectedDigest);
   return true;
  },120000);
 } catch {throw billingError('REQUIRED_TRIAL_EVIDENCE');}
}
