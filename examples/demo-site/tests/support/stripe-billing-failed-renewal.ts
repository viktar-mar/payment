import type {Browser,BrowserContext,Page,Route} from '@playwright/test';
import type {BillingBinding,BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {BillingFailedRenewalJourney,BillingFailedRenewalMethodPort,BillingFirstPaymentPort,FailedMethodSession,FirstPaymentPrepared} from '../../../../infrastructure/stripe-billing-first-payment.mjs';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
import {browserFailure,browserDiagnostic,finishBrowserCleanup} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
// @ts-ignore Explicit source for dependency-free Node TS runner.
import {createFirstPaymentBridge} from './stripe-billing-first-payment.ts';
// @ts-ignore Explicit source for dependency-free Node TS runner.
import {enterPrivateText} from './private-entry.ts';

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
function providerOrigin(url:URL) {
  return url.protocol==='https:' && !url.port && !url.username && !url.password &&
    (['api.stripe.com','hooks.stripe.com','js.stripe.com'].includes(url.hostname) || url.hostname.endsWith('.js.stripe.com'));
}

/** A single owned page. Neither phase accepts a target from the page or chooses a provider operation. */
export function runFailedRenewalBrowser(browser:Pick<Browser,'newContext'>,runAbort:AbortController):BillingFailedRenewalJourney {
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
    if(!(preparationActive && code==='BROWSER_WAIT_READY'))checkpoint=code;
    try{return await work();}catch{throw latch(code);}
  }
  const track=<T>(work:Promise<T>)=>{
    pending.add(work);void work.then(()=>pending.delete(work),()=>pending.delete(work));return work;
  };
  function stop() {
    state='CLOSED';lifetime.abort();clearTimeout(expires);
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
    await at('BROWSER_WAIT_READY',()=>waitPhase(ready));
    const button=page!.getByRole('button',{name:label,exact:true});
    await at('BROWSER_WAIT_READY',async()=>need(!(await button.isEnabled())));
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
    try {
      current();
      const request=route.request(),url=new URL(request.url());
      // Select backend provenance before even reading headers or delivering a
      // preflight. Provider and asset routes retain the outer checkpoint.
      if(url.origin===binding.endpoints.backend && (step==='ACTION'||step==='SESSION'||step==='CONFIRM'))
        routeCheckpoint=step==='ACTION'?'BROWSER_ACTION_BRIDGE':step==='SESSION'?'BROWSER_SESSION_BRIDGE':'BROWSER_CONFIRM_BRIDGE';
      const headers=await request.allHeaders();
      current();
      need(!request.redirectedFrom() && !url.username && !url.password);
      if(url.origin!==binding.endpoints.backend) {
        need(!headers.authorization || (providerOrigin(url) && headers.authorization!=='Bearer '+token));
        if(url.origin===binding.endpoints.demo){
          need(request.method()==='GET' && !url.search && !url.hash && request.frame()===page!.mainFrame() &&
            ((state==='INITIAL' && navigation===0 && url.pathname===pagePath && request.isNavigationRequest()) ||
            (!request.isNavigationRequest() && /^\/assets\/[\w.-]+\.(js|css)$/.test(url.pathname))));
        }else{
          need(providerOrigin(url) && !(request.isNavigationRequest() && request.frame()===page!.mainFrame()));
        }
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
          step=previous==='ACTION'?'SESSION':previous==='SESSION'?'CONFIRM':'ACK';
          const delivery=route.fulfill(options);
          if(previous==='CONFIRM')finalDelivery=delivery;
          await delivery;
        }});return;
      }
      need(state==='METHOD' && method && methodPort && Date.now()<Date.parse(method.action.expires_at));
      let json:unknown,status=200;
      if(previous==='ACTION') {
        json=method.action;step='SESSION';
      }else{
        need(body(request.postData(),['action_token']).action_token===method.action.secret);
        if(previous==='SESSION'){
          const s=await methodPort.selectMethod(scoped());
          assertPage();
          need(s.protocol==='BROWSER_CONFIRM_V1' && s.flow==='SETUP' && s.provider==='stripe' && s.attempt_id===null && s.capability===null &&
            s.action_token===method.action.secret && s.publishable_key.startsWith('pk_test_') && Date.now()<Date.parse(s.expires_at) &&
            Date.parse(s.expires_at)<=Math.min(sessionExpiry,Date.parse(method.action.expires_at)));
          selected=s;json={protocol:s.protocol,provider:s.provider,flow:s.flow,publishable_key:s.publishable_key,client_secret:s.client_secret,action_token:s.action_token,expires_at:s.expires_at};step='CONFIRM';
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
      latch(routeCheckpoint);
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
          args.length!==1 || args[0]!=='STOPPED')return;
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
        preparationActive=false;
        checkpoint='BROWSER_WAIT_READY';
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
    await at('BROWSER_WAIT_READY',()=>page!.getByRole('button',{name:'Start payment',exact:true}).click({timeout:timeout()}));
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
          method=m;step='ACTION';
          await at('BROWSER_WAIT_READY',()=>page!.evaluate(o=>(window as any).__failedRenewalMethod(o),{subscriptionId:m.subscriptionId,operationId:m.operationId}));
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
