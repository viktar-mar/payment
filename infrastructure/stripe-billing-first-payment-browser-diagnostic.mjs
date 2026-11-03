// Internal process-local provenance. Never inspect a rejected value or attach payloads.
const codes=new Set(['BROWSER_CONTEXT','BROWSER_PAGE','BROWSER_SETUP','BROWSER_NAVIGATION','BROWSER_CONFIGURE','BROWSER_OTP_REQUEST','BROWSER_OTP_VERIFY','BROWSER_PREPARE','BROWSER_ACTION_BRIDGE','BROWSER_SESSION_BRIDGE','BROWSER_CONFIRM_BRIDGE','BROWSER_WAIT_READY','BROWSER_CARD_NUMBER','BROWSER_CARD_EXPIRY','BROWSER_CARD_CVC','BROWSER_POSTAL_CHECK','BROWSER_POSTAL_FILL','BROWSER_PAY_CLICK','BROWSER_WAIT_OBSERVE','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_SUBMIT','BROWSER_WAIT_OBSERVE_LAST_OBSERVED_TOKEN','BROWSER_CLOSE','BROWSER_CONFIRM_GUARD','BROWSER_CONFIRM_STATE','BROWSER_CONFIRM_SUBMIT_VALIDATION','BROWSER_CONFIRM_SUBMIT','BROWSER_CONFIRM_TOKEN_VALIDATION','BROWSER_CONFIRM_TOKEN','BROWSER_CONFIRM_CLIENT','BROWSER_CONFIRM_NEXT_ACTION','BROWSER_CONFIRM_RETIRED','BROWSER_CONFIRM_RECEIPT']);
const metadata=new WeakMap();
export const isBrowserDiagnostic=code=>typeof code==='string'&&codes.has(code);
export function browserFailure(code){const error=new Error('first_payment_BROWSER');if(isBrowserDiagnostic(code))metadata.set(error,{code,cleanup:false});return error;}
export const browserDiagnostic=error=>metadata.get(error)?.code??null;
export const browserCleanupConfirmed=error=>metadata.get(error)?.cleanup===true;
export function finishBrowserCleanup(error,confirmed){const value=metadata.get(error);if(value)value.cleanup=confirmed===true;}
