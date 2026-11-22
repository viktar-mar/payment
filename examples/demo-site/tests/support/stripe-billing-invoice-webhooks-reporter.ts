// @ts-ignore Node's offline suite executes the source reporter.
import {fixedBillingReporter} from './stripe-billing-case-reporter.ts';
// @ts-ignore Node's offline suite executes the worker protocol.
import {invoiceAnnotation,invoiceTerminalLine} from './stripe-billing-invoice-webhooks-terminal.ts';
export default fixedBillingReporter({
  spec:new URL('../stripe-billing-invoice-webhooks.spec.ts',import.meta.url),
  title:'OWNED_INITIAL_MISSED_REORDERED',project:'owned-invoice-webhooks',
  terminal:{type:invoiceAnnotation,parse:invoiceTerminalLine},
  marker:'owned_initial_missed_reordered',setupFailure:'billing_initial_missed_reordered_setup_failed',
});
