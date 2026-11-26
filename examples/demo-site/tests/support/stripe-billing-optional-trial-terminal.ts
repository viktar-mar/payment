import {isOptionalTrialPayDiagnostic,isOptionalTrialResumeDiagnostic} from '../../../../infrastructure/stripe-billing-optional-trial-run.mjs';
// @ts-ignore Node's offline tests execute the worker protocol from TypeScript source.
import {billingTerminal} from './stripe-billing-terminal.ts';

export const optionalTrialAnnotation = 'owned-optional-trial-terminal';
export const {run:runOptionalTrialCase,parse:optionalTrialTerminalLine} = billingTerminal(
  'OPTIONAL_TRIAL_PAUSE_RESUME','optionalTrialAccepted','owned_optional_trial_pause_resume',optionalTrialAnnotation,
  ['CLI_PROFILE','CUSTOMER','OPEN_TRIAL','ADVANCE','EVIDENCE','PAUSE_PROOF','SETUP_PERMIT','SETUP_INSTALL','RESUME','OPEN_PROOF','PAY','PAID_PROOF'],
  'OPTIONAL_TRIAL_ORDER',(code,phase)=>code==='CLI_PROFILE_EXPIRED'
    || (phase==='RESUME' && isOptionalTrialResumeDiagnostic(code))
    || (phase==='PAY' && isOptionalTrialPayDiagnostic(code)),false,
);
