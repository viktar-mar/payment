import {constants} from 'node:fs';
import {lstat,realpath,open,rename,unlink} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {initializeStripeSandboxState} from './smoke-stage-5a.mjs';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PORT_NAMES=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
const EXHAUSTED=new Set(['2f3f49b1-df07-41b1-b978-bc884b390813','6f684b69-32e6-429f-a34c-e4f69ba2ce18']);
const ERRORS=new Set(['OPT_IN_REQUIRED','INPUTS_CONFIGURATION','OPERATION_BUSY','PROVISION_PENDING','HANDOFF_PENDING']);
const fixed=code=>new Error('stripe_sandbox_provision_'+(ERRORS.has(code)?code:'INPUTS_CONFIGURATION'));
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

/** Inert, one-run provisioning capability. Root owns runtime and financial execution. */
export async function createOwnedSandboxProvisioner(options,dependencies={}) {
  let enabled=false;
  try {const env=options?.env;enabled=env?.PAYMENT_STRIPE_SANDBOX_ENABLED==='true'&&env?.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS==='true';}catch{}
  if(!enabled)throw fixed('OPT_IN_REQUIRED');
  let topology,verify,adminCredentials,providerBinding,io,env;
  try {
    const flags=options.env;
    if(![undefined,''].includes(flags.DEBUG)||![undefined,''].includes(flags.NODE_DEBUG)||![undefined,'','0'].includes(flags.PWDEBUG))fail();
    const bootstrap=options.bootstrap;
    if(typeof bootstrap?.resources!=='function'||typeof bootstrap.verify!=='function'||typeof bootstrap.withAdminCredentials!=='function'||typeof options.verifyProviderBinding!=='function')fail();
    const safe=bootstrap.resources(),runId=safe.runId;
    if(typeof runId!=='string'||!UUID.test(runId)||EXHAUSTED.has(runId)||safe.status!=='LIVE'||safe.webhookReady!==true||typeof safe.expectedSource!=='string'||!/^[0-9a-f]{40}$/.test(safe.expectedSource)
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
    const owners={project:safe.project};
    for(const [name,service] of [['backend','backend'],['demo','demo-site'],['receiver','callback-receiver']]){
      const id=safe.containers?.[service]?.id;if(typeof id!=='string'||!/^[0-9a-f]{64}$/.test(id))fail();owners[name]=id;
    }
    if(new Set(Object.values(owners)).size!==4)fail();
    topology=snapshot({runId,expectedSource:safe.expectedSource,daemon:safe.daemon,project:safe.project,siteKey:safe.siteKey,ports,endpoints,owners});
    verify=bootstrap.verify.bind(bootstrap);adminCredentials=bootstrap.withAdminCredentials.bind(bootstrap);providerBinding=options.verifyProviderBinding;
    env=Object.freeze({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'});
    io=Object.freeze({lstat:dependencies.lstat??lstat,realpath:dependencies.realpath??realpath,open:dependencies.open??open,
      rename:dependencies.rename??rename,unlink:dependencies.unlink??unlink,fetch:dependencies.fetch??globalThis.fetch,
      initializeState:dependencies.initializeState??initializeStripeSandboxState,now:dependencies.now??Date.now,
      setTimeout:dependencies.setTimeout??setTimeout,clearTimeout:dependencies.clearTimeout??clearTimeout});
  }catch{throw fixed('INPUTS_CONFIGURATION');}

  const directory=ROOT+'/test-results/stripe-sandbox/'+topology.runId,ledgerPath=directory+'/provisioning.json',lockPath=directory+'/provisioning.lock';
  const suffix=topology.runId.replaceAll('-',''),keys={account:'stripe-sandbox-default',site:topology.siteKey,product:'s5a-product-'+suffix,
    price:'s5a-price-'+suffix,provider:'s5a-email-'+suffix,campaign:'s5a-donate-'+suffix};
  const intents=[],identities=new Map(),cases={},tokens={};
  let status='NEW',busy=false,pending=false,sequence=0,lock=null,admin=null,cookie='',csrf='';
  let account=null,site=null,product=null,price=null,identityProvider=null,campaign=null;
  const stable=name=>'s5a:'+topology.runId+':'+name;
  const resources=()=>snapshot({...topology,status:pending?'PENDING':status,account,site,product,price,identityProvider,campaign,cases});
  function check(context){if(context.signal.aborted||io.now()>=context.deadline)fail();}
  async function controlled(context,body){
    check(context);
    return new Promise((resolveWait,rejectWait)=>{
      const abort=()=>rejectWait(null);context.signal.addEventListener('abort',abort,{once:true});
      Promise.resolve().then(()=>{check(context);return body();}).then(value=>{
        context.signal.removeEventListener('abort',abort);try{check(context);resolveWait(value);}catch{rejectWait(null);}
      },()=>{context.signal.removeEventListener('abort',abort);rejectWait(null);});
      if(context.signal.aborted)abort();
    });
  }
  async function bounded(parent,body,budget=10000){
    check(parent);const controller=new AbortController(),abort=()=>controller.abort();
    const context={deadline:Math.min(parent.deadline,io.now()+budget),signal:controller.signal};
    parent.signal.addEventListener('abort',abort,{once:true});if(parent.signal.aborted)abort();
    const timer=io.setTimeout(abort,Math.max(1,context.deadline-io.now()));
    try{return await controlled(context,()=>body(context));}
    finally{try{io.clearTimeout(timer);parent.signal.removeEventListener('abort',abort);}catch{fail();}}
  }
  const call=(context,fn,...args)=>controlled(context,()=>fn(...args));
  async function close(handle,context){
    const closing=Promise.resolve().then(()=>handle.close());closing.catch(()=>{});
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
    const temp=directory+'/.provisioning-'+(++sequence)+'.tmp',text=JSON.stringify({version:1,...resources(),intents})+'\n';
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
  async function ownership(context){if(await call(context,verify,{signal:context.signal,deadline:context.deadline})!==true)fail();}
  async function request(path,context,{method='GET',body,expected=200,key,receiver=false,login=false}={}){
    return bounded(context,async operation=>{
      await ownership(operation);
      const origin=receiver?topology.endpoints.receiver:topology.endpoints.backend;
      const protectedAdmin=!receiver&&!login&&(path.startsWith('/api/admin/')||path.startsWith('/api/v1/admin/'));
      const headers={Accept:'application/json',...(body!==undefined?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{}),
        ...(protectedAdmin?{Cookie:cookie,...(method!=='GET'?{'X-XSRF-TOKEN':csrf}:{})}:{}),
        ...(!receiver&&path.startsWith('/api/v1/public/')?{Origin:topology.endpoints.demo}:{})};
      if(protectedAdmin&&(!cookie||!csrf))fail();
      const response=await call(operation,io.fetch,origin+path,{method,headers,redirect:'error',signal:operation.signal,...(body!==undefined?{body:JSON.stringify(body)}:{})});
      if(response.status!==expected){await response.body?.cancel().catch(()=>{});fail();}
      const reader=response.body?.getReader();let size=0;const chunks=[];
      if(reader)try{for(;;){const part=await call(operation,()=>reader.read());if(part.done)break;
        size+=part.value.byteLength;if(size>65536)fail();chunks.push(Buffer.from(part.value));}}
      finally{reader.cancel().catch(()=>{});reader.releaseLock();}
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
      ||!same(value.allowed_origins,[topology.endpoints.demo])||!same(value.authentication_modes,['EMAIL_OTP'])
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
  function campaignProof(value,active){
    if(!object(value)||!uuid(value.id)||!uuid(value.site_id)||value.site_id!==site.id||value.key!==keys.campaign||value.currency!=='EUR'
      ||value.display_name!=='Stage5A Donation acceptance'||value.purpose_description!==null||!same(value.suggested_amounts,[500])
      ||value.minimum_amount!==500||value.maximum_amount!==500||value.custom_amount_allowed!==false||value.anonymous_allowed!==true
      ||value.status!==(active?'ACTIVE':'DRAFT')||value.activated_once!==active||value.version!==(active?1:0)||(campaign&&value.id!==campaign.id))fail();
  }
  function checkoutProof(value){
    if(!object(value)||!uuid(value.checkout_id)||!uuid(value.payment_id)||value.checkout_id===value.payment_id||!token(value.checkout_token)
      ||value.amount_minor!==2000||value.currency!=='EUR'||value.provider_type!=='STRIPE'||value.confirmation_mode!=='CLIENT_PROVIDER'
      ||value.status!=='REQUIRES_PAYMENT_METHOD'||!object(value.provider_client_payload)||Object.keys(value.provider_client_payload).length!==0
      ||typeof value.expires_at!=='string'||!Number.isFinite(Date.parse(value.expires_at))||Date.parse(value.expires_at)<=io.now())fail();
    const p=value.price;
    if(!object(p)||!uuid(p.price_id)||p.price_id!==price.id||p.price_key!==price.key||p.version!==price.version||p.amount_minor!==2000||p.currency!=='EUR'||p.tax_mode!=='NONE')fail();
    if(Object.values(cases).some(c=>[c.checkoutId,c.paymentId].includes(value.checkout_id)||[c.checkoutId,c.paymentId].includes(value.payment_id))
      ||Object.values(tokens).includes(value.checkout_token))fail();
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
    if(await bounded(context,operation=>call(operation,providerBinding,{signal:operation.signal,deadline:operation.deadline}))!==true)fail();
    await bounded(context,async operation=>{
      await ownership(operation);
      if(await call(operation,adminCredentials,value=>{
        check(operation);if(!credential(value?.username)||!credential(value?.password))fail();admin=Object.freeze({username:value.username,password:value.password});return true;
      })!==true||!admin)fail();
    });
    const login=await request('/api/admin/v1/auth/sessions',context,{method:'POST',body:admin,expected:204,login:true});captureCookies(login.cookies);
    const accounts=await read('/api/admin/v1/payment-accounts',context),sites=await read('/api/admin/v1/sites',context);
    if(!Array.isArray(accounts)||accounts.length||!Array.isArray(sites)||sites.length)fail();
    await directoryProof(context);await absent(ledgerPath,context);await absent(lockPath,context);
    lock=await exclusive(lockPath,JSON.stringify({runId:topology.runId})+'\n',context,true);status='PROVISIONING';await save(context);
    const createdAccount=await mutation('account',keys.account,'/api/admin/v1/payment-accounts',context,{method:'POST',expected:201,
      body:{key:keys.account,provider:'STRIPE',mode:'SANDBOX',external_account_id:'acct_1UPiMpELswAd8tpR',credentials_ref:'stripe-sandbox-default'}},accountProof,v=>({id:v.id,key:v.key}));
    account={id:createdAccount.id,key:keys.account,externalId:'acct_1UPiMpELswAd8tpR'};
    const createdSite=await mutation('site-and-email-provider',keys.site,'/api/admin/v1/sites',context,{method:'POST',expected:201,
      body:{key:keys.site,display_name:'Stage5A Sandbox acceptance',payment_account_id:account.id,allowed_origins:[topology.endpoints.demo],authentication_modes:['EMAIL_OTP'],payment_method_policy:{mode:'ALLOWLIST',methods:['CARD']},
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
      body:{origin:topology.endpoints.demo,return_url:topology.endpoints.demo+'/payment-return.html',expected_version:-1}},v=>{if(v!==null)fail();},()=>({origin:topology.endpoints.demo}));
    const policy=await read(returnPath,context);
    if(!Array.isArray(policy)||policy.length!==1||!same(policy[0],{origin:topology.endpoints.demo,return_url:topology.endpoints.demo+'/payment-return.html',version:0}))fail();
    const createdProduct=await mutation('product',keys.product,'/api/admin/v1/sites/'+site.id+'/products',context,{method:'POST',expected:201,
      body:{key:keys.product,name:'Stage5A one-time acceptance',description:null}},productProof,v=>({id:v.id,key:v.key}));product={id:createdProduct.id,key:keys.product};
    const createdPrice=await mutation('price',keys.price,'/api/admin/v1/products/'+product.id+'/prices',context,{method:'POST',expected:201,
      body:{price_key:keys.price,type:'ONE_TIME',amount_minor:2000,currency:'EUR',billing_interval:null,trial_days:0,tax_mode:'NONE',tax_code:null}},v=>priceProof(v,false),v=>({id:v.id,key:v.price_key,version:v.version}));
    price={id:createdPrice.id,key:keys.price,version:createdPrice.version};
    const published=await mutation('publish-price',stable('publish-price'),'/api/admin/v1/prices/'+price.id+'/publish',context,{method:'POST'},v=>priceProof(v,true),v=>({id:v.id,key:v.price_key,providerPriceId:v.provider_price_id}));
    price={...price,providerPriceId:published.provider_price_id};await catalogProof(context);
    const inspection=await read('/inspection',context,{receiver:true});
    if(!object(inspection)||Object.keys(inspection).sort().join(',')!=='count,duplicates,events'||inspection.count!==0||inspection.duplicates!==0||!same(inspection.events,[]))fail();
    const callback=await mutation('callback',stable('callback'),sitePath+'/callback',context,{method:'PUT',body:{endpoint_url:'http://callback-receiver:8080/callback',enabled:true}},v=>{
      if(!object(v)||!uuid(v.site_id)||v.site_id!==site.id||v.endpoint_url!=='http://callback-receiver:8080/callback'||v.enabled!==true||v.secret_version!==1||!token(v.secret))fail();
    },v=>({siteId:v.site_id,secretVersion:v.secret_version}));
    await mutation('receiver',stable('receiver'),'/configure',context,{method:'POST',expected:204,receiver:true,body:{secret:callback.secret,secret_version:callback.secret_version}},v=>{if(v!==null)fail();},()=>({secretVersion:1}));
    const createdCampaign=await mutation('campaign',stable('campaign:create'),sitePath+'/donation-campaigns',context,{method:'POST',expected:201,key:stable('campaign:create'),
      body:{key:keys.campaign,display_name:'Stage5A Donation acceptance',purpose_description:null,currency:'EUR',suggested_amounts:[500],minimum_amount:500,maximum_amount:500,custom_amount_allowed:false,anonymous_allowed:true}},v=>campaignProof(v,false),v=>({id:v.id,key:v.key,version:v.version}));
    campaign={id:createdCampaign.id,key:keys.campaign,status:'DRAFT',version:0};
    await mutation('activate-campaign',stable('campaign:activate'),sitePath+'/donation-campaigns/'+campaign.id+'/status',context,{method:'POST',key:stable('campaign:activate'),body:{status:'ACTIVE',expected_version:0}},v=>campaignProof(v,true),v=>({id:v.id,key:v.key,version:v.version}));
    campaign={...campaign,status:'ACTIVE',version:1};campaignProof(await read(sitePath+'/donation-campaigns/'+campaign.id,context),true);
    for(const name of ['success','decline','sca','missed']){
      const value=await mutation('checkout:'+name,stable('checkout:'+name),'/api/v1/public/sites/'+site.key+'/checkouts',context,
        {method:'POST',expected:201,key:stable('checkout:'+name),body:{price_key:price.key}},checkoutProof,v=>({checkoutId:v.checkout_id,paymentId:v.payment_id}));
      cases[name]={checkoutId:value.checkout_id,paymentId:value.payment_id};tokens[name]=value.checkout_token;
    }
    const finalAccounts=await read('/api/admin/v1/payment-accounts',context),finalSites=await read('/api/admin/v1/sites',context);
    if(!Array.isArray(finalAccounts)||finalAccounts.length!==1||!Array.isArray(finalSites)||finalSites.length!==1)fail();accountProof(finalAccounts[0]);siteProof(finalSites[0]);
    await catalogProof(context);await bounded(context,ownership);
    await dispatch('initialize-state',stable('initialize-state'),context);
    await call(context,io.initializeState,snapshot({runId:topology.runId,owners:topology.owners,endpoints:topology.endpoints,account,site,price:{id:price.id,key:price.key},
      cases:Object.fromEntries(['success','decline','sca'].map(name=>[name,cases[name]]))}),{env});
    await acknowledge({runId:topology.runId},context);status='READY';await save(context);
    await directoryProof(context);await fileProof(lockPath,lock,context);await call(context,io.unlink,lockPath);await syncDirectory(context);await close(lock,context);check(context);lock=null;return true;
  }
  function handoff(callback,value){
    try{if(pending||status!=='READY'||busy||typeof callback!=='function')fail();const result=callback(snapshot(value));
      if(result instanceof Promise)result.catch(()=>{});if(result!==true)fail();return true;
    }catch{pending=true;throw fixed('HANDOFF_PENDING');}
  }
  return Object.freeze({resources,
    provision:async limits=>{
      if(busy)throw fixed('OPERATION_BUSY');busy=true;const controller=new AbortController();let timer,external,abort,context;
      try{
        if(pending||status!=='NEW'||!Number.isSafeInteger(limits?.deadline)||limits.deadline<=io.now()||(limits.signal!==undefined&&!(limits.signal instanceof AbortSignal)))fail();
        external=limits.signal;abort=()=>controller.abort();external?.addEventListener('abort',abort,{once:true});if(external?.aborted)abort();
        context={signal:controller.signal,deadline:limits.deadline};
        timer=io.setTimeout(abort,Math.min(2147483647,limits.deadline-io.now()));return await controlled(context,()=>provision(context));
      }catch{pending=true;if(lock){try{await close(lock,context);}catch{}lock=null;}throw fixed('PROVISION_PENDING');}
      finally{busy=false;try{io.clearTimeout(timer);external?.removeEventListener('abort',abort);}catch{pending=true;throw fixed('PROVISION_PENDING');}}
    },
    withCatalogEnvironment:callback=>handoff(callback,{...env,PAYMENT_STRIPE_SANDBOX_RUN_ID:topology.runId,DOCKER_HOST:topology.daemon,
      PAYMENT_SANDBOX_ADMIN_USERNAME:admin?.username,PAYMENT_SANDBOX_ADMIN_PASSWORD:admin?.password,
      ...Object.fromEntries(['success','decline','sca'].map(name=>['PAYMENT_SANDBOX_'+name.toUpperCase()+'_CHECKOUT_TOKEN',tokens[name]]))}),
    withMissedCheckout:callback=>handoff(callback,{checkoutToken:tokens.missed,...cases.missed}),
  });
}
