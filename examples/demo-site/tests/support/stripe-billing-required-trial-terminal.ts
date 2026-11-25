// @ts-ignore Node's offline tests execute the worker protocol from TypeScript source.
import {billingTerminal} from './stripe-billing-terminal.ts';

export const requiredTrialAnnotation='owned-required-trial-terminal';
export const {parse:requiredTrialTerminalLine,run:runRequiredTrialCase}=billingTerminal(
  'INITIAL_REQUIRED_TRIAL','requiredTrialAccepted','owned_initial_required_trial',requiredTrialAnnotation,
  ['CUSTOMER','DISPATCH','ACK','TRIAL_SETUP_PERMIT','TRIAL_SETUP_DISPATCH','TRIAL_SETUP_ACK','TRIAL_EVIDENCE'],
  'TRIAL_ORDER',undefined,false,
);
