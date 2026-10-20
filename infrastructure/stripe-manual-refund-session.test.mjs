import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHmac} from 'node:crypto';
import {createReceiver} from './callback-receiver.mjs';
import {fixture} from './fixtures/stripe-manual-refund-state-fixture.mjs';
import {createManualRefundSession} from './stripe-manual-refund-session.mjs';

const uuid = n => `bbbbbbbb-cccc-4ddd-8eee-${String(n).padStart(12,'0')}`;
const secret = Buffer.alloc(32,25).toString('base64url');
const fail = /stripe_manual_refund_session_UNCONFIRMED/;
const clone = value => structuredClone(value);

// Complete transport shapes follow AdminPaymentViews, ProviderOperationView,
// AdminAuthController, CallbackAdminController and receiver /inspection.
async function harness(t, changes={}) {
  const f=await fixture(t),state=await f.open(),target=state.target,limits=f.limits;
  for(const service of ['postgres','mailpit','callback-receiver','demo-site','backend']) {
    await state.beginRestore(service,limits);await state.ackRestore(service,limits);
  }
  const p={payment_id:target.paymentId,site_id:target.site.id,payment_account_id:target.account.id,
    source:'CATALOG',price_id:target.price.id,price_key:target.price.key,price_version:1,
    external_order_id:null,amount_minor:2000,currency:'EUR',tax_mode:'NONE',tax_amount_minor:null,
    status:'SUCCEEDED',provider:'STRIPE',refundable_amount_minor:2000,refunded_amount_minor:0,
    created_at:'2026-10-19T10:00:00Z',updated_at:'2026-10-19T10:00:00Z',customer_id:null,
    donation_id:null,campaign_id:null,campaign_key:null,campaign_version:null,subscription_id:null,
    billing_record_id:null,subscription_billing_kind:null,subscription_period_start:null,subscription_period_end:null};
  const event=(n,type,amount,ref)=>({id:uuid(n),sequence:n,event_type:type,source:'PROVIDER_EVENT',
    amount_minor:amount,currency:'EUR',provider_event_id:'evt_SYNTHETIC'+n,provider_object_id:ref,
    request_id:uuid(90),occurred_at:'2026-10-19T10:00:00Z',received_at:'2026-10-19T10:00:00Z'});
  const success=event(10,'PAYMENT_SUCCEEDED',2000,target.intentId);
  const out=e=>({id:e.id,callback_type:e.event_type==='PAYMENT_SUCCEEDED'?'payment.succeeded':'refund.succeeded',
    status:'DELIVERED',attempt_count:1,next_attempt_at:null,created_at:p.created_at,updated_at:p.updated_at});
  const delivery=e=>({id:uuid(100+e.sequence),outbox_event_id:e.id,attempt_number:1,delivery_mode:'AUTOMATIC',
    status:'SUCCEEDED',http_status:204,error_category:null,started_at:p.created_at,finished_at:p.updated_at});
  const d={payment:p,attempts:[{id:uuid(11),attempt_number:1,status:'SUCCEEDED',provider_intent_reference:target.intentId,
    payment_method_family:'CARD',result_code:null,created_at:p.created_at,updated_at:p.updated_at}],
    events:[event(1,'PAYMENT_CREATED',2000,null),event(2,'ATTEMPT_CREATED',2000,target.intentId),success],
    refunds:[],outbox_events:[out(success)],deliveries:[delivery(success)]};
  const op=(id,kind,resource,ref)=>({operation_id:id,payment_id:target.paymentId,resource_id:resource,
    account_id:target.account.id,account_mode:'SANDBOX',site_id:target.site.id,kind,status:'ACKNOWLEDGED',
    manual_review_required:false,reference:ref,first_dispatched_at:p.created_at,retry_before:null,
    next_attempt_at:null,safe_error_code:null});
  const operations=[op(target.operationId,'PAYMENT_PREPARE',target.paymentId,target.intentId)];
  const receipt={count:0,duplicates:0,events:[]},trace=[],posts=[];
  let receiverServer,receiverOrigin;
  if(changes.realReceiver) {
    receiverServer=createReceiver();await new Promise(resolve=>receiverServer.listen(0,'127.0.0.1',resolve));
    receiverOrigin='http://127.0.0.1:'+receiverServer.address().port;
    t.after(()=>new Promise(resolve=>{receiverServer.closeAllConnections();receiverServer.close(resolve);}));
  }
  const account={id:target.account.id,key:target.account.key,provider:'STRIPE',mode:'SANDBOX',status:'ACTIVE',external_account_id:target.account.externalId};
  const site={id:target.site.id,key:target.site.key,status:'ACTIVE',payment_account_id:target.account.id,
    allowed_origins:['http://127.0.0.1:18083'],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'ALLOWLIST',methods:['CARD']}};
  const price={id:target.price.id,site_id:site.id,product_id:uuid(98),price_key:target.price.key,version:1,
    type:'ONE_TIME',amount_minor:2000,currency:'EUR',billing_interval:null,trial_days:0,
    trial_payment_method_policy:'OPTIONAL_DURING_TRIAL',tax_mode:'NONE',tax_code:null,status:'PUBLISHED',provider_price_id:target.price.providerPriceId};
  const catalog={products:[{id:price.product_id,site_id:site.id,key:'s5a-product-'+target.originalRunId.replaceAll('-',''),
    name:'Stage5A one-time acceptance',description:null,status:'ACTIVE'}],prices:[price]};
  let configured=false,adminId=uuid(99),listener=true;
  const response=(value,status=200,headers={})=>{
    const resultHeaders=new Headers();
    for(const [name,values] of Object.entries(headers))for(const value of Array.isArray(values)?values:[values])resultHeaders.append(name,value);
    return new Response(value===null?null:JSON.stringify(value),{status,headers:resultHeaders});
  };
  async function fetcher(url,init) {
    const u=new URL(url),path=u.pathname+u.search;
    assert.equal(init.redirect,'error');assert.ok(init.signal instanceof AbortSignal);
    assert.ok([target.endpoints.backend,target.endpoints.receiver].includes(u.origin));
    trace.push(init.method+' '+path);
    if(changes.transport) {const override=await changes.transport({url,init,path,d,trace});if(override)return override;}
    if(u.origin===target.endpoints.receiver) {
      assert.equal(init.headers.Cookie,undefined);assert.equal(init.headers['X-XSRF-TOKEN'],undefined);
      if(receiverOrigin)return fetch(receiverOrigin+path,init);
      if(path==='/configure'&&init.method==='POST') {
        assert.equal(configured,false);assert.deepEqual(JSON.parse(init.body),{secret,secret_version:2});
        const persisted=JSON.parse(await readFile(f.statePath));assert.equal(persisted.configure.phase,'DISPATCHING');
        configured=true;if(changes.loseConfigure)throw Error(secret);return response(null,204);
      }
      if(path==='/inspection'&&init.method==='GET')return response(receipt);
      throw Error('unexpected_receiver_route');
    }
    if(path==='/api/admin/v1/auth/sessions'&&init.method==='POST') {
      assert.equal(JSON.parse(init.body).password,f.data.app.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD);
      return response(null,204,{'set-cookie':['PAYMENT_ADMIN_SESSION=session_canary; Path=/','XSRF-TOKEN=csrf_canary; Path=/']});
    }
    assert.equal(init.headers.Cookie,'PAYMENT_ADMIN_SESSION=session_canary; XSRF-TOKEN=csrf_canary');
    if(init.method!=='GET')assert.equal(init.headers['X-XSRF-TOKEN'],'csrf_canary');
    if(path==='/api/admin/v1/auth/session')return response({id:adminId,username:'synthetic',expires_at:'2099-01-01T00:00:00Z'});
    if(path==='/api/admin/v1/payment-accounts')return response([account]);
    if(path==='/api/admin/v1/sites')return response([site]);
    if(path===`/api/admin/v1/sites/${site.id}/catalog`)return response(catalog);
    if(path===`/api/v1/admin/payments?site_id=${site.id}&page=0&size=2&sort=CREATED_DESC`)
      return response({items:[p],total:1,page:0,size:2,sort:'CREATED_DESC'});
    if(path===`/api/v1/admin/payments/${target.paymentId}`)return response(d);
    if(path===`/api/v1/admin/provider-operations?payment_id=${target.paymentId}`)return response(operations);
    if(path===`/api/v1/admin/sites/${site.id}/callback/rotate-secret`&&init.method==='POST') {
      const persisted=JSON.parse(await readFile(f.statePath));assert.equal(persisted.rotation.phase,'DISPATCHING');
      if(changes.loseRotate)throw Error(secret);
      return response({site_id:site.id,endpoint_url:'http://callback-receiver:8080/callback',enabled:true,
        secret_version:2,secret,...changes.rotation});
    }
    if(path===`/api/v1/admin/payments/${target.paymentId}/refunds`&&init.method==='POST') {
      const i=posts.length,amount=[700,1300][i],persisted=JSON.parse(await readFile(f.statePath));
      assert.equal(persisted.legs[i].phase,'DISPATCHING');
      if(i===1)assert.equal(persisted.legs[0].phase,'CONFIRMED');
      assert.equal(init.headers['Idempotency-Key'],persisted.legs[i].key);
      assert.deepEqual(JSON.parse(init.body),{amount_minor:amount,currency:'EUR',reason_code:'REQUESTED_BY_CUSTOMER'});
      assert.equal(trace.at(-2),'listener');posts.push(amount);
      if(changes.loseRefund)throw Error('session_canary '+secret);
      const refundId=uuid(20+i),ref='re_SYNTHETIC'+i;
      d.refunds.push({id:refundId,amount_minor:amount,currency:'EUR',status:'SUCCEEDED',
        provider_refund_reference:ref,reason_code:'REQUESTED_BY_CUSTOMER',created_at:p.created_at,updated_at:p.updated_at});
      const e=event(30+i,'REFUND_SUCCEEDED',amount,ref);d.events.push(e);d.outbox_events.push(out(e));d.deliveries.push(delivery(e));
      receipt.events.push({event_id:e.id,event_type:'refund.succeeded',count:2});receipt.count++;receipt.duplicates++;
      if(receiverOrigin) {
        const body=JSON.stringify({schema_version:1,event_id:e.id,event_type:'refund.succeeded',site_id:site.id,
          payment_id:target.paymentId,payment_status:i?'REFUNDED':'PARTIALLY_REFUNDED',external_order_id:null,
          amount_minor:amount,currency:'EUR',occurred_at:p.created_at});
        const timestamp=String(Math.floor(Date.now()/1000));
        const signature=createHmac('sha256',Buffer.from(secret,'base64url')).update(timestamp+'.'+body).digest('hex');
        for(let copy=0;copy<2;copy++) {
          const delivered=await fetch(receiverOrigin+'/callback',{method:'POST',headers:{'content-type':'application/json',
            'payment-event-id':e.id,'payment-timestamp':timestamp,'payment-secret-version':'2','payment-signature':'v1='+signature},body});
          assert.equal(delivered.status,204);await delivered.body?.cancel();
        }
      }
      p.status=i?'REFUNDED':'PARTIALLY_REFUNDED';p.refunded_amount_minor=i?2000:700;p.refundable_amount_minor=i?0:1300;
      operations.push(op(uuid(40+i),'REFUND_CREATE',refundId,ref));
      changes.afterRefund?.({d,receipt,operations,i});
      return response({refund_id:refundId,payment_id:target.paymentId,amount_minor:amount,currency:'EUR',status:'PROCESSING'},202);
    }
    throw Error('unexpected_route '+path);
  }
  let runtimeBusy=false;
  const runtime={resources:()=>target,verify:async()=>{
    if(changes.exclusiveRuntime&&runtimeBusy)throw Error('runtime_reentry');return true;
  },withAdminCredentials:async(cb)=>{
    runtimeBusy=true;
    try{return await cb({username:f.data.app.PAYMENT_ADMIN_BOOTSTRAP_USERNAME,password:f.data.app.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD});}
    finally{runtimeBusy=false;}
  }};
  const cli={validateBinding:async()=>{trace.push('binding');return true;},
    assertRefundListener:()=>{trace.push('listener');if(!listener)throw Error('listener_dead');return true;},
    inspectRefund:async(value,context)=>{trace.push('provider:'+value.amountMinor);await changes.provider?.(value,context);return Object.freeze({providerVerified:true,webhookVerified:true});}};
  const wrappedState=changes.state?changes.state(state,{trace,killListener:()=>{listener=false;}}):state;
  const session=await createManualRefundSession({runtime,cli,state:wrappedState},{fetch:fetcher,probeDelay:0,...changes.dependencies});
  t.after(async()=>{await session.close({deadline:limits.deadline+120000}).catch(()=>{});await state.close({retain:true},{deadline:limits.deadline+120000}).catch(()=>{});});
  return {session,state,f,limits,d,receipt,operations,trace,posts,account,site,price,catalog,
    changeAdmin:()=>{adminId=uuid(101);},killListener:()=>{listener=false;}};
}

test('inert constructor then durable rotation and ordered complete financial proofs',async t=>{
  const h=await harness(t);assert.deepEqual(h.trace,[]);
  await h.session.verifyBaseline(h.limits);await h.session.rotateCallback(h.limits);
  const first=await h.session.refundPartial(h.limits);assert.equal(first.amountMinor,700);assert.equal(first.callbackVerified,true);
  const second=await h.session.refundRemainder(h.limits);assert.equal(second.amountMinor,1300);
  assert.deepEqual(await h.session.verifyFinal(h.limits),{partialConfirmed:true,remainderConfirmed:true,
    providerVerified:true,webhookVerified:true,journalVerified:true,callbackVerified:true});
  assert.deepEqual(h.posts,[700,1300]);
  assert.equal(h.trace.filter(v=>v.endsWith('/rotate-secret')).length,1);
  assert.equal(h.trace.filter(v=>v==='POST /configure').length,1);
  assert.ok(!JSON.stringify(JSON.parse(await readFile(h.f.statePath))).includes(secret));
});

for(const [name,changes] of [
  ['lost rotation',{loseRotate:true}],['lost receiver configure',{loseConfigure:true}],
  ['wrong site',{rotation:{site_id:uuid(199)}}],['wrong endpoint',{rotation:{endpoint_url:'http://evil/callback'}}],
  ['disabled callback',{rotation:{enabled:false}}],['wrong version',{rotation:{secret_version:3}}],
  ['lost durable rotation receipt',{state:s=>({...s,ackRotation:async()=>{throw Error(secret);}})}],
  ['lost durable configure receipt',{state:s=>({...s,ackConfigure:async()=>{throw Error(secret);}})}]
])test(name+' permanently denies repeated rotation and all refunds',async t=>{
  const h=await harness(t,changes);await h.session.verifyBaseline(h.limits);
  await assert.rejects(h.session.rotateCallback(h.limits),fail);
  await assert.rejects(h.session.rotateCallback(h.limits),fail);
  await assert.rejects(h.session.refundPartial(h.limits),fail);
  assert.equal(h.trace.filter(v=>v.endsWith('/rotate-secret')).length,1);assert.deepEqual(h.posts,[]);
});

for(const [name,mutate] of [
  ['foreign pending operation',h=>h.operations.push({...h.operations[0],operation_id:uuid(299),status:'DISPATCHING'})],
  ['wrong site',h=>{h.d.payment.site_id=uuid(299);}],['preexisting refund',h=>h.d.refunds.push({id:uuid(299)})],
  ['wrong catalog provider price',h=>{h.price.provider_price_id='price_FOREIGN';}],
  ['extra catalog product',h=>h.catalog.products.push({...h.catalog.products[0],id:uuid(299)})],
  ['duplicate payment success',h=>h.d.events.push(clone(h.d.events.at(-1)))],
  ['unpaid attempt',h=>{h.d.attempts[0].status='PROCESSING';}],
  ['reconciliation payment',h=>{h.d.events.at(-1).source='RECONCILIATION';}],
])test('baseline denies '+name,async t=>{
  const h=await harness(t);mutate(h);await assert.rejects(h.session.verifyBaseline(h.limits),fail);
  assert.deepEqual(h.posts,[]);assert.equal(h.trace.some(v=>v.endsWith('/rotate-secret')),false);
});

for(const [name,changes] of [
  ['unknown POST response',{loseRefund:true}],
  ['acknowledgement without confirmation',{afterRefund:({d})=>{d.refunds[0].status='PROCESSING';}}],
  ['reconciliation success',{afterRefund:({d})=>{d.events.at(-1).source='RECONCILIATION';}}],
  ['wrong provider reference',{afterRefund:({d})=>{d.events.at(-1).provider_object_id='re_FOREIGN';}}],
  ['wrong outbox ID',{afterRefund:({d})=>{d.outbox_events.at(-1).id=uuid(499);}}],
  ['wrong delivery ID',{afterRefund:({d})=>{d.deliveries.at(-1).outbox_event_id=uuid(499);}}],
  ['wrong receiver ID',{afterRefund:({receipt})=>{receipt.events[0].event_id=uuid(499);}}],
  ['duplicate money event',{afterRefund:({d})=>d.events.push({...clone(d.events.at(-1)),id:uuid(499)})}],
  ['duplicate refund operation',{afterRefund:({operations})=>operations.push({...operations.at(-1),operation_id:uuid(499)})}],
  ['independent provider denial',{provider:()=>{throw Error('private_provider_canary');}}],
  ['lost durable confirmation',{state:s=>({...s,confirmRefund:async()=>{throw Error('fsync');}})}],
])test(name+' permits one partial POST and permanently denies remainder or replay',async t=>{
  const h=await harness(t,changes);await h.session.verifyBaseline(h.limits);await h.session.rotateCallback(h.limits);
  await assert.rejects(h.session.refundPartial(h.limits),fail);
  await assert.rejects(h.session.refundRemainder(h.limits),fail);await assert.rejects(h.session.refundPartial(h.limits),fail);
  assert.deepEqual(h.posts,[700]);
});

test('fresh actor identity and listener are required before both refund dispatches',async t=>{
  const h=await harness(t);await h.session.verifyBaseline(h.limits);await h.session.rotateCallback(h.limits);
  await h.session.refundPartial(h.limits);h.changeAdmin();
  await assert.rejects(h.session.refundRemainder(h.limits),fail);assert.deepEqual(h.posts,[700]);
});

test('signed real receiver callbacks, including transport duplicates, prove both legs',async t=>{
  const h=await harness(t,{realReceiver:true});await h.session.verifyBaseline(h.limits);await h.session.rotateCallback(h.limits);
  await h.session.refundPartial(h.limits);await h.session.refundRemainder(h.limits);
  assert.equal((await h.session.verifyFinal(h.limits)).callbackVerified,true);
});

test('login uses the exclusive runtime credential callback without reentering runtime verification',async t=>{
  const h=await harness(t,{exclusiveRuntime:true});assert.equal(await h.session.verifyBaseline(h.limits),true);
});

test('listener loss during durable beginRefund prevents actual HTTP dispatch',async t=>{
  const h=await harness(t,{state:(s,{killListener})=>({...s,beginRefund:async(...args)=>{
    const persisted=await s.beginRefund(...args);killListener();return persisted;
  }})});
  await h.session.verifyBaseline(h.limits);await h.session.rotateCallback(h.limits);
  await assert.rejects(h.session.refundPartial(h.limits),fail);assert.deepEqual(h.posts,[]);
  assert.equal(JSON.parse(await readFile(h.f.statePath)).legs[0].phase,'DISPATCHING');
});

test('lost acknowledgement commit prevents replay and the second leg',async t=>{
  const h=await harness(t,{state:s=>({...s,ackRefund:async()=>{throw Error('private_fsync_canary');}})});
  await h.session.verifyBaseline(h.limits);await h.session.rotateCallback(h.limits);
  await assert.rejects(h.session.refundPartial(h.limits),fail);
  await assert.rejects(h.session.refundRemainder(h.limits),fail);assert.deepEqual(h.posts,[700]);
});

test('final checks reject replaced first event identity despite matching money',async t=>{
  const h=await harness(t);await h.session.verifyBaseline(h.limits);await h.session.rotateCallback(h.limits);
  await h.session.refundPartial(h.limits);await h.session.refundRemainder(h.limits);
  h.d.events.find(e=>e.event_type==='REFUND_SUCCEEDED').provider_event_id='evt_REPLACEMENT';
  await assert.rejects(h.session.verifyFinal(h.limits),fail);assert.deepEqual(h.posts,[700,1300]);
});

test('abort owns late HTTP response and drains its body before close resolves',async t=>{
  let resolveResponse,enteredResolve,cancelled=false;
  const entered=new Promise(resolve=>{enteredResolve=resolve;});
  const h=await harness(t,{transport:({path})=>{
    if(path==='/api/admin/v1/auth/session') {
      enteredResolve();return new Promise(resolve=>{resolveResponse=resolve;});
    }
  }});
  const controller=new AbortController();
  const pending=h.session.verifyBaseline({...h.limits,signal:controller.signal});
  await entered;controller.abort();await assert.rejects(pending,fail);
  let closeDone=false;
  const closing=h.session.close({deadline:h.limits.deadline+120000}).then(value=>{closeDone=true;return value;});
  await new Promise(resolve=>setImmediate(resolve));assert.equal(closeDone,false);
  resolveResponse(new Response(new ReadableStream({cancel(){cancelled=true;}})));
  assert.equal(await closing,true);assert.equal(cancelled,true);
  const count=h.trace.length;await assert.rejects(h.session.rotateCallback(h.limits),fail);assert.equal(h.trace.length,count);
});

test('aborted response body remains owned until cancel and read settle',async t=>{
  let enteredResolve,cancelResolve,cancelled=false;
  const entered=new Promise(resolve=>{enteredResolve=resolve;});
  const h=await harness(t,{transport:({path})=>{
    if(path==='/api/admin/v1/auth/session')return new Response(new ReadableStream({
      pull(){enteredResolve();},cancel(){cancelled=true;return new Promise(resolve=>{cancelResolve=resolve;});}
    }));
  }});
  const controller=new AbortController();
  const pending=h.session.verifyBaseline({...h.limits,signal:controller.signal});
  await entered;controller.abort();await assert.rejects(pending,fail);
  let closeDone=false;
  const closing=h.session.close({deadline:h.limits.deadline+120000}).then(value=>{closeDone=true;return value;});
  await new Promise(resolve=>setImmediate(resolve));assert.equal(closeDone,false);assert.equal(cancelled,true);
  cancelResolve();assert.equal(await closing,true);
});

test('oversized protected response stops before mutation and never exposes canaries',async t=>{
  const h=await harness(t,{transport:({path})=>path==='/api/admin/v1/auth/session'?new Response('session_canary'+secret+'x'.repeat(65536)):undefined});
  await assert.rejects(h.session.verifyBaseline(h.limits),error=>{
    assert.equal(error.message,'stripe_manual_refund_session_UNCONFIRMED');return true;
  });assert.deepEqual(h.posts,[]);
});

test('independent provider read cannot extend the original 45 second proof phase',async t=>{
  let current=Date.now(),releaseProvider,enteredResolve,providerLimits,lateProviderReads=0;
  const timers=new Map(),entered=new Promise(resolve=>{enteredResolve=resolve;});
  const h=await harness(t,{dependencies:{now:()=>current,
    setTimeout:(body,delay)=>{const token={};timers.set(token,{body,at:current+delay});return token;},
    clearTimeout:token=>timers.delete(token)},
    provider:async(value,context)=>{
      providerLimits=context;enteredResolve();await new Promise(resolve=>{releaseProvider=resolve;});
      if(!context.signal?.aborted)lateProviderReads++;
    }});
  await h.session.verifyBaseline(h.limits);await h.session.rotateCallback(h.limits);
  const controller=new AbortController();let settled=false;
  const pending=h.session.refundPartial({...h.limits,signal:controller.signal});
  pending.then(()=>{settled=true;},()=>{settled=true;});
  await entered;current+=45000;
  for(const [token,timer] of [...timers])if(timer.at<=current){timers.delete(token);timer.body();}
  await new Promise(resolve=>setImmediate(resolve));const bounded=settled,cancelled=providerLimits.signal?.aborted;
  controller.abort();releaseProvider();await assert.rejects(pending,fail);
  assert.equal(bounded,true,'proof phase must stop waiting at its original 45 second ceiling');
  assert.equal(providerLimits.deadline,h.limits.deadline);assert.equal(cancelled,true,'phase expiration must abort delegated provider work');
  assert.equal(lateProviderReads,0);
  await assert.rejects(h.session.refundRemainder(h.limits),fail);assert.deepEqual(h.posts,[700]);
});
