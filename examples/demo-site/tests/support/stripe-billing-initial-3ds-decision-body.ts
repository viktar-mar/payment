import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
import type {GuardSession} from './stripe-billing-chromium-iframe.ts';

export const issuerDecisionBodyLimit=65536;
export type IssuerDecisionBody=Uint8Array|'BROWSER_CHALLENGE_CRES_DECISION_READ'|'BROWSER_CHALLENGE_CRES_DECISION_SIZE';
const readFailed='BROWSER_CHALLENGE_CRES_DECISION_READ';
const invalidSize='BROWSER_CHALLENGE_CRES_DECISION_SIZE';
const buffers={maxTotalBufferSize:issuerDecisionBodyLimit,maxResourceBufferSize:issuerDecisionBodyLimit};

type DecisionSource={id:string;fetchId:string;frameId:string;loaderId:string;url:string;encodedLength?:number};
type Decision=DecisionSource&{
 responseSeen:boolean;finished:boolean;decodedSize:number;
 release:PromiseWithResolvers<boolean>;result:PromiseWithResolvers<IssuerDecisionBody>;
 timer:ReturnType<typeof setTimeout>;
};

/** Observe the exact admitted decision without modifying or replaying its response. */
export function observeCompressedIssuerDecision(session:GuardSession,signal?:AbortSignal){
 let decision:Decision|undefined;
 let closed=false;
 function finish(current:Decision,body:IssuerDecisionBody){
  if(decision!==current)return;
  clearTimeout(current.timer);
  decision=undefined;
  current.release.resolve(false);
  current.result.resolve(body);
 }
 function invalidate(id:string){
  if(decision?.id===id)finish(decision,readFailed);
 }
 function close(){
  closed=true;
  if(decision)finish(decision,readFailed);
 }
 session.on('Network.responseReceived',event=>{
  const current=decision;
  if(!current||current.id!==event.requestId)return;
  const response=event.response;
  if(current.responseSeen||event.type!=='Document'||event.frameId!==current.frameId||event.loaderId!==current.loaderId||
     response.url!==current.url||response.status!==200||response.fromServiceWorker||response.fromDiskCache||response.fromPrefetchCache){
   finish(current,readFailed);return;
  }
  current.responseSeen=true;
 });
 session.on('Network.dataReceived',event=>{
  const current=decision;
  if(!current||current.id!==event.requestId)return;
  if(!current.responseSeen||current.finished){finish(current,readFailed);return;}
  if(!Number.isSafeInteger(event.dataLength)||event.dataLength<0||
     event.dataLength>issuerDecisionBodyLimit-current.decodedSize){finish(current,invalidSize);return;}
  current.decodedSize+=event.dataLength;
 });
 session.on('Network.requestServedFromCache',event=>invalidate(event.requestId));
 session.on('Network.loadingFailed',event=>invalidate(event.requestId));
 session.on('Network.loadingFinished',event=>{
  const current=decision;
  if(!current||current.id!==event.requestId)return;
  if(!current.responseSeen||current.finished){finish(current,readFailed);return;}
  current.finished=true;
  const encoded=event.encodedDataLength;
  // Encoded bytes are bounded separately. Only decoded dataLength authorizes the body read.
  if(!current.decodedSize||!Number.isSafeInteger(encoded)||encoded<=0||encoded>issuerDecisionBodyLimit||
     current.encodedLength!==undefined&&encoded<current.encodedLength){finish(current,invalidSize);return;}
  void billingBounded({signal,deadline:Date.now()+1000},async limits=>{
   if(!await current.release.promise||decision!==current)return;
   billingCheck(limits);
   const value=await session.send('Network.getResponseBody',{requestId:current.id});
   billingCheck(limits);
   if(decision!==current)return;
   const size=current.decodedSize;
   if(typeof value.body!=='string'||typeof value.base64Encoded!=='boolean'||
      (value.base64Encoded?value.body.length>Math.ceil(size/3)*4:Buffer.byteLength(value.body,'utf8')!==size)){
    finish(current,invalidSize);return;
   }
   const bytes=Buffer.from(value.body,value.base64Encoded?'base64':'utf8');
   if(bytes.length!==size){finish(current,invalidSize);return;}
   if(bytes.toString(value.base64Encoded?'base64':'utf8')!==value.body){finish(current,readFailed);return;}
   finish(current,bytes);
  }).catch(()=>finish(current,readFailed));
 });
 return {
  async enable(){
   await session.send('Network.configureDurableMessages',buffers);
   await session.send('Network.enable',buffers);
  },
  has:(id:string)=>decision?.id===id,
  invalidate,close,
  duplicatePause(fetchId:string,networkId:string|undefined){
   if(!decision||(decision.fetchId!==fetchId&&decision.id!==networkId))return false;
   finish(decision,readFailed);
   return true;
  },
  observe(source:DecisionSource){
   if(decision||closed||signal?.aborted)throw Error('billing_BROWSER');
   const current:Decision={...source,responseSeen:false,finished:false,decodedSize:0,
    release:Promise.withResolvers<boolean>(),result:Promise.withResolvers<IssuerDecisionBody>(),
    timer:setTimeout(()=>finish(current,readFailed),2000)};
   decision=current;
   return {body:current.result.promise,released:()=>current.release.resolve(true)};
  },
 };
}
