import {createOwnedFormDiagnosticBootstrap} from './stripe-sandbox-bootstrap.mjs';
import {formDiagnosticOptIn} from './stripe-sandbox-owned.mjs';
import {createFormDiagnosticProvider} from './stripe-form-diagnostic-provider.mjs';
import {createFormDiagnosticProvisioner,createManualFormDiagnosticProvisioner} from './stripe-form-diagnostic-provision.mjs';
import {validateManualFormOptions} from './stripe-manual-form-options.mjs';
const fail=()=>{throw new Error('stripe_form_diagnostic_RUNTIME_UNCONFIRMED');};

/** Inert private runtime. Only these three capabilities cross to the controller. */
export async function createFormDiagnosticRuntime(options,dependencies={}) {
  return createRuntime(options,dependencies,false);
}
export async function createManualFormDiagnosticRuntime(options,dependencies={}) {
  return createRuntime(options,dependencies,true);
}
async function createRuntime(options,dependencies,manual) {
  let selected,manualOptions,browserOrigin;
  try {
    if(manual){manualOptions=validateManualFormOptions(options);if(!manualOptions)fail();options=manualOptions;browserOrigin='http://127.0.0.1:'+manualOptions.manualPort;}
    else if(!formDiagnosticOptIn(options?.env)||Object.keys(options).sort().join(',')!=='daemon,env,expectedSource,ports,runId')fail();
    selected=Object.freeze({env:Object.freeze({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}),
      runId:options.runId,expectedSource:options.expectedSource,daemon:options.daemon,ports:Object.freeze({...options.ports})});
    // Validate all configuration before even reading a dependency accessor.
    await createOwnedFormDiagnosticBootstrap(selected,{});
  }catch{fail();}
  const now=dependencies.now??Date.now,bootstrapDependencies=dependencies.bootstrap??{},providerDependencies=dependencies.provider??{},provisionDependencies=dependencies.provision??{};
  const setTimer=dependencies.setTimeout??setTimeout,clearTimer=dependencies.clearTimeout??clearTimeout;
  const bootstrap=await createOwnedFormDiagnosticBootstrap(selected,bootstrapDependencies);
  let prepareUsed=false,proofUsed=false,closeUsed=false,busy=false,failed=false,originalDeadline=null,provider=null,provisioner=null,reference=null,ready=false;
  function limitsCheck(limits,cleanup=false) {
    if(!Number.isSafeInteger(limits?.deadline)||limits.deadline<=now()||limits.signal?.aborted||(limits.signal!==undefined&&!(limits.signal instanceof AbortSignal)))fail();
    if(originalDeadline===null){if(cleanup||limits.deadline>now()+900000)fail();originalDeadline=limits.deadline;}
    if(cleanup?limits.deadline>originalDeadline+120000:limits.deadline!==originalDeadline)fail();
  }
  async function run(limits,body,cleanup=false) {
    if(busy){failed=true;fail();}busy=true;
    let timer,abort;
    try{
      limitsCheck(limits,cleanup);
      const stopped=new Promise((_,reject)=>{abort=()=>{failed=true;reject(new Error('stripe_form_diagnostic_RUNTIME_UNCONFIRMED'));};
        limits.signal?.addEventListener('abort',abort,{once:true});timer=setTimer(abort,Math.max(1,limits.deadline-now()));if(limits.signal?.aborted)abort();});
      const value=await Promise.race([Promise.resolve().then(body),stopped]);limitsCheck(limits,cleanup);return value;
    }catch{failed=true;fail();}finally{clearTimer(timer);limits?.signal?.removeEventListener('abort',abort);busy=false;}
  }
  return Object.freeze({
    prepare:async limits=>{if(prepareUsed){failed=true;fail();}prepareUsed=true;return run(limits,async()=>{
      if(failed||closeUsed)fail();
      await bootstrap.prepare(limits);
      await bootstrap.withProviderCredentials(keys=>{provider=createFormDiagnosticProvider(keys,providerDependencies);return true;});
      if(await provider.verifyBinding(limits)!==true)fail();
      await bootstrap.start(limits);
      provisioner=manual
        ?await createManualFormDiagnosticProvisioner({env:manualOptions.env,bootstrap,browserOrigin},provisionDependencies)
        :await createFormDiagnosticProvisioner({env:selected.env,bootstrap},provisionDependencies);
      const prepared=await provisioner.prepare(limits);reference=prepared.reference;
      if(await provider.verifyIntent(reference,limits)!==true)fail();
      await bootstrap.verify(limits);if(failed)fail();ready=true;
      const assetEndpoint='http://127.0.0.1:'+selected.ports.demo;
      return Object.freeze({runId:selected.runId,expectedSource:selected.expectedSource,endpoint:manual?browserOrigin:assetEndpoint,...(manual?{assetEndpoint}:{}),session:prepared.session});
    });},
    verifyNoConfirmation:async limits=>{if(proofUsed){failed=true;fail();}proofUsed=true;return run(limits,async()=>{
      if(closeUsed||!ready||!provider||!provisioner)fail();
      await bootstrap.verify(limits);
      const proof=await provisioner.verifyNoConfirmation(limits);
      if(proof.reference!==reference||await provider.verifyIntent(reference,limits)!==true)fail();return true;
    });},
    close:async limits=>{if(closeUsed)fail();closeUsed=true;return run(limits,async()=>{
      let ok=true;
      if(provisioner)try{if(await provisioner.close(limits)!==true)ok=false;}catch{ok=false;}
      if(provider)try{if(await provider.close(limits)!==true)ok=false;}catch{ok=false;}
      try{if(await bootstrap.stop(limits)!==true)ok=false;}catch{ok=false;}
      if(!ok)fail();return true;
    },true);},
  });
}
