import type {Limits,RemainderState,PriorPartialProof,RemainderProof,FinalRemainderProof} from './stripe-manual-refund-remainder-state.mjs';
import type {RemainderRuntime} from './stripe-manual-refund-remainder-runtime.mjs';
import type {RemainderCli} from './stripe-manual-refund-remainder-cli.mjs';
import type {ManualRefundDiagnostic} from './stripe-manual-refund-schema.mjs';
export interface RemainderSession {
 verifyBaseline(limits:Limits):Promise<Readonly<PriorPartialProof>>;
 rotateCallback(limits:Limits):Promise<true>;
 refundRemainder(limits:Limits):Promise<Readonly<RemainderProof>>;
 verifyFinal(limits:Limits):Promise<Readonly<FinalRemainderProof>>;
 diagnostic():Readonly<ManualRefundDiagnostic>|null;
 close(cleanupLimits:Limits):Promise<true>;
}
export function createManualRefundRemainderSession(input:Readonly<{runtime:RemainderRuntime;cli:RemainderCli;state:RemainderState}>,deps?:Record<string,unknown>):Promise<Readonly<RemainderSession>>;
