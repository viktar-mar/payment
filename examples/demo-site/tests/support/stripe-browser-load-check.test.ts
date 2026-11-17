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
const report=(loadResult:string,cleanupErrors:string[]=[])=>({loadResult,cleanupErrors});
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
  for(const name of ['DEBUG','PWDEBUG','NODE_DEBUG','NODE_DEBUG_NATIVE','NODE_OPTIONS','VITE_INSPECT','PLAYWRIGHT_TRACE','PW_TEST_HTML_REPORT_OPEN','TRACE','SCREENSHOT','HAR','VIDEO','CAPTURE','RECORD_HAR_PATH','BROWSER_CAPTURE','ENABLE_DEBUG']){
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

test('generic ORIGIN reports only its fixed cause through the registered route and safe formatter',async()=>{
  const {runLoadCheck,formatLoadCheckResult}=await launcherModule();
  const cases=[
    ['https://[private-sentinel','URL_INVALID'],
    ['http://private-sentinel.invalid:8443/private?key=secret','NON_HTTPS_SCHEME'],
    ['data:text/plain,private-sentinel','NON_HTTPS_SCHEME'],
    ['blob:https://js.stripe.com/private-sentinel','NON_HTTPS_SCHEME'],
    ['https://private-sentinel.invalid:8443/path?key=secret','NON_DEFAULT_PORT'],
    ['http://stripe.network/private-sentinel','NON_HTTPS_SCHEME'],
    ['https://stripe.network:8443/private-sentinel','NON_DEFAULT_PORT'],
    ['https://private-sentinel.invalid/path?key=secret','HOST_NOT_ALLOWED'],
  ];
  for(const [url,originReason] of cases){
    const f=runnerFixture();
    f.page.waitForFunction=async()=>{
      const route:any=providerRoute(f,async()=>assert.fail('rejected origin must never be fetched'));
      const req=route.request();route.request=()=>({...req,url:()=>url});
      route.fulfill=async()=>assert.fail('rejected origin must never be fulfilled');
      await f.route()(route);
    };
    const result=await runLoadCheck(env,f.ports);
    assert.deepEqual(result,{...report('ORIGIN'),originReason,...(originReason==='HOST_NOT_ALLOWED'?{originHostGroup:'OTHER'}:{})});
    assert.equal(Object.isFrozen(result),true);
    assert.deepEqual(formatLoadCheckResult(result),{line:`STRIPE_BROWSER_LOAD_CHECK load=ORIGIN cleanup=OK components=NONE origin_reason=${originReason}${originReason==='HOST_NOT_ALLOWED'?' host_group=OTHER':''}`,exitCode:1});
    assert.doesNotMatch(JSON.stringify(result)+JSON.stringify(formatLoadCheckResult(result)),/private-sentinel|secret|https?:|blob:|data:/);
    for(const call of ['abort','context.close','browser.close','dispose'])assert.equal(f.calls.filter(x=>x===call).length,1,call);
  }
});

test('HOST_NOT_ALLOWED retains only a fixed host family from refused parsed URLs',async()=>{
  const {runLoadCheck,formatLoadCheckResult}=await launcherModule();
  for(const [host,originHostGroup] of [
    ['stripe.com','STRIPE_COM'],['stripe.network','STRIPE_NETWORK'],['sub.stripe.network','STRIPE_NETWORK'],
    ['SUB.STRIPE.NETWORK:443','STRIPE_NETWORK'],['notstripe.network','OTHER'],['stripe.network.evil.test','OTHER'],
    ['stripe.network.','OTHER'],['unrelated.invalid','OTHER'],
  ]){
    const f=runnerFixture();f.page.waitForFunction=async()=>{
      const route:any=providerRoute(f,async()=>assert.fail('refused host must not fetch'));
      const request=route.request();route.request=()=>({...request,url:()=>`https://${host}/private-sentinel?key=secret`});
      route.fulfill=async()=>assert.fail('refused host must not fulfill');await f.route()(route);
    };
    const result=await runLoadCheck(env,f.ports);
    assert.deepEqual(result,{...report('ORIGIN'),originReason:'HOST_NOT_ALLOWED',originHostGroup});
    assert.equal(Object.isFrozen(result),true);assert.equal(Object.isFrozen(result.cleanupErrors),true);
    assert.throws(()=>{result.originHostGroup='OTHER';});
    const formatted=formatLoadCheckResult(result);
    assert.deepEqual(formatted,{line:`STRIPE_BROWSER_LOAD_CHECK load=ORIGIN cleanup=OK components=NONE origin_reason=HOST_NOT_ALLOWED host_group=${originHostGroup}`,exitCode:1});
    assert.doesNotMatch(JSON.stringify(result)+JSON.stringify(formatted),/private-sentinel|secret|https:|stripe\.network|stripe\.com/);
    assert.equal(f.calls.filter(call=>call==='abort').length,1);
  }
});

test('host-family formatter bounds legacy, hostile and throwing metadata and reads applicable fields once',async()=>{
  const {formatLoadCheckResult}=await launcherModule();
  const base={...report('ORIGIN',['CONTEXT_CLOSE']),originReason:'HOST_NOT_ALLOWED'};
  const expected=(group:string)=>({line:`STRIPE_BROWSER_LOAD_CHECK load=ORIGIN cleanup=FAILED components=CONTEXT_CLOSE origin_reason=HOST_NOT_ALLOWED host_group=${group}`,exitCode:1});
  for(const originHostGroup of [undefined,null,'private-sentinel https://outside.invalid/',{},['OTHER'],'stripe.network'])
    assert.deepEqual(formatLoadCheckResult({...base,originHostGroup}),expected('UNKNOWN'));
  assert.deepEqual(formatLoadCheckResult({...base,get originHostGroup(){throw Error('private-sentinel');}}),expected('UNKNOWN'));
  for(const originHostGroup of ['STRIPE_COM','STRIPE_NETWORK','OTHER','UNKNOWN'])
    assert.deepEqual(formatLoadCheckResult({...base,originHostGroup}),expected(originHostGroup));
  let reasonReads=0,groupReads=0;
  assert.deepEqual(formatLoadCheckResult({...base,get originReason(){reasonReads++;return reasonReads===1?'HOST_NOT_ALLOWED':'URL_INVALID';},get originHostGroup(){groupReads++;return groupReads===1?'STRIPE_NETWORK':'private-sentinel';}}),expected('STRIPE_NETWORK'));
  assert.equal(reasonReads,1);assert.equal(groupReads,1);
  for(const value of [report('READY'),report('ORIGIN_STRIPE_COM_M'),report('ORIGIN_STRIPE_COM_OTHER'),{...report('ORIGIN'),originReason:'URL_INVALID'},{...report('ORIGIN'),originReason:'NON_HTTPS_SCHEME'},{...report('ORIGIN'),originReason:'NON_DEFAULT_PORT'},{...report('ORIGIN'),originReason:'private-sentinel'}]){
    const normal=formatLoadCheckResult(value);
    let inapplicableGroupReads=0;
    assert.deepEqual(formatLoadCheckResult({...value,get originHostGroup(){inapplicableGroupReads++;return assert.fail('inapplicable group must not be read');}}),normal);
    assert.equal(inapplicableGroupReads,0);
  }
});

test('ORIGIN cause belongs to the first failure and survives later refusal and cleanup failure',async()=>{
  const {runLoadCheck}=await launcherModule();const f=runnerFixture();
  f.context.close=async()=>{f.calls.push('context.close');throw Error('private cleanup');};
  f.page.waitForFunction=async()=>{
    for(const url of ['https://stripe.network/private-sentinel','https://stripe.com/private-sentinel','http://private-sentinel.invalid/']){
      const route:any=providerRoute(f,async()=>assert.fail('forbidden fetch'));
      const req=route.request();route.request=()=>({...req,url:()=>url});await f.route()(route);
    }
    f.listeners.pageerror!(Error('private later error'));
  };
  assert.deepEqual(await runLoadCheck(env,f.ports),{...report('ORIGIN',['CONTEXT_CLOSE']),originReason:'HOST_NOT_ALLOWED',originHostGroup:'STRIPE_NETWORK'});
});

test('ORIGIN formatter bounds legacy, hostile and throwing causes without altering the primary result',async()=>{
  const {formatLoadCheckResult}=await launcherModule();
  const unknown={line:'STRIPE_BROWSER_LOAD_CHECK load=ORIGIN cleanup=OK components=NONE origin_reason=UNKNOWN',exitCode:1};
  for(const originReason of [undefined,null,'private-sentinel https://outside.invalid/',{},['HOST_NOT_ALLOWED']]){
    assert.deepEqual(formatLoadCheckResult({...report('ORIGIN'),originReason}),unknown);
  }
  assert.deepEqual(formatLoadCheckResult({...report('ORIGIN'),get originReason(){throw Error('private-sentinel');}}),unknown);
  let reads=0;
  assert.deepEqual(formatLoadCheckResult({...report('ORIGIN'),get originReason(){reads++;return reads===1?'HOST_NOT_ALLOWED':'private-sentinel';}}),
    {line:'STRIPE_BROWSER_LOAD_CHECK load=ORIGIN cleanup=OK components=NONE origin_reason=HOST_NOT_ALLOWED host_group=UNKNOWN',exitCode:1});
  assert.equal(reads,1);
  assert.deepEqual(formatLoadCheckResult({...report('READY'),get originReason(){return assert.fail('unrelated diagnostic must not be read');}}),
    {line:'STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=OK components=NONE',exitCode:0});
});

test('origin diagnostics preserve existing authority gates, dedicated Stripe categories and default-port acceptance',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const [url,extra,expected] of [
    ['https://m.stripe.com/private',{},'ORIGIN_STRIPE_COM_M'],
    ['https://unknown.stripe.com/private',{},'ORIGIN_STRIPE_COM_OTHER'],
    ['https://js.stripe.com:8443/private',{},'ORIGIN_STRIPE_COM_OTHER'],
    ['https://user:secret@outside.invalid/',{},'AUTH'],
    ['https://outside.invalid/',{redirectedFrom:()=>({})},'REDIRECT'],
    ['https://outside.invalid/',{allHeaders:async()=>({Authorization:'secret'})},'HEADER'],
    ['https://outside.invalid/',{method:()=> 'DELETE'},'METHOD'],
    ['https://outside.invalid/',{isNavigationRequest:()=>true},'NAVIGATION'],
  ] as const){
    const f=runnerFixture();f.page.waitForFunction=async()=>{
      const route:any=providerRoute(f,async()=>assert.fail('forbidden fetch'));
      const req=route.request();route.request=()=>({...req,url:()=>url,...extra});await f.route()(route);
    };
    assert.deepEqual(await runLoadCheck(env,f.ports),report(expected));
  }
  const {classifyRequest}=await launcherModule();
  assert.equal(classifyRequest({url:'https://js.stripe.com:443/v3/',method:'GET',headers:{},navigation:false,mainFrame:false,redirected:false},new Map(),false),'PROVIDER');
});

function runnerFixture(){
  const calls:string[]=[];let routeHandler:Function;let websocket:Function;const listeners:Record<string,Function>={};
  const frame:{page:()=>any}={page:()=>page};
  const page={mainFrame:()=>frame,on:(name:string,fn:Function)=>{listeners[name]=fn;},goto:async()=>{calls.push('goto');},evaluate:async()=>{calls.push('configure');},waitForFunction:async()=>{},close:async()=>{calls.push('page.close');}};
  const context={route:async(_pattern:string,fn:Function)=>{routeHandler=fn;},routeWebSocket:async(_pattern:string,fn:Function)=>{websocket=fn;},on:(name:string,fn:Function)=>{listeners['context.'+name]=fn;},newPage:async()=>{calls.push('context.newPage');return page;},close:async()=>{calls.push('context.close');}};
  const browser={newContext:async(options:any)=>{calls.push('context');assert.deepEqual(options,{serviceWorkers:'block',acceptDownloads:false});return context;},close:async()=>{calls.push('browser.close');}};
  const ports={build:async()=>{calls.push('build');return {assets:new Map([['/stripe-browser-load-check.html',{body:'html',contentType:'text/html'}]]),dispose:async()=>{calls.push('dispose');}};},launch:async()=>{calls.push('launch');return browser;},timeoutMs:30};
  return {calls,ports,browser,context,page,listeners,route:()=>routeHandler!,websocket:()=>websocket!};
}

test('runner gates before build/launch and closes one owned context with fixed result',async()=>{
  const {runLoadCheck}=await launcherModule();const f=runnerFixture();
  assert.deepEqual(await runLoadCheck({},f.ports),report('REFUSED'));assert.deepEqual(f.calls,[]);
  assert.deepEqual(await runLoadCheck(env,f.ports),report('READY'));
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
    assert.equal(result.loadResult,({route:'API_PATH',pageerror:'ELEMENT',popup:'POPUP',download:'DOWNLOAD',websocket:'WEBSOCKET',timeout:'TIMEOUT'} as Record<string,string>)[scenario],scenario);
    assert.equal(f.calls.filter(x=>x==='context.close').length,1);assert.equal(f.calls.filter(x=>x==='browser.close').length,1);assert.equal(f.calls.filter(x=>x==='dispose').length,1);
  }
  const f=runnerFixture(),late=deferred();f.ports.launch=async()=>late.promise;
  const run=runLoadCheck(env,f.ports);await new Promise(r=>setTimeout(r,45));late.resolve(f.browser);
  assert.deepEqual(await run,report('TIMEOUT'));await new Promise(r=>setTimeout(r,0));assert.equal(f.calls.filter(x=>x==='browser.close').length,1);assert.ok(!f.calls.includes('context'));
});

test('held provider headers cannot forward after readiness retirement or timeout',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const terminal of ['ready','timeout']){
    const f=runnerFixture(),headers=deferred();let requestFinished:Promise<void>|undefined;
    f.page.waitForFunction=async()=>{
      requestFinished=f.route()({request:()=>({url:()=> 'https://js.stripe.com/v3/',method:()=> 'GET',allHeaders:()=>headers.promise,isNavigationRequest:()=>false,redirectedFrom:()=>null,frame:()=>f.page.mainFrame()}),abort:async()=>{f.calls.push('abort');},continue:async()=>{f.calls.push('continue');},fulfill:()=>assert.fail('provider fulfill')});
      if(terminal==='timeout')await new Promise(()=>{});
    };
    assert.deepEqual(await runLoadCheck(env,f.ports),report(terminal==='ready'?'READY':'TIMEOUT'));
    headers.resolve({});await requestFinished;
    assert.ok(!f.calls.includes('continue'),terminal);assert.equal(f.calls.filter(x=>x==='abort').length,1,terminal);
  }
});

test('acquisition failures retain stage and actual cleanup failures report uncertainty',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const stage of ['build','launch']){
    const f=runnerFixture();f.ports[stage as 'build']=async()=>{throw Error('private detail');};
    assert.deepEqual(await runLoadCheck(env,f.ports),report(stage.toUpperCase()));
  }
  const owned=runnerFixture();owned.context.close=async()=>{throw Error('private cleanup');};
  assert.deepEqual(await runLoadCheck(env,owned.ports),report('READY',['CONTEXT_CLOSE']));
  const late=runnerFixture(),opening=deferred();late.ports.launch=async()=>opening.promise;
  late.browser.close=async()=>{throw Error('private late cleanup');};
  const result=runLoadCheck(env,late.ports);await new Promise(resolve=>setTimeout(resolve,45));opening.resolve(late.browser);
  assert.deepEqual(await result,report('TIMEOUT',['LATE_BROWSER_CLOSE']));
});

function providerRoute(f:ReturnType<typeof runnerFixture>,fetcher:(options:any)=>Promise<any>){
  return {request:()=>({url:()=> 'https://api.stripe.com/v1/elements/sessions',method:()=> 'POST',allHeaders:async()=>({}),isNavigationRequest:()=>false,redirectedFrom:()=>null,frame:()=>f.page.mainFrame()}),
    fetch:fetcher,abort:async()=>{f.calls.push('abort');},continue:()=>assert.fail('provider forwarding must not follow redirects implicitly'),
    fulfill:async(options:any)=>{f.calls.push('fulfill');assert.ok(options.response,'fulfill validated fetched response');}};
}
function providerResponse(f:ReturnType<typeof runnerFixture>,status=200,location?:string){
  return {url:()=> 'https://api.stripe.com/v1/elements/sessions',status:()=>status,headers:()=>location?{location}:{},dispose:async()=>{f.calls.push('response.dispose');}};
}

for(const rejects of [false,true])test(`READY drains pending response disposal before destroying its request channel${rejects?' and retains an independent disposal failure':''}`,async t=>{
  const {runLoadCheck,formatLoadCheckResult}=await launcherModule();
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
  const f=runnerFixture(),disposing=deferred(),release=deferred(),headers=deferred();
  let channelClosed=false,routing:Promise<void>|undefined,heldHeaders:Promise<void>|undefined;
  const response=providerResponse(f);
  response.dispose=async()=>{
    f.calls.push('response.dispose');disposing.resolve(undefined);await release.promise;
    if(channelClosed)throw Error('request channel destroyed before response disposal');
    if(rejects)throw Error('independent private disposal failure');
    f.calls.push('response.disposed');
  };
  f.context.close=async()=>{f.calls.push('context.close');channelClosed=true;};
  f.page.waitForFunction=async()=>{
    routing=f.route()(providerRoute(f,async()=>{f.calls.push('fetch');return response;}));
    await disposing.promise;
    const held=providerRoute(f,async()=>assert.fail('headers released after retirement must not fetch'));
    const request=held.request();held.request=()=>({...request,allHeaders:()=>headers.promise});
    held.fulfill=async()=>assert.fail('headers released after retirement must not fulfill');
    heldHeaders=f.route()(held);f.calls.push('ready.observed');
  };
  const running=runLoadCheck(env,f.ports);await disposing.promise;
  await new Promise<void>(resolve=>setImmediate(resolve));
  const closedBeforeRelease=channelClosed;
  headers.resolve({});await heldHeaders;
  release.resolve(undefined);await routing;
  const result=await running;
  assert.deepEqual(result,report('READY',rejects?['RESPONSE_DISPOSE']:[]));
  assert.equal(closedBeforeRelease,false,'keep the request channel alive during the remaining original budget');
  assert.equal(formatLoadCheckResult(result).line,rejects?'STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=FAILED components=RESPONSE_DISPOSE':'STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=OK components=NONE');
  for(const call of ['fetch','fulfill','response.dispose','context.close','browser.close','dispose','abort'])assert.equal(f.calls.filter(value=>value===call).length,1,call);
  assert.ok(f.calls.indexOf('response.dispose')<f.calls.indexOf('ready.observed'),'disposal is pending when readiness is observed');
  if(!rejects){
    assert.ok(f.calls.indexOf('ready.observed')<f.calls.indexOf('response.disposed'));
    assert.ok(f.calls.indexOf('response.disposed')<f.calls.indexOf('context.close'));
  }
  t.mock.timers.tick(30);await new Promise<void>(resolve=>setImmediate(resolve));
  assert.equal(f.calls.filter(value=>value==='context.close').length,1,'a cleared drain timer cannot close again');
});

for(const stuck of ['fulfill','dispose'])test(`READY closes at the original deadline when ${stuck} needs context closure to settle`,async t=>{
  const {runLoadCheck}=await launcherModule();
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
  const f=runnerFixture(),blocked=deferred(),closed=deferred();let closeTime:number|undefined,channelClosed=false;
  const response=providerResponse(f);
  response.dispose=async()=>{
    f.calls.push('response.dispose');
    if(stuck==='dispose'){blocked.resolve(undefined);await closed.promise;}
    if(channelClosed)throw Error('request channel closed during fallback');
  };
  f.context.close=async()=>{f.calls.push('context.close');closeTime=Date.now();channelClosed=true;closed.resolve(undefined);};
  f.page.waitForFunction=async()=>{
    const route=providerRoute(f,async()=>{f.calls.push('fetch');return response;});
    if(stuck==='fulfill')route.fulfill=async()=>{f.calls.push('fulfill');blocked.resolve(undefined);await closed.promise;};
    void f.route()(route);
    await blocked.promise;t.mock.timers.setTime(1020);
  };
  const running=runLoadCheck(env,f.ports);await blocked.promise;
  await new Promise<void>(resolve=>setImmediate(resolve));
  const closedBeforeDeadline=f.calls.includes('context.close');
  t.mock.timers.tick(9);await new Promise<void>(resolve=>setImmediate(resolve));
  const closedOneMsEarly=f.calls.includes('context.close');
  t.mock.timers.tick(1);await new Promise<void>(resolve=>setImmediate(resolve));
  assert.equal(f.calls.filter(value=>value==='context.close').length,1,'fallback closes without a fresh timeout or grace');
  assert.deepEqual(await running,report('READY',['RESPONSE_DISPOSE']));
  assert.equal(closedBeforeDeadline,false);assert.equal(closedOneMsEarly,false);
  assert.equal(closeTime,1030,'only the ten remaining milliseconds belong to pre-close draining');
  for(const call of ['fetch','fulfill','response.dispose','context.close','browser.close','dispose'])assert.equal(f.calls.filter(value=>value===call).length,1,call);
});

for(const terminal of ['TIMEOUT','ORIGIN'])test(`already expired ${terminal} closes without another drain budget and preserves the first result`,async t=>{
  const {runLoadCheck}=await launcherModule();
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
  const f=runnerFixture(),disposing=deferred(),closed=deferred();let closeTime:number|undefined;
  const response=providerResponse(f);
  response.dispose=async()=>{f.calls.push('response.dispose');disposing.resolve(undefined);await closed.promise;throw Error('private channel disposal failure');};
  f.context.close=async()=>{f.calls.push('context.close');closeTime=Date.now();closed.resolve(undefined);f.listeners.pageerror!(Error('later page failure'));};
  f.page.waitForFunction=async()=>{
    void f.route()(providerRoute(f,async()=>{f.calls.push('fetch');return response;}));
    await disposing.promise;
    if(terminal==='ORIGIN'){
      const refused=providerRoute(f,async()=>assert.fail('refused origin must not fetch'));
      const request=refused.request();refused.request=()=>({...request,url:()=> 'https://outside.invalid/private'});
      refused.abort=async()=>{f.calls.push('abort');t.mock.timers.setTime(1030);};
      await f.route()(refused);
    }
    await new Promise(()=>{});
  };
  const running=runLoadCheck(env,f.ports);await disposing.promise;
  if(terminal==='TIMEOUT')t.mock.timers.tick(30);
  await new Promise<void>(resolve=>setImmediate(resolve));
  assert.equal(f.calls.filter(value=>value==='context.close').length,1,'an expired budget does not receive even a one-millisecond grace');
  assert.equal(closeTime,1030);
  assert.deepEqual(await running,terminal==='ORIGIN'?{...report('ORIGIN',['RESPONSE_DISPOSE']),originReason:'HOST_NOT_ALLOWED',originHostGroup:'OTHER'}:report('TIMEOUT',['RESPONSE_DISPOSE']));
  for(const call of ['fetch','fulfill','response.dispose','context.close','browser.close','dispose'])assert.equal(f.calls.filter(value=>value===call).length,1,call);
});

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
    assert.equal(result.loadResult,ownership==='foreign'?'NAVIGATION':'ELEMENT',ownership);
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
  assert.deepEqual(await runLoadCheck(env,f.ports),report('READY'));
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
      assert.deepEqual(await runLoadCheck(env,f.ports),report('REDIRECT'));
      assert.ok(!f.calls.includes('fulfill'));assert.equal(f.calls.filter(x=>x==='abort').length,1);assert.equal(f.calls.filter(x=>x==='response.dispose').length,1);
    }
  }
  const f=runnerFixture();f.page.waitForFunction=async()=>{await f.route()(providerRoute(f,async()=>providerResponse(f)));};
  assert.deepEqual(await runLoadCheck(env,f.ports),report('READY'));assert.equal(f.calls.filter(x=>x==='fulfill').length,1);assert.equal(f.calls.filter(x=>x==='response.dispose').length,1);
});

test('late fetched response is disposed without fulfillment after retirement or timeout',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const terminal of ['ready','timeout']){
    const f=runnerFixture(),fetching=deferred(),started=deferred();let routing:Promise<void>|undefined;
    f.page.waitForFunction=async()=>{
      routing=f.route()(providerRoute(f,async()=>{started.resolve(undefined);return fetching.promise;}));
      if(terminal==='timeout')await new Promise(()=>{});
    };
    let settled=false;const result=runLoadCheck(env,f.ports).then((value:any)=>{settled=true;return value;});
    assert.equal(await Promise.race([started.promise.then(()=>true),result.then(()=>false)]),true,'expected non-following provider fetch to start before run settles');
    await new Promise(resolve=>setTimeout(resolve,terminal==='timeout'?45:0));
    assert.equal(settled,false,'do not return with an unresolved owned provider transport');
    fetching.resolve(providerResponse(f));await routing;
    assert.deepEqual(await result,report(terminal==='timeout'?'TIMEOUT':'READY'));
    assert.ok(!f.calls.includes('fulfill'));assert.equal(f.calls.filter(x=>x==='abort').length,1);assert.equal(f.calls.filter(x=>x==='response.dispose').length,1);
  }
});

test('provider fetch failure is safe and response disposal failure reports cleanup uncertainty',async()=>{
  const {runLoadCheck}=await launcherModule();
  const failed=runnerFixture();failed.page.waitForFunction=async()=>{await failed.route()(providerRoute(failed,async()=>{throw Error('private transport detail');}));};
  assert.deepEqual(await runLoadCheck(env,failed.ports),report('ELEMENT'));assert.ok(!failed.calls.includes('fulfill'));
  const disposal=runnerFixture();const response=providerResponse(disposal);response.dispose=async()=>{throw Error('private disposal detail');};
  disposal.page.waitForFunction=async()=>{await disposal.route()(providerRoute(disposal,async()=>response));};
  assert.deepEqual(await runLoadCheck(env,disposal.ports),report('NOT_CONFIRMED',['RESPONSE_DISPOSE']));
});

test('formatter emits fixed safe diagnostics and exit status for both channels',async()=>{
  const {formatLoadCheckResult}=await launcherModule();
  for(const [value,line,exitCode] of [
    [report('READY'),'STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=OK components=NONE',0],
    [report('REFUSED'),'STRIPE_BROWSER_LOAD_CHECK load=REFUSED cleanup=OK components=NONE',1],
    [report('READY',['CONTEXT_CLOSE','BUILD_DISPOSE']),'STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=FAILED components=CONTEXT_CLOSE,BUILD_DISPOSE',1],
    [report('private https://sentinel.test/key',['private /sentinel/path']),'STRIPE_BROWSER_LOAD_CHECK load=UNKNOWN cleanup=FAILED components=UNKNOWN',1],
  ] as const)assert.deepEqual(formatLoadCheckResult(value),{line,exitCode});
});

test('primary failure and ready retain every owned cleanup category in fixed order',async()=>{
  const {runLoadCheck,formatLoadCheckResult}=await launcherModule();
  for(const primary of ['READY','API_PATH','TIMEOUT']){
    const f=runnerFixture();const build=await f.ports.build();
    f.ports.build=async()=>({...build,dispose:async()=>{f.calls.push('dispose');throw Error('private build');}});
    f.context.close=async()=>{f.calls.push('context.close');throw Error('private context');};
    f.browser.close=async()=>{f.calls.push('browser.close');throw Error('private browser');};
    f.page.waitForFunction=async()=>{
      if(primary==='API_PATH'){
        const route:any=providerRoute(f,async()=>assert.fail('forbidden fetch'));
        const request=route.request();route.request=()=>({...request,url:()=> 'https://api.stripe.com/v1/payment_intents'});
        await f.route()(route);f.listeners.pageerror!(Error('later error'));
      }
      if(primary==='TIMEOUT')await new Promise(()=>{});
    };
    const result=await runLoadCheck(env,f.ports);
    assert.deepEqual(result,report(primary,['CONTEXT_CLOSE','BROWSER_CLOSE','BUILD_DISPOSE']));
    assert.equal(formatLoadCheckResult(result).exitCode,1);
    for(const call of ['context.close','browser.close','dispose'])assert.equal(f.calls.filter(x=>x===call).length,1);
  }
});

test('every late acquisition retires exactly once, waits disposal and never starts next stage',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const stage of ['build','browser','context','page'])for(const rejects of [false,true]){
    const f=runnerFixture(),opening=deferred(),started=deferred(),disposing=deferred(),release=deferred();let disposals=0;
    const lateDispose=async()=>{disposals++;disposing.resolve(undefined);await release.promise;if(rejects)throw Error('private late sentinel');};
    const codes:Record<string,string>={build:'LATE_BUILD_DISPOSE',browser:'LATE_BROWSER_CLOSE',context:'LATE_CONTEXT_CLOSE',page:'LATE_PAGE_CLOSE'};
    let value:any;
    if(stage==='build'){value={assets:new Map(),dispose:lateDispose};f.ports.build=async()=>{started.resolve(undefined);return opening.promise;};}
    if(stage==='browser'){value={...f.browser,close:lateDispose};f.ports.launch=async()=>{started.resolve(undefined);return opening.promise;};}
    if(stage==='context'){value={...f.context,close:lateDispose};f.browser.newContext=async()=>{started.resolve(undefined);return opening.promise;};}
    if(stage==='page'){value={...f.page,close:lateDispose};f.context.newPage=async()=>{started.resolve(undefined);return opening.promise;};}
    let settled=false;const running=runLoadCheck(env,f.ports).then((r:any)=>{settled=true;return r;});
    await started.promise;await new Promise(r=>setTimeout(r,45));opening.resolve(value);await disposing.promise;
    assert.equal(settled,false);release.resolve(undefined);
    assert.deepEqual(await running,report('TIMEOUT',rejects?[codes[stage]!]:[]));assert.equal(disposals,1);
    assert.ok(!f.calls.includes(stage==='build'?'launch':stage==='browser'?'context':stage==='context'?'context.newPage':'goto'));
  }
});

test('internal build disposal callback preserves BUILD and deduplicates finite diagnostics',async()=>{
  const {runLoadCheck}=await launcherModule();const f=runnerFixture();
  f.ports.build=async(...args:any[])=>{args[1]('BUILD_DISPOSE');args[1]('BUILD_DISPOSE');throw Error('private build sentinel');};
  assert.deepEqual(await runLoadCheck(env,f.ports),report('BUILD',['BUILD_DISPOSE']));
  assert.ok(!f.calls.includes('launch'));
});

test('response disposal before ready stops readiness; after failure or retirement preserves terminal outcome',async()=>{
  const {runLoadCheck,formatLoadCheckResult}=await launcherModule();
  for(const terminal of ['NOT_CONFIRMED','READY','API_PATH','TIMEOUT']){
    const f=runnerFixture(),fetching=deferred(),started=deferred();const response=providerResponse(f);
    response.dispose=async()=>{f.calls.push('response.dispose');throw Error('private https://sentinel.test/key /sentinel/path');};
    f.page.waitForFunction=async()=>{
      const routing=f.route()(providerRoute(f,async()=>{started.resolve(undefined);return terminal==='NOT_CONFIRMED'?response:fetching.promise;}));
      if(terminal==='NOT_CONFIRMED'){await routing;await new Promise(()=>{});}
      if(terminal==='API_PATH'){
        const route:any=providerRoute(f,async()=>assert.fail('forbidden fetch'));const req=route.request();route.request=()=>({...req,url:()=> 'https://api.stripe.com/v1/payment_intents'});await f.route()(route);await new Promise(()=>{});
      }
      if(terminal==='TIMEOUT')await new Promise(()=>{});
    };
    const running=runLoadCheck(env,f.ports);await started.promise;
    if(terminal!=='NOT_CONFIRMED'){await new Promise(r=>setTimeout(r,terminal==='TIMEOUT'?45:0));fetching.resolve(response);}
    const result=await running;assert.deepEqual(result,report(terminal,['RESPONSE_DISPOSE']));
    assert.equal(f.calls.filter(x=>x==='response.dispose').length,1);
    if(terminal!=='NOT_CONFIRMED')assert.ok(!f.calls.includes('fulfill'));
    assert.ok(!JSON.stringify(formatLoadCheckResult(result)).includes('sentinel'));
  }
});

test('returned report is frozen and late headers and page events cannot alter snapshot',async()=>{
  const {runLoadCheck}=await launcherModule();const f=runnerFixture(),headers=deferred();let routing:Promise<void>|undefined;
  f.page.waitForFunction=async()=>{const route:any=providerRoute(f,async()=>assert.fail('late forwarding'));const req=route.request();route.request=()=>({...req,allHeaders:()=>headers.promise});routing=f.route()(route);};
  const result=await runLoadCheck(env,f.ports);assert.deepEqual(result,report('READY'));
  assert.equal(Object.isFrozen(result),true);assert.equal(Object.isFrozen(result.cleanupErrors),true);
  headers.resolve({Authorization:'private sentinel'});await routing;f.listeners.pageerror!(Error('private sentinel'));f.listeners.close!();
  assert.deepEqual(result,report('READY'));assert.equal(f.calls.filter(x=>x==='abort').length,1);
});

test('extra page and download cleanup is awaited, classified and deduplicated',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const kind of ['page','download']){
    const f=runnerFixture(),started=deferred(),release=deferred();let disposals=0;
    const dispose=async()=>{disposals++;started.resolve(undefined);await release.promise;throw Error('private event cleanup');};
    f.page.waitForFunction=async()=>{
      if(kind==='page'){
        f.listeners['context.page']!(f.page);
        const extra={close:dispose};f.listeners['context.page']!(extra);f.listeners.popup!(extra);
      }else f.listeners.download!({cancel:dispose});
      await new Promise(()=>{});
    };
    let settled=false;const running=runLoadCheck(env,f.ports).then((r:any)=>{settled=true;return r;});await started.promise;
    await new Promise(r=>setTimeout(r,0));assert.equal(settled,false);release.resolve(undefined);
    assert.deepEqual(await running,report(kind==='page'?'POPUP':'DOWNLOAD',[kind==='page'?'EXTRA_PAGE_CLOSE':'DOWNLOAD_CANCEL']));assert.equal(disposals,1);
  }
});

test('first terminal observation survives later events while final cleanup is still pending',async()=>{
  const {runLoadCheck}=await launcherModule();
  for(const terminal of ['READY','API_PATH']){
    const f=runnerFixture(),closing=deferred(),release=deferred();
    f.context.close=async()=>{closing.resolve(undefined);await release.promise;throw Error('private context cleanup');};
    f.page.waitForFunction=async()=>{
      if(terminal==='API_PATH'){
        const route:any=providerRoute(f,async()=>assert.fail('forbidden fetch'));const req=route.request();route.request=()=>({...req,url:()=> 'https://api.stripe.com/v1/payment_intents'});await f.route()(route);await new Promise(()=>{});
      }
    };
    const running=runLoadCheck(env,f.ports);await closing.promise;f.listeners.pageerror!(Error('later terminal'));f.listeners.crash!();release.resolve(undefined);
    assert.deepEqual(await running,report(terminal,['CONTEXT_CLOSE']));
  }
});

test('formatter rejects sparse diagnostics',async()=>{
  const {formatLoadCheckResult}=await launcherModule();
  assert.deepEqual(formatLoadCheckResult(report('READY',Array(1))),{line:'STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=FAILED components=UNKNOWN',exitCode:1});
});

test('formatter reads each candidate only once',async()=>{
  const {formatLoadCheckResult}=await launcherModule();
  let loadReads=0,cleanupReads=0;
  const changing={get loadResult(){return ++loadReads===1?'READY':'private sentinel';},get cleanupErrors(){return ++cleanupReads===1?[]:['private sentinel'];}};
  assert.deepEqual(formatLoadCheckResult(changing),{line:'STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=OK components=NONE',exitCode:0});
  assert.equal(loadReads,1);assert.equal(cleanupReads,1);
});

test('formatter renders throwing diagnostics as finite failure',async()=>{
  const {formatLoadCheckResult}=await launcherModule();
  const unknown={line:'STRIPE_BROWSER_LOAD_CHECK load=UNKNOWN cleanup=FAILED components=UNKNOWN',exitCode:1};
  for(const value of [
    {get loadResult(){throw Error('private sentinel');},cleanupErrors:[]},
    {loadResult:'READY',get cleanupErrors(){throw Error('private sentinel');}},
    {loadResult:'READY',cleanupErrors:new Proxy([],{get(){throw Error('private sentinel');}})},
  ])assert.deepEqual(formatLoadCheckResult(value),unknown);
});

test('formatter reads indexed cleanup entries without consulting a custom iterator',async()=>{
  const {formatLoadCheckResult}=await launcherModule();
  const errors=['CONTEXT_CLOSE'];
  Object.defineProperty(errors,Symbol.iterator,{value:function*(){}});
  const expected={line:'STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=FAILED components=CONTEXT_CLOSE',exitCode:1};
  assert.deepEqual(formatLoadCheckResult(report('READY',errors)),expected);
  const unread=['CONTEXT_CLOSE'];
  Object.defineProperty(unread,Symbol.iterator,{get(){assert.fail('cleanup array iterator must not be read');}});
  assert.deepEqual(formatLoadCheckResult(report('READY',unread)),expected);
});
