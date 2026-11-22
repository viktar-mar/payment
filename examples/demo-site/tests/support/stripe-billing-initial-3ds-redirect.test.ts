import test from 'node:test';
import assert from 'node:assert/strict';
// @ts-ignore Offline source.
import {guardChallengeRedirects} from './stripe-billing-initial-3ds-redirect.ts';

const origins={backend:'http://127.0.0.1:18080',demo:'http://127.0.0.1:18081'};
for(const mode of ['eligible','redirect','missing request','foreign frame','unclaimed response','error response','foreign response']){
 test('Chromium response interception retains original bytes and stops '+mode,async()=>{
  const href='https://api.stripe.com/v1/3ds2/authenticate',location='https://js.stripe.com/v3/frame';
  const commands:string[]=[],events=new Map<string,(e:unknown)=>Promise<void>|void>();let invalid=false;
  const frame={url:()=>location,name:()=>'',parentFrame:()=>null,isDetached:()=>false};
  const session={on:(event:string,listener:(e:unknown)=>Promise<void>)=>events.set(event,listener),send:async(command:string,args:unknown)=>{
   commands.push(command);
   if(command==='Page.getFrameTree')return {frameTree:{frame:{id:'frame',url:location}}};
   if(command==='Fetch.continueResponse')assert.deepEqual(args,{requestId:'paused'});
  }};
  const page={context:()=>({newCDPSession:async()=>session}),frames:()=>[frame],mainFrame:()=>frame,on:()=>{}};
  const guard=await guardChallengeRedirects(page as never,()=>{invalid=true;},origins);
  if(mode!=='missing request')events.get('Network.requestWillBeSent')!({requestId:'network',frameId:mode==='foreign frame'?'wrong':'frame',request:{url:href,method:'POST'}});
  const request={url:()=>href,method:()=>'POST',frame:()=>frame};
  if(['missing request','foreign frame'].includes(mode)){
   await assert.rejects(guard.authorize(request as never));assert.equal(commands.includes('Fetch.continueResponse'),false);return;
  }
  if(mode!=='unclaimed response')await guard.authorize(request as never);
  await events.get('Fetch.requestPaused')!({requestId:'paused',networkId:'network',frameId:'frame',
   request:{url:mode==='foreign response'?href+'x':href,method:'POST'},responseStatusCode:mode==='redirect'?307:mode==='error response'?500:200});
  assert.equal(commands.includes('Fetch.continueResponse'),mode==='eligible');
  assert.equal(commands.includes('Fetch.failRequest'),mode!=='eligible');assert.equal(invalid,mode!=='eligible');
 });
}

for(const mode of ['valid','script options','unbound request','credential','body','foreign origin','wrong method','wrong echo','redirect','stray header','paused credential','paused origin','pending non-options']){
 test('an intercepted browser-generated CORS preflight has no payment authority: '+mode,async()=>{
  const location=origins.demo,url=origins.backend+'/api/v1/customer/subscriptions',commands:string[]=[];
  const events=new Map<string,(e:unknown)=>Promise<void>|void>();let failed=false;
  const frame={url:()=>location,name:()=>'',parentFrame:()=>null,isDetached:()=>false};
  const session={on:(name:string,fn:(e:unknown)=>void)=>events.set(name,fn),send:async(name:string)=>{commands.push(name);}};
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
