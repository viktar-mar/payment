// @ts-ignore The offline Node suite loads the original reporter.
import {fixedBillingReporter} from './stripe-billing-case-reporter.ts';
// @ts-ignore The offline Node suite loads the worker protocol from TypeScript source.
import {optionalTrialAnnotation,optionalTrialTerminalLine} from './stripe-billing-optional-trial-terminal.ts';

export default fixedBillingReporter({
  spec:new URL('../stripe-billing-optional-trial.spec.ts',import.meta.url),
  title:'OWNED_OPTIONAL_TRIAL_PAUSE_RESUME',project:'owned-optional-trial',
  terminal:{type:optionalTrialAnnotation,parse:optionalTrialTerminalLine},
  marker:'owned_optional_trial_pause_resume',setupFailure:'billing_optional_trial_setup_failed',
});
