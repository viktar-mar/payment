// @ts-ignore Node's offline tests execute the same worker protocol.
import {billingTerminal} from './stripe-billing-terminal.ts';
export const cancellationAnnotation='owned-period-end-cancellation-terminal';
export const {parse:cancellationTerminalLine,run:runCancellationCase}=billingTerminal('INITIAL_CANCEL_WITHDRAW','cancellationAccepted',
 'owned_period_end_cancellation',cancellationAnnotation,['DISPATCH','ACK','EVIDENCE','GRAPH','CANCELLATION_DISPATCH','CANCELLATION_EVIDENCE'],'CANCELLATION_ORDER');
