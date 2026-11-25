import assert from 'node:assert/strict';
import test from 'node:test';
import {createHash} from 'node:crypto';
import {chromium,type Request,type Route} from '@playwright/test';
// @ts-ignore Node runs this support suite directly, outside Playwright discovery.
import {createPortal201Guard,validatePortal201Body} from './stripe-billing-portal-response.ts';
// @ts-ignore The Node suite uses the same pinned Chromium route reader as the 3DS guard.
import {chromiumRouteReader,type ChromiumRouteEvidence} from './stripe-billing-chromium-route.ts';
// @ts-ignore The pure policy builder does not have a published declaration.
import {buildBillingPortalPolicyRequest} from '../../../../infrastructure/stripe-billing-provision.mjs';

const merchant='http://127.0.0.1:18081',api='http://127.0.0.1:18080';
const hostedUrl='https://billing.stripe.com/p/session?secret=test_syntheticChromiumOnly';
const id=(n:number)=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const customerId=id(1),siteId=id(2),subscriptionId=id(3),productId=id(4),priceId=id(5);
const endpoint=api+'/api/v1/customer/subscriptions/'+subscriptionId+'/portal-sessions';
const bearer='Bearer '+'C'.repeat(43),idempotencyKey='synthetic-chromium-portal-key';
const runId='11111111-2222-4333-8444-555555555555';
const account={id:id(6),provider:'STRIPE',mode:'SANDBOX',status:'ACTIVE',external_account_id:'acct_1UPiMpELswAd8tpR'};
const policyRequest=buildBillingPortalPolicyRequest({
  env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'},
  selectedCase:'initial_success',runId,expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
  approvedBinding:{externalAccountId:account.external_account_id,apiVersion:'2026-09-30.endive'},
},{
  account,site:{id:siteId,key:'s5a-'+runId.replaceAll('-',''),status:'ACTIVE',payment_account_id:account.id,
    allowed_origins:[merchant],authentication_modes:['EMAIL_OTP']},
  product:{id:productId,site_id:siteId,status:'ACTIVE'},
  price:{id:priceId,site_id:siteId,product_id:productId,type:'SUBSCRIPTION',status:'PUBLISHED',version:1,
    amount_minor:2000,currency:'EUR',billing_interval:'MONTH',trial_days:0,tax_mode:'INCLUSIVE',provider_price_id:'price_synthetic'},
  policy:{site_id:siteId,version:0,enabled:false,grace_days:3,allow_immediate_cancellation:false,
    portal_enabled:false,defer_upgrades:false,portal_return_urls:[]},
});
const policy:{portalReturnUrl:string;portalPolicyVersion:number}=policyRequest.validateResponse({site_id:siteId,...policyRequest.body,version:1});
const returnUrl=policy.portalReturnUrl;
const expectedBody=Buffer.from(JSON.stringify({return_url:returnUrl}));
const markup=`<!doctype html><html lang="en" data-owner="checkout-generation"><head><meta charset="utf-8"><link rel="icon" href="data:,"></head>
<body><button id="portal">Open customer portal</button><script>
  document.querySelector('#portal').addEventListener('click',async()=>{
    const result=await fetch(${JSON.stringify(endpoint)},{
      method:'POST',credentials:'omit',headers:{'Authorization':${JSON.stringify(bearer)},
        'Content-Type':'application/json','Idempotency-Key':${JSON.stringify(idempotencyKey)}},
      body:${JSON.stringify(expectedBody.toString())}
    });
    if(result.status!==201) return;
    const receipt=await result.json();
    if(receipt.simulated===false)location.assign(receipt.navigation_url);
  });
</script></body></html>`;
const cors={'access-control-allow-origin':merchant,'cache-control':'no-store'};
const hidden=(phase:string)=>new Error('synthetic_portal_'+phase);

async function chromiumComposition(replaceDocumentBeforeContinuation:boolean) {
  const channel=process.env.PAYMENT_TEST_CHROME_CHANNEL==='chrome'?'chrome':'chromium';
  const browser=await chromium.launch({channel,headless:true,timeout:10000,args:[
    '--site-per-process','--disable-background-networking','--proxy-server=direct://',
    '--host-resolver-rules=MAP * ~NOTFOUND','--disable-default-apps','--no-pings',
  ]});
  try {
    const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
    const done=Promise.withResolvers<void>(),failed=Promise.withResolvers<never>(),arming=Promise.withResolvers<void>();
    const repeated=Promise.withResolvers<void>();
    let timeout:ReturnType<typeof setTimeout>|undefined;
    const sqlToken=Promise.withResolvers<void>(),responseVerified=Promise.withResolvers<void>();
    for(const promise of [done.promise,failed.promise,arming.promise,responseVerified.promise,repeated.promise])void promise.catch(()=>{});
    const through=<T>(promise:Promise<T>)=>Promise.race([promise,failed.promise]);
    const stages:string[]=[];
    let original:Request|undefined,held:Route|undefined,continued=0,blocked=0,checks=0;
    let originalSizes:{declared:number;measured:number;read:number;playwrightEstimate:number}|undefined;
    let measured:number|undefined,receivedBytes:Buffer|undefined;
    let checkpoint='SETUP',diagnostic='';
    const expiresAt=new Date(Math.floor(Date.now()/1000)*1000+240000).toISOString().replace('.000Z','.000987Z');
    const responseBytes=Buffer.from(JSON.stringify({navigation_url:hostedUrl,expires_at:expiresAt,simulated:false}));
    const page=await context.newPage();
    const frame=page.mainFrame(),ownedDocument={};
    let generation=0,boundGeneration=-1,attached=true;
    page.on('framenavigated',value=>{if(value===frame)generation++;});
    page.on('framedetached',value=>{if(value===frame)attached=false;});
    page.on('close',()=>{attached=false;});
    page.on('crash',()=>{attached=false;});
    const protocol=await context.newCDPSession(page);
    const reader=chromiumRouteReader(page);
    let routeEvidence:ChromiumRouteEvidence|null|undefined;
    let captureStarted=false;
    let capture201:{networkId:string;fetchId:string;bytes:Buffer}|undefined;
    const live=()=>attached&&generation===boundGeneration&&frame===page.mainFrame()&&frame.url()===returnUrl;
    type NetworkReceipt={frameId:string;loaderId:string;chunks:number;status:number;finished:PromiseWithResolvers<number|null>};
    const network=new Map<string,NetworkReceipt>();
    protocol.on('Network.requestWillBeSent',event=>{
      const previous=network.get(event.requestId);
      if(previous){previous.status=0;previous.finished.resolve(null);return;}
      if(event.request.url===endpoint&&event.request.method==='POST'&&!event.redirectResponse&&event.frameId)
        network.set(event.requestId,{frameId:event.frameId,loaderId:event.loaderId,chunks:0,status:0,finished:Promise.withResolvers<number|null>()});
    });
    protocol.on('Network.responseReceived',event=>{
      const seen=network.get(event.requestId);
      if(seen&&event.frameId===seen.frameId&&event.loaderId===seen.loaderId&&event.response.url===endpoint&&!event.response.fromServiceWorker)
        seen.status=event.response.status;
    });
    protocol.on('Network.dataReceived',event=>{
      const seen=network.get(event.requestId);
      if(seen)seen.chunks+=event.dataLength;
    });
    protocol.on('Network.loadingFinished',event=>{
      const seen=network.get(event.requestId);
      if(seen){
        stages.push('CDP_BYTES_'+seen.chunks);
        seen.finished.resolve(seen.status===201&&Number.isSafeInteger(seen.chunks)&&seen.chunks>0?seen.chunks:null);
      }
    });
    protocol.on('Network.loadingFailed',event=>network.get(event.requestId)?.finished.resolve(null));
    protocol.on('Fetch.requestPaused',event=>{
      if(event.request.url!==endpoint)return;
      void (async()=>{
        if(event.request.method==='OPTIONS'){
          assert.equal(event.responseStatusCode,204);
          await protocol.send('Fetch.continueResponse',{requestId:event.requestId});
          return;
        }
        const capture=routeEvidence;
        assert.ok(original&&capture&&!captureStarted&&live());
        captureStarted=true;
        assert.equal(original.existingResponse(),null);
        assert.equal(capture.targetId,reader?.mainId);
        assert.equal(event.networkId,capture.networkId);
        assert.equal(event.frameId,capture.frameId);
        // The response-stage Fetch ID is session-local. The pinned Network ID
        // ties it to the exact held Playwright request in the same target and frame.
        assert.ok(event.requestId);
        assert.equal(event.request.method,'POST');
        assert.ok(event.resourceType==='Fetch'||event.resourceType==='XHR');
        assert.equal(event.responseStatusCode,201);
        assert.equal(event.responseErrorReason,undefined);
        assert.equal(event.redirectedRequestId,undefined);
        const headers=new Map<string,string>();
        for(const {name,value} of event.responseHeaders??[]){
          const key=name.toLowerCase();
          assert.ok(!headers.has(key));
          headers.set(key,value);
        }
        const length=headers.get('content-length');
        assert.ok(length&&/^[1-9][0-9]{0,4}$/.test(length)&&Number(length)<=32768);
        assert.equal(headers.get('content-type'),'application/json');
        assert.equal(headers.get('cache-control'),'no-store');
        assert.ok(!headers.has('content-encoding')||headers.get('content-encoding')==='identity');
        assert.ok(!headers.has('location'));
        stages.push('ORIGINAL_201_PAUSED');
        const body=await protocol.send('Fetch.getResponseBody',{requestId:event.requestId});
        const bytes=Buffer.from(body.body,body.base64Encoded?'base64':'utf8');
        assert.equal(bytes.length,Number(length));
        const validated=validatePortal201Body(bytes);
        assert.equal(validated.expiresAt,expiresAt);
        assert.equal(routeEvidence,capture);
        assert.ok(live()&&original.existingResponse()===null);
        capture201={networkId:capture.networkId,fetchId:event.requestId,bytes};
        stages.push('ORIGINAL_201_BYTES_VALIDATED_BEFORE_RELEASE');
        await protocol.send('Fetch.continueResponse',{requestId:capture201.fetchId});
      })().catch(()=>failed.reject(hidden('FETCH_RESPONSE')));
    });
    await protocol.send('Network.enable');
    await protocol.send('Fetch.enable',{patterns:[{urlPattern:endpoint,resourceType:'Fetch',requestStage:'Response'}]});
    const authority={customerId,siteId,subscriptionId,bearer,revision:3,frame,document:ownedDocument,returnUrl};
    const guard=createPortal201Guard({backendOrigin:api,demoOrigin:merchant,authority,
      policy},async()=>{
      const observed=++checks;
      stages.push('LIVE_'+observed+'_ENTER');
      const exact=live();
      stages.push('LIVE_'+observed+'_'+(exact?'OK':'LOST'));
      return exact?authority:null;
    },{deadline:Date.now()+8000},async(request,response)=>{
      const capture=routeEvidence,receipt=capture201;
      assert.ok(original&&request===original&&response===original.existingResponse()&&capture&&capture.targetId===reader?.mainId);
      assert.ok(receipt&&receipt.networkId===capture.networkId&&receipt.fetchId);
      const seen=network.get(capture.networkId);
      assert.ok(seen&&seen.frameId===capture.frameId);
      const count=await seen.finished.promise;
      assert.ok(count!==null&&seen.status===201&&routeEvidence===capture&&capture201===receipt);
      measured=count;
      receivedBytes=receipt.bytes;
      capture201=undefined;
      return {measured:count,bytes:receipt.bytes};
    });
    const unexpected=async(route:Route,phase:string)=>{
      await route.abort('blockedbyclient').catch(()=>{});
      failed.reject(hidden(phase));
    };
    const exactPair=()=>{
      const response=original?.existingResponse();
      return Boolean(original&&response&&response.request()===original&&response.status()===201);
    };
    function metadata(request:Request) {
      return [
        request.frame()===frame,request.redirectedFrom()===null,request.serviceWorker()===null,
        request.isNavigationRequest()===false,request.postDataBuffer()?.equals(expectedBody),request.existingResponse()===null,
      ].join('/');
    }
    try {
      timeout=setTimeout(()=>failed.reject(hidden('FIXTURE_TIMEOUT')),12000);
      await context.routeWebSocket('**/*',socket=>socket.close({code:1008,reason:'Synthetic fixture'}));
      page.on('response',response=>{
        if(!original||response.request()!==original)return;
        void (async()=>{
          checkpoint='PAIRED_RESPONSE';
          assert.ok(exactPair(),'reciprocal Request and Response identity');
          assert.equal(response.fromServiceWorker(),false);
          const receipt=await guard.observe201(response);
          assert.deepEqual(receipt,{expiresAt});
          const [length,sizes]=await Promise.all([response.headerValue('content-length'),original!.sizes()]);
          const bytes=receivedBytes;
          assert.ok(bytes);
          assert.ok(measured);
          originalSizes={declared:Number(length),measured,read:bytes.byteLength,playwrightEstimate:sizes.responseBodySize};
          assert.equal(originalSizes.declared,responseBytes.length);
          assert.equal(originalSizes.measured,responseBytes.length);
          assert.equal(originalSizes.read,responseBytes.length);
          assert.ok(Number.isSafeInteger(originalSizes.playwrightEstimate)&&originalSizes.playwrightEstimate>0);
          assert.equal(bytes.equals(responseBytes),true);
          stages.push('ACTUAL_BYTE_PROOF');
          responseVerified.resolve();
        })().catch(()=>failed.reject(hidden(checkpoint)));
      });
      await context.route('**/*',async route=>{
        const request=route.request(),method=request.method(),url=request.url();
        try {
          if(method==='GET'&&url===returnUrl){
            stages.push('SYNTHETIC_MERCHANT');
            await route.fulfill({status:200,contentType:'text/html',body:markup});
            return;
          }
          if(method==='OPTIONS'&&url===endpoint){
            stages.push('SYNTHETIC_PREFLIGHT');
            await route.fulfill({status:204,headers:{...cors,'access-control-allow-methods':'POST',
              'access-control-allow-headers':'authorization,content-type,idempotency-key'}});
            return;
          }
          if(method==='POST'&&url===endpoint&&!original){
            checkpoint='CLAIM';
            original=request;
            routeEvidence=reader?.capture(request);
            assert.ok(routeEvidence?.current()&&routeEvidence.targetId===reader?.mainId);
            diagnostic=metadata(request);
            const receipt=await guard.claimRequest(request);
            assert.equal(receipt,createHash('sha256').update(idempotencyKey).digest('hex'));
            assert.equal(original,route.request());
            assert.equal(original.existingResponse(),null);
            stages.push('ORIGINAL_POST_BEFORE_201');
            await route.fulfill({status:201,headers:{...cors,'content-type':'application/json',
              'content-length':String(responseBytes.length)},body:responseBytes});
            return;
          }
          if(method==='GET'&&url===hostedUrl&&!held){
            checkpoint='HOSTED_ROUTE';
            held=route;
            let armReturned=false;
            try {
              await guard.continueHostedNavigation({
                request:()=>route.request(),
                async continue(){
                  assert.equal(armReturned,true);
                  assert.equal(held,route);
                  assert.equal(route.request(),request);
                  assert.equal(request.existingResponse(),null);
                  assert.ok(exactPair());
                  continued++;
                  stages.push('EXACT_ORIGINAL_ROUTE_FULFILLED');
                  await route.fulfill({status:200,contentType:'text/html',body:`<!doctype html><link rel="icon" href="data:,"><title>Synthetic Portal</title>`});
                },
              },async(candidate):Promise<true>=>{
                assert.equal(candidate,request);
                assert.ok(exactPair());
                assert.equal(request.existingResponse(),null);
                stages.push('HOSTED_GET_HELD_FOR_SQL');
                arming.resolve();
                await sqlToken.promise;
                assert.equal(request.existingResponse(),null);
                assert.ok(exactPair());
                armReturned=true;
                return true;
              });
              if(replaceDocumentBeforeContinuation)throw hidden('STALE_DOCUMENT_CONTINUED');
              done.resolve();
            } catch(error) {
              if(!replaceDocumentBeforeContinuation||!(error instanceof Error)||error.message!=='billing_BROWSER_PORTAL_RECEIPT')throw error;
              blocked++;
              await route.abort('blockedbyclient').catch(()=>{});
              done.resolve();
            }
            return;
          }
          if(method==='GET'&&url===hostedUrl&&held){
            assert.notEqual(request,held.request());
            let attempted=false;
            await assert.rejects(guard.continueHostedNavigation({
              request:()=>route.request(),
              async continue(){attempted=true;throw hidden('REPEATED_ROUTE_CONTINUATION');},
            },()=>assert.fail()),/^Error: billing_BROWSER_PORTAL_RECEIPT$/);
            assert.equal(attempted,false);
            stages.push('REPEATED_HOSTED_GET_REFUSED');
            await route.abort('blockedbyclient');
            repeated.resolve();
            return;
          }
          await unexpected(route,'UNEXPECTED_ROUTE');
        } catch {
          await unexpected(route,checkpoint);
        }
      });
      await page.goto(returnUrl,{timeout:3000});
      assert.ok(await page.evaluate(()=>document.documentElement.getAttribute('data-owner')==='checkout-generation'));
      boundGeneration=generation;
      await page.getByRole('button',{name:'Open customer portal',exact:true}).focus({timeout:3000});
      await page.keyboard.press('Enter');
      await through(arming.promise);
      await through(responseVerified.promise);
      assert.ok(held&&originalSizes);
      assert.equal(continued,0);
      assert.equal(held.request().existingResponse(),null);
      assert.equal(frame.url(),returnUrl);
      assert.ok(exactPair());
      assert.ok(checks>=7,'checkout document witness checked during original request and response');
      assert.ok(stages.indexOf('ORIGINAL_POST_BEFORE_201')<stages.indexOf('ORIGINAL_201_PAUSED'));
      assert.ok(stages.indexOf('ORIGINAL_201_BYTES_VALIDATED_BEFORE_RELEASE')<stages.indexOf('HOSTED_GET_HELD_FOR_SQL'));
      const beforeSqlRelease=checks;
      if(replaceDocumentBeforeContinuation){
        await page.goto(returnUrl,{timeout:3000});
        assert.equal(frame.url(),returnUrl);
        assert.ok(generation>boundGeneration);
      }
      sqlToken.resolve();
      await through(done.promise);
      assert.ok(checks>beforeSqlRelease,'one fresh checkout-generation check after the mock SQL token');
      if(replaceDocumentBeforeContinuation){
        assert.equal(continued,0);
        assert.equal(blocked,1);
        assert.ok(!stages.includes('EXACT_ORIGINAL_ROUTE_FULFILLED'));
      }else{
        assert.equal(continued,1);
        assert.equal(blocked,0);
        assert.ok(stages.indexOf('HOSTED_GET_HELD_FOR_SQL')<stages.indexOf('EXACT_ORIGINAL_ROUTE_FULFILLED'));
        await page.waitForFunction(()=>document.title==='Synthetic Portal',{},{timeout:3000});
        const repeat=page.goto(hostedUrl,{timeout:3000}).catch(()=>null);
        await through(repeated.promise);
        await repeat;
        assert.equal(continued,1);
        assert.ok(stages.includes('REPEATED_HOSTED_GET_REFUSED'));
      }
      return {originalSizes,checks,stages};
    }catch{
      throw new Error('Chromium fixture stopped with fixed checkpoint: '+checkpoint+
        ' ('+diagnostic+'; '+stages.join(',')+'; checks='+checks+')');
    }finally{
      sqlToken.resolve();
      if(timeout)clearTimeout(timeout);
    }
  } finally {
    await browser.close().catch(()=>{});
  }
}

test('Chromium holds the original Portal GET until the mock SQL token and refuses a repeat',{timeout:20000},async()=>{
  const result=await chromiumComposition(false);
  assert.ok(result.originalSizes&&result.originalSizes.read>0);
  console.log('Measured synthetic HTTP 201 bytes '+JSON.stringify(result.originalSizes));
});
test('Chromium original hosted GET is refused when a fresh same-URL document commits during the SQL hold',{timeout:20000},async()=>{await chromiumComposition(true);});
