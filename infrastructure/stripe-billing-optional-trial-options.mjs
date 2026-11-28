import {isIPv4} from 'node:net';
import {resolve} from 'node:path';
import {isProxy} from 'node:util/types';
import {validateFormDiagnosticTopology} from './stripe-manual-form-options.mjs';

const FLAG='PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL';
export const OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID=FLAG+'_CLI_PROFILE_RUN_ID';
export const OPTIONAL_TRIAL_SUBNET=FLAG+'_SUBNET';
const PROFILE=OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID;
const SUBNET=OPTIONAL_TRIAL_SUBNET;
const CASE='optional_trial_pause_resume';
const BINDING=Object.freeze({externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'});
const HISTORICAL_RUNS=new Set(['031b5ae7-6603-4339-b1b3-77231291ff34','34941d87-12d3-4c74-8c5a-b62b7c914390']);
const EXCLUDED=['PAYMENT_STRIPE_BILLING_FIRST_PAYMENT','PAYMENT_STRIPE_BILLING_FIRST_RENEWAL',
  'PAYMENT_STRIPE_BILLING_FAILED_RENEWAL','PAYMENT_STRIPE_BILLING_ACCEPTANCE','PAYMENT_STAGE4_TEST_RUNTIME',
  'PAYMENT_STRIPE_ACCEPTANCE','PAYMENT_STRIPE_NATIVE_ACCEPTANCE',...['ALLOW_TEST_OPERATIONS','FULL_RUN','OWNED_RUN',
    'REMAINING_ONLY','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT','MANUAL_3DS',
    'MANUAL_REFUND','MANUAL_REFUND_REMAINDER'].map(suffix=>'PAYMENT_STRIPE_SANDBOX_'+suffix)];
const PRIVATE=[FLAG+'_ACCOUNT_ID',FLAG+'_FROZEN_TIME',FLAG+'_CONTROL_KEY',FLAG+'_CONTROL_BIND',
  FLAG+'_CONTROL_PORT',FLAG+'_SOURCE_SHA',FLAG+'_RUN_ID'];
const FLAGS=[FLAG,PROFILE,SUBNET,'PAYMENT_STRIPE_SANDBOX_ENABLED',...EXCLUDED,...PRIVATE,'DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'];
const disabled=value=>[undefined,'','false'].includes(value);
const uuid=value=>typeof value==='string'&&value.length===36&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);

function record(value){
  if(!value||typeof value!=='object'||isProxy(value)||![Object.prototype,null].includes(Object.getPrototypeOf(value)))throw null;
  return Object.getOwnPropertyDescriptors(value);
}
function data(descriptors,key){
  const value=descriptors[key];
  if(!value?.enumerable||!Object.hasOwn(value,'value'))throw null;
  return value.value;
}
function allowedEnvironment(descriptors){
  for(const key of FLAGS)if(descriptors[key])data(descriptors,key);
  const profile=data(descriptors,PROFILE),subnet=data(descriptors,SUBNET);
  if(!uuid(profile)||HISTORICAL_RUNS.has(profile)||typeof subnet!=='string'||!subnet.startsWith('10.')||!subnet.endsWith('/28'))return false;
  const address=subnet.slice(0,-3);
  return isIPv4(address)&&Number(address.split('.').at(-1))%16===0&&data(descriptors,FLAG)==='true'
    &&data(descriptors,'PAYMENT_STRIPE_SANDBOX_ENABLED')==='true'&&EXCLUDED.every(key=>disabled(descriptors[key]?.value))
    &&PRIVATE.every(key=>!Object.hasOwn(descriptors,key))
    &&[undefined,''].includes(descriptors.DEBUG?.value)&&[undefined,''].includes(descriptors.NODE_DEBUG?.value)
    &&[undefined,'','0'].includes(descriptors.PWDEBUG?.value)&&[undefined,'','off'].includes(descriptors.PW_TRACE?.value);
}

export function billingOptionalTrialOptIn(env){
  try{return allowedEnvironment(record(env));}catch{return false;}
}

export function billingOptionalTrialExcluded(env){
  try{
    if(!env||typeof env!=='object'||isProxy(env))return false;
    const flag=Object.getOwnPropertyDescriptor(env,FLAG);
    return flag?disabled(data({[FLAG]:flag},FLAG)):!(FLAG in env);
  }catch{return false;}
}

export function validateBillingOptionalTrialOptions(value){
  try{
    const descriptors=record(value),keys=['env','runId','expectedSource','daemon','ports','approvedBinding','selectedCase','controlPort'];
    if(Reflect.ownKeys(descriptors).length!==keys.length||Reflect.ownKeys(descriptors).some(key=>!keys.includes(key)))return null;
    const selected=Object.fromEntries(keys.map(key=>[key,data(descriptors,key)]));
    const flags=record(selected.env);
    if(!allowedEnvironment(flags))return null;
    const topology=validateFormDiagnosticTopology(selected);
    const profile=data(flags,PROFILE),controlPort=selected.controlPort;
    if(!topology||HISTORICAL_RUNS.has(topology.runId)||topology.runId===profile||selected.selectedCase!==CASE)return null;
    const socket=new URL(topology.daemon).pathname;
    if(resolve(socket)!==socket||!Number.isInteger(controlPort)||controlPort<1024||controlPort>65535
      ||controlPort===8080||Object.values(topology.ports).includes(controlPort))return null;
    const binding=record(selected.approvedBinding);
    if(Reflect.ownKeys(binding).length!==2||Object.entries(BINDING).some(([key,expected])=>data(binding,key)!==expected))return null;
    const env=Object.freeze({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',[FLAG]:'true',[PROFILE]:profile,[SUBNET]:data(flags,SUBNET)});
    return Object.freeze({...topology,env,approvedBinding:BINDING,selectedCase:CASE,controlPort});
  }catch{return null;}
}

export function snapshotBillingOptionalTrialEnvironment(raw){
  try{
    if(!raw||typeof raw!=='object'||Array.isArray(raw)||isProxy(raw))return null;
    const env={};
    for(const key of FLAGS){
      const own=Object.getOwnPropertyDescriptor(raw,key);
      if(own)env[key]=data({[key]:own},key);
      let parent=Object.getPrototypeOf(raw),depth=0;
      while(parent){
        if(++depth>32||isProxy(parent)||Object.getOwnPropertyDescriptor(parent,key))return null;
        parent=Object.getPrototypeOf(parent);
      }
    }
    return billingOptionalTrialOptIn(env)?Object.freeze(env):null;
  }catch{return null;}
}
