// @ts-ignore Node's offline tests execute the same worker protocol.
import {billingTerminal} from './stripe-billing-terminal.ts';
import {isInitial3dsProvisionCheckpoint} from '../../../../infrastructure/stripe-billing-provision.mjs';
export const challengeAnnotation='owned-initial-3ds-terminal';
export const {parse:challengeTerminalLine,run:runInitial3dsCase}=billingTerminal('INITIAL_3DS','initial3dsAccepted',
 'owned_initial_3ds',challengeAnnotation,['DISPATCH','ACK','EVIDENCE'],'CHALLENGE_ORDER',
 (code,phase)=>phase==='PROVISION'&&isInitial3dsProvisionCheckpoint(code));
