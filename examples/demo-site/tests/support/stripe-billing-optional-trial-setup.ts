import {randomUUID} from 'node:crypto';
import type {Browser,BrowserContext,Frame,JSHandle,Request,Response} from '@playwright/test';
import type {Stripe} from '@stripe/stripe-js';
import type {BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
import {acceptanceBrowserFailure,finishBrowserCleanup,isAcceptanceBrowserDiagnostic} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
import {parseRefundRetainedJSON as parseJsonWithoutDuplicateKeys} from '../../../../infrastructure/stripe-manual-refund-retained.mjs';
// @ts-ignore Native Node tests load the same support source as Playwright.
import {stripeFrames} from './stripe-billing-acceptance.ts';
// @ts-ignore Native Node tests load the same support source as Playwright.
import {guardChallengeRedirects} from './stripe-billing-initial-3ds-redirect.ts';
// @ts-ignore Native Node tests load the same support source as Playwright.
import {pinnedRetryPresentation,plainHttpsAuthority,stripeFrameChain} from './stripe-billing-pinned-presentation.ts';
// @ts-ignore Native Node tests load the same support source as Playwright.
import {providerNetworkOrigin,providerOrigin} from './stripe-provider-origin.ts';

export interface OptionalTrialSetupPermit {
  readonly setupIntentId:string;
  readonly publishableKey:string;
  readonly clientSecret:string;
  readonly expiresAt:string;
}

const refused=()=>new Error('billing_OPTIONAL_TRIAL_SETUP_BROWSER');
function need(value:unknown):asserts value {if(!value)throw refused();}
function terminalFailure(cleanupConfirmed:boolean,code='BROWSER') {
  const error=acceptanceBrowserFailure(cleanupConfirmed?code:'BROWSER_CLEANUP');
  finishBrowserCleanup(error,cleanupConfirmed);
  return error;
}
const demo='http://127.0.0.1:48000';
// Use the same Stripe.js load option as the other isolated payment checks.
const stripeScript='https://js.stripe.com/endive/stripe.js?advancedFraudSignals=false';
const stripeSetupDependency=/^https:\/\/js\.stripe\.com\/v3\/fingerprinted\/js\/(?:elements-easel-frame|trusted-types-checker)-[a-f0-9]{32}\.js$/;
const elementReadyBudget=30_000;
const usedIntents=new Set<string>();
const secretPattern=/^(seti_[A-Za-z0-9]{1,250})_secret_[A-Za-z0-9_]+$/;
const documentBody=`<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Save test card</title>
<link rel="icon" href="data:,"><script defer src="${stripeScript}"></script></head>
<body><main><div id="payment-element"></div><button type="button" id="confirm-setup" disabled>Save test card</button>
</main></body></html>`;

function expiry(permit:OptionalTrialSetupPermit) {
  need(typeof permit?.setupIntentId==='string' && /^seti_[A-Za-z0-9]{1,250}$/.test(permit.setupIntentId) &&
    typeof permit.publishableKey==='string' && /^pk_test_[A-Za-z0-9]+$/.test(permit.publishableKey) &&
    typeof permit.clientSecret==='string' && permit.clientSecret.length<=512 &&
    secretPattern.exec(permit.clientSecret)?.[1]===permit.setupIntentId &&
    typeof permit.expiresAt==='string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(permit.expiresAt));
  const at=Date.parse(permit.expiresAt);
  need(Number.isSafeInteger(at) && at>Date.now());
  return at;
}

function setupBase(permit:OptionalTrialSetupPermit) {
  return 'https://api.stripe.com/v1/setup_intents/'+permit.setupIntentId;
}

function setupFields(data:URLSearchParams,headers:Record<string,string>,permit:OptionalTrialSetupPermit) {
  const keys=data.getAll('key');
  const bearer='Bearer '+permit.publishableKey;
  need(data.getAll('client_secret').length===1 && data.get('client_secret')===permit.clientSecret &&
    keys.length<=1 && (keys.length===1 ? keys[0]===permit.publishableKey : headers.authorization===bearer) &&
    (!headers.authorization || headers.authorization===bearer) &&
    ![...data.keys()].some(name=>/^(?:client_secret|key)\[|(?:^|\[)(?:payment_intent|payment_method|confirmation_token|stripe_account|stripe_context)(?:\[|\]|$)/i.test(name)));
  const types=data.getAll('payment_method_data[type]');
  need(types.length<=1 && (types.length===0 || types[0]==='card'));
}

function unprivileged(headers:Record<string,string>) {
  return !['cookie','proxy-authorization','stripe-account','stripe-context'].some(field=>field in headers);
}

/** Tracks the exact intercepted request that owns the right to a succeeded response. */
export function optionalTrialSetupExchange(permit:OptionalTrialSetupPermit) {
  let sent:Request|undefined;
  const confirmUrl=setupBase(permit)+'/confirm';
  function isResponse(response:Response) {return sent!==undefined && response.request()===sent && response.url()===confirmUrl;}
  return {
    get dispatched(){return sent!==undefined;},
    isResponse,
    claim(request:Request,headers:Record<string,string>,frameOrigin:string,trusted:boolean){
      expiry(permit);
      const raw=request.postData();
      need(!sent && trusted && request.url()===confirmUrl && request.method()==='POST' &&
        !request.isNavigationRequest() && !request.redirectedFrom() && !request.serviceWorker() &&
        ['fetch','xhr'].includes(request.resourceType()) && unprivileged(headers) && headers.origin===frameOrigin &&
        raw && raw.length<=32768 && !/%(?![0-9a-f]{2})/i.test(raw) &&
        /^application\/x-www-form-urlencoded(?:\s*;\s*charset=utf-8)?$/i.test(headers['content-type']??''));
      setupFields(new URLSearchParams(raw),headers,permit);
      sent=request;
    },
    async verify(response:Response){
      try {
        expiry(permit);
        need(isResponse(response) && !response.fromServiceWorker() && !response.request().redirectedFrom() && response.status()===200);
        const bytes=await response.body();
        need(bytes.length<=65536);
        const data=parseJsonWithoutDuplicateKeys(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
        need(data!==null && typeof data==='object' && !Array.isArray(data));
        const value=data as Record<string,unknown>;
        need(value.object==='setup_intent' && value.id===permit.setupIntentId && value.client_secret===permit.clientSecret &&
          value.livemode===false && value.status==='succeeded' && value.next_action==null && value.last_setup_error==null);
        expiry(permit);
      } catch {throw refused();}
    },
  };
}

/** No application requests are made from this isolated checkout document. */
export async function runOptionalTrialSetupBrowser(browser:Browser,permit:OptionalTrialSetupPermit,owner:BillingLimits,
    matchesStaticOrigin:(origin:string)=>boolean):Promise<true> {
  const granted=Object.freeze({setupIntentId:permit?.setupIntentId,publishableKey:permit?.publishableKey,
    clientSecret:permit?.clientSecret,expiresAt:permit?.expiresAt});
  let expiresAt:number;
  try{
    expiresAt=expiry(granted);
    need(!usedIntents.has(granted.setupIntentId) && typeof matchesStaticOrigin==='function');
    usedIntents.add(granted.setupIntentId);
    billingCheck(owner);
  }catch{throw terminalFailure(true,'BROWSER_SETUP');}
  const lifetime=new AbortController();
  const limits={deadline:Math.min(owner.deadline,expiresAt),
    signal:owner.signal?AbortSignal.any([owner.signal,lifetime.signal]):lifetime.signal};
  const check=()=>billingCheck(limits);
  const timeout=()=>{check();return Math.min(45_000,limits.deadline-Date.now());};
  const url=demo+'/owned-trial-setup/'+randomUUID();
  const intentUrl=setupBase(granted),confirmUrl=intentUrl+'/confirm';
  const exchange=optionalTrialSetupExchange(granted);
  let context:BrowserContext|undefined,closing:Promise<void>|undefined,open:Promise<BrowserContext>|undefined;
  let failed=false,completed=false,cleanupConfirmed=false;
  let checkpoint='BROWSER_SETUP',failureCode:string|undefined;
  function close(ctx:BrowserContext){
    closing??=Promise.resolve().then(()=>ctx.close());
    void closing.catch(()=>{});
    return closing;
  }
  function recordFailure(code:string){
    failed=true;
    failureCode??=isAcceptanceBrowserDiagnostic(code)?code:'BROWSER';
  }
  function stop(code=checkpoint){
    recordFailure(code);
    lifetime.abort();
    if(context)void close(context).catch(()=>{});
  }
  const onAbort=()=>stop();
  limits.signal.addEventListener('abort',onAbort,{once:true});
  try {
    check();
    open=browser.newContext({serviceWorkers:'block',acceptDownloads:false});
    void open.then(ctx=>{if(limits.signal.aborted)return close(ctx);},()=>{}).catch(()=>{});
    context=await billingBounded(limits,()=>open!);
    const page=await billingBounded(limits,()=>context!.newPage());
    const presentationSessions=new WeakSet<Request>();
    let observedSession:'NONE'|'REQUESTED'|'RESPONDED'='NONE';
    page.on('response',response=>{
      const request=response.request();
      if(presentationSessions.has(request) && response.url()===request.url() &&
        response.status()>=200 && response.status()<300 && !response.fromServiceWorker())
        observedSession='RESPONDED';
    });
    context.on('page',other=>{if(other!==page)stop('BROWSER_GUARD_POPUP');});
    page.on('close',()=>{if(!completed)stop('BROWSER_INVALIDATED');});
    page.on('download',()=>stop('BROWSER_INVALIDATED'));
    await billingBounded(limits,()=>context!.routeWebSocket('**/*',socket=>{try{socket.close();}finally{stop('BROWSER_GUARD_WEBSOCKET');}}));
    const redirects=await billingBounded(limits,()=>guardChallengeRedirects(page,stop,{demo,backend:'http://127.0.0.1:48001'},limits.signal));
    let documentServed=false,active=false;
    const generations=new WeakMap<Frame,number>();
    const bump=(frame:Frame)=>generations.set(frame,(generations.get(frame)??0)+1);
    page.on('framenavigated',bump);
    page.on('framedetached',bump);
    function capture(frame:Frame){
      const main=page.mainFrame(),chain=frame===main?[]:stripeFrameChain(page,()=>frame);
      need(page.url()===url && chain);
      const original=[...chain,{frame:main,href:url}].map(({frame:document,href})=>({
        document,href,generation:generations.get(document),parent:document.parentFrame(),
      }));
      return ()=>page.mainFrame()===main && original.every(({document,href,generation,parent})=>
        !document.isDetached() && document.page()===page && document.url()===href &&
        document.parentFrame()===parent && generations.get(document)===generation);
    }
    const setupReaders=new Map<Frame,()=>boolean>();
    let transports:ReadonlyMap<Frame,()=>boolean>|undefined;
    let cardCurrent:(()=>Promise<void>)|undefined,cardDocumentIsCurrent:(()=>boolean)|undefined;
    let trusted:JSHandle<()=>boolean>|undefined;
    const pinned=pinnedRetryPresentation(page,()=>url,limits,()=>exchange.dispatched?undefined:'initial',
      ()=>!active,()=>undefined,stop);
    const checkClick=async(request:Request)=>{
      check();
      const click=trusted,currentFrame=transports?.get(request.frame()),currentCard=cardDocumentIsCurrent;
      need(active && click && cardCurrent && currentCard && currentFrame && currentCard() && currentFrame());
      await cardCurrent();
      need(await click.evaluate(current=>current()));
      check();
      // The confirm transport can be a separate helper. A reload of the
      // visible card frame during the last renderer await still revokes it.
      need(active && trusted===click && currentCard() && currentFrame());
    };
    await billingBounded(limits,()=>context!.route('**/*',async route=>{
      let routeCheckpoint='BROWSER_PROVIDER_ROUTE';
      try {
        check();
        const request=route.request(),raw=request.url(),method=request.method(),navigation=request.isNavigationRequest();
        routeCheckpoint='BROWSER_PROVIDER_REDIRECT';
        need(!request.redirectedFrom());
        routeCheckpoint='BROWSER_PROVIDER_ROUTE_POLICY';
        need(!request.serviceWorker());
        if(raw===url){
          routeCheckpoint='BROWSER_NAVIGATION';
          need(!documentServed && method==='GET' && navigation && request.frame()===page.mainFrame());
          documentServed=true;
          redirects.registerRoute(request);
          await redirects.authorize(request);
          check();
          await route.fulfill({status:200,contentType:'text/html; charset=utf-8',body:documentBody,
            headers:{'cache-control':'no-store','referrer-policy':'no-referrer','x-content-type-options':'nosniff'}});
          return;
        }
        const target=new URL(raw);
        need(page.url()===url && plainHttpsAuthority.test(raw) && !target.hash && !target.username && !target.password && !target.port);
        if(!providerNetworkOrigin(target) && !/(?:^|\.)stripe\.(?:com|network)$/.test(target.hostname) && matchesStaticOrigin(target.origin)){
          await pinned.forward(route,target);
          return;
        }
        need(providerNetworkOrigin(target) && ['GET','POST','OPTIONS'].includes(method));
        const script=providerOrigin(target) && (target.hostname==='js.stripe.com'||target.hostname.endsWith('.js.stripe.com'));
        const api=target.hostname==='api.stripe.com';
        const elementsSession=api && target.pathname==='/v1/elements/sessions';
        const support=target.hostname==='r.stripe.com'||target.hostname==='merchant-ui-api.stripe.com';
        const preflight=method==='OPTIONS',childNavigation=script && method==='GET' && navigation;
        const confirming=raw===confirmUrl,setupRead=target.origin+target.pathname===intentUrl;
        const ownScript=script && method==='GET' && request.resourceType()==='script';
        const allowMain=()=>api && confirming && method==='POST' ||
          ownScript && (raw===stripeScript || !active && !exchange.dispatched && stripeSetupDependency.test(raw));
        routeCheckpoint='BROWSER_PROVIDER_ROUTE_FRAME';
        const before=stripeFrames(page,request,childNavigation,allowMain(),demo);
        need(before && (!navigation || childNavigation));
        redirects.registerRoute(request);
        routeCheckpoint='BROWSER_PROVIDER_HEADERS';
        const headers=await request.allHeaders();
        check();
        routeCheckpoint='BROWSER_PROVIDER_AUTH';
        need(unprivileged(headers) && (!headers.authorization || api && !preflight && headers.authorization==='Bearer '+granted.publishableKey));
        routeCheckpoint='BROWSER_PROVIDER_ROUTE_FRAME';
        // The initial child GET is bound to its parent and CDP frame before the child has a URL.
        const source=childNavigation?undefined:new URL(request.frame().url()).origin;
        routeCheckpoint='BROWSER_PROVIDER_ROUTE_POLICY';
        const intended=preflight?headers['access-control-request-method']:method;
        need(intended==='GET'||intended==='POST');
        if(!script && headers.origin)need(headers.origin===source);
        let readAuthority:(()=>boolean)|undefined;
        if(script)need(intended==='GET');
        else if(support)need(!navigation && !/^\/(?:v\d+|api)(?:\/|$)/i.test(target.pathname) && !target.pathname.includes('%'));
        else if(api && confirming){
          routeCheckpoint='BROWSER_PROVIDER_ROUTE_PAY';
          need(source!==undefined && intended==='POST' && headers.origin===source);
          await checkClick(request);
          if(preflight)need(!exchange.dispatched);
          else exchange.claim(request,headers,source,true);
        }else if(api && setupRead){
          need(intended==='GET');
          if(!preflight){
            setupFields(target.searchParams,headers,granted);
            if(!active)readAuthority=capture(before.target);
          }
        }else{
          need(elementsSession && (intended==='GET'||!exchange.dispatched));
          if(!active && !preflight){
            const rawForm=method==='POST' && /^application\/x-www-form-urlencoded/i.test(headers['content-type']??'')?request.postData():null;
            const fields=rawForm && rawForm.length<=32768?new URLSearchParams(rawForm):target.searchParams;
            try{setupFields(fields,headers,granted);readAuthority=capture(before.target);}
            catch{/* A general presentation session cannot establish a confirmation transport. */}
          }
        }

        routeCheckpoint='BROWSER_PROVIDER_ROUTE_GUARD';
        await redirects.authorize(request);
        if(confirming){
          routeCheckpoint='BROWSER_PROVIDER_ROUTE_PAY';
          await checkClick(request);
        }
        check();
        routeCheckpoint='BROWSER_PROVIDER_ROUTE_FRAME';
        const current=stripeFrames(page,request,childNavigation,allowMain(),demo);
        need(current && current.target===before.target && current.frames.size===before.frames.size &&
          [...before.frames].every(([frame,location])=>current.frames.get(frame)===location));
        if(elementsSession && !preflight){
          presentationSessions.add(request);
          if(observedSession==='NONE')observedSession='REQUESTED';
        }
        routeCheckpoint='BROWSER_PROVIDER_FALLBACK';
        if(confirming && !preflight)await route.continue();
        else await route.fallback();
        if(readAuthority){
          routeCheckpoint='BROWSER_INITIAL_SESSION_WIRE_READ';
          const observed=await billingBounded(limits,()=>request.response());
          if(!active && readAuthority() && observed?.request()===request && observed.url()===raw &&
              observed.status()===200 && !observed.fromServiceWorker())setupReaders.set(before.target,readAuthority);
        }
      } catch {
        stop(routeCheckpoint);
        await route.abort('failed').catch(()=>{});
      }
    }));
    checkpoint='BROWSER_NAVIGATION';
    await billingBounded(limits,()=>page.goto(url,{timeout:timeout()}));
    need(documentServed && page.url()===url);
    page.on('framenavigated',frame=>{if(frame===page.mainFrame())stop('BROWSER_GUARD_MAIN_NAVIGATION');});
    checkpoint='BROWSER_WAIT_READY';
    await billingBounded(limits,()=>page.evaluate(({publishableKey,clientSecret,setupIntentId,expires,documentUrl})=>{
      const owner=window as Window&{Stripe?:(key:string)=>Stripe};
      const host=document.querySelector('#payment-element');
      const button=document.querySelector('#confirm-setup');
      if(window!==window.top || location.href!==documentUrl || !owner.Stripe ||
          !(host instanceof HTMLElement) || !(button instanceof HTMLButtonElement))throw Error('setup unavailable');
      let retired=false,ready=false,complete=false,spent=false;
      const current=()=>!retired && !document.hidden && Date.now()<expires && location.href===documentUrl &&
        host.isConnected && button.isConnected && document.querySelector('#payment-element')===host &&
        document.querySelector('#confirm-setup')===button;
      const stripe=owner.Stripe(publishableKey);
      const elements=stripe.elements({clientSecret,locale:'en'});
      const payment=elements.create('payment',{wallets:{applePay:'never',googlePay:'never',link:'never'}});
      const retire=()=>{retired=true;button.disabled=true;};
      window.addEventListener('pagehide',retire,{once:true});
      document.addEventListener('visibilitychange',()=>{if(document.hidden)retire();});
      const enabled=()=>{button.disabled=!current()||!ready||!complete||spent;};
      payment.on('ready',()=>{
        if(retired)return;
        ready=true;
        button.dataset.elementState='READY';
        enabled();
      });
      payment.on('change',event=>{complete=event.complete;enabled();});
      payment.on('loaderror',()=>{button.dataset.elementState='FAILED';retire();});
      payment.mount(host);
      button.addEventListener('click',event=>{
        if(!event.isTrusted || document.activeElement!==button || !current() || !ready || !complete || spent)return;
        spent=true;enabled();
        void stripe.confirmSetup({elements,redirect:'if_required'}).then(reply=>{
          if(current() && !reply.error && reply.setupIntent?.id===setupIntentId && reply.setupIntent.status==='succeeded')
            button.dataset.confirmed='true';
        }).catch(retire);
      });
    },{publishableKey:granted.publishableKey,clientSecret:granted.clientSecret,setupIntentId:granted.setupIntentId,
      expires:limits.deadline,documentUrl:url}));
    checkpoint='BROWSER_SETUP_ELEMENT_READY';
    try{
      await billingBounded(limits,()=>page.waitForFunction(()=>{
        const state=document.querySelector<HTMLButtonElement>('#confirm-setup')?.dataset.elementState;
        return state==='READY' || state==='FAILED';
      },undefined,{timeout:Math.min(timeout(),elementReadyBudget)}),elementReadyBudget);
    }catch(error){
      checkpoint={
        NONE:'BROWSER_SETUP_ELEMENT_SESSION_NOT_SENT',
        REQUESTED:'BROWSER_SETUP_ELEMENT_SESSION_NO_SUCCESS',
        RESPONDED:'BROWSER_SETUP_ELEMENT_NO_READY_EVENT',
      }[observedSession];
      throw error;
    }
    checkpoint='BROWSER_SETUP_ELEMENT_LOAD_ERROR';
    need(await billingBounded(limits,()=>page.evaluate(()=>
      document.querySelector<HTMLButtonElement>('#confirm-setup')?.dataset.elementState==='READY')));
    checkpoint='BROWSER_SETUP_CARD_FRAME';
    const cardSelector='#payment-element iframe[title="Secure payment input frame"]';
    const ownerFrame=await billingBounded(limits,()=>page.locator('#payment-element')
      .locator('iframe[title="Secure payment input frame"]').elementHandle({timeout:timeout()}));
    const frame=await billingBounded(limits,()=>ownerFrame.contentFrame());
    need(frame);
    await billingBounded(limits,()=>frame.waitForURL(location=>providerOrigin(location) &&
      (location.hostname==='js.stripe.com'||location.hostname.endsWith('.js.stripe.com')),
    {waitUntil:'commit',timeout:timeout()}));
    const card=capture(frame);
    cardDocumentIsCurrent=card;
    cardCurrent=async()=>{
      check();
      need(card() && await ownerFrame.contentFrame()===frame &&
        await ownerFrame.evaluate((element,selector)=>element.isConnected && document.querySelector(selector)===element,cardSelector));
      check();
      need(card());
    };
    await cardCurrent();
    for(const [field,value,code] of [
      ['number','4242424242424242','BROWSER_CARD_NUMBER'],['expiry','1230','BROWSER_CARD_EXPIRY'],['cvc','123','BROWSER_CARD_CVC'],
    ] as const){
      checkpoint=code;
      await billingBounded(limits,()=>frame.locator(`input[name="${field}"]`).fill(value,{timeout:timeout()}));
      checkpoint='BROWSER_SETUP_CARD_FRAME';
      await cardCurrent();
    }
    checkpoint='BROWSER_POSTAL_CHECK';
    const postal=frame.locator('input[name="postalCode"]');
    if(await billingBounded(limits,()=>postal.isVisible())){
      checkpoint='BROWSER_POSTAL_FILL';
      await billingBounded(limits,()=>postal.fill('10001',{timeout:timeout()}));
    }

    checkpoint='BROWSER_WAIT_READY';
    const button=await billingBounded(limits,()=>page.getByRole('button',{name:'Save test card',exact:true}).elementHandle({timeout:timeout()}));
    await billingBounded(limits,()=>button.waitForElementState('enabled',{timeout:timeout()}));
    await pinned.wait();
    checkpoint='BROWSER_CONFIRM_FOCUS';
    await billingBounded(limits,()=>button.focus());
    trusted=await billingBounded(limits,()=>button.evaluateHandle(element=>{
      const current=()=>element instanceof HTMLButtonElement && element.isConnected && !document.hidden &&
        document.querySelector('#confirm-setup')===element;
      if(!current() || document.activeElement!==element)throw Error('setup unavailable');
      let clicked=false;
      window.addEventListener('pagehide',()=>{clicked=false;},{once:true});
      document.addEventListener('visibilitychange',()=>{if(document.hidden)clicked=false;});
      element.addEventListener('click',event=>{clicked=event.isTrusted && current() && document.activeElement===element;},
        {capture:true,once:true});
      return ()=>clicked && current();
    }));

    await cardCurrent();
    const chain=stripeFrameChain(page,()=>frame);
    need(chain);
    // A helper outside the original card ancestry needs its own guarded proof of
    // the original permit. Snapshot the evidence before any trusted activation.
    transports=new Map([...setupReaders,...chain.map(({frame:owner})=>[owner,capture(owner)] as const),
      [page.mainFrame(),capture(page.mainFrame())]]);
    const response=page.waitForResponse(exchange.isResponse,{timeout:timeout()});
    void response.catch(()=>{});
    active=true;
    checkpoint='BROWSER_CONFIRM_WAIT';
    const [accepted]=await billingBounded(limits,()=>Promise.all([response,button.press('Enter',{timeout:timeout()})]),45_000);
    checkpoint='BROWSER_CONFIRM_RECEIPT';
    await billingBounded(limits,()=>exchange.verify(accepted));
    await billingBounded(limits,()=>page.waitForFunction(()=>document.querySelector('#confirm-setup[data-confirmed="true"]')!==null,
      undefined,{timeout:timeout()}));
    await checkClick(accepted.request());
    active=false;
    need(!failed);
    completed=true;
  } catch {recordFailure(checkpoint);}
  finally {
    limits.signal.removeEventListener('abort',onAbort);
    lifetime.abort();
    try{
      if(open)await billingBounded({deadline:Date.now()+15_000},async()=>close(context??await open!));
      cleanupConfirmed=true;
    } catch {failed=true;}
  }
  if(!completed || failed || owner.signal?.aborted || Date.now()>=limits.deadline)throw terminalFailure(cleanupConfirmed,failureCode??checkpoint);
  return true;
}
