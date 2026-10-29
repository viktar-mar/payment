import type { Stripe, StripeElements, StripePaymentElement } from '@stripe/stripe-js';
import { browserOrigin, validateCustomerSession } from '../customer/contracts.js';
import { PaymentSdkError, isAbort } from '../errors.js';
import { loadStripeClient } from '../providers/stripe-loader.js';
import { subscribeSubscriptionCustomer } from '../customer/session-lifecycle.js';
import { captureSubscriptionAuthority } from './authority.js';
import { assertPaymentSession } from './confirmation-v2.js';
import { visibleSubscriptionHost } from './stripe-subscription-driver.js';
import type { SubscriptionAttemptStatus, SubscriptionClient, SubscriptionIdRequest, SubscriptionPaymentSession } from './contracts.js';
export interface StripePaymentAttemptHandle { readonly protocol:'SERVER_CONFIRM_V2'; confirm():Promise<SubscriptionAttemptStatus|{validationFailed:true}>; destroy():void; }
export async function mountStripePaymentAttempt(options:{client:SubscriptionClient;input:SubscriptionIdRequest&{operationId:string};context:SubscriptionPaymentSession;container:HTMLElement;isCurrent:()=>boolean;locale?:'en'|'pt';onRetire?:()=>void}):Promise<StripePaymentAttemptHandle>{
 const {client,input,container,isCurrent,onRetire}=options;
 let context:SubscriptionPaymentSession|undefined=options.context;
 let assertAuthority:(()=>void)|undefined=captureSubscriptionAuthority(client,input.session,input.signal);
 const expires=Math.min(Date.parse(input.session.expiresAt),Date.parse(context.expiresAt));
 let stripe:Stripe|undefined,elements:StripeElements|undefined,element:StripePaymentElement|undefined;
 let active=true,ready=false,busy=false,dispatched=false;
 let expiry:ReturnType<typeof setTimeout>|undefined,readinessTimer:ReturnType<typeof setTimeout>|undefined,releaseCustomer:(()=>void)|undefined;
 let readyListener:(()=>void)|undefined,loadErrorListener:(()=>void)|undefined;
 const aborted=()=>new DOMException('Aborted','AbortError');let cancel!:(error:DOMException)=>void;
 const cancelled=new Promise<never>((_,reject)=>{cancel=reject;});void cancelled.catch(()=>{});
 const observer=new MutationObserver(()=>{if(active){try{current();}catch{destroy();}}});
 function destroy():void{
  const wasActive=active;active=false;ready=false;if(expiry!==undefined)clearTimeout(expiry);if(readinessTimer!==undefined)clearTimeout(readinessTimer);
  input.signal?.removeEventListener('abort',destroy);window.removeEventListener('pagehide',destroy);document.removeEventListener('visibilitychange',visibility);observer.disconnect();releaseCustomer?.();releaseCustomer=undefined;assertAuthority=undefined;
  try{if(readyListener)element?.off('ready',readyListener);if(loadErrorListener)element?.off('loaderror',loadErrorListener);}catch{/* sanitized cleanup */}
  const retired=element;element=undefined;elements=undefined;stripe=undefined;context=undefined;
  try{retired?.destroy();}catch{/* sanitized cleanup */}
  if(wasActive){cancel(aborted());try{onRetire?.();}catch{/* cannot revive */}}
 }
 function current():void{if(!active||input.signal?.aborted||!isCurrent()||!visibleSubscriptionHost(container)||document.visibilityState==='hidden')throw aborted();assertAuthority!();validateCustomerSession(input.session);if(input.session.origin!==browserOrigin()||Date.now()>=expires||!input.session.scopes.includes('SUBSCRIPTION_MANAGE'))throw new PaymentSdkError('INVALID_SESSION');}
 function visibility():void{if(document.visibilityState==='hidden')destroy();}
 async function guarded<T>(pending:Promise<T>):Promise<T>{const result=await Promise.race([pending,cancelled]);current();return result;}
 try{
  current();assertPaymentSession(client,input,context);
  input.signal?.addEventListener('abort',destroy,{once:true});window.addEventListener('pagehide',destroy,{once:true});document.addEventListener('visibilitychange',visibility);releaseCustomer=subscribeSubscriptionCustomer(input.session,destroy);
  const observation={childList:true,subtree:true,attributes:true,attributeFilter:['hidden','style','class','name','slot']};observer.observe(container.ownerDocument,observation);const projection=container.assignedSlot?.getRootNode();if(projection instanceof ShadowRoot)observer.observe(projection,observation);
  expiry=setTimeout(destroy,Math.max(0,Math.min(expires-Date.now(),2147483647)));
  const loaded=await guarded(loadStripeClient(context.publishableKey));if(!loaded)throw new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE');stripe=loaded;
  elements=stripe.elements({clientSecret:context.clientSecret,locale:options.locale??'en'});current();element=elements.create('payment',{wallets:{applePay:'never',googlePay:'never',link:'never'}});
  const readiness=new Promise<void>((resolve,reject)=>{readyListener=()=>{try{current();ready=true;resolve();}catch{reject(aborted());}};loadErrorListener=()=>reject(new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE'));readinessTimer=setTimeout(()=>reject(new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE')),30000);});void readiness.catch(()=>{});
  element.on('ready',readyListener!);element.on('loaderror',loadErrorListener!);current();element.mount(container);await guarded(readiness);clearTimeout(readinessTimer);
  return {protocol:'SERVER_CONFIRM_V2',destroy,async confirm(){
   if(busy||dispatched)throw new PaymentSdkError('PAYMENT_STATE_CONFLICT');busy=true;
   let validationFailed=false;
   try{
    current();if(!ready||!context)throw new PaymentSdkError('PAYMENT_STATE_CONFLICT');assertPaymentSession(client,input,context);
    const submitted=await guarded(elements!.submit());if(submitted.error){validationFailed=true;return {validationFailed:true};}
    const token=await guarded(stripe!.createConfirmationToken({elements:elements!}));if(token.error||!token.confirmationToken){validationFailed=true;return {validationFailed:true};}
    current();assertPaymentSession(client,input,context);dispatched=true;
    const receipt=await guarded(client.confirmPaymentAttempt!({...input,context,confirmationToken:token.confirmationToken.id,idempotencyKey:crypto.randomUUID()}));
    if(receipt.nextActionAllowed===true){current();await guarded(stripe!.handleNextAction({clientSecret:context!.clientSecret}));}
    // Neither a successful challenge nor an OBSERVE receipt establishes settlement.
    return receipt;
   }catch(error){if(isAbort(error)||error instanceof PaymentSdkError)throw error;throw new PaymentSdkError('PROVIDER_RESULT_UNKNOWN');}
   finally{busy=false;if(!validationFailed)destroy();}
  }};
 }catch(error){destroy();if(isAbort(error)||error instanceof PaymentSdkError)throw error;throw new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE');}
}
