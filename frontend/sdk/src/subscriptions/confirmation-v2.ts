import { exact, instant, object, uuid } from '../contracts.js';
import { PaymentSdkError } from '../errors.js';
import type { CustomerSession } from '../customer/contracts.js';
import type { SubscriptionAction, SubscriptionAttemptStatus, SubscriptionClient, SubscriptionConfirmationSession, SubscriptionConfirmReceipt, SubscriptionIdRequest, SubscriptionPaymentSession } from './contracts.js';
import { parseSubscriptionClientSession } from './wire.js';
function invalid():never{throw new PaymentSdkError('INVALID_RESPONSE');}
/** All protocol DTOs are flat; reject duplicate decoded keys before JSON loses them. */
export function parseConfirmationJson(raw:string):unknown {
  const str='"(?:[^"\\\\\\u0000-\\u001f]|\\\\(?:["\\\\/bfnrt]|u[0-9a-fA-F]{4}))*"';
  const pair=`(${str})\\s*:\\s*(?:${str}|true|false|null)`;
  if(raw.trim()==='null')return null;
  if(!new RegExp(`^\\s*\\{\\s*${pair}(?:\\s*,\\s*${pair})*\\s*\\}\\s*$`).test(raw))invalid();
  const keys=[...raw.matchAll(new RegExp(pair,'g'))].map(m=>JSON.parse(m[1]!));
  if(new Set(keys).size!==keys.length)invalid();
  return JSON.parse(raw);
}
function redacted<T>(values:Record<string,unknown>):T {
  const result={};for(const [key,value] of Object.entries(values))Object.defineProperty(result,key,{value});
  Object.defineProperties(result,{toJSON:{value:()=> '[SubscriptionConfirmationSession redacted]'},toString:{value:()=> '[SubscriptionConfirmationSession redacted]'}});
  return Object.freeze(result) as T;
}
export function parseConfirmationSession(raw:unknown,session:CustomerSession,expiresAt:string,action?:SubscriptionAction,attemptId?:string):SubscriptionConfirmationSession {
  if(!object(raw))invalid();
  if(raw.protocol==='BROWSER_CONFIRM_V1'){
    if(!action||raw.flow!=='SETUP'||attemptId)invalid();
    const {protocol:_,...legacy}=raw;const parsed=parseSubscriptionClientSession(legacy,action,session);
    return redacted({protocol:'BROWSER_CONFIRM_V1',flow:parsed.flow,publishableKey:parsed.publishableKey,clientSecret:parsed.clientSecret,actionToken:parsed.actionToken,expiresAt:parsed.expiresAt});
  }
  if(!exact(raw,['protocol','provider','flow','publishable_key','client_secret','attempt_id','capability','expires_at'])||raw.protocol!=='SERVER_CONFIRM_V2'||raw.provider!=='stripe'||raw.flow!=='PAYMENT'||!uuid(raw.attempt_id)||(attemptId!==undefined&&raw.attempt_id!==attemptId)
    ||typeof raw.publishable_key!=='string'||raw.publishable_key.length>255||!/^pk_(?:test|live)_[A-Za-z0-9_]+$/.test(raw.publishable_key)
    ||typeof raw.client_secret!=='string'||raw.client_secret.length>512||!/^pi_[A-Za-z0-9_]+_secret_[A-Za-z0-9_]+$/.test(raw.client_secret)
    ||typeof raw.capability!=='string'||!/^bca2_[A-Za-z0-9_-]{43}$/.test(raw.capability)
    ||!instant(raw.expires_at)||!instant(expiresAt)||Date.parse(raw.expires_at)<=Date.now()||Date.parse(raw.expires_at)>Math.min(Date.parse(expiresAt),Date.parse(session.expiresAt)))invalid();
  return redacted({protocol:'SERVER_CONFIRM_V2',flow:'PAYMENT',publishableKey:raw.publishable_key,clientSecret:raw.client_secret,attemptId:raw.attempt_id,capability:raw.capability,expiresAt:raw.expires_at});
}
export function parseAttempt(raw:unknown,attemptId?:string,confirm=false):SubscriptionAttemptStatus|SubscriptionConfirmReceipt {
 if(!object(raw)||!exact(raw,['protocol','attempt_id','state','retry_allowed','successor_attempt_id','expires_at',...(confirm?['next_action_allowed']:[])])||raw.protocol!=='SERVER_CONFIRM_V2'||!uuid(raw.attempt_id)||(attemptId!==undefined&&raw.attempt_id!==attemptId)||!['READY','DISPATCHING','REQUIRES_ACTION','OBSERVE','DECLINED','UNKNOWN'].includes(raw.state as string)||typeof raw.retry_allowed!=='boolean'||(raw.successor_attempt_id!==null&&(!uuid(raw.successor_attempt_id)||raw.successor_attempt_id===raw.attempt_id))||!instant(raw.expires_at)||raw.retry_allowed&&(raw.state!=='DECLINED'||raw.successor_attempt_id!==null)||raw.successor_attempt_id!==null&&raw.state!=='DECLINED'||confirm&&(typeof raw.next_action_allowed!=='boolean'||raw.next_action_allowed&&raw.state!=='REQUIRES_ACTION'))invalid();
 const result={protocol:'SERVER_CONFIRM_V2' as const,attemptId:raw.attempt_id,state:raw.state as SubscriptionAttemptStatus['state'],retryAllowed:raw.retry_allowed,successorAttemptId:raw.successor_attempt_id as string|null,expiresAt:raw.expires_at};
 if(confirm)Object.defineProperty(result,'nextActionAllowed',{value:raw.next_action_allowed});
 return Object.freeze(result);
}
const identity=(s:CustomerSession)=>JSON.stringify([s.apiBaseUrl,s.siteKey,s.siteId,s.origin,s.customer,s.sessionToken,s.expiresAt,[...s.scopes].sort()]);
type Input=SubscriptionIdRequest&{operationId:string};
const bindings=new WeakMap<SubscriptionPaymentSession,{client:SubscriptionClient;identity:string;subscriptionId:string;operationId:string}>();
const spent=new WeakMap<SubscriptionClient,Set<string>>();
const key=(i:Input,c:SubscriptionPaymentSession)=>JSON.stringify([i.subscriptionId,i.operationId,c.attemptId]);
export function bindPaymentSession(client:SubscriptionClient,i:Input,c:SubscriptionPaymentSession):SubscriptionPaymentSession{bindings.set(c,{client,identity:identity(i.session),subscriptionId:i.subscriptionId,operationId:i.operationId});return c;}
export function assertPaymentSession(client:SubscriptionClient,i:Input,c:SubscriptionPaymentSession):void{const b=bindings.get(c);if(!b||b.client!==client||b.identity!==identity(i.session)||b.subscriptionId!==i.subscriptionId||b.operationId!==i.operationId||Date.parse(c.expiresAt)<=Date.now()||spent.get(client)?.has(key(i,c)))throw new PaymentSdkError('INVALID_SESSION');}
export function consumePaymentSession(client:SubscriptionClient,i:Input,c:SubscriptionPaymentSession):void{assertPaymentSession(client,i,c);const set=spent.get(client)??new Set<string>();set.add(key(i,c));spent.set(client,set);bindings.delete(c);}
export function supportsPaymentV2(client:SubscriptionClient):boolean{const methods=[client.confirmationSession,client.confirmationAttempt,client.currentConfirmationAttempt,client.confirmPaymentAttempt,client.retryPaymentAttempt,client.paymentAttemptSession];if(methods.every(v=>v===undefined))return false;if(methods.some(v=>typeof v!=='function'))throw new PaymentSdkError('INVALID_CONFIGURATION');return true;}
const selectedSetups=new WeakMap<object,{client:SubscriptionClient;identity:string;subscriptionId:string;operationId:string;action:SubscriptionAction}>();
/** Selected SETUP is an original server-issued object, never an injected driver option. */
export function bindSelectedSetup(client:SubscriptionClient,i:Input,action:SubscriptionAction,context:SubscriptionConfirmationSession):SubscriptionConfirmationSession{selectedSetups.set(context,{client,identity:identity(i.session),subscriptionId:i.subscriptionId,operationId:i.operationId,action});return context;}
export function assertSelectedSetup(client:SubscriptionClient,i:Input,action:SubscriptionAction,context:object):void{const b=selectedSetups.get(context);if(!b||b.client!==client||b.identity!==identity(i.session)||b.subscriptionId!==i.subscriptionId||b.operationId!==i.operationId||b.action!==action)throw new PaymentSdkError('INVALID_SESSION');}
