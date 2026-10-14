import { randomUUID, createHmac } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';

const fields=['schema_version','event_id','event_type','site_id','payment_id','external_order_id','payment_status','amount_minor','currency','occurred_at'];
export function callbackShape(value,version) {
  const expected=version===1?fields:version===2?[...fields,'donation_id','campaign_key','customer_id']:[];
  return value?.schema_version===version&&Object.keys(value).sort().join(',')===[...expected].sort().join(',');
}
export async function boundedJson(response) {
  if(Number(response.headers.get('content-length')??0)>262144)throw new Error('response_limit');
  const reader=response.body?.getReader();if(!reader)return null;
  const parts=[];let size=0;
  try {for(;;){const part=await reader.read();if(part.done)break;size+=part.value.byteLength;
    if(size>262144){await reader.cancel();throw new Error('response_limit');}parts.push(part.value);}}
  finally {reader.releaseLock();}
  if(!size)return null;
  try{return JSON.parse(Buffer.concat(parts).toString('utf8'));}catch{throw new Error('response_invalid');}
}
const check=(value,label)=>{if(!value)throw new Error(label);};
/** ENV-only, local fake-provider integration; no private files or payload diagnostics. */
export async function runSmoke(env=process.env) {
  let sql;const known=new Set(),messages=[],responses=[],ids=[];let cookie='',csrf='',phase='configuration';
  const base=env.PAYMENT_SMOKE_BACKEND_URL,mailpit=env.PAYMENT_SMOKE_MAILPIT_URL,receiver=env.PAYMENT_SMOKE_RECEIVER_URL;
  const origin='http://127.0.0.1:4173',keys=[env.PAYMENT_SMOKE_SITE_KEY,env.PAYMENT_SMOKE_SECOND_SITE_KEY];
  async function request(path,{method='GET',body,headers={},expected=200,admin=false,target=base}={}){
    let response;
    try {response=await fetch(target+path,{method,redirect:'error',signal:AbortSignal.timeout(10000),headers:{Accept:'application/json','X-Request-Id':'stage3b-smoke',...(body!==undefined?{'Content-Type':'application/json'}:{}),...(admin?{Cookie:cookie,...(method!=='GET'?{'X-XSRF-TOKEN':csrf,'Idempotency-Key':randomUUID()}:{})}:{}),...headers},...(body!==undefined?{body:JSON.stringify(body)}:{})});}
    catch {throw new Error('transport_failed');}
    check(response.status===expected,'http_status');return {response,value:await boundedJson(response)};
  }
  const get=async path=>(await request(path,{admin:true})).value;
  const post=async(path,body,expected=201)=>(await request(path,{method:'POST',body,admin:true,expected})).value;
  async function wait(operation){const end=Date.now()+20000;do{const result=await operation();if(result)return result;await new Promise(r=>setTimeout(r,200));}while(Date.now()<end);throw new Error('bounded_wait');}
  async function settle(checkout){
    ids.push(checkout.payment_id);known.add(checkout.checkout_token);
    await request(`/api/v1/public/checkouts/${checkout.checkout_id}/confirm`,{method:'POST',headers:{Origin:origin,Authorization:'Bearer '+checkout.checkout_token,'Idempotency-Key':randomUUID()},body:{payment_method_family:'CARD'}});
    await post(`/api/v1/admin/payments/${checkout.payment_id}/fake-events`,{target:'PAYMENT',event_type:'PAYMENT_SUCCEEDED',sequence:7},202);
    await wait(async()=>((await get(`/api/v1/admin/payments/${checkout.payment_id}`)).payment.status==='SUCCEEDED'));
    await post(`/api/v1/admin/payments/${checkout.payment_id}/reconcile`,{},200);
    await wait(async()=>(await get(`/api/v1/admin/payments/${checkout.payment_id}`)).outbox_events.some(e=>e.callback_type==='payment.succeeded'&&e.status==='DELIVERED'));
  }
  try {
    check([base,mailpit,receiver].every(value=>{try{const u=new URL(value);return u.protocol==='http:'&&u.hostname==='127.0.0.1'&&!u.username&&!u.password&&!u.search&&!u.hash&&u.pathname==='/';}catch{return false;}}),'local_endpoints');
    check(keys.every(k=>typeof k==='string'&&/^[a-z0-9][a-z0-9-]{2,63}$/.test(k))&&keys[0]!==keys[1],'site_configuration');
    check(keys.every(k=>(env.PAYMENT_TELEMETRY_SITEKEYS??'').split(',').includes(k)),'telemetry_configuration');
    check(env.PAYMENT_ADMIN_BOOTSTRAP_USERNAME&&env.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD&&env.PAYMENT_SMOKE_DATABASE_URL,'runtime_configuration');
    for(const name of ['PAYMENT_ADMIN_BOOTSTRAP_PASSWORD','PAYMENT_DB_PASSWORD','PAYMENT_DATA_ENCRYPTION_KEY','PAYMENT_CUSTOMER_DIGEST_KEY_V1','PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1','PAYMENT_DONATION_MESSAGE_FINGERPRINT_KEY'])if(env[name])known.add(env[name]);
    phase='login';check((await request('/actuator/health/readiness')).value.status==='UP','readiness');
    const login=await request('/api/admin/v1/auth/sessions',{method:'POST',body:{username:env.PAYMENT_ADMIN_BOOTSTRAP_USERNAME,password:env.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD},expected:204});
    const cookies=login.response.headers.getSetCookie(),session=cookies.find(c=>c.startsWith('PAYMENT_ADMIN_SESSION='))?.split(';')[0],xsrf=cookies.find(c=>c.startsWith('XSRF-TOKEN='))?.split(';')[0];
    check(session&&xsrf,'admin_session');cookie=session+'; '+xsrf;csrf=xsrf.slice(11);known.add(session.slice(22));known.add(csrf);
    const postgres=createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');
    sql=postgres(env.PAYMENT_SMOKE_DATABASE_URL,{max:1,connect_timeout:5,idle_timeout:1});
    phase='provision';const sites=[];
    for(const key of keys){
      const account=await post('/api/admin/v1/payment-accounts',{key:'s3b-'+randomUUID(),provider:'FAKE',mode:'SANDBOX',external_account_id:'local-smoke',credentials_ref:'local-reference'});
      const site=await post('/api/admin/v1/sites',{key,display_name:'Stage 3B local smoke',payment_account_id:account.id,allowed_origins:[origin],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'AUTOMATIC',methods:[]}});
      await post(`/api/v1/admin/sites/${site.id}/identity-providers`,{provider_key:'local-email',mode:'EMAIL_OTP',sender_ref:'local-default',template_key:'otp-default'});
      const campaign=await post(`/api/v1/admin/sites/${site.id}/donation-campaigns`,{key:'community-help',display_name:'Community',currency:'EUR',suggested_amounts:[500,1000],minimum_amount:100,maximum_amount:2000,custom_amount_allowed:true,anonymous_allowed:true});
      await post(`/api/v1/admin/sites/${site.id}/donation-campaigns/${campaign.id}/status`,{status:'ACTIVE',expected_version:campaign.version},200);sites.push(site);
    }
    const site=sites[0],publicPath=`/api/v1/public/sites/${site.key}/donation-campaigns/community-help/checkouts`;
    phase='callback';const callback=(await request(`/api/v1/admin/sites/${site.id}/callback`,{method:'PUT',admin:true,body:{endpoint_url:'http://callback-receiver:8080/callback',enabled:true}})).value;known.add(callback.secret);
    await request('/configure',{target:receiver,method:'POST',body:{secret:callback.secret,secret_version:callback.secret_version},expected:204});
    const anonMessage='task9-private-donor-'+randomUUID(),customerMessage='task9-private-donor-'+randomUUID();messages.push(anonMessage,customerMessage);messages.forEach(v=>known.add(v));
    phase='anonymous';const anon=(await request(publicPath,{method:'POST',expected:201,headers:{Origin:origin,'Idempotency-Key':randomUUID()},body:{amount_minor:500,message:anonMessage}})).value;
    await settle(anon);
    phase='email-otp';const email='task9-smoke-'+randomUUID()+'@example.test';known.add(email);
    const challenge=(await request('/api/v1/public/customer-auth/email-otp/challenges',{method:'POST',expected:202,headers:{Origin:origin},body:{site_key:site.key,email,intent:'AUTHENTICATE'}})).value;
    const code=await wait(async()=>{const listing=(await request('/api/v1/messages',{target:mailpit})).value;const found=listing.messages.find(m=>m.To?.some(t=>t.Address===email));if(!found)return null;const mail=(await request(`/api/v1/message/${found.ID}`,{target:mailpit})).value;return /\b([0-9]{6})\b/.exec(mail.Text??'')?.[1];});known.add(code);
    const customer=(await request(`/api/v1/public/customer-auth/email-otp/challenges/${challenge.challenge_id}/verify`,{method:'POST',headers:{Origin:origin},body:{site_key:site.key,code}})).value;
    known.add(customer.session_token);const auth={Origin:origin,Authorization:'Bearer '+customer.session_token};
    await request(`/api/v1/public/sites/${sites[1].key}/donation-campaigns/community-help/checkouts`,{method:'POST',expected:401,headers:{...auth,'Idempotency-Key':randomUUID()},body:{amount_minor:500}});
    phase='customer';const donation=(await request(publicPath,{method:'POST',expected:201,headers:{...auth,'Idempotency-Key':randomUUID()},body:{amount_minor:1000,message:customerMessage}})).value;await settle(donation);
    phase='isolation';const history=(await request('/api/v1/customer/donations?limit=20',{headers:auth})).value;responses.push(history);
    check(history.items.length===1&&history.items[0].donation_id===donation.donation.donation_id,'customer_isolation');
    for(const other of sites){const key=(await post(`/api/v1/admin/sites/${other.id}/api-keys`,{scopes:['donations:read']})).api_key;known.add(key);responses.push((await request(`/api/v1/partner/donations/${donation.donation.donation_id}`,{headers:{Authorization:'Bearer '+key},expected:other.id===site.id?200:404})).value);}
    await request(`/api/v1/admin/donations/${donation.donation.donation_id}`,{expected:401});
    check((await get(`/api/v1/admin/donations/${donation.donation.donation_id}`)).message===customerMessage,'authorized_admin_message');
    const tuples=await sql`select message_state,message_ciphertext is not null as encrypted,octet_length(message_nonce)=12 as nonce from donations where id in (${anon.donation.donation_id}::uuid,${donation.donation.donation_id}::uuid)`;
    check(tuples.length===2&&tuples.every(r=>r.message_state==='PRESENT'&&r.encrypted&&r.nonce),'encrypted_messages');
    phase='signed-dedup';const payloads=await sql`select payload from outbox_events where payment_id=${donation.payment_id}::uuid and callback_type='payment.succeeded'`;
    check(payloads.length===1&&callbackShape(payloads[0].payload,2),'v2_fields');
    const payload=payloads[0].payload,bytes=JSON.stringify(payload),timestamp=String(Math.floor(Date.now()/1000));
    const signature='v1='+createHmac('sha256',Buffer.from(callback.secret,'base64url')).update(timestamp+'.'+bytes).digest('hex');
    const inspection=(await request('/inspection',{target:receiver})).value;
    check(inspection.events.some(e=>e.event_id===payload.event_id&&e.count===1),'signed_real_delivery');
    await request('/callback',{target:receiver,method:'POST',body:payload,expected:204,headers:{'Payment-Event-Id':payload.event_id,'Payment-Timestamp':timestamp,'Payment-Secret-Version':String(callback.secret_version),'Payment-Signature':signature}});
    const duplicate=(await request('/inspection',{target:receiver})).value;responses.push(duplicate);
    check(duplicate.count===inspection.count&&duplicate.duplicates===inspection.duplicates+1,'receiver_deduplication');
    phase='refund';const refund=await post(`/api/v1/admin/payments/${donation.payment_id}/refunds`,{amount_minor:100,currency:'EUR',reason_code:'REQUESTED_BY_CUSTOMER'},202);
    await post(`/api/v1/admin/payments/${donation.payment_id}/fake-events`,{target:'REFUND',event_type:'REFUND_SUCCEEDED',sequence:9,refund_id:refund.refund_id},202);
    await wait(async()=>(await get(`/api/v1/admin/payments/${donation.payment_id}`)).payment.status==='PARTIALLY_REFUNDED');
    await wait(async()=>(await get(`/api/v1/admin/payments/${donation.payment_id}`)).outbox_events.every(e=>e.status==='DELIVERED'));
    async function financial(){const result=[];for(const table of ['payments','payment_events','outbox_events','outbox_deliveries','outbox_retry_requests']){
      const filter=table==='payments'?'id':table==='outbox_deliveries'||table==='outbox_retry_requests'?'outbox_event_id in (select id from outbox_events where payment_id':'payment_id';
      const predicate=filter.includes(' in (')?`${filter}=$1::uuid)`:`${filter}=$1::uuid`;
      result.push(await sql.unsafe(`select row_to_json(t)::text as row from ${table} t where ${predicate} order by id`,[donation.payment_id]));}return JSON.stringify(result);}
    phase='anonymization';const before=await financial();await post(`/api/v1/admin/sites/${site.id}/customers/${customer.customer.id}/anonymization`,{confirmation:customer.customer.id},200);
    check((await financial())===before,'anonymization_financial_invariance');
    const removed=await sql`select message_state='REDACTED' and message_ciphertext is null and message_nonce is null and message_key_version is null as removed from donations where id=${donation.donation.donation_id}::uuid`;
    check(removed.length===1&&removed[0].removed,'anonymization_message_deletion');await request('/api/v1/customer/donations',{headers:auth,expected:401});
    phase='catalog';const product=await post(`/api/admin/v1/sites/${site.id}/products`,{key:'catalog-smoke',name:'Catalog',description:'Local smoke'});
    const price=await post(`/api/admin/v1/products/${product.id}/prices`,{price_key:'catalog-smoke',type:'ONE_TIME',amount_minor:500,currency:'EUR',trial_days:0,tax_mode:'INCLUSIVE'});await post(`/api/admin/v1/prices/${price.id}/publish`,{},200);
    const catalog=(await request(`/api/v1/public/sites/${site.key}/checkouts`,{method:'POST',expected:201,headers:{Origin:origin,'Idempotency-Key':randomUUID()},body:{price_key:price.price_key}})).value;await settle(catalog);
    const v1=await sql`select payload from outbox_events where payment_id=${catalog.payment_id}::uuid and callback_type='payment.succeeded'`;
    check(v1.length===1&&callbackShape(v1[0].payload,1),'catalog_v1_unchanged');
    phase='privacy';const scans=[];
    for(const table of ['donations','payments','payment_events','outbox_events','outbox_deliveries','audit_log','idempotency_records']){
      const rows=await sql.unsafe(`select row_to_json(t)::text as row from ${table} t limit 10000`),text=JSON.stringify(rows);check(rows.length<10000,'scan_row_bound');
      check(messages.every(message=>!text.includes(message)),'database_message_scan');scans.push({sink:table,rows:rows.length,matches:0});}
    check(messages.every(message=>!JSON.stringify(responses).includes(message)),'safe_history_receiver_scan');
    for(const name of ['PAYMENT_SMOKE_BACKEND_OWNER','PAYMENT_SMOKE_RECEIVER_OWNER']){
      check(/^[0-9a-f]{64}$/.test(env[name]??'')&&/^\d{4}-\d\d-\d\dT/.test(env.PAYMENT_SMOKE_LOG_SINCE??''),'log_owner_boundary');
      const result=spawnSync('docker',['logs','--since',env.PAYMENT_SMOKE_LOG_SINCE,env[name]],{encoding:'utf8',maxBuffer:8000000,timeout:10000});
      check(result.status===0&&!result.error,'log_scan_child');const text=(result.stdout??'')+(result.stderr??'');check([...known].every(value=>!text.includes(value)),'runtime_log_privacy');scans.push({sink:name==='PAYMENT_SMOKE_BACKEND_OWNER'?'owned-backend-log':'owned-receiver-log',bytes:Buffer.byteLength(text),matches:0});}
    return {result:'PASS',sites:2,anonymous:true,emailOtp:true,providerFinality:true,v2SignedReceiver:true,deduplicated:true,customerPartnerAdminIsolation:true,refundReconcile:true,anonymizedMessageRemoved:true,financialFactsUnchanged:true,catalogV1:true,finitePrivacy:{scans,historyAndReceiverResponses:responses.length,metrics:'NOT EXPOSED / NOT SCANNED; real MeterRegistry integration evidence is separate',matches:0,scope:'exact in-memory fixtures; bounded named database/log/history/receiver sinks, before shutdown'}};
  } catch {const failure=new Error('stage3b_smoke_verification_failed');failure.phase=phase;throw failure;}
  finally {if(sql)await sql.end({timeout:2});}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  try {console.log(JSON.stringify(await runSmoke()));}
  catch(error) {console.error(JSON.stringify({result:'FAIL',reason:'stage3b_smoke_verification_failed',phase:['configuration','login','provision','callback','anonymous','email-otp','customer','isolation','signed-dedup','refund','anonymization','catalog','privacy'].includes(error?.phase)?error.phase:'cleanup'}));process.exitCode=1;}
}
