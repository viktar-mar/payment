import type {Page} from '@playwright/test';
// @ts-ignore Offline tests execute the same Chromium target guard source.
import {guardIframeTargets,type GuardSession} from './stripe-billing-chromium-iframe.ts';

type Header={name:string;value:string};
const redirectStatuses=new Set([301,302,303,307,308]);
export const billingContractBrowserArgs=['--disable-blink-features=SharedWorker'] as const;

function permitsResponse(status:number|undefined,headers:Header[]){
 if(headers.some(({name})=>name.toLowerCase()==='refresh'))return false;
 return status===undefined||status<300||status>=400||
  !redirectStatuses.has(status)&&!headers.some(({name})=>name.toLowerCase()==='location');
}

/** Playwright routes the first hop. Chromium blocks any redirect that could bypass that check. */
export async function guardBillingContractRedirects(page:Page,onFailure:(code:string)=>void,signal?:AbortSignal):Promise<void>{
 let failed=false;
 function fail(code:string){
  if(failed||signal?.aborted)return;
  failed=true;
  onFailure(code);
 }
 async function arm(session:GuardSession){
  let closed=false;
  session.on('close',()=>{closed=true;});
  session.on('Fetch.requestPaused',async event=>{
   if(closed)return;
   let reason='BROWSER_GUARD_RESPONSE_REDIRECT';
   try{
    if(failed || signal?.aborted || !permitsResponse(event.responseStatusCode,event.responseHeaders??[])){
     throw Error('billing_BROWSER');
    }
    reason='BROWSER_GUARD_RESPONSE_RESUME';
    await session.send('Fetch.continueResponse',{requestId:event.requestId});
   }catch{
    // Fail closed before resuming the renderer. The owner also retires the context.
    const blocked=session.send('Fetch.failRequest',{requestId:event.requestId,errorReason:'BlockedByClient'}).catch(()=>{});
    if(!closed)fail(reason);
    await blocked;
   }
  });
  await session.send('Fetch.enable',{patterns:[
   {urlPattern:'http://*',requestStage:'Response'},{urlPattern:'https://*',requestStage:'Response'},
  ]});
 }
 try{
  if(signal?.aborted || !(await page.evaluate(()=>typeof SharedWorker==='undefined')) || signal?.aborted){
   throw Error('billing_BROWSER');
  }
  const session=await page.context().newCDPSession(page);
  await arm(session);
  const targets=await guardIframeTargets(session,arm,()=>fail('BROWSER_GUARD_IFRAME_SETUP'));
  if(failed || signal?.aborted || !targets.active())throw Error('billing_BROWSER');
 }catch{
  fail('BROWSER_GUARD_IFRAME_SETUP');
  throw Error('billing_BROWSER');
 }
}
