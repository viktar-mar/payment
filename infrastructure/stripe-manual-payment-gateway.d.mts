import type {PrivateSession} from './stripe-manual-payment-options.mjs';
import type {IncomingMessage,Server,ServerResponse} from 'node:http';
export interface Limits {readonly deadline:number;readonly signal?:AbortSignal;}
export interface ManualPaymentGatewayDependencies {
  readonly createServer?:(handler:(request:IncomingMessage,response:ServerResponse)=>void)=>Server;
  readonly fetch?:typeof globalThis.fetch;
  readonly randomBytes?:(size:number)=>Buffer;
  readonly now?:()=>number;
  readonly setTimeout?:(callback:()=>void,delay:number)=>unknown;
  readonly clearTimeout?:(handle:unknown)=>void;
}
export function createManualPaymentGateway(options:{readonly origin:string;readonly assetEndpoint:string;readonly session:PrivateSession;readonly threeDSecure?:true;readonly authorizeConfirmation:()=>Promise<true>},limits:Limits,deps?:ManualPaymentGatewayDependencies):{
  open():Promise<{readonly url:string}>;
  /** A single durable permission grant is not payment or delivery proof. */
  wait():Promise<{readonly outcome:'CONFIRMATION_GRANTED'|'NOT_STARTED'|'FAILED'}>;
  /** True requires owned server, sockets, responses and work to have settled. */
  close(limits:Limits):Promise<boolean>;
};
