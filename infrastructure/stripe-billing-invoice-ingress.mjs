import {createServer} from 'node:http';
import {createHmac,randomBytes,timingSafeEqual} from 'node:crypto';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const live=new WeakMap(),maxBytes=262144;
const refusalCodes=new Set(['INGRESS_SIGNATURE','INGRESS_REQUEST','INGRESS_ENVELOPE','INGRESS_INVOICE','INGRESS_ORDER','INGRESS_LISTENER','INGRESS_DELIVERY']);
export function isInvoiceIngressFailureCode(value){return refusalCodes.has(value);}
const refusals=new WeakMap();
function need(value,code){
 if(value)return;
 const error=billingError('INVOICE_INGRESS');
 if(code)refusals.set(error,code);
 throw error;
}
const path='/api/v1/provider-webhooks/stripe/stripe-sandbox-default';
const version='2026-09-30.endive';
const ref=(value,prefix)=>typeof value==='string' && new RegExp('^'+prefix+'_[A-Za-z0-9]{1,200}$').test(value);
function parse(raw){
 const source=new TextDecoder('utf-8',{fatal:true}).decode(raw),result=JSON.parse(source);
 const tokens=source.match(/"(?:\\[\s\S]|[^"\\])*"|[{}\[\],:]/g)??[],stack=[];
 for(let i=0;i<tokens.length;i++){
  const token=tokens[i];
  if(token==='{'||token==='['){need(stack.length<64);stack.push(token==='{'?new Set():null);}
  else if(token==='}'||token===']')stack.pop();
  else if(token.startsWith('"') && tokens[i+1]===':'){
   const key=JSON.parse(token),keys=stack.at(-1);need(keys && !keys.has(key));keys.add(key);
  }
 }
 return result;
}
function signature(raw,header,key,now){
 const requireSignature=value=>need(value,'INGRESS_SIGNATURE');
 requireSignature(key && typeof header==='string' && header.length<=4096 && !/[^\x20-\x7e]/.test(header));
 let timestamp;const macs=[];
 for(const field of header.split(',')){
  const pair=/^([A-Za-z0-9_-]+)=(.*)$/.exec(field);
  requireSignature(pair);
  const [,name,value]=pair;
  if(name==='t'){
   requireSignature(timestamp===undefined && /^\d{1,12}$/.test(value));
   timestamp=value;
  }else if(name==='v1'){
   requireSignature(/^[a-fA-F0-9]{64}$/.test(value));
   macs.push(value);
  }
  // Stripe's other signature schemes and optional fields confer no authority.
 }
 requireSignature(timestamp!==undefined && macs.length>0 && Math.abs(now-Number(timestamp)*1000)<=300000);
 const expected=createHmac('sha256',key).update(timestamp+'.').update(raw).digest();
 requireSignature(macs.some(value=>timingSafeEqual(expected,Buffer.from(value,'hex'))));
}
export function ownedInvoiceForwardTarget(ingress,configuration){
 const owner=live.get(ingress);need(owner && owner.runId===configuration.runId && owner.source===configuration.expectedSource &&
  owner.daemon===configuration.daemon && Object.keys(owner.ports).length===Object.keys(configuration.ports).length &&
  Object.entries(owner.ports).every(([k,v])=>configuration.ports[k]===v) && configuration.selectedCase==='initial_missed_reordered');return owner.url();
}

/** Receives only the original CLI's invoice notifications. No generic forwarding or resend surface. */
export function createOwnedInvoiceIngress(configuration,bootstrap,dependencies={}){
 need(configuration.selectedCase==='initial_missed_reordered' && billingUuid(configuration.runId) && /^[a-f0-9]{40}$/.test(configuration.expectedSource));
 const now=dependencies.now??Date.now,fetcher=dependencies.fetch??fetch,listen=dependencies.createServer??createServer;
 const destination='http://127.0.0.1:'+configuration.ports.backend+path,route='/invoice/'+randomBytes(32).toString('hex');
 let server,origin,key,scope,grant,armedAt,state='new',failed=false,reading=false,closed=false,closing,opening;
 const held=new Map(),changed=()=>{wake?.();wake=undefined;};let wake;
 function check(){need(!failed && !closed);}
 function stop(code){if(!failed){failed=true;changed();dependencies.onFailure?.(code);}}
 function stopAt(error,fallback){stop(refusals.get(error)??fallback);}
 function capture(raw,header){
  check();need(scope);signature(raw,header,key,now());
  let event;
  try{event=parse(raw);}catch{need(false,'INGRESS_ENVELOPE');}
  need(event && typeof event==='object' && !Array.isArray(event),'INGRESS_ENVELOPE');
  const invoice=event.data?.object,parent=invoice?.parent?.subscription_details,meta=parent?.metadata;
  const kind=event.type==='invoice.finalized'?'finalized':event.type==='invoice.paid'?'paid':null;
  need(kind && event.object==='event' && event.api_version===version && event.livemode===false && event.account==null && event.context==null &&
   ref(event.id,'evt') && Number.isSafeInteger(event.created) && event.created>=armedAt && event.created<=Math.floor(now()/1000)+1,'INGRESS_ENVELOPE');
  need(invoice?.object==='invoice' && invoice.livemode===false && ref(invoice.id,'in') && ref(invoice.customer,'cus') &&
   invoice.parent?.type==='subscription_details' && ref(parent?.subscription,'sub') && meta?.account_id===scope.accountId &&
   meta.site_id===scope.siteId && meta.customer_id===scope.customerId && billingUuid(meta.subscription_id) && billingUuid(meta.operation_id) &&
   meta.subscription_id!==meta.operation_id && invoice.currency==='eur' && invoice.billing_reason==='subscription_create' &&
   invoice.amount_due===2000 && invoice.total===2000,'INGRESS_INVOICE');
  const d=Object.freeze({eventId:event.id,invoiceReference:invoice.id,subscriptionReference:parent.subscription,customerReference:invoice.customer,created:event.created});
  if(kind==='finalized'){
   need(state==='armed','INGRESS_ORDER');
   need(invoice.status==='open' && invoice.amount_paid===0 && invoice.amount_remaining===2000,'INGRESS_INVOICE');
  }
  else{
   const first=held.get('finalized');need(state==='confirm' && first && grant &&
    first.d.eventId!==d.eventId && first.d.created<d.created && Object.keys(grant).every(k=>d[k]===grant[k]) &&
    first.subscriptionId===meta.subscription_id && first.operationId===meta.operation_id,'INGRESS_ORDER');
   need(invoice.status==='paid' && invoice.amount_paid===2000 && invoice.amount_remaining===0,'INGRESS_INVOICE');
  }
  need(!held.has(kind),'INGRESS_ORDER');held.set(kind,{raw,header,d,subscriptionId:meta.subscription_id,operationId:meta.operation_id});
  state=kind;changed();
 }
 async function receive(req,res){
  let raw,retained=false,claimed=false;const chunks=[];
  try{
   check();need(['armed','confirm'].includes(state),'INGRESS_ORDER');
   need(server?.listening && req.socket.remoteAddress==='127.0.0.1' && req.method==='POST' && req.url===route);
   const headers=new Map();
   for(let i=0;i<req.rawHeaders.length;i+=2){
    const name=req.rawHeaders[i].toLowerCase();need(!headers.has(name));headers.set(name,req.rawHeaders[i+1]);
   }
   need(headers.get('host')===new URL(origin).host && /^application\/json(?:;\s*charset=utf-8)?$/i.test(headers.get('content-type')) &&
    !['content-encoding','authorization','cookie','stripe-account','stripe-context','proxy-authorization'].some(k=>headers.has(k)) &&
    !reading && scope);
   need(headers.has('stripe-signature'),'INGRESS_SIGNATURE');
   const length=headers.get('content-length');
   need(length===undefined || /^\d{1,6}$/.test(length) && Number(length)<=maxBytes);
   reading=true;claimed=true;let count=0;
   for await(const chunk of req){check();count+=chunk.length;need(count<=maxBytes);chunks.push(Buffer.from(chunk));}
   need(count>0 && (length===undefined || count===Number(length)));
   raw=Buffer.concat(chunks);capture(raw,headers.get('stripe-signature'));retained=true;res.shouldKeepAlive=false;res.writeHead(204);res.end();
  }catch(error){stopAt(error,'INGRESS_REQUEST');res.writeHead(400);res.end();}
  finally{if(claimed)reading=false;for(const b of chunks)b.fill(0);if(raw&&!retained)raw.fill(0);}
 }
 async function until(expected,limits){
  await billingBounded(limits,async l=>{
   while(state!==expected){
    check();billingCheck(l);
    await new Promise(resolve=>{
     const done=()=>{l.signal.removeEventListener('abort',done);if(wake===done)wake=undefined;resolve();};
     need(!wake);wake=done;l.signal.addEventListener('abort',done,{once:true});
    });
   }
   check();
  },90000);
 }
 let bindingClaimed=false,pairClaimed=false;
 const ingress=Object.freeze({
  async open(limits){
   check();need(state==='new');state='opening';
   server=listen({maxHeaderSize:8192,requestTimeout:10000,headersTimeout:5000},receive);
   server.maxConnections=4;server.maxRequestsPerSocket=1;
   const rejectConnection=(_request,connection)=>{stop('INGRESS_REQUEST');connection.destroy();};
   server.on('drop',()=>stop('INGRESS_LISTENER'));
   for(const event of ['clientError','dropRequest','checkContinue','checkExpectation','connect','upgrade'])
    server.on(event,rejectConnection);
   opening=new Promise((resolve,reject)=>{
    server.once('error',reject);server.listen(0,'127.0.0.1',()=>{server.removeListener('error',reject);server.on('error',()=>stop('INGRESS_LISTENER'));resolve();});
   });
   await billingBounded(limits,()=>opening);check();const address=server.address();
   need(server.listening && address?.address==='127.0.0.1' && Number.isInteger(address.port) && address.port>=1024 && !Object.values(configuration.ports).includes(address.port));
   origin='http://127.0.0.1:'+address.port;state='ready';return true;
  },
  listenerLost(){stop('INGRESS_LISTENER');},
  signingKey(secret){check();need(state==='ready' && !key && typeof secret==='string' && /^whsec_[A-Za-z0-9_]{16,256}$/.test(secret));key=Buffer.from(secret);return true;},
  arm(local){check();need(state==='ready' && key && ['accountId','siteId','customerId'].every(k=>billingUuid(local[k])));
   scope=Object.freeze({accountId:local.accountId,siteId:local.siteId,customerId:local.customerId});armedAt=Math.floor(now()/1000);state='armed';return true;},
  async beforeConfirm(local,refs,limits){
   try{check();need(!bindingClaimed);bindingClaimed=true;await until('finalized',limits);
    const e=held.get('finalized');need(e.subscriptionId===local.subscriptionId && e.operationId===local.operationId &&
     Object.keys(scope).every(k=>scope[k]===local[k]) && ['invoiceReference','subscriptionReference','customerReference'].every(k=>refs[k]===e.d[k]));
    grant=Object.freeze({invoiceReference:refs.invoiceReference,subscriptionReference:refs.subscriptionReference,customerReference:refs.customerReference});
    // Ensure the later paid event can establish a strictly later Stripe creation second.
    const wait=(e.d.created+1)*1000-now();if(wait>0)await billingBounded(limits,()=>new Promise(r=>setTimeout(r,wait)),3000);
    check();signature(e.raw,e.header,key,now());state='reserved';return e.d;
   }catch(error){stopAt(error,'INGRESS_ORDER');throw error;}
  },
  releaseConfirm(){
   try{check();need(!reading && state==='reserved');const e=held.get('finalized');signature(e.raw,e.header,key,now());state='confirm';return true;}
   catch(error){stopAt(error,'INGRESS_ORDER');throw error;}
  },
  requirePair(){
   try{check();need(state==='paired' && !reading);
    for(const kind of ['paid','finalized']){const e=held.get(kind);signature(e.raw,e.header,key,now());}
    return true;
   }catch(error){stopAt(error,'INGRESS_ORDER');throw error;}
  },
  async pair(limits){try{need(!pairClaimed);pairClaimed=true;await until('paid',limits);const a=held.get('finalized'),b=held.get('paid');
   signature(a.raw,a.header,key,now());signature(b.raw,b.header,key,now());state='paired';return Object.freeze({finalized:a.d,paid:b.d});
  }catch(error){stopAt(error,'INGRESS_ORDER');throw error;}},
  async deliver(kind,limits){
   try{check();need(kind==='paid' && state==='paired' || kind==='finalized' && state==='paid-delivered','INGRESS_ORDER');state='delivering';
    const e=held.get(kind);
    await billingBounded(limits,async l=>{
     need(await bootstrap.verify(l)===true);check();signature(e.raw,e.header,key,now());billingCheck(l);
     const response=await fetcher(destination,{method:'POST',redirect:'error',credentials:'omit',signal:l.signal,
      headers:{'Content-Type':'application/json','Stripe-Signature':e.header},body:e.raw});
     try{need(response.status===204 && response.redirected===false);check();}finally{await response.body?.cancel();}
    });
    e.raw.fill(0);held.delete(kind);state=kind+'-delivered';return true;
   }catch(error){stopAt(error,'INGRESS_DELIVERY');throw error;}
  },
  async finish(limits){
   check();need(state==='finalized-delivered' && !reading && held.size===0);
   await billingBounded(limits,()=>ingress.close());
   need(!failed && !reading && !server.listening);return true;
  },
  async close(){
   if(closing)return closing;
   closed=true;changed();live.delete(ingress);key?.fill(0);key=undefined;
   for(const e of held.values())e.raw.fill(0);held.clear();
   closing=(async()=>{if(opening)await opening.catch(()=>{});if(server?.listening)await new Promise((resolve,reject)=>{server.close(e=>e?reject(e):resolve());server.closeAllConnections();});need(!server?.listening);return true;})();return closing;
  },
 });
 live.set(ingress,{runId:configuration.runId,source:configuration.expectedSource,ports:Object.freeze({...configuration.ports}),daemon:configuration.daemon,
  url(){check();need(server?.listening && origin && server.address()?.port===Number(new URL(origin).port));return origin+route;}});
 return ingress;
}
