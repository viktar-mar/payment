import type {BillingBinding,BillingLimits} from './stripe-billing-acceptance.mjs';
import type {FailedRenewalRuntime} from './stripe-billing-first-payment-runtime.mjs';

export function billingUuid(value:unknown):value is string;
type FixtureOptions={configuration:unknown;bootstrap:unknown;cli:unknown};
type FixtureResources=Pick<BillingBinding,'runId'|'expectedSource'|'endpoints'|'siteKey'|'email'>
 &Partial<Pick<BillingBinding,'accountId'|'siteId'|'productId'|'priceId'|'customerId'|'subscriptionId'|'operationId'|'attemptId'>>
 &{priceReference?:string;createKeyHash?:string;confirmKeyHash?:string};
interface BillingFixture {
 resources():Readonly<FixtureResources>;
 provision(limits:BillingLimits):Promise<true>;
 withOtpCode(callback:(code:string)=>true|Promise<true>,limits:BillingLimits):Promise<true>;
 acknowledgeCustomer(value:{customerId:string;siteId:string},limits:BillingLimits):Promise<true>;
 beforeDispatch(kind:'create'|'confirm',key:string,identity:Record<string,unknown>|undefined,limits:BillingLimits):Promise<true>;
 acknowledge(kind:'create'|'confirm',value:Record<string,unknown>,limits:BillingLimits):Promise<true>;
 inspectCallbacks(limits:BillingLimits):Promise<unknown>;
 close():Promise<true>;
}
export function createBillingFixture(options:FixtureOptions,dependencies?:Record<string,unknown>):Promise<Readonly<BillingFixture>>;
export function createBillingFailedRenewalFixture(options:FixtureOptions,dependencies?:Record<string,unknown>):Promise<Readonly<BillingFixture&{
 beforeDeclineArm(value:Awaited<ReturnType<FailedRenewalRuntime['boundary']>>,limits:BillingLimits):Promise<true>;
 acknowledgeDeclineArm(value:Awaited<ReturnType<FailedRenewalRuntime['armDecline']>>,limits:BillingLimits):Promise<true>;
}>>;
