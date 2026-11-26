import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {isProxy} from 'node:util/types';
import {exactRecord,instantNanos} from './stripe-billing-failed-renewal-response.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const proofFile=new URL('./stripe-billing-portal-proof.sql',import.meta.url);
const demoRequire=createRequire(new URL('../examples/demo-site/package.json',import.meta.url));
const verifiedRows=Object.freeze([Object.freeze({portal_verified:true})]);
const need=condition=>{if(!condition)throw billingError('PORTAL_EVIDENCE');};

function validReturnUrl(value) {
 if(typeof value!=='string'||value.length>2048)return false;
 const url=new URL(value);
 return url.href===value&&url.protocol==='http:'&&url.hostname==='127.0.0.1'&&Number(url.port)>=1024
  &&url.pathname==='/'&&!url.username&&!url.password&&!url.hash;
}

function timestamp(value) {
 need(typeof value==='string'&&value.length<=27);
 return instantNanos(value);
}

function snapshot(request) {
 need(!isProxy(request)&&Object.isFrozen(request));
 const {mode,query,args}=exactRecord(request,['mode','query','args']);
 need(mode==='read only'&&typeof query==='string'&&!isProxy(args)&&Array.isArray(args)&&Object.isFrozen(args));
 const fields=Object.getOwnPropertyDescriptors(args);
 need(fields.length?.value===15&&Reflect.ownKeys(fields).length===16);
 const parameters=Array.from({length:15},(_,index)=>{
  const field=fields[index];
  need(field?.enumerable&&Object.hasOwn(field,'value'));
  return field.value;
 });
 need(parameters.slice(0,8).every(billingUuid));
 const [policyVersion,keyDigest,returnUrl,expiresAt,revision,periodStart,periodEnd]=parameters.slice(8);
 need(policyVersion===1&&typeof keyDigest==='string'&&/^[a-f0-9]{64}$/.test(keyDigest)
  &&validReturnUrl(returnUrl)&&Number.isSafeInteger(revision)&&revision>=0);
 timestamp(expiresAt);
 need(timestamp(periodStart)<timestamp(periodEnd));
 return {query,args:Object.freeze(parameters)};
}

/** Read only the fixed proof supplied by the owning Portal flow. */
export async function readOnlyBillingPortalSql(inputs,limits,dependencies={}) {
 try {
  const {bootstrap,request}=inputs;
  return await billingBounded(limits,async active=>{
   const {query,args}=snapshot(request);
   need(query===await readFile(proofFile,{encoding:'utf8',signal:active.signal}));
   billingCheck(active);
   const connect=dependencies.connect??demoRequire('postgres');
   let called=false,verified=false;
   need(await bootstrap.withDatabaseCredentials(async credentials=>{
    billingCheck(active);
    need(!called);called=true;
    const sql=connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});
    let closing;
    const close=()=>closing??=(async()=>sql.end({timeout:0}))();
    const abort=()=>{void close().catch(()=>{});};
    active.signal.addEventListener('abort',abort,{once:true});
    try {
     billingCheck(active);
     const rows=await sql.begin('read only',async tx=>{
      billingCheck(active);
      await tx.unsafe("set local statement_timeout='5000'");
      billingCheck(active);
      return tx.unsafe(query,args);
     });
     billingCheck(active);
     need(!isProxy(rows)&&Array.isArray(rows)&&rows.length===1);
     const row=Object.getOwnPropertyDescriptor(rows,'0');
     need(row?.enumerable&&Object.hasOwn(row,'value')&&!isProxy(row.value)
      &&exactRecord(row.value,['portal_verified']).portal_verified===true);
    } finally {
     active.signal.removeEventListener('abort',abort);
     await billingBounded({deadline:Date.now()+5000},close,5000);
    }
    billingCheck(active);
    verified=true;return true;
   },active)===true&&verified);
   billingCheck(active);
   return verifiedRows;
  },60000);
 } catch {
  throw billingError('PORTAL_EVIDENCE');
 }
}
