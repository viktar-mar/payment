import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

test('Stage1 entrypoint reports booleans only and never the published provider object',async()=>{
  const server=http.createServer((req,res)=>{
    if(req.url==='/actuator/health/readiness'){res.end(JSON.stringify({status:'UP'}));return;}
    if(req.url==='/api/admin/v1/auth/sessions'){res.writeHead(204,{'set-cookie':'XSRF-TOKEN=owned-xsrf; Path=/'});res.end();return;}
    const price={id:'owned-price',status:'PUBLISHED',provider_price_id:'fake_price_private-canary',amount_minor:1999,version:1};
    const value=req.method==='GET'?(req.url.endsWith('/catalog')?{prices:[price]}:[]):req.url.endsWith('/publish')?price:{id:req.url.endsWith('/prices')?'owned-price':'owned-id'};
    req.resume();res.setHeader('content-type','application/json');res.end(JSON.stringify(value));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const {stdout,stderr}=await promisify(execFile)('bash',['infrastructure/smoke-stage-1.sh'],{env:{...process.env,PAYMENT_API_BASE_URL:`http://127.0.0.1:${server.address().port}`,PAYMENT_ADMIN_BOOTSTRAP_USERNAME:'owned-user',PAYMENT_ADMIN_BOOTSTRAP_PASSWORD:'owned-password'},timeout:15000,maxBuffer:16384});
    assert.equal(stderr,'');assert.deepEqual(JSON.parse(stdout),{result:'PASS',health:true,authentication:true,published:true,catalog:true});
    for(const secret of ['private-canary','owned-password','owned-xsrf'])assert.equal(stdout.includes(secret),false);
  }finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});

// Dynamic discovery makes the initial RED an unmet executable contract, rather
// than a deliberately unreachable endpoint or module-loader error.
const implementation = await import('./smoke-stage-4.mjs').catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
  throw error;
});
test('every owned EMAIL_OTP Site has a provider before restart readiness is checked',async()=>{
  assert.equal(typeof implementation.configureStage4IdentityProviders,'function');
  const calls=[];
  await implementation.configureStage4IdentityProviders([{id:'first'},{id:'second'}],async(...args)=>calls.push(args));
  assert.deepEqual(calls,[['/api/v1/admin/sites/first/identity-providers',{provider_key:'local-email',mode:'EMAIL_OTP',sender_ref:'local-default',template_key:'otp-default'}],['/api/v1/admin/sites/second/identity-providers',{provider_key:'local-email',mode:'EMAIL_OTP',sender_ref:'local-default',template_key:'otp-default'}]]);
});
test('fresh Stage4 fixture time is monotonic and exactly representable by PostgreSQL',()=>{
  assert.equal(typeof implementation.stage4FixtureStartTime,'function');
  const real=Date.parse('2026-10-12T11:33:00Z');
  assert.equal(implementation.stage4FixtureStartTime('2026-10-12T11:33:22.167045961Z',real),'2026-10-12T11:33:22.168Z');
  assert.equal(implementation.stage4FixtureStartTime('2026-10-12T11:33:22.167000000Z',real),'2026-10-12T11:33:22.167Z');
  assert.equal(implementation.stage4FixtureStartTime('2026-10-12T11:32:00Z',real),'2026-10-12T11:33:00.000Z');
  assert.equal(implementation.stage4FixtureStartTime('2027-10-12T11:33:22Z',real),'2027-10-12T11:33:22.000Z');
  assert.throws(()=>implementation.stage4FixtureStartTime('invalid',real));
  assert.throws(()=>implementation.stage4FixtureStartTime('2026-10-12T11:33:22Z',NaN));
});
test('access cache expires at the exact valid_until boundary', () => {
  assert.equal(typeof implementation.accessCacheFresh, 'function', 'Stage4 cache freshness contract is implemented');
  const decision = { allowed: true, valid_until: '2026-10-12T12:05:00Z' };
  assert.equal(implementation.accessCacheFresh(decision, Date.parse('2026-10-12T12:04:59.999Z')), true);
  assert.equal(implementation.accessCacheFresh(decision, Date.parse('2026-10-12T12:05:00Z')), false);
  for (const value of [null, {}, { allowed: false, valid_until: decision.valid_until }, { allowed: true, valid_until: 'invalid' }]) {
    assert.equal(implementation.accessCacheFresh(value, 0), false);
  }
});

test('finite Stage4 smoke rejects missing ownership and nonlocal configuration before any request',async()=>{
  assert.equal(typeof implementation.runSmoke,'function');
  for(const env of [{},{PAYMENT_SMOKE_BACKEND_URL:'https://example.test'},{PAYMENT_SMOKE_ENV_ONLY:'false'}]) {
    await assert.rejects(implementation.runSmoke(env),error=>error.message==='stage4_smoke_verification_failed'&&error.phase==='configuration');
  }
});

test('owned service control fails closed unless exact immutable ID and both Compose labels agree',async()=>{
  assert.equal(typeof implementation.ownedContainerCommand,'function');
  const id='a'.repeat(64),env={PAYMENT_E2E_MAILPIT_OWNER:id,PAYMENT_E2E_COMPOSE_PROJECT:'task17-owned'};
  for(const patch of [{PAYMENT_E2E_MAILPIT_OWNER:''},{PAYMENT_E2E_COMPOSE_PROJECT:''},{PAYMENT_E2E_MAILPIT_OWNER:'other-name'}])await assert.rejects(implementation.ownedContainerCommand({...env,...patch},'stop',async()=>{throw new Error('must not call Docker');}));
  for(const inspected of [`${id}|other|mailpit`,`${id}|task17-owned|backend`,`${'b'.repeat(64)}|task17-owned|mailpit`]){
    const calls=[];await assert.rejects(implementation.ownedContainerCommand(env,'stop',async(_file,args)=>{calls.push(args);return {stdout:inspected};}));assert.equal(calls.length,1);
  }
  for(const action of ['stop','start']){
    const calls=[];await implementation.ownedContainerCommand(env,action,async(file,args,options)=>{assert.equal(file,'docker');assert.ok(options.timeout<=15000);calls.push(args);return {stdout:`${id}|task17-owned|mailpit`};});
    assert.deepEqual(calls[1],action==='stop'?['stop','--time','5',id]:['start',id]);
  }
});
