import type {BillingBinding,BillingLimits} from './stripe-billing-acceptance.mjs';
import type {FailedRenewalRuntime} from './stripe-billing-first-payment-runtime.mjs';

export function billingUuid(value:unknown):value is string;
export function billingFailedRenewalProvisionCheckpoint(fixture:unknown,limits:BillingLimits,runId:string,expectedSource:string):string|null;
export function isFailedRenewalProvisionCheckpoint(code:unknown):code is string;
export function isInitial3dsProvisionCheckpoint(code:unknown):code is string;
export function billingPortalFixturePolicy(fixture:unknown,scope:unknown):Readonly<{portalReturnUrl:string;portalPolicyVersion:number}>|null;
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
export function createBillingPortalFixture(options:FixtureOptions,dependencies?:Record<string,unknown>):Promise<Readonly<Omit<BillingFixture,'resources'>&{
 resources():Readonly<FixtureResources&{portalReturnUrl?:string;portalPolicyVersion?:number}>;
 beforePortalOpening(scope:FixtureResources,keyDigest:string,limits:BillingLimits):Promise<true>;
 acknowledgePortalOpening(scope:FixtureResources,keyDigest:string,limits:BillingLimits):Promise<true>;
}>>;
export function createBillingOptionalTrialFixture(options:FixtureOptions,dependencies?:Record<string,unknown>):Promise<Readonly<Omit<BillingFixture,'beforeDispatch'|'acknowledge'>>>;
export function createBillingFailedRenewalFixture(options:FixtureOptions,dependencies?:Record<string,unknown>):Promise<Readonly<BillingFixture&{
 beforeDeclineArm(value:Awaited<ReturnType<FailedRenewalRuntime['boundary']>>,limits:BillingLimits):Promise<true>;
 acknowledgeDeclineArm(value:Awaited<ReturnType<FailedRenewalRuntime['armDecline']>>,limits:BillingLimits):Promise<true>;
}>>;
