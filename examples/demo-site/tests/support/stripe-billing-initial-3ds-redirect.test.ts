import test from 'node:test';
import assert from 'node:assert/strict';
// @ts-ignore Offline source.
import {guardChallengeRedirects} from './stripe-billing-initial-3ds-redirect.ts';

const origins={backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:18081'};
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
  const guard=await guardChallengeRedirects(page as never,()=>{},origins);
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

for(const mode of ['eligible','redirect','not modified','missing request','foreign frame','unclaimed response','error response','network failure','foreign response']){
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
  const guard=await guardChallengeRedirects(page as never,reason=>refusals.push(reason),origins);
  if(mode!=='missing request')events.get('Network.requestWillBeSent')!({requestId:'network',frameId:mode==='foreign frame'?'wrong':'frame',request:{url:href,method:'POST'}});
  const request={url:()=>href,method:()=>'POST',frame:()=>frame};
  if(['missing request','foreign frame'].includes(mode)){
   await assert.rejects(guard.authorize(request as never));assert.equal(commands.includes('Fetch.continueResponse'),false);return;
  }
  if(mode!=='unclaimed response')await guard.authorize(request as never);
  await events.get('Fetch.requestPaused')!({requestId:'paused',networkId:'network',frameId:'frame',
   request:{url:mode==='foreign response'?href+'x':href,method:'POST'},
   responseStatusCode:mode==='redirect'?307:mode==='not modified'?304:mode==='error response'?500:200,
   responseErrorReason:mode==='network failure'?'private failure canary':undefined});
  assert.equal(commands.includes('Fetch.continueResponse'),mode==='eligible');
  assert.equal(commands.includes('Fetch.failRequest'),mode!=='eligible');
  const expected=mode==='redirect'?'BROWSER_GUARD_RESPONSE_REDIRECT':
   mode==='error response'||mode==='not modified'?'BROWSER_GUARD_RESPONSE_STATUS':
   mode==='network failure'?'BROWSER_GUARD_RESPONSE_NETWORK':
   mode==='unclaimed response'?'BROWSER_GUARD_RESPONSE_UNCLAIMED':'BROWSER_GUARD_RESPONSE_PAIR';
  assert.deepEqual(refusals,mode==='eligible'?[]:[expected]);
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
  await guardChallengeRedirects(page as never,()=>{failed=true;},origins);
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

for(const mode of ['paired','missing initiator','wrong target','wrong method','different response frame','replaced initiator'])
 test('a preflight without frameId requires the exact initiating request: '+mode,async()=>{
  const url=origins.backend+'/auth',commands:string[]=[],events=new Map<string,(e:unknown)=>Promise<void>|void>();
  const frame={url:()=>origins.demo,name:()=>'',parentFrame:()=>null,isDetached:()=>false};
  const session={on:(name:string,listener:(e:unknown)=>void)=>events.set(name,listener),send:async(name:string)=>{
   commands.push(name);
   if(name==='Page.getFrameTree')return {frameTree:{frame:{id:'checkout',url:origins.demo}}};
  }};
  const page={context:()=>({newCDPSession:async()=>session}),frames:()=>[frame],mainFrame:()=>frame,on:()=>{}};
  let rejected=false;
  const guard=await guardChallengeRedirects(page as never,()=>{rejected=true;},origins);
  const network=events.get('Network.requestWillBeSent')!;
  const post={frameId:'checkout',requestId:'post',request:{url:mode==='wrong target'?url+'/other':url,
   method:mode==='wrong method'?'GET':'POST'}};
  network(post);
  const request={url,method:'OPTIONS',headers:{Origin:origins.demo,'Access-Control-Request-Method':'POST'}};
  network({requestId:'probe',initiator:{type:'preflight',requestId:mode==='missing initiator'?'other':'post'},request});
  if(mode==='replaced initiator'){
   events.get('Network.loadingFailed')!({requestId:'post'});
   network(post);
  }
  await events.get('Fetch.requestPaused')!({networkId:'probe',requestId:'paused',request,
   frameId:mode==='different response frame'?'foreign':'checkout',responseStatusCode:204,responseHeaders:[
    {name:'Access-Control-Allow-Origin',value:origins.demo},{name:'Access-Control-Allow-Methods',value:'POST'},
    {name:'Access-Control-Allow-Credentials',value:'true'},
   ]});
  assert.equal(rejected,mode!=='paired');
  assert.equal(commands.includes('Fetch.continueResponse'),mode==='paired');
  if(mode==='paired'){
   // A successful preflight grants no authority to the POST itself.
   await events.get('Fetch.requestPaused')!({networkId:'post',requestId:'post-paused',frameId:'checkout',
    request:{url,method:'POST'},responseStatusCode:200});
   assert.equal(commands.at(-1),'Fetch.failRequest');
   assert.equal(rejected,true);
   await assert.rejects(guard.authorize({frame:()=>frame,url:()=>url,method:()=>'OPTIONS'} as never));
  }
 });
