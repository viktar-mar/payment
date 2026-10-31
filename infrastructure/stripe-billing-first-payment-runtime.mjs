import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {validateBillingFirstPaymentOptions} from './stripe-billing-first-payment-options.mjs';
const prepareStages=new Set(['REQUEST','PREFLIGHT','CLOCK_ADMISSION','CLOCK_CREATE','CUSTOMER_CREATE','LOCAL_RESERVE','SUBSCRIPTION_CREATE','LOCAL_RECORD','RECEIPT','ACTION_SELECT','ACTION_SCOPE','ACTION_AUTHORITY','ACTION_VERIFICATION','ACTION_GRAPH','ACTION_GRAPH_LINE_ASSOCIATION','ACTION_GRAPH_INVOICE_SETTLEMENT','ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_FLAG','ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_KIND','ACTION_GRAPH_INVOICE_SETTLEMENT_COLLECTION_METHOD','ACTION_GRAPH_INVOICE_SETTLEMENT_PAID_OFF_STRIPE','ACTION_GRAPH_INVOICE_SETTLEMENT_OVERPAID','ACTION_GRAPH_INVOICE_SETTLEMENT_STARTING_BALANCE','ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE','ACTION_GRAPH_INVOICE_SETTLEMENT_PRE_CREDIT_NOTES','ACTION_GRAPH_INVOICE_SETTLEMENT_POST_CREDIT_NOTES','ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNTS','ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNT_AMOUNTS','ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_PRETAX_CREDITS','ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_DISCOUNT_AMOUNTS','ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_PRETAX_CREDITS','ACTION_GRAPH_INVOICE_SETTLEMENT_TOTAL','ACTION_GRAPH_INVOICE_SETTLEMENT_SUBTOTAL','ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_AMOUNT','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_POSITIVE','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_CATALOG','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_PAID','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_REMAINING','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_STATUS','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_PAYMENT','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_TYPE','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_INTENT','ACTION_GRAPH_INVOICE_SETTLEMENT_SNAPSHOT_UNSUPPORTED','ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN_CASH','ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN','ACTION_GRAPH_PAYMENT_ALLOCATION','ACTION_RESERVATION','ACTION_RESULT','ACTION_BINDING','SESSION_SELECT','RESPONSE_BUILD','UNCLASSIFIED']);
const prepareCodes=new Set(['PREPARE_AUTHORITY','PREPARE_REQUEST','PREPARE_HTTP','PREPARE_RESPONSE','PREPARE_ACK',...[...prepareStages].map(s=>'PREPARE_SERVER_'+s)]);
const ownDiagnostics=new WeakMap();
/** Only errors created here carry metadata; foreign rejections are never inspected. */
export const billingFirstPaymentDiagnostic=error=>ownDiagnostics.get(error)??null;
export const isBillingFirstPaymentDiagnostic=code=>typeof code==='string'&&prepareCodes.has(code);
const need=v=>{if(!v)throw billingError('RUNTIME');};
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).sort().join(',')===[...keys].sort().join(',');
const identity=['phase','code','run_id','account_id','site_id','customer_id','price_id','subscription_id','operation_id'];
function responseShape(v,route){
 const extras=route==='prepare'?['action','session']:route==='confirm'?['protocol','attempt_id','state','retry_allowed','successor_attempt_id','next_action_allowed','expires_at']:route==='observe'&&v.phase==='SETTLED_LOCAL'?['period_start','paid_through','access_valid_until','access_evaluated_at']:[];
 need(exact(v,route==='status'?['phase','code','run_id']:[...identity,...extras]));
 if(route==='prepare'){need(exact(v.action,['kind','secret','expires_at'])&&v.action.kind==='PROVIDER_CLIENT'&&typeof v.action.secret==='string'&&Number.isFinite(Date.parse(v.action.expires_at)));need(exact(v.session,['protocol','provider','flow','publishable_key','client_secret','action_token','attempt_id','capability','expires_at'])&&v.session.protocol==='SERVER_CONFIRM_V2'&&v.session.provider==='stripe'&&v.session.flow==='PAYMENT'&&v.session.action_token===null&&['publishable_key','client_secret','attempt_id','capability'].every(k=>typeof v.session[k]==='string'&&v.session[k].length>0)&&Number.isFinite(Date.parse(v.session.expires_at)));}
}
/** Inert private client. No credentials or arbitrary URLs/operations are exposed. */
export function createBillingFirstPaymentRuntime({configuration,bootstrap},dependencies={}){
 const c=validateBillingFirstPaymentOptions(configuration);need(c);let phase='NEW',failed=false,busy=false,observations=0,deadline,diagnostic=null,failureLatched=false;
 const failure=code=>{if(!failureLatched){failureLatched=true;if(isBillingFirstPaymentDiagnostic(code))diagnostic=code;}const error=billingError('RUNTIME');if(diagnostic!==null)ownDiagnostics.set(error,diagnostic);return error;};
 async function request(route,body,limits){
  if(failed||busy){failed=true;throw failure();}busy=true;
  let checkpoint=route==='prepare'?'PREPARE_REQUEST':null;
  try{
   billingCheck(limits);if(deadline===undefined){need(limits.deadline<=Date.now()+2700000);deadline=limits.deadline;}need(limits.deadline<=deadline);
   let result,entered=false;if(route==='prepare')checkpoint='PREPARE_AUTHORITY';
   const admitted=await bootstrap.withFirstPaymentRuntime(async privateRuntime=>{
    if(route==='prepare'){checkpoint='PREPARE_AUTHORITY';need(!entered);entered=true;}
    need(!failed);
    need(privateRuntime.endpoint==='http://127.0.0.1:'+c.controlPort&&/^[A-Za-z0-9_-]{43}$/.test(privateRuntime.capability));
    if(route==='prepare')checkpoint='PREPARE_REQUEST';
    result=await billingBounded(limits,async l=>{
     const response=await (dependencies.fetch??globalThis.fetch)(privateRuntime.endpoint+'/initial-payment/'+route,{method:body===undefined?'GET':'POST',redirect:'error',signal:l.signal,headers:{Accept:'application/json',Authorization:'Bearer '+privateRuntime.capability,...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});
     let count=0;const chunks=[];const stopped=route==='prepare'&&response.status===400;
     if(route==='prepare')checkpoint=response.status===200?'PREPARE_RESPONSE':'PREPARE_HTTP';
     try{
      need(response.status===200||stopped);
      for await(const chunk of response.body??[]){billingCheck(l);count+=chunk.length;need(count<=16384);chunks.push(Buffer.from(chunk));}
     }finally{await response.body?.cancel().catch(()=>{});}
     billingCheck(l);need(!failed);const text=new TextDecoder('utf8',{fatal:true}).decode(Buffer.concat(chunks)),v=JSON.parse(text);
     if(stopped){
      // Exact flat JSON grammar rejects duplicate keys, escaped key aliases and extra fields.
      const field='"(?:code|stage)"\\s*:\\s*"[A-Z_]+"';
      need(new RegExp('^\\s*\\{\\s*'+field+'\\s*,\\s*'+field+'\\s*\\}\\s*$').test(text));
      need(exact(v,['code','stage'])&&v.code==='INITIAL_PAYMENT_STOPPED'&&prepareStages.has(v.stage));
      checkpoint='PREPARE_SERVER_'+v.stage;throw failure(checkpoint);
     }
     need(v&&v.code==='OK'&&v.run_id===c.runId);responseShape(v,route);return v;
    },120000);return true;
   },limits);
   if(route==='prepare'&&(admitted!==true||!entered||result===undefined))checkpoint='PREPARE_AUTHORITY';
   need(admitted===true&&(route!=='prepare'||entered&&result!==undefined));billingCheck(limits);need(!failed);return result;
  }catch{failed=true;throw failure(route==='prepare'&&(limits?.signal?.aborted||Date.now()>=limits?.deadline)?'PREPARE_REQUEST':checkpoint);}finally{busy=false;}
 }
 const stop=code=>{failed=true;throw failure(code);};
 return Object.freeze({
  async ready(l){if(phase!=='NEW')stop();const v=await request('status',undefined,l);if(v.phase!=='NEW')stop();phase='READY';return true;},
  async prepare(s,l){if(phase!=='READY')stop();phase='PREPARING';const v=await request('prepare',{run_id:c.runId,source_sha:c.expectedSource,account_id:s.accountId,site_id:s.siteId,customer_id:s.customerId,price_id:s.priceId,origin:s.origin,create_key:s.createKey,customer_token:s.customerToken},l);if(v.phase!=='PREPARED')stop('PREPARE_RESPONSE');phase='PREPARED';return v;},
  async confirm(original,l){if(phase!=='PREPARED')stop();phase='CONFIRMING';const v=await request('confirm',original,l);if(v.phase!=='OBSERVE'||v.state!=='OBSERVE'||v.next_action_allowed!==false||v.retry_allowed!==false||v.successor_attempt_id!==null)stop();phase='OBSERVE';return v;},
  async observe(l){if(phase!=='OBSERVE'||++observations>30)stop();const v=await request('observe',{},l);if(!['OBSERVE','SETTLED_LOCAL'].includes(v.phase))stop();if(v.phase==='SETTLED_LOCAL')phase=v.phase;return v;},
  async close(){failed=true;failureLatched=true;phase='CLOSED';return true;},
 });
}
