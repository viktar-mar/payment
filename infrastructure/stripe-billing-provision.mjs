import {billingFailedRenewalExcluded,validateBillingFailedRenewalOptions} from './stripe-billing-failed-renewal-options.mjs';
import {validateBillingAcceptanceOptions} from './stripe-billing-acceptance-options.mjs';
import {constants} from 'node:fs';
import {open,lstat,realpath} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve,dirname} from 'node:path';
import {createHash} from 'node:crypto';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
export const billingUuid=value=>typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const need=value=>{if(!value)throw billingError('FIXTURE');};
const digest=value=>createHash('sha256').update(value).digest('hex');
const failedRenewalCheckpoints=new WeakMap();
const checkpointCodes=new Set([
 'PROVISION_CALLBACK_INTENT','PROVISION_CALLBACK_PUT','PROVISION_CALLBACK_VALIDATE',
 'PROVISION_RECEIVER_CONFIGURE','PROVISION_MAILPIT_READ','PROVISION_MAILPIT_EMPTY','PROVISION_FIXTURE_READY',
]);
export function billingFailedRenewalProvisionCheckpoint(fixture,limits,runId,expectedSource) {
 const checkpoint=failedRenewalCheckpoints.get(fixture);
 return checkpoint&&checkpoint.limits===limits&&checkpoint.runId===runId&&checkpoint.expectedSource===expectedSource
  ? checkpoint.code : null;
}
export const isFailedRenewalProvisionCheckpoint=code=>checkpointCodes.has(code);
/** Fresh recurring fixture only. Cookies and callback secrets never enter its append-only public ledger. */
export async function createBillingFixture(options,dependencies={}) {
 need(billingFailedRenewalExcluded(options?.configuration?.env??{}));
 if(['initial_cancel_withdraw','initial_decline_retry'].includes(options.configuration?.selectedCase)){
  const configuration=validateBillingAcceptanceOptions(options.configuration);need(configuration);
  return createFixture({...options,configuration},dependencies,configuration.selectedCase==='initial_decline_retry'?'INITIAL_RETRY':'CANCELLATION');
 }
 return createFixture(options,dependencies,'INITIAL');
}
export async function createBillingFailedRenewalFixture({configuration,bootstrap,cli},dependencies={}) {
 const c=validateBillingFailedRenewalOptions(configuration);need(c);
 return createFixture({configuration:c,bootstrap,cli},dependencies,'FAILED_RENEWAL');
}
async function createFixture({configuration:c,bootstrap,cli},dependencies,purpose) {
 const failed=purpose==='FAILED_RENEWAL',graceDays=failed?3:0;
 let provisionClaimed=false,confirmRecorded=false,armState='NEW',armBoundary;
 const retry=purpose==='INITIAL_RETRY';
 const retryLedger=['create-dispatch','create-ack','confirm-dispatch','confirm-ack','decline-proof','retry-dispatch','retry-ack','successor-confirm-dispatch','successor-confirm-ack','recovered-proof'];
 let retryCursor=0,retryKeyHash,successorId,successorKeyHash;
 let nextCancellation='schedule',scheduleHash,scheduleOperation;
 const fetcher=dependencies.fetch??globalThis.fetch,write=dependencies.write??writeOwned;
 const suffix=c.runId.replaceAll('-',''),siteKey='s5a-'+suffix,email='billing-'+suffix+'@example.test';
 const endpoints=Object.fromEntries(['backend','demo','receiver'].map(k=>[k,'http://127.0.0.1:'+c.ports[k]]));
 const directory=ROOT+'/test-results/stripe-sandbox/'+c.runId;
 let sequence=0,cookie='',csrf='',provisioned=false,closed=false,busy=false,started=0,customerId,subscriptionId,operationId,attemptId,createKey,confirmKey;
 let accountId,siteId,productId,priceId,priceReference;
 const resources=()=>Object.freeze({runId:c.runId,expectedSource:c.expectedSource,endpoints:Object.freeze({...endpoints}),siteKey,email,accountId,siteId,productId,priceId,priceReference,customerId,subscriptionId,operationId,attemptId,createKeyHash:createKey&&digest(createKey),confirmKeyHash:confirmKey&&digest(confirmKey)});
 async function writeOwned(name,value,limits) {
  billingCheck(limits);
  for(const path of [ROOT,ROOT+'/test-results',ROOT+'/test-results/stripe-sandbox',directory]){
   const info=await lstat(path);need(info.isDirectory()&&!info.isSymbolicLink()&&info.uid===process.getuid()&&await realpath(path)===path&&(path===ROOT||(info.mode&0o777)===0o700));
  }
  const handle=await open(directory+'/'+name,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);
  try{billingCheck(limits);await handle.writeFile(JSON.stringify(value)+'\n');await handle.sync();billingCheck(limits);}finally{await handle.close();}
  const dir=await open(directory,constants.O_RDONLY|constants.O_NOFOLLOW);try{await dir.sync();}finally{await dir.close();}billingCheck(limits);
 }
 async function save(kind,value,limits){need(!closed);await billingBounded(limits,l=>write('billing-'+String(++sequence).padStart(3,'0')+'-'+kind+'.json',{runId:c.runId,expectedSource:c.expectedSource,...value},l));}
 async function financialReceipt(kind,value,limits) {
  if(!retry)return save(kind,value,limits);
  need(retryLedger[retryCursor]===kind);
  const current=retryCursor;retryCursor=-1;
  await save(kind,value,limits);
  retryCursor=current+1;
 }
 async function request(path,limits,{method='GET',body,status=200,target='backend'}={}) {
  return billingBounded(limits,async l=>{
   need(!closed);need(await bootstrap.verify(l)===true);
   const origin=target==='mailpit'?'http://127.0.0.1:'+c.ports.mailpitHttp:endpoints[target];
   const admin=target==='backend'&&path!=='/api/admin/v1/auth/sessions';
   const response=await fetcher(origin+path,{method,redirect:'error',signal:l.signal,headers:{Accept:'application/json',...(body===undefined?{}:{'Content-Type':'application/json'}),...(admin?{Cookie:cookie,...(method==='GET'?{}:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':'billing:'+c.runId+':'+sequence})}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
   let bytes=0;const chunks=[];
   try{billingCheck(l);need(response.status===status);for await(const chunk of response.body??[]){billingCheck(l);bytes+=chunk.length;need(bytes<=65536);chunks.push(Buffer.from(chunk));}}
   finally{await response.body?.cancel().catch(()=>{});}
   billingCheck(l);return {value:bytes?JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(Buffer.concat(chunks))):null,cookies:path==='/api/admin/v1/auth/sessions'?response.headers.getSetCookie():[]};
  });
 }
 const read=async(path,l,settings)=>(await request(path,l,settings)).value;
 async function mutate(name,path,body,status,l,check) {
  await save(name+'-dispatch',{phase:'DISPATCHING'},l);const v=await read(path,l,{method:'POST',body,status});need(check(v));await save(name+'-ack',{phase:'ACKNOWLEDGED',...(billingUuid(v?.id)?{id:v.id}:{})},l);return v;
 }
 function checkpoint(code,limits) {
  if(failed){
   billingCheck(limits);need(!closed);
   failedRenewalCheckpoints.set(fixture,{code,limits,runId:c.runId,expectedSource:c.expectedSource});
  }
 }
 const fixture=Object.freeze({resources,
  async provision(limits){
   need(!provisioned&&!busy&&!closed&&(purpose==='INITIAL'||!provisionClaimed));provisionClaimed=true;busy=true;started=Date.now();
   try{
    need(await cli.validateBinding(limits)===true);
    let admin;need(await bootstrap.withAdminCredentials(credentials=>{admin={username:credentials.username,password:credentials.password};return true;},limits)===true);
    try {
     const login=await request('/api/admin/v1/auth/sessions',limits,{method:'POST',body:admin,status:204});
     need(login.value===null&&login.cookies.length===2);const values={};
     for(const item of login.cookies){need(typeof item==='string'&&!/[\r\n]/.test(item)&&!/;\s*domain=/i.test(item));const m=/^(PAYMENT_ADMIN_SESSION|XSRF-TOKEN)=([A-Za-z0-9_-]{1,512});/.exec(item);need(m&&!values[m[1]]);values[m[1]]=m[2];}
     need(values.PAYMENT_ADMIN_SESSION&&values['XSRF-TOKEN']);cookie='PAYMENT_ADMIN_SESSION='+values.PAYMENT_ADMIN_SESSION+'; XSRF-TOKEN='+values['XSRF-TOKEN'];csrf=values['XSRF-TOKEN'];
    }finally{admin=null;}
    const accounts=await read('/api/admin/v1/payment-accounts',limits),sites=await read('/api/admin/v1/sites',limits);need(Array.isArray(accounts)&&accounts.length===0&&Array.isArray(sites)&&sites.length===0);
    await save('fresh',{phase:'FRESH'},limits);
    const account=await mutate('account','/api/admin/v1/payment-accounts',{key:'stripe-sandbox-default',provider:'STRIPE',mode:'SANDBOX',external_account_id:c.approvedBinding.externalAccountId,credentials_ref:'stripe-sandbox-default'},201,limits,v=>billingUuid(v?.id)&&v.provider==='STRIPE'&&v.mode==='SANDBOX'&&v.status==='ACTIVE'&&v.external_account_id===c.approvedBinding.externalAccountId);accountId=account.id;
    const site=await mutate('site','/api/admin/v1/sites',{key:siteKey,display_name:'Initial Billing acceptance',payment_account_id:accountId,allowed_origins:[endpoints.demo],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'ALLOWLIST',methods:['CARD']},email_otp_provider:{provider_key:'billing-email-'+suffix,sender_ref:'local-default',template_key:'otp-default'}},201,limits,v=>billingUuid(v?.id)&&v.key===siteKey&&v.status==='ACTIVE'&&v.payment_account_id===accountId&&JSON.stringify(v.allowed_origins)===JSON.stringify([endpoints.demo])&&JSON.stringify(v.authentication_modes)==='["EMAIL_OTP"]');siteId=site.id;
    const base='/api/v1/admin/sites/'+siteId;
    const providers=await read(base+'/identity-providers',limits);need(Array.isArray(providers)&&providers.length===1&&providers[0].mode==='EMAIL_OTP'&&providers[0].readiness==='READY'&&providers[0].status==='ACTIVE');
    const policy=await read(base+'/subscription-policy',limits);need(Number.isSafeInteger(policy.version));
    await mutate('policy',base+'/subscription-policy',{version:policy.version,enabled:true,grace_days:graceDays,allow_immediate_cancellation:false,portal_enabled:false,defer_upgrades:false,portal_return_urls:[]},200,limits,v=>v?.enabled===true&&v.portal_enabled===false&&(!failed||v.grace_days===3));
    const product=await mutate('product','/api/admin/v1/sites/'+siteId+'/products',{key:'billing-product-'+suffix,name:'Initial monthly Billing',description:null},201,limits,v=>billingUuid(v?.id)&&v.site_id===siteId&&v.status==='ACTIVE');productId=product.id;
    const price=await mutate('price','/api/admin/v1/products/'+productId+'/prices',{price_key:'billing-price-'+suffix,type:'SUBSCRIPTION',amount_minor:2000,currency:'EUR',billing_interval:'MONTH',trial_days:0,tax_mode:'INCLUSIVE',tax_code:null},201,limits,v=>billingUuid(v?.id)&&v.site_id===siteId&&v.product_id===productId&&v.version===1&&v.status==='DRAFT');priceId=price.id;
    const published=await mutate('publish','/api/admin/v1/prices/'+priceId+'/publish',undefined,200,limits,v=>v?.id===priceId&&v.type==='SUBSCRIPTION'&&v.status==='PUBLISHED'&&v.version===1&&v.amount_minor===2000&&v.currency==='EUR'&&v.billing_interval==='MONTH'&&v.trial_days===0&&v.tax_mode==='INCLUSIVE'&&/^price_[A-Za-z0-9]+$/.test(v.provider_price_id));priceReference=published.provider_price_id;
    const empty=await read('/inspection',limits,{target:'receiver'});need(empty.count===0&&empty.duplicates===0&&empty.events?.length===0);
    checkpoint('PROVISION_CALLBACK_INTENT',limits);
    await save('callback-dispatch',{phase:'DISPATCHING'},limits);
    checkpoint('PROVISION_CALLBACK_PUT',limits);
    let callback=await read(base+'/callback',limits,{method:'PUT',body:{endpoint_url:'http://callback-receiver:8080/callback',enabled:true}});
    checkpoint('PROVISION_CALLBACK_VALIDATE',limits);
    need(callback?.site_id===siteId&&callback.enabled===true&&callback.secret_version===1&&/^[A-Za-z0-9_-]{43}$/.test(callback.secret));
    checkpoint('PROVISION_RECEIVER_CONFIGURE',limits);
    await read('/configure',limits,{method:'POST',target:'receiver',status:204,body:{secret:callback.secret,secret_version:1}});callback=null;
    checkpoint('PROVISION_MAILPIT_READ',limits);
    const messages=await read('/api/v1/messages',limits,{target:'mailpit'});
    checkpoint('PROVISION_MAILPIT_EMPTY',limits);
    need(messages.total===0&&messages.messages?.length===0);
    checkpoint('PROVISION_FIXTURE_READY',limits);
    // Initial-payment fixtures already consume provisioning before writing READY.
    if(!failed)provisioned=true;
    await save('fixture',{phase:'READY',accountId,siteId,productId,priceId},limits);
    if(failed){billingCheck(limits);need(!closed);failedRenewalCheckpoints.delete(fixture);provisioned=true;}
    return true;
   }finally{busy=false;}
  },
  async withOtpCode(callback,limits){
   need(provisioned&&!customerId&&!closed);return billingBounded(limits,async l=>{
    for(;;){
     const list=await read('/api/v1/messages',l,{target:'mailpit'});need(list.start===0&&list.total===list.messages?.length&&list.messages_count===list.total&&list.total<=1);
     if(list.total===1){const summary=list.messages[0];need(/^[A-Za-z0-9]{1,128}$/.test(summary.ID));const detail=await read('/api/v1/message/'+summary.ID,l,{target:'mailpit'});
      for(const [m,date] of [[summary,'Created'],[detail,'Date']])need(m.To?.length===1&&m.To[0].Address===email&&m.From?.Address==='no-reply@payment.local'&&m.Subject==='Your verification code'&&Date.parse(m[date])>=Math.floor(started/1000)*1000&&Date.parse(m[date])<=Date.now());
      need(detail.ID===summary.ID&&typeof detail.Text==='string');let matches=[...detail.Text.matchAll(/(?<![0-9])[0-9]{6}(?![0-9])/g)];need(matches.length===1);need(await callback(matches[0][0])===true);matches=[];billingCheck(l);return true;
     }
     await billingBounded(l,()=>new Promise(r=>setTimeout(r,250)),1000);
    }
   },45000);
  },
  async acknowledgeCustomer(value,limits){need(provisioned&&!customerId&&billingUuid(value?.customerId)&&value.siteId===siteId);customerId=value.customerId;await save('customer',{customerId},limits);return true;},
  async beforeDispatch(kind,key,identity,limits){
   need(provisioned&&customerId&&!closed&&typeof key==='string'&&/^[A-Za-z0-9:_-]{1,255}$/.test(key));
   if(kind==='create'){need(!createKey&&!subscriptionId);createKey=key;}
   else{need(kind==='confirm'&&subscriptionId&&operationId&&!confirmKey&&billingUuid(identity?.attemptId)&&identity.subscriptionId===subscriptionId&&identity.operationId===operationId);confirmKey=key;attemptId=identity.attemptId;}
   await financialReceipt(kind+'-dispatch',{phase:'DISPATCHING',key,customerId,...(kind==='confirm'?{subscriptionId,operationId,attemptId}:{priceId})},limits);return true;
  },
  async acknowledge(kind,value,limits){
   if(kind==='create'){need(createKey&&!subscriptionId&&billingUuid(value?.subscriptionId)&&billingUuid(value.operationId)&&value.subscriptionId!==value.operationId);subscriptionId=value.subscriptionId;operationId=value.operationId;}
   else {
    need(kind==='confirm'&&confirmKey&&value?.attemptId===attemptId&&value.nextActionAllowed===false);
    need(retry?value.state==='DECLINED'&&value.retryAllowed===true&&value.successorAttemptId===null:value.state==='OBSERVE');
   }
   await financialReceipt(kind+'-ack',{phase:'ACKNOWLEDGED',subscriptionId,operationId,...(kind==='confirm'?{attemptId,...(retry?{state:'DECLINED'}:{})}:{})},limits);if(kind==='confirm')confirmRecorded=true;return true;
  },
  ...(retry?{
   async recordRetry(kind,value,limits) {
    need(confirmRecorded && retryLedger[retryCursor]===kind);
    const base={subscriptionId,operationId,customerId};
    if(kind==='decline-proof' || kind==='recovered-proof'){
     await financialReceipt(kind,{...base,phase:'VERIFIED',attemptId:kind==='decline-proof'?attemptId:successorId},limits);
    }else if(kind==='retry-dispatch' || kind==='successor-confirm-dispatch'){
     const expected=kind==='retry-dispatch'?attemptId:successorId;
     need(value.attemptId===expected && value.subscriptionId===subscriptionId && value.operationId===operationId &&
       typeof value.keyHash==='string' && /^[a-f0-9]{64}$/.test(value.keyHash) &&
       ![digest(createKey),digest(confirmKey),retryKeyHash,successorKeyHash].includes(value.keyHash));
     if(kind==='retry-dispatch')retryKeyHash=value.keyHash;else successorKeyHash=value.keyHash;
     await financialReceipt(kind,{...base,phase:'DISPATCHING',attemptId:expected,keyHash:value.keyHash},limits);
    }else if(kind==='retry-ack'){
     need(value.attemptId===attemptId && billingUuid(value.successorAttemptId) && value.successorAttemptId!==attemptId);
     successorId=value.successorAttemptId;
     await financialReceipt(kind,{...base,phase:'ACKNOWLEDGED',attemptId,successorAttemptId:successorId},limits);
    }else{
     need(kind==='successor-confirm-ack' && value.attemptId===successorId && value.state==='OBSERVE' && value.nextActionAllowed===false);
     await financialReceipt(kind,{...base,phase:'ACKNOWLEDGED',attemptId:successorId,state:'OBSERVE'},limits);
    }
    return true;
   },
  }:{}),
  ...(purpose==='CANCELLATION'?{
   async beforeCancellation(kind,keyHash,limits){
    need(!closed && provisioned && confirmRecorded && ['schedule','withdraw'].includes(kind) &&
      nextCancellation===kind && typeof keyHash==='string' && /^[0-9a-f]{64}$/.test(keyHash) &&
      ![digest(createKey),digest(confirmKey),scheduleHash].includes(keyHash));
    nextCancellation='writing';
    await save(kind+'-dispatch',{phase:'DISPATCHING',subscriptionId,customerId,keyHash},limits);
    if(kind==='schedule')scheduleHash=keyHash;
    nextCancellation=kind+'-sent';return true;
   },
   async acknowledgeCancellation(kind,id,limits){
    need(!closed && ['schedule','withdraw'].includes(kind) && nextCancellation===kind+'-sent' &&
      billingUuid(id) && id!==operationId && id!==scheduleOperation);
    nextCancellation='writing';
    await save(kind+'-ack',{phase:'ACKNOWLEDGED',subscriptionId,operationId:id},limits);
    if(kind==='schedule')scheduleOperation=id;
    nextCancellation=kind==='schedule'?'withdraw':'complete';return true;
   },
  }:{}),
  ...(failed?{
   async beforeDeclineArm(v,limits){
    need(provisioned&&!closed&&confirmRecorded&&armState==='NEW');
    armState='WRITING_INTENT';
    need(v?.phase==='RENEWAL_BOUNDARY'&&v.code==='OK');
    need(v.run_id===c.runId&&v.source_sha===c.expectedSource&&v.original_frozen_time===c.frozenTime&&v.account_id===accountId&&v.site_id===siteId&&v.customer_id===customerId&&v.price_id===priceId&&v.subscription_id===subscriptionId&&v.operation_id===operationId);
    need([v.renewal_admission_id,v.boundary_id,v.initial_bill_id,v.initial_payment_id].every(billingUuid));
    armBoundary=Object.freeze({admissionId:v.renewal_admission_id,boundaryId:v.boundary_id,initialBillId:v.initial_bill_id,initialPaymentId:v.initial_payment_id});
    await save('decline-arm-dispatch',{phase:'DISPATCHING',subscriptionId,operationId,customerId,...armBoundary},limits);
    armState='DISPATCHING';return true;
   },
   async acknowledgeDeclineArm(v,limits){
    need(!closed&&armState==='DISPATCHING');
    armState='WRITING_ACK';
    need(v?.phase==='DECLINE_ARMED'&&v.code==='OK'&&v.arm_run_id===c.runId&&v.source_sha===c.expectedSource&&v.subscription_id===subscriptionId&&v.arm_boundary_id===armBoundary.boundaryId&&v.arm_admission_id===armBoundary.admissionId);
    need(typeof v.arm_payment_method_id==='string'&&/^pm_[a-zA-Z0-9]+$/.test(v.arm_payment_method_id)&&Number.isFinite(Date.parse(v.arm_acknowledged_at)));
    await save('decline-arm-ack',{phase:'ACKNOWLEDGED',subscriptionId,operationId,customerId,...armBoundary,acknowledgedAt:v.arm_acknowledged_at},limits);
    armState='ACKNOWLEDGED';return true;
   }
  }:{}),
  async inspectCallbacks(limits){return read('/inspection/digests',limits,{target:'receiver'});},
  async close(){closed=true;cookie='';csrf='';createKey=undefined;confirmKey=undefined;return true;},
 });
 return fixture;
}
