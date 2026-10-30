import {resolve} from 'node:path';
import {validateFormDiagnosticTopology} from './stripe-manual-form-options.mjs';
const FLAG='PAYMENT_STRIPE_BILLING_ACCEPTANCE';
const EXCLUDED=['ALLOW_TEST_OPERATIONS','FULL_RUN','OWNED_RUN','REMAINING_ONLY','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT','MANUAL_3DS','MANUAL_REFUND','MANUAL_REFUND_REMAINDER'].map(name=>'PAYMENT_STRIPE_SANDBOX_'+name).concat('PAYMENT_STRIPE_BILLING_FIRST_PAYMENT');
const OLD_RUNS=new Set(['031b5ae7-6603-4339-b1b3-77231291ff34','34941d87-12d3-4c74-8c5a-b62b7c914390']);
function descriptors(value){if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw null;return Object.getOwnPropertyDescriptors(value);}
function data(d,key){const p=d[key];if(!p?.enumerable||!Object.hasOwn(p,'value'))throw null;return p.value;}
/** Reciprocal gate for legacy factories, including exotic legacy environment records. */
export function billingAcceptanceExcluded(env){
  try{if(!env||typeof env!=='object')return false;return [FLAG,'PAYMENT_STRIPE_BILLING_FIRST_PAYMENT'].every(key=>{const d=Object.getOwnPropertyDescriptor(env,key);return d?d.enumerable&&Object.hasOwn(d,'value')&&[undefined,'','false'].includes(d.value):!(key in env);});}catch{return false;}
}
export function billingAcceptanceOptIn(env){
  try{const d=descriptors(env);for(const key of [FLAG,'PAYMENT_STRIPE_SANDBOX_ENABLED',...EXCLUDED,'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'])if(d[key]&&(!d[key].enumerable||!Object.hasOwn(d[key],'value')))return false;
    return data(d,FLAG)==='true'&&data(d,'PAYMENT_STRIPE_SANDBOX_ENABLED')==='true'&&EXCLUDED.every(k=>[undefined,'','false'].includes(d[k]?.value))
      &&[undefined,''].includes(d.DEBUG?.value)&&[undefined,''].includes(d.NODE_DEBUG?.value)&&[undefined,'','0'].includes(d.PWDEBUG?.value)&&[undefined,'','off'].includes(d.PW_TRACE?.value);
  }catch{return false;}
}
/** Pure admission. The generated snapshot is also accepted, without widening authority. */
export function validateBillingAcceptanceOptions(value){
  try{const d=descriptors(value),env=data(d,'env');if(!billingAcceptanceOptIn(env))return null;
    const keys=['env','runId','expectedSource','daemon','ports','approvedBinding','selectedCase'];
    if(Reflect.ownKeys(d).some(k=>![...keys,'budget','protocol'].includes(k)))return null;
    const input=Object.fromEntries(keys.map(k=>[k,data(d,k)])),base=validateFormDiagnosticTopology(input);
    if(!base||OLD_RUNS.has(base.runId)||resolve(new URL(base.daemon).pathname)!==new URL(base.daemon).pathname||input.selectedCase!=='initial_success')return null;
    const bd=descriptors(input.approvedBinding);if(Reflect.ownKeys(bd).length!==2||data(bd,'externalAccountId')!=='acct_1UPiMpELswAd8tpR'||data(bd,'apiVersion')!=='2026-09-30.endive')return null;
    if(d.protocol&&data(d,'protocol')!=='SERVER_CONFIRM_V2')return null;
    if(d.budget){const budget=descriptors(data(d,'budget'));if(Reflect.ownKeys(budget).length!==2||data(budget,'create')!==1||data(budget,'confirm')!==1)return null;}
    return Object.freeze({...base,env:Object.freeze({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',[FLAG]:'true'}),approvedBinding:Object.freeze({externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}),selectedCase:'initial_success',protocol:'SERVER_CONFIRM_V2',budget:Object.freeze({create:1,confirm:1})});
  }catch{return null;}
}

/** Explicit native environment boundary. Check every security flag before making a plain snapshot.
 * process.env has an exotic prototype; no unrelated environment value is enumerated or read.
 */
export function snapshotBillingAcceptanceEnvironment(raw){
  try{
    if(!raw||typeof raw!=='object'||Array.isArray(raw))return null;
    const snapshot={};
    for(const key of [FLAG,'PAYMENT_STRIPE_SANDBOX_ENABLED',...EXCLUDED,'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE']){
      const own=Object.getOwnPropertyDescriptor(raw,key);
      if(own){if(!own.enumerable||!Object.hasOwn(own,'value'))return null;snapshot[key]=own.value;}
      let parent=Object.getPrototypeOf(raw),depth=0;
      while(parent){if(++depth>32||Object.getOwnPropertyDescriptor(parent,key))return null;parent=Object.getPrototypeOf(parent);}
    }
    return billingAcceptanceOptIn(snapshot)?Object.freeze(snapshot):null;
  }catch{return null;}
}
