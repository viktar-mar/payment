import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { loadSmokeEnv } from './smoke-env.mjs';

test('explicit ENV-only mode never attempts a configuration-file read',async()=>{
  let reads=0;const env={PAYMENT_SMOKE_ENV_ONLY:'true',FIXTURE:'exported'};
  assert.deepEqual(await loadSmokeEnv(env,async()=>{reads++;throw new Error('non-sensitive read trap');}),env);
  assert.equal(reads,0);
});
test('standalone fallback preserves exported precedence and original quoted parsing',async()=>{
  let reads=0;
  const result=await loadSmokeEnv({FIXTURE:'exported'},async()=>{reads++;return 'FIXTURE=ignored\nOPTION="synthetic"\nOTHER=plain\n';});
  assert.deepEqual(result,{FIXTURE:'exported',OPTION:'synthetic',OTHER:'plain'});assert.equal(reads,1);
});

test('Compose provides explicit Customer keys, bounded local Mailpit and read-only issuer fixtures', async () => {
  const compose=await readFile(new URL('./compose.yaml',import.meta.url),'utf8');
  for(const value of ['PAYMENT_CUSTOMER_DIGEST_KEY_V1:?','PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1:?','mailpit:','127.0.0.1:${PAYMENT_MAILPIT_HOST_PORT:-28025}','customer-issuer-private.jwks.json:ro']) assert.ok(compose.includes(value),`missing safe runtime configuration: ${value}`);
  assert.ok(!compose.includes('down --volumes'));
});

async function failureFixture(kind) {
  let followed=0;const secret=randomBytes(24).toString('hex');
  const server=http.createServer((req,res)=>{
    if(req.url==='/actuator/health/readiness'){res.writeHead(200,{'content-type':'application/json'});res.end('{"status":"UP"}');return;}
    if(kind==='redirect'){res.writeHead(302,{location:'/credential-sink'});res.end();}
    else if(kind==='oversize'){res.writeHead(200,{'content-type':'application/json'});res.end('x'.repeat(300000));}
    else {res.writeHead(500,{'content-type':'application/json'});res.end(JSON.stringify({detail:secret}));}
    if(req.url==='/credential-sink')followed++;
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {
    const result=await new Promise(resolve=>{
      const child=spawn(process.execPath,['--import','./infrastructure/fixtures/smoke-env-read-trap.mjs','infrastructure/smoke-stage-3a.mjs'],{cwd:new URL('..',import.meta.url),env:{...process.env,
        PAYMENT_SMOKE_ENV_ONLY:'true',PAYMENT_SMOKE_BACKEND_URL:`http://127.0.0.1:${server.address().port}`,PAYMENT_ADMIN_BOOTSTRAP_USERNAME:'fixture-admin',PAYMENT_ADMIN_BOOTSTRAP_PASSWORD:secret,
        PAYMENT_SMOKE_SITE_KEY:'stage3a-smoke-one',PAYMENT_TELEMETRY_SITEKEYS:'stage3a-smoke-one,stage3a-smoke-two'}});
      let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);child.on('close',code=>resolve({code,output}));
    });
    assert.equal(result.code,1);assert.equal(followed,0);assert.ok(!result.output.includes(secret));
    assert.deepEqual(JSON.parse(result.output),{result:'FAIL',reason:'stage3a_smoke_verification_failed'});
  } finally {await new Promise(resolve=>server.close(resolve));}
}
for(const kind of ['redirect','oversize','problem'])test(`smoke rejects ${kind} with bounded, credential-free output`,()=>failureFixture(kind));
