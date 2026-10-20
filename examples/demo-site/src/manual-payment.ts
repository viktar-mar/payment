import {defineGenericPaymentWidget,GenericPaymentWidget} from '@generic-payments/sdk';
import {armManualPayment} from '../../../frontend/sdk/src/diagnostics/manual-payment-authorization';
import {exact,object,validateSession} from '../../../frontend/sdk/src/contracts';
import type {CheckoutSession} from '../../../frontend/sdk/src/contracts';
interface ManualPaymentDependencies {readonly signal?:AbortSignal;readonly now?:()=>number;readonly fetch?:typeof fetch;}
type LoadFailureCode='SESSION_HTTP_FORBIDDEN'|'SESSION_HTTP_GONE'|'SESSION_HTTP_ERROR'|'SESSION_HTTP_UNAVAILABLE'|'SESSION_JSON_INVALID'|'SESSION_INVALID'|'WIDGET_SETUP_FAILED'|'AUTHORIZATION_BINDING_FAILED'|'WIDGET_MOUNT_FAILED';

/** Human-owned one-shot page. Neither card fields nor provider error details are read. */
export function mountManualPaymentPage(root:HTMLElement,dependencies:ManualPaymentDependencies={}):{close():void}{
  const now=dependencies.now??Date.now,request=dependencies.fetch??globalThis.fetch,created=now();
  const title=document.createElement('h1');title.textContent='Только Stripe Sandbox';
  const warning=document.createElement('p');warning.textContent='Один тестовый платёж €20. Реальную карту не вводите. Не обновляйте страницу.';
  const instructions=document.createElement('p');instructions.textContent='Нажмите «Загрузить форму» один раз. Введите 4242 4242 4242 4242, будущую дату и любой трёхзначный CVC. Нажмите Pay один раз. При любой ошибке остановитесь и сообщите оператору.';
  const start=document.createElement('button');start.type='button';start.textContent='Загрузить форму';
  const status=document.createElement('p');status.setAttribute('role','status');status.setAttribute('aria-live','polite');
  const host=document.createElement('div');root.append(title,warning,instructions,start,status,host);
  let started=false,closed=false,resumed=false,allowResume=false,attempted=false,authorizing=false,widgetWasConnected=false,deadline=created+300000;
  let widget:GenericPaymentWidget|undefined,lease:ReturnType<typeof armManualPayment>|undefined,changes:MutationObserver|undefined;
  const exchange=new AbortController();let timer:ReturnType<typeof setTimeout>|undefined=setTimeout(expire,300000);
  function close():void{
    if(closed)return;closed=true;start.disabled=true;exchange.abort();clearTimeout(timer);changes?.disconnect();lease?.close();widget?.remove();
    start.removeEventListener('click',begin);window.removeEventListener('pagehide',close);dependencies.signal?.removeEventListener('abort',close);
  }
  function finish(message='Попытка завершена. Окончательный результат проверяет оператор по серверному отчёту. Закройте эту вкладку.'):void{if(closed)return;status.textContent=message;close();}
  function expire():void{finish('Время истекло. Повторная оплата недоступна. Закройте эту вкладку.');}
  function active():boolean{if(closed)return false;if(!Number.isFinite(now())||now()>=deadline||widgetWasConnected&&!widget?.isConnected){expire();return false;}return true;}
  async function post(path:string,httpFailure?:(status:number)=>void):Promise<string>{
    // Headers and body share one immutable HTTP budget, including late delivery.
    const httpDeadline=Math.min(now()+10000,deadline),controller=new AbortController();
    const abort=()=>controller.abort();exchange.signal.addEventListener('abort',abort,{once:true});if(exchange.signal.aborted)abort();
    let httpTimer:ReturnType<typeof setTimeout>|undefined,cancel:(()=>void)|undefined;
    const check=()=>{if(controller.signal.aborted||!active()||now()>=httpDeadline)throw null;};
    const work=(async()=>{
      check();const response=await request(path,{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json'},body:'{}',signal:controller.signal});
      try{check();if(!response.ok){httpFailure?.(response.status);throw null;}const wire=await response.text();check();return wire;}
      catch{if(response.body)void response.body.cancel().catch(()=>{});throw null;}
    })();
    try{
      const cancelled=new Promise<never>((_,reject)=>{cancel=()=>reject(null);controller.signal.addEventListener('abort',cancel,{once:true});httpTimer=setTimeout(abort,Math.max(1,httpDeadline-now()));if(controller.signal.aborted)cancel();});
      const wire=await Promise.race([work,cancelled]);check();return wire;
    }finally{clearTimeout(httpTimer);exchange.signal.removeEventListener('abort',abort);if(cancel)controller.signal.removeEventListener('abort',cancel);}
  }
  async function authorize():Promise<true>{
    if(attempted||!active())throw Error('CONFIRMATION_BLOCKED');attempted=true;authorizing=true;
    // Keep the provider generation connected while its sole operation settles.
    // The page exposes no more actions, even if provider validation rejects.
    widget!.hidden=true;status.textContent='Одна попытка подтверждения. Окончательный результат проверяет оператор по серверному отчёту.';
    try{
      const wire=await post('/manual-payment-confirmation');if(!active()||wire.length>32)throw null;
      // Exact literal also excludes duplicate keys and extra fields.
      if(wire!=='{"authorized":true}')throw null;return true;
    }catch{finish();throw Error('CONFIRMATION_BLOCKED');}finally{authorizing=false;}
  }
  function guard(event:Event):void{
    const target=event.composedPath()[0];
    if(!attempted&&active()&&target instanceof HTMLButtonElement&&(allowResume||target.getAttribute('aria-label')==='Pay'))return;
    event.preventDefault();event.stopImmediatePropagation();
  }
  function inspect():void{
    if(!active()||!widget)return;
    if(!resumed){const resume=widget.shadowRoot?.querySelectorAll('button')[1];if(resume&&!resume.hidden&&!resume.disabled){resumed=true;allowResume=true;try{resume.click();}finally{allowResume=false;}}}
    if(attempted){
      if(!authorizing&&['checking','processing','requires_action','succeeded','failed','unknown','expired'].includes(widget.state))finish(widget.state==='succeeded'?'Браузер сообщил об успехе. Окончательный результат проверяет оператор по серверному отчёту. Закройте эту вкладку.':undefined);
    }else if(['failed','unknown','expired','succeeded','processing','requires_action'].includes(widget.state))finish();
  }
  async function begin():Promise<void>{
    if(started||!active())return;started=true;start.disabled=true;status.textContent='Загрузка формы…';
    // Only locally assigned stages and numeric HTTP statuses reach the terminal UI.
    let loadFailure:LoadFailureCode='SESSION_HTTP_UNAVAILABLE';
    try{
      const wire=await post('/manual-payment-session',status=>{loadFailure=status===403?'SESSION_HTTP_FORBIDDEN':status===410?'SESSION_HTTP_GONE':'SESSION_HTTP_ERROR';});if(!active())return;
      if(wire.length>8192){loadFailure='SESSION_INVALID';throw null;}loadFailure='SESSION_JSON_INVALID';const record:unknown=JSON.parse(wire);loadFailure='SESSION_INVALID';
      if(!object(record)||!exact(record,['version','apiBaseUrl','siteKey','priceKey','checkoutId','checkoutToken','expiresAt','providerType','confirmationMode','state'])||record.version!==2||record.providerType!=='STRIPE'||record.confirmationMode!=='CLIENT_PROVIDER')throw null;
      const session={...record,providerClientPayload:{}} as unknown as CheckoutSession;validateSession(session);
      if(!/^http:\/\/127\.0\.0\.1:[1-9][0-9]{3,4}$/.test(session.apiBaseUrl)||Number(new URL(session.apiBaseUrl).port)<1024||Number(new URL(session.apiBaseUrl).port)>65535||session.apiBaseUrl===location.origin
        ||session.state.status!=='REQUIRES_PAYMENT_METHOD'||session.state.amountMinor!==2000||session.state.currency!=='EUR'||session.state.price?.version!==1||session.state.price.taxMode!=='NONE'||Date.parse(session.expiresAt)<=now())throw null;
      deadline=Math.min(deadline,Date.parse(session.expiresAt));clearTimeout(timer);timer=setTimeout(expire,Math.max(0,deadline-now()));
      loadFailure='WIDGET_SETUP_FAILED';
      defineGenericPaymentWidget();widget=new GenericPaymentWidget();widget.apiBaseUrl=session.apiBaseUrl;widget.siteKey=session.siteKey;widget.priceKey=session.priceKey;widget.locale='en';
      // A separate SDK graph is unknown to this internal registry and fails here.
      loadFailure='AUTHORIZATION_BINDING_FAILED';lease=armManualPayment(widget,{deadline,authorize,session});
      loadFailure='WIDGET_MOUNT_FAILED';widget.addEventListener('click',guard,true);widget.addEventListener('keydown',guard,true);
      for(const name of ['ready','succeeded','failed','unknown','expired','requires-action','processing'])widget.addEventListener(name,()=>{
        if(attempted&&!authorizing&&name==='ready')finish();else inspect();
      });
      changes=new MutationObserver(inspect);changes.observe(widget.shadowRoot!,{subtree:true,attributes:true,childList:true});changes.observe(document,{subtree:true,childList:true});
      host.append(widget);widgetWasConnected=widget.isConnected;status.textContent='Введите только тестовую карту. Нажмите Pay один раз.';inspect();
    }catch{finish('Не удалось загрузить форму. Повторная оплата недоступна. Закройте эту вкладку. '+loadFailure);}
  }
  start.addEventListener('click',begin);window.addEventListener('pagehide',close);dependencies.signal?.addEventListener('abort',close,{once:true});if(dependencies.signal?.aborted)close();
  return Object.freeze({close});
}
const root=typeof document==='undefined'?null:document.getElementById('manual-payment');if(root)mountManualPaymentPage(root);
