import test,{before,after} from 'node:test';import assert from 'node:assert/strict';
import {opts,id,original,initial,responses,methods} from './fixtures/failed-renewal-descriptors.mjs';
import {validateBillingFailedRenewalOptions as validate,billingFailedRenewalOptIn as enabled,snapshotBillingFailedRenewalEnvironment as snapshot} from './stripe-billing-failed-renewal-options.mjs';
import {validateFailedRenewalStage} from './stripe-billing-failed-renewal-response.mjs';
import {billingFirstRenewalOptIn,snapshotBillingFirstRenewalEnvironment} from './stripe-billing-first-renewal-options.mjs';
import {billingFirstPaymentOptIn,snapshotBillingFirstPaymentEnvironment} from './stripe-billing-first-payment-options.mjs';
import {billingAcceptanceExcluded,billingAcceptanceOptIn} from './stripe-billing-acceptance-options.mjs';
import {manualFormOptIn} from './stripe-manual-form-options.mjs';
import {manualPaymentOptIn} from './stripe-manual-payment-options.mjs';
import {manualRefundOptIn} from './stripe-manual-refund-options.mjs';
import {manualRefundRemainderOptIn,manualRefundRemainderExcluded} from './stripe-manual-refund-remainder-options.mjs';
import {remainingOnlyOptIn,remainingOnlyExcluded} from './stripe-sandbox-remaining-options.mjs';
import {proveFailedRenewalRuntime,proveFirstPaymentRuntime,proveFirstRenewalRuntime,failedRenewalContainerFormat,firstPaymentContainerFormat,firstRenewalContainerFormat} from './stripe-sandbox-ownership.mjs';
const saved=globalThis.fetch;let escaped=0;before(()=>{globalThis.fetch=()=>{escaped++;throw Error('blocked');};});after(()=>{globalThis.fetch=saved;assert.equal(escaped,0);});
const budget={clock:1,customer:1,create:1,confirm:1,advance:5,declineAttach:1,declineUpdate:1,setup:1,method:1,recoveryConfirm:1};
test('closed case, fixed persisted budget and snapshot without reading other process values',()=>{
 const o=opts();assert.deepEqual(validate(o).budget,budget);assert.equal(validate(o).protocol,'SERVER_CONFIRM_V2');assert.deepEqual(validate(validate(o)),validate(o));
 Object.defineProperty(o.env,'SECRET',{get(){assert.fail();}});assert.deepEqual(snapshot(o.env),opts().env);
});
for(const mutate of [o=>o.selectedCase='initial_and_first_renewal',o=>o.provider='stripe',o=>o.budget={...budget,advance:4},o=>o.budget={...budget,post:1},o=>o.controlPort=o.ports.demo,o=>o.runId='031b5ae7-6603-4339-b1b3-77231291ff34',o=>o.expectedSource='e'.repeat(39),o=>o.approvedBinding.apiVersion='latest',o=>o.env.DEBUG='*',o=>Object.setPrototypeOf(o.ports,{extra:true}),o=>Object.defineProperty(o.env,'PAYMENT_STRIPE_BILLING_FAILED_RENEWAL',{get(){assert.fail();}}),o=>Object.defineProperty(o,'controlPort',{get(){assert.fail();}}),o=>o[Symbol()]=true])test('options reject unexpected authority',()=>{const o=opts();mutate(o);assert.equal(validate(o),null);});
test('legacy gates reject active new authority, new gate rejects every other purpose',()=>{
 for(const [flag,gate,snap] of [['PAYMENT_STRIPE_BILLING_FIRST_RENEWAL',billingFirstRenewalOptIn,snapshotBillingFirstRenewalEnvironment],['PAYMENT_STRIPE_BILLING_FIRST_PAYMENT',billingFirstPaymentOptIn,snapshotBillingFirstPaymentEnvironment],['PAYMENT_STRIPE_BILLING_ACCEPTANCE',billingAcceptanceOptIn]]){
  const mixed={...opts().env,[flag]:'true'};assert.equal(gate(mixed),false);if(snap)assert.equal(snap(mixed),null);assert.equal(enabled(mixed),false);
 }
 assert.equal(billingAcceptanceExcluded(opts().env),false);
 for(const flag of ['PAYMENT_STAGE4_TEST_RUNTIME','PAYMENT_STRIPE_ACCEPTANCE','PAYMENT_STRIPE_NATIVE_ACCEPTANCE',...['ALLOW_TEST_OPERATIONS','FULL_RUN','OWNED_RUN','REMAINING_ONLY','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT','MANUAL_3DS','MANUAL_REFUND','MANUAL_REFUND_REMAINDER'].map(k=>'PAYMENT_STRIPE_SANDBOX_'+k)])assert.equal(snapshot({...opts().env,[flag]:'true'}),null);
});
function check(vs){let prev;for(const [i,v] of vs.entries())prev=validateFailedRenewalStage(methods[i],v,original(),initial(),opts(),prev);return prev;}
test('old native entry modes reject the failed flag without losing their original opt-in',()=>{
 for(const [gate,flags] of [[manualFormOptIn,['MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY']],[manualPaymentOptIn,['MANUAL_PAYMENT','ALLOW_TEST_OPERATIONS']],[manualRefundOptIn,['MANUAL_REFUND','ALLOW_TEST_OPERATIONS']],[manualRefundRemainderOptIn,['MANUAL_REFUND_REMAINDER','ALLOW_TEST_OPERATIONS']],[remainingOnlyOptIn,['ALLOW_TEST_OPERATIONS','OWNED_RUN','REMAINING_ONLY']]]){
  const env=Object.fromEntries(['ENABLED',...flags].map(k=>['PAYMENT_STRIPE_SANDBOX_'+k,'true']));
  assert.equal(gate(env),true);assert.equal(gate({...env,PAYMENT_STRIPE_BILLING_FAILED_RENEWAL:'true'}),false);
 }
 for(const gate of [remainingOnlyExcluded,manualRefundRemainderExcluded])assert.equal(gate(opts().env),false);
});
test('only the dedicated source-bound failed image/container entrypoint proves ownership',()=>{
 const Entrypoint=['java','-Dloader.path=/app/failed-renewal-runtime','-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFailedRenewalRuntime','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher'];
 for(const kind of ['image','container']){
  const v={Entrypoint,Cmd:null,RuntimeSource:true,RuntimeIdentity:true};assert.doesNotThrow(()=>proveFailedRenewalRuntime(v,kind));
  assert.throws(()=>proveFirstPaymentRuntime(v,kind));assert.throws(()=>proveFirstRenewalRuntime(v,kind));
  assert.throws(()=>proveFailedRenewalRuntime({...v,[kind==='image'?'RuntimeSource':'RuntimeIdentity']:false},kind));
 }
 const name='PAYMENT_STRIPE_BILLING_FAILED_RENEWAL';
 for(const format of [firstPaymentContainerFormat,firstRenewalContainerFormat])assert.ok(format({}).includes(name));
 const failed=failedRenewalContainerFormat({[name]:'true'});assert.ok(failed.includes('PAYMENT_STRIPE_BILLING_FIRST_RENEWAL')&&failed.includes('PAYMENT_STRIPE_BILLING_FIRST_PAYMENT'));
});
test('all exact cumulative producer descriptors accepted; access, session, action and permit are local',()=>{assert.equal(check(responses()).phase,'RECOVERED_SETTLED_LOCAL');});
for(const [i,change] of [[0,v=>v.source_sha='b'.repeat(40)],[0,v=>v.original_frozen_time++],[0,v=>v.renewal_invoice_id=v.initial_invoice_id],[1,v=>v.pending_graph_invocation_id=id(77)],[2,v=>v.failed_allocation_id=v.initial_allocation_id],[3,v=>v.grace_target++],[4,v=>v.access_valid_until=v.grace_deadline],[6,v=>v.access_allowed=false],[7,v=>v.session.extra=true],[7,v=>Object.setPrototypeOf(v.session,{target:true})],[8,v=>Object.defineProperty(v,'state',{get(){throw Error('do not invoke');}})],[9,v=>v.recovery_bill_id=v.initial_bill_id],[10,v=>v.recovery_confirm_operation_version=1],[11,v=>v.recovery_publication_reservation_id=id(66)],[11,v=>v.recovery_observation_sequence=-1]])test(methods[i]+' rejects changed type/shape/lineage',()=>{const vs=responses();change(vs[i]);assert.throws(()=>check(vs));});
