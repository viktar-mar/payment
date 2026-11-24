import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
// @ts-ignore Native Node executes the iframe transport source.
import {guardIframeTargets} from './stripe-billing-chromium-iframe.ts';

for(const mode of ['armed','setup rejected','detach during setup','existing target','replacement']){
 test('iframe debugger pause is scoped to the live session: '+mode,async()=>{
  const events=new EventEmitter();
  const commands:{sessionId:string;method:string}[]=[];
  const awaitingFetch=Promise.withResolvers<()=>void>();
  let failures=0;
  const root={
   on:events.on.bind(events),
   async send(method:string,params?:{sessionId:string;message:string}){
    if(method==='Target.setAutoAttach')return;
    assert.equal(method,'Target.sendMessageToTarget');
    const {sessionId,message}=params!;
    const command=JSON.parse(message);
    commands.push({sessionId,method:command.method});
    const respond=()=>events.emit('Target.receivedMessageFromTarget',{sessionId,message:JSON.stringify({
     id:command.id,...(mode==='setup rejected'&&command.method==='Fetch.enable'?{error:{code:-1}}:{result:{}}),
    })});
    if(command.method==='Fetch.enable'&&sessionId==='first')awaitingFetch.resolve(respond);
    else queueMicrotask(respond);
   },
  };
  const targets=await guardIframeTargets(root as never,async session=>{
   await session.send('Network.enable');
   await session.send('Fetch.enable',{patterns:[]});
   return session;
  },()=>failures++);
  const attach=(sessionId:string,waitingForDebugger=true)=>events.emit('Target.attachedToTarget',{
   sessionId,targetInfo:{targetId:'fixture-iframe',type:'iframe'},waitingForDebugger,
  });
  const detach=(sessionId:string)=>events.emit('Target.detachedFromTarget',{sessionId});
  attach('first',mode!=='existing target');
  const get=(generation=targets.generation())=>targets.get('fixture-iframe',generation);
  if(mode==='existing target'){
   await assert.rejects(get(),/billing_BROWSER/);
   assert.equal(failures,1);
   assert.deepEqual(commands,[]);
   return;
  }
  const finish=await awaitingFetch.promise;
  const lookup=get();
  // The request guard cannot be used or the target resumed before Fetch.enable replies.
  assert.deepEqual(commands.map(command=>command.method),['Network.enable','Fetch.enable']);
  if(mode==='detach during setup')detach('first');
  finish();
  if(mode==='detach during setup'||mode==='setup rejected'){
   await assert.rejects(lookup,/billing_BROWSER/);
   assert.equal(failures,mode==='setup rejected'?1:0);
   assert.equal(commands.some(command=>command.method==='Runtime.runIfWaitingForDebugger'),false);
  }else{
   const original=await lookup;
   assert.equal(original.current(),true);
   assert.deepEqual(commands.map(command=>command.method),[
    'Network.enable','Fetch.enable','Target.setAutoAttach','Runtime.runIfWaitingForDebugger',
   ]);
   if(mode==='replacement'){
    const staleRoute=targets.generation();
    detach('first');
    attach('replacement');
    detach('first');
    assert.equal(original.current(),false);
    await assert.rejects(get(staleRoute),/billing_BROWSER/);
    const replacement=await get();
    assert.notEqual(replacement.guard,original.guard);
    assert.equal(replacement.current(),true);
    assert.equal(failures,0);
   }
  }
  events.emit('close');
  assert.equal(targets.active(),false);
  await assert.rejects(get(),/billing_BROWSER/);
 });
}
