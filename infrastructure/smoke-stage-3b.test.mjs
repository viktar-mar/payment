import { test } from 'node:test';
import assert from 'node:assert/strict';
import { callbackShape, boundedJson } from './smoke-stage-3b.mjs';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
const base={schema_version:1,event_id:'fixture',event_type:'payment.succeeded',site_id:'site',payment_id:'payment',external_order_id:null,payment_status:'SUCCEEDED',amount_minor:500,currency:'EUR',occurred_at:'2026-10-11T00:00:00Z'};
test('catalog v1 and Donation v2 have exact independent callback field sets',()=>{
  assert.equal(callbackShape(base,1),true);
  const donation={...base,schema_version:2,donation_id:'donation',campaign_key:'help',customer_id:null};
  assert.equal(callbackShape(donation,2),true);
  assert.equal(callbackShape({...donation,message:'non-sensitive-contract-canary'},2),false);
  assert.equal(callbackShape({...base,donation_id:'unexpected'},1),false);
  assert.equal(callbackShape(base,2),false);
});
test('bounded JSON accepts a finite response',async()=>assert.deepEqual(await boundedJson(new Response('{"ok":true}')),{ok:true}));
test('bounded JSON rejects declared and streamed oversize without payload diagnostics',async()=>{
  for(const response of [new Response('{}',{headers:{'content-length':'300000'}}),new Response('x'.repeat(300000))])
    await assert.rejects(boundedJson(response),{message:'response_limit'});
});
test('bounded JSON parse failures omit received content',async()=>{
  await assert.rejects(boundedJson(new Response('NONSECRET_PARSE_CANARY')),{message:'response_invalid'});
});
for(const kind of ['redirect','oversize','problem'])test(`Stage3B ENV-only child rejects ${kind} without following or printing payloads`,async()=>{
  let followed=0;const secret=randomBytes(24).toString('hex');
  const server=http.createServer((req,res)=>{
    if(req.url==='/actuator/health/readiness'){res.writeHead(200,{'content-type':'application/json'});res.end('{"status":"UP"}');return;}
    if(req.url==='/credential-sink')followed++;
    if(kind==='redirect'){res.writeHead(302,{location:'/credential-sink'});res.end();}
    else if(kind==='oversize'){res.writeHead(200,{'content-type':'application/json'});res.end('x'.repeat(300000));}
    else {res.writeHead(500,{'content-type':'application/json'});res.end(JSON.stringify({detail:secret}));}
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {
    const url=`http://127.0.0.1:${server.address().port}`;
    const result=await new Promise(resolve=>{
      const child=spawn(process.execPath,['--import','./infrastructure/fixtures/smoke-env-read-trap.mjs','infrastructure/smoke-stage-3b.mjs'],{cwd:new URL('..',import.meta.url),env:{...process.env,PAYMENT_SMOKE_BACKEND_URL:url,PAYMENT_SMOKE_MAILPIT_URL:url,PAYMENT_SMOKE_RECEIVER_URL:url,PAYMENT_SMOKE_SITE_KEY:'fixture-one',PAYMENT_SMOKE_SECOND_SITE_KEY:'fixture-two',PAYMENT_TELEMETRY_SITEKEYS:'fixture-one,fixture-two',PAYMENT_ADMIN_BOOTSTRAP_USERNAME:'fixture-admin',PAYMENT_ADMIN_BOOTSTRAP_PASSWORD:secret,PAYMENT_SMOKE_DATABASE_URL:'postgres://fixture:fixture@127.0.0.1:1/fixture'}});
      let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);child.on('close',code=>resolve({code,output}));
    });
    assert.equal(result.code,1);assert.equal(followed,0);assert.equal(result.output.includes(secret),false);
    assert.deepEqual(JSON.parse(result.output),{result:'FAIL',reason:'stage3b_smoke_verification_failed',phase:'login'});
  } finally {await new Promise(resolve=>server.close(resolve));}
});
