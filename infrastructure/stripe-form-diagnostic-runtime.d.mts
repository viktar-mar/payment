import type {CheckoutState} from '../frontend/sdk/src/contracts.js';
export interface FormDiagnosticLimits {deadline:number;signal?:AbortSignal;}
export interface FormDiagnosticOptions {
  env:Readonly<Record<string,string|undefined>>;runId:string;expectedSource:string;daemon:string;
  ports:Readonly<{backend:number;demo:number;receiver:number;postgres:number;mailpitHttp:number;mailpitSmtp:number}>;
}
export interface FormDiagnosticHandoff {
  readonly runId:string;readonly expectedSource:string;readonly endpoint:string;
  readonly session:Readonly<{version:2;apiBaseUrl:string;siteKey:string;priceKey:string;checkoutId:string;checkoutToken:string;expiresAt:string;
    providerType:'STRIPE';confirmationMode:'CLIENT_PROVIDER';state:Readonly<CheckoutState>}>;
}
export interface FormDiagnosticRuntime {
  prepare(limits:FormDiagnosticLimits):Promise<Readonly<FormDiagnosticHandoff>>;
  verifyNoConfirmation(limits:FormDiagnosticLimits):Promise<true>;
  close(limits:FormDiagnosticLimits):Promise<true>;
}
export function createFormDiagnosticRuntime(options:FormDiagnosticOptions,dependencies?:Record<string,unknown>):Promise<Readonly<FormDiagnosticRuntime>>;
