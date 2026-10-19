import {constants} from 'node:fs';
import {lstat,realpath,open,rename} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {formDiagnosticOptIn} from './stripe-sandbox-owned.mjs';
import {manualFormOptIn} from './stripe-manual-form-options.mjs';
import {manualPaymentOptIn} from './stripe-manual-payment-options.mjs';
import {createFormDiagnosticSettlement} from './stripe-form-diagnostic-settlement.mjs';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PORT_NAMES=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
const EXHAUSTED=new Set(['2f3f49b1-df07-41b1-b978-bc884b390813','6f684b69-32e6-429f-a34c-e4f69ba2ce18']);
const ERRORS=new Set(['OPT_IN_REQUIRED','INPUTS_CONFIGURATION','OPERATION_BUSY','PROVISION_PENDING','HANDOFF_PENDING']);
const fixed=code=>new Error('stripe_form_diagnostic_provision_'+(ERRORS.has(code)?code:'INPUTS_CONFIGURATION'));
const fail=()=>{throw null;};
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const uuid=value=>typeof value==='string'&&UUID.test(value);
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
function frozen(value) {
  if(value&&typeof value==='object'){for(const child of Object.values(value))frozen(child);Object.freeze(value);}return value;
}
const snapshot=value=>frozen(JSON.parse(JSON.stringify(value)));
const token=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{43}$/.test(value)&&Buffer.from(value,'base64url').length===32&&Buffer.from(value,'base64url').toString('base64url')===value;
const credential=value=>typeof value==='string'&&value.length>0&&value.length<=1024&&!/[\x00-\x1f\x7f]/.test(value);

/** Private one-run preparation and read proofs. No confirmation/cleanup deletion capability. */
export async function createFormDiagnosticProvisioner(options,dependencies={}) {
  return createProvisioner(options,dependencies,false);
}
export async function createManualFormDiagnosticProvisioner(options,dependencies={}) {
  return createProvisioner(options,dependencies,true);
}
export async function createManualPaymentProvisioner(options,dependencies={}) {
  return createProvisioner(options,dependencies,'payment');
}
async function createProvisioner(options,dependencies,manual) {
  const payment=manual==='payment';
  let enabled=false,descriptors;
  try {
    if(manual)descriptors=Object.getOwnPropertyDescriptors(options);
    const env=manual?descriptors.env?.value:options?.env;enabled=payment?manualPaymentOptIn(env):manual?manualFormOptIn(env):formDiagnosticOptIn(env);
  }catch{}
  if(!enabled)throw fixed('OPT_IN_REQUIRED');
  let topology,verify,adminCredentials,io,browserOrigin,providerBinding,bootstrap;
  try {
    if(!manual&&Object.hasOwn(options,'browserOrigin'))fail();
    if(manual){
      const descriptor=descriptors.browserOrigin;if(!descriptor||!Object.hasOwn(descriptor,'value'))fail();
      browserOrigin=descriptor.value;
      if(typeof browserOrigin!=='string')fail();const url=new URL(browserOrigin);
      if(url.protocol!=='http:'||url.hostname!=='127.0.0.1'||!Number.isInteger(Number(url.port))||Number(url.port)<1024||Number(url.port)>65535||url.origin!==browserOrigin)fail();
    }
    const flags=manual?descriptors.env.value:options.env;
    if(![undefined,''].includes(flags.DEBUG)||![undefined,''].includes(flags.NODE_DEBUG)||![undefined,'','0'].includes(flags.PWDEBUG))fail();
    if(manual&&(!descriptors.bootstrap||!Object.hasOwn(descriptors.bootstrap,'value')))fail();
    bootstrap=manual?descriptors.bootstrap.value:options.bootstrap;
    if(payment){const descriptor=descriptors.verifyProviderBinding;if(!descriptor||!Object.hasOwn(descriptor,'value')||typeof descriptor.value!=='function')fail();providerBinding=descriptor.value;}
    if(typeof bootstrap?.resources!=='function'||typeof bootstrap.verify!=='function'||typeof bootstrap.withAdminCredentials!=='function')fail();
    const safe=bootstrap.resources(),runId=safe.runId;
    if(typeof runId!=='string'||!UUID.test(runId)||EXHAUSTED.has(runId)||safe.status!=='LIVE'||(payment?(safe.webhookReady!==true||safe.mode!=='MANUAL_PAYMENT'):(safe.webhookReady!==false||safe.mode!=='FORM_DIAGNOSTIC'))||typeof safe.expectedSource!=='string'||!/^[0-9a-f]{40}$/.test(safe.expectedSource)
      ||safe.project!=='stripe-'+runId||safe.siteKey!=='s5a-'+runId.replaceAll('-',''))fail();
    if(!object(safe.ports)||Object.keys(safe.ports).sort().join(',')!==[...PORT_NAMES].sort().join(','))fail();
    const ports=Object.fromEntries(PORT_NAMES.map(name=>[name,safe.ports[name]]));
    if(Object.values(ports).some(port=>!Number.isInteger(port)||port<1024||port>65535)||new Set(Object.values(ports)).size!==6)fail();
    if(typeof safe.daemon!=='string'||/[\s\x00-\x1f\x7f%]/.test(safe.daemon))fail();
    const daemon=new URL(safe.daemon);
    if(daemon.protocol!=='unix:'||daemon.host||daemon.username||daemon.password||daemon.search||daemon.hash||daemon.pathname.length<2
      ||daemon.href!==safe.daemon||safe.daemon!=='unix://'+daemon.pathname)fail();
    const endpoints=Object.fromEntries(['backend','demo','receiver'].map(name=>{
      const expected='http://127.0.0.1:'+ports[name];if(safe.endpoints?.[name]!==expected)fail();return [name,expected];
    }));
    if(manual&&Object.values(ports).includes(Number(new URL(browserOrigin).port)))fail();
    if(!manual)browserOrigin=endpoints.demo;
    const owners={project:safe.project};
    for(const [name,service] of [['backend','backend'],['demo','demo-site'],['receiver','callback-receiver']]){
      const id=safe.containers?.[service]?.id;if(typeof id!=='string'||!/^[0-9a-f]{64}$/.test(id))fail();owners[name]=id;
    }
    if(new Set(Object.values(owners)).size!==4)fail();
    topology=snapshot({runId,expectedSource:safe.expectedSource,daemon:safe.daemon,project:safe.project,siteKey:safe.siteKey,ports,endpoints,owners});
    verify=bootstrap.verify.bind(bootstrap);adminCredentials=bootstrap.withAdminCredentials.bind(bootstrap);
    io=Object.freeze({lstat:dependencies.lstat??lstat,realpath:dependencies.realpath??realpath,open:dependencies.open??open,
      rename:dependencies.rename??rename,fetch:dependencies.fetch??globalThis.fetch,
      now:dependencies.now??Date.now,
      setTimeout:dependencies.setTimeout??setTimeout,clearTimeout:dependencies.clearTimeout??clearTimeout});
  }catch{throw fixed('INPUTS_CONFIGURATION');}

  const directory=ROOT+'/test-results/stripe-sandbox/'+topology.runId,ledgerPath=directory+'/form-provisioning.json',lockPath=directory+'/form-provisioning.lock';
  const suffix=topology.runId.replaceAll('-',''),keys={account:'stripe-sandbox-default',site:topology.siteKey,product:'s5a-product-'+suffix,
    price:'s5a-price-'+suffix,provider:'s5a-email-'+suffix};
  const intents=[],identities=new Map(),cases={};
  let status='NEW',busy=false,pending=false,sequence=0,lock=null,admin=null,cookie='',csrf='';
  let account=null,site=null,product=null,price=null,identityProvider=null,checkout=null,operationProof=null,originalDeadline=null,finalUsed=false,closed=false,permitUsed=false,permitGranted=false,paymentAttempt=null;
  const stable=name=>'s5a:'+topology.runId+':'+name;
  const settlement=createFormDiagnosticSettlement(io);
  const resources=()=>snapshot({...topology,status:pending?'PENDING':status,account,site,product,price,identityProvider,cases,operationProof});
  function check(context){if(context.signal.aborted||io.now()>=context.deadline)fail();}
  async function controlled(context,body){
    return settlement.wait(context,body);
  }
  async function bounded(parent,body,budget=10000){
    check(parent);const controller=new AbortController(),abort=()=>controller.abort();
    const context={deadline:Math.min(parent.deadline,io.now()+budget),signal:controller.signal};
    parent.signal.addEventListener('abort',abort,{once:true});if(parent.signal.aborted)abort();
    const timer=io.setTimeout(abort,Math.max(1,context.deadline-io.now()));
    try{return await controlled(context,()=>body(context));}
    finally{try{io.clearTimeout(timer);parent.signal.removeEventListener('abort',abort);}catch{fail();}}
  }
  const call=(context,fn,...args)=>settlement.call(context,()=>fn(...args),fn===io.open?handle=>handle.close():undefined);
  async function close(handle,context){
    const closing=settlement.release(handle);closing.catch(()=>{});
    if(context.signal.aborted||io.now()>=context.deadline)return;
    await bounded(context,()=>closing);
  }
  function privateFile(info){if(!info?.isFile()||info.isSymbolicLink()||info.uid!==process.getuid()||(info.mode&0o777)!==0o600||info.nlink!==1)fail();}
  async function directoryProof(context){
    for(const path of [ROOT,ROOT+'/test-results',ROOT+'/test-results/stripe-sandbox',directory]){
      const info=await call(context,io.lstat,path);
      if(!info.isDirectory()||info.isSymbolicLink()||info.uid!==process.getuid()||await call(context,io.realpath,path)!==path
        ||(path!==ROOT&&(info.mode&0o777)!==0o700))fail();
    }
  }
  async function fileProof(path,handle,context){
    const held=await call(context,()=>handle.stat()),named=await call(context,io.lstat,path);privateFile(held);privateFile(named);
    if(held.ino!==named.ino||held.dev!==named.dev||await call(context,io.realpath,path)!==path)fail();
    const previous=identities.get(path);if(previous&&(previous.ino!==held.ino||previous.dev!==held.dev))fail();return held;
  }
  async function absent(path,context){
    const missing=await call(context,async()=>{try{await io.lstat(path);return false;}catch(error){return error?.code==='ENOENT';}});
    if(!missing)fail();
  }
  async function syncDirectory(context){
    const handle=await call(context,io.open,directory,constants.O_RDONLY|constants.O_NOFOLLOW);
    try{const info=await call(context,()=>handle.stat());if(!info.isDirectory()||info.uid!==process.getuid()||(info.mode&0o777)!==0o700)fail();await call(context,()=>handle.sync());}
    finally{await close(handle,context);}
  }
  async function exclusive(path,text,context,hold=false){
    await directoryProof(context);const handle=await call(context,io.open,path,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);
    let handedOff=false;
    try{const proof=await fileProof(path,handle,context);await call(context,()=>handle.writeFile(text));await call(context,()=>handle.sync());
      await fileProof(path,handle,context);await syncDirectory(context);identities.set(path,{ino:proof.ino,dev:proof.dev});if(hold){handedOff=true;return handle;}
    }finally{if(!handedOff)await close(handle,context);}
  }
  async function save(context){
    await directoryProof(context);
    if(lock)await fileProof(lockPath,lock,context);
    if(identities.has(ledgerPath)){
      const handle=await call(context,io.open,ledgerPath,constants.O_RDONLY|constants.O_NOFOLLOW);
      try{await fileProof(ledgerPath,handle,context);}finally{await close(handle,context);}
    }else await absent(ledgerPath,context);
    const temp=directory+'/.form-provisioning-'+(++sequence)+'.tmp',text=JSON.stringify({version:1,...resources(),intents})+'\n';
    if(Buffer.byteLength(text)>65536)fail();await exclusive(temp,text,context);
    await directoryProof(context);
    if(identities.has(ledgerPath)){
      const handle=await call(context,io.open,ledgerPath,constants.O_RDONLY|constants.O_NOFOLLOW);
      try{await fileProof(ledgerPath,handle,context);}finally{await close(handle,context);}
    }else await absent(ledgerPath,context);
    const temporary=await call(context,io.open,temp,constants.O_RDONLY|constants.O_NOFOLLOW);
    try{await fileProof(temp,temporary,context);}finally{await close(temporary,context);}
    await call(context,io.rename,temp,ledgerPath);identities.set(ledgerPath,identities.get(temp));await syncDirectory(context);
  }
  async function dispatch(name,key,context){intents.push({name,key,status:'DISPATCHING'});await save(context);}
  async function acknowledge(value,context){
    const intent=intents.at(-1);intent.status='ACKNOWLEDGED';if(value!==undefined)intent.identity=value;await save(context);
  }
  async function ownership(context){
    if(payment){const current=bootstrap.resources();
      if(current.mode!=='MANUAL_PAYMENT'||current.webhookReady!==true||current.status!=='LIVE'||current.runId!==topology.runId||current.expectedSource!==topology.expectedSource||current.daemon!==topology.daemon||current.project!==topology.project||current.siteKey!==topology.siteKey||!same(current.ports,topology.ports)
        ||['backend','demo','receiver'].some(name=>current.endpoints?.[name]!==topology.endpoints[name])
        ||[['backend','backend'],['demo','demo-site'],['receiver','callback-receiver']].some(([name,service])=>current.containers?.[service]?.id!==topology.owners[name]))fail();
    }
    if(await call(context,verify,{signal:context.signal,deadline:context.deadline})!==true)fail();
  }
  async function request(path,context,{method='GET',body,expected=200,key,receiver=false,login=false}={}){
    return bounded(context,async operation=>{
      await ownership(operation);
      const origin=receiver?topology.endpoints.receiver:topology.endpoints.backend;
      const protectedAdmin=!receiver&&!login&&(path.startsWith('/api/admin/')||path.startsWith('/api/v1/admin/'));
      const headers={Accept:'application/json',...(body!==undefined?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{}),
        ...(protectedAdmin?{Cookie:cookie,...(method!=='GET'?{'X-XSRF-TOKEN':csrf}:{})}:{}),
        ...(!receiver&&path.startsWith('/api/v1/public/')?{Origin:browserOrigin}:{})};
      if(protectedAdmin&&(!cookie||!csrf))fail();
      const response=await settlement.call(operation,()=>io.fetch(origin+path,{method,headers,redirect:'error',signal:operation.signal,...(body!==undefined?{body:JSON.stringify(body)}:{})}),value=>value.body?.cancel());
      let size=0;const chunks=[];
      try {
        if(response.redirected||response.status!==expected)fail();
        const reader=response.body?.getReader();
        if(reader){settlement.replaceDisposer(response,async()=>{await reader.cancel();reader.releaseLock();});
          for(;;){const part=await call(operation,()=>reader.read());if(part.done)break;
            size+=part.value.byteLength;if(size>65536)fail();chunks.push(Buffer.from(part.value));}}
      }finally{await close(response,operation);}
      check(operation);const bytes=Buffer.concat(chunks),value=bytes.length?JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes)):null;
      if(expected===204&&bytes.length)fail();
      return {value,cookies:login?response.headers.getSetCookie():null};
    });
  }
  const read=async(path,context,settings)=>(await request(path,context,settings)).value;
  async function mutation(name,key,path,context,settings,predicate,identity){
    await dispatch(name,key,context);const value=await read(path,context,settings);predicate(value);
    await acknowledge(identity(value),context);return value;
  }
  function accountProof(value){
    if(!object(value)||!uuid(value.id)||value.key!==keys.account||value.provider!=='STRIPE'||value.mode!=='SANDBOX'
      ||value.status!=='ACTIVE'||value.external_account_id!=='acct_1UPiMpELswAd8tpR'||(account&&value.id!==account.id))fail();
  }
  function siteProof(value){
    if(!object(value)||!uuid(value.id)||value.key!==keys.site||value.status!=='ACTIVE'||!uuid(value.payment_account_id)||value.payment_account_id!==account.id
      ||!same(value.allowed_origins,[browserOrigin])||!same(value.authentication_modes,['EMAIL_OTP'])
      ||value.payment_method_policy?.mode!=='ALLOWLIST'||!same(value.payment_method_policy.methods,['CARD'])||(site&&value.id!==site.id))fail();
  }
  function productProof(value){
    if(!object(value)||!uuid(value.id)||!uuid(value.site_id)||value.site_id!==site.id||value.key!==keys.product||value.name!=='Stage5A one-time acceptance'
      ||value.description!==null||value.status!=='ACTIVE'||(product&&value.id!==product.id))fail();
  }
  function priceProof(value,published){
    if(!object(value)||!uuid(value.id)||!uuid(value.site_id)||value.site_id!==site.id||!uuid(value.product_id)||value.product_id!==product.id||value.price_key!==keys.price
      ||value.version!==1||value.type!=='ONE_TIME'||value.amount_minor!==2000||value.currency!=='EUR'||value.billing_interval!==null
      ||value.trial_days!==0||value.trial_payment_method_policy!=='OPTIONAL_DURING_TRIAL'||value.tax_mode!=='NONE'||value.tax_code!==null
      ||value.status!==(published?'PUBLISHED':'DRAFT')||(price&&value.id!==price.id))fail();
    if(published){if(typeof value.provider_price_id!=='string'||!/^price_[A-Za-z0-9_]{1,249}$/.test(value.provider_price_id)
      ||(price?.providerPriceId&&value.provider_price_id!==price.providerPriceId))fail();}
    else if(value.provider_price_id!==null)fail();
  }
  async function catalogProof(context){
    const catalog=await read('/api/admin/v1/sites/'+site.id+'/catalog',context);
    if(!object(catalog)||!Array.isArray(catalog.products)||catalog.products.length!==1||!Array.isArray(catalog.prices)||catalog.prices.length!==1)fail();
    productProof(catalog.products[0]);priceProof(catalog.prices[0],true);
  }
  function providerProof(value){
    if(!object(value)||!uuid(value.id)||value.provider_key!==keys.provider||value.mode!=='EMAIL_OTP'||value.status!=='ACTIVE'
      ||value.readiness!=='READY'||value.version!==0||value.sender_ref!=='local-default'||value.template_key!=='otp-default'
      ||value.issuer!==null||!same(value.audiences,[])||!same(value.allowed_algorithms,[])||value.jwks_source_type!==null||!same(value.keys,[])
      ||(identityProvider&&value.id!==identityProvider.id))fail();
  }
  function checkoutProof(value){
    if(!object(value)||!uuid(value.checkout_id)||!uuid(value.payment_id)||value.checkout_id===value.payment_id||!token(value.checkout_token)
      ||value.amount_minor!==2000||value.currency!=='EUR'||value.provider_type!=='STRIPE'||value.confirmation_mode!=='CLIENT_PROVIDER'
      ||value.status!=='REQUIRES_PAYMENT_METHOD'||!object(value.provider_client_payload)||Object.keys(value.provider_client_payload).length!==0
      ||typeof value.expires_at!=='string'||!Number.isFinite(Date.parse(value.expires_at))||Date.parse(value.expires_at)<=io.now())fail();
    const p=value.price;
    if(!object(p)||!uuid(p.price_id)||p.price_id!==price.id||p.price_key!==price.key||p.version!==price.version||p.amount_minor!==2000||p.currency!=='EUR'||p.tax_mode!=='NONE')fail();
    if(Object.values(cases).some(c=>[c.checkoutId,c.paymentId].includes(value.checkout_id)||[c.checkoutId,c.paymentId].includes(value.payment_id)))fail();
  }
  function captureCookies(cookies){
    if(!Array.isArray(cookies)||cookies.length!==2)fail();const values={};
    for(const entry of cookies){
      if(typeof entry!=='string'||entry.length>2048||/[\r\n\x00-\x1f\x7f]/.test(entry))fail();
      const parts=entry.split(';').map(part=>part.trim()),match=/^(PAYMENT_ADMIN_SESSION|XSRF-TOKEN)=([A-Za-z0-9_-]{1,512})$/.exec(parts[0]);
      if(!match||values[match[1]]||parts.some(part=>/^domain=/i.test(part))||!parts.some(part=>/^path=\/$/i.test(part)))fail();values[match[1]]=match[2];
    }
    if(!values.PAYMENT_ADMIN_SESSION||!values['XSRF-TOKEN'])fail();
    cookie='PAYMENT_ADMIN_SESSION='+values.PAYMENT_ADMIN_SESSION+'; XSRF-TOKEN='+values['XSRF-TOKEN'];csrf=values['XSRF-TOKEN'];
  }
  async function provision(context){
    await bounded(context,ownership);
    if(payment&&await call(context,providerBinding,{deadline:context.deadline,signal:context.signal})!==true)fail();
    await bounded(context,async operation=>{
      await ownership(operation);
      if(await call(operation,adminCredentials,value=>{
        check(operation);if(!credential(value?.username)||!credential(value?.password))fail();admin=Object.freeze({username:value.username,password:value.password});return true;
      })!==true||!admin)fail();
    });
    await directoryProof(context);await absent(ledgerPath,context);await absent(lockPath,context);
    lock=await exclusive(lockPath,JSON.stringify({runId:topology.runId})+'\n',context,true);status='PROVISIONING';await save(context);
    await dispatch('admin-session',stable('admin-session'),context);
    const login=await request('/api/admin/v1/auth/sessions',context,{method:'POST',body:admin,expected:204,login:true});captureCookies(login.cookies);
    await acknowledge(undefined,context);
    const accounts=await read('/api/admin/v1/payment-accounts',context),sites=await read('/api/admin/v1/sites',context);
    if(!Array.isArray(accounts)||accounts.length||!Array.isArray(sites)||sites.length)fail();
    const createdAccount=await mutation('account',keys.account,'/api/admin/v1/payment-accounts',context,{method:'POST',expected:201,
      body:{key:keys.account,provider:'STRIPE',mode:'SANDBOX',external_account_id:'acct_1UPiMpELswAd8tpR',credentials_ref:'stripe-sandbox-default'}},accountProof,v=>({id:v.id,key:v.key}));
    account={id:createdAccount.id,key:keys.account,externalId:'acct_1UPiMpELswAd8tpR'};
    const createdSite=await mutation('site-and-email-provider',keys.site,'/api/admin/v1/sites',context,{method:'POST',expected:201,
      body:{key:keys.site,display_name:'Stage5A Sandbox acceptance',payment_account_id:account.id,allowed_origins:[browserOrigin],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'ALLOWLIST',methods:['CARD']},
        email_otp_provider:{provider_key:keys.provider,sender_ref:'local-default',template_key:'otp-default'}}},siteProof,v=>({id:v.id,key:v.key}));
    site={id:createdSite.id,key:keys.site};const sitePath='/api/v1/admin/sites/'+site.id;
    const createdProviders=await read(sitePath+'/identity-providers',context);
    if(!Array.isArray(createdProviders)||createdProviders.length!==1)fail();
    providerProof(createdProviders[0]);
    identityProvider={id:createdProviders[0].id,key:keys.provider,mode:'EMAIL_OTP',status:'ACTIVE',readiness:'READY',version:0};
    providerProof(await read(sitePath+'/identity-providers/'+identityProvider.id,context));
    const returnPath=sitePath+'/checkout-return-urls',policies=await read(returnPath,context);
    if(!Array.isArray(policies)||policies.length)fail();
    await mutation('return-url',stable('return-url'),returnPath,context,{method:'PUT',expected:204,
      body:{origin:browserOrigin,return_url:browserOrigin+'/payment-return.html',expected_version:-1}},v=>{if(v!==null)fail();},()=>({origin:browserOrigin}));
    const policy=await read(returnPath,context);
    if(!Array.isArray(policy)||policy.length!==1||!same(policy[0],{origin:browserOrigin,return_url:browserOrigin+'/payment-return.html',version:0}))fail();
    const createdProduct=await mutation('product',keys.product,'/api/admin/v1/sites/'+site.id+'/products',context,{method:'POST',expected:201,
      body:{key:keys.product,name:'Stage5A one-time acceptance',description:null}},productProof,v=>({id:v.id,key:v.key}));product={id:createdProduct.id,key:keys.product};
    const createdPrice=await mutation('price',keys.price,'/api/admin/v1/products/'+product.id+'/prices',context,{method:'POST',expected:201,
      body:{price_key:keys.price,type:'ONE_TIME',amount_minor:2000,currency:'EUR',billing_interval:null,trial_days:0,tax_mode:'NONE',tax_code:null}},v=>priceProof(v,false),v=>({id:v.id,key:v.price_key,version:v.version}));
    price={id:createdPrice.id,key:keys.price,version:createdPrice.version};
    const published=await mutation('publish-price',stable('publish-price'),'/api/admin/v1/prices/'+price.id+'/publish',context,{method:'POST'},v=>priceProof(v,true),v=>({id:v.id,key:v.price_key,providerPriceId:v.provider_price_id}));
    price={...price,providerPriceId:published.provider_price_id};await catalogProof(context);
    if(payment){
      const inspection=await read('/inspection',context,{receiver:true});
      if(!object(inspection)||Object.keys(inspection).sort().join(',')!=='count,duplicates,events'||inspection.count!==0||inspection.duplicates!==0||!same(inspection.events,[]))fail();
      const callback=await mutation('callback',stable('callback'),sitePath+'/callback',context,{method:'PUT',body:{endpoint_url:'http://callback-receiver:8080/callback',enabled:true}},v=>{
        if(!object(v)||v.site_id!==site.id||v.endpoint_url!=='http://callback-receiver:8080/callback'||v.enabled!==true||v.secret_version!==1||!token(v.secret))fail();
      },v=>({siteId:v.site_id,secretVersion:1}));
      await mutation('receiver',stable('receiver'),'/configure',context,{method:'POST',expected:204,receiver:true,body:{secret:callback.secret,secret_version:1}},v=>{if(v!==null)fail();},()=>({secretVersion:1}));
    }
    checkout=await mutation('checkout',stable('checkout'),'/api/v1/public/sites/'+site.key+'/checkouts',context,
      {method:'POST',expected:201,key:stable('checkout'),body:{price_key:price.key}},checkoutProof,v=>({checkoutId:v.checkout_id,paymentId:v.payment_id}));
    cases.form={checkoutId:checkout.checkout_id,paymentId:checkout.payment_id};
    const finalAccounts=await read('/api/admin/v1/payment-accounts',context),finalSites=await read('/api/admin/v1/sites',context);
    if(!Array.isArray(finalAccounts)||finalAccounts.length!==1||!Array.isArray(finalSites)||finalSites.length!==1)fail();accountProof(finalAccounts[0]);siteProof(finalSites[0]);
    await catalogProof(context);await bounded(context,ownership);
    await noConfirmation(context,true);status='READY';await save(context);
    return snapshot({...(payment?{binding:paymentBinding()}:{reference:operationProof.reference}),session:{version:2,apiBaseUrl:topology.endpoints.backend,siteKey:site.key,priceKey:price.key,
      checkoutId:checkout.checkout_id,checkoutToken:checkout.checkout_token,expiresAt:checkout.expires_at,
      providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',
      state:{checkoutId:checkout.checkout_id,paymentId:checkout.payment_id,amountMinor:2000,currency:'EUR',status:'REQUIRES_PAYMENT_METHOD',expiresAt:checkout.expires_at,
        price:{priceId:price.id,priceKey:price.key,version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'}}}});
  }
  function paymentBinding(){return {paymentId:checkout.payment_id,siteId:site.id,paymentAccountId:account.id,operationId:operationProof.operationId,intentId:operationProof.reference,priceId:price.id,priceKey:price.key,amountMinor:2000,currency:'EUR',externalAccountId:'acct_1UPiMpELswAd8tpR'};}
  async function noConfirmation(context,baseline=false,successful=false) {
    if(!checkout)fail();
    const accounts=await read('/api/admin/v1/payment-accounts',context),sites=await read('/api/admin/v1/sites',context);
    if(!Array.isArray(accounts)||accounts.length!==1||!Array.isArray(sites)||sites.length!==1)fail();
    accountProof(accounts[0]);siteProof(sites[0]);await catalogProof(context);
    const d=await read('/api/v1/admin/payments/'+checkout.payment_id,context),p=d?.payment;
    if(!object(p)||p.payment_id!==checkout.payment_id||p.site_id!==site.id||p.payment_account_id!==account.id||p.provider!=='STRIPE'
      ||p.source!=='CATALOG'||p.price_id!==price.id||p.price_key!==price.key||p.price_version!==1||p.amount_minor!==2000||p.currency!=='EUR'
      ||p.tax_mode!=='NONE'||p.status!==(successful?'SUCCEEDED':'REQUIRES_PAYMENT_METHOD')||p.refunded_amount_minor!==0||p.refundable_amount_minor!==(successful?2000:0)
      ||p.customer_id!==null||p.donation_id!==null||p.subscription_id!==null)fail();
    // Filter by the owned Site only: filtering by the selected payment would hide another checkout.
    const page=await read('/api/v1/admin/payments?site_id='+site.id+'&page=0&size=2&sort=CREATED_DESC',context);
    if(!object(page)||page.total!==1||page.page!==0||page.size!==2||page.sort!=='CREATED_DESC'
      ||!Array.isArray(page.items)||page.items.length!==1||!same(page.items[0],p))fail();
    if(!['attempts','events','refunds','outbox_events','deliveries'].every(k=>Array.isArray(d[k])&&d[k].length<=100)
      ||d.refunds.length||(!successful&&(d.outbox_events.length||d.deliveries.length))||(baseline?d.attempts.length!==0:payment?(successful||permitUsed?d.attempts.length!==1:d.attempts.length>1):manual?d.attempts.length>1:d.attempts.length!==1))fail();
    const operations=await read('/api/v1/admin/provider-operations?payment_id='+checkout.payment_id,context);
    if(!Array.isArray(operations)||operations.length!==1)fail();const op=operations[0];
    if(!uuid(op.operation_id)||op.payment_id!==checkout.payment_id||op.resource_id!==checkout.payment_id||op.account_id!==account.id||op.account_mode!=='SANDBOX'
      ||op.site_id!==site.id||op.kind!=='PAYMENT_PREPARE'||op.status!=='ACKNOWLEDGED'||op.manual_review_required!==false
      ||typeof op.reference!=='string'||!/^pi_[A-Za-z0-9]{1,249}$/.test(op.reference))fail();
    const binding={operationId:op.operation_id,reference:op.reference};if(operationProof&&!same(binding,operationProof))fail();
    if(!operationProof)operationProof=binding;
    if(d.attempts.some(a=>!uuid(a.id)||a.attempt_number!==1||a.status!==(successful?'SUCCEEDED':'REQUIRES_PAYMENT_METHOD')||a.provider_intent_reference!==op.reference
      ||a.payment_method_family!=='CARD'||(!successful&&a.result_code!==null)))fail();
    if(payment&&d.attempts.length){if(paymentAttempt!==null&&d.attempts[0].id!==paymentAttempt)fail();paymentAttempt=d.attempts[0].id;}
    if(d.events.some(e=>!uuid(e.id)||!(successful?['PAYMENT_CREATED','PAYMENT_METHOD_REQUIRED','ATTEMPT_CREATED','PAYMENT_PROCESSING','PAYMENT_SUCCEEDED']:['PAYMENT_CREATED','PAYMENT_METHOD_REQUIRED','ATTEMPT_CREATED']).includes(e.event_type))
      ||new Set(d.events.map(e=>e.id)).size!==d.events.length
      ||d.events.filter(e=>e.event_type==='PAYMENT_CREATED').length!==1
      ||d.events.filter(e=>e.event_type==='ATTEMPT_CREATED').length!==d.attempts.length)fail();
    return successful?d:snapshot({...binding,attempts:d.attempts.length});
  }
  async function paymentProof(context){
    const d=await noConfirmation(context,false,true),success=d.events.filter(e=>e.event_type==='PAYMENT_SUCCEEDED');
    if(success.length!==1)fail();const event=success[0];
    if(event.source!=='PROVIDER_EVENT'||typeof event.provider_event_id!=='string'||!/^evt_[A-Za-z0-9_]{1,200}$/.test(event.provider_event_id)||event.provider_object_id!==operationProof.reference||event.amount_minor!==2000||event.currency!=='EUR')fail();
    const callbacks={PAYMENT_PROCESSING:'payment.processing',PAYMENT_SUCCEEDED:'payment.succeeded'};
    if(new Set(d.outbox_events.map(v=>v.id)).size!==d.outbox_events.length||d.outbox_events.some(v=>!d.events.some(e=>e.id===v.id&&callbacks[e.event_type]===v.callback_type)))fail();
    const out=d.outbox_events.filter(v=>v.id===event.id&&v.callback_type==='payment.succeeded');if(out.length!==1)fail();
    if(out[0].status!=='DELIVERED'||!d.deliveries.some(v=>v.outbox_event_id===event.id&&v.status==='SUCCEEDED'&&Number.isInteger(v.http_status)&&v.http_status>=200&&v.http_status<300))return null;
    if(d.deliveries.some(v=>!d.outbox_events.some(o=>o.id===v.outbox_event_id)))fail();
    const inspected=await read('/inspection',context,{receiver:true});
    if(!object(inspected)||!Array.isArray(inspected.events)||inspected.events.length>100)fail();
    if(inspected.events.some(v=>!d.outbox_events.some(o=>o.id===v.event_id&&o.callback_type===v.event_type)||!Number.isSafeInteger(v.count)||v.count<1)||new Set(inspected.events.map(v=>v.event_id)).size!==inspected.events.length)fail();
    const received=inspected.events.filter(v=>v.event_id===event.id&&v.event_type==='payment.succeeded');if(received.length!==1)return null;
    return snapshot({paymentConfirmed:true,webhookVerified:true,journalVerified:true,callbackVerified:true,providerEventId:event.provider_event_id});
  }
  async function run(limits,body,{cleanup=false}={}) {
    if(busy) {pending=true;throw fixed('OPERATION_BUSY');}busy=true;
    const controller=new AbortController();let timer,abort,context;
    try {
      if(!Number.isSafeInteger(limits?.deadline)||limits.deadline<=io.now()||(limits.signal!==undefined&&!(limits.signal instanceof AbortSignal)))fail();
      if(originalDeadline===null)originalDeadline=limits.deadline;
      settlement.bind(originalDeadline);
      if(cleanup?limits.deadline>originalDeadline+120000:limits.deadline!==originalDeadline)fail();
      abort=()=>controller.abort();limits.signal?.addEventListener('abort',abort,{once:true});if(limits.signal?.aborted)abort();
      context={deadline:limits.deadline,signal:controller.signal};timer=io.setTimeout(abort,Math.min(2147483647,limits.deadline-io.now()));
      return await controlled(context,()=>body(context));
    }catch{pending=true;throw fixed('PROVISION_PENDING');}
    finally{busy=false;io.clearTimeout(timer);limits?.signal?.removeEventListener('abort',abort);}
  }
  const capabilities={
    prepare:limits=>run(limits,async context=>{if(pending||status!=='NEW'||closed)fail();status='PREPARING';return provision(context);}),
    verifyNoConfirmation:limits=>run(limits,async context=>{if(finalUsed||closed||!checkout||!operationProof)fail();finalUsed=true;
      const proof=await noConfirmation(context);await save(context);return proof;}),
    close:limits=>run(limits,async context=>{if(closed)fail();closed=true;
      let ownership=true;
      try{if(lock){await directoryProof(context);await fileProof(lockPath,lock,context);await close(lock,context);lock=null;}}catch{ownership=false;}
      if(!settlement.close(context)||!ownership||intents.some(i=>i.status!=='ACKNOWLEDGED'))fail();return true;},{cleanup:true}),
  };
  if(payment)return Object.freeze({prepare:capabilities.prepare,
    markConfirmation:limits=>run(limits,async context=>{
      if(pending||closed||status!=='READY'||permitUsed||finalUsed)fail();permitUsed=true;
      const baseline=await noConfirmation(context);if(baseline.attempts!==1||pending)fail();
      status='CONFIRMING';await save(context);check(context);if(pending)fail();permitGranted=true;return true;
    }),
    verifyPayment:limits=>run(limits,async context=>{
      if(pending||closed||!permitGranted||finalUsed)fail();finalUsed=true;
      return bounded(context,async polling=>{for(let n=0;n<30;n++){
        const value=await read('/api/v1/admin/payments/'+checkout.payment_id,polling);
        if(value?.payment?.status==='SUCCEEDED'){const proof=await paymentProof(polling);if(proof)return proof;}
        if(n<29)await call(polling,()=>new Promise(resolveWait=>io.setTimeout(resolveWait,Math.min(1500,Math.max(1,polling.deadline-io.now())))));
      }fail();},45000);
    }),
    verifyUnused:limits=>run(limits,async context=>{if(pending||closed||permitUsed||finalUsed||status!=='READY')fail();finalUsed=true;await noConfirmation(context);return true;}),
    close:capabilities.close,
  });
  return Object.freeze(capabilities);
}
