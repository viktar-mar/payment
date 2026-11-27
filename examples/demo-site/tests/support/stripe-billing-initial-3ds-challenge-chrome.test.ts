import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium,type Frame,type Request,type Response,type Route} from '@playwright/test';
// @ts-ignore Native Node executes the same challenge source as Playwright.
import {initial3dsChallenge} from './stripe-billing-initial-3ds.ts';
// @ts-ignore Native Node executes the guarded response source.
import {guardChallengeRedirects} from './stripe-billing-initial-3ds-redirect.ts';

const rejectedAssets=new Map([
 ['direct reviewed issuer stylesheet rejected',{
  path:'/fixture-private-stylesheet.scss',kind:'stylesheet',checkpoint:'BROWSER_CHALLENGE_ISSUER_ASSET_STYLESHEET_SUFFIX',
  contentType:'text/css',body:':root { color: black; }',
 }],
 ['direct reviewed issuer script rejected',{
  path:'/fixture-private-script.js',kind:'script',checkpoint:'BROWSER_GUARD_ROUTE_ISSUER_SCRIPT_PARSER',
  contentType:'application/javascript',
  body:"document.querySelector('form').append(document.querySelector('template').content.cloneNode(true))",
 }],
]);
const stylesheetBlock='direct reviewed issuer passive stylesheet blocked';
const twoFonts='direct reviewed issuer two passive fonts blocked';
const sixFonts='direct reviewed issuer six passive fonts blocked';
const parserScript='one parser-initiated script from the exact issuer document';
const decisionScript='one parser-initiated script from the issuer decision document';
const secondDecisionPost='a second document POST from the issuer decision';
const decisionCres='a CRes document POST to the Stripe hooks origin';
const isolatedDecisionCres='a CRes document POST between isolated issuer and hooks targets';
const lateDecisionCres='a CRes document POST with late issuer response observation';
const changedCresAction='a CRes form whose action changed after the issuer response';
const changedCresBody='a CRes form whose session data changed after the issuer response';
const refusedFollowups=new Set([secondDecisionPost,changedCresAction,changedCresBody]);
const wrongScriptMime='issuer script with the wrong MIME type is refused';
for(const mode of ['normal observation','late Playwright response','separate issuer process','late issuer response','direct Stripe EMVCo frame','direct reviewed issuer origin','direct reviewed issuer passive image blocked','direct reviewed issuer passive font blocked',twoFonts,sixFonts,stylesheetBlock,parserScript,decisionScript,decisionCres,isolatedDecisionCres,lateDecisionCres,wrongScriptMime,...refusedFollowups,...rejectedAssets.keys()])
test(rejectedAssets.has(mode)?mode+' before handoff':refusedFollowups.has(mode)?mode+' is blocked after the original 2xx':mode===wrongScriptMime?mode:
 'an issuer iframe completes the exact challenge with '+mode, {timeout:35_000},async()=>{
 const demo='http://127.0.0.1:18081',backend='http://127.0.0.1:18080',stripe='https://js.stripe.com';
 const secret='pi_fixture_secret_fixture',key='pk_test_fixture',source='fingerprint_fixture-v2',api='https://api.stripe.com';
 const retrieve=api+'/v1/payment_intents/pi_fixture?client_secret='+secret+'&key='+key;
 const authenticate=api+'/v1/3ds2/authenticate',complete=api+'/v1/3ds2/challenge_complete';
 const outer=stripe+'/v3/three-ds-2-challenge-fixture.html',inner=stripe+'/v3/fixture-inner.html';
 const blocksPassiveImage=mode==='direct reviewed issuer passive image blocked';
 const blocksPassiveFont=mode==='direct reviewed issuer passive font blocked'||mode===twoFonts||mode===sixFonts;
 const blocksPassiveStylesheet=mode===stylesheetBlock;
 const blocksPassiveAsset=blocksPassiveImage||blocksPassiveFont||blocksPassiveStylesheet;
 const badMime=mode===wrongScriptMime,loadsScript=mode===parserScript||badMime;
 const loadsDecisionScript=mode===decisionScript;
 const submitsCres=[decisionCres,isolatedDecisionCres,lateDecisionCres,changedCresAction,changedCresBody].includes(mode);
 const isolated=mode===isolatedDecisionCres||mode==='separate issuer process';
 const submitsSecondDecision=mode===secondDecisionPost||submitsCres;
 const rejectedAsset=rejectedAssets.get(mode);
 const scopedIssuer=mode==='direct reviewed issuer origin'||blocksPassiveAsset||loadsScript||loadsDecisionScript||submitsSecondDecision||Boolean(rejectedAsset);
 const direct=mode==='direct Stripe EMVCo frame'||scopedIssuer;
 const issuerName=direct?'stripe-challenge-frame':'acsFrame';
 const approvedIssuerOrigin=scopedIssuer?'https://reviewed-test-issuer.stripe.com':undefined;
 const issuerOrigin=approvedIssuerOrigin??'https://hooks.stripe.com',issuer=issuerOrigin+'/fixture-issuer',decision=issuer+'/decision';
 const assetPath=issuerOrigin+(rejectedAsset?.path??(loadsScript?'/fixture-required.js':blocksPassiveStylesheet?'/fixture-passive.css':
  blocksPassiveFont?'/fixture-passive.woff2':'/fixture-passive.png'));
 const decisionAsset=issuerOrigin+'/fixture-decision.js';
 const followup=(submitsCres?'https://hooks.stripe.com':issuerOrigin)+'/fixture-followup';
 const observedFollowup=mode===changedCresAction?followup+'-different':followup;
 const fontCount=mode===sixFonts?6:mode===twoFonts?2:1;
 const assetUrls=[assetPath,...Array.from({length:fontCount-1},(_,index)=>issuerOrigin+`/fixture-passive-${index+2}.woff2`)];
 const server='11111111-2222-4333-8444-555555555555',acs='11111111-2222-4333-8444-555555555554';
 const creq=Buffer.from(JSON.stringify({messageType:'CReq',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs})).toString('base64url');
 const cres=Buffer.from(JSON.stringify({messageType:'CRes',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs,transStatus:'Y'})).toString('base64url');
 const followupFields=submitsCres?`<input type="hidden" name="cres" value="${cres}"><input type="hidden" name="threeDSSessionData" value="fixture&amp;session">`:
  '<input name="result" value="fixture">';
 const followupScript=[
  mode===changedCresAction?'document.forms[0].action='+JSON.stringify(observedFollowup):'',
  mode===changedCresBody?'document.forms[0].elements.namedItem("threeDSSessionData").value="different"':'',
  'document.forms[0].submit()',
 ].filter(Boolean).join(';');
 const script=`(async()=>{
  await (await fetch(${JSON.stringify(retrieve)})).json();
  await (await fetch(${JSON.stringify(authenticate)},{method:'POST',body:new URLSearchParams({source:${JSON.stringify(source)},key:${JSON.stringify(key)}})})).json();
  const frame=document.createElement('iframe');frame.name=${JSON.stringify(issuerName)};document.body.append(frame);
  window.addEventListener('message',async event=>{
   if(event.source!==frame.contentWindow || event.origin!==${JSON.stringify(submitsCres?new URL(followup).origin:issuerOrigin)} || event.data!=='fixture decision')return;
   ${loadsDecisionScript?'frame.remove();':''}
   try{
    const reply=await fetch(${JSON.stringify(complete)},{method:'POST',body:new URLSearchParams({source:${JSON.stringify(source)},key:${JSON.stringify(key)}})});
    if(!(await reply.json()).complete)throw Error();
    const final=await (await fetch(${JSON.stringify(retrieve)})).json();
    document.body.dataset.fixtureResult=final.status;
   }catch{document.body.dataset.fixtureResult='refused';}
  });
  const form=document.createElement('form');form.method='post';form.target=frame.name;form.action=${JSON.stringify(issuer)};
  const field=document.createElement('input');field.type='hidden';field.name='creq';field.value=${JSON.stringify(creq)};
  form.append(field);document.body.append(form);form.submit();
 })()`;
 const json=(value:object)=>({contentType:'application/json',headers:{'Access-Control-Allow-Origin':stripe},body:JSON.stringify(value)});
 const issuerButton='<button name="decision" value="complete">Complete</button>';
 const revealButton="document.querySelector('form').append(document.querySelector('template').content.cloneNode(true))";
 const issuerDecision='parent.postMessage("fixture decision",'+JSON.stringify(stripe)+')';
 const issuerBody='<!doctype html><form method="post" action="'+decision+'">'+
  (blocksPassiveAsset||rejectedAsset||loadsScript?'<template>'+issuerButton+'</template>':issuerButton)+'</form>'+
  (blocksPassiveImage?`<img width="1" height="1" alt="" src="${assetPath}" onerror="${revealButton}">`:
   blocksPassiveFont?`<style>${assetUrls.map((url,index)=>`@font-face{font-family:FixturePassive${index};src:url("${url}") format("woff2")}`).join('')}</style>
     <script>Promise.allSettled([${assetUrls.map((_,index)=>`document.fonts.load('16px FixturePassive${index}','X')`).join(',')}])
       .then(results=>{if(results.every(result=>result.status==='rejected')){${revealButton}}})</script>`:
   blocksPassiveStylesheet?`<link rel="stylesheet" href="${assetPath}" onerror="${revealButton}">`:
   rejectedAsset?.kind==='stylesheet'?`<link rel="stylesheet" href="${assetPath}" onload="${revealButton}">`:
   rejectedAsset?.kind==='script'?`<script>const s=document.createElement('script');s.src="${assetPath}";document.head.append(s)</script>`:
   loadsScript?`<script src="${assetPath}"></script>`:'');
 const responses=new Map<string,NonNullable<Parameters<Route['fulfill']>[0]>>([
  [demo+'/',{contentType:'text/html',body:'<!doctype html><link rel="icon" href="data:,"><iframe name="__privateStripeFrame7" src="'+outer+'"></iframe>'}],
  [outer,{contentType:'text/html',body:direct?'<!doctype html><body><script>'+script+'</script></body>':
   '<!doctype html><iframe name="stripe-challenge-frame" src="'+inner+'"></iframe>'}],
  [inner,{contentType:'text/html',body:'<!doctype html><body><script>'+script+'</script></body>'}],
  [retrieve,json({object:'payment_intent',
   livemode:false,id:'pi_fixture',client_secret:secret,status:'requires_action',
   next_action:{type:'use_stripe_sdk',use_stripe_sdk:{type:'stripe_3ds2_fingerprint',three_d_secure_2_source:source}}})],
  [authenticate,json({state:'challenge_required',
   ares:{messageType:'ARes',transStatus:'C',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs,acsURL:issuer}})],
  [issuer,{contentType:'text/html',body:issuerBody}],
  [decision,{contentType:'text/html',body:submitsSecondDecision?
   `<!doctype html><form method="post" action="${followup}">${followupFields}</form><script>${followupScript}</script>`:
   loadsDecisionScript?`<!doctype html><script src="${decisionAsset}"></script>`:
   '<!doctype html><script>'+issuerDecision+'</script>'}],
  [complete,json({complete:true})],
 ]);
 if(rejectedAsset)responses.set(assetPath,{contentType:rejectedAsset.contentType,body:rejectedAsset.body});
 if(loadsScript)responses.set(assetPath,{contentType:badMime?'text/html':'application/javascript',body:revealButton});
 if(loadsDecisionScript)responses.set(decisionAsset,{contentType:'application/javascript',body:issuerDecision});
 if(submitsSecondDecision)responses.set(observedFollowup,{contentType:'text/html',body:submitsCres?
  '<!doctype html><script>'+issuerDecision+'</script>':'unexpected forwarded form'});
 const channel=process.env.PAYMENT_TEST_CHROME_CHANNEL==='chrome'?'chrome':'chromium';
 const browser=await chromium.launch({channel,headless:true,timeout:10_000,args:[
  '--site-per-process','--disable-background-networking','--proxy-server=direct://','--host-resolver-rules=MAP * ~NOTFOUND',
  ...(isolated?['--isolate-origins='+[stripe,issuerOrigin,...(submitsCres?[new URL(followup).origin]:[])].join(',')]:[]),
 ]});
 const stop=new AbortController();
 let attendance:Promise<boolean>|undefined;
 try{
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
  const page=await context.newPage(),forwarded:string[]=[],refusals:string[]=[];
  const passiveRequests:Request[]=[],passiveFailures:Request[]=[],passiveResponses:Response[]=[];
  const passiveAborts:(string|undefined)[]=[],authorized:string[]=[];
  page.on('requestfailed',request=>{if(assetUrls.includes(request.url()))passiveFailures.push(request);});
  page.on('response',response=>{if(assetUrls.includes(response.url()))passiveResponses.push(response);});
  const limits={deadline:Date.now()+15_000,signal:stop.signal};
  let assetErrorRedacted=false;
  let issuerFrame:Frame|undefined,attachedIssuerFrame=false,separateIssuerTarget=false,gestures=0;
  const guard=await guardChallengeRedirects(page,reason=>refusals.push(reason),{demo,backend},undefined,approvedIssuerOrigin);
  const challenge=initial3dsChallenge({page,limits,demo,owner:()=>({key,secret}),winning:async()=>{},check:async()=>{},
   expectIssuerAssetBlock:request=>guard.expectIssuerAssetBlock(request),
   authorizeIssuerScript:(request,document)=>guard.authorizeIssuerScript(request,document),
   authorizeIssuerDecision:async request=>{
    authorized.push(request.url());
    assert.ok(guard.authorizeIssuerDecision);
    await guard.authorizeIssuerDecision(request);
   },
   takeIssuerDecisionBody:request=>guard.takeIssuerDecisionBody(request),
   authorizeIssuerResult:async(request,document)=>{
    authorized.push(request.url());
    await guard.authorizeIssuerResult(request,document);
   },
   approvedIssuerOrigin,authorizeResponse:async request=>{
    authorized.push(request.url());
    assert.ok(!assetUrls.includes(request.url()));
    await guard.authorize(request);
   },waitForGesture:async()=>{
    if(blocksPassiveAsset){
     assert.equal(passiveRequests.length,assetUrls.length);
     assert.deepEqual(new Set(passiveFailures),new Set(passiveRequests));
     assert.deepEqual(passiveAborts,assetUrls.map(()=>'blockedbyclient'));
    }
    gestures++;
    if(isolated&&issuerFrame){
     const session=await context.newCDPSession(issuerFrame);
     try{
      const {targetInfo}=await session.send('Target.getTargetInfo');
      separateIssuerTarget=targetInfo.type==='iframe'&&targetInfo.url===issuer;
     }finally{await session.detach();}
    }
   },
   stripeFrames:request=>{
    const target=request.frame(),parent=target.parentFrame();
    if(direct&&target.url()===outer&&parent===page.mainFrame()){
     return {target,frames:new Map([[target,outer],[parent,demo+'/']])};
    }
    if(target.url()===inner&&parent?.url()===outer&&parent.parentFrame()===page.mainFrame()){
     return {target,frames:new Map([[target,inner],[parent,outer]])};
    }
   },
  });
  await context.route('**/*',async route=>{
   const request=route.request(),url=request.url(),response=responses.get(url);
   const isPassiveAsset=blocksPassiveAsset&&assetUrls.includes(url);
   const isRejectedAsset=rejectedAsset&&url===assetPath;
   if(!response&&!isPassiveAsset){await route.abort('blockedbyclient');return;}
   guard.registerRoute(request);
   let checkpoint='route refused';
   try{
    if(challenge.owns(new URL(url))){
     if(mode==='late Playwright response'&&(url===retrieve||url===authenticate))request.existingResponse=()=>null;
     if((mode==='late issuer response'||mode===lateDecisionCres)&&url===decision)request.existingResponse=()=>null;
     const actual=Object.create(route) as Route;
     actual.continue=async()=>{
      assert.ok(response,'passive asset must never reach continuation');
      const reply=url===retrieve&&forwarded.includes(complete)?json({status:'succeeded'}):response;
      forwarded.push(url);
      await route.fulfill(reply);
     };
     if(isPassiveAsset||isRejectedAsset){
      passiveRequests.push(request);
     }
     if(isPassiveAsset){
      actual.abort=async errorCode=>{passiveAborts.push(errorCode);await route.abort(errorCode);};
      actual.fallback=async()=>assert.fail('passive asset must never reach fallback');
      actual.fulfill=async()=>assert.fail('passive asset must never receive a synthetic response');
     }
     if(url===issuer){
      issuerFrame=request.frame();
      attachedIssuerFrame=issuerFrame.name()===issuerName&&issuerFrame.url()==='about:blank';
     }
     await challenge.forward(actual,new URL(url),code=>{checkpoint=code;});
    }else{
     assert.ok(response);
     await guard.authorize(request);await route.fulfill(response);
    }
   }catch(error){
    refusals.push(checkpoint);
    if(isRejectedAsset)assetErrorRedacted=error instanceof Error&&String(error)==='Error: billing_BROWSER'&&JSON.stringify(error)==='{}';
    await route.abort('blockedbyclient').catch(()=>{});
   }
  });
  if(rejectedAsset){
   attendance=challenge.attend().then(()=>true,()=>false);
   const [blocked]=await Promise.all([
    page.waitForEvent('requestfailed',{predicate:request=>request.url()===assetPath,timeout:8_000}),
    page.goto(demo+'/',{waitUntil:'domcontentloaded',timeout:7_000}),
   ]);
   assert.equal(attachedIssuerFrame,true);
   assert.equal(refusals[0],rejectedAsset.checkpoint);
   // The owner retains the first failure. The route and Chromium can both report the abort.
   assert.ok(refusals.slice(1).every(code=>code==='BROWSER_GUARD_RESPONSE_NETWORK' ||
    rejectedAsset.kind==='script' && code==='BROWSER_CHALLENGE_ISSUER_ASSET_SCRIPT'),JSON.stringify(refusals));
   assert.equal(assetErrorRedacted,true);
   assert.equal(passiveRequests.length,1);
   assert.equal(passiveRequests[0]===blocked,true);
   assert.equal(blocked.resourceType(),rejectedAsset.kind);
   assert.equal(blocked.method(),'GET');
   assert.equal(blocked.frame()===issuerFrame,true);
   assert.equal(blocked.isNavigationRequest(),false);
   assert.equal(blocked.serviceWorker(),null);
   assert.match(blocked.failure()?.errorText??'',/^net::ERR_BLOCKED_BY_CLIENT(?:\.Inspector)?$/);
   assert.equal(blocked.existingResponse(),null);
   assert.equal(await blocked.frame().locator('form button[name=decision]').count(),0);
   assert.equal(passiveResponses.length,0);
   assert.deepEqual(forwarded,[retrieve,authenticate,issuer]);
   assert.deepEqual(authorized,[retrieve,authenticate,issuer]);
   assert.equal(gestures,0);
   return;
  }
  if(badMime){
   attendance=challenge.attend().then(()=>true,()=>false);
   const [request]=await Promise.all([
    page.waitForEvent('requestfailed',{predicate:value=>value.url()===assetPath,timeout:8_000}),
    page.goto(demo+'/',{waitUntil:'domcontentloaded',timeout:7_000}),
   ]);
   assert.equal(refusals[0],'BROWSER_GUARD_RESPONSE_ISSUER_SCRIPT');
   assert.equal(request.existingResponse(),null);
   assert.equal(await request.frame().locator('form button').count(),0);
   assert.deepEqual(forwarded,[retrieve,authenticate,issuer,assetPath]);
   assert.equal(gestures,0);
   return;
  }
  await page.goto(demo+'/',{waitUntil:'domcontentloaded',timeout:7_000});
  if(refusedFollowups.has(mode)){
   const [request]=await Promise.all([
    page.waitForEvent('requestfailed',{predicate:value=>value.url()===observedFollowup,timeout:8_000}),
    challenge.attend(),
   ]);
   assert.equal(request.frame(),issuerFrame);
   assert.equal(request.method(),'POST');
   assert.equal(request.isNavigationRequest(),true);
   assert.equal(request.existingResponse(),null);
   assert.equal(refusals[0],submitsCres?'BROWSER_CHALLENGE_ISSUER_SUBMISSION_CRES':'BROWSER_CHALLENGE_ISSUER_SUBMISSION_FOLLOWUP');
   assert.deepEqual(forwarded,[retrieve,authenticate,issuer,decision]);
   assert.deepEqual(authorized,forwarded);
   assert.equal(gestures,1);
   return;
  }
  try{
   const stripeFrame=page.frameLocator('iframe');
   const result=(direct?stripeFrame:stripeFrame.frameLocator('iframe')).locator('body[data-fixture-result]');
   await Promise.all([challenge.attend(),result.waitFor({state:'attached',timeout:8_000})]);
   assert.equal(await result.getAttribute('data-fixture-result'),'succeeded');
  }catch{assert.fail(JSON.stringify({attachedIssuerFrame,separateIssuerTarget,refusals,gestures,forwarded:forwarded.length,
   passiveAborts:passiveAborts.length,passiveFailures:passiveFailures.length}));}
  assert.equal(attachedIssuerFrame,true);
  if(isolated)assert.equal(separateIssuerTarget,true);
  if(mode===isolatedDecisionCres){
   assert.ok(issuerFrame);
   const target=await context.newCDPSession(issuerFrame);
   try{
    const {targetInfo}=await target.send('Target.getTargetInfo');
    assert.equal(targetInfo.type,'iframe');
    assert.equal(targetInfo.url,followup);
   }finally{await target.detach();}
  }
  assert.deepEqual(refusals,[]);
  assert.deepEqual(forwarded,[retrieve,authenticate,issuer,...(loadsScript?[assetPath]:[]),decision,
   ...(loadsDecisionScript?[decisionAsset]:[]),...(submitsCres?[followup]:[]),complete,retrieve]);
  if(submitsCres)assert.deepEqual(authorized,forwarded);
  assert.equal(gestures,1);
  if(blocksPassiveAsset){
   assert.deepEqual(passiveRequests.map(asset=>asset.url()).sort(),[...assetUrls].sort());
   assert.equal(passiveFailures.length,assetUrls.length);
   for(const asset of passiveRequests){
    assert.equal(asset.method(),'GET');
    assert.equal(asset.postDataBuffer(),null);
    assert.equal(asset.resourceType(),blocksPassiveFont?'font':blocksPassiveStylesheet?'stylesheet':'image');
    assert.equal(asset.isNavigationRequest(),false);
    assert.equal(asset.frame(),issuerFrame);
    assert.equal(asset.serviceWorker(),null);
    assert.match(asset.failure()?.errorText??'',/^net::ERR_BLOCKED_BY_CLIENT(?:\.Inspector)?$/);
    assert.equal(asset.existingResponse(),null);
   }
   assert.deepEqual(passiveResponses,[]);
   assert.deepEqual(authorized,[retrieve,authenticate,issuer,decision,complete,retrieve]);
  }
 }finally{
  stop.abort();
  await browser.close();
  if(blocksPassiveAsset||rejectedAsset||badMime){
   assert.equal(browser.isConnected(),false);
   if(attendance)assert.equal(await attendance,false);
  }
 }
});
