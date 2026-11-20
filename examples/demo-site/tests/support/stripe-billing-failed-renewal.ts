import type {Browser,BrowserContext,Frame,Page,Route} from '@playwright/test';
import type {BillingBinding,BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {BillingFailedRenewalJourney,BillingFailedRenewalMethodPort,BillingFirstPaymentPort,FailedMethodSession,FirstPaymentPrepared} from '../../../../infrastructure/stripe-billing-first-payment.mjs';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
import {browserFailure,browserDiagnostic,finishBrowserCleanup,rememberBrowserProviderOrigin} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
// @ts-ignore Explicit source for dependency-free Node TS runner.
import {createFirstPaymentBridge} from './stripe-billing-first-payment.ts';
// @ts-ignore Explicit source for dependency-free Node TS runner.
import {enterPrivateText} from './private-entry.ts';
// @ts-ignore Explicit source for dependency-free Node TS runner.
import {providerOrigin,providerNetworkOrigin} from './stripe-provider-origin.ts';

const pagePath = '/billing-failed-renewal.html';
const failed = () => new Error('failed_renewal_BROWSER');
function need(ok:unknown):asserts ok {if(!ok)throw failed();}
const uuid = (v:unknown):v is string => typeof v==='string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v);
const loopback = (v:string) => /^http:\/\/127\.0\.0\.1:[1-9]\d{0,4}$/.test(v) && new URL(v).origin===v;
function fields(value:unknown,names:string[]):Record<string,string> {
  need(value && Object.getPrototypeOf(value)===Object.prototype && Reflect.ownKeys(value).length===names.length);
  const result:Record<string,string>={};
  for(const name of names){
    const d=Object.getOwnPropertyDescriptor(value,name);
    need(d?.enumerable && Object.hasOwn(d,'value') && typeof d.value==='string' && d.value.length<=8192);
    result[name]=d.value;
  }
  return result;
}
function body(raw:string|null,names:string[]) {
  need(raw && raw.length<=8192);
  const value:unknown=JSON.parse(raw);
  // The real SDK writes JSON.stringify on a flat data object. Reject duplicate
  // and escaped keys before the decoded body grants access to a fixed port.
  need(JSON.stringify(value)===raw);
  return fields(value,names);
}
const staticAssets=[
  {resource:'image',suffix:'.png',media:['image/png']},
  {resource:'image',suffix:'.svg',media:['image/svg+xml']},
  {resource:'stylesheet',suffix:'.css',media:['text/css']},
  {resource:'script',suffix:'.js',media:['text/javascript','application/javascript']},
];
function providerRefusal(url:URL) {
  if(providerNetworkOrigin(url))return undefined;
  if(url.protocol!=='https:')return 'BROWSER_PROVIDER_ORIGIN_SCHEME';
  if(url.port)return 'BROWSER_PROVIDER_ORIGIN_PORT';
  if(url.hostname==='m.stripe.com')return 'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M';
  if(url.hostname==='r.stripe.com')return 'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_R';
  if(url.hostname==='stripe.com'||url.hostname.endsWith('.stripe.com'))return 'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER';
  if(url.hostname==='stripe.network'||url.hostname.endsWith('.stripe.network'))return 'BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK';
  return 'BROWSER_PROVIDER_ORIGIN_OTHER';
}
function stripeFrameChain(route:Route,page:Page) {
  // Header retrieval is asynchronous. Remember which documents initiated the request.
  try{
    const chain:{frame:Frame;href:string}[]=[],main=page.mainFrame();
    for(let frame:Frame|null=route.request().frame();frame!==main;frame=frame.parentFrame()){
      if(!frame || frame.page()!==page || frame.isDetached())return undefined;
      const href=frame.url(),url=new URL(href);
      if(!providerOrigin(url) || !(url.hostname==='js.stripe.com'||url.hostname.endsWith('.js.stripe.com')))return undefined;
      chain.push({frame,href});
    }
    return chain.length?chain:undefined;
  }catch{return undefined;}
}
async function fetchStaticAsset(url:URL,media:string[],origin:string|undefined,signal:AbortSignal) {
  const controller=new AbortController();
  try {
    const response=await fetch(url,{method:'GET',redirect:'error',credentials:'omit',referrerPolicy:'no-referrer',
      headers:origin?{Origin:origin}:undefined,signal:AbortSignal.any([signal,controller.signal])});
    const type=response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase();
    need(response.status===200 && !response.redirected && type && media.includes(type) && response.body);
    // Playwright can add CORS headers on fulfill when ACAO is absent.
    // Never fulfill a CORS request the upstream response did not authorize.
    const allowedOrigin=response.headers.get('access-control-allow-origin');
    need(!origin || allowedOrigin===origin || allowedOrigin==='*');
    const maxBytes=1024*1024;
    const reader=response.body.getReader(),chunks:Uint8Array[]=[];
    let size=0;
    while(true){
      const {value,done}=await reader.read();
      if(done)break;
      size+=value.byteLength;need(size<=maxBytes);chunks.push(value);
    }
    need(size>0);
    const headers:Record<string,string>={'Content-Type':response.headers.get('content-type')!,
      'X-Content-Type-Options':'nosniff','Cache-Control':'no-store'};
    for(const name of ['access-control-allow-origin','access-control-allow-credentials','cross-origin-resource-policy',
      'cross-origin-embedder-policy','content-security-policy','content-security-policy-report-only','content-disposition','referrer-policy']){
      const header=response.headers.get(name);if(header!==null)headers[name]=header;
    }
    return {headers,body:Buffer.concat(chunks,size)};
  } finally {controller.abort();}
}
/** A single owned page. Neither phase accepts a target from the page or chooses a provider operation. */
export function runFailedRenewalBrowser(browser:Pick<Browser,'newContext'>,runAbort:AbortController,matchesStaticOrigin?:(origin:string)=>boolean):BillingFailedRenewalJourney {
  need(runAbort instanceof AbortController && !runAbort.signal.aborted);
  let state:'NEW'|'INITIAL'|'HELD'|'METHOD'|'DONE'|'CLOSED'='NEW';
  let opening:Promise<BrowserContext>|undefined, closing:Promise<void>|undefined;
  let context:BrowserContext|undefined, page:Page|undefined;
  let binding:BillingBinding, whole:BillingLimits, stage:BillingLimits;
  let initialPort:BillingFirstPaymentPort|undefined, methodPort:BillingFailedRenewalMethodPort|undefined;
  let initial:FirstPaymentPrepared|undefined, initialBridge:ReturnType<typeof createFirstPaymentBridge>|undefined;
  let method:Awaited<ReturnType<BillingFailedRenewalMethodPort['prepareMethod']>>|undefined, selected:FailedMethodSession|undefined;
  let token='',sessionExpiry=Infinity,challenge='',code='';
  let step:'CHALLENGE'|'CHALLENGE_ID'|'VERIFY'|'PREPARE'|'ACTION'|'SESSION'|'CONFIRM'|'BUSY'|'ACK'='CHALLENGE';
  let readiness:'INITIAL'|'METHOD'|undefined;
  let inFlight:{phase:'INITIAL'|'METHOD';step:'ACTION'|'SESSION'}|undefined;
  let navigation=0,manualArmed=false,expires:ReturnType<typeof setTimeout>|undefined;
  const lifetime=new AbortController(),pending=new Set<Promise<unknown>>();
  const usedKeys=new Set<string>();
  let finalDelivery:Promise<void>|undefined;
  let checkpoint='BROWSER_CONTEXT',firstFailure:Error|undefined,preparationActive=false;
  function latch(code:string):Error {
    firstFailure??=browserFailure(code);
    return firstFailure;
  }
  async function at<T>(code:string,work:()=>Promise<T>):Promise<T> {
    // DOM click completion can precede its async preparation binding. READY
    // owns its own rejection, while page loss still belongs to active prepare.
    if(!(preparationActive && code==='BROWSER_READY_PHASE'))checkpoint=code;
    try{return await work();}catch{throw latch(code);}
  }
  const track=<T>(work:Promise<T>)=>{
    pending.add(work);void work.then(()=>pending.delete(work),()=>pending.delete(work));return work;
  };
  function stop() {
    state='CLOSED';lifetime.abort();clearTimeout(expires);
    readiness=undefined;inFlight=undefined;
    whole?.signal?.removeEventListener('abort',stop);
    token='';code='';challenge='';initial=undefined;initialBridge=undefined;method=undefined;selected=undefined;initialPort=undefined;methodPort=undefined;
    usedKeys.clear();
    if(opening && !closing){
      closing=opening.then(c=>c.close());
      void closing.catch(()=>{});
    }
  }
  function lost() {
    // Intentional owner teardown calls stop before closing the context. A real
    // page loss while held must revoke financial stages outside this helper.
    if(state==='CLOSED')return;
    latch(checkpoint);
    stop();
    runAbort.abort();
  }
  function current() {
    need(state!=='CLOSED');billingCheck(whole);billingCheck(stage);
    need(Date.now()<sessionExpiry && !lifetime.signal.aborted);
  }
  function scoped() {
    current();
    return {deadline:Math.min(whole.deadline,stage.deadline,sessionExpiry),
      signal:AbortSignal.any([lifetime.signal,...(stage.signal?[stage.signal]:[])])};
  }
  function assertPage() {current();need(page && page.mainFrame().url()===binding.endpoints.demo+pagePath);}
  const timeout=()=>Math.min(45000,scoped().deadline-Date.now());
  async function waitPhase(expected:string,manual=false) {
    const max=manual?Math.min(180000,scoped().deadline-Date.now()):timeout();
    await page!.waitForFunction(value=>[value,'STOPPED'].includes(document.body.dataset.phase??''),expected,{timeout:max});
    assertPage();need(await page!.locator('body').getAttribute('data-phase')===expected);
  }
  async function awaitOperator(which:'initial'|'method') {
    const ready=which==='initial'?'READY':'METHOD_READY', label=which==='initial'?'Pay':'Confirm test method';
    await at('BROWSER_READY_PHASE',()=>waitPhase(ready));
    readiness=undefined;
    const button=page!.getByRole('button',{name:label,exact:true});
    await at('BROWSER_READY_BUTTON',async()=>need(!(await button.isEnabled())));
    const frame=page!.locator('#provider').frameLocator('iframe[title="Secure payment input frame"]');
    for(const [name,value,code] of [['number','4242424242424242','BROWSER_CARD_NUMBER'],['expiry','1230','BROWSER_CARD_EXPIRY'],['cvc','123','BROWSER_CARD_CVC']] as const)
      await at(code,()=>frame.locator('input[name="'+name+'"]').fill(value,{timeout:timeout()}));
    const postal=frame.locator('input[name="postalCode"]');
    if(await at('BROWSER_POSTAL_CHECK',()=>postal.isVisible()))await at('BROWSER_POSTAL_FILL',()=>postal.fill('10001',{timeout:timeout()}));
    assertPage();need(step==='CONFIRM');
    manualArmed=true;
    await at('BROWSER_MANUAL_PAY_ARM',async()=>need(await button.evaluate((b,phase)=>{
      if(!(b instanceof HTMLButtonElement)||!b.disabled||document.body.dataset.phase!==phase)return false;
      b.disabled=false;return true;
    },ready)));
    await at('BROWSER_MANUAL_PAY_WAIT',()=>waitPhase(which==='initial'?'OBSERVE':'METHOD_CONFIRMED',true));
    need((step as string)==='ACK' && finalDelivery);
    await finalDelivery;assertPage();
    finalDelivery=undefined;
    manualArmed=false;
  }
  const otpUrl=()=>binding.endpoints.backend+'/api/v1/public/customer-auth/email-otp/challenges';
  function expected() {
    if(state==='INITIAL' && step==='CHALLENGE')return [otpUrl(),'POST'];
    if(state==='INITIAL' && step==='VERIFY')return [otpUrl()+'/'+challenge+'/verify','POST'];
    const subscription=state==='INITIAL'?initial?.subscription_id:method?.subscriptionId;
    const operation=state==='INITIAL'?initial?.operation_id:method?.operationId;
    if(!subscription||!operation||!['INITIAL','METHOD'].includes(state))return [];
    const base=binding.endpoints.backend+'/api/v1/customer/subscriptions/'+subscription+'/operations/'+operation;
    switch(step){
      case 'ACTION': return [base+'/action','GET'];
      case 'SESSION': return [base+'/confirmation-session','POST'];
      case 'CONFIRM': return [base+(state==='METHOD'?'/action-confirmations':'/confirmation-attempts/'+initial!.session.attempt_id+'/confirm'),'POST'];
      default:return [];
    }
  }
  async function routeRequest(route:Route) {
    let routeCheckpoint=checkpoint;
    let refusedOrigin:string|undefined;
    try {
      current();
      const request=route.request(),url=new URL(request.url());
      const renderPhase=(state==='INITIAL'||state==='METHOD') && step==='CONFIRM'?state:undefined;
      // Select backend provenance before even reading headers or delivering a
      // preflight. Non-backend refusals get a fixed route boundary.
      if(url.origin===binding.endpoints.backend && (step==='ACTION'||step==='SESSION'||step==='CONFIRM'))
        routeCheckpoint=step==='ACTION'?'BROWSER_ACTION_BRIDGE':step==='SESSION'?'BROWSER_SESSION_BRIDGE':'BROWSER_CONFIRM_BRIDGE';
      if(url.origin!==binding.endpoints.backend)routeCheckpoint=url.origin===binding.endpoints.demo?'BROWSER_ASSET_ROUTE':'BROWSER_PROVIDER_ROUTE';
      const provider=url.origin!==binding.endpoints.backend && url.origin!==binding.endpoints.demo;
      const refusal=provider?providerRefusal(url):undefined;
      const staticTarget=refusal==='BROWSER_PROVIDER_ORIGIN_OTHER' && matchesStaticOrigin?.(url.origin)===true;
      const originalFrames=staticTarget && renderPhase && page?stripeFrameChain(route,page):undefined;
      if(provider)routeCheckpoint='BROWSER_PROVIDER_HEADERS';
      const headers=await request.allHeaders();
      if(provider)routeCheckpoint='BROWSER_PROVIDER_CURRENT';
      current();
      if(provider)routeCheckpoint='BROWSER_PROVIDER_REDIRECT';
      need(!request.redirectedFrom());
      if(provider)routeCheckpoint='BROWSER_PROVIDER_USERINFO';
      need(!url.username && !url.password);
      if(url.origin!==binding.endpoints.backend) {
        if(provider)routeCheckpoint='BROWSER_PROVIDER_AUTH';
        need(!headers.authorization || (providerOrigin(url) && headers.authorization!=='Bearer '+token));
        if(url.origin===binding.endpoints.demo){
          need(request.method()==='GET' && !url.search && !url.hash && request.frame()===page!.mainFrame() &&
            ((state==='INITIAL' && navigation===0 && url.pathname===pagePath && request.isNavigationRequest()) ||
            (!request.isNavigationRequest() && /^\/assets\/[\w.-]+\.(js|css)$/.test(url.pathname))));
        }else{
          if(refusal){
            // Syntax categories of the already refused parsed URL grant no authority.
            routeCheckpoint=refusal;
            if(staticTarget){
              routeCheckpoint='BROWSER_PROVIDER_STATIC_REQUEST_PHASE';
              need(renderPhase);
              routeCheckpoint='BROWSER_PROVIDER_STATIC_REQUEST_METHOD';
              need(request.method()==='GET');
              routeCheckpoint='BROWSER_PROVIDER_STATIC_REQUEST_BODY';
              need(request.postData()===null);
              routeCheckpoint='BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION';
              need(!request.isNavigationRequest());
              routeCheckpoint='BROWSER_PROVIDER_STATIC_REQUEST_URL';
              need(url.href===url.origin+url.pathname);
              routeCheckpoint='BROWSER_PROVIDER_STATIC_REQUEST_CREDENTIALS';
              need(!('authorization' in headers) && !('cookie' in headers) && !('proxy-authorization' in headers));
              routeCheckpoint='BROWSER_PROVIDER_STATIC_REQUEST_RESOURCE_TYPE';
              const resource=request.resourceType();
              need(staticAssets.some(a=>a.resource===resource));
              routeCheckpoint='BROWSER_PROVIDER_STATIC_REQUEST_SUFFIX';
              const asset=staticAssets.find(a=>a.resource===resource&&url.pathname.endsWith(a.suffix));
              need(asset);
              routeCheckpoint='BROWSER_PROVIDER_STATIC_FRAME';
              need(originalFrames && (!('origin' in headers)||headers.origin===new URL(originalFrames[0]!.href).origin));
              const isRendering=()=>state===renderPhase && step==='CONFIRM';
              try{
                // The old form can detach its frame while request headers are pending.
                need(isRendering());
                const checkOwner=()=>{
                  need(isRendering());routeCheckpoint='BROWSER_PROVIDER_STATIC_FRAME';assertPage();
                  const actual=stripeFrameChain(route,page!);
                  need(actual && actual.length===originalFrames.length &&
                    actual.every(({frame,href},i)=>frame===originalFrames[i]!.frame && href===originalFrames[i]!.href));
                  routeCheckpoint='BROWSER_PROVIDER_STATIC_RESPONSE';
                };
                await billingBounded(scoped(),limits=>track((async()=>{
                  checkOwner();
                  const reply=await fetchStaticAsset(url,asset.media,headers.origin,limits.signal!);
                  billingCheck(limits);
                  checkOwner();
                  await route.fulfill({...reply,status:200});
                })()),15000);
              }catch{
                if(isRendering())throw failed();
                await route.abort('failed').catch(()=>{});
              }
              return;
            }
            if(routeCheckpoint==='BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER' ||
                (state==='INITIAL' && routeCheckpoint==='BROWSER_PROVIDER_ORIGIN_OTHER'))refusedOrigin=url.origin;
            need(false);
          }
          routeCheckpoint='BROWSER_PROVIDER_NAVIGATION';
          need(!(request.isNavigationRequest() && request.frame()===page!.mainFrame()));
        }
        if(provider)routeCheckpoint='BROWSER_PROVIDER_FALLBACK';
        await route.fallback();return;
      }
      assertPage();
      need(request.frame()===page!.mainFrame() && !request.isNavigationRequest() && headers.origin===binding.endpoints.demo && !headers.cookie);
      const [target,verb]=expected();
      need(request.url()===target);
      if(request.method()==='OPTIONS'){
        need(headers['access-control-request-method']===verb && !headers.authorization && !request.postData() &&
          (headers['access-control-request-headers']??'').split(',').every(s=>['','authorization','content-type','idempotency-key'].includes(s.trim().toLowerCase())));
        await route.fulfill({status:204,headers:{'Cache-Control':'no-store','Access-Control-Allow-Origin':binding.endpoints.demo,'Access-Control-Allow-Methods':verb!,
          'Access-Control-Allow-Headers':'Content-Type, Authorization, Idempotency-Key'}});return;
      }
      need(request.method()===verb && (verb==='GET'?!request.postData():headers['content-type']==='application/json'));
      const previous=step;
      if((state==='INITIAL'||state==='METHOD')&&(previous==='ACTION'||previous==='SESSION'))inFlight={phase:state,step:previous};
      step='BUSY';
      if(previous==='CHALLENGE' || previous==='VERIFY'){
        need(!headers.authorization && !headers['idempotency-key']);
        if(previous==='CHALLENGE'){
          const v=body(request.postData(),['site_key','email','intent']);
          need(v.site_key===binding.siteKey && v.email===binding.email && v.intent==='AUTHENTICATE');
          step='CHALLENGE_ID';
        }else{
          const v=body(request.postData(),['site_key','code']);
          need(v.site_key===binding.siteKey && code && v.code===code);
          code='';step='PREPARE';
        }
        await route.fallback();return;
      }
      need(headers.authorization==='Bearer '+token && token);
      if(verb==='POST'){
        const key=headers['idempotency-key'];
        need(uuid(key) && !usedKeys.has(key));usedKeys.add(key);
      }
      if(previous==='CONFIRM')need(manualArmed);
      if(state==='INITIAL'){
        need(initial && initialBridge && Date.now()<Date.parse(initial.action.expires_at));
        if(previous==='SESSION')body(request.postData(),['action_token']);
        if(previous==='CONFIRM')body(request.postData(),['capability','confirmation_token']);
        await initialBridge({request:()=>request,fulfill:async options=>{
          assertPage();
          inFlight=undefined;
          step=previous==='ACTION'?'SESSION':previous==='SESSION'?'CONFIRM':'ACK';
          const delivery=route.fulfill(options);
          if(previous==='CONFIRM')finalDelivery=delivery;
          await delivery;
        }});return;
      }
      need(state==='METHOD' && method && methodPort && Date.now()<Date.parse(method.action.expires_at));
      let json:unknown,status=200;
      if(previous==='ACTION') {
        json=method.action;inFlight=undefined;step='SESSION';
      }else{
        need(body(request.postData(),['action_token']).action_token===method.action.secret);
        if(previous==='SESSION'){
          const s=await methodPort.selectMethod(scoped());
          assertPage();
          need(s.protocol==='BROWSER_CONFIRM_V1' && s.flow==='SETUP' && s.provider==='stripe' && s.attempt_id===null && s.capability===null &&
            s.action_token===method.action.secret && s.publishable_key.startsWith('pk_test_') && Date.now()<Date.parse(s.expires_at) &&
            Date.parse(s.expires_at)<=Math.min(sessionExpiry,Date.parse(method.action.expires_at)));
          selected=s;json={protocol:s.protocol,provider:s.provider,flow:s.flow,publishable_key:s.publishable_key,client_secret:s.client_secret,action_token:s.action_token,expires_at:s.expires_at};inFlight=undefined;step='CONFIRM';
        }else{
          need(previous==='CONFIRM' && selected && Date.now()<Date.parse(selected.expires_at));
          const permit=await methodPort.confirmMethod(scoped());
          assertPage();
          need(permit.browser_confirm_allowed===true && permit.state==='RESERVED' && Date.now()<Date.parse(selected.expires_at));
          json={subscription_id:method.subscriptionId,operation_id:method.operationId,status:permit.state,action_available:false,next_action:null,browser_confirm_allowed:permit.browser_confirm_allowed};
          status=202;step='ACK';
        }
      }
      assertPage();
      const delivery=route.fulfill({status,contentType:'application/json',headers:{'Cache-Control':'no-store','Access-Control-Allow-Origin':binding.endpoints.demo},json});
      if(previous==='CONFIRM')finalDelivery=delivery;
      await delivery;
    }catch{
      const original=!firstFailure,error=latch(routeCheckpoint);
      if(original && refusedOrigin)rememberBrowserProviderOrigin(error,binding.runId,binding.expectedSource,refusedOrigin);
      lost();await route.abort('failed').catch(()=>{});
    }
  }
  async function setup(port:BillingFirstPaymentPort) {
    binding=port.resources();initialPort=port;
    need(loopback(binding.endpoints.backend) && loopback(binding.endpoints.demo) && binding.endpoints.backend!==binding.endpoints.demo);
    checkpoint='BROWSER_CONTEXT';
    opening=browser.newContext({serviceWorkers:'block',acceptDownloads:false});
    context=await opening;current();
    page=await at('BROWSER_PAGE',()=>context!.newPage());current();
    checkpoint='BROWSER_SETUP';
    context.on('page',other=>{if(other!==page)lost();});
    context.on('close',lost);
    page.on('framenavigated',frame=>{if(frame===page!.mainFrame() && (++navigation!==1 || frame.url()!==binding.endpoints.demo+pagePath))lost();});
    page.on('close',lost);
    page.on('crash',lost);
    await context.routeWebSocket('**/*',socket=>socket.close());
    await context.route('**/*',r=>track(routeRequest(r)));
    await context.exposeBinding('__failedRenewalTerminal',({frame,page:source},...args:unknown[])=>{
      if(state==='CLOSED' || source!==page || frame!==page!.mainFrame() || frame.url()!==binding.endpoints.demo+pagePath ||
          args.length!==2 || args[0]!=='STOPPED')return;
      const marker=args[1];
      // BUSY is serialized transport, not a page stage. Only its exact original
      // action/session may report a terminal until delivery advances the step.
      const active=step==='BUSY'&&inFlight?.phase===state?inFlight.step:step;
      const valid=marker===null ||
        (readiness==='INITIAL' && state==='INITIAL' && !preparationActive && (
          (marker==='BROWSER_INITIAL_ACTION' && (active==='ACTION'||(step!=='BUSY'&&active==='SESSION'))) ||
          (marker==='BROWSER_INITIAL_SESSION' && (active==='SESSION'||active==='CONFIRM')) ||
          (marker==='BROWSER_INITIAL_MOUNT' && active==='CONFIRM'))) ||
        (readiness==='METHOD' && state==='METHOD' && marker==='BROWSER_METHOD_MOUNT' && (active==='ACTION'||active==='SESSION'||active==='CONFIRM'));
      if(!valid)return;
      if(typeof marker==='string')latch(marker);
      lost();
    });
    await context.exposeBinding('__failedRenewalChallenge',({frame,page:source},value:unknown)=>{
      try{
        assertPage();need(source===page && frame===page!.mainFrame() && state==='INITIAL' && step==='CHALLENGE_ID' && uuid(value));
        challenge=value;step='VERIFY';
      }catch{const error=latch(checkpoint);lost();throw error;}
    });
    await context.exposeBinding('__failedRenewalPrepare',({frame,page:source},value:unknown)=>track((async()=>{
      let prepareCheckpoint=checkpoint;
      try {
        assertPage();need(source===page && frame===page!.mainFrame() && state==='INITIAL' && step==='PREPARE' && initialPort);
        preparationActive=true;prepareCheckpoint=checkpoint='BROWSER_PREPARE';
        step='BUSY';
        const v=fields(value,['token','origin','customerId','siteId','expiresAt']);
        need(v.origin===binding.endpoints.demo && v.siteId===binding.siteId && uuid(v.customerId) && (!binding.customerId||binding.customerId===v.customerId) &&
          /^[A-Za-z0-9_-]{43}$/.test(v.token!) && Date.parse(v.expiresAt!)>Date.now());
        token=v.token!;sessionExpiry=Date.parse(v.expiresAt!);
        need(await initialPort.acknowledgeCustomer({customerId:v.customerId,siteId:v.siteId!})===true);
        assertPage();
        const createKey=crypto.randomUUID();usedKeys.add(createKey);
        const p=await initialPort.prepare({origin:v.origin!,token},createKey,scoped());
        assertPage();need(p.customer_id===v.customerId && p.site_id===binding.siteId && p.account_id===binding.accountId && p.run_id===binding.runId && p.price_id===binding.priceId && uuid(p.subscription_id) && uuid(p.operation_id) && p.session.publishable_key.startsWith('pk_test_'));
        initial=p;initialBridge=createFirstPaymentBridge({binding,prepared:p,token,port:initialPort,limits:scoped(),latchFailure:latch});
        step='ACTION';
        readiness='INITIAL';
        preparationActive=false;
        checkpoint='BROWSER_READY_PHASE';
        return {subscriptionId:p.subscription_id,operationId:p.operation_id};
      }catch{const error=latch(prepareCheckpoint);lost();throw error;}
    })()));
    await at('BROWSER_NAVIGATION',()=>page!.goto(binding.endpoints.demo+pagePath,{timeout:timeout()}));
    assertPage();
    await at('BROWSER_CONFIGURE',async()=>{
      await page!.waitForFunction(()=>typeof (window as any).__failedRenewalConfigure==='function',undefined,{timeout:timeout()});
      await page!.evaluate(c=>(window as any).__failedRenewalConfigure(c),{backend:binding.endpoints.backend,siteKey:binding.siteKey,email:binding.email,deadline:whole.deadline});
    });
    checkpoint='BROWSER_OTP_REQUEST';
    await page.getByRole('button',{name:'Request code',exact:true}).click({timeout:timeout()});
    await waitPhase('CODE');
    need(await port.withOtpCode(async v=>{
      assertPage();need(step==='VERIFY' && !code && /^\d{6}$/.test(v));
      code=v;await enterPrivateText(page!.getByRole('textbox',{name:'Six-digit code',exact:true}),v);return true as const;
    },scoped())===true);
    checkpoint='BROWSER_OTP_VERIFY';
    await page.getByRole('button',{name:'Verify code',exact:true}).click({timeout:timeout()});
    await waitPhase('AUTHENTICATED');
    await page.getByRole('checkbox',{name:'I consent to recurring payments',exact:true}).check({timeout:timeout()});
    checkpoint='BROWSER_PREPARE';
    await at('BROWSER_START_CLICK',()=>page!.getByRole('button',{name:'Start payment',exact:true}).click({timeout:timeout()}));
    await awaitOperator('initial');
  }
  return Object.freeze({
    async initial(port:BillingFirstPaymentPort,limits:BillingLimits) {
      try{
        need(state==='NEW');billingCheck(limits);need(limits.deadline<=Date.now()+2700000);
        whole=limits;stage=limits;state='INITIAL';
        whole.signal?.addEventListener('abort',stop,{once:true});expires=setTimeout(lost,whole.deadline-Date.now());
        await billingBounded(scoped(),()=>track(setup(port)),300000);
        assertPage();need(initial);initialPort=undefined;initialBridge=undefined;
        state='HELD';return true;
      }catch{const error=latch(checkpoint);lost();throw error;}
    },
    async method(port:BillingFailedRenewalMethodPort,limits:BillingLimits):Promise<true> {
      try{
        need(state==='HELD' && initial);stage=limits;assertPage();
        state='METHOD';methodPort=port;
        await billingBounded(scoped(),()=>track((async()=>{
          checkpoint='BROWSER_PREPARE';
          need(await page!.locator('body').getAttribute('data-phase')==='OBSERVE');
          const m=await port.prepareMethod(scoped());
          assertPage();
          need(initial && m.subscriptionId===initial.subscription_id && uuid(m.operationId) && m.operationId!==initial.operation_id &&
            m.action.kind==='PROVIDER_CLIENT' && Date.now()<Date.parse(m.action.expires_at));
          method=m;step='ACTION';readiness='METHOD';
          await at('BROWSER_READY_PHASE',()=>page!.evaluate(o=>(window as any).__failedRenewalMethod(o),{subscriptionId:m.subscriptionId,operationId:m.operationId}));
          await awaitOperator('method');
        })()),300000);
        assertPage();stage=whole;methodPort=undefined;method=undefined;selected=undefined;state='DONE';return true;
      }catch{const error=latch(checkpoint);lost();throw error;}
    },
    async close(limits:BillingLimits):Promise<true>{
      stop();
      let cleanup=false;
      try{
        const result=await billingBounded(limits,async()=>{
          await closing;
          await Promise.allSettled([...pending]);
          return true as const;
        },30000);
        cleanup=!!context;
        return result;
      }catch{throw latch('BROWSER_CLOSE');}
      finally{if(firstFailure && browserDiagnostic(firstFailure))finishBrowserCleanup(firstFailure,cleanup);}
    }
  });
}
