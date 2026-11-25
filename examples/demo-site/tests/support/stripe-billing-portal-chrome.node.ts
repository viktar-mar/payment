import assert from 'node:assert/strict';
import test from 'node:test';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {chromium,type Request,type Route} from '@playwright/test';
import type {BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import {billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
// @ts-ignore Node runs this support suite directly, outside Playwright discovery.
import {createPortal201Guard,validatePortal201Body} from './stripe-billing-portal-response.ts';
// @ts-ignore The Node suite uses the same pinned Chromium route reader as the 3DS guard.
import {chromiumRouteReader,type ChromiumRouteEvidence} from './stripe-billing-chromium-route.ts';
// @ts-ignore The pure policy builder does not have a published declaration.
import {buildBillingPortalPolicyRequest} from '../../../../infrastructure/stripe-billing-provision.mjs';

// @ts-ignore The fixture owns the injected stages and reads the tracked SQL.
import {createBillingPortalFlow} from './stripe-billing-portal-flow.node.mjs';

const merchant='http://127.0.0.1:18081',api='http://127.0.0.1:18080';
const hostedUrl='https://billing.stripe.com/p/session?secret=test_syntheticChromiumOnly';
const id=(n:number)=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const customerId=id(1),siteId=id(2),subscriptionId=id(3),productId=id(4),priceId=id(5);
const paidEndpoint=api+'/api/v1/customer/subscriptions/'+subscriptionId;
const endpoint=paidEndpoint+'/portal-sessions';
const bearer='Bearer '+'C'.repeat(43),idempotencyKey='synthetic-chromium-portal-key';
const runId='11111111-2222-4333-8444-555555555555';
const account={id:id(6),provider:'STRIPE',mode:'SANDBOX',status:'ACTIVE',external_account_id:'acct_1UPiMpELswAd8tpR'};
const configuration={
  env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'},
  selectedCase:'initial_success',runId,expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
  approvedBinding:{externalAccountId:account.external_account_id,apiVersion:'2026-09-30.endive'},
};
const policyReceipts={
  account,site:{id:siteId,key:'s5a-'+runId.replaceAll('-',''),status:'ACTIVE',payment_account_id:account.id,
    allowed_origins:[merchant],authentication_modes:['EMAIL_OTP']},
  product:{id:productId,site_id:siteId,status:'ACTIVE'},
  price:{id:priceId,site_id:siteId,product_id:productId,type:'SUBSCRIPTION',status:'PUBLISHED',version:1,
    amount_minor:2000,currency:'EUR',billing_interval:'MONTH',trial_days:0,tax_mode:'INCLUSIVE',provider_price_id:'price_synthetic'},
  policy:{site_id:siteId,version:0,enabled:false,grace_days:3,allow_immediate_cancellation:false,
    portal_enabled:false,defer_upgrades:false,portal_return_urls:[]},
};
const policyRequest=buildBillingPortalPolicyRequest(configuration,policyReceipts);
const policyAck={site_id:siteId,...policyRequest.body,version:1};
const policy:{portalReturnUrl:string;portalPolicyVersion:number}=policyRequest.validateResponse(policyAck);
const returnUrl=policy.portalReturnUrl;
const wrongReturn=returnUrl+'&wrong_return=1';
const exactSql=await readFile(new URL('../../../../infrastructure/stripe-billing-portal-proof.sql',import.meta.url),'utf8');
const digest=(value:string)=>createHash('sha256').update(value).digest('hex');
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
function hostedMarkup(destination:string) {
  return `<!doctype html><link rel="icon" href="data:,"><title>Synthetic Portal</title>
  <a id="return">Return to merchant</a><script>
    const link=document.querySelector('#return');link.href=${JSON.stringify(destination)};
    link.addEventListener('click',event=>{if(!event.isTrusted)event.preventDefault();});
  </script>`;
}
const returnedMarkup='<!doctype html><link rel="icon" href="data:,"><title>Synthetic Merchant Return</title>';
const cors={'access-control-allow-origin':merchant,'cache-control':'no-store'};
const hidden=(phase:string)=>new Error('synthetic_portal_'+phase);

type Mode='repeat-hosted'|'stale-checkout'|'exact'|'wrong-return'|'replacement'|'aborted-return'|'aborted-grant';
type OpeningPort={returnUrl:string;beforeDispatch(key:string):Promise<true>;observe201(receipt:{expiresAt:string}):Promise<object>};
type Witness={tab:object;document:object};
type SqlCall={mode:string;query:string;args:readonly unknown[]};
async function chromiumComposition(mode:Mode) {
  const channel=process.env.PAYMENT_TEST_CHROME_CHANNEL==='chrome'?'chrome':'chromium';
  const browser=await chromium.launch({channel,headless:true,timeout:10000,args:[
    '--site-per-process','--disable-background-networking','--proxy-server=direct://',
    '--host-resolver-rules=MAP * ~NOTFOUND','--disable-default-apps','--no-pings',
  ]});
  try {
    const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
    const done=Promise.withResolvers<void>(),failed=Promise.withResolvers<never>(),arming=Promise.withResolvers<void>();
    const repeated=Promise.withResolvers<void>(),openingProof=Promise.withResolvers<object>();
    const returnHeld=Promise.withResolvers<void>();
    const ownerAbort=new AbortController();
    const returnToken=Promise.withResolvers<boolean>(),returnCompleted=Promise.withResolvers<Request>(),returnRefused=Promise.withResolvers<void>();
    let timeout:ReturnType<typeof setTimeout>|undefined;
    const sqlToken=Promise.withResolvers<void>(),responseVerified=Promise.withResolvers<void>();
    for(const promise of [done.promise,failed.promise,arming.promise,responseVerified.promise,repeated.promise,openingProof.promise,returnHeld.promise,returnCompleted.promise,returnRefused.promise])void promise.catch(()=>{});
    const through=<T>(promise:Promise<T>)=>Promise.race([promise,failed.promise]);
    const settleReturnDuringCleanup=()=>returnToken.resolve(false);
    const waitForReturnDisposition=()=>through(Promise.race([returnCompleted.promise,returnRefused.promise]));
    const stages:string[]=[];
    let original:Request|undefined,held:Route|undefined,continued=0,checks=0;
    let originalSizes:{declared:number;measured:number;read:number;playwrightEstimate:number}|undefined;
    let measured:number|undefined,receivedBytes:Buffer|undefined;
    let checkpoint='SETUP',diagnostic='';
    const expiresAt=new Date(Math.floor(Date.now()/1000)*1000+240000).toISOString().replace('.000Z','.000987Z');
    const responseBytes=Buffer.from(JSON.stringify({navigation_url:hostedUrl,expires_at:expiresAt,simulated:false}));
    const now=Math.floor(Date.now()/1000);
    const graph={runId,expectedSource:configuration.expectedSource,accountId:account.id,siteId,customerId,subscriptionId,
      operationId:id(7),priceId,priceVersion:1,periodStart:now-60,periodEnd:now+3600};
    const scope={...graph,productId,siteKey:policyReceipts.site.key,createKeyHash:digest('create'),confirmKeyHash:digest('confirm'),
      endpoints:{demo:merchant,backend:api}};
    const origin={billingRecordId:id(8),paymentId:id(9),cashEventId:id(10),paidEventId:id(11),revision:3,
      callbackDigests:{[id(10)]:digest('cash'),[id(11)]:digest('paid')}};
    const instant=(seconds:number)=>new Date(seconds*1000).toISOString();
    const paid={siteId,customerId,subscriptionId,billingStatus:'ACTIVE',access:{allowed:true,reason:'PAID'},
      revision:origin.revision,paidThrough:instant(graph.periodEnd)};
    const expectedSql:readonly unknown[]=[account.id,siteId,customerId,subscriptionId,graph.operationId,origin.billingRecordId,
      origin.paymentId,priceId,1,digest(idempotencyKey),returnUrl,expiresAt,origin.revision,instant(graph.periodStart),instant(graph.periodEnd)];
    const sqlCalls:SqlCall[]=[];
    let openingPort:OpeningPort|undefined;
    const page=await context.newPage();
    const frame=page.mainFrame(),ownedDocument={};
    let generation=0,boundGeneration=-1,attached=true;
    page.on('framenavigated',value=>{if(value===frame)generation++;});
    page.on('framedetached',value=>{if(value===frame)attached=false;});
    page.on('close',()=>{attached=false;});
    page.on('crash',()=>{attached=false;});
    const protocol=await context.newCDPSession(page);
    const reader=chromiumRouteReader(page);
    const commits=new Map<string,{frameId:string;url:string}>();
    const navigations=new Map<string,{frameId:string;loaderId:string;url:string}>();
    let hostedRouteEvidence:ChromiumRouteEvidence|null|undefined;
    let returnedRouteEvidence:ChromiumRouteEvidence|null|undefined;
    let hostedWitness:{tab:object;document:object;generation:number}|undefined;
    let merchantWitness:{tab:object;document:object;generation:number}|undefined;
    let returnRoute:Route|undefined,returnActionArmed=false,merchantNavigations=0,returnReleased=0,paidReads=0,providerMutations=0;
    const hostedOrigin=new URL(hostedUrl).origin;
    let returnLimits:Readonly<BillingLimits>|undefined;
    function currentReturnAuthority(limits:Readonly<BillingLimits>|undefined) {
      if(!limits||limits!==returnLimits)return false;
      try { billingCheck(limits); return true; }
      catch { return false; }
    }
    const nativeReturn=Promise.withResolvers<{url:string;generation:number}|null>();
    protocol.on('Page.frameNavigated',event=>{
      if(event.frame.id===reader?.mainId)commits.set(event.frame.loaderId,{frameId:event.frame.id,url:event.frame.url});
    });
    protocol.on('Page.frameRequestedNavigation',event=>{
      if(!returnActionArmed||event.frameId!==reader?.mainId)return;
      stages.push(event.reason==='anchorClick'?'ANCHOR_NAVIGATION':'NON_ANCHOR_NAVIGATION');
      nativeReturn.resolve(event.reason==='anchorClick'&&event.disposition==='currentTab'?{url:event.url,generation}:null);
    });
    function verifyCommit(request:Request,evidence:ChromiumRouteEvidence|null|undefined,url:string) {
      const used=evidence&&navigations.get(evidence.networkId);
      const commit=used&&commits.get(used.loaderId);
      const response=request.existingResponse();
      assert.ok(evidence&&used&&commit&&response&&frame===page.mainFrame()&&frame.url()===url&&attached);
      assert.equal(used.url,url);
      assert.equal(used.frameId,evidence.frameId);
      assert.equal(commit.frameId,evidence.frameId);
      assert.equal(commit.url,url);
      assert.equal(response.request(),request);
      assert.equal(response.status(),200);
      assert.equal(response.fromServiceWorker(),false);
    }
    let routeEvidence:ChromiumRouteEvidence|null|undefined;
    let captureStarted=false;
    let capture201:{networkId:string;fetchId:string;bytes:Buffer}|undefined;
    const live=()=>attached&&generation===boundGeneration&&frame===page.mainFrame()&&frame.url()===returnUrl;
    type NetworkReceipt={frameId:string;loaderId:string;chunks:number;status:number;finished:PromiseWithResolvers<number|null>};
    const network=new Map<string,NetworkReceipt>();
    protocol.on('Network.requestWillBeSent',event=>{
      if(event.type==='Document'&&event.frameId===reader?.mainId&&!event.redirectResponse)
        navigations.set(event.requestId,{frameId:event.frameId!,loaderId:event.loaderId,url:event.request.url});
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
    await protocol.send('Page.enable');
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
    },{deadline:Date.now()+16000},async(request,response)=>{
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
      timeout=setTimeout(()=>failed.reject(hidden('FIXTURE_TIMEOUT')),20000);
      await context.routeWebSocket('**/*',socket=>socket.close({code:1008,reason:'Synthetic fixture'}));
      page.on('response',response=>{
        if(!original||response.request()!==original)return;
        void (async()=>{
          checkpoint='PAIRED_RESPONSE';
          assert.ok(exactPair(),'reciprocal Request and Response identity');
          assert.equal(response.fromServiceWorker(),false);
          const receipt=await guard.observe201(response);
          assert.deepEqual(receipt,{expiresAt});
          assert.ok(openingPort);
          void openingPort.observe201(receipt).then(openingProof.resolve,()=>failed.reject(hidden('OWNER_SQL_BEFORE_HOSTED')));
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
          const requestOrigin=new URL(url).origin;
          if(requestOrigin===hostedOrigin&&method!=='GET'){
            providerMutations++;
            await unexpected(route,'PROVIDER_WRITE');
            return;
          }
          if(request.isNavigationRequest()&&requestOrigin===merchant&&merchantNavigations>0&&
            (mode!=='stale-checkout'||returnActionArmed)){
            checkpoint='RETURN_ROUTE';
            assert.ok(!returnRoute);
            returnRoute=route;
            const evidence=reader?.capture(request);
            returnedRouteEvidence=evidence;
            const ownedReturnLimits=returnLimits;
            const requireHeldReturn=async()=>{
              if(!currentReturnAuthority(ownedReturnLimits))return false;
              const intent=await nativeReturn.promise;
              if(!currentReturnAuthority(ownedReturnLimits))return false;
              if(intent&&hostedWitness&&generation!==hostedWitness.generation){
                stages.push('RETURN_SOURCE_DOCUMENT_REPLACED');
                return false;
              }
              if(intent&&(intent.url!==returnUrl||request.url()!==returnUrl)){
                stages.push('RETURN_ADDRESS_MISMATCH');
                return false;
              }
              return returnActionArmed&&Boolean(intent&&hostedWitness&&intent.generation===hostedWitness.generation
                &&frame.url()===hostedUrl
                &&attached&&frame===page.mainFrame()&&request.frame()===frame&&route.request()===request
                &&request.method()==='GET'&&request.isNavigationRequest()
                &&request.redirectedFrom()===null&&request.serviceWorker()===null
                &&request.postDataBuffer()===null&&request.existingResponse()===null
                &&evidence&&returnedRouteEvidence===evidence&&evidence.targetId===reader?.mainId&&evidence.current());
            };
            if(!await requireHeldReturn()){
              stages.push('RETURN_REFUSED');
              await route.abort('blockedbyclient');
              returnRefused.resolve();
              return;
            }
            stages.push('RETURN_HELD');
            returnHeld.resolve();
            const granted=await returnToken.promise;
            const stillHeld=granted===true&&await requireHeldReturn();
            const active=currentReturnAuthority(ownedReturnLimits);
            if(!stillHeld||!active){
              stages.push(!granted?'RETURN_RELEASE_DENIED':active?'STALE_RETURN_REFUSED':'RETURN_OWNER_INACTIVE');
              await route.abort('blockedbyclient').catch(()=>{});
              returnRefused.resolve();
              return;
            }
            returnActionArmed=false;
            assert.equal(++returnReleased,1);
            await route.fulfill({status:200,contentType:'text/html',body:returnedMarkup});
            returnCompleted.resolve(request);
            return;
          }
          if(method==='GET'&&url===returnUrl){
            const count=++merchantNavigations;
            assert.ok(count===1||(count===2&&mode==='stale-checkout'&&held&&continued===0));
            stages.push('SYNTHETIC_MERCHANT');
            await route.fulfill({status:200,contentType:'text/html',body:markup});
            return;
          }
          if(method==='OPTIONS'&&(url===endpoint||url===paidEndpoint)){
            stages.push('SYNTHETIC_PREFLIGHT');
            await route.fulfill({status:204,headers:{...cors,'access-control-allow-methods':url===endpoint?'POST':'GET',
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
            assert.ok(openingPort);
            assert.equal(await openingPort.beforeDispatch(receipt),true);
            assert.equal(receipt,digest(idempotencyKey));
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
            hostedRouteEvidence=reader?.capture(request);
            assert.ok(hostedRouteEvidence?.current()&&hostedRouteEvidence.targetId===reader?.mainId);
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
                  await route.fulfill({status:200,contentType:'text/html',body:hostedMarkup(mode==='wrong-return'?wrongReturn:returnUrl)});
                },
              },async(candidate):Promise<true>=>{
                assert.equal(candidate,request);
                assert.ok(exactPair());
                assert.equal(request.existingResponse(),null);
                stages.push('HOSTED_GET_HELD_FOR_SQL');
                arming.resolve();
                await openingProof.promise;
                assert.equal(request.existingResponse(),null);
                assert.ok(exactPair());
                armReturned=true;
                return true;
              });
              assert.notEqual(mode,'stale-checkout');
            } catch(error) {
              if(mode!=='stale-checkout'||!(error instanceof Error)||error.message!=='billing_BROWSER_PORTAL_RECEIPT')throw error;
              stages.push('STALE_CHECKOUT_REFUSED');
              await route.abort('blockedbyclient').catch(()=>{});
            }
            done.resolve();
            return;
          }
          if(mode==='replacement'&&method==='GET'&&url===hostedUrl&&returnRoute&&held){
            assert.equal(returnReleased,0);
            await route.fulfill({status:200,contentType:'text/html',body:hostedMarkup(returnUrl)});
            return;
          }
          if(mode==='repeat-hosted'&&method==='GET'&&url===hostedUrl&&held){
            assert.notEqual(request,held.request());
            let attempted=false;
            await assert.rejects(guard.continueHostedNavigation({
              request:()=>route.request(),
              async continue(){attempted=true;throw hidden('REPEATED_ROUTE_CONTINUATION');},
            },()=>assert.fail()),/^Error: billing_BROWSER_PORTAL_RECEIPT$/);
            assert.equal(attempted,false);
            await route.abort('blockedbyclient');
            stages.push('REPEATED_HOSTED_GET_REFUSED');
            repeated.resolve();
            return;
          }
          if(method==='GET'&&url===paidEndpoint&&merchantWitness){
            checkpoint='PAID_READ';
            assert.equal(++paidReads,1);
            assert.equal(generation,merchantWitness.generation);
            assert.equal(frame.url(),returnUrl);
            assert.equal(request.frame(),frame);
            assert.equal(request.isNavigationRequest(),false);
            assert.equal(request.postDataBuffer(),null);
            assert.equal(request.serviceWorker(),null);
            assert.equal(request.redirectedFrom(),null);
            const headers=await request.allHeaders();
            assert.equal(headers.origin,merchant);
            assert.equal(headers.authorization,bearer);
            assert.ok(!Object.hasOwn(headers,'cookie'));
            await route.fulfill({status:200,headers:{...cors,'content-type':'application/json'},json:paid});
            return;
          }
          await unexpected(route,'UNEXPECTED_ROUTE');
        } catch {
          await unexpected(route,checkpoint);
        }
      });
      const flow=createBillingPortalFlow({configuration,scope,policyReceipts,policyAck},{
        async verifyInitial(){
          return {baseline:{graph,cashVerified:true,journalVerified:true,signedCallbacksVerified:true},origin,
            provider:{providerVerified:true,initialCashVerified:true,lineCount:1,allocationCount:1,chargeCount:1}};
        },
        async recordOpeningIntent(intent:unknown){
          assert.deepEqual(intent,{portalKeyDigest:digest(idempotencyKey),returnUrl});
          return true;
        },
        async readOnlyPortalSql(request:SqlCall){
          assert.deepEqual(request,{mode:'read only',query:exactSql,args:expectedSql});
          sqlCalls.push(request);
          if(sqlCalls.length===1)await sqlToken.promise;
          else {
            assert.equal(sqlCalls.length,2);
            assert.equal(request,sqlCalls[0]);
            assert.equal(paidReads,1);
            assert.equal(returnReleased,1);
          }
          return [{portal_verified:true}];
        },
        async openPortal(port:OpeningPort){
          openingPort=port;
          assert.equal(port.returnUrl,returnUrl);
          await page.goto(returnUrl,{timeout:4000});
          assert.ok(await page.evaluate(()=>document.documentElement.getAttribute('data-owner')==='checkout-generation'));
          boundGeneration=generation;
          await page.getByRole('button',{name:'Open customer portal',exact:true}).focus({timeout:4000});
          await page.keyboard.press('Enter');
          await through(arming.promise);
          await through(responseVerified.promise);
          assert.ok(held&&originalSizes);
          assert.equal(continued,0);
          assert.equal(held.request().existingResponse(),null);
          assert.equal(frame.url(),returnUrl);
          assert.ok(exactPair()&&checks>=7);
          assert.ok(stages.indexOf('ORIGINAL_POST_BEFORE_201')<stages.indexOf('ORIGINAL_201_PAUSED'));
          assert.ok(stages.indexOf('ORIGINAL_201_BYTES_VALIDATED_BEFORE_RELEASE')<stages.indexOf('HOSTED_GET_HELD_FOR_SQL'));
          assert.equal(sqlCalls.length,1);
          const beforeSqlRelease=checks;
          if(mode==='stale-checkout'){
            await page.goto(returnUrl,{timeout:4000});
            assert.equal(frame.url(),returnUrl);
            assert.ok(generation>boundGeneration);
          }
          sqlToken.resolve();
          await through(done.promise);
          assert.ok(checks>beforeSqlRelease);
          if(mode==='stale-checkout'){
            assert.equal(continued,0);
            assert.ok(stages.includes('STALE_CHECKOUT_REFUSED'));
            throw hidden('STALE_CHECKOUT_REFUSED');
          }
          assert.equal(continued,1);
          assert.ok(stages.indexOf('HOSTED_GET_HELD_FOR_SQL')<stages.indexOf('EXACT_ORIGINAL_ROUTE_FULFILLED'));
          await page.waitForFunction(()=>document.title==='Synthetic Portal',{},{timeout:4000});
          assert.equal(generation,boundGeneration+1);
          verifyCommit(held.request(),hostedRouteEvidence,hostedUrl);
          hostedWitness={tab:page,document:Object.freeze({}),generation};
          return {...hostedWitness,opening:await through(openingProof.promise),origin:hostedOrigin,providerMutationCount:providerMutations};
        },
        async observeMerchantReturn(opened:Witness,active:Readonly<BillingLimits>){
          checkpoint='TRUSTED_HOSTED_ACTION';
          assert.ok(hostedWitness);
          assert.equal(opened.tab,hostedWitness.tab);
          assert.equal(opened.document,hostedWitness.document);
          assert.equal(generation,hostedWitness.generation);
          if(mode==='repeat-hosted'){
            const repeat=page.goto(hostedUrl,{timeout:4000}).catch(()=>null);
            await through(repeated.promise);
            await repeat;
            throw hidden('OPENING_ONLY');
          }
          const link=page.getByRole('link',{name:'Return to merchant',exact:true});
          await link.focus({timeout:4000});
          const limits=Object.freeze({deadline:active.deadline,signal:active.signal});
          billingCheck(limits);
          assert.equal(returnLimits,undefined);
          returnLimits=limits;
          const grantReturn=()=>{
            billingCheck(limits);
            returnToken.resolve(true);
          };
          returnActionArmed=true;
          const press=page.keyboard.press('Enter');
          void press.catch(()=>{});
          if(mode==='wrong-return'){
            await through(returnRefused.promise);
            await press;
            throw hidden('WRONG_RETURN_REFUSED');
          }
          await through(returnHeld.promise);
          assert.ok(returnRoute);
          assert.equal(returnRoute.request().existingResponse(),null);
          assert.equal(frame.url(),hostedUrl);
          assert.equal(returnReleased,0);
          assert.equal(paidReads,0);
          if(mode==='aborted-return'||mode==='aborted-grant'){
            if(mode==='aborted-grant')grantReturn();
            ownerAbort.abort();
            await waitForReturnDisposition();
            await press;
            throw hidden('RETURN_OWNER_ABORTED');
          }
          if(mode==='replacement'){
            const replacement=await page.goto(hostedUrl,{timeout:4000});
            assert.ok(replacement&&held&&replacement.request()!==held.request()&&replacement.status()===200);
            assert.ok(generation>hostedWitness.generation);
            assert.equal(frame.url(),hostedUrl);
          }
          grantReturn();
          if(mode==='replacement'){
            await through(returnRefused.promise);
            await press;
            throw hidden('REPLACED_HOSTED_DOCUMENT_REFUSED');
          }
          const returned=await through(returnCompleted.promise);
          await press;
          await page.waitForFunction(()=>document.title==='Synthetic Merchant Return',{},{timeout:4000});
          assert.equal(generation,hostedWitness.generation+1);
          verifyCommit(returned,returnedRouteEvidence,returnUrl);
          merchantWitness={tab:page,document:Object.freeze({}),generation};
          return {...merchantWitness,sourceDocument:hostedWitness.document,initiator:'PROVIDER_DOCUMENT',method:'GET',
            url:returnUrl,providerMutationCount:providerMutations};
        },
        async readReturnedSubscription(witness:Witness){
          assert.ok(merchantWitness);
          assert.equal(witness.tab,merchantWitness.tab);
          assert.equal(witness.document,merchantWitness.document);
          const {status,body}=await page.evaluate(async({endpoint,token})=>{
            const response=await fetch(endpoint,{method:'GET',credentials:'omit',headers:{authorization:token}});
            return {status:response.status,body:await response.json()};
          },{endpoint:paidEndpoint,token:bearer});
          return {...body,...witness,status,method:'GET'};
        },
      },{deadline:Date.now()+18000,signal:ownerAbort.signal});
      const result=await through(flow.run());
      if(mode==='aborted-return'||mode==='aborted-grant'){
        assert.ok(stages.includes('RETURN_HELD'));
        assert.equal(ownerAbort.signal.aborted,true);
        settleReturnDuringCleanup();
        await waitForReturnDisposition();
      }
      if(mode==='exact'){
        assert.deepEqual(result,{status:'PASSED',code:'OK',portalOpened:true,returnObserved:true,portalAccepted:true});
        assert.equal(returnReleased,1);
        assert.equal(paidReads,1);
        assert.equal(sqlCalls.length,2);
        assert.equal(sqlCalls[0],sqlCalls[1]);
        assert.ok(stages.includes('RETURN_HELD'));
      }else{
        const opened=mode!=='stale-checkout';
        assert.deepEqual(result,{status:'STOPPED',code:opened?'PORTAL_RETURN':'PORTAL_OPEN',portalOpened:opened,returnObserved:false,portalAccepted:false});
        assert.equal(returnReleased,0);
        assert.equal(paidReads,0);
        assert.equal(sqlCalls.length,1);
        if(mode==='wrong-return'){
          assert.ok(stages.includes('RETURN_REFUSED')&&stages.includes('RETURN_ADDRESS_MISMATCH'));
          assert.ok(!stages.includes('RETURN_HELD'));
        }
        if(mode==='replacement'){
          assert.ok(stages.includes('RETURN_HELD')&&stages.includes('STALE_RETURN_REFUSED')&&stages.includes('RETURN_SOURCE_DOCUMENT_REPLACED'));
        }
        if(mode==='repeat-hosted'){
          assert.ok(stages.includes('REPEATED_HOSTED_GET_REFUSED'));
          assert.equal(continued,1);
        }
        if(mode==='stale-checkout'){
          assert.ok(stages.includes('STALE_CHECKOUT_REFUSED'));
          assert.equal(continued,0);
        }
        if(mode==='aborted-return')assert.ok(stages.includes('RETURN_RELEASE_DENIED'));
        if(mode==='aborted-grant')assert.ok(stages.includes('RETURN_OWNER_INACTIVE'));
      }
      assert.equal(providerMutations,0);
      if(mode!=='repeat-hosted'&&mode!=='stale-checkout')assert.ok(stages.includes('ANCHOR_NAVIGATION'));
      return {originalSizes,stages};
    }catch{
      throw new Error('Chromium fixture stopped with fixed checkpoint: '+checkpoint+
        ' ('+diagnostic+'; '+stages.join(',')+'; checks='+checks+'; returns='+returnReleased+'; paid reads='+paidReads+'; SQL calls='+sqlCalls.length+')');
    }finally{
      ownerAbort.abort();
      sqlToken.resolve();
      settleReturnDuringCleanup();
      if(timeout)clearTimeout(timeout);
    }
  } finally {
    await browser.close().catch(()=>{});
  }
}

test('Chromium holds the original Portal GET until the mock SQL token and refuses a repeat',{timeout:25000},async()=>{
  const result=await chromiumComposition('repeat-hosted');
  assert.ok(result.originalSizes&&result.originalSizes.read>0);
  console.log('Measured synthetic HTTP 201 bytes '+JSON.stringify(result.originalSizes));
});
test('Chromium original hosted GET is refused when a fresh same-URL document commits during the SQL hold',{timeout:25000},async()=>{
  await chromiumComposition('stale-checkout');
});
test('Chromium binds the trusted hosted link to the exact held merchant return and unchanged SQL bindings',{timeout:25000},async()=>{
  const result=await chromiumComposition('exact');
  assert.ok(result.originalSizes?.read);
});
test('Chromium refuses a trusted hosted link to a different merchant URL',{timeout:25000},async()=>{await chromiumComposition('wrong-return');});
test('Chromium refuses the held return after a replacement hosted document commits at the same URL',{timeout:25000},async()=>{await chromiumComposition('replacement');});
test('Chromium abort cleanup refuses a held merchant return without a grant',{timeout:25000},async()=>{
  await chromiumComposition('aborted-return');
});
test('Chromium revokes an issued return grant when its owner aborts before release',{timeout:25000},async()=>{
  await chromiumComposition('aborted-grant');
});
