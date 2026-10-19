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
export async function exercise(entry:string,scenario:string,automatic=false,instrumented=false):Promise<void>{
  const worker=new Worker(new URL(import.meta.url),{workerData:{entry,scenario,automatic,instrumented}});
  try{await new Promise<void>((resolve,reject)=>{
    const timer=setTimeout(()=>reject(Error('manual_offline_timeout')),10000);
    worker.once('message',value=>{clearTimeout(timer);value==='PASS'?resolve():reject(Error(String(value)));});
    worker.once('error',error=>{clearTimeout(timer);reject(error);});
  });}finally{await worker.terminate();}
}

if(isMainThread&&process.argv[1]===fileURLToPath(import.meta.url))test('manual page uses the real SDK with bounded one-shot recovery',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'payment-manual-source-'));
  try{
    const bundle=async(mutation:string)=>{
      const output=join(directory,mutation);
      await build({root,configFile:false,logLevel:'silent',resolve:{alias:{'@generic-payments/sdk':root+'/../../frontend/sdk/src/index.ts'}},
        plugins:[{name:'test-only-real-path-observation',enforce:'pre',transform(code,id){
          if(id.endsWith('/checkout-provider-view.ts'))code=code.replace('this.#diagnostic = formLoadBinding(host);','this.#diagnostic = formLoadBinding(host);globalThis.__manualView=this;');
          if(id.endsWith('/manual-form-readiness.ts'))code=code.replace('return Object.freeze({snapshot,close});','return globalThis.__manualReadiness=Object.freeze({snapshot,close});');
          if(id.endsWith('/stripe-driver.ts'))code=code.replace('return {\n    async confirm()', 'return globalThis.__manualHandle = {\n    async confirm()');
          if(id.endsWith('/form-load-manual.ts')){
            code=code.replace('const result=classifyFormDiagnosticSample(value);','const result=classifyFormDiagnosticSample(value);(globalThis.__manualTrace??=[]).push({widget_state:value.widget_state,widget_completed:value.widget_completed,operation:value.operation,result});');
            if(mutation==='one-shot')code=code.replace('if(started||closed)return;','if(closed)return;');
            if(mutation==='expired-start')code=code.replaceAll('||now()-startTime>=60000','').replaceAll('if(now()-startTime>=60000){finish();return;}','');
          }
          if(mutation==='deny'&&id.endsWith('/form-load-observer.ts'))code=code.replace('if (!binding) return;\n  // Captured binding identity','return;\n  // Captured binding identity');
          return code;
        }}],build:{outDir:output,lib:{entry:root+'/src/form-load-manual.ts',formats:['es'],fileName:()=> 'manual.mjs'}}});
      return pathToFileURL(join(output,'manual.mjs')).href;
    };
    const entry=await bundle('control');
    for(const scenario of ['no-click','synchronous-ready','delayed-ready','after-checkpoint','frame-only','pay-only','late-ready','slow-session','late-session','late-body','expired-start','malformed','missing','expired','exchange-failure','recovery-failure','late-recovery','close','pagehide','abort','evidence-positive','evidence-hidden','evidence-absent','evidence-multiple','evidence-replaced','evidence-load','evidence-loaderstart','evidence-csp','evidence-untrusted','evidence-abort','evidence-close','evidence-no-host','evidence-unavailable','evidence-csp-unavailable','evidence-late-replaced','evidence-late-load','widget-loss-detach','widget-loss-replace','widget-loss-detach-immediate','widget-loss-replace-immediate','widget-loss-reinsert-immediate'])
      await t.test(scenario,()=>exercise(entry,scenario,false,true));
    for(const [mutation,scenario,reason] of [['deny','synchronous-ready','deny_view_after_observer_close'],['one-shot','delayed-ready','one_shot_exchange'],['expired-start','expired-start','expired_start_operation']])
      await t.test('in-memory mutation is detected: '+mutation,async()=>{
        const mutant=await bundle(mutation!);
        await assert.rejects(exercise(mutant,scenario!,false,true),error=>{assert.ok(error instanceof Error);assert.ok(error.message.includes(reason!),error.message);t.diagnostic(mutation+' RED detected: '+error.message.split('\n')[0]);return true;});
      });
  }finally{await rm(directory,{recursive:true,force:true});}
});

if(!isMainThread){
  const sdkRequire=createRequire(new URL('../../../../frontend/sdk/package.json',import.meta.url));
  const {JSDOM}=sdkRequire('jsdom') as {JSDOM:new(html:string,options:{url:string})=>{window:Window&typeof globalThis}};
  const dom=new JSDOM(workerData.automatic?'<main id="manual-form"></main>':'<main id="fixture"></main>',{url:'http://127.0.0.1:14174/form-load-manual.html'});
  const window=dom.window,scenario=workerData.scenario as string;
  for(const name of ['window','document','HTMLElement','HTMLButtonElement','ShadowRoot','customElements','sessionStorage','location','navigator','CustomEvent','MutationObserver','getComputedStyle'] as const)
    Object.defineProperty(globalThis,name,{value:name==='window'?window:window[name],configurable:true});
  const tick=()=>new Promise<void>(resolve=>setImmediate(resolve));
  const until=async(predicate:()=>boolean)=>{for(let i=0;i<100;i++){if(predicate())return;await tick();}assert.fail('real manual path did not settle');};
  let now=1000000,serial=0;
  const timers=new Map<number,{at:number;callback:()=>void}>();
  const fakeSet=(callback:()=>void,delay=0)=>{const id=++serial;timers.set(id,{at:now+delay,callback});return id;};
  const fakeClear=(id:unknown)=>{timers.delete(id as number);};
  Object.defineProperty(globalThis,'setTimeout',{value:fakeSet,configurable:true});
  Object.defineProperty(globalThis,'clearTimeout',{value:fakeClear,configurable:true});
  const advance=async(target:number)=>{while(true){const next=[...timers].filter(([,v])=>v.at<=target).sort((a,b)=>a[1].at-b[1].at)[0];if(!next)break;now=next[1].at;timers.delete(next[0]);next[1].callback();await tick();}now=target;await tick();};
  const actualNow=Date.now;Date.now=()=>now;
  const checkout='11111111-1111-4111-8111-111111111111',payment='22222222-2222-4222-8222-222222222222',api='http://127.0.0.1:18080';
  const expiresAt='2099-01-01T00:00:00Z',token='x'.repeat(43),secret='pi_fixture_secret_sensitive';
  const price={priceId:'33333333-3333-4333-8333-333333333333',priceKey:'test-price',version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'};
  const session={version:2,apiBaseUrl:api,siteKey:'test-site',priceKey:'test-price',checkoutId:checkout,checkoutToken:token,expiresAt,
    providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId:checkout,paymentId:payment,amountMinor:2000,currency:'EUR',price,status:'REQUIRES_PAYMENT_METHOD',expiresAt}};
  const ownedKey='generic-payments:v1:http%3A%2F%2F127.0.0.1%3A18080:test-site:test-price';
  window.sessionStorage.setItem('unrelated','preserve');
  const calls:string[]=[],reports:Record<string,unknown>[]=[],listeners=new Map<string,()=>void>();
  let sessionSignal:AbortSignal|undefined;
  let confirmations=0,mounts=0,resumes=0,releaseSession:((value:Response)=>void)|undefined,releaseBody:((value:string)=>void)|undefined,releaseRecovery:(()=>void)|undefined;
  const frame=window.document.createElement('iframe');
  let forbiddenReads=0;
  const evidenceReports:Record<string,unknown>[]=[];
  const capturedCsp=new Set<(event:unknown)=>void>(),capturedLoad=new Set<(event:unknown)=>void>();
  const lateCsp:((event:unknown)=>void)[]=[],lateLoad:((event:unknown)=>void)[]=[];
  const nativeAdd=window.EventTarget.prototype.addEventListener,nativeRemove=window.EventTarget.prototype.removeEventListener;
  window.EventTarget.prototype.addEventListener=function(type,listener,options){
    if(type==='securitypolicyviolation'&&scenario==='evidence-csp-unavailable')throw Error('listener_unavailable');
    if(type==='securitypolicyviolation'){capturedCsp.add(listener as (event:unknown)=>void);lateCsp.push(listener as (event:unknown)=>void);}
    if(type==='load'&&this instanceof window.ShadowRoot){capturedLoad.add(listener as (event:unknown)=>void);lateLoad.push(listener as (event:unknown)=>void);assert.equal(options,true);}
    return nativeAdd.call(this,type,listener,options);
  };
  window.EventTarget.prototype.removeEventListener=function(type,listener,options){
    if(type==='securitypolicyviolation')capturedCsp.delete(listener as (event:unknown)=>void);
    if(type==='load'&&this instanceof window.ShadowRoot)capturedLoad.delete(listener as (event:unknown)=>void);
    return nativeRemove.call(this,type,listener,options);
  };
  if(scenario==='evidence-unavailable'){
    window.HTMLElement.prototype.getBoundingClientRect=function(){throw Error('layout_unavailable');};
    const nativeStyle=globalThis.getComputedStyle;Object.defineProperty(globalThis,'getComputedStyle',{configurable:true,value:(node:Element,pseudo?:string|null)=>{if(node.classList.contains('payment-element')||node.tagName==='IFRAME')throw Error('style_unavailable');return nativeStyle(node,pseudo);}});
  }
  if(scenario==='evidence-positive')window.HTMLElement.prototype.getBoundingClientRect=function(){return {width:8,height:4} as DOMRect;};
  for(const name of ['src','contentWindow','contentDocument'])Object.defineProperty(frame,name,{get(){forbiddenReads++;throw Error('forbidden_frame_read_'+name);}});
  const csp=(trusted:boolean,directive:string,disposition:string)=>{
    const event={isTrusted:trusted,effectiveDirective:directive,disposition};
    for(const key of ['blockedURI','sourceFile','sample'])Object.defineProperty(event,key,{get(){forbiddenReads++;throw Error('forbidden_csp_read_'+key);}});
    return event;
  };
  const element={on:(name:string,callback:()=>void)=>{listeners.set(name,callback);return element;},off:(name:string)=>{listeners.delete(name);return element;},
    mount:(host:HTMLElement)=>{mounts++;assert.equal(host.isConnected,true);assert.equal(host.getRootNode(),window.document.querySelector('generic-payment-widget')!.shadowRoot);host.append(frame);
      if(['evidence-load','evidence-late-replaced'].includes(scenario)){assert.equal(capturedLoad.size,1,'capture_before_mount');for(const callback of capturedLoad)callback({isTrusted:true,target:frame});}
      if(scenario==='evidence-loaderstart')listeners.get('loaderstart')?.();
      if(scenario==='synchronous-ready')listeners.get('ready')?.();},
    destroy:()=>frame.remove(),focus:()=>{}};
  Object.defineProperty(window,'Stripe',{value:Object.assign((key:string)=>{
    assert.equal(key,'pk_test_fixture');return {elements:(options:{clientSecret:string})=>{assert.equal(options.clientSecret,secret);return {create:(kind:string)=>{assert.equal(kind,'payment');return element;},update:()=>{}};},
      confirmPayment:()=>{confirmations++;throw Error('forbidden_confirmation');}};
  },{version:'endive'})});
  globalThis.fetch=async(input,init)=>{
    const url=new URL(String(input),window.location.href);calls.push(url.pathname);
    if(url.origin===window.location.origin){
      assert.equal(init?.method,'POST');assert.equal(init?.credentials,'same-origin');assert.equal(init?.cache,'no-store');
      if(url.pathname==='/manual-session'){
        sessionSignal=init?.signal??undefined;
        assert.equal(init?.body,'{}');assert.equal(now,1000000);
        if(scenario==='evidence-csp'){assert.equal(capturedCsp.size,1,'collector_before_session_handoff');for(const callback of capturedCsp)callback(csp(true,'script-src','enforce'));}
        if(['late-session','slow-session','expired-start'].includes(scenario))return new Promise<Response>(resolve=>{releaseSession=resolve;});
        if(scenario==='exchange-failure')throw Error(secret);
        if(scenario==='missing')return Response.json(null);
        if(scenario==='malformed')return Response.json({...session,extra:secret});
        if(scenario==='expired')return Response.json({...session,expiresAt:'1970-01-01T00:00:01Z',state:{...session.state,expiresAt:'1970-01-01T00:00:01Z'}});
        if(scenario==='late-body')return {ok:true,headers:new Headers({'content-type':'application/json'}),text:()=>new Promise<string>(resolve=>{releaseBody=resolve;})} as Response;
        return Response.json(session);
      }
      assert.equal(url.pathname,'/manual-observation');
      const envelope=JSON.parse(String(init?.body)) as {version:number;sample:Record<string,unknown>;evidence:Record<string,unknown>};
      assert.deepEqual(Object.keys(envelope).sort(),['evidence','sample','version']);assert.equal(envelope.version,2);
      assert.equal(typeof envelope.evidence.loaderstart_seen,'boolean');evidenceReports.push(envelope.evidence);
      const sample=envelope.sample;reports.push(sample);
      assert.deepEqual(Object.keys(sample).sort(),['version','binding','capability_started','capability_ready','loader_started','loader_ready','element_created','mount_called','mount_returned','ready_seen','loaderror_seen','view_completed','widget_completed','confirmation_blocked','catchup','operation','widget_state','element_state','pay_state','pay_visible','bucket'].sort());
      assert.ok(JSON.stringify(sample).length<4096);return new Response(null,{status:204});
    }
    assert.equal(url.origin,api);assert.equal(init?.headers instanceof Headers?init.headers.get('Authorization'):(init?.headers as Record<string,string>)?.Authorization,'Bearer '+token);
    if(url.pathname===`/api/v1/public/checkouts/${checkout}`){
      assert.equal(init?.method,'GET');if(scenario==='recovery-failure')throw Error(secret);
      if(scenario==='late-recovery')await new Promise<void>(resolve=>{releaseRecovery=resolve;});
      return Response.json({checkout_id:checkout,payment_id:payment,amount_minor:2000,currency:'EUR',price:{price_id:price.priceId,price_key:'test-price',version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE'},provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'REQUIRES_PAYMENT_METHOD',expires_at:expiresAt});
    }
    assert.equal(url.pathname,`/api/v1/public/checkouts/${checkout}/client-session`);assert.equal(init?.method,'POST');
    return Response.json({checkout_id:checkout,payment_id:payment,attempt_id:'44444444-4444-4444-8444-444444444444',provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',publishable_key:'pk_test_fixture',client_secret:secret,expires_at:expiresAt,return_url:'http://127.0.0.1:14174/payment-return'});
  };
  try{
    const module=await import(workerData.entry);
    const root=window.document.querySelector('main')!;
    const controller=new AbortController();
    const page=workerData.automatic?undefined:module.mountManualFormPage(root,{signal:controller.signal});
    assert.ok(root.textContent?.includes('Ручная проверка — без оплаты'));
    assert.ok(root.textContent?.includes('Не вводите карту и не нажимайте Pay. Не обновляйте страницу'));
    const start=root.querySelector('button')!;assert.equal(start.textContent,'Загрузить форму');
    await tick();assert.equal(calls.length,0);assert.equal(root.querySelector('generic-payment-widget'),null);
    if(scenario==='no-click'){page?.close();assert.equal([...timers.values()].filter(timer=>timer.at>now).length,0);}
    else{
      root.addEventListener('click',event=>{if((event.composedPath()[0] as HTMLElement)?.textContent==='Resume existing payment')resumes++;});
      start.click();start.click();start.dispatchEvent(new window.MouseEvent('click',{bubbles:true}));
      assert.equal(calls.filter(path=>path==='/manual-session').length,1,'one_shot_exchange');
      if(scenario==='slow-session'){await tick();await advance(1010000);releaseSession?.(Response.json(session));}
      if(['late-session','late-body','expired-start'].includes(scenario)){
        await tick();if(scenario==='expired-start')now=1060000;else await advance(1060000);
        releaseSession?.(Response.json(session));releaseBody?.(JSON.stringify(session));await tick();await tick();
        assert.equal(calls.filter(path=>path!=='/manual-observation').length,1,'expired_start_operation');
        assert.equal(root.querySelector('generic-payment-widget'),null);assert.equal(mounts,0);assert.equal(reports.length,0);
      }else if(['missing','malformed','expired','exchange-failure'].includes(scenario)){
        await tick();await tick();assert.equal(root.querySelector('generic-payment-widget'),null);assert.equal(mounts,0);assert.equal(reports.length,0);
      }else if(['recovery-failure','late-recovery'].includes(scenario)){
        await tick();await tick();await advance(1060000);releaseRecovery?.();await tick();await tick();assert.equal(mounts,0);assert.equal(calls.filter(path=>path!=='/manual-observation').length,2);
      }else{
        await until(()=>frame.isConnected);assert.equal(mounts,1);assert.equal(resumes,1);
        const widget=root.querySelector('generic-payment-widget') as HTMLElement&{state:string};
        if(scenario.startsWith('widget-loss-')){
          await advance(1015000);assert.equal(reports.length,1);
          const captured=globalThis as typeof globalThis&{__manualReadiness:{snapshot():Readonly<Record<string,unknown>>};__manualView:{confirm():Promise<unknown>};__manualHandle:{confirm():Promise<unknown>}};
          const before=captured.__manualReadiness.snapshot(),history=JSON.stringify({sample:reports[0],evidence:evidenceReports[0]}),lateReady=listeners.get('ready');
          const ownedView=captured.__manualView;
          await advance(1020000);
          const replacement=scenario.includes('replace')?window.document.createElement('generic-payment-widget'):undefined;
          const previousParent=widget.parentElement!;
          if(replacement)widget.replaceWith(replacement);else widget.remove();
          if(scenario.includes('reinsert'))previousParent.append(widget);
          // A callback can run before MutationObserver delivery. It must revoke
          // the old generation before inspecting any new evidence.
          if(scenario.endsWith('immediate'))for(const callback of lateCsp)callback(csp(true,'connect-src','enforce'));
          else await tick();
          assert.equal(capturedCsp.size,0,'lost_widget_csp_cleanup');assert.equal(capturedLoad.size,0,'lost_widget_load_cleanup');
          await tick();
          assert.equal(sessionSignal?.aborted,true,'lost_widget_exchange_cleanup');
          assert.equal(window.sessionStorage.getItem(ownedKey),null,'lost_widget_recovery_cleanup');assert.equal([...timers.values()].filter(timer=>timer.at>now).length,0,'lost_widget_timer_cleanup');
          for(const callback of lateCsp)callback(csp(true,'connect-src','enforce'));
          for(const callback of lateLoad)callback({isTrusted:true,target:frame});
          lateReady?.();start.click();await tick();
          assert.deepEqual(captured.__manualReadiness.snapshot(),before,'lost_widget_evidence_frozen');
          assert.equal(JSON.stringify({sample:reports[0],evidence:evidenceReports[0]}),history,'lost_widget_history_immutable');
          assert.equal(calls.filter(path=>path==='/manual-session').length,1);assert.equal(mounts,1);assert.equal(resumes,1);
          await assert.rejects(ownedView.confirm(),error=>error instanceof Error&&error.message==='CONFIRMATION_BLOCKED');
          if(replacement){assert.equal(replacement.isConnected,true,'replacement_is_not_adopted');replacement.remove();}
          await advance(1060000);assert.equal(reports.length,scenario.includes('reinsert')?2:1,'lost_widget_no_extra_observation');
          if(scenario.includes('reinsert')){assert.equal(reports[1]!.binding,'LOST');assert.deepEqual(evidenceReports[1],before,'lost_generation_terminal_keeps_frozen_evidence');}
        }else if(scenario.startsWith('evidence-')){
          assert.equal(capturedCsp.size,scenario==='evidence-csp-unavailable'?0:1);assert.equal(capturedLoad.size,1);
          const beforeHostLoss=scenario==='evidence-no-host'?(globalThis as typeof globalThis&{__manualReadiness:{snapshot():Readonly<Record<string,unknown>>}}).__manualReadiness.snapshot():undefined;
          if(scenario==='evidence-hidden')root.hidden=true;
          if(scenario==='evidence-absent')frame.remove();
          if(scenario==='evidence-no-host')frame.parentElement!.remove();
          if(scenario==='evidence-multiple')frame.parentElement!.append(window.document.createElement('iframe'));
          if(scenario==='evidence-replaced'){
            for(const callback of capturedLoad)callback({isTrusted:true,target:frame});
            const replacement=window.document.createElement('iframe');frame.replaceWith(replacement);
            for(const callback of capturedLoad)callback({isTrusted:true,target:frame});
          }
          if(scenario==='evidence-csp')for(const callback of capturedCsp){callback(csp(true,'script-src-elem','enforce'));callback(csp(true,'child-src','enforce'));callback(csp(true,'connect-src','report'));callback(csp(true,'style-src','report'));}
          if(scenario==='evidence-untrusted'){
            for(const callback of capturedCsp)callback(csp(false,'script-src','enforce'));
            for(const callback of capturedLoad)callback({isTrusted:false,target:frame});
          }
          if(scenario==='evidence-abort')controller.abort();
          if(scenario==='evidence-close')page?.close();
          if(['evidence-abort','evidence-close'].includes(scenario)){
            assert.equal(capturedCsp.size,0);assert.equal(capturedLoad.size,0);
          }else{
            await advance(1015000);
            const evidence=evidenceReports[0]!;
            assert.deepEqual(Object.keys(evidence).sort(),['loaderstart_seen','host_box','frame_box','host_visibility','frame_visibility','frame_load','csp_observation','csp_enforced_mask','csp_report_only_mask'].sort());
            if(beforeHostLoss)assert.deepEqual(evidence,beforeHostLoss,'lost_host_freezes_evidence');else{
            assert.equal(evidence.host_box,scenario==='evidence-no-host'?'ABSENT':scenario==='evidence-unavailable'?'UNAVAILABLE':scenario==='evidence-positive'?'POSITIVE_AREA':'ZERO_AREA');
            assert.equal(evidence.host_visibility,scenario==='evidence-no-host'?'ABSENT':scenario==='evidence-unavailable'?'UNAVAILABLE':scenario==='evidence-hidden'?'HIDDEN':'VISIBLE');
            assert.equal(evidence.frame_box,['evidence-absent','evidence-no-host'].includes(scenario)?'ABSENT':scenario==='evidence-multiple'?'MULTIPLE':scenario==='evidence-unavailable'?'UNAVAILABLE':scenario==='evidence-positive'?'POSITIVE_AREA':'ZERO_AREA');
            assert.equal(evidence.frame_visibility,['evidence-absent','evidence-no-host'].includes(scenario)?'ABSENT':['evidence-multiple','evidence-unavailable'].includes(scenario)?'UNAVAILABLE':scenario==='evidence-hidden'?'HIDDEN':'VISIBLE');
            assert.equal(evidence.frame_load,['evidence-absent','evidence-no-host'].includes(scenario)?'NO_FRAME':scenario==='evidence-multiple'?'UNAVAILABLE':['evidence-load','evidence-late-replaced'].includes(scenario)?'SEEN':'NOT_SEEN');
            }
            assert.equal(evidence.loaderstart_seen,scenario==='evidence-loaderstart');
            assert.equal(evidence.csp_observation,scenario==='evidence-csp-unavailable'?'UNAVAILABLE':'OBSERVED');assert.equal(evidence.csp_enforced_mask,scenario==='evidence-csp'?3:0);assert.equal(evidence.csp_report_only_mask,scenario==='evidence-csp'?12:0);
            assert.equal(reports[0]!.ready_seen,false);
            if(['evidence-late-replaced','evidence-late-load'].includes(scenario)){
              await advance(1020000);
              for(const callback of capturedCsp)callback(csp(true,'connect-src','enforce'));
              if(scenario==='evidence-late-replaced')frame.replaceWith(window.document.createElement('iframe'));
              else for(const callback of capturedLoad)callback({isTrusted:true,target:frame});
              await tick();
            }
            await advance(1060000);assert.equal(reports.length,scenario==='evidence-no-host'?1:2);
            if(['evidence-late-replaced','evidence-late-load'].includes(scenario)){
              assert.equal(evidenceReports[0]!.csp_enforced_mask,0,'checkpoint_is_immutable');
              assert.equal(evidenceReports[1]!.csp_enforced_mask,4,'retain_last_active_csp');
              assert.equal(evidenceReports[1]!.frame_load,scenario==='evidence-late-replaced'?'NOT_SEEN':'SEEN','retain_current_frame_load');
            }
          }
          assert.equal(capturedCsp.size,0);assert.equal(capturedLoad.size,0);
          for(const callback of lateCsp)callback(csp(true,'script-src','enforce'));
          for(const callback of lateLoad)callback({isTrusted:true,target:frame});
        }else{
        if(['delayed-ready','close','pagehide','abort'].includes(scenario))listeners.get('ready')?.();
        if(scenario==='pay-only'){const pay=widget.shadowRoot!.querySelector('button')!;pay.hidden=false;pay.disabled=false;pay.setAttribute('aria-label','Pay');}
        if(scenario==='after-checkpoint'){
          await advance(1015000);assert.equal(reports.length,1);assert.equal(reports[0]!.bucket,'AFTER_15S');assert.equal(reports[0]!.ready_seen,false);
          await advance(1020000);listeners.get('ready')?.();await until(()=>widget.state==='ready');await tick();
          assert.equal(reports.length,2);assert.equal(reports[1]!.bucket,'AFTER_15S');assert.equal(reports[1]!.ready_seen,true);
          await advance(1059999);assert.equal(frame.isConnected,true);await advance(1060000);
        }else if(['synchronous-ready','delayed-ready','close','pagehide','abort'].includes(scenario)){
          await until(()=>widget.state==='ready');await advance(1000100);
          assert.equal(reports.length,1);assert.equal(reports[0]!.ready_seen,true);
          assert.equal(capturedCsp.size,0,'ready_csp_cleanup');assert.equal(capturedLoad.size,0,'ready_load_cleanup');assert.equal(reports[0]!.bucket,'BEFORE_15S');
          assert.ok(root.textContent?.includes('Форма сообщила о готовности. Проверьте, видны ли поля; ничего не вводите'));
          if(workerData.instrumented){
            const captured=globalThis as typeof globalThis&{__manualView:{confirm():Promise<unknown>};__manualHandle:{confirm():Promise<unknown>};__manualTrace:{widget_state:string;widget_completed:boolean;operation:string;result:string}[]};
            // The page closes the observer after its ready report; both captured real layers must still deny.
            await assert.rejects(captured.__manualView.confirm(),error=>{assert.equal(error instanceof Error&&error.message==='CONFIRMATION_BLOCKED',true,'deny_view_after_observer_close');return true;});
            await assert.rejects(captured.__manualHandle.confirm(),error=>{assert.equal(error instanceof Error&&error.message==='CONFIRMATION_BLOCKED',true,'deny_handle_after_observer_close');return true;});
            if(scenario==='synchronous-ready')assert.ok(captured.__manualTrace.some(value=>value.widget_state==='loading'&&value.result==='INSTRUMENTATION_UNAVAILABLE'),JSON.stringify(captured.__manualTrace));
          }
          const pay=widget.shadowRoot!.querySelector<HTMLButtonElement>('button[aria-label="Pay"]')!;pay.click();await tick();assert.equal(confirmations,0);
          if(scenario==='close')page?.close();
          if(scenario==='pagehide')window.dispatchEvent(new window.Event('pagehide'));
          if(scenario==='abort')controller.abort();
          if(['close','pagehide','abort'].includes(scenario))assert.equal(frame.isConnected,false);
          else{await advance(1059999);assert.equal(frame.isConnected,true);await advance(1060000);}
        }else{
          await advance(1014999);assert.equal(reports.length,0);await advance(1015000);
          assert.equal(reports.length,1);assert.equal(reports[0]!.ready_seen,false);assert.equal(reports[0]!.bucket,'AFTER_15S');
          if(scenario==='late-ready'){now=1060000;listeners.get('ready')?.();await tick();}
          await advance(1060000);assert.equal(reports.length,2);assert.equal(reports[1]!.bucket,'AT_LIMIT');
        }
        }
        assert.equal(frame.isConnected,false);assert.equal(root.querySelector('generic-payment-widget'),null);
        assert.equal(calls.filter(path=>path.endsWith('/client-session')).length,1);assert.equal(resumes,1);
        listeners.get('ready')?.();await tick();assert.equal(root.querySelector('generic-payment-widget'),null);
      }
      assert.equal(capturedCsp.size,0,'collector_csp_cleanup');assert.equal(capturedLoad.size,0,'collector_load_cleanup');
      page?.close();assert.equal(window.sessionStorage.getItem(ownedKey),null);assert.equal([...timers.values()].filter(timer=>timer.at>now).length,0);
      assert.equal(confirmations,0);assert.equal(forbiddenReads,0,'forbidden_detail_reads');
      const safe=JSON.stringify(reports)+root.outerHTML+window.location.href;
      for(const value of [token,secret,checkout])assert.equal(safe.includes(value),false);
    }
    assert.equal(window.sessionStorage.getItem('unrelated'),'preserve');
    parentPort?.postMessage('PASS');
  }finally{Date.now=actualNow;window.close();}
}
