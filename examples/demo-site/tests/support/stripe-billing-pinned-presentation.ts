import type {Page,Frame,Request,Route} from '@playwright/test';
import type {BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
// @ts-ignore Explicit source for the offline Node tests.
import {providerOrigin} from './stripe-provider-origin.ts';

function need(ok:unknown):asserts ok {if(!ok)throw Error('billing_BROWSER');}

export const staticAssets=[
  {resource:'image',suffix:'.png',media:['image/png']},
  {resource:'image',suffix:'.svg',media:['image/svg+xml']},
  {resource:'stylesheet',suffix:'.css',media:['text/css']},
  {resource:'script',suffix:'.js',media:['text/javascript','application/javascript']},
];
// Check the original authority before URL parsing can normalize malformed input.
export const plainHttpsAuthority=/^https:\/\/[A-Za-z0-9.-]+(?::443)?(?:[/?#]|$)/i;
function isStripeScriptDocument(href:string):boolean {
  const url=new URL(href);
  return providerOrigin(url) && (url.hostname==='js.stripe.com'||url.hostname.endsWith('.js.stripe.com'));
}
export function staticNavigationTarget(request:Request,page:Page) {
  // Keep this sample descriptive. Playwright exposes the destination frame,
  // which can still be unassociated, and does not identify the initiator.
  try {if(!request.isNavigationRequest())return null;}
  catch {return null;}
  try {
    const frame=request.frame(),main=page.mainFrame();
    if(frame.page()!==page || frame.isDetached())return 'FRAME_UNAVAILABLE';
    if(frame===main)return 'MAIN_FRAME';
    const parent=frame.parentFrame();
    if(!parent || parent.page()!==page || parent.isDetached())return 'FRAME_UNAVAILABLE';
    if(parent===main)return 'CHECKOUT_CHILD';
    return isStripeScriptDocument(parent.url())?'STRIPE_PARENT':'OTHER_PARENT';
  }catch{return 'FRAME_UNAVAILABLE';}
}
type FrameIssue='LOOKUP'|'MAIN'|'FOREIGN_PAGE'|'DETACHED'|'CHECKOUT'|'PARENT'|'CYCLE'|'UNCOMMITTED'|'DOCUMENT'|'CHANGED';
type ReportFrameIssue=(issue:FrameIssue)=>void;
function rejectFrame(report:ReportFrameIssue|undefined,issue:FrameIssue):undefined {
  report?.(issue);
  return undefined;
}
export function stripeFrameChain(page:Page,start:()=>Frame|null,report?:ReportFrameIssue) {
  try{
    const chain:{frame:Frame;href:string}[]=[],main=page.mainFrame();
    const visited=new Set<Frame>();
    for(let frame=start();frame!==main;frame=frame.parentFrame()){
      if(!frame)return rejectFrame(report,'PARENT');
      if(visited.has(frame))return rejectFrame(report,'CYCLE');
      if(frame.page()!==page)return rejectFrame(report,'FOREIGN_PAGE');
      if(frame.isDetached())return rejectFrame(report,'DETACHED');
      const href=frame.url();
      if(href==='')return rejectFrame(report,'UNCOMMITTED');
      if(!isStripeScriptDocument(href))return rejectFrame(report,'DOCUMENT');
      visited.add(frame);
      chain.push({frame,href});
    }
    return chain.length?chain:rejectFrame(report,'MAIN');
  }catch{return rejectFrame(report,'LOOKUP');}
}
export function stripeNavigationChain(request:Request,page:Page,checkoutUrl:string,report?:ReportFrameIssue) {
  try{
    const target=request.frame(),main=page.mainFrame();
    if(target===main)return rejectFrame(report,'MAIN');
    if(target.page()!==page || main.page()!==page)return rejectFrame(report,'FOREIGN_PAGE');
    if(target.isDetached() || main.isDetached())return rejectFrame(report,'DETACHED');
    if(main.url()!==checkoutUrl)return rejectFrame(report,'CHECKOUT');
    const parent=target.parentFrame();
    if(!parent || parent===main)return rejectFrame(report,'PARENT');
    const parents=stripeFrameChain(page,()=>parent,report);
    if(parents)return {target,main,parents};
  }catch{return rejectFrame(report,'LOOKUP');}
}
export function sameStripeFrames(before:NonNullable<ReturnType<typeof stripeFrameChain>>,after:ReturnType<typeof stripeFrameChain>) {
  return after && after.length===before.length &&
    after.every(({frame,href},i)=>frame===before[i]!.frame && href===before[i]!.href);
}
type StaticAssetCheck='STATUS'|'MEDIA'|'CORS'|'BODY';
export async function fetchStaticAsset(url:URL,media:string[],origin:string|undefined,signal:AbortSignal,
 checkpoint?:(check:StaticAssetCheck)=>void) {
  const controller=new AbortController();
  try {
    const response=await fetch(url,{method:'GET',redirect:'error',credentials:'omit',referrerPolicy:'no-referrer',
      headers:origin?{Origin:origin}:undefined,signal:AbortSignal.any([signal,controller.signal])});
    checkpoint?.('STATUS');
    need(response.status===200 && !response.redirected);
    checkpoint?.('MEDIA');
    const type=response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase();
    need(type && media.includes(type));
    checkpoint?.('BODY');
    need(response.body);
    // Playwright can add CORS headers on fulfill when ACAO is absent.
    // Never fulfill a CORS request the upstream response did not authorize.
    checkpoint?.('CORS');
    const allowedOrigin=response.headers.get('access-control-allow-origin');
    need(!origin || allowedOrigin===origin || allowedOrigin==='*');
    checkpoint?.('BODY');
    const maxBytes=1024*1024;
    const reader=response.body.getReader(),chunks:Uint8Array[]=[];
    let size=0;
    while(true){
      const {value,done}=await reader.read();
      if(done)break;
      size+=value.byteLength;need(size<=maxBytes);chunks.push(value);
    }
    need(size>0);
    const headers:Record<string,string>={'Content-Type':response.headers.get('content-type')!,
      'X-Content-Type-Options':'nosniff','Cache-Control':'no-store'};
    for(const name of ['access-control-allow-origin','access-control-allow-credentials','cross-origin-resource-policy',
      'cross-origin-embedder-policy','content-security-policy','content-security-policy-report-only','content-disposition','referrer-policy']){
      const header=response.headers.get(name);if(header!==null)headers[name]=header;
    }
    return {headers,body:Buffer.concat(chunks,size)};
  } finally {controller.abort();}
}

type PresentationPhase='initial'|'successor';
type PendingPresentation={phase:PresentationPhase;ready:Promise<void>};
type FrameStage='NAV_CAPTURE'|'NAV_BLOCK'|'ASSET_CAPTURE'|'ASSET_FETCH'|'ASSET_FULFILL';

/** The one local block can only occur on the first form, before its Pay gesture. */
export function pinnedRetryPresentation(page:Page,checkout:()=>string,limits:BillingLimits,
 form:()=>PresentationPhase|undefined,beforePay:()=>boolean,
 pendingForm:(kind:'document'|'asset')=>PendingPresentation|undefined,onFailure:(code:string)=>void){
 const pending=new Set<Promise<void>>();
 const navigations=new WeakMap<Frame,number>();
 // Playwright can retain the same Frame and URL across document navigations.
 page.on('framenavigated',frame=>navigations.set(frame,(navigations.get(frame)??0)+1));
 const captureDocumentVersions=(frames:Frame[])=>{
  const versions=frames.map(frame=>[frame,navigations.get(frame)] as const);
  return ()=>versions.every(([frame,version])=>navigations.get(frame)===version);
 };
 let used=false,block:{request:Request;confirm:()=>void}|undefined;
 page.on('requestfailed',request=>{
  if(block && request===block.request && form()==='initial' && beforePay() &&
    request.failure()?.errorText==='net::ERR_BLOCKED_BY_CLIENT')block.confirm();
 });
 async function handle(route:Route,url:URL,checkpoint:(code:string)=>void){
  const request=route.request(),phaseAtRequest=form(),raw=request.url();
  const navigation=request.isNavigationRequest();
  const frameCheck=(stage:FrameStage)=>(issue:FrameIssue)=>checkpoint('BROWSER_PINNED_FRAME_'+stage+'_'+issue);
  checkpoint('BROWSER_PINNED_PHASE');
  const staged=phaseAtRequest===undefined && (!navigation || request.resourceType()==='document')
   ?pendingForm(navigation?'document':'asset'):undefined;
  const phase=phaseAtRequest??staged?.phase;
  need(phase);
  checkpoint('BROWSER_PINNED_METHOD');need(request.method()==='GET');
  checkpoint('BROWSER_PINNED_BODY');need(request.postData()===null);
  checkpoint('BROWSER_PINNED_AUTHORITY');
  need(plainHttpsAuthority.test(raw) && url.protocol==='https:' && !url.username && !url.password && !url.port);
  checkpoint('BROWSER_PINNED_REDIRECT');need(!request.redirectedFrom());
  const capture=frameCheck(navigation?'NAV_CAPTURE':'ASSET_CAPTURE');
  capture('LOOKUP');
  const originalNavigation=navigation?stripeNavigationChain(request,page,checkout(),capture):undefined;
  const frames=navigation?undefined:stripeFrameChain(page,()=>request.frame(),capture);
  const originalChain=originalNavigation?.parents??frames;
  need(originalChain);
  const originalMain=originalNavigation?.main??page.mainFrame();
  const owners=[originalMain,...originalChain.map(({frame})=>frame)];
  if(originalNavigation)owners.push(originalNavigation.target);
  const sameDocuments=captureDocumentVersions(owners);
  checkpoint('BROWSER_PINNED_HEADERS');
  const headers=await request.allHeaders();
  checkpoint('BROWSER_PINNED_CURRENT');
  billingCheck(limits);need(page.mainFrame().url()===checkout());
  checkpoint('BROWSER_PINNED_CREDENTIALS');
  need(!Object.keys(headers).some(key=>['authorization','proxy-authorization','cookie'].includes(key.toLowerCase())));
  // The SDK can mount after receiving the session while the independent
  // response-body read is still pending. Do not relay or block until that
  // exact session has passed validation. Retain the pre-wait frame snapshot.
  if(staged){
   checkpoint('BROWSER_PINNED_SESSION');
   await billingBounded(limits,()=>staged.ready);
  }
  checkpoint('BROWSER_PINNED_CURRENT');
  billingCheck(limits);need(form()===phase && page.mainFrame().url()===checkout());
  if(navigation){
   checkpoint('BROWSER_PINNED_DOCUMENT_POLICY');
   need(phase==='initial' && beforePay() && !used && request.resourceType()==='document' && originalNavigation);
   used=true;
   const event=Promise.withResolvers<void>();
   checkpoint('BROWSER_PINNED_DOCUMENT_BLOCK');
   try{
    await billingBounded(limits,()=>{
     checkpoint('BROWSER_PINNED_CURRENT');
     need(form()==='initial' && beforePay());
     const recheck=frameCheck('NAV_BLOCK');
     recheck('LOOKUP');
     const current=stripeNavigationChain(request,page,checkout(),recheck);
     need(current);
     recheck('CHANGED');
     need(current.main===originalNavigation.main && current.target===originalNavigation.target &&
      sameStripeFrames(originalNavigation.parents,current.parents) && sameDocuments());
     checkpoint('BROWSER_PINNED_DOCUMENT_BLOCK');
     block={request,confirm:event.resolve};
     return Promise.all([route.abort('blockedbyclient'),event.promise]);
    },Infinity);
    need(page.mainFrame()===originalNavigation.main && page.mainFrame().url()===checkout() && form()==='initial' && beforePay());
   }finally{block=undefined;}
   return;
  }
  checkpoint('BROWSER_PINNED_ASSET_URL');need(url.href===url.origin+url.pathname);
  need(frames);
  checkpoint('BROWSER_PINNED_ASSET_ORIGIN');need(!('origin' in headers)||headers.origin===new URL(frames[0]!.href).origin);
  const resource=request.resourceType();
  checkpoint('BROWSER_PINNED_ASSET_KIND');need(staticAssets.some(a=>a.resource===resource));
  const asset=staticAssets.find(a=>a.resource===resource && url.pathname.endsWith(a.suffix));
  checkpoint('BROWSER_PINNED_ASSET_SUFFIX');
  need(asset);
  const checkFrames=(stage:'ASSET_FETCH'|'ASSET_FULFILL')=>{
   checkpoint('BROWSER_PINNED_CURRENT');
   billingCheck(limits);need(form()===phase && page.mainFrame().url()===checkout());
   const recheck=frameCheck(stage);
   recheck('LOOKUP');
   const current=stripeFrameChain(page,()=>request.frame(),recheck);
   need(current);
   recheck('CHANGED');
   need(page.mainFrame()===originalMain && sameStripeFrames(frames,current) && sameDocuments());
  };
  checkpoint('BROWSER_PINNED_ASSET_TRANSPORT');
  await billingBounded(limits,async bounded=>{
   checkFrames('ASSET_FETCH');
   checkpoint('BROWSER_PINNED_ASSET_TRANSPORT');
   const reply=await fetchStaticAsset(url,asset.media,headers.origin,bounded.signal!,step=>checkpoint('BROWSER_PINNED_ASSET_'+step));
   billingCheck(bounded);checkFrames('ASSET_FULFILL');
   checkpoint('BROWSER_PINNED_ASSET_FULFILL');
   await route.fulfill({...reply,status:200});
  });
 }
 return {
  async forward(route:Route,url:URL){
   let code='BROWSER_PINNED_PRESENTATION';
   const work=handle(route,url,next=>{code=next;}).catch(error=>{
    onFailure(code);
    throw error;
   });
   pending.add(work);
   try{await work;}finally{pending.delete(work);}
  },
  get pending(){return pending.size;},
  wait:()=>billingBounded(limits,()=>Promise.all([...pending]),Infinity),
 };
}
