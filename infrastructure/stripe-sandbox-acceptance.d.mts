import type {SandboxFixture} from './smoke-stage-5a.mjs';
import type {SandboxAcceptanceExtras,SandboxExtrasOptions} from './stripe-sandbox-extras.mjs';
export interface OwnedSandboxLimits {deadline:number;signal?:AbortSignal}
export interface OwnedSandboxOptions {
  env:Record<string,string|undefined>;runId:string;expectedSource:string;daemon:string;
  ports:{backend:number;demo:number;receiver:number;postgres:number;mailpitHttp:number;mailpitSmtp:number};
}
export interface OwnedSandboxPublicBinding {
  endpoints:{backend:string;demo:string;receiver:string};
  site:{id:string;key:string};campaign:{id:string;key:string;status:string;version:number};price:{id:string;key:string;version:number};
}
export interface OwnedSandboxResources extends Partial<OwnedSandboxPublicBinding> {
  readonly runId:string;readonly phase:string;readonly failure:string|null;readonly accepted:boolean;readonly cleanupConfirmed:boolean;
  readonly proof:Readonly<{cards:boolean;refunds:boolean;anonymous:boolean;emailOtp:boolean;otp:boolean;missed:boolean}>;
  readonly cleanup:Readonly<{cards:boolean;missed:boolean;extras:boolean;listener:boolean;services:boolean}>;
}
export interface OwnedSandboxJourneys {
  cards(fixture:SandboxFixture):Promise<true>;
  donations(extras:SandboxAcceptanceExtras):Promise<true>;
  missed(fixture:SandboxFixture):Promise<true>;
}
export interface OwnedSandboxAcceptance {
  run(journeys:OwnedSandboxJourneys,limits:OwnedSandboxLimits):Promise<true>;
  close(limits:OwnedSandboxLimits):Promise<true>;
  resources():OwnedSandboxResources;
}
/** Reviewed factories plus raw process/HTTP/time boundaries, for guarded synthetic tests only. */
type Bootstrap=SandboxExtrasOptions['bootstrap'] & {
  prepare(limits:OwnedSandboxLimits):Promise<true>;start(limits:OwnedSandboxLimits):Promise<true>;stop(limits:OwnedSandboxLimits):Promise<true>;
  verifyForwardTarget():Promise<true>;persistWebhookSecret(secret:string):Promise<true>;
};
interface LoginDescriptor {readonly file:string;readonly args:readonly string[];readonly options:Readonly<{
  cwd:string;env:Readonly<Record<string,string>>;shell:false;stdio:readonly ['pipe','pipe','pipe'];timeout:180000;maxBuffer:65536;
}>}
type Cli=SandboxExtrasOptions['cli'] & {startListener(limits:OwnedSandboxLimits):Promise<true>;
  loginInvocation(limits:OwnedSandboxLimits):Promise<LoginDescriptor>;completeLoginInvocation(limits:OwnedSandboxLimits):Promise<LoginDescriptor>};
type Provisioner=SandboxExtrasOptions['provisioner'] & {provision(limits:OwnedSandboxLimits):Promise<true>;
  withCatalogEnvironment(callback:(env:Readonly<Record<string,string>>)=>true):true};
type Binding=Readonly<{externalAccountId:'acct_1UPiMpELswAd8tpR';apiVersion:'2026-09-30.endive'}>;
export interface OwnedSandboxDependencies {
  createOwnedSandboxBootstrap?:(options:Readonly<OwnedSandboxOptions & {approvedBinding:Binding}>)=>Promise<Bootstrap>;
  createSandboxCliAdapter?:(options:Readonly<{env:OwnedSandboxOptions['env'];runId:string;cliDirectory:string;backendPort:number;accountKey:'stripe-sandbox-default';approvedBinding:Binding;
    persistWebhookSecret:Bootstrap['persistWebhookSecret'];verifyForwardTarget:Bootstrap['verifyForwardTarget']}>)=>Promise<Cli>;
  createOwnedSandboxProvisioner?:(options:Readonly<{env:OwnedSandboxOptions['env'];bootstrap:Bootstrap;verifyProviderBinding:Cli['validateBinding']}>)=>Promise<Provisioner>;
  createSandboxAcceptanceExtras?:typeof import('./stripe-sandbox-extras.mjs').createSandboxAcceptanceExtras;
  openStripeSandboxFixture?:typeof import('./smoke-stage-5a.mjs').openStripeSandboxFixture;
  runStripeSandboxSmoke?:typeof import('./smoke-stage-5a.mjs').runStripeSandboxSmoke;
  execFile?:typeof import('node:child_process').execFile;fetch?:typeof globalThis.fetch;
  now?:()=>number;setTimeout?:typeof globalThis.setTimeout;clearTimeout?:typeof globalThis.clearTimeout;
}
export function openOwnedSandboxAcceptance(options:OwnedSandboxOptions,limits:OwnedSandboxLimits,dependencies?:OwnedSandboxDependencies):Promise<OwnedSandboxAcceptance>;
export function formatSandboxAuthorization(value:unknown):string|null;
export function formatSandboxDiagnostic(value:unknown):string|null;
