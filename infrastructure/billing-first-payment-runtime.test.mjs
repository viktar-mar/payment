import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,readdirSync,rmSync,symlinkSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,dirname,resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
const runtime='dev/genericpayments/acceptance/runtime/';
const inventory=[runtime+'OwnedBillingFirstPaymentRuntime.class',...['Admission','Operations','State','Handler','BillingTimeConfiguration','StartupResources'].map(n=>runtime+`OwnedBillingFirstPaymentRuntime$${n}.class`),runtime+'OwnedBillingFirstPaymentScenario.class','dev/genericpayments/platform/support/OwnedSubscriptionBillingTime.class'].sort();
const script=resolve(import.meta.dirname,'../backend/export-billing-first-payment-runtime.sh');
function fixture(fn){const root=mkdtempSync(join(tmpdir(),'first-payment-export-'));try{const source=join(root,'source'),dest=join(root,'export');for(const p of inventory){mkdirSync(dirname(join(source,p)),{recursive:true});writeFileSync(join(source,p),p);}fn({root,source,dest,run:()=>spawnSync('sh',[script,source,dest],{encoding:'utf8'})});}finally{rmSync(root,{recursive:true,force:true});}}
function files(root,prefix=''){return readdirSync(join(root,prefix),{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(root,join(prefix,e.name)):[join(prefix,e.name)]).sort();}
test('export includes only runnable classes even with JUnit fixtures and unrelated support present',()=>fixture(({source,dest,run})=>{
  writeFileSync(join(source,runtime+'OwnedBillingFirstPaymentRuntimeTest.class'),'JUnit');
  writeFileSync(join(source,'dev/genericpayments/platform/support/StripeBillingClockConfirmationCompositionFixture.class'),'SpringTest');
  assert.equal(run().status,0);assert.deepEqual(files(dest),inventory);for(const p of inventory)assert.equal(readFileSync(join(dest,p),'utf8'),p);
}));
test('missing or extra runnable classes fail before publishing any export',()=>{
  fixture(({source,dest,run})=>{rmSync(join(source,runtime+'OwnedBillingFirstPaymentRuntime$StartupResources.class'));assert.notEqual(run().status,0);assert.equal(existsSync(dest),false);});
  fixture(({source,dest,run})=>{writeFileSync(join(source,runtime+'OwnedBillingFirstPaymentScenario$Unexpected.class'),'unreviewed');assert.notEqual(run().status,0);assert.equal(existsSync(dest),false);});
});
test('class symlinks, symlink ancestors, and preexisting exports are rejected',()=>{
  fixture(({root,source,dest,run})=>{rmSync(join(source,inventory[0]));writeFileSync(join(root,'foreign'),'foreign');symlinkSync(join(root,'foreign'),join(source,inventory[0]));assert.notEqual(run().status,0);assert.equal(existsSync(dest),false);});
  fixture(({root,source,dest,run})=>{const support=join(source,'dev/genericpayments/platform/support');rmSync(support,{recursive:true});mkdirSync(join(root,'foreign'));writeFileSync(join(root,'foreign/OwnedSubscriptionBillingTime.class'),'foreign');symlinkSync(join(root,'foreign'),support);assert.notEqual(run().status,0);assert.equal(existsSync(dest),false);});
  fixture(({dest,run})=>{mkdirSync(dest);writeFileSync(join(dest,'keep'),'keep');assert.notEqual(run().status,0);assert.equal(readFileSync(join(dest,'keep'),'utf8'),'keep');});
});

test('dedicated Docker launch compiles and exports the named runtime beside the production jar', () => {
  // Static deployment contract: no Docker build or application invocation here.
  const source = readFileSync(resolve(import.meta.dirname, '../backend/Dockerfile.billing-first-payment'), 'utf8');
  const instruction = source.replace(/\\\n\s*/g, ' ').split('\n');
  const entrypoint = JSON.parse(instruction.find(line => line.startsWith('ENTRYPOINT ')).slice('ENTRYPOINT '.length));
  assert.deepEqual(entrypoint, ['java','-Dloader.path=/app/initial-runtime','-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFirstPaymentRuntime','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher']);
  assert.ok(instruction.some(line => line.startsWith('RUN mvn ') && line.includes('-DskipTests package') && line.includes('sh export-billing-first-payment-runtime.sh /workspace/target/test-classes /workspace/initial-runtime')));
  assert.ok(instruction.some(line => /^COPY --from=build .*\/workspace\/initial-runtime \/app\/initial-runtime$/.test(line)));
  assert.ok(instruction.some(line => /^COPY --from=build .*\/workspace\/target\/payment-platform-backend-\*\.jar app.jar$/.test(line)));
  const normal = readFileSync(resolve(import.meta.dirname, '../backend/Dockerfile'), 'utf8');
  assert.doesNotMatch(normal, /initial-runtime|OwnedBillingFirstPaymentRuntime/);
});
