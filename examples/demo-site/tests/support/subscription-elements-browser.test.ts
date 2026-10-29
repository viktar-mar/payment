import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'vite';
import {chromium} from '@playwright/test';

// Dedicated native DOM contract. No provider helpers or default Playwright runner.
// Build uses source bytes, a synthetic loader and no config/environment discovery.
test('native subscription PAYMENT and SETUP mount visibly, wait for ready and retire after one nested challenge',async()=>{
  const root=fileURLToPath(new URL('../..',import.meta.url)),directory=await mkdtemp(join(tmpdir(),'payment-subscription-native-'));
  let browser:Awaited<ReturnType<typeof chromium.launch>>|undefined;
  try {
    const nginx=await readFile(join(root,'nginx.conf'),'utf8'),returnHtml=await readFile(join(root,'subscription-return.html'),'utf8');
    const csp=/add_header Content-Security-Policy "([^"]+)"/.exec(nginx)?.[1],prescrub=/<script>([\s\S]*?)<\/script>/.exec(returnHtml)?.[1];
    assert.ok(csp,'owned deployment CSP exists');assert.ok(prescrub,'owned return scrub exists');
    await build({root,configFile:false,envFile:false,logLevel:'silent',plugins:[{
      name:'synthetic-subscription-entry',
      enforce:'pre',
      resolveId(id){if(id.endsWith('/subscription-native-entry'))return '\0subscription-native-entry';if(id==='../providers/stripe-loader.js'||id==='./stripe-loader.js'||id.endsWith('/providers/stripe-loader.ts')||id.endsWith('/providers/stripe-loader.js'))return '\0subscription-synthetic-loader';},
      load(id){if(id==='\0subscription-native-entry')return `export * from ${JSON.stringify(root+'/../../frontend/sdk/src/index.ts')};export {ownedProviderHost} from ${JSON.stringify(root+'/../../frontend/sdk/src/widget/provider-mount.ts')};`;if(id==='\0subscription-synthetic-loader')return 'export async function loadStripeClient(key){return window.__subscriptionStripeFactory(key)}';},
    }],build:{outDir:directory,lib:{entry:root+'/subscription-native-entry',formats:['es'],fileName:()=> 'sdk.js'},rolldownOptions:{output:{codeSplitting:false}}}});
    const bundle=await readFile(join(directory,'sdk.js'),'utf8');
    browser=await chromium.launch({headless:true,args:['--disable-background-networking','--disable-component-update','--no-first-run']});
    for(const flow of ['PAYMENT','SETUP'] as const){
      const origin='http://127.0.0.1:14178',site='08ac693f-6f49-4a8d-bd14-e5ef47a1dc18',customer='11111111-1111-4111-8111-111111111111',product='5db5813b-277e-4bb1-87e3-8625a0c294dc',priceId='98f16bdb-3f7a-4b1f-bf02-01d1ced46b75',subscription='8ad38d60-f78a-44b3-9881-722eea250289',operation='f1d1b7e8-4158-40d1-ae13-ea053568f314';
      const token='C'.repeat(43),grant='G'.repeat(43),secret=flow==='PAYMENT'?'pi_fixture_secret_synthetic':'seti_fixture_secret_synthetic';
      const expires=new Date(Date.now()+120000).toISOString(),actionExpires=new Date(Date.now()+90000).toISOString(),clientExpires=new Date(Date.now()+60000).toISOString();
      const price={price_id:priceId,price_key:'native-monthly',version:1,product_id:product,amount_minor:1000,currency:'EUR',interval:'MONTH',trial_days:0,trial_payment_method_policy:'REQUIRED_BEFORE_TRIAL',tax_mode:'NONE'};
      const attempt='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',capability='bca2_'+'A'.repeat(43);
      const attemptStatus=()=>({protocol:'SERVER_CONFIRM_V2',attempt_id:attempt,state:preflight?'REQUIRES_ACTION':'READY',retry_allowed:false,successor_attempt_id:null,expires_at:clientExpires});
      const op={operation_id:operation,subscription_id:subscription,status:'PROCESSING',action_available:false};
      const now=new Date().toISOString(),end=new Date(Date.now()+86400000).toISOString(),valid=new Date(Date.now()+240000).toISOString();
      const view={subscription_id:subscription,site_id:site,customer_id:customer,product_id:product,payment_account_id:'b773932b-2075-4ead-8e9b-5cd7aab43d4c',billing_price:price,entitled_price:price,state:'ACTIVE',billing_status:'ACTIVE',revision:1,period:{start:now,end},paid_through:end,trial:null,cancellation:null,pending_change:null,pending_operation:null,debt:null,access:{allowed:true,reason:'PAID',site_id:site,customer_id:customer,product_id:product,subscription_id:subscription,entitled_price_id:priceId,entitled_price_version:1,revision:1,evaluated_at:now,valid_until:valid},permitted_actions:[],next_amount:{amount_minor:1000,currency:'EUR',tax_final:true},created_at:now,updated_at:now};
      let settled=false,denied=0,sockets=0,create=0,issue=0,preflight=0,reads=0,leaked=false;
      const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
      try {
        await context.routeWebSocket('**/*',socket=>{sockets++;socket.close();});
        await context.route('**/*',async route=>{
          const request=route.request(),url=new URL(request.url()),path=url.pathname,method=request.method();
          if(url.origin!==origin){denied++;return route.abort('blockedbyclient');}
          if(path==='/'&&method==='GET')return route.fulfill({contentType:'text/html',headers:{'Content-Security-Policy':csp,'Referrer-Policy':'no-referrer'},body:`<!doctype html><html><head><meta name="referrer" content="no-referrer"><script>${prescrub}</script><link rel="icon" href="data:,"></head><body><main></main></body></html>`});
          if(path==='/sdk.js'&&method==='GET')return route.fulfill({contentType:'text/javascript',body:bundle});
          if(path==='/challenge'&&method==='GET')return route.fulfill({contentType:'text/html',body:'<!doctype html><iframe title="Synthetic issuer" src="/acs"></iframe>'});
          if(path==='/acs'&&method==='GET')return route.fulfill({contentType:'text/html',body:'<!doctype html><button id="complete">Complete</button><script>document.querySelector("#complete").onclick=()=>window.top.postMessage("synthetic-subscription-sca",location.origin)</script>'});
          if(path.startsWith('/api/v1/customer/')){
            assert.equal(request.headers()['authorization']===`Bearer ${token}`,true,'owned synthetic Customer authorization');
            if(path===`/api/v1/customer/subscription-products/${product}/offers`&&method==='GET')return route.fulfill({json:{items:[{price,direction:null,allowed_timings:[],trial_eligible:false}]}});
            const base='/api/v1/customer/subscriptions',original=`${base}/${subscription}/operations/${operation}`;
            if(path===base&&method==='GET')return route.fulfill({json:{items:settled?[view]:[],next_cursor:null}});
            if(path===base&&method==='POST'){create++;assert.equal(!!request.headers()['idempotency-key'],true);return route.fulfill({status:202,json:{...op,next_action:null,browser_confirm_allowed:false}});}
            if(path===original&&method==='GET'){reads++;return route.fulfill({json:{...op,status:settled?'SUCCEEDED':'PROCESSING'}});}
            if(path===original+'/action'&&method==='GET')return route.fulfill({json:{kind:'PROVIDER_CLIENT',secret:grant,expires_at:actionExpires}});
            if(path===original+'/confirmation-attempts/current'&&method==='GET')return route.fulfill({json:issue&&flow==='PAYMENT'?attemptStatus():null});
            if(path===original+'/confirmation-attempts/'+attempt&&method==='GET')return route.fulfill({json:attemptStatus()});
            if(path===original+'/confirmation-attempts/'+attempt+'/confirm'&&method==='POST'){preflight++;assert.deepEqual(request.postDataJSON(),{capability,confirmation_token:'ctoken_fixture'});return route.fulfill({json:{...attemptStatus(),next_action_allowed:preflight===1}});}
            if(path===original+'/confirmation-session'&&method==='POST'){issue++;const body=request.postDataJSON();assert.equal(Object.keys(body).join(',')==='action_token'&&body.action_token===grant,true,'exact synthetic session body');return route.fulfill({headers:{'Cache-Control':'no-store'},json:{protocol:flow==='PAYMENT'?'SERVER_CONFIRM_V2':'BROWSER_CONFIRM_V1',provider:'stripe',flow,publishable_key:'pk_test_fixture',client_secret:secret,...(flow==='PAYMENT'?{attempt_id:attempt,capability}:{action_token:grant}),expires_at:clientExpires}});}
            if(path===original+'/action-confirmations'&&method==='POST'){preflight++;return route.fulfill({status:202,json:{...op,next_action:null,browser_confirm_allowed:preflight===1}});}
            if([`${base}/${subscription}/billing-records`,`${base}/${subscription}/events`].includes(path)&&method==='GET')return route.fulfill({json:{items:[],next_cursor:null}});
          }
          denied++;await route.abort('blockedbyclient');
        });
        const page=await context.newPage();
        const sensitive=[token,grant,secret,'pk_test_fixture'];page.on('console',message=>{if(sensitive.some(value=>message.text().includes(value)))leaked=true;});page.on('pageerror',error=>{if(sensitive.some(value=>error.message.includes(value)))leaked=true;});
        await page.goto(origin+'/?return_marker=synthetic#fragment');
        assert.equal(new URL(page.url()).search===''&&new URL(page.url()).hash==='',true,'CSP permits the exact pre-boot return scrub');
        const mounted:{mounts:number;payment:number;setup:number}=await page.evaluate(async({origin,site,customer,product,priceId,token,expires,flow,secret,grant,prescrub,csp})=>{
          const check=(value:unknown,message:string)=>{if(!value)throw Error(message);};
          const hash=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(prescrub));
          check(csp.includes("'sha256-"+btoa(String.fromCharCode(...new Uint8Array(hash)))+"'"),'exact return scrub bytes retain their approved CSP hash');
          const records:{host:HTMLElement;listeners:Map<string,()=>void>;destroyed:boolean}[]=[];let payment=0,setup=0;
          const state={records,loaderCalls:0,sessionsResolved:0,errors:[] as string[],get payment(){return payment;},get setup(){return setup;},manager:null as HTMLElement|null};(window as any).__nativeSubscription=state;
          (window as any).__subscriptionStripeFactory=(key:string)=>{
            state.loaderCalls++;
            check(key==='pk_test_fixture','synthetic publishable boundary');
            const confirm=(kind:string)=>{if(kind==='PAYMENT')payment++;else setup++;return new Promise(resolve=>{
              const frame=document.createElement('iframe');frame.title='Synthetic subscription challenge';frame.src='/challenge';document.body.append(frame);
              const complete=(event:MessageEvent)=>{if(event.origin===origin&&event.data==='synthetic-subscription-sca'){window.removeEventListener('message',complete);frame.remove();resolve({});}};window.addEventListener('message',complete);
            });};
            return {elements:(options:{clientSecret:string})=>{check(options.clientSecret===secret,'synthetic flow context');return {submit:async()=>({}),create:()=>{
              const listeners=new Map<string,()=>void>();let record:typeof records[number];const frame=document.createElement('iframe');frame.title='Synthetic subscription field';frame.style.height='80px';
              const element={on:(name:string,listener:()=>void)=>{listeners.set(name,listener);return element;},off:(name:string)=>{listeners.delete(name);return element;},mount:(host:HTMLElement)=>{
                check(host.isConnected&&host.getRootNode()===document&&host.assignedSlot,'connected projected owned host');check([...host.getClientRects()].some(r=>r.width>0&&r.height>0),'visible host before provider mount');
                record={host,listeners,destroyed:false};records.push(record);host.append(frame);
              },destroy:()=>{if(record)record.destroyed=true;frame.remove();}};return element;
            }};},createConfirmationToken:async()=>({confirmationToken:{id:'ctoken_fixture'}}),handleNextAction:()=>confirm('PAYMENT'),confirmPayment:()=>{throw Error('v2 browser confirmation is forbidden');},confirmSetup:()=>confirm('SETUP')};
          };
          const sdk=await import(origin+'/sdk.js');sdk.registerGenericSubscriptionManager();
          const manager=document.createElement('generic-subscription-manager') as any;state.manager=manager;manager.client=sdk.createGenericPaymentClient({apiBaseUrl:origin});manager.customerSession={apiBaseUrl:origin,siteKey:'test-site',siteId:site,origin,customer:{id:customer,status:'ACTIVE',maskedEmail:null},sessionToken:token,expiresAt:expires,scopes:['SUBSCRIPTION_READ','SUBSCRIPTION_MANAGE']};manager.productId=product;manager.priceId=priceId;document.querySelector('main')!.append(manager);
          const issue=manager.client.subscriptions.confirmationSession;manager.client.subscriptions.clientSession=async(input:unknown)=>{try{const result=await issue(input);state.sessionsResolved++;return result;}catch(error){state.errors.push((error as {code?:string}).code??'FIXTURE_UNCLASSIFIED');throw error;}};
          manager.addEventListener('generic-subscription-error',(event:CustomEvent)=>state.errors.push(event.detail.code));
          const until=async(predicate:()=>boolean,phase:string)=>{const end=Date.now()+4000;while(!predicate()){check(Date.now()<end,'native subscription '+phase+' did not settle');await new Promise(resolve=>setTimeout(resolve,5));}};
          const buttons=()=>[...manager.shadowRoot.querySelectorAll('button')] as HTMLButtonElement[];const button=(name:string)=>buttons().find(b=>b.textContent===name&&!b.hidden)!;
          await until(()=>!!button('Create subscription')&&!button('Create subscription').disabled,'create-ready');manager.shadowRoot.querySelector('input[type=checkbox]').click();button('Create subscription').click();
          await until(()=>records.length===1,'provider-mounted');check(button('Pay').disabled,'iframe insertion is not readiness');check(sdk.ownedProviderHost(manager)===records[0]!.host,'actual mount identity');records[0]!.listeners.get('ready')!();await until(()=>!button('Pay').disabled,'element-ready');
          manager.locale='pt';manager.locale='en';check(records.length===1&&records[0]!.host.isConnected,'presentation retains original host');button('Pay').click();button('Pay').click();await until(()=>payment+setup===1,'challenge-dispatched');
          check(flow==='PAYMENT'?payment===1&&setup===0:setup===1&&payment===0,'exact confirmation flow');
          const sinks=[manager.outerHTML,manager.shadowRoot.innerHTML,JSON.stringify(manager.state),JSON.stringify({...sessionStorage}),location.href];check(!sinks.some(value=>[grant,secret,'pk_test_fixture'].some(v=>value.includes(v))),'capability sink boundary');
          return {mounts:records.length,payment,setup};
        },{origin,site,customer,product,priceId,token,expires,flow,secret,grant,prescrub,csp}).catch(async()=>{
          const safe=await page.evaluate(()=>{const s=(window as any).__nativeSubscription;return {phase:s?.manager?.state.phase??'none',loaderCalls:s?.loaderCalls??0,sessionsResolved:s?.sessionsResolved??0,errors:s?.errors??[]};});
          throw Error(`Native state boundary: phase=${safe.phase}, creates=${create}, issues=${issue}, reads=${reads}, sessionsResolved=${safe.sessionsResolved}, loaders=${safe.loaderCalls}, denied=${denied}, errors=${safe.errors.join(',')}`);
        });
        assert.equal(mounted.mounts,1);assert.equal(mounted.payment+mounted.setup,1);
        await page.frameLocator('iframe[title="Synthetic subscription challenge"]').frameLocator('iframe[title="Synthetic issuer"]').getByRole('button',{name:'Complete'}).click();
        const pending=await page.evaluate(async()=>{
          const state=(window as any).__nativeSubscription,end=Date.now()+4000;while(!state.records[0].destroyed){if(Date.now()>end)throw Error('retirement timeout');await new Promise(r=>setTimeout(r,5));}
          return {retired:state.records[0].destroyed,hosts:state.manager.children.length,billing:state.manager.state.subscription?.billingStatus??null,pending:state.manager.state.operation?.status,confirmations:state.payment+state.setup};
        });
        assert.deepEqual(pending,{retired:true,hosts:0,billing:null,pending:'PROCESSING',confirmations:1});
        settled=true;
        await page.waitForFunction(()=>((window as any).__nativeSubscription.manager.state.subscription?.billingStatus==='ACTIVE'),undefined,{timeout:4000});
        assert.equal(create,1);assert.equal(issue,1);assert.equal(preflight,1);assert.equal(reads>=2,true);assert.equal(denied,0);assert.equal(sockets,0);assert.equal(leaked,false);
      }finally{await context.close();}
    }
  }finally{await browser?.close();await rm(directory,{recursive:true,force:true});}
});
