import type {Page,Request,Response} from '@playwright/test';
import type {FormResourceEvidence} from '../../../../infrastructure/stripe-form-diagnostic-environment.mjs';

/** Serialized as an init script: keep every dependency inside this function. */
export function installFormCspObserver(args:{ownedOrigin:string;activeDeadline:number}):void {
  if(globalThis.top!==(globalThis as unknown)||location.origin!==args.ownedOrigin
    ||!Number.isSafeInteger(args.activeDeadline)||Date.now()>=args.activeDeadline)return;
  if(Object.getOwnPropertyDescriptor(globalThis,'__formDiagnosticCsp'))return;
  const activeDeadline=args.activeDeadline;
  let available=true,enforced=0,reportOnly=0;
  const invalidate=()=>{available=false;enforced=0;reportOnly=0;};
  const snapshot=()=>Object.freeze({csp_observation:available?'OBSERVED':'UNAVAILABLE',
    csp_enforced_mask:enforced,csp_report_only_mask:reportOnly});
  Object.defineProperty(globalThis,'__formDiagnosticCsp',{
    value:Object.freeze({snapshot}),writable:false,configurable:false,
  });
  const receive=(event:SecurityPolicyViolationEvent)=>{
    if(!available||Date.now()>=activeDeadline)return;
    try {
      if(event.isTrusted!==true)return;
      const directive=event.effectiveDirective,disposition=event.disposition;
      if(typeof directive!=='string'||(disposition!=='enforce'&&disposition!=='report')){invalidate();return;}
      const bit=directive==='script-src'||directive==='script-src-elem'||directive==='script-src-attr'?1
        :directive==='frame-src'||directive==='child-src'?2:directive==='connect-src'?4:8;
      if(disposition==='enforce')enforced|=bit;else reportOnly|=bit;
    } catch {invalidate();}
  };
  try {document.addEventListener('securitypolicyviolation',receive,true);}catch {invalidate();}
}

/** One serialized page read; failure of either facade cannot hide the other. */
export function readDiagnosticPageEvidence(includeForm:boolean):unknown {
  function facade(name:string):object|null {
    try {
      const descriptor=Object.getOwnPropertyDescriptor(globalThis,name);
      return descriptor&&Object.hasOwn(descriptor,'value')&&descriptor.value!==null
        &&typeof descriptor.value==='object'?descriptor.value:null;
    } catch {return null;}
  }
  function invoke(api:object|null,name:string):unknown {
    try {
      if(api===null)return null;
      const descriptor=Object.getOwnPropertyDescriptor(api,name);
      return descriptor&&Object.hasOwn(descriptor,'value')&&typeof descriptor.value==='function'
        ?descriptor.value.call(api)??null:null;
    } catch {return null;}
  }
  // Validate before Playwright can read accessors or discard hidden/symbol keys.
  // Only the page snapshot shape is accepted; Node owns Pay visibility/bucket.
  function snapshot(value:unknown,domains:Record<string,readonly unknown[]>):Readonly<Record<string,unknown>>|null {
    try {
      if(value===null||typeof value!=='object')return null;
      const prototype=Object.getPrototypeOf(value);
      if(prototype!==Object.prototype&&prototype!==null)return null;
      const descriptors=Object.getOwnPropertyDescriptors(value),names=Reflect.ownKeys(descriptors),keys=Object.keys(domains);
      if(names.length!==keys.length||names.some(key=>typeof key!=='string'||!Object.hasOwn(domains,key)))return null;
      const copy:Record<string,unknown>=Object.create(null);
      for(const key of keys) {
        const descriptor=descriptors[key];
        if(!descriptor||!Object.hasOwn(descriptor,'value')||!descriptor.enumerable||!domains[key]!.includes(descriptor.value))return null;
        copy[key]=descriptor.value;
      }
      return Object.freeze(copy);
    } catch {return null;}
  }
  const sampleDomains:Record<string,readonly unknown[]>={
    version:[1],binding:['ARMED','BOUND','LOST','CLOSED'],
    catchup:['NOT_STARTED','PENDING','RESOLVED','REJECTED','ABORTED'],operation:['PENDING','COMPLETED','FAILED','ABORTED'],
    widget_state:['idle','loading','ready','checking','processing','requires_action','succeeded','failed','unknown','expired','UNAVAILABLE'],
    element_state:['UNAVAILABLE','NO_HOST','EMPTY','FRAME'],pay_state:['UNAVAILABLE','ABSENT','HIDDEN','DISABLED','ENABLED'],
  };
  for(const key of ['capability_started','capability_ready','loader_started','loader_ready','element_created',
    'mount_called','mount_returned','ready_seen','loaderror_seen','view_completed','widget_completed','confirmation_blocked'])sampleDomains[key]=[false,true];
  const sdk=facade('__formLoadDiagnostic');
  const handshake=invoke(sdk,'handshake')===true;
  const sample=includeForm?snapshot(invoke(sdk,'snapshot'),sampleDomains):null;
  const masks=[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15];
  let csp=snapshot(invoke(facade('__formDiagnosticCsp'),'snapshot'),{
    csp_observation:['OBSERVED','UNAVAILABLE'],csp_enforced_mask:masks,csp_report_only_mask:masks,
  });
  if(csp?.csp_observation==='UNAVAILABLE'&&(csp.csp_enforced_mask!==0||csp.csp_report_only_mask!==0))csp=null;
  return {handshake,sample,csp};
}

/** Own-page finite events only. No event payload survives the synchronous call. */
export function createFormResourceObserver(page:Pick<Page,'on'|'off'>,isActive:()=>boolean):{
  snapshot():Readonly<FormResourceEvidence>;stop():void;
} {
  let available=true,stopped=false,transport=0,http4xx=0,http5xx=0;
  let attemptedRequest=false,attemptedResponse=false;
  const invalidate=()=>{available=false;transport=0;http4xx=0;http5xx=0;};
  function active():boolean {
    if(stopped||!available)return false;
    try {return isActive()===true;}catch {invalidate();return false;}
  }
  function bit(type:string):number {
    return type==='script'?1:type==='document'?2:type==='fetch'||type==='xhr'?4:8;
  }
  function requestfailed(request:Request):void {
    if(!active())return;
    try {
      const type=request.resourceType();
      if(typeof type!=='string'){invalidate();return;}
      transport|=bit(type);
    }catch {invalidate();}
  }
  function response(response:Response):void {
    if(!active())return;
    try {
      const status=response.status();
      if(!Number.isInteger(status)||status<100||status>599){invalidate();return;}
      const type=response.request().resourceType();
      if(typeof type!=='string'){invalidate();return;}
      if(status>=400&&status<=499)http4xx|=bit(type);
      else if(status>=500)http5xx|=bit(type);
    }catch {invalidate();}
  }
  function stop():void {
    if(stopped)return;
    stopped=true;
    // on() may register before throwing. Removal failure never reopens the gate.
    if(attemptedRequest){try {page.off('requestfailed',requestfailed);}catch {}}
    if(attemptedResponse){try {page.off('response',response);}catch {}}
  }
  try {
    attemptedRequest=true;page.on('requestfailed',requestfailed);
    attemptedResponse=true;page.on('response',response);
  }catch {invalidate();stop();}
  return {snapshot:()=>Object.freeze({resource_observation:available?'OBSERVED':'UNAVAILABLE',
    resource_transport_failure_mask:transport,resource_http_4xx_mask:http4xx,resource_http_5xx_mask:http5xx}),stop};
}
