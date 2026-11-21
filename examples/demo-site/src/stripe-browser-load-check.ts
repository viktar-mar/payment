// @ts-ignore Explicit source extension also supports the dependency-free Node runner.
import {loadStripeClient} from '../../../frontend/sdk/src/providers/stripe-loader.ts';

type State='IDLE'|'LOADING'|'READY'|'ERROR'|'STOPPED'|'TIMEOUT';
type Profile='recovery-presentation';
interface ElementPort {on(name:'ready'|'loaderror',listener:()=>void):unknown;mount(host:HTMLElement):unknown;destroy():void;}
interface Ports {
  host:HTMLElement;
  load:(key:string,profile?:Profile)=>Promise<unknown>;
  status:(state:State)=>void;
  onPageLoss:(listener:()=>void)=>()=>void;
  deadline:(listener:()=>void)=>()=>void;
}
const testKey=(key:unknown):key is string=>typeof key==='string'&&/^pk_test_[A-Za-z0-9]+$/.test(key);

/** Private page controller: never receives an operation, secret, or financial method. */
export function createLoadController(ports:Ports){
  let state:State='IDLE',element:ElementPort|undefined;
  let holdReady=false;
  let removeLoss=()=>{},cancelDeadline=()=>{};
  function finish(next:State){
    if(state!=='LOADING'&&!(state==='READY'&&holdReady)&&!(state==='IDLE'&&next==='STOPPED'))return;
    state=next;
    try{element?.destroy();}catch{state='ERROR';}finally{element=undefined;removeLoss();cancelDeadline();ports.status(state);}
  }
  return Object.freeze({
    state:()=>state,
    stop:()=>finish('STOPPED'),
    async configure(key:unknown,profile?:unknown){
      if(!testKey(key)||(profile!==undefined&&profile!=='recovery-presentation')||
        state!=='IDLE'||!ports.host.isConnected)throw Error('CONFIGURATION');
      holdReady=profile==='recovery-presentation';
      state='LOADING';ports.status(state);
      ports.host.inert=true;ports.host.tabIndex=-1;ports.host.style.pointerEvents='none';
      removeLoss=ports.onPageLoss(()=>finish('STOPPED'));
      cancelDeadline=ports.deadline(()=>finish('TIMEOUT'));
      try{
        const stripe=await ports.load(key,profile) as {elements(options:unknown):{create(type:'payment',options:unknown):ElementPort}}|null;
        if(state!=='LOADING')return;
        if(!stripe||!ports.host.isConnected){finish('ERROR');return;}
        const elements=stripe.elements({mode:holdReady?'subscription':'payment',amount:2000,currency:'eur',
          allowedPaymentMethodTypes:['card'],...(holdReady?{locale:'en'}:{})});
        const mounted=elements.create('payment',{wallets:{applePay:'never',googlePay:'never',...(holdReady?{link:'never'}:{})}});
        element=mounted;
        let mountReturned=false, sawReady=false;
        const reportReady=()=>{
          if(state!=='LOADING')return;
          sawReady=true;
          if(!mountReturned)return;
          if(holdReady){state='READY';ports.status(state);}
          else finish('READY');
        };
        mounted.on('loaderror',()=>finish('ERROR'));
        if(state!=='LOADING')return;
        mounted.on('ready',reportReady);
        if(state!=='LOADING')return;
        mounted.mount(ports.host);
        mountReturned=true;
        if(sawReady)reportReady();
      }catch{finish('ERROR');}
    },
  });
}

async function loadForBrowserCheck(key:string,profile?:Profile) {
  if(profile===undefined){
    const {loadStripe} = await import('@stripe/stripe-js/pure/index.js');
    // The probe bundle resolves both imports to the same pure loader instance.
    loadStripe.setLoadParameters({advancedFraudSignals:false});
  }
  return loadStripeClient(key);
}

// No loader or provider acquisition until the launcher explicitly configures this page.
if(typeof document!=='undefined'){
  const host=document.getElementById('provider-host');
  const output=document.getElementById('status');
  if(host&&output){
    const controller=createLoadController({host,load:loadForBrowserCheck,status:state=>{output.textContent=state;},
      onPageLoss:listener=>{window.addEventListener('pagehide',listener,{once:true});return ()=>window.removeEventListener('pagehide',listener);},
      deadline:listener=>{const timer=setTimeout(listener,60000);return ()=>clearTimeout(timer);},
    });
    Object.defineProperty(window,'configureStripeBrowserLoadCheck',{value:(key:unknown,profile?:unknown)=>controller.configure(key,profile),writable:false,configurable:false});
  }
}
