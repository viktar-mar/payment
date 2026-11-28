import {createHmac} from 'node:crypto';
import {EventEmitter} from 'node:events';
import {Readable} from 'node:stream';
import {createOwnedInvoiceIngress,ownedInvoiceForwardTarget} from '../stripe-billing-invoice-ingress.mjs';
import {id} from './failed-renewal-descriptors.mjs';
export const config={selectedCase:'initial_missed_reordered',runId:id(1),expectedSource:'a'.repeat(40),ports:{backend:18080,demo:18081}};
export const local={accountId:id(2),siteId:id(3),customerId:id(4)},owner={...local,subscriptionId:id(7),operationId:id(8)};
export const refs={invoiceReference:'in_fixture',subscriptionReference:'sub_fixture',customerReference:'cus_fixture'};
export const secret='whsec_'+'S'.repeat(32);
export function syntheticInvoiceIngress(configuration=config,onFailure=()=>{}){
 let receive,now=Date.now(),active=true;const sent=[];
 const server=Object.assign(new EventEmitter(),{listening:false,address:()=>({address:'127.0.0.1',port:24555}),listen(_port,_host,cb){this.listening=true;cb();},close(cb){this.listening=false;cb();},closeAllConnections(){}});
 const ingress=createOwnedInvoiceIngress(configuration,{verify:async()=>active},{createServer:(_options,fn)=>{receive=fn;return server;},now:()=>now,onFailure,
  fetch:async(url,opts)=>{sent.push({url,raw:Buffer.from(opts.body),sig:opts.headers['Stripe-Signature']});return {status:204,redirected:false,body:null};}});
 const target=()=>new URL(ownedInvoiceForwardTarget(ingress,configuration));
 function event(kind,changes={}){
  const created=Math.floor(now/1000),open=kind==='finalized';
  return {id:'evt_'+kind,object:'event',type:'invoice.'+kind,api_version:'2026-09-30.endive',livemode:false,created,data:{object:{
   id:refs.invoiceReference,object:'invoice',livemode:false,customer:refs.customerReference,billing_reason:'subscription_create',currency:'eur',status:open?'open':'paid',
   total:2000,amount_due:2000,amount_paid:open?0:2000,amount_remaining:open?2000:0,
   parent:{type:'subscription_details',subscription_details:{subscription:refs.subscriptionReference,metadata:{account_id:local.accountId,
    site_id:local.siteId,customer_id:local.customerId,subscription_id:owner.subscriptionId,operation_id:owner.operationId}}}}},...changes};
 }
 async function post(v,{invalid=false,old=false,path=target().pathname,extra=[],content,repeat=false,signature}={}){
  const raw=Buffer.from(content??JSON.stringify(v,null,2)),t=Math.floor(now/1000)-(old?301:0);
  const mac=createHmac('sha256',invalid?'foreign':secret).update(t+'.').update(raw).digest('hex');
  const sig=signature?signature(t,mac):'t='+t+',v1='+mac;
  const headers=['Host',target().host,'Content-Type','application/json','Stripe-Signature',sig,'Content-Length',String(raw.length),...extra];
  if(repeat)headers.push('Stripe-Signature',sig);
  const req=Object.assign(Readable.from([raw]),{method:'POST',url:path,rawHeaders:headers,socket:{remoteAddress:'127.0.0.1'}});
  let status;await receive(req,{writeHead(s){status=s;},end(){}});return {status,raw,sig};
 }
 return {ingress,sent,event,post,advance:n=>{now+=n;},lose:()=>{active=false;}};
}
