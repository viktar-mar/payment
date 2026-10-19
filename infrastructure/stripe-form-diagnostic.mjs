import {buildFormDiagnosticFrame,validateFormDiagnosticObservation} from './stripe-form-diagnostic-schema.mjs';
import {unavailableFormEnvironmentEvidence} from './stripe-form-diagnostic-environment.mjs';
import {formDiagnosticOptIn} from './stripe-sandbox-owned.mjs';

const OUTCOMES=['OBSERVING','FAILED','ABORTED','DEADLINE_REACHED','INSTRUMENTATION_UNAVAILABLE','UNEXPECTED_ACTIVITY'];
const END_OUTCOMES={FORM_READY:['OBSERVING','FAILED'],SDK_TERMINAL:['OBSERVING','FAILED'],LOCAL_LIMIT:['FAILED'],
  ABORTED:['ABORTED'],ACTIVE_DEADLINE:['DEADLINE_REACHED'],INSTRUMENTATION_UNAVAILABLE:['OBSERVING','INSTRUMENTATION_UNAVAILABLE','FAILED'],
  BROWSER_OPERATION_FAILED:['FAILED']};
function browserResult(value) {
  try {
    if(value===null||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))return null;
    const descriptors=Object.getOwnPropertyDescriptors(value),names=Reflect.ownKeys(descriptors);
    const keys=['observations','outcome','browserClosed','contextClosed','observationEnd'];
    if(names.length!==keys.length||names.some(key=>typeof key!=='string'||!keys.includes(key)))return null;
    const data={};
    for(const key of keys){if(!Object.hasOwn(descriptors[key],'value')||!descriptors[key].enumerable)return null;data[key]=descriptors[key].value;}
    if(!OUTCOMES.includes(data.outcome)||typeof data.observationEnd!=='string'||!Object.hasOwn(END_OUTCOMES,data.observationEnd)
      ||!END_OUTCOMES[data.observationEnd].includes(data.outcome)||typeof data.browserClosed!=='boolean'||typeof data.contextClosed!=='boolean'
      ||!Array.isArray(data.observations))return null;
    const entries=Object.getOwnPropertyDescriptors(data.observations),length=entries.length?.value;
    if(!Number.isInteger(length)||length<0||length>2||Reflect.ownKeys(entries).length!==length+1)return null;
    const observations=[];
    for(let n=0;n<length;n++){
      const descriptor=entries[String(n)];
      if(!descriptor||!Object.hasOwn(descriptor,'value')||!descriptor.enumerable)return null;
      const observation=validateFormDiagnosticObservation(descriptor.value);if(!observation)return null;observations.push(observation);
    }
    return {...data,observations};
  }catch{return null;}
}

/** Separate prepare-only lifecycle; never imports/calls full acceptance or CLI. */
export async function runFormLoadDiagnostic(options,originalLimits,dependencies={}) {
  let runtime,outcome='FAILED',observations=[],browserClosed=false,contextClosed=false,noConfirmation=false,runtimeClosed=false;
  let observationEnd='BROWSER_NOT_STARTED';
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
    observationEnd='BROWSER_OPERATION_FAILED';
    const observation=browserResult(await dependencies.runBrowser(handoff,limits));
    if(!observation){outcome='INSTRUMENTATION_UNAVAILABLE';observationEnd='INSTRUMENTATION_UNAVAILABLE';throw null;}
    ({observations,outcome,browserClosed,contextClosed,observationEnd}=observation);
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
  if(outcome==='OBSERVING'&&(!browserClosed||!contextClosed||!runtimeClosed))outcome='FAILED';
  // A prior checkpoint retains its historical stage. Only the final frame
  // carries the settled lifecycle outcome, which alone controls success.
  if(!observations.length)observations=[{sample:null,environment:unavailableFormEnvironmentEvidence()}];
  const frames=Object.freeze(observations.map(({sample,environment},index)=>buildFormDiagnosticFrame(sample,index===observations.length-1?outcome:'OBSERVING',proofs,environment,index===observations.length-1?observationEnd:'OBSERVING')));
  const last=frames.at(-1);
  const success=last&&['FORM_READY_BEFORE_15S','FORM_READY_AFTER_15S'].includes(last.result)
    &&Object.values(proofs).every(value=>value===true);
  return Object.freeze({frames,exitCode:success?0:1});
}
