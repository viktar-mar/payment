import test from 'node:test';
import assert from 'node:assert/strict';
// @ts-ignore Offline tests execute source.
import {challengeSource,issuerBinding,matchingChallengeRequest,challengeApiRequest} from './stripe-billing-initial-3ds.ts';

const secret='pi_test123_secret_test123',source='src_bound',key='pk_test_fixture';
const intent={object:'payment_intent',livemode:false,id:'pi_test123',client_secret:secret,status:'requires_action',
 next_action:{type:'use_stripe_sdk',use_stripe_sdk:{type:'stripe_3ds2_fingerprint',three_d_secure_2_source:source}}};
const server='11111111-2222-4333-8444-555555555555',acs='11111111-2222-4333-8444-555555555554';
const url='https://hooks.stripe.com/test-only-fixture?bound=1';
const result={state:'challenge_required',ares:{messageType:'ARes',transStatus:'C',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs,acsURL:url}};
const form=(override={})=>new URLSearchParams({creq:Buffer.from(JSON.stringify({messageType:'CReq',messageVersion:'2.2.0',threeDSServerTransID:server,acsTransID:acs,...override})).toString('base64url')}).toString();
test('only the authenticated original test PI fingerprint supplies the challenge source',()=>{
 assert.equal(challengeSource(intent,secret),source);
 for(const changed of [{livemode:true},{id:'pi_other'},{client_secret:'other'},{status:'succeeded'},
  {next_action:{type:'redirect_to_url'}},{next_action:{type:'use_stripe_sdk',use_stripe_sdk:{type:'stripe_3ds2_fingerprint'}}}])
  assert.throws(()=>challengeSource({...intent,...changed},secret));
});
test('issuer navigation is pinned to both transaction IDs and exact ACS URL in the original challenge-required ARes',()=>{
 const original=issuerBinding(result);
 assert.equal(original.url,url);assert.equal(matchingChallengeRequest(original,url,form()),true);
 for(const wrong of [url+'x',url.replace('hooks.stripe.com','foreign.test')])assert.equal(matchingChallengeRequest(original,wrong,form()),false);
 for(const override of [{acsTransID:server},{threeDSServerTransID:acs},{messageType:'CRes'},{messageVersion:'1.0.0'}])
  assert.equal(matchingChallengeRequest(original,url,form(override)),false);
 assert.equal(matchingChallengeRequest(original,url,form()+'&creq=other'),false);
 for(const changed of [{acsURL:'https://hooks.stripe.com.example.test/x'},{acsURL:'https://user@hooks.stripe.com/x'},{transStatus:'Y'},{messageType:'CRes'}])
  assert.throws(()=>issuerBinding({...result,ares:{...result.ares,...changed}}));
 assert.throws(()=>issuerBinding({...result,state:'succeeded'}));
});
test('the SDK can retrieve only the original intent and authenticate only its verified source',()=>{
 const pi='https://api.stripe.com/v1/payment_intents/pi_test123?client_secret='+secret+'&key='+key;
 assert.equal(challengeApiRequest(new URL(pi),'GET',null,key,secret,undefined),'retrieve');
 for(const wrong of [pi.replace('/pi_test123?','/pi_other?'),pi.replace(secret,'foreign'),pi+'&client_secret='+secret,pi.replace(key,'pk_test_other'),pi.replace('/pi_test123?','/pi_test123/confirm?')])
  assert.throws(()=>challengeApiRequest(new URL(wrong),'GET',null,key,secret,undefined));
 const authenticate=new URL('https://api.stripe.com/v1/3ds2/authenticate');
 assert.equal(challengeApiRequest(authenticate,'POST','source='+source+'&key='+key,key,secret,source),'authenticate');
 for(const data of ['source=src_foreign','source='+source+'&source=src_foreign','source='+source+'&key=pk_test_other'])
  assert.throws(()=>challengeApiRequest(authenticate,'POST',data,key,secret,source));
});
