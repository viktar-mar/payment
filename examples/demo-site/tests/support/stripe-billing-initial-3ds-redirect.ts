import type {CDPSession,Frame,Page,Request} from '@playwright/test';
// @ts-ignore Native Node executes the same iframe guard source as Playwright.
import {guardIframeTargets,type GuardSession} from './stripe-billing-chromium-iframe.ts';
// @ts-ignore Native Node executes the pinned Chromium identity reader.
import {chromiumRouteReader,type ChromiumRouteEvidence} from './stripe-billing-chromium-route.ts';

const refuse=():never=>{throw Error('billing_BROWSER');};
// Keep response interception aligned with every original HTTP destination the guarded
// checkout may forward. The separate pinned presentation fetch already uses redirect:error.
const stripeOrigins=['https://js.stripe.com','https://api.stripe.com','https://hooks.stripe.com','https://r.stripe.com','https://merchant-ui-api.stripe.com'];
type Origins={backend:string;demo:string};
type Header={name:string;value:string};
type Preflight={origin:string;method:string;names?:string};
type FrameWait={settle:(matched:boolean)=>void;invalidated:boolean};
type Entry={url:string;method:string;frameId?:string;allowed:boolean;preflight?:Preflight;
 initiator?:{id:string;entry?:Entry};frameWait?:FrameWait};
type RedirectGuardFailure='BROWSER_GUARD_REQUEST_REDIRECT'|'BROWSER_GUARD_REQUEST_PAIR'|'BROWSER_GUARD_REQUEST_CAPACITY'
 |'BROWSER_GUARD_RESPONSE_PAIR_NETWORK_ID'|'BROWSER_GUARD_RESPONSE_PAIR_REQUEST_MISSING'|'BROWSER_GUARD_RESPONSE_PAIR_DUPLICATE'
 |'BROWSER_GUARD_RESPONSE_PAIR_NETWORK_FRAME_MISSING'|'BROWSER_GUARD_RESPONSE_PAIR_FETCH_FRAME_MISSING'
 |'BROWSER_GUARD_RESPONSE_PAIR_FRAME'|'BROWSER_GUARD_RESPONSE_PAIR_URL'|'BROWSER_GUARD_RESPONSE_PAIR_METHOD'
 |'BROWSER_GUARD_RESPONSE_REDIRECT'|'BROWSER_GUARD_RESPONSE_STATUS'|'BROWSER_GUARD_RESPONSE_NETWORK'
 |'BROWSER_GUARD_RESPONSE_PREFLIGHT'|'BROWSER_GUARD_RESPONSE_UNCLAIMED'|'BROWSER_GUARD_RESPONSE_RESUME'|'BROWSER_GUARD_IFRAME_SETUP'
 |'BROWSER_GUARD_ROUTE_IDENTITY'|'BROWSER_GUARD_ROUTE_TARGET'
 |'BROWSER_GUARD_ROUTE_EVENT_MISSING'|'BROWSER_GUARD_ROUTE_EVENT_RETIRED'
 |'BROWSER_GUARD_ROUTE_EVENT_FRAME'|'BROWSER_GUARD_ROUTE_EVENT_URL'|'BROWSER_GUARD_ROUTE_EVENT_METHOD'
 |'BROWSER_GUARD_ROUTE_EVENT_CLAIMED'|'BROWSER_GUARD_ROUTE_EVENT_PREFLIGHT';
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
type ObservedEvent=Entry|'RETIRED'|undefined;
type Guard={session:GuardSession;requests:Map<string,Entry>;waitForEvent:(id:string)=>Promise<ObservedEvent>};
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
 preflight.frameWait?.settle(true);
 return true;
}

/** Playwright routes only the first URL in a redirect chain. Arm a response-stage guard
 * before each SDK request exists and require its passive network event on the exact frame. */
export async function guardChallengeRedirects(page:Page,onFailure:(reason:RedirectGuardFailure)=>void,origins:Origins,signal?:AbortSignal){
 if(signal?.aborted)return refuse();
 const routeReader=chromiumRouteReader(page);
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
  const waiters=new Map<string,(event:ObservedEvent)=>void>();
  const retired=new Set<string>();
  const pendingPauses=new Map<string,FrameWait>();
  let closed=false;
  function waitForEvent(id:string):Promise<ObservedEvent>{
   if(closed||waiters.has(id)||waiters.size>=24)return Promise.resolve(undefined);
   return new Promise(resolve=>{
    const timer=setTimeout(()=>waiters.get(id)?.(undefined),1000);
    waiters.set(id,event=>{clearTimeout(timer);waiters.delete(id);resolve(event);});
   });
  }
  session.on('Network.requestWillBeSent',event=>{
   if(closed||!tracked(event.request.url))return;
   if(event.redirectResponse){retire({requestId:event.requestId});onFailure('BROWSER_GUARD_REQUEST_REDIRECT');return;}
   if(requests.has(event.requestId)||retired.has(event.requestId)){
    retire({requestId:event.requestId});onFailure('BROWSER_GUARD_REQUEST_PAIR');return;
   }
   if(requests.size>=24){onFailure('BROWSER_GUARD_REQUEST_CAPACITY');return;}
   const preflight=probe(event.request,event.initiator?.type,origins);
   const entry:Entry={frameId:event.frameId,url:event.request.url,method:event.request.method,allowed:false,preflight};
   if(!entry.frameId){
    // Chrome may report a frameless preflight before the linked initiating request.
    // Record the exact identifier, but require its frame and the synthetic 204 at the pause.
    const id=event.initiator?.requestId;
    if(!preflight || !id || id===event.requestId || retired.has(id)){onFailure('BROWSER_GUARD_REQUEST_PAIR');return;}
    entry.initiator={id};
    const existing=requests.get(id);
    if(existing && !bindPreflightInitiator(entry,existing)){onFailure('BROWSER_GUARD_REQUEST_PAIR');return;}
   }
   for(const request of requests.values()){
    if(request.initiator?.id===event.requestId && !request.initiator.entry && !bindPreflightInitiator(request,entry)){
     retire({requestId:event.requestId});
     onFailure('BROWSER_GUARD_REQUEST_PAIR');return;
    }
   }
   requests.set(event.requestId,entry);
   waiters.get(event.requestId)?.(entry);
  });
  function invalidateFrameWait(wait:FrameWait|undefined){
   if(!wait)return;
   wait.invalidated=true;
   wait.settle(false);
  }
  // Playwright 1.63 fulfills genuine browser preflights before exposing a user route.
  // A locally handled probe may finish without a response pause on this CDP target.
  function retire({requestId}: {requestId:string}){
   if(closed)return;
   // Preserve retirement even when it is observed before the preflight or initiator.
   if(!retired.has(requestId)&&retired.size>=4096){closeGuard();onFailure('BROWSER_GUARD_REQUEST_CAPACITY');return;}
   retired.add(requestId);
   invalidateFrameWait(requests.get(requestId)?.frameWait);
   requests.delete(requestId);
   waiters.get(requestId)?.('RETIRED');
   for(const request of requests.values())if(request.initiator?.id===requestId)invalidateFrameWait(request.frameWait);
  }
  session.on('Network.loadingFinished',retire);
  session.on('Network.loadingFailed',retire);
  function closeGuard(){
   if(closed)return;
   closed=true;
   signal?.removeEventListener('abort',closeGuard);
   for(const wait of pendingPauses.values())invalidateFrameWait(wait);
   pendingPauses.clear();retired.clear();
   requests.clear();
   for(const settle of waiters.values())settle('RETIRED');
  }
  session.on('close',closeGuard);
  signal?.addEventListener('abort',closeGuard,{once:true});
  if(signal?.aborted){closeGuard();return refuse();}
  session.on('Fetch.requestPaused',async event=>{
   let reason:RedirectGuardFailure='BROWSER_GUARD_RESPONSE_PAIR_NETWORK_ID';
   let waitingClaim:FrameWait|undefined;
   try{
    const networkId=event.networkId;
    const original=networkId?requests.get(networkId):undefined;
    const repeated=pendingPauses.get(event.requestId);
    if(repeated || original?.frameWait){
     reason='BROWSER_GUARD_RESPONSE_PAIR_DUPLICATE';
     invalidateFrameWait(repeated);
     invalidateFrameWait(original?.frameWait);
     return refuse();
    }
    if(!networkId)return refuse();
    reason='BROWSER_GUARD_RESPONSE_PAIR_REQUEST_MISSING';
    if(!original)return refuse();
    reason='BROWSER_GUARD_RESPONSE_PAIR_FETCH_FRAME_MISSING';
    if(!event.frameId)return refuse();
    reason='BROWSER_GUARD_RESPONSE_PAIR_FRAME';
    if(original.frameId && original.frameId!==event.frameId)return refuse();
    reason='BROWSER_GUARD_RESPONSE_PAIR_URL';
    if(original.url!==event.request.url)return refuse();
    reason='BROWSER_GUARD_RESPONSE_PAIR_METHOD';
    if(original.method!==event.request.method)return refuse();
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
      const initiator=original.initiator;
      if(!original.frameId){
       // Only the exact linked Network event on this session can supply the frame.
       // Keep the validated synthetic response paused while waiting for that event.
       reason='BROWSER_GUARD_RESPONSE_PAIR_NETWORK_FRAME_MISSING';
       if(retired.has(initiator.id))return refuse();
       const waiting=Promise.withResolvers<boolean>();
       const claim={settle:waiting.resolve,invalidated:false};
       original.frameWait=claim;
       waitingClaim=claim;
       pendingPauses.set(event.requestId,claim);
       const timer=setTimeout(()=>invalidateFrameWait(claim),1000);
       try{
        const matched=await waiting.promise;
        if(!matched || claim.invalidated || closed || requests.get(networkId)!==original)return refuse();
       }finally{clearTimeout(timer);}
      }
      reason='BROWSER_GUARD_RESPONSE_PREFLIGHT';
      const entry=initiator.entry;
      if(retired.has(initiator.id) || !entry || requests.get(initiator.id)!==entry || entry.allowed)refuse();
     }
    }else if(!original.allowed){reason='BROWSER_GUARD_RESPONSE_UNCLAIMED';refuse();}
    reason='BROWSER_GUARD_RESPONSE_PAIR_NETWORK_FRAME_MISSING';
    if(!original.frameId)return refuse();
    reason='BROWSER_GUARD_RESPONSE_PAIR_FRAME';
    if(original.frameId!==event.frameId)return refuse();
    requests.delete(networkId);
    reason='BROWSER_GUARD_RESPONSE_RESUME';
    await session.send('Fetch.continueResponse',{requestId:event.requestId});
   }catch{
    try{await session.send('Fetch.failRequest',{requestId:event.requestId,errorReason:'BlockedByClient'});}
    catch{}finally{onFailure(reason);}
   }finally{if(waitingClaim)pendingPauses.delete(event.requestId);}
  });
  await session.send('Network.enable');
  await session.send('Fetch.enable',{patterns:patterns.map(urlPattern=>({urlPattern,requestStage:'Response' as const}))});
  return {session,requests,waitForEvent};
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
 if(!mainId||routeReader&&routeReader.mainId!==mainId)refuse();
 const routes=new WeakMap<Request,{generation:number;evidence?:ChromiumRouteEvidence|null}>();
 return {
 registerRoute(request:Request){
  if(!routes.has(request))routes.set(request,{generation:iframeGuard.generation(),evidence:routeReader?.capture(request)});
 },
 async authorize(request:Request){
  // A replacement target cannot inherit a route that predates its attachment.
  const registered=routes.get(request);
  const generation=registered?.generation??iframeGuard.generation();
  if(routeReader){
   let reason:RedirectGuardFailure='BROWSER_GUARD_ROUTE_IDENTITY';
   try{
    const evidence=registered?.evidence;
    if(!evidence||!evidence.current())return refuse();
    reason='BROWSER_GUARD_ROUTE_TARGET';
    const origin=evidence.targetId===mainId?{guard:mainTarget,current:mainTarget.current}:
     await iframeGuard.get(evidence.targetId,generation);
    if(!origin.current())return refuse();
    // Playwright's route and our passive CDP events travel on separate sessions.
    // A route may arrive first. Keep it paused until its own event arrives.
    let entry:ObservedEvent=origin.guard.requests.get(evidence.networkId);
    if(!entry)entry=await origin.guard.waitForEvent(evidence.networkId);
    if(!origin.current())return refuse();
    reason='BROWSER_GUARD_ROUTE_EVENT_RETIRED';
    if(entry==='RETIRED'||entry&&origin.guard.requests.get(evidence.networkId)!==entry)return refuse();
    reason='BROWSER_GUARD_ROUTE_IDENTITY';
    if(!evidence.current()||request.frame().isDetached())return refuse();
    reason='BROWSER_GUARD_ROUTE_EVENT_MISSING';
    if(!entry)return refuse();
    reason='BROWSER_GUARD_ROUTE_EVENT_FRAME';
    if(entry.frameId!==evidence.frameId)return refuse();
    reason='BROWSER_GUARD_ROUTE_EVENT_URL';
    if(entry.url!==request.url())return refuse();
    reason='BROWSER_GUARD_ROUTE_EVENT_METHOD';
    if(entry.method!==request.method())return refuse();
    reason='BROWSER_GUARD_ROUTE_EVENT_CLAIMED';
    if(entry.allowed)return refuse();
    reason='BROWSER_GUARD_ROUTE_EVENT_PREFLIGHT';
    if(entry.method==='OPTIONS'&&!entry.preflight)return refuse();
    entry.allowed=true;
   }catch{onFailure(reason);return refuse();}
   return;
  }
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
