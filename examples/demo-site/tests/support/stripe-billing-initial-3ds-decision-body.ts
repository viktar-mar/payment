import {billingBounded,billingCheck} from '../../../../infrastructure/stripe-billing-limits.mjs';
import type {GuardSession} from './stripe-billing-chromium-iframe.ts';

export const issuerDecisionBodyLimit=65536;
export type IssuerDecisionBody=Uint8Array|'BROWSER_CHALLENGE_CRES_DECISION_READ'|'BROWSER_CHALLENGE_CRES_DECISION_SIZE';
const readFailed='BROWSER_CHALLENGE_CRES_DECISION_READ';
const invalidSize='BROWSER_CHALLENGE_CRES_DECISION_SIZE';
const buffers={maxTotalBufferSize:issuerDecisionBodyLimit,maxResourceBufferSize:issuerDecisionBodyLimit};

type Resource='Document'|'Script';
type BodySource={id:string;fetchId:string;frameId:string;loaderId:string;url:string;
 resource?:Resource;encodedLength?:number;decodedLength?:number;asciiOnly?:boolean};
type PendingBody=BodySource&{
 resource:Resource;
 responseSeen:boolean;finished:boolean;decodedSize:number;
 release:PromiseWithResolvers<boolean>;result:PromiseWithResolvers<IssuerDecisionBody>;
 timer:ReturnType<typeof setTimeout>;
};

/** Observe the admitted decision and script on their own network IDs. */
export function observeCompressedIssuerDecision(session:GuardSession,signal?:AbortSignal){
 const pending=new Map<string,PendingBody>();
 let closed=false;
 function finish(current:PendingBody,body:IssuerDecisionBody){
  if(pending.get(current.id)!==current)return;
  clearTimeout(current.timer);
  pending.delete(current.id);
  current.release.resolve(false);
  current.result.resolve(body);
 }
 function invalidate(id:string){
  const current=pending.get(id);
  if(current)finish(current,readFailed);
 }
 function close(){
  closed=true;
  for(const current of pending.values())finish(current,readFailed);
 }
 session.on('Network.responseReceived',event=>{
  const current=pending.get(event.requestId);
  if(!current)return;
  const response=event.response;
  if(current.responseSeen||event.type!==current.resource||event.frameId!==current.frameId||event.loaderId!==current.loaderId||
     response.url!==current.url||response.status!==200||response.fromServiceWorker||response.fromDiskCache||response.fromPrefetchCache){
   finish(current,readFailed);return;
  }
  current.responseSeen=true;
 });
 session.on('Network.dataReceived',event=>{
  const current=pending.get(event.requestId);
  if(!current)return;
  if(!current.responseSeen||current.finished){finish(current,readFailed);return;}
  if(!Number.isSafeInteger(event.dataLength)||event.dataLength<0||
     event.dataLength>(current.decodedLength??issuerDecisionBodyLimit)-current.decodedSize){finish(current,invalidSize);return;}
  current.decodedSize+=event.dataLength;
 });
 session.on('Network.requestServedFromCache',event=>invalidate(event.requestId));
 session.on('Network.loadingFailed',event=>invalidate(event.requestId));
 session.on('Network.loadingFinished',event=>{
  const current=pending.get(event.requestId);
  if(!current)return;
  if(!current.responseSeen||current.finished){finish(current,readFailed);return;}
  current.finished=true;
  const encoded=event.encodedDataLength;
  // Encoded bytes are bounded separately. Only decoded dataLength authorizes the body read.
  if(!current.decodedSize||current.decodedLength!==undefined&&current.decodedSize!==current.decodedLength||
     !Number.isSafeInteger(encoded)||encoded<=0||encoded>issuerDecisionBodyLimit||
     current.encodedLength!==undefined&&encoded<current.encodedLength){finish(current,invalidSize);return;}
  void billingBounded({signal,deadline:Date.now()+1000},async limits=>{
   if(!await current.release.promise||pending.get(current.id)!==current)return;
   billingCheck(limits);
   const value=await session.send('Network.getResponseBody',{requestId:current.id});
   billingCheck(limits);
   if(pending.get(current.id)!==current)return;
   const size=current.decodedSize;
   if(typeof value.body!=='string'||typeof value.base64Encoded!=='boolean'||
      (value.base64Encoded?value.body.length>Math.ceil(size/3)*4:Buffer.byteLength(value.body,'utf8')!==size)){
    finish(current,invalidSize);return;
   }
   const bytes=Buffer.from(value.body,value.base64Encoded?'base64':'utf8');
   if(bytes.length!==size){finish(current,invalidSize);return;}
   // Decoded CDP text can hide lossy decoding or transcoding at the same byte count.
   // A non-ASCII script needs exact base64 bytes and an explicit UTF-8 charset.
   if(bytes.toString(value.base64Encoded?'base64':'utf8')!==value.body||
      current.resource==='Script'&&(!value.base64Encoded||current.asciiOnly)&&bytes.some(byte=>byte>0x7f)){
    finish(current,readFailed);return;
   }
   finish(current,bytes);
  }).catch(()=>finish(current,readFailed));
 });
 return {
  async enable(){
   await session.send('Network.configureDurableMessages',buffers);
   await session.send('Network.enable',buffers);
  },
  has:(id:string)=>pending.has(id),
  invalidate,close,
  duplicatePause(fetchId:string,networkId:string|undefined){
   let duplicate=false;
   for(const current of pending.values()){
    if(current.fetchId!==fetchId&&current.id!==networkId)continue;
    finish(current,readFailed);
    duplicate=true;
   }
   return duplicate;
  },
  observe(source:BodySource){
   const {resource='Document',decodedLength}=source;
   if(closed||signal?.aborted||pending.has(source.id)||
      decodedLength!==undefined&&(!Number.isSafeInteger(decodedLength)||decodedLength<1||decodedLength>issuerDecisionBodyLimit))
    throw Error('billing_BROWSER');
   for(const current of pending.values()){
    if(current.resource===resource||current.fetchId===source.fetchId)throw Error('billing_BROWSER');
   }
   const current:PendingBody={...source,resource,responseSeen:false,finished:false,decodedSize:0,
    release:Promise.withResolvers<boolean>(),result:Promise.withResolvers<IssuerDecisionBody>(),
    timer:setTimeout(()=>finish(current,readFailed),2000)};
   pending.set(current.id,current);
   return {body:current.result.promise,released:()=>current.release.resolve(true)};
  },
 };
}
