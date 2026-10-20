import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {constants} from 'node:fs';
import {lstat,realpath,open,mkdir,rename,unlink} from 'node:fs/promises';
import {dirname,resolve,parse as parsePath} from 'node:path';
import {fileURLToPath} from 'node:url';
import {timingSafeEqual} from 'node:crypto';
import {validateManualRefundOptions} from './stripe-manual-refund-options.mjs';
import {createFormDiagnosticSettlement} from './stripe-form-diagnostic-settlement.mjs';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const ORIGINAL_RUN='031b5ae7-6603-4339-b1b3-77231291ff34',ORIGINAL_SOURCE='f0620e40004162d2b3eededa60512f50a492f4cc';
const PROJECT='stripe-'+ORIGINAL_RUN,SITEKEY='s5a-'+ORIGINAL_RUN.replaceAll('-','');
const SERVICES=['postgres','mailpit','backend','demo-site','callback-receiver'],RESTORE=['postgres','mailpit','callback-receiver','demo-site','backend'];
const PORTS=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
const fail=()=>{throw null;},fixed=()=>new Error('stripe_manual_refund_state_PENDING');
const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
const fullId=v=>typeof v==='string'&&/^[0-9a-f]{64}$/.test(v);
const provider=(v,prefix)=>typeof v==='string'&&new RegExp('^'+prefix+'[A-Za-z0-9_]{1,249}$').test(v);
function exact(v,keys){if(!v||typeof v!=='object'||Array.isArray(v)||![Object.prototype,null].includes(Object.getPrototypeOf(v)))fail();const d=Object.getOwnPropertyDescriptors(v);if(Reflect.ownKeys(d).length!==keys.length||keys.some(k=>!d[k]?.enumerable||!Object.hasOwn(d[k],'value')))fail();return Object.fromEntries(keys.map(k=>[k,d[k].value]));}
function canonical(v){return Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;}
const same=(a,b)=>JSON.stringify(canonical(a))===JSON.stringify(canonical(b));
function frozen(v){if(v&&typeof v==='object'){for(const child of Object.values(v))frozen(child);Object.freeze(v);}return v;}

/** JSON.parse alone loses duplicate members. Tokenize and reject them at every depth. */
function strictJSON(text){
  const tokens=text.match(/"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null|[{}\[\],:]|[ \t\r\n]+|./gs)?.filter(v=>!(/^[ \t\r\n]+$/).test(v))??[];let i=0;
  function value(depth=0){if(depth>32)fail();const token=tokens[i++];if(token==='{'){const result=Object.create(null),seen=new Set();if(tokens[i]==='}'){i++;return result;}for(;;){const key=JSON.parse(tokens[i++]);if(typeof key!=='string'||seen.has(key)||tokens[i++]!==':')fail();seen.add(key);result[key]=value(depth+1);const end=tokens[i++];if(end==='}')return result;if(end!==',')fail();}}
    if(token==='['){const result=[];if(tokens[i]===']'){i++;return result;}for(;;){result.push(value(depth+1));const end=tokens[i++];if(end===']')return result;if(end!==',')fail();}}
    const result=JSON.parse(token);if(typeof result==='object'&&result!==null||typeof result==='number'&&!Number.isFinite(result))fail();return result;
  }
  const result=value();if(i!==tokens.length)fail();return result;
}
function expectedPorts(service,ports){return Object.fromEntries(({postgres:[['5432/tcp',ports.postgres]],mailpit:[['8025/tcp',ports.mailpitHttp],['1025/tcp',ports.mailpitSmtp]],backend:[['8080/tcp',ports.backend]],'demo-site':[['8080/tcp',ports.demo]],'callback-receiver':[['8080/tcp',ports.receiver]]}[service]??[]).map(([port,host])=>[port,[{HostIp:'127.0.0.1',HostPort:String(host)}]]));}
function expectedMounts(service,root,directory){return {postgres:[{Type:'volume',Name:PROJECT+'-postgres',Destination:'/var/lib/postgresql',RW:true}],backend:[{Type:'volume',Name:PROJECT+'-issuer',Destination:'/app/local-fixtures',RW:false}],'callback-receiver':[{Type:'bind',Source:root+'/infrastructure/callback-receiver.mjs',Destination:'/app/callback-receiver.mjs',RW:false}],'issuer-installer':[{Type:'bind',Source:directory+'/issuer.jwks.json',Destination:'/input/issuer.json',RW:false},{Type:'volume',Name:PROJECT+'-issuer',Destination:'/issuer',RW:true}],'issuer-probe':[{Type:'volume',Name:PROJECT+'-issuer',Destination:'/issuer',RW:false}]}[service]??[];}
const labels=kind=>({'payment.sandbox.project':PROJECT,'payment.sandbox.run':ORIGINAL_RUN,'payment.sandbox.kind':kind});
function decodeRuntime(raw,root,directory,options){
  const r=exact(raw,['runId','expectedSource','daemon','project','siteKey','ports','cliDirectory','endpoints','status','webhookReady','mode','manualPort','images','network','volumes','containers','retiredBackend','intents']);
  if(r.runId!==ORIGINAL_RUN||r.expectedSource!==ORIGINAL_SOURCE||r.daemon!==options.daemon||r.project!==PROJECT||r.siteKey!==SITEKEY||r.cliDirectory!==directory+'/cli'||r.status!=='STOPPED'||r.webhookReady!==true||r.mode!=='MANUAL_PAYMENT'||r.retiredBackend!==null)fail();
  exact(r.ports,PORTS);if(Object.values(r.ports).some(v=>!Number.isInteger(v)||v<1024||v>65535)||new Set(Object.values(r.ports)).size!==6||!Number.isInteger(r.manualPort)||r.manualPort<1024||r.manualPort>65535||Object.values(r.ports).includes(r.manualPort))fail();
  const endpoints=Object.fromEntries(PORTS.map(k=>[k,({postgres:'postgresql',mailpitSmtp:'smtp'}[k]??'http')+'://127.0.0.1:'+r.ports[k]]));if(!same(r.endpoints,endpoints))fail();
  exact(r.images,SERVICES);const tags={backend:PROJECT+'-backend:latest','demo-site':PROJECT+'-demo-site:latest',postgres:'postgres:18-alpine',mailpit:'axllent/mailpit:v1.27','callback-receiver':'node:24-alpine'};
  for(const service of SERVICES){const image=exact(r.images[service],['tag','id','user']);if(image.tag!==tags[service]||typeof image.id!=='string'||!/^sha256:[0-9a-f]{64}$/.test(image.id)||typeof image.user!=='string'||!/^[-A-Za-z0-9_:]{0,128}$/.test(image.user))fail();}
  exact(r.network,['Id','Name','Labels','Driver','Scope']);if(!fullId(r.network.Id)||r.network.Name!==PROJECT+'-network'||r.network.Driver!=='bridge'||r.network.Scope!=='local'||!same(r.network.Labels,labels('network')))fail();
  exact(r.volumes,['postgres','issuer']);for(const kind of ['postgres','issuer']){const v=exact(r.volumes[kind],['Name','CreatedAt','Driver','Scope','Labels']);if(v.Name!==PROJECT+'-'+kind||v.Driver!=='local'||v.Scope!=='local'||typeof v.CreatedAt!=='string'||!Number.isFinite(Date.parse(v.CreatedAt))||!same(v.Labels,labels(kind)))fail();}
  exact(r.containers,[...SERVICES,'issuer-installer','issuer-probe']);const ids=new Set();for(const [service,raw] of Object.entries(r.containers)){const c=exact(raw,['id','name','image','service','status','startedAt','networkId','mounts','ports']);if(!fullId(c.id)||ids.has(c.id)||c.name!==PROJECT+'-'+service||c.service!==service||c.image!==r.images[service.startsWith('issuer-')?'backend':service].id||c.status!=='exited'||typeof c.startedAt!=='string'||!Number.isFinite(Date.parse(c.startedAt))||Date.parse(c.startedAt)<=0||c.networkId!==(service.startsWith('issuer-')?null:r.network.Id)||!same(c.mounts,expectedMounts(service,root,directory))||!same(c.ports,expectedPorts(service,r.ports)))fail();ids.add(c.id);}
  const pairs=[['BUILD','backend+demo-site'],...['postgres','mailpit','callback-receiver'].map(v=>['PULL',v]),['CREATE_NETWORK',PROJECT+'-network'],['CREATE_VOLUME',PROJECT+'-postgres'],['CREATE_VOLUME',PROJECT+'-issuer'],...['issuer-installer','issuer-probe'].flatMap(v=>[['CREATE_HELPER',PROJECT+'-'+v],['START_HELPER',r.containers[v].id]]),['CREATE_STOPPED',PROJECT],['PERSIST_WEBHOOK','backend-key-persistence'],['CREATE_FINAL_BACKEND',PROJECT+'-backend'],...SERVICES.map(v=>['START_SERVICE',r.containers[v].id])];
  if(!same(r.intents,pairs.map(([action,name])=>({action,name,status:'ACKNOWLEDGED'}))))fail();return r;
}
function decodeProvision(raw,r){
  const p=exact(raw,['version','runId','expectedSource','daemon','project','siteKey','ports','endpoints','owners','status','account','site','product','price','identityProvider','cases','operationProof','intents']);
  for(const key of ['runId','expectedSource','daemon','project','siteKey','ports'])if(!same(p[key],r[key]))fail();if(p.version!==1||p.status!=='CONFIRMING'||!same(p.endpoints,Object.fromEntries(['backend','demo','receiver'].map(k=>[k,r.endpoints[k]])))||!same(p.owners,{project:PROJECT,backend:r.containers.backend.id,demo:r.containers['demo-site'].id,receiver:r.containers['callback-receiver'].id}))fail();
  const suffix=ORIGINAL_RUN.replaceAll('-','');exact(p.account,['id','key','externalId']);exact(p.site,['id','key']);exact(p.product,['id','key']);exact(p.price,['id','key','version','providerPriceId']);exact(p.identityProvider,['id','key','mode','status','readiness','version']);exact(p.cases,['form']);exact(p.cases.form,['checkoutId','paymentId']);exact(p.operationProof,['operationId','reference']);
  const ids=[p.account.id,p.site.id,p.product.id,p.price.id,p.identityProvider.id,p.cases.form.checkoutId,p.cases.form.paymentId,p.operationProof.operationId];if(ids.some(v=>!uuid(v))||new Set(ids).size!==ids.length||p.account.key!=='stripe-sandbox-default'||p.account.externalId!=='acct_1UPiMpELswAd8tpR'||p.site.key!==SITEKEY||p.product.key!=='s5a-product-'+suffix||p.price.key!=='s5a-price-'+suffix||p.price.version!==1||!provider(p.price.providerPriceId,'price_')||!provider(p.operationProof.reference,'pi_')||!same(p.identityProvider,{id:p.identityProvider.id,key:'s5a-email-'+suffix,mode:'EMAIL_OTP',status:'ACTIVE',readiness:'READY',version:0}))fail();
  const stable=name=>'s5a:'+ORIGINAL_RUN+':'+name,entries=[['admin-session',stable('admin-session')],['account',p.account.key,{id:p.account.id,key:p.account.key}],['site-and-email-provider',p.site.key,{id:p.site.id,key:p.site.key}],['return-url',stable('return-url'),{origin:'http://127.0.0.1:'+r.manualPort}],['product',p.product.key,{id:p.product.id,key:p.product.key}],['price',p.price.key,{id:p.price.id,key:p.price.key,version:1}],['publish-price',stable('publish-price'),{id:p.price.id,key:p.price.key,providerPriceId:p.price.providerPriceId}],['callback',stable('callback'),{siteId:p.site.id,secretVersion:1}],['receiver',stable('receiver'),{secretVersion:1}],['checkout',stable('checkout'),p.cases.form]];
  if(!same(p.intents,entries.map(([name,key,identity])=>({name,key,status:'ACKNOWLEDGED',...(identity?{identity}:{})}))))fail();return p;
}
const PRIVATE=['PAYMENT_DB_PASSWORD','PAYMENT_ADMIN_BOOTSTRAP_USERNAME','PAYMENT_ADMIN_BOOTSTRAP_PASSWORD','PAYMENT_DATA_ENCRYPTION_KEY','PAYMENT_DONATION_MESSAGE_FINGERPRINT_KEY','PAYMENT_CUSTOMER_DIGEST_KEY_V1','PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1'];
const PUBLIC=['PAYMENT_FAKE_AUTO_SUCCESS','PAYMENT_TELEMETRY_SITEKEYS','PAYMENT_BACKEND_HOST_PORT','PAYMENT_DEMO_HOST_PORT','PAYMENT_POSTGRES_HOST_PORT','PAYMENT_MAILPIT_HOST_PORT','PAYMENT_MAILPIT_SMTP_HOST_PORT'];
const STRIPE=['PAYMENT_STRIPE_SANDBOX_SECRET_KEY','PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY','PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET'];
function decodeEnv(text,final,r){const result=Object.create(null);if(!text.endsWith('\n')||text.includes('\r'))fail();for(const line of text.slice(0,-1).split('\n')){const match=/^([A-Z][A-Z0-9_]*)=([A-Za-z0-9_+/=:-]+)$/.exec(line);if(!match||Object.hasOwn(result,match[1]))fail();result[match[1]]=match[2];}exact(result,[...PRIVATE,...PUBLIC,...(final?STRIPE:[])]);
  for(const key of PRIVATE.filter(k=>k!=='PAYMENT_ADMIN_BOOTSTRAP_USERNAME')){const value=result[key];if(!/^[A-Za-z0-9+/]{43}=$/.test(value)||Buffer.from(value,'base64').toString('base64')!==value)fail();}if(!/^s5a-[0-9a-f]{32}$/.test(result.PAYMENT_ADMIN_BOOTSTRAP_USERNAME)||result.PAYMENT_FAKE_AUTO_SUCCESS!=='false'||result.PAYMENT_TELEMETRY_SITEKEYS!==SITEKEY)fail();
  for(const [key,port] of [['PAYMENT_BACKEND_HOST_PORT','backend'],['PAYMENT_DEMO_HOST_PORT','demo'],['PAYMENT_POSTGRES_HOST_PORT','postgres'],['PAYMENT_MAILPIT_HOST_PORT','mailpitHttp'],['PAYMENT_MAILPIT_SMTP_HOST_PORT','mailpitSmtp']])if(result[key]!==String(r.ports[port]))fail();
  if(final&&(!/^sk_test_[A-Za-z0-9_]{16,256}$/.test(result[STRIPE[0]])||!/^pk_test_[A-Za-z0-9_]{16,256}$/.test(result[STRIPE[1]])||!/^whsec_[A-Za-z0-9_]{16,256}$/.test(result[STRIPE[2]])))fail();return result;
}

/** Private one-shot state authority. No runtime, HTTP, provider or credential getter capability. */
export async function openManualRefundState(input,limits,dependencies={}){
  const options=validateManualRefundOptions(input);if(!options)throw fixed();
  let io,root,original,directory,lockPath,statePath,settlement,target,admin=null,verifier=null,lock=null,busy=false,pending=false,closed=false,cleanupContext=null,sequence=0,state;
  const identities=new Map(),directories=new Map(),retained=new Map();
  const identity=s=>({ino:s.ino,dev:s.dev});
  const stable=s=>({ino:s.ino,dev:s.dev,size:s.size,mtimeMs:s.mtimeMs,ctimeMs:s.ctimeMs});
  try{
    io={lstat:dependencies.lstat??lstat,realpath:dependencies.realpath??realpath,open:dependencies.open??open,mkdir:dependencies.mkdir??mkdir,rename:dependencies.rename??rename,unlink:dependencies.unlink??unlink,execute:dependencies.execute??promisify(execFile),now:dependencies.now??Date.now,setTimeout:dependencies.setTimeout??setTimeout,clearTimeout:dependencies.clearTimeout??clearTimeout};
    root=dependencies.root??ROOT;if(typeof root!=='string'||resolve(root)!==root)fail();original=root+'/test-results/stripe-sandbox/'+ORIGINAL_RUN;directory=root+'/test-results/stripe-sandbox/'+options.runId;lockPath=original+'/manual-refund.lock';statePath=directory+'/manual-refund.json';
    limits=Object.freeze(validateLimits(limits));if(limits.deadline>io.now()+1800000)fail();settlement=createFormDiagnosticSettlement(io);settlement.bind(limits.deadline);
    await admit(limits);
    await directoryProof(original,limits);
    const runtime=decodeRuntime(strictJSON(await readRetained(original+'/runtime.json',limits)),root,original,options),provision=decodeProvision(strictJSON(await readRetained(original+'/form-provisioning.json',limits)),runtime);
    const app=decodeEnv(await readRetained(original+'/app.env',limits),false,runtime),final=decodeEnv(await readRetained(original+'/final.env',limits),true,runtime);for(const key of [...PRIVATE,...PUBLIC])if(app[key]!==final[key])fail();
    admin=Object.freeze({username:app.PAYMENT_ADMIN_BOOTSTRAP_USERNAME,password:app.PAYMENT_ADMIN_BOOTSTRAP_PASSWORD});verifier=final[STRIPE[2]];
    target=frozen({originalRunId:ORIGINAL_RUN,originalSource:ORIGINAL_SOURCE,project:PROJECT,containers:runtime.containers,images:runtime.images,network:runtime.network,volumes:runtime.volumes,ports:runtime.ports,endpoints:runtime.endpoints,account:provision.account,site:provision.site,price:provision.price,paymentId:provision.cases.form.paymentId,operationId:provision.operationProof.operationId,intentId:provision.operationProof.reference,callbackVersion:1,amountMinor:2000,currency:'EUR',source:'CATALOG'});
    await absent(directory,limits);await absent(lockPath,limits);lock=await exclusive(lockPath,JSON.stringify({version:1,runId:options.runId,expectedSource:options.expectedSource})+'\n',limits,true);
    await call(limits,()=>io.mkdir(directory,{mode:0o700}));await directoryProof(directory,limits);await syncDirectory(dirname(directory),limits);
    state={version:1,mode:'MANUAL_REFUND',runId:options.runId,expectedSource:options.expectedSource,originalRunId:ORIGINAL_RUN,originalSource:ORIGINAL_SOURCE,deadline:limits.deadline,siteId:target.site.id,paymentId:target.paymentId,pending:false,restore:RESTORE.map(service=>({service,phase:'READY'})),rotation:{phase:'READY',version:null},configure:{phase:'READY',version:null},legs:[700,1300].map((amountMinor,index)=>({amountMinor,key:'s5a-refund:'+options.runId+':'+(index?'remainder':'partial'),phase:'READY',refundId:null,providerReference:null}))};await save(limits);
  }catch{pending=true;admin=null;verifier=null;if(lock){try{await settlement.release(lock);}catch{}lock=null;}throw fixed();}
  function validateLimits(value,cleanup=false,read=false){const selected=exact(value,Object.hasOwn(Object.getOwnPropertyDescriptors(value),'signal')?['deadline','signal']:['deadline']);if(!Number.isSafeInteger(selected.deadline)||selected.deadline<=io.now()||selected.signal!==undefined&&!(selected.signal instanceof AbortSignal)||selected.signal?.aborted)fail();if(state&&(cleanup?selected.deadline>state.deadline+120000:read?selected.deadline>state.deadline:selected.deadline!==state.deadline))fail();return selected;}
  function check(context){if(closed&&context!==cleanupContext||context.signal?.aborted||io.now()>=context.deadline)fail();}
  async function call(context,fn,dispose){check(context);return settlement.call(context,()=>{check(context);return fn();},dispose);}
  async function handle(path,flags,context,mode){return call(context,()=>io.open(path,flags,mode),file=>file.close());}
  async function release(file){await settlement.release(file);}
  function fileInfo(s){if(!s.isFile()||s.isSymbolicLink()||s.uid!==process.getuid()||(s.mode&0o7777)!==0o600||s.nlink!==1||!Number.isSafeInteger(s.size)||s.size>65536)fail();}
  async function directoryProof(path,context){
    const paths=[];for(let current=path;;current=dirname(current)){paths.unshift(current);if(current===parsePath(current).root)break;}
    for(const current of paths){const info=await call(context,()=>io.lstat(current));if(!info.isDirectory()||info.isSymbolicLink()||![0,process.getuid()].includes(info.uid)||await call(context,()=>io.realpath(current))!==current)fail();if(current===root||current.startsWith(root+'/')){if(info.uid!==process.getuid()||current!==root&&(info.mode&0o7777)!==0o700)fail();const prior=directories.get(current);if(prior&&!same(identity(info),prior))fail();directories.set(current,identity(info));}}
  }
  async function fileProof(path,file,context,expected=identities.get(path)){await directoryProof(dirname(path),context);const held=await call(context,()=>file.stat()),named=await call(context,()=>io.lstat(path));fileInfo(held);fileInfo(named);if(!same(stable(held),stable(named))||expected&&!same(identity(held),expected)||await call(context,()=>io.realpath(path))!==path)fail();return held;}
  async function readRetained(path,context){const before=await call(context,()=>io.lstat(path));fileInfo(before);const file=await handle(path,constants.O_RDONLY|constants.O_NOFOLLOW,context);try{const held=await fileProof(path,file,context,identity(before));const bytes=Buffer.alloc(held.size+1);const read=await call(context,()=>file.read(bytes,0,bytes.length,0));if(read.bytesRead!==held.size)fail();const after=await fileProof(path,file,context,identity(held));if(!same(stable(held),stable(after)))fail();retained.set(path,stable(after));return new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes.subarray(0,read.bytesRead));}finally{await release(file);}}
  async function retainedProof(context){for(const [path,prior] of retained){const current=await call(context,()=>io.lstat(path));fileInfo(current);if(!same(stable(current),prior)||await call(context,()=>io.realpath(path))!==path)fail();}}
  async function absent(path,context){await call(context,async()=>{try{await io.lstat(path);}catch(error){if(error?.code==='ENOENT')return;throw error;}fail();});}
  async function syncDirectory(path,context){await directoryProof(path,context);const file=await handle(path,constants.O_RDONLY|constants.O_NOFOLLOW|constants.O_DIRECTORY,context);try{const held=await call(context,()=>file.stat());if(!held.isDirectory()||!same(identity(held),directories.get(path)))fail();await call(context,()=>file.sync());await directoryProof(path,context);}finally{await release(file);}}
  async function exclusive(path,text,context,hold=false){await directoryProof(dirname(path),context);const file=await handle(path,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,context,0o600);let retainedHandle=false;try{const before=await fileProof(path,file,context);await call(context,()=>file.writeFile(text));await call(context,()=>file.sync());await fileProof(path,file,context,identity(before));identities.set(path,identity(before));await syncDirectory(dirname(path),context);if(hold){retainedHandle=true;return file;}}finally{if(!retainedHandle)await release(file);}}
  async function ownedState(context){await fileProof(lockPath,lock,context);if(identities.has(statePath)){const file=await handle(statePath,constants.O_RDONLY|constants.O_NOFOLLOW,context);try{await fileProof(statePath,file,context);}finally{await release(file);}}else await absent(statePath,context);}
  async function save(context){await retainedProof(context);await ownedState(context);const temporary=directory+'/.manual-refund-'+(++sequence)+'.tmp',text=JSON.stringify(state)+'\n';if(Buffer.byteLength(text)>65536)fail();await exclusive(temporary,text,context);await ownedState(context);const file=await handle(temporary,constants.O_RDONLY|constants.O_NOFOLLOW,context);try{await fileProof(temporary,file,context);}finally{await release(file);}await call(context,()=>io.rename(temporary,statePath));identities.set(statePath,identities.get(temporary));identities.delete(temporary);await syncDirectory(directory,context);}
  async function admit(context){
    const settings={cwd:root,env:{PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',GIT_OPTIONAL_LOCKS:'0',GIT_CONFIG_GLOBAL:'/dev/null',GIT_CONFIG_SYSTEM:'/dev/null'},shell:false,timeout:Math.min(10000,context.deadline-io.now()),maxBuffer:65536,signal:context.signal};
    async function git(args){const output=await call(context,()=>io.execute('/usr/bin/git',args,settings));if(typeof output?.stdout!=='string'||typeof output.stderr!=='string'||Buffer.byteLength(output.stdout)+Buffer.byteLength(output.stderr)>65536)fail();return output.stdout;}
    if((await git(['rev-parse','HEAD'])).trim()!==options.expectedSource||await git(['-c','core.fsmonitor=false','status','--porcelain=v1','--untracked-files=no'])!=='')fail();await git(['merge-base','--is-ancestor',ORIGINAL_SOURCE,options.expectedSource]);
    const path=new URL(options.daemon).pathname;await directoryProof(dirname(path),context);const socket=await call(context,()=>io.lstat(path));if(!socket.isSocket()||socket.isSymbolicLink()||socket.uid!==process.getuid()||await call(context,()=>io.realpath(path))!==path)fail();
  }
  async function operation(context,body,{cleanup=false,read=false}={}){
    if(busy){pending=true;throw fixed();}busy=true;
    try{context=Object.freeze(validateLimits(context,cleanup,read));if(closed||pending&&!cleanup)fail();if(!cleanup){await directoryProof(original,context);await retainedProof(context);await ownedState(context);}const result=await settlement.wait(context,()=>body(context));check(context);if(pending&&!cleanup)fail();return result;}
    catch{pending=true;if(state)state.pending=true;throw fixed();}finally{busy=false;}
  }
  const transition=(context,body)=>operation(context,async active=>{body();await save(active);return true;});
  function phase(record,expected){if(record.phase!==expected)fail();}
  function leg(index){if(index!==0&&index!==1)fail();if(index===1&&state.legs[0].phase!=='CONFIRMED')fail();return state.legs[index];}
  async function disposeTerminal(){
    // Rejected caller Limits cannot renew disposal or suppress it while original authority remains.
    const disposal=Object.freeze({deadline:state.deadline+120000});if(io.now()>=disposal.deadline)return false;
    const held=lock;lock=null;const releasing=held?settlement.release(held):Promise.resolve();releasing.catch(()=>{});
    try{settlement.close(disposal);await settlement.wait(disposal,()=>releasing);return settlement.close(disposal);}catch{return false;}
  }
  async function terminalClose(value,context){
    const overlapping=busy,repeated=closed;closed=true;admin=null;verifier=null;
    let failed=false,ownsBusy=false;
    try{
      if(overlapping||repeated)fail();const active=Object.freeze(validateLimits(context,true));cleanupContext=active;busy=true;ownsBusy=true;
      const selected=exact(value,['retain']);if(typeof selected.retain!=='boolean')fail();await ownedState(active);
      const uncertain=pending||state.restore.some(v=>v.phase==='DISPATCHING')||state.rotation.phase==='DISPATCHING'||state.configure.phase==='DISPATCHING'||state.legs.some(v=>['DISPATCHING','ACKNOWLEDGED'].includes(v.phase))||state.rotation.phase!=='READY'&&state.legs.some(v=>v.phase!=='CONFIRMED');
      await fileProof(lockPath,lock,active);await release(lock);lock=null;
      if(!selected.retain&&!uncertain&&!pending){const named=await call(active,()=>io.lstat(lockPath));fileInfo(named);if(!same(identity(named),identities.get(lockPath)))fail();await call(active,()=>{if(pending)fail();return io.unlink(lockPath);});await syncDirectory(original,active);}
    }catch{failed=true;pending=true;state.pending=true;}
    finally{if(!await disposeTerminal()){failed=true;pending=true;state.pending=true;}if(ownsBusy)busy=false;}
    if(failed)throw fixed();return true;
  }
  return Object.freeze({target,
    beginRestore:(service,context)=>transition(context,()=>{const item=state.restore.find(v=>v.service===service);if(!item||state.restore.slice(0,state.restore.indexOf(item)).some(v=>v.phase!=='ACKNOWLEDGED'))fail();phase(item,'READY');item.phase='DISPATCHING';}),
    ackRestore:(service,context)=>transition(context,()=>{const item=state.restore.find(v=>v.service===service);if(!item)fail();phase(item,'DISPATCHING');item.phase='ACKNOWLEDGED';}),
    beginRotation:context=>transition(context,()=>{if(state.restore.some(v=>v.phase!=='ACKNOWLEDGED'))fail();phase(state.rotation,'READY');state.rotation.phase='DISPATCHING';}),
    ackRotation:(version,context)=>transition(context,()=>{if(version!==2)fail();phase(state.rotation,'DISPATCHING');state.rotation={phase:'ACKNOWLEDGED',version:2};}),
    beginConfigure:(version,context)=>transition(context,()=>{if(version!==2)fail();phase(state.rotation,'ACKNOWLEDGED');phase(state.configure,'READY');state.configure={phase:'DISPATCHING',version:2};}),
    ackConfigure:(version,context)=>transition(context,()=>{if(version!==2)fail();phase(state.configure,'DISPATCHING');state.configure={phase:'ACKNOWLEDGED',version:2};}),
    beginRefund:(index,context)=>operation(context,async active=>{phase(state.configure,'ACKNOWLEDGED');const item=leg(index);phase(item,'READY');item.phase='DISPATCHING';await save(active);return Object.freeze({...item});}),
    ackRefund:(index,value,context)=>transition(context,()=>{const item=leg(index),ack=exact(value,['refundId']);phase(item,'DISPATCHING');if(!uuid(ack.refundId)||state.legs.some(v=>v!==item&&v.refundId===ack.refundId))fail();item.phase='ACKNOWLEDGED';item.refundId=ack.refundId;}),
    confirmRefund:(index,value,context)=>transition(context,()=>{const item=leg(index);phase(item,'ACKNOWLEDGED');const p=exact(value,['refundId','providerReference','providerEventId','amountMinor','totalMinor','providerVerified','webhookVerified','journalVerified','callbackVerified']);if(p.refundId!==item.refundId||!provider(p.providerReference,'re_')||!provider(p.providerEventId,'evt_')||p.amountMinor!==item.amountMinor||p.totalMinor!==(index?2000:700)||['providerVerified','webhookVerified','journalVerified','callbackVerified'].some(k=>p[k]!==true)||state.legs.some(v=>v!==item&&v.providerReference===p.providerReference))fail();item.phase='CONFIRMED';item.providerReference=p.providerReference;item.proof=p;}),
    withRetainedAdmin:(callback,context)=>operation(context,async active=>{if(typeof callback!=='function'||!admin||await call(active,()=>callback(admin))!==true)fail();return true;},{read:true}),
    verifyRetainedWebhookSecret:(candidate,context)=>operation(context,async()=>{if(typeof candidate!=='string'||!/^whsec_[A-Za-z0-9_]{16,256}$/.test(candidate)||!verifier)fail();const a=Buffer.from(candidate),b=Buffer.from(verifier);if(a.length!==b.length||!timingSafeEqual(a,b))fail();return true;},{read:true}),
    close:terminalClose,
  });
}
