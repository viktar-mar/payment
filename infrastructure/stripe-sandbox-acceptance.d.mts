import type {SandboxFixture} from './smoke-stage-5a.mjs';
import type {SandboxAcceptanceExtras,SandboxExtrasOptions} from './stripe-sandbox-extras.mjs';
export type SandboxBrowserCase='success'|'decline'|'sca';
export type SandboxBrowserStep='BEGIN_BROWSER'|'NEW_CONTEXT'|'CHECKOUT_SESSION'|'INSTALL_SESSION'|'NEW_PAGE'|'NAVIGATE'|'RESUME'|'PAYMENT_FORM_READY'|'CARD_FRAME'|'CARD_NUMBER_READY'|'CARD_NUMBER'|'CARD_EXPIRY'|'CARD_CVC'|'POSTAL_CHECK'|'POSTAL_FILL'|'MARK_PAY'|'PAY'|'MARK_RETRY'|'RETRY_PAYMENT_FORM_READY'|'RETRY_CARD_FRAME'|'RETRY_CARD_NUMBER_READY'|'RETRY_CARD_NUMBER'|'RETRY_CARD_EXPIRY'|'RETRY_CARD_CVC'|'RETRY_POSTAL_CHECK'|'RETRY_POSTAL_FILL'|'RETRY_PAY'|'SCA_WRAPPER_FRAME'|'SCA_CHALLENGE_FRAME'|'SCA_ISSUER_FRAME'|'SCA_COMPLETE'|'SERVER_CONFIRM'|'CLOSE_CONTEXT';
/** Read-only, best-effort, bounded failure observer; only the two form-readiness steps can emit its finite projection. */
export function runSandboxBrowserStep<T>(browserCase:SandboxBrowserCase,browserStep:SandboxBrowserStep,body:()=>Promise<T>,observeFailure?:()=>Promise<unknown>):Promise<T>;
export function rethrowSandboxBrowserFailure(error:unknown):never;
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
/** Revoked after the payment callback; no refund, close, retry or alternate-card authority. */
export interface SandboxRemainingPaymentFixture {
  readonly identity:Readonly<{endpoints:Readonly<SandboxFixture['identity']['endpoints']>;site:Readonly<SandboxFixture['identity']['site']>;price:Readonly<SandboxFixture['identity']['price']>}>;
  beginBrowser(name:'success'):Promise<boolean>;
  browserSession(name:'success'):Promise<Record<string,unknown>>;
  markPay(name:'success'):Promise<void>;
  confirm(name:'success'):Promise<boolean>;
}
export interface OwnedSandboxRemainingResources extends Omit<OwnedSandboxResources,'accepted'|'proof'> {
  readonly mode:'REMAINING_5A';readonly remainingAccepted:boolean;readonly accepted:false;
  readonly proof:Readonly<{cards:false;payment:boolean;refunds:boolean;anonymous:boolean;emailOtp:boolean;otp:boolean;missed:boolean}>;
}
export interface OwnedSandboxRemainingJourneys {
  payment(fixture:SandboxRemainingPaymentFixture):Promise<true>;
  donations(extras:SandboxAcceptanceExtras):Promise<true>;
  missed(fixture:SandboxFixture):Promise<true>;
}
export interface OwnedSandboxRemainingAcceptance {
  run(journeys:OwnedSandboxRemainingJourneys,limits:OwnedSandboxLimits):Promise<true>;
  close(limits:OwnedSandboxLimits):Promise<true>;
  resources():OwnedSandboxRemainingResources;
}
/** Reviewed factories plus raw process/HTTP/time boundaries, for guarded synthetic tests only. */
type Bootstrap=SandboxExtrasOptions['bootstrap'] & {
  prepare(limits:OwnedSandboxLimits):Promise<true>;start(limits:OwnedSandboxLimits):Promise<true>;stop(limits:OwnedSandboxLimits):Promise<true>;
  verifyForwardTarget():Promise<true>;persistWebhookSecret(secret:string):Promise<true>;
};
interface LoginDescriptor<Cap extends 180000|600000> {readonly file:string;readonly args:readonly string[];readonly options:Readonly<{
  cwd:string;env:Readonly<Record<string,string>>;shell:false;stdio:readonly ['pipe','pipe','pipe'];timeout:Cap;maxBuffer:65536;
}>}
type Cli=SandboxExtrasOptions['cli'] & {startListener(limits:OwnedSandboxLimits):Promise<true>;
  loginInvocation(limits:OwnedSandboxLimits):Promise<LoginDescriptor<180000>>;completeLoginInvocation(limits:OwnedSandboxLimits):Promise<LoginDescriptor<600000>>};
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
export function openOwnedSandboxRemainingAcceptance(options:OwnedSandboxOptions,limits:OwnedSandboxLimits,dependencies?:OwnedSandboxDependencies):Promise<OwnedSandboxRemainingAcceptance>;
export function formatSandboxAuthorization(value:unknown):string|null;
export function formatSandboxDiagnostic(value:unknown):string|null;
