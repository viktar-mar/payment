import type {ManualPaymentOptions,Limits} from './stripe-manual-payment-options.mjs';
import type {ManualPaymentReport} from './stripe-manual-payment-schema.mjs';
export type {ManualPaymentOptions,Limits,ManualPaymentReport};
export interface ManualPaymentResult {readonly report:ManualPaymentReport;readonly exitCode:0|1;}
export function runManualPayment(options:ManualPaymentOptions,limits:Limits,deps?:Record<string,unknown>):Promise<Readonly<ManualPaymentResult>>;
export function runManualPaymentCli(input:{readonly env:object;readonly args:readonly string[]},deps?:Record<string,unknown>):Promise<Readonly<ManualPaymentResult>>;
export function runManualPaymentEntry(input:{readonly env:object;readonly args:readonly string[]},capabilities:{readonly stdout:NodeJS.WritableStream;readonly stderr?:NodeJS.WritableStream;readonly exit:(code:0|1)=>void},deps?:Record<string,unknown>):Promise<void>;
