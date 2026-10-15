import test from 'node:test';
import assert from 'node:assert/strict';
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

async function fixture(t,{alter,redirect=false,oversize=false,never=false,accountPatch={},slowBody=false,lostRefundReply=false}={}) {
  const directory=await mkdtemp(join(tmpdir(),'task16-smoke-'));
  const metadata={runId:randomUUID(),owners:{project:'stripe-acceptance-fixture',backend:'a'.repeat(64),demo:'b'.repeat(64),receiver:'c'.repeat(64)},
    endpoints:{backend:'',demo:'http://127.0.0.1:4173',receiver:''},account:{id:randomUUID(),key:'stripe-acceptance',externalId:'acct_owned'},
    site:{id:randomUUID(),key:'stripe-acceptance'},price:{id:randomUUID(),key:'stripe-price'},cases:{}};
  const details={},requests=[];
  for(const name of ['success','decline','sca']) {
    const paymentId=randomUUID(),checkoutId=randomUUID(),event=randomUUID();metadata.cases[name]={paymentId,checkoutId};
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
  return {directory,metadata,options,details,requests,statePath:join(directory,'test-results','stripe-sandbox',metadata.runId,'state.json')};
}

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
  const f=await fixture(t);assert.deepEqual(await runStripeSandboxSmoke(f.options),{paymentConfirmed:true,refundConfirmed:true,callbackVerified:true});
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
  const f=await fixture(t,{lostRefundReply:true});await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_HTTP');
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
  const f=await fixture(t),state=JSON.parse(await readFile(f.statePath,'utf8'));state.refunds[0].phase='DISPATCHING';
  await writeFile(f.statePath,JSON.stringify(state),{mode:0o600});
  await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_PENDING_REFUND');
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,0);
  assert.equal(JSON.parse(await readFile(f.statePath,'utf8')).refunds[0].key,state.refunds[0].key);
});
test('pending acknowledged refund exhausts finite reads without another POST or remaining refund',async t=>{
  const f=await fixture(t,{never:true});await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_PENDING_REFUND');
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,1);
  const before=f.requests.length;await assert.rejects(runStripeSandboxSmoke(f.options),e=>e.message==='stripe_sandbox_PENDING_REFUND');
  assert.equal(f.requests.filter(r=>r.path.endsWith('/refunds')).length,1);assert.equal(f.requests.length-before,13);
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
  process.stdout.write=function(value){lines.push(String(value));return true;};
  try {
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
