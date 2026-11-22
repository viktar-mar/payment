import test from 'node:test';import assert from 'node:assert/strict';import {readFile} from 'node:fs/promises';
// @ts-expect-error Native Node type erasure requires explicit source extension.
import {forwardBillingDispatch,runInitialBillingBrowser,runBillingCancellationBrowser} from './stripe-billing-acceptance.ts';
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
 assert.equal(number(owner,/let ok=true;const limits=\{deadline:Date\.now\(\)\+(\d+)/),cleanupBudget);
 assert.equal(runnerBudget,ownerBudget+cleanupBudget+60*1000);
 assert.equal(runnerBudget,48*60*1000);
 assert.equal(globalBudget,50*60*1000);
 assert.ok(globalBudget>runnerBudget);
});

// A single in-memory page sends SDK-shaped requests only in response to visible manager
// controls. It cannot make network calls. Responses retain their originating request.
import type {Browser, Request, Response} from '@playwright/test';
import type {BillingCancellationPort} from '../../../../infrastructure/stripe-billing-acceptance.mjs';

function cancellationPage(fault = '') {
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
 const trace:string[] = [], sent:string[] = [];
 const frame = {page:()=>page,isDetached:()=>false,url:()=>documentUrl};
 const stripeFrame = {page:()=>page,isDetached:()=>verified&&fault==='detached',url:()=>verified&&fault==='wrong-parent'?'https://unowned.invalid/':'https://js.stripe.com/v3/frame.html',parentFrame:()=>frame};
 const providerSent:string[]=[];
 let payClicked=false, interceptsProvider=false;
 let documentUrl = '', closed = false, verified = false, displayed:'initial'|'schedule'|'withdraw' = 'initial';
 let ack:'schedule'|'withdraw'|undefined, refreshed = false;
 let releaseBody:undefined|(()=>void), duplicateConfirm:undefined|(()=>Promise<void>);
 let releaseDispatch:undefined|(()=>void);
 let handler:(route:Route)=>Promise<void>;
 let socketHandler:undefined|((socket:{close:()=>void})=>void);
 let navigate:(frame:unknown)=>void = ()=>{};
 const waiters = new Set<{predicate:(r:Response)=>boolean;resolve:(r:Response)=>void;reject:(e:Error)=>void}>();
 function publish(request:Request, status:number, json:object) {
  const response = {request:()=>request,url:()=>request.url(),status:()=>status,fromServiceWorker:()=>false,body:async()=>Buffer.from(JSON.stringify(json))} as Response;
  for(const w of waiters) if(w.predicate(response)){waiters.delete(w);w.resolve(response);}
 }
 async function provider(url:string,owner:unknown=stripeFrame){
  if(!interceptsProvider){providerSent.push(url);return;}
  const tokenCall=url.endsWith('/confirmation_tokens');
  const badSupport=!tokenCall;
  let tokenResponse:Response|null=null;
  const request={url:()=>url,method:()=>'POST',frame:()=>owner,isNavigationRequest:()=>false,redirectedFrom:()=>badSupport&&fault==='telemetry-redirect'?{}:null,
   existingResponse:()=>tokenResponse,
   allHeaders:async()=>badSupport&&fault==='telemetry-auth'?{authorization:'Bearer '+token}:badSupport&&fault==='telemetry-cookie'?{cookie:'provider=demo'}:{origin:'https://js.stripe.com'}} as unknown as Request;
  await handler({request:()=>request,fallback:async()=>{
   providerSent.push(url);
   if(tokenCall&&fault!=='missing-token-response')tokenResponse={
    request:()=>fault==='wrong-token-request'?{...request}:request,url:()=>url,
    status:()=>fault==='token-failure'?402:200,fromServiceWorker:()=>fault==='token-worker',
    body:async()=>{
     if(fault==='pending-token-body')await new Promise<void>(resolve=>{setTimeout(resolve,5);});
     if(fault==='concurrent-confirm')await new Promise<void>(resolve=>{releaseBody=resolve;void duplicateConfirm?.().catch(()=>{}).finally(resolve);});
     return Buffer.from(JSON.stringify({object:fault==='token-type'?'payment_method':'confirmation_token',id:'ctoken_demo',livemode:fault==='live-token'}));
    }
   } as Response;
  },continue:async()=>{providerSent.push(url);},abort:async()=>{}} as unknown as Route);
  if(closed)throw new Error('closed');
 }
 async function send(url:string, value:object, result:object, status=202, method='POST') {
  const schedulePost = url===cancel, withdrawPost=url===cancel+'/withdraw';
  const key = schedulePost?id(9):withdrawPost?(fault==='same-key'?id(9):id(10)):'initial-key';
  const headers = {'idempotency-key':key,authorization:'Bearer '+token,origin:b.endpoints.demo,'content-type':'application/json'};
  if(schedulePost && fault==='origin')headers.origin='https://elsewhere.invalid';
  if(schedulePost && fault==='cookie')Object.assign(headers,{cookie:'session=demo'});
  if(schedulePost && fault==='token')headers.authorization='Bearer '+'B'.repeat(43);
  const request = {
   url:()=>schedulePost && fault==='foreign-endpoint'?url.replace(sub!,id(19)):
    schedulePost && fault==='foreign-host'?url.replace(b.endpoints.backend,'https://unowned.invalid'):url,
   method:()=>method,
   frame:()=>schedulePost && fault==='wrong-frame'?{}:frame,isNavigationRequest:()=>false,
   redirectedFrom:()=>schedulePost&&fault==='redirect'?{}:null,allHeaders:async()=>headers,
   postData:()=>withdrawPost && fault==='malformed-withdraw'?'{"mode":"PERIOD_END"}':
    JSON.stringify(schedulePost&&fault==='immediate'?{mode:'IMMEDIATE'}:value)
  } as unknown as Request;
  const route = {request:()=>request,continue:async(...args:unknown[])=>{
   assert.deepEqual(args,[]);sent.push(request.url());
   publish(schedulePost&&fault==='foreign-request'?{...request} as Request:request,status,result);
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
  evaluate:async(fn:(m:unknown)=>unknown)=>fn(manager),
  frameLocator:()=>({locator:()=>({fill:async()=>{},isVisible:async()=>false})}),
  getByRole:(_role:string,opts:{name:string})=>({
   check:async()=>{trace.push(opts.name);},waitFor:async()=>{},
   evaluate:async()=>true,evaluateHandle:async()=>({evaluate:async(fn:(get:()=>boolean)=>boolean)=>fn(()=>payClicked)}),
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
     void send(select,{action_token:'bga1_'+'G'.repeat(43)},{protocol:'SERVER_CONFIRM_V2',provider:'stripe',flow:'PAYMENT',attempt_id:attempt},200).catch(()=>{});
    }
    if(name==='Pay'){
     if(fault==='before-actual-click')await provider('https://api.stripe.com/v1/confirmation_tokens');
     payClicked=true;
     if(fault!=='absent-token'&&fault!=='initial-only')await provider('https://api.stripe.com/v1/confirmation_tokens');
     if(fault==='token-replay')await provider('https://api.stripe.com/v1/confirmation_tokens');
     const submit=()=>send(confirm,{capability:'bca2_'+'C'.repeat(43),confirmation_token:fault==='substituted-token'?'ctoken_other':'ctoken_demo'},{protocol:'SERVER_CONFIRM_V2',attempt_id:attempt,state:'OBSERVE',next_action_allowed:false,retry_allowed:false,successor_attempt_id:null},200);
     duplicateConfirm=submit;
     await submit();
     if(fault==='early')await send(cancel,{mode:'PERIOD_END'},{});
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
  mainFrame:()=>frame,url:()=>documentUrl,waitForFunction:async()=>{},
  on:(event:string,fn:typeof navigate)=>{if(event==='framenavigated')navigate=fn;},
  goto:async(url:string)=>{documentUrl=url;},locator:()=>({first:()=>manager}),
  waitForResponse:(predicate:(r:Response)=>boolean)=>new Promise<Response>((resolve,reject)=>{if(closed)reject(new Error('closed'));else waiters.add({predicate,resolve,reject});})
 };
 const context={
  newPage:async()=>page,on:()=>{},routeWebSocket:async(_pattern:unknown,fn:typeof socketHandler)=>{socketHandler=fn;},route:async(pattern:unknown,fn:typeof handler)=>{interceptsProvider=pattern==='**/*';handler=fn;},
  close:async()=>{closed=true;releaseBody?.();releaseDispatch?.();for(const w of waiters)w.reject(new Error('closed'));waiters.clear();}
 };
 const port={
  resources:()=>b,beforeDispatch:async(kind:string)=>{
   if(kind==='confirm'&&fault==='duplicate-during-persist')await new Promise<void>(resolve=>{releaseDispatch=resolve;void duplicateConfirm?.().catch(()=>{}).finally(resolve);});
   return true;
  },acknowledge:async()=>true,acknowledgeCustomer:async()=>true,
  withOtpCode:async(callback:(code:string)=>Promise<true>)=>callback('123456'),
  verifyInitial:async()=>{trace.push('verify initial');verified=true;return fault!=='initial-proof';},
  beforeCancellation:async(kind:string,key:string)=>{trace.push('persist '+kind+' '+key);return fault!=='persist';},
  verifyCancellation:async(kind:string,operation:string)=>{trace.push('verify '+kind+' '+operation);return fault!=='proof';}
 } as BillingCancellationPort;
 return {browser:{newContext:async()=>context} as unknown as Browser,port,trace,sent,providerSent,cancel,id,displayed:()=>displayed,refreshed:()=>refreshed,closed:()=>closed};
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
