import {createHash,randomUUID} from 'node:crypto';
import type {Frame,Page,Request,Route} from '@playwright/test';
import type {BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';

function requireValue(value:unknown):asserts value {if(!value)throw Error('billing_BROWSER');}
function object(value:unknown):Record<string,unknown>{
 requireValue(value && typeof value==='object' && !Array.isArray(value));return value as Record<string,unknown>;
}
const uuid=(v:unknown)=>typeof v==='string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(v);
const issuerOrigin='https://hooks.stripe.com';
function exactUrl(value:unknown){
 requireValue(typeof value==='string' && value.length<=4096);
 const url=new URL(value);requireValue(url.href===value && !url.username && !url.password && !url.hash && url.pathname!=='/');
 return url;
}
export function challengeSource(raw:unknown,secret:string):string{
 const intent=object(raw),action=object(intent.next_action),sdk=object(action.use_stripe_sdk);
 requireValue(intent.object==='payment_intent' && intent.livemode===false && intent.id===secret.split('_secret_')[0] &&
  intent.client_secret===secret && intent.status==='requires_action' && action.type==='use_stripe_sdk' &&
  sdk.type==='stripe_3ds2_fingerprint' && typeof sdk.three_d_secure_2_source==='string' && /^src_[A-Za-z0-9]{1,200}$/.test(sdk.three_d_secure_2_source));
 return sdk.three_d_secure_2_source;
}
export function issuerBinding(raw:unknown){
 const result=object(raw),ares=object(result.ares);
 requireValue(result.state==='challenge_required' && ares.messageType==='ARes' && ares.transStatus==='C' &&
  typeof ares.messageVersion==='string' && /^2\.\d\.\d$/.test(ares.messageVersion) && uuid(ares.threeDSServerTransID) &&
  uuid(ares.acsTransID) && ares.acsTransID!==ares.threeDSServerTransID);
 const url=exactUrl(ares.acsURL);requireValue(url.origin===issuerOrigin);
 return Object.freeze({url:url.href,version:ares.messageVersion,acs:ares.acsTransID,server:ares.threeDSServerTransID});
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
 stripeFrames:(req:Request)=>FrameSnapshot|undefined;waitForGesture:WaitForIssuerGesture;
};
function sameChain(a:FrameSnapshot|undefined,b:FrameSnapshot|undefined){
 requireValue(a && b && a.target===b.target && a.frames.size===b.frames.size && [...a.frames].every(([f,url])=>b.frames.get(f)===url));
}

/** Scoped to one original v2 session. Only Stripe.js sends requests and only the issuer UI completes the challenge. */
export function initial3dsChallenge({page,limits,demo,owner,winning,authorizeResponse,check,stripeFrames,waitForGesture}:Options){
 let retrieve:Request|undefined,authenticate:Request|undefined,completed=false,finalRead=false,boundSource:string|undefined;
 let issuer:Frame|undefined,issuerRequest:Request|undefined,submission:Request|undefined,binding:ReturnType<typeof issuerBinding>|undefined;
 let arm:{document:FrameSnapshot;action:string;bodyHash:string;gesture:Promise<void>}|undefined;
 const issuerReady=Promise.withResolvers<void>(),navigation=new WeakMap<Frame,number>();
 const revision=(frame:Frame)=>navigation.get(frame)??0;
 page.on('framenavigated',frame=>navigation.set(frame,revision(frame)+1));
 function stamped(chain:FrameSnapshot|undefined){
  if(!chain)return;
  return {target:chain.target,frames:new Map([...chain.frames].map(([frame,url])=>[frame,revision(frame)+' '+url]))};
 }
 let expectedIssuerRevision=-1,issuerParent:FrameSnapshot|undefined;

 function frameChain(target:Frame):FrameSnapshot{
  const inner=target.parentFrame(),outer=inner?.parentFrame(),main=page.mainFrame();
  requireValue(inner && outer && outer.parentFrame()===main && target.name()==='acsFrame' && inner.name()==='stripe-challenge-frame' &&
   /^__privateStripeFrame/.test(outer.name()) && /^https:\/\/js\.stripe\.com\/v3\/three-ds-2-challenge-[A-Za-z0-9_.-]+(?:[?#].*)?$/.test(outer.url()) &&
   (inner.url()==='about:blank' || new URL(inner.url()).origin==='https://js.stripe.com') && new URL(main.url()).origin===demo);
  const frames=new Map<Frame,string>();for(const f of [target,inner,outer,main]){
   requireValue(f.page()===page && !f.isDetached());frames.set(f,f.url());
  }
  return {target,frames};
 }
 function issuerResponded(){
  const response=submission?.existingResponse();if(!response)return false;
  requireValue(response.request()===submission && response.url()===submission!.url() && !response.fromServiceWorker() && response.status()>=200 && response.status()<300);
  return true;
 }
 async function verifiedBody(request:Request|undefined){
  requireValue(request);
  // Frame code can use a response before the Playwright client has observed it.
  const response=request.existingResponse()??await billingBounded(limits,()=>request.response(),1000);
  requireValue(response && response.request()===request && response.url()===request.url() && !response.fromServiceWorker() && response.status()===200);
  const bytes=await billingBounded(limits,()=>response.body());requireValue(bytes.length<=65536);
  return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)) as unknown;
 }
 const owns=(url:URL)=>url.origin===issuerOrigin || url.origin==='https://api.stripe.com' &&
  (url.pathname.startsWith('/v1/payment_intents/') || url.pathname.startsWith('/v1/3ds2/'));
 async function forward(route:Route,url:URL,report:(code:string)=>void){
  report('BROWSER_CHALLENGE_REQUEST');
  const request=route.request(),target=request.frame(),api=url.origin==='https://api.stripe.com',navigation=request.isNavigationRequest();
  requireValue(owns(url) && !request.redirectedFrom() && url.href===request.url() && !url.username && !url.password && !url.hash);
  const frameCode=api?'BROWSER_CHALLENGE_API_FRAME':'BROWSER_CHALLENGE_ISSUER_FRAME';
  report(frameCode);
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
      path==='/v1/3ds2/challenge_complete' && issuerResponded() && !completed));
    await current();await forwardOriginal();return;
   }
   if(!isRetrieve && !boundSource){
    report('BROWSER_CHALLENGE_SOURCE_RESPONSE');
    const body=await verifiedBody(retrieve);
    report('BROWSER_CHALLENGE_SOURCE_WIRE');
    boundSource=challengeSource(body,secret);
   }
   report('BROWSER_CHALLENGE_API_REQUEST');
   const kind=challengeApiRequest(url,method,data,key,secret,boundSource);
   requireValue(headers.authorization==='Bearer '+key || (isRetrieve?url.searchParams:new URLSearchParams(data!)).get('key')===key);
   await current();
   report('BROWSER_CHALLENGE_API_ORDER');
   if(kind==='retrieve'){
    if(retrieve){requireValue(issuerResponded() && !finalRead);finalRead=true;}else retrieve=request;
   }else if(kind==='authenticate'){requireValue(!authenticate);authenticate=request;}
   else{
    requireValue(issuerResponded());await current();
    report('BROWSER_CHALLENGE_API_ORDER');
    requireValue(!completed);completed=true;
   }
  }else{
   report('BROWSER_CHALLENGE_ISSUER_REQUEST');
   requireValue(navigation && method==='POST' && request.resourceType()==='document' &&
    headers['content-type']?.split(';')[0]==='application/x-www-form-urlencoded');
   if(!issuerRequest){
    report('BROWSER_CHALLENGE_ISSUER_RESPONSE');
    const body=await verifiedBody(authenticate);
    report('BROWSER_CHALLENGE_ISSUER_BINDING');
    const observed=issuerBinding(body);
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
   requireValue(target===issuer && target.url()===binding?.url && url.href===armed.action && headers.origin===issuerOrigin &&
    typeof data==='string' && data.length<=65536 && createHash('sha256').update(data).digest('hex')===armed.bodyHash);
   await billingBounded(limits,()=>armed.gesture,1000);
   await current();
   report('BROWSER_CHALLENGE_ISSUER_SUBMISSION');
   requireValue(!submission);submission=request;
  }
  await forwardOriginal();
 }
 async function attend(){
  await billingBounded(limits,()=>issuerReady.promise,45000);
  requireValue(issuer && binding && issuerRequest);
  const frame=issuer,bound=binding;
  await frame.waitForURL(url=>url.href===bound.url,{timeout:Math.min(45000,limits.deadline-Date.now())});
  const html=issuerRequest.existingResponse();requireValue(html && html.request()===issuerRequest && html.status()===200 && html.url()===bound.url && !html.fromServiceWorker());
  const original=stamped(frameChain(frame))!;requireValue(revision(frame)===expectedIssuerRevision);
  const parents={target:frame,frames:new Map(original.frames)};parents.frames.delete(frame);sameChain(issuerParent,parents);
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
  const action=exactUrl(form.action);requireValue(action.origin===issuerOrigin);
  const validate=async()=>{
   await check();sameChain(original,stamped(frameChain(frame)));
   const current=await getForm();
   requireValue(frame.url()===bound.url && current?.action===action.href && current.bodyHash===form.bodyHash && await element.isVisible() && await element.isEnabled());
  };
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
  await validate();await element.click({timeout:Math.min(5000,limits.deadline-Date.now()),noWaitAfter:true});
  // Acknowledgement requires the issuer's own 2xx response to the original trusted form submission.
  await billingBounded(limits,async l=>{while(!issuerResponded()){billingCheck(l);await new Promise(r=>setTimeout(r,50));}},45000);
 }
 return {owns,forward,attend};
}
