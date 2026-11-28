// @ts-ignore The offline Node suite loads the original reporter.
import {fixedBillingReporter} from './stripe-billing-case-reporter.ts';
// @ts-ignore The offline Node suite loads the worker protocol from TypeScript source.
import {requiredTrialAnnotation,requiredTrialTerminalLine} from './stripe-billing-required-trial-terminal.ts';

export default fixedBillingReporter({
  spec:new URL('../stripe-billing-required-trial.spec.ts',import.meta.url),
  title:'OWNED_INITIAL_REQUIRED_TRIAL',project:'owned-required-trial',
  terminal:{type:requiredTrialAnnotation,parse:requiredTrialTerminalLine},
  marker:'owned_initial_required_trial',setupFailure:'billing_initial_required_trial_setup_failed',
});
