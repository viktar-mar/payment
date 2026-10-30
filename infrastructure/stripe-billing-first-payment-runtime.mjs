import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {validateBillingFirstPaymentOptions} from './stripe-billing-first-payment-options.mjs';
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
 const c=validateBillingFirstPaymentOptions(configuration);need(c);let phase='NEW',failed=false,busy=false,observations=0,deadline;
 async function request(route,body,limits){
  if(failed||busy) {failed=true;throw billingError('RUNTIME');}busy=true;
  try{billingCheck(limits);if(deadline===undefined){need(limits.deadline<=Date.now()+2700000);deadline=limits.deadline;}need(limits.deadline<=deadline);
   let result;need(await bootstrap.withFirstPaymentRuntime(async privateRuntime=>{
    need(privateRuntime.endpoint==='http://127.0.0.1:'+c.controlPort&&/^[A-Za-z0-9_-]{43}$/.test(privateRuntime.capability));
    result=await billingBounded(limits,async l=>{
     const response=await (dependencies.fetch??globalThis.fetch)(privateRuntime.endpoint+'/initial-payment/'+route,{method:body===undefined?'GET':'POST',redirect:'error',signal:l.signal,headers:{Accept:'application/json',Authorization:'Bearer '+privateRuntime.capability,...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});
     let count=0;const chunks=[];try{need(response.status===200);for await(const chunk of response.body??[]){billingCheck(l);count+=chunk.length;need(count<=16384);chunks.push(Buffer.from(chunk));}}finally{await response.body?.cancel().catch(()=>{});}
     billingCheck(l);const v=JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(Buffer.concat(chunks)));need(v&&v.code==='OK'&&v.run_id===c.runId);responseShape(v,route);return v;
    },120000);return true;
   },limits)===true);billingCheck(limits);return result;
  }catch{failed=true;throw billingError('RUNTIME');}finally{busy=false;}
 }
 const stop=()=>{failed=true;throw billingError('RUNTIME');};
 return Object.freeze({
  async ready(l){if(phase!=='NEW')stop();const v=await request('status',undefined,l);if(v.phase!=='NEW')stop();phase='READY';return true;},
  async prepare(s,l){if(phase!=='READY')stop();phase='PREPARING';const v=await request('prepare',{run_id:c.runId,source_sha:c.expectedSource,account_id:s.accountId,site_id:s.siteId,customer_id:s.customerId,price_id:s.priceId,origin:s.origin,create_key:s.createKey,customer_token:s.customerToken},l);if(v.phase!=='PREPARED')stop();phase='PREPARED';return v;},
  async confirm(original,l){if(phase!=='PREPARED')stop();phase='CONFIRMING';const v=await request('confirm',original,l);if(v.phase!=='OBSERVE'||v.state!=='OBSERVE'||v.next_action_allowed!==false||v.retry_allowed!==false||v.successor_attempt_id!==null)stop();phase='OBSERVE';return v;},
  async observe(l){if(phase!=='OBSERVE'||++observations>30)stop();const v=await request('observe',{},l);if(!['OBSERVE','SETTLED_LOCAL'].includes(v.phase))stop();if(v.phase==='SETTLED_LOCAL')phase=v.phase;return v;},
  async close(){failed=true;phase='CLOSED';return true;},
 });
}
