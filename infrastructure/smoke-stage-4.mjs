/** Freshness only: exact identity, revision and Partner-authority validation belong to the consumer. */
export function accessCacheFresh(decision, now) {
  return decision?.allowed === true && typeof decision.valid_until === 'string'
    && Number.isFinite(Date.parse(decision.valid_until)) && now < Date.parse(decision.valid_until);
}
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID,createHmac} from 'node:crypto';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {boundedJson} from './smoke-stage-3b.mjs';

/** All active EMAIL_OTP fixture Sites participate in global readiness, including isolation-only Sites. */
export async function configureStage4IdentityProviders(sites, post) {
  for (const site of sites) await post(`/api/v1/admin/sites/${site.id}/identity-providers`, {provider_key:'local-email',mode:'EMAIL_OTP',sender_ref:'local-default',template_key:'otp-default'});
}

/** Align only the owned fixture's business time before facts; never truncate a future saved instant backwards. */
export function stage4FixtureStartTime(saved, realNow) {
  const match=/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.(\d{1,9}))?Z$/.exec(saved);
  const millis=Date.parse(saved);
  if(!match||!Number.isFinite(millis)||!Number.isSafeInteger(realNow))throw new Error('invalid_fixture_time');
  const subMillis=Number((match[1]??'').padEnd(9,'0').slice(3));
  return new Date(Math.max(millis+(subMillis>0?1:0),realNow)).toISOString();
}

/** Mutate only the explicitly owned immutable Mailpit ID after checking both Compose labels. */
export async function ownedContainerCommand(env,action,execute=promisify(execFile)) {
  const id=env.PAYMENT_E2E_MAILPIT_OWNER,project=env.PAYMENT_E2E_COMPOSE_PROJECT;
  if(!/^[0-9a-f]{64}$/.test(id??'')||!/^task17-[a-z0-9-]{1,63}$/.test(project??'')||!['start','stop'].includes(action))throw new Error('invalid_owned_service');
  const options={timeout:15000,maxBuffer:4096};
  const inspected=await execute('docker',['inspect','--format','{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}',id],options);
  if(inspected.stdout.trim()!==`${id}|${project}|mailpit`)throw new Error('unowned_service');
  await execute('docker',action==='stop'?['stop','--time','5',id]:['start',id],options);
}

/** @typedef {{result:'PASS',initial:boolean,trial:boolean,renewal:boolean,grace:boolean,recovery:boolean,upgrade:boolean,cancellation:boolean,signedCallbacks:boolean,noDuplicateCash:boolean,isolation:boolean,durableRestart:boolean,cacheExpiry:boolean,subscriptions:number,callbacks:number}} Stage4SmokeReport */
/** Finite ENV-only local acceptance. Diagnostics contain a bounded phase name, never a response or capability.
 * @param {NodeJS.ProcessEnv} env
 * @returns {Promise<Stage4SmokeReport>}
 */
export async function runSmoke(env=process.env) {
  let phase='configuration',sql,cookie='',csrf='',unexpectedHttpStatus;
  const base=env.PAYMENT_SMOKE_BACKEND_URL,receiver=env.PAYMENT_SMOKE_RECEIVER_URL,mailpit=env.PAYMENT_SMOKE_MAILPIT_URL;
  const origin='http://127.0.0.1:4173',execute=promisify(execFile);
  const check=value=>{if(!value)throw new Error('verification');};
  async function request(path,{target=base,method='GET',body,headers={},admin=false,expected=200}={}) {
    const response=await fetch(target+path,{method,redirect:'error',signal:AbortSignal.timeout(10000),headers:{Accept:'application/json',...(body!==undefined?{'Content-Type':'application/json'}:{}),...(admin?{Cookie:cookie,...(method!=='GET'?{'X-XSRF-TOKEN':csrf,'Idempotency-Key':randomUUID()}:{})}:{}),...headers},...(body!==undefined?{body:JSON.stringify(body)}:{})});
    if(response.status!==expected){unexpectedHttpStatus=response.status;check(false);}return {response,value:await boundedJson(response)};
  }
  const get=async path=>(await request(path,{admin:true})).value;
  const post=async(path,body,expected=201)=>(await request(path,{admin:true,method:'POST',body,expected})).value;
  async function wait(operation,seconds=25){const end=Date.now()+seconds*1000;do{const value=await operation();if(value)return value;await new Promise(resolve=>setTimeout(resolve,200));}while(Date.now()<end);throw new Error('deadline');}
  async function business(until){
    const r=await fetch(env.PAYMENT_STAGE4_CONTROL_URL,{method:until?'POST':'GET',headers:{Authorization:'Bearer '+env.PAYMENT_STAGE4_CONTROL_KEY},...(until?{body:until}:{}),redirect:'error',signal:AbortSignal.timeout(5000)});
    check(r.status===200);const value=await r.text();check(value.length<=64&&Number.isFinite(Date.parse(value)));return value;
  }
  async function verifyContainer(service,id){
    check(/^[a-f0-9]{64}$/.test(id??''));
    const result=await execute('docker',['inspect','--format','{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}',id],{timeout:10000,maxBuffer:4096});
    check(result.stdout.trim()===`${id}|${env.PAYMENT_E2E_COMPOSE_PROJECT}|${service}`);
  }
  try {
    const local=(value,path='/')=>{try{const u=new URL(value);return u.protocol==='http:'&&u.hostname==='127.0.0.1'&&!u.username&&!u.password&&!u.search&&!u.hash&&u.pathname===path;}catch{return false;}};
    check(env.PAYMENT_SMOKE_ENV_ONLY==='true'&&env.PAYMENT_STAGE4_TEST_RUNTIME==='true');
    check([base,receiver,mailpit].every(value=>local(value))&&local(env.PAYMENT_STAGE4_CONTROL_URL,'/time'));
    check(/^[A-Za-z0-9_-]{43}$/.test(env.PAYMENT_STAGE4_CONTROL_KEY??'')&&/^task17-[a-z0-9-]{1,63}$/.test(env.PAYMENT_E2E_COMPOSE_PROJECT??''));
    check(env.PAYMENT_ADMIN_BOOTSTRAP_USERNAME&&env.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD);
    const db=new URL(env.PAYMENT_SMOKE_DATABASE_URL);check(db.protocol==='postgres:'&&db.hostname==='127.0.0.1'&&db.pathname==='/payment');
    const keys=[env.PAYMENT_SMOKE_SITE_KEY,env.PAYMENT_SMOKE_SECOND_SITE_KEY];
    check(keys[0]!==keys[1]&&keys.every(k=>/^task17-[a-z0-9-]{1,55}$/.test(k??'')&&(env.PAYMENT_TELEMETRY_SITEKEYS??'').split(',').includes(k)));
    await verifyContainer('backend',env.PAYMENT_STAGE4_BACKEND_OWNER);await verifyContainer('callback-receiver',env.PAYMENT_SMOKE_RECEIVER_OWNER);await verifyContainer('postgres',env.PAYMENT_SMOKE_POSTGRES_OWNER);
    const postgres=createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');sql=postgres(env.PAYMENT_SMOKE_DATABASE_URL,{max:1,connect_timeout:5,idle_timeout:1,connection:{statement_timeout:10000}});
    phase='login';check((await request('/actuator/health/readiness')).value.status==='UP');
    const login=await request('/api/admin/v1/auth/sessions',{method:'POST',expected:204,body:{username:env.PAYMENT_ADMIN_BOOTSTRAP_USERNAME,password:env.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD}});
    const cookies=login.response.headers.getSetCookie(),session=cookies.find(c=>c.startsWith('PAYMENT_ADMIN_SESSION='))?.split(';')[0],xsrf=cookies.find(c=>c.startsWith('XSRF-TOKEN='))?.split(';')[0];
    check(session&&xsrf);cookie=session+'; '+xsrf;csrf=xsrf.slice(11);
    phase='provision';await business(stage4FixtureStartTime(await business(),Date.now()));const sites=[];
    for(const key of keys){
      const account=await post('/api/admin/v1/payment-accounts',{key:'s4-'+randomUUID(),provider:'FAKE',mode:'SANDBOX',external_account_id:'local-smoke',credentials_ref:'local-reference'});
      const site=await post('/api/admin/v1/sites',{key,display_name:'Stage 4 local smoke',payment_account_id:account.id,allowed_origins:[origin],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'AUTOMATIC',methods:[]}});
      const path=`/api/v1/admin/sites/${site.id}/subscription-policy`,policy=await get(path);
      await post(path,{version:policy.version,enabled:true,grace_days:3,allow_immediate_cancellation:true,portal_enabled:false,defer_upgrades:false,portal_return_urls:[]},200);sites.push(site);
    }
    await configureStage4IdentityProviders(sites,post);
    const site=sites[0];
    const products=[];
    for(const trialDays of [0,7]){
      const product=await post(`/api/admin/v1/sites/${site.id}/products`,{key:'s4-'+randomUUID(),name:'Subscription smoke',description:'Owned local acceptance'}),prices=[];
      for(const amount of [2000,4000]){
        const price=await post(`/api/admin/v1/products/${product.id}/prices`,{price_key:'s4-'+randomUUID(),type:'SUBSCRIPTION',amount_minor:amount,currency:'EUR',billing_interval:'MONTH',trial_days:trialDays,trial_payment_method_policy:'OPTIONAL_DURING_TRIAL',tax_mode:'INCLUSIVE',tax_code:null});
        await post(`/api/admin/v1/prices/${price.id}/publish`,{},200);prices.push(price);
      }
      const currentPolicy=await get(`/api/v1/admin/sites/${site.id}/subscription-policy`);
      await post(`/api/v1/admin/sites/${site.id}/products/${product.id}/subscription-transitions`,{policy_version:currentPolicy.version,from_price_id:prices[0].id,to_price_id:prices[1].id,direction:'UPGRADE',immediate_allowed:true},200);
      products.push({product,prices});
    }
    phase='callback';const callback=(await request(`/api/v1/admin/sites/${site.id}/callback`,{method:'PUT',admin:true,body:{endpoint_url:'http://callback-receiver:8080/callback',enabled:true}})).value;
    await request('/configure',{target:receiver,method:'POST',body:{secret:callback.secret,secret_version:callback.secret_version},expected:204});
    phase='email-otp';const email='s4-'+randomUUID()+'@example.test';
    const challenge=(await request('/api/v1/public/customer-auth/email-otp/challenges',{method:'POST',expected:202,headers:{Origin:origin},body:{site_key:site.key,email,intent:'AUTHENTICATE'}})).value;
    const code=await wait(async()=>{const listing=(await request('/api/v1/messages',{target:mailpit})).value,found=listing.messages.find(m=>m.To?.some(t=>t.Address===email));if(!found)return null;const mail=(await request(`/api/v1/message/${found.ID}`,{target:mailpit})).value;return /\b([0-9]{6})\b/.exec(mail.Text??'')?.[1];});
    const customer=(await request(`/api/v1/public/customer-auth/email-otp/challenges/${challenge.challenge_id}/verify`,{method:'POST',headers:{Origin:origin},body:{site_key:site.key,code}})).value;
    const auth={Origin:origin,Authorization:'Bearer '+customer.session_token};
    const mutate=async(path,body,key=randomUUID(),expected=202)=>(await request('/api/v1/customer/subscriptions'+path,{method:'POST',body,expected,headers:{...auth,'Idempotency-Key':key}})).value;
    const view=async id=>get(`/api/v1/admin/subscriptions/${id}?site_id=${site.id}`);
    const fake=async(id,body)=>post(`/api/v1/admin/subscriptions/${id}/fake-controls?site_id=${site.id}`,body,200);
    const advance=async(id,until)=>{await business(until);await fake(id,{action:'ADVANCE',until});};
    const state=async(id,wanted)=>wait(async()=>{const v=await view(id);return v.state===wanted?v:null;});
    const cash=async id=>Number((await sql`select count(*) as n from payments where subscription_id=${id}::uuid and status='SUCCEEDED'`)[0].n);
    phase='initial-create';const paid=(await mutate('',{price_id:products[0].prices[0].id,recurring_consent:true})).subscription_id;
    phase='initial-state';
    let current=await state(paid,'ACTIVE');check(current.access.allowed&&await cash(paid)===1);
    phase='initial-key';const partner=(await post(`/api/v1/admin/sites/${site.id}/api-keys`,{scopes:['subscriptions:read']})).api_key;
    const identity={site_id:site.id,customer_id:customer.customer.id,product_id:products[0].product.id};
    phase='initial-configure';await request('/access/configure',{target:receiver,method:'POST',expected:204,body:{...identity,api_key:partner}});
    phase='initial-access';
    check((await request('/access',{target:receiver})).value.allowed);
    phase='trial';const trial=(await mutate('',{price_id:products[1].prices[0].id,recurring_consent:true})).subscription_id;
    const trialView=await state(trial,'TRIALING');check(trialView.access.allowed&&await cash(trial)===0);
    const setup=await mutate(`/${trial}/method-setups`,{recurring_consent:true});
    const action=(await request(`/api/v1/customer/subscriptions/${trial}/operations/${setup.operation_id}/action`,{headers:auth})).value;
    await mutate(`/${trial}/operations/${setup.operation_id}/action-confirmations`,{action_token:action.secret});
    await wait(async()=>{const op=(await request(`/api/v1/customer/subscriptions/${trial}/operations/${setup.operation_id}`,{headers:auth})).value;return op.status==='SUCCEEDED';});
    check((await view(trial)).state==='TRIALING'&&await cash(trial)===0);
    await advance(trial,trialView.trial.end);await state(trial,'ACTIVE');check(await cash(trial)===1);
    phase='upgrade';const quote=await mutate(`/${paid}/change-preview`,{target_price_id:products[0].prices[1].id,timing:'IMMEDIATE'},randomUUID(),200);check(quote.due_now_minor>0);
    await mutate(`/${paid}/changes`,{quote_id:quote.quote_id});
    await wait(async()=>((await view(paid)).entitled_price.price_id===products[0].prices[1].id));check(await cash(paid)===2);
    phase='renewal';current=await view(paid);await fake(paid,{action:'SCENARIO',scenario:'DECLINE'});await advance(paid,current.period.end);
    current=await state(paid,'PAST_DUE');check(current.access.allowed&&current.debt.grace_deadline&&await cash(paid)===2);
    phase='grace';const deadline=current.debt.grace_deadline,bill=current.debt.billing_record_id;
    const policy=await get(`/api/v1/admin/sites/${site.id}/subscription-policy`);
    await post(`/api/v1/admin/sites/${site.id}/subscription-policy`,{version:policy.version,enabled:true,grace_days:10,allow_immediate_cancellation:true,portal_enabled:false,defer_upgrades:false,portal_return_urls:[]},200);
    check((await view(paid)).debt.grace_deadline===deadline);
    const cacheBefore=(await request('/access',{target:receiver})).value;check(cacheBefore.allowed);
    await advance(paid,new Date(Date.parse(await business())+300000).toISOString());
    const cacheAfter=(await request('/access',{target:receiver})).value;check(cacheAfter.allowed&&cacheAfter.refreshes>cacheBefore.refreshes);
    phase='recovery';await fake(paid,{action:'SCENARIO',scenario:'SUCCESS'});const recoveryKey=randomUUID();
    const recovery=await mutate(`/${paid}/recoveries`,{billing_record_id:bill},recoveryKey);await state(paid,'ACTIVE');check(await cash(paid)===3);
    const retry=await mutate(`/${paid}/recoveries`,{billing_record_id:bill},recoveryKey);check(retry.operation_id===recovery.operation_id&&await cash(paid)===3);
    phase='cancellation';await mutate(`/${paid}/cancellation`,{mode:'PERIOD_END'});await state(paid,'CANCEL_AT_PERIOD_END');
    await mutate(`/${paid}/cancellation/withdraw`,{});await state(paid,'ACTIVE');
    await fake(paid,{action:'SCENARIO',scenario:'UNKNOWN'});const cancelKey=randomUUID(),cancelBody={mode:'IMMEDIATE',confirmation:paid};
    const cancellation=await mutate(`/${paid}/cancellation`,cancelBody,cancelKey);check(cancellation.status==='UNKNOWN'&&(await view(paid)).state!=='CANCELLED');
    const receipts=await sql`select row_to_json(t)::text as row from fake_subscription_operations t where subscription_id=${paid}::uuid order by operation_key`;
    const countBefore=await cash(paid),timeBefore=await business();
    phase='restart';await verifyContainer('backend',env.PAYMENT_STAGE4_BACKEND_OWNER);
    await execute('docker',['restart','--time','10',env.PAYMENT_STAGE4_BACKEND_OWNER],{timeout:60000,maxBuffer:4096});
    await wait(async()=>{try{return (await request('/actuator/health/readiness')).value.status==='UP';}catch{return false;}},60);
    check(await business()===timeBefore);
    check(JSON.stringify(await sql`select row_to_json(t)::text as row from fake_subscription_operations t where subscription_id=${paid}::uuid order by operation_key`)===JSON.stringify(receipts));
    check((await mutate(`/${paid}/cancellation`,cancelBody,cancelKey)).operation_id===cancellation.operation_id);
    await fake(paid,{action:'SCENARIO',scenario:'SUCCESS'});await advance(paid,new Date(Date.parse(timeBefore)+1000).toISOString());await state(paid,'CANCELLED');
    check(!(await view(paid)).access.allowed&&await cash(paid)===countBefore);
    check(JSON.stringify(await sql`select row_to_json(t)::text as row from fake_subscription_operations t where subscription_id=${paid}::uuid order by operation_key`)===JSON.stringify(receipts));
    phase='signed-callbacks';await wait(async()=>Number((await sql`select count(*) as n from outbox_events where site_id=${site.id}::uuid and status<>'DELIVERED'`)[0].n)===0);
    const rows=await sql`select payload from outbox_events where site_id=${site.id}::uuid order by created_at,id`;
    check(rows.some(r=>r.payload.schema_version===3)&&rows.some(r=>r.payload.schema_version===4));
    const inspection=(await request('/inspection',{target:receiver})).value;
    check(rows.every(r=>inspection.events.some(e=>e.event_id===r.payload.event_id&&e.count===1)));
    const latest=(await request('/access',{target:receiver})).value;check(!latest.allowed);
    const replay=rows.find(r=>r.payload.schema_version===4&&r.payload.subscription_id===paid&&r.payload.access_allowed).payload;
    const timestamp=String(Math.floor(Date.now()/1000)),signature='v1='+createHmac('sha256',Buffer.from(callback.secret,'base64url')).update(timestamp+'.'+JSON.stringify(replay)).digest('hex');
    await request('/callback',{target:receiver,method:'POST',expected:204,body:replay,headers:{'Payment-Event-Id':replay.event_id,'Payment-Timestamp':timestamp,'Payment-Secret-Version':String(callback.secret_version),'Payment-Signature':signature}});
    const afterReplay=(await request('/access',{target:receiver})).value;check(!afterReplay.allowed&&afterReplay.revision>=latest.revision);
    const dedup=(await request('/inspection',{target:receiver})).value;check(dedup.count===inspection.count&&dedup.duplicates===inspection.duplicates+1);
    phase='isolation';const other=(await post(`/api/v1/admin/sites/${sites[1].id}/api-keys`,{scopes:['subscriptions:read']})).api_key;
    await request(`/api/v1/partner/subscriptions/${paid}`,{headers:{Authorization:'Bearer '+other},expected:404});
    await request(`/api/v1/customer/subscriptions/${paid}`,{expected:401});
    const journals=await sql`select count(*) as n,count(distinct sequence_number) as unique_count from subscription_events where subscription_id=${paid}::uuid`;
    check(Number(journals[0].n)>3&&journals[0].n===journals[0].unique_count&&await cash(paid)===3);
    return {result:'PASS',initial:true,trial:true,renewal:true,grace:true,recovery:true,upgrade:true,cancellation:true,signedCallbacks:true,noDuplicateCash:true,isolation:true,durableRestart:true,cacheExpiry:true,subscriptions:2,callbacks:rows.length};
  }catch{const failure=new Error('stage4_smoke_verification_failed');failure.phase=phase;failure.httpStatus=unexpectedHttpStatus;throw failure;}
  finally{if(sql)await sql.end({timeout:2});}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  try{console.log(JSON.stringify(await runSmoke()));}
  catch(error){console.error(JSON.stringify({result:'FAIL',reason:'stage4_smoke_verification_failed',phase:['configuration','login','provision','callback','email-otp','initial-create','initial-state','initial-key','initial-configure','initial-access','trial','upgrade','renewal','grace','recovery','cancellation','restart','signed-callbacks','isolation'].includes(error?.phase)?error.phase:'cleanup',...(Number.isInteger(error?.httpStatus)&&error.httpStatus>=100&&error.httpStatus<=599?{httpStatus:error.httpStatus}:{})}));process.exitCode=1;}
}
