import test,{before,after} from 'node:test';import assert from 'node:assert/strict';
import * as api from './stripe-billing-provision.mjs';
import {opts,responses,id} from './fixtures/failed-renewal-descriptors.mjs';
const saved=globalThis.fetch;let escaped=0;before(()=>{globalThis.fetch=()=>{escaped++;throw Error('blocked');};});after(()=>{globalThis.fetch=saved;assert.equal(escaped,0);});
for(const reject of ['none','dispatch','ack'])test('fixed fixture consumes the arm once when ledger rejection is '+reject,async()=>{
 const c=opts(),ids=[2,3,6,5,4,7,8,9].map(id),writes=[],requests=[];
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
 const fixture=await api.createBillingFailedRenewalFixture({configuration:c,bootstrap:{verify:async()=>true,withAdminCredentials:async callback=>callback({username:'owned',password:'secret-sentinel'})},cli:{validateBinding:async()=>true}},{fetch,write:async(name,value)=>{writes.push({name,value});if(name.endsWith('decline-arm-'+reject+'.json'))throw Error('uncertain ledger write');}});
 await fixture.provision({deadline:Date.now()+10000});assert.equal(requests.filter(r=>r.path.endsWith('/prices'))[0].body.type,'SUBSCRIPTION');assert.equal(requests.find(r=>r.path.endsWith('/subscription-policy')&&r.method==='POST').headers['Idempotency-Key'].startsWith('billing:'),true);
 await fixture.acknowledgeCustomer({customerId:ids[4],siteId:ids[1]},{deadline:Date.now()+1000});
 await fixture.beforeDispatch('create','sdk-original-create',undefined,{deadline:Date.now()+1000});assert.equal(writes.at(-1).value.key,'sdk-original-create');
 await fixture.acknowledge('create',{subscriptionId:ids[5],operationId:ids[6]},{deadline:Date.now()+1000});
 await fixture.beforeDispatch('confirm','sdk-original-confirm',{subscriptionId:ids[5],operationId:ids[6],attemptId:ids[7]},{deadline:Date.now()+1000});
 await fixture.acknowledge('confirm',{attemptId:ids[7],state:'OBSERVE',nextActionAllowed:false},{deadline:Date.now()+1000});
 const l={deadline:Date.now()+5000},[boundary,armed]=responses();
 assert.equal(requests.find(r=>r.path.endsWith('/subscription-policy')&&r.method==='POST').body.grace_days,3);
 if(reject!=='none'){
  if(reject==='dispatch')await assert.rejects(fixture.beforeDeclineArm(boundary,l));
  else{await fixture.beforeDeclineArm(boundary,l);await assert.rejects(fixture.acknowledgeDeclineArm(armed,l));}
  await assert.rejects(fixture.beforeDeclineArm(boundary,l));await assert.rejects(fixture.acknowledgeDeclineArm(armed,l));
  assert.deepEqual(writes.filter(w=>w.name.includes('decline-arm')).map(w=>w.value.phase),reject==='dispatch'?['DISPATCHING']:['DISPATCHING','ACKNOWLEDGED']);
  await fixture.close();return;
 }
 assert.equal(await fixture.beforeDeclineArm(boundary,l),true);
 assert.equal(writes.at(-1).value.phase,'DISPATCHING');
 assert.equal(await fixture.acknowledgeDeclineArm(armed,l),true);
 assert.equal(writes.at(-1).value.phase,'ACKNOWLEDGED');
 await assert.rejects(fixture.beforeDeclineArm(boundary,l));await assert.rejects(fixture.acknowledgeDeclineArm(armed,l));
 assert.ok(!JSON.stringify(writes).includes('secret-sentinel'));await fixture.close();
});
