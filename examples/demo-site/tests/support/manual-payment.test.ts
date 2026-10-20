import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
import {Worker,isMainThread,parentPort,workerData} from 'node:worker_threads';
import {build} from 'vite';
const root=fileURLToPath(new URL('../..',import.meta.url));
export async function exercise(entry:string,scenario:string,automatic=false):Promise<void>{
  const worker=new Worker(new URL(import.meta.url),{workerData:{entry,scenario,automatic}});
  try{await new Promise<void>((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('offline_manual_payment_timeout')),10000);worker.once('message',value=>{clearTimeout(timer);assert.equal(value,'PASS');resolve();});worker.once('error',error=>{clearTimeout(timer);reject(error);});});}finally{await worker.terminate();}
}
if(isMainThread&&process.argv[1]===fileURLToPath(import.meta.url))test('real SDK manual payment page spends one permit and never retries after any outcome',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'manual-payment-source-'));
  try{
    const bundle=async(mutation='control')=>{
      const output=join(directory,mutation);
      await build({root,configFile:false,logLevel:'silent',resolve:{alias:{'@generic-payments/sdk':root+'/../../frontend/sdk/src/index.ts'}},
        plugins:[{name:'capture-real-handle',enforce:'pre',transform(code,id){
          if(id.endsWith('/stripe-driver.ts'))code=code.replace('return {\n    async confirm()', 'return globalThis.__manualHandle = {\n    async confirm()');
          if(mutation==='mixed'&&id.endsWith('/manual-payment.ts'))code=code.replace("from '../../../frontend/sdk/src/diagnostics/manual-payment-authorization'", "from '../../../frontend/sdk/src/diagnostics/manual-payment-authorization?separate-graph'");
          if(mutation==='start-latch'&&id.endsWith('/manual-payment.ts'))code=code.replace('if(started||!active())return;','if(!active())return;').replace('started=true;start.disabled=true;','started=true;');
          return code;
        }}],build:{outDir:output,lib:{entry:root+'/src/manual-payment.ts',formats:['es'],fileName:()=> 'manual.mjs'}}});return pathToFileURL(join(output,'manual.mjs')).href;
    };
    const entry=await bundle();for(const scenario of ['no-click','success','validation','grant-rejected','grant-closed','grant-malformed','late-grant','pagehide','close','expired','mixed-session','malformed','late-session','expired-start','programmatic','enter','detached','session-http-timeout','session-body-http-timeout','permit-http-timeout','permit-body-http-timeout',...['get','post','refresh'].flatMap(stage=>['headers','body','pagehide','detached','late'].map(phase=>`sdk-${stage}-${phase}`)),'sdk-post-host','sdk-refresh-host'])await t.test(scenario,()=>exercise(entry,scenario));
    const mixed=await bundle('mixed');await t.test('missing module instrumentation fails before Pay',()=>exercise(mixed,'instrumentation'));
    const mutant=await bundle('start-latch');await t.test('behavioral start latch mutation is detected',async()=>{
      await assert.rejects(exercise(mutant,'success'),error=>error instanceof Error&&error.message.includes('one_shot_session_exchange'));
    });
  }finally{await rm(directory,{recursive:true,force:true});}
});
if(!isMainThread){
  const require=createRequire(new URL('../../../../frontend/sdk/package.json',import.meta.url));const {JSDOM}=require('jsdom');
  const dom=new JSDOM(workerData.automatic?'<main id="manual-payment"></main>':'<main id="fixture"></main>',{url:'http://127.0.0.1:14174/manual-payment.html'}),window=dom.window,scenario=workerData.scenario;
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
  const price={priceId:'33333333-3333-4333-8333-333333333333',priceKey:'test-price',version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'};
  const session={version:2,apiBaseUrl:api,siteKey:'test-site',priceKey:'test-price',checkoutId:checkout,checkoutToken:token,expiresAt,providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId:checkout,paymentId:payment,amountMinor:2000,currency:'EUR',price,status:'REQUIRES_PAYMENT_METHOD',expiresAt}};
  // Count throughout the lifetime; never retain or print credential-bearing writes.
  let credentialWrites=0;const originalSet=window.Storage.prototype.setItem;
  window.Storage.prototype.setItem=function(key:string,value:string){if(value.includes(token)||value.includes(secret))credentialWrites++;return originalSet.call(this,key,value);};
  let releaseSdk:((value:Response)=>void)|undefined,releaseSdkBody:((value:string)=>void)|undefined,sdkSignal:AbortSignal|undefined,sdkReply:unknown;
  const calls:string[]=[],listeners=new Map<string,()=>void>();let forbiddenReads=0,mounts=0,resumes=0,getCalls=0,releaseGrant:((value:Response)=>void)|undefined,releaseSession:((value:Response)=>void)|undefined,releaseBody:((value:string)=>void)|undefined,httpSignal:AbortSignal|undefined;
  const frame=window.document.createElement('iframe');for(const key of ['src','contentWindow','contentDocument'])Object.defineProperty(frame,key,{get(){forbiddenReads++;throw Error('forbidden_card_read');}});
  const element={on:(name:string,callback:()=>void)=>{listeners.set(name,callback);return element;},off:(name:string)=>{listeners.delete(name);return element;},mount:(host:HTMLElement)=>{mounts++;host.append(frame);listeners.get('ready')?.();},destroy:()=>frame.remove(),focus:()=>{}};
  const fakeStripe={confirmCalls:0,elements:()=>({create:()=>element,update:()=>{}}),confirmPayment:async()=>{fakeStripe.confirmCalls++;return scenario==='validation'?{error:{type:'validation_error'}}:{};}};
  Object.defineProperty(window,'Stripe',{value:Object.assign(()=>fakeStripe,{version:'endive'})});
  globalThis.fetch=async(input,init)=>{
    const url=new URL(String(input),window.location.href);calls.push(url.pathname);
    if(url.origin===window.location.origin){assert.equal(init?.method,'POST');assert.equal(init?.body,'{}');assert.equal(init?.credentials,'same-origin');assert.equal(init?.cache,'no-store');
      if(url.pathname==='/manual-payment-session'){
        if(scenario==='late-session'||scenario==='session-http-timeout'){httpSignal=init?.signal??undefined;return new Promise<Response>(resolve=>{releaseSession=resolve;});}
        if(scenario==='session-body-http-timeout'){httpSignal=init?.signal??undefined;return new Promise<Response>(resolve=>{releaseSession=()=>resolve({ok:true,text:()=>new Promise<string>(yes=>{releaseBody=yes;})} as Response);});}
        if(scenario==='malformed')return Response.json({...session,extra:secret});if(scenario==='mixed-session')return Response.json({...session,providerType:'FAKE'});
        if(scenario==='expired')return Response.json({...session,expiresAt:'1970-01-01T00:00:01Z',state:{...session.state,expiresAt:'1970-01-01T00:00:01Z'}});return Response.json(session);
      }
      assert.equal(url.pathname,'/manual-payment-confirmation');if(scenario==='grant-rejected')throw Error(secret);if(scenario==='grant-closed')return new Response(null,{status:410});if(scenario==='grant-malformed')return Response.json({authorized:true,extra:true});
      if(scenario==='late-grant'||scenario==='permit-http-timeout'){httpSignal=init?.signal??undefined;return new Promise<Response>(resolve=>{releaseGrant=resolve;});}
      if(scenario==='permit-body-http-timeout'){httpSignal=init?.signal??undefined;return new Promise<Response>(resolve=>{releaseGrant=()=>resolve({ok:true,text:()=>new Promise<string>(yes=>{releaseBody=yes;})} as Response);});}return Response.json({authorized:true});
    }
    assert.equal(url.origin,api,'all external HTTP denied');assert.equal(url.pathname.startsWith('/api/v1/public/checkouts/'+checkout),true);assert.equal(init?.headers instanceof Headers?init.headers.get('Authorization'):(init?.headers as Record<string,string>)?.Authorization,'Bearer '+token);
    const preparing=url.pathname.endsWith('/client-session');if(!preparing)getCalls++;
    const reply=preparing?{checkout_id:checkout,payment_id:payment,attempt_id:'44444444-4444-4444-8444-444444444444',provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',publishable_key:'pk_test_fixture',client_secret:secret,expires_at:expiresAt,return_url:'http://127.0.0.1:14174/payment-return.html'}:{checkout_id:checkout,payment_id:payment,amount_minor:2000,currency:'EUR',price:{price_id:price.priceId,price_key:price.priceKey,version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE'},provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:fakeStripe.confirmCalls&&scenario!=='validation'?'SUCCEEDED':'REQUIRES_PAYMENT_METHOD',expires_at:expiresAt};
    if(scenario.startsWith(`sdk-${preparing?'post':getCalls===1?'get':'refresh'}-`)){
      sdkSignal=init?.signal??undefined;sdkReply=reply;
      return new Promise<Response>(resolve=>{releaseSdk=scenario.endsWith('-body')?()=>resolve({ok:true,status:200,text:()=>new Promise<string>(yes=>{releaseSdkBody=yes;})} as Response):resolve;});
    }
    return Response.json(reply);
  };
  window.fetch=globalThis.fetch;
  try{
    const module=await import(workerData.entry),root=window.document.querySelector(workerData.automatic?'#manual-payment':'#fixture');const page=workerData.automatic?undefined:module.mountManualPaymentPage(root);
    const start=root.querySelector('button');assert.ok(start);await tick();assert.equal(calls.length,0);
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
      else if(['malformed','mixed-session','expired','expired-start','instrumentation'].includes(scenario)){await until(()=>root.querySelector('[role="status"]').textContent.includes('Закройте'));assert.equal(fakeStripe.confirmCalls,0);assert.equal(mounts,0);}
      else if(scenario==='late-session'){await until(()=>!!releaseSession);await advance(1300000);releaseSession!(Response.json(session));await tick();assert.equal(mounts,0);}
      else{
        await until(()=>root.querySelector('generic-payment-widget')?.state==='ready');const widget=root.querySelector('generic-payment-widget');assert.equal(mounts,1);assert.equal(getCalls,1);
        assert.equal(calls.filter(path=>path.endsWith('/client-session')).length,1);resumes=mounts;
        const pay=widget.shadowRoot.querySelector('button[aria-label="Pay"]');assert.ok(pay);const captured=(globalThis as typeof globalThis&{__manualHandle:{confirm():Promise<unknown>}}).__manualHandle;
        if(scenario==='pagehide')window.dispatchEvent(new window.Event('pagehide'));else if(scenario==='close')page?.close();else if(scenario==='detached')widget.remove();
        if(['pagehide','close','detached'].includes(scenario)){await tick();await assert.rejects(captured.confirm());assert.equal(fakeStripe.confirmCalls,0);}
        else{
          if(scenario==='programmatic')void captured.confirm().catch(()=>{});else if(scenario==='enter'){pay.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Enter',bubbles:true,composed:true}));pay.click();}else pay.click();pay.click();
          if(scenario==='late-grant'){await until(()=>!!releaseGrant);await advance(1300000);releaseGrant!(Response.json({authorized:true}));await tick();}
          else if(scenario.startsWith('permit-')&&scenario.endsWith('http-timeout')){await until(()=>!!releaseGrant||!!releaseBody);if(scenario==='permit-body-http-timeout'){await advance(1008000);releaseGrant!(Response.json({authorized:true}));await until(()=>!!releaseBody);}await advance(1010000);assert.equal(httpSignal?.aborted,true,'permit_http_10s_abort');assert.equal(root.querySelector('generic-payment-widget'),null);await advance(1016000);releaseGrant?.(Response.json({authorized:true}));releaseBody?.('{"authorized":true}');await tick();}
          else if(scenario==='programmatic'){await until(()=>fakeStripe.confirmCalls===1);assert.equal(widget.hidden,true);page?.close();}
          else await until(()=>root.querySelector('generic-payment-widget')===null);
          const allowed=['success','validation','programmatic','enter'].includes(scenario);assert.equal(fakeStripe.confirmCalls,allowed?1:0);if(scenario==='success')assert.equal(fakeStripe.confirmCalls,1);
          if(captured)await assert.rejects(captured.confirm());pay.click();await tick();assert.equal(calls.filter(path=>path==='/manual-payment-confirmation').length,1);assert.equal(resumes,1);
          assert.equal(root.querySelector('generic-payment-widget'),null);assert.match(root.querySelector('[role="status"]').textContent,/серверн|Закройте/);
        }
      }
      assert.equal(calls.filter(path=>path==='/manual-payment-session').length,scenario==='expired-start'?0:1,'one_shot_session_exchange');
    }
    page?.close();assert.equal(forbiddenReads,0);assert.equal(credentialWrites,0,'zero_credential_storage_writes_throughout_lifetime');assert.equal(window.sessionStorage.length,0);for(const privateValue of [token,secret,checkout])assert.equal((root.outerHTML+window.location.href).includes(privateValue),false);parentPort?.postMessage('PASS');
  }finally{Date.now=actualNow;frame.remove();window.close();}
}
