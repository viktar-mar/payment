import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {setTimeout as delay} from 'node:timers/promises';
import {billingCallbackDigest,validateBillingCallbackEvidence} from './stripe-billing-evidence.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const claimedFixtures=new WeakSet();
const scopeIds=['accountId','siteId','customerId','subscriptionId','operationId','attemptId','productId','priceId'];
const prefixes={customer:'cus',subscription:'sub',product:'prod',price:'price',invoice:'in',allocation:'inpay',intent:'pi'};
const digest=value=>typeof value==='string' && /^[a-f0-9]{64}$/.test(value);
function need(value) { if(!value)throw billingError('EVIDENCE'); }

function parameters(configuration,scope) {
  need(configuration.selectedCase==='initial_missed_reordered' && billingUuid(configuration.runId)
    && /^[a-f0-9]{40}$/.test(configuration.expectedSource) && scope.runId===configuration.runId
    && scope.expectedSource===configuration.expectedSource && scope.siteKey==='s5a-'+scope.runId.replaceAll('-',''));
  const ids=scopeIds.map(key=>scope[key]);
  need(ids.every(billingUuid) && new Set(ids).size===ids.length && digest(scope.createKeyHash)
    && digest(scope.confirmKeyHash) && scope.confirmKeyHash!==scope.createKeyHash);
  return [...ids,scope.createKeyHash,scope.confirmKeyHash,scope.endpoints.demo,scope.siteKey];
}

function originalGraph(scope,row) {
  const graph={runId:scope.runId,expectedSource:scope.expectedSource,priceVersion:1};
  for(const key of ['accountId','siteId','customerId','subscriptionId','operationId','priceId'])graph[key]=scope[key];
  for(const [field,prefix] of Object.entries(prefixes)) {
    const value=row[field+'_reference'];
    need(typeof value==='string' && new RegExp('^'+prefix+'_[A-Za-z0-9_]{1,200}$').test(value));
    graph[field+'Reference']=value;
  }
  need(scope.priceReference===graph.priceReference && row.create_operation_id===scope.operationId && row.attempt_id===scope.attemptId);
  return graph;
}

function retainEvents(incoming,graph) {
  const records={};
  for(const kind of ['paid','finalized']) {
    const event=incoming?.[kind];
    need(event && typeof event.eventId==='string' && /^evt_[A-Za-z0-9]{1,251}$/.test(event.eventId)
      && Number.isSafeInteger(event.created) && event.created>0 && event.invoiceReference===graph.invoiceReference
      && event.subscriptionReference===graph.subscriptionReference && event.customerReference===graph.customerReference);
    records[kind]=Object.freeze({eventId:event.eventId,created:event.created});
  }
  need(records.paid.eventId!==records.finalized.eventId && records.paid.created>records.finalized.created);
  return records;
}

function receipt(row,kind,events) {
  const matches=row.inbox.filter(e=>e.eventId===events[kind].eventId);
  need(matches.length===1);
  const r=matches[0];
  need(billingUuid(r.id) && r.type==='invoice.'+kind && r.normalizedType==='SUBSCRIPTION_INVOICE_CHANGED'
    && r.status==='PROCESSED' && Number(r.occurredAt)===events[kind].created
    && typeof r.receivedAt==='string' && Number.isFinite(Date.parse(r.receivedAt)));
  return JSON.stringify([r.id,r.eventId,r.occurredAt,r.receivedAt]);
}

function verifyUnpaidCallbacks(scope,callbacks,inspection) {
  need(Array.isArray(callbacks) && callbacks.length<=1 && Array.isArray(inspection?.events)
    && inspection.duplicates===0 && inspection.count===inspection.events.length && inspection.count<=callbacks.length);
  for(const callback of callbacks) {
    const p=callback.payload;
    need(billingUuid(callback.id) && p?.event_id===callback.id && callback.type==='subscription.updated'
      && p.event_type===callback.type && p.schema_version===4 && p.site_id===scope.siteId
      && p.customer_id===scope.customerId && p.subscription_id===scope.subscriptionId && p.product_id===scope.productId
      && p.access_allowed===false && p.payment_id===null && p.billing_record_id===null);
    const reception=inspection.events[0];
    if(reception)need(reception.event_id===callback.id && reception.event_type===callback.type
      && reception.count===1 && reception.sha256===billingCallbackDigest(p));
    else need(callback.delivered===false);
  }
}

function cashWitness(scope,row,inspection) {
  const {graph}=validateBillingCallbackEvidence(scope,row,inspection);
  const keys=[row.payment_id,row.billing_record_id,row.payment_event_id,row.period_event_id];
  need(keys.every(billingUuid) && new Set(keys).size===keys.length);
  const payment=row.callbacks.find(c=>c.id===row.payment_event_id)?.payload;
  const period=row.callbacks.find(c=>c.id===row.period_event_id)?.payload;
  need(payment?.event_type==='payment.succeeded' && payment.payment_id===row.payment_id
    && period?.schema_version===4 && period.event_type==='subscription.updated' && period.billing_record_id===row.billing_record_id
    && period.payment_id===null && period.state==='ACTIVE' && period.access_allowed===true);
  const revision=Number(row.subscription_revision);
  need(Number.isSafeInteger(revision) && revision>=1 && Number.isSafeInteger(period.revision) && period.revision<=revision);
  const deliveries=row.callbacks.map(c=>{
    need(billingUuid(c.deliveryId));
    return [c.id,c.deliveryId,inspection.events.find(e=>e.event_id===c.id).sha256];
  }).sort((a,b)=>a[0].localeCompare(b[0]));
  need(new Set(deliveries.map(d=>d[1])).size===deliveries.length);
  return JSON.stringify([...keys,graph.periodStart,graph.periodEnd,revision,deliveries]);
}

export function createBillingInvoiceWebhookEvidence({configuration,bootstrap,fixture,cli},dependencies={}) {
  need(configuration.selectedCase==='initial_missed_reordered' && !claimedFixtures.has(fixture));
  claimedFixtures.add(fixture);
  const stages=['beforeConfirm','beforeReconcile','reconciled','paid','finalized'];
  let next=0,scopeArgs,graph,events,operation,settlement,paidReceipt,bill;
  async function read(scope,stage,limits) {
    const args=parameters(configuration,scope);
    if(scopeArgs)need(args.every((a,i)=>scopeArgs[i]===a));
    else scopeArgs=args;
    const values=[...args,stage,operation?.operationId??null,operation?.keyHash??null,events?.paid.eventId??null,
      events?.paid.created??null,events?.finalized.eventId??null,events?.finalized.created??null];
    const query=await readFile(new URL('./stripe-billing-invoice-webhook-proof.sql',import.meta.url),'utf8');
    const connect=dependencies.connect??createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');
    let result;
    need(await bootstrap.withDatabaseCredentials(async credentials=>{
      billingCheck(limits);
      const sql=connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});
      let closing;
      const close=()=>closing??=sql.end({timeout:0});
      const abort=()=>{void Promise.resolve(close()).catch(()=>{});};
      limits.signal.addEventListener('abort',abort,{once:true});
      try {
        for(;;) {
          billingCheck(limits);
          const rows=await sql.begin('read only',async tx=>{
            await tx.unsafe("set local statement_timeout='5000'");
            return tx.unsafe(query,values);
          });
          billingCheck(limits);
          need(rows.length===1 && rows[0].original===true);
          const row=rows[0];
          if(row.phase_proof===true && row.pending===false) { result=row; return true; }
          need(row.phase_proof===false && row.pending===true && ['reconciled','paid','finalized'].includes(stage));
          await delay(250,undefined,{signal:limits.signal});
        }
      }finally{
        limits.signal.removeEventListener('abort',abort);
        await billingBounded({deadline:Date.now()+5000},close,5000);
      }
    },limits)===true);
    billingCheck(limits);need(result);return result;
  }
  function transition(stage,parent,verify) {
    return billingBounded(parent,async limits=>{
      need(next>=0 && next<stages.length && stages[next]===stage);
      const following=next+1;
      next=-1;
      try {
        const result=await verify(fixture.resources(),limits);
        billingCheck(limits);next=following;return result;
      }catch{throw billingError('EVIDENCE');}
    },90000);
  }
  async function phaseRow(scope,stage,limits) {
    const row=await read(scope,stage,limits), current=originalGraph(scope,row);
    if(graph)need(Object.keys(graph).every(key=>graph[key]===current[key]));else graph=current;
    need(row.reconcile_operation_id===(operation?.operationId??null));
    if(row.billing_record_id!==null){
      const periodStart=Number(row.period_start),periodEnd=Number(row.period_end);
      need(billingUuid(row.billing_record_id) && Number.isSafeInteger(periodStart) && periodStart>0
        && Number.isSafeInteger(periodEnd) && periodEnd>periodStart);
      if(bill)need(bill.id===row.billing_record_id && bill.periodStart===periodStart && bill.periodEnd===periodEnd);
      else bill={id:row.billing_record_id,periodStart,periodEnd};
    }else need(!bill && row.period_start===null && row.period_end===null);
    const expected=stage==='paid'?1:stage==='finalized'?2:0;
    need(Array.isArray(row.inbox) && row.inbox.length===expected);
    if(expected) {
      const paid=receipt(row,'paid',events);
      if(expected===1)paidReceipt=paid;
      else {need(paid===paidReceipt);receipt(row,'finalized',events);need(row.inbox[0].id!==row.inbox[1].id);}
    }
    return row;
  }
  return Object.freeze({
    beforeConfirm:limits=>transition('beforeConfirm',limits,async(scope,l)=>{
      await phaseRow(scope,'beforeConfirm',l);
      return Object.freeze({invoiceReference:graph.invoiceReference,subscriptionReference:graph.subscriptionReference,customerReference:graph.customerReference});
    }),
    beforeReconcile:(incoming,limits)=>transition('beforeReconcile',limits,async(scope,l)=>{
      events=retainEvents(incoming,graph);
      const row=await phaseRow(scope,'beforeReconcile',l);
      need(row.payment_id===null && row.payment_event_id===null && row.period_event_id===null);
      verifyUnpaidCallbacks(scope,row.callbacks,await fixture.inspectCallbacks(l));
      billingCheck(l);
      need(await cli.observeOwnedBillingCapturedInvoice(Object.freeze({...graph,periodStart:bill?.periodStart??null,periodEnd:bill?.periodEnd??null}),l)===true);
      return true;
    }),
    reconciled:(command,limits)=>transition('reconciled',limits,async(scope,l)=>{
      need(billingUuid(command?.operationId) && !scopeIds.some(k=>scope[k]===command.operationId)
        && digest(command.keyHash) && ![scope.createKeyHash,scope.confirmKeyHash].includes(command.keyHash));
      operation=Object.freeze({operationId:command.operationId,keyHash:command.keyHash});
      const row=await phaseRow(scope,'reconciled',l);
      settlement=cashWitness(scope,row,await fixture.inspectCallbacks(l));
      return true;
    }),
    delivered:(kind,limits)=>transition(kind,limits,async(scope,l)=>{
      need(kind==='paid' || kind==='finalized');
      const row=await phaseRow(scope,kind,l);
      need(cashWitness(scope,row,await fixture.inspectCallbacks(l))===settlement);
      if(kind==='finalized')need(await cli.observeOwnedBillingCapturedInvoice(Object.freeze({...graph,
        periodStart:Number(row.period_start),periodEnd:Number(row.period_end)}),l)===true);
      return true;
    })
  });
}
