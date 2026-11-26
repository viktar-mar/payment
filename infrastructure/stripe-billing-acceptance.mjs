import {createOwnedInvoiceIngress,isInvoiceIngressFailureCode} from './stripe-billing-invoice-ingress.mjs';
import {invoiceWebhookFlow} from './stripe-billing-invoice-webhook-port.mjs';
import {initialChallengeFlow} from './stripe-billing-initial-3ds-port.mjs';
import {initialRetryFlow} from './stripe-billing-initial-retry-port.mjs';
import {billingAcceptanceProfileRunId,validateBillingAcceptanceOptions} from './stripe-billing-acceptance-options.mjs';
import {createOwnedBillingBootstrap} from './stripe-sandbox-bootstrap.mjs';
import {createBillingCliAdapter} from './stripe-sandbox-cli.mjs';
import {preflightOwnedBilling} from './stripe-sandbox-owned.mjs';
import {browserDiagnostic,browserCleanupConfirmed,browserProviderOrigin} from './stripe-billing-first-payment-browser-diagnostic.mjs';
import {billingBounded, billingCheck, billingError} from './stripe-billing-limits.mjs';
import {createHash} from 'node:crypto';
import {billingInitial3dsProvisionCheckpoint,billingUuid} from './stripe-billing-provision.mjs';

/** Inert admission. Native capabilities are constructed only by the explicit, single-use run. */
export async function createOwnedBillingAcceptance(options,dependencies={}) {
 return createOwner(options,dependencies,'initial_success');
}
export async function createOwnedBillingCancellation(options,dependencies={}) {
 return createOwner(options,dependencies,'initial_cancel_withdraw');
}
export async function createOwnedBillingDeclineRetry(options,dependencies={}) {
 return createOwner(options,dependencies,'initial_decline_retry');
}
export async function createOwnedBillingInitial3ds(options,dependencies={}) {
 return createOwner(options,dependencies,'initial_3ds');
}
export async function createOwnedBillingInvoiceWebhooks(options,dependencies={}) {
 return createOwner(options,dependencies,'initial_missed_reordered');
}
export async function createOwnedBillingRequiredTrial(options,dependencies={}) {
 return createOwner(options,dependencies,'initial_required_trial');
}
async function createOwner(options,dependencies,selectedCase) {
 const invoiceCase=selectedCase==='initial_missed_reordered',requiredTrial=selectedCase==='initial_required_trial';
 let trialStage='create';
 let ingress;
 const challenge=selectedCase==='initial_3ds';
 const cancellation=selectedCase==='initial_cancel_withdraw',retry=selectedCase==='initial_decline_retry';
 const configuration=validateBillingAcceptanceOptions(options);
 if(!configuration || configuration.selectedCase!==selectedCase)throw billingError('OPT_IN_REQUIRED');
 let browserCompletion;
 let finishRun;const finished=new Promise(resolve=>finishRun=resolve);
 let state='NEW',phase='NEW',failure=null,bootstrap,cli,fixture,cleanupPromise,active,accepted=false,cleanupConfirmed=false;
 const lifetime=new AbortController(),counts={create:0,confirm:0},acks={create:false,confirm:false};
 const resources=()=>Object.freeze({runId:configuration.runId,expectedSource:configuration.expectedSource,scope:requiredTrial?'INITIAL_REQUIRED_TRIAL':invoiceCase?'INITIAL_MISSED_REORDERED':challenge?'INITIAL_3DS':retry?'INITIAL_DECLINE_RETRY':cancellation?'INITIAL_CANCEL_WITHDRAW':'INITIAL_NON_CLOCK',status:state,phase,code:failure,initialAccepted:!requiredTrial&&accepted,...(requiredTrial?{requiredTrialAccepted:accepted}:{}),...(cancellation?{cancellationAccepted:accepted}:{}),...(retry?{declineRetryAccepted:accepted}:{}),...(challenge?{initial3dsAccepted:accepted}:{}),...(invoiceCase?{invoiceWebhooksAccepted:accepted}:{}),full5bAccepted:false,cleanupConfirmed});
 const fail=code=>{failure??=code;lifetime.abort();throw billingError(failure);};
 async function cleanup() {
  if(cleanupPromise)return cleanupPromise;
  cleanupPromise=(async()=>{
   let ok=true;
   const now=Date.now(),limits={deadline:Math.min(now,active?.deadline??now)+120000};
   // Run cancellation can win before the browser finishes closing its context.
   const browserClosed=browserCompletion&&billingBounded(limits,()=>browserCompletion,20000).catch(()=>false);
   for(const [object,method] of [[fixture,'close'],[cli,'stopListener'],[ingress,'close'],[bootstrap,'stop']])if(object)try{
    // Topology shutdown re-proves and stops each owned container in sequence.
    if(await billingBounded(limits,l=>object[method](l),method==='stop'?120000:30000)!==true)ok=false;
   }catch{ok=false;}
   if(browserClosed&&await browserClosed!==true)ok=false;
   cleanupConfirmed=ok;if(!ok)failure??='CLEANUP';return ok;
  })();return cleanupPromise;
 }
 return Object.freeze({resources,
  async run(browserJourney,limits) {
   if(state!=='NEW')throw billingError('SINGLE_USE');state='RUNNING';
   let timer,remove=()=>{};
   try {
    billingCheck(limits);if(typeof browserJourney!=='function'||limits.deadline-Date.now()>2700000)fail('LIMITS');
    const abort=()=>lifetime.abort();limits.signal?.addEventListener('abort',abort,{once:true});remove=()=>limits.signal?.removeEventListener('abort',abort);
    timer=setTimeout(abort,Math.max(1,limits.deadline-Date.now()));active={deadline:limits.deadline,signal:lifetime.signal};
    const step=async(name,body,max=120000)=>{if(failure)fail(failure);phase=name;try{const value=await billingBounded(active,body,max);if(['PREPARE','LOGIN','BINDING','LISTENER','START','PROVISION'].includes(name)&&value!==true)fail(name);return value;}catch{failure??=active.signal.aborted?'ABORTED':name;lifetime.abort();throw billingError(failure);}};
    const preflight=await step('PREFLIGHT',l=>dependencies.preflight?dependencies.preflight(configuration,l):preflightOwnedBilling(configuration,{},l));
    if(preflight!==true&&preflight?.status!=='READY')fail('PREFLIGHT');
    bootstrap=await step('BOOTSTRAP',()=>(dependencies.createBootstrap??createOwnedBillingBootstrap)(configuration));
    await step('PREPARE',()=>bootstrap.prepare(active),2700000);
    if(invoiceCase){
     ingress=(dependencies.createInvoiceIngress??createOwnedInvoiceIngress)(configuration,bootstrap,{onFailure(code){
      if(state==='RUNNING'){failure??=isInvoiceIngressFailureCode(code)?code:'INGRESS';lifetime.abort();}
     }});
     if(await step('INGRESS',l=>ingress.open(l))!==true)fail('INGRESS');
    }
    cli=await step('CLI',()=>(dependencies.createCli??createBillingCliAdapter)({configuration,cliDirectory:bootstrap.resources().cliDirectory,verifyForwardTarget:bootstrap.verifyForwardTarget,persistWebhookSecret:bootstrap.persistWebhookSecret,...(invoiceCase?{invoiceIngress:ingress}:{})}));
    if(!billingAcceptanceProfileRunId(configuration.env)){
     await step('LOGIN',async()=>{const login=dependencies.login??(await import('./stripe-billing-login.mjs')).completeBillingLogin;return login(cli,configuration,active);},780000);
    }
    // With a saved profile, binding is the first CLI call and captures the run's authority.
    await step('BINDING',()=>cli.validateBinding(active));
    await step('LISTENER',l=>cli.startListener(l));
    await step('START',l=>bootstrap.start(l),2700000);
    fixture=await step('FIXTURE',async()=>{const factory=dependencies.createFixture??(await import('./stripe-billing-provision.mjs')).createBillingFixture;return factory({configuration,bootstrap,cli});});
    let provisionLimits;
    try{await step('PROVISION',l=>{provisionLimits=l;return fixture.provision(l);});}
    catch(error){
     if(challenge&&failure==='PROVISION'&&provisionLimits){
      failure=billingInitial3dsProvisionCheckpoint(fixture,provisionLimits,configuration.runId,configuration.expectedSource)??failure;
     }
     throw error;
    }
    async function verifyInitial() {
     if(!acks.create||!acks.confirm)fail('ACK');
     const proof=await step('EVIDENCE',async l=>{const prove=dependencies.prove??(await import('./stripe-billing-evidence.mjs')).proveBillingEvidence;return prove({configuration,bootstrap,fixture},l);},120000);
     if(!proof||!['cashVerified','journalVerified','signedCallbacksVerified'].every(k=>proof[k]===true))fail('EVIDENCE');
     await step('GRAPH',async l=>{if(await cli.bindOwnedBillingGraph(proof.graph,l)!==true)fail('EVIDENCE');const p=await cli.observeOwnedBillingGraph(l);if(p?.providerVerified!==true||p.initialCashVerified!==true||p.lineCount!==1||p.allocationCount!==1||p.chargeCount!==1)fail('EVIDENCE');});
     return proof;
    }
    let next='baseline',baseline;
    const operations=Object.create(null);
    function claim(expected) {
     billingCheck(active);
     if(next!==expected||failure)fail('CANCELLATION_ORDER');
     next='busy';
    }
    const invoiceFlow=invoiceCase?invoiceWebhookFlow({configuration,bootstrap,cli,fixture,ingress,active,step,fail,createEvidence:dependencies.createInvoiceEvidence}):null;
    const retryFlow=retry?initialRetryFlow({configuration,bootstrap,cli,fixture,active,step,fail,prove:dependencies.proveInitialRetry}):null;
    const challengeFlow=challenge?initialChallengeFlow({configuration,bootstrap,cli,fixture,active,step,fail,prove:dependencies.proveInitial3ds}):null;
    function claimTrial(stage,next){
     billingCheck(active);
     if(failure||trialStage!==stage)fail('TRIAL_ORDER');
     trialStage=next;
    }
    async function recordTrial(stage,receipt,identity,key){
     await step(stage,async l=>{
      const scope=fixture.resources();
      if(!scope.subscriptionId||!scope.operationId||identity?.subscriptionId!==scope.subscriptionId||identity?.operationId!==scope.operationId)fail(stage);
      const permit=receipt==='trial-permit-dispatch';
      if(permit?!billingUuid(key):key!==undefined)fail(stage);
      const hash=permit?createHash('sha256').update(key).digest('hex'):undefined;
      if((permit&&hash===scope.createKeyHash)||await cli.assertBillingListener(l)!==true||
        await fixture.recordTrialSetup(receipt,scope,hash,l)!==true)fail(stage);
     });
    }
    const port=Object.freeze({resources:()=>fixture.resources(),
     beforeDispatch:async(kind,key,identity)=>{
      billingCheck(active);if(failure)fail(failure);
      if(!['create','confirm'].includes(kind)||counts[kind]!==0||(kind==='confirm'&&!acks.create))fail('DISPATCH');
      if(requiredTrial){
       if(kind!=='create'||!billingUuid(key))fail('DISPATCH');
       claimTrial('create','create-in-flight');
      }
      counts[kind]++;await step('DISPATCH',async l=>{if(await cli.assertBillingListener(l)!==true||await fixture.beforeDispatch(kind,key,identity,l)!==true)fail('DISPATCH');});
      if(requiredTrial)trialStage='create-sent';return true;
     },
     acknowledge:async(kind,identity)=>{
      billingCheck(active);if(failure)fail(failure);
      if(!['create','confirm'].includes(kind)||counts[kind]!==1||acks[kind]||identity?.state==='UNKNOWN')fail('ACK');
      if(requiredTrial)claimTrial('create-sent','creating');
      if(await step('ACK',l=>fixture.acknowledge(kind,identity,l))!==true)fail('ACK');
      acks[kind]=true;if(requiredTrial)trialStage='permit';return true;
     },
     withOtpCode:async(callback,l)=>{billingCheck(active);return fixture.withOtpCode(callback,{deadline:Math.min(active.deadline,l.deadline),signal:active.signal});},
     acknowledgeCustomer:async(value)=>{
      billingCheck(active);
      if(requiredTrial)return step('CUSTOMER',async l=>{
       if(await fixture.acknowledgeCustomer(value,l)!==true)fail('CUSTOMER');
       return true;
      });
      return fixture.acknowledgeCustomer(value,active);
     },
     ...(requiredTrial?{
      async beforeTrialSetup(kind,identity,key){
       if(kind!=='permit'&&kind!=='setup')fail('TRIAL_ORDER');
       claimTrial(kind,'writing');
       await recordTrial(kind==='permit'?'TRIAL_SETUP_PERMIT':'TRIAL_SETUP_DISPATCH',
        kind==='permit'?'trial-permit-dispatch':'trial-setup-dispatch',identity,key);
       trialStage=kind==='permit'?'setup':'ack';return true;
      },
      async acknowledgeTrialSetup(identity){
       claimTrial('ack','writing');
       await recordTrial('TRIAL_SETUP_ACK','trial-setup-ack',identity);
       trialStage='complete';return true;
      },
     }:{}),
     ...(invoiceFlow?.port),
     ...(retryFlow?.port),
     ...(challengeFlow?.port),
     ...(cancellation?{
      async verifyInitial(){claim('baseline');baseline=await verifyInitial();next='schedule';return true;},
      async beforeCancellation(kind,key){
       if(!['schedule','withdraw'].includes(kind)||!billingUuid(key))fail('CANCELLATION_ORDER');
       claim(kind);
       const keyHash=createHash('sha256').update(key).digest('hex');
       const scope=fixture.resources();
       if([scope.createKeyHash,scope.confirmKeyHash,operations.schedule?.keyHash].includes(keyHash))fail('CANCELLATION_ORDER');
       await step('CANCELLATION_DISPATCH',async l=>{
        if(await cli.assertBillingListener(l)!==true || await fixture.beforeCancellation(kind,keyHash,l)!==true)fail('CANCELLATION_DISPATCH');
       });
       operations[kind]={keyHash};next=kind+'-sent';return true;
      },
      async verifyCancellation(kind,operationId){
       if(!['schedule','withdraw'].includes(kind)||!billingUuid(operationId))fail('CANCELLATION_ORDER');
       claim(kind+'-sent');
       if(operationId===fixture.resources().operationId || operationId===operations.schedule?.operationId)fail('CANCELLATION_ORDER');
       operations[kind].operationId=operationId;
       const phase=kind==='schedule'?'scheduled':'withdrawn';
       await step('CANCELLATION_EVIDENCE',async l=>{
        let checkpoint='CANCELLATION_LOCAL_EVIDENCE';
        try{
         const prove=dependencies.proveCancellation??(await import('./stripe-billing-cancellation-evidence.mjs')).proveBillingCancellationEvidence;
         if(await prove({configuration,bootstrap,fixture,phase,operations,baseline},l)!==true)fail(checkpoint);
         billingCheck(l);
         checkpoint='CANCELLATION_PROVIDER_EVIDENCE';
         if(await cli.observeOwnedBillingCancellation(phase,l)!==true)fail(checkpoint);
         billingCheck(l);
         checkpoint='CANCELLATION_ACK_EVIDENCE';
         if(await fixture.acknowledgeCancellation(kind,operationId,l)!==true)fail(checkpoint);
        }catch{
         billingCheck(l);
         fail(checkpoint);
        }
       });
       next=kind==='schedule'?'withdraw':'complete';return true;
      },
     }:{}),
    });
    const browserResult=await step('BROWSER',async()=>{
     const guarded=retry||challenge||invoiceCase||cancellation||requiredTrial;
     const execution=Promise.resolve().then(()=>{billingCheck(active);return browserJourney(port,active);});
     if(guarded)browserCompletion=execution.then(result=>result===true,
      error=>browserDiagnostic(error,'ACCEPTANCE')!==null&&browserCleanupConfirmed(error));
     try{return await execution;}
     catch(error){
      const checkpoint=guarded?browserDiagnostic(error,'ACCEPTANCE'):null;
      if(checkpoint){
       if(challengeFlow?.challengeDispatched()&&checkpoint==='BROWSER_PROVIDER_ORIGIN_STRIPE_COM'){
        const witness=browserProviderOrigin(error);
        if(witness?.runId===configuration.runId&&witness.expectedSource===configuration.expectedSource){
         // Private evidence is best effort. Preserve the original refusal even
         // if cancellation or the evidence deadline wins before the file is saved.
         failure??=checkpoint;
         try{
          const persist=Object.getOwnPropertyDescriptor(bootstrap,'persistRefusedStripeOrigin')?.value;
          if(typeof persist==='function')await billingBounded(active,l=>persist.call(bootstrap,witness,l),2000);
         }catch{}
        }
       }
       fail(checkpoint);
      }
      throw error;
     }
    },2700000);
    if(requiredTrial){
     if(browserResult!==true)fail('BROWSER');
     claimTrial('complete','verification');
     if(await step('TRIAL_EVIDENCE',async l=>{
      const prove=dependencies.proveRequiredTrial??(await import('./stripe-billing-required-trial-runtime-evidence.mjs')).proveBillingRequiredTrialRuntime;
      return prove({configuration,bootstrap,cli,fixture},l);
     },180000)!==true)fail('TRIAL_EVIDENCE');
    }else if(invoiceFlow){
     if(browserResult!==true)fail('BROWSER');
     await invoiceFlow.finish();
    }else if(challengeFlow){
     if(browserResult!==true)fail('BROWSER');
     await challengeFlow.verify();
    }else if(retryFlow){
     if(browserResult!==true)fail('BROWSER');
     retryFlow.requireComplete();
    }else if(cancellation){
     if(next!=='complete')fail('CANCELLATION_ORDER');
     if(browserResult!==true)fail('BROWSER');
    }else await verifyInitial();
    billingCheck(active);
   } catch {failure??=lifetime.signal.aborted?'ABORTED':'FAILED';}
   finally {clearTimeout(timer);remove();lifetime.abort();await cleanup();state='CLOSED';finishRun();}
   if(failure)throw billingError(failure);accepted=true;phase='COMPLETE';return resources();
  },
  async close(){lifetime.abort();if(state==='RUNNING')await billingBounded({deadline:Date.now()+120000},()=>finished,120000);else{state='CLOSED';await cleanup();}return resources();},
 });
}
