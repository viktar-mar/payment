import test from 'node:test';
import assert from 'node:assert/strict';
// @ts-ignore Offline tests execute source.
import {challengeSource,issuerBinding,matchingChallengeRequest,challengeApiRequest} from './stripe-billing-initial-3ds.ts';
// @ts-ignore Native Node executes the same terminal decoder as the launcher.
import {challengeTerminalLine} from './stripe-billing-initial-3ds-terminal.ts';

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
test('binds an opaque Stripe source to both SDK requests without guessing its prefix',()=>{
 const action=intent.next_action;
 const withSource=(value:unknown)=>({...intent,next_action:{...action,
  use_stripe_sdk:{...action.use_stripe_sdk,three_d_secure_2_source:value}}});
 const opaque='fingerprint_fixture-v2';
 const bound=challengeSource(withSource(opaque),secret);
 assert.equal(bound,opaque);
 for(const [endpoint,kind] of [['authenticate','authenticate'],['challenge_complete','complete']]){
  const route=new URL('https://api.stripe.com/v1/3ds2/'+endpoint);
  const body=(source:string)=>new URLSearchParams({source,key}).toString();
  assert.equal(challengeApiRequest(route,'POST',body(opaque),key,secret,bound),kind);
  assert.throws(()=>challengeApiRequest(route,'POST',body('fingerprint_other'),key,secret,bound));
  assert.throws(()=>challengeApiRequest(route,'POST',body(opaque)+'&source='+opaque,key,secret,bound));
 }
 for(const invalid of ['',42,{},'fingerprint/fixture','fingerprint\n','fingerprint&key=other','a'.repeat(205)])
  assert.throws(()=>challengeSource(withSource(invalid),secret));
});
test('a rejected challenge source reports only the first fixed field category',()=>{
 const action=intent.next_action,sdk=action.use_stripe_sdk;
 const withSdk=(value:unknown)=>({...intent,next_action:{...action,use_stripe_sdk:value}});
 const cases:[unknown,string][]=[
  [[], 'OBJECT'],
  [{...intent,object:'private_object'}, 'OBJECT'],
  [{...intent,id:'private_identity'}, 'IDENTITY'],
  [{...intent,client_secret:'private_secret'}, 'SECRET'],
  [{...intent,livemode:true}, 'MODE'],
  [{...intent,livemode:undefined}, 'MODE'],
  [{...intent,status:'private_status'}, 'STATE'],
  [{...intent,next_action:null}, 'ACTION'],
  [{...intent,next_action:{...action,type:'private_type'}}, 'ACTION'],
  [withSdk(null), 'SDK'],
  [withSdk({...sdk,type:'stripe_3ds2_challenge'}), 'SDK_CHALLENGE'],
  [withSdk({...sdk,type:'private_sdk_type'}), 'SDK_TYPE'],
  [withSdk({...sdk,three_d_secure_2_source:undefined}), 'VALUE_MISSING'],
  [withSdk({...sdk,three_d_secure_2_source:{token:'private_value'}}), 'VALUE_TYPE'],
  [withSdk({...sdk,three_d_secure_2_source:''}), 'VALUE_LENGTH'],
  [withSdk({...sdk,three_d_secure_2_source:'x'.repeat(205)}), 'VALUE_LENGTH'],
  [withSdk({...sdk,three_d_secure_2_source:'private/value'}), 'VALUE_ALPHABET'],
 ];
 for(const [value,phase] of cases){
  let checkpoint:string|undefined;
  assert.throws(()=>challengeSource(value,secret,code=>{checkpoint=code;}),/^Error: billing_BROWSER$/);
  assert.equal(checkpoint,'BROWSER_CHALLENGE_SOURCE_WIRE_'+phase);
  const line='owned_initial_3ds_terminal phase=DISPATCH code='+checkpoint+' cleanup=CONFIRMED';
  assert.equal(challengeTerminalLine(line),line);
 }
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
