import type {createOwnedBillingDeclineRetry} from '../../../../infrastructure/stripe-billing-acceptance.mjs';

type Owner=Awaited<ReturnType<typeof createOwnedBillingDeclineRetry>>;
const phases=new Set(['NEW','PREFLIGHT','BOOTSTRAP','PREPARE','CLI','LOGIN','BINDING','LISTENER','START','FIXTURE','PROVISION','BROWSER',
 'RETRY_DISPATCH','RETRY_ACK','RETRY_EVIDENCE','COMPLETE']);
const codes=new Set([...phases,'LIMITS','RETRY_ORDER','ABORTED','FAILED','CLEANUP']);
const prefix='owned_initial_decline_retry_terminal ';
const closeFailed=prefix+'phase=CLOSE code=CLOSE cleanup=UNCONFIRMED';
export const retryAnnotation='owned-decline-retry-terminal';

export function retryTerminalLine(raw:unknown):string|null{
 if(typeof raw!=='string'||raw.length>140)return null;
 if(raw===closeFailed)return raw;
 const parts=/^owned_initial_decline_retry_terminal phase=([A-Z_]+) code=([A-Z_]+) cleanup=(CONFIRMED|UNCONFIRMED)$/.exec(raw);
 if(!parts||parts[0]!==raw||!phases.has(parts[1]!)||!codes.has(parts[2]!))return null;
 return raw;
}
function formatTerminal(raw:unknown,failedClose:boolean):string|null{
 if(!raw||typeof raw!=='object')return null;
 const value=(k:string):unknown=>Object.getOwnPropertyDescriptor(raw,k)?.value;
 if(value('scope')!=='INITIAL_DECLINE_RETRY'||value('full5bAccepted')!==false||value('initialAccepted')!==false||
    value('declineRetryAccepted')!==false||(!failedClose&&value('status')!=='CLOSED')||typeof value('cleanupConfirmed')!=='boolean')return null;
 const phase=value('phase'),code=value('code');
 if(typeof phase!=='string'||typeof code!=='string'||!phases.has(phase)||!codes.has(code))return null;
 return prefix+'phase='+phase+' code='+code+' cleanup='+(!failedClose&&value('cleanupConfirmed')?'CONFIRMED':'UNCONFIRMED');
}
function accepted(value:Awaited<ReturnType<Owner['close']>>){
 return value.scope==='INITIAL_DECLINE_RETRY'&&value.status==='CLOSED'&&value.phase==='COMPLETE'&&value.code===null&&
  value.initialAccepted===true&&value.declineRetryAccepted===true&&value.cleanupConfirmed===true&&value.full5bAccepted===false;
}

/** Worker-side boundary. Only this owner supplies the attempt's terminal annotation. */
export async function runDeclineRetryCase(owner:Owner,journey:Parameters<Owner['run']>[0],annotations:{type:string;description?:string}[]){
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
 if(line)annotations.push({type:retryAnnotation,description:line});
 if(!passed)throw Error('billing_NOT_ACCEPTED');
}
