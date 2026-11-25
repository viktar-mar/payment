import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
// @ts-ignore Offline source.
import {guardChallengeRedirects} from './stripe-billing-initial-3ds-redirect.ts';
// @ts-ignore Offline protocol fixture registration.
import {registerChromiumProtocolFixture} from './stripe-billing-chromium-route.ts';
// @ts-ignore Offline tests use the same terminal decoder as the launcher.
import {challengeTerminalLine} from './stripe-billing-initial-3ds-terminal.ts';

const origins={backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:18081'};
const protocolGuard=(page:object,onFailure:Parameters<typeof guardChallengeRedirects>[1],signal?:AbortSignal)=>{
 registerChromiumProtocolFixture(page);
 return guardChallengeRedirects(page as never,onFailure,origins,signal);
};
test('an unsupported driver cannot fall back to protocol fixture matching',async()=>{
 await assert.rejects(guardChallengeRedirects({} as never,()=>{},origins),/^Error: billing_BROWSER$/);
 assert.throws(()=>registerChromiumProtocolFixture({_connection:{}}),/^Error: billing_BROWSER$/);
});

for(const mode of ['exact request','late event','late event retired','ended while waiting','same-URL decoy',
 'replaced document','wrong frame','wrong URL','wrong method','duplicate claim','unverified OPTIONS',
 'expected issuer block','expected issuer block with 200','expected issuer block with wrong error','expected issuer block with decoy',
 'expected issuer font block','expected issuer font block with 200','expected issuer font block with wrong error',
 'expected issuer font block with decoy','expected issuer font block with wrong suffix'])
 test('Chromium route evidence rejects a false match: '+mode,async()=>{
 const font=mode.startsWith('expected issuer font block');
 const block=font||mode.startsWith('expected issuer block');
 const wrongSuffix=mode.endsWith('wrong suffix');
 const approved=block?'https://reviewed-test-issuer.stripe.com':undefined;
 const url=approved?approved+(font&&!wrongSuffix?'/fixture.woff2':'/fixture.png'):'https://api.stripe.com/fixture-read',location=origins.demo+'/';
 const method=mode==='unverified OPTIONS'?'OPTIONS':'GET';
 const frameId='checkout',networkId='actual-request',decoyId='decoy-request';
 const browserSession={},symbol=Symbol('InterceptableRequest');
 const main={_id:frameId,_page:undefined as unknown,_currentDocument:{},_isDetached:()=>false,parentFrame:()=>null};
 const pending=new Map();
 const impl={
  frameManager:{frame:(id:string)=>id===frameId?main:undefined},
  delegate:{_targetId:frameId,_sessions:new Map([[frameId,{_targetId:frameId,_client:browserSession}]]),
   _networkManager:{_requestIdToRequest:pending}},
 };
 main._page=impl;
 const connection={toImpl:(client:{driver:unknown})=>client.driver};
 const original={frame:()=>main,method:()=>method,url:()=>url};
 const intercepted={request:original,session:browserSession,_requestId:networkId,_interceptionId:'paused-request',_originalRequestRoute:{}};
 Object.defineProperty(original,symbol,{value:intercepted});
 pending.set(networkId,intercepted);
 const frame={_connection:connection,driver:main,url:()=>location,name:()=>'',isDetached:()=>false};
 const events=new Map<string,(value:unknown)=>void|Promise<void>>(),commands:string[]=[],failures:string[]=[];
 const cdp={on:(event:string,listener:(value:unknown)=>void)=>events.set(event,listener),send:async(method:string)=>{
  commands.push(method);
  if(method==='Page.getFrameTree')return {frameTree:{frame:{id:frameId,url:location}}};
 }};
 const page={_connection:connection,driver:impl,mainFrame:()=>frame,context:()=>({newCDPSession:async()=>cdp})};
 const request={_connection:connection,driver:original,frame:()=>frame,url:()=>url,method:()=>method,
  redirectedFrom:()=>null,serviceWorker:()=>null,isNavigationRequest:()=>false,resourceType:()=>font?'font':block?'image':'xhr'} as never;
 const guard=await guardChallengeRedirects(page as never,code=>failures.push(code),origins,undefined,approved);
 guard.registerRoute(request);
 const passive={frameId,request:{url,method:'GET'}};
 const record=(id:string,original=passive)=>events.get('Network.requestWillBeSent')!({...original,requestId:id});
 const respond=(id:string)=>events.get('Fetch.requestPaused')!({...passive,networkId:id,requestId:id,responseStatusCode:200});
 record(decoyId);
 if(!['same-URL decoy','late event','late event retired','ended while waiting'].includes(mode)){
  record(networkId,{frameId:mode==='wrong frame'?'foreign-frame':frameId,
   request:{url:mode==='wrong URL'?url+'/different':url,method:mode==='wrong method'?'POST':method}});
 }
 if(block){
  if(wrongSuffix){
   await assert.rejects(guard.expectIssuerAssetBlock(request),/^Error: billing_BROWSER$/);
   await respond(networkId);
   assert.equal(commands.includes('Fetch.continueResponse'),false);
   assert.equal(commands.at(-1),'Fetch.failRequest');
   assert.deepEqual(failures,['BROWSER_GUARD_ROUTE_IDENTITY','BROWSER_GUARD_RESPONSE_UNCLAIMED']);
   return;
  }
  await guard.expectIssuerAssetBlock(request);
  const exactFailure=mode==='expected issuer block'||mode==='expected issuer font block',http=mode.endsWith('with 200');
  const id=mode.endsWith('with decoy')?decoyId:networkId;
  await events.get('Fetch.requestPaused')!({...passive,networkId:id,requestId:id,
   ...(http?{responseStatusCode:200}:{responseErrorReason:mode.endsWith('wrong error')?'Failed':'BlockedByClient'})});
  assert.equal(commands.includes('Fetch.continueResponse'),false);
  assert.equal(commands.at(-1),'Fetch.failRequest');
  assert.deepEqual(failures,exactFailure?[]:[http?'BROWSER_GUARD_RESPONSE_UNCLAIMED':'BROWSER_GUARD_RESPONSE_NETWORK']);
  return;
 }
 if(mode==='replaced document')main._currentDocument={};
 if(mode==='duplicate claim')await guard.authorize(request);
 const authorization=guard.authorize(request);
 void authorization.catch(()=>{});
 if(['late event','late event retired','ended while waiting'].includes(mode)){
  await new Promise<void>(resolve=>setImmediate(resolve));
  if(mode!=='ended while waiting')record(networkId);
  if(mode!=='late event')events.get('Network.loadingFailed')!({requestId:networkId});
 }
 const accepted=mode==='exact request'||mode==='late event';
 if(accepted){
  await authorization;
  await respond(networkId);
 }else await assert.rejects(authorization,/^Error: billing_BROWSER$/);
 await respond(decoyId);
 assert.equal(commands.filter(method=>method==='Fetch.continueResponse').length,accepted?1:0);
 assert.equal(commands.at(-1),'Fetch.failRequest');
 const faults:Record<string,string>={
  'replaced document':'BROWSER_GUARD_ROUTE_IDENTITY',
  'wrong frame':'BROWSER_GUARD_ROUTE_EVENT_FRAME','wrong URL':'BROWSER_GUARD_ROUTE_EVENT_URL',
  'wrong method':'BROWSER_GUARD_ROUTE_EVENT_METHOD','duplicate claim':'BROWSER_GUARD_ROUTE_EVENT_CLAIMED',
  'unverified OPTIONS':'BROWSER_GUARD_ROUTE_EVENT_PREFLIGHT','same-URL decoy':'BROWSER_GUARD_ROUTE_EVENT_MISSING',
  'late event retired':'BROWSER_GUARD_ROUTE_EVENT_RETIRED','ended while waiting':'BROWSER_GUARD_ROUTE_EVENT_RETIRED',
 };
 assert.deepEqual(failures,[
  ...(accepted?[]:[faults[mode]]),
  'BROWSER_GUARD_RESPONSE_UNCLAIMED',
 ]);
});

for(const during of ['route policy','identity probe'])test('a replacement iframe cannot inherit a request during '+during,async()=>{
 const url='https://api.stripe.com/fixture-read',iframeUrl='https://js.stripe.com/fixture-frame.html';
 const main={url:()=>origins.demo+'/',name:()=>'',isDetached:()=>false};
 const child={url:()=>iframeUrl,name:()=>'fixture',isDetached:()=>false,parentFrame:()=>main};
 const events=new EventEmitter(),commands:string[]=[],failures:string[]=[];
 const identity=Promise.withResolvers<object>(),probing=Promise.withResolvers<void>();
 const session={
  on:events.on.bind(events),
  async send(method:string,args?:{sessionId:string;message:string}){
   if(method==='Page.getFrameTree')return {frameTree:{frame:{id:'main',url:main.url()}}};
   if(method!=='Target.sendMessageToTarget')return {};
   const {id,method:command}=JSON.parse(args!.message);
   commands.push(command);
   const result=command==='Page.getFrameTree'?{frameTree:{frame:{id:'iframe',url:iframeUrl,name:'fixture'}}}:{};
   events.emit('Target.receivedMessageFromTarget',{sessionId:args!.sessionId,message:JSON.stringify({id,result})});
   return {};
  },
 };
 const page={mainFrame:()=>main,context:()=>({newCDPSession:async(frame:unknown)=>frame===child?{
  send:async()=>{probing.resolve();return identity.promise;},detach:async()=>{},
 }:session})};
 const guard=await protocolGuard(page,code=>failures.push(code));
 const flush=()=>new Promise<void>(resolve=>setImmediate(resolve));
 const passive={frameId:'iframe',request:{url,method:'GET'}};
 const emit=(sessionId:string,method:string,params:object)=>events.emit('Target.receivedMessageFromTarget',{
  sessionId,message:JSON.stringify({method,params}),
 });
 const attach=async(sessionId:string)=>{
  events.emit('Target.attachedToTarget',{sessionId,targetInfo:{targetId:'iframe',type:'iframe'},waitingForDebugger:true});
  await flush();
  emit(sessionId,'Network.requestWillBeSent',{...passive,requestId:sessionId});
 };
 await attach('first');
 const request={frame:()=>child,url:()=>url,method:()=>'GET'} as never;
 guard.registerRoute(request);
 const authorization=during==='identity probe'?guard.authorize(request):undefined;
 if(authorization)await probing.promise;
 events.emit('Target.detachedFromTarget',{sessionId:'first'});
 await attach('replacement');
 identity.resolve({targetInfo:{targetId:'iframe',type:'iframe'}});
 await assert.rejects(authorization??guard.authorize(request),/^Error: billing_BROWSER$/);
 emit('replacement','Fetch.requestPaused',{...passive,networkId:'replacement',requestId:'pause',responseStatusCode:200});
 await flush();
 assert.equal(commands.includes('Fetch.continueResponse'),false);
 assert.equal(commands.at(-1),'Fetch.failRequest');
 assert.deepEqual(failures,['BROWSER_GUARD_RESPONSE_UNCLAIMED']);
});

for(const [frameId,status,release] of [['checkout-frame',200,true],['wrong-frame',200,false],['checkout-frame',307,false]] as const)
 test('the paused main navigation uses the attached frame identity: '+frameId+' '+status,async()=>{
  const href=origins.demo+'/',events=new Map<string,(e:unknown)=>Promise<void>|void>(),commands:string[]=[];
  let paused=false;
  const frame={url:()=>'about:blank',name:()=>'',parentFrame:()=>null,isDetached:()=>false};
  const session={on:(event:string,listener:(e:unknown)=>void)=>events.set(event,listener),send:async(command:string)=>{
   commands.push(command);
   if(command==='Page.getFrameTree'){
    assert.equal(paused,false,'the renderer cannot answer while its document request is paused');
    return {frameTree:{frame:{id:'checkout-frame',url:'about:blank'}}};
   }
  }};
  const page={context:()=>({newCDPSession:async()=>session}),frames:()=>[frame],mainFrame:()=>frame,on:()=>{}};
  const guard=await protocolGuard(page,()=>{});
  paused=true;
  events.get('Network.requestWillBeSent')!({requestId:'nav',frameId,request:{url:href,method:'GET'}});
  const authorize=()=>guard.authorize({frame:()=>frame,method:()=>'GET',url:()=>href} as never);
  if(frameId==='wrong-frame')await assert.rejects(authorize(),/^Error: billing_BROWSER$/);
  else await authorize();
  await events.get('Fetch.requestPaused')!({requestId:'paused',networkId:'nav',frameId,
   request:{url:href,method:'GET'},responseStatusCode:status});
  assert.equal(commands.filter(command=>command==='Page.getFrameTree').length,1);
  assert.equal(commands.includes('Fetch.continueResponse'),release);
  assert.equal(commands.includes('Fetch.failRequest'),!release);
 });

for(const mode of ['first navigation','ID owner','unnamed sibling','committed blank','different document','ambiguous blank',
 'foreign owner','disconnected owner','replaced owner','renamed owner','foreign request','not navigation','redirect'])
 test('a new Stripe iframe still requires one exact CDP frame and request: '+mode,async()=>{
  const href='https://js.stripe.com/v3/fixture-frame.html';
  const events=new Map<string,(e:unknown)=>void|Promise<void>>(),commands:string[]=[],refusals:string[]=[];
  const main={url:()=>origins.demo+'/',name:()=>'',parentFrame:()=>null,isDetached:()=>false};
  const ownerName='fixture-stripe-frame';let label=ownerName;
  const child={url:()=>mode==='different document'?'https://js.stripe.com/v3/other.html':mode==='committed blank'?'about:blank':'',
   name:()=>mode==='committed blank'?ownerName:'',parentFrame:()=>main,isDetached:()=>false,
   frameElement:async()=>({evaluate:async(read:(element:unknown)=>unknown)=>read({
    localName:'iframe',isConnected:mode!=='disconnected owner',id:mode==='ID owner'?ownerName:'',
    getAttribute:()=>mode==='ID owner'?null:mode==='foreign owner'?'foreign-frame':label,
   }),contentFrame:async():Promise<unknown>=>mode==='replaced owner'?null:child,dispose:async()=>{}})};
  const node={id:'child',url:mode==='committed blank'?'about:blank':'',name:ownerName};
  const session={on:(name:string,listener:(e:unknown)=>void)=>events.set(name,listener),send:async(name:string)=>{
   commands.push(name);
   if(name==='Page.getFrameTree'){
    if(mode==='renamed owner'&&commands.filter(command=>command==='Page.getFrameTree').length>1)label='renamed';
    return {frameTree:{frame:{id:'main',url:main.url()},
     childFrames:[{frame:node},...(mode==='ambiguous blank'||mode==='unnamed sibling'?
      [{frame:{...node,id:'other',name:mode==='ambiguous blank'?ownerName:undefined}}]:[])]}};
   }
  }};
  const page={context:()=>({newCDPSession:async(surface:unknown)=>{
   if(surface===child)throw Error("This frame does not have a separate CDP session, it is a part of the parent frame's session");
   return session;
  }}),frames:()=>[main,child],mainFrame:()=>main,on:()=>{}};
  const guard=await protocolGuard(page,reason=>refusals.push(reason));
  const frameId=mode==='foreign request'?'other':'child';
  events.get('Network.requestWillBeSent')!({requestId:'iframe-navigation',frameId,request:{url:href,method:'GET'}});
  const authorize=()=>guard.authorize({frame:()=>child,url:()=>href,method:()=>'GET',redirectedFrom:()=>null,
   isNavigationRequest:()=>mode!=='not navigation'} as never);
  const denied=['different document','ambiguous blank','foreign owner','disconnected owner','replaced owner','renamed owner',
   'foreign request','not navigation'].includes(mode);
  if(denied)await assert.rejects(authorize(),/^Error: billing_BROWSER$/);
  else await authorize();
  await events.get('Fetch.requestPaused')!({requestId:'response',networkId:'iframe-navigation',frameId,
   request:{url:href,method:'GET'},responseStatusCode:mode==='redirect'?307:200});
  assert.equal(commands.includes('Fetch.continueResponse'),!denied&&mode!=='redirect');
  assert.deepEqual(refusals,denied?['BROWSER_GUARD_RESPONSE_UNCLAIMED']:mode==='redirect'?['BROWSER_GUARD_RESPONSE_REDIRECT']:[]);
 });

for(const mode of ['eligible','redirect','not modified','missing request','foreign frame','unclaimed response','error response',
 'network failure','absent response network ID','unknown response network ID','missing response frame','foreign response frame',
 'foreign response URL','foreign response method']){
 test('Chromium response interception retains original bytes and stops '+mode,async()=>{
  const href='https://api.stripe.com/v1/3ds2/authenticate',location='https://js.stripe.com/v3/frame';
  const commands:string[]=[],events=new Map<string,(e:unknown)=>Promise<void>|void>(),refusals:string[]=[];
  const frame={url:()=>location,name:()=>'',parentFrame:()=>null,isDetached:()=>false};
  const session={on:(event:string,listener:(e:unknown)=>Promise<void>)=>events.set(event,listener),send:async(command:string,args:unknown)=>{
   commands.push(command);
   if(command==='Page.getFrameTree')return {frameTree:{frame:{id:'frame',url:location}}};
   if(command==='Fetch.continueResponse')assert.deepEqual(args,{requestId:'paused'});
  }};
  const page={context:()=>({newCDPSession:async()=>session}),frames:()=>[frame],mainFrame:()=>frame,on:()=>{}};
  const guard=await protocolGuard(page,reason=>refusals.push(reason));
  if(mode!=='missing request')events.get('Network.requestWillBeSent')!({requestId:'network',frameId:mode==='foreign frame'?'wrong':'frame',request:{url:href,method:'POST'}});
  const request={url:()=>href,method:()=>'POST',frame:()=>frame};
  if(['missing request','foreign frame'].includes(mode)){
   await assert.rejects(guard.authorize(request as never));assert.equal(commands.includes('Fetch.continueResponse'),false);return;
  }
  if(mode!=='unclaimed response')await guard.authorize(request as never);
  await events.get('Fetch.requestPaused')!({requestId:'paused',
   networkId:mode==='absent response network ID'?undefined:mode==='unknown response network ID'?'other-network':'network',
   frameId:mode==='missing response frame'?undefined:mode==='foreign response frame'?'other-frame':'frame',
   request:{url:mode==='foreign response URL'?href+'x':href,method:mode==='foreign response method'?'GET':'POST'},
   responseStatusCode:mode==='redirect'?307:mode==='not modified'?304:mode==='error response'?500:200,
   responseErrorReason:mode==='network failure'?'private failure canary':undefined});
  assert.equal(commands.includes('Fetch.continueResponse'),mode==='eligible');
  assert.equal(commands.includes('Fetch.failRequest'),mode!=='eligible');
  const expected:Record<string,string>={
   redirect:'REDIRECT','error response':'STATUS','not modified':'STATUS','network failure':'NETWORK',
   'unclaimed response':'UNCLAIMED','absent response network ID':'PAIR_NETWORK_ID',
   'unknown response network ID':'PAIR_REQUEST_MISSING','missing response frame':'PAIR_FETCH_FRAME_MISSING',
   'foreign response frame':'PAIR_FRAME','foreign response URL':'PAIR_URL','foreign response method':'PAIR_METHOD',
  };
  const code='BROWSER_GUARD_RESPONSE_'+expected[mode];
  assert.deepEqual(refusals,mode==='eligible'?[]:[code]);
  if(mode!=='eligible'){
   const line='owned_initial_3ds_terminal phase=BROWSER code='+code+' cleanup=CONFIRMED';
   assert.equal(challengeTerminalLine(line),line);
  }
 });
}

for(const mode of ['valid','script options','unbound request','credential','body','foreign origin','wrong method','wrong echo','redirect','stray header','paused credential','paused origin','pending non-options']){
 test('an intercepted browser-generated CORS preflight has no payment authority: '+mode,async()=>{
  const location=origins.demo,url=origins.backend+'/api/v1/customer/subscriptions',commands:string[]=[];
  const events=new Map<string,(e:unknown)=>Promise<void>|void>();let failed=false;
  const frame={url:()=>location,name:()=>'',parentFrame:()=>null,isDetached:()=>false};
  const session={on:(name:string,fn:(e:unknown)=>void)=>events.set(name,fn),send:async(name:string)=>{
   commands.push(name);
   if(name==='Page.getFrameTree')return {frameTree:{frame:{id:'frame',url:location}}};
  }};
  const page={context:()=>({newCDPSession:async()=>session}),frames:()=>[frame],mainFrame:()=>frame,on:()=>{}};
  await protocolGuard(page,()=>{failed=true;});
  const origin=mode==='foreign origin'?'https://foreign.invalid':location;
  const headers={Origin:origin,'Access-Control-Request-Method':mode==='wrong method'?'DELETE':'POST','Access-Control-Request-Headers':'authorization,content-type'};
  const request={url,method:mode==='pending non-options'?'POST':'OPTIONS',headers:{...headers,...(mode==='credential'?{Cookie:'private'}:{})},hasPostData:mode==='body'};
  if(mode!=='unbound request')events.get('Network.requestWillBeSent')!({frameId:'frame',requestId:'preflight',initiator:{type:mode==='script options'?'script':'preflight'},request});
  await events.get('Fetch.requestPaused')!({frameId:'frame',requestId:'pause',networkId:'preflight',request:{...request,headers:{...headers,...(mode==='paused credential'?{Authorization:'private'}:mode==='paused origin'?{Origin:'https://foreign.invalid'}:{})}},responseStatusCode:mode==='redirect'?307:204,
   responseHeaders:[{name:'Access-Control-Allow-Origin',value:mode==='wrong echo'?'*':origin},{name:'Access-Control-Allow-Methods',value:'POST'},
    {name:'Access-Control-Allow-Headers',value:'authorization,content-type'},{name:'Access-Control-Allow-Credentials',value:'true'},
    ...(mode==='stray header'?[{name:'Location',value:'https://foreign.invalid'}]:[])]});
  assert.equal(commands.includes('Fetch.continueResponse'),mode==='valid');assert.equal(failed,mode!=='valid');
 });
}

for(const mode of ['paired','preflight first','missing initiator','wrong target','wrong method','different response frame',
 'replaced initiator','first initiator replaced','pause before initiator','late foreign frame','late decoy','timed out',
 'initiator retired before observation','preflight retired before observation',
 'initiator retired before pause','initiator retired while paused','preflight retired while paused','retired after binding',
 'duplicate pending response','duplicate pause ID','session closed while paused','aborted while paused','early invalid response'])
 test('a preflight without frameId requires the exact initiating request: '+mode,async()=>{
  const url=origins.backend+'/auth',commands:string[]=[],events=new EventEmitter();
  const handler=(name:string)=>events.listeners(name)[0] as (event:unknown)=>void|Promise<void>;
  const frame={url:()=>origins.demo,name:()=>'',parentFrame:()=>null,isDetached:()=>false};
  const session={on:events.on.bind(events),send:async(name:string)=>{
   commands.push(name);
   if(name==='Page.getFrameTree')return {frameTree:{frame:{id:'checkout',url:origins.demo}}};
  }};
  const page={context:()=>({newCDPSession:async()=>session}),frames:()=>[frame],mainFrame:()=>frame,on:()=>{}};
  const refusals:string[]=[];
  const controller=new AbortController();
  const guard=await protocolGuard(page,code=>refusals.push(code),controller.signal);
  const network=handler('Network.requestWillBeSent');
  const post={frameId:mode==='late foreign frame'?'foreign':'checkout',requestId:'post',request:{url:mode==='wrong target'?url+'/other':url,
   method:mode==='wrong method'?'GET':'POST'}};
  const earlyPause=['pause before initiator','late foreign frame','late decoy','timed out','initiator retired before pause',
   'initiator retired while paused','preflight retired while paused','retired after binding','duplicate pending response',
   'initiator retired before observation','preflight retired before observation','duplicate pause ID',
   'session closed while paused','aborted while paused','early invalid response'].includes(mode);
  const preflightFirst=earlyPause||['preflight first','first initiator replaced'].includes(mode);
  if(!preflightFirst)network(post);
  const request={url,method:'OPTIONS',headers:{Origin:origins.demo,'Access-Control-Request-Method':'POST'}};
  if(mode==='initiator retired before observation')events.emit('Network.loadingFailed',{requestId:'post'});
  if(mode==='preflight retired before observation')events.emit('Network.loadingFailed',{requestId:'probe'});
  network({requestId:'probe',initiator:{type:'preflight',requestId:mode==='missing initiator'?'other':'post'},request});
  if(preflightFirst&&!earlyPause)network(post);
  if(mode==='replaced initiator'||mode==='first initiator replaced'){
   events.emit('Network.loadingFailed',{requestId:'post'});
   network(post);
  }
  const pause=(requestId:string,networkId='probe')=>handler('Fetch.requestPaused')({networkId,requestId,request,
   frameId:mode==='different response frame'?'foreign':'checkout',responseStatusCode:204,responseHeaders:[
    {name:'Access-Control-Allow-Origin',value:mode==='early invalid response'?'*':origins.demo},{name:'Access-Control-Allow-Methods',value:'POST'},
    {name:'Access-Control-Allow-Credentials',value:'true'},
   ]});
  if(mode==='initiator retired before pause')events.emit('Network.loadingFailed',{requestId:'post'});
  const pending=pause('paused');
  if(mode==='pause before initiator'){
   assert.ok(!commands.includes('Fetch.continueResponse')&&!commands.includes('Fetch.failRequest'));
  }
  if(mode==='duplicate pending response')await pause('duplicate');
  let duplicate;
  if(mode==='duplicate pause ID'){
   network({requestId:'probe-other',initiator:{type:'preflight',requestId:'post-other'},request});
   duplicate=pause('paused','probe-other');
   network({...post,requestId:'post-other'});
  }
  if(mode==='initiator retired while paused')events.emit('Network.loadingFailed',{requestId:'post'});
  if(mode==='preflight retired while paused')events.emit('Network.loadingFinished',{requestId:'probe'});
  if(mode==='session closed while paused')events.emit('close');
  if(mode==='aborted while paused')controller.abort();
  if(['pause before initiator','late foreign frame','initiator retired while paused','preflight retired while paused',
      'initiator retired before pause','initiator retired before observation','preflight retired before observation',
      'duplicate pending response','duplicate pause ID','retired after binding','aborted while paused'].includes(mode))network(post);
  if(mode==='late decoy')network({...post,requestId:'same-url-decoy'});
  if(mode==='retired after binding')events.emit('Network.loadingFinished',{requestId:'post'});
  await pending;
  await duplicate;
  const released=['paired','preflight first','pause before initiator'].includes(mode);
  assert.equal(refusals.length>0,!released);
  assert.equal(commands.includes('Fetch.continueResponse'),released);
  if(mode==='timed out')assert.deepEqual(refusals,['BROWSER_GUARD_RESPONSE_PAIR_NETWORK_FRAME_MISSING']);
  if(mode==='early invalid response')assert.deepEqual(refusals,['BROWSER_GUARD_RESPONSE_PREFLIGHT']);
  if(mode==='duplicate pending response'||mode==='duplicate pause ID')assert.equal(commands.filter(command=>command==='Fetch.failRequest').length,2);
  if(released){
   // A successful preflight grants no authority to the POST itself.
   await handler('Fetch.requestPaused')({networkId:'post',requestId:'post-paused',frameId:'checkout',
    request:{url,method:'POST'},responseStatusCode:200});
   assert.equal(commands.at(-1),'Fetch.failRequest');
   assert.deepEqual(refusals,['BROWSER_GUARD_RESPONSE_UNCLAIMED']);
   await assert.rejects(guard.authorize({frame:()=>frame,url:()=>url,method:()=>'OPTIONS'} as never));
  }
 });
