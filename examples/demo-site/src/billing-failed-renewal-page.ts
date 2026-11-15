import type * as Payments from '@generic-payments/sdk';

type Sdk = Pick<typeof Payments, 'createGenericPaymentClient'|'mountStripePaymentAttempt'|'mountStripeSubscriptionMethod'>;
type Configuration = {backend:string;siteKey:string;email:string;deadline:number};
type Operation = {subscriptionId:string;operationId:string};

/** Capabilities live only in the original document and original SDK client. */
export function installFailedRenewalPage(sdk:Sdk):void {
  const button = (id:string) => document.querySelector<HTMLButtonElement>('#'+id)!;
  const code = document.querySelector<HTMLInputElement>('#code')!;
  const consent = document.querySelector<HTMLInputElement>('#consent')!;
  const host = document.querySelector<HTMLElement>('#provider')!;
  const controller = new AbortController();
  let state = 'NEW', deadline = 0, sessionDeadline = Infinity;
  let timer:ReturnType<typeof setTimeout>|undefined;
  let initial:Payments.StripePaymentAttemptHandle|undefined, method:Payments.StripeSubscriptionHandle|undefined;
  let client:ReturnType<Sdk['createGenericPaymentClient']>|undefined, session:Payments.CustomerSession|undefined;
  let original:Operation|undefined, replacement:Operation|undefined;
  function phase(next:string) {
    state = next;
    document.body.dataset.phase = next;
    document.querySelector('#status')!.textContent = next;
  }
  function stop() {
    if (state === 'STOPPED') return;
    phase('STOPPED');
    // The owner may be between browser phases. Revoke that run even when no
    // controller call is currently waiting on a browser result.
    try {void Promise.resolve((window as any).__failedRenewalTerminal('STOPPED')).catch(()=>{});} catch { /* page is closing */ }
    clearTimeout(timer);
    controller.abort();
    initial?.destroy(); method?.destroy();
    initial = undefined; method = undefined; session = undefined; client = undefined;
    code.value = '';
    for (const e of document.querySelectorAll<HTMLInputElement>('button,input')) e.disabled = true;
  }
  function arm(until:number) {
    clearTimeout(timer);
    const left = Math.min(deadline,sessionDeadline,until) - Date.now();
    if (!Number.isFinite(left) || left <= 0) {stop();return;}
    timer = setTimeout(stop,left);
  }
  function check(expected:string) {
    if (state !== expected || controller.signal.aborted || Date.now() >= Math.min(deadline,sessionDeadline) ||
        window !== window.top || document.visibilityState === 'hidden') throw Error('closed');
  }
  function take(expected:string,next:string) {check(expected);phase(next);}
  function expose(name:string, callback:Function) {
    Object.defineProperty(window,name,{value:callback,enumerable:false,configurable:false,writable:false});
  }
  window.addEventListener('pagehide',stop,{once:true});
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')stop();});
  expose('__failedRenewalStop',stop);
  expose('__failedRenewalConfigure',(c:Configuration)=>{
    try {
      if (state!=='NEW' || !Number.isSafeInteger(c.deadline) || c.deadline<=Date.now() ||
          c.deadline>Date.now()+2700000 || !/^http:\/\/127\.0\.0\.1:[1-9]\d{0,4}$/.test(c.backend)) throw Error();
      deadline = c.deadline;
      check('NEW');
      for (const name of ['sessionStorage','localStorage']) {
        Object.defineProperty(window,name,{configurable:false,get:()=>{throw new DOMException('Unavailable','SecurityError');}});
      }
      client = sdk.createGenericPaymentClient({apiBaseUrl:c.backend});
      let challengeId:string|undefined;
      button('request').disabled = false;
      button('request').onclick = async () => {
        try {
          take('CONFIGURED','REQUESTING');
          button('request').disabled = true;
          const challenge = await client!.requestEmailOtp({siteKey:c.siteKey,email:c.email,intent:'AUTHENTICATE',signal:controller.signal});
          check('REQUESTING');
          challengeId = challenge.challengeId;
          await (window as any).__failedRenewalChallenge(challengeId);
          check('REQUESTING');
          code.disabled = false; button('verify').disabled = false; phase('CODE');
        } catch {stop();}
      };
      button('verify').onclick = async () => {
        try {
          take('CODE','VERIFYING');
          button('verify').disabled = true; code.disabled = true;
          const value = code.value; code.value = '';
          const verified = await client!.verifyEmailOtp({siteKey:c.siteKey,challengeId:challengeId!,code:value,intent:'AUTHENTICATE',signal:controller.signal});
          challengeId = undefined;
          check('VERIFYING');
          sessionDeadline = Date.parse(verified.expiresAt);
          check('VERIFYING');
          session = verified;
          consent.disabled = false; button('start').disabled = false;
          phase('AUTHENTICATED'); arm(Date.now()+300000);
        } catch {stop();}
      };
      phase('CONFIGURED'); arm(Date.now()+300000);
    } catch {stop();}
  });
  button('start').onclick = async () => {
    try {
      take('AUTHENTICATED','PREPARING');
      if (!consent.checked || !session || !client) throw Error();
      button('start').disabled = true; consent.disabled = true;
      const result:Operation = await (window as any).__failedRenewalPrepare({token:session.sessionToken,origin:session.origin,
        customerId:session.customer.id,siteId:session.siteId,expiresAt:session.expiresAt});
      check('PREPARING');
      original = result;
      const input = {session,...original,signal:controller.signal};
      const action = await client.subscriptions.action(input);
      check('PREPARING');
      const context = await client.subscriptions.confirmationSession!({...input,action,idempotencyKey:crypto.randomUUID()});
      check('PREPARING');
      if(context.protocol!=='SERVER_CONFIRM_V2')throw Error();
      const handle = await sdk.mountStripePaymentAttempt({client:client.subscriptions,input,context,container:host,
        isCurrent:()=>!controller.signal.aborted,locale:'en',onRetire:()=>{if(state!=='CONFIRMING')stop();}});
      if(controller.signal.aborted){handle.destroy();return;}
      initial = handle;
      phase('READY');
    } catch {stop();}
  };
  button('pay').onclick = async event => {
    try {
      if(!event.isTrusted || button('pay').disabled)throw Error();
      take('READY','CONFIRMING');
      button('pay').disabled = true;
      const result = await initial!.confirm();
      check('CONFIRMING');
      if ('validationFailed' in result || result.state!=='OBSERVE' || result.retryAllowed!==false || result.successorAttemptId!==null) throw Error();
      initial!.destroy(); initial = undefined;
      phase('OBSERVE'); arm(deadline);
    } catch {stop();}
  };
  expose('__failedRenewalMethod',async (operation:Operation)=>{
    try {
      take('OBSERVE','MOUNTING_METHOD');
      if (!client || !session || operation.subscriptionId!==original?.subscriptionId || operation.operationId===original.operationId)throw Error();
      replacement = operation;
      arm(Date.now()+300000);
      const handle = await sdk.mountStripeSubscriptionMethod({client:client.subscriptions,session,...operation,container:host,locale:'en',
        signal:controller.signal,isCurrent:()=>!controller.signal.aborted,onRetire:()=>{if(state!=='CONFIRMING_METHOD')stop();}});
      if(controller.signal.aborted){handle.destroy();return;}
      method = handle; phase('METHOD_READY');
    } catch {stop();}
  });
  button('confirm-method').onclick = async event => {
    try {
      if(!event.isTrusted || button('confirm-method').disabled)throw Error();
      take('METHOD_READY','CONFIRMING_METHOD'); button('confirm-method').disabled = true;
      const result = await method!.confirm();
      check('CONFIRMING_METHOD');
      if(result.operationId!==replacement?.operationId || result.subscriptionId!==original?.subscriptionId ||
          result.status!=='RESERVED' || result.actionAvailable!==false || result.nextAction!==undefined)throw Error();
      method!.destroy(); method = undefined;
      phase('METHOD_CONFIRMED'); arm(deadline);
    } catch {stop();}
  };
}
