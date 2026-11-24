import {createHash} from 'node:crypto';
import test from 'node:test';import assert from 'node:assert/strict';import {readFile} from 'node:fs/promises';
// @ts-expect-error Native Node type erasure requires explicit source extension.
import {forwardBillingDispatch,runInitialBillingBrowser,runBillingCancellationBrowser,runBillingDeclineRetryBrowser,runBillingInitial3dsBrowser,runBillingInvoiceWebhooksBrowser} from './stripe-billing-acceptance.ts';
import type {BillingBinding,BillingPort} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {Route} from '@playwright/test';
const binding={endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:18081',receiver:'http://127.0.0.1:18082'},priceId:'11111111-2222-4333-8444-555555555555'} as BillingBinding;
function fixture(){const calls:string[]=[],headers={origin:binding.endpoints.demo,'content-type':'application/json',authorization:'Bearer '+'A'.repeat(43),'idempotency-key':'original-sdk-key'};let args:unknown[]=[];const request={url:()=>binding.endpoints.backend+'/api/v1/customer/subscriptions',method:()=> 'POST',redirectedFrom:()=>null,postData:()=>JSON.stringify({price_id:binding.priceId,recurring_consent:true}),allHeaders:async()=>headers};const route={request:()=>request,continue:async(...v:unknown[])=>{args=v;calls.push('continue');}} as unknown as Route;const port={beforeDispatch:async()=>{calls.push('persist');return true;}} as unknown as BillingPort;return {calls,headers,request,route,port,args:()=>args};}
test('original browser CREATE waits for durable key before unmodified continuation',async()=>{const f=fixture();await forwardBillingDispatch(f.route,f.port,binding,'Bearer '+'A'.repeat(43),'create',undefined,{deadline:Date.now()+1000});assert.deepEqual(f.calls,['persist','continue']);assert.deepEqual(f.args(),[]);});
for(const kind of ['foreign-origin','cookie','authority','body','persist-failure'])test('browser dispatch refuses '+kind,async()=>{const f=fixture();if(kind==='foreign-origin')f.headers.origin='https://other.test';if(kind==='cookie')Object.assign(f.headers,{cookie:'session=bad'});if(kind==='authority')f.headers.authorization='Bearer '+'B'.repeat(43);if(kind==='body')f.request.postData=()=>'{"price_id":"foreign","recurring_consent":true}';if(kind==='persist-failure')f.port.beforeDispatch=async()=>{throw Error('secret-sentinel');};await assert.rejects(()=>forwardBillingDispatch(f.route,f.port,binding,'Bearer '+'A'.repeat(43),'create',undefined,{deadline:Date.now()+1000}),e=>e instanceof Error&&!e.message.includes('sentinel'));assert.ok(!f.calls.includes('continue'));});
test('native Billing discovery is dedicated, bounded and explicitly excluded by default',async()=>{const config=await readFile(new URL('../../playwright.stripe-billing.config.ts',import.meta.url),'utf8'),normal=await readFile(new URL('../../playwright.config.ts',import.meta.url),'utf8');assert.match(config,/workers:1/);assert.match(config,/retries:0/);assert.match(config,/trace:'off'/);assert.doesNotMatch(config,/webServer/);assert.match(normal,/stripe-billing\.spec\.ts/);assert.match(normal,/stripe-billing-acceptance\.test\.ts/);assert.match(normal,/subscription-elements-browser\.test\.ts/);});
test('native runner reserves cleanup and scheduling time beyond the unchanged owner budget',async()=>{
 const [config,spec,owner]=await Promise.all([
  readFile(new URL('../../playwright.stripe-billing.config.ts',import.meta.url),'utf8'),
  readFile(new URL('../stripe-billing.spec.ts',import.meta.url),'utf8'),
  readFile(new URL('../../../../infrastructure/stripe-billing-acceptance.mjs',import.meta.url),'utf8')
 ]);
 const number=(source:string,pattern:RegExp)=>{const match=source.match(pattern);assert.ok(match,'expected explicit timeout contract');return Number(match[1]);};
 const ownerBudget=number(spec,/limits=\{deadline:Date\.now\(\)\+(\d+)/);
 const cleanupBudget=number(spec,/owner\.close\(\{deadline:Date\.now\(\)\+(\d+)/);
 const runnerBudget=number(config,/(?<!\w)timeout:(\d+)/);
 const globalBudget=number(config,/globalTimeout:(\d+)/);
 assert.equal(ownerBudget,45*60*1000);
 assert.equal(number(owner,/limits\.deadline-Date\.now\(\)>(\d+)/),ownerBudget);
 assert.equal(cleanupBudget,120*1000);
 assert.equal(number(owner,/const limits=\{deadline:Date\.now\(\)\+(\d+)/),cleanupBudget);
 assert.equal(runnerBudget,ownerBudget+cleanupBudget+60*1000);
 assert.equal(runnerBudget,48*60*1000);
 assert.equal(globalBudget,50*60*1000);
 assert.ok(globalBudget>runnerBudget);
});

// A single in-memory page sends SDK-shaped requests only in response to visible manager
// controls. It cannot make network calls. Responses retain their originating request.
import type {Browser, Request, Response} from '@playwright/test';
import type {BillingCancellationPort,BillingDeclineRetryPort,BillingInitial3dsPort,BillingInvoiceWebhooksPort} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import {browserCleanupConfirmed,browserDiagnostic} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';

function cancellationPage(fault = '', declineRetry = false, sca = false, invoiceCase = false) {
 const id = (n:number) => '11111111-2222-4333-8444-' + String(n).padStart(12, '0');
 const b = {...binding, siteKey:'demo-key', email:'user@example.test', siteId:id(1), productId:id(2)};
 const [sub, create, attempt, schedule, withdraw] = [id(3), id(4), id(5), id(6), id(7)];
 const boundary = new Date(Date.now() + 86400000).toISOString();
 const token = 'A'.repeat(43);
 const base = b.endpoints.backend + '/api/v1/customer/subscriptions';
 const otp = b.endpoints.backend + '/api/v1/public/customer-auth/email-otp/challenges';
 const cancel = base + '/' + sub + '/cancellation';
 const select = base + '/' + sub + '/operations/' + create + '/confirmation-session';
 const confirm = base + '/' + sub + '/operations/' + create + '/confirmation-attempts/' + attempt + '/confirm';
 const trace:string[] = [], sent:string[] = [], numbers:string[]=[];
 const successor=id(21),retry=confirm.replace(/confirm$/,'retry'),nextConfirm=confirm.replace(attempt!,successor);
 const sessionFor=(id:string)=>({protocol:'SERVER_CONFIRM_V2',provider:'stripe',flow:'PAYMENT',attempt_id:id,publishable_key:'pk_test_fixture',
  capability:'bca2_'+(id===attempt?'C':'D').repeat(43),client_secret:'pi_original_secret_fixture',expires_at:boundary});
 const receiptFor=(id:string,state:string,retry_allowed:boolean,successor_attempt_id:string|null)=>({protocol:'SERVER_CONFIRM_V2',attempt_id:id,state,retry_allowed,successor_attempt_id,expires_at:boundary});
 let payCount=0,retryClicked=false,nonce=30,staticProbed=false;
 let requestFailed=(request:Request)=>{void request;};
 const presentation:string[]=[];
 async function staticPresentation(){
  const document=fault!=='static-image' && fault!=='early-static-image';
  const nested={parentFrame:()=>stripeFrame,page:()=>page,isDetached:()=>false,url:()=>''};
  const request={url:()=>document?'https://private.example/presentation?frame=1':'https://private.example/image.png',method:()=>'GET',
   frame:()=>document?nested:stripeFrame,resourceType:()=>document?'document':'image',isNavigationRequest:()=>document,
   postData:()=>null,redirectedFrom:()=>null,allHeaders:async()=>({}),failure:()=>({errorText:'net::ERR_BLOCKED_BY_CLIENT'})} as unknown as Request;
  await handler({request:()=>request,abort:async(reason:string)=>{
   presentation.push('abort '+reason);if(reason==='blockedbyclient')requestFailed(fault==='static-foreign-event'?{...request} as Request:request);
  },fulfill:async(reply:{status:number})=>{presentation.push('fulfilled '+reply.status);}} as unknown as Route);
 }
 const frame = {name:()=>'',page:()=>page,isDetached:()=>false,url:()=>documentUrl};
 const stripeFrame = {name:()=>'stripe',page:()=>page,isDetached:()=>verified&&fault==='detached',url:()=>verified&&fault==='wrong-parent'?'https://unowned.invalid/':'https://js.stripe.com/v3/frame.html',parentFrame:()=>frame};
 const providerSent:string[]=[];
 let releaseFirstToken:undefined|(()=>void),delayedToken:Promise<void>|undefined;
 const delayedHeaders=Promise.withResolvers<void>();
 let payClicked=false, interceptsProvider=false;
 let documentUrl = '', closed = false, verified = false, displayed:'initial'|'schedule'|'withdraw' = 'initial';
 let ack:'schedule'|'withdraw'|undefined, refreshed = false;
 let releaseBody:undefined|(()=>void), duplicateConfirm:undefined|(()=>Promise<void>);
 let releaseDispatch:undefined|(()=>void);
 let handler:(route:Route)=>Promise<void>;
 let socketHandler:undefined|((socket:{close:()=>void})=>void);
 const navigationListeners:((frame:unknown)=>void)[]=[];
 const navigate=(frame:unknown)=>{for(const listener of navigationListeners)listener(frame);};
 const waiters = new Set<{predicate:(r:Response)=>boolean;resolve:(r:Response)=>void;reject:(e:Error)=>void}>();
 function publish(request:Request, status:number, json:object) {
  const earlyStatic=declineRetry && fault.startsWith('early-static') && request.url()===select;
  const bodyAvailable=earlyStatic?Promise.withResolvers<void>():undefined;
  const response = {request:()=>request,url:()=>request.url(),status:()=>status,fromServiceWorker:()=>false,body:async()=>{
   await bodyAvailable?.promise;
   return Buffer.from(JSON.stringify(json));
  }} as Response;
  for(const w of waiters) if(w.predicate(response)){waiters.delete(w);w.resolve(response);}
  if(bodyAvailable){
   // The page got its session response. The observer's independent body read
   // can still be pending when the browser requests the first Stripe asset.
   void staticPresentation().catch(()=>{});
   setImmediate(()=>{
    if(fault==='early-static-navigation')navigate(frame);
    bodyAvailable.resolve();
   });
  }
  return response;
 }
 async function provider(url:string,owner:unknown=stripeFrame,delay=false,method='POST'){
  if(!interceptsProvider){providerSent.push(url);return;}
  const tokenCall=url.endsWith('/confirmation_tokens');
  const badSupport=!tokenCall;
  let tokenResponse:Response|null=null;
  const reply=observeNetwork(owner,url,method);
  const request={url:()=>url,method:()=>method,frame:()=>owner,isNavigationRequest:()=>false,redirectedFrom:()=>badSupport&&fault==='telemetry-redirect'?{}:null,
   existingResponse:()=>tokenResponse,
   allHeaders:async()=>{
    if(delay){delayedHeaders.resolve();await new Promise<void>(r=>{releaseFirstToken=r;});}
    return badSupport&&fault==='telemetry-auth'?{authorization:'Bearer '+token}:badSupport&&fault==='telemetry-cookie'?{cookie:'provider=demo'}:{origin:'https://js.stripe.com'};}} as unknown as Request;
  await handler({request:()=>request,fallback:async()=>{
   providerSent.push(url);await reply(tokenCall?'token':new URL(url).hostname,200);
   if(tokenCall&&fault!=='missing-token-response')tokenResponse={
    request:()=>fault==='wrong-token-request'?{...request}:request,url:()=>url,
    status:()=>fault==='token-failure'?402:200,fromServiceWorker:()=>fault==='token-worker',
    body:async()=>{
     if(fault==='pending-token-body')await new Promise<void>(resolve=>{setTimeout(resolve,5);});
     if(fault==='concurrent-confirm')await new Promise<void>(resolve=>{releaseBody=resolve;void duplicateConfirm?.().catch(()=>{}).finally(resolve);});
     return Buffer.from(JSON.stringify({object:fault==='token-type'?'payment_method':'confirmation_token',id:declineRetry&&payCount===2&&fault!=='reused-token'?'ctoken_second':'ctoken_demo',livemode:fault==='live-token'}));
    }
   } as Response;
  },continue:async()=>{providerSent.push(url);},abort:async()=>{}} as unknown as Route);
  if(closed)throw new Error('closed');
 }
 const issuerUrl='https://hooks.stripe.com/test-fixture?run=one',actionUrl='https://hooks.stripe.com/test-fixture/decision';
 let issuerDocument='about:blank',trustedIssuer=false;
 const ancestor=(name:string,href:string,parent:unknown)=>({page:()=>page,isDetached:()=>false,url:()=>href,name:()=>name,parentFrame:()=>parent});
 const outer=ancestor('__privateStripeFrame7','https://js.stripe.com/v3/three-ds-2-challenge-fixture.html',frame);
 const inner=ancestor('stripe-challenge-frame','https://js.stripe.com/v3/fixture-inner.html',outer);
 const issuer={
  ...ancestor('acsFrame','',inner),url:()=>issuerDocument,waitForURL:async(fn:(u:URL)=>boolean)=>assert.ok(fn(new URL(issuerDocument))),
  getByRole:(role:string,opts:{name:string;exact:boolean})=>{
   assert.equal(role,'button');assert.deepEqual(opts,{name:'Complete',exact:true});
   return {count:async()=>1,waitFor:async()=>{},elementHandle:async()=>({
    ownerFrame:async()=>issuer,isVisible:async()=>true,isEnabled:async()=>true,
    evaluate:async()=>({action:actionUrl,bodyHash:createHash('sha256').update('decision=complete').digest('hex')}),evaluateHandle:async()=>({evaluate:async()=>trustedIssuer}),
    click:async()=>{trace.push('issuer Complete');trustedIssuer=fault!=='untrusted-issuer';
     if(fault==='premature-sdk-complete'){await wire('https://api.stripe.com/v1/3ds2/challenge_complete',stripeFrame,'source=src_fixture',{});return;}
     await wire(actionUrl,issuer,fault==='substituted-issuer-form'?'decision=other':'decision=complete',{},true);},
   })};
  },
 };
 const devtools=new Map<unknown,Map<string,(value:unknown)=>void>>();
 let networkSequence=0;
 const armedPatterns=new Map<unknown,string[]>(),abortedPauses=new Set<string>();
 function observeNetwork(target:unknown,url:string,method:string){
  const rec=devtools.get(target),networkId='net'+(++networkSequence),request={url,method};
  rec?.get('Network.requestWillBeSent')?.({requestId:networkId,frameId:'frame',request});
  return async(label:string,status:number)=>{
   if(!sca&&!invoiceCase)return;
   const redirect=fault==='redirect-'+label;
   const covered=armedPatterns.get(target)?.some(pattern=>{
    const escaped=pattern.split('*').map(part=>part.replace(/[.*+?^${}()|[\]\\]/g,'\\$&'));
    return new RegExp('^'+escaped.join('.*')+'$').test(url);
   });
   if(covered)await rec?.get('Fetch.requestPaused')?.({requestId:networkId,networkId,frameId:'frame',request,responseStatusCode:redirect?307:status});
   if(abortedPauses.has(networkId))throw Error('stopped response');
   if(redirect)trace.push('followed redirect '+label);
  };
 }
 async function wire(url:string,target:unknown,data:string|null,json:object,navigation=false){
  const reply=observeNetwork(target,url,data===null?'GET':'POST');
  let existing:Response|undefined;
  const headers=navigation?{'content-type':'application/x-www-form-urlencoded',origin:issuerDocument===issuerUrl?'https://hooks.stripe.com':'https://js.stripe.com'}:
   {authorization:'Bearer pk_test_fixture',origin:'https://js.stripe.com'};
  const request={url:()=>url,method:()=>data===null?'GET':'POST',frame:()=>target,isNavigationRequest:()=>navigation,resourceType:()=>navigation?'document':'xhr',
   redirectedFrom:()=>null,postData:()=>data,allHeaders:async()=>headers,existingResponse:()=>existing} as unknown as Request;
  await handler({request:()=>request,continue:async(...args:unknown[])=>{
   assert.deepEqual(args,[]);providerSent.push(url);
   await reply('issuer',200);
   existing=publish(request,200,json);if(navigation){issuerDocument=url;navigate(issuer);}
  },abort:async()=>{}} as unknown as Route);
  if(closed)throw Error('closed');
 }
 async function challengeTraffic(){
  const secret=sessionFor(attempt!).client_secret,source='src_fixture',server=id(90),acs=id(91);
  const target='https://api.stripe.com/v1/payment_intents/'+(fault==='foreign-next-pi'?'pi_other':'pi_original')+'?client_secret='+secret;
  await wire(target,stripeFrame,null,{object:'payment_intent',livemode:false,id:'pi_original',client_secret:secret,status:'requires_action',
   next_action:{type:'use_stripe_sdk',use_stripe_sdk:{type:'stripe_3ds2_fingerprint',three_d_secure_2_source:source}}});
  await wire('https://api.stripe.com/v1/3ds2/authenticate',stripeFrame,'source='+(fault==='foreign-next-source'?'src_other':source),
   {state:'challenge_required',ares:{messageType:'ARes',transStatus:'C',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs,acsURL:issuerUrl}});
  const creq=Buffer.from(JSON.stringify({messageType:'CReq',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:fault==='wrong-issuer-transaction'?server:acs})).toString('base64url');
  await wire(fault==='foreign-issuer-path'?issuerUrl+'/foreign':issuerUrl,issuer,'creq='+creq,{},true);
 }
 async function send(url:string, value:object, result:object, status=202, method='POST') {
  const schedulePost = url===cancel, withdrawPost=url===cancel+'/withdraw';
  const key = declineRetry||sca||invoiceCase?id(nonce++):schedulePost?id(9):withdrawPost?(fault==='same-key'?id(9):id(10)):'initial-key';
  const headers = {'idempotency-key':key,authorization:'Bearer '+token,origin:b.endpoints.demo,'content-type':'application/json'};
  if(schedulePost && fault==='origin')headers.origin='https://elsewhere.invalid';
  if(schedulePost && fault==='cookie')Object.assign(headers,{cookie:'session=demo'});
  if(schedulePost && fault==='token')headers.authorization='Bearer '+'B'.repeat(43);
  let existing:Response|undefined;
  const request = {
   existingResponse:()=>existing,url:()=>schedulePost && fault==='foreign-endpoint'?url.replace(sub!,id(19)):
    schedulePost && fault==='foreign-host'?url.replace(b.endpoints.backend,'https://unowned.invalid'):url,
   method:()=>method,
   frame:()=>schedulePost && fault==='wrong-frame'?{}:frame,isNavigationRequest:()=>false,
   redirectedFrom:()=>schedulePost&&fault==='redirect'?{}:null,allHeaders:async()=>headers,
   postData:()=>withdrawPost && fault==='malformed-withdraw'?'{"mode":"PERIOD_END"}':
    JSON.stringify(schedulePost&&fault==='immediate'?{mode:'IMMEDIATE'}:value)
  } as unknown as Request;
  const reply=observeNetwork(frame,request.url(),method);
  const label=url===otp?'otp':url.endsWith('/verify')?'verify':url===base?'create':url===select?'selection':url===confirm?'confirm':'read';
  const route = {request:()=>request,continue:async(...args:unknown[])=>{
   assert.deepEqual(args,[]);sent.push(request.url());await reply(label,status);
   existing=publish((schedulePost&&fault==='foreign-request'||url===retry&&fault==='wrong-retry-response')?{...request} as Request:request,status,result);
  },abort:async()=>{}} as unknown as Route;
  await handler(route);
  if(closed)throw new Error('closed');
 }
 const subscription = {
  subscriptionId:sub!, siteId:b.siteId, customerId:id(11), productId:b.productId, billingPrice:{priceId:b.priceId},
  billingStatus:'ACTIVE', state:'ACTIVE', revision:4, paidThrough:boundary,period:{start:'2026-01-01T00:00:00Z',end:boundary},access:{allowed:true,reason:'PAID'},cancellation:null as {effectiveAt:string}|null, pendingOperation:null
 };
 const manager = {
  state:{phase:'ready',authorityRevision:1,subscription,operation:null},
  evaluateHandle:async()=>({evaluate:async(fn:(check:()=>boolean)=>boolean)=>fn(()=>manager.state.authorityRevision===1)}),
  evaluate:async(fn:(m:unknown)=>unknown)=>fn(manager),
  frameLocator:()=>({locator:(selector:string)=>({fill:async(v:string)=>{if(selector.includes('number'))numbers.push(v);},isVisible:async()=>false})}),
  getByRole:(_role:string,opts:{name:string})=>({
   check:async()=>{trace.push(opts.name);},waitFor:async()=>{},
   evaluate:async()=>true,evaluateHandle:async()=>{const n=payCount;return {evaluate:async(fn:(get:()=>boolean)=>boolean)=>fn(()=>opts.name==='Retry payment'?retryClicked:opts.name==='Create subscription'?trace.includes('Create subscription'):declineRetry?payCount>n:payClicked)};},
   click:async()=>{
    const name=opts.name;
    trace.push(name);
    if(name==='Request code'&&fault==='websocket'){if(!socketHandler)throw new Error('socket escaped');socketHandler({close:()=>{providerSent.push('closed websocket');}});}
    if(name==='Request code'&&['early-provider-confirm','early-token','unknown-host'].includes(fault))await provider(fault==='unknown-host'?'https://unknown.invalid/anything':'https://api.stripe.com/v1/'+(fault==='early-token'?'confirmation_tokens':'payment_intents/pi_test/confirm'));
    if(name==='Request code') await send(otp, {site_key:b.siteKey,email:b.email,intent:'AUTHENTICATE'}, {challenge_id:id(8)});
    if(name==='Verify code'){
     await send(otp+'/'+id(8)+'/verify',{}, {customer:{id:id(11),status:'ACTIVE'},site_id:b.siteId,scopes:['SUBSCRIPTION_READ','SUBSCRIPTION_MANAGE'],session_token:token},200);
     await send(base+'?limit=100',{}, {items:[],next_cursor:null},200,'GET');
    }
    if(name==='Create subscription'){
     await send(base,{price_id:b.priceId,recurring_consent:true},{subscription_id:sub,operation_id:create,status:'PROCESSING'});
     const session=sessionFor(attempt!);
     if(fault==='early-static-invalid-session')session.capability='invalid';
     void send(select,{action_token:'bga1_'+'G'.repeat(43)},session,fault.startsWith('session-http')?409:200).catch(()=>{});
    }
    if(name==='Pay'){
     if(fault==='before-actual-click')await provider('https://api.stripe.com/v1/confirmation_tokens');
     payClicked=true;payCount++;
     if(fault==='late-first-token'){
      if(payCount===1){delayedToken=provider('https://api.stripe.com/v1/confirmation_tokens',stripeFrame,true);void delayedToken.catch(()=>{});await delayedHeaders.promise;}
      else{releaseFirstToken!();await delayedToken;}
     }
     if(fault!=='absent-token'&&fault!=='initial-only')await provider('https://api.stripe.com/v1/confirmation_tokens');
     if(fault==='token-replay')await provider('https://api.stripe.com/v1/confirmation_tokens');
     const submit=()=>send(confirm,{capability:'bca2_'+'C'.repeat(43),confirmation_token:fault==='substituted-token'?'ctoken_other':'ctoken_demo'},{protocol:'SERVER_CONFIRM_V2',attempt_id:attempt,state:'OBSERVE',next_action_allowed:false,retry_allowed:false,successor_attempt_id:null},200);
     if(sca){
      await send(confirm,{capability:sessionFor(attempt!).capability,confirmation_token:'ctoken_demo'},
       {...receiptFor(attempt!,'REQUIRES_ACTION',false,null),next_action_allowed:fault!=='replayed-next-ack'},200);
      void challengeTraffic().catch(()=>{});return;
     }
     if(declineRetry){
      const first=payCount===1;
      await send(first?confirm:nextConfirm,{capability:sessionFor(first?attempt!:successor).capability,confirmation_token:first||fault==='reused-token'?'ctoken_demo':'ctoken_second'},
       {...receiptFor(first?attempt!:successor,first?(fault==='unknown-first'?'UNKNOWN':'DECLINED'):'OBSERVE',first,null),next_action_allowed:false},200);
      if(first&&fault==='early-retry')await send(retry,{},receiptFor(attempt!,'DECLINED',false,successor),200);
      return;
     }
     duplicateConfirm=submit;
     await submit();
     if(fault==='early')await send(cancel,{mode:'PERIOD_END'},{});
    }
    if(name==='Retry payment'){
     retryClicked=true;
     await send(retry,{},receiptFor(attempt!,'DECLINED',false,fault==='same-successor'?attempt!:successor),200);
     if(fault==='repeat-retry')await send(retry,{},receiptFor(attempt!,'DECLINED',false,successor),200);
     void send(nextConfirm.replace(/confirm$/,'client-session'),{}, {...sessionFor(successor),...(fault==='different-pi'?{client_secret:'pi_foreign_secret_fixture'}:{})},200).catch(()=>{});
    }
    if(name==='Refresh status' && ack){
     if(fault==='lost-authority')manager.state.authorityRevision++;
     if(fault==='unknown-state')manager.state.phase='unknown';
     displayed=ack; refreshed=true;subscription.revision++;
     subscription.state=ack==='schedule'?'CANCEL_AT_PERIOD_END':'ACTIVE';
     subscription.cancellation=ack==='schedule'?{effectiveAt:fault==='wrong-boundary'?'2025-01-01T00:00:00Z':boundary}:null;
     ack=undefined;
    }
    if(name==='Confirm cancellation'||name==='Withdraw cancellation'){
     const kind=name==='Confirm cancellation'?'schedule':'withdraw';
     assert.ok(verified);if(kind==='withdraw')assert.equal(displayed,'schedule');
     if(kind==='schedule'&&fault==='navigate')navigate(frame);
     if(kind==='schedule'&&['support','telemetry-main','wrong-parent','detached','telemetry-auth','telemetry-redirect','telemetry-cookie','late-provider-confirm','late-token','merchant-api'].includes(fault)){
      const providerUrl=fault==='late-provider-confirm'?'https://api.stripe.com/v1/payment_intents/pi_test/confirm':
       fault==='late-token'?'https://api.stripe.com/v1/confirmation_tokens':fault==='merchant-api'?'https://merchant-ui-api.stripe.com/v1/subscriptions':'https://r.stripe.com/';
      await provider(providerUrl,fault==='telemetry-main'?frame:stripeFrame);
     }
     const path=kind==='schedule'?cancel:cancel+'/withdraw';
     const payload=kind==='schedule'?{mode:'PERIOD_END'}:{};
     const receipt={subscription_id:fault==='foreign-subscription'?id(20):sub,operation_id:kind==='withdraw'&&fault==='reused-operation'?schedule:kind==='schedule'?schedule:withdraw,status:fault==='unknown-ack'?'UNKNOWN':'PROCESSING',next_action:null,action_available:false,browser_confirm_allowed:false};
     await send(path,payload,receipt);ack=kind;refreshed=false;
     if(fault==='duplicate'||fault==='duplicate-withdraw'&&kind==='withdraw')await send(path,payload,receipt);
    }
   }
  })
 };
 const page={
  frames:()=>[frame,stripeFrame,issuer],context:()=>({newCDPSession:async(surface:unknown)=>{
   const f=(surface===page?frame:surface) as {url:()=>string;name:()=>string};const listeners=new Map<string,(value:unknown)=>void>();devtools.set(f,listeners);
   return {on:(event:string,fn:(v:unknown)=>void)=>listeners.set(event,fn),send:async(command:string,args:{requestId?:string;patterns?:{urlPattern:string}[]})=>{
    if(command==='Fetch.enable')armedPatterns.set(f,args.patterns!.map(p=>p.urlPattern));
    if(command==='Fetch.failRequest')abortedPauses.add(args.requestId!);
    if(command==='Page.getFrameTree')return {frameTree:{frame:{id:'frame',url:f.url(),name:f.name()}}};
   }};
  }}),
  mainFrame:()=>frame,url:()=>documentUrl,waitForFunction:async()=>{
   if(sca||invoiceCase){
    await provider('https://api.stripe.com/v1/elements/sessions',stripeFrame,false,'GET');
    await provider('https://r.stripe.com/b',stripeFrame);
    await provider('https://merchant-ui-api.stripe.com/telemetry',stripeFrame);
   }
   if(declineRetry&&!staticProbed&&fault.startsWith('static-')){
    staticProbed=true;void staticPresentation().catch(()=>{});
    if(fault==='static-duplicate')void staticPresentation().catch(()=>{});
   }
  },
  on:(event:string,fn:typeof navigate)=>{if(event==='framenavigated')navigationListeners.push(fn);if(event==='requestfailed')requestFailed=fn;},
  goto:async(url:string)=>{
   if(sca||invoiceCase){
    documentUrl='about:blank';
    const method='GET',reply=observeNetwork(frame,url,method);
    const request={url:()=>url,method:()=>method,frame:()=>frame,redirectedFrom:()=>null};
    await handler({request:()=>request,fallback:async()=>{await reply('demo',200);},abort:async()=>{}} as unknown as Route);
    if(closed)throw Error('closed');
   }
   documentUrl=url;
   if(sca||invoiceCase)await provider('https://js.stripe.com/v3/',frame,false,'GET');
  },locator:()=>({first:()=>manager}),
  waitForResponse:(predicate:(r:Response)=>boolean)=>new Promise<Response>((resolve,reject)=>{if(closed)reject(new Error('closed'));else waiters.add({predicate,resolve,reject});})
 };
 const context={
  newPage:async()=>page,on:()=>{},routeWebSocket:async(_pattern:unknown,fn:typeof socketHandler)=>{socketHandler=fn;},route:async(pattern:unknown,fn:typeof handler)=>{interceptsProvider=pattern==='**/*';handler=fn;},
  close:async()=>{closed=true;releaseBody?.();releaseDispatch?.();for(const w of waiters)w.reject(new Error('closed'));waiters.clear();
   if(fault==='session-http-close-fails')throw Error('private close reason');
  }
 };
 const port={
  resources:()=>b,beforeDispatch:async(kind:string)=>{
   if(kind==='confirm'&&fault==='duplicate-during-persist')await new Promise<void>(resolve=>{releaseDispatch=resolve;void duplicateConfirm?.().catch(()=>{}).finally(resolve);});
   return true;
  },releaseOriginalConfirm:()=>{trace.push('release original confirm');if(fault==='invalid ingress')throw Error('tainted');return true;},
  acknowledge:async(kind:string)=>{if(invoiceCase&&kind==='confirm'){trace.push('completed invoice proof');if(fault==='invalid evidence')throw Error();}if(declineRetry&&kind==='confirm'){trace.push('decline proof');return fault!=='no-decline-proof';}return true;},acknowledgeCustomer:async()=>true,
  beforeChallenge:async()=>{trace.push('persist challenge');return true;},acknowledgeChallenge:async()=>{trace.push('issuer witnessed');return true;},
  beforeRetry:async()=>{trace.push('persist retry');if(fault==='retired-retry')manager.state.authorityRevision++;return true;},acknowledgeRetry:async()=>{trace.push('ack retry');return true;},
  beforeSuccessorConfirm:async()=>{trace.push('persist successor');if(fault==='retired-successor')manager.state.authorityRevision++;return true;},acknowledgeSuccessor:async()=>{trace.push('recovered proof');return true;},
  withOtpCode:async(callback:(code:string)=>Promise<true>)=>callback('123456'),
  verifyInitial:async()=>{trace.push('verify initial');verified=true;return fault!=='initial-proof';},
  beforeCancellation:async(kind:string,key:string)=>{trace.push('persist '+kind+' '+key);return fault!=='persist';},
  verifyCancellation:async(kind:string,operation:string)=>{trace.push('verify '+kind+' '+operation);return fault!=='proof';}
 } as BillingCancellationPort&BillingDeclineRetryPort&BillingInitial3dsPort&BillingInvoiceWebhooksPort;
 return {browser:{newContext:async()=>context} as unknown as Browser,port,trace,sent,providerSent,cancel,id,numbers,presentation,retire:()=>{manager.state.authorityRevision++;},reloadIssuer:()=>navigate(issuer),displayed:()=>displayed,refreshed:()=>refreshed,closed:()=>closed};
}
test('same-page manager schedules and withdraws once after the initial paid proof',async()=>{
 const f=cancellationPage();
 await runBillingCancellationBrowser(f.browser,f.port,{deadline:Date.now()+3000});
 assert.deepEqual(f.trace.filter(e=>e.startsWith('verify ')||e.startsWith('persist ')||['Cancel at period end','I confirm cancellation','Confirm cancellation','Withdraw cancellation'].includes(e)),[
  'verify initial','Cancel at period end','I confirm cancellation','Confirm cancellation','persist schedule '+f.id(9),'verify schedule '+f.id(6),'Withdraw cancellation','persist withdraw '+f.id(10),'verify withdraw '+f.id(7)
 ]);
 assert.deepEqual(f.sent.filter(url=>url.includes('/cancellation')),[f.cancel,f.cancel+'/withdraw']);
 assert.equal(f.displayed(),'withdraw');assert.ok(f.refreshed());assert.ok(f.closed());
});
for(const [fault,expected] of Object.entries({
 'initial-proof':0,early:0,origin:0,token:0,cookie:0,redirect:0,'foreign-host':0,'wrong-frame':0,'foreign-endpoint':0,immediate:0,navigate:0,persist:0,
 duplicate:1,'duplicate-withdraw':2,'malformed-withdraw':1,'foreign-request':1,'foreign-subscription':1,'unknown-ack':1,'same-key':1,'unknown-state':1,'wrong-boundary':1,'lost-authority':1,proof:1,'reused-operation':2
}))test('cancellation stops at '+fault,async()=>{
 const f=cancellationPage(fault);
 await assert.rejects(()=>runBillingCancellationBrowser(f.browser,f.port,{deadline:Date.now()+1500}),/^Error: billing_BROWSER$/);
 assert.equal(f.sent.filter(url=>url.includes('/cancellation')).length,expected);
 if(['foreign-request','foreign-subscription','unknown-ack'].includes(fault))assert.ok(!f.trace.some(v=>v.startsWith('verify schedule')));
 if(fault==='reused-operation')assert.ok(!f.trace.some(v=>v.startsWith('verify withdraw')));
 assert.ok(f.closed());
});
test('initial-only browser never enables cancellation even with an extended port',async()=>{
 const f=cancellationPage('initial-only');
 await runInitialBillingBrowser(f.browser,f.port,{deadline:Date.now()+1000});
 assert.deepEqual(f.trace.filter(e=>e.startsWith('verify ')||e.startsWith('persist ')),[]);
 const early=cancellationPage('early');
 await assert.rejects(()=>runInitialBillingBrowser(early.browser,early.port,{deadline:Date.now()+1000}),/^Error: billing_BROWSER$/);
 assert.equal(early.sent.includes(early.cancel),false);
});

test('original token and trailing uncredentialed provider-frame support can complete the journey',async()=>{
 const f=cancellationPage('support');
 await runBillingCancellationBrowser(f.browser,f.port,{deadline:Date.now()+5000});
 assert.deepEqual(f.providerSent,['https://api.stripe.com/v1/confirmation_tokens','https://r.stripe.com/']);
 assert.equal(f.displayed(),'withdraw');
});
for(const fault of ['early-provider-confirm','early-token','late-provider-confirm','late-token','unknown-host','telemetry-main','wrong-parent','detached','telemetry-auth','telemetry-redirect','telemetry-cookie','merchant-api','token-replay','before-actual-click','websocket'])test('refuses provider traffic at '+fault,async()=>{
 const f=cancellationPage(fault);
 await assert.rejects(()=>runBillingCancellationBrowser(f.browser,f.port,{deadline:Date.now()+5000}),/^Error: billing_BROWSER$/);
 const beforePay=['early-provider-confirm','early-token','unknown-host','before-actual-click','websocket'].includes(fault);
 assert.deepEqual(f.providerSent,beforePay?(fault==='websocket'?['closed websocket']:[]):['https://api.stripe.com/v1/confirmation_tokens']);
 assert.equal(f.sent.includes(f.cancel),false);
});

for(const fault of ['absent-token','missing-token-response','substituted-token','wrong-token-request','token-failure','token-worker','token-type','live-token','concurrent-confirm','duplicate-during-persist'])test('refuses backend confirm at '+fault,async()=>{
 const f=cancellationPage(fault);
 await assert.rejects(()=>runBillingCancellationBrowser(f.browser,f.port,{deadline:Date.now()+2000}),/^Error: billing_BROWSER$/);
 assert.equal(f.sent.some(url=>url.endsWith('/confirm')),false);
 assert.equal(f.trace.includes('verify initial'),false);
});
test('matching token verification can finish reading the response after backend interception',async()=>{
 const f=cancellationPage('pending-token-body');
 await runBillingCancellationBrowser(f.browser,f.port,{deadline:Date.now()+3000});
 assert.equal(f.sent.filter(url=>url.endsWith('/confirm')).length,1);
});

test('the same manager explicitly retries a proven decline before paying with fresh details',async()=>{
 const f=cancellationPage('',true);
 await runBillingDeclineRetryBrowser(f.browser,f.port,{deadline:Date.now()+3000});
 assert.deepEqual(f.trace.filter(x=>['Pay','decline proof','Retry payment','persist retry','ack retry','persist successor','recovered proof'].includes(x)),
  ['Pay','decline proof','Retry payment','persist retry','ack retry','Pay','persist successor','recovered proof']);
 assert.deepEqual(f.numbers,['4000000000000002','4242424242424242']);
 assert.deepEqual(f.providerSent,['https://api.stripe.com/v1/confirmation_tokens','https://api.stripe.com/v1/confirmation_tokens']);
 assert.equal(f.sent.filter(u=>u.endsWith('/confirm')).length,2);
 assert.equal(f.sent.filter(u=>u.endsWith('/retry')).length,1);
 assert.ok(f.closed());
});
test('decline retry checks a staged Stripe document after validating the original session',async()=>{
 const f=cancellationPage('early-static',true);
 await runBillingDeclineRetryBrowser(f.browser,f.port,{deadline:Date.now()+3000},origin=>origin==='https://private.example');
 assert.deepEqual(f.presentation,['abort blockedbyclient']);
 assert.equal(f.sent.filter(url=>url.endsWith('/confirm')).length,2);
 assert.equal(f.sent.filter(url=>url.endsWith('/retry')).length,1);
});
test('an invalid original session cannot release a staged Stripe document',async()=>{
 const f=cancellationPage('early-static-invalid-session',true);
 await assert.rejects(runBillingDeclineRetryBrowser(f.browser,f.port,{deadline:Date.now()+3000},origin=>origin==='https://private.example'));
 assert.deepEqual(f.presentation,['abort failed']);
 assert.ok(!f.sent.some(url=>url.endsWith('/confirm')||url.endsWith('/retry')));
});
test('a pinned subresource cannot start transport before the original session is validated',async t=>{
 const f=cancellationPage('early-static-image',true);
 const fetch=t.mock.method(globalThis,'fetch',async()=>assert.fail('early pinned asset must not be fetched'));
 await assert.rejects(runBillingDeclineRetryBrowser(f.browser,f.port,{deadline:Date.now()+3000},origin=>origin==='https://private.example'));
 assert.equal(fetch.mock.callCount(),0);
 assert.deepEqual(f.presentation,['abort failed']);
 assert.ok(!f.sent.some(url=>url.endsWith('/confirm')||url.endsWith('/retry')));
});
for(const [fault,expected,closed] of [
 ['session-http','BROWSER_INITIAL_SESSION_HTTP',true],
 ['early-static-invalid-session','BROWSER_INITIAL_SESSION_WIRE',true],
 ['session-http-close-fails','BROWSER_INITIAL_SESSION_HTTP',false],
 ['early-static-navigation','BROWSER_INVALIDATED',true],
] as const)test('decline retry reports only the original safe session boundary for '+fault,async()=>{
 const f=cancellationPage(fault,true);
 await assert.rejects(runBillingDeclineRetryBrowser(f.browser,f.port,{deadline:Date.now()+3000},origin=>origin==='https://private.example'),(error:unknown)=>{
  assert.equal(browserDiagnostic(error),expected);
  assert.equal(browserCleanupConfirmed(error),closed);
  assert.equal(String(error),'Error: billing_BROWSER');
  return true;
 });
 assert.ok(!f.sent.some(url=>url.endsWith('/confirm')||url.endsWith('/retry')));
});
for(const fault of ['unknown-first','early-retry','no-decline-proof','repeat-retry','wrong-retry-response','same-successor','different-pi','reused-token','retired-retry','retired-successor']){
 test('decline retry refuses '+fault+' before a second confirm',async()=>{
  const f=cancellationPage(fault,true);
  await assert.rejects(runBillingDeclineRetryBrowser(f.browser,f.port,{deadline:Date.now()+1500}),/^Error: billing_BROWSER$/);
  assert.equal(f.sent.filter(u=>u.endsWith('/confirm')).length,1);
  assert.equal(f.sent.filter(u=>u.endsWith('/retry')).length,['unknown-first','early-retry','no-decline-proof','retired-retry'].includes(fault)?0:1);
  assert.ok(!f.trace.includes('recovered proof'));assert.ok(f.closed());
 });
}

for(const mode of ['static-block','static-image','static-duplicate','static-foreign-event']){
 test('decline retry applies the reviewed presentation policy: '+mode,async t=>{
  const f=cancellationPage(mode,true);
  const asset=t.mock.method(globalThis,'fetch',async(_url:unknown,options:RequestInit)=>{
   assert.equal(mode,'static-image');assert.equal(options.redirect,'error');assert.equal(options.credentials,'omit');
   return new globalThis.Response(new Uint8Array([1]),{status:200,headers:{'content-type':'image/png'}});
  });
  const accepted=mode==='static-block'||mode==='static-image';
  const result=runBillingDeclineRetryBrowser(f.browser,f.port,{deadline:Date.now()+(accepted?1500:300)},o=>o==='https://private.example');
  if(accepted)assert.equal(await result,true);else await assert.rejects(result);
  assert.equal(f.sent.filter(u=>u.endsWith('/confirm')).length,accepted?2:0);
  assert.equal(asset.mock.callCount(),mode==='static-image'?1:0);
  assert.ok(f.presentation.includes(mode==='static-image'?'fulfilled 200':'abort blockedbyclient'));
 });
}

test('a token intercepted on the first Pay cannot use the successor gesture after its headers arrive',async()=>{
 const f=cancellationPage('late-first-token',true);
 await assert.rejects(runBillingDeclineRetryBrowser(f.browser,f.port,{deadline:Date.now()+1500}),/^Error: billing_BROWSER$/);
 assert.equal(f.trace.filter(x=>x==='Pay').length,2);
 assert.deepEqual(f.providerSent,['https://api.stripe.com/v1/confirmation_tokens']);
 assert.equal(f.sent.filter(url=>url.endsWith('/confirm')).length,1);
});

for(const fault of ['none','replayed-next-ack','foreign-next-pi','foreign-next-source','wrong-issuer-transaction','foreign-issuer-path','profile-retires','untrusted-issuer','substituted-issuer-form','premature-sdk-complete','same-url-issuer-replaced']){
 test('the real browser journey requires one original issuer challenge: '+fault,async()=>{
  const f=cancellationPage(fault,false,true);
  const run=runBillingInitial3dsBrowser(f.browser,f.port,{deadline:Date.now()+3000},async()=>{
   f.trace.push('operator');if(fault==='profile-retires')f.retire();if(fault==='same-url-issuer-replaced')f.reloadIssuer();
  });
  if(fault==='none')assert.equal(await run,true);else await assert.rejects(run,/billing_BROWSER/);
  assert.deepEqual(f.numbers,['4000002500003155']);
  assert.equal(f.trace.filter(x=>x==='Create subscription').length,1);assert.equal(f.trace.filter(x=>x==='Pay').length,1);
  assert.equal(f.sent.filter(x=>x.endsWith('/confirm')).length,1);assert.ok(f.closed());
  assert.equal(f.trace.includes('issuer witnessed'),fault==='none');
  assert.equal(f.trace.includes('issuer Complete'),['none','untrusted-issuer','substituted-issuer-form','premature-sdk-complete'].includes(fault));
  if(fault==='premature-sdk-complete')assert.equal(f.providerSent.some(x=>x.endsWith('/challenge_complete')),false);
  assert.equal(f.providerSent.filter(x=>x.endsWith('/decision')).length,fault==='none'?1:0);
 });
}

for(const label of ['demo','js.stripe.com','otp','verify','read','create','selection','api.stripe.com','r.stripe.com','merchant-ui-api.stripe.com','token','confirm']){
 test('a response redirect never forwards the original '+label+' request to a second destination',async()=>{
  const f=cancellationPage('redirect-'+label,false,true);
  await assert.rejects(runBillingInitial3dsBrowser(f.browser,f.port,{deadline:Date.now()+2000},async()=>{}),/billing_BROWSER/);
  assert.equal(f.trace.some(value=>value.startsWith('followed redirect ')),false);
  assert.ok(f.closed());assert.equal(f.trace.includes('issuer witnessed'),false);
 });
}

for(const fault of ['none','invalid ingress','invalid evidence','redirect-token','redirect-confirm']){
 test('single original Pay through held invoice browser: '+fault,async()=>{
  const f=cancellationPage(fault,false,false,true);
  const run=runBillingInvoiceWebhooksBrowser(f.browser,f.port,{deadline:Date.now()+3000},()=>false);
  if(fault==='none')assert.equal(await run,true);else await assert.rejects(run,/billing_BROWSER/);
  assert.deepEqual(f.numbers,['4242424242424242']);assert.ok(f.closed());
  assert.equal(f.trace.filter(x=>x==='Create subscription').length,1);assert.equal(f.trace.filter(x=>x==='Pay').length,1);
  assert.equal(f.sent.filter(x=>x.endsWith('/confirm')).length,['invalid ingress','redirect-token'].includes(fault)?0:1);
  assert.equal(f.trace.includes('completed invoice proof'),['none','invalid evidence'].includes(fault));
  assert.equal(f.trace.some(x=>x.startsWith('followed redirect ')||x==='Retry payment'||x==='issuer Complete'),false);
 });
}
