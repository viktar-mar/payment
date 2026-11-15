export interface BillingFailedRenewalOptions {
 env:Readonly<Record<string,string|undefined>>;
 runId:string;expectedSource:string;daemon:string;
 ports:Readonly<{backend:number;demo:number;receiver:number;postgres:number;mailpitHttp:number;mailpitSmtp:number}>;
 controlPort:number;frozenTime:number;
 approvedBinding:Readonly<{externalAccountId:'acct_1UPiMpELswAd8tpR';apiVersion:'2026-09-30.endive'}>;
 selectedCase:'initial_failed_renewal_grace_recovery';protocol?:'SERVER_CONFIRM_V2';
 budget?:Readonly<{clock:1;customer:1;create:1;confirm:1;advance:5;declineAttach:1;declineUpdate:1;setup:1;method:1;recoveryConfirm:1}>;
}
export function billingFailedRenewalOptIn(env:unknown):boolean;
export function validateBillingFailedRenewalOptions(options:unknown):Readonly<BillingFailedRenewalOptions>|null;
export function snapshotBillingFailedRenewalEnvironment(env:unknown):Readonly<Record<string,string|undefined>>|null;
export function billingFailedRenewalExcluded(env:unknown):boolean;
