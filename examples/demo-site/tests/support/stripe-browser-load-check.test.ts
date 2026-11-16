import test from 'node:test';
import assert from 'node:assert/strict';
import {matchesGlob} from 'node:path';

// These tests acquire only injected objects. Import failure names the missing contract.
// @ts-ignore Explicit source extension for the Node-only test runner.
const pageModule = async ():Promise<any> => import('../../src/stripe-browser-load-check.ts').catch(()=>assert.fail('expected exported createLoadController with injected DOM/loader ports'));
// @ts-ignore Private JavaScript launcher intentionally has no SDK declaration surface.
const launcherModule = async ():Promise<any> => import('../../scripts/stripe-browser-load-check.mjs').catch(()=>assert.fail('expected inert launcher exports admitEnvironment, classifyRequest, runLoadCheck'));
const key='pk_test_artificialOnly';
const env={PAYMENT_STRIPE_BROWSER_LOAD_CHECK:'true',PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY:key};
test('default Playwright discovery excludes both Node-only load-check support tests',async()=>{
  // Importing configuration constructs data only; never ask Playwright to list/run.
  // @ts-ignore Explicit source extension for the Node source runner.
  const {default:config}=await import('../../playwright.config.ts');
  const patterns=Array.isArray(config.testIgnore)?config.testIgnore:[config.testIgnore];
  for(const filename of ['tests/support/stripe-browser-load-check.test.ts','tests/support/stripe-browser-load-check-build.test.ts']){
    assert.ok(patterns.some(pattern=>typeof pattern==='string'&&matchesGlob(filename,pattern)),`${filename} must be ignored by default Playwright`);
  }
});
const deferred=()=>{let resolve!:(value:any)=>void;const promise=new Promise<any>(r=>{resolve=r;});return {promise,resolve};};

function controllerFixture(load?:Promise<any>){
  const events:Record<string,Function>={};const statuses:string[]=[];const calls:any[]=[];
  let destroys=0;
  const financial=()=>assert.fail('financial API must never be invoked');
  const element={on:(name:string,fn:Function)=>{events[name]=fn;},mount:(host:any)=>{calls.push(['mount',host]);},destroy:()=>{destroys++;}};
  const stripe={elements:(options:any)=>{calls.push(['elements',options]);return {create:(type:string,options:any)=>{calls.push(['create',type,options]);return element;},submit:financial};},confirmPayment:financial,confirmSetup:financial,createPaymentMethod:financial,createToken:financial,createConfirmationToken:financial};
  const host={isConnected:true,inert:false,tabIndex:0,style:{pointerEvents:''}};
  const life:Record<string,Function>={};
  const ports={host,load:async(k:string)=>{calls.push(['load',k]);return load?await load:stripe;},status:(s:string)=>statuses.push(s),onPageLoss:(fn:Function)=>{life.loss=fn;return ()=>{delete life.loss;};},deadline:(_fn:Function)=>()=>{}};
  return {ports,stripe,events,statuses,calls,host,life,destroys:()=>destroys};
}

test('admission is exact, explicit, test-key-only, rejects capture/debug flags',async()=>{
  const {admitEnvironment}=await launcherModule();
  assert.equal(admitEnvironment(env),key);
  for(const value of [{},{...env,PAYMENT_STRIPE_BROWSER_LOAD_CHECK:'1'},{...env,PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY:'pk_live_no'},{...env,PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY:'sk_test_no'},{...env,PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY:key+'\n'},{...env,DEBUG:'pw:*'},{...env,PWDEBUG:'1'},{...env,NODE_OPTIONS:'--inspect'},{...env,NODE_DEBUG:'http'}])assert.throws(()=>admitEnvironment(value));
});

test('admission and browser environment never read unrelated secret values',async()=>{
  const {admitEnvironment,browserEnvironment}=await launcherModule();
  const isolated={...env,PATH:'/artificial/bin'};
  Object.defineProperty(isolated,'PRIVATE_ACCOUNT_KEY',{enumerable:true,get:()=>assert.fail('unrelated key discovery')});
  Object.defineProperty(isolated,'SHARED_SECRET',{enumerable:true,get:()=>assert.fail('shared secret must not be read')});
  assert.equal(admitEnvironment(isolated),key);
  assert.deepEqual(browserEnvironment(isolated),{PATH:'/artificial/bin'});
});

test('admission accepts ordinary CHARSET value',async()=>{
  const {admitEnvironment}=await launcherModule();
  assert.equal(admitEnvironment({...env,CHARSET:'UTF-8'}),key);
});

test('admission refuses actual debug and capture flags',async()=>{
  const {admitEnvironment}=await launcherModule();
  for(const name of ['DEBUG','PWDEBUG','NODE_DEBUG','NODE_OPTIONS','VITE_INSPECT','PLAYWRIGHT_TRACE','PW_TEST_HTML_REPORT_OPEN','TRACE','SCREENSHOT','HAR','VIDEO','CAPTURE','RECORD_HAR_PATH','BROWSER_CAPTURE','ENABLE_DEBUG']){
    assert.throws(()=>admitEnvironment({...env,[name]:'enabled'}),name);
  }
});

test('controller admits a single string only, mounts deferred card Element and destroys after ready',async()=>{
  const {createLoadController}=await pageModule();const f=controllerFixture();const c=createLoadController(f.ports);
  for(const value of [{publishableKey:key,clientSecret:'secret'},'pk_live_no','sk_test_no',''])await assert.rejects(async()=>c.configure(value));
  await c.configure(key);
  assert.deepEqual(f.calls.slice(0,3),[['load',key],['elements',{mode:'payment',amount:2000,currency:'eur',allowedPaymentMethodTypes:['card']}],['create','payment',{wallets:{applePay:'never',googlePay:'never'}}]]);
  assert.equal(f.calls[3][1],f.host);assert.equal(f.host.inert,true);assert.equal(f.host.tabIndex,-1);assert.equal(f.host.style.pointerEvents,'none');
  f.events.ready!();assert.equal(c.state(),'READY');assert.equal(f.destroys(),1);
  f.events.loaderror!({error:{message:'private'}});assert.equal(c.state(),'READY');
  await assert.rejects(()=>c.configure(key));assert.ok(!JSON.stringify(f.statuses).includes('private'));
});

test('controller loaderror, stop, page loss and late loader cannot resurrect host',async()=>{
  const {createLoadController}=await pageModule();
  for(const action of ['error','stop','loss']){
    const f=controllerFixture();const c=createLoadController(f.ports);await c.configure(key);
    if(action==='error')f.events.loaderror!({message:'secret'});else if(action==='loss')f.life.loss!();else c.stop();
    f.events.ready!();c.stop();assert.equal(f.destroys(),1);assert.notEqual(c.state(),'READY');
  }
  const delayed=deferred();const f=controllerFixture(delayed.promise);const c=createLoadController(f.ports);
  const opening=c.configure(key);c.stop();delayed.resolve(f.stripe);await opening;
  assert.equal(f.calls.length,1);assert.equal(c.state(),'STOPPED');
  const disconnected=controllerFixture();disconnected.host.isConnected=false;
  await assert.rejects(()=>createLoadController(disconnected.ports).configure(key));assert.equal(disconnected.calls.length,0);
});

test('route classifier never falls through financial API, navigation, auth, redirect or unknown origins',async()=>{
  const {classifyRequest}=await launcherModule();const assets=new Map([['/stripe-browser-load-check.html',{body:'html',contentType:'text/html'}],['/assets/check.js',{body:'js',contentType:'text/javascript'}]]);
  const request=(url:string,extra:any={})=>({url,method:'GET',headers:{},navigation:false,mainFrame:false,redirected:false,...extra});
  assert.equal(classifyRequest(request('http://127.0.0.1:4173/stripe-browser-load-check.html',{navigation:true,mainFrame:true}),assets,true),'LOCAL');
  assert.equal(classifyRequest(request('https://api.stripe.com/v1/elements/sessions',{method:'POST'}),assets,false),'PROVIDER');
  assert.equal(classifyRequest(request('https://js.stripe.com/v3/'),assets,false),'PROVIDER');
  for(const r of [request('https://api.stripe.com/v1/payment_intents',{method:'POST'}),request('https://api.stripe.com/v1/setup_intents'),request('https://api.stripe.com/v1/payment_methods'),request('https://api.stripe.com/v1/elements/sessions/'),request('https://api.stripe.com/v1/elements/sessions',{headers:{Authorization:'x'}}),request('https://js.stripe.com/v3/',{redirected:true}),request('https://js.stripe.com/v3/',{navigation:true,mainFrame:true}),request('https://evil.test/'),request('https://user@js.stripe.com/v3/'),request('https://js.stripe.com/v3/',{method:'DELETE'}),request('http://127.0.0.1:4173/assets/check.js?secret=x')])assert.equal(classifyRequest(r,assets,false),'REFUSE');
  assert.equal(classifyRequest(request('http://127.0.0.1:4173/stripe-browser-load-check.html',{navigation:true,mainFrame:true}),assets,false),'REFUSE');
});

function runnerFixture(){
  const calls:string[]=[];let routeHandler:Function;let websocket:Function;const listeners:Record<string,Function>={};
  const frame:{page:()=>any}={page:()=>page};
  const page={mainFrame:()=>frame,on:(name:string,fn:Function)=>{listeners[name]=fn;},goto:async()=>{calls.push('goto');},evaluate:async()=>{calls.push('configure');},waitForFunction:async()=>{},close:async()=>{calls.push('page.close');}};
  const context={route:async(_pattern:string,fn:Function)=>{routeHandler=fn;},routeWebSocket:async(_pattern:string,fn:Function)=>{websocket=fn;},on:(name:string,fn:Function)=>{listeners['context.'+name]=fn;},newPage:async()=>page,close:async()=>{calls.push('context.close');}};
  const browser={newContext:async(options:any)=>{calls.push('context');assert.deepEqual(options,{serviceWorkers:'block',acceptDownloads:false});return context;},close:async()=>{calls.push('browser.close');}};
  const ports={build:async()=>{calls.push('build');return {assets:new Map([['/stripe-browser-load-check.html',{body:'html',contentType:'text/html'}]]),dispose:async()=>{calls.push('dispose');}};},launch:async()=>{calls.push('launch');return browser;},timeoutMs:30};
  return {calls,ports,browser,context,page,listeners,route:()=>routeHandler!,websocket:()=>websocket!};
}

test('runner gates before build/launch and closes one owned context with fixed result',async()=>{
  const {runLoadCheck}=await launcherModule();const f=runnerFixture();
  assert.equal(await runLoadCheck({},f.ports),'REFUSED');assert.deepEqual(f.calls,[]);
  assert.equal(await runLoadCheck(env,f.ports),'READY');
  for(const name of ['build','launch','context','goto','configure','context.close','browser.close','dispose'])assert.equal(f.calls.filter(x=>x===name).length,1,name);
  assert.equal(typeof f.websocket(),'function');
});

test('runner refuses network before forwarding and closes after errors and late acquisition',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const scenario of ['route','pageerror','popup','download','websocket','timeout']){
    const f=runnerFixture();f.page.waitForFunction=async()=>{
      if(scenario==='route')await f.route()({request:()=>({url:()=> 'https://api.stripe.com/v1/payment_intents',method:()=> 'POST',allHeaders:async()=>({}),isNavigationRequest:()=>false,redirectedFrom:()=>null,frame:()=>f.page.mainFrame()}),abort:async()=>{f.calls.push('abort');},continue:()=>assert.fail('financial forwarding'),fulfill:()=>assert.fail('financial fulfill')});
      if(scenario==='pageerror')f.listeners.pageerror!(Error('secret'));
      if(scenario==='popup')f.listeners.popup!({close:async()=>{}});
      if(scenario==='download')f.listeners.download!({cancel:async()=>{}});
      if(scenario==='websocket')f.websocket()({close:()=>{}});
      await new Promise(()=>{});
    };
    const result=await runLoadCheck(env,f.ports);
    assert.equal(result,({route:'API_PATH',pageerror:'ELEMENT',popup:'POPUP',download:'DOWNLOAD',websocket:'WEBSOCKET',timeout:'TIMEOUT'} as Record<string,string>)[scenario],scenario);
    assert.equal(f.calls.filter(x=>x==='context.close').length,1);assert.equal(f.calls.filter(x=>x==='browser.close').length,1);assert.equal(f.calls.filter(x=>x==='dispose').length,1);
  }
  const f=runnerFixture(),late=deferred();f.ports.launch=async()=>late.promise;
  const run=runLoadCheck(env,f.ports);await new Promise(r=>setTimeout(r,45));late.resolve(f.browser);
  assert.notEqual(await run,'READY');await new Promise(r=>setTimeout(r,0));assert.equal(f.calls.filter(x=>x==='browser.close').length,1);assert.ok(!f.calls.includes('context'));
});

test('held provider headers cannot forward after readiness retirement or timeout',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const terminal of ['ready','timeout']){
    const f=runnerFixture(),headers=deferred();let requestFinished:Promise<void>|undefined;
    f.page.waitForFunction=async()=>{
      requestFinished=f.route()({request:()=>({url:()=> 'https://js.stripe.com/v3/',method:()=> 'GET',allHeaders:()=>headers.promise,isNavigationRequest:()=>false,redirectedFrom:()=>null,frame:()=>f.page.mainFrame()}),abort:async()=>{f.calls.push('abort');},continue:async()=>{f.calls.push('continue');},fulfill:()=>assert.fail('provider fulfill')});
      if(terminal==='timeout')await new Promise(()=>{});
    };
    assert.equal(await runLoadCheck(env,f.ports),terminal==='ready'?'READY':'TIMEOUT');
    headers.resolve({});await requestFinished;
    assert.ok(!f.calls.includes('continue'),terminal);assert.equal(f.calls.filter(x=>x==='abort').length,1,terminal);
  }
});

test('acquisition failures retain stage and actual cleanup failures report uncertainty',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const stage of ['build','launch']){
    const f=runnerFixture();f.ports[stage as 'build']=async()=>{throw Error('private detail');};
    assert.equal(await runLoadCheck(env,f.ports),stage.toUpperCase());
  }
  const owned=runnerFixture();owned.context.close=async()=>{throw Error('private cleanup');};
  assert.equal(await runLoadCheck(env,owned.ports),'CLEANUP');
  const late=runnerFixture(),opening=deferred();late.ports.launch=async()=>opening.promise;
  late.browser.close=async()=>{throw Error('private late cleanup');};
  const result=runLoadCheck(env,late.ports);await new Promise(resolve=>setTimeout(resolve,45));opening.resolve(late.browser);
  assert.equal(await result,'CLEANUP');
});

function providerRoute(f:ReturnType<typeof runnerFixture>,fetcher:(options:any)=>Promise<any>){
  return {request:()=>({url:()=> 'https://api.stripe.com/v1/elements/sessions',method:()=> 'POST',allHeaders:async()=>({}),isNavigationRequest:()=>false,redirectedFrom:()=>null,frame:()=>f.page.mainFrame()}),
    fetch:fetcher,abort:async()=>{f.calls.push('abort');},continue:()=>assert.fail('provider forwarding must not follow redirects implicitly'),
    fulfill:async(options:any)=>{f.calls.push('fulfill');assert.ok(options.response,'fulfill validated fetched response');}};
}
function providerResponse(f:ReturnType<typeof runnerFixture>,status=200,location?:string){
  return {url:()=> 'https://api.stripe.com/v1/elements/sessions',status:()=>status,headers:()=>location?{location}:{},dispose:async()=>{f.calls.push('response.dispose');}};
}

test('registered route refuses foreign navigation before popup notification and unresolved frame ownership',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const ownership of ['foreign','missing','throwing']){
    const f=runnerFixture();let forwardedBeforePopup:string[]=[];
    f.page.waitForFunction=async()=>{
      const foreignPage={};
      const frame=ownership==='foreign'?{page:()=>foreignPage}:ownership==='missing'?{}:{page:()=>{throw Error('private ownership detail');}};
      const route:any=providerRoute(f,async()=>{f.calls.push('fetch');return {...providerResponse(f),url:()=> 'https://js.stripe.com/v3/'};});
      route.request=()=>({url:()=> 'https://js.stripe.com/v3/',method:()=> 'GET',allHeaders:async()=>({}),isNavigationRequest:()=>true,redirectedFrom:()=>null,frame:()=>frame as any});
      route.continue=()=>{f.calls.push('continue');};
      route.fulfill=async()=>{f.calls.push('fulfill');};
      await f.route()(route);
      forwardedBeforePopup=f.calls.filter(call=>['fetch','continue','fulfill'].includes(call));
      f.listeners.popup!({close:async()=>{}});
    };
    const result=await runLoadCheck(env,f.ports);
    assert.deepEqual(forwardedBeforePopup,[],ownership);
    assert.equal(result,ownership==='foreign'?'NAVIGATION':'ELEMENT',ownership);
    assert.equal(f.calls.filter(call=>call==='abort').length,1,ownership);
    for(const name of ['context.close','browser.close','dispose'])assert.equal(f.calls.filter(call=>call===name).length,1,`${ownership}: ${name}`);
  }
});

test('registered route preserves owned main document and owned provider subframe navigation',async()=>{
  const {runLoadCheck}=await launcherModule();const f=runnerFixture();
  f.page.waitForFunction=async()=>{
    const documentRoute=providerRoute(f,async()=>assert.fail('local document fetch'));
    documentRoute.request=()=>({url:()=> 'http://127.0.0.1:4173/stripe-browser-load-check.html',method:()=> 'GET',allHeaders:async()=>({}),isNavigationRequest:()=>true,redirectedFrom:()=>null,frame:()=>f.page.mainFrame()});
    documentRoute.fulfill=async(options:any)=>{assert.equal(options.status,200);f.calls.push('document.fulfill');};
    await f.route()(documentRoute);
    const subframe={page:()=>f.page};
    const route:any=providerRoute(f,async()=>{f.calls.push('fetch');return providerResponse(f);});
    route.request=()=>({url:()=> 'https://api.stripe.com/v1/elements/sessions',method:()=> 'POST',allHeaders:async()=>({}),isNavigationRequest:()=>true,redirectedFrom:()=>null,frame:()=>subframe});
    await f.route()(route);
  };
  assert.equal(await runLoadCheck(env,f.ports),'READY');
  for(const name of ['document.fulfill','fetch','fulfill','response.dispose'])assert.equal(f.calls.filter(call=>call===name).length,1,name);
  assert.ok(!f.calls.includes('abort'));
});

test('provider transport never follows redirects and validates before fulfill',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const location of ['https://api.stripe.com/v1/payment_intents','https://foreign.test/private']){
    for(const status of [301,307,308]){
      const f=runnerFixture();const response=providerResponse(f,status,location);
      f.page.waitForFunction=async()=>{await f.route()(providerRoute(f,async(options:any)=>{
        assert.equal(options.maxRedirects,0);assert.equal(options.maxRetries,0);assert.ok(options.timeout>0&&options.timeout<=30);return response;
      }));};
      assert.equal(await runLoadCheck(env,f.ports),'REDIRECT');
      assert.ok(!f.calls.includes('fulfill'));assert.equal(f.calls.filter(x=>x==='abort').length,1);assert.equal(f.calls.filter(x=>x==='response.dispose').length,1);
    }
  }
  const f=runnerFixture();f.page.waitForFunction=async()=>{await f.route()(providerRoute(f,async()=>providerResponse(f)));};
  assert.equal(await runLoadCheck(env,f.ports),'READY');assert.equal(f.calls.filter(x=>x==='fulfill').length,1);assert.equal(f.calls.filter(x=>x==='response.dispose').length,1);
});

test('late fetched response is disposed without fulfillment after retirement or timeout',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const terminal of ['ready','timeout']){
    const f=runnerFixture(),fetching=deferred(),started=deferred();let routing:Promise<void>|undefined;
    f.page.waitForFunction=async()=>{
      routing=f.route()(providerRoute(f,async()=>{started.resolve(undefined);return fetching.promise;}));
      if(terminal==='timeout')await new Promise(()=>{});
    };
    let settled=false;const result=runLoadCheck(env,f.ports).then((value:string)=>{settled=true;return value;});
    assert.equal(await Promise.race([started.promise.then(()=>true),result.then(()=>false)]),true,'expected non-following provider fetch to start before run settles');
    await new Promise(resolve=>setTimeout(resolve,terminal==='timeout'?45:0));
    assert.equal(settled,false,'do not return with an unresolved owned provider transport');
    fetching.resolve(providerResponse(f));await routing;
    assert.equal(await result,terminal==='timeout'?'TIMEOUT':'READY');
    assert.ok(!f.calls.includes('fulfill'));assert.equal(f.calls.filter(x=>x==='abort').length,1);assert.equal(f.calls.filter(x=>x==='response.dispose').length,1);
  }
});

test('provider fetch failure is safe and response disposal failure reports cleanup uncertainty',async()=>{
  const {runLoadCheck}=await launcherModule();
  const failed=runnerFixture();failed.page.waitForFunction=async()=>{await failed.route()(providerRoute(failed,async()=>{throw Error('private transport detail');}));};
  assert.equal(await runLoadCheck(env,failed.ports),'ELEMENT');assert.ok(!failed.calls.includes('fulfill'));
  const disposal=runnerFixture();const response=providerResponse(disposal);response.dispose=async()=>{throw Error('private disposal detail');};
  disposal.page.waitForFunction=async()=>{await disposal.route()(providerRoute(disposal,async()=>response));};
  assert.equal(await runLoadCheck(env,disposal.ports),'CLEANUP');
});
