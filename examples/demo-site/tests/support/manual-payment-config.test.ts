import test from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {matchesGlob} from 'node:path';
import {loadConfigFromFile} from 'vite';
// @ts-expect-error Native Node source type erasure.
import normal from '../../playwright.config.ts';
const root=fileURLToPath(new URL('../..',import.meta.url));
test('manual payment has one isolated source SDK graph and manifest; old modes keep their entries',async()=>{
  const manual=await loadConfigFromFile({command:'build',mode:'manual-payment'},root+'/vite.config.ts',root);
  assert.deepEqual(manual?.config.build?.rollupOptions?.input,{manualPayment:'manual-payment.html'});
  assert.equal(manual?.config.build?.manifest,true);
  assert.ok((manual?.config.resolve?.alias as Record<string,string>)['@generic-payments/sdk']?.endsWith('/frontend/sdk/src/index.ts'));
  const ordinary=await loadConfigFromFile({command:'build',mode:'production'},root+'/vite.config.ts',root);
  assert.deepEqual(ordinary?.config.build?.rollupOptions?.input,{main:'index.html',paymentReturn:'payment-return.html'});
  const diagnostic=await loadConfigFromFile({command:'build',mode:'form-diagnostic'},root+'/vite.config.ts',root);
  assert.deepEqual(diagnostic?.config.build?.rollupOptions?.input,{formDiagnostic:'form-load-diagnostic.html',formManual:'form-load-manual.html'});
});
test('actual normal Playwright config excludes all manual payment support while retaining ordinary journeys',()=>{
  for(const [file,ignored] of [['support/manual-payment.test.ts',true],['support/manual-payment-build.test.ts',true],['support/manual-payment-config.test.ts',true],['checkout.spec.ts',false],['donations.spec.ts',false],['subscriptions.spec.ts',false]] as const)
    assert.equal((normal.testIgnore as string[]).some(pattern=>matchesGlob('tests/'+file,pattern)),ignored,file);
});
