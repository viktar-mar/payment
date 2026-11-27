import type {CDPSession,Frame,Page,Request} from '@playwright/test';
import {billingBounded} from '../../../../infrastructure/stripe-billing-limits.mjs';
// @ts-ignore Native Node executes the same iframe guard source as Playwright.
import {guardIframeTargets,type GuardSession} from './stripe-billing-chromium-iframe.ts';
// @ts-ignore Native Node executes the pinned Chromium identity reader.
import {chromiumRouteReader,type ChromiumRouteEvidence} from './stripe-billing-chromium-route.ts';
// @ts-ignore Native Node executes the same issuer-origin validator.
import {isApprovedIssuerOrigin,isIssuerScriptPath,issuerAssetSuffixMatches} from './stripe-billing-initial-3ds.ts';
// @ts-ignore Native Node executes the same compressed decision observer.
import {issuerDecisionBodyLimit,observeCompressedIssuerDecision,type IssuerDecisionBody} from './stripe-billing-initial-3ds-decision-body.ts';

const refuse=():never=>{throw Error('billing_BROWSER');};
// Keep response interception aligned with every original HTTP destination the guarded
// checkout may forward. The separate pinned presentation fetch already uses redirect:error.
const stripeOrigins=['https://js.stripe.com','https://api.stripe.com','https://hooks.stripe.com','https://r.stripe.com','https://merchant-ui-api.stripe.com'];
type Origins={backend:string;demo:string};
type Header={name:string;value:string};
type Preflight={origin:string;method:string;names?:string};
type FrameWait={settle:(matched:boolean)=>void;invalidated:boolean};
type Entry={url:string;method:string;frameId?:string;loaderId?:string;allowed:boolean;expectedAssetBlock?:boolean;preflight?:Preflight;
 issuerParser?:{url:string;loaderId:string};allowedIssuerScript?:boolean;allowedIssuerResult?:boolean;issuerDecision?:Request;
 issuerScriptProof?:{request:Request;document:Request};cached?:boolean;
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
 |'BROWSER_GUARD_ROUTE_EVENT_CLAIMED'|'BROWSER_GUARD_ROUTE_EVENT_PREFLIGHT'
 |'BROWSER_GUARD_ROUTE_ISSUER_SCRIPT_PARSER'|'BROWSER_GUARD_RESPONSE_ISSUER_SCRIPT'
 |'BROWSER_GUARD_ROUTE_ISSUER_RESULT_DOCUMENT'|'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'|'BROWSER_GUARD_RESPONSE_ISSUER_DECISION';
function issuerScriptResponse(headers:Header[]|undefined){
 const types=headers?.filter(header=>header.name.toLowerCase()==='content-type')??[];
 return types.length===1 && /^(?:text|application)\/javascript(?:\s*;\s*charset=utf-8)?$/i.test(types[0]!.value.trim()) &&
  !headers?.some(header=>['set-cookie','content-disposition','location'].includes(header.name.toLowerCase()));
}
function issuerResultResponse(headers:Header[]|undefined){
 const types=headers?.filter(header=>header.name.toLowerCase()==='content-type')??[];
 return types.length===1 && /^text\/html(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?$/i.test(types[0]!.value.trim()) &&
  !headers?.some(header=>['set-cookie','content-disposition','location','refresh'].includes(header.name.toLowerCase()));
}
function issuerBodyPolicy(headers:Header[]|undefined,script=false){
 const lengths=headers?.filter(header=>header.name.toLowerCase()==='content-length')??[];
 const encodings=headers?.filter(header=>header.name.toLowerCase()==='content-encoding')??[];
 const transfers=headers?.filter(header=>header.name.toLowerCase()==='transfer-encoding')??[];
 if(lengths.length>1||encodings.length>1||transfers.length>1)return;
 const length=lengths[0]?.value;
 const size=length===undefined?undefined:Number(length);
 if(length!==undefined&&(!/^[1-9][0-9]{0,4}$/.test(length)||size!>issuerDecisionBodyLimit))return;
 const encoding=encodings[0]?.value.trim().toLowerCase()??'identity';
 if(encoding==='identity'){
  if(size!==undefined&&!transfers.length)return {kind:'identity' as const,size};
  if(script&&size===undefined&&(!transfers.length||transfers[0]!.value.trim().toLowerCase()==='chunked'))return {kind:'identity' as const,size};
  return;
 }
 if(!['gzip','deflate','br','zstd'].includes(encoding)||
    transfers.length&&(size!==undefined||transfers[0]!.value.trim().toLowerCase()!=='chunked'))return;
 return {kind:'compressed' as const,size};
}
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
export async function guardChallengeRedirects(page:Page,onFailure:(reason:RedirectGuardFailure)=>void,origins:Origins,signal?:AbortSignal,approvedIssuerOrigin?:string,captureIssuerBodies=false){
 if(signal?.aborted || approvedIssuerOrigin!==undefined&&!isApprovedIssuerOrigin(approvedIssuerOrigin))return refuse();
 const routeReader=chromiumRouteReader(page);
 for(const fresh of [origins.backend,origins.demo]){
  const url=new URL(fresh);
  if(url.origin!==fresh || !/^http:\/\/127\.0\.0\.1:[1-9]\d{3,4}$/.test(fresh))refuse();
 }
 if(origins.backend===origins.demo)refuse();
 const exactOrigins=[...stripeOrigins,origins.backend,origins.demo,...(approvedIssuerOrigin?[approvedIssuerOrigin]:[])];
 const patterns=exactOrigins.map(origin=>origin+'/*').concat('https://*.js.stripe.com/*');
 const issuerDecisionBodies=new WeakMap<Request,Buffer|Promise<IssuerDecisionBody>>();
 const issuerScriptBodies=new WeakMap<Request,{document:Request;body:Promise<IssuerDecisionBody>}>();
 const scriptDocuments=new WeakSet<Request>();
 function tracked(raw:string){
  const url=new URL(raw);return exactOrigins.includes(url.origin)||stripeScript(url);
 }
 async function arm(session:GuardSession):Promise<Guard>{
  const requests=new Map<string,Entry>();
  const waiters=new Map<string,(event:ObservedEvent)=>void>();
  const retired=new Set<string>();
  const pendingPauses=new Map<string,FrameWait>();
  const readingDecisions=new Map<string,FrameWait>();
  let closed=false;
  const responseBodies=captureIssuerBodies&&routeReader?observeCompressedIssuerDecision(session,signal):undefined;
  function waitForEvent(id:string):Promise<ObservedEvent>{
   if(closed||waiters.has(id)||waiters.size>=24)return Promise.resolve(undefined);
   return new Promise(resolve=>{
    const timer=setTimeout(()=>waiters.get(id)?.(undefined),1000);
    waiters.set(id,event=>{clearTimeout(timer);waiters.delete(id);resolve(event);});
   });
  }
  session.on('Network.requestWillBeSent',event=>{
   if(closed)return;
   const repeatedCapture=responseBodies?.has(event.requestId);
   if(!repeatedCapture&&!tracked(event.request.url))return;
   if(event.redirectResponse){responseBodies?.invalidate(event.requestId);retire(event);onFailure('BROWSER_GUARD_REQUEST_REDIRECT');return;}
   if(requests.has(event.requestId)||retired.has(event.requestId)||repeatedCapture){
    responseBodies?.invalidate(event.requestId);
    retire({requestId:event.requestId});onFailure('BROWSER_GUARD_REQUEST_PAIR');return;
   }
   if(requests.size>=24){onFailure('BROWSER_GUARD_REQUEST_CAPACITY');return;}
   const issuerTraffic=approvedIssuerOrigin&&new URL(event.request.url).origin===approvedIssuerOrigin;
   const preflight=issuerTraffic?undefined:probe(event.request,event.initiator?.type,origins);
   const entry:Entry={frameId:event.frameId,loaderId:event.loaderId,url:event.request.url,method:event.request.method,allowed:false,preflight};
   if(issuerTraffic && event.type==='Script' && event.initiator?.type==='parser' &&
      event.initiator.url===event.documentURL && event.loaderId)entry.issuerParser={url:event.documentURL,loaderId:event.loaderId};
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
  session.on('Network.requestServedFromCache',event=>{
   const entry=requests.get(event.requestId);
   if(entry)entry.cached=true;
  });
  function closeGuard(){
   if(closed)return;
   closed=true;
   responseBodies?.close();
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
   const networkId=event.networkId,original=networkId?requests.get(networkId):undefined;
   if(responseBodies?.duplicatePause(event.requestId,networkId)){
    if(!closed)onFailure('BROWSER_GUARD_RESPONSE_PAIR_DUPLICATE');
    return;
   }
   const repeated=pendingPauses.get(event.requestId);
   const fetchReadId='fetch:'+event.requestId,networkReadId='network:'+networkId;
   const decisionRead=()=>readingDecisions.get(fetchReadId)??readingDecisions.get(networkReadId);
   const activeRead=decisionRead();
   if(activeRead){
    invalidateFrameWait(activeRead);
    if(!closed)onFailure('BROWSER_GUARD_RESPONSE_PAIR_DUPLICATE');
    return;
   }
   let reason:RedirectGuardFailure='BROWSER_GUARD_RESPONSE_PAIR_NETWORK_ID';
   let waitingClaim:FrameWait|undefined;
   let capturedDecision:Request|undefined;
   let capturedScript:Request|undefined;
   let releaseBody:(()=>void)|undefined;
   try{
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
    if(event.responseErrorReason){
     reason='BROWSER_GUARD_RESPONSE_NETWORK';
     if(original.expectedAssetBlock && !original.allowed && original.frameId===event.frameId &&
        event.responseErrorReason==='BlockedByClient' && event.responseStatusCode===undefined && !event.responseHeaders?.length){
      // Chromium can pause the synthetic abort a second time at the response stage.
      // The exact request remains blocked and never acquires response authority.
      requests.delete(networkId);
      await session.send('Fetch.failRequest',{requestId:event.requestId,errorReason:'BlockedByClient'});
      return;
     }
     refuse();
    }
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
    if(original.allowedIssuerScript){
     reason='BROWSER_GUARD_RESPONSE_ISSUER_SCRIPT';
     if(status!==200 || event.resourceType!=='Script' || !issuerScriptResponse(event.responseHeaders))refuse();
    }
    if(original.allowedIssuerResult){
     reason='BROWSER_GUARD_RESPONSE_ISSUER_RESULT';
     if(status!==200 || event.resourceType!=='Document' || !issuerResultResponse(event.responseHeaders))refuse();
    }
    reason='BROWSER_GUARD_RESPONSE_PAIR_NETWORK_FRAME_MISSING';
    if(!original.frameId)return refuse();
    reason='BROWSER_GUARD_RESPONSE_PAIR_FRAME';
    if(original.frameId!==event.frameId)return refuse();
    if(original.issuerDecision && status===200 && event.resourceType==='Document'&&issuerResultResponse(event.responseHeaders)){
     const policy=issuerBodyPolicy(event.responseHeaders);
     if(policy?.kind==='identity'&&policy.size!==undefined){
      const size=policy.size;
      // Chromium may retire the old renderer when CRes starts navigating. Read
      // the measured bytes from this exact response pause before releasing it.
      reason='BROWSER_GUARD_RESPONSE_ISSUER_DECISION';
      const claim:FrameWait={settle:()=>{},invalidated:false};
      original.frameWait=claim;
      waitingClaim=claim;
      pendingPauses.set(event.requestId,claim);
      const value=await billingBounded({deadline:Date.now()+1000,signal},async()=>{
       if(claim.invalidated)return refuse();
       readingDecisions.set(fetchReadId,claim);
       readingDecisions.set(networkReadId,claim);
       try{return await session.send('Fetch.getResponseBody',{requestId:event.requestId});}
       finally{
        readingDecisions.delete(fetchReadId);
        readingDecisions.delete(networkReadId);
       }
      });
      if(closed || claim.invalidated || requests.get(networkId)!==original ||
         (value.base64Encoded?value.body.length>Math.ceil(size/3)*4:Buffer.byteLength(value.body,'utf8')!==size))return refuse();
      const bytes=Buffer.from(value.body,value.base64Encoded?'base64':'utf8');
      if(bytes.length!==size)return refuse();
      capturedDecision=original.issuerDecision;
      issuerDecisionBodies.set(capturedDecision,bytes);
     }else if(policy?.kind==='compressed'&&responseBodies&&original.loaderId){
      reason='BROWSER_GUARD_RESPONSE_ISSUER_DECISION';
      const capture=responseBodies.observe({id:networkId,fetchId:event.requestId,frameId:original.frameId,
       loaderId:original.loaderId,url:original.url,encodedLength:policy.size});
      issuerDecisionBodies.set(original.issuerDecision,capture.body);
      releaseBody=capture.released;
     }
    }
    if(original.issuerScriptProof&&responseBodies&&original.loaderId&&!original.cached){
     const policy=issuerBodyPolicy(event.responseHeaders,true);
     if(policy){
      reason='BROWSER_GUARD_RESPONSE_ISSUER_SCRIPT';
      const {request,document}=original.issuerScriptProof;
      const utf8=event.responseHeaders?.some(header=>header.name.toLowerCase()==='content-type'&&
       /^[\t ]*(?:text|application)\/javascript[\t ]*;[\t ]*charset=utf-8[\t ]*$/i.test(header.value));
      const capture=responseBodies.observe({id:networkId,fetchId:event.requestId,frameId:original.frameId,
       loaderId:original.loaderId,url:original.url,resource:'Script',encodedLength:policy.size,
       decodedLength:policy.kind==='identity'?policy.size:undefined,asciiOnly:!utf8});
      capturedScript=request;
      issuerScriptBodies.set(request,{document,body:capture.body});
      releaseBody=capture.released;
     }
    }
    requests.delete(networkId);
    reason='BROWSER_GUARD_RESPONSE_RESUME';
    await session.send('Fetch.continueResponse',{requestId:event.requestId});
    releaseBody?.();
   }catch{
    if(capturedDecision)issuerDecisionBodies.delete(capturedDecision);
    if(capturedScript)issuerScriptBodies.delete(capturedScript);
    if(releaseBody&&networkId)responseBodies?.invalidate(networkId);
    // Fetch.getResponseBody owns the paused request until it settles. On timeout
    // report failure without resuming it or racing that command with failRequest.
    try{if(!decisionRead())await session.send('Fetch.failRequest',{requestId:event.requestId,errorReason:'BlockedByClient'});}
    catch{}finally{onFailure(reason);}
   }finally{if(waitingClaim)pendingPauses.delete(event.requestId);}
  });
  if(responseBodies)await responseBodies.enable();
  else await session.send('Network.enable');
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
 const issuerResults=new WeakSet<Request>();
 type IssuerClaim={kind:'script';document:Request;capture:boolean}|{kind:'result';document:Request}|{kind:'decision'};
 async function claimRoute(request:Request,blockIssuerAsset:boolean,issuerClaim?:IssuerClaim){
  const url=new URL(request.url());
  const fromApprovedIssuer=approvedIssuerOrigin!==undefined && url.origin===approvedIssuerOrigin;
  const issuerScript=issuerClaim?.kind==='script'?issuerClaim.document:undefined;
  const issuerScriptDocument=issuerScript?.url();
  const issuerResult=issuerClaim?.kind==='result'?issuerClaim.document:undefined;
  const issuerResultDocument=issuerResult?.url();
  const issuerDecision=issuerClaim?.kind==='decision';
  if(issuerDecision && (!routeReader || !routes.has(request) || !(fromApprovedIssuer||url.origin==='https://hooks.stripe.com') ||
     url.href!==request.url() || url.username || url.password || url.hash ||
     request.method()!=='POST' || !request.isNavigationRequest() || request.resourceType()!=='document' ||
     request.redirectedFrom() || request.serviceWorker())){
   onFailure('BROWSER_GUARD_ROUTE_IDENTITY');return refuse();
  }
  if(issuerResult && issuerResultDocument!==undefined){
   const source=URL.canParse(issuerResultDocument)?new URL(issuerResultDocument):undefined;
   if(!routeReader || !routes.has(request) || url.origin!=='https://hooks.stripe.com' || url.href!==request.url() ||
      url.username || url.password || url.hash || url.pathname==='/' || !source ||
      (source.origin!=='https://hooks.stripe.com' && source.origin!==approvedIssuerOrigin) ||
      source.href!==issuerResultDocument || source.username || source.password || source.hash ||
      issuerResult===request || issuerResult.frame()!==request.frame() || request.frame().url()!==issuerResultDocument ||
      issuerResult.method()!=='POST' || !issuerResult.isNavigationRequest() || issuerResult.resourceType()!=='document' ||
      issuerResult.redirectedFrom() || issuerResult.serviceWorker() ||
      request.method()!=='POST' || !request.isNavigationRequest() || request.resourceType()!=='document' ||
      request.redirectedFrom() || request.serviceWorker()){
    onFailure('BROWSER_GUARD_ROUTE_IDENTITY');return refuse();
   }
  }
  if(issuerScriptDocument!==undefined?
     !routeReader || !fromApprovedIssuer || !routes.has(request) || !URL.canParse(issuerScriptDocument) ||
      new URL(issuerScriptDocument).origin!==url.origin || request.frame().url()!==issuerScriptDocument ||
      issuerScript!.frame()!==request.frame() || issuerScript!.method()!=='POST' ||
      request.method()!=='GET' || request.isNavigationRequest() || request.resourceType()!=='script' ||
      request.redirectedFrom() || request.serviceWorker() ||
      url.href!==url.origin+url.pathname || !isIssuerScriptPath(url.pathname):
    blockIssuerAsset?
     !routes.has(request) || !fromApprovedIssuer || request.method()!=='GET' || request.isNavigationRequest() ||
      !issuerAssetSuffixMatches(request.resourceType(),url.pathname) || url.href!==url.origin+url.pathname || url.pathname.includes('%'):
     fromApprovedIssuer && (request.method()!=='POST'||!request.isNavigationRequest()||request.resourceType()!=='document')){
   onFailure('BROWSER_GUARD_ROUTE_IDENTITY');return refuse();
  }
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
    if(entry.allowed || entry.expectedAssetBlock)return refuse();
    reason='BROWSER_GUARD_ROUTE_EVENT_PREFLIGHT';
    if(entry.method==='OPTIONS'&&!entry.preflight)return refuse();
    if(issuerScriptDocument!==undefined){
     reason='BROWSER_GUARD_ROUTE_ISSUER_SCRIPT_PARSER';
     if(entry.issuerParser?.url!==issuerScriptDocument || entry.issuerParser.loaderId!==evidence.documentId ||
        evidence.documentId!==routeReader.navigationDocument(issuerScript!) ||
        request.frame().url()!==issuerScriptDocument)return refuse();
     entry.allowedIssuerScript=true;
     if(issuerClaim?.kind==='script'&&issuerClaim.capture&&issuerDecisionBodies.has(issuerClaim.document)&&
        !scriptDocuments.has(issuerClaim.document)){
      scriptDocuments.add(issuerClaim.document);
      entry.issuerScriptProof={request,document:issuerClaim.document};
     }
    }
    if(issuerResult){
     reason='BROWSER_GUARD_ROUTE_ISSUER_RESULT_DOCUMENT';
     // For a document navigation the new Network loader belongs to the
     // destination. The route captured the currently committed issuer document.
     const source=routeReader.navigationDocument(issuerResult);
     if(!source || evidence.documentId!==source || request.frame().url()!==issuerResultDocument ||
        issuerResults.has(issuerResult))return refuse();
     issuerResults.add(issuerResult);
     entry.allowedIssuerResult=true;
    }
    if(blockIssuerAsset)entry.expectedAssetBlock=true;
    else{
     if(issuerDecision)entry.issuerDecision=request;
     entry.allowed=true;
    }
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
  if(matches.length!==1 || matches[0]!.allowed || matches[0]!.expectedAssetBlock || request.method()==='OPTIONS' && !matches[0]!.preflight)refuse();
  // Only explicitly registered protocol fixtures use this branch.
  if(blockIssuerAsset)matches[0]!.expectedAssetBlock=true;
  else matches[0]!.allowed=true;
 }
 return {
  registerRoute(request:Request){
   if(!routes.has(request))routes.set(request,{generation:iframeGuard.generation(),evidence:routeReader?.capture(request)});
  },
  authorize:(request:Request)=>claimRoute(request,false),
  expectIssuerAssetBlock:(request:Request)=>claimRoute(request,true),
  authorizeIssuerScript:(request:Request,document:Request,capture=false)=>claimRoute(request,false,{kind:'script',document,capture}),
  authorizeIssuerDecision:routeReader?(request:Request)=>claimRoute(request,false,{kind:'decision'}):undefined,
  takeIssuerDecisionBody(request:Request){
   const body=issuerDecisionBodies.get(request);
   issuerDecisionBodies.delete(request);
   return body;
  },
  takeIssuerScriptBody(request:Request,document:Request){
   const receipt=issuerScriptBodies.get(request);
   if(receipt?.document!==document)return;
   issuerScriptBodies.delete(request);
   return receipt.body;
  },
  authorizeIssuerResult:(request:Request,document:Request)=>claimRoute(request,false,{kind:'result',document}),
 };
}
