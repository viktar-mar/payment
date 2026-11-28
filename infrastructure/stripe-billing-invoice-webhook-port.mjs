import {billingCheck} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';
import {exactRecord} from './stripe-billing-failed-renewal-response.mjs';

export function invoiceWebhookFlow({configuration,bootstrap,cli,fixture,ingress,active,step,fail,createEvidence}){
 let next='customer',createKey,evidence;
 const need=v=>{billingCheck(active);if(!v)fail('INVOICE_ORDER');};
 function listener(){
  try{if(cli.assertBillingListener(active)===true)return true;}catch{}
  fail('LISTENER');
 }
 function claim(stage){need(stage===next);next='busy';}
 function record(value,keys){try{return exactRecord(value,keys);}catch{fail('INVOICE_ORDER');}}
 const checked=(phase,body)=>step(phase,async l=>{if(await body(l)!==true)fail(phase);return true;});
 return {port:{
  async acknowledgeCustomer(raw){
   claim('customer');const v=record(raw,['siteId','customerId']);need(billingUuid(v.customerId) && v.siteId===fixture.resources().siteId);
   await checked('ACK',l=>fixture.acknowledgeCustomer(v,l));next='create';return true;
  },
  async beforeDispatch(kind,key,raw){
   claim(kind);need(billingUuid(key) && ['create','confirm'].includes(kind));
   if(kind==='create'){need(raw===undefined);createKey=key;}
   else{
    const scope=fixture.resources(),v=record(raw,['subscriptionId','operationId','attemptId']);
    need(key!==createKey && v.subscriptionId===scope.subscriptionId && v.operationId===scope.operationId && billingUuid(v.attemptId));
   }
   await checked('DISPATCH',async l=>await cli.assertBillingListener(l)===true && await fixture.beforeDispatch(kind,key,raw,l)===true);
   if(kind==='create')await checked('INGRESS',()=>ingress.arm(fixture.resources()));
   else await step('INVOICE_GRANT',async l=>{
    const factory=createEvidence??(await import('./stripe-billing-invoice-webhook-evidence.mjs')).createBillingInvoiceWebhookEvidence;
    evidence=await factory({configuration,bootstrap,fixture,cli});
    const refs=await evidence.beforeConfirm(l);
    if(!refs || !(await ingress.beforeConfirm(fixture.resources(),refs,l)))fail('INVOICE_GRANT');
    if(await cli.assertBillingListener(l)!==true)fail('INVOICE_GRANT');
   });
   next=kind==='confirm'?'confirm-reserved':'create-sent';return true;
  },
  releaseOriginalConfirm(){
   claim('confirm-reserved');listener();need(ingress.releaseConfirm()===true);next='confirm-sent';return true;
  },
  async acknowledge(kind,raw){
   need(['create','confirm'].includes(kind));claim(kind+'-sent');
   const v=record(raw,kind==='create'?['subscriptionId','operationId']:['attemptId','state','nextActionAllowed']);
   if(kind==='confirm')need(v.attemptId===fixture.resources().attemptId && v.state==='OBSERVE' && v.nextActionAllowed===false);
   await checked('ACK',l=>fixture.acknowledge(kind,v,l));
   if(kind==='create'){next='confirm';return true;}
   const pair=await step('INVOICE_PAIR',l=>ingress.pair(l));
   await checked('INVOICE_MISSED',l=>evidence.beforeReconcile(pair,l));
   const receipt=await step('INVOICE_RECONCILE',async l=>{
    if(await cli.assertBillingListener(l)!==true)fail('INVOICE_RECONCILE');return fixture.reconcile(()=>listener() && ingress.requirePair(),l);
   });
   await checked('INVOICE_RECOVERED',l=>evidence.reconciled(receipt,l));
   for(const kind of ['paid','finalized']){
    await checked('INVOICE_DELIVERY',async l=>await cli.assertBillingListener(l)===true &&
     await fixture.recordInvoiceDelivery(kind,'dispatch',pair[kind].eventId,l)===true && await ingress.deliver(kind,l)===true);
    await checked('INVOICE_PROCESSED',async l=>await evidence.delivered(kind,l)===true &&
     await fixture.recordInvoiceDelivery(kind,'ack',pair[kind].eventId,l)===true);
   }
   next='complete';return true;
  },
 },async finish(){claim('complete');await checked('INGRESS',l=>ingress.finish(l));next='finished';}};
}
