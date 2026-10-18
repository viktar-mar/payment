const failure=()=>new Error('stripe_form_diagnostic_IO_UNCONFIRMED');

/** Private ownership of promises and acquired descriptors/bodies. Abort is not settlement. */
export function createFormDiagnosticSettlement(dependencies={}) {
  const now=dependencies.now??Date.now,setTimer=dependencies.setTimeout??setTimeout,clearTimer=dependencies.clearTimeout??clearTimeout;
  const active=new Set(),resources=new Map();let cleanupCeiling=null,uncertain=false,closing=false;
  const expired=context=>context.signal?.aborted||now()>=context.deadline;
  function track(promise) {
    active.add(promise);promise.then(()=>active.delete(promise),()=>active.delete(promise));return promise;
  }
  function bind(deadline) {
    if(!Number.isSafeInteger(deadline)||(cleanupCeiling!==null&&cleanupCeiling!==deadline+120000))throw failure();
    cleanupCeiling=deadline+120000;
  }
  function release(value) {
    const resource=resources.get(value);if(!resource){uncertain=true;return Promise.reject(failure());}
    if(resource.closing)return resource.closing;
    resource.closing=track(Promise.resolve().then(()=>{
      if(cleanupCeiling===null||now()>=cleanupCeiling)throw failure();return resource.dispose(value);
    }).then(()=>{resources.delete(value);if(now()>=cleanupCeiling)uncertain=true;},()=>{uncertain=true;throw failure();}));
    // Late acquisition cleanup remains owned even after the caller has received its fixed failure.
    resource.closing.catch(()=>{});return resource.closing;
  }
  async function wait(context,body,onAbandon=()=>{}) {
    if(expired(context))throw failure();let timer,abort,settled=false;
    const abandon=()=>{if(!settled){uncertain=true;onAbandon();}};
    try {
      const stopped=new Promise((_,reject)=>{abort=()=>{abandon();reject(failure());};
        context.signal?.addEventListener('abort',abort,{once:true});timer=setTimer(abort,Math.max(1,context.deadline-now()));if(expired(context))abort();});
      const value=await Promise.race([Promise.resolve().then(()=>{if(expired(context))throw failure();return body();}),stopped]);
      if(expired(context)){abandon();throw failure();}settled=true;return value;
    }finally{clearTimer(timer);context.signal?.removeEventListener('abort',abort);}
  }
  async function call(context,body,dispose) {
    if(closing||expired(context))throw failure();let abandoned=false;
    const operation=track(Promise.resolve().then(()=>{if(expired(context))throw failure();return body();}).then(value=>{
      if(dispose){if(resources.has(value))throw failure();resources.set(value,{dispose,closing:null});}
      if(abandoned||closing||expired(context)){
        uncertain=true;if(dispose)release(value).catch(()=>{});
      }
      return value;
    }));
    return wait(context,()=>operation,()=>{abandoned=true;});
  }
  function replaceDisposer(value,dispose) {
    const resource=resources.get(value);if(!resource||resource.closing)throw failure();resource.dispose=dispose;
  }
  function close(limits) {
    if(cleanupCeiling===null||!Number.isSafeInteger(limits?.deadline)||limits.deadline>cleanupCeiling||expired(limits))throw failure();
    closing=true;
    for(const value of resources.keys())release(value).catch(()=>{});
    return !uncertain&&active.size===0&&resources.size===0;
  }
  return Object.freeze({bind,wait,call,release,replaceDisposer,close});
}
