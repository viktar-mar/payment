import assert from 'node:assert/strict';
import test from 'node:test';
import {chromium,type Frame,type Route} from '@playwright/test';
// @ts-ignore Native Node executes the same challenge source as Playwright.
import {initial3dsChallenge} from './stripe-billing-initial-3ds.ts';
// @ts-ignore Native Node executes the guarded response source.
import {guardChallengeRedirects} from './stripe-billing-initial-3ds-redirect.ts';

for(const mode of ['normal observation','late Playwright response','separate issuer process','late issuer response'])
test('an issuer iframe completes the exact challenge with '+mode, {timeout:35_000},async()=>{
 const demo='http://127.0.0.1:18081',backend='http://127.0.0.1:18080',stripe='https://js.stripe.com';
 const secret='pi_fixture_secret_fixture',key='pk_test_fixture',source='src_fixture',api='https://api.stripe.com';
 const retrieve=api+'/v1/payment_intents/pi_fixture?client_secret='+secret+'&key='+key;
 const authenticate=api+'/v1/3ds2/authenticate',complete=api+'/v1/3ds2/challenge_complete';
 const outer=stripe+'/v3/three-ds-2-challenge-fixture.html',inner=stripe+'/v3/fixture-inner.html';
 const issuerOrigin='https://hooks.stripe.com',issuer=issuerOrigin+'/fixture-issuer',decision=issuer+'/decision';
 const server='11111111-2222-4333-8444-555555555555',acs='11111111-2222-4333-8444-555555555554';
 const creq=Buffer.from(JSON.stringify({messageType:'CReq',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs})).toString('base64url');
 const script=`(async()=>{
  await (await fetch(${JSON.stringify(retrieve)})).json();
  await (await fetch(${JSON.stringify(authenticate)},{method:'POST',body:new URLSearchParams({source:${JSON.stringify(source)},key:${JSON.stringify(key)}})})).json();
  const frame=document.createElement('iframe');frame.name='acsFrame';document.body.append(frame);
  window.addEventListener('message',async event=>{
   if(event.source!==frame.contentWindow || event.origin!==${JSON.stringify(issuerOrigin)} || event.data!=='fixture decision')return;
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
 const responses=new Map<string,NonNullable<Parameters<Route['fulfill']>[0]>>([
  [demo+'/',{contentType:'text/html',body:'<!doctype html><link rel="icon" href="data:,"><iframe name="__privateStripeFrame7" src="'+outer+'"></iframe>'}],
  [outer,{contentType:'text/html',body:'<!doctype html><iframe name="stripe-challenge-frame" src="'+inner+'"></iframe>'}],
  [inner,{contentType:'text/html',body:'<!doctype html><body><script>'+script+'</script></body>'}],
  [retrieve,json({object:'payment_intent',
   livemode:false,id:'pi_fixture',client_secret:secret,status:'requires_action',
   next_action:{type:'use_stripe_sdk',use_stripe_sdk:{type:'stripe_3ds2_fingerprint',three_d_secure_2_source:source}}})],
  [authenticate,json({state:'challenge_required',
   ares:{messageType:'ARes',transStatus:'C',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs,acsURL:issuer}})],
  [issuer,{contentType:'text/html',body:'<!doctype html><form method="post" action="'+decision+'"><button name="decision" value="complete">Complete</button></form>'}],
  [decision,{contentType:'text/html',body:'<!doctype html><script>parent.postMessage("fixture decision",'+JSON.stringify(stripe)+')</script>'}],
  [complete,json({complete:true})],
 ]);
 const channel=process.env.PAYMENT_TEST_CHROME_CHANNEL==='chrome'?'chrome':'chromium';
 const browser=await chromium.launch({channel,headless:true,timeout:10_000,args:[
  '--site-per-process','--disable-background-networking','--proxy-server=direct://','--host-resolver-rules=MAP * ~NOTFOUND',
  ...(mode==='separate issuer process'?['--isolate-origins='+stripe+','+new URL(issuer).origin]:[]),
 ]});
 try{
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
  const page=await context.newPage(),forwarded:string[]=[],refusals:string[]=[];
  const limits={deadline:Date.now()+15_000};
  let issuerFrame:Frame|undefined,attachedIssuerFrame=false,separateIssuerTarget=false,gestures=0;
  const guard=await guardChallengeRedirects(page,reason=>refusals.push(reason),{demo,backend});
  const challenge=initial3dsChallenge({page,limits,demo,owner:()=>({key,secret}),winning:async()=>{},check:async()=>{},
   authorizeResponse:request=>guard.authorize(request),waitForGesture:async()=>{
    gestures++;
    if(mode==='separate issuer process'&&issuerFrame){
     const session=await context.newCDPSession(issuerFrame);
     try{
      const {targetInfo}=await session.send('Target.getTargetInfo');
      separateIssuerTarget=targetInfo.type==='iframe'&&targetInfo.url===issuer;
     }finally{await session.detach();}
    }
   },
   stripeFrames:request=>{
    const target=request.frame(),parent=target.parentFrame();
    if(target.url()===inner&&parent?.url()===outer&&parent.parentFrame()===page.mainFrame()){
     return {target,frames:new Map([[target,inner],[parent,outer]])};
    }
   },
  });
  await context.route('**/*',async route=>{
   const request=route.request(),url=request.url(),response=responses.get(url);
   if(!response){await route.abort('blockedbyclient');return;}
   guard.registerRoute(request);
   let checkpoint='route refused';
   try{
    if(challenge.owns(new URL(url))){
     if(mode==='late Playwright response'&&(url===retrieve||url===authenticate))request.existingResponse=()=>null;
     if(mode==='late issuer response'&&url===decision)request.existingResponse=()=>null;
     const actual=Object.create(route) as Route;
     actual.continue=async()=>{
      const reply=url===retrieve&&forwarded.includes(complete)?json({status:'succeeded'}):response;
      forwarded.push(url);
      await route.fulfill(reply);
     };
     if(url===issuer){
      issuerFrame=request.frame();
      attachedIssuerFrame=issuerFrame.name()==='acsFrame'&&issuerFrame.url()==='about:blank';
     }
     await challenge.forward(actual,new URL(url),code=>{checkpoint=code;});
    }else{
     await guard.authorize(request);await route.fulfill(response);
    }
   }catch{refusals.push(checkpoint);await route.abort('blockedbyclient');}
  });
  await page.goto(demo+'/',{waitUntil:'domcontentloaded',timeout:7_000});
  try{
   const result=page.frameLocator('iframe').frameLocator('iframe').locator('body[data-fixture-result]');
   await Promise.all([challenge.attend(),result.waitFor({state:'attached',timeout:8_000})]);
   assert.equal(await result.getAttribute('data-fixture-result'),'succeeded');
  }catch{assert.fail(JSON.stringify({attachedIssuerFrame,separateIssuerTarget,refusals,gestures,forwarded:forwarded.length}));}
  assert.equal(attachedIssuerFrame,true);
  if(mode==='separate issuer process')assert.equal(separateIssuerTarget,true);
  assert.deepEqual(refusals,[]);
  assert.deepEqual(forwarded,[retrieve,authenticate,issuer,decision,complete,retrieve]);
  assert.equal(gestures,1);
 }finally{await browser.close();}
});
