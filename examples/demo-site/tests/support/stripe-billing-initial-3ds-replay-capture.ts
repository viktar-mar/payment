import {randomUUID} from 'node:crypto';
import {closeSync,constants,fstatSync,fsyncSync,linkSync,lstatSync,openSync,realpathSync,unlinkSync,writeFileSync,type Stats} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
// @ts-ignore The Node suite imports the same anonymizer as the dedicated worker.
import {anonymizeIssuerDecisionReplay,describeIssuerDecisionReplay} from './stripe-billing-initial-3ds-replay-fixture.ts';

const evidenceRoot=fileURLToPath(new URL('../../../../test-results/stripe-sandbox/',import.meta.url));
const filename='issuer-replay-fixture.private.json';
const recordLimit=65536;
const sameFile=(first:Stats,second:Stats)=>first.dev===second.dev&&first.ino===second.ino;
const privateDirectory=(s:Stats)=>s.isDirectory()&&s.uid===process.getuid?.()&&(s.mode&0o7777)===0o700;
const privateFile=(s:Stats)=>s.isFile()&&s.uid===process.getuid?.()&&(s.mode&0o7777)===0o600&&s.nlink===1;

/** Arm one private record for the dedicated 3DS project and its exact run ID. */
export function prepareIssuerReplayCapture(setting:unknown,runId:unknown,root=evidenceRoot){
 if(setting===undefined)return;
 if(typeof runId!=='string'||!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(runId)||setting!==runId){
  throw Error('billing_OPT_IN_REQUIRED');
 }
 const directory=join(root,runId),lock=join(directory,'owner.lock');
 let used=false;
 return (source:Parameters<typeof anonymizeIssuerDecisionReplay>[0]):void=>{
  if(used)return;
  used=true;
  try{
   const parent=lstatSync(directory),owner=lstatSync(lock);
   if(!privateDirectory(parent)||realpathSync(directory)!==directory||!privateFile(owner))return;
   const fixture=anonymizeIssuerDecisionReplay(source);
   let contents=fixture?JSON.stringify({status:'CAPTURED',fixture})+'\n':'';
   if(!contents||Buffer.byteLength(contents)>recordLimit){
    const shape=describeIssuerDecisionReplay(source);
    contents=JSON.stringify(shape?{status:'STRUCTURAL_ONLY',shape}:{status:'UNSUPPORTED'})+'\n';
   }
   if(Buffer.byteLength(contents)>recordLimit)return;
   const temporary=join(directory,'.'+filename+'.'+randomUUID());
   const fd=openSync(temporary,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);
   let created:Stats|undefined;
   try{
    created=fstatSync(fd);
    if(!privateFile(created))return;
    writeFileSync(fd,contents,'utf8');
    fsyncSync(fd);
    const currentParent=lstatSync(directory),currentOwner=lstatSync(lock),staged=lstatSync(temporary);
    if(!privateDirectory(currentParent)||!sameFile(parent,currentParent)||realpathSync(directory)!==directory||
       !privateFile(currentOwner)||!sameFile(owner,currentOwner)||!privateFile(staged)||!sameFile(created,staged))return;
    // The final name is published exclusively only after the complete record is available.
    linkSync(temporary,join(directory,filename));
   }finally{
    try{
     if(created&&sameFile(created,lstatSync(temporary)))unlinkSync(temporary);
    }catch{}
    closeSync(fd);
   }
  }catch{/* An unavailable diagnostic never replaces the original failure. */}
 };
}
