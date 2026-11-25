import {createHmac,timingSafeEqual} from 'node:crypto';
import {closeSync,constants,fstatSync,lstatSync,openSync,readSync,realpathSync,type Stats} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {isBrowserProviderOriginWitness} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';

const reviewed=Object.freeze({
 runId:'52ac58a2-787f-4213-9c56-9d9ed1334961',
 source:'68ae458b59a6b01a278cefa1fc34e509b25a8f29',
 hmac:'5b9fc20d231a69967e5140c30b9f7d304ce88b24fdfe43457a295d7894a4a301',
});
type Review={runId:string;source:string;hmac:string};
const sameFile=(a:Stats,b:Stats)=>a.dev===b.dev && a.ino===b.ino;

function readPrivate(directory:string,name:string,maximum:number):Buffer|undefined {
 const uid=process.getuid?.();
 const privateDirectory=(s:Stats)=>s.uid===uid && s.isDirectory() && (s.mode&0o7777)===0o700;
 const privateFile=(s:Stats)=>s.uid===uid && s.isFile() && s.nlink===1 && (s.mode&0o7777)===0o600 && s.size>0 && s.size<=maximum;
 const parent=lstatSync(directory),path=join(directory,name);
 if(!privateDirectory(parent) || realpathSync(directory)!==directory)return;
 const named=lstatSync(path);
 if(!privateFile(named))return;
 const fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW|constants.O_NONBLOCK);
 let bytes:Buffer|undefined,valid=false;
 try{
  const opened=fstatSync(fd);
  if(!privateFile(opened) || !sameFile(named,opened))return;
  bytes=Buffer.alloc(opened.size);
  if(readSync(fd,bytes,0,bytes.length,0)!==bytes.length)return;
  const current=lstatSync(path),currentParent=lstatSync(directory);
  valid=privateFile(current) && sameFile(opened,current) && current.size===opened.size && current.mtimeMs===opened.mtimeMs &&
   privateDirectory(currentParent) && sameFile(parent,currentParent) && realpathSync(directory)===directory;
  if(valid)return bytes;
 }finally{
  if(!valid)bytes?.fill(0);
  closeSync(fd);
 }
}

export function readPinnedInitial3dsIssuerOrigin(evidenceDirectory:string,policyDirectory:string,review:Review):string|undefined {
 let key:Buffer|undefined,bytes:Buffer|undefined;
 try{
  if(!/^[a-f0-9]{64}$/.test(review.hmac))return;
  key=readPrivate(policyDirectory,'policy.key',32);
  if(key?.length!==32)return;
  bytes=readPrivate(evidenceDirectory,'refused-provider-origin.private.json',4096);
  if(!bytes||!timingSafeEqual(createHmac('sha256',key).update(bytes).digest(),Buffer.from(review.hmac,'hex')))return;
  const witness:unknown=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
  if(isBrowserProviderOriginWitness(witness) && witness.checkpoint==='BROWSER_PROVIDER_ORIGIN_STRIPE_COM' &&
   witness.runId===review.runId && witness.expectedSource===review.source && witness.issuerRelation==='ISSUER_TRANSACTION_MATCH')return witness.origin;
 }catch{return;}
 finally{key?.fill(0);bytes?.fill(0);}
}

export function loadPinnedInitial3dsIssuerOrigin():string|undefined {
 const root=new URL('../../../../',import.meta.url);
 return readPinnedInitial3dsIssuerOrigin(
  fileURLToPath(new URL('test-results/stripe-sandbox/'+reviewed.runId,root)),
  fileURLToPath(new URL('.superpowers/initial-3ds-issuer-origin',root)),reviewed);
}
