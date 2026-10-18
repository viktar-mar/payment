import type {Browser,BrowserContext,BrowserType} from '@playwright/test';
import type {FormDiagnosticHandoff} from '../../../../infrastructure/stripe-form-diagnostic-runtime.mjs';
import type {FormDiagnosticSample,FormDiagnosticOutcome} from '../../../../infrastructure/stripe-form-diagnostic-schema.mjs';
import {classifyFormDiagnosticSample,validateFormDiagnosticSample} from '../../../../infrastructure/stripe-form-diagnostic-schema.mjs';

export interface FormLoadBrowserResult {
  readonly samples: readonly Readonly<FormDiagnosticSample>[];
  readonly outcome: FormDiagnosticOutcome;
  readonly browserClosed: boolean;
  readonly contextClosed: boolean;
}
export interface FormLoadLimits {readonly deadline:number; readonly signal:AbortSignal}
// Only finite local discriminators are caught. Never inspect an external error.
const FAILED=Symbol(),ABORTED=Symbol(),DEADLINE=Symbol(),UNAVAILABLE=Symbol(),FORM_LIMIT=Symbol();
function bounded<T>(promise:Promise<T>,deadline:number,signal?:AbortSignal):Promise<T> {
  return new Promise((resolve,reject)=>{
    let done=false;
    const finish=(ok:boolean,value:unknown)=>{
      if(done)return;
      if(signal?.aborted){ok=false;value=ABORTED;}
      else if(Date.now()>=deadline){ok=false;value=DEADLINE;}
      done=true;clearTimeout(timer);signal?.removeEventListener('abort',abort);ok?resolve(value as T):reject(value);
    };
    const abort=()=>finish(false,ABORTED);
    const timer=setTimeout(()=>finish(false,DEADLINE),Math.max(0,deadline-Date.now()));
    promise.then(value=>finish(true,value),()=>finish(false,FAILED));
    if(signal?.aborted)abort();
    else if(Date.now()>=deadline)finish(false,DEADLINE);
    else signal?.addEventListener('abort',abort,{once:true});
  });
}
function check(limits:FormLoadLimits):void {
  if(limits.signal.aborted)throw ABORTED;
  if(!Number.isSafeInteger(limits.deadline)||Date.now()>=limits.deadline)throw DEADLINE;
}
function handshake():boolean {
  const api=(globalThis as typeof globalThis&{__formLoadDiagnostic?:{handshake:()=>boolean}}).__formLoadDiagnostic;
  return api?.handshake()===true;
}
function readSnapshot():unknown {
  const api=(globalThis as typeof globalThis&{__formLoadDiagnostic?:{snapshot:()=>unknown}}).__formLoadDiagnostic;
  return api?.snapshot();
}

/** Exactly one owned launch, context and Resume. No payment/input/frame APIs are held here. */
export async function runFormLoadBrowser(browserType:Pick<BrowserType,'launch'>,handoff:FormDiagnosticHandoff,limits:FormLoadLimits):Promise<FormLoadBrowserResult> {
  const cleanupDeadline=limits.deadline+120000,samples:Readonly<FormDiagnosticSample>[]=[];
  let browser:Browser|undefined,context:BrowserContext|undefined,outcome:FormDiagnosticOutcome='OBSERVING',closing=false;
  let browserClosed=false,contextClosed=false,uncertain=false;
  let lastObservation:Readonly<FormDiagnosticSample>|undefined;
  let browserUncertain=false,contextUncertain=false;
  const acquisitions=new Set<Promise<unknown>>(),closures=new Set<Promise<unknown>>(),closed=new WeakSet<object>();
  function closeOwned(resource:Browser|BrowserContext,kind:'browser'|'context'):Promise<void> {
    if(closed.has(resource))return Promise.resolve();closed.add(resource);
    const task=bounded(Promise.resolve().then(()=>{
      if(Date.now()>=cleanupDeadline)throw DEADLINE;
      return resource.close();
    }),cleanupDeadline).then(()=>{
      if(kind==='browser')browserClosed=true;else contextClosed=true;
    },()=>{uncertain=true;if(kind==='browser')browserUncertain=true;else contextUncertain=true;});
    closures.add(task);task.finally(()=>closures.delete(task));return task;
  }
  function acquire<T extends Browser|BrowserContext>(promise:Promise<T>,kind:'browser'|'context'):Promise<T> {
    const task=promise.then(value=>{
      if(kind==='browser')browser=value as Browser;else context=value as BrowserContext;
      if(closing){uncertain=true;if(kind==='browser')browserUncertain=true;else contextUncertain=true;void closeOwned(value,kind);}return value;
    });
    acquisitions.add(task);task.then(()=>acquisitions.delete(task),()=>acquisitions.delete(task));
    return bounded(task,limits.deadline,limits.signal);
  }
  try {
    check(limits);
    const origin=new URL(handoff.endpoint);
    if(origin.protocol!=='http:'||origin.hostname!=='127.0.0.1'||origin.origin!==handoff.endpoint||!origin.port)throw UNAVAILABLE;
    browser=await acquire(browserType.launch({headless:true,timeout:Math.min(30000,limits.deadline-Date.now())}),'browser');check(limits);
    context=await acquire(browser.newContext({serviceWorkers:'block',acceptDownloads:false}),'context');check(limits);
    await bounded(context.addInitScript(({record,origin})=>{
      if(location.origin!==origin)return;
      // Storage capability and bootstrap identity never go through URL or DOM.
      const key=`generic-payments:v1:${encodeURIComponent(record.apiBaseUrl)}:${record.siteKey}:${record.priceKey}`;
      sessionStorage.setItem(key,JSON.stringify(record));
      Object.defineProperty(globalThis,'__formDiagnosticSession',{value:Object.freeze({apiBaseUrl:record.apiBaseUrl,siteKey:record.siteKey,priceKey:record.priceKey}),writable:false,configurable:false});
    },{record:handoff.session,origin:handoff.endpoint}),limits.deadline,limits.signal);check(limits);
    const page=await bounded(context.newPage(),limits.deadline,limits.signal);check(limits);
    await bounded(page.goto(handoff.endpoint+'/form-load-diagnostic.html',{timeout:Math.min(15000,limits.deadline-Date.now())}),limits.deadline,limits.signal);check(limits);
    if(await bounded(page.evaluate(handshake),limits.deadline,limits.signal)!==true)throw UNAVAILABLE;
    const widget=page.locator('generic-payment-widget');
    if(await bounded(widget.count(),limits.deadline,limits.signal)!==1)throw UNAVAILABLE;
    const start=Date.now(),end=Math.min(start+60000,limits.deadline);
    const checkFormWindow=()=>{check(limits);if(Date.now()>=end)throw FORM_LIMIT;};
    const observe=async<T>(read:()=>Promise<T>):Promise<T>=>{
      checkFormWindow();
      try {
        const value=await bounded(read(),end,limits.signal);
        checkFormWindow();return value;
      }catch(error){throw error===DEADLINE&&Date.now()<limits.deadline?FORM_LIMIT:error;}
    };
    await observe(()=>widget.getByRole('button',{name:'Resume existing payment',exact:true}).click({timeout:Math.min(15000,end-Date.now())}));
    let checkpoint=false;
    for(;;) {
      checkFormWindow();
      if(await observe(()=>widget.count())!==1)throw UNAVAILABLE;
      const raw=await observe(()=>page.evaluate(readSnapshot));
      let data:Record<string,unknown>={};
      try {
        if(!raw||typeof raw!=='object'||Array.isArray(raw))throw UNAVAILABLE;
        const descriptors=Object.getOwnPropertyDescriptors(raw);
        if(Reflect.ownKeys(raw).length!==Object.keys(descriptors).length)throw UNAVAILABLE;
        for(const [key,descriptor] of Object.entries(descriptors)){
          if(!('value'in descriptor))throw UNAVAILABLE;data[key]=descriptor.value;
        }
      }catch {throw UNAVAILABLE;}
      const pay=widget.locator('button[aria-label="Pay"]:enabled');
      const payVisible=await observe(()=>pay.count())===1
        &&await observe(()=>pay.isEnabled({timeout:Math.max(1,Math.min(1000,end-Date.now()))}))
        &&await observe(()=>pay.isVisible());
      checkFormWindow();
      const elapsed=Date.now()-start;
      const sample=validateFormDiagnosticSample({...data,pay_visible:payVisible,bucket:elapsed<15000?'BEFORE_15S':'AFTER_15S'});
      if(!sample)throw UNAVAILABLE;
      checkFormWindow();lastObservation=sample;
      const result=classifyFormDiagnosticSample(sample,'OBSERVING');
      const ready=result==='FORM_READY_BEFORE_15S'||result==='FORM_READY_AFTER_15S';
      const terminal=ready||['BINDING_LOST','CONFIRMATION_BLOCKED','ELEMENT_LOADERROR','OPERATION_FAILED','ABORTED','INSTRUMENTATION_UNAVAILABLE'].includes(result);
      if(terminal){samples.push(sample);break;}
      if(!checkpoint&&elapsed>=15000){samples.push(sample);checkpoint=true;}
      const next=checkpoint?end:Math.min(start+15000,end);
      await bounded(new Promise<void>(resolve=>setTimeout(resolve,Math.max(0,Math.min(100,next-Date.now())))),limits.deadline,limits.signal);
    }
  }catch(error) {
    outcome=error===ABORTED?'ABORTED':error===DEADLINE?'DEADLINE_REACHED':error===UNAVAILABLE?'INSTRUMENTATION_UNAVAILABLE':'FAILED';
    // The limit frame preserves only an earlier complete observation. Its
    // failure provenance forbids treating cached flags as a fresh pending or
    // ready diagnosis; no page read receives authority after the local window.
    if(error===FORM_LIMIT&&lastObservation)samples.push(Object.freeze({...lastObservation,bucket:'AT_LIMIT'}));
  }
  finally {
    // Freeze all observations before any teardown. Late acquisitions are owned,
    // closed when settled, and can never turn uncertain cleanup into certainty.
    closing=true;
    try {
      if(context)void closeOwned(context,'context');
      if(browser)void closeOwned(browser,'browser');
      while(acquisitions.size||closures.size)await bounded(Promise.allSettled([...acquisitions,...closures]),cleanupDeadline);
    }catch {uncertain=true;}
  }
  if(uncertain&&outcome==='OBSERVING')outcome='FAILED';
  return Object.freeze({samples:Object.freeze(samples),outcome,browserClosed:browserClosed&&!browserUncertain,contextClosed:contextClosed&&!contextUncertain});
}
