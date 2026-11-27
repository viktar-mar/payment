import {createHash,randomUUID} from 'node:crypto';
import type {Frame,Page,Request,Response,Route} from '@playwright/test';
import type {BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import type {RefusedIssuerRelation} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
// @ts-ignore Native Node executes the same inert parser as Playwright.
import {issuerResultForm} from './stripe-billing-initial-3ds-result-form.ts';

function requireValue(value:unknown):asserts value {if(!value)throw Error('billing_BROWSER');}
function object(value:unknown):Record<string,unknown>{
 requireValue(value && typeof value==='object' && !Array.isArray(value));return value as Record<string,unknown>;
}
const uuid=(v:unknown)=>typeof v==='string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v);
const issuerOrigin='https://hooks.stripe.com';
const directIssuerFrame='stripe-challenge-frame';
const reservedStripeHosts=new Set(['api','hooks','js','m','r','merchant-ui-api']);
const issuerAssetBlockLimit=6;
const issuerScriptLimit=6;
export function isIssuerScriptPath(pathname:string):boolean{
 return /^\/(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*\/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\.js$/.test(pathname);
}
export function issuerAssetSuffixMatches(kind:string,pathname:string):boolean{
 if(kind==='image')return /\.(?:png|jpe?g|gif|webp|ico)$/.test(pathname);
 if(kind==='font')return /\.woff2?$/.test(pathname);
 return kind==='stylesheet'&&/\.css$/.test(pathname);
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
// Correlation only. Transport also requires the original form and paused route.
function matchingChallengeResult(binding:ReturnType<typeof issuerBinding>|undefined,body:string|null){
 try{
  if(!binding||!body||body.length>32768)return false;
  const fields=new URLSearchParams(body),results=fields.getAll('cres');
  if(results.length!==1 || [...fields.keys()].some(key=>key!=='cres'&&key!=='threeDSSessionData') ||
   fields.getAll('threeDSSessionData').length>1 || !/^[A-Za-z0-9_-]+={0,2}$/.test(results[0]!))return false;
  const encoded=results[0]!;
  const bytes=Buffer.from(encoded,'base64url');
  if(bytes.toString('base64url')!==encoded.replace(/=+$/,'') || encoded.endsWith('=')&&encoded.length%4!==0)return false;
  const text=new TextDecoder('utf-8',{fatal:true}).decode(bytes);
  const result=object(JSON.parse(text));
  let depth=0,members=0;
  for(const [token,key] of text.matchAll(/("(?:[^"\\]|\\.)*")\s*:|"(?:[^"\\]|\\.)*"|[{}\[\]]/gs)){
   if(key&&depth===1)members++;
   else if(token==='{'||token==='[')depth++;
   else if(token==='}'||token===']')depth--;
  }
  if(members!==Object.keys(result).length)return false;
  return result.messageType==='CRes' && result.messageVersion===binding.version &&
   result.threeDSServerTransID===binding.server && result.acsTransID===binding.acs &&
   ['Y','N','D'].some(status=>result.transStatus===status);
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
 authorizeIssuerScript?:(req:Request,document:Request)=>Promise<void>;
 authorizeIssuerDecision?:(req:Request)=>Promise<void>;
 takeIssuerDecisionBody?:(req:Request)=>Uint8Array|undefined;
 authorizeIssuerResult?:(req:Request,document:Request)=>Promise<void>;
 stripeFrames:(req:Request)=>FrameSnapshot|undefined;waitForGesture:WaitForIssuerGesture;
 approvedIssuerOrigin?:string;
};
function sameChain(a:FrameSnapshot|undefined,b:FrameSnapshot|undefined){
 requireValue(a && b && a.target===b.target && a.frames.size===b.frames.size && [...a.frames].every(([f,url])=>b.frames.get(f)===url));
}

/** Scoped to one original v2 session. Only Stripe.js sends requests and only the issuer UI completes the challenge. */
export function initial3dsChallenge({page,limits,demo,owner,winning,authorizeResponse,expectIssuerAssetBlock,authorizeIssuerScript,authorizeIssuerDecision,takeIssuerDecisionBody,authorizeIssuerResult,check,stripeFrames,waitForGesture,approvedIssuerOrigin}:Options){
 requireValue(approvedIssuerOrigin===undefined||isApprovedIssuerOrigin(approvedIssuerOrigin));
 let retrieve:Request|undefined,authenticate:Request|undefined,admittedAuthenticate:Request|undefined;
 let completed=false,finalRead=false,initialIssuerDispatched=false,boundSource:string|undefined;
 let issuer:Frame|undefined,issuerRequest:Request|undefined,submission:Request|undefined,binding:ReturnType<typeof issuerBinding>|undefined;
 let issuerResult:{request:Request;state:'pending'|'forwarded'|'verified'}|undefined;
 let arm:{document:FrameSnapshot;action:string;bodyHash:string;gesture:Promise<void>}|undefined;
 let handoffStarted=false;
 const issuerAssets=()=>({
  blocks:new Map<string,Promise<void>>(),scripts:new Map<string,Promise<void>>(),deadline:undefined as number|undefined,
 });
 const challengeAssets=issuerAssets(),decisionAssets=issuerAssets();
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
 function issuerDocumentSnapshot(frame:Frame,request:Request|undefined){
  requireValue(binding && request && frame===issuer);
  const challengeDocument=request===issuerRequest;
  const documentUrl=challengeDocument?binding.url:request.url();
  requireValue((challengeDocument || request===submission) && frame.url()===documentUrl);
  const document=stamped(frameChain(frame))!;
  requireValue(revision(frame)===expectedIssuerRevision+(challengeDocument?0:1));
  const parents={target:frame,frames:new Map(document.frames)};parents.frames.delete(frame);sameChain(issuerParent,parents);
  return document;
 }
 function committedIssuerDocument(frame:Frame,request=issuerRequest,html=request?.existingResponse()){
  requireValue(request && html && html.request()===request && html.status()===200 && html.url()===request.url() && !html.fromServiceWorker());
  return issuerDocumentSnapshot(frame,request);
 }
 async function issuerResponded(active:BillingLimits,maximum=1000){
  const request=submission;
  if(!request)return false;
  const response=await originalResponse(request,active,maximum);
  requireValue(response.status()>=200 && response.status()<300);
  return true;
 }
 async function issuerCompletionReady(){
  if(!await issuerResponded(limits))return false;
  if(issuerResult){
   if(issuerResult.state==='pending')return false;
   if(issuerResult.state==='forwarded'){
    requireValue((await originalResponse(issuerResult.request,limits,1000)).status()===200);
    issuerResult.state='verified';
   }
  }
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
 async function verifiedIssuerResultForm(request:Request,response:Response,report:(code:string)=>void){
  return billingBounded(limits,async active=>{
   // This receipt comes from the exact paired Chromium response pause with
   // its MIME, identity encoding and byte length already verified.
   report('BROWSER_CHALLENGE_CRES_DECISION_READ');
   let bytes=takeIssuerDecisionBody?.(request);
   if(!bytes){
    report('BROWSER_CHALLENGE_CRES_DECISION_HEADERS');
    const [type,encoding]=await Promise.all([response.headerValue('content-type'),response.headerValue('content-encoding')]);
    billingCheck(active);
    report('BROWSER_CHALLENGE_CRES_DECISION_MEDIA');
    requireValue(type && /^text\/html(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?$/i.test(type.trim()));
    report('BROWSER_CHALLENGE_CRES_DECISION_ENCODING');
    requireValue(encoding===null||encoding.trim().toLowerCase()==='identity');
    report('BROWSER_CHALLENGE_CRES_DECISION_SIZE');
    const {responseBodySize}=await request.sizes();
    billingCheck(active);
    requireValue(Number.isSafeInteger(responseBodySize)&&responseBodySize>0&&responseBodySize<=65536);
    report('BROWSER_CHALLENGE_CRES_DECISION_READ');
    bytes=await response.body();
   }
   billingCheck(active);
   report('BROWSER_CHALLENGE_CRES_DECISION_SIZE');
   requireValue(bytes.length>0&&bytes.length<=65536);
   report('BROWSER_CHALLENGE_CRES_DECISION_UTF8');
   const html=new TextDecoder('utf-8',{fatal:true}).decode(bytes);
   report('BROWSER_CHALLENGE_CRES_FORM');
   const form=issuerResultForm(html);
   requireValue(form);
   return form;
  },2000);
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
  const decisionForResult=submission && url.origin===issuerOrigin && navigation &&
   request.method()==='POST' && request.resourceType()==='document' ? submission : undefined;
  const observedResultBody=decisionForResult?request.postData():null;
  if(decisionForResult && matchingChallengeResult(binding,observedResultBody)){
   report('BROWSER_CHALLENGE_ISSUER_SUBMISSION_DOCUMENT');
   sameChain(original,issuerDocumentSnapshot(target,decisionForResult));
   report('BROWSER_CHALLENGE_CRES_RESERVATION');
   billingCheck(limits);
   // Reserve before awaiting headers or response bytes so SDK completion sees the pending POST.
   report('BROWSER_CHALLENGE_CRES_REPLAY');
   requireValue(!issuerResult);
   report('BROWSER_CHALLENGE_CRES_ORDER');
   requireValue(!completed && !finalRead);
   issuerResult={request,state:'pending'};
  }
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
  let issuerCompletionRoute=false;
  const requireIssuerResult=()=>requireValue(!issuerResult||issuerResult.state==='verified');
  const forwardOriginal=async(authorize=()=>authorizeResponse(request))=>{
   report('BROWSER_CHALLENGE_ROUTE_GUARD');
   await authorize();
   await current();
   report('BROWSER_CHALLENGE_ROUTE_FORWARD');
   if(issuerCompletionRoute)requireIssuerResult();
   billingCheck(limits);
   if(request===authenticate)admittedAuthenticate=request;
   if(request===issuerRequest)initialIssuerDispatched=true;
   if(request===issuerResult?.request)issuerResult.state='forwarded';
   await route.continue();
  };
  if(api){
   report('BROWSER_CHALLENGE_API_REQUEST');
   requireValue(!navigation && (!headers.origin||headers.origin===new URL(target.url()).origin));
   const path=url.pathname,isRetrieve=path==='/v1/payment_intents/'+secret.split('_secret_')[0];
   if(method==='OPTIONS'){
    report('BROWSER_CHALLENGE_API_PREFLIGHT');
    issuerCompletionRoute=path==='/v1/3ds2/challenge_complete';
    requireValue(!headers.authorization && !data && headers['access-control-request-method']===(isRetrieve?'GET':'POST') &&
     (isRetrieve && (!retrieve || arm && !finalRead) || path==='/v1/3ds2/authenticate' && retrieve && !authenticate ||
      issuerCompletionRoute && await issuerCompletionReady() && !completed));
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
     issuerCompletionRoute=true;
     requireValue(await issuerCompletionReady());await current();
     report('BROWSER_CHALLENGE_API_ORDER');
     requireIssuerResult();
     requireValue(!finalRead);finalRead=true;
    }else retrieve=request;
   }else if(kind==='authenticate'){requireValue(!authenticate);authenticate=request;}
   else{
    issuerCompletionRoute=true;
    requireValue(await issuerCompletionReady());await current();
    report('BROWSER_CHALLENGE_API_ORDER');
    requireIssuerResult();
    requireValue(!completed);completed=true;
   }
  }else{
   if(approved && initialIssuerDispatched && !navigation){
    report('BROWSER_CHALLENGE_ISSUER_ASSET_HANDOFF');
    const decisionDocument=handoffStarted;
    const documentRequest=decisionDocument?submission:issuerRequest;
    requireValue(documentRequest && (!decisionDocument ||
     target.url()===documentRequest.url() && revision(target)===expectedIssuerRevision+1));
    // After the gesture, only the committed reply to the one trusted form POST can load assets.
    const assets=decisionDocument?decisionAssets:challengeAssets;
    report('BROWSER_CHALLENGE_ISSUER_ASSET_BINDING');
    requireValue(binding && new URL(binding.url).origin===url.origin);
    report('BROWSER_CHALLENGE_ISSUER_ASSET_WORKER');
    requireValue(request.serviceWorker()===null);
    report('BROWSER_CHALLENGE_ISSUER_ASSET_ORIGIN');
    requireValue(!headers.origin||headers.origin===url.origin);
    const requireIssuerDocument=()=>{
     report('BROWSER_CHALLENGE_ISSUER_ASSET_DOCUMENT');
     sameChain(original,committedIssuerDocument(target,documentRequest));
    };
    requireIssuerDocument();
    report('BROWSER_CHALLENGE_ISSUER_ASSET_METHOD');
    requireValue(method==='GET' && data===null && !headers['transfer-encoding'] &&
     (!headers['content-length']||headers['content-length']==='0'));
    report('BROWSER_CHALLENGE_ISSUER_ASSET_PATH');
    requireValue(url.href===url.origin+url.pathname && !url.pathname.includes('%'));
    report('BROWSER_CHALLENGE_ISSUER_ASSET_KIND');
    const kind=request.resourceType();
    if(kind==='script'){
     report('BROWSER_CHALLENGE_ISSUER_ASSET_SCRIPT');
     requireValue(authorizeIssuerScript && binding && isIssuerScriptPath(url.pathname) &&
      !assets.scripts.has(url.pathname) && assets.scripts.size<issuerScriptLimit);
     const forwarded=billingBounded(limits,async active=>{
      await authorizeIssuerScript(request,documentRequest);
      await check();billingCheck(active);requireIssuerDocument();
      report('BROWSER_CHALLENGE_ROUTE_FORWARD');
      await route.continue();
      const response=await originalResponse(request,active,5000);
      requireValue(response.status()===200 &&
       await billingBounded(active,()=>response.finished(),5000)===null && request.failure()===null);
      await check();billingCheck(active);
      // Stripe.js may retire the issuer after the successfully loaded decision script posts back.
      if(!decisionDocument)requireIssuerDocument();
     },10000);
     assets.scripts.set(url.pathname,forwarded);
     return await forwarded;
    }
    switch(kind){
     case 'image': report('BROWSER_CHALLENGE_ISSUER_ASSET_IMAGE_SUFFIX');break;
     case 'font': report('BROWSER_CHALLENGE_ISSUER_ASSET_FONT_SUFFIX');break;
     case 'stylesheet': report('BROWSER_CHALLENGE_ISSUER_ASSET_STYLESHEET_SUFFIX');break;
     case 'xhr': report('BROWSER_CHALLENGE_ISSUER_ASSET_XHR');break;
     case 'fetch': report('BROWSER_CHALLENGE_ISSUER_ASSET_FETCH');break;
     default: report('BROWSER_CHALLENGE_ISSUER_ASSET_OTHER');
    }
    requireValue(issuerAssetSuffixMatches(kind,url.pathname));
    report('BROWSER_CHALLENGE_ISSUER_ASSET_REPEAT');
    requireValue(!assets.blocks.has(url.pathname));
    report('BROWSER_CHALLENGE_ISSUER_ASSET_LIMIT');
    requireValue(assets.blocks.size<issuerAssetBlockLimit);
    // All claims share the first request's deadline. A late request cannot
    // start a new proof window or inherit another request's receipt.
    assets.deadline??=Math.min(limits.deadline,Date.now()+750);
    const blockLimits={deadline:assets.deadline,signal:limits.signal};
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
    assets.blocks.set(url.pathname,block);
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
   if(submission){
    report('BROWSER_CHALLENGE_ISSUER_SUBMISSION_DOCUMENT');
    const decision=submission;
    let decisionResponse:Response|undefined;
    if(issuerResult?.request===request){
     report('BROWSER_CHALLENGE_CRES_DECISION_RESPONSE');
     decisionResponse=await originalResponse(decision,limits,1000);
     report('BROWSER_CHALLENGE_ISSUER_SUBMISSION_DOCUMENT');
    }
    sameChain(original,committedIssuerDocument(target,decision,decisionResponse));
    requireValue(headers.origin===new URL(decision.url()).origin);
    const repeat=url.href===decision.url() && typeof data==='string' &&
     data.length<=65536 && createHash('sha256').update(data).digest('hex')===arm?.bodyHash;
    report(repeat?'BROWSER_CHALLENGE_ISSUER_SUBMISSION_REPEAT':'BROWSER_CHALLENGE_ISSUER_SUBMISSION_FOLLOWUP');
    requireValue(!repeat && matchingChallengeResult(binding,data));
    report('BROWSER_CHALLENGE_CRES_DESTINATION');
    requireValue(url.origin===issuerOrigin);
    report('BROWSER_CHALLENGE_CRES_REQUEST');
    requireValue(authorizeIssuerResult && decisionResponse && issuerResult?.request===request && data===observedResultBody &&
     request.serviceWorker()===null && !headers['transfer-encoding']);
    const form=await verifiedIssuerResultForm(decision,decisionResponse,report);
    report('BROWSER_CHALLENGE_CRES_ACTION');
    requireValue(form.action===url.href);
    report('BROWSER_CHALLENGE_CRES_BODY');
    requireValue(request.postDataBuffer()?.equals(Buffer.from(form.body)));
    await current();
    report('BROWSER_CHALLENGE_ISSUER_SUBMISSION_DOCUMENT');
    sameChain(original,committedIssuerDocument(target,decision,decisionResponse));
    report('BROWSER_CHALLENGE_CRES_ORDER');
    requireValue(!completed && !finalRead);
    await forwardOriginal(()=>authorizeIssuerResult(request,decision));
    return;
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
  await forwardOriginal(!api && request===submission && authorizeIssuerDecision?()=>authorizeIssuerDecision(request):undefined);
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
  await Promise.all([...challengeAssets.blocks.values(),...challengeAssets.scripts.values()])
   .catch(()=>billingBounded(limits,()=>new Promise<never>(()=>{}),750));
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
