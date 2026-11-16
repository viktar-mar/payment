export function isBrowserDiagnostic(code: unknown): boolean;
export function browserFailure(code: string): Error;
export function browserDiagnostic(error: unknown): string | null;
export function browserCleanupConfirmed(error: unknown): boolean;
export function finishBrowserCleanup(error: unknown, confirmed: boolean): void;
export interface BrowserProviderOriginWitness {readonly runId:string;readonly expectedSource:string;readonly origin:string}
export function rememberBrowserProviderOrigin(error: unknown, runId: string, expectedSource: string, origin: string): boolean;
export function browserProviderOrigin(error: unknown): BrowserProviderOriginWitness | null;
export function isBrowserProviderOriginWitness(value: unknown): value is BrowserProviderOriginWitness;
