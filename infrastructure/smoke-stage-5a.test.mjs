import test from 'node:test';
import assert from 'node:assert/strict';

test('all smoke exports reject raw remainder mode before any private or execute access',async()=>{
  let reads=0;const api=await import('./smoke-stage-5a.mjs');
  for(const shape of ['true','malformed','accessor','hidden','inherited']){
    const env=shape==='inherited'?Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'}):{};
    if(shape!=='inherited')Object.defineProperty(env,'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',shape==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:shape!=='hidden',value:shape==='malformed'?false:'true'});
    Object.assign(env,{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'});
    for(const key of ['PATH','HOME','PAYMENT_STRIPE_SANDBOX_RUN_ID','PAYMENT_SANDBOX_ADMIN_USERNAME'])Object.defineProperty(env,key,{get(){reads++;throw null;}});
    const input={env,get rootDirectory(){reads++;throw null;},get fetch(){reads++;throw null;}};
    for(const call of [()=>api.initializeStripeSandboxState({},input),()=>api.openStripeSandboxFixture(input),()=>api.runStripeSandboxSmoke(input),()=>api.verifyStripeSandboxOwnership({},env,()=>{reads++;throw null;})])await assert.rejects(call,/OPT_IN_REQUIRED/);
  }
  assert.equal(reads,0);
});
import {runStripeSandboxSmoke} from './smoke-stage-5a.mjs';
import * as sandbox from './smoke-stage-5a.mjs';
import {mkdtemp,readFile,writeFile,symlink,stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import http from 'node:http';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
const flags={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'};
test('smoke rejects new payment authority before fixture/private getters',async()=>{let touched=0;await assert.rejects(runStripeSandboxSmoke({env:{...flags,PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true'},get statePath(){touched++;throw null;}}));assert.equal(touched,0);});

async function fixture(t,{alter,redirect=false,oversize=false,never=false,accountPatch={},slowBody=false,lostRefundReply=false}={}) {
  const directory=await mkdtemp(join(tmpdir(),'task16-smoke-'));
  const metadata={runId:randomUUID(),owners:{project:'stripe-acceptance-fixture',backend:'a'.repeat(64),demo:'b'.repeat(64),receiver:'c'.repeat(64)},
    endpoints:{backend:'',demo:'http://127.0.0.1:4173',receiver:''},account:{id:randomUUID(),key:'stripe-acceptance',externalId:'acct_owned'},
    site:{id:randomUUID(),key:'stripe-acceptance'},price:{id:randomUUID(),key:'stripe-price'},cases:{}};
  const details={},operations={},requests=[];
  for(const name of ['success','decline','sca']) {
    const paymentId=randomUUID(),checkoutId=randomUUID(),event=randomUUID();metadata.cases[name]={paymentId,checkoutId};
    operations[name]=[{operation_id:randomUUID(),payment_id:paymentId,account_id:metadata.account.id,account_mode:'SANDBOX',kind:'PAYMENT_PREPARE',status:'ACKNOWLEDGED',reference:'pi_'+name,
      first_dispatched_at:'2026-10-15T10:00:00Z',retry_before:'2026-10-16T09:00:00Z',next_attempt_at:null,safe_error_code:null,resource_id:paymentId,site_id:metadata.site.id,manual_review_required:false}];
    details[name]={payment:{payment_id:paymentId,site_id:metadata.site.id,payment_account_id:metadata.account.id,provider:'STRIPE',source:'CATALOG',price_id:metadata.price.id,
      price_key:metadata.price.key,amount_minor:2000,currency:'EUR',status:'SUCCEEDED',refunded_amount_minor:0},attempts:[],refunds:[],
      events:[{id:event,event_type:'PAYMENT_SUCCEEDED',amount_minor:2000,currency:'EUR',provider_object_id:'pi_'+name}],
      outbox_events:[{id:event,callback_type:'payment.succeeded',status:'DELIVERED'}],deliveries:[{outbox_event_id:event,status:'SUCCEEDED',http_status:204}]};
  }
  const verified=()=>Object.values(details).flatMap(d=>d.outbox_events.map(e=>({event_id:e.id,event_type:e.callback_type,count:2})));
  const server=http.createServer(async(req,res)=>{
    const body=[];for await(const b of req)body.push(b);requests.push({method:req.method,path:req.url,key:req.headers['idempotency-key']});
    const reply=(status,value,headers={})=>{res.writeHead(status,{'content-type':'application/json',...headers});res.end(value===undefined?'':JSON.stringify(value));};
    if(redirect)return reply(302,{}, {location:'http://example.invalid/private-fixture-marker'});
    if(oversize){res.writeHead(200);res.end('private-fixture-marker'.repeat(10000));return;}
    if(req.url==='/api/admin/v1/auth/sessions')return reply(204,undefined,{'set-cookie':['PAYMENT_ADMIN_SESSION=synthetic-session; Path=/','XSRF-TOKEN=synthetic-csrf; Path=/']});
    if(req.url==='/api/admin/v1/payment-accounts'){
      const value={id:metadata.account.id,key:metadata.account.key,provider:'STRIPE',mode:'SANDBOX',status:'ACTIVE',external_account_id:metadata.account.externalId,...accountPatch};
      if(slowBody){res.writeHead(200,{'content-type':'application/json'});res.write('[');setTimeout(()=>res.end(JSON.stringify(value)+']'),150);return;}
      return reply(200,[value]);
    }
    if(req.url==='/api/admin/v1/sites')return reply(200,[{id:metadata.site.id,key:metadata.site.key,payment_account_id:metadata.account.id,status:'ACTIVE',allowed_origins:[metadata.endpoints.demo]}]);
    if(req.url===`/api/admin/v1/sites/${metadata.site.id}/catalog`)return reply(200,{prices:[{id:metadata.price.id,price_key:metadata.price.key,type:'ONE_TIME',status:'PUBLISHED',amount_minor:2000,currency:'EUR'}]});
    if(req.url==='/inspection')return reply(200,{events:verified()});
    const operationName=Object.keys(operations).find(n=>req.url==='/api/v1/admin/provider-operations?payment_id='+metadata.cases[n].paymentId);
    if(operationName)return reply(200,operations[operationName]);
    const name=Object.keys(details).find(n=>req.url.startsWith('/api/v1/admin/payments/'+metadata.cases[n].paymentId));
    if(name) {
      const d=details[name];
      if(req.method==='POST') {
        const state=JSON.parse(await readFile(join(directory,'test-results','stripe-sandbox',metadata.runId,'state.json'),'utf8'));
        const input=JSON.parse(Buffer.concat(body).toString()),index=input.amount_minor===700?0:1;
        assert.equal(state.refunds[index].phase,'DISPATCHING');assert.equal(state.refunds[index].key,req.headers['idempotency-key']);
        const id=randomUUID(),event=randomUUID(),reference='re_owned'+index;
        d.refunds.push({id,amount_minor:input.amount_minor,currency:'EUR',provider_refund_reference:reference,status:never?'PENDING':'SUCCEEDED'});
        if(!never){d.payment.refunded_amount_minor+=input.amount_minor;d.payment.status=index===0?'PARTIALLY_REFUNDED':'REFUNDED';
          d.events.push({id:event,event_type:'REFUND_SUCCEEDED',amount_minor:input.amount_minor,currency:'EUR',provider_object_id:reference});
          d.outbox_events.push({id:event,callback_type:'refund.succeeded',status:'DELIVERED'});d.deliveries.push({outbox_event_id:event,status:'SUCCEEDED',http_status:204});}
        if(lostRefundReply){res.destroy();return;}
        return reply(202,{refund_id:id});
      }
      const value=structuredClone(d);alter?.(value,name);return reply(200,value);
    }
    return reply(404,{message:'private-fixture-marker'});
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
  metadata.endpoints.backend=metadata.endpoints.receiver='http://127.0.0.1:'+server.address().port;
  const env={...flags,PAYMENT_STRIPE_SANDBOX_RUN_ID:metadata.runId,PAYMENT_SANDBOX_ADMIN_USERNAME:'synthetic-admin',PAYMENT_SANDBOX_ADMIN_PASSWORD:'synthetic-password'};
  const options={env,rootDirectory:directory,verifyOwnership:async()=>true,pollAttempts:2,pollDelayMs:1};
  await sandbox.initializeStripeSandboxState(metadata,options);
  return {directory,metadata,options,details,operations,requests,statePath:join(directory,'test-results','stripe-sandbox',metadata.runId,'state.json')};
}

function declineReady(f) {
  const d=f.details.decline,settlement=structuredClone(d);
  d.payment.status='REQUIRES_PAYMENT_METHOD';d.events=[];d.outbox_events=[];d.deliveries=[];
  const attempt=(number,status)=>({id:randomUUID(),attempt_number:number,status,provider_intent_reference:'pi_decline',payment_method_family:'CARD',result_code:status==='FAILED'?'PROVIDER_FAILED':null,
    created_at:'2026-10-15T10:00:00Z',updated_at:'2026-10-15T10:00:01Z'});
  d.attempts=[attempt(1,'FAILED'),attempt(2,'CREATED')];
  return {d,settle(reference='pi_decline'){Object.assign(d,{...settlement,attempts:d.attempts});d.events[0].provider_object_id=reference;}};
}

test('unchanged historical FAILED attempt cannot authorize a second Pay',async t=>{
  const f=await fixture(t);declineReady(f);const session=await sandbox.openStripeSandboxFixture(f.options);
  try {await session.beginBrowser('decline');await session.markPay('decline');
    await assert.rejects(session.markPay('decline',true),e=>e.message==='stripe_sandbox_MANUAL_CHECK_DECLINE');
    assert.equal(JSON.parse(await readFile(f.statePath,'utf8')).cases.decline.phase,'CONFIRMING');
  }finally{await session.close();}
});
test('different-intent settlement cannot complete a freshly verified decline retry',async t=>{
  const f=await fixture(t),journey=declineReady(f),session=await sandbox.openStripeSandboxFixture(f.options);
  try {await session.beginBrowser('decline');await session.markPay('decline');journey.d.attempts[1].status='FAILED';
    await session.markPay('decline',true);journey.settle('pi_other');
    await assert.rejects(session.confirm('decline'),e=>/^stripe_sandbox_[A-Z_]+$/.test(e.message));
    assert.equal(JSON.parse(await readFile(f.statePath,'utf8')).cases.decline.phase,'RETRYING');
  }finally{await session.close();}
});

async function verifiedDecline(f) {
  const journey=declineReady(f),session=await sandbox.openStripeSandboxFixture(f.options);
  try {await session.beginBrowser('decline');await session.markPay('decline');
    journey.d.attempts[1].status='FAILED';await session.markPay('decline',true);journey.settle();
  }finally{await session.close();}
  return journey;
}
test('same-intent decline proof is durable before Pay and resumes with no browser replay',async t=>{
  const f=await fixture(t),journey=declineReady(f),session=await sandbox.openStripeSandboxFixture(f.options);
  try {await session.beginBrowser('decline');await session.markPay('decline');
    const state=JSON.parse(await readFile(f.statePath,'utf8'));
    assert.equal(state.version,2);assert.equal(state.declineProof.intentReference,'pi_decline');
    assert.equal(state.declineProof.operationId,f.operations.decline[0].operation_id);
    assert.equal(state.declineProof.attemptId,journey.d.attempts[1].id);assert.equal(state.declineProof.failedVerified,false);
    journey.d.attempts[1].status='FAILED';await session.markPay('decline',true);journey.settle();
  }finally{await session.close();}
  const resumed=await sandbox.openStripeSandboxFixture(f.options);
  try {assert.equal(await resumed.beginBrowser('decline'),false);assert.equal(await resumed.confirm('decline'),true);
    await assert.rejects(resumed.markPay('decline'),e=>e.message==='stripe_sandbox_PENDING_BROWSER');
  }finally{await resumed.close();}
  assert.equal(f.requests.some(r=>r.path.includes('client-session')||r.path.endsWith('/refunds')),false);
});
for(const mismatch of ['reference','operation','account','site'])test('protected original '+mismatch+' change cannot pass resumed decline proof',async t=>{
  const f=await fixture(t);await verifiedDecline(f);
  const op=f.operations.decline[0];if(mismatch==='reference')op.reference='pi_other';if(mismatch==='operation')op.operation_id=randomUUID();
  if(mismatch==='account')op.account_id=randomUUID();if(mismatch==='site')op.site_id=randomUUID();
  const resumed=await sandbox.openStripeSandboxFixture(f.options);
  try{await assert.rejects(resumed.beginBrowser('decline'),e=>/^stripe_sandbox_[A-Z_]+$/.test(e.message));}finally{await resumed.close();}
  assert.equal(f.requests.some(r=>r.path.includes('client-session')||r.path.endsWith('/refunds')),false);
});
test('foreign historical intent reference prevents pre-Pay authorization',async t=>{
  const f=await fixture(t),journey=declineReady(f);journey.d.attempts[0].provider_intent_reference='pi_other';
  const session=await sandbox.openStripeSandboxFixture(f.options);
  try{await session.beginBrowser('decline');await assert.rejects(session.markPay('decline'),e=>/^stripe_sandbox_[A-Z_]+$/.test(e.message));}
  finally{await session.close();}
  assert.equal(JSON.parse(await readFile(f.statePath,'utf8')).cases.decline.phase,'PREPARING');
});
test('old state cannot be migrated or touch ownership and login',async t=>{
  const f=await fixture(t),state=JSON.parse(await readFile(f.statePath,'utf8'));state.version=1;delete state.declineProof;
  await writeFile(f.statePath,JSON.stringify(state),{mode:0o600});let ownershipReads=0;f.options.verifyOwnership=async()=>{ownershipReads++;return true;};
  await assert.rejects(sandbox.openStripeSandboxFixture(f.options),e=>e.message==='stripe_sandbox_STATE');
  assert.equal(ownershipReads,0);assert.equal(f.requests.length,0);
  assert.equal(JSON.parse(await readFile(f.statePath,'utf8')).version,1);
});
for(const invalid of ['terminal-only','multiple-current','not-latest'])test('pre-Pay baseline refuses '+invalid+' attempts',async t=>{
  const f=await fixture(t),journey=declineReady(f);
  if(invalid==='terminal-only')journey.d.attempts[1].status='FAILED';
  if(invalid==='multiple-current')journey.d.attempts[0].status='CREATED';
  if(invalid==='not-latest')journey.d.attempts[0].attempt_number=3;
  const session=await sandbox.openStripeSandboxFixture(f.options);
  try{await session.beginBrowser('decline');await assert.rejects(session.markPay('decline'),e=>e.message==='stripe_sandbox_PENDING_BROWSER');}
  finally{await session.close();}
});
test('interrupted first Pay cannot be replayed or credited as completed decline retry',async t=>{
  const f=await fixture(t),journey=declineReady(f),session=await sandbox.openStripeSandboxFixture(f.options);
  await session.beginBrowser('decline');await session.markPay('decline');await session.close();journey.settle();
  const resumed=await sandbox.openStripeSandboxFixture(f.options);
  try{await assert.rejects(resumed.beginBrowser('decline'),e=>e.message==='stripe_sandbox_PENDING_BROWSER');
    await assert.rejects(resumed.confirm('decline'),e=>e.message==='stripe_sandbox_PENDING_BROWSER');
    await assert.rejects(resumed.markPay('decline'),e=>e.message==='stripe_sandbox_PENDING_BROWSER');
  }finally{await resumed.close();}
  assert.equal(f.requests.some(r=>r.path.includes('client-session')||r.path.endsWith('/refunds')),false);
});
test('same journal intent cannot hide a foreign final attempt reference',async t=>{
  const f=await fixture(t),journey=await verifiedDecline(f);journey.d.attempts[0].provider_intent_reference='pi_other';
  const resumed=await sandbox.openStripeSandboxFixture(f.options);
  try{await assert.rejects(resumed.confirm('decline'),e=>e.message==='stripe_sandbox_PROOF');}finally{await resumed.close();}
});

for (const flags of [{}, {PAYMENT_STRIPE_SANDBOX_ENABLED:'true'}, {PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'},
  {PAYMENT_STRIPE_SANDBOX_ENABLED:'TRUE',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'}]) {
  test('missing explicit opt-in fails before dependency or credential access '+Object.keys(flags).join('-'), async()=>{
    let touched=0;
    const env=new Proxy(flags,{get(target,key){if(!(key in target)&&!String(key).startsWith('PAYMENT_STRIPE_SANDBOX_'))touched++;return target[key];}});
    let rejected=false;
    try {await runStripeSandboxSmoke({env,fetch:()=>{touched++;throw new Error('private-fixture-marker');}});}catch(error){rejected=error.message==='stripe_sandbox_OPT_IN_REQUIRED';}
    assert.equal(rejected,true);assert.equal(touched,0);
  });
}

test('actual bounded HTTP joins journal, delivered outbox and signature-verified receiver IDs before each refund',async t=>{
  const f=await fixture(t);await verifiedDecline(f);assert.deepEqual(await runStripeSandboxSmoke(f.options),{paymentConfirmed:true,refundConfirmed:true,callbackVerified:true});
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,2);
  const state=JSON.parse(await readFile(f.statePath,'utf8'));assert.deepEqual(state.refunds.map(r=>r.phase),['CONFIRMED','CONFIRMED']);
  assert.equal((await stat(f.statePath)).mode&0o777,0o600);
  assert.equal((await readFile(f.statePath,'utf8')).includes('synthetic-password'),false);
  await runStripeSandboxSmoke(f.options);assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,2);
});
test('complete-response deadline includes delayed body after successful response headers',async t=>{
  const f=await fixture(t,{slowBody:true});f.options.requestTimeoutMs=50;
  await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_HTTP');
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,0);
});
for(const accountPatch of [{mode:'LIVE'},{provider:'FAKE'},{external_account_id:'acct_foreign'}])test('original Account binding refuses unsupported mode/provider/identity before payment writes',async t=>{
  const f=await fixture(t,{accountPatch});await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_BINDING');
  assert.equal(f.requests.filter(r=>r.path.startsWith('/api/v1/admin/payments')).length,0);
});
test('lost refund response persists uncertainty and cannot dispatch again on restart',async t=>{
  const f=await fixture(t,{lostRefundReply:true});await verifiedDecline(f);await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_HTTP');
  const state=JSON.parse(await readFile(f.statePath,'utf8'));assert.equal(state.refunds[0].phase,'DISPATCHING');assert.equal(state.refunds[0].id,null);
  await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_PENDING_REFUND');
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,1);
});
for(const override of ['context','host'])test('remote Docker '+override+' cannot authorize local credential traffic',async t=>{
  const f=await fixture(t),calls=[];
  const dockerEnv=override==='context'?{DOCKER_CONTEXT:'remote-fixture'}:{DOCKER_HOST:'tcp://remote.invalid:2376'};
  f.options.verifyOwnership=state=>sandbox.verifyStripeSandboxOwnership(state,dockerEnv,async(cmd,args)=>{
    calls.push(args);
    if(args[0]==='context')return {stdout:JSON.stringify('ssh://remote.invalid')};
    const id=args.at(-1),name=Object.keys(state.owners).find(k=>state.owners[k]===id),service={backend:'backend',demo:'demo-site',receiver:'callback-receiver'}[name];
    return {stdout:[id,true,state.owners.project,service,{'8080/tcp':[{HostIp:'127.0.0.1',HostPort:new URL(state.endpoints[name]).port}]}].map(JSON.stringify).join('|')};
  });
  await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_OWNERSHIP');
  assert.equal(f.requests.length,0);assert.equal(calls.some(args=>args.includes('--host')),false);
});
test('local Docker endpoint is pinned for every narrow inspection without ambient context overrides',async t=>{
  const f=await fixture(t),calls=[],endpoint='unix:///private/tmp/owned-docker-fixture.sock';
  const env={PATH:process.env.PATH,HOME:process.env.HOME,DOCKER_CONTEXT:'local-fixture',DOCKER_HOST:'ssh://ignored.invalid',DEBUG:'private-fixture-marker'};
  const result=await sandbox.verifyStripeSandboxOwnership(f.metadata,env,async(cmd,args,options)=>{
    calls.push(args);assert.equal(cmd,'docker');assert.equal(options.env.DOCKER_CONTEXT,undefined);assert.equal(options.env.DOCKER_HOST,undefined);assert.equal(options.env.DEBUG,undefined);
    if(args[0]==='context'){env.DOCKER_CONTEXT='remote-after-resolution';env.DOCKER_HOST='tcp://changed.invalid:2376';return {stdout:JSON.stringify(endpoint)};}
    assert.deepEqual(args.slice(0,3),['--host',endpoint,'inspect']);
    assert.equal(args[4].includes('Config.Env'),false);
    const id=args.at(-1),name=Object.keys(f.metadata.owners).find(k=>f.metadata.owners[k]===id),service={backend:'backend',demo:'demo-site',receiver:'callback-receiver'}[name];
    return {stdout:[id,true,f.metadata.owners.project,service,{'8080/tcp':[{HostIp:'127.0.0.1',HostPort:new URL(f.metadata.endpoints[name]).port}]}].map(JSON.stringify).join('|')};
  });
  assert.equal(result,true);assert.equal(calls.length,4);
});
for(const mutation of ['provider','account','source','amount','event','callback','delivery'])test('rejects mismatched '+mutation+' proof without refund dispatch',async t=>{
  const f=await fixture(t,{alter(d){if(mutation==='provider')d.payment.provider='FAKE';if(mutation==='account')d.payment.payment_account_id=randomUUID();
    if(mutation==='source')d.payment.source='DONATION';if(mutation==='amount')d.payment.amount_minor=1999;
    if(mutation==='event')d.events[0].amount_minor=1999;if(mutation==='callback')d.outbox_events[0].id=randomUUID();if(mutation==='delivery')d.deliveries=[];}});
  await assert.rejects(runStripeSandboxSmoke(f.options),e=>/^stripe_sandbox_[A-Z_]+$/.test(e.message));
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,0);
});
for(const condition of ['redirect','oversize','owner','live','nonloopback','missing-owner'])test('rejects '+condition+' safely',async t=>{
  const f=await fixture(t,{redirect:condition==='redirect',oversize:condition==='oversize'});
  if(condition==='owner')f.options.verifyOwnership=async()=>false;
  if(condition==='live')f.options.env.PAYMENT_SANDBOX_ADMIN_PASSWORD='sk_live_private-fixture-marker';
  if(['nonloopback','missing-owner'].includes(condition)){
    const state=JSON.parse(await readFile(f.statePath,'utf8'));if(condition==='nonloopback')state.endpoints.backend='https://example.invalid';else state.owners.backend='';
    await writeFile(f.statePath,JSON.stringify(state),{mode:0o600});
  }
  await assert.rejects(runStripeSandboxSmoke(f.options),e=>/^stripe_sandbox_[A-Z_]+$/.test(e.message)&&!e.cause);
  if(!['redirect','oversize'].includes(condition))assert.equal(f.requests.length,0);
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,0);
});
test('unknown refund without recorded ID stays pending on resumed runs and retains its original key',async t=>{
  const f=await fixture(t);await verifiedDecline(f);const state=JSON.parse(await readFile(f.statePath,'utf8'));state.refunds[0].phase='DISPATCHING';
  await writeFile(f.statePath,JSON.stringify(state),{mode:0o600});
  await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_PENDING_REFUND');
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,0);
  assert.equal(JSON.parse(await readFile(f.statePath,'utf8')).refunds[0].key,state.refunds[0].key);
});
test('pending acknowledged refund exhausts finite reads without another POST or remaining refund',async t=>{
  const f=await fixture(t,{never:true});await verifiedDecline(f);await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_PENDING_REFUND');
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,1);
  const before=f.requests.length;await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_PENDING_REFUND');
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,1);assert.equal(f.requests.length-before,14);
});
test('PREPARING is durable before browser handoff and interrupted preparation is never replayed',async t=>{
  const f=await fixture(t);f.details.success.events=[];f.details.success.outbox_events=[];f.details.success.payment.status='REQUIRES_PAYMENT_METHOD';
  f.options.env.PAYMENT_SANDBOX_SUCCESS_CHECKOUT_TOKEN='A'.repeat(43);
  const session=await sandbox.openStripeSandboxFixture(f.options);await session.beginBrowser('success');
  assert.equal(JSON.parse(await readFile(f.statePath,'utf8')).cases.success.phase,'PREPARING');await session.close();
  const resumed=await sandbox.openStripeSandboxFixture(f.options);
  try{await assert.rejects(resumed.beginBrowser('success'),e=>e.message==='stripe_sandbox_PENDING_BROWSER');}finally{await resumed.close();}
  assert.equal(f.requests.some(r=>r.path.includes('client-session')),false);
});
test('exclusive lock rejects concurrent runs and symlink state is refused',async t=>{
  const f=await fixture(t),session=await sandbox.openStripeSandboxFixture(f.options);
  try{await assert.rejects(sandbox.openStripeSandboxFixture(f.options),e=>e.message==='stripe_sandbox_STATE');}finally{await session.close();}
  const other=join(f.directory,'copy.json');await writeFile(other,await readFile(f.statePath),{mode:0o600});
  const {unlink}=await import('node:fs/promises');await unlink(f.statePath);await symlink(other,f.statePath);
  await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_STATE');
});
test('Sandbox reporter emits failure status but never errors, iframe URLs, stdout or attachment content',async()=>{
  const {default:Reporter}=await import('../examples/demo-site/tests/support/stripe-sandbox-reporter.ts');
  const reporter=new Reporter(),lines=[],original=process.stdout.write;
  const secret='https://js.stripe.com/frame?client_secret=private-fixture-marker';
  process.stdout.write=function(...args){
    if(typeof args[0]!=='string')return original.apply(this,args);
    lines.push(args[0]);return true;
  };
  try {
    // A zero-byte binary write exercises protocol forwarding without adding protocol data.
    let binaryCallback=false;process.stdout.write(Buffer.alloc(0),()=>{binaryCallback=true;});
    await new Promise(resolve=>setImmediate(resolve));assert.equal(binaryCallback,true);
    reporter.onStdOut?.(secret);reporter.onStdErr?.(secret);reporter.onError?.({message:secret});
    reporter.onTestEnd?.({title:secret},{status:'failed',errors:[{message:secret}],attachments:[{body:Buffer.from(secret)}]});
    reporter.onEnd?.({status:'failed'});
  }finally{process.stdout.write=original;}
  assert.equal(lines.join('').includes(secret),false);
  assert.equal(lines.join('').includes('sandbox_case_1_failed'),true);
  assert.equal(lines.join('').includes('sandbox_result_failed cases=1 failures=2'),true);
});
test('default actual Playwright discovery excludes external Sandbox and retains offline coverage',async()=>{
  const {stdout}=await promisify(execFile)('pnpm',['exec','playwright','test','--list'],{cwd:fileURLToPath(new URL('../examples/demo-site',import.meta.url)),timeout:15000,maxBuffer:65536});
  assert.equal(stdout.includes('stripe-sandbox.spec.ts'),false);assert.equal(stdout.includes('stripe-return-offline.spec.ts'),true);
});
test('separate real-browser command refuses missing opt-in with only safe failure output',async()=>{
  const env={PATH:process.env.PATH,HOME:process.env.HOME,CI:'1'};
  let result;try{await promisify(execFile)('pnpm',['exec','playwright','test','--config','playwright.stripe-sandbox.config.ts'],{
    cwd:fileURLToPath(new URL('../examples/demo-site',import.meta.url)),env,timeout:20000,maxBuffer:65536});}catch(error){result=error;}
  assert.equal(result?.code,1);assert.equal(result.stdout.includes('sandbox_result_failed'),true);
  assert.equal(result.stdout.includes('stripe_sandbox_OPT_IN_REQUIRED'),false);
});
