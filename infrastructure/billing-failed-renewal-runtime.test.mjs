import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,readdirSync,rmSync,symlinkSync,existsSync,realpathSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,dirname,resolve} from 'node:path';
import {spawnSync} from 'node:child_process';

// Artificial bytes only. Root audits the complete exporter before executing this fixed shell target.
const runtime='dev/genericpayments/acceptance/runtime/';
const support='dev/genericpayments/platform/support/';
const persistence='dev/genericpayments/platform/provider/adapter/out/persistence/';
const inventory=[
  runtime+'OwnedBillingFirstPaymentRuntime.class',
  ...['Admission','Operations','State','Handler','BillingTimeConfiguration','StartupResources'].map(n=>runtime+`OwnedBillingFirstPaymentRuntime$${n}.class`),
  runtime+'OwnedBillingFirstPaymentScenario.class',runtime+'OwnedBillingFailedRenewalRuntime.class',
  ...['Handler','BillingTimeConfiguration'].map(n=>runtime+`OwnedBillingFailedRenewalRuntime$${n}.class`),
  runtime+'OwnedBillingFailedRenewalOperations.class',runtime+'OwnedBillingFailedRenewalSandboxScenario.class',
  ...['Stage','State'].map(n=>runtime+`OwnedBillingFailedRenewalSandboxScenario$${n}.class`),
  runtime+'OwnedBillingFailedRenewalComponents.class',runtime+'OwnedBillingFailedRenewalFailureArm.class',
  runtime+'OwnedBillingFailedRenewalFailureArm$Remote.class',runtime+'FailureArmEvidence.class',
  runtime+'OwnedBillingFailedRenewalRecovery.class',runtime+'OwnedBillingFailedRenewalRecovery$State.class',
  support+'OwnedSubscriptionBillingTime.class',support+'OwnedFirstRenewalBillingTime.class',
  support+'OwnedFailedRenewalBillingTime.class',support+'OwnedFailedRenewalBillingTime$Candidate.class',
  persistence+'OwnedFailedGraceBillingTime.class',persistence+'OwnedFailedGraceBillingTime$Candidate.class',
  'dev/genericpayments/platform/subscriptions/adapter/out/persistence/OwnedGraceSubscriptionRows.class'
].sort();
const script=resolve(import.meta.dirname,'../backend/export-billing-failed-renewal-runtime.sh');
function fixture(fn){
  const root=mkdtempSync(join(realpathSync(tmpdir()),'failed-renewal-export-'));
  try{const source=join(root,'source'),dest=join(root,'export');
    for(const p of inventory){mkdirSync(dirname(join(source,p)),{recursive:true});writeFileSync(join(source,p),p);}
    fn({root,source,dest,run:(from=source,to=dest)=>spawnSync('sh',[script,from,to],{encoding:'utf8',timeout:5000,maxBuffer:65536})});
  }finally{rmSync(root,{recursive:true,force:true});}
}
function files(root,prefix=''){return readdirSync(join(root,prefix),{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(root,join(prefix,e.name)):[join(prefix,e.name)]).sort();}

test('explicit export contains the genuine runtime, arm and exact read-selector closure only',()=>fixture(({source,dest,run})=>{
  for(const extra of ['OwnedBillingFailedRenewalRuntimeTest.class','OwnedBillingFailedRenewalScenarioIntegrationTest.class','OwnedBillingFirstPaymentScenarioFixture.class'])writeFileSync(join(source,runtime+extra),'must never ship');
  mkdirSync(join(source,'org/junit/jupiter/api'),{recursive:true});writeFileSync(join(source,'org/junit/jupiter/api/Test.class'),'framework');
  assert.equal(run().status,0);assert.deepEqual(files(dest),inventory);
}));
test('every missing class refuses the entire export before creating any destination',()=>{
  for(const required of inventory)fixture(({source,dest,run})=>{rmSync(join(source,required));assert.notEqual(run().status,0,required);assert.equal(existsSync(dest),false,required);});
});
test('an unexpected nested class under any explicit owner refuses publication',()=>{
  for(const owner of inventory.filter(p=>!p.includes('$'))){
    fixture(({source,dest,run})=>{writeFileSync(join(source,owner.replace(/\.class$/,'$Unexpected.class')),'unsafe closure');assert.notEqual(run().status,0,owner);assert.equal(existsSync(dest),false,owner);});
  }
});
test('class symlinks and package symlink ancestors cannot reach an output',()=>{
  fixture(({root,source,dest,run})=>{const last=inventory.at(-1);rmSync(join(source,last));writeFileSync(join(root,'foreign.class'),'foreign');symlinkSync(join(root,'foreign.class'),join(source,last));assert.notEqual(run().status,0);assert.equal(existsSync(dest),false);});
  fixture(({root,source,dest,run})=>{const packagePath=resolve(source,persistence);rmSync(packagePath,{recursive:true});mkdirSync(join(root,'foreign'));symlinkSync(join(root,'foreign'),packagePath);assert.notEqual(run().status,0);assert.equal(existsSync(dest),false);});
  fixture(({root,source,dest,run})=>{const alias=join(root,'source-alias');symlinkSync(source,alias);assert.notEqual(run(alias,dest).status,0);assert.equal(existsSync(dest),false);});
  fixture(({root,source,dest,run})=>{const alias=join(root,'root-alias');symlinkSync(root,alias);assert.notEqual(run(join(alias,'source'),dest).status,0);assert.equal(existsSync(dest),false);});
});
test('existing outputs and destination symlink ancestors retain their original contents',()=>{
  fixture(({dest,run})=>{mkdirSync(dest);writeFileSync(join(dest,'keep'),'keep');assert.notEqual(run().status,0);assert.deepEqual(files(dest),['keep']);assert.equal(readFileSync(join(dest,'keep'),'utf8'),'keep');});
  fixture(({root,source,dest,run})=>{mkdirSync(join(root,'foreign'));symlinkSync(join(root,'foreign'),dest);assert.notEqual(run().status,0);assert.deepEqual(files(join(root,'foreign')),[]);});
  fixture(({root,source,run})=>{mkdirSync(join(root,'foreign'));const alias=join(root,'output-parent');symlinkSync(join(root,'foreign'),alias);assert.notEqual(run(source,join(alias,'export')).status,0);assert.deepEqual(files(join(root,'foreign')),[]);});
});
test('dedicated image chooses the failed entrypoint and leaves ordinary packaging isolated',()=>{
  const source=readFileSync(resolve(import.meta.dirname,'../backend/Dockerfile.billing-failed-renewal'),'utf8');
  const instruction=source.replace(/\\\n\s*/g,' ').split('\n');
  assert.deepEqual(JSON.parse(instruction.find(l=>l.startsWith('ENTRYPOINT ')).slice(11)),['java','-Dloader.path=/app/failed-renewal-runtime','-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFailedRenewalRuntime','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher']);
  assert.ok(source.includes('sh export-billing-failed-renewal-runtime.sh /workspace/target/test-classes /workspace/failed-renewal-runtime'));
  assert.doesNotMatch(readFileSync(resolve(import.meta.dirname,'../backend/Dockerfile'),'utf8'),/failed-renewal-runtime|OwnedBillingFailedRenewalRuntime/);
  assert.doesNotMatch(readFileSync(resolve(import.meta.dirname,'../backend/export-billing-first-payment-runtime.sh'),'utf8'),/OwnedBillingFailedRenewal|FailureArmEvidence|OwnedFailedGraceBillingTime/);
  assert.doesNotMatch(readFileSync(resolve(import.meta.dirname,'../backend/export-billing-first-renewal-runtime.sh'),'utf8'),/OwnedBillingFailedRenewal|FailureArmEvidence|OwnedFailedGraceBillingTime/);
});
