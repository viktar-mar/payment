import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';

const owned = await import('./smoke-stage-2-owned.mjs').catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
  throw error;
});
const ownedEnv = {PAYMENT_SMOKE_ENV_ONLY:'true',PAYMENT_E2E_COMPOSE_PROJECT:'task17-owned',PAYMENT_SMOKE_BACKEND_OWNER:'a'.repeat(64),PAYMENT_SMOKE_RECEIVER_OWNER:'b'.repeat(64),PAYMENT_SMOKE_LOG_SINCE:'2026-10-12T00:00:00.000Z'};

test('ENV-only Stage2 receiver rejects nonlocal endpoints and redirects without following',async()=>{
  assert.equal(typeof owned.stage2OwnedReceiver,'function','owned receiver transport exists');
  for(const url of ['https://example.test','http://localhost:8080','http://127.0.0.1:8080/private'])await assert.rejects(owned.stage2OwnedReceiver({...ownedEnv,PAYMENT_SMOKE_RECEIVER_URL:url},'/inspection','GET',200));
  let followed=0;const server=http.createServer((req,res)=>{if(req.url==='/inspection'){res.writeHead(302,{location:'/private'});res.end();}else{followed++;res.end('{}');}});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{await assert.rejects(owned.stage2OwnedReceiver({...ownedEnv,PAYMENT_SMOKE_RECEIVER_URL:`http://127.0.0.1:${server.address().port}`},'/inspection','GET',200));assert.equal(followed,0);}
  finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});

test('ENV-only Stage2 logs require exact ownership before bounded ID-only log reads',async()=>{
  assert.equal(typeof owned.stage2OwnedLogs,'function','owned log transport exists');
  for(const mismatch of ['wrong-project','wrong-service','wrong-id']){
    const calls=[];await assert.rejects(owned.stage2OwnedLogs(ownedEnv,async(_file,args)=>{calls.push(args);return {stdout:mismatch};}));assert.equal(calls.length,1);
  }
  const calls=[];const result=await owned.stage2OwnedLogs(ownedEnv,async(file,args,options)=>{
    assert.equal(file,'docker');assert.ok(options.timeout<=10000);assert.ok(options.maxBuffer<=8388608);calls.push(args);
    const id=args.at(-1),service=id===ownedEnv.PAYMENT_SMOKE_BACKEND_OWNER?'backend':'callback-receiver';
    return {stdout:args[0]==='inspect'?`${id}|task17-owned|${service}`:'safe',stderr:''};
  });
  assert.equal(result,'safesafe');assert.deepEqual(calls.slice(0,2).map(args=>args[0]),['inspect','inspect']);
  assert.deepEqual(calls.slice(2).map(args=>args.slice(0,-1)),[['logs','--since',ownedEnv.PAYMENT_SMOKE_LOG_SINCE,'--tail','2000'],['logs','--since',ownedEnv.PAYMENT_SMOKE_LOG_SINCE,'--tail','2000']]);
  assert.equal(calls.some(args=>args.includes('compose')||args.includes('--env-file')),false);
});

test('owned manual settlement preserves one explicit local Fake intent',async()=>{
  assert.equal(typeof owned.stage2OwnedSettlement,'function','owned manual settlement exists');
  const calls=[];await owned.stage2OwnedSettlement({...ownedEnv,PAYMENT_FAKE_AUTO_SUCCESS:'false'},'11111111-1111-4111-8111-111111111111',async(...args)=>calls.push(args));
  assert.equal(calls.length,1);assert.deepEqual(calls[0].slice(0,3),['/api/v1/admin/payments/11111111-1111-4111-8111-111111111111/fake-events',{target:'PAYMENT',event_type:'PAYMENT_SUCCEEDED',sequence:7},202]);
  assert.match(calls[0][3]['Idempotency-Key'],/^[0-9a-f-]{36}$/);
  await owned.stage2OwnedSettlement({...ownedEnv,PAYMENT_FAKE_AUTO_SUCCESS:'true'},'11111111-1111-4111-8111-111111111111',async()=>assert.fail('auto success is unchanged'));
  await assert.rejects(owned.stage2OwnedSettlement({...ownedEnv,PAYMENT_SMOKE_ENV_ONLY:'false',PAYMENT_FAKE_AUTO_SUCCESS:'false'},'11111111-1111-4111-8111-111111111111',async()=>assert.fail('not owned')));
});

test('smoke fails closed on redirects without following or revealing credentials', async () => {
  let followed=0,authReached=0; const password=randomBytes(24).toString('hex');
  const server=http.createServer((req,res)=>{
    if(req.url==='/actuator/health/readiness'){res.writeHead(200,{'content-type':'application/json'});res.end('{"status":"UP"}');}
    else if(req.url==='/api/admin/v1/auth/sessions'){authReached++;res.writeHead(302,{location:'/credential-sink'});res.end();}
    else {followed++;res.writeHead(500);res.end();}
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {
    const result=await new Promise(resolve=>{
      const child=spawn('bash',['infrastructure/smoke-stage-2.sh'],{cwd:new URL('..',import.meta.url),env:{...process.env,NODE_OPTIONS:'--import=./infrastructure/fixtures/smoke-env-read-trap.mjs',PAYMENT_SMOKE_ENV_ONLY:'true',PAYMENT_SMOKE_BACKEND_URL:`http://127.0.0.1:${server.address().port}`,PAYMENT_ADMIN_BOOTSTRAP_USERNAME:'admin',PAYMENT_ADMIN_BOOTSTRAP_PASSWORD:password,PAYMENT_SMOKE_SITE_KEY:'smoke-test'}});
      let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);child.on('close',code=>resolve({code,output}));
    });
    assert.equal(result.code,1);assert.equal(authReached,1);assert.equal(followed,0);assert.ok(!result.output.includes(password));assert.match(result.output,/FAIL/);
  } finally {await new Promise(resolve=>server.close(resolve));}
});
