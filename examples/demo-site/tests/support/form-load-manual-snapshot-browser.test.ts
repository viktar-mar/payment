import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'vite';
import {chromium} from '@playwright/test';

interface Observation {
  version:number;
  sample:{ready_seen:boolean;widget_state:string;operation:string};
  evidence:{host_visibility:string;frame_visibility:string;frame_box:string};
}
interface Probe {
  ready():void;changeOpacity():void;close():void;
  state():{mounted:boolean;opacity:string;positiveArea:boolean;confirmations:number};
}

// Node-only regression; normal Playwright discovery excludes form-load-manual*.
// Real manual page and SDK, synthetic provider only. All HTTP/WebSocket traffic
// is intercepted; no Stripe service, frame content, card input or payment is used.
test('native manual-ready reports remain historical when outer iframe styles change',async t=>{
  const root=fileURLToPath(new URL('../..',import.meta.url));
  const directory=await mkdtemp(join(tmpdir(),'payment-manual-snapshot-'));
  let browser:Awaited<ReturnType<typeof chromium.launch>>|undefined;
  try{
    await build({root,configFile:false,logLevel:'silent',resolve:{alias:{'@generic-payments/sdk':root+'/../../frontend/sdk/src/index.ts'}},
      build:{outDir:directory,lib:{entry:root+'/src/form-load-manual.ts',formats:['es'],fileName:()=> 'manual.mjs'},rolldownOptions:{output:{codeSplitting:false}}}});
    const bundle=await readFile(join(directory,'manual.mjs'),'utf8');
    browser=await chromium.launch({headless:true,args:['--disable-background-networking','--disable-component-update','--no-first-run']});
    for(const initiallyHidden of [true,false])await t.test(initiallyHidden?'hidden at ready, visible later':'visible at ready, hidden later',async()=>{
      const origin='http://127.0.0.1:14174',api='http://127.0.0.1:18080';
      const checkout='11111111-1111-4111-8111-111111111111',payment='22222222-2222-4222-8222-222222222222';
      const expiresAt='2099-01-01T00:00:00Z',token='x'.repeat(43);
      const price={priceId:'33333333-3333-4333-8333-333333333333',priceKey:'test-price',version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'};
      const session={version:2,apiBaseUrl:api,siteKey:'test-site',priceKey:'test-price',checkoutId:checkout,checkoutToken:token,expiresAt,
        providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId:checkout,paymentId:payment,amountMinor:2000,currency:'EUR',price,status:'REQUIRES_PAYMENT_METHOD',expiresAt}};
      const reports:Observation[]=[],calls:string[]=[];let denied=0,sockets=0;
      const context=await browser!.newContext({serviceWorkers:'block',acceptDownloads:false});
      try{
        await context.routeWebSocket('**/*',socket=>{sockets++;socket.close();});
        await context.route('**/*',async route=>{
          const request=route.request(),url=request.url(),method=request.method();
          if(url===origin+'/'&&method==='GET')return route.fulfill({contentType:'text/html',body:'<!doctype html><html><body><main></main></body></html>'});
          if(url===origin+'/manual.mjs'&&method==='GET')return route.fulfill({contentType:'text/javascript',body:bundle});
          if(url===origin+'/manual-session'&&method==='POST'){calls.push('session');return route.fulfill({json:session});}
          if(url===origin+'/manual-observation'&&method==='POST'){calls.push('observation');reports.push(request.postDataJSON());return route.fulfill({status:204});}
          if(url===api+'/api/v1/public/checkouts/'+checkout&&method==='GET'){
            calls.push('read');return route.fulfill({json:{checkout_id:checkout,payment_id:payment,amount_minor:2000,currency:'EUR',
              price:{price_id:price.priceId,price_key:'test-price',version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE'},
              provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'REQUIRES_PAYMENT_METHOD',expires_at:expiresAt}});
          }
          if(url===api+'/api/v1/public/checkouts/'+checkout+'/client-session'&&method==='POST'){
            calls.push('attempt');return route.fulfill({json:{checkout_id:checkout,payment_id:payment,attempt_id:'44444444-4444-4444-8444-444444444444',
              provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',publishable_key:'pk_test_fixture',client_secret:'pi_fixture_secret_synthetic',expires_at:expiresAt,return_url:origin+'/payment-return'}});
          }
          denied++;await route.abort('blockedbyclient');
        });
        const page=await context.newPage();page.setDefaultTimeout(5000);page.setDefaultNavigationTimeout(5000);await page.goto(origin+'/');
        await page.evaluate(async({origin,initiallyHidden})=>{
          const callbacks=new Map<string,()=>void>();let frame:HTMLIFrameElement|undefined,confirmations=0;
          const element={on:(name:string,callback:()=>void)=>{callbacks.set(name,callback);return element;},off:(name:string)=>{callbacks.delete(name);return element;},
            mount:(host:HTMLElement)=>{
              if(host.getRootNode()!==document||!host.assignedSlot)throw Error('offline_provider_requires_projected_host');
              frame=document.createElement('iframe');frame.style.width='240px';frame.style.height='80px';frame.style.opacity=initiallyHidden?'0':'1';host.append(frame);callbacks.get('loaderstart')?.();
            },destroy:()=>frame?.remove(),focus:()=>{}};
          Object.defineProperty(window,'Stripe',{value:Object.assign((key:string)=>{
            if(key!=='pk_test_fixture')throw Error('unexpected_offline_key');
            return {elements:(options:{clientSecret:string})=>{
              if(options.clientSecret!=='pi_fixture_secret_synthetic')throw Error('unexpected_offline_capability');
              return {create:(kind:string)=>{if(kind!=='payment')throw Error('unexpected_offline_element');return element;},update:()=>{}};
            },confirmPayment:()=>{confirmations++;throw Error('offline_confirmation_forbidden');}};
          },{version:'endive'})});
          const module=await import(origin+'/manual.mjs');
          const controller=module.mountManualFormPage(document.querySelector('main')!);
          (window as unknown as {probe:Probe}).probe={
            ready:()=>{const callback=callbacks.get('ready');if(!callback)throw Error('offline_ready_not_armed');callback();},
            changeOpacity:()=>{frame!.style.opacity=initiallyHidden?'1':'0';},close:()=>controller.close(),
            state:()=>{const box=frame?.getBoundingClientRect();return {mounted:!!frame?.isConnected,opacity:frame?getComputedStyle(frame).opacity:'',positiveArea:!!box&&box.width>0&&box.height>0,confirmations};},
          };
        },{origin,initiallyHidden});
        await page.getByRole('button',{name:'Загрузить форму',exact:true}).click();
        await page.waitForFunction(()=>(window as unknown as {probe:Probe}).probe.state().mounted);
        const observed=page.waitForResponse(response=>response.url()===origin+'/manual-observation'&&response.status()===204);
        await page.evaluate(()=>(window as unknown as {probe:Probe}).probe.ready());await observed;
        assert.equal(reports.length,1);assert.equal(reports[0]!.version,2);
        assert.equal(reports[0]!.sample.ready_seen,true);assert.equal(reports[0]!.sample.widget_state,'ready');assert.equal(reports[0]!.sample.operation,'COMPLETED');
        assert.equal(reports[0]!.evidence.host_visibility,'VISIBLE');assert.equal(reports[0]!.evidence.frame_box,'POSITIVE_AREA');
        assert.equal(reports[0]!.evidence.frame_visibility,initiallyHidden?'HIDDEN':'VISIBLE');
        const history=JSON.stringify(reports);
        const later=await page.evaluate(async()=>{
          const probe=(window as unknown as {probe:Probe}).probe;probe.changeOpacity();
          await new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve())));return probe.state();
        });
        assert.deepEqual(later,{mounted:true,opacity:initiallyHidden?'1':'0',positiveArea:true,confirmations:0});
        assert.equal(JSON.stringify(reports),history,'later outer style cannot rewrite the ready snapshot');assert.equal(reports.length,1);
        const status=await page.locator('main > [role="status"]').textContent();
        assert.match(status!,/снимок.*момент.*не обновляется/isu,'ready_snapshot_is_historical');
        assert.match(status!,/видны ли поля сейчас/iu,'human_visibility_is_current_not_reported');
        await page.evaluate(()=>(window as unknown as {probe:Probe}).probe.close());
        assert.equal(await page.locator('generic-payment-widget').count(),0);assert.equal(JSON.stringify(reports),history);
        assert.deepEqual(calls,['session','read','attempt','observation']);assert.equal(denied,0);assert.equal(sockets,0);
      }finally{await context.close();}
    });
  }finally{await browser?.close();await rm(directory,{recursive:true,force:true});}
});
