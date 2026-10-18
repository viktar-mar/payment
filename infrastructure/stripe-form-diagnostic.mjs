import {buildFormDiagnosticFrame,validateFormDiagnosticSample} from './stripe-form-diagnostic-schema.mjs';
import {formDiagnosticOptIn} from './stripe-sandbox-owned.mjs';

const OUTCOMES=['OBSERVING','FAILED','ABORTED','DEADLINE_REACHED','INSTRUMENTATION_UNAVAILABLE','UNEXPECTED_ACTIVITY'];
function browserResult(value) {
  try {
    const descriptors=Object.getOwnPropertyDescriptors(value),names=Reflect.ownKeys(descriptors);
    const keys=['samples','outcome','browserClosed','contextClosed'];
    if(names.length!==keys.length||names.some(key=>typeof key!=='string'||!keys.includes(key)))return null;
    const data={};
    for(const key of keys){if(!Object.hasOwn(descriptors[key],'value'))return null;data[key]=descriptors[key].value;}
    if(!OUTCOMES.includes(data.outcome)||typeof data.browserClosed!=='boolean'||typeof data.contextClosed!=='boolean'
      ||!Array.isArray(data.samples)||data.samples.length>2)return null;
    const samples=[];
    for(let n=0;n<data.samples.length;n++){
      const descriptor=Object.getOwnPropertyDescriptor(data.samples,String(n));
      if(!descriptor||!Object.hasOwn(descriptor,'value'))return null;
      const sample=validateFormDiagnosticSample(descriptor.value);if(!sample)return null;samples.push(sample);
    }
    return {...data,samples};
  }catch{return null;}
}

/** Separate prepare-only lifecycle; never imports/calls full acceptance or CLI. */
export async function runFormLoadDiagnostic(options,originalLimits,dependencies={}) {
  let runtime,outcome='FAILED',samples=[],browserClosed=false,contextClosed=false,noConfirmation=false,runtimeClosed=false;
  let limits,cleanupDeadline;
  try {
    if(!formDiagnosticOptIn(options?.env))throw null;
    const {deadline,signal}=originalLimits;
    if(!Number.isSafeInteger(deadline)||Date.now()>=deadline||!signal||typeof signal.aborted!=='boolean')throw null;
    limits=Object.freeze({deadline,signal});cleanupDeadline=deadline+120000;
    if(signal.aborted){outcome='ABORTED';throw null;}
    const createRuntime=dependencies.createRuntime??(await import('./stripe-form-diagnostic-runtime.mjs')).createFormDiagnosticRuntime;
    runtime=await createRuntime(options);
    const handoff=await runtime.prepare(limits);
    const observation=browserResult(await dependencies.runBrowser(handoff,limits));
    if(!observation){outcome='INSTRUMENTATION_UNAVAILABLE';throw null;}
    ({samples,outcome,browserClosed,contextClosed}=observation);
  }catch {
    if(limits?.signal.aborted)outcome='ABORTED';
    else if(limits&&Date.now()>=limits.deadline)outcome='DEADLINE_REACHED';
  }finally {
    if(outcome==='OBSERVING'&&limits){
      if(limits.signal.aborted)outcome='ABORTED';
      else if(Date.now()>=limits.deadline)outcome='DEADLINE_REACHED';
    }
    if(runtime){
      // Exactly one final read, only under the original active authority. A
      // failed read grants no write, retry, reconciliation or fresh deadline.
      if(limits&&!limits.signal.aborted&&Date.now()<limits.deadline){
        try {noConfirmation=await runtime.verifyNoConfirmation(limits)===true;}catch{}
        const controlCause=limits.signal.aborted?'ABORTED':Date.now()>=limits.deadline?'DEADLINE_REACHED':null;
        if(controlCause)noConfirmation=false;
        if(outcome==='OBSERVING'){
          if(controlCause)outcome=controlCause;
          else if(!noConfirmation)outcome='UNEXPECTED_ACTIVITY';
        }
      }
      try {runtimeClosed=await runtime.close(Object.freeze({deadline:cleanupDeadline}))===true;}catch{}
    }
  }
  const proofs={no_confirmation:noConfirmation,browser_closed:browserClosed,context_closed:contextClosed,runtime_closed:runtimeClosed};
  // A prior checkpoint retains its historical stage. Only the final frame
  // carries the settled lifecycle outcome, which alone controls success.
  const observations=samples.length?samples:[null];
  const frames=Object.freeze(observations.map((sample,index)=>buildFormDiagnosticFrame(sample,index===observations.length-1?outcome:'OBSERVING',proofs)));
  const last=frames.at(-1);
  const success=last&&['FORM_READY_BEFORE_15S','FORM_READY_AFTER_15S'].includes(last.result)
    &&Object.values(proofs).every(value=>value===true);
  return Object.freeze({frames,exitCode:success?0:1});
}
