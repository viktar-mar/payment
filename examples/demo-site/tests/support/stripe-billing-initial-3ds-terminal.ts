// @ts-ignore Node's offline tests execute the same worker protocol.
import {billingTerminal} from './stripe-billing-terminal.ts';
import {isInitial3dsProvisionCheckpoint} from '../../../../infrastructure/stripe-billing-provision.mjs';
export const challengeAnnotation='owned-initial-3ds-terminal';
const {parse:plainTerminalLine,run}=billingTerminal('INITIAL_3DS','initial3dsAccepted',
 'owned_initial_3ds',challengeAnnotation,['DISPATCH','ACK','EVIDENCE'],'CHALLENGE_ORDER',
 (code,phase)=>phase==='PROVISION'&&isInitial3dsProvisionCheckpoint(code));
export const runInitial3dsCase=run;

const replayStatuses=[
 'DECISION_RECEIPT_MISSING','SCRIPT_REFERENCE_UNPROVED','SCRIPT_SLOT_UNVERIFIED','SCRIPT_FORWARD_UNCONFIRMED',
 'FRAME_UNVERIFIED','ABORT_NOT_STARTED','ABORT_UNCONFIRMED','SCRIPT_RECEIPT_MISSING','SCRIPT_READ_UNCONFIRMED',
 'SCRIPT_SIZE_REJECTED','SANITIZE_FAILED','STORAGE_UNVERIFIED','STORAGE_FAILED','ALREADY_USED',
 'CAPTURED','STRUCTURAL_ONLY','UNSUPPORTED',
] as const;
export type IssuerReplayStatus=typeof replayStatuses[number];
const replayStatusNames=new Set<string>(replayStatuses);
const replayPrefix='owned_initial_3ds_terminal phase=ACK code=BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_';

export function challengeTerminalLine(raw:unknown):string|null{
 if(typeof raw!=='string'||raw.length>180)return null;
 const pos=raw.indexOf(' replay=');
 if(pos<0)return plainTerminalLine(raw);
 const line=raw.slice(0,pos),status=raw.slice(pos+8);
 return line.startsWith(replayPrefix)&&plainTerminalLine(line)&&replayStatusNames.has(status)?raw:null;
}

export function recordInitial3dsReplayStatus(annotations:{type:string;description?:string}[],status:IssuerReplayStatus|undefined){
 if(!status||!replayStatusNames.has(status))return;
 const terminal=annotations.find(annotation=>annotation.type===challengeAnnotation);
 const line=terminal?.description;
 if(terminal&&line?.startsWith(replayPrefix)&&plainTerminalLine(line))terminal.description=line+' replay='+status;
}
