// @ts-ignore Node's offline tests execute the same worker protocol.
import {billingTerminal} from './stripe-billing-terminal.ts';
export const invoiceAnnotation='owned-invoice-webhooks-terminal';
export const {parse:invoiceTerminalLine,run:runInvoiceWebhooksCase}=billingTerminal('INITIAL_MISSED_REORDERED','invoiceWebhooksAccepted',
 'owned_initial_missed_reordered',invoiceAnnotation,['INGRESS','DISPATCH','ACK','INVOICE_GRANT','INVOICE_PAIR','INVOICE_MISSED','INVOICE_RECONCILE','INVOICE_RECOVERED','INVOICE_DELIVERY','INVOICE_PROCESSED'],'INVOICE_ORDER');
