import type {CDPSession,Frame,Page,Request} from '@playwright/test';
// @ts-ignore Native Node executes the same iframe guard source as Playwright.
import {guardIframeTargets,type GuardSession} from './stripe-billing-chromium-iframe.ts';

const refuse=():never=>{throw Error('billing_BROWSER');};
// Keep response interception aligned with every original HTTP destination the guarded
// checkout may forward. The separate pinned presentation fetch already uses redirect:error.
const stripeOrigins=['https://js.stripe.com','https://api.stripe.com','https://hooks.stripe.com','https://r.stripe.com','https://merchant-ui-api.stripe.com'];
type Origins={backend:string;demo:string};
type Header={name:string;value:string};
type Preflight={origin:string;method:string;names?:string};
type Entry={url:string;method:string;frameId?:string;allowed:boolean;preflight?:Preflight;initiator?:{id:string;entry?:Entry}};
type RedirectGuardFailure='BROWSER_GUARD_REQUEST_REDIRECT'|'BROWSER_GUARD_REQUEST_PAIR'|'BROWSER_GUARD_REQUEST_CAPACITY'
 |'BROWSER_GUARD_RESPONSE_PAIR'|'BROWSER_GUARD_RESPONSE_REDIRECT'|'BROWSER_GUARD_RESPONSE_STATUS'|'BROWSER_GUARD_RESPONSE_NETWORK'
 |'BROWSER_GUARD_RESPONSE_PREFLIGHT'|'BROWSER_GUARD_RESPONSE_UNCLAIMED'|'BROWSER_GUARD_RESPONSE_RESUME'|'BROWSER_GUARD_IFRAME_SETUP';
function stripeScript(url:URL){
 return url.protocol==='https:' && !url.port && !url.username && !url.password &&
  (url.hostname==='js.stripe.com'||url.hostname.endsWith('.js.stripe.com'));
}
function probe(request:{url:string;method:string;headers?:Record<string,string>;postData?:string;hasPostData?:boolean;postDataEntries?:unknown[]},
    initiator:string|undefined,origins:Origins):Preflight|undefined{
 if(request.method!=='OPTIONS'||initiator!=='preflight'||request.hasPostData||request.postData!==undefined||request.postDataEntries?.length)return;
 const headers=new Map(Object.entries(request.headers??{}).map(([k,v])=>[k.toLowerCase(),v]));
 if(['authorization','cookie','proxy-authorization','stripe-account','stripe-context'].some(name=>headers.has(name)))return;
 const origin=headers.get('origin'),method=headers.get('access-control-request-method');
 if(!origin || !URL.canParse(origin) || !method || !['GET','POST'].includes(method))return;
 const from=new URL(origin),to=new URL(request.url);
 if(from.origin!==origin || from.username || from.password || to.username || to.password)return;
 if(to.origin===origins.backend){if(origin!==origins.demo)return;}
 else if(to.origin===origins.demo || !(stripeScript(from) || origin===origins.demo && stripeScript(to) && method==='GET'))return;
 const names=headers.get('access-control-request-headers');
 if(names!==undefined && (names.length>1024||!/^[A-Za-z0-9-]+(?:,\s*[A-Za-z0-9-]+)*$/.test(names)))return;
 return {origin,method,names};
}
function syntheticProbe(value:Preflight|undefined,status:number|undefined,headers:Header[]|undefined){
 if(!value || status!==204 || !headers)return false;
 const expected=new Map([['access-control-allow-origin',value.origin],['access-control-allow-methods',value.method],
  ['access-control-allow-credentials','true']]);
 if(value.names!==undefined)expected.set('access-control-allow-headers',value.names);
 return headers.length===expected.size && headers.every(h=>expected.get(h.name.toLowerCase())===h.value && expected.delete(h.name.toLowerCase()));
}
type Guard={session:GuardSession;requests:Map<string,Entry>};
type Target=Guard&{root:Frame;current:()=>boolean;id?:string};
type Tree={frame:{id:string;name?:string;url:string;urlFragment?:string};childFrames?:Tree[]};
function frameId(tree:Tree,root:Frame,frame:Frame,uncommittedOwner?:string):string {
 const chain:Frame[]=[];
 for(let current:Frame|null=frame;current!==root;current=current.parentFrame()){
  if(!current||current.isDetached())return refuse();chain.unshift(current);
 }
 function matches(node:Tree,f:Frame){
  const document=node.frame.url+(node.frame.urlFragment??'');
  const url=f.url(),name=f.name();
  if(f===frame && uncommittedOwner!==undefined){
   return url==='' && name==='' && document==='' && node.frame.name===uncommittedOwner;
  }
  return document===(url||'about:blank') && (node.frame.name??'')===name;
 }
 if(root.isDetached()||!matches(tree,root))return refuse();
 for(const child of chain){
  const selected=tree.childFrames?.filter(node=>matches(node,child));
  if(selected?.length!==1)return refuse();tree=selected[0]!;
 }
 return tree.frame.id;
}

function bindPreflightInitiator(preflight:Entry,entry:Entry):boolean{
 if(!preflight.initiator || !preflight.preflight || !entry.frameId || entry.allowed ||
    entry.url!==preflight.url || entry.method!==preflight.preflight.method)return false;
 preflight.frameId=entry.frameId;
 preflight.initiator.entry=entry;
 return true;
}

/** Playwright routes only the first URL in a redirect chain. Arm a response-stage guard
 * before each SDK request exists and require its passive network event on the exact frame. */
export async function guardChallengeRedirects(page:Page,onFailure:(reason:RedirectGuardFailure)=>void,origins:Origins){
 for(const fresh of [origins.backend,origins.demo]){
  const url=new URL(fresh);
  if(url.origin!==fresh || !/^http:\/\/127\.0\.0\.1:[1-9]\d{3,4}$/.test(fresh))refuse();
 }
 if(origins.backend===origins.demo)refuse();
 const exactOrigins=[...stripeOrigins,origins.backend,origins.demo];
 const patterns=exactOrigins.map(origin=>origin+'/*').concat('https://*.js.stripe.com/*');
 function tracked(raw:string){
  const url=new URL(raw);return exactOrigins.includes(url.origin)||stripeScript(url);
 }
 async function arm(session:GuardSession):Promise<Guard>{
  const requests=new Map<string,Entry>();
  session.on('Network.requestWillBeSent',event=>{
   if(!tracked(event.request.url))return;
   if(event.redirectResponse){onFailure('BROWSER_GUARD_REQUEST_REDIRECT');return;}
   if(requests.has(event.requestId)){onFailure('BROWSER_GUARD_REQUEST_PAIR');return;}
   if(requests.size>=24){onFailure('BROWSER_GUARD_REQUEST_CAPACITY');return;}
   const preflight=probe(event.request,event.initiator?.type,origins);
   const entry:Entry={frameId:event.frameId,url:event.request.url,method:event.request.method,allowed:false,preflight};
   if(!entry.frameId){
    // Chrome may report a frameless preflight before the linked initiating request.
    // Record the exact identifier, but require its frame and the synthetic 204 at the pause.
    const id=event.initiator?.requestId;
    if(!preflight || !id || id===event.requestId){onFailure('BROWSER_GUARD_REQUEST_PAIR');return;}
    entry.initiator={id};
    const existing=requests.get(id);
    if(existing && !bindPreflightInitiator(entry,existing)){onFailure('BROWSER_GUARD_REQUEST_PAIR');return;}
   }
   for(const request of requests.values()){
    if(request.initiator?.id===event.requestId && !request.initiator.entry &&
       !bindPreflightInitiator(request,entry)){onFailure('BROWSER_GUARD_REQUEST_PAIR');return;}
   }
   requests.set(event.requestId,entry);
  });
  // Playwright 1.63 fulfills genuine browser preflights before exposing a user route.
  // A locally handled probe may finish without a response pause on this CDP target.
  session.on('Network.loadingFinished',event=>requests.delete(event.requestId));
  session.on('Network.loadingFailed',event=>requests.delete(event.requestId));
  session.on('Fetch.requestPaused',async event=>{
   let reason:RedirectGuardFailure='BROWSER_GUARD_RESPONSE_PAIR';
   try{
    const original=event.networkId && requests.get(event.networkId);
    if(!original || !original.frameId || original.frameId!==event.frameId || original.url!==event.request.url ||
       original.method!==event.request.method)return refuse();
    if(event.responseErrorReason){reason='BROWSER_GUARD_RESPONSE_NETWORK';refuse();}
    const status=event.responseStatusCode;
    if(!status || status<200 || status>=300){
     reason=status && [301,302,303,307,308].includes(status)?'BROWSER_GUARD_RESPONSE_REDIRECT':'BROWSER_GUARD_RESPONSE_STATUS';
     refuse();
    }
    if(original.method==='OPTIONS'){
     reason='BROWSER_GUARD_RESPONSE_PREFLIGHT';
     const paused=probe(event.request,'preflight',origins),sent=original.preflight;
     if(!sent || !paused || paused.origin!==sent.origin || paused.method!==sent.method || paused.names!==sent.names ||
        !syntheticProbe(sent,event.responseStatusCode,event.responseHeaders))refuse();
     if(original.initiator){
      const {id,entry}=original.initiator;
      if(!entry || requests.get(id)!==entry || entry.allowed)refuse();
     }
    }else if(!original.allowed){reason='BROWSER_GUARD_RESPONSE_UNCLAIMED';refuse();}
    requests.delete(event.networkId!);
    reason='BROWSER_GUARD_RESPONSE_RESUME';
    await session.send('Fetch.continueResponse',{requestId:event.requestId});
   }catch{
    try{await session.send('Fetch.failRequest',{requestId:event.requestId,errorReason:'BlockedByClient'});}
    catch{}finally{onFailure(reason);}
   }
  });
  await session.send('Network.enable');
  await session.send('Fetch.enable',{patterns:patterns.map(urlPattern=>({urlPattern,requestStage:'Response' as const}))});
  return {session,requests};
 }
 const main=page.mainFrame(),mainGuard=await arm(await page.context().newCDPSession(page));
 const iframeGuard=await guardIframeTargets(mainGuard.session,arm,()=>onFailure('BROWSER_GUARD_IFRAME_SETUP'));
 const mainTarget={...mainGuard,root:main,current:()=>iframeGuard.active()&&!main.isDetached()};
 async function target(frame:Frame,generation:number):Promise<Target>{
  if(frame===main)return mainTarget;
  let probe:CDPSession;
  try{probe=await page.context().newCDPSession(frame);}
  catch(error){
   const parent=frame.parentFrame();
   // In-process frames are covered by their nearest target and its exact frame tree.
   if(parent && error instanceof Error && error.message.endsWith("This frame does not have a separate CDP session, it is a part of the parent frame's session"))return target(parent,generation);
   return refuse();
  }
  try{
   // The late probe identifies the current target. It never owns request evidence.
   const {targetInfo}=await probe.send('Target.getTargetInfo');
   if(targetInfo.type!=='iframe')return refuse();
   const {guard,current}=await iframeGuard.get(targetInfo.targetId,generation);
   return {...guard,root:frame,current,id:targetInfo.targetId};
  }finally{await probe.detach();}
 }
 // The renderer cannot answer Page.getFrameTree while its own navigation is paused.
 // Bind the main CDP frame before navigation and keep requiring the matching network event.
 const {frameTree}=await mainTarget.session.send('Page.getFrameTree');
 const mainId=frameId(frameTree,main,main);
 if(!mainId)refuse();
 const routes=new WeakMap<Request,number>();
 return {
 registerRoute(request:Request){
  if(!routes.has(request))routes.set(request,iframeGuard.generation());
 },
 async authorize(request:Request){
  // A replacement target cannot inherit a route that predates its attachment.
  const generation=routes.get(request)??iframeGuard.generation();
  const frame=request.frame(),origin=await target(frame,generation);
  const tree=async()=>{
   const {frameTree}=await origin.session.send('Page.getFrameTree');
   if(origin.id!==undefined && frameTree.frame.id!==origin.id)return refuse();
   return frameTree;
  };
  let id:string;
  if(frame===main){
   if(page.mainFrame()!==main||main.isDetached()||origin!==mainTarget)refuse();
   id=mainId;
  }else if(frame.url()==='' && frame.name()==='' && request.isNavigationRequest() &&
     request.method()==='GET' && stripeScript(new URL(request.url())) && !request.redirectedFrom()){
    // Before the first commit Playwright has no document name. Chromium already
    // exposes the iframe element's name, or its ID when the name is empty.
    const parent=frame.parentFrame(),owner=await frame.frameElement();
    try{
     const readOwnerName=()=>owner.evaluate(node=>{
      const element=node as HTMLIFrameElement;
      return element.localName==='iframe' && element.isConnected ? element.getAttribute('name') || element.id : null;
     });
     const name=await readOwnerName();
     if(!name || !parent)return refuse();
     const frameTree=await tree();
     if(await owner.contentFrame()!==frame || await readOwnerName()!==name ||
        frame.parentFrame()!==parent || frame.isDetached())return refuse();
     id=frameId(frameTree,origin.root,frame,name);
    }finally{void owner.dispose().catch(()=>{});}
  }else{
   id=frameId(await tree(),origin.root,frame);
  }
  if(!origin.current()||frame.isDetached())refuse();
  const matches=[...origin.requests.values()].filter(entry=>entry.frameId===id && entry.url===request.url() && entry.method===request.method());
  if(matches.length!==1 || matches[0]!.allowed || request.method()==='OPTIONS' && !matches[0]!.preflight)refuse();
  matches[0]!.allowed=true;
 }};
}
