import type {CDPSession,Frame,Page,Request} from '@playwright/test';

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
 |'BROWSER_GUARD_RESPONSE_PREFLIGHT'|'BROWSER_GUARD_RESPONSE_UNCLAIMED'|'BROWSER_GUARD_RESPONSE_RESUME';
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
type Target={session:CDPSession;root:Frame;requests:Map<string,Entry>};
type Tree={frame:{id:string;name?:string;url:string;urlFragment?:string};childFrames?:Tree[]};
function frameId(tree:Tree,root:Frame,frame:Frame):string {
 const chain:Frame[]=[];
 for(let current:Frame|null=frame;current!==root;current=current.parentFrame()){
  if(!current||current.isDetached())return refuse();chain.unshift(current);
 }
 function matches(node:Tree,f:Frame){
  return (node.frame.url+(node.frame.urlFragment??''))===(f.url()||'about:blank') && (node.frame.name??'')===f.name();
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
 const targets=new WeakMap<Frame,Promise<Target>>();
 async function attach(root:Frame):Promise<Target>{
  let session:CDPSession;
  try{session=await page.context().newCDPSession(root===page.mainFrame()?page:root);}
  catch(error){
   const parent=root.parentFrame();
   // Playwright 1.63 explicitly reports in-process frames as part of their parent's CDP target.
   if(parent && error instanceof Error && error.message.endsWith("This frame does not have a separate CDP session, it is a part of the parent frame's session"))return target(parent);
   throw Error('billing_BROWSER');
  }
  const requests=new Map<string,Entry>();let ready=false;
  session.on('Network.requestWillBeSent',event=>{
   if(!ready||!tracked(event.request.url))return;
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
  ready=true;return {session,root,requests};
 }
 function target(frame:Frame):Promise<Target>{
  const existing=targets.get(frame);if(existing)return existing;
  const pending=attach(frame);targets.set(frame,pending);void pending.catch(()=>{});return pending;
 }
 // A cross-origin commit can change which target owns a frame. Discover a fresh
 // child target when the previous owner was its parent, while keeping the parent's guard active.
 function refresh(frame:Frame){
  const old=targets.get(frame);
  if(!old){void target(frame);return;}
  void old.then(previous=>{
   if(previous.root!==frame && targets.get(frame)===old){targets.delete(frame);void target(frame);}
  },()=>{if(targets.get(frame)===old){targets.delete(frame);void target(frame);}});
 }
 page.on('frameattached',frame=>{void target(frame);});page.on('framenavigated',refresh);
 await Promise.all(page.frames().map(target));
 const main=page.mainFrame(),mainTarget=await target(main);
 // The renderer cannot answer Page.getFrameTree while its own navigation is paused.
 // Bind the main CDP frame before navigation and keep requiring the matching network event.
 const {frameTree}=await mainTarget.session.send('Page.getFrameTree');
 const mainId=frameId(frameTree,main,main);
 if(!mainId)refuse();
 return {async authorize(request:Request){
  const frame=request.frame(),origin=await target(frame);
  let id:string;
  if(frame===main){
   if(page.mainFrame()!==main||main.isDetached()||origin!==mainTarget)refuse();
   id=mainId;
  }else{
   const {frameTree}=await origin.session.send('Page.getFrameTree');
   id=frameId(frameTree,origin.root,frame);
  }
  const matches=[...origin.requests.values()].filter(entry=>entry.frameId===id && entry.url===request.url() && entry.method===request.method());
  if(matches.length!==1 || matches[0]!.allowed || request.method()==='OPTIONS' && !matches[0]!.preflight)refuse();
  matches[0]!.allowed=true;
 }};
}
