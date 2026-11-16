import {fileURLToPath,pathToFileURL} from 'node:url';
import {providerNetworkOrigin} from '../tests/support/stripe-provider-origin.ts';

const localOrigin='http://127.0.0.1:4173';
const documentPath='/stripe-browser-load-check.html';
const safeResults=new Set(['READY','REFUSED','BUILD','LAUNCH','CONTEXT','PAGE','ELEMENT','TIMEOUT','CLEANUP','HEADER','AUTH','REDIRECT','API_PATH','ORIGIN_STRIPE_COM_M','ORIGIN_STRIPE_COM_OTHER','ORIGIN','NAVIGATION','METHOD','LOCAL','POPUP','DOWNLOAD','WEBSOCKET']);
export function admitEnvironment(env){
  if(env.PAYMENT_STRIPE_BROWSER_LOAD_CHECK!=='true'||typeof env.PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY!=='string'||!/^pk_test_[A-Za-z0-9]+$/.test(env.PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY))throw Error('REFUSED');
  for(const name of Object.keys(env))if((/^(?:DEBUG|PWDEBUG|NODE_DEBUG|NODE_DEBUG_NATIVE|NODE_OPTIONS|VITE_INSPECT|PLAYWRIGHT_.*|PW_.*)$/.test(name)||/^(?:(?:BROWSER|RECORD|ENABLE)_)?(?:TRACE|SCREENSHOT|HAR|VIDEO|CAPTURE|DEBUG)(?:_(?:PATH|DIR|FILE|ENABLED))?$/i.test(name))&&env[name])throw Error('REFUSED');
  return env.PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY;
}

export function refusalReason(request,assets,firstDocument){
  let url;try{url=new URL(request.url);}catch{return 'ORIGIN';}
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
  if(!providerNetworkOrigin(url))return url.hostname==='m.stripe.com'?'ORIGIN_STRIPE_COM_M':url.hostname.endsWith('.stripe.com')?'ORIGIN_STRIPE_COM_OTHER':'ORIGIN';
  // This API gate precedes the broad provider-subresource origin decision.
  if(url.hostname==='api.stripe.com'&&url.pathname!=='/v1/elements/sessions')return 'API_PATH';
  return null;
}
export function classifyRequest(request,assets,firstDocument){
  if(refusalReason(request,assets,firstDocument))return 'REFUSE';
  return new URL(request.url).origin===localOrigin?'LOCAL':'PROVIDER';
}

/** All acquisitions are injected; importing this module is inert. */
export async function runLoadCheck(env,ports){
  let key;try{key=admitEnvironment(env);}catch{return 'REFUSED';}
  const timeoutMs=ports.timeoutMs??60000;
  const expires=Date.now()+timeoutMs;
  let failure,signalFailure,build,browser,context,page,firstDocument=true,pageCount=0,finishing=false,lateCleanupFailed=false,responseCleanupFailed=false;
  const acquisitions=[];
  const transports=new Set();
  const abort=new AbortController();
  const failed=new Promise(resolve=>{signalFailure=resolve;});
  const fail=code=>{if(!failure&&!finishing){failure=safeResults.has(code)?code:'ELEMENT';abort.abort();signalFailure(failure);}};
  const timer=setTimeout(()=>fail('TIMEOUT'),timeoutMs);
  const remaining=()=>Math.max(1,expires-Date.now());
  async function forwardProvider(route,url){
    let response;
    try{
      // continue() follows redirects without routing their targets. Fetch once.
      response=await route.fetch({maxRedirects:0,maxRetries:0,timeout:remaining()});
      if(finishing||failure||Date.now()>=expires){if(!finishing)fail('TIMEOUT');await route.abort();return;}
      const status=response.status();
      if(response.url()!==url||(status>=300&&status<400)){fail('REDIRECT');await route.abort();return;}
      if(status<200||status>=300){fail('ELEMENT');await route.abort();return;}
      await route.fulfill({response});
    }catch{fail('ELEMENT');try{await route.abort();}catch{}}
    finally{
      if(response)try{await response.dispose();}catch{responseCleanupFailed=true;fail('CLEANUP');}
    }
  }
  const acquire=async(fn,assign,lateDispose)=>{
    if(failure)throw Error(failure);
    const pending=Promise.resolve().then(fn).then(async value=>{if(failure){try{await lateDispose(value);}catch{lateCleanupFailed=true;}return undefined;}assign(value);return value;});
    acquisitions.push(pending);void pending.catch(()=>{});
    const value=await Promise.race([pending,failed.then(()=>undefined)]);
    if(failure||!value)throw Error(failure??'ELEMENT');
    return value;
  };
  let checkpoint='BUILD';
  try{
    await acquire(()=>ports.build(abort.signal),value=>{build=value;},value=>value.dispose());
    checkpoint='LAUNCH';
    await acquire(()=>ports.launch({signal:abort.signal,timeout:remaining()}),value=>{browser=value;},value=>value.close());
    checkpoint='CONTEXT';
    await acquire(()=>browser.newContext({serviceWorkers:'block',acceptDownloads:false}),value=>{context=value;},value=>value.close());
    await context.route('**/*',async route=>{
      try{
        const request=route.request();
        const frame=request.frame();
        if(!page||!frame||frame.page()!==page){fail('NAVIGATION');await route.abort();return;}
        const view={url:request.url(),method:request.method(),headers:await request.allHeaders(),navigation:request.isNavigationRequest(),mainFrame:frame===page.mainFrame(),redirected:!!request.redirectedFrom()};
        // Header acquisition may outlive timeout or successful retirement.
        if(finishing||failure||Date.now()>=expires){if(!finishing)fail('TIMEOUT');await route.abort();return;}
        const reason=failure??refusalReason(view,build.assets,firstDocument);
        if(reason){fail(reason);await route.abort();return;}
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
    });
    await context.routeWebSocket('**/*',socket=>{fail('WEBSOCKET');socket.close();});
    context.on('page',extra=>{pageCount++;if(pageCount>1){fail('POPUP');void extra.close().catch(()=>{});}});
    checkpoint='PAGE';
    await acquire(()=>context.newPage(),value=>{page=value;},value=>value.close());
    page.on('popup',extra=>{fail('POPUP');void extra.close().catch(()=>{});});
    page.on('download',download=>{fail('DOWNLOAD');void download.cancel().catch(()=>{});});
    page.on('pageerror',()=>fail('ELEMENT'));
    page.on('crash',()=>fail('PAGE'));
    page.on('close',()=>fail('PAGE'));
    await Promise.race([(async()=>{
      await page.goto(localOrigin+documentPath,{waitUntil:'load',timeout:remaining()});
      if(failure)return;
      await page.evaluate(key=>window.configureStripeBrowserLoadCheck(key),key);
      if(failure)return;
      checkpoint='ELEMENT';
      await page.waitForFunction(()=>{const state=document.getElementById('status')?.textContent;if(state==='READY')return true;if(state&&state!=='IDLE'&&state!=='LOADING')throw Error('ELEMENT');return false;},{},{timeout:remaining()});
    })(),failed]);
  }catch{fail(checkpoint);}
  finally{
    clearTimeout(timer);
    finishing=true;
    // Acquisition APIs cannot all be cancelled. Wait for their late resources to
    // be retired; never report completion while a pending owned browser survives.
    await Promise.allSettled(acquisitions);
    // Context closure interrupts transport. Settle owned fetch/disposal before
    // returning; late responses must never be fulfilled into the retired page.
    try{await context?.close();}catch{failure='CLEANUP';}
    await Promise.allSettled([...transports]);
    if(lateCleanupFailed||responseCleanupFailed)failure='CLEANUP';
    for(const close of [()=>browser?.close(),()=>build?.dispose()]){
      try{await close();}catch{failure='CLEANUP';}
    }
  }
  return failure??'READY';
}

// Explicitly minimize the spawned browser environment; no account/provider keys.
export function browserEnvironment(env){
  return Object.fromEntries(['PATH','HOME','TMPDIR','TEMP','TMP','DISPLAY','WAYLAND_DISPLAY','XAUTHORITY','LANG','LC_ALL','SystemRoot'].filter(name=>typeof env[name]==='string').map(name=>[name,env[name]]));
}
async function realPorts(env){
  return {
    async build(signal){
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
      }catch{await dispose();throw Error('BUILD');}
    },
    async launch({timeout,signal}){
      if(signal.aborted)throw Error('TIMEOUT');
      const {chromium}=await import('@playwright/test');
      if(signal.aborted)throw Error('TIMEOUT');
      return chromium.launch({channel:'chrome',headless:false,timeout,env:browserEnvironment(env)});
    },
  };
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  let result='REFUSED';
  try{admitEnvironment(process.env);result=await runLoadCheck(process.env,await realPorts(process.env));}catch{}
  process.stdout.write(`STRIPE_BROWSER_LOAD_CHECK ${safeResults.has(result)?result:'REFUSED'}\n`);
  if(result!=='READY')process.exitCode=1;
}
