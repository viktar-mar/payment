import test from 'node:test';
import assert from 'node:assert/strict';
import {billingCallbackDigest,proveBillingEvidence} from './stripe-billing-evidence.mjs';
import {createBillingPortalFlow} from '../examples/demo-site/tests/support/stripe-billing-portal-flow.node.mjs';
const api=await import('./stripe-billing-provision.mjs').catch(()=>({}));
test('Billing fixture factory does not provision at construction',async()=>{
 assert.equal(typeof api.createBillingFixture,'function');let calls=0;
 const fixture=await api.createBillingFixture({configuration:{runId:'11111111-2222-4333-8444-555555555555',ports:{backend:18080,demo:18081,receiver:18082,mailpitHttp:18025}},bootstrap:{verify(){calls++;throw null;}}});
 assert.equal(calls,0);assert.equal(fixture.resources().customerId,undefined);await assert.rejects(()=>fixture.beforeDispatch('confirm','key',{}, {deadline:Date.now()+1000}));
});
test('ordinary provisioning refuses the dedicated optional-trial mode before dependencies',async()=>{
 const dependencies=new Proxy({}, {get(){assert.fail('ordinary provisioning reached dependencies');}});
 for(const configuration of [
  {env:{PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL:'true'}},
  {selectedCase:'optional_trial_pause_resume'},
 ])await assert.rejects(api.createBillingFixture({configuration},dependencies),/billing_FIXTURE/);
});
for(const purpose of ['initial','cancellation','optional-trial'])test('fresh recurring provisioning persists ordered receipts for '+purpose,async()=>{
 const cancellation=purpose==='cancellation',optionalTrial=purpose==='optional-trial';
 const c={runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),ports:{backend:18080,demo:18081,receiver:18082,mailpitHttp:18025},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR'}},ids=[1,2,3,4,5,6,7,8].map(n=>`${String(n).padStart(8,'0')}-2222-4333-8444-555555555555`),writes=[],requests=[];
 if(cancellation)Object.assign(c,{env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:'initial_cancel_withdraw'},
   selectedCase:'initial_cancel_withdraw',daemon:'unix:///Users/test/docker.sock',ports:{...c.ports,postgres:15432,mailpitSmtp:11025},approvedBinding:{...c.approvedBinding,apiVersion:'2026-09-30.endive'}});
 if(optionalTrial)Object.assign(c,{env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL:'true',
   PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID:'88888888-2222-4333-8444-555555555555',PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_SUBNET:'10.94.40.0/28'},
   selectedCase:'optional_trial_pause_resume',daemon:'unix:///Users/test/docker.sock',controlPort:18083,
   ports:{...c.ports,postgres:15432,mailpitSmtp:11025},approvedBinding:{...c.approvedBinding,apiVersion:'2026-09-30.endive'}});
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
  else if(path.endsWith('/publish'))value={id:ids[3],type:'SUBSCRIPTION',status:'PUBLISHED',version:1,amount_minor:2000,currency:'EUR',billing_interval:'MONTH',trial_days:optionalTrial?7:0,
   ...(optionalTrial?{trial_payment_method_policy:'OPTIONAL_DURING_TRIAL'}:{}),tax_mode:'INCLUSIVE',provider_price_id:'price_fresh'};
  else if(path.endsWith('/callback'))value={site_id:ids[1],enabled:true,secret_version:1,secret:'A'.repeat(43)};
  else if(path==='/configure')status=204;else throw Error('Unexpected mutation');
  const response=new Response(status===204?null:JSON.stringify(value),{status});if(headers['set-cookie'])Object.defineProperty(response.headers,'getSetCookie',{value:()=>headers['set-cookie']});return response;
 };
 const create=optionalTrial?api.createBillingOptionalTrialFixture:api.createBillingFixture;
 assert.equal(typeof create,'function');
 const fixture=await create({configuration:c,bootstrap:{verify:async()=>true,withAdminCredentials:async callback=>callback({username:'owned',password:'secret-sentinel'})},
  cli:{validateBinding:async()=>true,assertBillingListener:async()=>true}},{fetch,write:async(name,value)=>writes.push({name,value})});
 await fixture.provision({deadline:Date.now()+10000});assert.equal(requests.filter(r=>r.path.endsWith('/prices'))[0].body.type,'SUBSCRIPTION');assert.equal(requests.find(r=>r.path.endsWith('/subscription-policy')&&r.method==='POST').headers['Idempotency-Key'].startsWith('billing:'),true);
 const ordinaryPolicy=requests.find(r=>r.path.endsWith('/subscription-policy')&&r.method==='POST');
 assert.equal(ordinaryPolicy.body.portal_enabled,false);
 assert.equal(fixture.resources().portalReturnUrl,undefined);
 assert.ok(!writes.some(r=>r.name.includes('portal-policy')));
 await fixture.acknowledgeCustomer({customerId:ids[4],siteId:ids[1]},{deadline:Date.now()+1000});
 if(optionalTrial){
  const price=requests.find(r=>r.path.endsWith('/prices')).body;
  assert.deepEqual(price,{price_key:'billing-price-11111111222243338444555555555555',type:'SUBSCRIPTION',amount_minor:2000,currency:'EUR',
   billing_interval:'MONTH',trial_days:7,trial_payment_method_policy:'OPTIONAL_DURING_TRIAL',tax_mode:'INCLUSIVE',tax_code:null});
  assert.equal(fixture.resources().priceId,ids[3]);assert.equal(fixture.resources().customerId,ids[4]);
  assert.equal(fixture.beforeDispatch,undefined);assert.equal(fixture.acknowledge,undefined);
  await assert.rejects(()=>fixture.provision({deadline:Date.now()+1000}));
  assert.ok(!JSON.stringify(writes).includes('secret-sentinel'));
  return;
 }
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

function portalFixtureInputs(){
 const {configuration,receipts}=portalPolicyInputs();
 return {receipts,configuration:{...configuration,selectedCase:'initial_portal',
  env:{...configuration.env,PAYMENT_STRIPE_BILLING_CASE:'initial_portal',
   PAYMENT_STRIPE_BILLING_PORTAL_CLI_PROFILE_RUN_ID:'22222222-3333-4444-8555-666666666666',
   PAYMENT_STRIPE_BILLING_PORTAL_SUBNET:'10.254.81.0/28'}}};
}

async function portalFixtureHarness(fault){
 const {configuration,receipts}=portalFixtureInputs(),{account,site,product,price,policy}=receipts;
 const events=[],requests=[],writes=[];
 let fixture,callbackInspection;
 const fetch=async(url,settings)=>{
  const path=new URL(url).pathname,method=settings.method,body=settings.body&&JSON.parse(settings.body);
  events.push(method+' '+path);requests.push({path,method,body,headers:settings.headers});
  let status=200,value=null;
  if(path.endsWith('/auth/sessions')){
   const response=new Response(null,{status:204});
   Object.defineProperty(response.headers,'getSetCookie',{value:()=>['PAYMENT_ADMIN_SESSION=privateCookie; Path=/','XSRF-TOKEN=privateCsrf; Path=/']});
   return response;
  }
  if(method==='GET'){
   if(path.endsWith('/payment-accounts')||path.endsWith('/sites'))value=[];
   else if(path.endsWith('/identity-providers'))value=[{mode:'EMAIL_OTP',readiness:'READY',status:'ACTIVE'}];
   else if(path.endsWith('/subscription-policy'))value=fault==='v0-changed'?{...policy,version:1}:policy;
   else if(path==='/inspection')value={count:0,duplicates:0,events:[]};
   else if(path==='/inspection/digests')value=callbackInspection;
   else if(path==='/api/v1/messages')value={total:0,messages:[]};
   else assert.fail('unexpected GET');
  }else if(path.endsWith('/payment-accounts')){status=201;value=account;}
  else if(path.endsWith('/sites')){status=201;value=site;}
  else if(path.endsWith('/products')){status=201;value=product;}
  else if(path.endsWith('/prices')){status=201;value={...price,status:'DRAFT'};}
  else if(path.endsWith('/publish'))value=fault==='foreign-price'?{...price,product_id:site.id}:price;
  else if(path.endsWith('/subscription-policy')){
   if(fault==='uncertain-http'){status=409;value={code:'PRIVATE_RESPONSE'};}
   else value={site_id:site.id,...body,version:fault==='wrong-ack'?2:1};
  }else if(path.endsWith('/callback'))value={site_id:site.id,enabled:true,secret_version:1,secret:'A'.repeat(43)};
  else if(path==='/configure')status=204;
  else assert.fail('unexpected mutation');
  return new Response(status===204?null:JSON.stringify(value),{status});
 };
 const write=async(name,value)=>{
  const kind=name.replace(/^billing-\d{3}-/,'').replace(/\.json$/,'');
  if(kind===fault)throw Error('PRIVATE_WRITE_ERROR');
  writes.push({kind,value});events.push('saved '+kind);
 };
 const bootstrap={
  async verify(){
   if(fault==='closed-before-policy-post'&&writes.at(-1)?.kind==='portal-policy-dispatch')await fixture.close();
   return true;
  },
  withAdminCredentials:async callback=>callback({username:'owned',password:'privateAdmin'}),
 };
 fixture=await api.createBillingPortalFixture({configuration,bootstrap,cli:{validateBinding:async()=>true}},{fetch,write});
 return {configuration,receipts,fixture,events,requests,writes,recordCallbackInspection:value=>{callbackInspection=value;}};
}

test('Portal requires its dedicated fixture and explicit admitted configuration before dependencies',async()=>{
 const dependencies=new Proxy({}, {get(){assert.fail('nonadmitted fixture reached dependencies');}});
 const {configuration}=portalFixtureInputs(),ordinary=portalPolicyInputs().configuration;
 assert.equal(typeof api.createBillingPortalFixture,'function');
 for(const input of [configuration,{...ordinary,env:configuration.env}])
  await assert.rejects(api.createBillingFixture({configuration:input},dependencies),/billing_FIXTURE/);
 for(const input of [ordinary,{...configuration,env:ordinary.env}])
  await assert.rejects(api.createBillingPortalFixture({configuration:input},dependencies),/billing_FIXTURE/);
});

test('Portal writes one exact v1 policy after the owned price publish acknowledgement',async()=>{
 const h=await portalFixtureHarness(),{fixture,events,requests,writes,receipts,configuration}=h;
 const limits={deadline:Date.now()+5000};
 assert.equal(fixture.resources().portalReturnUrl,undefined);
 assert.equal(await fixture.provision(limits),true);
 const expected=api.buildBillingPortalPolicyRequest(configuration,receipts);
 const policyPath=new URL(expected.url).pathname;
 const policyPosts=requests.filter(r=>r.method==='POST'&&r.path===policyPath);
 assert.equal(policyPosts.length,1);
 assert.deepEqual(policyPosts[0].body,expected.body);
 assert.equal(policyPosts[0].headers['Idempotency-Key'].startsWith('billing:'),true);
 const order=['saved publish-ack','GET '+policyPath,'saved portal-policy-dispatch','POST '+policyPath,
  'saved portal-policy-ack','saved callback-dispatch','saved fixture'];
 assert.deepEqual(events.filter(e=>order.includes(e)),order);
 assert.equal(writes.find(r=>r.kind==='portal-policy-dispatch').value.phase,'DISPATCHING');
 assert.equal(writes.find(r=>r.kind==='portal-policy-dispatch').value.policyVersion,0);
 assert.equal(writes.find(r=>r.kind==='portal-policy-ack').value.phase,'ACKNOWLEDGED');
 assert.equal(writes.find(r=>r.kind==='portal-policy-ack').value.policyVersion,1);
 assert.equal(fixture.resources().portalReturnUrl,expected.body.portal_return_urls[0]);
 assert.equal(fixture.resources().portalPolicyVersion,1);
 assert.equal(fixture.resources().portalNavigationUrl,undefined);
 assert.ok(!requests.some(r=>r.path.includes('/portal-sessions')));
 assert.doesNotMatch(JSON.stringify(writes),/private(Admin|Cookie|Csrf)/);
 const once=events.length;
 await assert.rejects(()=>fixture.provision(limits),/billing_FIXTURE/);
 assert.equal(events.length,once);
 await fixture.close();
 assert.equal(fixture.resources().portalReturnUrl,undefined);
});

for(const origin of ['owned','foreign-fixture','foreign-subscription','closed','forged-payment','pending-confirm','publication-unproved','callback-drift'])test('Portal flow binds the ready fixture policy: '+origin,async()=>{
 const {fixture,configuration,receipts,writes,recordCallbackInspection}=await portalFixtureHarness();
 const limits={deadline:Date.now()+5000};
 const id=n=>String(n).padStart(8,'0')+'-2222-4333-8444-555555555555';
 await fixture.provision(limits);
 await fixture.acknowledgeCustomer({siteId:receipts.site.id,customerId:id(20)},limits);
 await fixture.beforeDispatch('create','owned-create',undefined,limits);
 await fixture.acknowledge('create',{subscriptionId:id(21),operationId:id(22)},limits);
 await fixture.beforeDispatch('confirm','owned-confirm',{subscriptionId:id(21),operationId:id(22),attemptId:id(23)},limits);
 if(origin!=='pending-confirm')await fixture.acknowledge('confirm',{attemptId:id(23),state:'OBSERVE',nextActionAllowed:false},limits);
 const original=fixture.resources();
 const scope=origin==='foreign-subscription'?{...original,subscriptionId:id(30)}:original;
 if(origin==='closed')await fixture.close();
 const now=Math.floor(Date.now()/1000),periodStart=now-10,periodEnd=now+3600;
 const utc=epoch=>new Date(epoch*1000).toISOString();
 const base={site_id:scope.siteId,customer_id:scope.customerId,subscription_id:scope.subscriptionId,product_id:scope.productId};
 const payment={...base,schema_version:3,event_id:id(35),event_type:'payment.succeeded',payment_status:'SUCCEEDED',
  payment_id:id(34),billing_record_id:id(33),amount_minor:2000,currency:'EUR',billing_kind:'INITIAL',price_id:scope.priceId,
  price_version:1,period_start:utc(periodStart),period_end:utc(periodEnd)};
 const paid={...base,schema_version:4,event_id:id(36),event_type:'subscription.updated',
  state:'ACTIVE',access_allowed:true,revision:3,billing_record_id:id(33),payment_id:null};
 const callbacks=[payment,paid].map(payload=>({id:payload.event_id,type:payload.event_type,delivered:true,payload}));
 const inspection={count:2,duplicates:0,events:callbacks.map(({id,type,payload})=>({
  event_id:id,event_type:type,count:1,sha256:billingCallbackDigest(payload),
 }))};
 recordCallbackInspection(origin==='callback-drift'?{...inspection,duplicates:1}:inspection);
 const initialRow={customer_reference:'cus_fixture',subscription_reference:'sub_fixture',product_reference:'prod_fixture',
  price_reference:'price_fixture',invoice_reference:'in_fixture',allocation_reference:'inpay_fixture',intent_reference:'pi_fixture',
  period_start:periodStart,period_end:periodEnd,payment_id:id(34),billing_record_id:id(33),initial_unscheduled:true,
  subscription_revision:3,cash_journal_ids:[id(35)],paid_journal_ids:[id(36)],callbacks,
  proof:{cash:true,journal:true,original:true,webhook:true}};
 const bootstrap={withDatabaseCredentials:async fn=>fn({})};
 const baseline=await proveBillingEvidence({configuration,bootstrap,
  fixture:{resources:()=>scope,inspectCallbacks:async()=>inspection}},limits,
 {connect:()=>({begin:async(_mode,fn)=>fn({unsafe:async(_query,args)=>args?[initialRow]:[]}),end:async()=>{}})});
 let openings=0,sqlCalls=0;
 const page={},hostedDocument={},returnDocument={},digest='f'.repeat(64);
 const flow=()=>createBillingPortalFlow({
  configuration,scope,bootstrap,fixture:origin==='foreign-fixture'?{resources:()=>original}:fixture,
 },{
  verifyInitial:async()=>({baseline:origin==='forged-payment'?{...baseline}:baseline,
   provider:{providerVerified:true,initialCashVerified:true,lineCount:1,allocationCount:1,chargeCount:1}}),
  assertListener:async()=>true,
  connect:()=>({begin:async(mode,fn)=>{
   assert.equal(mode,'read only');
   return fn({unsafe:async(_query,args)=>{
    if(!args)return [];
    sqlCalls++;
    assert.equal(args[10],original.portalReturnUrl);
    assert.equal(args[6],id(34));
    return [{portal_verified:origin!=='publication-unproved'}];
   }});
  },end:async()=>{}}),
  recordOpeningIntent:async()=>assert.fail('native flow must write the owned journal itself'),
  async openPortal(port){
   openings++;assert.equal(port.returnUrl,original.portalReturnUrl);
   await port.beforeDispatch(digest);
   const opening=await port.observe201({expiresAt:utc(now+120)});
   return {opening,tab:page,document:hostedDocument,origin:'https://billing.stripe.com',providerMutationCount:0};
  },
  observeMerchantReturn:async()=>({tab:page,sourceDocument:hostedDocument,document:returnDocument,
   initiator:'PROVIDER_DOCUMENT',method:'GET',url:original.portalReturnUrl,providerMutationCount:0}),
  readReturnedSubscription:async()=>({tab:page,document:returnDocument,method:'GET',status:200,
   siteId:scope.siteId,customerId:scope.customerId,subscriptionId:scope.subscriptionId,
   billingStatus:'ACTIVE',access:{allowed:true,reason:'PAID'},revision:3,paidThrough:utc(periodEnd)}),
 },limits);
 const terminal=await flow().run();
 const unproved=origin==='publication-unproved';
 const reachedReturn=origin==='owned'||origin==='callback-drift';
 assert.equal(terminal.code,origin==='owned'?'OK':origin==='callback-drift'?'PORTAL_PROOF_AFTER_RETURN':
  origin==='forged-payment'?'PORTAL_INITIAL':unproved?'PORTAL_PROOF_BEFORE_OPEN':'PORTAL_POLICY');
 assert.equal(openings,reachedReturn||unproved?1:0);
 assert.equal(sqlCalls,reachedReturn?2:unproved?1:0);
 const portalWrites=()=>writes.filter(row=>row.kind.startsWith('portal-session-')).map(row=>row.kind);
 assert.deepEqual(portalWrites(),reachedReturn?['portal-session-dispatch','portal-session-ack']:unproved?['portal-session-dispatch']:[]);
 if(unproved){
  assert.equal((await flow().run()).code,'PORTAL_DISPATCH');
  assert.deepEqual(portalWrites(),['portal-session-dispatch']);
  assert.equal(sqlCalls,1);
 }
});

for(const [fault,posts,intent,ack] of [
 ['foreign-price',0,false,false],
 ['v0-changed',0,false,false],
 ['portal-policy-dispatch',0,false,false],
 ['closed-before-policy-post',0,true,false],
 ['uncertain-http',1,true,false],
 ['wrong-ack',1,true,false],
 ['portal-policy-ack',1,true,false],
 ['fixture',1,true,true],
])test('Portal stays fenced after '+fault,async()=>{
 const {fixture,events,requests,writes,receipts}=await portalFixtureHarness(fault);
 const limits={deadline:Date.now()+5000};
 await assert.rejects(()=>fixture.provision(limits));
 const once=events.length;
 await assert.rejects(()=>fixture.provision(limits),/billing_FIXTURE/);
 await assert.rejects(()=>fixture.acknowledgeCustomer({customerId:receipts.product.id,siteId:receipts.site.id},limits),/billing_FIXTURE/);
 assert.equal(events.length,once);
 assert.equal(fixture.resources().portalReturnUrl,undefined);
 assert.equal(fixture.resources().portalPolicyVersion,undefined);
 assert.equal(requests.filter(r=>r.method==='POST'&&r.path.endsWith('/subscription-policy')).length,posts);
 assert.equal(writes.some(r=>r.kind==='portal-policy-dispatch'),intent);
 assert.equal(writes.some(r=>r.kind==='portal-policy-ack'),ack);
 assert.ok(!writes.some(r=>r.kind==='fixture'));
});

for(const fault of ['create-ack','confirm-ack'])test('Portal loses opening authority after an uncertain '+fault,async()=>{
 const {fixture,receipts,writes}=await portalFixtureHarness(fault);
 const limits={deadline:Date.now()+5000};
 const id=n=>String(n).padStart(8,'0')+'-2222-4333-8444-555555555555';
 const create={subscriptionId:id(21),operationId:id(22)};
 const confirm={attemptId:id(23),state:'OBSERVE',nextActionAllowed:false};
 const createAck=()=>fixture.acknowledge('create',create,limits);
 const confirmDispatch=()=>fixture.beforeDispatch('confirm','owned-confirm',{...create,attemptId:id(23)},limits);
 const confirmAck=()=>fixture.acknowledge('confirm',confirm,limits);
 await fixture.provision(limits);
 await fixture.acknowledgeCustomer({siteId:receipts.site.id,customerId:id(20)},limits);
 await fixture.beforeDispatch('create','owned-create',undefined,limits);
 if(fault==='create-ack') {
  await assert.rejects(createAck,/PRIVATE_WRITE_ERROR/);
  await assert.rejects(createAck,/billing_FIXTURE/);
  await assert.rejects(confirmDispatch,/billing_FIXTURE/);
  assert.ok(!writes.some(row=>row.kind==='confirm-dispatch'));
 } else {
  await createAck();
  await confirmDispatch();
  await assert.rejects(confirmAck,/PRIVATE_WRITE_ERROR/);
  await assert.rejects(confirmAck,/billing_FIXTURE/);
 }
 assert.equal(api.billingPortalFixturePolicy(fixture,fixture.resources()),null);
 await assert.rejects(()=>fixture.beforePortalOpening(fixture.resources(),'f'.repeat(64),limits),/billing_FIXTURE/);
 assert.ok(!writes.some(row=>row.kind.startsWith('portal-session-')));
});
