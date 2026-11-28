export interface BillingFirstPaymentOptions {
 env:Readonly<Record<string,string|undefined>>;
 runId:string;expectedSource:string;daemon:string;
 ports:Readonly<{backend:number;demo:number;receiver:number;postgres:number;mailpitHttp:number;mailpitSmtp:number}>;
 controlPort:number;frozenTime:number;
 approvedBinding:Readonly<{externalAccountId:'acct_1UPiMpELswAd8tpR';apiVersion:'2026-09-30.endive'}>;
 selectedCase:'initial_success';protocol?:'SERVER_CONFIRM_V2';
 budget?:Readonly<{clock:1;customer:1;create:1;confirm:1}>;
}
export function billingFirstPaymentOptIn(env:unknown):boolean;
export function validateBillingFirstPaymentOptions(options:unknown):Readonly<BillingFirstPaymentOptions>|null;
export function snapshotBillingFirstPaymentEnvironment(env:unknown):Readonly<Record<string,string|undefined>>|null;
