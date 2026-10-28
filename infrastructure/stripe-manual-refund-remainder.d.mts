import type {Limits,RemainderOptions} from './stripe-manual-refund-remainder-options.mjs';
import type {createManualRefundRemainderState} from './stripe-manual-refund-remainder-state.mjs';
import type {createOwnedManualRefundRemainderRuntime} from './stripe-manual-refund-remainder-runtime.mjs';
import type {createManualRefundRemainderCliAdapter} from './stripe-manual-refund-remainder-cli.mjs';
import type {createManualRefundRemainderSession} from './stripe-manual-refund-remainder-session.mjs';
import type {ManualRefundRemainderReport} from './stripe-manual-refund-remainder-schema.mjs';
export interface ManualRefundRemainderDependencies {
  now?:()=>number;
  setTimeout?:typeof globalThis.setTimeout;
  clearTimeout?:typeof globalThis.clearTimeout;
  createState?:typeof createManualRefundRemainderState;
  createRuntime?:typeof createOwnedManualRefundRemainderRuntime;
  createCli?:typeof createManualRefundRemainderCliAdapter;
  createSession?:typeof createManualRefundRemainderSession;
  write?:(text:string)=>Promise<unknown>;
  subscribe?:(stop:()=>void)=>()=>void;
}
export interface ManualRefundRemainderResult {readonly report:ManualRefundRemainderReport;readonly exitCode:0|1;}
export function runManualRefundRemainder(options:RemainderOptions,limits:Limits,deps?:ManualRefundRemainderDependencies):Promise<Readonly<ManualRefundRemainderResult>>;
export function runManualRefundRemainderCli(input:Readonly<{env:Record<string,string|undefined>;args:readonly string[]}>,deps?:ManualRefundRemainderDependencies):Promise<Readonly<ManualRefundRemainderResult>>;
export function runManualRefundRemainderEntry(input:Readonly<{env:Record<string,string|undefined>;args:readonly string[]}>,capabilities:Readonly<{stdout:NodeJS.WritableStream;exit:(code:0|1)=>unknown}>,deps?:ManualRefundRemainderDependencies):Promise<void>;
