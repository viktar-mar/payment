import type {Browser,BrowserContext,Request,Response,Route,Page} from '@playwright/test';
import type {SandboxAcceptanceExtras,SandboxDonationKind,SandboxDonationIdentity} from '../../../../infrastructure/stripe-sandbox-extras.mjs';
import type {OwnedSandboxLimits,OwnedSandboxPublicBinding} from '../../../../infrastructure/stripe-sandbox-acceptance.mjs';
import {rethrowSandboxBrowserFailure,runSandboxBrowserStep} from '../../../../infrastructure/stripe-sandbox-acceptance.mjs';
// @ts-expect-error Native Node 25 test execution uses the existing source directly.
import {enterPrivateText} from './private-entry.ts';

function fail():never {throw new Error('stripe_sandbox_BROWSER_DONATION_PENDING');}
const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const object=(value:unknown):value is Record<string,unknown>=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const exact=(value:unknown,keys:string[]):value is Record<string,unknown>=>object(value)&&Object.keys(value).sort().join(',')===[...keys].sort().join(',');
const endpoint=(binding:OwnedSandboxPublicBinding)=>binding.endpoints.backend+'/api/v1/public/sites/'+encodeURIComponent(binding.site.key)
  +'/donation-campaigns/'+encodeURIComponent(binding.campaign.key)+'/checkouts';
function timeout(limits:OwnedSandboxLimits,maximum=15000):number {
  if(!Number.isSafeInteger(limits.deadline)||limits.deadline<=Date.now()||limits.signal?.aborted)fail();
  return Math.min(maximum,limits.deadline-Date.now());
}
/** Lifetime only: pass the actual Browser and fixture unchanged to the existing card driver. */
export async function withOwnedCardContexts(browser:Browser,limits:OwnedSandboxLimits,journey:()=>Promise<void>):Promise<true> {
  const before=new Set(browser.contexts()),closing=new Map<BrowserContext,Promise<void>>();
  let closingFailed=false,failed=false,primary:unknown;
  const closeCreated=()=>{
    try{for(const context of browser.contexts())if(!before.has(context)&&!closing.has(context)){
      const promise=Promise.resolve().then(()=>context.close());promise.catch(()=>{});closing.set(context,promise);
    }}catch{closingFailed=true;}
  };
  let aborted=false,interval:ReturnType<typeof setInterval>|undefined,timer:ReturnType<typeof setTimeout>|undefined,stopScanning:ReturnType<typeof setTimeout>|undefined;
  const abort=()=>{aborted=true;closeCreated();interval??=setInterval(closeCreated,50);stopScanning??=setTimeout(()=>clearInterval(interval),120000);};
  try {
    limits.signal?.addEventListener('abort',abort,{once:true});timer=setTimeout(abort,timeout(limits,2700000));
    if(limits.signal?.aborted)abort();
    await journey();timeout(limits);if(aborted||closingFailed)throw null;
  }catch(error){failed=true;primary=error;}
  finally {
    clearTimeout(timer);clearTimeout(stopScanning);clearInterval(interval);limits.signal?.removeEventListener('abort',abort);closeCreated();
    try{await bounded(async()=>{await Promise.all(closing.values());},{deadline:Date.now()+15000});}
    catch(error){if(!failed){failed=true;primary=error;}}
    if(closingFailed&&!failed){failed=true;primary=null;}
  }
  if(failed)rethrowSandboxBrowserFailure(primary);
  return true;
}
async function bounded<T>(body:()=>Promise<T>,limits:OwnedSandboxLimits,maximum=15000):Promise<T> {
  let timer:ReturnType<typeof setTimeout>|undefined,abort:()=>void=()=>{};
  try {
    const duration=timeout(limits,maximum);
    const value=await Promise.race([Promise.resolve().then(()=>{timeout(limits);return body();}),new Promise<never>((_resolve,reject)=>{
      abort=()=>reject(new Error('stripe_sandbox_BROWSER_DONATION_PENDING'));
      limits.signal?.addEventListener('abort',abort,{once:true});timer=setTimeout(abort,duration);
      if(limits.signal?.aborted)abort();
    })]);timeout(limits);return value;
  }catch(error){return rethrowSandboxBrowserFailure(error,'DONATION_PENDING');}finally{clearTimeout(timer);limits.signal?.removeEventListener('abort',abort);}
}
async function jsonResponse(response:Pick<Response,'body'>,limits:OwnedSandboxLimits):Promise<Record<string,unknown>> {
  const bytes=await bounded(()=>response.body(),limits);
  if(bytes.byteLength>65536)fail();
  try {const value:unknown=JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes));if(!object(value))fail();return value as Record<string,unknown>;}catch{fail();}
}

/** Original SDK request only: no replacement body, synthetic response or replay. The owner captures failure before abort. */
export async function forwardDonationCreate(route:Pick<Route,'request'|'continue'>,extras:Pick<SandboxAcceptanceExtras,'beforeDonationCreate'>,kind:SandboxDonationKind,
  binding:OwnedSandboxPublicBinding,limits:OwnedSandboxLimits):Promise<void> {
  try {
    timeout(limits);const request=route.request();
    if(request.url()!==endpoint(binding)||request.redirectedFrom())fail();
    if(request.method()==='OPTIONS'){await bounded(()=>route.continue(),limits);return;}
    if(request.method()!=='POST')fail();
    const body:unknown=JSON.parse(request.postData()??'');
    if(!exact(body,['amount_minor'])||body.amount_minor!==500)fail();
    const headers=await bounded(()=>request.allHeaders(),limits),authority=headers.authorization;
    if(headers.origin!==binding.endpoints.demo||headers['content-type']!=='application/json'
      ||(kind==='anonymous'?authority!==undefined:typeof authority!=='string'||!/^Bearer [A-Za-z0-9_-]{43}$/.test(authority)))fail();
    const key=await extras.beforeDonationCreate(kind,{siteKey:binding.site.key,campaignKey:binding.campaign.key,amountMinor:500,hasCustomerAuthority:authority!==undefined},limits);
    timeout(limits);
    // Playwright preserves the original method/body and every unmodified original header.
    await bounded(()=>route.continue({headers:{...headers,'idempotency-key':key}}),limits);
  }catch(error){rethrowSandboxBrowserFailure(error,'DONATION_PENDING');}
}

export async function acknowledgeDonationResponse(response:Pick<Response,'request'|'url'|'status'|'body'>,request:Request,extras:Pick<SandboxAcceptanceExtras,'ackDonationCreate'>,
  kind:SandboxDonationKind,binding:OwnedSandboxPublicBinding,limits:OwnedSandboxLimits):Promise<void> {
  try {
    if(response.request()!==request||response.url()!==endpoint(binding)||request.method()!=='POST'||response.status()!==201)fail();
    const value=await jsonResponse(response,limits),donation=value.donation;
    if(!exact(value,['checkout_id','payment_id','checkout_token','amount_minor','currency','price','provider_type','confirmation_mode','provider_client_payload','status','expires_at','donation'])
      ||!uuid(value.checkout_id)||!uuid(value.payment_id)||value.amount_minor!==500||value.currency!=='EUR'||value.price!==null
      ||value.provider_type!=='STRIPE'||value.confirmation_mode!=='CLIENT_PROVIDER'||!exact(value.provider_client_payload,[])
      ||value.status!=='CREATED'||typeof value.expires_at!=='string'||!Number.isFinite(Date.parse(value.expires_at))||Date.parse(value.expires_at)<=Date.now()
      ||!exact(donation,['donation_id','campaign_key','campaign_version'])||!uuid(donation.donation_id)
      ||donation.campaign_key!==binding.campaign.key||donation.campaign_version!==binding.campaign.version)fail();
    const identity:SandboxDonationIdentity={checkoutId:value.checkout_id as string,paymentId:value.payment_id as string,
      donationId:donation.donation_id as string,campaignKey:binding.campaign.key,campaignVersion:binding.campaign.version,amountMinor:500,currency:'EUR'};
    await runSandboxBrowserStep(kind,'DONATION_ACK',async()=>{if(await extras.ackDonationCreate(kind,identity,limits)!==true)fail();});
  }catch(error){rethrowSandboxBrowserFailure(error,'DONATION_PENDING');}
}

export async function runDonationOtp(page:Page,extras:Pick<SandboxAcceptanceExtras,'beginOtp'|'ackOtpChallenge'|'withOtpCode'|'beforeOtpVerify'|'ackOtpVerification'>,binding:OwnedSandboxPublicBinding,limits:OwnedSandboxLimits):Promise<void> {
  let email:string|undefined,code:string|undefined,challengeId:string|undefined,challengePosts=0,verifyPosts=0;
  const challengeUrl=binding.endpoints.backend+'/api/v1/public/customer-auth/email-otp/challenges';
  const observe=(request:Request)=>{
    if(request.method()!=='POST')return;
    if(request.url()===challengeUrl)challengePosts++;
    else if(request.url().startsWith(challengeUrl+'/'))verifyPosts++;
  };
  try {
    timeout(limits);const widget=page.locator('generic-email-otp').first();page.on('request',observe);
    if(await extras.beginOtp(value=>{if(email!==undefined||value.siteKey!==binding.site.key||typeof value.email!=='string')fail();email=value.email;return true;},limits)!==true||email===undefined)fail();
    await bounded(()=>enterPrivateText(widget.getByRole('textbox',{name:'Email',exact:true}),email!),limits);
    const challengeResponse=page.waitForResponse(response=>response.url()===challengeUrl&&response.request().method()==='POST',{timeout:timeout(limits)});challengeResponse.catch(()=>{});
    await widget.getByRole('button',{name:'Request code',exact:true}).click({timeout:timeout(limits)});
    const challenge=await bounded(()=>challengeResponse,limits),request=challenge.request();
    const challengeBody:unknown=JSON.parse(request.postData()??'');
    if(challenge.status()!==202||request.redirectedFrom()||!exact(challengeBody,['site_key','email','intent'])
      ||challengeBody.site_key!==binding.site.key||challengeBody.email!==email||challengeBody.intent!=='AUTHENTICATE'||challengePosts!==1)fail();
    const value=await jsonResponse(challenge,limits);email=undefined;
    if(!exact(value,['challenge_id','expires_at'])||!uuid(value.challenge_id)||typeof value.expires_at!=='string'||!Number.isFinite(Date.parse(value.expires_at))||Date.parse(value.expires_at)<=Date.now())fail();
    challengeId=value.challenge_id as string;
    if(await extras.ackOtpChallenge({challengeId,expiresAt:value.expires_at as string},limits)!==true)fail();
    if(await extras.withOtpCode(value=>{if(code!==undefined||!/^\d{6}$/.test(value))fail();code=value;return true;},limits)!==true||code===undefined)fail();
    await bounded(()=>enterPrivateText(widget.getByRole('textbox',{name:'Six-digit code',exact:true}),code!),limits);
    if(await extras.beforeOtpVerify(limits)!==true)fail();
    const verifyUrl=challengeUrl+'/'+challengeId+'/verify',meUrl=binding.endpoints.backend+'/api/v1/customer/me';
    const verification=page.waitForResponse(response=>response.url()===verifyUrl&&response.request().method()==='POST',{timeout:timeout(limits)});verification.catch(()=>{});
    const activeCustomer=page.waitForResponse(response=>response.url()===meUrl&&response.request().method()==='GET',{timeout:timeout(limits)});activeCustomer.catch(()=>{});
    await widget.getByRole('button',{name:'Verify code',exact:true}).click({timeout:timeout(limits)});
    const verified=await bounded(()=>verification,limits),verifyRequest=verified.request(),verifyBody:unknown=JSON.parse(verifyRequest.postData()??'');
    if(verified.status()!==200||verifyRequest.redirectedFrom()||!exact(verifyBody,['site_key','code'])||verifyBody.site_key!==binding.site.key||verifyBody.code!==code||challengePosts!==1||verifyPosts!==1)fail();
    code=undefined;const session=await jsonResponse(verified,limits),customer=session.customer;
    if(!exact(session,['session_token','expires_at','scopes','customer',...(Object.hasOwn(session,'site_id')?['site_id']:[])])
      ||typeof session.session_token!=='string'||!/^[-_A-Za-z0-9]{43}$/.test(session.session_token)
      ||typeof session.expires_at!=='string'||!Number.isFinite(Date.parse(session.expires_at))||Date.parse(session.expires_at)<=Date.now()
      ||!Array.isArray(session.scopes)||!session.scopes.includes('CHECKOUT_CREATE')||!session.scopes.includes('CUSTOMER_READ')
      ||!exact(customer,['id','status','masked_email'])||!uuid(customer.id)||customer.status!=='ACTIVE'
      ||(Object.hasOwn(session,'site_id')&&session.site_id!==binding.site.id))fail();
    const currentResponse=await bounded(()=>activeCustomer,limits),current=await jsonResponse(currentResponse,limits);
    if(currentResponse.status()!==200||current.id!==customer.id||current.status!=='ACTIVE'||current.session_expires_at!==session.expires_at
      ||!Array.isArray(current.authentication_modes)||!current.authentication_modes.includes('EMAIL_OTP'))fail();
    await page.waitForFunction(({backend,site,demo,customerId})=>{
      try {
        const key=`generic-payments:customer:v1:${encodeURIComponent(backend)}:${site}:${encodeURIComponent(demo)}`;
        const record=JSON.parse(sessionStorage.getItem(key)??'null');
        return record?.siteKey===site&&record?.apiBaseUrl===backend&&record?.origin===demo&&record?.customer?.id===customerId
          &&record?.customer?.status==='ACTIVE'&&typeof record?.sessionToken==='string'&&record.sessionToken.length===43
          &&Date.parse(record.expiresAt)>Date.now()&&document.querySelector('#customer-summary')?.textContent?.includes('ACTIVE')===true;
      }catch{return false;}
    },{backend:binding.endpoints.backend,site:binding.site.key,demo:binding.endpoints.demo,customerId:customer.id as string},{timeout:timeout(limits)});
    if(challengePosts!==1||verifyPosts!==1||await extras.ackOtpVerification({challengeId,customerId:customer.id as string},limits)!==true)fail();
  }catch(error){rethrowSandboxBrowserFailure(error,'DONATION_PENDING');}finally{email=undefined;code=undefined;page.off('request',observe);}
}

async function donationContext(browser:Browser,limits:OwnedSandboxLimits):Promise<{context:BrowserContext;close:()=>Promise<void>}> {
  timeout(limits);let abandoned=false,closing:Promise<void>|undefined;
  const opening=browser.newContext({serviceWorkers:'block'});
  const closeOwned=(context:BrowserContext)=>{
    if(!closing){closing=Promise.resolve().then(()=>context.close());closing.catch(()=>{});}return closing;
  };
  // This receipt remains attached even after finite cleanup expires; no late owned context is forgotten.
  opening.then(context=>{if(abandoned)return closeOwned(context);},()=>{}).catch(()=>{});
  try {
    const context=await bounded(()=>opening,limits);
    return {context,close:()=>closeOwned(context)};
  }catch(error){
    abandoned=true;
    // Creation and actual close share one independent cleanup budget. Neither can wait indefinitely.
    try{await bounded(async()=>{await closeOwned(await opening);},{deadline:Date.now()+15000});}catch{}
    rethrowSandboxBrowserFailure(error,'DONATION_PENDING');
  }
}

export async function runOwnedSandboxDonations(browser:Browser,extras:SandboxAcceptanceExtras,binding:OwnedSandboxPublicBinding,limits:OwnedSandboxLimits):Promise<true> {
  for(const kind of ['anonymous','emailOtp'] as const) {
    const owner=await runSandboxBrowserStep(kind,'DONATION_SETUP',()=>donationContext(browser,limits)),context=owner.context;
    let timer:ReturnType<typeof setTimeout>|undefined,failed=false,primary:unknown;
    const routes={closing:false,pending:new Set<Promise<void>>(),
      fail(error:unknown){if(!failed){failed=true;primary=error;}return primary;},
      check(){if(failed)rethrowSandboxBrowserFailure(primary,'DONATION_PENDING');}};
    const abort=()=>{routes.closing=true;void owner.close().catch(()=>{});};
    try {
      const page=await runSandboxBrowserStep(kind,'DONATION_SETUP',async()=>{
        limits.signal?.addEventListener('abort',abort,{once:true});
        if(limits.signal?.aborted){abort();fail();}
        timer=setTimeout(abort,timeout(limits,2700000));
        const page=await bounded(()=>context.newPage(),limits),url=new URL('/',binding.endpoints.demo);
        for(const [key,value] of Object.entries({api_base_url:binding.endpoints.backend,site_key:binding.site.key,price_key:binding.price.key,campaign_key:binding.campaign.key,locale:'en'}))url.searchParams.set(key,value);
        await page.goto(url.href,{timeout:timeout(limits)});
        if(kind==='emailOtp')await runDonationOtp(page,extras,binding,limits);
        else if(!await page.evaluate(()=>!Object.keys(sessionStorage).some(key=>key.startsWith('generic-payments:customer:'))))fail();
        return page;
      });
      await donationPayment(page,context,extras,kind,binding,limits,routes);
    }catch(error){routes.fail(error);}
    finally{
      routes.closing=true;clearTimeout(timer);limits.signal?.removeEventListener('abort',abort);
      // Close and every owned route callback share this one existing cleanup ceiling.
      try{await runSandboxBrowserStep(kind,'DONATION_CLOSE_CONTEXT',()=>bounded(async()=>{
        try{await runSandboxBrowserStep(kind,'DONATION_CLOSE_CONTEXT',()=>owner.close());}catch(error){routes.fail(error);}
        while(routes.pending.size)await Promise.allSettled([...routes.pending]);
      },{deadline:Date.now()+15000}));}
      catch(error){routes.fail(error);}
    }
    if(failed)rethrowSandboxBrowserFailure(primary,'DONATION_PENDING');
  }
  return true;
}

async function donationPayment(page:Page,context:BrowserContext,extras:SandboxAcceptanceExtras,kind:SandboxDonationKind,binding:OwnedSandboxPublicBinding,limits:OwnedSandboxLimits,
  routes:{closing:boolean;pending:Set<Promise<void>>;fail(error:unknown):unknown;check():void}):Promise<void> {
  let posts=0,responses=0,original:Request|undefined,routeFailure=false;
  const checkRouteFailure=()=>routes.check();
  let resolveForward!:()=>void,rejectForward!:(error:unknown)=>void;
  const forwarded=new Promise<void>((resolve,reject)=>{resolveForward=resolve;rejectForward=reject;});
  forwarded.catch(()=>{});
  await runSandboxBrowserStep(kind,'DONATION_SETUP',()=>context.route(endpoint(binding),route=>{
    const work=(async()=>{
      try {
        let preflight=false;
        await runSandboxBrowserStep(kind,'DONATION_DISPATCH',async()=>{
          if(routes.closing)fail();checkRouteFailure();
          preflight=route.request().method()==='OPTIONS';
          if(!preflight){if(++posts!==1||route.request().method()!=='POST')fail();original=route.request();}
          await forwardDonationCreate(route,extras,kind,binding,limits);
        });if(!preflight)resolveForward();
      }catch(error){
        routeFailure=true;rejectForward(routes.fail(error));
        try{await bounded(()=>route.abort('failed'),{deadline:Date.now()+15000});}catch{}
      }
    })();
    routes.pending.add(work);work.then(()=>routes.pending.delete(work),()=>routes.pending.delete(work));return work;
  }));
  const observe=(response:Response)=>{if(response.url()===endpoint(binding)&&response.request().method()==='POST')responses++;};
  page.on('response',observe);
  try {
    const widget=await runSandboxBrowserStep(kind,'DONATION_SETUP',async()=>{
      const widget=page.locator('generic-donation').first();
      await widget.locator('button[data-amount="500"]').click({timeout:timeout(limits)});
      if(!await widget.locator('textarea').evaluate(element=>(element as HTMLTextAreaElement).value===''))fail();
      return widget;
    });
    const {genuine}=await runSandboxBrowserStep(kind,'DONATION_DISPATCH',async()=>{
      const genuine=page.waitForResponse(response=>response.url()===endpoint(binding)&&response.request().method()==='POST',{timeout:timeout(limits)});
      genuine.catch(()=>{});
      try{
        await widget.getByRole('button',{name:'Create donation',exact:true}).click({timeout:timeout(limits)});
        await bounded(()=>forwarded,limits);checkRouteFailure();
      }catch(error){checkRouteFailure();rethrowSandboxBrowserFailure(error,'DONATION_PENDING');}
      return {genuine};
    });
    await runSandboxBrowserStep(kind,'DONATION_RESPONSE',async()=>{
      checkRouteFailure();
      const response=await bounded(()=>genuine,limits);
      checkRouteFailure();
      if(!original||posts!==1||responses!==1||routeFailure)fail();
      await acknowledgeDonationResponse(response,original,{ackDonationCreate:(ackKind,identity,ackLimits)=>{
        checkRouteFailure();return extras.ackDonationCreate(ackKind,identity,ackLimits);
      }},kind,binding,limits);
      checkRouteFailure();
    });
    await runSandboxBrowserStep(kind,'DONATION_PAYMENT',async()=>{
      checkRouteFailure();
      const frame=widget.frameLocator('iframe[title="Secure payment input frame"]');
      await frame.locator('input[name="number"]').fill('4242424242424242',{timeout:timeout(limits)});
      checkRouteFailure();
      await frame.locator('input[name="expiry"]').fill('1230',{timeout:timeout(limits)});
      checkRouteFailure();
      await frame.locator('input[name="cvc"]').fill('123',{timeout:timeout(limits)});
      checkRouteFailure();
      const postal=frame.locator('input[name="postalCode"]');if(await postal.isVisible()){checkRouteFailure();await postal.fill('10001',{timeout:timeout(limits)});}
      checkRouteFailure();
      if(await extras.beforeDonationPay(kind,limits)!==true||posts!==1||responses!==1||routeFailure)fail();
      await widget.getByRole('button',{name:'Pay',exact:true}).click({timeout:timeout(limits)});
      checkRouteFailure();
      if(await extras.confirmDonation(kind,{deadline:Math.min(limits.deadline,Date.now()+45000),signal:limits.signal})!==true||posts!==1||responses!==1||routeFailure)fail();
    });
  }catch(error){rethrowSandboxBrowserFailure(routes.fail(error),'DONATION_PENDING');}
  finally{page.off('response',observe);}
}
