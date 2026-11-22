import {createHash} from 'node:crypto';
import {billingCheck} from './stripe-billing-limits.mjs';
import {billingUuid} from './stripe-billing-provision.mjs';
import {exactRecord} from './stripe-billing-failed-renewal-response.mjs';

// The original browser requests own their keys. A completed durable receipt permits the browser to forward once.
export function initialRetryFlow({configuration,bootstrap,cli,fixture,active,step,fail,prove}) {
  let next='customer', subscriptionId, operationId, originalAttempt, successorAttempt;
  let firstHash, retryHash, secondHash;
  const usedKeys=new Set();
  const need=ok=>{billingCheck(active);if(!ok)fail('RETRY_ORDER');};
  function claim(expected) {
    billingCheck(active);
    need(next===expected);
    next='busy';
  }
  function snapshot(value,keys) {
    try{return exactRecord(value,keys);}
    catch{fail('RETRY_ORDER');}
  }
  function uniqueHash(key) {
    need(billingUuid(key));
    const hash=createHash('sha256').update(key).digest('hex');
    need(!usedKeys.has(hash));usedKeys.add(hash);
    return hash;
  }
  function attemptIdentity(raw,expected) {
    const identity=snapshot(raw,['subscriptionId','operationId','attemptId']);
    need(identity.subscriptionId===subscriptionId && identity.operationId===operationId && billingUuid(identity.attemptId)
      && (expected===undefined || identity.attemptId===expected));
    return identity;
  }
  async function persist(after,body,dispatch=false) {
    await step(dispatch?'RETRY_DISPATCH':'RETRY_ACK',async limits=>{
      if(dispatch){
        need(await cli.assertBillingListener(limits)===true);
        billingCheck(limits);
      }
      need(await body(limits)===true);
    });
    next=after;
    return true;
  }
  async function evidence(phase,after) {
    await step('RETRY_EVIDENCE',async limits=>{
      const scope=fixture.resources();
      need(scope.runId===configuration.runId && scope.expectedSource===configuration.expectedSource &&
        scope.subscriptionId===subscriptionId && scope.operationId===operationId && scope.attemptId===originalAttempt &&
        scope.confirmKeyHash===firstHash);
      const attempts={first:{attemptId:originalAttempt,confirmKeyHash:firstHash}};
      if(phase==='recovered'){
        attempts.retry={keyHash:retryHash,attemptId:successorAttempt};
        attempts.second={attemptId:successorAttempt,confirmKeyHash:secondHash};
      }
      const verify=prove??(await import('./stripe-billing-initial-retry-evidence.mjs')).proveBillingInitialRetryEvidence;
      if(await verify({configuration,bootstrap,cli,fixture,phase,attempts},limits)!==true)fail('RETRY_EVIDENCE');
      billingCheck(limits);
      const kind=phase==='declined'?'decline-proof':'recovered-proof';
      if(await fixture.recordRetry(kind,null,limits)!==true)fail('RETRY_EVIDENCE');
    });
    next=after;
    return true;
  }
  const port=Object.freeze({
    async acknowledgeCustomer(raw) {
      claim('customer');
      const value=snapshot(raw,['customerId','siteId']);
      need(billingUuid(value.customerId) && value.siteId===fixture.resources().siteId);
      return persist('create',l=>fixture.acknowledgeCustomer(value,l));
    },
    async beforeDispatch(kind,key,raw) {
      need(kind==='create'||kind==='confirm');claim(kind);
      const hash=uniqueHash(key);
      if(kind==='create'){
        need(raw===undefined);
        return persist('create-ack',l=>fixture.beforeDispatch('create',key,undefined,l),true);
      }
      const value=attemptIdentity(raw);
      originalAttempt=value.attemptId;firstHash=hash;
      return persist('decline',l=>fixture.beforeDispatch('confirm',key,value,l),true);
    },
    async acknowledge(kind,raw) {
      need(kind==='create'||kind==='confirm');
      if(kind==='create'){
        claim('create-ack');
        const value=snapshot(raw,['subscriptionId','operationId']);
        need(billingUuid(value.subscriptionId)&&billingUuid(value.operationId)&&value.operationId!==value.subscriptionId);
        subscriptionId=value.subscriptionId;operationId=value.operationId;
        return persist('confirm',l=>fixture.acknowledge(kind,value,l));
      }
      claim('decline');
      const value=snapshot(raw,['attemptId','state','retryAllowed','successorAttemptId','nextActionAllowed']);
      need(value.attemptId===originalAttempt && value.state==='DECLINED' && value.retryAllowed===true &&
        value.nextActionAllowed===false && value.successorAttemptId===null);
      await persist('decline-evidence',l=>fixture.acknowledge(kind,value,l));
      claim('decline-evidence');
      return evidence('declined','retry');
    },
    async beforeRetry(key,raw) {
      claim('retry');
      const value=attemptIdentity(raw,originalAttempt);retryHash=uniqueHash(key);
      return persist('retry-ack',l=>fixture.recordRetry('retry-dispatch',{...value,keyHash:retryHash},l),true);
    },
    async acknowledgeRetry(raw) {
      claim('retry-ack');
      const value=snapshot(raw,['attemptId','successorAttemptId']);
      const scope=fixture.resources();
      const ownedIds=[configuration.runId,scope.accountId,scope.siteId,scope.productId,scope.priceId,scope.customerId,subscriptionId,operationId,originalAttempt];
      need(value.attemptId===originalAttempt && billingUuid(value.successorAttemptId) && !ownedIds.includes(value.successorAttemptId));
      successorAttempt=value.successorAttemptId;
      return persist('successor-confirm',l=>fixture.recordRetry('retry-ack',value,l));
    },
    async beforeSuccessorConfirm(key,raw) {
      claim('successor-confirm');
      const value=attemptIdentity(raw,successorAttempt);secondHash=uniqueHash(key);
      return persist('successor-ack',l=>fixture.recordRetry('successor-confirm-dispatch',{...value,keyHash:secondHash},l),true);
    },
    async acknowledgeSuccessor(raw) {
      claim('successor-ack');
      const value=snapshot(raw,['attemptId','state','nextActionAllowed']);
      need(value.attemptId===successorAttempt && value.state==='OBSERVE' && value.nextActionAllowed===false);
      await persist('recovery-evidence',l=>fixture.recordRetry('successor-confirm-ack',value,l));
      claim('recovery-evidence');
      return evidence('recovered','complete');
    },
  });
  return {port,requireComplete(){claim('complete');}};
}
