export const billingError=code=>new Error('billing_'+code);
export function billingCheck(limits) {
 if(!limits||(limits.signal!==undefined&&!(limits.signal instanceof AbortSignal))||!Number.isSafeInteger(limits.deadline)||limits.deadline<=Date.now()||limits.signal?.aborted)throw billingError('ABORTED');
}
/** Every operation receives the cancellation that wins its timeout race. Late values never regain authority. */
export async function billingBounded(parent,body,maximum=15000) {
 billingCheck(parent);const controller=new AbortController(),abort=()=>controller.abort();
 parent.signal?.addEventListener('abort',abort,{once:true});if(parent.signal?.aborted)abort();
 const limits={deadline:Math.min(parent.deadline,Date.now()+maximum),signal:controller.signal};
 let rejectAbort;const stopped=new Promise((_,reject)=>{rejectAbort=()=>reject(billingError('ABORTED'));controller.signal.addEventListener('abort',rejectAbort,{once:true});});
 const timer=setTimeout(abort,Math.max(1,limits.deadline-Date.now()));
 try {const value=await Promise.race([Promise.resolve().then(()=>{billingCheck(limits);return body(limits);}),stopped]);billingCheck(limits);return value;}
 finally{clearTimeout(timer);parent.signal?.removeEventListener('abort',abort);controller.signal.removeEventListener('abort',rejectAbort);controller.abort();}
}
