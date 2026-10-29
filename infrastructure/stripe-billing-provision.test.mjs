import test from 'node:test';
import assert from 'node:assert/strict';
const api=await import('./stripe-billing-provision.mjs').catch(()=>({}));
test('Billing fixture factory does not provision at construction',async()=>{
 assert.equal(typeof api.createBillingFixture,'function');let calls=0;
 const fixture=await api.createBillingFixture({configuration:{runId:'11111111-2222-4333-8444-555555555555',ports:{backend:18080,demo:18081,receiver:18082,mailpitHttp:18025}},bootstrap:{verify(){calls++;throw null;}}});
 assert.equal(calls,0);assert.equal(fixture.resources().customerId,undefined);await assert.rejects(()=>fixture.beforeDispatch('confirm','key',{}, {deadline:Date.now()+1000}));
});
test('fresh recurring provisioning uses real request shapes and persists original financial keys before ACK',async()=>{
 const c={runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),ports:{backend:18080,demo:18081,receiver:18082,mailpitHttp:18025},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR'}},ids=[1,2,3,4,5,6,7,8].map(n=>`${String(n).padStart(8,'0')}-2222-4333-8444-555555555555`),writes=[],requests=[];
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
  const response=new Response(status===204?null:JSON.stringify(value),{status});if(headers['set-cookie'])Object.defineProperty(response.headers,'getSetCookie',{value:()=>headers['set-cookie']});return response;
 };
 const fixture=await api.createBillingFixture({configuration:c,bootstrap:{verify:async()=>true,withAdminCredentials:async callback=>callback({username:'owned',password:'secret-sentinel'})},cli:{validateBinding:async()=>true}},{fetch,write:async(name,value)=>writes.push({name,value})});
 await fixture.provision({deadline:Date.now()+10000});assert.equal(requests.filter(r=>r.path.endsWith('/prices'))[0].body.type,'SUBSCRIPTION');assert.equal(requests.find(r=>r.path.endsWith('/subscription-policy')&&r.method==='POST').headers['Idempotency-Key'].startsWith('billing:'),true);
 await fixture.acknowledgeCustomer({customerId:ids[4],siteId:ids[1]},{deadline:Date.now()+1000});
 await fixture.beforeDispatch('create','sdk-original-create',undefined,{deadline:Date.now()+1000});assert.equal(writes.at(-1).value.key,'sdk-original-create');
 await fixture.acknowledge('create',{subscriptionId:ids[5],operationId:ids[6]},{deadline:Date.now()+1000});
 await fixture.beforeDispatch('confirm','sdk-original-confirm',{subscriptionId:ids[5],operationId:ids[6],attemptId:ids[7]},{deadline:Date.now()+1000});
 await fixture.acknowledge('confirm',{attemptId:ids[7],state:'OBSERVE',nextActionAllowed:false},{deadline:Date.now()+1000});
 assert.ok(!JSON.stringify(writes).includes('secret-sentinel'));assert.ok(!requests.some(r=>/fake|checkout|donation|clock/.test(r.path)));assert.equal(requests.some(r=>r.path==='/api/v1/customer/subscriptions'),false);
});
