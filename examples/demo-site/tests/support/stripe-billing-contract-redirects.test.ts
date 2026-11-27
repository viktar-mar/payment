import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {chromium} from '@playwright/test';
// @ts-ignore Offline source.
import {billingContractBrowserArgs,guardBillingContractRedirects} from './stripe-billing-contract-redirects.ts';

const issuer='https://approved-issuer.example';
const stripe='https://hooks.stripe.com';
const demo='http://127.0.0.1:18081';
const api='https://api.stripe.com';

type ResponseEvent={
 requestId:string;
 request:{url:string;method:string};
 responseStatusCode?:number;
 responseErrorReason?:string;
 responseHeaders?:{name:string;value:string}[];
};

async function guard({signal,failResume=false}:{signal?:AbortSignal;failResume?:boolean}={}){
 let respond:(event:ResponseEvent)=>Promise<void>=()=>assert.fail('response handler not armed');
 const commands:{method:string;params:unknown}[]=[],failures:string[]=[];
 const events=new EventEmitter();
 const session={
  on(event:string,listener:(event:ResponseEvent)=>Promise<void>){
   if(event==='Fetch.requestPaused')respond=listener;
   else events.on(event,listener);
  },
  async send(method:string,params:unknown){
   commands.push({method,params});
   if(failResume&&method==='Fetch.continueResponse')throw Error('fixture failure');
  },
 };
 const page={evaluate:async()=>true,context:()=>({newCDPSession:async()=>session})};
 await guardBillingContractRedirects(page as never,code=>failures.push(code),signal);
 return {respond:(event:ResponseEvent)=>respond(event),commands,failures};
}

const response=(url:string,method:string,status:number,location?:string):ResponseEvent=>({
 requestId:'response',request:{url,method},responseStatusCode:status,
 ...(location===undefined?{}:{responseHeaders:[{name:'Location',value:location}]}),
});

test('arms responses before auto-attaching Chromium child targets and needs no request-pair metadata',async()=>{
 const {respond,commands,failures}=await guard();
 assert.deepEqual(commands.map(c=>c.method),['Fetch.enable','Target.setAutoAttach']);
 assert.deepEqual(commands[0]?.params,{patterns:[
  {urlPattern:'http://*',requestStage:'Response'},{urlPattern:'https://*',requestStage:'Response'},
 ]});
 for(const status of [200,204,304,400,500])await respond(response(issuer+'/action','POST',status));
 await respond({requestId:'err',request:{url:issuer+'/static',method:'GET'},responseErrorReason:'NameNotResolved'});
 assert.deepEqual(failures,[]);
 assert.equal(commands.filter(c=>c.method==='Fetch.continueResponse').length,6);
});

for(const [name,request] of [
 ['same-origin POST preserved',response(issuer+'/action','POST',307,'/result')],
 ['same-origin GET',response(stripe+'/action','GET',308,stripe+'/done')],
 ['same-origin POST converted to GET',response(issuer+'/action','POST',303,'/done')],
] as const)test('blocks even an approved '+name,async()=>{
 const {respond,commands,failures}=await guard();
 await respond(request);
 assert.deepEqual(failures,['BROWSER_GUARD_RESPONSE_REDIRECT']);
 assert.equal(commands.at(-1)?.method,'Fetch.failRequest');
});

const repeated=response(issuer+'/action','GET',302,stripe+'/done');
repeated.responseHeaders?.push({name:'location',value:stripe+'/again'});
const refresh=response(issuer+'/action','GET',200);
refresh.responseHeaders=[{name:'ReFrEsH',value:'0; url='+issuer+'/done'}];
const cookie=response(issuer+'/action','GET',302,'/done');
cookie.responseHeaders?.push({name:'seT-CookIE',value:'fixture=1'});
for(const [name,request] of [
 ['unknown destination',response(issuer+'/action','GET',302,'https://unrelated.example/')],
 ['alternate 3xx with a Location',response(issuer+'/action','GET',300,'https://unrelated.example/')],
 ['Stripe lookalike',response(issuer+'/action','GET',302,'https://hooks.stripe.com.unrelated.example/')],
 ['unknown source',response('https://unrelated.example/action','GET',302,stripe+'/done')],
 ['local source',response(demo+'/action','GET',302,stripe+'/done')],
 ['local destination',response(issuer+'/action','GET',302,demo+'/done')],
 ['API source',response(api+'/action','GET',302,stripe+'/done')],
 ['API destination',response(issuer+'/action','GET',302,api+'/done')],
 ['HTTP downgrade',response(issuer+'/action','GET',302,'http://hooks.stripe.com/done')],
 ['foreign port',response(issuer+'/action','GET',302,stripe+':444/done')],
 ['userinfo',response(issuer+'/action','GET',302,'https://name@hooks.stripe.com/done')],
 ['opaque URL',response(issuer+'/action','GET',302,'data:text/html,hello')],
 ['missing Location',response(issuer+'/action','GET',302)],
 ['duplicate Location',repeated],
 ['Refresh response',refresh],
 ['redirect adding a cookie',cookie],
 ['cross-origin GET to an approved origin',response(issuer+'/action','GET',302,stripe+'/done')],
 ['cross-origin POST discards body',response(issuer+'/action','POST',303,stripe+'/done')],
 ['cross-origin request preserves POST',response(issuer+'/action','POST',307,stripe+'/done')],
] as const)test('blocks '+name+' before continuation',async()=>{
 const {respond,commands,failures}=await guard();
 await respond(request);
 assert.deepEqual(failures,['BROWSER_GUARD_RESPONSE_REDIRECT']);
 assert.deepEqual(commands.at(-1),{method:'Fetch.failRequest',params:{requestId:'response',errorReason:'BlockedByClient'}});
});

test('an expired guard never resumes and a resume error uses a fixed diagnostic',async()=>{
 const controller=new AbortController();
 const expired=await guard({signal:controller.signal});
 controller.abort();
 await expired.respond(response(issuer+'/action','GET',200));
 assert.equal(expired.commands.at(-1)?.method,'Fetch.failRequest');
 assert.deepEqual(expired.failures,[]);
 const failed=await guard({failResume:true});
 await failed.respond(response(issuer+'/action','GET',200));
 assert.equal(failed.commands.at(-1)?.method,'Fetch.failRequest');
 assert.deepEqual(failed.failures,['BROWSER_GUARD_RESPONSE_RESUME']);
});

function launchFixture(){
 return chromium.launch({
  channel:process.env.PAYMENT_TEST_CHROME_CHANNEL==='chrome'?'chrome':'chromium',headless:true,timeout:10_000,args:[
   '--site-per-process','--disable-background-networking','--proxy-server=direct://','--host-resolver-rules=MAP * ~NOTFOUND',
   '--isolate-origins='+issuer,...billingContractBrowserArgs,
  ],
 });
}

test('refuses to arm a Chromium session if SharedWorker remains enabled',async()=>{
 let attached=false;
 const page={
  evaluate:async()=>false,
  context:()=>({newCDPSession:async()=>{
   attached=true;
   return {on(){},async send(){}};
  }}),
 };
 const failures:string[]=[];
 await assert.rejects(guardBillingContractRedirects(page as never,code=>failures.push(code)),/^Error: billing_BROWSER$/);
 assert.deepEqual(failures,['BROWSER_GUARD_IFRAME_SETUP']);
 assert.equal(attached,false);
});

test('Chrome blocks a same-origin second hop after a cookie was set', {timeout:20_000},async()=>{
 const browser=await launchFixture();
 try{
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
  const page=await context.newPage(),failures:string[]=[];
  const challenge=issuer+'/challenge',read=issuer+'/data',hop=issuer+'/xhr',tripwire=issuer+'/second';
  await context.addCookies([{name:'fixture',value:'present',domain:new URL(issuer).hostname,path:'/second',secure:true,sameSite:'None'}]);
  assert.equal((await context.cookies(tripwire)).length,1);
  let followed=0;
  page.on('request',request=>{if(request.url()===tripwire)followed++;});
  await guardBillingContractRedirects(page,code=>failures.push(code));
  await context.route('**/*',async route=>{
   switch(route.request().url()){
    case demo+'/':
     await route.fulfill({contentType:'text/html',body:'<!doctype html><link rel="icon" href="data:,"><iframe src="'+challenge+'"></iframe>'});
     return;
    case challenge:
     await route.fulfill({contentType:'text/html',body:`<!doctype html><body><script>
      fetch("/data").then(response=>response.text()).then(async text=>{
       document.body.textContent=text;
       try{await fetch("/xhr");document.body.textContent+=" followed";}
       catch{document.body.textContent+=" blocked";}
      });
     </script>`});
     return;
    case read:
     await route.fulfill({status:409,contentType:'text/plain',body:'ordinary response'});
     return;
    case hop:
     await route.fulfill({status:302,headers:{location:tripwire}});
     return;
    default:
     await route.abort('blockedbyclient');
   }
  });
  await page.goto(demo+'/',{timeout:6_000});
  await page.frameLocator('iframe').getByText('ordinary response blocked',{exact:true}).waitFor({timeout:5_000});
  const child=page.frames().find(frame=>frame.url()===challenge);
  assert.ok(child);
  const session=await context.newCDPSession(child);
  try{assert.equal((await session.send('Target.getTargetInfo')).targetInfo.type,'iframe');}
  finally{await session.detach();}
  assert.deepEqual(failures,['BROWSER_GUARD_RESPONSE_REDIRECT']);
  assert.equal(followed,0);
 }finally{await browser.close();}
});

test('Chrome disables SharedWorker in the isolated provider iframe',{timeout:20_000},async()=>{
 const browser=await launchFixture();
 try{
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
  const page=await context.newPage(),failures:string[]=[];
  const challenge=issuer+'/challenge',tripwire=issuer+'/worker.js';
  let fetched=0;
  context.on('request',request=>{if(request.url()===tripwire)fetched++;});
  await guardBillingContractRedirects(page,code=>failures.push(code));
  await context.route('**/*',async route=>{
   switch(route.request().url()){
    case demo+'/':
     await route.fulfill({contentType:'text/html',body:'<!doctype html><link rel="icon" href="data:,"><iframe src="'+challenge+'"></iframe>'});
     return;
    case challenge:
     await route.fulfill({contentType:'text/html',body:`<!doctype html><body><script>
      try{new SharedWorker("/worker.js");document.body.textContent="shared worker exposed";}
      catch(error){document.body.textContent=error instanceof ReferenceError?"shared worker unavailable":"shared worker failed";}
     </script>`});
     return;
    default:
     await route.abort('blockedbyclient');
   }
  });
  await page.goto(demo+'/',{timeout:6_000});
  const status=page.frameLocator('iframe').getByText(/shared worker (unavailable|exposed|failed)/);
  await status.waitFor({timeout:5_000});
  assert.equal(await status.textContent(),'shared worker unavailable');
  assert.equal(fetched,0);
  assert.deepEqual(failures,[]);
 }finally{await browser.close();}
});

test('Chrome blocks redirects from a dedicated worker in a cross-process iframe',{timeout:20_000},async()=>{
 const browser=await launchFixture();
 try{
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
  const page=await context.newPage(),failures:string[]=[];
  const challenge=issuer+'/challenge',worker=issuer+'/worker.js',hop=issuer+'/hop',tripwire=issuer+'/second';
  let initial=0,followed=0;
  context.on('request',request=>{
   if(request.url()===hop)initial++;
   if(request.url()===tripwire)followed++;
  });
  await guardBillingContractRedirects(page,code=>failures.push(code));
  await context.route('**/*',async route=>{
   switch(route.request().url()){
    case demo+'/':
     await route.fulfill({contentType:'text/html',body:'<!doctype html><link rel="icon" href="data:,"><iframe src="'+challenge+'"></iframe>'});
     return;
    case challenge:
     await route.fulfill({contentType:'text/html',body:`<!doctype html><body><script>
      new Worker("/worker.js").onmessage=event=>{document.body.textContent=event.data;};
     </script>`});
     return;
    case worker:
     await route.fulfill({contentType:'application/javascript',body:'fetch("/hop").then(()=>self.postMessage("worker followed")).catch(()=>self.postMessage("worker blocked"));'});
     return;
    case hop:
     await route.fulfill({status:302,headers:{location:tripwire}});
     return;
    default:
     await route.abort('blockedbyclient');
   }
  });
  await page.goto(demo+'/',{timeout:6_000});
  await page.frameLocator('iframe').getByText('worker blocked',{exact:true}).waitFor({timeout:5_000});
  assert.equal(initial,1);
  assert.equal(followed,0);
  assert.deepEqual(failures,['BROWSER_GUARD_RESPONSE_REDIRECT']);
 }finally{await browser.close();}
});
