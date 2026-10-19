import type {FormDiagnosticOptions,FormDiagnosticLimits,FormDiagnosticHandoff} from './stripe-form-diagnostic-runtime.mjs';
export interface ManualPaymentOptions extends FormDiagnosticOptions {readonly manualPort:number;}
export type Limits=FormDiagnosticLimits;
export type PrivateSession=FormDiagnosticHandoff['session'];
export interface PaymentBinding {readonly paymentId:string;readonly siteId:string;readonly paymentAccountId:string;readonly operationId:string;readonly intentId:string;readonly priceId:string;readonly priceKey:string;readonly amountMinor:2000;readonly currency:'EUR';readonly externalAccountId:'acct_1UPiMpELswAd8tpR';}
export interface PaymentServerProof {readonly paymentConfirmed:true;readonly webhookVerified:true;readonly journalVerified:true;readonly callbackVerified:true;readonly providerEventId:string;}
export function manualPaymentOptIn(env:unknown):boolean;
export function validateManualPaymentOptions(value:unknown):Readonly<ManualPaymentOptions>|null;
