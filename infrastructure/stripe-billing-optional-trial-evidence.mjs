import {readFile} from 'node:fs/promises';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const account='acct_1UPiMpELswAd8tpR',apiVersion='2026-09-30.endive';
const base= './stripe-billing-optional-trial-';
const pauseProof=new URL(base+'pause-proof.sql',import.meta.url);
const resumeProof=new URL(base+'resume-proof.sql',import.meta.url);
const caseName='optional_trial_pause_resume';
const trialDuration=7*24*60*60,amount=2000;
const ids=['siteId','customerId','accountId','productId','priceId','subscriptionId','createId','trialBillId','startedEventId'];
const stripeReferences={
 customerReference:'cus',subscriptionReference:'sub',itemReference:'si',invoiceReference:'in',
 productReference:'prod',priceReference:'price',clockReference:'clock',
};
const ownerKeys=['runId','expectedSource','siteKey',...ids,...Object.keys(stripeReferences),'trialStart','trialEnd'];
const resumeKeys=['setupId','resumeId','resumeBillId','resumeInvoiceReference','setupReference','methodReference','resumeStart','resumeEnd'];
const zeroAdjustments=[
 'amount_paid_off_stripe','amount_overpaid','starting_balance','ending_balance',
 'pre_payment_credit_notes_amount','post_payment_credit_notes_amount',
];

const need=condition=>{if(!condition)throw billingError('OPTIONAL_TRIAL_EVIDENCE');};
const timestamp=value=>Number.isSafeInteger(value)&&value>0&&value<=253402300799;
const instant=value=>new Date(value*1000).toISOString();
const ref=(value,prefix)=>typeof value==='string'&&value.length<=255&&new RegExp('^'+prefix+'_[A-Za-z0-9]+$').test(value);
const empty=value=>Array.isArray(value)&&value.length===0;
const optionalEmpty=value=>value==null||empty(value);
const fields=(object,keys)=>Object.freeze(Object.fromEntries(keys.map(key=>[key,object?.[key]])));
const matches=(actual,expected)=>actual&&Object.entries(expected).every(([key,value])=>actual[key]===value);

function accountBinding(configuration,scope) {
 return configuration?.selectedCase===caseName&&configuration.runId===scope.runId
  &&configuration.expectedSource===scope.expectedSource
  &&configuration.approvedBinding?.externalAccountId===account
  &&configuration.approvedBinding?.apiVersion===apiVersion;
}

function stripeObject(value,type,id) {
 need(value?.id===id&&value.object===type&&value.livemode===false
  &&(value.account===undefined||value.account===account));
 return value;
}

function page(value,count) {
 need(value?.object==='list'&&value.has_more===false&&Array.isArray(value.data)&&value.data.length===count);
 return value.data;
}

async function verifyClock(read,s,clockTime) {
 const clock=stripeObject(await read('/v1/test_helpers/test_clocks/'+s.clockReference),'test_helpers.test_clock',s.clockReference);
 need(clock.status==='ready'&&clock.frozen_time===clockTime);
}

async function verifySubscription(read,s,latestInvoice,resume,paid) {
 const owner={account_id:s.accountId,site_id:s.siteId,customer_id:s.customerId};
 const binding={...owner,subscription_id:s.subscriptionId};
 const c=stripeObject(await read('/v1/customers/'+s.customerReference),'customer',s.customerReference);
 need(c.deleted!==true&&c.test_clock===s.clockReference&&matches(c.metadata,owner)
  &&c.default_source===null&&c.invoice_settings?.default_payment_method===null);
 const [sole]=page(await read('/v1/subscriptions',{customer:s.customerReference,status:'all',limit:2}),1);
 stripeObject(sole,'subscription',s.subscriptionReference);
 const status=paid?'active':'paused';
 need(sole.customer===s.customerReference&&sole.test_clock===s.clockReference&&sole.status===status);
 const sub=stripeObject(await read('/v1/subscriptions/'+s.subscriptionReference),'subscription',s.subscriptionReference);
 need(sub.customer===s.customerReference&&sub.test_clock===s.clockReference
  &&sub.status===status&&sub.latest_invoice===latestInvoice&&sub.currency==='eur'
  &&sub.collection_method==='charge_automatically'&&sub.billing_mode?.type==='classic'
  &&sub.pause_collection===null&&sub.default_source===null&&sub.default_payment_method===(resume?.methodReference??null)
  &&sub.cancel_at===null&&sub.cancel_at_period_end===false&&sub.canceled_at===null&&sub.ended_at===null
  &&sub.schedule===null&&sub.pending_update===null&&sub.trial_start===s.trialStart&&sub.trial_end===s.trialEnd
  &&sub.trial_settings?.end_behavior?.missing_payment_method==='pause'
  &&sub.payment_settings?.save_default_payment_method==='on_subscription'
  &&Array.isArray(sub.payment_settings.payment_method_types)
  &&sub.payment_settings.payment_method_types.length===1&&sub.payment_settings.payment_method_types[0]==='card'
  &&matches(sub.metadata,{...binding,operation_id:s.createId}));
 const [item]=page(sub.items,1);
 need(item?.id===s.itemReference&&item.object==='subscription_item'&&item.subscription===s.subscriptionReference
  &&item.quantity===1&&item.deleted!==true);
 if(!resume)need(item.current_period_start===s.trialStart&&item.current_period_end===s.trialEnd);
 if(paid)need(item.current_period_start===resume.resumeStart&&item.current_period_end===resume.resumeEnd);
 const price=stripeObject(item.price,'price',s.priceReference);
 need(price.product===s.productReference&&price.active===true&&price.unit_amount===amount
  &&price.currency==='eur'&&price.type==='recurring'&&price.tax_behavior==='inclusive'
  &&matches(price.recurring,{interval:'month',interval_count:1,usage_type:'licensed'})
  &&matches(price.metadata,{local_price_id:s.priceId,local_price_version:'1'}));
 const methods=page(await read('/v1/payment_methods',{customer:s.customerReference,limit:2}),resume?1:0);
 if(resume) {
  const setup=stripeObject(await read('/v1/setup_intents/'+resume.setupReference),'setup_intent',resume.setupReference);
  need(setup.customer===s.customerReference&&setup.status==='succeeded'&&setup.usage==='off_session'
   &&setup.payment_method===resume.methodReference&&Array.isArray(setup.payment_method_types)
   &&setup.payment_method_types.length===1&&setup.payment_method_types[0]==='card'
   &&matches(setup.metadata,{...binding,operation_id:resume.setupId}));
  const method=stripeObject(methods[0],'payment_method',resume.methodReference);
  need(method.customer===s.customerReference&&method.type==='card'
   &&method.card&&typeof method.card==='object'&&!Array.isArray(method.card));
 }
}

async function verifyInvoice(read,s,{id,reason,start,end,due,paid}) {
 const inv=stripeObject(await read('/v1/invoices/'+id,{'expand[]':'amount_paid_off_stripe'}),'invoice',id);
 need(inv.customer===s.customerReference&&inv.test_clock===s.clockReference&&inv.currency==='eur'
  &&inv.billing_reason===reason&&inv.collection_method==='charge_automatically'
  &&inv.parent?.type==='subscription_details'&&inv.parent.subscription_details?.subscription===s.subscriptionReference
  &&inv.status===(paid?'paid':'open')&&inv.amount_due===due&&inv.amount_paid===(paid?due:0)
  &&inv.amount_remaining===(paid?0:due)&&inv.total===due&&inv.subtotal===due
  &&zeroAdjustments.every(key=>inv[key]===0)
  &&['discounts','total_discount_amounts','total_pretax_credit_amounts'].every(key=>empty(inv[key]))
  &&empty(inv.total_taxes)&&optionalEmpty(inv.default_tax_rates)
  &&(inv.amount_shipping==null||inv.amount_shipping===0)&&inv.shipping_cost==null&&inv.shipping_details==null
  &&(inv.automatic_tax==null||inv.automatic_tax.enabled===false)
  &&(inv.total_excluding_tax==null||inv.total_excluding_tax===due)
  &&(inv.subtotal_excluding_tax==null||inv.subtotal_excluding_tax===due));
 const [line]=page(await read('/v1/invoices/'+id+'/lines',{limit:2}),1);
 need(ref(line?.id,'il'));
 stripeObject(line,'line_item',line.id);
 const detail=line.parent?.subscription_item_details;
 const pricing=line.pricing?.price_details;
 need(line.invoice===id&&line.amount===due&&line.quantity===1&&line.currency==='eur'
  &&(line.subscription==null||line.subscription===s.subscriptionReference)
  &&line.parent?.type==='subscription_item_details'&&detail?.subscription===s.subscriptionReference
  &&detail.subscription_item===s.itemReference&&detail.proration===false
  &&line.pricing?.type==='price_details'&&pricing?.price===s.priceReference&&pricing.product===s.productReference
  &&line.period?.start===start&&line.period.end===end&&empty(line.discount_amounts)
  &&empty(line.pretax_credit_amounts)&&optionalEmpty(line.taxes)&&optionalEmpty(line.discounts));
}

async function verifySettlement(read,s,resume,paid,previous) {
 const invoice=resume.resumeInvoiceReference;
 const [allocation]=page(await read('/v1/invoice_payments',{invoice,limit:2}),1);
 const paymentReference=allocation?.payment?.payment_intent;
 const allocationReference=allocation?.id;
 need(ref(paymentReference,'pi')&&ref(allocationReference,'inpay'));
 if(paid)need(previous.paymentReference===paymentReference&&previous.allocationReference===allocationReference);
 function allocationMatches(value) {
  stripeObject(value,'invoice_payment',allocationReference);
  const transitions=value.status_transitions;
  need(value.invoice===invoice&&value.currency==='eur'&&value.is_default===true&&value.amount_requested===amount
   &&value.payment?.type==='payment_intent'&&value.payment.payment_intent===paymentReference
   &&value.status===(paid?'paid':'open')&&transitions?.canceled_at===null);
  if(paid)need(value.amount_paid===amount&&timestamp(transitions.paid_at)
   &&transitions.paid_at>=resume.resumeStart&&transitions.paid_at<resume.resumeEnd);
  else need((value.amount_paid===null||value.amount_paid===0)&&transitions.paid_at===null);
 }
 allocationMatches(allocation);
 const [reverse]=page(await read('/v1/invoice_payments',{
  'payment[type]':'payment_intent','payment[payment_intent]':paymentReference,limit:2,
 }),1);
 allocationMatches(reverse);
 need(reverse.status_transitions.paid_at===allocation.status_transitions.paid_at);
 const customer=s.customerReference;
 const [intent]=page(await read('/v1/payment_intents',{customer,limit:2}),1);
 stripeObject(intent,'payment_intent',paymentReference);
 need(intent.customer===customer&&intent.amount===amount&&intent.currency==='eur'
  &&intent.amount_received===(paid?amount:0)&&intent.capture_method==='automatic'
  &&Array.isArray(intent.payment_method_types)&&intent.payment_method_types.length===1
  &&intent.payment_method_types[0]==='card');
 if(paid){
  need(intent.status==='succeeded'&&intent.payment_method===resume.methodReference&&ref(intent.latest_charge,'ch'));
  const [charge]=page(await read('/v1/charges',{customer,limit:2}),1);
  stripeObject(charge,'charge',intent.latest_charge);
  need(charge.customer===customer&&charge.payment_intent===paymentReference&&charge.payment_method===resume.methodReference
   &&charge.status==='succeeded'&&charge.captured===true&&charge.paid===true&&charge.amount===amount
   &&charge.amount_captured===amount&&charge.refunded===false&&charge.amount_refunded===0&&charge.disputed===false
   &&charge.currency==='eur'&&charge.payment_method_details?.type==='card');
  page(await read('/v1/refunds',{charge:charge.id,limit:1}),0);
 }else{
  need(['requires_payment_method','requires_confirmation','requires_action'].includes(intent.status)
   &&intent.latest_charge===null&&(intent.payment_method===null||intent.payment_method===resume.methodReference));
  page(await read('/v1/charges',{customer,limit:1}),0);
 }
 return {allocationReference,paymentReference,paidAt:allocation.status_transitions.paid_at};
}

/** The caller binds both read transports and the original trial receipt to the current admitted run. */
export function createOptionalTrialEvidence(configuration,fixture,{readProvider,readOnly}) {
 const s=fields(fixture.resources(),ownerKeys);
 let next='PAUSED',pause,resume,pending;

 function current(limits) {
  billingCheck(limits);
  need(next!=='STOPPED'&&accountBinding(configuration,s)&&matches(fixture.resources(),s));
 }

 async function verify(phase,receipt,limits) {
  try {
   need(next===phase);
   next='READING';
   return await billingBounded(limits,async active=>{
    current(active);
    need(billingUuid(s.runId)&&/^[a-f0-9]{40}$/.test(s.expectedSource)
     &&s.siteKey==='s5a-'+s.runId.replaceAll('-','')&&ids.every(key=>billingUuid(s[key]))
     &&Object.entries(stripeReferences).every(([key,prefix])=>ref(s[key],prefix))
     &&timestamp(s.trialStart)&&timestamp(s.trialEnd)&&s.trialEnd-s.trialStart===trialDuration);
    const paused=phase==='PAUSED',paid=phase==='PAID';
    const clockTime=paused?receipt?.frozenTime:phase==='PENDING'?receipt?.resumeStart:resume.resumeStart;
    need(timestamp(clockTime)&&clockTime>=s.trialEnd);
    if(phase==='PENDING') {
     resume=fields(receipt,resumeKeys);
     need(resume.resumeStart===clockTime&&resume.resumeStart>=pause.frozenTime
      &&timestamp(resume.resumeEnd)&&resume.resumeEnd>clockTime
      &&[resume.setupId,resume.resumeId,resume.resumeBillId].every(billingUuid)
      &&resume.setupId!==s.createId&&resume.resumeId!==s.createId&&resume.setupId!==resume.resumeId
      &&resume.resumeBillId!==s.trialBillId&&ref(resume.resumeInvoiceReference,'in')
      &&resume.resumeInvoiceReference!==s.invoiceReference&&ref(resume.setupReference,'seti')&&ref(resume.methodReference,'pm'));
    }

    const read=async(path,parameters={})=>{
     current(active);
     const request=Object.freeze({
      method:'GET',path,parameters:Object.freeze(parameters),runId:s.runId,expectedSource:s.expectedSource,
     });
     const envelope=await readProvider(request,active);
     current(active);
     need(envelope?.externalAccountId===account&&envelope.apiVersion===apiVersion&&envelope.livemode===false);
     return envelope.body;
    };

    const expectedInvoices=paused?[s.invoiceReference]:[s.invoiceReference,resume.resumeInvoiceReference];
    await verifyClock(read,s,clockTime);
    await verifySubscription(read,s,expectedInvoices.at(-1),resume,paid);
    const invoices=page(await read('/v1/invoices',{customer:s.customerReference,limit:3}),expectedInvoices.length);
    for(const expected of expectedInvoices){
     const invoice=stripeObject(invoices.find(invoice=>invoice?.id===expected),'invoice',expected);
     const status=expected===s.invoiceReference||paid?'paid':'open';
     need(invoice.customer===s.customerReference&&invoice.test_clock===s.clockReference&&invoice.status===status);
    }
    await verifyInvoice(read,s,{id:s.invoiceReference,reason:'subscription_create',start:s.trialStart,end:s.trialEnd,due:0,paid:true});
    page(await read('/v1/invoice_payments',{invoice:s.invoiceReference,limit:1}),0);

    let settlement;
    if(paused){
     page(await read('/v1/payment_intents',{customer:s.customerReference,limit:1}),0);
     page(await read('/v1/charges',{customer:s.customerReference,limit:1}),0);
    }else{
     await verifyInvoice(read,s,{id:resume.resumeInvoiceReference,reason:'subscription_cycle',
      start:resume.resumeStart,end:resume.resumeEnd,due:amount,paid});
     settlement=await verifySettlement(read,s,resume,paid,pending);
    }

    const args=[s.siteId,s.siteKey,s.customerId,s.accountId,s.productId,s.priceId,s.subscriptionId,s.createId,
     s.trialBillId,s.startedEventId,s.subscriptionReference,s.invoiceReference,instant(s.trialStart),instant(s.trialEnd)];
    if(!paused)args.push(pause.pausedEventId,resume.setupId,resume.resumeId,resume.resumeInvoiceReference,
     instant(resume.resumeStart),instant(resume.resumeEnd),resume.resumeBillId,paid?settlement.paymentReference:null,phase);
    const query=await readFile(paused?pauseProof:resumeProof,'utf8');
    current(active);
    const rows=await readOnly(Object.freeze({mode:'read only',query,args:Object.freeze(args)}),active);
    current(active);
    await verifyClock(read,s,clockTime);
    current(active);
    need(Array.isArray(rows)&&rows.length===1);
    const [row]=rows;
    need(row?.subscription_id===s.subscriptionId&&row.external_account_id===account&&row.item_reference===s.itemReference);
    const result={phase,runId:s.runId,subscriptionId:s.subscriptionId,trialBillId:s.trialBillId,
     invoiceReference:s.invoiceReference,frozenTime:clockTime};
    if(paused) {
     const revision=Number(row.revision);
     need(row.billing_record_id===s.trialBillId&&row.trial_started_event_id===s.startedEventId
      &&billingUuid(row.paused_event_id)&&row.paused_event_id!==s.startedEventId
      &&Number.isSafeInteger(revision)&&revision>1&&row.trial_end instanceof Date
      &&row.trial_end.getTime()===s.trialEnd*1000&&row.paused_at instanceof Date
      &&row.paused_at.getTime()>=s.trialEnd*1000&&row.paused_at.getTime()<=clockTime*1000);
     pause=Object.freeze({...result,pausedEventId:row.paused_event_id,revision});
     next='PENDING';
     return pause;
    }
    need(row.trial_bill_id===s.trialBillId&&row.resume_bill_id===resume.resumeBillId
     &&row.setup_reference===resume.setupReference&&row.method_reference===resume.methodReference);
    if(paid)need([row.payment_id,row.cash_event_id,row.period_event_id].every(billingUuid)
     &&row.cash_occurred_at instanceof Date&&row.cash_occurred_at.getTime()===settlement.paidAt*1000);
    else need(row.payment_id===null&&row.cash_event_id===null&&row.period_event_id===null&&row.cash_occurred_at===null);
    const proof=Object.freeze({...result,resumeBillId:resume.resumeBillId,resumeInvoiceReference:resume.resumeInvoiceReference,
     ...settlement,paymentId:row.payment_id,cashEventId:row.cash_event_id,periodEventId:row.period_event_id});
    if(!paid)pending=proof;
    next=paid?'COMPLETE':'PAID';
    return proof;
   },30000);
  }catch{
   next='STOPPED';
   throw billingError('OPTIONAL_TRIAL_EVIDENCE');
  }
 }

 return Object.freeze({
  verifyPause:(clock,limits)=>verify('PAUSED',clock,limits),
  verifyPendingResume:(operation,limits)=>verify('PENDING',operation,limits),
  verifyPaidResume:limits=>verify('PAID',null,limits),
 });
}
