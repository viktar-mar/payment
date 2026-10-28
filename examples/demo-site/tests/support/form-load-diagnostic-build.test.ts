import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
import {Worker,isMainThread,parentPort,workerData} from 'node:worker_threads';
import {build} from 'vite';

if(isMainThread)test('actual diagnostic ESM build preserves its registry and ready path in synthetic DOM only',async t=>{
  const directory=await mkdtemp(join(tmpdir(),'payment-form-graph-'));
  try {
    const root=fileURLToPath(new URL('../..',import.meta.url));
    await build({root,configFile:root+'/vite.config.ts',mode:'form-diagnostic',logLevel:'silent',build:{outDir:directory}});
    const files=await readdir(join(directory,'assets'));
    const entry=files.find(name=>/^formDiagnostic-.*\.js$/.test(name));assert.ok(entry);
    const run=async(scenario:string)=>{
      const worker=new Worker(new URL(import.meta.url),{workerData:{entry:pathToFileURL(join(directory,'assets',entry)).href,scenario}});
      return new Promise<unknown>((resolve,reject)=>{
        const timer=setTimeout(()=>reject(new Error('offline_graph_check_timeout')),10000);
        worker.once('message',value=>{clearTimeout(timer);resolve(value);});
        worker.once('error',error=>{clearTimeout(timer);reject(error);});
      }).finally(()=>worker.terminate());
    };
    await t.test('bootstrap alone has no network or provider authority',async()=>{
      assert.deepEqual(await run('bootstrap'),{handshake:true,oneWidget:true,readonly:true,armed:true,onlyReaders:true,networkCalls:0});
    });
    for(const scenario of ['delayed-ready','synchronous-ready'])await t.test(scenario,async()=>{
      assert.deepEqual(await run(scenario),{widget:'ready',operation:'COMPLETED',payEnabled:true,requests:['GET','POST'],confirmations:0});
    });
    await t.test('iframe-only remains pending through the packaged reader',async()=>{
      assert.deepEqual(await run('frame-only'),{widget:'loading',operation:'PENDING',element:'FRAME',ready:false,confirmations:0});
    });
    await t.test('same-turn host reinsertion permanently loses the packaged binding',async()=>{
      assert.deepEqual(await run('host-loss'),{binding:'LOST',element:'NO_HOST',ready:false,confirmations:0});
    });
  }finally {await rm(directory,{recursive:true,force:true});}
});
else if(!isMainThread){
  // A worker isolates the built page's deliberately non-configurable read-only
  // facade from the Node test host. jsdom is the existing SDK test dependency;
  // no real browser is launched, no resource loader/network is enabled.
  const sdkRequire=createRequire(new URL('../../../../frontend/sdk/package.json',import.meta.url));
  const {JSDOM}=sdkRequire('jsdom') as {JSDOM:new(html:string,options:{url:string})=>{window:Window&typeof globalThis}};
  const dom=new JSDOM('<main id="diagnostic"></main>',{url:'http://127.0.0.1:14173/form-load-diagnostic.html'});
  const window=dom.window;
  for(const name of ['window','document','HTMLElement','customElements','sessionStorage','location','navigator','CustomEvent','MutationObserver','getComputedStyle'] as const)
    Object.defineProperty(globalThis,name,{value:name==='window'?window:window[name],configurable:true});
  Object.defineProperty(globalThis,'__formDiagnosticSession',{value:{apiBaseUrl:'http://127.0.0.1:18080',siteKey:'test-site',priceKey:'test-price'}});
  let networkCalls=0;globalThis.fetch=async()=>{networkCalls++;throw new Error('offline_network_forbidden');};
  try {
    if(workerData.scenario!=='bootstrap'){
      const checkout='11111111-1111-4111-8111-111111111111',payment='22222222-2222-4222-8222-222222222222';
      const expiresAt='2099-01-01T00:00:00Z',apiBaseUrl='http://127.0.0.1:18080';
      const price={priceId:'33333333-3333-4333-8333-333333333333',priceKey:'test-price',version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'};
      const state={checkoutId:checkout,paymentId:payment,amountMinor:2000,currency:'EUR',price,status:'CREATED',expiresAt};
      window.sessionStorage.setItem(`generic-payments:v1:${encodeURIComponent(apiBaseUrl)}:test-site:test-price`,JSON.stringify({
        version:2,checkoutId:checkout,checkoutToken:'SYNTHETIC_TEST_TOKEN_'+'x'.repeat(22),expiresAt,apiBaseUrl,
        siteKey:'test-site',priceKey:'test-price',providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state,
      }));
      const requests:string[]=[],listeners=new Map<string,()=>void>();let confirmations=0;
      const frame=window.document.createElement('iframe');
      const snapshot=()=>Object.getOwnPropertyDescriptor(globalThis,'__formLoadDiagnostic')?.value.snapshot();
      const element={
        on:(name:string,callback:()=>void)=>{listeners.set(name,callback);return element;},
        off:(name:string)=>{listeners.delete(name);return element;},
        mount:(host:HTMLElement)=>{
          const widget=window.document.querySelector('generic-payment-widget')!;
          assert.equal(host.isConnected,true);assert.equal(host.getRootNode(),window.document);
          assert.equal(host.parentElement,widget);assert.equal(host.assignedSlot?.getRootNode(),widget.shadowRoot);
          host.append(frame);
          assert.equal(snapshot().ready_seen,false);
          if(workerData.scenario==='synchronous-ready'){
            listeners.get('ready')?.();
            assert.equal(snapshot().ready_seen,true);assert.equal(snapshot().mount_returned,false);
          }
        },
        destroy:()=>frame.remove(),focus:()=>{},
      };
      const stripeFactory=Object.assign((key:string)=>{
        assert.equal(key,'pk_test_fixture');
        return {elements:(options:{clientSecret:string})=>{
          assert.equal(options.clientSecret,'pi_fixture_secret_sensitive');
          return {create:(type:string)=>{assert.equal(type,'payment');return element;},update:()=>{}};
        },confirmPayment:()=>{confirmations++;throw new Error('offline_confirmation_forbidden');}};
      },{version:'endive'});
      // Keep the real packaged lazy loader; replace only its remote Stripe boundary.
      Object.defineProperty(window,'Stripe',{value:stripeFactory});
      globalThis.fetch=async(input,init)=>{
        const url=new URL(String(input));const method=init?.method;
        assert.equal(url.origin,apiBaseUrl);
        if(url.pathname===`/api/v1/public/checkouts/${checkout}`&&method==='GET'){
          requests.push('GET');
          return Response.json({checkout_id:checkout,payment_id:payment,amount_minor:2000,currency:'EUR',
            price:{price_id:price.priceId,price_key:'test-price',version:1,amount_minor:2000,currency:'EUR',tax_mode:'NONE'},
            provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'CREATED',expires_at:expiresAt});
        }
        assert.equal(url.pathname,`/api/v1/public/checkouts/${checkout}/client-session`);assert.equal(method,'POST');
        requests.push('POST');
        return Response.json({checkout_id:checkout,payment_id:payment,attempt_id:'44444444-4444-4444-8444-444444444444',
          provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',publishable_key:'pk_test_fixture',
          client_secret:'pi_fixture_secret_sensitive',expires_at:expiresAt,return_url:'http://127.0.0.1:14173/payment-return'});
      };
      const until=async(predicate:()=>boolean)=>{
        const deadline=Date.now()+3000;
        while(!predicate()){
          assert.ok(Date.now()<deadline,'offline packaged ready path did not settle');
          await new Promise(resolve=>setTimeout(resolve,1));
        }
      };
      await import(workerData.entry);
      const widget=window.document.querySelector('generic-payment-widget') as HTMLElement&{state:string};
      const resume=widget.shadowRoot!.querySelectorAll('button')[1] as HTMLButtonElement;
      await until(()=>!resume.hidden&&!resume.disabled);
      assert.equal(snapshot().binding,'ARMED');resume.click();
      await until(()=>frame.isConnected);
      assert.equal(snapshot().element_state,'FRAME');
      if(workerData.scenario==='frame-only'){
        assert.equal(widget.shadowRoot!.querySelector('button[aria-label="Pay"]:enabled'),null);
        parentPort?.postMessage({widget:widget.state,operation:snapshot().operation,element:snapshot().element_state,ready:snapshot().ready_seen,confirmations});
      }else if(workerData.scenario==='host-loss'){
        const host=frame.parentElement!;host.remove();widget.append(host);
        const lost=snapshot();listeners.get('ready')?.();
        parentPort?.postMessage({binding:lost.binding,element:lost.element_state,ready:snapshot().ready_seen,confirmations});
      }else{
      if(workerData.scenario==='delayed-ready'){
        assert.equal(widget.state,'loading');
        assert.equal(widget.shadowRoot!.querySelector('button[aria-label="Pay"]:enabled'),null);
        assert.equal(snapshot().ready_seen,false);assert.equal(snapshot().operation,'PENDING');
        assert.equal(snapshot().mount_returned,true);
        listeners.get('ready')?.();
      }
      await until(()=>widget.state==='ready');
      const sample=snapshot();
      assert.equal(sample.element_state,'FRAME','automatic reader resolves the owned Light DOM host');
      for(const flag of ['capability_started','capability_ready','loader_started','loader_ready','element_created',
        'mount_called','mount_returned','ready_seen','view_completed','widget_completed'])assert.equal(sample[flag],true,flag);
      assert.equal(sample.binding,'BOUND');assert.equal(sample.loaderror_seen,false);
      assert.equal(sample.operation,'COMPLETED');
      const pay=widget.shadowRoot!.querySelector('button[aria-label="Pay"]') as HTMLButtonElement;
      parentPort?.postMessage({widget:widget.state,operation:sample.operation,payEnabled:!pay.hidden&&!pay.disabled,requests,confirmations});
      }
    }else{
      await import(workerData.entry);
      const descriptor=Object.getOwnPropertyDescriptor(globalThis,'__formLoadDiagnostic');
      const api=descriptor?.value as {handshake:()=>boolean;snapshot:()=>{binding:string}}|undefined;
      parentPort?.postMessage({handshake:api?.handshake()===true,oneWidget:window.document.querySelectorAll('generic-payment-widget').length===1,
        readonly:descriptor?.writable===false&&descriptor.configurable===false&&Object.isFrozen(api),armed:api?.snapshot().binding==='ARMED',
        onlyReaders:!!api&&Object.keys(api).sort().join(',')==='handshake,snapshot',networkCalls});
    }
  }finally {window.close();}
}
