import {expect, it, vi} from 'vitest';
import {JSDOM} from 'jsdom';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {transpileModule, ModuleKind, ScriptTarget} from 'typescript';

const provider = vi.hoisted(() => ({load: vi.fn()}));
vi.mock('../providers/stripe-loader.js', () => ({loadStripeClient: provider.load}));
const subscriptionId = '8ad38d60-f78a-44b3-9881-722eea250289';
const operationId = 'f1d1b7e8-4158-40d1-ae13-ea053568f314';
const methodId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const globals = ['fetch','XMLHttpRequest','WebSocket','EventSource','Worker','window','document','location','HTMLElement','ShadowRoot','MutationObserver','DOMException','Event','getComputedStyle'];
const tick = async () => {for (let n=0;n<100;n++) await Promise.resolve();};

async function page(body:(f:any)=>Promise<void>, allowed=true) {
  vi.resetModules(); provider.load.mockReset();
  const originals = new Map(globals.map(name => [name,Object.getOwnPropertyDescriptor(globalThis,name)]));
  const realm = new JSDOM('<body><button id="request" disabled></button><button id="verify" disabled></button><button id="start" disabled></button><button id="pay" disabled></button><button id="confirm-method" disabled></button><input id="code" disabled><input id="consent" type="checkbox" disabled><div id="provider"></div><span id="status"></span></body>', {url:'http://localhost:3000',runScripts:'outside-only',pretendToBeVisual:true});
  const w = realm.window as any;
  const blocked = vi.fn(() => {throw Error('offline test transport blocked');});
  const tasks:Promise<unknown>[]=[];
  const track = (task:Promise<unknown>) => {tasks.push(task);return task;};
  try {
    for (const target of [w,globalThis]) {
      Object.defineProperty(target,'fetch',{configurable:true,writable:true,value:blocked});
      for (const name of ['XMLHttpRequest','WebSocket','EventSource','Worker']) Object.defineProperty(target,name,{configurable:true,writable:true,value:class {constructor(){blocked();}}});
    }
    for (const name of globals.slice(5)) Object.defineProperty(globalThis,name,{configurable:true,writable:true,value:name==='getComputedStyle'?w.getComputedStyle.bind(w):w[name]});
    vi.spyOn(w.HTMLElement.prototype,'getClientRects').mockReturnValue([{width:300,height:100}] as any);
    const {registerSubscriptionAuthority}=await import('./authority.js');
    const {bindPaymentSession,bindSelectedSetup}=await import('./confirmation-v2.js');
    const {bindSubscriptionAction}=await import('./action-driver.js');
    const {mountStripePaymentAttempt}=await import('./stripe-payment-attempt-driver.js');
    const diagnostics=await import('./stripe-payment-attempt-diagnostic.js');
    const {mountStripeSubscriptionMethod}=await import('./stripe-subscription-method.js');
    const expiresAt=new Date(Date.now()+60000).toISOString();
    const session={apiBaseUrl:'http://127.0.0.1:3001',siteKey:'test-site',siteId:'08ac693f-6f49-4a8d-bd14-e5ef47a1dc18',origin:w.location.origin,sessionToken:'T'.repeat(43),customer:{id:'3aaa2ff0-bad1-40bc-8619-d862d0932230',status:'ACTIVE',maskedEmail:null},expiresAt,scopes:['SUBSCRIPTION_READ','SUBSCRIPTION_MANAGE']};
    const context={protocol:'SERVER_CONFIRM_V2',flow:'PAYMENT',publishableKey:'pk_test_fixture',clientSecret:'pi_fixture_secret_fixture',attemptId:methodId,capability:'bca2_'+'A'.repeat(43),expiresAt};
    const action={kind:'PROVIDER_CLIENT',secret:'bga1_'+'M'.repeat(43),expiresAt};
    const setup={protocol:'BROWSER_CONFIRM_V1',flow:'SETUP',publishableKey:'pk_test_fixture',clientSecret:'seti_fixture_secret_fixture',actionToken:action.secret,expiresAt};
    const confirmPayment=vi.fn(async()=>({state:'OBSERVE',retryAllowed:false,successorAttemptId:null}));
    const permit=vi.fn(async()=>({operationId:methodId,subscriptionId,status:'RESERVED',actionAvailable:false,browserConfirmAllowed:allowed}));
    const subscriptions:any={confirmAction:permit,confirmPaymentAttempt:confirmPayment,
      action:vi.fn(async(input:any)=>{expect(input.session).toBe(session);return bindSubscriptionAction(subscriptions,session as any,input.operationId,input.subscriptionId,action as any);}),
      confirmationSession:vi.fn(async(input:any)=>{expect(input.session).toBe(session);return input.operationId===operationId?bindPaymentSession(subscriptions,input,context as any):bindSelectedSetup(subscriptions,input,input.action,setup as any);})};
    registerSubscriptionAuthority(subscriptions,(original,signal)=>{expect(original).toBe(session);return ()=>{if(signal?.aborted)throw Error('retired');};});
    const client={subscriptions,requestEmailOtp:vi.fn(async()=>({challengeId:'fixture'})),verifyEmailOtp:vi.fn(async()=>session)};
    const elements:any[]=[];
    const submit=vi.fn(async()=>({})),token=vi.fn(async()=>({confirmationToken:{id:'ctoken_fixture'}})),confirmSetup=vi.fn(async()=>({}));
    const createElements=vi.fn(()=>({submit,create:()=>{
      const events=new Map<string,(event?:unknown)=>void>();
      const element={events,on:vi.fn((name:string,fn:(event?:unknown)=>void)=>events.set(name,fn)),off:vi.fn(),mount:vi.fn(),destroy:vi.fn()};
      elements.push(element);return element;
    }}));
    provider.load.mockResolvedValue({elements:createElements,createConfirmationToken:token,confirmSetup});
    const terminal=vi.fn(),progress=vi.fn();
    w.__failedRenewalPayProgress=progress;w.__failedRenewalTerminal=terminal;w.__failedRenewalChallenge=vi.fn(async()=>{});w.__failedRenewalPrepare=vi.fn(async()=>({subscriptionId,operationId}));
    // Execute the actual coordinator. Only provider transport and the client wire
    // boundary are inert; both SDK mounts and their original authority checks run.
    const source=readFileSync(resolve(process.cwd(),'../../examples/demo-site/src/billing-failed-renewal-page.ts'),'utf8');
    const script=transpileModule(source,{compilerOptions:{module:ModuleKind.ESNext,target:ScriptTarget.ES2022}}).outputText.replace(/^export /gm,'');
    // Keep the outer controller/signal pair together for AbortSignal.any in the method driver.
    w.__sdk={createGenericPaymentClient:()=>client,mountStripePaymentAttempt,mountStripeSubscriptionMethod};
    w.__diagnostics=diagnostics;
    w.__Controller=globalThis.AbortController;
    w.eval('const AbortController=__Controller;\n'+script+'\ninstallFailedRenewalPage(__sdk,__diagnostics);');delete w.__sdk;delete w.__diagnostics;delete w.__Controller;
    const click=(id:string,event:any={isTrusted:false})=>track(Promise.resolve(w.document.getElementById(id).onclick(event)));
    const phase=()=>w.document.body.dataset.phase;
    w.__failedRenewalConfigure({backend:session.apiBaseUrl,siteKey:session.siteKey,email:'fixture@example.test',deadline:Date.now()+50000});
    await click('request');w.document.getElementById('code').value='123456';await click('verify');expect(phase()).toBe('AUTHENTICATED');
    w.document.getElementById('consent').checked=true;
    const start=()=>click('start');
    const emit=(index:number,name:string,event?:unknown)=>elements[index].events.get(name)?.(event);
    const ready=async(complete=true)=>{const pending=start();await tick();expect(elements).toHaveLength(1);expect(phase()).toBe('PREPARING');emit(0,'ready');await pending;expect(phase()).toBe('READY');if(complete)emit(0,'change',{elementType:'payment',complete:true,empty:false});};
    // Synthetic trusted-shaped arguments exercise the handler's positive branch;
    // JSDOM events remain untrusted and do not establish browser acceptance.
    const pay=async()=>{w.document.getElementById('pay').disabled=false;await click('pay',{isTrusted:true});};
    const method=()=>track(w.__failedRenewalMethod({subscriptionId,operationId:methodId}));
    await body({w,phase,click,start,ready,pay,method,emit,elements,createElements,context,terminal,progress,confirmPayment,permit,submit,token,confirmSetup,subscriptions,session,blocked});
    expect(blocked).not.toHaveBeenCalled();
  } finally {
    try {w.__failedRenewalStop?.();await Promise.allSettled(tasks);realm.window.close();}
    finally {for(const [name,descriptor] of originals){if(descriptor)Object.defineProperty(globalThis,name,descriptor);else Reflect.deleteProperty(globalThis,name);}}
    expect(blocked).not.toHaveBeenCalled();
  }
}

it('actual initial driver waits for ready and page leaves Pay disabled without dispatch',async()=>page(async f=>{
  const pending=f.start();await tick();expect(f.phase()).toBe('PREPARING');expect(f.elements[0].mount).toHaveBeenCalledTimes(1);expect(f.w.document.getElementById('pay').disabled).toBe(true);
  expect(f.confirmPayment).not.toHaveBeenCalled();f.emit(0,'ready');await pending;expect(f.phase()).toBe('READY');expect(f.w.document.getElementById('pay').disabled).toBe(true);expect(f.token).not.toHaveBeenCalled();expect(f.terminal).not.toHaveBeenCalled();
}));
it.each(['loaderror','pagehide','hidden'])('actual initial pending %s retires once with mount provenance and cannot revive',async reason=>page(async f=>{
  const pending=f.start();await tick();
  if(reason==='loaderror')f.emit(0,'loaderror');else if(reason==='pagehide')f.w.dispatchEvent(new f.w.Event('pagehide'));else {Object.defineProperty(f.w.document,'visibilityState',{configurable:true,value:'hidden'});f.w.document.dispatchEvent(new f.w.Event('visibilitychange'));}
  await pending;expect(f.phase()).toBe('STOPPED');expect(f.terminal.mock.calls).toEqual([['STOPPED','BROWSER_INITIAL_MOUNT']]);expect(f.elements[0].destroy).toHaveBeenCalledTimes(1);
  f.emit(0,'ready');await tick();expect(f.phase()).toBe('STOPPED');expect(f.confirmPayment).not.toHaveBeenCalled();expect(f.token).not.toHaveBeenCalled();
}));
it.each(['disabled','untrusted'])('initial %s Pay refuses confirmation after real readiness',async reason=>page(async f=>{
  await f.ready();if(reason==='untrusted')f.w.document.getElementById('pay').disabled=false;
  await f.click('pay',reason==='disabled'?{isTrusted:true}:new f.w.Event('click'));expect(f.phase()).toBe('STOPPED');expect(f.terminal.mock.calls).toEqual([['STOPPED',null]]);expect(f.submit).not.toHaveBeenCalled();expect(f.confirmPayment).not.toHaveBeenCalled();
  expect(f.progress).not.toHaveBeenCalled();
}));
it('rejects Pay when Stripe reports that previously complete input became incomplete',async()=>page(async f=>{
  await f.ready(false);
  expect(f.createElements).toHaveBeenCalledExactlyOnceWith({clientSecret:f.context.clientSecret,locale:'en'});
  expect(f.w.__failedRenewalInitialInputComplete?.()).toBe(false);
  f.emit(0,'change',{elementType:'payment',complete:true,empty:false});
  expect(f.w.__failedRenewalInitialInputComplete()).toBe(true);
  f.emit(0,'change',{elementType:'payment',complete:false,empty:false});
  expect(f.w.__failedRenewalInitialInputComplete()).toBe(false);
  await f.pay();
  expect(f.terminal.mock.calls).toEqual([['STOPPED','BROWSER_CARD_INCOMPLETE']]);
  expect(f.w.__failedRenewalInitialInputComplete()).toBe(false);
  expect(f.submit).not.toHaveBeenCalled();
  expect(f.token).not.toHaveBeenCalled();
  expect(f.confirmPayment).not.toHaveBeenCalled();
}));
it('actual initial synchronous successful retirement preserves OBSERVE and consumes original Pay once',async()=>page(async f=>{
  await f.ready();await f.pay();expect(f.phase()).toBe('OBSERVE');expect(f.elements[0].destroy).toHaveBeenCalledTimes(1);expect(f.confirmPayment).toHaveBeenCalledTimes(1);expect(f.confirmPayment.mock.calls[0][0].session).toBe(f.session);expect(f.submit).toHaveBeenCalledTimes(1);expect(f.token).toHaveBeenCalledTimes(1);expect(f.terminal).not.toHaveBeenCalled();
  await f.pay();expect(f.confirmPayment).toHaveBeenCalledTimes(1);expect(f.phase()).toBe('STOPPED');
  expect(f.progress.mock.calls).toEqual([['ENTERED'],['SUBMIT'],[null],['TOKEN'],[null]]);
  expect(f.terminal.mock.calls).toEqual([['STOPPED',null]]);
}));
it.each(['submit','token'])('reports the real %s validation branch with only fixed values',async stage=>page(async f=>{
  const providerError={message:'private canary',client_secret:'pi_fixture_secret_fixture'};
  f[stage].mockResolvedValue({error:providerError});
  await f.ready();
  await f.pay();
  const expected=stage==='submit'?'BROWSER_CONFIRM_SUBMIT_VALIDATION':'BROWSER_CONFIRM_TOKEN_VALIDATION';
  expect(f.terminal.mock.calls).toEqual([['STOPPED',expected]]);
  expect(f.w.__failedRenewalInitialConfirmFailure()).toBe(expected);
  expect(f.progress.mock.calls).toEqual(stage==='submit'?[['ENTERED'],['SUBMIT'],[null]]:[['ENTERED'],['SUBMIT'],[null],['TOKEN'],[null]]);
  expect(f.phase()).toBe('STOPPED');
  expect(f.elements[0].destroy).toHaveBeenCalledTimes(1);
  expect(f.confirmPayment).not.toHaveBeenCalled();
  await f.pay();
  expect(f.terminal).toHaveBeenCalledTimes(1);
}));
it('preserves the original client failure when the driver retires and drops raw rejection details',async()=>page(async f=>{
  const rejected=Object.create(null);
  const inspected=vi.fn(()=>{throw Error('private canary');});
  for(const name of ['code','message','cause','stack'])Object.defineProperty(rejected,name,{get:inspected});
  f.confirmPayment.mockRejectedValue(rejected);
  await f.ready();
  await f.pay();
  expect(f.terminal.mock.calls).toEqual([['STOPPED','BROWSER_CONFIRM_CLIENT']]);
  expect(inspected).not.toHaveBeenCalled();
  expect(f.elements[0].destroy).toHaveBeenCalledTimes(1);
}));
it('classifies an unusable initial receipt without relaying its contents',async()=>page(async f=>{
  f.confirmPayment.mockResolvedValue({state:'OBSERVE',retryAllowed:true,successorAttemptId:'private canary'});
  const readFailure=Object.getOwnPropertyDescriptor(f.w,'__failedRenewalInitialConfirmFailure');
  expect(readFailure).toMatchObject({configurable:false,enumerable:false,writable:false,value:expect.any(Function)});
  await f.ready();
  expect(readFailure!.value()).toBeNull();
  const terminalSnapshots:unknown[]=[];
  f.terminal.mockImplementation(()=>{terminalSnapshots.push([f.phase(),readFailure!.value()]);});
  await f.pay();
  expect(f.terminal.mock.calls).toEqual([['STOPPED','BROWSER_CONFIRM_RECEIPT']]);
  expect(terminalSnapshots).toEqual([['STOPPED','BROWSER_CONFIRM_RECEIPT']]);
  expect(readFailure!.value()).toBe('BROWSER_CONFIRM_RECEIPT');
  expect(f.phase()).toBe('STOPPED');
}));
it('publishes pending stages immediately and stops observation before shutdown retires a pending token',async()=>page(async f=>{
  let releaseSubmit!:(value:{})=>void,releaseToken!:(value:{confirmationToken:{id:string}})=>void;
  f.submit.mockReturnValue(new Promise(resolve=>{releaseSubmit=resolve;}));
  f.token.mockReturnValue(new Promise(resolve=>{releaseToken=resolve;}));
  f.progress.mockImplementation(()=>new Promise(()=>{}));
  await f.ready();
  const paying=f.pay();
  expect(f.progress.mock.calls).toEqual([['ENTERED'],['SUBMIT']]);
  releaseSubmit({});
  await tick();
  expect(f.progress.mock.calls).toEqual([['ENTERED'],['SUBMIT'],[null],['TOKEN']]);
  f.w.__failedRenewalStop();
  releaseToken({confirmationToken:{id:'ctoken_late'}});
  await paying;
  expect(f.progress.mock.calls).toEqual([['ENTERED'],['SUBMIT'],[null],['TOKEN']]);
  expect(f.terminal.mock.calls).toEqual([['STOPPED',null]]);
  expect(f.w.__failedRenewalInitialConfirmFailure()).toBeNull();
  expect(f.confirmPayment).not.toHaveBeenCalled();
}));
it.each(['throw','reject','accessor','missing'])('an optional %s progress binding cannot change the original payment',async mode=>page(async f=>{
  const onRead=vi.fn(()=>{throw Error('private canary');});
  if(mode==='throw')f.progress.mockImplementation(onRead);
  if(mode==='reject')f.progress.mockRejectedValue(Error('private canary'));
  if(mode==='accessor')Object.defineProperty(f.w,'__failedRenewalPayProgress',{get:onRead});
  if(mode==='missing')delete f.w.__failedRenewalPayProgress;
  await f.ready();
  await f.pay();
  expect(f.phase()).toBe('OBSERVE');
  expect(f.confirmPayment).toHaveBeenCalledTimes(1);
  expect(f.terminal).not.toHaveBeenCalled();
  if(mode==='accessor')expect(onRead).not.toHaveBeenCalled();
}));
it.each(['loaderror','pagehide','ready'])('actual method mount %s uses original owner and never dispatches on readiness',async reason=>page(async f=>{
  await f.ready();await f.pay();const pending=f.method();await tick();expect(f.phase()).toBe('MOUNTING_METHOD');expect(f.elements).toHaveLength(2);expect(f.elements[1].mount).toHaveBeenCalledTimes(1);expect(f.permit).not.toHaveBeenCalled();expect(f.confirmSetup).not.toHaveBeenCalled();
  if(reason==='pagehide')f.w.dispatchEvent(new f.w.Event('pagehide'));else f.emit(1,reason);await pending;
  if(reason==='ready'){expect(f.phase()).toBe('METHOD_READY');expect(f.w.document.getElementById('confirm-method').disabled).toBe(true);expect(f.terminal).not.toHaveBeenCalled();}
  else {expect(f.phase()).toBe('STOPPED');expect(f.terminal.mock.calls).toEqual([['STOPPED','BROWSER_METHOD_MOUNT']]);expect(f.elements[1].destroy).toHaveBeenCalledTimes(1);f.emit(1,'ready');await tick();expect(f.phase()).toBe('STOPPED');}
  expect(f.permit).not.toHaveBeenCalled();expect(f.confirmSetup).not.toHaveBeenCalled();
}));
it.each(['disabled','untrusted','allowed','denied'])('method confirmation %s requires manual gating and original one-use permit',async mode=>page(async f=>{
  await f.ready();await f.pay();const mounting=f.method();await tick();f.emit(1,'ready');await mounting;
  const replaced=vi.fn(async()=>({browserConfirmAllowed:true}));f.subscriptions.confirmAction=replaced;
  if(mode!=='disabled')f.w.document.getElementById('confirm-method').disabled=false;
  await f.click('confirm-method',mode==='untrusted'?new f.w.Event('click'):{isTrusted:true});
  const dispatched=mode==='allowed';expect(f.phase()).toBe(dispatched?'METHOD_CONFIRMED':'STOPPED');expect(f.confirmSetup).toHaveBeenCalledTimes(dispatched?1:0);expect(f.permit).toHaveBeenCalledTimes(mode==='allowed'||mode==='denied'?1:0);expect(replaced).not.toHaveBeenCalled();expect(f.elements[1].destroy).toHaveBeenCalledTimes(1);
  if(mode==='allowed'||mode==='denied'){expect(f.permit.mock.calls[0][0]).toMatchObject({subscriptionId,operationId:methodId,actionToken:'bga1_'+'M'.repeat(43)});expect(f.permit.mock.calls[0][0].session).toBe(f.session);}
  await f.click('confirm-method',{isTrusted:true});expect(f.confirmSetup).toHaveBeenCalledTimes(dispatched?1:0);
},mode!=='denied'));
it('restores exact global descriptors even when the transport guard rejects the fixture',async()=>{
  const originals=new Map(globals.map(name=>[name,Object.getOwnPropertyDescriptor(globalThis,name)]));
  await expect(page(async f=>{expect(()=>f.w.fetch('inert-canary')).toThrow('offline test transport blocked');})).rejects.toThrow();
  for(const [name,descriptor] of originals)expect(Object.getOwnPropertyDescriptor(globalThis,name),name).toEqual(descriptor);
});
