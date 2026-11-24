import {EventEmitter} from 'node:events';
import type {CDPSession} from '@playwright/test';

export type GuardSession=Pick<CDPSession,'send'|'on'>;
const refusal=()=>Error('billing_BROWSER');

// Playwright's public CDPSession does not expose a flattened child session.
// The page-scoped Chromium transport delivers nested replies to their owning session.
function iframeSession(parent:GuardSession,sessionId:string){
 const events=new EventEmitter();
 const commands=new Map<number,(reply?:{result?:unknown;error?:unknown})=>void>();
 let nextId=0,closed=false;
 const send=(method:string,params?:unknown)=>new Promise<unknown>((resolve,reject)=>{
  if(closed||commands.size>=64){reject(refusal());return;}
  const id=++nextId;
  const timer=setTimeout(()=>commands.get(id)?.(),5000);
  commands.set(id,reply=>{
   clearTimeout(timer);commands.delete(id);
   if(!reply||reply.error!==undefined)reject(refusal());
   else resolve(reply.result);
  });
  void parent.send('Target.sendMessageToTarget',{sessionId,message:JSON.stringify({id,method,params})})
   .catch(()=>commands.get(id)?.());
 });
 // The tunnel carries the same Chromium protocol payloads as a public CDP session.
 const session={on:events.on.bind(events),send} as unknown as GuardSession;
 return {
  session,
  receive(message:string){
   if(closed)return;
   const reply=JSON.parse(message);
   if(Number.isSafeInteger(reply.id))commands.get(reply.id)?.(reply);
   else if(typeof reply.method==='string'&&reply.method!=='close')events.emit(reply.method,reply.params);
  },
  close(){
   if(closed)return;
   closed=true;
   for(const finish of commands.values())finish();
   events.emit('close');
   events.removeAllListeners();
  },
 };
}

/** Pause out-of-process iframe startup until its own request and response guards are armed. */
export async function guardIframeTargets<T>(main:GuardSession,arm:(session:GuardSession)=>Promise<T>,onFailure:()=>void){
 const targets=new Map<string,{generation:number;ready:Promise<T>}>();
 let generation=0,failed=false,mainClosed=false;
 const active=()=>!failed&&!mainClosed;
 const fail=()=>{if(!failed){failed=true;onFailure();}};
 main.on('close',()=>{mainClosed=true;});
 function observe(parent:GuardSession):Promise<unknown>{
  const children=new Map<string,{targetId:string;transport:ReturnType<typeof iframeSession>}>();
  const detach=(sessionId:string)=>{
   const child=children.get(sessionId);
   if(!child)return;
   children.delete(sessionId);
   targets.delete(child.targetId);
   child.transport.close();
  };
  parent.on('close',()=>{for(const id of children.keys())detach(id);});
  parent.on('Target.detachedFromTarget',event=>detach(event.sessionId));
  parent.on('Target.receivedMessageFromTarget',event=>{
   try{children.get(event.sessionId)?.transport.receive(event.message);}
   catch{fail();}
  });
  parent.on('Target.attachedToTarget',event=>{
   if(!active())return;
   const {sessionId,targetInfo:{targetId,type},waitingForDebugger}=event;
   if(type!=='iframe'||!waitingForDebugger||!targetId||!sessionId||
      targets.size>=24||targets.has(targetId)||children.has(sessionId)){fail();return;}
   const transport=iframeSession(parent,sessionId);
   const child={targetId,transport};
   children.set(sessionId,child);
   const ready=(async()=>{
    const guard=await arm(transport.session);
    await observe(transport.session);
    if(!active()||children.get(sessionId)!==child)throw refusal();
    await transport.session.send('Runtime.runIfWaitingForDebugger');
    return guard;
   })();
   targets.set(targetId,{generation:++generation,ready});
   void ready.catch(()=>{if(children.get(sessionId)===child)fail();});
  });
  return parent.send('Target.setAutoAttach',{autoAttach:true,waitForDebuggerOnStart:true,flatten:false,
   filter:[{type:'iframe'},{exclude:true}]});
 }
 await observe(main);
 return {
  active,
  generation:()=>generation,
  async get(targetId:string,before:number){
   const target=targets.get(targetId);
   if(!active()||!target||target.generation>before)throw refusal();
   const current=()=>active()&&targets.get(targetId)===target;
   const guard=await target.ready;
   if(!current())throw refusal();
   return {guard,current};
  },
 };
}
