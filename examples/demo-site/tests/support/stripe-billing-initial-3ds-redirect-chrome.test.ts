import test from 'node:test';
import assert from 'node:assert/strict';
import {chromium,type CDPSession} from '@playwright/test';
// @ts-ignore Native Node executes the guard source.
import {guardChallengeRedirects} from './stripe-billing-initial-3ds-redirect.ts';

for(const mode of ['first request','nested siblings','unnamed iframe','parallel same frame','late CDP event','redirect']){
 test('Chrome guards '+mode+' in cross-process iframes', {timeout:25_000},async()=>{
 const demo='http://127.0.0.1:18081',backend='http://127.0.0.1:18080';
 const stripe='https://js.stripe.com',nested='https://nested.js.stripe.com';
 const frameUrl=stripe+'/fixture-frame.html',nestedUrl=nested+'/fixture-inner.html';
 const apiUrl='https://api.stripe.com/fixture-read',tripwire='https://api.stripe.com/fixture-redirected';
 const channel=process.env.PAYMENT_TEST_CHROME_CHANNEL==='chrome'?'chrome':'chromium';
 const browser=await chromium.launch({channel,headless:true,timeout:10_000,args:[
  '--site-per-process','--disable-background-networking','--proxy-server=direct://','--host-resolver-rules=MAP * ~NOTFOUND',
  '--isolate-origins='+stripe+','+nested,
 ]});
 try{
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
  const page=await context.newPage(),authorized:string[]=[],refused:string[]=[];
  const bothRequests=Promise.withResolvers<void>();
  let apiRequests=0;
  let followed=0;
  page.on('request',request=>{if(request.url()===tripwire)followed++;});
  const delayedEvent=Promise.withResolvers<()=>void>();
  // Delay the guard's copy of a real Chromium event. Playwright still receives
  // the same event on its own session and can deliver the intercepted route.
  const guardContext=Object.create(context) as typeof context;
  guardContext.newCDPSession=async()=>{
   const session=await context.newCDPSession(page);
   const copy=Object.create(session) as CDPSession;
   copy.on=((name:string,listener:(event:unknown)=>void)=>{
    if(name==='Target.receivedMessageFromTarget')return session.on(name,event=>{
     const message=JSON.parse(event.message);
     if(message.method==='Network.requestWillBeSent'&&message.params.request.url===apiUrl){
      delayedEvent.resolve(()=>listener(event));
     }else listener(event);
    });
    return session.on(name as 'close',listener);
   }) as CDPSession['on'];
   return copy;
  };
  const guardPage=Object.create(page) as typeof page;
  guardPage.context=()=>guardContext;
  const guard=await guardChallengeRedirects(mode==='late CDP event'?guardPage:page,code=>refused.push(code),{demo,backend});
  const fetchExpression='fetch('+JSON.stringify(apiUrl)+',{cache:"no-store"}).then(r=>r.text())';
  const fetches=mode==='parallel same frame'?'Promise.all(['+fetchExpression+','+fetchExpression+']).then(body=>body.join(" "))':fetchExpression;
  const script='<!doctype html><body><script>'+fetches+'.then(t=>document.body.textContent=t).catch(()=>document.body.textContent="blocked")</script>';
  const document='<!doctype html><link rel="icon" href="data:,">';
  const fixture=new Map([
   [demo+'/',{contentType:'text/html',body:document+'<iframe '+(mode==='unnamed iframe'?'':'name="outer" ')+'src="'+frameUrl+'"></iframe>'}],
   [frameUrl,{contentType:'text/html',body:mode==='nested siblings'?document+
    '<iframe name="one" src="'+nestedUrl+'"></iframe><iframe name="two" src="'+nestedUrl+'"></iframe>':script}],
   [nestedUrl,{contentType:'text/html',body:script}],
   [apiUrl,{contentType:'text/plain',status:mode==='redirect'?307:200,
    headers:{'Access-Control-Allow-Origin':mode==='nested siblings'?nested:stripe,
     ...(mode==='redirect'?{Location:tripwire}:{})},body:'synthetic response'}],
  ]);
  await context.route('**/*',async route=>{
   const request=route.request(),reply=fixture.get(request.url());
   guard.registerRoute(request);
   if(!reply){await route.abort('blockedbyclient');return;}
   if(mode==='parallel same frame'&&request.url()===apiUrl){
    if(++apiRequests===2)bothRequests.resolve();
    await bothRequests.promise;
   }
   try{
    const authorization=guard.authorize(request);
    if(mode==='late CDP event'&&request.url()===apiUrl){
     let settled=false;
     void authorization.then(()=>{settled=true;},()=>{settled=true;});
     const deliver=await delayedEvent.promise;
     await new Promise<void>(resolve=>setImmediate(resolve));
     assert.equal(settled,false,'the exact route stays paused without the passive event');
     deliver();
    }
    await authorization;
   }
   catch{refused.push('authorize');await route.abort('blockedbyclient');return;}
   authorized.push(request.url());
   await route.fulfill(reply);
  });
  await page.goto(demo+'/',{timeout:7_000});
  const outer=page.frameLocator('iframe');
  if(mode==='nested siblings'){
   for(const name of ['one','two']){
    await outer.frameLocator('iframe[name="'+name+'"]').locator('body').filter({hasText:'synthetic response'})
     .waitFor({state:'attached',timeout:5_000});
   }
   assert.deepEqual(authorized.sort(),[demo+'/',frameUrl,nestedUrl,nestedUrl,apiUrl,apiUrl].sort());
  }else{
   await outer.locator('body').filter({hasText:mode==='redirect'?'blocked':
    mode==='parallel same frame'?'synthetic response synthetic response':'synthetic response'})
    .waitFor({state:'attached',timeout:5_000});
   assert.deepEqual(authorized,mode==='parallel same frame'?[demo+'/',frameUrl,apiUrl,apiUrl]:[demo+'/',frameUrl,apiUrl]);
  }
  assert.deepEqual(refused,mode==='redirect'?['BROWSER_GUARD_RESPONSE_REDIRECT']:[]);
  assert.equal(followed,0);
  const frames=page.frames().filter(child=>child.url()===frameUrl||child.url()===nestedUrl);
  assert.equal(frames.length,mode==='nested siblings'?3:1);
  const ids=new Set<string>();
  for(const frame of frames){
   const probe=await context.newCDPSession(frame);
   try{
    const {targetInfo}=await probe.send('Target.getTargetInfo');
    assert.equal(targetInfo.type,'iframe');ids.add(targetInfo.targetId);
   }finally{await probe.detach();}
  }
  assert.equal(ids.size,frames.length);
 }finally{await browser.close();}
 });
}
