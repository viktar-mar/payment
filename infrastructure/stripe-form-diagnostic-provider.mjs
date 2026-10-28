const fail=()=>{throw new Error('stripe_form_diagnostic_PROOF_UNCONFIRMED');};
const ACCOUNT='acct_1UPiMpELswAd8tpR',VERSION='2026-09-30.endive';

/** Private, size/deadline bounded transport. No retries, redirects or response logging. */
export async function readFormDiagnosticJson(url,settings,limits,dependencies={},settlement=createFormDiagnosticSettlement(dependencies)) {
  const now=dependencies.now??Date.now,fetcher=dependencies.fetch??globalThis.fetch;
  const setTimer=dependencies.setTimeout??setTimeout,clearTimer=dependencies.clearTimeout??clearTimeout;
  if(!Number.isSafeInteger(limits?.deadline)||limits.deadline<=now()||limits.signal?.aborted)fail();
  const controller=new AbortController(),abort=()=>controller.abort(),deadline=Math.min(limits.deadline,now()+10000);
  const check=()=>{if(controller.signal.aborted||now()>=deadline)fail();};
  limits.signal?.addEventListener('abort',abort,{once:true});
  settlement.bind(limits.deadline);
  const timer=setTimer(abort,Math.max(1,deadline-now())),context={deadline,signal:controller.signal};let response;
  const wait=body=>settlement.call(context,body);
  try {
    response=await settlement.call(context,()=>fetcher(url,{...settings,redirect:'error',signal:controller.signal}),value=>value.body?.cancel());
    if(response.redirected||response.status!==(settings.expected??200))fail();
    const chunks=[];let size=0;const reader=response.body?.getReader();
    if(reader){settlement.replaceDisposer(response,async()=>{await reader.cancel();reader.releaseLock();});
      for(;;){const part=await wait(()=>reader.read());if(part.done)break;size+=part.value.byteLength;if(size>65536)fail();chunks.push(Buffer.from(part.value));}}
    check();const bytes=Buffer.concat(chunks),value=bytes.length?JSON.parse(new TextDecoder('utf8',{fatal:true}).decode(bytes)):null;
    if(settings.expected===204&&value!==null)fail();return {value,headers:response.headers};
  }catch{fail();}finally{
    if(response){const closing=settlement.release(response);closing.catch(()=>{});if(!controller.signal.aborted&&now()<deadline)try{await settlement.wait(context,()=>closing);}catch{}}
    clearTimer(timer);limits.signal?.removeEventListener('abort',abort);
  }
}

export function createFormDiagnosticProvider(credentials,dependencies={}) {
  let secret,publishable;
  try {secret=credentials.secretKey;publishable=credentials.publishableKey;
    if(!/^(?:sk|rk)_test_[A-Za-z0-9]+$/.test(secret)||!/^pk_test_[A-Za-z0-9]+$/.test(publishable))fail();
  }catch{fail();}
  let originalDeadline,bound=false,intentReads=0,bindingReads=0;
  const settlement=createFormDiagnosticSettlement(dependencies);
  async function read(path,limits) {
    if(originalDeadline===undefined)originalDeadline=limits?.deadline;
    if(limits?.deadline!==originalDeadline)fail();
    const {value,headers}=await readFormDiagnosticJson('https://api.stripe.com/v1/'+path,
      {method:'GET',headers:{Accept:'application/json',Authorization:'Bearer '+secret}},limits,dependencies,settlement);
    if(headers.get('stripe-version')!==VERSION||value?.livemode===true)fail();return value;
  }
  return Object.freeze({
    close:limits=>{if(!settlement.close(limits))fail();return true;},
    verifyBinding:async limits=>{
      if(bindingReads++!==0)fail();
      const v=await read('account',limits);
      if(v?.id!==ACCOUNT||v.object!=='account')fail();bound=true;return true;
    },
    verifyIntent:async(reference,limits)=>{
      if(!bound||intentReads++>=2||typeof reference!=='string'||!/^pi_[A-Za-z0-9]{1,249}$/.test(reference))fail();
      const v=await read('payment_intents/'+reference,limits);
      if(v?.id!==reference||v.object!=='payment_intent'||v.livemode!==false||v.status!=='requires_payment_method'
        ||v.amount!==2000||v.currency!=='eur'||v.amount_received!==0
        ||['payment_method','latest_charge','customer','setup_future_usage','on_behalf_of','transfer_data'].some(k=>v[k]!==null))fail();return true;
    },
  });
}
import {createFormDiagnosticSettlement} from './stripe-form-diagnostic-settlement.mjs';
