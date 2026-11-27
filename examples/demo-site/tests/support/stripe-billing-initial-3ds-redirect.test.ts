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
 'expected issuer font block with decoy','expected issuer font block with wrong suffix',
 'expected issuer stylesheet block','expected issuer stylesheet block with 200','expected issuer stylesheet block with wrong error',
 'expected issuer stylesheet block with decoy','expected issuer stylesheet block with wrong suffix'])
 test('Chromium route evidence rejects a false match: '+mode,async()=>{
 const font=mode.startsWith('expected issuer font block');
 const stylesheet=mode.startsWith('expected issuer stylesheet block');
 const block=font||stylesheet||mode.startsWith('expected issuer block');
 const wrongSuffix=mode.endsWith('wrong suffix');
 const approved=block?'https://reviewed-test-issuer.stripe.com':undefined;
 const suffix=wrongSuffix?'.png':stylesheet?'.css':font?'.woff2':'.png';
 const url=approved?approved+'/fixture'+suffix:'https://api.stripe.com/fixture-read',location=origins.demo+'/';
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
  redirectedFrom:()=>null,serviceWorker:()=>null,isNavigationRequest:()=>false,
  resourceType:()=>stylesheet?'stylesheet':font?'font':block?'image':'xhr'} as never;
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
  const exactFailure=mode.endsWith(' block'),http=mode.endsWith('with 200');
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

type DecisionBody='exact'|'missing length'|'large'|'encoded'|'encoded alias'|'short body'|'long body'|'duplicate pause'|'retired'|'timeout'|'abort';
type IssuerResultPolicy={release?:true;claimFailure?:string;responseFailure?:string;status?:number;type?:string;header?:string;decision?:DecisionBody};
const issuerResultPolicy:Record<string,IssuerResultPolicy>={
 'exact source and response':{release:true},
 'same URL with a different source loader':{claimFailure:'BROWSER_GUARD_ROUTE_ISSUER_RESULT_DOCUMENT'},
 'retired source while claim waits':{claimFailure:'BROWSER_GUARD_ROUTE_IDENTITY'},
 'unregistered request':{claimFailure:'BROWSER_GUARD_ROUTE_IDENTITY'},
 'fixture without Chromium identity':{claimFailure:'BROWSER_GUARD_ROUTE_IDENTITY'},
 'foreign result origin':{claimFailure:'BROWSER_GUARD_ROUTE_IDENTITY'},
 'redirected source document':{claimFailure:'BROWSER_GUARD_ROUTE_IDENTITY'},
 'replay with another Network request':{claimFailure:'BROWSER_GUARD_ROUTE_ISSUER_RESULT_DOCUMENT'},
 'redirect response':{status:302,responseFailure:'BROWSER_GUARD_RESPONSE_REDIRECT'},
 'aborted before response':{responseFailure:'BROWSER_GUARD_RESPONSE_PAIR_REQUEST_MISSING'},
 'wrong response frame':{responseFailure:'BROWSER_GUARD_RESPONSE_PAIR_FRAME'},
 'wrong response resource':{responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'},
 'non-200 response':{status:201,responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'},
 'non-HTML response':{type:'application/json',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'},
 'ambiguous media type':{header:'Content-Type',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'},
 'cookie response':{header:'Set-Cookie',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'},
 'location response':{header:'Location',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'},
 'refresh response':{header:'Refresh',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'},
 'attachment response':{header:'Content-Disposition',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_RESULT'},
 'decision body captured exactly once':{decision:'exact',release:true},
 'decision body without announced length':{decision:'missing length',release:true},
 'decision body exceeds the byte limit':{decision:'large',release:true},
 'encoded decision body is not captured':{decision:'encoded',release:true},
 'compressed decision rejects reuse of the original Network ID at an untracked URL':{decision:'encoded alias',release:true},
 'decision body length does not match':{decision:'short body',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_DECISION'},
 'decision body wire size exceeds the limit':{decision:'long body',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_DECISION'},
 'decision capture invalidated by duplicate':{decision:'duplicate pause',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_DECISION'},
 'decision capture invalidated by retirement':{decision:'retired',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_DECISION'},
 'decision capture times out while Chromium still owns the read':{decision:'timeout',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_DECISION'},
 'decision capture abort suppresses only commands for the active read':{decision:'abort',responseFailure:'BROWSER_GUARD_RESPONSE_ISSUER_DECISION'},
};

for(const [mode,policy] of Object.entries(issuerResultPolicy))test('issuer result claim uses one original decision and guarded response: '+mode,{timeout:4000},async()=>{
 const approved='https://reviewed-test-issuer.stripe.com',address=approved+'/decision',frameId='issuer';
 const url=policy.decision?address:(mode==='foreign result origin'?approved:'https://hooks.stripe.com')+'/result',location=origins.demo+'/';
 const browserSession={},symbol=Symbol('InterceptableRequest'),pending=new Map();
 const frameDriver=(id:string,parent:unknown,documentId:string)=>({
  _id:id,_page:undefined as unknown,_currentDocument:{documentId},_isDetached:()=>false,parentFrame:()=>parent,
 });
 const main=frameDriver('checkout',null,'checkout-loader'),issuer=frameDriver(frameId,main,'decision-loader');
 const impl={
  frameManager:{frame:(id:string)=>[main,issuer].find(node=>node._id===id)},
  delegate:{_targetId:main._id,_sessions:new Map([[main._id,{_targetId:main._id,_client:browserSession}]]),
   _networkManager:{_requestIdToRequest:pending}},
 };
 main._page=issuer._page=impl;
 const connection={toImpl:(client:{driver:unknown})=>client.driver};
 const wrap=(driver:unknown,href:string)=>({_connection:connection,driver,url:()=>href,name:()=>'',isDetached:()=>false});
 const top=wrap(main,location),frame=wrap(issuer,address);
 const events=new Map<string,(value:unknown)=>void|Promise<void>>(),commands:string[]=[],failures:string[]=[];
 const body='<form></form>',reading=Promise.withResolvers<void>(),wireBody=Promise.withResolvers<{body:string;base64Encoded:boolean}>();
 const session={on:(name:string,callback:(value:unknown)=>void)=>events.set(name,callback),send:async(method:string,args?:{requestId:string})=>{
  commands.push(method);
  if(method==='Page.getFrameTree')return {frameTree:{frame:{id:main._id,url:location}}};
  if(method==='Fetch.getResponseBody'){
   assert.deepEqual(args,{requestId:'response-result'});
   reading.resolve();
   return wireBody.promise;
  }
 }};
 const fixture={mainFrame:()=>top,context:()=>({newCDPSession:async()=>session})};
 const page=mode==='fixture without Chromium identity'?fixture:{...fixture,_connection:connection,driver:impl};
 if(page===fixture)registerChromiumProtocolFixture(fixture);
 const abort=new AbortController();
 const guard=await guardChallengeRedirects(page as never,code=>failures.push(code),origins,abort.signal,approved,policy.decision==='encoded alias');
 const original=(href:string,documentId:string)=>({frame:()=>issuer,method:()=>'POST',url:()=>href,_documentId:documentId});
 const shape=(href:string,driver:ReturnType<typeof original>)=>({
  _connection:connection,driver,frame:()=>frame,url:()=>href,method:()=>'POST',isNavigationRequest:()=>true,
  resourceType:()=>'document',serviceWorker:()=>null,redirectedFrom:()=>null,
 });
 const completed=shape(address,original(address,mode==='same URL with a different source loader'?'retired-loader':'decision-loader'));
 const decision={...completed,redirectedFrom:()=>mode==='redirected source document'?{}:null} as never;
 const passive={frameId,loaderId:'destination-loader',type:'Document',request:{url,method:'POST'}};
 const connect=(networkId:string,register=true)=>{
  const driver=original(url,'destination-loader');
  const intercepted={request:driver,session:browserSession,_requestId:networkId,_interceptionId:'fetch-'+networkId,_originalRequestRoute:{}};
  Object.defineProperty(driver,symbol,{value:intercepted});
  pending.set(networkId,intercepted);
  const request=shape(url,driver) as never;
  if(register)guard.registerRoute(request);
  return request;
 };
 const record=(networkId:string)=>events.get('Network.requestWillBeSent')!({...passive,requestId:networkId});
 const respond=(networkId:string,requestId='response-'+networkId)=>events.get('Fetch.requestPaused')!({...passive,networkId,requestId,
  frameId:mode==='wrong response frame'?'foreign':frameId,
  resourceType:mode==='wrong response resource'?'XHR':'Document',responseStatusCode:policy.status??200,
  responseHeaders:[{name:'content-type',value:policy.type??'text/html; charset=UTF-8'},
   ...(policy.decision&&policy.decision!=='missing length'?[{name:'Content-Length',value:String(policy.decision==='large'?65537:body.length)}]:[]),
   ...(policy.decision?.startsWith('encoded')?[{name:'Content-Encoding',value:'gzip'}]:[]),
   ...(policy.header?[{name:policy.header,value:'fixture'}]:[])],
 });
 const request=connect('result',mode!=='unregistered request');
 if(mode!=='retired source while claim waits')record('result');
 if(policy.decision){
  assert.ok(guard.authorizeIssuerDecision);
  await guard.authorizeIssuerDecision(request);
  const seen=respond('result');
  if(policy.decision==='encoded alias'){
   await seen;
   events.get('Network.requestWillBeSent')!({...passive,requestId:'result',request:{url:'https://untracked.invalid/',method:'POST'}});
   assert.deepEqual(failures,['BROWSER_GUARD_REQUEST_PAIR']);
   assert.equal(await guard.takeIssuerDecisionBody(request),'BROWSER_CHALLENGE_CRES_DECISION_READ');
   assert.equal(commands.includes('Network.getResponseBody'),false);
   return;
  }
  const capture=!['missing length','large','encoded'].includes(policy.decision);
  if(capture){
   await reading.promise;
   assert.equal(commands.includes('Fetch.continueResponse'),false);
   assert.equal(guard.takeIssuerDecisionBody(request),undefined);
   if(policy.decision==='duplicate pause')await respond('result');
   if(policy.decision==='retired'){
    events.get('Network.loadingFailed')!({requestId:'result'});
    await respond('result','different-response-id');
   }
   if(policy.decision==='abort'){
    abort.abort();
    await respond('result');
   }
   if(policy.decision==='timeout'||policy.decision==='abort')await seen;
   assert.equal(commands.includes('Fetch.failRequest'),false);
   const reply=policy.decision==='short body'?body.slice(1):policy.decision==='long body'?body.repeat(8):body;
   wireBody.resolve({body:Buffer.from(reply).toString('base64'),base64Encoded:true});
  }
  await seen;
  assert.equal(commands.filter(command=>command==='Fetch.getResponseBody').length,capture?1:0);
  assert.equal(commands.at(-1),policy.decision==='timeout'||policy.decision==='abort'?'Fetch.getResponseBody':
   policy.release?'Fetch.continueResponse':'Fetch.failRequest');
  assert.equal(guard.takeIssuerDecisionBody(decision),undefined);
  assert.equal(guard.takeIssuerDecisionBody(request)?.toString(),policy.decision==='exact'?body:undefined);
  assert.equal(guard.takeIssuerDecisionBody(request),undefined);
  assert.deepEqual(failures,[
   ...(policy.decision==='duplicate pause'||policy.decision==='retired'?['BROWSER_GUARD_RESPONSE_PAIR_DUPLICATE']:[]),
   ...(policy.responseFailure?[policy.responseFailure]:[]),
  ]);
  return;
 }
 if(mode==='replay with another Network request')await guard.authorizeIssuerResult(request,decision);
 const target=mode==='replay with another Network request'?connect('replay'):request;
 if(target!==request)record('replay');
 const authorization=guard.authorizeIssuerResult(target,decision);
 if(mode==='retired source while claim waits'){
  issuer._currentDocument={documentId:'replacement-loader'};
  record('result');
 }
 if(policy.claimFailure){
  await assert.rejects(authorization,/^Error: billing_BROWSER$/);
  assert.deepEqual(failures,[policy.claimFailure]);
  assert.equal(commands.includes('Fetch.continueResponse'),false);
  return;
 }
 await authorization;
 if(mode==='aborted before response')abort.abort();
 await respond('result');
 assert.equal(commands.at(-1),policy.release?'Fetch.continueResponse':'Fetch.failRequest');
 assert.deepEqual(failures,policy.responseFailure?[policy.responseFailure]:[]);
});

for(const mode of ['exact source and block','http response','wrong loader','wrong loader http response','wrong source loader',
 'non-parser initiator','foreign parser document','wrong parser resource','foreign frame','unregistered decision',
 'wrong decision method','no decision proof requested','wrong suffix'])test(
 'issuer stylesheet block binds its exact committed decision: '+mode,async()=>{
 const approved='https://reviewed-test-issuer.stripe.com',address=approved+'/decision';
 const url=approved+(mode==='wrong suffix'?'/fixture.png':'/fixture.css');
 const browserSession={},symbol=Symbol('InterceptableRequest'),pending=new Map(),frameId='issuer',loaderId='decision-loader';
 const main={_id:'checkout',_page:undefined as unknown,_currentDocument:{documentId:'checkout-loader'},_isDetached:()=>false,parentFrame:()=>null};
 const issuer={_id:frameId,_page:undefined as unknown,_currentDocument:{documentId:loaderId},_isDetached:()=>false,parentFrame:()=>main};
 const impl={frameManager:{frame:(id:string)=>[main,issuer].find(node=>node._id===id)},
  delegate:{_targetId:main._id,_sessions:new Map([[main._id,{_targetId:main._id,_client:browserSession}]]),
  _networkManager:{_requestIdToRequest:pending}}};
 main._page=issuer._page=impl;
 const connection={toImpl:(client:{driver:unknown})=>client.driver},location=origins.demo+'/';
 const wrap=(driver:unknown,href:string)=>({_connection:connection,driver,url:()=>href,name:()=>'',isDetached:()=>false});
 const top=wrap(main,location),frame=wrap(issuer,address);
 const events=new Map<string,(value:unknown)=>void|Promise<void>>(),commands:string[]=[],failures:string[]=[];
 const session={on:(name:string,callback:(value:unknown)=>void)=>events.set(name,callback),send:async(method:string)=>{
  commands.push(method);
  if(method==='Page.getFrameTree')return {frameTree:{frame:{id:main._id,url:location}}};
 }};
 const page={_connection:connection,driver:impl,mainFrame:()=>top,context:()=>({newCDPSession:async()=>session})};
 const guard=await guardChallengeRedirects(page as never,code=>failures.push(code),origins,undefined,approved);
 const connect=(networkId:string,href:string,method:string,documentId?:string,register=true)=>{
  const driver={frame:()=>issuer,url:()=>href,method:()=>method,_documentId:documentId};
  const route={request:driver,session:browserSession,_requestId:networkId,_interceptionId:'fetch-'+networkId,_originalRequestRoute:{}};
  Object.defineProperty(driver,symbol,{value:route});
  pending.set(networkId,route);
  const request={_connection:connection,driver,frame:()=>frame,url:()=>href,method:()=>method,isNavigationRequest:()=>!!documentId,
   resourceType:()=>documentId?'document':'stylesheet',serviceWorker:()=>null,redirectedFrom:()=>null} as never;
  if(register)guard.registerRoute(request);
  return request;
 };
 const decision=connect('decision',address,mode==='wrong decision method'?'GET':'POST',
  mode==='wrong source loader'?'retired-loader':loaderId,mode!=='unregistered decision');
 const stylesheet=connect('stylesheet',url,'GET');
 const passive={frameId:mode==='foreign frame'?'foreign':frameId,loaderId:mode.startsWith('wrong loader')?'foreign-loader':loaderId,
  documentURL:address,type:mode==='wrong parser resource'?'Script':'Stylesheet',
  initiator:{type:mode==='non-parser initiator'?'script':'parser',url:mode==='foreign parser document'?address+'/different':address},
  request:{url,method:'GET'}};
 events.get('Network.requestWillBeSent')!({...passive,requestId:'stylesheet'});
 const generic=mode==='no decision proof requested';
 const claim=guard.expectIssuerAssetBlock(stylesheet,generic?undefined:decision);
 if(mode==='foreign frame'||mode==='wrong suffix'){
  await assert.rejects(claim,/^Error: billing_BROWSER$/);
  assert.deepEqual(failures,[mode==='foreign frame'?'BROWSER_GUARD_ROUTE_EVENT_FRAME':'BROWSER_GUARD_ROUTE_IDENTITY']);
  return;
 }
 assert.equal(await claim,generic?undefined:mode==='exact source and block'||mode==='http response');
 const http=mode.endsWith('http response');
 await events.get('Fetch.requestPaused')!({...passive,networkId:'stylesheet',requestId:'response-stylesheet',
  ...(http?{responseStatusCode:200}:{responseErrorReason:'BlockedByClient'})});
 assert.equal(commands.at(-1),'Fetch.failRequest');
 assert.equal(commands.includes('Fetch.continueResponse'),false);
 assert.deepEqual(failures,http?['BROWSER_GUARD_RESPONSE_UNCLAIMED']:[]);
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
