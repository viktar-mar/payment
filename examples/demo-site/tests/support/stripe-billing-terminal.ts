import type {BillingLimits} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
import {isAcceptanceBrowserDiagnostic} from '../../../../infrastructure/stripe-billing-first-payment-browser-diagnostic.mjs';

export function billingTerminal(scope:string,field:string,marker:string,annotation:string,casePhases:string[],orderCode:string,caseCode?:(code:string)=>boolean){
const phases=new Set(['NEW','PREFLIGHT','BOOTSTRAP','PREPARE','CLI','LOGIN','BINDING','LISTENER','START','FIXTURE','PROVISION','BROWSER','COMPLETE',...casePhases]);
const codes=new Set([...phases,'LIMITS',orderCode,'ABORTED','FAILED','CLEANUP']);
const knownCode=(code:string)=>codes.has(code)||isAcceptanceBrowserDiagnostic(code)||caseCode?.(code)===true;
const prefix=marker+'_terminal ';
const closeFailed=prefix+'phase=CLOSE code=CLOSE cleanup=UNCONFIRMED';
function parse(raw:unknown):string|null{
 if(typeof raw!=='string'||raw.length>140||!raw.startsWith(prefix))return null;
 if(raw===closeFailed)return raw;
 const fields=raw.slice(prefix.length),parts=/^phase=([A-Z_]+) code=([A-Z_]+) cleanup=(CONFIRMED|UNCONFIRMED)$/.exec(fields);
 if(!parts||parts[0]!==fields||!phases.has(parts[1]!)||!knownCode(parts[2]!))return null;
 return raw;
}
function formatTerminal(raw:unknown,failedClose:boolean):string|null{
 if(!raw||typeof raw!=='object')return null;
 const value=(k:string):unknown=>Object.getOwnPropertyDescriptor(raw,k)?.value;
 if(value('scope')!==scope||value('full5bAccepted')!==false||value('initialAccepted')!==false||
    value(field)!==false||(!failedClose&&value('status')!=='CLOSED')||typeof value('cleanupConfirmed')!=='boolean')return null;
 const phase=value('phase'),code=value('code');
 if(typeof phase!=='string'||typeof code!=='string'||!phases.has(phase)||!knownCode(code))return null;
 return prefix+'phase='+phase+' code='+code+' cleanup='+(!failedClose&&value('cleanupConfirmed')?'CONFIRMED':'UNCONFIRMED');
}
function accepted(raw:unknown){
 if(!raw || typeof raw!=='object')return false;
 const value=(key:string)=>Object.getOwnPropertyDescriptor(raw,key)?.value;
 return value('scope')===scope && value('status')==='CLOSED' && value('phase')==='COMPLETE' && value('code')===null &&
  value('initialAccepted')===true && value(field)===true && value('cleanupConfirmed')===true && value('full5bAccepted')===false;
}
/** Worker-side boundary. Only this owner supplies the attempt's terminal annotation. */
async function run<P>(owner:{run(journey:(port:P,limits:BillingLimits)=>Promise<unknown>,limits:BillingLimits):Promise<unknown>;close():Promise<unknown>;resources():unknown},journey:(port:P,limits:BillingLimits)=>Promise<unknown>,annotations:{type:string;description?:string}[]){
 const controller=new AbortController();
 let passed=false,line:string|null=null;
 try{passed=accepted(await owner.run(journey,{deadline:Date.now()+2700000,signal:controller.signal}));}
 catch{passed=false;}
 finally{
  controller.abort();
  try{
   const terminal=await owner.close();
   passed=passed&&accepted(terminal);
   if(!passed)line=formatTerminal(terminal,false);
  }catch{
   passed=false;line=closeFailed;
   try{line=formatTerminal(owner.resources(),true)??closeFailed;}catch{}
  }
 }
 if(line)annotations.push({type:annotation,description:line});
 if(!passed)throw Error('billing_NOT_ACCEPTED');
}
return {parse,run};
}
