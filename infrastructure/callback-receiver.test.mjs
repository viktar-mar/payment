import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac, randomBytes, randomUUID } from 'node:crypto';
import { createReceiver } from './callback-receiver.mjs';
import * as receiverModule from './callback-receiver.mjs';
import http from 'node:http';

test('owned access channel performs bounded authenticated Partner refresh in business time and is opt-in',async()=>{
  assert.equal(typeof receiverModule.createOwnedAccessRuntime,'function');
  let business=Date.parse('2027-01-01T00:00:00Z'),partnerCalls=0;
  const capability=randomBytes(32).toString('base64url'),partnerKey='owned-test-partner-key';
  const fixture=http.createServer((req,res)=>{
    if(req.url==='/time'){
      assert.equal(req.headers.authorization,'Bearer '+capability);res.end(new Date(business).toISOString());return;
    }
    assert.equal(req.url,`/api/v1/partner/customers/${cacheIdentity.customer_id}/products/${cacheIdentity.product_id}/access`);
    assert.equal(req.headers.authorization,'Bearer '+partnerKey);partnerCalls++;
    res.setHeader('content-type','application/json');res.end(JSON.stringify({...cacheIdentity,subscription_id:lifecycle().subscription_id,revision:2,allowed:true,evaluated_at:new Date(business).toISOString(),valid_until:new Date(business+300000).toISOString()}));
  });
  await new Promise(resolve=>fixture.listen(0,'127.0.0.1',resolve));
  const fixtureBase=`http://127.0.0.1:${fixture.address().port}`;
  const runtime=receiverModule.createOwnedAccessRuntime({backendUrl:fixtureBase,clockUrl:fixtureBase+'/time',capability});
  const local=createReceiver({accessRuntime:runtime});await new Promise(resolve=>local.listen(0,'127.0.0.1',resolve));
  const target=`http://127.0.0.1:${local.address().port}`;
  const request=(path,body)=>fetch(target+path,{signal:AbortSignal.timeout(3000),...(body?{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)}:{})});
  try{
    assert.equal((await fetch(base+'/access',{signal:AbortSignal.timeout(3000)})).status,404);
    assert.equal((await request('/access')).status,409);
    assert.equal((await request('/access/configure',{...cacheIdentity,api_key:partnerKey,extra:true})).status,400);
    assert.equal((await request('/access/configure',{...cacheIdentity,api_key:partnerKey})).status,204);
    assert.deepEqual(await (await request('/access')).json(),{allowed:true,revision:2,refreshes:1,invalidations:0});
    assert.equal((await (await request('/access')).json()).allowed,true);assert.equal(partnerCalls,1);
    business+=300000;assert.equal((await (await request('/access')).json()).refreshes,2);assert.equal(partnerCalls,2);
    const safe=await (await request('/inspection')).text();for(const privateValue of [partnerKey,capability,cacheIdentity.customer_id])assert.equal(safe.includes(privateValue),false);
    for(const backendUrl of ['https://example.test','http://127.0.0.1/path','http://user:password@127.0.0.1'])assert.throws(()=>receiverModule.createOwnedAccessRuntime({backendUrl,clockUrl:fixtureBase+'/time',capability}));
  }finally{for(const owned of [local,fixture]){owned.closeAllConnections();await new Promise(resolve=>owned.close(resolve));}}
});

const cacheIdentity={site_id:'22222222-2222-4222-8222-222222222222',customer_id:'44444444-4444-4444-8444-444444444444',product_id:'55555555-5555-4555-8555-555555555555'};
test('authoritative NO_SUBSCRIPTION denial survives old lifecycle replay without nullable grants',async()=>{
  const now=Date.parse('2027-01-01T00:00:00Z');
  const denied={...cacheIdentity,subscription_id:null,entitled_price_id:null,entitled_price_version:null,reason:'NO_SUBSCRIPTION',allowed:false,revision:0,evaluated_at:new Date(now).toISOString(),valid_until:null};
  let calls=0,decision={...denied,subscription_id:lifecycle().subscription_id,reason:'PAID',allowed:true,revision:4,entitled_price_id:lifecycle().product_id,entitled_price_version:1,valid_until:new Date(now+300000).toISOString()};
  const cache=receiverModule.createSubscriptionAccessCache({identity:cacheIdentity,now:()=>now,refresh:async()=>{calls++;return decision;}});
  assert.equal((await cache.read()).allowed,true);
  decision=denied;
  cache.observe({...lifecycle(),revision:5});
  assert.deepEqual(await cache.read(),{allowed:false,revision:0,refreshes:2,invalidations:1});
  cache.observe({...lifecycle(),revision:2});
  assert.equal((await cache.read()).allowed,false);assert.equal(calls,3);
  for(const patch of [{allowed:true,valid_until:new Date(now+300000).toISOString()},{reason:'CANCELLED'},{revision:1},{entitled_price_id:lifecycle().product_id},{entitled_price_version:1},{valid_until:new Date(now+1).toISOString()}]){
    const invalid=receiverModule.createSubscriptionAccessCache({identity:cacheIdentity,now:()=>now,refresh:async()=>({...denied,...patch})});
    await assert.rejects(invalid.read());assert.equal(invalid.inspect().allowed,false);
  }
});
test('Partner refresh alone grants a bounded cache; late callbacks never renew authority',async()=>{
  assert.equal(typeof receiverModule.createSubscriptionAccessCache,'function');
  let business=Date.parse('2027-01-01T00:00:00Z'),real=1000,calls=0;
  let decision={...cacheIdentity,subscription_id:'33333333-3333-4333-8333-333333333333',allowed:true,revision:2,evaluated_at:new Date(business).toISOString(),valid_until:new Date(business+300000).toISOString()};
  const cache=receiverModule.createSubscriptionAccessCache({identity:cacheIdentity,now:()=>business,realNow:()=>real,refresh:async()=>{calls++;return {...decision};}});
  assert.equal(cache.inspect().allowed,false);
  cache.observe({...lifecycle(),revision:2});assert.equal(cache.inspect().allowed,false);
  assert.equal((await cache.read()).allowed,true);assert.equal(calls,1);
  business+=1000;cache.observe({...lifecycle(),revision:1});
  assert.equal(cache.inspect().allowed,true);assert.equal(calls,1);
  business=Date.parse(decision.valid_until);assert.equal(cache.inspect().allowed,false);
  // Frozen business time must not turn a future business valid_until into indefinite real authority.
  business=Date.parse(decision.evaluated_at);real=301000;assert.equal(cache.inspect().allowed,false);
  decision={...decision,revision:3,allowed:false,valid_until:null,evaluated_at:new Date(business).toISOString()};
  cache.observe({...lifecycle(),revision:3});assert.equal((await cache.read()).allowed,false);
  cache.observe({...lifecycle(),revision:2});assert.equal(cache.inspect().allowed,false);
});
test('Partner cache rejects foreign identities, regressive revisions, unbounded expiry and stale in-flight refresh',async()=>{
  assert.equal(typeof receiverModule.createSubscriptionAccessCache,'function');
  const now=Date.parse('2027-01-01T00:00:00Z');
  const good={...cacheIdentity,subscription_id:'33333333-3333-4333-8333-333333333333',allowed:true,revision:2,evaluated_at:new Date(now).toISOString(),valid_until:new Date(now+300000).toISOString()};
  for(const patch of [{site_id:randomUUID()},{customer_id:randomUUID()},{product_id:randomUUID()},{revision:1},{valid_until:new Date(now+300001).toISOString()},{evaluated_at:new Date(now+1).toISOString()}]) {
    const cache=receiverModule.createSubscriptionAccessCache({identity:cacheIdentity,now:()=>now,refresh:async()=>({...good,...patch})});
    cache.observe({...lifecycle(),revision:2});await assert.rejects(cache.read());assert.equal(cache.inspect().allowed,false);
  }
  let resolve;const pending=new Promise(r=>{resolve=r;});
  const cache=receiverModule.createSubscriptionAccessCache({identity:cacheIdentity,now:()=>now,refresh:()=>pending});
  const reading=cache.read();cache.observe({...lifecycle(),revision:3});resolve(good);
  await assert.rejects(reading);assert.equal(cache.inspect().allowed,false);
});
test('only a verified owned lifecycle callback invalidates the integrated receiver cache',async()=>{
  const now=Date.parse('2027-01-01T00:00:00Z');
  const cache=receiverModule.createSubscriptionAccessCache({identity:cacheIdentity,now:()=>now,refresh:async()=>({...cacheIdentity,subscription_id:lifecycle().subscription_id,allowed:true,revision:1,evaluated_at:new Date(now).toISOString(),valid_until:new Date(now+300000).toISOString()})});
  await cache.read();const local=createReceiver({accessCache:cache});
  await new Promise((resolve,reject)=>{local.once('error',reject);local.listen(0,'127.0.0.1',resolve);});
  const target=`http://127.0.0.1:${local.address().port}`;
  const send=(path,body,headers={})=>fetch(target+path,{method:'POST',signal:AbortSignal.timeout(3000),headers:{'content-type':'application/json',...headers},body:JSON.stringify(body)});
  try {
    assert.equal((await send('/configure',{secret_version:1,secret})).status,204);
    const event=lifecycle(),timestamp=String(Math.floor(Date.now()/1000));
    const headers={'payment-event-id':event.event_id,'payment-timestamp':timestamp,'payment-secret-version':'1','payment-signature':'v1='+'0'.repeat(64)};
    assert.equal((await send('/callback',event,headers)).status,401);assert.equal(cache.inspect().allowed,true);
    headers['payment-signature']='v1='+createHmac('sha256',Buffer.from(secret,'base64url')).update(timestamp+'.'+JSON.stringify(event)).digest('hex');
    assert.equal((await send('/callback',event,headers)).status,204);assert.equal(cache.inspect().allowed,false);
    assert.equal((await send('/callback',event,headers)).status,204);assert.equal(cache.inspect().invalidations,1);
  } finally {local.closeAllConnections();await new Promise(resolve=>local.close(resolve));}
});

let server, base;
const secret = randomBytes(32).toString('base64url');
before(async () => {
  server = createReceiver();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(async () => { await new Promise(resolve => server.close(resolve)); });
const payload = () => ({ schema_version: 1, event_id: randomUUID(), event_type: 'payment.succeeded', site_id: randomUUID(), payment_id: randomUUID(), external_order_id: null, payment_status: 'SUCCEEDED', amount_minor: 4999, currency: 'EUR', occurred_at: new Date().toISOString() });
async function configure() {
  const r = await fetch(`${base}/configure`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ secret_version: 1, secret }) });
  assert.equal(r.status, 204); assert.equal(await r.text(), '');
}
async function callback(p = payload(), options = {}) {
  const body = options.body ?? JSON.stringify(p);
  const timestamp = options.timestamp ?? String(Math.floor(Date.now()/1000));
  const signature = createHmac('sha256', Buffer.from(secret,'base64url')).update(timestamp+'.').update(body).digest('hex');
  return fetch(`${base}/callback`, { method: options.method ?? 'POST', headers: { 'content-type': options.media ?? 'application/json', 'payment-event-id': options.eventId ?? p.event_id, 'payment-timestamp': timestamp, 'payment-secret-version': '1', 'payment-signature': options.signature ?? 'v1='+signature }, body });
}
test('health and configuration expose no cryptographic material', async () => {
  assert.equal((await fetch(`${base}/health`)).status, 200); await configure();
  const inspection = await (await fetch(`${base}/inspection`)).text(); assert.ok(!inspection.includes(secret));
});
test('verified callbacks deduplicate event ID and store only safe IDs/types/counts', async () => {
  await configure(); const p=payload(); assert.equal((await callback(p)).status,204); assert.equal((await callback(p)).status,204);
  const info=await (await fetch(`${base}/inspection`)).json(); assert.equal(info.count,1); assert.equal(info.duplicates,1);
  assert.deepEqual(info.events,[{ event_id:p.event_id,event_type:p.event_type,count:2 }]);
  assert.ok(!JSON.stringify(info).includes(p.payment_id));
});
test('exact HMAC rejects modified, malformed and wrong length encodings', async () => {
  for (const signature of ['v1='+'0'.repeat(64),'v1='+'f'.repeat(63),'v2='+'0'.repeat(64),'v1='+'A'.repeat(64),'v1=not-hex']) assert.equal((await callback(payload(),{signature})).status,401);
});
test('stale/future/noninteger timestamps are rejected', async () => {
  for (const timestamp of [String(Math.floor(Date.now()/1000)-301),String(Math.floor(Date.now()/1000)+301),'1.5','-1','0001','1'.repeat(21)]) assert.equal((await callback(payload(),{timestamp})).status,401);
});
test('unsafe IDs, wrong event types, header identity mismatch and malformed JSON fail closed', async () => {
  for (const patch of [{event_id:'../unsafe'},{site_id:'unsafe'},{payment_id:'unsafe'},{event_type:'provider.raw'},{schema_version:2}]) assert.equal((await callback({...payload(),...patch})).status,400);
  assert.equal((await callback(payload(),{eventId:randomUUID()})).status,400);
  assert.equal((await callback(payload(),{body:'{"bad":'})).status,400);
});
test('unsupported routes, methods and media are rejected', async () => {
  assert.equal((await fetch(`${base}/unknown`)).status,404);
  assert.equal((await fetch(`${base}/inspection`,{method:'POST'})).status,405);
  assert.equal((await callback(payload(),{media:'text/plain'})).status,415);
});
test('oversized body is rejected without echo', async () => {
  const r=await callback(payload(),{body:'x'.repeat(16385)});assert.equal(r.status,413); assert.ok((await r.text()).length<100);
});
test('invalid UTF-8 is rejected even with a valid MAC over exact bytes', async () => {
  assert.equal((await callback(payload(),{body:Buffer.from([0xc3,0x28])})).status,400);
});
test('invalid configuration cannot replace the active secret', async () => {
  for(const body of [{secret_version:0,secret},{secret_version:1,secret:'invalid'},{secret_version:1,secret,extra:'value'}]) {
    assert.equal((await fetch(`${base}/configure`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)})).status,400);
  }
  assert.equal((await callback()).status,204);
});
const donationPayload=()=>({...payload(),schema_version:2,donation_id:randomUUID(),campaign_key:'help-now',customer_id:null});
const subscriptionPayload=()=>({...payload(),schema_version:3,subscription_id:randomUUID(),billing_record_id:randomUUID(),customer_id:randomUUID(),product_id:randomUUID(),billing_kind:'PRORATION',price_id:randomUUID(),price_version:1,period_start:'2026-10-01T00:00:00Z',period_end:'2026-11-01T00:00:00Z'});
const lifecycle=()=>({schema_version:4,event_id:'11111111-1111-4111-8111-111111111111',event_type:'subscription.updated',site_id:'22222222-2222-4222-8222-222222222222',subscription_id:'33333333-3333-4333-8333-333333333333',customer_id:'44444444-4444-4444-8444-444444444444',product_id:'55555555-5555-4555-8555-555555555555',revision:2,state:'TRIALING',access_allowed:true,access_valid_until:'2026-10-11T12:05:00Z',occurred_at:'2026-10-11T12:00:00Z',billing_record_id:null,payment_id:null});
test('v4 signed lifecycle accepts exact safe fields and retains legacy delivery',async()=>{
  await configure();assert.equal((await callback(lifecycle())).status,204);
  assert.equal((await callback(lifecycle())).status,204);
  assert.equal((await callback(payload())).status,204);assert.equal((await callback(donationPayload())).status,204);
  assert.equal((await callback({...lifecycle(),event_id:randomUUID(),event_type:'subscription.access_changed',access_allowed:false,access_valid_until:null,state:'CANCELLED'})).status,204);
  for(const patch of [{secret:'private'},{revision:-1},{revision:1.5},{state:'RAW_PROVIDER_STATE'},{access_allowed:'true'},{access_valid_until:null},{payment_id:'bad'},{billing_record_id:{}},{access_valid_until:'2026-10-11T12:05:01Z'}])assert.equal((await callback({...lifecycle(),...patch})).status,400);
  assert.equal((await callback(lifecycle(),{signature:'v1='+'0'.repeat(64)})).status,401);
});
test('v3 positive cash and linked refund accept exactly the immutable context',async()=>{
  await configure();
  for(const patch of [{},{event_type:'refund.succeeded',payment_status:'PARTIALLY_REFUNDED',amount_minor:700}]) {
    const p={...subscriptionPayload(),...patch};
    assert.equal((await callback(p)).status,204);assert.equal((await callback(p)).status,204);
  }
  for(const patch of [{customer_id:null},{subscription_id:'bad'},{billing_record_id:'bad'},{product_id:null},{price_id:null},{billing_kind:'OTHER'},{price_version:0},{price_version:1.5},{price_version:2147483648},{period_end:'2026-09-01T00:00:00Z'},{period_start:null},{external_order_id:'order'},{amount_minor:0},{amount_minor:null,currency:null},{provider_invoice_reference:'private'},{donation_id:randomUUID()}])
    assert.equal((await callback({...subscriptionPayload(),...patch})).status,400);
  const missing=subscriptionPayload();delete missing.billing_record_id;
  assert.equal((await callback(missing)).status,400);
});
test('v2 anonymous and identified deliveries deduplicate and retain only safe event facts',async()=>{
  await configure();
  for(const customer_id of [null,randomUUID()]) {
    const p={...donationPayload(),customer_id};
    assert.equal((await callback(p)).status,204);assert.equal((await callback(p)).status,204);
    const info=await (await fetch(`${base}/inspection`)).json();
    assert.deepEqual(info.events.find(e=>e.event_id===p.event_id),{event_id:p.event_id,event_type:p.event_type,count:2});
    for(const value of [p.donation_id,p.campaign_key,p.payment_id,customer_id].filter(Boolean))assert.ok(!JSON.stringify(info).includes(value));
  }
});
test('both schemas reject every missing extra and invalid type without reflecting private fixtures',async()=>{
  for(const valid of [payload(),donationPayload()]) {
    for(const field of Object.keys(valid)) {
      const missing={...valid};delete missing[field];
      const bad={...valid,[field]:{private:'receiver-private-7q9'}};
      for(const p of [missing,bad]) {const r=await callback(p);assert.equal(r.status,400);assert.ok(!(await r.text()).includes('receiver-private-7q9'));}
    }
    for(const field of ['message','donor_message','email','subject','raw_subject','token','sdk_session','otp','extra']) {
      const r=await callback({...valid,[field]:'receiver-private-7q9'});assert.equal(r.status,400);assert.ok(!(await r.text()).includes('receiver-private-7q9'));
    }
    for(const schema_version of [0,3,4294967297,1.5,'2',null])assert.equal((await callback({...valid,schema_version})).status,400);
  }
  for(const patch of [{customer_id:'bad'},{donation_id:null},{campaign_key:'Bad Key'},{external_order_id:'order'}])assert.equal((await callback({...donationPayload(),...patch})).status,400);
  const info=await (await fetch(`${base}/inspection`)).text();assert.ok(!info.includes('receiver-private-7q9'));
});
test('v2 signatures cover the exact donation context bytes',async()=>{
  const p=donationPayload();const timestamp=String(Math.floor(Date.now()/1000));
  const signature='v1='+createHmac('sha256',Buffer.from(secret,'base64url')).update(timestamp+'.'+JSON.stringify(p)).digest('hex');
  assert.equal((await callback({...p,campaign_key:'other'},{timestamp,signature})).status,401);
});
