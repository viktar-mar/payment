import {defineGenericPaymentWidget,GenericPaymentWidget} from '@generic-payments/sdk';
import {armFormLoadDiagnostic} from '../../../frontend/sdk/src/diagnostics/form-load-observer';
import {ownedProviderHost} from '../../../frontend/sdk/src/widget/provider-mount';

type Identity={readonly apiBaseUrl:string;readonly siteKey:string;readonly priceKey:string};
const stateDomain=['idle','loading','ready','checking','processing','requires_action','succeeded','failed','unknown','expired'];
// No arbitrary payload, input, frame, console or error data crosses this facade.
try {
  const identity=(globalThis as typeof globalThis&{__formDiagnosticSession?:Identity}).__formDiagnosticSession;
  if(!identity||document.querySelector('generic-payment-widget'))throw null;
  defineGenericPaymentWidget();
  const widget=new GenericPaymentWidget();
  widget.apiBaseUrl=identity.apiBaseUrl;widget.siteKey=identity.siteKey;widget.priceKey=identity.priceKey;widget.locale='en';
  const observation=armFormLoadDiagnostic(widget);
  const root=document.getElementById('diagnostic');if(!root)throw null;
  root.append(widget);
  const facade=Object.freeze({
    handshake:()=>widget.isConnected&&document.querySelectorAll('generic-payment-widget').length===1&&['ARMED','BOUND'].includes(observation.snapshot().binding),
    snapshot:()=>{
      if(!widget.isConnected||document.querySelectorAll('generic-payment-widget').length!==1)return null;
      const shadow=widget.shadowRoot;if(!shadow)return null;
      const host=ownedProviderHost(widget),pay=shadow.querySelector<HTMLButtonElement>('button[aria-label="Pay"]');
      return Object.freeze({...observation.snapshot(),widget_state:stateDomain.includes(widget.state)?widget.state:'UNAVAILABLE',
        element_state:host?(host.querySelector('iframe')?'FRAME':'EMPTY'):'NO_HOST',
        pay_state:!pay?'ABSENT':pay.hidden?'HIDDEN':pay.disabled?'DISABLED':'ENABLED'});
    },
  });
  Object.defineProperty(globalThis,'__formLoadDiagnostic',{value:facade,writable:false,configurable:false});
}catch {
  // Missing facade is the only failure signal. No raw exception is retained.
}
