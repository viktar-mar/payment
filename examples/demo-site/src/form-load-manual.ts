import {defineGenericPaymentWidget,GenericPaymentWidget} from '@generic-payments/sdk';
import {armFormLoadDiagnostic} from '../../../frontend/sdk/src/diagnostics/form-load-observer';
import {recoverCheckout,storageKey} from '../../../frontend/sdk/src/storage';
import {exact,object,validateSession} from '../../../frontend/sdk/src/contracts';
import type {CheckoutSession} from '../../../frontend/sdk/src/contracts';
import {classifyFormDiagnosticSample,validateFormDiagnosticSample} from '../../../infrastructure/stripe-form-diagnostic-schema.mjs';
import type {FormDiagnosticSample} from '../../../infrastructure/stripe-form-diagnostic-schema.mjs';

import {createManualFormReadinessObserver} from './manual-form-readiness';
import {unavailableManualFormEvidence} from '../../../infrastructure/stripe-manual-form-evidence.mjs';

interface ManualPageDependencies {readonly signal?:AbortSignal;readonly now?:()=>number;readonly fetch?:typeof fetch;}
const states=['idle','loading','ready','checking','processing','requires_action','succeeded','failed','unknown','expired'];
const pending=(result:string)=>result.startsWith('WAITING_')||['ELEMENT_SETUP_INCOMPLETE','WIDGET_HANDOFF_INCOMPLETE'].includes(result);

/** One ordinary browser activation; no provider capability is exposed by this page. */
export function mountManualFormPage(root:HTMLElement,dependencies:ManualPageDependencies={}):{close():void}{
  const now=dependencies.now??Date.now,request=dependencies.fetch??globalThis.fetch;
  const title=document.createElement('h1');title.textContent='Ручная проверка — без оплаты';
  const warning=document.createElement('p');warning.textContent='Не вводите карту и не нажимайте Pay. Не обновляйте страницу';
  const start=document.createElement('button');start.type='button';start.textContent='Загрузить форму';
  const status=document.createElement('p');status.setAttribute('role','status');status.setAttribute('aria-live','polite');
  const host=document.createElement('div');root.append(title,warning,start,status,host);
  let started=false,closed=false,startTime=0,terminal=false,pendingSent=false,resumed=false,allowResume=false,ownedKey:string|undefined;
  let widget:GenericPaymentWidget|undefined,observation:ReturnType<typeof armFormLoadDiagnostic>|undefined,changes:MutationObserver|undefined;
  let checkpoint:ReturnType<typeof setTimeout>|undefined,limit:ReturnType<typeof setTimeout>|undefined;
  let readiness:ReturnType<typeof createManualFormReadinessObserver>|undefined;
  const exchange=new AbortController();
  function close():void{
    if(closed)return;closed=true;start.disabled=true;exchange.abort();
    clearTimeout(checkpoint);clearTimeout(limit);changes?.disconnect();readiness?.close();observation?.close();widget?.remove();
    if(ownedKey)try{sessionStorage.removeItem(ownedKey);}catch{/* Best-effort owned recovery disposal. */}
    start.removeEventListener('click',begin);window.removeEventListener('pagehide',onPageHide);dependencies.signal?.removeEventListener('abort',onAbort);
    widget?.removeEventListener('click',guard,true);
  }
  function sample():Readonly<FormDiagnosticSample>|null{
    if(!widget?.isConnected||!observation||!widget.shadowRoot)return null;
    try{
      const shadow=widget.shadowRoot,element=shadow.querySelector('.payment-element'),pay=shadow.querySelector<HTMLButtonElement>('button[aria-label="Pay"]');
      const visible=(node:HTMLElement):boolean=>{
        for(let current:HTMLElement|null=node;current;current=current.parentElement??(current.getRootNode() instanceof ShadowRoot?(current.getRootNode() as ShadowRoot).host as HTMLElement:null)){
          const style=getComputedStyle(current);if(current.hidden||style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||style.opacity==='0')return false;
        }
        return node.isConnected;
      };
      const elapsed=now()-startTime;
      return validateFormDiagnosticSample({...observation.snapshot(),widget_state:states.includes(widget.state)?widget.state:'UNAVAILABLE',
        element_state:element?(element.querySelector('iframe')?'FRAME':'EMPTY'):'NO_HOST',pay_state:!pay?'ABSENT':pay.hidden?'HIDDEN':pay.disabled?'DISABLED':'ENABLED',
        pay_visible:!!pay&&visible(pay),bucket:elapsed>=60000?'AT_LIMIT':elapsed<15000?'BEFORE_15S':'AFTER_15S'});
    }catch{return null;}
  }
  function report(value:Readonly<FormDiagnosticSample>,final:boolean):void{
    if(terminal||(final?false:pendingSent))return;
    const evidence=readiness?.snapshot()??unavailableManualFormEvidence();
    if(final)terminal=true;else pendingSent=true;
    void request('/manual-observation',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:2,sample:value,evidence})}).catch(()=>{});
  }
  function finish():void{
    if(closed)return;const value=sample();if(value&&!terminal)report(value,true);
    status.textContent='Время проверки истекло. Закройте эту вкладку.';close();
  }
  function guard(event:Event):void{
    const target=event.composedPath()[0];
    if(target instanceof HTMLButtonElement&&(allowResume||target.getAttribute('aria-label')==='Pay'))return;
    event.preventDefault();event.stopImmediatePropagation();
  }
  function inspect():void{
    if(closed||!widget)return;if(now()-startTime>=60000){finish();return;}
    if(!resumed){
      const resume=widget.shadowRoot?.querySelectorAll('button')[1];
      if(resume&&!resume.hidden&&!resume.disabled){resumed=true;allowResume=true;try{resume.click();}finally{allowResume=false;}}
    }
    if(terminal)return;
    readiness?.snapshot();
    const value=sample();if(!value||!resumed)return;
    const result=classifyFormDiagnosticSample(value);
    if(result.startsWith('FORM_READY_')){
      status.textContent='Форма сообщила о готовности. Проверьте, видны ли поля; ничего не вводите';
      report(value,true);readiness?.close();observation?.close();
    }else if(['CONFIRMATION_BLOCKED','BINDING_LOST','ABORTED','OPERATION_FAILED','ELEMENT_LOADERROR'].includes(result)){
      report(value,true);status.textContent='Проверка формы завершена. Закройте эту вкладку.';close();
    }else if(pending(result)&&now()-startTime>=15000)report(value,false);
  }
  async function begin():Promise<void>{
    if(started||closed)return;started=true;start.disabled=true;startTime=now();
    readiness=createManualFormReadinessObserver({getWidget:()=>widget,isActive:()=>!closed&&!terminal&&now()-startTime<60000,
      readinessSnapshot:()=>observation?.readinessSnapshot()});
    // The original local window starts before the first asynchronous handoff.
    checkpoint=setTimeout(()=>{if(now()-startTime>=60000){finish();return;}inspect();},15000);
    limit=setTimeout(finish,60000);status.textContent='Загрузка формы…';
    try{
      const response=await request('/manual-session',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json'},body:'{}',signal:exchange.signal});
      if(closed||now()-startTime>=60000){close();return;}if(!response.ok)throw null;
      const wire=await response.text();if(closed||now()-startTime>=60000){close();return;}
      if(wire.length>8192)throw null;
      const record:unknown=JSON.parse(wire);
      if(!object(record)||!exact(record,['version','apiBaseUrl','siteKey','priceKey','checkoutId','checkoutToken','expiresAt','providerType','confirmationMode','state'])
        ||record.version!==2||record.providerType!=='STRIPE'||record.confirmationMode!=='CLIENT_PROVIDER')throw null;
      const session={...record,providerClientPayload:{}} as unknown as CheckoutSession;validateSession(session);
      if(!/^http:\/\/127\.0\.0\.1:[1-9][0-9]{3,4}$/.test(session.apiBaseUrl)||Number(new URL(session.apiBaseUrl).port)<1024
        ||session.apiBaseUrl===location.origin||session.state.status!=='REQUIRES_PAYMENT_METHOD'||session.state.amountMinor!==2000||session.state.currency!=='EUR'
        ||session.state.price?.version!==1||session.state.price.taxMode!=='NONE'||Date.parse(session.expiresAt)<=now())throw null;
      const key=storageKey(session);if(sessionStorage.getItem(key)!==null)throw null;
      ownedKey=key;sessionStorage.setItem(key,wire);if(!recoverCheckout(session))throw null;
      if(closed||now()-startTime>=60000){close();return;}
      defineGenericPaymentWidget();widget=new GenericPaymentWidget();
      widget.apiBaseUrl=session.apiBaseUrl;widget.siteKey=session.siteKey;widget.priceKey=session.priceKey;widget.locale='en';
      observation=armFormLoadDiagnostic(widget);readiness.snapshot();widget.addEventListener('click',guard,true);
      changes=new MutationObserver(inspect);changes.observe(widget.shadowRoot!,{subtree:true,attributes:true,childList:true});
      host.append(widget);inspect();
    }catch{if(!closed){status.textContent='Не удалось загрузить форму. Закройте эту вкладку.';close();}}
  }
  function onPageHide():void{close();}
  function onAbort():void{close();}
  start.addEventListener('click',begin);window.addEventListener('pagehide',onPageHide);dependencies.signal?.addEventListener('abort',onAbort,{once:true});
  if(dependencies.signal?.aborted)close();
  return Object.freeze({close});
}

const root=typeof document==='undefined'?null:document.getElementById('manual-form');
if(root)mountManualFormPage(root);
