import type {Browser,BrowserContext,BrowserType} from '@playwright/test';
import type {FormDiagnosticHandoff} from '../../../../infrastructure/stripe-form-diagnostic-runtime.mjs';
import type {FormDiagnosticObservation,FormDiagnosticOutcome} from '../../../../infrastructure/stripe-form-diagnostic-schema.mjs';
import {classifyFormDiagnosticSample,validateFormDiagnosticSample} from '../../../../infrastructure/stripe-form-diagnostic-schema.mjs';
import {validateFormEnvironmentEvidence,unavailableFormEnvironmentEvidence} from '../../../../infrastructure/stripe-form-diagnostic-environment.mjs';
// @ts-expect-error Native Node source type erasure.
import {installFormCspObserver,readDiagnosticPageEvidence,createFormResourceObserver} from './form-load-diagnostic-environment.ts';

export interface FormLoadBrowserResult {
  readonly observations: readonly Readonly<FormDiagnosticObservation>[];
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
function dataRecord(value:unknown,keys?:readonly string[]):Record<string,unknown>|null {
  try {
    if(value===null||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;
    const descriptors=Object.getOwnPropertyDescriptors(value),names=Reflect.ownKeys(descriptors);
    if(names.some(key=>typeof key!=='string')||(keys&&(names.length!==keys.length||names.some(key=>!keys.includes(key as string)))))return null;
    const data:Record<string,unknown>=Object.create(null);
    for(const key of names as string[]){const descriptor=descriptors[key]!;if(!Object.hasOwn(descriptor,'value')||!descriptor.enumerable)return null;data[key]=descriptor.value;}
    return data;
  }catch {return null;}
}

/** Exactly one owned launch, context and Resume. No payment/input/frame APIs are held here. */
export async function runFormLoadBrowser(browserType:Pick<BrowserType,'launch'>,handoff:FormDiagnosticHandoff,limits:FormLoadLimits):Promise<FormLoadBrowserResult> {
  const cleanupDeadline=limits.deadline+120000,observations:Readonly<FormDiagnosticObservation>[]=[];
  let browser:Browser|undefined,context:BrowserContext|undefined,outcome:FormDiagnosticOutcome='OBSERVING',closing=false;
  let browserClosed=false,contextClosed=false,uncertain=false;
  let lastObservation:Readonly<FormDiagnosticObservation>|undefined;
  let localEnd:number|undefined,collector:ReturnType<typeof createFormResourceObserver>|undefined;
  const active=()=>!closing&&!limits.signal.aborted&&Date.now()<limits.deadline&&(localEnd===undefined||Date.now()<localEnd);
  function environment(csp:unknown) {
    const source=dataRecord(csp,['csp_observation','csp_enforced_mask','csp_report_only_mask']);
    const unavailable=unavailableFormEnvironmentEvidence();
    const resource=collector?.snapshot()??{resource_observation:'UNAVAILABLE',resource_transport_failure_mask:0,resource_http_4xx_mask:0,resource_http_5xx_mask:0};
    const missingCsp={csp_observation:unavailable.csp_observation,csp_enforced_mask:0,csp_report_only_mask:0};
    const combine=(cspData:Record<string,unknown>)=>validateFormEnvironmentEvidence({...cspData,...resource,
      environment_freshness:cspData.csp_observation==='UNAVAILABLE'&&resource.resource_observation==='UNAVAILABLE'?'UNAVAILABLE':'SAMPLED'});
    return (source&&combine(source))||combine(missingCsp)||unavailable;
  }
  function pageEvidence(raw:unknown) {
    const data=dataRecord(raw,['handshake','sample','csp']);
    if(!data||typeof data.handshake!=='boolean')throw UNAVAILABLE;
    return data;
  }
  let browserUncertain=false,contextUncertain=false;
  const acquisitions=new Set<Promise<unknown>>(),closures=new Set<Promise<unknown>>(),closed=new WeakSet<object>();
  function closeOwned(resource:Browser|BrowserContext,kind:'browser'|'context',proofDeadline=cleanupDeadline):Promise<void> {
    if(closed.has(resource))return Promise.resolve();closed.add(resource);
    const task=bounded(Promise.resolve().then(()=>{
      if(Date.now()>=cleanupDeadline)throw DEADLINE;
      // Playwright close() fulfills without work for closing/closed contexts.
      // Check at dispatch so an external close cannot manufacture owned proof.
      if(kind==='context'&&(resource as BrowserContext).isClosed())throw FAILED;
      return resource.close();
    }),cleanupDeadline).then(()=>{
      if(kind==='browser')browserClosed=true;
      else if(Date.now()>=proofDeadline){uncertain=true;contextUncertain=true;}
      else if(!contextUncertain)contextClosed=true;
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
    await bounded(context.addInitScript(installFormCspObserver,{ownedOrigin:handoff.endpoint,activeDeadline:limits.deadline}),limits.deadline,limits.signal);check(limits);
    const page=await bounded(context.newPage(),limits.deadline,limits.signal);check(limits);
    collector=createFormResourceObserver(page,active);check(limits);
    await bounded(page.goto(handoff.endpoint+'/form-load-diagnostic.html',{timeout:Math.min(15000,limits.deadline-Date.now())}),limits.deadline,limits.signal);check(limits);
    const baseline=pageEvidence(await bounded(page.evaluate(readDiagnosticPageEvidence,false),limits.deadline,limits.signal));check(limits);
    lastObservation=Object.freeze({sample:null,environment:environment(baseline.csp)});
    if(baseline.handshake!==true||baseline.sample!==null||lastObservation.environment.environment_freshness!=='SAMPLED'
      ||lastObservation.environment.csp_observation!=='OBSERVED'||lastObservation.environment.resource_observation!=='OBSERVED')throw UNAVAILABLE;
    const widget=page.locator('generic-payment-widget');
    if(await bounded(widget.count(),limits.deadline,limits.signal)!==1)throw UNAVAILABLE;
    const start=Date.now(),end=Math.min(start+60000,limits.deadline);
    localEnd=end;
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
      const raw=pageEvidence(await observe(()=>page.evaluate(readDiagnosticPageEvidence,true)));
      const data=dataRecord(raw.sample);
      // Validate SDK scalar data before any subsequent capability read. The
      // internal SDK sample stays version1; Node alone supplies Pay and bucket.
      if(raw.handshake!==true||!data||Object.hasOwn(data,'pay_visible')||Object.hasOwn(data,'bucket')
        ||!validateFormDiagnosticSample({...data,pay_visible:false,bucket:'BEFORE_15S'}))throw UNAVAILABLE;
      const csp=dataRecord(raw.csp,['csp_observation','csp_enforced_mask','csp_report_only_mask']);
      if(!csp)throw UNAVAILABLE;
      const pay=widget.locator('button[aria-label="Pay"]:enabled');
      const payVisible=await observe(()=>pay.count())===1
        &&await observe(()=>pay.isEnabled({timeout:Math.max(1,Math.min(1000,end-Date.now()))}))
        &&await observe(()=>pay.isVisible());
      checkFormWindow();
      const elapsed=Date.now()-start;
      const sample=validateFormDiagnosticSample({...data,pay_visible:payVisible,bucket:elapsed<15000?'BEFORE_15S':'AFTER_15S'});
      if(!sample)throw UNAVAILABLE;
      checkFormWindow();
      const evidence=environment(csp);checkFormWindow();
      lastObservation=Object.freeze({sample,environment:evidence});
      const result=classifyFormDiagnosticSample(sample,'OBSERVING');
      const primary=['BINDING_LOST','CONFIRMATION_BLOCKED','OPERATION_FAILED','ABORTED'].includes(result);
      if(!primary&&(evidence.environment_freshness!=='SAMPLED'||evidence.csp_observation!=='OBSERVED'||evidence.resource_observation!=='OBSERVED'))throw UNAVAILABLE;
      const ready=result==='FORM_READY_BEFORE_15S'||result==='FORM_READY_AFTER_15S';
      const terminal=ready||['BINDING_LOST','CONFIRMATION_BLOCKED','ELEMENT_LOADERROR','OPERATION_FAILED','ABORTED','INSTRUMENTATION_UNAVAILABLE'].includes(result);
      if(terminal){observations.push(lastObservation);break;}
      if(!checkpoint&&elapsed>=15000){observations.push(lastObservation);checkpoint=true;}
      const next=checkpoint?end:Math.min(start+15000,end);
      await bounded(new Promise<void>(resolve=>setTimeout(resolve,Math.max(0,Math.min(100,next-Date.now())))),limits.deadline,limits.signal);
    }
  }catch(error) {
    outcome=error===ABORTED?'ABORTED':error===DEADLINE?'DEADLINE_REACHED':error===UNAVAILABLE?'INSTRUMENTATION_UNAVAILABLE':'FAILED';
    // The limit frame preserves only an earlier complete observation. Its
    // failure provenance forbids treating cached flags as a fresh pending or
    // ready diagnosis; no page read receives authority after the local window.
    if(lastObservation){
      const sample=error===FORM_LIMIT&&lastObservation.sample?Object.freeze({...lastObservation.sample,bucket:'AT_LIMIT'}):lastObservation.sample;
      const evidence=lastObservation.environment;
      observations.push(Object.freeze({sample,environment:Object.freeze({...evidence,
        environment_freshness:evidence.environment_freshness==='UNAVAILABLE'?'UNAVAILABLE':'CACHED'})}));
    }
  }
  finally {
    collector?.stop();Object.freeze(observations);
    // Freeze all observations before any teardown. Late acquisitions are owned,
    // closed when settled, and can never turn uncertain cleanup into certainty.
    closing=true;
    try {
      if(context){
        // Grant one independent context attempt up to five seconds, reserving
        // ten seconds for browser fallback inside the original cleanup ceiling.
        const graceDeadline=Math.min(Date.now()+5000,cleanupDeadline-10000);
        const attempt=closeOwned(context,'context',graceDeadline);
        if(Date.now()<graceDeadline){try {await bounded(attempt,graceDeadline);}catch {}}
        // closeOwned drains failures into fulfillment. Only its independent
        // proof counts; fallback uncertainty survives all later settlement.
        if(!contextClosed||contextUncertain){uncertain=true;contextUncertain=true;}
      }
      if(browser)void closeOwned(browser,'browser');
      while(acquisitions.size||closures.size)await bounded(Promise.allSettled([...acquisitions,...closures]),cleanupDeadline);
    }catch {uncertain=true;}
  }
  if(uncertain&&outcome==='OBSERVING')outcome='FAILED';
  return Object.freeze({observations,outcome,browserClosed:browserClosed&&!browserUncertain,contextClosed:contextClosed&&!contextUncertain});
}
