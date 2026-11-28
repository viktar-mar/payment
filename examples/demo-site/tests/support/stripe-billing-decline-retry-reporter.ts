// @ts-ignore Node's offline suite executes the source reporter.
import {fixedBillingReporter} from './stripe-billing-case-reporter.ts';
// @ts-ignore Node's offline suite executes the worker protocol.
import {retryAnnotation,retryTerminalLine} from './stripe-billing-decline-retry-terminal.ts';
export default fixedBillingReporter({
  spec:new URL('../stripe-billing-decline-retry.spec.ts',import.meta.url),
  title:'OWNED_INITIAL_DECLINE_RETRY',project:'owned-initial-decline-retry',
  terminal:{type:retryAnnotation,parse:retryTerminalLine},
  marker:'owned_initial_decline_retry',setupFailure:'billing_decline_retry_setup_failed',
});
