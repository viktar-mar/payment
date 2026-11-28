import {setTimeout as pause} from 'node:timers/promises';
import type {ElementHandle,Frame,Locator,Page} from '@playwright/test';
import type {BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
// @ts-ignore Native Node executes the same origin predicates as Playwright.
import {providerNetworkOrigin} from './stripe-provider-origin.ts';
// @ts-ignore Native Node executes the same reviewed issuer origin validation as Playwright.
import {isApprovedIssuerOrigin,type WaitForIssuerGesture} from './stripe-billing-initial-3ds.ts';

// https://docs.stripe.com/ips#stripe-domains
const paymentHosts=new Set(['m.stripe.com','m.stripe.network','pay.stripe.com','payments.stripe.com']);
const dnsLabel=/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

export function contractChallengeOrigin(url:URL,approvedIssuerOrigin?:string):boolean{
 return url.protocol==='https:' && !url.port && !url.username && !url.password && !url.href.includes('#') &&
  url.hostname.length<=253 && url.hostname.split('.').every(label=>dnsLabel.test(label)) &&
  (providerNetworkOrigin(url) || paymentHosts.has(url.hostname) ||
   url.origin===approvedIssuerOrigin && isApprovedIssuerOrigin(approvedIssuerOrigin));
}

function requireValue(condition:unknown):asserts condition{
 if(!condition)throw Error('billing_BROWSER');
}

type Control={frame:Frame;documentUrl:string;locator:Locator};
type Button=ElementHandle<HTMLElement|SVGElement>;

export async function attendContractChallenge(page:Page,limits:BillingLimits,trustedProvider:(url:URL)=>boolean,
 checkOwner:()=>Promise<void>,waitForGesture:WaitForIssuerGesture):Promise<void>{
 async function findControl(active:BillingLimits):Promise<Control|undefined>{
  let match:Control|undefined;
  for(const frame of page.frames()){
   billingCheck(active);
   if(frame===page.mainFrame() || frame.isDetached() || frame.page()!==page)continue;
   const documentUrl=frame.url();
   if(!URL.canParse(documentUrl))continue;
   const document=new URL(documentUrl);
   if(document.protocol!=='https:' || document.username || document.password || !trustedProvider(new URL(document.origin)))continue;
   const locator=frame.getByRole('button',{name:'Complete',exact:true});
   const count=await locator.count();
   if(frame.isDetached() || frame.url()!==documentUrl)continue;
   if(count){
    requireValue(count===1 && !match);
    match={frame,documentUrl,locator};
   }
  }
  return match;
 }
 async function verifyControl(active:BillingLimits,selected:Control,element:Button){
  await checkOwner();
  const current=await findControl(active);
  requireValue(current && current.frame===selected.frame && current.documentUrl===selected.documentUrl);
  requireValue(await element.ownerFrame()===selected.frame &&
   await current.locator.evaluate((node,expected)=>node===expected,element) &&
   await element.isVisible() && await element.isEnabled());
  billingCheck(active);
 }
 let button:Button|undefined;
 try{
  const selected=await billingBounded(limits,async active=>{
   await checkOwner();
   for(;;){
    const current=await findControl(active);
    if(current && await current.locator.isVisible() && await current.locator.isEnabled())return current;
    await pause(100,undefined,{signal:active.signal});
   }
  },45_000);
  const element=await billingBounded(limits,active=>selected.locator.elementHandle({timeout:active.deadline-Date.now()}),5_000);
  button=element;
  await billingBounded(limits,active=>verifyControl(active,selected,element),5_000);
  await billingBounded(limits,active=>waitForGesture({deadline:active.deadline,signal:active.signal!}),180_000);
  await billingBounded(limits,async active=>{
   await verifyControl(active,selected,element);
   // Playwright activates the exact selected element. Stripe.js handles everything after the gesture.
   await element.click({timeout:active.deadline-Date.now(),noWaitAfter:true});
  },5_000);
 }catch{
  throw Error('billing_BROWSER');
 }finally{if(button)await button.dispose().catch(()=>{});}
}
