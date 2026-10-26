import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'vite';
import {chromium,type Browser} from '@playwright/test';
import type {SandboxFixture} from './stripe-sandbox-fixtures';
// @ts-expect-error Native Node type erasure requires the source extension.
import {runSandboxCard} from './stripe-sandbox-fixtures.ts';

// DOM compatibility only. No Stripe service, Sandbox, card fields or financial
// confirmation are exercised. Every HTTP and WebSocket boundary is intercepted.
test('native Chromium projects real Catalog/Donation provider mounts and retires their ownership offline',async()=>{
  const root=fileURLToPath(new URL('../..',import.meta.url));
  const directory=await mkdtemp(join(tmpdir(),'payment-provider-native-'));
  const origin='http://127.0.0.1:14176',api=origin+'/api/v1/public';
  const checkout='11111111-1111-4111-8111-111111111111',payment='22222222-2222-4222-8222-222222222222',fakeCheckout='66666666-6666-4666-8666-666666666666';
  const expires='2099-01-01T00:00:00Z',token='SYNTHETIC_TEST_TOKEN_'+'x'.repeat(22);
  const price={price_id:'33333333-3333-4333-8333-333333333333',price_key:'native-price',version:1,amount_minor:4999,currency:'EUR',tax_mode:'NONE'};
  const wire={checkout_id:checkout,payment_id:payment,checkout_token:token,amount_minor:4999,currency:'EUR',price,
    provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',provider_client_payload:{},status:'REQUIRES_PAYMENT_METHOD',expires_at:expires};
  const requests:string[]=[];let denied=0,sockets=0;
  let browser:Awaited<ReturnType<typeof chromium.launch>>|undefined;
  try{
    await build({root,configFile:false,logLevel:'silent',plugins:[{name:'native-test-entry',resolveId(id){if(id.endsWith('/native-test-entry'))return '\0native-test-entry';},load(id){if(id==='\0native-test-entry')return `export * from ${JSON.stringify(root+'/../../frontend/sdk/src/index.ts')};export {ownedProviderHost} from ${JSON.stringify(root+'/../../frontend/sdk/src/widget/provider-mount.ts')};export {createManualFormReadinessObserver} from ${JSON.stringify(root+'/src/manual-form-readiness.ts')};`;}}],
      build:{outDir:directory,lib:{entry:root+'/native-test-entry',formats:['es'],fileName:()=> 'sdk.js'},rolldownOptions:{output:{codeSplitting:false}}}});
    const bundle=await readFile(join(directory,'sdk.js'),'utf8');
    browser=await chromium.launch({headless:true,args:['--disable-background-networking','--disable-component-update','--no-first-run']});
    const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
    await context.routeWebSocket('**/*',socket=>{sockets++;socket.close();});
    await context.route('**/*',async route=>{
      const request=route.request(),url=request.url(),method=request.method();
      if(url===origin+'/'&&method==='GET')return route.fulfill({contentType:'text/html',body:'<!doctype html><html><body><main></main></body></html>'});
      if(url===origin+'/sdk.js'&&method==='GET')return route.fulfill({contentType:'text/javascript',body:bundle});
      if(url===api+'/sites/test-site/donation-campaigns/help-campaign'&&method==='GET'){
        requests.push('campaign');return route.fulfill({json:{key:'help-campaign',display_name:'Synthetic campaign',purpose_description:'Offline DOM contract',currency:'EUR',suggested_amounts:[4999],minimum_amount:100,maximum_amount:10000,custom_amount_allowed:true,anonymous_allowed:true}});
      }
      if(url===api+'/sites/test-site/donation-campaigns/help-campaign/checkouts'&&method==='POST'){
        requests.push('donation');return route.fulfill({status:201,json:{...wire,price:null,donation:{donation_id:'44444444-4444-4444-8444-444444444444',campaign_key:'help-campaign',campaign_version:2}}});
      }
      if(url===api+'/checkouts/'+checkout&&method==='GET'){
        requests.push('read');const {checkout_token:_,...read}=wire;return route.fulfill({json:read});
      }
      if(url===api+'/checkouts/'+fakeCheckout&&method==='GET'){
        requests.push('fake-read');const {checkout_token:_,...read}=wire;return route.fulfill({json:{...read,checkout_id:fakeCheckout,provider_type:'FAKE',confirmation_mode:'SERVER',price:{...price,price_key:'native-fake'}}});
      }
      if(url===api+'/checkouts/'+checkout+'/client-session'&&method==='POST'){
        requests.push('attempt');return route.fulfill({json:{checkout_id:checkout,payment_id:payment,attempt_id:'55555555-5555-4555-8555-555555555555',provider_type:'STRIPE',confirmation_mode:'CLIENT_PROVIDER',publishable_key:'pk_test_fixture',client_secret:'pi_fixture_secret_synthetic',expires_at:expires,return_url:origin+'/payment-return'}});
      }
      denied++;await route.abort('blockedbyclient');
    });
    const page=await context.newPage();await page.goto(origin+'/');
    const result=await page.evaluate(async({origin,checkout,payment,expires,token,fakeCheckout})=>{
      const check=(value:unknown,message:string)=>{if(!value)throw Error(message);};
      // Only installed, locally bundled SDK bytes enter this isolated document.
      const sdk=await import(origin+'/sdk.js');
      const events:{host:HTMLElement;frame:HTMLIFrameElement;listeners:Map<string,()=>void>;destroyed:boolean}[]=[];
      let confirmations=0;
      Object.defineProperty(window,'Stripe',{value:Object.assign((key:string)=>{
        check(key==='pk_test_fixture','synthetic publishable boundary');
        return {elements:(options:{clientSecret:string})=>{
          check(options.clientSecret==='pi_fixture_secret_synthetic','synthetic capability boundary');
          return {create:(kind:string)=>{
            check(kind==='payment','payment element only');const listeners=new Map<string,()=>void>();
            const frame=document.createElement('iframe');frame.style.width='240px';frame.style.height='80px';
            let record:typeof events[number];
            const element={on:(name:string,callback:()=>void)=>{listeners.set(name,callback);return element;},off:(name:string)=>{listeners.delete(name);return element;},
              mount:(host:HTMLElement)=>{
                check(host.getRootNode()===document&&document.documentElement.contains(host),'provider rejects shadow/non-document host');
                check(host.parentElement?.shadowRoot&&host.assignedSlot?.getRootNode()===host.parentElement.shadowRoot,'provider requires projected owned widget child');
                record={host,frame,listeners,destroyed:false};events.push(record);host.append(frame);
              },destroy:()=>{if(record)record.destroyed=true;frame.remove();},focus:()=>{}};
            return element;
          },update:()=>{}};
        },confirmPayment:()=>{confirmations++;throw Error('offline_confirmation_forbidden');}};
      },{version:'endive'})});
      sdk.defineGenericPaymentWidget();sdk.defineGenericDonation();
      const state={checkoutId:checkout,paymentId:payment,status:'REQUIRES_PAYMENT_METHOD',amountMinor:4999,currency:'EUR',expiresAt:expires,price:{priceId:'33333333-3333-4333-8333-333333333333',priceKey:'native-price',version:1,amountMinor:4999,currency:'EUR',taxMode:'NONE'}};
      const recovery={version:2,apiBaseUrl:origin,siteKey:'test-site',priceKey:'native-price',checkoutId:checkout,checkoutToken:token,expiresAt:expires,providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state};
      sessionStorage.setItem(`generic-payments:v1:${encodeURIComponent(origin)}:test-site:native-price`,JSON.stringify(recovery));
      const catalog=new sdk.GenericPaymentWidget();catalog.apiBaseUrl=origin;catalog.siteKey='test-site';catalog.priceKey='native-price';catalog.locale='en';
      document.querySelector('main')!.append(catalog);
      const until=async(predicate:()=>boolean)=>{const end=Date.now()+4000;while(!predicate()){check(Date.now()<end,'native contract did not settle');await new Promise(resolve=>setTimeout(resolve,5));}};
      const buttons=(widget:HTMLElement)=>Array.from(widget.shadowRoot!.querySelectorAll('button'));
      await until(()=>buttons(catalog).some(button=>button.textContent==='Resume existing payment'&&!button.hidden&&!button.disabled));
      const evidence=sdk.createManualFormReadinessObserver({getWidget:()=>catalog,isActive:()=>catalog.isConnected,readinessSnapshot:()=>({loaderstart_seen:false})});evidence.snapshot();
      buttons(catalog).find(button=>button.textContent==='Resume existing payment')!.click();await until(()=>events.length===1);
      const first=events[0]!;await until(()=>evidence.snapshot().frame_load==='SEEN');
      check(catalog.state==='loading'&&!buttons(catalog).some(button=>button.getAttribute('aria-label')==='Pay'&&!button.hidden&&!button.disabled),'iframe-only must stay pending');
      check(sdk.ownedProviderHost(catalog)===first.host,'reader shares actual SDK registry');
      const initial=evidence.snapshot();check(initial.host_box==='POSITIVE_AREA'&&initial.frame_box==='POSITIVE_AREA'&&initial.host_visibility==='VISIBLE','real projected geometry');
      const slot=first.host.assignedSlot!,wrapper=slot.parentElement!;
      wrapper.hidden=true;check(evidence.snapshot().frame_visibility==='HIDDEN','composed wrapper visibility');wrapper.hidden=false;
      slot.hidden=true;check(evidence.snapshot().host_visibility==='HIDDEN','composed slot visibility');slot.hidden=false;
      catalog.hidden=true;check(evidence.snapshot().frame_visibility==='HIDDEN','widget ancestor visibility');catalog.hidden=false;
      first.listeners.get('ready')!();await until(()=>catalog.state==='ready');
      const donation=new sdk.GenericDonation();donation.apiBaseUrl=origin;donation.siteKey='test-site';donation.campaignKey='help-campaign';donation.locale='en';donation.setAttribute('customer-session','omit');document.querySelector('main')!.append(donation);
      await until(()=>buttons(donation).some(button=>button.textContent==='Create donation'&&!button.disabled));buttons(donation).find(button=>button.textContent==='Create donation')!.click();
      await until(()=>events.length===2);const second=events[1]!;
      check(sdk.ownedProviderHost(donation)===second.host&&second.host!==first.host,'independent widget lease');
      check(!buttons(donation).some(button=>button.textContent==='Pay'&&!button.disabled&&!button.hidden),'donation iframe-only pending');
      second.listeners.get('ready')!();await until(()=>buttons(donation).some(button=>button.textContent==='Pay'&&!button.disabled&&!button.hidden));
      catalog.locale='pt-PT';donation.locale='pt-PT';catalog.dispatchEvent(new Event('payment-theme-change'));donation.dispatchEvent(new Event('payment-theme-change'));
      check(events.length===2&&sdk.ownedProviderHost(catalog)===first.host&&sdk.ownedProviderHost(donation)===second.host,'presentation retains mounts');
      const foreign=document.createElement('div');foreign.className='payment-element';catalog.prepend(foreign);check(sdk.ownedProviderHost(catalog)===first.host,'matching class is not ownership');
      first.host.remove();catalog.append(first.host);check(sdk.ownedProviderHost(catalog)===undefined,'same-turn host reinsertion retires lease');check(sdk.ownedProviderHost(donation)===second.host,'other instance survives');
      evidence.close();catalog.remove();check(first.destroyed&&!first.host.isConnected,'catalog cleanup');
      document.querySelector('main')!.append(catalog);check(sdk.ownedProviderHost(catalog)===undefined,'reconnect does not restore old lease');
      await until(()=>buttons(catalog).some(button=>!button.hidden&&!button.disabled&&button.textContent==='Retomar pagamento existente'));
      buttons(catalog).find(button=>button.textContent==='Retomar pagamento existente')!.click();await until(()=>events.length===3);
      const successor=events[2]!;check(sdk.ownedProviderHost(catalog)===successor.host&&successor.host!==first.host,'fresh generation after cleanup');
      successor.listeners.get('ready')!();await until(()=>catalog.state==='ready');catalog.remove();
      donation.remove();check(second.destroyed&&!second.host.isConnected,'donation cleanup');
      const fake=new sdk.GenericPaymentWidget();fake.apiBaseUrl=origin;fake.siteKey='test-site';fake.priceKey='native-fake';fake.locale='en';
      sessionStorage.setItem(`generic-payments:v1:${encodeURIComponent(origin)}:test-site:native-fake`,JSON.stringify({...recovery,checkoutId:fakeCheckout,priceKey:'native-fake',providerType:'FAKE',confirmationMode:'SERVER',state:{...state,checkoutId:fakeCheckout,price:{...state.price,priceKey:'native-fake'}}}));
      document.querySelector('main')!.append(fake);
      await until(()=>fake.state==='ready');
      const fakeHost=sdk.ownedProviderHost(fake);check(fakeHost?.getRootNode()===fake.shadowRoot&&fake.shadowRoot!.querySelector('slot')===null,'Fake keeps shadow mount');fake.remove();
      return {mounts:events.length,confirmations,load:initial.frame_load,geometry:initial.host_box,cleaned:events.every(event=>event.destroyed)};
    },{origin,checkout,payment,expires,token,fakeCheckout});
    assert.deepEqual(result,{mounts:3,confirmations:0,load:'SEEN',geometry:'POSITIVE_AREA',cleaned:true});
    assert.deepEqual(requests,['read','attempt','campaign','donation','attempt','read','attempt','fake-read']);
    assert.equal(denied,0,'no unexpected HTTP boundary');assert.equal(sockets,0,'no WebSocket boundary');
    await context.close();
  }finally{await browser?.close();await rm(directory,{recursive:true,force:true});}
});

// Break: looking for the issuer frame at page level skips Stripe's private
// lightbox wrapper. Exercise the actual driver and frame traversal, not a mock
// that accepts any frameLocator chain. All documents below are artificial.
test('native Chromium completes nested SCA without selecting unrelated private frames',async()=>{
  const origin='http://127.0.0.1:14177';
  const outer='https://js.stripe.com/v3/three-ds-2-challenge-synthetic.html';
  const documents=new Map<string,string>([
    [origin+'/',`<!doctype html><generic-payment-widget><button>Resume existing payment</button><iframe title="Secure payment input frame" src="${origin}/card"></iframe><button aria-label="Pay" id="pay">Pay</button></generic-payment-widget>
      <iframe name="__privateStripeFrameUnrelated" src="${origin}/decoy"></iframe>
      <script>document.querySelector('#pay').onclick=()=>{const frame=document.createElement('iframe');frame.name='__privateStripeFrameSynthetic';frame.src='${outer}';document.body.append(frame);};
      window.addEventListener('message',event=>{if(event.origin==='${origin}'&&event.data==='synthetic-sca-complete')document.body.dataset.scaComplete='yes';});</script>`],
    [origin+'/card','<!doctype html><input name="number"><input name="expiry"><input name="cvc">'],
    [origin+'/decoy','<!doctype html><button>Complete</button>'],
    [outer,`<!doctype html><iframe id="challengeFrame" name="stripe-challenge-frame" title="3DS Challenge" src="${origin}/issuer"></iframe>`],
    [origin+'/issuer',`<!doctype html><iframe name="acsFrame" src="${origin}/acs"></iframe>`],
    [origin+'/acs',`<!doctype html><button id="complete">Complete</button><script>document.querySelector('#complete').onclick=()=>window.top.postMessage('synthetic-sca-complete','${origin}');</script>`],
  ]);
  const requests:string[]=[],actions:string[]=[];let denied=0,sockets=0,confirmed=false;
  const browser=await chromium.launch({headless:true,args:['--disable-background-networking','--disable-component-update','--no-first-run']});
  try{
    const isolated={newContext:async(options:Parameters<Browser['newContext']>[0])=>{
      const context=await browser.newContext({...options,acceptDownloads:false});
      await context.routeWebSocket('**/*',socket=>{sockets++;socket.close();});
      await context.route('**/*',async route=>{
        const request=route.request(),url=new URL(request.url());
        const document=url.origin===origin&&url.pathname==='/'?origin+'/':url.href,body=documents.get(document);
        if(request.method()==='GET'&&body!==undefined){requests.push(document);return route.fulfill({contentType:'text/html',body});}
        denied++;await route.abort('blockedbyclient');
      });
      return context;
    }} as Browser;
    const fixture={identity:{endpoints:{backend:origin,demo:origin},site:{key:'synthetic-site'},price:{key:'synthetic-price'}},
      beginBrowser:async(name:string)=>{actions.push('begin:'+name);return true;},
      browserSession:async()=>({apiBaseUrl:origin,siteKey:'synthetic-site',priceKey:'synthetic-price'}),
      markPay:async(name:string)=>{actions.push('pay:'+name);},
      confirm:async(name:string)=>{
        const page=browser.contexts()[0]!.pages()[0]!;
        await page.waitForFunction(()=>document.body.dataset.scaComplete==='yes',undefined,{timeout:1000});
        actions.push('confirm:'+name);confirmed=true;
      }} as unknown as SandboxFixture;
    let completed=false;
    try{await runSandboxCard(isolated,fixture,'sca');completed=true;}catch{/* No raw browser failure or frame URL is reported. */}
    assert.deepEqual(actions.slice(0,2),['begin:sca','pay:sca'],'reach the real SCA boundary before testing its traversal');
    assert.equal(completed,true,'the actual driver must cross the private wrapper before server confirmation');
    assert.equal(confirmed,true);assert.deepEqual(actions,['begin:sca','pay:sca','confirm:sca']);
    assert.deepEqual(requests.sort(),[origin+'/',origin+'/card',origin+'/decoy',outer,origin+'/issuer',origin+'/acs'].sort());
    assert.equal(denied,0);assert.equal(sockets,0);assert.equal(browser.contexts().length,0);
  }finally{await browser.close();}
});
