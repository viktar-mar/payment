import {createHash,randomUUID} from 'node:crypto';
import type {Frame,Page,Request,Route} from '@playwright/test';
import type {BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {RefusedIssuerRelation} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';

function requireValue(value:unknown):asserts value {if(!value)throw Error('billing_BROWSER');}
function object(value:unknown):Record<string,unknown>{
 requireValue(value && typeof value==='object' && !Array.isArray(value));return value as Record<string,unknown>;
}
const uuid=(v:unknown)=>typeof v==='string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v);
const issuerOrigin='https://hooks.stripe.com';
const directIssuerFrame='stripe-challenge-frame';
const reservedStripeHosts=new Set(['api','hooks','js','m','r','merchant-ui-api']);
export function issuerAssetSuffixMatches(kind:string,pathname:string):boolean{
 if(kind==='image')return /\.(?:png|jpe?g|gif|webp|ico)$/.test(pathname);
 return kind==='font'&&/\.woff2?$/.test(pathname);
}
export function isApprovedIssuerOrigin(value:unknown):value is string{
 if(typeof value!=='string')return false;
 const label=/^https:\/\/([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.stripe\.com$/.exec(value)?.[1];
 if(!label||reservedStripeHosts.has(label))return false;
 try{return new URL(value).origin===value;}catch{return false;}
}
function initialIssuerDocument(frame:Frame){
 return frame.name()!==directIssuerFrame||frame.url()==='about:blank';
}
function exactUrl(value:unknown){
 requireValue(typeof value==='string' && value.length<=4096);
 const url=new URL(value);requireValue(url.href===value && !url.username && !url.password && !url.hash && url.pathname!=='/');
 return url;
}
export function challengeSource(raw:unknown,secret:string,report?:(code:string)=>void):string{
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_OBJECT');
 const intent=object(raw);requireValue(intent.object==='payment_intent');
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_IDENTITY');
 requireValue(intent.id===secret.split('_secret_')[0]);
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_SECRET');
 requireValue(intent.client_secret===secret);
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_MODE');
 requireValue(intent.livemode===false);
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_STATE');
 requireValue(intent.status==='requires_action');
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_ACTION');
 const action=object(intent.next_action);requireValue(action.type==='use_stripe_sdk');
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_SDK');
 const sdk=object(action.use_stripe_sdk);
 report?.(sdk.type==='stripe_3ds2_challenge'?'BROWSER_CHALLENGE_SOURCE_WIRE_SDK_CHALLENGE':'BROWSER_CHALLENGE_SOURCE_WIRE_SDK_TYPE');
 requireValue(sdk.type==='stripe_3ds2_fingerprint');
 const source=sdk.three_d_secure_2_source;
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_VALUE_MISSING');
 requireValue(source!==undefined && source!==null);
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_VALUE_TYPE');
 requireValue(typeof source==='string');
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_VALUE_LENGTH');
 requireValue(source.length>0 && source.length<=204);
 // Stripe.js owns the token format. Trust only the exact value in this owned response.
 report?.('BROWSER_CHALLENGE_SOURCE_WIRE_VALUE_ALPHABET');
 requireValue(!/[^A-Za-z0-9_-]/.test(source));
 return source;
}
function observedIssuerBinding(raw:unknown){
 const result=object(raw),ares=object(result.ares);
 requireValue(result.state==='challenge_required' && ares.messageType==='ARes' && ares.transStatus==='C' &&
  typeof ares.messageVersion==='string' && /^2\.\d\.\d$/.test(ares.messageVersion) && uuid(ares.threeDSServerTransID) &&
  uuid(ares.acsTransID) && ares.acsTransID!==ares.threeDSServerTransID);
 const url=exactUrl(ares.acsURL);
 return Object.freeze({url:url.href,version:ares.messageVersion,acs:ares.acsTransID,server:ares.threeDSServerTransID});
}
export function issuerBinding(raw:unknown,approvedOrigin?:string){
 requireValue(approvedOrigin===undefined||isApprovedIssuerOrigin(approvedOrigin));
 const value=observedIssuerBinding(raw);
 requireValue(new URL(value.url).origin===(approvedOrigin??issuerOrigin));
 return value;
}
export function matchingChallengeRequest(binding:ReturnType<typeof issuerBinding>,url:string,body:string|null){
 try{
  requireValue(url===binding.url && body && body.length<=32768);
  const fields=new URLSearchParams(body),creq=fields.get('creq');
  requireValue([...fields.keys()].length===1 && creq && /^[A-Za-z0-9_-]+={0,2}$/.test(creq));
  const msg=object(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(Buffer.from(creq,'base64url'))));
  return msg.messageType==='CReq' && msg.messageVersion===binding.version && msg.threeDSServerTransID===binding.server && msg.acsTransID===binding.acs;
 }catch{return false;}
}
function unique(fields:URLSearchParams,name:string,expected:string){
 return fields.getAll(name).length===1 && fields.get(name)===expected;
}
export function challengeApiRequest(url:URL,method:string,data:string|null,key:string,secret:string,source:string|undefined){
 requireValue(url.origin==='https://api.stripe.com' && !url.username && !url.password && !url.hash);
 if(url.pathname==='/v1/payment_intents/'+secret.split('_secret_')[0]){
  requireValue(method==='GET' && data===null && unique(url.searchParams,'client_secret',secret) &&
   (!url.searchParams.has('key')||unique(url.searchParams,'key',key)));
  return 'retrieve';
 }
 requireValue(method==='POST' && !url.search && data && data.length<=65536 && source);
 const fields=new URLSearchParams(data);
 requireValue(unique(fields,'source',source) && (!fields.has('key')||unique(fields,'key',key)));
 if(url.pathname==='/v1/3ds2/authenticate')return 'authenticate';
 requireValue(url.pathname==='/v1/3ds2/challenge_complete');return 'complete';
}

export type WaitForIssuerGesture=(limits:{deadline:number;signal:AbortSignal})=>Promise<void>;
interface FrameSnapshot {target:Frame;frames:Map<Frame,string>}
type Options={
 page:Page;limits:BillingLimits;demo:string;owner:()=>{secret:string;key:string};
 winning:()=>Promise<void>;authorizeResponse:(req:Request)=>Promise<void>;check:()=>Promise<void>;
 expectIssuerAssetBlock:(req:Request)=>Promise<void>;
 stripeFrames:(req:Request)=>FrameSnapshot|undefined;waitForGesture:WaitForIssuerGesture;
 approvedIssuerOrigin?:string;
};
function sameChain(a:FrameSnapshot|undefined,b:FrameSnapshot|undefined){
 requireValue(a && b && a.target===b.target && a.frames.size===b.frames.size && [...a.frames].every(([f,url])=>b.frames.get(f)===url));
}

/** Scoped to one original v2 session. Only Stripe.js sends requests and only the issuer UI completes the challenge. */
export function initial3dsChallenge({page,limits,demo,owner,winning,authorizeResponse,expectIssuerAssetBlock,check,stripeFrames,waitForGesture,approvedIssuerOrigin}:Options){
 requireValue(approvedIssuerOrigin===undefined||isApprovedIssuerOrigin(approvedIssuerOrigin));
 let retrieve:Request|undefined,authenticate:Request|undefined,admittedAuthenticate:Request|undefined;
 let completed=false,finalRead=false,initialIssuerDispatched=false,boundSource:string|undefined;
 let issuer:Frame|undefined,issuerRequest:Request|undefined,submission:Request|undefined,binding:ReturnType<typeof issuerBinding>|undefined;
 let arm:{document:FrameSnapshot;action:string;bodyHash:string;gesture:Promise<void>}|undefined;
 let handoffStarted=false,passiveDeadline:number|undefined;
 const passiveBlocks=new Map<string,Promise<void>>();
 const passiveFailures=new Map<Request,()=>void>();
 const issuerReady=Promise.withResolvers<void>(),issuerDispatched=Promise.withResolvers<void>(),navigation=new WeakMap<Frame,number>();
 const revision=(frame:Frame)=>navigation.get(frame)??0;
 page.on('framenavigated',frame=>navigation.set(frame,revision(frame)+1));
 page.on('requestfailed',request=>{
  const confirm=passiveFailures.get(request);
  if(!confirm)return;
  const reason=request.failure()?.errorText;
  if(reason==='net::ERR_BLOCKED_BY_CLIENT' || reason==='net::ERR_BLOCKED_BY_CLIENT.Inspector')confirm();
 });
 function stamped(chain:FrameSnapshot|undefined){
  if(!chain)return;
  return {target:chain.target,frames:new Map([...chain.frames].map(([frame,url])=>[frame,revision(frame)+' '+url]))};
 }
 let expectedIssuerRevision=-1,issuerParent:FrameSnapshot|undefined;

 function frameChain(target:Frame):FrameSnapshot{
  const parent=target.parentFrame(),main=page.mainFrame();
  // Stripe.js EMVCo submits CReq directly into its named challenge frame.
  const direct=target.name()===directIssuerFrame;
  const outer=direct?parent:parent?.parentFrame();
  requireValue(parent && outer && outer.parentFrame()===main &&
   /^__privateStripeFrame/.test(outer.name()) && /^https:\/\/js\.stripe\.com\/v3\/three-ds-2-challenge-[A-Za-z0-9_.-]+(?:[?#].*)?$/.test(outer.url()) &&
   new URL(main.url()).origin===demo);
  if(!direct)requireValue(target.name()==='acsFrame' && parent.name()===directIssuerFrame &&
   (parent.url()==='about:blank' || new URL(parent.url()).origin==='https://js.stripe.com'));
  const frames=new Map<Frame,string>();for(const f of direct?[target,outer,main]:[target,parent,outer,main]){
   requireValue(f.page()===page && !f.isDetached());frames.set(f,f.url());
  }
  return {target,frames};
 }
 function committedIssuerDocument(frame:Frame){
  requireValue(binding && issuerRequest && frame===issuer && frame.url()===binding.url);
  const html=issuerRequest.existingResponse();
  requireValue(html && html.request()===issuerRequest && html.status()===200 && html.url()===binding.url && !html.fromServiceWorker());
  const document=stamped(frameChain(frame))!;requireValue(revision(frame)===expectedIssuerRevision);
  const parents={target:frame,frames:new Map(document.frames)};parents.frames.delete(frame);sameChain(issuerParent,parents);
  return document;
 }
 async function issuerResponded(active:BillingLimits,maximum=1000){
  const request=submission;
  if(!request)return false;
  const response=await originalResponse(request,active,maximum);
  requireValue(response.status()>=200 && response.status()<300);
  return true;
 }
 async function originalResponse(request:Request,active:BillingLimits,maximum:number){
  // Frame code can use a response before the Playwright client has observed it.
  const response=request.existingResponse()??await billingBounded(active,()=>request.response(),maximum);
  requireValue(response && response.request()===request && response.url()===request.url() && !response.fromServiceWorker());
  return response;
 }
 async function verifiedBody(request:Request|undefined){
  requireValue(request);
  const response=await originalResponse(request,limits,1000);requireValue(response.status()===200);
  const bytes=await billingBounded(limits,()=>response.body());requireValue(bytes.length<=65536);
  return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)) as unknown;
 }
 // Descriptive only. Capture the refused frame shape, then read only the
 // response to the authenticate POST already admitted by the browser guard.
 async function refusedIssuerRelation(request:Request,active:BillingLimits):Promise<RefusedIssuerRelation>{
  try{
   if(request.method()!=='POST'||!request.isNavigationRequest()||request.resourceType()!=='document'||request.redirectedFrom())return 'NOT_ISSUER_POST';
  }catch{return 'ISSUER_FRAME_UNVERIFIED';}
  let parentOrigin:string|undefined;
  try{
   const target=request.frame();frameChain(target);
   requireValue(initialIssuerDocument(target));
   const parent=target.parentFrame()!;
   parentOrigin=new URL(parent.url()==='about:blank'?parent.parentFrame()!.url():parent.url()).origin;
  }catch{/* The refused request can still be compared with the original transaction. */}
  const original=admittedAuthenticate;
  if(!original)return 'AUTHENTICATE_NOT_ADMITTED';
  try{
   return await billingBounded(active,async diagnostic=>{
    const headers=await request.allHeaders();
    if(headers['content-type']?.split(';')[0]!=='application/x-www-form-urlencoded'||!headers.origin||parentOrigin&&headers.origin!==parentOrigin||
       headers.cookie||headers.authorization||headers['proxy-authorization']||headers['stripe-account']||headers['stripe-context'])return 'ISSUER_FRAME_UNVERIFIED';
    const response=await originalResponse(original,diagnostic,750);
    requireValue(response.status()===200);
    const encoding=await response.headerValue('content-encoding');
    if(encoding!==null&&encoding.trim().toLowerCase()!=='identity')return 'AUTHENTICATE_BODY_UNBOUNDED';
    const {responseBodySize}=await original.sizes();
    if(!Number.isSafeInteger(responseBodySize)||responseBodySize<1||responseBodySize>65536)return 'AUTHENTICATE_BODY_UNBOUNDED';
    billingCheck(diagnostic);
    const bytes=await response.body();
    billingCheck(diagnostic);requireValue(bytes.length<=65536);
    const observed=observedIssuerBinding(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)));
    const url=request.url();
    if(observed.url!==url)return 'ISSUER_URL_MISMATCH';
    if(!matchingChallengeRequest(observed,url,request.postData()))return 'ISSUER_TRANSACTION_MISMATCH';
    return parentOrigin?'ISSUER_TRANSACTION_MATCH':'ISSUER_TRANSACTION_MATCH_FRAME_UNVERIFIED';
   },750);
  }catch{return 'AUTHENTICATE_UNVERIFIED';}
 }
 const owns=(url:URL)=>url.origin===issuerOrigin || url.origin===approvedIssuerOrigin || url.origin==='https://api.stripe.com' &&
  (url.pathname.startsWith('/v1/payment_intents/') || url.pathname.startsWith('/v1/3ds2/'));
 async function forward(route:Route,url:URL,report:(code:string)=>void){
  report('BROWSER_CHALLENGE_REQUEST');
  const request=route.request(),target=request.frame(),api=url.origin==='https://api.stripe.com',navigation=request.isNavigationRequest();
  requireValue(owns(url) && !request.redirectedFrom() && url.href===request.url() && !url.username && !url.password && !url.hash);
  const frameCode=api?'BROWSER_CHALLENGE_API_FRAME':'BROWSER_CHALLENGE_ISSUER_FRAME';
  report(frameCode);
  const approved=url.origin===approvedIssuerOrigin;
  if(approved)requireValue(target.name()===directIssuerFrame);
  const chain=()=>stamped(api?stripeFrames(request):frameChain(target)),original=chain();
  requireValue(original);
  report('BROWSER_CHALLENGE_WINNING');
  await winning();
  report('BROWSER_CHALLENGE_OWNER');
  await check();
  const {secret,key}=owner();requireValue(/^pi_[A-Za-z0-9]+_secret_[A-Za-z0-9]+$/.test(secret) && /^pk_test_[A-Za-z0-9_]+$/.test(key));
  report('BROWSER_CHALLENGE_HEADERS');
  const headers=await request.allHeaders(),method=request.method(),data=request.postData();
  requireValue(!headers.cookie && !headers['stripe-account'] && !headers['stripe-context'] && !headers['proxy-authorization'] && (!headers.authorization || api && headers.authorization==='Bearer '+key));
  const current=async()=>{
   report('BROWSER_CHALLENGE_OWNER');
   await check();
   report(frameCode);
   sameChain(original,chain());
  };
  const forwardOriginal=async()=>{
   report('BROWSER_CHALLENGE_ROUTE_GUARD');
   await authorizeResponse(request);
   await current();
   report('BROWSER_CHALLENGE_ROUTE_FORWARD');
   billingCheck(limits);
   if(request===authenticate)admittedAuthenticate=request;
   if(request===issuerRequest)initialIssuerDispatched=true;
   await route.continue();
  };
  if(api){
   report('BROWSER_CHALLENGE_API_REQUEST');
   requireValue(!navigation && (!headers.origin||headers.origin===new URL(target.url()).origin));
   const path=url.pathname,isRetrieve=path==='/v1/payment_intents/'+secret.split('_secret_')[0];
   if(method==='OPTIONS'){
    report('BROWSER_CHALLENGE_API_PREFLIGHT');
    requireValue(!headers.authorization && !data && headers['access-control-request-method']===(isRetrieve?'GET':'POST') &&
     (isRetrieve && (!retrieve || arm && !finalRead) || path==='/v1/3ds2/authenticate' && retrieve && !authenticate ||
      path==='/v1/3ds2/challenge_complete' && await issuerResponded(limits) && !completed));
    await current();await forwardOriginal();return;
   }
   if(!isRetrieve && !boundSource){
    report('BROWSER_CHALLENGE_SOURCE_RESPONSE');
    const body=await verifiedBody(retrieve);
    boundSource=challengeSource(body,secret,report);
   }
   report('BROWSER_CHALLENGE_API_REQUEST');
   const kind=challengeApiRequest(url,method,data,key,secret,boundSource);
   requireValue(headers.authorization==='Bearer '+key || (isRetrieve?url.searchParams:new URLSearchParams(data!)).get('key')===key);
   await current();
   report('BROWSER_CHALLENGE_API_ORDER');
   if(kind==='retrieve'){
    if(retrieve){
     requireValue(await issuerResponded(limits));await current();
     report('BROWSER_CHALLENGE_API_ORDER');
     requireValue(!finalRead);finalRead=true;
    }else retrieve=request;
   }else if(kind==='authenticate'){requireValue(!authenticate);authenticate=request;}
   else{
    requireValue(await issuerResponded(limits));await current();
    report('BROWSER_CHALLENGE_API_ORDER');
    requireValue(!completed);completed=true;
   }
  }else{
   if(approved && initialIssuerDispatched && !navigation){
    report('BROWSER_CHALLENGE_ISSUER_ASSET_HANDOFF');
    requireValue(!handoffStarted);
    report('BROWSER_CHALLENGE_ISSUER_ASSET_REPEAT');
    requireValue(!passiveBlocks.has(url.pathname));
    report('BROWSER_CHALLENGE_ISSUER_ASSET_LIMIT');
    requireValue(passiveBlocks.size<2);
    report('BROWSER_CHALLENGE_ISSUER_ASSET_BINDING');
    requireValue(binding && new URL(binding.url).origin===url.origin);
    report('BROWSER_CHALLENGE_ISSUER_ASSET_WORKER');
    requireValue(request.serviceWorker()===null);
    report('BROWSER_CHALLENGE_ISSUER_ASSET_ORIGIN');
    requireValue(!headers.origin||headers.origin===url.origin);
    const requireIssuerDocument=()=>{
     report('BROWSER_CHALLENGE_ISSUER_ASSET_DOCUMENT');
     sameChain(original,committedIssuerDocument(target));
    };
    requireIssuerDocument();
    report('BROWSER_CHALLENGE_ISSUER_ASSET_METHOD');
    requireValue(method==='GET' && data===null && !headers['transfer-encoding'] &&
     (!headers['content-length']||headers['content-length']==='0'));
    report('BROWSER_CHALLENGE_ISSUER_ASSET_PATH');
    requireValue(url.href===url.origin+url.pathname && !url.pathname.includes('%'));
    report('BROWSER_CHALLENGE_ISSUER_ASSET_KIND');
    const kind=request.resourceType();
    if(kind!=='image'&&kind!=='font'){
     switch(kind){
      case 'script': report('BROWSER_CHALLENGE_ISSUER_ASSET_SCRIPT');break;
      case 'stylesheet': report('BROWSER_CHALLENGE_ISSUER_ASSET_STYLESHEET');break;
      case 'xhr': report('BROWSER_CHALLENGE_ISSUER_ASSET_XHR');break;
      case 'fetch': report('BROWSER_CHALLENGE_ISSUER_ASSET_FETCH');break;
      default: report('BROWSER_CHALLENGE_ISSUER_ASSET_OTHER');
     }
     requireValue(false);
    }
    report(kind==='image'?'BROWSER_CHALLENGE_ISSUER_ASSET_IMAGE_SUFFIX':'BROWSER_CHALLENGE_ISSUER_ASSET_FONT_SUFFIX');
    requireValue(issuerAssetSuffixMatches(kind,url.pathname));
    // Both distinct requests share the first request's deadline. A late second
    // request cannot start a new proof window or inherit another request's receipt.
    passiveDeadline??=Math.min(limits.deadline,Date.now()+750);
    const blockLimits={deadline:passiveDeadline,signal:limits.signal};
    report('BROWSER_CHALLENGE_ISSUER_ASSET_DEADLINE');
    billingCheck(blockLimits);
    const failed=Promise.withResolvers<void>();
    const block=billingBounded(blockLimits,async active=>{
     await check();billingCheck(active);
     requireIssuerDocument();
     report('BROWSER_CHALLENGE_ISSUER_ASSET_BLOCK');
     await expectIssuerAssetBlock(request);
     await check();billingCheck(active);
     requireIssuerDocument();
     report('BROWSER_CHALLENGE_ISSUER_ASSET_BLOCK');
     passiveFailures.set(request,failed.resolve);
     // This optional asset never receives response-stage authority.
     await Promise.all([route.abort('blockedbyclient'),failed.promise]);
     await check();billingCheck(active);
     requireIssuerDocument();
    },750);
    passiveBlocks.set(url.pathname,block);
    try{await block;}finally{passiveFailures.delete(request);}
    return;
   }
   const stage=initialIssuerDispatched?'LATER':'INITIAL';
   report('BROWSER_CHALLENGE_ISSUER_'+stage+'_NAVIGATION');requireValue(navigation);
   report('BROWSER_CHALLENGE_ISSUER_'+stage+'_METHOD');requireValue(method==='POST');
   report('BROWSER_CHALLENGE_ISSUER_'+stage+'_RESOURCE');requireValue(request.resourceType()==='document');
   report('BROWSER_CHALLENGE_ISSUER_'+stage+'_MEDIA');
   requireValue(headers['content-type']?.split(';')[0]==='application/x-www-form-urlencoded');
   if(!issuerRequest){
    report(frameCode);
    requireValue(initialIssuerDocument(target));
    report('BROWSER_CHALLENGE_ISSUER_RESPONSE');
    const body=await verifiedBody(admittedAuthenticate);
    report('BROWSER_CHALLENGE_ISSUER_BINDING');
    const observed=issuerBinding(body,approved?approvedIssuerOrigin:undefined);
    report('BROWSER_CHALLENGE_ISSUER_TRANSACTION');
    const inner=target.parentFrame()!;
    const parentDocument=inner.url()==='about:blank'?inner.parentFrame()!.url():inner.url();
    requireValue(headers.origin===new URL(parentDocument).origin && matchingChallengeRequest(observed,url.href,data));
    await current();
    report('BROWSER_CHALLENGE_ISSUER_TRANSACTION');
    requireValue(!issuerRequest);issuerRequest=request;issuer=target;binding=observed;
    expectedIssuerRevision=revision(target)+1;issuerParent={target,frames:new Map(original.frames)};issuerParent.frames.delete(target);
    await forwardOriginal();issuerReady.resolve();return;
   }
   report('BROWSER_CHALLENGE_ISSUER_SUBMISSION');
   const armed=arm;
   requireValue(armed);sameChain(armed.document,original);
   requireValue(target===issuer && target.url()===binding?.url && url.href===armed.action && headers.origin===url.origin &&
    typeof data==='string' && data.length<=65536 && createHash('sha256').update(data).digest('hex')===armed.bodyHash);
   await billingBounded(limits,()=>armed.gesture,1000);
   await current();
   report('BROWSER_CHALLENGE_ISSUER_SUBMISSION');
   requireValue(!submission);submission=request;
  }
  await forwardOriginal();
  if(!api)issuerDispatched.resolve();
 }
 async function attend(){
  await billingBounded(limits,()=>issuerReady.promise,45000);
  requireValue(issuer && binding && issuerRequest);
  const frame=issuer,bound=binding;
  await frame.waitForURL(url=>url.href===bound.url,{timeout:Math.min(45000,limits.deadline-Date.now())});
  const original=committedIssuerDocument(frame);
  const button=frame.getByRole('button',{name:'Complete',exact:true});await button.waitFor({state:'visible',timeout:Math.min(45000,limits.deadline-Date.now())});
  requireValue(await button.count()===1);
  const element=await button.elementHandle();requireValue(element && await element.ownerFrame()===frame);
  const getForm=()=>element.evaluate(async node=>{
   if(!(node instanceof HTMLButtonElement || node instanceof HTMLInputElement) || !node.form || node.disabled ||
     !node.isConnected || !node.getClientRects().length || document.hidden)return null;
   if((node.getAttribute('formmethod')??node.form.method).toLowerCase()!=='post')return null;
   const target=node.getAttribute('formtarget')??node.form.target;if(target!==''&&target!=='_self')return null;
   if((node.getAttribute('formenctype')??node.form.enctype)!=='application/x-www-form-urlencoded')return null;
   const fields=new URLSearchParams();
   for(const [name,value] of new FormData(node.form,node)){
    if(typeof value!=='string')return null;
    fields.append(name.replace(/\r\n|[\r\n]/g,'\r\n'),value.replace(/\r\n|[\r\n]/g,'\r\n'));
   }
   const data=fields.toString();if(data.length>65536)return null;
   const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(data));
   const bodyHash=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
   return {action:node.hasAttribute('formaction')?node.formAction:node.form.action,bodyHash};
  });
  const form=await getForm();requireValue(form);
  const action=exactUrl(form.action);requireValue(action.origin===new URL(bound.url).origin);
  const validate=async()=>{
   await check();sameChain(original,stamped(frameChain(frame)));
   const current=await getForm();
   requireValue(frame.url()===bound.url && current?.action===action.href && current.bodyHash===form.bodyHash && await element.isVisible() && await element.isEnabled());
  };
  handoffStarted=true;
  // Let the route's fixed checkpoint reach the owner on failure, while preventing handoff.
  await Promise.all(passiveBlocks.values()).catch(()=>billingBounded(limits,()=>new Promise<never>(()=>{}),750));
  await validate();await billingBounded(limits,l=>waitForGesture({deadline:l.deadline,signal:l.signal!}),180000);await validate();
  // A pending form navigation can block evaluation in the issuer renderer.
  // Keep the trusted click receipt in the runner, tied to the armed document.
  const nonce=randomUUID(),gesture=Promise.withResolvers<void>(),name='__billingIssuerComplete';
  const armed={document:original,action:action.href,bodyHash:form.bodyHash,gesture:gesture.promise};
  await page.exposeBinding(name,(source,receipt:unknown)=>{
   if(arm!==armed || source.page!==page || source.frame!==frame || receipt!==nonce)return;
   try{sameChain(original,stamped(frameChain(frame)));}catch{return;}
   gesture.resolve();
  });
  await validate();
  await element.evaluate((node,{name,nonce})=>{
   const send=(window as unknown as Record<string,(receipt:string)=>Promise<void>>)[name];
   if(typeof send!=='function')throw Error('billing_BROWSER');
   node.addEventListener('click',event=>{if(event.isTrusted)void send(nonce).catch(()=>{});},{capture:true,once:true});
  },{name,nonce});
  arm=armed;
  await validate();billingCheck(limits);await element.click({timeout:Math.min(5000,limits.deadline-Date.now()),noWaitAfter:true});
  // Acknowledgement requires the issuer's own 2xx response to the original trusted form submission.
  await billingBounded(limits,async l=>{
   await issuerDispatched.promise;
   billingCheck(l);
   requireValue(await issuerResponded(l,45000));
  },45000);
 }
 return {owns,forward,attend,refusedIssuerRelation};
}
