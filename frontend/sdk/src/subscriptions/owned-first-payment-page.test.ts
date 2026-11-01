import {expect, it, vi} from 'vitest';
import {JSDOM} from 'jsdom';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {transpileModule, ModuleKind, ScriptTarget} from 'typescript';
const seam=vi.hoisted(()=>({client:null as any,mount:null as any,load:vi.fn()}));
vi.mock('../providers/stripe-loader.js',()=>({loadStripeClient:seam.load}));
const categories=['GUARD','STATE','SUBMIT_VALIDATION','SUBMIT','TOKEN_VALIDATION','TOKEN','CLIENT','NEXT_ACTION','RETIRED'] as const;
const html='<body><button id="request" disabled></button><button id="verify" disabled></button><button id="start" disabled></button><button id="pay" disabled></button><input id="code" disabled><input id="consent" type="checkbox" disabled><div id="provider"></div><span id="status"></span></body>';
async function page(options:{category?:string;receipt?:any;pending?:boolean;realDriver?:boolean;validation?:boolean;throwConfirm?:boolean}={},body:(f:any)=>Promise<void>){
 vi.resetModules();
 const realm=new JSDOM(html,{url:'http://localhost:3000',runScripts:'outside-only'});
 const blocked=vi.fn(()=>{throw new Error('offline test transport blocked');});
 realm.window.fetch=blocked as any;
 for(const name of ['XMLHttpRequest','WebSocket','EventSource','Worker'])Object.defineProperty(realm.window,name,{configurable:true,value:class {constructor(){blocked();}}});
 const originalGlobals=new Map<string,PropertyDescriptor|undefined>();
 originalGlobals.set('fetch',Object.getOwnPropertyDescriptor(globalThis,'fetch'));Object.defineProperty(globalThis,'fetch',{configurable:true,writable:true,value:blocked});
 for(const name of ['window','document','location','HTMLElement','ShadowRoot','MutationObserver','DOMException','AbortController','Event']){originalGlobals.set(name,Object.getOwnPropertyDescriptor(globalThis,name));Object.defineProperty(globalThis,name,{configurable:true,writable:true,value:(realm.window as any)[name]});}
 const w=realm.window as any;let retire:any,release:any;let handle:any;const duringDestroy:unknown[]=[];
 const confirm=vi.fn(),clientConfirm=vi.fn(),destroy=vi.fn(),submit=vi.fn(async()=>options.validation?{error:{}}:{}),token=vi.fn(async()=>({confirmationToken:{id:'ctoken_fixture'}}));
 try {
  const diagnostic=await import('./stripe-payment-attempt-diagnostic.js');
  const session={apiBaseUrl:'https://api.test',siteKey:'test-site',siteId:'08ac693f-6f49-4a8d-bd14-e5ef47a1dc18',origin:w.location.origin,sessionToken:'T'.repeat(43),customer:{id:'3aaa2ff0-bad1-40bc-8619-d862d0932230',status:'ACTIVE',maskedEmail:null},expiresAt:new Date(Date.now()+60000).toISOString(),scopes:['SUBSCRIPTION_READ','SUBSCRIPTION_MANAGE']};
  const receipt=options.receipt??{state:'OBSERVE',retryAllowed:false,successorAttemptId:null};
  const context={protocol:'SERVER_CONFIRM_V2',flow:'PAYMENT',publishableKey:'pk_test_fixture',clientSecret:'pi_fixture_secret_fixture',attemptId:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',capability:'bca2_'+'A'.repeat(43),expiresAt:new Date(Date.now()+50000).toISOString()};
  seam.client={requestEmailOtp:vi.fn(async()=>({challengeId:'challenge'})),verifyEmailOtp:vi.fn(async()=>session),subscriptions:{action:vi.fn(async()=>({})),confirmationSession:vi.fn(async()=>context),confirmPaymentAttempt:clientConfirm}};
  w.__firstPaymentPrepare=vi.fn(async()=>({subscriptionId:'8ad38d60-f78a-44b3-9881-722eea250289',operationId:'f1d1b7e8-4158-40d1-ae13-ea053568f314'}));
  seam.mount=async(optionsMount:any)=>{
   retire=optionsMount.onRetire;
   if(options.realDriver){
    const {registerSubscriptionAuthority}=await import('./authority.js');const {bindPaymentSession}=await import('./confirmation-v2.js');const driver=await import('./stripe-payment-attempt-driver.js');
    registerSubscriptionAuthority(seam.client.subscriptions,()=>()=>{});bindPaymentSession(seam.client.subscriptions,optionsMount.input,context as any);
    vi.spyOn(w.HTMLElement.prototype,'getClientRects').mockReturnValue([{width:300,height:100}] as any);
    if(options.pending)token.mockImplementation(()=>new Promise(r=>{release=r;}));
    seam.load.mockResolvedValue({elements:()=>({submit,create:()=>{let ready=()=>{};return {on:(name:string,cb:any)=>{if(name==='ready')ready=cb;},off:vi.fn(),mount:()=>queueMicrotask(ready),destroy};}}),createConfirmationToken:token});
    clientConfirm.mockImplementation(async()=>receipt);handle=await driver.mountStripePaymentAttempt(optionsMount);return handle;
   }
   const pending=new Promise(r=>{release=r;});
   handle={protocol:'SERVER_CONFIRM_V2',destroy:()=>{duringDestroy.push(w.__firstPaymentFailure());destroy();if(options.pending)diagnostic.retainStripePaymentAttemptFailure(handle,'RETIRED');retire();},confirm:confirm.mockImplementation(async()=>{
    if(options.throwConfirm)throw Object.create(null);
    if(options.pending)return await pending;
    if(options.category){diagnostic.retainStripePaymentAttemptFailure(handle,options.category);return {validationFailed:true};}
    retire();return receipt;
   })};return handle;
  };
  // Execute the actual coordinator in an isolated realm. Only its import boundary
  // is inert; no package copy, application server, resource loading or provider.
  const source=readFileSync(resolve(process.cwd(),'../../examples/demo-site/src/billing-first-payment.ts'),'utf8');
  expect(source).toContain("from '../../../frontend/sdk/src/subscriptions/stripe-payment-attempt-diagnostic.js'");
  const script=transpileModule(source,{compilerOptions:{module:ModuleKind.ESNext,target:ScriptTarget.ES2022}}).outputText.replace(/^import .*;\s*$/gm,'').replace(/^export \{\};?\s*$/gm,'');
  w.__boundaries={createGenericPaymentClient:()=>seam.client,mountStripePaymentAttempt:(value:any)=>seam.mount(value),readStripePaymentAttemptFailure:diagnostic.readStripePaymentAttemptFailure};
  w.eval('const {createGenericPaymentClient,mountStripePaymentAttempt,readStripePaymentAttemptFailure}=__boundaries;\n'+script);
  delete w.__boundaries;
  const click=async(id:string)=>{await w.document.getElementById(id).onclick();};
  const snapshot=()=>w.__firstPaymentFailure();
  expect(snapshot()).toBeNull();
  w.__firstPaymentConfigure({backend:'https://api.test',siteKey:'test-site',email:'fixture@example.test',deadline:Date.now()+50000});
  expect(seam.client.requestEmailOtp).not.toHaveBeenCalled();
  for(const name of ['sessionStorage','localStorage']){expect(Object.getOwnPropertyDescriptor(w,name)?.configurable).toBe(false);expect(()=>w[name]).toThrow();}
  await click('request');expect(seam.client.requestEmailOtp).toHaveBeenCalledTimes(1);expect(w.document.body.dataset.phase).toBe('CODE');w.document.getElementById('code').value='123456';await click('verify');expect(w.document.body.dataset.phase).toBe('AUTHENTICATED');w.document.getElementById('consent').checked=true;expect(typeof crypto.randomUUID).toBe('function');await click('start');
  expect(w.document.body.dataset.phase).toBe('READY');expect(snapshot()).toBeNull();
  await body({w,click,snapshot,confirm,clientConfirm,destroy,submit,token,release:(value:any)=>release(value),handle:()=>handle,duringDestroy});
  expect(blocked).not.toHaveBeenCalled();
 } finally {
  try {
   w.dispatchEvent(new w.Event('pagehide'));realm.window.close();
  } finally {
   for(const [name,descriptor] of originalGlobals){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}
  }
  expect(blocked).not.toHaveBeenCalled();
 }
}
it.each(categories)('actual page retains terminal %s once, with one Pay and a read-only primitive snapshot',async category=>{
 await page({category},async f=>{const descriptor=Object.getOwnPropertyDescriptor(f.w,'__firstPaymentFailure');expect(descriptor).toMatchObject({configurable:false,writable:false,enumerable:false});await f.click('pay');expect(f.w.document.body.dataset.phase).toBe('STOPPED');expect(f.snapshot()).toBe(category);expect(f.duringDestroy).toEqual([null]);await f.click('pay');expect(f.confirm).toHaveBeenCalledTimes(1);expect(f.w.__firstPaymentPrepare).toHaveBeenCalledTimes(1);expect(JSON.stringify(f.snapshot())).not.toMatch(/pk_|pi_|bca2_|ctoken_|customer|session/);});
});
it.each([{state:'DECLINED',retryAllowed:false,successorAttemptId:null},{state:'OBSERVE',retryAllowed:true,successorAttemptId:null},{state:'OBSERVE',retryAllowed:false,successorAttemptId:'foreign'}])('page refuses invalid receipt as RECEIPT: %j',async receipt=>{await page({receipt},async f=>{await f.click('pay');expect(f.snapshot()).toBe('RECEIPT');expect(f.w.document.body.dataset.phase).toBe('STOPPED');expect(f.confirm).toHaveBeenCalledTimes(1);});});
it('missing SDK provenance remains null after validation refusal',async()=>{await page({category:'UNKNOWN'},async f=>{await f.click('pay');expect(f.snapshot()).toBeNull();expect(f.w.document.body.dataset.phase).toBe('STOPPED');});});
it('lifecycle stop while confirm is pending synchronously captures retirement before clearing handle',async()=>{await page({pending:true},async f=>{const running=f.click('pay');expect(f.snapshot()).toBeNull();await f.click('pay');expect(f.confirm).toHaveBeenCalledTimes(1);f.w.dispatchEvent(new f.w.Event('pagehide'));expect(f.snapshot()).toBe('RETIRED');expect(f.w.document.body.dataset.phase).toBe('STOPPED');f.release({state:'OBSERVE',retryAllowed:false,successorAttemptId:null});await running;expect(f.snapshot()).toBe('RETIRED');expect(f.confirm).toHaveBeenCalledTimes(1);});});
it('actual page and actual driver share helper identity for submit validation and prohibit a second Pay',async()=>{await page({realDriver:true,validation:true},async f=>{await f.click('pay');expect(f.snapshot()).toBe('SUBMIT_VALIDATION');expect(f.w.document.body.dataset.phase).toBe('STOPPED');await f.click('pay');expect(f.submit).toHaveBeenCalledTimes(1);expect(f.token).not.toHaveBeenCalled();expect(f.clientConfirm).not.toHaveBeenCalled();});});
it('actual driver successful final retirement before await resolution preserves page OBSERVE',async()=>{await page({realDriver:true},async f=>{await f.click('pay');expect(f.w.document.body.dataset.phase).toBe('OBSERVE');expect(f.snapshot()).toBeNull();expect(f.clientConfirm).toHaveBeenCalledTimes(1);expect(f.submit).toHaveBeenCalledTimes(1);expect(f.token).toHaveBeenCalledTimes(1);expect(f.destroy).toHaveBeenCalledTimes(1);await f.click('pay');expect(f.clientConfirm).toHaveBeenCalledTimes(1);});});

it('actual pending driver retirement is captured synchronously by page lifecycle stop',async()=>{await page({realDriver:true,pending:true},async f=>{const running=f.click('pay');await vi.waitFor(()=>expect(f.token).toHaveBeenCalledTimes(1));await f.click('pay');f.w.dispatchEvent(new f.w.Event('pagehide'));expect(f.snapshot()).toBe('RETIRED');expect(f.w.document.body.dataset.phase).toBe('STOPPED');f.release({confirmationToken:{id:'ctoken_late'}});await running;expect(f.snapshot()).toBe('RETIRED');expect(f.clientConfirm).not.toHaveBeenCalled();expect(f.submit).toHaveBeenCalledTimes(1);expect(f.token).toHaveBeenCalledTimes(1);});});
it('thrown confirmation without retained provenance remains generic',async()=>{await page({throwConfirm:true},async f=>{await f.click('pay');expect(f.snapshot()).toBeNull();expect(f.w.document.body.dataset.phase).toBe('STOPPED');expect(f.confirm).toHaveBeenCalledTimes(1);});});

it('restores original global descriptors when the inert transport guard rejects the fixture',async()=>{
 const names=['fetch','window','document','location','HTMLElement','ShadowRoot','MutationObserver','DOMException','AbortController','Event'];
 const originals=new Map(names.map(name=>[name,Object.getOwnPropertyDescriptor(globalThis,name)]));
 try {
  await expect(page({},async f=>{expect(()=>f.w.fetch('inert-transport-canary')).toThrow('offline test transport blocked');})).rejects.toThrow(/not.*called/);
  for(const [name,original] of originals){
   const actual=Object.getOwnPropertyDescriptor(globalThis,name);
   expect(!!actual===!!original,name+' descriptor presence').toBe(true);
   for(const key of ['configurable','enumerable','writable','value','get','set'] as const)expect(Object.is(actual?.[key],original?.[key]),name+' descriptor '+key).toBe(true);
  }
 } finally {
  // Keep even the deliberately RED version safe for shared SDK teardown.
  for(const [name,descriptor] of originals){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}
 }
});
