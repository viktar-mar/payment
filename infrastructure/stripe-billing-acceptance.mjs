import {validateBillingAcceptanceOptions} from './stripe-billing-acceptance-options.mjs';
import {createOwnedBillingBootstrap} from './stripe-sandbox-bootstrap.mjs';
import {createBillingCliAdapter} from './stripe-sandbox-cli.mjs';
import {preflightOwnedBilling} from './stripe-sandbox-owned.mjs';
import {billingBounded, billingCheck, billingError} from './stripe-billing-limits.mjs';

/** Inert admission. Native capabilities are constructed only by the explicit, single-use run. */
export async function createOwnedBillingAcceptance(options,dependencies={}) {
 const configuration=validateBillingAcceptanceOptions(options);
 if(!configuration)throw billingError('OPT_IN_REQUIRED');
 let finishRun;const finished=new Promise(resolve=>finishRun=resolve);
 let state='NEW',phase='NEW',failure=null,bootstrap,cli,fixture,cleanupPromise,active,accepted=false,cleanupConfirmed=false;
 const lifetime=new AbortController(),counts={create:0,confirm:0},acks={create:false,confirm:false};
 const resources=()=>Object.freeze({runId:configuration.runId,expectedSource:configuration.expectedSource,scope:'INITIAL_NON_CLOCK',status:state,phase,code:failure,initialAccepted:accepted,full5bAccepted:false,cleanupConfirmed});
 const fail=code=>{failure??=code;lifetime.abort();throw billingError(failure);};
 async function cleanup() {
  if(cleanupPromise)return cleanupPromise;
  cleanupPromise=(async()=>{
   let ok=true;const limits={deadline:Date.now()+120000};
   for(const [object,method] of [[fixture,'close'],[cli,'stopListener'],[bootstrap,'stop']])if(object)try{if(await billingBounded(limits,l=>object[method](l),30000)!==true)ok=false;}catch{ok=false;}
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
    cli=await step('CLI',()=>(dependencies.createCli??createBillingCliAdapter)({configuration,cliDirectory:bootstrap.resources().cliDirectory,verifyForwardTarget:bootstrap.verifyForwardTarget,persistWebhookSecret:bootstrap.persistWebhookSecret}));
    await step('LOGIN',async l=>{const login=dependencies.login??(await import('./stripe-billing-login.mjs')).completeBillingLogin;return login(cli,configuration,active);},780000);
    await step('BINDING',l=>cli.validateBinding(l));await step('LISTENER',l=>cli.startListener(l));
    await step('START',l=>bootstrap.start(l),2700000);
    fixture=await step('FIXTURE',async()=>{const factory=dependencies.createFixture??(await import('./stripe-billing-provision.mjs')).createBillingFixture;return factory({configuration,bootstrap,cli});});
    await step('PROVISION',l=>fixture.provision(l));
    const port=Object.freeze({resources:()=>fixture.resources(),
     beforeDispatch:async(kind,key,identity)=>{
      billingCheck(active);if(failure)fail(failure);
      if(!['create','confirm'].includes(kind)||counts[kind]!==0||(kind==='confirm'&&!acks.create))fail('DISPATCH');
      counts[kind]++;await step('DISPATCH',async l=>{if(await cli.assertBillingListener(l)!==true||await fixture.beforeDispatch(kind,key,identity,l)!==true)fail('DISPATCH');});return true;
     },
     acknowledge:async(kind,identity)=>{
      billingCheck(active);if(failure)fail(failure);
      if(!['create','confirm'].includes(kind)||counts[kind]!==1||acks[kind]||identity?.state==='UNKNOWN')fail('ACK');
      if(await step('ACK',l=>fixture.acknowledge(kind,identity,l))!==true)fail('ACK');acks[kind]=true;return true;
     },
     withOtpCode:async(callback,l)=>{billingCheck(active);return fixture.withOtpCode(callback,{deadline:Math.min(active.deadline,l.deadline),signal:active.signal});},
     acknowledgeCustomer:async(value)=>{billingCheck(active);return fixture.acknowledgeCustomer(value,active);},
    });
    await step('BROWSER',()=>browserJourney(port,active),2700000);
    if(!acks.create||!acks.confirm)fail('ACK');
    const proof=await step('EVIDENCE',async l=>{const prove=dependencies.prove??(await import('./stripe-billing-evidence.mjs')).proveBillingEvidence;return prove({configuration,bootstrap,fixture},l);},120000);
    if(!proof||!['cashVerified','journalVerified','signedCallbacksVerified'].every(k=>proof[k]===true))fail('EVIDENCE');
    await step('GRAPH',async l=>{if(await cli.bindOwnedBillingGraph(proof.graph,l)!==true)fail('EVIDENCE');const p=await cli.observeOwnedBillingGraph(l);if(p?.providerVerified!==true||p.initialCashVerified!==true||p.lineCount!==1||p.allocationCount!==1||p.chargeCount!==1)fail('EVIDENCE');});
    billingCheck(active);
   } catch {failure??=lifetime.signal.aborted?'ABORTED':'FAILED';}
   finally {clearTimeout(timer);remove();lifetime.abort();await cleanup();state='CLOSED';finishRun();}
   if(failure)throw billingError(failure);accepted=true;phase='COMPLETE';return resources();
  },
  async close(){lifetime.abort();if(state==='RUNNING')await billingBounded({deadline:Date.now()+120000},()=>finished,120000);else{state='CLOSED';await cleanup();}return resources();},
 });
}
