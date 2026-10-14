import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { loadSmokeEnv } from './smoke-env.mjs';
import { stage2OwnedLogs, stage2OwnedReceiver, stage2OwnedSettlement, verifyStage2Ownership } from './smoke-stage-2-owned.mjs';

const env=await loadSmokeEnv(process.env,()=>readFile('infrastructure/.env','utf8'));
const known=new Set();
for(const name of ['PAYMENT_ADMIN_BOOTSTRAP_PASSWORD','PAYMENT_DB_PASSWORD','PAYMENT_DATA_ENCRYPTION_KEY'])if(env[name])known.add(env[name]);
const base=env.PAYMENT_SMOKE_BACKEND_URL??`http://127.0.0.1:${env.PAYMENT_BACKEND_HOST_PORT??8080}`;
const origin=env.PAYMENT_SMOKE_ORIGIN??'http://localhost:3000';
const siteKey=env.PAYMENT_SMOKE_SITE_KEY;
let cookie='',csrf='';
function check(condition){if(!condition)throw new Error('verification_failed');}
async function request(path,{method='GET',body,headers={},expected=200,admin=false}={}) {
  const r=await fetch(base+path,{method,redirect:'error',signal:AbortSignal.timeout(10000),headers:{...(body!==undefined?{'content-type':'application/json'}:{}),...(admin?{cookie,...(method!=='GET'?{'X-XSRF-TOKEN':csrf}:{})}:{}),'X-Request-Id':'stage2-smoke',...headers},...(body!==undefined?{body:JSON.stringify(body)}:{})});
  check(r.status===expected);const text=await r.text();check(Buffer.byteLength(text)<262144);
  return {r,value:text?JSON.parse(text):null};
}
async function file(name,value){await writeFile(join(env.PAYMENT_SMOKE_TMP,name),JSON.stringify(value),{mode:0o600});}
async function compose(args,input='') {
  return new Promise((resolve,reject)=>{
    const child=spawn('docker',['compose','--profile','smoke','--env-file','infrastructure/.env','-f','infrastructure/compose.yaml',...args],{env,stdio:['pipe','pipe','pipe']});
    let output='',error='',size=0;child.stdout.on('data',b=>{size+=b.length;if(size>8388608)child.kill();else output+=b;});child.stderr.on('data',b=>{if(error.length<4096)error+=b;});
    child.on('error',()=>reject(new Error('compose_failed')));child.on('close',code=>code===0?resolve(output):reject(new Error('compose_failed')));
    child.stdin.end(input);
  });
}
const receiverRequest="let b='';for await(const c of process.stdin)b+=c;const q=JSON.parse(b);const r=await fetch('http://127.0.0.1:8080'+q.path,{method:q.method,redirect:'error',headers:q.body?{'content-type':'application/json'}:{},body:q.body?JSON.stringify(q.body):undefined});if(r.status!==q.status)process.exit(1);const t=await r.text();if(t)process.stdout.write(t);";
async function receiver(path,method,status,body){
  if(env.PAYMENT_SMOKE_ENV_ONLY==='true')return stage2OwnedReceiver(env,path,method,status,body);
  const result=await compose(['exec','-T','callback-receiver','node','--input-type=module','-e',receiverRequest],JSON.stringify({path,method,status,body}));return result?JSON.parse(result):null;
}
try {
  check(typeof siteKey==='string'&&/^[a-z0-9][a-z0-9_-]{0,63}$/.test(siteKey));
  check(env.PAYMENT_ADMIN_BOOTSTRAP_USERNAME&&env.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD&&env.PAYMENT_SMOKE_TMP);
  let ready=false;
  for(let i=0;i<90;i++){try{check((await request('/actuator/health/readiness')).value.status==='UP');ready=true;break;}catch{await new Promise(r=>setTimeout(r,1000));}}
  check(ready);
  const login=await request('/api/admin/v1/auth/sessions',{method:'POST',expected:204,body:{username:env.PAYMENT_ADMIN_BOOTSTRAP_USERNAME,password:env.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD}});
  const cookies=login.r.headers.getSetCookie();
  const session=cookies.find(c=>c.startsWith('PAYMENT_ADMIN_SESSION='))?.split(';')[0];const xsrf=cookies.find(c=>c.startsWith('XSRF-TOKEN='))?.split(';')[0];
  check(session&&xsrf);csrf=xsrf.slice('XSRF-TOKEN='.length);cookie=session+'; '+xsrf;known.add(session.slice('PAYMENT_ADMIN_SESSION='.length));known.add(csrf);await file('session.json',{cookie,csrf});
  const post=async(path,body,expected=201,headers={})=>(await request(path,{method:'POST',body,expected,headers,admin:true})).value;
  const suffix=randomUUID().slice(0,8);
  const account=await post('/api/admin/v1/payment-accounts',{key:'smoke-account-'+suffix,provider:'FAKE',mode:'SANDBOX',external_account_id:'fake-smoke',credentials_ref:'local-smoke-reference'});
  const site=await post('/api/admin/v1/sites',{key:siteKey,display_name:'Stage 2 smoke',payment_account_id:account.id,allowed_origins:[origin],authentication_modes:['SITE_TOKEN'],payment_method_policy:{mode:'AUTOMATIC',methods:[]}});
  const product=await post(`/api/admin/v1/sites/${site.id}/products`,{key:'one-time',name:'One time',description:'Local smoke'});
  const price=await post(`/api/admin/v1/products/${product.id}/prices`,{price_key:'one-time',type:'ONE_TIME',amount_minor:4999,currency:'EUR',trial_days:0,tax_mode:'INCLUSIVE'});
  await post(`/api/admin/v1/prices/${price.id}/publish`,undefined,200);
  const reveal=(await request(`/api/v1/admin/sites/${site.id}/callback`,{method:'PUT',admin:true,body:{endpoint_url:'http://callback-receiver:8080/callback',enabled:true}})).value;
  check(reveal.secret&&reveal.secret_version===1);known.add(reveal.secret);await file('callback.json',reveal);
  if(env.PAYMENT_SMOKE_ENV_ONLY==='true')await verifyStage2Ownership(env);
  await receiver('/configure','POST',204,{secret_version:reveal.secret_version,secret:reveal.secret});
  const createKey=randomUUID(),confirmKey=randomUUID();
  const create=async()=>(await request(`/api/v1/public/sites/${siteKey}/checkouts`,{method:'POST',expected:201,body:{price_key:'one-time'},headers:{Origin:origin,'Idempotency-Key':createKey}})).value;
  const checkout=await create();check(checkout.checkout_token&&checkout.payment_id&&checkout.checkout_id);known.add(checkout.checkout_token);await file('checkout.json',checkout);
  const publicHeaders={Origin:origin,Authorization:'Bearer '+checkout.checkout_token};
  const confirm=async()=>(await request(`/api/v1/public/checkouts/${checkout.checkout_id}/confirm`,{method:'POST',body:{payment_method_family:'CARD'},headers:{...publicHeaders,'Idempotency-Key':confirmKey}})).value;
  await confirm();
  if(env.PAYMENT_SMOKE_ENV_ONLY==='true')await stage2OwnedSettlement(env,checkout.payment_id,post);
  let succeeded=false;
  for(let i=0;i<90;i++){const state=(await request(`/api/v1/public/checkouts/${checkout.checkout_id}`,{headers:publicHeaders})).value;if(state.status==='SUCCEEDED'){succeeded=true;break;}await new Promise(r=>setTimeout(r,1000));}check(succeeded);
  let details,inspection;
  for(let i=0;i<90;i++){
    details=(await request(`/api/v1/admin/payments/${checkout.payment_id}`,{admin:true})).value;inspection=await receiver('/inspection','GET',200);
    if(details.outbox_events.some(e=>e.callback_type==='payment.succeeded'&&e.status==='DELIVERED')&&inspection.events.some(e=>e.event_type==='payment.succeeded'))break;
    await new Promise(r=>setTimeout(r,1000));
  }
  const facts=details.events.filter(e=>e.event_type==='PAYMENT_SUCCEEDED');check(facts.length===1);
  const event=inspection.events.filter(e=>e.event_type==='payment.succeeded'&&e.event_id===facts[0].id);check(event.length===1&&event[0].count===1);
  const deliveries=details.deliveries.filter(d=>d.outbox_event_id===facts[0].id);check(deliveries.length===1&&deliveries[0].status==='SUCCEEDED');
  const replay=await create();check(replay.payment_id===checkout.payment_id&&replay.checkout_id===checkout.checkout_id&&replay.checkout_token===checkout.checkout_token);await confirm();
  details=(await request(`/api/v1/admin/payments/${checkout.payment_id}`,{admin:true})).value;
  check(details.attempts.length===1&&details.payment.status==='SUCCEEDED');
  const page=(await request(`/api/v1/admin/payments?site_id=${site.id}`,{admin:true})).value;check(page.total===1&&page.items[0].payment_id===checkout.payment_id);
  const logs=env.PAYMENT_SMOKE_ENV_ONLY==='true'?await stage2OwnedLogs(env):await compose(['logs','--no-color','backend','callback-receiver']);for(const secret of known)check(!logs.includes(secret));
  console.log(JSON.stringify({result:'PASS',payment_id:checkout.payment_id,checkout_id:checkout.checkout_id,event_id:facts[0].id,site_id:site.id}));
} catch {console.error(JSON.stringify({result:'FAIL',reason:'stage2_smoke_verification_failed'}));process.exitCode=1;}
