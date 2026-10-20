export interface RefundOptions {
  readonly env:Readonly<Record<string,string|undefined>>;
  readonly runId:string;
  readonly expectedSource:string;
  readonly daemon:string;
}
export interface Limits {readonly deadline:number;readonly signal?:AbortSignal;}
export function manualRefundOptIn(env:unknown):boolean;
export function validateManualRefundOptions(value:unknown):Readonly<RefundOptions>|null;
