export function isBrowserDiagnostic(code: unknown): boolean;
export function browserFailure(code: string): Error;
export function browserDiagnostic(error: unknown): string | null;
export function browserCleanupConfirmed(error: unknown): boolean;
export function finishBrowserCleanup(error: unknown, confirmed: boolean): void;
