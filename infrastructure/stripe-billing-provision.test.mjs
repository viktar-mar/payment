import test from 'node:test';
import assert from 'node:assert/strict';
const api=await import('./stripe-billing-provision.mjs').catch(()=>({}));
test('Billing fixture factory does not provision at construction',async()=>{
 assert.equal(typeof api.createBillingFixture,'function');let calls=0;
 const fixture=await api.createBillingFixture({configuration:{runId:'11111111-2222-4333-8444-555555555555',ports:{backend:18080,demo:18081,receiver:18082,mailpitHttp:18025}},bootstrap:{verify(){calls++;throw null;}}});
 assert.equal(calls,0);assert.equal(fixture.resources().customerId,undefined);await assert.rejects(()=>fixture.beforeDispatch('confirm','key',{}, {deadline:Date.now()+1000}));
});
for(const cancellation of [false,true])test('fresh recurring provisioning persists ordered receipts with cancellation='+cancellation,async()=>{
 const c={runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),ports:{backend:18080,demo:18081,receiver:18082,mailpitHttp:18025},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR'}},ids=[1,2,3,4,5,6,7,8].map(n=>`${String(n).padStart(8,'0')}-2222-4333-8444-555555555555`),writes=[],requests=[];
 if(cancellation)Object.assign(c,{env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:'initial_cancel_withdraw'},
   selectedCase:'initial_cancel_withdraw',daemon:'unix:///Users/test/docker.sock',ports:{...c.ports,postgres:15432,mailpitSmtp:11025},approvedBinding:{...c.approvedBinding,apiVersion:'2026-09-30.endive'}});
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
 if(cancellation){
  const limits={deadline:Date.now()+5000},key='a'.repeat(64),next='b'.repeat(64);
  const opA='10000000-2222-4333-8444-555555555555',opB='20000000-2222-4333-8444-555555555555';
  await assert.rejects(()=>fixture.beforeCancellation('withdraw',next,limits));
  await fixture.beforeCancellation('schedule',key,limits);
  assert.equal(writes.at(-1).value.phase,'DISPATCHING');
  await assert.rejects(()=>fixture.beforeCancellation('schedule',key,limits));
  await fixture.acknowledgeCancellation('schedule',opA,limits);
  await fixture.beforeCancellation('withdraw',next,limits);
  await fixture.acknowledgeCancellation('withdraw',opB,limits);
  assert.equal(writes.at(-1).value.phase,'ACKNOWLEDGED');
  assert.equal(writes.at(-1).value.operationId,opB);
  assert.equal(writes.at(-1).value.subscriptionId,ids[5]);
 }else assert.equal(fixture.beforeCancellation,undefined);
 assert.ok(!JSON.stringify(writes).includes('secret-sentinel'));assert.ok(!requests.some(r=>/fake|checkout|donation|clock/.test(r.path)));assert.equal(requests.some(r=>r.path==='/api/v1/customer/subscriptions'),false);
});

function portalPolicyInputs() {
 const configuration={
  env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'},
  selectedCase:'initial_success',runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
  approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}
 };
 const account={id:'00000001-2222-4333-8444-555555555555',provider:'STRIPE',mode:'SANDBOX',status:'ACTIVE',external_account_id:'acct_1UPiMpELswAd8tpR'};
 const site={id:'00000002-2222-4333-8444-555555555555',key:'s5a-11111111222243338444555555555555',status:'ACTIVE',payment_account_id:account.id,
  allowed_origins:['http://127.0.0.1:18081'],authentication_modes:['EMAIL_OTP']};
 const product={id:'00000003-2222-4333-8444-555555555555',site_id:site.id,status:'ACTIVE'};
 const price={id:'00000004-2222-4333-8444-555555555555',type:'SUBSCRIPTION',site_id:site.id,product_id:product.id,status:'PUBLISHED',
  version:1,amount_minor:2000,currency:'EUR',billing_interval:'MONTH',trial_days:0,tax_mode:'INCLUSIVE',provider_price_id:'price_fresh'};
 const policy={site_id:site.id,version:0,enabled:false,grace_days:3,allow_immediate_cancellation:false,portal_enabled:false,defer_upgrades:false,portal_return_urls:[]};
 return {configuration,receipts:{account,site,product,price,policy}};
}

test('builds the Portal policy request for one exact owned loopback checkout URL',()=>{
 const {configuration,receipts}=portalPolicyInputs();
 const request=api.buildBillingPortalPolicyRequest(configuration,receipts);
 const returnUrl='http://127.0.0.1:18081/?api_base_url=http%3A%2F%2F127.0.0.1%3A18080&site_key=s5a-11111111222243338444555555555555&subscription_product_1_id=00000003-2222-4333-8444-555555555555&subscription_price_1_id=00000004-2222-4333-8444-555555555555&locale=en&subscription_product_2_id=';
 assert.equal(request.method,'POST');
 assert.equal(request.url,'http://127.0.0.1:18080/api/v1/admin/sites/00000002-2222-4333-8444-555555555555/subscription-policy');
 assert.deepEqual(request.body,{version:0,enabled:true,grace_days:0,allow_immediate_cancellation:false,portal_enabled:true,defer_upgrades:false,portal_return_urls:[returnUrl]});
 assert.throws(()=>request.body.portal_return_urls.push('https://foreign.test/'),TypeError);
 assert.deepEqual(request.validateResponse({site_id:receipts.site.id,...request.body,version:1}),{portalReturnUrl:returnUrl,portalPolicyVersion:1});
});

test('requires initial-only admission with distinct owned ports',()=>{
 const {configuration,receipts}=portalPolicyInputs();
 const denied={message:'billing_FIXTURE'};
 assert.throws(()=>api.buildBillingPortalPolicyRequest({...configuration,selectedCase:'initial_cancel_withdraw'},receipts),denied);
 assert.throws(()=>api.buildBillingPortalPolicyRequest({...configuration,ports:{...configuration.ports,demo:18080}},receipts),denied);
});

for(const [receipt,patch] of [
 ['account',{mode:'LIVE'}],
 ['site',{allowed_origins:['http://localhost:18081']}],
 ['site',{payment_account_id:'ffffffff-ffff-ffff-ffff-ffffffffffff'}],
 ['product',{site_id:'ffffffff-ffff-ffff-ffff-ffffffffffff'}],
 ['price',{product_id:'ffffffff-ffff-ffff-ffff-ffffffffffff'}],
 ['price',{status:'DRAFT'}],
 ['policy',{site_id:'ffffffff-ffff-ffff-ffff-ffffffffffff'}],
 ['policy',{version:1}],
])test(`refuses an unowned or altered Portal ${receipt} receipt (${Object.keys(patch)[0]})`,()=>{
 const {configuration,receipts}=portalPolicyInputs();
 assert.throws(()=>api.buildBillingPortalPolicyRequest(configuration,{...receipts,[receipt]:{...receipts[receipt],...patch}}),{message:'billing_FIXTURE'});
});

for(const patch of [
 {site_id:'ffffffff-ffff-ffff-ffff-ffffffffffff'},
 {version:0},
 {portal_enabled:false},
 {allow_immediate_cancellation:true},
 {portal_return_urls:['http://127.0.0.1:18081/']},
])test(`refuses an altered Portal policy acknowledgement (${Object.keys(patch)[0]})`,()=>{
 const {configuration,receipts}=portalPolicyInputs();
 const request=api.buildBillingPortalPolicyRequest(configuration,receipts);
 assert.throws(()=>request.validateResponse({site_id:receipts.site.id,...request.body,version:1,...patch}),{message:'billing_FIXTURE'});
});
