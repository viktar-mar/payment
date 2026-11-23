import {validateBillingFailedRenewalOptions} from './stripe-billing-failed-renewal-options.mjs';
import {checkInitialSettlementTime,checkedInitialResponse,failedStages,checkFailedWireKeys,snapshotInitialConfirmation,validateFailedRenewalStage} from './stripe-billing-failed-renewal-response.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {validateBillingFirstRenewalOptions} from './stripe-billing-first-renewal-options.mjs';
import {validateRenewalBoundary,validateRenewalFinalization} from './stripe-billing-first-renewal-response.mjs';
import {validateBillingFirstPaymentOptions} from './stripe-billing-first-payment-options.mjs';
const prepareStages=new Set(['REQUEST','PREFLIGHT','CLOCK_ADMISSION','CLOCK_CREATE','CUSTOMER_CREATE','LOCAL_RESERVE','SUBSCRIPTION_CREATE','LOCAL_RECORD','RECEIPT','ACTION_SELECT','ACTION_SCOPE','ACTION_AUTHORITY','ACTION_VERIFICATION','ACTION_GRAPH','ACTION_GRAPH_LINE_ASSOCIATION','ACTION_GRAPH_INVOICE_SETTLEMENT','ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_FLAG','ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_KIND','ACTION_GRAPH_INVOICE_SETTLEMENT_COLLECTION_METHOD','ACTION_GRAPH_INVOICE_SETTLEMENT_PAID_OFF_STRIPE','ACTION_GRAPH_INVOICE_SETTLEMENT_OVERPAID','ACTION_GRAPH_INVOICE_SETTLEMENT_STARTING_BALANCE','ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE','ACTION_GRAPH_INVOICE_SETTLEMENT_PRE_CREDIT_NOTES','ACTION_GRAPH_INVOICE_SETTLEMENT_POST_CREDIT_NOTES','ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNTS','ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNT_AMOUNTS','ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_PRETAX_CREDITS','ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_DISCOUNT_AMOUNTS','ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_PRETAX_CREDITS','ACTION_GRAPH_INVOICE_SETTLEMENT_TOTAL','ACTION_GRAPH_INVOICE_SETTLEMENT_SUBTOTAL','ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_AMOUNT','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_POSITIVE','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_CATALOG','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_PAID','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_REMAINING','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_STATUS','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_PAYMENT','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_TYPE','ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_INTENT','ACTION_GRAPH_INVOICE_SETTLEMENT_SNAPSHOT_UNSUPPORTED','ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN_CASH','ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN','ACTION_GRAPH_PAYMENT_ALLOCATION','ACTION_GRAPH_PAYMENT_ALLOCATION_STATUS_UNAVAILABLE','ACTION_GRAPH_PAYMENT_ALLOCATION_STATUS_NOT_OPEN','ACTION_GRAPH_PAYMENT_ALLOCATION_DEFAULT_UNAVAILABLE','ACTION_GRAPH_PAYMENT_ALLOCATION_DEFAULT_NOT_TRUE','ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_REQUESTED_UNAVAILABLE','ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_REQUESTED_MISMATCH','ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_PAID_UNAVAILABLE','ACTION_GRAPH_PAYMENT_ALLOCATION_AMOUNT_PAID_NONZERO','ACTION_GRAPH_PAYMENT_ALLOCATION_PAYMENT_UNAVAILABLE','ACTION_GRAPH_PAYMENT_ALLOCATION_TYPE_UNAVAILABLE','ACTION_GRAPH_PAYMENT_ALLOCATION_TYPE_UNEXPECTED','ACTION_RESERVATION','ACTION_RESULT','ACTION_BINDING','SESSION_SELECT','RESPONSE_BUILD','UNCLASSIFIED']);
const prepareCodes=new Set(['PREPARE_AUTHORITY','PREPARE_REQUEST','PREPARE_REQUEST_AUTHORITY','PREPARE_REQUEST_TRANSPORT','PREPARE_REQUEST_RESPONSE','PREPARE_HTTP','PREPARE_RESPONSE','PREPARE_ACK',...[...prepareStages].map(s=>'PREPARE_SERVER_'+s)]);
const renewalBoundaryCodes=new Set([...['TRANSPORT','HTTP','RESPONSE'].map(stage=>'POST_CONFIRM_RENEWAL_BOUNDARY_'+stage),...['INITIAL_RECEIPT','ADMISSION','CLOCK_ADVANCE','CLOCK_ADVANCE_AUTHORITY','CLOCK_ADVANCE_PREPARE','CLOCK_ADVANCE_ACCOUNT','CLOCK_ADVANCE_RESERVE','CLOCK_ADVANCE_CLAIM','CLOCK_ADVANCE_CLIENT','CLOCK_ADVANCE_PRE_READ','CLOCK_ADVANCE_PRE_VALIDATE','CLOCK_ADVANCE_POST','CLOCK_ADVANCE_POST_VALIDATE','CLOCK_ADVANCE_ACK_STORE','CLOCK_ADVANCE_POLL_WAIT','CLOCK_ADVANCE_POLL_READ','CLOCK_ADVANCE_POLL_VALIDATE','CLOCK_ADVANCE_POLL_EXHAUSTED','CLOCK_ADVANCE_READY_STORE','GRAPH','GRAPH_OBSERVER_INIT','GRAPH_CLAIM','GRAPH_CLOCK_BEFORE','GRAPH_REMOTE_PRECHECK','GRAPH_CUSTOMER','GRAPH_SUBSCRIPTION','GRAPH_INVOICES','GRAPH_INITIAL_INVOICE','GRAPH_RENEWAL_INVOICE','GRAPH_CLOCK_AFTER','GRAPH_ACCEPT','GRAPH_ASSERT','BOUNDARY_RESPONSE'].map(stage=>'POST_CONFIRM_RENEWAL_BOUNDARY_SERVER_'+stage)]);
const ownDiagnostics=new WeakMap();
const ownPrepareCheckpoints=new WeakMap();
// The Java handler emits these exact ASCII envelopes for the selected owned routes.
// Match bytes before parsing; duplicate keys, extra fields or an unrelated stage give no detail.
const observationSteps=['CLAIM','AUTHORITY','CLOCK_BEFORE','GRAPH','CUSTOMER','SUBSCRIPTION','INVOICES','INITIAL_INVOICE','RENEWAL_INVOICE',
 'FAILED_ALLOCATION','FAILED_INTENT','FAILED_CHARGE','FAILED_REVERSE','CLOCK_AFTER','ACCEPTANCE','PUBLICATION'];
const graceClockSteps=['INSPECT','CREDENTIALS','CLAIM','PRE_READ','PRE_VALIDATE','POST','POST_VALIDATE','ACK_STORE',
 'POLL_WAIT','POLL_READ','POLL_VALIDATE','POLL_EXHAUSTED','READY_STORE'];
function requestDiagnostics(stage,steps,code='FAILED_RENEWAL_STOPPED',wireStage=stage){
 const errors=new Map(steps.map(step=>[
  JSON.stringify({code,stage:wireStage+'_'+step}),stage+'_SERVER_'+step,
 ]));
 return {stage,errors,limit:Math.max(...Array.from(errors.keys(),value=>value.length)),
  codes:new Set([...['AUTHORITY','TRANSPORT','HTTP','RESPONSE'].map(step=>stage+'_'+step),...errors.values()])};
}
const failedRequestDiagnostics=new Map([
 ['confirm',requestDiagnostics('INITIAL_CONFIRM',['REQUEST','STATE','AUTHORITY','SERVICE','RESULT','RESPONSE_BUILD','UNCLASSIFIED'],'INITIAL_PAYMENT_STOPPED','CONFIRM')],
 ['failed',requestDiagnostics('FAILED_RENEWAL',['CLOCK_ADVANCE','ADMISSION','OBSERVATION','EVIDENCE',...observationSteps.map(name=>'OBSERVATION_'+name)])],
 ['grace-last-second',requestDiagnostics('GRACE_LAST_SECOND',['PREPARATION','CLOCK_ADVANCE','CLOCK_EVIDENCE','ACCESS',...graceClockSteps.map(name=>'CLOCK_'+name)])],
 ['recovery/confirm',requestDiagnostics('RECOVERY_CONFIRM',[
  'MANAGEMENT','CONFIRMATION','ACCESS','RESPONSE_BUILD',
  'CONFIRMATION_PREFLIGHT','CONFIRMATION_AUTHORITY','CONFIRMATION_CLAIM','CONFIRMATION_POST',
  'CONFIRMATION_POST_ADMISSION','CONFIRMATION_POST_WORKER_ADMISSION','CONFIRMATION_POST_WAIT_BUDGET',
  'CONFIRMATION_POST_TIMEOUT','CONFIRMATION_POST_INTERRUPTED','CONFIRMATION_POST_REPLY_BUDGET',
  'CONFIRMATION_POST_STRIPE_CARD','CONFIRMATION_POST_STRIPE_INVALID_REQUEST','CONFIRMATION_POST_STRIPE_ACCESS',
  'CONFIRMATION_POST_STRIPE_RATE_LIMIT','CONFIRMATION_POST_STRIPE_IDEMPOTENCY','CONFIRMATION_POST_STRIPE_CONNECTION',
  'CONFIRMATION_POST_STRIPE_API','CONFIRMATION_POST_STRIPE_OTHER','CONFIRMATION_REPLY','CONFIRMATION_ACK',
 ])],
]);
const failedRuntimeSteps=new WeakMap();
export function isFailedRenewalRuntimeCheckpoint(code,stage='FAILED_RENEWAL'){
 for(const diagnostic of failedRequestDiagnostics.values())if(diagnostic.stage===stage)return diagnostic.codes.has(code);
 return false;
}
export function billingFailedRenewalRuntimeCheckpoint(runtime,limits){
 const step=failedRuntimeSteps.get(runtime);
 return step&&step.limits===limits?step.code:null;
}
/** Only errors created here carry metadata; foreign rejections are never inspected. */
export const billingFirstPaymentDiagnostic=error=>ownDiagnostics.get(error)??null;
/** Last local prepare boundary only; neither completion nor provider proof. */
export const billingFirstPaymentPrepareCheckpoint=runtime=>ownPrepareCheckpoints.get(runtime)??null;
export const isBillingFirstPaymentDiagnostic=code=>typeof code==='string'&&(prepareCodes.has(code)||renewalBoundaryCodes.has(code));
const need=v=>{if(!v)throw billingError('RUNTIME');};
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).sort().join(',')===[...keys].sort().join(',');
const identity=['phase','code','run_id','account_id','site_id','customer_id','price_id','subscription_id','operation_id'];
function snapshotPrepareScope(scope){
 const result=Object.create(null);
 for(const key of ['runId','accountId','siteId','customerId','priceId','origin','createKey','customerToken']){
  const field=Object.getOwnPropertyDescriptor(scope,key);
  if(field===undefined)continue;
  need(field.enumerable&&Object.hasOwn(field,'value')&&typeof field.value==='string'&&field.value.length>0&&field.value.length<=8192);
  result[key]=field.value;
 }
 need(result.runId&&result.accountId&&result.siteId&&result.customerId&&result.priceId&&result.origin&&result.createKey&&result.customerToken);
 return Object.freeze(result);
}
function responseShape(v,route){
 const extras=route==='prepare'?['action','session']:route==='confirm'?['protocol','attempt_id','state','retry_allowed','successor_attempt_id','next_action_allowed','expires_at']:route==='observe'&&v.phase==='SETTLED_LOCAL'?['period_start','paid_through','access_valid_until','access_evaluated_at']:[];
 need(exact(v,route==='status'?['phase','code','run_id']:[...identity,...extras]));
 if(route==='prepare'){need(exact(v.action,['kind','secret','expires_at'])&&v.action.kind==='PROVIDER_CLIENT'&&typeof v.action.secret==='string'&&Number.isFinite(Date.parse(v.action.expires_at)));need(exact(v.session,['protocol','provider','flow','publishable_key','client_secret','action_token','attempt_id','capability','expires_at'])&&v.session.protocol==='SERVER_CONFIRM_V2'&&v.session.provider==='stripe'&&v.session.flow==='PAYMENT'&&v.session.action_token===null&&['publishable_key','client_secret','attempt_id','capability'].every(k=>typeof v.session[k]==='string'&&v.session[k].length>0)&&Number.isFinite(Date.parse(v.session.expires_at)));}
}
/** Inert private client. No credentials or arbitrary URLs/operations are exposed. */
export function createBillingFirstPaymentRuntime(options,dependencies={}){return createRuntime(options,dependencies,'INITIAL');}
export function createBillingFirstRenewalRuntime(options,dependencies={}){return createRuntime(options,dependencies,'FIRST_RENEWAL');}
export function createBillingFailedRenewalRuntime(options,dependencies={}){return createRuntime(options,dependencies,'FAILED_RENEWAL');}
function createRuntime({configuration,bootstrap},dependencies,purpose){
 const failedRenewal=purpose==='FAILED_RENEWAL',firstRenewal=purpose==='FIRST_RENEWAL',extended=failedRenewal||firstRenewal;
 let priorFailedStage,initialSelection,terminalRequest,terminalClose;
 const c=(failedRenewal?validateBillingFailedRenewalOptions:firstRenewal?validateBillingFirstRenewalOptions:validateBillingFirstPaymentOptions)(configuration);need(c);let phase='NEW',failed=false,busy=false,observations=0,deadline,diagnostic=null,failureLatched=false,originalIdentity,initialSettlement,renewalBoundary;
 const failure=code=>{if(!failureLatched){failureLatched=true;if(isBillingFirstPaymentDiagnostic(code))diagnostic=code;}const error=billingError('RUNTIME');if(diagnostic!==null)ownDiagnostics.set(error,diagnostic);return error;};
 async function request(route,body,limits){
  if(failed||busy){failed=true;throw failure();}busy=true;
  const requestDiagnostic=failedRenewal?failedRequestDiagnostics.get(route):undefined;
  const failedStep=requestDiagnostic?{limits,code:requestDiagnostic.stage+'_AUTHORITY'}:null;
  if(failedStep)failedRuntimeSteps.set(runtime,failedStep);
  let checkpoint=route==='prepare'?'PREPARE_REQUEST':route==='boundary'?'POST_CONFIRM_RENEWAL_BOUNDARY_TRANSPORT':null;
  try{
   billingCheck(limits);if(deadline===undefined){need(limits.deadline<=Date.now()+2700000);deadline=limits.deadline;}need(limits.deadline<=deadline);
   let result,entered=false;if(route==='prepare')checkpoint='PREPARE_AUTHORITY';
   if(route==='prepare')ownPrepareCheckpoints.set(runtime,'PREPARE_REQUEST_AUTHORITY');
   const failedStage=failedRenewal?failedStages.find(s=>s.path===route):undefined;
   const admitted=await bootstrap[failedRenewal?'withFailedRenewalRuntime':firstRenewal?'withFirstRenewalRuntime':'withFirstPaymentRuntime'](async privateRuntime=>{
    if(route==='prepare')checkpoint='PREPARE_AUTHORITY';if(extended||route==='prepare'){need(!entered);entered=true;}
    need(!failed);
    need(privateRuntime.endpoint==='http://127.0.0.1:'+c.controlPort&&/^[A-Za-z0-9_-]{43}$/.test(privateRuntime.capability));
    if(failedRenewal&&!terminalRequest){
     // Capture only a closed terminal call from the admitted binding. A busy
     // financial bootstrap callback or its aborted fetch cannot block revocation.
     const endpoint=privateRuntime.endpoint+'/failed-renewal/abort',authorization='Bearer '+privateRuntime.capability;
     terminalRequest=async l=>{
      const response=await (dependencies.fetch??globalThis.fetch)(endpoint,{method:'POST',redirect:'error',signal:l.signal,
       headers:{Accept:'application/json',Authorization:authorization,'Content-Type':'application/json'},body:'{}'});
      const expected=Buffer.from('{"code":"QUIESCENT"}'),chunks=[];let size=0;
      try{
       need(response.status===200);
       for await(const chunk of response.body??[]){billingCheck(l);size+=chunk.length;need(size<=expected.length);chunks.push(Buffer.from(chunk));}
       billingCheck(l);need(expected.equals(Buffer.concat(chunks)));return true;
      }finally{await response.body?.cancel().catch(()=>{});}
     };
    }
    if(route==='prepare')checkpoint='PREPARE_REQUEST';
    result=await billingBounded(limits,async l=>{
     if(failedRenewal)need(!failed);
     if(route==='prepare')ownPrepareCheckpoints.set(runtime,'PREPARE_REQUEST_TRANSPORT');
     if(failedStep)failedStep.code=requestDiagnostic.stage+'_TRANSPORT';
     const response=await (dependencies.fetch??globalThis.fetch)(privateRuntime.endpoint+(failedStage?'/failed-renewal/':['boundary','finalization'].includes(route)?'/first-renewal/':'/initial-payment/')+route,{method:body===undefined?'GET':'POST',redirect:'error',signal:l.signal,headers:{Accept:'application/json',Authorization:'Bearer '+privateRuntime.capability,...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});
     if(route==='prepare')ownPrepareCheckpoints.set(runtime,'PREPARE_REQUEST_RESPONSE');
     let count=0;const chunks=[];const stopped=route==='prepare'&&response.status===400,boundaryStopped=firstRenewal&&route==='boundary'&&response.status===400;
     if(route==='prepare')checkpoint=response.status===200?'PREPARE_RESPONSE':'PREPARE_HTTP';
     if(route==='boundary')checkpoint=response.status===200?'POST_CONFIRM_RENEWAL_BOUNDARY_RESPONSE':'POST_CONFIRM_RENEWAL_BOUNDARY_HTTP';
     try{
      const httpStatus=response.status;
      if(failedStep){billingCheck(l);need(!failed);failedStep.code=requestDiagnostic.stage+(httpStatus===200?'_RESPONSE':'_HTTP');}
      const failedStopped=failedStep&&httpStatus===400;
      need(httpStatus===200||stopped||boundaryStopped||failedStopped);
      const limit=failedStopped?requestDiagnostic.limit:16384;
      for await(const chunk of response.body??[]){billingCheck(l);count+=chunk.length;need(count<=limit);chunks.push(Buffer.from(chunk));}
      if(failedStopped){
       billingCheck(l);need(!failed);
       const detail=requestDiagnostic.errors.get(Buffer.concat(chunks).toString('latin1'));
       if(detail)failedStep.code=detail;
       throw billingError('RUNTIME');
      }
     }finally{await response.body?.cancel().catch(()=>{});}
     billingCheck(l);need(!failed);const text=new TextDecoder('utf8',{fatal:true}).decode(Buffer.concat(chunks)),v=JSON.parse(text);
     if(stopped){
      // Exact flat JSON grammar rejects duplicate keys, escaped key aliases and extra fields.
      const field='"(?:code|stage)"\\s*:\\s*"[A-Z_]+"';
      need(new RegExp('^\\s*\\{\\s*'+field+'\\s*,\\s*'+field+'\\s*\\}\\s*$').test(text));
      need(exact(v,['code','stage'])&&v.code==='INITIAL_PAYMENT_STOPPED'&&prepareStages.has(v.stage));
      checkpoint='PREPARE_SERVER_'+v.stage;throw failure(checkpoint);
     }
     if(boundaryStopped){
      need(/^\{"code":"FIRST_RENEWAL_STOPPED","stage":"[A-Z_]+"\}$/.test(text));
      need(exact(v,['code','stage'])&&v.code==='FIRST_RENEWAL_STOPPED');
      const code='POST_CONFIRM_RENEWAL_BOUNDARY_SERVER_'+v.stage;
      need(renewalBoundaryCodes.has(code));checkpoint=code;throw failure(code);
     }
     need(v&&v.code==='OK'&&v.run_id===c.runId);
     if(failedRenewal)checkFailedWireKeys(text);
     if(failedStage){return validateFailedRenewalStage(failedStage.method,v,originalIdentity,initialSettlement,c,priorFailedStage);}
     if(route==='boundary'||route==='finalization'){
      // Both fixed responses are flat string records. Reject duplicate keys and escaped aliases before validation.
      const field='"[a-z_]+"\\s*:\\s*"[A-Za-z0-9_:.-]+"';
      need(new RegExp('^\\s*\\{\\s*'+field+'(?:\\s*,\\s*'+field+')*\\s*\\}\\s*$').test(text));
      need((text.match(/"[a-z_]+"\s*:/g)??[]).length===Object.keys(v).length);
      return route==='boundary'?validateRenewalBoundary(v,originalIdentity,initialSettlement):validateRenewalFinalization(v,originalIdentity,renewalBoundary);
     }
     if(failedRenewal&&route!=='status')return checkedInitialResponse(v,route);
     responseShape(v,route);return v;
    },120000);
    if(failedStep){billingCheck(limits);need(!failed);failedStep.code=requestDiagnostic.stage+'_AUTHORITY';}
    return true;
   },limits);
   if(route==='prepare'&&(admitted!==true||!entered||result===undefined))checkpoint='PREPARE_AUTHORITY';
   need(admitted===true&&(!(extended||route==='prepare')||entered&&result!==undefined));billingCheck(limits);need(!failed);
   if(failedStep&&route!=='confirm')failedRuntimeSteps.delete(runtime);
   return result;
  }catch{failed=true;throw failure(route==='prepare'&&(limits?.signal?.aborted||Date.now()>=limits?.deadline)?'PREPARE_REQUEST':checkpoint);}finally{busy=false;}
 }
 const stop=code=>{failed=true;throw failure(code);};
 const runtime=Object.freeze({
  async ready(l){if(phase!=='NEW')stop();const v=await request('status',undefined,l);if(v.phase!=='NEW')stop();phase='READY';return true;},
  async prepare(s,l){
   if(phase!=='READY')stop();
   phase='PREPARING';
   if(failedRenewal)try{s=snapshotPrepareScope(s);}catch{stop('PREPARE_REQUEST');}
   const v=await request('prepare',{run_id:c.runId,source_sha:c.expectedSource,account_id:s.accountId,site_id:s.siteId,customer_id:s.customerId,price_id:s.priceId,origin:s.origin,create_key:s.createKey,customer_token:s.customerToken},l);
   if(v.phase!=='PREPARED')stop('PREPARE_RESPONSE');
   if(extended){
    for(const [k,n] of [['run_id','runId'],['account_id','accountId'],['site_id','siteId'],['customer_id','customerId'],['price_id','priceId']])
     if(v[k]!==s[n])stop('PREPARE_ACK');
    originalIdentity=Object.fromEntries(identity.slice(2).map(k=>[k,v[k]]));
   }
   if(failedRenewal)initialSelection={attemptId:v.session.attempt_id,capability:v.session.capability,createKey:s.createKey};
   phase='PREPARED';return v;
  },
  async confirm(original,l){
   if(phase!=='PREPARED')stop();
   phase='CONFIRMING';
   if(failedRenewal)try{original=snapshotInitialConfirmation(original,initialSelection);}catch{stop();}
   const v=await request('confirm',original,l);
   const confirmStep=failedRuntimeSteps.get(runtime);
   if(confirmStep)confirmStep.code='INITIAL_CONFIRM_RESPONSE';
   if(failedRenewal&&v.attempt_id!==original.attempt_id)stop();
   if(v.phase!=='OBSERVE'||v.state!=='OBSERVE'||v.next_action_allowed!==false||v.retry_allowed!==false||v.successor_attempt_id!==null)stop();
   if(extended&&Object.entries(originalIdentity).some(([k,value])=>v[k]!==value))stop();
   if(confirmStep)failedRuntimeSteps.delete(runtime);
   initialSelection=undefined;phase='OBSERVE';return v;
  },
  async observe(l){if(phase!=='OBSERVE'||++observations>30)stop();const v=await request('observe',{},l);if(!['OBSERVE','SETTLED_LOCAL'].includes(v.phase))stop();if(extended&&Object.entries(originalIdentity).some(([k,value])=>v[k]!==value))stop();if(v.phase==='SETTLED_LOCAL'){if(failedRenewal)try{checkInitialSettlementTime(v,c);}catch{stop();}phase=v.phase;initialSettlement=extended?Object.freeze({...v}):v;}return v;},
  ...(failedRenewal?Object.fromEntries(failedStages.map((stage,index)=>[stage.method,async function(l){
   if(arguments.length!==1||phase!==(index===0?'SETTLED_LOCAL':failedStages[index-1].phase))stop();
   phase='DISPATCHING';
   const v=await request(stage.path,{},l);priorFailedStage=v;phase=stage.phase;return v;
  }])):{}),
  ...(firstRenewal?{
   async boundary(l){if(phase!=='SETTLED_LOCAL')stop();phase='BOUNDARY_RUNNING';const v=await request('boundary',{},l);renewalBoundary=v;phase='RENEWAL_BOUNDARY';return v;},
   async finalization(l){if(phase!=='RENEWAL_BOUNDARY')stop();phase='FINALIZATION_RUNNING';const v=await request('finalization',{},l);phase='RENEWAL_SETTLED_LOCAL';return v;},
  }:{}),
  async close(limits){
   failed=true;failureLatched=true;phase='CLOSED';priorFailedStage=undefined;initialSelection=undefined;
   if(!failedRenewal)return true;
   if(!terminalClose){
    const send=terminalRequest;terminalRequest=undefined;
    terminalClose=send?billingBounded(limits??{deadline:Date.now()+30000},send,30000):Promise.resolve(true);
   }
   return terminalClose;
  },
 });
 ownPrepareCheckpoints.set(runtime,null);return runtime;
}
