import {createBillingFailedRenewalFixture} from '../stripe-billing-provision.mjs';
import {id,opts} from './failed-renewal-descriptors.mjs';

// All HTTP responses and receipt writes stay in memory.
export async function provisionHarness({onFetch,onWrite,onVerify,configuration=opts(),factory=createBillingFailedRenewalFixture}={}) {
 const ids=[2,3,6,5,4,7,8,9].map(id),writes=[],requests=[];
 const fetch=async(url,settings)=>{
  const path=new URL(url).pathname,body=settings.body&&JSON.parse(settings.body);requests.push({path,...settings,body});let value=null,status=200,headers={};
  if(path==='/api/admin/v1/auth/sessions'){status=204;headers={'set-cookie':['PAYMENT_ADMIN_SESSION=abc; Path=/','XSRF-TOKEN=def; Path=/']};}
  else if(settings.method==='GET'){
   if(path.endsWith('/payment-accounts')||path.endsWith('/sites'))value=[];
   else if(path.endsWith('/identity-providers'))value=[{mode:'EMAIL_OTP',readiness:'READY',status:'ACTIVE'}];
   else if(path.endsWith('/subscription-policy'))value={version:0};
   else if(path==='/inspection')value={count:0,duplicates:0,events:[]};
   else if(path==='/api/v1/messages')value={total:0,messages:[]};else throw Error('Unexpected read');
  }else if(path.endsWith('/payment-accounts')){status=201;value={...body,id:ids[0],status:'ACTIVE'};}
  else if(path.endsWith('/sites')){status=201;value={...body,id:ids[1],status:'ACTIVE'};}
  else if(path.endsWith('/subscription-policy'))value=body;
  else if(path.endsWith('/products')){status=201;value={...body,id:ids[2],site_id:ids[1],status:'ACTIVE'};}
  else if(path.endsWith('/prices')){status=201;value={...body,id:ids[3],site_id:ids[1],product_id:ids[2],version:1,status:'DRAFT'};}
  else if(path.endsWith('/publish'))value={id:ids[3],type:'SUBSCRIPTION',status:'PUBLISHED',version:1,amount_minor:2000,currency:'EUR',billing_interval:'MONTH',trial_days:0,tax_mode:'INCLUSIVE',provider_price_id:'price_fresh'};
  else if(path.endsWith('/callback'))value={site_id:ids[1],enabled:true,secret_version:1,secret:'A'.repeat(43)};
  else if(path==='/configure')status=204;else throw Error('Unexpected mutation');
  const response=new Response(status===204?null:JSON.stringify(value),{status});if(headers['set-cookie'])Object.defineProperty(response.headers,'getSetCookie',{value:()=>headers['set-cookie']});return onFetch ? onFetch(path,settings,response) : response;
 };
 const fixture=await factory({configuration,bootstrap:{verify:async()=>onVerify?onVerify({writes,requests}):true,withAdminCredentials:async callback=>callback({username:'owned',password:'secret-sentinel'})},cli:{validateBinding:async()=>true}},{fetch,write:async(name,value)=>{writes.push({name,value});await onWrite?.(name,value);}});
 return {fixture,ids,writes,requests};
}
