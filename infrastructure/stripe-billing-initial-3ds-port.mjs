import {billingCheck} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';
import {exactRecord} from './stripe-billing-failed-renewal-response.mjs';

/** Each successful durable receipt enables exactly one subsequent action. */
export function initialChallengeFlow({configuration,bootstrap,cli,fixture,active,step,fail,prove}){
  let next='customer',subscriptionId,operationId,attemptId,createKey,challengeDispatched=false;
  const need=value=>{billingCheck(active);if(!value)fail('CHALLENGE_ORDER');};
  function record(value,keys){try{return exactRecord(value,keys);}catch{fail('CHALLENGE_ORDER');}}
  function identity(value){
    const scope=record(value,['subscriptionId','operationId','attemptId']);
    need(scope.subscriptionId===subscriptionId && scope.operationId===operationId && billingUuid(scope.attemptId));
    return scope;
  }
  async function once(expected,after,phase,operation){
    need(next===expected);next='busy';
    await step(phase,async limits=>{
      if(phase==='DISPATCH')need(await cli.assertBillingListener(limits)===true);
      need(await operation(limits)===true);
    });
    need(next==='busy');next=after;return true;
  }
  const port={
    acknowledgeCustomer(raw){
      return once('customer','create','ACK',l=>{
        const value=record(raw,['customerId','siteId']);
        need(billingUuid(value.customerId) && value.siteId===fixture.resources().siteId);
        return fixture.acknowledgeCustomer(value,l);
      });
    },
    beforeDispatch(kind,key,raw){
      need(billingUuid(key));
      if(kind==='create'){
        need(raw===undefined);createKey=key;
        return once('create','created','DISPATCH',l=>fixture.beforeDispatch(kind,key,undefined,l));
      }
      need(kind==='confirm' && key!==createKey);
      return once('confirm','confirmed','DISPATCH',l=>{
        const value=identity(raw);attemptId=value.attemptId;
        return fixture.beforeDispatch(kind,key,value,l);
      });
    },
    acknowledge(kind,raw){
      if(kind==='create')return once('created','confirm','ACK',l=>{
        const value=record(raw,['subscriptionId','operationId']);
        subscriptionId=value.subscriptionId;operationId=value.operationId;
        need(billingUuid(subscriptionId) && billingUuid(operationId) && subscriptionId!==operationId);
        return fixture.acknowledge(kind,value,l);
      });
      need(kind==='confirm');
      return once('confirmed','challenge','ACK',l=>{
        const value=record(raw,['attemptId','state','nextActionAllowed','retryAllowed','successorAttemptId']);
        need(value.attemptId===attemptId && value.state==='REQUIRES_ACTION' && value.nextActionAllowed===true &&
          value.retryAllowed===false && value.successorAttemptId===null);
        return fixture.acknowledge(kind,value,l);
      });
    },
    async beforeChallenge(raw){
      await once('challenge','challenged','DISPATCH',l=>{
        const value=identity(raw);need(value.attemptId===attemptId);
        return fixture.recordChallenge('challenge-dispatch',value,l);
      });
      challengeDispatched=true;
      return true;
    },
    acknowledgeChallenge(raw){return once('challenged','evidence','ACK',l=>{
      const value=identity(raw);need(value.attemptId===attemptId);
      return fixture.recordChallenge('challenge-ack',value,l);
    });},
  };
  return {port,challengeDispatched:()=>challengeDispatched,async verify(){
    return once('evidence','complete','EVIDENCE',async l=>{
      const verify=prove??(await import('./stripe-billing-evidence.mjs')).proveBillingInitial3dsEvidence;
      if(await verify({configuration,bootstrap,fixture,cli},l)!==true)fail('EVIDENCE');
      return true;
    });
  }};
}
