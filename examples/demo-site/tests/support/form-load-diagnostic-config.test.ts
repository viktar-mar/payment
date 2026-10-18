import test from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {loadConfigFromFile} from 'vite';
// @ts-expect-error Native Node source type erasure.
import normalPlaywright from '../../playwright.config.ts';
// @ts-expect-error Native Node source type erasure.
import diagnosticPlaywright from '../../playwright.form-diagnostic.config.ts';

const root=fileURLToPath(new URL('../..',import.meta.url));
test('actual Vite config isolates diagnostic entry and preserves ordinary entries',async()=>{
  const normal=await loadConfigFromFile({command:'build',mode:'production'},root+'/vite.config.ts',root);
  const diagnostic=await loadConfigFromFile({command:'build',mode:'form-diagnostic'},root+'/vite.config.ts',root);
  assert.deepEqual(normal?.config.build?.rollupOptions?.input,{main:'index.html',paymentReturn:'payment-return.html'});
  assert.deepEqual(diagnostic?.config.build?.rollupOptions?.input,{formDiagnostic:'form-load-diagnostic.html'});
  const aliases=diagnostic?.config.resolve?.alias as Record<string,string>;
  assert.ok(aliases['@generic-payments/sdk']?.endsWith('/frontend/sdk/src/index.ts'));
});
test('normal Playwright discovery explicitly excludes diagnostic and Node-only support tests',()=>{
  assert.ok(Array.isArray(normalPlaywright.testIgnore));
  assert.ok(normalPlaywright.testIgnore.includes('form-load-diagnostic.spec.ts'));
  assert.ok(normalPlaywright.testIgnore.includes('**/support/form-load-diagnostic*.test.ts'));
});
test('separate Playwright config has no auto browser, repetition or artifact capture',()=>{
  assert.equal(diagnosticPlaywright.testMatch,'form-load-diagnostic.spec.ts');
  assert.equal(diagnosticPlaywright.workers,1);assert.equal(diagnosticPlaywright.retries,0);
  assert.equal(diagnosticPlaywright.fullyParallel,false);assert.equal(diagnosticPlaywright.preserveOutput,'never');
  assert.equal(diagnosticPlaywright.use?.trace,'off');assert.equal(diagnosticPlaywright.use?.screenshot,'off');assert.equal(diagnosticPlaywright.use?.video,'off');
  assert.equal(diagnosticPlaywright.webServer,undefined);
});
