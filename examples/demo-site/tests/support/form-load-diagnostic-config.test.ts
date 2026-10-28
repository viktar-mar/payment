import test from 'node:test';
import assert from 'node:assert/strict';
import {readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {matchesGlob} from 'node:path';
import {registerHooks} from 'node:module';
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
  assert.deepEqual(diagnostic?.config.build?.rollupOptions?.input,{formDiagnostic:'form-load-diagnostic.html',formManual:'form-load-manual.html'});
  const aliases=diagnostic?.config.resolve?.alias as Record<string,string>;
  assert.ok(aliases['@generic-payments/sdk']?.endsWith('/frontend/sdk/src/index.ts'));
});
test('normal Playwright discovery explicitly excludes diagnostic and Node-only support tests',()=>{
  assert.ok(Array.isArray(normalPlaywright.testIgnore));
  assert.ok(normalPlaywright.testIgnore.includes('form-load-diagnostic.spec.ts'));
  assert.ok(normalPlaywright.testIgnore.includes('**/support/form-load-diagnostic*.test.ts'));
  assert.ok(normalPlaywright.testIgnore.includes('**/support/form-load-manual*.test.ts'));
  for(const file of ['form-load-manual.test.ts','form-load-manual-build.test.ts'])assert.ok(normalPlaywright.testIgnore.some(
    pattern=>typeof pattern==='string'&&matchesGlob('tests/support/'+file,pattern)));
  assert.ok(normalPlaywright.testIgnore.some(pattern=>typeof pattern==='string'&&matchesGlob(
    'tests/support/form-load-diagnostic-environment.test.ts',pattern)));
});
test('normal Playwright discovery excludes the offline manual snapshot browser regression',async()=>{
  const files=(await readdir(root+'/tests/support')).filter(file=>file.endsWith('-snapshot-browser.test.ts'));
  assert.equal(files.length,1,'one offline snapshot regression must be present');
  assert.ok(Array.isArray(normalPlaywright.testIgnore));
  for(const file of files)assert.ok(normalPlaywright.testIgnore.some(pattern=>typeof pattern==='string'&&matchesGlob(
    'tests/support/'+file,pattern)),'offline_snapshot_is_node_only');
});
test('normal Playwright excludes Node-only support regressions without hiding ordinary journeys or helpers',()=>{
  assert.ok(Array.isArray(normalPlaywright.testIgnore));
  for(const [file,excluded] of [
    ['tests/support/provider-mount-browser.test.ts',true],
    ['tests/support/stripe-sandbox-fixtures.test.ts',true],
    ['tests/support/stripe-sandbox-fixtures.ts',false],
    ['tests/checkout.spec.ts',false],
    ['tests/donations.spec.ts',false],
    ['tests/subscriptions.spec.ts',false],
  ] as const)assert.equal(normalPlaywright.testIgnore.some(pattern=>typeof pattern==='string'&&matchesGlob(file,pattern)),
    excluded,'normal_playwright_discovery: '+file);
});
test('separate Playwright config has no auto browser, repetition or artifact capture',()=>{
  assert.equal(diagnosticPlaywright.testMatch,'form-load-diagnostic.spec.ts');
  assert.equal(diagnosticPlaywright.workers,1);assert.equal(diagnosticPlaywright.retries,0);
  assert.equal(diagnosticPlaywright.fullyParallel,false);assert.equal(diagnosticPlaywright.preserveOutput,'never');
  assert.equal(diagnosticPlaywright.use?.trace,'off');assert.equal(diagnosticPlaywright.use?.screenshot,'off');assert.equal(diagnosticPlaywright.use?.video,'off');
  assert.equal(diagnosticPlaywright.webServer,undefined);
});
test('actual diagnostic and owned spec entrypoints forward the raw manual flag before admission',async()=>{
  const previous=process.env.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC,owned=process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;
  const state={callbacks:[] as (()=>Promise<void>)[],environments:[] as Record<string,unknown>[]};
  const scope=globalThis as typeof globalThis&{__manualAdmissionTest?:typeof state};scope.__manualAdmissionTest=state;
  const moduleUrl=(code:string)=>'data:text/javascript,'+encodeURIComponent(code);
  const stubs:Record<string,string>={
    '@playwright/test':moduleUrl(`const state=globalThis.__manualAdmissionTest;export const chromium={};function test(name,body){state.callbacks.push(body)};test.describe={serial:(name,body)=>body()};test.beforeAll=body=>state.callbacks.push(body);test.afterAll=()=>{};export {test};`),
    '../../../infrastructure/stripe-form-diagnostic.mjs':moduleUrl(`export async function runFormLoadDiagnostic(options){globalThis.__manualAdmissionTest.environments.push(options.env);return {frames:[],exitCode:1}}`),
    '../../../infrastructure/stripe-form-diagnostic-schema.mjs':moduleUrl(`export const formatFormDiagnosticFrame=()=>null;`),
    './support/form-load-diagnostic':moduleUrl(`export const runFormLoadBrowser=()=>{throw Error('forbidden_browser')};`),
    './support/stripe-sandbox-fixtures':moduleUrl(`export const openStripeSandboxFixture=()=>{throw Error('forbidden_fixture')},runSandboxCard=()=>{};`),
    './support/stripe-sandbox-acceptance':moduleUrl(`export const runOwnedSandboxDonations=()=>{},withOwnedCardContexts=()=>{};`),
    '../../../infrastructure/stripe-sandbox-acceptance.mjs':moduleUrl(`export async function openOwnedSandboxAcceptance(options){globalThis.__manualAdmissionTest.environments.push(options.env);throw Error('admission_captured')}`),
  };
  const hooks=registerHooks({resolve(specifier,context,nextResolve){
    if(context.parentURL?.includes('/tests/')&&/\/(form-load-diagnostic|stripe-sandbox)\.spec\.ts/.test(context.parentURL)&&Object.hasOwn(stubs,specifier))
      return {url:stubs[specifier]!,shortCircuit:true};return nextResolve(specifier,context);
  }});
  try{
    process.env.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC='true';process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN='true';
    for(const spec of ['form-load-diagnostic','stripe-sandbox']){
      state.callbacks.length=0;await import(new URL('../'+spec+'.spec.ts?manual-admission-test',import.meta.url).href);
      await assert.rejects(state.callbacks[0]!());
    }
    assert.deepEqual(state.environments.map(env=>env.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC),['true','true']);
  }finally{
    hooks.deregister();delete scope.__manualAdmissionTest;
    if(previous===undefined)delete process.env.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC;else process.env.PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC=previous;
    if(owned===undefined)delete process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN;else process.env.PAYMENT_STRIPE_SANDBOX_OWNED_RUN=owned;
  }
});
