import {execFile} from 'node:child_process';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createOwnedSandboxBootstrap} from './stripe-sandbox-bootstrap.mjs';
import {createSandboxCliAdapter} from './stripe-sandbox-cli.mjs';
import {createOwnedSandboxProvisioner} from './stripe-sandbox-provision.mjs';
import {createSandboxAcceptanceExtras} from './stripe-sandbox-extras.mjs';
import {openStripeSandboxFixture,runStripeSandboxSmoke} from './smoke-stage-5a.mjs';

const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const PORT_NAMES=['backend','demo','receiver','postgres','mailpitHttp','mailpitSmtp'];
const BINDING=Object.freeze({externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'});
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const EXHAUSTED=new Set(['2f3f49b1-df07-41b1-b978-bc884b390813','6f684b69-32e6-429f-a34c-e4f69ba2ce18']);
const POLICY='(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))';
const fail=()=>{throw null;};
const fixed=code=>new Error('stripe_sandbox_acceptance_'+code);
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const exact=(value,keys)=>object(value)&&Object.keys(value).sort().join(',')===[...keys].sort().join(',');
function freeze(value){if(value&&typeof value==='object'){for(const child of Object.values(value))freeze(child);Object.freeze(value);}return value;}
const snapshot=value=>freeze(JSON.parse(JSON.stringify(value)));

/** Pure public human handoff. No inferred device pathname or executable next_step. */
export function formatSandboxAuthorization(value) {
  try {
    if(!exact(value,['tag','browser_url','verification_code'])||value.tag!=='stripe_sandbox_authorization')return null;
    const url=value.browser_url,code=value.verification_code;
    if(typeof url!=='string'||url.length>2048||/[\s\\\x00-\x1f\x7f-\x9f]/.test(url)
      ||/%(?:0[0-9a-f]|1[0-9a-f]|7f)/i.test(url))return null;
    const decoded=decodeURIComponent(url);
    if(/[\x00-\x1f\x7f-\x9f]/.test(decoded)||/(?:[spr]k_(?:test|live)_|whsec_|client_secret)/i.test(decoded))return null;
    const parsed=new URL(url);
    if(parsed.protocol!=='https:'||!['access.stripe.com','dashboard.stripe.com'].includes(parsed.hostname)
      ||parsed.port||parsed.username||parsed.password||parsed.hash)return null;
    if(typeof code!=='string'||code.length<4||code.length>128||!code.match(/^[A-Za-z0-9](?:[A-Za-z0-9 -]*[A-Za-z0-9])?$/)
      ||/(?:[spr]k_(?:test|live)|whsec|client.secret)/i.test(code))return null;
    return `Authorize the isolated Sandbox CLI at ${url} using code ${code}. Keep this terminal open.\n`;
  }catch{return null;}
}

/** Flat public failure observation. Accessors and unknown fields are never consumed. */
export function formatSandboxDiagnostic(value) {
  try {
    if(!object(value))return null;
    const keys=['tag','phase','code','exitCode','cleanupConfirmed'];
    if(Reflect.ownKeys(value).length!==keys.length)return null;
    const descriptors=Object.getOwnPropertyDescriptors(value);
    if(keys.some(key=>!Object.hasOwn(descriptors,key)||!Object.hasOwn(descriptors[key],'value')))return null;
    const {tag,phase,code,exitCode,cleanupConfirmed}=Object.fromEntries(keys.map(key=>[key,descriptors[key].value]));
    if(tag!=='stripe_sandbox_diagnostic'||!['PREPARE','LOGIN','LOGIN_COMPLETE','BINDING','LISTENER','START','PROVISION','READY','CARDS','REFUNDS','DONATIONS','MISSED','CONFIRMED','CLEANUP'].includes(phase)
      ||!['OPERATION_FAILED','PROCESS_EXIT_NONZERO','PROCESS_SIGNALLED','PROCESS_FAILED','ABORTED','DEADLINE_REACHED'].includes(code)
      ||typeof cleanupConfirmed!=='boolean')return null;
    if(code==='PROCESS_EXIT_NONZERO'? !Number.isInteger(exitCode)||exitCode<1||exitCode>255 : exitCode!==null)return null;
    return `sandbox_diagnostic phase=${phase} code=${code} exit=${exitCode===null?'none':exitCode} cleanup=${cleanupConfirmed?'confirmed':'unconfirmed'}\n`;
  }catch{return null;}
}

/** One owned run. Factories are reviewed capability boundaries, never command plugins. */
export async function openOwnedSandboxAcceptance(options,limits,dependencies={}) {
  let enabled=false;
  try {const env=options?.env;enabled=env?.PAYMENT_STRIPE_SANDBOX_ENABLED==='true'&&env?.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS==='true'&&env?.PAYMENT_STRIPE_SANDBOX_OWNED_RUN==='true';}catch{}
  if(!enabled)throw fixed('OPT_IN_REQUIRED');
  let configuration,io,originalDeadline,originalSignal;
  try {
    const flags=options.env;
    if(![undefined,''].includes(flags.DEBUG)||![undefined,''].includes(flags.NODE_DEBUG)||![undefined,'','0'].includes(flags.PWDEBUG))fail();
    const {runId,expectedSource,daemon}=options;
    if(typeof runId!=='string'||!UUID.test(runId)||EXHAUSTED.has(runId)||typeof expectedSource!=='string'||!/^[0-9a-f]{40}$/.test(expectedSource)
      ||typeof daemon!=='string'||/[\s\x00-\x1f\x7f%]/.test(daemon))fail();
    const url=new URL(daemon);
    if(url.protocol!=='unix:'||url.host||url.username||url.password||url.search||url.hash||url.pathname.length<2||url.href!==daemon||daemon!=='unix://'+url.pathname)fail();
    if(!exact(options.ports,PORT_NAMES))fail();
    const ports=Object.fromEntries(PORT_NAMES.map(name=>[name,options.ports[name]]));
    if(Object.values(ports).some(port=>!Number.isInteger(port)||port<1024||port>65535)||new Set(Object.values(ports)).size!==6)fail();
    // Public configuration admission precedes dependencies, private setup and browser fixtures.
    originalDeadline=limits?.deadline;originalSignal=limits?.signal;
    if(!Number.isSafeInteger(originalDeadline)||originalDeadline<=Date.now()||originalDeadline>Date.now()+2700000
      ||(originalSignal!==undefined&&!(originalSignal instanceof AbortSignal))||originalSignal?.aborted)fail();
    configuration=freeze({runId,expectedSource,daemon,ports,approvedBinding:BINDING,env:{
      PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true',
      DEBUG:flags.DEBUG,NODE_DEBUG:flags.NODE_DEBUG,PWDEBUG:flags.PWDEBUG}});
    io=Object.freeze({createOwnedSandboxBootstrap:dependencies.createOwnedSandboxBootstrap??createOwnedSandboxBootstrap,
      createSandboxCliAdapter:dependencies.createSandboxCliAdapter??createSandboxCliAdapter,
      createOwnedSandboxProvisioner:dependencies.createOwnedSandboxProvisioner??createOwnedSandboxProvisioner,
      createSandboxAcceptanceExtras:dependencies.createSandboxAcceptanceExtras??createSandboxAcceptanceExtras,
      openStripeSandboxFixture:dependencies.openStripeSandboxFixture??openStripeSandboxFixture,
      runStripeSandboxSmoke:dependencies.runStripeSandboxSmoke??runStripeSandboxSmoke,execFile:dependencies.execFile??execFile,
      fetch:dependencies.fetch??globalThis.fetch,now:dependencies.now??Date.now,setTimeout:dependencies.setTimeout??setTimeout,clearTimeout:dependencies.clearTimeout??clearTimeout});
  }catch{throw fixed('CONFIGURATION');}

  const lifetime=new AbortController(),abortLifetime=()=>lifetime.abort();
  originalSignal?.addEventListener('abort',abortLifetime,{once:true});
  const lifetimeTimer=io.setTimeout(abortLifetime,Math.max(1,originalDeadline-io.now()));
  const setup={deadline:originalDeadline,signal:lifetime.signal};
  let busy=true,phase='PREPARE',failure=null,failureDiagnostic=null,cleanupDiagnostic=null,diagnosticEmitted=false,acceptedProof=false,cleanupConfirmed=false,closed=false,processUncertain=false,browserUncertain=false;
  // Only tokens created inside this owner carry process provenance; dependency errors are opaque.
  const diagnostics=new WeakMap();
  function observedFailure(code,exitCode=null){const token=Object.freeze(Object.create(null));diagnostics.set(token,{code,exitCode});return token;}
  let bootstrap=null,cli=null,provisioner=null,extras=null,cards=null,missed=null,catalogEnv=null,publicBinding=null,activeContext=setup,activeJourney=null;
  const cleanup={cards:false,missed:false,extras:false,listener:false,services:false};
  const proof={cards:false,refunds:false,anonymous:false,emailOtp:false,otp:false,missed:false};
  function contextFailure(ctx){
    if(io.now()>=ctx.deadline)return observedFailure('DEADLINE_REACHED');
    if(ctx.signal.aborted)return observedFailure('ABORTED');return null;
  }
  function check(ctx){const error=contextFailure(ctx);if(error)throw error;}
  const evidence=(error,ctx)=>diagnostics.get(error)??diagnostics.get(ctx?contextFailure(ctx):null)??{code:'OPERATION_FAILED',exitCode:null};
  function recordFailure(error,ctx=setup){if(failure===null){failure=phase;failureDiagnostic=evidence(error,ctx);}}
  function emitDiagnostic(){
    if(diagnosticEmitted)return;diagnosticEmitted=true;
    const envelope={tag:'stripe_sandbox_diagnostic',phase:failure??'CLEANUP',...(failureDiagnostic??cleanupDiagnostic??{code:'OPERATION_FAILED',exitCode:null}),cleanupConfirmed};
    try{process.stdout.write(JSON.stringify(envelope)+'\n');}catch{}
  }
  function resources(){return snapshot({runId:configuration.runId,phase,failure,proof,accepted:acceptedProof&&cleanupConfirmed,cleanupConfirmed,cleanup,
    ...(publicBinding??{})});}
  function context(limits,cleanupOnly=false) {
    const deadline=limits?.deadline,signal=limits?.signal;
    if(!Number.isSafeInteger(deadline)||deadline<=io.now()||(signal!==undefined&&!(signal instanceof AbortSignal)))fail();
    const controller=new AbortController(),sources=[signal,...(cleanupOnly?[]:[lifetime.signal])].filter(Boolean),abort=()=>controller.abort();
    for(const source of sources){source.addEventListener('abort',abort,{once:true});if(source.aborted)abort();}
    const end=cleanupOnly?Math.min(deadline,io.now()+120000):Math.min(deadline,originalDeadline);
    const timer=io.setTimeout(abort,Math.max(1,end-io.now()));
    return {deadline:end,signal:controller.signal,dispose(){io.clearTimeout(timer);for(const source of sources)source.removeEventListener('abort',abort);}};
  }
  function controlled(ctx,body) {
    check(ctx);
    return new Promise((resolveWait,rejectWait)=>{
      const abort=()=>rejectWait(contextFailure(ctx));ctx.signal.addEventListener('abort',abort,{once:true});
      Promise.resolve().then(()=>{check(ctx);return body();}).then(value=>{
        ctx.signal.removeEventListener('abort',abort);try{check(ctx);resolveWait(value);}catch(error){rejectWait(error);}
      },error=>{ctx.signal.removeEventListener('abort',abort);rejectWait(diagnostics.has(error)?error:contextFailure(ctx));});
      if(ctx.signal.aborted)abort();
    });
  }
  async function requireTrue(ctx,body){if(await controlled(ctx,body)!==true)fail();}
  async function journey(ctx,body){
    activeJourney=Promise.resolve().then(body);activeJourney.catch(()=>{});
    try{if(await controlled(ctx,()=>activeJourney)!==true)fail();}
    catch(error){browserUncertain=true;throw error;}
    finally{if(!ctx.signal.aborted)activeJourney=null;}
  }
  const ownership=ctx=>requireTrue(ctx,()=>bootstrap.verify({deadline:ctx.deadline,signal:ctx.signal}));

  function validateDescriptor(value,complete) {
    const directory=ROOT+'/test-results/stripe-sandbox/'+configuration.runId+'/cli';
    const expectedArgs=['-p',POLICY,'/opt/homebrew/bin/stripe','--config',directory+'/config/stripe/config.toml',
      '--project-name','default','--color','off','--log-level','error','login',...(complete?['--complete-device']:[])];
    const expectedEnv={PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:directory+'/home',XDG_CONFIG_HOME:directory+'/config',NO_COLOR:'1'};
    if(!exact(value,['file','args','options'])||!Object.isFrozen(value)||!Object.isFrozen(value.args)||!Object.isFrozen(value.options)||!Object.isFrozen(value.options.env)
      ||value.file!=='/usr/bin/sandbox-exec'||JSON.stringify(value.args)!==JSON.stringify(expectedArgs)
      ||!exact(value.options,['cwd','env','shell','stdio','timeout','maxBuffer'])||value.options.cwd!==ROOT||value.options.shell!==false
      ||JSON.stringify(value.options.stdio)!==JSON.stringify(['pipe','pipe','pipe'])||value.options.timeout!==180000||value.options.maxBuffer!==65536
      ||!exact(value.options.env,Object.keys(expectedEnv))||Object.entries(expectedEnv).some(([key,v])=>value.options.env[key]!==v))fail();
    return value;
  }
  async function executeDescriptor(value,ctx) {
    check(ctx);
    // Unlike an abort race, callback completion alone never means the process has exited.
    const result=await new Promise((resolveExecution,rejectExecution)=>{
      let callbackDone=false,closedProcess=false,failed=false,stdout='',stderr='',grace,closeCode,closeSignal;
      const processFailure=()=>Number.isInteger(closeCode)&&closeCode>=1&&closeCode<=255
        ?observedFailure('PROCESS_EXIT_NONZERO',closeCode)
        :contextFailure(ctx)??observedFailure(typeof closeSignal==='string'&&closeSignal!==''?'PROCESS_SIGNALLED':'PROCESS_FAILED');
      const finish=()=>{if(callbackDone&&closedProcess){io.clearTimeout(grace);ctx.signal.removeEventListener('abort',onAbort);
        if(failed)rejectExecution(processFailure());else resolveExecution({stdout,stderr});}};
      const onAbort=()=>{failed=true;grace=io.setTimeout(()=>{processUncertain=true;rejectExecution(processFailure());},10000);};
      ctx.signal.addEventListener('abort',onAbort,{once:true});
      try {
        const child=io.execFile(value.file,value.args,{...value.options,signal:ctx.signal,timeout:Math.max(1,Math.min(180000,ctx.deadline-io.now()))},(error,out,err)=>{
          failed=failed||(error!==null&&error!==undefined);stdout=out;stderr=err;callbackDone=true;finish();
        });
        child.once('close',(code,signal)=>{closedProcess=true;closeCode=code;closeSignal=signal;failed=failed||code!==0||(typeof signal==='string'&&signal!=='');finish();});
        child.once('error',()=>{failed=true;});
        if(ctx.signal.aborted)onAbort();
      }catch{ctx.signal.removeEventListener('abort',onAbort);rejectExecution(processFailure());}
    });
    check(ctx);
    if(typeof result.stdout!=='string'||typeof result.stderr!=='string'||Buffer.byteLength(result.stdout)+Buffer.byteLength(result.stderr)>65536)fail();
    return result;
  }
  function authorization(result) {
    if(result.stderr!=='')fail();
    // Purpose-specific flat three-string initiation, checking decoded names before assignment.
    const raw=result.stdout,names=['browser_url','verification_code','next_step'],value=Object.create(null);
    const quoted=/"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/y;let cursor=0;
    const whitespace=()=>{while(/[ \t\r\n]/.test(raw[cursor]??'!'))cursor++;};
    const punctuation=char=>{whitespace();if(raw[cursor++]!==char)fail();};
    const string=()=>{whitespace();quoted.lastIndex=cursor;const match=quoted.exec(raw);if(!match)fail();cursor=quoted.lastIndex;return JSON.parse(match[0]);};
    punctuation('{');
    for(let index=0;index<3;index++){
      if(index>0)punctuation(',');const name=string();
      if(!names.includes(name)||Object.hasOwn(value,name))fail();punctuation(':');value[name]=string();
    }
    punctuation('}');whitespace();if(cursor!==raw.length||value.next_step!=='stripe login --complete-device')fail();
    const envelope=Object.freeze({tag:'stripe_sandbox_authorization',browser_url:value.browser_url,verification_code:value.verification_code});
    if(formatSandboxAuthorization(envelope)===null)fail();return envelope;
  }

  async function gatedFetch(input,settings={}) {
    const ctx=context({deadline:Math.min(activeContext.deadline,io.now()+10000),signal:activeContext.signal});
    const external=settings.signal;
    // Use a linked request controller: signals from the old runner remain authoritative too.
    const controller=new AbortController(),cancel=()=>controller.abort();
    ctx.signal.addEventListener('abort',cancel,{once:true});external?.addEventListener('abort',cancel,{once:true});
    if(ctx.signal.aborted||external?.aborted)cancel();
    const requestContext={deadline:ctx.deadline,signal:controller.signal};let response;
    try {
      const url=new URL(input),backend=publicBinding.endpoints.backend,receiver=publicBinding.endpoints.receiver;
      const path=url.pathname,method=settings.method??'GET';
      const adminRead=['/api/admin/v1/payment-accounts','/api/admin/v1/sites'].includes(path)||/^\/api\/admin\/v1\/sites\/[0-9a-f-]{36}\/catalog$/.test(path)
        ||/^\/api\/v1\/admin\/payments\/[0-9a-f-]{36}$/.test(path)||path==='/api/v1/admin/provider-operations';
      const allowed=url.origin===backend&&((method==='GET'&&(adminRead||/^\/api\/v1\/public\/checkouts\/[0-9a-f-]{36}$/.test(path)))
        ||(method==='POST'&&(path==='/api/admin/v1/auth/sessions'||/^\/api\/v1\/admin\/payments\/[0-9a-f-]{36}\/refunds$/.test(path))))
        ||url.origin===receiver&&method==='GET'&&path==='/inspection';
      if(!allowed||url.username||url.password||url.hash||settings.redirect!=='error')fail();
      if(url.origin!==backend&&new Headers(settings.headers).has('cookie'))fail();
      await ownership(requestContext);
      response=await controlled(requestContext,()=>io.fetch(url.href,{...settings,redirect:'error',signal:controller.signal}));
      if(response.redirected||response.status>=300&&response.status<400)fail();
      const chunks=[];let size=0;
      await controlled(requestContext,async()=>{for await(const chunk of response.body??[]){check(requestContext);size+=chunk.length;if(size>65536)fail();chunks.push(chunk);}});
      check(requestContext);
      // Preserve genuine Set-Cookie headers, and the required null-body status semantics.
      return new Response([204,205,304].includes(response.status)?null:Buffer.concat(chunks),{status:response.status,statusText:response.statusText,headers:response.headers});
    }catch{try{response?.body?.cancel()?.catch(()=>{});}catch{}throw fixed('HTTP_PENDING');}
    finally{controller.abort();ctx.dispose();ctx.signal.removeEventListener('abort',cancel);external?.removeEventListener('abort',cancel);}
  }
  const fixtureOptions=()=>Object.freeze({env:catalogEnv,verifyOwnership:async()=>{await ownership(activeContext);return true;},fetch:gatedFetch,
    pollAttempts:30,pollDelayMs:250,requestTimeoutMs:10000});

  async function cleanupOwned(limits) {
    let ctx;
    try {ctx=context(limits,true);}catch(error){cleanupDiagnostic??=evidence(error);cleanupConfirmed=false;return false;}
    let confirmed=!processUncertain&&!browserUncertain;
    if(activeJourney){
      // Settlement only orders Node cleanup. It never clears uncertain browser ownership.
      try{await controlled(ctx,()=>activeJourney.then(()=>true,()=>true));}catch(error){cleanupDiagnostic??=evidence(error,ctx);confirmed=false;}
      activeJourney=null;
    }
    for(const [name,capability,method,withLimits] of [['cards',cards,'close',false],['missed',missed,'close',false],['extras',extras,'close',true],
      ['listener',cli,'stopListener',true],['services',bootstrap,'stop',true]]) {
      if(cleanup[name])continue;
      if(!capability){cleanup[name]=true;continue;}
      try {const value=await controlled(ctx,()=>withLimits?capability[method]({deadline:ctx.deadline,signal:ctx.signal}):capability[method]());
        if(withLimits&&value!==true)fail();cleanup[name]=true;
      }catch(error){cleanupDiagnostic??=evidence(error,ctx);confirmed=false;}
    }
    ctx.dispose();cleanupConfirmed=confirmed&&!browserUncertain&&Object.values(cleanup).every(Boolean);closed=true;catalogEnv=null;
    io.clearTimeout(lifetimeTimer);originalSignal?.removeEventListener('abort',abortLifetime);
    return cleanupConfirmed;
  }
  async function setupOwned() {
    bootstrap=await controlled(setup,()=>io.createOwnedSandboxBootstrap(configuration));
    await requireTrue(setup,()=>bootstrap.prepare(setup));
    const safe=bootstrap.resources(),directory=ROOT+'/test-results/stripe-sandbox/'+configuration.runId+'/cli';
    if(safe.runId!==configuration.runId||safe.status!=='PREPARED'||safe.cliDirectory!==directory||safe.ports?.backend!==configuration.ports.backend)fail();
    cli=await controlled(setup,()=>io.createSandboxCliAdapter(Object.freeze({env:configuration.env,runId:configuration.runId,cliDirectory:directory,
      backendPort:configuration.ports.backend,accountKey:'stripe-sandbox-default',approvedBinding:BINDING,
      persistWebhookSecret:bootstrap.persistWebhookSecret,verifyForwardTarget:bootstrap.verifyForwardTarget})));
    phase='LOGIN';const initial=validateDescriptor(await controlled(setup,()=>cli.loginInvocation(setup)),false);
    const envelope=authorization(await executeDescriptor(initial,setup));
    process.stdout.write(JSON.stringify(envelope)+'\n');
    phase='LOGIN_COMPLETE';const complete=validateDescriptor(await controlled(setup,()=>cli.completeLoginInvocation(setup)),true);
    await executeDescriptor(complete,setup);
    phase='BINDING';await requireTrue(setup,()=>cli.validateBinding(setup));
    phase='LISTENER';await requireTrue(setup,()=>cli.startListener(setup));
    phase='START';await requireTrue(setup,()=>bootstrap.start(setup));await ownership(setup);
    phase='PROVISION';provisioner=await controlled(setup,()=>io.createOwnedSandboxProvisioner(Object.freeze({env:configuration.env,bootstrap,verifyProviderBinding:cli.validateBinding})));
    await requireTrue(setup,()=>provisioner.provision(setup));
    const provisioned=provisioner.resources();if(provisioned.status!=='READY'||provisioned.runId!==configuration.runId)fail();
    publicBinding=snapshot({endpoints:provisioned.endpoints,site:{id:provisioned.site.id,key:provisioned.site.key},
      campaign:{id:provisioned.campaign.id,key:provisioned.campaign.key,status:provisioned.campaign.status,version:provisioned.campaign.version},
      price:{id:provisioned.price.id,key:provisioned.price.key,version:provisioned.price.version}});
    let handoffs=0;
    if(provisioner.withCatalogEnvironment(value=>{if(++handoffs!==1||!Object.isFrozen(value))fail();catalogEnv=Object.freeze({...value});return true;})!==true||handoffs!==1)fail();
    phase='READY';
  }
  try {await setupOwned();}catch(error){recordFailure(error);await cleanupOwned({deadline:io.now()+120000});emitDiagnostic();throw fixed(failure+'_PENDING'+(cleanupConfirmed?'':'_CLEANUP_UNCONFIRMED'));}
  finally{busy=false;}
  return Object.freeze({resources,
    run:async(browserJourneys,limits)=>{
      if(busy)throw fixed('BUSY');busy=true;let ctx;
      try {
        if(closed||failure||phase!=='READY'||!exact(browserJourneys,['cards','donations','missed'])||Object.values(browserJourneys).some(fn=>typeof fn!=='function'||fn.constructor.name!=='AsyncFunction'))fail();
        ctx=context(limits);activeContext=ctx;check(ctx);phase='CARDS';await ownership(ctx);
        cards=await controlled(ctx,()=>io.openStripeSandboxFixture(fixtureOptions()));
        await journey(ctx,()=>browserJourneys.cards(cards));proof.cards=true;
        await controlled(ctx,()=>cards.close());cleanup.cards=true;cards=null;
        phase='REFUNDS';const refunds=await controlled(ctx,()=>io.runStripeSandboxSmoke(fixtureOptions()));
        if(refunds?.paymentConfirmed!==true||refunds.refundConfirmed!==true||refunds.callbackVerified!==true)fail();proof.refunds=true;
        phase='DONATIONS';await ownership(ctx);
        extras=await controlled(ctx,()=>io.createSandboxAcceptanceExtras(Object.freeze({env:configuration.env,bootstrap,provisioner,cli})));
        await requireTrue(ctx,()=>extras.open({deadline:ctx.deadline,signal:ctx.signal}));
        await journey(ctx,()=>browserJourneys.donations(extras));
        const donations=extras.resources();
        if(donations.donations?.anonymous?.confirmed!==true||donations.donations?.emailOtp?.confirmed!==true||donations.otp?.phase!=='VERIFIED')fail();
        proof.anonymous=true;proof.emailOtp=true;proof.otp=true;
        phase='MISSED';await ownership(ctx);missed=await controlled(ctx,()=>extras.missedFixture({deadline:ctx.deadline,signal:ctx.signal}));
        await journey(ctx,()=>browserJourneys.missed(missed));
        if(extras.resources().missed?.confirmed!==true)fail();proof.missed=true;
        await controlled(ctx,()=>missed.close());cleanup.missed=true;missed=null;acceptedProof=true;phase='CONFIRMED';
      }catch(error){recordFailure(error,ctx??setup);}
      finally{ctx?.dispose();await cleanupOwned({deadline:io.now()+120000});busy=false;}
      if(failure){emitDiagnostic();throw fixed(failure+'_PENDING'+(cleanupConfirmed?'':'_CLEANUP_UNCONFIRMED'));}
      if(!cleanupConfirmed){emitDiagnostic();throw fixed('CLEANUP_UNCONFIRMED');}return true;
    },
    close:async limits=>{
      if(busy)throw fixed('BUSY');busy=true;
      try{if(closed){if(!cleanupConfirmed)throw fixed('CLEANUP_UNCONFIRMED');return true;}
        if(!await cleanupOwned(limits)){emitDiagnostic();throw fixed('CLEANUP_UNCONFIRMED');}return true;
      }finally{busy=false;}
    },
  });
}
