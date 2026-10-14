/** Types for the finite local smoke's shared browser ownership guard. */
export function accessCacheFresh(decision: {allowed?: boolean; valid_until?: string} | null | undefined, now: number): boolean;
export function ownedContainerCommand(
  env: NodeJS.ProcessEnv,
  action: 'start' | 'stop',
  execute?: (command: string, args: string[], options: {timeout: number; maxBuffer: number}) => Promise<{stdout: string}>,
): Promise<void>;
export interface Stage4SmokeReport {
  result: 'PASS';
  initial: boolean; trial: boolean; renewal: boolean; grace: boolean;
  recovery: boolean; upgrade: boolean; cancellation: boolean; signedCallbacks: boolean;
  noDuplicateCash: boolean; isolation: boolean; durableRestart: boolean; cacheExpiry: boolean;
  subscriptions: number; callbacks: number;
}
export function runSmoke(env?: NodeJS.ProcessEnv): Promise<Stage4SmokeReport>;
export function stage4FixtureStartTime(saved: string, realNow: number): string;
export function configureStage4IdentityProviders(sites: readonly {id: string}[], post: (path: string, body: {provider_key: string; mode: string; sender_ref: string; template_key: string}) => Promise<unknown>): Promise<void>;
