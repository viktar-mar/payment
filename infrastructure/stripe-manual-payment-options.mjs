import {validateFormDiagnosticTopology} from './stripe-manual-form-options.mjs';
const REQUIRED=['PAYMENT_STRIPE_SANDBOX_ENABLED','PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS','PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT'];
const EXCLUDED=['PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER','PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND','PAYMENT_STRIPE_SANDBOX_FULL_RUN','PAYMENT_STRIPE_SANDBOX_OWNED_RUN','PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY'];
function descriptors(value){if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw null;return Object.getOwnPropertyDescriptors(value);}
function data(d,key){const p=d[key];if(!p||!p.enumerable||!Object.hasOwn(p,'value'))throw null;return p.value;}
export function manualPaymentOptIn(env){
  try{const d=descriptors(env),flags={};
    for(const key of [...REQUIRED,...EXCLUDED,'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE']){const p=d[key];if(p&&(!p.enumerable||!Object.hasOwn(p,'value')))return false;flags[key]=p?.value;}
    return REQUIRED.every(key=>flags[key]==='true')&&EXCLUDED.every(key=>[undefined,'','false'].includes(flags[key]))
      &&[undefined,''].includes(flags.DEBUG)&&[undefined,''].includes(flags.NODE_DEBUG)&&[undefined,'','0'].includes(flags.PWDEBUG)&&[undefined,'','off'].includes(flags.PW_TRACE);
  }catch{return false;}
}
export function validateManualPaymentOptions(value){
  try{const d=descriptors(value),env=data(d,'env');if(!manualPaymentOptIn(env))return null;
    const keys=['env','runId','expectedSource','daemon','ports','manualPort'];if(Reflect.ownKeys(d).length!==keys.length)return null;
    const input=Object.fromEntries(keys.map(key=>[key,data(d,key)])),base=validateFormDiagnosticTopology(input),manualPort=input.manualPort;
    if(!base||!Number.isInteger(manualPort)||manualPort<1024||manualPort>65535||Object.values(base.ports).includes(manualPort))return null;
    return Object.freeze({...base,env:Object.freeze(Object.fromEntries(REQUIRED.map(key=>[key,'true']))),manualPort});
  }catch{return null;}
}
