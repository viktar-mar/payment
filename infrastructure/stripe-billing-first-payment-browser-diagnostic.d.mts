export function isBrowserDiagnostic(code: unknown): boolean;
export function isMethodBrowserDiagnostic(code: unknown): boolean;
export function isAcceptanceBrowserDiagnostic(code: unknown): boolean;
export function acceptanceBrowserFailure(code: string): Error;
export function browserFailure(code: string, phase?: 'INITIAL'|'METHOD'): Error;
export function browserDiagnostic(error: unknown, phase?: 'INITIAL'|'METHOD'|'ACCEPTANCE'): string | null;
export function browserCleanupConfirmed(error: unknown): boolean;
export function finishBrowserCleanup(error: unknown, confirmed: boolean): void;
export type RefusedIssuerRelation='NOT_ISSUER_POST'|'ISSUER_FRAME_UNVERIFIED'|'AUTHENTICATE_NOT_ADMITTED'|'AUTHENTICATE_UNVERIFIED'|'AUTHENTICATE_BODY_UNBOUNDED'|
 'ISSUER_URL_MISMATCH'|'ISSUER_TRANSACTION_MISMATCH'|'ISSUER_TRANSACTION_MATCH'|'ISSUER_TRANSACTION_MATCH_FRAME_UNVERIFIED';
export interface BrowserProviderOriginWitness {
 readonly runId:string;
 readonly expectedSource:string;
 readonly origin:string;
 readonly checkpoint?:'BROWSER_PROVIDER_ORIGIN_OTHER'|'BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK'|'BROWSER_PROVIDER_ORIGIN_STRIPE_COM';
 readonly issuerRelation?:RefusedIssuerRelation;
}
export function rememberBrowserProviderOrigin(error: unknown, runId: string, expectedSource: string, origin: string, issuerRelation?:RefusedIssuerRelation): boolean;
export function browserProviderOrigin(error: unknown): BrowserProviderOriginWitness | null;
export function isBrowserProviderOriginWitness(value: unknown): value is BrowserProviderOriginWitness;
