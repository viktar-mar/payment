/** Descriptor-only admission; returns only canonical flags accepted by existing owned capabilities. */
export function remainingOnlyEnvironment(env:unknown):Readonly<Record<string,string|undefined>>|null;
export function remainingOnlyOptIn(env:unknown):boolean;
export function remainingOnlyExcluded(env:unknown):boolean;
