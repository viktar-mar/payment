import {errors} from '@playwright/test';
import type {Browser,Route,Request,Response,BrowserContext,Page,Locator,Frame,JSHandle,ElementHandle} from '@playwright/test';
import type {BillingPort,BillingBinding,BillingLimits,BillingCancellationPort,BillingDeclineRetryPort,BillingInitial3dsPort,BillingInvoiceWebhooksPort} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {GenericSubscriptionManager} from '@generic-payments/sdk';
import type {ManagerState} from '../../../../frontend/sdk/src/subscriptions/manager-state';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
import {acceptanceBrowserFailure,finishBrowserCleanup,rememberBrowserProviderOrigin} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
import type {RefusedIssuerRelation} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
// @ts-ignore Native Node and bundlers use the same shared provider origin policy.
import {providerOrigin,providerNetworkOrigin} from './stripe-provider-origin.ts';
// @ts-ignore Node's offline test entry consumes source.
import {enterPrivateText} from './private-entry.ts';
// @ts-ignore Explicit source for the offline Node tests.
import {guardChallengeRedirects} from './stripe-billing-initial-3ds-redirect.ts';
// @ts-ignore Explicit source for the offline Node tests.
import {initial3dsChallenge,type WaitForIssuerGesture} from './stripe-billing-initial-3ds.ts';
// @ts-ignore Explicit source for the offline Node tests.
import {pinnedRetryPresentation} from './stripe-billing-pinned-presentation.ts';
const fail=():never=>{throw new Error('billing_BROWSER');};
const need=(v:unknown):void=>{if(!v)fail();};
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>object(v)&&Object.keys(v).sort().join(',')===[...keys].sort().join(',');
const duration=(l:BillingLimits)=>{billingCheck(l);return Math.min(45000,l.deadline-Date.now());};
type BodyCheckpoint='READ'|'SIZE'|'JSON'|'OBJECT';
type SessionWireCheckpoint=BodyCheckpoint|'PROTOCOL'|'ATTEMPT'|'FIELDS'|'EXPIRY'|'KEY'|'CAPABILITY'|'INTENT';
async function body(response:Response,l:BillingLimits,checkpoint?:(step:BodyCheckpoint)=>void):Promise<Record<string,unknown>>{
 checkpoint?.('READ');const bytes=await billingBounded(l,()=>response.body());
 checkpoint?.('SIZE');need(bytes.length<=65536);
 checkpoint?.('JSON');const value:unknown=JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes));
 checkpoint?.('OBJECT');need(object(value));
 return value as Record<string,unknown>;
}

function initialSessionStatus(status:number):string{
 switch(status){
  case 400:return 'BROWSER_SESSION_HTTP_BAD_REQUEST';
  case 401:return 'BROWSER_SESSION_HTTP_UNAUTHORIZED';
  case 403:return 'BROWSER_SESSION_HTTP_FORBIDDEN';
  case 404:return 'BROWSER_SESSION_HTTP_NOT_FOUND';
  case 409:return 'BROWSER_SESSION_HTTP_CONFLICT';
  case 429:return 'BROWSER_SESSION_HTTP_RATE_LIMITED';
  default:return status>=500&&status<600?'BROWSER_SESSION_HTTP_SERVER':'BROWSER_INITIAL_SESSION_HTTP';
 }
}

/** Inspect only a small, already received problem from the owned session POST. */
async function initialSessionConflict(response:Response,limits:BillingLimits){
 try{
  return await billingBounded(limits,async diagnostic=>{
   const [type,encoding,length]=await Promise.all([
    response.headerValue('content-type'),response.headerValue('content-encoding'),response.headerValue('content-length'),
   ]);
   if(!type || !/^application\/problem\+json(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?$/i.test(type.trim()) ||
    encoding!==null&&encoding.trim().toLowerCase()!=='identity')return;
   const maximum=4096;
   if(!length||!/^[1-9][0-9]{0,3}$/.test(length))return;
   const declaredSize=Number(length);
   if(declaredSize>maximum)return;
   billingCheck(diagnostic);
   const {responseBodySize}=await response.request().sizes();
   if(!Number.isSafeInteger(responseBodySize)||responseBodySize<1||responseBodySize>maximum)return;
   billingCheck(diagnostic);
   const bytes=await response.body();
   billingCheck(diagnostic);
   if(bytes.length!==declaredSize)return;
   const problem:unknown=JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes));
   if(!object(problem)||problem.status!==409||typeof problem.code!=='string'||problem.type!=='urn:payment:problem:'+problem.code)return;
   switch(problem.code){
    case 'PROVIDER_EVIDENCE_MISMATCH':return 'BROWSER_SESSION_PROVIDER_EVIDENCE';
    case 'SUBSCRIPTION_STATE_CONFLICT':return 'BROWSER_SESSION_STATE_CONFLICT';
    case 'SUBSCRIPTION_CONFLICT':return 'BROWSER_SESSION_APPLICATION_CONFLICT';
    case 'resource_conflict':return 'BROWSER_SESSION_RESOURCE_CONFLICT';
   }
  },2000);
 }catch{return undefined;}
}

/** Observes and persists the SDK's own key. Never changes body/headers, fetches, replays or fulfills a financial request. */
export async function forwardBillingDispatch(route:Pick<Route,'request'|'continue'>,port:Pick<BillingPort,'beforeDispatch'>,binding:BillingBinding,authority:string,kind:'create'|'confirm',identity:Record<string,string>|undefined,limits:BillingLimits,confirmation?:{token:string;check:()=>void},check?:()=>void|Promise<void>,authorizeResponse?:(request:Request)=>Promise<void>,beforeForward?:()=>true):Promise<void>{
 try{billingCheck(limits);const request=route.request(),base=binding.endpoints.backend+'/api/v1/customer/subscriptions';
  const expected=kind==='create'?base:base+'/'+identity?.subscriptionId+'/operations/'+identity?.operationId+'/confirmation-attempts/'+identity?.attemptId+'/confirm';
  need(request.url()===expected&&request.method()==='POST'&&!request.redirectedFrom());const headers=await request.allHeaders(),value:unknown=JSON.parse(request.postData()??'');
  need(headers.authorization===authority&&/^Bearer [A-Za-z0-9_-]{43}$/.test(authority)&&headers.origin===binding.endpoints.demo&&headers['content-type']==='application/json'&&!headers.cookie&&typeof headers['idempotency-key']==='string'&&/^[A-Za-z0-9:_-]{1,255}$/.test(headers['idempotency-key']));
  if(kind==='create')need(exact(value,['price_id','recurring_consent'])&&value.price_id===binding.priceId&&value.recurring_consent===true);
  else need(exact(value,['capability','confirmation_token'])&&typeof value.capability==='string'&&/^bca2_[A-Za-z0-9_-]{43}$/.test(value.capability)&&typeof value.confirmation_token==='string'&&/^ctoken_[A-Za-z0-9]{1,200}$/.test(value.confirmation_token)&&(!confirmation||value.confirmation_token===confirmation.token));
  confirmation?.check();
  await check?.();confirmation?.check();need(await port.beforeDispatch(kind,headers['idempotency-key']!,identity)===true);
  if(authorizeResponse)await authorizeResponse(request);
  billingCheck(limits);await check?.();confirmation?.check();billingCheck(limits);if(beforeForward)need(beforeForward()===true);await route.continue();billingCheck(limits);
 }catch{fail();}
}
const stripeScriptOrigin = (url:URL) => providerOrigin(url) && (url.hostname==='js.stripe.com'||url.hostname.endsWith('.js.stripe.com'));

/** Capture live Stripe.js ancestors to the one checkout. A child of a foreign document cannot lend authority. */
function stripeFrames(page:Page,request:Request,assetNavigation:boolean,allowMain:boolean,demo:string) {
 const main=page.mainFrame(),target=request.frame();
 if(main.isDetached() || main.page()!==page || new URL(main.url()).origin!==demo || target.page()!==page || target.isDetached())return;
 if(target===main && (!allowMain || assetNavigation))return;
 const frames=new Map<Frame,string>();
 for(let current:Frame|null=assetNavigation?target.parentFrame():target;current!==main;current=current.parentFrame()){
  if(!current || frames.has(current) || current.page()!==page || current.isDetached() || !stripeScriptOrigin(new URL(current.url())))return;
  frames.set(current,current.url());
 }
 return {target,frames};
}

type CancellationKind = 'schedule' | 'withdraw';
type CancellationRequest = {kind:CancellationKind; url:string; request?:Request; forwarded?:boolean};

/** Bind each 202 to the original, already persisted SDK request from this document. */
function cancellationJourney(page:Page, manager:Locator, port:BillingCancellationPort, binding:BillingBinding,
 authority:string, customerId:string, subscriptionId:string, createId:string, limits:BillingLimits, check:()=>void,recordFailure:(code:string)=>void,
 authorizeResponse?:(request:Request)=>Promise<void>,checkOwner?:()=>Promise<void>) {
 const endpoint = binding.endpoints.backend+'/api/v1/customer/subscriptions/'+subscriptionId+'/cancellation';
 const usedKeys = new Set<string>(), operations = new Set([createId]);
 let armed:CancellationRequest|undefined;
 const button = (name:string) => manager.getByRole('button',{name,exact:true});
 const click = (name:string) => button(name).click({timeout:duration(limits)});
 const projection = () => manager.evaluate((element:Element)=>{
  const {phase,authorityRevision,operation,subscription:s} = (element as Element&{state:ManagerState}).state;
  return {phase,authorityRevision,operation:operation?.status,operationId:operation?.operationId,
   subscriptionId:s?.subscriptionId,siteId:s?.siteId,customerId:s?.customerId,productId:s?.productId,priceId:s?.billingPrice.priceId,
   state:s?.state,billing:s?.billingStatus,allowed:s?.access.allowed,reason:s?.access.reason,revision:s?.revision,
   periodEnd:s?.period?.end,paidThrough:s?.paidThrough,pending:s?.pendingOperation?.operationId,
   cancellation:s?.cancellation?.effectiveAt??null};
 });
 type Projection = Awaited<ReturnType<typeof projection>>;
 async function waitForProjection(kind:'initial'|CancellationKind, operationId:string, previous?:Projection) {
  for(;;){
   check();
   const view=await projection();
   need(!['unknown','error','expired','requires_action'].includes(view.phase) && !['UNKNOWN','FAILED','REQUIRES_ACTION'].includes(view.operation??''));
   need(view.subscriptionId===subscriptionId && view.siteId===binding.siteId && view.customerId===customerId &&
    view.productId===binding.productId && view.priceId===binding.priceId && view.billing==='ACTIVE' && view.allowed===true && view.reason==='PAID');
   need(!!view.paidThrough && view.periodEnd===view.paidThrough && Date.parse(view.paidThrough!)>Date.now());
   if(previous)need(view.authorityRevision===previous.authorityRevision && view.paidThrough===previous.paidThrough);
   if(view.pending)need(view.pending===operationId);
   if(view.operationId)need(view.operationId===operationId || operations.has(view.operationId));
   const expected=kind==='schedule'?'CANCEL_AT_PERIOD_END':'ACTIVE';
   if(view.state===expected){
    need(view.cancellation===(kind==='schedule'?view.paidThrough:null));
    if(view.phase==='ready' && !view.pending && (view.operation===undefined || view.operation==='SUCCEEDED') &&
      view.revision!==undefined && (!previous || view.revision>previous.revision!))return view;
   }
   await click('Refresh status');
   await billingBounded(limits,()=>new Promise(resolve=>setTimeout(resolve,250)),1000);
  }
 }
 async function mutate(kind:CancellationKind, previous:Projection) {
  if(kind==='schedule'){
   await click('Cancel at period end');
   await manager.getByRole('checkbox',{name:'I confirm cancellation',exact:true}).check({timeout:duration(limits)});
  }
  check();
  const intent:CancellationRequest={kind,url:kind==='schedule'?endpoint:endpoint+'/withdraw'};
  const receipt=page.waitForResponse(r=>r.url()===intent.url && r.request().method()==='POST',{timeout:duration(limits)});
  void receipt.catch(()=>{});
  let checkpoint:string|undefined='BROWSER_CANCELLATION_WITHDRAW_RESPONSE';
  armed=intent;
  try {
   await click(kind==='schedule'?'Confirm cancellation':'Withdraw cancellation');
   const response=await receipt;
   check();
   need(intent.forwarded && response.request()===intent.request && !response.fromServiceWorker());
   checkpoint='BROWSER_CANCELLATION_WITHDRAW_HTTP';
   need(response.status()===202);
   checkpoint='BROWSER_CANCELLATION_WITHDRAW_RECEIPT';
   const ack=await body(response,limits);
   need(exact(ack,['subscription_id','operation_id','status','action_available','next_action','browser_confirm_allowed']) &&
    ack.subscription_id===subscriptionId && uuid(ack.operation_id) && !operations.has(ack.operation_id) &&
    ack.next_action===null && ack.action_available===false && ack.browser_confirm_allowed===false);
   if(ack.status==='UNKNOWN')checkpoint='BROWSER_CANCELLATION_WITHDRAW_UNKNOWN';
   need(ack.status==='PROCESSING' || ack.status==='SUCCEEDED');
   checkpoint=undefined;
   const operationId=ack.operation_id as string;
   operations.add(operationId);
   armed=undefined;
   check();need(await port.verifyCancellation(kind,operationId)===true);
   return await waitForProjection(kind,operationId,previous);
  }catch(error){
   if(kind==='withdraw' && checkpoint)recordFailure(checkpoint);
   throw error;
  }finally{armed=undefined;}
 }
 return {
  async run(){
   const initial = await waitForProjection('initial',createId);
   check();need(await port.verifyInitial()===true);check();
   const scheduled = await mutate('schedule',initial);
   await mutate('withdraw',scheduled);
   check();
  },
  async forward(route:Route,report:(code:string)=>void){
   check();
   const req=route.request(),intent=armed;
   if(!intent || intent.request || req.url()!==intent.url || req.method()!=='POST' || req.redirectedFrom() ||
     req.frame()!==page.mainFrame() || req.isNavigationRequest())return fail();
   intent.request=req;
   const headers=await req.allHeaders(),key=headers['idempotency-key'];
   need(headers.origin===binding.endpoints.demo && headers.authorization===authority && /^Bearer [A-Za-z0-9_-]{43}$/.test(authority) &&
    !headers.cookie && headers['content-type']==='application/json' && uuid(key) && !usedKeys.has(key));
   // The manager uses JSON.stringify for these exact bodies. Byte matching also excludes duplicate JSON members.
   need(req.postData()===(intent.kind==='schedule'?'{"mode":"PERIOD_END"}':'{}'));
   usedKeys.add(key!);
   const current=()=>{check();need(armed===intent && intent.request===req);};
   const withdrawCheckpoint=(stage:'OWNER'|'FORWARD')=>{
    if(intent.kind==='withdraw')report('BROWSER_CANCELLATION_WITHDRAW_'+stage);
   };
   withdrawCheckpoint('OWNER');
   if(checkOwner)await checkOwner();
   current();need(await port.beforeCancellation(intent.kind,key!)===true);
   current();
   withdrawCheckpoint('FORWARD');
   if(authorizeResponse)await authorizeResponse(req);
   withdrawCheckpoint('OWNER');
   if(checkOwner)await checkOwner();
   current();
   withdrawCheckpoint('FORWARD');
   intent.forwarded=true;
   await route.continue();check();
  }
 };
}
/** Genuine demo OTP + one manager + visible ready provider Element. Only original server reads settle it. */
export function runInitialBillingBrowser(browser:Browser,port:BillingPort,limits:BillingLimits):Promise<true>{
 return runBillingBrowser(browser,port,limits);
}
export function runBillingCancellationBrowser(browser:Browser,port:BillingCancellationPort,limits:BillingLimits,matchesStaticOrigin?:(origin:string)=>boolean):Promise<true>{
 return runBillingBrowser(browser,port,limits,port,undefined,matchesStaticOrigin);
}
export function runBillingDeclineRetryBrowser(browser:Browser,port:BillingDeclineRetryPort,limits:BillingLimits,matchesStaticOrigin?:(origin:string)=>boolean):Promise<true>{
 return runBillingBrowser(browser,port,limits,undefined,port,matchesStaticOrigin);
}
export function runBillingInitial3dsBrowser(browser:Browser,port:BillingInitial3dsPort,limits:BillingLimits,waitForGesture:WaitForIssuerGesture,matchesStaticOrigin?:(origin:string)=>boolean,approvedIssuerOrigin?:string):Promise<true>{
 return runBillingBrowser(browser,port,limits,undefined,undefined,matchesStaticOrigin,{port,waitForGesture,approvedIssuerOrigin});
}
export function runBillingInvoiceWebhooksBrowser(browser:Browser,port:BillingInvoiceWebhooksPort,limits:BillingLimits,matchesStaticOrigin:(origin:string)=>boolean):Promise<true>{
 return runBillingBrowser(browser,port,limits,undefined,undefined,matchesStaticOrigin,undefined,port);
}
async function runBillingBrowser(browser:Browser,port:BillingPort,limits:BillingLimits,cancellationPort?:BillingCancellationPort,retryPort?:BillingDeclineRetryPort,matchesStaticOrigin?:(origin:string)=>boolean,challenge?:{port:BillingInitial3dsPort;waitForGesture:WaitForIssuerGesture;approvedIssuerOrigin?:string},invoiceCase?:BillingInvoiceWebhooksPort):Promise<true>{
 const pinnedCancellation=!!cancellationPort&&typeof matchesStaticOrigin==='function';
 const exactInitialCheckout=!!(retryPort||challenge||pinnedCancellation);
 const bound=exactInitialCheckout||!!invoiceCase,guarded=!!(cancellationPort||bound);
 const responseGuard=!!(challenge||invoiceCase||pinnedCancellation);
 const lifecycle=new AbortController();
 const ownerLimits=limits;
 limits={deadline:limits.deadline,signal:limits.signal?AbortSignal.any([limits.signal,lifecycle.signal]):lifecycle.signal};
 let context:BrowserContext|undefined,abandoned=false,closing:Promise<void>|undefined,failure=false;
 let checkpoint=cancellationPort?'BROWSER_SETUP':'BROWSER',firstFailure:string|undefined,cleanupConfirmed=false;
 let refusedOrigin:{runId:string;expectedSource:string;origin:string;issuerRelation:Promise<RefusedIssuerRelation>}|undefined;
 const recordFailure=(code=checkpoint)=>{firstFailure??=code;failure=true;};
 const close=(ctx:BrowserContext)=>{closing??=ctx.close();void closing.catch(()=>{});return closing;};
 const opening=browser.newContext({serviceWorkers:'block',acceptDownloads:false});void opening.then(c=>{if(abandoned)return close(c);},()=>{}).catch(()=>{});
 const abort=()=>{
  abandoned=true;lifecycle.abort();
  const current=context;
  if(!current)return;
  if(refusedOrigin)void refusedOrigin.issuerRelation.then(()=>close(current)).catch(()=>{});
  else void close(current);
 };limits.signal?.addEventListener('abort',abort,{once:true});
 const pending=new Set<Promise<void>>();
 try{
  context=await billingBounded(limits,()=>opening);const page=await billingBounded(limits,()=>context!.newPage()),binding=port.resources();
  let challengeDispatched=false;
  let authority='',subscriptionId='',operationId='',attemptId='',createPosts=0,confirmPosts=0,selectPosts=0,challengePosts=0,verifyPosts=0;
  const base=binding.endpoints.backend+'/api/v1/customer/subscriptions',otp=binding.endpoints.backend+'/api/v1/public/customer-auth/email-otp/challenges';
  let acknowledgeCreate!:()=>void;const created=new Promise<void>(r=>acknowledgeCreate=r);
  let cancellation:ReturnType<typeof cancellationJourney>|undefined, challengeId:string|undefined;
  let createGestureInFlight=false,payInFlight=false,retryGestureInFlight=false;
  let trustedPayClick:JSHandle<()=>boolean>|undefined, tokenRequest:Request|undefined, publishableKey:string|undefined;
  const requests=new Map<string,Request>(),keys=new Set<string>();
  const initialReads:{url:string;request:Request;authorized?:boolean}[]=[];
  const initialReadPath=/^\/[a-f0-9-]{36}\/operations\/[a-f0-9-]{36}(?:\/action|\/confirmation-attempts\/current)?$/;
  let initialReadOverflow=false,initialSessionObserved=false,initialSessionForwarded=false;
  let sessionExpiry=0;
  let firstAttempt='',firstSecret='',capability='',firstToken='',successorId='';
  let trustedRetry:JSHandle<()=>boolean>|undefined,trustedCreate:JSHandle<()=>boolean>|undefined;
  const successorAck=Promise.withResolvers<void>();
  const initialPresentationReady=Promise.withResolvers<void>();
  const successorPresentationReady=Promise.withResolvers<void>();
  let originalManager:JSHandle<()=>boolean>|undefined;
  async function checkManager(){
   check();
   if(responseGuard && firstAttempt && !cancellation)need(sessionExpiry>Date.now());
   if(bound)need(originalManager && await originalManager.evaluate(current=>current()));
   check();
  }
  function claim(kind:string,request:Request){
   need(!requests.has(kind));requests.set(kind,request);
  }
  function paired(kind:string,response:Response){
   if(bound)need(response.request()===requests.get(kind) && !response.fromServiceWorker() && !response.request().redirectedFrom());
  }
  function observeInitialRead(url:string,request:Request){
   if(initialReads.length===12){initialReadOverflow=true;return;}
   const observation:{url:string;request:Request;authorized?:boolean}={url,request};
   const expectedAuthority=authority;
   initialReads.push(observation);
   // Inspect request identity independently of continuation. A failed observation never controls routing.
   void request.allHeaders().then(headers=>{
    observation.authorized=!!expectedAuthority && headers.authorization===expectedAuthority && headers.origin===binding.endpoints.demo &&
     !headers.cookie && !headers['proxy-authorization'];
   }).catch(()=>{});
  }
  function initialSessionBoundary(original:string,expectsReads:boolean){
   const session=requests.get('initial-session');
   if(session?.existingResponse())return 'BROWSER_SESSION_UNPAIRED';
   if(initialSessionForwarded)return 'BROWSER_SESSION_NO_RESPONSE';
   if(initialSessionObserved || session)return 'BROWSER_SESSION_REQUEST_PENDING';
   if(!expectsReads)return 'BROWSER_SESSION_NOT_SENT';
   if(initialReadOverflow)return 'BROWSER_INITIAL_SESSION';
   for(const [suffix,missing,http] of [
    ['', 'BROWSER_OPERATION_NOT_SEEN', 'BROWSER_OPERATION_HTTP'],
    ['/action', 'BROWSER_ACTION_NOT_SEEN', 'BROWSER_ACTION_HTTP'],
    ['/confirmation-attempts/current', 'BROWSER_CURRENT_NOT_SEEN', 'BROWSER_CURRENT_HTTP'],
   ] as const){
    const url=original+suffix,owned=initialReads.filter(read=>read.url===url && read.authorized!==false);
    const read=owned[0];
    if(owned.length>1 || (read && !read.authorized))return 'BROWSER_INITIAL_SESSION';
    const request=read?.request,response=request?.existingResponse();
    if(!response || response.request()!==request || response.url()!==url || response.fromServiceWorker())return missing;
    const status=response.status();
    if(suffix==='/action' && status===409)return 'BROWSER_ACTION_CONFLICT';
    if(status!==200)return http;
   }
   return 'BROWSER_SESSION_NOT_SENT';
  }
  function captureTrustedClick(element:HTMLElement|SVGElement){
   let clicked=false;
   element.addEventListener('click',event=>{clicked=event.isTrusted;},{once:true,capture:true});
   return ()=>clicked;
  }
  function trustedClick(button:Locator){return button.evaluateHandle(captureTrustedClick);}
  async function focusExactButton(locator:Locator){
   const button=await locator.elementHandle({timeout:duration(limits)});
   await button.waitForElementState('stable',{timeout:duration(limits)});
   await button.waitForElementState('enabled',{timeout:duration(limits)});
   await billingBounded(limits,()=>button.focus(),duration(limits));
   return button;
  }
  async function requireButtonFocus(button:ElementHandle<HTMLElement|SVGElement>){
   need(await button.evaluate(element=>{
    const root=element.getRootNode();
    return element instanceof HTMLButtonElement && element.type==='button' && element.isConnected && !element.disabled &&
     root instanceof ShadowRoot && root.host===document.querySelector('generic-subscription-manager') && root.activeElement===element;
   }));
  }
  function sessionContext(value:Record<string,unknown>,successor:boolean,report?:(step:SessionWireCheckpoint)=>void){
   report?.('FIELDS');need(exact(value,['protocol','provider','flow','publishable_key','client_secret','attempt_id','capability','expires_at']));
   report?.('PROTOCOL');need(value.protocol==='SERVER_CONFIRM_V2' && value.provider==='stripe' && value.flow==='PAYMENT');
   report?.('EXPIRY');need(typeof value.expires_at==='string' && Date.parse(value.expires_at)>Date.now());
   report?.('KEY');need(typeof value.publishable_key==='string' && /^pk_test_[A-Za-z0-9_]+$/.test(value.publishable_key));
   report?.('CAPABILITY');need(typeof value.capability==='string' && /^bca2_[A-Za-z0-9_-]{43}$/.test(value.capability));
   report?.('INTENT');need(typeof value.client_secret==='string' && /^pi_[A-Za-z0-9]+_secret_[A-Za-z0-9]+$/.test(value.client_secret));
   if(successor)need(value.attempt_id===successorId && value.client_secret===firstSecret && value.capability!==capability && value.publishable_key===publishableKey);
   else {firstAttempt=attemptId;sessionExpiry=Date.parse(value.expires_at as string);firstSecret=value.client_secret as string;publishableKey=value.publishable_key as string;}
   capability=value.capability as string;
  }
  let checkoutUrl='';
  function acceptedSessionHeaders(kind:'initial-session'|'successor-session'){
   const request=requests.get(kind),response=request?.existingResponse();
   return !!request && response?.request()===request && response.url()===request.url() &&
    response.status()===200 && !response.fromServiceWorker() && !request.redirectedFrom();
  }
  const pinned=bound&&matchesStaticOrigin?pinnedRetryPresentation(page,()=>checkoutUrl,limits,()=>{
   if(firstAttempt && !successorId && confirmPosts===0)return 'initial';
   if(successorId && attemptId===successorId && confirmPosts===1)return 'successor';
   return undefined;
  },()=>!payInFlight && confirmPosts===0,kind=>{
   if(!firstAttempt && selectPosts===1 && requests.has('initial-session') &&
      (kind==='document' || exactInitialCheckout && acceptedSessionHeaders('initial-session')))
    return {phase:'initial',ready:initialPresentationReady.promise};
   if(kind==='asset' && retryPort && successorId && attemptId!==successorId && confirmPosts===1 && acceptedSessionHeaders('successor-session'))
    return {phase:'successor',ready:successorPresentationReady.promise};
  },recordFailure):undefined;
  const winning=Promise.withResolvers<void>();
  const invalidate=(reason:string)=>{recordFailure(reason);abort();};
  if(responseGuard)checkpoint='BROWSER_SETUP';
  const redirects=responseGuard?await guardChallengeRedirects(page,invalidate,binding.endpoints,limits.signal,challenge?.approvedIssuerOrigin):undefined;
  const sca=challenge?initial3dsChallenge({page,limits,demo:binding.endpoints.demo,owner:()=>({secret:firstSecret,key:publishableKey??''}),
   stripeFrames:req=>stripeFrames(page,req,false,false,binding.endpoints.demo),authorizeResponse:req=>redirects!.authorize(req),check:checkManager,waitForGesture:challenge.waitForGesture,
   approvedIssuerOrigin:challenge.approvedIssuerOrigin,
   winning:async()=>{
    const original=requests.get('original-confirm'),response=original?.existingResponse();
    need(response && response.request()===original && response.status()===200 && !response.fromServiceWorker());
    await billingBounded(limits,()=>winning.promise);
   },
  }):undefined;
  async function providerTraffic(route:Route,url:URL,report:(code:string)=>void) {
   const request=route.request(),method=request.method(),navigation=request.isNavigationRequest();
   if(sca?.owns(url)){await sca.forward(route,url,report);return;}
   if(pinned && !providerNetworkOrigin(url) && !/(?:^|\.)stripe\.(?:com|network)$/.test(url.hostname) && matchesStaticOrigin!(url.origin)){
    await pinned.forward(route,url);return;
   }
   if(!providerNetworkOrigin(url)){
    const host=url.hostname;
    report(url.protocol!=='https:'?'BROWSER_PROVIDER_ORIGIN_SCHEME':
     url.port?'BROWSER_PROVIDER_ORIGIN_PORT':
     url.username||url.password?'BROWSER_PROVIDER_USERINFO':
     /(?:^|\.)stripe\.com$/.test(host)?'BROWSER_PROVIDER_ORIGIN_STRIPE_COM':
     /(?:^|\.)stripe\.network$/.test(host)?'BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK':'BROWSER_PROVIDER_ORIGIN_OTHER');
    fail();
   }
   report('BROWSER_PROVIDER_REDIRECT');
   need(!request.redirectedFrom());
   report('BROWSER_PROVIDER_ROUTE_METHOD');
   need(['GET','POST','OPTIONS'].includes(method));
   const script=stripeScriptOrigin(url),api=url.hostname==='api.stripe.com';
   const support=url.hostname==='r.stripe.com'||url.hostname==='merchant-ui-api.stripe.com';
   const preflight=method==='OPTIONS';
   const tokenPath=api && url.pathname==='/v1/confirmation_tokens';
   report('BROWSER_PROVIDER_ROUTE_FRAME');
   const original=stripeFrames(page,request,navigation,script && method==='GET',binding.endpoints.demo);
   need(original && (!navigation || script && method==='GET'));
   const click=trustedPayClick,interceptedAttempt=attemptId;
   const samePay=()=>need(payInFlight && click && click===trustedPayClick && attemptId===interceptedAttempt);
   if(tokenPath){report('BROWSER_PROVIDER_ROUTE_PAY');samePay();need(await click!.evaluate(clicked=>clicked()));}
   report('BROWSER_PROVIDER_HEADERS');
   const headers=await request.allHeaders();
   report('BROWSER_PROVIDER_CURRENT');
   check();
   const sameFrames=()=>{
    report('BROWSER_PROVIDER_ROUTE_FRAME');
    const current=stripeFrames(page,request,navigation,script && method==='GET',binding.endpoints.demo);
    need(current && original && current.target===original.target && current.frames.size===original.frames.size &&
     [...original.frames].every(([frame,href])=>current.frames.get(frame)===href));
   };
   sameFrames();
   report('BROWSER_PROVIDER_AUTH');
   need(!headers.cookie && !headers['proxy-authorization'] &&
    (!headers.authorization || api && !preflight && publishableKey && headers.authorization==='Bearer '+publishableKey));
   const intended=preflight?headers['access-control-request-method']:method;
   report('BROWSER_PROVIDER_ROUTE_METHOD');
   need(intended==='GET'||intended==='POST');
   report('BROWSER_PROVIDER_ROUTE_POLICY');
   if(!script && headers.origin)need(headers.origin===new URL(request.frame().url()).origin);
   if(script)need(intended==='GET');
   else if(support){
    // These two network-only origins are approved for uncredentialed support requests.
    // Never forward API resources or navigation through a support origin.
    need(!navigation && !/^\/(?:v\d+|api)(?:\/|$)/i.test(url.pathname) && !url.pathname.includes('%'));
   }else if(api && !navigation && !cancellation && selectPosts===1){
    if(tokenPath){
     samePay();need(!tokenRequest && intended==='POST' && !url.search);
     await checkManager();samePay();need(!tokenRequest);
     if(!preflight)tokenRequest=request;
    }else need(url.pathname==='/v1/elements/sessions');
   }else fail();
   if(redirects){
    report('BROWSER_PROVIDER_ROUTE_GUARD');
    await redirects.authorize(request);check();sameFrames();
    if(tokenPath){report('BROWSER_PROVIDER_ROUTE_PAY');await checkManager();samePay();sameFrames();}
   }
   report('BROWSER_PROVIDER_FALLBACK');
   check();await route.fallback();
  }
  const check=()=>{billingCheck(limits);if(failure||abandoned)fail();};
  if(guarded){
   if(redirects)context.on('request',request=>{if(request.redirectedFrom())invalidate('BROWSER_GUARD_REQUEST_REDIRECT');});
   context.on('page',()=>invalidate('BROWSER_GUARD_POPUP'));
   await context.routeWebSocket('**/*',socket=>{try{socket.close();}finally{invalidate('BROWSER_GUARD_WEBSOCKET');}});
  }
  await context.route(guarded?'**/*':binding.endpoints.backend+'/**',route=>{
   const work=(async()=>{let routeCheckpoint:string|undefined,origin:string|undefined;try{
    billingCheck(limits);if(failure)fail();const req=route.request(),url=req.url(),method=req.method();
    redirects?.registerRoute(req);
    if(guarded){
     const target=new URL(url);
     if(target.origin===binding.endpoints.demo){
      need(method==='GET' && !req.redirectedFrom());
      if(redirects){await redirects.authorize(req);check();}
      await route.fallback();return;
     }
     if(target.origin!==binding.endpoints.backend){
      routeCheckpoint='BROWSER_PROVIDER_ROUTE';
      await providerTraffic(route,target,code=>{
       if(!challenge&&!cancellationPort)return;
       routeCheckpoint=code;
       if(challenge&&challengeDispatched&&code==='BROWSER_PROVIDER_ORIGIN_STRIPE_COM')origin=target.origin;
      });return;
     }
     if(cancellation && !['GET','OPTIONS'].includes(method)){await cancellation.forward(route,code=>{routeCheckpoint=code;});return;}
    }
    const observingSession=exactInitialCheckout && !attemptId && method==='POST' && subscriptionId && operationId &&
     url===base+'/'+subscriptionId+'/operations/'+operationId+'/confirmation-session';
    if(exactInitialCheckout&&url===base&&method==='POST')routeCheckpoint='BROWSER_CREATE_REQUEST_REJECTED';
    if(observingSession){initialSessionObserved=true;routeCheckpoint='BROWSER_SESSION_REQUEST_REJECTED';}
    if(retryPort && firstAttempt && method==='POST' &&
       url===base+'/'+subscriptionId+'/operations/'+operationId+'/confirmation-attempts/'+firstAttempt+'/retry'){
     routeCheckpoint='BROWSER_RETRY_REQUEST_REJECTED';
    }
    if(bound)need(req.frame()===page.mainFrame() && !req.isNavigationRequest() && !req.redirectedFrom());
    async function continueBackend(){
     if(redirects){
      await redirects.authorize(req);check();
      need(req.frame()===page.mainFrame() && !req.isNavigationRequest() && !req.redirectedFrom());
      if(originalManager)await checkManager();
     }
     check();await route.continue();
    }
    if(method==='OPTIONS' || method==='GET'){
     // The SDK may start its reads before the controller parses the CREATE response.
     // Retain only a few request references. Classify them later against the exact receipt.
     if(exactInitialCheckout && createPosts===1 && !initialSessionObserved && !selectPosts && method==='GET' && url.startsWith(base) &&
        initialReadPath.test(url.slice(base.length)))observeInitialRead(url,req);
     await continueBackend();return;
    }
    if(method!=='POST')fail();
    if(guarded)need(req.frame()===page.mainFrame() && !req.isNavigationRequest() && !req.redirectedFrom());
    if(url===otp){need(++challengePosts===1);if(bound)claim('challenge',req);const v:unknown=JSON.parse(req.postData()??'');need(exact(v,['site_key','email','intent'])&&v.site_key===binding.siteKey&&v.email===binding.email&&v.intent==='AUTHENTICATE');await continueBackend();return;}
    if(url.startsWith(otp+'/')&&url.endsWith('/verify')){need(++verifyPosts===1 && (!guarded || challengeId && url===otp+'/'+challengeId+'/verify'));if(bound)claim('verify',req);await continueBackend();return;}
    let key:string|undefined;
    if(bound){
     const h=await req.allHeaders();key=h['idempotency-key'];
     need(uuid(key)&&!keys.has(key)&&h.authorization===authority&&h.origin===binding.endpoints.demo&&
      h['content-type']==='application/json'&&!h.cookie&&!h['proxy-authorization']);
     keys.add(key!);await checkManager();
    }
    if(url===base){
     need(++createPosts===1);
     if(bound){claim('create',req);need(trustedCreate && await trustedCreate.evaluate(clicked=>clicked()));}
     const requireCreateGesture=():true=>{need(createGestureInFlight);return true;};
     const createOwner:Pick<BillingPort,'beforeDispatch'>=exactInitialCheckout?{beforeDispatch:(kind,key,identity)=>{
      requireCreateGesture();
      return port.beforeDispatch(kind,key,identity);
     }}:port;
     await forwardBillingDispatch(route,createOwner,binding,authority,'create',undefined,limits,undefined,checkManager,
      redirects?.authorize,exactInitialCheckout?requireCreateGesture:undefined);return;
    }
    if(guarded && !subscriptionId)need(createPosts===1 && url.startsWith(base+'/') && url.endsWith('/confirmation-session'));
    await billingBounded(limits,()=>created);const original=base+'/'+subscriptionId+'/operations/'+operationId;
    if(url===original+'/confirmation-session'){
     if(exactInitialCheckout)routeCheckpoint='BROWSER_SESSION_REQUEST_REJECTED';
     need(++selectPosts===1);if(bound)claim('initial-session',req);const v:unknown=JSON.parse(req.postData()??''),h=await req.allHeaders();need(exact(v,['action_token'])&&typeof v.action_token==='string'&&/^bga1_[A-Za-z0-9_-]{43}$/.test(v.action_token)&&h.authorization===authority&&h.origin===binding.endpoints.demo&&!h.cookie);
     await continueBackend();if(exactInitialCheckout)initialSessionForwarded=true;return;
    }
    const attempts=original+'/confirmation-attempts/';
    if(retryPort && url===attempts+firstAttempt+'/retry'){
     claim('retry',req);
     need(retryGestureInFlight && trustedRetry && await trustedRetry.evaluate(clicked=>clicked()) && req.postData()==='{}');
     await checkManager();check();
     if(!retryGestureInFlight||!key)return fail();
     need(await retryPort.beforeRetry(key,{subscriptionId,operationId,attemptId:firstAttempt})===true);
     await checkManager();check();need(retryGestureInFlight);
     await route.continue();return;
    }
    if(retryPort && url.endsWith('/client-session')){
     need(requests.has('retry') && req.postData()==='{}');claim('successor-session',req);
     await billingBounded(limits,()=>successorAck.promise);check();
     need(url===attempts+successorId+'/client-session');await checkManager();await route.continue();return;
    }
    const successor=!!retryPort&&!!successorId;
    need(url===attempts+attemptId+'/confirm'&&++confirmPosts===(successor?2:1));
    if(bound)claim(successor?'successor-confirm':'original-confirm',req);
    let confirmation:{token:string;check:()=>void}|undefined;
    if(guarded){
     // The SDK cannot legitimately confirm until its own token POST has received a response.
     // Response headers precede the SDK's confirm. Our read of its JSON body may still be pending.
     const tokenResponse=tokenRequest?.existingResponse();
     if(!payInFlight || !tokenResponse || tokenResponse.request()!==tokenRequest ||
       tokenResponse.url()!=='https://api.stripe.com/v1/confirmation_tokens' || tokenResponse.fromServiceWorker() || tokenResponse.status()!==200)return fail();
     const token=await body(tokenResponse,limits);
     check();
     if(token.object!=='confirmation_token' || token.livemode!==false || typeof token.id!=='string' || !/^ctoken_[A-Za-z0-9]{1,200}$/.test(token.id))return fail();
     if(bound){
      need(req.postData()===JSON.stringify({capability,confirmation_token:token.id}));
      if(successor)need(token.id!==firstToken);else firstToken=token.id;
     }
     confirmation={token:token.id,check:()=>{check();need(payInFlight);}};
    }
    const dispatch=successor?{beforeDispatch:(_kind:unknown,key:string)=>retryPort!.beforeSuccessorConfirm(key,{subscriptionId,operationId,attemptId})}:port;
    await forwardBillingDispatch(route,dispatch,binding,authority,'confirm',{subscriptionId,operationId,attemptId},limits,confirmation,checkManager,redirects?.authorize,invoiceCase?.releaseOriginalConfirm);
   }catch{
    const capture=!firstFailure&&origin?sca:undefined;
    recordFailure(routeCheckpoint);
    if(capture&&origin){
     const blocked=route.abort('failed').catch(()=>{});
     refusedOrigin={runId:binding.runId,expectedSource:binding.expectedSource,origin,
      issuerRelation:capture.refusedIssuerRelation(route.request(),ownerLimits)};
     abort();await blocked;return;
    }
    abort();await route.abort('failed').catch(()=>{});
   }})();pending.add(work);void work.finally(()=>pending.delete(work));return work;
  });
  const url=new URL('/',binding.endpoints.demo);
  url.search=new URLSearchParams({
   api_base_url:binding.endpoints.backend,
   site_key:binding.siteKey,
   subscription_product_1_id:binding.productId,
   subscription_price_1_id:binding.priceId,
   locale:'en',
   // The demo writes both product slots to the address on mount.
   subscription_product_2_id:'',
  }).toString();
  if(guarded)checkpoint='BROWSER_NAVIGATION';
  checkoutUrl=url.href;await page.goto(url.href,{timeout:duration(limits)});
  if(guarded){
   need(new URL(page.url()).origin===binding.endpoints.demo);
   page.on('framenavigated',frame=>{if(frame===page.mainFrame())invalidate('BROWSER_GUARD_MAIN_NAVIGATION');});
  }
  if(guarded)checkpoint='BROWSER_OTP_REQUEST';
  const widget=page.locator('generic-email-otp').first();
  await enterPrivateText(widget.getByRole('textbox',{name:'Email',exact:true}),binding.email);
  const otpChallenge=page.waitForResponse(r=>r.url()===otp&&r.request().method()==='POST',{timeout:duration(limits)});void otpChallenge.catch(()=>{});await widget.getByRole('button',{name:'Request code',exact:true}).click({timeout:duration(limits)});
  const challengeResponse=await otpChallenge;paired('challenge',challengeResponse);need(challengeResponse.status()===202);const challengeBody=await body(challengeResponse,limits);need(uuid(challengeBody.challenge_id));challengeId=challengeBody.challenge_id as string;
  await port.withOtpCode(async code=>{await enterPrivateText(widget.getByRole('textbox',{name:'Six-digit code',exact:true}),code);return true as const;},limits);
  if(guarded)checkpoint='BROWSER_OTP_VERIFY';
  const verification=page.waitForResponse(r=>r.url()===otp+'/'+challengeBody.challenge_id+'/verify'&&r.request().method()==='POST',{timeout:duration(limits)});void verification.catch(()=>{});
  const baseline=page.waitForResponse(r=>r.url().startsWith(base+'?')&&r.request().method()==='GET',{timeout:duration(limits)});void baseline.catch(()=>{});
  await widget.getByRole('button',{name:'Verify code',exact:true}).click({timeout:duration(limits)});const response=await verification;paired('verify',response);need(response.status()===200);const session=await body(response,limits),customer=session.customer;
  need(object(customer)&&uuid(customer.id)&&customer.status==='ACTIVE'&&session.site_id===binding.siteId&&Array.isArray(session.scopes)&&session.scopes.includes('SUBSCRIPTION_READ')&&session.scopes.includes('SUBSCRIPTION_MANAGE')&&typeof session.session_token==='string'&&/^[A-Za-z0-9_-]{43}$/.test(session.session_token));
  authority='Bearer '+session.session_token;await port.acknowledgeCustomer({customerId:(customer as Record<string,string>).id!,siteId:binding.siteId});
  if(exactInitialCheckout||cancellationPort)checkpoint='BROWSER_CREATE_BASELINE';
  const before=await body(await baseline,limits);need(Array.isArray(before.items)&&before.items.length===0&&before.next_cursor===null);
  if(exactInitialCheckout||cancellationPort)checkpoint='BROWSER_CREATE_CONSENT';
  const manager=page.locator('generic-subscription-manager').first();await manager.getByRole('checkbox',{name:'I consent to recurring payments',exact:true}).check({timeout:duration(limits)});
  if(exactInitialCheckout||cancellationPort)checkpoint='BROWSER_CREATE_OWNER';
  if(bound){
   originalManager=await manager.evaluateHandle((element,expected)=>{
    const m=element as GenericSubscriptionManager,revision=m.state.authorityRevision,session=m.customerSession;
    let retired=false;
    window.addEventListener('pagehide',()=>{retired=true;},{once:true});
    document.addEventListener('visibilitychange',()=>{if(document.hidden)retired=true;});
    return ()=>!retired && !document.hidden && m.isConnected && m.getClientRects().length>0 &&
     document.querySelector('generic-subscription-manager')===m && m.state.authorityRevision===revision &&
     m.priceId===expected.priceId && m.productId===expected.productId && !!session && m.customerSession===session &&
     session.customer.id===expected.customerId && session.siteId===expected.siteId && Date.parse(session.expiresAt)>Date.now();
   },{priceId:binding.priceId,productId:binding.productId,siteId:binding.siteId,customerId:(customer as Record<string,string>).id!});
   await checkManager();
  }
  const createButton=manager.getByRole('button',{name:'Create subscription',exact:true});
  if(exactInitialCheckout)checkpoint='BROWSER_CREATE_FOCUS';
  const exactCreate=exactInitialCheckout?await focusExactButton(createButton):undefined;
  if(bound)trustedCreate=exactCreate?await exactCreate.evaluateHandle(captureTrustedClick):await trustedClick(createButton);
  if(exactCreate){await checkManager();await requireButtonFocus(exactCreate);}
  if(exactInitialCheckout||cancellationPort)checkpoint='BROWSER_CREATE_GESTURE';
  const createObservationFailed=Symbol();
  const createResponse=page.waitForResponse(r=>r.url()===base&&r.request().method()==='POST',{timeout:duration(limits)})
   .catch(()=>{throw createObservationFailed;});void createResponse.catch(()=>{});
  let receipt:Response;
  createGestureInFlight=true;
  try{
   const activation=exactCreate?exactCreate.press('Enter',{timeout:duration(limits)}):createButton.click({timeout:duration(limits)});
   const observed=activation.then(async()=>{
    if(exactCreate)need(await trustedCreate!.evaluate(clicked=>clicked()));
   });
   [,receipt]=await Promise.all([observed,createResponse]);
  }catch(error){
   if(exactInitialCheckout&&error===createObservationFailed)checkpoint='BROWSER_CREATE_WAIT';
   throw error;
  }finally{createGestureInFlight=false;}
  if(exactInitialCheckout||cancellationPort)checkpoint='BROWSER_CREATE_RECEIPT';
  paired('create',receipt);need(receipt.status()===202);const original=await body(receipt,limits);need(uuid(original.subscription_id)&&uuid(original.operation_id)&&['PROCESSING','REQUIRES_ACTION','RESERVED'].includes(String(original.status)));
  subscriptionId=original.subscription_id as string;operationId=original.operation_id as string;await port.acknowledge('create',{subscriptionId,operationId});
  checkpoint='BROWSER_INITIAL_SESSION';
  const originalOperation=base+'/'+subscriptionId+'/operations/'+operationId;
  const expectsActionReads=original.next_action===null && (original.status==='PROCESSING'||original.status==='REQUIRES_ACTION');
  const selection=page.waitForResponse(r=>r.url()===originalOperation+'/confirmation-session'&&r.request().method()==='POST',{timeout:duration(limits)});void selection.catch(()=>{});acknowledgeCreate();
  let selected:Response;
  try{selected=await selection;}catch(error){
   if(exactInitialCheckout && error instanceof errors.TimeoutError && !firstFailure && !limits.signal?.aborted){
    checkpoint=initialSessionBoundary(originalOperation,expectsActionReads);
   }
   throw error;
  }finally{initialReads.length=0;}
  if(exactInitialCheckout||cancellationPort)checkpoint='BROWSER_SESSION_UNPAIRED';
  paired('initial-session',selected);
  const sessionStatus=selected.status();
  if(sessionStatus!==200){
   const ownsFailure=firstFailure===undefined;
   recordFailure(exactInitialCheckout?initialSessionStatus(sessionStatus):'BROWSER_INITIAL_SESSION_HTTP');
   if(ownsFailure&&exactInitialCheckout&&sessionStatus===409){
    const category=await initialSessionConflict(selected,limits);
    if(category)firstFailure=category;
   }
   fail();
  }
  checkpoint='BROWSER_INITIAL_SESSION_WIRE';
  const wire=exactInitialCheckout?(step:SessionWireCheckpoint)=>{checkpoint='BROWSER_INITIAL_SESSION_WIRE_'+step;}:undefined;
  const selectedBody=await body(selected,limits,wire);
  wire?.('PROTOCOL');need(selectedBody.protocol==='SERVER_CONFIRM_V2'&&selectedBody.provider==='stripe'&&selectedBody.flow==='PAYMENT');
  wire?.('ATTEMPT');need(uuid(selectedBody.attempt_id));attemptId=selectedBody.attempt_id as string;
  if(bound){sessionContext(selectedBody,false,wire);initialPresentationReady.resolve();}
  if(cancellationPort && !bound && typeof selectedBody.publishable_key==='string' && /^pk_test_[A-Za-z0-9_]+$/.test(selectedBody.publishable_key))publishableKey=selectedBody.publishable_key;
  async function confirmWaitBoundary(){
   const request=requests.get(successorId?'successor-confirm':'original-confirm');
   if(request)return request.existingResponse()?'BROWSER_CONFIRM_WAIT':'BROWSER_CONFIRM_REQUEST_NO_RESPONSE';
   const token=tokenRequest;
   if(token){
    const reply=token.existingResponse();
    if(!reply)return 'BROWSER_CONFIRM_TOKEN_NO_RESPONSE';
    if(reply.request()!==token || reply.fromServiceWorker() ||
     reply.url()!=='https://api.stripe.com/v1/confirmation_tokens')return 'BROWSER_CONFIRM_WAIT';
    if(reply.status()!==200)return 'BROWSER_CONFIRM_TOKEN_HTTP';
   }
   return billingBounded(limits,async ()=>{
    await checkManager();
    if(!await trustedPayClick?.evaluate(clicked=>clicked()))return 'BROWSER_CONFIRM_CLICK_UNOBSERVED';
    const validation=manager.locator('[role="alert"]').filter({hasText:/^Check your payment details and try Pay again\.$/});
    if(await validation.isVisible())return 'BROWSER_CONFIRM_FORM_VALIDATION';
    return token?'BROWSER_CONFIRM_REQUEST_NOT_SENT':'BROWSER_CONFIRM_TOKEN_NOT_SENT';
   },1000);
  }
  async function payOnce(card:string){
   checkpoint='BROWSER_WAIT_READY';
   const pay=manager.getByRole('button',{name:'Pay',exact:true});await pay.waitFor({state:'visible',timeout:duration(limits)});
   // Enabled Pay is controlled by the driver's real Element ready event, not iframe insertion.
   await page.waitForFunction(()=>{const m=document.querySelector('generic-subscription-manager');return [...m?.shadowRoot?.querySelectorAll('button')??[]].some(b=>b.textContent?.trim()==='Pay'&&!b.disabled&&!!b.getClientRects().length);},undefined,{timeout:duration(limits)});
   checkpoint='BROWSER_CARD_FIELDS';
   const frame=manager.frameLocator('iframe[title="Secure payment input frame"]');await frame.locator('input[name="number"]').fill(card,{timeout:duration(limits)});await frame.locator('input[name="expiry"]').fill('1230',{timeout:duration(limits)});await frame.locator('input[name="cvc"]').fill('123',{timeout:duration(limits)});
   const postal=frame.locator('input[name="postalCode"]');if(await postal.isVisible())await postal.fill('10001',{timeout:duration(limits)});
   const settlePresentation=async()=>{
    await checkManager();
    while(pinned?.pending){
     await pinned.wait();
     await checkManager();
    }
   };
   if(bound)await settlePresentation();
   if(bound)checkpoint='BROWSER_CONFIRM_FOCUS';
   const payButton=bound?await focusExactButton(pay):undefined;
   if(guarded)trustedPayClick=payButton?await payButton.evaluateHandle(captureTrustedClick):await trustedClick(pay);
   await settlePresentation();
   if(payButton)await requireButtonFocus(payButton);
   // Arm observation after presentation has settled, but before a click can dispatch.
   checkpoint=bound?'BROWSER_CONFIRM_CLICK':'BROWSER_CONFIRM_WAIT';
   const confirmUrl=base+'/'+subscriptionId+'/operations/'+operationId+'/confirmation-attempts/'+attemptId+'/confirm';
   const responseFailure=Symbol(),unobservedClick=Symbol();
   let responseTimedOut=false;
   const confirmed=page.waitForResponse(r=>r.url()===confirmUrl&&r.request().method()==='POST',{timeout:duration(limits)}).catch(error=>{
    responseTimedOut=error instanceof errors.TimeoutError;
    throw responseFailure;
   });void confirmed.catch(()=>{});
   check();tokenRequest=undefined;payInFlight=true;
   let confirmResponse:Response;
   let clickFinished=false;
   try{
    // Activate the exact focused control once, avoiding a pointer sequence across a layout change.
    const activation=payButton?payButton.press('Enter',{timeout:duration(limits)}):pay.click({timeout:duration(limits)});
    const clicking=activation.then(async()=>{
     clickFinished=true;
     if(payButton && !await trustedPayClick!.evaluate(clicked=>clicked()))throw unobservedClick;
    });
    // A timed-out response must close Pay authority even if the click is still pending.
    [,confirmResponse]=await Promise.all([clicking,confirmed]);
   }catch(error){
    payInFlight=false;
    if(error===responseFailure){
     checkpoint=bound&&responseTimedOut&&!clickFinished?'BROWSER_CONFIRM_WAIT_CLICK_PENDING':'BROWSER_CONFIRM_WAIT';
     if(bound && clickFinished && responseTimedOut && !firstFailure && !limits.signal?.aborted && Date.now()<limits.deadline){
      checkpoint=await confirmWaitBoundary().catch(()=> 'BROWSER_CONFIRM_WAIT');
     }
    }
    if(error===unobservedClick)checkpoint='BROWSER_CONFIRM_CLICK_UNOBSERVED';
    throw error;
   }finally{payInFlight=false;}
   checkpoint='BROWSER_CONFIRM_RECEIPT';
   paired(successorId?'successor-confirm':'original-confirm',confirmResponse);
   need(confirmResponse.status()===200);const confirmation=await body(confirmResponse,limits);check();
   need(confirmation.protocol==='SERVER_CONFIRM_V2' && confirmation.attempt_id===attemptId && confirmation.next_action_allowed===!!challenge && confirmation.successor_attempt_id===null);
   return confirmation;
  }
  const confirmation=await payOnce(challenge?'4000002500003155':retryPort?'4000000000000002':'4242424242424242');
  if(retryPort){
   need(confirmation.state==='DECLINED' && confirmation.retry_allowed===true);
   need(await retryPort.acknowledge('confirm',{attemptId,state:'DECLINED',nextActionAllowed:false,retryAllowed:true,successorAttemptId:null})===true);check();
   const prefix=base+'/'+subscriptionId+'/operations/'+operationId+'/confirmation-attempts/';
   checkpoint='BROWSER_RETRY_READY';
   const retryButton=manager.getByRole('button',{name:'Retry payment',exact:true});
   await retryButton.waitFor({state:'visible',timeout:duration(limits)});
   checkpoint='BROWSER_RETRY_FOCUS';
   const exactRetry=await focusExactButton(retryButton);
   trustedRetry=await exactRetry.evaluateHandle(captureTrustedClick);
   await checkManager();await requireButtonFocus(exactRetry);
   const responseFailure=Symbol(),unobservedClick=Symbol();
   const retried=page.waitForResponse(r=>r.url()===prefix+firstAttempt+'/retry' && r.request().method()==='POST',
    {timeout:duration(limits)}).catch(()=>{throw responseFailure;});
   const nextSession=page.waitForResponse(r=>r.url()===prefix+successorId+'/client-session' && r.request().method()==='POST',{timeout:duration(limits)});
   void retried.catch(()=>{});void nextSession.catch(()=>{});
   checkpoint='BROWSER_RETRY_GESTURE';
   check();retryGestureInFlight=true;
   let reply:Response;
   try{
    const activating=exactRetry.press('Enter',{timeout:duration(limits)}).then(async()=>{
     if(!await trustedRetry!.evaluate(clicked=>clicked()))throw unobservedClick;
    });
    [,reply]=await Promise.all([activating,retried]);
   }catch(error){
    if(error===responseFailure)checkpoint='BROWSER_RETRY_WAIT';
    if(error===unobservedClick)checkpoint='BROWSER_RETRY_CLICK_UNOBSERVED';
    throw error;
   }finally{retryGestureInFlight=false;}
   checkpoint='BROWSER_RETRY_RECEIPT';
   paired('retry',reply);need(reply.status()===200);
   const retry=await body(reply,limits);check();
   need(retry.protocol==='SERVER_CONFIRM_V2' && retry.attempt_id===firstAttempt && retry.state==='DECLINED' &&
    retry.retry_allowed===false && uuid(retry.successor_attempt_id) && retry.successor_attempt_id!==firstAttempt);
   successorId=retry.successor_attempt_id as string;
   need(await retryPort.acknowledgeRetry({attemptId:firstAttempt,successorAttemptId:successorId})===true);check();successorAck.resolve();
   checkpoint='BROWSER_RETRY_SUCCESSOR_SESSION';
   const session=await nextSession;paired('successor-session',session);need(session.status()===200);
   sessionContext(await body(session,limits),true);check();attemptId=successorId;
   successorPresentationReady.resolve();
   const observed=await payOnce('4242424242424242');
   need(observed.state==='OBSERVE' && observed.retry_allowed===false);
   need(await retryPort.acknowledgeSuccessor({attemptId,state:'OBSERVE',nextActionAllowed:false})===true);check();
  }else if(challenge && sca){
   need(confirmation.state==='REQUIRES_ACTION' && confirmation.retry_allowed===false);
   const scope={subscriptionId,operationId,attemptId};
   need(await port.acknowledge('confirm',{attemptId,state:'REQUIRES_ACTION',nextActionAllowed:true,retryAllowed:false,successorAttemptId:null})===true);
   await checkManager();need(await challenge.port.beforeChallenge(scope)===true);
   challengeDispatched=true;
   winning.resolve();
   checkpoint='BROWSER_CHALLENGE_WAIT';
   await sca.attend();await checkManager();need(await challenge.port.acknowledgeChallenge(scope)===true);
  }else{
   need(confirmation.state==='OBSERVE' && confirmation.retry_allowed===false);
   await port.acknowledge('confirm',{attemptId,state:'OBSERVE',nextActionAllowed:false});
  }
  if(cancellationPort)checkpoint='BROWSER';
  for(;;){billingCheck(limits);if(failure)fail();const settled=await manager.evaluate((element:Element)=>{const state=(element as Element&{state?:{phase:string;subscription?:{subscriptionId:string;billingStatus:string;access:{allowed:boolean;reason:string}};operation?:{status:string}}}).state;return {phase:state?.phase,id:state?.subscription?.subscriptionId,status:state?.subscription?.billingStatus,allowed:state?.subscription?.access.allowed,reason:state?.subscription?.access.reason,operation:state?.operation?.status};});
   need(!['unknown','error','expired'].includes(settled.phase??''));if(settled.id===subscriptionId&&settled.status==='ACTIVE'&&settled.allowed&&settled.reason==='PAID')break;
   await manager.getByRole('button',{name:'Refresh status',exact:true}).click({timeout:duration(limits)});await billingBounded(limits,()=>new Promise(r=>setTimeout(r,250)),1000);
  }
  need(!failure&&createPosts===1&&confirmPosts===(retryPort?2:1)&&selectPosts===1&&challengePosts===1&&verifyPosts===1);
  if(cancellationPort){
   cancellation=cancellationJourney(page,manager,cancellationPort,binding,authority,(customer as Record<string,string>).id!,
    subscriptionId,operationId,limits,check,recordFailure,redirects?.authorize,pinnedCancellation?checkManager:undefined);
   await cancellation.run();
   await billingBounded(limits,()=>Promise.all(pending));check();
  }
  if(bound){await billingBounded(limits,()=>Promise.all(pending));await checkManager();}
  authority='';
 }catch{recordFailure();}
 finally{
  abandoned=true;limits.signal?.removeEventListener('abort',abort);lifecycle.abort();
  await refusedOrigin?.issuerRelation;
  if(context)try{
   await billingBounded({deadline:Date.now()+15000},async()=>{await close(context!);await Promise.allSettled(pending);});
   cleanupConfirmed=true;
  }catch{recordFailure('BROWSER_CLEANUP');}
 }
 if(firstFailure || guarded && failure){
  if(!guarded)fail();
  const error=acceptanceBrowserFailure(firstFailure??checkpoint);
  if(refusedOrigin)rememberBrowserProviderOrigin(error,refusedOrigin.runId,refusedOrigin.expectedSource,refusedOrigin.origin,await refusedOrigin.issuerRelation);
  finishBrowserCleanup(error,cleanupConfirmed);
  throw error;
 }
 return true;
}
