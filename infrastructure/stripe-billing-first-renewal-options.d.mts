export interface BillingFirstRenewalOptions {
 env:Readonly<Record<string,string|undefined>>;
 runId:string;expectedSource:string;daemon:string;
 ports:Readonly<{backend:number;demo:number;receiver:number;postgres:number;mailpitHttp:number;mailpitSmtp:number}>;
 controlPort:number;frozenTime:number;
 approvedBinding:Readonly<{externalAccountId:'acct_1UPiMpELswAd8tpR';apiVersion:'2026-09-30.endive'}>;
 selectedCase:'initial_and_first_renewal';protocol?:'SERVER_CONFIRM_V2';
 budget?:Readonly<{clock:1;customer:1;create:1;confirm:1;advance:2}>;
}
export function billingFirstRenewalOptIn(env:unknown):boolean;
export function validateBillingFirstRenewalOptions(options:unknown):Readonly<BillingFirstRenewalOptions>|null;
export function snapshotBillingFirstRenewalEnvironment(env:unknown):Readonly<Record<string,string|undefined>>|null;
