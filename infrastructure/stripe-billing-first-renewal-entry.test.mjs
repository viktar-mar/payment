import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const read=path=>readFile(new URL('../'+path,import.meta.url),'utf8');
test('combined browser entry is opt-in, initial browser reused, no default discovery or diagnostics artifacts',async()=>{
 const entry=await read('examples/demo-site/tests/stripe-billing-first-renewal.spec.ts');
 assert.match(entry,/snapshotBillingFirstRenewalEnvironment/);assert.match(entry,/createOwnedBillingFirstRenewal/);assert.match(entry,/selectedCase:'initial_and_first_renewal'/);assert.match(entry,/FIRST_RENEWAL_CONTROL_PORT/);assert.match(entry,/FIRST_RENEWAL_FROZEN_TIME/);assert.match(entry,/runFirstPaymentBrowser\(browser, port, scoped\)/);assert.match(entry,/renewalAccepted/);
 assert.ok(entry.indexOf('test.beforeAll')<entry.indexOf('await createOwnedBillingFirstRenewal'));assert.doesNotMatch(entry.slice(0,entry.indexOf('test.beforeAll')),/\.run\(/);
 const ordinary=await read('examples/demo-site/playwright.config.ts'),offline=await read('examples/demo-site/playwright.offline.config.ts'),config=await read('examples/demo-site/playwright.stripe-billing-first-renewal.config.ts');
 assert.match(ordinary,/testIgnore:.*"stripe-billing-first-renewal.spec.ts"/);assert.match(offline,/testMatch:'stripe-return-offline.spec.ts'/);
 assert.match(config,/testMatch:'stripe-billing-first-renewal.spec.ts'/);assert.match(config,/workers:1/);assert.match(config,/retries:0/);assert.match(config,/preserveOutput:'never'/);assert.match(config,/trace:'off',screenshot:'off',video:'off'/);assert.doesNotMatch(config,/webServer|globalSetup/);
 const reporter=await read('examples/demo-site/tests/support/stripe-billing-first-renewal-reporter.ts');assert.match(reporter,/FirstPaymentReporter/);assert.match(reporter,/owned_initial_and_first_renewal/);assert.doesNotMatch(reporter,/\.errors|\.error|stack|attachments/);
});
test('combined image selects only exported renewal runtime and preserves original image',async()=>{
 const renewal=await read('backend/Dockerfile.billing-first-renewal'),initial=await read('backend/Dockerfile.billing-first-payment');
 assert.match(renewal,/-Dloader.path=\/app\/renewal-runtime/);assert.match(renewal,/-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFirstRenewalRuntime/);assert.match(initial,/-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFirstPaymentRuntime/);
});
test('combined failure diagnostics identify combined scope and never claim acceptance',async()=>{
 const api=await import('./stripe-billing-first-payment.mjs');assert.equal(typeof api.formatBillingFirstRenewalDiagnostic,'function');
 const result={scope:'OWNED_INITIAL_AND_FIRST_RENEWAL',status:'CLOSED',initialAccepted:false,renewalAccepted:false,code:'BROWSER',diagnostic:'BROWSER_PAGE',cleanupConfirmed:true};
 const line=api.formatBillingFirstRenewalDiagnostic(result);assert.equal(line,'owned_initial_and_first_renewal_diagnostic code=BROWSER_PAGE cleanup=CONFIRMED');assert.equal(api.parseBillingFirstRenewalDiagnosticLine(line),line);assert.equal(api.parseBillingFirstPaymentDiagnosticLine(line),null);
 assert.equal(api.formatBillingFirstRenewalDiagnostic({...result,renewalAccepted:true}),null);assert.equal(api.formatBillingFirstRenewalDiagnostic({...result,scope:'OWNED_INITIAL_PAYMENT'}),null);assert.equal(api.parseBillingFirstRenewalDiagnosticLine(line+' secret'),null);
});
test('runtime ownership binds exact distinct entrypoints and excludes the opposite mode',async()=>{
 const api=await import('./stripe-sandbox-ownership.mjs');
 const entry=['java','-Dloader.path=/app/renewal-runtime','-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFirstRenewalRuntime','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher'];
 const value={Entrypoint:entry,Cmd:null,RuntimeSource:true,RuntimeIdentity:true};
 for(const kind of ['image','container']){assert.doesNotThrow(()=>api.proveFirstRenewalRuntime(value,kind));assert.throws(()=>api.proveFirstPaymentRuntime(value,kind));assert.throws(()=>api.proveFirstRenewalRuntime({...value,Cmd:['foreign']},kind));assert.throws(()=>api.proveFirstRenewalRuntime({...value,[kind==='image'?'RuntimeSource':'RuntimeIdentity']:false},kind));}
 assert.match(api.firstRenewalContainerFormat({PAYMENT_STRIPE_BILLING_FIRST_RENEWAL:'true'}),/PAYMENT_STRIPE_BILLING_FIRST_PAYMENT/);assert.match(api.firstPaymentContainerFormat({PAYMENT_STRIPE_BILLING_FIRST_PAYMENT:'true'}),/PAYMENT_STRIPE_BILLING_FIRST_RENEWAL/);
});
