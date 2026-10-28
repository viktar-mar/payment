import type {RefundOptions,Limits,StateCapability,OriginalRefundTarget} from './stripe-manual-refund-state.mjs';
import type {RefundRuntime} from './stripe-manual-refund-runtime.mjs';
import type {RefundCli,RefundSession} from './stripe-manual-refund-session.mjs';
import type {ManualRefundReport} from './stripe-manual-refund-schema.mjs';
export interface ControllerRefundCli extends RefundCli {
  startListener(limits:Limits):Promise<true>;
  bindCapturedPayment(target:OriginalRefundTarget,limits:Limits):Promise<true>;
  stopListener(cleanupLimits:Limits):Promise<true>;
}
export interface ManualRefundDependencies {
  now?:()=>number;
  setTimeout?:typeof globalThis.setTimeout;
  clearTimeout?:typeof globalThis.clearTimeout;
  openState?:(options:RefundOptions,limits:Limits)=>Promise<StateCapability>;
  createRuntime?:(input:Readonly<{options:RefundOptions;state:StateCapability}>)=>Promise<RefundRuntime>;
  createCli?:(input:Readonly<{env:RefundOptions['env'];originalRunId:'031b5ae7-6603-4339-b1b3-77231291ff34';cliDirectory:string;backendPort:number;verifyForwardTarget:RefundRuntime['verifyForwardTarget'];persistWebhookSecret:RefundRuntime['verifyWebhookSecret'];approvedBinding:Readonly<{externalAccountId:'acct_1UPiMpELswAd8tpR';apiVersion:'2026-09-30.endive'}>}>)=>Promise<ControllerRefundCli>;
  createSession?:(input:Readonly<{runtime:RefundRuntime;cli:ControllerRefundCli;state:StateCapability}>)=>Promise<RefundSession>;
  write?:(text:string)=>Promise<unknown>;
  subscribe?:(stop:()=>void)=>()=>void;
}
export interface ManualRefundResult {readonly report:ManualRefundReport;readonly exitCode:0|1;}
export function runManualRefund(options:RefundOptions,limits:Limits,deps?:ManualRefundDependencies):Promise<Readonly<ManualRefundResult>>;
export function runManualRefundCli(input:Readonly<{env:Record<string,string|undefined>;args:readonly string[]}>,deps?:ManualRefundDependencies):Promise<Readonly<ManualRefundResult>>;
export function runManualRefundEntry(input:Readonly<{env:Record<string,string|undefined>;args:readonly string[]}>,capabilities:Readonly<{stdout:NodeJS.WritableStream;exit:(code:0|1)=>unknown}>,deps?:ManualRefundDependencies):Promise<void>;
