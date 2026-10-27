import test,{after,before,describe} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
import {Worker,isMainThread,parentPort,workerData} from 'node:worker_threads';
const root=fileURLToPath(new URL('../..',import.meta.url));
// These literals catch wrong phase/status classification and accidental raw-data exposure.
const loadFailures:Record<string,string>={
  'session-forbidden':'SESSION_HTTP_FORBIDDEN','session-gone':'SESSION_HTTP_GONE','session-error':'SESSION_HTTP_ERROR',
  'session-fetch-rejected':'SESSION_HTTP_UNAVAILABLE','session-body-rejected':'SESSION_HTTP_UNAVAILABLE',
  'session-http-timeout':'SESSION_HTTP_UNAVAILABLE','session-body-http-timeout':'SESSION_HTTP_UNAVAILABLE',
  'session-json-invalid':'SESSION_JSON_INVALID','session-oversized':'SESSION_INVALID','session-contract-invalid':'SESSION_INVALID',
  'session-scenario-invalid':'SESSION_INVALID','session-scenario-empty':'SESSION_INVALID','session-scenario-duplicate':'SESSION_INVALID',
  'session-scenario-throws':'SESSION_INVALID','session-scenario-late':'SESSION_HTTP_UNAVAILABLE',
  malformed:'SESSION_INVALID','mixed-session':'SESSION_INVALID',expired:'SESSION_INVALID',instrumentation:'AUTHORIZATION_BINDING_FAILED',
  'widget-registration':'WIDGET_SETUP_FAILED','widget-construction':'WIDGET_SETUP_FAILED','widget-configuration':'WIDGET_SETUP_FAILED',
  'mount-observer-construction':'WIDGET_MOUNT_FAILED','mount-observer-observe':'WIDGET_MOUNT_FAILED','mount-append':'WIDGET_MOUNT_FAILED','mount-inspection':'WIDGET_MOUNT_FAILED',
};
export async function exercise(entry:string,scenario:string,automatic=false):Promise<void>{
  const worker=new Worker(new URL(import.meta.url),{workerData:{entry,scenario,automatic}});
  try{await new Promise<void>((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('offline_manual_payment_timeout')),10000);worker.once('message',value=>{clearTimeout(timer);assert.equal(value,'PASS');resolve();});worker.once('error',error=>{clearTimeout(timer);reject(error);});});}finally{await worker.terminate();}
}
if(isMainThread&&process.argv[1]===fileURLToPath(import.meta.url))describe('real SDK manual payment page spends one permit and never retries after any outcome',{concurrency:1},()=>{
  let directory='',entry='';
  const bundle=async(mutation='control')=>{
    const {build}=await import('vite');
    const output=join(directory,mutation);
    await build({root,configFile:false,logLevel:'silent',resolve:{alias:{'@generic-payments/sdk':root+'/../../frontend/sdk/src/index.ts'}},
      plugins:[{name:'capture-real-handle',enforce:'pre',transform(code,id){
        if(id.endsWith('/stripe-driver.ts'))code=code.replace('return {\n    async confirm()', 'return globalThis.__manualHandle = {\n    async confirm()');
        if(mutation==='mixed'&&id.endsWith('/manual-payment.ts'))code=code.replace("from '../../../frontend/sdk/src/diagnostics/manual-payment-authorization'", "from '../../../frontend/sdk/src/diagnostics/manual-payment-authorization?separate-graph'");
        if(mutation==='start-latch'&&id.endsWith('/manual-payment.ts'))code=code.replace('if(started||!active())return;','if(!active())return;').replace('started=true;start.disabled=true;','started=true;');
        return code;
      }}],build:{outDir:output,lib:{entry:root+'/src/manual-payment.ts',formats:['es'],fileName:()=> 'manual.mjs'}}});return pathToFileURL(join(output,'manual.mjs')).href;
  };
  before(async()=>{directory=await mkdtemp(join(tmpdir(),'manual-payment-source-'));entry=await bundle();});
  after(async()=>{if(directory)await rm(directory,{recursive:true,force:true});});
  for(const scenario of ['no-click','success','ordinary-ready','validation','grant-rejected','grant-closed','grant-malformed','late-grant','pagehide','close','expired','mixed-session','malformed','late-session','expired-start','programmatic','enter','detached','session-http-timeout','session-body-http-timeout','permit-http-timeout','permit-body-http-timeout',...['get','post','refresh'].flatMap(stage=>['headers','body','pagehide','detached','late'].map(phase=>`sdk-${stage}-${phase}`)),'sdk-post-host','sdk-refresh-host'])test(scenario,()=>exercise(entry,scenario));
  for(const scenario of ['success','failed','unknown','expired','checking','processing','requires_action','pagehide','close','abort','detached','timeout'])test('3ds-challenge-'+scenario,()=>exercise(entry,'3ds-challenge-'+scenario));
  for(const sequence of ['repeated-action','processing-action'])for(const end of ['close','expiry'])test(`3ds-challenge-${sequence}-${end}`,()=>exercise(entry,`3ds-challenge-${sequence}-${end}`));
  for(const scenario of ['no-click','success','validation','grant-rejected','grant-closed','grant-malformed','late-grant','permit-http-timeout','permit-body-http-timeout'])test('3ds-'+scenario,()=>exercise(entry,'3ds-'+scenario));
  for(const scenario of Object.keys(loadFailures).filter(name=>!['malformed','mixed-session','expired','instrumentation','session-http-timeout','session-body-http-timeout'].includes(name)))test(scenario,()=>exercise(entry,scenario));
  test('missing module instrumentation fails before Pay',async()=>{const mixed=await bundle('mixed');await exercise(mixed,'instrumentation');});
  test('behavioral start latch mutation is detected',async()=>{
    const mutant=await bundle('start-latch');
    await assert.rejects(exercise(mutant,'success'),error=>error instanceof Error&&error.message.includes('one_shot_session_exchange'));
  });
});
if(!isMainThread){
  const require=createRequire(new URL('../../../../frontend/sdk/package.json',import.meta.url));const {JSDOM}=require('jsdom');
  const selected3DS=workerData.scenario.startsWith('3ds-'),scenario=selected3DS?workerData.scenario.slice(4):workerData.scenario;
  const challenge=selected3DS&&scenario.startsWith('challenge-');
  const actionReady=challenge&&(scenario.startsWith('challenge-repeated-action-')||scenario.startsWith('challenge-processing-action-'));
  const dom=new JSDOM(workerData.automatic?'<main id="manual-payment"></main>':'<main id="fixture"></main>',{url:'http://127.0.0.1:14174/manual-payment.html'}),window=dom.window;
  for(const name of ['window','document','HTMLElement','HTMLButtonElement','ShadowRoot','customElements','sessionStorage','location','navigator','CustomEvent','MutationObserver','getComputedStyle'])Object.defineProperty(globalThis,name,{value:name==='window'?window:window[name],configurable:true});
  // No resource loader is installed; all HTTP and WS boundaries are replaced or denied.
  const deniedNetwork=class{constructor(){throw Error('external_network_denied');}};
  for(const scope of [globalThis,window])for(const name of ['WebSocket','XMLHttpRequest'])Object.defineProperty(scope,name,{value:deniedNetwork,configurable:true});
  Object.defineProperty(window.navigator,'sendBeacon',{value:()=>{throw Error('external_network_denied');},configurable:true});
  const actualNow=Date.now;let now=1000000,serial=0;Date.now=()=>now;const timers=new Map<number,{at:number;callback:()=>void}>();
  Object.defineProperty(globalThis,'setTimeout',{configurable:true,value:(callback:()=>void,ms=0)=>{const id=++serial;timers.set(id,{at:now+ms,callback});return id;}});Object.defineProperty(globalThis,'clearTimeout',{configurable:true,value:(id:number)=>timers.delete(id)});
  const tick=()=>new Promise<void>(resolve=>setImmediate(resolve));const until=async(predicate:()=>boolean)=>{for(let n=0;n<100;n++){if(predicate())return;await tick();}assert.fail('manual payment did not settle: '+scenario);};
  const advance=async(target:number)=>{while(true){const next=[...timers].filter(([,v])=>v.at<=target).sort((a,b)=>a[1].at-b[1].at)[0];if(!next)break;now=next[1].at;timers.delete(next[0]);next[1].callback();await tick();}now=target;await tick();};
  const checkout='11111111-1111-4111-8111-111111111111',payment='22222222-2222-4222-8222-222222222222',api='http://127.0.0.1:18080',token='x'.repeat(43),secret='pi_fixture_secret_sensitive',expiresAt='2099-01-01T00:00:00Z';
  const canary='private_cookie_header_body_url_session_key_token_error_stack_card_canary';
  const sensitiveError=()=>{const error=new Error(canary);Object.defineProperties(error,{status:{get(){assert.fail('caught_error_status_read');}},code:{get(){assert.fail('caught_error_code_read');}}});return error;};
  const price={priceId:'33333333-3333-4333-8333-333333333333',priceKey:'test-price',version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'};
  const session={version:2,apiBaseUrl:api,siteKey:'test-site',priceKey:'test-price',checkoutId:checkout,checkoutToken:token,expiresAt,providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId:checkout,paymentId:payment,amountMinor:2000,currency:'EUR',price,status:'REQUIRES_PAYMENT_METHOD',expiresAt}};
  // Count throughout the lifetime; never retain or print credential-bearing writes.
  let credentialWrites=0;const originalSet=window.Storage.prototype.setItem;
  window.Storage.prototype.setItem=function(key:string,value:string){if(value.includes(token)||value.includes(secret))credentialWrites++;return originalSet.call(this,key,value);};
  let releaseSdk:((value:Response)=>void)|undefined,releaseSdkBody:((value:string)=>void)|undefined,sdkSignal:AbortSignal|undefined,sdkReply:unknown;
  const calls:string[]=[],listeners=new Map<string,()=>void>();let forbiddenReads=0,mounts=0,destroys=0,resumes=0,getCalls=0,releaseGrant:((value:Response)=>void)|undefined,releaseSession:((value:Response)=>void)|undefined,releaseBody:((value:string)=>void)|undefined,httpSignal:AbortSignal|undefined;
  const frame=window.document.createElement('iframe');for(const key of ['src','contentWindow','contentDocument'])Object.defineProperty(frame,key,{get(){forbiddenReads++;throw Error('forbidden_card_read');}});
  const element={on:(name:string,callback:()=>void)=>{listeners.set(name,callback);return element;},off:(name:string)=>{listeners.delete(name);return element;},mount:(host:HTMLElement)=>{mounts++;host.append(frame);listeners.get('ready')?.();},destroy:()=>{destroys++;frame.remove();},focus:()=>{}};
  let completeChallenge:((value:object)=>void)|undefined,releaseWatch:(()=>void)|undefined;
  const fakeStripe={confirmCalls:0,elements:()=>({create:()=>element,update:()=>{}}),confirmPayment:async()=>{fakeStripe.confirmCalls++;if(challenge)return new Promise<object>(resolve=>{completeChallenge=resolve;});return scenario==='validation'?{error:{type:'validation_error'}}:{};}};
  Object.defineProperty(window,'Stripe',{value:Object.assign(()=>fakeStripe,{version:'endive'})});
  globalThis.fetch=async(input,init)=>{
    const url=new URL(String(input),window.location.href);calls.push(url.pathname);
    if(url.origin===window.location.origin){assert.equal(init?.method,'POST');assert.equal(init?.body,'{}');assert.equal(init?.credentials,'same-origin');assert.equal(init?.cache,'no-store');
      if(url.pathname==='/manual-payment-session'){
        if(scenario.startsWith('session-scenario-')){
          const response=Response.json(session,{headers:{'X-Payment-Scenario':scenario==='session-scenario-empty'?'':scenario==='session-scenario-duplicate'?'3DS, 3DS':'3ds'}});
          if(scenario==='session-scenario-throws'||scenario==='session-scenario-late')Object.defineProperty(response,'headers',{get(){if(scenario==='session-scenario-throws')throw sensitiveError();now=1010000;return new Headers();}});
          return response;
        }
        if(['session-forbidden','session-gone','session-error'].includes(scenario))return new Response(canary,{status:scenario==='session-forbidden'?403:scenario==='session-gone'?410:503});
        if(scenario==='session-fetch-rejected')throw sensitiveError();
        if(scenario==='session-body-rejected'){const response=new Response(canary);response.text=async()=>{throw sensitiveError();};return response;}
        if(scenario==='session-json-invalid')return new Response('{'+canary);
        if(scenario==='session-oversized')return new Response(canary.repeat(300));
        if(scenario==='session-contract-invalid')return Response.json({...session,checkoutToken:canary,state:{...session.state,amountMinor:-1}});
        if(scenario==='late-session'||scenario==='session-http-timeout'){httpSignal=init?.signal??undefined;return new Promise<Response>(resolve=>{releaseSession=resolve;});}
        if(scenario==='session-body-http-timeout'){httpSignal=init?.signal??undefined;return new Promise<Response>(resolve=>{releaseSession=()=>resolve({ok:true,headers:new Headers(),text:()=>new Promise<string>(yes=>{releaseBody=yes;})} as Response);});}
        if(scenario==='malformed')return Response.json({...session,extra:secret});if(scenario==='mixed-session')return Response.json({...session,providerType:'FAKE'});
        if(scenario==='expired')return Response.json({...session,expiresAt:'1970-01-01T00:00:01Z',state:{...session.state,expiresAt:'1970-01-01T00:00:01Z'}});return Response.json(session,selected3DS?{headers:{'X-Payment-Scenario':'3DS'}}:undefined);
      }
      assert.equal(url.pathname,'/manual-payment-confirmation');if(scenario==='grant-rejected')throw Error(secret);if(scenario==='grant-closed')return new Response(null,{status:410});if(scenario==='grant-malformed')return Response.json({authorized:true,extra:true});
      if(scenario==='late-grant'||scenario==='permit-http-timeout'){httpSignal=init?.signal??undefined;return new Promise<Response>(resolve=>{releaseGrant=resolve;});}
      if(scenario==='permit-body-http-timeout'){httpSignal=init?.signal??undefined;return new Promise<Response>(resolve=>{releaseGrant=()=>resolve({ok:true,text:()=>new Promise<string>(yes=>{releaseBody=yes;})} as Response);});}return Response.json({authorized:true});
    }
    assert.equal(url.origin,api,'all external HTTP denied');assert.equal(url.pathname.startsWith('/api/v1/public/checkouts/'+checkout),true);assert.equal(init?.headers instanceof Headers?init.headers.get('Authorization'):(init?.headers as Record<string,string>)?.Authorization,'Bearer '+token);
    const preparing=url.pathname.endsWith('/client-session');if(!preparing)getCalls++;
    const challengeStatus:Record<string,string>={'challenge-failed':'FAILED','challenge-unknown':'UNKNOWN','challenge-expired':'EXPIRED','challenge-checking':'CREATED','challenge-processing':'PROCESSING','challenge-requires_action':'REQUIRES_ACTION'};
    const reply=preparing?{checkout_id:checkout,payment_id:payment,attempt_id:'44444444-4444-4444-8444-444444444444',provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',publishable_key:'pk_test_fixture',client_secret:secret,expires_at:expiresAt,return_url:'http://127.0.0.1:14174/payment-return.html'}:{checkout_id:checkout,payment_id:payment,amount_minor:2000,currency:'EUR',price:{price_id:price.priceId,price_key:price.priceKey,version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE'},provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:fakeStripe.confirmCalls&&!['validation','ordinary-ready'].includes(scenario)?(actionReady?(scenario.startsWith('challenge-processing-action-')?'PROCESSING':'REQUIRES_ACTION'):challengeStatus[scenario]??'SUCCEEDED'):'REQUIRES_PAYMENT_METHOD',expires_at:expiresAt};
    if(challenge&&!preparing&&getCalls>2)return new Promise<Response>(resolve=>{releaseWatch=()=>resolve(Response.json({...reply,status:actionReady?'REQUIRES_ACTION':'SUCCEEDED'}));});
    if(scenario.startsWith(`sdk-${preparing?'post':getCalls===1?'get':'refresh'}-`)){
      sdkSignal=init?.signal??undefined;sdkReply=reply;
      return new Promise<Response>(resolve=>{releaseSdk=scenario.endsWith('-body')?()=>resolve({ok:true,status:200,text:()=>new Promise<string>(yes=>{releaseSdkBody=yes;})} as Response):resolve;});
    }
    return Response.json(reply);
  };
  window.fetch=globalThis.fetch;
  // Fault only browser/platform boundaries; page orchestration and SDK authority stay real.
  let failedWidget:HTMLElement|undefined;
  const define=window.customElements.define.bind(window.customElements);
  window.customElements.define=((name:string,constructor:CustomElementConstructor,options?:ElementDefinitionOptions)=>{
    if(name==='generic-payment-widget'&&scenario==='widget-registration')throw sensitiveError();
    return define(name,constructor,options);
  }) as typeof window.customElements.define;
  const attachShadow=window.HTMLElement.prototype.attachShadow;
  window.HTMLElement.prototype.attachShadow=function(options:ShadowRootInit){
    if(this.localName==='generic-payment-widget'){failedWidget=this;if(scenario==='widget-construction')throw sensitiveError();}
    return attachShadow.call(this,options);
  };
  const setAttribute=window.HTMLElement.prototype.setAttribute;
  window.HTMLElement.prototype.setAttribute=function(name:string,value:string){if(this.localName==='generic-payment-widget'&&name==='api-base-url'&&scenario==='widget-configuration')throw sensitiveError();return setAttribute.call(this,name,value);};
  const NativeObserver=window.MutationObserver;
  Object.defineProperty(globalThis,'MutationObserver',{configurable:true,value:class extends NativeObserver{
    constructor(callback:MutationCallback){if(scenario==='mount-observer-construction')throw sensitiveError();super(callback);}
    observe(target:Node,options?:MutationObserverInit){if(scenario==='mount-observer-observe')throw sensitiveError();return super.observe(target,options);}
  }});
  const append=window.HTMLElement.prototype.append;
  window.HTMLElement.prototype.append=function(...nodes:(Node|string)[]){if(scenario==='mount-append'&&nodes.some(node=>typeof node!=='string'&&(node as Element).localName==='generic-payment-widget'))throw sensitiveError();return append.apply(this,nodes);};
  const querySelectorAll=window.ShadowRoot.prototype.querySelectorAll;
  window.ShadowRoot.prototype.querySelectorAll=function(selectors:string){if(scenario==='mount-inspection'&&selectors==='button')throw sensitiveError();return querySelectorAll.call(this,selectors);};
  try{
    const module=await import(workerData.entry),root=window.document.querySelector(workerData.automatic?'#manual-payment':'#fixture'),pageController=new AbortController();const page=workerData.automatic?undefined:module.mountManualPaymentPage(root,{signal:pageController.signal});
    const start=root.querySelector('button');assert.ok(start);await tick();assert.equal(calls.length,0);
    if(scenario==='no-click'||scenario==='late-session')assert.doesNotMatch(root.textContent,/4242|3155/,'card_instructions_wait_for_protected_session');
    if(scenario!=='no-click'){
      if(scenario==='expired-start')now=1300000;start.click();start.click();
      assert.equal(calls.filter(path=>path==='/manual-payment-session').length,scenario==='expired-start'?0:1,'one_shot_session_exchange');
      if(scenario.startsWith('sdk-')){
        if(scenario.startsWith('sdk-refresh-')){await until(()=>root.querySelector('generic-payment-widget')?.state==='ready');root.querySelector('generic-payment-widget').shadowRoot.querySelector('button[aria-label="Pay"]').click();}
        await until(()=>!!releaseSdk);const before=calls.length;
        if(scenario.endsWith('-body')){await advance(1008000);releaseSdk!(Response.json(sdkReply));await until(()=>!!releaseSdkBody);}
        if(scenario.endsWith('-pagehide'))window.dispatchEvent(new window.Event('pagehide'));
        else if(scenario.endsWith('-detached'))root.querySelector('generic-payment-widget').remove();
        else if(scenario.endsWith('-host'))root.querySelector('generic-payment-widget > div').replaceWith(document.createElement('div'));
        else if(scenario.endsWith('-late'))now=1011000; // Delayed event loop: completion checks must also enforce the deadline.
        else await advance(1010000);
        if(!scenario.endsWith('-late')&&!scenario.endsWith('-host'))assert.equal(sdkSignal?.aborted,true,'sdk_http_or_lifetime_abort');
        if(!scenario.endsWith('-host'))now=1011000;releaseSdk!(Response.json(sdkReply));releaseSdkBody?.(JSON.stringify(sdkReply));await tick();await tick();
        assert.equal(calls.length,before,'late_sdk_completion_cannot_start_next_capability');
        assert.equal(mounts,scenario.startsWith('sdk-refresh-')?1:0,'late_sdk_completion_cannot_mount_provider');
        assert.equal(fakeStripe.confirmCalls,scenario.startsWith('sdk-refresh-')?1:0);
        await until(()=>root.querySelector('generic-payment-widget')===null);
        const captured=(globalThis as typeof globalThis&{__manualHandle?:{confirm():Promise<unknown>}}).__manualHandle;if(captured)await assert.rejects(captured.confirm());
        start.click();assert.equal(calls.length,before);
      }
      else if(scenario.startsWith('session-')&&scenario.endsWith('http-timeout')){
        await until(()=>!!releaseSession||!!releaseBody);if(scenario==='session-body-http-timeout'){await advance(1008000);releaseSession!(Response.json(session));await until(()=>!!releaseBody);}await advance(1010000);assert.equal(httpSignal?.aborted,true,'session_http_10s_abort');assert.match(root.querySelector('[role="status"]').textContent,/Закройте/);await advance(1016000);
        releaseSession?.(Response.json(session));releaseBody?.(JSON.stringify(session));await tick();start.click();assert.equal(mounts,0);assert.equal(calls.filter(path=>path==='/manual-payment-session').length,1);assert.equal(fakeStripe.confirmCalls,0);
      }
      else if(loadFailures[scenario]||scenario==='expired-start'){await until(()=>root.querySelector('[role="status"]').textContent.includes('Закройте'));assert.equal(fakeStripe.confirmCalls,0);assert.equal(mounts,0);}
      else if(scenario==='late-session'){await until(()=>!!releaseSession);assert.doesNotMatch(root.textContent,/4242|3155/,'pending_session_keeps_neutral_instructions');await advance(1300000);releaseSession!(Response.json(session));await tick();assert.equal(mounts,0);}
      else{
        await until(()=>root.querySelector('generic-payment-widget')?.state==='ready');const widget=root.querySelector('generic-payment-widget');assert.equal(mounts,1);assert.equal(getCalls,1);
        assert.equal(widget.isConnected,true,'initial_ready_keeps_form_connected');assert.equal(frame.isConnected,true);assert.equal(destroys,0);
        if(!challenge)assert.match(root.textContent,selected3DS?/4000 0025 0000 3155/:/4242 4242 4242 4242/,'trusted_scenario_selects_card');
        if(selected3DS&&!challenge){assert.doesNotMatch(root.textContent,/4242/);assert.match(root.textContent,/45/);}
        assert.equal(calls.filter(path=>path.endsWith('/client-session')).length,1);resumes=mounts;
        const pay=widget.shadowRoot.querySelector('button[aria-label="Pay"]');assert.ok(pay);const captured=(globalThis as typeof globalThis&{__manualHandle:{confirm():Promise<unknown>}}).__manualHandle;
        if(!workerData.automatic)assert.ok(captured,'real_driver_handle_was_captured');
        if(challenge){
          pay.click();await until(()=>!!completeChallenge);
          assert.equal(widget.hidden,false,'human_challenge_remains_visible');assert.equal(widget.isConnected,true);assert.equal(frame.isConnected,true);assert.equal(destroys,0);
          const rejectRepeat=async()=>{pay.disabled=false;pay.click();pay.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Enter',bubbles:true,composed:true}));if(captured)await assert.rejects(captured.confirm());await tick();assert.equal(fakeStripe.confirmCalls,1,'one_provider_confirmation');assert.equal(calls.filter(path=>path==='/manual-payment-confirmation').length,1,'one_durable_permit');};
          await rejectRepeat();
          if(scenario==='challenge-pagehide')window.dispatchEvent(new window.Event('pagehide'));
          else if(scenario==='challenge-close')page?.close();
          else if(scenario==='challenge-abort')pageController.abort();
          else if(scenario==='challenge-detached')widget.remove();
          else if(scenario==='challenge-timeout')await advance(1300000);
          else{
            completeChallenge!({});
            if(actionReady){
              await until(()=>!!releaseWatch);assert.equal(widget.state,scenario.startsWith('challenge-processing-action-')?'processing':'requires_action');
              assert.equal(widget.isConnected,true);assert.equal(widget.hidden,false);assert.equal(frame.isConnected,true);await rejectRepeat();
              releaseWatch!();await until(()=>widget.state==='ready');await tick();
              assert.equal(widget.isConnected,false,'post_attempt_ready_retires_form');assert.equal(frame.isConnected,false);assert.equal(destroys,1);await rejectRepeat();
              assert.equal(root.querySelector('[role="status"]').textContent,'Попытка завершена. Окончательный результат проверяет оператор по серверному отчёту. Закройте эту вкладку.','post_attempt_ready_defers_to_server_report');
              start.disabled=false;start.click();start.click();
              const before=calls.length;await advance(1005000);assert.equal(calls.length,before,'action_ready_does_not_restart_watch');
              assert.equal(widget.isConnected,false);assert.equal(frame.isConnected,false);assert.equal(destroys,1);await rejectRepeat();
              if(scenario.endsWith('-expiry'))await advance(1300000);else page?.close();
            }
            else if(['challenge-checking','challenge-processing','challenge-requires_action'].includes(scenario)){
              const expected=({'challenge-checking':'checking','challenge-processing':'processing','challenge-requires_action':'requires_action'} as Record<string,string>)[scenario];
              await until(()=>widget.state===expected);await tick();
              assert.equal(widget.isConnected,true,'intermediate_state_preserves_provider_generation');assert.equal(widget.hidden,false);assert.equal(frame.isConnected,true);assert.equal(destroys,0);await rejectRepeat();
              if(scenario==='challenge-checking')await advance(1300000);
              else{await until(()=>!!releaseWatch);releaseWatch!();}
            }
          }
          await until(()=>root.querySelector('generic-payment-widget')===null);await tick();
          const terminal=root.querySelector('[role="status"]').textContent,before=calls.length;completeChallenge!({});await tick();await tick();
          if(actionReady)for(const name of ['ready','succeeded','failed','unknown','expired','requires-action','processing'])widget.dispatchEvent(new window.CustomEvent(name));
          page?.close();pageController.abort();window.dispatchEvent(new window.Event('pagehide'));await advance(1301000);
          assert.equal(root.querySelector('[role="status"]').textContent,terminal);assert.equal(calls.length,before,'late_challenge_cannot_continue_after_retirement');assert.equal(destroys,1,'provider_retires_once');if(captured)await assert.rejects(captured.confirm());
          if(scenario!=='challenge-success'&&scenario!=='challenge-processing'&&scenario!=='challenge-requires_action')assert.doesNotMatch(terminal,/Браузер сообщил об успехе/);
        }
        else if(scenario==='pagehide')window.dispatchEvent(new window.Event('pagehide'));else if(scenario==='close')page?.close();else if(scenario==='detached')widget.remove();
        if(!challenge&&['pagehide','close','detached'].includes(scenario)){await tick();await assert.rejects(captured.confirm());assert.equal(fakeStripe.confirmCalls,0);}
        else if(!challenge){
          if(scenario==='programmatic')void captured.confirm().catch(()=>{});else if(scenario==='enter'){pay.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Enter',bubbles:true,composed:true}));pay.click();}else pay.click();pay.click();
          if(scenario==='late-grant'){await until(()=>!!releaseGrant);await advance(1300000);releaseGrant!(Response.json({authorized:true}));await tick();}
          else if(scenario.startsWith('permit-')&&scenario.endsWith('http-timeout')){await until(()=>!!releaseGrant||!!releaseBody);if(scenario==='permit-body-http-timeout'){await advance(1008000);releaseGrant!(Response.json({authorized:true}));await until(()=>!!releaseBody);}await advance(1010000);assert.equal(httpSignal?.aborted,true,'permit_http_10s_abort');assert.equal(root.querySelector('generic-payment-widget'),null);await advance(1016000);releaseGrant?.(Response.json({authorized:true}));releaseBody?.('{"authorized":true}');await tick();}
          else if(scenario==='programmatic'){await until(()=>fakeStripe.confirmCalls===1);assert.equal(widget.hidden,true);page?.close();}
          else await until(()=>root.querySelector('generic-payment-widget')===null);
          const allowed=['success','ordinary-ready','validation','programmatic','enter'].includes(scenario);assert.equal(fakeStripe.confirmCalls,allowed?1:0);if(scenario==='success')assert.equal(fakeStripe.confirmCalls,1);
          if(captured)await assert.rejects(captured.confirm());pay.click();await tick();assert.equal(calls.filter(path=>path==='/manual-payment-confirmation').length,1);assert.equal(resumes,1);
          assert.equal(root.querySelector('generic-payment-widget'),null);assert.match(root.querySelector('[role="status"]').textContent,/серверн|Закройте/);
          if(selected3DS&&scenario!=='success')assert.doesNotMatch(root.querySelector('[role="status"]').textContent,/Браузер сообщил об успехе/,'failed_or_late_permit_never_reports_success');
        }
      }
      assert.equal(calls.filter(path=>path==='/manual-payment-session').length,scenario==='expired-start'?0:1,'one_shot_session_exchange');
    }
    if(loadFailures[scenario]){
      if(scenario.startsWith('session-scenario-'))assert.equal(failedWidget,undefined,'scenario_rejected_before_widget_or_lease');
      const status=root.querySelector('[role="status"]'),terminal=status.textContent;
      assert.equal(terminal,'Не удалось загрузить форму. Повторная оплата недоступна. Закройте эту вкладку. '+loadFailures[scenario]);
      assert.equal(start.disabled,true);assert.equal(root.querySelector('generic-payment-widget'),null);assert.equal(calls.filter(path=>path==='/manual-payment-confirmation').length,0);
      const before=calls.length;start.disabled=false;start.click();start.click();
      for(const name of ['ready','succeeded','failed','unknown','expired','requires-action','processing'])failedWidget?.dispatchEvent(new window.CustomEvent(name));
      await advance(1301000);await tick();assert.equal(status.textContent,terminal,'late_callbacks_preserve_terminal_code');assert.equal(calls.length,before,'closed_page_cannot_reopen');assert.equal(fakeStripe.confirmCalls,0);
    }
    page?.close();assert.equal(forbiddenReads,0);assert.equal(credentialWrites,0,'zero_credential_storage_writes_throughout_lifetime');assert.equal(window.sessionStorage.length,0);for(const privateValue of [token,secret,checkout,canary])assert.equal((root.outerHTML+window.location.href).includes(privateValue),false);parentPort?.postMessage('PASS');
  }finally{Date.now=actualNow;frame.remove();window.close();}
}
