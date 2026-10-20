import {resolve} from 'node:path';
const REQUIRED=['ENABLED','ALLOW_TEST_OPERATIONS','MANUAL_REFUND'].map(v=>'PAYMENT_STRIPE_SANDBOX_'+v);
const EXCLUDED=['FULL_RUN','OWNED_RUN','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT'].map(v=>'PAYMENT_STRIPE_SANDBOX_'+v);
const OLD_RUNS=new Set(['031b5ae7-6603-4339-b1b3-77231291ff34','2f3f49b1-df07-41b1-b978-bc884b390813','6f684b69-32e6-429f-a34c-e4f69ba2ce18']);
function descriptors(value){if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw null;return Object.getOwnPropertyDescriptors(value);}
function data(d,key){const p=d[key];if(!p||!p.enumerable||!Object.hasOwn(p,'value'))throw null;return p.value;}
/** Inspect data descriptors only; no operator accessors or mutable flag references survive. */
export function manualRefundOptIn(env){
  try{const d=descriptors(env),values={};for(const key of [...REQUIRED,...EXCLUDED,'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE']){const p=d[key];if(p&&(!p.enumerable||!Object.hasOwn(p,'value')))return false;values[key]=p?.value;}
    return REQUIRED.every(k=>values[k]==='true')&&EXCLUDED.every(k=>[undefined,'','false'].includes(values[k]))&&[undefined,''].includes(values.DEBUG)&&[undefined,''].includes(values.NODE_DEBUG)&&[undefined,'','0'].includes(values.PWDEBUG)&&[undefined,'','off'].includes(values.PW_TRACE);
  }catch{return false;}
}
export function validateManualRefundOptions(value){
  try{const d=descriptors(value),keys=['env','runId','expectedSource','daemon'];if(Reflect.ownKeys(d).length!==keys.length)return null;const input=Object.fromEntries(keys.map(k=>[k,data(d,k)]));
    if(!manualRefundOptIn(input.env)||typeof input.runId!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(input.runId)||OLD_RUNS.has(input.runId)||typeof input.expectedSource!=='string'||!/^[0-9a-f]{40}$/.test(input.expectedSource)||input.expectedSource==='f0620e40004162d2b3eededa60512f50a492f4cc')return null;
    if(typeof input.daemon!=='string'||/[\s\x00-\x1f\x7f%]/.test(input.daemon))return null;const url=new URL(input.daemon);if(url.protocol!=='unix:'||url.host||url.username||url.password||url.search||url.hash||url.pathname.length<2||resolve(url.pathname)!==url.pathname||url.href!==input.daemon||input.daemon!=='unix://'+url.pathname)return null;
    return Object.freeze({...input,env:Object.freeze(Object.fromEntries(REQUIRED.map(k=>[k,'true'])))});
  }catch{return null;}
}
