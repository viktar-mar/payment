import test,{after,mock} from 'node:test';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import net from 'node:net';
import filesystem from 'node:fs/promises';
import {syncBuiltinESMExports} from 'node:module';
import {constants} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createOwnedSandboxProvisioner} from './stripe-sandbox-provision.mjs';
const diagnosticModule=await import('./stripe-form-diagnostic-provision.mjs').catch(()=>({}));
const forbidden=()=>{throw new Error('synthetic_test_forbids_external_boundary');};
mock.method(childProcess,'execFile',forbidden);mock.method(childProcess,'spawn',forbidden);
mock.method(net,'createServer',forbidden);mock.method(globalThis,'fetch',forbidden);
const actualOpen=filesystem.open,actualReadFile=filesystem.readFile;
mock.method(filesystem,'open',(path,...args)=>String(path).endsWith('/infrastructure/stripe-sandbox.env.local')?forbidden():actualOpen(path,...args));
mock.method(filesystem,'readFile',(path,...args)=>String(path).endsWith('/infrastructure/stripe-sandbox.env.local')?forbidden():actualReadFile(path,...args));
syncBuiltinESMExports();after(()=>{mock.restoreAll();syncBuiltinESMExports();});

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const RUN='11111111-2222-4333-8444-555555555555',SUFFIX='11111111222243338444555555555555';
const DIR=ROOT+'/test-results/stripe-sandbox/'+RUN;
const LIMIT=()=>({deadline:Date.now()+120000});
const uuid=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const ACCOUNT=uuid(1),SITE=uuid(2),PRODUCT=uuid(3),PRICE=uuid(4),PROVIDER=uuid(5),CAMPAIGN=uuid(6);
const SECRET=Buffer.alloc(32,17).toString('base64url');
const TOKEN=n=>Buffer.alloc(32,n).toString('base64url');
const rejects=(promise,code='PROVISION_PENDING')=>assert.rejects(promise,{message:'stripe_sandbox_provision_'+code});
const turn=()=>new Promise(resolveTurn=>setImmediate(resolveTurn));
function fixture({browserOrigin}={}) {
  const events=[],files=new Map(),checkoutStatuses=[];let serial=0,held=false,checkout=0,accountCreated=false,siteCreated=false,pricePublished=false,providerCreated=false,campaignActive=false;
  const add=(path,directory,contents='')=>files.set(path,{directory,contents:Buffer.from(contents),uid:process.getuid(),mode:directory?0o40700:0o100600,nlink:1,dev:1,ino:++serial});
  for(let path=DIR;path!=='/';path=dirname(path))add(path,true);add('/',true);
  const info=entry=>({...entry,size:entry.contents.length,isDirectory:()=>entry.directory,isFile:()=>!entry.directory,isSymbolicLink:()=>Boolean(entry.symlink)});
  const need=path=>{const entry=files.get(path);if(!entry)throw Object.assign(new Error(),{code:'ENOENT'});return entry;};
  const resource={runId:RUN,expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',project:'stripe-'+RUN,siteKey:'s5a-'+SUFFIX,
    ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
    endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:18081',receiver:'http://127.0.0.1:18082',postgres:'postgresql://127.0.0.1:15432',mailpitHttp:'http://127.0.0.1:18025',mailpitSmtp:'smtp://127.0.0.1:11025'},
    status:'LIVE',webhookReady:true,containers:Object.fromEntries(['postgres','mailpit','backend','demo-site','callback-receiver'].map((name,i)=>[name,{id:String(i+1).repeat(64)}]))};
  const account=()=>({id:ACCOUNT,key:'stripe-sandbox-default',provider:'STRIPE',mode:'SANDBOX',status:'ACTIVE',external_account_id:'acct_1UPiMpELswAd8tpR'});
  const site=()=>({id:SITE,key:'s5a-'+SUFFIX,display_name:'Stage5A Sandbox acceptance',status:'ACTIVE',payment_account_id:ACCOUNT,allowed_origins:[browserOrigin??resource.endpoints.demo],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'ALLOWLIST',methods:['CARD']}});
  const product=()=>({id:PRODUCT,site_id:SITE,key:'s5a-product-'+SUFFIX,name:'Stage5A one-time acceptance',description:null,status:'ACTIVE'});
  const price=()=>({id:PRICE,site_id:SITE,product_id:PRODUCT,price_key:'s5a-price-'+SUFFIX,version:1,type:'ONE_TIME',amount_minor:2000,currency:'EUR',billing_interval:null,trial_days:0,trial_payment_method_policy:'OPTIONAL_DURING_TRIAL',tax_mode:'NONE',tax_code:null,status:pricePublished?'PUBLISHED':'DRAFT',provider_price_id:pricePublished?'price_synthetic_original':null});
  const provider=()=>({id:PROVIDER,provider_key:'s5a-email-'+SUFFIX,mode:'EMAIL_OTP',status:'ACTIVE',version:0,issuer:null,audiences:[],allowed_algorithms:[],jwks_source_type:null,readiness:'READY',keys:[],sender_ref:'local-default',template_key:'otp-default',last_successful_refresh_at:null,jwks_refresh_state:'NOT_APPLICABLE'});
  const campaign=()=>({id:CAMPAIGN,site_id:SITE,key:'s5a-donate-'+SUFFIX,display_name:'Stage5A Donation acceptance',purpose_description:null,currency:'EUR',suggested_amounts:[500],minimum_amount:500,maximum_amount:500,custom_amount_allowed:false,anonymous_allowed:true,status:campaignActive?'ACTIVE':'DRAFT',activated_once:campaignActive,version:campaignActive?1:0,created_at:'2026-10-16T00:00:00Z',updated_at:'2026-10-16T00:00:00Z'});
  function response(status,value=null,cookies=[]) {
    const bytes=Buffer.from(value===null?'':JSON.stringify(value));
    return {status,headers:{getSetCookie:()=>cookies},body:new ReadableStream({start(controller){if(bytes.length)controller.enqueue(bytes);controller.close();}})};
  }
  const bootstrap={resources:()=>resource,verify:async context=>{assert.equal(held,false,'R3 callback lock released before verify');events.push({type:'verify',context});return !siteCreated||providerCreated;},
    withAdminCredentials:async callback=>{held=true;events.push({type:'credentials'});try {assert.equal(callback(Object.freeze({username:'generated-admin',password:'synthetic-password'})),true);return true;}finally{held=false;}}};
  const options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',STRIPE_API_KEY:'must-not-leak'},bootstrap,
    verifyProviderBinding:async context=>{events.push({type:'binding',context});return true;}};
  const dependencies={execFile:forbidden,fetch:async(url,settings)=>{
    assert.equal(held,false);const path=new URL(url).pathname,method=settings.method,body=settings.body?JSON.parse(settings.body):null;
    events.push({type:'http',url,path,method,body,settings});
    if(path==='/api/admin/v1/auth/sessions')return response(204,null,['PAYMENT_ADMIN_SESSION=session-test; Path=/; HttpOnly; SameSite=Strict','XSRF-TOKEN=csrf-test; Path=/']);
    if(path==='/api/admin/v1/payment-accounts'){if(method==='GET')return response(200,accountCreated?[account()]:[]);accountCreated=true;return response(201,account());}
    if(path==='/api/admin/v1/sites'){
      if(method==='GET')return response(200,siteCreated?[site()]:[]);
      const inline=body.email_otp_provider!=null;
      if(inline&&JSON.stringify(body.email_otp_provider)!==JSON.stringify({provider_key:'s5a-email-'+SUFFIX,sender_ref:'local-default',template_key:'otp-default'}))return response(400);
      if(inline&&(!Array.isArray(body.authentication_modes)||!body.authentication_modes.includes('EMAIL_OTP')))return response(400);
      siteCreated=true;providerCreated=inline;return response(201,site());
    }
    if(path.endsWith('/checkout-return-urls'))return method==='PUT'?response(204):response(200,events.some(e=>e.type==='http'&&e.path===path&&e.method==='PUT')?[{origin:browserOrigin??resource.endpoints.demo,return_url:(browserOrigin??resource.endpoints.demo)+'/payment-return.html',version:0}]:[]);
    if(path.endsWith('/products'))return response(201,product());
    if(path.endsWith('/prices'))return response(201,price());
    if(path.endsWith('/publish')){pricePublished=true;return response(200,price());}
    if(path.endsWith('/catalog'))return response(200,{products:[product()],prices:[price()]});
    if(path==='/inspection')return response(200,{count:0,duplicates:0,events:[]});
    if(path.endsWith('/callback'))return response(200,{site_id:SITE,endpoint_url:'http://callback-receiver:8080/callback',enabled:true,secret_version:1,secret:SECRET});
    if(path==='/configure')return response(204);
    if(path.endsWith('/identity-providers')){assert.equal(method,'GET','provider is created only in the atomic Site request');return response(200,providerCreated?[provider()]:[]);}
    if(path.endsWith('/identity-providers/'+PROVIDER)){assert.equal(providerCreated,true);return response(200,provider());}
    if(path.endsWith('/donation-campaigns'))return response(201,campaign());
    if(path.endsWith('/status')){campaignActive=true;return response(200,campaign());}
    if(path.endsWith('/donation-campaigns/'+CAMPAIGN))return response(200,campaign());
    if(path.endsWith('/checkouts')){checkout++;const value={checkout_id:uuid(10+checkout),checkout_token:TOKEN(checkout),payment_id:uuid(20+checkout),amount_minor:2000,currency:'EUR',price:{price_id:PRICE,price_key:'s5a-price-'+SUFFIX,version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE'},provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'REQUIRES_PAYMENT_METHOD',expires_at:new Date(Date.now()+3600000).toISOString()};checkoutStatuses.push(value.status);return response(201,value);}
    throw new Error('unexpected synthetic route');
  },lstat:async path=>info(need(path)),realpath:async path=>{need(path);return path;},
    open:async(path,flags,mode)=>{events.push({type:'open',path,flags,mode});
      if(flags&constants.O_CREAT){if(files.has(path)&&(flags&constants.O_EXCL))throw null;if(!files.has(path)){need(dirname(path));add(path,false);}}
      const entry=need(path);if(entry.symlink&&(flags&constants.O_NOFOLLOW))throw null;
      return {stat:async()=>info(entry),writeFile:async text=>{events.push({type:'write',path});entry.contents=Buffer.from(text);},sync:async()=>events.push({type:'sync',path}),close:async()=>events.push({type:'close',path})};
    },rename:async(from,to)=>{events.push({type:'rename',from,to});files.set(to,need(from));files.delete(from);},unlink:async path=>{events.push({type:'unlink',path});need(path);files.delete(path);},
    initializeState:async(metadata,settings)=>{events.push({type:'initialize',metadata,settings});},now:Date.now,setTimeout,clearTimeout};
  return {options,dependencies,events,files,resource,response,account,site,product,price,provider,campaign,checkoutStatuses};
}

function diagnosticOperation(){return {operation_id:uuid(40),payment_id:uuid(21),resource_id:uuid(21),account_id:ACCOUNT,account_mode:'SANDBOX',site_id:SITE,kind:'PAYMENT_PREPARE',status:'ACKNOWLEDGED',manual_review_required:false,reference:'pi_TestOnly'};}
function diagnosticDetail(attempts=0){return {payment:{payment_id:uuid(21),site_id:SITE,payment_account_id:ACCOUNT,provider:'STRIPE',source:'CATALOG',price_id:PRICE,price_key:'s5a-price-'+SUFFIX,price_version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE',refunded_amount_minor:0,refundable_amount_minor:0,status:'REQUIRES_PAYMENT_METHOD',customer_id:null,donation_id:null,subscription_id:null},
  attempts:Array.from({length:attempts},(_,i)=>({id:uuid(50+i),attempt_number:i+1,status:'REQUIRES_PAYMENT_METHOD',provider_intent_reference:'pi_TestOnly',payment_method_family:'CARD',result_code:null})),
  events:[{id:uuid(60),event_type:'PAYMENT_CREATED'},{id:uuid(61),event_type:'PAYMENT_METHOD_REQUIRED'},...Array.from({length:attempts},(_,i)=>({id:uuid(62+i),event_type:'ATTEMPT_CREATED'}))],refunds:[],outbox_events:[],deliveries:[]};}


const paymentEnv={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true'};
function paymentFixture(){
 const f=fixture({browserOrigin:'http://127.0.0.1:18083'});f.options.env={...paymentEnv};f.options.browserOrigin='http://127.0.0.1:18083';f.resource.mode='MANUAL_PAYMENT';
 f.actions=[];const rename=f.dependencies.rename,open=f.dependencies.open;
 f.delays=[];f.probes=0;f.dependencies.setTimeout=(callback,ms)=>{f.delays.push(ms);if(f.fastPoll&&ms===1500){queueMicrotask(callback);return 0;}return setTimeout(callback,ms);};
 f.dependencies.rename=async(from,to)=>{if(f.failRename)throw null;return rename(from,to);};
 f.dependencies.open=async(path,...args)=>{const handle=await open(path,...args),sync=handle.sync;handle.sync=async()=>{if(f.failSync)throw null;await sync();if(path===DIR&&f.files.get(DIR+'/form-provisioning.json')?.contents.includes('"status":"CONFIRMING"'))f.actions.push('DURABLE_CONFIRMING');};return handle;};
 f.attempts=0;f.success=false;f.mutate=()=>{};const fetcher=f.dependencies.fetch;
 f.dependencies.fetch=async(url,settings)=>{const path=new URL(url).pathname,d=diagnosticDetail(f.attempts);
  if(f.success){d.payment.status='SUCCEEDED';d.payment.refundable_amount_minor=2000;d.attempts[0].status='SUCCEEDED';d.attempts[0].result_code='succeeded';
   d.events.push({id:uuid(70),event_type:'PAYMENT_SUCCEEDED',source:'PROVIDER_EVENT',provider_event_id:'evt_original',provider_object_id:'pi_TestOnly',amount_minor:2000,currency:'EUR'});
   d.outbox_events=[{id:uuid(70),callback_type:'payment.succeeded',status:'DELIVERED'}];d.deliveries=[{outbox_event_id:uuid(70),status:'SUCCEEDED',http_status:204},{outbox_event_id:uuid(70),status:'SUCCEEDED',http_status:204}];}
  f.mutate(d);
  if(path==='/api/v1/admin/payments')return f.response(200,{items:[d.payment],total:f.total??1,page:0,size:2,sort:'CREATED_DESC'});
  if(path.startsWith('/api/v1/admin/payments/')){f.probes++;return f.response(200,d);}
  if(path==='/api/v1/admin/provider-operations')return f.response(200,[{...diagnosticOperation(),...f.operation}]);
  if(path==='/inspection'&&f.success)return f.response(200,f.inspection??{count:1,duplicates:1,events:[{event_id:uuid(70),event_type:'payment.succeeded',count:2}]});
  return fetcher(url,settings);};
 return f;
}
async function prepared({threeDSecure=false}={}){assert.equal(typeof diagnosticModule.createManualPaymentProvisioner,'function');const f=paymentFixture();if(threeDSecure)f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';f.p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies);f.limits=LIMIT();f.handoff=await f.p.prepare(f.limits);return f;}
function fixtureBoundaryFailure(f,boundary,error){
 const state={injected:0},fetcher=f.dependencies.fetch,open=f.dependencies.open;
 f.dependencies.fetch=async(url,settings)=>{
  const result=await fetcher(url,settings),path=new URL(url).pathname;
  if(path.endsWith('/checkout-return-urls')&&settings.method==='GET'&&f.events.some(e=>e.path===path&&e.method==='PUT')){
   if(boundary==='RETURN_POLICY_READ'){state.injected++;throw error;}
   if(boundary==='RETURN_POLICY_PROOF'){state.injected++;return f.response(200,[{origin:'synthetic-private-policy-canary',return_url:'synthetic-private-policy-canary',version:0}]);}
  }
  if(path.endsWith('/products')){
   if(boundary==='product-http'){state.injected++;throw error;}
   if(boundary==='product-proof'){state.injected++;return f.response(201,{...f.product(),status:'synthetic-private-product-canary'});}
  }
  return result;
 };
 f.dependencies.open=async(path,...args)=>{
  const handle=await open(path,...args),write=handle.writeFile;
  handle.writeFile=async text=>{
   const intent=JSON.parse(text).intents?.at(-1);
   if(intent?.name==='product'&&((boundary==='PRODUCT_DISPATCH'&&intent.status==='DISPATCHING')||(boundary==='product-ack'&&intent.status==='ACKNOWLEDGED'))){state.injected++;throw error;}
   return write(text);
  };
  return handle;
 };
 return state;
}
// Break: the return-policy read/proof and durable product dispatch lose their exact bounded stage.
for(const threeDSecure of [false,true])for(const stage of ['RETURN_POLICY_READ','RETURN_POLICY_PROOF','PRODUCT_DISPATCH'])for(const hostile of stage==='RETURN_POLICY_PROOF'?[false]:[false,true])test(`fixture boundary ${stage} ${threeDSecure?'3DS':'ordinary'} ${hostile?'hostile':'canary'} failure is safe and cannot resume`,async()=>{
 const f=paymentFixture();if(threeDSecure)f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';let reads=0;
 const trap=()=>{reads++;throw Error('synthetic-private-error-canary');};
 const error=hostile?new Proxy({},{get:trap,ownKeys:trap,getPrototypeOf:trap,getOwnPropertyDescriptor:trap}):Error('synthetic-private-error-canary');
 const state=fixtureBoundaryFailure(f,stage,error),p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies),limits=LIMIT();let failure;
 try{await p.prepare(limits);}catch(caught){failure=caught;}
 assert.ok(failure);assert.equal(failure.message,'stripe_form_diagnostic_provision_PROVISION_PENDING');assert.equal(state.injected,1);
 assert.equal(reads,0);assert.equal(f.events.filter(e=>e.path?.endsWith('/products')||e.path?.endsWith('/checkouts')).length,0);
 const before=[...f.events];await assert.rejects(p.prepare(limits));assert.deepEqual(f.events,before);assert.equal(state.injected,1);
 const wire=diagnosticModule.formatManualPaymentProvisionDiagnostic(failure);
 assert.equal(wire,'{"tag":"stripe_manual_payment_provision_diagnostic","stage":"'+stage+'"}\n');assert.ok(Buffer.byteLength(wire)<=256);assert.equal(reads,0);
});
// Break: PRODUCT_DISPATCH escapes its durable-save boundary and mislabels later product failures.
for(const threeDSecure of [false,true])for(const boundary of ['product-http','product-proof','product-ack'])test(`fixture boundary completed dispatch resets for ${boundary} ${threeDSecure?'3DS':'ordinary'}`,async()=>{
 const f=paymentFixture();if(threeDSecure)f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';
 const state=fixtureBoundaryFailure(f,boundary,Error('synthetic-private-error-canary')),p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies),limits=LIMIT();let failure;
 try{await p.prepare(limits);}catch(error){failure=error;}
 assert.ok(failure);assert.equal(state.injected,1);assert.equal(f.events.filter(e=>e.path?.endsWith('/products')).length,1);assert.equal(f.events.filter(e=>e.path?.endsWith('/checkouts')).length,0);
 const ledger=JSON.parse(f.files.get(DIR+'/form-provisioning.json').contents);assert.equal(ledger.intents.at(-1).name,'product');assert.equal(ledger.intents.at(-1).status,'DISPATCHING');
 const before=[...f.events];await assert.rejects(p.prepare(limits));assert.deepEqual(f.events,before);
 assert.equal(diagnosticModule.formatManualPaymentProvisionDiagnostic(failure),'{"tag":"stripe_manual_payment_provision_diagnostic","stage":"FIXTURE_SETUP"}\n');
});
for(const stage of ['RETURN_POLICY_READ','RETURN_POLICY_PROOF','PRODUCT_DISPATCH'])test('fixture boundary nonmanual '+stage+' failure stays unbranded',async()=>{
 const f=fixture();f.resource.mode='FORM_DIAGNOSTIC';f.resource.webhookReady=false;f.options.env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
 const state=fixtureBoundaryFailure(f,stage,Error('synthetic-private-error-canary')),p=await diagnosticModule.createFormDiagnosticProvisioner(f.options,f.dependencies);let failure;
 try{await p.prepare(LIMIT());}catch(error){failure=error;}
 assert.ok(failure);assert.equal(state.injected,1);assert.equal(f.events.filter(e=>e.path?.endsWith('/products')||e.path?.endsWith('/checkouts')).length,0);
 assert.equal(diagnosticModule.formatManualPaymentProvisionDiagnostic(failure),'{"tag":"stripe_manual_payment_provision_diagnostic","stage":"UNKNOWN"}\n');
});
// Break: actual provisioner brands fail to reach the sole safe line after independent cleanup and before the native final report.
for(const threeDSecure of [false,true])for(const stage of ['RETURN_POLICY_READ','RETURN_POLICY_PROOF','PRODUCT_DISPATCH'])test(`fixture boundary real ${stage} reaches ${threeDSecure?'3DS':'ordinary'} controller cleanup and final report`,async()=>{
 const {runManualPaymentCli}=await import('./stripe-manual-payment.mjs'),f=paymentFixture();if(threeDSecure)f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';
 let reads=0;const trap=()=>{reads++;throw Error('synthetic-private-error-canary');};
 const state=fixtureBoundaryFailure(f,stage,new Proxy({},{get:trap,ownKeys:trap,getPrototypeOf:trap,getOwnPropertyDescriptor:trap}));
 const p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies),lines=[],reports=[],order=[],close=p.close;
 const env={...f.options.env,PAYMENT_STRIPE_SANDBOX_RUN_ID:RUN,PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:f.resource.expectedSource,PAYMENT_STRIPE_SANDBOX_DAEMON:f.resource.daemon,PAYMENT_STRIPE_SANDBOX_PORT_MANUAL:'18083',...Object.fromEntries(Object.entries(f.resource.ports).map(([key,value])=>['PAYMENT_STRIPE_SANDBOX_PORT_'+key.toUpperCase(),String(value)]))};
 const unreachable=()=>assert.fail('no gateway, grant, verification or provider capability after failed preparation');
 const value=await runManualPaymentCli({env,args:[]},{subscribe:()=>()=>{},preflight:async()=>({status:'READY'}),
  createBootstrap:async()=>({prepare:async()=>true,resources:()=>({...f.resource,status:'PREPARED',cliDirectory:DIR+'/cli'}),start:async()=>true,verify:async()=>true,stop:async()=>{order.push('runtime');return true;}}),
  createCli:async()=>({validateBinding:async()=>true,startListener:async()=>true,stopListener:async()=>{order.push('listener');if(threeDSecure)throw null;return true;},inspectManualPayment:unreachable}),authorizeCli:async()=>true,
  createProvisioner:async()=>({...p,markConfirmation:unreachable,verifyPayment:unreachable,verifyUnused:unreachable,close:async limits=>{order.push('provisioner');return close(limits);}}),createGateway:unreachable,
  writeProvisionDiagnostic:async line=>{lines.push(line);order.push('diagnostic');},write:async line=>{reports.push(line);order.push('report');}});
 assert.equal(state.injected,1);assert.equal(reads,0);assert.equal(f.events.filter(e=>e.path?.endsWith('/products')||e.path?.endsWith('/checkouts')).length,0);
 assert.deepEqual(order,['provisioner','listener','runtime','diagnostic','report']);
 assert.deepEqual(value.report,{version:1,mode:threeDSecure?'MANUAL_3DS':'MANUAL_PAYMENT',result:'PENDING',phase:'PROVISION',confirmation_granted:false,payment_confirmed:false,provider_confirmed:false,webhook_verified:false,journal_verified:false,callback_verified:false,...(threeDSecure?{three_ds_verified:false}:{}),gateway_closed:true,listener_closed:!threeDSecure,runtime_closed:true,browser_closure:'NOT_OWNED',provenance:'CONTROLLER_REPORTED'});
 assert.equal(value.exitCode,1);assert.equal(reports.length,1);assert.deepEqual(JSON.parse(reports[0]),value.report);
 assert.deepEqual(lines,['{"tag":"stripe_manual_payment_provision_diagnostic","stage":"'+stage+'"}\n']);assert.ok(Buffer.byteLength(lines[0])<=256);
});
// Break: leaking dependency errors or losing the actual initial failure boundary.
for(const stage of ['ADMISSION','OWNERSHIP','PROVIDER_BINDING','ADMIN_CREDENTIALS'])test('manual provision diagnostic brands actual '+stage+' failure',async()=>{
 const f=paymentFixture(),canary=Error('password=synthetic-private sk_test_canary');
 if(stage==='ADMISSION')f.options.browserOrigin='https://secret.invalid';
 if(stage==='OWNERSHIP')f.options.bootstrap.verify=async()=>{throw canary;};
 if(stage==='PROVIDER_BINDING')f.options.verifyProviderBinding=async()=>{throw canary;};
 if(stage==='ADMIN_CREDENTIALS')f.options.bootstrap.withAdminCredentials=async()=>{throw canary;};
 let failure;try{const p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies);await p.prepare(LIMIT());}catch(error){failure=error;}
 assert.ok(failure);assert.equal(typeof diagnosticModule.formatManualPaymentProvisionDiagnostic,'function');
 const wire=diagnosticModule.formatManualPaymentProvisionDiagnostic(failure);
 assert.equal(wire,'{"tag":"stripe_manual_payment_provision_diagnostic","stage":"'+stage+'"}\n');assert.ok(Buffer.byteLength(wire)<=256);
 assert.equal(f.events.filter(e=>e.type==='http').length,0);
});
// Break: resetting post-credential metadata to UNKNOWN hides a known preparation boundary.
for(const threeDSecure of [false,true])for(const [stage,mutationCount,checkoutCount] of [
 ['PRIVATE_STORAGE',0,0],['ADMIN_SESSION',1,0],['FIXTURE_SETUP',1,0],['CHECKOUT',10,1],['FINAL_BASELINE',10,1],
])test(`manual ${threeDSecure?'selected 3DS':'ordinary'} provision diagnostic brands actual ${stage} failure without resuming`,async()=>{
 const f=paymentFixture(),canary=Error('password=synthetic-private sk_test_canary');
 if(threeDSecure)f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';
 let injected=0,accountReads=0;
 if(stage==='PRIVATE_STORAGE')f.dependencies.lstat=async()=>{injected++;throw canary;};
 else {
  const fetcher=f.dependencies.fetch;
  f.dependencies.fetch=async(url,settings)=>{
   const result=await fetcher(url,settings),path=new URL(url).pathname;
   if((stage==='ADMIN_SESSION'&&path==='/api/admin/v1/auth/sessions')
     ||(stage==='FIXTURE_SETUP'&&path==='/api/admin/v1/payment-accounts'&&settings.method==='GET')
     ||(stage==='CHECKOUT'&&path.endsWith('/checkouts'))
     ||(stage==='FINAL_BASELINE'&&path==='/api/admin/v1/payment-accounts'&&settings.method==='GET'&&++accountReads===2)){
    injected++;throw canary;
   }
   return result;
  };
 }
 const p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies),limits=LIMIT();
 let failure;try{await p.prepare(limits);}catch(error){failure=error;}
 assert.ok(failure);assert.equal(failure.message,'stripe_form_diagnostic_provision_PROVISION_PENDING');assert.equal(injected,1);
 const wire=diagnosticModule.formatManualPaymentProvisionDiagnostic(failure);
 assert.equal(wire,'{"tag":"stripe_manual_payment_provision_diagnostic","stage":"'+stage+'"}\n');assert.ok(Buffer.byteLength(wire)<=256);
 assert.equal(f.events.filter(e=>e.type==='credentials').length,1);
 assert.equal(f.events.filter(e=>e.type==='http'&&e.method!=='GET').length,mutationCount);
 assert.equal(f.events.filter(e=>e.path?.endsWith('/checkouts')).length,checkoutCount);
 const stoppedEvents=[...f.events];await assert.rejects(p.prepare(limits),{message:'stripe_form_diagnostic_provision_PROVISION_PENDING'});
 assert.deepEqual(f.events,stoppedEvents);assert.equal(injected,1);
});
for(const threeDSecure of [false,true])test(`manual ${threeDSecure?'selected 3DS':'ordinary'} post-credential hostile throw uses only module-owned metadata`,async()=>{
 const f=paymentFixture();if(threeDSecure)f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';let reads=0;
 const trap=()=>{reads++;throw Error('password=synthetic-private sk_test_canary');};
 const hostile=new Proxy({},{get:trap,getPrototypeOf:trap,ownKeys:trap,getOwnPropertyDescriptor:trap});
 f.dependencies.lstat=async()=>{throw hostile;};
 const p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies);let failure;
 try{await p.prepare(LIMIT());}catch(error){failure=error;}
 assert.ok(failure);assert.equal(f.events.filter(e=>e.type==='credentials').length,1);
 assert.equal(diagnosticModule.formatManualPaymentProvisionDiagnostic(failure),'{"tag":"stripe_manual_payment_provision_diagnostic","stage":"PRIVATE_STORAGE"}\n');
 assert.equal(reads,0);assert.equal(f.events.filter(e=>e.type==='http').length,0);
});
test('unbranded hostile provision errors are UNKNOWN without any reads',()=>{
 assert.equal(typeof diagnosticModule.formatManualPaymentProvisionDiagnostic,'function');let reads=0;
 const trap=()=>{reads++;throw Error('sk_test_canary');},accessor=Object.defineProperties({stage:'OWNERSHIP'},{message:{get:trap},stack:{get:trap},toJSON:{get:trap}});
 const revoked=Proxy.revocable({},{});revoked.revoke();
 for(const error of [null,undefined,false,42,1n,Symbol('sk_test_canary'),'password=synthetic-private',Error('sk_test_canary'),accessor,new Proxy({},{get:trap,getPrototypeOf:trap,ownKeys:trap,getOwnPropertyDescriptor:trap}),revoked.proxy]){
  assert.equal(diagnosticModule.formatManualPaymentProvisionDiagnostic(error),'{"tag":"stripe_manual_payment_provision_diagnostic","stage":"UNKNOWN"}\n');
 }assert.equal(reads,0);
});
test('nonmanual failures cannot impersonate a manual initial boundary',async()=>{
 let failure;try{await diagnosticModule.createFormDiagnosticProvisioner({env:{}});}catch(error){failure=error;}
 assert.equal(typeof diagnosticModule.formatManualPaymentProvisionDiagnostic,'function');assert.equal(JSON.parse(diagnosticModule.formatManualPaymentProvisionDiagnostic(failure)).stage,'UNKNOWN');
});
for(const threeDSecure of [false,true])test(`manual ${threeDSecure?'selected 3DS':'ordinary'} credentials receive the existing ten-second child`,async()=>{
 const f=paymentFixture();if(threeDSecure)f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';let child;
 const credentials=f.options.bootstrap.withAdminCredentials;f.options.bootstrap.withAdminCredentials=(callback,limits)=>{child=limits;return credentials(callback);};
 f.dependencies.now=()=>1000;const p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies);
 await p.prepare({deadline:121000});assert.equal(child.deadline,11000);assert.ok(child.signal instanceof AbortSignal);assert.equal(child.signal.aborted,false);
});
const writes=f=>f.events.filter(e=>e.type==='http'&&e.method!=='GET').length;
// Break: losing the actual verification boundary, guessing timeout, or extending the consumed proof.
for(const threeDSecure of [false,true])for(const boundary of ['admission','status','proof','status-limit','proof-limit'])test(`verify diagnostic brands actual ${boundary} ${threeDSecure?'3DS':'ordinary'} failure without another proof`,async()=>{
 const f=await prepared({threeDSecure});
 if(boundary!=='admission'){f.attempts=1;await f.p.markConfirmation(f.limits);}
 f.fastPoll=true;const before=f.probes,financial=writes(f);let reads=0;
 if(boundary==='status')f.mutate=()=>{throw new Proxy({},{get(){reads++;throw Error('private');}});};
 if(boundary==='proof'||boundary==='proof-limit'){f.success=true;f.mutate=d=>{if(boundary==='proof')d.events.at(-1).provider_object_id='pi_wrong';else d.deliveries=[];};}
 let error;try{await f.p.verifyPayment(f.limits);}catch(failure){error=failure;}
 assert.ok(error);assert.equal(typeof diagnosticModule.formatManualPaymentVerifyDiagnostic,'function');
 Object.defineProperties(error,{stage:{get(){reads++;throw null;}},reason:{get(){reads++;throw null;}},message:{get(){reads++;throw null;}},stack:{get(){reads++;throw null;}}});
 const stage=boundary==='admission'?'ADMISSION':boundary.startsWith('proof')?'SERVER_PROOF':'STATUS_POLL',reason=boundary.endsWith('limit')?'PROBE_LIMIT':'UNCONFIRMED';
 const line=diagnosticModule.formatManualPaymentVerifyDiagnostic(error);
 assert.equal(line,'{"tag":"stripe_manual_payment_verify_diagnostic","stage":"'+stage+'","reason":"'+reason+'"}\n');assert.ok(Buffer.byteLength(line)<=256);
 assert.equal(f.probes-before,boundary==='admission'||boundary==='status'?0:boundary==='proof'?2:boundary==='proof-limit'?60:30);assert.equal(writes(f),financial);
 await assert.rejects(f.p.verifyPayment(f.limits));assert.equal(writes(f),financial);assert.equal(diagnosticModule.formatManualPaymentVerifyDiagnostic(error),line);
 assert.equal(reads,0);
});
test('verify diagnostic formats arbitrary hostile unbranded errors without any property read',()=>{
 assert.equal(typeof diagnosticModule.formatManualPaymentVerifyDiagnostic,'function');let reads=0;const trap=()=>{reads++;throw null;};
 const revoked=Proxy.revocable({},{});revoked.revoke();const accessor=Object.defineProperties({stage:'SERVER_PROOF',reason:'PROBE_LIMIT'},{message:{get:trap},stack:{get:trap},toJSON:{get:trap}});
 for(const error of [null,undefined,1,1n,false,Symbol('private'),'password=private',Error('private'),accessor,new Proxy({},{get:trap,ownKeys:trap,getPrototypeOf:trap,getOwnPropertyDescriptor:trap}),revoked.proxy])assert.equal(diagnosticModule.formatManualPaymentVerifyDiagnostic(error),'{"tag":"stripe_manual_payment_verify_diagnostic","stage":"UNKNOWN","reason":"UNCONFIRMED"}\n');
 assert.equal(reads,0);
});
for(const threeDSecure of [false,true])test(`real provisioner verify brand reaches ${threeDSecure?'3DS':'ordinary'} controller after independent cleanup`,async()=>{
 const {runManualPayment}=await import('./stripe-manual-payment.mjs');const f=paymentFixture();if(threeDSecure)f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';
 const p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies),lines=[],order=[];let authorize,permits=0;const close=p.close;
 const selected={env:f.options.env,runId:RUN,expectedSource:f.resource.expectedSource,daemon:f.resource.daemon,ports:f.resource.ports,manualPort:18083};
 const value=await runManualPayment(selected,{deadline:Date.now()+2700000},{preflight:async()=>({status:'READY'}),
  createBootstrap:async()=>({prepare:async()=>true,resources:()=>({...f.resource,status:'PREPARED',cliDirectory:DIR+'/cli'}),start:async()=>true,verify:async()=>true,stop:async()=>{order.push('runtime');return true;}}),
  createCli:async()=>({validateBinding:async()=>true,startListener:async()=>true,bindManualPayment:async()=>true,verifyManualPaymentBaseline:async()=>true,assertManualPaymentListener:()=>true,stopListener:async()=>{order.push('listener');return true;},inspectManualPayment:async()=>{assert.fail('provider cannot run after rejected server proof');}}),authorizeCli:async()=>true,
  createProvisioner:async()=>({...p,markConfirmation:async limits=>{permits++;return p.markConfirmation(limits);},close:async limits=>{order.push('provisioner');return close(limits);}}),
  createGateway:async options=>{authorize=options.authorizeConfirmation;return {open:async()=>({url:'http://127.0.0.1:18083/manual-payment.html'}),wait:async()=>{f.attempts=1;await authorize();f.success=true;f.mutate=d=>{d.events.at(-1).source='RECONCILIATION';};return {outcome:'CONFIRMATION_GRANTED'};},close:async()=>{order.push('gateway');return true;}};},
  write:async()=>{},writeVerifyDiagnostic:async line=>{lines.push(line);order.push('diagnostic');}});
 assert.deepEqual(lines,['{"tag":"stripe_manual_payment_verify_diagnostic","stage":"SERVER_PROOF","reason":"UNCONFIRMED"}\n']);assert.deepEqual(order,['gateway','provisioner','listener','runtime','diagnostic']);
 assert.equal(permits,1);assert.equal(f.events.filter(e=>e.path?.endsWith('/checkouts')).length,1);assert.equal(value.report.result,'PENDING');assert.equal(value.report.phase,'VERIFY');assert.equal(value.report.confirmation_granted,true);assert.equal(value.report.payment_confirmed,false);assert.equal(value.report.provider_confirmed,false);assert.equal(value.report.mode,threeDSecure?'MANUAL_3DS':'MANUAL_PAYMENT');assert.equal(value.exitCode,1);
});
test('one payment prepares one original Intent and durable permit precedes reply',async()=>{
 const f=await prepared();assert.equal(f.events.filter(e=>e.path?.endsWith('/checkouts')).length,1);
 assert.equal(f.handoff.binding.intentId,'pi_TestOnly');assert.equal(f.handoff.binding.priceId,PRICE);assert.equal(f.handoff.binding.paymentAccountId,ACCOUNT);
 assert.equal(f.events.filter(e=>e.path?.endsWith('/callback')).length,1);assert.equal(f.events.filter(e=>e.path==='/configure').length,1);
 assert.equal(f.events.find(e=>e.path?.endsWith('/checkouts')).settings.headers.Origin,'http://127.0.0.1:18083');assert.equal(f.resource.endpoints.demo,'http://127.0.0.1:18081');
 assert.ok(![...f.files.values()].some(e=>e.contents.includes(SECRET)));
 f.attempts=1;const before=writes(f);assert.equal(await f.p.markConfirmation(f.limits),true);f.actions.push('PERMIT_REPLY');assert.ok(f.actions.indexOf('DURABLE_CONFIRMING')>=0&&f.actions.indexOf('DURABLE_CONFIRMING')<f.actions.indexOf('PERMIT_REPLY'));
 const ledger=JSON.parse(f.files.get(DIR+'/form-provisioning.json').contents);assert.equal(ledger.status,'CONFIRMING');assert.equal(ledger.operationProof.reference,'pi_TestOnly');
 await assert.rejects(f.p.markConfirmation(f.limits));assert.equal(writes(f),before);
});
test('concurrent grants and unknown durable write never grant replacement',async()=>{
 const f=await prepared();f.attempts=1;const n=writes(f);const one=f.p.markConfirmation(f.limits);await assert.rejects(f.p.markConfirmation(f.limits));await assert.rejects(one);assert.equal(writes(f),n);
});
for(const [name,mutate] of [['zero attempt',f=>f.attempts=0],['second attempt',f=>f.attempts=2],['second payment',f=>f.total=2],['changed Intent',f=>f.operation={reference:'pi_other'}],['dispatching preparation',f=>f.operation={status:'DISPATCHING'}],['wrong account',f=>f.mutate=d=>d.payment.payment_account_id=uuid(99)],['wrong price',f=>f.mutate=d=>d.payment.price_id=uuid(99)],['wrong owner',f=>f.resource.containers.backend.id='f'.repeat(64)],['wrong host',f=>f.resource.endpoints.backend='http://127.0.0.1:19999'],['Customer',f=>f.mutate=d=>d.payment.customer_id=uuid(99)],['refund',f=>f.mutate=d=>d.refunds.push({})]])
test('permit refuses '+name+' without financial writes',async()=>{const f=await prepared();f.attempts=1;mutate(f);const n=writes(f);await assert.rejects(f.p.markConfirmation(f.limits));await assert.rejects(f.p.markConfirmation(f.limits));assert.equal(writes(f),n);});
for(const kind of ['Rename','Sync'])test('failed ledger '+kind+' consumes permit before reply',async()=>{const f=await prepared();f.attempts=1;f['fail'+kind]=true;const n=writes(f);await assert.rejects(f.p.markConfirmation(f.limits));await assert.rejects(f.p.markConfirmation(f.limits));assert.equal(writes(f),n);assert.equal(f.actions.includes('PERMIT_REPLY'),false);});
test('exact server journal callback join accepts transport duplicates',async()=>{const f=await prepared();f.attempts=1;await f.p.markConfirmation(f.limits);f.success=true;const n=writes(f);
 assert.deepEqual(await f.p.verifyPayment(f.limits),{paymentConfirmed:true,webhookVerified:true,journalVerified:true,callbackVerified:true,providerEventId:'evt_original'});assert.equal(writes(f),n);
 assert.equal(await f.p.close({deadline:f.limits.deadline+120000}),true);await assert.rejects(f.p.verifyPayment(f.limits));});
for(const [name,mutate] of [['reconciliation',d=>d.events.at(-1).source='RECONCILIATION'],['duplicate fact',d=>d.events.push({...d.events.at(-1),id:uuid(71)})],['wrong Intent',d=>d.events.at(-1).provider_object_id='pi_other'],['missing event',d=>delete d.events.at(-1).provider_event_id],['wrong outbox',d=>d.outbox_events[0].id=uuid(72)],['Customer',d=>d.payment.customer_id=uuid(99)],['refund',d=>d.refunds.push({})]])
test('authoritative proof refuses '+name+' with zero subsequent writes',async()=>{const f=await prepared();f.attempts=1;await f.p.markConfirmation(f.limits);f.success=true;f.mutate=mutate;const n=writes(f);await assert.rejects(f.p.verifyPayment(f.limits));assert.equal(writes(f),n);});
test('unused proof accepts only original unpaid payment without grant',async()=>{const f=await prepared();assert.equal(await f.p.verifyUnused(f.limits),true);await assert.rejects(f.p.markConfirmation(f.limits));});
test('unused proof refuses activity or a consumed permit',async()=>{const f=await prepared();f.attempts=1;await f.p.markConfirmation(f.limits);await assert.rejects(f.p.verifyUnused(f.limits));});
test('successful server proof refuses a changed original Attempt identity',async()=>{const f=await prepared();f.attempts=1;await f.p.markConfirmation(f.limits);f.success=true;f.mutate=d=>d.attempts[0].id=uuid(99);await assert.rejects(f.p.verifyPayment(f.limits));});
test('unknown Checkout reply preserves DISPATCHING and never creates another checkout',async()=>{const f=paymentFixture(),fetch=f.dependencies.fetch;f.dependencies.fetch=async(url,settings)=>{const result=await fetch(url,settings);if(new URL(url).pathname.endsWith('/checkouts'))throw null;return result;};
 const p=await diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies),limits=LIMIT();await assert.rejects(p.prepare(limits));const n=writes(f);await assert.rejects(p.prepare(limits));assert.equal(writes(f),n);assert.equal(f.events.filter(e=>e.path?.endsWith('/checkouts')).length,1);const ledger=JSON.parse(f.files.get(DIR+'/form-provisioning.json').contents);assert.equal(ledger.intents.at(-1).status,'DISPATCHING');});
test('server payment proof polls at most thirty times within a forty-five second child budget',async()=>{const f=await prepared();f.attempts=1;await f.p.markConfirmation(f.limits);f.fastPoll=true;const n=f.probes,financial=writes(f);await assert.rejects(f.p.verifyPayment(f.limits));assert.equal(f.probes-n,30);assert.ok(f.delays.includes(45000));assert.equal(writes(f),financial);await assert.rejects(f.p.verifyPayment(f.limits));});
test('a legitimate processing callback cannot hide the unique joined success callback',async()=>{const f=await prepared();f.attempts=1;await f.p.markConfirmation(f.limits);f.success=true;
 f.mutate=d=>{d.events.splice(-1,0,{id:uuid(69),event_type:'PAYMENT_PROCESSING'});d.outbox_events.unshift({id:uuid(69),callback_type:'payment.processing',status:'DELIVERED'});d.deliveries.unshift({outbox_event_id:uuid(69),status:'SUCCEEDED',http_status:204});};
 f.inspection={count:2,duplicates:1,events:[{event_id:uuid(69),event_type:'payment.processing',count:1},{event_id:uuid(70),event_type:'payment.succeeded',count:2}]};assert.equal((await f.p.verifyPayment(f.limits)).providerEventId,'evt_original');});

// A signed action-required receipt is an intermediate fact, never the final proof.
function actionHistory(d){
 d.events.splice(-1,0,{id:uuid(68),event_type:'CUSTOMER_ACTION_REQUIRED',source:'PROVIDER_EVENT',provider_event_id:'evt_action',provider_object_id:'pi_TestOnly',amount_minor:2000,currency:'EUR'});
 d.outbox_events.unshift({id:uuid(68),callback_type:'payment.requires_action',status:'DELIVERED'});
 d.deliveries.unshift({outbox_event_id:uuid(68),status:'SUCCEEDED',http_status:204});
}
function actionInspection(){return {count:2,duplicates:1,events:[{event_id:uuid(68),event_type:'payment.requires_action',count:1},{event_id:uuid(70),event_type:'payment.succeeded',count:2}]};}
test('selected 3DS accepts signed action history and joined callbacks before its unique success',async()=>{
 const f=await prepared({threeDSecure:true});f.attempts=1;await f.p.markConfirmation(f.limits);f.success=true;f.mutate=actionHistory;f.inspection=actionInspection();
 // Selection is the validated construction-time environment, never a later choice.
 f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='false';const n=writes(f);
 assert.deepEqual(await f.p.verifyPayment(f.limits),{paymentConfirmed:true,webhookVerified:true,journalVerified:true,callbackVerified:true,providerEventId:'evt_original'});assert.equal(writes(f),n);
});
for(const [name,mutate,inspect] of [
 ['action reconciliation',d=>d.events.at(-2).source='RECONCILIATION'],
 ['action missing source',d=>delete d.events.at(-2).source],
 ['action wrong Intent',d=>d.events.at(-2).provider_object_id='pi_other'],
 ['action wrong amount',d=>d.events.at(-2).amount_minor=1999],
 ['action wrong currency',d=>d.events.at(-2).currency='USD'],
 ['action missing provider event',d=>delete d.events.at(-2).provider_event_id],
 ['action malformed provider event',d=>d.events.at(-2).provider_event_id='invalid'],
 ['action reuses final provider event',d=>d.events.at(-2).provider_event_id='evt_original'],
 ['action malformed journal ID',d=>d.events.at(-2).id='invalid'],
 ['action duplicate journal ID',d=>d.events.at(-2).id=uuid(70)],
 ['action duplicate provider event',d=>d.events.splice(-1,0,{...d.events.at(-2),id:uuid(67)})],
 ['action wrong outbox ID',d=>d.outbox_events[0].id=uuid(67)],
 ['action wrong callback type',d=>d.outbox_events[0].callback_type='payment.processing'],
 ['action duplicate outbox ID',d=>d.outbox_events.push({...d.outbox_events[0]})],
 ['action wrong delivery join',d=>d.deliveries[0].outbox_event_id=uuid(67)],
 ['action wrong receiver join',()=>{},i=>i.events[0].event_id=uuid(67)],
 ['action wrong receiver type',()=>{},i=>i.events[0].event_type='payment.processing'],
 ['action duplicate receiver ID',()=>{},i=>i.events.push({...i.events[0]})],
 ['missing final success',d=>d.events.pop()],
 ['duplicate final success',d=>d.events.push({...d.events.at(-1),id:uuid(71)})],
 ['final reconciliation',d=>d.events.at(-1).source='RECONCILIATION'],
 ['final wrong Intent',d=>d.events.at(-1).provider_object_id='pi_other'],
 ['final wrong amount',d=>d.events.at(-1).amount_minor=1999],
 ['final wrong currency',d=>d.events.at(-1).currency='USD'],
 ['final missing provider event',d=>delete d.events.at(-1).provider_event_id],
 ['missing final outbox',d=>{d.outbox_events.pop();d.deliveries=d.deliveries.slice(0,1);}],
 ['undelivered final outbox',d=>d.outbox_events[1].status='PENDING'],
 ['missing final HTTP delivery',d=>d.deliveries=d.deliveries.slice(0,1)],
 ['unsuccessful final HTTP delivery',d=>{for(const v of d.deliveries.slice(1))v.http_status=503;}],
 ['missing final receiver',()=>{},i=>i.events.pop()],
])test('selected 3DS action history refuses '+name+' without writes',async()=>{
 const f=await prepared({threeDSecure:true});f.attempts=1;await f.p.markConfirmation(f.limits);f.success=true;f.fastPoll=true;
 f.mutate=d=>{actionHistory(d);mutate(d);};f.inspection=actionInspection();inspect?.(f.inspection);const n=writes(f);
 await assert.rejects(f.p.verifyPayment(f.limits));assert.equal(writes(f),n);
});
test('ordinary mode cannot select action history by mutating the environment after construction',async()=>{
 const f=await prepared();f.options.env.PAYMENT_STRIPE_SANDBOX_MANUAL_3DS='true';f.attempts=1;await f.p.markConfirmation(f.limits);f.success=true;f.mutate=actionHistory;f.inspection=actionInspection();const n=writes(f);
 await assert.rejects(f.p.verifyPayment(f.limits));assert.equal(writes(f),n);
});
test('failure while snapshotting validated 3DS selection cannot downgrade admission',async()=>{
 const f=paymentFixture();let selectionReads=0;
 f.options.env=new Proxy({...paymentEnv,PAYMENT_STRIPE_SANDBOX_MANUAL_3DS:'true'},{getOwnPropertyDescriptor(target,key){if(key==='PAYMENT_STRIPE_SANDBOX_MANUAL_3DS'&&++selectionReads===2)throw null;return Reflect.getOwnPropertyDescriptor(target,key);}});
 await assert.rejects(diagnosticModule.createManualPaymentProvisioner(f.options,f.dependencies),{message:'stripe_form_diagnostic_provision_OPT_IN_REQUIRED'});assert.equal(f.events.length,0);
});
for(const threeDSecure of [false,true])for(const operation of ['permit','unused'])test(`${threeDSecure?'selected 3DS':'ordinary'} ${operation} refuses action history before permission`,async()=>{
 const f=await prepared({threeDSecure});f.attempts=operation==='permit'?1:0;
 f.mutate=d=>d.events.push({id:uuid(68),event_type:'CUSTOMER_ACTION_REQUIRED',source:'PROVIDER_EVENT',provider_event_id:'evt_action',provider_object_id:'pi_TestOnly',amount_minor:2000,currency:'EUR'});const n=writes(f);
 await assert.rejects(operation==='permit'?f.p.markConfirmation(f.limits):f.p.verifyUnused(f.limits));assert.equal(writes(f),n);
});
