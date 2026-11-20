import {domainToASCII,domainToUnicode} from 'node:url';
// Internal process-local provenance. Never inspect a rejected value or attach payloads.
const codes=new Set(['BROWSER_CONTEXT','BROWSER_PAGE','BROWSER_SETUP','BROWSER_NAVIGATION','BROWSER_CONFIGURE','BROWSER_OTP_REQUEST','BROWSER_OTP_VERIFY','BROWSER_PREPARE','BROWSER_ACTION_BRIDGE','BROWSER_SESSION_BRIDGE','BROWSER_CONFIRM_BRIDGE','BROWSER_WAIT_READY','BROWSER_START_CLICK','BROWSER_READY_PHASE','BROWSER_READY_BUTTON','BROWSER_INITIAL_ACTION','BROWSER_INITIAL_SESSION','BROWSER_INITIAL_MOUNT','BROWSER_METHOD_MOUNT','BROWSER_PROVIDER_ROUTE','BROWSER_ASSET_ROUTE','BROWSER_CARD_NUMBER','BROWSER_CARD_EXPIRY','BROWSER_CARD_CVC','BROWSER_POSTAL_CHECK','BROWSER_POSTAL_FILL','BROWSER_PAY_CLICK','BROWSER_MANUAL_PAY_ARM','BROWSER_MANUAL_PAY_WAIT','BROWSER_WAIT_OBSERVE','BROWSER_WAIT_OBSERVE_MARKER_MISSING','BROWSER_WAIT_OBSERVE_PAY_SIGNAL_OBSERVED_MARKER_MISSING','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_SUBMIT','BROWSER_WAIT_OBSERVE_SUBMIT_CLEARED','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_TOKEN','BROWSER_WAIT_OBSERVE_TOKEN_CLEARED','BROWSER_WAIT_OBSERVE_MARKER_INVALID','BROWSER_CLOSE','BROWSER_CONFIRM_GUARD','BROWSER_CONFIRM_STATE','BROWSER_CONFIRM_SUBMIT_VALIDATION','BROWSER_CONFIRM_SUBMIT','BROWSER_CONFIRM_TOKEN_VALIDATION','BROWSER_CONFIRM_TOKEN','BROWSER_CONFIRM_CLIENT','BROWSER_CONFIRM_NEXT_ACTION','BROWSER_CONFIRM_RETIRED','BROWSER_CONFIRM_RECEIPT']);
const metadata=new WeakMap();
const providerOrigins=new WeakMap();
for(const code of ['BROWSER_PROVIDER_HEADERS','BROWSER_PROVIDER_CURRENT','BROWSER_PROVIDER_REDIRECT','BROWSER_PROVIDER_USERINFO','BROWSER_PROVIDER_AUTH','BROWSER_PROVIDER_ORIGIN_SCHEME','BROWSER_PROVIDER_ORIGIN_PORT','BROWSER_PROVIDER_ORIGIN_STRIPE_COM','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_R','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK','BROWSER_PROVIDER_ORIGIN_OTHER','BROWSER_PROVIDER_NAVIGATION','BROWSER_PROVIDER_FALLBACK'])codes.add(code);
for(const code of [
 'BROWSER_PROVIDER_STATIC_FRAME',
 'BROWSER_PROVIDER_STATIC_REQUEST',
 'BROWSER_PROVIDER_STATIC_REQUEST_PHASE',
 'BROWSER_PROVIDER_STATIC_REQUEST_METHOD',
 'BROWSER_PROVIDER_STATIC_REQUEST_BODY',
 'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION',
 'BROWSER_PROVIDER_STATIC_REQUEST_URL',
 'BROWSER_PROVIDER_STATIC_REQUEST_CREDENTIALS',
 'BROWSER_PROVIDER_STATIC_REQUEST_RESOURCE_TYPE',
 'BROWSER_PROVIDER_STATIC_REQUEST_SUFFIX',
 'BROWSER_PROVIDER_STATIC_RESPONSE',
])codes.add(code);
export const isBrowserDiagnostic=code=>typeof code==='string'&&codes.has(code);
export function browserFailure(code){const error=new Error('first_payment_BROWSER');if(isBrowserDiagnostic(code))metadata.set(error,{code,cleanup:false});return error;}
export const browserDiagnostic=error=>metadata.get(error)?.code??null;
export const browserCleanupConfirmed=error=>metadata.get(error)?.cleanup===true;
export function finishBrowserCleanup(error,confirmed){const value=metadata.get(error);if(value)value.cleanup=confirmed===true;}

const stripeComOther='BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER';
const generalOther='BROWSER_PROVIDER_ORIGIN_OTHER';
function providerOriginIdentity(runId,expectedSource,origin,checkpoint){
 if(typeof runId!=='string'||runId.length!==36||!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(runId)||
    typeof expectedSource!=='string'||expectedSource.length!==40||!/^[0-9a-f]{40}$/.test(expectedSource)||
    typeof origin!=='string'||origin.length>262||!origin.startsWith('https://'))return false;
 if(checkpoint===generalOther){
  // Preserve the browser's canonical host, including a final dot or IPv6 brackets.
  // Equality excludes credentials, paths and query/fragment data from the private file.
  try{
   const url=new URL(origin);
   return url.origin===origin && !url.port &&
     url.hostname!=='stripe.com' && !url.hostname.endsWith('.stripe.com') &&
     url.hostname!=='stripe.network' && !url.hostname.endsWith('.stripe.network');
  }catch{return false;}
 }
 if(checkpoint!==stripeComOther)return false;
 const hostname=origin.slice(8);
 if(hostname.length>253||/[^a-z0-9.-]/.test(hostname)||
    !(hostname==='stripe.com'||hostname.endsWith('.stripe.com'))||
    ['api.stripe.com','hooks.stripe.com','js.stripe.com','m.stripe.com','r.stripe.com','merchant-ui-api.stripe.com'].includes(hostname)||hostname.endsWith('.js.stripe.com'))return false;
 if(!hostname.split('.').every(label=>label.length>0&&label.length<=63&&/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label)))return false;
 try{
  for(const label of hostname.split('.')){
   if(!label.startsWith('xn--'))continue;
   const unicode=domainToUnicode(label);
   if(!unicode||!/[^\x00-\x7f]/.test(unicode)||domainToASCII(unicode)!==label)return false;
  }
  return new URL(origin).origin===origin;
 }catch{return false;}
}
export function isBrowserProviderOriginWitness(value){
 try{
  if(!value||typeof value!=='object'||Object.getPrototypeOf(value)!==Object.prototype)return false;
  const keys=Reflect.ownKeys(value),tagged=keys.includes('checkpoint');
  const names=tagged?['runId','expectedSource','origin','checkpoint']:['runId','expectedSource','origin'];
  if(keys.length!==names.length)return false;
  const fields=Object.create(null);
  for(const name of names){
   const descriptor=Object.getOwnPropertyDescriptor(value,name);
   if(!descriptor?.enumerable||!Object.hasOwn(descriptor,'value'))return false;
   fields[name]=descriptor.value;
  }
  if(tagged&&fields.checkpoint!==generalOther)return false;
  return providerOriginIdentity(fields.runId,fields.expectedSource,fields.origin,tagged?generalOther:stripeComOther);
 }catch{return false;}
}
export function rememberBrowserProviderOrigin(error,runId,expectedSource,origin){
 const checkpoint=metadata.get(error)?.code;
 if((checkpoint!==generalOther&&checkpoint!==stripeComOther)||providerOrigins.has(error)||!providerOriginIdentity(runId,expectedSource,origin,checkpoint))return false;
 const witness={runId,expectedSource,origin};
 if(checkpoint===generalOther)witness.checkpoint=checkpoint;
 providerOrigins.set(error,Object.freeze(witness));return true;
}
export const browserProviderOrigin=error=>providerOrigins.get(error)??null;
