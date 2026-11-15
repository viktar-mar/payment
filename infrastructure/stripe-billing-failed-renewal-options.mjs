import {resolve} from 'node:path';
import {validateFormDiagnosticTopology} from './stripe-manual-form-options.mjs';
const FLAG='PAYMENT_STRIPE_BILLING_FAILED_RENEWAL';
const EXCLUDED=['PAYMENT_STRIPE_BILLING_FIRST_RENEWAL','PAYMENT_STRIPE_BILLING_FIRST_PAYMENT','PAYMENT_STRIPE_BILLING_ACCEPTANCE','PAYMENT_STAGE4_TEST_RUNTIME','PAYMENT_STRIPE_ACCEPTANCE','PAYMENT_STRIPE_NATIVE_ACCEPTANCE',...['ALLOW_TEST_OPERATIONS','FULL_RUN','OWNED_RUN','REMAINING_ONLY','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT','MANUAL_3DS','MANUAL_REFUND','MANUAL_REFUND_REMAINDER'].map(n=>'PAYMENT_STRIPE_SANDBOX_'+n)];
const BUDGET=Object.freeze({clock:1,customer:1,create:1,confirm:1,advance:5,declineAttach:1,declineUpdate:1,setup:1,method:1,recoveryConfirm:1});
const FLAGS=[FLAG,'PAYMENT_STRIPE_SANDBOX_ENABLED',...EXCLUDED,'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'];
const CASE='initial_failed_renewal_grace_recovery';
const BINDING=Object.freeze({externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'});
const OLD_RUNS=new Set(['031b5ae7-6603-4339-b1b3-77231291ff34','34941d87-12d3-4c74-8c5a-b62b7c914390']);
const disabled=value=>[undefined,'','false'].includes(value);
function record(value){
 if(!value||typeof value!=='object'||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw new Error();
 return Object.getOwnPropertyDescriptors(value);
}
function data(descriptors,key){
 const d=descriptors[key];if(!d?.enumerable||!Object.hasOwn(d,'value'))throw new Error();return d.value;
}
export function billingFailedRenewalExcluded(raw){
 try{
  if(!raw||typeof raw!=='object')return false;
  const d=Object.getOwnPropertyDescriptor(raw,FLAG);
  return d?d.enumerable&&Object.hasOwn(d,'value')&&disabled(d.value):!(FLAG in raw);
 }catch{return false;}
}
export function billingFailedRenewalOptIn(env){
 try{
  const d=record(env);
  for(const k of FLAGS)if(d[k])data(d,k);
  return data(d,FLAG)==='true'&&data(d,'PAYMENT_STRIPE_SANDBOX_ENABLED')==='true'&&EXCLUDED.every(k=>disabled(d[k]?.value))
   &&[undefined,''].includes(d.DEBUG?.value)&&[undefined,''].includes(d.NODE_DEBUG?.value)
   &&[undefined,'','0'].includes(d.PWDEBUG?.value)&&[undefined,'','off'].includes(d.PW_TRACE?.value);
 }catch{return false;}
}
export function validateBillingFailedRenewalOptions(raw){
 try{
  const d=record(raw),required=['env','runId','expectedSource','daemon','ports','approvedBinding','selectedCase','controlPort','frozenTime'];
  if(Reflect.ownKeys(d).some(k=>![...required,'budget','protocol'].includes(k)))return null;
  const o=Object.fromEntries(required.map(k=>[k,data(d,k)]));
  const topology=validateFormDiagnosticTopology(o);
  if(!topology||!billingFailedRenewalOptIn(o.env)||OLD_RUNS.has(topology.runId)||o.selectedCase!==CASE)return null;
  const socket=new URL(topology.daemon).pathname;
  if(resolve(socket)!==socket||!Number.isSafeInteger(o.frozenTime)||o.frozenTime<=0)return null;
  if(!Number.isInteger(o.controlPort)||o.controlPort<1024||o.controlPort>65535||o.controlPort===8080||Object.values(topology.ports).includes(o.controlPort))return null;
  const b=record(o.approvedBinding);if(Reflect.ownKeys(b).length!==2||Object.entries(BINDING).some(([k,v])=>data(b,k)!==v))return null;
  if(d.protocol&&data(d,'protocol')!=='SERVER_CONFIRM_V2')return null;
  if(d.budget){
   const b=record(data(d,'budget'));
   if(Reflect.ownKeys(b).length!==Object.keys(BUDGET).length||Object.entries(BUDGET).some(([k,v])=>data(b,k)!==v))return null;
  }
  return Object.freeze({...topology,env:Object.freeze({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',[FLAG]:'true'}),
   approvedBinding:BINDING,selectedCase:CASE,protocol:'SERVER_CONFIRM_V2',controlPort:o.controlPort,frozenTime:o.frozenTime,budget:BUDGET});
 }catch{return null;}
}
/** process.env may be exotic. Read descriptors of only the security flags. */
export function snapshotBillingFailedRenewalEnvironment(raw){
 try{
  if(!raw||typeof raw!=='object'||Array.isArray(raw))return null;
  const env={};
  for(const k of FLAGS){
   const d=Object.getOwnPropertyDescriptor(raw,k);
   if(d){if(!d.enumerable||!Object.hasOwn(d,'value'))return null;env[k]=d.value;}
   let parent=Object.getPrototypeOf(raw),depth=0;
   while(parent){if(++depth>32||Object.getOwnPropertyDescriptor(parent,k))return null;parent=Object.getPrototypeOf(parent);}
  }
  return billingFailedRenewalOptIn(env)?Object.freeze(env):null;
 }catch{return null;}
}
