import type {Browser,Route,Response,BrowserContext} from '@playwright/test';
import type {BillingPort,BillingBinding,BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
// @ts-ignore Node's offline test entry consumes source; bundlers resolve the same module.
import {enterPrivateText} from './private-entry.ts';
const fail=():never=>{throw new Error('billing_BROWSER');};
const need=(v:unknown):void=>{if(!v)fail();};
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>object(v)&&Object.keys(v).sort().join(',')===[...keys].sort().join(',');
const duration=(l:BillingLimits)=>{billingCheck(l);return Math.min(45000,l.deadline-Date.now());};
async function body(response:Response,l:BillingLimits):Promise<Record<string,unknown>>{const bytes=await billingBounded(l,()=>response.body());need(bytes.length<=65536);const v:unknown=JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes));need(object(v));return v as Record<string,unknown>;}
/** Observes and persists the SDK's own key. Never changes body/headers, fetches, replays or fulfills a financial request. */
export async function forwardBillingDispatch(route:Pick<Route,'request'|'continue'>,port:Pick<BillingPort,'beforeDispatch'>,binding:BillingBinding,authority:string,kind:'create'|'confirm',identity:Record<string,string>|undefined,limits:BillingLimits):Promise<void>{
 try{billingCheck(limits);const request=route.request(),base=binding.endpoints.backend+'/api/v1/customer/subscriptions';
  const expected=kind==='create'?base:base+'/'+identity?.subscriptionId+'/operations/'+identity?.operationId+'/confirmation-attempts/'+identity?.attemptId+'/confirm';
  need(request.url()===expected&&request.method()==='POST'&&!request.redirectedFrom());const headers=await request.allHeaders(),value:unknown=JSON.parse(request.postData()??'');
  need(headers.authorization===authority&&/^Bearer [A-Za-z0-9_-]{43}$/.test(authority)&&headers.origin===binding.endpoints.demo&&headers['content-type']==='application/json'&&!headers.cookie&&typeof headers['idempotency-key']==='string'&&/^[A-Za-z0-9:_-]{1,255}$/.test(headers['idempotency-key']));
  if(kind==='create')need(exact(value,['price_id','recurring_consent'])&&value.price_id===binding.priceId&&value.recurring_consent===true);
  else need(exact(value,['capability','confirmation_token'])&&typeof value.capability==='string'&&/^bca2_[A-Za-z0-9_-]{43}$/.test(value.capability)&&typeof value.confirmation_token==='string'&&/^ctoken_[A-Za-z0-9]{1,200}$/.test(value.confirmation_token));
  await port.beforeDispatch(kind,headers['idempotency-key']!,identity);billingCheck(limits);await route.continue();billingCheck(limits);
 }catch{fail();}
}
/** Genuine demo OTP + one manager + visible ready provider Element. Only original server reads settle it. */
export async function runInitialBillingBrowser(browser:Browser,port:BillingPort,limits:BillingLimits):Promise<true>{
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
  await context.route(binding.endpoints.backend+'/**',route=>{
   const work=(async()=>{try{
    billingCheck(limits);if(failure)fail();const req=route.request(),url=req.url(),method=req.method();
    if(method==='OPTIONS'){await route.continue();return;}
    if(method==='GET'){await route.continue();return;}
    if(method!=='POST')fail();
    if(url===otp){need(++challengePosts===1);const v:unknown=JSON.parse(req.postData()??'');need(exact(v,['site_key','email','intent'])&&v.site_key===binding.siteKey&&v.email===binding.email&&v.intent==='AUTHENTICATE');await route.continue();return;}
    if(url.startsWith(otp+'/')&&url.endsWith('/verify')){need(++verifyPosts===1);await route.continue();return;}
    if(url===base){need(++createPosts===1);await forwardBillingDispatch(route,port,binding,authority,'create',undefined,limits);return;}
    await billingBounded(limits,()=>created);const original=base+'/'+subscriptionId+'/operations/'+operationId;
    if(url===original+'/confirmation-session'){
     need(++selectPosts===1);const v:unknown=JSON.parse(req.postData()??''),h=await req.allHeaders();need(exact(v,['action_token'])&&typeof v.action_token==='string'&&/^bga1_[A-Za-z0-9_-]{43}$/.test(v.action_token)&&h.authorization===authority&&h.origin===binding.endpoints.demo&&!h.cookie);await route.continue();return;
    }
    need(url===original+'/confirmation-attempts/'+attemptId+'/confirm'&&++confirmPosts===1);
    await forwardBillingDispatch(route,port,binding,authority,'confirm',{subscriptionId,operationId,attemptId},limits);
   }catch{failure=true;abort();await route.abort('failed').catch(()=>{});}})();pending.add(work);void work.finally(()=>pending.delete(work));return work;
  });
  const url=new URL('/',binding.endpoints.demo);for(const [k,v] of Object.entries({api_base_url:binding.endpoints.backend,site_key:binding.siteKey,subscription_product_1_id:binding.productId,subscription_price_1_id:binding.priceId,locale:'en'}))url.searchParams.set(k,v);
  await page.goto(url.href,{timeout:duration(limits)});const widget=page.locator('generic-email-otp').first();
  await enterPrivateText(widget.getByRole('textbox',{name:'Email',exact:true}),binding.email);
  const challenge=page.waitForResponse(r=>r.url()===otp&&r.request().method()==='POST',{timeout:duration(limits)});void challenge.catch(()=>{});await widget.getByRole('button',{name:'Request code',exact:true}).click({timeout:duration(limits)});
  const challengeResponse=await challenge;need(challengeResponse.status()===202);const challengeBody=await body(challengeResponse,limits);need(uuid(challengeBody.challenge_id));
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
  const pay=manager.getByRole('button',{name:'Pay',exact:true});await pay.waitFor({state:'visible',timeout:duration(limits)});
  // Enabled Pay is controlled by the driver's real Element ready event, not iframe insertion.
  await page.waitForFunction(()=>{const m=document.querySelector('generic-subscription-manager');return [...m?.shadowRoot?.querySelectorAll('button')??[]].some(b=>b.textContent?.trim()==='Pay'&&!b.disabled&&!!b.getClientRects().length);},undefined,{timeout:duration(limits)});
  const frame=manager.frameLocator('iframe[title="Secure payment input frame"]');await frame.locator('input[name="number"]').fill('4242424242424242',{timeout:duration(limits)});await frame.locator('input[name="expiry"]').fill('1230',{timeout:duration(limits)});await frame.locator('input[name="cvc"]').fill('123',{timeout:duration(limits)});
  const postal=frame.locator('input[name="postalCode"]');if(await postal.isVisible())await postal.fill('10001',{timeout:duration(limits)});
  const confirmed=page.waitForResponse(r=>r.url()===base+'/'+subscriptionId+'/operations/'+operationId+'/confirmation-attempts/'+attemptId+'/confirm'&&r.request().method()==='POST',{timeout:duration(limits)});void confirmed.catch(()=>{});
  await pay.click({timeout:duration(limits)});const confirmResponse=await confirmed;need(confirmResponse.status()===200);const confirmation=await body(confirmResponse,limits);need(confirmation.protocol==='SERVER_CONFIRM_V2'&&confirmation.attempt_id===attemptId&&confirmation.state==='OBSERVE'&&confirmation.next_action_allowed===false&&confirmation.retry_allowed===false&&confirmation.successor_attempt_id===null);
  await port.acknowledge('confirm',{attemptId,state:'OBSERVE',nextActionAllowed:false});
  for(;;){billingCheck(limits);if(failure)fail();const settled=await manager.evaluate((element:Element)=>{const state=(element as Element&{state?:{phase:string;subscription?:{subscriptionId:string;billingStatus:string;access:{allowed:boolean;reason:string}};operation?:{status:string}}}).state;return {phase:state?.phase,id:state?.subscription?.subscriptionId,status:state?.subscription?.billingStatus,allowed:state?.subscription?.access.allowed,reason:state?.subscription?.access.reason,operation:state?.operation?.status};});
   need(!['unknown','error','expired'].includes(settled.phase??''));if(settled.id===subscriptionId&&settled.status==='ACTIVE'&&settled.allowed&&settled.reason==='PAID')break;
   await manager.getByRole('button',{name:'Refresh status',exact:true}).click({timeout:duration(limits)});await billingBounded(limits,()=>new Promise(r=>setTimeout(r,250)),1000);
  }
  need(!failure&&createPosts===1&&confirmPosts===1&&selectPosts===1&&challengePosts===1&&verifyPosts===1);authority='';return true;
 }catch{return fail();}
 finally{abandoned=true;limits.signal?.removeEventListener('abort',abort);if(context)await billingBounded({deadline:Date.now()+15000},async()=>{await close(context!);await Promise.allSettled(pending);});}
}
