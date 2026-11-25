// @ts-ignore Node's offline suite executes the source reporter.
import {fixedBillingReporter} from './stripe-billing-case-reporter.ts';
// @ts-ignore Node's offline suite executes the worker protocol.
import {cancellationAnnotation,cancellationTerminalLine} from './stripe-billing-cancellation-terminal.ts';
export default fixedBillingReporter({
  spec:new URL('../stripe-billing-cancellation.spec.ts',import.meta.url),
  title:'OWNED_PERIOD_END_CANCELLATION',project:'owned-period-end-cancellation',
  terminal:{type:cancellationAnnotation,parse:cancellationTerminalLine},
  marker:'owned_period_end_cancellation',setupFailure:'billing_cancellation_setup_failed',
});
