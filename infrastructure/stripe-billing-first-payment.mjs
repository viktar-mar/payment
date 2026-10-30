import {validateBillingFirstPaymentOptions} from './stripe-billing-first-payment-options.mjs';
import {billingBounded,billingCheck,billingError} from './stripe-billing-limits.mjs';
import {createBillingFirstPaymentRuntime} from './stripe-billing-first-payment-runtime.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';
/** Single owner: construction is inert, run is explicit, uncertainty consumes all authority. */
export async function createOwnedBillingFirstPayment(options,dependencies={}){
 const c=validateBillingFirstPaymentOptions(options);if(!c)throw billingError('OPT_IN_REQUIRED');
 let state='NEW',phase='NEW',failure=null,accepted=false,cleanupConfirmed=false,bootstrap,cli,fixture,runtime,cleanupPromise,active,finish;
 const lifetime=new AbortController(),finished=new Promise(r=>finish=r);let prepareUsed=false,confirmUsed=false,prepareAck=false,confirmAck=false,selected,originalIdentity;
 const checkIdentity=v=>{if(!originalIdentity||Object.entries(originalIdentity).some(([k,value])=>v[k]!==value))fail('ACK');};
 const resources=()=>Object.freeze({runId:c.runId,expectedSource:c.expectedSource,scope:'OWNED_INITIAL_PAYMENT',status:state,phase,code:failure,initialAccepted:accepted,full5bAccepted:false,cleanupConfirmed});
 const fail=code=>{failure??=code;lifetime.abort();throw billingError(failure);};
 async function cleanup(){return cleanupPromise??=(async()=>{let ok=true;const l={deadline:Math.min(Date.now()+120000,(active?.deadline??Date.now())+120000)};for(const [o,m]of [[runtime,'close'],[cli,'stopListener'],[bootstrap,'stop'],[fixture,'close']])if(o)try{if(await billingBounded(l,x=>o[m](x),30000)!==true)ok=false;}catch{ok=false;}cleanupConfirmed=ok;if(!ok)failure??='CLEANUP';return ok;})();}
 return Object.freeze({resources,
  async run(journey,limits){if(state!=='NEW')throw billingError('SINGLE_USE');state='RUNNING';let timer,remove=()=>{};
   try{billingCheck(limits);if(typeof journey!=='function'||limits.deadline>Date.now()+2700000)fail('LIMITS');const abort=()=>lifetime.abort();limits.signal?.addEventListener('abort',abort,{once:true});remove=()=>limits.signal?.removeEventListener('abort',abort);timer=setTimeout(abort,Math.max(1,limits.deadline-Date.now()));active={deadline:limits.deadline,signal:lifetime.signal};
    async function step(name,body,max=120000){if(failure)fail(failure);phase=name;try{return await billingBounded(active,body,max);}catch{fail(active.signal.aborted?'ABORTED':name);}}
    const requireTrue=async(name,body,max)=>{if(await step(name,body,max)!==true)fail(name);};
    const pf=await step('PREFLIGHT',async l=>(dependencies.preflight??(await import('./stripe-sandbox-owned.mjs')).preflightOwnedBillingFirstPayment)(c,...(dependencies.preflight?[l]:[{},l])));if(pf!==true&&pf?.status!=='READY')fail('PREFLIGHT');
    bootstrap=await step('BOOTSTRAP',async()=>(dependencies.createBootstrap??(await import('./stripe-sandbox-bootstrap.mjs')).createOwnedBillingFirstPaymentBootstrap)(c));
    await requireTrue('PREPARE',()=>bootstrap.prepare(active),2700000);
    cli=await step('CLI',async()=>(dependencies.createCli??(await import('./stripe-sandbox-cli.mjs')).createBillingFirstPaymentCliAdapter)({configuration:c,cliDirectory:bootstrap.resources().cliDirectory,verifyForwardTarget:bootstrap.verifyForwardTarget,persistWebhookSecret:bootstrap.persistWebhookSecret}));
    await requireTrue('LOGIN',async()=>(dependencies.login??(await import('./stripe-billing-login.mjs')).completeBillingLogin)(cli,c,active),780000);
    await requireTrue('BINDING',()=>cli.validateBinding(active));await requireTrue('LISTENER',()=>cli.startListener(active));await requireTrue('START',()=>bootstrap.start(active),2700000);
    runtime=await step('RUNTIME',()=>(dependencies.createRuntime??createBillingFirstPaymentRuntime)({configuration:c,bootstrap}));await requireTrue('RUNTIME',()=>runtime.ready(active));
    fixture=await step('FIXTURE',async()=>(dependencies.createFixture??(await import('./stripe-billing-provision.mjs')).createBillingFixture)({configuration:c,bootstrap,cli}));await requireTrue('PROVISION',l=>fixture.provision(l));
    const child=l=>{billingCheck(active);billingCheck(l);return {deadline:Math.min(active.deadline,l.deadline),signal:l.signal?AbortSignal.any([active.signal,l.signal]):active.signal};};
    async function guarded(name,l,body){try{if(failure)fail(failure);return await billingBounded(child(l),body,120000);}catch{fail(name);}}
    const port=Object.freeze({resources:()=>fixture.resources(),
     withOtpCode:(callback,l)=>guarded('OTP',l,x=>fixture.withOtpCode(callback,x)),
     acknowledgeCustomer:v=>guarded('CUSTOMER',active,async l=>{if(await fixture.acknowledgeCustomer(v,l)!==true)fail('CUSTOMER');return true;}),
     prepare:(session,key,l)=>guarded('DISPATCH',l,async bound=>{
      if(prepareUsed)fail('SINGLE_USE');prepareUsed=true;
      const scope=fixture.resources();if(!session||Object.keys(session).sort().join(',')!=='origin,token'||typeof session.token!=='string'||!session.token||session.token.length>8192||session.origin!==scope.endpoints.demo||!['accountId','siteId','customerId','priceId'].every(k=>billingUuid(scope[k])))fail('SESSION');
      if(await cli.assertBillingListener(bound)!==true||await fixture.beforeDispatch('create',key,undefined,bound)!==true)fail('DISPATCH');
      const v=await runtime.prepare({...scope,customerToken:session.token,origin:session.origin,createKey:key},bound);billingCheck(bound);
      for(const [k,s] of [['run_id','runId'],['account_id','accountId'],['site_id','siteId'],['customer_id','customerId'],['price_id','priceId']])if(v[k]!==scope[s])fail('ACK');
      if(v.phase!=='PREPARED'||!billingUuid(v.subscription_id)||!billingUuid(v.operation_id)||!billingUuid(v.session?.attempt_id)||v.session.protocol!=='SERVER_CONFIRM_V2'||v.action?.kind!=='PROVIDER_CLIENT'||typeof v.action.secret!=='string'||typeof v.session.capability!=='string')fail('ACK');
      originalIdentity=Object.fromEntries(['run_id','account_id','site_id','customer_id','price_id','subscription_id','operation_id'].map(k=>[k,v[k]]));
      selected={attemptId:v.session.attempt_id,capability:v.session.capability,subscriptionId:v.subscription_id,operationId:v.operation_id,createKey:key};
      if(await fixture.acknowledge('create',{subscriptionId:v.subscription_id,operationId:v.operation_id},bound)!==true)fail('ACK');prepareAck=true;return v;
     }),
     confirm:(original,l)=>guarded('CONFIRM',l,async bound=>{
      if(!prepareAck||confirmUsed)fail('SINGLE_USE');confirmUsed=true;
      if(!original||Object.getPrototypeOf(original)!==Object.prototype||Object.keys(original).sort().join(',')!=='attempt_id,capability,confirmation_token,idempotency_key'||Object.values(Object.getOwnPropertyDescriptors(original)).some(d=>!Object.hasOwn(d,'value'))||Object.values(original).some(v=>typeof v!=='string'||!v||v.length>8192)||original.attempt_id!==selected.attemptId||original.capability!==selected.capability||original.idempotency_key===selected.createKey)fail('CONFIRM');
      const request=Object.freeze({...original});if(await cli.assertBillingListener(bound)!==true||await fixture.beforeDispatch('confirm',request.idempotency_key,selected,bound)!==true)fail('DISPATCH');
      const v=await runtime.confirm(request,bound);billingCheck(bound);checkIdentity(v);
      if(v.phase!=='OBSERVE'||v.protocol!=='SERVER_CONFIRM_V2'||v.attempt_id!==selected.attemptId||v.state!=='OBSERVE'||v.next_action_allowed!==false||v.retry_allowed!==false||v.successor_attempt_id!==null)fail('ACK');
      if(await fixture.acknowledge('confirm',{attemptId:selected.attemptId,state:'OBSERVE',nextActionAllowed:false},bound)!==true)fail('ACK');confirmAck=true;selected=null;return v;
     }),
    });
    await step('BROWSER',()=>journey(port,active),2700000);if(!prepareAck||!confirmAck)fail('ACK');
    let settlement;for(let i=0;i<30;i++){const v=await step('OBSERVE',l=>runtime.observe(l));checkIdentity(v);if(v.phase==='SETTLED_LOCAL'){settlement=v;break;}if(v.phase!=='OBSERVE')fail('OBSERVE');if(i<29)await step('OBSERVE',l=>(dependencies.pause??(()=>new Promise(r=>setTimeout(r,1000))))(l));}if(!settlement)fail('OBSERVE');
    if(Date.parse(settlement.access_evaluated_at)/1000!==c.frozenTime||Date.parse(settlement.access_valid_until)/1000!==c.frozenTime+300||!(Date.parse(settlement.period_start)/1000<=c.frozenTime)||!(Date.parse(settlement.paid_through)/1000>c.frozenTime))fail('EVIDENCE');
    const proof=await step('EVIDENCE',async l=>(dependencies.prove??(await import('./stripe-billing-first-payment-evidence.mjs')).proveBillingFirstPaymentEvidence)({configuration:c,bootstrap,fixture,settlement},l));if(!proof||!['ownedVerified','cashVerified','journalVerified','signedCallbacksVerified'].every(k=>proof[k]===true))fail('EVIDENCE');billingCheck(active);
   }catch{failure??=lifetime.signal.aborted?'ABORTED':'FAILED';}
   finally{clearTimeout(timer);remove();lifetime.abort();selected=null;await cleanup();state='CLOSED';finish();}
   if(failure)throw billingError(failure);accepted=true;phase='COMPLETE';return resources();
  },
  async close(){lifetime.abort();if(state==='RUNNING')await billingBounded({deadline:Date.now()+120000},()=>finished,120000);else{state='CLOSED';await cleanup();}return resources();},
 });
}
