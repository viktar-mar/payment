import test, {after, mock} from 'node:test';
import assert from 'node:assert/strict';

test('factory reuses one own-data env capture despite a substituting Proxy get trap',async()=>{
  const {createSandboxAcceptanceExtras:create}=await import('./stripe-sandbox-extras.mjs');
  for(const exotic of [false,true]){
    let gets=0,captures=0,configuration=0,dependencies=0;
    const env=Object.assign(exotic?Object.create({unrelated:'ignored'}):{},{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'});
    const input=new Proxy({env,get bootstrap(){configuration++;throw null;}},{get(target,key){if(key==='env'){gets++;return {};}return Reflect.get(target,key);},getOwnPropertyDescriptor(target,key){if(key==='env')captures++;return Reflect.getOwnPropertyDescriptor(target,key);}});
    const trap=new Proxy({},{get(){dependencies++;throw null;}});
    await assert.rejects(()=>create(input,trap,trap));
    assert.equal(gets,0);assert.equal(captures,1);assert.equal(configuration,1);assert.equal(dependencies,0);
  }
});

// Break: new-mode exclusion after normalization or evaluating an accessor.
test('remainder exclusion precedes snapshots configuration and dependencies',async()=>{
  const {createSandboxAcceptanceExtras:accept}=await import('./stripe-sandbox-extras.mjs'); const base={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'};
  let reads=0;const trap=new Proxy({},{get(){reads++;throw null;}});
  for(const shape of ['true','malformed','boolean','accessor','hidden','inherited']){
    const raw=shape==='inherited'?Object.assign(Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'}),base):{...base};
    if(shape!=='inherited')Object.defineProperty(raw,'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',shape==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:shape!=='hidden',value:shape==='true'||shape==='hidden'?'true':shape==='boolean'?false:{private:'canary'}});
    await assert.rejects(accept({env:raw,get bootstrap(){reads++;throw null;},get runId(){reads++;throw null;}},trap,trap),/OPT_IN_REQUIRED/);
  }
  assert.equal(reads,0);
});
import childProcess from 'node:child_process';
import net from 'node:net';
import filesystem from 'node:fs/promises';
import {syncBuiltinESMExports} from 'node:module';
import {constants} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createSandboxAcceptanceExtras} from './stripe-sandbox-extras.mjs';
test('full extras refuse manual flag before configuration/dependency access',async()=>{
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  await assert.rejects(createSandboxAcceptanceExtras({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true'},get bootstrap(){touched++;throw null;}},trap),{message:'stripe_sandbox_extras_OPT_IN_REQUIRED'});
  assert.equal(touched,0);
});

const forbidden = () => { throw new Error('synthetic_test_forbids_external_boundary'); };
for (const name of ['execFile', 'spawn']) mock.method(childProcess, name, forbidden);
mock.method(net, 'createServer', forbidden);
mock.method(globalThis, 'fetch', forbidden);
for (const name of ['open', 'readFile', 'lstat', 'realpath', 'rename', 'unlink']) mock.method(filesystem, name, forbidden);
syncBuiltinESMExports();
after(() => { mock.restoreAll(); syncBuiltinESMExports(); });

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const RUN = '11111111-2222-4333-8444-555555555555';
const SUFFIX = RUN.replaceAll('-', '');
const DIR = ROOT + '/test-results/stripe-sandbox/' + RUN;
const uuid = n => '00000000-0000-4000-8000-' + String(n).padStart(12, '0');
const ACCOUNT = uuid(1), SITE = uuid(2), PRODUCT = uuid(3), PRICE = uuid(4), PROVIDER = uuid(5), CAMPAIGN = uuid(6);
const TOKEN = Buffer.alloc(32, 4).toString('base64url');
const limits = () => ({deadline: Date.now() + 120000});
const rejects = (promise, code = 'PENDING') => assert.rejects(promise, {message: 'stripe_sandbox_extras_' + code});

function fixture() {
  const events = [], files = new Map();
  let serial = 0, credentialsHeld = false;
  const add = (path, directory, contents = '') => files.set(path, {directory, contents:Buffer.from(contents), uid:process.getuid(), mode:directory ? 0o40700 : 0o100600, nlink:1, dev:1, ino:++serial});
  for (let path = DIR; path !== '/'; path = dirname(path)) add(path, true);
  add('/', true);
  const stat = entry => ({...entry, isFile:()=>!entry.directory, isDirectory:()=>entry.directory, isSymbolicLink:()=>Boolean(entry.symlink)});
  const need = path => { const entry = files.get(path); if (!entry) throw Object.assign(new Error(), {code:'ENOENT'}); return entry; };
  const topology = {runId:RUN, expectedSource:'e'.repeat(40), daemon:'unix:///Users/test/docker.sock', project:'stripe-'+RUN, siteKey:'s5a-'+SUFFIX,
    ports:{backend:18080, demo:18081, receiver:18082, postgres:15432, mailpitHttp:18025, mailpitSmtp:11025},
    endpoints:{backend:'http://127.0.0.1:18080', demo:'http://127.0.0.1:18081', receiver:'http://127.0.0.1:18082'},
    status:'LIVE', webhookReady:true, containers:Object.fromEntries(['postgres','mailpit','backend','demo-site','callback-receiver'].map((name,i)=>[name,{id:String(i+1).repeat(64)}]))};
  const provisioned = {...structuredClone(topology), status:'READY', owners:{project:topology.project,backend:'3'.repeat(64),demo:'4'.repeat(64),receiver:'5'.repeat(64)},
    account:{id:ACCOUNT,key:'stripe-sandbox-default',externalId:'acct_1UPiMpELswAd8tpR'}, site:{id:SITE,key:topology.siteKey},
    product:{id:PRODUCT,key:'s5a-product-'+SUFFIX}, price:{id:PRICE,key:'s5a-price-'+SUFFIX,version:1,providerPriceId:'price_original'},
    identityProvider:{id:PROVIDER,key:'s5a-email-'+SUFFIX,mode:'EMAIL_OTP',status:'ACTIVE',readiness:'READY',version:0},
    campaign:{id:CAMPAIGN,key:'s5a-donate-'+SUFFIX,status:'ACTIVE',version:1},
    cases:Object.fromEntries(['success','decline','sca','missed'].map((name,i)=>[name,{checkoutId:uuid(11+i),paymentId:uuid(21+i)}]))};
  const details = new Map(), operations = new Map();
  const receiver = {count:0, duplicates:0, events:[]};
  const mailbox = {messages:[], messages_count:0, start:0, total:0};
  const messages = new Map();
  function response(status, value = null, cookies = []) {
    const bytes = Buffer.from(value === null ? '' : JSON.stringify(value));
    return {status, headers:{getSetCookie:()=>cookies}, body:new ReadableStream({start(controller){if(bytes.length)controller.enqueue(bytes);controller.close();}})};
  }
  function payment(id, source = 'DONATION', customer = null) {
    return {payment:{payment_id:id,site_id:SITE,payment_account_id:ACCOUNT,source,price_id:source==='CATALOG'?PRICE:null,
      price_key:source==='CATALOG'?provisioned.price.key:null,price_version:source==='CATALOG'?1:null,external_order_id:null,
      amount_minor:source==='CATALOG'?2000:500,currency:'EUR',tax_mode:'NONE',tax_amount_minor:null,status:'CREATED',provider:'STRIPE',
      refundable_amount_minor:0,refunded_amount_minor:0,created_at:new Date().toISOString(),updated_at:new Date().toISOString(),customer_id:customer,
      donation_id:source==='DONATION'?uuid(id===uuid(30)?31:41):null,campaign_id:source==='DONATION'?CAMPAIGN:null,
      campaign_key:source==='DONATION'?provisioned.campaign.key:null,campaign_version:source==='DONATION'?1:null,
      subscription_id:null,billing_record_id:null,subscription_billing_kind:null,subscription_period_start:null,subscription_period_end:null},
      attempts:[],events:[],refunds:[],outbox_events:[],deliveries:[]};
  }
  function prepared(id) {
    const d = details.get(id), opId = uuid(id === uuid(24)?70:id === uuid(30)?71:72), intent = 'pi_'+(id === uuid(24)?'missed':id === uuid(30)?'anonymous':'identified');
    d.attempts = [{id:uuid(id===uuid(24)?80:id===uuid(30)?81:82),attempt_number:1,status:'CREATED',provider_intent_reference:intent,payment_method_family:'CARD',result_code:null,created_at:new Date().toISOString(),updated_at:new Date().toISOString()}];
    operations.set(id,[{operation_id:opId,payment_id:id,account_id:ACCOUNT,account_mode:'SANDBOX',kind:'PAYMENT_PREPARE',status:'ACKNOWLEDGED',reference:intent,first_dispatched_at:new Date().toISOString(),retry_before:null,next_attempt_at:null,safe_error_code:null,resource_id:id,site_id:SITE,manual_review_required:false}]);
    return {operationId:opId,intentId:intent};
  }
  function succeed(id) {
    const d = details.get(id), eventId = uuid(id===uuid(24)?90:id===uuid(30)?91:92);
    d.payment.status='SUCCEEDED';d.payment.refundable_amount_minor=d.payment.amount_minor;d.attempts[0].status='SUCCEEDED';
    d.events.push({id:eventId,sequence:1,event_type:'PAYMENT_SUCCEEDED',source:'PROVIDER_EVENT',amount_minor:d.payment.amount_minor,currency:'EUR',provider_event_id:'evt_original',provider_object_id:operations.get(id)[0].reference,request_id:'safe-request',occurred_at:new Date().toISOString(),received_at:new Date().toISOString()});
    d.outbox_events.push({id:eventId,callback_type:'payment.succeeded',status:'DELIVERED',attempt_count:1,next_attempt_at:null,created_at:new Date().toISOString(),updated_at:new Date().toISOString()});
    d.deliveries.push({id:uuid(100),outbox_event_id:eventId,attempt_number:1,delivery_mode:'AUTOMATIC',status:'SUCCEEDED',http_status:204,error_category:null,started_at:new Date().toISOString(),finished_at:new Date().toISOString()});
    receiver.events.push({event_id:eventId,event_type:'payment.succeeded',count:1});receiver.count=receiver.events.length;
    return eventId;
  }
  details.set(uuid(24),payment(uuid(24),'CATALOG'));
  const bootstrap = {resources:()=>topology, verify:async context=>{assert.equal(credentialsHeld,false);events.push({type:'verify',context});return true;},
    withAdminCredentials:async callback=>{credentialsHeld=true;try{assert.equal(callback(Object.freeze({username:'generated-admin',password:'synthetic-password'})),true);return true;}finally{credentialsHeld=false;}}};
  const provisioner = {resources:()=>provisioned, withMissedCheckout:callback=>callback(Object.freeze({checkoutToken:TOKEN,...provisioned.cases.missed}))};
  const cli = {validateBinding:async context=>{events.push({type:'binding',context});return true;},bindMissedIntent:async proof=>{events.push({type:'bind',proof});return true;},
    stopListener:async context=>{events.push({type:'stop',context});return true;},inspectIntent:async context=>{events.push({type:'inspect',context});return Object.freeze({captured:true});}};
  const options = {env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'},bootstrap,provisioner,cli};
  const dependencies = {execFile:forbidden,now:Date.now,setTimeout,clearTimeout,lstat:async path=>stat(need(path)),realpath:async path=>{need(path);return path;},
    open:async(path,flags,mode)=>{events.push({type:'open',path,flags,mode});if(flags&constants.O_CREAT){if(files.has(path)&&flags&constants.O_EXCL)throw null;add(path,false);}
      const entry=need(path);if(entry.symlink&&flags&constants.O_NOFOLLOW)throw null;
      return {stat:async()=>stat(entry),writeFile:async text=>{events.push({type:'write',path});entry.contents=Buffer.from(text);},sync:async()=>events.push({type:'sync',path}),close:async()=>events.push({type:'close',path})};},
    rename:async(from,to)=>{events.push({type:'rename',from,to});files.set(to,need(from));files.delete(from);},
    unlink:async path=>{events.push({type:'unlink',path});need(path);files.delete(path);},
    fetch:async(url,settings)=>{assert.equal(credentialsHeld,false);events.push({type:'http',url,settings});const u=new URL(url),path=u.pathname;
      if(path==='/api/admin/v1/auth/sessions')return response(204,null,['PAYMENT_ADMIN_SESSION=session-test; Path=/; HttpOnly','XSRF-TOKEN=csrf-test; Path=/']);
      if(path==='/inspection')return response(200,receiver);
      if(path==='/api/v1/messages')return response(200,mailbox);
      if(path.startsWith('/api/v1/message/'))return response(200,messages.get(path.slice('/api/v1/message/'.length)));
      if(path==='/api/v1/admin/provider-operations')return response(200,operations.get(u.searchParams.get('payment_id'))??[]);
      if(path.endsWith('/reconcile')){const id=path.split('/').at(-2);succeed(id);const event=details.get(id).events.at(-1);event.source='RECONCILIATION';event.request_id=settings.headers['X-Request-Id'];return response(200,details.get(id));}
      if(path.startsWith('/api/v1/admin/payments/'))return response(200,details.get(path.split('/').at(-1)));
      if(path==='/api/v1/public/checkouts/'+uuid(14))return response(200,{checkout_id:uuid(14),payment_id:uuid(24),amount_minor:2000,currency:'EUR',provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'CREATED',expires_at:new Date(Date.now()+3600000).toISOString(),price:{price_id:PRICE,price_key:provisioned.price.key,version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE'}});
      throw new Error('unexpected synthetic route');}};
  const ledger = () => JSON.parse(need(DIR+'/extras.json').contents);
  return {options,dependencies,events,files,topology,provisioned,details,operations,receiver,mailbox,messages,response,payment,prepared,succeed,ledger};
}

test('absent opt-ins reject without touching any configuration or dependency getter',async()=>{
  let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  const options=new Proxy({env:{}},{get(target,key){if(key==='env')return target.env;touched++;throw null;}});
  await rejects(createSandboxAcceptanceExtras(options,trap),'OPT_IN_REQUIRED');assert.equal(touched,0);
});

test('open proves ownership and binding then acquires durable private evidence before login',async()=>{
  const f=fixture(),fetcher=f.dependencies.fetch;
  f.dependencies.fetch=async(url,settings)=>{assert.equal(f.files.has(DIR+'/extras.lock'),true);assert.equal(f.ledger().status,'OPENING');assert.equal(f.events.at(-1).type,'verify');return fetcher(url,settings);};
  const opened=await createSandboxAcceptanceExtras(f.options,f.dependencies);assert.equal(f.events.length,0);assert.equal(await opened.open(limits()),true);
  assert.equal(opened.resources().status,'OPEN');assert.equal(Object.isFrozen(opened.resources()),true);
  assert.ok(f.events.some(event=>event.type==='binding'));assert.ok(f.events.some(event=>event.type==='rename'));
  await rejects(opened.open(limits()));assert.equal(opened.resources().status,'PENDING');
});

test('donation create key dispatch is durable and genuine safe ACK binds anonymous customer and original PI',async()=>{
  const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
  assert.equal(await e.beforeDonationCreate('anonymous',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:false},limits()),'s5a:'+RUN+':donation:anonymous');
  assert.equal(f.ledger().donations.anonymous.phase,'DISPATCHING');
  f.details.set(uuid(30),f.payment(uuid(30)));await e.ackDonationCreate('anonymous',{checkoutId:uuid(32),paymentId:uuid(30),donationId:uuid(31),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'},limits());
  f.prepared(uuid(30));await e.beforeDonationPay('anonymous',limits());assert.equal(f.ledger().donations.anonymous.phase,'PAY_DISPATCHING');
  f.succeed(uuid(30));assert.equal(await e.confirmDonation('anonymous',limits()),true);
  assert.equal(e.resources().donations.anonymous.confirmed,true);assert.equal(f.ledger().donations.anonymous.intentId,'pi_anonymous');
  const text=f.files.get(DIR+'/extras.json').contents.toString();for(const secret of [TOKEN,'generated-admin','synthetic-password','session-test','csrf-test'])assert.equal(text.includes(secret),false);
});

function deliverOtp(f, {id='OpaqueMail1', code='123456', date, created}={}) {
  const email=RUN+'@example.test', time=new Date().toISOString();
  const address={Name:'',Address:email}, sender={Name:'',Address:'no-reply@payment.local'};
  const summary={ID:id,Created:created??time,To:[address],From:sender,Subject:'Your verification code',Size:100,Read:false,Attachments:0,Snippet:'redacted',Tags:[]};
  f.mailbox.messages.push(summary);f.mailbox.messages_count=f.mailbox.total=f.mailbox.messages.length;
  f.messages.set(id,{ID:id,Date:date??time,To:[structuredClone(address)],From:structuredClone(sender),Subject:'Your verification code',Text:'Your verification code is '+code+'. It expires in 10 minutes.',HTML:'',Cc:[],Bcc:[],ReplyTo:[],ReturnPath:'no-reply@payment.local',Attachments:[],Inline:[]});
}
async function challenge(f,e) {
  await e.beginOtp(value=>{
    assert.deepEqual(value,{email:RUN+'@example.test',siteKey:f.provisioned.site.key});assert.equal(Object.isFrozen(value),true);
    assert.equal(f.ledger().otp.phase,'CHALLENGE_DISPATCHING');return true;
  },limits());
  await e.ackOtpChallenge({challengeId:uuid(50),expiresAt:new Date(Date.now()+600000).toISOString()},limits());
}
async function identify(f,e) {
  await challenge(f,e);deliverOtp(f);let handed='';
  await e.withOtpCode(value=>{handed=value;assert.equal(f.ledger().otp.phase,'CODE_DISPATCHING');return true;},limits());
  assert.equal(handed,'123456');await e.beforeOtpVerify(limits());
  assert.equal(f.ledger().otp.phase,'VERIFY_DISPATCHING');
  await e.ackOtpVerification({challengeId:uuid(50),customerId:uuid(51)},limits());
}

test('OTP sends one memory-only recipient/code and identified donation binds exact verified customer',async()=>{
  const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
  await identify(f,e);assert.equal(e.resources().otp.phase,'VERIFIED');
  await e.beforeDonationCreate('emailOtp',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:true},limits());
  f.details.set(uuid(40),f.payment(uuid(40),'DONATION',uuid(51)));
  await e.ackDonationCreate('emailOtp',{checkoutId:uuid(42),paymentId:uuid(40),donationId:uuid(41),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'},limits());
  f.prepared(uuid(40));await e.beforeDonationPay('emailOtp',limits());f.succeed(uuid(40));await e.confirmDonation('emailOtp',limits());
  const text=f.files.get(DIR+'/extras.json').contents.toString();
  for(const privateText of ['@example.test','123456','Your verification code','OpaqueMail1','session-test'])assert.equal(text.includes(privateText),false);
  assert.equal(e.resources().donations.emailOtp.confirmed,true);
});

test('OTP refuses existing recipient mail and never invokes the challenge callback',async()=>{
  const f=fixture();deliverOtp(f);const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());let calls=0;
  await rejects(e.beginOtp(()=>{calls++;return true;},limits()));assert.equal(calls,0);assert.equal(e.resources().status,'PENDING');
});

test('OTP refuses ambiguous, stale, foreign, and multiple-code messages before code handoff',async()=>{
  for(const mutate of [f=>deliverOtp(f,{id:'OtherMail2'}),f=>f.mailbox.messages[0].Created='2020-01-01T00:00:00Z',
    f=>f.messages.get('OpaqueMail1').ID='ForeignId',f=>f.messages.get('OpaqueMail1').Text='123456 and 654321',
    f=>f.messages.get('OpaqueMail1').To[0].Address='foreign@example.test',f=>f.messages.get('OpaqueMail1').From.Address='other@payment.local']) {
    const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());await challenge(f,e);deliverOtp(f);mutate(f);let calls=0;
    await rejects(e.withOtpCode(()=>{calls++;return true;},limits()));assert.equal(calls,0);
  }
});

test('missed fixture latches the original operation and stops proven listener before Pay then requires new reconcile journal',async()=>{
  const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
  const fourth=await e.missedFixture(limits());assert.equal(await fourth.beginBrowser('success'),true);
  const session=await fourth.browserSession('success');assert.equal(session.version,2);assert.equal(session.checkoutToken,TOKEN);
  f.prepared(uuid(24));await fourth.markPay('success');
  assert.equal(f.ledger().missed.phase,'PAY_DISPATCHING');
  const bind=f.events.find(event=>event.type==='bind');assert.deepEqual(bind.proof,{paymentId:uuid(24),siteId:SITE,operationId:uuid(70),intentId:'pi_missed',amountMinor:2000,currency:'EUR',source:'CATALOG',externalAccountId:'acct_1UPiMpELswAd8tpR'});
  assert.ok(f.events.findIndex(event=>event.type==='stop')>f.events.indexOf(bind));
  assert.equal(await fourth.confirm('success'),true);assert.equal(e.resources().missed.confirmed,true);
  const posts=f.events.filter(event=>event.type==='http'&&event.settings.method==='POST'&&!event.url.endsWith('/auth/sessions'));
  assert.equal(posts.length,1);assert.equal(posts[0].settings.headers['Idempotency-Key'],'s5a:'+RUN+':missed:reconcile');
  assert.equal(f.ledger().missed.negativeBaseline.journalIds.length,0);
  await fourth.close();await e.close(limits());assert.equal(f.files.has(DIR+'/extras.lock'),false);
});

test('missed fixture rejects retry arguments, no-op reconciliation and unsupported refunds',async()=>{
  for(const scenario of ['retry','noop','refunds']) {
    const f=fixture(),fetcher=f.dependencies.fetch;
    if(scenario==='noop')f.dependencies.fetch=async(url,settings)=>url.endsWith('/reconcile')?f.response(200,f.details.get(uuid(24))):fetcher(url,settings);
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());const fourth=await e.missedFixture(limits());
    if(scenario==='refunds'){await rejects(fourth.refunds(),'UNSUPPORTED_OPERATION');continue;}
    await fourth.beginBrowser('success');await fourth.browserSession('success');f.prepared(uuid(24));
    if(scenario==='retry'){await rejects(fourth.markPay('success',false));assert.equal(f.events.some(event=>event.type==='stop'),false);continue;}
    await fourth.markPay('success');await rejects(fourth.confirm('success'));assert.equal(e.resources().missed.confirmed,false);
  }
});

async function anonymous(f,e) {
  await e.beforeDonationCreate('anonymous',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:false},limits());
  f.details.set(uuid(30),f.payment(uuid(30)));
  await e.ackDonationCreate('anonymous',{checkoutId:uuid(32),paymentId:uuid(30),donationId:uuid(31),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'},limits());
  f.prepared(uuid(30));await e.beforeDonationPay('anonymous',limits());
}
async function fourthPay(f,e,context=limits()) {
  const fourth=await e.missedFixture(context);await fourth.beginBrowser('success');await fourth.browserSession('success');f.prepared(uuid(24));await fourth.markPay('success');return fourth;
}

test('extra unmatched payment-success outbox cannot certify donation success',async()=>{
  const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());await anonymous(f,e);f.succeed(uuid(30));
  f.details.get(uuid(30)).outbox_events.push({...f.details.get(uuid(30)).outbox_events[0],id:uuid(199)});
  await rejects(e.confirmDonation('anonymous',limits()));assert.equal(e.resources().donations.anonymous.confirmed,false);
});

test('missed limits getter failures are redacted and latch pending',async()=>{
  const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
  await rejects(e.missedFixture(new Proxy({},{get(){throw new Error('private-canary');}})));assert.equal(e.resources().status,'PENDING');
});

test('fixture close cannot overlap and release its lifetime while browserSession owns IO',async()=>{
  const f=fixture(),fetcher=f.dependencies.fetch;let release,entered;
  const ready=new Promise(resolveReady=>entered=resolveReady),blocked=new Promise(resolveBlocked=>release=resolveBlocked);
  f.dependencies.fetch=async(url,settings)=>{if(url.includes('/api/v1/public/checkouts/')){entered();await blocked;}return fetcher(url,settings);};
  const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());const fourth=await e.missedFixture(limits());await fourth.beginBrowser('success');
  const session=fourth.browserSession('success');await ready;
  try{await rejects(fourth.close(),'BUSY');}finally{release();await session;}
  await fourth.close();
});

test('scheduler success between last negative baseline and our POST cannot certify no-op reconcile',async()=>{
  const f=fixture(),fetcher=f.dependencies.fetch;let posts=0;
  f.dependencies.fetch=async(url,settings)=>{
    if(url.endsWith('/reconcile')){
      posts++;
      assert.equal(f.ledger().missed.phase,'RECONCILE_DISPATCHING');f.succeed(uuid(24));
      const event=f.details.get(uuid(24)).events.at(-1);event.source='RECONCILIATION';event.request_id='scheduler-other-request';
      return f.response(200,f.details.get(uuid(24)));
    }return fetcher(url,settings);
  };
  const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());const fourth=await fourthPay(f,e);
  await rejects(fourth.confirm('success'));assert.equal(e.resources().missed.confirmed,false);
  assert.equal(posts,1);
});

test('constructor rejects malformed binding before dependencies and snapshots methods without IO',async()=>{
  for(const mutate of [f=>f.topology.runId=[RUN],f=>f.topology.expectedSource=['e'.repeat(40)],f=>f.topology.ports.backend='18080',
    f=>f.topology.endpoints.backend='http://localhost:18080',f=>f.topology.containers.backend.id=['3'.repeat(64)],
    f=>f.provisioned.account.id=[ACCOUNT],f=>f.provisioned.status='PENDING',f=>f.provisioned.site.key='foreign',
    f=>f.provisioned.campaign.version='1',f=>f.provisioned.price.version=2,f=>f.provisioned.identityProvider.readiness='NOT_READY',
    f=>f.provisioned.cases.missed.paymentId=f.provisioned.cases.success.paymentId,f=>f.options.env.DEBUG='*']) {
    const f=fixture();mutate(f);let getters=0;
    await rejects(createSandboxAcceptanceExtras(f.options,new Proxy({},{get(){getters++;throw null;}})),'CONFIGURATION');assert.equal(getters,0);
  }
  const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);
  assert.equal(f.events.length,0);assert.equal(Object.isFrozen(e),true);
  f.dependencies.fetch=forbidden;f.options.bootstrap.verify=forbidden;f.options.provisioner.resources=forbidden;f.options.cli.validateBinding=forbidden;
  assert.equal(await e.open(limits()),true);assert.equal(Object.isFrozen(e.resources().donations.anonymous),true);
});

test('provider and fresh ownership refusal precede admin credentials and every subsequent credential request',async()=>{
  for(const mode of ['provider','ownership','topology','provision']) {
    const f=fixture();let credentials=0;
    const handoff=f.options.bootstrap.withAdminCredentials;f.options.bootstrap.withAdminCredentials=async callback=>{credentials++;return handoff(callback);};
    if(mode==='provider')f.options.cli.validateBinding=async()=>false;
    if(mode==='ownership')f.options.bootstrap.verify=async()=>false;
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);
    if(mode==='topology')f.topology.status='STOPPED';if(mode==='provision')f.provisioned.status='PENDING';
    await rejects(e.open(limits()));assert.equal(credentials,0);assert.equal(f.events.some(event=>event.type==='http'),false);
  }
  const f=fixture();let verified=0,refuse=false;
  f.options.bootstrap.verify=async()=>{verified++;return !refuse;};
  const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
  await e.beforeDonationCreate('anonymous',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:false},limits());
  refuse=true;const requests=f.events.filter(event=>event.type==='http').length;
  await rejects(e.ackDonationCreate('anonymous',{checkoutId:uuid(32),paymentId:uuid(30),donationId:uuid(31),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'},limits()));
  assert.equal(f.events.filter(event=>event.type==='http').length,requests);assert.ok(verified>=4);
});

test('all transport calls use exact origins, redirect refusal, fresh verification and scoped backend credentials',async()=>{
  const f=fixture(),fetcher=f.dependencies.fetch;
  f.dependencies.fetch=async(url,settings)=>{
    assert.equal(f.events.at(-1).type,'verify');assert.equal(settings.redirect,'error');assert.ok(settings.signal instanceof AbortSignal);
    const u=new URL(url);assert.ok(['http://127.0.0.1:18080','http://127.0.0.1:18082','http://127.0.0.1:18025'].includes(u.origin));
    if(u.pathname.startsWith('/api/v1/admin/'))assert.equal(settings.headers.Cookie,'PAYMENT_ADMIN_SESSION=session-test; XSRF-TOKEN=csrf-test');
    else assert.equal(settings.headers.Cookie,undefined);
    if(settings.method==='POST'&&u.pathname.endsWith('/reconcile')) {
      assert.equal(f.ledger().missed.phase,'RECONCILE_DISPATCHING');assert.equal(settings.headers['X-XSRF-TOKEN'],'csrf-test');
      assert.equal(settings.headers['X-Request-Id'],'s5a:'+RUN+':missed:reconcile');assert.equal(settings.body,undefined);
    }
    return fetcher(url,settings);
  };
  const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());await identify(f,e);await anonymous(f,e);f.succeed(uuid(30));await e.confirmDonation('anonymous',limits());
  const fourth=await fourthPay(f,e);await fourth.confirm('success');
  assert.equal(f.events.filter(event=>event.type==='binding').length,1);
});

test('bounded transport rejects oversized, invalid UTF8, malformed JSON and error bodies without exposing them',async()=>{
  for(const mode of ['oversize','utf8','json','http','cookie','throw']) {
    const f=fixture(),fetcher=f.dependencies.fetch;let cancelled=0,properties=0,broken=mode==='cookie';
    f.dependencies.fetch=async(url,settings)=>{
      if(!broken)return fetcher(url,settings);
      if(mode==='throw')throw new Proxy({},{get(){properties++;throw null;}});
      if(mode==='cookie')return f.response(204,null,['PAYMENT_ADMIN_SESSION=private; Path=/; Domain=foreign','XSRF-TOKEN=private; Path=/']);
      const bytes=mode==='oversize'?Buffer.alloc(65537):mode==='utf8'?Buffer.from([0xff]):Buffer.from('{private-canary');
      const body=new ReadableStream({start(controller){controller.enqueue(bytes);controller.close();}}),getReader=body.getReader.bind(body);
      body.getReader=()=>{const reader=getReader(),cancel=reader.cancel.bind(reader);reader.cancel=()=>{cancelled++;return cancel();};return reader;};
      return {status:mode==='http'?500:200,headers:{getSetCookie:()=>[]},body};
    };
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);
    if(mode==='cookie')await rejects(e.open(limits()));
    else {await e.open(limits());broken=true;await rejects(e.beginOtp(()=>true,limits()));}
    assert.equal(properties,0);
    if(!['cookie','throw'].includes(mode))assert.equal(cancelled,1);
    assert.equal(JSON.stringify(e.resources()).includes('private-canary'),false);
    assert.equal(f.files.has(DIR+'/extras.lock'),true);
  }
});

test('whole request deadline includes ownership, headers and streaming body and propagates abort',async()=>{
  for(const stall of ['ownership','headers','body']) {
    const f=fixture();let verifyCount=0,signal,cancelled=0;
    const verify=f.options.bootstrap.verify;
    f.options.bootstrap.verify=async context=>{verifyCount++;if(stall==='ownership'&&verifyCount===3){signal=context.signal;return new Promise(()=>{});}return verify(context);};
    f.dependencies.setTimeout=(callback,ms)=>setTimeout(callback,ms>1000&&ms<=10000?8:ms);
    f.dependencies.fetch=async(url,settings)=>{
      signal=settings.signal;if(stall==='headers')return new Promise(()=>{});
      if(stall==='body')return {status:204,headers:{getSetCookie:()=>[]},body:new ReadableStream({cancel(){cancelled++;}})};
      throw new Error('must not reach fetch');
    };
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await rejects(e.open(limits()));assert.equal(signal.aborted,true);
    if(stall==='body')assert.equal(cancelled,1);
  }
});

test('invalid caller deadlines and cancellation latch pending without any IO',async()=>{
  for(const value of [{},{deadline:Infinity},{deadline:Date.now()-1},{deadline:Date.now()+1000,signal:{}},
    {deadline:Date.now()+10000,signal:AbortSignal.abort()}]) {
    const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await rejects(e.open(value));assert.equal(f.events.length,0);assert.equal(e.resources().status,'PENDING');
    await rejects(e.open(limits()));
  }
});

test('exclusive private ledger rejects existing state and filesystem ownership mismatches before login',async()=>{
  for(const mode of ['ledger','lock','symlink','permissions','uid','canonical']) {
    const f=fixture();
    if(mode==='ledger'||mode==='lock')f.files.set(DIR+'/extras.'+(mode==='ledger'?'json':'lock'),{...f.files.get(DIR),directory:false,mode:0o100600});
    if(mode==='symlink')f.files.get(DIR).symlink=true;
    if(mode==='permissions')f.files.get(DIR).mode=0o40755;
    if(mode==='uid')f.files.get(DIR).uid++;
    if(mode==='canonical')f.dependencies.realpath=async path=>path===DIR?DIR+'-other':path;
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await rejects(e.open(limits()));assert.equal(f.events.some(event=>event.type==='http'),false);
  }
});

test('unknown create dispatch and failed durable ACK cannot retry, rekey or release evidence',async()=>{
  for(const mode of ['duplicate','ack-sync']) {
    const f=fixture();let breakSync=false;
    const open=f.dependencies.open;
    f.dependencies.open=async(path,...args)=>{const handle=await open(path,...args),sync=handle.sync;handle.sync=async()=>{if(breakSync&&path.includes('/.extras-'))throw null;return sync();};return handle;};
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
    const facts={siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:false};
    const key=await e.beforeDonationCreate('anonymous',facts,limits());
    if(mode==='duplicate')await rejects(e.beforeDonationCreate('anonymous',facts,limits()));
    else {breakSync=true;f.details.set(uuid(30),f.payment(uuid(30)));await rejects(e.ackDonationCreate('anonymous',{checkoutId:uuid(32),paymentId:uuid(30),donationId:uuid(31),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'},limits()));}
    assert.equal(f.ledger().donations.anonymous.phase,'DISPATCHING');assert.equal(f.ledger().donations.anonymous.key,key);
    // Durable OPEN is the last saved state, not evidence that the failed in-memory run can resume.
    assert.equal(f.ledger().status,'OPEN');assert.equal(e.resources().status,'PENDING');
    const calls=f.events.length;
    await rejects(e.beforeDonationCreate('anonymous',facts,limits()));await rejects(e.beforeDonationPay('anonymous',limits()));await rejects(e.missedFixture(limits()));
    assert.equal(f.events.length,calls);assert.equal(f.ledger().donations.anonymous.key,key);assert.equal(f.ledger().donations.anonymous.phase,'DISPATCHING');
    await rejects(e.close(limits()));assert.equal(f.files.has(DIR+'/extras.lock'),true);assert.equal(e.resources().status,'PENDING');
  }
});

test('donation authority facts and safe IDs reject coercion, wrong amount and unexpected customer',async()=>{
  for(const mutate of [v=>v.siteKey='foreign',v=>v.amountMinor=2000,v=>v.hasCustomerAuthority=true,v=>v.hasCustomerAuthority='false',v=>v.extra='secret']) {
    const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
    const facts={siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:false};mutate(facts);
    await rejects(e.beforeDonationCreate('anonymous',facts,limits()));assert.equal(f.ledger().donations.anonymous.phase,'NEW');
  }
  for(const change of [v=>v.checkoutId=[uuid(32)],v=>v.paymentId={toString:()=>uuid(30)},v=>v.campaignVersion='1',v=>v.currency='USD',v=>v.amountMinor=501]) {
    const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
    await e.beforeDonationCreate('anonymous',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:false},limits());
    const value={checkoutId:uuid(32),paymentId:uuid(30),donationId:uuid(31),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'};change(value);
    await rejects(e.ackDonationCreate('anonymous',value,limits()));assert.equal(f.ledger().donations.anonymous.phase,'DISPATCHING');
  }
});

test('protected donation proof rejects wrong site account source customer campaign money price and original PI',async()=>{
  for(const mutate of [d=>d.payment.site_id=uuid(299),d=>d.payment.payment_account_id=uuid(299),d=>d.payment.source='CATALOG',
    d=>d.payment.customer_id=uuid(51),d=>d.payment.campaign_id=uuid(299),d=>d.payment.campaign_version=2,
    d=>d.payment.amount_minor=2000,d=>d.payment.currency='USD',d=>d.payment.price_id=PRICE,d=>d.events[0].provider_object_id='pi_foreign',
    d=>d.attempts[0].id=uuid(299),d=>d.outbox_events[0].id=[uuid(91)]]) {
    const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());await anonymous(f,e);f.succeed(uuid(30));mutate(f.details.get(uuid(30)));
    await rejects(e.confirmDonation('anonymous',limits()));assert.equal(e.resources().donations.anonymous.confirmed,false);
  }
});

test('browser success without complete server journal outbox delivery and receiver joins stays unconfirmed',async()=>{
  for(const mutate of [f=>f.details.get(uuid(30)).events=[],f=>f.details.get(uuid(30)).outbox_events=[],f=>f.details.get(uuid(30)).outbox_events[0].status='PENDING',
    f=>f.details.get(uuid(30)).deliveries[0].http_status=500,f=>f.receiver.events[0].event_id=uuid(299)]) {
    const f=fixture();f.dependencies.setTimeout=(callback,ms)=>setTimeout(callback,ms===1000?1:ms);
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());await anonymous(f,e);f.succeed(uuid(30));mutate(f);
    await rejects(e.confirmDonation('anonymous',limits()));assert.equal(e.resources().donations.anonymous.confirmed,false);
  }
});

test('sole original preparation and original attempt are required before donation Pay authority',async()=>{
  for(const mutate of [f=>f.operations.get(uuid(30)).push({...f.operations.get(uuid(30))[0],operation_id:uuid(299)}),
    f=>f.operations.get(uuid(30))[0].manual_review_required=true,f=>f.operations.get(uuid(30))[0].resource_id=uuid(299),
    f=>f.operations.get(uuid(30))[0].account_mode='LIVE',f=>f.operations.get(uuid(30))[0].status='DISPATCHING',
    f=>f.operations.get(uuid(30))[0].reference=['pi_anonymous'],f=>f.details.get(uuid(30)).attempts[0].provider_intent_reference='pi_other',
    f=>f.details.get(uuid(30)).attempts.push({...f.details.get(uuid(30)).attempts[0],id:uuid(299),attempt_number:2}),
    f=>f.succeed(uuid(30))]) {
    const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
    await e.beforeDonationCreate('anonymous',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:false},limits());
    f.details.set(uuid(30),f.payment(uuid(30)));await e.ackDonationCreate('anonymous',{checkoutId:uuid(32),paymentId:uuid(30),donationId:uuid(31),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'},limits());
    f.prepared(uuid(30));mutate(f);await rejects(e.beforeDonationPay('anonymous',limits()));assert.equal(f.ledger().donations.anonymous.phase,'ACKNOWLEDGED');
  }
});

test('OTP accepts fractional listing time and second precision offset Date within the adjudicated dispatch second',async()=>{
  const f=fixture();const start=Date.now(),clock=Math.floor(start/1000)*1000+321;
  f.dependencies.now=()=>clock;
  const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());await challenge(f,e);
  const second=new Date(Math.floor(clock/1000)*1000).toISOString().replace('.000Z','+00:00');
  deliverOtp(f,{date:second,created:new Date(clock).toISOString().replace('.321Z','.321123456Z')});
  let code;await e.withOtpCode(value=>{code=value;return true;},limits());assert.equal(code,'123456');
});

test('OTP callback refusal and duplicate phases never resend or hand out a second code',async()=>{
  for(const mode of ['begin-async','code-async','duplicate-code','duplicate-challenge','wrong-challenge','expiry','foreign-id','duplicate-after-detail']) {
    const f=fixture();let calls=0;
    const fetcher=f.dependencies.fetch;
    if(mode==='duplicate-after-detail')f.dependencies.fetch=async(url,settings)=>{const result=await fetcher(url,settings);if(url.includes('/api/v1/message/'))deliverOtp(f,{id:'LateDuplicate'});return result;};
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
    if(mode==='begin-async'){await rejects(e.beginOtp(async()=>{calls++;return true;},limits()));assert.equal(calls,1);assert.equal(f.ledger().otp.phase,'CHALLENGE_DISPATCHING');continue;}
    await challenge(f,e);
    if(mode==='duplicate-challenge'){await rejects(e.ackOtpChallenge({challengeId:uuid(50),expiresAt:new Date(Date.now()+600000).toISOString()},limits()));continue;}
    deliverOtp(f);
    if(mode==='foreign-id'){f.mailbox.messages[0].ID=['OpaqueMail1'];await rejects(e.withOtpCode(()=>true,limits()));continue;}
    if(mode==='duplicate-after-detail'){await rejects(e.withOtpCode(()=>{calls++;return true;},limits()));assert.equal(calls,0);continue;}
    if(mode==='code-async'){await rejects(e.withOtpCode(async()=>{calls++;return true;},limits()));assert.equal(calls,1);assert.equal(f.ledger().otp.phase,'CODE_DISPATCHING');continue;}
    await e.withOtpCode(()=>{calls++;return true;},limits());
    if(mode==='duplicate-code'){await rejects(e.withOtpCode(()=>{calls++;return true;},limits()));assert.equal(calls,1);continue;}
    await e.beforeOtpVerify(limits());
    if(mode==='expiry')await rejects(e.ackOtpVerification({challengeId:uuid(50),customerId:[uuid(51)]},limits()));
    else await rejects(e.ackOtpVerification({challengeId:uuid(299),customerId:uuid(51)},limits()));
    assert.equal(f.ledger().otp.phase,'VERIFY_DISPATCHING');
  }
});

test('identified donation rejects a customer other than the original verified customer',async()=>{
  const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());await identify(f,e);
  await e.beforeDonationCreate('emailOtp',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:true},limits());
  f.details.set(uuid(40),f.payment(uuid(40),'DONATION',uuid(299)));
  await rejects(e.ackDonationCreate('emailOtp',{checkoutId:uuid(42),paymentId:uuid(40),donationId:uuid(41),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'},limits()));
  assert.equal(f.ledger().donations.emailOtp.phase,'DISPATCHING');
});

test('missed listener-stop failure never authorizes Pay and readonly capture retry never reconciles without capture',async()=>{
  for(const mode of ['stop','capture']) {
    const f=fixture();let inspected=0;
    f.dependencies.setTimeout=(callback,ms)=>setTimeout(callback,ms===1000?1:ms);
    if(mode==='stop')f.options.cli.stopListener=async()=>false;
    else f.options.cli.inspectIntent=async context=>{inspected++;assert.ok(context.deadline<=Date.now()+20000);throw new Error('private-proof-error');};
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
    const fourth=await e.missedFixture(limits());await fourth.beginBrowser('success');await fourth.browserSession('success');f.prepared(uuid(24));
    if(mode==='stop'){await rejects(fourth.markPay('success'));assert.equal(f.ledger().missed.phase,'LISTENER_STOP_DISPATCHING');}
    else {await fourth.markPay('success');await rejects(fourth.confirm('success'));assert.equal(inspected,10);}
    assert.equal(f.events.some(event=>event.type==='http'&&event.url.endsWith('/reconcile')),false);assert.equal(e.resources().missed.confirmed,false);
  }
});

test('missed confirms only captured original PI and refuses success before reconcile, swapped operations and reused event IDs',async()=>{
  for(const mode of ['already-success','operation','baseline-id']) {
    const f=fixture();
    if(mode==='baseline-id'){
      f.receiver.events.push({event_id:uuid(90),event_type:'payment.succeeded',count:1});f.receiver.count=1;
    }
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());const fourth=await fourthPay(f,e);
    if(mode==='already-success')f.succeed(uuid(24));
    if(mode==='operation')f.operations.get(uuid(24))[0].reference='pi_swapped';
    await rejects(fourth.confirm('success'));assert.equal(e.resources().missed.confirmed,false);
    if(mode!=='baseline-id')assert.equal(f.events.some(event=>event.type==='http'&&event.url.endsWith('/reconcile')),false);
  }
});

test('fourth fixture methods keep the originally captured finite deadline and never renew it',async()=>{
  const f=fixture();let now=Date.now();f.dependencies.now=()=>now;
  const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open({deadline:now+120000});const fourth=await e.missedFixture({deadline:now+1000});
  now+=1001;const calls=f.events.length;await rejects(fourth.beginBrowser('success'));assert.equal(f.events.length,calls);assert.equal(e.resources().status,'PENDING');
});

test('lock release keeps the held exact inode through checked unlink and directory sync',async()=>{
  for(const mode of ['clean','foreign','hardlink','sync-failure']) {
    const f=fixture();let breakSync=false;const open=f.dependencies.open;
    f.dependencies.open=async(path,...args)=>{const handle=await open(path,...args),sync=handle.sync;handle.sync=async()=>{if(breakSync&&path===DIR)throw null;return sync();};return handle;};
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());const before=f.events.length;
    if(mode==='foreign')f.files.set(DIR+'/extras.lock',{...f.files.get(DIR+'/extras.lock'),ino:9000});
    if(mode==='hardlink')f.files.get(DIR+'/extras.lock').nlink=2;
    if(mode==='sync-failure')breakSync=true;
    if(mode==='clean') {
      assert.equal(await e.close(limits()),true);assert.equal(e.resources().status,'CLOSED');
      const events=f.events.slice(before),unlink=events.findIndex(event=>event.type==='unlink');
      const close=events.findIndex(event=>event.type==='close'&&event.path===DIR+'/extras.lock');
      assert.ok(close>unlink);assert.ok(events.slice(unlink+1,close).some(event=>event.type==='sync'&&event.path===DIR));
      assert.equal(f.files.has(DIR+'/extras.lock'),false);
    } else {await rejects(e.close(limits()));assert.equal(f.events.slice(before).some(event=>event.type==='unlink'),false);assert.equal(f.files.has(DIR+'/extras.lock'),true);}
  }
});

test('missing injection hits an independent builtin guard and cleanup errors stay fixed and finite',async()=>{
  for(const mode of ['fetch','open','timer']) {
    const f=fixture();if(mode==='fetch')delete f.dependencies.fetch;if(mode==='open')delete f.dependencies.open;
    if(mode==='timer')f.dependencies.clearTimeout=timer=>{clearTimeout(timer);throw new Error('private-cleanup-error');};
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await rejects(e.open(limits()));assert.equal(e.resources().status,'PENDING');
    assert.equal(f.events.some(event=>event.type==='http'),false);
  }
});

test('swapped temporary ledger inode and lost writer lock stop before authority is returned',async()=>{
  for(const mode of ['temporary','lock']) {
    const f=fixture(),open=f.dependencies.open;let armed=false,swapped=false;
    f.dependencies.open=async(path,...args)=>{
      if(armed&&!swapped&&mode==='temporary'&&path.includes('/.extras-')&&args[0]===(constants.O_RDONLY|constants.O_NOFOLLOW)){
        f.files.set(path,{...f.files.get(path),ino:9000});swapped=true;
      }
      return open(path,...args);
    };
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());armed=true;
    if(mode==='lock')f.files.set(DIR+'/extras.lock',{...f.files.get(DIR+'/extras.lock'),ino:9000});
    const renamed=f.events.filter(event=>event.type==='rename').length;
    await rejects(e.beforeDonationCreate('anonymous',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:false},limits()));
    assert.equal(f.events.filter(event=>event.type==='rename').length,renamed);assert.equal(f.ledger().donations.anonymous.phase,'NEW');
    assert.equal(f.files.has(DIR+'/extras.lock'),true);
  }
});

test('OTP expiry, malformed dates and wrong challenge identity never authorize verification',async()=>{
  for(const mode of ['expired','unbounded','calendar','wrong-id','verify-expired']) {
    const f=fixture();let now=Date.now();f.dependencies.now=()=>now;
    const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
    if(mode==='verify-expired') {
      await e.beginOtp(()=>true,limits());await e.ackOtpChallenge({challengeId:uuid(50),expiresAt:new Date(now+600000).toISOString()},limits());
      deliverOtp(f);await e.withOtpCode(()=>true,limits());now+=600001;
      await rejects(e.beforeOtpVerify({deadline:now+1000}));assert.equal(f.ledger().otp.phase,'CODE_HANDED');continue;
    }
    await e.beginOtp(()=>true,limits());
    const expiresAt=mode==='expired'?new Date(now-1).toISOString():mode==='unbounded'?new Date(now+1800001).toISOString():mode==='calendar'?'2026-02-31T00:00:00Z':new Date(now+600000).toISOString();
    await rejects(e.ackOtpChallenge({challengeId:mode==='wrong-id'?[uuid(50)]:uuid(50),expiresAt},limits()));
    assert.equal(f.ledger().otp.phase,'CHALLENGE_DISPATCHING');
  }
});

test('synchronous OTP callback time counts toward the explicit enclosing deadline',async()=>{
  const f=fixture();let now=Date.now();f.dependencies.now=()=>now;
  const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open({deadline:now+120000});
  const deadline=now+1000;
  await rejects(e.beginOtp(()=>{now+=1001;return true;},{deadline}));assert.equal(e.resources().status,'PENDING');
  assert.equal(f.ledger().otp.phase,'CHALLENGE_DISPATCHING');
});

test('missed capture polling reuses only the latched readonly capability before the one original reconcile',async()=>{
  const f=fixture();let inspected=0;f.dependencies.setTimeout=(callback,ms)=>setTimeout(callback,ms===1000?1:ms);
  f.options.cli.inspectIntent=async context=>{inspected++;assert.deepEqual(Object.keys(context).sort(),['deadline','signal']);if(inspected===1)throw null;return {captured:true};};
  const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());const fourth=await fourthPay(f,e);await fourth.confirm('success');
  assert.equal(inspected,2);assert.equal(f.events.filter(event=>event.type==='bind').length,1);
  assert.equal(f.events.filter(event=>event.type==='http'&&event.url.endsWith('/reconcile')).length,1);
});

test('overlapping lifecycle calls refuse immediately while original known response determines the sole ACK',async()=>{
  const f=fixture(),fetcher=f.dependencies.fetch;let release,entered,block=false;
  const ready=new Promise(resolveReady=>entered=resolveReady),waiting=new Promise(resolveWait=>release=resolveWait);
  f.dependencies.fetch=async(url,settings)=>{if(block&&url.endsWith('/payments/'+uuid(30))){assert.equal(f.ledger().donations.anonymous.phase,'DISPATCHING');entered();await waiting;}return fetcher(url,settings);};
  const e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
  await e.beforeDonationCreate('anonymous',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:false},limits());
  f.details.set(uuid(30),f.payment(uuid(30)));block=true;
  const ack=e.ackDonationCreate('anonymous',{checkoutId:uuid(32),paymentId:uuid(30),donationId:uuid(31),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'},limits());
  await ready;
  try {await rejects(e.beginOtp(()=>true,limits()),'BUSY');assert.equal(f.ledger().donations.anonymous.phase,'DISPATCHING');}
  finally {release();assert.equal(await ack,true);}
  assert.equal(f.ledger().donations.anonymous.phase,'ACKNOWLEDGED');
});

test('complete extras sequence closes only after two exact donation joins and the new missed reconcile join',async()=>{
  const f=fixture(),e=await createSandboxAcceptanceExtras(f.options,f.dependencies);await e.open(limits());
  await anonymous(f,e);f.succeed(uuid(30));await e.confirmDonation('anonymous',limits());await identify(f,e);
  await e.beforeDonationCreate('emailOtp',{siteKey:f.provisioned.site.key,campaignKey:f.provisioned.campaign.key,amountMinor:500,hasCustomerAuthority:true},limits());
  f.details.set(uuid(40),f.payment(uuid(40),'DONATION',uuid(51)));await e.ackDonationCreate('emailOtp',{checkoutId:uuid(42),paymentId:uuid(40),donationId:uuid(41),campaignKey:f.provisioned.campaign.key,campaignVersion:1,amountMinor:500,currency:'EUR'},limits());
  f.prepared(uuid(40));await e.beforeDonationPay('emailOtp',limits());f.succeed(uuid(40));await e.confirmDonation('emailOtp',limits());
  const fourth=await fourthPay(f,e);await fourth.confirm('success');await fourth.close();assert.equal(await e.close(limits()),true);
  assert.equal(e.resources().status,'CLOSED');assert.equal(e.resources().donations.anonymous.confirmed,true);
  assert.equal(e.resources().donations.emailOtp.confirmed,true);assert.equal(e.resources().missed.confirmed,true);
  assert.equal(f.files.has(DIR+'/extras.lock'),false);assert.equal(f.ledger().missed.eventId,uuid(90));
});
