import type {BillingLimits} from './stripe-billing-acceptance.mjs';
export function billingCheck(limits:BillingLimits):void;
export function billingError(code:string):Error;
export function billingBounded<T>(limits:BillingLimits,body:(limits:BillingLimits)=>Promise<T>,maximum?:number):Promise<T>;
