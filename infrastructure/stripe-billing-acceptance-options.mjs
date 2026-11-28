import {resolve} from 'node:path';
import {isIPv4} from 'node:net';
import {validateFormDiagnosticTopology} from './stripe-manual-form-options.mjs';
const FLAG='PAYMENT_STRIPE_BILLING_ACCEPTANCE';
const CASE='PAYMENT_STRIPE_BILLING_CASE';
export const RETRY_CLI_PROFILE_RUN_ID='PAYMENT_STRIPE_BILLING_RETRY_CLI_PROFILE_RUN_ID';
export const RETRY_NETWORK_SUBNET='PAYMENT_STRIPE_BILLING_RETRY_SUBNET';
export const THREE_DS_CLI_PROFILE_RUN_ID='PAYMENT_STRIPE_BILLING_3DS_CLI_PROFILE_RUN_ID';
export const THREE_DS_NETWORK_SUBNET='PAYMENT_STRIPE_BILLING_3DS_SUBNET';
const CANCELLATION_CLI_PROFILE_RUN_ID='PAYMENT_STRIPE_BILLING_CANCELLATION_CLI_PROFILE_RUN_ID';
const CANCELLATION_NETWORK_SUBNET='PAYMENT_STRIPE_BILLING_CANCELLATION_SUBNET';
const INVOICE_WEBHOOKS_CLI_PROFILE_RUN_ID='PAYMENT_STRIPE_BILLING_INVOICE_WEBHOOKS_CLI_PROFILE_RUN_ID';
const INVOICE_WEBHOOKS_NETWORK_SUBNET='PAYMENT_STRIPE_BILLING_INVOICE_WEBHOOKS_SUBNET';
const REQUIRED_TRIAL_CLI_PROFILE_RUN_ID='PAYMENT_STRIPE_BILLING_REQUIRED_TRIAL_CLI_PROFILE_RUN_ID';
const REQUIRED_TRIAL_NETWORK_SUBNET='PAYMENT_STRIPE_BILLING_REQUIRED_TRIAL_SUBNET';
export const PORTAL_CLI_PROFILE_RUN_ID='PAYMENT_STRIPE_BILLING_PORTAL_CLI_PROFILE_RUN_ID';
export const PORTAL_NETWORK_SUBNET='PAYMENT_STRIPE_BILLING_PORTAL_SUBNET';
const requiredTrialCase='initial_required_trial';
const portalCase='initial_portal';
const cancelCase='initial_cancel_withdraw';
const invoiceCase='initial_missed_reordered';
const challengeCase='initial_3ds';
const retryCase='initial_decline_retry';
const OTHER_BILLING=['PAYMENT_STRIPE_BILLING_FAILED_RENEWAL','PAYMENT_STRIPE_BILLING_FIRST_RENEWAL','PAYMENT_STRIPE_BILLING_FIRST_PAYMENT','PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL'];
const EXCLUDED=['ALLOW_TEST_OPERATIONS','FULL_RUN','OWNED_RUN','REMAINING_ONLY','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT','MANUAL_3DS','MANUAL_REFUND','MANUAL_REFUND_REMAINDER'].map(name=>'PAYMENT_STRIPE_SANDBOX_'+name).concat(OTHER_BILLING);
const OLD_RUNS=new Set(['031b5ae7-6603-4339-b1b3-77231291ff34','34941d87-12d3-4c74-8c5a-b62b7c914390']);
const PROFILE_FLAGS=[[RETRY_CLI_PROFILE_RUN_ID,retryCase],[THREE_DS_CLI_PROFILE_RUN_ID,challengeCase],
  [CANCELLATION_CLI_PROFILE_RUN_ID,cancelCase],[INVOICE_WEBHOOKS_CLI_PROFILE_RUN_ID,invoiceCase],
  [REQUIRED_TRIAL_CLI_PROFILE_RUN_ID,requiredTrialCase],[PORTAL_CLI_PROFILE_RUN_ID,portalCase]];
const SUBNET_FLAGS=[[RETRY_NETWORK_SUBNET,retryCase],[THREE_DS_NETWORK_SUBNET,challengeCase],
  [CANCELLATION_NETWORK_SUBNET,cancelCase],[INVOICE_WEBHOOKS_NETWORK_SUBNET,invoiceCase],
  [REQUIRED_TRIAL_NETWORK_SUBNET,requiredTrialCase],[PORTAL_NETWORK_SUBNET,portalCase]];
const RUNTIME_FLAGS=[...PROFILE_FLAGS,...SUBNET_FLAGS].map(([key])=>key);
const FLAGS=[FLAG,CASE,...RUNTIME_FLAGS,'PAYMENT_STRIPE_SANDBOX_ENABLED',...EXCLUDED,'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'];
function descriptors(value){if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw null;return Object.getOwnPropertyDescriptors(value);}
function data(d,key){const p=d[key];if(!p?.enumerable||!Object.hasOwn(p,'value'))throw null;return p.value;}
function runtimeOption(d,flags){
  const selected=flags.find(([,selectedCase])=>selectedCase===d[CASE]?.value);
  return selected&&d[selected[0]]?.value;
}
/** Select options from an already validated acceptance environment. */
export function billingAcceptanceProfileRunId(env){return runtimeOption(Object.getOwnPropertyDescriptors(env),PROFILE_FLAGS);}
export function billingAcceptanceSubnet(env){return runtimeOption(Object.getOwnPropertyDescriptors(env),SUBNET_FLAGS);}
/** Reciprocal gate for legacy factories, including exotic legacy environment records. */
export function billingAcceptanceExcluded(env){
  try{if(!env||typeof env!=='object')return false;return [FLAG,...OTHER_BILLING].every(key=>{const d=Object.getOwnPropertyDescriptor(env,key);return d?d.enumerable&&Object.hasOwn(d,'value')&&[undefined,'','false'].includes(d.value):!(key in env);});}catch{return false;}
}
export function billingAcceptanceOptIn(env){
  try{return admittedFlags(descriptors(env));}catch{return false;}
}
function admittedFlags(d){
  for(const key of FLAGS)if(d[key]&&(!d[key].enumerable||!Object.hasOwn(d[key],'value')))return false;
    for(const [key,selectedCase] of PROFILE_FLAGS)if(Object.hasOwn(d,key)){
      const runId=data(d,key);
      if(data(d,CASE)!==selectedCase||typeof runId!=='string'||runId.length!==36||!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(runId)||OLD_RUNS.has(runId))return false;
    }
    for(const [key,selectedCase] of SUBNET_FLAGS)if(Object.hasOwn(d,key)){
      const subnet=data(d,key);
      if(data(d,CASE)!==selectedCase||typeof subnet!=='string'||!subnet.startsWith('10.')||!subnet.endsWith('/28'))return false;
      const address=subnet.slice(0,-3);
      if(!isIPv4(address)||Number(address.split('.').at(-1))%16!==0)return false;
    }
    if(d[CASE]?.value===requiredTrialCase&&!Object.hasOwn(d,REQUIRED_TRIAL_CLI_PROFILE_RUN_ID))return false;
    if(d[CASE]?.value===portalCase&&!Object.hasOwn(d,PORTAL_CLI_PROFILE_RUN_ID))return false;
    return data(d,FLAG)==='true'&&data(d,'PAYMENT_STRIPE_SANDBOX_ENABLED')==='true'&&EXCLUDED.every(k=>[undefined,'','false'].includes(d[k]?.value))
      &&(!d[CASE] || [cancelCase,retryCase,challengeCase,invoiceCase,requiredTrialCase,portalCase].includes(data(d,CASE)))
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
    const profileRunId=runtimeOption(flags,PROFILE_FLAGS);
    if(profileRunId===base.runId)return null;
    const bd=descriptors(input.approvedBinding);if(Reflect.ownKeys(bd).length!==2||data(bd,'externalAccountId')!=='acct_1UPiMpELswAd8tpR'||data(bd,'apiVersion')!=='2026-09-30.endive')return null;
    const protocol=selectedCase===requiredTrialCase?'BROWSER_CONFIRM_V1':'SERVER_CONFIRM_V2';
    if(d.protocol&&data(d,'protocol')!==protocol)return null;
    const expectedBudget=selectedCase===requiredTrialCase?{create:1,setupPermit:1,setupConfirm:1}:selectedCase===invoiceCase?{create:1,confirm:1,reconcile:1,deliver:2}:selectedCase===challengeCase?{create:1,confirm:1,nextAction:1}:selectedCase===retryCase?{create:1,confirm:2,retry:1}:selectedCase===cancelCase?{create:1,confirm:1,schedule:1,withdraw:1}
      :selectedCase===portalCase?{create:1,confirm:1,portal:1}:{create:1,confirm:1};
    if(d.budget){const budget=descriptors(data(d,'budget'));if(Reflect.ownKeys(budget).length!==Object.keys(expectedBudget).length||Object.keys(expectedBudget).some(k=>data(budget,k)!==expectedBudget[k]))return null;}
    const runtime=Object.fromEntries(RUNTIME_FLAGS.filter(key=>Object.hasOwn(flags,key)).map(key=>[key,data(flags,key)]));
    return Object.freeze({...base,env:Object.freeze({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',[FLAG]:'true',...(selectedCase==='initial_success'?{}:{[CASE]:selectedCase}),
      ...runtime}),approvedBinding:Object.freeze({externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}),selectedCase:input.selectedCase,protocol,budget:Object.freeze(expectedBudget)});
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
