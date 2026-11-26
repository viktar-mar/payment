import {billingFailedRenewalExcluded,validateBillingFailedRenewalOptions} from './stripe-billing-failed-renewal-options.mjs';
import {billingOptionalTrialExcluded,validateBillingOptionalTrialOptions} from './stripe-billing-optional-trial-options.mjs';
import {validateBillingAcceptanceOptions} from './stripe-billing-acceptance-options.mjs';
import {billingFailedRenewalVerificationCheckpoint} from './stripe-sandbox-bootstrap.mjs';
import {constants} from 'node:fs';
import {open,lstat,realpath} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve,dirname} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
export const billingUuid=value=>typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const need=value=>{if(!value)throw billingError('FIXTURE');};
const digest=value=>createHash('sha256').update(value).digest('hex');
const failedRenewalCheckpoints=new WeakMap();
const initial3dsCheckpoints=new WeakMap();
const initial3dsReads=['PROVISION_LOGIN','PROVISION_ACCOUNTS','PROVISION_SITES'];
const initial3dsCodes=new Set([
 'PROVISION_BINDING','PROVISION_ADMIN','PROVISION_LOGIN_COOKIES','PROVISION_FRESHNESS','PROVISION_FRESH_RECEIPT',
 ...initial3dsReads.flatMap(step=>['VERIFY','REQUEST','HTTP','RESPONSE'].map(detail=>step+'_'+detail)),
]);
const identityProviderRead='PROVISION_IDENTITY_PROVIDERS',policyRead='PROVISION_SUBSCRIPTION_POLICY',publishStep='PROVISION_PUBLISH';
// A fresh publication can run four sequential Stripe calls with a 20-second limit each.
const publishRequestLimit=90000;
const publishConflictTitles=new Map([
 ['PROVIDER_RESULT_UNKNOWN','Provider result requires recovery'],
 ['PROVIDER_OPERATION_CONFLICT','Provider operation conflicts with the request'],
 ['PRICE_PUBLICATION_CONFLICT','Price publication conflicts with current catalog'],
 ['resource_conflict','Resource conflict'],
]);
const checkpointCodes=new Set([
 'PROVISION_SITE_ACK','PROVISION_POLICY_INTENT',
 ...[identityProviderRead,policyRead,publishStep].flatMap(step=>['VERIFY','REQUEST','HTTP','RESPONSE','VALIDATE'].map(detail=>step+'_'+detail)),
 ...['INTENT','ACK','HTTP_CONFLICT','HTTP_CLIENT_ERROR','HTTP_SERVER_ERROR'].map(detail=>publishStep+'_'+detail),
 ...['ADMISSION','SOURCE','TOPOLOGY','HEALTH'].map(detail=>publishStep+'_VERIFY_'+detail),
 ...Array.from(publishConflictTitles.keys(),code=>publishStep+'_HTTP_'+code.toUpperCase()),
 'PROVISION_CALLBACK_INTENT','PROVISION_CALLBACK_PUT','PROVISION_CALLBACK_VALIDATE',
 'PROVISION_RECEIVER_CONFIGURE','PROVISION_MAILPIT_READ','PROVISION_MAILPIT_EMPTY','PROVISION_FIXTURE_READY',
]);
export function billingFailedRenewalProvisionCheckpoint(fixture,limits,runId,expectedSource) {
 const checkpoint=failedRenewalCheckpoints.get(fixture);
 if(!checkpoint||checkpoint.limits!==limits||checkpoint.runId!==runId||checkpoint.expectedSource!==expectedSource)return null;
 const step=checkpoint.verifying&&billingFailedRenewalVerificationCheckpoint(checkpoint.verifying.bootstrap,checkpoint.verifying.limits);
 return step?checkpoint.code+'_'+step:checkpoint.code;
}
export const isFailedRenewalProvisionCheckpoint=code=>checkpointCodes.has(code);
export function billingInitial3dsProvisionCheckpoint(fixture,limits,runId,expectedSource) {
 const checkpoint=initial3dsCheckpoints.get(fixture);
 return checkpoint?.limits===limits&&checkpoint?.runId===runId&&checkpoint?.expectedSource===expectedSource
  ?checkpoint?.code??null:null;
}
export const isInitial3dsProvisionCheckpoint=code=>initial3dsCodes.has(code);
async function responseBytes(response,limits,maximum){
 let bytes=0;const chunks=[];
 for await(const chunk of response.body??[]){billingCheck(limits);bytes+=chunk.length;need(bytes<=maximum);chunks.push(Buffer.from(chunk));}
 billingCheck(limits);return Buffer.concat(chunks);
}
function publishHttpCategory(status){
 if(!Number.isInteger(status))return '_HTTP';
 if(status===409)return '_HTTP_CONFLICT';
 if(status>=400&&status<500)return '_HTTP_CLIENT_ERROR';
 if(status>=500&&status<600)return '_HTTP_SERVER_ERROR';
 return '_HTTP';
}
async function publishConflictCode(response,limits){
 return billingBounded(limits,async l=>{
  if(!/^application\/problem\+json(?:;\s*charset=utf-8)?$/i.test(response.headers.get('content-type')??''))return null;
  const text=new TextDecoder('utf8',{fatal:true,ignoreBOM:true}).decode(await responseBytes(response,l,1024));
  const v=JSON.parse(text),title=publishConflictTitles.get(v?.code);
  if(!title||typeof v.request_id!=='string'||!/^[A-Za-z0-9_.:-]{1,128}$/.test(v.request_id))return null;
  const exact=JSON.stringify({type:'urn:payment:problem:'+v.code,title,status:409,code:v.code,request_id:v.request_id});
  return text===exact?v.code.toUpperCase():null;
 },1000).catch(()=>null);
}

function matchesPortalPolicy(value,siteId,expected) {
 return value?.site_id===siteId&&value.version===expected.version&&value.enabled===expected.enabled
  &&value.grace_days===expected.grace_days&&value.allow_immediate_cancellation===expected.allow_immediate_cancellation
  &&value.portal_enabled===expected.portal_enabled&&value.defer_upgrades===expected.defer_upgrades
  &&Array.isArray(value.portal_return_urls)&&value.portal_return_urls.length===expected.portal_return_urls.length
  &&expected.portal_return_urls.every((url,index)=>value.portal_return_urls[index]===url);
}

/** Describe a Portal policy change for acknowledged fixture resources. The caller owns transport and dispatch. */
export function buildBillingPortalPolicyRequest(configuration,receipts) {
 const c=validateBillingAcceptanceOptions(configuration);
 need(c?.selectedCase==='initial_success');
 const {account,site,product,price,policy}=receipts??{};
 const backend='http://127.0.0.1:'+c.ports.backend,demo='http://127.0.0.1:'+c.ports.demo;
 const siteKey='s5a-'+c.runId.replaceAll('-','');
 need(billingUuid(account?.id)&&account.provider==='STRIPE'&&account.mode==='SANDBOX'&&account.status==='ACTIVE'
  &&account.external_account_id===c.approvedBinding.externalAccountId);
 need(billingUuid(site?.id)&&site.key===siteKey&&site.status==='ACTIVE'&&site.payment_account_id===account.id
  &&Array.isArray(site.allowed_origins)&&site.allowed_origins.length===1&&site.allowed_origins[0]===demo
  &&Array.isArray(site.authentication_modes)&&site.authentication_modes.length===1&&site.authentication_modes[0]==='EMAIL_OTP');
 need(billingUuid(product?.id)&&product.site_id===site.id&&product.status==='ACTIVE');
 need(billingUuid(price?.id)&&price.site_id===site.id&&price.product_id===product.id&&price.type==='SUBSCRIPTION'
  &&price.status==='PUBLISHED'&&price.version===1&&price.amount_minor===2000&&price.currency==='EUR'
  &&price.billing_interval==='MONTH'&&price.trial_days===0&&price.tax_mode==='INCLUSIVE'
  &&typeof price.provider_price_id==='string'&&/^price_[A-Za-z0-9]+$/.test(price.provider_price_id));
 const defaults={version:0,enabled:false,grace_days:3,allow_immediate_cancellation:false,portal_enabled:false,defer_upgrades:false,portal_return_urls:[]};
 const siteId=site.id;
 need(matchesPortalPolicy(policy,siteId,defaults));
 const returnUrl=new URL('/',demo);
 // The manager's canonical address includes the empty second product slot.
 returnUrl.search=new URLSearchParams({api_base_url:backend,site_key:siteKey,subscription_product_1_id:product.id,
  subscription_price_1_id:price.id,locale:'en',subscription_product_2_id:''}).toString();
 const portalReturnUrl=returnUrl.href;
 const body=Object.freeze({...defaults,enabled:true,grace_days:0,portal_enabled:true,portal_return_urls:Object.freeze([portalReturnUrl])});
 return Object.freeze({method:'POST',url:backend+'/api/v1/admin/sites/'+siteId+'/subscription-policy',body,
  validateResponse(value){
   need(matchesPortalPolicy(value,siteId,{...body,version:1}));
   return Object.freeze({portalReturnUrl,portalPolicyVersion:1});
  }});
}

/** Fresh recurring fixture only. Cookies and callback secrets never enter its append-only public ledger. */
export async function createBillingFixture(options,dependencies={}) {
 const env=options?.configuration?.env??{};
 need(billingFailedRenewalExcluded(env)&&billingOptionalTrialExcluded(env)&&options?.configuration?.selectedCase!=='optional_trial_pause_resume');
 if(['initial_cancel_withdraw','initial_decline_retry','initial_3ds','initial_missed_reordered','initial_required_trial'].includes(options.configuration?.selectedCase)){
  const configuration=validateBillingAcceptanceOptions(options.configuration);need(configuration);
  return createFixture({...options,configuration},dependencies,configuration.selectedCase==='initial_required_trial'?'REQUIRED_TRIAL':configuration.selectedCase==='initial_missed_reordered'?'INVOICE_WEBHOOKS':configuration.selectedCase==='initial_3ds'?'INITIAL_3DS':configuration.selectedCase==='initial_decline_retry'?'INITIAL_RETRY':'CANCELLATION');
 }
 return createFixture(options,dependencies,'INITIAL');
}
export async function createBillingFailedRenewalFixture({configuration,bootstrap,cli},dependencies={}) {
 const c=validateBillingFailedRenewalOptions(configuration);need(c);
 return createFixture({configuration:c,bootstrap,cli},dependencies,'FAILED_RENEWAL');
}
export async function createBillingOptionalTrialFixture({configuration,bootstrap,cli},dependencies={}) {
 const c=validateBillingOptionalTrialOptions(configuration);need(c);
 const {resources,provision,withOtpCode,acknowledgeCustomer,inspectCallbacks,close}=
  await createFixture({configuration:c,bootstrap,cli},dependencies,'OPTIONAL_TRIAL');
 return Object.freeze({resources,provision,withOtpCode,acknowledgeCustomer,inspectCallbacks,close});
}
async function createFixture({configuration:c,bootstrap,cli},dependencies,purpose) {
 const failed=purpose==='FAILED_RENEWAL',graceDays=failed?3:0;
 let provisionClaimed=false,confirmRecorded=false,armState='NEW',armBoundary;
 const invoices=purpose==='INVOICE_WEBHOOKS',requiredTrial=purpose==='REQUIRED_TRIAL';
 const optionalTrial=purpose==='OPTIONAL_TRIAL';
 let trialSetupStage='trial-permit-dispatch',trialPermitKeyHash;
 const trialDays=requiredTrial||optionalTrial?7:0;
 const trialPolicy=requiredTrial?'REQUIRED_BEFORE_TRIAL':optionalTrial?'OPTIONAL_DURING_TRIAL':null;
 let reconciliationClaimed=false;
 const retry=purpose==='INITIAL_RETRY',challenge=purpose==='INITIAL_3DS';
 const retryLedger=invoices?['create-dispatch','create-ack','confirm-dispatch','confirm-ack','reconcile-dispatch','reconcile-ack','paid-dispatch','paid-ack','finalized-dispatch','finalized-ack']:challenge?['create-dispatch','create-ack','confirm-dispatch','confirm-ack','challenge-dispatch','challenge-ack']:['create-dispatch','create-ack','confirm-dispatch','confirm-ack','decline-proof','retry-dispatch','retry-ack','successor-confirm-dispatch','successor-confirm-ack','recovered-proof'];
 let retryCursor=0,retryKeyHash,successorId,successorKeyHash;
 let nextCancellation='schedule',scheduleHash,scheduleOperation;
 const fetcher=dependencies.fetch??globalThis.fetch,write=dependencies.write??writeOwned;
 const suffix=c.runId.replaceAll('-',''),siteKey='s5a-'+suffix,email='billing-'+suffix+'@example.test';
 const endpoints=Object.fromEntries(['backend','demo','receiver'].map(k=>[k,'http://127.0.0.1:'+c.ports[k]]));
 const directory=ROOT+'/test-results/stripe-sandbox/'+c.runId;
 let sequence=0,cookie='',csrf='',provisioned=false,closed=false,busy=false,started=0,customerId,subscriptionId,operationId,attemptId,createKey,confirmKey;
 let accountId,siteId,productId,priceId,priceReference;
 const resources=()=>Object.freeze({runId:c.runId,expectedSource:c.expectedSource,endpoints:Object.freeze({...endpoints}),siteKey,email,accountId,siteId,productId,priceId,priceReference,customerId,subscriptionId,operationId,attemptId,createKeyHash:createKey&&digest(createKey),confirmKeyHash:confirmKey&&digest(confirmKey),
  ...(requiredTrial?{trialPermitKeyHash}:{})});
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
  if(!retry&&!challenge&&!invoices)return save(kind,value,limits);
  need(retryLedger[retryCursor]===kind);
  const current=retryCursor;retryCursor=-1;
  await save(kind,value,limits);
  retryCursor=current+1;
 }
 async function request(path,limits,{method='GET',body,status=200,target='backend',idempotencyKey,beforeSend,provisionStep}={}) {
  const publish=(failed||optionalTrial)&&provisionStep===publishStep;
  return billingBounded(limits,async l=>{
   need(!closed);
   if(provisionStep)checkpoint(provisionStep+'_VERIFY',limits,publish?l:undefined);
   need(await bootstrap.verify(l)===true);
   const origin=target==='mailpit'?'http://127.0.0.1:'+c.ports.mailpitHttp:endpoints[target];
   const admin=target==='backend'&&path!=='/api/admin/v1/auth/sessions';
   billingCheck(l);if(beforeSend)need(beforeSend()===true);
   if(provisionStep)checkpoint(provisionStep+'_REQUEST',limits);
   const response=await fetcher(origin+path,{method,redirect:'error',signal:l.signal,headers:{Accept:'application/json',...(body===undefined?{}:{'Content-Type':'application/json'}),...(admin?{Cookie:cookie,...(method==='GET'?{}:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':idempotencyKey??('billing:'+c.runId+':'+sequence)})}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
   let payload;
   try{
    billingCheck(l);const actualStatus=response.status,expectedStatus=actualStatus===status;
    if(provisionStep)checkpoint(provisionStep+(expectedStatus?'_RESPONSE':publish?publishHttpCategory(actualStatus):'_HTTP'),limits);
    if(publish&&!expectedStatus&&actualStatus===409){
     const code=await publishConflictCode(response,l);
     billingCheck(l);if(code)checkpoint(publishStep+'_HTTP_'+code,limits);
    }
    need(expectedStatus);
    payload=await responseBytes(response,l,65536);
   }
   finally{await response.body?.cancel().catch(()=>{});}
   billingCheck(l);return {value:payload.length?JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(payload)):null,cookies:path==='/api/admin/v1/auth/sessions'?response.headers.getSetCookie():[]};
  },publish?publishRequestLimit:undefined);
 }
 const read=async(path,l,settings)=>(await request(path,l,settings)).value;
 async function mutate(name,path,body,status,l,check,{dispatchCheckpoint,ackCheckpoint,provisionStep}={}) {
  if(dispatchCheckpoint)checkpoint(dispatchCheckpoint,l);
  await save(name+'-dispatch',{phase:'DISPATCHING'},l);
  if(failed&&dispatchCheckpoint){
   billingCheck(l);need(!closed);if(!provisionStep)failedRenewalCheckpoints.delete(fixture);
  }
  const v=await read(path,l,{method:'POST',body,status,provisionStep});
  if(provisionStep)checkpoint(provisionStep+'_VALIDATE',l);
  need(check(v));
  if(ackCheckpoint)checkpoint(ackCheckpoint,l);
  await save(name+'-ack',{phase:'ACKNOWLEDGED',...(billingUuid(v?.id)?{id:v.id}:{})},l);return v;
 }
 function checkpoint(code,limits,verificationLimits) {
  if(failed){
   billingCheck(limits);need(!closed);
   failedRenewalCheckpoints.set(fixture,{code,limits,runId:c.runId,expectedSource:c.expectedSource,
    verifying:verificationLimits?{bootstrap,limits:verificationLimits}:undefined});
  }else if(challenge&&isInitial3dsProvisionCheckpoint(code)){
   billingCheck(limits);need(!closed);
   initial3dsCheckpoints.set(fixture,{code,limits,runId:c.runId,expectedSource:c.expectedSource});
  }
 }
 const fixture=Object.freeze({resources,
  async provision(limits){
   need(!provisioned&&!busy&&!closed&&(purpose==='INITIAL'||!provisionClaimed));provisionClaimed=true;busy=true;started=Date.now();
   try{
    if(challenge)checkpoint('PROVISION_BINDING',limits);
    need(await (optionalTrial?cli.assertBillingListener(limits):cli.validateBinding(limits))===true);
    if(challenge)checkpoint('PROVISION_ADMIN',limits);
    let admin;need(await bootstrap.withAdminCredentials(credentials=>{admin={username:credentials.username,password:credentials.password};return true;},limits)===true);
    try {
     const login=await request('/api/admin/v1/auth/sessions',limits,{method:'POST',body:admin,status:204,
      provisionStep:challenge?'PROVISION_LOGIN':undefined});
     if(challenge)checkpoint('PROVISION_LOGIN_COOKIES',limits);
     need(login.value===null&&login.cookies.length===2);const values={};
     for(const item of login.cookies){need(typeof item==='string'&&!/[\r\n]/.test(item)&&!/;\s*domain=/i.test(item));const m=/^(PAYMENT_ADMIN_SESSION|XSRF-TOKEN)=([A-Za-z0-9_-]{1,512});/.exec(item);need(m&&!values[m[1]]);values[m[1]]=m[2];}
     need(values.PAYMENT_ADMIN_SESSION&&values['XSRF-TOKEN']);cookie='PAYMENT_ADMIN_SESSION='+values.PAYMENT_ADMIN_SESSION+'; XSRF-TOKEN='+values['XSRF-TOKEN'];csrf=values['XSRF-TOKEN'];
    }finally{admin=null;}
    const accounts=await read('/api/admin/v1/payment-accounts',limits,{provisionStep:challenge?'PROVISION_ACCOUNTS':undefined});
    const sites=await read('/api/admin/v1/sites',limits,{provisionStep:challenge?'PROVISION_SITES':undefined});
    if(challenge)checkpoint('PROVISION_FRESHNESS',limits);
    need(Array.isArray(accounts)&&accounts.length===0&&Array.isArray(sites)&&sites.length===0);
    if(challenge)checkpoint('PROVISION_FRESH_RECEIPT',limits);
    await save('fresh',{phase:'FRESH'},limits);
    if(challenge)initial3dsCheckpoints.delete(fixture);
    const account=await mutate('account','/api/admin/v1/payment-accounts',{key:'stripe-sandbox-default',provider:'STRIPE',mode:'SANDBOX',external_account_id:c.approvedBinding.externalAccountId,credentials_ref:'stripe-sandbox-default'},201,limits,v=>billingUuid(v?.id)&&v.provider==='STRIPE'&&v.mode==='SANDBOX'&&v.status==='ACTIVE'&&v.external_account_id===c.approvedBinding.externalAccountId);accountId=account.id;
    const site=await mutate('site','/api/admin/v1/sites',{key:siteKey,display_name:'Initial Billing acceptance',payment_account_id:accountId,allowed_origins:[endpoints.demo],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'ALLOWLIST',methods:['CARD']},email_otp_provider:{provider_key:'billing-email-'+suffix,sender_ref:'local-default',template_key:'otp-default'}},201,limits,v=>billingUuid(v?.id)&&v.key===siteKey&&v.status==='ACTIVE'&&v.payment_account_id===accountId&&JSON.stringify(v.allowed_origins)===JSON.stringify([endpoints.demo])&&JSON.stringify(v.authentication_modes)==='["EMAIL_OTP"]',{ackCheckpoint:'PROVISION_SITE_ACK'});siteId=site.id;
    const base='/api/v1/admin/sites/'+siteId;
    const providers=await read(base+'/identity-providers',limits,{provisionStep:identityProviderRead});
    checkpoint(identityProviderRead+'_VALIDATE',limits);
    need(Array.isArray(providers)&&providers.length===1&&providers[0].mode==='EMAIL_OTP'&&providers[0].readiness==='READY'&&providers[0].status==='ACTIVE');
    const policy=await read(base+'/subscription-policy',limits,{provisionStep:policyRead});
    checkpoint(policyRead+'_VALIDATE',limits);
    need(Number.isSafeInteger(policy.version));
    await mutate('policy',base+'/subscription-policy',{version:policy.version,enabled:true,grace_days:graceDays,allow_immediate_cancellation:false,portal_enabled:false,defer_upgrades:false,portal_return_urls:[]},200,limits,v=>v?.enabled===true&&v.portal_enabled===false&&(!failed||v.grace_days===3),{dispatchCheckpoint:'PROVISION_POLICY_INTENT'});
    const product=await mutate('product','/api/admin/v1/sites/'+siteId+'/products',{key:'billing-product-'+suffix,name:'Initial monthly Billing',description:null},201,limits,v=>billingUuid(v?.id)&&v.site_id===siteId&&v.status==='ACTIVE');productId=product.id;
    const price=await mutate('price','/api/admin/v1/products/'+productId+'/prices',{price_key:'billing-price-'+suffix,type:'SUBSCRIPTION',amount_minor:2000,currency:'EUR',billing_interval:'MONTH',trial_days:trialDays,...(trialPolicy?{trial_payment_method_policy:trialPolicy}:{}),tax_mode:'INCLUSIVE',tax_code:null},201,limits,v=>billingUuid(v?.id)&&v.site_id===siteId&&v.product_id===productId&&v.version===1&&v.status==='DRAFT');priceId=price.id;
    const published=await mutate('publish','/api/admin/v1/prices/'+priceId+'/publish',undefined,200,limits,
     v=>v?.id===priceId&&v.type==='SUBSCRIPTION'&&v.status==='PUBLISHED'&&v.version===1&&v.amount_minor===2000&&v.currency==='EUR'&&v.billing_interval==='MONTH'&&v.trial_days===trialDays&&(!trialPolicy||v.trial_payment_method_policy===trialPolicy)&&v.tax_mode==='INCLUSIVE'&&/^price_[A-Za-z0-9]+$/.test(v.provider_price_id),
     {dispatchCheckpoint:publishStep+'_INTENT',ackCheckpoint:publishStep+'_ACK',provisionStep:publishStep});
    priceReference=published.provider_price_id;
    if(failed)failedRenewalCheckpoints.delete(fixture);
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
   else{need(!requiredTrial&&kind==='confirm'&&subscriptionId&&operationId&&!confirmKey&&billingUuid(identity?.attemptId)&&identity.subscriptionId===subscriptionId&&identity.operationId===operationId);confirmKey=key;attemptId=identity.attemptId;}
   await financialReceipt(kind+'-dispatch',{phase:'DISPATCHING',key,customerId,...(kind==='confirm'?{subscriptionId,operationId,attemptId}:{priceId})},limits);return true;
  },
  async acknowledge(kind,value,limits){
   if(kind==='create'){need(createKey&&!subscriptionId&&billingUuid(value?.subscriptionId)&&billingUuid(value.operationId)&&value.subscriptionId!==value.operationId);subscriptionId=value.subscriptionId;operationId=value.operationId;}
   else {
    need(!requiredTrial&&kind==='confirm'&&confirmKey&&value?.attemptId===attemptId&&value.nextActionAllowed===challenge);
    need(challenge?value.state==='REQUIRES_ACTION'&&value.retryAllowed===false&&value.successorAttemptId===null:retry?value.state==='DECLINED'&&value.retryAllowed===true&&value.successorAttemptId===null:value.state==='OBSERVE');
   }
   await financialReceipt(kind+'-ack',{phase:'ACKNOWLEDGED',subscriptionId,operationId,...(kind==='confirm'?{attemptId,...(challenge?{state:'REQUIRES_ACTION'}:retry?{state:'DECLINED'}:{})}:{})},limits);if(kind==='confirm')confirmRecorded=true;return true;
  },
  ...(requiredTrial?{
   async recordTrialSetup(stage,identity,keyHash,limits){
    billingCheck(limits);
    need(provisioned&&!closed&&subscriptionId&&operationId&&identity?.subscriptionId===subscriptionId&&
      identity.operationId===operationId&&stage===trialSetupStage);
    const transitions={'trial-permit-dispatch':'trial-setup-dispatch','trial-setup-dispatch':'trial-setup-ack','trial-setup-ack':'complete'};
    const next=transitions[stage];
    need(next&&(stage==='trial-permit-dispatch'?
      typeof keyHash==='string'&&/^[a-f0-9]{64}$/.test(keyHash)&&keyHash!==digest(createKey):keyHash===undefined));
    trialSetupStage='writing';
    await save(stage,{phase:stage==='trial-setup-ack'?'ACKNOWLEDGED':'DISPATCHING',subscriptionId,operationId,
      ...(keyHash===undefined?{}:{keyHash})},limits);
    billingCheck(limits);need(!closed);
    if(stage==='trial-permit-dispatch')trialPermitKeyHash=keyHash;
    trialSetupStage=next;return true;
   },
  }:{}),
  ...(invoices?{
   async reconcile(beforeSend,limits){
    need(!closed && provisioned && confirmRecorded && !reconciliationClaimed);reconciliationClaimed=true;
    const key=randomUUID(),keyHash=digest(key);
    need(key!==confirmKey && key!==createKey);
    await financialReceipt('reconcile-dispatch',{phase:'DISPATCHING',subscriptionId,originalOperationId:operationId,keyHash},limits);
    const path='/api/v1/admin/subscriptions/'+subscriptionId+'/reconcile?site_id='+siteId;
    const value=await read(path,limits,{method:'POST',body:{},status:202,idempotencyKey:key,beforeSend});
    need(value?.subscription_id===subscriptionId && billingUuid(value.operation_id) && value.operation_id!==operationId &&
      value.status==='SUCCEEDED' && value.action_available===false && value.browser_confirm_allowed===false && value.next_action===null);
    const receipt=Object.freeze({operationId:value.operation_id,keyHash});
    await financialReceipt('reconcile-ack',{phase:'ACKNOWLEDGED',subscriptionId,...receipt},limits);return receipt;
   },
   async recordInvoiceDelivery(kind,ack,eventId,limits){
    need(!closed && ['paid','finalized'].includes(kind) && ['dispatch','ack'].includes(ack) && typeof eventId==='string' && /^evt_[A-Za-z0-9]{1,200}$/.test(eventId));
    await financialReceipt(kind+'-'+ack,{phase:ack==='dispatch'?'DISPATCHING':'ACKNOWLEDGED',subscriptionId,eventId},limits);return true;
   },
  }:{}),
  ...(challenge?{
   async recordChallenge(kind,identity,limits){
    need(confirmRecorded && ['challenge-dispatch','challenge-ack'].includes(kind) && identity.subscriptionId===subscriptionId && identity.operationId===operationId && identity.attemptId===attemptId);
    await financialReceipt(kind,{subscriptionId,operationId,attemptId,phase:kind==='challenge-dispatch'?'DISPATCHING':'ACKNOWLEDGED'},limits);return true;
   },
  }:{}),
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
