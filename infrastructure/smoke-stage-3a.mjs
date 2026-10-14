import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { loadSmokeEnv } from './smoke-env.mjs';

// All credentials and mail content stay in memory. The only output is a fixed safe result.
const env=await loadSmokeEnv(process.env,()=>readFile(new URL('./.env',import.meta.url),'utf8'));
const base=env.PAYMENT_SMOKE_BACKEND_URL??`http://127.0.0.1:${env.PAYMENT_BACKEND_HOST_PORT??8080}`;
const mailpit=env.PAYMENT_SMOKE_MAILPIT_URL??`http://127.0.0.1:${env.PAYMENT_MAILPIT_HOST_PORT??28025}`;
const origin=env.PAYMENT_SMOKE_ORIGIN??'http://127.0.0.1:4173';
const siteKeys=[env.PAYMENT_SMOKE_SITE_KEY??'stage3a-smoke-one',env.PAYMENT_SMOKE_SECOND_SITE_KEY??'stage3a-smoke-two'];
const known=new Set();let cookie='',csrf='';
const check=value=>{if(!value)throw new Error('verification_failed');};
async function json(response) {
  check(Number(response.headers.get('content-length')??0)<=262144);
  const reader=response.body?.getReader();if(!reader)return null;
  const parts=[];let count=0;
  try {for(;;){const part=await reader.read();if(part.done)break;count+=part.value.byteLength;
    if(count>262144){await reader.cancel();throw new Error('response_limit');}parts.push(part.value);}}
  finally {reader.releaseLock();}
  return count?JSON.parse(Buffer.concat(parts).toString('utf8')):null;
}
async function request(path,{method='GET',body,headers={},expected=200,admin=false,target=base}={}) {
  const response=await fetch(target+path,{method,redirect:'error',signal:AbortSignal.timeout(10000),headers:{
    Accept:'application/json','X-Request-Id':'stage3a-smoke',...(body!==undefined?{'Content-Type':'application/json'}:{}),
    ...(admin?{Cookie:cookie,...(method!=='GET'?{'X-XSRF-TOKEN':csrf,'Idempotency-Key':randomUUID()}:{})}:{}),...headers},
    ...(body!==undefined?{body:JSON.stringify(body)}:{})});
  check(response.status===expected);return {response,value:await json(response)};
}
async function wait(label,operation,timeout=15000) {
  const deadline=Date.now()+timeout;
  do {const result=await operation();if(result)return result;await new Promise(resolve=>setTimeout(resolve,200));}while(Date.now()<deadline);
  throw new Error(label);
}
try {
  check(siteKeys[0]!==siteKeys[1]&&siteKeys.every(k=>/^[a-z0-9][a-z0-9-]{2,63}$/.test(k)));
  const allowlist=(env.PAYMENT_TELEMETRY_SITEKEYS??'').split(',').map(x=>x.trim());
  check(siteKeys.every(k=>allowlist.includes(k)));
  for(const name of ['PAYMENT_ADMIN_BOOTSTRAP_PASSWORD','PAYMENT_DB_PASSWORD','PAYMENT_DATA_ENCRYPTION_KEY','PAYMENT_CUSTOMER_DIGEST_KEY_V1','PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1'])if(env[name])known.add(env[name]);
  await wait('readiness',async()=>{try{return (await request('/actuator/health/readiness')).value.status==='UP';}catch{return false;}},30000);
  const login=await request('/api/admin/v1/auth/sessions',{method:'POST',expected:204,body:{username:env.PAYMENT_ADMIN_BOOTSTRAP_USERNAME,password:env.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD}});
  const cookies=login.response.headers.getSetCookie();
  const sessionCookie=cookies.find(c=>c.startsWith('PAYMENT_ADMIN_SESSION='))?.split(';')[0];
  const csrfCookie=cookies.find(c=>c.startsWith('XSRF-TOKEN='))?.split(';')[0];
  check(sessionCookie&&csrfCookie);cookie=sessionCookie+'; '+csrfCookie;csrf=csrfCookie.slice(11);known.add(sessionCookie.slice(22));known.add(csrf);
  const adminGet=async path=>(await request(path,{admin:true})).value;
  const post=async(path,body,expected=201)=>(await request(path,{method:'POST',body,expected,admin:true})).value;
  const existing=await adminGet('/api/admin/v1/sites');
  const sites=[];
  for(const key of siteKeys) {
    let site=existing.find(s=>s.key===key);
    if(!site) {
      const suffix=randomUUID().slice(0,8);
      const account=await post('/api/admin/v1/payment-accounts',{key:'s3a-'+suffix,provider:'FAKE',mode:'SANDBOX',external_account_id:'local-smoke',credentials_ref:'local-reference'});
      site=await post('/api/admin/v1/sites',{key,display_name:'Stage 3A local smoke',payment_account_id:account.id,allowed_origins:[origin],authentication_modes:['SITE_TOKEN','OIDC_TOKEN','EMAIL_OTP'],payment_method_policy:{mode:'AUTOMATIC',methods:[]}});
    }
    check(site.allowed_origins.includes(origin)&&site.authentication_modes.includes('EMAIL_OTP'));
    const providerPath=`/api/v1/admin/sites/${site.id}/identity-providers`;
    const providers=await adminGet(providerPath);
    if(!providers.some(p=>p.mode==='EMAIL_OTP'&&p.status==='ACTIVE'))await post(providerPath,{provider_key:'local-email',mode:'EMAIL_OTP',sender_ref:'local-default',template_key:'otp-default'});
    sites.push(site);
  }
  const site=sites[0];const suffix=randomUUID().slice(0,8);
  const product=await post(`/api/admin/v1/sites/${site.id}/products`,{key:'s3a-'+suffix,name:'Stage 3A',description:'Local integration smoke'});
  const price=await post(`/api/admin/v1/products/${product.id}/prices`,{price_key:'s3a-'+suffix,type:'ONE_TIME',amount_minor:4999,currency:'EUR',trial_days:0,tax_mode:'INCLUSIVE'});
  await post(`/api/admin/v1/prices/${price.id}/publish`,{},200);
  const email=`stage3a-${suffix}@example.test`;known.add(email);
  const challenge=(await request('/api/v1/public/customer-auth/email-otp/challenges',{method:'POST',expected:202,headers:{Origin:origin},body:{site_key:site.key,email,intent:'AUTHENTICATE'}})).value;
  const code=await wait('mail_delivery',async()=>{
    const listing=(await request('/api/v1/messages',{target:mailpit})).value;
    const message=listing.messages.find(m=>m.To?.some(t=>t.Address===email));if(!message)return null;
    const body=(await request(`/api/v1/message/${message.ID}`,{target:mailpit})).value;
    return /\b([0-9]{6})\b/.exec(body.Text??'')?.[1];
  });known.add(code);
  const authenticated=(await request(`/api/v1/public/customer-auth/email-otp/challenges/${challenge.challenge_id}/verify`,{method:'POST',headers:{Origin:origin},body:{site_key:site.key,code}})).value;
  const token=authenticated.session_token;known.add(token);check(authenticated.customer.status==='ACTIVE');
  const customerHeaders={Origin:origin,Authorization:'Bearer '+token};
  check((await request('/api/v1/customer/me',{headers:customerHeaders})).value.id===authenticated.customer.id);
  await request(`/api/v1/public/sites/${sites[1].key}/checkouts`,{method:'POST',expected:401,headers:{...customerHeaders,'Idempotency-Key':randomUUID()},body:{price_key:price.price_key}});
  const checkout=(await request(`/api/v1/public/sites/${site.key}/checkouts`,{method:'POST',expected:201,headers:{...customerHeaders,'Idempotency-Key':randomUUID()},body:{price_key:price.price_key}})).value;
  known.add(checkout.checkout_token);
  const checkoutHeaders={Origin:origin,Authorization:'Bearer '+checkout.checkout_token,'Idempotency-Key':randomUUID()};
  await request(`/api/v1/public/checkouts/${checkout.checkout_id}/confirm`,{method:'POST',headers:checkoutHeaders,body:{payment_method_family:'CARD'}});
  const history=(await request('/api/v1/customer/payments?limit=20',{headers:customerHeaders})).value;
  check(history.items.some(p=>p.payment_id===checkout.payment_id&&p.amount_minor===4999&&p.currency==='EUR'));
  await request('/api/v1/customer/session',{method:'DELETE',expected:204,headers:customerHeaders});
  await request('/api/v1/customer/me',{expected:401,headers:customerHeaders});
  const paymentPath=`/api/v1/admin/payments/${checkout.payment_id}`;
  const before=(await adminGet(paymentPath)).payment;
  const customerPath=`/api/v1/admin/sites/${site.id}/customers/${authenticated.customer.id}`;
  await post(customerPath+'/anonymization',{confirmation:authenticated.customer.id},200);
  check((await adminGet(customerPath)).status==='ANONYMIZED');
  const after=(await adminGet(paymentPath)).payment;
  check(after.payment_id===checkout.payment_id&&after.site_id===site.id&&after.amount_minor===4999&&after.currency==='EUR');
  check(JSON.stringify(before)===JSON.stringify(after));
  console.log(JSON.stringify({result:'PASS',sites:2,mailpit_delivery:true,owned_payment:true,history:true,logout_rejected:true,anonymization_retained_financial_facts:true}));
} catch {console.error(JSON.stringify({result:'FAIL',reason:'stage3a_smoke_verification_failed'}));process.exitCode=1;}
