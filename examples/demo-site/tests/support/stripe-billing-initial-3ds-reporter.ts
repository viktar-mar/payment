// @ts-ignore Node's offline suite executes the source reporter.
import {fixedBillingReporter} from './stripe-billing-case-reporter.ts';
// @ts-ignore Node's offline suite executes the worker protocol.
import {challengeAnnotation,challengeTerminalLine} from './stripe-billing-initial-3ds-terminal.ts';
export default fixedBillingReporter({
  spec:new URL('../stripe-billing-initial-3ds.spec.ts',import.meta.url),
  title:'OWNED_INITIAL_3DS',project:'owned-initial-3ds',
  terminal:{type:challengeAnnotation,parse:challengeTerminalLine},
  marker:'owned_initial_3ds',setupFailure:'billing_initial_3ds_setup_failed',
});
