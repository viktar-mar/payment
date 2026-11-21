import {fileURLToPath,pathToFileURL} from 'node:url';
import {providerNetworkOrigin,providerOrigin} from '../tests/support/stripe-provider-origin.ts';

const localOrigin='http://127.0.0.1:4173';
const documentPath='/stripe-browser-load-check.html';
const safeResults=new Set(['READY','REFUSED','BUILD','LAUNCH','CONTEXT','PAGE','ELEMENT','TIMEOUT','NOT_CONFIRMED','HEADER','AUTH','REDIRECT','API_PATH','ORIGIN_STRIPE_COM_M','ORIGIN_STRIPE_COM_OTHER','ORIGIN','NAVIGATION','METHOD','LOCAL','POPUP','DOWNLOAD','WEBSOCKET']);
const cleanupCodes=['CONTEXT_CLOSE','BROWSER_CLOSE','BUILD_DISPOSE','RESPONSE_DISPOSE','LATE_BUILD_DISPOSE','LATE_BROWSER_CLOSE','LATE_CONTEXT_CLOSE','LATE_PAGE_CLOSE','EXTRA_PAGE_CLOSE','DOWNLOAD_CANCEL','UNKNOWN'];
const originReasons=new Set(['URL_INVALID','NON_HTTPS_SCHEME','NON_DEFAULT_PORT','HOST_NOT_ALLOWED']);
const safeOriginReason=value=>originReasons.has(value)?value:'UNKNOWN';
const originHostGroups=new Set(['STRIPE_COM','STRIPE_NETWORK','OTHER']);
const safeOriginHostGroup=value=>originHostGroups.has(value)?value:'UNKNOWN';
const nestedDocumentFlag='PAYMENT_STRIPE_BROWSER_LOAD_CHECK_BLOCK_NESTED_DOCUMENT';
const nestedDocumentResults=new Set(['BLOCKED','NOT_OBSERVED']);
const recoveryPresentationFlag='PAYMENT_STRIPE_BROWSER_LOAD_CHECK_RECOVERY_PRESENTATION';
const recoveryPresentationProfile='RECOVERY_PRESENTATION';
const postReadyObservationMs=5000;
const refusedHostGroup=url=>{
  try{
    const host=url.hostname;
    if(typeof host!=='string'||!host)return 'UNKNOWN';
    if(host==='stripe.com'||host.endsWith('.stripe.com'))return 'STRIPE_COM';
    if(host==='stripe.network'||host.endsWith('.stripe.network'))return 'STRIPE_NETWORK';
    return 'OTHER';
  }catch{return 'UNKNOWN';}
};
// Witness validation is deliberately stricter than public host-family grouping.
// Check raw authority first: URL normalization must not hide non-ASCII DNS input.
const refusedNetworkOrigin=value=>{
  try{
    const authority=/^https:\/\/([A-Za-z0-9.-]+)(?::443)?(?:[/?#]|$)/i.exec(value);
    if(!authority)return undefined;
    const url=new URL(value),host=url.hostname;
    if(url.protocol!=='https:'||url.username||url.password||url.port||host.length>253)return undefined;
    if(host!=='stripe.network'&&!host.endsWith('.stripe.network'))return undefined;
    if(!host.split('.').every(label=>label.length<=63&&/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label)))return undefined;
    return url.origin;
  }catch{return undefined;}
};
const snapshot=(loadResult,cleanupErrors,originReason,originHostGroup,nestedDocument,recoveryPresentation)=>Object.freeze({
  loadResult,cleanupErrors:Object.freeze([...cleanupErrors]),
  ...(loadResult==='ORIGIN'?{originReason:safeOriginReason(originReason),...(originReason==='HOST_NOT_ALLOWED'?{originHostGroup:safeOriginHostGroup(originHostGroup)}:{})}:{}),
  ...(nestedDocumentResults.has(nestedDocument)?{nestedDocument}:{}),
  ...(recoveryPresentation?{profile:recoveryPresentationProfile}:{}),
});
export function formatLoadCheckResult(report){
  try{
    const candidateLoad=report?.loadResult;
    const candidateCleanup=report?.cleanupErrors;
    const loadResult=safeResults.has(candidateLoad)?candidateLoad:'UNKNOWN';
    const errors=Array.isArray(candidateCleanup)?candidateCleanup:['UNKNOWN'];
    const normalized=new Set();
    const length=errors.length;
    for(let index=0;index<length;index++){
      const code=errors[index];
      normalized.add(cleanupCodes.includes(code)?code:'UNKNOWN');
    }
    const components=cleanupCodes.filter(code=>normalized.has(code));
    let originSuffix='';
    if(loadResult==='ORIGIN'){
      let reason='UNKNOWN';try{reason=safeOriginReason(report.originReason);}catch{}
      originSuffix=` origin_reason=${reason}`;
      if(reason==='HOST_NOT_ALLOWED'){
        let group='UNKNOWN';try{group=safeOriginHostGroup(report.originHostGroup);}catch{}
        originSuffix+=` host_group=${group}`;
      }
    }
    let nestedDocument,recoveryPresentation=false;
    try{const result=report?.nestedDocument;if(nestedDocumentResults.has(result))nestedDocument=result;}catch{}
    try{recoveryPresentation=report?.profile===recoveryPresentationProfile;}catch{}
    const nestedSuffix=nestedDocument?` nested_document=${nestedDocument}`:'';
    const profileSuffix=recoveryPresentation?` profile=${recoveryPresentationProfile}`:'';
    const success=loadResult==='READY'&&!components.length&&(!recoveryPresentation||nestedDocument==='BLOCKED');
    return {line:`STRIPE_BROWSER_LOAD_CHECK load=${loadResult} cleanup=${components.length?'FAILED':'OK'} components=${components.join(',')||'NONE'}${originSuffix}${nestedSuffix}${profileSuffix}`,exitCode:success?0:1};
  }catch{
    return {line:'STRIPE_BROWSER_LOAD_CHECK load=UNKNOWN cleanup=FAILED components=UNKNOWN',exitCode:1};
  }
}
export function admitEnvironment(env){
  if(env.PAYMENT_STRIPE_BROWSER_LOAD_CHECK!=='true'||typeof env.PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY!=='string'||!/^pk_test_[A-Za-z0-9]+$/.test(env.PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY))throw Error('REFUSED');
  if(env[nestedDocumentFlag]!==undefined && !['true','false'].includes(env[nestedDocumentFlag]))throw Error('REFUSED');
  if(env[recoveryPresentationFlag]!==undefined && !['true','false'].includes(env[recoveryPresentationFlag]))throw Error('REFUSED');
  if(env[recoveryPresentationFlag]==='true'&&env[nestedDocumentFlag]!=='true')throw Error('REFUSED');
  for(const name of Object.keys(env))if((/^(?:DEBUG|PWDEBUG|NODE_DEBUG|NODE_DEBUG_NATIVE|NODE_OPTIONS|VITE_INSPECT|PLAYWRIGHT_.*|PW_.*)$/.test(name)||/^(?:(?:BROWSER|RECORD|ENABLE)_)?(?:TRACE|SCREENSHOT|HAR|VIDEO|CAPTURE|DEBUG)(?:_(?:PATH|DIR|FILE|ENABLED))?$/i.test(name))&&env[name])throw Error('REFUSED');
  return env.PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY;
}

export function refusalReason(request,assets,firstDocument,onOriginRefusal){
  let url;try{url=new URL(request.url);}catch{onOriginRefusal?.('URL_INVALID');return 'ORIGIN';}
  if(url.username||url.password)return 'AUTH';
  if(request.redirected)return 'REDIRECT';
  if(Object.keys(request.headers).some(name=>name.toLowerCase()==='authorization'||name.toLowerCase()==='proxy-authorization'))return 'HEADER';
  if(!['GET','POST'].includes(request.method))return 'METHOD';
  if(url.origin===localOrigin){
    if(url.search||url.hash||request.method!=='GET'||!assets.has(url.pathname))return 'LOCAL';
    if(request.navigation&&(!request.mainFrame||url.pathname!==documentPath||!firstDocument))return 'NAVIGATION';
    if(url.pathname===documentPath&&(!request.navigation||!request.mainFrame||!firstDocument))return 'NAVIGATION';
    return null;
  }
  if(request.navigation&&request.mainFrame)return 'NAVIGATION';
  if(!providerNetworkOrigin(url)){
    const reason=url.hostname==='m.stripe.com'?'ORIGIN_STRIPE_COM_M':url.hostname.endsWith('.stripe.com')?'ORIGIN_STRIPE_COM_OTHER':'ORIGIN';
    if(reason==='ORIGIN'){
      const cause=url.protocol!=='https:'?'NON_HTTPS_SCHEME':url.port?'NON_DEFAULT_PORT':'HOST_NOT_ALLOWED';
      onOriginRefusal?.(cause,cause==='HOST_NOT_ALLOWED'?refusedHostGroup(url):undefined);
    }
    return reason;
  }
  // This API gate precedes the broad provider-subresource origin decision.
  if(url.hostname==='api.stripe.com'&&url.pathname!=='/v1/elements/sessions')return 'API_PATH';
  return null;
}
export function classifyRequest(request,assets,firstDocument){
  if(refusalReason(request,assets,firstDocument))return 'REFUSE';
  return new URL(request.url).origin===localOrigin?'LOCAL':'PROVIDER';
}

function stripeParents(target,page){
  try{
    const main=page.mainFrame();
    if(target===main||target.page()!==page||target.isDetached()||main.page()!==page||main.isDetached()||main.url()!==localOrigin+documentPath)return;
    const parents=[];
    const visited=new Set([target]);
    for(let frame=target.parentFrame();frame!==main;frame=frame.parentFrame()){
      if(!frame||visited.has(frame)||frame.page()!==page||frame.isDetached())return;
      const href=frame.url();
      const url=new URL(href);
      if(!providerOrigin(url)||(url.hostname!=='js.stripe.com'&&!url.hostname.endsWith('.js.stripe.com')))return;
      visited.add(frame);
      parents.push({frame,href});
    }
    if(parents.length)return {main,parents};
  }catch{}
}

function sameStripeParents(before,after){
  return after&&before.main===after.main&&before.parents.length===after.parents.length&&
    before.parents.every(({frame,href},index)=>frame===after.parents[index].frame&&href===after.parents[index].href);
}

function matchesPinnedNestedOrigin(url,match){
  if(!/^https:\/\/[A-Za-z0-9.-]+(?::443)?(?:[/?#]|$)/i.test(url))return false;
  let origin;try{origin=new URL(url).origin;}catch{return false;}
  return match(origin)===true;
}

/** All acquisitions are injected; importing this module is inert. */
export async function runLoadCheck(env,ports){
  let key;try{key=admitEnvironment(env);}catch{return snapshot('REFUSED',[]);}
  const observeNested=env[nestedDocumentFlag]==='true';
  const matchesNestedDocumentOrigin=observeNested?ports.matchesNestedDocumentOrigin:undefined;
  if(observeNested&&typeof matchesNestedDocumentOrigin!=='function')return snapshot('REFUSED',[]);
  const recoveryPresentation=env[recoveryPresentationFlag]==='true';
  let nestedDocument=observeNested?'NOT_OBSERVED':undefined;
  let nestedRequest,observeBrowserBlock,nestedObservation,signalNestedRequest;
  const nestedStarted=recoveryPresentation?new Promise(resolve=>{signalNestedRequest=resolve;}):undefined;
  const pendingNestedHeaders=new Set();
  const timeoutMs=ports.timeoutMs??60000;
  const expires=Date.now()+timeoutMs;
  let loadResult,originReason,originHostGroup,signalFailure,build,browser,context,page,firstDocument=true,pageCount=0,finishing=false;
  const cleanupErrors=new Set();
  const pendingCleanup=new Set();
  const retiredExtras=new WeakSet();
  const collectCleanup=code=>cleanupErrors.add(cleanupCodes.includes(code)?code:'UNKNOWN');
  const retireExtra=(value,method,code)=>{
    if(retiredExtras.has(value))return;retiredExtras.add(value);
    const pending=Promise.resolve().then(()=>value[method]()).catch(()=>collectCleanup(code));
    pendingCleanup.add(pending);void pending.finally(()=>pendingCleanup.delete(pending));
  };
  const acquisitions=[];
  const transports=new Set();
  const abort=new AbortController();
  const failed=new Promise(resolve=>{signalFailure=resolve;});
  const fail=(code,originCause,hostGroup)=>{if(!loadResult&&!finishing){loadResult=safeResults.has(code)?code:'ELEMENT';if(loadResult==='ORIGIN'){originReason=safeOriginReason(originCause);if(originReason==='HOST_NOT_ALLOWED')originHostGroup=safeOriginHostGroup(hostGroup);}abort.abort();signalFailure(loadResult);return true;}return false;};
  const timer=setTimeout(()=>fail('TIMEOUT'),timeoutMs);
  const remaining=()=>Math.max(1,expires-Date.now());
  async function forwardProvider(route,url){
    let response;
    try{
      // continue() follows redirects without routing their targets. Fetch once.
      response=await route.fetch({maxRedirects:0,maxRetries:0,timeout:remaining()});
      if(finishing||loadResult||Date.now()>=expires){if(!finishing)fail('TIMEOUT');await route.abort();return;}
      const status=response.status();
      if(response.url()!==url||(status>=300&&status<400)){fail('REDIRECT');await route.abort();return;}
      if(status<200||status>=300){fail('ELEMENT');await route.abort();return;}
      await route.fulfill({response});
    }catch{fail('ELEMENT');try{await route.abort();}catch{}}
    finally{
      if(response)try{await response.dispose();}catch{collectCleanup('RESPONSE_DISPOSE');fail('NOT_CONFIRMED');}
    }
  }
  const acquire=async(fn,assign,lateDispose,cleanupCode)=>{
    if(loadResult)throw Error(loadResult);
    const pending=Promise.resolve().then(fn).then(async value=>{if(loadResult){try{await lateDispose(value);}catch{collectCleanup(cleanupCode);}return undefined;}assign(value);return value;});
    acquisitions.push(pending);void pending.catch(()=>{});
    const value=await Promise.race([pending,failed.then(()=>undefined)]);
    if(loadResult||!value)throw Error(loadResult??'ELEMENT');
    return value;
  };
  let checkpoint='BUILD';
  try{
    await acquire(()=>ports.build(abort.signal,collectCleanup),value=>{build=value;},value=>value.dispose(),'LATE_BUILD_DISPOSE');
    checkpoint='LAUNCH';
    await acquire(()=>ports.launch({signal:abort.signal,timeout:remaining()}),value=>{browser=value;},value=>value.close(),'LATE_BROWSER_CLOSE');
    checkpoint='CONTEXT';
    await acquire(()=>browser.newContext({serviceWorkers:'block',acceptDownloads:false}),value=>{context=value;},value=>value.close(),'LATE_CONTEXT_CLOSE');
    await context.route('**/*',async route=>{
      let finishNestedHeaders;
      try{
        const request=route.request();
        const frame=request.frame();
        if(!page||!frame||frame.page()!==page){fail('NAVIGATION');await route.abort();return;}
        const parents=observeNested&&!nestedRequest&&!firstDocument&&request.isNavigationRequest()?stripeParents(frame,page):undefined;
        const url=request.url(),method=request.method();
        const pinnedBeforeHeaders=recoveryPresentation&&parents&&method==='GET'&&
          matchesPinnedNestedOrigin(url,matchesNestedDocumentOrigin);
        if(pinnedBeforeHeaders){
          let settle;
          const classification=new Promise(resolve=>{settle=resolve;});
          pendingNestedHeaders.add(classification);
          finishNestedHeaders=()=>{pendingNestedHeaders.delete(classification);settle();};
        }
        const view={url,method,headers:await request.allHeaders(),navigation:request.isNavigationRequest(),mainFrame:frame===page.mainFrame(),redirected:!!request.redirectedFrom()};
        // Header acquisition may outlive timeout or successful retirement.
        if(finishing||loadResult||Date.now()>=expires){if(!finishing)fail('TIMEOUT');await route.abort();return;}
        let originCause,hostGroup;
        const reason=loadResult??refusalReason(view,build.assets,firstDocument,(value,group)=>{originCause=value;hostGroup=group;});
        if(reason==='ORIGIN'&&originCause==='HOST_NOT_ALLOWED'&&parents&&!nestedRequest&&view.navigation&&
          view.method==='GET'&&request.resourceType()==='document'&&request.postData()===null&&
          !Object.keys(view.headers).some(name=>name.toLowerCase()==='cookie')&&
          request.frame()===frame&&sameStripeParents(parents,stripeParents(frame,page))&&
          (recoveryPresentation?pinnedBeforeHeaders:matchesPinnedNestedOrigin(view.url,matchesNestedDocumentOrigin))){
          // Suppress one fatal refusal in this load-only probe. Never transmit the request.
          nestedRequest=request;
          const browserBlock=new Promise(resolve=>{observeBrowserBlock=resolve;});
          const abortRequest=(async()=>{
            try{await route.abort('blockedbyclient');}
            catch{fail('ELEMENT');}
          })();
          // Chromium can return from abort() without confirming it applied the block.
          nestedObservation=Promise.race([
            Promise.all([abortRequest,browserBlock]).then(()=>{
              if(!loadResult&&!finishing&&Date.now()<expires)nestedDocument='BLOCKED';
            }),
            failed,
          ]);
          signalNestedRequest?.();
          finishNestedHeaders?.();
          finishNestedHeaders=undefined;
          transports.add(abortRequest);
          try{await abortRequest;}finally{transports.delete(abortRequest);}
          return;
        }
        if(reason){
          const won=fail(reason,originCause,hostGroup);
          if(won&&reason==='ORIGIN'&&originCause==='HOST_NOT_ALLOWED'&&hostGroup==='STRIPE_NETWORK'){
            const origin=refusedNetworkOrigin(view.url);
            if(origin)try{
              // Trusted synchronous hook, read only for this winning refusal.
              // Its failures (including returned thenables) never own load state.
              const observer=ports.onRefusedNetworkOrigin;
              if(typeof observer==='function')void Promise.resolve(observer(origin)).catch(()=>{});
            }catch{}
          }
          await route.abort();return;
        }
        if(new URL(view.url).origin===localOrigin){
          const url=new URL(view.url);if(view.navigation)firstDocument=false;
          const asset=build.assets.get(url.pathname);
          await route.fulfill({status:200,contentType:asset.contentType,body:asset.body,headers:{'Referrer-Policy':'no-referrer','Cache-Control':'no-store'}});
        }else{
          const transport=forwardProvider(route,view.url);
          transports.add(transport);
          try{await transport;}finally{transports.delete(transport);}
        }
      }catch{fail('ELEMENT');try{await route.abort();}catch{}}
      finally{finishNestedHeaders?.();}
    });
    await context.routeWebSocket('**/*',socket=>{fail('WEBSOCKET');socket.close();});
    context.on('page',extra=>{pageCount++;if(pageCount>1){fail('POPUP');retireExtra(extra,'close','EXTRA_PAGE_CLOSE');}});
    checkpoint='PAGE';
    await acquire(()=>context.newPage(),value=>{page=value;},value=>value.close(),'LATE_PAGE_CLOSE');
    page.on('popup',extra=>{fail('POPUP');retireExtra(extra,'close','EXTRA_PAGE_CLOSE');});
    page.on('download',download=>{fail('DOWNLOAD');retireExtra(download,'cancel','DOWNLOAD_CANCEL');});
    page.on('pageerror',()=>fail('ELEMENT'));
    page.on('crash',()=>fail('PAGE'));
    page.on('close',()=>fail('PAGE'));
    if(observeNested)page.on('requestfailed',request=>{
      if(request!==nestedRequest||loadResult||finishing||Date.now()>=expires)return;
      try{if(request.failure()?.errorText==='net::ERR_BLOCKED_BY_CLIENT')observeBrowserBlock?.();}catch{}
    });
    await Promise.race([(async()=>{
      await page.goto(localOrigin+documentPath,{waitUntil:'load',timeout:remaining()});
      if(loadResult)return;
      if(recoveryPresentation){
        await page.evaluate(({key,profile})=>window.configureStripeBrowserLoadCheck(key,profile),{key,profile:'recovery-presentation'});
      }else{
        await page.evaluate(key=>window.configureStripeBrowserLoadCheck(key),key);
      }
      if(loadResult)return;
      checkpoint='ELEMENT';
      const waitForReady=()=>page.waitForFunction(()=>{const state=document.getElementById('status')?.textContent;if(state==='READY')return true;if(state&&state!=='IDLE'&&state!=='LOADING')throw Error('ELEMENT');return false;},{},{timeout:remaining()});
      const blockedBeforeWait=nestedDocument==='BLOCKED';
      await waitForReady();
      const observeAfterReady=recoveryPresentation&&!nestedObservation&&!loadResult&&Date.now()<expires;
      if(observeAfterReady){
        let observationTimer;
        try{
          await Promise.race([nestedStarted,failed,new Promise(resolve=>{
            observationTimer=setTimeout(resolve,Math.min(postReadyObservationMs,remaining()));
          })]);
        }finally{clearTimeout(observationTimer);}
      }
      if(observeAfterReady&&!nestedObservation&&!loadResult&&Date.now()<expires)await waitForReady();
      // A matching navigation may have entered the route before readiness
      // was rechecked while its headers were still pending.
      if(!nestedObservation&&!loadResult&&pendingNestedHeaders.size){
        await Promise.race([Promise.all([...pendingNestedHeaders]),failed]);
      }
      if(nestedObservation&&!blockedBeforeWait){
        await nestedObservation;
        if(!loadResult&&nestedDocument==='BLOCKED'&&Date.now()<expires)await waitForReady();
      }
      // Readiness is a terminal observation, before any final disposal starts.
      if(!loadResult&&!finishing){
        if(observeNested&&Date.now()>=expires)fail('TIMEOUT');
        else loadResult='READY';
      }
    })(),failed]);
  }catch{fail(checkpoint);}
  finally{
    clearTimeout(timer);
    if(!loadResult)loadResult='NOT_CONFIRMED';
    finishing=true;
    // Acquisition APIs cannot all be cancelled. Wait for their late resources to
    // be retired; never report completion while a pending owned browser survives.
    await Promise.allSettled(acquisitions);
    // Response disposal shares the context's request channel. Give owned
    // transports only the remaining original budget before closing that channel.
    const ownedTransports=[...transports];
    const settledTransports=Promise.allSettled(ownedTransports);
    const drainMs=Math.max(0,expires-Date.now());
    if(ownedTransports.length&&drainMs>0){
      let drainTimer;
      try{await Promise.race([settledTransports,new Promise(resolve=>{drainTimer=setTimeout(resolve,drainMs);})]);}
      finally{clearTimeout(drainTimer);}
    }
    // Closure interrupts stuck transport. Still await the same owned operations;
    // late responses must never be fulfilled into the retired page.
    try{await context?.close();}catch{collectCleanup('CONTEXT_CLOSE');}
    await settledTransports;
    for(const [code,close] of [['BROWSER_CLOSE',()=>browser?.close()],['BUILD_DISPOSE',()=>build?.dispose()]]){
      try{await close();}catch{collectCleanup(code);}
    }
    while(pendingCleanup.size)await Promise.allSettled([...pendingCleanup]);
  }
  return snapshot(loadResult,cleanupCodes.filter(code=>cleanupErrors.has(code)),originReason,originHostGroup,nestedDocument,recoveryPresentation);
}

// Explicitly minimize the spawned browser environment; no account/provider keys.
export function browserEnvironment(env){
  return Object.fromEntries(['PATH','HOME','TMPDIR','TEMP','TMP','DISPLAY','WAYLAND_DISPLAY','XAUTHORITY','LANG','LC_ALL','SystemRoot'].filter(name=>typeof env[name]==='string').map(name=>[name,env[name]]));
}
async function realPorts(env){
  return {
    async build(signal,onCleanupError){
      const [{build},{mkdtemp,readFile,readdir,rm},{tmpdir},{join}]=await Promise.all([import('vite'),import('node:fs/promises'),import('node:os'),import('node:path')]);
      const root=fileURLToPath(new URL('..',import.meta.url));
      const directory=await mkdtemp(join(tmpdir(),'stripe-browser-load-check-'));
      const dispose=()=>rm(directory,{recursive:true,force:true});
      try{
        if(signal.aborted)throw Error('TIMEOUT');
        const outDir=join(directory,'dist');
        await build({root,configFile:join(root,'vite.config.ts'),configLoader:'runner',envFile:false,mode:'stripe-browser-load-check',logLevel:'silent',cacheDir:join(directory,'cache'),build:{outDir}});
        const assets=new Map([[documentPath,{body:await readFile(join(outDir,'stripe-browser-load-check.html')),contentType:'text/html'}]]);
        for(const name of await readdir(join(outDir,'assets'))){
          if(!/^[\w.-]+\.(?:js|css)$/.test(name))throw Error('BUILD');
          assets.set('/assets/'+name,{body:await readFile(join(outDir,'assets',name)),contentType:name.endsWith('.css')?'text/css':'text/javascript'});
        }
        return {assets,dispose};
      }catch{try{await dispose();}catch{onCleanupError?.('BUILD_DISPOSE');}throw Error('BUILD');}
    },
    async launch({timeout,signal}){
      if(signal.aborted)throw Error('TIMEOUT');
      const {chromium}=await import('@playwright/test');
      if(signal.aborted)throw Error('TIMEOUT');
      return chromium.launch({channel:'chrome',headless:false,timeout,env:browserEnvironment(env)});
    },
  };
}
/** Private harness entry; no capture writer or automatic observer is installed. */
export async function runNativeLoadCheck(env,observer){
  try{
    admitEnvironment(env);
    if(observer!==undefined&&typeof observer!=='function')return snapshot('REFUSED',[]);
    const ports=await realPorts(env);
    if(env[nestedDocumentFlag]==='true'){
      const {loadFailedRenewalStaticOriginMatcher}=await import('../tests/support/stripe-billing-failed-renewal-static-origin-pin.ts');
      ports.matchesNestedDocumentOrigin=loadFailedRenewalStaticOriginMatcher();
    }
    if(observer!==undefined)ports.onRefusedNetworkOrigin=observer;
    return await runLoadCheck(env,ports);
  }catch{return snapshot('REFUSED',[]);}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const result=await runNativeLoadCheck(process.env);
  const {line,exitCode}=formatLoadCheckResult(result);
  process.stdout.write(line+'\n');
  process.exitCode=exitCode;
}
