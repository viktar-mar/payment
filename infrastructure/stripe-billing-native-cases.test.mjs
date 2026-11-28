import assert from 'node:assert/strict';
import {test} from 'node:test';
import {failureLocation,readResult,selectCase} from './stripe-billing-native-cases.mjs';

test('only explicit native case names select a fixed JUnit class',()=>{
  assert.equal(selectCase(['method_isolation']).className,'StripeSubscriptionMethodIsolationNativeIT');
  for(const bad of [[],[''],['old'],['__proto__'],['method_isolation','-Dtest=other']])
    assert.throws(()=>selectCase(bad),/NATIVE_CASE_SELECTION/);
});

test('only the selected native class and complete Surefire counters are evidence',()=>{
  const name='StripeSubscriptionMethodIsolationNativeIT';
  const header='<testsuite name="dev.genericpayments.platform.acceptance.'+name+'" tests="1" skipped="0" errors="0" failures="0">';
  assert.deepEqual(readResult(header,name),{tests:1,failures:0,errors:0,skipped:0});
  assert.throws(()=>readResult(header,'DifferentTest'),/NATIVE_RESULT_MISSING/);
  assert.throws(()=>readResult(header.replace('skipped="0"',''),name),/NATIVE_RESULT_MISSING/);
});

test('native failure diagnostics discard raw Maven and provider details',()=>{
  const safe='NATIVE_CASE_FAILED AssertionError StripeSubscriptionMethodIsolationNativeIT.java:142';
  assert.equal(failureLocation(safe+' SECRET_CANARY'),safe);
  const cleanup='NATIVE_CASE_FAILED PORTAL_CONFIGURATION_CLEANUP AssertionError';
  assert.equal(failureLocation(cleanup+' PRIVATE_RESPONSE'),cleanup);
  assert.equal(failureLocation('response: SECRET_CANARY'),'NATIVE_CASE_INCOMPLETE');
  assert.equal(failureLocation('error NATIVE_SANDBOX_BINDING private details'),'NATIVE_SANDBOX_BINDING');
});

test('the scheduled boundary accepts only its provider adapter test class',()=>{
  const {className}=selectCase(['plan_schedule_boundary'],Date.UTC(2027,0,1));
  const suite='dev.genericpayments.platform.provider.adapter.out.stripe.billing.'+className;
  const header='<testsuite name="'+suite+'" tests="1" skipped="0" errors="0" failures="0">';
  assert.deepEqual(readResult(header,className),{tests:1,failures:0,errors:0,skipped:0});
  assert.throws(()=>readResult(header.replace('provider.adapter.out.stripe.billing','acceptance'),className),/NATIVE_RESULT_MISSING/);
});

test('monthly boundary needs the same UTC day in the previous month throughout the launch window',()=>{
  const check=(at,name='plan_schedule_boundary')=>selectCase([name],Date.parse(at));
  for(const at of ['2027-03-28T12:00Z','2028-03-29T12:00Z','2027-02-28T12:00Z','2027-04-30T12:00Z',
    '2027-05-31T23:59Z','2027-01-31T12:00Z']) {
    assert.doesNotThrow(()=>check(at));
  }
  for(const at of ['2027-03-29T12:00Z','2027-03-30T12:00Z','2027-03-31T12:00Z','2028-03-30T12:00Z',
    '2027-05-31T12:00Z','2027-03-28T23:52Z']) {
    assert.throws(()=>check(at),/^Error: NATIVE_SCHEDULE_MONTH_END$/);
    assert.doesNotThrow(()=>check(at,'plan_schedule'));
  }
});

test('launch selection uses the UTC clock before any native resource setup',t=>{
  t.mock.timers.enable({apis:['Date'],now:Date.parse('2027-03-31T12:00Z')});
  assert.throws(()=>selectCase(['plan_schedule_boundary']),/^Error: NATIVE_SCHEDULE_MONTH_END$/);
});
