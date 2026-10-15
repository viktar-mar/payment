export type SandboxCase = 'success'|'decline'|'sca';
export interface SandboxFixture {
  identity:{endpoints:{backend:string;demo:string;receiver:string};site:{id:string;key:string};price:{id:string;key:string}};
  beginBrowser(name:SandboxCase):Promise<boolean>;
  browserSession(name:SandboxCase):Promise<Record<string,unknown>>;
  markPay(name:SandboxCase,retry?:boolean):Promise<void>;
  confirm(name:SandboxCase):Promise<boolean>;
  refunds():Promise<boolean>;
  close():Promise<void>;
}
export function openStripeSandboxFixture(options?:object):Promise<SandboxFixture>;
export function runStripeSandboxSmoke(options?:object):Promise<{paymentConfirmed:boolean;refundConfirmed:boolean;callbackVerified:boolean}>;
export function initializeStripeSandboxState(metadata:object,options?:object):Promise<void>;
export function verifyStripeSandboxOwnership(state:object,env?:Record<string,string|undefined>,execute?:Function):Promise<boolean>;
