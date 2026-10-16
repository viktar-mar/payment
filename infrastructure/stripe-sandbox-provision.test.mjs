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
function fixture() {
  const events=[],files=new Map();let serial=0,held=false,checkout=0,accountCreated=false,siteCreated=false,pricePublished=false,providerCreated=false,campaignActive=false;
  const add=(path,directory,contents='')=>files.set(path,{directory,contents:Buffer.from(contents),uid:process.getuid(),mode:directory?0o40700:0o100600,nlink:1,dev:1,ino:++serial});
  for(let path=DIR;path!=='/';path=dirname(path))add(path,true);add('/',true);
  const info=entry=>({...entry,size:entry.contents.length,isDirectory:()=>entry.directory,isFile:()=>!entry.directory,isSymbolicLink:()=>Boolean(entry.symlink)});
  const need=path=>{const entry=files.get(path);if(!entry)throw Object.assign(new Error(),{code:'ENOENT'});return entry;};
  const resource={runId:RUN,expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',project:'stripe-'+RUN,siteKey:'s5a-'+SUFFIX,
    ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
    endpoints:{backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:18081',receiver:'http://127.0.0.1:18082',postgres:'postgresql://127.0.0.1:15432',mailpitHttp:'http://127.0.0.1:18025',mailpitSmtp:'smtp://127.0.0.1:11025'},
    status:'LIVE',webhookReady:true,containers:Object.fromEntries(['postgres','mailpit','backend','demo-site','callback-receiver'].map((name,i)=>[name,{id:String(i+1).repeat(64)}]))};
  const account=()=>({id:ACCOUNT,key:'stripe-sandbox-default',provider:'STRIPE',mode:'SANDBOX',status:'ACTIVE',external_account_id:'acct_1UPiMpELswAd8tpR'});
  const site=()=>({id:SITE,key:'s5a-'+SUFFIX,display_name:'Stage5A Sandbox acceptance',status:'ACTIVE',payment_account_id:ACCOUNT,allowed_origins:[resource.endpoints.demo],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'ALLOWLIST',methods:['CARD']}});
  const product=()=>({id:PRODUCT,site_id:SITE,key:'s5a-product-'+SUFFIX,name:'Stage5A one-time acceptance',description:null,status:'ACTIVE'});
  const price=()=>({id:PRICE,site_id:SITE,product_id:PRODUCT,price_key:'s5a-price-'+SUFFIX,version:1,type:'ONE_TIME',amount_minor:2000,currency:'EUR',billing_interval:null,trial_days:0,trial_payment_method_policy:'OPTIONAL_DURING_TRIAL',tax_mode:'NONE',tax_code:null,status:pricePublished?'PUBLISHED':'DRAFT',provider_price_id:pricePublished?'price_synthetic_original':null});
  const provider=()=>({id:PROVIDER,provider_key:'s5a-email-'+SUFFIX,mode:'EMAIL_OTP',status:'ACTIVE',version:0,issuer:null,audiences:[],allowed_algorithms:[],jwks_source_type:null,readiness:'READY',keys:[],sender_ref:'local-default',template_key:'otp-default',last_successful_refresh_at:null,jwks_refresh_state:'NOT_APPLICABLE'});
  const campaign=()=>({id:CAMPAIGN,site_id:SITE,key:'s5a-donate-'+SUFFIX,display_name:'Stage5A Donation acceptance',purpose_description:null,currency:'EUR',suggested_amounts:[500],minimum_amount:500,maximum_amount:500,custom_amount_allowed:false,anonymous_allowed:true,status:campaignActive?'ACTIVE':'DRAFT',activated_once:campaignActive,version:campaignActive?1:0,created_at:'2026-10-16T00:00:00Z',updated_at:'2026-10-16T00:00:00Z'});
  function response(status,value=null,cookies=[]) {
    const bytes=Buffer.from(value===null?'':JSON.stringify(value));
    return {status,headers:{getSetCookie:()=>cookies},body:new ReadableStream({start(controller){if(bytes.length)controller.enqueue(bytes);controller.close();}})};
  }
  const bootstrap={resources:()=>resource,verify:async context=>{assert.equal(held,false,'R3 callback lock released before verify');events.push({type:'verify',context});return true;},
    withAdminCredentials:async callback=>{held=true;events.push({type:'credentials'});try {assert.equal(callback(Object.freeze({username:'generated-admin',password:'synthetic-password'})),true);return true;}finally{held=false;}}};
  const options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',STRIPE_API_KEY:'must-not-leak'},bootstrap,
    verifyProviderBinding:async context=>{events.push({type:'binding',context});return true;}};
  const dependencies={execFile:forbidden,fetch:async(url,settings)=>{
    assert.equal(held,false);const path=new URL(url).pathname,method=settings.method,body=settings.body?JSON.parse(settings.body):null;
    events.push({type:'http',url,path,method,body,settings});
    if(path==='/api/admin/v1/auth/sessions')return response(204,null,['PAYMENT_ADMIN_SESSION=session-test; Path=/; HttpOnly; SameSite=Strict','XSRF-TOKEN=csrf-test; Path=/']);
    if(path==='/api/admin/v1/payment-accounts'){if(method==='GET')return response(200,accountCreated?[account()]:[]);accountCreated=true;return response(201,account());}
    if(path==='/api/admin/v1/sites'){if(method==='GET')return response(200,siteCreated?[site()]:[]);siteCreated=true;return response(201,site());}
    if(path.endsWith('/checkout-return-urls'))return method==='PUT'?response(204):response(200,events.some(e=>e.type==='http'&&e.path===path&&e.method==='PUT')?[{origin:resource.endpoints.demo,return_url:resource.endpoints.demo+'/payment-return.html',version:0}]:[]);
    if(path.endsWith('/products'))return response(201,product());
    if(path.endsWith('/prices'))return response(201,price());
    if(path.endsWith('/publish')){pricePublished=true;return response(200,price());}
    if(path.endsWith('/catalog'))return response(200,{products:[product()],prices:[price()]});
    if(path==='/inspection')return response(200,{count:0,duplicates:0,events:[]});
    if(path.endsWith('/callback'))return response(200,{site_id:SITE,endpoint_url:'http://callback-receiver:8080/callback',enabled:true,secret_version:1,secret:SECRET});
    if(path==='/configure')return response(204);
    if(path.endsWith('/identity-providers')){providerCreated=true;return response(201,provider());}
    if(path.endsWith('/identity-providers/'+PROVIDER)){assert.equal(providerCreated,true);return response(200,provider());}
    if(path.endsWith('/donation-campaigns'))return response(201,campaign());
    if(path.endsWith('/status')){campaignActive=true;return response(200,campaign());}
    if(path.endsWith('/donation-campaigns/'+CAMPAIGN))return response(200,campaign());
    if(path.endsWith('/checkouts')){checkout++;return response(201,{checkout_id:uuid(10+checkout),checkout_token:TOKEN(checkout),payment_id:uuid(20+checkout),amount_minor:2000,currency:'EUR',price:{price_id:PRICE,price_key:'s5a-price-'+SUFFIX,version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE'},provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'CREATED',expires_at:new Date(Date.now()+3600000).toISOString()});}
    throw new Error('unexpected synthetic route');
  },lstat:async path=>info(need(path)),realpath:async path=>{need(path);return path;},
    open:async(path,flags,mode)=>{events.push({type:'open',path,flags,mode});
      if(flags&constants.O_CREAT){if(files.has(path)&&(flags&constants.O_EXCL))throw null;if(!files.has(path)){need(dirname(path));add(path,false);}}
      const entry=need(path);if(entry.symlink&&(flags&constants.O_NOFOLLOW))throw null;
      return {stat:async()=>info(entry),writeFile:async text=>{events.push({type:'write',path});entry.contents=Buffer.from(text);},sync:async()=>events.push({type:'sync',path}),close:async()=>events.push({type:'close',path})};
    },rename:async(from,to)=>{events.push({type:'rename',from,to});files.set(to,need(from));files.delete(from);},unlink:async path=>{events.push({type:'unlink',path});need(path);files.delete(path);},
    initializeState:async(metadata,settings)=>{events.push({type:'initialize',metadata,settings});},now:Date.now,setTimeout,clearTimeout};
  return {options,dependencies,events,files,resource,response,account,site,product,price,provider,campaign};
}

test('both opt-ins precede every other option and dependency getter',async()=>{
  let touched=0;
  const trap=new Proxy({},{get(){touched++;throw null;}});
  const options=new Proxy({env:{}},{get(target,key){if(key==='env')return target.env;touched++;throw null;}});
  await assert.rejects(createOwnedSandboxProvisioner(options,trap),{message:'stripe_sandbox_provision_OPT_IN_REQUIRED'});
  assert.equal(touched,0);
});

test('constructor snapshots approved inputs without I/O and rejects unsafe topology before dependencies',async()=>{
  const f=fixture();const capability=await createOwnedSandboxProvisioner(f.options,f.dependencies);
  assert.equal(f.events.length,0);assert.equal(Object.isFrozen(capability),true);
  f.resource.ports.backend=19090;f.options.env.DEBUG='unsafe';
  assert.equal(capability.resources().endpoints?.backend,'http://127.0.0.1:18080');
  for(const mutate of [r=>r.status='PREPARED',r=>r.runId='2f3f49b1-df07-41b1-b978-bc884b390813',r=>r.endpoints.backend='http://localhost:18080',r=>r.siteKey='foreign',r=>r.containers.backend.id='short',r=>r.daemon='https://foreign',r=>r.expectedSource=['e'.repeat(40)]]){
    const g=fixture();mutate(g.resource);let accesses=0;
    await rejects(createOwnedSandboxProvisioner(g.options,new Proxy({},{get(){accesses++;throw null;}})),'INPUTS_CONFIGURATION');assert.equal(accesses,0);
  }
});

test('successful provisioning records each durable intent before HTTP and initializes original three after four',async()=>{
  const f=fixture(),fetcher=f.dependencies.fetch;
  f.dependencies.fetch=async(url,settings)=>{
    if(settings.method!=='GET'&&!url.endsWith('/auth/sessions')){
      const ledger=JSON.parse(f.files.get(DIR+'/provisioning.json').contents);
      assert.equal(ledger.intents.at(-1).status,'DISPATCHING');
      assert.equal(f.events.at(-1).type,'verify');
    }
    return fetcher(url,settings);
  };
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);
  assert.equal(await p.provision(LIMIT()),true);
  assert.equal(p.resources().status,'READY');assert.equal(f.files.has(DIR+'/provisioning.lock'),false);
  const ledger=JSON.parse(f.files.get(DIR+'/provisioning.json').contents);
  assert.equal(ledger.intents.every(i=>i.status==='ACKNOWLEDGED'),true);assert.equal(ledger.intents.length,16);
  const http=f.events.filter(e=>e.type==='http'),init=f.events.find(e=>e.type==='initialize');
  assert.equal(http.filter(e=>e.path.endsWith('/checkouts')).length,4);
  assert.deepEqual(Object.keys(init.metadata.cases),['success','decline','sca']);
  assert.equal(init.metadata.cases.success.checkoutId,uuid(11));assert.equal(init.metadata.owners.backend,'3'.repeat(64));
  assert.equal(init.settings.env.PAYMENT_STRIPE_SANDBOX_ENABLED,'true');
  assert.equal(http[0].path,'/api/admin/v1/auth/sessions');
  for(const e of http){assert.equal(e.settings.redirect,'error');assert.ok(e.settings.signal instanceof AbortSignal);
    const admin=e.path.startsWith('/api/admin/')||e.path.startsWith('/api/v1/admin/');
    if(admin&&!e.path.endsWith('/auth/sessions')){assert.equal(e.settings.headers.Cookie,'PAYMENT_ADMIN_SESSION=session-test; XSRF-TOKEN=csrf-test');if(e.method!=='GET')assert.equal(e.settings.headers['X-XSRF-TOKEN'],'csrf-test');}
    else assert.equal(e.settings.headers.Cookie,undefined);
    assert.equal(e.settings.headers.Authorization,undefined);
  }
  const account=http.find(e=>e.path==='/api/admin/v1/payment-accounts'&&e.method==='POST');
  assert.deepEqual(account.body,{key:'stripe-sandbox-default',provider:'STRIPE',mode:'SANDBOX',external_account_id:'acct_1UPiMpELswAd8tpR',credentials_ref:'stripe-sandbox-default'});
  assert.equal(account.settings.headers['Idempotency-Key'],undefined);
  assert.deepEqual(http.find(e=>e.method==='PUT'&&e.path.endsWith('/checkout-return-urls')).body,{origin:'http://127.0.0.1:18081',return_url:'http://127.0.0.1:18081/payment-return.html',expected_version:-1});
  assert.deepEqual(http.find(e=>e.path==='/configure').body,{secret:SECRET,secret_version:1});
  assert.equal(http.find(e=>e.path.endsWith('/callback')).body.endpoint_url,'http://callback-receiver:8080/callback');
  assert.deepEqual(http.find(e=>e.path.endsWith('/identity-providers')).body,{provider_key:'s5a-email-'+SUFFIX,mode:'EMAIL_OTP',sender_ref:'local-default',template_key:'otp-default'});
  assert.deepEqual(http.filter(e=>e.path.endsWith('/checkouts')).map(e=>e.settings.headers['Idempotency-Key']),['success','decline','sca','missed'].map(n=>'s5a:'+RUN+':checkout:'+n));
  for(const forbiddenText of [SECRET,TOKEN(1),'generated-admin','synthetic-password','session-test','csrf-test','must-not-leak'])assert.equal(f.files.get(DIR+'/provisioning.json').contents.includes(forbiddenText),false);
  let environment,missed;
  assert.equal(p.withCatalogEnvironment(value=>{environment=value;return true;}),true);
  assert.equal(p.withMissedCheckout(value=>{missed=value;return true;}),true);
  assert.equal(environment.PAYMENT_SANDBOX_SUCCESS_CHECKOUT_TOKEN,TOKEN(1));assert.equal(environment.PAYMENT_SANDBOX_SCA_CHECKOUT_TOKEN,TOKEN(3));
  assert.equal(environment.DOCKER_HOST,'unix:///Users/test/docker.sock');assert.equal(environment.STRIPE_API_KEY,undefined);
  assert.deepEqual(missed,{checkoutToken:TOKEN(4),checkoutId:uuid(14),paymentId:uuid(24)});assert.equal(Object.isFrozen(missed),true);
  assert.equal(Object.isFrozen(p.resources().cases),true);assert.equal(JSON.stringify(p.resources()).includes(TOKEN(4)),false);
  await rejects(p.provision(LIMIT()));
});

test('lost writer ownership stops before the next fixture write and retains the foreign lock',async()=>{
  const f=fixture(),fetcher=f.dependencies.fetch;let replacement;
  f.dependencies.fetch=async(url,settings)=>{
    const result=await fetcher(url,settings);
    if(url.endsWith('/payment-accounts')&&settings.method==='POST'){
      replacement={...f.files.get(DIR+'/provisioning.lock'),ino:9000};f.files.set(DIR+'/provisioning.lock',replacement);
    }return result;
  };
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
  assert.equal(f.events.filter(e=>e.type==='http'&&e.method!=='GET'&&!e.path.endsWith('/auth/sessions')).length,1);
  assert.equal(f.files.get(DIR+'/provisioning.lock'),replacement);assert.equal(f.events.some(e=>e.type==='unlink'),false);
});

test('changed temporary ledger inode is refused before rename and before any fixture mutation',async()=>{
  const f=fixture(),open=f.dependencies.open;let changed=false;
  f.dependencies.open=async(path,flags,mode)=>{
    const handle=await open(path,flags,mode);
    if(path===DIR+'/.provisioning-1.tmp'){
      const close=handle.close;handle.close=async()=>{await close();if(!changed){changed=true;f.files.set(path,{...f.files.get(path),ino:9900});}};
    }return handle;
  };
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
  assert.equal(f.events.some(e=>e.type==='rename'),false);assert.equal(f.events.some(e=>e.type==='http'&&e.method==='POST'&&e.path.endsWith('/payment-accounts')),false);
});

test('a never-settling error-body cancellation cannot outlive the whole HTTP deadline',async()=>{
  const f=fixture(),timers=new Map();let serial=0,expired=false;
  f.dependencies.setTimeout=(callback,ms)=>{const id=++serial;timers.set(id,{callback,ms});return id;};f.dependencies.clearTimeout=id=>timers.delete(id);
  f.dependencies.fetch=async()=>({status:500,body:{cancel:()=>new Promise(()=>{})}});
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies),outcome=p.provision(LIMIT()).catch(()=>{expired=true;});
  await turn();for(const timer of [...timers.values()])if(timer.ms<=10000)timer.callback();await turn();
  assert.equal(expired,true);await outcome;assert.equal(p.resources().status,'PENDING');
});

test('a never-settling lock close cannot retain a successful operation beyond its absolute deadline',async()=>{
  const f=fixture(),open=f.dependencies.open,timers=new Map();let serial=0,expired=false;
  f.dependencies.setTimeout=(callback,ms)=>{const id=++serial;timers.set(id,{callback,ms});return id;};f.dependencies.clearTimeout=id=>timers.delete(id);
  f.dependencies.open=async(path,flags,mode)=>{const handle=await open(path,flags,mode);if(path===DIR+'/provisioning.lock')handle.close=()=>new Promise(()=>{});return handle;};
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies),outcome=p.provision(LIMIT()).catch(()=>{expired=true;});
  for(let n=0;n<10&&!f.events.some(e=>e.type==='unlink');n++)await turn();
  assert.equal(f.events.some(e=>e.type==='unlink'),true);
  for(const timer of [...timers.values()])timer.callback();await turn();assert.equal(expired,true);await outcome;
});

test('each fresh ownership refusal stops credentials or the next HTTP request without replay',async()=>{
  for(let failAt=1;failAt<=30;failAt++){
    const f=fixture();let count=0,requestsAtFailure;
    f.options.bootstrap.verify=async context=>{f.events.push({type:'verify',context});if(++count===failAt){requestsAtFailure=f.events.filter(e=>e.type==='http').length;return false;}return true;};
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
    assert.equal(f.events.filter(e=>e.type==='http').length,requestsAtFailure,'no request after failed verification '+failAt);
    assert.equal(f.events.some(e=>e.type==='initialize'),false);if(failAt===1)assert.equal(f.events.some(e=>e.type==='credentials'),false);
    const before=f.events.length;await rejects(p.provision(LIMIT()));assert.equal(f.events.length,before);
  }
});

test('binding must return exactly true before any credentials or fixture writes',async()=>{
  for(const value of [false,undefined,{status:'READY'},Promise.resolve(false)]){
    const f=fixture();f.options.verifyProviderBinding=()=>value;const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);
    await rejects(p.provision(LIMIT()));assert.equal(f.events.some(e=>['credentials','http','open'].includes(e.type)),false);
  }
});

test('invalid deadlines, cancellation and overlapping calls cannot start another lifecycle',async()=>{
  for(const limits of [{},{deadline:Date.now()-1},{deadline:Infinity},{...LIMIT(),signal:{}}]){
    const f=fixture(),p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(limits));assert.equal(f.events.length,0);
  }
  const f=fixture(),controller=new AbortController();let entered,release;
  const started=new Promise(resolveStarted=>{entered=resolveStarted;});
  f.options.bootstrap.verify=()=>{entered();return new Promise(resolveVerify=>{release=resolveVerify;});};
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies),operation=p.provision({...LIMIT(),signal:controller.signal});
  await started;await rejects(p.provision(LIMIT()),'OPERATION_BUSY');controller.abort();await rejects(operation);
  release(true);await turn();assert.equal(p.resources().status,'PENDING');assert.equal(f.events.length,0);
});

test('every uncertain mutation retains DISPATCHING and stops later writes without a replacement key',async()=>{
  for(let failAt=1;failAt<=15;failAt++){
    const f=fixture(),fetcher=f.dependencies.fetch;let mutations=0,lastLedger;
    f.dependencies.fetch=async(url,settings)=>{
      if(settings.method!=='GET'&&!url.endsWith('/auth/sessions')&&++mutations===failAt){
        lastLedger=JSON.parse(f.files.get(DIR+'/provisioning.json').contents);
        throw new Proxy({},{get(){assert.fail('raw HTTP error properties must not be read');}});
      }return fetcher(url,settings);
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
    assert.equal(mutations,failAt);assert.equal(lastLedger.intents.at(-1).status,'DISPATCHING');
    assert.equal(lastLedger.intents.at(-1).identity,undefined);assert.equal(f.files.has(DIR+'/provisioning.lock'),true);
    assert.equal(f.events.some(e=>e.type==='initialize'||e.type==='unlink'),false);const before=f.events.length;
    await rejects(p.provision(LIMIT()));assert.equal(f.events.length,before);
    assert.throws(()=>p.withMissedCheckout(()=>true),{message:'stripe_sandbox_provision_HANDOFF_PENDING'});
  }
});

test('all four checkouts independently reject mismatched identity, source, money or early client secrets',async()=>{
  const changes=[v=>v.checkout_id='bad',v=>v.payment_id='bad',v=>v.amount_minor=500,v=>v.currency='USD',v=>v.price.price_id=uuid(999),
    v=>v.price.price_key='foreign-price',v=>v.price.version=2,v=>v.price.amount_minor=500,v=>v.price.currency='USD',v=>v.price.tax_mode='INCLUSIVE',
    v=>v.provider_type='FAKE',v=>v.confirmation_mode='SERVER',v=>v.provider_client_payload={client_secret:'synthetic-should-never-exist'},
    v=>v.status='SUCCEEDED',v=>v.checkout_token='wrong',v=>v.expires_at='2000-01-01T00:00:00Z'];
  for(let caseIndex=1;caseIndex<=4;caseIndex++)for(const change of changes){
    const f=fixture(),fetcher=f.dependencies.fetch;let created=0;
    f.dependencies.fetch=async(url,settings)=>{
      const response=await fetcher(url,settings);if(!url.endsWith('/checkouts'))return response;
      if(++created!==caseIndex)return response;const value=await new Response(response.body).json();change(value);return f.response(201,value);
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
    assert.equal(created,caseIndex);assert.equal(f.events.some(e=>e.type==='initialize'),false);
    const ledger=JSON.parse(f.files.get(DIR+'/provisioning.json').contents);assert.equal(ledger.intents.at(-1).status,'DISPATCHING');
  }
});

test('duplicate checkout IDs, payment IDs and tokens cannot initialize state',async()=>{
  for(const field of ['checkout_id','payment_id','checkout_token']){
    const f=fixture(),fetcher=f.dependencies.fetch;let previous;
    f.dependencies.fetch=async(url,settings)=>{
      const response=await fetcher(url,settings);if(!url.endsWith('/checkouts'))return response;
      const value=await new Response(response.body).json();if(previous)value[field]=previous[field];else previous=value;return f.response(201,value);
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));assert.equal(Object.keys(p.resources().cases).length,1);
    assert.equal(f.events.some(e=>e.type==='initialize'),false);
  }
});

test('login rejects missing, duplicate, malformed and foreign-domain cookies before any fixture write',async()=>{
  for(const cookies of [[],['PAYMENT_ADMIN_SESSION=only; Path=/'],['PAYMENT_ADMIN_SESSION=a; Path=/','PAYMENT_ADMIN_SESSION=b; Path=/'],
    ['PAYMENT_ADMIN_SESSION=a\r\nInjected=b; Path=/','XSRF-TOKEN=b; Path=/'],['PAYMENT_ADMIN_SESSION=a; Path=/; Domain=other','XSRF-TOKEN=b; Path=/'],
    ['PAYMENT_ADMIN_SESSION=a; Path=/else','XSRF-TOKEN=b; Path=/'],['PAYMENT_ADMIN_SESSION=a; Path=/','XSRF-TOKEN=b; Path=/','extra=c; Path=/']]){
    const f=fixture();f.dependencies.fetch=async()=>f.response(204,null,cookies);
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));assert.equal(f.events.some(e=>e.type==='open'),false);
  }
});

test('HTTP bounds include verification and complete response body, with safe errors and redirect refusal',async()=>{
  for(const failure of ['oversize','malformed','redirect','body-hangs','verify-budget']){
    const f=fixture(),fetcher=f.dependencies.fetch,timers=new Map();let timerId=0,now=Date.now(),verifies=0,fetches=0;
    f.dependencies.now=()=>now;
    f.dependencies.setTimeout=(callback,ms)=>{const id=++timerId;timers.set(id,{callback,ms});return id;};f.dependencies.clearTimeout=id=>timers.delete(id);
    f.options.bootstrap.verify=async context=>{assert.ok(context.deadline-now<=10000);if(++verifies===3&&failure==='verify-budget')now+=9999;return true;};
    f.dependencies.fetch=async(url,settings)=>{
      fetches++;assert.equal(settings.redirect,'error');
      if(failure==='redirect')throw new Proxy({},{get(){assert.fail('raw redirect error inspected');}});
      if(failure==='body-hangs')return {status:204,body:new ReadableStream({pull:()=>new Promise(()=>{})}),headers:{getSetCookie:()=>[]}};
      if(failure==='verify-budget'){now+=2;return fetcher(url,settings);}
      return {status:204,body:new ReadableStream({start(c){c.enqueue(Buffer.from(failure==='oversize'?'x'.repeat(65537):'private malformed response'));c.close();}}),headers:{getSetCookie:()=>[]}};
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies),outcome=p.provision(LIMIT());
    const rejection=rejects(outcome);if(failure==='body-hangs'){await turn();for(const timer of [...timers.values()])if(timer.ms<=10000)timer.callback();}
    await rejection;assert.equal(fetches,1);assert.equal(f.events.some(e=>e.type==='open'),false);
  }
});

test('private preexisting files, unsafe directories and hardlinked ledgers refuse before a subsequent write',async()=>{
  for(const target of ['provisioning.json','provisioning.lock','symlink-directory','wide-directory','foreign-directory','hardlink-ledger','changed-ledger']){
    const f=fixture(),fetcher=f.dependencies.fetch;
    if(target.startsWith('provisioning.'))f.files.set(DIR+'/'+target,{...f.files.get(DIR),directory:false,mode:0o100600,ino:8000});
    if(target==='symlink-directory')f.files.get(DIR).symlink=true;
    if(target==='wide-directory')f.files.get(DIR).mode=0o40755;
    if(target==='foreign-directory')f.files.get(DIR).uid++;
    f.dependencies.fetch=async(url,settings)=>{
      const response=await fetcher(url,settings);
      if(url.endsWith('/payment-accounts')&&settings.method==='POST'){
        if(target==='hardlink-ledger')f.files.get(DIR+'/provisioning.json').nlink=2;
        if(target==='changed-ledger')f.files.set(DIR+'/provisioning.json',{...f.files.get(DIR+'/provisioning.json'),ino:9900});
      }return response;
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
    assert.equal(f.events.filter(e=>e.type==='http'&&e.method!=='GET'&&!e.path.endsWith('/auth/sessions')).length,target.endsWith('ledger')?1:0);
    assert.equal(f.events.some(e=>e.type==='unlink'),false);
  }
});

test('memory-only handoffs require synchronous exact-true callbacks and immutable snapshots',async()=>{
  const before=fixture(),early=await createOwnedSandboxProvisioner(before.options,before.dependencies);
  assert.throws(()=>early.withCatalogEnvironment(()=>true),{message:'stripe_sandbox_provision_HANDOFF_PENDING'});assert.equal(before.events.length,0);
  for(const callback of [()=>false,()=>({}),async()=>true,()=>{throw new Proxy({},{get(){assert.fail('callback error inspected');}});}]){
    const f=fixture(),p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await p.provision(LIMIT());const count=f.events.length;
    assert.throws(()=>p.withCatalogEnvironment(callback),{message:'stripe_sandbox_provision_HANDOFF_PENDING'});assert.equal(f.events.length,count);
  }
  const f=fixture(),p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await p.provision(LIMIT());
  assert.equal(p.withCatalogEnvironment(value=>{assert.equal(Object.isFrozen(value),true);assert.throws(()=>{value.DOCKER_HOST='foreign';});return true;}),true);
  assert.equal(p.withMissedCheckout(value=>{assert.throws(()=>{value.checkoutToken='wrong';});return true;}),true);
});

test('cleanup boundary failures remain fixed safe errors and permanently prevent provisioning',async()=>{
  const f=fixture();f.dependencies.clearTimeout=id=>{clearTimeout(id);throw new Proxy({},{get(){assert.fail('cleanup error inspected');}});};
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
  assert.equal(p.resources().status,'PENDING');assert.equal(f.events.some(e=>e.type==='credentials'||e.type==='http'),false);
});

test('immutable initializer metadata cannot alter later safe identities or checkout handoffs',async()=>{
  const f=fixture();f.dependencies.initializeState=async metadata=>{
    assert.equal(Object.isFrozen(metadata),true);assert.equal(Object.isFrozen(metadata.account),true);
    assert.throws(()=>{metadata.account.id=uuid(999);});assert.throws(()=>{metadata.cases.success.checkoutId=uuid(999);});
  };
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await p.provision(LIMIT());assert.equal(p.resources().account.id,ACCOUNT);
});

test('Account Site Product Price callback EMAIL_OTP and campaign response mismatches stop subsequent mutations',async()=>{
  const scenarios=[
    ['/payment-accounts','POST',v=>v.external_account_id='acct_foreign'],['/payment-accounts','POST',v=>v.provider='FAKE'],
    ['/sites','POST',v=>v.payment_account_id=uuid(999)],['/sites','POST',v=>v.allowed_origins=['http://127.0.0.1:9999']],
    ['/sites','POST',v=>v.authentication_modes=['TOKEN']],['/sites','POST',v=>v.payment_method_policy.methods=['CARD','PAYPAL']],
    ['/products','POST',v=>v.site_id=uuid(999)],['/prices','POST',v=>v.version=2],['/prices','POST',v=>v.tax_mode='EXCLUSIVE'],
    ['/publish','POST',v=>v.id=uuid(999)],['/publish','POST',v=>v.provider_price_id=''],
    ['/callback','PUT',v=>v.site_id=uuid(999)],['/callback','PUT',v=>v.endpoint_url='http://127.0.0.1:18082/callback'],
    ['/callback','PUT',v=>v.secret_version=2],['/callback','PUT',v=>v.secret='A'.repeat(42)+'B'],
    ['/identity-providers','POST',v=>v.mode='TOKEN'],['/identity-providers','POST',v=>v.status='DISABLED'],
    ['/identity-providers','POST',v=>v.readiness='NOT_READY'],['/identity-providers','POST',v=>v.sender_ref='foreign'],
    ['/donation-campaigns','POST',v=>v.status='ACTIVE'],['/donation-campaigns','POST',v=>v.minimum_amount=100],
    ['/status','POST',v=>v.version=0],['/status','POST',v=>v.activated_once=false],['/status','POST',v=>v.site_id=uuid(999)],
  ];
  for(const [suffix,method,change] of scenarios){
    const f=fixture(),fetcher=f.dependencies.fetch;let stoppedAt;
    f.dependencies.fetch=async(url,settings)=>{
      const response=await fetcher(url,settings);if(!url.endsWith(suffix)||settings.method!==method)return response;
      stoppedAt=f.events.filter(e=>e.type==='http').length;const value=await new Response(response.body).json();change(value);return f.response(response.status,value);
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
    assert.equal(f.events.filter(e=>e.type==='http').length,stoppedAt);assert.equal(f.events.some(e=>e.type==='initialize'),false);
    assert.equal(JSON.parse(f.files.get(DIR+'/provisioning.json').contents).intents.at(-1).status,'DISPATCHING');
  }
});

test('fresh-database reads and receiver inspection never adopt unexpected existing data',async()=>{
  for(const path of ['/api/admin/v1/payment-accounts','/api/admin/v1/sites','/inspection']){
    const f=fixture(),fetcher=f.dependencies.fetch;
    f.dependencies.fetch=async(url,settings)=>{
      if(new URL(url).pathname===path&&settings.method==='GET')return f.response(200,path==='/inspection'?{count:1,duplicates:0,events:[{event_id:uuid(999),event_type:'PAYMENT_SUCCEEDED',count:1}]}:[{id:uuid(999)}]);
      return fetcher(url,settings);
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
    assert.equal(f.events.some(e=>e.type==='http'&&(e.path.endsWith('/callback')||e.path==='/configure')),false);
    if(path!=='/inspection')assert.equal(f.events.some(e=>e.type==='open'),false);
  }
});

test('durable fsync acknowledgement failure stops before any later write and retains safe original evidence',async()=>{
  const f=fixture(),open=f.dependencies.open;let failed=false;
  f.dependencies.open=async(path,flags,mode)=>{
    const handle=await open(path,flags,mode);
    if(path.endsWith('/.provisioning-3.tmp'))handle.sync=async()=>{failed=true;throw new Error('private fsync detail');};return handle;
  };
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));assert.equal(failed,true);
  const ledger=JSON.parse(f.files.get(DIR+'/provisioning.json').contents);
  assert.equal(ledger.intents[0].status,'DISPATCHING');assert.equal(ledger.intents[0].key,'stripe-sandbox-default');
  assert.equal(f.events.filter(e=>e.type==='http'&&e.method!=='GET'&&!e.path.endsWith('/auth/sessions')).length,1);
  assert.equal(f.files.has(DIR+'/provisioning.lock'),true);
});

test('snapshotted function boundaries and explicit environment prevent later caller mutation from changing requests',async()=>{
  const f=fixture(),p=await createOwnedSandboxProvisioner(f.options,f.dependencies);
  f.dependencies.fetch=forbidden;f.options.bootstrap.verify=forbidden;f.options.bootstrap.withAdminCredentials=forbidden;f.options.verifyProviderBinding=forbidden;
  f.options.env.DEBUG='unsafe';f.options.env.PAYMENT_STRIPE_SANDBOX_ENABLED='false';
  await p.provision(LIMIT());assert.equal(p.resources().status,'READY');
  assert.equal(p.withCatalogEnvironment(env=>{assert.equal(env.DEBUG,undefined);assert.equal(env.PAYMENT_STRIPE_SANDBOX_ENABLED,'true');return true;}),true);
});

test('every private file is exclusive nofollow UID0600 single-link and file plus directory sync precede first fixture HTTP',async()=>{
  const f=fixture(),p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await p.provision(LIMIT());
  for(const event of f.events.filter(e=>e.type==='open'&&(e.flags&constants.O_CREAT))){
    assert.equal(Boolean(event.flags&constants.O_EXCL),true);assert.equal(Boolean(event.flags&constants.O_NOFOLLOW),true);assert.equal(event.mode,0o600);
  }
  const first=f.events.findIndex(e=>e.type==='http'&&e.path.endsWith('/payment-accounts')&&e.method==='POST');
  const durable=f.events.slice(0,first),rename=durable.findLastIndex(e=>e.type==='rename');
  assert.ok(rename>0);assert.equal(durable.slice(rename).some(e=>e.type==='sync'&&e.path===DIR),true);
  assert.equal(durable.slice(0,rename).some(e=>e.type==='sync'&&e.path.endsWith('/.provisioning-2.tmp')),true);
  const release=f.events.findIndex(e=>e.type==='unlink'),close=f.events.findLastIndex(e=>e.type==='close'&&e.path===DIR+'/provisioning.lock');
  assert.ok(close>release);assert.ok(f.events.slice(release,close).some(e=>e.type==='sync'&&e.path===DIR));
  for(const entry of f.files.values())if(!entry.directory){assert.equal(entry.uid,process.getuid());assert.equal(entry.mode&0o777,0o600);assert.equal(entry.nlink,1);}
});

test('missing injected HTTP is caught by builtin guards rather than reaching any user endpoint or file',async()=>{
  const f=fixture();delete f.dependencies.fetch;const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
  assert.equal(f.events.some(e=>e.type==='open'||e.type==='http'),false);assert.equal(p.resources().status,'PENDING');
});

test('debug options and missing either opt-in cannot access protected dependencies',async()=>{
  for(const env of [{PAYMENT_STRIPE_SANDBOX_ENABLED:'true'},{PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'},
    {PAYMENT_STRIPE_SANDBOX_ENABLED:true,PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:true}]){
    let accesses=0;await rejects(createOwnedSandboxProvisioner({env},new Proxy({},{get(){accesses++;throw null;}})),'OPT_IN_REQUIRED');assert.equal(accesses,0);
  }
  for(const [name,value] of [['DEBUG','1'],['NODE_DEBUG','http'],['PWDEBUG','1']]){
    const f=fixture();f.options.env[name]=value;let accesses=0;
    await rejects(createOwnedSandboxProvisioner(f.options,new Proxy({},{get(){accesses++;throw null;}})),'INPUTS_CONFIGURATION');assert.equal(accesses,0);assert.equal(f.events.length,0);
  }
});

test('I1 every external resource UUID must be a string before acknowledgement or subsequent requests',async()=>{
  for(const suffix of ['/payment-accounts','/sites','/products','/prices','/identity-providers','/donation-campaigns']){
    const f=fixture(),fetcher=f.dependencies.fetch;let responseRequests,malformedId;
    f.dependencies.fetch=async(url,settings)=>{
      const response=await fetcher(url,settings);
      if(settings.method!=='POST'||!url.endsWith(suffix))return response;
      responseRequests=f.events.filter(e=>e.type==='http').length;
      const value=await new Response(response.body).json();malformedId=[value.id];value.id=malformedId;return f.response(response.status,value);
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
    assert.equal(f.events.filter(e=>e.type==='http').length,responseRequests,'first malformed '+suffix+' stops subsequent requests');
    const ledger=JSON.parse(f.files.get(DIR+'/provisioning.json').contents),intent=ledger.intents.at(-1);
    assert.equal(intent.status,'DISPATCHING');assert.equal(intent.identity,undefined);
    assert.equal(f.events.some(e=>e.type==='initialize'||e.type==='unlink'),false);assert.equal(f.files.has(DIR+'/provisioning.lock'),true);
    assert.equal(JSON.stringify(ledger).includes(JSON.stringify(malformedId)),false);
  }
});

test('I1 array-wrapped checkout and payment UUIDs stop the first malformed case before later writes or initialization',async()=>{
  for(const field of ['checkout_id','payment_id'])for(const caseIndex of [1,2,3,4]){
    const f=fixture(),fetcher=f.dependencies.fetch;let requests=0;
    f.dependencies.fetch=async(url,settings)=>{
      const response=await fetcher(url,settings);if(!url.endsWith('/checkouts'))return response;
      if(++requests!==caseIndex)return response;const value=await new Response(response.body).json();value[field]=[value[field]];return f.response(201,value);
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
    assert.equal(requests,caseIndex);assert.equal(Object.keys(p.resources().cases).length,caseIndex-1);
    const intent=JSON.parse(f.files.get(DIR+'/provisioning.json').contents).intents.at(-1);
    assert.equal(intent.status,'DISPATCHING');assert.equal(intent.identity,undefined);
    assert.equal(f.events.some(e=>e.type==='initialize'||e.type==='unlink'),false);assert.equal(f.files.has(DIR+'/provisioning.lock'),true);
  }
});

test('I1 repeated textual UUIDs in separate arrays cannot become four distinct original checkout identities',async()=>{
  for(const fields of [['checkout_id'],['payment_id'],['checkout_id','payment_id']]){
    const f=fixture(),fetcher=f.dependencies.fetch;let requests=0;
    f.dependencies.fetch=async(url,settings)=>{
      const response=await fetcher(url,settings);if(!url.endsWith('/checkouts'))return response;
      requests++;const value=await new Response(response.body).json();
      for(const field of fields)value[field]=[field==='checkout_id'?uuid(777):uuid(778)];return f.response(201,value);
    };
    const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
    assert.equal(requests,1);assert.equal(Object.keys(p.resources().cases).length,0);
    assert.equal(f.events.some(e=>e.type==='initialize'||e.type==='unlink'),false);
    const intent=JSON.parse(f.files.get(DIR+'/provisioning.json').contents).intents.at(-1);
    assert.equal(intent.name,'checkout:success');assert.equal(intent.key,'s5a:'+RUN+':checkout:success');
    assert.equal(intent.status,'DISPATCHING');assert.equal(intent.identity,undefined);
  }
});

test('I1 array and primitive forms of the same textual checkout UUID are rejected at the malformed second response',async()=>{
  const f=fixture(),fetcher=f.dependencies.fetch;let requests=0;
  f.dependencies.fetch=async(url,settings)=>{
    const response=await fetcher(url,settings);if(!url.endsWith('/checkouts'))return response;
    requests++;if(requests!==2)return response;const value=await new Response(response.body).json();value.checkout_id=[uuid(11)];return f.response(201,value);
  };
  const p=await createOwnedSandboxProvisioner(f.options,f.dependencies);await rejects(p.provision(LIMIT()));
  assert.equal(requests,2);assert.equal(p.resources().cases.success.checkoutId,uuid(11));
  assert.equal(Object.keys(p.resources().cases).length,1);assert.equal(f.events.some(e=>e.type==='initialize'),false);
  const intent=JSON.parse(f.files.get(DIR+'/provisioning.json').contents).intents.at(-1);
  assert.equal(intent.name,'checkout:decline');assert.equal(intent.status,'DISPATCHING');assert.equal(intent.identity,undefined);
});
