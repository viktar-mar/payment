import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,readdirSync,rmSync,symlinkSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,dirname,resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
const runtime='dev/genericpayments/acceptance/runtime/';
const inventory=[runtime+'OwnedBillingFirstPaymentRuntime.class',...['Admission','Operations','State','Handler','BillingTimeConfiguration','StartupResources'].map(n=>runtime+`OwnedBillingFirstPaymentRuntime$${n}.class`),runtime+'OwnedBillingFirstPaymentScenario.class',runtime+'OwnedBillingFirstRenewalScenario.class',runtime+'OwnedBillingFirstRenewalRuntime.class',...['Operations','Handler','BillingTimeConfiguration'].map(n=>runtime+`OwnedBillingFirstRenewalRuntime$${n}.class`),'dev/genericpayments/platform/support/OwnedSubscriptionBillingTime.class','dev/genericpayments/platform/support/OwnedFirstRenewalBillingTime.class'].sort();
const script=resolve(import.meta.dirname,'../backend/export-billing-first-renewal-runtime.sh');
function fixture(fn){const root=mkdtempSync(join(tmpdir(),'first-renewal-export-'));try{const source=join(root,'source'),dest=join(root,'export');for(const p of inventory){mkdirSync(dirname(join(source,p)),{recursive:true});writeFileSync(join(source,p),p);}fn({root,source,dest,run:()=>spawnSync('sh',[script,source,dest],{encoding:'utf8'})});}finally{rmSync(root,{recursive:true,force:true});}}
function files(root,prefix=''){return readdirSync(join(root,prefix),{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(root,join(prefix,e.name)):[join(prefix,e.name)]).sort();}
test('new named export excludes hosted fixtures and unrelated test classes',()=>fixture(({source,dest,run})=>{
  writeFileSync(join(source,runtime+'OwnedBillingFirstRenewalRuntimeTest.class'),'JUnit');
  writeFileSync(join(source,runtime+'OwnedBillingFirstPaymentScenarioFixture.class'),'SpringTest');
  assert.equal(run().status,0);assert.deepEqual(files(dest),inventory);
}));
test('missing or extra named classes cannot publish an export',()=>{
  fixture(({source,dest,run})=>{rmSync(join(source,runtime+'OwnedBillingFirstRenewalRuntime$Handler.class'));assert.notEqual(run().status,0);assert.equal(existsSync(dest),false);});
  fixture(({source,dest,run})=>{writeFileSync(join(source,runtime+'OwnedBillingFirstRenewalScenario$Unexpected.class'),'unsafe');assert.notEqual(run().status,0);assert.equal(existsSync(dest),false);});
});
test('export rejects symlink ancestors and preexisting output',()=>{
  fixture(({root,source,dest,run})=>{const support=join(source,'dev/genericpayments/platform/support');rmSync(support,{recursive:true});mkdirSync(join(root,'foreign'));symlinkSync(join(root,'foreign'),support);assert.notEqual(run().status,0);assert.equal(existsSync(dest),false);});
  fixture(({dest,run})=>{mkdirSync(dest);writeFileSync(join(dest,'keep'),'keep');assert.notEqual(run().status,0);assert.equal(readFileSync(join(dest,'keep'),'utf8'),'keep');});
});
test('dedicated image selects only new explicit entrypoint and ordinary image remains isolated',()=>{
  const source=readFileSync(resolve(import.meta.dirname,'../backend/Dockerfile.billing-first-renewal'),'utf8');
  const instruction=source.replace(/\\\n\s*/g,' ').split('\n');
  assert.deepEqual(JSON.parse(instruction.find(l=>l.startsWith('ENTRYPOINT ')).slice(11)),['java','-Dloader.path=/app/renewal-runtime','-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFirstRenewalRuntime','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher']);
  assert.ok(source.includes('sh export-billing-first-renewal-runtime.sh /workspace/target/test-classes /workspace/renewal-runtime'));
  assert.doesNotMatch(readFileSync(resolve(import.meta.dirname,'../backend/Dockerfile'),'utf8'),/renewal-runtime|OwnedBillingFirstRenewalRuntime/);
});
