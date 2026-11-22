import type {Browser,Route,Request,Response,BrowserContext,Page,Locator,Frame,JSHandle} from '@playwright/test';
import type {BillingPort,BillingBinding,BillingLimits,BillingCancellationPort} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {ManagerState} from '../../../../frontend/sdk/src/subscriptions/manager-state';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
// @ts-ignore Native Node and bundlers use the same shared provider origin policy.
import {providerOrigin,providerNetworkOrigin} from './stripe-provider-origin.ts';
// @ts-ignore Node's offline test entry consumes source.
import {enterPrivateText} from './private-entry.ts';
const fail=():never=>{throw new Error('billing_BROWSER');};
const need=(v:unknown):void=>{if(!v)fail();};
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>object(v)&&Object.keys(v).sort().join(',')===[...keys].sort().join(',');
const duration=(l:BillingLimits)=>{billingCheck(l);return Math.min(45000,l.deadline-Date.now());};
async function body(response:Response,l:BillingLimits):Promise<Record<string,unknown>>{const bytes=await billingBounded(l,()=>response.body());need(bytes.length<=65536);const v:unknown=JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes));need(object(v));return v as Record<string,unknown>;}
/** Observes and persists the SDK's own key. Never changes body/headers, fetches, replays or fulfills a financial request. */
export async function forwardBillingDispatch(route:Pick<Route,'request'|'continue'>,port:Pick<BillingPort,'beforeDispatch'>,binding:BillingBinding,authority:string,kind:'create'|'confirm',identity:Record<string,string>|undefined,limits:BillingLimits,confirmation?:{token:string;check:()=>void}):Promise<void>{
 try{billingCheck(limits);const request=route.request(),base=binding.endpoints.backend+'/api/v1/customer/subscriptions';
  const expected=kind==='create'?base:base+'/'+identity?.subscriptionId+'/operations/'+identity?.operationId+'/confirmation-attempts/'+identity?.attemptId+'/confirm';
  need(request.url()===expected&&request.method()==='POST'&&!request.redirectedFrom());const headers=await request.allHeaders(),value:unknown=JSON.parse(request.postData()??'');
  need(headers.authorization===authority&&/^Bearer [A-Za-z0-9_-]{43}$/.test(authority)&&headers.origin===binding.endpoints.demo&&headers['content-type']==='application/json'&&!headers.cookie&&typeof headers['idempotency-key']==='string'&&/^[A-Za-z0-9:_-]{1,255}$/.test(headers['idempotency-key']));
  if(kind==='create')need(exact(value,['price_id','recurring_consent'])&&value.price_id===binding.priceId&&value.recurring_consent===true);
  else need(exact(value,['capability','confirmation_token'])&&typeof value.capability==='string'&&/^bca2_[A-Za-z0-9_-]{43}$/.test(value.capability)&&typeof value.confirmation_token==='string'&&/^ctoken_[A-Za-z0-9]{1,200}$/.test(value.confirmation_token)&&(!confirmation||value.confirmation_token===confirmation.token));
  confirmation?.check();
  await port.beforeDispatch(kind,headers['idempotency-key']!,identity);
  billingCheck(limits);confirmation?.check();await route.continue();billingCheck(limits);
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
 authority:string, customerId:string, subscriptionId:string, createId:string, limits:BillingLimits, check:()=>void) {
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
  armed=intent;
  try {
   await click(kind==='schedule'?'Confirm cancellation':'Withdraw cancellation');
   const response=await receipt;
   check();
   need(intent.forwarded && response.request()===intent.request && !response.fromServiceWorker() && response.status()===202);
   const ack=await body(response,limits);
   need(exact(ack,['subscription_id','operation_id','status','action_available','next_action','browser_confirm_allowed']) &&
    ack.subscription_id===subscriptionId && uuid(ack.operation_id) && !operations.has(ack.operation_id) &&
    (ack.status==='PROCESSING' || ack.status==='SUCCEEDED') && ack.next_action===null && ack.action_available===false && ack.browser_confirm_allowed===false);
   const operationId=ack.operation_id as string;
   operations.add(operationId);
   armed=undefined;
   check();need(await port.verifyCancellation(kind,operationId)===true);
   return await waitForProjection(kind,operationId,previous);
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
  async forward(route:Route){
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
   check();need(await port.beforeCancellation(intent.kind,key!)===true);check();
   intent.forwarded=true;
   await route.continue();check();
  }
 };
}
/** Genuine demo OTP + one manager + visible ready provider Element. Only original server reads settle it. */
export function runInitialBillingBrowser(browser:Browser,port:BillingPort,limits:BillingLimits):Promise<true>{
 return runBillingBrowser(browser,port,limits);
}
export function runBillingCancellationBrowser(browser:Browser,port:BillingCancellationPort,limits:BillingLimits):Promise<true>{
 return runBillingBrowser(browser,port,limits,port);
}
async function runBillingBrowser(browser:Browser,port:BillingPort,limits:BillingLimits,cancellationPort?:BillingCancellationPort):Promise<true>{
 let context:BrowserContext|undefined,abandoned=false,closing:Promise<void>|undefined,failure=false;
 const close=(ctx:BrowserContext)=>{closing??=ctx.close();void closing.catch(()=>{});return closing;};
 const opening=browser.newContext({serviceWorkers:'block',acceptDownloads:false});void opening.then(c=>{if(abandoned)return close(c);},()=>{}).catch(()=>{});
 const abort=()=>{abandoned=true;if(context)void close(context);};limits.signal?.addEventListener('abort',abort,{once:true});
 const pending=new Set<Promise<void>>();
 try{
  context=await billingBounded(limits,()=>opening);const page=await billingBounded(limits,()=>context!.newPage()),binding=port.resources();
  let authority='',subscriptionId='',operationId='',attemptId='',createPosts=0,confirmPosts=0,selectPosts=0,challengePosts=0,verifyPosts=0;
  const base=binding.endpoints.backend+'/api/v1/customer/subscriptions',otp=binding.endpoints.backend+'/api/v1/public/customer-auth/email-otp/challenges';
  let acknowledgeCreate!:()=>void;const created=new Promise<void>(r=>acknowledgeCreate=r);
  let cancellation:ReturnType<typeof cancellationJourney>|undefined, challengeId:string|undefined;
  let payInFlight=false, trustedPayClick:JSHandle<()=>boolean>|undefined, tokenRequest:Request|undefined, publishableKey:string|undefined;
  async function providerTraffic(route:Route,url:URL) {
   const request=route.request(),method=request.method(),navigation=request.isNavigationRequest();
   need(providerNetworkOrigin(url) && !request.redirectedFrom() && ['GET','POST','OPTIONS'].includes(method));
   const script=stripeScriptOrigin(url),api=url.hostname==='api.stripe.com';
   const support=url.hostname==='r.stripe.com'||url.hostname==='merchant-ui-api.stripe.com';
   const preflight=method==='OPTIONS';
   const tokenPath=api && url.pathname==='/v1/confirmation_tokens';
   const original=stripeFrames(page,request,navigation,script && method==='GET',binding.endpoints.demo);
   need(original && (!navigation || script && method==='GET'));
   if(tokenPath)need(payInFlight && trustedPayClick && await trustedPayClick.evaluate(clicked=>clicked()));
   const headers=await request.allHeaders();
   check();
   const current=stripeFrames(page,request,navigation,script && method==='GET',binding.endpoints.demo);
   need(current && original && current.target===original.target && current.frames.size===original.frames.size &&
    [...original.frames].every(([frame,href])=>current.frames.get(frame)===href));
   need(!headers.cookie && !headers['proxy-authorization'] &&
    (!headers.authorization || api && !preflight && publishableKey && headers.authorization==='Bearer '+publishableKey));
   const intended=preflight?headers['access-control-request-method']:method;
   need(intended==='GET'||intended==='POST');
   if(!script && headers.origin)need(headers.origin===new URL(request.frame().url()).origin);
   if(script)need(intended==='GET');
   else if(support){
    // These two network-only origins are approved for uncredentialed support requests.
    // Never forward API resources or navigation through a support origin.
    need(!navigation && !/^\/(?:v\d+|api)(?:\/|$)/i.test(url.pathname) && !url.pathname.includes('%'));
   }else if(api && !navigation && !cancellation && selectPosts===1){
    if(tokenPath){
     need(payInFlight && !tokenRequest && intended==='POST' && !url.search);
     if(!preflight)tokenRequest=request;
    }else need(url.pathname==='/v1/elements/sessions');
   }else fail();
   await route.fallback();
  }
  const check=()=>{billingCheck(limits);if(failure||abandoned)fail();};
  const invalidate=()=>{failure=true;abort();};
  if(cancellationPort){
   context.on('page',invalidate);
   await context.routeWebSocket('**/*',socket=>{try{socket.close();}finally{invalidate();}});
  }
  await context.route(cancellationPort?'**/*':binding.endpoints.backend+'/**',route=>{
   const work=(async()=>{try{
    billingCheck(limits);if(failure)fail();const req=route.request(),url=req.url(),method=req.method();
    if(cancellationPort){
     const target=new URL(url);
     if(target.origin===binding.endpoints.demo){need(method==='GET' && !req.redirectedFrom());await route.fallback();return;}
     if(target.origin!==binding.endpoints.backend){await providerTraffic(route,target);return;}
     if(cancellation && !['GET','OPTIONS'].includes(method)){await cancellation.forward(route);return;}
    }
    if(method==='OPTIONS'){await route.continue();return;}
    if(method==='GET'){await route.continue();return;}
    if(method!=='POST')fail();
    if(cancellationPort)need(req.frame()===page.mainFrame() && !req.isNavigationRequest() && !req.redirectedFrom());
    if(url===otp){need(++challengePosts===1);const v:unknown=JSON.parse(req.postData()??'');need(exact(v,['site_key','email','intent'])&&v.site_key===binding.siteKey&&v.email===binding.email&&v.intent==='AUTHENTICATE');await route.continue();return;}
    if(url.startsWith(otp+'/')&&url.endsWith('/verify')){need(++verifyPosts===1 && (!cancellationPort || challengeId && url===otp+'/'+challengeId+'/verify'));await route.continue();return;}
    if(url===base){need(++createPosts===1);await forwardBillingDispatch(route,port,binding,authority,'create',undefined,limits);return;}
    if(cancellationPort && !subscriptionId)need(createPosts===1 && url.startsWith(base+'/') && url.endsWith('/confirmation-session'));
    await billingBounded(limits,()=>created);const original=base+'/'+subscriptionId+'/operations/'+operationId;
    if(url===original+'/confirmation-session'){
     need(++selectPosts===1);const v:unknown=JSON.parse(req.postData()??''),h=await req.allHeaders();need(exact(v,['action_token'])&&typeof v.action_token==='string'&&/^bga1_[A-Za-z0-9_-]{43}$/.test(v.action_token)&&h.authorization===authority&&h.origin===binding.endpoints.demo&&!h.cookie);await route.continue();return;
    }
    need(url===original+'/confirmation-attempts/'+attemptId+'/confirm'&&++confirmPosts===1);
    let confirmation:{token:string;check:()=>void}|undefined;
    if(cancellationPort){
     // The SDK cannot legitimately confirm until its own token POST has received a response.
     // Response headers precede the SDK's confirm. Our read of its JSON body may still be pending.
     const tokenResponse=tokenRequest?.existingResponse();
     if(!payInFlight || !tokenResponse || tokenResponse.request()!==tokenRequest ||
       tokenResponse.url()!=='https://api.stripe.com/v1/confirmation_tokens' || tokenResponse.fromServiceWorker() || tokenResponse.status()!==200)return fail();
     const token=await body(tokenResponse,limits);
     check();
     if(token.object!=='confirmation_token' || token.livemode!==false || typeof token.id!=='string' || !/^ctoken_[A-Za-z0-9]{1,200}$/.test(token.id))return fail();
     confirmation={token:token.id,check};
    }
    await forwardBillingDispatch(route,port,binding,authority,'confirm',{subscriptionId,operationId,attemptId},limits,confirmation);
   }catch{failure=true;abort();await route.abort('failed').catch(()=>{});}})();pending.add(work);void work.finally(()=>pending.delete(work));return work;
  });
  const url=new URL('/',binding.endpoints.demo);for(const [k,v] of Object.entries({api_base_url:binding.endpoints.backend,site_key:binding.siteKey,subscription_product_1_id:binding.productId,subscription_price_1_id:binding.priceId,locale:'en'}))url.searchParams.set(k,v);
  await page.goto(url.href,{timeout:duration(limits)});
  if(cancellationPort){
   need(new URL(page.url()).origin===binding.endpoints.demo);
   page.on('framenavigated',frame=>{if(frame===page.mainFrame())invalidate();});
  }
  const widget=page.locator('generic-email-otp').first();
  await enterPrivateText(widget.getByRole('textbox',{name:'Email',exact:true}),binding.email);
  const challenge=page.waitForResponse(r=>r.url()===otp&&r.request().method()==='POST',{timeout:duration(limits)});void challenge.catch(()=>{});await widget.getByRole('button',{name:'Request code',exact:true}).click({timeout:duration(limits)});
  const challengeResponse=await challenge;need(challengeResponse.status()===202);const challengeBody=await body(challengeResponse,limits);need(uuid(challengeBody.challenge_id));challengeId=challengeBody.challenge_id as string;
  await port.withOtpCode(async code=>{await enterPrivateText(widget.getByRole('textbox',{name:'Six-digit code',exact:true}),code);return true as const;},limits);
  const verification=page.waitForResponse(r=>r.url()===otp+'/'+challengeBody.challenge_id+'/verify'&&r.request().method()==='POST',{timeout:duration(limits)});void verification.catch(()=>{});
  const baseline=page.waitForResponse(r=>r.url().startsWith(base+'?')&&r.request().method()==='GET',{timeout:duration(limits)});void baseline.catch(()=>{});
  await widget.getByRole('button',{name:'Verify code',exact:true}).click({timeout:duration(limits)});const response=await verification;need(response.status()===200);const session=await body(response,limits),customer=session.customer;
  need(object(customer)&&uuid(customer.id)&&customer.status==='ACTIVE'&&session.site_id===binding.siteId&&Array.isArray(session.scopes)&&session.scopes.includes('SUBSCRIPTION_READ')&&session.scopes.includes('SUBSCRIPTION_MANAGE')&&typeof session.session_token==='string'&&/^[A-Za-z0-9_-]{43}$/.test(session.session_token));
  authority='Bearer '+session.session_token;await port.acknowledgeCustomer({customerId:(customer as Record<string,string>).id!,siteId:binding.siteId});
  const before=await body(await baseline,limits);need(Array.isArray(before.items)&&before.items.length===0&&before.next_cursor===null);
  const manager=page.locator('generic-subscription-manager').first();await manager.getByRole('checkbox',{name:'I consent to recurring payments',exact:true}).check({timeout:duration(limits)});
  const createResponse=page.waitForResponse(r=>r.url()===base&&r.request().method()==='POST',{timeout:duration(limits)});void createResponse.catch(()=>{});
  await manager.getByRole('button',{name:'Create subscription',exact:true}).click({timeout:duration(limits)});const receipt=await createResponse;need(receipt.status()===202);const original=await body(receipt,limits);need(uuid(original.subscription_id)&&uuid(original.operation_id)&&['PROCESSING','REQUIRES_ACTION','RESERVED'].includes(String(original.status)));
  subscriptionId=original.subscription_id as string;operationId=original.operation_id as string;await port.acknowledge('create',{subscriptionId,operationId});
  const selection=page.waitForResponse(r=>r.url()===base+'/'+subscriptionId+'/operations/'+operationId+'/confirmation-session'&&r.request().method()==='POST',{timeout:duration(limits)});void selection.catch(()=>{});acknowledgeCreate();
  const selected=await selection;need(selected.status()===200);const selectedBody=await body(selected,limits);need(selectedBody.protocol==='SERVER_CONFIRM_V2'&&selectedBody.provider==='stripe'&&selectedBody.flow==='PAYMENT'&&uuid(selectedBody.attempt_id));attemptId=selectedBody.attempt_id as string;
  if(cancellationPort && typeof selectedBody.publishable_key==='string' && /^pk_test_[A-Za-z0-9_]+$/.test(selectedBody.publishable_key))publishableKey=selectedBody.publishable_key;
  const pay=manager.getByRole('button',{name:'Pay',exact:true});await pay.waitFor({state:'visible',timeout:duration(limits)});
  // Enabled Pay is controlled by the driver's real Element ready event, not iframe insertion.
  await page.waitForFunction(()=>{const m=document.querySelector('generic-subscription-manager');return [...m?.shadowRoot?.querySelectorAll('button')??[]].some(b=>b.textContent?.trim()==='Pay'&&!b.disabled&&!!b.getClientRects().length);},undefined,{timeout:duration(limits)});
  const frame=manager.frameLocator('iframe[title="Secure payment input frame"]');await frame.locator('input[name="number"]').fill('4242424242424242',{timeout:duration(limits)});await frame.locator('input[name="expiry"]').fill('1230',{timeout:duration(limits)});await frame.locator('input[name="cvc"]').fill('123',{timeout:duration(limits)});
  const postal=frame.locator('input[name="postalCode"]');if(await postal.isVisible())await postal.fill('10001',{timeout:duration(limits)});
  const confirmed=page.waitForResponse(r=>r.url()===base+'/'+subscriptionId+'/operations/'+operationId+'/confirmation-attempts/'+attemptId+'/confirm'&&r.request().method()==='POST',{timeout:duration(limits)});void confirmed.catch(()=>{});
  if(cancellationPort)trustedPayClick=await pay.evaluateHandle(button=>{
   let clicked=false;
   button.addEventListener('click',event=>{clicked=event.isTrusted;},{once:true,capture:true});
   return ()=>clicked;
  });
  payInFlight=true;await pay.click({timeout:duration(limits)});const confirmResponse=await confirmed;payInFlight=false;need(confirmResponse.status()===200);const confirmation=await body(confirmResponse,limits);need(confirmation.protocol==='SERVER_CONFIRM_V2'&&confirmation.attempt_id===attemptId&&confirmation.state==='OBSERVE'&&confirmation.next_action_allowed===false&&confirmation.retry_allowed===false&&confirmation.successor_attempt_id===null);
  await port.acknowledge('confirm',{attemptId,state:'OBSERVE',nextActionAllowed:false});
  for(;;){billingCheck(limits);if(failure)fail();const settled=await manager.evaluate((element:Element)=>{const state=(element as Element&{state?:{phase:string;subscription?:{subscriptionId:string;billingStatus:string;access:{allowed:boolean;reason:string}};operation?:{status:string}}}).state;return {phase:state?.phase,id:state?.subscription?.subscriptionId,status:state?.subscription?.billingStatus,allowed:state?.subscription?.access.allowed,reason:state?.subscription?.access.reason,operation:state?.operation?.status};});
   need(!['unknown','error','expired'].includes(settled.phase??''));if(settled.id===subscriptionId&&settled.status==='ACTIVE'&&settled.allowed&&settled.reason==='PAID')break;
   await manager.getByRole('button',{name:'Refresh status',exact:true}).click({timeout:duration(limits)});await billingBounded(limits,()=>new Promise(r=>setTimeout(r,250)),1000);
  }
  need(!failure&&createPosts===1&&confirmPosts===1&&selectPosts===1&&challengePosts===1&&verifyPosts===1);
  if(cancellationPort){
   cancellation=cancellationJourney(page,manager,cancellationPort,binding,authority,(customer as Record<string,string>).id!,subscriptionId,operationId,limits,check);
   await cancellation.run();
   await billingBounded(limits,()=>Promise.all(pending));check();
  }
  authority='';return true;
 }catch{return fail();}
 finally{abandoned=true;limits.signal?.removeEventListener('abort',abort);if(context)await billingBounded({deadline:Date.now()+15000},async()=>{await close(context!);await Promise.allSettled(pending);});if(cancellationPort && failure)fail();}
}
