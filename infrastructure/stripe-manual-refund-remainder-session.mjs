import {createFormDiagnosticSettlement} from './stripe-form-diagnostic-settlement.mjs';
import {validateManualRefundDiagnostic} from './stripe-manual-refund-schema.mjs';

const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
const provider=(v,p)=>typeof v==='string'&&new RegExp('^'+p+'[A-Za-z0-9_]{1,200}$').test(v);
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const unique=a=>new Set(a).size===a.length;
const deny=(reason='INTEGRITY')=>{throw reason;};
const fixed=()=>new Error('stripe_manual_refund_remainder_session_UNCONFIRMED');
function instant(value){
 if(typeof value!=='string')deny();
 const match=/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value);
 if(!match)deny();const seconds=Date.parse(match[1]+'Z');
 if(!Number.isFinite(seconds)||new Date(seconds).toISOString().slice(0,19)!==match[1])deny();
 return BigInt(seconds)*1000000n+BigInt((match[2]??'').padEnd(9,'0'));
}
function data(value,keys) {
 if(!value||![Object.prototype,null].includes(Object.getPrototypeOf(value)))deny();
 const descriptors=Object.getOwnPropertyDescriptors(value);
 if(Reflect.ownKeys(descriptors).some(k=>!keys.includes(k)||!Object.hasOwn(descriptors[k],'value')))deny();
 return Object.fromEntries(Object.entries(descriptors).map(([k,d])=>[k,d.value]));
}

/** Inert, single-use protected controller for the retained EUR13 leg only. */
export async function createManualRefundRemainderSession({runtime,cli,state},dependencies={}) {
 const now=dependencies.now??Date.now,fetcher=dependencies.fetch??globalThis.fetch;
 const setTimer=dependencies.setTimeout??setTimeout,clearTimer=dependencies.clearTimeout??clearTimeout;
 const io=createFormDiagnosticSettlement({now,setTimeout:setTimer,clearTimeout:clearTimer});
 const work=new Set(),controllers=new Set(),resources=new Map(),releases=new WeakMap();let disposalFailed=false;
 let target,t,paymentPath,deadline=null,admissionSignal,busy=false,failed=false,closed=false,closeUsed=false;
 let actor=null,cookie='',csrf='',initial=null,prior=null,remainder=null,remainderHistory=null,refundId=null;
 let baseline=false,rotation=false,rotationUsed=false,dispatchUsed=false,stage='BASELINE',probes=0,diagnostic=null;
 function remember(error,context) {
  if(diagnostic)return;
  const reason=context?.signal?.aborted?'ABORTED':context&&now()>=context.deadline?'DEADLINE':error;
  diagnostic=validateManualRefundDiagnostic({stage,reason,probes})??validateManualRefundDiagnostic({stage,reason:'UNCONFIRMED',probes});
 }
 function limits(value,cleanup=false) {
  const v=data(value,['deadline','signal']);
  if(!Number.isSafeInteger(v.deadline)||v.signal!==undefined&&!(v.signal instanceof AbortSignal))deny();
  if(deadline===null){if(cleanup||v.deadline>now()+1800000)deny();deadline=v.deadline;admissionSignal=v.signal;io.bind(deadline);}
  if(cleanup?v.deadline>deadline+120000:v.deadline!==deadline)deny();
  const signals=cleanup?[v.signal]:[admissionSignal,v.signal];
  const signal=AbortSignal.any(signals.filter(Boolean));
  if(signal.aborted)deny('ABORTED');if(v.deadline<=now())deny('DEADLINE');
  return Object.freeze({deadline:v.deadline,signal});
 }
 function check(c){if(c.signal?.aborted)deny('ABORTED');if(now()>=c.deadline)deny('DEADLINE');if(failed||closed)deny('UNCONFIRMED');}
 function track(body){const p=Promise.resolve().then(body);work.add(p);p.then(()=>work.delete(p),()=>work.delete(p));p.catch(()=>{});return p;}
 function release(response){
  if(releases.has(response))return releases.get(response);
  const dispose=resources.get(response);if(!dispose)deny();
  const pending=track(async()=>{try{if(now()>=deadline+120000)deny();await dispose();}catch(error){disposalFailed=true;throw error;}finally{resources.delete(response);}});
  releases.set(response,pending);return pending;
 }
 async function call(c,fn){check(c);const v=await io.wait(c,()=>track(()=>{check(c);return fn();}));check(c);return v;}
 async function phase(c,body){
  const until=Math.min(c.deadline,now()+45000),controller=new AbortController();controllers.add(controller);
  const signal=AbortSignal.any([c.signal,controller.signal]),timer=setTimer(()=>controller.abort(),Math.max(1,until-now()));
  const delegated=Object.freeze({deadline:c.deadline,signal}),bounded=Object.freeze({deadline:until,signal});
  try{return await io.wait(bounded,()=>body(delegated,until));}
  catch(error){controller.abort();remember(error,{deadline:until,signal:c.signal});throw error;}
  // Dependencies retain narrowing signals. A completed phase must not cancel future authority.
  finally{clearTimer(timer);controllers.delete(controller);}
 }
 function routes(){return new Set(['POST /api/admin/v1/auth/sessions','GET /api/admin/v1/auth/session','GET /api/admin/v1/payment-accounts','GET /api/admin/v1/sites',
  'GET /api/admin/v1/sites/'+t.site.id+'/catalog','GET /api/v1/admin/payments?site_id='+t.site.id+'&page=0&size=2&sort=CREATED_DESC',
  'GET '+paymentPath,'GET /api/v1/admin/provider-operations?payment_id='+t.paymentId,
  'POST /api/v1/admin/sites/'+t.site.id+'/callback/rotate-secret','POST '+paymentPath+'/refunds']);}
 async function request(path,c,{method='GET',body,expected=200,receiver=false,login=false,guard=false,key,until=c.deadline}={}) {
  check(c);if(receiver?!['POST /configure','GET /inspection'].includes(method+' '+path):!routes().has(method+' '+path))deny();
  if(!login&&await call(c,()=>runtime.verify(c))!==true)deny();
  const origin=receiver?t.endpoints.receiver:t.endpoints.backend,u=new URL(origin);
  if(u.protocol!=='http:'||u.hostname!=='127.0.0.1'||u.username||u.password||u.pathname!=='/'||u.search||u.hash)deny();
  if(!receiver&&!login&&(!cookie||!csrf))deny();
  const controller=new AbortController();controllers.add(controller);
  const end=Math.min(until,c.deadline,now()+10000),signal=AbortSignal.any([c.signal,controller.signal,AbortSignal.timeout(Math.max(1,end-now()))]);
  const bound={deadline:end,signal};let response;
  try{
   const headers={Accept:'application/json',...(body===undefined?{}:{'Content-Type':'application/json'}),
    ...(!receiver&&!login?{Cookie:cookie,...(method==='GET'?{}:{'X-XSRF-TOKEN':csrf})}:{}),...(key?{'Idempotency-Key':key}:{})};
   const acquisition=track(async()=>{
    check(c);if(signal.aborted||now()>=end)deny();
    // Durable dispatch is already committed; this last fence and fetch are synchronous neighbours.
    if(guard&&cli.assertRemainderListener(c)!==true)deny();
    const response=await fetcher(origin+path,{method,headers,redirect:'error',signal,...(body===undefined?{}:{body:JSON.stringify(body)})});
    if(resources.has(response))deny();resources.set(response,()=>response.body?.cancel());
    if(closed||failed||signal.aborted||now()>=end)release(response).catch(()=>{});
    return response;
   });
   response=await io.wait(bound,()=>acquisition);check(c);
   if(response.redirected||response.status!==expected)deny();
   const reader=response.body?.getReader();
   if(reader)resources.set(response,async()=>{try{await reader.cancel();}finally{reader.releaseLock();}});
   const chunks=[];let size=0;
   if(reader)for(;;){const part=await io.wait(bound,()=>track(()=>reader.read()));check(c);if(part.done)break;size+=part.value.byteLength;if(size>65536)deny();chunks.push(Buffer.from(part.value));}
   check(c);if(signal.aborted||now()>=end)deny();
   const bytes=Buffer.concat(chunks);if(expected===204&&bytes.length)deny();
   return {value:bytes.length?JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes)):null,cookies:login?response.headers.getSetCookie():null};
  }finally{controller.abort();controllers.delete(controller);if(response)await release(response);}
 }
 const read=async(path,c,options)=>(await request(path,c,options)).value;
 async function binding(c,until){
  if(await call(c,()=>cli.validateBinding(c))!==true)deny();
  const a=await read('/api/admin/v1/auth/session',c,{until});
  if(!uuid(a?.id)||!Number.isFinite(Date.parse(a.expires_at))||Date.parse(a.expires_at)<=now()||actor!==null&&actor!==a.id)deny();actor??=a.id;
  const accounts=await read('/api/admin/v1/payment-accounts',c,{until}),sites=await read('/api/admin/v1/sites',c,{until});
  if(!Array.isArray(accounts)||accounts.length!==1||!Array.isArray(sites)||sites.length!==1)deny();
  const account=accounts[0],site=sites[0];
  if(account.id!==t.account.id||account.key!=='stripe-sandbox-default'||account.external_account_id!=='acct_1UPiMpELswAd8tpR'||account.provider!=='STRIPE'||account.mode!=='SANDBOX'||account.status!=='ACTIVE'
   ||site.id!==t.site.id||site.key!==t.site.key||site.status!=='ACTIVE'||site.payment_account_id!==account.id||!same(site.authentication_modes,['EMAIL_OTP'])||site.payment_method_policy?.mode!=='ALLOWLIST'||!same(site.payment_method_policy.methods,['CARD']))deny();
  const catalog=await read('/api/admin/v1/sites/'+site.id+'/catalog',c,{until});
  if(!Array.isArray(catalog?.products)||catalog.products.length!==1||!Array.isArray(catalog.prices)||catalog.prices.length!==1)deny();
  const product=catalog.products[0],price=catalog.prices[0];
  if(!uuid(product.id)||product.id!==price.product_id||product.site_id!==site.id||product.status!=='ACTIVE'||product.key!=='s5a-product-'+t.originalRunId.replaceAll('-','')
   ||price.id!==t.price.id||price.site_id!==site.id||price.price_key!==t.price.key||price.version!==1||price.provider_price_id!==t.price.providerPriceId||price.type!=='ONE_TIME'||price.status!=='PUBLISHED'||price.amount_minor!==2000||price.currency!=='EUR'||price.tax_mode!=='NONE')deny();
 }
 function cash(d,e,amount,reference){
  if(!e||e.source!=='PROVIDER_EVENT'||!provider(e.provider_event_id,'evt_')||e.provider_object_id!==reference||e.amount_minor!==amount||e.currency!=='EUR')deny();
  const out=d.outbox_events.filter(o=>o.id===e.id&&o.callback_type===(e.event_type==='PAYMENT_SUCCEEDED'?'payment.succeeded':'refund.succeeded'));
  if(out.length!==1||out[0].status!=='DELIVERED')return false;
  // Current Admin views have no secret-version field. Delivery204 is server evidence only.
  return d.deliveries.some(v=>v.outbox_event_id===e.id&&v.status==='SUCCEEDED'&&v.http_status===204);
 }
 const tuple=(p,total,available)=>p?.status===(total===700?'PARTIALLY_REFUNDED':'REFUNDED')&&p.refunded_amount_minor===total&&p.refundable_amount_minor===available;
 async function local(c,full,until){
  stage='DETAIL';const d=await read(paymentPath,c,{until}),p=d?.payment,ids=full?[target.priorRefundId,refundId]:[target.priorRefundId];
  if(!p||p.payment_id!==t.paymentId||p.site_id!==t.site.id||p.payment_account_id!==t.account.id||p.provider!=='STRIPE'||p.source!=='CATALOG'||p.price_id!==t.price.id||p.price_key!==t.price.key||p.price_version!==1||p.amount_minor!==2000||p.currency!=='EUR'||p.tax_mode!=='NONE'
   ||p.customer_id!==null||p.donation_id!==null||p.subscription_id!==null||p.external_order_id!==null||!['attempts','events','refunds','outbox_events','deliveries'].every(k=>Array.isArray(d[k])&&d[k].length<=100)
   ||!unique(d.events.map(e=>e.id))||!unique(d.refunds.map(r=>r.id))||!unique(d.outbox_events.map(o=>o.id))||!unique(d.deliveries.map(v=>v.id))||d.attempts.length!==1||d.refunds.some(r=>!ids.includes(r.id)))deny();
  const attempt=d.attempts[0];if(!uuid(attempt.id)||attempt.attempt_number!==1||attempt.status!=='SUCCEEDED'||attempt.provider_intent_reference!==t.intentId||attempt.payment_method_family!=='CARD')deny();
  if(d.events.some((e,i)=>!uuid(e.id)||!Number.isSafeInteger(e.sequence)||e.sequence<1||i>0&&e.sequence<=d.events[i-1].sequence||!['PAYMENT_CREATED','PAYMENT_METHOD_REQUIRED','ATTEMPT_CREATED','PAYMENT_PROCESSING','PAYMENT_SUCCEEDED','REFUND_REQUESTED','REFUND_PROCESSING','REFUND_SUCCEEDED'].includes(e.event_type))
   ||d.events.filter(e=>e.event_type==='PAYMENT_CREATED').length!==1||d.events.filter(e=>e.event_type==='ATTEMPT_CREATED').length!==1)deny();
  const paymentSuccess=d.events.filter(e=>e.event_type==='PAYMENT_SUCCEEDED');
  if(paymentSuccess.length!==1||!cash(d,paymentSuccess[0],2000,t.intentId))deny();
  const requested=d.events.filter(e=>e.event_type==='REFUND_REQUESTED'),successes=d.events.filter(e=>e.event_type==='REFUND_SUCCEEDED');
  if(requested.length!==d.refunds.length||successes.length>ids.length||!unique(successes.map(e=>e.provider_event_id))||!unique(successes.map(e=>e.provider_object_id)))deny();
  const found=[];let complete=true;
  for(let i=0;i<ids.length;i++){
   const amount=i?1300:700,r=d.refunds.find(r=>r.id===ids[i]);
   if(!r){if(i===0)deny();complete=false;continue;}
   if(r.amount_minor!==amount||r.currency!=='EUR'||r.reason_code!=='REQUESTED_BY_CUSTOMER'||!['PENDING','PROCESSING','SUCCEEDED'].includes(r.status)||r.provider_refund_reference!==null&&!provider(r.provider_refund_reference,'re_'))deny();
   const reservations=requested.filter(e=>e.amount_minor===amount);if(reservations.length!==1)deny();const reservation=reservations[0];
   if(reservation.source!=='ADMIN'||reservation.currency!=='EUR'||reservation.provider_event_id!==null||reservation.provider_object_id!==null||reservation.sequence<=paymentSuccess[0].sequence||i===1&&reservation.sequence<=found[0]?.success.sequence)deny();
   const transitions=d.events.filter(e=>['REFUND_PROCESSING','REFUND_SUCCEEDED'].includes(e.event_type)&&e.provider_object_id===r.provider_refund_reference),success=transitions.filter(e=>e.event_type==='REFUND_SUCCEEDED');
   if(success.length>1||transitions.filter(e=>e.event_type==='REFUND_PROCESSING').length>1)deny();
   if((r.status==='SUCCEEDED'||success.length)&&!provider(r.provider_refund_reference,'re_'))deny();
   for(const e of transitions){if(e.amount_minor!==amount||e.currency!=='EUR'||e.sequence<=reservation.sequence)deny();if(e.event_type==='REFUND_PROCESSING'&&(!['API','PROVIDER_EVENT','RECONCILIATION'].includes(e.source)||(e.source==='PROVIDER_EVENT'?!provider(e.provider_event_id,'evt_'):e.provider_event_id!==null)||success[0]&&e.sequence>=success[0].sequence))deny();}
   if(success.length)cash(d,success[0],amount,r.provider_refund_reference);
   if(r.status!=='SUCCEEDED'||success.length!==1||!cash(d,success[0],amount,r.provider_refund_reference))complete=false;
   if(i===0&&(r.status!=='SUCCEEDED'||success.length!==1))deny();
   found.push({refund:r,reservation,success:success[0],transitions});
  }
  if(d.events.some(e=>['REFUND_PROCESSING','REFUND_SUCCEEDED'].includes(e.event_type)&&!d.refunds.some(r=>r.provider_refund_reference===e.provider_object_id))
   ||d.outbox_events.some(o=>!d.events.some(e=>e.id===o.id&&({PAYMENT_PROCESSING:'payment.processing',PAYMENT_SUCCEEDED:'payment.succeeded',REFUND_PROCESSING:'refund.processing',REFUND_SUCCEEDED:'refund.succeeded'})[e.event_type]===o.callback_type))
   ||d.deliveries.some(v=>!d.outbox_events.some(o=>o.id===v.outbox_event_id)))deny();
  const operations=await read('/api/v1/admin/provider-operations?payment_id='+t.paymentId,c,{until});
  if(!Array.isArray(operations)||operations.length!==d.refunds.length+1||!unique(operations.map(o=>o.operation_id))||!unique(operations.map(o=>o.resource_id)))deny();
  const prepare=operations.filter(o=>o.kind==='PAYMENT_PREPARE');
  if(prepare.length!==1||prepare[0].operation_id!==t.operationId||prepare[0].resource_id!==t.paymentId||prepare[0].reference!==t.intentId)deny();
  for(const o of operations){if(!uuid(o.operation_id)||o.payment_id!==t.paymentId||o.site_id!==t.site.id||o.account_id!==t.account.id||o.account_mode!=='SANDBOX'||o.status!=='ACKNOWLEDGED'||o.manual_review_required!==false)deny();if(o.kind!=='PAYMENT_PREPARE'){const r=d.refunds.find(r=>r.id===o.resource_id);if(o.kind!=='REFUND_CREATE'||!r||o.reference!==r.provider_refund_reference)deny();}}
  const history={attempt,events:d.events.filter(e=>!e.event_type.startsWith('REFUND_')),prior:found[0],operations:operations.filter(o=>o.resource_id!==refundId),outbox:d.outbox_events.filter(o=>!found[1]?.transitions.some(e=>e.id===o.id)),deliveries:d.deliveries.filter(v=>!found[1]?.transitions.some(e=>e.id===v.outbox_event_id))};
  const stable=value=>Object.fromEntries(Object.entries(value).filter(([k])=>!['status','refunded_amount_minor','refundable_amount_minor','updated_at'].includes(k)));
  if(initial&&(!same(history,initial.history)||!same(stable(p),initial.payment)))deny();
  // All contradictory detail facts are evaluated before any incomplete result.
  const page=await read('/api/v1/admin/payments?site_id='+t.site.id+'&page=0&size=2&sort=CREATED_DESC',c,{until});
  if(page?.total!==1||page.page!==0||page.size!==2||page.sort!=='CREATED_DESC'||!Array.isArray(page.items)||page.items.length!==1)deny();
  const next=page.items[0];
  if(full?(!tuple(p,700,0)&&!tuple(p,2000,0)||!tuple(next,700,0)&&!tuple(next,2000,0)):(!tuple(p,700,1300)||!tuple(next,700,1300)))deny();
  if(!same(p,next)){if(!full||!tuple(p,700,0)||!tuple(next,2000,0)||!same(stable(p),stable(next))||instant(next.updated_at)<instant(p.updated_at))deny();complete=false;}
  if(full&&!tuple(p,2000,0))complete=false;
  if(!full&&!complete)deny();
  return {d,found,complete,history,payment:stable(p),operations};
 }
 function identity(found,i){const f=found[i];return {refundId:f.refund.id,providerReference:f.refund.provider_refund_reference,providerEventId:f.success.provider_event_id};}
 async function refresh(c,until){await binding(c,until);const current=await local(c,false,until),value=identity(current.found,0);stage='PROVIDER';const proof=await call(c,()=>cli.verifyPriorPartial(Object.freeze(value),c));if(proof?.providerVerified!==true||proof.webhookVerified!==true)deny();if(cli.assertRemainderListener(c)!==true)deny();return current;}
 async function verifyBaseline(c){
  if(baseline)deny();target=state.target();t=target.original;paymentPath='/api/v1/admin/payments/'+t.paymentId;
  if(!same(runtime.resources(),target)||target.priorRunId!=='34941d87-12d3-4c74-8c5a-b62b7c914390'||target.remainderKey!=='s5a-refund:34941d87-12d3-4c74-8c5a-b62b7c914390:remainder'||target.callbackVersionBefore!==2||target.callbackVersionAfter!==3||!uuid(target.priorRefundId))deny();
  return phase(c,async(c,until)=>{
   if(await call(c,()=>runtime.withAdminCredentials(async credentials=>{const result=await request('/api/admin/v1/auth/sessions',c,{method:'POST',body:credentials,expected:204,login:true,until});const tokens=name=>result.cookies.filter(v=>v.startsWith(name+'=')).map(v=>v.split(';')[0]),sessions=tokens('PAYMENT_ADMIN_SESSION'),xsrfs=tokens('XSRF-TOKEN');if(sessions.length!==1||xsrfs.length!==1||!/^PAYMENT_ADMIN_SESSION=[A-Za-z0-9_-]{1,512}$/.test(sessions[0])||!/^XSRF-TOKEN=[A-Za-z0-9_-]{1,512}$/.test(xsrfs[0]))deny();cookie=sessions[0]+'; '+xsrfs[0];csrf=xsrfs[0].slice(11);return true;},c))!==true)deny();
   const current=await refresh(c,until);initial=structuredClone({history:current.history,payment:current.payment});prior=Object.freeze({...identity(current.found,0),cashEventId:current.found[0].success.id,amountMinor:700,totalMinor:700,providerVerified:true,webhookVerified:true,journalVerified:true,callbackDeliveryVerified:true});
   stage='BASELINE';if(await call(c,()=>state.confirmBaseline(prior,c))!==true)deny();baseline=true;return prior;
  });
 }
 async function rotateCallback(c){
  if(!baseline||rotationUsed)deny();rotationUsed=true;
  return phase(c,async(c,until)=>{await refresh(c,until);stage='ROTATION';if(await call(c,()=>state.beginRotation(c))!==true)deny();let reveal=await read('/api/v1/admin/sites/'+t.site.id+'/callback/rotate-secret',c,{method:'POST',guard:true,until});
   try{if(reveal?.site_id!==t.site.id||reveal.endpoint_url!=='http://callback-receiver:8080/callback'||reveal.enabled!==true||reveal.secret_version!==3||typeof reveal.secret!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(reveal.secret)||Buffer.from(reveal.secret,'base64url').length!==32)deny();
    if(await call(c,()=>state.ackRotation(3,c))!==true||await call(c,()=>state.beginConfigure(3,c))!==true)deny();await request('/configure',c,{method:'POST',expected:204,receiver:true,body:{secret:reveal.secret,secret_version:3},until});if(await call(c,()=>state.ackConfigure(3,c))!==true)deny();
   }finally{if(reveal)reveal.secret='';reveal=null;}
   await refresh(c,until);rotation=true;return true;
  });
 }
 async function receipt(current,c,until){
  stage='CALLBACK';const v=await read('/inspection',c,{receiver:true,until});
  if(!Array.isArray(v?.events)||v.events.length>100||v.count!==v.events.length||!Number.isSafeInteger(v.duplicates)||v.duplicates<0||!unique(v.events.map(e=>e.event_id))||v.events.some(e=>!Number.isSafeInteger(e.count)||e.count<1||!current.d.outbox_events.some(o=>o.id===e.event_id&&o.callback_type===e.event_type)))deny();
  return v.events.some(e=>e.event_id===current.found[1]?.success?.id&&e.event_type==='refund.succeeded');
 }
 async function prove(c,until){const current=await local(c,true,until);if(!current.complete)return null;if(!await receipt(current,c,until))return null;const value={...identity(current.found,1),amountMinor:1300,totalMinor:2000};
  const history={facts:current.found[1],operations:current.operations,outbox:current.d.outbox_events,deliveries:current.d.deliveries};
  if(remainder&&(!same(value,{refundId:remainder.refundId,providerReference:remainder.providerReference,providerEventId:remainder.providerEventId,amountMinor:1300,totalMinor:2000})||current.found[1].success.id!==remainder.cashEventId)||remainderHistory&&!same(history,remainderHistory))deny();
  remainderHistory??=structuredClone(history);return Object.freeze({...value,cashEventId:current.found[1].success.id,providerVerified:true,webhookVerified:true,journalVerified:true,callbackSignatureVerified:true});}
 async function refundRemainder(c){
  if(!rotation||dispatchUsed)deny();dispatchUsed=true;
  await phase(c,async(c,until)=>{await refresh(c,until);stage='DISPATCH';const leg=await call(c,()=>state.beginRemainder(c));if(!Object.isFrozen(leg)||leg.phase!=='DISPATCHING'||leg.amountMinor!==1300||leg.key!==target.remainderKey||leg.refundId!==null||leg.providerReference!==null)deny();
   const ack=await read(paymentPath+'/refunds',c,{method:'POST',expected:202,key:leg.key,guard:true,body:{amount_minor:1300,currency:'EUR',reason_code:'REQUESTED_BY_CUSTOMER'},until});
   if(!uuid(ack?.refund_id)||ack.refund_id===target.priorRefundId||ack.payment_id!==t.paymentId||ack.amount_minor!==1300||ack.currency!=='EUR'||!['PENDING','PROCESSING','SUCCEEDED'].includes(ack.status))deny();stage='ACKNOWLEDGEMENT';if(await call(c,()=>state.ackRemainder(Object.freeze({refundId:ack.refund_id}),c))!==true)deny();refundId=ack.refund_id;
  });
  return phase(c,async(c,until)=>{let proof;for(let n=0;n<30&&now()<until;n++){probes=n+1;proof=await prove(c,until);if(proof)break;const delay=dependencies.probeDelay??100;if(!Number.isInteger(delay)||delay<0||delay>250)deny();if(delay)await call(c,()=>new Promise(r=>setTimer(r,Math.min(delay,Math.max(0,until-now())))));}if(!proof)deny(now()>=until?'DEADLINE':'EXHAUSTED');stage='PROVIDER';const v=await call(c,()=>cli.inspectRemainder(Object.freeze({refundId:proof.refundId,providerReference:proof.providerReference,providerEventId:proof.providerEventId,amountMinor:1300,totalMinor:2000}),c));if(v?.providerVerified!==true||v.webhookVerified!==true)deny();stage='CONFIRMATION';if(await call(c,()=>state.confirmRemainder(proof,c))!==true)deny();remainder=proof;return proof;});
 }
 async function verifyFinal(c){if(!remainder)deny();return phase(c,async(c,until)=>{await binding(c,until);if(!await prove(c,until))deny();stage='PROVIDER';const v=await call(c,()=>cli.inspectRemainder(Object.freeze({refundId:remainder.refundId,providerReference:remainder.providerReference,providerEventId:remainder.providerEventId,amountMinor:1300,totalMinor:2000}),c));if(v?.providerVerified!==true||v.webhookVerified!==true)deny();const proof=Object.freeze({priorPartialVerified:true,priorCallbackDeliveryVerified:true,remainderConfirmed:true,providerVerified:true,webhookVerified:true,journalVerified:true,callbackSignatureVerified:true,finalTotalVerified:true});stage='FINAL';if(await call(c,()=>state.confirmFinal(proof,c))!==true)deny();return proof;});}
 async function run(value,body,nextStage){if(busy){remember('UNCONFIRMED');failed=true;throw fixed();}busy=true;if(!failed){stage=nextStage;probes=0;}let c;try{c=limits(value);check(c);const v=await io.wait(c,()=>track(()=>body(c)));check(c);return v;}catch(error){remember(error,c);failed=true;throw fixed();}finally{busy=false;}}
 async function close(value){if(closeUsed)throw fixed();closeUsed=true;closed=true;for(const controller of controllers)controller.abort();try{if(deadline===null)return true;const c=limits(value,true);for(;;){for(const response of resources.keys())release(response).catch(()=>{});if(!work.size)break;await io.wait(c,()=>Promise.allSettled([...work]));}if(resources.size||disposalFailed)deny();return true;}catch(error){if(!diagnostic)stage='CLEANUP';remember(error);failed=true;throw fixed();}finally{cookie='';csrf='';actor=null;}}
 return Object.freeze({verifyBaseline:v=>run(v,verifyBaseline,'BASELINE'),rotateCallback:v=>run(v,rotateCallback,'ROTATION'),refundRemainder:v=>run(v,refundRemainder,'DISPATCH'),verifyFinal:v=>run(v,verifyFinal,'FINAL'),diagnostic:()=>diagnostic,close});
}
