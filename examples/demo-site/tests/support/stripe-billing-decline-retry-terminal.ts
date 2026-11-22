// @ts-ignore Node's offline tests execute the same worker protocol.
import {billingTerminal} from './stripe-billing-terminal.ts';
export const retryAnnotation='owned-decline-retry-terminal';
export const {parse:retryTerminalLine,run:runDeclineRetryCase}=billingTerminal('INITIAL_DECLINE_RETRY','declineRetryAccepted',
 'owned_initial_decline_retry',retryAnnotation,['RETRY_DISPATCH','RETRY_ACK','RETRY_EVIDENCE'],'RETRY_ORDER');
