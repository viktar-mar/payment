export interface Limits {readonly deadline:number;readonly signal?:AbortSignal;}
export interface RemainderOptions {readonly env:Readonly<Record<string,string|undefined>>;readonly runId:string;readonly expectedSource:string;readonly daemon:string;}
export function manualRefundRemainderOptIn(env:unknown):boolean;
/** Pure raw-flag exclusion for legacy entries, including exotic env objects. */
export function manualRefundRemainderExcluded(env:unknown):boolean;
export function validateManualRefundRemainderOptions(value:unknown):Readonly<RemainderOptions>|null;
