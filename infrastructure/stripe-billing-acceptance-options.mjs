import {resolve} from 'node:path';
import {isIPv4} from 'node:net';
import {validateFormDiagnosticTopology} from './stripe-manual-form-options.mjs';
const FLAG='PAYMENT_STRIPE_BILLING_ACCEPTANCE';
const CASE='PAYMENT_STRIPE_BILLING_CASE';
export const RETRY_CLI_PROFILE_RUN_ID='PAYMENT_STRIPE_BILLING_RETRY_CLI_PROFILE_RUN_ID';
export const RETRY_NETWORK_SUBNET='PAYMENT_STRIPE_BILLING_RETRY_SUBNET';
const cancelCase='initial_cancel_withdraw';
const invoiceCase='initial_missed_reordered';
const challengeCase='initial_3ds';
const retryCase='initial_decline_retry';
const EXCLUDED=['ALLOW_TEST_OPERATIONS','FULL_RUN','OWNED_RUN','REMAINING_ONLY','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT','MANUAL_3DS','MANUAL_REFUND','MANUAL_REFUND_REMAINDER'].map(name=>'PAYMENT_STRIPE_SANDBOX_'+name).concat('PAYMENT_STRIPE_BILLING_FAILED_RENEWAL','PAYMENT_STRIPE_BILLING_FIRST_RENEWAL','PAYMENT_STRIPE_BILLING_FIRST_PAYMENT');
const OLD_RUNS=new Set(['031b5ae7-6603-4339-b1b3-77231291ff34','34941d87-12d3-4c74-8c5a-b62b7c914390']);
const FLAGS=[FLAG,CASE,RETRY_CLI_PROFILE_RUN_ID,RETRY_NETWORK_SUBNET,'PAYMENT_STRIPE_SANDBOX_ENABLED',...EXCLUDED,'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'];
function descriptors(value){if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw null;return Object.getOwnPropertyDescriptors(value);}
function data(d,key){const p=d[key];if(!p?.enumerable||!Object.hasOwn(p,'value'))throw null;return p.value;}
/** Reciprocal gate for legacy factories, including exotic legacy environment records. */
export function billingAcceptanceExcluded(env){
  try{if(!env||typeof env!=='object')return false;return [FLAG,'PAYMENT_STRIPE_BILLING_FAILED_RENEWAL','PAYMENT_STRIPE_BILLING_FIRST_RENEWAL','PAYMENT_STRIPE_BILLING_FIRST_PAYMENT'].every(key=>{const d=Object.getOwnPropertyDescriptor(env,key);return d?d.enumerable&&Object.hasOwn(d,'value')&&[undefined,'','false'].includes(d.value):!(key in env);});}catch{return false;}
}
export function billingAcceptanceOptIn(env){
  try{return admittedFlags(descriptors(env));}catch{return false;}
}
function admittedFlags(d){
  for(const key of FLAGS)if(d[key]&&(!d[key].enumerable||!Object.hasOwn(d[key],'value')))return false;
    if(Object.hasOwn(d,RETRY_CLI_PROFILE_RUN_ID)){
      const runId=data(d,RETRY_CLI_PROFILE_RUN_ID);
      if(data(d,CASE)!==retryCase||typeof runId!=='string'||runId.length!==36||!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(runId)||OLD_RUNS.has(runId))return false;
    }
    if(Object.hasOwn(d,RETRY_NETWORK_SUBNET)){
      const subnet=data(d,RETRY_NETWORK_SUBNET);
      if(data(d,CASE)!==retryCase||typeof subnet!=='string'||!subnet.startsWith('10.')||!subnet.endsWith('/28'))return false;
      const address=subnet.slice(0,-3);
      if(!isIPv4(address)||Number(address.split('.').at(-1))%16!==0)return false;
    }
    return data(d,FLAG)==='true'&&data(d,'PAYMENT_STRIPE_SANDBOX_ENABLED')==='true'&&EXCLUDED.every(k=>[undefined,'','false'].includes(d[k]?.value))
      &&(!d[CASE] || [cancelCase,retryCase,challengeCase,invoiceCase].includes(data(d,CASE)))
      &&[undefined,''].includes(d.DEBUG?.value)&&[undefined,''].includes(d.NODE_DEBUG?.value)&&[undefined,'','0'].includes(d.PWDEBUG?.value)&&[undefined,'','off'].includes(d.PW_TRACE?.value);
}
/** Pure admission. The generated snapshot is also accepted, without widening authority. */
export function validateBillingAcceptanceOptions(value){
  try{const d=descriptors(value),flags=descriptors(data(d,'env'));if(!admittedFlags(flags))return null;
    const keys=['env','runId','expectedSource','daemon','ports','approvedBinding','selectedCase'];
    if(Reflect.ownKeys(d).some(k=>![...keys,'budget','protocol'].includes(k)))return null;
    const input=Object.fromEntries(keys.map(k=>[k,data(d,k)])),base=validateFormDiagnosticTopology(input);
    const selectedCase=Object.hasOwn(flags,CASE)?data(flags,CASE):'initial_success';
    if(!base||OLD_RUNS.has(base.runId)||resolve(new URL(base.daemon).pathname)!==new URL(base.daemon).pathname||input.selectedCase!==selectedCase)return null;
    const profileRunId=flags[RETRY_CLI_PROFILE_RUN_ID]?.value;
    if(profileRunId===base.runId)return null;
    const bd=descriptors(input.approvedBinding);if(Reflect.ownKeys(bd).length!==2||data(bd,'externalAccountId')!=='acct_1UPiMpELswAd8tpR'||data(bd,'apiVersion')!=='2026-09-30.endive')return null;
    if(d.protocol&&data(d,'protocol')!=='SERVER_CONFIRM_V2')return null;
    const expectedBudget=selectedCase===invoiceCase?{create:1,confirm:1,reconcile:1,deliver:2}:selectedCase===challengeCase?{create:1,confirm:1,nextAction:1}:selectedCase===retryCase?{create:1,confirm:2,retry:1}:selectedCase===cancelCase?{create:1,confirm:1,schedule:1,withdraw:1}:{create:1,confirm:1};
    if(d.budget){const budget=descriptors(data(d,'budget'));if(Reflect.ownKeys(budget).length!==Object.keys(expectedBudget).length||Object.keys(expectedBudget).some(k=>data(budget,k)!==expectedBudget[k]))return null;}
    const subnet=flags[RETRY_NETWORK_SUBNET]?.value;
    return Object.freeze({...base,env:Object.freeze({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',[FLAG]:'true',...(selectedCase==='initial_success'?{}:{[CASE]:selectedCase}),
      ...(profileRunId?{[RETRY_CLI_PROFILE_RUN_ID]:profileRunId}:{}),...(subnet?{[RETRY_NETWORK_SUBNET]:subnet}:{})}),approvedBinding:Object.freeze({externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}),selectedCase:input.selectedCase,protocol:'SERVER_CONFIRM_V2',budget:Object.freeze(expectedBudget)});
  }catch{return null;}
}

/** Explicit native environment boundary. Check every security flag before making a plain snapshot.
 * process.env has an exotic prototype; no unrelated environment value is enumerated or read.
 */
export function snapshotBillingAcceptanceEnvironment(raw){
  try{
    if(!raw||typeof raw!=='object'||Array.isArray(raw))return null;
    const snapshot={};
    for(const key of FLAGS){
      const own=Object.getOwnPropertyDescriptor(raw,key);
      if(own){if(!own.enumerable||!Object.hasOwn(own,'value'))return null;snapshot[key]=own.value;}
      let parent=Object.getPrototypeOf(raw),depth=0;
      while(parent){if(++depth>32||Object.getOwnPropertyDescriptor(parent,key))return null;parent=Object.getPrototypeOf(parent);}
    }
    return billingAcceptanceOptIn(snapshot)?Object.freeze(snapshot):null;
  }catch{return null;}
}
