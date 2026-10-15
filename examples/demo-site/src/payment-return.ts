import {createGenericPaymentClient,recoverCheckout} from '@generic-payments/sdk';
import type {RecoveryIdentity} from '@generic-payments/sdk';

export type ReturnConfiguration=RecoveryIdentity & {locale:string};
const configurationKey='demo:public-return-configuration:v1';
const noSession='No saved checkout session is available. Sign in to inspect payment history or use the original checkout recovery flow.';
export function sanitizeStripeReturnLocation(location:URL):URL {
  const safe=new URL(location.href);safe.search='';safe.hash='';return safe;
}
function valid(value:unknown):value is ReturnConfiguration {
  if(!value || typeof value!=='object')return false;
  const v=value as Record<string,unknown>;
  if(Object.keys(v).length!==4 || !['apiBaseUrl','siteKey','priceKey','locale'].every(k=>typeof v[k]==='string'))return false;
  if(!/^[A-Za-z0-9][A-Za-z0-9-]{2,62}$/.test(v.siteKey as string)||!/^[A-Za-z0-9][A-Za-z0-9-]{2,62}$/.test(v.priceKey as string)
      ||!/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(v.locale as string))return false;
  try {const u=new URL(v.apiBaseUrl as string);return u.origin===v.apiBaseUrl &&
    (u.protocol==='https:' || u.protocol==='http:'&&['127.0.0.1','localhost'].includes(u.hostname));}catch{return false;}
}
/** Public configuration is a recovery hint, never redirect correlation or checkout authority. */
export function rememberReturnConfiguration(configuration:ReturnConfiguration):void {
  const value={apiBaseUrl:configuration.apiBaseUrl,siteKey:configuration.siteKey,priceKey:configuration.priceKey,locale:configuration.locale};
  if(!valid(value))return;
  try {sessionStorage.setItem(configurationKey,JSON.stringify(value));}catch{/* Optional storage. */}
}
function configuration():ReturnConfiguration|undefined {
  try {const raw=sessionStorage.getItem(configurationKey);if(!raw||raw.length>2048)return;
    const value:unknown=JSON.parse(raw);return valid(value)?value:undefined;
  }catch{return undefined;}
}
export function mountPaymentReturn():void {
  const status=document.querySelector<HTMLElement>('#return-status');
  const button=document.querySelector<HTMLButtonElement>('#read-saved-payment');
  if(!status||!button)return;
  const config=configuration();
  const candidate=config?recoverCheckout(config):null;
  if(!config||!candidate){status.textContent=noSession;return;}
  status.textContent='A previously saved catalog checkout is available. It may be unrelated to this return.';
  button.hidden=false;
  const controller=new AbortController();window.addEventListener('pagehide',()=>controller.abort(),{once:true});
  button.addEventListener('click',async()=>{
    if(button.disabled||controller.signal.aborted)return;button.disabled=true;
    try {
      const current=recoverCheckout(config);
      if(!current||current.checkoutId!==candidate.checkoutId||current.checkoutToken!==candidate.checkoutToken
        ||current.state.paymentId!==candidate.state.paymentId||current.providerType!==candidate.providerType)throw new Error('No original session');
      const state=await createGenericPaymentClient({apiBaseUrl:config.apiBaseUrl,signal:controller.signal}).get(current);
      if(!controller.signal.aborted)status.textContent=`Saved catalog payment ${state.paymentId} · ${state.status}. Fulfillment requires trusted server-side status.`;
    }catch {if(!controller.signal.aborted)status.textContent=noSession;}
    finally {if(!controller.signal.aborted)button.disabled=false;}
  });
}
if(typeof document!=='undefined'&&document.querySelector('#return-status'))mountPaymentReturn();
