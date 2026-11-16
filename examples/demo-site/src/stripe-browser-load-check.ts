// @ts-ignore Explicit source extension also supports the dependency-free Node runner.
import {loadStripeClient} from '../../../frontend/sdk/src/providers/stripe-loader.ts';

type State='IDLE'|'LOADING'|'READY'|'ERROR'|'STOPPED'|'TIMEOUT';
interface ElementPort {on(name:'ready'|'loaderror',listener:()=>void):unknown;mount(host:HTMLElement):unknown;destroy():void;}
interface Ports {
  host:HTMLElement;
  load:(key:string)=>Promise<unknown>;
  status:(state:State)=>void;
  onPageLoss:(listener:()=>void)=>()=>void;
  deadline:(listener:()=>void)=>()=>void;
}
const testKey=(key:unknown):key is string=>typeof key==='string'&&/^pk_test_[A-Za-z0-9]+$/.test(key);

/** Private page controller: never receives an operation, secret, or financial method. */
export function createLoadController(ports:Ports){
  let state:State='IDLE',element:ElementPort|undefined;
  let removeLoss=()=>{},cancelDeadline=()=>{};
  function finish(next:State){
    if(state!=='LOADING'&&!(state==='IDLE'&&next==='STOPPED'))return;
    state=next;
    try{element?.destroy();}catch{state='ERROR';}finally{element=undefined;removeLoss();cancelDeadline();ports.status(state);}
  }
  return Object.freeze({
    state:()=>state,
    stop:()=>finish('STOPPED'),
    async configure(key:unknown){
      if(!testKey(key)||state!=='IDLE'||!ports.host.isConnected)throw Error('CONFIGURATION');
      state='LOADING';ports.status(state);
      ports.host.inert=true;ports.host.tabIndex=-1;ports.host.style.pointerEvents='none';
      removeLoss=ports.onPageLoss(()=>finish('STOPPED'));
      cancelDeadline=ports.deadline(()=>finish('TIMEOUT'));
      try{
        const stripe=await ports.load(key) as {elements(options:unknown):{create(type:'payment',options:unknown):ElementPort}}|null;
        if(state!=='LOADING')return;
        if(!stripe||!ports.host.isConnected){finish('ERROR');return;}
        const elements=stripe.elements({mode:'payment',amount:2000,currency:'eur',allowedPaymentMethodTypes:['card']});
        element=elements.create('payment',{wallets:{applePay:'never',googlePay:'never'}});
        element.on('ready',()=>finish('READY'));
        element.on('loaderror',()=>finish('ERROR'));
        element.mount(ports.host);
      }catch{finish('ERROR');}
    },
  });
}

// No loader or provider acquisition until the launcher explicitly configures this page.
if(typeof document!=='undefined'){
  const host=document.getElementById('provider-host');
  const output=document.getElementById('status');
  if(host&&output){
    const controller=createLoadController({host,load:loadStripeClient,status:state=>{output.textContent=state;},
      onPageLoss:listener=>{window.addEventListener('pagehide',listener,{once:true});return ()=>window.removeEventListener('pagehide',listener);},
      deadline:listener=>{const timer=setTimeout(listener,60000);return ()=>clearTimeout(timer);},
    });
    Object.defineProperty(window,'configureStripeBrowserLoadCheck',{value:(key:unknown)=>controller.configure(key),writable:false,configurable:false});
  }
}
