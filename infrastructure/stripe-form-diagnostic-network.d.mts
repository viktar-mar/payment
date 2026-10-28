export type ObservedFormFetchDestination='OWN_APP'|'STRIPE_API'|'STRIPE_JS'|'OTHER'|'UNKNOWN';
export type ObservedFormFetchFailureCode='ABORTED'|'NAME_NOT_RESOLVED'|'CONNECTION_REFUSED'|'CONNECTION_RESET'|'TIMED_OUT'|'CERTIFICATE'|'BLOCKED_BY_CLIENT'|'GENERIC_FAILED'|'UNKNOWN';
export type FormFetchDestination=ObservedFormFetchDestination|'NONE'|'UNAVAILABLE';
export type FormFetchFailureCode=ObservedFormFetchFailureCode|'NONE'|'UNAVAILABLE';
export function classifyFormFetchDestination(rawUrl:unknown,ownedOrigins:readonly string[]):ObservedFormFetchDestination;
export function classifyFormFetchFailure(rawFailure:unknown):ObservedFormFetchFailureCode;
