import {createGenericPaymentClient, mountStripePaymentAttempt, type CustomerSession} from '@generic-payments/sdk';
import './billing-first-payment.css';

// The dedicated build is inert until its single controller configures this page.
// Capabilities stay in closures. There is no recovery, storage or secret DOM state.
const button = (id: string): HTMLButtonElement => document.querySelector<HTMLButtonElement>('#' + id)!;
const code = document.querySelector<HTMLInputElement>('#code')!;
const consent = document.querySelector<HTMLInputElement>('#consent')!;
const host = document.querySelector<HTMLElement>('#provider')!;
let configured = false;
let stopped = false;
let spent = false;
let confirming = false;
let session: CustomerSession | undefined;
let handle: Awaited<ReturnType<typeof mountStripePaymentAttempt>> | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
const controller = new AbortController();
const phase = (value: string): void => {
  document.body.dataset.phase = value;
  document.querySelector('#status')!.textContent = value;
};
function stop(): void {
  if (stopped) return;
  stopped = true;
  clearTimeout(timer);
  controller.abort();
  handle?.destroy();
  handle = undefined;
  session = undefined;
  code.value = '';
  for (const element of document.querySelectorAll('button,input')) (element as HTMLInputElement).disabled = true;
  if (document.body.dataset.phase !== 'OBSERVE') phase('STOPPED');
}
window.addEventListener('pagehide', stop, {once:true});
document.addEventListener('visibilitychange', () => {if (document.visibilityState === 'hidden') stop();});

(window as any).__firstPaymentConfigure = (configuration: {backend:string;siteKey:string;email:string;deadline:number}): void => {
  if (configured || stopped || !Number.isSafeInteger(configuration.deadline) || configuration.deadline <= Date.now() || configuration.deadline > Date.now() + 300000) {stop();return;}
  configured = true;
  timer = setTimeout(stop, configuration.deadline - Date.now());
  // Exercise the SDK's existing unavailable-storage path, retaining genuine OTP
  // authority in this client only. No customer/action/session material is saved.
  try {
    for (const name of ['sessionStorage','localStorage']) {
      Object.defineProperty(window, name, {configurable:false, get:() => {throw new DOMException('Unavailable', 'SecurityError');}});
    }
  } catch {stop();return;}
  const client = createGenericPaymentClient({apiBaseUrl:configuration.backend});
  let challengeId: string | undefined;
  button('request').disabled = false;
  button('request').onclick = async () => {
    button('request').disabled = true;
    try {
      const challenge = await client.requestEmailOtp({siteKey:configuration.siteKey,email:configuration.email,intent:'AUTHENTICATE',signal:controller.signal});
      if (stopped) return;
      challengeId = challenge.challengeId;
      button('verify').disabled = false;
      code.disabled = false;
      phase('CODE');
    } catch {stop();}
  };
  button('verify').onclick = async () => {
    button('verify').disabled = true;
    const value = code.value;
    code.value = '';
    code.disabled = true;
    try {
      if (!challengeId) throw Error('state');
      session = await client.verifyEmailOtp({siteKey:configuration.siteKey,challengeId,code:value,intent:'AUTHENTICATE',signal:controller.signal});
      challengeId = undefined;
      if (stopped) {session = undefined;return;}
      consent.disabled = false;
      button('start').disabled = false;
      phase('AUTHENTICATED');
    } catch {stop();}
  };
  button('start').onclick = async () => {
    if (spent || stopped || !consent.checked || !session) {stop();return;}
    spent = true;
    button('start').disabled = true;
    consent.disabled = true;
    try {
      const original = await (window as any).__firstPaymentPrepare({token:session.sessionToken,origin:session.origin,customerId:session.customer.id,siteId:session.siteId});
      if (stopped || !session) return;
      const input = {session,subscriptionId:original.subscriptionId,operationId:original.operationId,signal:controller.signal};
      const action = await client.subscriptions.action(input);
      const context = await client.subscriptions.confirmationSession!({...input,action,idempotencyKey:crypto.randomUUID()});
      if (context.protocol !== 'SERVER_CONFIRM_V2') throw Error('protocol');
      handle = await mountStripePaymentAttempt({client:client.subscriptions,input,context,container:host,isCurrent:()=>!stopped,locale:'en',onRetire:()=>{
        button('pay').disabled = true;
        if (!confirming) stop();
      }});
      if (stopped) {handle.destroy();return;}
      button('pay').disabled = false;
      phase('READY');
    } catch {stop();}
  };
  button('pay').onclick = async () => {
    if (confirming || stopped || !handle) return;
    confirming = true;
    button('pay').disabled = true;
    phase('CONFIRMING');
    try {
      const result = await handle.confirm();
      if (stopped || 'validationFailed' in result || result.state !== 'OBSERVE' || result.retryAllowed || result.successorAttemptId !== null) throw Error('confirmation');
      phase('OBSERVE');
      stop();
    } catch {stop();}
  };
  phase('CONFIGURED');
};
