import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {setTimeout as delay} from 'node:timers/promises';
import {validateBillingCallbackEvidence} from './stripe-billing-evidence.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';

const witnesses=new WeakMap();
const ids=['accountId','siteId','customerId','subscriptionId','operationId','attemptId','productId','priceId'];
const refs={customer:'cus',subscription:'sub',product:'prod',price:'price',invoice:'in',allocation:'inpay',intent:'pi'};
const digest=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
function requireProof(v){if(!v)throw billingError('EVIDENCE');}

function parameters({configuration,fixture,phase,attempts},scope){
  const {first,retry,second}=attempts??{},recovered=phase==='recovered';
  requireProof(configuration.selectedCase==='initial_decline_retry'&&(recovered||phase==='declined')
    &&scope.runId===configuration.runId&&billingUuid(scope.runId)&&scope.expectedSource===configuration.expectedSource
    &&/^[a-f0-9]{40}$/.test(scope.expectedSource)&&scope.siteKey==='s5a-'+scope.runId.replaceAll('-','')
    &&ids.every(k=>billingUuid(scope[k]))&&scope.attemptId===first?.attemptId&&scope.confirmKeyHash===first?.confirmKeyHash);
  const hashes=[scope.createKeyHash,first.confirmKeyHash];
  if(recovered){
    requireProof(witnesses.get(fixture)?.complete===false&&billingUuid(second?.attemptId)&&retry?.attemptId===second.attemptId
      &&!ids.some(k=>scope[k]===second.attemptId));
    hashes.push(retry.keyHash,second.confirmKeyHash);
  }else requireProof(!witnesses.has(fixture)&&retry===undefined&&second===undefined);
  requireProof(hashes.every(digest)&&new Set(hashes).size===hashes.length);
  return [...ids.map(k=>scope[k]),...hashes.slice(0,2),scope.endpoints.demo,scope.siteKey,
    second?.attemptId??null,retry?.keyHash??null,second?.confirmKeyHash??null];
}
function identity(scope,row){
  const graph={runId:scope.runId,expectedSource:scope.expectedSource,priceVersion:1};
  for(const field of ['accountId','siteId','customerId','subscriptionId','operationId','priceId'])graph[field]=scope[field];
  for(const [name,prefix] of Object.entries(refs)){
    const ref=row[name+'_reference'];
    requireProof(typeof ref==='string'&&new RegExp('^'+prefix+'_[A-Za-z0-9_]{1,200}$').test(ref));
    graph[name+'Reference']=ref;
  }
  requireProof(graph.priceReference===scope.priceReference&&typeof row.request_id==='string'&&/^req_[A-Za-z0-9_]{1,200}$/.test(row.request_id)
    &&typeof row.charge_reference==='string'&&/^ch_[A-Za-z0-9_]{1,200}$/.test(row.charge_reference)
    &&['generic_decline','do_not_honor','insufficient_funds'].includes(row.decline_code));
  return {graph,receipt:{chargeReference:row.charge_reference,declineCode:row.decline_code},request:row.request_id};
}
async function localRow(bootstrap,query,args,recovered,connect,limits){
  let row;
  requireProof(await bootstrap.withDatabaseCredentials(async credentials=>{
    billingCheck(limits);
    const sql=connect({...credentials,max:1,idle_timeout:1,connect_timeout:5,prepare:false,onnotice:()=>{},debug:false});
    let closing;
    const close=()=>closing??=sql.end({timeout:0});
    const abort=()=>{void close().catch(()=>{});};
    limits.signal.addEventListener('abort',abort,{once:true});
    try{
      for(;;){
        billingCheck(limits);
        const rows=await sql.begin('read only',async tx=>{
          await tx.unsafe("set local statement_timeout='5000'");
          return tx.unsafe(query,args);
        });
        billingCheck(limits);requireProof(rows.length===1);
        row=rows[0];requireProof(row.original===true&&row.lineage===true);
        if(!recovered){requireProof(row.phase_proof===true);break;}
        if(row.settlement_pending!==true){
          requireProof(row.phase_proof===true&&row.journal===true&&Array.isArray(row.callbacks));
          if(row.webhook===true&&row.callbacks.length>=2&&row.callbacks.every(c=>c.delivered===true))break;
        }
        await delay(250,undefined,{signal:limits.signal});
      }
      return true;
    }finally{
      limits.signal.removeEventListener('abort',abort);
      await billingBounded({deadline:Date.now()+5000},close,5000);
    }
  },limits)===true);
  return row;
}

export async function proveBillingInitialRetryEvidence(inputs,limits,dependencies={}){
  const {configuration,fixture,bootstrap,cli,phase}=inputs,recovered=phase==='recovered';
  try{
    billingCheck(limits);
    const scope=fixture.resources(),args=parameters(inputs,scope);
    const previous=witnesses.get(fixture);
    // Claim this transition before the first asynchronous operation, including a failing one.
    witnesses.set(fixture,{complete:true});
    const query=await readFile(new URL('./stripe-billing-initial-retry-proof.sql',import.meta.url),'utf8');
    const connect=dependencies.connect??createRequire(new URL('../examples/demo-site/package.json',import.meta.url))('postgres');
    const row=await billingBounded(limits,l=>localRow(bootstrap,query,args,recovered,connect,l),90000);
    const current=identity(scope,row);
    if(recovered){
      requireProof(previous.configuration===configuration&&previous.cli===cli&&previous.bootstrap===bootstrap
        &&previous.createKeyHash===scope.createKeyHash&&previous.firstAttempt===scope.attemptId&&previous.confirmHash===scope.confirmKeyHash
        &&previous.request===current.request&&previous.receipt.chargeReference===current.receipt.chargeReference
        &&previous.receipt.declineCode===current.receipt.declineCode
        &&Object.keys(current.graph).every(k=>current.graph[k]===previous.graph[k])
        &&(previous.bill===null||previous.bill===row.billing_record_id)
        &&billingUuid(row.payment_id)&&billingUuid(row.billing_record_id));
      const inspection=await billingBounded(limits,l=>fixture.inspectCallbacks(l),10000);
      const paid=validateBillingCallbackEvidence(scope,row,inspection);
      requireProof(Object.keys(current.graph).every(k=>paid.graph[k]===current.graph[k]));
      if(previous.periodStart!==null)requireProof(paid.graph.periodStart===previous.periodStart&&paid.graph.periodEnd===previous.periodEnd);
    }
    const periodStart=row.period_start===null?null:Number(row.period_start),periodEnd=row.period_end===null?null:Number(row.period_end);
    requireProof(!recovered&&periodStart===null&&periodEnd===null||Number.isSafeInteger(periodStart)&&periodStart>0&&Number.isSafeInteger(periodEnd)&&periodEnd>periodStart);
    requireProof(await billingBounded(limits,l=>cli.observeOwnedBillingInitialRetry(phase,{...current.graph,periodStart,periodEnd},current.receipt,l),120000)===true);
    billingCheck(limits);
    if(!recovered)witnesses.set(fixture,{...current,configuration,bootstrap,cli,periodStart,periodEnd,firstAttempt:scope.attemptId,
      createKeyHash:scope.createKeyHash,confirmHash:scope.confirmKeyHash,bill:row.billing_record_id??null,complete:false});
    return true;
  }catch{billingCheck(limits);throw billingError('EVIDENCE');}
}
